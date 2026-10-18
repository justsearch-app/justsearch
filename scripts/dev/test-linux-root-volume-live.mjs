#!/usr/bin/env node
// Run: flock /tmp/js-linux-port/devstack.lock node scripts/dev/test-linux-root-volume-live.mjs --disposable-mounts
// Uses the real Engine index half and KnowledgeClient root/search interfaces, as the Windows
// EngineRootVolumeSwapTest does. No HTTP trust policy or ingest approval is bypassed or modified.
import assert from 'node:assert/strict';
import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const exec = promisify(execFile);
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
assert.equal(process.platform, 'linux', 'this campaign requires Linux');
assert(process.argv.includes('--disposable-mounts'), 'pass --disposable-mounts to create disposable loop filesystems');
const base = path.join(repo, 'tmp');
fs.mkdirSync(base, { recursive: true });
const campaign = fs.mkdtempSync(path.join(base, 'linux-root-volume ü-'));
const point = path.join(campaign, 'point');
fs.mkdirSync(point);
const loops = new Map();
let mounted = false;
let testProcess;
let testFinished = false;
const evidence = [];
const run = async (program, args) => {
  const result = await exec(program, args, { cwd: repo, maxBuffer: 4 * 1024 * 1024 });
  return result.stdout.trim();
};
const sudo = (...args) => run('sudo', ['-n', ...args]);
const record = (event, details = {}) => {
  const item = { event, ...details };
  evidence.push(item);
  console.log(JSON.stringify(item));
};

async function quickHealth() {
  const child = spawn(process.execPath, ['scripts/dev/justsearch-dev-mcp.mjs'], {
    cwd: repo, stdio: ['pipe', 'pipe', 'pipe'],
    env: { ...process.env, JUSTSEARCH_DEV_RUNNER_STATE_ROOT: path.join(campaign, 'dev-state') },
  });
  let buffer = '';
  let errors = '';
  let nextId = 1;
  const pending = new Map();
  child.stderr.on('data', chunk => { errors = (errors + chunk).slice(-8000); });
  const closed = new Promise(resolve => child.once('close', resolve));
  child.stdout.on('data', chunk => {
    buffer += chunk;
    let newline;
    while ((newline = buffer.indexOf('\n')) !== -1) {
      const line = buffer.slice(0, newline);
      buffer = buffer.slice(newline + 1);
      let message;
      try { message = JSON.parse(line); } catch { continue; }
      pending.get(message.id)?.(message);
    }
  });
  const call = (method, params) => new Promise((resolve, reject) => {
    const id = nextId++;
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`${method} timed out: ${errors}`));
    }, 30_000);
    pending.set(id, message => {
      clearTimeout(timer);
      pending.delete(id);
      resolve(message);
    });
    child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
  });
  try {
    const initialized = await call('initialize', { protocolVersion: '2024-11-05',
      capabilities: {}, clientInfo: { name: 'linux-root-volume-live', version: '1' } });
    assert(!initialized.error, `MCP initialization failed: ${JSON.stringify(initialized)}`);
    child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized', params: {} })}\n`);
    const response = await call('tools/call', { name: 'justsearch.dev.quick_health',
      arguments: { probe: false, detail: 'summary' } });
    assert(!response.error && !response.result?.isError, `quick_health refused: ${JSON.stringify(response)}`);
    record('quick_health', { response });
  } finally {
    child.stdin.end();
    // Closing stdin is the MCP server's normal shutdown path; await the original child handle.
    await closed;
  }
}

async function unmount() {
  if (!mounted) return;
  await sudo('umount', point);
  mounted = false;
}

async function mount(stage) {
  await unmount();
  if (stage !== 'empty') {
    if (stage === 'tmpfs') {
      await sudo('mount', '-t', 'tmpfs', '-o', `size=16m,uid=${process.getuid()},gid=${process.getgid()}`, 'tmpfs', point);
    } else {
      assert(loops.has(stage), `unknown mount stage ${stage}`);
      await sudo('mount', loops.get(stage).device, point);
    }
    mounted = true;
    // mkfs creates an empty root-owned lost+found. Remove that fixture-only directory so an
    // unrelated unreadable subfolder cannot determine the root's walk status.
    const recoveryDirectory = path.join(point, 'lost+found');
    if (fs.existsSync(recoveryDirectory)) fs.rmdirSync(recoveryDirectory);
    if (stage === 'B' || stage === 'tmpfs') {
      fs.writeFileSync(path.join(point, 'alpha.txt'), 'pangolin is on the wrong filesystem');
      fs.writeFileSync(path.join(point, 'extra.txt'), 'okapi is on the wrong filesystem');
    }
  }
  record('mounted', { stage, device: fs.statSync(point).dev });
}

