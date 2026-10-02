#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
VERSION=${1:?usage: package-oiv-release.sh <haminn-version>}
SDK_DIR=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export PATH="$JAVA_HOME/bin:$PATH"
AAPT=$SDK_DIR/build-tools/37.0.0/aapt
APKSIGNER=$SDK_DIR/build-tools/37.0.0/apksigner
RELEASE_SOURCE=$ROOT/app/build/outputs/apk/release/app-release.apk
OUT=$ROOT/artifacts/v$VERSION
STANDARD_APK=$OUT/haminn-v$VERSION-release.apk
OIV_APK=$OUT/haminn-v$VERSION-oiv-release.apk
OIV_CATALOG=$ROOT/app/build/oiv-package-assets/oiv/catalog.json
STAGE_DIR=
cleanup() { [ -z "$STAGE_DIR" ] || rm -rf "$STAGE_DIR"; }
trap cleanup EXIT HUP INT TERM

test -x "$AAPT"
test -x "$APKSIGNER"
test -f "$RELEASE_SOURCE"
test -f "$STANDARD_APK"
test -f "$OUT/release-manifest.json"
test -f "$OIV_CATALOG"
test ! -e "$OIV_APK" || { echo "Refusing to overwrite OIV artifact: $OIV_APK" >&2; exit 1; }
test -z "$(git -C "$ROOT" status --porcelain --untracked-files=normal)" || {
  echo "Refusing to package OIV from a dirty source tree" >&2
  exit 1
}

BADGING=$("$AAPT" dump badging "$RELEASE_SOURCE")
printf '%s\n' "$BADGING" | grep -q "package: name='life.airen.haminn'"
printf '%s\n' "$BADGING" | grep -q "versionName='$VERSION'"
VERSION_CODE=$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" | head -n 1)
CERT_SHA256=$("$APKSIGNER" verify --print-certs "$RELEASE_SOURCE" | sed -n 's/^.*certificate SHA-256 digest: //p' | head -n 1 | tr '[:lower:]' '[:upper:]')
STANDARD_CERT=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["signingCertificateSha256"])' "$OUT/release-manifest.json")
test "$CERT_SHA256" = "$STANDARD_CERT" || { echo "OIV signer differs from standard APK" >&2; exit 1; }

STAGE_DIR=$(mktemp -d "$OUT/.oiv-stage.XXXXXX")
TEMP_APK=$STAGE_DIR/haminn-v$VERSION-oiv-release.apk
cp "$RELEASE_SOURCE" "$TEMP_APK"
python3 - "$TEMP_APK" "$OIV_CATALOG" <<'PY'
import hashlib
import json
import sys
import zipfile

apk_path, catalog_path = sys.argv[1:]
catalog = json.load(open(catalog_path, encoding="utf-8"))
with zipfile.ZipFile(apk_path) as apk:
    catalog_data = apk.read("assets/oiv/catalog.json")
    embedded_catalog = json.loads(catalog_data)
    if embedded_catalog != catalog:
        raise SystemExit("OIV catalog in APK differs from staged catalog")
    for app in catalog["apps"]:
        asset_path = "assets/oiv/" + app["package"]
        payload = apk.read(asset_path)
        if hashlib.sha256(payload).hexdigest() != app["sha256"]:
            raise SystemExit(f"OIV package hash mismatch: {app['happId']}")
        if not zipfile.is_zipfile(__import__("io").BytesIO(payload)):
            raise SystemExit(f"OIV payload is not a ZIP: {app['happId']}")
PY

OIV_BADGING=$("$AAPT" dump badging "$TEMP_APK")
printf '%s\n' "$OIV_BADGING" | grep -q "package: name='life.airen.haminn'"
printf '%s\n' "$OIV_BADGING" | grep -q "versionName='$VERSION'"
OIV_CODE=$(printf '%s\n' "$OIV_BADGING" | sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" | head -n 1)
test "$OIV_CODE" = "$VERSION_CODE" || { echo "OIV versionCode differs from standard APK" >&2; exit 1; }
"$APKSIGNER" verify --verbose --print-certs "$TEMP_APK" >/dev/null
OIV_CERT=$("$APKSIGNER" verify --print-certs "$TEMP_APK" | sed -n 's/^.*certificate SHA-256 digest: //p' | head -n 1 | tr '[:lower:]' '[:upper:]')
test "$OIV_CERT" = "$STANDARD_CERT" || { echo "OIV signing certificate differs from standard APK" >&2; exit 1; }

OIV_SHA=$(shasum -a 256 "$TEMP_APK" | awk '{print $1}')
OIV_SIZE=$(stat -f '%z' "$TEMP_APK")
SOURCE_COMMIT=$(git -C "$ROOT" rev-parse HEAD)
export VERSION VERSION_CODE OIV_SHA OIV_SIZE SOURCE_COMMIT STAGE_DIR OIV_CATALOG
python3 - <<'PY'
import json
import os
from pathlib import Path

apps = json.loads(Path(os.environ["OIV_CATALOG"]).read_text(encoding="utf-8"))["apps"]
manifest = {
    "schema": 1,
    "distribution": "Official Integrated Version",
    "versionName": os.environ["VERSION"],
    "versionCode": int(os.environ["VERSION_CODE"]),
    "applicationId": "life.airen.haminn",
    "file": f"haminn-v{os.environ['VERSION']}-oiv-release.apk",
    "bytes": int(os.environ["OIV_SIZE"]),
    "sha256": os.environ["OIV_SHA"],
    "sourceCommit": os.environ["SOURCE_COMMIT"],
    "apps": apps,
}
out = Path(os.environ["STAGE_DIR"])
(out / "oiv-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
(out / (manifest["file"] + ".sha256")).write_text(f"{manifest['sha256']}  {manifest['file']}\n", encoding="utf-8")
PY
mv "$TEMP_APK" "$OIV_APK"
mv "$STAGE_DIR/oiv-manifest.json" "$OUT/oiv-manifest.json"
mv "$STAGE_DIR/haminn-v$VERSION-oiv-release.apk.sha256" "$OUT/haminn-v$VERSION-oiv-release.apk.sha256"
echo "Packaged $OIV_APK"
