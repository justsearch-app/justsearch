import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { ARMS, ROOT, MAIN_REVISION, checkFixturePins, parseArgs, buildPlan, verdict, tableVerdicts, fillValues, qualityGateVerdict, verifySharedModels, windowValidity, projectLoad, mergeLoadRecords, latestRecords, stageCompletionRates, compareStageRates, reprojectRecord, main } from './e-run.mjs';
import { ingestAccepted } from './fixture-ingest.mjs';
import { captureWorkload } from './admission-loop.mjs';
import { activateChat, chatReady } from './e-start-ready.mjs';
import { collect } from './e-run.mjs';

const document = JSON.parse(fs.readFileSync(path.join(ROOT, 'docs/design/lane-f-engine-jvm/evidence/E/values.json'), 'utf8'));
const values = document.values;

for (const arm of ['main', 'branch']) for (const group of ['e2-e3-load', 'e4-memory-soak', 'e5-crash', 'e6-hang']) {
  test(`chat activation follows every capable start: ${group}/${arm}`, () => {
    const options = parseArgs([group, '--arm', arm,
      ...(group === 'e2-e3-load' ? ['--workload', 'scripted-agent'] : []),
      ...(group === 'e4-memory-soak' ? ['--window', '1'] : [])]);
    const plan = buildPlan(options, values);
    for (const [i, c] of plan.entries()) if (c.mode === 'start') {
      const activation = plan[i + 1];
      assert.equal(activation.mode, 'ai-activate');
      assert.equal(activation.endpoint, '/api/ai/runtime/activate');
      assert.deepEqual(activation.body, { variantId: 'cuda12', chatProfile: 'standard' });
      assert.equal(activation.timeoutSeconds, 300);
      assert.equal(activation.readyEndpoint, '/api/ai/runtime/status');
    }
    assert.equal(plan.filter(c => c.mode === 'ai-activate').length, plan.filter(c => c.mode === 'start').length);
  });
}
test('E1 retains its fixture-owned chat activation', () => {
  for (const arm of ['main', 'branch']) assert.ok(!buildPlan(parseArgs(['e1-quality', '--arm', arm]), values, ROOT,
    { decision: 'recapture' }).some(c => c.mode === 'ai-activate'));
});

const activationCommand = () => buildPlan(parseArgs(['e2-e3-load', '--arm', 'main', '--workload', 'scripted-agent']), values)
  .find(c => c.mode === 'ai-activate');
test('activation records the standard request/response and waits for realized readiness without retaining a token', async t => {
  const raw = fs.mkdtempSync(path.join(ROOT, 'tmp/e-activate-'));
  t.after(() => fs.rmSync(raw, { recursive: true, force: true }));
  const context = { raw, record: { commands: [] }, sequence: 0, token: 'private-boot-token' };
  const calls = []; let clock = 0;
  await activateChat(activationCommand(), context, { now: () => clock, sleep: async ms => { clock += ms; },
    fetch: async (url, options) => {
      calls.push({ url, options });
      if (options.method === 'POST') return Response.json({ activation: { state: 'activating' } }, { status: 202 });
      return Response.json(clock ? { active: { modelPath: 'model.gguf', chatProfile: 'standard' } }
        : { active: { modelPath: 'old-model.gguf', chatProfile: 'compact' }, activation: { state: 'completed' } });
    } });
  assert.equal(clock, 2000);
  assert.deepEqual(JSON.parse(calls[0].options.body), { variantId: 'cuda12', chatProfile: 'standard' });
  assert.equal(calls[0].options.headers['X-JustSearch-Session'], context.token);
  assert.equal(calls.length, 3);
  for (const c of context.record.commands) {
    assert.ok(fs.existsSync(c.requestFile) && fs.existsSync(c.rawFile));
    assert.ok(!fs.readFileSync(c.requestFile, 'utf8').includes(context.token));
    assert.ok(!JSON.stringify(c).includes(context.token));
  }
  assert.equal(chatReady({ activation: { state: 'completed' } }, 'standard'), false);
  assert.equal(chatReady({ active: { activeVariantId: 'cuda12' }, activation: { state: 'completed' }, chatProfile: 'standard' }, 'standard'), true);
});
test('activation timeout fails the invocation prerequisite within 300 seconds', async t => {
  const raw = fs.mkdtempSync(path.join(ROOT, 'tmp/e-activate-timeout-'));
  t.after(() => fs.rmSync(raw, { recursive: true, force: true }));
  const context = { raw, record: { commands: [] }, sequence: 0 }; let clock = 0;
  await assert.rejects(activateChat(activationCommand(), context, { now: () => clock,
    sleep: async ms => { clock += ms; }, fetch: async () => Response.json({ activation: { state: 'activating' } }) }),
  /AI activation timed out: standard profile did not become ready within 300 s/);
  assert.equal(clock, 300000);
});

