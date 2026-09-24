# Product plan

Genoa is a private video messenger for individuals and small groups. The core path is to find a conversation, watch a message, record a reply, and know whether it sent.

## Product scope

- Four core surfaces: conversations, watch, recorder, and settings.
- Native Android in Kotlin/Compose and native iOS in SwiftUI/AVFoundation.
- Recorded video, voice, photos, restricted imports, and explicit live sharing.
- Direct and group conversations, invitations, watched status, replies, and timed reactions.
- Background listening, playback speed, saving, bookmarks, forwarding, private notes, and reminders in context.
- Durable account-scoped drafts and retryable uploads that survive restarts and connection loss.
- Server-authorized private media, account export/deletion, blocking, and reporting.
- Optional author-consented transcription and summaries; current native UI hides these controls.

All implemented features are available without billing. Paid tiers, family subscriptions, broadcast channels, arbitrary-format transcoding, and end-to-end encryption are outside the current implementation. Published history has no automatic expiry; current per-message limits are 8 GB and 24 hours. Long recordings remain a requirement, subject to measured device and service limits.

## Ownership and architecture

D1 owns messages and permissions; private R2 owns media; the phone owns unsent drafts. Live events prompt synchronization with the authoritative API. The backend uses Hono on Cloudflare Workers, with Queues for retryable jobs and Durable Objects for conversation events. See [architecture](docs/ARCHITECTURE.md) for upload, playback, and platform details.

The earlier Expo, direct presigned-upload, and separate media-service proposals are superseded by the native implementation. Add infrastructure only when a demonstrated feature requires it.

## Delivery priorities

1. Make recording, sending, and watching reliable, including permission changes, duplicate requests, interrupted uploads, and deletion races.
2. Prove two-device Android/iOS exchange, live replay, long recording, background behavior, and weak-network recovery on physical devices.
3. Complete accessibility, account recovery, provider/push configuration, operational monitoring, and restore drills.
4. Publish signed releases with repeatable builds, matching crash symbols, and verified store information.

[Feature status](docs/FEATURES.md) records verification and the remaining release gates. Neither simulator success nor a signed build certifies public-launch readiness.
