#!/usr/bin/env node
// Run against a before checkout with: node --test scripts/dev/test-prepare-ui-checks.mjs
// PREPARE_UI_CHECKS_SCRIPT can select that checkout's preparation script.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const script = process.env.PREPARE_UI_CHECKS_SCRIPT || fileURLToPath(new URL('./prepare-ui-checks.cjs', import.meta.url));
const realPython = spawnSync('python3', ['-c', 'import sys; print(sys.executable)'], { encoding: 'utf8' });
assert.equal(realPython.status, 0, realPython.stderr);

// Only npm/venv/install are stubbed. The actual browser probe runs in Python and
// its Node subprocess against a tiny Playwright registry with fixture executables.
function fixture(t, version = '3.14') {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'prepare-ui-checks-'));
  t.after(() => fs.rmSync(base, { recursive: true, force: true }));
  const root = path.join(base, 'checkout');
  const outside = path.join(base, 'outside');
  const tools = path.join(base, 'tools');
  const home = path.join(base, 'home');
  const trace = path.join(base, 'calls.jsonl');
  const write = (file, content = '', mode) => {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, content, { mode });
  };
  for (const dir of [root, outside, tools, home, path.join(root, 'modules/ui-web'), path.join(root, 'scripts/wire-contract')]) {
    fs.mkdirSync(dir, { recursive: true });
  }
  write(path.join(root, 'scripts/dev/prepare-ui-checks.cjs'), fs.readFileSync(script));
  const pkg = path.join(tools, 'packages/playwright');
  write(path.join(pkg, '__init__.py'));
  fs.mkdirSync(path.join(pkg, 'driver'), { recursive: true });
  fs.symlinkSync(process.execPath, path.join(pkg, 'driver/node'));
  write(path.join(pkg, 'driver/package/lib/server/registry/index.js'), `
const path = require('node:path');
exports.registry = { findExecutable: name => ({ executablePath: () => path.join(
  process.env.PLAYWRIGHT_BROWSERS_PATH,
  name === 'chromium' ? 'chromium-123/chrome-linux/chrome' : 'chromium_headless_shell-456/chrome-linux/headless_shell'
) }) };
`);
  const bundle = path.join(pkg, 'driver/package/lib/coreBundle.js');
  write(bundle, "exports.registry = require('./server/registry/index.js');\n");
  // Also provide the older JSON/API interface so the before script's probe runs.
  write(path.join(pkg, 'driver/package/browsers.json'), JSON.stringify({ browsers: [{ name: 'chromium-headless-shell', revision: '456' }] }));
  write(path.join(pkg, 'sync_api.py'), `
import os
from pathlib import Path
from types import SimpleNamespace
class sync_playwright:
    def __enter__(self):
        return SimpleNamespace(chromium=SimpleNamespace(executable_path=str(Path(os.environ['PLAYWRIGHT_BROWSERS_PATH']) / 'chromium-123/chrome-linux/chrome')))
    def __exit__(self, *args): pass
`);
  const stub = `#!${process.execPath}
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const args = process.argv.slice(2);
const tool = path.basename(process.argv[1]);
const selectedEnv = Object.fromEntries(Object.entries(process.env).filter(([key]) => key.startsWith('PIP_') || key === 'PYTHONUSERBASE'));
fs.appendFileSync(${JSON.stringify(trace)}, JSON.stringify({ tool, args, env: selectedEnv }) + '\\n');
if (tool === 'python3') {
  const bin = path.join(args[2], 'bin');
  fs.mkdirSync(bin, { recursive: true });
  fs.copyFileSync(process.argv[1], path.join(bin, 'python'));
  fs.chmodSync(path.join(bin, 'python'), 0o755);
} else if (args[0] === '-c') {
  if (args[1].startsWith('import sys; print(')) console.log(${JSON.stringify(version)});
  else {
    const result = spawnSync(${JSON.stringify(realPython.stdout.trim())}, ['-c', 'import sys; sys.path.insert(0, ' + ${JSON.stringify(JSON.stringify(path.dirname(pkg)))} + ');\\n' + args[1]], { stdio: 'inherit' });
    process.exit(result.status ?? 2);
  }
} else if (args[1] === 'pip' && process.env.PIP_TARGET) {
  fs.writeFileSync(path.join(process.env.PIP_TARGET, 'escaped-install'), 'bad');
}
`;
  write(path.join(tools, 'npm'), stub, 0o755);
  write(path.join(tools, 'python3'), stub, 0o755);
  const cache = path.join(home, '.cache/ms-playwright');
  const chromium = path.join(cache, 'chromium-123/chrome-linux/chrome');
  const shell = path.join(cache, 'chromium_headless_shell-456/chrome-linux/headless_shell');
  write(chromium, '', 0o755);
  write(shell, '', 0o755);
  return {
    root, outside, chromium, shell, bundle,
    calls: () => fs.existsSync(trace) ? fs.readFileSync(trace, 'utf8').trim().split('\n').map(JSON.parse) : [],
    run: (extra = {}) => spawnSync(process.execPath, [path.join(root, 'scripts/dev/prepare-ui-checks.cjs')], {
      cwd: root, encoding: 'utf8', env: { ...process.env, HOME: home, PATH: `${tools}:${process.env.PATH}`, ...extra },
    }),
  };
}

