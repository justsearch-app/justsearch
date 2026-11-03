#!/usr/bin/env node
// Run against a before checkout with: node --test scripts/dev/test-dev-runner-prepare-ui-checks.mjs
// PREPARE_UI_CHECKS_SCRIPT can select that checkout's preparation script.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { createRequire } from 'node:module';

const { pythonPath } = createRequire(import.meta.url)('../lib/python-path.cjs');

// The recipe and its stubs (shebang npm/python3, bash) are Linux-only; the shared
// dev-runner lane also runs on Windows.
if (process.platform !== 'linux') {
  console.log('SKIP prepare-ui-checks: Linux-only recipe');
  process.exit(0);
}

const script = process.env.PREPARE_UI_CHECKS_SCRIPT || fileURLToPath(new URL('./prepare-ui-checks.cjs', import.meta.url));
const realPython = spawnSync('python3', ['-c', 'import sys; print(sys.executable)'], { encoding: 'utf8' });
assert.equal(realPython.status, 0, realPython.stderr);

// Installers are stubbed; both browser revisions share a cache whose contents
// model Playwright GC, so preparing one revision must preserve the other.
function fixture(t, version = '3.14') {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'prepare-ui-checks-'));
  t.after(() => fs.rmSync(base, { recursive: true, force: true }));
  // `checkout` is a temporary fixture, not the repository. Fixture-only folders are joined
  // from segments so the change classifier's reader scan (scripts/ci/classify-changes.test.mjs)
  // does not count them as repository reads; the one real read, jsharness's pyproject.toml,
  // selects this lane there.
  const checkout = path.join(base, 'checkout');
  const outside = path.join(base, 'outside');
  const tools = path.join(base, 'tools');
  const home = path.join(base, 'home');
  const trace = path.join(base, 'calls.jsonl');
  const write = (file, content = '', mode) => {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, content, { mode });
  };
  for (const dir of [checkout, outside, tools, home, path.join(checkout, 'modules/ui-web'), path.join(checkout, 'scripts', 'wire-contract')]) {
    fs.mkdirSync(dir, { recursive: true });
  }
  write(path.join(checkout, 'scripts/dev/prepare-ui-checks.cjs'), fs.readFileSync(script));
  write(path.join(checkout, 'scripts/lib/python-path.cjs'), fs.readFileSync(new URL('../lib/python-path.cjs', import.meta.url)));
  write(path.join(checkout, 'scripts/jsharness/pyproject.toml'), fs.readFileSync(new URL('../jsharness/pyproject.toml', import.meta.url)));
  write(path.join(checkout, 'scripts/jsharness/requirements.lock.txt'), fs.readFileSync(new URL('../jsharness/requirements.lock.txt', import.meta.url)));
  const pkg = path.join(tools, 'packages/playwright');
  const stub = `#!${process.execPath}
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const args = process.argv.slice(2);
const tool = path.basename(process.argv[1]);
const selectedEnv = Object.fromEntries(Object.entries(process.env).filter(([key]) => /^(npm_config_|NPM_CONFIG_|PIP_|PYTHON|JUSTSEARCH_|PLAYWRIGHT_|TMPDIR$)/.test(key)));
fs.appendFileSync(${JSON.stringify(trace)}, JSON.stringify({ tool, args, env: selectedEnv }) + '\\n');
if (tool === 'python3') {
  const bin = path.join(args[2], 'bin');
  fs.mkdirSync(bin, { recursive: true });
  fs.copyFileSync(process.argv[1], path.join(bin, 'python'));
  fs.chmodSync(path.join(bin, 'python'), 0o755);
} else if (tool === 'cli.js' || args[1] === 'playwright') {
  const cache = process.env.PLAYWRIGHT_BROWSERS_PATH;
  const revision = tool === 'cli.js' ? 'node-1.61.1' : 'python-1.64.1';
  if (process.env.PLAYWRIGHT_SKIP_BROWSER_GC !== '1') fs.rmSync(cache, { recursive: true, force: true });
  fs.mkdirSync(cache, { recursive: true });
  fs.writeFileSync(path.join(cache, revision), 'usable');
} else if (args[0] === '-c') {
  if (args[1].startsWith('import sys; print(')) console.log(${JSON.stringify(version)});
  else {
    const result = spawnSync(${JSON.stringify(realPython.stdout.trim())}, ['-c', 'import sys; sys.path.insert(0, ' + ${JSON.stringify(JSON.stringify(path.dirname(pkg)))} + ');\\n' + args[1], ...args.slice(2)], { stdio: 'inherit' });
    process.exit(result.status ?? 2);
  }
} else if (args[1] === 'pip' && process.env.PIP_TARGET) {
  fs.writeFileSync(path.join(process.env.PIP_TARGET, 'escaped-install'), 'bad');
}
`;
  write(path.join(tools, 'npm'), stub, 0o755);
  write(path.join(tools, 'python3'), stub, 0o755);
  write(path.join(checkout, 'modules/ui-web/node_modules/playwright-core/cli.js'), stub);
  const cache = path.join(checkout, 'tmp/ui-checks/ms-playwright');
  return {
    root: checkout, outside, cache,
    calls: () => fs.existsSync(trace) ? fs.readFileSync(trace, 'utf8').trim().split('\n').map(JSON.parse) : [],
    run: (extra = {}) => spawnSync(process.execPath, [path.join(checkout, 'scripts/dev/prepare-ui-checks.cjs')], {
      cwd: checkout, encoding: 'utf8', env: { ...process.env, HOME: home, PATH: `${tools}:${process.env.PATH}`, ...extra },
    }),
  };
}

