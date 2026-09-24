# Architecture

The [product plan](../PLAN.md) defines scope. This document describes how the system is built.

## Ownership

- **D1** owns accounts, conversations, membership, messages and permissions.
- **R2** (private) owns media: recordings, live segments, extracted audio and diagnostics payloads.
- **The phone** owns unsent drafts until the server accepts them.
- **Live events** from a Durable Object per conversation are hints to resynchronize with the API, never a second source of truth.

## Backend

One [Hono](https://hono.dev) Worker (`backend/`) serves HTTP routes, runs scheduled maintenance every five minutes, and consumes a queue of idempotent jobs (notifications, deletions, AI processing). Jobs that exhaust their retries land in a dead-letter queue and are recorded for redrive.

- **Auth:** [Better Auth](https://www.better-auth.com) with its D1 adapter. Email/password signup is open. Google and Apple browser sign-in use proof-bound, expiring, single-use handoffs, and their buttons stay hidden until credentials are configured. Password reset and email confirmation are sent through Resend. The reset link opens a script-free page served by the Worker.
- **Uploads:** 8 MiB authenticated parts go through the Worker into an R2 multipart upload. No storage credentials or presigned URLs reach clients. Uploads and messages use client-generated stable IDs, so every step is safe to retry.
- **Publication:** a message becomes `ready` only in a single conditional batch that also checks the message still exists and its author is still a member. Uploaded MP4s must contain a playable H.264/HEVC or AAC track; this is checked by walking box headers with range reads. Each account has a storage quota.
- **Deletion:** revokes access immediately, then a job removes media, derivatives and queued work. A delete that arrives before its message exists leaves a tombstone so a late request can't create it. A periodic sweep reconciles R2 against D1.
- **Playback:** media and HLS playlists stream through the Worker with membership checks on every request, including byte ranges and live segments.
- **Push:** Firebase Cloud Messaging (Android) and APNs with a token-based key (iOS), sent from jobs.
- **AI:** optional and author-consented. Native AAC extraction feeds bounded two-minute chunks to Workers AI for captions and summaries. Delivery never waits for it, and revoking consent clears generated text. The native apps currently hide these controls.

## Android

Kotlin and Jetpack Compose, Android 10+ (`android/`).

- **Capture:** CameraX records HD video; MediaRecorder records voice. Live sharing uses Camera2 and MediaCodec for incremental H.264/AAC, a native MPEG-TS writer for HLS segments, and MediaMuxer for the replay MP4.
- **Drafts and uploads:** SQLite holds account-scoped drafts. `DraftUploader` and WorkManager deliver them. Discard writes a tombstone first and reconciles server deletion by stable ID.
- **Live delivery:** `LiveUplink` and `LiveEndWorker` own delivery and finishing, outside any screen, with bounded persisted buffering.
- **Playback:** Media3 with authenticated Range/HLS requests, speed control, resume and a foreground media session for background listening.
- **Security and diagnostics:** Keystore encryption protects the session. Crashlytics is enabled for release builds only.

The package namespace is `app.gather` for historical reasons; the application ID is `app.genoa`.

## iOS

SwiftUI and AVFoundation, iOS 17+ (`ios/`, generated from `project.yml` with XcodeGen).

- **Storage:** `LocalStore` is an account-scoped actor with atomic, data-protected persistence for drafts, live fragments, cached watch windows and watch progress.
- **Uploads:** `Outbox` reconciles server-confirmed upload parts over background URLSession transfers, using stable IDs and discard tombstones.
- **Live sharing:** an AVAssetWriter produces HLS fragmented MP4 (`init.mp4` plus `.m4s`); Android's `.ts` segments use the same endpoints.
- **Playback and diagnostics:** AVPlayer authenticates with the session cookie. MetricKit crash and hang reports are redacted and uploaded to the Worker.

## Admin console

A separate Next.js app (`admin/`) on Vercel for moderation and operations. It stores nothing: every request goes server-side to the Worker's `/admin/*` routes with an admin's session, sealed in an encrypted httpOnly cookie. D1's `admins` table is the only grant, and non-admins receive 404. See [operations](OPERATIONS.md).

## Boundaries

- **Privacy:** "private" means server-authorized access, not end-to-end encryption.
- **Scope:** there is no billing; every account gets every feature.
- **Limits:** 8 GB and 24 hours per message, with no automatic expiry of published history.
- **Environments:** production and development use separate Wrangler configurations. Automated tests create their own accounts and must never target a populated database.
- **Legacy deployment:** the `gather-api` Worker (`backend/src/compatibility.ts`) forwards to the Genoa API for beta builds installed before the rename. Android still accepts the older `gather://join/<token>` links.