test('AI_OFFLINE and every invalid streamed terminal are excluded from admitted p95 and fail the wire clause', () => {
  const bad = { status: 200, durationMs: 8, streamed: true, terminal: { doneCount: 0, errorCount: 1, eof: true, errorCode: 'AI_OFFLINE' } };
  const r = { ...record('main'), workload: 'scripted-agent', gaps: {} };
  projectLoad(r, windowLoad(), [bad, { status: 200, durationMs: 300 }], values);
  assert.equal(r.metrics.agentP95, 300);
  assert.equal(r.metrics.agentAdmitted, 1);
  assert.deepEqual(r.metrics.agentTerminalErrors, { AI_OFFLINE: 1 });
  assert.match(r.gaps.agentTerminalErrors, /AI_OFFLINE/);
  assert.equal(r.clauses['no-timeout-or-5xx'], false);
  for (const terminal of [{ doneCount: 0, errorCount: 0, eof: true }, { doneCount: 2, errorCount: 0, eof: true },
    { doneCount: 1, errorCount: 0, eof: false }, { doneCount: 1, errorCount: 0, eof: 'true' }, undefined]) {
    projectLoad(r, windowLoad(), [{ ...bad, terminal }], values);
    assert.equal(r.metrics.agentP95, undefined);
    assert.equal(r.metrics.agentAdmitted, 0);
    assert.equal(r.clauses['no-timeout-or-5xx'], false);
  }
  projectLoad(r, windowLoad(), [{ ...bad, terminal: { doneCount: 1, errorCount: 0, eof: true } }], values);
  assert.equal(r.metrics.agentAdmitted, 1);
  assert.equal(r.clauses['no-timeout-or-5xx'], true);
  assert.deepEqual(r.metrics.agentTerminalErrors, {});
});
test('E4 collector excludes offline streams and fails the foreground workload', t => {
  const raw = fs.mkdtempSync(path.join(ROOT, 'tmp/e-offline-soak-'));
  t.after(() => fs.rmSync(raw, { recursive: true, force: true }));
  fs.writeFileSync(path.join(raw, 'summary.json'), JSON.stringify({ search_load: { errors: 0 } }));
  fs.writeFileSync(path.join(raw, 'workload.json'), JSON.stringify({ requests: [{ status: 200, durationMs: 8,
    streamed: true, terminal: { doneCount: 0, errorCount: 1, eof: true, errorCode: 'AI_OFFLINE' } }] }));
  const r = { ...record('main', ['E4']), gaps: {}, commands: [{ label: 'soak-cycle-1', code: 0 }] };
  r.clauses['index-agent-reconfigure-workload'] = true;
  collect({ record: r, raw, values });
  assert.equal(r.metrics.agentP95, undefined);
  assert.equal(r.metrics.agentAdmitted, 0);
  assert.deepEqual(r.metrics.agentTerminalErrors, { AI_OFFLINE: 1 });
  assert.equal(r.clauses['no-timeout-or-5xx'], false);
  assert.equal(r.clauses['index-agent-reconfigure-workload'], false);
});
test('shared model gate rejects missing and arm-local model directories', () => {
  const config = value => ({ keys: [{ key: 'justsearch.models.dir', value }] });
  const shared = path.resolve(ARMS.main, '../../../models');
  assert.equal(verifySharedModels(config(shared)), shared);
  assert.throws(() => verifySharedModels({}), /Shared models required/);
  for (const tree of Object.values(ARMS)) assert.throws(() => verifySharedModels(config(path.join(tree, 'models'))), /Shared models required/);
});
for (const command of ['e1-quality', 'e5-crash', 'e6-hang']) {
  test(`parse ${command}`, () => assert.deepEqual(parseArgs([command, '--arm', 'main', '--dry-run']),
    { command, arm: 'main', dryRun: true }));
}
test('strict options and required window', () => {
  assert.equal(parseArgs(['e1-quality', '--arm', 'main', '--repo-root', ARMS.branch]).repoRoot, ARMS.branch);
  for (const argv of [[], ['bad'], ['e1-quality'], ['e1-quality', '--arm', 'other'],
    ['e1-quality', '--arm', 'main', '--arm', 'main'], ['table', '--arm', 'main'],
    ['e0-values', '--arm', 'branch'], ['e4-memory-soak', '--arm', 'main'],
    ['e4-memory-soak', '--arm', 'main', '--window', '4'], ['table', '--window', '1'],
    ['table', '--dry-run', '--dry-run'], ['table', '--unknown', 'value']]) assert.throws(() => parseArgs(argv));
  assert.equal(parseArgs(['e4-memory-soak', '--arm', 'branch', '--window', '3']).window, '3');
});
test('E2/E3 requires an explicit workload and rejects it elsewhere', () => {
  for (const arm of ['main', 'branch']) for (const workload of ['agent-idle', 'scripted-agent'])
    assert.equal(parseArgs(['e2-e3-load', '--arm', arm, '--workload', workload]).workload, workload);
  for (const args of [[], ['--workload', 'other'], ['--workload', 'agent-idle', '--workload', 'scripted-agent']])
    assert.throws(() => parseArgs(['e2-e3-load', '--arm', 'main', ...args]));
  assert.throws(() => parseArgs(['table', '--workload', 'agent-idle']));
});
const windowLoad = () => ({ expectedDocuments: 30000, durationSeconds: 1200, blockSeconds: 600, modes: ['hybrid', 'lexical'], concurrency: 1,
  startedAtMs: 100000, endedAtMs: 1300000, queryPoolHash: 'same-queries', searchP95: { hybrid: 100, lexical: 50 }, admissionExitCode: 0,
  samples: Array.from({ length: 241 }, (_, i) => ({ active: true, offsetSeconds: i * 5,
    observedAtMs: 100000 + i * 5000, indexedDocuments: i, chunkEmbeddingCompletedCount: i * 100,
    chunkEmbeddingPendingCount: 50, pendingJobs: 0,
    raw: { worker: { core: { indexedDocuments: i }, enrichment: {
      embeddingDocCount: 30000, embeddingCompletedCount: i * 100, embeddingPendingCount: 50,
      spladeDocCount: 30000, spladeCompletedCount: i * 100, spladePendingCount: 50,
      completedNerCount: i * 100, pendingNerCount: 50,
      chunk: { chunkDocCount: 30000, chunkEmbeddingCompletedCount: i * 100, chunkEmbeddingPendingCount: 50 },
    } } } })),
  requests: [{ mode: 'hybrid', status: 200, durationMs: 100 }, { mode: 'lexical', status: 200, durationMs: 50 }] });
