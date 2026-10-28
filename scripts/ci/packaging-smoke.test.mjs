import assert from 'node:assert/strict';
import test from 'node:test';
const { packagingChanged } = await import('./packaging-smoke.mjs').catch(() => ({}));
test('smoke selects package, Engine, frontend, trust and build inputs; skips unrelated prose', () => {
  assert.equal(typeof packagingChanged, 'function', 'packaging path selection is required for the daily smoke');
  for (const name of ['modules/shell/src-tauri/src/updater.rs', 'modules/ui/src/main/java/Engine.java', 'modules/ui-web/src/app.ts',
    'SSOT/docs/help/search-syntax.md', 'packaging/runtime/tesseract-windows.v1.json', 'gradle.properties', 'build.gradle.kts',
    'settings.gradle.kts', 'gradle/libs.versions.toml', 'build-logic/src/build.kt', 'scripts/release/release-candidate.mjs',
    'scripts/ci/package-installer-linux.sh', 'governance/store-recoverability.v1.json',
    'contracts/engine.v1.json', 'packages/runtime-client/src/index.ts', 'new-build-input.dat', '.github/workflows/build-installer.yml', 'package-lock.json']) {
    assert.equal(packagingChanged([name]), true, name);
  }
  assert.equal(packagingChanged(['README.md', 'docs/how-to/cut-a-release.md', 'CHANGELOG.md']), false);
});

// Exercise the real git/gh adapter: a successful smoke may have disappeared after a force push.
import { mkdtempSync, mkdirSync, writeFileSync, chmodSync, readFileSync, rmSync } from 'node:fs';
import { execFileSync, spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
test('daily smoke runs when the previous successful commit is missing or no longer an ancestor', t => {
  const dir = mkdtempSync(path.join(tmpdir(), 'smoke-history-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const git = (...args) => execFileSync('git', args, { cwd: dir, encoding: 'utf8' }).trim();
  git('init', '--quiet');
  git('config', 'user.email', 'test@example.invalid'); git('config', 'user.name', 'Test');
  writeFileSync(path.join(dir, 'README.md'), 'docs');
  git('add', '.'); git('commit', '--quiet', '-m', 'initial');
  const initial = git('rev-parse', 'HEAD');
  git('checkout', '--quiet', '--orphan', 'rewritten');
  git('add', '.'); git('commit', '--quiet', '-m', 'rewritten');
  mkdirSync(path.join(dir, 'bin'));
  const gh = path.join(dir, 'bin/gh');
  writeFileSync(gh, `#!/usr/bin/env node
console.log(JSON.stringify([{workflow_runs:[{id:1,head_sha:process.env.PREVIOUS_SHA}]}]));
`);
  chmodSync(gh, 0o755);
  for (const sha of ['a'.repeat(40), initial]) {
    const output = path.join(dir, 'output'); writeFileSync(output, '');
    const result = spawnSync(process.execPath, [fileURLToPath(new URL('./packaging-smoke.mjs', import.meta.url)), 'changed'], {
      cwd: dir, encoding: 'utf8', env: { ...process.env, PATH: `${path.join(dir, 'bin')}${path.delimiter}${process.env.PATH}`,
        PREVIOUS_SHA: sha, GITHUB_OUTPUT: output, GITHUB_RUN_ID: '2', GITHUB_REPOSITORY: 'test/repo' },
    });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(readFileSync(output, 'utf8'), 'changed=true\n');
  }
});
