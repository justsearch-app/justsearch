import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
// Hermetic: inherited JustSearch and JVM settings from the caller (CI sets JUSTSEARCH_PR_CI,
// developers may set more) must not change what these tests observe.
for (const name of Object.keys(process.env)) {
  if (/^JUSTSEARCH_/.test(name) || /^(?:JAVA_OPTS|UI_OPTS|JDK_JAVA_OPTIONS|JAVA_TOOL_OPTIONS|_JAVA_OPTIONS)$/.test(name)) delete process.env[name];
}

const api = require('./dev-runner.cjs').__test;
const isolated = require('./lib/isolated-models.cjs');
const repoRoot = path.resolve(import.meta.dirname, '../..');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'isolated-models-'));
const tests = [];
const test = (name, fn) => tests.push([name, fn]);
const registry = JSON.parse(fs.readFileSync(path.join(repoRoot,
  'modules/configuration/src/main/resources/ai/model-registry.v2.json'), 'utf8'));
const embedding = registry.packages.find(pkg => pkg.id === 'embedding');
const prepare = (state, dataDir = path.join(root, state), env = {}) =>
  isolated.prepare({ state, dataDir, repoRoot, env });

function entries(dir) {
  return fs.readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter(entry => entry.isFile()).map(entry => path.join(entry.parentPath, entry.name)).sort();
}

test('S1: CLI distinguishes absent isolation, bare flag, and each explicit seed state', () => {
  assert.equal(api.parseArgs(['start']).isolatedModels, null);
  assert.equal(api.parseArgs(['start', '--isolated-models']).isolatedModels, 'none');
  for (const state of isolated.STATES) {
    assert.equal(api.parseArgs(['start', `--isolated-models=${state}`]).isolatedModels, state);
    assert.equal(api.parseArgs(['start', '--isolated-models', state]).isolatedModels, state);
  }
  for (const state of ['', 'installed', 'PARTIAL']) {
    assert.throws(() => api.parseArgs(['start', `--isolated-models=${state}`]), /Invalid --isolated-models/);
  }
  assert.throws(() => api.parseArgs(['stop', '--isolated-models']), /only valid for start/);
});

test('S1: empty private folder exists before launch, without any copied model bytes', () => {
  const result = prepare('none');
  assert.equal(result.env.JUSTSEARCH_MODELS_DIR, path.join(root, 'none', 'isolated-models'));
  assert.ok(fs.statSync(result.env.JUSTSEARCH_MODELS_DIR).isDirectory());
  assert.deepEqual(entries(result.env.JUSTSEARCH_MODELS_DIR), []);
  assert.equal(result.env.JUSTSEARCH_SPLADE_MODEL_PATH, undefined);
});

test('S2: seeds are bounded, incomplete files at registry-derived variant and supporting targets', () => {
  for (const state of ['partial', 'truncated']) {
    const { env } = prepare(state);
    const models = env.JUSTSEARCH_MODELS_DIR;
    const actual = entries(models);
    const expected = new Set();
    for (const pkg of registry.packages.filter(pkg => !pkg.installRoot && !pkg.devOnly)) {
      for (const variant of pkg.variants?.length ? pkg.variants : [null]) {
        for (const file of [variant, ...(pkg.supportingFiles ?? [])].filter(Boolean)) {
          if (!Number.isSafeInteger(file.sizeBytes) || file.sizeBytes <= 1) continue;
          const final = path.join(models, isolated.effectiveTargetDir(pkg, variant), file.filename);
          const target = state === 'partial' ? `${final}.partial` : final;
          expected.add(target);
          assert.ok(fs.statSync(target).size <= 64);
          assert.ok(fs.statSync(target).size < file.sizeBytes, `${target} cannot pass size checks`);
        }
      }
    }
    assert.deepEqual(actual, [...expected].sort());
    assert.ok(actual.length > 0);
  }
});

