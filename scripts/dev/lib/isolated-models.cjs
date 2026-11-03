'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { fileURLToPath } = require('node:url');
const { splitJvmOptions } = require('./engine-java-launch.cjs');

const FOLDER = 'isolated-models';
const STATES = ['none', 'partial', 'truncated'];
const CACHE_FOLDER = 'isolated-ort-cache';
const ENGINE_CWD = path.resolve(__dirname, '../../..');
const CACHE_PROPERTY = 'justsearch.ort.optimized_cache_dir';
const SETTINGS_PATHS = ['llmModelPath', 'embedOnnxModelPath', 'rerankerModelPath',
  'nerModelPath', 'spladeModelPath', 'citationScorerModelPath'];
const MODEL_OVERRIDES = {
  JUSTSEARCH_MODELS_DIR: 'justsearch.models.dir',
  JUSTSEARCH_SPLADE_MODEL_PATH: 'justsearch.splade.model_path',
  JUSTSEARCH_EMBED_ONNX_MODEL_PATH: 'justsearch.embed.onnx.model_path',
  JUSTSEARCH_LLM_MODEL_PATH: 'justsearch.llm.model_path',
  JUSTSEARCH_RERANK_MODEL_PATH: 'justsearch.rerank.model_path',
  JUSTSEARCH_RERANK_CHUNKS_MODEL_PATH: 'justsearch.rerank.chunks.model_path',
  JUSTSEARCH_NER_MODEL_PATH: 'justsearch.ner.model_path',
  JUSTSEARCH_CITATION_SCORER_MODEL_PATH: 'justsearch.citation.scorer.model_path',
  JUSTSEARCH_BGE_M3_MODEL_PATH: 'justsearch.bgem3.model_path',
  JUSTSEARCH_VLM_MODEL: 'justsearch.vlm.model',
  JUSTSEARCH_MMPROJ_MODEL: 'justsearch.mmproj.model',
};

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

