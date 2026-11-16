#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac -d "$out" app/src/main/java/dev/snorelocal/PcmRing.java app/src/main/java/dev/snorelocal/EpisodeEngine.java app/src/test/java/dev/snorelocal/CoreChecks.java
java -Xmx64m -cp "$out" dev.snorelocal.CoreChecks
