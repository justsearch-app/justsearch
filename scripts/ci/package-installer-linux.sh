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
rm -f dist/installer-linux/*.deb dist/installer-linux/*.deb.sig dist/installer-linux/elf-audit.json
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
# Debian updater artifacts use the same Tauri/Minisign key as Windows. A .deb is
# not one of Tauri's automatic updater bundles, so sign the exact copied bytes.
if [[ ${JUSTSEARCH_SIGN_UPDATER:-} == 1 ]]; then
  : "${TAURI_SIGNING_PRIVATE_KEY:?TAURI_SIGNING_PRIVATE_KEY is required}"
  npm --prefix modules/shell run tauri -- signer sign "$PWD/$package"
  [[ -s "$package.sig" ]] || { echo 'Missing Debian updater signature' >&2; exit 1; }
fi
