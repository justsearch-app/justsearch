'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { splitJvmOptions } = require('./engine-java-launch.cjs');

const FOLDER = 'isolated-models';
const STATES = ['none', 'partial', 'truncated'];
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
    const parent = path.dirname(candidate);
    if (parent === candidate) throw error;
    return path.join(physicalPath(parent), path.basename(candidate));
  }
}

function pathsFor(dataDir, env = process.env) {
  const home = path.resolve(dataDir);
  return {
    models: path.join(home, FOLDER),
    splade: env.JUSTSEARCH_SPLADE_MODEL_PATH || null,
    appHome: home,
    settings: path.join(home, 'ui', 'settings.json'),
    installContract: path.join(home, 'install-contract.v2.json'),
  };
}

function validateOverrides(dataDir, env = process.env) {
  const paths = pathsFor(dataDir, env);
  const privateRoot = path.join(physicalPath(paths.appHome), FOLDER);
  if (physicalPath(paths.models) !== privateRoot) refuse(`${paths.models} redirects outside its private folder`);
  const check = (name, value, root = privateRoot, exact = false) => {
    if (!value?.trim()) return;
    const resolved = physicalPath(path.resolve(value.trim()));
    if (exact ? resolved !== root : !within(root, resolved)) {
      refuse(`${name}=${value} must ${exact ? 'equal' : 'stay inside'} ${root}; unset it before starting with --isolated-models`);
    }
  };
  for (const name of Object.keys(MODEL_OVERRIDES)) check(name, env[name], privateRoot, name === 'JUSTSEARCH_MODELS_DIR');
  // System properties take priority over env in the Engine. Check every inherited JVM option
  // channel, including the ones the JVM reads itself rather than through the launch script.
  for (const name of ['JAVA_OPTS', 'UI_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS']) {
    for (const option of splitJvmOptions(env[name])) {
      const match = option.match(/^-D([^=]+)=(.*)$/s);
      if (!match) continue;
      if (Object.values(MODEL_OVERRIDES).includes(match[1])) {
        check(`${name} ${match[1]}`, match[2], privateRoot, match[1] === 'justsearch.models.dir');
        if (match[1] === 'justsearch.splade.model_path') paths.splade = match[2];
      }
      if (['justsearch.home', 'justsearch.data.dir'].includes(match[1])) {
        check(`${name} ${match[1]}`, match[2], physicalPath(paths.appHome), true);
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

function ensureDirectory(dataDir) {
  const { models } = validateOverrides(dataDir, {});
  fs.mkdirSync(models, { recursive: true });
  return models;
}

function prepare({ dataDir, state, repoRoot, env = process.env }) {
  if (!STATES.includes(state)) throw new Error(`Invalid --isolated-models: ${state} (expected none|partial|truncated)`);
  const paths = validateOverrides(dataDir, env);
  const models = ensureDirectory(dataDir);
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
  return { paths, env: { JUSTSEARCH_MODELS_DIR: models } };
}

function printPaths(paths, write = text => process.stderr.write(text)) {
  write(`[dev-runner] Isolated AI paths: ${JSON.stringify(paths)}\n`);
}

module.exports = { FOLDER, STATES, pathsFor, validateOverrides, effectiveTargetDir, ensureDirectory, prepare, printPaths };
