import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { spawnSync } from 'node:child_process';
import { caseTimings } from './timings.mjs';
import { summarize } from './summarize-timings.mjs';

const tmp = path.resolve(import.meta.dirname, '../../tmp');
fs.mkdirSync(tmp, { recursive: true });

test('partial failures persist their budget, completed wait, and supervisor output', () => {
  const root = fs.mkdtempSync(path.join(tmp, 'timing-test-'));
  try {
    const timing = caseTimings({ adapter: 'dev-runner', testCase: { id: 'partial' },
      policy: { startDeadlineMs: 4000, gracefulStopDeadlineMs: 1500, quitDeadlineMs: 8000, quitForceMarginMs: 300 }, root });
    timing.addWorkDir(root);
    timing.launch(root);
    timing.launch(root);
    const end = timing.wait('first running', 25000); end();
    timing.attach({ workDir: root, child: { pid: 123 }, output: { stdout: 'refused start', stderr: 'spawn error' } });
    const actual = timing.finish(['assertion failure']);
    assert.deepEqual(JSON.parse(fs.readFileSync(timing.file, 'utf8')), actual);
    assert.equal(actual.budgetsMs.quitAssertion, 10000);
    assert.equal(actual.events.filter(row => row.phase === 'supervisorSpawnRequested').length, 2);
    assert.equal(actual.waits[0].budgetMs, 25000);
    assert.ok(actual.waits[0].endedAtMs >= actual.waits[0].startedAtMs);
    assert.equal(fs.readFileSync(path.join(root, 'supervisor-123.stderr.log'), 'utf8'), 'spawn error');
    assert.equal(actual.events.some(row => row.phase === 'engineEntry'), false, 'never fabricate an Engine start');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('spawn witness precedes the actual child entry without changing the exit status', () => {
  const root = fs.mkdtempSync(path.join(tmp, 'timing-spawn-test-'));
  try {
    const target = path.join(root, 'spawn.jsonl');
    const preload = path.join(import.meta.dirname, 'timings-preload.cjs');
    const result = spawnSync(process.execPath, ['--require', preload, '-e', `
      const { spawn } = require('node:child_process');
      const child = spawn(process.execPath, ['-e', 'console.log(Date.now());process.exit(7)'], {
        env: { ...process.env, JUSTSEARCH_ENGINE_INCARNATION: 'run/1' }, stdio: ['ignore', 'pipe', 'inherit'] });
      child.stdout.pipe(process.stdout);child.on('exit', code => process.exit(code));
    `], { env: { ...process.env, JUSTSEARCH_CONFORMANCE_TIMINGS: target }, encoding: 'utf8' });
    assert.equal(result.status, 7, result.stderr);
    const row = JSON.parse(fs.readFileSync(target, 'utf8').trim());
    assert.equal(row.phase, 'spawnRequested');
    assert.equal(row.incarnation, 'run/1');
    assert.ok(Number(result.stdout.trim()) >= row.atMs);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('summary pairs the same incarnation and excludes missing phases, with nearest-rank percentiles', () => {
  const records = Array.from({ length: 20 }, (_, i) => ({ adapter: 'dev-runner', platform: 'linux', measurements: [],
    events: [{ phase: 'spawnRequested', incarnation: 'r/1', atMs: 100 },
      { phase: 'engineEntry', incarnation: 'r/1', atMs: 101 + i },
      { phase: 'portEmitted', incarnation: 'r/2', atMs: 900 }] }));
  assert.deepEqual(summarize(records), [{ phase: 'dev-runner/linux/spawnToEntry', n: 20, p50: 10, p95: 19, max: 20 }]);
});

test('record joins Engine entry and ports to the durable quit, force and exit timestamps', async () => {
  const { createLedgerWriter, ledgerDirectory } = await import('../dev/lib/exit-ledger.cjs');
  const root = fs.mkdtempSync(path.join(tmp, 'timing-ledger-test-'));
  try {
    const now = Date.now();
    const iso = offset => new Date(now + offset).toISOString();
    const timing = caseTimings({ adapter: 'dev-runner', testCase: { id: 'complete' },
      policy: { startDeadlineMs: 4000, gracefulStopDeadlineMs: 1500, quitDeadlineMs: 8000, quitForceMarginMs: 300 }, root });
    const incarnation = { session: 'test-run', n: 1 };
    const writer = createLedgerWriter({ ledgerDir: ledgerDirectory(path.join(root, 'data')), family: 'engine',
      writer: { role: 'dev-runner', pid: process.pid, startedAt: now }, clock: () => now });
    assert.equal(writer.append('engine-start', { incarnation, pid: 123 }), true);
    assert.equal(writer.append('engine-intent', { incarnation, reason: 'QUIT', channel: 'REQUEST_FILE', requestedAt: iso(30) }), true);
    assert.equal(writer.append('engine-exit', { incarnation, terminateCalledAt: iso(7730), observedAt: iso(7750) }), true);
    const logs = path.join(root, 'data/logs'); fs.mkdirSync(logs, { recursive: true });
    fs.writeFileSync(path.join(logs, 'engine.log'),
      `[fake-engine] ${iso(10)} entryEpochMs=${now + 10} pid=123 incarnationRef=test-run/1\n`
      + `[fake-engine] ${iso(15)} JUSTSEARCH_API_PORT=1234\n`);
    fs.writeFileSync(path.join(root, 'spawn-timings.jsonl'), JSON.stringify({ phase: 'spawnRequested', atMs: now, incarnation: 'test-run/1', source: 'test' }) + '\n');
    timing.addWorkDir(root);
    timing.state({ runId: 'test-run', incarnation: 1, state: 'running', readyAt: iso(20) }, 'state');
    timing.state({ runId: 'test-run', incarnation: 1, state: 'stopped', updatedAt: iso(7760) }, 'state');
    const record = timing.finish([]);
    const values = Object.fromEntries(summarize([record]).map(row => [row.phase.split('/').at(-1), row.max]));
    assert.deepEqual(values, { entryToPort: 5, exitToStopped: 10, forceToExit: 20, portToReady: 5,
      quitToExit: 7720, quitToForce: 7700, spawnToEntry: 10 });
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('a starting successor cannot inherit its predecessor readiness timestamp', () => {
  const root = fs.mkdtempSync(path.join(tmp, 'timing-successor-test-'));
  try {
    const timing = caseTimings({ adapter: 'dev-runner', testCase: { id: 'successor' }, policy: {}, root });
    timing.state({ runId: 'run', incarnation: 1, state: 'running', readyAt: '2026-10-20T04:00:00.000Z' }, 'state');
    timing.state({ runId: 'run', incarnation: 2, state: 'starting', readyAt: '2026-10-20T04:00:00.000Z' }, 'state');
    assert.equal(timing.record.events.filter(row => row.phase === 'ready').length, 1);
    timing.state({ runId: 'run', incarnation: 2, state: 'running', readyAt: '2026-10-20T04:00:02.000Z' }, 'state');
    assert.equal(timing.record.events.find(row => row.phase === 'ready' && row.incarnation === 'run/2').atMs,
      Date.parse('2026-10-20T04:00:02.000Z'));
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('quit during cooldown is witnessed even without a live Engine intent', () => {
  const root = fs.mkdtempSync(path.join(tmp, 'timing-cooldown-test-'));
  try {
    const timing = caseTimings({ adapter: 'dev-runner', testCase: { id: 'cooldown' }, policy: {}, root });
    const atMs = Date.now();
    timing.addWorkDir(root);
    timing.quitRequested({ runId: 'run', incarnation: 1, state: 'restarting' }, root, atMs);
    const record = timing.finish([]);
    assert.deepEqual(record.events, [{ phase: 'quitRequested', atMs, incarnation: 'run/1',
      workDir: root, source: 'harness control request' }]);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('readiness is retained while the adapter waits for a terminal process exit', async () => {
  const root = fs.mkdtempSync(path.join(tmp, 'timing-terminal-test-'));
  let timing;
  try {
    timing = caseTimings({ adapter: 'dev-runner', testCase: { id: 'terminal' }, policy: {}, root });
    timing.addWorkDir(root);
    const runtime = path.join(root, 'data/runtime'); fs.mkdirSync(runtime, { recursive: true });
    const statePath = path.join(runtime, 'supervisor.v1.json');
    const readyAt = new Date().toISOString();
    fs.writeFileSync(statePath, JSON.stringify({ runId: 'run', incarnation: 2, state: 'running', readyAt }));
    timing.attach({ workDir: root, statePath, child: { pid: 123 }, output: { stdout: '', stderr: '' } });
    await new Promise(resolve => setTimeout(resolve, 80));
    fs.writeFileSync(statePath, JSON.stringify({ runId: 'run', incarnation: 2, state: 'exhausted', readyAt }));
    const record = timing.finish([]); timing = null;
    assert.equal(record.events.find(row => row.phase === 'ready' && row.incarnation === 'run/2')?.atMs,
      Date.parse(readyAt));
  } finally {
    timing?.finish([]);
    fs.rmSync(root, { recursive: true, force: true });
  }
});
