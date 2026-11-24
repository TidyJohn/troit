# Troit

A shared Kotlin Compose music app for Windows, Android phones and Android TV, with bundled Manrope typography and a black-and-white interface.

## Run and build

Use JDK 17 and an Android SDK configured in `local.properties`.

```powershell
.\gradlew.bat :desktop:run
.\gradlew.bat :desktop:packageExe
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

The desktop player uses the bundled `desktop/resources/windows/mpv.exe`. Install Git LFS before cloning, or run `git lfs pull` in an existing checkout to download the Windows player.

## Start listening

On Windows and phones, **Discover** opens with Search, Liked songs, and Downloads. Recently played songs appear here once you have listening history. Recommendations appear as each catalogue responds. Select a song to play it, or select an album or playlist to browse its tracks.

On TV, use the arrow keys and OK to choose a destination. **Library → Pair with phone** brings a signed-out TV into your account. **Now playing** has the music video and playback modes.

## Android TV and connected devices

Android v0.14 uses one APK for phones and Android TV (Android 8.0/API 26 or newer). TV devices open a dedicated landscape interface with large focus highlights, D-pad navigation, Discover, Search, Library, Now playing, account sign-in and Devices. The TV remote supports transport keys and ten-second seek controls. The manifest includes the TV launcher entry and 320×180 banner, with no touchscreen requirement, following the [Android TV app requirements](https://developer.android.com/training/tv/get-started/create).

Build `:app:assembleRelease`, then install `app/build/outputs/apk/release/app-release.apk` on your TV using your usual APK installer or an authorized ADB connection. Open **Troit** from the TV's apps launcher. This is an installable TV build; a Play Store listing is not included.

Sign in to the same Troit account on Windows v1.12.1, Android phone and TV. Open **Devices & playback** using **Devices** in the phone or Windows header or the TV footer. It shows the current playback device, song, artist and estimated progress, with previous/play/pause/next controls. The phone/PC player follows another device automatically while local audio is paused. Playback polls about every two seconds and devices/queue metadata about every three seconds, plus network time, with slower retries offline. Progress between reports is estimated from the server timestamp and capped at the track duration.

**Choose a playback device** lists up to 20 recently connected players, including idle receivers. **Rename** saves names such as Living room TV or My PC across apps and widgets. A device heartbeat refreshes every 15 seconds and on local interaction; devices become offline after 45 seconds without a heartbeat and are removed after 30 days. **Play on this device** moves the complete queue, index, playback mode and reported position to an online receiver. **Listen here** uses that same full-queue path between updated apps. With the [handoff preload migration](supabase/migrations/20261124000000_troit_handoff_preload.sql) applied and an updated receiver, the destination resolves the song while the source keeps playing. The source then confirms its pause and the server rechecks ownership before the destination starts. Older receivers use the existing pause-first flow. A failed switch leaves the prepared destination paused. An explicit new local playback session also takes ownership, and the old online app pauses on its next successful ownership check. This is handoff and remote control; it does not synchronize multi-room audio.

Open **Shared queue** from Devices, **Shared queue** on TV, or **Edit shared queue** inside the desktop/phone now-playing queue. Use **Add songs**, or a song row's queue-options menu, for **Play next** / **Add to queue**. Upcoming songs can move up/down or be removed, including separate occurrences of the same recording. History and the currently audible song stay fixed, so edits preserve its decoder and position. Requests carry the observed queue/track revision and one UUID; concurrent or stale edits require a refresh. Queues support up to 1,000 songs within an 800 KB metadata limit. Only changed queue content is uploaded; unchanged polls return metadata and reuse cached songs. Account changes clear that cache. Destination music filters can reject a queue rather than play excluded songs. Older clients without shared-queue publication retain the historical handoff fallback, which can contain only the current song.

For TV sign-in, open **Account → Pair with phone**. On an already signed-in phone or PC choose **Devices → Pair a TV**, enter the eight-character code, inspect the displayed TV/account, and approve. Codes expire after ten minutes. The TV gets its own verified account session; the phone's credentials are not copied. Normal email/password sign-in remains available. See [TV pairing deployment and validation](supabase/DEVICE_PAIRING.md).

The Windows app must remain running, including minimized. Android/TV controls and playback publication belong to the MediaSession service and continue while that service is alive, even with the activity closed; Android may stop an idle paused service. Force-stop, app exit or no network makes that device unavailable. Old snapshots remain labelled offline and their controls are disabled after 45 seconds. Controls display confirmation or a pending/failure state; a lost response never silently replays a skip. Account changes discard old playback state and queued controls.

Basic playback controls reuse the existing authenticated [Troit Connect migration](supabase/migrations/20261111030000_troit_connect.sql). Device switching and full shared queues require the additive [ecosystem migration](supabase/migrations/20261115000000_troit_ecosystem.sql), applied after Connect. TV pairing requires the additive [pairing migration](supabase/migrations/20261115010000_troit_device_pairing.sql), the included Edge Function, and its documented cleanup schedule. Apply migrations through an authorized migration connection or SQL Editor; deploy the function using the commands in [DEVICE_PAIRING.md](supabase/DEVICE_PAIRING.md). This workspace has no authenticated deployment connection, so these server changes are prepared and tested locally, not activated in the live project. The app reports a server-update state until activation; existing basic controls and password sign-in remain usable.

Validation: `node supabase/tests/connect.integration.mjs` covers PC/phone/TV ownership and controls. `PlaybackConnectTest`, `PlaybackEcosystemTest`, `ConnectUiTest` and the TV rendering/navigation tests cover safe retries, account changes, takeover, handoff and layouts. Real TV remote input, Android background service survival and a live multi-device session still require device acceptance testing; this workspace has no connected Android device or TV emulator.

The new release also has isolated `ecosystem.integration.mjs`, `pairing.integration.mjs` and `pairing-edge.test.mjs` suites, plus `EcosystemV2Test`, `EcosystemUiTest`, `DevicePairingTest` and `NativeQueueEditTest`. The native queue test uses local silent WAV audio through bundled mpv to verify that queue edits keep the current decoder/position and that transfers prepare paused. After live activation, check phone-to-TV pairing, moves between all three platforms, queue edits during playback, target/source interaction during a delayed switch, expired codes, sign-out and network recovery on real devices.

The Windows title bar blends into the app: it takes the theme background, and has no border, title text or icon. Standard Windows window controls, snapping and resizing are retained. It uses the [Windows DWM attributes](https://learn.microsoft.com/en-us/windows/win32/api/dwmapi/ne-dwmapi-dwmwindowattribute) and [`SetWindowThemeAttribute`](https://learn.microsoft.com/en-us/windows/win32/api/uxtheme/nf-uxtheme-setwindowthemeattribute).

`:desktop:packageExe` writes the one Windows release, the installer `desktop/build/compose/binaries/main/exe/Troit-<version>.exe`. It installs for the current user under `%LOCALAPPDATA%\Troit` with no administrator prompt, adds Start menu and desktop shortcuts, and replaces an earlier installed version. Uninstall from **Settings → Apps**.

The local release APK is non-debuggable and uses the existing development signing key so it can replace the earlier local APK. Code shrinking remains disabled to preserve the music extractor's reflection behavior. The Android application ID and existing Windows data folder remain unchanged, so the rename preserves local playlists, account sessions, and music preferences.

## Likes, lyrics, timers, parties and transitions

Open the header's **More** menu (also on TV) for **Your listening** (liked songs, history and stats), **Lyrics**, **Sleep timer**, **Party queue** and **Playback settings** (transitions and volume levelling). Library also links to Your listening. Song hearts respond immediately; the song options menu retains **Save to playlist** alongside shared-queue actions.

- **Liked songs and history:** hearts and recent listening are saved by account and synced between devices, with an offline journal and retry receipts. History records actual playback after a few seconds, including when the Windows window is minimized or Android's playback service owns the audio. Guest data remains local. Limits are 1,000 favorites and 500 recent entries; offline history older than 30 days stays local. Conflicting heart changes ask you to refresh and choose again.
- **Lyrics:** matching [LRCLIB](https://lrclib.net/docs.txt) lyrics highlight and scroll with the local or connected player's position. TV uses large text and artwork. Manual scrolling has a return-to-follow action. Plain lyrics, instrumental tracks and missing matches have explicit states; availability and timestamp accuracy depend on the provider and recording match. No lyrics API key is required.
- **Sleep timer:** choose 15, 30 or 60 minutes, or **End of this song**. Signed-in devices can view or cancel the account timer, and countdowns follow playback handoff. The native engine enforces a received deadline through a temporary network outage. End-of-song stops before repeat, gapless, crossfade or radio can advance. Guest mode or an unavailable server migration offers a clearly labelled local timer. Remote changes need a connection, and enforcement requires the app's audio process/service to remain alive.
- **Party queue:** host a room and share its code with accepted Troit friends. They inspect and join the room, then search and request song additions to the host's queue. The host retains playback and queue controls; guests see confirmation or a pending/failure state. Rooms allow 20 participants and expire after 24 hours. Removal, leaving or lost friendship revokes access. The host's app and receiver must remain online.
- **Gapless and crossfade:** gapless is enabled by default; crossfade is off by default and can overlap queued tracks for 1–12 seconds, capped for short tracks. Windows uses native mpv prefetch or two actual audio decoders; Android uses Media3 preloading or two ExoPlayers. Settings persist on each playback device. Source resolution, a slow connection or unavailable next audio can still cause a gap. Radio cannot preload a recommendation that has not yet been fetched.

Apply migrations in filename order, after the existing social, Connect and ecosystem setup: [20261115020000_troit_listening.sql](supabase/migrations/20261115020000_troit_listening.sql), then [20261115030000_troit_listening_sessions.sql](supabase/migrations/20261115030000_troit_listening_sessions.sql). These enable synced likes/history, party rooms and account timers; no new Edge Function is needed. They are prepared and tested locally, and **have not been applied to the live Supabase project** because this workspace has no authenticated deployment connection. Lyrics, local likes/history/timers and native transitions work without these migrations. See [party and timer activation details](supabase/LISTENING_SESSIONS.md).

Validation includes isolated database suites (`listening.integration.mjs`, `sessions.integration.mjs`), account/offline/retry fixtures, phone/TV renders and actual bundled-mpv tests for gapless progression, overlapping crossfade, pause/resume, failed standby recovery, repeat-one fences and a sleep deadline during a blocked source lookup. Android compilation and lint do not prove device audio behavior. Check real phone/TV focus changes, remote input, background service survival, timer handoff/cancellation and host/guest sessions after server activation.

```powershell
node supabase/tests/listening.integration.mjs
node supabase/tests/sessions.integration.mjs
.\gradlew.bat :desktop:test --tests app.tunes.ListeningLibraryTest --tests app.tunes.LyricsTest --tests app.tunes.LyricsRenderTest --tests app.tunes.PartyTimerTest --tests app.tunes.PartyTimerRenderTest --tests app.tunes.NativeTransitionsTest
.\gradlew.bat :app:assembleRelease :desktop:packageExe
```

## Downloads, video, sharing, stats and volume

Added in Windows v1.13.2 and Android v0.17.

- **Offline downloads:** choose **Download** in a song's ⋮ menu, or **Download all** on a playlist or album. Downloaded songs show a small arrow and play from disk on that device, with no connection. **Library → Downloads** (also in the More menu) lists them with the space used, plays them, and removes one or all. Files are the original audio stream, fetched in ranged chunks into the app's own data folder, and are per device: they are not synced. Downloads run one at a time while Troit is open; closing the app stops the queue, and a failed or cancelled download leaves no partial file. There is no automatic size limit. Artwork needs a connection unless it is already cached.
- **Music videos:** **Video** in the Windows or phone player bar, **Show music video** in its expanded player, or **Music video** on the TV's Now playing screen opens the song's music video in a small panel beside the app: a side column on wide windows, a strip above the player on phones, in place of the queue on TV. It is not a dialog and not a separate player. The music is never touched: the picture is a second, silent stream timed against the audio that is already playing, so showing or hiding it cannot interrupt, reload or re-seek the song, and gapless, crossfade, the sleep timer and remote controls are unaffected. Pausing holds the picture, seeking moves it, and each new song brings its own video. Windows reads frames from a second bundled mpv and releases each when the audio clock reaches it; Android and Android TV run a second, muted player that is nudged or re-seeked against the audio position. The player checks the song title, artist credits, catalogue video type, and upload title. It skips lyric videos, audio-only releases, and unrelated recordings. Duration breaks ties between comparable matches. An official cut with an opening scene can differ in timing from the album recording. When a matching video is unavailable, the panel shows that status and the song keeps playing. The Windows picture is 480×270, sized for the panel.
- **Friend activity:** the Friends list shows what each accepted friend is playing right now, with a play button. **Show friends what I'm playing** in the same list turns your own sharing off; it is on by default. Only the song is shared, never the device name, position or history. It reads the playback row that devices already publish, updates about every 15 seconds while Friends is open, and needs the [friend activity migration](supabase/migrations/20261116000000_troit_friend_activity.sql) applied after the social and Connect migrations. Until then nothing is shown and friends and chat work as before.
- **Sharing songs in chat:** **Send to a friend** in a song's ⋮ menu or on an album/playlist page, and the note button beside the sticker button in a conversation (sends what you are playing). The message is ordinary text with a YouTube Music link, so it needs no server change and an older app shows a readable title and link; current apps draw a card that plays the song or opens the album. Saved personal playlists have no link and cannot be shared this way.
- **Listening stats:** **Your listening → Stats** shows play counts, top songs and top artists for the last 30 days or everything recorded. It is counted on the device from the existing history, so it covers the most recent 500 plays and works for guests.
- **Volume and media keys (Windows):** a volume slider sits in the player bar on wide windows and in the now-playing view; the level is remembered. The keyboard's play/pause, next and previous keys control Troit while a song is loaded, including when the window is minimized. While a song is loaded those keys are claimed from other players. Android keeps using the system volume and media controls.
- **Volume levelling:** off by default, in **Playback settings → Normalize volume**. Windows uses mpv's EBU R128 `loudnorm` filter; Android 9 and newer uses a compressor and limiter on the playback session, because Android has no loudness normalizer. Both change the original dynamics slightly. The setting is per device.

```powershell
node supabase/tests/activity.integration.mjs
.\gradlew.bat :desktop:test --tests app.tunes.NewFeaturesTest --tests app.tunes.InlineVideoTest --tests app.tunes.MusicVideoLiveTest
```

`activity.integration.mjs` runs 18 isolated database checks for friend-only access, pending requests, paused and stale sessions, opting out and blocking. `NewFeaturesTest` downloads through a local range-serving HTTP server, restarts the index, checks shared-link parsing against look-alike links, counts stats, and plays local audio through bundled mpv with levelling on and a saved volume. `InlineVideoTest` uses a generated video whose every frame encodes its own number: it checks that each frame is shown within a tenth of a second of its moment and is the frame from that moment, that the picture holds while paused and follows a seek, and that no player process is left behind; it then opens a real window with the real side panel beside real audio playback and checks that the picture is drawn there while the audio keeps playing and is never loaded again. `MusicVideoLiveTest` finds a real video of the song's own length on the public catalogue and checks its frames keep up with a running clock. `UiRenderTest` renders the whole app with the panel opened and closed on desktop and phone sizes. The Android picture sync, Android levelling and offline playback inside the Android service compile but were not exercised on a device in this workspace; the media-key handling is tested with a stand-in key, not a real media keyboard. The friend activity migration is tested locally and **has not been applied to the live Supabase project**.

### Less to look at, easier to learn

The same release trims the interface, using [Shneiderman's eight golden rules](https://www.cs.umd.edu/~ben/goldenrules.html) as the checklist:

| Principle | What changed |
|---|---|
| Consistency | A place has one name everywhere: the header title matches the navigation (**Library**, **Settings**), and **Your listening** and **Playback settings** are spelled the same in the menu, the Library and the dialog title. The queue is the same icon on every width. |
| Shortcuts for frequent use | The video button and the keyboard's media keys act without opening anything. |
| Informative feedback | A song row shows a spinner while it downloads and an arrow once it is on the device; a failed download is reported at the bottom of the window, not only inside Downloads. Icon-only buttons name themselves on hover or long-press. |
| Closure | Sending a song ends in **Sent**; a finished download ends in the arrow. |
| Error prevention | **Remove all** downloads needs a second tap; a song with no video says so instead of showing an empty box. |
| Easy reversal | The button that shows the video hides it; **Remove download** undoes **Download**. |
| User in control | Nothing starts or changes playback by itself; the video never touches the music. |
| Less to remember | The header keeps two controls (Devices and More); Discover also links directly to Search. The More menu and the song menu are short groups split by what they act on. The Library shows one primary action and two destinations, with the occasional rest under **More**. Now playing lost its second heading and the audio-quality explainer. |

Removed from the header: the **Find your next favorite** button (Search is in the navigation) and the **Music preferences** button (available in Settings).

## Typography and scrolling

The persistent connected player uses a compact responsive layout on Windows and Android: centered transport on wide screens, two rows on phones, and secondary **Lyrics**, **Sleep timer** and **Listen here** actions under **More**. The song, artist, heart and device/state remain visible. Offline recovery is available through **Devices** and More, without contradictory transfer guidance. Pending and confirmed/error feedback remains visible; offline or pending transport/transfer actions stay disabled. On TV, the smaller footer retains 56dp focus targets, a visible heart focus state and D-pad navigation. An independent clock disables expired remote controls even when a paused song has not changed.

This pass applies [Shneiderman's eight golden rules](https://www.cs.umd.edu/~ben/goldenrules.html) to the player:

| Principle | Application |
|---|---|
| Consistent controls | Previous, play/pause, next and the same More menu terminology. |
| Accessible layouts | Responsive rows, enlarged-text checks, 48dp touch and 56dp TV targets. |
| Clear feedback | Current playing/paused/offline state plus pending and error messages. |
| Confirmed outcomes | Existing command receipts confirm completion; no optimistic remote skip. |
| Error prevention | Stale/pending playback and transfer actions are disabled; recovery remains available. |
| Reversible choices | Hearts toggle back; Escape/Back dismisses menus without changing playback. |
| Explicit control | Moving playback requires choosing Listen here. |
| Visible context | Song, artist, playback device and progress stay on the player. |

`CompactPlayerUiTest`, `ConnectUiTest`, `TvTransportCompactTest` and `TvNavigationTest` render and operate the actual Compose controls. Current previews and the comparison report are written to `desktop/build/ui-previews/compact-player-review.html`. These checks do not replace physical Android/TV remote and screen-reader testing.

Both apps package five Manrope weights; there is no runtime font download. Font provenance and the bundled license are described in [shared/FONTS.md](shared/FONTS.md).

Music lists use lazy rendering, stable duplicate-safe item identities, reusable content types, cached filtering and artwork sized to layout bounds. Library and preference scroll positions survive tab switches; Search keeps its result and genre scroll positions. Placeholder rendering and player timer formatting avoid repeated allocations.

`ScrollRenderingTest` exercises real wheel input against 100 shelves and 2,400 songs, including duplicate titles and URLs. It writes `desktop/build/ui-previews/scroll-report.txt`. Those offscreen timings are diagnostics, not a phone frame-rate measurement.

## Make the music yours

Open **Settings** in the navigation to change music preferences.

- **Discovery region** changes the market used for discovery and search requests.
- **Hidden regions** and **Hidden languages** are reversible filters: tap to hide, tap again to restore.
- **Hide Indian music** turns on the India filter and the listed Indian music language filters together. Each can be adjusted separately.
- **Reset all music preferences** restores automatic discovery with no exclusions.

Preferences are saved locally and apply to discovery, search, album/playlist browsing, and new playback queues. Saved playlists are preserved. Changing preferences does not interrupt the track already playing; selecting another queue entry applies the current filters.

The upstream catalog does not reliably supply recording-country or language metadata. Filters therefore use explicit music labels and recognizable writing scripts; they cannot guarantee a song's origin. Artist names are never treated as nationality. A selected discovery region is a recommendation market, not an artist-origin restriction.

## Playback and collections

Select the song in the bottom player to open its playback controls, or choose **Queue** to jump straight to the track list. Create playlists in **Library**, save songs with **Save to playlist**, or import Spotify links. Open **Devices** to switch players or sign in.

Rename a saved playlist from its **More → Rename playlist** menu in Library, or the **Rename** button inside the playlist. Names save on this device and sync through the existing account connection, keeping the songs and playlist identity.

**Search in this playlist** searches song titles and artists locally, including combined words. The count and clear control sit beside the results. Clicking a match plays the complete playable playlist at that song's original position, including duplicate occurrences; the header's Play and Shuffle controls use the matching songs. Searching does not change the saved playlist.

Open **Now playing** and choose **Change** beside the current playback mode to select what happens when songs finish.

- **Stop at end** finishes the queue and stops. This is the default.
- **Repeat playlist** wraps back to the first queued song.
- **Repeat song** repeats the current song; pressing Next still moves to the next song.
- **Similar music** finishes the queue, then adds new recommendations inspired by the playlist. Next on the final song also starts discovery.

The mode is remembered on each device and applies in the background Android service as well as Windows. Similar music uses the provider's song-radio relationships, sampling up to three representative artists from the original playlist; explicit genre labels are not reliably available. It excludes songs already in the playlist/recent radio history and respects current music preferences. Artist-based discovery is a fallback when radio is unavailable. Discovery requires a network connection, can take up to 20 seconds, and stops with a retry message when no new songs are available. Changing playlists/modes or pausing cancels pending discovery; saved playlists are not modified by added queue recommendations.

Long-running Similar music retains a rolling tail of 40 completed/current songs plus at most 20 new songs after each discovery batch. An original 1,000-song playlist keeps all of its upcoming songs until they have played. Session duplicate protection keeps at most 1,000 original identities and 1,000 recent identities/normalized recording keys, rather than full Song/artwork objects. Only three seed tracks are retained, without artwork. The signed audio-source cache uses a synchronized 64-entry LRU and removes expired links, so it cannot grow with every song played. Recommendations are requested in batches only when the queue finishes, rather than on a continuous timer.

## Spotify imports and connection setup

The import limit is 1,000 source tracks across the links in one batch. Open **Library → Import Spotify → Set up Spotify** to connect a developer app:

1. Create/configure an app in the [Spotify developer dashboard](https://developer.spotify.com/dashboard).
2. Add this exact redirect URI: `http://127.0.0.1:43824/spotify/callback`.
3. Paste the app's **Client ID** into Troit and select **Connect Spotify**. Complete sign-in and consent in your system browser, then return to Troit.
4. Paste playlist, album, or song links and choose **Import music**.

