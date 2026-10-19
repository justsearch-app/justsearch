// Pure Windows startup-boundary replay: no native process, timer, or dev stack is started.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { EventEmitter } from 'node:events';
import { createRequire } from 'node:module';
import { test } from 'node:test';

const runnerFile = path.join(import.meta.dirname, 'dev-runner.cjs');
// An explicit source file permits the same behavioral checks against the declared before state.
const source = fs.readFileSync(process.argv[2] ?? runnerFile, 'utf8');
const require = createRequire(import.meta.url);
const host = { pid: 41, creationFileTimeUtc: '134000000000000001' };
const root = { pid: 42, creationFileTimeUtc: '134000000000000002' };
const record = { ...host, root };

function fixture({ publishAt = 0, captureMs = 0, capturedHost = host, publishedRecord = record, reportError = null, realFs = false,
  cleanup = { ok: true, outcome: 'terminated' } } = {}) {
  let now = 0;
  const calls = [], reports = [];
  const run = { child: { pid: host.pid }, recordPath: path.join(import.meta.dirname, 'replayed-engine.json'), host: null };
  const facade = { pid: root.pid, rootIdentity: root, jobRun: run };
  const job = {
    startInJob(options) {
      calls.push(['start', options]);
      run.host = options.capture ? options.capture(run.child) : capturedHost;
      return run;
    },
    waitForRootSync(actual, options = {}) {
      assert.equal(actual, run);
      const timeoutMs = options.timeoutMs ?? 20000;
      calls.push(['wait', timeoutMs]);
      const published = publishAt <= now + timeoutMs;
      now = published ? Math.max(now, publishAt) : now + timeoutMs;
      return published ? publishedRecord : null;
    },
    readRunRecord: () => null,
    stopJobRunSync(actual) {
      assert.equal(actual, run);
      calls.push(['stop', actual.host]);
      // The native boundary refuses to terminate anything without the host's recorded birth.
      return actual.host ? cleanup : { ok: false, outcome: 'unknown-identity' };
    },
    rootAsChild(actual, published) {
      calls.push(['facade', published]);
      assert.equal(actual, run);
      return published ? facade : { pid: undefined, jobRun: run };
    },
  };
  const module = { exports: {} };
  const localRequire = createRequire(runnerFile);
  vm.runInNewContext(source + '\nmodule.exports.spawnEngineInJob = spawnEngineInJob;', {
    require(name) {
      if (name === './lib/job-run.cjs') return job;
      if (name === './lib/engine-actuator.cjs') return { ...localRequire(name), captureSpawnedIdentity(_child, options) {
        calls.push(['capture', options.timeoutMs]);
        now += Math.min(captureMs, options.timeoutMs);
        return captureMs <= options.timeoutMs ? capturedHost : null;
      } };
      if (name === 'fs' && !realFs) return { ...fs, writeFileSync() { assert.fail('the startup boundary must retain ownership in the existing run record'); } };
      return localRequire(name);
    },
    module, exports: module.exports, __dirname: path.dirname(runnerFile), __filename: runnerFile,
    process: { ...process, platform: 'win32' }, performance: { now: () => now },
    console, Buffer, URL, setTimeout, clearTimeout, setInterval, clearInterval,
  }, { filename: runnerFile });
  return { calls, reports, run, facade, api: module.exports.__test, now: () => now,
    start: (...values) => module.exports.spawnEngineInJob('engine', [], { cwd: import.meta.dirname },
      null, null, () => {}, { recordDir: import.meta.dirname, label: 'replay', startupTimeoutMs: values.length ? values[0] : 90000,
        onStartupFailure(details) {
          if (reportError) throw reportError;
          reports.push(details);
        } }) };
}

