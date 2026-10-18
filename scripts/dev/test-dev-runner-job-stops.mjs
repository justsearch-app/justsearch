import './lib/test-fixture-processes.cjs';
// Every process a dev tool's run starts must end with the run's stop, without parent links: the
// real serve-worktree-fe.cjs and dev-all.cjs run in a scratch copy of the repository whose Vite and
// Gradle are CPU-only fixtures (no dev stack, no network). The fixtures record their own identities
// (PID plus creation time, read on a handle) at start, and the job hosts' run records are read while
// the run lives; after the stop none of those identities may still be running. A process outside
// the run, of the same image name or under a recycled-looking PID, is never touched.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';

// Every case exercises a real Windows job, native creation identity or console Ctrl+C.
// Keep the full suite on Windows; these prerequisites do not exist on the Ubuntu runner.
if (process.platform !== 'win32') {
  console.log('job-stops: SKIP (Windows job objects, process creation identities and console events)');
  process.exit(0);
}

const require = createRequire(import.meta.url);
const repo = path.resolve(import.meta.dirname, '../..');
const actuator = require('./lib/engine-actuator.cjs');
const { identityExecutable } = require('./lib/test-fixture-processes.cjs');
const tests = [], decoys = [], logged = [];
const test = (name, fn) => tests.push([name, fn]);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'job-stops-'));

const FIXTURE_COMMON = `const cp=require('child_process'),fs=require('fs');
const own=cp.spawnSync(process.env.FIXTURE_IDENTITY_EXE,[String(process.pid)],{encoding:'utf8',windowsHide:true}).stdout.trim();
const note=(role,pid,birth)=>fs.appendFileSync(process.env.FIXTURE_LOG,JSON.stringify({role,pid,creationFileTimeUtc:birth})+'\\n');
setTimeout(()=>process.exit(0),120000).unref();
const keep=setInterval(()=>{},1000);
`;
const HELPER = `${FIXTURE_COMMON}note(process.argv[1],process.pid,own);`;
// The Engine's side of what dev-all relies on, as HeadlessApp does it: data in JUSTSEARCH_DATA_DIR
// and settings in JUSTSEARCH_HOME/ui/settings.json, each falling back to the user's own folder
// (%LOCALAPPDATA%\JustSearch) when unset; POST /api/lifecycle/shutdown runs the ordered close,
// which records the Engine's QUIT (engine-self-intent) in its exit ledger and exits 0; a console
// Ctrl+C runs the JVM's shutdown hook instead, which exits 130.
const ENGINE_FIXTURE = `const path=require('path'),os=require('os');
const event=row=>fs.appendFileSync(process.env.FIXTURE_LOG,JSON.stringify({role:'engine-event',pid:process.pid,...row})+'\\n');
const user=path.join(process.env.LOCALAPPDATA||os.homedir(),'JustSearch');
const dataDir=process.env.JUSTSEARCH_DATA_DIR||user,home=process.env.JUSTSEARCH_HOME||user;
const settings=path.join(home,'ui','settings.json');
event({kind:'paths',dataDir,home,settingsRead:fs.existsSync(settings)?settings:null});
fs.mkdirSync(path.join(dataDir,'index','default'),{recursive:true});fs.writeFileSync(path.join(dataDir,'index','default','segments_1'),'');
fs.mkdirSync(path.join(dataDir,'logs'),{recursive:true});
const engineLog=line=>fs.appendFileSync(path.join(dataDir,'logs','engine.log'),line+'\\n');engineLog('HeadlessApp started');
const ledger=require(path.join(__dirname,'scripts','dev','lib','exit-ledger.cjs'));
let closing=false;
const close=(why,code)=>{if(closing)return;closing=true;
const w=ledger.createLedgerWriter({ledgerDir:ledger.ledgerDirectory(dataDir),family:'engine',writer:{role:'engine',pid:process.pid,startedAt:Date.now()}});
w.startSession();w.append('engine-self-intent',{engine:{pid:process.pid,creationFileTime:null,instanceId:'fixture'},reason:'QUIT',detail:null,expectedCode:0});
event({kind:'close',why});setTimeout(()=>{engineLog('HeadlessApp stopped.');process.exit(code)},400)};
process.on('exit',code=>event({kind:'exit',code}));
process.on('SIGINT',()=>{event({kind:'sigint'});close('jvm-shutdown-hook',130)});
require('http').createServer((q,s)=>{
if(q.method==='POST'&&q.url==='/api/lifecycle/shutdown'){let body='';q.on('data',d=>body+=d);q.on('end',()=>{event({kind:'shutdown-post',body});s.statusCode=202;s.end('{"shutdownAccepted":true}');close('lifecycle-post',0)});return}
s.statusCode=q.url.startsWith('/api/status')?200:404;s.end('{}')}).listen(Number(process.env.JUSTSEARCH_API_PORT),'127.0.0.1');`;

