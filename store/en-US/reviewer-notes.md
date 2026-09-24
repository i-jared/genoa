# App review and beta testing notes

Genoa is a private messaging application; anyone can create an account, and conversations are joined by invitation. Review requires a working dedicated account and another synthetic conversation participant. The account must have a seeded conversation with sample photo, voice, and video messages. Supply the reviewer username/password in the private store-console fields; never put them in public metadata or the distribution archive. Local screenshot accounts are not production review accounts.

Suggested review path: sign in → open the seeded conversation → watch a message → record a reply → stop and send → verify the message appears. Also test voice/photo import, reactions, bookmarks, private scratchpad, account export, and account deletion. Use two devices for live sharing; beginning a live recording makes it available to invited members while capture continues.

There are no purchases in the current beta. Microphone/camera permissions support messages. Background audio supports listening with the screen locked; background transfer/processing supports queued uploads. Notifications are optional. iOS APNs credentials must be configured and device delivery verified before describing iOS push as available to reviewers.

User-generated content safeguards (Apple guideline 1.2): signup requires agreeing to the Terms (`/terms`, zero tolerance for objectionable content); any message can be reported and any person blocked from the message menu; reports are reviewed in the admin console within 24 hours, where content is removed and abusive accounts are suspended.

Before submission, the owner must supply the real reviewer account and review contact, and confirm the support (`/support`), privacy-policy (`/privacy`) and Google Play account-deletion (`/delete-account`) URLs on the production API host. Confirm the target audience, current age-rating questionnaires, content moderation process, and encryption answers against actual operation. These values cannot be inferred from artwork or a successful build.
