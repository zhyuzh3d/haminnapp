#!/usr/bin/env python3
"""Haminn LAN MCP helper. Python 3.10+, standard library only; never prints passwords."""
import argparse
import getpass
import glob
import hashlib
import http.client
import ipaddress
import io
import json
import os
from pathlib import Path
import platform
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import urllib.parse
import uuid
import zipfile

MAX_REPLY = 8 * 1024 * 1024
PROTOCOL = "2025-11-25"
MAX_INCREMENTAL_TEXT_FILES = 16
MAX_INCREMENTAL_TEXT_BYTES = 2 * 1024 * 1024
MAX_INCREMENTAL_BINARY_BYTES = 8 * 1024 * 1024
MAX_INCREMENTAL_BINARY_FILES = 8
IGNORE = {".git", ".svn", "node_modules", "__pycache__", ".DS_Store", ".idea", ".vscode", ".env", ".haminn"}
PLUGIN_ID = "haminn-dev-plugin"
LEGACY_PLUGIN_IDS = ("haminn-device",)
PLUGIN_IDS = (PLUGIN_ID,) + LEGACY_PLUGIN_IDS


def address(value):
    parsed = urllib.parse.urlsplit(value.rstrip("/"))
    if parsed.scheme != "http" or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment:
        raise ValueError("Use the exact HTTP base address displayed on the phone, without a path or password")
    ip = ipaddress.ip_address(parsed.hostname or "")
    if ip.version != 4 or not (ip.is_private or ip.is_loopback) or ip.is_unspecified or ip.is_multicast:
        raise ValueError("Only a trusted LAN IPv4 or local USB-forward address is supported")
    if not parsed.port:
        raise ValueError("Include the phone's displayed port")
    return parsed.geturl()


class Device:
    def __init__(self, base, password=None, config_root=None):
        self.base = address(base)
        parsed = urllib.parse.urlsplit(self.base)
        self.host = parsed.hostname
        self.port = parsed.port
        self._connection = None
        self.discovery_cache = None
        self.session_id = None
        root = Path(config_root or os.environ.get("HAMINN_CONFIG_HOME") or default_config_root())
        self.credential_file = root / (hashlib.sha256(self.base.encode()).hexdigest()[:24] + ".json")
        self.password = password or os.environ.get("HAMINN_PASSWORD")
        if self.password is None and self.credential_file.exists():
            if self.credential_file.is_symlink() or os.name != "nt" and self.credential_file.stat().st_mode & 0o077:
                raise RuntimeError("Credential file must be private (chmod 600) and not a symlink")
            self.password = json.loads(self.credential_file.read_text())["password"]

    def close(self):
        if self._connection is not None:
            try:
                self._connection.close()
            finally:
                self._connection = None

    def _connection_or_open(self):
        if self._connection is None:
            self._connection = http.client.HTTPConnection(self.host, self.port, timeout=60)
        return self._connection

    def request_target(self, value):
        """Resolve a relative path or an absolute URL from this exact device origin."""
        if not isinstance(value, str) or not value:
            raise ValueError("Device URL must be a non-empty string")
        parsed = urllib.parse.urlsplit(value)
        if parsed.scheme or parsed.netloc:
            base = urllib.parse.urlsplit(self.base)
            if (parsed.scheme != base.scheme or parsed.hostname != base.hostname or
                    parsed.port != base.port or parsed.username or parsed.password or
                    parsed.fragment):
                raise ValueError("Refusing a package URL from another origin")
            path = parsed.path or "/"
            if not path.startswith("/") or path.startswith("//"):
                raise ValueError("Device URL must contain an absolute path")
            return urllib.parse.urlunsplit(("", "", path, parsed.query, ""))
        if not value.startswith("/") or value.startswith("//"):
            raise ValueError("Only device-relative paths or same-origin HTTP URLs are accepted")
        return value

    def discover(self):
        value = json.loads(self.request("/.well-known/haminn-agent", authenticated=False))
        if not isinstance(value, dict) or not value.get("runId") or not value.get("schemaDigest"):
            raise RuntimeError("Device discovery response is incomplete")
        self.discovery_cache = value
        return value

    def bootstrap(self):
        value = json.loads(self.request("/", authenticated=False, headers={"Accept": "application/json"}))
        if not isinstance(value, dict) or value.get("kind") != "haminn-agent-bootstrap":
            raise RuntimeError("该地址不是可识别的 Haminn 智能体开发服务")
        return value

    def request(self, path, method="GET", data=None, headers=None, authenticated=True):
        target = self.request_target(path)
        fields = {"Accept": "application/json, text/event-stream", "MCP-Protocol-Version": PROTOCOL, "Connection": "keep-alive"}
        fields.update({str(key): str(value) for key, value in (headers or {}).items()})
        if authenticated:
            if not self.password or len(self.password) != 6 or not self.password.isascii() or not all(ch.isalnum() for ch in self.password):
                raise RuntimeError("Run connect to enter the current six-character password privately")
            fields["Authorization"] = "Bearer " + self.password
        try:
            connection = self._connection_or_open()
            connection.request(method, target, body=data, headers=fields)
            response = connection.getresponse()
            body = response.read(MAX_REPLY + 1)
            status = response.status
            will_close = response.will_close
            if len(body) > MAX_REPLY:
                self.close()
                raise RuntimeError("Device response exceeds the safety limit")
            if status in (301, 302, 303, 307, 308):
                self.close()
                raise RuntimeError("Redirect refused: credentials must remain on the selected device")
            if will_close or status >= 400:
                self.close()
            if status < 400:
                return body
            if status == 401:
                raise RuntimeError("Haminn 开发密码无效或已改变。请在手机打开 Haminn 应用，在开发配置中查看并提供当前开发密码。") from None
            if status == 429:
                raise RuntimeError("Haminn 开发服务已暂时锁定当前地址，请等待 " + response.getheader("Retry-After", "60") + " 秒后再试") from None
            if status == 409:
                raise RuntimeError("Version conflict or concurrent write: inspect current app/release before retrying") from None
            raise RuntimeError("Device HTTP error " + str(status)) from None
        except (OSError, http.client.HTTPException) as exc:
            self.close()
            raise RuntimeError("无法连接旧的 Haminn 开发服务地址。请在手机打开 Haminn 应用，在开发配置中查看并提供当前开发服务地址。") from None

    def rpc(self, method, params=None, request_id=1):
        message = {"jsonrpc": "2.0", "method": method}
        if request_id is not None:
            message["id"] = request_id
        if params is not None:
            message["params"] = params
        data = self.request("/mcp", "POST", json.dumps(message, ensure_ascii=False).encode(), {"Content-Type": "application/json"})
        if not data:
            return None
        response = json.loads(data)
        if "error" in response:
            raise RuntimeError("MCP error: " + json.dumps(response["error"]))
        return response["result"]

    def tool(self, name, arguments=None):
        result = self.rpc("tools/call", {"name": name, "arguments": arguments or {}})
        if result.get("isError"):
            raise RuntimeError("Tool failed: " + json.dumps(result.get("structuredContent", {}), ensure_ascii=False))
        return result["structuredContent"]

    def initialize(self):
        result = self.rpc("initialize", {"protocolVersion": PROTOCOL, "capabilities": {}, "clientInfo": {"name": "haminn-python", "version": "1.2.0"}})
        self.rpc("notifications/initialized", request_id=None)
        session = self.tool("haminn_open_agent_session")
        self.session_id = session.get("sessionId")
        return result

    def save_password(self):
        root = self.credential_file.parent
        root.mkdir(mode=0o700, parents=True, exist_ok=True)
        if root.is_symlink() or os.name != "nt" and root.stat().st_mode & 0o077:
            raise RuntimeError("Credential directory must be private (chmod 700)")
        fd, temporary = tempfile.mkstemp(prefix=".credential-", dir=root)
        try:
            with os.fdopen(fd, "w") as out:
                json.dump({"address": self.base, "password": self.password}, out)
                out.flush()
                os.fsync(out.fileno())
            os.replace(temporary, self.credential_file)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)


