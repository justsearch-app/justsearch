#!/usr/bin/env node
/**
 * Tempdoc 861 W3 [A3] — resolve the PID actually LISTENING on a TCP port.
 *
 * Why this exists, and why it is not `child.pid`: `serve-worktree-fe.cjs` spawns `npx.cmd` with
 * `shell: isWin`, so Node launches `cmd.exe /d /s /c "npx.cmd vite …"` — `child.pid` is the
 * **`cmd.exe` shim**, and the surviving Vite that actually holds the port and the file locks is
 * two or three generations below it (861 [A3], `serve-worktree-fe.cjs:115-120` before this
 * change). A record built from `child.pid` would be a register full of dead pids describing a
 * shim that exits almost immediately, while the process that leaks runs on unrecorded. The fix is
 * additive: resolve the listener from the port itself, AFTER the readiness gate, and record that
 * — no change to how anything is launched (861 §7.1 Phase 3's decided fix).
 *
 * `dev-runner.cjs` already has this exact technique, privately, as `getPortOwnerWindows`
 * (`dev-runner.cjs:1207-1227`) — unexported, and specific to that file's own async
 * `execPowerShell` helper. Rather than write a second copy, this is the ONE shared,
 * synchronously-callable, dependency-injectable implementation producers use.
 *
 * Nothing in this module writes a record, kills, or signals a process. It answers one question:
 * "what PID, if any, is listening on this port right now?"
 */
'use strict';

const { spawnSync } = require('node:child_process');

/**
 * @param {number} port
 * @param {object} [opts]
 * @param {string} [opts.platform]
 * @param {typeof spawnSync} [opts.exec] - injectable so this is unit-testable without PowerShell.
 * @returns {{ ok: true, pid: number } | { ok: false, reason: string }}
 */
function resolveListenerPidWindows(port, { platform = process.platform, exec = spawnSync } = {}) {
  if (platform !== 'win32') {
    return { ok: false, reason: `listener-pid resolution is implemented for win32 only (platform=${platform})` };
  }
  if (!Number.isInteger(port) || port <= 0 || port > 65535) {
    return { ok: false, reason: `invalid port ${JSON.stringify(port)}` };
  }
  let res;
  try {
    res = exec(
      'powershell.exe',
      [
        '-NoProfile',
        '-NonInteractive',
        '-Command',
        `Get-NetTCPConnection -State Listen -LocalPort ${port} -ErrorAction SilentlyContinue | ` +
          'Select-Object -First 1 OwningProcess | ConvertTo-Json -Compress',
      ],
      { encoding: 'utf8', maxBuffer: 1024 * 1024 },
    );
  } catch (err) {
    return { ok: false, reason: `port-owner query threw: ${String(err?.message || err).slice(0, 200)}` };
  }
  if (!res || res.status !== 0) {
    return { ok: false, reason: `port-owner query exited ${res ? res.status : 'with no result'}` };
  }
  const out = (res.stdout || '').trim();
  if (!out) return { ok: false, reason: `nothing listening on port ${port}` };
  let parsed;
  try {
    parsed = JSON.parse(out);
  } catch (err) {
    return { ok: false, reason: `port-owner output is not JSON: ${String(err?.message || err).slice(0, 200)}` };
  }
  const pid = Number(parsed?.OwningProcess);
  if (!Number.isInteger(pid) || pid <= 0) {
    return { ok: false, reason: `no usable OwningProcess in port-owner output (${JSON.stringify(parsed)})` };
  }
  return { ok: true, pid };
}

module.exports = { resolveListenerPidWindows };