function fixtureSources() {
  const vite = `${FIXTURE_COMMON}note('vite',process.pid,own);
// Vite starts helpers of its own (the esbuild service): a non-detached child here.
cp.spawn(process.execPath,['-e',${JSON.stringify(HELPER)},'vite-helper'],{stdio:'ignore',windowsHide:true});
if(process.env.FIXTURE_VITE_MODE==='exit-on-stop'){setInterval(()=>{if(fs.existsSync(process.env.FIXTURE_STOP))process.exit(0)},50)}
`;
  const backend = `${FIXTURE_COMMON}note('backend',process.pid,own);
// Gradle's application run, detached from the client as Gradle's own daemon is.
const app=cp.spawn(process.execPath,['-e',${JSON.stringify(HELPER)},'backend-app'],{stdio:'ignore',detached:true,windowsHide:true});app.unref();
if(process.env.FIXTURE_BACKEND_MODE==='engine'){${ENGINE_FIXTURE}}else{
require('http').createServer((q,s)=>{s.statusCode=q.url.startsWith('/api/status')?200:404;s.end('{}')}).listen(Number(process.env.JUSTSEARCH_API_PORT),'127.0.0.1');
}
`;
  return { vite, backend };
}

/** A scratch copy of the scripts under test, with fixture Vite and Gradle; plain files only. */
function fakeRepository(name) {
  const fake = path.join(root, name);
  for (const dir of ['scripts/dev/lib', 'scripts/lib', 'modules/ui-web/scripts']) {
    fs.cpSync(path.join(repo, dir), path.join(fake, dir), { recursive: true });
  }
  for (const file of ['scripts/dev/serve-worktree-fe.cjs', 'contracts/native-targets/spec.v1.json', 'governance/supervision-contract.v1.json']) {
    fs.mkdirSync(path.dirname(path.join(fake, file)), { recursive: true });
    fs.copyFileSync(path.join(repo, file), path.join(fake, file));
  }
  const ui = path.join(fake, 'modules/ui-web');
  const { vite, backend } = fixtureSources();
  fs.writeFileSync(path.join(ui, 'package.json'), JSON.stringify({ name: 'fixture-ui', private: true, scripts: { dev: 'vite' } }));
  fs.mkdirSync(path.join(ui, 'node_modules/vite/bin'), { recursive: true });
  fs.writeFileSync(path.join(ui, 'node_modules/vite/package.json'), JSON.stringify({ name: 'vite', version: '0.0.0-fixture', bin: { vite: 'bin/vite.js' } }));
  fs.writeFileSync(path.join(ui, 'node_modules/vite/bin/vite.js'), vite);
  fs.mkdirSync(path.join(ui, 'node_modules/.bin'), { recursive: true });
  fs.writeFileSync(path.join(ui, 'node_modules/.bin/vite.cmd'), `@echo off\r\n"${process.execPath}" "%~dp0..\\vite\\bin\\vite.js" %*\r\n`);
  fs.writeFileSync(path.join(fake, 'fixture-backend.js'), backend);
  fs.writeFileSync(path.join(fake, 'gradlew.bat'), `@echo off\r\n"${process.execPath}" "%~dp0fixture-backend.js" %*\r\n`);
  return fake;
}

function fixtureEnv(name, extra = {}) {
  const dir = path.join(root, `${name}-state`);
  fs.mkdirSync(dir, { recursive: true });
  return {
    dir,
    env: { ...process.env, NODE_OPTIONS: '', npm_config_offline: 'true', npm_config_update_notifier: 'false',
      FIXTURE_IDENTITY_EXE: identityExecutable(), FIXTURE_LOG: path.join(dir, 'fixture.log'),
      JUSTSEARCH_JOB_RECORD_DIR: path.join(dir, 'job-runs'), JUSTSEARCH_DEV_RUNNER_STATE_ROOT: path.join(dir, 'dev-runner'),
      ...extra },
  };
}

const readLog = file => (fs.existsSync(file) ? fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(Boolean).map(l => JSON.parse(l)) : []);

