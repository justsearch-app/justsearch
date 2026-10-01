import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { ROOT, ARMS, parseArgs, buildPlan, compareStageRates, chunkProgress, projectLoad,
  tableVerdicts, projectionIdentity, projectExperiments, fillValues, reprojectRecord, collect, main } from './e-run.mjs';
import { markBoundaryCancellation } from './admission-loop.mjs';
import { boundaryCensored, agentMetrics } from './e-agent-metrics.cjs';
import { measurementIdentity } from './e-pair-identity.mjs';
import { corpusManifest, modelIdentity, validateRuntimeIdentity, captureEncoderSessions, finalizeInputs } from './e-measured-inputs.mjs';
import { configuredFixture } from './e-model-fixture.test-support.mjs';
import { componentBudget } from './e-memory-budget.mjs';
import { waitForScope, markTeardown } from './e-acquire.mjs';
import { crashEvidence, crashTiming, projectCrashes } from './e-crash-evidence.mjs';

const document = JSON.parse(fs.readFileSync(path.join(ROOT, 'docs/design/lane-f-engine-jvm/evidence/E/values.json')));
const values = document.values;
const record = (arm, groups = ['E2', 'E3']) => ({ arm, groups, id: arm, runIds: [], commands: [],
  metrics: {}, clauses: {}, gaps: {}, pairIdentity: 'same-measurement', valuesHash: 'same-values',
  projectionIdentity: projectionIdentity(values) });
