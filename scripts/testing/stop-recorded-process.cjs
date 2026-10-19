'use strict';
const { spawnSync } = require('node:child_process');
const path = require('node:path');
/** Only for fixture-emitted PID/birth identities recorded when that fixture started. */
function stopRecordedProcess(identity) {
  if (process.platform === 'linux') {
    const { linuxIdentity, signalLinuxProcess } = require('../dev/lib/process-identity.cjs');
    if (!linuxIdentity(identity)) throw new Error('fixture start identity unavailable');
    const result = signalLinuxProcess(identity);
    if (!result.ok && result.status !== 3) throw new Error(`fixture stop failed: ${JSON.stringify(result)}`);
    return;
  }
  if (!Number.isSafeInteger(identity?.pid) || !/^[1-9][0-9]+$/.test(identity?.creationFileTimeUtc ?? '')) {
    throw new Error('fixture start identity unavailable');
  }
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
    '-File', path.join(__dirname, '../dev/lib/terminate-process.ps1'), '-Pid', String(identity.pid),
    '-CreationFileTimeUtc', identity.creationFileTimeUtc, '-Code', '1', '-BirthTick', '10'],
    { encoding: 'utf8', windowsHide: true, timeout: 10000 });
  if (result.error) throw result.error;
  if (![0, 3].includes(result.status)) throw new Error(`fixture stop failed: ${result.stdout} ${result.stderr}`);
}
module.exports = { stopRecordedProcess };
