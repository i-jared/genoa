# Record a reply

Record a reply lets a person capture a private voice or video for a chosen recipient, stop and review it on the phone, then send explicitly or keep a draft. Capture does not publish until send succeeds.

## Sub-features

- `record-recipient` shows `To: <conversation>` before capture.
- `record-start-stop` toggles `Start recording` / `Stop recording`.
- `record-review` shows `Saved on this phone` after stop.
- `record-send` publishes only after `Send to <conversation>`.
- `record-draft` keeps a local draft when the person chooses `Keep draft`.

## How to get to it (user POV)

- Inside a thread, choose `Reply options` then `Record a private video` or `Record a voice message`.
- Home `Record` and the thread `Record a reply` action open live sharing, not this private recorder.

## Driving it with control-genoa

Default. `ANDROID_SERIAL=emulator-5554 node .cursor/skills/verify-genoa/scripts/control-genoa.mjs prove record`. Skips without an emulator.

Preconditions:

- Dedicated emulator `ANDROID_SERIAL=emulator-5554` with synthetic cameras, passing `android-doctor`.
- The app is signed in, has conversation `The weekend crew` or `Weekend plans`, and has camera plus microphone permission.
- Isolated or README local backend is the server in `Server address`.
- No ready audio/video message should already be sending for this proof.

- **Open private recorder from a thread.** Choose `Reply options`, then `Record a private video` or `Record a voice message`. Screen shows `To: Weekend plans` and `Start recording`.
- **Capture.** Choose `Start recording`, wait until `Stop recording` is available, wait at least two seconds, choose `Stop recording`. Run `android-tap-desc "Start recording"` then later `android-tap-desc "Stop recording"`. Review shows `Saved on this phone`.
- **Send.** Choose `Send to Weekend plans`. Run `android-tap-text "Send to Weekend plans"`. Doctor the API as a second member: `node .cursor/skills/verify-genoa/scripts/control-genoa.mjs api GET /api/conversations/<cid>/messages --as bob` eventually contains one `kind` `audio` or `video` with `state` `ready` and a non-null `asset_id`.
- **Draft path (separate run).** After review, choose `Keep draft` instead of send. Run `android-tap-text "Keep draft"`. Thread shows `Draft · Saved on this phone` and GET messages as bob still has no new ready media.
- **Proof.** Screenshots `recorder.png` and `review.png` plus bob's message list JSON. Capture must show the recipient name; the API read proves publication or its absence.

If `android-doctor` fails, mark every sub-feature unreachable. Do not upload arbitrary files through `/api/uploads` as a stand-in for tapping `Start recording`.

## Gotchas

- Recording is tap to start/stop, then an explicit send. Never treat in-progress capture as sent.
- Synthetic emulator cameras can take tens of seconds before encoded bytes exist. Wait for `Stop recording` and review copy, not a fixed short sleep.
- `Close recorder` while capturing asks `Stop and review` vs `Keep recording`. Discard is a separate confirmation.
- Live sharing is the default `Record a reply` / Home `Record` path (`Start sharing live`). This recipe is only the private recorder in `Reply options`.
- Physical devices are out of bounds for this harness even when they already have the beta installed.
