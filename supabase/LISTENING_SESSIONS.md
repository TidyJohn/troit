# Party queues and sleep timers

Apply [20261115030000_troit_listening_sessions.sql](migrations/20261115030000_troit_listening_sessions.sql) after the social and ecosystem migrations. It adds private room/membership/request/timer tables and restricted authenticated RPCs. No Edge Function, service key in an app, or scheduled job is needed for these two features. It preserves the existing ecosystem receiver behind permission-checking wrappers; apply migrations in filename order rather than reapplying an older ecosystem function over the wrappers.

This workspace has no authenticated live database connection. The migration and isolated checks prepare the feature; they do not activate it in the configured Supabase project.

## Party queues

Open Party queue, name a room and choose **Host a room**. Share its eight-character code with accepted Troit friends. Friends enter the code, inspect the room/host and explicitly join. The host's online player supplies the audio; joining does not start another copy on every guest device. Guests can search and append songs. The host keeps the normal playback, skip, reorder/remove and device controls. Up to twenty participants can join a room; the shared queue remains limited to 1,000 songs and its existing metadata bound.

The host's Troit app and playback receiver must remain online. A request is confirmed only when that receiver applies it; guests see pending, stale, expired or failed status when appropriate. Requests retain one UUID through lost-response retries. A queue or track change during an upload requires refresh instead of appending to an unexpected session. Guest authorization must be at most one second old when the native queue changes, including the validation round trip; a slow reply or stalled engine rejects the request and requires refresh.

Rooms last up to 24 hours or until the host ends them. Leaving, host removal, blocking or removing friendship revokes visibility and pending requests. Claimed requests are checked again immediately before native queue application. Removed guests cannot simply re-enter the same room code. Room codes never authorize anonymous access, expose email addresses, copy the host's tokens or permit guests to pause/skip another account's player. Room creation prunes records expired for more than seven days; addition receipt history is bounded to one day.

## Sleep timers

Choose 15, 30 or 60 minutes, or **End of this song**. Signed-in devices share one account timer; any connected device can inspect or cancel it. Countdown deadlines come from server time and follow the active player through handoff. The target carries the deadline into its native alarm before activation, and server activation rejects a transfer that was loading while the timer elapsed. A new explicit playback request after a completed timer is allowed.

End-of-song timers arm the native completion fence, including repeat-one, gapless playback, radio and crossfade. The current track stops before another is started. The completion counter distinguishes natural completion from a manual skip. The timer follows the same song/index during a transfer and cancels if the user selects a different track.

Countdown enforcement runs in the native audio service/engine, independently of network polling. The receiver consumes each timer once and checks account, active device/runtime and local playback intent before applying remote work. Cached authorized deadlines can finish through a temporary network outage. Remote cancellation and changes made on another device require a successful connection to arrive. A late response must not pause music that the user has explicitly resumed after the alarm.

Guest mode and an unavailable timer migration offer a local timer saved in this device's app data. Local timers are labelled accordingly, do not share across devices and are cleared when changing accounts. They continue while Troit's audio engine/service runs; Android can stop an idle service and a force-stop or exiting the Windows app ends enforcement. No app can enforce a timer after its process is stopped.

## Validation

```powershell
node supabase/tests/sessions.integration.mjs
.\gradlew.bat :desktop:test --tests app.tunes.PartyTimerTest --tests app.tunes.PartyTimerRenderTest --tests app.tunes.EcosystemPartyFreshnessTest --tests app.tunes.NativeTransitionsTest
```

The SQL suite runs in isolated PostgreSQL/WASM with fixture identities. Client tests use fixture RPCs and temporary app directories. They cover account isolation, UUID retries, guest permissions, cancelled/claimed room requests, timer revisions, local persistence, natural completion, changing devices, native deadline arming and delayed claims. They do not create real accounts or issue production commands.

After migration activation, check a real host/guest room, concurrent requests, removal/blocking, host app exit, end-of-song with repeat-one, a timer during crossfade, cancellation from another device, timer deadlines during a delayed handoff, and Android service survival with the activity closed.
