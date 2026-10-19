import './lib/test-fixture-processes.cjs';
// Synthetic boundary checks plus CPU-only CLI/census fixtures with spawn-recorded ownership.
// Identity-only stops, as on the integration side since 58a51a608: no tree inspection exists, so
// a stop's proof is the recheck of each recorded identity, never an enumeration of descendants.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const repo = path.resolve(import.meta.dirname, '../..');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'stop-round3-'));
process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = root;
const api = require('./dev-runner.cjs').__test;
const tests = [];
const test = (name, fn) => tests.push([name, fn, false]);
const windowsTest = (name, fn) => tests.push([name, fn, true]);
const windows = process.platform === 'win32';
const pid = 987654, birth = '134000000000000000';
const owner = api.holderBlockFor(api.resolveCallerIdentity('owner'));
const read = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const identityExe = process.platform === 'win32' ? require('./lib/test-fixture-processes.cjs').identityExecutable() : null;
function fixture(id, extra = {}) {
  const run = { runId: id, holder: owner, pids: { frontendRootPid: pid },
    identities: { frontend: { pid, creationFileTimeUtc: birth } }, ...extra };
  const dir = path.join(root, 'runs', id); fs.mkdirSync(dir, { recursive: true });
  const runPath = path.join(dir, 'run.json'); fs.writeFileSync(runPath, JSON.stringify(run));
  fs.writeFileSync(path.join(root, 'active.json'), JSON.stringify({ runId: id, holder: owner, runPath }));
  return { run, runPath, dir };
}
const gone = ids => new Map(ids.map(p => [p, { present: false }]));
const io = target => ({ resolveTarget: async () => target,
  census: async () => ({ finish: async () => ({}) }),
  lookupIdentities: async ids => gone(ids), portOwner: async () => ({ closed: true }), portWaitMs: 0,
  gracefulShutdown: async () => ({ outcome: 'exited' }),
  terminateProcess: () => ({ ok: false, outcome: 'failed' }), terminateEngine: () => ({ ok: false, outcome: 'failed' }) });
