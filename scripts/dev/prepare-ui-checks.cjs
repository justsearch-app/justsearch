#!/usr/bin/env node
'use strict';

// Linux isolated-session setup. npm installs belong to this checkout's node_modules;
// the Python environment and session-local caches belong to this checkout's tmp/;
// the shared Playwright cache is inspected and used read-only when it matches.
const fs = require('node:fs');
const os = require('node:os');
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
env.PYTHONPATH = path.join(root, 'scripts/jseval');
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
const constraints = version.stdout.trim() === '3.13' ? ['-c', path.join(root, 'scripts/jseval/requirements.lock.txt')] : [];
// Read the declared requirements without asking setuptools to build metadata in
// the source tree. Import jseval directly via PYTHONPATH instead of installing it.
const requirements = spawnSync(python, ['-c', `
import json, sys, tomllib
from pathlib import Path
project = tomllib.loads(Path(sys.argv[1]).read_text())['project']
print(json.dumps(project['dependencies'] + project['optional-dependencies']['ui']))
`, path.join(root, 'scripts/jseval/pyproject.toml')], { env, encoding: 'utf8' });
if (requirements.error) throw requirements.error;
if (requirements.status !== 0) {
  process.stderr.write(requirements.stderr);
  process.exit(requirements.status || 1);
}
run(python, ['-m', 'pip', 'install', ...JSON.parse(requirements.stdout), ...constraints], env);

// Chromium's executable_path comes from this Python Playwright, not playwright-core
// in npm. Headless screenshots also need its matching headless-shell revision.
const sharedBrowsers = path.join(os.homedir(), '.cache/ms-playwright');
const probe = spawnSync(python, ['-c', `
import subprocess
from pathlib import Path
import playwright
driver = Path(playwright.__file__).parent / 'driver'
# Use Playwright's registry so platform paths and revision overrides match launch.
raise SystemExit(subprocess.call([str(driver / 'node'), '-e', """
const fs = require('node:fs');
const path = require('node:path');
process.on('uncaughtException', error => { console.error(error); process.exit(2); });
const bundle = path.join(process.argv[1], 'lib/coreBundle.js');
const registry = fs.existsSync(bundle) ? require(bundle).registry.registry
  : require(path.join(process.argv[1], 'lib/server/registry/index.js')).registry;
const present = ['chromium', 'chromium-headless-shell'].every(name => {
  const executable = registry.findExecutable(name).executablePath();
  try {
    fs.accessSync(executable, fs.constants.X_OK);
    return fs.statSync(executable).isFile();
  } catch { return false; }
});
process.exit(present ? 0 : 1);
""", str(driver / 'package')]))
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
