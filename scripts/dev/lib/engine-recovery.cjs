'use strict';
const fs = require('fs');
const path = require('path');
const ledger = require('./exit-ledger.cjs');

function prepareCrashFolder(directory) {
  fs.mkdirSync(directory, { recursive: true });
  const dumps = fs.readdirSync(directory, { withFileTypes: true })
    .filter(entry => entry.isFile() && entry.name.endsWith('.hprof'))
    .map(entry => ({ name: entry.name, at: fs.statSync(path.join(directory, entry.name)).mtimeMs }))
    .sort((a, b) => a.at - b.at || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  for (const dump of dumps.slice(1, -1)) fs.unlinkSync(path.join(directory, dump.name));
}

function recoveryData(dataDir, now = Date.now()) {
  let recentExits = null;
  try {
    const read = ledger.readLedger(path.join(dataDir, 'runtime', 'exit-ledger'), 'engine');
    const rows = read.records.map(row => row.line).filter(row => {
      const at = ledger.knownTimestamp(row.at);
      return row.type === 'engine-exit' && at !== null && at <= now && now - at <= 86400000
        && (row.exitClass !== 'REQUESTED' || row.contract?.counted === true || row.contract?.exitClass === 'TRANSIENT');
    });
    const codes = {};
    for (const row of rows) { const code = String(row.code ?? row.signalName ?? 'unknown'); codes[code] = (codes[code] ?? 0) + 1; }
    const times = rows.map(row => ledger.knownTimestamp(row.at));
    recentExits = { count: rows.length, codes,
      firstAt: times.length ? new Date(Math.min(...times)).toISOString() : null,
      lastAt: times.length ? new Date(Math.max(...times)).toISOString() : null,
      windowMs: 86400000, incomplete: read.corruptLines > 0 || read.tornTail };
  } catch { /* Optional evidence; failure must not stop supervision. */ }
  return { recentExits, crashFile: latestCrash(dataDir) };
}
function safeLine(line) {
  if (line.replace(/^#\s*/, '').startsWith('Internal Error')) return 'Internal Error [path]';
  return line.replace(/^#\s*/, '').replace(/\[([^\]]+)\]/g, (_, value) => `[${value.split(/[\\/]/).at(-1)}]`)
    .split(/\s+/).map(word => /[\\/]/.test(word) ? '[path]' : word).join(' ').replace(/[\x00-\x1f\x7f]/g, '').slice(0, 256);
}
function latestCrash(dataDir) {
  const directory = path.join(dataDir, 'crashes');
  let files;
  try {
    files = fs.readdirSync(directory, { withFileTypes: true }).filter(entry => entry.isFile() && /^hs_err_pid.*\.log$/.test(entry.name))
      .map(entry => ({ name: entry.name, at: fs.statSync(path.join(directory, entry.name)).mtimeMs }))
      .sort((a, b) => b.at - a.at || b.name.localeCompare(a.name));
  } catch { return null; }
  for (const file of files) {
    let fd;
    try {
      fd = fs.openSync(path.join(directory, file.name), 'r');
      const buffer = Buffer.alloc(64 * 1024);
      const lines = buffer.subarray(0, fs.readSync(fd, buffer, 0, buffer.length, 0)).toString('utf8').split(/\r?\n/).slice(0, 64);
      const exception = lines.find(line => /^#  (SIG|EXCEPTION_|Internal Error)/.test(line));
      const frameIndex = lines.findIndex(line => line.trim() === '# Problematic frame:');
      const frame = frameIndex >= 0 ? lines[frameIndex + 1] : null;
      if (exception || frame) return { name: file.name, at: new Date(file.at).toISOString(),
        exception: exception ? safeLine(exception) : null, problematicFrame: frame ? safeLine(frame) : null };
    } catch { /* Try the next retained report. */ }
    finally { if (fd !== undefined) fs.closeSync(fd); }
  }
  return null;
}
module.exports = { recoveryData, prepareCrashFolder };
