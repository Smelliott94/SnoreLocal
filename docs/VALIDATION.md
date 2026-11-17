# Validation record

## Collapsible recordings and statistics (2026-11-17)

- All application, JVM-test and Android-instrumentation Kotlin sources compile with
  Kotlin/Compose 2.2.21, Android SDK 36 and JVM target 17, using cached dependencies.
- All 26 JVM tests pass under a 64 MB heap. Coverage includes the previous regression
  suite plus overlapping detection windows, split-file logical IDs, bounded measurement
  checkpoints, PCM energy/levels, silence versus missing data, partial read partitions,
  noon/DST/calendar boundaries, weighted aggregation, mic-test exclusion and legacy,
  cleared or interrupted statistics.
- Actual SQLite SQL was exercised with fresh schema version 3 and upgrades from
  versions 1 and 2. Existing clip/session rows survive; migrated measurements are
  unavailable. Interrupted open events close at their last checkpoint and become partial.
- New isolated Android tests cover the actual migration, audio deletion/expiry with
  retained measurements, clearing statistics without deleting audio, and crash recovery.
  These tests compile but require an Android device/emulator to execute.
- Gradle test/lint/APK tasks were attempted with writable caches and cached dependencies.
  Startup failed before tasks ran: FileLockContentionHandler could not determine a
  usable wildcard IP because the sandbox denies socket access. No new APK or lint
  result is claimed for this change.

Device acceptance still needed: expand/collapse nights and episodes, rotate with
expansion/playback state, listen/seek/share/delete clips, compare a split episode to
its single instance count, check night/week/month navigation, confirm quiet teal versus
missing grey graph sections, and test both audio retention and explicit statistics
clearing. Use annotated sounds to assess detected duration; recordings include context
that is deliberately absent from the estimated-snoring counter. Compare colour scales
using consistent microphone placement. No loudness calibration or accuracy claims.

## Kotlin conversion checks (2026-11-16)

- Converted all four Java application helpers and all four Java unit-test files to Kotlin.
- Compiled all application Kotlin sources directly with the pinned Kotlin 2.2.21 and
  Compose compiler 2.2.21, cached Android SDK 36 and cached project dependencies: passed.
- Compiled and ran all seven Kotlin JUnit tests with Kotlin 2.2.21, JVM 17 target,
  and a 64 MB runtime heap: passed, including actual bundled model labels.
- Updated the standalone core-check script to use Kotlin; it also passed using the
  cached Kotlin 2.3.10 CLI.
- Full Gradle test/lint/APK tasks could not run in this sandbox: Gradle's file-lock
  service requires socket access, which the sandbox denies. Direct compiler checks
  do not replace Android packaging, lint or physical-device validation.

## Initial implementation checks

- Workspace/tool inspection: no Android repository, SDK, Gradle, Kotlin compiler,
  adb or emulator; Java 21.0.6 available. A new standalone project was created.
- Official documentation review: see ANDROID-DECISIONS.md.
- `./tools/test-core.sh`: passed with `javac` and `java -Xmx64m`.
  Checks partial/full/wrapped ring order, latest model window, five-second pre-roll,
  sample start times, smoothed stop threshold, trailing audio, nearby detections,
  exact maximum splits with no missing/duplicated PCM, Stop at a split, Stop during
  an episode, invalid scores, sensitivity thresholds and eight hours of silent PCM
  with bounded history/no disk writes.
