(() => {
  "use strict";
  const H = window.HaminnShell;
  const { $, $$, state, host } = H;
  const { say, busy, bind, open, close, confirmAction, selected } = H.ui;
  const copy = (text, label) => H.copy(text, label);
  const SETTINGS_TABS = ["interface", "tts", "speech", "system"];
  function showSettingsTab(value) {
    const active = SETTINGS_TABS.includes(value) ? value : "interface";
    state.settingsTab = active;
    $$('[data-settings-tab]').forEach(button => {
      const selected = button.dataset.settingsTab === active;
      button.classList.toggle("selected", selected);
      button.setAttribute("aria-selected", String(selected));
      button.tabIndex = selected ? 0 : -1;
    });
    $$('[data-settings-pane]').forEach(pane => pane.classList.toggle("hidden", pane.dataset.settingsPane !== active));
  }
  $$('[data-settings-tab]').forEach(button => { button.onclick = () => showSettingsTab(button.dataset.settingsTab); });
  showSettingsTab(state.settingsTab);
  function renderLanguageChoice() { selected("#languageChoices", "languageChoice", H.i18n.preference()); }
  renderLanguageChoice();
  $$('[data-language-choice]').forEach(button => {
    button.onclick = () => { H.i18n.setPreference(button.dataset.languageChoice); renderLanguageChoice(); };
  });
  addEventListener("haminnlanguagechange", renderLanguageChoice);
  let theme = "system";
  const systemDark = matchMedia("(prefers-color-scheme: dark)");
  function resolvedTheme() { return theme === "system" ? (systemDark.matches ? "dark" : "light") : theme; }
  function reportTheme() {
    host.reportTheme(resolvedTheme()).catch(() => {});
  }
  function applyTheme(value, persist = true) {
    theme = ["system", "light", "dark"].includes(value) ? value : "system";
    if (theme === "system") delete document.documentElement.dataset.theme;
    else document.documentElement.dataset.theme = theme;
    selected("#themeChoices", "themeChoice", theme);
    if (persist) host.call("settings.set", { key:"theme", value:theme }).catch(() => {});
    reportTheme();
  }
  // The theme lives in the Haminn database so an unattended backup can store it.
  async function loadStoredTheme() {
    try {
      const value = await host.call("settings.get", {});
      applyTheme(value.theme || "system", false);
    } catch (_) {
      applyTheme("system", false);
    }
  }
  applyTheme(theme, false);
  addEventListener("haminnready", reportTheme);
  const systemThemeChanged = () => { if (theme === "system") reportTheme(); };
  if (systemDark.addEventListener) systemDark.addEventListener("change", systemThemeChanged);
  else systemDark.addListener(systemThemeChanged);
  $$("[data-theme-choice]").forEach(button => { button.onclick = () => applyTheme(button.dataset.themeChoice); });

  async function openLicenses() { const value = await host.call("licenses.info", {}); $("#licensesOutput").textContent = value.text; open("#licensesPanel"); }
  async function openDiagnostics() { const report = await host.call("diagnostics.info", {}); $("#diagnosticsOutput").textContent = JSON.stringify(report, null, 2); open("#diagnosticsPanel"); }
  bind("#licensesButton", openLicenses);
  bind("#diagnosticsButton", openDiagnostics);
  bind("#copyDiagnostics", () => copy($("#diagnosticsOutput").textContent, "诊断报告"));
  bind("#githubButton", () => host.call("about.openRepository", {}));
  function showRestoreResult(value) {
    if (value.theme) applyTheme(value.theme);
    const root = $("#backupRestoreResult");
    root.replaceChildren();
    const lines = [];
    if (value.backupType === "settings") lines.push("Haminn 设置");
    if (value.backupType === "happ") lines.push(value.name ? "应用：“" + value.name + "”" : "单个 happ");
    if (value.backupType === "full") {
      if (value.settingsRestored) lines.push("Haminn 设置");
      const apps = Array.isArray(value.apps) ? value.apps : [];
      lines.push("全部 happ（" + (value.happCount ?? apps.length) + " 个）");
      apps.forEach(app => { if (app && app.name) lines.push("已恢复：“" + app.name + "”"); });
    }
    if (!lines.length) lines.push("备份内容");
    const heading = document.createElement("strong"); heading.textContent = "恢复完成"; root.append(heading);
    const list = document.createElement("ul"); lines.forEach(line => { const item = document.createElement("li"); item.textContent = line; list.append(item); }); root.append(list);
    open("#backupRestoreResultPanel");
  }
  bind("#restoreBackupSettings", async () => { const value = await host.call("backup.restore", {}); if (!value.cancelled) showRestoreResult(value); });
  bind("#openBackupActions", async () => { await loadBackupApps(); open("#backupActionsPanel"); });
  bind("#backupAll", async () => { await host.call("backup.exportAll", {}); say("全部备份已导出。"); close("#backupActionsPanel"); });
  bind("#backupSettings", async () => { await host.call("backup.exportSettings", {}); say("Haminn 设置备份已导出。"); close("#backupActionsPanel"); });

  let autoBackupPicked = false;
  // The retention picker is a bottom sheet in the app's own style, so the value
  // lives in this module instead of a native <select>.
  const KEEP_COUNT_MIN = 1;
  const KEEP_COUNT_MAX = 10;
  let autoBackupKeepCount = 3;
  const keepCountText = value => value + " 份";
  function renderKeepCount() {
    const label = $("#autoBackupKeepCountValue");
    if (label) label.textContent = keepCountText(autoBackupKeepCount);
    selected("#autoBackupKeepCountChoices", "keepCount", String(autoBackupKeepCount));
  }
  function buildKeepCountChoices() {
    const root = $("#autoBackupKeepCountChoices");
    if (!root) return;
    root.replaceChildren();
    for (let value = KEEP_COUNT_MIN; value <= KEEP_COUNT_MAX; value += 1) {
      const button = document.createElement("button");
      button.type = "button";
      button.dataset.keepCount = String(value);
      button.setAttribute("aria-pressed", "false");
      const label = document.createElement("span"); label.textContent = keepCountText(value);
      const check = document.createElement("i");
      check.className = "fa-solid fa-check"; check.setAttribute("aria-hidden", "true");
      button.append(label, check);
      button.onclick = () => { autoBackupKeepCount = value; renderKeepCount(); close("#autoBackupKeepCountPanel"); };
      root.append(button);
    }
  }
  buildKeepCountChoices();
  renderKeepCount();
  $("#autoBackupKeepCountPick").onclick = () => open("#autoBackupKeepCountPanel");
  function two(value) { return String(value).padStart(2, "0"); }
  function formatBytes(value) {
    const bytes = Number(value) || 0;
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
    return (bytes / (1024 * 1024)).toFixed(1) + " MB";
  }
  function autoBackupTime(value) { return two(value.hour) + ":" + two(value.minute); }
  function autoBackupSummary(value) {
    if (!value.enabled) return value.hasDirectory ? "未启用 · 目录：" + (value.directoryName || "已选择") : "未启用";
    return "每天 " + autoBackupTime(value) + " · 保留 " + value.keepCount + " 份" + (value.directoryName ? " · " + value.directoryName : "");
  }
  function autoBackupStatusText(value) {
    if (value.needsPermission) return "备份目录授权已失效，请重新选择目录后保存。";
    if (!value.enabled) return "启用后会每天在设定时间对 Haminn 与全部 happ 做一次完整备份；未选择目录时不会执行。";
    if (!value.exactAlarmAvailable) return "系统未允许精确闹钟，备份时间可能推迟，请在系统设置中允许 Haminn 使用闹钟。";
    const parts = [];
    if (value.lastStatus === "success") parts.push("上次成功：" + (value.lastRunAt ? new Date(value.lastRunAt).toLocaleString() : "已完成") + (value.lastBytes ? "（" + formatBytes(value.lastBytes) + "）" : ""));
    else if (value.lastStatus === "skipped") parts.push("上次跳过：" + (value.lastMessage || "数据没有变化"));
    else if (value.lastStatus === "failed") parts.push("上次失败：" + (value.lastMessage || "未知原因"));
    else parts.push("尚未执行过自动备份。");
    parts.push("下次运行：" + new Date(value.nextRunAt).toLocaleString());
    if (value.batteryPercent !== null && value.batteryPercent <= value.minBatteryPercent) parts.push("当前电量 " + value.batteryPercent + "%，低于 " + value.minBatteryPercent + "% 时不会执行。");
    return parts.join(" ");
  }
  // A busy button replaces its own children while work is in flight, so this
  // label lives outside the picker button and is only ever set through here.
  function setAutoBackupDirectory(name) {
    const label = $("#autoBackupDirectory");
    if (label) label.textContent = name || "尚未选择";
  }
  function renderAutoBackup(value) {
    const summary = $("#autoBackupSummary");
    if (summary) summary.textContent = autoBackupSummary(value);
    const toggle = $("#autoBackupSwitch");
    if (toggle) { toggle.setAttribute("aria-checked", value.enabled ? "true" : "false"); toggle.classList.toggle("on", !!value.enabled); }
    setAutoBackupDirectory(value.directoryName);
    if (Number.isFinite(value.keepCount)) autoBackupKeepCount = value.keepCount;
    renderKeepCount();
    const time = $("#autoBackupTime");
    if (time && document.activeElement !== time) time.value = autoBackupTime(value);
    const status = $("#autoBackupStatus");
    if (status) { status.textContent = autoBackupStatusText(value); status.classList.toggle("error", value.needsPermission || value.lastStatus === "failed"); }
    autoBackupPicked = false;
  }
  async function refreshAutoBackup() {
    const value = await host.call("backup.autoBackup.get", {});
    renderAutoBackup(value);
    return value;
  }
  bind("#openAutoBackup", async () => {
    await refreshAutoBackup();
    open("#autoBackupPanel");
  });
  $("#autoBackupSwitch").onclick = () => {
    const button = $("#autoBackupSwitch");
    const on = button.getAttribute("aria-checked") !== "true";
    button.classList.toggle("on", on);
    button.setAttribute("aria-checked", String(on));
  };
  bind("#autoBackupPickDirectory", async () => {
    const value = await host.call("backup.autoBackup.pickDirectory", {});
    if (value.cancelled) return;
    autoBackupPicked = true;
    setAutoBackupDirectory(value.directoryName || "所选目录");
  });
  bind("#autoBackupSave", async () => {
    const parts = ($("#autoBackupTime").value || "").split(":");
    const hour = Number(parts[0]), minute = Number(parts[1]);
    if (!Number.isInteger(hour) || !Number.isInteger(minute) || hour < 0 || hour > 23 || minute < 0 || minute > 59) {
      say("请设定每天的备份时间。", true);
      return;
    }
    const value = await host.call("backup.autoBackup.save", {
      enabled: $("#autoBackupSwitch").getAttribute("aria-checked") === "true",
      keepCount: autoBackupKeepCount,
      hour,
      minute,
      applyPickedDirectory: autoBackupPicked,
    });
    renderAutoBackup(value);
    close("#autoBackupPanel");
    if (value.enabled) {
      say("自动备份已设置为每天 " + two(hour) + ":" + two(minute) + "。");
      if (!value.exactAlarmAvailable) say("系统未允许精确闹钟，备份时间可能推迟。", true);
    } else say("自动备份已关闭。");
  });
  bind("#autoBackupRunNow", async () => {
    const value = await host.call("backup.autoBackup.runNow", {});
    if (value.outcome === "completed") say("已备份到所选目录" + (value.removed ? "，并清理 " + value.removed + " 份旧备份。" : "。"));
    else say(value.message || "本次未执行备份。", true);
    await refreshAutoBackup();
  });
  async function loadBackupApps() {
    const root = $("#backupApps"); if (!root) return;
    root.replaceChildren();
    const value = await host.call("apps.list", {});
    const apps = value.apps || [];
    if (!apps.length) { root.textContent = "暂无可备份的 happ。"; return; }
    apps.forEach(app => {
      const button = document.createElement("button"); button.className = "backup-app-row"; button.type = "button";
      const icon = document.createElement("span"); icon.className = "backup-app-icon";
      if (app.iconUrl) { icon.style.backgroundImage = "url(" + JSON.stringify(app.iconUrl).slice(1, -1) + ")"; icon.classList.add("custom"); }
      else icon.innerHTML = '<i class="fa-solid fa-cube"></i>';
      const copy = document.createElement("span"); copy.className = "backup-app-copy";
      const name = document.createElement("strong"); name.textContent = app.name || "未命名应用";
      const version = document.createElement("small"); const active = app.activeVersion || {}; version.textContent = active.name || (Number.isFinite(active.code) ? "版本 " + active.code : "版本未标注");
      copy.append(name, version); const action = document.createElement("i"); action.className = "fa-solid fa-download"; action.setAttribute("aria-hidden", "true");
      button.append(icon, copy, action); button.setAttribute("aria-label", "备份 " + (app.name || "应用"));
      button.onclick = () => busy(button, async () => { await host.call("backup.export", { appId: app.appId }); say("已导出“" + app.name + "”备份。"); });
      root.append(button);
    });
  }

  let voiceLoaded = false, voiceStatus = null;
  function option(value, label, selectedValue, disabled = false) {
    const node = document.createElement("option");
    node.value = value; node.textContent = label; node.selected = value === selectedValue; node.disabled = disabled;
    return node;
  }
  function fillProviders(selector, providers, selectedValue, automaticLabel) {
    const select = $(selector);
    select.replaceChildren(option("system", automaticLabel, selectedValue));
    for (const provider of providers || []) select.append(option(provider.id, provider.label, selectedValue, provider.enabled === false));
  }
  function chooseOption(selector, value) {
    const select = $(selector), wanted = value || "automatic";
    Array.from(select.querySelectorAll("option")).forEach(item => { item.selected = item.value === wanted; });
  }
  function setStatus(selector, text, kind) {
    const element = $(selector); element.textContent = text; element.classList.toggle("ready", kind === "ready"); element.classList.toggle("error", kind === "error");
  }
  async function loadTtsVoices(engineId, selectedVoice) {
    const select = $("#ttsVoice");
    select.disabled = true; select.replaceChildren(option("automatic", "正在读取声音…", "automatic"));
    try {
      const value = await host.call("voice.ttsVoices", engineId === "system" ? {} : { engineId });
      select.replaceChildren(option("automatic", "自动选择", selectedVoice || "automatic"));
      for (const voice of value.voices || []) {
        const locality = voice.networkRequired ? "需联网" : "可离线";
        select.append(option(voice.id, (voice.locale || "未知语言") + " · " + locality, selectedVoice));
      }
      select.disabled = !(value.voices || []).length;
    } catch (error) {
      select.replaceChildren(option("automatic", "声音列表不可用", "automatic")); select.disabled = true;
      setStatus("#ttsCapabilityStatus", error.message || "朗读引擎不可用", "error");
    }
  }
  async function loadVoiceSettings(force = false) {
    if (voiceLoaded && !force) return;
    setStatus("#ttsCapabilityStatus", "正在检测…"); setStatus("#speechCapabilityStatus", "正在检测…");
    const value = await host.call("voice.status", {});
    voiceStatus = value;
    const tts = value.tts, speech = value.speech, ttsPrefs = tts.preferences || {}, speechPrefs = speech.preferences || {};
    fillProviders("#ttsEngine", tts.engines, ttsPrefs.engineSelection || "system", "跟随系统默认");
    chooseOption("#ttsLanguage", ttsPrefs.language || "automatic");
    setStatus("#ttsCapabilityStatus", tts.operational ? ((tts.fallbackFrom ? "已自动切换 · " : "可用 · ") + (tts.engines.find(item => item.id === tts.effectiveEngine)?.label || "系统引擎")) : (tts.error || "没有可用朗读引擎"), tts.operational ? "ready" : "error");
    await loadTtsVoices($("#ttsEngine").value, ttsPrefs.voiceSelection === "automatic" ? null : ttsPrefs.voiceSelection);
    fillProviders("#speechService", speech.services, speechPrefs.serviceSelection || "system", "自动选择");
    fillProviders("#speechActivity", speech.activities, speechPrefs.activitySelection || "system", "自动选择");
    chooseOption("#speechLanguage", speechPrefs.language || "automatic");
    $("#speechOfflineSwitch").classList.toggle("on", !!speechPrefs.preferOffline);
    $("#speechOfflineSwitch").setAttribute("aria-checked", String(!!speechPrefs.preferOffline));
    const capability = speech.capability || {};
    const speechLabel = capability.state === "ready" ? (capability.onDeviceAvailable ? "连续识别 · 支持离线" : "连续识别可用") : capability.state === "activity-only" ? "仅支持一次性识别" : (capability.message || "系统未提供语音识别");
    setStatus("#speechCapabilityStatus", speechLabel, capability.state === "unavailable" ? "error" : "ready");
    $("#speechService").disabled = !(speech.services || []).length && !capability.streamingAvailable;
    $("#speechActivity").disabled = !(speech.activities || []).length;
    $("#testSystemTts").disabled = !tts.operational;
    voiceLoaded = true;
  }

  $("#ttsEngine").onchange = event => loadTtsVoices(event.currentTarget.value, null);
  $("#speechOfflineSwitch").onclick = event => {
    const button = event.currentTarget, on = button.getAttribute("aria-checked") !== "true";
    button.classList.toggle("on", on); button.setAttribute("aria-checked", String(on));
  };
  bind("#testSystemTts", () => host.call("voice.testTts", { text: "Haminn 系统朗读测试" }));
  bind("#openTtsSettings", () => host.call("voice.openSettings", { type: "tts" }));
  bind("#openSpeechSettings", () => host.call("voice.openSettings", { type: "speech" }));
  bind("#saveVoiceSettings", async () => {
    const settings = { speech: {
      serviceId: $("#speechService").value, activityId: $("#speechActivity").value,
      language: $("#speechLanguage").value, preferOffline: $("#speechOfflineSwitch").getAttribute("aria-checked") === "true"
    }};
    if (voiceStatus?.tts?.operational) settings.tts = {
      engineId: $("#ttsEngine").value, voiceId: $("#ttsVoice").value, language: $("#ttsLanguage").value
    };
    await host.call("voice.configure", settings);
    voiceLoaded = false; await loadVoiceSettings(true); say("系统语音设置已保存。");
  });

  let shellState = null;
  function renderShellState(value) {
    shellState = value;
    const online = value.configuredMode === "online";
    const currentVersion = value.runningMode === "online" ? H.version : value.localVersion;
    $("#brandDot").classList.toggle("online", online);
    $("#shellVersionSwitch").textContent = "当前界面版本：" + currentVersion + (online ? " · 实时在线" : " · 本地");
    $("#useLocalShell").classList.toggle("hidden", !online);
  }
  async function loadShellStatus() { renderShellState(await host.call("shell.status", {})); }
  let shellVersionTaps = 0, shellVersionTapTimer;
  $("#shellVersionSwitch").onclick = event => {
    clearTimeout(shellVersionTapTimer);
    shellVersionTaps += 1;
    shellVersionTapTimer = setTimeout(() => { shellVersionTaps = 0; }, 1200);
    if (shellVersionTaps < 3) return;
    shellVersionTaps = 0;
    busy(event.currentTarget, async () => {
      if (shellState && shellState.configuredMode === "online") return;
      await host.call("shell.setMode", { mode:"online" });
    });
  };
  bind("#useLocalShell", async () => {
    if (shellState && shellState.configuredMode === "local") return;
    await host.call("shell.setMode", { mode:"local" });
  });
  bind("#updateLocalShell", async () => {
    const value = await host.call("shell.updateLocal", {});
    renderShellState(value);
    say("本地界面已更新到 " + value.localVersion + "。");
  });
  window.haminnShellUnavailable = message => {
    say(message || "界面更新暂时不可用，继续使用当前本地界面。", true);
  };

  async function loadAbout() {
    const value = await host.call("about.info", {});
    $("#aboutVersion").textContent = value.version + "（" + value.versionCode + "）";
    $("#topApkVersion").textContent = value.version;
    $("#aboutAuthor").textContent = value.author;
    $("#aboutAppId").textContent = value.applicationId;
  }

  bind("#supportRepository", () => host.call("about.openRepository", {}));
  async function load() {
    const results = await Promise.allSettled([loadAbout(), loadVoiceSettings(true), loadShellStatus(), refreshAutoBackup()]);
    if (results[1].status === "rejected") setStatus("#speechCapabilityStatus", (results[1].reason && results[1].reason.message) || "语音能力检测失败", "error");
    if (results[2].status === "rejected") $("#shellVersionSwitch").textContent = "界面模式暂不可用";
    if (results[3].status === "rejected") $("#autoBackupSummary").textContent = "自动备份状态读取失败";
  }
  H.features.settings = { loadAbout, loadVoiceSettings, load, openLicenses, openDiagnostics, showSettingsTab, loadStoredTheme, refreshAutoBackup };
})();
