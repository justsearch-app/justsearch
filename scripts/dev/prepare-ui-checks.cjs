#!/usr/bin/env node
'use strict';

// Linux isolated-session setup. npm installs belong to this checkout's node_modules;
// the Python environment and session-local caches belong to this checkout's tmp/;
// the shared Playwright cache is inspected and used read-only when it matches.
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const root = fs.realpathSync(path.resolve(__dirname, '../..'));
const prep = path.join(root, 'tmp/ui-checks');
const installs = [
  root,
  path.join(root, 'modules/ui-web'),
  path.join(root, 'scripts/wire-contract'), // Governance's pinned buf CLI.
];
const quote = value => `'${value.replaceAll("'", "'\\''")}'`;

function run(command, args, env) {
  console.error(`[prepare-ui-checks] ${command} ${args.join(' ')}`);
  const result = spawnSync(command, args, { cwd: root, env, stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status || 1);
}

// Validate every destination before npm ci can remove an existing installation.
for (const source of installs) {
  const modules = path.join(source, 'node_modules');
  const stat = fs.lstatSync(modules, { throwIfNoEntry: false });
  if (stat?.isSymbolicLink()) {
    throw new Error(`${modules} is a symlink; remove it or use a fresh isolated checkout`);
  }
  const resolved = fs.realpathSync(stat ? modules : source);
  const relative = path.relative(root, resolved);
  if (relative === '..' || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) {
    throw new Error(`${modules} must be a real directory inside this checkout; remove the link or use a fresh isolated checkout`);
  }
}

fs.mkdirSync(path.join(prep, 'scratch'), { recursive: true });
const env = {
  ...process.env,
  TMPDIR: path.join(prep, 'scratch'),
  npm_config_cache: path.join(prep, 'npm-cache'),
  PIP_CACHE_DIR: path.join(prep, 'pip-cache'),
  // Avoid inheriting a user-site or another session's Python packages.
  PYTHONNOUSERSITE: '1',
  PYTHONPATH: '',
};
for (const source of installs) {
  run('npm', ['ci', '--prefix', source, '--audit=false'], env);
}

run('python3', ['-m', 'venv', path.join(prep, 'venv')], env);
const python = path.join(prep, 'venv/bin/python');
run(python, ['-m', 'pip', 'install', '-e', `${root}/scripts/jseval[ui]`], env);

// Chromium's executable_path comes from this Python Playwright, not playwright-core
// in npm. Headless screenshots also need its matching headless-shell revision.
const sharedBrowsers = path.join(require('node:os').homedir(), '.cache/ms-playwright');
const probe = spawnSync(python, ['-c', `
import json
from pathlib import Path
import playwright
from playwright.sync_api import sync_playwright
cache = Path(${JSON.stringify(sharedBrowsers)})
registry = json.loads((Path(playwright.__file__).parent / 'driver/package/browsers.json').read_text())
shell = next(b for b in registry['browsers'] if b['name'] == 'chromium-headless-shell')
with sync_playwright() as p:
    present = Path(p.chromium.executable_path).is_file()
raise SystemExit(0 if present and (cache / ('chromium_headless_shell-' + shell['revision'])).is_dir() else 1)
`], { env: { ...env, PLAYWRIGHT_BROWSERS_PATH: sharedBrowsers }, stdio: 'inherit' });
if (probe.error) throw probe.error;
if (probe.status !== 0 && probe.status !== 1) process.exit(probe.status || 1);
const browsers = probe.status === 0 ? sharedBrowsers : path.join(prep, 'ms-playwright');
if (probe.status === 0) {
  console.error(`[prepare-ui-checks] reusing matching Chromium read-only: ${browsers}`);
} else {
  run(python, ['-m', 'playwright', 'install', 'chromium'], { ...env, PLAYWRIGHT_BROWSERS_PATH: browsers });
}

const variables = {
  UI_CHECKS_DIR: prep,
  VIRTUAL_ENV: path.join(prep, 'venv'),
  TMPDIR: env.TMPDIR,
  npm_config_cache: env.npm_config_cache,
  PIP_CACHE_DIR: env.PIP_CACHE_DIR,
  PYTHONNOUSERSITE: env.PYTHONNOUSERSITE,
  PYTHONPATH: env.PYTHONPATH,
  PLAYWRIGHT_BROWSERS_PATH: browsers,
};
const environment = Object.entries(variables).map(([key, value]) => `export ${key}=${quote(value)}`);
environment.push(`export PATH=${quote(path.join(prep, 'venv/bin'))}:"$PATH"`);
const envFile = path.join(prep, 'env.sh');
fs.writeFileSync(envFile, `${environment.join('\n')}\n`);
console.log(`source ${quote(envFile)}`);
