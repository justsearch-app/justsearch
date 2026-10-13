#!/usr/bin/env node
//
// Spec F §5.3 / §8 step 10 (S17, S18): ending a dev stack leaves nothing running and never kills a
// process outside that stack.
//
//   - `stop` ASKS the holder: the runner quits its own Engine through its loop and answers; the CLI
//     kills nothing (S18-U1).
//   - a stop during startup ends the stack; the start is published (run.json + active.json, phase
//     starting, runner identity) inside the admission lock and its lease is renewed while starting;
//     the Engine's exit is accounted as REQUESTED (S18-I2/I3).
//   - a stop whose holder is frozen still records the Engine stop as `dev-cli`: the intent before
//     acting and one REQUESTED exit when the Engine ends with its runner, so nothing is left open
//     for the next runner to record as OWNER_DIED (S15).
//   - fallback kills are identity-checked, and the birth is checked again on the handle that kills:
//     a recorded identity that no longer matches is reported `pidReused` and not killed; a record
//     without a birth never proves ownership: a process born before it is unknown, one born after it
//     a reuse, and neither is killed (S17, S18-U2).
//   - a registered helper left running or unverified makes the stop STOP_INCOMPLETE.
//   - a port owner is never killed; a foreign one makes the stop STOP_INCOMPLETE and active.json is
//     kept with `stopFailed` (S18-U3/U4).
//   - a takeover whose stop of the predecessor fails is refused (`takeover_stop_failed`) (S18-U5).
//   - an admission lock is stale only when its holder has ended, never for its age.
//   - a soft clean keeps the exit ledger.
//
// Hermetic: the state root is a temp directory (JUSTSEARCH_DEV_RUNNER_STATE_ROOT), the stacks are the
// supervisor-conformance fake Engine and a stand-in frontend, and every other process is a child this
// test spawns. Windows only (identity checks read CreationFileTimeUtc). Env MUST be set before
// requiring dev-runner.cjs (its state paths are load-time).

import fs from 'node:fs';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, '..', '..');

if (process.platform !== 'win32') {
  console.log('test-dev-runner-stop-ownership: SKIP (identity-checked stop is Windows-only)');
  process.exit(0);
}

const TMPBASE = path.join(repoRoot, 'tmp');
fs.mkdirSync(TMPBASE, { recursive: true });
const STATE = fs.mkdtempSync(path.join(TMPBASE, 'devrunner-stop-ownership-'));
process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = STATE;
process.env.JUSTSEARCH_DEV_MCP_LOG_NDJSON = '0';
delete process.env.JUSTSEARCH_PROCESS_TABLE_FIXTURE;

const require = createRequire(import.meta.url);
const { __test } = require(path.join(__dirname, 'dev-runner.cjs'));
const { acquireAdmission, stopRun, cleanDataDir } = __test;
const engineActuator = require(path.join(__dirname, 'lib', 'engine-actuator.cjs'));
const ledgerLib = require(path.join(__dirname, 'lib', 'exit-ledger.cjs'));
const DEV_RUNNER = path.join(__dirname, 'dev-runner.cjs');

const activePath = path.join(STATE, 'active.json');
const lockPath = path.join(STATE, 'active.lock.json');

const children = [];
function track(child) { children.push(child); return child; }
function sleeper() {
  return track(spawn(process.execPath, ['-e', 'setTimeout(()=>{}, 600000)'], { stdio: 'ignore' }));
}
function alive(pid) { try { process.kill(pid, 0); return true; } catch { return false; } }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function until(what, fn, timeoutMs, diag = () => '') {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    // eslint-disable-next-line no-await-in-loop
    const v = await fn();
    if (v) return v;
    assert.ok(Date.now() < deadline, `timed out waiting for ${what}\n${diag()}`);
    // eslint-disable-next-line no-await-in-loop
    await sleep(50);
  }
}
function readJson(file) { try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; } }
async function birthOf(pid) {
  const seen = (await engineActuator.lookupProcessIdentities([pid]))?.get(pid);
  assert.ok(seen?.present && seen.creationFileTime, `identity of ${pid} must be readable`);
  return seen;
}
const noCensus = async () => ({ finish: async () => ({}) });
const noGraceful = async () => ({ outcome: 'not_attempted', reason: 'test', requested: false,
  httpStatus: null, error: null, waitedMs: null });

/** A run directory under the state root with an optional run.json. */
function craftRun(runId, run, { writeRecord = true } = {}) {
  const dir = path.join(STATE, 'runs', runId);
  fs.mkdirSync(dir, { recursive: true });
  const runPath = path.join(dir, 'run.json');
  const full = { schemaVersion: 1, runId, apiPortActual: 0, uiPortActual: 0, dataDir: null, ...run };
  if (writeRecord) fs.writeFileSync(runPath, JSON.stringify(full));
  return { run: full, runPath, dir };
}

function cli(args, env, timeoutMs = 60_000) {
  const res = spawnSync(process.execPath, [DEV_RUNNER, ...args, '--json'], { cwd: repoRoot, env, encoding: 'utf8', timeout: timeoutMs });
  const line = String(res.stdout || '').trim().split(/\r?\n/).filter(Boolean).pop() || '{}';
  let json;
  try { json = JSON.parse(line); } catch { json = { unparsed: res.stdout, stderr: res.stderr }; }
  return { status: res.status, json, stderr: res.stderr };
}

