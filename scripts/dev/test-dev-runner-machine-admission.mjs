#!/usr/bin/env node
import './lib/test-fixture-processes.cjs';
// Machine admission around `dev-runner start`, with real processes: the real dev-runner in this
// checkout, the supervisor-conformance fake engine (harness flag), a private state root, private
// ports and a private admission directory. A start holds heavy admission until the stack reports
// ready, and releases it on every other ending too (Engine boot failure, readiness timeout, a stop
// during startup); a running idle stack never blocks an exclusive window.
//
// Run: node scripts/dev/test-dev-runner-machine-admission.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

if (process.platform !== 'linux') {
  console.log('machine-admission: SKIP (Linux machine admission)');
  process.exit(0);
}

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
fs.mkdirSync(path.join(repo, 'tmp'), { recursive: true });
const root = fs.mkdtempSync(path.join(repo, 'tmp', 'dev-runner-machine-admission-'));
process.env.JS_ADMISSION_DIR = path.join(root, 'admission');
const ADMISSION = path.join(repo, 'scripts/dev/admission.py');
const DEV_RUNNER = path.join(repo, 'scripts/dev/dev-runner.cjs');
const FAKE_ENGINE = path.join(repo, 'scripts/supervisor-conformance/fake-engine.mjs');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

const status = () => {
  const result = spawnSync('python3', ['-I', ADMISSION, 'status'], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  return JSON.parse(result.stdout);
};
const startRows = () => status().filter(row => row.purpose === 'dev-runner engine start');
function exclusive(drain) {
  const started = Date.now();
  const result = spawnSync('python3', ['-I', ADMISSION, 'exclusive', '--purpose', 'fixture scoring',
    '--drain-timeout', drain, '--', process.execPath, '-e', ''], { encoding: 'utf8', timeout: 15_000 });
  return { ...result, ms: Date.now() - started };
}
async function until(label, predicate, timeoutMs = 20_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = predicate();
    if (value) return value;
    assert.ok(Date.now() < deadline, `timed out: ${label}`);
    await sleep(50);
  }
}
async function freePort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const { port } = server.address();
  await new Promise(resolve => server.close(resolve));
  return port;
}

/** A scratch checkout of the scripts under test with no gradlew (and no dist). */
function checkoutWithoutGradle() {
  const dest = path.join(root, 'no-gradlew');
  const { nativeTarget } = createRequire(import.meta.url)('../lib/native-targets.cjs');
  for (const rel of ['scripts/dev/dev-runner.cjs', 'scripts/dev/admission.py', 'scripts/dev/lib', 'scripts/lib',
    'contracts/native-targets/spec.v1.json', 'governance/supervision-contract.v1.json',
    `modules/ui/build/install/ui/${nativeTarget().paths['bound-exec']}`]) {
    fs.mkdirSync(path.dirname(path.join(dest, rel)), { recursive: true });
    fs.cpSync(path.join(repo, rel), path.join(dest, rel), { recursive: true });
  }
  return dest;
}