test('S2: candidate identity includes supporting hashes, normalizes SHA case and ignores file order', () => {
  const variant = embedding.variants[0];
  const original = isolated.effectiveTargetDir(embedding, variant);
  assert.match(original, new RegExp(`^${embedding.targetDir}/candidates/[a-f0-9]{64}$`));
  assert.equal(isolated.effectiveTargetDir({ ...embedding, supportingFiles: [...embedding.supportingFiles].reverse() }, variant), original);
  assert.equal(isolated.effectiveTargetDir(embedding, { ...variant, sha256: variant.sha256.toLowerCase() }), original);
  const changed = structuredClone(embedding);
  changed.supportingFiles[0].sha256 = '0'.repeat(64);
  assert.notEqual(isolated.effectiveTargetDir(changed, variant), original);
  assert.notEqual(isolated.effectiveTargetDir(embedding, embedding.variants[1]), original);
  assert.equal(isolated.effectiveTargetDir(embedding, { filename: 'chat.gguf' }), embedding.targetDir);
});

test('S3: unlisted inherited JUSTSEARCH variables and all JVM properties are refused before mutation', () => {
  const dataDir = path.join(root, 'refused');
  for (const env of [
    { JUSTSEARCH_SPLADE_MODEL_PATH: root }, { JUSTSEARCH_CONFIG: 'config.yaml' },
    { JUSTSEARCH_NEW_MODEL_PATH: root },
    { JUSTSEARCH_REPO_ROOT: root }, { JUSTSEARCH_UNEXPECTED: 'true' },
    ...['JAVA_OPTS', 'UI_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'].flatMap(name =>
      [{ [name]: '-Danything=value' }, { [name]: '@options.txt' }]),
  ]) {
    assert.throws(() => prepare('partial', dataDir, env), error =>
      error.code === 'ISOLATED_MODELS_CONFLICT' && error.message.includes(Object.keys(env)[0]));
    assert.equal(fs.existsSync(dataDir), false);
  }
  assert.doesNotThrow(() => isolated.validateOverrides(dataDir, {
    JUSTSEARCH_GPU_ENABLED: 'true', JUSTSEARCH_SERVER_EXE: '/some/server', JAVA_OPTS: '-Xmx512m',
  }));
});

test('S3: printed paths name the actual models, SPLADE override, home, settings and contract', () => {
  const dataDir = path.join(root, 'printed');
  const splade = null;
  const { paths } = prepare('none', dataDir);
  let output = '';
  isolated.printPaths(paths, text => { output += text; });
  assert.deepEqual(JSON.parse(output.slice(output.indexOf('{'))), {
    models: path.join(dataDir, 'isolated-models'), optimizedCache: path.join(dataDir, 'isolated-ort-cache'), splade, appHome: dataDir,
    settings: path.join(dataDir, 'ui', 'settings.json'), installContract: path.join(dataDir, 'install-contract.v2.json'),
  });
});

test('S4: restarts and both start cleans preserve private progress; a deleted folder is recreated', async () => {
  const dataDir = path.join(root, 'restart');
  const { env } = prepare('partial', dataDir);
  const owned = path.join(root, 'owned');
  const first = prepare('partial', owned);
  const staged = entries(first.env.JUSTSEARCH_MODELS_DIR)[0];
  fs.writeFileSync(staged, 'retained download progress');
  for (const mode of ['soft', 'hard']) {
    await api.cleanOwnDataDir(owned, mode, [isolated.FOLDER, isolated.CACHE_FOLDER, isolated.MARKER]);
    assert.equal(prepare('partial', owned).env.JUSTSEARCH_MODELS_DIR, first.env.JUSTSEARCH_MODELS_DIR);
    assert.equal(fs.readFileSync(staged, 'utf8'), 'retained download progress');
  }
  fs.rmSync(env.JUSTSEARCH_MODELS_DIR, { recursive: true });
  assert.equal(prepare('none', dataDir).env.JUSTSEARCH_MODELS_DIR, env.JUSTSEARCH_MODELS_DIR);
  assert.deepEqual(entries(env.JUSTSEARCH_MODELS_DIR), []);
});

