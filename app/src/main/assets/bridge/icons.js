(() => {
  'use strict';
  if (window.HaminnIcons) return;
  const stylesheet = location.origin + '/__haminn/icons/fontawesome/css/all.min.css';
  let pending;
  function load() {
    if (pending) return pending;
    pending = new Promise((resolve, reject) => {
      function attach() {
        const existing = [...document.querySelectorAll('link[rel="stylesheet"]')].find(link => link.href === stylesheet);
        if (existing && existing.sheet) { resolve(); return; }
        // If a parser-created link failed before this code ran, its load/error events
        // are already gone. Create our own observed link rather than wait forever.
        const link = document.createElement('link');
        link.rel = 'stylesheet'; link.href = stylesheet;
        link.dataset.haminnIcons = '7.3.1';
        link.onload = () => resolve();
        link.onerror = () => { pending = null; link.remove(); reject(new Error('Font Awesome could not load. Check the page style-src and font-src CSP.')); };
        document.head.append(link);
      }
      if (document.head) attach();
      else document.addEventListener('DOMContentLoaded', attach, { once: true });
    });
    return pending;
  }
  const api = Object.freeze({
    version: '7.3.1', stylesheet, load,
    create(name, options = {}) {
      if (!/^[a-z0-9-]+$/.test(name)) throw new TypeError('Invalid icon name');
      const style = options.style || 'solid';
      if (!['solid', 'regular', 'brands'].includes(style)) throw new TypeError('Unsupported icon style');
      const icon = document.createElement('i');
      icon.className = `fa-${style} fa-${name}`;
      if (options.label) { icon.setAttribute('role', 'img'); icon.setAttribute('aria-label', options.label); }
      else icon.setAttribute('aria-hidden', 'true');
      return icon;
    }
  });
  Object.defineProperty(window, 'HaminnIcons', { value: api, configurable: false });
  load().catch(() => { /* A remote page owns its CSP; never relax it to inject icons. */ });
})();
