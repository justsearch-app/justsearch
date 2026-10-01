import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { ARMS, ROOT, parseArgs, buildPlan, verdict, tableVerdicts, fillValues, qualityGateVerdict, main } from './e-run.mjs';
import { captureWorkload } from './admission-loop.mjs';

const document = JSON.parse(fs.readFileSync(path.join(ROOT, 'docs/design/lane-f-engine-jvm/evidence/E/values.json'), 'utf8'));
const values = document.values;
for (const command of ['e1-quality', 'e2-e3-load', 'e5-crash', 'e6-hang']) {
  test(`parse ${command}`, () => assert.deepEqual(parseArgs([command, '--arm', 'main', '--dry-run']),
    { command, arm: 'main', dryRun: true }));
}
test('strict options and required window', () => {
  for (const argv of [[], ['bad'], ['e1-quality'], ['e1-quality', '--arm', 'other'],
    ['e1-quality', '--arm', 'main', '--arm', 'main'], ['table', '--arm', 'main'],
    ['e0-values', '--arm', 'branch'], ['e4-memory-soak', '--arm', 'main'],
    ['e4-memory-soak', '--arm', 'main', '--window', '4'], ['table', '--window', '1'],
    ['table', '--dry-run', '--dry-run'], ['table', '--unknown', 'value']]) assert.throws(() => parseArgs(argv));
  assert.equal(parseArgs(['e4-memory-soak', '--arm', 'branch', '--window', '3']).window, '3');
});
for (const arm of ['main', 'branch']) {
  test(`quality command construction ${arm}`, () => {
    const plan = buildPlan(parseArgs(['e1-quality', '--arm', arm]), values);
    const starts = plan.filter(c => c.mode === 'start');
    assert.equal(starts.length, 4);
    for (const start of starts) {
      assert.equal(start.cwd, ARMS[arm]);
      assert.equal(start.args[0], path.join(ARMS[arm], 'scripts/dev/dev-runner.cjs'));
      assert.ok(start.args.includes('--skip-build'));
      assert.equal(start.args[start.args.indexOf('--lease-duration-sec') + 1], '3600');
      assert.equal(start.env.JUSTSEARCH_HEAD_HEAP, '2g');
      assert.match(start.env.UI_OPTS, /UseG1GC/);
    }
    assert.equal(plan.filter(c => c.label.match(/^fixture-\d+$/)).length, 3);
    assert.ok(plan.filter(c => c.mode === 'stop').every(c => c.args.includes('--run') && !c.args.includes('--active')));
    assert.ok(plan.every(c => !JSON.stringify(c).includes('gradlew')));
  });
  test(`load uses both foreground modes and normal agent workload ${arm}`, () => {
    const plan = buildPlan(parseArgs(['e2-e3-load', '--arm', arm]), values);
    const evals = plan.filter(c => c.executable === 'python');
    assert.equal(evals.length, 4);
    assert.deepEqual(evals.map(c => c.args[c.args.indexOf('--search-load-search-mode') + 1]), ['hybrid', 'lexical', 'hybrid', 'lexical']);
    assert.equal(plan.filter(c => c.label.startsWith('admission-')).length, 2);
    assert.ok(plan.filter(c => c.label.startsWith('admission-')).every(c => c.args.includes('--capture-workload')));
    assert.ok(plan.filter(c => c.label.startsWith('rss-')).every(c => c.args.includes('-IncludeSplitWorker')));
  });
}
test('soak windows come from values, without a continuous two-hour claim', () => {
  assert.deepEqual(['1', '2', '3'].map(window => buildPlan(parseArgs(['e4-memory-soak', '--arm', 'main', '--window', window]), values)
    .find(c => c.mode === 'soak').minutes), [55, 55, 10]);
});
test('dry run prints plans only, including paired fixture comparison', async () => {
  const prior = console.log;
  const output = [];
  const before = fs.existsSync(path.join(ROOT, 'tmp/lane-f-e')) ? fs.readdirSync(path.join(ROOT, 'tmp/lane-f-e')) : null;
  console.log = text => output.push(text);
  try {
    for (const arm of ['main', 'branch']) await main(['e1-quality', '--arm', arm, '--dry-run']);
    await main(['table', '--dry-run']);
  } finally { console.log = prior; }
  assert.equal(output.length, 3);
  assert.ok(JSON.parse(output[2]).commands.some(c => c.label === 'fixture-gate'));
  const after = fs.existsSync(path.join(ROOT, 'tmp/lane-f-e')) ? fs.readdirSync(path.join(ROOT, 'tmp/lane-f-e')) : null;
  assert.deepEqual(after, before);
});
test('three-valued verdict never hides a failure behind missing evidence', () => {
  assert.equal(verdict([true, true]), 'pass');
  assert.equal(verdict([true, undefined]), 'unmeasurable');
  assert.equal(verdict([false, undefined]), 'fail');
  assert.equal(verdict([]), 'unmeasurable');
});
test('an unpinned quality gate exit zero is not a baseline pass', () => {
  assert.equal(qualityGateVerdict({ exit_code: 0, checks: [{ name: 'baseline-pinned', status: 'skip' }] }), undefined);
  assert.equal(qualityGateVerdict({ exit_code: 1 }), false);
  assert.equal(qualityGateVerdict({ exit_code: 0, current: .6, baseline: .61, floor: .59,
    checks: [{ name: 'ndcg10-no-regression', status: 'ok' }] }), true);
});
const record = (arm, groups = ['E2', 'E3']) => ({ arm, groups, id: `fixture-${arm}`, pairIdentity: 'same', valuesHash: 'fixed',
  clauses: { 'foreground-p95': true, 'agent-api-p95': true, 'chunks-per-second-under-foreground-load': true },
  metrics: { searchP95: { hybrid: 100, lexical: 50 }, agentP95: 200, chunksPerSec: 20 } });
