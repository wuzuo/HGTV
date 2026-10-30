#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -z "${JAVA_HOME:-}" ]]; then
  for native_jdk in "$HOME"/.cache/hgtv/toolchain/jdk-17*/Contents/Home; do
    if [[ -x "$native_jdk/bin/java" ]]; then export JAVA_HOME="$native_jdk"; break; fi
  done
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/.cache/hgtv/android-sdk" ]]; then
  export ANDROID_HOME="$HOME/.cache/hgtv/android-sdk"
fi
"$repo_dir/kotlin-tv/gradlew" -p "$repo_dir/kotlin-tv" :core:test :app:lintRelease :app:assembleRelease --console=plain
# Use the APK metadata rather than parsing Gradle defaults (environment overrides apply).
command -v python3 >/dev/null || { echo "Python 3 is required" >&2; exit 1; }
native_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$native_sdk" && -f "$repo_dir/kotlin-tv/local.properties" ]]; then
  native_sdk="$(sed -n 's/^sdk.dir=//p' "$repo_dir/kotlin-tv/local.properties")"
fi
native_aapt="${HGTV_AAPT:-}"
if [[ -z "$native_aapt" ]]; then
  for native_tool in "$native_sdk"/build-tools/*/aapt; do
    if [[ -x "$native_tool" ]]; then native_aapt="$native_tool"; fi
  done
fi
[[ -x "$native_aapt" ]] || { echo "Cannot find aapt; set ANDROID_HOME or HGTV_AAPT" >&2; exit 1; }
native_args=()
if [[ -n "${HGTV_RELEASE_TAG:-}" ]]; then native_args+=(--tag "$HGTV_RELEASE_TAG"); fi
if [[ -n "${HGTV_RELEASE_NOTES_FILE:-}" ]]; then native_args+=(--notes-file "$HGTV_RELEASE_NOTES_FILE"); fi
python3 "$repo_dir/scripts/prepare-kotlin-release.py" \
  --apk "$repo_dir/kotlin-tv/app/build/outputs/apk/release/app-release.apk" \
  --aapt "$native_aapt" --output "$repo_dir/outputs/releases" "${native_args[@]}"