test('a cold Windows job host can publish after 40s inside the shared 90s startup budget', () => {
  const f = fixture({ publishAt: 40000, captureMs: 5000 });
  assert.equal(f.start(), f.facade, 'discovery must receive the actual Engine root');
  assert.equal(f.calls.find(([name]) => name === 'wait')[1], 85000, 'the capture cost consumes the startup budget');
  assert.equal(f.now(), 40000);
  assert.equal(f.run.host.pid, host.pid);
  assert.equal(f.run.host.creationFileTimeUtc, host.creationFileTimeUtc);
  assert.equal(f.calls.some(([name]) => name === 'stop'), false);
});

test('an immediate known root keeps its PID, birth, and host ownership', () => {
  const f = fixture();
  const child = f.start();
  assert.equal(child.pid, root.pid);
  assert.equal(child.rootIdentity.creationFileTimeUtc, root.creationFileTimeUtc);
  assert.equal(child.jobRun.host.creationFileTimeUtc, host.creationFileTimeUtc);
  assert.equal(f.calls.some(([name]) => name === 'stop'), false);
  assert.equal(f.reports.length, 0);
});

test('a missing root stops only the captured host identity, reports failure, and creates no facade', () => {
  const f = fixture({ publishAt: Infinity });
  assert.throws(() => f.start(), error => {
    assert.equal(error.code, 'ENGINE_ROOT_UNPUBLISHED');
    assert.equal(error.details.cleanup.ok, true);
    assert.equal(error.details.host.pid, host.pid);
    assert.equal(error.details.host.creationFileTimeUtc, host.creationFileTimeUtc);
    assert.equal(error.details.recordPath, f.run.recordPath);
    return true;
  });
  assert.equal(f.calls.filter(([name]) => name === 'stop').length, 1);
  assert.equal(f.calls.some(([name]) => name === 'facade'), false, 'never supervise or ledger pid 0');
  assert.equal(f.reports[0].recordPath, f.run.recordPath);
  assert.equal(f.reports[0].cleanup.outcome, 'terminated');
});

test('an unreadable host birth grants no kill authority and retains the unresolved startup context', () => {
  const f = fixture({ publishAt: Infinity, capturedHost: null });
  assert.throws(() => f.start(), error => {
    assert.equal(error.code, 'ENGINE_ROOT_UNPUBLISHED');
    assert.equal(error.details.cleanup.ok, false);
    assert.equal(error.details.cleanup.outcome, 'unknown-identity');
    assert.equal(error.details.host, null);
    assert.equal(error.details.hostPid, host.pid);
    assert.equal(error.details.recordPath, f.run.recordPath);
    return true;
  });
  assert.equal(f.calls.some(([name]) => name === 'facade'), false);
  assert.equal(f.reports[0].cleanup.outcome, 'unknown-identity');
  assert.equal(f.reports[0].host, null);
});

test('an exhausted capture budget does not grant another root-publication wait', () => {
  const f = fixture({ publishAt: Infinity, captureMs: 5000 });
  assert.throws(() => f.start(1000), { code: 'ENGINE_ROOT_UNPUBLISHED' });
  assert.equal(f.calls.find(([name]) => name === 'capture')[1], 1000);
  assert.equal(f.calls.find(([name]) => name === 'wait')[1], 0);
});

test('a root already published at capture expiry is refused after the startup deadline', () => {
  const f = fixture({ publishAt: 1000, captureMs: 1000 });
  assert.throws(() => f.start(1000), { code: 'ENGINE_ROOT_UNPUBLISHED' });
  assert.equal(f.calls.some(([name]) => name === 'facade'), false);
  assert.equal(f.calls.filter(([name]) => name === 'stop').length, 1);
});

test('a conflicting record cannot replace the captured host birth or authorize its root', () => {
  const f = fixture({ publishedRecord: { ...record, creationFileTimeUtc: '134000000000000099' } });
  assert.throws(() => f.start(), error => {
    assert.equal(error.code, 'ENGINE_ROOT_UNPUBLISHED');
    assert.equal(error.details.host.creationFileTimeUtc, host.creationFileTimeUtc);
    return true;
  });
  assert.equal(f.calls.some(([name]) => name === 'facade'), false);
  assert.equal(f.calls.find(([name]) => name === 'stop')[1].creationFileTimeUtc, host.creationFileTimeUtc);
});

