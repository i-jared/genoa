# Feature status and verification

Genoa is beta software. This document records current behavior and verification limits; it is not a production-readiness certification.

## Production-readiness work — September 24, 2026 (not yet deployed or released)

**Backend** (migration `0008.sql`, not applied to production):
- **Upload vs deletion:** upload completion publishes only if the message still exists and its author is still a member. Any file left behind after a failed publish is removed.
- **Early deletes:** deleting an ID the server hasn't seen yet records a tombstone. A request still in flight then can't create that message (`410 message_discarded`), so a 404 from DELETE is final for clients.
- **Media checks:** uploads must be playable H.264/HEVC video or AAC audio (`422 unsupported_media`).
- **Limits:** each account gets a storage quota (`413 storage_quota`, default 25 GiB). JSON requests are capped at 64 KiB, and each person can react to a message at most 200 times.
- **Live sockets:** deleting or suspending an account closes their live sockets in every conversation. Every broadcast also rechecks membership.
- **Email:** password reset and email confirmation go through Resend, and new passwords need at least 8 characters.
- **Moderation:** suspension, a report queue, removing messages, an audit log, crash diagnostics intake, dead-lettered job triage and email alerts.
- **Cleanup and pages:** a sweep reconciles R2 against D1, and the Worker serves public privacy, terms, support and account-deletion pages.
- **Admin console:** a separate Next.js app in `admin/`, for Vercel, holding no data of its own. See [operations](OPERATIONS.md).

**Android** (unreleased):
- **Discard:** writes a tombstone first, waits for upload work to stop, then keeps deleting the server message until the server confirms, including after a restart.
- **Live sharing:** delivery and finishing are owned by `LiveUplink`/`LiveEndWorker` rather than the screen. Pending segments are capped at 150 or 160 MiB, and sharing stops when storage runs low. Errors that retrying can't fix stop the retries.
- **Live consent:** the button says **Go live**, preflight requires **Start sharing live**, and a sharing badge is shown.
- **Imports:** size, free space and codec are checked before copying, and the copy streams with a cap.
- **Account screens:** "Forgot password?", agreeing to the Terms at signup, links to Terms, Privacy and Support, and handling of suspended accounts.
- **Crash reporting:** Crashlytics on release builds only, with no user identifiers.

**iOS** (unreleased):
- **Discard:** writes a tombstone before cleanup, cancels background transfers for the draft, and deletes on the server once none of its requests are in flight, including after relaunch. Queued and uploading drafts can now be discarded.
- **Errors:** upload errors are sorted into sign in again / blocked / retry / final. Final ones show the server's message with Retry and Discard.
- **Live sharing:** always goes through the **Start sharing live** preflight and shows a LIVE badge. Pending fragments are capped at 90 or 192 MiB. Recording stops below 200 MiB free. Terminal errors offer **Stop and save recording**.
- **Imports:** free space is checked and photos must decode.
- **Account screens:** the same password reset, Terms and suspension handling as Android.
- **Crash reporting:** MetricKit crash, hang, CPU and disk reports are redacted, capped at 256 KiB, queued per account (at most 20) and uploaded to `/api/diagnostics`. The privacy manifest declares crash and performance data.

Verification (run in this session):
- **Backend:** type check passes. All 23 local tests pass: the 8 earlier suites plus a new moderation/recovery suite and unit tests.
  - The suite holds upload completion open for 1.5 s and for 9 s while the message is deleted. It confirms nothing is left in the local D1 or R2 stores.
  - It also covers the tombstone, media checks, quota, reaction cap, password reset (single use, revokes sessions), admin 404s and moderation, suspension closing live sockets, non-owner deletion closing live sockets, diagnostics and job redrive.
  - Unit tests run MP4 inspection on real moov-first, moov-last and fragmented files, plus fake and truncated ones, and check the sweep's rules.
