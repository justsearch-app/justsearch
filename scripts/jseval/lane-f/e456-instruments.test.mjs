import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import { gzipSync } from 'node:zlib';
import { DatabaseSync } from 'node:sqlite';
import { ROOT, parseArgs, buildPlan, main, tableVerdicts, latestRecords, sourceDirt, projectionIdentity } from './e-run.mjs';
import { gcLogOption, parseGc, heapTrend, summedHeapTrend, ownedProcesses, memorySeries, launchBudget,
  crashObservation, hangVerdict, hangPolicy, splitGap } from './e456-instruments.mjs';
import { settingsPatch, requestLive, executableFrom, projectChildPolicies, LiveCollector, jobRows, logEventTime, hangRequestTime } from './e456-live.mjs';
import { packet, readIds, loopbackEndpoint, attachFault } from '../../supervisor-conformance/jdwp-fault.mjs';
import { killOwned } from '../../supervisor-conformance/verified-crash.mjs';

const values = JSON.parse(fs.readFileSync(path.join(ROOT, 'docs/design/lane-f-engine-jvm/evidence/E/values.json'), 'utf8')).values;
test('E6 hard phase cannot reuse earlier launches, persistent data logs or soft-phase narration', t => {
  const directory = fs.mkdtempSync(path.join(ROOT, 'tmp/e6-log-scope-'));
  t.after(() => fs.rmSync(directory, { recursive: true }));
  const data = path.join(directory, 'data'), output = path.join(directory, 'hard');
  fs.mkdirSync(path.join(data, 'logs'), { recursive: true }); fs.mkdirSync(output);
  const soft = path.join(directory, 'soft-stderr.log'), hard = path.join(directory, 'hard-stderr.log');
  const persisted = path.join(data, 'logs', 'engine.log');
  const stale = 'wrote a shutdown request: reason=hang\nFORCED KILL: the Engine ignored the hang request\nWorker restarted on port\nworker unresponsive\n';
  fs.writeFileSync(soft, stale); fs.writeFileSync(hard, 'hard launch\n'); fs.writeFileSync(persisted, stale);
  const collector = new LiveCollector({ dataDir: data, record: { arm: 'branch', commands: [
    { mode: 'start', stderrFile: soft }, { mode: 'start', stderrFile: hard }] } }, { directory: output });
  collector.tailLogs();
  assert.equal(fs.readFileSync(path.join(output, 'head-events.log'), 'utf8'), 'hard launch\n');
  // Even a same-launch earlier phase is excluded by the injection boundary.
  fs.appendFileSync(hard, stale);
  const phase = collector.beginLogPhase();
  assert.equal(collector.phaseLog(phase), '');
  for (const pattern of [/wrote a shutdown request: reason=hang/, /FORCED KILL/, /worker unresponsive/, /Worker restarted on port/]) {
    assert.equal(pattern.test(collector.phaseLog(phase)), false);
  }
  fs.appendFileSync(soft, stale); // An earlier actuator may still write; it remains foreign.
  fs.appendFileSync(hard, 'wrote a shutdown request: reason=hang\n'); // Undated narration cannot qualify.
  assert.equal(collector.phaseLog(phase), '');
  const event = message => JSON.stringify({ '@timestamp': new Date(phase.startedAtMs).toISOString(), message }) + '\n';
  const requested = event('wrote a shutdown request: reason=hang');
  fs.appendFileSync(hard, requested);
  assert.equal(collector.phaseLog(phase), requested);
  fs.appendFileSync(hard, 'FORCED KILL: the Engine ignored the hang request\n');
  assert.doesNotMatch(collector.phaseLog(phase), /FORCED KILL/);
  fs.appendFileSync(hard, event('FORCED KILL: the Engine ignored the hang request'));
  assert.match(collector.phaseLog(phase), /FORCED KILL/);
});
test('E6 hard phase rejects soft evidence rotated into a new archive and accepts current archive events', t => {
  const directory = fs.mkdtempSync(path.join(ROOT, 'tmp/e6-log-rotation-'));
  t.after(() => fs.rmSync(directory, { recursive: true }));
  const data = path.join(directory, 'data'), output = path.join(directory, 'hard');
  fs.mkdirSync(path.join(data, 'logs'), { recursive: true }); fs.mkdirSync(output);
  const active = path.join(data, 'logs', 'engine.log');
  const archive = path.join(data, 'logs', 'engine.2026-10-04.0.log.gz');
  const messages = ['worker unresponsive', 'did not terminate gracefully; forcing', 'Worker restarted on port',
    'wrote a shutdown request: reason=hang', 'FORCED KILL: the Engine ignored the hang request'];
  const event = (time, message, runId = 'owned-run') => JSON.stringify({ '@timestamp': new Date(time).toISOString(), message, runId }) + '\n';
  const stale = messages.map(message => event(Date.now() - 60000, message)).join('');
  fs.writeFileSync(active, stale);
  const collector = new LiveCollector({ dataDir: data, owned: { runId: 'owned-run' },
    record: { arm: 'main', commands: [] } }, { directory: output });
  const phase = collector.beginLogPhase();
  assert.equal(collector.phaseLog(phase), '');
  // Rotation occurs after baselining: the new archive replays soft bytes and also holds
  // an event written during hard collection before the active file was replaced.
  const genuine = event(phase.startedAtMs, messages[0]);
  fs.writeFileSync(archive, gzipSync(Buffer.from(fs.readFileSync(active, 'utf8') + genuine)));
  fs.writeFileSync(active, '');
  const log = collector.phaseLog(phase);
  assert.equal(log, genuine);
  assert.ok(fs.readFileSync(path.join(output, 'head-events.log'), 'utf8').includes(stale));
  assert.equal(logEventTime(log, /worker unresponsive/, phase.startedAtMs, phase.runId), phase.startedAtMs);
  for (const message of messages.slice(1)) assert.ok(!log.includes(message), message);
  // The same rejection applies to late appends to the active file, undated narration,
  // and a timestamped event carrying a foreign run identity.
  fs.appendFileSync(active, stale + messages.join('\n') + '\n' + event(phase.startedAtMs, messages[2], 'foreign-run'));
  assert.equal(collector.phaseLog(phase), genuine);
  const current = messages.slice(1).map(message => event(phase.startedAtMs + 1, message)).join('');
  fs.writeFileSync(archive, gzipSync(Buffer.from(stale + genuine + current)));
  assert.equal(collector.phaseLog(phase), genuine + current);
});
test('collector detects active log replacement even when the new file has grown beyond its old offset', t => {
  const directory = fs.mkdtempSync(path.join(ROOT, 'tmp/e6-log-replacement-'));
  t.after(() => fs.rmSync(directory, { recursive: true }));
  const data = path.join(directory, 'data'), output = path.join(directory, 'hard');
  fs.mkdirSync(path.join(data, 'logs'), { recursive: true }); fs.mkdirSync(output);
  const active = path.join(data, 'logs', 'engine.log');
  const event = (time, message) => JSON.stringify({ '@timestamp': new Date(time).toISOString(), message }) + '\n';
  const stale = event(Date.now() - 60000, 'soft-phase');
  fs.writeFileSync(active, stale);
  const collector = new LiveCollector({ dataDir: data, record: { arm: 'main', commands: [] } }, { directory: output });
  const phase = collector.beginLogPhase();
  const current = event(phase.startedAtMs, 'worker unresponsive; hard-phase');
  assert.ok(Buffer.byteLength(current) > Buffer.byteLength(stale));
  fs.writeFileSync(active, current);
  assert.equal(collector.phaseLog(phase), current);
  // A split producer write stays pending until the complete event is available.
  const restarted = event(phase.startedAtMs + 1, 'Worker restarted on port');
  fs.appendFileSync(active, restarted.slice(0, -1));
  assert.equal(collector.phaseLog(phase), current);
  fs.appendFileSync(active, '\n');
  assert.equal(collector.phaseLog(phase), current + restarted);
});
test('E6 request-file timing rejects a soft request left over before hard injection', () => {
  assert.equal(hangRequestTime({ reason: 'hang', deadlineEpochMs: 16000 }, 2000, 15000), undefined);
  assert.equal(hangRequestTime({ reason: 'hang', deadlineEpochMs: 18000 }, 2000, 15000), 3000);
  assert.equal(hangRequestTime({ reason: 'quit', deadlineEpochMs: 18000 }, 2000, 15000), undefined);
  assert.equal(hangRequestTime({}, 2000, 15000), undefined);
  assert.equal(hangRequestTime({ reason: 'hang', deadlineEpochMs: 18000, runId: 'foreign' }, 2000, 15000, 'owned'), undefined);
  assert.equal(hangRequestTime({ reason: 'hang', deadlineEpochMs: 18000, runId: 'owned' }, 2000, 15000, 'owned'), 3000);
});
test('E456 options reject ambiguous values and unrelated options', () => {
  for (const args of [ ['e4-hang-values', '--arm', 'main'], ['table', '--arm-tree', ROOT],
    ['e5-crash', '--arm', 'main', '--fault', 'soft'], ['e6-hang', '--arm', 'main', '--fault', 'bad'],
    ['e6-hang', '--arm', 'main', '--debug-port', '0'], ['e6-hang', '--arm', 'main', '--debug-port', '1e4'],
    ['e6-hang', '--arm', 'main', '--debug-port', '65536'],
    ['e5-crash', '--arm', 'main', '--arm-tree', ROOT, '--arm-tree', ROOT]]) assert.throws(() => parseArgs(args));
  assert.equal(parseArgs(['e6-hang', '--arm', 'main', '--fault', 'hard', '--debug-port', '33229']).fault, 'hard');
});
for (const arm of ['main', 'branch']) {
  test(`E4 scope, live setting and all-JVM logging plan ${arm}`, () => {
    const plan = buildPlan(parseArgs(['e4-memory-soak', '--arm', arm, '--window', '1', '--arm-tree', ROOT]), values);
    const start = plan.find(p => p.mode === 'start'), instruments = plan.find(p => p.mode === 'instruments-start');
    assert.equal(start.cwd, ROOT); assert.match(start.env.JAVA_TOOL_OPTIONS, /gc\*,safepoint.*jvm-%p-%t/);
    assert.equal(instruments.liveSetting, 'ui.excludePatterns'); assert.equal(instruments.gcEveryMinutes, 5);
    assert.equal(instruments.reconfigureEveryMinutes, 15);
    assert.ok(plan.find(p => p.label.startsWith('rss-')).args.includes('-Scope'));
    assert.ok(!start.env.JAVA_TOOL_OPTIONS.includes('UseG1GC'), 'Serial extraction children must keep their collector');
    if (arm === 'main') assert.match(start.env.JUSTSEARCH_JVM_OPTS, /UseG1GC/);
  });
  test(`E5 actual target + separate child shutdown plans ${arm}`, () => {
    const plan = buildPlan(parseArgs(['e5-crash', '--arm', arm]), values);
    const crash = plan.find(p => p.mode === 'crash-experiment');
    assert.match(crash.killTarget, arm === 'main' ? /Worker/ : /Engine/);
    assert.equal(crash.requireProcessing, true); assert.equal(crash.requireNonzeroCheckpoint, arm === 'branch');
    assert.deepEqual(plan.filter(p => p.mode === 'child-path').map(p => p.reason), ['restart', 'quit', 'upgrade']);
    assert.ok(!JSON.stringify(plan).includes('10000ms cooldown'));
  });
  test(`E6 loopback and real JVM plan ${arm}`, () => {
    const frozen = { ...values, hangParameters: { intervalMs: 10000, missCount: 3, worstPauseMs: 100 } };
    const plan = buildPlan(parseArgs(['e6-hang', '--arm', arm]), frozen);
    const faults = plan.filter(p => p.mode === 'hang-experiment');
    assert.deepEqual(faults.map(p => p.kind), ['soft', 'hard']);
    assert.ok(faults.every(p => p.loopback === '127.0.0.1' && p.target === (arm === 'main' ? 'Worker' : 'Engine')));
    const env = plan.find(p => p.mode === 'start').env;
    assert.equal(env.JUSTSEARCH_SUPERVISOR_HANG_THRESHOLD, '3');
    assert.match(arm === 'main' ? env.JUSTSEARCH_JVM_OPTS : env.JAVA_OPTS, /address=127\.0\.0\.1:33225/);
    assert.ok(!JSON.stringify(plan).includes('fake-engine'));
  });
}
test('all new dry runs have no request, attach, kill, or output-directory side effects', async () => {
  const prior = { log: console.log, fetch: globalThis.fetch, kill: process.kill };
  const directory = path.join(ROOT, 'tmp/lane-f-e');
  const before = fs.existsSync(directory) ? fs.readdirSync(directory) : [];
  const output = []; console.log = value => output.push(JSON.parse(value));
  globalThis.fetch = () => { throw new Error('Unexpected network'); }; process.kill = () => { throw new Error('Unexpected kill'); };
  try {
    for (const arm of ['main', 'branch']) for (const group of ['e4-memory-soak', 'e5-crash', 'e6-hang']) {
      await main([group, '--arm', arm, ...(group === 'e4-memory-soak' ? ['--window', '1'] : []), '--dry-run']);
    }
    await main(['e4-hang-values', '--dry-run']);
  } finally { console.log = prior.log; globalThis.fetch = prior.fetch; process.kill = prior.kill; }
  assert.equal(output.length, 7); assert.deepEqual(fs.existsSync(directory) ? fs.readdirSync(directory) : [], before);
});
test('GC parser distinguishes used-after-young from full-GC retained heap and handles pauses/units', () => {
  const result = parseGc('[2026-10-01T00:05:00.000+0000][gc] GC(1) Pause Full (Diagnostic Command) 1G->10M(2G) 100.00ms\n'
    + '[2026-10-01T00:06:00.000+0000][gc] GC(2) Pause Young (Normal) 20M->4M(2G) 0.01s\n'
    + '[2026-10-01T00:06:00.000+0000][safepoint] Safepoint "G1CollectForAllocation", Total: 10000000 ns\nmalformed');
  assert.equal(result.points.length, 2); assert.equal(result.points[0].bytes, 10 * 1024 ** 2);
  assert.deepEqual(result.points.map(p => p.full), [true, false]); assert.deepEqual(result.pausesMs, [100, 10, 10]);
  assert.ok(gcLogOption('F:\\my logs').includes('file="F:/my logs/jvm-%p-%t.log"'));
  assert.ok(!gcLogOption('F:/my logs').includes('\\:'), 'JDK 25 rejects an escaped drive colon');
});
test('heap slopes reject growth, sparse/wrong-kind coverage and preserve rotated-point identities', () => {
  const start = Date.parse('2026-10-01T00:00:00Z'), end = start + 55 * 60000;
  const points = delta => Array.from({ length: 10 }, (_, i) => ({ timeMs: start + (5 + 5 * i) * 60000, gcId: i, full: true, bytes: 1000 + delta * i }));
  assert.equal(heapTrend(points(10), start, end).status, 'fail');
  assert.equal(heapTrend(points(-10), start, end).status, 'pass');
  assert.equal(heapTrend([...points(0), ...points(0)], start, end).points.length, 10);
  assert.equal(heapTrend(points(0), start, end).bytesPerMinute, 0);
  assert.equal(heapTrend(points(10).slice(0, 3), start, end).status, 'unmeasurable');
  assert.equal(heapTrend(points(0).map(p => ({ ...p, full: false })), start, end).status, 'unmeasurable');
});
const row = (pid, parent, command, name = 'java.exe') => ({ ProcessId: pid, ParentProcessId: parent,
  CreationFileTimeUtc: String(134320479841300350n + BigInt(pid)), CommandLine: command, Name: name });
