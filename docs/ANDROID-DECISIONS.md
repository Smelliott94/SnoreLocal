# Platform and dependency decisions

Official documentation reviewed during implementation on 2026-11-16. Documentation
compatibility is not a substitute for compiling and running the complete stack.

| Decision | Official evidence | Implementation |
| --- | --- | --- |
| AGP 8.13.2 / Gradle 8.13 / compile SDK 36 | [AGP 8.13 release and compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes) supports API 36.1, Gradle 8.13 and JDK 17+. | Pinned versions; no dynamic dependencies. Host Java 21 meets the JDK minimum. |
| Kotlin 2.2.21 with Compose compiler 2.2.21 | [Compose setup](https://developer.android.com/develop/ui/compose/compiler), [compatibility](https://developer.android.com/jetpack/androidx/releases/compose-kotlin), [Kotlin/AGP support](https://developer.android.com/build/kotlin-support). | Matching Kotlin/Compose compiler plugin; Compose BOM 2025.10.01 manages UI libraries. |
| Compose BOM | [Official BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping). | Conservative pinned BOM, not the current latest release; dependency resolution and AAR metadata still need a real build. |
| Standalone LiteRT | [LiteRT 1.4.0 release](https://github.com/google-ai-edge/LiteRT/releases/tag/v1.4.0) reports a 16 KB page fix; [1.4.1](https://github.com/google-ai-edge/LiteRT/releases/tag/v1.4.1) fixes minSdk. | Pin 1.4.1, CPU Interpreter API, no Play Services, no task/support JNI libraries. APK ELF/ZIP alignment and native loading on a 16 KB device still require validation. |
| YAMNet waveform and categories | [Official classifier model](https://ai.google.dev/edge/mediapipe/solutions/audio/audio_classifier), [YAMNet source](https://github.com/tensorflow/models/tree/master/research/audioset/yamnet), [class map](https://raw.githubusercontent.com/tensorflow/models/master/research/audioset/yamnet/yamnet_class_map.csv). | Use the metadata-bearing 15,600 float-sample model, mono 16 kHz. Verify 521 output scores and associated Snoring label at index 38 at runtime. Versioned Google URL is in setup.py. Tensor sizes and native operators remain unverified against the downloaded bytes here. |
| Score meaning | [Google's YAMNet model card](https://github.com/tensorflow/tfhub.dev/blob/master/assets/docs/google/models/yamnet/1.md) warns about uncalibrated scores and domain mismatch. | Score thresholds are initial tuning defaults, not probabilities or an accuracy claim. |
| Microphone foreground service | [Service types](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone), [background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). | RECORD_AUDIO runtime permission, FOREGROUND_SERVICE and FOREGROUND_SERVICE_MICROPHONE declarations, microphone service type, visible-activity Start. START_NOT_STICKY; no boot receiver or automatic restart. |
| Notification visibility | [Notification runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission). | Request POST_NOTIFICATIONS on API 33+; require notifications enabled before Start so status/Stop are visible. Android does not itself require that grant merely to start an FGS. |
| CPU wakefulness | [Wake locks](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock), [choose API](https://developer.android.com/develop/background-work/background-tasks/awake). | Explicit partial lock only while monitoring, with timeout and cleanup. AudioRecord + application inference/encoding must continue while the screen is off. This conservative choice must be measured against device audio pipeline behaviour. |
| Input interruptions | [Sharing audio input](https://developer.android.com/media/platform/sharing-audio-input), [AudioRecord API](https://developer.android.com/reference/android/media/AudioRecord). | API 29+ per-recording callback detects client silencing. All versions check read errors, recording state, microphone mute, stalled reads, backlog and timing drift. Older devices lack equivalent per-client silence reporting. |
| Compressed audio | [Supported media formats](https://developer.android.com/media/platform/supported-formats#audio-formats), [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec), [MediaMuxer](https://developer.android.com/reference/android/media/MediaMuxer). | PCM16 → AAC-LC at target 32 kbit/s → MPEG-4. Explicit error if the requested encoder configuration is unsupported. Test encoder priming/padding on each target device. |
| Private sharing | [FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider), [secure file sharing](https://developer.android.com/training/secure-file-sharing). | App-private files, restricted episodes path, exported=false provider, temporary read URI grant and ClipData. No broad storage permissions. |

The Android 15 dataSync/mediaProcessing FGS timeouts are not a reason to label this
service as either of those types: it is capturing the microphone throughout. The
12-hour cap is an application default to bound unattended capture and wake-lock
holding time, not a claimed Android microphone-service time limit.

Android 16 edge-to-edge/inset behaviour is handled with enableEdgeToEdge and Compose
safeDrawingPadding. Newer OEM background restrictions still require physical tests;
no manifest declaration can promise that an app will survive a user force-stop.
