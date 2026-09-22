# Genoa release handoff

Selected distribution: **Google Play + App Store/TestFlight**. The primary deliverables are a signed Play AAB and an App Store Connect IPA. The APK is a supplemental artifact.

Both platforms use **`app.genoa`**. The assembled handoff is in `artifacts/deployment/package/`; the downloadable archive is `artifacts/deployment/genoa-release-handoff.zip`. Icons, listing artwork, and 12 native screenshots are supplied. **The Android AAB/APK and App Store Connect IPA are signed and verified.** See `verification.json` for signing and validation evidence. No store upload or submission has been performed.

## Included files

| Destination | Handoff file/folder |
|---|---|
| Google Play upload | `android/genoa-0.2.8-build12.aab` |
| Supplemental Android package | `android/genoa-0.2.8-build12.apk` |
| Android crash deobfuscation | `android/mapping-0.2.8-build12.txt` |
| App Store / TestFlight upload | `ios/Genoa-0.1.0-build1.ipa` |
| iOS matching crash symbols | `ios/Genoa-0.1.0-build1-dSYMs.zip` |
| Play app icon / feature graphic | `store/google-play/icon-512.png`, `feature-1024x500.png` |
| Play phone screenshots | `store/google-play/phone/` — four 1080×1920 native captures |
| App Store icon | `store/app-store/icon-1024.png` (also compiled into the app) |
| iPhone 6.9-inch screenshots | `store/app-store/iphone/` — four 1320×2868 native captures |
| iPad 13-inch screenshots | `store/app-store/ipad/` — four 2064×2752 native captures |
| Platform icon sources | `platform-icons/` — Android adaptive/round/monochrome/notification, iOS default/dark/tinted and interface catalog |
| Logo, web and social artwork | `brand/` — outlined SVGs, transparent PNGs, favicons, social images and font license |
| Listing copy and review notes | `store/en-US/` |
| Privacy form starting point | `store/privacy-data-worksheet.md` |
| File integrity and inspection | `asset-manifest.json`, `SHA256SUMS.txt`, `verification.json`, `preview.html` |

Screenshots are real native app captures of fictional local accounts and an AI-generated demo photo. They are not generated UI or customer data. Default display mode is light. The capture tools only connect to the dedicated loopback Worker. PNGs preserve original pixels/dimensions; a redundant opaque alpha channel is removed for store upload. Optional videos and Android tablet promotional screenshots are not required for this phone listing and are not supplied.

## Signing and repeatable builds

Android uses `app.genoa`, version 0.2.8 (12), with a new Firebase Android registration in the existing `gather-video-20260916` project. A new RSA-4096 upload key was created with the owner's authorization for this first Play release. Its public certificate is `android/genoa-upload-certificate.pem`. Private signing material is stored separately at `artifacts/private-signing/android/` with owner-only permissions and excluded from Git and this handoff. Back up the keystore and credentials together in secure storage. Enroll in Play App Signing and retain this upload key for subsequent releases.

The earlier `app.gather` beta remains a separate installation. Local drafts and sessions do not migrate automatically, so handle unsent drafts there before removing it. Server accounts remain available in the new app. A supplemental APK signed with the upload key cannot update a Play-installed app when Play uses a different app-signing key.

Set `JAVA_HOME` to Android Studio's bundled JBR and `ANDROID_HOME` to the Android SDK. Load `GENOA_KEYSTORE`, `GENOA_KEY_ALIAS`, `GENOA_KEYSTORE_PASSWORD` and `GENOA_KEY_PASSWORD` from the private credentials file into the build process environment; never paste passwords into command history or Git. Run `scripts/release/android.sh`. It refuses incomplete credentials; `--unsigned` explicitly creates unsigned artifacts. Validate the APK with `apksigner verify --verbose --print-certs`; retain the AAB and matching mapping file together.

iOS uses `app.genoa`, version 0.1.0 (1), and the authorized **Whatado, LLC team `DCWP7J7692`**. Its bundle ID and push-enabled development and App Store profiles were registered successfully. Set `GENOA_APPLE_TEAM=DCWP7J7692` and run `scripts/release/ios.sh --cloud-sign` to archive and export for App Store Connect locally. The verified cloud-signing path compiles an archive, preserves production push entitlements in an ad-hoc signature, then exports an Apple Distribution-signed IPA through Xcode's automatic cloud signing. It requires an authorized Xcode account with cloud distribution-signing access. The archive is an export source, not the upload artifact; upload the signed IPA. Omitting `--cloud-sign` uses local development signing for the archive and may require an unlocked login Keychain. No store upload or submission is performed by this script. The exported app must have production APNs entitlement; backend `APNS_CREDENTIALS.bundle_id` must be `app.genoa`.

The final IPA passed strict code-signature verification, has `DCWP7J7692.app.genoa` as its application identifier, `aps-environment=production`, and `get-task-allow=false`. Its App Store profile expires March 2, 2027. The exported binary and supplied dSYMs have matching UUIDs. Cloud-managed distribution signing resolved the initial login Keychain blocker; no Keychain password or access-control changes were required. Private signing keys are excluded from the handoff; the IPA includes its normal embedded provisioning profile.

## Reproduce the asset checks

`python3 assets/brand/genoa/source/verify.py` validates the existing brand exports. `python3 scripts/release/ios-icons.py` rebuilds iOS appearance icons from the existing vector master using Pillow and `rsvg-convert`. `python3 scripts/release/package-assets.py` assembles the reviewed captures, validates their sizes/opacity and copy limits, and generates checksums. Rebuild the iOS archive after changing its icon catalog. Do not edit screenshots to hide errors; recapture the actual app.

For fresh screenshots, start `ios/scripts/test-server.sh`, run `scripts/release/seed-screenshots.py` once, then explicitly run `StoreScreenshotTests` with `TEST_RUNNER_GENOA_STORE_SCREENSHOTS=1` on isolated iPhone/iPad simulators. Android's `StoreScreenshotTest` requires the `storeScreenshots=1` instrumentation argument and an emulator reverse port for 18888. It is skipped in ordinary test runs. Android capture outputs go to the app's external `store-screenshots` directory; pull them before uninstalling. Export iOS attachments with `xcresulttool export attachments`. The packaging script expects the reviewed captures under `artifacts/deployment/logs/`.

## Store-account information still needed

Complete the reviewer account/contact, support URL/email, published privacy policy, external account-deletion request URL, age/target-audience/content declarations, and operational privacy answers before submission. See the supplied reviewer notes and privacy worksheet. Backend APNs credentials and physical-device camera/live/background/notification/accessibility verification remain open. The existing `docs/PRODUCTION-READINESS.md` findings are not resolved by release builds or artwork.

Asset specifications checked September 21, 2026: [Apple screenshots](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications), [Apple icon catalogs](https://developer.apple.com/documentation/xcode/configuring-your-app-icon), [Google Play preview assets](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en). The archive uses Xcode 26.0.1 / iOS 26 SDK, meeting the currently published [SDK minimum](https://developer.apple.com/news/upcoming-requirements/). Store validation still requires a signed upload.
