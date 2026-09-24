# Feature status

Genoa is beta software. This page records what is implemented, how it is verified, and what still blocks a public release. Update it whenever verification changes.

## Features

| Feature | Implementation | Verification |
|---|---|---|
| Conversations | Direct and group conversations, invite links, rename, member removal, ownership transfer, leave | Backend integration tests (membership, invitations, concurrent creation) |
| Video and voice | CameraX / AVFoundation HD capture and voice; explicit send after recording | Emulator and simulator flows |
| Durable delivery | Account-scoped drafts, resumable 8 MiB parts, stable IDs, WorkManager / background URLSession | Backend retry and resume tests; Android and iOS interrupted-upload and restart tests |
| Discard | Tombstone first, cancel in-flight work, reconcile server deletion by stable ID | Android `DraftDiscardTest`, iOS `ReliabilityTests`, backend tombstone test |
| Watching | Resume position, watched state, 1–3× speed, background listening, unseen-first selection | Emulator and simulator playback tests; backend history tests |
| Live sharing | Explicit **Start sharing live** consent, HLS segments, replay MP4, bounded buffering, low-storage stop | Android `LiveUplinkTest` and live replay flow; iOS reliability tests; backend HLS tests |
| Replies and reactions | Replies stay in their conversation; timed emoji reactions (max 200 per person per message) | Backend tests |
| Photos and imports | JPEG/PNG and MP4/M4A; size, free-space and codec checked before copying | Unit tests; server rejects unplayable media (`422`) |
| Bookmarks, notes, reminders, forwarding | Personal bookmarks, private scratchpad, scheduled reminders, separately authorized forwarded copies | Backend isolation tests |
| Push notifications | FCM (Android), APNs (iOS; needs credentials) | FCM delivery observed in production; APNs unverified |
| Account | Open signup (8-character minimum), password reset, email confirmation, Google/Apple sign-in (hidden until configured), JSON export, deletion | Backend recovery, export and deletion tests |
| Safety | Block, report, admin review, message removal, suspension, audit log | Backend moderation suite; admin console against a local Worker |
| Diagnostics | Crashlytics (Android release), MetricKit uploads (iOS) | Unit tests; delivery requires physical devices |
| Captions and summaries | Author-consented server processing; hidden in the current apps | Earlier production proof; not exercised by current UI |

## Production

- **Deployed:** migration `0008.sql` applied and Worker version `d9b74d0a-e30f-4a4d-b2ac-2dc5396cfb7a` deployed on September 24, 2026. The pre-migration D1 Time Travel bookmark is `0000090a-00000036-000050f0-6631ffb9b5c12278a94b2304d3b9ea8b`.
- **Smoke test passed:**
  - health, including the legacy host
  - provider capabilities
  - public policy pages
  - admin routes hidden
  - 8-character minimum
  - signup and `/api/me`
  - diagnostics intake
  - the delete tombstone refusing a late create
  - a text message
  - account deletion (the temporary account was removed)
- **Not configured yet:** email (password reset stays hidden), `SUPPORT_EMAIL`, `ALERT_EMAIL`, `ADMIN_URL`, APNs.

## Automated checks

Run locally and in [CI](../.github/workflows/ci.yml):

- **Backend:** TypeScript check. Integration suites against a local Worker cover authorization, retries, interrupted uploads, deletion races, moderation, recovery and limits. Unit tests cover MP4 inspection and media reconciliation.
- **Admin:** typecheck, lint, session-cookie tests and a production build.
- **Android:** unit tests, lint, and debug and instrumentation builds. Emulator tests (`DraftDiscardTest`, `LiveUplinkTest`, `GenoaFlowTest`) need an emulator and are run manually.
- **iOS:** unit and integration tests on a simulator against an isolated local Worker.

## Before public release

Implemented is not the same as verified on real devices or in production. Open items:

- **Physical devices:**
  - two-device Android↔iOS exchange and live replay
  - front/back rotation
  - long recordings
  - background and lock-screen behaviour
  - low storage
  - weak-network restart
  - large imports
  - notification routing
  - MetricKit delivery
  - TalkBack, VoiceOver and large text
- **Android live capture:** the camera stops when the live screen goes away. Delivery and finishing continue, but capture does not run in a foreground service.
- **Configuration:**
  - email provider (`RESEND_API_KEY`, `EMAIL_FROM`), `SUPPORT_EMAIL`, `ALERT_EMAIL`
  - APNs credentials
  - Google/Apple sign-in credentials
  - Crashlytics enabled in Firebase
  - Cloudflare error-rate, billing and storage notifications
- **Operations:** deploy the admin console and grant admins. Run the [restore drill](OPERATIONS.md#backups-and-restore) against production. R2 media has no point-in-time backup.
- **Policy and stores:** legal review of the privacy and terms pages, store privacy declarations, reviewer account.
- **Known test gap:** `GenoaFlowTest#textNotesAndVoiceSurviveRealDelivery` fails at a late filmstrip lookup; not yet diagnosed.