The connection uses [Authorization Code with PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow), with no embedded or entered client secret. The short-lived callback server listens only on this device's explicit loopback address, validates the state/host/path, and closes after completion, cancellation, or a five-minute timeout. The redirect follows Spotify's [loopback redirect requirements](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri). Spotify tokens are saved in the app's local data folder and refresh automatically; **Disconnect Spotify** deletes the local session. Connections are separate on Android and Windows.

Connected playlist reads use the current [Playlist Items API](https://developer.spotify.com/documentation/web-api/reference/get-playlists-items), 50 positions at a time up to the first 1,000. Spotify currently restricts those reads to the playlist's owner or collaborators; [developer-app account access rules](https://developer.spotify.com/documentation/web-api/concepts/quota-modes) also apply. Unavailable, local, and non-song items are skipped with a report. Without a connection, Spotify links still use the public embed preview, which may expose only part of a playlist; the app reports that limitation rather than presenting a preview as a full import.

Matching uses at most two simultaneous searches and preserves source ordering. **Pause import**, **Resume import**, and **Save matched songs** keep long batches manageable; pending work can resume during the current app session. Closing/restarting the app does not preserve an unfinished import. Rate limiting stops the batch for a later resume. The completion report summarizes unmatched tracks and preview/access limitations without rendering an unbounded failure list.

## Audio and connected playback

Playback selects the best available original audio stream, preferring versions without dynamic range compression and using reported bitrates when nominal rates are missing. Now playing shows the source codec and approximate bitrate. Windows uses clean mpv settings without external configuration, scripts, or ReplayGain; neither platform adds an encoding step. The source is YouTube Music, so recording and provider limits still apply: these changes cannot supply Spotify audio or turn a lossy source into lossless.

Live playback and controls on PC, phone and TV use the connection described above. Library/legacy queue snapshots still sync every 30 seconds, plus network time. If no live connected player is available, **Last played on PC / Phone / TV** can offer **Continue here** from the saved queue and position. That historical fallback does not wait for a remote pause; a successful new playback takeover pauses a superseded online app. Opening an old paused local queue never takes playback ownership.

Android v0.5 includes a **Troit player** home-screen widget with the current title, artist, device, and previous/play/pause/next controls. Local playback updates through the existing music service even with the activity closed. PC controls require Windows v1.4.0, the same account, a running online PC app, and the existing [Connect migration](supabase/migrations/20261111030000_troit_connect.sql). Add it by long-pressing the home screen and choosing **Widgets → Troit player**. The live PC connection updates the widget automatically, including changes made in the desktop app. It starts from the visible app or a widget tap, shows a connection notification with a stop action, slows down while idle/offline, and pauses network reads while the screen is off. Widget controls also reconcile the actual result after a command instead of relying on one fixed-delay fetch. WorkManager remains a fallback when the live connection is stopped. See [widget setup and behavior](app/WIDGETS.md).

The historical session card shows the remote song title, artist and device, with **Continue here** and **Dismiss** below the details. The live connected player adds playback controls and confirmed **Listen here** handoff. Both paths respect local music filters.

## Collaborative playlists

Create a shared playlist from **Library → New collaboration**, or promote a saved personal playlist using **More → Make collaborative** / **Make collaborative** inside the playlist. Choose accepted Troit friends and a name. Friends receive invitations in Library and can accept or decline. The Library badge counts pending collaboration invitations.

Accepted editors can play, search, add, and remove songs. Open any song's options menu and choose the shared playlist in **Save to playlist**. The owner can rename/delete the playlist, invite more friends, or remove collaborators; editors can leave. Song edits use granular server operations under a playlist lock, so concurrent additions do not replace each other's queue. Renames use a server revision and report a conflict when the playlist changed while the form was open. Limits are 1,000 songs, 20 accepted/pending members including the owner, and 50 active memberships/invitations per account.

Conversion copies the songs to the server-owned collaboration and tombstones the personal source only after server confirmation. A lost response can be retried without creating another shared playlist. Local private edits observed during conversion are preserved as a separate private copy. Shared content stays separate from personal-library last-write-wins sync and is kept only in account-isolated memory: at most 50 summaries, 50 invitation summaries, and one selected song list. Signing out hides it immediately. Open apps poll the selected playlist about every five seconds and the summaries every 15 seconds, plus network time, with failure backoff. Background/minimized apps wait until they become active again.

Only accepted friends can be invited; membership acceptance and current friendship/block rules are checked on the server. Removing a friend, blocking, removing a collaborator, or deleting a playlist prevents further server reads/writes. An existing member row may become eligible again when friendship/block restrictions are restored; owners can permanently remove that membership from **Collaborators**.

### Activate the collaboration server endpoints

Apply [20261113010000_troit_collaborative_playlists.sql](supabase/migrations/20261113010000_troit_collaborative_playlists.sql) in the existing project's Supabase SQL Editor, after the existing Tunes sync and social migrations. This additive migration creates two RLS-enabled shared tables and restricted authenticated RPCs. It does not publish a general profile directory or grant direct table access. The app shows a server-update state until it is applied; personal playlists remain usable. This workspace has no configured database connection, so the prepared migration has been tested locally and is left for the user to apply in SQL Editor.

`node supabase/tests/collab.integration.mjs` runs 74 isolated PostgreSQL/WASM checks for access control, invitation acceptance, promotion, mutation/retry semantics, blocking, ownership, conflicts, and limits. Client fixtures and phone/desktop renders cover account switches, stale requests, shared saves, and promotion after confirmation. These tests do not replace a live multi-device acceptance check after the migration is applied.

## Music search

Typed searches debounce for 250 milliseconds, cancel superseded HTTP calls, and use a direct YouTube Music search request. The last 32 first-page query/filter/market combinations are cached for five minutes; music exclusions are reapplied to each cache read. Continuations retain their original market and visitor state. A new query after cache expiry reads the upstream catalog, so a new release becomes searchable when the upstream search index includes it.

The first-search loading state no longer waits for a hidden result list to attach. Pagination and first-page requests have separate ownership and cancellation guards, so slow old replies cannot overwrite a newer query. A 16-second UI timeout provides a retry state instead of an indefinite spinner.

Offline checks live in `SearchStateTest` and `MusicSearchTest`. The opt-in `SearchLatencyTest` uses the public upstream service and writes diagnostic timings under `desktop/build/search-diagnostics`; those measurements depend on the network and are not a latency guarantee.

## Friends, chat, and appearance

Open **Friends**, connect your Troit account, and choose a unique handle and display name. Add friends by their exact handle. Only the recipient can accept a request; accepted friends can exchange text and the built-in **Jomok** and **Jawir** reaction GIF stickers. The eight photo-meme GIFs use different online reaction-photo references with Indonesian captions and generated motion, animate only in visible active chats, and use still previews in the picker. Asset paths, source links, generation prompts, and rebuild instructions are in [shared/STICKERS.md](shared/STICKERS.md). Profiles do not expose email addresses or a searchable account directory. Remove friends or block accounts from a conversation's **More** menu. Blocked accounts can be managed from the friends list.

Requests and conversations belong to the signed-in account and are shared by Android and Windows. The visible conversation polls for changes and backs off while idle or offline. A separate inbox checks every five seconds plus network time across all app screens, including minimized/unfocused Windows. New incoming messages show an in-app banner, unread conversation badges, and native system notifications. The conversation currently visible in a focused app stays read and does not alert. Android asks for notification permission once after sign-in; if denied, banners and unread badges still work. Reading a chat clears its Android notification. Windows notification clicks open Friends, with an explicit tray menu action for the latest sender.

Android also schedules independent background inbox checks through WorkManager at its 15-minute minimum; battery settings, Doze, force-stop, and network availability can delay these checks. Instant closed-app delivery requires a separately configured push service. Windows checks stop when the program exits. First-time notification setup silently establishes a baseline for existing messages. Later checks persist account-specific sequence cursors and unread peer IDs to avoid repeated alerts after restarts; previews are not stored in that file. Message retries keep the original UUID, preventing duplicate database rows. A failed message stays available for retry in the current app session; restarting the app does not retain an unsent message.

Messages are limited to 2,000 characters or one supported sticker ID. Images are drawn locally, not uploaded into chat rows. Reads use server sequence cursors and pages of at most 50 messages; the current conversation shows up to 300 recent messages. Each account can have up to 300 active friends/requests and 200 blocked accounts. Server-side write limits include 60 messages per minute and 1,000 per day. These bounds reduce resource pressure; they are not a guarantee of availability under arbitrary traffic.

Open **Settings → Appearance**, or **More → Appearance** in the header, to select Monochrome, Midnight, Paper, Rose, or Ocean and an accent. Theme choices are cached separately for each account and guest mode. Signed-in users sync through server revisions; simultaneous edits present a choice instead of overwriting the other device silently. A connection failure keeps the local theme applied. Manrope remains the font in every theme, and window/system bar contrast follows light or dark colors.

### Database activation

The new shared features require the additive migration [20261111020000_troit_social.sql](supabase/migrations/20261111020000_troit_social.sql) in the existing Supabase project. Apply it with an authorized Supabase migration connection or the SQL editor. It creates six new tables and restricted authenticated RPCs without changing existing music tables or account identities. All social tables have row-level security and deny direct client reads/writes; RPCs check the authenticated owner and conversation membership.

Until that migration is applied, Friends displays a server-update state and themes remain saved locally. Successful compilation alone does not activate the live server. Do not put a service-role key or database password in either app.

The additive [chat inbox migration](supabase/migrations/20261113000000_troit_chat_inbox.sql) adds one authenticated, bounded inbox RPC and an index. Apply it after the social migration for one request per inbox check. Existing social installations also have a compatibility path that checks at most 12 conversations per tick and rotates through larger friend lists; this is slower and may miss an incoming message pushed outside the latest 50 messages before the next check. The inbox RPC returns the latest incoming message per peer, so outgoing messages do not hide a received message and independently committed conversations cannot skip each other's sequence numbers.

### Social and account validation

The SQL integration suite runs against isolated PostgreSQL/WASM with fixture identities, never against the configured live project:

```powershell
npm install --prefix build/social-db-check @electric-sql/pglite@0.5.16
node supabase/tests/social.integration.mjs
.\gradlew.bat :desktop:test --tests app.tunes.SocialStateTest --tests app.tunes.UserThemesTest --tests app.tunes.AccountTransportTest --tests app.tunes.SocialRenderTest --tests app.tunes.ThemeRenderTest
.\gradlew.bat :app:assembleRelease :desktop:packageExe
```

The SQL suite covers permissions, friend acceptance, blocking, message validation, idempotent retries, cursor pagination, rates, theme isolation, and revision conflicts. Client tests cover sign-out during requests/credential refresh, bounded responses, account changes, offline theme persistence, message reconciliation, and responsive rendering. Real multi-client contention and production load still require testing in a staging Supabase project.

## Targeted checks

```powershell
.\gradlew.bat :desktop:test --tests app.tunes.RegionPreferencesTest --tests app.tunes.PlaybackSelectionTest
.\gradlew.bat :desktop:test --tests app.tunes.ChatInboxTest --tests app.tunes.LibraryTest --tests app.tunes.AudioQualityTest --tests app.tunes.NotificationFeaturesRenderTest
.\gradlew.bat :desktop:test --tests app.tunes.PlaybackModeTest --tests app.tunes.MpvPlaybackModesTest --tests app.tunes.PlaylistRadioTest --tests app.tunes.PlaylistRadioLiveTest --tests app.tunes.PlaybackModeRenderTest
.\gradlew.bat :desktop:test --tests app.tunes.RadioQueueTest --tests app.tunes.AudioSourceCacheTest --tests app.tunes.PlaylistSearchTest --tests app.tunes.PlaylistFeaturesRenderTest --tests app.tunes.SpotifyAccountTest --tests app.tunes.SpotifyImportTest --tests app.tunes.SpotifyOAuthLoopbackTest --tests app.tunes.SpotifyConnectionTest
.\gradlew.bat :desktop:test --tests app.tunes.CollaborativePlaylistsTest --tests app.tunes.CollaborativePlaylistRenderTest --tests app.tunes.StickerGifTest
node supabase/tests/collab.integration.mjs
```

The existing `MpvPlayerTest` additionally exercises live network playback and native seeking, using silent audio output.

`ChatInboxTest` covers account isolation, first-read baselines, persisted deduplication across foreground/background instances, active-chat suppression, legacy rotation, and peer removal. The SQL suite also checks the inbox RPC's recipient restrictions. `NotificationFeaturesRenderTest` writes narrow-phone and desktop banner/rename previews under `desktop/build/ui-previews`. Native OS notification presentation and battery-delayed Android delivery still need device checks; offline tests do not prove end-to-end push delivery.

`MpvPlaybackModesTest` plays local silent WAV files through the real bundled engine to check natural completion, repeating, manual skips, and stale recommendation cancellation. `PlaylistRadioTest` uses bounded local HTTP fixtures for parsing, matching, filters, cancellation, and timeouts; `PlaylistRadioLiveTest` checks the public radio response without creating an account or changing any playlists. `PlaybackModeRenderTest` verifies the four-choice menu and bottom player on desktop and a 320-pixel phone with larger text. Android's native repeat behavior still needs device testing; JVM tests and compilation do not simulate the Android playback service.

`RadioQueueTest` simulates 3,000 discovery batches (60,000 generated tracks) and checks queue/history bounds plus preservation of unplayed original songs. `AudioSourceCacheTest` inserts 10,000 sources and checks its capacity, LRU behavior, and expiry. Spotify tests use fixture credentials and local HTTP interception/socket callbacks to verify PKCE, state validation, cancellation, refresh, and 20 pages containing 1,000 tracks. They never authorize a real account; the final browser consent flow still needs checking with your registered developer Client ID. Playlist rendering tests search a 1,000-song collection and check that selecting the last match retains the correct full queue.
