#!/usr/bin/env node
/**
 * Spec F steps 7 and 8, dev half: the one quit (its deadline from the contract, the request file and
 * the lifecycle POST addressed to one incarnation, Spec F §4.3) and suspended time that is not a hang
 * (the tick-gap `SuspendAwareClock` and the resume grace, §3.5).
 *
 * The loop cases drive the real `createSupervisionLoop` over a fake actuator and a fake clock, so a
 * 60 s suspend takes milliseconds. Nothing spawns an Engine.
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import http from 'node:http';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const engineSupervisor = require(path.join(__dirname, 'lib', 'engine-supervisor.cjs'));
const engineActuator = require(path.join(__dirname, 'lib', 'engine-actuator.cjs'));

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const CONTRACT = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'governance', 'supervision-contract.v1.json'), 'utf8'));
const ENGINE_POLICY = CONTRACT.processes.find((p) => p.id === 'engine').policy;
const PRODUCT_POLICY = engineSupervisor.loadPolicy({ env: {} });

test('Q2: the Rust host installs the incarnation before Engine boot', () => {
  const shell = path.join(REPO_ROOT, 'modules', 'shell', 'src-tauri', 'src');
  const host = fs.readFileSync(path.join(shell, 'engine_host.rs'), 'utf8');
  assert.match(host, /command\.env\("JUSTSEARCH_ENGINE_INCARNATION"/,
    'the Rust host must install the identity before Engine boot');
});
for (const file of ['lib.rs', path.join('bin', 'supervisor_conformance.rs')]) {
  test(`Q2: Rust request writer ${file} carries incarnation and fresh nonce`, () => {
    const shell = path.join(REPO_ROOT, 'modules', 'shell', 'src-tauri', 'src');
    const source = fs.readFileSync(path.join(shell, file), 'utf8');
    const writer = source.slice(source.indexOf('fn write_shutdown_request'), source.indexOf('fn force_kill'));
    assert.match(writer, /current_incarnation\(\)/, `${file} must address its owned Engine`);
    assert.match(writer, /"incarnation": incarnation/, `${file} omitted the incarnation`);
    assert.match(writer, /"requestNonce": uuid::Uuid::new_v4\(\)/, `${file} omitted a fresh request nonce`);
  });
}
/** The contract's resume grace; 30 s (Spec F §0.1) where the module does not export it. */
const RESUME_GRACE_MS = engineSupervisor.RESUME_GRACE_MS ?? 30000;

/**
 * Runs the real loop to its end over a fake actuator. `suspend` turns one loop wait into a gap of
 * `ms` (what a sleeping machine looks like to the loop); `alive(t, suspend)` is the Engine's answer
 * to a liveness poll at fake monotonic time `t`; at `quitAt` a host quit is posted, and the Engine
 * exits on the POST unless `ignoreQuit`.
 */
async function runLoop({ policy = PRODUCT_POLICY, tickMs = 500, suspend = null, alive = () => true,
  quitAt, ignoreQuit = false, loopOptions = {} }) {
  let t = 0;
  let wall = 1_700_000_000_000;
  let exit = null;
  let quitAsked = false;
  let loop = null;
  const events = { requests: [], posts: [], forced: [], intents: [], logs: [] };
  const actuator = {
    reconcile: async () => [],
    spawnEngine: ({ n }) => ({ pid: 100 + n }),
    pollExit() {
      if (!exit || exit.consumed) return null;
      exit.consumed = true;
      return { n: 1, pid: 101, code: exit.code, signal: null };
    },
    exitObserved: () => Boolean(exit),
    async waitOrWake(ms) {
      if (suspend && !suspend.done && t >= suspend.at) {
        suspend.done = true;
        t += suspend.ms;
        wall += suspend.ms;
        suspend.resumedAt = t;
      } else {
        t += ms;
        wall += ms;
      }
      if (t > 30 * 60_000) throw new Error('the fake loop ran 30 simulated minutes without ending');
      if (!quitAsked && t >= quitAt) {
        quitAsked = true;
        void loop.hostQuit({ detail: 'test' });
      }
      await new Promise((resolve) => setImmediate(resolve));
    },
    wake() {},
    recordIntent(intent) { events.intents.push({ ...intent, t }); return intent; },
    writeShutdownRequest(reason, deadlineEpochMs) { events.requests.push({ reason, deadlineEpochMs, t }); },
    async postShutdown(apiPort, reason, options) {
      events.posts.push({ apiPort, reason, options, t, wall });
      if (!ignoreQuit) exit = { code: 0 };
      return { ok: true, status: 202 };
    },
    async forceKill(reasonKey) {
      events.forced.push({ reasonKey, t });
      exit = { code: 1 };
      return { attempted: true };
    },
    recordExit: async () => null,
  };
  const io = {
    clearStaleDiscoveryFiles() {},
    readManifest: () => ({ apiPort: 5000, instanceId: 'inst-1' }),
    readRawManifest: () => null,
    probeHealth: async () => true,
    probeAlive: async () => alive(t, suspend),
    fetchStatus: async () => null,
    essentialReadyEpoch: () => null,
    waitForRelease: async () => ({ released: true, waitedMs: 0 }),
  };
  loop = engineSupervisor.createSupervisionLoop({
    actuator, policy, io,
    publish: async () => {},
    timeouts: { initialPortMs: 60_000, initialReadyMs: 60_000 },
    log: (line) => events.logs.push(line),
    monotonic: () => t,
    wallClock: () => wall,
    tickMs,
    ...loopOptions,
  });
  const outcome = await loop.run();
  return { events, outcome, suspend };
}

