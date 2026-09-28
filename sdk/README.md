# Haminn page SDK

Author pages directly with native HTML, JavaScript, and CSS. No React/Vue, Vite/Webpack, Node/npm, bundler, or transpilation is required or specially supported. Already-generated static output from other tools is accepted under the same standard runtime rules. The .d.ts file is optional editor assistance, not a TypeScript build requirement. See [the authoring guide](../docs/webapp-authoring.md).

Font Awesome Free 7.3.1 is bundled offline and its CSS is attached automatically at the app origin. Use `<i class="fa-solid fa-heart" aria-hidden="true"></i>` directly, or `haminn.icons.create('heart')`. `await haminn.icons.load()` waits for the stylesheet; use `document.fonts.load(...)` if font decoding completion matters. A remote site's CSP remains authoritative; see the guide for `style-src`/`font-src` requirements. The icon resource prefix is `/__haminn/icons/fontawesome/` and exposes public read-only assets only. It grants no native capability and requires no RPC handshake.

Haminn injects `window.haminn` at document start for the exact top-level origin. Do not bundle a native transport or persist `sessionId`; each navigation receives a new document session.

Wait for `haminnready` or check `haminn.isReady`, then call a namespace method with one object argument. Store durable binary references as `logicalFileId`; `HaminnFile.url` is the same-origin object URL for rendering or upload, while the physical path never enters page code or the database. Treat revisions and subscription IDs as opaque. Catch errors by `error.code`; user cancellation, a denied page grant, a denied Android permission, and an unavailable system service are intentionally different results.

Images, recordings, videos, and imported files are persisted as filesystem objects with URL metadata. Base64 is reserved for explicitly transient protocols such as `pickInline` or bounded binary bridge chunks; do not put those values into `haminn.data` or any durable record.

The source of truth is `api/capabilities.json`, `api/protocol-v1.md`, and `haminn-api.d.ts`. Host methods are not available to installed page applications.
