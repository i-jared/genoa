# Genoa verification map

This directory names user-facing entry points. The executable recipe is `REGISTRY` in `../scripts/recipes.mjs`. Drive with `prove`, not a hand-built `api` sequence.

```sh
SCRIPT=.cursor/skills/verify-genoa/scripts/control-genoa.mjs
node "$SCRIPT" prove send-text
```

`prove` launches an isolated Worker on loopback 18787, walks the registry, and writes evidence under `artifacts/verify-genoa/<runId>/<feature>/001/`. A skip exits 2 with `verified: false`. Do not treat a skip as HTTP proof.

## Baseline preconditions

- Never drive the user's `wrangler dev` on port 8787, production `https://genoa-api.hp-accounts.workers.dev`, or a physical Android device.
- Isolated persist stays under `os.tmpdir()/genoa-verify` (or `GENOA_VERIFY_TMP`).
- Android recipes require `ANDROID_SERIAL=emulator-*` and a passing `android-doctor`. If that gate fails, `prove search`, `prove record`, and `prove send-text-android` skip. Do not substitute an API call as proof of the UI path.
- Camera, live sharing, and playback-speed proofs that need Compose belong on the dedicated emulator (or `connectedDebugAndroidTest`). Local HTTP cannot capture a recording.

## Driving conventions

- Prefer `node "$SCRIPT" prove <id>`. Use `signup`, `api`, `put-bytes`, `range-get`, and `android-*` only for paths the registry does not cover.
- Prefer HTTP paths from `Repository.kt` and Compose content descriptions / visible labels over coordinates.
- Treat quoted names in these maps as the user-visible strings the registry already asserts.
- Cleanup of isolated persist happens inside `prove` and `cleanup`. Do not delete proof artifacts.

## Proof and skip reporting

- Capture the user action and the resulting state, not only the final screen or JSON.
- API mutation proof includes a read-only second view, usually as a second person.
- Android proof includes an accessibility dump and a screenshot with Genoa or the conversation title visible.
- Record the feature ID and entry point used with every artifact.
- Report an unreachable path with the attempted command and the unmet precondition.
- Do not report a skipped entry point as verified through a different path.

## Feature entry contract

Each feature file starts with an H1 title and one paragraph describing the user-visible behavior. It then uses exactly four H2 sections in this order.

1. `Sub-features` lists short IDs with one line for each behavior.
2. `How to get to it (user POV)` lists every user entry point.
3. `Driving it with control-genoa` starts with the `prove <id>` command, then `Preconditions:`, then labeled bullets for the user-visible checks.
4. `Gotchas` lists traps that can waste or invalidate a verification run.

Keep implementation details out of the map. Name only user paths, stable handles, required state, commands, and observable proof.

## Features

HTTP prove ids are `account`, `conversations`, `send-text`, `notes`, `reactions`, `bookmarks`, `progress`, `rename`, `leave`, `mute-favorite`, `urgent`, `reply-to`, `pagination`, `member-remove`, `upload-resume`, `live`, `account-export-delete`, and `blocks`.

Android prove ids, emulator only, are `search`, `record`, and `send-text-android`.

Out of harness ids always skip. They are `verify-ai`, `fcm`, `ffprobe`, `physical-device`, and `genoa-flow-test`.

Maps for the core user paths:

- [Create an account](./account.md) covers invitation-gated registration, sign-in, and session rejection. Prove `account`.
- [Conversations](./conversations.md) covers creating a named conversation, inviting, and joining. Prove `conversations`.
- [Send a text](./send-text.md) covers writing a note, seeing it as the recipient, and idempotent retries. Prove `send-text`. Android compose is `send-text-android`.
- [Search conversations](./search.md) covers Home search match, empty, and clear states on Android. Prove `search`.
- [Record a reply](./record.md) covers private voice/video capture, explicit send, and keep-draft on the emulator. Prove `record`.