// --- step 7: the one quit -----------------------------------------------------------------------

test('Spec F §4.3: the contract declares the quit deadline and force margin the actuator uses', () => {
  assert.equal(ENGINE_POLICY.quitDeadlineMs, 8000);
  assert.equal(ENGINE_POLICY.quitForceMarginMs, 300);
  assert.equal(engineActuator.QUIT_DEADLINE_MS, ENGINE_POLICY.quitDeadlineMs);
  assert.equal(engineActuator.SHELL_FORCE_MARGIN_MS, ENGINE_POLICY.quitForceMarginMs);
  assert.equal(PRODUCT_POLICY.quitDeadlineMs, ENGINE_POLICY.quitDeadlineMs);
  assert.equal(PRODUCT_POLICY.quitForceMarginMs, ENGINE_POLICY.quitForceMarginMs);
});

test('Spec F §4.3: a host quit posts its deadline and is forced at the policy deadline minus the margin', async () => {
  const policy = { ...PRODUCT_POLICY, quitDeadlineMs: 6000, quitForceMarginMs: 500 };
  const { events, outcome } = await runLoop({ policy, tickMs: 50, quitAt: 3000, ignoreQuit: true });
  assert.equal(events.posts.length, 1);
  const [post] = events.posts;
  assert.equal(post.options?.deadlineAtEpochMs, post.wall + 6000, 'the POST names the quit deadline');
  assert.equal(events.forced.length, 1, 'the ignored quit is forced');
  const forcedAfter = events.forced[0].t - post.t;
  assert.ok(forcedAfter >= 5500 && forcedAfter <= 5500 + 50, `forced ${forcedAfter} ms after the POST, not 5500`);
  assert.equal(outcome.state, 'stopped');
});

test('Spec F §4.3: the request file and the POST name the incarnation they address, with a request nonce', async (t) => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-quit-request-'));
  t.after(() => fs.rmSync(dataDir, { recursive: true, force: true }));
  const posted = [];
  const actuator = engineActuator.createEngineActuator({
    dataDir,
    runId: 'run-quit',
    policy: PRODUCT_POLICY,
    spawnChild: () => Object.assign(new EventEmitter(), { pid: 4242 }),
    ledger: { append: () => true, startSession() {} },
    lookupIdentities: async () => new Map(),
    post: (...args) => { posted.push(args); return Promise.resolve({ ok: true, status: 202 }); },
    log: () => {},
  });
  const requestFile = path.join(dataDir, 'runtime', 'shutdown-request.v1.json');

  // Before spawn there is no Engine to address, so no stale request may be left on disk.
  assert.equal(actuator.writeShutdownRequest('quit', 111), null);
  assert.equal(fs.existsSync(requestFile), false);

  actuator.spawnEngine({ n: 2, cause: 'INITIAL', apiPort: 0 });
  actuator.writeShutdownRequest('quit', 222);
  const first = JSON.parse(fs.readFileSync(requestFile, 'utf8'));
  assert.equal(first.incarnation, 'run-quit/2');
  assert.match(String(first.requestNonce), /^[0-9a-f-]{36}$/);
  assert.equal(first.deadlineEpochMs, 222);
  assert.equal(first.nonce, undefined, 'the upgrade receipt nonce is never on the host channel');
  actuator.writeShutdownRequest('restart', 333);
  const second = JSON.parse(fs.readFileSync(requestFile, 'utf8'));
  assert.notEqual(second.requestNonce, first.requestNonce, 'every request carries a fresh nonce');

  await actuator.postShutdown(5000, 'quit', { deadlineAtEpochMs: 444 });
  assert.equal(posted.length, 1);
  assert.deepEqual(posted[0][3], { deadlineAtEpochMs: 444, incarnation: 'run-quit/2' });
});

