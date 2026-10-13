#!/usr/bin/env node
// Deterministic interleavings through the production stop and spawn boundaries. These cases
// never stop an OS process; every termination/lookup boundary is injected.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { EventEmitter } from 'node:events';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dev-process-races-'));
process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = root;
const require = createRequire(import.meta.url);
const actuator = require('./lib/engine-actuator.cjs');
const { stopRun } = require('./dev-runner.cjs').__test;
const source = fs.readFileSync(path.join(here, 'dev-runner.cjs'), 'utf8');
const pid = 900101, birth = '134000000000000000';
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const child = () => Object.assign(new EventEmitter(), { pid, exitCode: null, signalCode: null });
const tests = [];
const test = (name, fn) => tests.push([name, fn]);

for (const rootOutcome of ['terminated', 'failed']) test(`partialFrontendTreeKeepsStopIncomplete (${rootOutcome} root)`, async () => {
  const runId = `run-partial-${rootOutcome}`;
  const dir = path.join(root, 'runs', runId);
  fs.mkdirSync(dir, { recursive: true });
  const runPath = path.join(dir, 'run.json');
  const activePath = path.join(root, 'active.json');
  let live = true;
  const run = { runId, pids: { frontendRootPid: pid }, identities: { frontend: { pid, creationFileTimeUtc: birth } } };
  fs.writeFileSync(runPath, JSON.stringify(run));
  fs.writeFileSync(activePath, JSON.stringify({ runId }));
  const io = {
    resolveTarget: async () => ({ run: JSON.parse(fs.readFileSync(runPath, 'utf8')), runPath }),
    census: async () => ({ finish: async () => ({}) }),
    gracefulShutdown: async () => ({ outcome: 'not_attempted' }),
    lookupIdentities: async pids => new Map(pids.map(p => [p, (live || p == 900109)
      ? { present: true, creationFileTime: birth } : { present: false }])),
    terminateProcess: async () => { live = false; return { ok: false, outcome: 'partial', status: 4,
      rootOutcome, descendantsEnded: [900105], descendantsNotEnded: [900109] }; },
    portOwner: async () => ({ closed: true }), portWaitMs: 0,
  };
  const first = await stopRun({ runId }, io);
  assert.equal(first.killedPids.includes(pid), rootOutcome === 'terminated', 'only a confirmed root termination is counted as killed');
  assert.equal(first.outcome, 'STOP_INCOMPLETE', 'a dead root and closed ports cannot settle an unresolved tree');
  assert.equal(JSON.parse(fs.readFileSync(activePath, 'utf8')).runId, runId);
  const report = JSON.parse(fs.readFileSync(path.join(dir, 'stop-report.json'), 'utf8'));
  assert.deepEqual(report.fallback.processes[0].termination.descendantsNotEnded, [900109]);
  assert.equal((await stopRun({ runId }, io)).outcome, 'STOP_INCOMPLETE', 'a later stop must retain the unresolved tree');
});

