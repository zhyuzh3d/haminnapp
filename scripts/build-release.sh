#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SIGNING_DIR=${HAMINN_SIGNING_DIR:-/Users/zhyuzh/.haminn-signing}
export HAMINN_KEYSTORE_PATH=${HAMINN_KEYSTORE_PATH:-$SIGNING_DIR/haminn-v1.keystore}
PASSWORD_FILE=${HAMINN_PASSWORD_FILE:-$SIGNING_DIR/haminn-v1.password}
test -f "$HAMINN_KEYSTORE_PATH" || { echo "Missing release keystore: $HAMINN_KEYSTORE_PATH" >&2; exit 1; }
test -f "$PASSWORD_FILE" || { echo "Missing release password file: $PASSWORD_FILE" >&2; exit 1; }
HAMINN_STORE_PASSWORD=$(tr -d '\r\n' < "$PASSWORD_FILE")
export HAMINN_STORE_PASSWORD
export HAMINN_KEY_PASSWORD=$HAMINN_STORE_PASSWORD
export HAMINN_KEY_ALIAS=${HAMINN_KEY_ALIAS:-haminn-v1}
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
VERSION_NAME=${HAMINN_VERSION_NAME:-$(sed -n 's/.*versionName.*?: "\([^"]*\)"/\1/p' "$ROOT/app/build.gradle.kts" | head -n 1)}
VERSION_CODE=${HAMINN_VERSION_CODE:-$(sed -n 's/.*versionCode.*?: \([0-9][0-9]*\)/\1/p' "$ROOT/app/build.gradle.kts" | head -n 1)}
test -n "$VERSION_NAME" || { echo "Cannot determine versionName" >&2; exit 1; }
test -n "$VERSION_CODE" || { echo "Cannot determine versionCode" >&2; exit 1; }
cd "$ROOT"
"$ROOT/gradlew" :app:assembleRelease -PhaminnVersionName="$VERSION_NAME" -PhaminnVersionCode="$VERSION_CODE"
