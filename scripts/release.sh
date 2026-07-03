#!/usr/bin/env bash
set -euo pipefail

bump="${1:-patch}"

if [ -n "$(git status --porcelain)" ]; then
  git add -A
  git commit -m "chore: update before release"
fi

version="$(node - "$bump" <<'JS'
const bump = process.argv[2];
const fs = require("fs");
const pkg = JSON.parse(fs.readFileSync("package.json", "utf8"));
let [major, minor, patch] = (pkg.version || "0.1.0").split(".").map(Number);
if (bump === "major") { major++; minor = 0; patch = 0; }
else if (bump === "minor") { minor++; patch = 0; }
else if (bump === "patch") { patch++; }
else if (/^\d+\.\d+\.\d+$/.test(bump)) { [major, minor, patch] = bump.split(".").map(Number); }
else throw new Error("use: release.sh [patch|minor|major|x.y.z]");
pkg.version = `${major}.${minor}.${patch}`;
fs.writeFileSync("package.json", JSON.stringify(pkg, null, 2) + "\n");
const tauri = JSON.parse(fs.readFileSync("src-tauri/tauri.conf.json", "utf8"));
tauri.version = pkg.version;
fs.writeFileSync("src-tauri/tauri.conf.json", JSON.stringify(tauri, null, 2) + "\n");
let cargo = fs.readFileSync("src-tauri/Cargo.toml", "utf8");
cargo = cargo.replace(/^version = ".*"$/m, `version = "${pkg.version}"`);
fs.writeFileSync("src-tauri/Cargo.toml", cargo);
console.log(pkg.version);
JS
)"

cargo check --manifest-path src-tauri/Cargo.toml

if git rev-parse "v${version}" >/dev/null 2>&1; then
  echo "tag v${version} already exists" >&2
  exit 1
fi

git add package.json src-tauri/Cargo.toml src-tauri/tauri.conf.json
git commit -m "chore: release v${version}" || true
git tag "v${version}"
git push
git push origin "v${version}"

echo "released v${version}"
