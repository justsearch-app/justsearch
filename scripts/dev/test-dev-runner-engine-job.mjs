import './lib/test-fixture-processes.cjs';
// The Engine runs in a job dev-runner starts (job-run.cjs): every process the Engine starts is a
// member at its creation, including one started detached, which Node's own job lets go. A stop
// keeps its order (the ordered quit, then the recorded identities, then the job for what is left)
// and lists by identity each process the job's termination ended. The real dev-runner CLI runs with
// the CPU-only fake Engine; a process outside the run, of the same image name, is never touched.
// The Engine's own nested job (WindowsEngineJob: CreateJobObjectW(NULL, NULL), KILL_ON_JOB_CLOSE
// only, joined by the Engine itself) is reproduced call for call to confirm it changes neither the
// containment nor the exit code.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const repo = path.resolve(import.meta.dirname, '../..');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'engine-job-'));
fs.mkdirSync(path.join(repo, 'tmp'), { recursive: true });
const dataRoot = fs.mkdtempSync(path.join(repo, 'tmp', 'engine-job-data-'));
process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = path.join(root, 'unit-state');
const actuator = require('./lib/engine-actuator.cjs');
const jobRun = require('./lib/job-run.cjs');
const api = require('./dev-runner.cjs').__test;
const { identityExecutable } = require('./lib/test-fixture-processes.cjs');
const tests = [], owned = [], decoys = [];
const test = (name, fn) => tests.push([name, fn]);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const read = file => { try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; } };
const birthOf = pid => spawnSync(identityExecutable(), [String(pid)], { encoding: 'utf8', windowsHide: true }).stdout.trim();
const sameBirth = (a, b) => /^\d+$/.test(String(a)) && /^\d+$/.test(String(b)) && BigInt(a) / 10n === BigInt(b) / 10n;
const lists = (list, p) => (list ?? []).some(q => Number(q.pid) === p.pid && sameBirth(q.creationFileTimeUtc, p.creationFileTimeUtc));

/** Identities still running: the same PID with the same birth (microsecond), read in one lookup. */
async function stillRunning(identities, waitMs = 5000) {
  const end = Date.now() + waitMs;
  for (;;) {
    const rows = await actuator.lookupProcessIdentities(identities.map(p => p.pid));
    assert.ok(rows, 'the process table must be readable');
    const alive = identities.filter(p => { const row = rows.get(p.pid); return row?.present && sameBirth(row.creationFileTime, p.creationFileTimeUtc); });
    if (!alive.length || Date.now() > end) return alive;
    await sleep(100);
  }
}

async function until(fn, ms, why) {
  const end = Date.now() + ms;
  for (;;) {
    const value = await fn();
    if (value) return value;
    if (Date.now() > end) assert.fail(why());
    await sleep(50);
  }
}

function freePort() {
  return new Promise(resolve => {
    const server = net.createServer().listen(0, '127.0.0.1', () => { const { port } = server.address(); server.close(() => resolve(port)); });
  });
}

function startDecoy() {
  const decoy = spawn(process.execPath, ['-e', 'setTimeout(()=>{},180000)'], { stdio: 'ignore', windowsHide: true });
  decoys.push(decoy);
  return decoy;
}