for (const destination of [
  'tmp', 'tmp/ui-checks', 'tmp/ui-checks/venv', 'tmp/ui-checks/npm-cache',
  'tmp/ui-checks/pip-cache', 'tmp/ui-checks/env.sh', 'tmp/ui-checks/ms-playwright',
  'tmp/ui-checks/logs', 'tmp/ui-checks/scratch', 'tmp/ui-checks/captures',
  'tmp/ui-checks/a11y', 'tmp/ui-checks/venv/lib', 'tmp/ui-checks/npm-cache/_cacache',
  'tmp/ui-checks/logs/setup.log', 'node_modules', 'modules/ui-web/node_modules',
  'tmp/ui-checks/npm-logs', 'tmp/ui-checks/dev-runner', 'tmp/ui-checks/home',
  'tmp/ui-checks/data', 'tmp/ui-checks/job-records',
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
  assert.deepEqual(Object.fromEntries(Object.entries(pip.env).filter(([key]) => key.startsWith('PIP_'))), {
    PIP_CONFIG_FILE: os.devNull, PIP_CACHE_DIR: path.join(f.root, 'tmp/ui-checks/pip-cache'),
  });
  assert.equal(pip.env.PYTHONUSERBASE, undefined);
  assert.equal(pip.env.PYTHONPYCACHEPREFIX, undefined);
  assert.deepEqual(fs.readdirSync(f.outside), []);
});

