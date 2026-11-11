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

test('spawn timing write failure warns while preserving the child exit status', () => {
  const root = fs.mkdtempSync(path.join(tmp, 'timing-spawn-write-failure-'));
  try {
    const result = spawnSync(process.execPath, ['--require', path.join(import.meta.dirname, 'timings-preload.cjs'), '-e', `
      const { spawn } = require('node:child_process');
      const child = spawn(process.execPath, ['-e', 'process.exit(7)'], {
        env: { ...process.env, JUSTSEARCH_ENGINE_INCARNATION: 'run/1' }, stdio: 'ignore' });
      child.on('exit', code => process.exit(code));
    `], { env: { ...process.env, JUSTSEARCH_CONFORMANCE_TIMINGS: path.join(root, 'missing', 'spawn.jsonl') }, encoding: 'utf8' });
    assert.equal(result.status, 7, result.stderr);
    assert.match(result.stderr, /timing warning.*ENOENT/);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('record names how each incarnation ended and the bounded tail of its own stderr', async () => {
  const { createLedgerWriter, ledgerDirectory } = await import('../dev/lib/exit-ledger.cjs');
  const root = fs.mkdtempSync(path.join(tmp, 'timing-ends-test-'));
  try {
    const now = Date.now();
    const timing = caseTimings({ adapter: 'tauri', testCase: { id: 'ends' }, policy: {}, root });
    const writer = createLedgerWriter({ ledgerDir: ledgerDirectory(path.join(root, 'data')), family: 'engine',
      writer: { role: 'dev-runner', pid: process.pid, startedAt: now }, clock: () => now });
    const first = { session: 'run', n: 1 }; const second = { session: 'run', n: 2 };
    assert.equal(writer.append('engine-start', { incarnation: first, pid: 123 }), true);
    assert.equal(writer.append('engine-exit', { incarnation: first, code: 1, codeName: 'FATAL_OR_UNCAUGHT',
      exitClass: 'UNEXPECTED', phase: 'STARTING', forced: false, observedAt: new Date(now + 90).toISOString() }), true);
    assert.equal(writer.append('engine-start', { incarnation: second, pid: 124 }), true);
    assert.equal(writer.append('engine-exit', { incarnation: second, code: null, signal: 9, signalName: 'SIGKILL',
      forced: true, observedAt: new Date(now + 900).toISOString() }), true);
    const logs = path.join(root, 'data/logs'); fs.mkdirSync(logs, { recursive: true });
    fs.writeFileSync(path.join(logs, 'engine.log'),
      `[fake-engine] x entryEpochMs=${now + 1} pid=123 incarnationRef=run/1\n`
      + `[fake-engine] x entryEpochMs=${now + 2} pid=125 incarnationRef=\n`);
    fs.writeFileSync(path.join(logs, `fake-engine-123-${now + 1}-run_1.stderr.log`), `${'x'.repeat(5000)}[fake-engine] listen failed: EADDRINUSE\n`);
    fs.writeFileSync(path.join(logs, `fake-engine-125-${now + 2}.stderr.log`), 'orphan line\n');
    timing.addWorkDir(root);
    const record = timing.finish(['failed']);
    assert.deepEqual(JSON.parse(fs.readFileSync(timing.file, 'utf8')).incarnations, record.incarnations);
    const [one, two, orphan] = record.incarnations;
    assert.deepEqual({ ...one, stderrTail: undefined }, { incarnation: 'run/1', pid: 123, workDir: root, stderrTail: undefined, stderrFile: `fake-engine-123-${now + 1}-run_1.stderr.log`,
      exit: { code: 1, codeName: 'FATAL_OR_UNCAUGHT', exitClass: 'UNEXPECTED', phase: 'STARTING', forced: false,
        observedAt: new Date(now + 90).toISOString() } });
    assert.equal(one.stderrTail.length, 2000);
    assert.match(one.stderrTail, /listen failed: EADDRINUSE\n$/);
    assert.deepEqual(two.exit, { code: null, signal: 9, signalName: 'SIGKILL', forced: true,
      observedAt: new Date(now + 900).toISOString() });
    assert.equal(two.stderrTail, null, 'no stderr written means none reported');
    assert.deepEqual(orphan, { incarnation: null, pid: 125, workDir: root, exit: null,
      stderrFile: `fake-engine-125-${now + 2}.stderr.log`, stderrTail: 'orphan line\n' },
      'an exit no supervisor recorded is never invented');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('the fake Engine keeps a copy of its stderr, including a fatal error, and only when it writes one', () => {
  const root = fs.mkdtempSync(path.join(tmp, 'timing-fake-stderr-test-'));
  try {
    const fake = path.join(import.meta.dirname, 'fake-engine.mjs');
    const run = (name, env) => {
      const dataDir = path.join(root, name);
      const result = spawnSync(process.execPath, [fake], { encoding: 'utf8',
        env: { ...process.env, JUSTSEARCH_FAKE_ENGINE_PLAN: '', JUSTSEARCH_DATA_DIR: dataDir, ...env } });
      const logs = path.join(dataDir, 'logs');
      const names = fs.existsSync(logs) ? fs.readdirSync(logs).filter(name => name.endsWith('.stderr.log')) : [];
      assert.ok(names.length <= 1, names.join());
      assert.ok(names.every(name => name.startsWith(`fake-engine-${result.pid}-`)), names.join());
      return { result, name: names[0], copy: names.length ? fs.readFileSync(path.join(logs, names[0]), 'utf8') : null };
    };
    const bootFail = run('boot-fail', { JUSTSEARCH_FAKE_ENGINE_MODE: 'boot-fail', JUSTSEARCH_FAKE_ENGINE_EXIT_AFTER_MS: '1',
      JUSTSEARCH_ENGINE_INCARNATION: 'shell-1-0x2a/3' });
    assert.match(bootFail.name, /^fake-engine-\d+-\d+-shell-1-0x2a_3\.stderr\.log$/);
    assert.equal(bootFail.result.status, 2);
    assert.equal(bootFail.copy, bootFail.result.stderr);
    assert.match(bootFail.copy, /boot failed \(simulated\)/);
    const plan = path.join(root, 'broken.plan.json'); fs.writeFileSync(plan, '{not json');
    const fatal = run('fatal', { JUSTSEARCH_FAKE_ENGINE_PLAN: plan });
    assert.equal(fatal.result.status, 1);
    assert.match(fatal.copy, /^\[(uncaughtException|unhandledRejection)\] SyntaxError/);
    assert.match(fatal.name, /^fake-engine-\d+-\d+\.stderr\.log$/, 'no reference: PID and entry time keep it unique');
    const quiet = run('quiet', { JUSTSEARCH_FAKE_ENGINE_MODE: 'die-before-manifest', JUSTSEARCH_FAKE_ENGINE_EXIT_AFTER_MS: '1' });
    assert.equal(quiet.result.status, 1);
    assert.equal(quiet.copy, null);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

test('a PID reused by a later incarnation never merges two incarnations or their stderr', async () => {
  const { createLedgerWriter, ledgerDirectory } = await import('../dev/lib/exit-ledger.cjs');
  for (const secondExit of [true, false]) {
    const root = fs.mkdtempSync(path.join(tmp, 'timing-reused-pid-test-'));
    try {
      const now = Date.now();
      const timing = caseTimings({ adapter: 'tauri', testCase: { id: 'reused' }, policy: {}, root });
      const writer = createLedgerWriter({ ledgerDir: ledgerDirectory(path.join(root, 'data')), family: 'engine',
        writer: { role: 'dev-runner', pid: process.pid, startedAt: now }, clock: () => now });
      const first = { session: 'run', n: 1 }; const second = { session: 'run', n: 2 };
      writer.append('engine-start', { incarnation: first, pid: 123 });
      writer.append('engine-exit', { incarnation: first, code: 1, observedAt: new Date(now + 90).toISOString() });
      writer.append('engine-start', { incarnation: second, pid: 123 });
      if (secondExit) writer.append('engine-exit', { incarnation: second, code: 7, observedAt: new Date(now + 300).toISOString() });
      const logs = path.join(root, 'data/logs'); fs.mkdirSync(logs, { recursive: true });
      fs.writeFileSync(path.join(logs, 'engine.log'),
        `[fake-engine] x entryEpochMs=${now + 1} pid=123 incarnationRef=run/1\n`
        + `[fake-engine] x entryEpochMs=${now + 200} pid=123 incarnationRef=run/2\n`);
      fs.writeFileSync(path.join(logs, `fake-engine-123-${now + 1}-run_1.stderr.log`), 'first death\n');
      fs.writeFileSync(path.join(logs, `fake-engine-123-${now + 200}-run_2.stderr.log`), 'second death\n');
      timing.addWorkDir(root);
      const ends = timing.finish(['failed']).incarnations;
      assert.deepEqual(ends.map(row => [row.incarnation, row.pid, row.exit?.code ?? null, row.stderrTail]), [
        ['run/1', 123, 1, 'first death\n'],
        ['run/2', 123, secondExit ? 7 : null, 'second death\n'],
      ], `second exit row ${secondExit ? 'present' : 'absent'}`);
    } finally { fs.rmSync(root, { recursive: true, force: true }); }
  }
});

test('an unreferenced Engine entry joins a ledger start by PID only when no other start reused that PID', async () => {
  const { createLedgerWriter, ledgerDirectory } = await import('../dev/lib/exit-ledger.cjs');
  const root = fs.mkdtempSync(path.join(tmp, 'timing-unreferenced-entry-test-'));
  try {
    const now = Date.now();
    const iso = offset => new Date(now + offset).toISOString();
    const timing = caseTimings({ adapter: 'tauri', testCase: { id: 'unreferenced' }, policy: {}, root });
    const writer = createLedgerWriter({ ledgerDir: ledgerDirectory(path.join(root, 'data')), family: 'engine',
      writer: { role: 'dev-runner', pid: process.pid, startedAt: now }, clock: () => now });
    writer.append('engine-start', { incarnation: { session: 'run', n: 1 }, pid: 123 });
    writer.append('engine-start', { incarnation: { session: 'run', n: 2 }, pid: 123 });
    writer.append('engine-start', { incarnation: { session: 'run', n: 3 }, pid: 124 });
    const logs = path.join(root, 'data/logs'); fs.mkdirSync(logs, { recursive: true });
    fs.writeFileSync(path.join(logs, 'engine.log'), [
      `[fake-engine] ${iso(1)} entryEpochMs=${now + 1} pid=123 incarnationRef=`,
      `[fake-engine] ${iso(5)} JUSTSEARCH_API_PORT=1`,
      `[fake-engine] ${iso(200)} entryEpochMs=${now + 200} pid=123 incarnationRef=`,
      `[fake-engine] ${iso(205)} JUSTSEARCH_API_PORT=2`,
      `[fake-engine] ${iso(400)} entryEpochMs=${now + 400} pid=124 incarnationRef=`,
    ].join('\n') + '\n');
    timing.addWorkDir(root);
    const events = timing.finish([]).events.filter(row => ['engineEntry', 'portEmitted'].includes(row.phase));
    assert.deepEqual(events.map(row => [row.phase, row.atMs - now, row.pid, row.incarnation]), [
      ['engineEntry', 1, 123, null], ['portEmitted', 5, 123, null],
      ['engineEntry', 200, 123, null], ['portEmitted', 205, 123, null],
      ['engineEntry', 400, 124, 'run/3'],
    ]);
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});
