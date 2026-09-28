import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";

test("release identity and protocol major are frozen", () => {
  const build = fs.readFileSync("app/build.gradle.kts", "utf8");
  const caps = JSON.parse(fs.readFileSync("api/capabilities.json", "utf8"));
  assert.match(build, /applicationId = "life\.airen\.haminn"/);
  assert.match(build, /minSdk = 29/);
  assert.equal(caps.apiMajor, 1);
});

test("happ display policy is package-scoped and restored outside the happ", () => {
  const schema = JSON.parse(fs.readFileSync("api/haminn.schema.json", "utf8"));
  const manifest = fs.readFileSync("app/src/main/java/life/airen/haminn/install/PackageManifest.kt", "utf8");
  const activity = fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8");
  assert.deepEqual(schema.properties.display.properties.orientation.enum, ["unspecified", "portrait", "landscape"]);
  assert.deepEqual(schema.properties.display.properties.keyboard.enum, ["resize", "overlay"]);
  assert.match(manifest, /displayOrientation/);
  assert.match(manifest, /keyboardMode/);
  assert.match(activity, /SOFT_INPUT_ADJUST_NOTHING/);
  assert.match(activity, /SCREEN_ORIENTATION_PORTRAIT/);
  assert.match(activity, /if \(!keyboardOverlaysContent\) types = types or WindowInsetsCompat\.Type\.ime\(\)/);
  assert.match(activity, /showSupportBrowser\(\)[\s\S]*?applyDisplayPolicy\(null, forcePortrait = true\)/);
});

test("bridge keeps the isolated transport and an explicit legacy fallback", () => {
  const bridge = fs.readFileSync("app/src/main/assets/bridge/haminn-v1.js", "utf8");
  const native = fs.readFileSync("app/src/main/java/life/airen/haminn/bridge/BridgeController.kt", "utf8");
  const legacy = fs.readFileSync("app/src/main/java/life/airen/haminn/bridge/LegacyBridgeController.kt", "utf8");
  const bootstrap = fs.readFileSync("app/src/main/assets/bridge/legacy-bootstrap.js", "utf8");
  const activity = fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8");
  const devRuntime = activity.slice(activity.indexOf("val devRuntime"), activity.indexOf("val sessionSdk"));
  assert.match(bridge, /kind: "hello"/);
  assert.match(native, /addWebMessageListener/);
  assert.match(native, /isMainFrame/);
  assert.match(native, /MAX_IN_FLIGHT = 16/);
  assert.match(bridge, /MAX_PENDING = 16/);
  assert.match(bridge, /audio: namespace\("audio"\)/);
  assert.doesNotMatch(native, /addJavascriptInterface/);
  assert.match(legacy, /addJavascriptInterface/);
  assert.match(legacy, /weakly isolated/);
  assert.match(bootstrap, /__haminnLegacyNativeV1/);
  assert.doesNotMatch(devRuntime, /\?\./, "the injected dev runtime must parse on supported legacy OEM WebViews");
  assert.ok(devRuntime.includes(String.raw`replace(/^\//,'')`), "the dev CSS path regex must emit one escaped slash");
});

test("backup contract excludes ambient trust state", () => {
  const source = fs.readFileSync("app/src/main/java/life/airen/haminn/backup/BackupCoordinator.kt", "utf8");
  for (const excluded of ["webProfile", "cookies", "webStorage", "permissions", "developerTokens"]) assert.ok(source.includes(excluded));
});

