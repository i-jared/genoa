# Send a text

Send a text lets a person write a note in a conversation, send it explicitly, and lets a member watch it in the thread. A retry with the same message id does not create a second copy.

## Sub-features

- `text-compose` posts a ready text message.
- `text-recipient` shows that text to another member.
- `text-retry` reuses the client id without duplicating.
- `text-android` writes from `Reply options` → `Write a text message` on the emulator.

## How to get to it (user POV)

- Open a conversation, choose `Reply options`, choose `Write a text message`, type in `Send a note…`, choose `Send note`.
- The recipient opens the same conversation and reads the note in the thread.

## Driving it with control-genoa

Default. `node .cursor/skills/verify-genoa/scripts/control-genoa.mjs prove send-text`. Android compose is `prove send-text-android` and skips without an emulator.

Preconditions:

- Isolated Worker is healthy at `http://127.0.0.1:18787`.
- `alice` and `bob` exist and both belong to conversation `<cid>` titled `Weekend plans` (see [Conversations](./conversations.md)).
- No message text equals `See you Saturday`.

- **Send.** Alice writes a note. Run `node .cursor/skills/verify-genoa/scripts/control-genoa.mjs api POST /api/conversations/<cid>/messages --as alice --body '{"id":"<uuid>","text":"See you Saturday"}'`. Status `201` and `body.id` equals the requested id.
- **Retry.** Alice's phone retries the same send. Run the same `POST` with the same JSON. Status `201`.
- **Recipient watch.** Bob opens the thread. Run `node .cursor/skills/verify-genoa/scripts/control-genoa.mjs api GET /api/conversations/<cid>/messages --as bob`. Status `200`. Exactly one object has `text` `See you Saturday` and `kind` `text` and `state` `ready`.
- **Owner list.** Alice confirms Home preview. Run `node .cursor/skills/verify-genoa/scripts/control-genoa.mjs api GET /api/conversations --as alice`. The `Weekend plans` object preview contains `See you Saturday`.
- **Android compose (emulator only).** Open the conversation, choose `Reply options`, `Write a text message`, type `See you Saturday`, choose `Send note`. Run `ANDROID_SERIAL=emulator-5554 node .cursor/skills/verify-genoa/scripts/control-genoa.mjs android-tap-desc "Reply options"`, then `android-tap-text "Write a text message"`. If `android-doctor` fails, record `text-android` as unreachable and do not mark it verified via HTTP.
- **Proof.** Save the POST, retried POST, bob GET, and alice conversation list under `artifacts/verify-genoa/<runId>/send-text/`. Bob's history is the user-visible watch proof.

## Gotchas

- Text is trimmed. Assert the stored `text`, not a padded draft.
- A 201 on send is not enough. The recipient GET must show a single ready message.
- Urgent notes set `"urgent": true` on the same POST; mute settings can suppress push but the message still appears in history.
- Android still persists a local draft before upload. HTTP send skips that phone-side draft file; emulator or `connectedDebugAndroidTest` is required to prove draft survival across app restart.