test('window completion invalidates indexing comparison; missing/stale/gapped samples cannot pass', () => {
  assert.equal(windowValidity(windowLoad()), true);
  const finished = windowLoad(); finished.samples[100].active = false;
  assert.equal(windowValidity(finished), false);
  for (const mutate of [l => l.samples.pop(), l => l.samples[2].error = '503',
    l => l.samples.splice(3, 3), l => l.samples[4].chunkEmbeddingCompletedCount = 0,
    l => l.endedAtMs += 10000]) {
    const l = windowLoad(); mutate(l); assert.equal(windowValidity(l), undefined);
  }
});
test('stage order changes do not dilute a drained stage or count pre-start idle time', () => {
  const main = windowLoad(), branch = windowLoad();
  const configure = (load, start, duration) => {
    for (const s of load.samples) {
      const t = s.offsetSeconds, done = Math.max(0, Math.min(t - start, duration)) * 10;
      const e = s.raw.worker.enrichment;
      e.embeddingCompletedCount = done;
      e.embeddingPendingCount = t >= start && t < start + duration ? 100 : 0;
    }
  };
  configure(main, 0, 300); configure(branch, 600, 300);
  const mr = stageCompletionRates(main), br = stageCompletionRates(branch);
  assert.equal(mr.embed.rate, 10); assert.equal(br.embed.rate, 10);
  assert.equal(br.embed.activeSeconds, 300);
  assert.equal(br.embed.startObservedAtMs, 700000);
  assert.equal(compareStageRates(br, mr).check, true);
  br.embed.rate = 9; assert.equal(compareStageRates(br, mr).check, true);
  br.embed.rate = 8.99; assert.equal(compareStageRates(br, mr).check, false);
  br.embed.rate = 0; assert.equal(compareStageRates(br, mr).check, false);
  delete br.embed.rate; assert.equal(compareStageRates(br, mr).check, undefined);
});
test('MAIN whole-document-first progress compares primary/embed/SPLADE and skips zero chunks/NER', () => {
  const load = windowLoad();
  for (const s of load.samples) {
    s.chunkEmbeddingCompletedCount = 0;
    s.raw.worker.enrichment.chunk.chunkEmbeddingCompletedCount = 0;
    s.raw.worker.enrichment.completedNerCount = 0;
  }
  const r = { ...record('main'), workload: 'agent-idle', gaps: {} };
  projectLoad(r, load, [], values);
  assert.equal(r.metrics.chunksPerSec, 0);
  assert.equal(r.metrics.stageComparisons.chunk_embed.status, 'not-compared');
  assert.equal(r.metrics.stageComparisons.ner.status, 'not-compared');
  assert.equal(r.clauses['stage-completion-rates-under-foreground-load'], true);
  assert.equal(r.clauses['chunks-per-second-under-foreground-load'], undefined);
  const scripted = { ...structuredClone(r), workload: 'scripted-agent' };
  projectLoad(scripted, load, [{ status: 200, durationMs: 200 }], values);
  const merged = mergeLoadRecords({ 'agent-idle': r, 'scripted-agent': scripted });
  const frozen = fillValues(document, { 'E1/main': record('main'), 'E2/main': merged });
  const chunk = frozen.values.indexingProgressFraction.stagesByWorkload['agent-idle'].chunk_embed;
  assert.equal(chunk.rate, 0); assert.equal(chunk.comparison, 'not-compared');
  assert.equal(chunk.minimumRate, undefined);
  const rates = stageCompletionRates(load);
  for (const rate of Object.values(rates)) rate.rate = 0;
  assert.equal(compareStageRates(rates, rates).check, undefined);
  for (const s of load.samples) {
    const e = s.raw.worker.enrichment;
    e.embeddingCompletedCount = e.spladeCompletedCount = 0;
    s.raw.worker.core.indexedDocuments = 0;
  }
  projectLoad(r, load, [], values);
  assert.equal(r.clauses['stage-completion-rates-under-foreground-load'], undefined);
});
test('completed counter fallback is explicit and never replaces an invalid present counter', () => {
  const load = windowLoad();
  for (const s of load.samples) {
    const e = s.raw.worker.enrichment;
    delete e.embeddingCompletedCount;
    e.embeddingPendingCount = 30000 - s.offsetSeconds * 10;
  }
  const rate = stageCompletionRates(load).embed;
  assert.equal(rate.rate, 10);
  assert.match(rate.completedSources[0], /expected - embeddingPendingCount/);
  load.samples[0].raw.worker.enrichment.embeddingCompletedCount = null;
  assert.equal(stageCompletionRates(load).embed.rate, undefined);
});
test('reproject is offline, preserves original scores/provenance and refuses missing raw files', () => {
  assert.throws(() => parseArgs(['reproject']), /record/);
  assert.throws(() => parseArgs(['table', '--record', 'record.json']), /record/);
  assert.equal(parseArgs(['reproject', '--record', 'record.json']).record, 'record.json');
  const scratch = fs.mkdtempSync(path.join(ROOT, 'tmp/e-reproject-'));
  const file = path.join(scratch, 'record.json'), dir = path.join(scratch, 'agent-idle');
  fs.mkdirSync(dir);
  const bulk = path.join(dir, 'bulk-load.json'); fs.writeFileSync(bulk, JSON.stringify(windowLoad()));
  const original = { ...record('main'), workload: 'agent-idle', raw: scratch, recordFile: file,
    pairIdentityInputs: { plan: ['original acquisition'], instruments: { sampler: ['original bytes hash'] } },
    rawFiles: [bulk], endedAt: '2026-10-01T01:00:00Z', gaps: {}, commands: [{ label: 'original' }] };
  fs.writeFileSync(file, JSON.stringify(original));
  try {
    const projected = reprojectRecord(file, values);
    for (const key of ['id', 'pairIdentity', 'pairIdentityInputs', 'valuesHash', 'endedAt', 'commands']) assert.deepEqual(projected[key], original[key]);
    assert.deepEqual(projected.measuredProjection.metrics, original.metrics);
    assert.match(projected.reprojectionDriverHash, /^[a-f0-9]{64}$/);
    assert.ok(projected.reprojectedAt);
    assert.equal(projected.metrics.stageRates.chunk_embed.rate, 20);
    fs.unlinkSync(bulk);
    const before = fs.readFileSync(file, 'utf8');
    assert.throws(() => reprojectRecord(file, values), /Missing retained raw/);
    assert.equal(fs.readFileSync(file, 'utf8'), before);
  } finally { fs.rmSync(scratch, { recursive: true, force: true }); }
});
test('scoring projection leaves acquisition identity and its explanation unchanged', () => {
  const r = { ...record('main'), workload: 'agent-idle', gaps: {}, pairIdentityInputs: { plan: ['captured'], instruments: { sampler: 'original' } } };
  const before = structuredClone({ hash: r.pairIdentity, inputs: r.pairIdentityInputs });
  projectLoad(r, windowLoad(), [], values);
  assert.deepEqual({ hash: r.pairIdentity, inputs: r.pairIdentityInputs }, before);
});
test('wire failures, actual idle refusals, and reason-coded scripted ceilings are measured', () => {
  const r = record('main'); r.workload = 'agent-idle'; r.gaps = {};
  const l = windowLoad(); projectLoad(r, l, [], values);
  assert.equal(r.metrics.chunksPerSec, 20);
  // Chunk work drained at 300 s while NER keeps the window valid: rate over 300 s, not 1200 s.
  const early = windowLoad();
  for (const s of early.samples.slice(60)) { s.chunkEmbeddingPendingCount = 0; s.chunkEmbeddingCompletedCount = 6000; s.raw.worker.enrichment.chunk.chunkEmbeddingPendingCount = 0; s.raw.worker.enrichment.chunk.chunkEmbeddingCompletedCount = 6000; }
  const re = record('main'); re.workload = 'agent-idle'; re.gaps = {}; projectLoad(re, early, [], values);
  assert.equal(re.metrics.chunksPerSec, 20); assert.equal(re.metrics.chunkWorkDrainedInWindow, true);
  const instant = windowLoad(); for (const s of instant.samples.slice(5)) { s.chunkEmbeddingPendingCount = 0; s.raw.worker.enrichment.chunk.chunkEmbeddingPendingCount = 0; }
  const ri = record('main'); ri.workload = 'agent-idle'; ri.gaps = {}; projectLoad(ri, instant, [], values);
  assert.equal(ri.metrics.chunksPerSec, undefined, 'under 60 s of chunk work cannot be rated');
  assert.equal(r.clauses['idle-rejections'], true);
  l.requests[0] = { status: 429, code: 'ADMISSION_ENGINE_LIMIT', retrySafe: true, retryAfter: '1' };
  projectLoad(r, l, [], values); assert.equal(r.clauses['idle-rejections'], false);
  l.requests[0] = { status: 503 };
  projectLoad(r, l, [], values); assert.equal(r.clauses['no-timeout-or-5xx'], false);
  l.requests[0] = { error: 'timeout' };
  projectLoad(r, l, [], values); assert.equal(r.clauses['no-timeout-or-5xx'], false);
  r.workload = 'scripted-agent';
  projectLoad(r, windowLoad(), [{ status: 200, durationMs: 20 }], values);
  assert.equal(r.clauses['agent-api-p95'], true);
  const boundary = windowLoad(); boundary.requests.push({ windowBoundary: true, error: 'WINDOW_BOUNDARY_CANCELLED' });
  projectLoad(r, boundary, [{ status: 200, durationMs: 20 }], values);
  assert.equal(r.clauses['no-timeout-or-5xx'], true);
  boundary.requests.at(-1).error = 'TIMED_OUT';
  projectLoad(r, boundary, [{ status: 200, durationMs: 20 }], values);
  assert.equal(r.clauses['no-timeout-or-5xx'], false);
  projectLoad(r, windowLoad(), [{ status: 429, code: 'ADMISSION_ENGINE_LIMIT', retrySafe: true, retryAfter: '1' }], values);
  assert.equal(r.clauses['scripted-rejections'], false);
});
test('both workload records are required; E0 freezes workload-specific ceilings and throughput', () => {
  const loads = {};
  for (const w of ['agent-idle', 'scripted-agent']) {
    const r = { ...record('main'), workload: w, recordFile: `${w}.json`, gaps: {} };
    projectLoad(r, windowLoad(), w === 'scripted-agent' ? [{ status: 200, durationMs: 200 }] : [], values);
    loads[w] = r;
  }
  const missing = mergeLoadRecords({ 'agent-idle': loads['agent-idle'] });
  assert.equal(missing.clauses['indexing-window-valid'], undefined);
  assert.throws(() => fillValues(document, { 'E1/main': record('main'), 'E2/main': missing }), /Both valid/);
  const merged = mergeLoadRecords(loads);
  const filled = fillValues(document, { 'E1/main': record('main'), 'E2/main': merged });
  assert.equal(filled.values.indexingProgressFraction.stagesByWorkload['agent-idle'].chunk_embed.minimumRate, 18);
  assert.equal(filled.values.foregroundSearchP95Ceiling.ceilingByWorkload['agent-idle'].hybrid, 100 * 1.1);
  const branch = structuredClone(merged); branch.arm = 'branch';
  const pairs = { 'E2/main': merged, 'E3/main': merged, 'E2/branch': branch, 'E3/branch': branch };
  assert.equal(tableVerdicts(pairs, filled.values).find(r => r.clause === 'foreground-p95').verdict, 'pass');
  branch.metrics.searchP95ByWorkload['scripted-agent'].lexical = 56;
  assert.equal(tableVerdicts(pairs, filled.values).find(r => r.clause === 'foreground-p95').verdict, 'fail');
  branch.metrics.stagesByWorkload['agent-idle'].chunk_embed.rate = 17.9;
  assert.equal(tableVerdicts(pairs, filled.values).find(r => r.clause === 'stage-completion-rates-under-foreground-load').verdict, 'fail');
  branch.clauses['indexing-window-valid'] = false;
  assert.equal(tableVerdicts(pairs, filled.values).find(r => r.clause === 'indexing-window-valid').verdict, 'fail');
});
test('E0 reloads both workload sources and binds their values hash for later table reads', async () => {
  const scratch = fs.mkdtempSync(path.join(ROOT, 'tmp/e-load-records-'));
  const evidence = path.join(scratch, 'docs/design/lane-f-engine-jvm/evidence/E');
  fs.mkdirSync(evidence, { recursive: true });
  fs.writeFileSync(path.join(evidence, 'values.json'), JSON.stringify(document));
  const save = (r, directory) => {
    const dir = path.join(evidence, directory, 'main'); fs.mkdirSync(dir, { recursive: true });
    r.recordFile = path.join(dir, `${r.id}.json`);
    fs.writeFileSync(r.recordFile, JSON.stringify(r));
    const index = path.join(dir, 'index.json');
    const entries = fs.existsSync(index) ? JSON.parse(fs.readFileSync(index)).runs : [];
    fs.writeFileSync(index, JSON.stringify({ runs: [...entries, { record: r.recordFile }] }));
  };
  try {
    save({ ...record('main', ['E1']), id: 'quality', startedAt: '2026-10-01T00:00:00Z' }, 'e1-quality');
    for (const w of ['agent-idle', 'scripted-agent']) {
      const r = { ...record('main'), id: w, workload: w, gaps: {}, revision: 'main-pin', startedAt: '2026-10-01T01:00:00Z' };
      projectLoad(r, windowLoad(), w === 'scripted-agent' ? [{ status: 200, durationMs: 200 }] : [], values);
      save(r, 'e2-e3-load');
    }
    assert.equal(latestRecords(scratch)['E2/main'].workloadRecords.length, 2);
    await main(['e0-values', '--repo-root', scratch]);
    const merged = latestRecords(scratch)['E2/main'];
    assert.equal(merged.failure, undefined);
    assert.equal(merged.clauses['indexing-window-valid'], true);
    for (const file of merged.workloadRecords) {
      const r = JSON.parse(fs.readFileSync(file));
      assert.equal(r.executedValuesHash, 'fixed');
      assert.equal(r.valuesHash, merged.valuesHash);
      assert.match(r.valuesHash, /^[a-f0-9]{64}$/);
    }
  } finally { fs.rmSync(scratch, { recursive: true, force: true }); }
});
for (const arm of ['main', 'branch']) {
  test(`quality command construction ${arm}`, () => {
    const plan = buildPlan(parseArgs(['e1-quality', '--arm', arm]), values, ROOT, { decision: 'recapture' });
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
  for (const workload of ['agent-idle', 'scripted-agent']) test(`bounded load plan ${arm}/${workload}`, () => {
    const plan = buildPlan(parseArgs(['e2-e3-load', '--arm', arm, '--workload', workload]), values);
    assert.equal(plan.filter(c => c.mode === 'start').length, 1);
    assert.equal(plan.filter(c => c.mode === 'stop').length, 1);
    const load = plan.find(c => c.label === `bulk-${workload}`);
    assert.equal(load.args[1], 'jseval.bulk_load');
    assert.equal(load.args[load.args.indexOf('--workload') + 1], workload);
    assert.equal(load.args[load.args.indexOf('--block-seconds') + 1], '600');
    assert.equal(load.window.enrichmentWait, false);
    assert.deepEqual(load.window.modes, ['hybrid', 'lexical']);
    assert.equal(load.cwd, path.join(ROOT, 'scripts/jseval'));
    assert.ok(!load.args.includes('--pipeline'));
    assert.ok(plan.some(c => c.label === `encoder-${workload}`));
    assert.ok(plan.some(c => c.label === `analyze-${workload}`));
  });
}

test('E0.2 pin check records content diff and requires matching capture pins and installed build', () => {
  const scratch = fs.mkdtempSync(path.join(ROOT, 'tmp/e-fixture-pins-'));
  const spec = JSON.parse(fs.readFileSync(path.join(ROOT, 'scripts/jseval/lane-f-workflow-fixture.v1.json'), 'utf8'));
  const metadataPath = 'docs/design/lane-f-engine-jvm/evidence/baseline/fixture-pr0b/pins.json';
  const baseline = JSON.parse(fs.readFileSync(path.join(ROOT, metadataPath), 'utf8'));
  const specFile = path.join(scratch, 'scripts/jseval/lane-f-workflow-fixture.v1.json');
  fs.mkdirSync(path.dirname(specFile), { recursive: true });
  fs.writeFileSync(specFile, JSON.stringify(spec));
  fs.mkdirSync(path.dirname(path.join(scratch, metadataPath)), { recursive: true });
  fs.copyFileSync(path.join(ROOT, metadataPath), path.join(scratch, metadataPath));
  const directory = path.join(scratch, baseline.directory);
  fs.mkdirSync(directory, { recursive: true });
  for (const n of [1, 2, 3]) fs.copyFileSync(path.join(ROOT, baseline.directory, `capture-${n}.json`), path.join(directory, `capture-${n}.json`));
  const stampFile = path.join(scratch, 'build-stamp.txt');
  const stamp = JSON.parse(fs.readFileSync(path.join(directory, 'capture-1.json'))).provenance['worker.buildStamp'];
  fs.writeFileSync(stampFile, stamp);
  const check = changed => checkFixturePins(scratch, MAIN_REVISION, args => {
    assert.deepEqual(args, ['diff', '--name-only', baseline.recordedRevision, MAIN_REVISION, '--', ...baseline.pinnedSurfaces]);
    return changed;
  }, stampFile);
  try {
    const reuse = check('');
    assert.equal(reuse.decision, 'reuse');
    assert.equal(reuse.sameBuildAndPins, true);
    assert.equal(reuse.captures.length, 3);
    assert.ok(reuse.captures.every(c => /^[a-f0-9]{64}$/.test(c.sha256)));
    const recapture = check('contracts/wire/knowledge.proto\ndocs/reference/runtime-contract.md\n');
    assert.equal(recapture.decision, 'recapture');
    assert.deepEqual(recapture.changedFiles, ['contracts/wire/knowledge.proto', 'docs/reference/runtime-contract.md']);
    fs.writeFileSync(stampFile, 'different-build');
    assert.equal(check('').decision, 'recapture');
    fs.unlinkSync(stampFile);
    assert.equal(check('').decision, 'recapture');
    fs.writeFileSync(stampFile, stamp);
    const file = path.join(directory, 'capture-3.json');
    const capture = JSON.parse(fs.readFileSync(file));
    capture.provenance.pins['justsearch.llm.slots'] = '2';
    fs.writeFileSync(file, JSON.stringify(capture));
    assert.equal(check('').sameBuildAndPins, false);
    assert.equal(check('').decision, 'recapture');
  } finally { fs.rmSync(scratch, { recursive: true, force: true }); }
});

for (const decision of ['reuse', 'recapture']) test(`dry-run plan E0.2 ${decision} preserves SciFact and selects fixture work`, () => {
  const fixture = { decision, recordedChatProfile: 'compact', directory: '/recorded/side-a', captures: [] };
  const mainPlan = buildPlan(parseArgs(['e1-quality', '--arm', 'main', '--dry-run']), values, ROOT, fixture);
  assert.equal(mainPlan.filter(c => c.mode === 'start').length, decision === 'reuse' ? 1 : 4);
  assert.equal(mainPlan.some(c => c.mode === 'fixture-reuse'), decision === 'reuse');
  assert.equal(mainPlan.filter(c => /^fixture-\d+$/.test(c.label)).length, decision === 'reuse' ? 0 : 3);
  const other = buildPlan(parseArgs(['e1-quality', '--arm', 'main']), values, ROOT, { decision: decision === 'reuse' ? 'recapture' : 'reuse', recordedChatProfile: 'compact' });
  assert.deepEqual(mainPlan.find(c => c.label === 'quality'), other.find(c => c.label === 'quality'));
  const branch = buildPlan(parseArgs(['e1-quality', '--arm', 'branch', '--dry-run']), values, ROOT, fixture);
  assert.equal(branch.filter(c => /^fixture-\d+$/.test(c.label)).length, 3);
  assert.ok(branch.filter(c => /^fixture-\d+$/.test(c.label)).every(c => c.args[2] === (decision === 'reuse' ? 'compact' : 'standard')));
});

test('ingest accepts legacy main but an operation refusal cannot fall back to legacy fields', () => {
  const legacy = { accepted: 92, error: '', scanId: 'scan-123' };
  assert.equal(ingestAccepted(legacy), true);
  assert.equal(ingestAccepted({ success: true, structuredData: { operationKey: 'op-123' } }), true);
  for (const response of [null, {}, { ...legacy, accepted: 0 }, { ...legacy, accepted: '92' },
    { ...legacy, scanId: '' }, { ...legacy, error: 'refused' }, { ...legacy, errorCode: 'REFUSED' },
    { ...legacy, success: false }, { ...legacy, success: true }, { ...legacy, structuredData: {} },
    { success: false, structuredData: { operationKey: 'refused-op' } }]) assert.equal(ingestAccepted(response), false);
});
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
    for (const arm of ['main', 'branch']) for (const workload of ['agent-idle', 'scripted-agent'])
      await main(['e2-e3-load', '--arm', arm, '--workload', workload, '--dry-run']);
  } finally { console.log = prior; }
  assert.equal(output.length, 7);
  for (const plan of output.slice(3).map(JSON.parse)) {
    assert.equal(plan.loadBudgetSeconds, 3000);
    assert.equal(plan.commands.filter(c => c.mode === 'start').length, 1);
  }
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

  clauses: { 'indexing-window-valid': true, 'foreground-p95': true, 'agent-api-p95': true, 'stage-completion-rates-under-foreground-load': true,
    'idle-rejections': true, 'scripted-rejections': true, 'no-timeout-or-5xx': true },
  metrics: { searchP95: { hybrid: 100, lexical: 50 }, agentP95: 200, chunksPerSec: 20,
    searchP95ByWorkload: { 'agent-idle': { hybrid: 100, lexical: 50 }, 'scripted-agent': { hybrid: 100, lexical: 50 } },
    chunksByWorkload: { 'agent-idle': 20, 'scripted-agent': 20 },
    stagesByWorkload: { 'agent-idle': { chunk_embed: { rate: 20 } }, 'scripted-agent': { chunk_embed: { rate: 20 } } } } });