const bounds = { ...values, foregroundSearchP95Ceiling: { ceilingMs: { hybrid: 110, lexical: 55 } },
  agentLoopApiP95Ceiling: { ceilingMs: 220 }, indexingProgressFraction: { minimumChunksPerSec: 18 } };
function pairRecords() {
  const m = record('main'), b = record('branch');
  return { 'E2/main': m, 'E3/main': m, 'E2/branch': b, 'E3/branch': b };
}
const clause = (records, name) => tableVerdicts(records, bounds).find(r => r.clause === name).verdict;
test('table numeric bounds include exact limits and reject regression', () => {
  const records = pairRecords();
  records['E2/branch'].metrics.searchP95.hybrid = 110;
  assert.equal(clause(records, 'foreground-p95'), 'pass');
  records['E2/branch'].metrics.searchP95.hybrid = 110.01;
  assert.equal(clause(records, 'foreground-p95'), 'fail');
  records['E3/branch'].metrics.chunksPerSec = 17.99;
  assert.equal(clause(records, 'chunks-per-second-under-foreground-load'), 'fail');
});
test('table missing measurements, nonfinite numbers, and mismatched provenance cannot pass', () => {
  const records = pairRecords();
  records['E2/branch'].metrics.agentP95 = NaN;
  assert.equal(clause(records, 'agent-api-p95'), 'unmeasurable');
  records['E2/branch'].pairIdentity = 'different';
  assert.equal(clause(records, 'foreground-p95'), 'fail');
  assert.equal(clause({}, 'foreground-p95'), 'unmeasurable');
  assert.equal(tableVerdicts({}, bounds).find(r => r.group === 'E7').verdict, 'unmeasurable');
});
test('E0 fixes bounds from MAIN and rejects a refit after branch observations', () => {
  const records = { 'E1/main': record('main', ['E1']), 'E2/main': record('main') };
  const filled = fillValues(document, records);
  assert.equal(filled.values.foregroundSearchP95Ceiling.ceilingMs.hybrid, 100 * 1.1);
  assert.equal(filled.values.agentLoopApiP95Ceiling.ceilingMs, 200 * 1.1);
  assert.equal(filled.values.indexingProgressFraction.minimumChunksPerSec, 18);
  assert.equal(filled.values.foregroundSearchP95Ceiling.measuredAtE0, false);
  records['E1/branch'] = { ...record('branch'), failure: 'failed launch' };
  assert.throws(() => fillValues(document, records), /after any branch/);
});
test('E0 does not substitute baseline sanity or zero throughput for measured load', () => {
  assert.throws(() => fillValues(document, {}), /MAIN/);
  const records = { 'E1/main': record('main'), 'E2/main': record('main') };
  records['E2/main'].metrics.chunksPerSec = 0;
  assert.throws(() => fillValues(document, records), /cannot invent/);
  assert.equal(document.values.foregroundSearchP95Ceiling.measuredAtE0, true);
});
test('ordinary workload records wire refusals and terminates on its owned stop marker', async () => {
  const directory = fs.mkdtempSync(path.join(ROOT, 'tmp/e-driver-wire-'));
  const stop = path.join(directory, 'stop');
  const originalFetch = globalThis.fetch;
  const observed = [];
  globalThis.fetch = async (url, options) => {
    observed.push({ url, options });
    if (url.endsWith('/api/mcp/token')) return Response.json({ token: 'wire-fixture-token' });
    if (url.endsWith('/api/knowledge/search')) return Response.json({ errorCode: 'ADMISSION_ENGINE_LIMIT', retrySafe: true },
      { status: 429, headers: { 'Retry-After': '1' } });
    fs.writeFileSync(stop, 'stop');
    return new Response('event: run_started\ndata: {"runId":"wire-chat"}\n\nevent: done\ndata: {}\n\n', { status: 200 });
  };
  try {
    await captureWorkload(directory, 'http://127.0.0.1:33221', stop);
    const text = fs.readFileSync(path.join(directory, 'workload.json'), 'utf8');
    const capture = JSON.parse(text);
    assert.equal(capture.offered, 2);
    assert.deepEqual(capture.requests.map(r => r.operation), ['search', 'chat']);
    assert.equal(capture.requests[0].code, 'ADMISSION_ENGINE_LIMIT');
    assert.equal(capture.requests[0].retrySafe, true);
    assert.equal(capture.requests[1].terminal.doneCount, 1);
    assert.ok(capture.requests.every(r => Number.isFinite(r.durationMs)));
    assert.ok(observed.slice(1).every(r => r.options.headers['X-JustSearch-Session'] === 'wire-fixture-token'));
    assert.ok(!text.includes('wire-fixture-token'));
  } finally { globalThis.fetch = originalFetch; }
});