/** Union of every identity the run's job records name (root and members), read while the run lives. */
function snapshotRecords(dir, seen) {
  if (!fs.existsSync(dir)) return seen;
  for (const name of fs.readdirSync(dir)) {
    if (!name.endsWith('.json')) continue;
    try {
      const record = JSON.parse(fs.readFileSync(path.join(dir, name), 'utf8'));
      for (const p of [record.root, ...(record.members ?? [])]) if (p?.pid) seen.set(`${p.pid}:${p.creationFileTimeUtc}`, p);
    } catch { /* mid-replace: the next read sees it */ }
  }
  return seen;
}

/** Identities still running: the same PID with the same birth (microsecond), read in one lookup. */
async function stillRunning(identities, waitMs = 5000) {
  const end = Date.now() + waitMs;
  for (;;) {
    const rows = await actuator.lookupProcessIdentities(identities.map(p => p.pid));
    assert.ok(rows, 'the process table must be readable');
    const alive = identities.filter(p => {
      const row = rows.get(p.pid);
      return row?.present && row.creationFileTime && BigInt(row.creationFileTime) / 10n === BigInt(p.creationFileTimeUtc) / 10n;
    });
    if (!alive.length || Date.now() > end) return alive;
    await sleep(100);
  }
}

function startDecoy() {
  // Outside every run: the same image name (node.exe) as the run's processes.
  const decoy = spawn(process.execPath, ['-e', 'setTimeout(()=>{},180000)'], { stdio: 'ignore', windowsHide: true });
  decoys.push(decoy);
  return decoy;
}

function waitExit(child, ms) {
  if (child.exitCode !== null || child.signalCode !== null) return Promise.resolve(child.exitCode);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('harness deadline expired')), ms);
    child.once('exit', code => { clearTimeout(timer); resolve(code); });
  });
}

function freePort() {
  return new Promise(resolve => {
    const server = net.createServer().listen(0, '127.0.0.1', () => { const { port } = server.address(); server.close(() => resolve(port)); });
  });
}

function collect(child) {
  let text = '';
  child.stdout?.on('data', d => { text += d; });
  child.stderr?.on('data', d => { text += d; });
  return () => text;
}

