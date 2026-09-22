# Genoa feature inventory and verification

This is an engineering status document, not a claim of production readiness.

Genoa's website address is [talkgenoa.com](https://talkgenoa.com).

| Feature | Implementation | Verification |
|---|---|---|
| Native Android app | Kotlin/Compose; four primary surfaces | Debug APK builds; lint passes |
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
| Blocking / reporting | Server block filters and stored moderation reports | Admin review process still manual |
| Account controls | JSON export; account removal and media deletion queue | API export, explicit confirmation, and session-revocation tests passed |

Before wider release: test on two physical Android devices, slow/offline networks, interrupted uploads, camera rotation/backgrounding, long recordings, accessibility/font scaling, and signed release builds. Establish account recovery, moderation operations, cost alerts, backup/restore drills, and release signing. Do not describe this beta as full production parity until those gates pass.

## Deployment and remaining release gates

The backend is deployed at `https://genoa-api.hp-accounts.workers.dev` with D1, private R2, Queues, Durable Objects, Workers AI, and Firebase Cloud Messaging. Authentication and invitation secrets are configured. The beta access code is in the ignored, owner-readable `artifacts/BETA-ACCESS.txt` file.

Verified on September 16, 2026:
- Android development and optimized release builds compile; lint reports 0 errors and 31 warnings (mostly dependency updates, style suggestions, and lifecycle checks).
- The final emulator run passed both integration tests (100.5 seconds), covering text, private notes, voice recording, explicit sending, duration, live segments, completed replay, authenticated voice/HLS playback, speed changes, and screen-off audio.
- Local and production API checks cover authentication, invitations, membership revocation, private media, byte ranges, duplicate finalization, reminders, forwarding isolation, account export, and account deletion. The final production run also passed two-part upload restart/resume and tail byte-range checks (36 seconds).
- An actual production queue notification arrived on the dedicated emulator.
- A 7.6-second synthetic voice sample produced an accurate transcript, summary, and two timestamped captions through production R2, Queues, and Workers AI. Revoking consent removed all generated text.
- ffprobe recognized native live segments as 1280×720 H.264 with AAC audio.

This is an installable beta, not a completed public-launch certification. Physical-device coverage, long-duration recordings, weak-network soak tests, arbitrary-format import transcoding, accessibility audits, email recovery/social login, backup drills, monitoring alerts, moderation operations, and production release signing remain. Recordings are limited to the documented formats; server-side codec normalization is not present. Forwarded media keeps its independent access permissions, but extracted AI audio chunks are not copied to the forwarded message. The unsigned optimized APK requires a release key; the installable APK uses a development key.


## Initial Genoa app rename verification — September 16, 2026

- Updated launcher/sign-in/conversation/settings branding, sharing copy, notification titles, media metadata, export names/folders, Android application/view-model/theme/resource names, backend package/authentication branding, and project documentation to Genoa. Settings links to https://talkgenoa.com.
- New API invitations use `genoa://join/<token>`; Android also accepts the previous invitation scheme. Stable installed-app, storage, Firebase, and deployed Cloudflare identifiers remain unchanged to preserve existing accounts, drafts, queued work, and data. See the architecture compatibility notes.
- Android version 0.1.1 (version code 2): debug APK, optimized unsigned release APK, and instrumentation APK builds passed. Debug lint passed with 0 errors and 31 existing warnings. Packaged debug metadata confirms the launcher label is Genoa.
- Backend TypeScript checks and the local API integration suite passed against an isolated test database, including the Genoa invitation URL assertion and existing authorization, retries, media, and account-deletion checks.
- Refreshed `artifacts/genoa-android-debug.apk`, `artifacts/genoa-android-release-unsigned.apk`, and `artifacts/SHA256SUMS.txt`. The prior screenshot artifacts are historical captures from before the rename.
- At this initial checkpoint, the rename had not yet been deployed to the production Worker or installed on a device. The service migration below supersedes the deployment status. Website hosting/DNS and HTTPS Android App Links for talkgenoa.com were not configured by this change; the existing API endpoint continues to serve the beta. Device flows were not rerun for this naming-only update; prior device verification is recorded above.


## Genoa services and visual guideline — September 16, 2026