for (const destination of [
  'tmp', 'tmp/ui-checks', 'tmp/ui-checks/venv', 'tmp/ui-checks/npm-cache',
  'tmp/ui-checks/pip-cache', 'tmp/ui-checks/env.sh', 'tmp/ui-checks/ms-playwright',
  'tmp/ui-checks/logs', 'tmp/ui-checks/scratch', 'tmp/ui-checks/captures',
  'tmp/ui-checks/a11y', 'tmp/ui-checks/venv/lib', 'tmp/ui-checks/npm-cache/_cacache',
  'tmp/ui-checks/logs/setup.log', 'node_modules', 'modules/ui-web/node_modules',
  'scripts/wire-contract/node_modules', 'modules', 'scripts/wire-contract',
]) {
  test(`refuses ${destination} symlink before any command or write`, t => {
    const f = fixture(t);
    const target = path.join(f.root, destination);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.rmSync(target, { recursive: true, force: true });
    fs.symlinkSync(f.outside, target);
    const result = f.run();
    assert.notEqual(result.status, 0, result.stderr);
    assert.match(result.stderr, /symlink/);
    assert.deepEqual(f.calls(), []);
    assert.deepEqual(fs.readdirSync(f.outside), []);
    if (!destination.startsWith('tmp')) assert.equal(fs.existsSync(path.join(f.root, 'tmp')), false);
  });
}

test('refuses even an in-checkout symlink', t => {
  const f = fixture(t);
  fs.mkdirSync(path.join(f.root, 'tmp/ui-checks'), { recursive: true });
  fs.mkdirSync(path.join(f.root, 'other-cache'));
  fs.symlinkSync(path.join(f.root, 'other-cache'), path.join(f.root, 'tmp/ui-checks/pip-cache'));
  const result = f.run();
  assert.notEqual(result.status, 0, result.stderr);
  assert.deepEqual(f.calls(), []);
});

test('pip ignores inherited destination configuration, including external PIP_TARGET', t => {
  const f = fixture(t);
  const result = f.run({
    PIP_TARGET: f.outside, PIP_PREFIX: f.outside, PIP_ROOT: f.outside,
    PIP_USER: '1', PIP_CONFIG_FILE: path.join(f.outside, 'pip.conf'),
    PIP_CACHE_DIR: f.outside, PYTHONUSERBASE: f.outside, PYTHONPYCACHEPREFIX: f.outside,
  });
  assert.equal(result.status, 0, result.stderr);
  const pip = f.calls().find(call => call.args[1] === 'pip');
  assert.deepEqual(pip.env, { PIP_CONFIG_FILE: os.devNull, PIP_CACHE_DIR: path.join(f.root, 'tmp/ui-checks/pip-cache') });
  assert.deepEqual(fs.readdirSync(f.outside), []);
});

test('shared browser probe supports the older unbundled registry layout', t => {
  const f = fixture(t);
  fs.unlinkSync(f.bundle);
  const result = f.run();
  assert.equal(result.status, 0, result.stderr);
  assert.equal(f.calls().filter(call => call.args[1] === 'playwright').length, 0);
});

test('unexpected registry errors stop preparation instead of installing browsers', t => {
  const f = fixture(t);
  fs.writeFileSync(f.bundle, "throw new Error('broken registry');\n");
  const result = f.run();
  assert.equal(result.status, 2, result.stderr);
  assert.match(result.stderr, /broken registry/);
  assert.equal(f.calls().filter(call => call.args[1] === 'playwright').length, 0);
});

for (const version of ['3.13', '3.14']) {
  test(`Python ${version} ${version === '3.13' ? 'uses' : 'omits'} CI constraints`, t => {
    const f = fixture(t, version);
    const result = f.run();
    assert.equal(result.status, 0, result.stderr);
    const pip = f.calls().find(call => call.args[1] === 'pip');
    assert.equal(pip.args.includes('-c'), version === '3.13');
    if (version === '3.13') assert.equal(pip.args.at(-1), path.join(f.root, 'scripts/jseval/requirements.lock.txt'));
  });
}

for (const missing of ['neither', 'chromium', 'shell', 'shell-executable-bit']) {
  test(`shared browser cache: ${missing} missing`, t => {
    const f = fixture(t);
    if (missing === 'chromium') fs.unlinkSync(f.chromium);
    if (missing === 'shell') fs.unlinkSync(f.shell); // Keep its directory: the reported regression.
    if (missing === 'shell-executable-bit') fs.chmodSync(f.shell, 0o644);
    const result = f.run();
    assert.equal(result.status, 0, result.stderr);
    const installs = f.calls().filter(call => call.args[1] === 'playwright');
    assert.equal(installs.length, missing === 'neither' ? 0 : 1);
    const env = fs.readFileSync(path.join(f.root, 'tmp/ui-checks/env.sh'), 'utf8');
    assert.ok(env.includes(missing === 'neither' ? '.cache/ms-playwright' : 'tmp/ui-checks/ms-playwright'));
  });
}
