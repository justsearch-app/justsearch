'use strict';
/**
 * Start a dev tool's process so that every process it starts can be ended, without parent links.
 *
 * On Windows the command runs under job-host.ps1: the root is created suspended inside a new job
 * whose only limit is KILL_ON_JOB_CLOSE (no breakaway), as WindowsEngineJob contains the Engine's
 * children, so the kernel adds each descendant at its creation, including what third-party tools
 * (npm, Gradle, Vite) start. The host's identity (PID plus creation time) is read in the spawn turn;
 * the run record names the root and every member by PID plus creation time. A stop ends the host
 * when its handle shows that birth, which closes the job's only handle so Windows ends every
 * member, then waits for each recorded identity. Nothing is found through parent PIDs, names or a
 * PID alone. A `.cmd`/`.bat` command runs through cmd.exe as its documented interpreter; callers
 * start node or java directly where they can.
 */
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { EventEmitter } = require('node:events');
const { spawn, spawnSync } = require('node:child_process');
const { captureSpawnedIdentity } = require('./engine-actuator.cjs');

const HOST_SCRIPT = path.join(__dirname, 'job-host.ps1');
const STOP_SCRIPT = path.join(__dirname, 'stop-job-run.ps1');
/** Same as engine-actuator's: a stop that says nothing distinctive about why. */
const NON_DISTINCTIVE_KILL_CODE = 1;

