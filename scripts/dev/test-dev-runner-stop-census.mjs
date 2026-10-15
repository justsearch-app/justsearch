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
const actuator = require('./lib/engine-actuator.cjs');
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
// An exit naming the whole identity of a start of the proof child: its PID, birth, owner and incarnation.
const fullChildExit = (x = proofChild, fields = {}) => childExit({ pid: x.pid, creationFileTime: x.creationFileTimeUtc,
  owner: proofOwner, incarnation: proofInc, ...fields });
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
 * a record that puts another identity under the same childId (another start), so that a record
 * naming only the childId no longer proves either; `contradicts` marks a record of the key that names
 * another PID, birth or owner than the start, so that no key-only record proves it (round 8: no other
 * record may conflict); `specific` marks a record naming the identity's every field (PID, birth, owner,
 * incarnation), which proves it whatever else shares its key.
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
    ['engine-owner-died GONE naming another PID', false, [engineOwnerDied('GONE', { pid: 971017 })], 'contradicts'],
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
    ['child-exit (UNKNOWN, no code)', true, [childExit({ exitClass: 'UNKNOWN', reason: null, code: null })]],
    ['child-exit marked reconciled', false, [childExit({ reconciled: true })]],
    ['child-exit naming another PID', false, [childExit({ pid: 971017 })], 'contradicts'],
    // Round 8: a key-only record written by another Engine than the child's owner (a successor's
    // predecessor stop, say) names no owner, so nothing binds it to this start.
    ['child-exit naming only the childId, written by another Engine', false,
      [rec('children', by.later, 'child-exit', { childId: 'c1', exitClass: 'REQUESTED', reason: 'QUIT', code: 0, forced: false,
        exitedAt: iso7(BigInt(proofChild.creationFileTimeUtc) + 450000n) })]],
    ['owner-died GONE naming another owner instance', false, [ownerDied('GONE', { previousOwner: { ...proofOwner,
      instanceId: 'inst-2' } })], 'contradicts'],
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
    ['child-exit naming its PID, birth, owner and incarnation', true, [fullChildExit()], 'specific'],
    // Round 8: a record naming the PID and birth must carry every field the start carries.
    ['child-exit naming its PID and birth only', false, [childExit({ pid: proofChild.pid,
      creationFileTime: proofChild.creationFileTimeUtc })]],
    ['child-exit naming its PID, birth and owner, not its incarnation', false, [fullChildExit(proofChild, { incarnation: undefined })]],
    ['child-exit naming its PID, birth and incarnation, not its owner', false, [fullChildExit(proofChild, { owner: undefined })]],
    ['child-exit naming its PID and another birth', false, [fullChildExit(proofChild, {
      creationFileTime: nextTick(proofChild.creationFileTimeUtc) })], 'contradicts'],
    ['owner-died GONE naming its PID, birth and incarnation', true, [ownerDied('GONE', { pid: proofChild.pid,
      creationFileTime: proofChild.creationFileTimeUtc, incarnation: proofInc })], 'specific'],
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
    ['its own job-process-exit naming another owner instance', false, [witnessExit({ owner: { ...proofOwner, instanceId: 'inst-2' } })]],
    ['reconciled GONE by the dev runner, naming another owner instance', false,
      [reconciledExit(by.runner, 'GONE', { owner: { ...proofOwner, instanceId: 'inst-2' } })]],
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
    const judge = (label, records, expected) => {
      const { ended, result, row } = judgeLedger([...setup, ...records], subject);
      if (ended !== expected) misjudged.push(`${kind}: ${label}: proven ${ended}, the table says ${expected}`);
      if (!expected && result.exitAccountingComplete !== false) misjudged.push(`${kind}: ${label}: accounting complete without a proof`);
      if (!expected && !result.exitAccountingGaps.some((g) => g.pid === subject.pid)) {
        misjudged.push(`${kind}: ${label}: no gap names the unproven identity`);
      }
      if (!expected && row && row.ledgerClass !== 'UNKNOWN') misjudged.push(`${kind}: ${label}: unproven, yet classed ${row.ledgerClass}`);
    };
    for (const [label, proves, records] of rows) judge(label, records, proves);
    // Every two records in both orders: a proof stands whatever else is recorded, before or after it,
    // except that another start under the same key, or a record of the key that contradicts the
    // start, leaves only a record naming the whole identity.
    const proves = ([, p, , flag], other) => p && (flag === 'specific' || !['conflict', 'contradicts'].includes(other[3]));
    for (const a of rows) {
      for (const b of rows) {
        if (a === b) continue;
        judge(`${a[0]}, then ${b[0]}`, [...a[2], ...b[2]], proves(a, b) || proves(b, a));
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
    ['witness', 'its own job-process-exit naming another owner instance', [witnessExit({ owner: { ...proofOwner, instanceId: 'inst-2' } })]],
    ['child', 'a second start under its childId, which exits by its identity', [childStart(noProcess(971021, '134364634163991105')),
      childExit({ pid: 971021, creationFileTime: '134364634163991105' })]],
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

test('a ledger file missing, renamed, replaced, grown, truncated, recreated or rewritten at the stat, open, fstat or read of a read loses and repeats no record; a rewrite in place under the same id shows as damage', () => {
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
        const later = reader.read(dir, 'children');
        const again = later.records.map((r) => r.line.childId).sort();
        // A reader reads only the tail of a file that grew (round 6): a file rewritten in place under
        // the same id then shows as damage in its tail, a gap, never as a silent difference.
        const damaged = later.corruptLines > 0 && state.startsWith('rewritten in place');
        if (JSON.stringify(again) !== JSON.stringify(truth) && !damaged) wrong.push(`${label}: then read ${again}, the files hold ${truth}`);
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
 * A real Windows reader holding `file` open without delete sharing (FileShare.Read, or `share`: None
 * also refuses other readers), as an editor, scanner or indexer can, until released. Only this
 * test's own process is started and it ends itself.
 */
function holdOpen(file, share = 'Read') {
  const ready = `${file}.held`, release = `${file}.release`;
  const quote = (p) => `'${p.replaceAll("'", "''")}'`;
  const script = `$f = [System.IO.File]::Open(${quote(file)}, 'Open', 'Read', '${share}'); New-Item -ItemType File -Path ${quote(ready)} | Out-Null; `
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
    let out, again;
    try {
      out = await api.stopRun({}, fixture.io).catch((err) => ({ thrown: String(err) }));
      // Still held: a second stop keeps it too, and lists every earlier report.
      if (!holdMs) again = await api.stopRun({}, fixture.io).catch((err) => ({ thrown: String(err) }));
    } finally {
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
      const heldReport = { name: 'stop-report.json', archived: false, readable: true };
      assert.deepEqual([out.earlierReports, out.earlierReportFiles], [['stop-report.json'], [heldReport]], 'the held report is listed');
      assert.deepEqual([written.earlierReports, written.earlierReportFiles], [['stop-report.json'], [heldReport]]);
      assert.equal(again.thrown, undefined, again.thrown);
      assert.equal(again.reportWrittenAs, 'stop-report.2.json');
      assert.deepEqual(again.earlierReports, ['stop-report.1.json', 'stop-report.json']);
      assert.deepEqual(again.earlierReportFiles, [{ name: 'stop-report.1.json', archived: true, readable: true }, heldReport]);
      assert.ok(fs.readFileSync(reportPath).equals(prior), 'still never overwritten');
      assert.equal(JSON.parse(fs.readFileSync(path.join(fixture.dir, 'stop-report.1.json'), 'utf8')).reportWrittenAs,
        'stop-report.1.json', 'the first stop\'s report is kept');
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
  assert.deepEqual(result.earlierCensusFiles, [{ name: 'exit-targets.1.json', readable: false, archived: true },
    { name: 'process-exits.1.json', readable: true, archived: true }]);
  assert.ok(fs.readFileSync(path.join(dataDir, 'exit-targets.1.json')).equals(torn), 'kept as it was');
  assert.equal(result.exitAccountingComplete, false, 'what the unreadable census observed is not accounted');
  assert.ok(result.exitAccountingGaps.some((g) => g.reason === 'earlier-census-unreadable' && g.file === 'exit-targets.1.json'),
    JSON.stringify(result.exitAccountingGaps));
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

/** A live child of this test that exits with `code` when told to (`end`); its exact native birth. */
async function liveChild(code = 0) {
  const proc = cp.spawn(process.execPath, ['-e', `process.on('message', () => process.exit(${code})); setTimeout(() => process.exit(${code}), 120000)`],
    { stdio: ['ignore', 'ignore', 'ignore', 'ipc'], windowsHide: true });
  const exited = new Promise((resolve) => proc.once('exit', resolve));
  await new Promise((resolve) => proc.once('spawn', resolve));
  const out = cp.spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
    `[Diagnostics.Process]::GetProcessById(${proc.pid}).StartTime.ToUniversalTime().ToFileTimeUtc()`],
    { encoding: 'utf8', windowsHide: true, timeout: 60000 });
  const birth = String(out.stdout).trim();
  assert.match(birth, /^[1-9][0-9]+$/, `the child's native birth: ${out.stderr}`);
  return { pid: proc.pid, creationFileTimeUtc: birth, exited,
    end: async () => {
      if (proc.exitCode === null && proc.signalCode === null) { try { proc.send('end'); } catch { /* already ending */ } }
      return exited;
    } };
}
/** A birth one tick from `ft` inside the same microsecond: what a coarsened comparison cannot tell apart. */
const sameMicrosecondNeighbour = (ft) => String(BigInt(ft) % 10n === 9n ? BigInt(ft) - 1n : BigInt(ft) + 1n);
/** One more ledger line, appended as its writer would. */
let appendedSeq = 10000;
function appendLedger(dataDir, { family, writer, type, fields }) {
  fs.appendFileSync(path.join(ledgerLib.ledgerDirectory(dataDir), `${family}.v1.jsonl`), JSON.stringify({ schemaVersion: 1, type,
    seq: ++appendedSeq, at: new Date().toISOString(), writer, ...fields }) + '\n');
}
/** The real census, with the real PowerShell observer, of the proof run's ledger in `dataDir`. */
async function realCensus(dataDir, runDir) {
  return census.startStopExitCensus({ runId: PROOF_RUN, pids: { backendRootPid: proofEngine.pid } }, dataDir, runDir, scope);
}

test('a native ledger birth is compared exactly: a child recorded one tick off its own birth is not observed, and accounting is not complete', async () => {
  // Each target's birth carries the precision its source measured.
  const ledger = (records) => { const dataDir = freshDir('ticks'); writeLedger(dataDir, records); return census.readRunLedger(dataDir); };
  const childSpawn = PROOF_TABLE.child.setup[2];
  const ticks = census.exitTargets({ runId: PROOF_RUN, pids: { backendRootPid: proofEngine.pid } }, null, [],
    ledger([engineStart, childSpawn, childStart()])).targets.map((t) => [t.role, t.birthTick]);
  const wrong = [];
  const check = (ok, what) => { if (!ok) wrong.push(what); };
  check(JSON.stringify(ticks.sort()) === JSON.stringify([['engine', '10'], ['extraction-child', '1']]),
    `a dev runner's Engine start is CIM (10), the Engine's child native (1): ${JSON.stringify(ticks)}`);
  const selfWritten = rec('engine', { role: 'engine', pid: proofEngine.pid, startedAt: proofStartedAt }, 'engine-start', engineStart.fields);
  const selfTicks = census.exitTargets({ runId: PROOF_RUN, pids: {} }, null, [], ledger([selfWritten])).targets.map((t) => t.birthTick);
  check(JSON.stringify(selfTicks) === '["1"]', `an Engine start the Engine wrote is native: ${selfTicks}`);
  // A held handle one tick off the exact ledger birth names another process.
  const dataDir = freshDir('exact-held');
  writeLedger(dataDir, [...PROOF_TABLE.child.setup]);
  const input = census.exitTargets({ runId: PROOF_RUN, pids: { backendRootPid: proofEngine.pid } }, null, [], census.readRunLedger(dataDir));
  appendLedger(dataDir, childExit());
  const verdict = (osBirth) => census.exitResult(input, { processExits: input.targets.map((t) => ({ pid: t.pid,
    creationFileTimeUtc: t.creationFileTimeUtc, osCreationFileTimeUtc: t.pid === proofChild.pid ? osBirth : t.creationFileTimeUtc,
    exitCode: 0, atMs: Date.now() })) }, 'exact', census.readRunLedger(dataDir));
  const own = verdict(proofChild.creationFileTimeUtc);
  check(own.exitAccountingComplete === true, `its own birth is observed: ${JSON.stringify(own.exitAccountingGaps)}`);
  const neighbour = verdict(sameMicrosecondNeighbour(proofChild.creationFileTimeUtc));
  check(neighbour.exitAccountingComplete === false && neighbour.exitAccountingGaps.some((g) => g.pid === proofChild.pid),
    `a held handle one tick off is not the child: ${JSON.stringify(neighbour.exitAccountingGaps)}`);
  if (!windows) { assert.deepEqual(wrong, []); return; }
  // The real observer: a running child whose ledger birth is its own, then one recorded one tick off.
  for (const offByOne of [false, true]) {
    const child = await liveChild();
    const recorded = { pid: child.pid, creationFileTimeUtc: offByOne ? sameMicrosecondNeighbour(child.creationFileTimeUtc)
      : child.creationFileTimeUtc };
    const data = freshDir(offByOne ? 'real-neighbour' : 'real-own');
    writeLedger(data, [engineStart, engineExit(), childSpawn, childStart(recorded)]);
    let result;
    try {
      const armed = await realCensus(data, data);
      const target = JSON.parse(fs.readFileSync(path.join(data, 'exit-targets.json'), 'utf8')).targets.find((t) => t.pid === child.pid);
      check(target?.birthTick === '1', `the real observer's target is exact: ${JSON.stringify(target)}`);
      await child.end();
      appendLedger(data, rec('children', by.engine, 'child-exit', { childId: 'c1', exitClass: 'REQUESTED', reason: 'QUIT', code: 0,
        forced: false, exitedAt: new Date().toISOString() }));
      result = await armed.finish();
    } finally { await child.end(); }
    if (!offByOne) {
      check(result.exitAccountingComplete === true, `the real observer, its own birth: ${JSON.stringify(result.exitAccountingGaps)}`);
    } else {
      check(result.exitAccountingComplete === false && result.exitAccountingGaps.some((g) => g.pid === child.pid
        && /identity mismatch/.test(g.reason)), `the real observer, a ledger birth one tick off the process: complete `
        + `${result.exitAccountingComplete}, ${JSON.stringify(result.exitAccountingGaps)}`);
    }
  }
  assert.deepEqual(wrong, []);
});

test('each start under one childId is its own identity: an exit naming one start never accounts another', async () => {
  const p = [noProcess(971501, '134364634153991105'), noProcess(971505, '134364634153993105'), noProcess(971509, '134364634153995105')];
  const startOf = (x) => childStart(x);
  const exitOf = (x, fields = {}) => fullChildExit(x, fields);
  const judge = (records) => {
    const dataDir = freshDir('shared-child-id');
    writeLedger(dataDir, [engineStart, engineExit(), PROOF_TABLE.child.setup[2], ...records]);
    const ledger = census.readRunLedger(dataDir);
    const view = census.ledgerView(ledger, PROOF_RUN);
    const run = { runId: PROOF_RUN, pids: { backendRootPid: proofEngine.pid } };
    const input = census.exitTargets(run, null, [], ledger, view);
    const result = census.exitResult(input, { processExits: [], gaps: [] }, 'shared childId', ledger, view);
    return { ended: census.endedIdentitiesOf(view).filter((e) => e.role !== 'engine').map((e) => e.pid).sort(), targets: input.targets.map((t) => t.pid).sort(),
      gapped: [...new Set(result.exitAccountingGaps.map((g) => g.pid).filter(Boolean))].sort(), complete: result.exitAccountingComplete };
  };
  const wrong = [];
  const expect = (label, records, ended) => {
    const got = judge(records);
    const open = p.filter((x) => records.some((r) => r.type === 'child-start' && r.fields.pid === x.pid) && !ended.includes(x.pid))
      .map((x) => x.pid).sort();
    if (JSON.stringify(got.ended) !== JSON.stringify([...ended].sort())) wrong.push(`${label}: ended ${got.ended}, expected ${ended}`);
    // Every start not proven ended is armed (it may be running) and keeps accounting incomplete.
    if (JSON.stringify(got.targets) !== JSON.stringify(open)) wrong.push(`${label}: armed ${got.targets}, expected ${open}`);
    if (open.some((pid) => !got.gapped.includes(pid))) wrong.push(`${label}: no gap names ${open.filter((pid) => !got.gapped.includes(pid))}`);
    if (got.complete !== !open.length) wrong.push(`${label}: complete ${got.complete}`);
  };
  expect('two starts, the later exits by its identity', [startOf(p[0]), startOf(p[1]), exitOf(p[1])], [p[1].pid]);
  expect('two starts, the later exits by its identity, recorded first', [startOf(p[1]), exitOf(p[1]), startOf(p[0])], [p[1].pid]);
  expect('two starts, an exit naming only the childId', [startOf(p[0]), startOf(p[1]), childExit()], []);
  expect('three starts, the middle exits by its identity', [startOf(p[0]), startOf(p[1]), startOf(p[2]), exitOf(p[1])], [p[1].pid]);
  expect('three starts, the middle by its identity and one by the childId only',
    [startOf(p[0]), startOf(p[1]), startOf(p[2]), exitOf(p[1]), childExit()], [p[1].pid]);
  expect('three starts, a reconciliation finds the first gone by its identity',
    [startOf(p[0]), startOf(p[1]), startOf(p[2]), ownerDied('GONE', { pid: p[0].pid, creationFileTime: p[0].creationFileTimeUtc,
      incarnation: proofInc })], [p[0].pid]);
  expect('three starts, each exits by its identity', [startOf(p[0]), startOf(p[1]), startOf(p[2]), exitOf(p[0]), exitOf(p[1]), exitOf(p[2])],
    p.map((x) => x.pid));
  expect('the same start recorded twice, an exit naming only the childId', [startOf(p[0]), startOf(p[0]), childExit()], [p[0].pid]);
  assert.deepEqual(wrong, []);
});

/**
 * The real census of `count` running children recorded under one childId. `ending`: the starts that
 * exit, each with an exit naming its PID and birth; `childIdOnly`: one more exit naming only the
 * childId. Returns what is wrong: a complete verdict while one still runs, or (unless `verdictOnly`)
 * a running start no gap names, or an ended one a gap names.
 */
async function sharedChildIdCensus(label, count, ending, childIdOnly = false, verdictOnly = false) {
  const kids = [];
  try {
    for (let i = 0; i < count; i++) kids.push(await liveChild());
    const dataDir = freshDir('shared-child-id-real');
    writeLedger(dataDir, [engineStart, engineExit(), PROOF_TABLE.child.setup[2], ...kids.map((k) => childStart(k))]);
    for (const i of ending) {
      await kids[i].end();
      appendLedger(dataDir, fullChildExit(kids[i], { exitedAt: new Date().toISOString() }));
    }
    if (childIdOnly) appendLedger(dataDir, childExit({ exitedAt: new Date().toISOString() }));
    const result = await (await realCensus(dataDir, dataDir)).finish();
    const running = kids.filter((_, i) => !ending.includes(i));
    const wrong = [];
    if (result.exitAccountingComplete !== !running.length) wrong.push(`${label}: complete ${result.exitAccountingComplete}`);
    if (!verdictOnly) {
      for (const k of running) if (!result.exitAccountingGaps.some((g) => g.pid === k.pid)) wrong.push(`${label}: no gap names running ${k.pid}`);
      for (const i of ending) {
        if (result.exitAccountingGaps.some((g) => g.pid === kids[i].pid)) wrong.push(`${label}: a gap names ended ${kids[i].pid}`);
      }
    }
    return wrong.length ? [...wrong, JSON.stringify(result.exitAccountingGaps)] : [];
  } finally { for (const k of kids) await k.end(); }
}

test('preserved from b599d4d7e: two running children under one childId, the later ended by its own exit, leave accounting incomplete', { skip: !windows && 'Windows processes' }, async () => {
  assert.deepEqual(await sharedChildIdCensus('two starts, the later exits by its identity', 2, [1], false, true), []);
});

test('the real census of running children under one childId: only a start whose own exit is recorded is accounted, and each running one is named', { skip: !windows && 'Windows processes' }, async () => {
  assert.deepEqual(await sharedChildIdCensus('two starts, the later exits by its identity', 2, [1]), []);
  assert.deepEqual(await sharedChildIdCensus('three starts, the middle by its identity, one exit by the childId only', 3, [1], true), []);
  assert.deepEqual(await sharedChildIdCensus('three starts, each exits by its identity', 3, [0, 1, 2]), []);
});

test('an earlier census another reader holds open is read where it is: its OS disagreement is carried through every retry, and it is listed', { skip: !windows && 'Windows sharing' }, async () => {
  // The run's Engine is a real process; it exits 7 while its ledger exit records code 0.
  const engine = await liveChild(7);
  const runId = 'stop-census-held-disagreement';
  const run = { runId, pids: { backendRootPid: engine.pid } };
  const dataDir = freshDir('held-disagreement');
  const ledger = runLedger(dataDir, runId, engine, process.execPath);
  ledger.engineStart();
  const first = await census.startStopExitCensus(run, dataDir, dataDir, scope);
  await engine.end();
  ledger.engineExit();
  const prior = await first.finish();
  assert.ok(prior.exitAccountingGaps.some((g) => g.reason === 'ledger_os_disagree' && g.pid === engine.pid),
    `the first census finds the disagreement: ${JSON.stringify(prior.exitAccountingGaps)}`);
  const targetsFile = path.join(dataDir, 'exit-targets.json'), exitsFile = path.join(dataDir, 'process-exits.json');
  const bytes = [fs.readFileSync(targetsFile), fs.readFileSync(exitsFile)];
  const retry = async () => (await census.startStopExitCensus(run, dataDir, dataDir, scope)).finish();
  const disagrees = (r) => r.exitAccountingComplete === false
    && r.exitAccountingGaps.some((g) => g.reason === 'ledger_os_disagree' && g.pid === engine.pid);
  const readers = [holdOpen(targetsFile), holdOpen(exitsFile)];
  const retries = [];
  try {
    for (const reader of readers) await reader.held();
    // Two retries while both files stay held past the archive retry.
    retries.push(await retry(), await retry());
  } finally { for (const reader of readers) await reader.release(); }
  assert.ok(fs.readFileSync(targetsFile).equals(bytes[0]) && fs.readFileSync(exitsFile).equals(bytes[1]), 'the held census is untouched');
  for (const [i, r] of retries.entries()) {
    assert.ok(disagrees(r), `retry ${i + 1} keeps the earlier disagreement: ${JSON.stringify(r.exitAccountingGaps)}`);
    assert.deepEqual(r.censusWrittenAs, [`exit-targets.${i + 1}.json`, `process-exits.${i + 1}.json`]);
    for (const name of ['exit-targets.json', 'process-exits.json']) {
      assert.ok(r.earlierCensus?.includes(name), `retry ${i + 1} lists the held ${name}: ${JSON.stringify(r.earlierCensus)}`);
      assert.deepEqual(r.earlierCensusFiles?.find((f) => f.name === name), { name, readable: true, archived: false });
    }
  }
  assert.deepEqual(retries[1].earlierCensusFiles.filter((f) => f.archived).map((f) => f.name),
    ['exit-targets.1.json', 'process-exits.1.json'], 'and the first retry\'s own census');
  // Released: the next retry archives it under the next number and still carries the disagreement.
  const after = await retry();
  assert.ok(disagrees(after), JSON.stringify(after.exitAccountingGaps));
  assert.ok(fs.readFileSync(path.join(dataDir, 'exit-targets.3.json')).equals(bytes[0]), 'archived as it was');
  assert.ok(after.earlierCensusFiles.every((f) => f.archived && f.readable), JSON.stringify(after.earlierCensusFiles));
  // An earlier census held so that it cannot even be read is a named gap.
  const heldDir = freshDir('held-unreadable');
  const heldLedger = runLedger(heldDir, runId, noProcess(971601, '134364632933610725'), process.execPath);
  heldLedger.engineStart();
  heldLedger.engineExit();
  const unreadable = path.join(heldDir, 'process-exits.json');
  fs.writeFileSync(unreadable, JSON.stringify({ processExits: [] }));
  const locked = holdOpen(unreadable, 'None');
  let blind;
  try {
    await locked.held();
    blind = await (await census.startStopExitCensus({ runId, pids: { backendRootPid: 971601 } }, heldDir, heldDir, scope)).finish();
  } finally { await locked.release(); }
  assert.equal(blind.exitAccountingComplete, false);
  assert.ok(blind.exitAccountingGaps.some((g) => g.reason === 'earlier-census-unreadable' && g.file === 'process-exits.json'),
    JSON.stringify(blind.exitAccountingGaps));
  assert.deepEqual(blind.earlierCensusFiles, [{ name: 'process-exits.json', readable: false, archived: false }]);
});

// Round 6: a record proves or matches a start only when it agrees with every identity field both
// carry; what an earlier stop found (a disagreement, evidence lost or unreadable) is carried forward
// and no later record clears it; a reader reads only the tail of a grown file. Each reproduction is a
// case of its own, and a generated check compares the census with a small oracle of the two rules.

test('a reconciliation naming another owner instance than the child was spawned under proves nothing: the child is looked up and blocks the stop', async () => {
  const dataDir = freshDir('wrong-owner');
  writeLedger(dataDir, [...PROOF_TABLE.child.setup, ownerDied('GONE', { previousOwner: { ...proofOwner, instanceId: 'inst-2' },
    pid: proofChild.pid, creationFileTime: proofChild.creationFileTimeUtc })]);
  const fixture = freshProofRun(dataDir, proofEngine);
  fs.writeFileSync(path.join(fixture.dir, 'stop-tracked-processes.json'), JSON.stringify({ processes: [
    { ...proofChild, birthTick: '1', role: 'Engine job member' }] }));
  const looked = [];
  // The child is running: its PID names a process with its own birth.
  const out = await api.stopRun({}, { ...fixture.io, lookupIdentities: async (ids) => {
    looked.push(...ids);
    return new Map(ids.map((pid) => [pid, pid === proofChild.pid ? { present: true, creationFileTime: proofChild.creationFileTimeUtc,
      name: 'parser.exe', executable: 'C:\\fixture\\parser.exe' } : { present: false }]));
  } });
  assert.ok(looked.includes(proofChild.pid), `the child is looked up: ${JSON.stringify(looked)}`);
  assert.equal(out.outcome, 'STOP_INCOMPLETE', JSON.stringify(out.resolvedByLedger));
  assert.notEqual(out.exitAccountingComplete, true, JSON.stringify(out.exitAccountingGaps));
});

test('two Engine starts under one incarnation: an exit naming one start never accounts the other, whose exit the OS saw', () => {
  const second = noProcess(971041, '134364633933610725');
  const dataDir = freshDir('shared-incarnation');
  writeLedger(dataDir, [engineStart, rec('engine', by.runner, 'engine-start', { ...engineStart.fields, pid: second.pid,
    creationFileTime: second.creationFileTimeUtc }), engineExit({ pid: proofEngine.pid, creationFileTime: proofEngine.creationFileTimeUtc }),
  rec('children', by.engine, 'child-note', { note: 'the family is present' })]);
  const ledger = census.readRunLedger(dataDir);
  const input = census.exitTargets({ runId: PROOF_RUN, pids: { backendRootPid: second.pid } }, null, [], ledger);
  // The OS saw each start exit with code 0 (only an armed target's row is read).
  const result = census.exitResult(input, { processExits: [proofEngine, second].map((p) => ({ pid: p.pid,
    creationFileTimeUtc: p.creationFileTimeUtc, osCreationFileTimeUtc: p.creationFileTimeUtc, exitCode: 0, atMs: Date.now() })) },
  'shared incarnation', ledger);
  // Preserved from b599d4d7e (which arms neither start and so never completes).
  assert.equal(result.exitAccountingComplete, false, JSON.stringify(result.exitAccountingGaps));
});

test('the second Engine start under one incarnation is armed, and the gap that keeps accounting incomplete names it', () => {
  const second = noProcess(971041, '134364633933610725');
  const dataDir = freshDir('shared-incarnation-named');
  writeLedger(dataDir, [engineStart, rec('engine', by.runner, 'engine-start', { ...engineStart.fields, pid: second.pid,
    creationFileTime: second.creationFileTimeUtc }), engineExit({ pid: proofEngine.pid, creationFileTime: proofEngine.creationFileTimeUtc }),
  rec('children', by.engine, 'child-note', { note: 'the family is present' })]);
  const ledger = census.readRunLedger(dataDir);
  const input = census.exitTargets({ runId: PROOF_RUN, pids: { backendRootPid: second.pid } }, null, [], ledger);
  assert.deepEqual(input.targets.map((t) => t.pid), [second.pid], 'only the start without an exit of its own is armed');
  const result = census.exitResult(input, { processExits: [{ pid: second.pid, creationFileTimeUtc: second.creationFileTimeUtc,
    osCreationFileTimeUtc: second.creationFileTimeUtc, exitCode: 0, atMs: Date.now() }] }, 'shared incarnation', ledger);
  assert.ok(result.exitAccountingGaps.some((g) => g.pid === second.pid), `a gap names the second start: ${JSON.stringify(result.exitAccountingGaps)}`);
  assert.equal(result.processExits.find((r) => r.pid === second.pid)?.expectedTermination, false, 'never classed by the exit of the first start');
});

test('the OS exits a stop writes its own Engine exit from match a dev runner\'s microsecond Engine birth to the exact held handle', async () => {
  // A dev runner read this Engine's birth through CIM (the microsecond); the held handle's is exact.
  const birth = '134364632933610720';
  const dataDir = freshDir('before-verdict-tick');
  writeLedger(dataDir, [rec('engine', by.runner, 'engine-start', { ...engineStart.fields, pid: 971051, creationFileTime: birth })]);
  const { observer } = await arm({ runId: PROOF_RUN, pids: { backendRootPid: 971051 } }, dataDir, [{ pid: 971051,
    creationFileTimeUtc: birth, osCreationFileTimeUtc: '134364632933610727', exitCode: 0, atMs: Date.now() }]);
  let seen = null;
  await observer.finish({ beforeVerdict: (exits) => { seen = exits; } });
  assert.equal(seen?.find((e) => e.role === 'engine' && e.pid === 971051)?.exitCode, 0, JSON.stringify(seen));
});

test('a birth tick a target declares never coarsens two native births: a held handle one tick off is another process', () => {
  const selfStart = rec('engine', by.engine, 'engine-start', engineStart.fields);
  const note = rec('children', by.engine, 'child-note', { note: 'the family is present' });
  const wrong = [];
  for (const [label, records, subject, closing] of [['a child', PROOF_TABLE.child.setup, proofChild, childExit()],
    ['an Engine start the Engine wrote', [selfStart, note], proofEngine, engineExit()]]) {
    const dataDir = freshDir('stale-tick');
    writeLedger(dataDir, records);
    const input = census.exitTargets({ runId: PROOF_RUN, pids: { backendRootPid: proofEngine.pid } }, null, [], census.readRunLedger(dataDir));
    const target = input.targets.find((t) => t.pid === subject.pid);
    if (!target) { wrong.push(`${label}: not armed`); continue; }
    // A stale declaration (as an earlier census or an older writer may have left it).
    target.birthTick = '10';
    appendLedger(dataDir, closing);
    const result = census.exitResult(input, { processExits: input.targets.map((t) => ({ pid: t.pid, creationFileTimeUtc: t.creationFileTimeUtc,
      osCreationFileTimeUtc: t.pid === subject.pid ? sameMicrosecondNeighbour(subject.creationFileTimeUtc) : t.creationFileTimeUtc,
      exitCode: 0, atMs: Date.now() })) }, 'stale tick', census.readRunLedger(dataDir));
    if (result.exitAccountingComplete !== false || !result.exitAccountingGaps.some((g) => g.pid === subject.pid)) {
      wrong.push(`${label}: complete ${result.exitAccountingComplete}, ${JSON.stringify(result.exitAccountingGaps)}`);
    }
  }
  assert.deepEqual(wrong, []);
});

test('a corrupt line appended to a ledger file is read from the tail alone: one read of the new bytes, a damaged line, every earlier record kept', () => {
  const dir = path.join(freshDir('corrupt-tail'), 'ledger');
  fs.mkdirSync(dir, { recursive: true });
  const current = path.join(dir, 'children.v1.jsonl');
  fs.writeFileSync(current, generation(0) + generation(1));
  const reader = ledgerLib.createIncrementalReader();
  const first = reader.read(dir, 'children').records.map((r) => r.line.childId);
  const size = fs.statSync(current).size;
  fs.appendFileSync(current, '{bad}\n');
  const original = { openSync: fs.openSync, readSync: fs.readSync, closeSync: fs.closeSync };
  const fds = new Set(), reads = [];
  fs.openSync = function (file, ...rest) {
    const fd = original.openSync.call(this, file, ...rest);
    if (path.resolve(String(file)) === path.resolve(current)) fds.add(fd);
    return fd;
  };
  fs.readSync = function (fd, buffer, offset, length, position) {
    if (fds.has(fd)) reads.push({ position, length });
    return original.readSync.call(this, fd, buffer, offset, length, position);
  };
  fs.closeSync = function (fd, ...rest) { fds.delete(fd); return original.closeSync.call(this, fd, ...rest); };
  let again;
  try { again = reader.read(dir, 'children'); } finally { Object.assign(fs, original); }
  assert.deepEqual(reads, [{ position: size, length: 6 }], 'only the appended bytes are read');
  assert.equal(again.corruptLines, 1, 'the appended line is damaged: a gap');
  assert.deepEqual(again.records.map((r) => r.line.childId), first, 'what was read before stays');
});

/** One stop of a run whose Engine has gone, with its ledger exit; `earlier` is the text of a stop report left before it. */
async function stopAfterEarlierReport(runId, earlier, files = {}) {
  const engine = noProcess(971045, '134364632933610725');
  const dataDir = freshDir(runId);
  const ledger = runLedger(dataDir, runId, engine, process.execPath);
  ledger.engineStart();
  ledger.child('c-ended', noProcess(971047, '134364634153991105'), true);
  ledger.engineExit();
  const fixture = stopFixture({ runId, dataDir, engine });
  if (earlier !== undefined) fs.writeFileSync(path.join(fixture.dir, 'stop-report.json'), earlier);
  for (const [name, text] of Object.entries(files)) fs.writeFileSync(path.join(fixture.dir, name), text);
  const io = { ...fixture.io, lookupIdentities: async (ids) => new Map(ids.map((pid) => [pid, { present: false }])) };
  return { engine, fixture, stop: () => api.stopRun({}, io), out: await api.stopRun({}, io) };
}

test('an earlier stop report that cannot be read, or that found a disagreement, keeps every retry incomplete; one whose gap only says an exit was not yet seen does not', { skip: !windows && 'the Windows exit observer' }, async () => {
  const control = await stopAfterEarlierReport('stop-census-r6-control');
  assert.equal(control.out.exitAccountingComplete, true, `without an earlier report the stop completes: ${JSON.stringify(control.out.exitAccountingGaps)}`);

  const corrupt = await stopAfterEarlierReport('stop-census-r6-corrupt-report', '{corrupt earlier evidence');
  assert.equal(corrupt.out.exitAccountingComplete, false);
  assert.ok(corrupt.out.exitAccountingGaps.some((g) => g.reason === 'earlier-report-unreadable' && g.file === 'stop-report.1.json'),
    JSON.stringify(corrupt.out.exitAccountingGaps));

  const found = (engine) => JSON.stringify({ runId: 'earlier', exitAccountingComplete: false, exitAccountingGaps: [{ reason: 'ledger_os_disagree',
    pid: engine.pid, creationFileTimeUtc: engine.creationFileTimeUtc, ledgerCode: 0, osCode: 7 }] });
  const disagreement = await stopAfterEarlierReport('stop-census-r6-report-disagreement', found(noProcess(971045, '134364632933610725')));
  const carried = (out) => out.exitAccountingComplete === false && out.exitAccountingGaps.some((g) => g.reason === 'ledger_os_disagree'
    && g.pid === disagreement.engine.pid && g.carriedFrom === 'stop-report.1.json');
  assert.ok(carried(disagreement.out), JSON.stringify(disagreement.out.exitAccountingGaps));
  // And through every later retry (it is in stop-report.1.json and now in stop-report.2.json too).
  const again = await disagreement.stop();
  assert.ok(carried(again), JSON.stringify(again.exitAccountingGaps));
  assert.equal(again.exitAccountingGaps.filter((g) => g.reason === 'ledger_os_disagree').length, 1, 'carried once, not once per report');

  const notYet = await stopAfterEarlierReport('stop-census-r6-report-not-yet', JSON.stringify({ exitAccountingComplete: false,
    exitAccountingGaps: [{ reason: 'unrecorded_exit', pid: 971045, role: 'engine', osCode: 0 }] }));
  assert.equal(notYet.out.exitAccountingComplete, true, `an exit not yet seen is looked for again: ${JSON.stringify(notYet.out.exitAccountingGaps)}`);
});

test('a disagreement an earlier census found is never cleared by a later ledger receipt, and two codes for one identity are a disagreement too', { skip: !windows && 'Windows processes' }, async () => {
  // The run's Engine is a real process; it exits 7 while its ledger exit records code 0.
  const engine = await liveChild(7);
  const runId = 'stop-census-later-receipt';
  const run = { runId, pids: { backendRootPid: engine.pid } };
  const dataDir = freshDir('later-receipt');
  const ledger = runLedger(dataDir, runId, engine, process.execPath);
  ledger.engineStart();
  // A child that ended as recorded: the children family is present, so nothing else keeps accounting open.
  ledger.child('c-ended', noProcess(971049, '134364634153991105'), true);
  let prior;
  try {
    const first = await census.startStopExitCensus(run, dataDir, dataDir, scope);
    await engine.end();
    ledger.engineExit();
    prior = await first.finish();
  } finally { await engine.end(); }
  assert.ok(prior.exitAccountingGaps.some((g) => g.reason === 'ledger_os_disagree' && g.pid === engine.pid),
    `the first census finds the disagreement: ${JSON.stringify(prior.exitAccountingGaps)}`);
  // A later receipt for the same identity names the code the OS saw.
  appendLedger(dataDir, rec('engine', { role: 'dev-runner', pid: 4000, startedAt: new Date().toISOString() }, 'engine-exit', {
    incarnation: { session: runId, n: 1 }, pid: engine.pid, creationFileTime: engine.creationFileTimeUtc, exitClass: 'UNEXPECTED',
    reason: null, detail: null, code: 7, codeName: null, exitedAt: new Date().toISOString(), observedAt: new Date().toISOString(),
    terminateCalledAt: null, forced: false, phase: 'RUNNING', codeDistinctive: true }));
  const retry = await (await census.startStopExitCensus(run, dataDir, dataDir, scope)).finish();
  assert.equal(retry.exitAccountingComplete, false, JSON.stringify(retry.exitAccountingGaps));
  assert.deepEqual([...new Set(retry.exitAccountingGaps.map((g) => g.reason))].sort(), ['ledger_codes_disagree', 'ledger_os_disagree'],
    'only the disagreements keep it incomplete');
  assert.ok(retry.exitAccountingGaps.some((g) => g.reason === 'ledger_os_disagree' && g.pid === engine.pid
    && g.carriedFrom === 'process-exits.1.json'), `the earlier disagreement is carried: ${JSON.stringify(retry.exitAccountingGaps)}`);
  assert.ok(retry.exitAccountingGaps.some((g) => g.reason === 'ledger_codes_disagree' && g.pid === engine.pid),
    `the ledger's two codes are a disagreement: ${JSON.stringify(retry.exitAccountingGaps)}`);
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

/**
 * A 17,000,000-byte ledger of one Engine's children (the shape the verifier measured): each child's
 * spawn, start and a key-only exit (its childId), written by the Engine, which records that it started
 * at `childWriterStartedAt` (default: its own birth, so it is the owner and its exits prove).
 */
function childrenLedger(dataDir, runId, engine, childWriterStartedAt = null) {
  const dir = ledgerLib.ledgerDirectory(dataDir);
  fs.mkdirSync(dir, { recursive: true });
  const startedAt = isoMs(engine.creationFileTimeUtc);
  const owner = { pid: engine.pid, creationFileTime: engine.creationFileTimeUtc, instanceId: 'children-engine' };
  const incarnation = { session: runId, n: 1 };
  let seq = 0;
  const line = (role, type, fields) => JSON.stringify({ schemaVersion: 1, type, seq: ++seq, at: startedAt,
    writer: { role, pid: role === 'engine' ? engine.pid : 4000,
      startedAt: role === 'engine' ? childWriterStartedAt ?? startedAt : startedAt }, ...fields }) + '\n';
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
  return { children, bytes };
}

test('a stop over a 17 MB ledger of 19,000 Engine children reads each file once and stays well inside the 60 s limit', async () => {
  const runId = 'stop-census-children';
  const engine = noProcess(970901, '134364632933610725');
  const dataDir = freshDir('children');
  const { children, bytes } = childrenLedger(dataDir, runId, engine);
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

test('a stop over 19,000 Engine children whose exits prove nothing (19,000 gaps) stays well inside the 60 s limit', async () => {
  // The verifier's round-8 perf fixture: the children's writer records another start than the
  // owner's birth, so no key-only exit proves its child, and every child is a gap. A gap or an earlier
  // census record is matched through an index, never by scanning every other one (the round-8 change
  // before that took about 9 minutes here).
  const runId = 'stop-census-children-unproven';
  const engine = noProcess(970911, '134364632933610725');
  const dataDir = freshDir('children-unproven');
  const aYearLater = isoMs(String(BigInt(engine.creationFileTimeUtc) + 365n * 86400n * 10000000n));
  const { children } = childrenLedger(dataDir, runId, engine, aYearLater);
  const measured = await measuredStop(runId, dataDir, engine);
  const { out, report, elapsedMs } = measured;
  console.log(`stop over ${children} unproven ledger children: ${elapsedMs} ms`);
  // Every child is an observer target here too (about 13 s of the stop, as on b599d4d7e): half the limit.
  assert.notEqual(out.outcome, undefined, JSON.stringify(out.errors));
  assert.equal(report.exitAccountingComplete, false);
  assert.equal(report.ledgerSummary.children, children);
  assert.equal(report.exitAccountingGaps.filter((g) => g.reason === 'exit_unproven' && g.role !== 'engine').length, children,
    JSON.stringify(report.exitAccountingGaps.slice(0, 3)));
  assertReadOnce(measured);
  assert.ok(elapsedMs < 30_000, `the stop takes half its 60 s limit at most: ${elapsedMs} ms`);
});

// ---------------------------------------------------------------------------------------------
// Round 8 (b102 changeset, "One identity"): the verifier's reproductions on a2c0400b3, each a case
// through the real census (its PowerShell observer) and a whole stop of a run that tracks a live child
// of this test, its OS lookups the real ones. A record that does not name the live child's identity
// whole leaves it running: the stop looks it up, finds it, and is STOP_INCOMPLETE, its accounting
// incomplete.
// ---------------------------------------------------------------------------------------------

let identityRuns = 0;
/** A run whose Engine (no process) recorded its start and exit; `child(type, fields, writer)` adds a children record. */
function identityRun(tag) {
  const runId = `stop-census-r8-${tag}`;
  const engine = noProcess(973001 + 4 * ++identityRuns, '134364632933610725');
  const owner = { pid: engine.pid, creationFileTime: engine.creationFileTimeUtc, instanceId: 'inst-1' };
  const incarnation = { session: runId, n: 1 };
  // The Engine writes as itself (its PID, its start to the millisecond); `another` is the next Engine.
  const writer = { role: 'engine', pid: engine.pid, startedAt: isoMs(engine.creationFileTimeUtc) };
  const another = { role: 'engine', pid: engine.pid + 2, startedAt: isoMs(BigInt(engine.creationFileTimeUtc) + 600000000n) };
  const records = [rec('engine', by.runner, 'engine-start', { incarnation, pid: engine.pid, creationFileTime: engine.creationFileTimeUtc,
    executable: process.execPath, processStartedAt: isoMs(engine.creationFileTimeUtc), cause: 'START', supervisorKind: 'dev-runner' }),
  rec('engine', by.runner, 'engine-exit', { incarnation, exitClass: 'REQUESTED', reason: 'QUIT', detail: null, code: 0, codeName: 'ok',
    exitedAt: null, observedAt: new Date(Date.now() - 10000).toISOString(), terminateCalledAt: null, forced: false, phase: 'STOPPING',
    codeDistinctive: true })];
  const child = (type, fields, writtenBy = writer) => records.push(rec('children', writtenBy, type, fields));
  const startOf = (p, fields = {}) => child('child-start', { childId: 'c', pid: p.pid, creationFileTime: p.creationFileTimeUtc,
    executable: process.execPath, contained: false, ...fields });
  const exitOf = (fields = {}, writtenBy = writer) => child('child-exit', { childId: 'c', code: 0, exitClass: 'REQUESTED', reason: 'QUIT',
    forced: false, exitedAt: new Date().toISOString(), ...fields }, writtenBy);
  return { runId, engine, owner, incarnation, writer, another, child, startOf, exitOf, records, dataDir: freshDir(`r8-${tag}`) };
}

/**
 * A stop of `r` tracking `tracked` (a job helper the run does not own: reported, never ended), with the
 * real census and the real OS lookups, recorded. `census` replaces the census entry point. The tracked
 * record names no birth tick unless `tracked` does (the runner's records: the microsecond).
 */
async function trackedStop(r, tracked, { census: censusOf, lookup } = {}) {
  writeLedger(r.dataDir, r.records);
  const fixture = stopFixture({ runId: r.runId, dataDir: r.dataDir, engine: r.engine });
  const run = JSON.parse(fs.readFileSync(fixture.runPath, 'utf8'));
  fs.writeFileSync(fixture.runPath, JSON.stringify({ ...run, stopTrackedProcesses: [tracked ?? []].flat()
    .map((t) => ({ role: 'job-helper', owned: false, ...t })) }));
  const lookups = [];
  const out = await api.stopRun({}, { ...fixture.io, ...(censusOf ? { census: censusOf } : {}),
    lookupIdentities: async (ids) => { lookups.push(...ids); return (lookup ?? actuator.lookupProcessIdentities)(ids); } });
  return { out, lookups, fixture };
}

/** The live child stays running: looked up, found, the stop incomplete and its accounting too. */
function assertLiveChildFound({ out, lookups }, p, why) {
  assert.ok(lookups.includes(p.pid), `${why}: the live child is looked up, not taken as ended (${JSON.stringify(out.resolvedByLedger ?? [])})`);
  assert.equal(out.outcome, 'STOP_INCOMPLETE', `${why}: ${JSON.stringify(out.errors)}`);
  assert.ok(out.unresolvedProcesses?.some((u) => u.pid === p.pid), `${why}: the live child is named: ${JSON.stringify(out.unresolvedProcesses)}`);
  assert.equal(out.exitAccountingComplete, false, `${why}: ${JSON.stringify(out.exitAccountingGaps)}`);
}

const IDENTITY_EDGES = {
  // A child-start carries its incarnation; an exit naming its PID, birth and owner but not the incarnation proves nothing.
  'missing-incarnation': (r, p) => {
    r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: r.owner, incarnation: r.incarnation });
    r.startOf(p, { incarnation: r.incarnation });
    r.exitOf({ pid: p.pid, creationFileTime: p.creationFileTimeUtc, owner: r.owner });
  },
  // ... nor one naming its PID, birth and incarnation but not its owner.
  'missing-owner': (r, p) => {
    r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: r.owner, incarnation: r.incarnation });
    r.startOf(p, { owner: r.owner, incarnation: r.incarnation });
    r.exitOf({ pid: p.pid, creationFileTime: p.creationFileTimeUtc, incarnation: r.incarnation });
  },
  // A child-start naming another owner instance than its spawn conflicts: nothing proves it ended.
  'start-owner-not-latest-spawn': (r, p) => {
    r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: r.owner, incarnation: r.incarnation });
    r.startOf(p, { owner: { ...r.owner, instanceId: 'start-owner' }, incarnation: r.incarnation });
    r.exitOf({ pid: p.pid, creationFileTime: p.creationFileTimeUtc, owner: r.owner, incarnation: r.incarnation });
  },
  // ... nor one naming another incarnation than its spawn.
  'start-incarnation-not-spawn': (r, p) => {
    r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: r.owner, incarnation: r.incarnation });
    r.startOf(p, { owner: r.owner, incarnation: { ...r.incarnation, n: 2 } });
    r.exitOf({ pid: p.pid, creationFileTime: p.creationFileTimeUtc, owner: r.owner, incarnation: r.incarnation });
  },
  // A later spawn of the childId never rewrites the start already read; an exit naming the later owner proves nothing.
  'later-spawn-owner': (r, p) => {
    r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: r.owner, incarnation: r.incarnation });
    r.startOf(p, { owner: r.owner, incarnation: r.incarnation });
    const later = { ...r.owner, instanceId: 'later-owner' };
    r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: later, incarnation: r.incarnation });
    r.exitOf({ pid: p.pid, creationFileTime: p.creationFileTimeUtc, owner: later, incarnation: r.incarnation });
  },
  // The same PID and birth under another childId, whose start no exit names: the exit of `c` does not end it.
  'childId-collision': (r, p) => {
    r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: r.owner });
    r.startOf(p);
    r.exitOf({ pid: p.pid, creationFileTime: p.creationFileTimeUtc, owner: r.owner });
    r.child('child-spawn', { childId: 'unexited-child', kind: 'EXTRACTION', owner: r.owner });
    r.startOf(p, { childId: 'unexited-child' });
  },
  // Edge 1's dangerous variants: a record naming only the childId proves no start when two starts share it ...
  'key-only-exit-shared-childId': (r, p) => {
    r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: r.owner, incarnation: r.incarnation });
    r.startOf(p);
    r.startOf(noProcess(973997, '134364634153995105'));
    r.exitOf();
  },
  // ... nor when another Engine than the child's owner wrote it ...
  'key-only-exit-other-writer': (r, p) => {
    r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: r.owner, incarnation: r.incarnation });
    r.startOf(p);
    r.exitOf({}, r.another);
  },
  // ... nor when the owner instance it names is another.
  'key-only-exit-other-owner-instance': (r, p) => {
    r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: r.owner, incarnation: r.incarnation });
    r.startOf(p);
    r.exitOf({ owner: { ...r.owner, instanceId: 'inst-2' } });
  },
  // ... nor when its writer has the owner's PID but records a start other than the owner's birth (the
  // verifier's round-2 to round-7 fixtures: a year later, and about three hours earlier). A real
  // Engine's writer records its own birth to the millisecond (the writer agreement case), so such a
  // writer is another process under a reused PID: the stop falls back to looking the child up.
  'key-only-exit-misaligned-writer': (r, p) => {
    r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: r.owner, incarnation: r.incarnation });
    r.startOf(p);
    const birth = BigInt(r.engine.creationFileTimeUtc);
    r.exitOf({}, { ...r.writer, startedAt: isoMs(birth + 365n * 86400n * 10000000n) });
    r.exitOf({}, { ...r.writer, startedAt: isoMs(birth - 10615399n * 10000n) });
  },
};
for (const [name, setup] of Object.entries(IDENTITY_EDGES)) {
  test(`round 8 identity edge ${name}: the stop looks the live child up, finds it running, and is incomplete`, async () => {
    const p = await liveChild();
    try {
      const r = identityRun(name);
      setup(r, p);
      assertLiveChildFound(await trackedStop(r, p), p, name);
    } finally {
      await p.end();
    }
  });
}