for (const fixture of ['own-stack-checkout', 'worktree-run-record', 'gate-integration']) {
  test(`${fixture} cleanup rechecks its recorded birth at termination`, () => {
    const text = fs.readFileSync(path.join(here, `test-dev-runner-${fixture}.mjs`), 'utf8');
    const cleanup = text.match(/for \(const p of sleepers\) \{[^\n]+/)?.[0];
    assert.ok(cleanup);
    const killed = [], calls = [];
    vm.runInNewContext(cleanup, { sleepers: [{ pid, recordedIdentity: { pid, creationFileTimeUtc: birth } }],
      process: { kill: value => killed.push(value) },
      require: () => ({ terminateWithCodeSync: (...args) => { calls.push(args); return { outcome: 'mismatch', ok: false }; } }),
    });
    assert.deepEqual(killed, [], 'fixture cleanup cannot spend a stale PID on a replacement');
    assert.equal(calls[0]?.[1], birth);
  });
}

test('stop-ownership frozen-runner cleanup keeps its recorded identity', () => {
  const text = fs.readFileSync(path.join(here, 'test-dev-runner-stop-ownership.mjs'), 'utf8');
  const cleanup = text.match(/if \(frozen[^\n]+/)?.[0];
  assert.ok(cleanup);
  const killed = [], calls = [];
  vm.runInNewContext(cleanup, { frozen: { pid, creationFileTimeUtc: birth }, alive: () => true,
    process: { kill: value => killed.push(value) },
    engineActuator: { terminateWithCodeSync: (...args) => { calls.push(args); return { ok: false, outcome: 'mismatch' }; } },
  });
  assert.deepEqual(killed, [], 'frozen-runner cleanup cannot terminate a replacement');
  assert.equal(calls[0]?.[1], birth);
});

test('missingBirthNeverEstablishesOwnershipForHttp', async () => {
  const runId = 'run-no-birth', dir = path.join(root, 'runs', runId);
  fs.mkdirSync(dir, { recursive: true });
  const runPath = path.join(dir, 'run.json');
  const run = { runId, apiPortActual: 23456, pids: { backendRootPid: pid } };
  fs.writeFileSync(runPath, JSON.stringify(run));
  let shutdowns = 0;
  const result = await stopRun({ runId }, {
    resolveTarget: async () => ({ run, runPath }), census: async () => ({ finish: async () => ({}) }),
    lookupIdentities: async () => new Map([[pid, { present: true, creationFileTime: birth }]]),
    gracefulShutdown: async () => { shutdowns++; return { outcome: 'not_attempted' }; },
    terminateEngine: async () => assert.fail('unknown process cannot be forced'),
    portOwner: async () => ({ closed: true }), portWaitMs: 0,
  });
  assert.equal(shutdowns, 0, 'unknown ownership forbids HTTP shutdown as well as a forced kill');
  assert.equal(result.outcome, 'STOP_INCOMPLETE');
});

test('fallback exit accounting names the identity terminator instead of taskkill', async () => {
  const runId = 'run-fallback-label', dir = path.join(root, 'runs', runId);
  fs.mkdirSync(dir, { recursive: true });
  const runPath = path.join(dir, 'run.json');
  const run = { runId, dataDir: dir, control: { stopRequest: 1 }, incarnation: 1,
    pids: { backendRootPid: pid }, identities: { backend: { pid, creationFileTimeUtc: birth } } };
  fs.writeFileSync(runPath, JSON.stringify(run));
  let live = true;
  const endings = [];
  await stopRun({ runId }, {
    resolveTarget: async () => ({ run, runPath }),
    census: async () => ({ finish: async ({ beforeVerdict }) => { beforeVerdict?.([]); return {}; } }),
    gracefulShutdown: async () => ({ outcome: 'not_attempted' }),
    lookupIdentities: async pids => new Map(pids.map(p => [p, { present: live, creationFileTime: birth }])),
    terminateEngine: async () => ({ ok: false, outcome: 'failed' }),
    terminateProcess: async () => { live = false; return { ok: true, outcome: 'terminated', rootOutcome: 'terminated' }; },
    cliEngineRecorder: () => ({ intent: () => ({}), exit: ending => { endings.push(ending); return {}; } }),
    portOwner: async () => ({ closed: true }), portWaitMs: 0,
  });
  assert.equal(endings[0]?.endedBy, 'identity-fallback');
});

for (const synchronous of [false, true]) test(`actuator retains root and descendant outcomes (${synchronous ? 'sync' : 'async'})`, async () => {
  const detail = { outcome: 'partial', rootOutcome: 'terminated', descendantsEnded: [12],
    descendantsNotEnded: [16], enumerationLimitReached: true };
  const exec = synchronous ? () => ({ status: 4, stdout: JSON.stringify(detail) })
    : (_cmd, _args, _opts, done) => done({ code: 4 }, JSON.stringify(detail));
  const result = await actuator[synchronous ? 'terminateWithCodeSync' : 'terminateWithCode'](pid, birth, 1, { tree: true, exec });
  assert.equal(result.ok, false);
  for (const key of Object.keys(detail)) assert.deepEqual(result[key], detail[key], key);
});

test('exit while engine birth lookup is pending keeps ownership unknown', async () => {
  const pending = deferred(), spawned = child(), records = [];
  const engine = actuator.createEngineActuator({ dataDir: root, runId: 'late', policy: {},
    spawnChild: () => spawned, lookupIdentities: () => pending.promise,
    ledger: { append: (type, fields) => { records.push({ type, ...fields }); return true; } } });
  const state = engine.spawnEngine({ n: 1 });
  spawned.emit('exit', 0);
  pending.resolve(new Map([[pid, { present: true, creationFileTime: birth }]]));
  await engine.flush();
  assert.equal(state.identity.creationFileTime, null, 'a lookup after exit can observe a replacement');
  assert.equal(records.find(r => r.type === 'engine-start').creationFileTime, null);
});

test('old incarnation lookup cannot overwrite a newer incarnation with the same PID', async () => {
  const first = deferred(), second = deferred();
  const oldState = { n: 1, pid, exit: null, identityPromise: first.promise };
  const newState = { n: 2, pid, exit: null, identityPromise: second.promise };
  let current = oldState;
  const context = { runJson: null, startingRun: { pids: {} }, updateRunRecord: async () => {},
    actuator: { current: () => current } };
  const hook = source.match(/onSpawned: (async \(\{ n, pid \}\) => \{[^]*?\n      \}),/)?.[1];
  assert.ok(hook);
  const onSpawned = vm.runInNewContext(`(${hook})`, context);
  await onSpawned({ n: 1, pid });
  current = newState;
  await onSpawned({ n: 2, pid });
  second.resolve({ creationFileTime: '134000000000200000' });
  await new Promise(setImmediate);
  first.resolve({ creationFileTime: birth });
  await new Promise(setImmediate);
  assert.equal(context.startingRun.identities.backend.creationFileTimeUtc, '134000000000200000');
});

test('exit during frontend birth lookup never records a replacement', async () => {
  const pending = deferred(), frontend = child();
  const context = { frontend, frontendIdentity: null, engineActuator: {
    ...actuator, lookupProcessIdentities: () => pending.promise,
  } };
  const capture = source.match(/  frontendIdentity = frontend.pid[^]*?\n    : null;/)?.[0];
  assert.ok(capture, 'exercise production frontend capture');
  const promise = vm.runInNewContext(`(async () => { ${capture} })()`, context);
  frontend.exitCode = 0;
  frontend.emit('exit', 0);
  pending.resolve(new Map([[pid, { present: true, creationFileTime: birth }]]));
  await promise;
  assert.equal(context.frontendIdentity, null);
});

test('exit during ready census lookup never records a replacement', async () => {
  const pending = deferred(), state = { n: 1, pid, exit: null };
  const context = { runJson: { pids: {}, resourceClaims: {}, exitCensusEngine: null }, dataDir: root,
    apiPortActual: null, updateRunRecord: async () => {}, engineIdentityAsync: () => pending.promise,
    supervision: { snapshot: () => ({ incarnation: 1 }) }, actuator: { current: () => state } };
  const hook = source.match(/onReady: (async \(\{ n, pid, apiPort, instanceId \}\) => \{[^]*?\n      \}),/)?.[1];
  assert.ok(hook);
  const running = vm.runInNewContext(`(${hook})`, context)({ n: 1, pid, apiPort: 12345, instanceId: 'ready' });
  await new Promise(setImmediate);
  state.exit = { code: 0 };
  pending.resolve({ pid, creationFileTimeUtc: birth });
  await running;
  assert.equal(context.runJson.exitCensusEngine, null, 'the exit census must not observe a replacement as this Engine');
});

test('exit during initial census lookup never records a replacement', async () => {
  const pending = deferred(), state = { n: 1, pid, exit: null };
  const context = { backend: { pid }, dataDir: root, engineIdentityAsync: () => pending.promise,
    actuator: { current: () => state } };
  const prefix = source.match(/  const initialCensusState[^]*?(?=  runJson = \{)/)?.[0] ?? '';
  const expression = source.match(/  runJson = \{\n    exitCensusEngine: ([^]*?),\n    schemaVersion:/)?.[1];
  assert.ok(expression);
  const running = vm.runInNewContext(`(async () => { ${prefix}\nthis.census = ${expression}; })()`, context);
  state.exit = { code: 0 };
  pending.resolve({ pid, creationFileTimeUtc: birth });
  await running;
  assert.equal(context.census, null, 'the initial run record must not claim a replacement');
});

test('dev-all refuses a replacement at the termination boundary', async () => {
  const filename = path.resolve(here, '../../modules/ui-web/scripts/dev-all.cjs');
  const calls = [], ended = [], spawned = child();
  let script = fs.readFileSync(filename, 'utf8');
  // Do not start the dev stack: evaluate only its declarations and startup guards.
  script = script.slice(0, script.indexOf('process.on("SIGINT"'));
  const context = { require: id => id === 'process' ? { platform: 'win32', env: {} }
    : id === 'child_process' ? { spawn: () => { ended.push(pid); return child(); } }
      : id.includes('engine-actuator') ? { ...actuator, terminateWithCodeSync: (...args) => {
        calls.push(args); return { ok: false, outcome: 'mismatch' };
      } } : createRequire(filename)(id), __dirname: path.dirname(filename), console, setTimeout };
  vm.runInNewContext(`${script}\nthis.stop = terminateChild; this.setIdentity = (child, value) => {
    if (typeof childIdentities !== 'undefined') childIdentities.set(child, value);
  };`, context);
  context.setIdentity(spawned, { pid, creationFileTimeUtc: birth });
  await context.stop(spawned);
  assert.deepEqual(ended, [], 'dev-all must never delegate a stale PID to taskkill /T');
  assert.equal(calls[0]?.[1], birth, 'the recorded birth reaches the termination handle');
});

let failed = 0;
try {
  for (const [name, run] of tests) {
    try { await run(); console.log(`PASS ${name}`); }
    catch (error) { failed++; console.error(`FAIL ${name}: ${error.stack}`); }
  }
} finally {
  // Fixture contains only JSON files created above, no links or subprocesses.
  fs.rmSync(root, { recursive: true, force: true });
}
console.log(`process-races: ${tests.length - failed}/${tests.length} passed`);
process.exitCode = failed ? 1 : 0;
