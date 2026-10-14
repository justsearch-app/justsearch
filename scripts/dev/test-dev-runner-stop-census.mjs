// A stop's exit census after a long scripted-agent run (item 6 of the quiet-machine measurement):
// a recorded identity whose exit is in the Engine's exit ledger is gone, even when its PID now
// names a process whose birth cannot be read; a retried stop keeps the earlier census and adds to
// it (and the earlier stop report), and completes from the ledger once the Engine is gone; the
// census reads each ledger file
// once per stop, so a stop over a 17 MB ledger stays well inside the 60 s stop limit.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import cp from 'node:child_process';
import { EventEmitter } from 'node:events';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'stop-census-'));
process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = path.join(scratch, 'state');
// The owned-process census a stop compares with the ledger: here it observed nothing.
const scope = path.join(scratch, 'scope.json');
fs.writeFileSync(scope, JSON.stringify({ processes: [] }));
process.env.JUSTSEARCH_EXIT_CENSUS_SCOPE = scope;
const census = require('./lib/stop-exit-census.cjs');
const ledgerLib = require('./lib/exit-ledger.cjs');
const jobRun = require('./lib/job-run.cjs');
const api = require('./dev-runner.cjs').__test;
const windows = process.platform === 'win32';
const EPOCH = 116444736000000000n;
const iso7 = (ft) => {
  const ms = Number((BigInt(ft) - EPOCH) / 10000n);
  return `${new Date(ms).toISOString().slice(0, 19)}.${String((BigInt(ft) - EPOCH) % 10000000n).padStart(7, '0')}Z`;
};
const isoMs = (ft) => new Date(Number((BigInt(ft) - EPOCH) / 10000n)).toISOString();
let dirs = 0;
const freshDir = (name) => {
  const dir = path.join(scratch, `${name}-${++dirs}`);
  fs.mkdirSync(path.join(dir, 'runtime'), { recursive: true });
  return dir;
};
test.after(() => fs.rmSync(scratch, { recursive: true, force: true }));

// Windows PIDs are multiples of 4, so a PID that is not names no process, now or later.
const noProcess = (pid, creationFileTimeUtc) => {
  assert.notEqual(pid % 4, 0);
  return { pid, creationFileTimeUtc };
};

/**
 * A running process whose birth this user cannot read (OpenProcess is refused), found the way the
 * stop's own probe meets it: job-run.cs Survivors reports exactly those for a birth no process has.
 * Only query rights are asked for; nothing is ended.
 */
function unreadablePid() {
  const script = [
    `Add-Type -Path '${path.join(root, 'scripts/dev/lib/job-run.cs').replaceAll("'", "''")}'`,
    '$p = @(Get-Process | ForEach-Object { $_.Id } | Where-Object { $_ -gt 4 })',
    '$b = [long[]]@($p | ForEach-Object { 1 })',
    '[JustSearchJobRun]::Survivors([int[]]$p, $b, 10, 0) | Select-Object -First 1',
  ].join('\n');
  const out = cp.spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script],
    { encoding: 'utf8', windowsHide: true, timeout: 60000 });
  const pid = Number(JSON.parse(String(out.stdout).trim() || 'null')?.pid);
  assert.ok(Number.isInteger(pid) && pid > 4, `a running process whose birth cannot be read: ${out.stdout} ${out.stderr}`);
  return pid;
}