test('round 8 identity edge tracked-other-owner: a ledger exit of the child under one owner instance does not end a tracked record naming another', async () => {
  const p = await liveChild();
  try {
    const r = identityRun('tracked-other-owner');
    r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: r.owner });
    r.startOf(p);
    r.exitOf({ pid: p.pid, creationFileTime: p.creationFileTimeUtc, owner: r.owner });
    const { out, lookups } = await trackedStop(r, { ...p, childId: 'c', owner: { ...r.owner, instanceId: 'other-tracked-owner' },
      incarnation: r.incarnation });
    assert.ok(lookups.includes(p.pid), `looked up: ${JSON.stringify(out.resolvedByLedger ?? [])}`);
    assert.equal(out.outcome, 'STOP_INCOMPLETE', JSON.stringify(out.errors));
    assert.ok(out.unresolvedProcesses?.some((u) => u.pid === p.pid), JSON.stringify(out.unresolvedProcesses));
  } finally {
    await p.end();
  }
});

test('round 8 runner matching: a tracked identity recorded to the tick is compared with its CIM row at the microsecond, so a live process is found running, never taken as reused', async () => {
  // The OS lookup reads the process table (CIM): a birth to the microsecond. Compared at the tracked
  // record's own tick, an exact birth that is not a whole microsecond reads as another process.
  let p = await liveChild();
  for (let tries = 0; BigInt(p.creationFileTimeUtc) % 10n === 0n && tries < 5; tries++) { await p.end(); p = await liveChild(); }
  try {
    assert.notEqual(BigInt(p.creationFileTimeUtc) % 10n, 0n, 'a birth that is not a whole microsecond');
    const r = identityRun('cim-row-precision');
    assertLiveChildFound(await trackedStop(r, { ...p, birthTick: '1' }), p, 'cim-row-precision');
  } finally {
    await p.end();
  }
});

