#!/usr/bin/env node
/**
 * Lane F stage B item B8 — the dev-runner supervisor's actuator, at unit level.
 *
 * The DECISIONS are proven by the conformance harness's decision table
 * (`node scripts/supervisor-conformance/run.mjs --self-test`) and the whole state machine is driven
 * end to end against a real child by its dev-runner adapter (`--adapter dev-runner`). What is left
 * for this file is the part in between: the handful of impure helpers the state machine leans on,
 * each of which can be wrong in a way no decision test would notice — a state file that never lands,
 * a terminal record that is not mirrored, a handle wait that returns `released` for a process that
 * is still holding the data directory.
 *
 * Every case here is fs-local and takes milliseconds. Nothing spawns an Engine.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const {
  buildSupervisorState,
  writeSupervisorState,
  supervisorStatePath,
  supervisorHistoryPath,
  writeShutdownRequestFile,
  readShutdownRequestReason,
  waitForEngineHandleRelease,
  preserveEngineLog,
  buildStopReport,
  computeOwnershipVerdict,
} = require(path.join(__dirname, 'dev-runner.cjs')).__test;
const engineSupervisor = require(path.join(__dirname, 'lib', 'engine-supervisor.cjs'));

function tempRoot(label) {
  return fs.mkdtempSync(path.join(os.tmpdir(), `justsearch-supervisor-${label}-`));
}

const PRODUCT_POLICY = engineSupervisor.loadPolicy({ env: {} });
const HARNESS_POLICY = engineSupervisor.loadPolicy({
  env: { [engineSupervisor.HARNESS_FLAG]: '1', [engineSupervisor.OVERRIDE_ENV.stabilityWindowMs]: '1500' },
});

// --- the state file ---------------------------------------------------------------------------

function testStateRecordsWhichPolicyItRanUnder() {
  const product = buildSupervisorState({
    state: 'running', runId: 'r1', incarnation: 1, policy: PRODUCT_POLICY, updatedAt: 'T',
  });
  const harness = buildSupervisorState({
    state: 'running', runId: 'r1', incarnation: 1, policy: HARNESS_POLICY, updatedAt: 'T',
  });
  assert.equal(product.policyProfile, 'product');
  assert.equal(product.maxRestartAttempts, PRODUCT_POLICY.maxRestartAttempts);
  assert.ok(!('policyOverrides' in product), 'a product-policy record must not claim overrides');
  // Why this matters enough to assert: `exhausted` after a 1.5s stability window and `exhausted`
  // after 300s are different facts, and the file is the only place a later reader can tell which
  // one it is looking at.
  assert.equal(harness.policyProfile, 'harness');
  assert.deepEqual(harness.policyOverrides, ['stabilityWindowMs']);
  console.log('test-dev-runner-supervisor: the state record names the policy it ran under — PASS');
}

async function testTerminalStateIsMirroredAndNonTerminalIsNot() {
  const root = tempRoot('state');
  try {
    const dataDir = path.join(root, 'data');
    const running = buildSupervisorState({
      state: 'running', runId: 'r1', incarnation: 2, restartCount: 1, policy: PRODUCT_POLICY, updatedAt: 'T1',
    });
    await writeSupervisorState(dataDir, running);
    const onDisk = JSON.parse(fs.readFileSync(supervisorStatePath(dataDir), 'utf8'));
    assert.equal(onDisk.state, 'running');
    assert.equal(onDisk.incarnation, 2);
    assert.equal(onDisk.kind, 'engine-supervisor-state.v1');
    assert.ok(
      !fs.existsSync(supervisorHistoryPath(dataDir)),
      'a non-terminal transition must NOT be mirrored — the mirror is the record of giving up, and a '
      + 'mirror of every transition would bury it',
    );

    const exhausted = buildSupervisorState({
      state: 'exhausted',
      runId: 'r1',
      incarnation: 4,
      restartCount: 3,
      policy: PRODUCT_POLICY,
      reason: 'ENGINE_RESTART_EXHAUSTED:out_of_memory',
      lastExit: { code: 3, reason: 'out_of_memory', class: 'TRANSIENT' },
      updatedAt: 'T2',
    });
    await writeSupervisorState(dataDir, exhausted);
    const history = fs.readFileSync(supervisorHistoryPath(dataDir), 'utf8').trim().split('\n');
    assert.equal(history.length, 1, 'exactly one terminal record');
    const mirrored = JSON.parse(history[0]);
    assert.equal(mirrored.state, 'exhausted');
    assert.equal(mirrored.reason, 'ENGINE_RESTART_EXHAUSTED:out_of_memory');
    assert.equal(mirrored.lastExit.reason, 'out_of_memory');

    // Append-only: a second terminal record joins the first rather than replacing it. This is the
    // property the updater's dead-Engine path needs — "the supervisor exhausted three boots ago" is
    // a question live state cannot answer.
    await writeSupervisorState(dataDir, { ...exhausted, updatedAt: 'T3' });
    assert.equal(
      fs.readFileSync(supervisorHistoryPath(dataDir), 'utf8').trim().split('\n').length,
      2,
      'the mirror must append, not rewrite',
    );
    console.log('test-dev-runner-supervisor: the terminal state is mirrored, non-terminal is not — PASS');
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

// --- the request file -------------------------------------------------------------------------

async function testRequestReasonIsReadNeverGuessed() {
  const root = tempRoot('request');
  try {
    const dataDir = path.join(root, 'data');
    assert.equal(readShutdownRequestReason(dataDir), null, 'absent file -> no reason');

    await writeShutdownRequestFile(dataDir, { reason: 'restart', deadlineEpochMs: 1 });
    assert.equal(readShutdownRequestReason(dataDir), 'restart');

    const target = path.join(dataDir, 'runtime', 'shutdown-request.v1.json');
    fs.writeFileSync(target, '{ this is not json', 'utf8');
    assert.equal(readShutdownRequestReason(dataDir), null, 'a file caught mid-write is ignored, not guessed');

    fs.writeFileSync(target, JSON.stringify({ reason: 'reboot' }), 'utf8');
    assert.equal(
      readShutdownRequestReason(dataDir),
      null,
      'an unknown reason must NOT be treated as a request: charging a death to the wrong class is '
      + 'how a crash loop becomes invisible',
    );
    console.log('test-dev-runner-supervisor: the request reason is read, never guessed — PASS');
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

// --- the cooldown floor -----------------------------------------------------------------------

async function testHandleReleaseWaitsForALiveProcess() {
  const root = tempRoot('handle');
  try {
    const dataDir = path.join(root, 'data');
    fs.mkdirSync(path.join(dataDir, 'logs'), { recursive: true });
    fs.writeFileSync(path.join(dataDir, 'logs', 'engine.log'), 'x\n', 'utf8');
    fs.writeFileSync(path.join(dataDir, 'app.lock'), '', 'utf8');

    const dead = await waitForEngineHandleRelease({ pid: null, dataDir, timeoutMs: 500 });
    assert.equal(dead.released, true, 'no live process and free files -> released immediately');
    assert.ok(dead.waitedMs < 400, `should not have waited (${dead.waitedMs}ms)`);

    // The adverse precondition (`green-masked-destructive`): a wait that returns `released` for a
    // process still holding the data directory is worse than no wait at all, because the restart it
    // green-lights exits DATA_DIR_LOCKED — which the classifier calls NON_TRANSIENT, so one wrong
    // `released` turns a recoverable crash into `exhausted`.
    const child = spawn(process.execPath, ['-e', 'setTimeout(() => {}, 30000)'], { stdio: 'ignore' });
    try {
      const live = await waitForEngineHandleRelease({ pid: child.pid, dataDir, timeoutMs: 400, intervalMs: 50 });
      assert.equal(live.released, false, 'a live pid must NOT be reported as released');
      assert.equal(live.pidGone, false);
      assert.ok(live.waitedMs >= 350, `should have waited out the budget (${live.waitedMs}ms)`);
    } finally {
      child.kill();
    }
    console.log('test-dev-runner-supervisor: the cooldown floor waits for a live process — PASS');
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

// --- per-incarnation evidence ------------------------------------------------------------------

async function testEachIncarnationKeepsItsOwnEngineLog() {
  const root = tempRoot('incarnations');
  try {
    const dataDir = path.join(root, 'data');
    const engineLog = path.join(dataDir, 'logs', 'engine.log');
    fs.mkdirSync(path.dirname(engineLog), { recursive: true });
    const runDir = path.join(root, 'runs', 'run-1');
    fs.mkdirSync(runDir, { recursive: true });
    const runPath = path.join(runDir, 'run.json');
    const run = { dataDir };

    fs.writeFileSync(engineLog, 'incarnation-1: boot\nincarnation-1: died\n', 'utf8');
    const first = await preserveEngineLog(run, runPath, { destSubdir: path.join('incarnations', '1') });
    assert.equal(first.preserved, true);

    // Logback APPENDS across incarnations, so without a per-incarnation destination the second
    // death's copy would be the only copy — and the first death is the one that explains the second.
    fs.appendFileSync(engineLog, 'incarnation-2: boot\nincarnation-2: died\n', 'utf8');
    const second = await preserveEngineLog(run, runPath, { destSubdir: path.join('incarnations', '2') });
    assert.equal(second.preserved, true);

    const firstCopy = fs.readFileSync(path.join(runDir, 'incarnations', '1', 'logs', 'engine.log'), 'utf8');
    const secondCopy = fs.readFileSync(path.join(runDir, 'incarnations', '2', 'logs', 'engine.log'), 'utf8');
    assert.equal(firstCopy, 'incarnation-1: boot\nincarnation-1: died\n',
      'incarnation 1 evidence must be preserved BEFORE incarnation 2 can append to the shared file');
    assert.ok(secondCopy.includes('incarnation-2: died'));
    assert.notEqual(firstCopy, secondCopy);

    // The default destination is unchanged, which is why every pre-B8 caller and its tests still hold.
    const legacy = await preserveEngineLog(run, runPath);
    assert.equal(legacy.preserved, true);
    assert.ok(fs.existsSync(path.join(runDir, 'logs', 'engine.log')));
    console.log('test-dev-runner-supervisor: each incarnation keeps its own engine.log — PASS');
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

function testStopReportCarriesTheIncarnationOnlyWhenSupervised() {
  const supervised = buildStopReport({
    runId: 'r', stoppedAt: 'T', disposition: 'self_exited', backendExitCode: 1, incarnation: 3,
  });
  assert.equal(supervised.incarnation, 3);
  assert.equal(supervised.schemaVersion, 2, 'an optional additive field does not bump the schema');
  const unsupervised = buildStopReport({ runId: 'r', stoppedAt: 'T', disposition: 'normal_stop' });
  assert.ok(!('incarnation' in unsupervised), 'an unsupervised stop must not claim an incarnation');
  console.log('test-dev-runner-supervisor: the stop report is per-incarnation only when supervised — PASS');
}

// --- the lease across a restart -----------------------------------------------------------------

/**
 * Item B8's acceptance: a supervised restart must not read as `TAKEOVER_ABANDONED` to another
 * session. The adapter proves it on a real restart's real `active.json`; this proves the property
 * the adapter's assertion RESTS on, including the direction that makes it non-vacuous.
 */
