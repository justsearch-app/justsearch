import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);
// Hermetic: inherited JustSearch and JVM settings from the caller (CI sets JUSTSEARCH_PR_CI,
// developers may set more) must not change what these tests observe.
for (const name of Object.keys(process.env)) {
  if (/^JUSTSEARCH_/.test(name) || /^(?:JAVA_OPTS|UI_OPTS|JDK_JAVA_OPTIONS|JAVA_TOOL_OPTIONS|_JAVA_OPTIONS)$/.test(name)) delete process.env[name];
}

// Run the same new checks against a detached before checkout without changing that tree.
const repo = path.resolve(process.env.ISOLATED_MODELS_TEST_ROOT ?? path.join(import.meta.dirname, '../..'));
const isolated = require(path.join(repo, 'scripts/dev/lib/isolated-models.cjs'));
const { buildDevRunnerArgsStart, runCliJson } = await import(pathToFileURL(path.join(repo, 'scripts/dev/justsearch-dev-mcp/cli.mjs')));
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'isolated-refusals-'));
const tests = [];
const test = (name, fn) => tests.push([name, fn]);
const prepare = (dataDir, env = {}) => isolated.prepare({ dataDir, env, state: 'none', repoRoot: repo });
const conflict = fn => assert.throws(fn, error => error.code === 'ISOLATED_MODELS_CONFLICT');
const save = (file, text) => { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, text); };

function fixture(name) {
  const fake = path.join(root, name);
  for (const relative of ['scripts/dev', 'scripts/lib', 'governance', 'contracts']) {
    fs.cpSync(path.join(repo, relative), path.join(fake, relative), { recursive: true });
  }
  const invalidRuntime = path.join(fake, 'not-executable');
  fs.writeFileSync(invalidRuntime, 'invalid');
  const env = { ...process.env, JUSTSEARCH_SERVER_EXE: invalidRuntime,
    JS_ADMISSION_DIR: path.join(fake, 'admission'), JUSTSEARCH_DEV_RUNNER_STATE_ROOT: path.join(fake, 'state') };
  return { fake, env, data: path.join(fake, 'modules/ui-web/.dev-data') };
}
function start(f, { env = {}, args = ['start', '--json', '--isolated-models', '--skip-build', '--clean=none', '--api-port=0'] } = {}) {
  return spawnSync(process.execPath, [path.join(f.fake, 'scripts/dev/dev-runner.cjs'), ...args], {
    encoding: 'utf8', timeout: 20000, cwd: root, env: { ...f.env, ...env },
  });
}
function refused(f, options, source) {
  const result = start(f, options);
  assert.equal(result.status, 1, result.stdout + result.stderr);
  const failure = JSON.parse(result.stdout);
  assert.equal(failure.error.code, 'ISOLATED_MODELS_CONFLICT', result.stdout + result.stderr);
  assert.match(failure.error.message, source);
  assert.equal(fs.existsSync(path.join(f.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT, 'active.json')), false);
  return result;
}
function reachedRuntime(result) {
  assert.equal(result.status, 1, result.stdout + result.stderr);
  assert.match(JSON.parse(result.stdout).error.message, /JUSTSEARCH_SERVER_EXE must name a regular executable file/);
}

test('S3: CLI refuses every inherited source before admission or data mutation', () => {
  const f = fixture('sources');
  const yaml = path.join(root, 'application.yaml');
  save(yaml, `llm:\n  model_path: ${root}/shared.gguf\n`);
  const argfile = path.join(root, 'options.txt');
  save(argfile, `-Djustsearch.config=${yaml}`);
  for (const env of [
    { JUSTSEARCH_CONFIG: yaml }, { JUSTSEARCH_UNEXPECTED_PATH: root },
    { JUSTSEARCH_REPO_ROOT: root }, { JUSTSEARCH_UNEXPECTED: 'true' },
    { JUSTSEARCH_ANOTHER_MODEL: 'shared.gguf' }, { JUSTSEARCH_CUSTOM_PROFILE: 'foreign' },
    ...['JAVA_OPTS', 'UI_OPTS', 'JDK_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS'].flatMap(name => [
      { [name]: `-Djustsearch.config="${yaml}"` }, { [name]: `-Duser.dir="${root}"` },
      { [name]: '-Dunrelated' }, { [name]: `@"${argfile}"` },
    ]),
  ]) {
    refused(f, { env }, new RegExp(Object.keys(env)[0]));
    assert.equal(fs.existsSync(f.data), false);
  }
});

