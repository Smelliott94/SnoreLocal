#!/bin/sh
set -eu
cd "$(dirname "$0")/.."

# These Android-independent contracts can run without an Android SDK or Gradle.
# Install the Kotlin CLI, or set KOTLINC to the path of its kotlinc launcher.
compiler=${KOTLINC:-kotlinc}
if command -v "$compiler" >/dev/null 2>&1; then
    compiler=$(command -v "$compiler")
elif [ ! -f "$compiler" ]; then
    printf '%s\n' 'Kotlin compiler not found. Install kotlinc or set KOTLINC=/path/to/kotlinc.' >&2
    printf '%s\n' 'With Android build tools installed, ./gradlew :app:testDebugUnitTest also runs these checks.' >&2
    exit 127
fi

out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT HUP INT TERM
set -- \
    app/src/main/java/dev/snorelocal/PcmRing.kt \
    app/src/main/java/dev/snorelocal/EpisodeEngine.kt \
    app/src/main/java/dev/snorelocal/ContinuousRecorder.kt \
    app/src/main/java/dev/snorelocal/ModelLabels.kt \
    app/src/test/java/dev/snorelocal/CoreChecks.kt \
    -jvm-target 17 -include-runtime -d "$out/core-checks.jar"

if [ -x "$compiler" ]; then
    "$compiler" "$@"
else
    # Some IDE distributions preserve the launcher as a readable shell script.
    bash "$compiler" "$@"
fi
java -Xmx64m -jar "$out/core-checks.jar"