- **Admin console:** typecheck, lint, 6 cookie-encryption tests and the production build pass. Against the local Worker with a real admin session, every page renders real data, signed-out requests go to sign-in and non-admins are turned away. Server-action mutations were exercised only against a mock Worker.
- **Android:**
  - The debug, release and androidTest builds and lint pass, with 43 unit tests.
  - On the API 36 emulator, `DraftDiscardTest` (10) and `LiveUplinkTest` (11) passed over 4 runs. `GenoaFlowTest` live replay and recipient preflight pass against the local Worker.
  - `GenoaFlowTest#textNotesAndVoiceSurviveRealDelivery` fails at a later filmstrip lookup. This is not yet diagnosed.
  - `WatchPresentationTest`/`UsabilityFlowTest` were updated for the new labels but not run.
- **iOS:**
  - The 25 unit/integration tests pass five times in a row on the iOS 26 simulator against the isolated test server. A diagnostics queue ordering bug found here was fixed.
  - The UI flow test and an unsigned Release device build pass.
- **Ops:** the restore drill was rehearsed locally: 35 tables restored with matching row counts. Its media check correctly flagged local objects left from before the bucket rename.

Internal-testing builds: Android **0.2.10 (14)**, a signed AAB/APK plus mapping in `dist/android/`, signed with the existing upload key (certificate SHA-256 `dd93a70a…16ecee` matches `upload-certificate.pem`; `apksigner` and `jarsigner` verify it). iOS **0.1.2 (3)**, an App Store IPA exported with cloud signing for team `DCWP7J7692`, plus the archive and dSYMs in `dist/ios/`; the release script checked the signature, `app.genoa`, production push and matching app/dSYM UUIDs. The builds were run through the release scripts (the Android one also runs unit tests and lint). Neither has been uploaded to a store. They talk to the production API, which doesn't have this backend yet. Until it's deployed, password reset stays hidden, and iOS diagnostics stay queued on the phone because `/api/diagnostics` returns 404. Existing flows are unaffected.

Not done: production migration and deploy, admin console deployment, Resend/support/alert configuration, APNs, the Crashlytics console, the production restore drill, legal review of the policy pages, and every physical-device check (background, lock, low storage, long and two-device live, MetricKit delivery, large imports, TalkBack/VoiceOver). CI has never run because the repository has no remote. On Android the camera still stops when the live screen goes away; only delivery continues.

## Groups and reminders — September 24, 2026

- The iOS Groups filter includes named conversations as well as those with three or more people. Previously a named two-person group never appeared. Conversation rows on both apps show a compact member preview (first names plus "+N"; iOS adds small avatars).
- Any member can rename a conversation (server rule changed from owner-only). On iOS: long-press a row → Rename, or tap the conversation title to open details. The watch header updates without leaving the conversation. Android details allow every member to save a name.
- iOS reminders now schedule a local notification. The notification ID matches the server's APNs collapse ID, `reminder:<id>`. Before this, iOS reminders never alerted because APNs is unconfigured. Message actions confirm the reminder time or explain that notifications are off. The reminder list shows date and time, an empty state, and the displayed conversation name; removal also cancels the local alert. Pending alerts are reconciled with the server on launch and when the list opens, and cleared on sign-out or session expiry. After APNs is configured, whether local and remote reminders merge into one notification must be checked on a device.

Verification: backend TypeScript check and local integration suite pass (7 pass, 1 skipped as before), including member rename, outsider rejection, `named` and `member_names`. iOS simulator build and `WatchSelectionTests` pass, including group classification and preview. A simulator UI run against the isolated test server confirmed the Groups filter, member preview, long-press rename, and the live header update. The temporary test was removed afterwards. Android unit tests and lint pass. Not yet done: production deploy of the backend change, and on-device reminder alert delivery.

## Signup, recording and conversations — September 22, 2026

