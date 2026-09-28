import fs from "node:fs";

for (const path of [
  "app/src/main/assets/bridge/haminn-v1.js",
  "app/src/main/assets/bridge/icons.js",
  "app/src/main/assets/shared/fontawesome/css/all.min.css",
  "app/src/main/assets/shared/fontawesome/LICENSE.txt",
  "app/src/main/assets/shared/fontawesome/webfonts/fa-solid-900.woff2",
  "app/src/main/assets/shared/fontawesome/webfonts/fa-regular-400.woff2",
  "app/src/main/assets/shared/fontawesome/webfonts/fa-brands-400.woff2",
  "app/src/main/assets/store/icon-catalog.js",
  "app/src/main/assets/store/index.html",
  "app/src/main/assets/store/store.css",
  "app/src/main/assets/store/store.js",
  "app/src/main/assets/agent/tools.json",
  "app/src/main/assets/agent/haminn-dev-plugin/SKILL.md",
  "app/src/main/assets/agent/haminn-agent.py",
  "app/src/main/assets/agent/webapp-authoring.md",
  "app/src/main/assets/agent/haminn-api.d.ts",
  "sdk/haminn-api.d.ts"
]) {
  if (!fs.statSync(path).isFile() || fs.statSync(path).size === 0) throw new Error(`Missing Web artifact: ${path}`);
}
console.log("Haminn Web assets are ready for Android packaging.");
