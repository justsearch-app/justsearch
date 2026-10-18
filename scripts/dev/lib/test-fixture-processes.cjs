'use strict';
// Repository-owned fixture setup: no NODE_OPTIONS or private binary is needed.
const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const cp = require('node:child_process');
const { syncBuiltinESMExports } = require('node:module');
const spawn = cp.spawn, fork = cp.fork, execFile = cp.execFile, spawnSync = cp.spawnSync;
const originalKill = cp.ChildProcess.prototype.kill, remove = fs.rmSync, removeAsync = fs.promises.rm;
const records = new WeakMap();
let directory, executable;
function identityExecutable() {
  if (executable) return executable;
  if (process.platform !== 'win32') throw new Error('Windows fixture identity helper requested off Windows');
  directory = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-fixture-identity-'));
  executable = path.join(directory, 'FixtureIdentity.exe');
  const escaped = x => x.replaceAll("'", "''");
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
    `Add-Type -Path '${escaped(path.join(__dirname, 'test-fixture-identity.cs'))}' -OutputAssembly '${escaped(executable)}' -OutputType ConsoleApplication`],
    { encoding: 'utf8', windowsHide: true, timeout: 60000 });
  if (result.status !== 0) throw new Error('Fixture identity compiler failed: ' + result.stderr);
  return executable;
}
function note(row) {
  const log = process.env.JUSTSEARCH_FIXTURE_IDENTITY_LOG;
  if (log) fs.appendFileSync(log, JSON.stringify({ ...row, at: new Date().toISOString() }) + '\n');
}
function record(child) {
  if (!child?.pid) return child;
  if (process.platform === 'linux') {
    const seen = require('./process-identity.cjs').readLinuxProcess(child.pid);
    if (seen?.present && seen.processIdentity) child.recordedIdentity = { pid: child.pid,
      processIdentity: seen.processIdentity, executable: seen.executable };
    return child;
  }
  if (process.platform !== 'win32') return child;
  // Synchronous query before the event loop can release the original child's Windows handle.
  const r = spawnSync(identityExecutable(), [String(child.pid)], { encoding: 'utf8', windowsHide: true });
  const birth = r.stdout.trim();
  if (r.status !== 0 || !/^\d+$/.test(birth)) throw new Error('Fixture spawn birth unavailable: ' + child.pid);
  records.set(child, birth); child.recordedIdentity = { pid: child.pid, creationFileTimeUtc: birth };
  note({ event: 'spawn', pid: child.pid, creationFileTimeUtc: birth }); return child;
}
if (process.platform === 'linux') {
  cp.spawn = function(...args) { return record(spawn.apply(this, args)); };
  cp.fork = function(...args) { return record(fork.apply(this, args)); };
  cp.execFile = function(...args) { return record(execFile.apply(this, args)); };
  cp.ChildProcess.prototype.kill = function(signal = 'SIGTERM') {
    if (!this.pid || this.exitCode !== null || this.signalCode !== null) return false;
    const identity = this.recordedIdentity?.processIdentity;
    if (!identity) throw new Error('Fixture stop without a spawn-recorded identity refused: ' + this.pid);
    const result = require('./process-identity.cjs').signalLinuxProcess(identity, signal);
    if (![0, 3].includes(result.status)) throw new Error('Fixture identity-bound stop failed: ' + this.pid + ' ' + result.reason);
    return result.status === 0;
  };
}
if (process.platform === 'win32') {
  // Compile before any fixture is launched, not while its startup deadline is running.
  identityExecutable();
  cp.spawn = function(...args) { return record(spawn.apply(this, args)); };
  cp.fork = function(...args) { return record(fork.apply(this, args)); };
  cp.execFile = function(...args) { return record(execFile.apply(this, args)); };
  cp.ChildProcess.prototype.kill = function(signal) {
    if (!this.pid || this.exitCode !== null || this.signalCode !== null) return false;
    const birth = records.get(this);
    if (!birth) throw new Error('Fixture stop without a spawn-recorded identity refused: ' + this.pid);
    const r = spawnSync(identityExecutable(), [String(this.pid), birth], { encoding: 'utf8', windowsHide: true });
    note({ event: 'stop', pid: this.pid, creationFileTimeUtc: birth, status: r.status });
    if (![0, 3].includes(r.status)) throw new Error('Fixture identity-bound stop failed: ' + this.pid);
    return r.status === 0;
  };
}
// Hosted Windows TEMP may use an 8.3 alias while fixtures canonicalize their paths.
// Accept both spellings of each declared root, without admitting any other directory.
const roots = [os.tmpdir(), path.resolve(__dirname, '../../../tmp')].flatMap(p => {
  const resolved = path.resolve(p);
  let physical = resolved;
  try { physical = fs.realpathSync.native(resolved); } catch (e) { if (e.code !== 'ENOENT') throw e; }
  return [resolved.toLowerCase(), physical.toLowerCase()];
});
function unlinkBeforeRemoval(target) {
  let abs = path.resolve(String(target));
  const inside = p => roots.some(root => p.startsWith(root + path.sep));
  let physical = abs; try { physical = fs.realpathSync.native(abs); } catch (e) { if (e.code !== 'ENOENT') throw e; }
  if (!inside(abs.toLowerCase()) && !inside(physical.toLowerCase()) && process.platform === 'win32') {
    const drive = path.parse(abs).root.slice(0, 2).toUpperCase();
    const prefix = drive + '\\: => ';
    const mapping = spawnSync('subst.exe', [], { encoding: 'utf8', windowsHide: true }).stdout.split(/\r?\n/).find(s => s.startsWith(prefix));
    if (mapping) physical = path.resolve(mapping.slice(prefix.length), abs.slice(3));
  }
  if (!inside(abs.toLowerCase()) && !inside(physical.toLowerCase())) throw new Error('Fixture recursive removal outside temporary roots refused: ' + abs);
  if (!inside(abs.toLowerCase())) abs = physical;
  function visit(p) {
    let stat; try { stat = fs.lstatSync(p); } catch (e) { if (e.code === 'ENOENT') return; throw e; }
    if (stat.isSymbolicLink()) remove(p, { force: true });
    else if (stat.isDirectory()) for (const name of fs.readdirSync(p)) visit(path.join(p, name));
  }
  visit(abs); return abs;
}
fs.rmSync = function(p, opts) { return remove.call(this, opts?.recursive ? unlinkBeforeRemoval(p) : p, opts); };
fs.promises.rm = function(p, opts) { return removeAsync.call(this, opts?.recursive ? unlinkBeforeRemoval(p) : p, opts); };
syncBuiltinESMExports();
process.on('exit', () => { if (directory) fs.rmSync(directory, { recursive: true, force: true }); });
module.exports = { identityExecutable, record };
