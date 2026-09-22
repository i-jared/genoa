# Engineering and product guidelines

Build reliable recording, sending, and watching. Prefer clear ownership and predictable behavior over architectural machinery. Read PLAN.md and docs/FEATURES.md before changing scope. Android is now Kotlin/Compose; the original Expo recommendation is superseded for the Android implementation.

## Engineering

- Organize by feature. Keep routes thin and business rules close to their owner.
- D1 owns messages and permissions; R2 owns media; the phone owns unsent drafts. Live events are hints to synchronize, not a second database.
- Model explicit lifecycle states. Never claim a message was sent before server acceptance.
- Authorize every read, mutation, media request, and live subscription on the server.
- Make retries safe using stable IDs and idempotent jobs. Plan for duplicate delivery.
- Persist drafts before uploading; survive app restart and connectivity loss.
- Keep network/database work out of composables. Share contracts, not platform internals.
- Validate external inputs; avoid unchecked casts and unbounded payloads.
- Pass dependencies explicitly. Avoid hidden globals, circular dependencies, premature factories, and wrappers that only rename APIs.
- Abstract demonstrated repetition, not imagined future needs.
- Deletion includes media, derivatives, queued work, and access revocation. Late work must not resurrect deleted content.
- Log identifiers and error categories, never credentials, signed URLs, recordings, or transcripts.
- Comments explain reasons and constraints rather than narrating syntax.
- Test authorization, retries, interrupted uploads, deletion races, and device background behavior. Tests must verify meaningful behavior rather than mirror implementation.
- Keep changes reviewable. Run relevant builds, tests, and static checks. Never label scaffolding or an untested integration complete.
- Add infrastructure only when the corresponding feature requires it.

## UI and interaction

Follow [the Genoa design specification](docs/DESIGN.md) for all Android UI changes, including its compact presentation rules and custom icon set. Preserve existing functionality when applying the design language.

- Make the recipient, unwatched messages, and reply action obvious.
- Prioritize people over features, metrics, or promotions.
- One clear primary action per context; use consistent typography, spacing, and restrained color.
- Clearly distinguish new, partially watched, and finished messages without relying on color alone.
- Recording shows recipient, timer, and unmistakable status. Tap to start/stop; explicitly send after recording. Discard deliberately.
- Live sharing requires explicit consent and an unmistakable sharing state before recipients can watch.
- Distinguish uploading, processing, sent, and failed. Errors explain what happened and provide a recovery action.
- Resume playback, preserve position, and avoid unsolicited autoplay or unexpected speed changes.
- Attach replies/reactions to their source. Put advanced controls near the relevant content.
- Use accessible labels, at least 48dp targets, scalable text, contrast, and alternatives to gestures.
- Keep control positions stable when notices, captions, and reactions appear.
- Core surfaces: conversations, watch, recorder, settings. Bookmarks, notes, reminders belong in context.
- Bad smells: disappearing drafts, fake success, dead controls, mystery spinners, tiny icons, dense feature dashboards, recording without clear status, and surprise audio.

## Definition of done

Can a new person find a message, watch it, reply, and tell whether it sent without instruction? Does it survive retry, app restart, connection loss, and permission changes? Is ownership understandable? Record verification and remaining deployment/device requirements honestly in docs/FEATURES.md.