test('clears inherited npm and Python settings during preparation and after sourcing env.sh', t => {
  const f = fixture(t);
  const inherited = {
    npm_config_logs_dir: f.outside, NPM_CONFIG_PREFIX: f.outside,
    npm_config_userconfig: path.join(f.outside, 'npmrc'),
    PYTHONPYCACHEPREFIX: f.outside, PYTHONHOME: f.outside, PYTHONPATH: f.outside,
    PIP_TARGET: f.outside, PIP_CONFIG_FILE: path.join(f.outside, 'pip.conf'),
  };
  const result = f.run(inherited);
  assert.equal(result.status, 0, result.stderr);
  const sourced = spawnSync('bash', ['--noprofile', '--norc', '-c',
    'source "$1" && exec "$2" -e "console.log(JSON.stringify(process.env))"',
    'source-env', path.join(f.root, 'tmp/ui-checks/env.sh'), process.execPath,
  ], { encoding: 'utf8', env: { ...process.env, ...inherited } });
  assert.equal(sourced.status, 0, sourced.stderr);
  for (const env of [...f.calls().map(call => call.env), JSON.parse(sourced.stdout)]) {
    assert.equal(env.npm_config_logs_dir, path.join(f.root, 'tmp/ui-checks/npm-logs'));
    assert.equal(env.npm_config_cache, path.join(f.root, 'tmp/ui-checks/npm-cache'));
    for (const key of ['NPM_CONFIG_PREFIX', 'npm_config_userconfig', 'PYTHONPYCACHEPREFIX', 'PYTHONHOME', 'PIP_TARGET']) {
      assert.equal(env[key], undefined, key);
    }
    assert.equal(env.PIP_CONFIG_FILE, os.devNull);
    assert.equal(env.PYTHONNOUSERSITE, '1');
    assert.equal(env.PYTHONDONTWRITEBYTECODE, '1');
    assert.equal(env.PYTHONPATH, pythonPath(f.root));
  }
  assert.deepEqual(fs.readdirSync(f.outside), []);
});

test('env.sh also clears settings the shell gained after preparation', t => {
  const f = fixture(t);
  const result = f.run({});
  assert.equal(result.status, 0, result.stderr);
  const later = {
    PIP_PREFIX: f.outside, PYTHONUSERBASE: f.outside, PYTHONHOME: f.outside,
    npm_config_prefix: f.outside, NPM_CONFIG_USERCONFIG: path.join(f.outside, 'npmrc'),
  };
  const sourced = spawnSync('bash', ['--noprofile', '--norc', '-c',
    'source "$1" && [[ ! -v __ui_checks_var ]] && exec "$2" -e "console.log(JSON.stringify(process.env))"',
    'source-env', path.join(f.root, 'tmp/ui-checks/env.sh'), process.execPath,
  ], { encoding: 'utf8', env: { ...process.env, ...later } });
  assert.equal(sourced.status, 0, sourced.stderr);
  const env = JSON.parse(sourced.stdout);
  for (const key of Object.keys(later)) assert.equal(env[key], undefined, key);
  assert.equal(env.PYTHONPATH, pythonPath(f.root));
});

test('confines state roots in preparation and the sourced environment', t => {
  const f = fixture(t);
  const directories = {
    JUSTSEARCH_DEV_RUNNER_STATE_ROOT: 'dev-runner', JUSTSEARCH_HOME: 'home',
    JUSTSEARCH_DATA_DIR: 'data', JUSTSEARCH_JOB_RECORD_DIR: 'job-records',
  };
  const inherited = Object.fromEntries(Object.keys(directories).map(key => [key, f.outside]));
  const result = f.run(inherited);
  assert.equal(result.status, 0, result.stderr);
  const sourced = spawnSync('bash', ['--noprofile', '--norc', '-c',
    'source "$1" && exec "$2" -e "console.log(JSON.stringify(process.env))"',
    'source-env', path.join(f.root, 'tmp/ui-checks/env.sh'), process.execPath,
  ], { encoding: 'utf8', env: { ...process.env, ...inherited } });
  assert.equal(sourced.status, 0, sourced.stderr);
  for (const env of [...f.calls().map(call => call.env), JSON.parse(sourced.stdout)]) {
    for (const [key, directory] of Object.entries(directories)) {
      assert.equal(env[key], path.join(f.root, 'tmp/ui-checks', directory));
      assert.ok(fs.statSync(env[key]).isDirectory());
    }
  }
});

test('installs declared base and UI dependencies without building jsharness metadata', t => {
  const f = fixture(t);
  const result = f.run();
  assert.equal(result.status, 0, result.stderr);
  const pip = f.calls().find(call => call.args[1] === 'pip');
  assert.ok(pip.args.includes('httpx>=0.25.0'));
  assert.ok(pip.args.includes('playwright==1.64.1'));
  assert.ok(!pip.args.includes('playwright>=1.40'));
  assert.ok(!pip.args.includes('-e'));
  assert.ok(!pip.args.some(arg => arg.startsWith(f.root)));
  assert.deepEqual(fs.readdirSync(path.join(f.root, 'scripts', 'jsharness')), ['pyproject.toml', 'requirements.lock.txt']);
});