/** One argument for CreateProcess, quoted as CommandLineToArgvW reads it back. */
function quoteArgument(value) {
  const text = String(value);
  if (text !== '' && !/[\s"]/.test(text)) return text;
  let out = '"', slashes = 0;
  for (const ch of text) {
    if (ch === '\\') { slashes++; continue; }
    if (ch === '"') { out += '\\'.repeat(slashes * 2 + 1) + '"'; slashes = 0; continue; }
    out += '\\'.repeat(slashes) + ch; slashes = 0;
  }
  return out + '\\'.repeat(slashes * 2) + '"';
}

/** The CreateProcess application and command line; a batch file runs through cmd.exe /d /s /c. */
function windowsCommand(command, args = [], env = process.env) {
  if (/\.(cmd|bat)$/i.test(command)) {
    const comspec = env.ComSpec || env.COMSPEC || path.join(env.SystemRoot || 'C:\\Windows', 'System32', 'cmd.exe');
    const inner = [command, ...args].map(quoteArgument).join(' ');
    return { application: comspec, commandLine: `${quoteArgument(comspec)} /d /s /c "${inner}"` };
  }
  return { application: command, commandLine: [command, ...args].map(quoteArgument).join(' ') };
}

function recordDirectory(env = process.env) {
  return env.JUSTSEARCH_JOB_RECORD_DIR || path.join(os.tmpdir(), 'justsearch-job-runs');
}

/**
 * `newProcessGroup`: the root starts a new process group, so the console's Ctrl+C reaches neither
 * it nor anything it starts; the caller then ends the run through the root's own quit channel or
 * the job. The host itself never acts on Ctrl+C.
 *
 * `holdAfterRoot`: when the root exits the host records it (state `root-exited`, `rootExitCode`)
 * and keeps what the root left in the job until the job empties by itself (state `ended`) or the
 * caller's stop ends the host (stopJobRunSync), so the caller's ordered steps run before the job
 * ends anything.
 *
 * @returns {{ child: import('child_process').ChildProcess, recordPath: string|null,
 *   host: {pid: number, creationFileTimeUtc: string}|null, contained: boolean }}
 */
function startInJob({ command, args = [], cwd, env = process.env, stdio, label = 'run', newProcessGroup = false,
  holdAfterRoot = false, recordDir = null, platform = process.platform, spawnImpl = spawn, capture = captureSpawnedIdentity } = {}) {
  if (platform !== 'win32') {
    return { child: spawnImpl(command, args, { cwd, env, stdio, shell: false }), recordPath: null, host: null, contained: false };
  }
  const dir = recordDir ?? recordDirectory(env);
  fs.mkdirSync(dir, { recursive: true });
  const recordPath = path.join(dir, `${label}-${process.pid}-${crypto.randomUUID()}.json`);
  const spec = Buffer.from(JSON.stringify({ ...windowsCommand(command, args, env), cwd: cwd ?? null,
    ...(newProcessGroup ? { newProcessGroup: true } : {}), ...(holdAfterRoot ? { holdAfterRoot: true } : {}) }),
  'utf8').toString('base64');
  const child = spawnImpl('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
    '-File', HOST_SCRIPT, '-Spec', spec, '-RecordPath', recordPath],
  { cwd, env, stdio, windowsHide: true, shell: false });
  // In the spawn turn: libuv still holds the host's handle, so its PID cannot name another process.
  const host = capture(child);
  return { child, recordPath, host, contained: true };
}

/** The run record as last written by its host, or null. */
function readRunRecord(run) {
  try { return run?.recordPath ? JSON.parse(fs.readFileSync(run.recordPath, 'utf8')) : null; } catch { return null; }
}

/**
 * Stops a run started by startInJob and verifies its recorded processes have ended.
 * `endedIdentities` (PID plus creation time) are identities an exit ledger records as ended: they
 * are gone and not probed again, so a PID that now names a process whose birth cannot be read
 * does not keep the stop incomplete. They are reported as `endedPerLedger`.
 * @returns {{ ok: boolean, outcome: string, survivors?: object[] }}
 */
function stopJobRunSync(run, { code = NON_DISTINCTIVE_KILL_CODE, waitMs = 10000, exec = spawnSync, endedIdentities = [] } = {}) {
  if (!run?.recordPath || !run.host?.pid || !/^[1-9]\d{0,19}$/.test(String(run.host.creationFileTimeUtc ?? ''))) {
    return { ok: false, outcome: 'unknown-identity' };
  }
  const ended = (Array.isArray(endedIdentities) ? endedIdentities : [])
    .filter((p) => Number.isSafeInteger(Number(p?.pid)) && Number(p.pid) > 0 && /^[1-9]\d{0,19}$/.test(String(p.creationFileTimeUtc ?? '')))
    // Each is compared at the precision its birth was recorded at (`birthTick`, 100 ns ticks; the
    // microsecond when it names none), so an exact ledger birth never matches a neighbouring one.
    .map((p) => ({ pid: Number(p.pid), creationFileTimeUtc: String(p.creationFileTimeUtc),
      birthTick: /^[1-9]\d{0,9}$/.test(String(p.birthTick ?? '')) ? String(p.birthTick) : '10' }));
  const endedPath = ended.length ? `${run.recordPath}.ended-${process.pid}-${crypto.randomUUID()}.json` : null;
  try {
    if (endedPath) fs.writeFileSync(endedPath, JSON.stringify(ended));
    const result = exec('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
      '-File', STOP_SCRIPT, '-RecordPath', run.recordPath, '-Pid', String(run.host.pid),
      '-CreationFileTimeUtc', String(run.host.creationFileTimeUtc), '-Code', String(code >>> 0),
      '-WaitMs', String(waitMs), ...(endedPath ? ['-EndedPath', endedPath] : [])],
    { encoding: 'utf8', windowsHide: true, timeout: waitMs * 2 + 15000 });
    const detail = JSON.parse(String(result.stdout).trim().split(/\r?\n/).pop()) ?? {};
    const outcome = detail.outcome ?? 'failed';
    const ok = result.status === 0 && (outcome === 'terminated' || outcome === 'gone');
    if (ok) fs.rmSync(run.recordPath, { force: true });
    return { ...detail, ok, outcome, status: result.status ?? null };
  } catch {
    return { ok: false, outcome: 'failed', status: null };
  } finally {
    if (endedPath) fs.rmSync(endedPath, { force: true });
  }
}

/** After the host has exited by itself (its job is then empty), its record is no longer needed. */
function retireRunRecord(run) {
  const record = readRunRecord(run);
  if (record?.state === 'ended') fs.rmSync(run.recordPath, { force: true });
}

/**
 * Once the run's host has exited: a record its host closed as `ended` is retired; any other record
 * (a host that was itself ended) is settled by the stop, which verifies every identity it names
 * and removes the record when none is still running.
 */
