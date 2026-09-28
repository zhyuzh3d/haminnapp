(() => {
  "use strict";
  const H = window.HaminnShell;
  const { $, state, host } = H;
  const { say, busy, bind, open, close } = H.ui;

  function bytes(value) {
    const amount = Number(value || 0);
    if (amount < 1024) return amount + " B";
    if (amount < 1024 * 1024) return (amount / 1024).toFixed(1) + " KiB";
    return (amount / 1024 / 1024).toFixed(1) + " MiB";
  }
  function version(value) {
    if (value.versionName) return String(value.versionName);
    if (Number.isFinite(Number(value.versionCode))) return "版本 " + Number(value.versionCode);
    return "未标注版本";
  }
  function showIcon(root, url, name) {
    root.replaceChildren(); root.style.backgroundImage = ""; root.classList.remove("custom");
    if (url) { root.style.backgroundImage = "url(" + JSON.stringify(url).slice(1,-1) + ")"; root.classList.add("custom"); return; }
    root.textContent = Array.from(String(name || "H").trim() || "H")[0].toUpperCase();
  }
  function setShortcutSwitch(enabled) {
    state.shareCreateShortcut = enabled;
    $("#shareCreateShortcut").classList.toggle("on", enabled);
    $("#shareCreateShortcut").setAttribute("aria-checked", String(enabled));
  }
  async function openOutbound(app) {
    if (!app.localAvailable) throw new Error("此 happ 没有可分享的本地安装包。");
    const value = await host.call("apps.shareStart", { appId:app.appId });
    state.outboundShare = value;
    $("#shareTitle").textContent = "分享“" + value.name + "”";
    showIcon($("#shareAppIcon"), value.iconUrl, value.name);
    $("#shareAppName").textContent = value.name;
    $("#shareAppMeta").textContent = version(value) + " · " + bytes(value.bytes) + " · " + (value.snapshot === "development" ? "开发快照" : "正式版本");
    const ready = !!value.networkAvailable && !!value.qrUrl;
    $("#shareQrBox").classList.toggle("hidden", !ready);
    $("#shareQr").src = ready ? value.qrUrl : "";
    // The QR box carries the expiry itself, so one line is all the sheet needs.
    $("#shareNetworkHint").textContent = ready
      ? "让朋友使用Haminn应用扫码即可安装同款应用。二维码1小时有效。"
      : "未检测到可用局域网，不会启动下载服务。你仍可保存或发送安装包。";
    open("#sharePanel");
  }
  function openInbound(value) {
    state.inboundShare = value;
    showIcon($("#shareInstallIcon"), value.iconUrl, value.name);
    $("#shareInstallName").textContent = value.name;
    $("#shareInstallMeta").textContent = version(value) + " · " + bytes(value.bytes);
    $("#shareInstallAuthor").textContent = value.author || H.i18n.t("未标注");
    $("#shareInstallSource").textContent = (value.device || "另一台设备") + " · " + (value.snapshot === "development" ? "开发快照" : "正式版本");
    $("#shareInstallSignature").textContent = value.signed ? "带发布者签名" : value.snapshot === "development" ? "未签名开发快照" : "未签名安装包";
    setShortcutSwitch(true);
    open("#shareInstallPanel");
  }

  bind("#saveSharedPackage", async () => {
    const value = state.outboundShare;
    if (!value) throw new Error("分享会话已结束。");
    const result = await host.call("apps.shareSave", { sessionId:value.sessionId });
    say(result.cancelled ? "已取消保存。" : "安装包已保存。");
  });
  bind("#sendSharedPackage", async () => {
    const value = state.outboundShare;
    if (!value) throw new Error("分享会话已结束。");
    await host.call("apps.shareSend", { sessionId:value.sessionId });
  });
  bind("#stopHappShare", async () => {
    const value = state.outboundShare;
    if (value) await host.call("apps.shareStop", { sessionId:value.sessionId });
    state.outboundShare = null; $("#shareQr").src = ""; close("#sharePanel"); say("已停止分享。");
  });
  $("#shareCreateShortcut").onclick = () => setShortcutSwitch(!state.shareCreateShortcut);
  bind("#installSharedHapp", async () => {
    const value = state.inboundShare;
    if (!value) throw new Error("分享信息已失效，请重新扫码。");
    const installed = await host.call("apps.shareInstall", { shareId:value.shareId, createShortcut:state.shareCreateShortcut });
    state.inboundShare = null; close("#shareInstallPanel");
    await H.features.library.refresh();
    say(state.shareCreateShortcut
      ? installed.shortcutRequested ? "happ 已安装，已请求创建桌面图标。" : "happ 已安装，当前桌面没有接受快捷图标请求。"
      : "happ 已安装。");
  });

  H.features.share = { openOutbound, openInbound };
})();