function pathsFor(dataDir, env = process.env) {
  const home = path.resolve(ENGINE_CWD, dataDir);
  return {
    models: path.join(home, FOLDER),
    optimizedCache: path.join(home, CACHE_FOLDER),
    splade: env.JUSTSEARCH_SPLADE_MODEL_PATH?.trim()
      ? path.resolve(ENGINE_CWD, env.JUSTSEARCH_SPLADE_MODEL_PATH) : null,
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
  const paths = pathsFor(dataDir, env);
  const home = physicalPath(paths.appHome);
  const privateRoot = path.join(home, FOLDER);
  const cacheRoot = path.join(home, CACHE_FOLDER);
  for (const [folder, root] of [[paths.models, privateRoot], [paths.optimizedCache, cacheRoot]]) {
    if (physicalPath(folder) !== root) refuse(`${folder} redirects outside its private folder`);
    validateTree(root);
  }
  const check = (name, value, root = privateRoot, exact = false, base = ENGINE_CWD) => {
    if (value == null || value === '') return;
    if (typeof value !== 'string') refuse(`${name} must be a path string`);
    if (!value.trim()) return;
    const resolved = physicalPath(path.resolve(base, value));
    if (exact ? resolved !== root : !within(root, resolved)) {
      refuse(`${name}=${value} must ${exact ? 'equal' : 'stay inside'} ${root}; unset it or use a fresh data folder before starting with --isolated-models`);
    }
  };
  const checkRepo = (name, value) => {
    if (value?.trim()) check(name, path.resolve(ENGINE_CWD, value, 'models'));
  };
  for (const name of Object.keys(MODEL_OVERRIDES)) check(name, env[name], privateRoot, name === 'JUSTSEARCH_MODELS_DIR');
  check('JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR', env.JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR, cacheRoot, true);
  checkRepo('JUSTSEARCH_REPO_ROOT', env.JUSTSEARCH_REPO_ROOT);
  // Local/base fallback is consulted even with an explicit models directory. An absent
  // fallback is harmless; an existing one must resolve into the private tree.
  const fallback = path.join(home, 'models');
  if (fs.existsSync(fallback) || fs.lstatSync(fallback, { throwIfNoEntry: false })) check('<dataDir>/models', fallback);
  // System properties take priority over env in the Engine. Check every inherited JVM option
  // channel, including the ones the JVM reads itself rather than through the launch script.
  // Match the JVM's property precedence when reporting the effective SPLADE override.
  for (const name of ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'JAVA_OPTS', 'UI_OPTS', '_JAVA_OPTIONS']) {
    for (const option of splitJvmOptions(env[name])) {
      const match = option.match(/^-D([^=]+)=(.*)$/s);
      if (!match) continue;
      if (Object.values(MODEL_OVERRIDES).includes(match[1])) {
        check(`${name} ${match[1]}`, match[2], privateRoot, match[1] === 'justsearch.models.dir');
        if (match[1] === 'justsearch.splade.model_path' && match[2].trim()) paths.splade = path.resolve(ENGINE_CWD, match[2]);
      }
      if (match[1] === CACHE_PROPERTY) check(`${name} ${match[1]}`, match[2], cacheRoot, true);
      if (match[1] === 'justsearch.repo.root') checkRepo(`${name} ${match[1]}`, match[2]);
      if (['justsearch.home', 'justsearch.data.dir'].includes(match[1])) check(`${name} ${match[1]}`, match[2], home, true);
      // Relative paths in Java resolve against user.dir; keep that base the launcher's cwd.
      if (match[1] === 'user.dir') check(`${name} user.dir`, match[2], physicalPath(ENGINE_CWD), true);
    }
  }
  const readSaved = file => {
    check(file, file, home);
    let text;
    try { text = fs.readFileSync(file, 'utf8'); }
    catch (error) { if (error.code === 'ENOENT') return null; throw error; }
    let saved;
    try { saved = JSON.parse(text); }
    catch { refuse(`cannot validate saved paths in ${file}: invalid JSON`); }
    if (!saved || typeof saved !== 'object' || Array.isArray(saved)) refuse(`cannot validate saved paths in ${file}: expected an object`);
    return saved;
  };
  const savedSettings = readSaved(paths.settings);
  const settings = savedSettings && Object.hasOwn(savedSettings, 'settings') ? savedSettings.settings : savedSettings;
  if (savedSettings && (!settings || typeof settings !== 'object' || Array.isArray(settings))) refuse(`cannot validate saved paths in ${paths.settings}: expected settings object`);
  for (const key of SETTINGS_PATHS) check(`${paths.settings} ${key}`, settings?.[key]);
  // Settings can supply SPLADE when no higher-priority override is present.
  if (!paths.splade && settings?.spladeModelPath?.trim()) paths.splade = path.resolve(ENGINE_CWD, settings.spladeModelPath);
  const contract = readSaved(paths.installContract);
  if (contract) {
    // Jackson serializes java.nio.file.Path as a file URI, and trims path strings on
    // read (empty strings become null). Accept the product's own saved representation.
    let modelsDir = contract.modelsDir;
    if (typeof modelsDir === 'string') {
      modelsDir = modelsDir.trim();
      if (/^file:/i.test(modelsDir)) {
        try { modelsDir = fileURLToPath(modelsDir); }
        catch { refuse(`invalid modelsDir file URI in ${paths.installContract}`); }
      }
    }
    check(`${paths.installContract} modelsDir`, modelsDir, privateRoot, true);
    for (const [id, model] of Object.entries(contract.models ?? {})) {
      if (!model || typeof model !== 'object') refuse(`invalid saved model ${id} in ${paths.installContract}`);
      const target = model.targetDir ?? '';
      check(`${paths.installContract} ${id}.targetDir`, target, privateRoot, false, privateRoot);
      const base = path.resolve(privateRoot, target);
      for (const file of [model.variantFilename, ...(model.installedFiles ?? [])]) {
        check(`${paths.installContract} ${id} file`, file, privateRoot, false, base);
      }
    }
  }
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

module.exports = { FOLDER, CACHE_FOLDER, STATES, pathsFor, validateOverrides, effectiveTargetDir, ensureDirectory, prepare, printPaths };