/** The run's ledger as its writers write it: the dev runner (engine family), the Engine (children). */
function runLedger(dataDir, runId, engine, executable) {
  const ledgerDir = ledgerLib.ledgerDirectory(dataDir);
  const startedAt = Number((BigInt(engine.creationFileTimeUtc) - EPOCH) / 10000n);
  const runner = ledgerLib.createLedgerWriter({ ledgerDir, family: 'engine',
    writer: { role: 'dev-runner', pid: 4000, startedAt: startedAt - 1000 } });
  const engineWriter = ledgerLib.createLedgerWriter({ ledgerDir, family: 'children',
    writer: { role: 'engine', pid: engine.pid, startedAt } });
  const owner = { pid: engine.pid, creationFileTime: engine.creationFileTimeUtc, instanceId: 'inst-1' };
  const incarnation = { session: runId, n: 1 };
  return {
    engineStart() {
      runner.append('engine-start', { incarnation, pid: engine.pid, creationFileTime: engine.creationFileTimeUtc,
        executable, processStartedAt: new Date(startedAt), cause: 'START', supervisorKind: 'dev-runner' });
    },
    engineExit() {
      runner.append('engine-exit', { incarnation, exitClass: 'REQUESTED', reason: 'QUIT', detail: null, code: 0,
        codeName: 'ok', exitedAt: null, observedAt: new Date(), terminateCalledAt: null, forced: false,
        phase: 'STOPPING', codeDistinctive: true });
    },
    /** A process the Engine's job witness held and saw exit (a console host, as pid 24536 was). */
    jobProcess(p, witnessId) {
      const common = { witnessId, pid: p.pid, creationFileTime: p.creationFileTimeUtc, owner, incarnation };
      engineWriter.append('job-process-start', { ...common, executable: 'C:\\Windows\\System32\\conhost.exe',
        parentPid: engine.pid, parentCreationFileTime: engine.creationFileTimeUtc, contained: true,
        observedAt: new Date(startedAt + 2000), awaited: false, expectedCode: null, reason: null });
      engineWriter.append('job-process-exit', { ...common, code: 0, exitClass: 'UNEXPECTED', reason: null,
        exitedAt: iso7(BigInt(p.creationFileTimeUtc) + 450000n), observedAt: new Date(startedAt + 2100), forced: false });
    },
  };
}

test('a job member whose PID now names a process whose birth cannot be read is gone when the ledger records its exit', { skip: !windows && 'Windows job records' }, async () => {
  const host = noProcess(970401, '134364632930208358');
  const reused = unreadablePid();
  // The member's recorded birth is long past: its PID has since been given to another process.
  const member = { pid: reused, creationFileTimeUtc: '134000000000000005' };
  const dir = freshDir('job');
  const recordPath = path.join(dir, 'engine-1.json');
  const writeRecord = () => fs.writeFileSync(recordPath, JSON.stringify({ schema: 'justsearch-job-run.v1',
    pid: host.pid, creationFileTimeUtc: host.creationFileTimeUtc, root: member, members: [member],
    state: 'ended', rootExitCode: 0 }));
  writeRecord();
  const run = { recordPath, host };

  // What the stop met: the member's PID cannot be read, so on its own it is not proven gone.
  const unproven = jobRun.stopJobRunSync(run, { waitMs: 0 });
  assert.equal(unproven.outcome, 'incomplete', JSON.stringify(unproven));
  assert.deepEqual(unproven.survivors?.map((p) => Number(p.pid)), [reused]);

  // The Engine's ledger records the member's exit (same PID and birth): it is gone and not probed.
  writeRecord();
  const result = jobRun.stopJobRunSync(run, { waitMs: 0, endedIdentities: [member] });
  assert.equal(result.ok, true, JSON.stringify(result));
  assert.equal(result.outcome, 'gone');
  assert.deepEqual(result.survivors ?? [], []);
  assert.deepEqual((result.endedPerLedger ?? []).map((p) => [Number(p.pid), String(p.creationFileTimeUtc)]),
    [[reused, member.creationFileTimeUtc]]);
  assert.equal(fs.existsSync(recordPath), false, 'a stop that verified every identity retires the record');
});

/** stopRun's IO, with the real census, the real identity lookup and the real job stop. */
function stopFixture({ runId, dataDir, engine, engineJob = null }) {
  const dir = path.join(process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT, 'runs', runId);
  fs.mkdirSync(dir, { recursive: true });
  const run = { runId, pids: { backendRootPid: engine.pid },
    identities: { backend: { pid: engine.pid, creationFileTimeUtc: engine.creationFileTimeUtc } },
    apiPortActual: 1, dataDir, ...(engineJob ? { engineJob } : {}) };
  const runPath = path.join(dir, 'run.json');
  fs.writeFileSync(runPath, JSON.stringify(run));
  const never = (what) => async () => { throw new Error(`${what} must not be called: nothing of this run is running`); };
  return { dir, runPath, io: {
    resolveTarget: async () => ({ run: JSON.parse(fs.readFileSync(runPath, 'utf8')), runPath }),
    portOwner: async () => ({ closed: true }), portWaitMs: 0,
    gracefulShutdown: never('the ordered quit'), terminateEngine: never('an Engine termination'),
    terminateProcess: never('a termination'), askRunner: never('the runner'),
  } };
}

