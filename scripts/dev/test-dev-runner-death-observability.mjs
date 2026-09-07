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

async function main() {
  await testPreserveEngineLogBasic();
  await testPreserveEngineLogMissingCases();
  await testStartStopStartTwicePreservesDistinctLogs();
  testBuildStopReportKillPath();
  testBuildStopReportSelfExitRecordsExitCode();
  await testWriteSelfExitStopReportWritesToDisk();
  testBuildHeadJavaOptsDefaults();
  testBuildHeadJavaOptsOverride();
  console.log('test-dev-runner-death-observability: ALL PASS');
}

await main();
