#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
MODE=${1:-web}
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}

cd "$ROOT"
case "$MODE" in
  web)
    node --check app/src/main/assets/store/store.js
    node --test tools/contracts.test.mjs
    ;;
  android)
    ./gradlew :app:compileDebugKotlin
    ;;
  release)
    ./scripts/build-release.sh
    ;;
  *)
    echo "usage: $0 [web|android|release]" >&2
    exit 2
    ;;
esac
