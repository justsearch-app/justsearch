'use strict';
const path = require('node:path');
const { spawnSync } = require('node:child_process');

/** For teardown paths that exit before another event-loop turn; birth and kill use one OS handle. */
function terminateWithCodeSync(pid, creationFileTime, code, { exec = spawnSync, timeoutMs = 5000,
  tree = false, birthTick = 10 } = {}) {
  if (!Number.isInteger(pid) || pid <= 0 || !/^[1-9]\d{0,19}$/.test(String(creationFileTime ?? ''))) {
    return { ok: false, outcome: 'unknown-identity', status: null };
  }
  try {
    const result = exec('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
      '-File', path.join(__dirname, 'terminate-process.ps1'), '-Pid', String(pid),
      '-CreationFileTimeUtc', String(creationFileTime), '-Code', String(code >>> 0),
      '-BirthTick', String(birthTick), ...(tree ? ['-Tree'] : [])],
    { encoding: 'utf8', windowsHide: true, timeout: timeoutMs });
    const outcome = JSON.parse(String(result.stdout).trim().split(/\r?\n/).pop()).outcome ?? 'failed';
    return { ok: result.status === 0 && outcome === 'terminated', outcome, status: result.status ?? null };
  } catch {
    return { ok: false, outcome: 'failed', status: null };
  }
}

module.exports = { terminateWithCodeSync };
