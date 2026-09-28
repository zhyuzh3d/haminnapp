#!/bin/sh
set -eu
if [ "$#" -ne 2 ]; then echo "usage: HAMINN_TOKEN=... $0 APP_ID PACKAGE.zip" >&2; exit 2; fi
APP_ID=$1
PACKAGE=$2
: "${HAMINN_TOKEN:?Set HAMINN_TOKEN from the active Haminn developer panel}"
printf '%s' "$HAMINN_TOKEN" | grep -Eq '^[A-Za-z0-9_-]{40,100}={0,2}$' || { echo "HAMINN_TOKEN has an invalid format" >&2; exit 2; }
ADB=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb
ADDRESS=${HAMINN_ADDRESS:-http://127.0.0.1:8765}
case "$ADDRESS" in
  http://127.0.0.1:8765)
    "$ADB" forward tcp:8765 tcp:8765 >/dev/null
    CURL_TLS=
    PROTO='=http'
    ;;
  https://*)
    : "${HAMINN_SPKI_PIN:?Set HAMINN_SPKI_PIN for LAN TLS mode}"
    curl --help all | grep -q -- '--pinnedpubkey' || { echo "This curl build does not support --pinnedpubkey" >&2; exit 2; }
    CURL_TLS=1
    PROTO='=https'
    ;;
  *) echo "Unsupported HAMINN_ADDRESS" >&2; exit 2 ;;
esac
run_curl() {
  if [ -n "$CURL_TLS" ]; then
    curl --fail --silent --show-error --proto "$PROTO" --insecure --pinnedpubkey "$HAMINN_SPKI_PIN" --config - "$@"
  else
    curl --fail --silent --show-error --proto "$PROTO" --config - "$@"
  fi <<EOF
header = "Authorization: Bearer $HAMINN_TOKEN"
EOF
}
STATUS=$(run_curl --max-time 10 "$ADDRESS/v1/status")
EXPECTED=$(printf '%s' "$STATUS" | sed -n 's/.*"activeReleaseId":"\([^"]*\)".*/\1/p')
SHA=$(shasum -a 256 "$PACKAGE" | awk '{print $1}')
KEY="deploy-$(date +%s)-$$"
DEPLOY_RESULT=$(run_curl --max-time 180 --request PUT --upload-file "$PACKAGE" \
  --header 'Content-Type: application/zip' --header "Idempotency-Key: $KEY" \
  --header "X-Haminn-Expected-Release: $EXPECTED" --header "X-Haminn-Content-SHA256: $SHA" "$ADDRESS/v1/apps/$APP_ID/release")
RELOAD_RESULT=$(run_curl --max-time 10 --request POST --header 'Content-Length: 0' "$ADDRESS/v1/apps/$APP_ID/reload" || true)
printf '%s\n' "$DEPLOY_RESULT"
if [ -n "$RELOAD_RESULT" ]; then printf '%s\n' "$RELOAD_RESULT"; else printf '%s\n' '{"reload":"not-visible"}'; fi
