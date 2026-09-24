# Releases and store assets

Build outputs always go under `dist/`, which is ignored by Git. Keep one current build per platform and variant. Store published historical binaries with release attachments outside the source tree, together with their matching symbols and checksums.

## Output locations

| File | Purpose |
|---|---|
| `dist/android/Genoa.apk` | Signed Android installation |
| `dist/android/Genoa.aab` | Signed Google Play bundle |
| `dist/android/mapping.txt` | Matching R8 crash mapping |
| `dist/android/Genoa-debug.apk` | Development build |
| `dist/android/Genoa-unsigned.apk`, `Genoa-unsigned.aab` | Explicitly unsigned build |
| `dist/android/mapping-unsigned.txt` | Matching unsigned-build mapping |
| `dist/android/build*.json` | Version, application ID, variant, and filenames |
| `dist/ios/Genoa.ipa` | Signed App Store Connect export |
| `dist/ios/Genoa.xcarchive` | Signed archive with matching symbols |
| `dist/ios/Genoa-dSYMs.zip` | iOS crash symbols |
| `dist/ios/Genoa-unsigned.xcarchive` | Explicitly unsigned device archive |
| `dist/store/` | Store artwork, screenshots, and listing copy |

Signed Android and iOS output directories contain `SHA256SUMS.txt`. Run `shasum -a 256 -c SHA256SUMS.txt` from the corresponding directory to verify local integrity. Checksums do not establish store acceptance or device reliability. Current versions and last verified delivery status are in [FEATURES.md](../docs/FEATURES.md).

## Android

Set `JAVA_HOME` to a compatible JDK (Android Studio's bundled JBR works) and `ANDROID_HOME` to your SDK. From the repo root:

```sh
./scripts/release/android.sh --debug
./scripts/release/android.sh --unsigned
```

For a signed release, set all four environment variables from secure storage: `GENOA_KEYSTORE` (absolute path), `GENOA_KEYSTORE_PASSWORD`, `GENOA_KEY_ALIAS`, and `GENOA_KEY_PASSWORD`. Then run:

```sh
./scripts/release/android.sh
```

The script builds, runs unit tests and lint, then copies outputs to `dist/android/`. Failed checks do not publish a new copy. Debug and unsigned variants cannot overwrite the signed files. Verify release signatures with Android SDK `apksigner` and `jarsigner`; retain the mapping that was built with each AAB.

The maintainer's existing upload key and credential file are in ignored, owner-only `.local/signing/android/`. They are not included in clones or packages. Back them up securely and reuse the same key for updates. The credential file contains environment values; load it directly into the build process without printing it. A distributed fork needs its own signing identity and Firebase configuration. Do not regenerate the existing key during cleanup.

The store application ID is `app.genoa`. The earlier `app.gather` beta is a separate installation; local drafts do not migrate automatically. An upload-key-signed APK may not update a Play-installed app if Play uses a different app-signing key.

## iOS

With an authorized Xcode account and `GENOA_APPLE_TEAM` set to your developer team:

```sh
./scripts/release/ios.sh --cloud-sign
```

This builds and exports with Xcode cloud-managed distribution signing. Omitting `--cloud-sign` uses local signing and may require an unlocked login Keychain. `--unsigned` builds an archive without Apple credentials; it cannot be uploaded. The scripts export locally and do not submit to a store.

Signed export checks verify `app.genoa`, the selected team, production push entitlement, distribution profile, signature, and matching dSYM UUIDs. Backend `APNS_CREDENTIALS.bundle_id` must match the app. See [iOS setup](../ios/README.md).

## Store content

- `en-US/`: listing copy, release notes, and reviewer instructions.
- `screenshots/android/`, `screenshots/iphone/`, `screenshots/ipad/`: four reviewed native screenshots per platform, captured September 21, 2026. These predate the latest onboarding update; review and recapture affected screens before submission.
- `demo/`: generated photo and provenance used by synthetic capture accounts.
- `privacy-data-worksheet.md`: implementation-based starting point for store declarations.
- `assets/brand/genoa/` at the repo root: canonical brand artwork and font license.

Install Pillow, then assemble store assets:

```sh
python3 scripts/release/package-assets.py
```

The command validates screenshot dimensions/opacity and listing lengths, then replaces `dist/store/` with a fresh package and checksums. It never includes app binaries, private keys, or local backups. Brand export verification is `python3 assets/brand/genoa/source/verify.py` (requires its Python requirements and `rsvg-convert`). Rebuild iOS appearance icons with `python3 scripts/release/ios-icons.py` and recompile after changing icons.

For new screenshots, start `./ios/scripts/test-server.sh` and run `python3 scripts/release/seed-screenshots.py`. Use isolated simulators/emulators. iOS `StoreScreenshotTests` opt in with `TEST_RUNNER_GENOA_STORE_SCREENSHOTS=1`; export attachments using `xcresulttool export attachments`. Android `StoreScreenshotTest` opts in with `storeScreenshots=1` and needs `adb -s emulator-5554 reverse tcp:18888 tcp:18888`. Pull images from the app's external `store-screenshots` directory before uninstalling. Review captures and replace the corresponding named PNGs in `store/screenshots/`; never edit screenshots to conceal errors.

Support, privacy and account-deletion URLs are the Worker's `/support`, `/privacy` and `/delete-account` pages (draft text; needs legal review and `SUPPORT_EMAIL`). Complete reviewer contact/account, content/age declarations, crash-data declarations (Crashlytics on Android, MetricKit uploads on iOS), push configuration, and [device/reliability gates](../docs/PRODUCTION-READINESS.md) before public release.
