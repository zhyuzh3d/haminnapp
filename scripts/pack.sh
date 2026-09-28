#!/bin/sh
set -eu
if [ "$#" -ne 2 ]; then echo "usage: $0 WEB_ROOT OUTPUT.zip" >&2; exit 2; fi
SOURCE=$(CDPATH= cd -- "$1" && pwd)
OUTPUT=$2
test -f "$SOURCE/index.html" || { echo "index.html is required" >&2; exit 1; }
case "$OUTPUT" in /*) ;; *) OUTPUT=$(pwd)/$OUTPUT ;; esac
case "$OUTPUT" in "$SOURCE"/*) echo "Output ZIP must be outside the Web root" >&2; exit 2 ;; esac
OUTPUT_DIR=$(dirname -- "$OUTPUT")
mkdir -p "$OUTPUT_DIR"
TEMP_DIR=$(mktemp -d "$OUTPUT_DIR/.haminn-pack.XXXXXX")
trap 'find "$TEMP_DIR" -depth -delete' EXIT HUP INT TERM
(cd "$SOURCE" && find . -type f -print | LC_ALL=C sort | zip -X -q "$TEMP_DIR/package.zip" -@)
mv -f "$TEMP_DIR/package.zip" "$OUTPUT"
rmdir "$TEMP_DIR"
trap - EXIT HUP INT TERM
shasum -a 256 "$OUTPUT"