test('S3: unmarked non-empty folders, saved query roles and index manifests are refused unchanged', () => {
  const f = fixture('existing');
  for (const [relative, text] of [
    ['foreign.txt', 'keep me'],
    ['ui/settings.json', JSON.stringify({ settings: { queryRoles: { chat: { modelPath: '/shared/model.gguf' } } } })],
    ['index/default/indices/g-old/.justsearch-index-generation.json', JSON.stringify({ modelPath: '/shared/model.onnx' })],
  ]) {
    save(path.join(f.data, relative), text);
    refused(f, {}, /non-empty data folder/);
    assert.equal(fs.readFileSync(path.join(f.data, relative), 'utf8'), text);
    assert.equal(fs.existsSync(path.join(f.data, 'isolated-models')), false);
    fs.rmSync(f.data, { recursive: true });
  }
  // A normal dev-data marker is not evidence of an isolated start.
  require(path.join(f.fake, 'scripts/dev/lib/dev-data-folder.cjs')).claimDevDataFolder(f.data, { tool: 'dev-runner' });
  refused(f, {}, /previous isolated start/);
});

test('S3: marked folders still refuse foreign content and saved configuration before cleaning', () => {
  const f = fixture('foreign');
  const local = require(path.join(f.fake, 'scripts/dev/lib/isolated-models.cjs'));
  local.prepare({ dataDir: f.data, env: {}, state: 'none', repoRoot: f.fake });
  for (const relative of ['foreign.txt', 'config/application.yaml', 'ui/settings.json', 'install-contract.v2.json', 'models/shared.onnx']) {
    save(path.join(f.data, relative), '{}');
    refused(f, { args: ['start', '--json', '--isolated-models', '--skip-build', '--clean=hard'] }, /foreign content/);
    assert.equal(fs.readFileSync(path.join(f.data, relative), 'utf8'), '{}');
    fs.rmSync(path.join(f.data, relative.split('/')[0]), { recursive: true });
  }
});

test('S4: CLI and MCP marked starts accept the resolved default and preserve both folders through cleans', async () => {
  for (const transport of ['cli', 'mcp']) {
    const f = fixture(transport);
    const local = require(path.join(f.fake, 'scripts/dev/lib/isolated-models.cjs'));
    local.prepare({ dataDir: f.data, env: {}, state: 'none', repoRoot: f.fake });
    const marker = fs.readFileSync(path.join(f.data, local.MARKER), 'utf8');
    for (const folder of ['isolated-models', 'isolated-ort-cache']) save(path.join(f.data, folder, 'progress'), 'retained');
    for (const clean of ['soft', 'hard', 'none']) {
      const args = transport === 'cli' ? ['start', '--json', '--isolated-models', '--skip-build', `--clean=${clean}`, '--api-port=0']
        : buildDevRunnerArgsStart({ apiPort: 0, uiPort: 5173, clean, isolatedModels: 'none', skipBuild: true });
      const mcp = transport === 'mcp' ? await runCliJson({ repoRoot: f.fake,
        devRunnerPath: path.join(f.fake, 'scripts/dev/dev-runner.cjs'), args,
        env: { ...f.env, JUSTSEARCH_DEV_MCP_REPO_ROOT: f.fake,
          ...require(path.join(repo, 'scripts/dev/lib/agent-identity.cjs')).buildHandoffEnv({
            harness: 'ci', owner: null, sessionId: 'isolated-test', source: 'explicit',
          }) }, timeoutMs: 20000, mode: 'oneshot' }) : null;
      const result = mcp ? { status: mcp.exitCode, stdout: JSON.stringify(mcp.json), stderr: mcp.stderr } : start(f, { args });
      reachedRuntime(result);
      const printed = JSON.parse(result.stderr.match(/Isolated AI paths: (.+)/)?.[1] ?? '{}');
      assert.equal(printed.appHome, f.data);
      assert.equal(fs.readFileSync(path.join(f.data, local.MARKER), 'utf8'), marker);
      for (const folder of ['isolated-models', 'isolated-ort-cache']) {
        assert.equal(fs.readFileSync(path.join(f.data, folder, 'progress'), 'utf8'), 'retained');
      }
    }
  }
});

test('P1: default start still accepts inherited config and saved settings', () => {
  const f = fixture('ordinary');
  save(path.join(f.data, 'ui/settings.json'), '{"queryRoles":{}}');
  const result = start(f, { args: ['start', '--json', '--skip-build', '--clean=none'],
    env: { JUSTSEARCH_CONFIG: '/ordinary/application.yaml', JAVA_OPTS: '-Duser.dir=/ordinary' } });
  reachedRuntime(result);
  assert.doesNotMatch(result.stderr, /Isolated AI paths/);
  assert.equal(fs.existsSync(path.join(f.data, '.justsearch-isolated-models.json')), false);
});

