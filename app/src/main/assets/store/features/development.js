(() => {
  "use strict";
  const H = window.HaminnShell;
  const { $, state, host } = H;
  const { say, bind, confirmAction, open, close } = H.ui;
  const copy = async (text, label) => { await host.copy(text); say(label + "已复制。"); };
  let agentState = null, agentPoll = false;
  let pendingChangeRevision = 0;
  const ENDPOINT_ACK_KEY = "haminn.agent-endpoint-ack.v1";

  function acknowledgedRevision() {
    try { return Number(localStorage.getItem(ENDPOINT_ACK_KEY) || 0); } catch (_) { return 0; }
  }

  function acknowledgeEndpointChange() {
    if (!pendingChangeRevision) return;
    try { localStorage.setItem(ENDPOINT_ACK_KEY, String(pendingChangeRevision)); } catch (_) {}
    pendingChangeRevision = 0;
  }

  function maybeShowEndpointChange(value) {
    const change = value && value.endpointChange;
    const revision = Number(change && change.revision || 0);
    if (!revision || revision <= acknowledgedRevision()) return;
    pendingChangeRevision = revision;
    const available = !!change.available;
    const previous = change.previousAddress || "无可用地址";
    const current = change.address || "未连接 Wi-Fi 或手机热点";
    $("#agentEndpointTitle").textContent = available ? "开发服务地址已变化" : "开发服务暂不可达";
    $("#agentEndpointMessage").textContent = available
      ? "请重新告知智能体软件新的地址。"
      : "开发模式仍保持开启。请连接 Wi-Fi 或手机热点，恢复后会自动生成可用地址。";
    $("#agentPreviousAddress").textContent = previous;
    $("#agentCurrentAddress").textContent = current;
    open("#agentEndpointPanel");
  }

  function renderAgent(value) {
    agentState = value;
    const enabled = value.enabled == null ? !!value.active : !!value.enabled;
    const reachable = !!value.address && value.networkAvailable !== false;
    $("#agentStatus").textContent = enabled
      ? (reachable ? "已开启 · 普通 happ 可开发" : value.active ? "已开启 · 等待 Wi-Fi 或手机热点" : "已开启 · 服务正在恢复")
      : "未开启" + (value.reason ? " · " + value.reason : "");
    $(".developer-card").classList.toggle("active", enabled);
    $("#developmentDot").classList.toggle("hidden", !enabled);
    $("#agentToggle").classList.toggle("on", enabled);
    $("#agentToggle").setAttribute("aria-checked", String(enabled));
    $("#agentToggle").setAttribute("aria-label", enabled ? "关闭智能体开发模式" : "开启智能体开发模式");
    $("#copyAgentAddress").disabled = !reachable;
    $("#copyAgentUsb").disabled = !value.active;
    $("#startAgentUsb").classList.toggle("hidden", enabled);
    const firstIp = (value.addresses || [])[0];
    const address = value.address || (firstIp ? "http://" + firstIp + ":8766" : "");
    $("#agentUrl").textContent = address || "未连接 Wi-Fi";
    $("#agentUrl").dataset.address = address;
    $("#agentUsb").textContent = value.usbCommand || "adb forward tcp:8766 tcp:8766";
    $("#agentPassword").value = value.password || "";
    maybeShowEndpointChange(value);
  }

  async function refreshAgent() {
    if (agentPoll) return;
    agentPoll = true;
    try { renderAgent(await host.call("agent.status")); } finally { agentPoll = false; }
  }

  bind("#agentToggle", async () => {
    const enabled = agentState && (agentState.enabled == null ? agentState.active : agentState.enabled);
    if (enabled) {
      await host.call("agent.stop");
      renderAgent(await host.call("agent.status"));
      say("开发模式已关闭。");
      return;
    }
    const address = agentState && (agentState.addresses || [])[0];
    if (!address) throw new Error("当前没有可用的开发服务地址，可使用下方“仅 USB 启动”。");
    if (!await confirmAction("开启智能体开发模式？", "持有密码的电脑可修改普通 happ 的开发副本。请仅在可信局域网使用。", "开启开发模式")) return;
    renderAgent(await host.call("agent.start", { address, mode:"lan" }));
    say("开发模式已开启，请仅向可信智能体分享连接信息。");
  });

  bind("#startAgentUsb", async () => {
    if (!await confirmAction("仅通过 USB 启动？", "电脑需要执行页面显示的 adb forward 命令，接口和开发副本与局域网模式完全相同。", "启动 USB 模式")) return;
    renderAgent(await host.call("agent.start", { mode:"usb" }));
    say("USB 开发服务已启动，请在电脑执行转发命令。");
  });

  bind("#editAgentPassword", () => {
    $("#agentPasswordDraft").value = agentState && agentState.password || $("#agentPassword").value || "";
    open("#agentPasswordPanel");
  });
  bind("#randomAgentPassword", () => {
    const random = new Uint32Array(1);
    crypto.getRandomValues(random);
    $("#agentPasswordDraft").value = String(random[0] % 1_000_000).padStart(6, "0");
  });
  bind("#saveAgentPassword", async () => {
    const password = $("#agentPasswordDraft").value.trim();
    if (!/^[0-9]{6}$/.test(password)) throw new Error("密码必须是 6 位数字。");
    const value = await host.call("agent.resetPassword", { password });
    renderAgent(value);
    close("#agentPasswordPanel");
    say("新密码已生效，旧密码已失效。");
  });
  $("#agentPasswordDraft").oninput = event => { event.currentTarget.value = event.currentTarget.value.replace(/\D/g, "").slice(0, 6); };
  bind("#copyAgentAddress", () => {
    const address = $("#agentUrl").dataset.address;
    if (!address) throw new Error("开发服务尚未启动。");
    return copy(address, "插件安装地址");
  });
  bind("#refreshAgentAddress", async () => {
    renderAgent(await host.call("agent.refresh"));
    say(agentState && agentState.address ? "插件安装地址已刷新。" : "开发模式保持开启，正在等待 Wi-Fi 或手机热点。");
  });
  bind("#copyAgentUsb", () => copy($("#agentUsb").textContent, "USB 命令"));
  bind("#ackAgentEndpoint", () => { acknowledgeEndpointChange(); close("#agentEndpointPanel"); });
  $("#agentEndpointPanel").addEventListener("haminnmodalclose", acknowledgeEndpointChange);
  setInterval(async () => {
    if (document.hidden || !host.ready() || (state.view !== "development" && !(agentState && agentState.enabled))) return;
    try { await refreshAgent(); } catch (_) {}
  }, 3000);
  H.features.development = { refreshAgent };
  H.copy = copy;
})();
