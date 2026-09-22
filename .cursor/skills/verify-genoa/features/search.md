# Search conversations

Search lets a person filter the loaded Home list by conversation title, see a complete empty state, and clear the query.

## Sub-features

- `search-open` opens search from Home.
- `search-match` keeps matching titles and hides the rest.
- `search-empty` shows `No conversations match ‘<query>’.`
- `search-clear` restores the unfiltered list.

## How to get to it (user POV)

- On Home, choose `Search conversations` and type in `Search loaded conversations`.
- Choose `Clear search` or `Close search`.

## Driving it with control-genoa

Default. `ANDROID_SERIAL=emulator-5554 node .cursor/skills/verify-genoa/scripts/control-genoa.mjs prove search`. Skips without an emulator.

Preconditions:

- Dedicated emulator with `ANDROID_SERIAL=emulator-5554` passing `android-doctor`.
- Signed-in session on that emulator pointed at the isolated Worker (`Server address` `http://10.0.2.2:18787`) or the local test backend the recipe names.
- Home includes a conversation titled `Weekend plans` and does not include `Volcano club`.

- **Open search.** Choose `Search conversations`. Run `ANDROID_SERIAL=emulator-5554 node .cursor/skills/verify-genoa/scripts/control-genoa.mjs android-tap-desc "Search conversations"`. Dump shows a field labeled `Search loaded conversations`.
- **Empty state.** Type `missing conversation`. After input, run `android-dump` on `artifacts/verify-genoa/<runId>/search/empty.xml`. The dump contains `No conversations match ‘missing conversation’.`
- **Clear.** Choose `Close search`. Run `android-tap-desc "Close search"`. Home shows `Weekend plans` again.
- **Proof.** `ANDROID_SERIAL=emulator-5554 node .cursor/skills/verify-genoa/scripts/control-genoa.mjs android-screenshot artifacts/verify-genoa/<runId>/search/empty.png`. The screenshot shows the Genoa heading and the empty-match copy.

If `android-doctor` fails, stop. Search is client-side over loaded titles; proving it through `GET /api/conversations` is a different entry point and must not be reported as `search-open`.

## Gotchas

- Search only filters conversations already loaded on the phone. It is not a server-wide query.
- The empty copy uses a curly apostrophe in `‘query’`. Assert that exact string.
- `Unwatched` is a separate Home chip, not search.
- Do not drive this recipe on a personal device even if adb can see it.
