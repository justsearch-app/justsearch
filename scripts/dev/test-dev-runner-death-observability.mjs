#!/usr/bin/env node
/**
 * Tempdoc 730 Increment 4 (B1/B2/B3) — death observability ratchet.
 *
 * No live stack is exercised here (none is available to this test run); these are
 * unit/integration-level proofs against the pure functions and file-local logic dev-runner.cjs
 * uses to build B1 (per-run worker.log preservation), B2 (stop-report exit-code/liveness), and
 * B3 (bounded head heap + heap-dump-on-OOM JVM args). See docs/tempdocs/730-worker-lifecycle-integrity.md
 * §PLAN Increment 4 for the acceptance criteria this test targets.
 *
 * Historical note (lane F item A11): the WorkerSpawner.java rotation this file's B1 tests
 * simulate was written when a separate Worker JVM existed; item A11 later deleted WorkerSpawner
 * and the Worker child process, removing the only known producer of worker.log. Whether anything
 * still writes or rotates that path has not been verified — flagged for lane F item A13 to
 * resolve. None of these tests' assertions have been weakened, skipped, or removed on that basis.
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
  preserveWorkerLog,
  buildStopReport,
  buildHeadJavaOpts,
  writeSelfExitStopReport,
  captureWorkerLogStamp,
} = devRunnerModule.__test;

// --- B1: per-run worker.log preservation -----------------------------------------------------

async function testPreserveWorkerLogBasic() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-dev-runner-workerlog-'));
  try {
    const dataDir = path.join(tempRoot, 'dev-data');
    const logsDir = path.join(dataDir, 'logs');
    fs.mkdirSync(logsDir, { recursive: true });
    fs.writeFileSync(path.join(logsDir, 'worker.log'), 'boot ok\n', 'utf8');

    const runDir = path.join(tempRoot, 'runs', 'run-A');
    fs.mkdirSync(runDir, { recursive: true });
    const runPath = path.join(runDir, 'run.json');

    const result = await preserveWorkerLog({ dataDir: dataDir }, runPath);
    assert.equal(result.preserved, true, 'worker.log should be preserved when present');
    const destPath = path.join(runDir, 'logs', 'worker.log');
    assert.ok(fs.existsSync(destPath), 'preserved copy should exist in the run dir');
    assert.equal(fs.readFileSync(destPath, 'utf8'), 'boot ok\n');
    // Original left in place — B1 augments, does not replace, the existing dataDir-scoped log.
    assert.ok(fs.existsSync(path.join(logsDir, 'worker.log')), 'source worker.log must remain');
    console.log('test-dev-runner-death-observability: preserveWorkerLog basic copy — PASS');
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}

async function testPreserveWorkerLogMissingCases() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-dev-runner-workerlog-missing-'));
  try {
    const runDir = path.join(tempRoot, 'runs', 'run-B');
    fs.mkdirSync(runDir, { recursive: true });
    const runPath = path.join(runDir, 'run.json');

    const noDataDir = await preserveWorkerLog({ dataDir: null }, runPath);
    assert.equal(noDataDir.preserved, false);
    assert.equal(noDataDir.reason, 'no_data_dir');

    const dataDir = path.join(tempRoot, 'dev-data-empty');
    fs.mkdirSync(dataDir, { recursive: true }); // logs/worker.log intentionally absent
    const noLog = await preserveWorkerLog({ dataDir }, runPath);
    assert.equal(noLog.preserved, false);
    assert.equal(noLog.reason, 'no_worker_log');
    console.log('test-dev-runner-death-observability: preserveWorkerLog missing-input cases — PASS');
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}

/**
 * The exact regression tempdoc 730 §THEORIZE B reproduced: worker.log is a single file keyed to
 * the (persistent, cross-run) dataDir, and at the time this was written was rotated by
 * WorkerSpawner.java on the NEXT worker spawn (see this file's head comment on item A11's
 * deletion of WorkerSpawner). Simulate two runs against the SAME dataDir (mirroring
 * restart-preserves-index): run A writes "death run" content, is preserved at its own stop; a
 * NEW run B then overwrites the shared worker.log (simulating what was WorkerSpawner's rotation
 * on the next start) and is preserved at ITS stop. Assert run A's preserved copy survives run
 * B's start UNCHANGED and distinct from run B's.
 */
