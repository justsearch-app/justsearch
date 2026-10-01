import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { exerciseQueryReconfigure } from './query-reconfigure.mjs';
import { barrierFiles } from './barrier-files.mjs';

function fixture(t, mode, options = {}) {
  const parent = path.resolve('tmp/query-reconfigure-harness-tests');
  const work = path.join(parent, crypto.randomUUID());
  const data = path.join(work, 'data');
  const modelsRoot = path.join(work, 'models');
  const a = path.join(modelsRoot, 'onnx', 'reranker');
  const corpus = path.join(work, 'query-reconfigure-corpus');
  const indexBase = path.join(work, 'index');
  for (const dir of [a, corpus, indexBase, path.join(data, 'ui'), path.join(data, 'runtime')]) {
    fs.mkdirSync(dir, { recursive: true });
  }
  fs.writeFileSync(path.join(a, 'model_fp16.onnx'), 'retained A model fixture');
  fs.writeFileSync(path.join(a, 'tokenizer.json'), '{}');
  for (let i = 0; i < 6; i++) fs.writeFileSync(path.join(corpus, `rerank-${i}.txt`),
    `query reconfigure availability candidate ${i}; query reconfigure held lease`);
  fs.writeFileSync(path.join(indexBase, 'state.json'), JSON.stringify({ active_generation: 'g-A' }));
  t.after(() => {
    assert.equal(path.dirname(path.resolve(work)), parent);
    fs.rmSync(work, { recursive: true, force: true });
  });
  const first = { pid: 123, restartCount: 0, incarnation: 1, instanceId: 'instance-A' };
  fs.writeFileSync(path.join(data, 'runtime', 'supervisor.v1.json'), JSON.stringify(first));
  const calls = [];
  const waits = [];
  const hash = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
  const response = (status, body) => ({ status, text: JSON.stringify(body) });
  const composition = { mode, reason: mode === 'BESIDE' ? 'candidate_fits_free_device_memory'
    : 'candidate_fits_after_source_release', freeBytes: mode === 'BESIDE' ? 4e9 : 1048576,
    footprintBytes: 1e9 };
  let witness = { acceptedRevision: 0 };
  let currentPath = a;
  let recoveryAttempts = 0;
  let state = 'READY';
  let stale = null;
  let holdResolve = null;
  let applyResolve = null;
  let pendingApply = null;
  let admitted = false;
  let jobReads = 0;
  let committed = false;
  let refusal = null;
  let sequence = 0;
  const createOperationKey = () => `01996c43-8300-7000-8000-${String(++sequence).padStart(12, '0')}`;
  const forcedKey = mode === 'IN_PLACE' ? createOperationKey() : null;
  const snapshot = () => ({ worker: { gpu: { rerankerModelPath: currentPath } },
    readiness: { engineComponents: { encoders: { ...composition, state, recoveryAttempts,
      appliedVersion: currentPath === a ? 'A-version' : 'B-version' } } } });
  const persist = () => fs.writeFileSync(path.join(data, 'ui', 'settings.json'), JSON.stringify({
    witness, queryRoles: { reranker: { state: 'SELECTED', targetEp: 'CUDA',
      model: { path: path.join(currentPath, 'model_fp16.onnx'),
        sha256: hash(path.join(currentPath, 'model_fp16.onnx')) } } },
  }));
  persist();
  const commit = body => {
    stale = snapshot();
    currentPath = body.rerankerModelPath;
    state = 'READY';
    witness = { acceptedRevision: witness.acceptedRevision + 1 };
    persist();
    return response(200, { state: 'COMPLETE', operationKey: body.operationKey, witness, composition });
  };
  const finishApply = () => {
    const body = pendingApply;
    pendingApply = null;
    const result = commit(body);
    applyResolve(result);
    applyResolve = null;
  };
  const issued = barrierFiles(data, 'issued-a-search');
  const operation = barrierFiles(data);
  const originalWrite = fs.writeFileSync;
  t.mock.method(fs, 'writeFileSync', (file, ...args) => {
    originalWrite.call(fs, file, ...args);
    if (file === issued.releaseFile) {
      holdResolve(response(200, { results: [{}] }));
      holdResolve = null;
      if (mode === 'BESIDE') { applyResolve(pendingApply); applyResolve = null; pendingApply = null; }
    }
    if (file === operation.releaseFile) finishApply();
  });
  const requireThat = (condition, message) => assert.ok(condition, message);
  const readJson = file => {
    try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; }
  };
  const waitFor = async (label, _timeout, probe) => {
    for (let attempts = 1; attempts <= 4; attempts++) {
      const value = await probe();
      if (value) { waits.push({ label, attempts }); return value; }
    }
    throw new Error(`timeout waiting for ${label}`);
  };
  const request = async (_port, endpoint, opts = {}) => {
    calls.push(endpoint);
    if (endpoint === '/api/status') {
      const value = stale ?? snapshot();
      stale = null;
      return response(200, value);
    }
    if (endpoint === '/api/health') return response(503, {});
    if (endpoint === '/api/inference/encoders') return response(200, {
      snapshotStatus: 'ok', encoders: { reranker: { available: true,
        currentAccelerator: 'cuda', configuredAccelerator: 'CUDA' } },
    });
    if (endpoint === '/api/knowledge/ingest') {
      const body = JSON.parse(opts.body);
      assert.equal(opts.headers['X-JustSearch-Session'], 'session-token');
      assert.deepEqual(body.paths, Array.from({ length: 6 }, (_, i) => path.join(corpus, `rerank-${i}.txt`)));
      assert.equal(admitted, false);
      admitted = true;
      return response(200, { success: true, structuredData: {
        operationKey: body.idempotencyKey, operationRecordId: 1 } });
    }
    assert.equal(endpoint, '/api/settings/v2');
    if (opts.method !== 'POST') return response(200, { witness });
    const body = JSON.parse(opts.body);
    if (refusal?.key === body.operationKey) return refusal.response;
    if (body.rerankerModelPath.endsWith('-invalid')) {
      stale = snapshot();
      recoveryAttempts += mode === 'IN_PLACE' ? 1 : 0;
      const result = response(409, { state: 'FAILED', operationKey: body.operationKey, composition });
      refusal = { key: body.operationKey, response: result };
      return result;
    }
    if (holdResolve) {
      if (mode === 'IN_PLACE') {
        state = 'RELOADING';
        pendingApply = body;
        originalWrite.call(fs, operation.reachedFile, JSON.stringify({
          phase: 'settings-mid-compose', operationKey: body.operationKey, cursor: 'encoders', pid: first.pid,
        }));
      } else { pendingApply = commit(body); stale = null; }
      return new Promise(resolve => { applyResolve = resolve; });
    }
    return commit(body);
  };
  const post = async (_port, endpoint, body) => {
    calls.push(endpoint);
    assert.equal(endpoint, '/api/knowledge/search');
    assert.ok(committed, 'model queries must follow corpus admission and committed DONE jobs');
    if (body.query === 'query reconfigure held lease') {
      originalWrite.call(fs, issued.reachedFile, JSON.stringify({ query: body.query, pid: first.pid }));
      return new Promise(resolve => { holdResolve = resolve; });
    }
    return response(200, { results: Array.from({ length: 6 }, () => ({})),
      searchTrace: { stages: options.missingTrace ? [] : [{ id: 'cross-encoder',
        status: state === 'RELOADING' ? 'skipped' : 'executed', reason: null }] } });
  };
  return { calls, waits, context: { mode, work, data, indexBase, modelsRoot, apiPort: 12345,
    manifest: { head: { sessionToken: 'session-token' } }, first, readJson, waitFor, request,
    post, requireThat, createOperationKey, operationKey: forcedKey,
    requireOperationSuccess: (r, label) => {
      const v = JSON.parse(r.text);
      assert.equal(r.status, 200, label);
      assert.equal(v.success, true, label);
      return v.structuredData;
    },
    jobStateFor: filename => {
      assert.ok(admitted, 'job completion must follow the ingest operation');
      assert.match(filename, /^rerank-[0-5]\.txt$/);
      jobReads++;
      committed = jobReads > 6;
      return { state: committed ? 'DONE' : 'PENDING' };
    },
  } };
}

