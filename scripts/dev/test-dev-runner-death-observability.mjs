#!/usr/bin/env node
/**
 * Tempdoc 730 Increment 4 (B1/B2/B3) — death observability ratchet.
 *
 * No live stack is exercised here (none is available to this test run); these are
 * unit/integration-level proofs against the pure functions and file-local logic dev-runner.cjs
 * uses to build B1 (per-run engine.log preservation), B2 (stop-report exit-code/liveness), and
 * B3 (bounded head heap + heap-dump-on-OOM JVM args). See docs/tempdocs/730-worker-lifecycle-integrity.md
 * §PLAN Increment 4 for the acceptance criteria this test targets.
 *
 * Lane F stage A resolution of the open question this comment used to carry. A11 deleted
 * WorkerSpawner and the Worker child, so nothing wrote <dataDir>/logs/worker.log; A16 renamed
 * the Engine's own log to <dataDir>/logs/engine.log. B1's SUBJECT therefore survives and these
 * tests follow it. What did NOT survive is the ownership guard (a readiness size/mtime stamp, a
 * size-monotonicity check, and a worker.log.1/.2 rotation-name fallback): every one of those
 * existed to detect WorkerSpawner's rename-rotation replacing the file under a run, and Logback
 * appends to engine.log rather than renaming it aside. Its test
 * (testPreserveWorkerLogOwnershipGuard) was DELETED with the guard, not weakened or skipped —
 * keeping it would have meant keeping a guard that can no longer fail, which is a vacuous green.
 * No other assertion in this file was weakened.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const require = createRequire(import.meta.url);
const devRunnerModule = require(path.join(__dirname, 'dev-runner.cjs'));
const {
  preserveEngineLog,
  buildStopReport,
  buildHeadJavaOpts,
  writeSelfExitStopReport,
} = devRunnerModule.__test;

// --- B1: per-run engine.log preservation -----------------------------------------------------

async function testPreserveEngineLogBasic() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-dev-runner-enginelog-'));
  try {
    const dataDir = path.join(tempRoot, 'dev-data');
    const logsDir = path.join(dataDir, 'logs');
    fs.mkdirSync(logsDir, { recursive: true });
    fs.writeFileSync(path.join(logsDir, 'engine.log'), 'boot ok\n', 'utf8');

    const runDir = path.join(tempRoot, 'runs', 'run-A');
    fs.mkdirSync(runDir, { recursive: true });
    const runPath = path.join(runDir, 'run.json');

    const result = await preserveEngineLog({ dataDir: dataDir }, runPath);
    assert.equal(result.preserved, true, 'engine.log should be preserved when present');
    const destPath = path.join(runDir, 'logs', 'engine.log');
    assert.ok(fs.existsSync(destPath), 'preserved copy should exist in the run dir');
    assert.equal(fs.readFileSync(destPath, 'utf8'), 'boot ok\n');
    // Original left in place — B1 augments, does not replace, the existing dataDir-scoped log.
    assert.ok(fs.existsSync(path.join(logsDir, 'engine.log')), 'source engine.log must remain');
    console.log('test-dev-runner-death-observability: preserveEngineLog basic copy — PASS');
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}

async function testPreserveEngineLogMissingCases() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-dev-runner-enginelog-missing-'));
  try {
    const runDir = path.join(tempRoot, 'runs', 'run-B');
    fs.mkdirSync(runDir, { recursive: true });
    const runPath = path.join(runDir, 'run.json');

    const noDataDir = await preserveEngineLog({ dataDir: null }, runPath);
    assert.equal(noDataDir.preserved, false);
    assert.equal(noDataDir.reason, 'no_data_dir');

    const dataDir = path.join(tempRoot, 'dev-data-empty');
    fs.mkdirSync(dataDir, { recursive: true }); // logs/engine.log intentionally absent
    const noLog = await preserveEngineLog({ dataDir }, runPath);
    assert.equal(noLog.preserved, false);
    assert.equal(noLog.reason, 'no_engine_log');
    console.log('test-dev-runner-death-observability: preserveEngineLog missing-input cases — PASS');
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}

/**
 * The property tempdoc 730 §THEORIZE B actually needs, restated for the post-A16 world: engine.log
 * is ONE file keyed to the (persistent, cross-run) dataDir, so without B1 a death run's evidence
 * lives only in a file every later run keeps writing to. Two runs against the SAME dataDir must
 * end up with two independent snapshots, and the earlier run's snapshot must not change when a
 * later run starts.
 *
 * What changed at A16, and why this test's fixture changed with it: run B no longer OVERWRITES
 * the shared log (that was WorkerSpawner's rename-rotation on the next spawn, deleted at A11).
 * Logback APPENDS, so B's fixture appends. That makes the assertion strictly harder in one
 * direction and honest in the other: run A's copy must still be byte-identical to what A wrote,
 * and run B's copy must contain A's line too — the preserved file is 'the engine log as it stood
 * at this run's stop', not a per-run extract, and this test pins that reading rather than
 * asserting an isolation the implementation does not provide.
 */
