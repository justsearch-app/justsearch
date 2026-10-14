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
 * @returns {{ child: import('child_process').ChildProcess, recordPath: string|null,
 *   host: {pid: number, creationFileTimeUtc: string}|null, contained: boolean }}
 */
function startInJob({ command, args = [], cwd, env = process.env, stdio, label = 'run',
  platform = process.platform, spawnImpl = spawn, capture = captureSpawnedIdentity } = {}) {
  if (platform !== 'win32') {
    return { child: spawnImpl(command, args, { cwd, env, stdio, shell: false }), recordPath: null, host: null, contained: false };
  }
  const dir = recordDirectory(env);
  fs.mkdirSync(dir, { recursive: true });
  const recordPath = path.join(dir, `${label}-${process.pid}-${crypto.randomUUID()}.json`);
  const spec = Buffer.from(JSON.stringify({ ...windowsCommand(command, args, env), cwd: cwd ?? null }), 'utf8').toString('base64');
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
 * @returns {{ ok: boolean, outcome: string, survivors?: object[] }}
 */
function stopJobRunSync(run, { code = NON_DISTINCTIVE_KILL_CODE, waitMs = 10000, exec = spawnSync } = {}) {
  if (!run?.recordPath || !run.host?.pid || !/^[1-9]\d{0,19}$/.test(String(run.host.creationFileTimeUtc ?? ''))) {
    return { ok: false, outcome: 'unknown-identity' };
  }
  try {
    const result = exec('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
      '-File', STOP_SCRIPT, '-RecordPath', run.recordPath, '-Pid', String(run.host.pid),
      '-CreationFileTimeUtc', String(run.host.creationFileTimeUtc), '-Code', String(code >>> 0),
      '-WaitMs', String(waitMs)], { encoding: 'utf8', windowsHide: true, timeout: waitMs * 2 + 15000 });
    const detail = JSON.parse(String(result.stdout).trim().split(/\r?\n/).pop()) ?? {};
    const outcome = detail.outcome ?? 'failed';
    const ok = result.status === 0 && (outcome === 'terminated' || outcome === 'gone');
    if (ok) fs.rmSync(run.recordPath, { force: true });
    return { ...detail, ok, outcome, status: result.status ?? null };
  } catch {
    return { ok: false, outcome: 'failed', status: null };
  }
}

/** After the host has exited by itself (its job is then empty), its record is no longer needed. */
function retireRunRecord(run) {
  const record = readRunRecord(run);
  if (record?.state === 'ended') fs.rmSync(run.recordPath, { force: true });
}

module.exports = {
  startInJob, stopJobRunSync, readRunRecord, retireRunRecord, windowsCommand, quoteArgument,
  recordDirectory, NON_DISTINCTIVE_KILL_CODE, HOST_SCRIPT, STOP_SCRIPT,
};
