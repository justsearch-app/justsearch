#!/usr/bin/env node
import './lib/test-fixture-processes.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createRequire } from 'node:module';
import net from 'node:net';

const require = createRequire(import.meta.url);
const repoRoot = path.resolve(process.env.SUPERVISOR_OUTPUT_TEST_ROOT ?? path.join(import.meta.dirname, '../..'));
const identity = require('./lib/process-identity.cjs');
const agentIdentity = require('./lib/agent-identity.cjs');
const ledger = require('./lib/exit-ledger.cjs');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const read = file => { try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; } };
async function until(label, predicate, timeoutMs = 45000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = predicate();
    if (value) return value;
    assert.ok(Date.now() < deadline, `timed out: ${label}`);
    await sleep(25);
  }
}
const gone = birth => {
  const row = identity.readLinuxProcess(birth.pid);
  return row?.present === false || (row?.processIdentity && (row.processIdentity.bootId !== birth.bootId || row.processIdentity.startTicks !== birth.startTicks));
};

async function freePort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const { port } = server.address();
  await new Promise(resolve => server.close(resolve));
  return port;
}

// Invoked under flock /tmp/js-linux-port/devstack.lock by the acceptance run / suite caller.
async function checkStack(transport, readOnlyLog = false) {
  if (readOnlyLog && process.getuid() === 0) {
    console.log('SKIP: root bypasses read-only file permissions');
    return;
  }
  const root = fs.mkdtempSync(path.join(repoRoot, 'tmp/supervisor-output-'));
  const stateRoot = path.join(root, 'state');
  const uiPort = await freePort();
  const dataDir = path.join(root, 'data');
  const crashPath = path.join(root, 'uncaught.jsonl');
  const monitor = path.join(root, 'monitor.cjs');
  fs.writeFileSync(monitor, `process.on('uncaughtExceptionMonitor', e => require('node:fs').appendFileSync(${JSON.stringify(crashPath)}, JSON.stringify({pid:process.pid,code:e.code,message:e.message})+'\\n'));`);
  const owner = spawn(process.execPath, ['-e', 'setInterval(()=>{},60000)'], { stdio: 'ignore' });
  const ownerRow = identity.readLinuxProcess(owner.pid);
  const caller = { harness: 'codex', source: 'supervisor-output-test', sessionId: null,
    owner: { pid: owner.pid, creationTime: null,
      processIdentity: ownerRow.processIdentity, key: `output-test-${owner.pid}` } };
  const env = { ...process.env, ...agentIdentity.buildHandoffEnv(caller),
    NODE_OPTIONS: `--require=${monitor}`, JUSTSEARCH_DEV_RUNNER_STATE_ROOT: stateRoot,
    JUSTSEARCH_DEV_MCP_LOG_NDJSON: '0', JUSTSEARCH_SUPERVISOR_HARNESS: '1',
    JUSTSEARCH_DEV_LEASE_RENEW_MS: '100', JUSTSEARCH_DEV_REAPER_GRACE_MS: '1000',
    JUSTSEARCH_DEV_RUNNER_ENGINE_COMMAND: JSON.stringify([process.execPath, path.join(repoRoot, 'scripts/supervisor-conformance/fake-engine.mjs')]),
    JUSTSEARCH_DEV_RUNNER_FRONTEND_COMMAND: JSON.stringify([process.execPath, '-e', 'setInterval(()=>{},60000)']),
    JUSTSEARCH_DEV_RUNNER_BACKEND_PORT_TIMEOUT_MS: '10000', JUSTSEARCH_DEV_RUNNER_BACKEND_READY_TIMEOUT_MS: '10000', CI: '' };
  delete env.JUSTSEARCH_PROCESS_TABLE_FIXTURE;
  const args = transport === 'mcp' ? [path.join(repoRoot, 'scripts/dev/justsearch-dev-mcp.mjs')]
    : [path.join(repoRoot, 'scripts/dev/dev-runner.cjs'), 'start', '--json', '--skip-build', '--clean=none',
      '--api-port=0', `--ui-port=${uiPort}`, `--data-dir=${dataDir}`];
  const outputPath = path.join(root, 'cli.log');
  const fd = transport === 'cli' ? fs.openSync(outputPath, 'a') : null;
  const launcher = spawn(process.execPath, args, { cwd: repoRoot, env,
    stdio: transport === 'mcp' ? ['pipe', 'pipe', 'pipe'] : ['ignore', fd, fd] });
  if (fd !== null) fs.closeSync(fd);
  let diagnostics = '';
  let buffer = '';
  const replies = new Map();
  let requestId = 0;
  if (transport === 'mcp') {
    launcher.stderr.on('data', chunk => { diagnostics += chunk; });
    launcher.stdout.on('data', chunk => {
      buffer += chunk;
      let end;
      while ((end = buffer.indexOf('\n')) !== -1) {
        const line = buffer.slice(0, end); buffer = buffer.slice(end + 1);
        try { const message = JSON.parse(line); replies.set(message.id, message); } catch { /* non-JSON diagnostic */ }
      }
    });
  }
  const rpc = async (method, params) => {
    const id = ++requestId;
    launcher.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
    return until(`${method}: ${diagnostics}`, () => replies.get(id));
  };
  let run;
  let runDir;
  let births = [];
  try {
    if (transport === 'mcp') {
      await rpc('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'supervisor-output', version: '1' } });
      launcher.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' })}\n`);
      const response = await rpc('tools/call', { name: 'justsearch.dev.start', arguments: {
        apiPort: 0, uiPort, clean: 'none', skipBuild: true, hotReload: false,
        dataDir, waitLevel: 'ready_http', startTimeoutMs: 15000, waitTimeoutMs: 15000,
      } });
      const result = response.result?.structuredContent ?? JSON.parse(response.result?.content?.[0]?.text ?? '{}');
      assert.equal(result.ok, true, JSON.stringify(response));
    }
    run = await until('running stack', () => {
      const active = read(path.join(stateRoot, 'active.json'));
      if (active?.phase !== 'running') return null;
      return read(path.join(stateRoot, 'runs', active.runId, 'run.json'));
    });
    runDir = path.join(stateRoot, 'runs', run.runId);
    births = [run.pids.runnerPid, run.pids.backendRootPid, run.pids.frontendRootPid]
      .map(pid => identity.readLinuxProcess(pid).processIdentity);
    assert.equal(run.owner.owner.processIdentity.startTicks, ownerRow.processIdentity.startTicks, 'native owner identity reaches run record');
    assert.equal(run.owner.owner.creationTime, null, 'Linux owner needs no Windows timestamp');
    assert.ok(births.every(Boolean), 'all fixture identities recorded before any stop');
    const sequence = read(path.join(stateRoot, 'active.json')).lease.sequence;
    await sleep(500);
    assert.ok(read(path.join(stateRoot, 'active.json')).lease.sequence > sequence, 'live owner renews');
    if (readOnlyLog) {
      const logPath = path.resolve(repoRoot, run.logs.supervisor);
      fs.chmodSync(logPath, 0o444);
      assert.throws(() => fs.appendFileSync(logPath, 'probe'), { code: 'EACCES' }, 'the OS refuses this log before the owner exits');
    }
    if (transport === 'mcp') { const exited = once(launcher, 'exit'); launcher.kill(); await exited; }
    const ownerExited = once(owner, 'exit'); owner.kill(); await ownerExited;
    const endedAt = Date.now();
    await sleep(1000);
    const crash = fs.existsSync(crashPath) ? fs.readFileSync(crashPath, 'utf8') : '';
    assert.equal(gone(births[0]), false, `supervisor died ${Date.now() - endedAt}ms after owner exit; uncaught=${crash}`);
    const paused = read(path.join(stateRoot, 'active.json')).lease.sequence;
    await sleep(300);
    assert.equal(read(path.join(stateRoot, 'active.json')).lease.sequence, paused, 'ended owner pauses renewal');
    await until('reaped_abandoned stop report', () => read(path.join(runDir, 'stop-report.json'))?.disposition === 'reaped_abandoned');
    await until('active.json removed', () => !fs.existsSync(path.join(stateRoot, 'active.json')));
    await until('all stack processes ended', () => births.every(gone));
    assert.ok(Date.now() - endedAt >= 30000, 'declared 30s hold is respected');
    const exits = ledger.readLedger(ledger.ledgerDirectory(dataDir), 'engine').records.filter(record => record.type === 'engine-exit');
    assert.equal(exits.length, 1);
    assert.equal(exits[0].line.code, 0, JSON.stringify(exits));
    assert.equal(exits[0].line.exitClass, 'REQUESTED', JSON.stringify(exits));
    assert.equal(exits[0].line.forced, false, JSON.stringify(exits));
    let logPath = outputPath;
    if (!process.env.SUPERVISOR_OUTPUT_TEST_ROOT) {
      assert.ok(run.logs.supervisor, 'run.json makes the supervisor log findable');
      logPath = path.resolve(repoRoot, run.logs.supervisor);
      const logs = fs.readFileSync(logPath, 'utf8');
      if (readOnlyLog) assert.doesNotMatch(logs, /renewal paused|Reaping stack/, 'the read-only log cannot receive post-exit lines');
      else {
        assert.match(logs, /renewal paused/);
        assert.match(logs, /Reaping stack/);
      }
    }
    if (transport === 'cli') {
      const output = fs.readFileSync(outputPath, 'utf8');
      assert.match(output, /Reaping stack/);
      if (readOnlyLog) {
        assert.match(output, /renewal paused/);
        assert.match(output, /supervisor log unavailable.*EACCES/);
        assert.equal(output.match(/supervisor log unavailable/g)?.length, 1, 'one warning despite repeated log failures');
      }
    }
    console.log(`PASS ${transport}${readOnlyLog ? ' (read-only log)' : ''}: renewal paused, survived owner exit, orderly quit, active removed, reaped_abandoned; log=${logPath}`);
  } finally {
    // Only spawn-time identities / this test's retained child handles may be cleanup targets.
    for (const birth of births) if (!gone(birth)) {
      const result = identity.signalLinuxProcess(birth, 'SIGKILL');
      assert.equal(result.ok, true, JSON.stringify(result));
    }
    await until('fixture identities ended', () => births.every(gone), 5000);
    if (owner.exitCode === null && owner.signalCode === null) { const exited = once(owner, 'exit'); owner.kill(); await exited; }
    if (launcher.exitCode === null && launcher.signalCode === null) { const exited = once(launcher, 'exit'); launcher.kill(); await exited; }
    console.log(`Evidence retained: ${root}`);
  }
}

fs.mkdirSync(path.join(repoRoot, 'tmp'), { recursive: true });
if (process.platform === 'linux') {
  if (!process.argv.includes('--cli-only') && !process.argv.includes('--readonly-only')) await checkStack('mcp');
  if (!process.argv.includes('--mcp-only')) {
    if (!process.argv.includes('--readonly-only')) await checkStack('cli');
    await checkStack('cli', true);
  }
} else console.log('SKIP: real closed-pipe owner-exit reproduction requires Linux');
