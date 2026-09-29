import importlib.util
import io
import json
import hashlib
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("haminn_agent", Path(__file__).resolve().parents[1] / "app/src/main/assets/agent/haminn-agent.py")
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    seen = []
    ports = []
    def log_message(self, *args):
        pass
    def do_GET(self):
        self.send_response(302)
        self.send_header("Location", "http://127.0.0.1:1/leak")
        self.send_header("Content-Length", "0")
        self.end_headers()
    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.seen.append((dict(self.headers), data))
        self.ports.append(self.client_address[1])
        response = {"jsonrpc": "2.0", "id": data.get("id"), "result": {"tools": []}}
        encoded = json.dumps(response).encode()
        self.send_response(200 if "id" in data else 202)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded) if "id" in data else 0))
        self.end_headers()
        if "id" in data:
            self.wfile.write(encoded)


class AgentHelperTest(unittest.TestCase):
    def test_bootstrap_plugin_install_and_digest_validation(self):
        package_buffer = io.BytesIO()
        with zipfile.ZipFile(package_buffer, "w") as archive:
            archive.writestr("manifest.json", json.dumps({"kind": "haminn-agent-plugin", "id": "haminn-device", "version": "9.0.0", "codexVersion": "9.0.0+codex.test", "packageFormat": "codex-plugin-archive-v1"}))
            archive.writestr(".codex-plugin/plugin.json", json.dumps({"name": "haminn-device", "version": "9.0.0+codex.test"}))
            archive.writestr("SKILL.md", "bootstrap skill")
            archive.writestr("haminn-agent.py", "print('helper')")
        package = package_buffer.getvalue()
        digest = hashlib.sha256(package).hexdigest()

        class BootstrapHandler(BaseHTTPRequestHandler):
            root_requests = 0
            def log_message(self, *_):
                pass
            def do_GET(self):
                if self.path == "/":
                    type(self).root_requests += 1
                    body = json.dumps({
                        "kind": "haminn-agent-bootstrap",
                        "serverVersion": "9.0.0",
                        "plugin": {"id": "haminn-device", "version": "9.0.0"},
                        "install": {"action": "install_or_update", "packageUrl": "http://127.0.0.1:%d/plugin/haminn-device" % self.server.server_port, "packageSha256": digest},
                    }).encode()
                    self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
                elif self.path == "/plugin/haminn-device":
                    self.send_response(200); self.send_header("Content-Type", "application/zip"); self.send_header("Content-Length", str(len(package))); self.end_headers(); self.wfile.write(package)
                else:
                    self.send_response(404); self.end_headers()

        server = ThreadingHTTPServer(("127.0.0.1", 0), BootstrapHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            with tempfile.TemporaryDirectory() as temp:
                client = helper.Device("http://127.0.0.1:" + str(server.server_port), config_root=temp)
                target = Path(temp) / "plugin"
                result = helper.install_plugin(client, target)
                self.assertEqual("installed", result["action"])
                self.assertEqual("bootstrap skill", (target / "SKILL.md").read_text())
                self.assertEqual("haminn-device", json.loads((target / "manifest.json").read_text())["id"])
                result = helper.install_plugin(client, target)
                self.assertEqual("unchanged", result["action"])
                roots = BootstrapHandler.root_requests
                result = helper.install_plugin(client, target, force=True,
                                               package_url="http://127.0.0.1:%d/plugin/haminn-device" % server.server_port,
                                               package_sha256=digest, plugin_version="9.0.0")
                self.assertEqual("updated", result["action"])
                self.assertEqual(roots, BootstrapHandler.root_requests)
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_rejects_public_urls_embedded_secrets_and_paths(self):
        for value in ["http://8.8.8.8:8766", "http://0.0.0.0:8766", "http://user:password@192.168.1.2:8766", "https://192.168.1.2:8766", "http://192.168.1.2:8766/mcp", "http://192.168.1.2:8766?password=123456"]:
            with self.assertRaises(ValueError): helper.address(value)
        self.assertEqual("http://192.168.1.2:8766", helper.address("http://192.168.1.2:8766/"))

    def test_absolute_package_url_must_be_same_origin(self):
        client = helper.Device("http://127.0.0.1:8766", "123456")
        self.assertEqual("/plugin/haminn-device?x=1", client.request_target("http://127.0.0.1:8766/plugin/haminn-device?x=1"))
        with self.assertRaises(ValueError):
            client.request_target("http://127.0.0.2:8766/plugin/haminn-device")
        with self.assertRaises(ValueError):
            client.request_target("https://127.0.0.1:8766/plugin/haminn-device")

    def test_plugin_identity_survives_the_connector_removal(self):
        self.assertEqual("haminn-dev-plugin", helper.PLUGIN_ID)
        self.assertIn("haminn-device", helper.PLUGIN_IDS)
        # The install path no longer offers a connector: no stdio adapter, no
        # marketplace entry, no MCP configuration anywhere in the helper.
        for removed in ("stdio", "ensure_codex_marketplace"):
            self.assertFalse(hasattr(helper, removed), removed)
        source = Path(helper.__file__).read_text(encoding="utf-8")
        for absent in ("mcpServers", ".mcp.json", "client-config", "marketplace.json"):
            self.assertNotIn(absent, source)

    def test_password_is_reused_across_addresses_on_one_lan(self):
        with tempfile.TemporaryDirectory() as temp:
            helper.Device("http://192.168.1.10:8766", "123456", temp).save_password()
            self.assertEqual("123456", helper.Device("http://192.168.1.77:8766", config_root=temp).password)
            self.assertIsNone(helper.Device("http://192.168.2.77:8766", config_root=temp).password)

    def test_development_scope_names_what_can_never_publish(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "index.html").write_text("native")
            (root / "haminn.json").write_text("{}")
            (root / "notes.md").write_text("scratch")
            (root / "release").mkdir()
            with zipfile.ZipFile(root / "release" / "happ.zip", "w") as archive:
                archive.writestr("index.html", "old")
                archive.writestr("haminn.json", "{}")
            (root / "haminn-install.json").write_text(json.dumps({"package": "release/happ.zip"}))
            scope = helper.development_scope(root)
            self.assertEqual(["haminn.json", "index.html"], scope["roots"])
            self.assertIn("notes.md", scope["excluded"])
            self.assertIn("haminn-install.json", scope["excluded"])
            self.assertIn("release/happ.zip", scope["excluded"])
            self.assertEqual(scope["excludedCount"], len(scope["excluded"]))

    def test_credentials_are_private_and_rotation_replaces_one_value(self):
        with tempfile.TemporaryDirectory() as temp:
            client = helper.Device("http://127.0.0.1:8766", "123456", temp)
            client.save_password()
            self.assertEqual(0o600, client.credential_file.stat().st_mode & 0o777)
            client.password = "654321"; client.save_password()
            loaded = helper.Device(client.base, config_root=temp)
            self.assertTrue(loaded.password == client.password)
            client.credential_file.chmod(0o644)
            with self.assertRaises(RuntimeError): helper.Device(client.base, config_root=temp)

    def test_source_snapshot_excludes_secrets_and_rejects_symlinks(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "index.html").write_text("native")
            (root / ".env").write_text("SECRET")
            (root / "private.key").write_text("SECRET")
            (root / "node_modules").mkdir(); (root / "node_modules/skip.js").write_text("skip")
            self.assertEqual(["index.html"], [name for name, _ in helper.source_files(root)])
            (root / "linked.html").symlink_to(root / "index.html")
            with self.assertRaises(ValueError): helper.source_files(root)

    def test_development_snapshot_uses_local_package_scope(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "index.html").write_text("native")
            (root / "haminn.json").write_text("{}")
            (root / "app").mkdir(); (root / "app" / "app.js").write_text("run")
            (root / "docs").mkdir(); (root / "docs" / "draft.md").write_text("skip")
            (root / "release").mkdir()
            package = root / "release" / "happ.zip"
            with zipfile.ZipFile(package, "w") as archive:
                archive.writestr("index.html", "old")
                archive.writestr("haminn.json", "{}")
                archive.writestr("app/app.js", "old")
            (root / "haminn-install.json").write_text(json.dumps({"package": "release/happ.zip"}))
            self.assertEqual(["app/app.js", "haminn.json", "index.html"],
                             [name for name, _ in helper.development_files(root)])

    def test_http_auth_and_redirect_refusal(self):
        with tempfile.TemporaryDirectory() as temp:
            server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            try:
                client = helper.Device("http://127.0.0.1:" + str(server.server_port), "123456", temp)
                self.assertEqual({"tools": []}, client.rpc("tools/list"))
                first_port = Handler.ports[-1]
                self.assertEqual({"tools": []}, client.rpc("ping"))
                self.assertEqual(first_port, Handler.ports[-1])
                headers, body = Handler.seen[-1]
                self.assertTrue(headers["Authorization"] == "Bearer " + client.password)
                self.assertNotIn("Mcp-Session-Id", headers)
                with self.assertRaises(RuntimeError): client.request("/redirect")
            finally:
                client.close()
                server.shutdown(); server.server_close(); thread.join()

    def test_watcher_refuses_newer_remote_release(self):
        class OtherComputer:
            def tool(self, *_): return {"activeReleaseId": "newer-release"}
        with self.assertRaisesRegex(RuntimeError, "Another computer"):
            helper.deploy(OtherComputer(), "app-id", "/unused", "my-release")

    def test_dev_sync_sends_only_changed_text_and_deletions(self):
        class Device:
            def __init__(self): self.calls = []; self.base = "http://device"
            def tool(self, name, arguments=None):
                self.calls.append((name, arguments or {}))
                if name == "haminn_runtime_status":
                    return {"appId": "app-id", "launchChannel": "dev"}
                if name == "haminn_list_dev_files":
                    same = hashlib.sha256(b"same").hexdigest()
                    return {"revision": 7, "treeHash": "old", "files": [
                        {"path": "index.html", "sha256": same},
                        {"path": "removed.css", "sha256": "0" * 64},
                    ]}
                if name == "haminn_sync_dev_changes":
                    return {"revision": 8, "changedPaths": [item["path"] for item in arguments["files"]]}
                raise AssertionError(name)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "index.html").write_text("same")
            (root / "haminn.json").write_text("{}")
            (root / "app.js").write_text("changed")
            device = Device()
            result = helper.dev_sync(device, "app-id", root)
            self.assertEqual(8, result["revision"])
            self.assertEqual("haminn_runtime_status", device.calls[0][0])
            name, arguments = device.calls[-1]
            self.assertEqual("haminn_sync_dev_changes", name)
            self.assertEqual(7, arguments["expectedDevRevision"])
            self.assertEqual([
                {"path": "app.js", "content": "changed"},
                {"path": "haminn.json", "content": "{}"},
                {"path": "removed.css", "delete": True},
            ], arguments["files"])

    def test_dev_sync_prepares_and_opens_target_only_when_needed(self):
        class Device:
            def __init__(self): self.calls = []
            def tool(self, name, arguments=None):
                self.calls.append((name, arguments or {}))
                if name == "haminn_runtime_status":
                    return {"appId": "other-app", "launchChannel": "stable"}
                if name == "haminn_enter_dev_mode":
                    return {"appId": "app-id", "launchChannel": "dev", "revision": 3,
                            "runtime": {"state": "opening"}}
                if name == "haminn_list_dev_files":
                    return {"revision": 3, "treeHash": "same", "files": [
                        {"path": "index.html", "sha256": hashlib.sha256(b"same").hexdigest()},
                        {"path": "haminn.json", "sha256": hashlib.sha256(b"{}").hexdigest()}
                    ]}
                raise AssertionError(name)
        with tempfile.TemporaryDirectory() as temp:
            Path(temp, "index.html").write_text("same")
            Path(temp, "haminn.json").write_text("{}")
            Path(temp, "app").mkdir(); Path(temp, "styles").mkdir()
            device = Device()
            result = helper.dev_sync(device, "app-id", temp)
            self.assertEqual("unchanged", result["refreshState"])
            self.assertEqual(["haminn_runtime_status", "haminn_enter_dev_mode", "haminn_get_happ_dev_status", "haminn_list_dev_files"],
                             [name for name, _ in device.calls])
            self.assertEqual("app-id", device.calls[1][1]["appId"])
            self.assertIn("requestId", device.calls[1][1])

    def test_dev_sync_reuses_hash_cache_for_unchanged_files(self):
        class Device:
            def tool(self, name, arguments=None):
                if name == "haminn_list_dev_files":
                    return {"revision": 3, "treeHash": "same", "files": [
                        {"path": "index.html", "sha256": hashlib.sha256(b"same").hexdigest()},
                        {"path": "haminn.json", "sha256": hashlib.sha256(b"{}").hexdigest()},
                    ]}
                raise AssertionError(name)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "index.html").write_text("same")
            (root / "haminn.json").write_text("{}")
            cache = {}
            helper.dev_sync(Device(), "app-id", root, ensure_target=False, hash_cache=cache)
            self.assertEqual(2, len(cache))
            with patch.object(Path, "read_bytes", side_effect=AssertionError("unchanged file was reread")):
                result = helper.dev_sync(Device(), "app-id", root, ensure_target=False, hash_cache=cache)
            self.assertEqual("unchanged", result["refreshState"])

    def test_dev_sync_reuses_remote_workspace_snapshot_after_first_sync(self):
        class Device:
            def __init__(self): self.list_calls = 0; self.revision = 3
            def tool(self, name, arguments=None):
                if name == "haminn_list_dev_files":
                    self.list_calls += 1
                    return {"revision": self.revision, "treeHash": "old", "files": [
                        {"path": "haminn.json", "sha256": hashlib.sha256(b'{}').hexdigest()},
                        {"path": "index.html", "sha256": hashlib.sha256(b"old").hexdigest()},
                    ]}
                if name == "haminn_sync_dev_changes":
                    self.revision += 1
                    return {"revision": self.revision, "treeHash": "new", "changedPaths": ["index.html"]}
                raise AssertionError(name)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "haminn.json").write_text("{}")
            (root / "index.html").write_text("old")
            device, hashes, workspace = Device(), {}, {}
            helper.dev_sync(device, "app-id", root, ensure_target=False, hash_cache=hashes, workspace_cache=workspace)
            (root / "index.html").write_text("new")
            result = helper.dev_sync(device, "app-id", root, ensure_target=False, hash_cache=hashes, workspace_cache=workspace)
            self.assertEqual(4, result["revision"])
            self.assertEqual(1, device.list_calls)

    def test_update_dir_promotes_original_instance_and_only_bumps_explicitly(self):
        class Device:
            def __init__(self): self.calls = []; self.get_count = 0
            def tool(self, name, arguments=None):
                arguments = arguments or {}; self.calls.append((name, arguments))
                if name == "haminn_list_apps": return {"apps": [{"appId": "app-id", "happId": "io.example.happ"}]}
                if name == "haminn_runtime_status": return {"appId": "app-id", "launchChannel": "dev"}
                if name == "haminn_list_dev_files": return {"revision": 4, "treeHash": "same", "files": [
                    {"path": "haminn.json", "sha256": hashlib.sha256(b'{\"happId\": \"io.example.happ\", \"version\": {\"code\": 2, \"name\": \"1.0.0\"}, \"entry\": \"index.html\"}').hexdigest()},
                    {"path": "index.html", "sha256": hashlib.sha256(b"<h1>ok</h1>").hexdigest()},
                ]}
                if name == "haminn_get_app":
                    self.get_count += 1
                    return {"appId": "app-id", "activeReleaseId": "new" if self.get_count > 1 else "old",
                            "launchChannel": "stable" if self.get_count > 1 else "dev", "dataGenerationId": "data", "trustRevision": 7}
                if name == "haminn_get_page_state": return {"appId": "app-id"}
                if name == "haminn_list_releases": return {"releases": [{"releaseId": "old", "versionCode": 1}]}
                if name == "haminn_build_dev_package": return {"buildId": "build"}
                if name == "haminn_install_dev_package": return {"releaseId": "new"}
                raise AssertionError(name)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = {"happId": "io.example.happ", "version": {"code": 2, "name": "1.0.0"}, "entry": "index.html"}
            (root / "haminn.json").write_text(json.dumps(source))
            (root / "index.html").write_text("<h1>ok</h1>")
            result = helper.update_dir(Device(), root)
            self.assertEqual("installed", result["status"])
            self.assertTrue(result["dataPreserved"])
            self.assertEqual(source, json.loads((root / "haminn.json").read_text()))

    def test_dev_sync_uses_one_atomic_archive_for_many_binary_changes(self):
        class Device:
            def __init__(self): self.calls = []; self.base = "http://device"
            def tool(self, name, arguments=None):
                self.calls.append((name, arguments or {}))
                if name == "haminn_runtime_status":
                    return {"appId": "app-id", "launchChannel": "dev"}
                if name == "haminn_list_dev_files":
                    return {"revision": 4, "treeHash": "old", "files": []}
                if name == "haminn_replace_dev_tree":
                    return {"url": "http://device/v2/apps/app-id/dev/tree", "headers": {}}
                raise AssertionError(name)
            def request(self, path, method, data, headers):
                return json.dumps({"revision": 5, "changedPaths": ["<archive>"], "refreshState": "scheduled"})
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "index.html").write_text("native")
            (root / "haminn.json").write_text("{}")
            (root / "app" / "assets").mkdir(parents=True)
            (root / "styles").mkdir()
            for index in range(3):
                (root / "app" / "assets" / ("asset-" + str(index) + ".bin")).write_bytes(b"x" * (3 * 1024 * 1024))
            device = Device()
            result = helper.dev_sync(device, "app-id", root)
            self.assertEqual(5, result["revision"])
            self.assertEqual(["haminn_runtime_status", "haminn_get_happ_dev_status", "haminn_list_dev_files", "haminn_replace_dev_tree"],
                             [name for name, _ in device.calls])
            self.assertEqual(4, device.calls[-1][1]["expectedDevRevision"])

    def test_prepare_dev_matches_happ_runs_one_flow_and_waits_for_render(self):
        class Device:
            def __init__(self): self.calls = []
            def tool(self, name, arguments=None):
                self.calls.append((name, arguments or {}))
                if name == "haminn_list_apps":
                    return {"apps": [{"appId": "app-id", "happId": "io.example.happ"}]}
                if name == "haminn_runtime_status": return {"appId": None, "launchChannel": None}
                if name == "haminn_enter_dev_mode":
                    return {"revision": 2, "renderOperationId": "render-1"}
                if name == "haminn_list_dev_files":
                    return {"revision": 2, "treeHash": "same", "files": [
                        {"path": "haminn.json", "sha256": hashlib.sha256(b'{"happId":"io.example.happ"}').hexdigest()}
                    ]}
                if name == "haminn_wait_dev_render": return {"state": "rendered"}
                if name == "haminn_get_page_state": return {"appId": "app-id"}
                raise AssertionError(name)
        with tempfile.TemporaryDirectory() as temp:
            Path(temp, "haminn.json").write_text('{"happId":"io.example.happ"}')
            device = Device()
            result = helper.prepare_dev(device, temp)
            self.assertTrue(result["prepared"])
            self.assertEqual("app-id", result["appId"])
            self.assertEqual("unchanged", result["sync"]["refreshState"])
            self.assertEqual("rendered", result["render"]["state"])
            self.assertNotIn("haminn_get_guide", [name for name, _ in device.calls])

    def test_new_prepare_path_uses_status_and_atomic_hot_update_without_manifest(self):
        class Device:
            def __init__(self): self.calls = []
            def tool(self, name, arguments=None):
                self.calls.append((name, arguments or {}))
                if name == "haminn_list_apps":
                    return {"apps": [{"appId": "app-id", "happId": "io.example.happ"}]}
                if name == "haminn_prepare_happ_development":
                    return {"appId": "app-id", "revision": 4, "treeHash": "old", "devVersion": {"code": 1, "name": "1.0.0"}}
                if name == "haminn_get_happ_dev_status":
                    return {"appId": "app-id", "revision": 4, "treeHash": "old", "devVersion": {"code": 1, "name": "1.0.0"}}
                if name == "haminn_hot_update_happ":
                    return {"appId": "app-id", "revision": 5, "treeHash": "new", "changedPaths": ["index.html"], "refreshState": "scheduled"}
                if name == "haminn_runtime_status":
                    return {"appId": "app-id", "launchChannel": "dev"}
                if name == "haminn_get_page_state": return {"appId": "app-id"}
                raise AssertionError(name)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            root.joinpath("haminn.json").write_text(json.dumps({"happId": "io.example.happ", "version": {"code": 1, "name": "1.0.0"}}))
            root.joinpath("index.html").write_text("<h1>new</h1>")
            device = Device()
            result = helper.prepare_dev(device, root, sync_policy="continue", workspace_cache={})
            self.assertEqual(5, result["sync"]["revision"])
            names = [name for name, _ in device.calls]
            self.assertIn("haminn_get_happ_dev_status", names)
            self.assertIn("haminn_hot_update_happ", names)
            self.assertNotIn("haminn_list_dev_files", names)


    def test_doctor_caches_a_bounded_host_ledger_and_installs_nothing(self):
        with tempfile.TemporaryDirectory() as temp:
            ledger = helper.host_environment(config_root=temp)
            ledger_file = Path(temp) / "host-environment.json"
            self.assertEqual(str(ledger_file), ledger["ledgerFile"])
            self.assertEqual(["host-environment.json"], sorted(entry.name for entry in Path(temp).iterdir()))
            written = json.loads(ledger_file.read_text())
            self.assertEqual(ledger["capabilities"], written["capabilities"])
            self.assertEqual([name for name, _ in helper.HOST_CAPABILITIES],
                             [entry["name"] for entry in ledger["capabilities"]])
            self.assertEqual(sys.executable, ledger["interpreter"])
            for entry in ledger["capabilities"]:
                self.assertTrue(entry["purpose"])
                self.assertEqual(entry["present"], bool(entry.get("path")))


    def test_doctor_resolves_beyond_path_and_never_invents_a_version(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            working = root / "haminn-doctor-probe"
            working.write_text("#!/bin/sh\necho probe 9.9.9\n")
            working.chmod(0o755)
            broken = root / "haminn-doctor-broken"
            broken.write_text("#!/bin/sh\necho 'Unable to locate a runtime.' >&2\nexit 1\n")
            broken.chmod(0o755)
            capabilities = (("haminn-doctor-probe", "Probe a conventional directory"),
                            ("haminn-doctor-broken", "Probe a command whose --version fails"))
            with patch.object(helper, "host_bin_dirs", lambda: [root]), patch.object(helper, "HOST_CAPABILITIES", capabilities):
                ledger = helper.host_environment(config_root=root / "cfg")
            resolved, failing = ledger["capabilities"]
            self.assertTrue(resolved["present"])
            self.assertEqual(str(working), resolved["path"])
            self.assertEqual(str(root), resolved["resolvedVia"])
            self.assertEqual("probe 9.9.9", resolved["version"])
            self.assertTrue(failing["present"])
            self.assertIsNone(failing["version"])
            self.assertIn("--version failed", failing["note"])


if __name__ == "__main__": unittest.main()
