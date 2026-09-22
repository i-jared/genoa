---
name: verify-genoa
description: Drive Genoa the way a user does — isolated local API plus optional dedicated Android emulator. Use to prove recording, sending, watching, invitations, and home/search/profile after a change.
---

# Verify Genoa

Genoa is a private video messenger. People use the Android app. The phone talks to a Hono Worker (`backend/`) over the same HTTP routes this harness drives. There is no web messenger. `design-guide/` is a static sample, not the product.

Read `features/README.md` before driving. A proof that uses one convenient entry point is incomplete when the map lists others.

Helper (executable): `.cursor/skills/verify-genoa/scripts/control-genoa.mjs`

```sh
SCRIPT=.cursor/skills/verify-genoa/scripts/control-genoa.mjs
```

## Launch

The user often already has `wrangler dev` on **127.0.0.1:8787** using `backend/.wrangler`. Never attach to that process. Never hit production `https://genoa-api.hp-accounts.workers.dev`. Never drive the owner's physical phone.

Start an isolated Worker on loopback port **18787** with its own persist directory:

```sh
node "$SCRIPT" launch
node "$SCRIPT" doctor
```

Ready when `GET /health` returns `{"ok":true}` and `GET /api/conversations` without a session returns **401**. `launch` writes `/tmp/genoa-verify/current.json` (override with `GENOA_VERIFY_STATE`) and evidence under `artifacts/verify-genoa/<runId>/`.

If 18787 is taken, set `GENOA_VERIFY_PORT` and `GENOA_VERIFY_INSPECTOR_PORT` to free loopback ports and launch again. Two verification instances can run side by side only with distinct ports and persist dirs. Do not double-drive one instance.

Teardown:

```sh
node "$SCRIPT" cleanup
```

Cleanup kills only the pid recorded in the state file and deletes that run's persist dir. It does not kill `workerd` by name and does not delete evidence.

Android UI is optional and separate. Instrumentation tests (`ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest` from `android/`) remain the camera/live-capture proof path. This skill's `android-*` commands require `ANDROID_SERIAL` starting with `emulator-` and `ro.kernel.qemu=1`. A Motorola/personal device is a hard refuse.

## Doctor

Run doctor first whenever anything looks off, and after every failed drive:

```sh
node "$SCRIPT" doctor
```

It checks: recorded pid alive, that pid owns port 18787 (or `GENOA_VERIFY_PORT`), persist path under `/tmp/genoa-verify`, health JSON, and unauthenticated conversation list is 401. It refuses production and non-loopback URLs.

Android:

```sh
ANDROID_SERIAL=emulator-5554 node "$SCRIPT" android-doctor
```

## Drive

The default proof is one registry walk:

```sh
node "$SCRIPT" prove send-text
```

`prove` doctors or launches the isolated Worker, runs the in-process recipe, and prints JSON with `ok`, `feature`, `verified`, `skipped`, and `evidenceDir`. `node "$SCRIPT" prove` with no id lists recipes. A skip exits 2 with `skipped: true` and `verified: false`. Do not treat a skip as HTTP proof.

Prove ids: `account`, `conversations`, `send-text`, `notes`, `reactions`, `bookmarks`, `progress`, `rename`, `leave`, `mute-favorite`, `urgent`, `reply-to`, `pagination`, `member-remove`, `upload-resume`, `live`, `account-export-delete`, `blocks`, `search`, `record`, `send-text-android`, `verify-ai`, `fcm`, `ffprobe`, `physical-device`, `genoa-flow-test`.

Never attach to port 8787. Never hit production or `*.workers.dev`. Never drive a physical phone. `ANDROID_SERIAL` must start with `emulator-`. Isolated persist stays under `os.tmpdir()/genoa-verify` (or `GENOA_VERIFY_TMP`), never the user's `backend/.wrangler`.

If `android-doctor` fails, `prove search`, `prove record`, and `prove send-text-android` skip. Do not claim those passed via the API. `verify-ai`, `fcm`, `ffprobe`, `physical-device`, and `genoa-flow-test` are out of harness and always skip.

Primitives stay on the same CLI for paths the registry does not cover: `signup`, `signin`, `api` (`--as` xor `--unauthenticated`), `put-bytes`, `range-get`, `uuid`, `wait`, `android-*`. Prefer Compose content descriptions and visible labels from `android/app/src/androidTest/java/app/gather/GenoaFlowTest.kt`.

