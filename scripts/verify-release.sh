#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK_DIR=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export PATH="$JAVA_HOME/bin:$PATH"
EXPECTED_VERSION=$(sed -n 's/.*versionName.*?: "\([^"]*\)"/\1/p' "$ROOT/app/build.gradle.kts" | head -n 1)
if [ "${1:-}" = "--version" ]; then
  test "$#" -ge 2 || { echo "usage: $0 [--version VERSION] [APK]" >&2; exit 2; }
  EXPECTED_VERSION=$2
  shift 2
fi
APK=${1:-$ROOT/artifacts/v$EXPECTED_VERSION/haminn-v$EXPECTED_VERSION-release.apk}
if [ ! -f "$APK" ]; then APK=$ROOT/app/build/outputs/apk/release/app-release.apk; fi
test -f "$APK"
"$SDK_DIR/build-tools/37.0.0/apksigner" verify --verbose --print-certs "$APK"
BADGING=$("$SDK_DIR/build-tools/37.0.0/aapt" dump badging "$APK")
printf '%s\n' "$BADGING" | grep "package: name='life.airen.haminn'"
printf '%s\n' "$BADGING" | grep "versionName='$EXPECTED_VERSION'"
printf '%s\n' "$BADGING" | grep "sdkVersion:'29'"
printf '%s\n' "$BADGING" | grep "targetSdkVersion:'37'"
if "$SDK_DIR/build-tools/37.0.0/aapt" dump xmltree "$APK" AndroidManifest.xml | grep -q 'android:debuggable'; then
  echo "Release APK is debuggable" >&2
  exit 1
fi
shasum -a 256 "$APK"