/** A harness stack (fake Engine + stand-in frontend) started through the real `dev-runner start`. */
function startHarnessStack(name, plan) {
  const root = fs.mkdtempSync(path.join(STATE, `${name}-`));
  const dataDir = path.join(root, 'data');
  fs.mkdirSync(path.join(dataDir, 'runtime'), { recursive: true });
  const planPath = path.join(root, 'plan.json');
  fs.writeFileSync(planPath, JSON.stringify(plan));
  const env = {
    ...process.env,
    JUSTSEARCH_SUPERVISOR_HARNESS: '1',
    JUSTSEARCH_SUPERVISOR_STABILITY_WINDOW_MS: '600000',
    JUSTSEARCH_DEV_RUNNER_STATE_ROOT: STATE,
    JUSTSEARCH_DEV_LEASE_RENEW_MS: '300',
    JUSTSEARCH_DEV_RUNNER_ENGINE_COMMAND: JSON.stringify([
      process.execPath, path.join(repoRoot, 'scripts', 'supervisor-conformance', 'fake-engine.mjs')]),
    JUSTSEARCH_DEV_RUNNER_FRONTEND_COMMAND: JSON.stringify([process.execPath, '-e', 'setInterval(() => {}, 60000)']),
    JUSTSEARCH_FAKE_ENGINE_PLAN: planPath,
    JUSTSEARCH_DEV_RUNNER_BACKEND_PORT_TIMEOUT_MS: '60000',
    JUSTSEARCH_DEV_RUNNER_BACKEND_READY_TIMEOUT_MS: '60000',
    CI: '',
  };
  const child = track(spawn(process.execPath, [DEV_RUNNER, 'start', '--json', '--skip-build', '--clean', 'none',
    '--api-port', '0', '--ui-port', '5598', '--data-dir', dataDir, '--session-id', 'test-stop-ownership'],
  { cwd: repoRoot, env, stdio: ['ignore', 'pipe', 'pipe'] }));
  let out = '';
  child.stdout.on('data', (b) => { out += b.toString(); });
  child.stderr.on('data', (b) => { out += b.toString(); });
  const exited = new Promise((resolve) => child.on('exit', (code) => resolve(code)));
  return { child, env, dataDir, exited, output: () => out };
}

/** Freezes a process (NtSuspendProcess): it keeps running in name and answers nothing. */
function suspend(pid) {
  const script = "$s='[DllImport(\"ntdll.dll\")] public static extern int NtSuspendProcess(IntPtr h);';"
    + 'Add-Type -MemberDefinition $s -Name Nt -Namespace StopOwnershipFreeze;'
    + `$p=[Diagnostics.Process]::GetProcessById(${pid}); [StopOwnershipFreeze.Nt]::NtSuspendProcess($p.Handle)`;
  const res = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script],
    { encoding: 'utf8', timeout: 60_000 });
  assert.equal(String(res.stdout).trim(), '0', `NtSuspendProcess(${pid}): ${res.stdout}${res.stderr}`);
}

function engineExits(dataDir) {
  return ledgerLib.readLedger(ledgerLib.ledgerDirectory(dataDir), 'engine').records
    .filter((r) => r.type === 'engine-exit');
}

