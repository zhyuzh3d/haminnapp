import fs from "node:fs";

// These files are documentation, not a frontend build pipeline.
const check = process.argv.includes("--check");
for (const [source, destination] of [
  ["docs/webapp-authoring.md", "app/src/main/assets/agent/webapp-authoring.md"],
  ["sdk/haminn-api.d.ts", "app/src/main/assets/agent/haminn-api.d.ts"]
]) {
  const content = fs.readFileSync(source, "utf8");
  if (check) {
    if (!fs.existsSync(destination) || fs.readFileSync(destination, "utf8") !== content) throw new Error(`Stale agent guide: ${destination}`);
  } else fs.writeFileSync(destination, content);
}
console.log(check ? "Agent documentation matches its source." : "Agent documentation synchronized.");
