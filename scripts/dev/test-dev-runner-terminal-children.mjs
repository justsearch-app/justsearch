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

test('Windows default PowerShell inspector binds the real current PID (read only)',
  { skip: process.platform !== 'win32' }, () => {
    const result = api.inspectProcessIdentity(process.pid);
    assert.equal(result?.alive, true);
    assert.equal(path.resolve(result.executable).toLowerCase(), process.execPath.toLowerCase());
    assert.ok(Number.isFinite(Date.parse(result.startedAt)));
  });

test('Windows default inspector distinguishes a missing PID from a failed query (read only)',
  { skip: process.platform !== 'win32' }, () => {
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
  const run = { runId: path.basename(dir), dataDir: dir, apiPortActual: 0, uiPortActual: 0,
    pids: { runnerPid: process.pid, frontendRootPid: null, backendRootPid: 123 } };
  const result = await api.stopRun({}, {
    resolveTarget: async () => ({ run, runPath }),
    gracefulShutdown: async () => ({ outcome: 'exited', exitCode: 1 }),
    census: async () => ({ finish: async killed => {
      assert.deepEqual(killed, [child.pid]); return {};
    } }),
    inspectChild: () => alive ? { ...child, alive } : { alive: false },
    terminateChild: pid => { assert.equal(pid, child.pid); alive = false; return true; },
  });
  assert.equal(alive, false);
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
