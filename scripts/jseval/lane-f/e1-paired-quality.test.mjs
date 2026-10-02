import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { ROOT, collect, projectionIdentity, qualityGateVerdict, reprojectRecord, tableVerdicts } from './e-run.mjs';

const values = JSON.parse(fs.readFileSync(path.join(ROOT, 'scripts/jseval/lane-f/test/fixtures/values-before-e0.json'), 'utf8')).values;
const report = current => ({ dataset: 'beir/scifact', mode: 'hybrid', current, baseline: .65, floor: .63,
  exit_code: 0, checks: [{ name: 'ndcg10-no-regression', status: 'ok' }] });
const record = arm => ({ arm, groups: ['E1'], id: `fixture-${arm}`, pairIdentity: 'same', valuesHash: 'fixed',
  projectionIdentity: projectionIdentity(values), commands: [], runIds: [], metrics: {}, gaps: {},
  clauses: { 'SearchTrace-shape': true, 'workflow-evidence-citations-cancellation': true, 'allowed-differences': true } });
function qualityRecords(mainNdcg, branchNdcg) {
  return Object.fromEntries([['main', mainNdcg], ['branch', branchNdcg]].map(([arm, current]) => {
    const r = record(arm);
    r.metrics.scifactHybridNdcg10 = current;
    r.clauses['baseline-quality'] = qualityGateVerdict(report(current));
    return [`E1/${arm}`, r];
  }));
}
const clause = (records, name) => tableVerdicts(records, values).find(r => r.clause === name)?.verdict;

test('E1 paired quality fails the historical-floor counterexample independently of the ratchet', () => {
  const records = qualityRecords(.80, .64);
  assert.equal(clause(records, 'baseline-quality'), 'pass');
  assert.equal(clause(records, 'paired-quality'), 'fail');
});

test('E1 paired quality applies the predeclared absolute 0.01 noise allowance', () => {
  assert.equal(clause(qualityRecords(.80, .80 - .009), 'paired-quality'), 'pass');
  assert.equal(clause(qualityRecords(.80, .80 - .01), 'paired-quality'), 'pass');
  assert.equal(clause(qualityRecords(.80, .80 - .011), 'paired-quality'), 'fail');
  const records = qualityRecords(.60, .60);
  records['E1/branch'].clauses['baseline-quality'] = false;
  assert.equal(clause(records, 'paired-quality'), 'pass');
  assert.equal(clause(records, 'baseline-quality'), 'fail');
});

test('E1 paired quality cannot pass missing or invalid values, even with stale pass booleans', () => {
  for (const arm of ['main', 'branch']) for (const value of [undefined, null, NaN, Infinity, -.1, '0.80']) {
    const records = qualityRecords(.80, .80);
    records[`E1/${arm}`].metrics.scifactHybridNdcg10 = value;
    for (const r of Object.values(records)) r.clauses['paired-quality'] = true;
    const row = tableVerdicts(records, values).find(r => r.clause === 'paired-quality');
    assert.equal(row?.verdict, 'unmeasurable');
    assert.match(row.reason, /Missing hybrid beir\/scifact nDCG@10/);
  }
  assert.equal(clause({}, 'paired-quality'), 'unmeasurable');
});

test('E1 paired quality keeps provenance and completed-arm checks', () => {
  for (const change of [{ pairIdentity: 'different' }, { valuesHash: 'different' }, { failure: 'failed run' }]) {
    const records = qualityRecords(.80, .80);
    Object.assign(records['E1/branch'], change);
    assert.equal(clause(records, 'paired-quality'), 'fail');
  }
  const records = qualityRecords(.80, .80);
  records['E1/main'].failure = 'failed reference';
  assert.equal(clause(records, 'paired-quality'), 'fail');
  delete records['E1/main'].failure;
  records['E1/main'].projectionIdentity = 'stale';
  assert.equal(clause(records, 'paired-quality'), 'unmeasurable');
});

test('E1 collection retains only measured hybrid SciFact nDCG@10 and clears missing metrics', t => {
  fs.mkdirSync(path.join(ROOT, 'tmp'), { recursive: true });
  const raw = fs.mkdtempSync(path.join(ROOT, 'tmp/e-quality-metric-'));
  t.after(() => fs.rmSync(raw, { recursive: true, force: true }));
  const gate = path.join(raw, 'relevance-gate.json');
  const r = record('main'), measured = report(.7588375946421915);
  fs.writeFileSync(gate, JSON.stringify(measured));
  collect({ record: r, raw, values });
  assert.equal(r.metrics.scifactHybridNdcg10, measured.current);
  assert.equal(r.clauses['baseline-quality'], true);
  assert.match(r.gaps['paired-quality'], /must be recomputed by table/);
  for (const change of [{ mode: 'lexical' }, { dataset: 'beir/other' }, { current: null }, { current: '0.80' }]) {
    fs.writeFileSync(gate, JSON.stringify({ ...measured, ...change }));
    collect({ record: r, raw, values });
    assert.equal(r.metrics.scifactHybridNdcg10, undefined);
    assert.match(r.gaps['paired-quality'], /Missing hybrid beir\/scifact nDCG@10/);
  }
  fs.unlinkSync(gate);
  collect({ record: r, raw, values });
  assert.equal(r.metrics.scifactHybridNdcg10, undefined);
});

test('E1 reprojection recovers an older record metric from retained raw without changing acquisition', t => {
  fs.mkdirSync(path.join(ROOT, 'tmp'), { recursive: true });
  const raw = fs.mkdtempSync(path.join(ROOT, 'tmp/e-quality-reproject-'));
  t.after(() => fs.rmSync(raw, { recursive: true, force: true }));
  const gate = path.join(raw, 'relevance-gate.json'), file = path.join(raw, 'record.json');
  fs.writeFileSync(gate, JSON.stringify(report(.7588375946421915)));
  const original = { ...record('main'), raw, recordFile: file, rawFiles: [gate],
    endedAt: '2026-10-01T17:00:00Z', pairIdentityInputs: { instruments: { sampler: ['original hash'] } } };
  fs.writeFileSync(file, JSON.stringify(original));
  const before = fs.readFileSync(file, 'utf8'), rawBefore = fs.readFileSync(gate, 'utf8');
  const projected = reprojectRecord(file, values, true);
  assert.equal(projected.metrics.scifactHybridNdcg10, .7588375946421915);
  assert.equal(projected.measuredProjection.metrics.scifactHybridNdcg10, undefined);
  for (const key of ['id', 'pairIdentity', 'pairIdentityInputs', 'valuesHash', 'endedAt', 'commands'])
    assert.deepEqual(projected[key], original[key]);
  assert.match(projected.reprojectionRawHashes[gate], /^[a-f0-9]{64}$/);
  assert.equal(fs.readFileSync(file, 'utf8'), before);
  assert.equal(fs.readFileSync(gate, 'utf8'), rawBefore);
});
