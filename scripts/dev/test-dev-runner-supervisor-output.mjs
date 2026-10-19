#!/usr/bin/env node
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createRequire } from 'node:module';
import { Writable } from 'node:stream';

const require = createRequire(import.meta.url);
const helper = path.join(process.env.SUPERVISOR_OUTPUT_TEST_ROOT ?? path.resolve(import.meta.dirname, '../..'),
  'scripts/dev/lib/supervisor-output.cjs');
const { preserveSupervisorOutput } = require(helper);
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'supervisor-output-'));
const log = path.join(root, 'supervisor.log');

async function checkErrors() {
  for (const code of ['EPIPE', 'ERR_STREAM_DESTROYED', 'ERR_STREAM_WRITE_AFTER_END']) {
    const stream = new Writable({ write(_chunk, _encoding, callback) { callback(Object.assign(new Error(code), { code })); } });
    preserveSupervisorOutput(log, [stream]);
    stream.write(`${code}: first\n`);
    await new Promise(resolve => setImmediate(resolve));
    await new Promise(resolve => stream.write(`${code}: later\n`, resolve));
    assert.ok(fs.readFileSync(log, 'utf8').includes(`${code}: later`));
  }
  const stream = new Writable({ write(_chunk, _encoding, callback) { callback(); } });
  preserveSupervisorOutput(log, [stream]);
  const fatal = Object.assign(new Error('unexpected output failure'), { code: 'EIO' });
  assert.throws(() => stream.emit('error', fatal), error => error === fatal);
  console.log('PASS: closed streams keep logging; unrelated stream errors propagate');
}

async function checkLogFailure(logPath) {
  const output = { stdout: '', stderr: '' };
  const streams = ['stdout', 'stderr'].map(name => new Writable({
    write(chunk, _encoding, callback) { output[name] += chunk; callback(); },
  }));
  preserveSupervisorOutput(logPath, streams);
  // The callback and both consoles still work, and a shared log failure warns only once.
  await new Promise(resolve => streams[0].write('stdout first\n', resolve));
  streams[1].write('stderr line\n');
  streams[0].write('stdout later\n');
  assert.equal(output.stdout, 'stdout first\nstdout later\n');
  const lines = output.stderr.trimEnd().split('\n');
  assert.equal(lines.length, 2, output.stderr);
  assert.match(lines[0], /supervisor log.*(ENOENT|EACCES|EPERM|ENOSPC)/);
  assert.equal(lines[1], 'stderr line');
  console.log(`PASS: unwritable log preserves both consoles and reports failure once: ${logPath}`);
}

async function checkUnwritableLogs() {
  await checkLogFailure(path.join(root, 'deleted-directory', 'supervisor.log'));
  const readOnly = path.join(root, 'readonly.log');
  fs.writeFileSync(readOnly, '');
  fs.chmodSync(readOnly, 0o444);
  try {
    // Root can write mode 0444: do not claim a read-only check unless the OS enforces it.
    // POSIX refuses with EACCES, Windows with EPERM.
    let refused = false;
    try { fs.appendFileSync(readOnly, 'probe'); } catch (error) { assert.ok(['EACCES', 'EPERM'].includes(error.code), error.code); refused = true; }
    if (refused) await checkLogFailure(readOnly);
    else console.log('SKIP: OS does not enforce read-only mode for this user');
  } finally { fs.chmodSync(readOnly, 0o644); }
  const append = fs.appendFileSync;
  fs.appendFileSync = () => { throw Object.assign(new Error('disk full'), { code: 'ENOSPC' }); };
  try { await checkLogFailure(path.join(root, 'disk-full.log')); }
  finally { fs.appendFileSync = append; }

  let delivered = '';
  const stdout = new Writable({ write(chunk, _encoding, callback) { delivered += chunk; callback(); } });
  const stderr = new Writable({ write(_chunk, _encoding, callback) { callback(); } });
  stderr.write = () => { throw new Error('diagnostic console unavailable'); };
  preserveSupervisorOutput(path.join(root, 'deleted-directory', 'supervisor.log'), [stdout, stderr]);
  assert.doesNotThrow(() => stdout.write('line despite diagnostic failure'));
  assert.equal(delivered, 'line despite diagnostic failure');
  console.log('PASS: a failed warning cannot prevent console delivery');
}

async function checkRealPipe(preserve, streamName, logPath = log) {
  const program = `
    ${preserve ? `require(${JSON.stringify(helper)}).preserveSupervisorOutput(${JSON.stringify(logPath)});` : ''}
    process.on('uncaughtExceptionMonitor', error => process.send(error.code));
    process.send('ready');
    process.stdin.once('data', () => {
      process.${streamName}.write('${streamName}: after reader closed\\n');
      setTimeout(() => {
        process.${streamName}.write('${streamName}: still logging\\n');
        process.send('survived', () => process.disconnect());
      }, 50);
      process.stdin.pause();
    });`;
  const child = spawn(process.execPath, ['-e', program], { stdio: ['pipe', 'pipe', 'pipe', 'ipc'] });
  // Every assertion waits for this unreaped child, so its retained handle cannot name a reused PID.
  const exited = once(child, 'exit');
  const messages = [];
  child.on('message', message => messages.push(message));
  child.stdout.resume();
  child.stderr.resume();
  const deadline = setTimeout(() => child.kill(), 5000);
  try {
    await once(child, 'message');
    child[streamName].destroy();
    await once(child[streamName], 'close');
    child.stdin.end('write');
    const [code] = await exited;
    if (preserve) {
      assert.equal(code, 0, JSON.stringify(messages));
      assert.ok(messages.includes('survived'));
      if (logPath === log) assert.ok(fs.readFileSync(log, 'utf8').includes(`${streamName}: after reader closed\n${streamName}: still logging`));
    } else {
      assert.equal(code, 1, JSON.stringify(messages));
      assert.ok(messages.includes('EPIPE'));
    }
    console.log(`PASS: real closed ${streamName} pipe ${preserve ? `survives with ${logPath === log ? 'a working' : 'an unwritable'} log` : 'reproduces unhandled EPIPE without protection'}`);
  } finally { clearTimeout(deadline); }
}

try {
  await checkErrors();
  await checkUnwritableLogs();
  if (process.platform === 'linux') {
    for (const streamName of ['stdout', 'stderr']) {
      await checkRealPipe(false, streamName);
      await checkRealPipe(true, streamName);
      await checkRealPipe(true, streamName, path.join(root, 'deleted-directory', 'supervisor.log'));
    }
  } else console.log('SKIP: real closed-pipe reproduction requires Linux');
} finally { fs.rmSync(root, { recursive: true, force: true }); }