test('a stop after the Engine has gone completes when the ledger records the exit of a job member whose PID cannot be read', { skip: !windows && 'Windows job records' }, async () => {
  const runId = 'stop-census-reused-pid';
  const engine = noProcess(970501, '134364632933610725');
  const host = noProcess(970502 + 1, '134364632930208358');
  // A console host the Engine's job held, whose PID Windows has since given to a process whose
  // birth this user cannot read (pid 24536 and svchost.exe in run 8f60c4e7).
  const member = { pid: unreadablePid(), creationFileTimeUtc: '134364634153991105' };
  const dataDir = freshDir('reused-data');
  const ledger = runLedger(dataDir, runId, engine, process.execPath);
  ledger.engineStart();
  ledger.jobProcess(member, 'j-reused');
  ledger.engineExit();
  const fixture = stopFixture({ runId, dataDir, engine, engineJob: null });
  const recordPath = path.join(fixture.dir, 'engine-jobs', 'engine-1.json');
  fs.mkdirSync(path.dirname(recordPath), { recursive: true });
  fs.writeFileSync(recordPath, JSON.stringify({ schema: 'justsearch-job-run.v1', pid: host.pid,
    creationFileTimeUtc: host.creationFileTimeUtc, root: engine, members: [member], state: 'ended', rootExitCode: 0 }));
  const run = JSON.parse(fs.readFileSync(fixture.runPath, 'utf8'));
  fs.writeFileSync(fixture.runPath, JSON.stringify({ ...run, engineJob: { incarnation: 1, recordPath, host } }));

  const out = await api.stopRun({}, fixture.io);
  assert.equal(out.outcome, 'STOPPED', JSON.stringify({ errors: out.errors, engineJob: out.engineJob, unresolved: out.unresolvedProcesses }));
  assert.equal(out.engineJob?.outcome, 'gone');
  assert.deepEqual(out.engineJob?.survivors, []);
  assert.ok(out.engineJob?.endedPerLedger?.some((p) => p.pid === member.pid && p.creationFileTimeUtc === member.creationFileTimeUtc),
    'the member is reported as ended per the ledger: ' + JSON.stringify(out.engineJob));
  const report = JSON.parse(fs.readFileSync(path.join(fixture.dir, 'stop-report.json'), 'utf8'));
  assert.deepEqual(report.unresolvedProcesses, []);
  assert.equal(report.exitAccountingComplete, true, JSON.stringify(report.exitAccountingGaps));
});

test('a tracked identity the lookup cannot read stays unresolved unless the ledger records its exit', async () => {
  const runId = 'stop-census-tracked';
  const engine = noProcess(970001, '134364632933610725');
  const tracked = { ...noProcess(970002 + 1, '134364634153991105'), role: 'Engine job member' };
  const other = { ...noProcess(970005, '134364634153991999'), role: 'Engine job member' };
  const dataDir = freshDir('tracked-data');
  const ledger = runLedger(dataDir, runId, engine, process.execPath);
  ledger.engineStart();
  ledger.jobProcess(tracked, 'j-tracked');
  ledger.engineExit();
  const fixture = stopFixture({ runId, dataDir, engine });
  fs.writeFileSync(path.join(fixture.dir, 'stop-tracked-processes.json'), JSON.stringify({ processes: [tracked, other] }));
  // Both PIDs are present but their births cannot be read; the Engine is gone.
  const io = { ...fixture.io, lookupIdentities: async (ids) => new Map(ids.map((pid) => [pid,
    [tracked.pid, other.pid].includes(pid) ? { present: true, creationFileTime: null } : { present: false }])) };
  const out = await api.stopRun({}, io);
  assert.equal(out.outcome, 'STOP_INCOMPLETE', 'an identity nothing proves ended still blocks');
  assert.deepEqual(out.unresolvedProcesses.map((p) => p.pid), [other.pid]);
  const report = JSON.parse(fs.readFileSync(path.join(fixture.dir, 'stop-report.json'), 'utf8'));
  assert.deepEqual(report.resolvedByLedger?.map((p) => [p.pid, p.creationFileTimeUtc, p.basis]),
    [[tracked.pid, tracked.creationFileTimeUtc, 'exit ledger']]);
});