test('dev-runner stop: a detached process the Engine started is ended by the Engine\'s job and listed by identity; a stranger is untouched', async () => {
  const state = path.join(root, 'cli'); fs.mkdirSync(state, { recursive: true });
  const plan = path.join(state, 'plan.json');
  fs.writeFileSync(plan, JSON.stringify({ incarnations: [{ mode: 'honour', spawnGrandchild: true }] }));
  const data = path.join(dataRoot, 'cli');
  const env = { ...process.env, NODE_OPTIONS: '', JUSTSEARCH_DEV_RUNNER_STATE_ROOT: state, JUSTSEARCH_SUPERVISOR_HARNESS: '1',
    JUSTSEARCH_DEV_MCP_LOG_NDJSON: '0', JUSTSEARCH_FAKE_ENGINE_PLAN: plan, CI: '',
    JUSTSEARCH_DEV_RUNNER_ENGINE_COMMAND: JSON.stringify([process.execPath, path.join(repo, 'scripts/supervisor-conformance/fake-engine.mjs')]),
    JUSTSEARCH_DEV_RUNNER_FRONTEND_COMMAND: JSON.stringify([process.execPath, '-e', 'setInterval(()=>{},60000)']) };
  const decoy = startDecoy();
  const start = spawn(process.execPath, [path.join(repo, 'scripts/dev/dev-runner.cjs'), 'start', '--json', '--skip-build',
    '--clean', 'none', '--api-port', '0', '--ui-port', String(await freePort()), '--data-dir', data, '--session-id', 'engine-job'],
  { cwd: repo, env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  owned.push(start.recordedIdentity);
  let output = ''; start.stdout.on('data', b => { output += b; }); start.stderr.on('data', b => { output += b; });
  const grandchildFile = path.join(state, 'grandchild-1.json');
  const live = await until(() => {
    if (start.exitCode !== null) assert.fail('dev-runner start exited: ' + output);
    const active = read(path.join(state, 'active.json'));
    const run = active?.runId ? read(path.join(state, 'runs', active.runId, 'run.json')) : null;
    return active?.phase !== 'starting' && run?.pids?.frontendRootPid && fs.existsSync(grandchildFile) ? { active, run } : null;
  }, 90000, () => 'the stack did not start: ' + output);
  const spawned = read(grandchildFile);
  const grandchild = { pid: spawned.pid, creationFileTimeUtc: birthOf(spawned.pid) };
  assert.match(grandchild.creationFileTimeUtc, /^\d+$/, 'the Engine\'s detached process is running');
  owned.push(grandchild);
  assert.equal((live.run.stopTrackedProcesses ?? []).some(p => p.pid === grandchild.pid), false, 'no record names it');

  const stop = spawnSync(process.execPath, [path.join(repo, 'scripts/dev/dev-runner.cjs'), 'stop', '--json', '--session-id', 'engine-job'],
    { cwd: repo, env, encoding: 'utf8', windowsHide: true, timeout: 120000 });
  const result = JSON.parse(stop.stdout.trim().split(/\r?\n/).at(-1));
  assert.deepEqual(await stillRunning([grandchild]), [], 'the Engine\'s detached process must not outlive the stop: '
    + JSON.stringify(result) + '\n' + output);
  assert.equal(result.outcome, 'STOPPED', JSON.stringify(result));
  assert.ok(lists(result.engineJob?.endedByJob, grandchild), 'the stop lists it by identity: ' + JSON.stringify(result.engineJob));
  const report = read(path.join(state, 'runs', live.active.runId, 'stop-report.json'));
  assert.ok(lists(report?.engineJob?.endedByJob, grandchild), 'the stop report lists it by identity: ' + JSON.stringify(report?.engineJob));
  assert.equal(report.runner?.engine?.code, 0, 'the Engine itself quit with 0: ' + JSON.stringify(report.runner));
  const exits = fs.readFileSync(path.join(data, 'runtime', 'exit-ledger', 'engine.v1.jsonl'), 'utf8').split(/\r?\n/)
    .filter(Boolean).map(l => JSON.parse(l)).filter(r => r.type === 'engine-exit');
  assert.deepEqual(exits.map(r => [r.code, r.containment, r.jobEmpty]), [[0, 'DEV_RUNNER_JOB', true]],
    'one exit, code 0, in the runner\'s job, which its host left empty');
  assert.deepEqual(await stillRunning([decoy.recordedIdentity], 0), [decoy.recordedIdentity],
    'a process outside the run with the same image name is never touched');
  await until(() => start.exitCode !== null, 15000, () => 'the runner did not exit');
});

// WindowsEngineJob's own join, call for call; then a child as the Engine's helpers are started.
const NESTED_ENGINE = `param([string]$Out, [string]$Quit, [int]$Code)
Add-Type -TypeDefinition @'
using System; using System.Runtime.InteropServices;
public static class FixtureEngineJob {
  [StructLayout(LayoutKind.Sequential)] struct BASIC { public long a; public long b; public uint LimitFlags; public UIntPtr c; public UIntPtr d; public uint e; public UIntPtr f; public uint g; public uint h; }
  [StructLayout(LayoutKind.Sequential)] struct IO { public ulong a, b, c, d, e, f; }
  [StructLayout(LayoutKind.Sequential)] struct EXT { public BASIC Basic; public IO Io; public UIntPtr p1, p2, p3, p4; }
  [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr CreateJobObjectW(IntPtr attributes, IntPtr name);
  [DllImport("kernel32.dll", SetLastError = true)] static extern bool SetInformationJobObject(IntPtr job, int infoClass, ref EXT info, int length);
  [DllImport("kernel32.dll", SetLastError = true)] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
  [DllImport("kernel32.dll")] static extern IntPtr GetCurrentProcess();
  public static IntPtr Job;
  public static string Join() {
    Job = CreateJobObjectW(IntPtr.Zero, IntPtr.Zero);
    if (Job == IntPtr.Zero) return "CreateJobObjectW " + Marshal.GetLastWin32Error();
    EXT limits = new EXT(); limits.Basic.LimitFlags = 0x2000;
    if (!SetInformationJobObject(Job, 9, ref limits, Marshal.SizeOf(typeof(EXT)))) return "SetInformationJobObject " + Marshal.GetLastWin32Error();
    if (!AssignProcessToJobObject(Job, GetCurrentProcess())) return "AssignProcessToJobObject " + Marshal.GetLastWin32Error();
    return "joined";
  }
}
'@
$joined = [FixtureEngineJob]::Join()
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "$env:SystemRoot\\System32\\PING.EXE"; $psi.Arguments = '-n 600 127.0.0.1'
$psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
$helper = [System.Diagnostics.Process]::Start($psi)
@{ joined = $joined; helper = $helper.Id; root = $PID } | ConvertTo-Json -Compress | Set-Content -LiteralPath $Out
while (-not (Test-Path -LiteralPath $Quit)) { Start-Sleep -Milliseconds 50 }
exit $Code
`;

async function startNestedEngine(name, code) {
  const dir = path.join(root, name); fs.mkdirSync(dir, { recursive: true });
  const script = path.join(dir, 'engine.ps1'); fs.writeFileSync(script, NESTED_ENGINE);
  const out = path.join(dir, 'engine.json'), quit = path.join(dir, 'quit');
  // CreateProcessW takes the application by its path, as the Engine's launcher names java.exe.
  const powershell = path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe');
  const run = jobRun.startInJob({ command: powershell, args: ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
    '-File', script, '-Out', out, '-Quit', quit, '-Code', String(code)], stdio: 'ignore', label: name,
  env: { ...process.env, JUSTSEARCH_JOB_RECORD_DIR: path.join(dir, 'records') } });
  owned.push(run.host);
  const engine = await until(() => read(out), 60000, () => 'the nested-job Engine did not start');
  assert.equal(engine.joined, 'joined', 'the Engine joins its own job inside the runner\'s job, as WindowsEngineJob does');
  const record = await until(() => { const r = jobRun.readRunRecord(run); return r?.members?.some(m => m.pid === engine.helper) ? r : null; },
    10000, () => 'the helper in the Engine\'s nested job was not recorded as a member of the runner\'s job: ' + JSON.stringify(jobRun.readRunRecord(run)));
  const rootId = { pid: record.root.pid, creationFileTimeUtc: record.root.creationFileTimeUtc };
  const helper = record.members.find(m => m.pid === engine.helper);
  owned.push(rootId, helper);
  assert.equal(rootId.pid, engine.root, 'the job root is the Engine');
  return { run, quit, rootId, helper };
}

test('the Engine\'s own nested job changes neither containment nor the exit code it leaves', async () => {
  // An ordered exit: the Engine's code passes through the runner's job host unchanged.
  const quitting = await startNestedEngine('nested-quit', 7);
  const exited = new Promise(resolve => quitting.run.child.once('exit', code => resolve(code)));
  fs.writeFileSync(quitting.quit, '');
  assert.equal(await exited, 7, 'the host exits with the Engine\'s own code');
  const final = jobRun.readRunRecord(quitting.run);
  assert.equal(final.state, 'ended', 'the job is empty once the Engine has exited');
  assert.equal(final.rootExitCode, 7);
  assert.deepEqual(await stillRunning([quitting.rootId, quitting.helper]), [], 'the Engine and its helper have ended');

  // A stop while it runs: closing the runner's job ends the Engine and the helper in its nested job.
  const running = await startNestedEngine('nested-stop', 0);
  const stopped = jobRun.stopJobRunSync(running.run);
  assert.equal(stopped.ok, true, JSON.stringify(stopped));
  assert.deepEqual(await stillRunning([running.rootId, running.helper]), [], 'the stop ended the Engine and its helper');
  assert.ok(lists(stopped.endedByJob, running.helper) && lists(stopped.endedByJob, running.rootId),
    'the stop lists by identity what the job\'s termination ended: ' + JSON.stringify(stopped));
});

test('a fallback stop ends the Engine\'s job last, after the ordered quit and the recorded identities, and lists what it ended', async () => {
  const birth = '134000000000000000';
  const [backend, frontend, left] = [987651, 987652, 987653];
  const owner = api.holderBlockFor(api.resolveCallerIdentity('owner'));
  const fixture = (id, jobResult) => {
    const dir = path.join(process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT, 'runs', id); fs.mkdirSync(dir, { recursive: true });
    const recordPath = path.join(dir, 'engine-jobs', 'engine-1.json');
    fs.mkdirSync(path.dirname(recordPath), { recursive: true }); fs.writeFileSync(recordPath, '{}');
    const run = { runId: id, holder: owner, pids: { backendRootPid: backend, frontendRootPid: frontend },
      identities: { backend: { pid: backend, creationFileTimeUtc: birth }, frontend: { pid: frontend, creationFileTimeUtc: birth } },
      apiPortActual: 1, engineJob: { incarnation: 1, recordPath, host: { pid: 987650, creationFileTimeUtc: birth } } };
    const runPath = path.join(dir, 'run.json'); fs.writeFileSync(runPath, JSON.stringify(run));
    const alive = new Set([backend, frontend]);
    const order = [];
    const io = {
      resolveTarget: async () => ({ run, runPath }),
      census: async () => ({ finish: async () => ({}) }),
      lookupIdentities: async ids => new Map(ids.map(p => [p, alive.has(p) ? { present: true, creationFileTime: birth } : { present: false }])),
      portOwner: async () => ({ closed: true }), portWaitMs: 0,
      gracefulShutdown: async () => { order.push('ordered quit'); return { outcome: 'failed', requested: true }; },
      terminateEngine: async pid => { order.push(`identity ${pid}`); alive.delete(pid); return { ok: true, outcome: 'terminated' }; },
      terminateProcess: async pid => { order.push(`identity ${pid}`); alive.delete(pid); return { ok: true, outcome: 'terminated' }; },
      stopEngineJob: job => { order.push(`job ${job.host.pid}`); return jobResult; },
    };
    return { io, order, dir };
  };
  const ended = { pid: left, creationFileTimeUtc: birth };
  const clean = fixture('fallback-job', { ok: true, outcome: 'terminated', host: 'terminated', endedByJob: [ended], survivors: [] });
  const out = await api.stopRun({}, clean.io);
  assert.deepEqual(clean.order, ['ordered quit', `identity ${backend}`, `identity ${frontend}`, 'job 987650'],
    'the job is ended last: ' + JSON.stringify(clean.order));
  assert.equal(out.outcome, 'STOPPED', JSON.stringify(out));
  assert.ok(lists(out.engineJob?.endedByJob, ended), 'what the job ended is listed by identity: ' + JSON.stringify(out.engineJob));
  assert.ok(lists(read(path.join(clean.dir, 'stop-report.json'))?.engineJob?.endedByJob, ended), 'and in the stop report');

  const stuck = fixture('fallback-job-survivor', { ok: false, outcome: 'incomplete', host: 'terminated', endedByJob: [], survivors: [ended] });
  const incomplete = await api.stopRun({}, stuck.io);
  assert.equal(incomplete.outcome, 'STOP_INCOMPLETE', 'a job member still running keeps the stop incomplete: ' + JSON.stringify(incomplete));
  assert.ok(incomplete.unresolvedProcesses?.some(p => p.pid === left) ?? read(path.join(stuck.dir, 'stop-report.json'))
    ?.unresolvedProcesses?.some(p => p.pid === left), 'and names it');
});

let failed = 0;
try {
  for (const [name, fn] of tests) {
    try { await fn(); console.log(`PASS ${name}`); }
    catch (error) { failed++; console.error(`FAIL ${name}: ${error.stack}`); }
  }
} finally {
  // Only identities this harness recorded, each by its own birth.
  for (const p of owned) if (p?.pid && /^\d+$/.test(String(p.creationFileTimeUtc))) {
    spawnSync(identityExecutable(), [String(p.pid), String(p.creationFileTimeUtc)], { windowsHide: true });
  }
  for (const decoy of decoys) if (decoy.exitCode === null) decoy.kill();
  fs.rmSync(root, { recursive: true, force: true });
  fs.rmSync(dataRoot, { recursive: true, force: true });
}
console.log(`engine-job: ${tests.length - failed}/${tests.length} passed`);
process.exitCode = failed ? 1 : 0;
