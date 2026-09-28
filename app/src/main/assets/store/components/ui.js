(() => {
  "use strict";
  const H = window.HaminnShell;
  const { $, $$, state } = H;
  const notice = $("#notice");
  const say = (message, error = false) => {
    $("#noticeText").textContent = message;
    $("#noticeIcon").className = "fa-solid " + (error ? "fa-circle-exclamation" : "fa-circle-check");
    notice.classList.toggle("error", error); notice.classList.remove("hidden");
    clearTimeout(say.timer);
    if (!error) say.timer = setTimeout(() => notice.classList.add("hidden"), 4500);
  };
  $("#dismissNotice").onclick = () => notice.classList.add("hidden");
  async function busy(button, work) {
    if (button.disabled || (button.closest(".modal") && button.closest(".modal").dataset.working)) return;
    const children = [...button.childNodes];
    const spinner = document.createElement("i"); spinner.className = "fa-solid fa-circle-notch fa-spin"; spinner.setAttribute("aria-hidden", "true");
    const preserveContents = button.classList.contains("icon-picker");
    // Buttons that relabel themselves while working keep their contents and only gain a spinner.
    const keepContents = !preserveContents && button.dataset.busyPreserve === "true";
    if (keepContents) button.appendChild(spinner);
    else if (!preserveContents && button.classList.contains("icon-button")) button.replaceChildren(spinner);
    else if (!preserveContents) button.replaceChildren(spinner, document.createTextNode("处理中…"));
    button.disabled = true; button.setAttribute("aria-busy", "true");
    const modal = button.closest(".modal");
    const locked = modal ? [...modal.querySelectorAll("input,button")].filter(control => control !== button).map(control => ({ control, disabled: control.disabled })) : [];
    locked.forEach(item => { item.control.disabled = true; });
    if (modal) { modal.dataset.working = "true"; modal.classList.add("working"); }
    try { return await work(); }
    catch (error) { say(error.code === "E_CANCELLED" ? "已取消操作。" : (error.message || "操作失败，请重试。"), error.code !== "E_CANCELLED"); }
    finally { locked.forEach(item => { item.control.disabled = item.disabled; }); if (modal) { delete modal.dataset.working; modal.classList.remove("working"); } button.disabled = false; button.removeAttribute("aria-busy");
      if (keepContents) spinner.remove(); else if (!preserveContents) button.replaceChildren(...children);
      if (H.onOperationSettled) H.onOperationSettled(modal);
    }
  }
  const bind = (selector, work) => { $(selector).onclick = event => busy(event.currentTarget, work); };
  const focusable = root => [...root.querySelectorAll("button:not(:disabled),input:not(:disabled),a[href],summary,[tabindex='0']")].filter(el => el.getClientRects().length && !el.closest("[inert]"));
  function syncModals() {
    const topEntry = state.modals[state.modals.length - 1];
    const top = topEntry && topEntry.element;
    $("#shell").inert = !!top;
    if (top) $("#shell").setAttribute("aria-hidden", "true"); else $("#shell").removeAttribute("aria-hidden");
    state.modals.forEach((entry, index) => { entry.element.style.zIndex = 10 + index; });
    $$(".modal").forEach(el => { el.inert = el !== top;
      if (el !== top) el.setAttribute("aria-hidden", "true"); else el.removeAttribute("aria-hidden"); });
    document.body.classList.toggle("modal-open", !!top);
  }
  function open(selector) {
    const element = $(selector);
    if (state.modals.some(item => item.element === element)) return;
    element.removeAttribute("aria-hidden");
    state.modals.push({ element, previous: document.activeElement });
    element.classList.remove("hidden"); syncModals();
    // Focus a close button, not an input: opening a sheet should not summon the keyboard.
    const initialFocus = element.querySelector("[data-close]") || focusable(element)[0];
    if (initialFocus) initialFocus.focus({ preventScroll: true });
  }
  function close(selector) {
    const index = state.modals.findIndex(item => item.element === $(selector));
    if (index < 0) return;
    const removed = state.modals.splice(index);
    removed.forEach(item => { item.element.classList.add("hidden"); item.element.dispatchEvent(new Event("haminnmodalclose")); }); syncModals();
    const previous = removed[0].previous;
    const topEntry = state.modals[state.modals.length - 1];
    const fallback = (topEntry && topEntry.element) || $("#shell");
    if (previous && previous.isConnected && !previous.closest("[inert]") && !previous.disabled && previous.getClientRects().length) previous.focus({ preventScroll: true });
    else {
      const fallbackFocus = focusable(fallback)[0];
      if (fallbackFocus) fallbackFocus.focus({ preventScroll: true });
    }
  }
  let confirmResult;
  let hostPrompt = null;
  function confirmAction(title, message, label = "继续", destructive = false) {
    if (confirmResult) return Promise.resolve(false);
    $("#confirmTitle").textContent = title; $("#confirmMessage").textContent = message;
    $("#acceptConfirm").textContent = label;
    $("#acceptConfirm").classList.toggle("danger", destructive);
    open("#confirmPanel"); $("#cancelConfirm").focus();
    return new Promise(resolve => { confirmResult = resolve; });
  }
  function finishConfirm(accepted) {
    const result = confirmResult; confirmResult = null;
    close("#confirmPanel"); if (result) result(accepted);
  }
  $("#cancelConfirm").onclick = () => finishConfirm(false);
  $("#acceptConfirm").onclick = () => finishConfirm(true);
  function nativePrompt(payload) {
    if (hostPrompt || !payload || !payload.token || !Array.isArray(payload.choices) || !payload.choices.length) return false;
    const choices = payload.choices.filter(choice => choice && typeof choice.value === "string" && typeof choice.label === "string");
    if (!choices.length) return false;
    hostPrompt = { token:payload.token, choices };
    $("#hostPromptTitle").textContent = String(payload.title || "确认操作");
    $("#hostPromptMessage").textContent = String(payload.message || "");
    const actions = $("#hostPromptActions"); actions.replaceChildren();
    choices.forEach(choice => {
      const button = document.createElement("button");
      button.textContent = choice.label;
      if (choice.emphasis === "primary") button.classList.add("primary");
      if (choice.emphasis === "danger") button.classList.add("danger");
      button.onclick = () => busy(button, () => finishHostPrompt(choice.value));
      actions.append(button);
    });
    open("#hostPromptPanel");
    actions.querySelector("button")?.focus();
    return true;
  }
  async function finishHostPrompt(choice) {
    const prompt = hostPrompt;
    if (!prompt) return;
    await H.host.call("dialog.resolve", { token:prompt.token, choice });
    hostPrompt = null;
    close("#hostPromptPanel");
  }
  async function cancelHostPrompt() {
    const prompt = hostPrompt;
    if (!prompt) return;
    const cancel = prompt.choices.find(choice => choice.value === "cancel") || prompt.choices[0];
    await finishHostPrompt(cancel.value);
  }
  window.haminnNativePrompt = nativePrompt;
  function selected(group, key, value) {
    $$(group + " button").forEach(button => {
      const active = button.dataset[key] === value;
      button.classList.toggle("selected", active); button.setAttribute("aria-pressed", String(active));
    });
  }
  async function requestClose(selector) {
    if (state.modals.some(entry => entry.element.dataset.working)) { say("正在完成操作，请稍候。"); return; }
    if (H.beforeClose && !await H.beforeClose(selector)) return;
    close(selector);
  }
  H.ui = { requestClose, say, busy, bind, open, close, confirmAction, selected, finishConfirm, cancelHostPrompt, focusable, get confirming() { return !!confirmResult; }, get hostPrompting() { return !!hostPrompt; } };
})();