test('a retried stop keeps every earlier stop report and names the reports and census it kept', async () => {
  const runId = 'stop-census-reports';
  const engine = noProcess(970601, '134364632933610725');
  const tracked = { ...noProcess(970602 + 1, '134364634153991105'), role: 'Engine job member' };
  const dataDir = freshDir('reports-data');
  const ledger = runLedger(dataDir, runId, engine, process.execPath);
  ledger.engineStart();
  ledger.engineExit();
  const fixture = stopFixture({ runId, dataDir, engine });
  fs.writeFileSync(path.join(fixture.dir, 'stop-tracked-processes.json'), JSON.stringify({ processes: [tracked] }));
  const reportPath = (name = 'stop-report.json') => path.join(fixture.dir, name);
  const lookup = (present) => ({ ...fixture.io, lookupIdentities: async (ids) => new Map(ids.map((pid) => [pid,
    pid === tracked.pid && present ? { present: true, creationFileTime: null } : { present: false }])) });

  // The first stop cannot read the tracked identity and nothing proves it ended: incomplete.
  const first = await api.stopRun({}, lookup(true));
  assert.equal(first.outcome, 'STOP_INCOMPLETE');
  const firstReport = fs.readFileSync(reportPath(), 'utf8');
  assert.equal(JSON.parse(firstReport).earlierReports, undefined, 'a first stop has no earlier report');

  // The retry finds it gone: its report is stop-report.json, and the first is kept as it was.
  const retry = await api.stopRun({}, lookup(false));
  assert.equal(retry.outcome, 'STOPPED', JSON.stringify(retry.errors));
  assert.equal(fs.existsSync(reportPath('stop-report.1.json')), true, 'the earlier stop report is kept');
  assert.equal(fs.readFileSync(reportPath('stop-report.1.json'), 'utf8'), firstReport);
  const retryText = fs.readFileSync(reportPath(), 'utf8');
  const retryReport = JSON.parse(retryText);
  assert.equal(retryReport.outcome, 'STOPPED');
  assert.deepEqual(retryReport.earlierReports, ['stop-report.1.json']);
  assert.deepEqual(retry.earlierReports, ['stop-report.1.json']);
  if (windows) {
    // The census the first stop armed is kept beside its report, and the retry's report names it.
    assert.deepEqual(retryReport.earlierCensus, ['exit-targets.1.json', 'process-exits.1.json']);
    for (const name of retryReport.earlierCensus) assert.equal(fs.existsSync(reportPath(name)), true, name);
  }

  // Each further stop keeps the one before it under the next number.
  const third = await api.stopRun({}, lookup(false));
  assert.equal(third.outcome, 'STOPPED');
  assert.equal(fs.readFileSync(reportPath('stop-report.1.json'), 'utf8'), firstReport);
  assert.equal(fs.readFileSync(reportPath('stop-report.2.json'), 'utf8'), retryText);
  assert.deepEqual(JSON.parse(fs.readFileSync(reportPath(), 'utf8')).earlierReports, ['stop-report.1.json', 'stop-report.2.json']);
});

/** The census entry point with a stand-in for the PowerShell observer (as test-dev-runner-exit-census). */
async function arm(run, dataDir, exitsAtFinish) {
  let args, clock = 0, finished = false;
  const proc = new EventEmitter(); proc.exitCode = null; proc.kill = () => {};
  const flag = (f) => args[args.indexOf(f) + 1];
  const observer = await census.startStopExitCensus(run, dataDir, dataDir, scope, {
    launch: (a) => { args = a; fs.writeFileSync(flag('-ReadyFile'), '{}'); return proc; }, now: () => clock,
    pause: async (ms) => {
      clock += ms;
      if (!finished && fs.existsSync(flag('-FinishFile'))) {
        finished = true;
        fs.writeFileSync(flag('-OutputFile'), '\uFEFF' + JSON.stringify({ processExits: exitsAtFinish, gaps: [] }));
      }
    },
  });
  return { observer, targets: JSON.parse(fs.readFileSync(flag('-InputFile'), 'utf8')).targets, flag };
}

