// Round-4 repairs at the real CLI and injected stop boundary. CPU-only fixture processes.
import './lib/test-fixture-processes.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url), repo = path.resolve(import.meta.dirname, '../..');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'stop-round4-'));
process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = root;
const api = require('./dev-runner.cjs').__test;
const owner = api.holderBlockFor(api.resolveCallerIdentity('round4-owner'));
const tests = [], children = [], test = (name, fn) => tests.push([name, fn]);
const read = p => JSON.parse(fs.readFileSync(p, 'utf8'));
function forgotten(id, leftBehind) {
  fs.mkdirSync(path.join(repo, 'tmp'), { recursive: true });
  const data = fs.mkdtempSync(path.join(repo, 'tmp', 'round4-forgotten-'));
  const sentinel = path.join(data, 'derived-in-use'); fs.writeFileSync(sentinel, 'preserve');
  const dir = path.join(root, 'runs', id); fs.mkdirSync(dir, { recursive: true });
  const run = { runId: id, holder: owner, pids: {}, identities: {}, dataDir: data };
  fs.writeFileSync(path.join(dir, 'run.json'), JSON.stringify(run));
  fs.writeFileSync(path.join(dir, 'forgotten-unresolved.json'), JSON.stringify({ leftBehind }));
  return { dir, data, sentinel, run };
}
function cli(args) {
  const r = spawnSync(process.execPath, [path.join(repo, 'scripts/dev/dev-runner.cjs'), ...args, '--json', '--session-id', 'round4-owner'],
    { cwd: repo, env: process.env, windowsHide: true, encoding: 'utf8' });
  return { ...JSON.parse(r.stdout.trim().split(/\r?\n/).at(-1)), status: r.status };
}
test('forgotten cleanup names each living or unknown process and keeps data', () => {
  const child = spawn(process.execPath, ['-e', 'setInterval(()=>{},60000)'], { stdio: 'ignore', windowsHide: true }); children.push(child);
  const t = forgotten('live', [{ ...child.recordedIdentity, role: 'backend', name: 'fixture-Engine' }]);
  try {
    const out = cli(['cleanup', '--run', 'live', '--clean', 'hard']);
    assert.equal(out.cleaned, false); assert.equal(out.status, 1);
    assert.match(out.error.message, new RegExp(`PID ${child.pid}`), 'cleanup names the process blocking cleanup');
    assert.match(out.error.message, /alive|running|unverifiable|unknown/);
    assert.equal(fs.readFileSync(t.sentinel, 'utf8'), 'preserve');
  } finally { fs.rmSync(t.data, { recursive: true, force: true }); }
});
test('forgotten cleanup succeeds after every recorded identity ends', async () => {
  const child = spawn(process.execPath, ['-e', 'setInterval(()=>{},60000)'], { stdio: 'ignore', windowsHide: true }); children.push(child);
  const t = forgotten('ended', [{ ...child.recordedIdentity, role: 'backend', name: 'fixture-Engine' }]);
  child.kill(); await new Promise(resolve => child.once('exit', resolve));
  try {
    const out = cli(['cleanup', '--run', 'ended', '--clean', 'hard']);
    assert.equal(out.status, 0, 'gone forgotten identities must allow cleanup');
    assert.equal(out.ok, true); assert.equal(fs.existsSync(t.sentinel), false);
  } finally { fs.rmSync(t.data, { recursive: true, force: true }); }
});
test('forgotten cleanup names an unverifiable living identity and keeps data', () => {
  const child = spawn(process.execPath, ['-e', 'setInterval(()=>{},60000)'], { stdio: 'ignore', windowsHide: true }); children.push(child);
  const t = forgotten('unknown', [{ pid: child.pid, creationFileTimeUtc: null, role: 'backend', name: 'fixture-Engine' }]);
  try {
    const out = cli(['cleanup', '--run', 'unknown', '--clean', 'hard']);
    assert.equal(out.status, 1); assert.equal(out.cleaned, false);
    assert.match(out.error.message, new RegExp(`PID ${child.pid}`));
    assert.match(out.error.message, /unknown|unverifiable/i);
    assert.equal(fs.readFileSync(t.sentinel, 'utf8'), 'preserve');
  } finally { fs.rmSync(t.data, { recursive: true, force: true }); }
});
test('plain stop of a forgotten run says nothing stopped and fails', () => {
  const t = forgotten('plain-stop', []);
  try {
    const out = cli(['stop', '--run', 'plain-stop']);
    assert.equal(out.status, 1, 'forgotten stop is not a successful stop');
    assert.equal(out.ok, false); assert.deepEqual(out.killedPids, []);
    assert.match(out.error?.message ?? out.note, /stopped nothing.*forgotten/i);
  } finally { fs.rmSync(t.data, { recursive: true, force: true }); }
});
test('exit census starts before the initial tree inspection, while stop awaits its arming', async () => {
  const dir = path.join(root, 'ordering'); fs.mkdirSync(dir); const runPath = path.join(dir, 'run.json');
  const run = { runId: 'ordering', pids: {}, identities: {} }; fs.writeFileSync(runPath, JSON.stringify(run));
  const events = [];
  const out = await api.stopRun({}, { resolveTarget: async () => ({ run, runPath }),
    census: async () => { events.push('census'); return { finish: async () => ({}) }; },
    inspectTrees: async () => { events.push('inspect'); return { complete: true, processes: [] }; },
    lookupIdentities: async () => new Map(), portOwner: async () => ({ closed: true }), portWaitMs: 0 });
  assert.equal(out.outcome, 'STOPPED'); assert.deepEqual(events.slice(0,2), ['census', 'inspect'], 'observer startup overlaps the native inspection');
});
test('round3 check loads without NODE_OPTIONS or an external binary', () => {
  const env = { ...process.env }; delete env.NODE_OPTIONS;
  const r = spawnSync(process.execPath, [path.join(repo, 'scripts/dev/test-dev-runner-stop-round3.mjs')],
    { cwd: repo, env, encoding: 'utf8', windowsHide: true });
  assert.equal(r.status, 0, 'plain round3: ' + r.stdout + r.stderr);
});
test('recovery check passes without NODE_OPTIONS or an external binary', () => {
  const env = { ...process.env }; delete env.NODE_OPTIONS;
  const r = spawnSync(process.execPath, [path.join(repo, 'scripts/dev/test-dev-runner-stop-recovery.mjs')],
    { cwd: repo, env, encoding: 'utf8', windowsHide: true });
  assert.equal(r.status, 0, 'plain recovery: ' + r.stdout + r.stderr);
});
test('one read-only inspection host supplies fresh identities across stop phases', async () => {
  const actor = require('./lib/engine-actuator.cjs');
  assert.equal(typeof actor.createTreeInspectionSession, 'function', 'reuse the native inspection host across stop phases');
  const child = spawn(process.execPath, ['-e', 'setInterval(()=>{},60000)'], { stdio: 'ignore', windowsHide: true }); children.push(child);
  const session = actor.createTreeInspectionSession();
  try {
    const first = await session.inspect([child.recordedIdentity]);
    assert.equal(first.complete, true); assert.equal(first.identities.get(child.pid).present, true);
    child.kill(); await new Promise(resolve => child.once('exit', resolve));
    const second = await session.inspect([child.recordedIdentity]);
    assert.equal(second.complete, true); assert.equal(second.identities.get(child.pid).present, false);
  } finally { await session.close(); }
});
let failed = 0;
try { for (const [name, fn] of tests) { try { await fn(); console.log('PASS ' + name); }
  catch (e) { failed++; console.error('FAIL ' + name + ': ' + e.stack); } } }
finally { for (const child of children) child.kill(); fs.rmSync(root, { recursive: true, force: true }); }
console.log(`stop-round4: ${tests.length - failed}/${tests.length} passed`); process.exitCode = failed ? 1 : 0;
