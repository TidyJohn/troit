import { createPairingHandler } from "./handler.ts";

const url = Deno.env.get("SUPABASE_URL");
const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
const publishableKey = Deno.env.get("SUPABASE_ANON_KEY");
if (!url || !serviceKey || !publishableKey) throw new Error("Missing server authentication configuration.");
Deno.serve(createPairingHandler({ url, serviceKey, publishableKey }));