test('round 8 edge 1, corrected on evidence: the owning Engine\'s exit naming only the childId of the one start under it proves that start, so the stop never looks it up (S1)', async () => {
  // DefaultChildControl, LedgerReconciler, PredecessorChildStop and the schema write a child's exit by
  // its childId alone; S1-zero-lookup needs the owner's record to prove it.
  const r = identityRun('edge-1-key-only');
  const ended = noProcess(973993, '134364634153999105');
  r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: r.owner, incarnation: r.incarnation });
  r.startOf(ended);
  r.exitOf();
  const { out, lookups, fixture } = await trackedStop(r, ended);
  assert.ok(!lookups.includes(ended.pid), 'proven ended: never looked up');
  const report = JSON.parse(fs.readFileSync(path.join(fixture.dir, 'stop-report.json'), 'utf8'));
  assert.ok((report.resolvedByLedger ?? []).some((x) => x.pid === ended.pid), JSON.stringify(report.resolvedByLedger));
  assert.equal(out.outcome, 'STOPPED', JSON.stringify(out.errors));
  assert.equal(out.exitAccountingComplete, true, JSON.stringify(out.exitAccountingGaps));
});

test('round 8 sticky: a containment contradiction one stop observed is carried by every later stop, which stays incomplete', async () => {
  const p = await liveChild();
  let ended = false;
  try {
    const r = identityRun('sticky-containment');
    r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: r.owner, incarnation: r.incarnation });
    r.startOf(p, { contained: true, owner: r.owner, incarnation: r.incarnation });
    r.exitOf({ pid: p.pid, creationFileTime: p.creationFileTimeUtc, owner: r.owner, incarnation: r.incarnation });
    // The owned-process census saw the contained child alive after its Engine's job closed.
    const scopeFile = path.join(r.dataDir, 'scope.json');
    fs.writeFileSync(scopeFile, JSON.stringify({ atMs: Date.now(), processes: [{ pid: p.pid, creationFileTimeUtc: p.creationFileTimeUtc,
      executable: process.execPath, role: 'extraction-child' }] }));
    const options = { census: (run, d, rd, _s, runtime) => census.startStopExitCensus(run, d, rd, scopeFile, runtime),
      lookup: async () => new Map() };
    const first = await trackedStop(r, null, options);
    assert.equal(first.out.exitAccountingComplete, false);
    assert.ok(first.out.exitAccountingGaps.some((g) => g.reason === 'unaccounted_process' && g.pid === p.pid
      && /contradicts recorded containment/.test(g.detail)), JSON.stringify(first.out.exitAccountingGaps));
    await p.end();
    ended = true;
    // The retry: the child is gone, and the census sees nothing running.
    fs.writeFileSync(scopeFile, JSON.stringify({ processes: [] }));
    const second = await trackedStop(r, null, options);
    assert.ok(second.out.exitAccountingGaps.some((g) => g.reason === 'unaccounted_process' && g.pid === p.pid && g.carriedFrom
      && /contradicts recorded containment/.test(g.detail)), JSON.stringify(second.out.exitAccountingGaps));
    assert.equal(second.out.exitAccountingComplete, false, 'no later stop clears an observed contradiction of containment');
  } finally {
    if (!ended) await p.end();
  }
});

