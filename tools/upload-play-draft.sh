#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <signed-aab> <play-track>" >&2
  echo "Example: $0 ~/Downloads/scrawl-v0.1.17.aab beta" >&2
  exit 2
}

[[ $# -eq 2 ]] || usage
bundle=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
track=$2
[[ -f "$bundle" && "$bundle" == *.aab ]] || usage
[[ "$track" =~ ^[a-zA-Z0-9._-]+$ ]] || usage

if [[ -z "${ANDROID_PUBLISHER_CREDENTIALS:-}" ]]; then
  play_json=${PLAY_SERVICE_ACCOUNT_JSON:-$HOME/.config/scrawl/play-publisher.json}
  [[ -f "$play_json" ]] || {
    echo "Set PLAY_SERVICE_ACCOUNT_JSON to a Play API service-account JSON file, or set ANDROID_PUBLISHER_CREDENTIALS to its contents." >&2
    exit 1
  }
  export ANDROID_PUBLISHER_CREDENTIALS
  ANDROID_PUBLISHER_CREDENTIALS=$(<"$play_json")
fi

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
notes="$repo/app/src/main/play/release-notes/en-US/default.txt"
[[ -s "$notes" ]] || { echo "Missing release notes: $notes" >&2; exit 1; }
[[ $(wc -m < "$notes") -le 500 ]] || { echo "Play release notes exceed 500 characters" >&2; exit 1; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cp "$bundle" "$tmp/"
cd "$repo"
if [[ -z "${JAVA_HOME:-}" && -x "$HOME/.local/share/scrawl-build/jdk17/bin/java" ]]; then
  export JAVA_HOME="$HOME/.local/share/scrawl-build/jdk17"
elif [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/.local/share/android-sdk" ]]; then
  export ANDROID_HOME="$HOME/.local/share/android-sdk"
elif [[ -z "${ANDROID_HOME:-}" && -d /opt/homebrew/share/android-commandlinetools ]]; then
  export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
fi
if [[ -z "${SCRAWL_ONYX_MAVEN:-}" && -d "$HOME/.local/share/scrawl-build/onyx-maven" ]]; then
  export SCRAWL_ONYX_MAVEN="$HOME/.local/share/scrawl-build/onyx-maven"
fi
[[ -x "${JAVA_HOME:-}/bin/java" ]] || { echo "Java 17 is required; set JAVA_HOME" >&2; exit 1; }
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:publishReleaseBundle --artifact-dir "$tmp" --track "$track"
