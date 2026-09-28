(() => {
  "use strict";
  const H = window.HaminnShell;
  const { $, $$, state, host } = H;
  const { say, busy, bind, open, close, confirmAction, selected } = H.ui;
  const sourceLabels = { "online-manifest": "线上安装包", "https-package": "线上安装包", github: "GitHub 仓库", gitlab: "GitLab 仓库", gitee: "Gitee 仓库", zip: "本地 ZIP", import: "本地 ZIP", directory: "本地文件夹", agent: "本地创建", online: "线上网址" };
  function appSource(app) { return app.source || (["online", "online-manifest", "https-package", "github"].includes(app.sourceAdapter) ? "online" : "local"); }
  function appRuntime(app) { return app.runtimeMode || (app.liveUrl && !app.activeReleaseId ? "live" : "local"); }
  function hasLocal(app) { return typeof app.localAvailable === "boolean" ? app.localAvailable : !!app.activeReleaseId; }
  function hasLive(app) { return typeof app.liveAvailable === "boolean" ? app.liveAvailable : !!app.liveUrl; }
  function sourceLabel(app) { return sourceLabels[app.sourceAdapter] || (appSource(app) === "online" ? "线上 happ" : "本地 happ"); }
  function runtimeLabel(app) { return appRuntime(app) === "live" ? "线上实时运行" : "本地运行"; }
  function versionLabel(app) {
    for (const value of [app.activeVersion, app.downloadVersion]) {
      if (!value || typeof value !== "object") continue;
      if (typeof value.name === "string" && value.name.trim()) return value.name.trim().slice(0, 80);
      if (Number.isFinite(value.code)) return "版本 " + Math.trunc(value.code);
    }
    return "未标注版本";
  }
  function filterApps() {
    const favoritesOnly = state.libraryFilter === "favorites";
    const candidates = favoritesOnly ? state.apps.filter(app => app.favorite) : state.apps;
    $$(".app-card").forEach(card => {
      const inCollection = !favoritesOnly || card.dataset.favorite === "true";
      card.classList.toggle("hidden", !inCollection);
    });
    $("#empty").classList.toggle("hidden", candidates.length !== 0);
    $("#collectionPrompt").textContent = favoritesOnly
      ? "已收藏【" + candidates.length + "】个HAPP应用"
      : "已安装【" + candidates.length + "】个HAPP应用";
    $("#emptyTitle").textContent = favoritesOnly ? "收藏你的第一个应用" : "添加你的第一个应用";
    $("#emptyMessage").textContent = favoritesOnly ? "点亮应用卡片上的爱心，常用工具就会集中在这里。" : "添加在线网址或导入原生 HTML、JavaScript 和 CSS 页面。";
    selected("#libraryTabs", "collection", state.libraryFilter);
  }
  function resetFilters() { filterApps(); }
  function shortcutState(app) {
    return ["pinned", "notPinned", "unsupported", "unknown"].includes(app.desktopShortcutState)
      ? app.desktopShortcutState : "unknown";
  }
  function renderPinButton(button, app) {
    const value = shortcutState(app), pinned = value === "pinned";
    button.classList.toggle("active", pinned);
    button.classList.toggle("unavailable", value === "unknown" || value === "unsupported");
    button.setAttribute("aria-pressed", String(pinned));
    const label = pinned ? "已添加到手机桌面" : value === "unsupported" ? "当前桌面不支持固定图标" : value === "unknown" ? "无法确认桌面图标状态" : "添加到手机桌面";
    button.setAttribute("aria-label", label);
    button.title = label;
    const copy = button.querySelector(".pin-label");
    if (copy) copy.textContent = pinned ? "已添加到桌面" : value === "unsupported" ? "桌面不支持" : value === "unknown" ? "添加到桌面" : "添加到桌面";
    return value;
  }
  async function pin(app) {
    const current = shortcutState(app);
    if (current === "pinned") { say("桌面图标仍然存在；如需移除，请在桌面长按图标删除。"); return; }
    if (current === "unsupported") { say("当前桌面不支持固定图标。"); return; }
    const value = await host.call("apps.pin", { appId: app.appId });
    say(value.requested ? "已请求添加到手机桌面，请确认系统提示。" : "当前桌面不支持固定图标。");
  }
  $$("#libraryTabs [data-collection]").forEach(button => {
    button.onclick = () => {
      state.libraryFilter = button.dataset.collection === "all" ? "all" : "favorites";
      filterApps();
      if (H.navigation) H.navigation.captureViewState("favorites");
      window.scrollTo(0, 0);
    };
  });
  let refreshEpoch = 0, renderedApps = null;
  async function refresh() {
    const epoch = ++refreshEpoch;
    const result = await host.call("apps.list", {});
    if (epoch !== refreshEpoch) return;
    state.apps = result.apps || [];
    $("#libraryUnavailable").classList.add("hidden");
    $("#loading").classList.add("hidden");
    // The mounted cards already say exactly what this answer says. A read that changes
    // nothing leaves the list untouched, so coming back to the foreground cannot rebuild
    // it, jump the scroll or drop the element the user was typing in. Real changes still
    // rebuild below.
    const rendered = JSON.stringify(state.apps);
    if (rendered === renderedApps) return;
    renderedApps = rendered;
    const root = $("#apps"), cards = document.createDocumentFragment();
    for (const app of state.apps) {
      const fragment = $("#appTemplate").content.cloneNode(true), card = fragment.querySelector(".app-card");
      card.dataset.appId = app.appId;
      card.dataset.search = [app.name, app.startUrl, app.liveUrl, app.sourceAdapter, sourceLabel(app), runtimeLabel(app)].join(" ").toLocaleLowerCase();
      card.dataset.source = appSource(app);
      card.dataset.favorite = String(!!app.favorite);
      const runningDev = app.launchChannel === "dev";
      card.classList.toggle("dev", runningDev);
      fragment.querySelector(".dev-badge").classList.toggle("hidden", !runningDev);
      fragment.querySelector(".app-name").textContent = app.name;
      fragment.querySelector(".app-source").textContent = runningDev ? "运行开发副本 · 本机代码" : runtimeLabel(app) + (appRuntime(app) === "live" ? " · " + app.startUrl : " · 本机代码");
      const color = ["blue", "violet", "orange", "green"][[...app.appId].reduce((n,c) => n + c.charCodeAt(0), 0) % 4];
      const appIcon = fragment.querySelector(".app-icon"), appIconMark = appIcon.querySelector("i");
      if (app.iconUrl) { appIcon.style.backgroundImage = "url(" + JSON.stringify(app.iconUrl).slice(1,-1) + ")"; appIcon.classList.add("custom"); appIconMark.classList.add("hidden"); }
      else { appIcon.classList.add(color); appIconMark.className = "fa-solid " + (appSource(app) === "local" ? "fa-cube" : "fa-globe"); }
      fragment.querySelector(".version-badge").textContent = versionLabel(app);
      fragment.querySelector(".launch").onclick = event => busy(event.currentTarget, () => host.call("apps.launch", { appId: app.appId }));
      const favorite = fragment.querySelector(".favorite"), favoriteIcon = favorite.querySelector("i");
      favorite.classList.toggle("active", !!app.favorite);
      favorite.setAttribute("aria-pressed", String(!!app.favorite));
      favorite.setAttribute("aria-label", app.favorite ? "取消收藏" : "收藏应用");
      favorite.title = app.favorite ? "取消收藏" : "收藏应用";
      favoriteIcon.className = (app.favorite ? "fa-solid" : "fa-regular") + " fa-heart";
      favorite.onclick = event => busy(event.currentTarget, async () => {
        const updated = await host.call("apps.favorite", { appId: app.appId, favorite: !app.favorite });
        app.favorite = !!updated.favorite;
        await refresh();
        say(app.favorite ? "已加入收藏。" : "已取消收藏。");
      });
      const pinButton = fragment.querySelector(".pin");
      const pinState = renderPinButton(pinButton, app);
      pinButton.disabled = pinState === "unsupported";
      pinButton.onclick = event => busy(event.currentTarget, () => pin(app));
      const shareButton = fragment.querySelector(".share");
      shareButton.disabled = !hasLocal(app);
      shareButton.setAttribute("aria-label", hasLocal(app) ? "分享应用" : "没有可分享的本地安装包");
      shareButton.title = hasLocal(app) ? "分享应用" : "没有可分享的本地安装包";
      shareButton.onclick = event => busy(event.currentTarget, () => H.features.share.openOutbound(app));
      fragment.querySelector(".manage").onclick = () => H.features.manage.openManage(app).catch(error => say(error.message, true));
      cards.append(fragment);
    }
    root.replaceChildren(cards);
    filterApps();
  }
  H.features.library = { refresh, filterApps, resetFilters, pin, renderPinButton, appSource, appRuntime, hasLocal, hasLive, sourceLabel, runtimeLabel, versionLabel };
})();
