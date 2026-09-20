import assert from 'node:assert/strict';
import test from 'node:test';
import { stageAndVerify } from './stage-reference-corpus.mjs';

const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

test('stageAndVerify retries transport/readiness failures with one key and still proves search', async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  const ingestBodies = [];
  let ingestCalls = 0;
  globalThis.fetch = async (url, options = {}) => {
    const route = new URL(url).pathname;
    if (route === '/api/knowledge/ingest') {
      const body = JSON.parse(options.body);
      ingestBodies.push(body);
      ingestCalls += 1;
      if (ingestCalls === 1) throw new TypeError('socket reset after request write');
      if (ingestCalls === 2) {
        return json({ error: 'Worker capability unavailable', unavailable: 'worker',
          health: 'PENDING', reason: 'worker.starting' }, 503);
      }
      return json({ success: true, message: 'accepted', structuredData: {
        operationKey: body.idempotencyKey, operationRecordId: 27,
      } });
    }
    if (route === '/api/status') {
      return json({ worker: { core: { pendingJobs: 0, indexState: 'IDLE', indexedDocuments: 1 } } });
    }
    if (route === '/api/knowledge/search') {
      return json({ results: [{ id: 'canary' }], searchTrace: { effectiveMode: 'text' } });
    }
    throw new Error(`unexpected route ${route}`);
  };

  const result = await stageAndVerify({
    base: 'http://127.0.0.1:32123', corpusPath: 'F:/corpus', query: 'canary',
    pollAttempts: 4, pollIntervalMs: 0,
  });

  assert.equal(result.mode, 'text');
  assert.equal(result.results.length, 1);
  assert.equal(ingestBodies.length, 3);
  assert.match(ingestBodies[0].idempotencyKey, UUID_V7);
  assert.equal(ingestBodies[1].idempotencyKey, ingestBodies[0].idempotencyKey);
  assert.equal(ingestBodies[2].idempotencyKey, ingestBodies[0].idempotencyKey);
  assert.deepEqual(ingestBodies[0].paths, ['F:/corpus']);
});

test('stageAndVerify preserves an application failure and does not retry it', async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  let calls = 0;
  globalThis.fetch = async () => {
    calls += 1;
    return json({ success: false, errorClass: 'HANDLER_FAILURE', errorCode: 'BAD_REQUEST',
      message: 'paths refused by operation schema' });
  };

  await assert.rejects(
    stageAndVerify({
      base: 'http://127.0.0.1:32123', corpusPath: 'F:/corpus', query: 'canary',
      pollAttempts: 3, pollIntervalMs: 0, failLabel: 'REFERENCE CORPUS',
    }),
    /REFERENCE CORPUS: ingest failed without retry:.*HANDLER_FAILURE.*BAD_REQUEST.*paths refused/,
  );
  assert.equal(calls, 1);
});

test('stageAndVerify preserves a keyed conflict and does not retry it', async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  let calls = 0;
  globalThis.fetch = async () => {
    calls += 1;
    return json({ success: false, errorClass: 'CONFLICT', errorCode: 'OPERATION_KEY_REUSED' }, 409);
  };

  await assert.rejects(
    stageAndVerify({
      base: 'http://127.0.0.1:32123', corpusPath: 'F:/corpus', query: 'canary',
      pollAttempts: 3, pollIntervalMs: 0,
    }),
    /HTTP 409.*OPERATION_KEY_REUSED/,
  );
  assert.equal(calls, 1);
});

test('stageAndVerify does not mistake operation capacity for Worker readiness', async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  let calls = 0;
  globalThis.fetch = async () => {
    calls += 1;
    return json({ success: false, errorClass: 'UNAVAILABLE',
      errorCode: 'OPERATIONS_CAPACITY', retryable: true }, 503);
  };

  await assert.rejects(
    stageAndVerify({
      base: 'http://127.0.0.1:32123', corpusPath: 'F:/corpus', query: 'canary',
      pollAttempts: 3, pollIntervalMs: 0,
    }),
    /ingest failed without retry:.*OPERATIONS_CAPACITY/,
  );
  assert.equal(calls, 1);
});
