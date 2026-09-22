# Reply recording fix and nearby UX bugs — September 21, 2026

Scope: the Android watch → reply → live recording handoff, plus nearby playback, notification, and delivery interactions. This is a focused source review with emulator regression tests, not a complete device audit. The first-tap recording fix shipped in 0.2.7. The follow-up 0.2.8 update addresses unread-count freshness, reminder routing, and durable offline watch progress; see [verification](FEATURES.md).

## Fixed in 0.2.7

The watch screen's Record button navigated to `LiveScreen`, whose preflight required another Record tap. The first tap therefore appeared to load and do nothing. `MainActivity` now explicitly passes a start request from the watch reply action. `LiveScreen` retains that request until camera/microphone permissions and a valid camera surface are available, then consumes it once. It skips opening a redundant preview camera on this immediate-start path. Permission denial cancels the pending request and explains the missing permissions; a failed server start requires deliberate retry instead of looping.

Choosing a recipient from Home still opens a preview without recording. Private recording retains its separate stop/review/send flow.

Verification: debug and instrumentation builds, all JVM unit tests, and Android lint passed. Targeted emulator checks use synthetic conversations and an isolated local Worker: one reply tap creates exactly one live message, produces playable HLS segments, and completes an MP4 replay; the real watch screen reaches live recording with one tap after playback ends; Home recipient selection remains a preview. Native permission-dialog grant/denial, physical camera timing, and fold/unfold remain device checks. An initial watch test failed because the local backend trusted a different localhost port; it passed after correcting the test backend origin.

## Open findings

| Priority | Trigger and visible problem | Source evidence and suggested correction |
|---|---|---|
| High | Stop live recording while local saving or upload scheduling fails: there is no handled recovery state for the finalization error; an exception can escape the coroutine. | `LiveScreen.kt`, `finishWithReplay`: the saving/enqueue path has no catch, and Ending disables Back/Close. Preserve the recoverable file and expose a durable save/retry state. |
| High | Camera startup fails after the live session is accepted: the user can be returned to watching before understanding the error. A capture error is shown briefly, then the screen closes. | `LiveScreen.kt`, `startSharing` catch and capture-error callback: errors live only in screen state, followed by `onClose()` immediately or after three seconds. Keep the failure visible with retry and an accurate recovery explanation. |
| Medium | Leave a partly watched clip and later load it as a new player item: playback starts at zero despite stored progress. | `PlayerScreen.kt` calls `setMediaItem(..., 0L)` for every different message ID. Resume partial messages from saved progress; keep explicit replay/filmstrip behavior a deliberate product choice. The start-at-zero behavior was previously requested, so changing it should be treated as a behavior decision, not an accidental regression. |
| High | Discard while an upload is in flight: a late worker write can restore the draft, or a concurrent request can still publish it. | Existing finding in `PRODUCTION-READINESS.md`, still present in `Repository.discard` and `UploadWorker`: cancellation is requested without a terminal discard tombstone or conditional worker writes. Make discard terminal and reconcile remote state by stable message ID. |

The open rows are supported by source inspection and earlier readiness findings. They were not newly reproduced through fault injection on the Razr. In particular, this review does not claim an observed crash, lost recording, or unintended send on the owner's phone.

Recommended next fixes: live error recovery and discard safety.

## Addressed in 0.2.8

- Home refreshes on resume, push/sync hints, and every five seconds while visible. Finishing an incoming message updates cached unread/unwatched counts while its server update is pending.
- Reminders and message notifications carry the exact message ID. Navigation loads fresh conversations, retries when offline, handles a new intent while the app is already open, and explains when a target was deleted.
- Position and watched completion are persisted in account-scoped SQLite records, coalesced, and retried after reconnect. Revision-specific acknowledgments preserve newer local writes; completion never regresses. The playback service saves progress even after leaving the watch screen.

See [FEATURES.md](FEATURES.md) for final tests, deployment, installation, and remaining device checks.
