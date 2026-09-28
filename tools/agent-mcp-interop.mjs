// Run only with the debug externalClientInterop instrumentation fixture active.
// The actual device credential stays in memory and is never printed or written locally.
import { execFileSync, spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";
import path from "node:path";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import crypto from "node:crypto";

const sdk = process.env.HAMINN_MCP_SDK_ROOT;
if (!sdk) throw new Error("Set HAMINN_MCP_SDK_ROOT to the installed @modelcontextprotocol/sdk directory");
const { Client } = await import(pathToFileURL(path.join(sdk, "dist/esm/client/index.js")));
const { StreamableHTTPClientTransport } = await import(pathToFileURL(path.join(sdk, "dist/esm/client/streamableHttp.js")));
const adb = (...args) => execFileSync("adb", ["-d", ...args], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
const readConnection = () => JSON.parse(adb("exec-out", "run-as", "life.airen.haminn.debug", "cat", "files/agent-interop.json"));
let config;
for (let i = 0; i < 300; i++) {
  try { config = readConnection(); break; } catch { await new Promise(resolve => setTimeout(resolve, 200)); }
}
assert(config, "Debug fixture unavailable");
console.log("Testing phone LAN endpoint " + config.address + " (credential withheld)");
const clients = [];
async function connect(name, password) {
  const client = new Client({ name, version: "1" }); clients.push(client);
  await client.connect(new StreamableHTTPClientTransport(new URL(config.address + "/mcp"), { requestInit: { headers: { Authorization: "Bearer " + password } } }));
  return client;
}
let temp;
try {
  const discovery = await fetch(config.address + "/.well-known/haminn-agent").then(r => r.json());
  assert.equal(discovery.transport, "streamable-http");
  const one = await connect("computer-one", config.password);
  const two = await connect("computer-two", config.password);
  const [a, b] = await Promise.all([one.listTools(), two.listTools()]);
  assert.equal(a.tools.length, 26); assert.equal(b.tools.length, 26);
  const guide = await one.callTool({ name: "haminn_get_guide", arguments: {} });
  assert(guide.structuredContent.text.includes("six-character"));
  const resource = await two.readResource({ uri: "haminn://page-api" });
  assert(resource.contents[0].text.includes("Haminn"));
  const created = (await one.callTool({ name: "haminn_create_dev_app", arguments: { name: "SDK interoperability", happId: "com.example.sdkinterop", requestId: crypto.randomUUID() } })).structuredContent;
  const patched = await two.callTool({ name: "haminn_apply_dev_files", arguments: { appId: created.appId, expectedDevRevision: created.devWorkspace.revision, requestId: crypto.randomUUID(), refreshMode: "none", files: [{ path: "index.html", content: "<!doctype html><title>SDK native</title><h1>SDK connected</h1>" }] } });
  assert.equal(patched.isError, false);
  const opened = await one.callTool({ name: "haminn_open_app", arguments: { appId: created.appId } });
  assert.equal(opened.structuredContent.state, "opening");

  temp = fs.mkdtempSync(path.join(os.tmpdir(), "haminn-interop-"));
  const source = path.join(temp, "source"); fs.mkdirSync(source);
  fs.writeFileSync(path.join(source, "haminn.json"), JSON.stringify({
    schemaVersion: 1,
    happId: "com.example.sdkinterop",
    name: "SDK interoperability",
    version: "1.0.0",
    entry: "index.html"
  }));
  fs.writeFileSync(path.join(source, "index.html"), "<!doctype html><title>Python ZIP</title><h1>binary directory deployment</h1>");
  fs.writeFileSync(path.join(source, "sample.bin"), Buffer.from([0, 1, 2, 255]));
  const helper = path.resolve("app/src/main/assets/agent/haminn-agent.py");
  const py = spawnSync("python3", [helper, "--address", config.address, "sync-dir", created.appId, source], { encoding: "utf8", env: { ...process.env, HAMINN_PASSWORD: config.password, HAMINN_CONFIG_HOME: path.join(temp, "credentials") } });
  assert.equal(py.status, 0, py.stderr);
  assert(JSON.parse(py.stdout).revision);
  const read = await one.callTool({ name: "haminn_read_dev_file", arguments: { appId: created.appId, path: "index.html" } });
  assert(read.structuredContent.content.includes("directory deployment"));

  adb("shell", "run-as", "life.airen.haminn.debug", "touch", "files/agent-interop.rotate");
  let changed;
  for (let i = 0; i < 50; i++) { changed = readConnection(); if (changed.password !== config.password) break; await new Promise(resolve => setTimeout(resolve, 100)); }
  assert(changed.password !== config.password, "Password rotation did not complete");
  await assert.rejects(() => one.listTools());
  await assert.rejects(() => two.listTools());
  const three = await connect("new-password-computer", changed.password);
  assert.equal((await three.listTools()).tools.length, 26);
  console.log("PASS: real LAN MCP SDK initialize, two concurrent password-sharing clients, tools/resources, create/patch/open, Python binary ZIP publish, and live password rotation invalidating both old clients.");
} finally {
  await Promise.allSettled(clients.map(client => client.close()));
  adb("shell", "run-as", "life.airen.haminn.debug", "touch", "files/agent-interop.done");
  if (temp) fs.rmSync(temp, { recursive: true, force: true });
}
