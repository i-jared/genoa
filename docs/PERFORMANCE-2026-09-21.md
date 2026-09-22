# Performance review — September 21, 2026

Scope: conversation opening, playback preparation, filmstrip loading, frequent UI updates, session access, API decoding, and recording handoff. This is a targeted source review and functional regression run, not a physical-device benchmark.

| Avoidable work | Change in 0.2.8 | Expected benefit |
|---|---|---|
| Player service connection began after conversation loading | Connect while the watch request is in flight; cancel safely on exit | Overlap independent startup work |
| Multiple filmstrip videos extracted remote frames while the selected video loaded | Wait for the selected first frame, then decode one video thumbnail at a time | Reduce startup network/decoder contention |
| Full-size frames retained for tiny thumbnails | Aspect-preserving decode within 320×320; cache capped by allocated bytes at 4 MiB | Reduce bitmap allocation and garbage-collection pressure |
| One second of buffered media required before initial play | Require 500 ms initially; retain the two-second rebuffer threshold | Allow an earlier first frame when throughput permits |
| UI updates repeatedly decrypted the same session token using Android Keystore | Cache against the encrypted stored value; invalidate on change/logout | Remove repeated cryptographic and binder work from frequent reads |
| API JSON parsed on the caller, often Main | Parse on the IO dispatcher | Avoid blocking UI updates while decoding responses |

The thumbnail API preserves aspect ratio and supports scaled extraction ([Android reference](https://developer.android.com/reference/android/media/MediaMetadataRetriever#getScaledFrameAtTime(long,%20int,%20int,%20int))). Media3 1.8.0's original startup/rebuffer defaults were checked against its [source](https://raw.githubusercontent.com/androidx/media/1.8.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java). Playback resolution, message ordering, selection behavior, recording consent/controls, and send behavior are unchanged by these optimizations.

Validation covers real emulator first-frame rendering, failed preparation/retry, history paging, replay, recording/reply, background progress, notification navigation, and session rotation/logout. Builds, 21 JVM tests, seven emulator regressions, backend tests, and lint passed; final-build session verification also passed. See [FEATURES.md](FEATURES.md) and [logs](../artifacts/sync-review/README.md).

No before/after latency or frame-time measurements were captured on the owner's Razr. Lower startup buffering may trade some initial cushion for faster playback on marginal connections; weak-network testing remains necessary. The 4 MiB figure bounds the thumbnail cache, not all decoder or visible-image memory. Further media delivery work should start with measured first-frame latency and range-request traces on representative recordings; generated thumbnails, MP4 normalization, and adaptive streaming would require a separate media-processing change.
