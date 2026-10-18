#!/usr/bin/env node
// Run under flock /tmp/js-linux-port/devstack.lock. All state and corpora belong to this run.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import crypto from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { createRequire } from 'node:module';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
const require = createRequire(import.meta.url);
const identity = require('./lib/process-identity.cjs');
if (process.platform !== 'linux') { console.log('SKIP Linux development workflow: Linux required'); process.exit(0); }
const root = path.resolve(import.meta.dirname, '../..');
assert.ok(fs.statSync(path.join(root, '.git')).isFile(), 'exercise an actual worktree');
const scratch = fs.mkdtempSync(path.join(root, 'tmp/linux-dev-workflow-'));
const state = path.join(scratch, 'state');
const helper = identity.boundExecPath();
fs.accessSync(helper, fs.constants.X_OK);
const env = { ...process.env, JUSTSEARCH_DEV_RUNNER_STATE_ROOT: state, JUSTSEARCH_JOB_RECORD_DIR: path.join(scratch, 'jobs'),
  JUSTSEARCH_DEV_MCP_LOG_NDJSON: '0', JUSTSEARCH_AI_AUTOSTART_ENABLED: 'false',
  LD_LIBRARY_PATH: ['/opt/nvidia/ort-cuda12/current/lib', process.env.LD_LIBRARY_PATH].filter(Boolean).join(':') };