/**
 * The structural check (round 8, "one comparison function"): the census and the dev runner's ledger
 * resolution compare identities only through compareIdentity (ledger-identity.cjs). Their code, without
 * comments, never compares a PID or a birth itself, keys an identity by a PID/birth string, or reads a
 * birth's precision outside it.
 */
test('round 8 carry: an earlier report or census that parses to no report (null, a list, a number) cannot be read: a gap names it and the stop is incomplete', { skip: !windows && 'the Windows exit observer' }, async () => {
  for (const [label, text] of [['null', 'null'], ['list', '[]'], ['number', '7']]) {
    const report = await stopAfterEarlierReport(`stop-census-r8-${label}-report`, text);
    assert.equal(report.out.exitAccountingComplete, false, `an earlier report holding ${text}`);
    assert.ok(report.out.exitAccountingGaps.some((g) => g.reason === 'earlier-report-unreadable' && g.file === 'stop-report.1.json'),
      `${label}: ${JSON.stringify(report.out.exitAccountingGaps)}`);
    const census = await stopAfterEarlierReport(`stop-census-r8-${label}-census`, undefined, { 'process-exits.json': text });
    assert.equal(census.out.exitAccountingComplete, false, `an earlier census holding ${text}`);
    assert.ok(census.out.exitAccountingGaps.some((g) => g.reason === 'earlier-census-unreadable' && /^process-exits/.test(g.file)),
      `${label}: ${JSON.stringify(census.out.exitAccountingGaps)}`);
  }
});

test('round 8 carry: a retry carries every kind of finding an earlier stop reported, and only an exit not yet seen or one torn last line is looked at again', { skip: !windows && 'the Windows exit observer' }, async () => {
  const at = (n) => ({ pid: 972100 + 4 * n, creationFileTimeUtc: String(134364634153991105n + BigInt(n) * 10000n) });
  const carried = [
    { reason: 'exit_unproven', role: 'extraction-child', detail: 'child-exit does not prove the exit', ...at(1) },
    { reason: 'identity-bound OS exit code/time unavailable through teardown', role: 'extraction-child', ...at(2) },
    { reason: 'identity_metadata_conflict', role: 'extraction-child', childId: 'c-conflict', ...at(3) },
    { reason: 'unaccounted_process', detail: 'contradicts recorded containment', ...at(4) },
    { reason: 'ledger_codes_disagree', codes: [0, 7], ...at(5) },
    { reason: 'ledger_os_disagree', ledgerCode: 0, osCode: 7, ...at(6) },
    { reason: 'evidence_lost', detail: 'children.v1.9.jsonl', ...at(7) },
    { reason: 'final_owner_receipt_unavailable', role: 'job-helper', ...at(8) },
    { reason: 'ledger-unreadable', family: 'children', detail: 'EBUSY' },
    { reason: 'earlier-census-unreadable', file: 'process-exits.3.json' },
    { reason: 'ledger-damaged', family: 'children', file: 'children.v1.jsonl', lines: 1, tornTail: false },
    { reason: 'ledger-damaged', family: 'engine', file: 'engine.v1.jsonl', lines: 2, tornTail: true },
    { reason: 'a_kind_added_later', ...at(9) },
  ];
  const lookedAgain = [
    { reason: 'unrecorded_exit', role: 'extraction-child', ...at(10) },
    { reason: 'ledger-damaged', family: 'children', file: 'children.v1.1.jsonl', lines: 1, tornTail: true },
  ];
  const report = await stopAfterEarlierReport('stop-census-r8-carry-kinds', JSON.stringify({ runId: 'earlier', exitAccountingComplete: false,
    exitAccountingGaps: [...carried, ...lookedAgain] }));
  const gaps = report.out.exitAccountingGaps;
  const same = (a) => (g) => g.reason === a.reason && (g.pid ?? null) === (a.pid ?? null) && (g.file ?? null) === (a.file ?? null)
    && (g.family ?? null) === (a.family ?? null);
  for (const gap of carried) {
    assert.ok(gaps.some((g) => same(gap)(g) && g.carriedFrom === 'stop-report.1.json'), `${gap.reason} is carried: ${JSON.stringify(gaps)}`);
  }
  for (const gap of lookedAgain) assert.ok(!gaps.some(same(gap)), `${gap.reason} (${gap.file ?? gap.pid}) is looked at again, not carried`);
  assert.equal(report.out.exitAccountingComplete, false);
});

test('round 8 carry: a self-exit report carries what an earlier stop of the run found, and not an exit it had not yet seen', async () => {
  const selfExit = async (name, earlier) => {
    const dir = path.join(scratch, 'self-exit-carry', 'runs', name);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, 'stop-report.json'), JSON.stringify(earlier));
    const report = await api.writeSelfExitStopReport({ runId: name, runPath: path.join(dir, 'run.json'), run: { dataDir: null },
      backendExitCode: 0, interactive: false });
    return { report, onDisk: JSON.parse(fs.readFileSync(path.join(dir, 'stop-report.json'), 'utf8')) };
  };
  const found = await selfExit('run-self-found', { exitAccountingComplete: false, exitAccountingGaps: [{ reason: 'ledger_os_disagree',
    pid: 971045, creationFileTimeUtc: '134364632933610725', ledgerCode: 0, osCode: 7 }] });
  for (const r of [found.report, found.onDisk]) {
    assert.equal(r.exitAccountingComplete, false, 'the earlier disagreement keeps accounting incomplete');
    assert.ok(r.exitAccountingGaps.some((g) => g.reason === 'ledger_os_disagree' && g.carriedFrom === 'stop-report.1.json'),
      JSON.stringify(r.exitAccountingGaps));
  }
  const notYet = await selfExit('run-self-not-yet', { exitAccountingComplete: false, exitAccountingGaps: [{ reason: 'unrecorded_exit',
    pid: 971045, role: 'engine' }] });
  assert.equal(notYet.onDisk.exitAccountingComplete, undefined, 'an exit not yet seen is not carried; a self-exit takes no census');
});

/**
 * The Engine's writer records `startedAt` as ProcessHandle.current().info().startInstant()
 * (DefaultChildControl), written to the millisecond (LedgerTimestamps.format: truncatedTo(MILLIS),
 * ISO_INSTANT). This program prints exactly that for its own process, and its PID, then waits.
 */
const SELF_BIRTH_JAVA = `import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

public class SelfBirth {
  public static void main(String[] args) throws Exception {
    ProcessHandle self = ProcessHandle.current();
    Instant start = self.info().startInstant().orElseThrow();
    System.out.println(self.pid() + " " + DateTimeFormatter.ISO_INSTANT.format(start.truncatedTo(ChronoUnit.MILLIS)));
    System.out.flush();
    System.in.read();
  }
}
`;

test('round 8 writer agreement: a real JVM\'s own startInstant is its native birth truncated to the millisecond, so its writer is its owner', { skip: !windows && 'the native birth reader' }, async () => {
  // The JDK the product runs on (resolve-jdk.cjs, as the dev runner launches the Engine).
  const { resolveJavaExe } = require('./lib/resolve-jdk.cjs');
  const identityLib = require('./lib/ledger-identity.cjs');
  const java = resolveJavaExe();
  const dir = path.join(scratch, 'self-birth');
  fs.mkdirSync(dir, { recursive: true });
  const source = path.join(dir, 'SelfBirth.java');
  fs.writeFileSync(source, SELF_BIRTH_JAVA);
  const seen = [];
  // Eight launches: a birth 0.5 ms or more past its millisecond (about half of them) tells
  // truncation from rounding.
  for (let i = 0; i < 8; i++) {
    const child = cp.spawn(java, [source], { stdio: ['pipe', 'pipe', 'inherit'], windowsHide: true });
    try {
      const line = await new Promise((resolve, reject) => {
        let out = '';
        child.on('error', reject);
        child.on('exit', (code) => reject(new Error(`the JVM exited (${code}) before printing: ${out}`)));
        child.stdout.on('data', (d) => { out += d; if (out.includes('\n')) resolve(out.trim()); });
      });
      // The native observer's reading while the JVM is alive (GetProcessTimes on a held handle).
      const native = actuator.captureSpawnedIdentity(child);
      assert.ok(native, `the native birth of the JVM ${child.pid} is read`);
      const [pid, startedAt] = line.split(' ');
      assert.equal(Number(pid), child.pid, 'the JVM reports its own PID');
      const owner = { pid: child.pid, creationFileTime: native.creationFileTimeUtc, instanceId: 'self-birth' };
      seen.push({ birth: native.creationFileTimeUtc, startedAt });
      assert.equal(identityLib.birthMs({ creationFileTimeUtc: native.creationFileTimeUtc }), Date.parse(startedAt),
        `birthMs(${native.creationFileTimeUtc}) is the JVM's startInstant ${startedAt}`);
      const writer = { role: 'engine', pid: child.pid, startedAt };
      assert.equal(identityLib.writerIsOwner(writer, owner), true, JSON.stringify({ writer, owner }));
      // And through the one rule: the JVM's key-only exit of its one child proves that child.
      const start = identityLib.startIdentity({ pid: 4242, creationFileTime: String(BigInt(native.creationFileTimeUtc) + 10000000n),
        childId: 'self-birth-child', kind: 'child', owner });
      assert.equal(identityLib.compareIdentity({ childId: 'self-birth-child' }, start, 'proves', { writer }), true);
    } finally {
      child.stdin.end('x');
      if (child.exitCode === null) await new Promise((resolve) => child.once('exit', resolve));
    }
  }
  console.log(`JVM births and startInstants: ${JSON.stringify(seen)}`);
});

test('round 8 structural: no census or ledger-resolution code compares a PID or a birth outside compareIdentity', () => {
  const uncommented = (text) => text.replace(/\/\*[\s\S]*?\*\//g, '').split('\n')
    .map((line) => line.replace(/(^|[^:\\'"`])\/\/.*$/, '$1'));
  const censusLines = uncommented(fs.readFileSync(path.join(root, 'scripts/dev/lib/stop-exit-census.cjs'), 'utf8'))
    .map((text, i) => ({ where: `stop-exit-census.cjs:${i + 1}`, text }));
  const runnerText = fs.readFileSync(path.join(root, 'scripts/dev/dev-runner.cjs'), 'utf8').split('\n');
  const regions = [];
  let open = null;
  runnerText.forEach((line, i) => {
    if (/\/\/ <ledger-resolution>/.test(line)) open = i;
    else if (/\/\/ <\/ledger-resolution>/.test(line)) { regions.push([open, i]); open = null; }
  });
  assert.equal(open, null, 'every <ledger-resolution> region is closed');
  const runnerLines = regions.flatMap(([from, to]) => uncommented(runnerText.slice(from, to + 1).join('\n'))
    .map((text, i) => ({ where: `dev-runner.cjs:${from + i + 1}`, text })));
  const forbidden = [
    [/\.pid\s*(?:[!=]==|[!=]=(?!=))(?!\s*(?:null|undefined)\b)/, 'a PID compared'],
    [/(?:[!=]==|[!=]=(?!=))\s*[\w$.?]*\.pid\b/, 'a PID compared'],
    [/(?:[!=]==|[!=]=(?!=))\s*pid\b/, 'a PID compared'],
    [/\bNumber\([^)]*pid\)\s*[!=]=/i, 'a PID compared'],
    [/\bpidOf\([^)]*\)\s*[!=]=/, 'a PID compared'],
    [/ProcessId\)?\s*[!=]=/, 'a PID compared'],
    [/\bsameBirth\(/, 'a birth compared outside compareIdentity'],
    [/\bBIRTH_TICKS\b/, 'a birth precision read outside ledger-identity.cjs'],
    [/creationFileTime(Utc)?\s*(?:[!=]==|[!=]=(?!=))(?!\s*(?:null|undefined)\b)/, 'a birth compared'],
    [/(?:[!=]==|[!=]=(?!=))\s*[\w$.?]*\.creationFileTime(Utc)?\b/, 'a birth compared'],
    [/\bBigInt\([^)]*(creationFileTime|birthOf)/, 'a birth compared as a number'],
    [/\$\{[^}]*pid\}\/\$\{/, 'an identity keyed by a PID/birth string'],
  ];
  const found = [...censusLines, ...runnerLines].flatMap(({ where, text }) => forbidden
    .filter(([re]) => re.test(text)).map(([, what]) => `${where}: ${what}: ${text.trim()}`));
  assert.deepEqual(found, []);
  assert.ok(regions.length >= 3, `the dev runner's ledger resolution, runner matching and engine-exit match are marked: ${regions.length}`);
});

// ---------------------------------------------------------------------------------------------
// Round 9: one start never stands in for another. The verifier's four reproductions (and the
// related shapes it found) on real running children: a start appended after arming under another
// childId or incarnation with an ended start's PID and birth; two tracked records of one PID and
// birth under two childIds; a native tracked birth with a coarse declared tick; an Engine's
// incarnation-only exit written by another Engine. Each passes on b599d4d7e and failed on 3e9ebd546.
// ---------------------------------------------------------------------------------------------

/** `census` (the stop's census entry point) that appends `records` to the ledger once the census has armed. */
const appendAfterArming = (records, scopeFile = null) => async (run, dataDir, runDir, s, runtime) => {
  const armed = await census.startStopExitCensus(run, dataDir, runDir, scopeFile ?? s, runtime);
  for (const r of records) appendLedger(dataDir, r);
  return armed;
};
/** A gap of `gaps` that names process `p` by its PID (and its birth, where the gap records one). */
const namesProcess = (gaps, p) => (gaps ?? []).some((g) => g.pid === p.pid
  && (g.creationFileTimeUtc == null || g.creationFileTimeUtc === p.creationFileTimeUtc));

test('round 9 late childId: a start appended after arming under another childId, with an ended start\'s PID and birth, has no exit of its own, so the whole stop is incomplete', async () => {
  const p = await liveChild();
  try {
    const r = identityRun('r9-late-childid');
    r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: r.owner });
    r.startOf(p);
    r.exitOf({ pid: p.pid, creationFileTime: p.creationFileTimeUtc, owner: r.owner });
    const late = [rec('children', r.writer, 'child-spawn', { childId: 'second-child', kind: 'EXTRACTION', owner: r.owner }),
      rec('children', r.writer, 'child-start', { childId: 'second-child', pid: p.pid, creationFileTime: p.creationFileTimeUtc,
        executable: process.execPath, contained: false })];
    const { out, fixture } = await trackedStop(r, null, { census: appendAfterArming(late) });
    const report = JSON.parse(fs.readFileSync(path.join(fixture.dir, 'stop-report.json'), 'utf8'));
    assert.equal(report.exitAccountingComplete, false, `the late start borrowed the ended start's exit: ${JSON.stringify(report.exitAccountingGaps)}`);
    assert.equal(out.exitAccountingComplete, false);
    assert.ok(namesProcess(report.exitAccountingGaps, p), `a gap names the late start's process: ${JSON.stringify(report.exitAccountingGaps)}`);
  } finally {
    await p.end();
  }
});

test('round 9 late incarnation: an Engine start appended after arming under another incarnation, with an ended start\'s PID and birth, keeps the census incomplete', async () => {
  const p = await liveChild();
  try {
    const r = identityRun('r9-late-incarnation');
    const start = (n) => rec('engine', by.runner, 'engine-start', { incarnation: { ...r.incarnation, n }, pid: p.pid,
      creationFileTime: p.creationFileTimeUtc, executable: process.execPath, processStartedAt: isoMs(p.creationFileTimeUtc),
      cause: 'RESTART', supervisorKind: 'dev-runner' });
    r.records.push(start(2), rec('engine', by.runner, 'engine-exit', { incarnation: { ...r.incarnation, n: 2 }, exitClass: 'REQUESTED',
      reason: 'QUIT', detail: null, code: 0, codeName: 'ok', exitedAt: null, observedAt: new Date().toISOString(),
      terminateCalledAt: null, forced: false, phase: 'STOPPING', codeDistinctive: true }));
    writeLedger(r.dataDir, r.records);
    const runDir = freshDir('r9-late-incarnation-run');
    const armed = await census.startStopExitCensus({ runId: r.runId, pids: { backendRootPid: r.engine.pid } }, r.dataDir, runDir, scope);
    appendLedger(r.dataDir, start(3));
    const result = await armed.finish();
    assert.equal(result.exitAccountingComplete, false, `incarnation 3 borrowed incarnation 2's exit: ${JSON.stringify(result.exitAccountingGaps)}`);
  } finally {
    await p.end();
  }
});

for (const order of [['second-child', 'c'], ['c', 'second-child']]) {
  test(`round 9 tracked list: two tracked records of one process under childIds ${order.join(' then ')}, one proven ended, are both kept: the unproven one is looked up and the stop is incomplete`, async () => {
    const p = await liveChild();
    try {
      const r = identityRun(`r9-two-tracked-${order[0]}`);
      r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: r.owner });
      r.startOf(p);
      r.exitOf({ pid: p.pid, creationFileTime: p.creationFileTimeUtc, owner: r.owner });
      r.child('child-spawn', { childId: 'second-child', kind: 'EXTRACTION', owner: r.owner });
      r.startOf(p, { childId: 'second-child' });
      const tracked = order.map((childId) => ({ ...p, childId, owner: r.owner }));
      const result = await trackedStop(r, tracked);
      assertLiveChildFound(result, p, `tracked ${order.join(', ')}`);
    } finally {
      await p.end();
    }
  });
}

