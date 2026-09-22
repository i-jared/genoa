# Conversations

Conversations lets a person start a named private group, invite someone with a `genoa://join/<token>` link, and lets the invitee join. Outsiders cannot read the group.

## Sub-features

- `conversation-create` creates a titled conversation owned by the creator.
- `conversation-list` shows that conversation to its members.
- `conversation-invite` issues a join URL.
- `conversation-join` adds the invitee.
- `conversation-hidden` keeps a stranger at 404.

## How to get to it (user POV)

- On Home, choose `New conversation`, choose people if any are listed, choose `Continue`, enter `Conversation name`, choose `Create`.
- In the conversation, open `Conversation options` and share the group invitation.
- On Home, choose `Join with invitation` and paste the link or code.

## Driving it with control-genoa

Default. `node .cursor/skills/verify-genoa/scripts/control-genoa.mjs prove conversations`.

Preconditions:

- Isolated Worker is healthy at `http://127.0.0.1:18787`.
- Accounts `alice`, `bob`, and `eve` exist (`signup --as` each).
- No conversation is titled `Weekend plans` for these people.

- **Create.** Alice names a group. Run `node .cursor/skills/verify-genoa/scripts/control-genoa.mjs api POST /api/conversations --as alice --body '{"id":"<uuid>","title":"Weekend plans"}'`. Status `201` and `body.id` equals the requested id.
- **List as owner.** Alice opens Home. Run `node .cursor/skills/verify-genoa/scripts/control-genoa.mjs api GET /api/conversations --as alice`. The array includes an object whose `title` is `Weekend plans`.
- **Invite.** Alice creates a link. Run `node .cursor/skills/verify-genoa/scripts/control-genoa.mjs api POST /api/conversations/<cid>/invite --as alice --body '{}'`. Status `200`, `body.url` is `genoa://join/<token>`, and `body.token` matches the path.
- **Stranger.** Eve opens the group. Run `node .cursor/skills/verify-genoa/scripts/control-genoa.mjs api GET /api/conversations/<cid> --as eve`. Status `404`.
- **Join.** Bob pastes the invitation. Run `node .cursor/skills/verify-genoa/scripts/control-genoa.mjs api POST /api/join --as bob --body '{"token":"<token>"}'`. Status `200`.
- **List as member.** Bob opens Home. Run `node .cursor/skills/verify-genoa/scripts/control-genoa.mjs api GET /api/conversations --as bob`. `Weekend plans` is present.
- **Proof.** Save the create, invite, eve 404, join, and bob list JSON under `artifacts/verify-genoa/<runId>/`. The invitee list contains the title; the stranger response does not.

## Gotchas

- Only the owner can invite. A member other than the owner gets 403 on `/invite`.
- Join accepts the raw token or the tail of `genoa://join/<token>` in the Android client; the API body is `{ "token": "<token>" }` only.
- Creating with an id another user already owns returns 409. Generate a fresh UUID per run.
- Android Home labels are `New conversation` and `Join with invitation`. Without an emulator, those UI entry points stay unverified.