const evidence = { root, scratch, scenarios: {}, helpers: [] };
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
async function poll(description, predicate, timeout = 120000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { const value = await predicate(); if (value) return value; await wait(100); }
  throw new Error(`Timed out: ${description}`);
}
async function freePort() {
  const server = net.createServer(); server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const port = server.address().port; await new Promise(resolve => server.close(resolve)); return port;
}
async function listening(port) {
  return new Promise(resolve => { const socket = net.connect({ host: '127.0.0.1', port });
    socket.once('connect', () => { socket.destroy(); resolve(true); }); socket.once('error', () => resolve(false)); });
}
async function visitFrontend(port) {
  const base = `http://127.0.0.1:${port}`;
  const page = await fetch(base);
  assert.equal(page.status, 200, 'Vite serves the page');
  const entry = (await page.text()).match(/<script[^>]+src="(\/src\/[^\"]+)"/);
  assert.ok(entry, 'the page declares its real module entry');
  const module = await fetch(new URL(entry[1], base));
  assert.equal(module.status, 200, 'Vite transforms the actual frontend module');
  await module.text();
}
function ended(record) {
  const seen = identity.readLinuxProcess(record.pid), id = identity.linuxIdentity(record);
  return seen?.present === false || (id && seen?.processIdentity
    && (id.bootId !== seen.processIdentity.bootId || id.startTicks !== seen.processIdentity.startTicks));
}
function records(directory) {
  if (!fs.existsSync(directory)) return [];
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) return records(full);
    if (!entry.name.endsWith('.json')) return [];
    try { const value = JSON.parse(fs.readFileSync(full, 'utf8')); return value.backend === 'linux-bound-child-v1' ? value.members : []; } catch { return []; }
  });
}
// Test-only /proc census rooted in the runner's durable birth. It grants no cleanup authority.
function descendantCensus(runner) {
  const expected = identity.linuxIdentity(runner);
  const rootProcess = identity.readLinuxProcess(runner.pid);
  assert.ok(expected && rootProcess?.present && sameBirth(runner, rootProcess), 'recorded runner is alive');
  const queue = [{ pid: runner.pid, ...rootProcess }], found = [];
  for (const parent of queue) {
    // Enumerate the kernel links independently of production's job observation loop. A known
    // child whose birth cannot be read fails this check; it must not disappear from the census.
    let childPids;
    try {
      const tasks = `/proc/${parent.pid}/task`;
      childPids = new Set(fs.readdirSync(tasks).flatMap(tid => {
        try { return fs.readFileSync(path.join(tasks, tid, 'children'), 'utf8').trim().split(/\s+/).filter(Boolean).map(Number); }
        catch (error) { if (error.code === 'ENOENT') return []; throw error; }
      }));
    } catch (error) { if (ended(parent)) continue; throw error; }
    const children = [];
    for (const pid of childPids) {
      const child = identity.readLinuxProcess(pid);
      assert.ok(child, `child identity readable: ${pid}`);
      if (!child.present || child.parentPid !== parent.pid) continue;
      assert.equal(child.processIdentity.bootId, parent.processIdentity.bootId);
      assert.ok(BigInt(child.processIdentity.startTicks) >= BigInt(parent.processIdentity.startTicks));
      children.push({ pid, ...child });
    }
    const after = identity.readLinuxProcess(parent.pid);
    assert.ok(ended(parent) || sameBirth(parent, after), `parent identity stable: ${parent.pid}`);
    for (const child of children) {
      if (found.some(record => sameBirth(record, child))) continue;
      assert.ok(child.executable, `executable readable for descendant ${child.pid}`);
      const record = { pid: child.pid, processIdentity: child.processIdentity, executable: child.executable };
      found.push(record); queue.push(record);
    }
  }
  return found;
}
function sameBirth(a, b) {
  const left = identity.linuxIdentity(a), right = identity.linuxIdentity(b);
  return left && right && left.pid === right.pid && left.bootId === right.bootId && left.startTicks === right.startTicks;
}
async function snapshotStack(id) {
  const runDir = path.join(state, 'runs', id);
  const helpers = await poll('Engine and frontend durably recorded', () => {
    const found = records(runDir);
    return found.length >= 2 ? found : false;
  }, 10000);
  const run = JSON.parse(fs.readFileSync(path.join(runDir, 'run.json'), 'utf8'));
  const runner = run.identities.runner;
  assert.equal(runner.pid, run.pids.runnerPid);
  const descendants = descendantCensus(runner);
  assert.ok(helpers.every(record => ended(record) || descendants.some(child => sameBirth(record, child))), 'every recorded helper belongs to this live stack');
  const snapshot = { runner, helpers, descendants, ports: [run.apiPortActual, run.uiPortActual, run.hotReload?.debugPort].filter(Boolean) };
  fs.writeFileSync(path.join(runDir, 'test-descendant-census.json'), JSON.stringify(snapshot, null, 2));
  return snapshot;
}
async function assertStackEnded(snapshot, stopped) {
  assert.equal(stopped.ok, true, JSON.stringify(stopped));
  assert.equal(stopped.outcome, 'STOPPED', JSON.stringify(stopped));
  await poll('every pre-stop descendant and runner birth ended', () => [snapshot.runner, ...snapshot.descendants].every(ended), 2000);
  for (const port of snapshot.ports) assert.equal(await listening(port), false, `started port ${port} closed`);
  for (const helper of snapshot.helpers) {
    assert.ok(stopped.recordedHelpers?.some(record => sameBirth(record, helper) && record.accounted === true),
      `stop result accounts recorded helper ${JSON.stringify(helper)}`);
  }
}
async function command(command, args, options = {}) {
  const child = spawn(command, args, { cwd: root, env, ...options, stdio: ['ignore', 'pipe', 'pipe'] });
  const birth = identity.readLinuxProcess(child.pid)?.processIdentity;
  let output = '';
  child.stdout.on('data', chunk => { output += chunk; }); child.stderr.on('data', chunk => { output += chunk; });
  const [code, signal] = await once(child, 'exit');
  assert.equal(code, 0, `${command} ${args.join(' ')} (${signal})\n${output.slice(-10000)}`);
  assert.ok(birth && ended({ pid: child.pid, processIdentity: birth }));
  return output;
}
async function gradle(args) {
  return command('flock', ['/tmp/js-linux-port/gradle.lock', './gradlew', ...args]);
}
const transport = new StdioClientTransport({ command: process.execPath, args: [path.join(root, 'scripts/dev/justsearch-dev-mcp.mjs')], cwd: root, env, stderr: 'pipe' });
const client = new Client({ name: 'linux-workflow-verifier', version: '1.0.0' });
let mcpBirth, runId, sourceChanged = false, classesNeedRepair = false;
let ownerChild, ownerIdentity;
const javaSource = path.join(root, 'modules/worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerHealthService.java');
const originalSource = fs.readFileSync(javaSource, 'utf8');
const datasetName = `mixed/linux-workflow-${crypto.randomUUID()}`;
const datasetDir = path.join(root, 'datasets', datasetName);
async function tool(name, args = {}) {
  const result = await client.callTool({ name: `justsearch.dev.${name}`, arguments: args }, undefined, { timeout: 600000 });
  return result.structuredContent ?? JSON.parse(result.content.find(item => item.type === 'text').text);
}
try {
  const actual = JSON.parse(spawnSync(process.execPath, [path.join(root, 'scripts/dev/agent-identity.mjs'), '--json'], { cwd: root, env, encoding: 'utf8' }).stdout);
  assert.equal(actual.harness, 'codex'); assert.ok(actual.owner?.processIdentity); evidence.scenarios.S6_identity = actual;
  await command(process.execPath, [path.join(root, 'scripts/dev/prepare-worktree.cjs'), '--no-dist']);
  // An unchanged jar can retain its old timestamp after an incremental source restoration.
  // Build/install the fixture's pair together; never bypass the runner's stale-pair refusal.
  await gradle(['spotlessApply', ':modules:ui:installDist', '--rerun-tasks', '-PskipWebBuild=true']);
  fs.accessSync(helper, fs.constants.X_OK);
  await client.connect(transport);
  mcpBirth = identity.readLinuxProcess(transport.pid)?.processIdentity;
  assert.ok(mcpBirth, 'MCP process birth is readable');
  let mcpStderr = ''; transport.stderr?.on('data', data => { mcpStderr = (mcpStderr + data).slice(-30000); });
  const preflight = await tool('preflight'); evidence.scenarios.preflight = preflight;
  assert.equal(preflight.ready, true, JSON.stringify(preflight));
  const apiPort = await freePort(), uiPort = await freePort();
  const start = await tool('start', { apiPort, uiPort, dataDir: path.join(scratch, 'data space ü'), skipBuild: true,
    hotReload: true, waitLevel: 'ready_worker', waitTimeoutMs: 120000, leaseDurationSec: 7200 });
  evidence.scenarios.start = start;
  runId = start.runId;
  assert.equal(start.ok, true, `${JSON.stringify(start)}\n${mcpStderr}`);
  const runFile = path.join(state, 'runs', runId, 'run.json');
  const run = JSON.parse(fs.readFileSync(runFile, 'utf8'));
  assert.equal(run.hotReload.enabled, true, JSON.stringify(run.hotReload));
  const health = await tool('quick_health', { probe: true }); evidence.scenarios.quick_health = health;
  assert.equal(health.running, true, JSON.stringify(health));
  assert.equal(health.httpReady, true, JSON.stringify(health));
  assert.equal(health.workerReady, true, JSON.stringify(health));
  const foreign = `codex:foreign-${crypto.randomUUID()}`;
  for (const [name, args] of [['stop', { runId }], ['start', { skipBuild: true }], ['reload', { skipCompile: true }]]) {
    const refused = await tool(name, { ...args, sessionId: foreign });
    assert.equal(refused.ok, false, JSON.stringify(refused));
    assert.equal(refused.error?.code, 'OWNER_CONFLICT', JSON.stringify(refused));
  }
  evidence.scenarios.S6_foreign_owner = 'stop/start/reload refused';
  const marker = `linux-reload-${crypto.randomUUID()}`;
  assert.ok(originalSource.includes('String versionWithStatus = version;'));
  fs.writeFileSync(javaSource, originalSource.replace('String versionWithStatus = version;', `String versionWithStatus = version + " ${marker}";`)); sourceChanged = true; classesNeedRepair = true;
  await gradle(['spotlessApply', ':modules:worker-services:compileJava', '-PskipWebBuild=true']);
  const reloaded = await tool('reload', { skipCompile: true }); assert.equal(reloaded.ok, true, JSON.stringify(reloaded));
  await poll('changed method body observable in running Engine', async () => {
    for (const endpoint of ['/api/status?fresh=true', '/api/debug/state']) {
      const response = await fetch(`http://127.0.0.1:${apiPort}${endpoint}`);
      if ((await response.text()).includes(marker)) return true;
    }
    return false;
  });
  evidence.scenarios.S5 = { marker, reloaded };
  fs.writeFileSync(javaSource, originalSource); sourceChanged = false;
  await visitFrontend(uiPort);
  const snapshot = await snapshotStack(runId);
  evidence.helpers = snapshot;
  const stopped = await tool('stop', { runId }); evidence.scenarios.stop = stopped;
  await assertStackEnded(snapshot, stopped);
  assert.equal(await listening(apiPort), false); assert.equal(await listening(uiPort), false);
  const stoppedAgain = await tool('stop', { runId }); assert.equal(stoppedAgain.ok, true, JSON.stringify(stoppedAgain));
  assert.equal(stoppedAgain.outcome, 'STOPPED'); assert.equal(stoppedAgain.portsClosed, true);
  assert.deepEqual(stoppedAgain.killedPids, []); assert.deepEqual(stoppedAgain.errors, []);
  assert.deepEqual(stoppedAgain.unresolvedProcesses, []);
  evidence.scenarios.stopAgain = stoppedAgain;
  evidence.scenarios.S4 = 'start/ready/stop/repeated stop; recorded helpers ended; ports closed';
  runId = null;
  const second = await tool('start', { apiPort, uiPort, dataDir: path.join(scratch, 'data space ü'), skipBuild: true,
    hotReload: false, waitLevel: 'ready_worker', waitTimeoutMs: 120000, leaseDurationSec: 7200 });
  runId = second.runId; assert.equal(second.ok, true, JSON.stringify(second));
  await visitFrontend(uiPort);
  const secondSnapshot = await snapshotStack(runId);
  const secondStop = await tool('stop', { runId });
  await assertStackEnded(secondSnapshot, secondStop);
  assert.equal(await listening(apiPort), false); assert.equal(await listening(uiPort), false);
  evidence.scenarios.S4_repeat = { runId, snapshot: secondSnapshot, stop: secondStop };
  runId = null;
  await gradle(['spotlessApply', ':modules:ui:installDist', '-PskipWebBuild=true']); fs.accessSync(helper, fs.constants.X_OK); classesNeedRepair = false;
  const python = process.env.VIRTUAL_ENV ? path.join(process.env.VIRTUAL_ENV, 'bin/python3') : 'python3';
  const evalPort = await freePort();
  fs.mkdirSync(path.join(datasetDir, 'qrels'), { recursive: true });
  fs.writeFileSync(path.join(datasetDir, 'queries.jsonl'), '{"_id":"q1","text":"linux workflow"}\n{"_id":"q2","text":"worktree reload"}\n');
  fs.writeFileSync(path.join(datasetDir, 'qrels/test.tsv'), 'query-id\tcorpus-id\tscore\nq1\td1\t1\nq2\td1\t1\n');
  fs.writeFileSync(path.join(datasetDir, 'corpus.jsonl'), '{"_id":"d1","text":"Linux workflow fixture"}\n');
  const evalEnv = { ...env, PYTHONPATH: path.join(root, 'scripts/jseval'), JUSTSEARCH_API_PORT: String(evalPort),
    JUSTSEARCH_DATA_DIR: path.join(scratch, 'eval data'), JUSTSEARCH_REPO_ROOT: root };
  const campaign = await command('flock', ['/tmp/js-linux-port/gradle.lock', python, '-m', 'jseval', 'run',
    '--dataset', datasetName, '--modes', 'lexical', '--max-queries', '2', '--skip-ingest', '--start-backend',
    '--output-dir', path.join(scratch, 'campaign'), '--base-url', `http://127.0.0.1:${evalPort}`, '--json'],
    { cwd: path.join(root, 'scripts/jseval'), env: evalEnv });
  evidence.scenarios.S15_campaign = campaign.slice(-10000);
  assert.equal(await listening(evalPort), false);
  // Separate real Python lifetime owner, killed only by its recorded Linux birth tuple.
  const ownerProgram = 'from jseval.backend import start_backend\nimport json,time,os\nb=start_backend(port=int(os.environ["JUSTSEARCH_API_PORT"]),health_timeout_sec=180)\nprint(json.dumps({"ready":True,"runner":b.proc.pid}),flush=True)\ntime.sleep(600)';
  ownerChild = spawn(helper, ['exec', String(process.pid), '--', 'flock', '-F', '/tmp/js-linux-port/gradle.lock', python, '-u', '-c', ownerProgram],
    { cwd: path.join(root, 'scripts/jseval'), env: { ...evalEnv, JUSTSEARCH_DATA_DIR: path.join(scratch, 'owner kill data') }, stdio: ['ignore', 'pipe', 'pipe'] });
  // -F keeps the recorded PID as the Python lifetime owner by execing without a fork.
  const ownerExit = once(ownerChild, 'exit');
  ownerIdentity = identity.readLinuxProcess(ownerChild.pid)?.processIdentity;
  let ownerOutput = ''; ownerChild.stdout.on('data', data => { ownerOutput += data; }); ownerChild.stderr.on('data', data => { ownerOutput += data; });
  await poll('jseval owner SIGKILL round ready', () => {
    assert.ok(ownerChild.exitCode === null && ownerChild.signalCode === null, ownerOutput);
    return ownerOutput.includes('"ready": true');
  }, 240000);
  const evalHelpers = records(path.join(scratch, 'jobs'));
  const evalDescendants = descendantCensus({ pid: ownerChild.pid, processIdentity: ownerIdentity });
  assert.ok(evalHelpers.some(record => /java/.test(record.executable ?? '')) || evalHelpers.length > 0);
  evidence.scenarios.S15_owner_SIGKILL = { owner: ownerIdentity, helpers: evalHelpers, descendants: evalDescendants, port: evalPort };
  const killedAt = performance.now();
  assert.equal(identity.signalLinuxProcess(ownerIdentity, 'SIGKILL').ok, true);
  await ownerExit;
  // A JVM leader may become a zombie before the other dying threads release their sockets.
  // Both process identities and the listener must be gone within this single bounded interval.
  await poll('jseval Engine/helpers and listener exit after owner SIGKILL', async () =>
    [...evalHelpers, ...evalDescendants].every(ended) && !await listening(evalPort), Math.max(1, 2000 - (performance.now() - killedAt)));
  evidence.scenarios.S15_owner_SIGKILL.endedAfterMs = performance.now() - killedAt;
  evidence.scenarios.S15_owner_SIGKILL.portClosed = true;
  console.log('PASS Linux worktree MCP workflow, observed reload, ownership, helpers, jseval and owner SIGKILL');
} finally {
  const repairClasses = classesNeedRepair;
  if (sourceChanged) fs.writeFileSync(javaSource, originalSource);
  if (runId) { try { evidence.cleanup = await tool('stop', { runId }); } catch (error) { evidence.cleanupError = error.message; } }
  if (ownerChild?.exitCode === null && ownerChild?.signalCode === null && ownerIdentity) identity.signalLinuxProcess(ownerIdentity, 'SIGKILL');
  const cleanupIdentities = [...records(path.join(scratch, 'jobs')), ...records(path.join(state, 'runs'))];
  for (const entry of fs.existsSync(path.join(state, 'runs')) ? fs.readdirSync(path.join(state, 'runs')) : []) {
    try {
      const recorded = JSON.parse(fs.readFileSync(path.join(state, 'runs', entry, 'run.json'), 'utf8'));
      cleanupIdentities.push(...Object.values(recorded.identities ?? {}).filter(Boolean), recorded.runner);
    } catch {}
  }
  for (const record of cleanupIdentities) if (identity.linuxIdentity(record) && !ended(record)) identity.signalLinuxProcess(record);
  try { await poll('owned fixture cleanup', () => cleanupIdentities.every(record => !identity.linuxIdentity(record) || ended(record)), 2000); }
  catch (error) { evidence.cleanupError = error.message; }
  if (repairClasses) {
    try { await gradle(['spotlessApply', ':modules:ui:installDist', '-PskipWebBuild=true']); fs.accessSync(helper, fs.constants.X_OK); }
    catch (error) { evidence.classRepairError = error.message; }
  }

  await client.close().catch(() => {});
  evidence.mcpEnded = !mcpBirth || ended({ pid: mcpBirth.pid, processIdentity: mcpBirth });
  fs.writeFileSync(path.join(scratch, 'evidence.json'), JSON.stringify(evidence, null, 2));
  fs.rmSync(datasetDir, { recursive: true, force: true });
  console.log(`Evidence: ${path.join(scratch, 'evidence.json')}`);
  assert.equal(evidence.cleanupError, undefined, evidence.cleanupError);
  assert.equal(evidence.classRepairError, undefined, evidence.classRepairError);
  assert.equal(evidence.mcpEnded, true, 'MCP process ended after close');
}