async function testStartStopStartTwicePreservesDistinctLogs() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-dev-runner-enginelog-cycle-'));
  try {
    const dataDir = path.join(tempRoot, 'dev-data'); // shared across restarts, like .dev-data
    const sharedEngineLog = path.join(dataDir, 'logs', 'engine.log');
    fs.mkdirSync(path.dirname(sharedEngineLog), { recursive: true });

    // Run A boots, writes to the shared engine.log, then dies/stops.
    const runALine = 'run-A: 2026-07-14T13:46:00Z boot\n';
    fs.writeFileSync(sharedEngineLog, runALine, 'utf8');
    const runADir = path.join(tempRoot, 'runs', 'run-A');
    fs.mkdirSync(runADir, { recursive: true });
    const runAResult = await preserveEngineLog({ dataDir }, path.join(runADir, 'run.json'));
    assert.equal(runAResult.preserved, true);

    // A NEW start (run B) APPENDS to the shared engine.log — Logback's append mode, which is
    // what replaced WorkerSpawner's destructive rename-rotation.
    const runBLine = 'run-B: 2026-07-14T16:06:00Z boot\n';
    fs.appendFileSync(sharedEngineLog, runBLine, 'utf8');
    const runBDir = path.join(tempRoot, 'runs', 'run-B');
    fs.mkdirSync(runBDir, { recursive: true });
    const runBResult = await preserveEngineLog({ dataDir }, path.join(runBDir, 'run.json'));
    assert.equal(runBResult.preserved, true);

    // The load-bearing assertion: run A's preserved copy is untouched by run B's start/stop.
    const runAPreserved = fs.readFileSync(path.join(runADir, 'logs', 'engine.log'), 'utf8');
    const runBPreserved = fs.readFileSync(path.join(runBDir, 'logs', 'engine.log'), 'utf8');
    assert.equal(runAPreserved, runALine, 'run A log must survive run B starting');
    assert.equal(runBPreserved, runALine + runBLine, 'run B copy is the appended shared log');
    assert.notEqual(runAPreserved, runBPreserved, 'the two runs must have distinct preserved logs');
    console.log('test-dev-runner-death-observability: start->stop->start twice preserves distinct per-run logs — PASS');
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}
// --- B2: stop-report exit-code / liveness -----------------------------------------------------

function testBuildStopReportKillPath() {
  const report = buildStopReport({
    runId: 'run-kill-1',
    stoppedAt: '2026-07-14T16:10:00.000Z',
    disposition: 'normal_stop',
    taskkillExitCode: 0,
    killedPids: [111, 222],
    pidLiveness: [
      { role: 'backend', pid: 111, aliveBeforeKill: true },
      { role: 'frontend', pid: 222, aliveBeforeKill: false },
      // Tempdoc 730 Increment-4 review: taskkill(pid, role) now pushes this null-shape entry
      // when a role is named but the pid is invalid/missing (e.g. the worker role was never
      // captured for this run) — a stop-report reader should see an explicit "no pid to probe"
      // fact for the role, not have the role silently absent from pidLiveness entirely.
      { role: 'worker', pid: null, aliveBeforeKill: null },
    ],
    ports: { api: { port: 6100, closed: true }, ui: { port: 5173, closed: true } },
    portsClosed: true,
    errors: [],
  });
  assert.equal(report.disposition, 'normal_stop');
  assert.deepEqual(report.killedPids, [111, 222]);
  assert.equal(report.pidLiveness.length, 3);
  assert.equal(report.pidLiveness[0].aliveBeforeKill, true);
  assert.equal(report.pidLiveness[1].aliveBeforeKill, false);
  assert.deepEqual(report.pidLiveness[2], { role: 'worker', pid: null, aliveBeforeKill: null },
    'a named role with an invalid/missing pid must carry a null-shape entry, not be omitted');
  // backendExitCode omitted entirely (not `null`) when not applicable — kill-driven stops carry
  // taskkillExitCode instead.
  assert.ok(!('backendExitCode' in report), 'kill-driven stop-report should not carry backendExitCode');
  console.log('test-dev-runner-death-observability: buildStopReport kill-path shape — PASS');
}