function testARestartIsNotAnAbandonedStack() {
  const now = Date.now();
  const active = {
    runId: 'run-1',
    holder: { source: 'dev-runner', agentSessionId: 'owner-session' },
    lease: {
      durationSec: 30,
      renewedAt: new Date(now - 2000).toISOString(),
      expiresAt: new Date(now + 28_000).toISOString(),
      sequence: 7,
    },
  };
  const facts = {
    active,
    now,
    takeover: 'deny',
    callerSessionId: 'a-different-session',
    selfCheck: false,
    supervisorAlive: true,
    leaseExpired: false,
  };
  const live = computeOwnershipVerdict({
    ...facts,
    ownerActivity: { lastActivityAt: new Date(now - 1000).toISOString(), lastDevStackTouchAt: new Date(now - 1000).toISOString() },
  });
  assert.notEqual(
    live.verdict,
    'TAKEOVER_ABANDONED',
    'a live owner whose Engine is mid-restart is not an abandoned stack — the lease is renewed by the '
    + 'supervisor, which survives the child, so nothing about a restart should reach this verdict',
  );

  // The direction that keeps the assertion honest: the verdict IS reachable, so the check above is
  // not passing because TAKEOVER_ABANDONED is unreachable in this shape.
  const silent = computeOwnershipVerdict({
    ...facts,
    ownerActivity: { lastActivityAt: new Date(now - 6 * 60 * 60 * 1000).toISOString() },
  });
  assert.equal(
    silent.verdict,
    'TAKEOVER_ABANDONED',
    'a long-silent owner must still be reclaimable; if this stops holding, the assertion above proves nothing',
  );
  console.log('test-dev-runner-supervisor: a supervised restart is not an abandoned stack — PASS');
}

async function main() {
  testStateRecordsWhichPolicyItRanUnder();
  await testTerminalStateIsMirroredAndNonTerminalIsNot();
  await testRequestReasonIsReadNeverGuessed();
  await testHandleReleaseWaitsForALiveProcess();
  await testEachIncarnationKeepsItsOwnEngineLog();
  testStopReportCarriesTheIncarnationOnlyWhenSupervised();
  testARestartIsNotAnAbandonedStack();
  console.log('test-dev-runner-supervisor: ALL PASS');
}

await main();