for (const failure of [
  { ok: false, outcome: 'partial', enumerationLimitReached: true,
    processes: [{ pid: pid + 1, creationFileTimeUtc: birth, owned: true }] },
  { ok: false, outcome: 'failed', reason: 'tree enumeration failed: injected census error',
    processes: [{ pid: pid + 1, creationFileTimeUtc: birth, owned: true }] },
]) test(`a listed identity from a legacy ${failure.outcome} tree record keeps the stop incomplete while it lives, and is never killed`, async () => {
  const target = fixture('listed-' + failure.outcome, { stopUnresolvedTrees: [{ role: 'frontend', ...failure }] });
  const actions = [];
  const out = await api.stopRun({}, { ...io(target),
    lookupIdentities: async ids => new Map(ids.map(p => [p, p === pid + 1 ? { present: true, creationFileTime: birth } : { present: false }])),
    terminateProcess: (...args) => { actions.push(args); return { ok: false, outcome: 'failed' }; } });
  assert.equal(out.outcome, 'STOP_INCOMPLETE', 'gone roots and closed ports cannot erase a living recorded identity');
  assert.deepEqual(actions.filter(a => a[0] === pid + 1), [], 'an identity found through parent links is reported, never killed');
});
for (const outcome of ['partial', 'timeout', 'runner-tree-incomplete']) test(`an anonymous legacy ${outcome} tree record is retired once its recorded roots are gone`, async () => {
  // No enumeration can re-prove it; children are covered by their creator's ordered stop and by the
  // Engine's manifest and exit ledger, so only recorded identities decide the outcome.
  const target = fixture('anonymous-' + outcome, { stopUnresolvedTrees: [{ role: 'frontend', ok: false, outcome, processes: [] }] });
  const out = await api.stopRun({}, io(target));
  assert.equal(out.outcome, 'STOPPED', JSON.stringify(out));
  assert.equal(read(target.runPath).stopUnresolvedTrees, undefined, 'the tree-era record is retired');
});
test('a pre-stop identity lookup exception remains unknown', async () => {
  const target = fixture('lookup-exception');
  const fail = () => { throw new Error('injected lookup exception'); };
  let out;
  await assert.doesNotReject(async () => { out = await api.stopRun({}, { ...io(target), lookupIdentities: fail }); },
    'lookup exceptions must be reported as unknown rather than escape stop accounting');
  assert.equal(out.outcome, 'STOP_INCOMPLETE', 'lookup failure must be visible and retained');
});
test('forget refuses a healthy run', async () => {
  const target = fixture('healthy-forget');
  const out = await api.stopRun({ forgetUnresolved: true, sessionId: 'owner' }, io(target));
  assert.equal(out.ok, false, 'forget requires a previous STOP_INCOMPLETE');
  assert.equal(fs.existsSync(path.join(target.dir, 'forgotten-unresolved.json')), false);
});
test('forget refuses a non-owner even with force', async () => {
  const target = fixture('foreign-forget', { stopFailed: true });
  fs.writeFileSync(path.join(target.dir, 'stop-report.json'), JSON.stringify({ outcome: 'STOP_INCOMPLETE' }));
  const out = await api.stopRun({ forgetUnresolved: true, force: true, sessionId: 'foreign' }, io(target));
  assert.equal(out.ok, false, 'force never transfers authority to forget');
  assert.equal(fs.existsSync(path.join(target.dir, 'forgotten-unresolved.json')), false);
});
test('a repeated forget cannot erase the barrier for living forgotten identities', async () => {
  const target = fixture('forget-again', { pids: {}, identities: {} });
  const leftBehind = [{ pid, role: 'backend', name: 'Engine.exe', creationFileTimeUtc: birth }];
  const marker = path.join(target.dir, 'forgotten-unresolved.json');
  fs.writeFileSync(marker, JSON.stringify({ leftBehind }));
  fs.writeFileSync(path.join(target.dir, 'stop-report.json'), JSON.stringify({ outcome: 'STOP_INCOMPLETE' }));
  await api.stopRun({ forgetUnresolved: true, sessionId: 'owner' }, io(target));
  assert.deepEqual(read(marker).leftBehind, leftBehind, 'repeating forget must never remove the start/cleanup barrier');
});

test('forget keeps the start barrier while its identity query is pending', async () => {
  const target = fixture('forget-pending');
  fs.writeFileSync(path.join(target.dir, 'stop-report.json'), JSON.stringify({ outcome: 'STOP_INCOMPLETE' }));
  let release, queried;
  const pending = new Promise(r => { release = r; }), began = new Promise(r => { queried = r; });
  const stop = api.stopRun({ forgetUnresolved: true, sessionId: 'owner' }, { ...io(target),
    lookupIdentities: async ids => { queried(); await pending; return gone(ids); } });
  try {
    await began;
    const marker = read(path.join(target.dir, 'forgotten-unresolved.json'));
    assert.ok(marker.leftBehind.some(p => p.pid === pid), 'an in-flight forget must not publish an empty start barrier');
  } finally { release(); await stop; }
});
test('start refuses living forgotten runner and Engine before publishing or cleaning', async () => {
  const target = fixture('forgotten-live');
  const leftBehind = [
    { pid, role: 'runner', name: 'runner.exe', creationFileTimeUtc: birth },
    { pid: pid + 1, role: 'backend', name: 'Engine.exe', creationFileTimeUtc: birth },
  ];
  fs.writeFileSync(path.join(target.dir, 'forgotten-unresolved.json'), JSON.stringify({ leftBehind }));
  let publishes = 0, stops = 0;
  const out = await api.acquireAdmission({ sessionId: 'owner', publish: () => { publishes++; },
    stop: () => { stops++; }, lookupIdentities: async ids => new Map(ids.map(p => [p, { present: true, creationFileTime: birth }])) });
  assert.equal(out.action, 'conflict', 'default start cannot soft-clean or launch over forgotten live processes');
  assert.match(JSON.stringify(out), /runner.exe/); assert.match(JSON.stringify(out), /Engine.exe/);
  assert.equal(publishes, 0); assert.equal(stops, 0);
});

