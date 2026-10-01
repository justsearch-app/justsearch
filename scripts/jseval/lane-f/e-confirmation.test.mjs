import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { ROOT, ARMS, buildPlan, parseArgs, collect, tableVerdicts } from './e-run.mjs';
import { soakWire } from './e-soak-wire.mjs';
import { requestLive } from './e456-live.mjs';
import { modelIdentity, validateRuntimeIdentity, captureEncoderSessions, finalizeInputs, digest } from './e-measured-inputs.mjs';
import { configuredFixture } from './e-model-fixture.test-support.mjs';
import { measurementIdentity } from './e-pair-identity.mjs';

const scratch = t => {
  const dir = fs.mkdtempSync(path.join(ROOT, 'tmp/e-confirmation-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true })); return dir;
};
const write = (file, data) => fs.writeFileSync(file, JSON.stringify(data));
const journal = (file, events) => fs.writeFileSync(file, events.map(e => JSON.stringify(e)).join('\n') + '\n');
const calls = [{ status: 200, durationMs: 8 }];
const ok = { requestId: 0, atMs: 1000, status: 200, event: 'request-outcome' };
function soak(t) {
  const raw = scratch(t), outcomesFile = path.join(raw, 'search-cycle-1.jsonl');
  journal(path.join(raw, 'collector-wire.jsonl'), [ok]);
  journal(outcomesFile, [{ event: 'load-start' }, ok, { event: 'load-end' }]);
  return { raw, commands: [{ label: 'soak-cycle-1', outcomesFile, endedAt: new Date(4000).toISOString() }] };
}
test('interrupted E4 final cycle retains earlier 504; incomplete coverage cannot hide known failures', t => {
  const r = soak(t), cycle = r.commands[0]; cycle.cancellationCause = 'fixed-window-end';
  journal(cycle.outcomesFile, [{ event: 'load-start' }, { ...ok, status: 504 },
    { event: 'request-start', requestId: 1, atMs: 3000, requestTimeoutMs: 5000 }]);
  const projected = soakWire(r, calls, []);
  assert.equal(projected.check, false); assert.equal(projected.outcomes['http:504'], 1);
  assert.equal(projected.search[1].cancellationCause, 'fixed-window-end');
  journal(cycle.outcomesFile, [{ event: 'load-start' },
    { requestId: 1, event: 'http-response', status: 504, atMs: 3000, requestTimeoutMs: 5000 }]);
  assert.equal(soakWire(r, calls, []).check, false);
});
test('every E4 cycle requires outcomes; a missing, empty or corrupt final journal is unmeasurable', t => {
  const r = soak(t), second = path.join(r.raw, 'search-cycle-2.jsonl');
  r.commands.push({ label: 'soak-cycle-2', args: ['--search-load-outcomes', second], cancellationCause: 'fixed-window-end' });
  assert.equal(soakWire(r, calls, []).check, undefined);
  assert.match(soakWire(r, calls, []).gaps[0], /soak-cycle-2/);
  journal(second, [{ event: 'load-start' }]); assert.equal(soakWire(r, calls, []).check, undefined);
  fs.appendFileSync(second, '{partial'); assert.equal(soakWire(r, calls, []).check, undefined);
});
test('boundary censoring requires earlier outcomes and a request still inside its timeout', t => {
  const r = soak(t), cycle = r.commands[0]; cycle.cancellationCause = 'fixed-window-end';
  const start = { event: 'request-start', requestId: 1, atMs: 3000, requestTimeoutMs: 5000 };
  journal(cycle.outcomesFile, [{ event: 'load-start' }, ok, start]);
  assert.equal(soakWire(r, calls, []).check, true);
  journal(cycle.outcomesFile, [{ event: 'load-start' }, start]);
  assert.equal(soakWire(r, calls, []).check, undefined);
  journal(cycle.outcomesFile, [{ event: 'load-start' }, ok, { ...start, requestTimeoutMs: 500 }]);
  assert.equal(soakWire(r, calls, []).check, undefined);
  journal(cycle.outcomesFile, [{ event: 'load-start' }, ok, { ...start, error: 'TIMEOUT' }]);
  assert.equal(soakWire(r, calls, []).check, false);
  fs.rmSync(cycle.outcomesFile);
  assert.equal(soakWire(r, calls, [{ search_load: { errors: 1, outcomes_file: cycle.outcomesFile } }]).check, false);
});
test('collector settings restoration HTTP failures enter E4 wire evidence even if cleanup throws', async t => {
  const r = soak(t), previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  globalThis.fetch = async () => ({ ok: false, status: 500, text: async () => 'restoration failed' });
  await assert.rejects(requestLive({ raw: r.raw, record: { groups: ['E4'] } }, '/api/settings/v2', {}), /HTTP 500/);
  const projected = soakWire(r, calls, []);
  assert.equal(projected.check, false); assert.equal(projected.collector.at(-1).status, 500);
  const values = JSON.parse(fs.readFileSync(path.join(ROOT, 'docs/design/lane-f-engine-jvm/evidence/E/values.json'))).values;
  write(path.join(r.raw, 'workload.json'), { requests: calls });
  Object.assign(r, { arm: 'branch', groups: ['E4'], metrics: {}, clauses: {}, gaps: {} });
  collect({ raw: r.raw, record: r, values });
  assert.equal(r.clauses['no-timeout-or-5xx'], false);
  const baseline = { ...r, arm: 'main', clauses: { 'no-timeout-or-5xx': true } };
  assert.equal(tableVerdicts({ 'E4/main': baseline, 'E4/branch': r }, values)
    .find(row => row.group === 'E4' && row.clause === 'no-timeout-or-5xx').verdict, 'fail');
  fs.rmSync(path.join(r.raw, 'collector-wire.jsonl')); assert.equal(soakWire(r, calls, []).check, undefined);
});
function models(t) {
  const raw = scratch(t), store = path.join(raw, 'models'); fs.mkdirSync(store);
  const config = configuredFixture(store);
  const chat = path.join(store, 'chat.gguf'), external = path.join(raw, 'external.onnx');
  fs.writeFileSync(chat, 'chat'); fs.writeFileSync(external, 'external');
  config.keys.push({ key: 'justsearch.rerank.model_path', value: external });
  return { raw, external, config,
  status: { active: { modelPath: chat }, onnxFeatures: [] } };
}
test('missing runtime observations remain visible while configured identity is mandatory (2026-10-01 owner correction)', async t => {
  const m = models(t), context = { raw: m.raw, effectiveConfig: m.config,
    record: { groups: ['E5'], pairIdentityInputs: {} } };
  const request = async url => ({ ok: !url.endsWith('/status'), status: 503, json: async () => ({}) });
  await captureEncoderSessions(context, 'pre-load', request);
  const receipt = JSON.parse(fs.readFileSync(path.join(m.raw, 'encoder-sessions-pre-load.json')));
  assert.equal(receipt.aiError, 'HTTP 503');
  write(path.join(m.raw, 'effective-config.json'), m.config);
  finalizeInputs(context);
  assert.ok(context.record.pairIdentityInputs.models.configuredSelections.length >= 6);
  fs.rmSync(path.join(m.raw, 'encoder-sessions-pre-load.json'));
  finalizeInputs(context);
  fs.rmSync(path.join(m.raw, 'effective-config.json'));
  assert.throws(() => finalizeInputs(context), /effective-config capture missing/);
});
test('configured external weights bind metadata without any runtime reference to that file', t => {
  const m = models(t), before = modelIdentity(m.config);
  assert.ok(before.models.some(f => f.path === m.external.replaceAll('\\', '/')));
  fs.writeFileSync(m.external, 'changed external weights');
  assert.notEqual(digest(JSON.stringify(before)), digest(JSON.stringify(modelIdentity(m.config))));
  fs.rmSync(m.external); assert.throws(() => modelIdentity(m.config), /Configured model missing/);
});
test('MAIN lazy worker-policy sessions at start are valid on every arm and group', async t => {
  const m = models(t), dormant = { active: { modelPath: null },
    onnxFeatures: [{ id: 'embed', modelPath: null, modelActive: true, status: 'active',
      reason: 'worker_policy_snapshot', executionProvider: 'cpu', gpuFallback: true }] };
  for (const arm of ['main', 'branch']) for (const group of ['E1', 'E2', 'E4', 'E5', 'E6']) {
    const context = { raw: m.raw, effectiveConfig: m.config, record: { arm, groups: [group], pairIdentityInputs: {} } };
    await captureEncoderSessions(context, 'pre-load', async () => ({ ok: true, json: async () => dormant }));
    assert.deepEqual(context.record.encoderSessions['pre-load'].models, modelIdentity(m.config));
    assert.equal(context.record.encoderSessions['pre-load'].sessions[1].runtimeIdentityAvailable, false);
  }
});
test('E5 does not require a post-quit runtime API; every experiment still requires pre-load model proof', () => {
  const values = JSON.parse(fs.readFileSync(path.join(ROOT, 'docs/design/lane-f-engine-jvm/evidence/E/values.json'))).values;
  for (const arm of ['main', 'branch']) {
    const plan = buildPlan(parseArgs(['e5-crash', '--arm', arm]), values, ROOT);
    for (const c of plan.filter(c => c.mode === 'ai-activate'))
      assert.equal(plan[plan.indexOf(c) + 1].mode, 'encoder-sessions');
    for (const label of ['stop-children-quit', 'stop-children-upgrade'])
      assert.notEqual(plan[plan.findIndex(c => c.label === label) - 1].mode, 'encoder-sessions');
  }
});
test('missing end observation is recorded; runtime/config mismatch at end invalidates acquisition', t => {
  const m = models(t), context = { raw: m.raw, record: { groups: ['E5'], pairIdentityInputs: {} } };
  write(path.join(m.raw, 'effective-config.json'), m.config);
  write(path.join(m.raw, 'encoder-sessions-start.json'), { ai: m.status });
  write(path.join(m.raw, 'bulk-load.json'), { encoderSessions: { end: { aiError: 'status unavailable' } } });
  finalizeInputs(context);
  assert.ok(context.record.runtimeModelObservations.some(r => r.aiError === 'status unavailable'));
  write(path.join(m.raw, 'bulk-load.json'), { encoderSessions: { end: { ai: {
    onnxFeatures: [{ id: 'embed', modelPath: m.external }] } } } });
  assert.throws(() => finalizeInputs(context), /Runtime model identity mismatch: embed/);
});
test('runtime mismatch fails immediately even if that file is in the bound shared store', async t => {
  const m = models(t), wrong = path.join(m.raw, 'models/onnx/ner');
  const context = { raw: m.raw, effectiveConfig: m.config, record: { groups: ['E1'], pairIdentityInputs: {} } };
  await assert.rejects(captureEncoderSessions(context, 'start', async () => ({ ok: true,
    json: async () => ({ onnxFeatures: [{ id: 'embed', modelPath: wrong }] }) })), /Runtime model identity mismatch: embed/);
  assert.match(JSON.parse(fs.readFileSync(path.join(m.raw, 'encoder-sessions-start.json'))).modelIdentityError, /mismatch/);
});
test('missing configured encoder fails before measurement, regardless of lazy runtime state', async t => {
  const m = models(t); fs.rmSync(path.join(m.raw, 'models/onnx/gte-multilingual-base/model.onnx'));
  const context = { raw: m.raw, effectiveConfig: m.config, record: { groups: ['E1'], pairIdentityInputs: {} } };
  await assert.rejects(captureEncoderSessions(context, 'start', async () => ({ ok: true,
    json: async () => ({ onnxFeatures: [{ id: 'embed', modelPath: null }] }) })), /Configured model missing: embed/);
});
test('CPU fallback at window end is recorded and never changes pair identity', async t => {
  const m = models(t), embed = path.join(m.raw, 'models/onnx/gte-multilingual-base');
  write(path.join(m.raw, 'effective-config.json'), m.config);
  const context = { raw: m.raw, effectiveConfig: m.config, record: { groups: ['E5'], pairIdentityInputs: {} } };
  let ended = false;
  const request = async url => ({ ok: true, json: async () => url.endsWith('/status') ? { ...m.status,
    onnxFeatures: [{ id: 'embed', modelPath: ended ? embed : null, modelActive: ended,
      executionProvider: ended ? 'cpu' : 'unknown', gpuFallback: ended, fallbackReason: ended ? 'CPU fallback' : null }] } : {} });
  await captureEncoderSessions(context, 'window-start', request);
  const before = context.record.encoderSessions['window-start'].models;
  ended = true; await captureEncoderSessions(context, 'window-end', request); finalizeInputs(context);
  assert.deepEqual(context.record.pairIdentityInputs.models, before);
  const observation = context.record.runtimeModelObservations.find(r => r.label === 'window-end').sessions[1];
  assert.equal(observation.executionProvider, 'cpu'); assert.equal(observation.modelActive, true);
  assert.equal(observation.gpuFallback, true); assert.equal(observation.fallbackReason, 'CPU fallback');
  validateRuntimeIdentity(before, [{ onnxFeatures: [{ id: 'embed', modelPath: path.join(embed, 'model.onnx') }] }]);
});
test('CPU and CUDA realizations bind the same configured identity; all declared model choices enter it', t => {
  const m = models(t), store = path.join(m.raw, 'models'), dir = path.join(store, 'onnx/gte-multilingual-base');
  fs.writeFileSync(path.join(dir, 'model_fp16.onnx'), 'GPU weights');
  write(path.join(dir, 'model_manifest.json'), { cpu: 'model.onnx', gpu: 'model_fp16.onnx' });
  const identity = modelIdentity(m.config);
  const main = { onnxFeatures: [{ id: 'embed', modelPath: path.join(dir, 'model.onnx'), executionProvider: 'cpu' }] };
  const branch = { onnxFeatures: [{ id: 'embed', modelPath: path.join(dir, 'model_fp16.onnx'), executionProvider: 'cuda' }] };
  validateRuntimeIdentity(identity, [main]); validateRuntimeIdentity(identity, [branch]);
  const values = JSON.parse(fs.readFileSync(path.join(ROOT, 'docs/design/lane-f-engine-jvm/evidence/E/values.json'))).values;
  const paired = arm => measurementIdentity(buildPlan(parseArgs(['e2-e3-load', '--arm', arm, '--workload', 'agent-idle']), values, ROOT),
    { sourceRoot: ROOT, outputRoot: ROOT, armTree: ARMS[arm], arm, group: 'e2-e3-load', workload: 'agent-idle', models: identity });
  assert.equal(paired('main').pairIdentity, paired('branch').pairIdentity);
  m.config.keys.push({ key: 'justsearch.rerank.chunks.model_path', value: 'onnx/reranker' });
  const first = modelIdentity(m.config);
  m.config.keys.at(-1).value = 'onnx/ner';
  assert.notDeepEqual(first.configuredSelections, modelIdentity(m.config).configuredSelections);
});
test('eligibility predicates and machine/corpus receipts are hashed acquisition behavior', () => {
  const values = JSON.parse(fs.readFileSync(path.join(ROOT, 'docs/design/lane-f-engine-jvm/evidence/E/values.json'))).values;
  const plan = buildPlan(parseArgs(['e2-e3-load', '--arm', 'main', '--workload', 'agent-idle']), values, ROOT);
  const opts = { sourceRoot: ROOT, outputRoot: ROOT, armTree: ARMS.main, arm: 'main' };
  const before = measurementIdentity(plan, opts);
  assert.ok(before.pairIdentityInputs.instruments['scripts/jseval/lane-f/e-acquisition-eligibility.mjs']);
  const after = measurementIdentity(plan, opts, file => file.endsWith('e-acquisition-eligibility.mjs')
    ? Buffer.concat([fs.readFileSync(file), Buffer.from('\n// changed eligibility')]) : fs.readFileSync(file));
  assert.notEqual(before.pairIdentity, after.pairIdentity);
});
