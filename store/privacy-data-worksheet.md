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

The reviewed application has no advertising or analytics SDK. Its iOS privacy manifest declares no tracking and the required reasons for UserDefaults, file timestamp, and disk-space APIs. Confirm each store's definitions, SDK disclosures, optional/required categories, collection, sharing and retention in the console. Firebase Messaging is present on Android. Apple push is implemented but credentials are not configured. AI endpoints exist on the backend while the mobile transcript/caption controls are hidden; inspect operational availability and consent before answering AI-processing questions.

The app uses HTTPS, Keychain/Android secure session storage, and OS data protection. It does not implement end-to-end encryption; public copy must not claim it does. Deletion revokes account access and queues media cleanup. State the actual retention, backup and support procedures in the policy; do not promise instant removal from every backup.

Owner-provided facts still needed: legal operator and contact address, published support and privacy URLs, external deletion-request URL/process, data-retention periods, territories/target audience, moderation response process, and reviewer credentials. App-store metadata and privacy forms must be completed before public submission. The existing production-readiness findings in docs/PRODUCTION-READINESS.md also remain relevant.

References: [Apple app privacy](https://developer.apple.com/app-store/app-privacy-details/), [Google Play Data safety](https://support.google.com/googleplay/android-developer/answer/10787469), [Google account deletion](https://support.google.com/googleplay/android-developer/answer/13327111).
