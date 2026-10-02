#!/usr/bin/env python3
"""Stage the selected, currently published happ packages for an OIV APK build."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DOWNLOADS = ROOT.parent / "haminnweb" / "public" / "downloads" / "happs"
DEFAULT_SELECTION = ROOT / "tools" / "oiv-happs.json"
DEFAULT_OUTPUT = ROOT / "app" / "build" / "oiv-package-assets"
WEB_ROOT = ROOT.parent / "haminnweb"
EXPECTED_WEB_ORIGIN = "git@github.com:zhyuzh3d/haminnweb.git"
HAPP_ID = re.compile(r"[A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*\Z")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--downloads", type=Path, default=DEFAULT_DOWNLOADS)
    parser.add_argument("--selection", type=Path, default=DEFAULT_SELECTION)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    try:
        web_git_root = Path(subprocess.check_output(
            ["git", "-C", str(WEB_ROOT), "rev-parse", "--show-toplevel"], text=True
        ).strip()).resolve()
        web_origin = subprocess.check_output(
            ["git", "-C", str(WEB_ROOT), "remote", "get-url", "origin"], text=True
        ).strip()
        web_downloads = (WEB_ROOT / "public" / "downloads" / "happs").resolve()
        changes = subprocess.check_output(
            ["git", "-C", str(WEB_ROOT), "status", "--porcelain", "--", "public/downloads/happs"], text=True
        ).strip()
    except (OSError, subprocess.CalledProcessError) as error:
        fail(f"cannot verify HaminnWeb happ release source: {error}")
    if web_git_root != WEB_ROOT.resolve() or web_origin != EXPECTED_WEB_ORIGIN:
        fail(f"unexpected HaminnWeb repository identity: {web_git_root} / {web_origin}")
    if args.downloads.resolve() != web_downloads:
        fail("OIV inputs must come from haminnweb/public/downloads/happs")
    if changes:
        fail("HaminnWeb happ downloads contain uncommitted changes; publish and commit them first")

    try:
        selection = json.loads(args.selection.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read selection file {args.selection}: {error}")
    if not isinstance(selection, dict) or selection.get("schema") != 1 or not isinstance(selection.get("apps"), list):
        fail("selection file must use schema 1 and include an apps array")
    if not 1 <= len(selection["apps"]) <= 32:
        fail("selection must contain between 1 and 32 happs")

    destination = args.output.resolve()
    if destination == ROOT or ROOT not in destination.parents:
        fail("output must be inside the HaminnApp repository")
    if destination.exists():
        shutil.rmtree(destination)
    packages_dir = destination / "oiv" / "happs"
    packages_dir.mkdir(parents=True)

    app_entries: list[dict[str, object]] = []
    seen_ids: set[str] = set()
    try:
        for selected in selection["apps"]:
            if not isinstance(selected, dict):
                fail("each selection entry must be an object")
            happ_id = str(selected.get("happId", "")).strip()
            if not HAPP_ID.fullmatch(happ_id) or happ_id in seen_ids:
                fail(f"invalid or duplicate happId: {happ_id!r}")
            seen_ids.add(happ_id)

            app_dir = args.downloads / happ_id
            descriptor = json.loads((app_dir / "haminn-install.json").read_text(encoding="utf-8"))
            package_name = descriptor.get("package")
            declared_hash = str(descriptor.get("sha256", "")).lower()
            if descriptor.get("schema") != 1 or not isinstance(package_name, str):
                fail(f"invalid official install descriptor for {happ_id}")
            if Path(package_name).name != package_name or not SHA256.fullmatch(declared_hash):
                fail(f"unsafe package name or invalid hash for {happ_id}")

            package = app_dir / package_name
            payload = package.read_bytes()
            actual_hash = hashlib.sha256(payload).hexdigest()
            if actual_hash != declared_hash:
                fail(f"published ZIP hash mismatch for {happ_id}: {package}")
            with zipfile.ZipFile(package) as archive:
                metadata = json.loads(archive.read("haminn.json"))
            if metadata.get("happId") != happ_id:
                fail(f"package identity mismatch: expected {happ_id}, found {metadata.get('happId')}")
            if metadata.get("liveUrl"):
                fail(f"published package for {happ_id} unexpectedly contains a liveUrl")

            version = metadata.get("version") or {}
            app_name = str(metadata.get("name") or happ_id)
            packaged_name = f"{happ_id}.zip"
            (packages_dir / packaged_name).write_bytes(payload)
            app_entries.append({
                "happId": happ_id,
                "name": app_name,
                "package": f"happs/{packaged_name}",
                "sha256": actual_hash,
                "versionCode": version.get("code"),
                "versionName": version.get("name"),
            })
            print(f"staged {app_name} {version.get('name', '')}: {actual_hash}")
    except (OSError, KeyError, ValueError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        shutil.rmtree(destination, ignore_errors=True)
        fail(str(error))

    catalog = {"schema": 1, "apps": app_entries}
    (destination / "oiv" / "catalog.json").write_text(
        json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"wrote {destination / 'oiv' / 'catalog.json'} ({len(app_entries)} happs)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
