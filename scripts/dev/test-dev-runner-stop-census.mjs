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
    /** A child the Engine started (its spawn and start), and its exit when `exited`. */
    child(childId, p, exited) {
      engineWriter.append('child-spawn', { childId, kind: 'EXTRACTION', owner, incarnation });
      engineWriter.append('child-start', { childId, pid: p.pid, creationFileTime: p.creationFileTimeUtc,
        executable: 'C:\\fixture\\parser.exe', contained: true });
      if (exited) {
        engineWriter.append('child-exit', { childId, exitClass: 'REQUESTED', reason: 'QUIT', code: 0, forced: false,
          exitedAt: iso7(BigInt(p.creationFileTimeUtc) + 450000n) });
      }
    },
    /** A process the Engine's job witness held (its start only). */
    jobProcessStart(p, witnessId) {
      const common = { witnessId, pid: p.pid, creationFileTime: p.creationFileTimeUtc, owner, incarnation };
      engineWriter.append('job-process-start', { ...common, executable: 'C:\\Windows\\System32\\conhost.exe',
        parentPid: engine.pid, parentCreationFileTime: engine.creationFileTimeUtc, contained: true,
        observedAt: new Date(startedAt + 2000), awaited: false, expectedCode: null, reason: null });
    },
    /** A process the Engine's job witness held and saw exit (a console host, as pid 24536 was). */
    jobProcess(p, witnessId) {
      const common = { witnessId, pid: p.pid, creationFileTime: p.creationFileTimeUtc, owner, incarnation };
      this.jobProcessStart(p, witnessId);
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
  const looked = new Set();
  const io = { ...fixture.io, lookupIdentities: async (ids) => {
    for (const pid of ids) looked.add(pid);
    return new Map(ids.map((pid) => [pid,
      [tracked.pid, other.pid].includes(pid) ? { present: true, creationFileTime: null } : { present: false }]));
  } };
  const out = await api.stopRun({}, io);
  assert.equal(out.outcome, 'STOP_INCOMPLETE', 'an identity nothing proves ended still blocks');
  assert.deepEqual(out.unresolvedProcesses.map((p) => p.pid), [other.pid]);
  assert.deepEqual([looked.has(tracked.pid), looked.has(engine.pid), looked.has(other.pid)], [false, false, true]);
  const report = JSON.parse(fs.readFileSync(path.join(fixture.dir, 'stop-report.json'), 'utf8'));
  // The Engine and the tracked identity are resolved by their ledger exits, without a lookup.
  assert.deepEqual(report.resolvedByLedger?.map((p) => [p.pid, p.creationFileTimeUtc, p.basis]).sort(),
    [[engine.pid, engine.creationFileTimeUtc, 'exit ledger'], [tracked.pid, tracked.creationFileTimeUtc, 'exit ledger']]);
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
async function arm(run, dataDir, exitsAtFinish, atFinish = null, options = {}) {
  let args, clock = 0, finished = false;
  const proc = new EventEmitter(); proc.exitCode = null; proc.kill = () => {};
  const flag = (f) => args[args.indexOf(f) + 1];
  const observer = await census.startStopExitCensus(run, dataDir, dataDir, scope, { ...options,
    launch: (a) => { args = a; fs.writeFileSync(flag('-ReadyFile'), '{}'); return proc; }, now: () => clock,
    pause: async (ms) => {
      clock += ms;
      if (!finished && args && fs.existsSync(flag('-FinishFile'))) {
        finished = true;
        atFinish?.();
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
 * The run's children ledger file with its last line damaged as the verifier found it: `corrupt`
 * keeps the line's end but not its whole record; `torn` cuts it before its newline.
 */
function damageLastLine(dataDir, how) {
  const files = ledgerLib.ledgerFiles(ledgerLib.ledgerDirectory(dataDir), 'children');
  const file = files[files.length - 1];
  const text = fs.readFileSync(file, 'utf8');
  const start = text.lastIndexOf('\n', text.length - 2) + 1;
  const line = text.slice(start, text.length - 1);
  const cut = line.slice(0, Math.floor(line.length / 2));
  fs.writeFileSync(file, text.slice(0, start) + (how === 'corrupt' ? `${cut}\n` : cut));
  return { file, rest: line.slice(cut.length) + '\n' };
}

/** A stop of a run whose Engine and exit are recorded, and one job process with its start only. */
function damagedRun(how) {
  const runId = `stop-census-damaged-${how}`;
  const engine = noProcess(970701 + (how === 'torn' ? 4 : 0), '134364632933610725');
  const child = noProcess(970702 + (how === 'torn' ? 4 : 0) + 1, '134364634153991105');
  const dataDir = freshDir(`damaged-${how}`);
  const ledger = runLedger(dataDir, runId, engine, process.execPath);
  ledger.engineStart();
  ledger.engineExit();
  ledger.jobProcessStart(child, `j-damaged-${how}`);
  return { runId, engine, child, dataDir, ledger };
}

for (const how of ['corrupt', 'torn']) {
  test(`a stop never reports complete accounting over a ${how} ledger line (preserved from b599d4d7e)`, async () => {
    const { runId, engine, dataDir } = damagedRun(how);
    damageLastLine(dataDir, how);
    const fixture = stopFixture({ runId, dataDir, engine });
    const out = await api.stopRun({}, fixture.io);
    const report = JSON.parse(fs.readFileSync(path.join(fixture.dir, 'stop-report.json'), 'utf8'));
    assert.notEqual(report.exitAccountingComplete, true,
      `a record the census could not read leaves accounting incomplete: ${JSON.stringify(report.exitAccountingGaps)}`);
    assert.notEqual(out.exitAccountingComplete, true);
  });
}

test('a damaged ledger line is a gap naming its file and line count; a torn line the stop later reads whole is not', async () => {
  const run = (runId, engine) => ({ runId, pids: { backendRootPid: engine.pid } });
  for (const how of ['corrupt', 'torn']) {
    const { runId, engine, dataDir } = damagedRun(how);
    const { file } = damageLastLine(dataDir, how);
    const result = await (await arm(run(runId, engine), dataDir, [])).observer.finish();
    assert.equal(result.exitAccountingComplete, false, how);
    const gap = result.exitAccountingGaps.find((g) => g.reason === 'ledger-damaged');
    assert.ok(gap, `${how}: ${JSON.stringify(result.exitAccountingGaps)}`);
    assert.deepEqual([gap.family, path.basename(gap.file), gap.lines, gap.corruptLines, gap.tornTail],
      ['children', path.basename(file), 1, how === 'corrupt' ? 1 : 0, how === 'torn'], how);
  }
  // A line torn when the census armed (its writer was still writing it) and whole by the verdict.
  const { runId, engine, dataDir } = damagedRun('later');
  const { file, rest } = damageLastLine(dataDir, 'torn');
  let completed = false;
  const census2 = await arm(run(runId, engine), dataDir, [], () => {
    if (completed) return;
    completed = true;
    fs.appendFileSync(file, rest);
  });
  const whole = await census2.observer.finish();
  assert.equal(completed, true);
  assert.deepEqual(whole.exitAccountingGaps.filter((g) => g.reason === 'ledger-damaged'), []);
});

test('a gap of the arming read that a later read resolves is dropped: a torn Engine start completed before the verdict', async () => {
  const runId = 'stop-census-torn-engine';
  const engine = noProcess(970811, '134364632933610725');
  const dataDir = freshDir('torn-engine');
  const ledger = runLedger(dataDir, runId, engine, process.execPath);
  ledger.engineStart();
  ledger.jobProcess(noProcess(970813, '134364634153991105'), 'j-torn-engine');
  // The Engine start as its writer had written it when the census armed: its first 80 bytes.
  const file = path.join(ledgerLib.ledgerDirectory(dataDir), 'engine.v1.jsonl');
  const whole = fs.readFileSync(file, 'utf8');
  fs.writeFileSync(file, whole.slice(0, 80));
  const armed = await arm({ runId, pids: { backendRootPid: engine.pid } }, dataDir, [], () => {
    fs.appendFileSync(file, whole.slice(80));
    ledger.engineExit();
  });
  assert.deepEqual(armed.targets, [], 'the arming read has no Engine identity to arm');
  const result = await armed.observer.finish();
  assert.ok(result.processExits.some((e) => e.pid === engine.pid && e.ledgerClass === 'REQUESTED'), JSON.stringify(result.processExits));
  assert.deepEqual(result.exitAccountingGaps, []);
  assert.equal(result.exitAccountingComplete, true);
});

test('an identity whose exit the ledger records is never passed to an OS lookup or probe', async () => {
  const runId = 'stop-census-no-probe';
  const engine = noProcess(970801, '134364632933610725');
  const dataDir = freshDir('no-probe');
  const ledger = runLedger(dataDir, runId, engine, process.execPath);
  ledger.engineStart();
  ledger.engineExit();
  const fixture = stopFixture({ runId, dataDir, engine });
  const looked = [], ported = [];
  const io = { ...fixture.io,
    lookupIdentities: async (ids) => { looked.push([...ids]); return new Map(ids.map((pid) => [pid, { present: false }])); },
    portOwner: async (port, processes) => { ported.push((processes ?? []).map((p) => p.pid)); return { closed: true }; } };
  const out = await api.stopRun({}, io);
  assert.equal(out.outcome, 'STOPPED', JSON.stringify(out.errors));
  assert.equal(looked.filter((ids) => ids.includes(engine.pid)).length, 0,
    `the ledger-ended Engine was looked up ${looked.filter((ids) => ids.includes(engine.pid)).length} times: ${JSON.stringify(looked)}`);
  assert.equal(ported.filter((pids) => pids.includes(engine.pid)).length, 0, 'nor offered as a port owner');
  const report = JSON.parse(fs.readFileSync(path.join(fixture.dir, 'stop-report.json'), 'utf8'));
  assert.ok(report.resolvedByLedger?.some((p) => p.pid === engine.pid && p.creationFileTimeUtc === engine.creationFileTimeUtc),
    JSON.stringify(report.resolvedByLedger));
});

test('a rotation between the stat and the open of a ledger file loses no record: the moved file is read again in full', async () => {
  const runId = 'stop-census-rotation-race';
  const engine = noProcess(970211, '134364632933610725');
  const child = noProcess(970213, '134364634153991105');
  const dataDir = freshDir('rotation-race');
  const ledger = runLedger(dataDir, runId, engine, process.execPath);
  ledger.engineStart();
  ledger.child('c-live', child, false);
  ledger.engineExit();
  const dir = ledgerLib.ledgerDirectory(dataDir);
  const current = path.join(dir, 'children.v1.jsonl'), moved = path.join(dir, 'children.v1.1.jsonl');
  const before = ledgerLib.readLedger(dir, 'children').records.map((r) => [r.type, r.line.childId]);
  // The writer rotates right after the reader's stat of the current file: the current file is
  // renamed, and a replacement as long as it, holding only a note, takes its name.
  const size = fs.statSync(current).size;
  const note = JSON.stringify({ schemaVersion: 1, type: 'child-note', seq: 99, at: new Date().toISOString(),
    writer: { role: 'engine', pid: engine.pid, startedAt: isoMs(engine.creationFileTimeUtc) }, childId: 'c-note', note: 'new file' });
  const replacement = note + ' '.repeat(size - Buffer.byteLength(note) - 1) + '\n';
  const stat = fs.statSync;
  let rotated = false;
  fs.statSync = function (file, ...rest) {
    const out = stat.call(this, file, ...rest);
    if (!rotated && path.resolve(String(file)) === path.resolve(current)) {
      rotated = true;
      fs.renameSync(current, moved);
      fs.writeFileSync(current, replacement);
    }
    return out;
  };
  const reader = ledgerLib.createIncrementalReader();
  let result;
  try {
    const armed = await arm({ runId, pids: { backendRootPid: engine.pid } }, dataDir, [], null, { ledgerReader: reader });
    result = await armed.observer.finish();
  } finally {
    fs.statSync = stat;
  }
  assert.ok(rotated, 'the rotation happened between the stat and the open');
  const read = reader.read(dir, 'children').records.map((r) => [r.type, r.line.childId]);
  const truth = ledgerLib.readLedger(dir, 'children').records.map((r) => [r.type, r.line.childId]);
  assert.deepEqual(read, truth, 'the reader holds what a whole read of the files holds');
  for (const record of before) assert.ok(read.some((r) => r[0] === record[0] && r[1] === record[1]), `kept: ${record}`);
  assert.equal(result.exitAccountingComplete, false, 'a child with a start and no exit leaves accounting incomplete');
});

test('an Engine a reconciliation found alive (ALIVE_MATCH) is looked up and blocks the stop', async () => {
  const runId = 'stop-census-alive-match';
  const live = noProcess(970301 + 20, '134364632933610725');
  const engine = noProcess(970301 + 24, '134364633933610725');
  const dataDir = freshDir('alive-match');
  const dir = ledgerLib.ledgerDirectory(dataDir);
  const writerOf = (role, pid, family) => ledgerLib.createLedgerWriter({ ledgerDir: dir, family,
    writer: { role, pid, startedAt: Number((BigInt(engine.creationFileTimeUtc) - EPOCH) / 10000n) } });
  const runner = writerOf('dev-runner', 4000, 'engine');
  const start = (p, n) => runner.append('engine-start', { incarnation: { session: runId, n }, pid: p.pid,
    creationFileTime: p.creationFileTimeUtc, executable: process.execPath, processStartedAt: new Date(isoMs(p.creationFileTimeUtc)),
    cause: 'START', supervisorKind: 'dev-runner' });
  // Incarnation 1: a later supervisor found it open, and found it alive (ALIVE_MATCH).
  start(live, 1);
  runner.append('engine-owner-died', { incarnation: { session: runId, n: 1 }, basis: 'OPEN_START', osCheck: 'ALIVE_MATCH',
    exitClass: 'UNKNOWN', pid: live.pid });
  // Incarnation 2, the run's Engine, ended as recorded.
  start(engine, 2);
  runner.append('engine-exit', { incarnation: { session: runId, n: 2 }, exitClass: 'REQUESTED', reason: 'QUIT', detail: null,
    code: 0, codeName: 'ok', exitedAt: null, observedAt: new Date(), terminateCalledAt: null, forced: false,
    phase: 'STOPPING', codeDistinctive: true });
  // The stop: the incarnation found alive is still recorded, alive with its birth.
  const fixture = stopFixture({ runId, dataDir, engine });
  fs.writeFileSync(path.join(fixture.dir, 'stop-tracked-processes.json'),
    JSON.stringify({ processes: [{ ...live, role: 'engine', owned: false }] }));
  const looked = [];
  const io = { ...fixture.io, lookupIdentities: async (ids) => {
    looked.push([...ids]);
    return new Map(ids.map((pid) => [pid, pid === live.pid
      ? { present: true, creationFileTime: live.creationFileTimeUtc, name: 'node.exe', executable: process.execPath } : { present: false }]));
  } };
  const out = await api.stopRun({}, io);
  assert.ok(looked.some((ids) => ids.includes(live.pid)), `the identity found alive is looked up: ${JSON.stringify(looked)}`);
  assert.equal(out.outcome, 'STOP_INCOMPLETE', 'an identity found alive still blocks the stop');
  assert.deepEqual(out.unresolvedProcesses.map((p) => p.pid), [live.pid]);
});

/**
 * A ledger written as its writers write it, in one go: each record `{ family, writer, type, fields }`
 * as one line of `<family>.v1.jsonl` (the writer's envelope), in the order given.
 */
function writeLedger(dataDir, records) {
  const dir = ledgerLib.ledgerDirectory(dataDir);
  fs.mkdirSync(dir, { recursive: true });
  const text = { engine: '', children: '' };
  let seq = 0;
  for (const r of records) {
    text[r.family] += JSON.stringify({ schemaVersion: 1, type: r.type, seq: ++seq, at: new Date().toISOString(),
      writer: r.writer, ...r.fields }) + '\n';
  }
  for (const family of ['engine', 'children']) if (text[family]) fs.writeFileSync(path.join(dir, `${family}.v1.jsonl`), text[family]);
}

// The proof table's run: its Engine, one of its children and one process its job witness held.
const PROOF_RUN = 'stop-census-proof';
const proofEngine = noProcess(971001, '134364632933610725');
const proofChild = noProcess(971005, '134364634153991105');
const proofWitness = noProcess(971009, '134364634153995105');
const proofOwner = { pid: proofEngine.pid, creationFileTime: proofEngine.creationFileTimeUtc, instanceId: 'inst-1' };
const proofInc = { session: PROOF_RUN, n: 1 };
const nextTick = (ft) => String(BigInt(ft) + 1n);
const proofStartedAt = isoMs(proofEngine.creationFileTimeUtc);
const by = {
  runner: { role: 'dev-runner', pid: 4001, startedAt: proofStartedAt },
  engine: { role: 'engine', pid: proofEngine.pid, startedAt: proofStartedAt },
  // The next Engine, reconciling what the run's Engine left open.
  later: { role: 'engine', pid: 971013, startedAt: isoMs(BigInt(proofEngine.creationFileTimeUtc) + 600000000n) },
};
const rec = (family, writer, type, fields) => ({ family, writer, type, fields });
const engineStart = rec('engine', by.runner, 'engine-start', { incarnation: proofInc, pid: proofEngine.pid,
  creationFileTime: proofEngine.creationFileTimeUtc, executable: process.execPath, processStartedAt: proofStartedAt,
  cause: 'START', supervisorKind: 'dev-runner' });
const engineExit = (fields = {}) => rec('engine', by.runner, 'engine-exit', { incarnation: proofInc, exitClass: 'REQUESTED',
  reason: 'QUIT', detail: null, code: 0, codeName: 'ok', exitedAt: null, observedAt: proofStartedAt, terminateCalledAt: null,
  forced: false, phase: 'STOPPING', codeDistinctive: true, ...fields });
const engineOwnerDied = (osCheck, fields = {}) => rec('engine', by.runner, 'engine-owner-died', { incarnation: proofInc,
  basis: 'OPEN_START', ...(osCheck ? { osCheck } : {}), exitClass: osCheck === 'GONE' ? 'OWNER_DIED' : 'UNKNOWN',
  pid: proofEngine.pid, ...fields });
const childStart = (p = proofChild) => rec('children', by.engine, 'child-start', { childId: 'c1', pid: p.pid,
  creationFileTime: p.creationFileTimeUtc, executable: 'C:\\fixture\\parser.exe', contained: true });
const childExit = (fields = {}) => rec('children', by.engine, 'child-exit', { childId: 'c1', exitClass: 'REQUESTED',
  reason: 'QUIT', code: 0, forced: false, exitedAt: iso7(BigInt(proofChild.creationFileTimeUtc) + 450000n), ...fields });
const ownerDied = (osCheck, fields = {}) => rec('children', by.later, 'owner-died', { childId: 'c1', previousOwner: proofOwner,
  basis: 'OPEN_START', ...(osCheck ? { osCheck } : {}), exitClass: osCheck === 'GONE' ? 'OWNER_DIED' : 'UNKNOWN', ...fields });
const childRecord = (type, fields = {}) => rec('children', by.engine, type, { childId: 'c1', ...fields });
const witnessed = { witnessId: 'w1', pid: proofWitness.pid, creationFileTime: proofWitness.creationFileTimeUtc,
  owner: proofOwner, incarnation: proofInc };
const witnessStart = rec('children', by.engine, 'job-process-start', { ...witnessed, executable: 'C:\\fixture\\helper.exe',
  parentPid: proofEngine.pid, parentCreationFileTime: proofEngine.creationFileTimeUtc, contained: true,
  observedAt: proofStartedAt, awaited: false, expectedCode: null, reason: null });
const witnessExit = (fields = {}, writer = by.engine) => rec('children', writer, 'job-process-exit', { ...witnessed, code: 0,
  exitClass: 'UNEXPECTED', reason: null, exitedAt: iso7(BigInt(proofWitness.creationFileTimeUtc) + 450000n),
  observedAt: proofStartedAt, forced: false, ...fields });
const reconciledExit = (writer, osCheck, fields = {}) => witnessExit({ reconciled: true, cause: 'ENGINE_CRASH',
  ...(osCheck ? { osCheck } : {}), exitClass: osCheck === 'GONE' ? 'OWNER_DIED' : 'UNKNOWN', reason: 'reconciled',
  code: null, exitedAt: null, ...fields }, writer);
const helperReceipt = [
  rec('children', by.engine, 'helper-start', { helperId: 'h1', pid: proofWitness.pid, creationFileTime: proofWitness.creationFileTimeUtc,
    creatorPid: proofEngine.pid, creatorCreationFileTime: proofEngine.creationFileTimeUtc, purpose: 'probe', owner: proofOwner,
    incarnation: proofInc }),
  rec('children', by.engine, 'helper-exit', { helperId: 'h1', pid: proofWitness.pid, creationFileTime: proofWitness.creationFileTimeUtc,
    creatorPid: proofEngine.pid, creatorCreationFileTime: proofEngine.creationFileTimeUtc, code: 0, exitClass: 'REQUESTED',
    reason: 'probe: awaited completion', exitedAt: iso7(BigInt(proofWitness.creationFileTimeUtc) + 450000n),
    observedAt: proofStartedAt, forced: false, owner: proofOwner, incarnation: proofInc }),
];

/**
 * The proof table (b102 changeset, "Proof of an exit"): every record kind and field combination a
 * ledger can hold about one identity, with whether it proves that identity ended. `conflict` marks
 * a record that makes the identity itself ambiguous (another start for the same child).
 */
const PROOF_TABLE = {
  // The children family is present (one note), so its absence is not what leaves accounting incomplete.
  engine: { subject: proofEngine, setup: [engineStart, rec('children', by.engine, 'child-note', { note: 'the family is present' })], rows: [
    ['engine-exit (REQUESTED)', true, [engineExit()]],
    ['engine-exit (UNEXPECTED, code 1)', true, [engineExit({ exitClass: 'UNEXPECTED', reason: null, code: 1 })]],
    ['engine-exit (UNKNOWN, no code)', true, [engineExit({ exitClass: 'UNKNOWN', code: null })]],
    ['engine-exit marked reconciled', false, [engineExit({ reconciled: true })]],
    ['engine-owner-died GONE', true, [engineOwnerDied('GONE')]],
    ['engine-owner-died GONE, no PID', true, [engineOwnerDied('GONE', { pid: undefined })]],
    ['engine-owner-died GONE naming another PID', false, [engineOwnerDied('GONE', { pid: 971017 })]],
    ['engine-owner-died ALIVE_MATCH', false, [engineOwnerDied('ALIVE_MATCH')]],
    ['engine-owner-died ALIVE_MATCH classed OWNER_DIED', false, [engineOwnerDied('ALIVE_MATCH', { exitClass: 'OWNER_DIED' })]],
    ['engine-owner-died IDENTITY_UNKNOWN', false, [engineOwnerDied('IDENTITY_UNKNOWN')]],
    ['engine-owner-died REUSED', false, [engineOwnerDied('REUSED')]],
    ['engine-owner-died without osCheck, classed OWNER_DIED', false, [engineOwnerDied(null, { exitClass: 'OWNER_DIED' })]],
    ['engine-intent', false, [rec('engine', by.runner, 'engine-intent', { incarnation: proofInc, channel: 'TERMINATE', mode: 'NOW' })]],
    ['ledger-gap', false, [rec('engine', by.runner, 'ledger-gap', { reason: 'WRITE_FAILED', detail: 'fixture' })]],
  ] },
  child: { subject: proofChild, setup: [engineStart, engineExit(),
    rec('children', by.engine, 'child-spawn', { childId: 'c1', kind: 'EXTRACTION', owner: proofOwner, incarnation: proofInc }),
    childStart()], rows: [
    ['child-exit (REQUESTED)', true, [childExit()]],
    ['child-exit (UNEXPECTED, code 3)', true, [childExit({ exitClass: 'UNEXPECTED', reason: null, code: 3 })]],
    ['child-exit marked reconciled', false, [childExit({ reconciled: true })]],
    ['child-exit naming another PID', false, [childExit({ pid: 971017 })]],
    ['owner-died GONE', true, [ownerDied('GONE')]],
    ['owner-died GONE classed UNKNOWN', true, [ownerDied('GONE', { exitClass: 'UNKNOWN' })]],
    ['owner-died ALIVE_MATCH', false, [ownerDied('ALIVE_MATCH')]],
    ['owner-died ALIVE_MATCH classed OWNER_DIED', false, [ownerDied('ALIVE_MATCH', { exitClass: 'OWNER_DIED' })]],
    ['owner-died IDENTITY_UNKNOWN', false, [ownerDied('IDENTITY_UNKNOWN')]],
    ['owner-died REUSED', false, [ownerDied('REUSED')]],
    ['owner-died without osCheck, classed OWNER_DIED', false, [ownerDied(null, { exitClass: 'OWNER_DIED' })]],
    ['child-survived', false, [rec('children', by.later, 'child-survived', { childId: 'c1', basis: 'owner_died_alive', attempts: 1 })]],
    ['stop-intent', false, [childRecord('stop-intent', { intentId: 'i1', reason: 'QUIT' })]],
    ['stop-escalate', false, [childRecord('stop-escalate', { intentId: 'i1' })]],
    ['child-note', false, [childRecord('child-note', { note: 'fixture' })]],
    ['gpu-release', false, [childRecord('gpu-release', { released: true })]],
    ['a second child-start naming another process', false, [childStart(noProcess(971021, '134364634163991105'))], 'conflict'],
  ] },
  witness: { subject: proofWitness, setup: [engineStart, engineExit(), witnessStart], rows: [
    ['its own job-process-exit', true, [witnessExit()]],
    ['its own job-process-exit, code unknown', true, [witnessExit({ code: null, exitClass: 'UNKNOWN' })]],
    ['its own job-process-exit that lost track (no birth)', false, [witnessExit({ creationFileTime: null, code: null, exitClass: 'UNKNOWN' })]],
    ['its own job-process-exit one tick off the start', false, [witnessExit({ creationFileTime: nextTick(proofWitness.creationFileTimeUtc) })]],
    ['its own job-process-exit marked reconciled, no osCheck', false, [reconciledExit(by.engine, null, { exitClass: 'OWNER_DIED' })]],
    ['reconciled GONE by the dev runner', true, [reconciledExit(by.runner, 'GONE')]],
    ['reconciled GONE by the next Engine', true, [reconciledExit(by.later, 'GONE')]],
    ['reconciled GONE by its own Engine', false, [reconciledExit(by.engine, 'GONE')]],
    ['reconciled IDENTITY_UNKNOWN', false, [reconciledExit(by.runner, 'IDENTITY_UNKNOWN')]],
    ['reconciled ALIVE_MATCH', false, [reconciledExit(by.runner, 'ALIVE_MATCH')]],
    ['reconciled without osCheck, classed OWNER_DIED', false, [reconciledExit(by.runner, null, { exitClass: 'OWNER_DIED' })]],
    ['helper-start and helper-exit (an owner receipt alone)', false, helperReceipt],
  ] },
};

/** What the census makes of one ledger: whether `subject` is proven ended, and its accounting. */
function judgeLedger(records, subject) {
  const dataDir = freshDir('proof');
  writeLedger(dataDir, records);
  const ledger = census.readRunLedger(dataDir);
  const view = census.ledgerView(ledger, PROOF_RUN);
  const ended = census.endedIdentitiesOf(view).some((e) => e.pid === subject.pid
    && e.creationFileTimeUtc === subject.creationFileTimeUtc);
  const run = { runId: PROOF_RUN, pids: { backendRootPid: proofEngine.pid } };
  const input = census.exitTargets(run, null, [], ledger, view);
  const result = census.exitResult(input, { processExits: [], gaps: [] }, 'proof table', ledger, view);
  fs.rmSync(dataDir, { recursive: true, force: true });
  return { ended, result, row: result.processExits.find((p) => p.pid === subject.pid) };
}

test('an exit is proven only by an exit its writer saw or a reconciliation that found the process gone, from all of its records in any order', () => {
  const misjudged = [];
  for (const [kind, { subject, setup, rows }] of Object.entries(PROOF_TABLE)) {
    // `conflict`: the child is known by its later start, so a gap names that process instead.
    const judge = (label, records, expected, conflict = false) => {
      const { ended, result, row } = judgeLedger([...setup, ...records], subject);
      if (ended !== expected) misjudged.push(`${kind}: ${label}: proven ${ended}, the table says ${expected}`);
      if (!expected && result.exitAccountingComplete !== false) misjudged.push(`${kind}: ${label}: accounting complete without a proof`);
      if (!expected && !conflict && !result.exitAccountingGaps.some((g) => g.pid === subject.pid)) {
        misjudged.push(`${kind}: ${label}: no gap names the unproven identity`);
      }
      if (!expected && row && row.ledgerClass !== 'UNKNOWN') misjudged.push(`${kind}: ${label}: unproven, yet classed ${row.ledgerClass}`);
    };
    for (const [label, proves, records, conflict] of rows) judge(label, records, proves, Boolean(conflict));
    // Every two records in both orders: a proof stands whatever else is recorded, before or after it.
    for (const [a, aProves, aRecords, aConflict] of rows) {
      for (const [b, bProves, bRecords, bConflict] of rows) {
        if (a === b) continue;
        judge(`${a}, then ${b}`, [...aRecords, ...bRecords], (aProves || bProves) && !aConflict && !bConflict,
          Boolean(aConflict || bConflict));
      }
    }
  }
  assert.deepEqual(misjudged, []);
});

test('a manifest helper is matched to its ledger exit at the millisecond its start was recorded with, and no coarser', async () => {
  const runId = 'stop-census-manifest-ms';
  const engine = noProcess(970501, '134364632933610725');
  // Births with a sub-millisecond part, as the Engine records them; the manifest has milliseconds.
  const ended = noProcess(970503, '134364634153991105');
  const other = noProcess(970507, '134364634163993450');
  const dataDir = freshDir('manifest-ms');
  const ledger = runLedger(dataDir, runId, engine, process.execPath);
  ledger.engineStart();
  ledger.child('c-ended', ended, true);
  ledger.child('c-other', other, true);
  ledger.engineExit();
  const exe = 'C:\\fixture\\parser.exe';
  fs.writeFileSync(path.join(dataDir, 'runtime', 'manifest.json'), JSON.stringify({ pid: engine.pid, children: [
    { id: 'c-ended', pid: ended.pid, kind: 'EXTRACTION', startedAt: isoMs(ended.creationFileTimeUtc), executable: exe },
    // One millisecond after its ledger birth: another process under the same PID.
    { id: 'c-other', pid: other.pid, kind: 'EXTRACTION', startedAt: isoMs(BigInt(other.creationFileTimeUtc) + 10000n), executable: exe },
  ] }));
  const fixture = stopFixture({ runId, dataDir, engine });
  const looked = [];
  const io = { ...fixture.io, lookupIdentities: async (ids) => {
    looked.push([...ids]);
    return new Map(ids.map((pid) => [pid, [ended.pid, other.pid].includes(pid)
      ? { present: true, creationFileTime: null, name: 'parser.exe', executable: exe } : { present: false }]));
  } };
  const out = await api.stopRun({}, io);
  assert.equal(looked.filter((ids) => ids.includes(ended.pid)).length, 0,
    `the helper whose ledger exit matches at the millisecond is never looked up: ${JSON.stringify(looked)}`);
  assert.ok(looked.some((ids) => ids.includes(other.pid)), 'a helper a millisecond off is looked up');
  assert.equal(out.outcome, 'STOP_INCOMPLETE');
  assert.deepEqual([...new Set(out.unresolvedProcesses.map((p) => p.pid))], [other.pid]);
  const report = JSON.parse(fs.readFileSync(path.join(fixture.dir, 'stop-report.json'), 'utf8'));
  assert.ok(report.resolvedByLedger?.some((p) => p.pid === ended.pid), JSON.stringify(report.resolvedByLedger));
});

test('a self-exit report keeps the earlier stop report under the next number', async () => {
  const dir = path.join(scratch, 'self-exit', 'runs', 'run-self');
  fs.mkdirSync(dir, { recursive: true });
  const runPath = path.join(dir, 'run.json');
  const earlier = JSON.stringify({ outcome: 'STOP_INCOMPLETE', note: 'the earlier stop' });
  fs.writeFileSync(path.join(dir, 'stop-report.json'), earlier);
  const report = await api.writeSelfExitStopReport({ runId: 'run-self', runPath, run: { dataDir: null },
    backendExitCode: 137, interactive: false });
  assert.equal(fs.existsSync(path.join(dir, 'stop-report.1.json')), true, 'the earlier stop report is kept');
  assert.equal(fs.readFileSync(path.join(dir, 'stop-report.1.json'), 'utf8'), earlier);
  const onDisk = JSON.parse(fs.readFileSync(path.join(dir, 'stop-report.json'), 'utf8'));
  assert.equal(onDisk.backendExitCode, 137);
  assert.deepEqual(onDisk.earlierReports, ['stop-report.1.json']);
  assert.deepEqual(report.earlierReports, ['stop-report.1.json']);
});

/** stopFixture for the proof run, in a run directory no earlier stop of it has written to. */
function freshProofRun(dataDir, engine) {
  fs.rmSync(path.join(process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT, 'runs', PROOF_RUN), { recursive: true, force: true });
  return stopFixture({ runId: PROOF_RUN, dataDir, engine });
}

test('an identity whose records do not prove its exit is looked up, and keeps the stop and its accounting incomplete', async () => {
  // Incarnation 2, the run's Engine, ended as recorded; the subject is incarnation 1 when it is an Engine.
  const runEngine = noProcess(971025, '134364633933610725');
  const inc2 = { session: PROOF_RUN, n: 2 };
  const runEngineRecords = [rec('engine', by.runner, 'engine-start', { incarnation: inc2, pid: runEngine.pid,
    creationFileTime: runEngine.creationFileTimeUtc, executable: process.execPath, processStartedAt: isoMs(runEngine.creationFileTimeUtc),
    cause: 'START', supervisorKind: 'dev-runner' }), rec('engine', by.runner, 'engine-exit', { incarnation: inc2,
    exitClass: 'REQUESTED', reason: 'QUIT', detail: null, code: 0, codeName: 'ok', exitedAt: null, observedAt: new Date(),
    terminateCalledAt: null, forced: false, phase: 'STOPPING', codeDistinctive: true })];
  const cases = [
    ['child', 'owner-died ALIVE_MATCH classed OWNER_DIED', [ownerDied('ALIVE_MATCH', { exitClass: 'OWNER_DIED' })]],
    ['child', 'owner-died IDENTITY_UNKNOWN classed OWNER_DIED', [ownerDied('IDENTITY_UNKNOWN', { exitClass: 'OWNER_DIED' })]],
    ['child', 'owner-died REUSED', [ownerDied('REUSED', { exitClass: 'OWNER_DIED' })]],
    ['child', 'owner-died without osCheck', [ownerDied(null, { exitClass: 'OWNER_DIED' })]],
    ['child', 'child-survived', [rec('children', by.later, 'child-survived', { childId: 'c1', basis: 'owner_died_alive', attempts: 1 })]],
    ['child', 'child-exit naming another PID', [childExit({ pid: 971017 })]],
    ['child', 'owner-died ALIVE_MATCH, then IDENTITY_UNKNOWN', [ownerDied('ALIVE_MATCH', { exitClass: 'OWNER_DIED' }),
      ownerDied('IDENTITY_UNKNOWN', { exitClass: 'OWNER_DIED' })]],
    ['engine', 'engine-owner-died ALIVE_MATCH classed OWNER_DIED', [engineOwnerDied('ALIVE_MATCH', { exitClass: 'OWNER_DIED' })]],
    ['engine', 'engine-owner-died IDENTITY_UNKNOWN classed OWNER_DIED', [engineOwnerDied('IDENTITY_UNKNOWN', { exitClass: 'OWNER_DIED' })]],
    ['engine', 'engine-owner-died without osCheck', [engineOwnerDied(null, { exitClass: 'OWNER_DIED' })]],
    ['witness', 'its own job-process-exit marked reconciled, no osCheck', [reconciledExit(by.engine, null, { exitClass: 'OWNER_DIED' })]],
    ['witness', 'reconciled IDENTITY_UNKNOWN', [reconciledExit(by.runner, 'IDENTITY_UNKNOWN')]],
    ['witness', 'its own job-process-exit one tick off the start', [witnessExit({ creationFileTime: nextTick(proofWitness.creationFileTimeUtc) })]],
  ];
  const wrong = [];
  for (const [kind, label, records] of cases) {
    const { subject, setup } = PROOF_TABLE[kind];
    const dataDir = freshDir('unproven');
    const engineOfRun = kind === 'engine' ? runEngine : proofEngine;
    writeLedger(dataDir, [...setup.filter((r) => kind !== 'engine' || r.type !== 'engine-exit'), ...records,
      ...(kind === 'engine' ? runEngineRecords : [])]);
    const fixture = freshProofRun(dataDir, engineOfRun);
    fs.writeFileSync(path.join(fixture.dir, 'stop-tracked-processes.json'), JSON.stringify({ processes: [
      { ...subject, role: kind === 'engine' ? 'engine' : 'Engine job member', ...(kind === 'engine' ? { owned: false } : {}) }] }));
    const looked = [];
    // The subject's PID names a process whose birth cannot be read; nothing else is running.
    const io = { ...fixture.io, lookupIdentities: async (ids) => {
      looked.push(...ids);
      return new Map(ids.map((pid) => [pid, pid === subject.pid ? { present: true, creationFileTime: null } : { present: false }]));
    } };
    const out = await api.stopRun({}, io);
    const report = JSON.parse(fs.readFileSync(path.join(fixture.dir, 'stop-report.json'), 'utf8'));
    if (!looked.includes(subject.pid)) wrong.push(`${kind}: ${label}: never looked up`);
    if (out.outcome !== 'STOP_INCOMPLETE') wrong.push(`${kind}: ${label}: outcome ${out.outcome}`);
    if (!(out.unresolvedProcesses ?? []).some((p) => p.pid === subject.pid)) wrong.push(`${kind}: ${label}: not unresolved`);
    if (report.exitAccountingComplete === true) wrong.push(`${kind}: ${label}: accounting complete`);
  }
  assert.deepEqual(wrong, []);
});

test('an exact ledger birth matches only the same birth: a native identity one tick apart is looked up and blocks the stop', async () => {
  const dataDir = freshDir('exact-neighbour');
  writeLedger(dataDir, [...PROOF_TABLE.child.setup, childExit()]);
  const fixture = freshProofRun(dataDir, proofEngine);
  // The tracked identity was read natively (exact, birthTick 1), one tick after the ledger child's birth.
  const neighbour = { pid: proofChild.pid, creationFileTimeUtc: nextTick(proofChild.creationFileTimeUtc), birthTick: '1',
    role: 'Engine job member' };
  fs.writeFileSync(path.join(fixture.dir, 'stop-tracked-processes.json'), JSON.stringify({ processes: [neighbour] }));
  const looked = [];
  const out = await api.stopRun({}, { ...fixture.io, lookupIdentities: async (ids) => {
    looked.push(...ids);
    return new Map(ids.map((pid) => [pid, pid === neighbour.pid ? { present: true, creationFileTime: null } : { present: false }]));
  } });
  assert.ok(looked.includes(neighbour.pid), `the neighbour is looked up: ${JSON.stringify(looked)}`);
  assert.equal(out.outcome, 'STOP_INCOMPLETE');
  assert.deepEqual([...new Set(out.unresolvedProcesses.map((p) => p.pid))], [neighbour.pid]);
  if (!windows) return;
  // The Engine job's stop compares each ledger-ended identity at its own precision too.
  const member = { pid: unreadablePid(), creationFileTimeUtc: '134000000000000006' };
  const dir = freshDir('job-exact');
  const recordPath = path.join(dir, 'engine-1.json');
  fs.writeFileSync(recordPath, JSON.stringify({ schema: 'justsearch-job-run.v1', pid: 971029, creationFileTimeUtc: '134364632930208358',
    root: member, members: [member], state: 'ended', rootExitCode: 0 }));
  const result = jobRun.stopJobRunSync({ recordPath, host: noProcess(971029, '134364632930208358') }, { waitMs: 0,
    endedIdentities: [{ pid: member.pid, creationFileTimeUtc: '134000000000000005', birthTick: '1' }] });
  assert.equal(result.outcome, 'incomplete', JSON.stringify(result));
  assert.deepEqual(result.survivors?.map((p) => Number(p.pid)), [member.pid]);
});

test('preserved: a ledger birth matches at the coarser precision of the two records, no coarser', async () => {
  const dataDir = freshDir('precision');
  const other = noProcess(971033, '134364634153997105');
  writeLedger(dataDir, [...PROOF_TABLE.child.setup, childExit(),
    rec('children', by.engine, 'child-spawn', { childId: 'c2', kind: 'EXTRACTION', owner: proofOwner, incarnation: proofInc }),
    rec('children', by.engine, 'child-start', { childId: 'c2', pid: other.pid, creationFileTime: other.creationFileTimeUtc,
      executable: 'C:\\fixture\\parser.exe', contained: true }),
    rec('children', by.engine, 'child-exit', { childId: 'c2', exitClass: 'REQUESTED', reason: 'QUIT', code: 0, forced: false })]);
  const fixture = freshProofRun(dataDir, proofEngine);
  // Exact and equal; a CIM read (no birthTick: the microsecond) of the other child's microsecond; and
  // an exact native read of the Engine, whose start a dev runner read through CIM, in its microsecond.
  const tracked = [{ ...proofChild, birthTick: '1', role: 'Engine job member' },
    { pid: other.pid, creationFileTimeUtc: String(BigInt(other.creationFileTimeUtc) / 10n * 10n), role: 'Engine job member' },
    { pid: proofEngine.pid, creationFileTimeUtc: String(BigInt(proofEngine.creationFileTimeUtc) / 10n * 10n + 3n), birthTick: '1',
      role: 'Engine job member' }];
  fs.writeFileSync(path.join(fixture.dir, 'stop-tracked-processes.json'), JSON.stringify({ processes: tracked }));
  const looked = [];
  const out = await api.stopRun({}, { ...fixture.io, lookupIdentities: async (ids) => {
    looked.push(...ids);
    return new Map(ids.map((pid) => [pid, { present: true, creationFileTime: null }]));
  } });
  assert.deepEqual(looked.filter((pid) => [proofChild.pid, other.pid, proofEngine.pid].includes(pid)), [], 'all three are proven ended');
  assert.equal(out.outcome, 'STOPPED', JSON.stringify(out.errors));
  if (!windows) return;
  const member = { pid: unreadablePid(), creationFileTimeUtc: '134000000000000006' };
  const dir = freshDir('job-micro');
  const recordPath = path.join(dir, 'engine-1.json');
  fs.writeFileSync(recordPath, JSON.stringify({ schema: 'justsearch-job-run.v1', pid: 971029, creationFileTimeUtc: '134364632930208358',
    root: member, members: [member], state: 'ended', rootExitCode: 0 }));
  const result = jobRun.stopJobRunSync({ recordPath, host: noProcess(971029, '134364632930208358') }, { waitMs: 0,
    endedIdentities: [{ pid: member.pid, creationFileTimeUtc: '134000000000000005', birthTick: '10' }] });
  assert.equal(result.outcome, 'gone', JSON.stringify(result));
});

/** One ledger line of a child the Engine started: generation `n` of the current file. */
const generation = (n, tag = '') => JSON.stringify({ schemaVersion: 1, type: 'child-start', seq: n + 1, at: '2026-10-14T10:00:00Z',
  writer: { role: 'engine', pid: 971101, startedAt: '2026-10-14T10:00:00Z' }, childId: `generation-${n}${tag}`, pid: 971103 + 4 * n,
  creationFileTime: String(134364634150000000n + BigInt(n) * 1000n), executable: 'C:\\fixture\\child.exe' }) + '\n';

/**
 * What a writer or a rotation can do to the current file while a read is under way. `keepsFirst`:
 * the generation-0 record is still in some file of the family afterwards.
 */
const FILE_STATES = {
  'missing (renamed, not yet replaced)': { keepsFirst: true, apply: (dir, current) => fs.renameSync(current, path.join(dir, 'children.v1.1.jsonl')) },
  'renamed and replaced': { keepsFirst: true, apply: (dir, current) => {
    fs.renameSync(current, path.join(dir, 'children.v1.1.jsonl'));
    fs.writeFileSync(current, generation(1));
  } },
  grown: { keepsFirst: true, apply: (dir, current) => fs.appendFileSync(current, generation(1)) },
  'truncated to its first line': { keepsFirst: true, apply: (dir, current) => fs.truncateSync(current, Buffer.byteLength(generation(0))) },
  'deleted and recreated': { keepsFirst: true, apply: (dir, current) => {
    fs.rmSync(current);
    fs.writeFileSync(current, generation(0) + generation(1));
  } },
  'rewritten in place, longer': { keepsFirst: false, apply: (dir, current) => fs.writeFileSync(current,
    generation(5, '-rewritten-in-place-under-the-same-file-id-with-a-longer-first-line') + generation(6)) },
};

/** Runs `change` once, at `boundary` of the reader's handling of `current`: its stat, open, fstat or first read. */
function atBoundary(boundary, current, change) {
  const original = { statSync: fs.statSync, openSync: fs.openSync, fstatSync: fs.fstatSync, readSync: fs.readSync, closeSync: fs.closeSync };
  const fds = new Set();
  let done = false;
  const once = () => { if (!done) { done = true; change(); } };
  const isCurrent = (file) => path.resolve(String(file)) === path.resolve(current);
  fs.statSync = function (file, ...rest) {
    const out = original.statSync.call(this, file, ...rest);
    if (boundary === 'stat' && isCurrent(file)) once();
    return out;
  };
  fs.openSync = function (file, ...rest) {
    if (boundary === 'open' && isCurrent(file)) once();
    const fd = original.openSync.call(this, file, ...rest);
    if (isCurrent(file)) fds.add(fd);
    return fd;
  };
  fs.fstatSync = function (fd, ...rest) {
    const out = original.fstatSync.call(this, fd, ...rest);
    if (boundary === 'fstat' && fds.has(fd)) once();
    return out;
  };
  fs.readSync = function (fd, ...rest) {
    if (boundary === 'read' && fds.has(fd)) once();
    return original.readSync.call(this, fd, ...rest);
  };
  fs.closeSync = function (fd, ...rest) { fds.delete(fd); return original.closeSync.call(this, fd, ...rest); };
  return { restore: () => Object.assign(fs, original), fired: () => done };
}

test('a ledger file missing, renamed, replaced, grown, truncated, recreated or rewritten at the stat, open, fstat or read of a read loses and repeats no record', () => {
  const wrong = [];
  for (const [state, { keepsFirst, apply }] of Object.entries(FILE_STATES)) {
    for (const boundary of ['stat', 'open', 'fstat', 'read']) {
      for (const warm of [false, true]) {
        const label = `${state} at the ${boundary}${warm ? ' (a later read of a cached file)' : ''}`;
        const dir = path.join(freshDir('file-states'), 'ledger');
        fs.mkdirSync(dir, { recursive: true });
        const current = path.join(dir, 'children.v1.jsonl');
        fs.writeFileSync(current, generation(0) + generation(9));
        const reader = ledgerLib.createIncrementalReader();
        if (warm) {
          reader.read(dir, 'children');
          fs.appendFileSync(current, generation(2)); // so the later read opens the file again
        }
        const hook = atBoundary(boundary, current, () => apply(dir, current));
        let first, error = null;
        try { first = reader.read(dir, 'children'); } catch (err) { error = err; } finally { hook.restore(); }
        if (!hook.fired()) { wrong.push(`${label}: the change never happened`); continue; }
        if (error) { wrong.push(`${label}: threw ${error.code ?? ''} ${error.message}`); continue; }
        const ids = first.records.map((r) => r.line.childId);
        if (new Set(ids).size !== ids.length) wrong.push(`${label}: a record read twice: ${ids}`);
        if (keepsFirst && !ids.includes('generation-0')) wrong.push(`${label}: generation 0 lost: ${ids}`);
        const truth = ledgerLib.readLedger(dir, 'children').records.map((r) => r.line.childId).sort();
        const again = reader.read(dir, 'children').records.map((r) => r.line.childId).sort();
        if (JSON.stringify(again) !== JSON.stringify(truth)) wrong.push(`${label}: then read ${again}, the files hold ${truth}`);
      }
    }
  }
  assert.deepEqual(wrong, []);
});

test('a ledger family whose files never settle is unreadable, a named gap, and never complete', () => {
  const dataDir = freshDir('unsettled');
  writeLedger(dataDir, [...PROOF_TABLE.child.setup, childExit()]);
  const dir = ledgerLib.ledgerDirectory(dataDir);
  const current = path.join(dir, 'children.v1.jsonl');
  const text = fs.readFileSync(current);
  // A writer rotating at every read: each stat of the current file is followed by its rotation.
  let rotations = 0;
  const statSync = fs.statSync;
  fs.statSync = function (file, ...rest) {
    const out = statSync.call(this, file, ...rest);
    if (path.resolve(String(file)) === path.resolve(current)) {
      rotations += 1;
      fs.renameSync(current, path.join(dir, `children.v1.${rotations + 1}.jsonl`));
      fs.writeFileSync(current, text);
    }
    return out;
  };
  let ledger, thrown = null;
  try {
    try { ledgerLib.createIncrementalReader().read(dir, 'children'); } catch (err) { thrown = err; }
    ledger = census.readRunLedger(dataDir, ledgerLib.createIncrementalReader());
  } finally {
    fs.statSync = statSync;
  }
  assert.equal(thrown?.code, 'LEDGER_UNSETTLED', `a read of files that never settle throws: ${thrown}`);
  assert.match(ledger.children.error ?? '', /still moving/);
  const view = census.ledgerView(ledger, PROOF_RUN);
  const run = { runId: PROOF_RUN, pids: { backendRootPid: proofEngine.pid } };
  const result = census.exitResult(census.exitTargets(run, null, [], ledger, view), { processExits: [], gaps: [] }, 'unsettled', ledger, view);
  assert.equal(result.exitAccountingComplete, false);
  assert.ok(result.exitAccountingGaps.some((g) => g.reason === 'ledger-unreadable' && g.family === 'children'),
    JSON.stringify(result.exitAccountingGaps));
});

test('a rotation that renames the current file and has not yet replaced it, at the verdict\'s read, loses no record', async () => {
  const runId = 'stop-census-rename-gap';
  const engine = noProcess(971201, '134364632933610725');
  const child = noProcess(971205, '134364634153991105');
  const dataDir = freshDir('rename-gap');
  const ledger = runLedger(dataDir, runId, engine, process.execPath);
  ledger.engineStart();
  ledger.child('c-exited', child, true);
  ledger.engineExit();
  const dir = ledgerLib.ledgerDirectory(dataDir);
  const current = path.join(dir, 'children.v1.jsonl');
  let hook = null;
  const armed = await arm({ runId, pids: { backendRootPid: engine.pid } }, dataDir, [], () => {
    // A note written after the census armed, so the verdict opens the current file again; the
    // rotation renames it at that open, and its replacement does not exist yet.
    fs.appendFileSync(current, JSON.stringify({ schemaVersion: 1, type: 'child-note', seq: 99, at: new Date().toISOString(),
      writer: { role: 'engine', pid: engine.pid, startedAt: isoMs(engine.creationFileTimeUtc) }, note: 'after arming' }) + '\n');
    hook = atBoundary('open', current, () => fs.renameSync(current, path.join(dir, 'children.v1.1.jsonl')));
  }, { ledgerReader: ledgerLib.createIncrementalReader() });
  let result;
  try { result = await armed.observer.finish(); } finally { hook?.restore(); }
  assert.ok(hook?.fired(), 'the rotation happened at the verdict\'s open');
  const row = result.processExits.find((p) => p.pid === child.pid);
  assert.equal(row?.ledgerClass, 'REQUESTED', `the exited child is accounted: ${JSON.stringify(result.exitAccountingGaps)}`);
  assert.equal(result.exitAccountingComplete, true, JSON.stringify(result.exitAccountingGaps));
});

/**
 * A real Windows reader holding `file` open without delete sharing (FileShare.Read), as an editor,
 * scanner or indexer can, until released. Only this test's own process is started and it ends itself.
 */
function holdOpen(file) {
  const ready = `${file}.held`, release = `${file}.release`;
  const quote = (p) => `'${p.replaceAll("'", "''")}'`;
  const script = `$f = [System.IO.File]::Open(${quote(file)}, 'Open', 'Read', 'Read'); New-Item -ItemType File -Path ${quote(ready)} | Out-Null; `
    + `$until = (Get-Date).AddSeconds(60); while (-not (Test-Path -LiteralPath ${quote(release)}) -and (Get-Date) -lt $until) `
    + `{ Start-Sleep -Milliseconds 10 }; $f.Close()`;
  const proc = cp.spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], { stdio: 'ignore', windowsHide: true });
  const exited = new Promise((resolve) => proc.once('exit', resolve));
  return {
    held: async () => {
      const until = Date.now() + 30000;
      while (!fs.existsSync(ready) && Date.now() < until) await new Promise((r) => setTimeout(r, 20));
      assert.ok(fs.existsSync(ready), 'the reader holds the file');
    },
    release: async () => { fs.writeFileSync(release, ''); await exited; fs.rmSync(ready, { force: true }); fs.rmSync(release, { force: true }); },
  };
}

test('a stop report another reader holds open is kept: archived once the reader lets go, or else left untouched beside the new report', { skip: !windows && 'Windows sharing' }, async () => {
  const outcomes = {};
  for (const holdMs of [650, null]) {
    const runId = `stop-census-report-held-${holdMs ?? 'held'}`;
    const engine = noProcess(971301 + (holdMs ? 0 : 4), '134364632933610725');
    const dataDir = freshDir('report-held');
    const ledger = runLedger(dataDir, runId, engine, process.execPath);
    ledger.engineStart();
    ledger.engineExit();
    const fixture = stopFixture({ runId, dataDir, engine });
    const reportPath = path.join(fixture.dir, 'stop-report.json');
    const prior = Buffer.from('{"stop":"first-report","evidence":"must-survive"}\n');
    fs.writeFileSync(reportPath, prior);
    const reader = holdOpen(reportPath);
    await reader.held();
    // The reader lets go 650 ms after the stop's first rename meets it, or only after the stop.
    const renameSync = fs.renameSync;
    const refused = [];
    fs.renameSync = function (from, to, ...rest) {
      try { return renameSync.call(this, from, to, ...rest); } catch (err) {
        if (path.resolve(String(from)) === path.resolve(reportPath)) {
          if (!refused.length && holdMs) setTimeout(() => { reader.release(); }, holdMs);
          refused.push(err.code);
        }
        throw err;
      }
    };
    let out;
    try { out = await api.stopRun({}, fixture.io).catch((err) => ({ thrown: String(err) })); } finally {
      fs.renameSync = renameSync;
      await reader.release();
    }
    outcomes[holdMs ?? 'held'] = { out, refused, files: fs.readdirSync(fixture.dir).filter((n) => n.startsWith('stop-report')).sort() };
    assert.ok(refused.length > 0 && refused.every((c) => ['EBUSY', 'EPERM', 'EACCES'].includes(c)),
      `the held report refused the rename: ${JSON.stringify(outcomes)}`);
    assert.equal(out.thrown, undefined, JSON.stringify(outcomes));
    if (holdMs) {
      const archived = path.join(fixture.dir, 'stop-report.1.json');
      assert.ok(fs.existsSync(archived) && fs.readFileSync(archived).equals(prior),
        `the earlier report is kept as it was: ${JSON.stringify(outcomes)}`);
      assert.equal(JSON.parse(fs.readFileSync(reportPath, 'utf8')).runId, runId, 'stop-report.json is this stop\'s');
      assert.equal(out.reportWrittenAs, undefined);
    } else {
      assert.ok(fs.readFileSync(reportPath).equals(prior), 'the earlier report is never overwritten');
      assert.equal(out.reportWrittenAs, 'stop-report.1.json', JSON.stringify(outcomes));
      assert.equal(out.earlierReportNotArchived?.file, 'stop-report.json');
      assert.equal(path.basename(out.stopReportPath), 'stop-report.1.json');
      assert.ok(fs.existsSync(path.join(fixture.dir, 'stop-report.1.json')), 'this report is where the result says');
      const written = JSON.parse(fs.readFileSync(path.join(fixture.dir, 'stop-report.1.json'), 'utf8'));
      assert.deepEqual([written.runId, written.reportWrittenAs], [runId, 'stop-report.1.json']);
    }
  }
});

test('every earlier census file is kept and named, one that cannot be read or renamed included', async () => {
  // An earlier census whose targets file is torn: kept as it is, named, and marked unreadable.
  const runId = 'stop-census-torn-census';
  const engine = noProcess(971401, '134364632933610725');
  const dataDir = freshDir('torn-census');
  const ledger = runLedger(dataDir, runId, engine, process.execPath);
  ledger.engineStart();
  ledger.engineExit();
  const torn = Buffer.from('{"targets":[TORN');
  fs.writeFileSync(path.join(dataDir, 'exit-targets.json'), torn);
  fs.writeFileSync(path.join(dataDir, 'process-exits.json'), JSON.stringify({ processExits: [] }));
  const result = await (await arm({ runId, pids: { backendRootPid: engine.pid } }, dataDir, [])).observer.finish();
  assert.deepEqual(result.earlierCensus, ['exit-targets.1.json', 'process-exits.1.json']);
  assert.deepEqual(result.earlierCensusFiles, [{ name: 'exit-targets.1.json', readable: false },
    { name: 'process-exits.1.json', readable: true }]);
  assert.ok(fs.readFileSync(path.join(dataDir, 'exit-targets.1.json')).equals(torn), 'kept as it was');
  if (!windows) return;
  // An earlier census file another reader holds open: left untouched, and this census written beside it.
  const heldDir = freshDir('held-census');
  const heldLedger = runLedger(heldDir, runId, engine, process.execPath);
  heldLedger.engineStart();
  heldLedger.engineExit();
  const earlier = Buffer.from(JSON.stringify({ targets: [] }));
  const earlierPath = path.join(heldDir, 'exit-targets.json');
  fs.writeFileSync(earlierPath, earlier);
  const reader = holdOpen(earlierPath);
  await reader.held();
  let census2;
  try {
    const armed = await arm({ runId, pids: { backendRootPid: engine.pid } }, heldDir, []).catch((err) => ({ thrown: String(err) }));
    assert.equal(armed.thrown, undefined, armed.thrown);
    census2 = await armed.observer.finish();
  } finally {
    await reader.release();
  }
  assert.ok(fs.readFileSync(earlierPath).equals(earlier), 'the held earlier census is never overwritten');
  assert.deepEqual(census2.censusWrittenAs, ['exit-targets.1.json', 'process-exits.1.json']);
  assert.equal(census2.earlierCensusNotArchived?.[0]?.file, 'exit-targets.json');
  assert.ok(fs.existsSync(path.join(heldDir, 'process-exits.1.json')), 'this census is where it says');
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

/**
 * stopRun over `dataDir`, with every read of its ledger directory counted per file, whichever way
 * it is read: whole-file reads, and reads through a descriptor (each call, and the bytes).
 */
async function measuredStop(runId, dataDir, engine) {
  const ledgerDir = path.resolve(ledgerLib.ledgerDirectory(dataDir)).toLowerCase();
  const inLedger = (file) => typeof file === 'string' && path.resolve(file).toLowerCase().startsWith(ledgerDir);
  const reads = new Map();
  const of = (file) => {
    const key = path.basename(String(file));
    if (!reads.has(key)) reads.set(key, { readFileCalls: 0, readSyncCalls: 0, bytes: 0 });
    return reads.get(key);
  };
  const fds = new Map();
  const original = { openSync: fs.openSync, closeSync: fs.closeSync, readSync: fs.readSync, readFileSync: fs.readFileSync };
  fs.openSync = function (file, ...rest) { const fd = original.openSync.call(this, file, ...rest); if (inLedger(file)) fds.set(fd, file); return fd; };
  fs.closeSync = function (fd, ...rest) { fds.delete(fd); return original.closeSync.call(this, fd, ...rest); };
  fs.readSync = function (fd, ...rest) {
    const n = original.readSync.call(this, fd, ...rest);
    if (fds.has(fd)) { const m = of(fds.get(fd)); m.readSyncCalls += 1; m.bytes += n; }
    return n;
  };
  fs.readFileSync = function (file, ...rest) {
    const out = original.readFileSync.call(this, file, ...rest);
    if (inLedger(file)) { const m = of(file); m.readFileCalls += 1; m.bytes += Buffer.byteLength(out); }
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
  const report = JSON.parse(fs.readFileSync(path.join(fixture.dir, 'stop-report.json'), 'utf8'));
  const files = fs.readdirSync(ledgerLib.ledgerDirectory(dataDir)).filter((n) => n.endsWith('.jsonl'));
  return { out, report, elapsedMs, reads, files, bytesRead: [...reads.values()].reduce((sum, m) => sum + m.bytes, 0) };
}

/** No ledger file changed during the stop: each is read once, in one read, and never whole again. */
function assertReadOnce(measured) {
  for (const file of measured.files) {
    const m = measured.reads.get(file) ?? { readFileCalls: 0, readSyncCalls: 0 };
    assert.deepEqual([m.readFileCalls, m.readSyncCalls], [0, 1], `${file} is read once per stop: ${JSON.stringify(m)}`);
  }
}

test('a stop over a 17 MB ledger reads each ledger file once and stays well inside the 60 s stop limit', async () => {
  const runId = 'stop-census-17mb';
  const engine = noProcess(970301, '134364632933610725');
  const dataDir = freshDir('large');
  const ledgerBytes = syntheticLedger(dataDir, runId, engine, process.execPath, 17 * 1024 * 1024);
  assert.ok(ledgerBytes >= 17 * 1024 * 1024, `the ledger is at least 17 MiB: ${ledgerBytes}`);
  const measured = await measuredStop(runId, dataDir, engine);
  const { out, report, elapsedMs, bytesRead } = measured;
  console.log(`stop over a ${ledgerBytes}-byte ledger: ${elapsedMs} ms, ${bytesRead} ledger bytes read`);
  assert.equal(out.outcome, 'STOPPED', JSON.stringify(out.errors));
  assert.equal(report.exitAccountingComplete, true, JSON.stringify(report.exitAccountingGaps?.slice(0, 3)));
  assert.equal(report.ledgerSummary.jobProcesses, 2229);
  assert.ok(bytesRead <= ledgerBytes + 64 * 1024, `each ledger file is read once per stop: ${bytesRead} bytes read of ${ledgerBytes}`);
  assertReadOnce(measured);
  assert.ok(elapsedMs < 20_000, `the stop takes a third of its 60 s limit at most: ${elapsedMs} ms`);
});

test('a stop over a 17 MB ledger of 19,000 Engine children reads each file once and stays well inside the 60 s limit', async () => {
  const runId = 'stop-census-children';
  const engine = noProcess(970901, '134364632933610725');
  const dataDir = freshDir('children');
  const dir = ledgerLib.ledgerDirectory(dataDir);
  fs.mkdirSync(dir, { recursive: true });
  const startedAt = isoMs(engine.creationFileTimeUtc);
  const owner = { pid: engine.pid, creationFileTime: engine.creationFileTimeUtc, instanceId: 'children-engine' };
  const incarnation = { session: runId, n: 1 };
  let seq = 0;
  const line = (role, type, fields) => JSON.stringify({ schemaVersion: 1, type, seq: ++seq, at: startedAt,
    writer: { role, pid: role === 'engine' ? engine.pid : 4000, startedAt }, ...fields }) + '\n';
  fs.writeFileSync(path.join(dir, 'engine.v1.jsonl'),
    line('dev-runner', 'engine-start', { incarnation, pid: engine.pid, creationFileTime: engine.creationFileTimeUtc,
      executable: process.execPath, processStartedAt: startedAt, cause: 'START', supervisorKind: 'dev-runner' })
    + line('dev-runner', 'engine-exit', { incarnation, exitClass: 'REQUESTED', reason: 'QUIT', detail: null, code: 0,
      codeName: 'ok', exitedAt: null, observedAt: startedAt, terminateCalledAt: null, forced: false, phase: 'STOPPING',
      codeDistinctive: true }));
  // The shape the verifier measured: each child's spawn, start and exit, 17,000,000 bytes in all.
  const chunks = [];
  let bytes = 0, children = 0;
  while (bytes < 17_000_000) {
    const childId = `child-${children}`;
    const born = String(BigInt(engine.creationFileTimeUtc) + 10000n * BigInt(++children));
    const chunk = line('engine', 'child-spawn', { childId, owner, kind: 'EXTRACTION' })
      + line('engine', 'child-start', { childId, pid: 100001 + 4 * children, creationFileTime: born,
        executable: 'C:\\fixture\\parser.exe', contained: true })
      + line('engine', 'child-exit', { childId, exitClass: 'REQUESTED', reason: 'QUIT', code: 0, forced: false, exitedAt: startedAt });
    chunks.push(chunk);
    bytes += Buffer.byteLength(chunk);
  }
  fs.writeFileSync(path.join(dir, 'children.v1.jsonl'), chunks.join(''));
  const measured = await measuredStop(runId, dataDir, engine);
  const { out, report, elapsedMs } = measured;
  console.log(`stop over ${children} ledger children (${bytes} bytes): ${elapsedMs} ms, reads ${JSON.stringify(Object.fromEntries(measured.reads))}`);
  assert.equal(out.outcome, 'STOPPED', JSON.stringify(out.errors));
  assert.equal(report.exitAccountingComplete, true, JSON.stringify(report.exitAccountingGaps?.slice(0, 3)));
  assert.equal(report.ledgerSummary.children, children);
  assertReadOnce(measured);
  // b599d4d7e took about 19 s here (two whole reads), 5b53911da about 35 s (a view per phase).
  assert.ok(elapsedMs < 10_000, `the stop takes a sixth of its 60 s limit at most: ${elapsedMs} ms`);
});
