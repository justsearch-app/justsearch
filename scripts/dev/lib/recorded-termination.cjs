'use strict';
const path = require('node:path');
const { spawnSync } = require('node:child_process');

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

module.exports = { terminateWithCodeSync, inspectRecordedTree, inspectProcessTrees };
