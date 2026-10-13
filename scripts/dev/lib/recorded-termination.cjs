'use strict';
const path = require('node:path');
const { spawnSync, spawn } = require('node:child_process');
const { createInterface } = require('node:readline');

/** For teardown paths that exit before another event-loop turn; birth and kill use one OS handle. */
function terminateWithCodeSync(pid, creationFileTime, code, { exec = spawnSync, timeoutMs = 5000,
  tree = false, birthTick = 10, inspectOnly = false } = {}) {
  if (!Number.isInteger(pid) || pid <= 0 || !/^[1-9]\d{0,19}$/.test(String(creationFileTime ?? ''))) {
    return { ok: false, outcome: 'unknown-identity', status: null };
  }
  try {
    const result = exec('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
      '-File', path.join(__dirname, 'terminate-process.ps1'), '-Pid', String(pid),
      '-CreationFileTimeUtc', String(creationFileTime), '-Code', String(code >>> 0),
      '-BirthTick', String(birthTick), ...(tree ? ['-Tree'] : []), ...(inspectOnly ? ['-InspectOnly'] : [])],
    { encoding: 'utf8', windowsHide: true, timeout: timeoutMs });
    const detail = JSON.parse(String(result.stdout).trim().split(/\r?\n/).pop()) ?? {};
    const outcome = detail.outcome ?? 'failed';
    return { ...detail, ok: result.status === 0 && outcome === 'terminated', outcome, status: result.status ?? null };
  } catch {
    return { ok: false, outcome: 'failed', status: null };
  }
}

function inspectRecordedTree(pid, birth, opts = {}) {
  return terminateWithCodeSync(pid, birth, 0, { ...opts, tree: true, inspectOnly: true });
}

function inspectProcessTrees(roots, { exec = spawnSync, timeoutMs = 10000 } = {}) {
  try {
    const encoded = Buffer.from(JSON.stringify(roots.map(p => ({ pid: p.pid,
      creationFileTimeUtc: p.creationFileTimeUtc ?? null, birthTick: p.birthTick ?? 10 })))).toString('base64');
    const r = exec('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
      '-File', path.join(__dirname, 'inspect-process-trees.ps1'), '-RootsBase64', encoded],
    { encoding: 'utf8', windowsHide: true, timeout: timeoutMs });
    const detail = JSON.parse(String(r.stdout).trim().split(/\r?\n/).pop());
    return { ...detail, ok: r.status === 0 && detail.complete === true,
      identities: new Map(Object.entries(detail.identities ?? {}).map(([pid, row]) => [Number(pid), row])) };
  } catch (error) {
    return { ok: false, outcome: 'enumeration-failed', complete: false, processes: [], error: String(error) };
  }
}

/** One read-only PowerShell host per stop, with a fresh native census for every request.
 * Closing stdin ends it normally. Its own watchdog bounds a stalled query; no PID-only kill.
 */
function createTreeInspectionSession({ launch = spawn, timeoutMs = 10000 } = {}) {
  const proc = launch('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
    '-File', path.join(__dirname, 'inspect-process-trees.ps1'), '-Session'],
  { windowsHide: true, stdio: ['pipe', 'pipe', 'ignore'] });
  let pending, closed = false;
  const failure = error => ({ ok: false, complete: false, outcome: 'enumeration-failed', processes: [], error });
  const settle = value => { if (pending) { clearTimeout(pending.timer); const p = pending; pending = null; p.resolve(value); } };
  const lines = createInterface({ input: proc.stdout });
  lines.on('line', line => {
    try { const detail = JSON.parse(line); settle({ ...detail,
      identities: new Map(Object.entries(detail.identities ?? {}).map(([pid, row]) => [Number(pid), row])) }); }
    catch { settle(failure('invalid inspection response')); }
  });
  proc.on('error', error => { closed = true; settle(failure(String(error))); });
  proc.on('exit', () => { closed = true; settle(failure('inspection host exited before its response')); });
  proc.stdin.on('error', error => settle(failure(String(error))));
  return {
    inspect(roots) {
      if (closed) return Promise.resolve(failure('inspection host is closed'));
      if (pending) throw new Error('concurrent tree inspections are not allowed');
      return new Promise(resolve => {
        pending = { resolve, timer: setTimeout(() => {
          settle(failure('inspection deadline exceeded')); closed = true; proc.stdin.end();
        }, timeoutMs) };
        proc.stdin.write(Buffer.from(JSON.stringify(roots.map(p => ({ pid: p.pid,
          creationFileTimeUtc: p.creationFileTimeUtc ?? null, birthTick: p.birthTick ?? 10 })))).toString('base64') + '\n');
      });
    },
    close() { proc.stdin.end(); },
  };
}

module.exports = { terminateWithCodeSync, inspectRecordedTree, inspectProcessTrees, createTreeInspectionSession };