const bounds = { ...values, foregroundSearchP95Ceiling: { ceilingMs: { hybrid: 110, lexical: 55 } },
  agentLoopApiP95Ceiling: { ceilingMs: 220 }, indexingProgressFraction: { stagesByWorkload: { 'agent-idle': { chunk_embed: { rate: 20 } }, 'scripted-agent': { chunk_embed: { rate: 20 } } } } };
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
  records['E3/branch'].metrics.stagesByWorkload['agent-idle'].chunk_embed.rate = 17.99;
  assert.equal(clause(records, 'stage-completion-rates-under-foreground-load'), 'fail');
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
  const records = { 'E1/main': record('main', ['E1']), 'E2/main': { ...record('main'), workloadRecords: ['idle.json', 'scripted.json'] } };
  const filled = fillValues(document, records);
  assert.equal(filled.values.foregroundSearchP95Ceiling.ceilingMs.hybrid, 100 * 1.1);
  assert.equal(filled.values.agentLoopApiP95Ceiling.ceilingMs, 200 * 1.1);
  assert.equal(filled.values.indexingProgressFraction.stagesByWorkload['agent-idle'].chunk_embed.minimumRate, 18);
  assert.equal(filled.values.foregroundSearchP95Ceiling.measuredAtE0, false);
  records['E1/branch'] = { ...record('branch'), failure: 'failed launch' };
  assert.throws(() => fillValues(document, records), /after any branch/);
});
test('E0 does not substitute baseline sanity or zero throughput for measured load', () => {
  assert.throws(() => fillValues(document, {}), /MAIN/);
  const records = { 'E1/main': record('main'), 'E2/main': { ...record('main'), workloadRecords: ['idle.json', 'scripted.json'] } };
  for (const stage of Object.values(records['E2/main'].metrics.stagesByWorkload['agent-idle'])) stage.rate = 0;
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
