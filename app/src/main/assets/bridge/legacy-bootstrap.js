(() => {
  "use strict";
  if (window.__haminnTransportV1) return;
  const native = window.__haminnLegacyNativeV1;
  if (!native || typeof native.postMessage !== "function") return;
  const listeners = new Set();
  window.__haminnLegacyReceiveV1 = value => {
    const event = Object.freeze({ data: String(value) });
    for (const listener of [...listeners]) {
      try { listener(event); } catch (error) { console.error(error); }
    }
  };
  Object.defineProperty(window, "__haminnTransportV1", {
    configurable: false,
    value: Object.freeze({
      postMessage(value) { native.postMessage(String(value)); },
      addEventListener(type, listener) { if (type === "message" && typeof listener === "function") listeners.add(listener); },
      removeEventListener(type, listener) { if (type === "message") listeners.delete(listener); }
    })
  });
})();
