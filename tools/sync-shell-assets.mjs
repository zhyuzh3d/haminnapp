import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const shell = process.env.HAMINNWEB_SHELL
  ? path.resolve(process.env.HAMINNWEB_SHELL)
  : path.resolve(root, "../haminnweb/public/shell");
const target = path.join(root, "app/src/main/assets/store");
// HaminnUI uses ordered classic scripts and nested theme files. Copy the full
// runtime tree, excluding downloadable ZIPs and private/unrecognized files.
function runtimeFiles(directory, prefix = "") {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const relative = path.join(prefix, entry.name);
    if (entry.isSymbolicLink()) throw new Error(`Shell snapshot cannot contain symlinks: ${relative}`);
    if (entry.name.startsWith(".")) return [];
    if (entry.isDirectory()) return runtimeFiles(path.join(directory, entry.name), relative);
    return /\.(html|css|js|svg|png|webp)$/.test(entry.name) || relative === "manifest.json" ? [relative] : [];
  }).sort();
}
const files = runtimeFiles(shell);
for (const required of ["index.html", "manifest.json", "store.css", "core/runtime.js", "platform/host.js"]) {
  if (!files.includes(required)) throw new Error(`Missing HaminnUI source: ${required}`);
}

for (const file of files) {
  const source = path.join(shell, file);
  if (!fs.statSync(source).isFile()) throw new Error(`Missing HaminnUI source: ${source}`);
  fs.mkdirSync(path.dirname(path.join(target, file)), { recursive: true });
  fs.copyFileSync(source, path.join(target, file));
}

// store/ is generated-only. Remove stale generated assets after the new tree
// has been copied so an old module cannot survive a later source migration.
for (const file of runtimeFiles(target)) {
  if (!files.includes(file)) fs.unlinkSync(path.join(target, file));
}
for (const file of files) {
  if (!fs.readFileSync(path.join(shell, file)).equals(fs.readFileSync(path.join(target, file)))) {
    throw new Error(`Snapshot differs from HaminnUI source: ${file}`);
  }
}

console.log(`Synchronized HaminnUI ${shell} -> ${target}`);