windowsTest('real default CLI start refuses forgotten live processes without cleaning, even with takeover force', async () => {
  const children = [];
  fs.mkdirSync(path.join(repo, 'tmp'), { recursive: true });
  const data = fs.mkdtempSync(path.join(repo, 'tmp', 'round3-forgotten-data-'));
  const state = path.join(root, 'forgotten-cli');
  const runId = 'forgotten-live-cli', dir = path.join(state, 'runs', runId);
  fs.mkdirSync(dir, { recursive: true });
  const sentinel = path.join(data, 'derived-still-in-use'); fs.writeFileSync(sentinel, 'preserve');
  // The run's own data folder, as the start that created it marked it: the refusal under test is
  // the forgotten processes, not a folder no dev tool owns.
  fs.writeFileSync(path.join(data, '.justsearch-dev-data.json'), JSON.stringify({ schema: 'justsearch-dev-data.v1', tool: 'dev-runner', createdAt: new Date().toISOString() }));
  function launch(args, env = process.env) {
    const child = spawn(process.execPath, args, { cwd: repo, env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    const native = child.recordedIdentity;
    const birth = windows ? spawnSync(identityExe, [String(child.pid)], { encoding: 'utf8', windowsHide: true }).stdout.trim() : null;
    if (windows) assert.match(birth, /^\d+$/, 'fixture birth is recorded at spawn');
    else assert.equal(native.processIdentity.kind, 'linux-proc-v1');
    children.push({ child, birth }); return { child, birth, native };
  }
  try {
    const runner = launch(['-e', 'setInterval(()=>{},60000)']);
    const engine = launch(['-e', 'setInterval(()=>{},60000)']);
    const leftBehind = [{ pid: runner.child.pid, role: 'runner', name: 'runner.exe', creationFileTimeUtc: runner.birth, ...(runner.native?.processIdentity ? { processIdentity: runner.native.processIdentity } : {}) },
      { pid: engine.child.pid, role: 'backend', name: 'Engine.exe', creationFileTimeUtc: engine.birth, ...(engine.native?.processIdentity ? { processIdentity: engine.native.processIdentity } : {}) }];
    fs.writeFileSync(path.join(dir, 'run.json'), JSON.stringify({ runId, holder: owner, pids: {}, identities: {}, dataDir: data }));
    fs.writeFileSync(path.join(dir, 'forgotten-unresolved.json'), JSON.stringify({ leftBehind }));
    const env = { ...process.env, JUSTSEARCH_DEV_RUNNER_STATE_ROOT: state, JUSTSEARCH_SUPERVISOR_HARNESS: '1',
      JUSTSEARCH_DEV_MCP_LOG_NDJSON: '0', JUSTSEARCH_FAKE_ENGINE_PLAN: '', CI: '',
      JUSTSEARCH_DEV_RUNNER_ENGINE_COMMAND: JSON.stringify([process.execPath, path.join(repo, 'scripts/supervisor-conformance/fake-engine.mjs'), '--mode', 'boot-fail']),
      JUSTSEARCH_DEV_RUNNER_FRONTEND_COMMAND: JSON.stringify([process.execPath, '-e', 'setInterval(()=>{},60000)']) };
    const start = launch([path.join(repo, 'scripts/dev/dev-runner.cjs'), 'start', '--json', '--skip-build',
      '--api-port', '0', '--ui-port', '5598', '--data-dir', data, '--session-id', 'owner', '--takeover', 'force'], env);
    let output = ''; start.child.stdout.on('data', b => { output += b; }); start.child.stderr.on('data', b => { output += b; });
    const end = Date.now() + 45000;
    while (start.child.exitCode === null && Date.now() < end) await new Promise(r => setTimeout(r, 20));
    assert.notEqual(start.child.exitCode, null, 'CLI refusal returns without leaving a runner');
    assert.match(output, /Forgotten processes must be ended or inspected first/, output);
    assert.match(output, new RegExp(`PID ${runner.child.pid}.*runner.exe`));
    assert.match(output, new RegExp(`PID ${engine.child.pid}.*Engine.exe`));
    assert.equal(fs.readFileSync(sentinel, 'utf8'), 'preserve', 'default soft clean must never run');
    assert.equal(runner.child.exitCode, null); assert.equal(engine.child.exitCode, null);
  } finally {
    // These stand-ins have no children; boot-fail exits before frontend launch. The preload
    // checks the birth recorded at spawn and ends the same handle, without a PowerShell timeout.
    for (const { child } of children) if (child.exitCode === null) child.kill();
    // Private fixture has no links; the preload also unlinks links before any recursive removal.
    fs.rmSync(data, { recursive: true, force: true });
  }
});

test('real cleanup of a forgotten run refuses clearly and preserves its data', () => {
  fs.mkdirSync(path.join(repo, 'tmp'), { recursive: true });
  const data = fs.mkdtempSync(path.join(repo, 'tmp', 'round3-forgotten-cleanup-'));
  const sentinel = path.join(data, 'derived-still-in-use'); fs.writeFileSync(sentinel, 'preserve');
  const target = fixture('forgotten-cleanup', { pids: {}, identities: {}, dataDir: data });
  const child = spawn(process.execPath, ['-e', 'setInterval(()=>{},60000)'], { stdio: 'ignore', windowsHide: true });
  fs.writeFileSync(path.join(target.dir, 'forgotten-unresolved.json'), JSON.stringify({ leftBehind: [{ pid: child.pid, ...child.recordedIdentity, name: 'Engine.exe' }] }));
  try {
    const result = spawnSync(process.execPath, [path.join(repo, 'scripts/dev/dev-runner.cjs'), 'cleanup', '--json',
      '--run', target.run.runId, '--session-id', 'owner', '--clean', 'hard'], { cwd: repo, env: process.env, encoding: 'utf8', windowsHide: true });
    const out = JSON.parse(result.stdout.trim().split(/\r?\n/).at(-1));
    assert.equal(out.error?.code, 'STOP_INCOMPLETE', 'cleanup must explain the forgotten barrier, not throw a TypeError');
    assert.equal(out.cleaned, false); assert.match(out.error.message, /leaves processes running; a person decides/i);
    assert.equal(fs.readFileSync(sentinel, 'utf8'), 'preserve');
  } finally { child.kill(); fs.rmSync(data, { recursive: true, force: true }); }
});
test('MCP incomplete stop delegates forgetting to a person and never supplies force', async () => {
  const target = fixture('mcp-stop');
  const result = await api.stopRun({}, { ...io(target), lookupIdentities: async ids => new Map(ids.map(p =>
    [p, { present: true, creationFileTime: birth, name: 'frontend.exe' }])) });
  const source = fs.readFileSync(path.join(repo, 'scripts/dev/justsearch-dev-mcp/server.mjs'), 'utf8');
  const from = source.indexOf("  mcpServer.registerTool(\n    'justsearch.dev.stop',");
  const to = source.indexOf('  // ---------------------------------------------------------------------------', from);
  let handler;
  const ctx = { mcpServer: { registerTool: (_name, _options, fn) => { handler = fn; } },
    StopInputSchema: { parse: x => x }, DevRunnerStopJsonSchema: { parse: x => x },
    mainRepoRoot: repo, repoRoot: repo, devRunnerPath: '', resolveRunId: async () => target.run.runId,
    callerIdentityFor: async () => ({}), devRunnerEnvFor: () => ({}), buildDevRunnerArgsStop: () => [],
    maybeAppendNdjson: () => {}, runCliJson: async () => ({ exitCode: 1, json: { ...result, error: { code: 'STOP_INCOMPLETE' } } }),
    coerceExitAwareOk: x => ({ ...x }), toToolResult: x => x };
  vm.runInNewContext(source.slice(from, to), ctx);
  const out = await handler({});
  const text = JSON.stringify(out);
  assert.match(text, /a person decides/i, 'agent relay must not offer autonomous forgetting');
  assert.doesNotMatch(text, /--force[^"\n]*--forget-unresolved/);
  assert.match(text, /leaves processes running/i);
});
test('stop batches identity lookups per phase', async () => {
  const target = fixture('batch-stop', { pids: { runnerPid: pid, backendRootPid: pid + 1, frontendRootPid: pid + 2 },
    identities: { runner: { pid, creationFileTimeUtc: birth }, backend: { pid: pid + 1, creationFileTimeUtc: birth }, frontend: { pid: pid + 2, creationFileTimeUtc: birth } },
    stopTrackedProcesses: Array.from({ length: 8 }, (_, i) => ({ pid: pid + 3 + i, creationFileTimeUtc: birth, role: 'helper' })) });
  let lookups = 0;
  const out = await api.stopRun({}, { ...io(target), lookupIdentities: async ids => { lookups++; return gone(ids); } });
  assert.equal(out.outcome, 'STOPPED');
  assert.ok(lookups <= 3, `one lookup per stop phase, saw ${lookups}`);
});
test('birthless descendant gets a stable first-observed bound and resolves later reuse', async () => {
  const target = fixture('descendant-bound', { stopTrackedProcesses: [{ pid: pid + 1, role: 'descendant', creationFileTimeUtc: null }] });
  let reused = false;
  const lookup = async ids => new Map(ids.map(p => [p, p === pid + 1
    ? { present: true, creationFileTime: reused ? String(BigInt(Date.now() + 60000) * 10000n + 116444736000000000n) : null }
    : { present: false }]));
  assert.equal((await api.stopRun({}, { ...io(target), lookupIdentities: lookup })).outcome, 'STOP_INCOMPLETE');
  reused = true;
  assert.equal((await api.stopRun({}, { ...io(target), resolveTarget: async () => ({ ...target, run: read(target.runPath) }), lookupIdentities: lookup })).outcome,
    'STOPPED', 'a PID born after the descendant was observed is foreign');
});
windowsTest('real Windows identity lookup reads two spawn-recorded roots in one invocation', async () => {
  const actuator = require('./lib/engine-actuator.cjs');
  const children = [], roots = [];
  try {
    for (let i = 0; i < 2; i++) {
      const child = spawn(process.execPath, ['-e', 'setTimeout(()=>{},60000)'], { stdio: 'ignore', windowsHide: true });
      children.push(child);
      if (windows) {
        const birth = spawnSync(identityExe, [String(child.pid)], { encoding: 'utf8', windowsHide: true }).stdout.trim();
        assert.match(birth, /^\d+$/, 'fixture birth is recorded at spawn');
        roots.push({ pid: child.pid, creationFileTimeUtc: birth });
      } else roots.push(child.recordedIdentity);
    }
    let invocations = 0;
    const { execFile } = require('node:child_process');
    const exec = (...args) => { invocations++; return execFile(...args); };
    const rows = await actuator.lookupProcessIdentities(roots.map(p => p.pid), { exec });
    for (const p of roots) {
      const row = rows?.get(p.pid);
      assert.equal(row?.present, true, 'a real lookup sees both living roots');
      if (windows) assert.equal(BigInt(row.creationFileTime) / 10n, BigInt(p.creationFileTimeUtc) / 10n, 'each recorded birth matches at the rule\'s microsecond');
      else assert.deepEqual(row.processIdentity, p.processIdentity, 'the full Linux birth tuple agrees');
    }
    assert.equal(invocations, windows ? 1 : 0, 'Windows uses one PowerShell process; Linux reads the requested native tuples directly');
  } finally {
    // ChildProcess.kill is guarded by the birth recorded at spawn, through the same native handle.
    for (const child of children) child.kill();
  }
});
let failed = 0, skipped = 0;
try { for (const [name, fn, requiresWindows] of tests) {
  if (requiresWindows && !windows && process.platform !== 'linux') {
    skipped++; console.log('SKIP ' + name + ': requires native Windows process creation identities'); continue;
  }
  try { await fn(); console.log('PASS ' + name); }
  catch (e) { failed++; console.error('FAIL ' + name + ': ' + e.stack); } } }
finally { fs.rmSync(root, { recursive: true, force: true }); }
console.log(`stop-round3: ${tests.length - failed - skipped}/${tests.length - skipped} passed; ${skipped} skipped`);
process.exitCode = failed ? 1 : 0;
