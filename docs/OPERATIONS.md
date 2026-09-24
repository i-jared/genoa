# Operations

How Genoa is run day to day: configuration, moderation, alerts, failed work, and backups. Commands run from `backend/` unless noted. Production commands use `--config wrangler.production.jsonc`.

## Configuration

Worker secrets (`npx wrangler secret put NAME --config wrangler.production.jsonc`):

| Secret | Needed for | Without it |
|---|---|---|
| `BETTER_AUTH_SECRET` | Sessions | Worker refuses requests |
| `RESEND_API_KEY`, `EMAIL_FROM` | Password reset, email confirmation, alert emails | Apps hide **Forgot password?**; no verification or alert emails are sent |
| `FCM_SERVICE_ACCOUNT` | Android notifications | No Android push |
| `APNS_CREDENTIALS` | iOS notifications | No iOS push (local reminder alerts still work) |
| `GOOGLE_*`, `APPLE_*` | Social sign-in | Buttons stay hidden; see [SOCIAL-SIGN-IN.md](SOCIAL-SIGN-IN.md) |

Worker vars (in `wrangler.production.jsonc` `vars`): `SUPPORT_EMAIL` (shown on the public policy/support pages), `ALERT_EMAIL` (receives operational alerts), `ADMIN_URL` (exact origin of the admin console, trusted for its sign-in), and optionally `ACCOUNT_STORAGE_QUOTA_BYTES` (default 25 GiB per account, counting published media and uploads in progress).

`EMAIL_FROM` must be a sender on a domain verified with the email provider (for example `Genoa <no-reply@talkgenoa.com>`). Development needs none of this: emails are written to a local `dev_emails` table.

## Public pages

The Worker serves `/privacy`, `/terms`, `/support`, `/delete-account`, `/reset-password`, and `/email-verified`. Use `<API>/privacy`, `<API>/support`, and `<API>/delete-account` for the store listings' privacy, support, and account-deletion URLs. The policy text is an implementation-based draft; have it reviewed before public launch, and keep its commitments (24-hour report review, 30-day backup overwrite, 30-day diagnostics retention) true.

## Admin console

The console is a separate Next.js app in `admin/`, deployed on Vercel; setup and deployment are in [admin/README.md](../admin/README.md). It holds no data and calls only the Worker's `/admin/*` API server-side. Access is granted by adding a row to `admins`:

```sh
npx wrangler d1 execute genoa --remote --config wrangler.production.jsonc \
  --command "INSERT INTO admins(user_id,created_at) SELECT id, unixepoch()*1000 FROM user WHERE email='person@example.com'"
```

Remove access with `DELETE FROM admins WHERE user_id=...`; the console signs them out on the next request. Non-admins receive 404 from every admin route. Every moderation action (report resolution, message removal, suspension, job redrive) is recorded in the audit log.

### Reports

Review the **Reports** queue at least daily; the Terms promise action within 24 hours, and an alert fires when a report waits longer. For each report choose **Dismiss**, **Remove message**, or **Remove + suspend** (requires a reason). Resolving a report resolves all other open reports on the same message.

Suspension deletes the person's sessions and push registrations, closes their live sockets, and blocks sign-in and every API call with `account_suspended` until **Unsuspend**. Their messages stay unless removed individually. Unsent drafts remain on their phone.

## Alerts

The five-minute cron checks for dead-lettered jobs, reports open for more than 24 hours, and outbox events undelivered for more than 15 minutes. Each check logs `OPERATIONAL_ALERT` with the alert name and, when `ALERT_EMAIL` and email are configured, sends at most one email per alert every six hours. The console's overview shows the same alerts.

Also configure in the Cloudflare dashboard (not codified here): Workers error-rate and CPU notifications for `genoa-api`, a usage-based billing notification, and R2 storage notifications for `genoa-media`.

## Failed jobs

Queue jobs (notifications, deletions, AI) retry five times, then move to the `genoa-failed` dead-letter queue, whose consumer records them in `failed_jobs`. Triage them in the console's **Failed jobs** page: **Retry** re-sends the job with its original ID (jobs are idempotent), **Discard** drops it. A failed `delete` job means media may still be stored; retry it rather than discarding. The dead-letter queue must exist before deploying its consumer: `npx wrangler queues create genoa-failed` if it doesn't.

## Storage reconciliation

The cron also walks R2 `media/` a page at a time and removes objects that are more than a day old and either belong to a deleted or unknown message or are referenced by no asset, live segment, audio chunk, or open upload. It logs `ORPHANED_MEDIA_REMOVED` with a count. Diagnostics payloads (`diagnostics/`) are removed after 30 days.

## Backups and restore

D1 Time Travel keeps 30 days of point-in-time history. `scripts/ops/restore-drill.sh --production` (from the repository root) records the current Time Travel bookmark, exports production D1 to `.local/backups/`, restores the export into a temporary D1 database, compares every table's row count, samples 25 published assets to confirm their R2 objects exist, and deletes the temporary database. Run it monthly and after schema migrations; record the date and result in [FEATURES.md](FEATURES.md). `--local` rehearses the same steps against the development database.

To restore production in place, pick a time before the incident and run `npx wrangler d1 time-travel restore genoa --timestamp=<ISO 8601> --config wrangler.production.jsonc`. Time Travel restores metadata only. R2 media has no point-in-time copy: deleted objects are gone, so a D1 restore can reference media that no longer exists (the drill's media check shows how much). If media recovery matters, add R2 bucket replication or a periodic copy to a second bucket.
