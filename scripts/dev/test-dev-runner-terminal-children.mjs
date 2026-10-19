import './lib/test-fixture-processes.cjs';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import runner from './dev-runner.cjs';

const api = runner.__test;
function manifest(t) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../tmp');
  fs.mkdirSync(root, { recursive: true });
  const dir = fs.mkdtempSync(path.join(root, 'stop-orphan-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  fs.mkdirSync(path.join(dir, 'runtime'));
  const child = { id: 'llama-server', pid: 22004, executable: path.join(dir, 'llama-server.exe'),
    startedAt: '2026-10-03T14:00:00.123Z' };
  fs.writeFileSync(path.join(dir, 'runtime/manifest.json'), JSON.stringify({ children: [child] }));
  return { dir, child };
}

test('Native default inspector binds the real current PID (read only)',
  { skip: !['win32', 'linux'].includes(process.platform) }, () => {
    const result = api.inspectProcessIdentity(process.pid);
    assert.equal(result?.alive, true);
    if (process.platform === 'win32') {
      assert.equal(path.resolve(result.executable).toLowerCase(), process.execPath.toLowerCase());
      assert.ok(Number.isFinite(Date.parse(result.startedAt)));
    } else {
      assert.equal(result.executable, fs.realpathSync(process.execPath));
      const stat = fs.readFileSync('/proc/self/stat', 'utf8');
      assert.deepEqual(result.processIdentity, { kind: 'linux-proc-v1', pid: process.pid,
        bootId: fs.readFileSync('/proc/sys/kernel/random/boot_id', 'utf8').trim(), startTicks: stat.slice(stat.lastIndexOf(')') + 2).trim().split(/\s+/)[19] });
      assert.equal(result.startedAt, undefined, 'no invented wall-clock birth');
    }
  });

test('Native default inspector distinguishes a missing PID from a failed query (read only)',
  { skip: !['win32', 'linux'].includes(process.platform) }, () => {
    assert.deepEqual(api.inspectProcessIdentity(2147483647), { alive: false });
    assert.equal(api.inspectProcessIdentity('123;exit 0'), null);
  });

test('PowerShell failures remain unknown, including nonzero exit and malformed output', () => {
  for (const result of [{ status: 1 }, { error: new Error('timeout') },
    { status: 0, stdout: 'not json' }]) {
    assert.equal(api.inspectProcessIdentity(123, { platform: 'win32', spawnSync: () => result }), null);
  }
});

test('terminal cleanup cannot classify an inspection failure as dead', async t => {
  const { dir } = manifest(t);
  const outcomes = await api.cleanupRegisteredChildrenForTerminal(dir, () => null,
    () => assert.fail('cannot kill without identity'));
  assert.equal(outcomes[0].outcome, 'unknown-identity');
});

test('failed-drain terminal quit verifies llama exit and reports the killed PID', async t => {
  const { dir, child } = manifest(t);
  let alive = true;
  const killedPids = [], errors = [];
  const inspect = () => alive ? { ...child, alive } : { alive: false };
  const outcomes = await api.cleanupTerminalChildren(dir, killedPids, errors, inspect,
    pid => { assert.equal(pid, child.pid); alive = false; return true; });
  assert.equal(alive, false);
  assert.deepEqual(killedPids, [child.pid]);
  assert.deepEqual(errors, []);
  assert.equal(outcomes[0].outcome, 'terminated');
  const report = api.buildStopReport({ runId: 'failed-drain', killedPids, childCleanup: outcomes,
    gracefulBackendShutdown: { outcome: 'exited', exitCode: 1 } });
  assert.deepEqual(report.childCleanup, outcomes);
  assert.deepEqual(report.killedPids, [child.pid]);
});

test('restart and hang do not inspect or terminate registered llama children', async t => {
  const { dir } = manifest(t);
  for (const state of ['starting', 'running', 'stopping', 'restarting']) {
    assert.deepEqual(await api.cleanupRegisteredChildrenForSupervisorState(state, dir,
      () => assert.fail('preserved child must not be inspected'),
      () => assert.fail('preserved child must not be killed')), []);
  }
});

test('stopRun after failed Engine quit persists verified llama cleanup and returns it in JSON', async t => {
  const { dir, child } = manifest(t);
  let alive = true;
  const runPath = path.join(dir, 'run.json');
  const birth = '134000000000000000';
  let backendAlive = true;
  const run = { runId: path.basename(dir), dataDir: dir, apiPortActual: 0, uiPortActual: 0,
    pids: { runnerPid: process.pid, frontendRootPid: null, backendRootPid: 123 },
    identities: { backend: { pid: 123, creationFileTimeUtc: birth } } };
  let censusFinished = false;
  const result = await api.stopRun({}, {
    resolveTarget: async () => ({ run, runPath }),
    // The backend as it is, and the registered llama child as itself (its manifest start, to the
    // millisecond) while it runs: a row naming another birth at its PID would say it had ended.
    lookupIdentities: async pids => new Map(pids.map(pid => [pid, pid === child.pid
      ? { present: alive, creationFileTime: String(BigInt(Date.parse(child.startedAt)) * 10000n + 116444736000000000n) }
      : { present: backendAlive, creationFileTime: birth }])),
    gracefulShutdown: async () => { backendAlive = false; return { outcome: 'exited', exitCode: 1 }; },
    // Spec B step 9: the census reads the ledger; the stop's own kills are not handed to it.
    census: async () => ({ finish: async options => {
      assert.ok(!Array.isArray(options) && !JSON.stringify(options ?? {}).includes(String(child.pid)), JSON.stringify(options));
      censusFinished = true; return {};
    } }),
    inspectChild: () => alive ? { ...child, alive } : { alive: false },
    terminateChild: pid => { assert.equal(pid, child.pid); alive = false; return true; },
  });
  assert.equal(alive, false);
  assert.equal(censusFinished, true);
  assert.deepEqual(result.killedPids, [child.pid]);
  assert.equal(result.childCleanup[0].outcome, 'terminated');
  const report = JSON.parse(fs.readFileSync(path.join(dir, 'stop-report.json'), 'utf8'));
  assert.deepEqual(report.childCleanup, result.childCleanup);
  assert.deepEqual(report.killedPids, result.killedPids);
  assert.equal(report.gracefulBackendShutdown.exitCode, 1);
});

test('unknown inspection and survivors are reported instead of claiming termination', async t => {
  const { dir, child } = manifest(t);
  for (const [inspect, outcome, terminate] of [
    [() => null, 'unknown-identity', () => assert.fail('unknown identity cannot be killed')],
    [() => ({ ...child, alive: true }), 'termination-failed', () => true],
  ]) {
    const killedPids = [], errors = [];
    const outcomes = await api.cleanupTerminalChildren(dir, killedPids, errors, inspect, terminate,
      { timeoutMs: 0 });
    assert.equal(outcomes[0].outcome, outcome);
    assert.deepEqual(killedPids, []);
    assert.equal(errors.length, 1);
  }
});

for (const action of ['exhausted', 'stop']) {
  test(`discovery ${action} records matching, surviving and unknown children before publishing its report`, async t => {
    const { dir, child } = manifest(t);
    const children = [child, { ...child, id: 'survivor', pid: 22005 },
      { ...child, id: 'unknown', pid: 22006 }];
    fs.writeFileSync(path.join(dir, 'runtime/manifest.json'), JSON.stringify({ children }));
    const alive = new Set(children.map(child => child.pid));
    const kills = [];
    const report = await api.recordDiscoveryExit({ decision: { action }, dataDir: dir,
      runId: path.basename(dir), runPath: path.join(dir, 'run.json'), backendExitCode: 1, incarnation: 1 }, {
      inspect: pid => pid === 22006 ? null : { ...child, alive: alive.has(pid) },
      terminate: pid => { kills.push(pid); if (pid === child.pid) alive.delete(pid); return true; },
      cleanupOptions: { timeoutMs: 0 },
    });
    assert.deepEqual(report.killedPids, [child.pid]);
    assert.deepEqual(kills, [child.pid, 22005]);
    assert.deepEqual(report.childCleanup.map(child => child.outcome),
      ['terminated', 'termination-failed', 'unknown-identity']);
    assert.equal(report.errors.length, 2);
    for (const relative of ['stop-report.json', 'incarnations/1/stop-report.json']) {
      assert.deepEqual(JSON.parse(fs.readFileSync(path.join(dir, relative), 'utf8')), report);
    }
  });
}

test('discovery restart reports death while preserving registered children', async t => {
  const { dir } = manifest(t);
  const report = await api.recordDiscoveryExit({ decision: { action: 'restart' }, dataDir: dir,
    runId: path.basename(dir), runPath: path.join(dir, 'run.json'), backendExitCode: 2, incarnation: 1 }, {
    inspect: () => assert.fail('restart preserves registered children'),
    terminate: () => assert.fail('restart preserves registered children'),
  });
  assert.deepEqual(report.killedPids, []);
  assert.deepEqual(report.childCleanup, []);
  assert.deepEqual(report.errors, []);
});

test('Linux terminal cleanup terminates an identity-matched child after its binary is deleted; the stranger survives',
  { skip: process.platform !== 'linux' }, async t => {
    const { spawn } = await import('node:child_process');
    const { once } = await import('node:events');
    const { dir } = manifest(t);
    const executable = path.join(dir, 'owned-sleep');
    fs.copyFileSync('/bin/sleep', executable);
    const child = spawn(executable, ['30'], { stdio: 'ignore' });
    const stranger = spawn('/bin/sleep', ['30'], { stdio: 'ignore' });
    const ended = once(child, 'exit'), strangerEnded = once(stranger, 'exit');
    try {
      await Promise.all([once(child, 'spawn'), once(stranger, 'spawn')]);
      const saved = child.recordedIdentity;
      assert.ok(saved?.processIdentity);
      fs.unlinkSync(executable);
      assert.equal(fs.readlinkSync(`/proc/${child.pid}/exe`), executable + ' (deleted)');
      fs.writeFileSync(path.join(dir, 'runtime/manifest.json'), JSON.stringify({ children: [
        { id: 'deleted-helper', pid: child.pid, processIdentity: saved.processIdentity, executable }] }));
      const result = await api.cleanupRegisteredChildrenForTerminal(dir);
      assert.equal(result[0]?.outcome, 'terminated', JSON.stringify(result));
      assert.equal(result[0]?.killAccepted, true);
      await ended;
      assert.equal(api.inspectProcessIdentity(child.pid)?.alive, false);
      assert.equal(api.inspectProcessIdentity(stranger.pid)?.alive, true, 'a different process is never a cleanup target');
    } finally {
      if (child.exitCode === null && child.signalCode === null) child.kill();
      if (stranger.exitCode === null && stranger.signalCode === null) stranger.kill();
      await Promise.all([ended, strangerEnded]);
    }
  });

test('a malformed saved Linux tuple stays unknown and never authorizes a helper stop', async t => {
  const { dir, child } = manifest(t);
  const observed = { kind: 'linux-proc-v1', pid: child.pid,
    bootId: '333fd9cf-f547-4117-a537-fc8be3c44466', startTicks: '42' };
  const malformed = { ...observed, startTicks: 'unreadable' };
  fs.writeFileSync(path.join(dir, 'runtime/manifest.json'), JSON.stringify({ children: [
    { ...child, processIdentity: malformed }] }));
  const result = await api.cleanupRegisteredChildrenForTerminal(dir,
    () => ({ alive: true, executable: child.executable, processIdentity: observed }),
    () => assert.fail('an unreadable saved birth cannot authorize a signal'));
  assert.equal(result[0]?.outcome, 'unknown-identity', JSON.stringify(result));
  assert.equal(api.checkRecordedProcess({ pid: child.pid, processIdentity: malformed },
    { present: true, processIdentity: observed }), 'unknown');
});