test('an unwritable current run preserves the classified failure and cleanup context', () => {
  const f = fixture({ publishAt: Infinity, reportError: new Error('fixture disk refusal') });
  assert.throws(() => f.start(), error => {
    assert.equal(error.code, 'ENGINE_ROOT_UNPUBLISHED');
    assert.equal(error.details.cleanup.ok, true);
    assert.equal(error.details.failureRecordError, 'fixture disk refusal');
    return true;
  });
});

test('an invalid budget is refused before any native host is started', () => {
  for (const value of [0, -1, NaN, Infinity, '90000']) {
    const f = fixture();
    assert.throws(() => f.start(value), /startup budget must be positive and finite/);
    assert.equal(f.calls.length, 0);
  }
});

test('a direct caller without a configured budget retains the bounded legacy wait', () => {
  const f = fixture({ publishAt: Infinity });
  try { f.start(undefined); }
  catch (error) { assert.equal(error.code, 'ENGINE_ROOT_UNPUBLISHED'); }
  assert.equal(f.calls.find(([name]) => name === 'wait')[1], 20000);
});

test('a later real stop keeps the persisted unreadable host incomplete even before its native record exists', async () => {
  const f = fixture({ realFs: true });
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'engine-start-stop-'));
  const runPath = path.join(dir, 'run.json');
  try {
    const run = { runId: 'missing-root', pids: {}, identities: {} };
    const failure = { recordPath: path.join(dir, 'not-published.json'), hostPid: host.pid, host: null,
      startupTimeoutMs: 90000, cleanup: { ok: false, outcome: 'unknown-identity' } };
    // The older runner can consume this existing run field too. Seed the same persisted boundary
    // when replaying that source, so its actual stop verdict fails for the missing-file inference.
    if (f.api.persistEngineStartupFailure) f.api.persistEngineStartupFailure(runPath, run, 1, failure);
    else fs.writeFileSync(runPath, JSON.stringify({ ...run, engineJob: { incarnation: 1, recordPath: failure.recordPath,
      host: { pid: host.pid, creationFileTimeUtc: null }, startupFailure: { code: 'ENGINE_ROOT_UNPUBLISHED',
        startupTimeoutMs: failure.startupTimeoutMs, cleanup: failure.cleanup } } }));
    const persisted = JSON.parse(fs.readFileSync(runPath, 'utf8'));
    assert.equal(persisted.engineJob.host.pid, host.pid);
    assert.equal(persisted.engineJob.host.creationFileTimeUtc, null);
    let attempted;
    const result = await f.api.stopRun({}, {
      resolveTarget: async () => ({ run: persisted, runPath }),
      census: async () => ({ finish: async () => ({}) }),
      lookupIdentities: async () => new Map(), portOwner: async () => ({ closed: true }), portWaitMs: 0,
      stopEngineJob(job) {
        attempted = job;
        return require('./lib/job-run.cjs').stopJobRunSync({ recordPath: job.recordPath, host: job.host },
          { exec() { assert.fail('a host without a birth must never reach native termination'); } });
      },
    });
    assert.equal(result.outcome, 'STOP_INCOMPLETE');
    assert.equal(attempted.host.pid, host.pid, 'the ordinary stop must account for the failed startup host');
    assert.ok(result.unresolvedProcesses.some(p => p.pid === host.pid && p.creationFileTimeUtc === null));
  } finally {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      assert.equal(entry.isDirectory(), false);
      fs.unlinkSync(path.join(dir, entry.name));
    }
    fs.rmdirSync(dir);
  }
});

