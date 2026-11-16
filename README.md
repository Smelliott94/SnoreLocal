# Snore Local

Native Android sleep audio monitor in Kotlin and Jetpack Compose. It saves individual
YAMNet-classified snoring episodes locally as `.m4a` files. No account, server,
analytics, network permission, or runtime model download. The two small PCM and
segmentation helpers use plain Java so their contracts can also be tested with a JDK.

**Delivery status:** the model and build dependencies are installed, and an earlier
APK build passed unit tests and lint (0 errors, 11 warnings). The latest model-loader
fix and microphone-test mode require a fresh Android build. JVM regression checks
pass; emulator/device recording and native classifier behaviour still need validation.
See [validation](docs/VALIDATION.md). The build fails without the model; there is no
volume-detector fallback.

## Build and install

Requires Python 3.10+, JDK 17 or 21, Android SDK platform 36 and build tools 35.0.0.
Install these using Android Studio's SDK Manager, or your SDK's `sdkmanager`:

```sh
sdkmanager "platforms;android-36" "build-tools;35.0.0" "platform-tools"
# Accept Android SDK licences through the SDK Manager or sdkmanager --licenses.
# Set ANDROID_HOME to your SDK directory, or create local.properties with sdk.dir=...
cd SnoreLocal
python3 tools/setup.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `python tools/setup.py` and `gradlew.bat`. Import this folder into
Android Studio after setup. The launch scripts currently use a Python bootstrap
for pinned Gradle 8.13, checking the distribution against the official HTTPS
SHA-256 file. They are explicitly **not** generated Gradle Wrapper scripts.
With Gradle 8.13 installed you can use `gradle` directly and optionally generate a
standard wrapper with `gradle wrapper --gradle-version 8.13`.

`tools/setup.py` downloads Google's **version 1** metadata-bearing YAMNet to assets,
checks its associated Snoring label, and records SHA-256. Subsequent builds and
runtime startup verify that digest. Initial trust is HTTPS to the versioned Google
URL, plus the GCS transport checksum when supplied; this is not an independently
published model signature. Commit `yamnet.tflite` and `yamnet.sha256` after the first
successful setup to fix the model bytes for your checkout. Once dependencies and
the model are cached, `./gradlew --offline :app:assembleDebug` makes an offline APK.

Pinned stack: AGP 8.13.2, Gradle 8.13, Kotlin/Compose compiler 2.2.21, Compose BOM
2025.10.01, Activity 1.11.0, Core 1.17.0, Lifecycle 2.9.4, Coroutines 1.10.2, standalone
LiteRT 1.4.1. Minimum Android 8.0 / API 26; compile and target API 36. See
[official-source decisions](docs/ANDROID-DECISIONS.md) for compatibility and platform
requirements checked during implementation. Dependency resolution itself remains unverified.

## Use

1. Open the app, choose sensitivity and storage preferences, and press Start.
2. Allow microphone and notifications. Starting requires a visible activity. If
   permissions or the notification channel are disabled, enable them in Android's
   app settings before retrying.
3. Turn the screen off. Use Stop in the app or notification when awake.
4. Play, pause, seek, delete or share saved clips. Stop monitoring before playback
   or sharing. Playback releases audio resources when the activity leaves the screen.

For a microphone check, enable **Record everything (mic test)** before starting.
This explicit mode skips the classifier and sound gate and saves all captured audio
from the first sample until Stop, splitting every five minutes. It works even when
classifier initialization is failing. The notification states that ALL audio is
being recorded, and saved sessions are labelled Mic test. Stop, then play the saved
clip to check that the emulator or device microphone is receiving sound. Switch
it off to return to snoring detection. Normal permissions, private storage, retention
and storage limits apply in both modes.

Settings apply to the next session. Higher sensitivity lowers the YAMNet start
threshold (0.50 → 0.20) and the gate level. These are uncalibrated defaults, not
measured accuracy. Recordings are grouped by local night, defined as noon to noon;
multiple manual sessions remain individually labelled completed or interrupted.

## Architecture and defaults

- `MonitorService`: microphone foreground service; duplicate starts are ignored.
  Captures mono 16 kHz PCM16 using blocking AudioRecord reads on a dedicated worker.
  A pool of 24 reusable 100 ms buffers feeds a bounded 20-block queue. Queue/pool
  exhaustion stops monitoring explicitly instead of dropping audio. Classifier,
  encoding and disk I/O run on a second worker, outside the activity lifecycle.
- `Yamnet`: CPU inference, one thread, normalized float32, 15,600-sample windows,
  7,800-sample hop (0.975 s / 0.4875 s). Checks model digest, associated labels,
  float tensor sizes and native inference at startup. Only index 38, verified to be
  `Snoring`, triggers an episode. A conservative adaptive RMS gate bypasses every
  fourth window (~1.95 s) and all active episodes. Quiet snores can still be missed;
  validation must include distance, background fan noise and low levels.
- `EpisodeEngine` / `PcmRing`: five-second fixed PCM history (160 KB), exponential
  score smoothing (65% current / 35% previous), stop threshold 55% of start.
  Six seconds after the last qualifying smoothed score are retained by default;
  this is the adjustable 2–15 second merge gap/tail. Later detections reset the
  deadline. Sustained episodes split at exactly five minutes with contiguous PCM,
  and Stop flushes the active episode. History is written only when classification
  starts an episode. Detection-window delay is included in the five-second pre-roll.
- `AacEpisodeSink`: streaming MediaCodec AAC-LC, mono 16 kHz, target 32 kbit/s in
  MPEG-4 (`.m4a`, share MIME `audio/mp4`). Budget ~240 KB per minute / 14.4 MB per
  hour **of saved clips**, plus container overhead. Actual encoder output varies.
  Split PCM is contiguous; AAC encoder priming/padding may add a small playback
  boundary and must be measured per device. No whole episode accumulates in RAM.
- Timestamp: clip start derives from its original sample index, including pre-roll.
  The initial hardware AudioTimestamp, if available, maps the first sample to wall
  time using Android's boot clock. If unavailable, the instant before capture starts
  is the fallback origin; absolute timing then has device input-latency uncertainty.
  Sample-relative duration remains exact. Clock changes during a session do not
  rewrite earlier timestamps. Capture timing drift >2 seconds stops the session.
- `Repository`: SQLite metadata/session state and app-private `files/episodes`.
  Files are finalized from `.part` to `.m4a` before adding metadata. On process
  restart, uncommitted parts/orphans are removed and running sessions become
  interrupted. Earlier committed clips survive. An uncommitted active clip can be
  lost after process death; the session explains that loss. Automatic OS process
  restarts do not silently resume microphone recording.
- Retention defaults: 14 days, 512 MiB. Expired/over-budget oldest clips are pruned
  at Start or Apply retention. During monitoring, reaching the limit or less than
  32 MiB device free space stops with an error instead of deleting the current night.
  Live disk use is checked once per captured second; the limit can overshoot by a
  small encoder/container buffer before the error is reported.
- Foreground notification with Stop; microphone permission and foreground-service
  type/permission; notification permission requested on API 33+. Active monitoring
  holds a partial wake lock, bounded by a 12-hour session limit and released on
  cleanup. This deliberately trades battery for continuity. It is not proof of
  overnight reliability on any particular device. No blanket battery-optimization
  exemption is requested. Do not enable one without first measuring the device.
- Microphone errors, Android 10+ client silencing, microphone mute, capture drift,
  processing backlog, codec/model/storage errors stop the session and appear in
  the UI and an error notification. Unexpected process termination is reported on
  next launch. Android 8/9 provide weaker interruption information; test actual calls
  and competing recorders. Hardware/OS may occasionally return silence without an
  error; the app cannot infer microphone failure solely from quiet room audio.
- FileProvider grants temporary read access only for an explicitly shared recording.
  App backup is disabled. Android's destination share app controls the exported copy.

## Tests and measurement

```sh
./tools/test-core.sh                    # JDK only; no Android tools/dependencies
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest # Android device/emulator with microphone
```

Core checks cover wraparound, pre-roll, sample timestamps, tails, merging, exact
splits/no sample gaps, Stop and bounded eight-hour history under a 64 MB heap.
Instrumentation tests exercise native model loading, synthetic non-snore scores,
and AAC container/metadata integration. They have been written but not run here.
[Validation](docs/VALIDATION.md) includes a licensed real-snore fixture candidate,
accuracy protocol, battery protocol and full physical-device checklist.

Apache 2.0; bundled third-party attribution is in `app/src/main/assets/NOTICE.txt`.