test('S3: a symlink cannot redirect the private folder or a seeded subtree into shared models', () => {
  const dataDir = path.join(root, 'linked');
  const shared = path.join(root, 'shared-link-target');
  prepare('none', dataDir); fs.mkdirSync(shared);
  fs.rmdirSync(path.join(dataDir, isolated.FOLDER));
  fs.symlinkSync(shared, path.join(dataDir, 'isolated-models'), 'junction');
  assert.throws(() => prepare('partial', dataDir), /redirects outside/);
  fs.unlinkSync(path.join(dataDir, 'isolated-models'));
  const { env } = prepare('none', dataDir);
  fs.symlinkSync(shared, path.join(env.JUSTSEARCH_MODELS_DIR, 'onnx'), 'junction');
  assert.throws(() => prepare('partial', dataDir), /escapes the private folder/);
  assert.deepEqual(fs.readdirSync(shared), []);
});

test('S3: CLI refuses conflicting inherited overrides before admitting a run or claiming data', () => {
  // Data must be below the checkout for dev-runner; no Engine or frontend is started here.
  const dataDir = path.join(repoRoot, 'tmp', `isolated-refusal-${path.basename(root)}`);
  const state = path.join(root, 'cli-state');
  const result = spawnSync(process.execPath, ['scripts/dev/dev-runner.cjs', 'start', '--json', '--isolated-models',
    '--skip-build', '--api-port=0', `--data-dir=${dataDir}`], {
    cwd: repoRoot, encoding: 'utf8', timeout: 20000,
    env: { ...process.env, JS_ADMISSION_DIR: path.join(root, 'admission'),
      JUSTSEARCH_DEV_RUNNER_STATE_ROOT: state, JUSTSEARCH_SPLADE_MODEL_PATH: path.join(root, 'shared') },
  });
  assert.equal(result.status, 1, result.stdout + result.stderr);
  const failure = JSON.parse(result.stdout);
  assert.equal(failure.error.code, 'ISOLATED_MODELS_CONFLICT');
  assert.match(failure.error.message, /JUSTSEARCH_SPLADE_MODEL_PATH/);
  assert.equal(fs.existsSync(path.join(state, 'active.json')), false);
  assert.equal(fs.existsSync(dataDir), false);
});

test('P1: ordinary resolution retains shared models without injecting SPLADE demand', () => {
  const saved = { ...process.env };
  try {
    for (const name of ['JUSTSEARCH_MODELS_DIR', 'JUSTSEARCH_SPLADE_MODEL_PATH', 'JUSTSEARCH_SUPERVISOR_HARNESS', 'JUSTSEARCH_SERVER_EXE']) delete process.env[name];
    const env = api.resolveAiDevEnv();
    const mainRoot = require('./lib/dev-runtime.cjs').resolveMainRepoRoot(repoRoot);
    const expected = fs.existsSync(path.join(mainRoot, 'models')) ? path.join(mainRoot, 'models') : path.join(repoRoot, 'models');
    assert.equal(env.JUSTSEARCH_MODELS_DIR, expected);
    const splade = path.join(expected, 'splade', 'naver-splade-v3');
    if (['model.onnx', 'tokenizer.json', 'vocab.txt'].every(file => fs.existsSync(path.join(splade, file)))) {
      assert.equal(env.JUSTSEARCH_SPLADE_MODEL_PATH, splade);
      assert.equal(env.JUSTSEARCH_SPLADE_ENABLED, undefined);
    } else assert.equal(env.JUSTSEARCH_SPLADE_MODEL_PATH, undefined);
    const isolatedEnv = api.resolveAiDevEnv({ isolatedModels: 'none', dataDir: path.join(root, 'resolved') });
    assert.equal(isolatedEnv.JUSTSEARCH_MODELS_DIR, path.join(root, 'resolved', 'isolated-models'));
    assert.equal(isolatedEnv.JUSTSEARCH_SPLADE_MODEL_PATH, undefined);
  } finally { process.env = saved; }
});

let failed = 0;
try {
  for (const [name, fn] of tests) {
    try { await fn(); console.log(`PASS ${name}`); }
    catch (error) { failed++; console.error(`FAIL ${name}: ${error.stack}`); }
  }
} finally { fs.rmSync(root, { recursive: true, force: true }); }
console.log(`isolated-models: ${tests.length - failed}/${tests.length} passed`);
process.exitCode = failed ? 1 : 0;
