import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { ROOT, collect, latestRecords, projectionIdentity, qualityGateVerdict, reprojectRecord, tableVerdicts } from './e-run.mjs';

const values = JSON.parse(fs.readFileSync(path.join(ROOT, 'scripts/jseval/lane-f/test/fixtures/values-before-e0.json'), 'utf8')).values;
const report = current => ({ dataset: 'beir/scifact', mode: 'hybrid', current, baseline: .65, floor: .63,
  exit_code: 0, checks: [{ name: 'ndcg10-no-regression', status: 'ok' }] });
const record = arm => ({ arm, groups: ['E1'], id: `fixture-${arm}`, pairIdentity: 'same', valuesHash: 'fixed',
  projectionIdentity: projectionIdentity(values), commands: [], runIds: [], metrics: {}, gaps: {},
  clauses: { 'SearchTrace-shape': true, 'workflow-evidence-citations-cancellation': true, 'allowed-differences': true } });
function qualityRecords(mainNdcg, branchNdcg) {
  const records = Object.fromEntries([['main', mainNdcg], ['branch', branchNdcg]].map(([arm, current]) => {
    const r = record(arm);
    if (arm === 'main') r.id = referenceId;
    r.metrics.scifactHybridNdcg10 = current;
    r.clauses['baseline-quality'] = qualityGateVerdict(report(current));
    return [`E1/${arm}`, r];
  }));
  records['E1/quality-reference'] = records['E1/main'];
  return records;
}
const clause = (records, name) => tableVerdicts(records, values).find(r => r.clause === name)?.verdict;

const referenceId = '2026-10-01T16-27-42-378Z-0c6510b3';
const referenceNdcg = .7588375946421915;
function evidenceFixture(t, branchNdcg = .70) {
  fs.mkdirSync(path.join(ROOT, 'tmp'), { recursive: true });
  const root = fs.mkdtempSync(path.join(ROOT, 'tmp/e-quality-reference-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const save = (arm, id, current, startedAt) => {
    const dir = path.join(root, 'docs/design/lane-f-engine-jvm/evidence/E/e1-quality', arm);
    fs.mkdirSync(dir, { recursive: true });
    const r = { ...record(arm), id, startedAt, endedAt: startedAt, raw: path.join(root, 'raw', id),
      recordFile: path.join(dir, `${id}.json`), metrics: { scifactHybridNdcg10: current } };
    r.clauses['baseline-quality'] = true;
    fs.writeFileSync(r.recordFile, JSON.stringify(r));
    const index = path.join(dir, 'index.json');
    const entries = fs.existsSync(index) ? JSON.parse(fs.readFileSync(index)).runs : [];
    fs.writeFileSync(index, JSON.stringify({ runs: [...entries, { record: r.recordFile }] }));
    return r;
  };
  const reference = save('main', referenceId, referenceNdcg, '2026-10-01T16:27:43Z');
  save('branch', 'branch', branchNdcg, '2026-10-02T01:00:00Z');
  return { root, reference, save };
}

test('E1 pinned quality rejects 0.70 even when latest MAIN is a newer 0.70 capture', t => {
  const { root, save } = evidenceFixture(t);
  save('main', 'newer-main', .70, '2026-10-02T02:00:00Z');
  const records = latestRecords(root);
  assert.equal(records['E1/main'].id, 'newer-main');
  const row = tableVerdicts(records, values).find(r => r.clause === 'paired-quality');
  assert.equal(row.verdict, 'fail');
  assert.match(row.sources, new RegExp(referenceId));
});

test('E1 latestRecords selecting a different MAIN cannot change the pinned quality verdict', t => {
  const { root, save } = evidenceFixture(t, .75);
  const before = clause(latestRecords(root), 'paired-quality');
  assert.equal(before, 'pass');
  save('main', 'newer-main', .70, '2026-10-02T02:00:00Z');
  assert.equal(clause(latestRecords(root), 'paired-quality'), before);
  save('main', 'even-newer-main', .90, '2026-10-02T03:00:00Z');
  assert.equal(clause(latestRecords(root), 'paired-quality'), before);
});

test('E1 missing pinned record is a gap even with a passing latest MAIN capture', t => {
  const { root, reference, save } = evidenceFixture(t, .80);
  save('main', 'newer-main', .80, '2026-10-02T02:00:00Z');
  // Keep its index entry: absence must still be a clause gap rather than a substituted capture.
  fs.unlinkSync(reference.recordFile);
  const row = tableVerdicts(latestRecords(root), values).find(r => r.clause === 'paired-quality');
  assert.equal(row.verdict, 'unmeasurable');
  assert.match(row.reason, new RegExp(referenceId));
});

test('E1 pinned record recovers its metric from raw; missing both never falls back to another capture', t => {
  const { root, reference, save } = evidenceFixture(t);
  save('main', 'newer-main', .70, '2026-10-02T02:00:00Z');
  delete reference.metrics.scifactHybridNdcg10;
  fs.writeFileSync(reference.recordFile, JSON.stringify(reference));
  assert.equal(clause(latestRecords(root), 'paired-quality'), 'unmeasurable');
  fs.mkdirSync(reference.raw, { recursive: true });
  fs.writeFileSync(path.join(reference.raw, 'relevance-gate.json'), JSON.stringify(report(referenceNdcg)));
  const before = fs.readFileSync(reference.recordFile, 'utf8');
  assert.equal(clause(latestRecords(root), 'paired-quality'), 'fail');
  assert.equal(fs.readFileSync(reference.recordFile, 'utf8'), before);
});

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
