# Genoa Android implementation decisions

The original cross-platform plan is preserved in PLAN.md. The Android-first request supersedes its Expo client recommendation.

- Kotlin and Jetpack Compose implement a native Android application (Android 10+).
- CameraX records HD video; MediaRecorder records voice. Camera2 and MediaCodec supply incremental H.264/AAC packets for live video. The native MPEG-TS writer packages HLS segments while MediaMuxer retains an MP4 replay.
- Media3 provides authenticated HTTP Range/HLS playback, speed controls, resume, and a foreground media session for background listening.
- SQLite owns durable drafts and cached history. WorkManager retries bounded multipart uploads. Keystore encryption protects the bearer session. Drafts are scoped to the signed-in account.
- One Hono Worker owns HTTP routes, scheduled maintenance, and queue consumption. D1 is authoritative for membership and messages. Private R2 stores recordings, live segments, and extracted audio. A Durable Object per conversation sends synchronization hints.
- Uploads use 8 MiB authenticated parts through the Worker into R2 multipart upload. This deliberately avoids distributing S3 credentials or introducing a presigning service. Each request is bounded; playback streams through the Worker. This supersedes the plan's direct presigned upload proposal for this beta.
- Native AAC extraction provides bounded two-minute inputs to Workers AI. Delivery completes independently of optional caption preparation and gallery saving. Server transcription requires the author's opt-in; disabling consent clears generated text.
- Better Auth uses its native D1 adapter and email/password authentication. This beta does not implement Apple/Google OAuth or email password recovery. Firebase Cloud Messaging delivers Android notifications directly; Expo Push is unnecessary.
- Invitations gate account creation and group membership. Published media has no age-based expiration. Beta limits are disclosed as 8 GB and 24 hours per message.

There are no billing or entitlement checks: every beta account receives the implemented features. Family subscription products, paid passes, broadcast channels, and iOS are outside the Android beta scope. Private means server-authorized access, not end-to-end encryption.

Production and local development use separate Wrangler configuration files. Never point automated destructive tests at a user's populated database. The development integration suite creates its own test accounts and conversations.

## Product name and compatibility

Genoa's website is https://talkgenoa.com. This is the website address, not a replacement API endpoint. The canonical API is https://genoa-api.hp-accounts.workers.dev. The website domain is not configured by this change.

The Android application ID and Kotlin package (`app.gather`), Firebase registration, session keystore alias (`gather_session`), and local database filename (`gather.db`) are stable compatibility identifiers. Keeping them preserves installed-app upgrades, encrypted sessions, drafts, and persisted WorkManager jobs. The primary Cloudflare Worker, D1 database, R2 bucket, and queues now use Genoa names after a verified migration. The previous API address forwards through a service binding for installed beta builds. Firebase display names use Genoa; its project ID and registered Android identity remain stable. The Durable Object namespace retains its existing internal identity. See [the service migration record](SERVICE-RENAME.md).

New invitations use `genoa://join/<token>`. Android also accepts the previous `gather://join/<token>` scheme so existing invitations continue to work. Competitor names and source links in the original planning record identify external products and are not Genoa branding.
