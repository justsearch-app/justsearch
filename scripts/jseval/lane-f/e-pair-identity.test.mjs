import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { ROOT, ARMS, buildPlan, parseArgs, latestRecords } from './e-run.mjs';
import { measurementIdentity, normalizePlan, instrumentFiles } from './e-pair-identity.mjs';

const values = JSON.parse(fs.readFileSync(path.join(ROOT, 'docs/design/lane-f-engine-jvm/evidence/E/values.json'))).values;
const options = (arm, group) => ({ sourceRoot: ROOT, outputRoot: ROOT, armTree: ARMS[arm], arm, group,
  machine: { cpu: ['test-cpu'], ramBytes: 1000 }, corpus: [['frozen-corpus.txt', 'fixed-hash']],
  workload: group, heap: values.heap, collector: values.collector });
const planFor = (arm, group) => buildPlan(parseArgs([group, '--arm', arm,
  ...(group === 'e2-e3-load' ? ['--workload', 'agent-idle'] : []),
  ...(group === 'e4-memory-soak' ? ['--window', '1'] : [])]), values, ROOT, { decision: 'recapture' });

for (const group of ['e1-quality', 'e2-e3-load', 'e4-memory-soak', 'e5-crash', 'e6-hang']) {
  test(`normalized acquisition plans match across arms: ${group}`, () => {
    const main = planFor('main', group), branch = planFor('branch', group);
    assert.deepEqual(normalizePlan(main, options('main', group)), normalizePlan(branch, options('branch', group)));
    const m = measurementIdentity(main, options('main', group)), b = measurementIdentity(branch, options('branch', group));
    assert.equal(m.pairIdentity, b.pairIdentity);
    assert.deepEqual(m.pairIdentityInputs, b.pairIdentityInputs);
  });
}
test('scorer, test, and unrelated instrument bytes do not enter measurement identity', () => {
  const plan = planFor('main', 'e2-e3-load'), opts = options('main', 'e2-e3-load');
  const normal = measurementIdentity(plan, opts);
  const read = file => {
    assert.ok(!file.endsWith('e-run.mjs'));
    assert.ok(!file.endsWith('e456-live.mjs'));
    assert.ok(!file.includes('.test.') && !file.includes('/tests/'));
    return fs.readFileSync(file);
  };
  const scorerChanged = measurementIdentity(plan, opts, file => file.endsWith('e-run.mjs')
    ? Buffer.from('a different scoring implementation') : read(file));
  assert.equal(normal.pairIdentity, scorerChanged.pairIdentity);
  assert.ok(normal.pairIdentityInputs.instruments['scripts/jseval/lane-f/e-start-ready.mjs']);
  assert.ok(normal.pairIdentityInputs.instruments['scripts/jseval/jseval/bulk_load.py']);
  assert.ok(normal.pairIdentityInputs.instruments['scripts/jseval/jseval/readiness.py']);
  assert.ok(normal.pairIdentityInputs.instruments['scripts/jseval/jseval/search_load.py']);
  assert.ok(normal.pairIdentityInputs.instruments['scripts/dev/lib/stop-exit-census.cjs']);
  assert.ok(normal.pairIdentityInputs.instruments['scripts/dev/lib/stop-exit-census.ps1']);
  assert.ok(!normal.pairIdentityInputs.instruments['scripts/jseval/lane-f/analyze-head-run.cjs']);
});
test('instrument bytes, imported dependencies, plan arguments and shared launch settings change identity', () => {
  const plan = planFor('main', 'e2-e3-load'), opts = options('main', 'e2-e3-load');
  const normal = measurementIdentity(plan, opts);
  for (const changed of ['head-rss-sampler.ps1', 'readiness.py']) {
    const modified = measurementIdentity(plan, opts, file => file.endsWith(changed)
      ? Buffer.concat([fs.readFileSync(file), Buffer.from('\n# instrument change')]) : fs.readFileSync(file));
    assert.notEqual(normal.pairIdentity, modified.pairIdentity);
  }
  const changed = structuredClone(plan);
  changed.find(c => c.label === 'bulk-agent-idle').args.push('--new-load-parameter', '1');
  assert.notEqual(normal.pairIdentity, measurementIdentity(changed, opts).pairIdentity);
  const launch = structuredClone(plan); launch.find(c => c.mode === 'start').env.JUSTSEARCH_HEAD_HEAP = '4g';
  assert.notEqual(normal.pairIdentity, measurementIdentity(launch, opts).pairIdentity);
});
test('resolved invocation/session/raw paths and arm-only Worker env normalize away', () => {
  const template = planFor('main', 'e4-memory-soak');
  const a = options('main', 'e4-memory-soak'), b = { ...a, invocation: 'run-unique-42', session: 'session-unique-42', runId: 'stack-unique-42',
    raw: path.join(ROOT, 'tmp/lane-f-e/e4-memory-soak/main/run-unique-42') };
  const replace = value => typeof value === 'string' ? value.replaceAll('${invocation}', b.invocation)
    .replaceAll('${session}', b.session).replaceAll('${runId}', b.runId) : Array.isArray(value) ? value.map(replace)
    : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).map(([k, v]) => [k, replace(v)])) : value;
  const resolved = replace(template);
  for (const c of resolved) if (c.env) {
    c.env.JUSTSEARCH_WORKER_HEAP = 'arm-worker-setting'; c.env.JUSTSEARCH_JVM_OPTS = 'arm-worker-options';
  }
  assert.equal(measurementIdentity(template, a).pairIdentity, measurementIdentity(resolved, b).pairIdentity);
});
test('E456 instruments include in-process acquisition and transitive fault/process helpers', () => {
  const files = instrumentFiles(planFor('branch', 'e6-hang')).map(f => path.relative(ROOT, f).replaceAll('\\', '/'));
  for (const file of ['scripts/jseval/lane-f/e456-live.mjs', 'scripts/supervisor-conformance/jdwp-fault.mjs',
    'scripts/supervisor-conformance/verified-crash.mjs', 'scripts/dev/lib/process-identity.cjs']) assert.ok(files.includes(file));
  assert.ok(!files.includes('scripts/jseval/lane-f/e-run.mjs'));
});
test('Python dependencies follow the actual cwd/PYTHONPATH checkout', t => {
  const scratch = fs.mkdtempSync(path.join(ROOT, 'tmp/e-python-identity-'));
  t.after(() => fs.rmSync(scratch, { recursive: true, force: true }));
  const pyRoot = path.join(scratch, 'scripts/jseval'), pkg = path.join(pyRoot, 'jseval');
  fs.mkdirSync(pkg, { recursive: true });
  fs.writeFileSync(path.join(pkg, '__init__.py'), '');
  fs.writeFileSync(path.join(pkg, 'minimal.py'), 'from . import dependency\n');
  fs.writeFileSync(path.join(pkg, 'dependency.py'), 'VALUE = 1\n');
  const plan = [{ label: 'measurement', executable: 'python', args: ['-m', 'jseval.minimal'], cwd: pyRoot, env: { PYTHONPATH: pyRoot } }];
  const opts = { sourceRoot: ROOT, outputRoot: scratch };
  const first = measurementIdentity(plan, opts);
  assert.ok(first.pairIdentityInputs.instruments['scripts/jseval/jseval/dependency.py']);
  fs.writeFileSync(path.join(pkg, 'dependency.py'), 'VALUE = 2\n');
  assert.notEqual(first.pairIdentity, measurementIdentity(plan, opts).pairIdentity);
});
test('E4 aggregates corresponding window protocols rather than equating 55 and 10 minute windows', t => {
  const scratch = fs.mkdtempSync(path.join(ROOT, 'tmp/e-pair-windows-'));
  t.after(() => fs.rmSync(scratch, { recursive: true, force: true }));
  for (const arm of ['main', 'branch']) {
    const dir = path.join(scratch, 'docs/design/lane-f-engine-jvm/evidence/E/e4-memory-soak', arm);
    fs.mkdirSync(dir, { recursive: true });
    const runs = ['1', '2', '3'].map(window => {
      const record = path.join(dir, `${window}.json`);
      fs.writeFileSync(record, JSON.stringify({ id: window, arm, window, groups: ['E4'], startedAt: window, recordFile: record,
        pairIdentity: window === '3' ? '10-minute-protocol' : '55-minute-protocol', valuesHash: 'values', revision: arm,
        metrics: {}, clauses: { 'window-duration': true } }));
      return { record };
    });
    fs.writeFileSync(path.join(dir, 'index.json'), JSON.stringify({ runs }));
  }
  const records = latestRecords(scratch);
  assert.equal(records['E4/main'].clauses['owner-duration'], true);
  assert.equal(records['E4/main'].pairIdentity, records['E4/branch'].pairIdentity);
});
test('E4 owner-duration accepts one build across evidence-only revisions and refuses a changed build', t => {
  // The branch arm is the driver tree: each slot's evidence commit moves HEAD (2026-10-04).
  const aggregate = stamps => {
    const scratch = fs.mkdtempSync(path.join(ROOT, 'tmp/e-pair-build-'));
    t.after(() => fs.rmSync(scratch, { recursive: true, force: true }));
    const dir = path.join(scratch, 'docs/design/lane-f-engine-jvm/evidence/E/e4-memory-soak', 'branch');
    fs.mkdirSync(dir, { recursive: true });
    const runs = ['1', '2', '3'].map((window, i) => {
      const record = path.join(dir, `${window}.json`);
      fs.writeFileSync(record, JSON.stringify({ id: window, arm: 'branch', window, groups: ['E4'], startedAt: window,
        recordFile: record, pairIdentity: window, valuesHash: 'values', revision: `evidence-commit-${window}`,
        encoderSessions: { start: { manifest: { head: { buildStamp: stamps[i] } } } },
        metrics: {}, clauses: { 'window-duration': true } }));
      return { record };
    });
    fs.writeFileSync(path.join(dir, 'index.json'), JSON.stringify({ runs }));
    return latestRecords(scratch)['E4/branch'].clauses['owner-duration'];
  };
  assert.equal(aggregate(['same', 'same', 'same']), true);
  assert.equal(aggregate(['same', 'rebuilt', 'same']), false);
  assert.equal(aggregate([undefined, 'same', 'same']), false);
});