test('serve-worktree-fe: a stop after a failed readiness gate ends Vite and everything Vite started', async () => {
  const fake = fakeRepository('serve');
  const { dir, env } = fixtureEnv('serve', { FIXTURE_VITE_MODE: 'never-listen' });
  const decoy = startDecoy();
  const serve = spawn(process.execPath, [path.join(fake, 'scripts/dev/serve-worktree-fe.cjs'), '--api-port', '1'],
    { cwd: fake, env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  const output = collect(serve);
  const members = new Map();
  const poll = setInterval(() => snapshotRecords(env.JUSTSEARCH_JOB_RECORD_DIR, members), 100);
  try {
    // The fixture never listens, so the real readiness gate (30 s) ends with the script's own stop.
    await waitExit(serve, 120000);
  } finally { clearInterval(poll); }
  const started = readLog(env.FIXTURE_LOG); logged.push(...started);
  assert.deepEqual(new Set(started.map(p => p.role)), new Set(['vite', 'vite-helper']),
    'the real script must have started the fixture Vite and its helper: ' + output());
  const survivors = await stillRunning([...started, ...members.values()]);
  assert.deepEqual(survivors, [], 'no process the run started may survive its stop: ' + JSON.stringify(survivors) + '\n' + output());
  assert.ok(members.size >= started.length, 'the run\'s job membership was recorded at start: ' + JSON.stringify([...members.values()]));
  assert.deepEqual(await stillRunning([decoy.recordedIdentity], 0), [decoy.recordedIdentity],
    'a process outside the run with the same image name is never touched');
  fs.rmSync(dir, { recursive: true, force: true });
});

test('dev-all: the ordered shutdown ends the backend, the Gradle-started application and Vite\'s helpers', async () => {
  const fake = fakeRepository('dev-all');
  const stop = path.join(root, 'dev-all-stop');
  const { dir, env } = fixtureEnv('dev-all', { FIXTURE_VITE_MODE: 'exit-on-stop', FIXTURE_STOP: stop,
    JUSTSEARCH_API_PORT: String(await freePort()), JUSTSEARCH_DATA_DIR: path.join(root, 'dev-all-data') });
  const decoy = startDecoy();
  const devAll = spawn(process.execPath, [path.join(fake, 'modules/ui-web/scripts/dev-all.cjs')],
    { cwd: path.join(fake, 'modules/ui-web'), env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  const output = collect(devAll);
  const members = new Map();
  const poll = setInterval(() => snapshotRecords(env.JUSTSEARCH_JOB_RECORD_DIR, members), 100);
  try {
    const end = Date.now() + 90000;
    const roles = () => new Set(readLog(env.FIXTURE_LOG).map(p => p.role));
    while (!['backend', 'backend-app', 'vite', 'vite-helper'].every(r => roles().has(r))) {
      if (Date.now() > end || devAll.exitCode !== null) assert.fail('the dev stack fixtures did not all start: ' + output());
      await sleep(100);
    }
    await sleep(300);
    snapshotRecords(env.JUSTSEARCH_JOB_RECORD_DIR, members);
    // Vite exits by itself: dev-all's own shutdown then stops the backend run.
    fs.writeFileSync(stop, '');
    await waitExit(devAll, 90000);
  } finally { clearInterval(poll); }
  const started = readLog(env.FIXTURE_LOG); logged.push(...started);
  const survivors = await stillRunning([...started, ...members.values()]);
  assert.deepEqual(survivors, [], 'no process the run started may survive its stop: ' + JSON.stringify(survivors) + '\n' + output());
  assert.ok(members.size >= started.length, 'the run\'s job membership was recorded at start: ' + JSON.stringify([...members.values()]));
  assert.deepEqual(await stillRunning([decoy.recordedIdentity], 0), [decoy.recordedIdentity],
    'a process outside the run with the same image name is never touched');
  fs.rmSync(dir, { recursive: true, force: true });
});

// A real console Ctrl+C. The command runs in its own hidden console under a launcher that turns
// Ctrl+C processing back on for it (a parent here may have started with Ctrl+C ignored, which every
// child would inherit) and ignores Ctrl+C itself; the sender attaches to that console and generates
// CTRL_C_EVENT for every process attached to it, exactly as pressing Ctrl+C there does.
const CONSOLE_LAUNCHER = `param([string]$Spec)
$s = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Spec)) | ConvertFrom-Json
Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class FixtureConsole { [DllImport("kernel32.dll")] public static extern bool SetConsoleCtrlHandler(IntPtr h, bool add); }'
[void][FixtureConsole]::SetConsoleCtrlHandler([IntPtr]::Zero, $false)
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $s.command; $psi.Arguments = $s.arguments; $psi.WorkingDirectory = $s.cwd
$psi.UseShellExecute = $false; $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
$p = [System.Diagnostics.Process]::Start($psi)
[void][FixtureConsole]::SetConsoleCtrlHandler([IntPtr]::Zero, $true)
$out = [IO.File]::Open($s.log, 'Create', 'Write', 'ReadWrite'); $err = [IO.File]::Open($s.log + '.err', 'Create', 'Write', 'ReadWrite')
$copies = @($p.StandardOutput.BaseStream.CopyToAsync($out), $p.StandardError.BaseStream.CopyToAsync($err))
Set-Content -LiteralPath $s.pidFile -Value $p.Id
$p.WaitForExit(); [void][Threading.Tasks.Task]::WaitAll($copies, 5000); $out.Close(); $err.Close()
Set-Content -LiteralPath $s.exitFile -Value $p.ExitCode
`;
const CTRL_C_SENDER = `param([int]$Target)
Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class FixtureCtrlC {
 [DllImport("kernel32.dll")] static extern bool FreeConsole();
 [DllImport("kernel32.dll", SetLastError = true)] static extern bool AttachConsole(uint pid);
 [DllImport("kernel32.dll")] static extern bool SetConsoleCtrlHandler(IntPtr h, bool add);
 [DllImport("kernel32.dll", SetLastError = true)] static extern bool GenerateConsoleCtrlEvent(uint ev, uint group);
 public static string Send(uint pid) {
  FreeConsole();
  if (!AttachConsole(pid)) return "attach failed: " + Marshal.GetLastWin32Error();
  SetConsoleCtrlHandler(IntPtr.Zero, true);
  bool sent = GenerateConsoleCtrlEvent(0, 0); int error = Marshal.GetLastWin32Error();
  System.Threading.Thread.Sleep(300); FreeConsole();
  return sent ? "sent" : "generate failed: " + error;
 } }'
[Console]::Out.WriteLine([FixtureCtrlC]::Send([uint32]$Target))
`;

/** Starts `node <args>` in its own hidden console; returns its identity, exit code and output. */
async function startInConsole(name, args, { cwd, env }) {
  const dir = path.join(root, `${name}-console`); fs.mkdirSync(dir, { recursive: true });
  const launcher = path.join(dir, 'launcher.ps1'); fs.writeFileSync(launcher, CONSOLE_LAUNCHER);
  const files = { log: path.join(dir, 'output.log'), pidFile: path.join(dir, 'child.pid'), exitFile: path.join(dir, 'exit.code') };
  const spec = Buffer.from(JSON.stringify({ command: process.execPath, cwd, ...files,
    arguments: args.map(a => `"${a}"`).join(' ') }), 'utf8').toString('base64');
  const started = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
    `(Start-Process -FilePath powershell.exe -WindowStyle Hidden -PassThru -ArgumentList '-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File','"${launcher}"','-Spec','${spec}').Id`],
  { encoding: 'utf8', windowsHide: true, env, timeout: 60000 });
  const launcherPid = Number(started.stdout.trim());
  assert.ok(launcherPid > 0, 'console launcher did not start: ' + started.stderr);
  const launcherBirth = spawnSync(identityExecutable(), [String(launcherPid)], { encoding: 'utf8', windowsHide: true }).stdout.trim();
  if (/^\d+$/.test(launcherBirth)) logged.push({ role: 'console-launcher', pid: launcherPid, creationFileTimeUtc: launcherBirth });
  let pid = 0;
  for (const end = Date.now() + 60000; !pid && Date.now() < end;) {
    try { pid = Number(fs.readFileSync(files.pidFile, 'utf8').trim()); } catch { await sleep(50); }
  }
  assert.ok(pid > 0, 'the console command did not start');
  const birth = spawnSync(identityExecutable(), [String(pid)], { encoding: 'utf8', windowsHide: true }).stdout.trim();
  const identity = { role: name, pid, creationFileTimeUtc: birth };
  if (/^\d+$/.test(birth)) logged.push(identity);
  return {
    identity,
    output: () => ['', '.err'].map(s => { try { return fs.readFileSync(files.log + s, 'utf8'); } catch { return ''; } }).join('\n'),
    exitCode: () => { try { return Number(fs.readFileSync(files.exitFile, 'utf8').trim()); } catch { return null; } },
    ctrlC() {
      const sender = path.join(dir, 'ctrl-c.ps1'); fs.writeFileSync(sender, CTRL_C_SENDER);
      const r = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', sender,
        '-Target', String(pid)], { encoding: 'utf8', windowsHide: true, timeout: 60000 });
      assert.equal(r.stdout.trim(), 'sent', 'Ctrl+C could not be sent: ' + r.stdout + r.stderr);
    },
    async waitExit(ms) {
      for (const end = Date.now() + ms; Date.now() < end;) { if (this.exitCode() !== null) return this.exitCode(); await sleep(100); }
      assert.fail('the console command did not exit: ' + this.output());
    },
  };
}

