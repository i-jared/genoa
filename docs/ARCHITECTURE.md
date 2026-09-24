# Genoa native implementation decisions

The [product plan](../PLAN.md) defines scope; this document describes the native implementation.

- Kotlin and Jetpack Compose implement a native Android application (Android 10+).
- CameraX records HD video; MediaRecorder records voice. Camera2 and MediaCodec supply incremental H.264/AAC packets for live video. The native MPEG-TS writer packages HLS segments while MediaMuxer retains an MP4 replay.
- Media3 provides authenticated HTTP Range/HLS playback, speed controls, resume, and a foreground media session for background listening.
- SQLite owns durable drafts and cached history. WorkManager retries bounded multipart uploads. Keystore encryption protects the bearer session. Drafts are scoped to the signed-in account.
- One Hono Worker owns HTTP routes, scheduled maintenance, and queue consumption. D1 is authoritative for membership and messages. Private R2 stores recordings, live segments, and extracted audio. A Durable Object per conversation sends synchronization hints.
- Uploads use 8 MiB authenticated parts through the Worker into R2 multipart upload. This deliberately avoids distributing S3 credentials or introducing a presigning service. Each request is bounded; playback streams through the Worker. No presigning service is required.
- Native AAC extraction provides bounded two-minute inputs to Workers AI. Delivery completes independently of optional caption preparation and gallery saving. Server transcription requires the author's opt-in; disabling consent clears generated text.
- Better Auth uses its native D1 adapter and open email/password signup. Google/Apple browser flows use proof-bound, expiring single-use handoffs; provider buttons remain hidden until credentials are configured. Firebase Cloud Messaging delivers Android notifications directly; Expo Push is unnecessary.
- Moderation and operations live in a separate Next.js console (`admin/`, on Vercel). It stores nothing and calls the Worker's `/admin/*` routes server-side with an admin's session; D1's `admins` table is the only grant. Suspensions are enforced by the Worker at session creation and on every API request. See [operations](OPERATIONS.md).
- Password reset and email confirmation use Better Auth with the Resend HTTP API; the reset link opens a script-free page served by the Worker. Uploaded MP4s are checked for a playable H.264/HEVC or AAC track by walking box headers with range reads, and every account has a storage quota. iOS uploads MetricKit diagnostics to the Worker; Android uses Firebase Crashlytics.
- Account creation is open; invitations authorize joining conversations. Published media has no age-based expiration. Beta limits are disclosed as 8 GB and 24 hours per message.

There are no billing or entitlement checks: every beta account receives the implemented features. Family subscription products, paid passes, and broadcast channels remain outside the implemented beta scope. Private means server-authorized access, not end-to-end encryption.

Production and local development use separate Wrangler configuration files. Never point automated destructive tests at a user's populated database. The development integration suite creates its own test accounts and conversations.

## Product name and compatibility

Genoa's website is https://talkgenoa.com. This is the website address, not a replacement API endpoint. The canonical API is https://genoa-api.hp-accounts.workers.dev. The website domain is not configured by this change.

The store release uses `app.genoa` on Android and iOS. Android retains the internal Kotlin namespace `app.gather`, session keystore alias `gather_session`, and database filename `gather.db`. Firebase has a separate `app.genoa` Android registration in the existing project. The earlier `app.gather` beta is a separate installation: changing the application ID does not migrate its encrypted sessions, local drafts, or WorkManager jobs. Keep that beta installed until its unsent drafts are handled; existing server accounts remain usable in Genoa. The primary Cloudflare Worker, D1 database, R2 bucket, and queues now use Genoa names after a verified migration. The previous API address forwards through a service binding for installed beta builds. Firebase display names use Genoa; its project ID remains stable, and the earlier Android registration remains available for existing beta installs. The Durable Object namespace retains its existing internal identity.

New invitations use `genoa://join/<token>`. Android also accepts the previous `gather://join/<token>` scheme so existing invitations continue to work.

## Native iOS — September 21, 2026

The iOS app lives in `ios/` and uses SwiftUI, AVFoundation, Keychain, Photos, background URLSession transfers, and BGProcessing. It shares HTTP contracts and design assets with Android, rather than sharing platform internals. LocalStore is an account-scoped actor with atomic, data-protected persistence for draft metadata, live fragments, cached watch windows, and revisioned progress. Outbox reconciles 8 MiB server-confirmed upload parts and uses stable message IDs. Accepted media is reclaimed locally after publication; private unsent files remain on the phone.

AVCaptureSession supplies video/audio samples to a local H.264/AAC MP4 writer and, during explicit live sharing, a segmented AVAssetWriter. Apple HLS uses initialization MP4 plus `.m4s` fragments; the existing Android `.ts` format remains supported by the same authorized endpoints. LiveSegmentSink persists independently of network speed. Both formats use the existing live_segments table; no new database migration is required. Message deletion also removes the iOS initialization object. AVPlayer uses the signed Better Auth cookie through the public AVFoundation cookie option for MP4, playlists, and child segments.

The APNs sender uses a scoped Apple signing key supplied as a Worker secret. Device records now distinguish Android, iOS sandbox, and iOS production; device-specific unregister avoids removing another phone's notification registration. Existing Android FCM clients remain compatible. Background listening has one AVPlayer owner with lock-screen commands. iOS camera capture stops and finalizes when backgrounded; upload continuation remains subject to OS scheduling.

The iOS-compatible Worker was deployed on September 21, 2026; Apple credentials remain unconfigured. See [iOS setup](../ios/README.md) and the [verification record](FEATURES.md).

## Deployment compatibility

The legacy `gather-api` Worker forwards to Genoa through a service binding; keep it while installed beta clients depend on it. Its source and configuration remain in `backend/src/compatibility.ts` and `backend/wrangler.compatibility.jsonc`. The original `gather` D1 database and empty `gather-media` R2 bucket were retained as rollback resources after the September 16 migration. The old database is a frozen snapshot, not a current replica; switching back requires reconciliation. The migration verified all 26 tables and 75 rows before cutover. The private SQL backup is retained locally under `.local/backups/` and is never a repository or release asset.

Android playback connects its service while the watch request loads, defers filmstrip extraction until the selected first frame, decodes thumbnails serially at up to 320×320, and uses a 4 MiB thumbnail cache. Initial buffering is 500 ms; rebuffering remains 2 seconds. Session reuse observes encrypted-preference changes and API JSON decoding runs on IO. These are implementation choices, not measured physical-device speedup claims.
