#!/bin/sh
set -eu
# Set ANDROID_SERIAL to choose a device when more than one is connected.
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
probe_source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
probe_build_dir=$(mktemp -d "${TMPDIR:-/tmp}/scrawl-ui-probe.XXXXXX")
trap 'rm -rf "$probe_build_dir"' EXIT
mkdir "$probe_build_dir/classes"
"$JAVA_HOME/bin/javac" -source 8 -target 8 -classpath "$ANDROID_HOME/platforms/android-36/android.jar" -d "$probe_build_dir/classes" "$probe_source_dir/ScrawlUiProbe.java"
"$ANDROID_HOME/build-tools/36.0.0/d8" --lib "$ANDROID_HOME/platforms/android-36/android.jar" --output "$probe_build_dir/scrawl-ui-probe.jar" "$probe_build_dir/classes/ScrawlUiProbe.class"
adb push "$probe_build_dir/scrawl-ui-probe.jar" /data/local/tmp/scrawl-ui-probe.jar
# Validate arguments locally before passing them across adb's shell boundary.
case "${1:-}" in press|release|select) ;; *) echo 'Usage: run.sh press|release|select screenX screenY' >&2; exit 2;; esac
case "${2:-}" in ''|*[!0-9]*) exit 2;; esac
case "${3:-}" in ''|*[!0-9]*) exit 2;; esac
[ "$#" -eq 3 ]
adb shell "CLASSPATH=/data/local/tmp/scrawl-ui-probe.jar app_process /system/bin ScrawlUiProbe $1 $2 $3"
