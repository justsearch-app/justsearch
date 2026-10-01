import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { ROOT, ARMS, buildPlan, parseArgs, collect, tableVerdicts } from './e-run.mjs';
import { soakWire } from './e-soak-wire.mjs';
import { requestLive } from './e456-live.mjs';
import { establishExecutedModels, captureEncoderSessions, finalizeInputs, digest } from './e-measured-inputs.mjs';
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
  const chat = path.join(store, 'chat.gguf'), external = path.join(raw, 'external.onnx');
  fs.writeFileSync(chat, 'chat'); fs.writeFileSync(external, 'external');
  return { raw, external, config: { keys: [{ key: 'justsearch.models.dir', value: store },
    { key: 'justsearch.reranker.model_path', value: external }] },
  status: { active: { modelPath: chat }, onnxFeatures: [] } };
}
test('missing runtime status fails before measurement and identity finalization rather than hashing an empty set', async t => {
  const m = models(t), context = { raw: m.raw, effectiveConfig: m.config,
    record: { groups: ['E5'], pairIdentityInputs: {} } };
  assert.throws(() => establishExecutedModels(m.config, [], true), /runtime AI status/);
  const request = async url => ({ ok: !url.endsWith('/status'), status: 503, json: async () => ({}) });
  await assert.rejects(captureEncoderSessions(context, 'pre-load', request), /before measurement.*HTTP 503/);
  const receipt = JSON.parse(fs.readFileSync(path.join(m.raw, 'encoder-sessions-pre-load.json')));
  assert.equal(receipt.aiError, 'HTTP 503');
  write(path.join(m.raw, 'effective-config.json'), m.config);
  assert.throws(() => finalizeInputs(context), /runtime model identity capture failed/);
  fs.rmSync(path.join(m.raw, 'encoder-sessions-pre-load.json'));
  assert.throws(() => finalizeInputs(context), /runtime AI status/);
});
test('configured external weights bind metadata without any runtime reference to that file', t => {
  const m = models(t), before = establishExecutedModels(m.config, [m.status], true);
  assert.ok(before.models.some(f => f.path === m.external.replaceAll('\\', '/')));
  fs.writeFileSync(m.external, 'changed external weights');
  assert.notEqual(digest(JSON.stringify(before)), digest(JSON.stringify(establishExecutedModels(m.config, [m.status], true))));
  fs.rmSync(m.external); assert.throws(() => establishExecutedModels(m.config, [m.status], true), /ENOENT/);
});
test('E1 may start with explicitly dormant encoders; unknown or active unidentifiable encoders fail', async t => {
  const m = models(t), dormant = { active: { modelPath: null },
    onnxFeatures: [{ id: 'embed', modelPath: null, modelActive: false, status: 'inactive' }] };
  const context = { raw: m.raw, effectiveConfig: m.config, record: { groups: ['E1'], pairIdentityInputs: {} } };
  await captureEncoderSessions(context, 'e1-pre-load', async () => ({ ok: true, json: async () => dormant }));
  assert.deepEqual(context.record.encoderSessions['e1-pre-load'].models.executedSelections, []);
  assert.throws(() => establishExecutedModels(m.config, [dormant], false), /no runtime model IDs/);
  for (const feature of [{ id: 'embed', status: 'unknown' }, { id: 'embed', modelActive: true, modelPath: null }]) {
    assert.throws(() => establishExecutedModels(m.config, [{ ...m.status, onnxFeatures: [feature] }], true), /unknown model identity/);
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
test('failed end-of-window model receipt cannot be replaced by a valid startup status', t => {
  const m = models(t), context = { raw: m.raw, record: { groups: ['E5'], pairIdentityInputs: {} } };
  write(path.join(m.raw, 'effective-config.json'), m.config);
  write(path.join(m.raw, 'encoder-sessions-start.json'), { ai: m.status });
  write(path.join(m.raw, 'bulk-load.json'), { encoderSessions: { end: { aiError: 'status unavailable' } } });
  assert.throws(() => finalizeInputs(context), /runtime model identity capture failed/);
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
