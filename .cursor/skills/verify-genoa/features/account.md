# Create an account

Create account lets a person join Genoa with email, password, name, and a beta invitation, then use that session. Sign-in reuses an existing account. The API rejects callers without a session.

## Sub-features

- `account-register` creates an invitation-gated account.
- `account-reject-invite` refuses registration without a valid invitation.
- `account-session` reads `/api/me` with the new session.
- `account-unauthenticated` keeps private lists closed.

## How to get to it (user POV)

- Open the app to `Welcome to Genoa`, choose `Have an invitation? Join Genoa`, fill `Your name`, `Email`, `Password`, `Beta invitation code`, and choose `Create account`.
- On the same screen, choose `Sign in` with email and password.
- Optional `Server connection` changes `Server address` (emulator local server is `http://10.0.2.2:8787`; verification Workers use `http://10.0.2.2:18787`).

## Driving it with control-genoa

Default. `node .cursor/skills/verify-genoa/scripts/control-genoa.mjs prove account`.

Preconditions:

- Isolated Worker is healthy at `http://127.0.0.1:18787`.
- `control-genoa doctor` reports that URL and persist dir under `/tmp/genoa-verify`.

- **Register.** Create a disposable person. Run `node .cursor/skills/verify-genoa/scripts/control-genoa.mjs signup --as alice`. Status `200` and evidence `accounts/alice.json` contain an id and `alice-<runId>@example.test`.
- **Read profile.** Fetch the signed-in person. Run `node .cursor/skills/verify-genoa/scripts/control-genoa.mjs api GET /api/me --as alice`. Status `200` and `body.name` is `alice`.
- **Closed list.** Call conversations without a session by using doctor, which already requires `GET /api/conversations` to return `401`. Re-run `node .cursor/skills/verify-genoa/scripts/control-genoa.mjs doctor` if this check is stale.
- **Bad invitation.** Register with a wrong code. Run `TEST_INVITE_CODE=wrong node .cursor/skills/verify-genoa/scripts/control-genoa.mjs signup --as interloper`. The command exits non-zero and does not write `accounts/interloper.json`.
- **Proof.** Keep `accounts/alice.json` and `api/alice-get_api_me.json`. They show the created name and id, not a token.

## Gotchas

- Password must be at least 12 characters or Better Auth rejects the request.
- `X-Invite-Code` is required only on `POST /auth/sign-up/email`. Sign-in does not use it.
- The helper stores session tokens in `/tmp/genoa-verify/current.json`. Do not copy that file into `artifacts/`.
- Android sign-up is a separate entry point. Without `android-doctor`, do not claim the Welcome screen was verified.