test('a retried stop keeps the earlier census, adds to it, and completes from the ledger once the Engine is gone', async () => {
  const runId = 'stop-census-retry';
  const engine = noProcess(970101, '134364632933610725');
  const exe = path.join(root, 'tmp', 'engine.exe');
  const run = { runId, pids: { backendRootPid: engine.pid } };
  const dataDir = freshDir('retry');
  const ledger = runLedger(dataDir, runId, engine, exe);
  ledger.engineStart();
  ledger.jobProcess(noProcess(970105, '134364634153991105'), 'j-retry');
  const atMs = Date.UTC(2026, 9, 14, 15, 16, 46);

  // The first stop armed the Engine and its observer saw the Engine exit; the stop was then ended
  // (its 60 s limit) before its verdict.
  const first = await arm(run, dataDir, []);
  assert.deepEqual(first.targets.map((t) => [t.role, t.pid]), [['engine', engine.pid]]);
  const firstExits = '\uFEFF' + JSON.stringify({ processExits: [{ pid: engine.pid, creationFileTimeUtc: engine.creationFileTimeUtc,
    osCreationFileTimeUtc: engine.creationFileTimeUtc, executable: exe, exitCode: 0, atMs, exitTimeSource: 'os' }], gaps: [] });
  fs.writeFileSync(path.join(dataDir, 'process-exits.json'), firstExits);
  const firstTargets = fs.readFileSync(path.join(dataDir, 'exit-targets.json'), 'utf8');
  ledger.engineExit();

  // The retry: the Engine is gone, so it arms nothing and its own observer sees nothing.
  const retry = await arm(run, dataDir, []);
  assert.deepEqual(retry.targets, []);
  const result = await retry.observer.finish();
  assert.deepEqual(result.exitAccountingGaps, []);
  assert.equal(result.exitAccountingComplete, true, 'every recorded identity has its exit');
  const engineRow = result.processExits.find((e) => e.role === 'engine');
  assert.deepEqual([engineRow?.exitCode, engineRow?.atMs, engineRow?.ledgerClass, engineRow?.expectedTermination],
    [0, atMs, 'REQUESTED', true], 'the Engine is accounted with the OS exit the first census observed: ' + JSON.stringify(engineRow));
  // The earlier census is kept as it was, and the census file adds to it.
  assert.equal(fs.readFileSync(path.join(dataDir, 'process-exits.1.json'), 'utf8'), firstExits);
  assert.equal(fs.readFileSync(path.join(dataDir, 'exit-targets.1.json'), 'utf8'), firstTargets);
  const kept = JSON.parse(fs.readFileSync(path.join(dataDir, 'process-exits.json'), 'utf8').replace(/^\uFEFF/, ''));
  assert.ok(kept.processExits.some((e) => e.pid === engine.pid && e.exitCode === 0 && e.atMs === atMs),
    'the census file still holds the earlier OS exit: ' + JSON.stringify(kept));
});

test('a stop that finds the Engine already gone completes from the ledger when every recorded identity has its exit', async () => {
  const runId = 'stop-census-gone';
  const engine = noProcess(970201, '134364632933610725');
  const run = { runId, pids: { backendRootPid: engine.pid } };
  const dataDir = freshDir('gone');
  const ledger = runLedger(dataDir, runId, engine, path.join(root, 'tmp', 'engine.exe'));
  ledger.engineStart();
  ledger.jobProcess({ pid: 970202, creationFileTimeUtc: '134364634153991105' }, 'j-gone');
  ledger.engineExit();
  const { observer, targets } = await arm(run, dataDir, []);
  assert.deepEqual(targets, []);
  const result = await observer.finish();
  assert.deepEqual(result.exitAccountingGaps, []);
  assert.equal(result.exitAccountingComplete, true);
  // The Engine quit as asked; its console host is accounted with it.
  assert.deepEqual(result.processExits.map((e) => [e.pid, e.ledgerClass]).sort(), [[970201, 'REQUESTED'], [970202, 'REQUESTED']]);

  // An identity without its exit record keeps the census incomplete.
  const open = freshDir('open');
  const openLedger = runLedger(open, runId, engine, path.join(root, 'tmp', 'engine.exe'));
  openLedger.engineStart();
  openLedger.engineExit();
  ledgerLib.createLedgerWriter({ ledgerDir: ledgerLib.ledgerDirectory(open), family: 'children',
    writer: { role: 'engine', pid: engine.pid, startedAt: Number((BigInt(engine.creationFileTimeUtc) - EPOCH) / 10000n) } })
    .append('job-process-start', { witnessId: 'j-open', pid: 970203, creationFileTime: '134364634153991105',
      executable: 'C:\\Windows\\System32\\conhost.exe', parentPid: engine.pid, parentCreationFileTime: engine.creationFileTimeUtc,
      contained: true, observedAt: new Date(), awaited: false, expectedCode: null, reason: null,
      owner: { pid: engine.pid, creationFileTime: engine.creationFileTimeUtc, instanceId: 'inst-1' }, incarnation: { session: runId, n: 1 } });
  const incomplete = await (await arm(run, open, [])).observer.finish();
  assert.equal(incomplete.exitAccountingComplete, false);
  assert.ok(incomplete.exitAccountingGaps.some((g) => g.pid === 970203), JSON.stringify(incomplete.exitAccountingGaps));
});

