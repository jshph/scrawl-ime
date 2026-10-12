#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <new-version> <release-notes-file>" >&2
  echo "Example: $0 0.1.18 /path/to/notes.txt" >&2
  exit 2
}

[[ $# -eq 2 && "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && -s "$2" ]] || usage
version=$1
notes_source=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo"

[[ -z "$(git status --porcelain)" ]] || { echo "Worktree must be clean" >&2; exit 1; }
[[ "$(wc -m < "$notes_source")" -le 500 ]] || { echo "Play release notes exceed 500 characters" >&2; exit 1; }
[[ -z "$(git tag --list "v$version")" ]] || { echo "Tag v$version already exists" >&2; exit 1; }

signing_env=${SCRAWL_SIGNING_ENV:-$HOME/.config/scrawl/signing.env}
keystore_source=${SCRAWL_KEYSTORE_PATH:-$HOME/.config/scrawl/scrawl-release.jks}
[[ -f "$signing_env" ]] || { echo "Missing signing env: $signing_env" >&2; exit 1; }
[[ -f "$keystore_source" ]] || { echo "Missing release keystore: $keystore_source" >&2; exit 1; }
set -a
# shellcheck disable=SC1090
source "$signing_env"
set +a
[[ -n "${KEYSTORE_PASSWORD:-}" && -n "${KEY_PASSWORD:-}" ]] || { echo "Missing signing passwords in $signing_env" >&2; exit 1; }

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

keystore_target="$repo/keystore/scrawl-release.jks"
created_link=0
if [[ ! -e "$keystore_target" ]]; then
  mkdir -p "$(dirname "$keystore_target")"
  ln -s "$keystore_source" "$keystore_target"
  created_link=1
elif [[ "$keystore_target" != "$keystore_source" && ! "$keystore_target" -ef "$keystore_source" ]]; then
  echo "Worktree keystore differs from SCRAWL_KEYSTORE_PATH" >&2
  exit 1
fi
cleanup() { if [[ "$created_link" -eq 1 ]]; then rm -f "$keystore_target"; fi; }
trap cleanup EXIT

python3 - "$version" <<'PY'
import pathlib, re, sys
p = pathlib.Path('app/build.gradle')
s = p.read_text()
code = re.search(r'(?m)^\s*versionCode\s+(\d+)\s*$', s)
name = re.search(r"(?m)^\s*versionName\s+'([^']+)'\s*$", s)
assert code and name, 'Could not find unique version fields'
assert len(re.findall(r'(?m)^\s*versionCode\s+\d+\s*$', s)) == 1
assert len(re.findall(r"(?m)^\s*versionName\s+'[^']+'\s*$", s)) == 1
old = tuple(map(int, name.group(1).split('.')))
new = tuple(map(int, sys.argv[1].split('.')))
assert new > old, 'New version must be greater than current version'
s = s[:code.start(1)] + str(int(code.group(1)) + 1) + s[code.end(1):]
s = re.sub(r"(?m)^(\s*versionName\s+)'[^']+'", lambda m: m.group(1) + "'" + sys.argv[1] + "'", s, count=1)
p.write_text(s)
print('versionCode', int(code.group(1)) + 1, 'versionName', sys.argv[1])
PY
notes_target="$repo/app/src/main/play/release-notes/en-US/default.txt"
cp "$notes_source" "$notes_target"

./gradlew :app:testDebugUnitTest :app:bundleRelease :app:assembleRelease --console plain
bundle="$repo/app/build/outputs/bundle/release/app-release.aab"
apk="$repo/app/build/outputs/apk/release/app-release.apk"
[[ -s "$bundle" && -s "$apk" ]] || { echo "Signed artifacts missing" >&2; exit 1; }
"$JAVA_HOME/bin/jarsigner" -verify "$bundle" >/dev/null 2>&1
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify "$apk"

output_dir=${SCRAWL_RELEASE_OUTPUT_DIR:-$HOME/Downloads}
mkdir -p "$output_dir"
for extension in aab apk; do
  [[ ! -e "$output_dir/scrawl-v$version.$extension" ]] || { echo "Output already exists: $output_dir/scrawl-v$version.$extension" >&2; exit 1; }
done
cp "$bundle" "$output_dir/scrawl-v$version.aab"
cp "$apk" "$output_dir/scrawl-v$version.apk"
cp "$notes_target" "$output_dir/scrawl-v$version-release-notes.txt"

git add app/build.gradle app/src/main/play/release-notes/en-US/default.txt
git diff --cached --check
git commit -m "Release v$version"
git tag "v$version"
if command -v shasum >/dev/null; then
  shasum -a 256 "$output_dir/scrawl-v$version.aab" "$output_dir/scrawl-v$version.apk"
else
  sha256sum "$output_dir/scrawl-v$version.aab" "$output_dir/scrawl-v$version.apk"
fi
echo "Created v$version locally; no Git push or Play upload performed."
