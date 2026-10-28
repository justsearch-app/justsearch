import assert from 'node:assert/strict';
import test from 'node:test';
const { packagingChanged } = await import('./packaging-smoke.mjs').catch(() => ({}));
test('smoke selects package, Engine, frontend, trust and build inputs; skips unrelated prose', () => {
  assert.equal(typeof packagingChanged, 'function', 'packaging path selection is required for the daily smoke');
  for (const name of ['modules/shell/src-tauri/src/updater.rs', 'modules/ui/src/main/java/Engine.java', 'modules/ui-web/src/app.ts',
    'SSOT/docs/help/search-syntax.md', 'packaging/runtime/tesseract-windows.v1.json', 'gradle.properties', 'build.gradle.kts',
    'settings.gradle.kts', 'gradle/libs.versions.toml', 'build-logic/src/build.kt', 'scripts/release/release-candidate.mjs',
    'scripts/ci/package-installer-linux.sh', '.github/workflows/build-installer.yml', 'package-lock.json']) {
    assert.equal(packagingChanged([name]), true, name);
  }
  assert.equal(packagingChanged(['README.md', 'docs/how-to/cut-a-release.md', 'CHANGELOG.md']), false);
});
