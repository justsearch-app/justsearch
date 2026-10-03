import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { EventEmitter } from 'node:events';
import census from './lib/stop-exit-census.cjs';
import runner from './dev-runner.cjs';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const start = '2026-10-01T00:00:00.1234567Z', birth = census.fileTimeFromInstant(start);
const exe = path.join(root, 'tmp/native-child.exe');
const engine = { pid: 10, creationFileTimeUtc: birth, executable: exe, role: 'engine' };
const run = { pids: { backendRootPid: 10 }, exitCensusEngine: engine };
const child = { id: 'child', pid: 11, kind: 'LLAMA_SERVER', startedAt: start, executable: exe };
const manifest = { pid: 10, children: [child, { ...child, pid: 12, kind: 'EXTRACTION' }] };
const input = () => census.exitTargets(run, manifest);
test('B11 exit targets include Engine and every registered native/JVM child; exact birth precision is retained', () => {
  assert.equal(BigInt(birth) % 10000n, 4567n);
  assert.equal(census.fileTimeFromInstant('2026-10-01T00:00:00.123456789Z'), null);
  assert.deepEqual(input().targets.map(t => t.role), ['engine', 'llama-server', 'extraction-child']);
  assert.equal(input().gaps.length, 0);
  assert.equal(census.exitTargets(run, { ...manifest, pid: 99 }).gaps[0].role, 'engine');
  assert.ok(census.exitTargets(run, { pid: 10, children: [{ ...child, executable: '' }] }).gaps.length);
});
test('exit census rejects PID reuse, missing native exits, and unregistered historical children', () => {
  const i = input(), exits = i.targets.map(t => ({ ...t, exitCode: 0, atMs: 1000 }));
  assert.equal(census.exitResult(i, { processExits: exits }, 'receipt').exitAccountingComplete, true);
  exits[1].creationFileTimeUtc = (BigInt(birth) + 1n).toString();
  assert.equal(census.exitResult(i, { processExits: exits }, 'receipt').exitAccountingComplete, false);
  const missing = census.exitTargets(run, manifest, [{ pid: 99, creationFileTimeUtc: birth, role: 'extraction-child' }]);
  assert.match(missing.gaps[0].reason, /registry removes completed children.*no historical exit codes/);
});
test('B11 millisecond starts bind to exact owned CIM births without tolerance or ambiguous reuse', () => {
  const startedAt = '2026-10-01T00:00:00.123Z';
  const current = { pid: 11, executable: exe, creationFileTimeUtc: birth };
  const bound = census.exitTargets(run, { pid: 10, children: [{ ...child, startedAt }] }, [current]);
  assert.equal(bound.targets[1].creationFileTimeUtc, birth);
  assert.equal(bound.targets[1].creationTimeSource, 'CIM');
  assert.equal(bound.gaps.length, 0);
  const ambiguous = census.exitTargets(run, { pid: 10, children: [{ ...child, startedAt }] },
    [current, { ...current, creationFileTimeUtc: (BigInt(birth) + 1n).toString() }]);
  assert.match(ambiguous.gaps[0].reason, /multiple owned births/);
});
test('native exception survives teardown; forced-stop success does not fabricate exit codes', () => {
  const i = input(), exits = i.targets.map(t => ({ ...t, exitCode: 0, atMs: 1000 }));
  exits[1].exitCode = -1073741819; exits[2].exitCode = 1;
  const result = census.exitResult(i, { processExits: exits, forcedPids: [11, 12] }, 'os-receipt');
  assert.equal(result.processExits[1].expectedTermination, false);
  assert.equal(result.processExits[2].expectedTermination, true);
  assert.equal(census.exitResult(i, { processExits: exits }, 'receipt').processExits[2].expectedTermination, false);
  assert.equal(census.exitResult(i, { forcedPids: [10, 11, 12] }, 'receipt').exitAccountingComplete, false);
  const report = runner.__test.buildStopReport({ ...result, runId: 'test' });
  assert.deepEqual(report.processExits, result.processExits);
  assert.equal(report.exitAccountingComplete, true);
});
test('Engine-owned fallback accepts only code 1 with matching owner/child identity and prior intent', t => {
  const dir = fs.mkdtempSync(path.join(root, 'tmp/engine-intent-test-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const startedAt = '2026-10-01T00:00:00.123Z';
  const extraction = { ...manifest.children[1], id: 'extraction', startedAt };
  const i = census.exitTargets(run, { ...manifest, children: [extraction] },
    [{ pid: extraction.pid, executable: exe, creationFileTimeUtc: birth }]);
  const at = Date.parse(startedAt) + 2000;
  i.observedAfterMs = at - 100;
  const intent = { enginePid: 10, engineStartedAt: startedAt, child: extraction,
    requestedAtMs: at, reason: 'graceful-close-fallback' };
  const exits = i.targets.map(t => ({ ...t, exitCode: t.kind === 'EXTRACTION' ? 1 : 0, atMs: at + 1 }));
  const log = path.join(dir, 'backend.stderr.log');
  fs.writeFileSync(log, census.TERMINATION_INTENT_PREFIX + JSON.stringify(intent) + '\n');
  const receipts = [{ ...intent, source: log }];
  const classify = intents => census.exitResult(i, { processExits: exits, engineTerminationIntents: intents }, 'os-exits');
  assert.equal(classify(receipts).processExits[1].expectedTermination, true);
  assert.equal(classify(receipts).processExits[1].terminationIntent.source, log);
  assert.deepEqual(census.readEngineTerminationIntents(log), receipts);
  assert.equal(classify([]).processExits[1].expectedTermination, false);
  for (const bad of [
    { ...intent, enginePid: 99 }, { ...intent, engineStartedAt: '2026-10-01T00:00:00.124Z' },
    { ...intent, reason: 'crash' }, { ...intent, requestedAtMs: at + 2 }, { ...intent, requestedAtMs: at - 101 },
    ...[{ pid: 99 }, { id: 'reused' }, { kind: 'LLAMA_SERVER' }, { executable: exe + '.other' },
      { startedAt: '2026-10-01T00:00:00.1230001Z' }].map(change => ({ ...intent, child: { ...extraction, ...change } })),
  ]) assert.equal(classify([bad]).processExits[1].expectedTermination, false, JSON.stringify(bad));
  exits[1].exitCode = -1073741819;
  assert.equal(classify(receipts).processExits[1].expectedTermination, false, 'native exception remains a crash');
  assert.equal(census.exitResult(i, { engineTerminationIntents: receipts }, 'os-exits').exitAccountingComplete, false);
  fs.writeFileSync(log, census.TERMINATION_INTENT_PREFIX + '{bad}\n' + census.TERMINATION_INTENT_PREFIX + JSON.stringify(intent));
  assert.deepEqual(census.readEngineTerminationIntents(log), [], 'malformed/partial markers grant no exemption');
});

test('observer reads Engine fallback evidence from existing stderr after its pre-stop cursor', async t => {
  const dir = fs.mkdtempSync(path.join(root, 'tmp/engine-intent-test-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  fs.mkdirSync(path.join(dir, 'runtime')); fs.mkdirSync(path.join(dir, 'logs'));
  fs.writeFileSync(path.join(dir, 'runtime/manifest.json'), JSON.stringify(manifest));
  const scope = path.join(dir, 'scope.json'); fs.writeFileSync(scope, JSON.stringify({ processes: input().targets }));
  const log = path.join(dir, 'logs/backend.stderr.log');
  const at = Date.parse(start) + 2000;
  const intent = { enginePid: 10, engineStartedAt: start, child: manifest.children[1],
    requestedAtMs: at, reason: 'graceful-close-fallback' };
  fs.writeFileSync(log, census.TERMINATION_INTENT_PREFIX + JSON.stringify(intent) + '\n');
  let clock = at - 100, args;
  const proc = new EventEmitter(); proc.exitCode = null; proc.kill = () => {};
  const flag = f => args[args.indexOf(f) + 1];
  const observer = await census.startStopExitCensus(run, dir, dir, scope, {
    launch: a => { args = a; fs.writeFileSync(flag('-ReadyFile'), '{}'); return proc; }, now: () => clock,
    pause: async ms => {
      clock += ms;
      if (fs.existsSync(flag('-FinishFile'))) {
        fs.appendFileSync(log, census.TERMINATION_INTENT_PREFIX + JSON.stringify(intent) + '\n');
        fs.writeFileSync(flag('-OutputFile'), JSON.stringify({ processExits: input().targets.map(t => ({
          ...t, exitCode: t.kind === 'EXTRACTION' ? 1 : 0, atMs: at + 1,
        })), forcedPids: [] }));
      }
    },
  });
  const result = await observer.finish([]);
  assert.equal(result.exitAccountingComplete, true);
  assert.equal(result.processExits[2].expectedTermination, true);
  assert.equal(result.processExits[2].terminationIntent.source, log);
});

test('observer becomes ready before shutdown and returns held-handle receipts after stop (including PS UTF8 BOM)', async t => {
  const dir = fs.mkdtempSync(path.join(root, 'tmp/exit-census-test-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  fs.mkdirSync(path.join(dir, 'runtime')); fs.writeFileSync(path.join(dir, 'runtime/manifest.json'), JSON.stringify(manifest));
  const scope = path.join(dir, 'scope.json'); fs.writeFileSync(scope, JSON.stringify({ processes: input().targets }));
  let clock = 0, args, killed = false;
  const proc = new EventEmitter(); proc.exitCode = null; proc.kill = () => { killed = true; };
  const flag = f => args[args.indexOf(f) + 1];
  const observer = await census.startStopExitCensus(run, dir, dir, scope, {
    launch: a => { args = a; fs.writeFileSync(flag('-ReadyFile'), '{}'); return proc; }, now: () => clock,
    pause: async ms => {
      clock += ms;
      if (fs.existsSync(flag('-FinishFile'))) {
        const targets = JSON.parse(fs.readFileSync(flag('-InputFile'))).targets;
        fs.writeFileSync(flag('-OutputFile'), '\uFEFF' + JSON.stringify({ processExits: targets.map(t => ({ ...t, exitCode: 0, atMs: 5000 })), forcedPids: [] }));
      }
    },
  });
  assert.equal(clock, 0); assert.ok(fs.existsSync(flag('-ReadyFile')));
  const result = await observer.finish([]);
  assert.equal(killed, false); assert.equal(result.exitAccountingComplete, true);
  assert.equal(result.processExits.length, 3); assert.ok(result.processExits.every(e => e.source.endsWith('process-exits.json')));
});
test('missing observer readiness is bounded and cannot prove clean native exits', async t => {
  const dir = fs.mkdtempSync(path.join(root, 'tmp/exit-census-test-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  let clock = 0;
  const proc = new EventEmitter(); proc.exitCode = null; proc.kill = () => { proc.exitCode = 1; };
  const observer = await census.startStopExitCensus(run, dir, dir, null,
    { launch: () => proc, now: () => clock, pause: async ms => { clock += ms; } });
  const result = await observer.finish([]);
  assert.equal(clock, 15000); assert.equal(result.exitAccountingComplete, false);
  assert.ok(result.exitAccountingGaps.some(g => /failed before shutdown/.test(g.reason)));
});