/**
 * B2 acceptance test named in tempdoc 730 §PLAN Increment 4: "a stop-report from a self-exited
 * backend records exit code." Exercise the report builder with a fake exit code — no live JVM
 * required.
 */
function testBuildStopReportSelfExitRecordsExitCode() {
  const report = buildStopReport({
    runId: 'run-self-exit-1',
    stoppedAt: '2026-07-14T16:08:29.000Z',
    disposition: 'self_exited',
    backendExitCode: 1,
    killedPids: [],
    pidLiveness: [],
    ports: null,
    portsClosed: null,
    errors: [],
  });
  assert.equal(report.disposition, 'self_exited');
  assert.equal(report.backendExitCode, 1, 'self-exit report must record the backend JVM exit code');
  assert.deepEqual(report.killedPids, []);
  console.log('test-dev-runner-death-observability: buildStopReport self-exit records exit code — PASS');
}

/**
 * End-to-end (fs-local, no live process) proof of writeSelfExitStopReport: previously the
 * backend.on('exit') path in cmdStart wrote NO stop-report at all on an unexpected self-exit —
 * this is the concrete regression check that a report now lands on disk with the exit code.
 */
async function testWriteSelfExitStopReportWritesToDisk() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-dev-runner-self-exit-'));
  try {
    const dataDir = path.join(tempRoot, 'dev-data');
    fs.mkdirSync(path.join(dataDir, 'logs'), { recursive: true });
    fs.writeFileSync(path.join(dataDir, 'logs', 'engine.log'), 'dying...\n', 'utf8');

    const runDir = path.join(tempRoot, 'runs', 'run-dead');
    fs.mkdirSync(runDir, { recursive: true });
    const runPath = path.join(runDir, 'run.json');

    const report = await writeSelfExitStopReport({
      runId: 'run-dead',
      runPath,
      run: { dataDir },
      backendExitCode: 137,
      interactive: false,
    });

    assert.equal(report.disposition, 'self_exited');
    assert.equal(report.backendExitCode, 137);
    assert.equal(report.engineLog.preserved, true);

    const onDisk = JSON.parse(fs.readFileSync(path.join(runDir, 'stop-report.json'), 'utf8'));
    assert.equal(onDisk.backendExitCode, 137);
    assert.equal(onDisk.disposition, 'self_exited');
    assert.ok(fs.existsSync(path.join(runDir, 'logs', 'engine.log')), 'engine.log must be preserved alongside the report');

    const interactiveReport = await writeSelfExitStopReport({
      runId: 'run-dead',
      runPath,
      run: { dataDir },
      backendExitCode: 0,
      interactive: true,
    });
    assert.equal(interactiveReport.disposition, 'interactive_stop');
    console.log('test-dev-runner-death-observability: writeSelfExitStopReport writes stop-report.json to disk — PASS');
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}

// --- B3: bounded head heap + heap-dump-on-OOM JVM args ------------------------------------------

function testBuildHeadJavaOptsDefaults() {
  const opts = buildHeadJavaOpts({
    existingJavaOpts: null,
    headAotOpts: '',
    headDistStamp: null,
    logsDir: 'C:\\repo\\tmp\\dev-runner\\runs\\run-1\\logs',
    headHeap: null,
  });
  // Tempdoc 730 Increment-4 review: a default -Xmx cap can itself induce an artifact OOM in the
  // exact death scenario being diagnosed, so the heap cap is opt-in ONLY (JUSTSEARCH_HEAD_HEAP) —
  // no -Xmx token is emitted when it isn't set. The dump flags stay unconditional/default-on.
  assert.doesNotMatch(opts, /-Xmx/, 'no -Xmx bound should be emitted when JUSTSEARCH_HEAD_HEAP is unset');
  assert.match(opts, /-XX:\+HeapDumpOnOutOfMemoryError/);
  assert.match(opts, /-XX:HeapDumpPath=C:\\repo\\tmp\\dev-runner\\runs\\run-1\\logs/,
    'heap dump must be pointed at THIS run\'s own logs dir, not the shared dataDir');
  console.log('test-dev-runner-death-observability: buildHeadJavaOpts defaults (dump flags present, no -Xmx) — PASS');
}

