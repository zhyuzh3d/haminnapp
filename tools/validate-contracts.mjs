import fs from "node:fs";

const capabilities = JSON.parse(fs.readFileSync("api/capabilities.json", "utf8"));
const native = fs.readFileSync("app/src/main/java/life/airen/haminn/MainActivity.kt", "utf8");
const sdk = fs.readFileSync("sdk/haminn-api.d.ts", "utf8");
const bridge = fs.readFileSync("app/src/main/assets/bridge/haminn-v1.js", "utf8");
for (const [namespace, methods] of Object.entries(capabilities.public)) {
  if (!sdk.includes(`${namespace}:`)) throw new Error(`SDK namespace missing: ${namespace}`);
  if (!bridge.includes(`namespace("${namespace}")`) && !["runtime", "app"].includes(namespace)) throw new Error(`Bridge namespace missing: ${namespace}`);
  for (const method of methods) {
    if (!native.includes(`"${namespace}.${method}"`)) throw new Error(`Native dispatch missing: ${namespace}.${method}`);
  }
}
for (const [namespace, methods] of Object.entries(capabilities.storeOnly)) {
  for (const method of methods) {
    if (!native.includes(`"${namespace}.${method}"`)) throw new Error(`Host dispatch missing: ${namespace}.${method}`);
  }
}
for (const file of ["api/haminn.schema.json", "api/rpc.schema.json", "api/backup.schema.json"]) {
  JSON.parse(fs.readFileSync(file, "utf8"));
}
console.log("Haminn Web/API contracts are internally consistent.");