for (const version of ['3.13', '3.14']) {
  test(`Python ${version} ${version === '3.13' ? 'uses' : 'omits'} CI constraints`, t => {
    const f = fixture(t, version);
    const result = f.run();
    assert.equal(result.status, 0, result.stderr);
    const pip = f.calls().find(call => call.args[1] === 'pip');
    assert.equal(pip.args.includes('-c'), version === '3.13');
    if (version === '3.13') assert.equal(pip.args.at(-1), path.join(f.root, 'scripts', 'jsharness', 'requirements.lock.txt'));
  });
}

for (const cached of [[], ['node-1.61.1'], ['python-1.64.1'], ['node-1.61.1', 'python-1.64.1']]) {
  test(`prepares both local browser revisions with GC protection; cached: ${cached.join(', ') || 'none'}`, t => {
    const f = fixture(t);
    fs.mkdirSync(f.cache, { recursive: true });
    for (const revision of cached) fs.writeFileSync(path.join(f.cache, revision), 'cached');
    const result = f.run({ PLAYWRIGHT_SKIP_BROWSER_GC: '0', PLAYWRIGHT_BROWSERS_PATH: f.outside });
    assert.equal(result.status, 0, result.stderr);
    const installs = f.calls().filter(call => call.tool === 'cli.js' || call.args[1] === 'playwright');
    assert.equal(installs.length, 2);
    assert.deepEqual(installs.map(call => call.args), [['install', 'chromium'], ['-m', 'playwright', 'install', 'chromium']]);
    for (const install of installs) {
      assert.equal(install.env.PLAYWRIGHT_BROWSERS_PATH, f.cache);
      assert.equal(install.env.PLAYWRIGHT_SKIP_BROWSER_GC, '1');
    }
    assert.deepEqual(fs.readdirSync(f.cache).sort(), ['node-1.61.1', 'python-1.64.1']);
    const sourced = spawnSync('bash', ['--noprofile', '--norc', '-c',
      'source "$1" && exec "$2" -e "console.log(JSON.stringify(process.env))"',
      'source-env', path.join(f.root, 'tmp/ui-checks/env.sh'), process.execPath,
    ], { encoding: 'utf8', env: { ...process.env, PLAYWRIGHT_SKIP_BROWSER_GC: '0' } });
    assert.equal(sourced.status, 0, sourced.stderr);
    const env = JSON.parse(sourced.stdout);
    assert.equal(env.PLAYWRIGHT_BROWSERS_PATH, f.cache);
    assert.equal(env.PLAYWRIGHT_SKIP_BROWSER_GC, '1');
  });
}

test('prepared governance scratch repos stay outside the checkout and its installed TypeScript', t => {
  const f = fixture(t);
  const result = f.run();
  assert.equal(result.status, 0, result.stderr);
  const sourced = spawnSync('bash', ['--noprofile', '--norc', '-c',
    `source "$1" && exec "$2" -e 'const fs = require("node:fs"), os = require("node:os"), path = require("node:path"); const root = fs.mkdtempSync(path.join(os.tmpdir(), "governance-repo-")); console.log(root); fs.rmSync(root, {recursive:true, force:true});'`,
    'source-env', path.join(f.root, 'tmp/ui-checks/env.sh'), process.execPath,
  ], { encoding: 'utf8', env: process.env });
  assert.equal(sourced.status, 0, sourced.stderr);
  const relative = path.relative(f.root, sourced.stdout.trim());
  assert.ok(relative.startsWith('..') || path.isAbsolute(relative), sourced.stdout);
  assert.equal(path.dirname(sourced.stdout.trim()), os.tmpdir());
});