test('round 9 tracked list: records repeated whole are one; records of one PID and birth that differ in childId, owner, incarnation or tick are each kept', () => {
  assert.equal(typeof api.trackedIdentities, 'function', 'the stop keeps its tracked identities as a list');
  const base = { pid: 974301, creationFileTimeUtc: genFar(7), role: 'job-helper', owned: false };
  const list = api.trackedIdentities();
  const forms = [{ ...base, childId: 'a' }, { ...base, childId: 'b' }, { ...base, childId: 'a', birthTick: '10000' },
    { ...base, childId: 'a', owner: GEN_OWNER }, { ...base, incarnation: GEN_INCS.n1 }, { ...base, incarnation: GEN_INCS.n2 }, base];
  for (const f of forms) list.add(f);
  // The run record and stop-tracked-processes.json both hold what the last stop listed.
  for (const f of forms) list.add({ ...f, owned: true, observedName: 'parser.exe' });
  assert.equal(list.list.length, forms.length, JSON.stringify(list.list));
  forms.forEach((f, i) => {
    for (const k of Object.keys(f).filter((k) => k !== 'owned')) assert.deepEqual(list.list[i][k], f[k], `${i}.${k}`);
    assert.equal(list.list[i].owned, true, 'owned when either record says so');
  });
});

test('round 9 native precision: a tracked birth read natively is compared to the tick whatever tick it declares, so an exit of a birth one tick away never ends it', async () => {
  const p = await liveChild();
  try {
    const r = identityRun('r9-native-declared-coarse');
    const other = { pid: p.pid, creationFileTimeUtc: String(BigInt(p.creationFileTimeUtc) + 1n) };
    r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: r.owner });
    r.startOf(other);
    r.exitOf({ pid: other.pid, creationFileTime: other.creationFileTimeUtc, owner: r.owner });
    const { out, lookups } = await trackedStop(r, { ...p, creationTimeSource: 'native', birthTick: '10000' });
    assert.ok(lookups.includes(p.pid), `looked up, not ended by another birth's exit: ${JSON.stringify(out.resolvedByLedger ?? [])}`);
    assert.equal(out.outcome, 'STOP_INCOMPLETE', JSON.stringify(out.errors));
    assert.ok(out.unresolvedProcesses?.some((u) => u.pid === p.pid), JSON.stringify(out.unresolvedProcesses));
  } finally {
    await p.end();
  }
});

test('round 9 Engine writer: an incarnation-only exit another Engine wrote proves nothing of a live Engine start, which is looked up and keeps the stop incomplete', async () => {
  const p = await liveChild();
  try {
    const r = identityRun('r9-engine-other-writer');
    const n2 = { ...r.incarnation, n: 2 };
    // The live stand-in records its own start, as an Engine does: its PID, its start to the millisecond.
    r.records.push(rec('engine', { role: 'engine', pid: p.pid, startedAt: isoMs(p.creationFileTimeUtc) }, 'engine-start', {
      incarnation: n2, pid: p.pid, creationFileTime: p.creationFileTimeUtc, executable: process.execPath,
      processStartedAt: isoMs(p.creationFileTimeUtc), cause: 'RESTART', supervisorKind: 'dev-runner' }));
    // The run's first Engine (another PID and birth) writes an exit naming only that incarnation.
    r.records.push(rec('engine', r.writer, 'engine-exit', { incarnation: n2, exitClass: 'REQUESTED', reason: 'QUIT', detail: null,
      code: 0, codeName: 'ok', exitedAt: null, observedAt: new Date().toISOString(), terminateCalledAt: null, forced: false,
      phase: 'STOPPING', codeDistinctive: true }));
    assertLiveChildFound(await trackedStop(r, { ...p, incarnation: n2 }), p, 'another Engine\'s incarnation-only exit');
  } finally {
    await p.end();
  }
});

test('round 9 Engine writer table: an incarnation-only Engine exit proves its one start when a supervisor, that Engine itself or the writer of its start wrote it, never another Engine', () => {
  const runId = 'stop-census-r9-writers';
  const e = { pid: 974401, birth: genFar(4) }, inc = { session: runId, n: 1 };
  const startedAt = isoMs(e.birth);
  const self = { role: 'engine', pid: e.pid, startedAt };
  // Another Engine, which in the last two rows recorded the start.
  const other = { role: 'engine', pid: 974405, startedAt };
  const writers = {
    'dev-runner': [self, { role: 'dev-runner', pid: 4001, startedAt }, true], 'dev-cli': [self, { role: 'dev-cli', pid: 4005, startedAt }, true],
    shell: [self, { role: 'shell', pid: 4009, startedAt }, true], 'the Engine itself': [self, self, true],
    'another Engine': [self, other, false],
    'its PID, started a millisecond later': [self, { role: 'engine', pid: e.pid, startedAt: isoMs(BigInt(e.birth) + 10000n) }, false],
    'the Engine that recorded the start': [other, other, true],
    'an Engine other than the one that recorded the start': [other, { ...other, pid: 974409 }, false],
  };
  const wrong = [];
  for (const [name, [startWriter, writer, proves]] of Object.entries(writers)) {
    const dataDir = freshDir(`r9-writer-${name.replace(/\W+/g, '-')}`);
    writeLedger(dataDir, [rec('engine', startWriter, 'engine-start', { incarnation: inc, pid: e.pid,
      creationFileTime: e.birth, executable: process.execPath, processStartedAt: startedAt, cause: 'START', supervisorKind: 'dev-runner' }),
    rec('engine', writer, 'engine-exit', { incarnation: inc, exitClass: 'REQUESTED', reason: 'QUIT', detail: null, code: 0, codeName: 'ok',
      exitedAt: null, observedAt: startedAt, terminateCalledAt: null, forced: false, phase: 'STOPPING', codeDistinctive: true })]);
    const got = Boolean(census.ledgerView(census.readRunLedger(dataDir), runId).engines[0]?.exit);
    if (got !== proves) wrong.push(`${name}: ${got ? 'proves' : 'does not prove'}`);
  }
  assert.deepEqual(wrong, []);
});

test('round 9 related shapes: a later note naming another owner, a second childId with a relative executable, and a job witness of an ended child\'s process each keep the census incomplete', async () => {
  const shapes = {
    'later-note-conflict': (r, p) => {
      r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: r.owner });
      r.startOf(p);
      r.exitOf({ pid: p.pid, creationFileTime: p.creationFileTimeUtc, owner: r.owner });
      r.child('child-note', { childId: 'c', token: 'fixture', owner: { ...r.owner, instanceId: 'other-owner' } });
    },
    'second-childId-relative-executable': (r, p) => {
      r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: r.owner });
      r.startOf(p);
      r.exitOf({ pid: p.pid, creationFileTime: p.creationFileTimeUtc, owner: r.owner });
      r.child('child-spawn', { childId: 'second-child', kind: 'EXTRACTION', owner: r.owner });
      r.startOf(p, { childId: 'second-child', executable: 'node.exe' });
    },
    'witness-of-ended-child': (r, p) => {
      r.child('child-spawn', { childId: 'c', kind: 'EXTRACTION', owner: r.owner });
      r.startOf(p);
      r.exitOf({ pid: p.pid, creationFileTime: p.creationFileTimeUtc, owner: r.owner });
      r.child('job-process-start', { witnessId: 'w', owner: r.owner, incarnation: r.incarnation, pid: p.pid,
        creationFileTime: p.creationFileTimeUtc, executable: process.execPath, contained: true, awaited: false, expectedCode: null,
        reason: null, parentPid: r.engine.pid, parentCreationFileTime: r.engine.creationFileTimeUtc, observedAt: new Date().toISOString() });
    },
  };
  const p = await liveChild();
  try {
    const wrong = [];
    for (const [name, setup] of Object.entries(shapes)) {
      const r = identityRun(`r9-${name}`);
      setup(r, p);
      writeLedger(r.dataDir, r.records);
      const armed = await census.startStopExitCensus({ runId: r.runId, pids: { backendRootPid: r.engine.pid } }, r.dataDir,
        freshDir(`r9-${name}-run`), scope);
      const result = await armed.finish();
      if (result.exitAccountingComplete !== false) wrong.push(`${name}: complete ${JSON.stringify(result.exitAccountingGaps)}`);
    }
    assert.deepEqual(wrong, []);
  } finally {
    await p.end();
  }
});

// ---------------------------------------------------------------------------------------------
// The generated checks. Random ledgers over the schema, each judged by the real census and stop and
// by the oracle below, which is the round-8 identity rule (b102 changeset, "One identity") written
// out on its own and nothing else:
//  - a start's identity is read once from its own record: an Engine start's PID, birth (at the tick
//    its writer measured it) and incarnation; a child-start's PID, exact birth, childId, and its owner
//    and incarnation, its own or else its spawn's. A spawn, a later spawn or a second start of the same
//    process naming another owner or incarnation is a conflict on it, and nothing proves it ended;
//  - a record proves a start when it names every field the start carries and agrees on each; a record
//    naming only the key (a childId; an Engine incarnation, with or without the PID) proves the one
//    start of its key, when no record of the key contradicts it and, for a child, when the owner it
//    names (or else its writer) is the start's owner;
//  - a tracked identity is ended (and not looked up) only when some start agrees with it and every
//    start that agrees with it is proven ended.
// Cases build starts with owner and incarnation fields of their own, spawns naming them, later
// spawns and second starts naming others; records that name or leave out a PID, a birth, a childId,
// an owner (whole, without its instance, another instance, PID or birth) and an incarnation, each
// right, wrong or one tick off, written by the owning Engine or another, in every order. Each case is
// judged twice: by the census (armed before any exit was recorded, the OS reporting what it saw), and
// by a whole stop (stopRun, with the real census behind a stand-in for the PowerShell observer) of a
// run that tracks one identity in some form, its OS lookup stubbed: whether the stop looks it up
// (lookup suppression), what it makes of the OS row (runner matching), its outcome and accounting.
// A fixed seed; a failure prints the seed, the case and its input, and STOP_CENSUS_GEN_CASE=<n> runs
// that case alone.
// ---------------------------------------------------------------------------------------------

