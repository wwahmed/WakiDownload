#!/usr/bin/env bash
# Build the signed release APK. Prints the APK path on success.
#   scripts/build.sh            -> app/build/outputs/apk/release/app-release.apk
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
cd "$REPO"
./gradlew :app:testReleaseUnitTest :app:assembleRelease --console=plain
APK="$REPO/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "✗ no APK at $APK" >&2; exit 1; }
echo "$APK"
