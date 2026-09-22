# Genoa for iOS

Native SwiftUI application for iPhone and iPad, targeting iOS 17+. It uses the same Workers/D1/R2 service and Genoa identity assets as Android. Open `Genoa.xcodeproj` in Xcode 26 or later and run the **Genoa** scheme. There are no third-party iOS dependencies.

## Build and run

The Xcode project and shared scheme are checked in. If source files or build settings change, regenerate with `xcodegen generate --spec ios/project.yml` from the repository root (XcodeGen is a development tool, not an app dependency).

For a simulator, use ordinary Xcode signing; Keychain integration tests require the simulator's ad-hoc signature. Do not disable code signing for running tests. Device builds require selecting your Apple development team and provisioning `app.genoa`, including Push Notifications. Debug uses the APNs sandbox entitlement; Release uses production. Change the bundle identifier and the backend APNs topic together if necessary.

An unsigned device compilation can be checked without Apple credentials:

```sh
xcodebuild -project ios/Genoa.xcodeproj -scheme Genoa \
  -sdk iphoneos -configuration Release -derivedDataPath /tmp/genoa-ios-release \
  CODE_SIGNING_ALLOWED=NO build
```

## Local verification

Use an isolated simulator; tests create disposable local accounts and the Keychain test replaces the app's current session. The UI test resets the session only in Debug and only with a loopback server. No test is configured to create production content.

1. Install backend dependencies with `cd backend && npm ci`.
2. From the root, run `./ios/scripts/test-server.sh` in a separate terminal. This creates fresh D1/R2 state under `/tmp`, applies existing migrations, and starts the Worker on port 18888 with synthetic credentials.
3. Run the tests with your simulator's ID:

```sh
xcodebuild -project ios/Genoa.xcodeproj -scheme Genoa \
  -destination 'platform=iOS Simulator,id=YOUR_SIMULATOR_ID' \
  -parallel-testing-enabled NO -derivedDataPath /tmp/genoa-ios-build test
```

The integration tests exercise native video writing, real multipart upload, authorized AVPlayer MP4/HLS playback, watch progress, and deletion. Persistence tests cover app-store reopening, account isolation, interrupted finalization, server-confirmed part resume, stale progress acknowledgments, revoked access, draft deletion, corrupt storage, and Keychain persistence. UI tests attach synthetic screenshots to the `.xcresult` bundle.

To run the app manually against the test server, set the scheme environment variable `GENOA_API_URL=http://localhost:18888` in Debug. Release always starts against the configured production endpoint. No arbitrary remote endpoint override is accepted.

## Ownership and lifecycle

- `AppModel` owns the signed-in account, navigation, foreground synchronization, and service instances. Keychain holds the signed session token. No credentials appear in logs or media URLs.
- `LocalStore` serializes account-scoped draft files, live fragments, bounded watch pages, and coalesced watch progress. Atomic writes use iOS data protection. A malformed store fails visibly rather than being replaced with an empty one.
- `Outbox` publishes stable IDs, reconciles confirmed server parts, and marks Sent only after acceptance. Saved private drafts never upload until Send. File-backed background URLSession transfers survive suspension; BGProcessing/foreground/connectivity opportunities reconcile and continue pending work. iOS controls background scheduling; force-quitting suspends automatic work until reopening.
- `CaptureEngine` owns AVCaptureSession and AVAssetWriter on a serial queue. Private recordings use H.264/AAC MP4 or AAC M4A. Live recording writes a local MP4 alongside two-second fragmented MP4 HLS segments. Backgrounding stops capture and saves/finalizes; iOS does not promise continued camera capture while backgrounded.
- `PlaybackController` owns the sole published AVPlayer, background listening, lock-screen commands, interruptions, speed, and durable progress. Selection starts at zero to match current Android; stored watch position and completion remain synchronized. Private media uses AVFoundation's public signed-cookie option, including HLS initialization and segment requests. First-frame readiness covers video preparation.
- Local caches support connection loss; authorization failures revoke visible data and stop playback. The backend remains authoritative for reads, media, permissions, deletion, and all mutations.

## Backend rollout and Apple configuration

The accompanying backend changes are backward-compatible and require **no D1 migration**. They add `live_fmp4`/`apns` capability flags, authorized HLS initialization and `.m4s` endpoints, APNs delivery, platform-aware device registration, and device-specific unregister. Android's MPEG-TS and FCM paths remain supported.

The updated Worker was deployed on September 21, 2026 as version `be64f420-9921-472c-b2df-4fc28e659efe`. Production health and unauthenticated access checks passed; see the [rollout record](../docs/FEATURES.md#approved-ios-compatible-backend-rollout--september-21-2026). The app checks `live_fmp4` before publishing a live message. `APNS_CREDENTIALS` remains unconfigured, so iOS push delivery is not yet enabled.

Set an `APNS_CREDENTIALS` Worker secret containing this structure, using a real Apple Push Notifications authentication key:

```json
{"team_id":"YOURTEAMID","key_id":"YOURKEY_ID","bundle_id":"app.genoa","private_key":"-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----"}
```

Never commit the secret or a `.p8` key. The APNs sender chooses sandbox or production from the registered device platform. Notifications carry generic text and exact conversation/message/event IDs. The server honors the existing membership, block, and notification rules. On-device receipt, cold-launch routing, token rotation, and multi-device sign-out require physical-device validation.

## Release gates

See [FEATURES.md](../docs/FEATURES.md) for measured verification. Simulator compilation and synthetic media tests do not establish full physical-device parity. Remaining gates include two-device iOS/Android camera and live audio/video, live interruption/rotation, long captures and low storage, OS-terminated/background transfers, APNs credentials and delivery, Photos/Files export, VoiceOver/Switch Control/Dynamic Type, privacy/App Store declarations, and signing. Captions/transcripts remain hidden to match current Android. Universal HTTPS invite links, social sign-in, and password recovery remain outside the existing implemented Android feature set.

Apple references: [background file transfers](https://developer.apple.com/documentation/foundation/downloading-files-in-the-background), [segmented asset writing](https://developer.apple.com/documentation/avfoundation/avassetwriter/preferredoutputsegmentinterval), and [APNs requests](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns).

## Distribution artifacts

The release handoff in [store/README.md](../store/README.md) includes a signed App Store Connect IPA with matching dSYMs, App Store screenshot sets for iPhone and iPad, and default/dark/tinted app icons compiled into the app. `scripts/release/ios.sh --cloud-sign` archives and exports locally using Xcode cloud-managed distribution signing when an authorized team is supplied. The owner authorized Whatado, LLC (`DCWP7J7692`), and `app.genoa` was provisioned. The exported IPA is signed and verified with production push permissions. Cloud signing resolved the initial local Keychain blocker. Backend APNs credentials and physical-device verification still remain.