for (const mode of ['BESIDE', 'IN_PLACE']) {
  test(`${mode} admits corpus, holds A, rejects stale publication, and replays refusal`, async t => {
    const f = fixture(t, mode);
    const output = [];
    t.mock.method(console, 'log', (...args) => output.push(args));
    await exerciseQueryReconfigure(f.context);
    const proof = JSON.parse(output.find(args => args[0] === 'QUERY_RECONFIGURE_ROUND_PASS')[1]);
    assert.equal(proof.corpus.committedJobs, 6);
    assert.equal(proof.apiOutages, 0);
    assert.equal(proof.restartCount, 0);
    assert.equal(proof.availabilityRounds.length, 4);
    assert.ok(f.calls.indexOf('/api/knowledge/ingest') < f.calls.indexOf('/api/knowledge/search'));
    assert.equal(f.calls.filter(x => x === '/api/knowledge/ingest').length, 1);
    assert.ok(f.waits.find(x => x.label === 'all six query reconfigure corpus jobs committed DONE').attempts > 1);
    assert.ok(f.waits.find(x => x.label === `${mode} restored A publication`).attempts > 1);
    assert.equal(proof.witnesses.toB.degradation.issuedA.completedAfterRelease, true);
    if (mode === 'IN_PLACE') {
      assert.equal(proof.witnesses.toB.degradation.compositionBarrier.encoders.state, 'RELOADING');
      assert.equal(proof.recoveryAttempts.afterRefusal, 1);
    }
  });
}

test('model-query timeout preserves HTTP, result count, and missing trace evidence', async t => {
  const f = fixture(t, 'BESIDE', { missingTrace: true });
  await assert.rejects(exerciseQueryReconfigure(f.context), error => {
    assert.match(error.message, /timeout waiting for model query A/);
    assert.match(error.message, /"status":200,"results":6,"crossEncoder":null/);
    return true;
  });
  assert.equal(f.calls.filter(x => x === '/api/settings/v2').length, 0);
});