test('scope follows owned ancestry and verified adoption while rejecting foreign llama and PID reuse', () => {
  const head = row(1, 0, 'java HeadlessApp --data=owned'), worker = row(2, 1, 'java io.justsearch.indexerworker.IndexerWorker'),
    parser = row(3, 2, 'java ExtractionSandboxChild'), llama = row(4, 1, 'llama-server', 'llama-server.exe'),
    foreign = row(5, 99, 'llama-server', 'llama-server.exe');
  const root = { pid: 1, creationFileTimeUtc: head.CreationFileTimeUtc, cmdlineFingerprint: head.CommandLine };
  assert.deepEqual(ownedProcesses([head, worker, parser, llama, foreign], root, 'main').map(p => p.role), ['head', 'worker', 'extraction-child', 'llama-server']);
  assert.throws(() => ownedProcesses([{ ...head, CreationFileTimeUtc: '999' }], root, 'main'), /identity/);
  assert.equal(ownedProcesses([head, foreign], root, 'branch', [{ pid: 5, creationFileTimeUtc: 'wrong', cmdlineFingerprint: foreign.CommandLine }]).length, 1);
  assert.equal(executableFrom('"C:\\Program Files\\Java\\java.exe" -Xmx2g'), 'C:\\Program Files\\Java\\java.exe');
});
const header = 'ts,role,pid,workingSetMB,privateMB,cpuSec,threads,workingSetBytes,privateBytes,creationFileTimeUtc\n';
test('summed commit charge requires exact bytes, complete role coverage and unique process identities', () => {
  const csv = header + ['2026-10-01T00:00:00Z,head,1,0,0,0,1,10,20,134320479841300351',
    '2026-10-01T00:00:00Z,worker,2,0,0,0,1,30,40,134320479841300352',
    '2026-10-01T00:00:02Z,head,1,0,0,0,1,10,21,134320479841300351',
    '2026-10-01T00:00:02Z,worker,2,0,0,0,1,30,41,134320479841300352'].join('\n');
  assert.equal(memorySeries(csv, 'main').peakCommitBytes, 62); assert.equal(memorySeries(csv, 'main').peakWorkingSetBytes, 40);
  assert.equal(memorySeries(csv.replaceAll('worker', 'llama-server'), 'main').complete, false);
  assert.throws(() => memorySeries(header + csv.split('\n')[1] + '\n' + csv.split('\n')[1], 'main'), /Duplicate/);
  assert.throws(() => memorySeries('ts,role,pid,workingSetMB,privateMB\n2026-10-01T00:00:00Z,engine,1,1,1', 'branch'), /Incomplete/);
});
test('budget is launch limits plus paired private bytes, never the metaspace threshold as a total cap', () => {
  const flags = 'size_t MaxHeapSize = 2147483648\nuint64_t MaxDirectMemorySize = 268435456\nsize_t MetaspaceSize = 134217728\nbool UseG1GC = true';
  assert.equal(launchBudget(flags, 'engine'), true); assert.equal(launchBudget(flags.replace('2147483648', '4294967296'), 'engine'), false);
  assert.equal(launchBudget('no flags', 'engine'), undefined);
});
test('settings mutation uses the fresh witness only on branch; optional token is honored without leaking it', async () => {
  assert.throws(() => settingsPatch({}, [], 'branch'), /witness/);
  assert.equal(settingsPatch({ witness: { revision: 1 } }, ['glob'], 'branch', 'key').operationKey, 'key');
  assert.deepEqual(settingsPatch({}, ['glob'], 'main', 'key'), { ui: { excludePatterns: ['glob'] } });
  const prior = globalThis.fetch, seen = [];
  globalThis.fetch = async (url, options) => { seen.push({ url, options }); return Response.json({ status: 'ok' }); };
  try { await requestLive({ token: null }, '/api/settings/v2', {}); await requestLive({ token: 'secret' }, '/api/settings/v2', {}); }
  finally { globalThis.fetch = prior; }
  assert.equal(seen[0].options.headers['X-JustSearch-Session'], undefined); assert.equal(seen[1].options.headers['X-JustSearch-Session'], 'secret');
});
test('actual crash requires verified identity and refuses missing/stale/foreign process evidence', () => {
  const processRow = row(42, 1, 'java --data=owned'), record = { pid: 42, creationFileTimeUtc: processRow.CreationFileTimeUtc, cmdlineFingerprint: processRow.CommandLine };
  const table = { ok: true, readAt: Date.now(), table: [processRow] }, signals = [];
  const result = killOwned(record, 'owned', { table, kill: (...args) => signals.push(args), now: () => 100 });
  assert.equal(result.issuedAtMs, 100); assert.deepEqual(signals, [[42, 'SIGKILL']]);
  for (const bad of [{ ok: false }, { ...table, readAt: 0 }, { ...table, table: [{ ...processRow, CreationFileTimeUtc: '999' }] }]) {
    assert.throws(() => killOwned(record, 'owned', { table: bad, kill: () => assert.fail('unsafe kill') }), /Refusing/);
  }
  assert.throws(() => killOwned(record, 'other', { table }), /owned data/);
});
test('checkpoint verdict requires nonzero durable progress and no repeated effects; split stays distinct', () => {
  const before = { pid: 1, operation: { id: 'id', operation_key: 'key', state: 'RUNNING', units_completed: 1, checkpoint_cursor: 'unit1' } };
  const after = { pid: 2, noDuplicateEffects: true, operation: { ...before.operation, state: 'COMPLETE', units_completed: 100 } };
  const timeline = { killMs: 100, apiMs: 110, indexMs: 120 };
  assert.equal(crashObservation(before, after, timeline, 'branch').checkpointResume, true);
  assert.equal(crashObservation(before, { ...after, noDuplicateEffects: false }, timeline, 'branch').checkpointResume, false);
  assert.equal(crashObservation({ ...before, operation: { ...before.operation, units_completed: 0 } }, after, timeline, 'branch').checkpointResume, false);
  assert.equal(crashObservation(before, after, timeline, 'main').checkpointResume, undefined);
  assert.equal(crashObservation(before, after, { ...timeline, apiMs: 99 }, 'branch').apiMs, undefined);
});
test('child policy never passes vacuously and any orphan/reload dominates a gap', () => {
  const r = { metrics: { childPaths: {} }, clauses: {}, gaps: {} };
  projectChildPolicies(r); assert.equal(r.clauses['restart-quit-upgrade-child-policy'], undefined);
  r.metrics.childPaths.crash = { extractionStopped: false }; projectChildPolicies(r);
  assert.equal(r.clauses['restart-quit-upgrade-child-policy'], false);
  for (const reason of ['crash', 'restart', 'quit', 'upgrade']) r.metrics.childPaths[reason] = {
    extractionStopped: true, healthyLlamaAdopted: true, llamaStopped: true };
  projectChildPolicies(r); assert.equal(r.clauses['restart-quit-upgrade-child-policy'], true);
});
test('hang verdict requires the real fault/channel and rejects the wrong actuator outcome', () => {
  const policy = { gracefulStopDeadlineMs: 15000, cooldownIncrementMs: 1000 };
  const observed = { injected: true, preHealthy: true, postUnresponsive: true, requestObserved: true,
    graceful: true, forced: false, requestAtMs: 1000, restoredAtMs: 17000 };
  assert.equal(hangVerdict(observed, 'soft', policy, 12600), true);
  assert.equal(hangVerdict({ ...observed, forced: true }, 'soft', policy, 12600), false);
  assert.equal(hangVerdict(observed, 'hard', policy, 12600), false);
  assert.equal(hangVerdict({ ...observed, injected: false }, 'soft', policy, 12600), undefined);
  assert.equal(hangVerdict({ ...observed, restoredAtMs: 29601 }, 'soft', policy, 12600), false);
  // Unfrozen settings must refuse; use a copy without frozen hang parameters, never the live values file.
  assert.throws(() => hangPolicy({ ...values, hangParameters: undefined }, 100), /Freeze/);
  assert.equal(hangPolicy({ hangParameters: { intervalMs: 10000, missCount: 4 } }, 11000).splitCompatible, false);
  assert.throws(() => hangPolicy({ hangParameters: { intervalMs: 10000, missCount: 3 } }, 11000), /Freeze/);
});
test('unmeasurable-on-split dispositions remain gaps while known failure still dominates', () => {
  const make = arm => ({ arm, id: arm, pairIdentity: 'same', valuesHash: 'same', projectionIdentity: projectionIdentity(values), clauses: { 'checkpoint-resume': true }, metrics: {} });
  const m = make('main'), b = make('branch'); m.dispositions = { 'checkpoint-resume': splitGap('no operations.db') };
  const records = { 'E5/main': m, 'E5/branch': b };
  assert.equal(tableVerdicts(records, values).find(r => r.clause === 'checkpoint-resume').verdict, 'unmeasurable-on-split');
  b.clauses['checkpoint-resume'] = false;
  assert.equal(tableVerdicts(records, values).find(r => r.clause === 'checkpoint-resume').verdict, 'fail');
});
test('JDWP rejects non-loopback endpoints and malformed packets before connecting', () => {
  assert.throws(() => loopbackEndpoint('example.com', 1234)); assert.throws(() => loopbackEndpoint('127.0.0.1', 0));
  assert.equal(packet(1, 1, 8).readUInt32BE(), 11); assert.throws(() => readIds(Buffer.from([0, 0, 0, 1]), 8));
});
test('JDWP fake-peer exercises handshake, selection, individual suspension and explicit cleanup', async t => {
  const commands = [], events = []; let verifies = 0;
  const sockets = new Set(), server = net.createServer(socket => {
    sockets.add(socket); socket.on('close', () => sockets.delete(socket));
    let buffer = Buffer.alloc(0), handshake = false;
    socket.on('data', chunk => {
      buffer = Buffer.concat([buffer, chunk]);
      if (!handshake && buffer.length >= 14) { assert.equal(buffer.subarray(0, 14).toString(), 'JDWP-Handshake');
        buffer = buffer.subarray(14); handshake = true; socket.write('JDWP-Handshake'); }
      while (handshake && buffer.length >= 11 && buffer.length >= buffer.readUInt32BE()) {
        const p = buffer.subarray(0, buffer.readUInt32BE()); buffer = buffer.subarray(p.length);
        commands.push([p[9], p[10]]); let data = Buffer.alloc(0);
        if (p[9] === 1 && p[10] === 7) { data = Buffer.alloc(20); for (let i = 0; i < 5; i++) data.writeInt32BE(8, i * 4); }
        if (p[9] === 1 && p[10] === 4) { data = Buffer.alloc(20); data.writeUInt32BE(2); data.writeBigUInt64BE(1n, 4); data.writeBigUInt64BE(2n, 12); }
        if (p[9] === 11 && p[10] === 1) { const name = p.readBigUInt64BE(11) === 1n ? 'qtp1-1' : 'engine.shutdown-request-watcher';
          data = Buffer.alloc(4 + Buffer.byteLength(name)); data.writeUInt32BE(Buffer.byteLength(name)); data.write(name, 4); }
        const reply = packet(p.readUInt32BE(4), 0, 0, data); reply[8] = 0x80; socket.write(reply);
      }
    });
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { for (const socket of sockets) socket.destroy(); await new Promise(resolve => server.close(resolve)); });
  const fault = await attachFault({ port: server.address().port, kind: 'soft', verify: async () => { verifies++; }, record: e => events.push(e) });
  assert.equal(fault.suspended.length, 1); assert.equal(events[0].name, 'qtp1-1'); assert.equal(verifies, 2);
  assert.ok(commands.some(([set, cmd]) => set === 11 && cmd === 2));
  assert.ok(!commands.some(([set, cmd]) => set === 1 && cmd === 8));
  await fault.close(); assert.ok(commands.some(([set, cmd]) => set === 11 && cmd === 3));
});


test('aligned arm heap sum requires every JVM in every collection round', () => {
  const start = Date.parse('2026-10-01T00:00:00Z'), end = start + 55 * 60000;
  const series = delta => Array.from({ length: 10 }, (_, i) => ({ timeMs: start + (5 + 5 * i) * 60000,
    bytes: 1000 + delta * i, gcId: i, full: true }));
  assert.equal(summedHeapTrend([series(-1), series(2)], start, end).status, 'fail');
  assert.equal(summedHeapTrend([series(-1), series(1)], start, end).bytesPerMinute, 0);
  assert.equal(summedHeapTrend([series(-1), []], start, end).status, 'unmeasurable');
  assert.equal(summedHeapTrend([series(-1), series(1).map(p => ({ ...p, timeMs: p.timeMs + 40000 }))], start, end).status, 'unmeasurable');
});
test('MAIN ledger adapter uses scan identity and reports absent unit revisions without modifying DB', t => {
  const directory = fs.mkdtempSync(path.join(ROOT, 'tmp/e-schema-'));
  t.after(() => fs.rmSync(directory, { recursive: true }));
  const db = new DatabaseSync(path.join(directory, 'jobs.db'));
  db.exec("CREATE TABLE jobs (path TEXT, state TEXT, scan_id TEXT, last_updated INTEGER)");
  db.prepare("INSERT INTO jobs VALUES (?, 'PROCESSING', 'scan', 123)").run(path.join(directory, 'corpus/doc.rtf'));
  db.close();
  const jobs = jobRows(directory, path.join(directory, 'corpus'));
  assert.equal(jobs.length, 1); assert.equal(jobs[0].scan_id, 'scan'); assert.equal(jobs[0].unit_revision, null);
  const check = new DatabaseSync(path.join(directory, 'jobs.db'), { readOnly: true });
  assert.ok(!check.prepare('PRAGMA table_info(jobs)').all().some(r => r.name === 'unit_revision')); check.close();
});
test('collector snapshot projection serializes concurrent experiment and background reads', async () => {
  const collector = new LiveCollector({ record: { arm: 'main' }, dataDir: 'owned' }, { directory: 'unused' });
  let active = 0, peak = 0, reads = 0;
  collector.sampleOnce = async () => { active++; peak = Math.max(peak, active); await Promise.resolve(); reads++; active--; };
  await Promise.all([collector.sample(), collector.sample(), collector.sample()]);
  assert.equal(peak, 1); assert.equal(reads, 3);
});
test('all owned descendants contribute to commit charge, including unexpected native children', () => {
  const head = row(1, 0, 'java HeadlessApp --data=owned'), child = row(2, 1, 'native-helper', 'helper.exe');
  const processes = ownedProcesses([head, child], { pid: 1, creationFileTimeUtc: head.CreationFileTimeUtc, cmdlineFingerprint: head.CommandLine }, 'branch');
  assert.deepEqual(processes.map(p => p.role), ['engine', 'other-child']); assert.equal(processes[1].isJvm, false);
});
test('memory projection rejects an incomplete descendant census', () => {
  const csv = header.trimEnd() + ',expectedProcessCount\n' +
    '2026-10-01T00:00:00Z,engine,1,0,0,0,1,10,20,134320479841300351,2\n' +
    '2026-10-01T00:00:02Z,engine,1,0,0,0,1,10,20,134320479841300351,2';
  assert.equal(memorySeries(csv, 'branch').complete, false);
});
test('Head detection uses producer UTC timestamp only when an actual event supplies it', () => {
  const time = Date.parse('2026-10-01T00:00:05Z');
  const log = JSON.stringify({ '@timestamp': new Date(time).toISOString(), message: 'worker unresponsive; restarting' });
  assert.equal(logEventTime(log, /worker unresponsive/, time - 1), time);
  assert.equal(logEventTime(log, /worker unresponsive/, time + 1), undefined);
  assert.equal(logEventTime('worker unresponsive', /worker unresponsive/, 0), undefined);
});
test('E6 event matching validates timestamp, message and every available run identity together', () => {
  const pattern = /worker unresponsive/, time = Date.parse('2026-10-01T00:00:05Z');
  const event = row => JSON.stringify({ message: 'worker unresponsive', ...row });
  for (const timestamp of [undefined, null, time, 'not-a-date', '2026-10-01T00:00:05',
    '2026-02-30T00:00:05Z', '2026-10-01T24:00:00Z', '2026-10-01T00:00:05+99:00']) {
    assert.equal(logEventTime(event({ '@timestamp': timestamp }), pattern, 0), undefined, String(timestamp));
  }
  const row = { '@timestamp': '2026-10-01T02:00:05+02:00', runId: 'owned' };
  assert.equal(logEventTime(event(row), pattern, time, 'owned'), time);
  assert.equal(logEventTime(event(row), pattern, time + 1, 'owned'), undefined);
  assert.equal(logEventTime(event(row), pattern, time, 'foreign'), undefined);
  assert.equal(logEventTime(event({ ...row, run_id: 'foreign' }), pattern, time, 'owned'), undefined);
  assert.equal(logEventTime(event({ ...row, mdc: { runId: 'foreign' } }), pattern, time, 'owned'), undefined);
  assert.equal(logEventTime(event({ '@timestamp': row['@timestamp'], run_id: 'owned' }), pattern, time, 'owned'), time);
  assert.equal(logEventTime(event({ ...row, message: { text: 'worker unresponsive' } }), pattern, time, 'owned'), undefined);
  assert.equal(logEventTime('null\n' + event({ ...row, message: 'unrelated event' }) + '\nworker unresponsive', pattern, time, 'owned'), undefined);
});
test('hang detection bound includes native probe time and cannot pass on delayed detection', () => {
  const policy = { intervalMs: 10000, missCount: 3, probeTimeoutMs: 1000, gracefulStopDeadlineMs: 15000, cooldownIncrementMs: 1000 };
  const observed = { injected: true, preHealthy: true, postUnresponsive: true, requestObserved: true,
    graceful: true, forced: false, injectionAtMs: 1000, requestAtMs: 40000, restoredAtMs: 45000 };
  assert.equal(hangVerdict(observed, 'soft', policy, 12600), false);
  assert.equal(hangVerdict({ ...observed, requestAtMs: 31000 }, 'soft', policy, 12600), true);
  assert.equal(hangVerdict({ ...observed, requestAtMs: 31000, injectionErrors: ['lost thread'] }, 'soft', policy, 12600), undefined);
});
test('arm source check excludes only owned evidence, never changed product or instrument sources', () => {
  assert.deepEqual(sourceDirt('?? docs/design/lane-f-engine-jvm/evidence/E/one.json\n M scripts/jseval/lane-f/e-run.mjs\n'), [' M scripts/jseval/lane-f/e-run.mjs']);
});
test('E4 aggregation keeps failed long-window slopes and does not replace them with the short tail', t => {
  const root = fs.mkdtempSync(path.join(ROOT, 'tmp/e-windows-'));
  t.after(() => fs.rmSync(root, { recursive: true }));
  const directory = path.join(root, 'docs/design/lane-f-engine-jvm/evidence/E/e4-memory-soak/branch');
  fs.mkdirSync(directory, { recursive: true }); const runs = [];
  for (const window of ['1', '2', '3']) {
    const file = path.join(directory, `${window}.json`);
    fs.writeFileSync(file, JSON.stringify({ id: window, recordFile: file, groups: ['E4'], arm: 'branch', window,
      startedAt: window, metrics: { peakCommitMB: 100, worstPauseMs: 1 }, valuesHash: 'values', pairIdentity: 'pair', revision: 'revision',
      clauses: { 'window-duration': true, 'live-after-GC-trend': window === '1' ? false : window === '2' ? true : undefined } }));
    runs.push({ record: file });
  }
  fs.writeFileSync(path.join(directory, 'index.json'), JSON.stringify({ runs }));
  const record = latestRecords(root)['E4/branch'];
  assert.equal(record.clauses['live-after-GC-trend'], false); assert.equal(record.clauses['owner-duration'], true);
});

test('component budget cannot use flags or machine sums as consumer accounting (2026-10-01 owner amendment)', () => {
  const make = arm => ({ id: arm, arm, valuesHash: 'same', pairIdentity: 'same', projectionIdentity: projectionIdentity(values),
    metrics: { peakCommitMB: 1000 }, clauses: { 'component-commit-budget': true } });
  const records = { 'E4/main': make('main'), 'E4/branch': make('branch') };
  assert.equal(tableVerdicts(records, values).find(r => r.clause === 'component-commit-budget').verdict, 'unmeasurable');
});

test('live settings collector verifies the real consumer and restores the original patterns', async t => {
  const directory = fs.mkdtempSync(path.join(ROOT, 'tmp/e-settings-'));
  t.after(() => fs.rmSync(directory, { recursive: true }));
  const collector = new LiveCollector({ token: null, record: { arm: 'branch' }, dataDir: 'owned' }, { directory });
  collector.originalSettings = { ui: { excludePatterns: ['original'] } };
  collector.originalApplied = { matchedFiles: 0, deletedById: 0, deletedByPathJobs: 0, capped: false };
  const previous = globalThis.fetch, seen = []; let patterns = ['original'], revision = 1;
  globalThis.fetch = async (url, options) => {
    seen.push({ url, options });
    if (url.endsWith('/api/settings/v2')) {
      if (options.method === 'POST') {
        const body = JSON.parse(options.body); assert.equal(body.witness.revision, revision);
        assert.match(body.operationKey, /^[0-9a-f]{8}-[0-9a-f]{4}-7/); patterns = body.ui.excludePatterns; revision++;
      }
      return Response.json({ ui: { excludePatterns: patterns }, witness: { revision } });
    }
    if (url.includes('/api/indexing/excludes/apply?dryRun=true')) return Response.json({
      ...collector.originalApplied, perPattern: patterns.map(pattern => ({ pattern })) });
    return Response.json({});
  };
  try { await collector.reconfigure(); await collector.reconfigure(true); }
  finally { globalThis.fetch = previous; }
  assert.deepEqual(patterns, ['original']); assert.deepEqual(collector.reconfigures.map(r => r.restore), [false, true]);
  assert.equal(seen.filter(c => c.url.includes('/api/indexing/excludes/apply?dryRun=true')).length, 2);
});

test('ancestry rejects a child born before a reused parent PID and covers adopted descendants', () => {
  const head = row(10, 0, 'java HeadlessApp --data=owned'), foreign = row(2, 10, 'llama-server', 'llama-server.exe'),
    adopted = row(11, 99, 'llama-server', 'llama-server.exe'), child = row(12, 11, 'native-helper', 'helper.exe');
  const root = { pid: 10, creationFileTimeUtc: head.CreationFileTimeUtc, cmdlineFingerprint: head.CommandLine };
  const retained = [{ pid: 11, creationFileTimeUtc: adopted.CreationFileTimeUtc, cmdlineFingerprint: adopted.CommandLine }];
  assert.deepEqual(ownedProcesses([head, foreign, adopted, child], root, 'branch', retained).map(p => p.ProcessId), [10, 11, 12]);
});