def default_config_root():
    if os.name == "nt":
        return Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local")) / "HaminnAgent"
    return Path.home() / ".config/haminn-agent"


HOST_CAPABILITIES = (
    ("python3", "Run the Haminn device helper itself"),
    ("git", "Inspect repository state on the host"),
    ("node", "Run happ checks and release packaging on the host"),
    ("adb", "Install or update the Android host app on a device"),
    ("java", "Build the Android host app"),
    ("mutagen", "Publish HaminnUI or a static site to a server"),
)


def _first_meaningful_line(text):
    for line in (text or "").splitlines():
        line = line.strip()
        if line:
            return line[:120]
    return None


def host_bin_dirs():
    """Conventional install locations to search after PATH.

    These are platform conventions and environment variables, never a specific machine's layout.
    A developer may legitimately have a tool installed without it being on a non-interactive PATH.
    """
    home = Path.home()
    dirs = [Path("/opt/homebrew/bin"), Path("/usr/local/bin"), home / ".local/bin", home / "bin"]
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(variable)
        if value:
            dirs.extend([Path(value) / "platform-tools", Path(value) / "cmdline-tools/latest/bin"])
    dirs.extend([
        home / "Library/Android/sdk/platform-tools",
        home / "Android/Sdk/platform-tools",
        home / "AppData/Local/Android/Sdk/platform-tools",
    ])
    for pattern in ("/opt/homebrew/opt/openjdk*/bin", "/usr/local/opt/openjdk*/bin", "/usr/lib/jvm/*/bin",
                    "/Library/Java/JavaVirtualMachines/*/Contents/Home/bin"):
        dirs.extend(sorted(Path(match) for match in glob.glob(pattern)))
    return [directory for directory in dirs if directory.is_dir()]


def host_command_candidates(name):
    """Yield (path, source) for a command name, PATH first, then conventional locations."""
    suffixes = ("", ".exe", ".cmd", ".bat") if os.name == "nt" else ("",)
    seen = []
    found = shutil.which(name)
    if found:
        seen.append(Path(found))
        yield Path(found), "PATH"
    for directory in host_bin_dirs():
        for suffix in suffixes:
            candidate = directory / (name + suffix)
            if candidate in seen:
                continue
            if candidate.is_file() and (os.name == "nt" or os.access(candidate, os.X_OK)):
                seen.append(candidate)
                yield candidate, str(directory)


def _host_command_version(path):
    try:
        done = subprocess.run([str(path), "--version"], capture_output=True, text=True, timeout=15)
    except (OSError, subprocess.SubprocessError):
        return None, False
    if done.returncode != 0:
        return None, False
    return (_first_meaningful_line(done.stdout) or _first_meaningful_line(done.stderr)), True


def host_environment(config_root=None):
    """Probe a fixed, small capability list for the host machine and cache the ledger locally.

    This is deliberately bounded: it never installs anything, never walks the filesystem and never
    contacts the device. It exists so an agent can read what the host already has instead of
    searching for it, and so a missing prerequisite becomes a message for the developer.
    """
    root = Path(config_root or os.environ.get("HAMINN_CONFIG_HOME") or default_config_root())
    capabilities = []
    for name, purpose in HOST_CAPABILITIES:
        entry = {"name": name, "purpose": purpose, "present": False}
        unusable, skipped = False, []
        for path, source in host_command_candidates(name):
            version, usable = _host_command_version(path)
            if usable:
                entry.update(present=True, path=str(path), resolvedVia=source, version=version)
                break
            unusable = True
            skipped.append(str(path))
        if not entry["present"] and unusable:
            entry.update(present=True, path=skipped[0], version=None,
                         note="found, but --version failed; confirm with the developer before relying on it")
        if skipped:
            entry["skipped"] = skipped
        capabilities.append(entry)
    ledger = {
        "schema": 1,
        "generatedAt": int(time.time() * 1000),
        "platform": platform.platform(),
        "interpreter": sys.executable,
        "pythonVersion": platform.python_version(),
        "capabilities": capabilities,
        "note": "Search order: PATH, then conventional developer install locations, plus ANDROID_HOME/ANDROID_SDK_ROOT. present=false means not found in those places, not proof the developer lacks it: report the gap and ask. Nothing is installed automatically.",
    }
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    ledger_file = root / "host-environment.json"
    ledger_file.write_text(json.dumps(ledger, indent=2, ensure_ascii=False) + "\n")
    ledger["ledgerFile"] = str(ledger_file)
    return ledger


