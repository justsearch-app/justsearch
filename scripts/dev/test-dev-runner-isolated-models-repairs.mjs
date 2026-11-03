import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);
// Allows the identical behavioral checks to run against a detached before checkout.
const repo = path.resolve(process.env.ISOLATED_MODELS_TEST_ROOT ?? path.join(import.meta.dirname, '../..'));
const isolated = require(path.join(repo, 'scripts/dev/lib/isolated-models.cjs'));
const { buildDevRunnerArgsStart } = await import(pathToFileURL(path.join(repo, 'scripts/dev/justsearch-dev-mcp/cli.mjs')));
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'isolated-repairs-'));
const tests = [];
const test = (name, fn) => tests.push([name, fn]);
const prepare = (dataDir, env = {}, state = 'none') => isolated.prepare({ dataDir, env, state, repoRoot: repo });
const conflict = fn => assert.throws(fn, error => error.code === 'ISOLATED_MODELS_CONFLICT');
const save = (file, value) => { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, JSON.stringify(value)); };

test('F1: CLI and MCP-built starts without dataDir use the resolved default', () => {
  // A disposable checkout shape keeps the default data folder away from any real dev stack.
  // A deliberately invalid runtime stops the CLI immediately after model preparation.
  for (const transport of ['cli', 'mcp']) {
    const fake = path.join(root, transport);
    for (const relative of ['scripts/dev', 'scripts/lib', 'governance', 'contracts']) {
      fs.cpSync(path.join(repo, relative), path.join(fake, relative), { recursive: true });
    }
    const defaultData = path.join(fake, 'modules/ui-web/.dev-data');
    require(path.join(fake, 'scripts/dev/lib/dev-data-folder.cjs')).claimDevDataFolder(defaultData, { tool: 'dev-runner' });
    for (const folder of ['isolated-models', 'isolated-ort-cache']) {
      fs.mkdirSync(path.join(defaultData, folder));
      fs.writeFileSync(path.join(defaultData, folder, 'progress'), 'retained');
    }
    const invalidRuntime = path.join(fake, 'not-executable');
    fs.writeFileSync(invalidRuntime, 'invalid');
    const args = transport === 'cli' ? ['start', '--json', '--isolated-models', '--skip-build', '--clean=hard', '--api-port=0']
      : buildDevRunnerArgsStart({ apiPort: 0, uiPort: 5173, clean: 'hard', isolatedModels: 'none', skipBuild: true });
    const result = spawnSync(process.execPath, [path.join(fake, 'scripts/dev/dev-runner.cjs'), ...args], {
      encoding: 'utf8', timeout: 20000, cwd: root,
      env: { ...process.env, JUSTSEARCH_SERVER_EXE: invalidRuntime, JS_ADMISSION_DIR: path.join(fake, 'admission'),
        JUSTSEARCH_DEV_RUNNER_STATE_ROOT: path.join(fake, 'state') },
    });
    assert.equal(result.status, 1, result.stdout + result.stderr);
    const failure = JSON.parse(result.stdout);
    assert.match(failure.error.message, /JUSTSEARCH_SERVER_EXE must name a regular executable file/, result.stdout + result.stderr);
    const printed = JSON.parse(result.stderr.match(/Isolated AI paths: (.+)/)?.[1] ?? '{}');
    assert.equal(printed.appHome, path.join(fake, 'modules/ui-web/.dev-data'));
    assert.equal(printed.models, path.join(printed.appHome, 'isolated-models'));
    for (const folder of ['isolated-models', 'isolated-ort-cache']) {
      assert.equal(fs.readFileSync(path.join(defaultData, folder, 'progress'), 'utf8'), 'retained');
    }
  }
});

test('F2: relative data and model overrides resolve against the Engine checkout from a foreign cwd', () => {
  const original = process.cwd();
  try {
    process.chdir(root);
    const relative = 'tmp/relative-isolated-test';
    const paths = isolated.validateOverrides(relative, { JUSTSEARCH_SPLADE_MODEL_PATH: `${relative}/isolated-models/splade` });
    assert.equal(paths.appHome, path.join(repo, relative));
    assert.equal(paths.models, path.join(repo, relative, 'isolated-models'));
    assert.equal(paths.splade, path.join(paths.models, 'splade'));
    // Java Path.of preserves whitespace in env/settings strings; trimming can admit an escape.
    conflict(() => isolated.validateOverrides(path.join(repo, relative), {
      JAVA_OPTS: `-Djustsearch.models.dir="${paths.models} "`,
    }));
    conflict(() => isolated.validateOverrides(path.join(repo, relative), {
      JUSTSEARCH_SPLADE_MODEL_PATH: ` ${path.join(paths.models, 'splade')}`,
    }));
    const channels = ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'JAVA_OPTS', 'UI_OPTS', '_JAVA_OPTIONS'];
    const env = Object.fromEntries(channels.map((name, i) => [name, `-Djustsearch.splade.model_path="${path.join(paths.models, String(i))}"`]));
    for (let i = channels.length - 1; i >= 0; i--) {
      assert.equal(isolated.validateOverrides(path.join(repo, relative), env).splade, path.join(paths.models, String(i)));
      delete env[channels[i]];
    }
    assert.doesNotThrow(() => isolated.validateOverrides(path.join(repo, relative), {
      JAVA_OPTS: `-Djustsearch.home=${relative} -Djustsearch.splade.model_path=${relative}/isolated-models/splade`,
    }));
  } finally { process.chdir(original); }
});