try {
  // The outer devstack lock is the admission lease; quick_health also exercises the read surface.
  await quickHealth();
  for (const name of ['A', 'B']) {
    const image = path.join(campaign, `${name}.img`);
    fs.closeSync(fs.openSync(image, 'wx'));
    fs.truncateSync(image, 48 * 1024 * 1024);
    await run('mkfs.ext4', ['-q', '-F', '-E', `root_owner=${process.getuid()}:${process.getgid()}`, image]);
    const device = await sudo('losetup', '--find', '--show', image);
    loops.set(name, { device, image });
    record('loop-created', { name, device, image });
  }
  await run('udevadm', ['settle']);
  await mount('A');
  const log = fs.openSync(path.join(campaign, 'gradle.log'), 'w');
  testProcess = spawn('flock', ['/tmp/js-linux-port/gradle.lock', './gradlew',
    ':modules:app-engine:test', '--tests', '*EngineLinuxRootVolumeLiveTest', '-PskipWebBuild=true', '--rerun'], {
    cwd: repo, stdio: ['ignore', log, log],
    env: { ...process.env, JUSTSEARCH_LINUX_ROOT_VOLUME_CAMPAIGN_DIR: campaign },
  });
  fs.closeSync(log);
  record('test-started', { pid: testProcess.pid, campaign });
  let spawnError;
  const done = new Promise(resolve => {
    testProcess.once('error', error => { spawnError = error; testFinished = true; resolve(1); });
    testProcess.once('close', code => { testFinished = true; resolve(code); });
  });
  while (!testFinished) {
    const request = path.join(campaign, 'request');
    if (fs.existsSync(request)) {
      const stage = fs.readFileSync(request, 'utf8');
      fs.unlinkSync(request);
      try {
        await mount(stage);
        const ack = path.join(campaign, 'ack.tmp');
        fs.writeFileSync(ack, stage);
        fs.renameSync(ack, path.join(campaign, 'ack'));
      } catch (error) {
        fs.writeFileSync(path.join(campaign, 'mount-error'), String(error));
        record('mount-error', { error: String(error) });
      }
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  const exitCode = await done;
  if (spawnError) throw spawnError;
  assert.equal(exitCode, 0, `Engine campaign failed; see ${path.join(campaign, 'gradle.log')}`);
  for (const stage of ['indexed-A', 'unmounted-preserved-A', 'B-admission-and-prune-refused',
    'corrupt-ledger-preserved-A', 'lost-ledger-preserved-A', 'A-recovered-indexing-and-pruning',
    'known-identity-to-tmpfs-preserved-A', 'tmpfs-unmounted-known-preserved-A', 'A-returned-cleanly',
    'initially-unknown-tmpfs-indexed-and-pruned', 'initially-unknown-tmpfs-restarted-cleanly']) {
    assert(fs.existsSync(path.join(campaign, `${stage}.passed`)), `missing evidence for ${stage}`);
    record('pass', { stage });
  }
} finally {
  // The test JVM has ended before any mount is removed. Never discover kill authority by PID/name.
  assert(!testProcess || testFinished, 'test process must exit before mount cleanup');
  const failures = [];
  try { await unmount(); } catch (error) { failures.push(error); }
  if (!mounted) {
    for (const { device, image } of [...loops.values()].reverse()) {
      try {
        const backing = await sudo('losetup', '--noheadings', '--output', 'BACK-FILE', device);
        assert.equal(fs.realpathSync(backing), fs.realpathSync(image), 'loop backing identity changed; refusing detach');
        await sudo('losetup', '--detach', device);
        fs.unlinkSync(image);
        record('loop-cleaned', { device });
      } catch (error) { failures.push(error); }
    }
  }
  fs.writeFileSync(path.join(campaign, 'evidence.json'), JSON.stringify(evidence, null, 2));
  console.log(`Evidence: ${campaign}`);
  if (failures.length) throw new AggregateError(failures, 'disposable mount cleanup failed');
}