/**
 * A ledger shaped like the scripted-agent run's (8f60c4e7: 17 MB, 2229 job processes, 1111 helper
 * receipts, 10568 helper windows), written as the Engine's rotation leaves it: numbered archives,
 * then .1, then the current file.
 */
function syntheticLedger(dataDir, runId, engine, executable, minBytes) {
  const dir = ledgerLib.ledgerDirectory(dataDir);
  fs.mkdirSync(dir, { recursive: true });
  const startedAt = isoMs(engine.creationFileTimeUtc);
  const engineWriter = { role: 'engine', pid: engine.pid, startedAt };
  const owner = { pid: engine.pid, creationFileTime: engine.creationFileTimeUtc, instanceId: '48d2f3db-2e1b-4020-99b7-65970ffcc828' };
  const incarnation = { session: runId, n: 1 };
  let seq = 0;
  const line = (type, fields, ft) => JSON.stringify({ schemaVersion: 1, type, seq: ++seq, at: isoMs(ft), writer: engineWriter, ...fields });
  const lines = [];
  const base = BigInt(engine.creationFileTimeUtc);
  for (let i = 0; i < 2229; i++) {
    const born = base + 10000000n + BigInt(i) * 3000000n;
    const pid = 200000 + i;
    const helper = i % 2 === 0;
    const executable = helper ? 'C:\\Windows\\System32\\nvidia-smi.exe' : 'C:\\Windows\\System32\\conhost.exe';
    const witnessId = `j-${String(i).padStart(8, '0')}-6b90-4870-81e0-9761e1a508b8`;
    const common = { witnessId, pid, creationFileTime: String(born), owner, incarnation };
    lines.push(line('job-process-start', { ...common, executable, parentPid: engine.pid, parentCreationFileTime: engine.creationFileTimeUtc,
      contained: true, observedAt: isoMs(born), awaited: false, expectedCode: null, reason: null }, born));
    if (helper) lines.push(line('helper-start', { helperId: `h-${witnessId}`, pid, creationFileTime: String(born), creatorPid: engine.pid,
      creatorCreationFileTime: engine.creationFileTimeUtc, purpose: 'nvidia-smi VRAM probe', owner, incarnation }, born));
    const exited = born + 1500000n;
    if (helper) lines.push(line('helper-exit', { helperId: `h-${witnessId}`, pid, creationFileTime: String(born), creatorPid: engine.pid,
      creatorCreationFileTime: engine.creationFileTimeUtc, code: 0, exitClass: 'REQUESTED', reason: 'nvidia-smi VRAM probe: awaited completion',
      exitedAt: iso7(exited), observedAt: isoMs(exited), forced: false, owner, incarnation }, exited));
    lines.push(line('job-process-exit', { ...common, code: 0, exitClass: 'UNEXPECTED', reason: null, exitedAt: iso7(exited),
      observedAt: isoMs(exited), forced: false }, exited));
  }
  let bytes = lines.reduce((n, l) => n + l.length + 1, 0);
  for (let i = 0; bytes < minBytes; i++) {
    const at = base + 10000000n + BigInt(i) * 600000n;
    const window = { intentId: `i-${String(i).padStart(8, '0')}-b348-4ac9-ba0e-561598f799b2`, image: 'tesseract.exe',
      reason: 'Tika Tesseract extraction and availability', requestedAt: iso7(at), deadlineAt: iso7(at + 3000000000n), owner, incarnation };
    for (const l of [line('helper-window-start', { ...window, closedAt: null }, at),
      line('helper-window-end', { ...window, closedAt: iso7(at + 60000n) }, at + 60000n)]) {
      lines.push(l); bytes += l.length + 1;
    }
  }
  // 2 MiB files: the oldest numbered from 2 up, then .1, then the current file.
  const chunks = [];
  let chunk = [], size = 0;
  for (const l of lines) {
    if (size + l.length + 1 > ledgerLib.HARD_CAP_BYTES && chunk.length) { chunks.push(chunk); chunk = []; size = 0; }
    chunk.push(l); size += l.length + 1;
  }
  chunks.push(chunk);
  const names = chunks.map((_, i) => (i === chunks.length - 1 ? 'children.v1.jsonl'
    : i === chunks.length - 2 ? 'children.v1.1.jsonl' : `children.v1.${i + 2}.jsonl`));
  chunks.forEach((c, i) => fs.writeFileSync(path.join(dir, names[i]), c.join('\n') + '\n'));
  const runner = ledgerLib.createLedgerWriter({ ledgerDir: dir, family: 'engine',
    writer: { role: 'dev-runner', pid: 4000, startedAt: Number((base - EPOCH) / 10000n) - 1000 } });
  runner.append('engine-start', { incarnation, pid: engine.pid, creationFileTime: engine.creationFileTimeUtc, executable,
    processStartedAt: new Date(Number((base - EPOCH) / 10000n)), cause: 'INITIAL', supervisorKind: 'dev-runner' });
  runner.append('engine-exit', { incarnation, exitClass: 'REQUESTED', reason: 'QUIT', detail: null, code: 0, codeName: 'ok',
    exitedAt: null, observedAt: new Date(), terminateCalledAt: null, forced: false, phase: 'STOPPING', codeDistinctive: true });
  return ledgerLib.ledgerFiles(dir, 'children').concat(ledgerLib.ledgerFiles(dir, 'engine'))
    .reduce((n, f) => n + fs.statSync(f).size, 0);
}

