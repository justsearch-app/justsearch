// Run separately: node scripts/dev/check-dev-runner-cli-ownership.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { root, repo, envFor, cli, check, finish } from './owner-liveness-check-support.mjs';
const require = createRequire(import.meta.url), identity = require('./lib/process-identity.cjs');
const { buildHandoffEnv } = require('./lib/agent-identity.cjs');
const state = root(), children = [], output = new Map();
fs.mkdirSync(path.join(repo, 'tmp'), { recursive: true });
const dataRoot = fs.mkdtempSync(path.join(repo, 'tmp', 'owner-cli-data-'));
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(fn, label, timeout = 20000) {
  const end = Date.now() + timeout;
  for (;;) { const result = fn(); if (result) return result; assert.ok(Date.now() < end, label); await sleep(50); }
}
function startChild(args, env) {
  const child = spawn(process.execPath, args, { cwd: repo, env, stdio: ['ignore', 'pipe', 'pipe'] });
  children.push(child); output.set(child, '');
  child.on('error', e => output.set(child, output.get(child) + e.message));
  for (const stream of [child.stdout, child.stderr]) stream.on('data', b => output.set(child, output.get(child) + b));
  return child;
}
function owner(name) {
  const child = startChild(['-e', 'setInterval(()=>{},60000)'], envFor(state));
  const seen = identity.readLinuxProcess(child.pid);
  assert.ok(seen?.processIdentity, 'fixture owner birth must be readable');
  return { child, value: { harness: 'codex', owner: { key: `check-owner-${name}`, pid: child.pid,
    processIdentity: seen.processIdentity }, sessionId: `check-owner-${name}`, source: 'fixture' } };
}
let fixtureEnv;
try {
  await check('S16 real Linux CLI start status stop conflict and dead owner reclaim', async () => {
    assert.equal(process.platform, 'linux', 'this check requires Linux');
    const a = owner('a'), b = owner('b');
    const plan = path.join(state, 'plan.json'); fs.writeFileSync(plan, JSON.stringify({ incarnations: [{ mode: 'honour' }] }));
    fixtureEnv = { ...envFor(state), JUSTSEARCH_DATA_DIR: dataRoot, JUSTSEARCH_SUPERVISOR_HARNESS: '1',
      JUSTSEARCH_DEV_RUNNER_ENGINE_COMMAND: JSON.stringify([process.execPath, path.join(repo, 'scripts/supervisor-conformance/fake-engine.mjs')]),
      JUSTSEARCH_DEV_RUNNER_FRONTEND_COMMAND: JSON.stringify([process.execPath, '-e', 'setInterval(()=>{},60000)']),
      JUSTSEARCH_FAKE_ENGINE_PLAN: plan, JUSTSEARCH_SUPERVISOR_STABILITY_WINDOW_MS: '600000',
      JUSTSEARCH_DEV_REAPER_GRACE_MS: '600000', JUSTSEARCH_DEV_LEASE_RENEW_MS: '300' };
    const uiPort = await new Promise((resolve, reject) => {
      const server = net.createServer(); server.once('error', reject);
      server.listen(0, '127.0.0.1', () => { const port = server.address().port; server.close(() => resolve(port)); });
    });
    const args = ['start', '--skip-build', '--clean', 'none', '--api-port', '0', '--ui-port', String(uiPort),
      '--data-dir', dataRoot, '--json'];
    const launch = who => startChild([path.join(repo, 'scripts/dev/dev-runner.cjs'), ...args],
      { ...fixtureEnv, ...buildHandoffEnv(who.value) });
    const ready = async child => until(() => {
      assert.ok(child.exitCode === null && child.signalCode === null, `start exited: ${output.get(child)}`);
      try {
        const active = JSON.parse(fs.readFileSync(path.join(state, 'active.json')));
        const run = JSON.parse(fs.readFileSync(path.join(state, 'runs', active.runId, 'run.json')));
        return run.pids?.frontendRootPid && run.apiPortActual > 0 ? run : null;
      } catch { return null; }
    }, `fixture never became ready: ${output.get(child)}`);
    const first = launch(a), run = await ready(first);
    const status = cli(state, ['status'], { env: { ...fixtureEnv, ...buildHandoffEnv(a.value) } });
    assert.equal(status.status, 0); assert.equal(status.json.runId, run.runId); assert.equal(status.json.alive.runner, true);
    const conflict = cli(state, args, { env: { ...fixtureEnv, ...buildHandoffEnv(b.value) } });
    assert.equal(conflict.json.error?.code, 'OWNER_CONFLICT', conflict.stdout + conflict.stderr);
    const stopped = cli(state, ['stop'], { env: { ...fixtureEnv, ...buildHandoffEnv(a.value) } });
    assert.equal(stopped.json.outcome, 'STOPPED', stopped.stdout + stopped.stderr);
    await until(() => first.exitCode !== null || first.signalCode !== null, 'first runner must exit');
    const second = launch(a), run2 = await ready(second);
    const ended = new Promise(resolve => a.child.once('exit', resolve)); a.child.kill(); await ended;
    const reclaimed = startChild([path.join(repo, 'scripts/dev/dev-runner.cjs'), ...args, '--takeover', 'warn'],
      { ...fixtureEnv, ...buildHandoffEnv(b.value) });
    await until(() => {
      try { return JSON.parse(fs.readFileSync(path.join(state, 'active.json'))).holder?.owner?.key === b.value.owner.key; }
      catch { return false; }
    }, `dead owner not reclaimed; previous run ${run2.runId}; ${output.get(reclaimed)}`);
    await ready(reclaimed);
    const final = cli(state, ['stop'], { env: { ...fixtureEnv, ...buildHandoffEnv(b.value) } });
    assert.equal(final.json.outcome, 'STOPPED', final.stdout + final.stderr);
  });
} finally {
  if (fs.existsSync(path.join(state, 'active.json'))) {
    const stop = cli(state, ['stop', '--force'], { env: fixtureEnv });
    if (stop.json.outcome !== 'STOPPED') console.error(`CLEANUP stop: ${stop.stdout} ${stop.stderr}`);
  }
  for (const child of children) {
    if (child.exitCode !== null || child.signalCode !== null) continue;
    const end = new Promise(resolve => child.once('exit', resolve)); child.kill('SIGKILL'); await end;
  }
  fs.rmSync(state, { recursive: true, force: true });
  fs.rmSync(dataRoot, { recursive: true, force: true });
}
finish();