function settleRunRecord(run, options) {
  if (!run?.recordPath || !fs.existsSync(run.recordPath)) return { ok: true, outcome: 'gone' };
  const record = readRunRecord(run);
  if (record?.state === 'ended') {
    fs.rmSync(run.recordPath, { force: true });
    return { ok: true, outcome: 'gone', endedByJob: record.endedByJob ?? [] };
  }
  return stopJobRunSync(run, options);
}

/**
 * Blocks until the host has recorded its root (written before the root is resumed, so the root has
 * run no code yet), the host has exited, or `timeoutMs` passes. The host's PID cannot name another
 * process meanwhile: libuv holds its handle until its exit is reaped.
 */
function waitForRootSync(run, { timeoutMs = 20000, intervalMs = 10 } = {}) {
  if (!run?.recordPath) return null;
  const pause = new Int32Array(new SharedArrayBuffer(4));
  const end = Date.now() + timeoutMs;
  for (;;) {
    const record = readRunRecord(run);
    if (record?.root?.pid && Number(record.pid) === run.child.pid) return record;
    let alive = true;
    try { process.kill(run.child.pid, 0); } catch { alive = false; }
    if (!alive || Date.now() >= end) return null;
    Atomics.wait(pause, 0, 0, intervalMs);
  }
}

/**
 * The root of a job run, seen as a child process: its PID and recorded birth are the root's, its
 * streams are the host's (the host passes its standard handles to the root). 'exit' is the root's:
 * the root's code once the host records it (a held job, holdAfterRoot) or the host exits with it.
 * It has no kill(): a stop goes through a recorded identity or the job. `jobRun` is the run;
 * `jobFinal` is the record as it stood when the root's exit was seen (state `ended` when nothing
 * was left in the job, `root-exited` when the host holds what was left); 'job-closed' follows when
 * the host itself exits.
 */
function rootAsChild(run, record, { spawnfile, pollMs = 25 } = {}) {
  const host = run.child;
  const facade = new EventEmitter();
  let exited = false, rootCode = null;
  const emitExit = (latest, code, signal) => {
    if (exited) return;
    exited = true;
    clearInterval(poll);
    facade.jobFinal = latest;
    rootCode = code;
    facade.emit('exit', code, signal);
  };
  // A held job outlives its root: the root's exit is read from the record its host writes.
  const poll = setInterval(() => {
    const latest = readRunRecord(run);
    if (latest && Number.isInteger(latest.rootExitCode) && Number(latest.pid) === host.pid) {
      emitExit(latest, latest.rootExitCode, null);
    }
  }, pollMs);
  Object.defineProperties(facade, {
    pid: { value: record?.root?.pid ?? undefined, enumerable: true },
    rootIdentity: { value: record?.root ? { pid: record.root.pid, creationFileTimeUtc: String(record.root.creationFileTimeUtc) } : null },
    stdin: { get: () => host.stdin }, stdout: { get: () => host.stdout }, stderr: { get: () => host.stderr },
    exitCode: { get: () => (exited ? rootCode ?? host.exitCode : null) },
    signalCode: { get: () => (exited && rootCode === null ? host.signalCode : null) },
    spawnfile: { value: spawnfile ?? host.spawnfile },
    jobRun: { value: run },
  });
  facade.jobFinal = null;
  host.on('error', (err) => facade.emit('error', err));
  host.on('exit', (code, signal) => {
    clearInterval(poll);
    const latest = readRunRecord(run);
    emitExit(latest, Number.isInteger(latest?.rootExitCode) ? latest.rootExitCode : code,
      Number.isInteger(latest?.rootExitCode) ? null : signal);
    facade.emit('job-closed', latest);
  });
  host.on('close', (code, signal) => facade.emit('close', code, signal));
  return facade;
}

module.exports = {
  startInJob, stopJobRunSync, readRunRecord, retireRunRecord, settleRunRecord, waitForRootSync, rootAsChild,
  windowsCommand, quoteArgument,
  recordDirectory, NON_DISTINCTIVE_KILL_CODE, HOST_SCRIPT, STOP_SCRIPT,
};
