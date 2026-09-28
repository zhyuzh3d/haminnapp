(() => {
  "use strict";
  const H = window.HaminnShell;
  const { $, $$, state, host } = H;
  const { say, busy, bind, open, close, confirmAction } = H.ui;
  const { hasLocal, hasLive, sourceLabel, runtimeLabel, refresh, resetFilters, pin, renderPinButton } = H.features.library;
  const capabilityLabels = { "camera.capture": "拍照", "microphone.record": "麦克风录音", speech: "语音识别", "location.approximate": "大致位置", "location.precise": "精确位置", "clipboard.read": "读取剪贴板", "screen.capture": "截屏", "screen.record": "录屏", network: "网络请求", notifications: "发送通知" };
  let releaseState = { appId:null, result:null, visible:10 };

  /** The freshest instance for the open panel: every control acts on this one. */
  function current() {
    const app = state.selected;
    if (!app) return null;
    return state.apps.find(item => item.appId === app.appId) || app;
  }
  function requireApp() {
    const app = current();
    if (!app) throw new Error("应用设置已失效，请重新打开。");
    return app;
  }
  function versionText(value) {
    if (!value || typeof value !== "object") return "未标注版本";
    if (typeof value.name === "string" && value.name.trim()) return value.name.trim();
    if (Number.isFinite(Number(value.code))) return "版本 " + Number(value.code);
    return "未标注版本";
  }
  function firstLetter(name) {
    const characters = Array.from(String(name || "").trim());
    return characters.length ? characters[0].toUpperCase() : "H";
  }
  function setSwitch(selector, enabled) {
    const control = $(selector); control.classList.toggle("on", enabled); control.setAttribute("aria-checked", String(enabled));
  }
  function renderIconPreview(dataUrl, name) {
    const preview = $("#editIconPreview");
    preview.replaceChildren(); preview.style.backgroundImage = ""; preview.classList.remove("custom");
    if (dataUrl) {
      preview.style.backgroundImage = "url(" + JSON.stringify(dataUrl).slice(1,-1) + ")";
      preview.classList.add("custom");
      return;
    }
    // Without a custom icon the happ falls back to its own icon, or to a letter tile
    // generated from the first character of the name.
    preview.textContent = firstLetter(name);
  }
  function isNameDirty() {
    const app = current();
    if (!app) return false;
    const value = $("#editName").value.trim();
    return !!value && value !== app.name;
  }
  function renderManage(app, options = {}) {
    if (!app) return;
    if (options.syncName) $("#editName").value = app.name;
    $("#manageTitle").textContent = "设置：" + app.name;
    $("#manageMeta").textContent = sourceLabel(app) + " · " + runtimeLabel(app);
    $("#saveAppName").disabled = !isNameDirty();
    renderIconPreview(app.customIconUrl || app.defaultIconUrl || "", app.name);
    $("#removeCustomAppIcon").classList.toggle("hidden", !app.hasCustomIcon);
    const pinState = renderPinButton($("#managePin"), app);
    $("#managePin").classList.toggle("hidden", pinState === "pinned" || pinState === "unsupported");
    $$("#runtimeChoices button").forEach(button => {
      const mode = button.dataset.runtimeMode;
      const available = mode === "local" ? hasLocal(app) : hasLive(app);
      button.disabled = !available;
      button.classList.toggle("selected", appRuntimeMode(app) === mode);
      button.setAttribute("aria-pressed", String(appRuntimeMode(app) === mode));
    });
    $("#liveUrl").textContent = app.liveUrl || "无";
    const updateUrl = app.updateUrl || "";
    $("#updateUrlBlock").classList.toggle("hidden", !updateUrl);
    $("#updateUrl").textContent = updateUrl || "无";
    $("#updateApp").disabled = !updateUrl;
    const localPath = app.sourcePath || "";
    const link = app.downloadUrl || "";
    $("#downloadUrl").textContent = localPath || link || "无";
    $("#downloadKind").textContent = localPath ? "从本机文件安装" : link ? "从链接地址安装" : "";
    $("#qrLinkRow").classList.toggle("hidden", !link || !!localPath);
    $("#qrLink").textContent = link ? "haminn://add?url=" + encodeURIComponent(link) : "无";
    $("#reinstallApp").disabled = !(localPath || link);
    setSwitch("#notificationSwitch", !!app.notificationEnabled);
    setSwitch("#crossOriginSwitch", !!app.allowCrossOriginNetwork);
    renderDevWorkspace(app);
  }
  function appRuntimeMode(app) {
    return app.runtimeMode || (app.liveUrl && !app.activeReleaseId ? "live" : "local");
  }
  function renderDevWorkspace(app) {
    const dev = app.devWorkspace || {}, runningDev = app.launchChannel === "dev";
    const version = runningDev && dev.devVersion ? versionText(dev.devVersion) : versionText(app.activeVersion);
    $("#devWorkspaceStatus").textContent = (runningDev ? "开发副本模式运行" : "正式运行") + " · 版本 " + version;
    $("#switchToStable").classList.toggle("hidden", !runningDev);
    $("#exportDevApp").disabled = !hasLocal(app);
  }
  function renderManageDraft() { renderManage(current()); }

  /** Runs one immediate change and re-renders the panel from the refreshed library. */
  async function applyChange(method, params, message) {
    const app = requireApp();
    const value = await host.call(method, Object.assign({ appId: app.appId }, params));
    if (value && value.cancelled) { say("已取消操作。"); return; }
    await refresh();
    state.selected = state.apps.find(item => item.appId === app.appId) || app;
    renderManageDraft();
    if (message) say(message);
  }
  /** Switches and other content-preserving controls act without replacing their contents. */
  async function immediate(work) {
    const panel = $("#managePanel");
    if (panel.dataset.working) return;
    panel.dataset.working = "true"; panel.classList.add("working");
    try { await work(); }
    catch (error) {
      say(error.code === "E_CANCELLED" ? "已取消操作。" : (error.message || "操作失败，请重试。"), error.code !== "E_CANCELLED");
      // Roll the optimistic switch back to what the database still holds.
      renderManageDraft();
    }
    finally { delete panel.dataset.working; panel.classList.remove("working"); if (H.onOperationSettled) H.onOperationSettled(panel); }
  }

  $("#editName").oninput = () => { $("#saveAppName").disabled = !isNameDirty(); };
  bind("#saveAppName", async () => {
    const app = requireApp();
    const name = $("#editName").value.trim();
    if (!name) { $("#editName").focus(); throw new Error("应用名称不能为空。"); }
    if (name === app.name) return;
    await applyChange("apps.update", { name }, "应用名称已更新。");
    $("#editName").value = (current() || app).name;
    renderManageDraft();
  });
  bind("#editAppIcon", async () => {
    const app = requireApp();
    const value = await host.call("apps.pickIcon", {});
    if (value.cancelled) { say("已取消操作。"); return; }
    const cropped = await H.ui.cropIcon(value.preview);
    if (!cropped) return;
    await applyChange("apps.updatePresentation", { name: app.name, iconPreviewDataUrl: cropped }, "应用图标已更新。");
  });
  bind("#removeCustomAppIcon", async () => {
    const app = requireApp();
    await applyChange("apps.updatePresentation", { name: app.name, iconPreviewDataUrl: "" }, "已移除自定义图标，恢复 happ 默认图标。");
  });
  $("#notificationSwitch").onclick = () => immediate(async () => {
    const app = requireApp();
    const enabled = !app.notificationEnabled;
    setSwitch("#notificationSwitch", enabled);
    await applyChange("apps.setNotificationEnabled", { enabled }, enabled ? "已允许接收通知。" : "已停止接收通知。");
  });
  $("#crossOriginSwitch").onclick = () => immediate(async () => {
    const app = requireApp();
    const enabled = !app.allowCrossOriginNetwork;
    if (enabled && !await confirmAction("允许跨 Origin 网络？", "允许后，此 happ 的脚本、接口和页面可以主动连接 liveUrl Origin 之外的地址，运行时会持续显示提示。", "允许")) { renderManageDraft(); return; }
    setSwitch("#crossOriginSwitch", enabled);
    await applyChange("apps.setCrossOriginNetwork", { enabled }, enabled ? "已允许跨 Origin 网络。" : "已禁止跨 Origin 网络。");
  });
  $$("#runtimeChoices [data-runtime-mode]").forEach(button => {
    button.onclick = () => busy(button, async () => {
      const app = requireApp();
      const mode = button.dataset.runtimeMode;
      if (mode === appRuntimeMode(app)) return;
      const message = mode === "live"
        ? "以后打开此 happ 将直接加载它的线上地址，页面代码可随服务器变化。"
        : "以后打开此 happ 将优先运行手机内已验证的本地代码。";
      if (!await confirmAction("切换运行方式？", message, "切换")) return;
      await applyChange("apps.setRuntimeMode", { runtimeMode: mode }, mode === "live" ? "已切换为线上实时运行。" : "已切换为本地运行。");
    });
  });
  bind("#managePin", () => pin(current()));
  bind("#updateApp", async () => {
    const app = requireApp();
    await host.call("apps.updateFromSource", { appId: app.appId });
    await refresh(); await openManage(state.apps.find(x => x.appId === app.appId));
    say("已从更新地址下载并安装代码，数据已保留。");
  });
  bind("#reinstallApp", async () => {
    const app = requireApp();
    const source = app.sourcePath || app.downloadUrl;
    if (!await confirmAction("重新安装？", "将从 " + source + " 重新安装，替换当前代码并保留 Haminn 数据。", "重新安装")) return;
    await host.call("apps.reinstall", { appId: app.appId });
    await refresh(); await openManage(state.apps.find(x => x.appId === app.appId));
    say("已从原安装来源重新安装，数据已保留。");
  });

  async function reopenAfterDevChange(appId, message) {
    await refresh();
    const updated = state.apps.find(item => item.appId === appId);
    if (!updated) throw new Error("应用已不存在。");
    close("#managePanel");
    await openManage(updated);
    say(message);
  }
  /** Opens the confirmation that lets the user pick how to leave the dev copy. */
  function openDevSwitch() {
    const app = current();
    if (!app) return;
    const dev = app.devWorkspace || {};
    $("#devSwitchDevVersion").textContent = dev.devVersion ? versionText(dev.devVersion) : "未标注版本";
    $("#devSwitchStableVersion").textContent = versionText(app.activeVersion);
    $("#devSwitchMessage").textContent = "当前运行的是开发工作副本。请选择如何回到正式版。";
    open("#devSwitchPanel");
  }
  bind("#switchToStable", () => openDevSwitch());
  bind("#devSwitchUseStable", async () => {
    const app = requireApp();
    close("#devSwitchPanel");
    await host.call("apps.leaveDev", { appId: app.appId });
    await reopenAfterDevChange(app.appId, "已切换为运行原有正式版，开发副本仍保留。");
  });
  bind("#devSwitchPromote", async () => {
    const app = requireApp();
    close("#devSwitchPanel");
    await host.call("apps.promoteDev", { appId: app.appId });
    await reopenAfterDevChange(app.appId, "当前开发版已安装为正式版并开始运行。");
  });
  bind("#exportDevApp", async () => {
    const app = requireApp();
    const session = await host.call("apps.shareStart", { appId: app.appId, network: false });
    try {
      const result = await host.call("apps.shareSave", { sessionId: session.sessionId });
      say(result.cancelled ? "已取消导出。" : "当前运行版本已导出为 Zip 安装包。");
    } finally {
      await host.call("apps.shareStop", { sessionId: session.sessionId });
    }
  });

  async function openManage(app) {
    const epoch = ++state.managedEpoch;
    if (!app) throw new Error("应用已不存在，请刷新应用库。");
    state.selected = app;
    renderManage(app, { syncName: true });
    $("#developerDetails").classList.toggle("hidden", !hasLocal(app));
    $("#releaseDetails").classList.toggle("hidden", !hasLocal(app));
    $("#grants").textContent = "正在读取授权…"; $("#releases").textContent = "正在读取版本…";
    open("#managePanel");
    const [grantResult, releaseResult] = await Promise.allSettled([
      host.call("permissions.list", { appId: app.appId }),
      hasLocal(app) ? host.call("apps.releases", { appId: app.appId }) : Promise.resolve(null)
    ]);
    if (epoch !== state.managedEpoch || $("#managePanel").classList.contains("hidden")) return;
    const grantRoot = $("#grants"); grantRoot.replaceChildren();
    if (grantResult.status === "rejected") grantRoot.textContent = "授权信息读取失败，请重新打开管理面板。";
    else {
      const grants = grantResult.value.grants || [];
      if (!grants.length) grantRoot.textContent = "尚无持久授权。使用相关功能时会向你申请。";
      for (const grant of grants) {
        const row = document.createElement("div"); row.className = "row";
        const label = document.createElement("span");
        label.textContent = (capabilityLabels[grant.capability] || grant.capability) + (grant.scope ? " · " + grant.scope : "") + " · " + (grant.decision === "allow" ? "已允许" : "已拒绝");
        const revoke = document.createElement("button"); revoke.textContent = "重置";
        revoke.onclick = () => busy(revoke, async () => {
          await host.call("permissions.revoke", { appId: app.appId, capability: grant.capability, scope: grant.scope });
          row.remove(); say("已重置，下次使用会重新询问。");
          if (!grantRoot.children.length) grantRoot.textContent = "尚无持久授权。";
        });
        row.append(label, revoke); grantRoot.append(row);
      }
    }
    const releaseRoot = $("#releases"); releaseRoot.replaceChildren();
    if (releaseResult.status === "rejected") { releaseRoot.textContent = "版本信息读取失败，请重新打开管理面板。"; return; }
    const versions = releaseResult.value;
    if (!versions) return;
    releaseState = { appId: app.appId, result: versions, visible:10 };
    renderReleases();
  }
  function renderReleases() {
    const releaseRoot = $("#releases");
    const versions = releaseState.result;
    if (!versions) return;
    releaseRoot.replaceChildren();
    for (const release of versions.releases.slice(0, releaseState.visible)) {
      const row = document.createElement("div"); row.className = "row";
      const label = document.createElement("span");
      label.textContent = (release.versionName || release.releaseId.slice(0,8)) + (release.releaseId === versions.activeReleaseId ? " · 当前使用" : "");
      row.append(label);
      if (release.releaseId !== versions.activeReleaseId) {
        const activate = document.createElement("button"); activate.textContent = "切换";
        activate.onclick = () => busy(activate, async () => {
          if (!await confirmAction("切换代码版本", "页面代码会切换到此版本，现有数据保留。请确保旧版代码与当前数据兼容。", "切换版本")) return;
          await host.call("apps.rollback", { appId: releaseState.appId, releaseId: release.releaseId });
          close("#managePanel"); say("代码版本已切换。"); await refresh();
        });
        row.append(activate);
      }
      releaseRoot.append(row);
    }
    const more = $("#moreReleases");
    more.classList.toggle("hidden", versions.releases.length <= releaseState.visible);
    more.textContent = "显示更多版本（" + Math.min(10, versions.releases.length - releaseState.visible) + "）";
  }
  $("#moreReleases").onclick = () => { releaseState.visible += 10; renderReleases(); };
  bind("#pruneReleases", async button => {
    const app = requireApp();
    const versions = releaseState.result;
    if (!versions || versions.releases.length <= 5) { say("当前版本不超过5个，无需清理。"); return; }
    if (!await confirmAction("清理旧代码版本", "只保留最近5个版本；当前正在使用的版本始终保留。此操作无法撤销。", "清理版本", true)) return;
    await host.call("apps.pruneReleases", { appId: app.appId });
    const refreshed = await host.call("apps.releases", { appId: app.appId });
    releaseState = { appId: app.appId, result: refreshed, visible:10 };
    renderReleases();
    say("旧代码版本已清理。");
  });
  bind("#restoreBackupSettings", async () => {
    if (!await confirmAction("恢复应用备份", "将创建独立的新应用，不继承登录状态和页面授权。", "选择备份")) return;
    const value = await host.call("backup.restore", {});
    if (!value.cancelled) {
      state.libraryFilter = "all";
      H.navigation.cachedViewState.views.favorites = { scrollY: 0, libraryFilter: "all" };
      resetFilters();
      await H.navigation.showView("favorites"); say("已恢复“" + value.name + "”。");
    }
  });
  bind("#restoreAppData", async () => {
    const app = requireApp();
    if (!await confirmAction("替换“" + app.name + "”的数据？", "原有 Haminn 记录和附件会被备份内容覆盖，页面授权重置。此操作无法撤销，请先导出当前备份。", "选择备份并替换", true)) return;
    const value = await host.call("backup.restoreData", { appId: app.appId });
    if (!value.cancelled) { close("#managePanel"); await refresh(); say("数据已恢复，页面授权已重置。"); }
  });
  $("#uninstallApp").onclick = () => open("#uninstallPanel");
  bind("#removeApp", async () => {
    const app = requireApp();
    if (!await confirmAction("彻底清除“" + app.name + "”？", "将删除这个应用及其全部 Haminn 本机数据，无法恢复。", "彻底清除", true)) return;
    await host.call("apps.remove", { appId: app.appId }); close("#uninstallPanel"); close("#managePanel"); await refresh(); say("应用已卸载并彻底清除数据。");
  });
  bind("#archiveApp", async () => {
    const app = requireApp();
    await host.call("apps.archive", { appId:app.appId }); close("#uninstallPanel"); close("#managePanel"); await refresh(); say("应用已卸载，数据仍保留在 HaminnApp 中。");
  });
  bind("#backupApp", async () => {
    const app = requireApp();
    if (!await confirmAction("导出“" + app.name + "”的备份", "包含代码、Haminn 记录和附件，不包含网站登录状态。备份不加密，请保存到可信位置。", "选择保存位置")) return;
    const value = await host.call("backup.export", { appId: app.appId });
    say(value.cancelled ? "已取消导出。" : "备份已导出。");
  });
  H.features.manage = { openManage, isDirty: isNameDirty, renderManage, renderManageDraft, openDevSwitch };
})();
