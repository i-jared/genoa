# Genoa

Private video messaging for Android and iOS, built with Kotlin/Compose, SwiftUI/AVFoundation, and a shared Cloudflare backend.

Record a message, send it to a person or group, and watch or reply when convenient. Genoa supports voice, photos, live sharing, reactions, and durable uploads. Media access is authorized by the server; messages are not end-to-end encrypted. This is beta software; see [feature status and verification](docs/FEATURES.md) and [known reliability gaps](docs/PRODUCTION-READINESS.md).

## Repository

| Directory | Contents |
|---|---|
| `android/` | Native Android app and tests |
| `ios/` | Native iOS app, Xcode project, and tests |
| `backend/` | Workers API, D1 migrations, and integration tests |
| `admin/` | Moderation and operations console (Next.js on Vercel) |
| `docs/` | Architecture, design, feature status, and configuration |
| `assets/` | Canonical brand assets, sources, and font license |
| `scripts/release/` | Repeatable build and store-asset commands |
| `scripts/ops/` | Backup and restore drill |
| `store/` | Listing copy, reviewed screenshots, and demo media |
| `dist/` | Latest local build outputs; generated and ignored by Git |

## Build Android

Requires Node 22+, JDK 17 or a newer Gradle-compatible JDK, Android SDK 36, and an Android 10+ device or emulator.

Start the local backend:

```sh
cd backend
npm ci
cp .dev.vars.example .dev.vars
# Set a long random BETTER_AUTH_SECRET in .dev.vars.
npm run migrate:local
npm run dev
```

Open `android/` in Android Studio. Set **Server connection** in the app to `http://10.0.2.2:8787` for the emulator. Local HTTP is restricted to emulator/loopback development hosts. Builds otherwise default to the hosted Genoa API; use a local server for development and tests.

From the repository root:

```sh
./scripts/release/android.sh --debug
```

The APK is always **`dist/android/Genoa-debug.apk`**. Signed releases use **`dist/android/Genoa.apk`** and **`dist/android/Genoa.aab`**. Build outputs are local files, not included in a fresh clone. See [release instructions](store/README.md) for signing, checksums, iOS exports, and store assets.

## Build iOS

Open `ios/Genoa.xcodeproj` in Xcode 26 or later and run the **Genoa** scheme on an iOS 17+ simulator. Device builds require your Apple development team. See [iOS setup and testing](ios/README.md).

## Verify changes

Backend tests create disposable local state. Start the local Worker (`npm run dev`) first; the moderation/recovery suite reads the local D1 and R2 files to seed an admin and confirm deleted media is gone:

```sh
cd backend
npm run check
npm test
```

Admin console type checks, lint, tests and production build:

```sh
cd admin
npm ci && npm run typecheck && npm run lint && npm test && npm run build
```

`.github/workflows/ci.yml` runs the backend, admin, Android and iOS checks on every push and pull request once the repository has a GitHub remote.

Android build, unit tests, and static checks:

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

For the onboarding/record/send flow, start `./ios/scripts/test-server.sh` and use a dedicated emulator with synthetic cameras:

```sh
cd android
adb -s emulator-5554 reverse tcp:18888 tcp:18888
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.gather.UsabilityFlowTest
```

Other instrumentation classes use local servers on ports 8787 or 18891; configure their fixtures and reverse ports before running the full suite. Tests must not run against a personal phone or production database. See [iOS verification](ios/README.md#local-verification) for simulator commands.

## Contributing

Read [AGENTS.md](AGENTS.md), [the product plan](PLAN.md), and [the architecture](docs/ARCHITECTURE.md). Keep changes focused, preserve draft and permission behavior, and run the checks relevant to your changes. Android UI follows [the design specification](docs/DESIGN.md); the [visual reference](docs/design/guide/index.html) and custom icons are included in this repo.

Record what was tested and any remaining device or deployment requirements in [docs/FEATURES.md](docs/FEATURES.md). Keep generated builds, logs, private recordings, credentials, and signing keys out of commits. `dist/` is disposable build output; `.local/` is ignored private storage and must never be packaged or deleted by a build cleanup.

## Deployment

The checked-in `backend/wrangler.production.jsonc` identifies the hosted Genoa deployment. For your own deployment, provision separate D1, private R2, Queues, and Durable Object resources and update the configuration before using `npm run migrate:production` or `npm run deploy`. Use your own Firebase client configuration and signing identities for a distributed fork. Android and iOS currently use `app.genoa`.

Set Worker secrets through Wrangler, never in tracked files. Authentication needs `BETTER_AUTH_SECRET`; push uses `FCM_SERVICE_ACCOUNT` and optional `APNS_CREDENTIALS`. See [Google/Apple sign-in setup](docs/SOCIAL-SIGN-IN.md) and [iOS push setup](ios/README.md). Email signup is open; social buttons stay hidden until providers are configured. Password reset and alert emails need `RESEND_API_KEY` and `EMAIL_FROM`; see [operations](docs/OPERATIONS.md).

Monitor queue failures, storage, AI usage, and database latency. Published messages do not expire automatically; per-message limits are 8 GB and 24 hours. Backup/restore drills, recovery, moderation, and physical-device checks remain [release gates](docs/PRODUCTION-READINESS.md).

## License

[MIT](LICENSE). The bundled Newsreader font retains its [SIL Open Font License](assets/brand/genoa/licenses/Newsreader-OFL.txt).
