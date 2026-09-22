# Genoa service migration

Completed September 16, 2026 with explicit approval for a brief maintenance window and a restricted local backup containing production account and authentication records.

## Active services

| Service | Current name | Migration |
|---|---|---|
| Cloudflare API Worker | `genoa-api` | Renamed in place, preserving secrets and Durable Object binding |
| Production API | `https://genoa-api.hp-accounts.workers.dev` | Healthy after cutover |
| D1 | `genoa` | Verified copy, ID `3f63164d-6333-4440-8e76-7c6aec5870bc` |
| Private R2 | `genoa-media` | New private bucket; original bucket was empty |
| Jobs queue | `genoa-jobs` | Renamed in place, preserving queue identity and settings |
| Failed jobs queue | `genoa-failed` | Renamed in place, preserving queue identity and settings |
| Firebase / Google Cloud project display | Genoa | Display name updated |
| Firebase Android app display | Genoa Android | Display name updated |
| Push service-account display | Genoa notification delivery | Display name updated |

Production deployment version: `bacd8578-d8e1-4439-bf69-26d361bfd6ca`.

## Data protection and verification

During maintenance, API mutations returned 503 with Retry-After; scheduled work paused and queue batches retried. Exported the original database to the owner-readable, ignored `artifacts/service-migration/gather-before-rename.sql` and imported it into Genoa. Before cutover, compared every row in all 26 application/migration tables (75 rows), table schemas, indexes, triggers, views, and sequence counters. All matched. The content-free row-count/hash verification is in `artifacts/service-migration/verification.json`.

The source and destination R2 buckets were empty at cutover. No media-copy endpoint was deployed. Managed public bucket access is disabled. The three original Worker secrets remain bound; no secret rotation was required.

Both API addresses returned HTTP 200 with `{"ok":true}` after restoration, and unauthenticated conversation requests returned 401. The production integration test that creates/deletes test data was rejected by automatic approval review; it was not executed. Backend TypeScript checks and the isolated local API integration suite passed, including private media, upload retries, and membership revocation. Push delivery, AI processing, and device behavior were not rerun as part of this migration.

## Retained compatibility identities and rollback resources

These are deliberate remaining legacy identifiers, not user-facing app branding:

- `gather-api` is now a forward-only compatibility Worker with a single service binding to Genoa. It has no database, media, or secret bindings. Keep it while earlier beta builds use the previous endpoint. Source/config: `backend/src/compatibility.ts` and `backend/wrangler.compatibility.jsonc`.
- Android package/application ID `app.gather`, local `gather.db`, and keystore alias `gather_session` remain stable to preserve upgrades, sessions, drafts, and background work. Version 0.1.2 maps the old default API address to the new one without clearing the session.
- Firebase project ID `gather-video-20260916`, its Android registration, and push service-account email retain their existing identities. Their display names use Genoa. Changing these identities would require a separate project/app migration and push re-registration.
- The existing Durable Object namespace ID remains `2d62eed623c34483875027bfd3bffe7e`; Cloudflare retains its legacy `gather-api_ConversationRoom` internal name. Keeping the binding preserves conversation live-state identity.
- The original D1 `gather` (ID `4e43d01f-2c44-4c0f-8ff1-d338f9297fff`) and empty private R2 `gather-media` are retained as rollback resources. The active API no longer binds either one. They have not been deleted. The old D1 is a frozen pre-cutover snapshot, not a current replica.

Do not revert the active API to the old database after accepting new writes without reconciling the new data. Keep the SQL backup private; it contains sensitive authentication/account data. Resource retirement and backup retention should be deliberate follow-up operations.

## Website and design samples

Product website address: https://talkgenoa.com. DNS/website hosting and Android HTTPS App Links have not been configured here.

The private [Genoa visual design guideline](https://genoa-design-guide.i-jared.chatgpt.site) is separately published from `design-guide/`. It contains static visual examples, not functional recording or messaging. The detailed specification is [DESIGN.md](DESIGN.md).
