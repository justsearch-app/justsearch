import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import observer from './lib/engine-exit-observer.cjs';
import identity from './lib/process-identity.cjs';

test('spawn-bound exit is delivered once after delayed initialization', async () => {
  const child = new EventEmitter();
  const observed = observer.observeEngineExit(child);
  assert.equal(child.listenerCount('exit'), 1);
  child.emit('exit', 1);
  await new Promise(resolve => setTimeout(resolve, 20));
  const codes = [];
  observed.activate(code => codes.push(code));
  assert.equal(observed.exited, true);
  child.emit('exit', 2);
  assert.deepEqual(codes, [1]);
  assert.throws(() => observed.activate(() => {}), /already activated/);
});

test('identity capture is asynchronous and bounds the OS query', async () => {
  let options, completed = false;
  const capture = identity.readProcessTableAsync({ platform: 'win32', now: () => 123,
    exec: (_exe, _args, opts, callback) => {
      options = opts;
      setTimeout(() => { completed = true; callback(null, '[{"ProcessId":12}]'); }, 20);
    },
  });
  assert.equal(completed, false);
  assert.equal(options.timeout, 5000);
  assert.deepEqual(await capture, { ok: true, table: [{ ProcessId: 12 }], readAt: 123 });
  const failed = await identity.readProcessTableAsync({ platform: 'win32',
    exec: (_exe, _args, opts, callback) => callback(new Error('query timed out'), ''),
  });
  assert.equal(failed.ok, false);
});

// Pure ownership regression: no ports, real process termination, or dev stack.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import runner from './dev-runner.cjs';

test('exhaustion cleans matching children but refuses births 1 to 1000 ms later', t => {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../tmp');
  fs.mkdirSync(root, { recursive: true });
  const dir = fs.mkdtempSync(path.join(root, 'terminal-children-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  fs.mkdirSync(path.join(dir, 'runtime'));
  const starts = [0, 1, 500, 999, 1000];
  const executable = path.join(root, 'native-child.exe');
  fs.writeFileSync(path.join(dir, 'runtime/manifest.json'), JSON.stringify({
    schemaVersion: 2, children: starts.map((_, index) => ({
      id: String(index), pid: 100 + index, startedAt: '2026-10-01T00:00:00.123Z', executable,
    })),
  }));
  const inspect = pid => ({ alive: true, executable,
    startedAt: new Date(Date.parse('2026-10-01T00:00:00.123Z') + starts[pid - 100]).toISOString(),
  });
  const killed = [];
  const terminate = pid => { killed.push(pid); return true; };
  for (const state of ['starting', 'running', 'stopping', 'restarting']) {
    assert.deepEqual(runner.__test.cleanupRegisteredChildrenForSupervisorState(
      state, dir, inspect, terminate), []);
  }
  assert.deepEqual(killed, []);
  const results = runner.__test.cleanupRegisteredChildrenForSupervisorState(
    'exhausted', dir, inspect, terminate);
  assert.deepEqual(killed, [100]);
  assert.deepEqual(results.map(result => result.outcome),
    ['terminated', 'identity-mismatch', 'identity-mismatch', 'identity-mismatch', 'identity-mismatch']);
  const ticks = runner.__test.cleanupRegisteredChildrenForTerminal(dir,
    pid => ({ alive: true, executable, startedAt: pid === 100
      ? '2026-10-01T00:00:00.1234567Z' : inspect(pid).startedAt }), () => true);
  assert.equal(ticks[0].outcome, 'terminated', 'cross-source precision is the same represented millisecond');
});
