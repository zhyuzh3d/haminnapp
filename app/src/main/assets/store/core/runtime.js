(() => {
  "use strict";
  try {
    if (!window.CSS || !CSS.supports || !CSS.supports("selector(:is(*))")) document.documentElement.classList.add("legacy-webview");
  } catch (_) { document.documentElement.classList.add("legacy-webview"); }
  if (!Element.prototype.replaceChildren) {
    Element.prototype.replaceChildren = function (...nodes) {
      while (this.firstChild) this.removeChild(this.firstChild);
      this.append(...nodes);
    };
  }
  const $ = selector => document.querySelector(selector);
  const $$ = selector => [...document.querySelectorAll(selector)];
  const HAMINN_WEB_VERSION = "1.12.6";
  const VIEW_STATE_KEY = "haminn.shell.view-state.v1";
  const VIEWS = ["favorites", "development", "settings", "icons", "support"];
  const state = { apps: [], selected: null, deploy: null, iconStyle: "all", iconLimit: 60, settingsTab: "interface", modals: [], view: "favorites", libraryFilter: "favorites", viewEpoch: 0, viewMounted: false, addToFavorites: true, addDraft: null, managedEpoch: 0 };
  window.HaminnShell = { $, $$, state, version: HAMINN_WEB_VERSION, VIEW_STATE_KEY, VIEWS, features: {} };
})();
