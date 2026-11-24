# TV pairing deployment and validation

TV pairing uses an eight-character short code. On the TV open **Account → Pair with phone**. On a signed-in phone or computer open **Devices → Pair a TV**, enter the TV code, check the displayed device/account, and choose **Connect this TV**. The TV receives a new Supabase session belonging to that account; the phone's refresh token is never copied.

## Activate the server

1. Apply [20261115010000_troit_device_pairing.sql](migrations/20261115010000_troit_device_pairing.sql) through the existing project's authorized migration connection or SQL Editor. The migration is additive and does not change existing account or music data.
2. From this project, deploy the included Edge Function using an authenticated Supabase CLI:

   ```powershell
   supabase functions deploy device-pairing --project-ref cabbqhpxdxqzqqkbfpve --no-verify-jwt
   ```

   [supabase/config.toml](config.toml) also sets `verify_jwt = false` for this function. TV creation/polling are intentionally available before sign-in and use a high-entropy possession secret. Phone inspection and approval remain authenticated database RPCs. The function never accepts a user ID or account email supplied by an anonymous client.

3. Confirm the hosted function has Supabase's server-provided `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY` and `SUPABASE_ANON_KEY`. These are Edge server variables, never app configuration. No service key should be pasted into Kotlin, an APK or a Windows package. The function uses only Web APIs and `fetch`; no third-party Edge dependency or extra secret is required.

4. Enable Supabase Cron (`pg_cron`) in the dashboard and schedule credential cleanup every minute through SQL Editor or the Cron dashboard:

   ```sql
   select cron.schedule('troit-pairing-cleanup', '* * * * *',
     'select public.troit_pairing_cleanup();');
   ```

   This scrubs expired encrypted delivery envelopes and prunes abandoned rows even when no devices are pairing. Delivery access always expires after 90 seconds; the cleanup job removes stored ciphertext by its next run. Creation also runs cleanup as a fallback. The migration deliberately does not install an extension or assume Cron exists in isolated tests. See [Supabase Cron](https://supabase.com/docs/guides/cron).

This workspace had no authenticated Supabase CLI or deployment credentials. Local checks prepare and verify the implementation; they do not activate pairing on the live project. Until deployment, the app shows a server-update state and normal email/password sign-in remains available.

## Boundaries and security

- Codes expire after ten minutes and exclude ambiguous characters. Pairing IDs are random UUIDs; TV poll capabilities use 256 random bits and stay in TV memory only. The database stores capability/code hashes. Approval is bound to the exact inspected request, signed-in account and code; another account cannot replace an approval.
- Signed-in phones get at most ten inspections/minute and five approvals/minute. Anonymous creation is bounded to five requests per ten minutes per proxy-IP HMAC fingerprint, plus a global creation/storage cap. Polling is capped at forty requests/minute per TV. Raw IP addresses are not stored. Proxy/IP throttling supplements possession-secret entropy and the authenticated approval requirement; it is not a complete public abuse-prevention service.
- The Edge Function obtains the approved account from private SQL state and uses Supabase Auth's admin generate-link and token-hash verification endpoints to issue an independent session without sending an email. Accounts must already have a confirmed email. Pairing refuses accounts with verified MFA factors so it cannot silently bypass an MFA challenge.
- SQL grants a single issuance lease. A crashed issuance fails closed after 45 seconds; request a new code rather than minting the same session twice. A completed issuance has an AES-GCM encrypted delivery window of at most 90 seconds, so a lost HTTP response can recover the same session. The encryption key is derived from the server service key; rotating that key invalidates in-flight delivery envelopes. Successful completion, explicit cancellation and expired-cache reads scrub the envelope; the required cleanup job removes expired bytes without further client traffic.
- The TV verifies the returned access token with Supabase Auth before saving it. Signing in/out elsewhere in the local process, changing the account generation, or closing the pairing dialog during verification prevents adoption. Closing a dialog drops the local capability immediately. Explicit Cancel also attempts server cancellation; a disconnected TV's abandoned record expires automatically.
- The Edge response uses `Cache-Control: no-store`. No passwords, poll secrets or issued credentials are logged. RLS and denied direct client access protect both pairing tables. Only the server role can create, poll, save or cancel delivery envelopes; authenticated clients can inspect and approve only through the dedicated RPCs.

## Offline checks

```powershell
node supabase/tests/pairing.integration.mjs
node supabase/tests/pairing-edge.test.mjs
.\gradlew.bat :desktop:test --tests app.tunes.DevicePairingTest
```

The SQL suite runs against isolated PostgreSQL/WASM (the existing `build/social-db-check` PGlite installation). The Edge suite uses Node 24's TypeScript stripping and local `fetch` fixtures; it does not issue real Auth sessions. Client tests intercept all network calls and use temporary account directories. They cover approval boundaries, session verification, expiry, cancellation and late account-change responses. After deployment, perform a live TV/phone pairing, a cancelled/expired code attempt, a lost-response retry and sign-out during pairing before release.

The Auth contracts follow the official [generateLink](https://supabase.com/docs/reference/javascript/auth-admin-generatelink), [verifyOtp](https://supabase.com/docs/reference/javascript/auth-verifyotp) and [Edge authentication](https://supabase.com/docs/guides/functions/auth) documentation. Server responses are handled directly using the same REST paths as Supabase's Auth client.