test('the native identity query cannot outlive its allotted capture budget', () => {
  const { captureSpawnedIdentity } = require('./lib/engine-actuator.cjs');
  const child = { pid: host.pid, exitCode: null, signalCode: null };
  let options;
  const result = captureSpawnedIdentity(child, { platform: 'win32', timeoutMs: 123,
    exec(_command, _args, value) { options = value; return { status: 0, stdout: host.creationFileTimeUtc }; } });
  assert.equal(options.timeout, 123);
  assert.deepEqual(result, host);
  assert.equal(captureSpawnedIdentity(child, { platform: 'win32', timeoutMs: 0,
    exec() { assert.fail('an exhausted identity budget must not start another query'); } }), null);
});

test('root-publication waiting follows elapsed monotonic time through a backwards wall-clock jump', () => {
  const jobFile = path.join(import.meta.dirname, 'lib/job-run.cjs');
  const module = { exports: {} };
  let monotonic = 0, waits = 0;
  const localRequire = createRequire(jobFile);
  vm.runInNewContext(fs.readFileSync(process.argv[3] ?? jobFile, 'utf8'), {
    require(name) { return name === 'node:fs' ? { ...fs, readFileSync: () => { throw new Error('no root'); } } : localRequire(name); },
    module, exports: module.exports, __dirname: path.dirname(jobFile), __filename: jobFile,
    process: { ...process, kill: () => true }, performance: { now: () => monotonic },
    Date: { now: () => 100000 - monotonic },
    Atomics: { wait(_array, _index, _value, ms) { assert.ok(++waits <= 3, 'wall time must not extend the root wait'); monotonic += ms; } },
    console, Buffer, setTimeout, clearTimeout, setInterval, clearInterval,
  }, { filename: jobFile });
  assert.equal(module.exports.waitForRootSync({ recordPath: 'missing', child: { pid: host.pid } }, { timeoutMs: 20, intervalMs: 10 }), null);
  assert.equal(monotonic, 20);
  assert.equal(waits, 2);
});

test('the actuator forwards the same startup budget to its spawn boundary', async () => {
  const { createEngineActuator } = require('./lib/engine-actuator.cjs');
  let forwarded;
  const child = Object.assign(new EventEmitter(), { pid: root.pid, exitCode: null, signalCode: null });
  const actuator = createEngineActuator({ dataDir: path.join(import.meta.dirname, 'absent-startup-fixture'), runId: 'r',
    policy: {}, ledger: { append: () => true }, lookupIdentities: async () => new Map(), log: () => {},
    spawnChild(_n, _port, _env, startup) { forwarded = startup; return child; } });
  actuator.spawnEngine({ n: 1, cause: 'INITIAL', apiPort: 0, startupTimeoutMs: 90000 });
  assert.deepEqual(forwarded, { startupTimeoutMs: 90000 });
  await actuator.flush();
});

test('root publication consumes the supervisor deadline rather than resetting discovery time', async () => {
  const { createSupervisionLoop, loadPolicy } = require('./lib/engine-supervisor.cjs');
  let now = 0, forwarded;
  const timeout = new Error('observed startup deadline');
  const loop = createSupervisionLoop({
    policy: loadPolicy({ env: {} }), timeouts: { initialPortMs: 90000, initialReadyMs: 60000 },
    monotonic: () => now, wallClock: () => now, log: () => {}, publish: async () => {},
    actuator: {
      reconcile: async () => {}, spawnEngine(options) { forwarded = options.startupTimeoutMs; now += 40000; return { pid: root.pid }; },
      pollExit: () => null, exitObserved: () => false, waitOrWake: async ms => { now += ms; },
      recordIntent: async () => {}, writeShutdownRequest: () => { throw timeout; },
    },
    io: { clearStaleDiscoveryFiles() {}, readManifest: () => null, externalStopRequested: () => false },
  });
  await assert.rejects(loop.run(), error => error === timeout);
  assert.equal(now, 90000, '40s root publication leaves 50s for discovery, not another 90s');
  assert.equal(forwarded, 90000);
});
