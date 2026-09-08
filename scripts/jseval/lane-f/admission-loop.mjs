#!/usr/bin/env node
/** C1 wire-result oracle. Batch 3 supplies real context-many.json/context-one.json captures. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REJECTIONS = new Set(['ADMISSION_CONTEXT_LIMIT', 'ADMISSION_ENGINE_LIMIT']);

/**
 * Capture schema: {arm, offered, concurrency, policy:{perContextLimit, aggregateLimit},
 * requests:[{id,contextId,operation:'search'|'chat',status,code?,retryAfter?,durationMs,error?}]}.
 * One row per offered request, including failures. Latency is measured at the caller.
 */
export function analyzeArm(capture, expectedArm) {
  const failures = [];
  const counts = { offered: 0, admitted: 0, contextRejected: 0, engineRejected: 0 };
  const positiveInteger = (value) => Number.isSafeInteger(value) && value > 0;
  if (!capture || capture.arm !== expectedArm || !positiveInteger(capture.offered)
      || !positiveInteger(capture.concurrency) || capture.concurrency > capture.offered
      || !positiveInteger(capture.policy?.perContextLimit)
      || !positiveInteger(capture.policy?.aggregateLimit)
      || !Array.isArray(capture.requests)) {
    return { verdict: 'FAIL', counts, failures: ['invalid capture envelope'] };
  }
  counts.offered = capture.offered;
  if (capture.requests.length !== capture.offered) failures.push('missing or extra request results');
  const ids = new Set();
  const contexts = new Map();
  const contextOperations = new Map();
  const operations = new Set();
  for (const request of capture.requests) {
    if (!request || typeof request.id !== 'string' || !request.id || ids.has(request.id)) {
      failures.push('missing or duplicate request identity');
      continue;
    }
    ids.add(request.id);
    if (typeof request.contextId !== 'string' || !request.contextId.trim()
        || !['search', 'chat'].includes(request.operation)) {
      failures.push(`${request.id}: missing context or unknown operation`);
      continue;
    }
    contexts.set(request.contextId, (contexts.get(request.contextId) ?? 0) + 1);
    if (!contextOperations.has(request.contextId)) contextOperations.set(request.contextId, new Set());
    contextOperations.get(request.contextId).add(request.operation);
    operations.add(request.operation);
    if (!Number.isFinite(request.durationMs) || request.durationMs < 0 || request.error) {
      failures.push(`${request.id}: transport failure or invalid timing`);
      continue;
    }
    if (Number.isInteger(request.status) && request.status >= 200 && request.status < 300) {
      counts.admitted++;
    } else if (request.status === 429 && REJECTIONS.has(request.code)
        && typeof request.retryAfter === 'string' && /^[1-9]\d*$/.test(request.retryAfter)
        && Number.isSafeInteger(Number(request.retryAfter))) {
      if (request.code === 'ADMISSION_CONTEXT_LIMIT') counts.contextRejected++;
      else counts.engineRejected++;
    } else {
      // An upgrade barrier is distinguishable, but cannot substitute for a load measurement.
      failures.push(`${request.id}: unexpected response ${request.status}/${request.code ?? 'no-code'}`);
    }
  }
  if (expectedArm === 'context-one' ? contexts.size !== 1 : contexts.size < 2) {
    failures.push('context identities do not match arm composition');
  }
  if (expectedArm === 'context-many'
      && Math.max(...contexts.values()) - Math.min(...contexts.values()) > 1) {
    failures.push('many-context workload is not evenly distributed');
  }
  if (operations.size !== 2) failures.push('both search and chat must be offered');
  if ([...contextOperations.values()].some(mix => mix.size !== 2)) {
    failures.push('every context must offer both search and chat');
  }
  if (counts.admitted === 0) failures.push('no admitted request completed');
  if (counts.contextRejected + counts.engineRejected === 0) failures.push('admission was never exercised');
  return { verdict: failures.length ? 'FAIL' : 'PASS', counts, failures };
}

/**
 * Aggregate independence is compared with the fairness cap non-binding in BOTH arms.
 * Otherwise one context can legitimately reject earlier than many contexts. The fairness
 * arm separately proves the default per-context cap; it must not be conflated with this pair.
 */
