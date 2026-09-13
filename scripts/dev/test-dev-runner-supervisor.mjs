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
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createInterface } from 'node:readline';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const {
  buildSupervisorState,
  writeJsonAtomic,
  writeSupervisorState,
  createSupervisorStateWriter,
  supervisorStatePath,
  supervisorHistoryPath,
  writeShutdownRequestFile,
  waitForEngineHandleRelease,
  preserveEngineLog,
  buildStopReport,
  computeOwnershipVerdict,
  cleanupRegisteredChildrenForTerminal,
  checkHttp200,
  fetchJsonHttp,
  essentialStatusReady,
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

async function testOverlappingStatePublicationsStayInTransitionOrder() {
  const root = tempRoot('ordered-state');
  let releaseFirst;
  const blocked = new Promise(resolve => { releaseFirst = resolve; });
  const started = [];
  const dataDir = path.join(root, 'data');
  const publish = createSupervisorStateWriter(dataDir, async (directory, record) => {
    started.push(record.state);
    if (record.state === 'stopping') await blocked;
    await writeSupervisorState(directory, record);
  });
  const stopping = publish(buildSupervisorState({
    state: 'stopping', runId: 'r1', incarnation: 1, policy: PRODUCT_POLICY, updatedAt: 'T1',
  }));
  const restarting = publish(buildSupervisorState({
    state: 'restarting', runId: 'r1', incarnation: 1, policy: PRODUCT_POLICY, updatedAt: 'T2',
  }));
  try {
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(started, ['stopping'], 'the later transition cannot enter the shared temp-file writer');
    releaseFirst();
    await Promise.all([stopping, restarting]);
    assert.deepEqual(started, ['stopping', 'restarting']);
    assert.equal(JSON.parse(fs.readFileSync(supervisorStatePath(dataDir), 'utf8')).state, 'restarting');
    assert.equal(fs.existsSync(`${supervisorStatePath(dataDir)}.tmp`), false);
    console.log('test-dev-runner-supervisor: overlapping transitions publish in order — PASS');
  } finally {
    releaseFirst();
    await Promise.allSettled([stopping, restarting]);
    const resolved = path.resolve(root);
    assert.equal(path.dirname(resolved), path.resolve(os.tmpdir()));
    assert.ok(path.basename(resolved).startsWith('justsearch-supervisor-ordered-state-'));
    fs.rmSync(resolved, { recursive: true, force: true });
  }
}

async function withWindowsReadHandle(filePath, body) {
  const script = `
$ErrorActionPreference = 'Stop'
$reader = [IO.File]::Open($env:JUSTSEARCH_TEST_READ_HANDLE, [IO.FileMode]::Open,
    [IO.FileAccess]::Read, [IO.FileShare]::Read)
try {
    [Console]::WriteLine('held')
    [Console]::Out.Flush()
    [void][Console]::ReadLine()
} finally { $reader.Dispose() }
`;
  const child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand',
    Buffer.from(script, 'utf16le').toString('base64')], {
    windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'],
    env: { ...process.env, JUSTSEARCH_TEST_READ_HANDLE: filePath },
  });
  const lines = createInterface({ input: child.stdout });
  let stderr = '';
  child.stderr.on('data', chunk => { stderr += chunk; });
  const exited = new Promise(resolve => {
    child.once('exit', (code, signal) => resolve({ code, signal }));
    child.once('error', error => resolve({ error }));
  });
  let released = false;
  const release = async () => {
    if (!released) { released = true; child.stdin.end('\n'); }
    const result = await exited;
    assert.equal(result.code, 0, `read-handle helper failed: ${stderr || result.error || result.signal}`);
  };
  try {
    const [line] = await Promise.race([
      once(lines, 'line', { signal: AbortSignal.timeout(10_000) }),
      exited.then(result => { throw new Error(`reader exited before holding: ${stderr || JSON.stringify(result)}`); }),
    ]);
    assert.equal(line, 'held');
    console.log(`test-dev-runner-supervisor: Windows reader holds destination, pid=${child.pid}`);
    await body(release);
  } finally {
    lines.close();
    // The fixture is our own hidden child, never a registered dev-stack helper.
    const killTimer = setTimeout(() => child.kill(), 5_000);
    try { await release(); } finally { clearTimeout(killTimer); }
  }
}

