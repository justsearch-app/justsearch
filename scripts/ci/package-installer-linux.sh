#!/usr/bin/env bash
# Ubuntu 24.04+ amd64. Build through Tauri, then gate the actual payload.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || {
  echo 'Linux packaging requires Linux x86_64.' >&2
  exit 1
}
for tool in node npm java rustc cargo python3 dpkg-deb readelf ldd; do
  command -v "$tool" >/dev/null || { echo "Missing build prerequisite: $tool" >&2; exit 1; }
done

node scripts/ci/sync-version.mjs
npm ci --prefix modules/shell --audit=false
npm ci --prefix modules/ui-web --audit=false
export CARGO_TARGET_DIR="$PWD/modules/shell/src-tauri/target"
# Remove only stale Debian bundles, so a failed build can never select an old package.
bundle_dir=modules/shell/src-tauri/target/release/bundle/deb
rm -rf "$bundle_dir"
mkdir -p dist/installer-linux
rm -f dist/installer-linux/*.deb dist/installer-linux/elf-audit.json
npm --prefix modules/shell run tauri -- build --config src-tauri/tauri.linux.conf.json --ci
shopt -s nullglob
packages=("$bundle_dir"/*.deb)
[[ ${#packages[@]} == 1 ]] || { echo "Expected exactly one .deb, found ${#packages[@]}" >&2; exit 1; }
python3 scripts/ci/audit-linux-elf.py "${packages[0]}" dist/installer-linux/elf-audit.json
cp "${packages[0]}" dist/installer-linux/
package="dist/installer-linux/$(basename "${packages[0]}")"
dpkg-deb --info "$package"
stat --format='%n: %s bytes' "$package"
sha256sum "$package"