test('Spec F §4.3: the lifecycle POST body carries the deadline and the incarnation on the wire', async (t) => {
  const bodies = [];
  const server = http.createServer((req, res) => {
    let raw = '';
    req.on('data', (chunk) => { raw += chunk; });
    req.on('end', () => {
      bodies.push({ url: req.url, method: req.method, body: JSON.parse(raw) });
      res.writeHead(202).end('{}');
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => server.close());
  const { port } = server.address();
  const result = await engineActuator.postShutdown(port, 'quit', 2000,
    { deadlineAtEpochMs: 1_700_000_008_000, incarnation: 'run-quit/3' });
  assert.equal(result.ok, true);
  assert.deepEqual(bodies, [{ url: '/api/lifecycle/shutdown', method: 'POST',
    body: { reason: 'quit', deadlineAtEpochMs: 1_700_000_008_000, incarnation: 'run-quit/3' } }]);
  // Today's callers that name neither still send the reason alone.
  await engineActuator.postShutdown(port, 'quit');
  assert.deepEqual(bodies[1].body, { reason: 'quit' });
});

// --- step 8: suspended time is not a hang -------------------------------------------------------

test('Spec F §3.5 (P9-P2): with no suspend the suspend-aware clock is the monotonic clock', async () => {
  assert.equal(typeof engineSupervisor.createSuspendAwareClock, 'function', 'no SuspendAwareClock');
  let t = 1000;
  const clock = engineSupervisor.createSuspendAwareClock({ monotonic: () => t, tickMs: 50 });
  for (const wait of [50, 50, 1500, 2000, 50]) {
    // eslint-disable-next-line no-await-in-loop
    assert.equal(await clock.measureWait(async () => { t += wait; }), 0, `a ${wait} ms wait is not a suspend`);
    assert.equal(clock.now(), t);
  }
  assert.equal(clock.inResumeGrace(), false);
  assert.equal(await clock.measureWait(async () => { t += 60_050; }), 60_000, 'the gap minus one tick');
  assert.equal(clock.now(), t - 60_000);
  assert.equal(clock.inResumeGrace(), true);
  t += RESUME_GRACE_MS;
  assert.equal(clock.inResumeGrace(), false);
});

test('Spec F §3.5 (S11): health misses in the grace after a suspend are not a hang', async () => {
  const freezeMs = 25_000; // inside the 30 s resume grace
  const { events, outcome } = await runLoop({
    suspend: { at: 30_000, ms: 60_000 },
    alive: (t, suspend) => !(suspend?.resumedAt !== undefined && t < suspend.resumedAt + freezeMs),
    quitAt: 150_000,
  });
  assert.deepEqual(events.requests.filter((r) => r.reason === 'hang'), [], 'a suspend was taken for a hang');
  assert.equal(events.posts.length, 1, 'the run ended through the host quit');
  assert.equal(outcome.state, 'stopped');
  assert.ok(events.logs.some((line) => line.includes('suspended')), 'the suspend is narrated');
});

test('Spec F §3.5 (P9-P2): misses after the resume grace still end in the hang path', async () => {
  const { events, suspend } = await runLoop({
    suspend: { at: 30_000, ms: 60_000 },
    alive: (t, s) => !(s?.resumedAt !== undefined && t >= s.resumedAt + RESUME_GRACE_MS + 1000),
    quitAt: 400_000,
  });
  const hang = events.requests.filter((r) => r.reason === 'hang');
  assert.equal(hang.length, 1, 'a frozen Engine past the grace is a hang');
  assert.ok(hang[0].t >= suspend.resumedAt + RESUME_GRACE_MS);
});

test('P9: without a suspend the hang path fires after the threshold of misses, as before', async () => {
  const frozenFrom = 20_000;
  const { events } = await runLoop({ alive: (t) => t < frozenFrom, quitAt: 300_000 });
  const hang = events.requests.filter((r) => r.reason === 'hang');
  assert.equal(hang.length, 1);
  const window = PRODUCT_POLICY.hangPollIntervalMs * PRODUCT_POLICY.hangUnhealthyThreshold;
  assert.ok(hang[0].t - frozenFrom <= window + PRODUCT_POLICY.hangPollIntervalMs,
    `hang requested ${hang[0].t - frozenFrom} ms after the freeze`);
});
