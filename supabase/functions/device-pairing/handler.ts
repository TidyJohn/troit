// No third-party runtime dependency. The service key never leaves this server.
type Json = Record<string, unknown>;
type Configuration = { url: string; serviceKey: string; publishableKey: string; fetch?: typeof fetch };
const codePattern = /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$/;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const secretPattern = /^[0-9a-f]{64}$/;
const bytes = (value: string) => new TextEncoder().encode(value);
const hex = (buffer: ArrayBuffer) => Array.from(new Uint8Array(buffer), b => b.toString(16).padStart(2, "0")).join("");
const digest = async (value: string) => hex(await crypto.subtle.digest("SHA-256", bytes(value)));
const base64 = (value: Uint8Array) => btoa(String.fromCharCode(...value));
const unbase64 = (value: string) => Uint8Array.from(atob(value), c => c.charCodeAt(0));

export function createPairingHandler(configuration: Configuration) {
  const url = configuration.url.replace(/\/$/, "");
  const fetcher = configuration.fetch ?? fetch;
  const encryptionKey = crypto.subtle.digest("SHA-256", bytes("troit-pairing-session-v1:" + configuration.serviceKey))
    .then(hash => crypto.subtle.importKey("raw", hash, "AES-GCM", false, ["encrypt", "decrypt"]));
  async function upstream(path: string, body?: Json, admin = true): Promise<Json> {
    const key = admin ? configuration.serviceKey : configuration.publishableKey;
    const response = await fetcher(url + path, {
      method: body == null ? "GET" : "POST",
      headers: { "Content-Type": "application/json", apikey: key, Authorization: `Bearer ${key}` },
      body: body == null ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(12_000),
    });
    const text = await response.text();
    if (text.length > 128_000) throw new Error("upstream");
    const json = JSON.parse(text);
    if (!response.ok) throw new Error(json.code === "PGRST202" ? "setup" : json.message?.includes("Too many pairing") ? "rate" : "upstream");
    return json;
  }
  const rpc = (name: string, body: Json) => upstream(`/rest/v1/rpc/troit_pairing_${name}`, body);
  async function encrypt(session: Json, id: string) {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const encrypted = await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: bytes(id) }, await encryptionKey, bytes(JSON.stringify(session)));
    return base64(iv) + "." + base64(new Uint8Array(encrypted));
  }
  async function decrypt(envelope: string, id: string): Promise<Json> {
    const [iv, ciphertext] = envelope.split(".");
    return JSON.parse(new TextDecoder().decode(await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: unbase64(iv), additionalData: bytes(id) }, await encryptionKey, unbase64(ciphertext))));
  }
  async function issue(userId: string): Promise<Json> {
    const response = await upstream(`/auth/v1/admin/users/${userId}`);
    const user = (response.user ?? response) as Json;
    // Pairing must not create/confirm a new account or sidestep a verified MFA challenge.
    if (user.id !== userId || typeof user.email !== "string" || !user.email_confirmed_at || user.is_anonymous ||
      (Array.isArray(user.factors) && user.factors.some(factor => factor.status === "verified"))) throw new Error("account");
    const link = await upstream("/auth/v1/admin/generate_link", { type: "magiclink", email: user.email });
    if (typeof link.hashed_token !== "string") throw new Error("upstream");
    const session = await upstream("/auth/v1/verify", { token_hash: link.hashed_token, type: "magiclink" }, false);
    const sessionUser = session.user as Json | undefined;
    if (sessionUser?.id !== userId || typeof session.access_token !== "string" || typeof session.refresh_token !== "string") throw new Error("upstream");
    return { access_token: session.access_token, refresh_token: session.refresh_token, token_type: "bearer",
      expires_in: session.expires_in, expires_at: session.expires_at, user: { id: sessionUser.id, email: sessionUser.email } };
  }
  function reply(body: Json, status = 200) {
    return new Response(JSON.stringify(body), { status, headers: {
      "Content-Type": "application/json", "Cache-Control": "no-store, private", "Pragma": "no-cache",
      "X-Content-Type-Options": "nosniff",
    } });
  }
  return async (request: Request): Promise<Response> => {
    if (request.method !== "POST") return reply({ error: "POST required." }, 405);
    if (!request.headers.get("content-type")?.startsWith("application/json")) return reply({ error: "JSON required." }, 415);
    try {
      // Stream-bounded reads also cover requests with no Content-Length header.
      const reader = request.body?.getReader();
      if (!reader) return reply({ error: "Missing request." }, 400);
      let raw = ""; let size = 0;
      const decoder = new TextDecoder();
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > 4096) { await reader.cancel(); return reply({ error: "Request too large." }, 413); }
        raw += decoder.decode(value, { stream: true });
      }
      raw += decoder.decode();
      const body = JSON.parse(raw);
      if (!body || !uuidPattern.test(body.id) || !secretPattern.test(body.secret)) return reply({ error: "Invalid pairing request." }, 400);
      const secretHash = await digest(body.secret);
      if (body.action === "create") {
        const code = typeof body.code === "string" ? body.code.replace(/[-\s]/g, "").toUpperCase() : "";
        if (!codePattern.test(code) || typeof body.device_name !== "string" || !body.device_name.trim() || body.device_name.length > 64)
          return reply({ error: "Invalid pairing request." }, 400);
        // Proxy-provided IP is HMACed; raw IPs and pairing capabilities are never stored/logged.
        const ip = (request.headers.get("x-forwarded-for") ?? "unknown").split(",")[0].trim().slice(0, 128);
        const rateKey = await crypto.subtle.importKey("raw", bytes(configuration.serviceKey), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
        const requesterHash = hex(await crypto.subtle.sign("HMAC", rateKey, bytes("pair-create:" + ip)));
        const result = await rpc("create", { p_id: body.id, p_code_hash: await digest(code), p_secret_hash: secretHash,
          p_device_name: body.device_name.trim(), p_requester_hash: requesterHash });
        return reply({ ...result, code: code.slice(0, 4) + "-" + code.slice(4) });
      }
      if (body.action === "cancel" || body.action === "complete") {
        await rpc("cancel", { p_id: body.id, p_secret_hash: secretHash, p_complete: body.action === "complete" });
        return reply({ status: "cancelled" });
      }
      if (body.action !== "poll") return reply({ error: "Unsupported pairing request." }, 400);
      const lease = crypto.randomUUID();
      const result = await rpc("poll", { p_id: body.id, p_secret_hash: secretHash, p_lease: lease });
      if (result.status === "ready") return reply({ status: "ready", session: await decrypt(String(result.encrypted_session), body.id) });
      if (result.status !== "issue") return reply({ status: result.status });
      // SQL grants one lease only. A crashed issuance is failed closed, never generated twice.
      const session = await issue(String(result.user_id));
      await rpc("save", { p_id: body.id, p_secret_hash: secretHash, p_lease: lease, p_encrypted_session: await encrypt(session, body.id) });
      return reply({ status: "ready", session });
    } catch (failure) {
      const reason = failure instanceof Error ? failure.message : "upstream";
      return reply({ error: reason === "setup" ? "TV pairing needs the server update." : reason === "rate" ? "Too many pairing attempts. Please wait."
        : reason === "account" ? "Use normal sign-in for this account." : "Pairing could not finish. Try again shortly.",
        code: reason === "setup" ? "setup_required" : "pairing_failed" }, reason === "setup" ? 503 : reason === "rate" ? 429 : 400);
    }
  };
}