const engineEvents = file => readLog(file).filter(e => e.role === 'engine-event');
const jsonFiles = dir => (fs.existsSync(dir) ? fs.readdirSync(dir).filter(n => n.endsWith('.json')) : []);
function ledgerRecords(dataDir) {
  const file = path.join(dataDir, 'runtime', 'exit-ledger', 'engine.v1.jsonl');
  return fs.existsSync(file) ? fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(Boolean).map(l => JSON.parse(l)) : [];
}

/** Waits until the fake dev-all stack has started every fixture. */
async function waitForStack(env, alive, output) {
  const end = Date.now() + 90000;
  const roles = () => new Set(readLog(env.FIXTURE_LOG).map(p => p.role));
  while (!['backend', 'backend-app', 'vite', 'vite-helper'].every(r => roles().has(r))
      || !engineEvents(env.FIXTURE_LOG).some(e => e.kind === 'paths')) {
    if (Date.now() > end || !alive()) assert.fail('the dev stack fixtures did not all start: ' + output());
    await sleep(100);
  }
  await sleep(300);
}

/** The Engine was asked to quit and quit in order: its QUIT recorded, its own exit 0, no Ctrl+C. */
function assertOrderedQuit(env, dataDir, output) {
  const events = engineEvents(env.FIXTURE_LOG);
  const post = events.find(e => e.kind === 'shutdown-post');
  assert.ok(post, 'the stop must ask the Engine to quit through POST /api/lifecycle/shutdown: ' + JSON.stringify(events) + '\n' + output());
  const body = JSON.parse(post.body || '{}');
  assert.equal(body.reason, 'quit', 'the quit names its reason');
  assert.ok(Number.isInteger(body.deadlineAtEpochMs), 'the quit names its deadline');
  const quit = ledgerRecords(dataDir).filter(r => r.type === 'engine-self-intent' && r.reason === 'QUIT');
  assert.equal(quit.length, 1, 'the Engine records one intended QUIT in its exit ledger: ' + JSON.stringify(ledgerRecords(dataDir)));
  assert.deepEqual(events.filter(e => e.kind === 'exit').map(e => e.code), [0],
    'the Engine exits by itself with 0 after its ordered close, not ended by the job: ' + JSON.stringify(events) + '\n' + output());
  assert.equal(events.some(e => e.kind === 'sigint'), false, 'the console Ctrl+C is the stop\'s to handle, not the Engine\'s');
  assert.match(fs.readFileSync(path.join(dataDir, 'logs', 'engine.log'), 'utf8'), /HeadlessApp stopped\./, 'the ordered close completed');
}