- Open email signup is deployed: no beta code, minimum six-character password, optional display name in both apps. Google/Apple browser flows are implemented with proof-bound, expiring, single-use handoffs; buttons remain hidden until provider secrets exist. Provider credentials remain unconfigured; external provider consent/account-linking is not yet verified. See [setup instructions](SOCIAL-SIGN-IN.md).
- Starting a conversation immediately offers existing Genoa contacts plus Copy invite link / Share link. No name is required; other participants supply the default title, and owners can rename later. Stable conversation/invitation IDs make retries safe. Concurrent collisions cannot grant membership to another creator. HTTPS invitation pages open Genoa, and invitations survive authentication.
- Automatic watching skips outgoing and finished messages, resumes partial messages, and searches later pages for unseen incoming content. Sending returns to a ready recorder unless incoming content needs watching. Explicit history selection still permits replay. iOS progress completion is persisted before advancing.
- iOS uses camera-specific rotation coordinators for preview and capture. Encoding follows the rotated sample dimensions with no second writer transform, shared by MP4 and live fragments. Physical front/back portrait/landscape capture and cross-device replay remain required; simulator/encoding checks do not prove physical sensor behavior.
- Android dialogs now share a compact heading, restrained surface and action styling. Conversation creation is a contact/link bottom sheet. Native screenshots were inspected during the usability verification.

Verification: backend TypeScript and all 8 local integration tests pass, including open signup, password validation, retries, simultaneous creation/authorization, mobile OAuth proof/browser binding, expiry and replay. Android debug/release builds, lint and 23 unit tests pass; the emulator flow creates/copies an invitation, opens details, records/sends voice, remains recorder-ready and returns without autoplay. iOS 13 native tests and 1 UI flow pass; the optional store screenshot test was skipped. Native tests cover durable uploads/progress, authorized media/HLS, first-unseen selection on recorder return and encoding dimensions. These are previously recorded results, not tests rerun during repository cleanup.

Release artifacts: Android **0.2.9 (13)** signed AAB/APK and matching mapping; iOS **0.1.1 (2)** App Store IPA and distribution-signed Xcode archive in `dist/android/` and `dist/ios/`. Android signatures, Apple team `DCWP7J7692`, `app.genoa`, production push entitlement and matching app/dSYM UUID were verified. **iOS 0.1.1 (2) was delivered to App Store Connect through Transporter on September 22, 2026; Apple processing was pending at verification. No App Store review submission or Google Play upload was performed.** The prior store artwork and icons remain reusable; prior beta-code review instructions are obsolete.

Production: migration `0007.sql` applied and Worker version `573ec5cd-fa5f-49da-a898-85b41c28051e` deployed. Smoke verification passed for six-character signup without beta headers, hidden unconfigured providers, unnamed creation, invitation retry and the HTTPS landing page; temporary verification data was deleted. Provider credentials, physical camera verification, APNs backend configuration and the existing wider-release gates remain outstanding.

| Feature | Implementation | Verification |
|---|---|---|
| Native Android app | Kotlin/Compose; four primary surfaces | Debug APK builds; lint passes |
| Native iOS app | SwiftUI/AVFoundation; shared backend and Genoa design assets | 13 native tests and 1 UI flow passed in latest verification; physical parity checks remain |
| Private direct/group conversations | Invite links, rename, member removal, ownership transfer, leave | API membership/invitation tests; management UI implemented |
| HD video / voice | CameraX HD capture, native voice recording; explicit send | Emulator voice recording, explicit send, and duration check passed |
| Durable delivery | Account-scoped local files/SQLite; WorkManager; resumable 8 MiB parts; stable IDs | API duplicate-finalization, missing-part, and two-part restart/resume tests |
| Watching | Media3 play/pause, seek, stored position, watched status, 1–3× speed | Emulator authenticated voice/HLS playback and 2× speed assertions passed |
| Background listening | Foreground playback service and media notification | Emulator screen-off playback passed; physical headset/interruption checks remain |
| Photos / imported media | JPEG/PNG and MP4/M4A attachments | Restricted to these formats; arbitrary transcoding not implemented |
| Reply / reactions | Conversation-level send into the filmstrip; timed emoji moments on a message | Isolated API stores more than one reaction per person at different times |
| Bookmarks / forwarding | Personal bookmark flag; separately authorized forwarded copies | API personal-bookmark filtering and destination-isolation tests passed |
| Notes / favorites / mute | Private scratchpad in conversation, player, and recorder | Scratchpad isolation API tested |
| Reminders / urgency | Scheduled push reminders; urgent text messages; reminder management | Scheduling API tested; production queue delivered a notification to the dedicated emulator |
| Saving | Download to gallery/music and optional own-message auto-save | Native MediaStore implementation |
| Retained history | Cursor pagination; no automatic published-message expiration | Pagination implemented |
| Captions / transcript / summary | Server generation remains; watch UI and the settings toggle are hidden for now | Earlier production AI proof still stands; player no longer shows captions |
| Watch during recording | Native segmented capture, authenticated HLS, completed MP4 replay | Emulator segment publication and completed replay tests passed; ffprobe confirms H.264/AAC |
| Blocking / reporting | Server block filters; reports reviewed in the admin console (dismiss, remove, suspend) with an audit log | Local API tests; console not yet deployed |
| Account controls | JSON export; account removal and media deletion queue | API export, explicit confirmation, and session-revocation tests passed |