async function testStartStopStartTwicePreservesDistinctLogs() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-dev-runner-workerlog-cycle-'));
  try {
    const dataDir = path.join(tempRoot, 'dev-data'); // shared across "restarts", like .dev-data
    const sharedWorkerLog = path.join(dataDir, 'logs', 'worker.log');
    fs.mkdirSync(path.dirname(sharedWorkerLog), { recursive: true });

    // Run A "boots", writes to the shared worker.log, then dies/stops.
    fs.writeFileSync(sharedWorkerLog, 'run-A: 2026-07-14T13:46:00Z boot\n', 'utf8');
    const runADir = path.join(tempRoot, 'runs', 'run-A');
    fs.mkdirSync(runADir, { recursive: true });
    const runAPath = path.join(runADir, 'run.json');
    const runAResult = await preserveWorkerLog({ dataDir }, runAPath);
    assert.equal(runAResult.preserved, true);

    // A NEW start (run B) rotates/overwrites the shared worker.log — the exact destructive
    // event tempdoc 730 identified (WorkerSpawner.java:366-386 rotation on next spawn, back
    // when WorkerSpawner existed; item A11 deleted it, see this file's head comment).
    fs.writeFileSync(sharedWorkerLog, 'run-B: 2026-07-14T16:06:00Z boot\n', 'utf8');
    const runBDir = path.join(tempRoot, 'runs', 'run-B');
    fs.mkdirSync(runBDir, { recursive: true });
    const runBPath = path.join(runBDir, 'run.json');
    const runBResult = await preserveWorkerLog({ dataDir }, runBPath);
    assert.equal(runBResult.preserved, true);

    // The load-bearing assertion: run A's preserved copy is untouched by run B's start/stop.
    const runAPreserved = fs.readFileSync(path.join(runADir, 'logs', 'worker.log'), 'utf8');
    const runBPreserved = fs.readFileSync(path.join(runBDir, 'logs', 'worker.log'), 'utf8');
    assert.equal(runAPreserved, 'run-A: 2026-07-14T13:46:00Z boot\n', 'run A log must survive run B starting');
    assert.equal(runBPreserved, 'run-B: 2026-07-14T16:06:00Z boot\n');
    assert.notEqual(runAPreserved, runBPreserved, 'the two runs must have distinct preserved logs');
    console.log('test-dev-runner-death-observability: start->stop->start twice preserves distinct per-run logs — PASS');
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}

/**
 * Reap-after-restart ownership guard (tempdoc 730 Increment-4 review). Reproduces the exact
 * mislabel construction the review flagged: run A stamps worker.log at readiness (content A,
 * size sA at mtime mA); a NEW run B is spawned before run A's stop-time preserve() runs, and B's
 * spawn triggered what was, at the time, WorkerSpawner's rotation of the shared path (item A11
 * has since deleted WorkerSpawner; see this file's head comment). A naive "current mtime >= stamp
 * mtime" guard would treat B's fresh content as A's own "verified" log (it always is >= — mtime
 * only advances). The implemented guard instead requires monotonic SIZE (an append-only log never
 * shrinks while a run owns it) plus name-based rotation lookup (worker.log.1/.2), so:
 *   - a same-path file that only grew since the stamp -> 'verified' (still A's file)
 *   - a same-path file that SHRANK (replaced) but the old content survives at worker.log.1
 *     (matches the stamp's monotonicity there) -> 'heuristic'/'rotated', and the copied bytes are
 *     A's original content, not B's
 *   - no surviving generation satisfies the stamp at all -> 'ownership_unverified', never a
 *     silent mislabel
 *
 * (Substitution note: the reviewed plan's first-choice guard was birthtime identity —
 * WorkerSpawner renaming worker.log -> worker.log.1 used to preserve birthtime while a fresh spawn's
 * file gets a new one. A live probe on this Windows/NTFS checkout disproved that: NTFS
 * file-system tunneling hands a freshly-created file at a just-vacated path the OLD file's
 * birthtime back, making the rotated and the fresh-replacement file indistinguishable by
 * birthtime alone. This test exercises the size-monotonic + rotation-name fallback actually
 * implemented in preserveWorkerLog instead.)
 */
