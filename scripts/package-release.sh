#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
VERSION=${1:-$(sed -n 's/.*versionName.*?: "\([^"]*\)"/\1/p' "$ROOT/app/build.gradle.kts" | head -n 1)}
OUT=$ROOT/artifacts/v$VERSION
SDK_DIR=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export PATH="$JAVA_HOME/bin:$PATH"
AAPT=$SDK_DIR/build-tools/37.0.0/aapt
APKSIGNER=$SDK_DIR/build-tools/37.0.0/apksigner
RELEASE_SOURCE=$ROOT/app/build/outputs/apk/release/app-release.apk

test -x "$AAPT"
test -x "$APKSIGNER"
test -f "$RELEASE_SOURCE"
test -z "$(git -C "$ROOT" status --porcelain --untracked-files=normal)" || {
  echo "Refusing to package a release from a dirty source tree" >&2
  exit 1
}
test ! -e "$OUT" || { echo "Refusing to overwrite versioned release directory: $OUT" >&2; exit 1; }

BADGING=$($AAPT dump badging "$RELEASE_SOURCE")
printf '%s\n' "$BADGING" | grep -q "package: name='life.airen.haminn'"
printf '%s\n' "$BADGING" | grep -q "versionName='$VERSION'"
VERSION_CODE=$(printf '%s\n' "$BADGING" | sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" | head -n 1)
CERT_SHA256=$($APKSIGNER verify --print-certs "$RELEASE_SOURCE" | sed -n 's/^.*certificate SHA-256 digest: //p' | head -n 1 | tr '[:lower:]' '[:upper:]')
test -n "$VERSION_CODE"
test -n "$CERT_SHA256"

mkdir -p "$OUT"
cp "$RELEASE_SOURCE" "$OUT/haminn-v$VERSION-release.apk"
cp "$ROOT/app/src/main/assets/third-party-notices.txt" "$OUT/dependency-licenses.txt"
cp "$ROOT/app/src/main/assets/shared/fontawesome/LICENSE.txt" "$OUT/fontawesome-license.txt"
cp "$ROOT/docs/validation/validation-report.md" "$OUT/validation-report.md"
cp "$ROOT/docs/validation/known-limitations.md" "$OUT/known-limitations.md"

SOURCE_COMMIT=$(git -C "$ROOT" rev-parse HEAD)
RELEASE_APK=$OUT/haminn-v$VERSION-release.apk
RELEASE_SHA=$(shasum -a 256 "$RELEASE_APK" | awk '{print $1}')
RELEASE_SIZE=$(stat -f '%z' "$RELEASE_APK")
BUILT_AT=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
JAVA_VERSION=$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p')

export ROOT OUT VERSION VERSION_CODE CERT_SHA256 SOURCE_COMMIT RELEASE_SHA RELEASE_SIZE BUILT_AT JAVA_VERSION
node <<'NODE'
const fs = require('fs');
const path = require('path');
const e = process.env;
const manifest = {
  schema: 1,
  product: 'Haminn',
  builtAt: e.BUILT_AT,
  sourceCommit: e.SOURCE_COMMIT,
  sourceTreeClean: true,
  applicationId: 'life.airen.haminn',
  versionName: e.VERSION,
  versionCode: Number(e.VERSION_CODE),
  sdk: { min: 29, target: 37, compile: 37 },
  toolchain: { jdk: e.JAVA_VERSION, gradle: '9.3.1', agp: '9.1.1', buildTools: '37.0.0' },
  signingCertificateSha256: e.CERT_SHA256,
  artifacts: [
    { file: `haminn-v${e.VERSION}-release.apk`, sha256: e.RELEASE_SHA, bytes: Number(e.RELEASE_SIZE) },
  ],
};
fs.writeFileSync(path.join(e.OUT, 'release-manifest.json'), JSON.stringify(manifest, null, 2) + '\n');

const lock = fs.readFileSync(path.join(e.ROOT, 'app', 'gradle.lockfile'), 'utf8');
const components = [];
for (const line of lock.split(/\r?\n/)) {
  if (!line.includes('releaseRuntimeClasspath') || line.startsWith('#') || line.startsWith('empty=')) continue;
  const coordinate = line.slice(0, line.indexOf('='));
  const parts = coordinate.split(':');
  if (parts.length !== 3) continue;
  const [group, name, version] = parts;
  components.push({
    type: 'library', group, name, version,
    'bom-ref': `pkg:maven/${group}/${name}@${version}`,
    purl: `pkg:maven/${group}/${name}@${version}`,
  });
}
const webPackage = JSON.parse(fs.readFileSync(path.join(e.ROOT, 'package.json'), 'utf8'));
const iconVersion = webPackage.dependencies['@fortawesome/fontawesome-free'];
components.push({
  type: 'library', group: '@fortawesome', name: 'fontawesome-free', version: iconVersion,
  'bom-ref': `pkg:npm/%40fortawesome/fontawesome-free@${iconVersion}`,
  purl: `pkg:npm/%40fortawesome/fontawesome-free@${iconVersion}`,
  licenses: [{ license: { id: 'CC-BY-4.0' } }, { license: { id: 'OFL-1.1' } }, { license: { id: 'MIT' } }],
});
components.sort((a, b) => a['bom-ref'].localeCompare(b['bom-ref']));
const sbom = {
  bomFormat: 'CycloneDX', specVersion: '1.5', version: 1,
  metadata: {
    timestamp: e.BUILT_AT,
    component: { type: 'application', name: 'Haminn', version: e.VERSION, 'bom-ref': `pkg:generic/life.airen.haminn@${e.VERSION}`, purl: `pkg:generic/life.airen.haminn@${e.VERSION}` },
    properties: [{ name: 'haminn:sourceCommit', value: e.SOURCE_COMMIT }],
  },
  components,
};
fs.writeFileSync(path.join(e.OUT, 'sbom.json'), JSON.stringify(sbom, null, 2) + '\n');
NODE

(
  cd "$OUT"
  shasum -a 256 "haminn-v$VERSION-release.apk" release-manifest.json sbom.json dependency-licenses.txt fontawesome-license.txt validation-report.md known-limitations.md > SHA256SUMS
)

echo "Packaged $OUT"
