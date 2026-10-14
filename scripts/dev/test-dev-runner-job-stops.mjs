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

function fixtureSources() {
  const vite = `${FIXTURE_COMMON}note('vite',process.pid,own);
// Vite starts helpers of its own (the esbuild service): a non-detached child here.
cp.spawn(process.execPath,['-e',${JSON.stringify(HELPER)},'vite-helper'],{stdio:'ignore',windowsHide:true});
if(process.env.FIXTURE_VITE_MODE==='exit-on-stop'){setInterval(()=>{if(fs.existsSync(process.env.FIXTURE_STOP))process.exit(0)},50)}
`;
  const backend = `${FIXTURE_COMMON}note('backend',process.pid,own);
// Gradle's application run, detached from the client as Gradle's own daemon is.
const app=cp.spawn(process.execPath,['-e',${JSON.stringify(HELPER)},'backend-app'],{stdio:'ignore',detached:true,windowsHide:true});app.unref();
require('http').createServer((q,s)=>{s.statusCode=q.url.startsWith('/api/status')?200:404;s.end('{}')}).listen(Number(process.env.JUSTSEARCH_API_PORT),'127.0.0.1');
`;
  return { vite, backend };
}

/** A scratch copy of the scripts under test, with fixture Vite and Gradle; plain files only. */
function fakeRepository(name) {
  const fake = path.join(root, name);
  for (const dir of ['scripts/dev/lib', 'modules/ui-web/scripts']) {
    fs.cpSync(path.join(repo, dir), path.join(fake, dir), { recursive: true });
  }
  for (const file of ['scripts/dev/serve-worktree-fe.cjs', 'governance/supervision-contract.v1.json']) {
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
