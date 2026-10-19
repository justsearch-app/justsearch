'use strict';
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { nativeTarget } = require('../../lib/native-targets.cjs');
const { linuxIdentity, readLinuxProcess, signalLinuxProcess } = require('./process-identity.cjs');

/**
 * For teardown paths that exit before another event-loop turn; birth and kill use one OS handle.
 * Only the recorded process is ended: there is no tree mode and no descendant discovery.
 */
function terminateWithCodeSync(pid, creationFileTime, code, { exec = spawnSync, timeoutMs = 5000,
  birthTick = 10, inspectOnly = false } = {}) {
  const linux = linuxIdentity(creationFileTime);
  if (linux) {
    if (linux.pid !== pid) return { ok: false, outcome: 'unknown-identity', status: null };
    const state = () => {
      const seen = readLinuxProcess(pid);
      if (!seen || (seen.present && !seen.processIdentity)) return 'unknown';
      if (!seen.present) return 'gone';
      return seen.processIdentity.bootId === linux.bootId && seen.processIdentity.startTicks === linux.startTicks ? 'inspected' : 'mismatch';
    };
    if (inspectOnly) { const current = state(); return { ok: current === 'inspected', outcome: current, status: null }; }
    const result = signalLinuxProcess(linux, 'SIGKILL', { exec });
    if (!result.ok) return result;
    const deadline = performance.now() + timeoutMs;
    const pause = new Int32Array(new SharedArrayBuffer(4));
    do {
      const current = state();
      if (current === 'gone' || current === 'mismatch') return result;
      Atomics.wait(pause, 0, 0, 5);
    } while (performance.now() < deadline);
    return { ...result, ok: false, outcome: 'timeout', reason: 'Recorded process exit was not confirmed' };
  }
  if (!Number.isInteger(pid) || pid <= 0 || !/^[1-9]\d{0,19}$/.test(String(creationFileTime ?? ''))) {
    return { ok: false, outcome: 'unknown-identity', status: null };
  }
  try {
    const result = exec('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
      '-File', path.join(__dirname, 'terminate-process.ps1'), '-Pid', String(pid),
      '-CreationFileTimeUtc', String(creationFileTime), '-Code', String(code >>> 0),
      '-BirthTick', String(birthTick), ...(inspectOnly ? ['-InspectOnly'] : [])],
    { encoding: 'utf8', windowsHide: true, timeout: timeoutMs });
    const detail = JSON.parse(String(result.stdout).trim().split(/\r?\n/).pop()) ?? {};
    const outcome = detail.outcome ?? 'failed';
    return { ...detail, ok: result.status === 0 && outcome === 'terminated', outcome, status: result.status ?? null };
  } catch {
    return { ok: false, outcome: 'failed', status: null };
  }
}

/** A manual remedy carries the full saved identity; missing/malformed evidence grants nothing. */
function formatTerminationCommand(record) {
  const linux = linuxIdentity(record);
  if (linux) {
    const helper = path.posix.join('modules/ui/build/install/ui', nativeTarget('linux', 'x64').paths['bound-exec']);
    return `${helper} signal ${linux.pid} ${linux.bootId} ${linux.startTicks} SIGKILL`;
  }
  const pid = record?.pid, birth = String(record?.creationFileTimeUtc ?? '');
  if (record?.processIdentity || !Number.isInteger(pid) || pid <= 0 || !/^[1-9]\d{0,19}$/.test(birth)) {
    return 'identity unavailable; no safe termination command';
  }
  return `powershell.exe -NoProfile -File scripts/dev/lib/terminate-process.ps1 -Pid ${pid} -CreationFileTimeUtc ${birth} -Code 1`;
}

module.exports = { terminateWithCodeSync, formatTerminationCommand };
