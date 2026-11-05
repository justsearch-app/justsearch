#!/usr/bin/env node
import './lib/test-fixture-processes.cjs';
// A start queued for machine admission has no run a caller could stop. With --stop-with-stdin it
// ends when its caller does; the stand-in for admission queues until its own stdin closes.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';

const runner = path.join(import.meta.dirname, 'dev-runner.cjs');
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function queuedStart(args) {
  const bin = fs.mkdtempSync(path.join(os.tmpdir(), 'dev-runner-queue-'));
  const queued = path.join(bin, 'queued');
  fs.writeFileSync(path.join(bin, 'python3'), `#!/bin/sh\n: > "${queued}"\nexec cat > /dev/null\n`, { mode: 0o755 });
  const child = spawn(process.execPath, [runner, 'start', '--json', '--skip-build', ...args], {
    env: { ...process.env, PATH: `${bin}${path.delimiter}${process.env.PATH}` },
    stdio: ['pipe', 'ignore', 'ignore'],
  });
  const exited = new Promise((resolve) => child.once('exit', (code) => resolve(code)));
  return { bin, child, exited, queued };
}

async function untilQueued(start) {
  for (let i = 0; i < 300 && !fs.existsSync(start.queued); i++) await delay(100);
  assert.ok(fs.existsSync(start.queued), 'start never reached admission');
}

test('a start queued for admission ends with its caller', { skip: process.platform !== 'linux' }, async () => {
  const start = queuedStart(['--stop-with-stdin']);
  try {
    await untilQueued(start);
    start.child.stdin.end();
    const code = await Promise.race([start.exited, delay(10000).then(() => 'still running')]);
    assert.equal(code, 1);
  } finally {
    start.child.kill('SIGKILL');
    fs.rmSync(start.bin, { recursive: true, force: true });
  }
});

test('without the flag a closed stdin leaves the queued start alone', { skip: process.platform !== 'linux' }, async () => {
  const start = queuedStart([]);
  try {
    await untilQueued(start);
    start.child.stdin.end();
    const code = await Promise.race([start.exited, delay(1500).then(() => 'still running')]);
    assert.equal(code, 'still running');
  } finally {
    start.child.kill('SIGKILL');
    fs.rmSync(start.bin, { recursive: true, force: true });
  }
});
