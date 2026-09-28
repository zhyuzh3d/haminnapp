#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ADB=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb
MATRIX_PATH=
if [ "${1:-}" = "--matrix" ]; then
  test "$#" -eq 2 || { echo "usage: $0 [--matrix OUTPUT.json]" >&2; exit 2; }
  MATRIX_PATH=$2
elif [ "$#" -ne 0 ]; then
  echo "usage: $0 [--matrix OUTPUT.json]" >&2
  exit 2
fi
DEVICE_COUNT=$($ADB devices | awk 'NR>1 && $2=="device" {n++} END {print n+0}')
test "$DEVICE_COUNT" -gt 0 || { echo "No ready Android device or emulator" >&2; exit 1; }
test "$DEVICE_COUNT" -eq 1 || { echo "Exactly one ready device is required for an unambiguous report" >&2; exit 1; }
SDK=$($ADB shell getprop ro.build.version.sdk | tr -d '\r')
RELEASE=$($ADB shell dumpsys package life.airen.haminn 2>/dev/null | grep -E 'versionName=|versionCode=' | head -n 2 || true)
DEBUG=$($ADB shell dumpsys package life.airen.haminn.debug 2>/dev/null | grep -E 'versionName=|versionCode=' | head -n 2 || true)
RELEASE_JSON=$(printf '%s' "$RELEASE" | tr '\n' ';' | sed 's/;*$//')
DEBUG_JSON=$(printf '%s' "$DEBUG" | tr '\n' ';' | sed 's/;*$//')
WEBVIEW=$($ADB shell dumpsys webviewupdate 2>/dev/null | sed -n 's/.*Current WebView package (name, version): (\([^)]*\)).*/\1/p' | head -n 1 | tr -d '\r' || true)
MODEL=$($ADB shell getprop ro.product.model | tr -d '\r')
FINGERPRINT=$($ADB shell getprop ro.build.fingerprint | tr -d '\r')
IS_EMULATOR=$($ADB shell getprop ro.kernel.qemu | tr -d '\r')
printf 'sdk=%s\nmodel=%s\nwebview=%s\n' "$SDK" "$MODEL" "$WEBVIEW"
printf '%s\n%s\n' "$RELEASE" "$DEBUG" | sed '/^[[:space:]]*$/d'

if [ -n "$MATRIX_PATH" ]; then
  case "$MATRIX_PATH" in
    /*) OUTPUT=$MATRIX_PATH ;;
    *) OUTPUT=$ROOT/$MATRIX_PATH ;;
  esac
  mkdir -p "$(dirname -- "$OUTPUT")"
  escape_json() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
  GENERATED_AT=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  TEMP_FILE=$OUTPUT.tmp.$$
  {
    printf '{\n'
    printf '  "generatedAt": "%s",\n' "$(escape_json "$GENERATED_AT")"
    printf '  "device": {\n'
    printf '    "kind": "%s",\n' "$(if [ "$IS_EMULATOR" = "1" ]; then printf emulator; else printf physical; fi)"
    printf '    "model": "%s",\n' "$(escape_json "$MODEL")"
    printf '    "api": %s,\n' "$SDK"
    printf '    "buildFingerprint": "%s",\n' "$(escape_json "$FINGERPRINT")"
    printf '    "webView": "%s"\n' "$(escape_json "$WEBVIEW")"
    printf '  },\n'
    printf '  "installedRelease": "%s",\n' "$(escape_json "$RELEASE_JSON")"
    printf '  "installedDebug": "%s"\n' "$(escape_json "$DEBUG_JSON")"
    printf '}\n'
  } > "$TEMP_FILE"
  mv "$TEMP_FILE" "$OUTPUT"
  echo "Wrote $OUTPUT"
fi
echo "Connected-device smoke prerequisites passed."
