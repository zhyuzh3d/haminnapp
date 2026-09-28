(() => {
  "use strict";
  const H = window.HaminnShell, { $, state, host } = H;
  H.beforeClose = async selector => {
    if (selector === "#hostPromptPanel") { await H.ui.cancelHostPrompt(); return false; }
    if (selector !== "#managePanel" || !H.features.manage.isDirty()) return true;
    return H.ui.confirmAction("放弃尚未保存的修改？", "已保存的应用数据不受影响。", "放弃修改", true);
  };
  H.onOperationSettled = modal => { if (modal && modal.id === "managePanel") H.features.manage.renderManageDraft(); };
  let starting = false, connected = false;
  function libraryError(error) {
    $("#loading").classList.add("hidden");
    $("#empty").classList.add("hidden");
    $("#libraryUnavailable").classList.remove("hidden");
    $("#libraryErrorText").textContent = error.message || "应用库读取失败，请重试。";
  }
  async function start() {
    if (starting || !host.ready()) return;
    starting = true; connected = true;
    // The stored theme is applied before the first real screen is rendered.
    await H.features.settings.loadStoredTheme();
    $("#browserNotice").classList.add("hidden");
    $("#hostActions").classList.remove("hidden");
    $("#loading").classList.remove("hidden");
    if (!state.viewMounted) await H.navigation.showView(H.navigation.initialView(), false);
    try { await H.features.library.refresh(); } catch (error) { libraryError(error); }
    starting = false;
    if (state.view !== "favorites") {
      try { await H.navigation.showView(state.view); } catch (error) { H.ui.say(error.message || "页面恢复失败，请重试。", true); }
    } else H.navigation.restoreViewScroll(state.view, state.viewEpoch);
    const reads = [H.features.settings.loadAbout(), H.features.development.refreshAgent()];
    const results = await Promise.allSettled(reads);
    if (results[0].status === "rejected") $("#topApkVersion").textContent = "暂不可用";
    if (results[1].status === "rejected") $("#agentStatus").textContent = "开发状态读取失败，进入开发页可重试。";
    H.started = true;
    dispatchEvent(new Event("haminnshellstarted"));
  }
  $("#retryLibrary").onclick = start;
  addEventListener("haminnready", start);
  // Returning to the foreground must not change what the user was looking at: the page was
  // never unloaded, so the mounted view stays exactly as it was and only what can have
  // changed while Haminn was away is re-read. The system signal and the Host signal mean the
  // same thing, so a single return runs one pass instead of replaying the view twice.
  let returning = null;
  function resyncOnReturn() {
    if (!connected || !host.ready()) return Promise.resolve();
    if (!returning) returning = H.features.library.refresh().then(() => {
      if (!state.selected) return;
      state.selected = state.apps.find(app => app.appId === state.selected.appId) || state.selected;
      if (H.features.manage && !$("#managePanel").classList.contains("hidden")) H.features.manage.renderManageDraft();
    }).catch(error => { H.ui.say(error.message || "桌面图标状态刷新失败，请重试。", true); }).then(() => { returning = null; });
    return returning;
  }
  document.addEventListener("visibilitychange", () => { if (!document.hidden) resyncOnReturn(); });
  addEventListener("haminnresume", resyncOnReturn);
  if (host.ready()) start();
  else {
    H.navigation.showView(H.navigation.initialView(), false);
    $("#loading").classList.add("hidden"); $("#empty").classList.add("hidden");
    $("#browserNotice").classList.remove("hidden");
    $("#hostActions").classList.add("hidden");
    $("#topApkVersion").textContent = "未连接";
    $("#agentStatus").textContent = "请在 HaminnApp 中查看开发状态。";
    $("#aboutVersion").textContent = "未连接 HaminnApp";
    $("#topWebVersion").textContent = H.version;
    H.started = true;
    dispatchEvent(new Event("haminnshellstarted"));
  }
})();
