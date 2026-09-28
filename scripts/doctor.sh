#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
JAVA_BIN=$JAVA_HOME/bin/java
SDK_DIR=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
echo "workspace=$ROOT"
"$JAVA_BIN" -version
"$ROOT/gradlew" --version
node --version
npm --version
"$SDK_DIR/platform-tools/adb" version
test -f "$SDK_DIR/platforms/android-37.0/android.jar"
test -x "$SDK_DIR/build-tools/37.0.0/apksigner"
echo "Haminn build environment is ready."