test('S1: runner-owned paths replace inherited values; GPU/server and ordinary JVM flags remain allowed', () => {
  const data = path.join(root, 'owned-paths');
  const result = prepare(data, {
    JUSTSEARCH_HOME: '/ignored', JUSTSEARCH_DATA_DIR: '/ignored', JUSTSEARCH_MODELS_DIR: '/ignored',
    JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR: '/ignored', JUSTSEARCH_DEV_RUNNER_STATE_ROOT: root,
    JUSTSEARCH_CHAT_PROFILE: 'compact', JUSTSEARCH_GPU_ENABLED: 'true', JUSTSEARCH_SERVER_EXE: '/server',
    JUSTSEARCH_GH_BIN: '/tooling/gh',
    JAVA_OPTS: '-Xmx512m -XX:+UseG1GC',
  });
  assert.equal(result.env.JUSTSEARCH_MODELS_DIR, path.join(data, 'isolated-models'));
  assert.equal(result.env.JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR, path.join(data, 'isolated-ort-cache'));
});

test('S3: escaping private-folder and cache symlinks remain refused on restart', () => {
  const data = path.join(root, 'links');
  const { paths } = prepare(data);
  const shared = path.join(root, 'shared');
  fs.mkdirSync(shared);
  for (const folder of [paths.models, paths.optimizedCache]) {
    for (const target of [shared, path.join(shared, 'not-yet-created')]) {
      fs.symlinkSync(target, path.join(folder, 'escape'), 'junction');
      conflict(() => isolated.ensureDirectory(data, {}));
      fs.unlinkSync(path.join(folder, 'escape'));
    }
  }
  assert.deepEqual(fs.readdirSync(shared), []);
});

test('S3: JVM options files are refused before launch (reviewer-s6c)', () => {
  for (const [name, value] of [['JAVA_OPTS', '-XX:VMOptionsFile=/tmp/options.txt'], ['JDK_JAVA_OPTIONS', '-XX:Flags=/tmp/.hotspotrc'],
    ['JAVA_TOOL_OPTIONS', '-Xmx1g -XX:VMOptionsFile=/tmp/o.txt']]) {
    conflict(() => isolated.validateOverrides(path.join(root, `optfile-${name}`), { [name]: value }));
  }
});

test('S3: data and runner-state folders overlapping the shared models or graph cache are refused (reviewer-s6c)', () => {
  const sharedModels = path.join(repo, 'models');
  conflict(() => isolated.validateOverrides(path.join(sharedModels, 'install-check'), {}));
  conflict(() => isolated.validateOverrides(repo, {}));
  conflict(() => isolated.validateOverrides(path.join(root, 'fresh-state'), { JUSTSEARCH_DEV_RUNNER_STATE_ROOT: path.join(sharedModels, 'state') }));
  const viaLink = path.join(root, 'link-to-models');
  fs.symlinkSync(sharedModels, viaLink, process.platform === 'win32' ? 'junction' : 'dir');
  conflict(() => isolated.validateOverrides(path.join(viaLink, 'check'), {}));
  const platformHome = process.platform === 'win32'
    ? path.join(process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local'), 'JustSearch')
    : process.platform === 'darwin' ? path.join(os.homedir(), 'Library', 'Application Support', 'JustSearch') : path.join(os.homedir(), '.justsearch');
  const cache = path.join(platformHome, 'cache', 'ort-optimized');
  conflict(() => isolated.validateOverrides(path.join(cache, 'check'), {}));
  assert.doesNotThrow(() => isolated.validateOverrides(path.join(root, 'fresh-ok'), {}));
});

test('S3: CI\'s JUSTSEARCH_PR_CI is allowed, but does not admit other inherited variables', () => {
  for (const value of ['true', 'false']) {
    assert.doesNotThrow(() => isolated.validateOverrides(path.join(root, `pr-ci-${value}`), { JUSTSEARCH_PR_CI: value }));
  }
  conflict(() => isolated.validateOverrides(path.join(root, 'pr-ci-conflict'), { JUSTSEARCH_PR_CI: 'true', JUSTSEARCH_REPO_ROOT: repo }));
});

let failed = 0;
try {
  for (const [name, run] of tests) {
    if (process.env.ISOLATED_MODELS_TEST_FILTER && !name.includes(process.env.ISOLATED_MODELS_TEST_FILTER)) continue;
    try { await run(); console.log(`PASS ${name}`); }
    catch (error) { failed++; console.error(`FAIL ${name}: ${error.stack}`); }
  }
} finally { fs.rmSync(root, { recursive: true, force: true }); }
console.log(`isolated-models refusals: ${failed} failed`);
process.exitCode = failed ? 1 : 0;