function testBuildHeadJavaOptsOverride() {
  const opts = buildHeadJavaOpts({
    existingJavaOpts: '-Dfoo=bar',
    headAotOpts: '-XX:AOTCache=/x/head.aot',
    headDistStamp: 'abc123',
    logsDir: '/tmp/run-2/logs',
    headHeap: '512m',
  });
  assert.match(opts, /-Xmx512m\b/, 'JUSTSEARCH_HEAD_HEAP override must be honored — -Xmx<value> present when env set');
  assert.match(opts, /-Dfoo=bar/, 'existing JAVA_OPTS must be preserved, not clobbered');
  assert.match(opts, /-Djustsearch\.head\.stamp=abc123/);
  assert.match(opts, /-XX:\+HeapDumpOnOutOfMemoryError/);
  console.log('test-dev-runner-death-observability: buildHeadJavaOpts honors overrides — PASS');
}

// --- B9 (lane F stage B): the three assertions a supervised run has to survive ------------------

/**
 * Everything above this line is fs-local and takes milliseconds. This one is not: it starts the REAL
 * dev-runner against the conformance harness's fake engine, lets the Engine crash once, and asserts
 * on the artifacts a supervised restart leaves behind.
 *
 * Why it cannot be fs-local. The three properties lane F item B9 names — the stop report joins to
 * run.json by run id, a restart preserves the run id and the lease's continuity, and incarnation N's
 * engine.log is preserved before N+1 can append to it — are all properties of what the supervisor
 * DOES, not of what its helpers return. Each one is trivially satisfiable by a fixture and only
 * meaningful against a real restart: the run id is only interesting because a restart could have
 * minted a new one, and the log snapshot is only interesting because a second incarnation really did
 * write to the shared file afterwards.
 *
 * About 6 seconds, no Gradle, no Engine dist, no network.
 */
