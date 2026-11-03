#!/usr/bin/env node
'use strict';

// Linux isolated-session setup. npm installs belong to this checkout's node_modules;
// the Python environment and session-local caches belong to this checkout's tmp/;
// both pinned Playwright Chromium revisions stay in checkout-local storage.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { pythonPath } = require('../lib/python-path.cjs');

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

// Check ancestors too: lstat of a destination alone follows links in its parents.
// Do all checks before the first mutation, including existing cache/output children.
function validateDestination(destination) {
  const relative = path.relative(root, destination);
  if (relative === '..' || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) {
    throw new Error(`${destination} must stay inside this checkout`);
  }
  let current = root;
  for (const part of relative.split(path.sep)) {
    current = path.join(current, part);
    const stat = fs.lstatSync(current, { throwIfNoEntry: false });
    if (!stat) break;
    if (stat.isSymbolicLink() || fs.realpathSync(current) !== current) {
      throw new Error(`${current} is a symlink or resolves outside this checkout; remove it or use a fresh isolated checkout`);
    }
  }
}

function validateSessionTree(destination) {
  validateDestination(destination);
  if (fs.lstatSync(destination, { throwIfNoEntry: false })?.isDirectory()) {
    for (const child of fs.readdirSync(destination)) {
      validateSessionTree(path.join(destination, child));
    }
  }
}
for (const source of installs) validateDestination(path.join(source, 'node_modules'));
validateSessionTree(prep);

fs.mkdirSync(path.join(prep, 'scratch'), { recursive: true });
const env = {
  ...process.env,
  TMPDIR: path.join(prep, 'scratch'),
};
// Clear whole inherited configuration families, rather than maintaining a list
// of individual destinations. env.sh applies the same reset in the caller shell.
const inheritedSettings = Object.keys(env).filter(key =>
  /^(npm_config_|NPM_CONFIG_|PIP_|PYTHON)/.test(key) || key === 'VIRTUAL_ENV');
for (const key of inheritedSettings) delete env[key];
env.npm_config_cache = path.join(prep, 'npm-cache');
env.npm_config_logs_dir = path.join(prep, 'npm-logs');
env.PIP_CONFIG_FILE = os.devNull;
env.PIP_CACHE_DIR = path.join(prep, 'pip-cache');
// Avoid user-site packages, foreign module paths and redirected bytecode caches.
env.PYTHONNOUSERSITE = '1';
env.PYTHONDONTWRITEBYTECODE = '1';
env.PYTHONPATH = pythonPath(root);
for (const [key, directory] of Object.entries({
  JUSTSEARCH_DEV_RUNNER_STATE_ROOT: 'dev-runner',
  JUSTSEARCH_HOME: 'home',
  JUSTSEARCH_DATA_DIR: 'data',
  JUSTSEARCH_JOB_RECORD_DIR: 'job-records',
})) {
  env[key] = path.join(prep, directory);
  fs.mkdirSync(env[key], { recursive: true });
}
for (const source of installs) {
  run('npm', ['ci', '--prefix', source, '--audit=false'], env);
}

run('python3', ['-m', 'venv', path.join(prep, 'venv')], env);
const python = path.join(prep, 'venv/bin/python');
const version = spawnSync(python, ['-c', 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")'], {
  env, encoding: 'utf8',
});
if (version.error) throw version.error;
if (version.status !== 0) {
  process.stderr.write(version.stderr);
  process.exit(version.status || 1);
}
const constraints = version.stdout.trim() === '3.13' ? ['-c', path.join(root, 'scripts/jsharness/requirements.lock.txt')] : [];
// Read the declared requirements without asking setuptools to build metadata in
// the source tree. Import this checkout's packages via PYTHONPATH instead of installing them.
const requirements = spawnSync(python, ['-c', `
import json, sys, tomllib
from pathlib import Path
project = tomllib.loads(Path(sys.argv[1]).read_text())['project']
print(json.dumps(project['dependencies'] + project['optional-dependencies']['ui']))
`, path.join(root, 'scripts/jsharness/pyproject.toml')], { env, encoding: 'utf8' });
if (requirements.error) throw requirements.error;
if (requirements.status !== 0) {
  process.stderr.write(requirements.stderr);
  process.exit(requirements.status || 1);
}
// Pin Playwright from the UI lock even when the interpreter cannot use the full CI lock.
const playwright = fs.readFileSync(path.join(root, 'scripts/jsharness/requirements.lock.txt'), 'utf8')
  .match(/^playwright==[^\s]+$/m)?.[0];
if (!playwright) throw new Error('UI lock must pin playwright');
const dependencies = JSON.parse(requirements.stdout).filter(requirement => !requirement.startsWith('playwright'));
run(python, ['-m', 'pip', 'install', ...dependencies, playwright, ...constraints], env);

// Each installed Playwright owns a different browser revision. Install both into
// this checkout, and prevent either installer from collecting the other's revision.
const browsers = path.join(prep, 'ms-playwright');
const browserEnv = { ...env, PLAYWRIGHT_BROWSERS_PATH: browsers, PLAYWRIGHT_SKIP_BROWSER_GC: '1' };
run(process.execPath, [path.join(root, 'modules/ui-web/node_modules/playwright-core/cli.js'), 'install', 'chromium'], browserEnv);
run(python, ['-m', 'playwright', 'install', 'chromium'], browserEnv);

const variables = {
  UI_CHECKS_DIR: prep,
  VIRTUAL_ENV: path.join(prep, 'venv'),
  // Scratch git repos must not inherit this checkout's installed TypeScript.
  TMPDIR: os.tmpdir(),
  npm_config_cache: env.npm_config_cache,
  npm_config_logs_dir: env.npm_config_logs_dir,
  PIP_CONFIG_FILE: env.PIP_CONFIG_FILE,
  PIP_CACHE_DIR: env.PIP_CACHE_DIR,
  PYTHONNOUSERSITE: env.PYTHONNOUSERSITE,
  PYTHONDONTWRITEBYTECODE: env.PYTHONDONTWRITEBYTECODE,
  PYTHONPATH: env.PYTHONPATH,
  JUSTSEARCH_DEV_RUNNER_STATE_ROOT: env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT,
  JUSTSEARCH_HOME: env.JUSTSEARCH_HOME,
  JUSTSEARCH_DATA_DIR: env.JUSTSEARCH_DATA_DIR,
  JUSTSEARCH_JOB_RECORD_DIR: env.JUSTSEARCH_JOB_RECORD_DIR,
  PLAYWRIGHT_BROWSERS_PATH: browsers,
  PLAYWRIGHT_SKIP_BROWSER_GC: '1',
};
// Clear the families at source time, so settings the caller's shell gained after
// preparation are reset too, not only the names preparation saw.
const environment = [
  'for __ui_checks_var in $(compgen -e); do',
  '  case "$__ui_checks_var" in npm_config_*|NPM_CONFIG_*|PIP_*|PYTHON*|VIRTUAL_ENV) unset "$__ui_checks_var" ;; esac',
  'done',
  'unset __ui_checks_var',
];
environment.push(...Object.entries(variables).map(([key, value]) => `export ${key}=${quote(value)}`));
environment.push(`export PATH=${quote(path.join(prep, 'venv/bin'))}:"$PATH"`);
const envFile = path.join(prep, 'env.sh');
fs.writeFileSync(envFile, `${environment.join('\n')}\n`);
console.log(`source ${quote(envFile)}`);
