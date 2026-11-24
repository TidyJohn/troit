# Troit home-screen player

Android v0.11 also controls an active Android TV signed in to the same account. References to connected PC playback below apply to TV playback too; TV and phone command receivers now live in their playback service, while the Windows receiver stays in the running desktop app. The app's **Devices & playback** panel shares this same connection and supports confirmed **Listen here** handoff.

Install the current Android build, then long-press the home screen, open **Widgets**, and add **Troit player**. The black-and-white widget uses bundled Manrope, supports horizontal/vertical resizing, and has 48dp playback buttons. Its minimum size is four columns by two rows on a typical launcher.

The widget automatically shows a playing phone session first, then a playing connected computer, then an available paused session. Tap the song details to open Troit. Previous, play/pause, next, and refresh work without opening an activity. Phone controls target the existing playback service and never create a second player. The cover uses a small local music symbol, so refreshing does not download album artwork or allocate large bitmaps.

For PC controls, apply [the Troit Connect migration](../supabase/migrations/20261111030000_troit_connect.sql), install the updated Windows build, and sign in to the same account on both devices. The PC app must be running and online; it can be minimized. Play at least one track there to establish a playback session. If that migration is already active, this update needs no additional SQL.

Phone track/play-state changes update the widget immediately through the playback service. Opening Troit with a signed-in widget, or tapping a widget control, starts **Troit Connect** live updates. While the phone screen is on, it checks a playing PC roughly every two seconds and a paused PC every five seconds, then redraws only when displayed information changes. A widget command also follows its execution result and updated playback state, so next/previous and play/pause do not need a second refresh tap. Changes made directly on the PC appear through the same live observer.

Live updates run as a connected-device foreground service with a quiet **Troit Connect** notification. **Stop live updates** pauses that service until the next explicit widget tap; reopening the app respects the pause. If Android notifications are disabled, its foreground-service controls may appear under **Active apps** instead of the notification drawer. No notification permission dialog is forced by this update.

The observer performs no HTTP requests while the screen is off and refreshes on screen wake/unlock. While phone playback is active, local service events keep the widget current and PC checks slow to 30 seconds. Missing/offline computers back off to a minute instead of creating a tight retry loop. There is no wake lock, album-image download, whole-library load, or background audio player for remote control. Removing every widget or changing/signing out of the account stops the observer. Android can still stop the process or restrict it under power-saving rules; a deferrable 15-minute WorkManager refresh remains the fallback when live updates are not running.

A stale PC snapshot stays visible as last reported metadata. Tapping a playback button renews its 45-second server lease only when device, app runtime, track revision, URL, and queue index still match exactly. If playback changed, the widget updates and asks for another tap; it does not control the newly displayed track automatically.

Every PC command captures the displayed device, app runtime, track revision, action, and a stable UUID. The server expires commands after 30 seconds and consumes them at most once. A retry reuses the same UUID and never chooses another device. On Android 12+, button taps request expedited work with a normal-work fallback when Android's quota is exhausted. Post-command reconciliation follows both the receipt and actual state for at most 20 seconds/12 reads, showing real changed metadata as soon as it arrives. It never invents an optimistic song title or resends a command to obtain state. If the live service cannot start, up to three extra read-only checks provide a bounded follow-up. Regular scheduled refresh work avoids another HTTP poll while the live observer is running; explicit refresh and playback commands still run.

No network request runs inside an Android broadcast receiver. Compact widget snapshots contain metadata only; auth tokens remain in the existing private account store. Account changes cancel widget work, clear cached display state, and block results from an earlier account. The display cache rejects older remote revisions/timestamps; position and heartbeat changes renew the lease without redrawing every widget.

The network controller uses Android's [connected-device foreground service type](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device). It starts from a visible activity or [explicit widget interaction](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#exemptions), never from the periodic job or system widget update. Its notification behavior follows Android's [foreground service notification requirements](https://developer.android.com/develop/ui/compose/notifications/notification-permission). Screen-off behavior respects [Doze network restrictions](https://developer.android.com/training/monitoring-device-state/doze-standby).

## Validation

`WidgetPolicyTest` covers account ownership, original tap age, playback-target selection, invalid expiry timestamps, and explicit play/pause actions. `LiveWidgetPolicyTest` checks active/paused cadence, screen-off/local-playback throttling, and bounded offline backoff. Native widget layout and interaction still need a launcher/device smoke test:

1. Add and resize the widget on Android 8+; confirm legible Manrope text, truncation, and usable playback buttons.
2. Play on the phone, close the activity, and use previous/play/pause/next. Confirm the music notification and widget agree.
3. Play on the updated PC under the same account. Pause the phone, open Troit once or tap a widget control to start live updates, and control the PC while Troit is minimized. Confirm the title follows next/previous without refresh; then skip directly on PC and confirm the widget follows.
4. Tap next twice quickly; confirm the captured command does not skip twice after retries. Disconnect/reconnect after 30 seconds and confirm an old tap does not run.
5. Sign out while refreshing, sign into another account, and confirm the first account's track and controls disappear.
6. Remove the last widget and confirm periodic widget work is cancelled. Re-add it and confirm refresh resumes.
7. Turn the screen off and back on; confirm the current PC song appears automatically. Disable networking and confirm retry work backs off, then reconnect and confirm updates resume.
8. Stop live updates from the notification, reopen Troit, and confirm it stays paused. Tap the widget again and confirm live updates restart. Verify service startup on Android 8, 12, and 14+ without crashes.

The SQL integration tests use an isolated database; they do not activate or load-test the live Supabase project. A successful APK build does not replace the multi-device smoke test.