function scratch(t) {
  const dir = fs.mkdtempSync(path.join(ROOT, 'tmp/e-review-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return dir;
}
const write = (file, value) => {
  fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, JSON.stringify(value));
};

test('E3 relative progress cannot erase absolute chunk starvation; extra branch stages are gains', () => {
  const baseline = { primary: { rate: 10 }, chunk_embed: { rate: 0 } };
  const branch = { primary: { rate: 9 }, chunk_embed: { rate: 0, completedDelta: 0, pendingObserved: true } };
  assert.equal(compareStageRates(branch, baseline).check, true);
  assert.equal(chunkProgress(branch.chunk_embed, baseline.chunk_embed).check, false);
  assert.match(chunkProgress(branch.chunk_embed, baseline.chunk_embed).reason, /main starved chunks \(baseline\)/);
  branch.chunk_embed = { rate: 2, completedDelta: 200, pendingObserved: true };
  assert.equal(chunkProgress(branch.chunk_embed, baseline.chunk_embed).check, true);
  assert.equal(compareStageRates(branch, baseline).comparisons.chunk_embed.status, 'branch-gain');
  assert.equal(compareStageRates({ chunk_embed: branch.chunk_embed }, { chunk_embed: { rate: 0 } }).check, undefined);
  assert.equal(chunkProgress({ ...branch.chunk_embed, rate: 0 }, baseline.chunk_embed).check, false);
  assert.equal(chunkProgress({ ...branch.chunk_embed, rate: undefined }, baseline.chunk_embed).check, undefined);
  assert.equal(chunkProgress(branch.chunk_embed, { rate: 2 / .9 }).check, true);
  assert.equal(chunkProgress(branch.chunk_embed, { rate: 3 }).check, false);
});

test('boundary cut retains errors, duplicate done, HTTP failure and the explicit cancellation cause', () => {
  const base = { streamed: true, status: 200, durationMs: 8, error: 'TRANSPORT_FAILURE',
    terminal: { doneCount: 0, errorCount: 0, eof: false } };
  const clean = structuredClone(base); markBoundaryCancellation(clean, true);
  assert.equal(clean.cancellationCause, 'fixed-window-end');
  assert.equal(boundaryCensored(clean), true);
  assert.equal(agentMetrics([clean]).agentAdmitted, 0);
  for (const patch of [
    { terminal: { doneCount: 0, errorCount: 1, eof: false, errorCode: 'LLM_ERROR' } },
    { terminal: { doneCount: 2, errorCount: 0, eof: false } },
    { status: 500 }, { status: 429 },
  ]) {
    const call = { ...structuredClone(base), ...patch }; markBoundaryCancellation(call, true);
    assert.equal(call.cancellationCause, 'fixed-window-end');
    assert.equal(boundaryCensored(call), false);
    // Old or malformed acquisition flags cannot hide a recorded violation either.
    call.windowBoundary = true;
    const r = { ...record('branch'), workload: 'scripted-agent' };
    projectLoad(r, { requests: [], samples: [], admissionExitCode: 0 }, [call], values);
    assert.equal(r.clauses['no-timeout-or-5xx'], false);
    assert.equal(r.metrics.agentAdmitted, 0);
    if (call.terminal.errorCount) assert.deepEqual(r.metrics.agentTerminalErrors, { LLM_ERROR: 1 });
  }
  const timeout = { ...base, error: 'TIMEOUT', windowBoundary: true, cancellationCause: 'fixed-window-end' };
  assert.equal(boundaryCensored(timeout), false);
});

test('E5 index readiness uses the same 13600 ms budget in projection and table', t => {
  const raw = scratch(t), m = { ...record('main', ['E5']), raw }, b = { ...record('branch', ['E5']), raw };
  for (const r of [m, b]) Object.assign(r.metrics, { 'crash-to-api': 100, 'crash-to-index': 13600 });
  projectExperiments(m, values); projectExperiments(b, values);
  const row = () => tableVerdicts({ 'E5/main': m, 'E5/branch': b }, values).find(r => r.clause === 'crash-to-index');
  assert.equal(b.clauses['crash-to-index'], true); assert.equal(row().verdict, 'pass');
  b.metrics['crash-to-index'] = 13601; projectExperiments(b, values);
  assert.equal(b.clauses['crash-to-index'], false); assert.equal(row().verdict, 'fail');
  b.clauses['crash-to-index'] = true; assert.equal(row().verdict, 'fail');
  delete b.metrics['crash-to-index']; projectExperiments(b, values);
  assert.equal(row().verdict, 'unmeasurable');
});

test('acquisition identity binds corpus names/bytes, model file metadata and deadline/soak implementation', t => {
  const dir = scratch(t), corpus = path.join(dir, 'corpus'), models = path.join(dir, 'models');
  fs.mkdirSync(corpus); fs.mkdirSync(models);
  fs.writeFileSync(path.join(corpus, 'one.txt'), 'one'); fs.writeFileSync(path.join(models, 'encoder.onnx'), 'model');
  const config = configuredFixture(models);
  const plan = buildPlan(parseArgs(['e2-e3-load', '--arm', 'main', '--workload', 'agent-idle']), values);
  const opts = () => ({ sourceRoot: ROOT, outputRoot: ROOT, armTree: ARMS.main, arm: 'main', group: 'e2-e3-load',
    scifact: corpusManifest(corpus), models: modelIdentity(config) });
  const original = measurementIdentity(plan, opts());
  assert.ok(original.pairIdentityInputs.instruments['scripts/jseval/lane-f/e-acquire.mjs']);
  fs.writeFileSync(path.join(corpus, 'one.txt'), 'changed');
  assert.notEqual(measurementIdentity(plan, opts()).pairIdentity, original.pairIdentity);
  fs.writeFileSync(path.join(corpus, 'one.txt'), 'one'); fs.renameSync(path.join(corpus, 'one.txt'), path.join(corpus, 'two.txt'));
  assert.notEqual(measurementIdentity(plan, opts()).pairIdentity, original.pairIdentity);
  fs.renameSync(path.join(corpus, 'two.txt'), path.join(corpus, 'one.txt'));
  fs.writeFileSync(path.join(models, 'encoder.onnx'), 'different-model');
  assert.notEqual(measurementIdentity(plan, opts()).pairIdentity, original.pairIdentity);
  const current = measurementIdentity(plan, opts());
  const selected = modelIdentity(config);
  validateRuntimeIdentity(selected, [{ onnxFeatures: [{ id: 'embed', modelPath: path.join(models, 'onnx/gte-multilingual-base') }] }]);
  assert.throws(() => validateRuntimeIdentity(selected, [{ onnxFeatures: [{ id: 'splade', modelPath: path.join(models, 'onnx/gte-multilingual-base') }] }]), /mismatch/);
  const changed = measurementIdentity(plan, opts(), file => file.endsWith('e-acquire.mjs')
    ? Buffer.concat([fs.readFileSync(file), Buffer.from('\n// deadline change')]) : fs.readFileSync(file));
  assert.notEqual(changed.pairIdentity, current.pairIdentity);
});

test('table refuses mixed or stale projection identities independently of acquisition pairing', () => {
  const m = record('main'), b = record('branch');
  m.clauses['no-timeout-or-5xx'] = b.clauses['no-timeout-or-5xx'] = true;
  b.projectionIdentity = 'old-scorer';
  let row = tableVerdicts({ 'E2/main': m, 'E2/branch': b }, values).find(r => r.clause === 'no-timeout-or-5xx');
  assert.equal(row.verdict, 'unmeasurable'); assert.match(row.reason, /reproject both/);
  m.projectionIdentity = 'old-scorer';
  row = tableVerdicts({ 'E2/main': m, 'E2/branch': b }, values).find(r => r.clause === 'no-timeout-or-5xx');
  assert.equal(row.verdict, 'unmeasurable');
  assert.notEqual(projectionIdentity(values), projectionIdentity({ ...values, crashToApiRestoredMs: 13601 }));
});

test('general E4 projection preserves terminal violations at cancellation and keeps MAIN coverage separate', t => {
  const raw = scratch(t), r = { ...record('main', ['E4']), raw };
  r.commands = [{ label: 'soak-cycle-1', code: 0 }]; r.clauses['index-agent-reconfigure-workload'] = true;
  const error = { status: 200, streamed: true, durationMs: 8, windowBoundary: true,
    cancellationCause: 'fixed-window-end', error: 'TRANSPORT_FAILURE',
    terminal: { errorCount: 1, doneCount: 0, eof: false, errorCode: 'LLM_ERROR' } };
  write(path.join(raw, 'workload.json'), { requests: [error] });
  write(path.join(raw, 'summary.json'), { search_load: { errors: 33 } });
  collect({ record: r, raw, values });
  assert.deepEqual(r.metrics.agentTerminalErrors, { LLM_ERROR: 1 });
  assert.equal(r.metrics.agentAdmitted, 0); assert.equal(r.clauses['no-timeout-or-5xx'], false);
  assert.equal(r.metrics.wireOutcomes['search-load-failure'], 33);
  assert.equal(r.clauses['index-agent-reconfigure-workload'], true);
});

test('reprojection derives E6 settings from retained policy facts rather than a previous pass', t => {
  const raw = scratch(t), r = { ...record('branch', ['E6']), raw };
  const current = { ...values, hangParameters: { intervalMs: 10000, missCount: 3, worstPauseMs: 100 } };
  for (const kind of ['soft', 'hard']) {
    write(path.join(raw, `hang-${kind}/instruments.json`), { snapshots: [{ supervisor: {
      policyProfile: 'harness', policyOverrides: ['hangPollIntervalMs', 'hangUnhealthyThreshold'] } }] });
    write(path.join(raw, `hang-${kind}/result.json`), { native: { intervalMs: 10000, missCount: 3 } });
  }
  projectExperiments(r, current); assert.equal(r.clauses['E4-derived-hang-settings'], true);
  projectExperiments(r, { ...current, hangParameters: { ...current.hangParameters, missCount: 4 } });
  assert.equal(r.clauses['E4-derived-hang-settings'], false);
});

test('table reprojects both retained populations atomically before comparison without changing measured identity', async t => {
  const dir = scratch(t), evidence = path.join(dir, 'docs/design/lane-f-engine-jvm/evidence/E');
  write(path.join(evidence, 'values.json'), document);
  const records = [];
  for (const arm of ['main', 'branch']) {
    const raw = path.join(dir, 'raw', arm), file = path.join(evidence, 'e5-crash', arm, 'run.json');
    const retained = path.join(raw, 'receipt.json'); write(retained, { measured: true });
    const r = { ...record(arm, ['E5']), raw, recordFile: file, rawFiles: [retained],
      startedAt: '2026-10-01T12:00:00Z', endedAt: '2026-10-01T12:01:00Z', projectionIdentity: `old-${arm}` };
    r.metrics = { 'crash-to-api': 100, 'crash-to-index': 15000 }; r.clauses['crash-to-index'] = true;
    write(file, r); write(path.join(path.dirname(file), 'index.json'), { runs: [{ record: file }] }); records.push(r);
  }
  fs.rmSync(records[1].rawFiles[0]);
  await assert.rejects(main(['table', '--repo-root', dir]), /Missing retained raw files/);
  assert.equal(JSON.parse(fs.readFileSync(records[0].recordFile)).projectionIdentity, 'old-main');
  write(records[1].rawFiles[0], { measured: true });
  const log = console.log; console.log = () => {};
  try { await main(['table', '--repo-root', dir]); } finally { console.log = log; }
  for (const old of records) {
    const r = JSON.parse(fs.readFileSync(old.recordFile));
    assert.equal(r.projectionIdentity, projectionIdentity(values)); assert.equal(r.pairIdentity, old.pairIdentity);
    assert.equal(r.measuredProjection.clauses['crash-to-index'], true); assert.equal(r.clauses['crash-to-index'], false);
    assert.ok(r.reprojectedAt && r.reprojectionRawHashes[old.rawFiles[0]]);
    fs.rmSync(old.rawFiles[0]); assert.throws(() => reprojectRecord(old.recordFile, values), /Missing retained raw files/);
  }
});

test('E0 freezes admitted finite p95 despite MAIN wire failures, retaining outcomes; only candidate wire is gated', () => {
  const m = record('main'), b = record('branch');
  Object.assign(m, { workloadRecords: ['idle', 'scripted'], failure: 'baseline transport exit' });
  m.metrics = { searchP95: { hybrid: 0, lexical: 14 }, agentP95: 311,
    searchP95ByWorkload: { 'agent-idle': { hybrid: 0, lexical: 14 }, 'scripted-agent': { hybrid: 0, lexical: 14 } },
    wireOutcomesByWorkload: { 'scripted-agent': { 'http:504': 33, 'terminal:LLM_ERROR': 4 } } };
  m.clauses = { 'indexing-window-valid': true, 'no-timeout-or-5xx': false };
  const frozen = fillValues(document, { 'E1/main': record('main', ['E1']), 'E2/main': m });
  assert.equal(frozen.values.foregroundSearchP95Ceiling.ceilingMs.hybrid, 0);
  assert.deepEqual(frozen.e0BaselineOutcomes, m.metrics.wireOutcomesByWorkload);
  // Pairing fixture has no aggregate query populations; those are independently tested.
  delete m.workloadRecords;
  b.clauses['no-timeout-or-5xx'] = true;
  const row = () => tableVerdicts({ 'E2/main': m, 'E2/branch': b }, values).find(r => r.clause === 'no-timeout-or-5xx');
  assert.equal(row().verdict, 'pass'); assert.match(row().reason, /504.*33.*LLM_ERROR.*4/);
  b.clauses['no-timeout-or-5xx'] = false; assert.equal(row().verdict, 'fail');
  m.clauses['indexing-window-valid'] = false;
  assert.throws(() => fillValues(document, { 'E1/main': record('main'), 'E2/main': m }), /valid MAIN/);
});

test('consumer budget needs host ORT, complete private-byte attribution and every arm component', () => {
  const p = { role: 'engine', complete: true, privateBytes: 50, luceneMmapBytes: 100,
    consumers: Object.fromEntries(['heap', 'metaspace', 'direct', 'hostOrt', 'nativeOther'].map(k => [k, { privateBytes: 10, budgetBytes: 10 }])) };
  const accounting = { processes: [p] };
  assert.equal(componentBudget(undefined, 'branch').check, undefined);
  assert.equal(componentBudget(accounting, 'branch').check, true);
  assert.equal(componentBudget(accounting, 'main').check, undefined);
  delete p.consumers.hostOrt; assert.equal(componentBudget(accounting, 'branch').check, undefined);
  p.consumers.hostOrt = { privateBytes: 11, budgetBytes: 10 }; assert.equal(componentBudget(accounting, 'branch').check, false);
  p.consumers.hostOrt.privateBytes = 10; p.privateBytes = 60;
  assert.equal(componentBudget(accounting, 'branch').check, undefined);
});

test('scope wait handles collector start race and fails bounded without launching the RSS sampler', async () => {
  let now = 0;
  const runtime = { now: () => now, exists: () => now >= 300, pause: async ms => { now += ms; } };
  await waitForScope('owned/process-scope.json', 60000, runtime); assert.equal(now, 300);
  now = 0; runtime.exists = () => false;
  await assert.rejects(waitForScope('owned/process-scope.json', 60000, runtime), /within 30 s/);
  assert.equal(now, 30000);
  now = 0; await assert.rejects(waitForScope('owned/process-scope.json', 200, runtime), /within 30 s/);
  assert.equal(now, 200);
});

test('crash receipts include Worker teardown, hs_err sources and abnormal native-child exits; MAIN is baseline', t => {
  const dir = scratch(t), window = { startMs: 100000, endMs: 200000 };
  const worker = path.join(dir, 'crashes/crash-worker.json'), head = path.join(dir, 'crashes/crash-head.json');
  write(worker, { pid: 17, process: 'worker', timestamp: new Date(250000).toISOString() });
  write(head, { pid: 18, process: 'head', timestamp: new Date(150000).toISOString() });
  const hs = path.join(dir, 'crashes/hs_err_pid17.log'); fs.writeFileSync(hs, 'onnxruntime.dll fatal error'); fs.utimesSync(hs, 250, 250);
  const evidence = crashEvidence([dir], window, { portsClosed: true }, [
    { pid: 19, role: 'native-child', atMs: 170000, exitCode: 1, source: 'child-exit.json' },
    { pid: 20, role: 'native-child', atMs: 180000, exitCode: null },
  ]);
  const observed = crashEvidence([], window, {}, [], { source: 'instruments.json', snapshots: [
    { atMs: 120000, processes: [{ role: 'worker', pid: 42, creationFileTimeUtc: 'one' }] },
    { atMs: 130000, processes: [{ role: 'worker', pid: 43, creationFileTimeUtc: 'two' }] },
  ] });
  assert.equal(observed.events[0].timing, 'inside-window');
  assert.equal(observed.events[0].type, 'replacement-observed');
  assert.deepEqual(observed.events[0].sources, ['instruments.json']);
  assert.equal(evidence.events.length, 3); assert.equal(evidence.complete, false);
  assert.equal(crashEvidence([], window, { portsClosed: true, exitAccountingComplete: true }, []).complete, false);
  const census = { source: 'scope.json', snapshots: [{ atMs: 150000, processes: [
    { pid: 21, role: 'engine', creationFileTimeUtc: 'born' }, { pid: 22, role: 'native-child', creationFileTimeUtc: 'child-born' },
  ] }] };
  const exits = [{ pid: 21, creationFileTimeUtc: 'born', exitCode: 0, source: 'engine-exit.json' }];
  assert.equal(crashEvidence([], window, { portsClosed: true, exitAccountingComplete: true }, exits, census).complete, false);
  exits.push({ pid: 22, creationFileTimeUtc: 'child-born', exitCode: 0, source: 'child-exit.json' });
  assert.equal(crashEvidence([], window, { portsClosed: true, exitAccountingComplete: true }, exits, census).complete, true);
  assert.equal(evidence.events.find(e => e.pid === 17).timing, 'teardown');
  assert.deepEqual(evidence.events.find(e => e.pid === 17).sources.sort(), [worker, hs].sort());
  assert.equal(evidence.events.find(e => e.pid === 19).timing, 'inside-window');
  assert.equal(crashTiming(undefined, window), 'unknown');
  const aborted = { metrics: { soakWindow: { startMs: 100000, endMs: 400000 } } };
  markTeardown(aborted, 200000);
  assert.equal(aborted.metrics.soakWindow.plannedEndMs, 400000);
  assert.equal(crashTiming(250000, aborted.metrics.soakWindow), 'teardown');
  const m = record('main', ['E4']), b = record('branch', ['E4']);
  m.metrics.crashEvidence = evidence; m.clauses['zero-crashes'] = true; projectCrashes(m);
  b.metrics.crashEvidence = { events: [], complete: true }; b.clauses['zero-crashes'] = true; projectCrashes(b);
  const row = () => tableVerdicts({ 'E4/main': m, 'E4/branch': b }, values).find(r => r.clause === 'zero-crashes');
  assert.equal(m.clauses['zero-crashes'], false); assert.equal(row().verdict, 'pass');
  assert.match(row().reason, /main baseline crashes.*teardown.*hs_err/);
  b.metrics.crashEvidence = evidence; projectCrashes(b); assert.equal(row().verdict, 'fail');
  b.clauses['zero-crashes'] = true; b.metrics.crashEvidence = { events: [], complete: false }; projectCrashes(b);
  assert.equal(row().verdict, 'unmeasurable');
  b.clauses['zero-crashes'] = false; projectCrashes(b); assert.equal(row().verdict, 'fail');
});

test('realized encoder sessions retain lazy and active window boundaries without adding an acceptance gate', async t => {
  const dir = scratch(t), models = path.join(dir, 'models'); fs.mkdirSync(models);
  const config = configuredFixture(models);
  const model = path.join(models, 'onnx/gte-multilingual-base/model.onnx');
  const chat = path.join(models, 'chat.gguf');
  write(path.join(dir, 'effective-config.json'), config);
  const context = { raw: dir, root: dir, effectiveConfig: config, record: { ...record('main', ['E5']), pairIdentityInputs: {} } };
  let realized = false;
  const request = async url => ({ ok: true, json: async () => url.endsWith('/status')
    ? { active: { modelPath: chat }, onnxFeatures: [{ id: 'embed', modelPath: realized ? model : null }] } : { encoders: realized } });
  await captureEncoderSessions(context, 'window-start', request); realized = true;
  await captureEncoderSessions(context, 'window-end', request); finalizeInputs(context);
  assert.equal(context.record.encoderSessions['window-start'].ai.onnxFeatures[0].modelPath, null);
  assert.equal(context.record.encoderSessions['window-end'].ai.onnxFeatures[0].modelPath, model);
  assert.ok(context.record.executedModels.some(m => m.id === 'embed' && m.modelPath === model));
  assert.equal(context.record.pairIdentityInputs.models.method, 'configured-store-superset-size-mtime');
  assert.deepEqual(context.record.clauses, {});
});
