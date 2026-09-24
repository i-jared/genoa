# Genoa admin

Internal moderation and operations console for Genoa. It is a Next.js (App Router) app intended for Vercel. It has no database: it reads and changes data only through the Worker's `/admin` API. The Worker decides who is an admin and records every mutation in its audit log.

| Page | Purpose |
|---|---|
| `/` | Health overview: open reports (oldest highlighted after 24h), failed jobs, outbox backlog, storage, users, suspensions, 24h activity |
| `/reports` | Open and resolved report queue: dismiss, remove the message, or remove it and suspend the author |
| `/users`, `/users/[id]` | Search people, view storage, messages, and reports; suspend, unsuspend, remove a message |
| `/jobs` | Failed background jobs with payloads: retry or discard |
| `/diagnostics`, `/diagnostics/[id]` | Crash/hang reports from the apps |
| `/audit` | Admin actions recorded by the Worker |

## Setup

Requires Node 22.18 or newer.

```sh
cd admin
npm install
cp .env.example .env.local
# Set SESSION_SECRET, e.g. openssl rand -base64 48
npm run dev
```

### Environment variables

| Variable | Meaning |
|---|---|
| `GENOA_API_URL` | Worker base URL, e.g. `https://genoa-api.hp-accounts.workers.dev`. Must be HTTPS except for localhost. |
| `ADMIN_URL` | This app's public origin, e.g. `https://genoa-admin.vercel.app`. Sent as `Origin` when signing in; the Worker must trust the same value as its `ADMIN_URL`. |
| `SESSION_SECRET` | At least 32 characters. Derives the key that encrypts the session cookie. Changing it signs every admin out. |

The server refuses to start, with a list of problems, when any variable is missing or invalid. `npm run build` does not need them.

### Local development against a local Worker

1. In `backend/.dev.vars` set `ADMIN_URL=http://localhost:3000`, then run the Worker (`npm run dev`, port 8787).
2. In `admin/.env.local` set `GENOA_API_URL=http://localhost:8787` and `ADMIN_URL=http://localhost:3000`.
3. `npm run dev` here and open http://localhost:3000.

Over plain HTTP the cookie is named `genoa_admin` and is not marked `Secure`. When `ADMIN_URL` is HTTPS it is `__Host-genoa_admin` and `Secure`.

### Making someone an admin

The account must already exist. From `backend/`:

```sh
npx wrangler d1 execute genoa --remote --config wrangler.production.jsonc --command "INSERT INTO admins(user_id,created_at) SELECT id, unixepoch()*1000 FROM user WHERE email='you@example.com'"
```

Use `--local` without `--config` for a local database. To remove access, delete the row from `admins`. Existing admin sessions stop working at their next request.

## Deploying to Vercel

1. Import the repository in Vercel and set **Root Directory** to `admin`. The framework preset is Next.js; the default build and install commands are correct, so no `vercel.json` is needed.
2. Add `GENOA_API_URL`, `ADMIN_URL`, and `SESSION_SECRET` for Production (and Preview if used; preview URLs change, so their `ADMIN_URL` will not match the Worker unless you add a stable preview domain).
3. Set the Worker's `ADMIN_URL` to the same origin and deploy the Worker.
4. Deploy. Sign in with an admin account and check the overview loads.

Consider Vercel Deployment Protection or a firewall allowlist as an extra layer; the app itself already requires an admin session for every page.

## Security model

- **Sign-in.** A server action posts the email and password to `POST /auth/sign-in/email` with `Origin: ADMIN_URL`, reads the bearer token from `set-auth-token`, and checks `GET /admin/me`. If that check fails, the token is revoked and the person sees a generic "not authorised" message.
- **Session cookie.** The token is encrypted with AES-256-GCM (key derived from `SESSION_SECRET` via HKDF) together with a 12-hour expiry. The cookie is `httpOnly`, `SameSite=Strict`, `Secure` on HTTPS, and has a 12-hour max age. A tampered, foreign, or expired cookie is treated as signed out (`lib/session-crypto.ts`, tested in `test/`).
- **Every request is checked.** `proxy.ts` redirects to `/sign-in` when the cookie cannot be decrypted. Each page also calls `/admin/me`, and a 401/403/404 clears the cookie and returns to sign-in, so revoking admin access or suspending an account takes effect at once.
- **The token never reaches the browser.** All Worker calls happen on the server. Media is streamed through `/media/[assetId]`, which adds the bearer token, passes `Range` through, sets `Cache-Control: private, no-store`, forces non-media content types to `application/octet-stream`, and sandboxes the response. Nothing sensitive appears in URLs.
- **Mutations** are server actions, which Next.js protects by rejecting cross-origin requests. Destructive actions need an explicit confirmation step, and suspensions need a reason. Inputs and ids are validated before they are forwarded, and path segments are URL-encoded.
- **Headers.** A per-request nonce-based CSP with `frame-ancestors 'none'`, `Referrer-Policy: no-referrer`, `X-Robots-Tag: noindex`, `X-Frame-Options: DENY`, `nosniff`, HSTS, and `robots.txt` disallowing everything.
- **Logging.** The app logs nothing about tokens, media URLs, report text, or payloads.

## Checks

```sh
npm run typecheck   # next typegen && tsc --noEmit
npm run lint
npm test            # cookie encryption round trip and tamper rejection
npm run build
```