## Reliability and remaining release gates

- Earlier isolated tests cover server membership/media authorization, byte ranges, duplicate finalization, interrupted multipart resume, history pagination, forwarding isolation, invitations, account export/deletion, and migration compatibility.
- Android 0.2.8 introduced durable offline progress with account isolation, coalescing, monotonic completion, and revision-specific acknowledgments. Seven emulator regressions covered progress, notifications, session invalidation, first-frame/retry behavior, history and reply capture. The later 0.2.9 behavior resumes partial incoming messages and skips finished/outgoing messages during automatic watching.
- iOS local tests exercise Keychain, durable drafts/outbox/progress, revoked uploads, lost final responses, MP4 and fragmented HLS access/deletion, and persistence of live fragments independently of network speed. Force-quit/background delivery still needs physical-device soak testing.
- Earlier synthetic production checks demonstrated FCM delivery and author-consented transcription/summary/captions, including deletion on consent revocation. APNs and external Google/Apple consent/account-linking remain unverified. AI controls are currently hidden in the native UI; forwarded media does not copy extracted AI audio chunks.
- Two-device Android/iOS camera and live replay, physical front/back rotation, long capture, low storage, background/lock-screen audio, weak-network restart/retry, notification delivery/routing, Photos/Files export, and accessibility remain release gates. Emulator screenshots do not certify these behaviors.
- Account recovery, moderation operations, cost/queue alerts, metadata/media restore drills, and remaining store declarations are outstanding. Signed builds do not resolve these requirements.
- [Production readiness](PRODUCTION-READINESS.md) retains open deletion, discard, live-recovery, subscription-revocation, and resource-limit findings. This cleanup does not claim to fix them.

## Repository maintenance — September 22, 2026

Release outputs use stable paths in `dist/android/`, `dist/ios/`, and `dist/store/`. The release scripts own these outputs. Duplicate brand exports, obsolete APKs, task-specific artifact folders, stale audit reports, and the superseded editor-specific verification harness were removed. Native tests and the backend integration suites remain the supported checks.

The design reference and custom icon sources are ordinary files in `docs/design/guide/`, included in fresh clones. Private signing material and the historical database backup are preserved in ignored `.local/`; they are never packaged. Current behavior, unresolved findings, and measured verification above were retained while historical task logs were consolidated.

Cleanup verification: the updated Android debug release command passed the build, all 23 unit tests, and lint (0 errors, 42 warnings), and produced `dist/android/Genoa-debug.apk` with version metadata and checksums. Shell syntax and embedded Python checks passed. The iOS post-export packaging step passed on a temporary copy of the existing signed IPA/archive, verifying signature, team, production push, matching dSYM UUIDs, and checksums; Xcode archive/export and provisioning were not rerun. Store packaging validated all 12 screenshots and listing limits; all 134 canonical brand assets passed validation. Local Markdown/design-guide links, release checksums, private-file permissions, Git ignore coverage, and `git diff --check` passed. No application behavior, production deployment, store upload, or device state is changed by this maintenance.