- Model setup attempted: failed with `Temporary failure in name resolution`.
- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` attempted:
  Gradle bootstrap failed with the same DNS error before any Gradle tasks ran.
- No Android test/lint/build success and no APK are claimed. No physical device was
  connected. The model binary has not been downloaded or executed here.

## Added but not yet executed

`ClassifierIntegrationTest` loads native LiteRT with the actual bundled model,
checks the tensor/label contract and synthetic silence/440 Hz tone scores, and
round-trips a two-second AAC file with sample-clock metadata. Tests use generated
signals, so there is no third-party audio licence requirement for these checks.
Run on a disposable app installation: the encoder test creates a test session,
uses the normal retention policy, and removes its test recordings afterwards.

For real-snore regression testing, a verified licence candidate is **Snoring.wav
by SortsApostata**, [original Freesound page](https://freesound.org/people/SortsApostata/sounds/233600/),
34.5 seconds, released under [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).
The original page requires login for downloading. No account was created and no
sample was fetched here. A second CC0 candidate is [Snoring by poorenglishjuggler](https://freesound.org/people/poorenglishjuggler/sounds/182758/).
Neither licence nor a single sound effect establishes accuracy on real nights.

After obtaining the CC0 first fixture, preserve its original filename, source URL,
author, licence and SHA-256 in your test-fixture manifest. Convert with ffmpeg:

```sh
mkdir -p app/src/androidTest/assets
ffmpeg -i Snoring.wav -ac 1 -ar 16000 -f s16le app/src/androidTest/assets/snoring.pcm
./gradlew :app:connectedDebugAndroidTest
```

`licensedSnoreFixtureActivatesSegmentation` is explicitly skipped if the PCM asset
is missing. Once provided, it asserts that at least one complete classifier-driven
episode is produced, and reports the peak score. Record the result before claiming
classifier integration works. It does not tune thresholds or invent expected scores.

## Physical-device acceptance checklist

Record device model, Android version/security patch, app commit, model digest,
settings, bedside distance/orientation and room conditions for every run.

- [ ] Build/install on API 26, 29, 33, 34 and 36 where available. Test a real arm64
  phone and a 16 KB page-size device/emulator; use APK Analyzer/zipalign and native
  library ELF alignment checks from the Android 16 KB guide.
- [ ] Deny microphone, deny notifications, permanently deny, disable notification
  channel and use Android microphone privacy toggle. Confirm clear UI errors,
  visible settings recovery, no crash and no background permission prompts.
- [ ] Start from the visible activity, tap Start repeatedly, rotate, navigate away,
  turn screen off. Confirm one service/recording pipeline only.
- [ ] Stop from notification while locked; Stop during an active episode; rapid
  Start/Stop/Start during initialization. Verify closed AAC clips and no leaked
  AudioRecord, codec, thread, foreground notification or wake lock.
- [ ] Run at least one full 8–10 hour night unplugged with screen off. Also test
  battery saver, Doze and OEM default restrictions. Compare saved clip timing to
  known labelled sound events; look for gaps and unexpected session interruption.
- [ ] Play genuine snoring from a separate speaker during a controlled test,
  including quiet/distant snores, speech, breathing, coughs, fan, traffic, music and
  bed movement. Repeat at several sensitivity levels. No playback from this app
  should be possible while monitoring.
- [ ] Interrupt using incoming/active phone call, another recorder, voice assistant,
  microphone mute/privacy, wired/Bluetooth route changes and microphone permission
  revocation. Check interrupted status, error notification and recovery after relaunch.
- [ ] Simulate low storage and the configured size limit on a disposable device.
  Confirm clear error, valid earlier files, safe removal of unfinished files and
  successful restart after freeing space. Use a small test quota rather than filling
  a personal device's filesystem.
- [ ] Force-stop/kill/reboot mid-episode. Relaunch and confirm interrupted session,
  uncommitted clip cleanup, earlier clips playable, and no automatic microphone restart.
- [ ] Play/pause/seek to beginning/end, rotate while playing, background the UI,
  delete selected clip, share into another app. Verify temporary read access and
  no exposure outside the episodes directory.
- [ ] Test retention age/size pruning; multiple sessions spanning midnight and noon;
  wall-clock/time-zone changes; first detection before five seconds; continuous
  snoring beyond five minutes. Inspect clip duration and adjacent split boundaries.

## Measuring accuracy

Use consented, independently annotated bedside audio with snore intervals and
negative events. Keep a held-out set of different nights/rooms/people; calibrate
sensitivity on a separate development set. Run the identical 15,600/7,800-sample
windowing, gate, smoothing and segmenter. For window-level detection report
precision/recall and a precision-recall curve at each threshold; also report missed
snore events, false episodes per hour, clip boundary errors and saved-audio duration.
Run both gated and ungated evaluation so the gate's effect on quiet snores is visible.
Use the engine on a 100 ms sample stream, including the window-boundary splits used
by the service. Report corpus size, duration, noise distribution, matching rules and
confidence intervals. Do not infer bedside accuracy from synthetic tests or a single
CC0 sample. Scores are uncalibrated, not probabilities.

## Measuring battery and overnight continuity

Use the same physical phone and room for baseline and monitoring nights, fixed
screen/radio/charger conditions, similar starting charge and temperature. Reset
`adb shell dumpsys batterystats --reset` before each run, then disconnect USB.
Afterwards capture `adb shell dumpsys batterystats dev.snorelocal`, Battery Historian
or a Perfetto power trace, CPU/wake-lock time, thermal events, session duration,
classifier invocation rate, and saved bytes. Compare idle baseline to several
monitoring runs; report charge or energy per hour with variability. Instrument a
debug build if needed; do not add analytics to the app. Record watchdog/backlog
errors and sample timing drift as continuity failures, not successful silent nights.

The partial wake lock intentionally prevents CPU suspend during monitoring and can
increase power usage. A foreground service alone is not evidence that application
inference and disk writes remain awake. Evaluate an experimental build without the
lock only against a controlled stream of timestamped snore events and screen-off
traces; keep the lock in the default build until data supports removing it.

[Android 16 KB page-size checks](https://developer.android.com/guide/practices/page-sizes)
are still pending for the native LiteRT libraries and final APK.
