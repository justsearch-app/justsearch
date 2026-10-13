'use strict';
// Loaded before each fixture program. A cleanup owns exactly these births, never a PID tree.
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const dir = process.env.JUSTSEARCH_CONFORMANCE_IDENTITIES;
if (dir) {
  if (process.platform !== 'win32') throw new Error('fixture birth capture requires Windows');
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
    `(Get-Process -Id ${process.pid} -ErrorAction Stop).StartTime.ToFileTimeUtc().ToString()`],
  { encoding: 'utf8', windowsHide: true, timeout: 10000 });
  const birth = result.stdout?.trim();
  if (result.status !== 0 || !/^[1-9][0-9]+$/.test(birth ?? '')) throw new Error('fixture identity unavailable');
  fs.mkdirSync(dir, { recursive: true });
  // Written aside and renamed, so a teardown never reads a half-written identity.
  const file = path.join(dir, `${process.pid}-${birth}.json`);
  fs.writeFileSync(`${file}.tmp`, JSON.stringify({
    pid: process.pid, creationFileTimeUtc: birth, executable: process.execPath,
  }), { flag: 'wx' });
  fs.renameSync(`${file}.tmp`, file);
}