async function testSupervisedRestartKeepsTheRunIdTheLeaseAndTheEvidence() {
  const repoRoot = path.resolve(__dirname, '..', '..');
  const root = fs.mkdtempSync(path.join(repoRoot, 'tmp', 'dev-runner-b9-'));
  const dataDir = path.join(root, 'data');
  const stateRoot = path.join(root, 'state');
  fs.mkdirSync(path.join(dataDir, 'runtime'), { recursive: true });
  fs.mkdirSync(stateRoot, { recursive: true });
  const planPath = path.join(root, 'plan.json');
  fs.writeFileSync(
    planPath,
    JSON.stringify({
      incarnations: [{ mode: 'crash', exitCode: 1, exitAfterMs: 1500 }, { mode: 'honour' }],
    }),
    'utf8',
  );

  const child = spawn(
    process.execPath,
    [
      path.join(repoRoot, 'scripts', 'dev', 'dev-runner.cjs'), 'start',
      '--json', '--skip-build', '--clean', 'none', '--api-port', '0',
      // A real port, not 0: the runner refuses --ui-port 0 outright, and the frontend stand-in
      // ignores it anyway. Picked high and fixed rather than probed — nothing binds it here.
      '--ui-port', '5599',
      '--data-dir', dataDir, '--session-id', 'test-dev-runner-death-observability',
    ],
    {
      cwd: repoRoot,
      env: {
        ...process.env,
        JUSTSEARCH_SUPERVISOR_HARNESS: '1',
        JUSTSEARCH_SUPERVISOR_STABILITY_WINDOW_MS: '600000',
        JUSTSEARCH_SUPERVISOR_COOLDOWN_INCREMENT_MS: '100',
        JUSTSEARCH_SUPERVISOR_MAX_COOLDOWN_MS: '400',
        JUSTSEARCH_SUPERVISOR_HANG_POLL_INTERVAL_MS: '2000',
        JUSTSEARCH_DEV_RUNNER_STATE_ROOT: stateRoot,
        JUSTSEARCH_DEV_RUNNER_ENGINE_COMMAND: JSON.stringify([
          process.execPath,
          path.join(repoRoot, 'scripts', 'supervisor-conformance', 'fake-engine.mjs'),
        ]),
        JUSTSEARCH_DEV_RUNNER_FRONTEND_COMMAND: JSON.stringify([
          process.execPath, '-e', 'setInterval(() => {}, 60000)',
        ]),
        JUSTSEARCH_FAKE_ENGINE_PLAN: planPath,
        JUSTSEARCH_DEV_RUNNER_BACKEND_PORT_TIMEOUT_MS: '15000',
        JUSTSEARCH_DEV_RUNNER_BACKEND_READY_TIMEOUT_MS: '15000',
        CI: '',
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  );
  let stderr = '';
  child.stderr.on('data', (b) => { stderr += b.toString(); });
  // The dev-runner reports a refused start as JSON on STDOUT, so a failure message that quoted only
  // stderr would say 'no second incarnation' with nothing after it.
  child.stdout.on('data', (b) => { stderr += b.toString(); });

  try {
    const statePath = path.join(dataDir, 'runtime', 'supervisor.v1.json');
    const deadline = Date.now() + 40_000;
    let state = null;
    for (;;) {
      try {
        state = JSON.parse(fs.readFileSync(statePath, 'utf8'));
      } catch { /* not written yet */ }
      if (state?.state === 'running' && state.incarnation === 2) break;
      assert.ok(Date.now() < deadline, `no second incarnation reached running.\n${stderr.slice(-2000)}`);
      // eslint-disable-next-line no-await-in-loop
      await new Promise((r) => setTimeout(r, 50));
    }
    assert.equal(state.restartCount, 1, 'the crash must be charged to the budget exactly once');

    const runDir = path.join(stateRoot, 'runs', state.runId);
    const runJson = JSON.parse(fs.readFileSync(path.join(runDir, 'run.json'), 'utf8'));

    // (1) The stop report joins to run.json. Before item B8 the report was the runner's LAST act, so
    //     a run with more than one death had at most one; now it is per incarnation, and the join key
    //     is what lets a reader put the two files side by side at all.
    const stopReport = JSON.parse(
      fs.readFileSync(path.join(runDir, 'incarnations', '1', 'stop-report.json'), 'utf8'),
    );
    assert.equal(stopReport.runId, runJson.runId, 'stop-report runId must equal run.json runId');
    assert.equal(stopReport.incarnation, 1);
    assert.equal(stopReport.backendExitCode, 1, 'the crash exit code is recorded, not inferred');

    // (2) The run id and the LEASE survive the child. This is design 7.6's dev-runner sentence, and
    //     it is what stops another session reading a recovering stack as an abandoned one.
    assert.equal(state.runId, runJson.runId, 'a restart must not mint a new run id');
    const active = JSON.parse(fs.readFileSync(path.join(stateRoot, 'active.json'), 'utf8'));
    assert.equal(active.runId, runJson.runId, 'the lease still names the same run after the restart');
    assert.ok(active.lease.sequence >= 1, 'the lease sequence was reset by the restart');
    assert.ok(
      Date.parse(active.lease.expiresAt) > Date.parse(active.lease.renewedAt),
      'the lease is not live across the restart',
    );

    // (3) Incarnation 1's engine.log is preserved BEFORE incarnation 2 can append to the shared file.
    //     engine.log is one file keyed to the dataDir and Logback appends, so without the
    //     per-incarnation snapshot the only surviving copy would be the one containing both.
    const preserved = fs.readFileSync(
      path.join(runDir, 'incarnations', '1', 'logs', 'engine.log'), 'utf8',
    );
    const shared = fs.readFileSync(path.join(dataDir, 'logs', 'engine.log'), 'utf8');
    assert.ok(preserved.includes('incarnation=1'), 'incarnation 1 evidence is in its own snapshot');
    assert.ok(
      !preserved.includes('incarnation=2'),
      'the snapshot was taken AFTER incarnation 2 started writing — it is not incarnation 1 evidence',
    );
    assert.ok(shared.includes('incarnation=2'), 'incarnation 2 really did append to the shared log');
    console.log('test-dev-runner-death-observability: a supervised restart keeps the run id, the lease and each incarnation\'s log — PASS');
  } finally {
    try { child.kill(); } catch { /* already gone */ }
    await new Promise((r) => setTimeout(r, 300));
    try { fs.rmSync(root, { recursive: true, force: true, maxRetries: 5 }); } catch { /* windows handles */ }
  }
}

async function main() {
  await testPreserveEngineLogBasic();
  await testPreserveEngineLogMissingCases();
  await testStartStopStartTwicePreservesDistinctLogs();
  testBuildStopReportKillPath();
  testBuildStopReportSelfExitRecordsExitCode();
  await testWriteSelfExitStopReportWritesToDisk();
  testBuildHeadJavaOptsDefaults();
  testBuildHeadJavaOptsOverride();
  await testSupervisedRestartKeepsTheRunIdTheLeaseAndTheEvidence();
  console.log('test-dev-runner-death-observability: ALL PASS');
}

await main();