- Published the [visual guideline webpage](https://genoa-design-guide.i-jared.chatgpt.site), with four core screen examples, palette, typography, components, delivery states, accessibility guidance, and the supplied reference. Static asset/link checks passed; browser rendering and interaction QA were not performed. This is a design sample, not an implemented Android redesign.
- Renamed the deployed Worker and both queues in place. Migrated D1 into `genoa` under an approved maintenance window and switched private media storage to `genoa-media`. All 26 tables and 75 rows, schema, indexes, triggers, views, and sequence counters matched before cutover. Both media buckets were empty; no private recordings needed copying.
- Production is restored at `https://genoa-api.hp-accounts.workers.dev`; the legacy beta endpoint forwards to it. Both return healthy responses and reject unauthenticated conversation access with 401. Worker authentication, invitation, and Firebase secrets remain bound. See [migration details and retained identities](SERVICE-RENAME.md).
- Firebase project, Android app, and push service-account display names now use Genoa. Existing Android/Firebase identities, encrypted session keys, and draft storage remain compatible.
- Android 0.1.2 (code 3) defaults to the new API and maps the previous default endpoint to it while preserving existing sessions. Debug and optimized unsigned release builds and debug lint passed. Updated APKs and SHA-256 checksums are in `artifacts/`. No new device run was performed.
- A production mutation test was blocked by automatic approval review because it would create/delete production test records. No such test records were created by that attempt; production checks for this migration are read-only. The backend TypeScript check and isolated local integration suite passed after the rename, covering authentication, invitations, membership revocation, private media/ranges, retries, resumable uploads, forwarding, and account deletion.
- talkgenoa.com website DNS/hosting and Android HTTPS App Links remain unconfigured. The private design guide uses its separate preview address.


## Android design implementation — September 16, 2026

Android 0.2.0 (version code 4) applies the design language to the existing feature set:

- Shared light/dark colors, serif headings, compact spacing, stable pastel avatars, and the supplied custom icon set.
- Home, Record, and Profile navigation; recording begins with a recipient chooser. Home includes accurately counted unwatched media and explicitly scoped conversation search.
- Sender-aligned message cards, viewing progress and delivery labels, contextual replies and media actions, and player controls with a service-backed mini-player. Existing notes, bookmarks, reactions, forwarding, reminders, saving, captions, summaries, and group/account controls remain available.
- Private video/voice recording, explicit stop/review/send, durable draft feedback, deliberate discard, and a separate explicit live-sharing flow. Queued, uploading, processing, blocked, and failed delivery states reflect actual local/server state.
- Grouped Profile preferences and account controls, system notification status, offline recovery notices, scalable layouts, and landscape recorder controls.

Debug, instrumentation, and optimized unsigned release builds passed; debug lint passed with 0 errors and 31 warnings. Emulator checks passed for text/private notes, explicit voice sending, authenticated and screen-off playback, live sharing/replay, mini-player reopening, Home/search/Profile/recipient navigation, and private video stop/review/keep-draft without publishing. Camera verification exposed and fixed a quality fallback that skipped 720p; the private-video test now waits for actual encoded data before stopping the slower synthetic camera. The local API suite and TypeScript checks passed, including the distinction between unread text and unwatched media. The backwards-compatible conversation preview/count API update was deployed, and production health returned healthy. New production AI or push mutation tests were not run for this redesign.

The 0.2.0 development APK was installed wirelessly on the owner's Motorola Razr 2024 with an in-place update; existing app data was retained. Installed version and successful launch were verified. Automated tests use only the dedicated emulator and disposable local accounts.

APK artifacts and SHA-256 checksums have been refreshed. Visual evidence is under `artifacts/design-review/`. The navigation/recipient/recorder flow also passed at 320dp width with 200% font scale and dark appearance; screenshots were inspected for readable, reachable controls. The same navigation test passed in landscape, including the side-by-side recorder layout. Long list titles/previews use ellipses, while profile details and recorder recipients wrap. Video cards use a playable fallback when a real thumbnail is unavailable; this change does not add a thumbnail-generation service. Physical TalkBack/switch-access, two-device scenarios, long names, extended capture, and weak-network/restart soak tests remain release checks; the earlier public-launch gates still apply.


## Compact thread, live-default reply, and player carousel — September 16, 2026

Thread, player, and conversation-creation changes on top of the 0.2.0 design language:

- Message reactions and Reply sit beside the card. Already-placed reactions stay on the card. Sender name and avatar appear only when the author changes.
- Reply context shows a media thumbnail when the source is video, photo, or voice. Tapping it opens that source in the player.
- **Record a reply** and Home **Record** open live sharing. Private video and voice stay under Reply options.
- New conversation chooses people first (people already in other conversations can be added immediately), then a name.
- The player keeps the current message full screen and a filmstrip of playable messages at the bottom. Video and voice advance to the next item when they end.

Backend additions: `GET /api/people`, optional `member_ids` on conversation create (known contacts only), and optional `reply_to` on live start. Isolated API tests against loopback 18787 passed, including people listing, adding a known contact, and rejecting an unknown contact. `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`, and `:app:lintDebug` passed with the Android Studio JDK. Emulator camera, live, and carousel interaction were not rerun in this pass.

## Watch-only conversation — September 16, 2026

The conversation destination is the watch surface. The chat bubble timeline is gone.

- Opening a conversation shows the current message full screen and a filmstrip of every message, including text and photos.
- Video and voice advance when they end. Photos dwell 4 seconds. Text dwells 3 to 8 seconds from word count, then advances. Text and photos are marked watched only after that dwell finishes while the watch surface is in the foreground.
- React and Reply sit on the side of the current message. Speed is one icon that opens a rate menu. Captions, transcript, summary, and the private note share one overflow.
- Reply-to still shows a media thumbnail and jumps to that message. Record a reply stays live. New conversation stays people, then name.
- Sender name appears only when the author changes from the previous filmstrip item.

`:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`, `:app:testDebugUnitTest` (WatchTest), and `:app:lintDebug` passed with the Android Studio JBR and `ANDROID_HOME`. Lint reported 0 errors and 33 warnings. `Gather_API_36` exited during startup after a missing `default_boot` snapshot, so connected emulator tests were not rerun. The owner's physical phone was connected and was not driven.

## Immersive watch and live chrome, September 16, 2026

- The message player and live-sharing screen use full-window media with overlay controls. Chrome auto-hides while a message plays. TalkBack touch exploration keeps controls visible. Live send stays persistent and only shows SHARING LIVE after the server accepts Start sharing live.
- Playback still uses `PlaybackService` as the only published player. Note, transcript, and summary stay on the player under More.
- `:app:compileDebugKotlin` and `:app:compileDebugAndroidTestKotlin` passed. `connectedDebugAndroidTest` passed all 4 tests on emulator-5554 (`Gather_API_36`), including voice open, mini-player reopen, live start/end, and private video review. The owner's physical phone was connected and was not driven.
- Physical TalkBack, switch access, reduced motion, and two-device live watch remain release checks.

## Timed reactions, start-at-zero, and live preview — September 16, 2026

Watch and send changes on top of the watch-only conversation:

- Auto-advance and filmstrip selection start each video at 0. The MediaController stays up across messages. Saved `position_ms` still records watch progress.
- Finished MP4s no longer keep the live TextureView rotation. CameraX private capture writes `targetRotation` from the display.
- Live preflight opens a front-camera preview as soon as permission and the TextureView are ready. Start sharing live still waits for the POST.
- Specific-message reply chrome is gone. New sends go on the filmstrip in order.
- One-tap emojis sit at the bottom of the watch screen. The kebab sits above them. The extra button opens `EmojiPickerView`. Captions, transcript, summary, and the transcribe setting are hidden.
- Reactions insert into `reaction_moments` with `position_ms`. One person can react many times on one video. Those emojis appear again in a 2.5s window around their time.

Timed multi-reactions need migration `0005.sql` on the Worker the phone uses. Until that ships, the phone UI still sends `position_ms` and the current production API keeps one emoji per person.

Verification on this pass, measured:
- Isolated Worker `TEST_API_URL=http://127.0.0.1:18787 npm test` passed, including two timed reactions on one message.
- `prove reactions` passed on a second isolated Worker.
- `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`, `:app:testDebugUnitTest` (6 WatchTest cases), and `:app:lintDebug` passed. Lint reported 0 errors and 33 warnings.
- Debug 0.2.1 (versionCode 5) installed on the owner's Motorola Razr 2024 at 17:34:53 and launched. The phone was not driven after launch. Emulator connected tests were not rerun. Production D1 was not migrated.

## Self-record rotation and watch end-state — September 16, 2026

- Live capture and preview use `VideoRotation.captureHint` from sensor plus display rotation, not sensor alone.
- After the last watch item ends, the player shows a front-camera preview and the camera/plus composer. Those controls stay hidden during playback.
- Playback reactions are a vertical rail to the right of speed: kebab, heart, laugh, fire, picker.
- Record and live start are camera icon buttons. Explainer copy such as people-can-watch was removed.

Verification on this pass, measured:
- `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`, and `:app:testDebugUnitTest` passed, including `watchChromeShowsReplyAfterTheLastItemEnds`.
- Debug 0.2.2 (versionCode 6) installed on the owner's Motorola Razr 2024 at 17:58:46 and launched.
- Automatic camera-orientation check did not run. `uiautomator dump` after launch showed the PIN keypad, not Genoa.

## Genoa production identity assets — September 16, 2026

- Created the [Genoa identity kit](../assets/brand/genoa/README.md): a custom G/conversation symbol, outlined Newsreader wordmark, horizontal and stacked logos, five color variants, transparent PNG exports, Android adaptive/round/themed/notification resources, favicons and web app icons, Play icon and feature graphic, and social graphics. The kit includes licensed font source, the imagegen concept and exact prompt, reproducible vector/export sources, asset inventory, checksums, and usage guidance.
- Android now references the adaptive launcher icon, the sign-in brand mark, and the alpha-only notification mark for push/uploads and Firebase's fallback notification metadata. The obsolete camera launcher drawable was removed. Interface typography and application identity remain unchanged.
- Visual review covered the final logo board, stacked logo, wordmark, store graphic, and circle/rounded-square/themed mask previews. The offline asset validator passed for dimensions, transparency, self-contained SVG paths, resource references, adaptive and web maskable safe circles, and checksums. APK inspection confirms the launcher icon packages foreground, background, and monochrome layers.
- `:app:assembleDebug`, `:app:assembleRelease`, and `:app:lintDebug` passed. The optimized release APK remains unsigned. Lint reports 0 errors and 33 warnings, including one harmless API-26 resource-folder warning because this portable asset set supports older Android versions than the app's minimum API 29. This does not constitute device or public-launch verification.
- This pass did not install on or operate the owner's phone. OEM launcher/themed icon, system splash, and notification appearance still need physical-device visual review. Store graphics are supplied, not uploaded; website assets are supplied, not deployed. Actual listing screenshots, release signing, and the previously documented launch gates remain separate requirements.

## Camera preview rotation and excessive crop — September 16, 2026

- Fixed the sideways, overmagnified Camera2 preview in live preflight/recording and the watch screen's front-camera reply preview. These camera surfaces previously reused the decoder rotation transform, adding a sensor rotation that the camera Surface already supplies, followed by a view-aspect zoom. Camera preview now corrects only display rotation and undoes nonuniform stretching before applying the minimum uniform center crop needed to fill the view. See [Android's Camera2 preview guidance](https://developer.android.com/codelabs/android-camera2-preview).
- A shared preview TextureView updates the transform on resize and display changes, including 180-degree turns that do not resize the view. It unregisters the display listener when detached. Preview rotation does not recreate the capture session. Encoded recording orientation hints, live decoder transforms, completed-video playback, and the CameraX private recorder are unchanged.
- `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:lintDebug` passed. All 14 unit tests passed: four new preview regressions (including square-pixel/minimal-crop invariants across four sensor orientations, four display rotations, and four view shapes), three existing recording/playback rotation cases, and seven watch cases. Lint reports 0 errors and 35 warnings; no new preview-transform warning was reported.
- Debug 0.2.4 (versionCode 8) installed successfully on the owner's Motorola Razr 2024 using `adb install -r`; package metadata confirms that version. App data was not cleared. Refreshed `artifacts/genoa-android-debug.apk` and its checksum. The unsigned release artifact was not rebuilt in this pass.
- Physical visual confirmation of the upright preview and framing, rotation while recording, and fold/unfold behavior remains pending. No recordings or live messages were created to test this fix. Unit tests and installation do not establish physical-camera correctness on their own.

## Watch layout, stable video reveal, and loading feedback — September 16, 2026

- The owner confirmed the 0.2.4 camera preview is now upright. This pass preserves that camera transform.
- Centered the record action independently of its adjacent plus control. Play, elapsed/total time, and playback speed align with the bottom emoji picker. Transport, seek bar, and filmstrip now occupy measured rows rather than overlapping fixed bottom offsets; reactions become horizontal on short landscape screens.
- The seek bar prefers the player's decoded duration over message metadata, holds its thumb while dragging, seeks once on release, and paints the full endpoint before advancing. The shared filmstrip stays visible in the post-watch camera preview and recording preflight. Selecting the already-focused final video replays it; preflight thumbnails return to watching without starting capture or sharing.
- Each new video source stays behind the camera backdrop until the decoder supplies its first frame and nonzero video size, playback is ready, and the view has completed layout. Both live and completed videos use the texture-backed player view; only live segments receive the explicit live rotation transform. This avoids switching surface types and showing intermediate sizing. See [Media3 surface guidance](https://developer.android.com/media/media3/ui/surface).
- Added compact named feedback for video loading/buffering, photo and voice loading, camera startup, live connection startup, recording finalization, and processing. Video/photo failures and stalled loads expose retry actions. Uploads show actual percentage and a progress bar; queued/failed drafts retain clear saved-on-phone status. Photos begin their viewing interval after successful loading.
- Verification: all 16 unit tests passed, including decoded-duration/end-position regressions. `WatchPresentationTest` and `PreparedVideoTest` passed on the dedicated API 36 emulator against a disposable local Worker. The UI run checked portrait and landscape alignment, an unobscured seek bar, real 6-second playback with intentionally stale 9-second metadata, first-frame readiness, replay, centered record/plus geometry, and filmstrip navigation from both camera previews. The failure test verified that a missing video remains covered and retry stays available. The layout flow passed again after the final camera-start indicator change. [Screenshots and test details](../artifacts/watch-review/README.md) use synthetic media only.
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:testDebugUnitTest`, and `:app:lintDebug` passed. Lint reports 0 errors and 36 warnings. Debug 0.2.5 (versionCode 9) installed with `adb install -r` on the owner's Motorola Razr 2024; package metadata confirms the version and 19:05:25 update time. App data was not cleared. Refreshed `artifacts/genoa-android-debug.apk` and its SHA-256 checksum; the unsigned release artifact was not rebuilt.
- Remaining device checks: confirm that playback startup no longer visibly transforms on the Razr, plus fold/unfold behavior, large text/accessibility, and weak-network media/upload behavior. Emulator layout/first-frame checks do not establish physical-camera smoothness. No recording or live message was created on the owner's phone during verification, and no backend deployment was needed.

## Brand integration, unread history, reaction presentation, and readiness review — September 16, 2026

- Genoa branding now covers Home, sign-in, About, launcher/round/themed icons, Android 12+ system splash, and push/upload/playback notification icons. The shared UI mark respects light/dark appearance, with scalable interface text. Store and web assets remain in the identity kit. OEM splash, themed icon, and notification appearance still need physical-device review.
- Conversations request a bounded server window around the first unseen incoming message across accessible history, falling back to the newest message. Scrolling backward/forward loads contiguous pages; refreshes preserve the focused message and loaded history while removing deleted items. Mini-player reopening retains its explicit message. HTTP authorization failures do not fall back to cached messages.
- Accepted outgoing reactions float upward from the emoji picker. Other people's timed reactions appear near the top in small name/emoji cards, stack up to three visible cards, and disappear in order even while playback is paused. Rewinding allows replay; seeking avoids dumping skipped reactions. Reduced motion uses a brief stationary acknowledgment. Reaction IDs make retries idempotent, and server responses now include the sender's name.
- Verification: all 21 JVM unit tests and three targeted API 36 emulator tests passed. The real player test covers opening the latest item after 60 earlier notes, loading older history without changing playback focus, sending an emoji, portrait/landscape controls, replay, and both pre-recording filmstrips. Separate UI tests verify stacked card expiry and covered/retryable failed video preparation. [Screenshots and logs](../artifacts/release-review/README.md) contain only synthetic data.
- Backend TypeScript checks, Worker deployment dry run, and all three local API/migration suites passed. Tests cover first-unseen history older than the latest 50 items, complete paging, access/block filtering, reaction identity/idempotency, deletion during refresh, and migration preservation of legacy reactions/writes. The production-dependency audit reported zero known vulnerabilities; this is not a security certification.
- Debug, instrumentation, and optimized unsigned release builds passed, as did debug lint (0 errors, 36 warnings). Versioned 0.2.6 (versionCode 10) APKs and checksums are staged in `artifacts/`; the generic APKs have not been replaced. The owner's Razr remains on 0.2.5, with app data intact.
- Production read-only checks found migrations `0001`–`0004` applied and three legacy reactions. The new Worker requires migrations `0005`/`0006`; these preserve the legacy table and mirror legacy writes during cutover. A D1 recovery bookmark was captured. Automatic approval review rejected applying the production migration without explicit rollout approval. No production schema change or Worker deployment occurred in this pass, and 0.2.6 must not be installed against the older production API.
- Completed the [production readiness review](PRODUCTION-READINESS.md). Recommendation: controlled invite-only beta, not public-production sign-off. Priority gaps include upload/deletion and discard/worker races, durable and bounded live capture, revocation of every account subscription, offline watch-progress retry, media/resource limits, signing, account recovery, monitoring/restore drills, and real-device interruption/accessibility tests. These broader findings are documented, not claimed fixed.

## Approved 0.2.6 beta rollout — September 16, 2026

- The owner explicitly approved the production migrations, Worker deployment, and Razr update, resolving the prior approval block. Captured a fresh D1 recovery bookmark and verified migrations `0001`–`0004` plus three existing reactions before applying `0005`/`0006`. Both migrations succeeded; all three original reactions matched their migrated copies, and the legacy table remains intact.
- Deployed `genoa-api` Worker version `661ae370-3651-44fa-96f5-66a5bf997c61`, including its scheduled and queue triggers. Production smoke checks passed for health, denied non-member access, first-unseen/latest selection, older paging, named timed reactions, retry deduplication, and the legacy message-list endpoint. Checks used two disposable accounts and a synthetic conversation; both accounts were deleted afterward and both sessions returned 401. No real conversations were used.
- Installed the tested development-signed 0.2.6 APK on the owner's Motorola Razr 2024 with `adb install -r`. Package metadata confirms versionCode 10, versionName 0.2.6, and update time 21:38:20. Launch returned `Status: ok`. App data was preserved; the phone was not driven through conversations or recording flows.
- Refreshed the generic debug and unsigned release APKs and SHA-256 checksums to match the versioned 0.2.6 artifacts. [Rollout evidence](../artifacts/release-review/README.md) includes migration/deployment logs, preservation checks, and smoke results. No recovery restore was performed.
- This is an invite-only beta rollout. The [production-readiness findings](PRODUCTION-READINESS.md), release signing, and physical two-device/camera/interruption/accessibility checks remain outstanding.

## One-tap reply recording — September 21, 2026

- Android 0.2.7 (versionCode 11) carries the watch screen's Record tap through the live-camera screen transition. The start request waits for camera/microphone permission and a valid surface, then starts once without a second tap. Permission denial cancels the request with an explanation; failed server startup requires deliberate retry. Immediate reply recording skips creating an extra preflight preview camera.
- Home recipient selection continues to open a preview without sharing. Private capture/review/send behavior is unchanged.
- Debug and instrumentation APK builds, all 21 JVM unit tests, and debug lint passed (0 errors, 36 existing warnings). Three targeted API 36 emulator regressions passed in 37.615 seconds against an isolated local Worker: one tap creates exactly one live message and produces HLS segments/completed MP4 replay; watch-to-reply starts with one tap after playback; Home recipient selection does not publish a message. The first watch-test attempt had a local trusted-origin mismatch; correcting the test backend configuration resolved it.
- [Emulator results](../artifacts/reply-record-review/emulator-tests.txt) and the [focused UX bug report](UX-BUG-REVIEW-2026-09-21.md) record evidence and remaining issues. Other reviewed bugs are documented, not claimed fixed. Physical recording timing, permission-dialog grant/denial, fold/unfold, and interruption checks remain unverified in this pass.
- Refreshed the development-signed debug APK and its checksum; `artifacts/genoa-android-0.2.7-debug.apk` preserves this version. The unsigned release APK remains 0.2.6. No backend deployment or production test messages were needed.
- Installed 0.2.7 on the paired Motorola Razr 2024 using `adb install -r`; package metadata confirms versionCode 11 and update time 20:11:59. App data was preserved. Launch succeeded; no real conversations or recordings were used for verification on the phone.

## Unread counts, reminders, offline progress, and performance — September 21, 2026

Android 0.2.8 (versionCode 12):

- Home refreshes immediately on resume and synchronization hints (including received push messages), then every five seconds while visible. Finishing an incoming message updates cached unread/unwatched counts while the server update is pending. Background polling stops with the activity lifecycle.
- Push jobs include the exact message ID. Separate notification PendingIntents preserve different reminders in one conversation. Cold and warm navigation fetch fresh conversations, select the target through an authorized bounded watch window, retain offline navigation for retry, and explain deleted targets. Notification navigation waits while recording.
- SQLite schema v3 stores account-scoped pending watch progress. WorkManager retries after connectivity returns; writes coalesce by message, completion stays monotonic, and acknowledgments only clear the revision actually sent. Unauthorized/deleted targets are dropped appropriately; authentication failures preserve unsynced progress for reauthentication. PlaybackService saves periodically and on pause/end/item change/teardown, including after leaving the watch screen. The previously requested start-at-zero selection behavior remains unchanged.
- Video startup connects the media controller while conversation data loads. Filmstrip media work waits for the selected video's revealed first frame; video thumbnails decode serially. The initial playback buffer is 500 ms instead of 1,000 ms; the normal rebuffer threshold remains 2,000 ms.
- The additional performance review found full-resolution thumbnail allocation, repeated Keystore decryption during UI refreshes, and JSON decoding on the calling/UI thread. Thumbnails now decode within 320×320 while preserving aspect ratio and use a 4 MiB cache budget. Session tokens are reused in memory while their encrypted preference is unchanged, with rotation/logout/endpoint changes observed across store instances and temporary Keystore failures retried. JSON decoding runs on IO. These changes preserve existing controls and feature behavior. See the [performance review](PERFORMANCE-2026-09-21.md).
- Verification: debug/instrumentation builds, all 21 JVM tests, and lint passed (0 errors, 37 warnings). Seven API 36 emulator tests passed in 70.835 seconds: Home/resume counts; offline failure/store reopen/coalescing/stale acknowledgment/account isolation/deletion; background playback progress; cold/warm/distinct reminder routing and deleted-target fallback; cached-session invalidation; real watch/history/replay/one-tap reply capture; covered/retryable failed video preparation. After the final temporary-Keystore-failure retry adjustment, builds/unit/lint passed again and the session invalidation test passed on the final APK. No physical-phone speedup percentage or latency benchmark is claimed.
- Backend TypeScript checks, deployment dry run, and all four local suites passed. They cover authorized exact-message selection, deleted/blocked/nonmember targets, monotonic watched counts, message/reminder FCM payloads, existing API flows, and migration compatibility. Production Worker `131c3e75-a71b-490c-9f22-a1dff0bc32d1` was deployed without schema changes. Read-only production health returned 200; unauthenticated conversation and targeted-watch requests returned 401. No production accounts or messages were created for these checks.
- [Verification artifacts](../artifacts/sync-review/README.md) include tests, deployment, smoke checks, and installation evidence. The development-signed generic debug APK and versioned 0.2.8 APK/checksums were refreshed. The unsigned release APK remains 0.2.6.
- Installed 0.2.8 on the paired Motorola Razr 2024 with `adb install -r`, preserving app data. Package metadata confirms versionCode 12 and update time 20:50:16. Cold launch returned `Status: ok`; no real conversations or recordings were used for verification.
- Still required: physical two-device notification delivery/background restrictions, weak-network/restart soak testing, and measured Razr first-frame/scrolling performance. Server-generated thumbnails, normalized fast-start MP4, and adaptive media renditions are not added by this update. The other [production-readiness findings](PRODUCTION-READINESS.md) remain open.