const tests = [
  ['stop asks the holder: the runner quits its own stack, nothing is left running and the CLI kills nothing (S18-U1)', async () => {
    const stack = startHarnessStack('ask', { incarnations: [{ mode: 'honour' }] });
    try {
      // Ready: active.json names the run and its record has the frontend (spawned after readiness).
      const { active, run } = await until('a running stack', () => {
        const a = readJson(activePath);
        const r = a?.runId ? readJson(path.join(STATE, 'runs', a.runId, 'run.json')) : null;
        return a?.phase !== 'starting' && Number.isInteger(r?.pids?.frontendRootPid) ? { active: a, run: r } : null;
      }, 40_000, stack.output);
      const runDir = path.join(STATE, 'runs', active.runId);
      const pids = [run.pids.runnerPid, run.pids.backendRootPid, run.pids.frontendRootPid];
      assert.ok(pids.every((p) => Number.isInteger(p) && alive(p)), `all three processes run: ${pids}`);

      const r = cli(['stop', '--session-id', 'test-stop-ownership'], stack.env);
      assert.equal(r.json.outcome, 'STOPPED', JSON.stringify(r.json));
      const request = readJson(path.join(runDir, 'control', 'stop-request.json'));
      assert.equal(request?.reason, 'quit', 'the stop wrote a stop request for the holder');
      const answer = readJson(path.join(runDir, 'control', 'stop-result.json'));
      assert.equal(answer?.requestId, request.requestId);
      assert.equal(answer?.outcome, 'stopped', 'the runner answered it');
      assert.deepEqual(r.json.killedPids, [], 'the CLI killed nothing: the runner stopped its own stack');
      await until('every process of the stack gone', () => pids.every((p) => !alive(p)), 10_000);
      assert.ok(!fs.existsSync(activePath), 'active.json is removed with a stack verified gone');
      const exits = engineExits(stack.dataDir);
      assert.equal(exits.length, 1, JSON.stringify(exits));
      assert.equal(exits[0].line.exitClass, 'REQUESTED');
      assert.equal(exits[0].line.reason, 'QUIT');
    } finally {
      try { stack.child.kill(); } catch { /* gone */ }
    }
  }],

  ['a stop during startup ends the stack: the start is published while starting, its lease is renewed, and the Engine exit is REQUESTED (S18-I2/I3)', async () => {
    const stack = startHarnessStack('starting', { incarnations: [{ mode: 'slow-start', slowStartMs: 45_000 }] });
    try {
      const first = await until('active.json in phase starting', () => {
        const a = readJson(activePath);
        return a?.phase === 'starting' ? a : null;
      }, 30_000, stack.output);
      assert.ok(Number.isInteger(first.runner?.pid) && /^\d+$/.test(String(first.runner?.creationFileTimeUtc)),
        `the starting record names the runner's identity: ${JSON.stringify(first.runner)}`);
      const runDir = path.join(STATE, 'runs', first.runId);
      const starting = readJson(path.join(runDir, 'run.json'));
      assert.equal(starting?.phase, 'starting');
      await until('the starting lease renewed', () => {
        const a = readJson(activePath);
        return a?.phase === 'starting' && a.lease?.sequence > first.lease.sequence;
      }, 5_000);
      const run = await until('the Engine recorded', () => {
        const r = readJson(path.join(runDir, 'run.json'));
        return Number.isInteger(r?.pids?.backendRootPid) ? r : null;
      }, 20_000, stack.output);
      const pids = [run.pids.runnerPid, run.pids.backendRootPid];

      const r = cli(['stop', '--session-id', 'test-stop-ownership'], stack.env);
      assert.equal(r.json.outcome, 'STOPPED', JSON.stringify(r.json));
      assert.deepEqual(r.json.killedPids, [], 'the runner stopped its own starting stack');
      const code = await Promise.race([stack.exited, sleep(15_000).then(() => 'still running')]);
      assert.notEqual(code, 'still running', 'the start process ended');
      assert.notEqual(code, 0, 'a start stopped before ready is not a successful start');
      await until('every process of the stack gone', () => pids.every((p) => !alive(p)), 10_000);
      assert.ok(!fs.existsSync(activePath), 'active.json is gone');
      const exits = engineExits(stack.dataDir);
      assert.equal(exits.length, 1, JSON.stringify(exits));
      assert.equal(exits[0].line.exitClass, 'REQUESTED');
      assert.equal(exits[0].line.reason, 'QUIT');
      assert.equal(exits[0].line.phase, 'STOPPING');
    } finally {
      try { stack.child.kill(); } catch { /* gone */ }
    }
  }],

  ['a stop whose holder is frozen still records the Engine stop: the intent before acting, one REQUESTED exit when the Engine ends with its runner, nothing left open (Spec F §5.3, S15)', async () => {
    // The Engine ignores the HTTP quit, so it can only end with the runner the fallback kills.
    const stack = startHarnessStack('frozen', { incarnations: [{ mode: 'ignore-shutdown' }] });
    let frozen = null;
    try {
      const { active, run } = await until('a running stack', () => {
        const a = readJson(activePath);
        const r = a?.runId ? readJson(path.join(STATE, 'runs', a.runId, 'run.json')) : null;
        return a?.phase !== 'starting' && Number.isInteger(r?.pids?.frontendRootPid) ? { active: a, run: r } : null;
      }, 40_000, stack.output);
      const { runnerPid, backendRootPid: enginePid, frontendRootPid } = run.pids;
      frozen = run.identities.runner;
      suspend(runnerPid);

      const r = cli(['stop', '--session-id', 'test-stop-ownership'], stack.env, 120_000);
      assert.equal(r.json.outcome, 'STOPPED', JSON.stringify(r.json));
      assert.ok(r.json.killedPids.includes(runnerPid), `the fallback killed the silent runner: ${JSON.stringify(r.json)}`);
      assert.ok(!r.json.killedPids.includes(enginePid), 'the Engine ended with its runner, not by a kill of its own');
      await until('every process of the stack gone',
        () => [runnerPid, enginePid, frontendRootPid].every((p) => !alive(p)), 10_000);

      const records = ledgerLib.readLedger(ledgerLib.ledgerDirectory(stack.dataDir), 'engine').records;
      const mine = records.filter((rec) => rec.line?.incarnation?.session === active.runId);
      const lines = JSON.stringify(mine.map((rec) => rec.line));
      assert.ok(mine.some((rec) => rec.type === 'engine-start'), lines);
      const exits = mine.filter((rec) => rec.type === 'engine-exit');
      assert.equal(exits.length, 1, `one exit record for the stopped Engine: ${lines}`);
      const exit = exits[0].line;
      assert.equal(exit.writer.role, 'dev-cli');
      assert.equal(exit.exitClass, 'REQUESTED', lines);
      assert.equal(exit.reason, 'QUIT');
      assert.equal(exit.forced, true);
      assert.equal(exit.code, 0, 'a process ended by its job closing exits 0');
      assert.equal(exit.codeDistinctive, false);
      assert.equal(exit.phase, 'STOPPING');
      const intents = mine.filter((rec) => rec.type === 'engine-intent' && rec.line.writer.role === 'dev-cli');
      assert.deepEqual(intents.map((rec) => [rec.line.reason, rec.line.mode, rec.line.channel]),
        [['QUIT', 'COOPERATIVE', 'HTTP_SHUTDOWN'], ['QUIT', 'NOW', 'TERMINATE']], lines);
      assert.ok(mine.indexOf(intents[1]) < mine.indexOf(exits[0]), 'the intents precede the exit');
      assert.ok(Date.parse(intents[1].line.requestedAt) <= Date.parse(exit.terminateCalledAt),
        `the forced stop is recorded before the runner is killed: ${lines}`);
      assert.deepEqual(engineActuator.openEngineStarts(records), [],
        'nothing is left open for the next runner to record as OWNER_DIED');
    } finally {
      if (frozen) engineActuator.terminateWithCodeSync(frozen.pid, frozen.creationFileTimeUtc, 1);
      try { stack.child.kill(); } catch { /* gone */ }
    }
  }],

  ['the stop report reads every record of the stop: the Engine exit the CLI writes from the census observation is the one the report classifies (Spec F §6.3, S15)', async () => {
    // A runner that does not answer and an Engine that ignores it: real processes, the real census
    // (stop-exit-census.ps1 holds the Engine's handle) and the real identity-checked terminate.
    const runner = sleeper();
    const engine = sleeper();
    const [seenRunner, seenEngine] = [await birthOf(runner.pid), await birthOf(engine.pid)];
    const dataDir = path.join(STATE, 'report-reads-ledger');
    fs.mkdirSync(path.join(dataDir, 'runtime'), { recursive: true });
    const runId = 'run-REPORT';
    ledgerLib.createLedgerWriter({ ledgerDir: ledgerLib.ledgerDirectory(dataDir), family: 'engine',
      writer: { role: 'dev-runner', pid: runner.pid, startedAt: Date.now() } })
      .append('engine-start', { incarnation: { session: runId, n: 1 }, pid: engine.pid, creationFileTime: seenEngine.creationFileTime,
        executable: seenEngine.executable, processStartedAt: new Date(), cause: 'START', supervisorKind: 'dev-runner' });
    const identity = (p, seen) => ({ pid: p.pid, creationFileTimeUtc: seen.creationFileTime, executable: seen.executable });
    const target = craftRun(runId, { dataDir, incarnation: 1, control: { stopRequest: 1 },
      pids: { runnerPid: runner.pid, backendRootPid: engine.pid, frontendRootPid: null },
      identities: { runner: identity(runner, seenRunner), backend: identity(engine, seenEngine), frontend: null } });
    const res = await stopRun({ runId }, {
      resolveTarget: async () => target, gracefulShutdown: noGraceful, askRunner: async () => ({ outcome: 'no_answer' }),
      portWaitMs: 500 });
    await until('runner and Engine gone', () => !alive(runner.pid) && !alive(engine.pid), 10_000);
    assert.ok(res.killedPids.includes(engine.pid), JSON.stringify(res));
    const exits = engineExits(dataDir);
    assert.deepEqual(exits.map((r) => [r.line.exitClass, r.line.code, r.line.observedBy]),
      [['REQUESTED', ledgerLib.JS_ENGINE_STOP, 'census']], 'the ledger has the Engine exit, as observed by the census');
    const report = readJson(path.join(target.dir, 'stop-report.json'));
    const row = report?.processExits?.find((e) => e.role === 'engine' && e.pid === engine.pid);
    assert.deepEqual([row?.ledgerClass, row?.ledgerCode, row?.expectedTermination],
      ['REQUESTED', ledgerLib.JS_ENGINE_STOP, true], `the report classifies the exit the stop wrote: ${JSON.stringify(report?.processExits)}`);
    assert.deepEqual((report.exitAccountingGaps ?? []).filter((g) => g.pid === engine.pid), [],
      JSON.stringify(report.exitAccountingGaps));
    assert.equal(report.ledgerSummary?.requested, 1, JSON.stringify(report.ledgerSummary));
  }],

  ['the dev-cli Engine exit takes the census code when there is one: a crash during the stop stays UNEXPECTED, an unobserved quit is UNKNOWN, one exit per incarnation (S15)', async () => {
    const dataDir = path.join(STATE, 'cli-recorder');
    const exitOf = (n, input) => {
      const r = engineActuator.createCliEngineRecorder({ dataDir, incarnation: { session: 'run-CLI', n } }).exit(input);
      return [r.written, r.exitClass, r.code, r.observedBy];
    };
    const crash = -1073741819;
    assert.deepEqual(exitOf(1, { endedBy: 'terminate' }), [true, 'REQUESTED', ledgerLib.JS_ENGINE_STOP, 'dev-cli']);
    assert.deepEqual(exitOf(2, { endedBy: 'runner', observed: { exitCode: 0, atMs: Date.now() } }),
      [true, 'REQUESTED', 0, 'census']);
    assert.deepEqual(exitOf(3, { endedBy: 'runner', observed: { exitCode: crash, atMs: Date.now() } }),
      [true, 'UNEXPECTED', crash, 'census']);
    assert.deepEqual(exitOf(4, { endedBy: 'cooperative' }), [true, 'UNKNOWN', null, 'dev-cli']);
    assert.deepEqual(exitOf(5, { endedBy: 'cooperative', observed: { exitCode: 1, atMs: Date.now() } }),
      [true, 'UNEXPECTED', 1, 'census']);
    assert.deepEqual(exitOf(6, { endedBy: 'taskkill' }), [true, 'REQUESTED', 1, 'dev-cli']);
    assert.deepEqual(exitOf(1, { endedBy: 'terminate' }), [false, undefined, undefined, undefined],
      'an incarnation with an exit record gets no second one');
    const exits = engineExits(dataDir);
    assert.deepEqual(exits.map((r) => r.line.incarnation.n), [1, 2, 3, 4, 5, 6]);
    assert.ok(exits.every((r) => r.line.writer.role === 'dev-cli'));
    assert.deepEqual(exits.map((r) => r.line.forced), [true, true, true, false, false, true]);
    assert.deepEqual(exits.map((r) => r.line.codeDistinctive), [true, false, true, false, true, false]);
    assert.ok(exits[1].line.exitedAt && exits[3].line.exitedAt === null, 'the exit time is known only when observed');
  }],

  ['a recorded identity that no longer matches is reported pidReused and not killed (S18-U2)', async () => {
    const other = sleeper();
    const seen = await birthOf(other.pid);
    const target = craftRun('run-REUSED', {
      pids: { runnerPid: null, backendRootPid: null, frontendRootPid: other.pid },
      identities: { runner: null, backend: null,
        // The recorded frontend was born one hour before the process that now has its pid.
        frontend: { pid: other.pid, creationFileTimeUtc: String(BigInt(seen.creationFileTime) - 36_000_000_000n),
          executable: seen.executable } },
      control: { stopRequest: 1 },
    });
    const res = await stopRun({ runId: 'run-REUSED' }, {
      resolveTarget: async () => target, gracefulShutdown: noGraceful, census: noCensus });
    await sleep(300);
    assert.ok(alive(other.pid), 'a process whose identity does not match the record is never killed');
    assert.ok(!res.killedPids.includes(other.pid));
    const report = readJson(path.join(target.dir, 'stop-report.json'));
    const row = report?.fallback?.processes?.find((p) => p.role === 'frontend');
    assert.equal(row?.pidReused, true, JSON.stringify(report?.fallback));
  }],

  ['fallbackStopRechecksBirthOnTerminationHandle: a pid that names another process by the time the fallback acts is never killed, for the runner, the Engine, the frontend and a registered helper (Spec F §5.3)', async () => {
    // A deterministic interleaving of PID reuse: the inspection sees each recorded birth (the recorded
    // processes were alive then); when the stop acts, the pids name these processes, born later.
    const runner = sleeper();
    const engine = sleeper();
    const frontend = sleeper();
    const helper = sleeper();
    const seen = new Map();
    for (const p of [runner, engine, frontend, helper]) seen.set(p.pid, await birthOf(p.pid));
    const recordedBirth = (pid) => String(BigInt(seen.get(pid).creationFileTime) - 36_000_000_000n);
    const identity = (p) => ({ pid: p.pid, creationFileTimeUtc: recordedBirth(p.pid), executable: seen.get(p.pid).executable });
    // The helper's registration names its start an hour before this process's (milliseconds, as the
    // manifest records it); its inspection still reads the registered identity.
    const dataDir = path.join(STATE, 'reused-at-kill');
    fs.mkdirSync(path.join(dataDir, 'runtime'), { recursive: true });
    const helperStartedAt = new Date(engineActuator.fileTimeToMs(String(seen.get(helper.pid).creationFileTime)) - 3_600_000).toISOString();
    fs.writeFileSync(path.join(dataDir, 'runtime', 'manifest.json'), JSON.stringify({ children: [
      { id: 'llama-server', pid: helper.pid, executable: seen.get(helper.pid).executable, startedAt: helperStartedAt }] }));
    const target = craftRun('run-REUSED-AT-KILL', {
      dataDir,
      control: { stopRequest: 1 },
      pids: { runnerPid: runner.pid, backendRootPid: engine.pid, frontendRootPid: frontend.pid },
      identities: { runner: identity(runner), backend: identity(engine), frontend: identity(frontend) },
    });
    const inspected = new Set();
    const res = await stopRun({ runId: 'run-REUSED-AT-KILL' }, {
      resolveTarget: async () => target, gracefulShutdown: noGraceful, census: noCensus,
      askRunner: async () => ({ outcome: 'no_answer' }),
      lookupIdentities: async (pids) => new Map(pids.map((pid) => {
        inspected.add(pid);
        return [pid, { present: true, creationFileTime: recordedBirth(pid), executable: seen.get(pid).executable }];
      })),
      // The distinctive terminate did not run, so the Engine reaches the fallback kill.
      terminateEngine: async () => ({ ok: false, outcome: 'failed', status: 4 }),
      inspectChild: (pid) => {
        if (pid !== helper.pid) return null;
        inspected.add(pid);
        return { alive: true, executable: seen.get(pid).executable, startedAt: helperStartedAt };
      },
      portWaitMs: 200 });
    await sleep(500);
    for (const [role, p] of [['runner', runner], ['backend', engine], ['frontend', frontend], ['helper', helper]]) {
      assert.ok(inspected.has(p.pid), `the ${role} was inspected as the recorded process`);
      assert.ok(alive(p.pid), `the ${role} pid names another process when the stop acts: it is never killed ${JSON.stringify(res)}`);
      assert.ok(!res.killedPids.includes(p.pid), `the ${role} is not reported killed: ${JSON.stringify(res.killedPids)}`);
    }
    assert.equal(res.outcome, 'STOP_INCOMPLETE', JSON.stringify(res));
  }],

  ['a verified tree kill ends the recorded process and its descendants, each through a handle checked against its birth (Spec F §5.3)', async () => {
    // The descendant is detached: outside the job libuv gives its parent's children, it does not end
    // with its parent, so only the tree kill ends it (it ends itself after a minute regardless).
    const parent = track(spawn(process.execPath, ['-e',
      "const c=require('child_process').spawn(process.execPath,['-e','setTimeout(()=>{},60000)'],{stdio:'ignore',detached:true});console.log(c.pid);setTimeout(()=>{},600000)"],
    { stdio: ['ignore', 'pipe', 'ignore'] }));
    const childPid = await new Promise((resolve) => parent.stdout.once('data', (b) => resolve(Number(String(b).trim()))));
    assert.ok(childPid > 0);
    const parentBirth = (await birthOf(parent.pid)).creationFileTime;
    const result = await engineActuator.terminateWithCode(parent.pid, parentBirth, engineActuator.NON_DISTINCTIVE_KILL_CODE, { tree: true });
    assert.equal(result.ok, true, JSON.stringify(result));
    await until('the recorded process and its descendant gone', () => !alive(parent.pid) && !alive(childPid), 10_000);
  }],

  ['missingBirthNeverEstablishesOwnership: a record without a birth (legacy, or written before discovery) never makes a process born before it the run\'s: unknown, not killed, the stop incomplete; one born after it is a reuse (S17, S18-U2)', async () => {
    const before = sleeper();
    await birthOf(before.pid);
    await sleep(50);
    const legacy = craftRun('run-LEGACY', { apiPortActual: 23456,
      pids: { runnerPid: null, backendRootPid: before.pid, frontendRootPid: before.pid } });
    fs.writeFileSync(activePath, JSON.stringify({ kind: 'backend-shared-lease.v1', schemaVersion: 1, runId: 'run-LEGACY' }));
    let shutdowns = 0;
    const res1 = await stopRun({ runId: 'run-LEGACY' }, {
      resolveTarget: async () => legacy, gracefulShutdown: async () => { shutdowns++; return noGraceful(); },
      census: noCensus, portOwner: async () => ({ closed: true }), portWaitMs: 200 });
    assert.equal(shutdowns, 0, 'a live PID and an API port without a recorded birth never license HTTP shutdown');
    await sleep(300);
    assert.ok(alive(before.pid), 'a process the record does not identify by its birth is never killed');
    assert.ok(!res1.killedPids.includes(before.pid), JSON.stringify(res1));
    const row = readJson(path.join(legacy.dir, 'stop-report.json'))?.fallback?.processes?.find((p) => p.role === 'frontend');
    assert.equal(row?.identity, 'unknown', JSON.stringify(row));
    assert.equal(res1.outcome, 'STOP_INCOMPLETE', JSON.stringify(res1));
    const active = readJson(activePath);
    assert.equal(active?.runId, 'run-LEGACY', 'active.json is kept for a stack not verified gone');
    assert.ok(active?.stopFailed?.reason, JSON.stringify(active));
    fs.rmSync(activePath, { force: true });

    const later = craftRun('run-LEGACY2', { pids: { runnerPid: null, backendRootPid: null, frontendRootPid: null } });
    const after = sleeper();
    await birthOf(after.pid);
    later.run.pids.frontendRootPid = after.pid; // the pid the record names, but born after it was written
    const res2 = await stopRun({ runId: 'run-LEGACY2' }, {
      resolveTarget: async () => later, gracefulShutdown: noGraceful, census: noCensus });
    await sleep(300);
    assert.ok(alive(after.pid), 'a process born after the legacy record was written is not killed');
    assert.ok(!res2.killedPids.includes(after.pid));
  }],

  ['a stop whose registered helper survives its termination, or cannot be identified, is incomplete: active state and data stay (Spec F §5.3)', async () => {
    const executable = path.join(STATE, 'llama-server.exe');
    const startedAt = '2026-10-03T14:00:00.123Z';
    for (const [name, inspectChild, terminateChild, outcome] of [
      // Roots and ports gone; the helper is still the registered process after its termination.
      ['survives', () => ({ alive: true, executable, startedAt }), () => true, 'termination-failed'],
      // Roots and ports gone; the helper's identity cannot be read.
      ['unknown', () => null, () => assert.fail('a helper without a verified identity is never killed'), 'unknown-identity'],
    ]) {
      const runId = `run-HELPER-${name}`;
      const dataDir = path.join(STATE, `helper-${name}`);
      fs.mkdirSync(path.join(dataDir, 'runtime'), { recursive: true });
      const manifestPath = path.join(dataDir, 'runtime', 'manifest.json');
      fs.writeFileSync(manifestPath, JSON.stringify({ children: [{ id: 'llama-server', pid: 22004, executable, startedAt }] }));
      const target = craftRun(runId, { dataDir, pids: { runnerPid: null, backendRootPid: null, frontendRootPid: null } });
      fs.writeFileSync(activePath, JSON.stringify({ kind: 'backend-shared-lease.v1', schemaVersion: 1, runId }));
      const res = await stopRun({ runId }, {
        resolveTarget: async () => target, gracefulShutdown: noGraceful, census: noCensus,
        inspectChild, terminateChild, portWaitMs: 200 });
      assert.deepEqual(res.childCleanup.map((c) => c.outcome), [outcome], JSON.stringify(res.childCleanup));
      assert.equal(res.portsClosed, true, 'the ports are closed');
      assert.equal(res.outcome, 'STOP_INCOMPLETE', `a helper left alive or unverified is not a stopped stack: ${JSON.stringify(res)}`);
      const active = readJson(activePath);
      assert.equal(active?.runId, runId, 'active.json is kept: takeover and data cleanup see a stack that may still run');
      assert.ok(active?.stopFailed?.reason, JSON.stringify(active));
      assert.ok(fs.existsSync(manifestPath), 'the data stays');
      fs.rmSync(activePath, { force: true });
    }
  }],

  ['a port held outside the run is never killed: STOP_INCOMPLETE with foreignPortOwner (S18-U3)', async () => {
    const server = track(spawn(process.execPath, ['-e',
      "const s=require('net').createServer();s.listen(0,'127.0.0.1',()=>console.log(s.address().port));setTimeout(()=>{},600000)"],
    { stdio: ['ignore', 'pipe', 'ignore'] }));
    const port = await new Promise((resolve) => server.stdout.once('data', (b) => resolve(Number(String(b).trim()))));
    assert.ok(port > 0);
    const target = craftRun('run-PORT', { apiPortActual: port, uiPortActual: 0,
      pids: { runnerPid: null, backendRootPid: null, frontendRootPid: null } });
    fs.writeFileSync(activePath, JSON.stringify({ kind: 'backend-shared-lease.v1', schemaVersion: 1, runId: 'run-PORT' }));
    const res = await stopRun({ runId: 'run-PORT' }, {
      resolveTarget: async () => target, gracefulShutdown: noGraceful, census: noCensus, portWaitMs: 500 });
    await sleep(300);
    assert.ok(alive(server.pid), 'the port owner outside the run is never killed');
    assert.equal(res.outcome, 'STOP_INCOMPLETE', JSON.stringify(res));
    assert.equal(res.foreignPortOwner?.pid, server.pid);
    assert.equal(res.foreignPortOwner?.ownedByRun, false);
    // Spec F §5.3: no recorded process is left and the port is owned by no run process, so the
    // run's own record goes; the foreign listener is reported, not killed.
    assert.ok(!fs.existsSync(activePath), 'the run itself is verified gone');
  }],

  ['an identity lookup failure reads as unknown, never dead: nothing is killed and active.json is kept with stopFailed (S17, S18-U4)', async () => {
    const proc = sleeper();
    await birthOf(proc.pid);
    await sleep(50);
    const target = craftRun('run-UNKNOWN', { pids: { runnerPid: null, backendRootPid: null, frontendRootPid: proc.pid } });
    fs.writeFileSync(activePath, JSON.stringify({ kind: 'backend-shared-lease.v1', schemaVersion: 1, runId: 'run-UNKNOWN' }));
    const res = await stopRun({ runId: 'run-UNKNOWN' }, {
      resolveTarget: async () => target, gracefulShutdown: noGraceful, census: noCensus,
      lookupIdentities: async () => null });
    await sleep(300);
    assert.ok(alive(proc.pid), 'a process whose identity could not be read is not killed');
    assert.equal(res.outcome, 'STOP_INCOMPLETE', JSON.stringify(res));
    const active = readJson(activePath);
    assert.equal(active?.runId, 'run-UNKNOWN', 'active.json is kept for a stop that did not verify the stack gone');
    assert.ok(active?.stopFailed?.at && active.stopFailed.reason, JSON.stringify(active));
    fs.rmSync(activePath, { force: true });
  }],

  ['a takeover whose stop of the predecessor fails is refused (takeover_stop_failed); nothing is published (S18-U5)', async () => {
    craftRun('run-PRED', { pids: { runnerPid: 999_999, backendRootPid: null, frontendRootPid: null } });
    const predecessor = {
      kind: 'backend-shared-lease.v1', schemaVersion: 1, runId: 'run-PRED', runPath: 'x', launcherFamily: 'dev-runner',
      mode: 'shared', holder: { source: 'codex', agentSessionId: 'other' }, takeoverPolicy: 'warn', ownershipEpoch: 7,
      lease: { durationSec: 1, renewedAt: new Date(Date.now() - 120_000).toISOString(),
        expiresAt: new Date(Date.now() - 60_000).toISOString(), sequence: 3 },
    };
    fs.writeFileSync(activePath, JSON.stringify(predecessor));
    let published = false;
    const caller = { harness: 'claude', owner: { harness: 'claude', pid: 900_301, creationTime: '134000000000900301',
      key: 'claude-900301-134000000000900301' }, sessionId: 'sess-TAKEOVER', source: 'process-ancestry' };
    const r = await acquireAdmission({
      takeover: 'force', sessionId: caller.sessionId, callerIdentity: caller,
      publish: async () => { published = true; },
      stop: async () => ({ ok: true, outcome: 'STOP_INCOMPLETE', errors: ['backend pid 1 is still running after the stop'] }),
    });
    assert.equal(r.action, 'conflict', JSON.stringify(r));
    assert.equal(r.reason, 'takeover_stop_failed');
    assert.equal(r.stop?.outcome, 'STOP_INCOMPLETE');
    assert.equal(published, false, 'nothing is published over a stack that may still run');
    assert.equal(readJson(activePath)?.runId, 'run-PRED');
    assert.ok(!fs.existsSync(lockPath), 'the admission lock is released');
    fs.rmSync(activePath, { force: true });
  }],

  ['the start is published inside the admission lock, with the ownership epoch read there', async () => {
    fs.rmSync(activePath, { force: true });
    const caller = { harness: 'claude', owner: { harness: 'claude', pid: 900_305, creationTime: '134000000000900305',
      key: 'claude-900305-134000000000900305' }, sessionId: 'sess-PUBLISH', source: 'process-ancestry' };
    const calls = [];
    const r = await acquireAdmission({
      takeover: 'deny', sessionId: caller.sessionId, callerIdentity: caller,
      publish: async ({ ownershipEpoch }) => { calls.push({ ownershipEpoch, locked: fs.existsSync(lockPath) }); },
    });
    assert.equal(r.action, 'proceed', JSON.stringify(r));
    assert.deepEqual(calls, [{ ownershipEpoch: 1, locked: true }]);
    assert.equal(r.ownershipEpoch, 1);
    assert.ok(!fs.existsSync(lockPath));
  }],

  ['an admission lock is stale only when its holder has ended, never for its age', async () => {
    fs.rmSync(activePath, { force: true });
    const holder = sleeper();
    const seen = await birthOf(holder.pid);
    const oldLock = JSON.stringify({ pid: holder.pid, creationFileTimeUtc: seen.creationFileTime,
      acquiredAt: new Date(Date.now() - 200_000).toISOString() });
    fs.writeFileSync(lockPath, oldLock);
    const caller = { harness: 'claude', owner: { harness: 'claude', pid: 900_309, creationTime: '134000000000900309',
      key: 'claude-900309-134000000000900309' }, sessionId: 'sess-LOCK', source: 'process-ancestry' };
    const slow = await acquireAdmission({ takeover: 'deny', sessionId: caller.sessionId, callerIdentity: caller });
    assert.equal(slow.action, 'conflict', JSON.stringify(slow));
    assert.equal(slow.reason, 'lock_holder_slow');
    assert.equal(fs.readFileSync(lockPath, 'utf8'), oldLock, 'a live holder\'s lock is never stolen');

    holder.kill();
    await until('the holder gone', () => !alive(holder.pid), 5_000);
    const freed = await acquireAdmission({ takeover: 'deny', sessionId: caller.sessionId, callerIdentity: caller });
    assert.equal(freed.action, 'proceed', JSON.stringify(freed));
    assert.ok(!fs.existsSync(lockPath));
  }],

  ['a soft clean keeps the exit ledger and removes the rest of runtime/', async () => {
    const dataDir = path.join(STATE, 'soft-clean');
    const ledgerDir = path.join(dataDir, 'runtime', 'exit-ledger');
    fs.mkdirSync(ledgerDir, { recursive: true });
    fs.writeFileSync(path.join(ledgerDir, 'engine.v1.jsonl'), '{"v":1}\n');
    fs.writeFileSync(path.join(dataDir, 'runtime', 'manifest.json'), '{}');
    await cleanDataDir(dataDir, 'soft');
    assert.ok(fs.existsSync(path.join(ledgerDir, 'engine.v1.jsonl')), 'the exit ledger survives a soft clean');
    assert.ok(!fs.existsSync(path.join(dataDir, 'runtime', 'manifest.json')), 'the rest of runtime/ is removed');
  }],
];

let pass = 0;
let fail = 0;
const only = process.env.STOP_OWNERSHIP_ONLY ? new RegExp(process.env.STOP_OWNERSHIP_ONLY) : null;
for (const [name, fn] of tests) {
  if (only && !only.test(name)) continue;
  try {
    // eslint-disable-next-line no-await-in-loop
    await fn();
    console.log(`  PASS  ${name}`);
    pass++;
  } catch (e) {
    console.error(`  FAIL  ${name}: ${e.message}`);
    fail++;
  }
}
for (const c of children) { try { c.kill(); } catch { /* gone */ } }
await sleep(300);
try { fs.rmSync(STATE, { recursive: true, force: true, maxRetries: 5 }); } catch { /* windows handles */ }
console.log(`test-dev-runner-stop-ownership: ${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