test('a stop over a 17 MB ledger reads each ledger file once and stays well inside the 60 s stop limit', async () => {
  const runId = 'stop-census-17mb';
  const engine = noProcess(970301, '134364632933610725');
  const dataDir = freshDir('large');
  const ledgerBytes = syntheticLedger(dataDir, runId, engine, process.execPath, 17 * 1024 * 1024);
  assert.ok(ledgerBytes >= 17 * 1024 * 1024, `the ledger is at least 17 MiB: ${ledgerBytes}`);
  const ledgerDir = path.resolve(ledgerLib.ledgerDirectory(dataDir)).toLowerCase();
  const inLedger = (file) => typeof file === 'string' && path.resolve(file).toLowerCase().startsWith(ledgerDir);
  // Every byte the stop reads from the ledger directory, whichever way it is read.
  let read = 0;
  const fds = new Map();
  const original = { openSync: fs.openSync, closeSync: fs.closeSync, readSync: fs.readSync, readFileSync: fs.readFileSync };
  fs.openSync = function (file, ...rest) { const fd = original.openSync.call(this, file, ...rest); if (inLedger(file)) fds.set(fd, file); return fd; };
  fs.closeSync = function (fd, ...rest) { fds.delete(fd); return original.closeSync.call(this, fd, ...rest); };
  fs.readSync = function (fd, ...rest) { const n = original.readSync.call(this, fd, ...rest); if (fds.has(fd)) read += n; return n; };
  fs.readFileSync = function (file, ...rest) {
    const out = original.readFileSync.call(this, file, ...rest);
    if (inLedger(file)) read += Buffer.byteLength(out);
    return out;
  };
  const fixture = stopFixture({ runId, dataDir, engine });
  const started = Date.now();
  let out;
  try {
    out = await api.stopRun({}, { ...fixture.io, ...(windows ? {} : { lookupIdentities: async (ids) => new Map(ids.map((p) => [p, { present: false }])) }) });
  } finally {
    Object.assign(fs, original);
  }
  const elapsedMs = Date.now() - started;
  console.log(`stop over a ${ledgerBytes}-byte ledger: ${elapsedMs} ms, ${read} ledger bytes read`);
  assert.equal(out.outcome, 'STOPPED', JSON.stringify(out.errors));
  const report = JSON.parse(original.readFileSync(path.join(fixture.dir, 'stop-report.json'), 'utf8'));
  assert.equal(report.exitAccountingComplete, true, JSON.stringify(report.exitAccountingGaps?.slice(0, 3)));
  assert.equal(report.ledgerSummary.jobProcesses, 2229);
  assert.ok(read <= ledgerBytes + 64 * 1024, `each ledger file is read once per stop: ${read} bytes read of ${ledgerBytes}`);
  assert.ok(elapsedMs < 20_000, `the stop takes a third of its 60 s limit at most: ${elapsedMs} ms`);
});