def source_files(directory, roots=None):
    root = Path(directory).resolve(strict=True)
    if not root.is_dir():
        raise ValueError("Source must be a directory")
    result = []
    total = 0
    selected = [root] if roots is None else [root / name for name in roots]
    for selected_root in selected:
        if selected_root.is_file():
            walks = [(str(selected_root.parent), [], [selected_root.name])]
        elif selected_root.is_dir() and not selected_root.is_symlink():
            walks = os.walk(selected_root, followlinks=False)
        else:
            raise ValueError("Development source path is missing or unsafe: " + str(selected_root.relative_to(root)))
        for current, dirs, files in walks:
            dirs[:] = sorted(d for d in dirs if d not in IGNORE and not Path(current, d).is_symlink())
            for name in sorted(files):
                file = Path(current, name)
                relative = file.relative_to(root).as_posix()
                if name in IGNORE or name.startswith(".env.") or name.lower().endswith((".pem", ".key", ".keystore", ".jks")):
                    continue
                if file.is_symlink() or not file.is_file():
                    raise ValueError("Symlinks and special files are not deployable")
                if len(relative) > 240 or any(part in ("..", ".", "__haminn") for part in relative.split("/")) or ":" in relative or "\\" in relative:
                    raise ValueError("Unsafe source path")
                total += file.stat().st_size
                if total > 256 * 1024 * 1024 or len(result) >= 10000:
                    raise ValueError("Source exceeds file or expanded-size limits")
                result.append((relative, file))
    if not result:
        raise ValueError("Source directory is empty")
    return sorted(result)


def development_files(directory):
    root = Path(directory).resolve(strict=True)
    install = root / "haminn-install.json"
    try:
        descriptor = json.loads(install.read_text(encoding="utf-8"))
        package_name = descriptor["package"]
        package = (root / package_name).resolve(strict=True)
        if root not in package.parents or package.suffix.lower() != ".zip":
            raise ValueError("package path")
        with zipfile.ZipFile(package) as archive:
            names = [name.strip("/") for name in archive.namelist() if name.strip("/")]
        roots = sorted({name.split("/", 1)[0] for name in names})
        if "haminn.json" not in roots or not roots:
            raise ValueError("package scope")
        return source_files(root, roots)
    except (OSError, UnicodeError, KeyError, TypeError, ValueError, json.JSONDecodeError, zipfile.BadZipFile):
        return source_files(root)


def development_signature(directory, files=None):
    """Return a cheap change signature; content hashes are deferred until a save settles.

    The watcher keeps the selected file list between polls. Directory mtimes
    still reveal additions/deletions, so the list is rebuilt only after a
    possible save instead of reopening haminn-install.json/ZIP every 100 ms.
    """
    root = Path(directory).resolve(strict=True)
    files = development_files(root) if files is None else files
    directories = {root}
    for _, path in files:
        current = path.parent
        while current != root and root in current.parents:
            directories.add(current)
            current = current.parent
    signature = []
    for path in sorted(directories, key=lambda item: str(item)):
        try:
            signature.append(("d", str(path.relative_to(root)), path.stat().st_mtime_ns))
        except FileNotFoundError:
            signature.append(("d", str(path.relative_to(root)), None))
    for name, path in files:
        try:
            metadata = path.stat()
            signature.append(("f", name, metadata.st_mtime_ns, metadata.st_size))
        except FileNotFoundError:
            signature.append(("f", name, None, None))
    return signature


def archive_source(directory, files=None):
    files = source_files(directory) if files is None else files
    archive = tempfile.TemporaryFile()
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as zipped:
        for name, path in files:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            with path.open("rb") as source, zipped.open(info, "w") as target:
                while chunk := source.read(65536):
                    target.write(chunk)
    if archive.tell() > 64 * 1024 * 1024:
        archive.close()
        raise ValueError("ZIP exceeds 64 MiB")
    archive.seek(0)
    return archive


def upload_prepared(device, prepared, data, refresh_mode="auto"):
    url = urllib.parse.urlsplit(prepared["url"])
    if url.scheme + "://" + url.netloc != device.base:
        raise RuntimeError("Device returned an unexpected upload origin")
    headers = dict(prepared["headers"])
    headers["X-Haminn-Refresh-Mode"] = refresh_mode
    return json.loads(device.request(url.path + (("?" + url.query) if url.query else ""), "PUT", data, headers))


def replace_dev_tree(device, app_id, directory, expected_revision, refresh_mode="auto", files=None, force=False):
    with archive_source(directory, development_files(directory) if files is None else files) as archive:
        data = archive.read()
    request_id = str(uuid.uuid4())
    prepared = device.tool("haminn_replace_dev_tree", {
        "appId": app_id, "expectedDevRevision": expected_revision, "requestId": request_id, "force": force,
        "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()
    })
    return upload_prepared(device, prepared, data, refresh_mode)


def ensure_dev_target(device, app_id):
    current = device.tool("haminn_runtime_status")
    if current.get("appId") == app_id and current.get("launchChannel") == "dev":
        return current
    return device.tool("haminn_enter_dev_mode", {"appId": app_id, "requestId": str(uuid.uuid4())})


def remote_workspace(device, app_id, workspace_cache=None):
    if workspace_cache and workspace_cache.get("appId") == app_id and workspace_cache.get("revision"):
        return workspace_cache
    try:
        remote_state = device.tool("haminn_get_happ_dev_status", {"appId": app_id})
    except Exception:
        # Compatibility with an older enabled phone; new servers never enumerate here.
        remote_state = device.tool("haminn_list_dev_files", {"appId": app_id})
        files = {item["path"]: item for item in remote_state.get("files", [])}
    else:
        files = {}
    state = {
        "appId": app_id,
        "revision": remote_state["revision"],
        "treeHash": remote_state["treeHash"],
        # The normal path intentionally does not download or enumerate the device tree.
        # The local manifest becomes authoritative after the first explicit client upload.
        "files": files,
    }
    if workspace_cache is not None:
        workspace_cache.clear()
        workspace_cache.update(state)
        return workspace_cache
    return state