const runners = [];
async function startRunner(name, engineArgs, extraEnv = {}, { checkout = repo, build = false } = {}) {
  const dir = path.join(root, name);
  const env = { ...process.env, JUSTSEARCH_SUPERVISOR_HARNESS: '1', CI: '',
    JUSTSEARCH_DEV_RUNNER_STATE_ROOT: path.join(dir, 'state'), JUSTSEARCH_DEV_MCP_LOG_NDJSON: '0',
    JUSTSEARCH_DEV_RUNNER_ENGINE_COMMAND: JSON.stringify([process.execPath, FAKE_ENGINE, ...engineArgs]),
    JUSTSEARCH_DEV_RUNNER_FRONTEND_COMMAND: JSON.stringify([process.execPath, '-e', 'setInterval(()=>{},60000)']),
    // One owner for this start and its stop, independent of the harness running the test.
    JUSTSEARCH_AGENT_IDENTITY: `machine-admission-${name}`,
    ...extraEnv };
  for (const key of ['JS_ADMISSION_TOKEN', 'JUSTSEARCH_AGENT_IDENTITY_HANDOFF', 'CLAUDE_CODE_SESSION_ID', 'CLAUDE_PID',
    'CODEX_THREAD_ID', 'CODEX_SESSION_ID', 'JUSTSEARCH_AGENT_SESSION_ID', 'JUSTSEARCH_PROCESS_TABLE_FIXTURE']) delete env[key];
  const child = spawn(process.execPath, [path.join(checkout, 'scripts/dev/dev-runner.cjs'), 'start', '--json',
    ...(build ? [] : ['--skip-build']), '--clean=none', '--api-port=0',
    `--ui-port=${await freePort()}`, `--data-dir=${path.join(dir, 'data')}`, '--lease-duration-sec', '60'], { cwd: checkout, env, stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '';
  child.stdout.on('data', data => { output += data; });
  child.stderr.on('data', data => { output += data; });
  const exited = new Promise(resolve => child.once('exit', (code, signal) => resolve({ code, signal })));
  const runner = { child, env, exited, output: () => output, ended: false };
  exited.then(() => { runner.ended = true; });
  runners.push(runner);
  return runner;
}
const stop = runner => spawnSync(process.execPath, [DEV_RUNNER, 'stop', '--json'],
  { cwd: repo, env: runner.env, encoding: 'utf8', timeout: 60_000 });
async function ended(runner, timeoutMs = 60_000) {
  const timer = sleep(timeoutMs).then(() => null);
  const result = await Promise.race([runner.exited, timer]);
  assert.ok(result, `dev-runner did not exit:\n${runner.output()}`);
  return result;
}
function assertReleased(label) {
  assert.deepEqual(startRows(), [], `${label}: start admission still held`);
  const window = exclusive('1');
  assert.equal(window.status, 0, `${label}: ${window.stderr}`);
  assert.ok(window.ms < 2000, `${label}: exclusive window took ${window.ms} ms`);
}

const cases = [];
const test = (name, fn) => cases.push([name, fn]);

test('a start holds heavy admission until ready; the idle running stack does not block a window', async () => {
  const runner = await startRunner('ready', ['--mode', 'slow-start', '--slow-start-ms', '2500']);
  const [row] = await until('start admission recorded', () => startRows().length && startRows());
  assert.equal(row.mode, 'heavy');
  console.log(`  status during start: ${JSON.stringify(row)}`);
  const refused = exclusive('.3');
  assert.equal(refused.status, 75, refused.stderr);
  assert.match(refused.stderr, /dev-runner engine start/);
  const ready = await until('ready receipt', () => {
    assert.equal(runner.ended, false, runner.output());
    return runner.output().split('\n').map(line => { try { return JSON.parse(line); } catch { return null; } })
      .find(value => value?.ok === true && value.readiness?.ready_http);
  });
  assertReleased('ready');
  assert.equal(runner.ended, false, 'the stack keeps running while the window runs');
  console.log(`  run ${ready.runId} ready and idle: exclusive window admitted`);
  const stopped = stop(runner);
  assert.equal(stopped.status, 0, stopped.stdout + stopped.stderr);
  await ended(runner);
});

test('an Engine that fails to boot releases the start admission', async () => {
  const runner = await startRunner('boot-fail', ['--mode', 'boot-fail']);
  const { code } = await ended(runner);
  assert.notEqual(code, 0, runner.output());
  assertReleased('boot failure');
});

test('a readiness timeout releases the start admission', async () => {
  const runner = await startRunner('timeout', ['--mode', 'slow-start', '--slow-start-ms', '60000'],
    { JUSTSEARCH_DEV_RUNNER_BACKEND_PORT_TIMEOUT_MS: '2000' });
  await until('start admission recorded', () => startRows().length);
  const { code } = await ended(runner);
  assert.notEqual(code, 0, runner.output());
  assertReleased('readiness timeout');
});

test('a stop during startup releases the start admission', async () => {
  const runner = await startRunner('stop', ['--mode', 'slow-start', '--slow-start-ms', '60000'],
    { JUSTSEARCH_DEV_RUNNER_BACKEND_PORT_TIMEOUT_MS: '30000' });
  await until('start admission recorded', () => startRows().length);
  await until('engine launched', () => /Launching|engine/i.test(runner.output()));
  const stopped = stop(runner);
  const { code, signal } = await ended(runner, 30_000);
  console.log(`  stop during startup: stop exit ${stopped.status}, runner exit ${signal ?? code}`);
  assertReleased('stop during startup');
});

test('a missing gradlew is reported as its own ENOENT, not as an admission exit code', async () => {
  const runner = await startRunner('no-gradlew', ['--mode', 'honour'], {}, { checkout: checkoutWithoutGradle(), build: true });
  const { code } = await ended(runner);
  assert.notEqual(code, 0, runner.output());
  assert.match(runner.output(), /Gradle installDist could not be run: .*ENOENT/, runner.output());
  assertReleased('missing gradlew');
});

let failed = 0;
try {
  for (const [name, fn] of cases) {
    try {
      await fn();
      console.log(`PASS ${name}`);
    } catch (error) {
      failed++;
      console.log(`FAIL ${name}\n${error.stack}`);
    }
  }
} finally {
  // Only runners this file started, by their child handles; each stops its own stack first.
  for (const runner of runners) {
    if (!runner.ended) {
      stop(runner);
      if (!runner.ended) runner.child.kill('SIGTERM');
      await Promise.race([runner.exited, sleep(30_000)]);
    }
  }
  fs.rmSync(root, { recursive: true, force: true });
}
process.exit(failed ? 1 : 0);