test('F3: repo-root and local model discovery fallbacks cannot escape isolation', () => {
  const data = path.join(root, 'fallback');
  conflict(() => prepare(data, { JUSTSEARCH_REPO_ROOT: repo }));
  for (const name of ['JAVA_OPTS', 'UI_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS']) {
    conflict(() => prepare(data, { [name]: `-Djustsearch.repo.root="${repo}"` }));
  }
  assert.equal(fs.existsSync(data), false);
  fs.mkdirSync(path.join(data, 'models'), { recursive: true });
  fs.writeFileSync(path.join(data, 'models', 'model.onnx'), 'shared');
  conflict(() => prepare(data));
  fs.rmSync(path.join(data, 'models'), { recursive: true });
  prepare(data);
  fs.symlinkSync(path.join(data, 'isolated-models'), path.join(data, 'models'), 'junction');
  assert.doesNotThrow(() => prepare(data));
});

test('F4: legacy and versioned settings and install-contract external paths are refused without rewriting', () => {
  const data = path.join(root, 'saved');
  const settings = path.join(data, 'ui/settings.json');
  for (const key of ['llmModelPath', 'embedOnnxModelPath', 'rerankerModelPath', 'nerModelPath', 'spladeModelPath', 'citationScorerModelPath']) {
    for (const envelope of [false, true]) {
      const value = { [key]: path.join(root, 'shared-models') };
      save(settings, envelope ? { schemaVersion: 6, settings: value } : value);
      const bytes = fs.readFileSync(settings);
      conflict(() => prepare(data));
      assert.deepEqual(fs.readFileSync(settings), bytes);
      assert.equal(fs.existsSync(path.join(data, 'isolated-models')), false);
    }
  }
  save(settings, { schemaVersion: 6, settings: { spladeModelPath: path.join(data, 'isolated-models/splade') } });
  const contract = path.join(data, 'install-contract.v2.json');
  for (const value of [
    { modelsDir: path.join(root, 'shared-models') },
    { models: { embedding: { targetDir: '../escape', variantFilename: 'model.onnx' } } },
    { models: { embedding: { targetDir: 'embedding', installedFiles: ['../../escape'] } } },
  ]) {
    save(contract, { schemaVersion: 2, ...value });
    const bytes = fs.readFileSync(contract);
    conflict(() => prepare(data));
    assert.deepEqual(fs.readFileSync(contract), bytes);
  }
  save(contract, { schemaVersion: 2, modelsDir: path.join(data, 'isolated-models'), models: {} });
  assert.doesNotThrow(() => prepare(data));
  // A real InstallContractIO write uses file URIs (including percent-encoded spaces).
  const spacedData = path.join(root, 'saved with spaces');
  const serialized = pathToFileURL(path.join(spacedData, 'isolated-models')).href;
  save(path.join(spacedData, 'install-contract.v2.json'), { schemaVersion: 2, modelsDir: serialized, models: {} });
  assert.doesNotThrow(() => prepare(spacedData));
  save(contract, { schemaVersion: 2, modelsDir: pathToFileURL(path.join(root, 'shared-models')).href });
  conflict(() => prepare(data));
});

test('F5: optimized graph cache is private; every JVM channel rejects external overrides', () => {
  const data = path.join(root, 'cache');
  const result = prepare(data);
  const cache = path.join(data, 'isolated-ort-cache');
  assert.equal(result.env.JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR, cache);
  assert.equal(result.paths.optimizedCache, cache);
  conflict(() => prepare(data, { JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR: root }));
  for (const name of ['JAVA_OPTS', 'UI_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS']) {
    conflict(() => prepare(data, { [name]: `-Djustsearch.ort.optimized_cache_dir="${root}"` }));
  }
  assert.doesNotThrow(() => prepare(data, { JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR: cache }));

});

test('F6: every state and restart guard rejects escaping descendants, including dangling links', () => {
  const data = path.join(root, 'links');
  const shared = path.join(root, 'shared');
  fs.mkdirSync(shared);
  const { paths } = prepare(data);
  for (const target of [shared, path.join(shared, 'not-yet-created')]) {
    const link = path.join(paths.models, 'onnx');
    fs.symlinkSync(target, link, 'junction');
    for (const state of ['none', 'partial', 'truncated']) conflict(() => prepare(data, {}, state));
    conflict(() => isolated.ensureDirectory(data, {}));
    fs.unlinkSync(link);
  }
  fs.symlinkSync(shared, path.join(paths.optimizedCache, 'redirect'), 'junction');
  conflict(() => isolated.ensureDirectory(data, {}));
  assert.deepEqual(fs.readdirSync(shared), []);
});

let failed = 0;
try {
  for (const [name, run] of tests) {
    try { await run(); console.log(`PASS ${name}`); }
    catch (error) { failed++; console.error(`FAIL ${name}: ${error.stack}`); }
  }
} finally { fs.rmSync(root, { recursive: true, force: true }); }
console.log(`isolated-models repairs: ${tests.length - failed}/${tests.length} passed`);
process.exitCode = failed ? 1 : 0;