test("a happ can back itself up but never chooses whose data leaves", () => {
  const caps = JSON.parse(fs.readFileSync("api/capabilities.json", "utf8"));
  const activity = fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8");
  const bridge = fs.readFileSync("app/src/main/assets/bridge/haminn-v1.js", "utf8");
  assert.ok(caps.public.app.includes("backup"), "self-backup must stay a public app capability");
  const start = activity.indexOf('"app.backup" ->');
  const end = activity.indexOf('"system.language" ->');
  assert.ok(start > 0 && end > start, "the public self-backup method must be dispatched");
  const branch = activity.slice(start, end);
  assert.match(branch, /requireApp\(app\)/, "the target instance must come from the calling session");
  assert.doesNotMatch(branch, /params\./, "the caller must not be able to steer the export");
  assert.match(branch, /createBackupDocument\(/, "the destination must come from the system picker");
  assert.match(branch, /backup\.export\(target\.appId, uri\)/, "the unified single-happ export must do the packaging");
  assert.match(branch, /developmentServer\.stop\(/, "an active deployment must stop before a consistent export");
  assert.ok(bridge.includes(String.raw`app\.backup`), "the picker plus archive write needs the long bridge timeout");
});

test("all upstream free font assets referenced by the bundled CSS are present", () => {
  const root = "app/src/main/assets/shared/fontawesome";
  const css = fs.readFileSync(root + "/css/all.min.css", "utf8");
  const files = [...css.matchAll(/url\((?:['"])?\.\.\/webfonts\/([^)'"?]+)(?:[^)]*)\)/g)].map(match => match[1]);
  assert.ok(files.length >= 3);
  for (const file of files) assert.equal(fs.readFileSync(root + "/webfonts/" + file).subarray(0, 4).toString(), "wOF2");
  assert.ok(fs.readFileSync(root + "/LICENSE.txt", "utf8").includes("SIL"));
});

test("agent catalog, guide snapshots and shared-password authority stay aligned", () => {
  const root = "app/src/main/assets/agent/";
  const tools = JSON.parse(fs.readFileSync(root + "tools.json", "utf8"));
  const guide = fs.readFileSync(root + "haminn-dev-plugin/SKILL.md", "utf8");
  const server = fs.readFileSync("app/src/main/java/life/airen/haminn/deploy/AgentDevelopmentServer.kt", "utf8");
  const discovery = server.slice(server.indexOf("private fun bootstrap()"), server.indexOf("private fun bootstrapHtml()"));
  assert.equal(new Set(tools.map(tool => tool.name)).size, 37);
  const toolIndex = tools.map(({ name, description }) => ({ name, description }));
  assert.ok(Buffer.byteLength(JSON.stringify(toolIndex)) < 8 * 1024, "the public tool index must stay compact and schema-free");
  assert.deepEqual(Object.keys(toolIndex[0]).sort(), ["description", "name"]);
  assert.ok(Buffer.byteLength(guide) < 12 * 1024, "the default device guide must remain a short, cacheable operating guide");
  assert.match(guide, /~\/haminn\/happ-dev\.json/);
  assert.match(guide, /happ-<happId-with-dots-replaced-by-hyphens>/);
  assert.match(guide, /one `happId` has one active local directory/);
  assert.match(guide, /does not use those notes to select, compare or synchronize code/);
  assert.match(discovery, /put\("schema", 3\)/);
  assert.match(discovery, /put\("resourceDigests", resourceDigests\)/);
  assert.match(discovery, /put\("toolIndex", toolIndex\(\)\)/);
  assert.doesNotMatch(discovery, /put\("tools", catalog\)/);
  assert.match(server, /haminn:\/\/tool\/\{name\}/);
  assert.deepEqual(tools.find(tool => tool.name === "haminn_get_guide").inputSchema.properties, {});
  assert.deepEqual(tools.find(tool => tool.name === "haminn_reload_shell").inputSchema.properties.runtimeMode.enum, ["current", "online", "local"]);
  assert.deepEqual(tools.find(tool => tool.name === "haminn_reload_app").inputSchema.properties.strategy.enum, ["reload", "recreate"]);
  assert.equal(tools.find(tool => tool.name === "haminn_capture_screen").inputSchema.properties.maxEdge.maximum, 2048);
  assert.ok(tools.find(tool => tool.name === "haminn_reload_app").inputSchema.properties.postReloadScript);
  assert.equal(tools.find(tool => tool.name === "haminn_reload_shell").inputSchema.properties.postReloadScript, undefined);
  assert.equal(tools.find(tool => tool.name === "haminn_list_apps").inputSchema.properties.includeIcons.type, "boolean");
  assert.equal(tools.find(tool => tool.name === "haminn_list_apps").inputSchema.properties.happId.type, "string");
  assert.equal(tools.find(tool => tool.name === "haminn_get_app").inputSchema.properties.includeIcons.type, "boolean");
  assert.match(server, /"haminn_reload_shell"\s*->\s*ui\("reload-shell",\s*args,\s*authorization\)/);
  assert.match(server, /"haminn_capture_screen"\s*->\s*ui\("capture-screen",\s*args,\s*authorization\)/);
  assert.match(server, /Log\.i\(TAG, "dev-operation tool=\$tool app=.*result=/);
  assert.match(server, /put\("type", "image"\).*put\("mimeType"/s);
  assert.match(server, /includeIcons/);
  assert.match(server, /authorization\.isNullOrBlank\(\).*authenticationRequired\(\)/);
  assert.match(server, /put\("error", "authentication_required"\).*put\("reason".*put\("nextAction", "ask_user_for_current_password"/s);
  assert.match(server, /haminn-agent-bootstrap/);
  assert.match(discovery, /put\("packageFormat", "codex-plugin-archive-v1"\)/);
  assert.match(discovery, /put\("nativeCodexPlugin", true\)/);
  assert.match(discovery, /atomic_replace_if_hash_differs/);
  assert.match(discovery, /register_mcp_then_authenticate/);
  assert.match(discovery, /codex", "plugin", "add", "\$PLUGIN_ID@personal/);
  assert.match(server, /ZipEntry\(name\)\.apply \{ time = 0L \}/);
  assert.match(server, /packageSha256/);
  assert.doesNotMatch(server, /\/pw\/|password.*(?:path|query|fragment)/i);
  for (const tool of tools) {
    const dispatch = new RegExp(`(?:"[^"]+"\\s*,\\s*)*"${tool.name}"(?:\\s*,\\s*"[^"]+")*\\s*->`);
    assert.match(server, dispatch);
    assert.equal(tool.inputSchema.additionalProperties, false);
  }
  assert.doesNotMatch(server, /class Pairing|class Client|pairUrl|phone-approved/);
  assert.match(server, /putBoolean\("enabled", true\)/);
  assert.match(server, /restoreIfEnabled/);
  assert.match(server, /NETWORK_MONITOR_MS = 15_000L/);
  assert.match(server, /blockedPrefixes = listOf\("rmnet", "ccmni", "pdp", "wwan", "tun", "dummy"\)/);
  assert.doesNotMatch(server, /IDLE_MS|空闲 30 分钟，开发连接已关闭/);
  assert.match(server, /commitGuard = \{ guarded\(authorization/);
  assert.match(server, /guardedValue\(authorization\) \{\s*devWorkspaces\.apply/);
  const activity = fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8");
  assert.match(activity, /"host\.agent\.refresh" -> haminnApp\.agentServer\.refreshNetwork\(\)/);
  const onStop = activity.match(/override fun onStop\(\) \{([\s\S]*?)super\.onStop\(\)/)?.[1] || "";
  assert.doesNotMatch(onStop, /agentServer\.stop/);
  assert.match(activity, /page state only allows current running happ development copy|页面状态只允许读取当前运行的 happ 开发副本/);
  assert.match(activity, /refresh only allows scheduling current running happ development copy|刷新只允许调度当前运行的 happ 开发副本/);
  assert.match(activity, /val strategy = args\.optString\("strategy", RELOAD_IN_PLACE\)/);
  assert.match(activity, /applyPendingAgentReload\(view, currentSession\)/);
  assert.match(activity, /window\.haminnDevState/);
  assert.equal(fs.readFileSync(root + "webapp-authoring.md", "utf8"), fs.readFileSync("docs/webapp-authoring.md", "utf8"));
  assert.equal(fs.readFileSync(root + "haminn-api.d.ts", "utf8"), fs.readFileSync("sdk/haminn-api.d.ts", "utf8"));
  const helper = fs.readFileSync(root + "haminn-agent.py", "utf8");
  assert.match(helper, /reuse the exact happId binding in ~\/haminn\/happ-dev\.json/);
  assert.match(helper, /never scan the disk or silently create a second copy/);
});

function shellSources(extension, directory = "app/src/main/assets/store") {
  return fs.readdirSync(directory, { withFileTypes: true }).map(entry => {
    const file = directory + "/" + entry.name;
    return entry.isDirectory() ? shellSources(extension, file) : file.endsWith(extension) ? fs.readFileSync(file, "utf8") : "";
  }).join("\n");
}

test("store navigation, favorites, support fallback and QR bridge remain wired", () => {
  const html = fs.readFileSync("app/src/main/assets/store/index.html", "utf8");
  const script = shellSources(".js");
  const css = shellSources(".css");
  const tabs = [...html.matchAll(/<nav class="bottom-nav[^"]*"[\s\S]*?<\/nav>/g)][0]?.[0] || "";
  assert.deepEqual([...tabs.matchAll(/data-view="([^"]+)"/g)].map(match => match[1]), ["favorites", "development", "settings", "support"]);
  assert.equal((tabs.match(/class="nav-icon"/g) || []).length, 4);
  assert.match(css, /grid-template-columns:repeat\(4,minmax\(0,1fr\)\)/);
  assert.match(script, /"apps\.favorite"/);
  assert.match(script, /activeVersion/);
  assert.match(fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8"), /\.put\("activeVersion"/);
  assert.match(script, /"apps\.scanQr"/);
  assert.match(script, /"support\.open"/);
  assert.match(html, /10knet·zhyuzh3d/);
  for (const id of ["addZip", "addUrl", "scanQr", "libraryTabs", "topApkVersion", "topWebVersion"]) assert.match(html, new RegExp(`id="${id}"`));
  assert.doesNotMatch(html, /id="addFolder"/);
  assert.doesNotMatch(script, /"apps\.pickDirectory"/);
  assert.doesNotMatch(script, /"apps\.confirmDirectory"/);
  assert.match(script, /"apps\.pickIcon"/);
  assert.match(css, /bottom-nav \.nav-icon\{margin:0 auto 4px!important\}/);
  assert.match(css, /\.agent-address-row \{ display:block;/);
  assert.match(css, /\.agent-url \{[^}]*white-space:nowrap;/);
  assert.match(css, /\.agent-secret button \{[^}]*align-self:flex-end;[^}]*height:48px;/);
});

test("package defaults and custom happ icon overrides stay distinct", () => {
  const activity = fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8");
  const installer = fs.readFileSync("app/src/main/java/life/airen/haminn/install/InstallCoordinator.kt", "utf8");
  const processor = fs.readFileSync("app/src/main/java/life/airen/haminn/install/IconProcessor.kt", "utf8");
  const registry = fs.readFileSync("app/src/main/java/life/airen/haminn/registry/AppRegistry.kt", "utf8");
  const shortcuts = fs.readFileSync("app/src/main/java/life/airen/haminn/launcher/ShortcutHost.kt", "utf8");
  assert.match(activity, /"host\.apps\.updatePresentation"/);
  assert.match(activity, /decodePngDataUrl/);
  assert.match(activity, /put\("preview", iconSourceDataUrl\(uri\)\)/);
  assert.match(installer, /IconProcessor\.centeredPngBytes/);
  assert.match(installer, /iconUrl = null/);
  assert.match(installer, /defaultIconUrl = packageIconBytes/);
  assert.match(installer, /registry\.updateDefaultIcon\(appId, packageIconBytes\)/);
  assert.match(processor, /OUTPUT_SIZE = 192/);
  assert.match(registry, /fun updatePresentation\(appId: String, name: String, iconBytes: ByteArray\? = null/);
  assert.match(registry, /put\("icon_url", iconUrl\)/);
  assert.match(registry, /put\("default_icon_url", defaultIconUrl\)/);
  assert.match(shortcuts, /instance\.effectiveIconUrl/);
  assert.match(shortcuts, /Icon::createWithBitmap/);
  assert.match(shortcuts, /manager\.pinnedShortcuts/);
  assert.match(shortcuts, /enum class PinState/);
  assert.match(activity, /put\("desktopShortcutState"/);
  assert.match(activity, /new Event\('haminnresume'\)/);
});

test("host media uses object URLs and keeps inline bytes transient", () => {
  const imageStore = fs.readFileSync("app/src/main/java/life/airen/haminn/data/HostImageStore.kt", "utf8");
  const fileStore = fs.readFileSync("app/src/main/java/life/airen/haminn/data/FileStore.kt", "utf8");
  const gateway = fs.readFileSync("app/src/main/java/life/airen/haminn/runtime/ObjectAssetGateway.kt", "utf8");
  const registry = fs.readFileSync("app/src/main/java/life/airen/haminn/registry/AppRegistry.kt", "utf8");
  assert.match(imageStore, /objects\/images/);
  assert.match(imageStore, /objectUrl/);
  assert.match(fileStore, /object_url TEXT NOT NULL/);
  assert.match(fileStore, /put\("url", url\)/);
  assert.match(gateway, /private object URLs/);
  assert.match(gateway, /dataGeneration/);
  assert.doesNotMatch(registry, /icon_data_url|default_icon_data_url/);
});

test("device happ sharing is a bounded one-hour file session with explicit install confirmation", () => {
  const manager = fs.readFileSync("app/src/main/java/life/airen/haminn/share/HappShareManager.kt", "utf8");
  const service = fs.readFileSync("app/src/main/java/life/airen/haminn/share/HappShareService.kt", "utf8");
  const gateway = fs.readFileSync("app/src/main/java/life/airen/haminn/runtime/HappShareAssetGateway.kt", "utf8");
  const activity = fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8");
  const manifest = fs.readFileSync("app/src/main/AndroidManifest.xml", "utf8");
  const html = fs.readFileSync("app/src/main/assets/store/index.html", "utf8");
  const ui = fs.readFileSync("app/src/main/assets/store/features/share.js", "utf8");
  const host = fs.readFileSync("app/src/main/assets/store/platform/host.js", "utf8");
  const schema = JSON.parse(fs.readFileSync("api/haminn.schema.json", "utf8"));
  assert.match(manager, /SESSION_TTL_MS = 60L \* 60 \* 1000/);
  assert.match(manager, /nextInt\(1_000_000\).*padStart\(6, '0'\)/);
  assert.match(manager, /scheme\("haminn"\)\.authority\("share"\)/);
  for (const key of ["v", "u", "p"]) assert.match(manager, new RegExp(`appendQueryParameter\\("${key}"`));
  for (const path of ["/manifest", "/icon", "/package"]) assert.ok(manager.includes(`"${path}"`));
  assert.match(manager, /Authorization", "Bearer \$password"/);
  assert.match(manager, /cacheDir, "shared\/happ-share/);
  assert.match(manager, /snapshotKind = "development"/);
  assert.match(manager, /snapshotKind = "release"/);
  assert.match(manager, /blockedPrefixes = listOf\("rmnet", "ccmni", "pdp", "wwan", "tun", "dummy"\)/);
  assert.match(service, /startForeground/);
  assert.match(service, /停止分享/);
  assert.match(gateway, /short-lived QR and preview files/);
  assert.match(manifest, /android:foregroundServiceType="dataSync"/);
  for (const method of ["shareStart", "shareSave", "shareSend", "shareStop", "shareInstall"]) {
    assert.match(activity, new RegExp(`"host\\.apps\\.${method}"`));
    assert.ok(host.includes(`"apps.${method}"`));
  }
  for (const id of ["sharePanel", "shareInstallPanel", "shareQr", "shareCreateShortcut", "installSharedHapp", "shareNetworkHint"]) {
    assert.match(html, new RegExp(`id="${id}"`));
  }
  assert.match(ui, /createShortcut:state\.shareCreateShortcut/);
  // One hint under the QR code, and its wording carries the one-hour validity itself.
  assert.equal(html.includes('id="shareExpiry"'), false);
  assert.match(ui, /让朋友使用Haminn应用扫码即可安装同款应用。二维码1小时有效。/);
  assert.match(ui, /未检测到可用局域网/);
  assert.equal((ui.match(/二维码已包含六位分享密码/g) || []).length, 0);
  // The exported package is named after the happ and its version, never package.zip:
  // the file on disk keeps that name, so the save dialog and the system share sheet
  // (which shows the FileProvider file name) both offer it.
  assert.match(manager, /private fun packageFileName\(name: String, version: String\?\): String/);
  assert.match(manager, /if \(clean\.startsWith\("v", true\)\) clean else "v\$clean"/);
  assert.match(manager, /val fileName = packageFileName\(app\.name, versionLabel\)/);
  assert.match(manager, /if \(!staging\.renameTo\(archive\)\)/);
  assert.match(manager, /"\/package" -> file\("application\/zip", archive, downloadName\)/);
  assert.match(manager, /val value = Outbound\(id, appId, app\.name, fileName, archive/);
  assert.equal((activity.match(/haminnApp\.happShare\.packageFile\(/g) || []).length, 2);
  assert.match(activity, /"host\.apps\.shareSave"[\s\S]{0,200}createFileDocument\(fileName\)/);
  assert.match(activity, /"host\.apps\.shareSend"[\s\S]{0,300}FileProvider\.getUriForFile/);
  assert.equal(schema.properties.author.maxLength, 80);
});

test("HaminnUI follows the system language, keeps a manual override, and avoids input focus for external installs", () => {
  const html = fs.readFileSync("app/src/main/assets/store/index.html", "utf8");
  const i18n = fs.readFileSync("app/src/main/assets/store/core/i18n.js", "utf8");
  const settings = fs.readFileSync("app/src/main/assets/store/features/settings.js", "utf8");
  const install = fs.readFileSync("app/src/main/assets/store/features/install.js", "utf8");
  const ui = fs.readFileSync("app/src/main/assets/store/components/ui.js", "utf8");
  for (const value of ["system", "zh-CN", "en"]) assert.match(html, new RegExp(`data-language-choice="${value}"`));
  assert.match(i18n, /STORAGE_KEY = "haminn\.language"/);
  assert.match(i18n, /toLowerCase\(\) === "zh" \? "zh-CN" : "en"/);
  assert.match(i18n, /window\.haminn\.system\.language\(\)/);
  assert.match(settings, /H\.i18n\.setPreference\(button\.dataset\.languageChoice\)/);
  assert.match(ui, /element\.querySelector\("\[data-close\]"\) \|\| focusable\(element\)\[0\]/);
  assert.match(install, /if \(!value\.url && !value\.token && !value\.scanned && !value\.shared\)/);
  const shareInstall = html.slice(html.indexOf('id="shareInstallPanel"'), html.indexOf('id="managePanel"'));
  assert.doesNotMatch(shareInstall, /<input\b/);
});

test("development password is read only outside a deliberate modal save", () => {
  const html = fs.readFileSync("app/src/main/assets/store/index.html", "utf8");
  const development = fs.readFileSync("app/src/main/assets/store/features/development.js", "utf8");
  assert.match(html, /id="agentPassword"[^>]*readonly/);
  assert.match(html, /id="editAgentPassword"/);
  assert.match(html, /id="agentPasswordPanel"/);
  assert.match(html, /id="agentPasswordDraft"[^>]*pattern="\[0-9\]\{6\}"/);
  assert.match(html, /id="randomAgentPassword"/);
  assert.match(development, /crypto\.getRandomValues\(random\)/);
  assert.match(development, /host\.call\("agent\.resetPassword", \{ password \}\)/);
  assert.match(development, /close\("#agentPasswordPanel"\)/);
  assert.doesNotMatch(development, /#agentPassword"\)\.oninput/);
});

test("development page names the plugin install address and logs operations instead of showing them", () => {
  const html = fs.readFileSync("app/src/main/assets/store/index.html", "utf8");
  const development = fs.readFileSync("app/src/main/assets/store/features/development.js", "utf8");
  const server = fs.readFileSync("app/src/main/java/life/airen/haminn/deploy/AgentDevelopmentServer.kt", "utf8");
  assert.ok(html.includes("请把下面的插件地址粘贴给你的智能体开发软件（如WorkBuddy、Codex等），并要求它从这个地址安装插件。安装完毕后，就可以要求它进入特定Happ应用开发模式进行修改和安装更新。"));
  assert.ok(html.includes('<span class="field-label">Happ开发插件安装地址</span>'));
  assert.ok(html.includes('<span class="field-label">备用安装地址（USB连接）</span>'));
  assert.equal(html.includes('id="agentEvents"'), false, "recent activity panel must stay removed");
  assert.doesNotMatch(development, /agentEvents/);
  assert.doesNotMatch(server, /MAX_RECENT_OPERATIONS|endpoint\.events/);
  assert.match(server, /private const val TAG = "HaminnAgentDev"/);
  assert.match(server, /Log\.i\(TAG, "dev-operation tool=\$tool app=/);
});

test("launcher uses the compressed Haminn brand icon while notifications keep a monochrome glyph", () => {
  const foreground = fs.readFileSync("app/src/main/res/drawable/ic_launcher_foreground.xml", "utf8");
  const notifications = fs.readFileSync("app/src/main/java/life/airen/haminn/notification/NotificationDispatcher.kt", "utf8");
  const icon = fs.statSync("app/src/main/res/drawable-nodpi/haminn_icon.webp");
  const colors = fs.readFileSync("app/src/main/res/values/colors.xml", "utf8");
  assert.match(foreground, /@drawable\/haminn_icon/);
  const appManifest = fs.readFileSync("app/src/main/AndroidManifest.xml", "utf8");
  assert.match(appManifest, /android:icon="@drawable\/haminn_icon"/);
  assert.doesNotMatch(foreground, /android:inset/);
  assert.match(notifications, /R\.drawable\.ic_notification/);
  assert.match(colors, /name="haminn_icon_background">#000000/);
  assert.ok(icon.size < 32 * 1024, `launcher icon is unexpectedly large: ${icon.size}`);
  assert.equal(fs.existsSync("app/src/main/res/drawable-night-nodpi/haminn_icon.webp"), false);
  assert.ok(fs.statSync("app/src/main/assets/store/assets/haminn-mark.svg").size < 2 * 1024);
});

test("official HaminnUI is portrait-only while installed happs retain manifest orientation", () => {
  const activity = fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8");
  assert.match(activity, /applyDisplayPolicy\(packageManifest, forcePortrait = instance == null\)/);
  assert.match(activity, /forcePortrait -> ActivityInfo\.SCREEN_ORIENTATION_PORTRAIT/);
  assert.match(activity, /manifest\?\.displayOrientation == "landscape" -> ActivityInfo\.SCREEN_ORIENTATION_LANDSCAPE/);
});

test("online entry separates live pages from direct packages and install descriptors", () => {
  const installer = fs.readFileSync("app/src/main/java/life/airen/haminn/install/RemoteSourceInstaller.kt", "utf8");
  const repository = fs.readFileSync("app/src/main/java/life/airen/haminn/install/RepositorySource.kt", "utf8");
  const bridge = fs.readFileSync("app/src/main/assets/bridge/haminn-v1.js", "utf8");
  assert.match(installer, /encodedPath\.endsWith\("\.zip", ignoreCase = true\)/);
  assert.match(installer, /isZipArchive\(candidate\)/);
  assert.match(installer, /equals\("haminn-install\.json", ignoreCase = true\)/);
  assert.match(installer, /readInstallManifest\(repositoryRawUrl/);
  assert.match(installer, /directoryChoice\(resolved, choices\)/);
  assert.match(installer, /GITHUB_GATEWAY = "https:\/\/haminn\.airen\.life\/_repo\/github"/);
  assert.match(installer, /githubRepositoryFiles\(resolved, revision\)/);
  assert.match(bridge, /host\\\.apps\\\.\(\?:installOnline\|inspectUrl\|inspectZip\|confirmInspect\|importZip/);
  assert.match(repository, /GITHUB\("github"/);
  assert.match(repository, /GITLAB\("gitlab"/);
  assert.match(repository, /GITEE\("gitee"/);
  for (const path of ["dist", "build", "release", "web", "public"]) assert.match(repository, new RegExp(`"${path}"`));
  assert.match(installer, /OnlineInstallResult\(app\.appId, null, "live", "live"\)/);
  assert.match(installer, /"online-descriptor"/);
});

test("add flow describes a package before installing and only installs on confirmation", () => {
  const activity = fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8");
  const coordinator = fs.readFileSync("app/src/main/java/life/airen/haminn/install/InstallCoordinator.kt", "utf8");
  const installer = fs.readFileSync("app/src/main/java/life/airen/haminn/install/RemoteSourceInstaller.kt", "utf8");
  const capabilities = fs.readFileSync("api/capabilities.json", "utf8");
  const bridge = fs.readFileSync("app/src/main/assets/bridge/haminn-v1.js", "utf8");
  const shell = fs.readFileSync("app/src/main/assets/store/features/install.js", "utf8");
  const host = fs.readFileSync("app/src/main/assets/store/platform/host.js", "utf8");
  for (const method of ['"host.apps.inspectUrl"', '"host.apps.inspectZip"', '"host.apps.confirmInspect"']) {
    assert.ok(activity.includes(method), `Native dispatch missing: ${method}`);
  }
  assert.match(capabilities, /"inspectUrl", "inspectZip", "confirmInspect"/);
  for (const method of ['"apps.inspectUrl"', '"apps.inspectZip"', '"apps.confirmInspect"']) {
    assert.ok(host.includes(method), `HaminnUI host whitelist missing: ${method}`);
  }
  assert.match(coordinator, /fun describePackage\(file: File\): PackageDescription/);
  assert.match(coordinator, /fun installPackageFile\(/);
  assert.match(installer, /suspend fun previewOnline\(url: String\): SourcePreview/);
  assert.match(installer, /data class SourcePreview/);
  // The add sheet resolves first and needs a second tap before anything is installed.
  assert.match(shell, /host\.call\("apps\.inspectZip", \{\}\)/);
  assert.match(shell, /host\.call\("apps\.inspectUrl", \{ url:onlineUrl, insecureConfirmed:!!common\.insecureConfirmed \}\)/);
  assert.match(shell, /host\.call\("apps\.confirmInspect", Object\.assign\(common, \{ token:draft\.token \}\)/);
  assert.match(shell, /function showPackageFields\(preview\)/);
  assert.equal(shell.includes('host.call("apps.importZip"'), false, "ZIP must not install without confirmation");
});

test("the happ settings sheet acts immediately and keeps development read only", () => {
  const activity = fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8");
  const registry = fs.readFileSync("app/src/main/java/life/airen/haminn/registry/AppRegistry.kt", "utf8");
  const installer = fs.readFileSync("app/src/main/java/life/airen/haminn/install/RemoteSourceInstaller.kt", "utf8");
  const capabilities = fs.readFileSync("api/capabilities.json", "utf8");
  const bridge = fs.readFileSync("app/src/main/assets/bridge/haminn-v1.js", "utf8");
  const manage = fs.readFileSync("app/src/main/assets/store/features/manage.js", "utf8");
  const sheet = fs.readFileSync("app/src/main/assets/store/index.html", "utf8");
  const host = fs.readFileSync("app/src/main/assets/store/platform/host.js", "utf8");
  // Promoting the development workspace is the only development transition HaminnUI may drive.
  assert.ok(activity.includes('"host.apps.promoteDev"'), "Native dispatch missing: host.apps.promoteDev");
  assert.match(capabilities, /"enterDev", "leaveDev", "promoteDev", "resetDev", "exportDev"/);
  assert.match(bridge, /\|promoteDev\|/);
  for (const method of ['"apps.promoteDev"', '"apps.leaveDev"']) assert.ok(host.includes(method), `HaminnUI host whitelist missing: ${method}`);
  for (const method of ['"apps.enterDev"', '"apps.resetDev"', '"apps.exportDev"']) {
    assert.equal(host.includes(method), false, `HaminnUI must not offer ${method}`);
  }
  assert.match(activity, /devWorkspaces\.installed\(appId, revision, installed\.releaseId, keepDev = false\)/);
  // Every control applies on its own; only the name keeps an explicit save button.
  assert.equal(sheet.includes('id="saveApp"'), false, "the batch save bar must be gone");
  assert.ok(sheet.includes('id="saveAppName"'));assert.ok(sheet.includes('id="switchToStable"'));
  assert.ok(sheet.includes('id="liveUrl"'));assert.equal(sheet.includes('id="editUrl"'), false, "liveUrl must be read only");
  assert.equal(sheet.includes('id="runDevApp"'), false);assert.equal(sheet.includes('id="resetDevApp"'), false);
  assert.equal(fs.existsSync("app/src/main/assets/store/features/app-settings.js"), false, "the batch save module must be gone");
  for (const method of ["apps.setNotificationEnabled", "apps.setCrossOriginNetwork", "apps.setRuntimeMode", "apps.updatePresentation"]) {
    assert.ok(manage.includes(`applyChange("${method}"`), `immediate apply missing: ${method}`);
  }
  assert.match(manage, /apps\.promoteDev/);
  assert.match(manage, /apps\.shareStart", \{ appId: app\.appId, network: false \}/);
  // A package imported from a local file keeps a copy so it can be reinstalled later.
  assert.match(registry, /fun recordLocalSource\(appId: String, sourcePath: String\?, retainedPath: String\?\)/);
  assert.match(registry, /"source_path" to "TEXT", "source_uri" to "TEXT"/);
  // New columns only reach an existing installation when the schema version moves,
  // because onUpgrade is skipped while the file already carries the current version.
  assert.match(registry, /private const val VERSION = 14/);
  assert.match(installer, /val retained = app\.sourceUri\?\.let\(::File\)\?\.takeIf \{ it\.isFile \}/);
});

test("official shell keeps live fallback support and explicit local refresh", () => {
  const activity = fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8");
  const manager = fs.readFileSync("app/src/main/java/life/airen/haminn/runtime/OfficialShellManager.kt", "utf8");
  const bridge = fs.readFileSync("app/src/main/assets/bridge/haminn-v1.js", "utf8");
  const html = fs.readFileSync("app/src/main/assets/store/index.html", "utf8");
  const script = shellSources(".js");
  assert.match(manager, /OFFICIAL_ORIGIN = "https:\/\/haminn\.airen\.life"/);
  assert.match(manager, /ONLINE_URL = "\$OFFICIAL_ORIGIN\/shell\/index\.html"/);
  assert.match(manager, /Mode\.ONLINE/);
  assert.match(activity, /RuntimeRole\.STORE/);
  assert.match(activity, /LOAD_NO_CACHE/);
  assert.match(activity, /正在加载官网界面/);
  assert.match(activity, /local-fallback/);
  assert.match(bridge, /shell: namespace\("host\.shell"\)/);
  for (const id of ["shellVersionSwitch", "useLocalShell", "updateLocalShell"]) assert.match(html, new RegExp(`id="${id}"`));
  assert.match(script, /"shell\.setMode"/);
  assert.match(script, /"shell\.updateLocal"/);
  assert.match(manager, /KEY_MODE, Mode\.LOCAL\.value/);
  assert.match(manager, /downloadedVersion < embeddedVersion/);
  assert.match(manager, /bundleSha256/);
  assert.match(manager, /官网界面包摘要不匹配/);
  assert.match(manager, /store\/manifest\.json/);
  assert.match(manager, /suspend fun activateOnline/);
  assert.match(activity, /officialShell\.activateOnline\(\)/);
  assert.match(activity, /officialShell\.setMode\(OfficialShellManager\.Mode\.LOCAL\.value\)[\s\S]{0,160}forceLocalStoreOnce = true/);
  assert.match(activity, /onlineStore -> StatusBarStyle\.LIVE/);
  assert.match(activity, /"reload-shell"/);
  assert.match(fs.readFileSync("app/src/main/assets/agent/tools.json", "utf8"), /"name": "haminn_reload_shell"/);
  assert.match(activity, /"current" -> haminnApp\.officialShell\.mode\(\)/);
  const application = fs.readFileSync("app/src/main/java/life/airen/haminn/HaminnApplication.kt", "utf8");
  assert.match(application, /if \(replaced\) \{[\s\S]{0,160}officialShell\.resetToEmbedded\(\)[\s\S]{0,160}agentServer\.disable/);
  assert.doesNotMatch(application, /installState\.edit[\s\S]{0,160}officialShell\.setMode/);
  assert.doesNotMatch(html, /id="useOnlineShell"/);
});

test("shared WebView profile follows origin rules while Haminn data remains app scoped", () => {
  const activity = fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8");
  const registry = fs.readFileSync("app/src/main/java/life/airen/haminn/registry/AppRegistry.kt", "utf8");
  assert.doesNotMatch(activity, /WebViewCompat\.setProfile/);
  assert.match(activity, /"shared-web-message"/);
  assert.match(activity, /"shared-legacy-bridge"/);
  assert.match(activity, /"siteDataPolicy", "shared-by-origin"/);
  assert.match(registry, /icon_url/);
});

test("happ source and runtime mode remain independent across Native and HaminnUI", () => {
  const model = fs.readFileSync("app/src/main/java/life/airen/haminn/model/WebAppInstance.kt", "utf8");
  const registry = fs.readFileSync("app/src/main/java/life/airen/haminn/registry/AppRegistry.kt", "utf8");
  const remote = fs.readFileSync("app/src/main/java/life/airen/haminn/install/RemoteSourceInstaller.kt", "utf8");
  const activity = fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8");
  const shell = shellSources(".js");
  const caps = JSON.parse(fs.readFileSync("api/capabilities.json", "utf8"));
  const backup = JSON.parse(fs.readFileSync("api/backup.schema.json", "utf8"));
  const install = JSON.parse(fs.readFileSync("api/haminn-install.schema.json", "utf8"));
  assert.match(model, /enum class HappSource \{ ONLINE, LOCAL \}/);
  assert.match(model, /enum class HappRuntimeMode \{ LOCAL, LIVE \}/);
  assert.match(registry, /source_kind TEXT NOT NULL/);
  assert.match(registry, /runtime_mode TEXT NOT NULL/);
  assert.match(registry, /private const val VERSION = 14/);
  assert.match(registry, /CREATE TABLE IF NOT EXISTS settings/);
  assert.match(registry, /current\.activeReleaseId/);
  assert.match(remote, /encodedPath\("\/haminn-install\.json"\)/);
  assert.match(remote, /downloadSameOrigin\(packageUrl, updateUrl\)/);
  assert.match(remote, /recordDownload\(result\.appId, packageUrl/);
  assert.doesNotMatch(remote, /readNBytes/);
  assert.match(remote, /source = HappSource\.ONLINE/);
  assert.match(activity, /"host\.apps\.setRuntimeMode"/);
  assert.match(activity, /"app\.setRuntimeMode"[\s\S]*requireApp\(app\)[\s\S]*registry\.setRuntimeMode\(target\.appId, requested\)/);
  assert.ok(caps.public.app.includes("setRuntimeMode"));
  assert.match(fs.readFileSync("sdk/haminn-api.d.ts", "utf8"), /setRuntimeMode\(params: \{ runtimeMode: "local" \| "live" \}\)/);
  assert.match(shell, /"apps\.setRuntimeMode"/);
  assert.match(shell, /"apps\.updateUrls"/);
  assert.match(shell, /card\.dataset\.source/);
  assert.equal(backup.properties.schema.const, 3);
  assert.deepEqual(backup.properties.app.required.slice(0, 4), ["name", "source", "runtimeMode", "liveUrl"]);
  assert.equal(install.properties.schema.const, 1);
  assert.deepEqual(install.required, ["schema", "package"]);
});

test("notifications stay native, per-happ and do not execute background page code", () => {
  const caps = JSON.parse(fs.readFileSync("api/capabilities.json", "utf8"));
  const activity = fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8");
  const worker = fs.readFileSync("app/src/main/java/life/airen/haminn/notification/OnlineNotificationWorker.kt", "utf8");
  const scheduler = fs.readFileSync("app/src/main/java/life/airen/haminn/notification/NotificationScheduler.kt", "utf8");
  assert.deepEqual(caps.public.notifications, ["notify", "schedule", "cancel", "cancelAll", "getScheduled", "setEndpoint", "getStatus"]);
  assert.match(activity, /permissionBroker\.require\(runtime, "notifications"/);
  assert.match(scheduler, /setExactAndAllowWhileIdle/);
  assert.match(worker, /notify-token/);
  assert.doesNotMatch(worker, /readNBytes/);
  assert.doesNotMatch(worker, /WebView|evaluateJavascript/);
});

test("domestic runtime baseline has local QR scanning and no Google Play service dependency", () => {
  const build = fs.readFileSync("app/build.gradle.kts", "utf8");
  const manifest = fs.readFileSync("app/src/main/AndroidManifest.xml", "utf8");
  const scanner = fs.readFileSync("app/src/main/java/life/airen/haminn/QrScannerActivity.kt", "utf8");
  const speech = fs.readFileSync("app/src/main/java/life/airen/haminn/capability/SpeechController.kt", "utf8");
  assert.doesNotMatch(build, /play-services|firebase|mlkit/i);
  assert.doesNotMatch(manifest, /com\.google\.mlkit|com\.google\.android\.gms/);
  assert.match(build, /androidx\.camera:camera-camera2:1\.5\.3/);
  assert.match(build, /com\.google\.zxing:core:3\.5\.4/);
  assert.match(scanner, /CameraX preview with an APK-local ZXing decoder/);
  assert.match(scanner, /POSSIBLE_FORMATS, listOf\(BarcodeFormat\.QR_CODE\)/);
  assert.match(speech, /Build\.VERSION\.SDK_INT >= Build\.VERSION_CODES\.S && SpeechRecognizer\.isOnDeviceRecognitionAvailable/);
});

test("basic microphone and speaker APIs are capability-gated and lifecycle-bound", () => {
  const caps = JSON.parse(fs.readFileSync("api/capabilities.json", "utf8"));
  const main = fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8");
  const audio = fs.readFileSync("app/src/main/java/life/airen/haminn/capability/AudioController.kt", "utf8");
  const types = fs.readFileSync("sdk/haminn-api.d.ts", "utf8");
  assert.deepEqual(caps.public.audio, ["startRecording", "stopRecording", "cancelRecording", "play", "stopPlayback"]);
  assert.match(main, /permissionBroker\.require\(session, "microphone\.record"/);
  assert.match(main, /audio\.cancelSession/);
  assert.match(audio, /FEATURE_MICROPHONE/);
  assert.match(audio, /FEATURE_AUDIO_OUTPUT/);
  assert.match(audio, /MAX_MAX_DURATION_MS = 30L \* 60_000/);
  assert.match(audio, /"audio\.recording\.level"/);
  assert.match(audio, /maxAmplitude/);
  assert.match(types, /microphone\.record/);
  assert.match(types, /startRecording/);
});

test("native HTTP supports session-bound incremental reads and logical file uploads", () => {
  const caps = JSON.parse(fs.readFileSync("api/capabilities.json", "utf8"));
  const main = fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8");
  const network = fs.readFileSync("app/src/main/java/life/airen/haminn/capability/NativeHttpClient.kt", "utf8");
  const bridge = fs.readFileSync("app/src/main/assets/bridge/haminn-v1.js", "utf8");
  const types = fs.readFileSync("sdk/haminn-api.d.ts", "utf8");
  assert.deepEqual(caps.public.network, ["status", "watch", "clearWatch", "request", "openStream", "readStream", "closeStream", "openSocket", "readSocket", "sendSocket", "closeSocket"]);
  assert.match(main, /nativeHttp\.openStream\(session\.sessionId/);
  assert.match(main, /nativeHttp\.closeAll\(it\)/);
  assert.match(network, /bodyLogicalFileId/);
  assert.match(network, /MultipartBody\.Builder/);
  assert.match(network, /MAX_STREAM_CHUNK = 64 \* 1024/);
  assert.match(network, /handle\.owner != owner/);
  assert.match(network, /openSocket\(/);
  assert.match(network, /MAX_SOCKET_FRAME_BYTES = 1024 \* 1024/);
  assert.match(network, /ownedSocket\(socketId, owner, appId, generation\)/);
  assert.match(bridge, /requestTimeout\(method, params\)/);
  assert.match(types, /interface HaminnNetworkStream/);
  assert.match(types, /interface HaminnNetworkSocket/);
});

test("a page can push more bytes than one bridge message into the file store", () => {
  const caps = JSON.parse(fs.readFileSync("api/capabilities.json", "utf8"));
  const main = fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8");
  const store = fs.readFileSync("app/src/main/java/life/airen/haminn/data/FileStore.kt", "utf8");
  const web = fs.readFileSync("app/src/main/java/life/airen/haminn/bridge/BridgeController.kt", "utf8");
  const legacy = fs.readFileSync("app/src/main/java/life/airen/haminn/bridge/LegacyBridgeController.kt", "utf8");
  const protocol = fs.readFileSync("api/protocol-v1.md", "utf8");
  const types = fs.readFileSync("sdk/haminn-api.d.ts", "utf8");
  assert.deepEqual(caps.public.files, ["import", "writeText", "beginWrite", "appendBytes", "finishWrite", "abortWrite", "readText", "list", "export", "delete", "share"]);
  for (const call of ["beginWrite", "appendBytes", "finishWrite", "abortWrite"]) {
    assert.match(main, new RegExp(`"files\\.${call}" -> requireApp\\(app\\)`), `${call} must be dispatched to the calling instance`);
  }
  assert.match(main, /files\.appendBytes\(session\.sessionId, it\.appId, session\.dataGeneration!!, params\.getString\("writeId"\), params\.getString\("chunkBase64"\)\)/);
  // A handle belongs to the page session that opened it; a caller cannot name another one.
  assert.match(store, /handle\.owner != owner \|\| handle\.appId != appId \|\| handle\.generation != generation/);
  assert.match(store, /MAX_CHUNK_BYTES = 64 \* 1024/);
  assert.match(store, /单个数据块超过 64 KiB/);
  assert.match(store, /MAX_WRITES_PER_SESSION = 2/);
  assert.match(store, /WRITE_IDLE_TTL_MS = 2 \* 60 \* 1000L/);
  // The chunked write publishes through the same atomic rename as an inline import.
  assert.match(store, /private fun commit\(appId: String, generation: String/);
  assert.equal((store.match(/commit\(appId, generation/g) || []).length, 2, "import and chunked write must share one commit path");
  // An oversized message is answered on its own request id instead of vanishing.
  for (const controller of [web, legacy]) {
    assert.match(controller, /MAX_MESSAGE_BYTES = 256 \* 1024/);
    assert.match(controller, /oversizedRequestId\(/);
    assert.match(controller, /ErrorCodes\.QUOTA, oversizedMessage\(size\)/);
    assert.match(controller, /files\.beginWrite\/appendBytes\/finishWrite/);
    assert.doesNotMatch(controller, /> MAX_MESSAGE_BYTES\) return/, "an over-limit message must not be dropped in silence");
  }
  assert.match(protocol, /answered with `E_QUOTA` on its own request id/);
  assert.match(types, /beginWrite\(params: \{ name: string; mime\?: string \}\)/);
  assert.match(types, /appendBytes\(params: \{ writeId: string; chunkBase64: string \}\)/);
});

test("system capability adapters are discoverable, permission-gated and foreground-bound", () => {
  const caps = JSON.parse(fs.readFileSync("api/capabilities.json", "utf8"));
  const manifest = fs.readFileSync("app/src/main/AndroidManifest.xml", "utf8");
  const main = fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8");
  const bridge = fs.readFileSync("app/src/main/assets/bridge/haminn-v1.js", "utf8");
  const types = fs.readFileSync("sdk/haminn-api.d.ts", "utf8");
  assert.equal(caps.apiMinor, 14);
  assert.deepEqual(caps.public.appearance, ["reportTheme"]);
  assert.deepEqual(caps.public.tts, ["availability", "preferences", "voices", "languageAvailability", "speak", "stop", "export"]);
  assert.deepEqual(caps.public.speech, ["availability", "preferences", "languages", "start", "recognizeOnce", "stop", "cancel"]);
  assert.deepEqual(caps.storeOnly["host.voice"], ["status", "ttsVoices", "configure", "testTts", "openSettings"]);
  assert.deepEqual(caps.public.sensors, ["availability", "watch", "clearWatch"]);
  assert.deepEqual(caps.public.wifi, ["status", "scan", "requestNetwork", "releaseNetwork", "openSettings"]);
  assert.ok(caps.public.bluetooth.includes("subscribe"));
  assert.deepEqual(caps.public.infrared, ["status", "transmit"]);
  assert.deepEqual(caps.public.system, ["language", "openSettings"]);
  assert.match(main, /"system\.language" -> systemLanguageInfo\(\)/);
  assert.match(main, /\.put\("preferredLanguages", JSONArray\(tags\)\)/);
  assert.match(types, /language\(\): Promise<HaminnSystemLanguage>/);
  for (const namespace of ["appearance", "sensors", "wifi", "bluetooth", "infrared", "battery", "system"]) {
    assert.match(bridge, new RegExp(`${namespace}: namespace\\("${namespace}"\\)`));
    assert.match(types, new RegExp(`\\b${namespace}:`));
  }
  for (const permission of ["ACTIVITY_RECOGNITION", "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "NEARBY_WIFI_DEVICES", "TRANSMIT_IR"]) {
    assert.match(manifest, new RegExp(`android\\.permission\\.${permission}`));
  }
  assert.match(main, /permissionBroker\.require\(session, "wifi\.scan"/);
  assert.match(main, /permissionBroker\.require\(session, "bluetooth\.connect"/);
  assert.match(main, /permissionBroker\.require\(session, "infrared\.transmit"/);
  assert.match(main, /authorizationDescriptors\(runtime, name\)/);
  assert.match(types, /authorization\?: Record/);
  assert.match(main, /sensors\.cancelAll\(\)/);
  assert.match(main, /bluetooth\.cancelAll\(\)/);
  const speech = fs.readFileSync("app/src/main/java/life/airen/haminn/capability/SpeechController.kt", "utf8");
  const tts = fs.readFileSync("app/src/main/java/life/airen/haminn/capability/TtsController.kt", "utf8");
  assert.match(speech, /SpeechRecognizer\.createSpeechRecognizer\(appContext, selection\.component\)/);
  assert.match(speech, /speech\.recognizeOnce/);
  assert.match(speech, /RecognizerIntent\.ACTION_GET_LANGUAGE_DETAILS/);
  assert.match(speech, /EXTRA_SUPPORTED_LANGUAGES/);
  assert.match(main, /"speech" -> speech\.availability\(\)\.optBoolean\("available"\)/);
  assert.match(main, /"speech\.languages" -> speech\.languages\(\)/);
  assert.match(tts, /TextToSpeech\(appContext, \{ ready\.complete\(it\) \}, engineId\)/);
  assert.match(tts, /availableLanguages/);
  assert.doesNotMatch(tts, /tts\.providerFallback/);
  assert.doesNotMatch(types, /providerManagedByUser|directServiceAvailable|recognitionActivityAvailable|\bengines\(\):/);
  assert.match(fs.readFileSync("app/src/main/java/life/airen/haminn/capability/SensorController.kt", "utf8"), /MAX_RATE_HZ = 60\.0/);
  assert.match(fs.readFileSync("app/src/main/java/life/airen/haminn/capability/WifiController.kt", "utf8"), /directSavedNetworkChangesAllowed", false/);
  assert.match(fs.readFileSync("app/src/main/java/life/airen/haminn/capability/InfraredController.kt", "utf8"), /MAX_TOTAL_US = 2_000_000L/);
});

test("the installed plugin identity is haminn-dev-plugin and the legacy name stays installable", () => {
  const root = "app/src/main/assets/agent/";
  const server = fs.readFileSync("app/src/main/java/life/airen/haminn/deploy/AgentDevelopmentServer.kt", "utf8");
  const discovery = server.slice(server.indexOf("private fun bootstrap()"), server.indexOf("private fun bootstrapHtml()"));
  const helper = fs.readFileSync(root + "haminn-agent.py", "utf8");
  const guide = fs.readFileSync(root + "haminn-dev-plugin/SKILL.md", "utf8");
  assert.match(server, /private const val PLUGIN_ID = "haminn-dev-plugin"/);
  assert.match(server, /private const val LEGACY_PLUGIN_ID = "haminn-device"/);
  assert.match(discovery, /put\("id", PLUGIN_ID\)/);
  assert.match(discovery, /put\("replaces", JSONArray\(listOf\(LEGACY_PLUGIN_ID\)\)\)/);
  assert.match(discovery, /put\("name", PLUGIN_ID\)/);
  assert.match(discovery, /put\("target", "~\/plugins\/\$PLUGIN_ID"\)/);
  assert.match(discovery, /skills\/\$PLUGIN_ID\/SKILL\.md/);
  assert.match(server, /"skills\/\$PLUGIN_ID\/SKILL\.md" to guide\(\)/);
  assert.match(server, /put\("mcpServers", JSONObject\(\)\.put\(PLUGIN_ID,/);
  assert.match(helper, /^PLUGIN_ID = "haminn-dev-plugin"$/m);
  assert.match(helper, /LEGACY_PLUGIN_IDS = \("haminn-device",\)/);
  assert.match(helper, /plugin\.get\("id"\) not in PLUGIN_IDS/);
  assert.match(helper, /default_target = \(Path\.home\(\) \/ "plugins" \/ PLUGIN_ID\)/);
  assert.match(guide, /^name: haminn-dev-plugin$/m);
  assert.ok(fs.existsSync(root + "haminn-dev-plugin/SKILL.md"));
  assert.ok(!fs.existsSync(root + "haminn-device/SKILL.md"));
});

test("screen capture and recording are consent-bound, service-backed and quota-aware", () => {
  const caps = JSON.parse(fs.readFileSync("api/capabilities.json", "utf8"));
  const manifest = fs.readFileSync("app/src/main/AndroidManifest.xml", "utf8");
  const main = fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8");
  const app = fs.readFileSync("app/src/main/java/life/airen/haminn/HaminnApplication.kt", "utf8");
  const controller = fs.readFileSync("app/src/main/java/life/airen/haminn/capability/ScreenCaptureController.kt", "utf8");
  const service = fs.readFileSync("app/src/main/java/life/airen/haminn/capability/ScreenCaptureService.kt", "utf8");
  const audioCapture = fs.readFileSync("app/src/main/java/life/airen/haminn/capability/ScreenAudioCapture.kt", "utf8");
  const plan = fs.readFileSync("app/src/main/java/life/airen/haminn/capability/ScreenCapturePlan.kt", "utf8");
  const bridge = fs.readFileSync("app/src/main/assets/bridge/haminn-v1.js", "utf8");
  const types = fs.readFileSync("sdk/haminn-api.d.ts", "utf8");

  assert.deepEqual(caps.public.screen, ["availability", "capture", "startRecording", "stopRecording", "cancelRecording"]);
  for (const method of ["availability", "capture", "startRecording", "stopRecording", "cancelRecording"]) {
    assert.match(main, new RegExp(`"screen\\.${method}" ->`), `screen.${method} must be dispatched`);
  }
  // Both capabilities are gated twice: by Haminn's own per-happ grant and by the system
  // projection dialog, which is asked again for every single session.
  assert.match(main, /permissionBroker\.require\(session, "screen\.capture"/);
  assert.match(main, /permissionBroker\.require\(session, "screen\.record"/);
  assert.match(main, /permissionBroker\.require\(session, "microphone\.record",\s*"录屏时同时收录系统声音与麦克风", PermissionBroker\.MICROPHONE_PERMISSIONS\)/);
  assert.match(main, /"screen\.capture", "screen\.record"/);
  assert.match(main, /"screen\.record" -> "录制整个设备屏幕的画面与声音,包含其他应用"/);
  assert.match(main, /private suspend fun requestScreenCaptureConsent\(\): Intent\?/);
  assert.match(main, /manager\.createScreenCaptureIntent\(MediaProjectionConfig\.createConfigForDefaultDisplay\(\)\)/);
  // One consent buys exactly one virtual display, so a second session is refused here
  // rather than being discovered by the platform.
  assert.match(controller, /if \(activeKind != null\) throw HaminnException\(ErrorCodes\.CONFLICT/);

  // Android 14 refuses to hand out a projection unless the app already runs a foreground
  // service of the matching type, and the service cannot start without the declaration.
  assert.match(manifest, /android\.permission\.FOREGROUND_SERVICE_MEDIA_PROJECTION/);
  assert.match(manifest, /android:name="\.capability\.ScreenCaptureService"[\s\S]{0,120}foregroundServiceType="mediaProjection"/);
  assert.match(service, /ServiceInfo\.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION/);
  assert.match(controller, /context\.startForegroundService\(intent\)/);
  assert.match(controller, /withTimeoutOrNull\(FOREGROUND_READY_TIMEOUT_MS\) \{ ready\.await\(\) \}/);
  // The callback has to exist before the first virtual display is created, and be gone
  // before the projection is stopped on purpose, or a clean teardown reads as a revocation.
  assert.match(controller, /projection\.registerCallback\(callback, Handler\(Looper\.getMainLooper\(\)\)\)/);
  assert.match(controller, /live\.projection\.unregisterCallback\(live\.callback\)/);

  // A recording may be as large as one file store object, and that object's limit lives in a
  // different file. The two are compared here rather than trusted to stay in step by hand.
  const store = fs.readFileSync("app/src/main/java/life/airen/haminn/data/FileStore.kt", "utf8");
  const storeFileLimit = Number(store.match(/MAX_FILE_BYTES = (\d+)L \* 1024 \* 1024/)[1]);
  const recordingLimit = Number(plan.match(/MAX_MAX_BYTES = (\d+)L \* 1024 \* 1024/)[1]);
  assert.equal(recordingLimit, storeFileLimit, "the recording ceiling must equal the file store's single-object limit");
  assert.equal(storeFileLimit, 256, "the store keeps a single logical file at 256 MiB");
  assert.match(plan, /BYTE_BUDGET_DENOMINATOR = 1\.08/);
  assert.match(controller, /recorder\.setMaxFileSize\(maxVideoBytes\)/);
  assert.match(controller, /recorder\.setMaxDuration\(request\.maxDurationMs\.toInt\(\)\)/);
  assert.match(controller, /MediaRecorder\.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED/);
  assert.match(controller, /MediaRecorder\.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED/);

  // Segmentation is the only way a capture longer than one file can exist, and it is built on
  // the one platform mechanism that rolls the output without stopping the encoder. The queue
  // call is only legal in the window the 90% notification opens, so both halves have to be here.
  assert.match(plan, /val segment: Boolean/);
  assert.match(plan, /fun segmentName\(base: String, index: Int\): String/);
  assert.match(controller, /MediaRecorder\.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> queueNextSegment\(id\)/);
  assert.match(controller, /MediaRecorder\.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> rollSegment\(id\)/);
  assert.match(controller, /recorder\.setNextOutputFile\(next\)/);
  assert.match(controller, /put\("segmenting", true\)/);
  assert.match(controller, /EVENT_SEGMENT = "screen\.recording\.segment"/);
  assert.match(controller, /put\("segments", JSONArray\(all\)\)/);
  assert.match(audioCapture, /fun positionUs\(\): Long = framesProduced \* 1_000_000L \/ sampleRate/);
  // One continuous audio stream serves every segment, so the mux has to take a window of it
  // rather than the whole file.
  assert.match(controller, /ScreenMediaMux\.combine\(video, active\.audioFile, active\.muxFile, fromUs, toUs\)/);
  assert.match(controller, /put\("message", "音频轨没有写成功,已交付无声录制"\)/);

  // The outcome of a recording that ended on its own limit is cached for a late
  // stopRecording, and it names a file, so it is only handed back to the app that owns it.
  assert.match(controller, /recent\.appId == appId && \(recordingId\.isNullOrBlank\(\) \|\| recent\.id == recordingId\)/);

  // The picture and the sound are written by two different components, so the delivered
  // file is a mux, and losing the audio track never loses the recording.
  assert.match(controller, /put\("message", "音频轨没有写成功,已交付无声录制"\)/);
  assert.match(audioCapture, /addMatchingUsage\(AudioAttributes\.USAGE_MEDIA\)/);
  assert.match(audioCapture, /AudioRecord\.READ_NON_BLOCKING/);

  // The recording belongs to the application so it survives the page going to the
  // background, which is the only way "record another app" can mean anything.
  assert.match(app, /val screenCapture by screenCaptureDelegate/);
  assert.match(app, /if \(screenCaptureDelegate\.isInitialized\(\)\) screenCapture\.shutdown\(\)/);
  assert.match(main, /haminnApp\.screenCapture\.onForegroundAppChanged\(appId\)/);
  assert.match(main, /haminnApp\.screenCapture\.shutdown\(\)/);
  assert.doesNotMatch(
    main.slice(main.indexOf("override fun onStop()"), main.indexOf("override fun onResume()")),
    /screenCapture/,
    "leaving the activity must not stop a recording",
  );

  // Waiting on the consent dialog and on the final mux takes far longer than the default
  // client timeout, so these methods share the long one.
  assert.ok(
    bridge.includes("screen\\.(?:capture|startRecording|stopRecording)"),
    "the bridge must extend the timeout of the screen methods that wait on the user or on a mux",
  );
  assert.match(bridge, /screen: namespace\("screen"\)/);
  assert.match(types, /screen: \{/);
  assert.match(types, /"screen\.capture" \| "screen\.record"/);
  assert.match(types, /interface HaminnScreenAvailability/);
  assert.match(types, /interface HaminnScreenRecording/);
});