async function testWindowsReadHandlePublication() {
  if (process.platform !== 'win32') return;
  const root = tempRoot('reader-rename');
  const target = path.join(root, 'state.json');
  const oldState = { state: 'running', incarnation: 1 };
  const nextState = { state: 'restarting', incarnation: 2 };
  await writeJsonAtomic(target, oldState);
  try {
    await withWindowsReadHandle(target, async release => {
      const probe = path.join(root, 'rename-probe.json');
      fs.writeFileSync(probe, '{}');
      await assert.rejects(fs.promises.rename(probe, target),
        error => {
          console.log(`test-dev-runner-supervisor: held-reader rename probe returned ${error.code}`);
          return error.code === 'EPERM' || error.code === 'EBUSY';
        });
      fs.unlinkSync(probe);
      const observations = [];
      const reader = setInterval(() => {
        try { observations.push(JSON.parse(fs.readFileSync(target, 'utf8'))); }
        catch (error) { observations.push(error); }
      }, 25);
      const publication = writeJsonAtomic(target, nextState).then(
        () => ({ ok: true }), error => ({ error }));
      try {
        await new Promise(resolve => setTimeout(resolve, 150));
        assert.deepEqual(JSON.parse(fs.readFileSync(target, 'utf8')), oldState);
        assert.ok(fs.existsSync(`${target}.tmp`), 'refused rename must retain its temporary file for retry');
        await release();
        const result = await publication;
        assert.equal(result.error, undefined, `publication did not recover: ${result.error}`);
        assert.deepEqual(JSON.parse(fs.readFileSync(target, 'utf8')), nextState);
        assert.ok(observations.length > 0, 'the 25ms reader must actually observe the held interval');
        for (const row of observations) {
          assert.ok(!(row instanceof Error), `reader saw an unreadable or partial state: ${row}`);
          assert.ok(row.incarnation === 1 || row.incarnation === 2);
        }
      } finally {
        clearInterval(reader);
        await release();
        await publication;
      }
    });
    await withWindowsReadHandle(target, async release => {
      const started = performance.now();
      const publication = writeJsonAtomic(target, { state: 'exhausted', incarnation: 3 }).then(
        () => ({}), error => ({ error }));
      let watchdog;
      try {
        const result = await Promise.race([publication, new Promise((_, reject) => {
          watchdog = setTimeout(() => reject(new Error('held-reader publication exceeded five seconds')), 5_000);
        })]);
        assert.ok(['EPERM', 'EBUSY'].includes(result.error?.code), 'held-open exhaustion must preserve its refusal');
        const elapsed = performance.now() - started;
        assert.ok(elapsed >= 900 && elapsed < 5_000, `rename retry must be bounded near one second: ${elapsed}ms`);
        assert.deepEqual(JSON.parse(fs.readFileSync(target, 'utf8')), nextState);
        assert.equal(fs.existsSync(`${target}.tmp`), false, 'exhausted publication cleans only its temporary file');
        console.log(`test-dev-runner-supervisor: held-reader retry exhausted after ${Math.round(elapsed)}ms`);
      } finally {
        clearTimeout(watchdog);
        await release();
        await publication;
      }
    });
    console.log('test-dev-runner-supervisor: held Windows reader retries and expires without losing state — PASS');
  } finally {
    const resolved = path.resolve(root);
    assert.equal(path.dirname(resolved), path.resolve(os.tmpdir()));
    assert.ok(path.basename(resolved).startsWith('justsearch-supervisor-reader-rename-'));
    fs.rmSync(resolved, { recursive: true, force: true });
  }
}

