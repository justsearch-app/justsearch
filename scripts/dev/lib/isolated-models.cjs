'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { splitJvmOptions } = require('./engine-java-launch.cjs');
const devDataFolder = require('./dev-data-folder.cjs');

const FOLDER = 'isolated-models';
const STATES = ['none', 'partial', 'truncated'];
const CACHE_FOLDER = 'isolated-ort-cache';
const ENGINE_CWD = path.resolve(__dirname, '../../..');
const MARKER = '.justsearch-isolated-models.json';
const MARKER_CONTENT = `${JSON.stringify({ schema: 'justsearch-isolated-models.v1', tool: 'dev-runner' })}\n`;
// Only output from an idle isolated Engine is reusable. Saved configuration, install
// contracts and the normal models fallback are deliberately absent, even in a marked folder.
const RUN_OUTPUT = new Set([
  MARKER, '.justsearch-dev-data.json', FOLDER, CACHE_FOLDER, 'app.lock', 'data-version.json',
  'encryption', 'feedback', 'file-operations', 'logs', 'operations.db', 'operations.db-shm',
  'operations.db-wal', 'runtime', 'telemetry', 'temp', 'crashes', 'index', '.help-ingested-version',
  'jobs.db', 'jobs.db-shm', 'jobs.db-wal', 'entity-clusters.db', 'entity-clusters.db-shm', 'entity-clusters.db-wal',
]);
// These paths are overwritten by the runner before the Engine starts. The chat profile
// selects a registry variant, not an inherited path.
const RUNNER_ENV = new Set([
  'JUSTSEARCH_DATA_DIR', 'JUSTSEARCH_HOME', 'JUSTSEARCH_MODELS_DIR',
  'JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR', 'JUSTSEARCH_DEV_RUNNER_STATE_ROOT', 'JUSTSEARCH_CHAT_PROFILE',
]);
const ALLOWED_ENV = new Set([
  'JUSTSEARCH_SERVER_EXE', // Selects the inference executable, not configuration or model storage.
  'JUSTSEARCH_GPU_ENABLED', // Selects GPU execution, not configuration or storage paths.
  'JUSTSEARCH_GH_BIN', // Selects the GitHub CLI for developer tooling; the Engine does not read it.
  'JUSTSEARCH_DEV_MCP_REPO_ROOT', // Locates the MCP server's runner; the Engine does not read it.
  'JUSTSEARCH_AGENT_IDENTITY_HANDOFF', // Carries caller ownership identity, with no configuration or storage paths.
  'JUSTSEARCH_PR_CI', // CI's test-selection flag; only build and test tooling read it, never the Engine.
]);

function refuse(message) {
  const error = new Error(`Isolated models: ${message}`);
  error.code = 'ISOLATED_MODELS_CONFLICT';
  throw error;
}

function within(root, candidate) {
  const relative = path.relative(root, candidate);
  return relative === '' || (!path.isAbsolute(relative) && relative !== '..' && !relative.startsWith(`..${path.sep}`));
}

// Resolve existing ancestors too: a lexical child through a link must never write shared models.
function physicalPath(candidate) {
  try { return fs.realpathSync(candidate); }
  catch (error) {
    if (error.code !== 'ENOENT') throw error;
    const link = fs.lstatSync(candidate, { throwIfNoEntry: false });
    if (link?.isSymbolicLink()) return physicalPath(path.resolve(path.dirname(candidate), fs.readlinkSync(candidate)));
    const parent = path.dirname(candidate);
    if (parent === candidate) throw error;
    return path.join(physicalPath(parent), path.basename(candidate));
  }
}

function protectedTrees() {
  const home = require('node:os').homedir();
  const platformDefault = process.platform === 'win32'
    ? path.join(process.env.LOCALAPPDATA || path.join(home, 'AppData', 'Local'), 'JustSearch')
    : process.platform === 'darwin' ? path.join(home, 'Library', 'Application Support', 'JustSearch') : path.join(home, '.justsearch');
  const mainRoot = require('./dev-runtime.cjs').resolveMainRepoRoot(ENGINE_CWD);
  return [...new Set([path.join(ENGINE_CWD, 'models'), path.join(mainRoot, 'models'),
    path.join(platformDefault, 'cache', 'ort-optimized')])];
}

