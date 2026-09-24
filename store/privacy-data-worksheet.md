# Store privacy and operations worksheet

Implementation-based starting point for the owner to verify in App Store Connect and Play Console. This is not a published privacy policy or a completed store declaration.

| Data | Current use |
|---|---|
| Name, email, account ID | Account authentication, participant identity and access control; linked to the account |
| Videos, voice recordings, photos, text, reactions | Deliver user-selected content to authorized conversation members; stored in Cloudflare D1/R2 |
| Conversation membership, bookmarks, scratchpad, reminders, watch progress | App functionality and synchronization; private notes/bookmarks are account-scoped |
| Push device token/platform | Optional notification delivery through Firebase/Apple; associated with an account/device |
| Reports and blocked-user IDs | Safety and access control |
| Network/service logs | Hosting and operational diagnostics; review actual Cloudflare retention/configuration |
| Crash and performance diagnostics | Android: Firebase Crashlytics (release builds; app version/build type only, no user ID). iOS: MetricKit crash/hang/CPU/disk reports uploaded to Genoa's API with the session, so linked to the account; kept 30 days. Declare as crash data / performance data, used for app functionality, not tracking |
| Account emails | Password reset and email confirmation sent through the configured email provider |
| Moderation records | Report outcomes, suspensions and an admin audit log, retained for safety |

The reviewed application has no advertising or analytics SDK; Firebase Crashlytics (Android) is the only third-party diagnostics SDK. Its iOS privacy manifest declares no tracking and the required reasons for UserDefaults, file timestamp, and disk-space APIs. Confirm each store's definitions, SDK disclosures, optional/required categories, collection, sharing and retention in the console. Firebase Messaging is present on Android. Apple push is implemented but credentials are not configured. AI endpoints exist on the backend while the mobile transcript/caption controls are hidden; inspect operational availability and consent before answering AI-processing questions.

The app uses HTTPS, Keychain/Android secure session storage, and OS data protection. It does not implement end-to-end encryption; public copy must not claim it does. Deletion revokes account access and queues media cleanup. State the actual retention, backup and support procedures in the policy; do not promise instant removal from every backup.

Draft privacy, terms, support and deletion pages are served by the Worker at `/privacy`, `/terms`, `/support` and `/delete-account` (see [operations](../docs/OPERATIONS.md)); they need legal review and a configured `SUPPORT_EMAIL`. Owner-provided facts still needed: legal contact address, territories/target audience, and reviewer credentials. App-store metadata and privacy forms must be completed before public submission.

References: [Apple app privacy](https://developer.apple.com/app-store/app-privacy-details/), [Google Play Data safety](https://support.google.com/googleplay/android-developer/answer/10787469), [Google account deletion](https://support.google.com/googleplay/android-developer/answer/13327111).