async function testPermanentRenameFailureIsNotRetried() {
  const root = tempRoot('permanent-rename');
  const target = path.join(root, 'state.json');
  const original = { state: 'running' };
  await writeJsonAtomic(target, original);
  const rename = fs.promises.rename;
  const failure = Object.assign(new Error('permanent rename failure'), { code: 'EIO' });
  let attempts = 0;
  try {
    fs.promises.rename = async () => { attempts++; throw failure; };
    await assert.rejects(writeJsonAtomic(target, { state: 'restarting' }), error => error === failure);
    assert.equal(attempts, 1, 'non-retryable errors must fail immediately');
    assert.deepEqual(JSON.parse(fs.readFileSync(target, 'utf8')), original);
    assert.equal(fs.existsSync(`${target}.tmp`), false);
    console.log('test-dev-runner-supervisor: permanent rename failure preserves error and destination — PASS');
  } finally {
    fs.promises.rename = rename;
    const resolved = path.resolve(root);
    assert.equal(path.dirname(resolved), path.resolve(os.tmpdir()));
    assert.ok(path.basename(resolved).startsWith('justsearch-supervisor-permanent-rename-'));
    fs.rmSync(resolved, { recursive: true, force: true });
  }
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

async function testHostRequestWriterAndHandoffAdmission() {
  const root = tempRoot('request');
  try {
    const dataDir = path.join(root, 'data');
    await writeShutdownRequestFile(dataDir, { reason: 'restart', deadlineEpochMs: 1 });
    const body = JSON.parse(fs.readFileSync(path.join(dataDir, 'runtime', 'shutdown-request.v1.json'), 'utf8'));
    assert.deepEqual(body, { schemaVersion: 1, reason: 'restart', deadlineEpochMs: 1, issuedBy: 'dev-runner' });
    const manifest = { schemaVersion: 2, pid: 42, instanceId: 'boot',
      shutdownHandoff: { state: 'pending', reason: 'restart' } };
    assert.equal(engineSupervisor.shutdownHandoffReason(manifest, 42, 'boot'), 'restart');
    assert.equal(engineSupervisor.shutdownHandoffReason(manifest, 43, 'boot'), null);
    assert.equal(engineSupervisor.shutdownHandoffReason(manifest, 42, 'new-boot'), null);
    assert.equal(engineSupervisor.shutdownHandoffReason({ ...manifest, shutdownHandoff: { state: 'pending', reason: 'reboot' } }, 42, 'boot'), null);
    console.log('test-dev-runner-supervisor: host writer shape and current-instance handoff admission — PASS');
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

function testTerminalChildCleanupRequiresAllIdentityAxes() {
  const root = tempRoot('managed-child-cleanup');
  try {
    const dataDir = path.join(root, 'data');
    fs.mkdirSync(path.join(dataDir, 'runtime'), { recursive: true });
    fs.writeFileSync(path.join(dataDir, 'runtime', 'manifest.json'), JSON.stringify({
      schemaVersion: 2,
      children: [
        { id: 'match', pid: 101, startedAt: '2026-09-08T10:00:00.000Z', executable: 'C:\\bin\\llama.exe' },
        { id: 'reused', pid: 102, startedAt: '2026-09-08T10:00:00.000Z', executable: 'C:\\bin\\llama.exe' },
        { id: 'unrelated', pid: 103, startedAt: '2026-09-08T10:00:00.000Z', executable: 'C:\\bin\\llama.exe' },
        { id: 'unknown', pid: 104, startedAt: '2026-09-08T10:00:00.000Z', executable: 'C:\\bin\\llama.exe' },
      ],
    }));
    const identities = new Map([
      [101, { alive: true, startedAt: '2026-09-08T10:00:00.500Z', executable: 'c:\\bin\\llama.exe' }],
      [102, { alive: true, startedAt: '2026-09-08T11:00:00.000Z', executable: 'c:\\bin\\llama.exe' }],
      [103, { alive: true, startedAt: '2026-09-08T10:00:00.000Z', executable: 'c:\\other\\java.exe' }],
      [104, { alive: true }],
    ]);
    const killed = [];
    const outcomes = cleanupRegisteredChildrenForTerminal(
      dataDir, (pid) => identities.get(pid), (pid) => { killed.push(pid); return true; },
    );
    assert.deepEqual(killed, [101]);
    assert.deepEqual(outcomes.map((o) => o.outcome), [
      'terminated', 'identity-mismatch', 'identity-mismatch', 'unknown-identity',
    ]);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
  console.log('test-dev-runner-supervisor: terminal cleanup is identity-safe — PASS');
}

async function main() {
  const server = http.createServer((_req, res) => { res.writeHead(503); res.end(); });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}/api/health`;
  try {
    assert.equal(await checkHttp200(url, 500, true), true, '503 proves liveness');
    assert.equal(await checkHttp200(url, 500), false, 'ordinary readiness callers still require 200');
    assert.equal(await fetchJsonHttp(url, 500), null);
  } finally { await new Promise((resolve) => server.close(resolve)); }
  const sockets = new Set();
  const silent = net.createServer((socket) => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
  await new Promise((resolve) => silent.listen(0, '127.0.0.1', resolve));
  try {
    const silentUrl = `http://127.0.0.1:${silent.address().port}/api/health`;
    assert.equal(await checkHttp200(silentUrl, 50, true), false, 'accepted silence is not liveness');
    assert.equal(await fetchJsonHttp(silentUrl, 50), null, 'readiness has an absolute deadline');
  } finally {
    for (const socket of sockets) socket.destroy();
    await new Promise((resolve) => silent.close(resolve));
  }
  const status = {
    components: { head: { state: 'LIFECYCLE_STATE_READY' } }, indexAvailable: true,
    worker: { core: { indexHealthy: true } },
    readiness: { components: { indexServing: { state: 'DEGRADED', stale: false }, ai: { state: 'NOT_READY' } } },
  };
  assert.equal(essentialStatusReady(status), true, 'optional AI does not prevent stability');
  status.worker.core.indexHealthy = false;
  assert.equal(essentialStatusReady(status), false);
  status.worker.core.indexHealthy = true;
  status.readiness.components.indexServing.stale = true;
  assert.equal(essentialStatusReady(status), false);
  status.readiness.components.indexServing.stale = false;
  status.components.head.state = 'LIFECYCLE_STATE_STOPPING';
  assert.equal(essentialStatusReady(status), false);
  assert.equal(essentialStatusReady(null), false);
  console.log('test-dev-runner-supervisor: bounded liveness and essential readiness — PASS');
  testStateRecordsWhichPolicyItRanUnder();
  await testOverlappingStatePublicationsStayInTransitionOrder();
  await testWindowsReadHandlePublication();
  await testPermanentRenameFailureIsNotRetried();
  await testTerminalStateIsMirroredAndNonTerminalIsNot();
  await testHostRequestWriterAndHandoffAdmission();
  await testHandleReleaseWaitsForALiveProcess();
  await testEachIncarnationKeepsItsOwnEngineLog();
  testStopReportCarriesTheIncarnationOnlyWhenSupervised();
  testARestartIsNotAnAbandonedStack();
  testTerminalChildCleanupRequiresAllIdentityAxes();
  console.log('test-dev-runner-supervisor: ALL PASS');
}

await main();