function pathsFor(dataDir) {
  const home = path.resolve(ENGINE_CWD, dataDir);
  return {
    models: path.join(home, FOLDER),
    optimizedCache: path.join(home, CACHE_FOLDER),
    splade: null,
    appHome: home,
    settings: path.join(home, 'ui', 'settings.json'),
    installContract: path.join(home, 'install-contract.v2.json'),
  };
}

// Inspect descendants even for the empty state and on every supervised restart. Do not
// follow links while walking: checking each target catches escapes without link cycles.
function validateTree(root) {
  const visit = dir => {
    let entries;
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); }
    catch (error) { if (error.code === 'ENOENT') return; throw error; }
    for (const entry of entries) {
      const child = path.join(dir, entry.name);
      if (!within(root, physicalPath(child))) refuse(`descendant escapes the private folder: ${child}`);
      if (entry.isDirectory()) visit(child);
    }
  };
  visit(root);
}

function validateOverrides(dataDir, env = process.env) {
  for (const [name, value] of Object.entries(env)) {
    if (value == null) continue;
    if (name.startsWith('JUSTSEARCH_') && !RUNNER_ENV.has(name) && !ALLOWED_ENV.has(name)) {
      refuse(`inherited ${name} is not allowed; unset it and use a fresh data folder`);
    }
  }
  // Refuse sources, rather than trying to interpret every setting they could introduce.
  // Include _JAVA_OPTIONS too: HotSpot reads it even though the launcher does not.
  for (const name of ['JAVA_OPTS', 'UI_OPTS', 'JDK_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS']) {
    for (const option of splitJvmOptions(env[name])) {
      // -XX:VMOptionsFile and -XX:Flags load further options (including -D) from a file.
      if (option.startsWith('-D') || option.startsWith('@') || /^-XX:(?:VMOptionsFile|Flags)=/.test(option)) {
        refuse(`inherited ${name} contains ${option.startsWith('@') ? 'an @argument file' : option.startsWith('-D') ? 'a -D property' : 'a JVM options file'}; remove it before starting`);
      }
    }
  }
  const paths = pathsFor(dataDir);
  const home = physicalPath(paths.appHome);
  // Nothing an isolated start writes may overlap the shared models or the machine-wide graph cache.
  const protectedRoots = protectedTrees().map(physicalPath);
  const owned = [home];
  if (env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT) owned.push(physicalPath(path.resolve(ENGINE_CWD, env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT)));
  for (const ownedRoot of owned) {
    for (const shared of protectedRoots) {
      if (within(shared, ownedRoot) || within(ownedRoot, shared)) refuse(`${ownedRoot} overlaps the shared ${shared}; use a data folder outside it`);
    }
  }
  for (const [folder, root] of [[paths.models, path.join(home, FOLDER)],
    [paths.optimizedCache, path.join(home, CACHE_FOLDER)]]) {
    if (physicalPath(folder) !== root) refuse(`${folder} redirects outside its private folder`);
    validateTree(root);
  }
  const stat = fs.lstatSync(paths.appHome, { throwIfNoEntry: false });
  if (!stat) return paths;
  if (!stat.isDirectory() || stat.isSymbolicLink()) refuse(`${paths.appHome} must be a real data folder`);
  const entries = fs.readdirSync(paths.appHome);
  if (!entries.length) return paths;
  let marker;
  try { marker = fs.readFileSync(path.join(paths.appHome, MARKER), 'utf8'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (marker !== MARKER_CONTENT) {
    refuse(`non-empty data folder ${paths.appHome} has no marker from a previous isolated start (${MARKER}); use a fresh data folder`);
  }
  const foreign = entries.filter(name => !RUN_OUTPUT.has(name));
  if (foreign.length) {
    refuse(`marked data folder contains foreign content (${foreign.join(', ')}); use a fresh data folder`);
  }
  // Runtime output must not introduce links to inherited data either.
  validateTree(home);
  return paths;
}

// Fixture projection of InstallPlanner.effectiveTargetDir / appendIdentity, not an installed
// state claim. The real install plan remains the authority for whether these bytes are usable.
function effectiveTargetDir(pkg, variant) {
  const target = pkg.targetDir ?? '';
  if (!variant?.filename?.toLowerCase().endsWith('.onnx')) return target;
  const identity = (key, value) => {
    const normalized = value == null ? '<missing>' : key.endsWith('sha256') ? value.toUpperCase() : value;
    return `${key}=${normalized.length}:${normalized}\n`;
  };
  let input = identity('package', pkg.id) + identity('target', target)
    + identity('model', variant.filename) + identity('model-sha256', variant.sha256);
  for (const file of [...(pkg.supportingFiles ?? [])].sort((a, b) =>
    (a.filename ?? '') < (b.filename ?? '') ? -1 : (a.filename ?? '') > (b.filename ?? '') ? 1 : 0)) {
    input += identity('supporting', file.filename) + identity('supporting-sha256', file.sha256);
  }
  const suffix = crypto.createHash('sha256').update(input).digest('hex');
  return `${target ? `${target}/` : ''}candidates/${suffix}`;
}

function ensureDirectory(dataDir, env = process.env) {
  const paths = validateOverrides(dataDir, env);
  const claim = devDataFolder.claimDevDataFolder(paths.appHome, { tool: 'dev-runner' });
  if (!claim.ok) refuse(claim.message);
  if (!fs.existsSync(path.join(paths.appHome, MARKER))) {
    fs.writeFileSync(path.join(paths.appHome, MARKER), MARKER_CONTENT, { flag: 'wx' });
  }
  fs.mkdirSync(paths.models, { recursive: true });
  fs.mkdirSync(paths.optimizedCache, { recursive: true });
  return paths.models;
}

function prepare({ dataDir, state, repoRoot, env = process.env }) {
  if (!STATES.includes(state)) throw new Error(`Invalid --isolated-models: ${state} (expected none|partial|truncated)`);
  const paths = validateOverrides(dataDir, env);
  const models = ensureDirectory(dataDir, env);
  if (state !== 'none') {
    const registry = JSON.parse(fs.readFileSync(path.join(repoRoot,
      'modules/configuration/src/main/resources/ai/model-registry.v2.json'), 'utf8'));
    for (const pkg of registry.packages) {
      // Runtime packs install under app home, not models. Never seed runnable native files.
      if (pkg.installRoot || pkg.devOnly) continue;
      for (const variant of pkg.variants?.length ? pkg.variants : [null]) {
        const dir = effectiveTargetDir(pkg, variant);
        for (const file of [variant, ...(pkg.supportingFiles ?? [])].filter(Boolean)) {
          if (!Number.isSafeInteger(file.sizeBytes) || file.sizeBytes <= 1) continue;
          const target = path.resolve(models, dir, file.filename);
          if (!within(models, target) || !within(physicalPath(models), physicalPath(target))) {
            refuse(`seed target escapes the private folder: ${target}`);
          }
          const staged = `${target}.partial`; // InstallPlanner.PARTIAL_SUFFIX
          if (!within(physicalPath(models), physicalPath(staged))) refuse(`seed staging path escapes the private folder: ${staged}`);
          if (fs.existsSync(target) || fs.existsSync(staged)) continue; // restart never clobbers progress
          fs.mkdirSync(path.dirname(target), { recursive: true });
          fs.writeFileSync(state === 'partial' ? staged : target,
            Buffer.alloc(Math.min(64, file.sizeBytes - 1)), { flag: 'wx' });
        }
      }
    }
  }
  return { paths, env: { JUSTSEARCH_MODELS_DIR: models, JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR: paths.optimizedCache } };
}

function printPaths(paths, write = text => process.stderr.write(text)) {
  write(`[dev-runner] Isolated AI paths: ${JSON.stringify(paths)}\n`);
}

module.exports = { FOLDER, CACHE_FOLDER, MARKER, STATES, pathsFor, validateOverrides, effectiveTargetDir, ensureDirectory, prepare, printPaths };