async function testPreserveWorkerLogOwnershipGuard() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-dev-runner-workerlog-ownership-'));
  try {
    const dataDir = path.join(tempRoot, 'dev-data');
    const logsDir = path.join(dataDir, 'logs');
    fs.mkdirSync(logsDir, { recursive: true });
    const workerLog = path.join(logsDir, 'worker.log');
    const workerLog1 = path.join(logsDir, 'worker.log.1');

    // Run A boots and is stamped at readiness.
    fs.writeFileSync(workerLog, 'run-A: boot line\n', 'utf8');
    const stampA = captureWorkerLogStamp(dataDir);
    assert.ok(stampA, 'stamp must capture an existing worker.log');

    // --- Case 1: same-path file that only grew since the stamp -> verified -----------------
    fs.appendFileSync(workerLog, 'run-A: more output before stop\n', 'utf8');
    const runVerifiedDir = path.join(tempRoot, 'runs', 'run-verified');
    fs.mkdirSync(runVerifiedDir, { recursive: true });
    const resultVerified = await preserveWorkerLog(
      { dataDir, workerLogStamp: stampA },
      path.join(runVerifiedDir, 'run.json'),
    );
    assert.equal(resultVerified.preserved, true);
    assert.equal(resultVerified.ownership, 'verified', 'a grown-but-same-path file must be copied as verified');
    assert.equal(
      fs.readFileSync(path.join(runVerifiedDir, 'logs', 'worker.log'), 'utf8'),
      'run-A: boot line\nrun-A: more output before stop\n',
    );

    // --- Case 2: rotated away, replaced by a fresh smaller file -> heuristic/rotated -------
    // At the time this was written, WorkerSpawner rotated by renaming worker.log -> worker.log.1
    // on the NEXT spawn; the new spawn's own worker.log started fresh and small (below A's
    // stamped size), which is the tell that the current path stopped being A's. (Item A11 has
    // since deleted WorkerSpawner; see this file's head comment.)
    fs.renameSync(workerLog, workerLog1);
    fs.writeFileSync(workerLog, 'B\n', 'utf8'); // deliberately smaller than stampA.size
    assert.ok(fs.statSync(workerLog).size < stampA.size, 'test fixture: run B\'s fresh file must be smaller than the stamp');

    const runRotatedDir = path.join(tempRoot, 'runs', 'run-rotated');
    fs.mkdirSync(runRotatedDir, { recursive: true });
    const resultRotated = await preserveWorkerLog(
      { dataDir, workerLogStamp: stampA },
      path.join(runRotatedDir, 'run.json'),
    );
    assert.equal(resultRotated.preserved, true);
    assert.equal(resultRotated.ownership, 'heuristic');
    assert.equal(resultRotated.source, 'rotated', 'run A\'s content must be found at worker.log.1');
    assert.equal(
      fs.readFileSync(path.join(runRotatedDir, 'logs', 'worker.log'), 'utf8'),
      'run-A: boot line\nrun-A: more output before stop\n',
      'the rotated copy must be run A\'s original content, not run B\'s overwrite — the mislabel this guard prevents',
    );

    // --- Case 3: no surviving generation satisfies the stamp -> ownership_unverified -------
    // worker.log.1 itself gets overwritten by an unrelated, smaller run C before A's log is ever
    // read — no rename trail, no size match anywhere.
    fs.writeFileSync(workerLog1, 'C\n', 'utf8');
    assert.ok(fs.statSync(workerLog1).size < stampA.size, 'test fixture: the replacement at .log.1 must also undercut the stamp');

    const runReplacedDir = path.join(tempRoot, 'runs', 'run-replaced');
    fs.mkdirSync(runReplacedDir, { recursive: true });
    const resultReplaced = await preserveWorkerLog(
      { dataDir, workerLogStamp: stampA },
      path.join(runReplacedDir, 'run.json'),
    );
    assert.equal(resultReplaced.preserved, false);
    assert.equal(resultReplaced.reason, 'ownership_unverified');
    assert.ok(
      !fs.existsSync(path.join(runReplacedDir, 'logs', 'worker.log')),
      'no file should be copied when ownership cannot be verified',
    );

    // --- Unstamped run.json (predates the ownership stamp) keeps prior best-effort behavior -
    const runUnstampedDir = path.join(tempRoot, 'runs', 'run-unstamped');
    fs.mkdirSync(runUnstampedDir, { recursive: true });
    const resultUnstamped = await preserveWorkerLog({ dataDir }, path.join(runUnstampedDir, 'run.json'));
    assert.equal(resultUnstamped.preserved, true);
    assert.equal(resultUnstamped.ownership, 'unstamped', 'a run.json with no stamp must mark the copy unstamped, not verified');

    console.log('test-dev-runner-death-observability: preserveWorkerLog ownership guard (verified/rotated/unverified/unstamped) — PASS');
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
    fs.writeFileSync(path.join(dataDir, 'logs', 'worker.log'), 'dying...\n', 'utf8');

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
    assert.equal(report.workerLog.preserved, true);

    const onDisk = JSON.parse(fs.readFileSync(path.join(runDir, 'stop-report.json'), 'utf8'));
    assert.equal(onDisk.backendExitCode, 137);
    assert.equal(onDisk.disposition, 'self_exited');
    assert.ok(fs.existsSync(path.join(runDir, 'logs', 'worker.log')), 'worker.log must be preserved alongside the report');

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
  await testPreserveWorkerLogBasic();
  await testPreserveWorkerLogMissingCases();
  await testStartStopStartTwicePreservesDistinctLogs();
  await testPreserveWorkerLogOwnershipGuard();
  testBuildStopReportKillPath();
  testBuildStopReportSelfExitRecordsExitCode();
  await testWriteSelfExitStopReportWritesToDisk();
  testBuildHeadJavaOptsDefaults();
  testBuildHeadJavaOptsOverride();
  console.log('test-dev-runner-death-observability: ALL PASS');
}

await main();
