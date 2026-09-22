# Genoa

Website: [talkgenoa.com](https://talkgenoa.com). This rename does not configure website hosting or DNS; the app continues to use the deployed API.

Native Android and iOS video messaging with a shared Cloudflare backend. The product plan is in [PLAN.md](PLAN.md); binding engineering and UI guidance is in [AGENTS.md](AGENTS.md). Implementation decisions and honest verification status live in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/FEATURES.md](docs/FEATURES.md).

The visual and interaction design, inspired by the supplied Genoa reference, is in [docs/DESIGN.md](docs/DESIGN.md). Android 0.2.0 applies its palette, typography, custom icons, navigation, contextual controls, and private recording/review flow. See [verification status](docs/FEATURES.md) for tested behavior and remaining device checks.

Explore the [visual design guideline webpage](https://genoa-design-guide.i-jared.chatgpt.site), with screen examples, colors, typography, components, and interaction states. Its source is in `design-guide/`; these are design samples, not a functioning messenger.

## Try the beta

Install `artifacts/genoa-android-debug.apk` on Android 10 or newer. It defaults to the deployed HTTPS backend. Use the private invitation code in `artifacts/BETA-ACCESS.txt` to create your account; then create a conversation and share its group invitation link. The APK uses a development signing key.

## Native iOS

Open [ios/Genoa.xcodeproj](ios/Genoa.xcodeproj) in Xcode 26 or later. The SwiftUI/AVFoundation app targets iOS 17+, uses the Genoa design system and existing accounts, and includes native recording, live HLS, durable uploads, playback, and contextual conversation/account controls. See [iOS build, test, and Apple configuration instructions](ios/README.md). The iOS-compatible Worker changes must be deployed for live sharing; signing, APNs configuration, and physical-device parity verification remain release steps.

## Run locally

Requirements: Node 22+, JDK 17 or newer supported by Gradle, Android SDK 36, Android 10+ device/emulator.

```sh
cd backend
npm ci
cp .dev.vars.example .dev.vars
# Set a long random BETTER_AUTH_SECRET and your BETA_INVITE_CODE in .dev.vars.
npm run migrate:local
npm run dev
```

Open `android/` in Android Studio. In the app's **Server connection** field, use `http://10.0.2.2:8787` for the Android emulator. Local HTTP is allowed only for emulator/loopback development hosts. The default server is the dedicated HTTPS Genoa deployment.

```sh
cd android
./gradlew :app:assembleDebug :app:lintDebug
```

The installable development APK is `android/app/build/outputs/apk/debug/app-debug.apk`. It uses the development signing key. Configure your own release signing before distribution through Google Play.

## Verification

With the local backend running:

```sh
cd backend
npm run check
npm test
```

Android instrumentation tests use an emulator and the local invitation code `local-genoa-beta`. They create their own accounts. Use a dedicated emulator with synthetic front and back cameras, never a user's populated device:

```sh
cd android
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

Installation and storage identifiers retain their existing values for upgrade compatibility; see [the compatibility notes](docs/ARCHITECTURE.md#product-name-and-compatibility).

## Cloudflare deployment

`backend/wrangler.production.jsonc` contains the dedicated Genoa D1, R2, Queues, Durable Object, and Workers AI bindings. Local state is separate in `wrangler.jsonc`. Apply migrations before deployment:

```sh
cd backend
npm run migrate:production
npm run deploy
```

Production API: `https://genoa-api.hp-accounts.workers.dev`. The previous beta address forwards to it. See [the service migration record](docs/SERVICE-RENAME.md) for verification and retained compatibility identities.

Required Worker secrets: `BETTER_AUTH_SECRET`, `BETA_INVITE_CODE`, `FCM_SERVICE_ACCOUNT`. Use Wrangler's secret input; never commit credentials. The Firebase Android configuration is a public client identifier, not a service-account credential.

## Operations

- Inspect Worker logs by request/job ID; never add message content or tokens to logs.
- Monitor the `genoa-failed` dead-letter queue and replay jobs only after fixing their cause. Queued deletion uses tombstones so access is revoked immediately.
- Schedule database restore drills before widening the beta. R2 object durability alone is not a complete backup plan.
- Review reports in D1 through an authenticated administrator workflow. The app does not automatically act on reports.
- Monitor storage, AI minutes, queue failures, and Worker/D1 latency. Long recordings and forwarded copies increase retained storage.
- Beta uploads are capped at 8 GB and 24 hours; published history does not expire automatically.
- Authentication is email/password with invitation-gated registration. Email recovery and social sign-in are not configured.

## Optional AI integration check

`backend/tools/verify-ai.mjs` uses only the bundled synthetic speech fixture. With `TEST_API_URL` and `TEST_INVITE_CODE` set for a dedicated test deployment, run `node tools/verify-ai.mjs` from `backend/`. It creates a disposable account, uploads the sample, checks actual transcription/summary/caption output and consent revocation, then deletes the account. This invokes paid Workers AI models; run it deliberately, not as part of ordinary unit tests.