def local_workspace_state(app_id, files, revision, tree_hash=None):
    entries = {}
    for name, path in files:
        data = path.read_bytes()
        entries[name] = {"path": name, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
    return {"appId": app_id, "revision": revision, "treeHash": tree_hash, "files": entries}


def apply_remote_result(state, result, local_hashes, changes):
    files = state["files"]
    for change in changes:
        if change.get("move"):
            moved = files.pop(change["from"], None)
            if moved is not None:
                files[change["to"]] = dict(moved, path=change["to"])
        elif change.get("delete"):
            files.pop(change["path"], None)
        elif change.get("path"):
            path = change["path"]
            digest = local_hashes[path]
            files[path] = {"path": path, "sha256": digest, "bytes": len(change.get("content", "").encode("utf-8"))}
    state["revision"] = result["revision"]
    state["treeHash"] = result.get("treeHash", state["treeHash"])


def download_dev_tree(device, app_id, output=None):
    result = device.tool("haminn_download_dev_tree", {"appId": app_id})
    url = urllib.parse.urlsplit(result["downloadUrl"])
    data = device.request(url.path + (("?" + url.query) if url.query else ""), authenticated=True)
    if hashlib.sha256(data).hexdigest().lower() != result["sha256"].lower():
        raise RuntimeError("Downloaded device development tree digest mismatch")
    if output is None:
        output = Path.cwd() / (PLUGIN_ID + "-" + app_id + ".zip")
    output = Path(output).resolve()
    output.write_bytes(data)
    return dict(result, savedTo=str(output))


def dev_sync(device, app_id, directory, ensure_target=True, hash_cache=None, workspace_cache=None):
    target = ensure_dev_target(device, app_id) if ensure_target else {}
    local = development_files(directory)
    remote_state = remote_workspace(device, app_id, workspace_cache)
    revision = remote_state["revision"]
    remote = remote_state["files"]
    local_hashes = {}
    text_changes = []
    binary = []
    total_inline = 0
    for name, path in local:
        metadata = path.stat()
        cached = hash_cache.get(name) if hash_cache is not None else None
        digest = cached[3] if cached and cached[:3] == (metadata.st_mtime_ns, metadata.st_size, metadata.st_ino) else None
        if digest is not None and remote.get(name, {}).get("sha256") == digest:
            local_hashes[name] = digest
            continue
        data = path.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        if hash_cache is not None:
            hash_cache[name] = (metadata.st_mtime_ns, metadata.st_size, metadata.st_ino, digest)
        local_hashes[name] = digest
        if remote.get(name, {}).get("sha256") == digest:
            continue
        try:
            content = data.decode("utf-8")
            is_text = len(data) <= 512 * 1024 and b"\0" not in data
        except UnicodeDecodeError:
            content, is_text = None, False
        if is_text:
            text_changes.append({"path": name, "content": content})
            total_inline += len(data)
        else:
            binary.append((name, data, digest))
    removed = set(remote) - set(local_hashes)
    removed_by_hash = {}
    for name in removed:
        removed_by_hash.setdefault(remote[name].get("sha256"), []).append(name)
    moved = set()
    for name, digest in sorted(local_hashes.items()):
        candidates = removed_by_hash.get(digest, [])
        if name not in remote and len(candidates) == 1 and candidates[0] not in moved:
            source = candidates[0]
            text_changes.append({"from": source, "to": name, "move": True})
            moved.add(source)
            text_changes[:] = [change for change in text_changes if change.get("path") != name]
            binary[:] = [entry for entry in binary if entry[0] != name]
    for name in sorted(removed - moved):
        text_changes.append({"path": name, "delete": True})

    if not text_changes and not binary:
        result = {"appId": app_id, "revision": revision, "treeHash": remote_state["treeHash"],
                  "changedPaths": [], "refreshState": "unchanged"}
        if target.get("renderOperationId"):
            result["renderOperationId"] = target["renderOperationId"]
        return result
    binary_bytes = sum(len(data) for _, data, _ in binary)
    if (len(text_changes) > MAX_INCREMENTAL_TEXT_FILES or total_inline > MAX_INCREMENTAL_TEXT_BYTES
            or len(binary) > MAX_INCREMENTAL_BINARY_FILES
            or binary_bytes > MAX_INCREMENTAL_BINARY_BYTES):
        result = replace_dev_tree(device, app_id, directory, revision, files=local, force=True)
        if workspace_cache is not None:
            workspace_cache.clear()
            workspace_cache.update(local_workspace_state(app_id, local, result["revision"], result.get("treeHash")))
        return result

    result = None
    for index, (name, data, digest) in enumerate(binary):
        request_id = str(uuid.uuid4())
        prepared = device.tool("haminn_put_dev_file", {
            "appId": app_id, "expectedDevRevision": revision, "requestId": request_id,
            "path": name, "bytes": len(data), "sha256": digest,
            "contentType": "application/octet-stream"
        })
        final_operation = index == len(binary) - 1 and not text_changes
        result = upload_prepared(device, prepared, data, "auto" if final_operation else "none")
        revision = result["revision"]
        apply_remote_result(remote_state, result, {name: digest}, [{"path": name}])
    if text_changes:
        try:
            result = device.tool("haminn_hot_update_happ", {
                "appId": app_id, "expectedDevRevision": revision, "requestId": str(uuid.uuid4()),
                "files": text_changes, "refreshMode": "auto", "preserveState": True
            })
        except Exception:
            result = device.tool("haminn_sync_dev_changes", {
                "appId": app_id, "expectedDevRevision": revision, "requestId": str(uuid.uuid4()),
                "files": text_changes, "refreshMode": "auto"
            })
        apply_remote_result(remote_state, result, local_hashes, text_changes)
    return result


def prepare_dev(device, directory, selected_app_id=None, hash_cache=None, workspace_cache=None, sync_policy="ask"):
    # reuse the exact happId binding in ~/haminn/happ-dev.json; never scan the disk or silently create a second copy.
    root = Path(directory).resolve(strict=True)
    try:
        manifest = json.loads((root / "haminn.json").read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError("Local directory must contain a valid UTF-8 haminn.json") from error
    happ_id = manifest.get("happId")
    if not isinstance(happ_id, str) or not happ_id.strip():
        raise ValueError("Local haminn.json must declare a stable happId")
    apps = device.tool("haminn_list_apps", {"includeIcons": False, "happId": happ_id}).get("apps", [])
    matches = [item for item in apps if item.get("happId") == happ_id]
    if selected_app_id:
        matches = [item for item in matches if item.get("appId") == selected_app_id]
    if not matches:
        raise ValueError("No installed happ matches local happId " + happ_id)
    if len(matches) != 1:
        raise ValueError("Multiple installed instances match; rerun with --app-id")
    app_id = matches[0]["appId"]
    try:
        status = device.tool("haminn_prepare_happ_development", {
            "appId": app_id, "strategy": "resume", "startRuntimeMode": "dev", "requestId": str(uuid.uuid4())
        })
    except Exception:
        # Keep the helper usable while an older APK is being replaced.
        status = ensure_dev_target(device, app_id)
        result = dev_sync(device, app_id, root, ensure_target=False, hash_cache=hash_cache, workspace_cache=workspace_cache)
        render_operation = result.get("renderOperationId") or status.get("renderOperationId")
        rendered = device.tool("haminn_wait_dev_render", {"operationId": render_operation, "timeoutMs": 5000}) if render_operation else None
        return {"prepared": rendered is None or rendered.get("state") == "rendered",
                "happId": happ_id, "appId": app_id, "status": status, "sync": result, "render": rendered}
    local_version = manifest.get("version") if isinstance(manifest.get("version"), dict) else {}
    remote_version = status.get("devVersion") if isinstance(status.get("devVersion"), dict) else {}
    versions_differ = local_version != remote_version
    policy = sync_policy
    if policy == "ask":
        print(json.dumps({"happId": happ_id, "appId": app_id, "localVersion": local_version,
                          "deviceDevVersion": remote_version, "deviceRevision": status.get("revision"),
                          "question": "选择设备为准(device)、开发端为准(client)，或只下载设备树(download)"}, ensure_ascii=False), file=sys.stderr)
        if sys.stdin.isatty():
            policy = input("Sync policy [client/device/download/continue] (client): ").strip().lower() or "client"
        else:
            if versions_differ:
                raise RuntimeError("设备开发版与本地版本不同；请让用户选择 --sync-policy client|device|download|continue")
            policy = "continue"
    if policy not in {"client", "device", "download", "continue"}:
        raise ValueError("sync policy must be client, device, download or continue")
    if policy == "device":
        raise RuntimeError("设备为准已确认；请先调用 haminn_download_dev_tree 下载开发树，再重新绑定本地目录")
    if policy == "download":
        downloaded = download_dev_tree(device, app_id)
        return {"prepared": True, "happId": happ_id, "appId": app_id, "status": status, "download": downloaded}
    if policy == "client":
        result = replace_dev_tree(device, app_id, root, status["revision"], force=True)
        if workspace_cache is not None:
            workspace_cache.clear()
            workspace_cache.update(local_workspace_state(app_id, development_files(root), result["revision"], result.get("treeHash")))
    else:
        result = dev_sync(device, app_id, root, ensure_target=False, hash_cache=hash_cache, workspace_cache=workspace_cache)
    render_operation = result.get("renderOperationId")
    rendered = None
    if render_operation:
        rendered = device.tool("haminn_wait_dev_render", {"operationId": render_operation, "timeoutMs": 5000})
    return {"prepared": rendered is None or rendered.get("state") == "rendered",
            "happId": happ_id, "appId": app_id, "status": status, "sync": result,
            "render": rendered}


def release_manifest(directory):
    root = Path(directory).resolve(strict=True)
    manifest_file = root / "haminn.json"
    try:
        manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError("Local directory must contain a valid UTF-8 haminn.json") from error
    if not isinstance(manifest, dict) or not isinstance(manifest.get("happId"), str) or not manifest["happId"].strip():
        raise ValueError("Local haminn.json must declare a stable happId")
    version = manifest.get("version")
    if not isinstance(version, dict) or not isinstance(version.get("code"), int) or version["code"] < 1 or not isinstance(version.get("name"), str) or not version["name"].strip():
        raise ValueError("Local haminn.json must declare version.code >= 1 and a non-empty version.name")
    entry = manifest.get("entry", "index.html")
    if not isinstance(entry, str) or not entry or entry.startswith("/") or ".." in entry.split("/") or not (root / entry).is_file():
        raise ValueError("Local haminn.json entry must point to an existing package file")
    files = development_files(root)
    names = {name for name, _ in files}
    if "haminn.json" not in names or entry not in names:
        raise ValueError("haminn.json and entry must be inside the deployable source scope")
    return root, manifest_file, manifest, files


def bumped_version(name, kind):
    pieces = name.split(".", 2)
    if len(pieces) != 3 or any(not part.isdigit() for part in pieces):
        raise ValueError("--bump requires a numeric semantic version such as 1.2.3")
    major, minor, patch = (int(part) for part in pieces)
    if kind == "major":
        return f"{major + 1}.0.0"
    if kind == "minor":
        return f"{major}.{minor + 1}.0"
    if kind == "patch":
        return f"{major}.{minor}.{patch + 1}"
    return name


def bump_manifest(manifest_file, manifest, kind):
    if kind == "none":
        return manifest
    version = dict(manifest["version"])
    version["code"] += 1
    version["name"] = bumped_version(version["name"], kind)
    updated = dict(manifest); updated["version"] = version
    temporary = manifest_file.with_name("." + manifest_file.name + ".tmp")
    with temporary.open("w", encoding="utf-8") as output:
        output.write(json.dumps(updated, ensure_ascii=False, indent=2) + "\n")
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, manifest_file)
    return updated


def update_dir(device, directory, selected_app_id=None, bump="none"):
    started = time.monotonic()
    root, manifest_file, manifest, _ = release_manifest(directory)
    manifest = bump_manifest(manifest_file, manifest, bump)
    preflight_ms = round((time.monotonic() - started) * 1000)
    hash_cache, workspace_cache = {}, {}
    prepared = prepare_dev(device, root, selected_app_id, hash_cache=hash_cache, workspace_cache=workspace_cache, sync_policy="client")
    app_id = prepared["appId"]
    before = device.tool("haminn_get_app", {"appId": app_id, "includeIcons": False})
    stable_release = before.get("activeReleaseId")
    if not stable_release:
        raise RuntimeError("A local stable release is required before update-dir can publish")
    releases = device.tool("haminn_list_releases", {"appId": app_id}).get("releases", [])
    active = next((item for item in releases if item.get("releaseId") == stable_release), None)
    version = manifest["version"]
    if active and isinstance(active.get("versionCode"), int) and version["code"] <= active["versionCode"]:
        raise RuntimeError("Local version.code must be greater than the active stable version; pass --bump explicitly or update haminn.json")
    revision = prepared["sync"]["revision"]
    built = device.tool("haminn_build_dev_package", {
        "appId": app_id, "expectedDevRevision": revision, "requestId": str(uuid.uuid4()),
        "versionCode": version["code"], "versionName": version["name"],
    })
    installed = device.tool("haminn_install_dev_package", {
        "appId": app_id, "expectedDevRevision": revision, "requestId": str(uuid.uuid4()),
        "buildId": built["buildId"], "expectedStableReleaseId": stable_release,
    })
    after = device.tool("haminn_get_app", {"appId": app_id, "includeIcons": False})
    if after.get("appId") != app_id or after.get("activeReleaseId") == stable_release or after.get("launchChannel") != "stable":
        raise RuntimeError("Stable installation did not activate the original instance")
    if before.get("dataGenerationId") != after.get("dataGenerationId") or before.get("trustRevision") != after.get("trustRevision"):
        raise RuntimeError("Stable installation unexpectedly changed app data or grants")
    return {
        "status": "installed", "appId": app_id, "happId": manifest["happId"],
        "version": version, "changedPaths": len(prepared["sync"].get("changedPaths", [])),
        "render": (prepared["render"] or {}).get("state", prepared["sync"].get("refreshState", "unchanged")),
        "launchChannel": "stable", "dataPreserved": True,
        "releaseId": installed["releaseId"], "timing": {
            "preflightMs": preflight_ms,
            "controlMs": round((time.monotonic() - started) * 1000),
        },
    }


def watch_development(device, app_id, directory, already_synced=False, quiet=False, hash_cache=None, workspace_cache=None):
    tracked = development_files(directory)
    hash_cache = {} if hash_cache is None else hash_cache
    previous = development_signature(directory, tracked) if already_synced else None
    target_ready = already_synced
    reconnect_delay = 0.5
    last_heartbeat = time.monotonic()
    while True:
        # Do not hash the whole tree every 100 ms. Stat metadata catches normal
        # editor saves; dev_sync performs the authoritative SHA-256 comparison
        # once the debounce window has settled.
        snapshot = development_signature(directory, tracked)
        if snapshot != previous:
            time.sleep(0.15)
            stable = development_signature(directory, tracked)
            if stable == snapshot:
                try:
                    published = dev_sync(device, app_id, directory, ensure_target=not target_ready, hash_cache=hash_cache, workspace_cache=workspace_cache)
                except (OSError, RuntimeError) as error:
                    print("Haminn watch waiting for device: " + str(error), file=sys.stderr, flush=True)
                    # A reconnect may land on a phone where DEV mode was left
                    # by another client or the target was recreated.  Force
                    # the next successful publish to re-check/enter DEV once;
                    # do not pay that round-trip on every ordinary save.
                    target_ready = False
                    if workspace_cache is not None:
                        workspace_cache.clear()
                    time.sleep(reconnect_delay)
                    reconnect_delay = min(reconnect_delay * 2, 5.0)
                    try:
                        device.initialize()
                    except (OSError, RuntimeError):
                        pass
                    continue
                if not quiet:
                    print(json.dumps(published, ensure_ascii=False), flush=True)
                tracked = development_files(directory)
                live_names = {name for name, _ in tracked}
                for name in set(hash_cache) - live_names:
                    hash_cache.pop(name, None)
                previous = development_signature(directory, tracked)
                target_ready = True
                reconnect_delay = 0.5
                last_heartbeat = time.monotonic()
        if time.monotonic() - last_heartbeat >= 20:
            try:
                device.rpc("ping")
                last_heartbeat = time.monotonic()
            except (OSError, RuntimeError) as error:
                print("Haminn watch connection reset: " + str(error), file=sys.stderr, flush=True)
                target_ready = False
                if workspace_cache is not None:
                    workspace_cache.clear()
                time.sleep(reconnect_delay)
                reconnect_delay = min(reconnect_delay * 2, 5.0)
        time.sleep(0.1)


def deploy(device, app_id, directory, expected_release=None):
    current = device.tool("haminn_get_app", {"appId": app_id})
    release = current.get("activeReleaseId")
    if expected_release is not None and release != expected_release:
        raise RuntimeError("Another computer changed this app; stop watching and merge before resuming")
    if not release:
        raise ValueError("A local app with a code release is required")
    with archive_source(directory) as archive:
        data = archive.read()
    headers = {"Content-Type": "application/zip", "X-Haminn-Expected-Release": release, "Idempotency-Key": str(uuid.uuid4()), "X-Haminn-Content-SHA256": hashlib.sha256(data).hexdigest()}
    return json.loads(device.request("/v1/apps/" + urllib.parse.quote(app_id, safe="") + "/release", "PUT", data, headers))


def ensure_codex_marketplace(target):
    """Expose the standard ~/plugins target through Codex's personal marketplace."""
    defaults = {(Path.home() / "plugins" / name).absolute() for name in PLUGIN_IDS}
    if target not in defaults:
        return None
    marketplace = (Path.home() / ".agents" / "plugins" / "marketplace.json").absolute()
    if marketplace.is_symlink():
        raise RuntimeError("Refusing a symlinked Codex marketplace file")
    if marketplace.exists():
        try:
            payload = json.loads(marketplace.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            raise RuntimeError("Codex personal marketplace is not valid JSON") from error
        if not isinstance(payload, dict) or payload.get("name") != "personal":
            raise RuntimeError("Codex personal marketplace name is not 'personal'; it was not changed")
        plugins = payload.get("plugins")
        if not isinstance(plugins, list):
            raise RuntimeError("Codex personal marketplace plugins must be an array")
    else:
        payload = {"name": "personal", "interface": {"displayName": "Personal"}, "plugins": []}
        plugins = payload["plugins"]
    name = target.name
    entry = {
        "name": name,
        "source": {"source": "local", "path": "./plugins/" + name},
        "policy": {"installation": "AVAILABLE", "authentication": "ON_INSTALL"},
        "category": "Developer Tools",
    }
    plugins[:] = [item for item in plugins
                  if not (isinstance(item, dict) and item.get("name") in PLUGIN_IDS)]
    plugins.append(entry)
    marketplace.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = marketplace.with_name("." + marketplace.name + ".tmp-" + uuid.uuid4().hex)
    try:
        temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        try:
            temporary.chmod(0o600)
        except OSError:
            pass
        os.replace(temporary, marketplace)
    finally:
        if temporary.exists():
            temporary.unlink()
    return marketplace


def install_plugin(device, directory=None, force=False, package_url=None, package_sha256=None, plugin_version=None):
    """Install the device-provided plugin bundle without sending credentials."""
    supplied = (package_url, package_sha256, plugin_version)
    if any(value is not None for value in supplied):
        if not all(isinstance(value, str) and value for value in supplied):
            raise RuntimeError("Explicit package URL, SHA-256 and plugin version must be provided together")
        bootstrap = {
            "serverVersion": plugin_version,
            "plugin": {"id": PLUGIN_ID, "version": plugin_version},
            "install": {"action": "install_or_update", "packageUrl": package_url, "packageSha256": package_sha256},
        }
    else:
        bootstrap = device.bootstrap()
    plugin = bootstrap.get("plugin") or {}
    install = bootstrap.get("install") or {}
    if plugin.get("id") not in PLUGIN_IDS or install.get("action") not in {"install_or_update", "reinstall"}:
        raise RuntimeError("Haminn Bootstrap 未提供可安装的 " + PLUGIN_ID + " 插件")
    package_url = install.get("packageUrl")
    if not isinstance(package_url, str) or not package_url:
        raise RuntimeError("Haminn Bootstrap 的插件包地址无效")
    data = device.request(package_url, authenticated=False, headers={"Accept": "application/zip"})
    expected = install.get("packageSha256")
    actual = hashlib.sha256(data).hexdigest()
    if not isinstance(expected, str) or len(expected) != 64 or expected != actual:
        raise RuntimeError("Haminn 插件包摘要校验失败，旧插件未改变")
    default_target = (Path.home() / "plugins" / PLUGIN_ID).absolute()
    target = Path(directory or default_target).expanduser().absolute()
    if target.exists() and target.is_symlink():
        raise RuntimeError("Refusing a symlinked plugin destination")
    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    had_target = target.exists()
    with tempfile.TemporaryDirectory(dir=str(target.parent), prefix=".haminn-plugin-") as staging:
        staging_path = Path(staging)
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            names = archive.namelist()
            if any(not name or name.startswith("/") or ".." in Path(name).parts or name.endswith("/") for name in names):
                raise RuntimeError("Haminn 插件包包含非法路径")
            archive.extractall(staging_path)
        manifest_file = staging_path / "manifest.json"
        if not manifest_file.is_file():
            raise RuntimeError("Haminn 插件包缺少 manifest.json")
        manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
        if manifest.get("id") not in PLUGIN_IDS or manifest.get("kind") != "haminn-agent-plugin":
            raise RuntimeError("Haminn 插件 manifest 不匹配")
        if manifest.get("packageFormat") != "codex-plugin-archive-v1":
            raise RuntimeError("Haminn 插件包格式不是当前 Codex 插件格式")
        codex_manifest_file = staging_path / ".codex-plugin" / "plugin.json"
        mcp_config_file = staging_path / ".mcp.json"
        if not codex_manifest_file.is_file() or not mcp_config_file.is_file():
            raise RuntimeError("Haminn 插件包缺少 Codex plugin.json 或 MCP 配置")
        codex_manifest = json.loads(codex_manifest_file.read_text(encoding="utf-8"))
        if (codex_manifest.get("name") not in PLUGIN_IDS or
                codex_manifest.get("version") != manifest.get("codexVersion") or
                codex_manifest.get("mcpServers") != "./.mcp.json"):
            raise RuntimeError("Haminn Codex 插件清单不匹配")
        marketplace = ensure_codex_marketplace(target) if target == default_target else None
        if not force and target.is_dir() and (target / "manifest.json").is_file():
            try:
                old = json.loads((target / "manifest.json").read_text(encoding="utf-8"))
                marker = target / ".haminn-package-sha256"
                if (old.get("id") == manifest.get("id") and
                        old.get("version") == manifest.get("version") and
                        marker.is_file() and marker.read_text(encoding="ascii").strip() == actual):
                    result = {"installed": True, "installedPath": str(target), "mcpRegistered": False, "authenticated": False,
                              "action": "unchanged", "plugin": manifest,
                              "nextAction": "register_mcp_then_authenticate"}
                    if marketplace:
                        result["marketplace"] = str(marketplace)
                    return result
            except (OSError, UnicodeError, json.JSONDecodeError):
                pass
        backup = target.with_name(target.name + ".previous")
        if backup.exists() or backup.is_symlink():
            if backup.is_dir() and not backup.is_symlink():
                shutil.rmtree(backup)
            else:
                backup.unlink()
        if target.exists():
            os.replace(target, backup)
        try:
            os.replace(staging_path, target)
        except Exception:
            if backup.exists() and not target.exists():
                os.replace(backup, target)
            raise
        if backup.exists():
            shutil.rmtree(backup)
        (target / ".haminn-package-sha256").write_text(actual + "\n", encoding="ascii")
        try:
            (target / ".haminn-package-sha256").chmod(0o600)
        except OSError:
            pass
        result = {"installed": True, "installedPath": str(target), "mcpRegistered": False, "authenticated": False,
                  "action": "updated" if had_target else "installed", "plugin": manifest,
                  "nextAction": "register_mcp_then_authenticate", "serverVersion": bootstrap.get("serverVersion")}
        if marketplace:
            result["marketplace"] = str(marketplace)
        return result


def stdio(device):
    # Transparent JSON-lines MCP adapter. No banners or secrets on stdout.
    for line in sys.stdin:
        request = None
        try:
            if len(line) > 4 * 1024 * 1024:
                raise ValueError("Request exceeds size limit")
            request = json.loads(line)
            if not isinstance(request, dict):
                raise ValueError("Expected JSON object")
            reply = device.request("/mcp", "POST", json.dumps(request).encode(), {"Content-Type": "application/json"})
            if reply:
                print(reply.decode(), flush=True)
        except Exception as error:
            if isinstance(request, dict) and "id" in request:
                print(json.dumps({"jsonrpc": "2.0", "id": request["id"], "error": {"code": -32603, "message": str(error)}}), flush=True)
            else:
                print("Haminn adapter request failed", file=sys.stderr, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--address", required=True, help="HTTP base URL shown on phone (not /mcp)")
    commands = parser.add_subparsers(dest="command", required=True)
    connect = commands.add_parser("connect", help="Privately enter/replace and save the shared password")
    connect.add_argument("--show-guide", action="store_true", help="Print the full current guide after connecting")
    commands.add_parser("guide")
    commands.add_parser("tools")
    commands.add_parser("doctor", help="Cache what this host machine actually has; never installs anything")
    call = commands.add_parser("call"); call.add_argument("tool"); call.add_argument("arguments", nargs="?", default="{}")
    for name in ("deploy-dir", "sync-dir", "watch"):
        cmd = commands.add_parser(name); cmd.add_argument("app_id"); cmd.add_argument("directory")
        if name == "watch":
            cmd.add_argument("--quiet", action="store_true", help="Print only connection errors while watching")
    for name, help_text in (
        ("prepare-dir", "Match, enter DEV, sync a local happ directory and await render"),
        ("develop-dir", "Prepare a local happ once, then continuously sync settled saves"),
        ("update-dir", "Preflight, sync and atomically promote a local happ directory to stable"),
    ):
        prepare = commands.add_parser(name, help=help_text)
        prepare.add_argument("directory"); prepare.add_argument("--app-id")
        prepare.add_argument("--sync-policy", choices=("ask", "client", "device", "download", "continue"), default="ask",
                             help="整体同步方向；ask 比较本地和设备开发版本后询问")
        if name == "develop-dir":
            prepare.add_argument("--quiet", action="store_true", help="Suppress per-save sync results")
        if name == "update-dir":
            prepare.add_argument("--bump", choices=("patch", "minor", "major", "none"), default="none",
                                 help="Explicitly update haminn.json version before stable installation")
    enter = commands.add_parser("enter-dev"); enter.add_argument("app_id"); enter.add_argument("--route")
    leave = commands.add_parser("leave-dev"); leave.add_argument("app_id")
    create = commands.add_parser("create-dev"); create.add_argument("name"); create.add_argument("happ_id")
    build = commands.add_parser("build"); build.add_argument("app_id"); build.add_argument("version_code", type=int); build.add_argument("version_name"); build.add_argument("output")
    publish = commands.add_parser("publish"); publish.add_argument("app_id"); publish.add_argument("version_code", type=int); publish.add_argument("version_name")
    plugin = commands.add_parser("install-plugin", help="Install or update the Haminn plugin from this development service")
    plugin.add_argument("--directory", default=str(Path.home() / "plugins" / PLUGIN_ID))
    plugin.add_argument("--force", action="store_true", help="Reinstall even when the local plugin version is unchanged")
    plugin.add_argument("--package-url", help="Same-origin package URL already obtained from Bootstrap")
    plugin.add_argument("--package-sha256", help="Expected package digest already obtained from Bootstrap")
    plugin.add_argument("--plugin-version", help="Plugin version already obtained from Bootstrap")
    commands.add_parser("client-config")
    commands.add_parser("stdio")
    args = parser.parse_args()
    device = Device(args.address)
    if args.command == "connect":
        device.password = os.environ.get("HAMINN_PASSWORD") or getpass.getpass("Haminn six-character password: ")
        initialized = device.initialize(); device.save_password()
        result = {"connected": True, "serverInfo": initialized["serverInfo"],
                  "credentialFile": str(device.credential_file)}
        if args.show_guide:
            result["guidance"] = device.tool("haminn_get_guide")
    elif args.command == "doctor":
        result = host_environment()
    elif args.command == "install-plugin":
        result = install_plugin(device, args.directory, args.force, args.package_url, args.package_sha256, args.plugin_version)
    elif args.command == "client-config":
        script = str(Path(__file__).resolve())
        stdio_args = [script, "--address", device.base, "stdio"]
        codex_args = ["codex", "mcp", "add", PLUGIN_ID, "--", sys.executable] + stdio_args
        result = {"platforms": ["Windows", "macOS", "Linux"],
                  "mcpServers": {PLUGIN_ID: {"command": sys.executable, "args": stdio_args}},
                  "commands": {"posix": shlex.join(codex_args), "windows": subprocess.list2cmdline(codex_args)},
                  "remoteHTTP": {"url": device.base + "/mcp", "header": "Authorization: Bearer <current password>", "note": "Use your client's secret storage; run connect first for stdio. This command only prints templates, never edits client configuration."}}
    elif args.command == "stdio":
        stdio(device); return
    else:
        if args.command in ("prepare-dir", "develop-dir", "update-dir"):
            device.discover()
        device.initialize()
        if args.command == "guide":
            result = device.tool("haminn_get_guide")
        elif args.command == "tools":
            result = device.rpc("tools/list")
        elif args.command == "call":
            result = device.tool(args.tool, json.loads(args.arguments))
        elif args.command == "deploy-dir":
            result = deploy(device, args.app_id, args.directory)
        elif args.command == "enter-dev":
            arguments = {"appId": args.app_id, "requestId": str(uuid.uuid4())}
            if args.route:
                arguments["route"] = args.route
            result = device.tool("haminn_enter_dev_mode", arguments)
        elif args.command == "leave-dev":
            result = device.tool("haminn_leave_dev_mode", {"appId": args.app_id})
        elif args.command == "create-dev":
            result = device.tool("haminn_create_dev_app", {"name": args.name, "happId": args.happ_id, "requestId": str(uuid.uuid4())})
        elif args.command == "sync-dir":
            result = dev_sync(device, args.app_id, args.directory)
        elif args.command == "prepare-dir":
            result = prepare_dev(device, args.directory, args.app_id, workspace_cache={}, sync_policy=args.sync_policy)
        elif args.command == "develop-dir":
            hash_cache, workspace_cache = {}, {}
            prepared = prepare_dev(device, args.directory, args.app_id, hash_cache=hash_cache, workspace_cache=workspace_cache, sync_policy=args.sync_policy)
            print(json.dumps(prepared, ensure_ascii=False), flush=True)
            watch_development(device, prepared["appId"], args.directory, already_synced=True, quiet=args.quiet,
                              hash_cache=hash_cache, workspace_cache=workspace_cache)
            return
        elif args.command == "update-dir":
            result = update_dir(device, args.directory, args.app_id, args.bump)
        elif args.command in ("build", "publish"):
            current = device.tool("haminn_get_app", {"appId": args.app_id})
            dev = current.get("devWorkspace") or {}
            if current.get("launchChannel") != "dev" or not dev.get("revision"):
                raise RuntimeError("Enter dev mode before building the development workspace")
            result = device.tool("haminn_build_dev_package", {
                "appId": args.app_id, "expectedDevRevision": dev["revision"], "requestId": str(uuid.uuid4()),
                "versionCode": args.version_code, "versionName": args.version_name
            })
            if args.command == "build":
                url = urllib.parse.urlsplit(result["downloadUrl"])
                Path(args.output).write_bytes(device.request(url.path, authenticated=True))
                result["savedTo"] = str(Path(args.output).resolve())
            else:
                result = device.tool("haminn_install_dev_package", {
                    "appId": args.app_id, "expectedDevRevision": dev["revision"], "requestId": str(uuid.uuid4()),
                    "buildId": result["buildId"], "expectedStableReleaseId": current["activeReleaseId"]
                })
        elif args.command == "watch":
            watch_development(device, args.app_id, args.directory, quiet=args.quiet)
            return
        else:
            raise ValueError("Unknown command")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
    except Exception as error:
        print("Haminn: " + str(error), file=sys.stderr)
        sys.exit(1)
