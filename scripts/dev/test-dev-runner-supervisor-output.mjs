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
const { preserveSupervisorOutput } = require('./lib/supervisor-output.cjs');
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
  const failedLog = new Writable({ write(_chunk, _encoding, callback) { callback(); } });
  preserveSupervisorOutput(path.join(root, 'missing', 'output.log'), [failedLog]);
  assert.throws(() => failedLog.write('must report log failure'), { code: 'ENOENT' });
  console.log('PASS: closed streams keep logging; unrelated stream and log errors propagate');
}

async function checkRealPipe(preserve, streamName) {
  const helper = path.join(import.meta.dirname, 'lib/supervisor-output.cjs');
  const program = `
    ${preserve ? `require(${JSON.stringify(helper)}).preserveSupervisorOutput(${JSON.stringify(log)});` : ''}
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
      assert.ok(fs.readFileSync(log, 'utf8').includes(`${streamName}: after reader closed\n${streamName}: still logging`));
    } else {
      assert.equal(code, 1, JSON.stringify(messages));
      assert.ok(messages.includes('EPIPE'));
    }
    console.log(`PASS: real closed ${streamName} pipe ${preserve ? 'survives and keeps both lines in the run log' : 'reproduces unhandled EPIPE without protection'}`);
  } finally { clearTimeout(deadline); }
}

try {
  await checkErrors();
  if (process.platform === 'linux') {
    for (const streamName of ['stdout', 'stderr']) {
      await checkRealPipe(false, streamName);
      await checkRealPipe(true, streamName);
    }
  } else console.log('SKIP: real closed-pipe reproduction requires Linux');
} finally { fs.rmSync(root, { recursive: true, force: true }); }