export function analyzePair(many, one) {
  const manyResult = analyzeArm(many, 'context-many');
  const oneResult = analyzeArm(one, 'context-one');
  const failures = [];
  if (manyResult.verdict !== 'PASS' || oneResult.verdict !== 'PASS') {
    failures.push('at least one arm failed');
  } else {
    if (many.offered !== one.offered || many.concurrency !== one.concurrency
        || many.policy.aggregateLimit !== one.policy.aggregateLimit
        || many.policy.perContextLimit !== one.policy.perContextLimit) {
      failures.push('offered load or policy differs between arms');
    }
    for (const operation of ['search', 'chat']) {
      if (many.requests.filter(r => r.operation === operation).length
          !== one.requests.filter(r => r.operation === operation).length) {
        failures.push('operation mix differs between arms');
      }
    }
    for (const arm of [many, one]) {
      if (arm.policy.perContextLimit < arm.concurrency) failures.push('fairness cap constrains aggregate comparison');
    }
    if (manyResult.counts.contextRejected || oneResult.counts.contextRejected) {
      failures.push('per-context rejection in aggregate comparison');
    }
    if (manyResult.counts.engineRejected !== oneResult.counts.engineRejected) {
      failures.push('aggregate rejection counts differ');
    }
  }
  return { verdict: failures.length ? 'FAIL' : 'PASS', many: manyResult, one: oneResult, failures };
}

function selfTest() {
  const capture = (arm) => ({
    arm, offered: 4, concurrency: 4, policy: { perContextLimit: 4, aggregateLimit: 2 },
    requests: [
      { id: 'accepted', contextId: 'client-1', operation: 'search', status: 200, durationMs: 8 },
      { id: 'rejected', contextId: arm === 'context-many' ? 'client-2' : 'client-1', operation: 'chat',
        status: 429, code: 'ADMISSION_ENGINE_LIMIT', retryAfter: '1', durationMs: 1 },
      { id: 'accepted-chat', contextId: 'client-1', operation: 'chat', status: 200, durationMs: 8 },
      { id: 'rejected-search', contextId: arm === 'context-many' ? 'client-2' : 'client-1', operation: 'search',
        status: 429, code: 'ADMISSION_ENGINE_LIMIT', retryAfter: '1', durationMs: 1 },
    ],
  });
  const valid = () => [capture('context-many'), capture('context-one')];
  assert.equal(analyzePair(...valid()).verdict, 'PASS');
  let checked = 1;
  for (const mutation of [
    (c) => { c.requests = []; },
    (c) => { c.offered = 0; },
    (c) => { c.arm = 'wrong'; },
    (c) => { c.requests[0].id = 'rejected'; },
    (c) => { c.requests[0].contextId = undefined; },
    (c) => { c.requests[0].contextId = ' '; },
    (c) => { c.requests[1].contextId = 'client-1'; },
    (c) => { c.requests[1].operation = 'search'; },
    (c) => { c.requests[1].operation = 'unknown'; },
    (c) => { c.requests[0].error = 'TIMEOUT'; },
    (c) => { c.requests[0].error = 'ECONNRESET'; },
    (c) => { c.requests[0].durationMs = Number.NaN; },
    (c) => { c.requests[0].status = 500; },
    (c) => { c.requests[0].status = 503; c.requests[0].code = 'UPGRADE_PREPARING'; },
    (c) => { c.requests[1].status = 200; },
    (c) => { c.requests[0] = { ...c.requests[1], id: 'also-rejected' }; },
    (c) => { c.requests[1].code = 'RESOURCE_EXHAUSTED'; },
    (c) => { c.requests[1].retryAfter = undefined; },
    (c) => { c.requests[1].retryAfter = '0'; },
    (c) => { c.requests[1].retryAfter = 'Infinity'; },
    (c) => { c.policy.perContextLimit = 1; },
    (c) => { c.policy.aggregateLimit = 3; },
    (c) => { c.requests[1].code = 'ADMISSION_CONTEXT_LIMIT'; },
  ]) {
    const [many, one] = valid();
    mutation(many);
    assert.equal(analyzePair(many, one).verdict, 'FAIL');
    checked++;
  }
  assert.equal(analyzePair(null, null).verdict, 'FAIL');
  const [many, one] = valid();
  one.requests[1].contextId = 'client-2';
  assert.equal(analyzePair(many, one).verdict, 'FAIL');
  console.log(`admission oracle self-test: PASS (${checked + 2} cases)`);
}

function readCapture(directory, name) {
  const file = path.join(directory, `${name}.json`);
  if (fs.statSync(file).size > 16 * 1024 * 1024) throw new Error(`capture too large: ${name}`);
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function main() {
  if (process.argv.length === 3 && process.argv[2] === '--self-test') {
    selfTest();
    return;
  }
  if (process.argv.length !== 4 || process.argv[2] !== '--analyze') {
    throw new Error('usage: admission-loop.mjs --self-test | --analyze <capture-directory>');
  }
  const directory = process.argv[3];
  const result = analyzePair(readCapture(directory, 'context-many'), readCapture(directory, 'context-one'));
  console.log(JSON.stringify(result, null, 2));
  process.exitCode = result.verdict === 'PASS' ? 0 : 1;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (error) {
    console.error(JSON.stringify({ verdict: 'FAIL', error: error.message }));
    process.exitCode = 1;
  }
}