/** mulberry32: the same seed gives the same cases. */
function seeded(seed) {
  let a = seed >>> 0;
  const next = () => {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  return { int: (n) => Math.floor(next() * n), pick: (xs) => xs[Math.floor(next() * xs.length)], chance: (p) => next() < p,
    shuffle: (xs) => { const out = [...xs]; for (let i = out.length - 1; i > 0; i--) { const j = Math.floor(next() * (i + 1)); [out[i], out[j]] = [out[j], out[i]]; } return out; } };
}
const GEN_SEED = 0xB6D536;
const GEN_IDENTITY_CASES = 2000;
const GEN_EVIDENCE_CASES = 250;
const genOnly = process.env.STOP_CENSUS_GEN_CASE != null ? Number(process.env.STOP_CENSUS_GEN_CASE) : null;
/** STOP_CENSUS_GEN_DUMP=<dir> writes every misjudged case of a generated check there, as JSON. */
const genDump = (name, wrong) => {
  if (process.env.STOP_CENSUS_GEN_DUMP) fs.writeFileSync(path.join(process.env.STOP_CENSUS_GEN_DUMP, `${name}.json`), JSON.stringify(wrong));
};
const GEN_BIRTH = 134364634153991105n;
const genFar = (k) => String(GEN_BIRTH + BigInt(k) * 10000000n);
const GEN_ENGINE_PID = 972101;
const GEN_OWNER = { pid: GEN_ENGINE_PID, creationFileTime: genFar(0), instanceId: 'inst-1' };
/** The owners a record can name: the run Engine's instance whole, another instance, another PID or birth, no instance. */
const GEN_OWNERS = { same: GEN_OWNER, instance: { ...GEN_OWNER, instanceId: 'inst-2' },
  birth: { ...GEN_OWNER, creationFileTime: sameMicrosecondNeighbour(GEN_OWNER.creationFileTime) },
  pid: { ...GEN_OWNER, pid: 972117 }, bare: { pid: GEN_OWNER.pid, creationFileTime: GEN_OWNER.creationFileTime },
  tracked: { ...GEN_OWNER, instanceId: 'other-tracked-owner' } };
const GEN_INCS = { n1: { session: PROOF_RUN, n: 1 }, n2: { session: PROOF_RUN, n: 2 }, n3: { session: PROOF_RUN, n: 3 } };
/** The writers of a child record: the owning Engine, or the next Engine reconciling what it left. */
const GEN_WRITERS = { owner: { role: 'engine', pid: GEN_ENGINE_PID, startedAt: isoMs(genFar(0)) },
  later: { role: 'engine', pid: 972121, startedAt: isoMs(genFar(3)) } };

/** One identity case: the starts, the records about them (in ledger order), what the OS saw, and the tracked identity. */
function generateIdentityCase(r) {
  const nb = sameMicrosecondNeighbour;
  if (r.pick(['engine', 'child']) === 'engine') {
    const s1 = { pid: 972001, birth: genFar(0), n: 1, writer: r.pick(['dev-runner', 'engine']) };
    const starts = [s1];
    if (r.chance(0.6)) {
      const form = r.pick(['other', 'samePid', 'neighbour', 'again']);
      starts.push(form === 'again' ? { ...s1, writer: r.pick(['dev-runner', 'engine']) }
        : { pid: form === 'other' ? 972005 : s1.pid, birth: form === 'neighbour' ? nb(s1.birth) : genFar(1),
          n: r.chance(0.7) ? 1 : 2, writer: r.pick(['dev-runner', 'engine']) });
    }
    const exits = [];
    for (let i = r.int(4); i > 0; i--) {
      const about = r.pick(starts);
      const type = r.pick(['engine-exit', 'engine-exit', 'engine-owner-died']);
      const exit = { type, session: r.chance(0.92) ? PROOF_RUN : 'another-run', n: r.pick([about.n, about.n, 3]),
        pid: r.pick([null, null, about.pid, 972009]), birth: r.pick([null, null, about.birth, nb(about.birth), genFar(5)]) };
      if (type === 'engine-exit') Object.assign(exit, { reconciled: r.chance(0.15), code: r.pick([0, 0, 3]) });
      else Object.assign(exit, { osCheck: r.pick(['GONE', 'GONE', 'ALIVE_MATCH', 'IDENTITY_UNKNOWN', 'REUSED', null]) },
        { exitClass: r.chance(0.8) ? 'OWNER_DIED' : 'UNKNOWN' });
      exits.push(exit);
    }
    return { kind: 'engine', starts, records: r.shuffle([...starts.slice(1).map((s) => ({ start: s })), ...exits.map((e) => ({ exit: e }))]),
      os: starts.map((s) => r.chance(0.1) ? null : { birth: r.pick([s.birth, s.birth, nb(s.birth)]), code: r.pick([0, 0, 3]),
        declared: r.pick(['1', '10', '10000', null]) }),
      tracked: { start: r.int(starts.length), form: r.pick(['bare', 'bare', 'incarnation', 'otherIncarnation', 'tickOff', 'coarse']) } };
  }
  const engine = { pid: GEN_ENGINE_PID, birth: genFar(0), writer: r.pick(['dev-runner', 'engine']) };
  const spawn = { owner: r.pick(['same', 'same', 'instance']), inc: r.pick([null, 'n1', 'n2']) };
  const own = () => ({ owner: r.pick([null, null, null, 'same', 'instance']), inc: r.pick([null, null, null, 'n1', 'n2']) });
  const c1 = { pid: 972105, birth: genFar(1), ...own() };
  const starts = [c1];
  if (r.chance(0.5)) {
    const form = r.pick(['other', 'neighbour', 'again']);
    starts.push({ ...(form === 'other' ? { pid: 972109, birth: genFar(2) } : { pid: c1.pid, birth: form === 'neighbour' ? nb(c1.birth) : c1.birth }),
      ...own() });
  }
  const later = r.chance(0.25) ? { owner: r.pick(['same', 'instance']), inc: r.pick([null, 'n1', 'n2']) } : null;
  const exits = [];
  for (let i = r.int(4); i > 0; i--) {
    const about = r.pick(starts);
    const type = r.pick(['child-exit', 'child-exit', 'owner-died']);
    const exit = { type, childId: r.chance(0.93) ? 'c1' : 'c2', pid: r.pick([null, null, about.pid, 972113]),
      birth: r.pick([null, null, about.birth, nb(about.birth), genFar(6)]), inc: r.pick([null, null, 'n1', 'n2']),
      owner: r.pick([null, null, 'same', 'same', 'instance', 'birth', 'bare', 'pid']),
      writer: type === 'child-exit' ? r.pick(['owner', 'owner', 'later']) : r.pick(['later', 'later', 'owner']) };
    if (type === 'child-exit') Object.assign(exit, { reconciled: r.chance(0.15), code: r.pick([0, 0, 3]) });
    else Object.assign(exit, { osCheck: r.pick(['GONE', 'GONE', 'ALIVE_MATCH', 'IDENTITY_UNKNOWN', 'REUSED', null]),
      exitClass: r.chance(0.8) ? 'OWNER_DIED' : 'UNKNOWN' });
    exits.push(exit);
  }
  return { kind: 'child', engine, spawn, starts,
    records: r.shuffle([...starts.map((s) => ({ start: s })), ...(later ? [{ spawn: later }] : []), ...exits.map((e) => ({ exit: e }))]),
    os: starts.map((s) => r.chance(0.1) ? null : { birth: r.pick([s.birth, s.birth, nb(s.birth)]), code: r.pick([0, 0, 3]),
      declared: r.pick(['1', '10', '10000', null]) }),
    tracked: { start: r.int(starts.length), form: r.pick(['bare', 'full', 'full', 'otherOwner', 'bareOwner', 'otherChild',
      'otherIncarnation', 'tickOff']), row: r.pick(['alive', 'alive', 'gone', 'reused', 'unreadable']) } };
}

/** The ledger records of an identity case: `withExits` false is the ledger as it was when the census armed. */
function identityCaseRecords(c, withExits) {
  const writerOf = (role) => (role === 'engine' ? (c.kind === 'child' ? GEN_WRITERS.owner : by.engine) : by.runner);
  const inc = (n, session = PROOF_RUN) => ({ session, n });
  const startRecord = (s) => rec('engine', writerOf(s.writer), 'engine-start', { incarnation: inc(s.n), pid: s.pid,
    creationFileTime: s.birth, executable: process.execPath, processStartedAt: isoMs(s.birth), cause: 'START', supervisorKind: 'dev-runner' });
  const named = (e) => ({ ...(e.pid != null ? { pid: e.pid } : {}), ...(e.birth != null ? { creationFileTime: e.birth } : {}) });
  const records = [];
  if (c.kind === 'engine') {
    records.push(startRecord(c.starts[0]), rec('children', by.engine, 'child-note', { note: 'the family is present' }));
    for (const item of c.records) {
      if (item.start) records.push(startRecord(item.start));
      else if (withExits && item.exit.type === 'engine-exit') {
        records.push(rec('engine', by.runner, 'engine-exit', { incarnation: inc(item.exit.n, item.exit.session), ...named(item.exit),
          exitClass: item.exit.code === 0 ? 'REQUESTED' : 'UNEXPECTED', reason: item.exit.code === 0 ? 'QUIT' : null, detail: null,
          code: item.exit.code, codeName: null, exitedAt: null, observedAt: proofStartedAt, terminateCalledAt: null, forced: false,
          phase: 'STOPPING', codeDistinctive: true, ...(item.exit.reconciled ? { reconciled: true } : {}) }));
      } else if (withExits) {
        records.push(rec('engine', by.runner, 'engine-owner-died', { incarnation: inc(item.exit.n, item.exit.session), ...named(item.exit),
          basis: 'OPEN_START', ...(item.exit.osCheck ? { osCheck: item.exit.osCheck } : {}),
          exitClass: item.exit.osCheck === 'GONE' ? item.exit.exitClass : 'UNKNOWN' }));
      }
    }
    return records;
  }
  const meta = (m) => ({ ...(m.owner ? { owner: GEN_OWNERS[m.owner] } : {}), ...(m.inc ? { incarnation: GEN_INCS[m.inc] } : {}) });
  records.push(startRecord({ pid: c.engine.pid, birth: c.engine.birth, n: 1, writer: c.engine.writer }),
    rec('engine', by.runner, 'engine-exit', { incarnation: inc(1), exitClass: 'REQUESTED', reason: 'QUIT', detail: null, code: 0,
      codeName: 'ok', exitedAt: null, observedAt: proofStartedAt, terminateCalledAt: null, forced: false, phase: 'STOPPING', codeDistinctive: true }),
    rec('children', GEN_WRITERS.owner, 'child-spawn', { childId: 'c1', kind: 'EXTRACTION', ...meta(c.spawn) }));
  for (const item of c.records) {
    if (item.start) {
      records.push(rec('children', GEN_WRITERS.owner, 'child-start', { childId: 'c1', pid: item.start.pid, creationFileTime: item.start.birth,
        executable: 'C:\\fixture\\parser.exe', contained: true, ...meta(item.start) }));
      continue;
    }
    if (item.spawn) {
      records.push(rec('children', GEN_WRITERS.owner, 'child-spawn', { childId: 'c1', kind: 'EXTRACTION', ...meta(item.spawn) }));
      continue;
    }
    if (!withExits) continue;
    const e = item.exit;
    const common = { childId: e.childId, ...named(e), ...(e.inc ? { incarnation: GEN_INCS[e.inc] } : {}) };
    const owner = e.owner ? GEN_OWNERS[e.owner] : null;
    if (e.type === 'child-exit') {
      records.push(rec('children', GEN_WRITERS[e.writer], 'child-exit', { ...common, ...(owner ? { owner } : {}),
        exitClass: e.code === 0 ? 'REQUESTED' : 'UNEXPECTED', reason: e.code === 0 ? 'QUIT' : null, code: e.code, forced: false,
        exitedAt: iso7(BigInt(c.starts[0].birth) + 450000n), ...(e.reconciled ? { reconciled: true } : {}) }));
    } else {
      records.push(rec('children', GEN_WRITERS[e.writer], 'owner-died', { ...common, ...(owner ? { previousOwner: owner } : {}),
        basis: 'OPEN_START', ...(e.osCheck ? { osCheck: e.osCheck } : {}), exitClass: e.osCheck === 'GONE' ? e.exitClass : 'UNKNOWN' }));
    }
  }
  return records;
}

// The oracle's own reading of identities (no census code).
const genIncSame = (a, b) => a.session === b.session && a.n === b.n;
/** Two owners name different Engines: another PID or birth, or both name an instance and they differ. */
const genOwnersDiffer = (a, b) => a.pid !== b.pid || a.creationFileTime !== b.creationFileTime
  || (a.instanceId != null && b.instanceId != null && a.instanceId !== b.instanceId);
/** The owner a record names carries every field of the start's owner, each the same. */
const genOwnerCarries = (named, owner) => named.pid === owner.pid && named.creationFileTime === owner.creationFileTime
  && (owner.instanceId == null || named.instanceId === owner.instanceId);
const genOwnersEqual = (a, b) => (a === null) === (b === null) && (a === null || (a.pid === b.pid
  && a.creationFileTime === b.creationFileTime && (a.instanceId ?? null) === (b.instanceId ?? null)));
const genIncEqual = (a, b) => (a === null) === (b === null) && (a === null || genIncSame(a, b));
const genBirthsAgree = (a, b, tick) => BigInt(a) / tick === BigInt(b) / tick;

/**
 * The oracle's starts of a case, each with its identity as its records give it, whether a conflict
 * leaves it unprovable, and its proof (the last exit its writer saw, else the last reconciliation that
 * found it gone, of the records that prove it) and the codes its proving exits name.
 */
function oracleStarts(c) {
  const proofOf = (records, provesIt) => {
    const proving = records.filter(provesIt);
    const direct = proving.filter((e) => ['engine-exit', 'child-exit'].includes(e.type) && !e.reconciled);
    const gone = proving.filter((e) => ['engine-owner-died', 'owner-died'].includes(e.type) && e.osCheck === 'GONE');
    return { proof: direct.at(-1) ?? gone.at(-1) ?? null, codes: new Set(direct.map((e) => e.code)) };
  };
  const exits = c.records.filter((x) => x.exit).map((x) => x.exit);
  if (c.kind === 'engine') {
    // A dev runner reads an Engine's birth through CIM, to the microsecond; the Engine itself exactly.
    const starts = [];
    for (const s of c.starts) {
      if (!starts.some((o) => o.pid === s.pid && o.birth === s.birth && o.n === s.n)) {
        starts.push({ ...s, inc: GEN_INCS[`n${s.n}`], tick: s.writer === 'dev-runner' ? 10n : 1n });
      }
    }
    for (const s of starts) {
      const ofKey = exits.filter((e) => e.session === PROOF_RUN && e.n === s.n);
      const contradicts = (e) => (e.pid != null && e.pid !== s.pid) || (e.birth != null && !genBirthsAgree(e.birth, s.birth, s.tick));
      const contested = ofKey.some(contradicts);
      const keyStarts = starts.filter((o) => o.n === s.n).length;
      Object.assign(s, proofOf(ofKey, (e) => (e.birth == null
        ? keyStarts === 1 && !contested
        : e.pid === s.pid && genBirthsAgree(e.birth, s.birth, s.tick))));
    }
    return starts;
  }
  const ownerOf = (v) => (v ? GEN_OWNERS[v] : null);
  const incOf = (v) => (v ? GEN_INCS[v] : null);
  let spawn = { owner: ownerOf(c.spawn.owner), inc: incOf(c.spawn.inc) };
  const differs = (meta, s) => Boolean((meta.owner && s.owner && genOwnersDiffer(meta.owner, s.owner))
    || (meta.inc && s.inc && !genIncSame(meta.inc, s.inc)));
  const starts = [];
  for (const item of c.records) {
    if (item.spawn) {
      const meta = { owner: ownerOf(item.spawn.owner), inc: incOf(item.spawn.inc) };
      for (const s of starts) if (differs(meta, s)) s.conflict = true;
      spawn = meta;
    } else if (item.start) {
      const s = { pid: item.start.pid, birth: item.start.birth, tick: 1n, childId: 'c1',
        owner: ownerOf(item.start.owner) ?? spawn.owner, inc: incOf(item.start.inc) ?? spawn.inc };
      s.conflict = differs(spawn, s);
      const prior = starts.find((o) => o.pid === s.pid && o.birth === s.birth);
      if (!prior) starts.push(s);
      else if (!genOwnersEqual(prior.owner, s.owner) || !genIncEqual(prior.inc, s.inc)) prior.conflict = true;
    }
  }
  const ofKey = exits.filter((e) => e.childId === 'c1');
  for (const s of starts) {
    const named = (e) => ownerOf(e.owner);
    const contradicts = (e) => (e.pid != null && e.pid !== s.pid) || (e.birth != null && e.birth !== s.birth)
      || Boolean(named(e) && s.owner && genOwnersDiffer(named(e), s.owner)) || Boolean(e.inc && s.inc && !genIncSame(incOf(e.inc), s.inc));
    const contested = ofKey.some(contradicts);
    // The owning Engine wrote it: an Engine writer with the owner's PID, started the owner's millisecond.
    const writtenByOwner = (e) => Boolean(s.owner) && e.writer === 'owner' && s.owner.pid === GEN_ENGINE_PID
      && isoMs(s.owner.creationFileTime) === GEN_WRITERS.owner.startedAt;
    Object.assign(s, proofOf(ofKey, (e) => {
      if (s.conflict) return false;
      if (e.pid == null && e.birth == null && e.inc == null) {
        if (starts.length !== 1 || contested) return false;
        return named(e) ? Boolean(s.owner) && genOwnerCarries(named(e), s.owner) : writtenByOwner(e);
      }
      return e.pid === s.pid && e.birth === s.birth && (!s.owner || Boolean(named(e)) && genOwnerCarries(named(e), s.owner))
        && (!s.inc || Boolean(e.inc) && genIncSame(incOf(e.inc), s.inc));
    }));
  }
  return starts;
}

/** The identity a case's run tracks: one of its starts, in the form the case names. */
function trackedIdentity(c, starts) {
  const s = starts[c.tracked.start % starts.length];
  const base = { pid: s.pid, creationFileTimeUtc: s.birth, birthTick: '1', role: 'job-helper', owned: false };
  switch (c.tracked.form) {
    case 'full': return { ...base, childId: 'c1', ...(s.owner ? { owner: s.owner } : {}), ...(s.inc ? { incarnation: s.inc } : {}) };
    case 'incarnation': return { ...base, incarnation: s.inc };
    case 'otherIncarnation': return { ...base, incarnation: GEN_INCS.n3 };
    case 'otherOwner': return { ...base, childId: 'c1', owner: GEN_OWNERS.tracked };
    case 'bareOwner': return { ...base, childId: 'c1', owner: GEN_OWNERS.bare };
    case 'otherChild': return { ...base, childId: 'c9' };
    case 'tickOff': return { ...base, creationFileTimeUtc: sameMicrosecondNeighbour(s.birth) };
    case 'coarse': return { ...base, birthTick: '10' };
    default: return base;
  }
}

/**
 * The oracle: for each start, whether the ledger proves it ended; whether the census (armed before any
 * exit was recorded, the OS reporting what it saw) accounts every start; and what a stop of a run
 * tracking the case's identity does: whether it looks the identity up, its outcome and accounting.
 */
function identityOracle(c) {
  const starts = oracleStarts(c);
  const key = (s) => `${s.pid}/${s.birth}`;
  // A proven start is reported ended unless an unproven start of the run is the same process by PID
  // and birth (at the coarser tick of the two), which the PID and birth alone cannot tell apart.
  const proven = starts.filter((s) => s.proof && !starts.some((o) => !o.proof && o.pid === s.pid
    && genBirthsAgree(o.birth, s.birth, o.tick > s.tick ? o.tick : s.tick))).map(key);
  const accounted = (s) => {
    const os = c.os[c.starts.findIndex((x) => x.pid === s.pid && x.birth === s.birth)];
    return Boolean(s.proof) && !s.conflict && s.codes.size <= 1 && os != null && genBirthsAgree(os.birth, s.birth, s.tick)
      && (s.proof.code != null ? s.proof.code === os.code : s.proof.exitClass === 'OWNER_DIED');
  };
  // What a stop tracks: the identity, and the run's backend (the Engine's start, read through CIM).
  const tracked = trackedIdentity(c, starts);
  const backend = c.kind === 'engine' ? { pid: c.starts[0].pid, creationFileTimeUtc: c.starts[0].birth, birthTick: '10' }
    : { pid: c.engine.pid, creationFileTimeUtc: c.engine.birth, birthTick: '10' };
  const agrees = (t, s) => t.pid === s.pid && genBirthsAgree(t.creationFileTimeUtc, s.birth,
    BigInt(t.birthTick) > s.tick ? BigInt(t.birthTick) : s.tick)
    && (t.childId == null || s.childId == null || t.childId === s.childId)
    && (!t.owner || !s.owner || !genOwnersDiffer(t.owner, s.owner))
    && (!t.incarnation || !s.inc || genIncSame(t.incarnation, s.inc));
  const ended = (t) => { const agreeing = starts.filter((s) => agrees(t, s)); return agreeing.length > 0 && agreeing.every((s) => s.proof); };
  const lookedUp = !ended(tracked) || (backend.pid === tracked.pid && !ended(backend));
  // The OS row the lookup returns for the tracked PID (child cases; an Engine case's lookups find nothing).
  const row = c.kind === 'child' ? c.tracked.row : 'gone';
  const unresolved = lookedUp && ['alive', 'unreadable'].includes(row);
  return { proven: proven.sort(), complete: starts.every(accounted),
    stop: { lookedUp, outcome: unresolved ? 'STOP_INCOMPLETE' : 'STOPPED',
      // Every start proven, without a conflict or two codes, by its exit or by a reconciliation that
      // classed it OWNER_DIED (one that could not class it leaves its final receipt unavailable).
      accounting: starts.every((s) => s.proof && !s.conflict && s.codes.size <= 1
        && (s.proof.code != null || s.proof.exitClass === 'OWNER_DIED')) } };
}

/** What the census makes of an identity case. */
function identityCensus(c, dataDir) {
  const run = { runId: PROOF_RUN, pids: { backendRootPid: c.kind === 'engine' ? c.starts[0].pid : c.engine.pid } };
  // Both families always have a record, so each write replaces both files.
  writeLedger(dataDir, identityCaseRecords(c, false));
  const input = census.exitTargets(run, null, [], census.readRunLedger(dataDir));
  writeLedger(dataDir, identityCaseRecords(c, true));
  const ledger = census.readRunLedger(dataDir);
  const view = census.ledgerView(ledger, PROOF_RUN);
  const subjects = new Set(c.starts.map((s) => `${s.pid}/${s.birth}`));
  const proven = [...new Set(census.endedIdentitiesOf(view).map((e) => `${e.pid}/${e.creationFileTimeUtc}`)
    .filter((k) => subjects.has(k)))].sort();
  const processExits = [];
  for (const target of input.targets) {
    const at = c.starts.findIndex((s) => s.pid === target.pid && s.birth === target.creationFileTimeUtc);
    const os = c.os[at];
    if (at < 0 || !os) continue;
    if (os.declared === null) delete target.birthTick; else target.birthTick = os.declared;
    processExits.push({ pid: target.pid, creationFileTimeUtc: target.creationFileTimeUtc, osCreationFileTimeUtc: os.birth,
      exitCode: os.code, atMs: Date.now() });
  }
  const result = census.exitResult(input, { processExits }, 'generated', ledger, view);
  return { proven, complete: result.exitAccountingComplete, gaps: result.exitAccountingGaps };
}

/** The census with a stand-in for the PowerShell observer that saw no exit (`arm`'s, for stopRun). */
const standInCensus = (run, dataDir, runDir, _scope, runtime, scopeFile = scope) => {
  let args, clock = 0, finished = false;
  const proc = new EventEmitter(); proc.exitCode = null; proc.kill = () => {};
  const flag = (f) => args[args.indexOf(f) + 1];
  return census.startStopExitCensus(run, dataDir, runDir, scopeFile, { ...runtime,
    launch: (a) => { args = a; fs.writeFileSync(flag('-ReadyFile'), '{}'); return proc; }, now: () => clock,
    pause: async (ms) => {
      clock += ms;
      if (!finished && args && fs.existsSync(flag('-FinishFile'))) {
        finished = true;
        fs.writeFileSync(flag('-OutputFile'), JSON.stringify({ processExits: [], gaps: [] }));
      }
    } });
};

/** A whole stop of a run whose ledger is the case's and which tracks the case's identity, its OS lookup stubbed. */
async function identityStop(c, dataDir) {
  const starts = oracleStarts(c);
  const tracked = trackedIdentity(c, starts);
  const engine = c.kind === 'engine' ? { pid: c.starts[0].pid, creationFileTimeUtc: c.starts[0].birth }
    : { pid: c.engine.pid, creationFileTimeUtc: c.engine.birth };
  const fixture = stopFixture({ runId: PROOF_RUN, dataDir, engine });
  try {
    const run = JSON.parse(fs.readFileSync(fixture.runPath, 'utf8'));
    fs.writeFileSync(fixture.runPath, JSON.stringify({ ...run, stopTrackedProcesses: [tracked] }));
    const lookups = [];
    const row = c.kind === 'child' ? c.tracked.row : 'gone';
    // The lookup reads the process table (CIM): births to the microsecond.
    const cim = (ft) => String(BigInt(ft) / 10n * 10n);
    const seen = { alive: { present: true, creationFileTime: cim(tracked.creationFileTimeUtc), name: 'parser.exe' },
      reused: { present: true, creationFileTime: cim(String(BigInt(tracked.creationFileTimeUtc) + 10n)), name: 'other.exe' },
      unreadable: { present: true, creationFileTime: null, name: 'parser.exe' }, gone: { present: false } }[row];
    const out = await api.stopRun({}, { ...fixture.io, census: standInCensus,
      lookupIdentities: async (ids) => {
        lookups.push(...ids);
        return new Map(ids.map((pid) => [pid, pid === tracked.pid ? seen : { present: false }]));
      } });
    return { lookedUp: lookups.includes(tracked.pid), outcome: out.outcome, accounting: out.exitAccountingComplete,
      gaps: out.exitAccountingGaps };
  } finally {
    fs.rmSync(fixture.dir, { recursive: true, force: true });
  }
}

test(`the census and the stop agree with the identity rule on ${GEN_IDENTITY_CASES} generated ledgers (seed ${GEN_SEED})`, async () => {
  const r = seeded(GEN_SEED);
  const dataDir = freshDir('generated-identity');
  const wrong = [];
  const kinds = { census: 0, lookup: 0, outcome: 0, accounting: 0 };
  for (let i = 0; i < GEN_IDENTITY_CASES; i++) {
    const c = generateIdentityCase(r);
    if (genOnly != null && genOnly !== i) continue;
    const expected = identityOracle(c);
    const got = identityCensus(c, dataDir);
    got.stop = await identityStop(c, dataDir);
    const misjudged = {
      census: JSON.stringify(got.proven) !== JSON.stringify(expected.proven) || got.complete !== expected.complete,
      lookup: got.stop.lookedUp !== expected.stop.lookedUp,
      outcome: got.stop.outcome !== expected.stop.outcome,
      accounting: got.stop.accounting !== expected.stop.accounting,
    };
    for (const k of Object.keys(kinds)) if (misjudged[k]) kinds[k] += 1;
    if (Object.values(misjudged).some(Boolean) || genOnly === i) {
      wrong.push({ seed: GEN_SEED, case: i, misjudged: Object.keys(kinds).filter((k) => misjudged[k]), expected, got, input: c });
    }
  }
  if (wrong.length) {
    console.log(`generated identity cases misjudged: ${wrong.length} (${JSON.stringify(kinds)}); reproduce one with STOP_CENSUS_GEN_CASE=<case>`);
    genDump('generated-identity', wrong);
    for (const w of wrong.slice(0, 5)) console.log(JSON.stringify(w));
  }
  assert.deepEqual(wrong.map((w) => w.case), genOnly != null ? [genOnly] : []);
});

/**
 * Earlier gaps a stop can have found: findings (carried for good: every kind but the short list of
 * exits not yet seen, round 8's "carry by default") and exits not yet seen (looked for again: an
 * unrecorded exit, a torn last line).
 */
const EARLIER_GAPS = [
  { finding: true, gap: { reason: 'ledger_os_disagree', pid: 972201, creationFileTimeUtc: genFar(0), role: 'engine', ledgerCode: 0, osCode: 7 } },
  { finding: true, gap: { reason: 'ledger_codes_disagree', pid: 972201, creationFileTimeUtc: genFar(0), role: 'engine', codes: [0, 7] } },
  { finding: true, gap: { reason: 'owner_witness_disagree', pid: 972205, creationFileTimeUtc: genFar(1), role: 'job-helper' } },
  { finding: true, gap: { reason: 'evidence_lost', droppedFile: 'children.v1.3.jsonl' } },
  { finding: true, gap: { reason: 'ledger-damaged', family: 'children', file: 'children.v1.jsonl', lines: 2, corruptLines: 2, tornTail: false } },
  { finding: true, gap: { reason: 'earlier-census-unreadable', file: 'process-exits.7.json' } },
  { finding: false, gap: { reason: 'unrecorded_exit', pid: 972201, role: 'engine', osCode: 0 } },
  { finding: true, gap: { reason: 'identity-bound OS exit code/time unavailable through teardown', pid: 972201, role: 'engine' } },
  { finding: true, gap: { reason: 'exit_unproven', pid: 972205, creationFileTimeUtc: genFar(1), detail: 'owner-died IDENTITY_UNKNOWN does not prove the exit' } },
  { finding: true, gap: { reason: 'identity_metadata_conflict', pid: 972205, creationFileTimeUtc: genFar(1), role: 'extraction-child' } },
  { finding: true, gap: { reason: 'a kind added later', pid: 972205 } },
  { finding: false, gap: { reason: 'ledger-damaged', family: 'engine', file: 'engine.v1.jsonl', lines: 1, corruptLines: 0, tornTail: true } },
  { finding: true, gap: { reason: 'ledger-unreadable', family: 'children', detail: 'children ledger files still moving' } },
];

test(`a stop carries forward exactly what earlier stops found, over ${GEN_EVIDENCE_CASES} generated histories (seed ${GEN_SEED})`, async () => {
  const r = seeded(GEN_SEED ^ 0xE7);
  const engine = noProcess(972201, genFar(0));
  // A contained child of the run whose exit the ledger records: a stop that finds it alive after its
  // Engine's job closed finds the ledger's containment contradicted.
  const kept = noProcess(972207, genFar(2));
  const wrong = [];
  let stopsRun = 0;
  for (let i = 0; i < GEN_EVIDENCE_CASES; i++) {
    // Up to three earlier stops, each leaving a report or a census: unreadable, or holding up to three gaps.
    const earlier = [];
    for (let k = r.int(4); k > 0; k--) {
      earlier.push({ where: r.pick(['report', 'census']), unreadable: r.chance(0.15),
        gaps: Array.from({ length: r.int(4) }, () => r.pick(EARLIER_GAPS)) });
    }
    // Then one to three real stops of the run, each of which finds the contained child alive after its
    // job closed (a containment contradiction) or finds nothing running.
    const stops = Array.from({ length: 1 + r.int(3) }, () => ({ contradiction: r.chance(0.3) }));
    if (genOnly != null && genOnly !== i) continue;
    const runId = `stop-census-generated-${i}`;
    const dataDir = freshDir('generated-evidence');
    const ledger = runLedger(dataDir, runId, engine, process.execPath);
    ledger.engineStart();
    ledger.child('c-ended', noProcess(972203, genFar(1)), true);
    ledger.child('c-kept', kept, true);
    ledger.engineExit();
    const fixture = stopFixture({ runId, dataDir, engine });
    const numbers = { report: 0, census: 0 };
    const files = earlier.map((e) => {
      const n = ++numbers[e.where];
      const name = e.where === 'report' ? `stop-report.${n}.json` : `process-exits.${n}.json`;
      fs.writeFileSync(path.join(fixture.dir, name), e.unreadable ? '{"unreadable' : JSON.stringify(e.where === 'report'
        ? { runId, exitAccountingComplete: false, exitAccountingGaps: e.gaps.map((g) => g.gap) }
        : { processExits: [], findings: e.gaps.map((g) => g.gap) }));
      return name;
    });
    // The oracle: a stop is incomplete exactly when an earlier stop's file cannot be read or holds a
    // finding, or when this or an earlier stop found the containment contradicted; each such file and
    // finding is named, and a contradiction an earlier stop found is carried from it.
    const named = [...earlier.flatMap((e, k) => (e.unreadable ? [{ reason: e.where === 'report' ? 'earlier-report-unreadable'
      : 'earlier-census-unreadable', file: files[k] }] : e.gaps.filter((g) => g.finding).map((g) => ({ reason: g.gap.reason, pid: g.gap.pid }))))];
    const scopeFile = path.join(dataDir, 'scope.json');
    for (const [k, s] of stops.entries()) {
      fs.writeFileSync(scopeFile, JSON.stringify(s.contradiction ? { atMs: Date.now() + 60000, processes: [{ pid: kept.pid,
        creationFileTimeUtc: kept.creationFileTimeUtc, executable: 'C:\\fixture\\parser.exe', role: 'extraction-child' }] }
        : { processes: [] }));
      const out = await api.stopRun({}, { ...fixture.io,
        census: (run, d, rd, _s, runtime) => standInCensus(run, d, rd, _s, runtime, scopeFile),
        lookupIdentities: async (ids) => new Map(ids.map((pid) => [pid, { present: false }])) });
      stopsRun += 1;
      const found = stops.slice(0, k + 1).findIndex((x) => x.contradiction);
      const expectedComplete = !earlier.some((e) => e.unreadable || e.gaps.some((g) => g.finding)) && found < 0;
      const missing = named.filter((x) => !out.exitAccountingGaps.some((g) => g.reason === x.reason
        && (x.file == null || g.file === x.file) && (x.pid == null || g.pid === x.pid)));
      const contradiction = (g) => g.reason === 'unaccounted_process' && g.pid === kept.pid;
      // Found by this stop, or else carried from the earlier stop that found it.
      if (found >= 0 && !out.exitAccountingGaps.some((g) => contradiction(g) && (s.contradiction || g.carriedFrom))) {
        missing.push({ reason: 'unaccounted_process', pid: kept.pid, ...(s.contradiction ? {} : { carriedFrom: `stop ${found + 1}` }) });
      }
      if (out.exitAccountingComplete !== expectedComplete || missing.length || genOnly === i) {
        wrong.push({ seed: GEN_SEED ^ 0xE7, case: i, stop: k + 1, expectedComplete, got: out.exitAccountingComplete, missing,
          earlier, stops, gaps: out.exitAccountingGaps });
        break;
      }
    }
    fs.rmSync(fixture.dir, { recursive: true, force: true });
    fs.rmSync(dataDir, { recursive: true, force: true });
  }
  console.log(`generated histories: ${GEN_EVIDENCE_CASES}, stops run: ${stopsRun}`);
  if (wrong.length) {
    console.log(`generated histories the stop misjudges: ${wrong.length}; reproduce one with STOP_CENSUS_GEN_CASE=<case>`);
    genDump('generated-histories', wrong);
    for (const w of wrong.slice(0, 5)) console.log(JSON.stringify(w));
  }
  assert.deepEqual(wrong.map((w) => w.case), genOnly != null ? [genOnly] : []);
});

// ---------------------------------------------------------------------------------------------
// Round 9, generated: the census's indexed decision against isProven over all records. Random
// ledgers in which starts share a PID and birth across childIds and incarnations (some appended after
// the census armed), exits of every shape (whole identity, one tick off, key-only, an Engine's by a
// supervisor, by itself, by another Engine or a millisecond off), job witnesses of the same processes,
// and lists of tracked records in every order, with declared ticks beside native births. Each case:
//  - every start the ledger records is the one the generator wrote (none merged into another);
//  - each start's decision, made from the records and starts of its key, is isProven's over every
//    record and every start of the ledger;
//  - the census (armed before the late records) is complete exactly when every start of the run is
//    proven now and every start it armed for was proven then (no OS exit is observed), and never
//    complete when a start is unproven;
//  - each tracked record survives in the stop's list whole and is looked up exactly when no start
//    agrees with it (PID, birth at the coarser of the two measured ticks, every other field both
//    carry) or one that agrees is unproven; a whole stop looks up and reports exactly those.
// STOP_CENSUS_GEN_CASE=<n> runs one case; STOP_CENSUS_GEN_DUMP=<dir> writes the misjudged ones;
// STOP_CENSUS_REFERENCE=<stop-exit-census.cjs> takes isProven from that file (judging an older checkout).
// ---------------------------------------------------------------------------------------------
const GEN_PROOF_CASES = 3000;
const r9ReferenceModule = process.env.STOP_CENSUS_REFERENCE ? require(path.resolve(process.env.STOP_CENSUS_REFERENCE)) : census;
const R9_RUN = 'stop-census-r9-gen';
const R9_E0 = { pid: 974001, birth: genFar(0) };
const R9_OWNERS = { same: { pid: R9_E0.pid, creationFileTime: R9_E0.birth, instanceId: 'inst-1' },
  instance: { pid: R9_E0.pid, creationFileTime: R9_E0.birth, instanceId: 'inst-2' },
  bare: { pid: R9_E0.pid, creationFileTime: R9_E0.birth }, otherPid: { pid: 974009, creationFileTime: R9_E0.birth, instanceId: 'inst-1' } };
/** The processes starts are recorded for: two of one PID a tick apart, and another. */
const R9_PROCS = [{ pid: 974101, birth: genFar(1) }, { pid: 974101, birth: sameMicrosecondNeighbour(genFar(1)) },
  { pid: 974105, birth: genFar(2) }];
const r9Inc = (n) => ({ session: R9_RUN, n });
const R9_EXE = 'C:\\fixture\\proc.exe';
const r9Writers = {
  runner: { role: 'dev-runner', pid: 4001, startedAt: isoMs(R9_E0.birth) }, cli: { role: 'dev-cli', pid: 4005, startedAt: isoMs(R9_E0.birth) },
  shell: { role: 'shell', pid: 4009, startedAt: isoMs(R9_E0.birth) },
  // The run's Engine (the children's owner) and the next Engine.
  owner: { role: 'engine', pid: R9_E0.pid, startedAt: isoMs(R9_E0.birth) },
  later: { role: 'engine', pid: 974021, startedAt: isoMs(genFar(3)) },
};
const r9Self = (proc, offMs = 0n) => ({ role: 'engine', pid: proc.pid, startedAt: isoMs(BigInt(proc.birth) + offMs * 10000n) });
const r9EngineExit = (fields) => ({ exitClass: 'REQUESTED', reason: 'QUIT', detail: null, code: 0, codeName: 'ok', exitedAt: null,
  observedAt: isoMs(genFar(5)), terminateCalledAt: null, forced: false, phase: 'STOPPING', codeDistinctive: true, ...fields });

/** One case: ledger items in order (`late`: appended after the census armed), the starts it wrote, and the tracked records. */
function generateProofCase(r) {
  const items = [];
  const starts = [];
  const add = (record, late = false) => items.push({ record, late });
  const lateness = () => r.chance(0.3);
  add(rec('engine', r9Writers.runner, 'engine-start', { incarnation: r9Inc(1), pid: R9_E0.pid, creationFileTime: R9_E0.birth,
    executable: R9_EXE, processStartedAt: isoMs(R9_E0.birth), cause: 'START', supervisorKind: 'dev-runner' }));
  starts.push({ family: 'engine', key: 1, pid: R9_E0.pid, birth: R9_E0.birth });
  // The children family is present whatever else the case writes.
  add(rec('children', r9Writers.owner, 'child-note', { childId: 'present', token: 'present' }));
  const e0Exit = r.pick(['runner', 'runner', 'runner', 'cli', 'shell', 'owner', 'later', 'none']);
  if (e0Exit !== 'none') add(rec('engine', r9Writers[e0Exit], 'engine-exit', r9EngineExit({ incarnation: r9Inc(1) })), r.chance(0.15));
  // Engine starts of later incarnations, of processes children and witnesses also name.
  for (let i = r.int(3); i > 0; i--) {
    const proc = r.pick(R9_PROCS), n = r.pick([2, 2, 3]), late = lateness();
    // The same start recorded again is recorded by the same writer.
    const writer = starts.find((s) => s.family === 'engine' && s.key === n && s.pid === proc.pid && s.birth === proc.birth)?.writer
      ?? r.pick(['runner', 'self']);
    add(rec('engine', writer === 'self' ? r9Self(proc) : r9Writers.runner, 'engine-start', { incarnation: r9Inc(n), pid: proc.pid,
      creationFileTime: proc.birth, executable: R9_EXE, processStartedAt: isoMs(proc.birth), cause: 'RESTART', supervisorKind: 'dev-runner' }), late);
    if (!starts.some((s) => s.family === 'engine' && s.key === n && s.pid === proc.pid && s.birth === proc.birth)) {
      starts.push({ family: 'engine', key: n, pid: proc.pid, birth: proc.birth, writer });
    }
    for (let j = r.int(3); j > 0; j--) {
      const shape = r.pick(['keyOnly', 'keyOnly', 'whole', 'ownerDied']);
      const exitLate = late || lateness();
      if (shape === 'keyOnly') {
        const w = r.pick(['runner', 'cli', 'self', 'self', 'selfLateMs', 'owner', 'later']);
        add(rec('engine', w === 'self' ? r9Self(proc) : w === 'selfLateMs' ? r9Self(proc, 1n) : r9Writers[w], 'engine-exit',
          r9EngineExit({ incarnation: r9Inc(n) })), exitLate);
      } else if (shape === 'whole') {
        add(rec('engine', r9Writers.runner, 'engine-exit', r9EngineExit({ incarnation: r9Inc(n), pid: proc.pid,
          creationFileTime: r.chance(0.8) ? proc.birth : sameMicrosecondNeighbour(proc.birth) })), exitLate);
      } else {
        add(rec('engine', r9Writers.runner, 'engine-owner-died', { incarnation: r9Inc(n), pid: r.chance(0.8) ? proc.pid : 974113,
          basis: 'OPEN_START', osCheck: r.pick(['GONE', 'GONE', 'ALIVE_MATCH']), exitClass: 'OWNER_DIED' }), exitLate);
      }
    }
  }
  // Child starts under two childIds, of the same processes.
  const spawned = new Set();
  for (let i = r.int(4); i > 0; i--) {
    const proc = r.pick(R9_PROCS), childId = r.pick(['c1', 'c2']), late = lateness();
    const owner = r.pick(['same', 'same', 'same', 'instance', 'otherPid']);
    if (!spawned.has(childId) || r.chance(0.1)) {
      add(rec('children', r9Writers.owner, 'child-spawn', { childId, kind: 'EXTRACTION', owner: R9_OWNERS[owner] }), late);
      spawned.add(childId);
    }
    const own = r.chance(0.15) ? { owner: R9_OWNERS[r.pick(['same', 'instance'])] } : {};
    add(rec('children', r9Writers.owner, 'child-start', { childId, pid: proc.pid, creationFileTime: proc.birth, executable: R9_EXE,
      contained: false, ...(r.chance(0.15) ? { incarnation: r9Inc(1) } : {}), ...own }), late);
    if (!starts.some((s) => s.family === 'child' && s.key === childId && s.pid === proc.pid && s.birth === proc.birth)) {
      starts.push({ family: 'child', key: childId, pid: proc.pid, birth: proc.birth });
    }
    for (let j = r.int(3); j > 0; j--) {
      const exitLate = late || lateness();
      const named = r.pick(['same', 'same', 'instance', 'bare', null]);
      const writer = r.pick(['owner', 'owner', 'later']);
      if (r.chance(0.75)) {
        const whole = r.chance(0.6);
        add(rec('children', r9Writers[writer], 'child-exit', { childId, code: 0, exitClass: 'REQUESTED', reason: 'QUIT', forced: false,
          exitedAt: iso7(BigInt(proc.birth) + 450000n), ...(named ? { owner: R9_OWNERS[named] } : {}),
          ...(whole ? { pid: proc.pid, creationFileTime: r.chance(0.8) ? proc.birth : sameMicrosecondNeighbour(proc.birth) } : {}),
          ...(whole && r.chance(0.3) ? { incarnation: r9Inc(1) } : {}) }), exitLate);
      } else {
        add(rec('children', r9Writers.later, 'owner-died', { childId, previousOwner: R9_OWNERS[named ?? 'same'], basis: 'OPEN_START',
          osCheck: r.pick(['GONE', 'GONE', 'ALIVE_MATCH']), exitClass: 'OWNER_DIED',
          ...(r.chance(0.5) ? { pid: proc.pid, creationFileTime: proc.birth } : {}) }), exitLate);
      }
    }
    if (r.chance(0.08)) add(rec('children', r9Writers.owner, 'child-note', { childId, token: 'note', owner: R9_OWNERS.instance }), lateness());
  }
  // A job witness of one of the processes.
  let witnessed = false;
  if (r.chance(0.3)) {
    witnessed = true;
    const proc = r.pick(R9_PROCS), late = lateness();
    const common = { witnessId: 'w1', owner: R9_OWNERS.same, incarnation: r9Inc(1) };
    add(rec('children', r9Writers.owner, 'job-process-start', { ...common, pid: proc.pid, creationFileTime: proc.birth, executable: R9_EXE,
      contained: true, awaited: false, expectedCode: null, reason: null, parentPid: R9_E0.pid, parentCreationFileTime: R9_E0.birth,
      observedAt: isoMs(genFar(5)) }), late);
    starts.push({ family: 'witness', key: 'w1', pid: proc.pid, birth: proc.birth });
    for (let j = r.int(3); j > 0; j--) {
      const shape = r.pick(['own', 'own', 'reconciled', 'ownReconciled']);
      const birth = r.chance(0.8) ? proc.birth : sameMicrosecondNeighbour(proc.birth);
      add(rec('children', shape === 'reconciled' ? r9Writers.runner : r9Writers.owner, 'job-process-exit', { ...common, pid: proc.pid,
        creationFileTime: birth, code: 0, exitClass: shape === 'own' ? 'UNEXPECTED' : 'OWNER_DIED', reason: null, forced: false,
        exitedAt: iso7(BigInt(proc.birth) + 450000n), observedAt: isoMs(genFar(5)),
        ...(shape === 'own' ? {} : { reconciled: true, cause: 'ENGINE_CRASH', osCheck: 'GONE' }) }), late || lateness());
    }
  }
  // Tracked records: a start's identity in some form, or a process no start names; ticks declared beside
  // native births. Never the run's first Engine, whose PID the stop looks up as its Engine's.
  const tracked = [];
  for (let i = 1 + r.int(3); i > 0; i--) {
    const s = starts.length > 1 && r.chance(0.9) ? r.pick(starts.slice(1))
      : { family: 'child', key: 'c1', pid: 974109, birth: genFar(6) };
    const birth = r.chance(0.85) ? s.birth : sameMicrosecondNeighbour(s.birth);
    const tick = r.pick([{ birthTick: '1' }, { birthTick: '10' }, { birthTick: '10000' }, {}, { creationTimeSource: 'native', birthTick: '10000' },
      { creationTimeSource: 'native' }, { creationTimeSource: 'CIM' }]);
    const form = r.pick(['whole', 'whole', 'bare', 'otherKey', 'otherOwner']);
    const keyed = s.family === 'engine' ? { incarnation: r9Inc(form === 'otherKey' ? 7 : s.key) }
      : s.family === 'child' ? { childId: form === 'otherKey' ? 'c9' : s.key, ...(form === 'otherOwner' ? { owner: R9_OWNERS.instance } : {}) }
        : { witnessId: form === 'otherKey' ? 'w9' : s.key };
    const t = { pid: s.pid, creationFileTimeUtc: birth, role: 'job-helper', owned: false, ...tick, ...(form === 'bare' ? {} : keyed) };
    tracked.push(t);
    if (r.chance(0.15)) tracked.push({ ...t });
  }
  return { items, starts, tracked: r.shuffle(tracked), witnessed };
}

/** The case's records written before the census armed. */
const r9Records = (c) => c.items.filter((x) => !x.late).map((x) => x.record);
/** Every ledger record as the census reads it (both families). */
const r9AllRecords = (ledger) => [...(ledger.engine?.records ?? []), ...(ledger.children?.records ?? [])];
const r9Key = (family, id) => (family === 'engine' ? id.incarnation?.n : family === 'child' ? id.childId : id.witnessId);
/** A start of the run: an Engine start, a witnessed process, or a child owned by the run's Engine (PID and birth). */
const r9Run = (s) => s.family !== 'child' || (s.identity.owner?.pid === R9_E0.pid && s.identity.owner?.birth === R9_E0.birth);
/** The tick a tracked record's birth was measured at: native exact, else what it declares, else its source's, else CIM's. */
const r9TrackedTick = (t) => (t.creationTimeSource === 'native' ? 1n : t.birthTick ? BigInt(t.birthTick)
  : t.creationTimeSource === 'B11.startInstant' ? 10000n : 10n);
/** Tracked record `t` and start `s` agree: one PID, births at the coarser tick, every other field both carry. */
function r9Agrees(t, s) {
  const id = s.identity, tick = r9TrackedTick(t) > BigInt(id.tick) ? r9TrackedTick(t) : BigInt(id.tick);
  if (t.pid !== id.pid || BigInt(t.creationFileTimeUtc) / tick !== BigInt(id.birth) / tick) return false;
  if (t.childId != null && id.childId != null && t.childId !== id.childId) return false;
  if (t.witnessId != null && id.witnessId != null && t.witnessId !== id.witnessId) return false;
  if (t.owner && id.owner && (t.owner.pid !== id.owner.pid || t.owner.creationFileTime !== id.owner.birth
    || (t.owner.instanceId != null && id.owner.instanceId != null && t.owner.instanceId !== id.owner.instanceId))) return false;
  if (t.incarnation && id.incarnation && (t.incarnation.session !== id.incarnation.session || t.incarnation.n !== id.incarnation.n)) return false;
  return true;
}
const R9_TRACKED_FIELDS = ['pid', 'creationFileTimeUtc', 'childId', 'witnessId', 'owner', 'incarnation'];
/** `x` is tracked record `t`, whole: every identity field the same, its birth measured at the same tick. */
const r9IsTracked = (x, t) => R9_TRACKED_FIELDS.every((k) => JSON.stringify(x[k] ?? null) === JSON.stringify(t[k] ?? null))
  && r9TrackedTick(x) === r9TrackedTick(t);

/**
 * isProven's verdicts for a case, from the reference census module (`reference`: this checkout's,
 * unless a comparison with another checkout swaps it): each start of the final and the arming
 * ledgers, judged over every record and every start of that ledger.
 */
function r9Reference(reference, armingLedger, finalLedger) {
  const judge = (ledger) => {
    const view = reference.ledgerView(ledger, R9_RUN);
    const records = r9AllRecords(ledger);
    return view.allStarts.map((s) => ({ ...s, ref: reference.isProven(s, records, view.allStarts) }));
  };
  return { arming: judge(armingLedger), final: judge(finalLedger) };
}

const r9Owner = (o) => (o ? `${o.pid}/${o.rawBirth}/${o.instanceId ?? ''}` : null);
/**
 * What the census decided of a start: the proof of its view item (what the census's verdict reads),
 * found by the start's whole identity; undefined when the view has no item for it.
 */
function r9Decision(view, s) {
  const items = s.family === 'engine' ? view.engines : s.family === 'child' ? view.children : view.witnessStarts ?? [];
  const same = (items ?? []).filter((x) => x.identity && x.identity.pid === s.identity.pid
    && x.identity.rawBirth === s.identity.rawBirth && r9Key(s.family, x.identity) === r9Key(s.family, s.identity)
    && r9Owner(x.identity.owner) === r9Owner(s.identity.owner));
  return same.length === 1 ? Boolean(same[0].proof) : undefined;
}

async function r9Census(c, dataDir, runDir) {
  writeLedger(dataDir, r9Records(c));
  const armingLedger = census.readRunLedger(dataDir);
  fs.rmSync(runDir, { recursive: true, force: true });
  fs.mkdirSync(runDir, { recursive: true });
  const run = { runId: R9_RUN, pids: { backendRootPid: R9_E0.pid } };
  const armed = await standInCensus(run, dataDir, runDir, null, {});
  for (const x of c.items.filter((x) => x.late)) appendLedger(dataDir, x.record);
  const result = await armed.finish();
  return { armingLedger, finalLedger: census.readRunLedger(dataDir), complete: result.exitAccountingComplete, gaps: result.exitAccountingGaps };
}

/** A whole stop of the run over the census's final ledger (the late records after the rest), tracking the case's records. */
async function r9Stop(c, dataDir) {
  writeLedger(dataDir, [...r9Records(c), ...c.items.filter((x) => x.late).map((x) => x.record)]);
  const fixture = stopFixture({ runId: R9_RUN, dataDir, engine: { pid: R9_E0.pid, creationFileTimeUtc: R9_E0.birth } });
  try {
    const run = JSON.parse(fs.readFileSync(fixture.runPath, 'utf8'));
    fs.writeFileSync(fixture.runPath, JSON.stringify({ ...run, stopTrackedProcesses: c.tracked }));
    const lookups = new Set();
    // Every tracked process is running and its birth cannot be read: looked up, it stays unresolved.
    const out = await api.stopRun({}, { ...fixture.io, census: standInCensus,
      lookupIdentities: async (ids) => {
        for (const pid of ids) lookups.add(pid);
        return new Map(ids.map((pid) => [pid, pid === R9_E0.pid ? { present: false } : { present: true, creationFileTime: null, name: 'proc.exe' }]));
      } });
    return { lookups: [...lookups].sort(), unresolved: out.unresolvedProcesses ?? [], outcome: out.outcome, complete: out.exitAccountingComplete };
  } finally {
    fs.rmSync(fixture.dir, { recursive: true, force: true });
  }
}

async function judgeProofCase(c, dataDir, runDir, reference = census) {
  const misjudged = [];
  const got = await r9Census(c, dataDir, runDir);
  const ref = r9Reference(reference, got.armingLedger, got.finalLedger);
  // Every start written is a start of the ledger, none merged into another.
  const written = c.starts.map((s) => `${s.family}/${s.key}/${s.pid}/${s.birth}`).sort();
  const read = ref.final.map((s) => `${s.family}/${r9Key(s.family, s.identity)}/${s.identity.pid}/${s.identity.rawBirth}`).sort();
  if (JSON.stringify(written) !== JSON.stringify(read)) misjudged.push({ kind: 'starts', written, read });
  // Each start's decision is isProven's over every record and start.
  const finalView = census.ledgerView(got.finalLedger, R9_RUN);
  for (const s of ref.final) {
    // The census's own record of its decision (this checkout's: its index chose what isProven was given).
    if (reference === census && Boolean(s.proven) !== Boolean(s.ref)) {
      misjudged.push({ kind: 'decision', start: `${s.family}/${r9Key(s.family, s.identity)}/${s.identity.pid}`, indexed: Boolean(s.proven) });
    }
    if (!r9Run(s)) continue;
    const decided = r9Decision(finalView, s);
    if (decided !== Boolean(s.ref)) misjudged.push({ kind: 'decision', start: `${s.family}/${r9Key(s.family, s.identity)}/${s.identity.pid}/${s.identity.rawBirth}`,
      decided, isProven: Boolean(s.ref) });
  }
  // The census: never complete over an unproven start; exactly complete when nothing else (a witness's
  // receipt) decides. A start it armed for unproven needs the exit the OS saw, and it saw none.
  const allProven = ref.final.filter(r9Run).every((s) => s.ref);
  const armedProven = ref.arming.filter(r9Run).every((s) => s.ref);
  if (got.complete && !allProven) misjudged.push({ kind: 'census', complete: true, unproven: true });
  if (!c.witnessed && got.complete !== (allProven && armedProven)) {
    misjudged.push({ kind: 'census', complete: got.complete, expected: allProven && armedProven, gaps: got.gaps });
  }
  // Tracked records: each kept whole, looked up exactly when not every start agreeing with it is proven.
  const runStarts = ref.final.filter(r9Run);
  const ended = (t) => { const agreeing = runStarts.filter((s) => r9Agrees(t, s)); return agreeing.length > 0 && agreeing.every((s) => s.ref); };
  if (typeof api.trackedIdentities === 'function') {
    const list = api.trackedIdentities();
    for (const t of c.tracked) list.add(t);
    const resolution = census.ledgerResolution(finalView);
    for (const t of c.tracked) {
      const listed = list.list.filter((x) => r9IsTracked(x, t));
      const lookedUp = listed.length ? listed.some((x) => !resolution.isEnded(x)) : 'lost';
      if (lookedUp !== !ended(t)) misjudged.push({ kind: 'lookup', tracked: t, lookedUp, expected: !ended(t) });
    }
  }
  // A whole stop. Each tracked PID is running, its birth unreadable: a PID some tracked record of
  // which is not ended is looked up and reported unresolved, and the stop is incomplete; a PID every
  // tracked record of which is ended, and no unproven start of the run names, is neither.
  const stop = await r9Stop(c, dataDir);
  const unprovenPids = new Set(runStarts.filter((s) => !s.ref).map((s) => s.identity.pid));
  for (const pid of new Set(c.tracked.map((t) => t.pid))) {
    const open = c.tracked.some((t) => t.pid === pid && !ended(t));
    if (!open && unprovenPids.has(pid)) continue;
    const lookedUp = stop.lookups.includes(pid), reported = stop.unresolved.some((u) => u.pid === pid);
    if (lookedUp !== open || reported !== open) {
      misjudged.push({ kind: 'stop', pid, lookedUp, reported, expected: open, unresolved: stop.unresolved.filter((u) => u.pid === pid) });
    }
  }
  const anyOpen = c.tracked.some((t) => !ended(t));
  if (anyOpen && stop.outcome !== 'STOP_INCOMPLETE') misjudged.push({ kind: 'stop', outcome: stop.outcome, expected: 'STOP_INCOMPLETE' });
  if (stop.complete && !allProven) misjudged.push({ kind: 'stop', complete: true, unproven: true });
  if (!c.witnessed && !anyOpen && stop.complete !== allProven) misjudged.push({ kind: 'stop', complete: stop.complete, expected: allProven });
  return misjudged;
}

test(`round 9: the census's decisions agree with isProven over all records on ${GEN_PROOF_CASES} generated ledgers (seed ${GEN_SEED ^ 0x9})`, async () => {
  const r = seeded(GEN_SEED ^ 0x9);
  const dataDir = freshDir('generated-proof'), runDir = path.join(freshDir('generated-proof-run'), 'run');
  const wrong = [];
  const kinds = {};
  for (let i = 0; i < GEN_PROOF_CASES; i++) {
    const c = generateProofCase(r);
    if (genOnly != null && genOnly !== i) continue;
    const misjudged = await judgeProofCase(c, dataDir, runDir, r9ReferenceModule);
    for (const k of new Set(misjudged.map((m) => m.kind))) kinds[k] = (kinds[k] ?? 0) + 1;
    if (misjudged.length || genOnly === i) wrong.push({ seed: GEN_SEED ^ 0x9, case: i, misjudged, input: c });
  }
  if (wrong.length) {
    console.log(`generated proof cases misjudged: ${wrong.length} of ${GEN_PROOF_CASES} (${JSON.stringify(kinds)}); reproduce one with STOP_CENSUS_GEN_CASE=<case>`);
    genDump('generated-proof', wrong);
    for (const w of wrong.slice(0, 3)) console.log(JSON.stringify(w));
  }
  assert.deepEqual(wrong.filter((w) => w.misjudged.length).map((w) => w.case), []);
});