test('dev-all: a stop asks the Engine to quit first; it records its QUIT, exits 0, and nothing the run started survives', async () => {
  const fake = fakeRepository('dev-all-quit');
  const stop = path.join(root, 'dev-all-quit-stop');
  const { dir, env } = fixtureEnv('dev-all-quit', { FIXTURE_VITE_MODE: 'exit-on-stop', FIXTURE_STOP: stop,
    FIXTURE_BACKEND_MODE: 'engine', JUSTSEARCH_API_PORT: String(await freePort()) });
  const dataDir = path.join(fake, 'modules/ui-web/.dev-data');
  const decoy = startDecoy();
  const devAll = spawn(process.execPath, [path.join(fake, 'modules/ui-web/scripts/dev-all.cjs')],
    { cwd: path.join(fake, 'modules/ui-web'), env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  const output = collect(devAll);
  const members = new Map();
  const poll = setInterval(() => snapshotRecords(env.JUSTSEARCH_JOB_RECORD_DIR, members), 100);
  let code;
  try {
    await waitForStack(env, () => devAll.exitCode === null, output);
    snapshotRecords(env.JUSTSEARCH_JOB_RECORD_DIR, members);
    // Vite exits by itself: dev-all's own stop then runs.
    fs.writeFileSync(stop, '');
    code = await waitExit(devAll, 90000);
  } finally { clearInterval(poll); }
  const started = readLog(env.FIXTURE_LOG).filter(p => p.role !== 'engine-event'); logged.push(...started);
  assertOrderedQuit(env, dataDir, output);
  const survivors = await stillRunning([...started, ...members.values()]);
  assert.deepEqual(survivors, [], 'no process the run started may survive its stop: ' + JSON.stringify(survivors) + '\n' + output());
  assert.equal(code, 0, 'a clean stop exits 0: ' + output());
  assert.deepEqual(jsonFiles(env.JUSTSEARCH_JOB_RECORD_DIR), [], 'a clean stop leaves no run record');
  assert.deepEqual(await stillRunning([decoy.recordedIdentity], 0), [decoy.recordedIdentity],
    'a process outside the run with the same image name is never touched');
  fs.rmSync(dir, { recursive: true, force: true });
});

test('dev-all: a console Ctrl+C asks the Engine to quit; it records its QUIT, exits 0, and the stop leaves no process or run record', async () => {
  const fake = fakeRepository('dev-all-ctrl-c');
  const { dir, env } = fixtureEnv('dev-all-ctrl-c', { FIXTURE_BACKEND_MODE: 'engine', JUSTSEARCH_API_PORT: String(await freePort()) });
  const dataDir = path.join(fake, 'modules/ui-web/.dev-data');
  const decoy = startDecoy();
  const members = new Map();
  const devAll = await startInConsole('dev-all', [path.join(fake, 'modules/ui-web/scripts/dev-all.cjs')],
    { cwd: path.join(fake, 'modules/ui-web'), env });
  const poll = setInterval(() => snapshotRecords(env.JUSTSEARCH_JOB_RECORD_DIR, members), 100);
  let code;
  try {
    await waitForStack(env, () => devAll.exitCode() === null, devAll.output);
    snapshotRecords(env.JUSTSEARCH_JOB_RECORD_DIR, members);
    devAll.ctrlC();
    code = await devAll.waitExit(90000);
  } finally { clearInterval(poll); }
  const started = readLog(env.FIXTURE_LOG).filter(p => p.role !== 'engine-event'); logged.push(...started);
  assertOrderedQuit(env, dataDir, devAll.output);
  const survivors = await stillRunning([...started, ...members.values(), devAll.identity]);
  assert.deepEqual(survivors, [], 'no process the run started may survive Ctrl+C: ' + JSON.stringify(survivors) + '\n' + devAll.output());
  assert.equal(code, 0, 'a clean Ctrl+C exits 0: ' + devAll.output());
  assert.deepEqual(jsonFiles(env.JUSTSEARCH_JOB_RECORD_DIR), [], 'a clean Ctrl+C leaves no run record');
  assert.deepEqual(await stillRunning([decoy.recordedIdentity], 0), [decoy.recordedIdentity],
    'a process outside the run with the same image name is never touched');
  fs.rmSync(dir, { recursive: true, force: true });
});

test('dev-all with no options: the Engine reads no user settings and writes only in its own dev data folder', async () => {
  const fake = fakeRepository('dev-all-isolated');
  const stop = path.join(root, 'dev-all-isolated-stop');
  // A person's own profile and data, as the Engine would find them without isolation; an inherited
  // JUSTSEARCH_DATA_DIR (from a shell profile) is not an explicit choice of data folder.
  const profile = path.join(root, 'isolated-profile');
  const userSettings = path.join(profile, 'Local', 'JustSearch', 'ui', 'settings.json');
  const userData = path.join(root, 'isolated-user-data');
  fs.mkdirSync(path.dirname(userSettings), { recursive: true }); fs.writeFileSync(userSettings, '{"schemaVersion":5}');
  fs.mkdirSync(userData, { recursive: true });
  const listing = d => (fs.existsSync(d) ? fs.readdirSync(d, { recursive: true }).map(String).sort() : []);
  // JustSearch's own folders in the profile; the tools around it (PowerShell, npm) keep their
  // caches in a profile wherever it points, which is theirs and not the Engine's.
  const owned = () => ({ profile: listing(profile).filter(n => /JustSearch/i.test(n)), userData: listing(userData) });
  const before = owned();
  const { dir, env } = fixtureEnv('dev-all-isolated', { FIXTURE_VITE_MODE: 'exit-on-stop', FIXTURE_STOP: stop,
    FIXTURE_BACKEND_MODE: 'engine', JUSTSEARCH_API_PORT: String(await freePort()),
    LOCALAPPDATA: path.join(profile, 'Local'), APPDATA: path.join(profile, 'Roaming'), USERPROFILE: profile, HOME: profile,
    JUSTSEARCH_DATA_DIR: userData, JUSTSEARCH_HOME: path.join(profile, 'Local', 'JustSearch') });
  const devData = path.join(fake, 'modules/ui-web/.dev-data');
  const devAll = spawn(process.execPath, [path.join(fake, 'modules/ui-web/scripts/dev-all.cjs')],
    { cwd: path.join(fake, 'modules/ui-web'), env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  const output = collect(devAll);
  try {
    await waitForStack(env, () => devAll.exitCode === null, output);
    fs.writeFileSync(stop, '');
    await waitExit(devAll, 90000);
  } finally {
    logged.push(...readLog(env.FIXTURE_LOG).filter(p => p.role !== 'engine-event'));
  }
  const paths = engineEvents(env.FIXTURE_LOG).find(e => e.kind === 'paths');
  assert.equal(path.resolve(paths.dataDir), path.resolve(devData), 'the Engine\'s data folder is the dev data folder: ' + JSON.stringify(paths));
  assert.equal(path.resolve(paths.home), path.resolve(devData), 'the Engine\'s home is the dev data folder: ' + JSON.stringify(paths));
  assert.equal(paths.settingsRead, null, 'no settings outside the dev data folder are read: ' + JSON.stringify(paths));
  assert.deepEqual(owned(), before, 'nothing is written to the person\'s JustSearch folders or data folder');
  assert.ok(fs.existsSync(path.join(devData, 'index', 'default', 'segments_1')), 'the Engine wrote its index in the dev data folder');
  fs.rmSync(dir, { recursive: true, force: true });
});

test('serve-worktree-fe: a console Ctrl+C ends Vite and its helper and leaves no run record', async () => {
  const fake = fakeRepository('serve-ctrl-c');
  const { dir, env } = fixtureEnv('serve-ctrl-c', { FIXTURE_VITE_MODE: 'never-listen' });
  const decoy = startDecoy();
  const members = new Map();
  const serve = await startInConsole('serve', [path.join(fake, 'scripts/dev/serve-worktree-fe.cjs'), '--api-port', '1'],
    { cwd: fake, env });
  const poll = setInterval(() => snapshotRecords(env.JUSTSEARCH_JOB_RECORD_DIR, members), 100);
  let code;
  try {
    const end = Date.now() + 25000;
    while (!['vite', 'vite-helper'].every(r => readLog(env.FIXTURE_LOG).some(p => p.role === r))) {
      if (Date.now() > end || serve.exitCode() !== null) assert.fail('the fixture Vite did not start: ' + serve.output());
      await sleep(100);
    }
    await sleep(300);
    snapshotRecords(env.JUSTSEARCH_JOB_RECORD_DIR, members);
    // Within the readiness gate's 30 s, so this stop is the Ctrl+C's and not the gate's.
    serve.ctrlC();
    code = await serve.waitExit(60000);
  } finally { clearInterval(poll); }
  const started = readLog(env.FIXTURE_LOG); logged.push(...started);
  const survivors = await stillRunning([...started, ...members.values(), serve.identity]);
  assert.deepEqual(survivors, [], 'no process the run started may survive Ctrl+C: ' + JSON.stringify(survivors) + '\n' + serve.output());
  assert.ok(members.size >= started.length, 'the run\'s job membership was recorded at start');
  assert.equal(code, 0, 'a clean Ctrl+C exits 0: ' + serve.output());
  assert.deepEqual(jsonFiles(env.JUSTSEARCH_JOB_RECORD_DIR), [], 'a clean Ctrl+C leaves no run record');
  assert.deepEqual(await stillRunning([decoy.recordedIdentity], 0), [decoy.recordedIdentity],
    'a process outside the run with the same image name is never touched');
  fs.rmSync(dir, { recursive: true, force: true });
});

test('a run stop never touches a process outside the run: a recycled-looking PID or the same image name', async () => {
  const stopper = path.join(repo, 'scripts/dev/lib/stop-job-run.ps1');
  assert.ok(fs.existsSync(stopper), 'the run stop must exist: scripts/dev/lib/stop-job-run.ps1');
  const jobRun = require('./lib/job-run.cjs');
  const decoy = startDecoy();
  const identity = decoy.recordedIdentity;
  // The decoy's live PID under a birth one second earlier: what a recycled PID looks like to a record.
  const stale = String(BigInt(identity.creationFileTimeUtc) - 10_000_000n);
  const dir = path.join(root, 'recycled'); fs.mkdirSync(dir, { recursive: true });
  const recordPath = path.join(dir, 'run.json');
  fs.writeFileSync(recordPath, JSON.stringify({ schema: 'justsearch-job-run.v1', pid: decoy.pid, creationFileTimeUtc: stale,
    root: { pid: decoy.pid, creationFileTimeUtc: stale }, members: [{ pid: decoy.pid, creationFileTimeUtc: stale }], state: 'running' }));
  const recycled = jobRun.stopJobRunSync({ recordPath, host: { pid: decoy.pid, creationFileTimeUtc: stale } }, { waitMs: 1000 });
  assert.equal(recycled.outcome, 'gone', 'a recorded PID that now shows another birth is not the run: ' + JSON.stringify(recycled));
  assert.deepEqual(await stillRunning([identity], 0), [identity], 'the process now holding that PID is never touched');

  // A genuine run of the same image name, stopped while the decoy runs.
  const run = jobRun.startInJob({ command: process.execPath, args: ['-e', 'setTimeout(()=>{},180000)'], stdio: 'ignore',
    env: { ...process.env, JUSTSEARCH_JOB_RECORD_DIR: dir }, label: 'decoy-check' });
  let record;
  for (const end = Date.now() + 30000; !(record = jobRun.readRunRecord(run)) && Date.now() < end;) await sleep(50);
  assert.ok(record?.root?.pid, 'the run records its root');
  const stopped = jobRun.stopJobRunSync(run);
  assert.equal(stopped.ok, true, JSON.stringify(stopped));
  assert.deepEqual(await stillRunning([record.root]), [], 'the run\'s own node.exe has ended');
  assert.deepEqual(await stillRunning([identity], 0), [identity], 'the decoy node.exe outside the run is never touched');
});

let failed = 0;
try {
  for (const [name, fn] of tests) {
    try { await fn(); console.log(`PASS ${name}`); }
    catch (error) { failed++; console.error(`FAIL ${name}: ${error.stack}`); }
  }
} finally {
  // Only identities this harness or its fixtures recorded at start, each by its own birth.
  for (const p of logged) spawnSync(identityExecutable(), [String(p.pid), p.creationFileTimeUtc], { windowsHide: true });
  for (const decoy of decoys) if (decoy.exitCode === null) decoy.kill();
  fs.rmSync(root, { recursive: true, force: true });
}
console.log(`job-stops: ${tests.length - failed}/${tests.length} passed`);
process.exitCode = failed ? 1 : 0;