Required headers are applied by the helper: `Origin` is the isolated base URL; sign-up sends `X-Invite-Code` from `TEST_INVITE_CODE`, `BETA_INVITE_CODE`, or `backend/.dev.vars`. Authenticated calls send `Authorization: Bearer <token>`. Do not print tokens or invite codes into evidence; the helper already redacts them from saved JSON, including `genoa://join/` URLs.

Passwords are at least 12 characters (`verify-password-123`). Conversation and message ids are client-generated UUIDs; retries with the same id must not duplicate.

Content descriptions used in the app and tests: `Home`, `Record`, `Profile`, `Search conversations`, `Close search`, `Open profile`, `Back`, `Conversation options`, `Reply options`, `Send note`, `Start recording`, `Stop recording`, `Close recorder`, `Close playback`. Visible labels: `Welcome to Genoa`, `Create account`, `Sign in`, `New conversation`, `Choose people`, `Name this conversation`, `Join with invitation`, `Record a reply`, `Record a private video`, `Write a text message`, `Send a note…`, `Saved on this phone`, `Keep draft`, `Start sharing live`, `Sign out`.

## Evidence

Proof lives in `artifacts/verify-genoa/<runId>/` (gitignored with the rest of `artifacts/`; it must still exist after cleanup). Capture the action and the resulting state, not only the last response.

- API: command, status, JSON body, and a second read of the stored value (list conversations, reopen messages as the recipient).
- Android: accessibility dump plus screenshot with the Genoa heading or conversation title visible.
- Side effects: recipient history contains the message; invite join returns the conversation; unauthenticated reads stay 401.
- Do not use test-only endpoints. `/health` is only for doctor. Drive `/auth/*` and `/api/*` as the phone does.
- Do not treat `npm test` or ViewModel setters as user proof. `backend/tools/verify-ai.mjs` hits paid Workers AI on a deployment; it is not this local harness.
- Record the feature id and entry point with every artifact.

## Cleanup

```sh
node "$SCRIPT" cleanup
```

Kills the recorded verification pid and its children, removes `/tmp/genoa-verify/<runId>/`, deletes the state file. Leaves `artifacts/verify-genoa/<runId>/`. Never `pkill workerd` / `pkill wrangler`.

After a failed launch or drive, run cleanup before retrying so ports and persist dirs are not stranded.

## Helpers

| Command | What it does |
|---|---|
| `node "$SCRIPT" prove send-text` | Default: launch or doctor, walk the recipe, write attempt evidence |
| `node "$SCRIPT" prove` | List recipe ids |
| `node "$SCRIPT" launch` | Isolated `wrangler d1 migrations apply` + `wrangler dev --persist-to` on 18787 |
| `node "$SCRIPT" doctor` | Read-only health of that instance |
| `node "$SCRIPT" signup --as <name>` | Invitation-gated account; stores token only in the tmp state file |
| `node "$SCRIPT" signin --as <name>` | `POST /auth/sign-in/email` for a stored name |
| `node "$SCRIPT" api METHOD PATH --as <name> [--body json]` | Phone-equivalent HTTP |
| `node "$SCRIPT" api METHOD PATH --unauthenticated` | Same routes with no `Authorization` |
| `node "$SCRIPT" put-bytes PATH --as <name> --fixture jpeg\|png\|ftyp\|ts` | Octet-stream upload |
| `node "$SCRIPT" range-get PATH --as <name> --range bytes=N-M` | Media range read |
| `node "$SCRIPT" uuid` | One client UUID |
| `node "$SCRIPT" wait --ms N` | Sleep or poll emulator dump |
| `node "$SCRIPT" android-doctor` | Emulator-only gate |
| `node "$SCRIPT" android-dump <path>` | `uiautomator dump` |
| `node "$SCRIPT" android-screenshot <path>` | `screencap` |
| `node "$SCRIPT" android-tap-desc <name>` | Tap unique content description |
| `node "$SCRIPT" android-tap-text <text>` | Tap unique visible text |
| `node "$SCRIPT" android-type <text>` | Type into the focused field |
| `node "$SCRIPT" cleanup` | Stop what launch started; keep evidence |
