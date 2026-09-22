# Production readiness

**Genoa remains beta software with unresolved release gates.** Signup is now open; do not mistake that for production certification. The core flow is implemented, but the remaining reliability and release gates below prevent a public-production sign-off. This review covers Android capture/drafts/upload/playback, API authorization/media/reactions, deletion, live events, jobs, configuration, and release packaging. It is a code review with targeted tests, not a penetration test or a long-duration device certification.

## Findings to address before wider use

### P1 — Make deletion win against in-flight upload completion

[Upload completion](../backend/src/media.ts) can create the R2 object before its second `messageAccess` check. If deletion and its cleanup job run between the initial authorization and multipart completion, that second check throws before the later cleanup branch. An object created after the deletion job's scan can remain orphaned. The existing deletion tests check access and ordinary cleanup; they do not prove this precise interleaving.

Add a finalization/deletion ownership rule and cleanup on every post-upload authorization failure, plus reconciliation for orphaned keys. Test deletion paused just before R2 completion and resume it after the deletion job finishes. Verify both database state and absence of the object.

### P1 — Make draft discard atomic with worker cancellation

[Repository.discard](../android/app/src/main/java/app/gather/data/Repository.kt) requests WorkManager cancellation, then deletes remotely and locally without awaiting cancellation or storing a discard tombstone. [UploadWorker](../android/app/src/main/java/app/gather/data/UploadWorker.kt) writes draft snapshots with an upsert. An in-flight request or late worker write can race the discard; a remote 404 is not proof that a concurrently starting upload cannot subsequently create the message.

Persist a terminal discard state first, make worker updates conditional, and reconcile remote deletion by stable ID. Exercise discard during setup, part transfer, finalization, loss of the final response, and process restart.

### P1 — Bound and recover live capture independently of the screen

[LiveScreen](../android/app/src/main/java/app/gather/ui/LiveScreen.kt) owns capture, pending segments, and finalization in a composition-scoped coroutine. Its segment channel is unbounded and retries every error indefinitely, including terminal permission errors. A prolonged outage accumulates queued files, while leaving the composition cancels its recovery work. A saved draft exists, but that alone does not prove finalization and delivery survive interruption.

Move durable finalization/retry ownership outside the composable, stop retries for revoked access, bound buffering/disk use, and define low-storage behavior. Test backgrounding, screen lock, fold/unfold, permission revocation, process death, and reconnect during a long capture on two real devices. Also restore unmistakable live consent in preflight: the current camera button is labeled only “Record” although it starts sharing immediately.

### P1 — Revoke every live subscription when deleting an account

[Account deletion](../backend/src/account.ts) sends the Durable Object revoke request only for conversations the person owns, then removes all memberships. Existing sockets in conversations where they were an ordinary member remain open. [ConversationRoom](../backend/src/realtime.ts) rechecks membership on incoming socket messages, but broadcasts outgoing events without that check. Those sockets can still receive synchronization/presence hints until closed. Subsequent HTTP media requests are still authorized and rejected; this finding is about the live subscription.

Collect every membership before deletion and revoke all corresponding sockets. Verify deletion of a non-owner account while connected to several rooms, with another member emitting events afterward.

### P2 — Persist watch progress while offline — addressed in 0.2.8

The original review found progress sent without durable retry. The 0.2.8 follow-up adds account-scoped SQLite progress, coalesced retry, monotonic completion, revision-specific acknowledgments, and service-owned background progress. Offline failure/store-reopen/reconnect and stale-response tests cover this correction; see [current verification](FEATURES.md). Physical-device weak-network soak testing remains a release gate.

### P2 — Enforce media and resource limits beyond file extensions

[Media publication](../backend/src/media.ts) checks a few magic bytes, size, and declared MIME, not decodability. [Import](../android/app/src/main/java/app/gather/data/Repository.kt) copies a whole provider stream before checking usable metadata and has no local byte cap. Malformed or incompatible files can reach publication or exhaust phone storage. Reaction arrays and some account/history aggregates are also unbounded, and there is no aggregate per-account storage quota.

Validate supported codecs/dimensions/duration before publication, fail unsupported imports clearly, enforce streaming byte limits, bound JSON and reaction responses, and introduce measurable account/storage limits. Do not advertise arbitrary video imports until this is verified.

## Release and operating gates

- **Release signing:** [Android build configuration](../android/app/build.gradle.kts) now supports environment-based production signing. The earlier beta uses the development key. The `app.genoa` APK and AAB are signed with the newly authorized upload key; back up its private material and enroll in Play App Signing. Apple team/bundle provisioning and cloud-managed App Store signing/export are complete; the final IPA signature and production push entitlement are verified. See [the release handoff](../store/README.md).
- **Account recovery:** [Authentication](../backend/src/auth.ts) enables email/password but has no configured verification or password-reset delivery. Add and test recovery before public enrollment.
- **Operations:** queue retries, a dead-letter queue, and logs exist. Alerting, dead-letter triage/redrive, cost/storage alerts, and a rehearsed metadata/media restore procedure remain unverified. A recovery bookmark is not a restore drill.
- **Repeatable releases:** the source is now in Git and local release scripts are supplied; no CI workflow was found. Review and commit the release changes and run builds, tests, migrations, and deployment checks in a repeatable pipeline.
- **Device/accessibility evidence:** verify two-device send/watch/reply, long recordings, weak-network restart/retry, interruptions, large text, TalkBack, and an optimized signed build. Emulator screenshots alone cannot establish camera, battery, or background behavior.

## Live error recovery

The September 21 source review also found that `LiveScreen.finishWithReplay` can fail while saving or scheduling upload without exposing a handled recovery state, and capture-start errors may disappear when the screen closes. Preserve recoverable files, make finalization retryable outside the screen, and keep failures visible with a deliberate recovery action. These findings were not reproduced through physical-device fault injection and remain unverified as fixed.

Prior build/emulator evidence and current selection behavior are consolidated in [FEATURES.md](FEATURES.md). Historical findings above are retained until explicitly reproduced or resolved; repository cleanup is not a fresh security review.
