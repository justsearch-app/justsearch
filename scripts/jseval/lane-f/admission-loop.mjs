#!/usr/bin/env node
/** C1 wire-result oracle. Batch 3 supplies real context-many.json/context-one.json captures. */
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REJECTIONS = new Set(['ADMISSION_CONTEXT_LIMIT', 'ADMISSION_ENGINE_LIMIT']);
const BODY_LIMIT_BYTES = 1024 * 1024;
const ARM_TIMEOUT_MS = 5 * 60 * 1000;

/**
 * Capture schema: {arm, offered, concurrency, activeBaseline, activeAfter, holderCount,
 * policy:{perContextLimit,aggregateLimit,retryAfterSeconds}, timingPrecondition, timeline,
 * requests:[{id,contextId,operation,status,code?,retryAfter?,durationMs,timeline,terminal?,error?}]}.
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
  if (capture.captureKind === 'live') {
    if (!Number.isSafeInteger(capture.activeBaseline) || capture.activeBaseline < 0
        || !Number.isSafeInteger(capture.activeAfter) || capture.activeAfter < 0
        || capture.activeAfter !== capture.activeBaseline
        || capture.holderCount !== capture.policy.aggregateLimit - capture.activeBaseline
        || capture.holderCount < 2 || capture.holderCount > 32
        || capture.offered !== capture.holderCount * 2
        || capture.concurrency !== capture.offered
        || capture.policy.perContextLimit < capture.offered
        || !positiveInteger(capture.policy.retryAfterSeconds)
        || capture.timingPrecondition !== true
        || !Number.isFinite(capture.timeline?.overflowLaunchedAt)) {
      failures.push('invalid live workload or timing precondition');
    }
  }
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
    if (request.streamed === true
        && (request.activeAtProbe !== true || request.terminal?.doneCount !== 1
          || request.terminal?.errorCount !== 0 || request.terminal?.eof !== true)) {
      failures.push(`${request.id}: streamed response was not active through probe and done once`);
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
  if (capture.captureKind === 'live') {
    const chats = capture.requests.filter(request => request.operation === 'chat');
    const searches = capture.requests.filter(request => request.operation === 'search');
    const latestOverflowHeaders = Math.max(...searches.map(
      request => request.timeline?.responseHeadersAt ?? Number.POSITIVE_INFINITY));
    if (chats.length !== capture.holderCount || searches.length !== capture.holderCount
        || !Number.isFinite(latestOverflowHeaders)
        || searches.some(request => !Number.isFinite(request.timeline?.responseHeadersAt)
          || request.timeline.responseHeadersAt < capture.timeline.overflowLaunchedAt)
        || chats.some(request => request.status !== 200 || !request.streamed
          || !Number.isFinite(request.timeline?.responseHeadersAt)
          || !Number.isFinite(request.timeline?.runStartedAt)
          || request.timeline.runStartedAt < request.timeline.responseHeadersAt
          || request.timeline.runStartedAt > capture.timeline.overflowLaunchedAt
          || !Number.isFinite(request.timeline?.terminalAt)
          || request.timeline.terminalAt <= latestOverflowHeaders)) {
      failures.push('live holders did not span the complete overflow response window');
    }
    if (counts.admitted !== capture.holderCount
        || counts.engineRejected !== capture.holderCount
        || counts.contextRejected !== 0) {
      failures.push('live aggregate wave counts do not match available owner capacity');
    }
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
        || many.policy.perContextLimit !== one.policy.perContextLimit
        || many.policy.retryAfterSeconds !== one.policy.retryAfterSeconds) {
      failures.push('offered load or policy differs between arms');
    }
    if ((many.captureKind === 'live' || one.captureKind === 'live')
        && (many.captureKind !== one.captureKind
          || many.activeBaseline !== one.activeBaseline
          || many.activeAfter !== one.activeAfter
          || many.holderCount !== one.holderCount)) {
      failures.push('live baseline or holder count differs between arms');
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

/** Verifies the live one-bucket fairness wave independently of the aggregate pair. */
export function analyzeFairness(capture) {
  const failures = [];
  const fail = message => failures.push(message);
  if (!capture || capture.captureKind !== 'live-fairness' || capture.arm !== 'fairness'
      || !Number.isSafeInteger(capture.policy?.perContextLimit)
      || !Number.isSafeInteger(capture.policy?.aggregateLimit)
      || !Number.isSafeInteger(capture.policy?.retryAfterSeconds)
      || !Number.isSafeInteger(capture.activeBaseline)
      || !Number.isSafeInteger(capture.activeAfter)
      || capture.activeBaseline !== capture.activeAfter
      || capture.holderCount !== capture.policy.perContextLimit
      || capture.holderCount < 1 || capture.holderCount > 32
      || capture.policy.aggregateLimit - capture.activeBaseline < capture.holderCount + 2
      || capture.offered !== capture.holderCount + 5
      || capture.concurrency !== capture.offered
      || capture.timingPrecondition !== true
      || !Number.isFinite(capture.timeline?.probesLaunchedAt)
      || !Array.isArray(capture.requests) || capture.requests.length !== capture.offered
      || capture.error) {
    return { verdict: 'FAIL', failures: ['invalid fairness capture envelope'] };
  }
  const ids = new Set();
  for (const request of capture.requests) {
    if (!request || typeof request.id !== 'string' || !request.id || ids.has(request.id)
        || !Number.isFinite(request.durationMs) || request.durationMs < 0 || request.error) {
      fail('invalid fairness request row');
      continue;
    }
    ids.add(request.id);
  }
  const holders = capture.requests.filter(request => request.role === 'holder');
  const sameSearch = capture.requests.filter(request => request.role === 'same-bucket-search');
  const sameSuggest = capture.requests.filter(request => request.role === 'same-bucket-suggest');
  const sameMcp = capture.requests.filter(request => request.role === 'same-bucket-mcp');
  const health = capture.requests.filter(request => request.role === 'same-bucket-health');
  const other = capture.requests.filter(request => request.role === 'other-bucket-search');
  const probes = [...sameSearch, ...sameSuggest, ...sameMcp, ...health, ...other];
  const latestProbeHeaders = Math.max(...probes.map(
    request => request.timeline?.responseHeadersAt ?? Number.POSITIVE_INFINITY));
  const holderContexts = new Set(holders.map(request => request.contextId));
  if (holders.length !== capture.holderCount || holderContexts.size !== 1
      || !Number.isFinite(latestProbeHeaders)
      || holders.some(request => request.operation !== 'chat' || request.status !== 200
        || request.streamed !== true || request.activeAtProbe !== true
        || request.terminal?.doneCount !== 1 || request.terminal?.errorCount !== 0
        || request.terminal?.eof !== true
        || !Number.isFinite(request.timeline?.responseHeadersAt)
        || !Number.isFinite(request.timeline?.runStartedAt)
        || request.timeline.runStartedAt < request.timeline.responseHeadersAt
        || request.timeline.runStartedAt > capture.timeline.probesLaunchedAt
        || !Number.isFinite(request.timeline?.terminalAt)
        || request.timeline.terminalAt <= latestProbeHeaders)) {
    fail('fairness holders did not span the complete probe response window');
  }
  const holderContext = holders[0]?.contextId;
  const refusals = [...sameSearch, ...sameSuggest, ...sameMcp];
  if (sameSearch.length !== 1 || sameSearch[0].operation !== 'search'
      || sameSuggest.length !== 1 || sameSuggest[0].operation !== 'suggest'
      || sameMcp.length !== 1 || sameMcp[0].operation !== 'mcp'
      || refusals.some(request => request.contextId !== holderContext || request.status !== 429
        || request.code !== 'ADMISSION_CONTEXT_LIMIT'
        || request.jsonValidated !== true
        || request.retryAfter !== String(capture.policy.retryAfterSeconds)
        || !Number.isFinite(request.timeline?.responseHeadersAt)
        || request.timeline.responseHeadersAt < capture.timeline.probesLaunchedAt)) {
    fail('same-bucket probes were not typed context-limit refusals');
  }
  if (sameMcp.length !== 1 || sameMcp[0].mcp?.id !== 'fairness-probe'
      || sameMcp[0].mcp?.errorCode !== 'ADMISSION_CONTEXT_LIMIT'
      || sameMcp[0].mcp?.errorNumber !== -32000) {
    fail('MCP refusal did not preserve the JSON-RPC error envelope');
  }
  if (health.length !== 1 || health[0].operation !== 'health'
      || health[0].contextId !== holderContext || health[0].status !== 200
      || health[0].jsonValidated !== true
      || !Number.isFinite(health[0].timeline?.responseHeadersAt)
      || health[0].timeline.responseHeadersAt < capture.timeline.probesLaunchedAt) {
    fail('health was not reachable while the context was full');
  }
  if (other.length !== 1 || other[0].operation !== 'search'
      || other[0].contextId === holderContext || other[0].status !== 200
      || other[0].jsonValidated !== true
      || !Number.isFinite(other[0].timeline?.responseHeadersAt)
      || other[0].timeline.responseHeadersAt < capture.timeline.probesLaunchedAt) {
    fail('distinct-bucket probe was not admitted');
  }
  return { verdict: failures.length ? 'FAIL' : 'PASS', failures };
}

async function selfTest() {
  const capture = (arm) => ({
    arm, offered: 4, concurrency: 4, policy: { perContextLimit: 4, aggregateLimit: 2 },
    requests: [
      { id: 'accepted', contextId: 'client-1', operation: 'search', status: 200, durationMs: 8 },
      { id: 'rejected', contextId: arm === 'context-many' ? 'client-2' : 'client-1', operation: 'chat',
        status: 429, code: 'ADMISSION_ENGINE_LIMIT', retryAfter: '1', durationMs: 1 },
      { id: 'accepted-chat', contextId: 'client-1', operation: 'chat', status: 200, durationMs: 8,
        streamed: true, activeAtProbe: true,
        terminal: { doneCount: 1, errorCount: 0, eventCount: 3, eof: true } },
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
    (c) => { c.requests[2].terminal.doneCount = 0; },
    (c) => { c.requests[2].terminal.errorCount = 1; },
    (c) => { c.requests[2].activeAtProbe = false; },
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
  const parserState = () => ({ doneCount: 0, errorCount: 0, eventCount: 0, now: () => 7,
    resolveRunStarted: () => {} });
  const parsed = parserState();
  await consumeSseChunks([
    new TextEncoder().encode('event: run_started\r\ndata: {"runId"'),
    new TextEncoder().encode(':"r1"}\r\n\r\nevent: note\ndata: {\ndata: "note":true\ndata: }\n'),
    new TextEncoder().encode('\nevent: done\ndata: {}\n\n'),
  ], parsed);
  assert.equal(parsed.eventCount, 3);
  assert.equal(parsed.doneCount, 1);
  assert.equal(parsed.errorCount, 0);
  assert.equal(parsed.runStartedAt, 7);
  assert.equal(parsed.terminalAt, 7);
  await assert.rejects(
    consumeSseChunks(
      [new TextEncoder().encode('event: run_started\ndata: {bad}\n\n')], parserState()),
    /INVALID_RESPONSE/);
  await assert.rejects(
    consumeSseChunks(
      [new TextEncoder().encode('event: done\ndata: not-json\n\n')], parserState()),
    /INVALID_RESPONSE/);
  await assert.rejects(
    consumeSseChunks(
      [new TextEncoder().encode('event: done\ndata: {}')], parserState()),
    /INVALID_RESPONSE/);
  const liveCapture = (arm) => ({
    captureKind: 'live', arm, offered: 4, concurrency: 4,
    policy: { perContextLimit: 16, aggregateLimit: 3, retryAfterSeconds: 1 },
    activeBaseline: 1, activeAfter: 1, holderCount: 2, timingPrecondition: true,
    timeline: { overflowLaunchedAt: 3 },
    requests: [0, 1].flatMap(index => {
      const contextId = arm === 'context-many' ? `client-${index + 1}` : 'client-1';
      return [
        { id: `chat-${index}`, contextId, operation: 'chat', status: 200, durationMs: 10,
          streamed: true, activeAtProbe: true,
          timeline: { responseHeadersAt: 1, runStartedAt: 2, terminalAt: 10 },
          terminal: { doneCount: 1, errorCount: 0, eventCount: 3, eof: true } },
        { id: `search-${index}`, contextId, operation: 'search', status: 429, durationMs: 2,
          code: 'ADMISSION_ENGINE_LIMIT', retryAfter: '1',
          timeline: { responseHeadersAt: 5 } },
      ];
    }),
  });
  assert.equal(analyzePair(liveCapture('context-many'), liveCapture('context-one')).verdict, 'PASS');
  const mistimed = liveCapture('context-many');
  mistimed.requests[0].timeline.terminalAt = 4;
  assert.equal(analyzePair(mistimed, liveCapture('context-one')).verdict, 'FAIL');
  const leaked = liveCapture('context-many');
  leaked.activeAfter = 2;
  assert.equal(analyzePair(leaked, liveCapture('context-one')).verdict, 'FAIL');
  const fairness = () => ({
    captureKind: 'live-fairness', arm: 'fairness', offered: 7, concurrency: 7,
    policy: { perContextLimit: 2, aggregateLimit: 6, retryAfterSeconds: 1 },
    activeBaseline: 1, activeAfter: 1, holderCount: 2, timingPrecondition: true,
    timeline: { probesLaunchedAt: 3 },
    requests: [
      ...[0, 1].map(index => ({
        id: `holder-${index}`, contextId: 'full-bucket', operation: 'chat', role: 'holder',
        status: 200, durationMs: 10, streamed: true, activeAtProbe: true,
        timeline: { responseHeadersAt: 1, runStartedAt: 2, terminalAt: 10 },
        terminal: { doneCount: 1, errorCount: 0, eventCount: 3, eof: true },
      })),
      { id: 'same-search', contextId: 'full-bucket', operation: 'search', role: 'same-bucket-search',
        status: 429, code: 'ADMISSION_CONTEXT_LIMIT', retryAfter: '1', jsonValidated: true,
        durationMs: 2, timeline: { responseHeadersAt: 5 } },
      { id: 'same-suggest', contextId: 'full-bucket', operation: 'suggest', role: 'same-bucket-suggest',
        status: 429, code: 'ADMISSION_CONTEXT_LIMIT', retryAfter: '1', jsonValidated: true,
        durationMs: 2, timeline: { responseHeadersAt: 5 } },
      { id: 'same-mcp', contextId: 'full-bucket', operation: 'mcp', role: 'same-bucket-mcp',
        status: 429, code: 'ADMISSION_CONTEXT_LIMIT', retryAfter: '1', jsonValidated: true,
        mcp: { id: 'fairness-probe', errorCode: 'ADMISSION_CONTEXT_LIMIT', errorNumber: -32000 },
        durationMs: 2, timeline: { responseHeadersAt: 5 } },
      { id: 'health', contextId: 'full-bucket', operation: 'health', role: 'same-bucket-health',
        status: 200, jsonValidated: true, durationMs: 2,
        timeline: { responseHeadersAt: 5 } },
      { id: 'other', contextId: 'other-bucket', operation: 'search', role: 'other-bucket-search',
        status: 200, jsonValidated: true, durationMs: 2,
        timeline: { responseHeadersAt: 5 } },
    ],
  });
  assert.equal(analyzeFairness(fairness()).verdict, 'PASS');
  for (const mutation of [
    (value) => { value.requests[2].code = 'ADMISSION_ENGINE_LIMIT'; },
    (value) => { value.requests[6].status = 429; },
    (value) => { value.requests[0].timeline.terminalAt = 4; },
    (value) => { value.policy.aggregateLimit = 4; },
    (value) => { value.requests[0].terminal.doneCount = 0; },
    (value) => { value.activeAfter = 2; },
    (value) => { value.requests[4].mcp.errorNumber = -32600; },
    (value) => { value.requests[5].status = 429; },
  ]) {
    const value = fairness();
    mutation(value);
    assert.equal(analyzeFairness(value).verdict, 'FAIL');
  }
  console.log(`admission oracle self-test: PASS (${checked + 18} cases)`);
}

function elapsedMs(startNs) {
  return Number(process.hrtime.bigint() - startNs) / 1_000_000;
}

function headers(base, token, contextId) {
  return {
    ...base,
    'X-JustSearch-Session': token,
    'X-JustSearch-Client-Id': contextId,
    'X-JustSearch-Client-Kind': 'MCP_CLIENT',
    'X-JustSearch-Transport': 'MCP',
    'X-JustSearch-Session-Id': randomUUID(),
  };
}

async function readBoundedBytes(body) {
  if (!body) return new Uint8Array();
  const reader = body.getReader();
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > BODY_LIMIT_BYTES) {
        await reader.cancel();
        throw new Error('RESPONSE_TOO_LARGE');
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const combined = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    combined.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return combined;
}

async function responseJson(response) {
  const bytes = await readBoundedBytes(response.body);
  if (bytes.byteLength === 0) throw new Error('INVALID_RESPONSE');
  try {
    return JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    throw new Error('INVALID_RESPONSE');
  }
}

async function responseCode(response) {
  const parsed = await responseJson(response);
  const code = parsed?.errorCode ?? parsed?.code;
  return typeof code === 'string' ? code : undefined;
}

function requestFailure(error, timedOut = false) {
  if (timedOut || error?.name === 'TimeoutError') return 'TIMED_OUT';
  if (error?.message === 'RESPONSE_TOO_LARGE') return 'RESPONSE_TOO_LARGE';
  if (error?.message === 'INVALID_RESPONSE') return 'INVALID_RESPONSE';
  return 'TRANSPORT_FAILURE';
}

function setupFailure(error) {
  if (error?.name === 'TimeoutError') return 'TIMED_OUT';
  const known = new Set([
    'INVALID_TOKEN_FILE',
    'EMPTY_TOKEN_FILE',
    'TOKEN_ENDPOINT_HTTP_FAILURE',
    'TOKEN_ENDPOINT_INVALID',
    'RESPONSE_TOO_LARGE',
    'POLICY_HTTP_FAILURE',
    'POLICY_INVALID_JSON',
    'POLICY_ENGINE_ADMISSION_ABSENT',
    'POLICY_INVALID_AVAILABLE_CAPACITY',
    'POLICY_CONTEXT_LIMIT_BINDS_LOAD',
    'POLICY_FAIRNESS_BINDS_AGGREGATE',
    'INVALID_BASE_URL',
  ]);
  return known.has(error?.message) ? error.message : 'TRANSPORT_FAILURE';
}

function parseSseFrame(raw) {
  let event = 'message';
  const data = [];
  for (const line of raw.split(/\r?\n/)) {
    if (line.startsWith(':')) continue;
    const separator = line.indexOf(':');
    const field = separator < 0 ? line : line.slice(0, separator);
    let value = separator < 0 ? '' : line.slice(separator + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') event = value;
    else if (field === 'data') data.push(value);
  }
  return data.length ? { event, data: data.join('\n') } : null;
}

async function consumeSseChunks(chunks, state) {
  const decoder = new TextDecoder();
  let buffer = '';
  let size = 0;
  const accept = (raw) => {
    const frame = parseSseFrame(raw);
    if (!frame) return;
    let payload;
    try {
      payload = JSON.parse(frame.data);
    } catch {
      throw new Error('INVALID_RESPONSE');
    }
    state.eventCount++;
    if (frame.event === 'run_started' && state.runStartedAt === undefined) {
      state.runStartedAt = state.now();
      state.resolveRunStarted(true);
    }
    if (frame.event === 'done') state.doneCount++;
    if (frame.event === 'error') {
      state.errorCount++;
      const code = payload?.errorCode ?? payload?.code ?? payload?.reasonCode;
      if (typeof code === 'string') state.errorCode = code;
    }
    if ((frame.event === 'done' || frame.event === 'error')
        && state.terminalAt === undefined) {
      state.terminalAt = state.now();
    }
  };
  for await (const chunk of chunks) {
    size += chunk.byteLength;
    if (size > BODY_LIMIT_BYTES) throw new Error('RESPONSE_TOO_LARGE');
    buffer += decoder.decode(chunk, { stream: true });
    while (true) {
      const boundary = buffer.search(/\r?\n\r?\n/);
      if (boundary < 0) break;
      const delimiter = buffer.slice(boundary).match(/^\r?\n\r?\n/)[0];
      accept(buffer.slice(0, boundary));
      buffer = buffer.slice(boundary + delimiter.length);
    }
  }
  buffer += decoder.decode();
  if (buffer.trim()) throw new Error('INVALID_RESPONSE');
}

function consumeSse(response, row, armStartNs, timeoutState) {
  let resolveRunStarted;
  const runStarted = new Promise(resolve => { resolveRunStarted = resolve; });
  const state = {
    doneCount: 0,
    errorCount: 0,
    eventCount: 0,
    settled: false,
    runStarted,
    resolveRunStarted,
    now: () => elapsedMs(armStartNs),
  };
  const chunks = {
    async *[Symbol.asyncIterator]() {
      const reader = response.body.getReader();
      let eof = false;
      try {
        while (true) {
          const item = await reader.read();
          if (item.done) {
            eof = true;
            return;
          }
          yield item.value;
        }
      } finally {
        if (!eof) await reader.cancel().catch(() => {});
        reader.releaseLock();
      }
    },
  };
  const completion = consumeSseChunks(chunks, state)
    .catch((error) => {
      row.error = requestFailure(error, timeoutState.timedOut);
    })
    .finally(() => {
      state.settled = true;
      state.resolveRunStarted(false);
      row.terminal = {
        doneCount: state.doneCount,
        errorCount: state.errorCount,
        eventCount: state.eventCount,
        eof: !row.error,
        ...(state.errorCode ? { errorCode: state.errorCode } : {}),
      };
      if (state.runStartedAt !== undefined) {
        row.timeline.runStartedAt = state.runStartedAt;
      }
      if (state.terminalAt !== undefined) {
        row.timeline.terminalAt = state.terminalAt;
      }
      row.timeline.completedAtMs = elapsedMs(armStartNs);
      row.durationMs = row.timeline.completedAtMs - row.timeline.offeredAtMs;
    });
  return { state, completion };
}

function newRow(id, contextId, operation, startNs) {
  return {
    id,
    contextId,
    operation,
    timeline: { offeredAtMs: elapsedMs(startNs) },
  };
}

async function startChat(
    baseUrl, token, row, sessionId, signal, armStartNs, timeoutState,
    workload = {
      prompt: 'Reply with a brief acknowledgement for an admission measurement.',
      maxTokens: 64,
    }) {
  try {
    const response = await fetch(`${baseUrl}/api/chat/runs`, {
      method: 'POST',
      headers: headers({ Accept: 'text/event-stream', 'Content-Type': 'application/json' }, token,
        row.contextId),
      body: JSON.stringify({
        shapeId: 'core.free-chat',
        sessionId,
        prompt: workload.prompt,
        conversationId: sessionId,
        maxTokens: workload.maxTokens,
      }),
      signal,
    });
    row.status = response.status;
    row.retryAfter = response.headers.get('retry-after') ?? undefined;
    row.timeline.responseHeadersAt = elapsedMs(armStartNs);
    if (!response.ok) {
      row.code = await responseCode(response);
      row.timeline.completedAtMs = elapsedMs(armStartNs);
      row.durationMs = row.timeline.completedAtMs - row.timeline.offeredAtMs;
      return { row, state: null, completion: Promise.resolve() };
    }
    row.streamed = true;
    return { row, ...consumeSse(response, row, armStartNs, timeoutState) };
  } catch (error) {
    row.error = requestFailure(error, timeoutState.timedOut);
    row.timeline.completedAtMs = elapsedMs(armStartNs);
    row.durationMs = row.timeline.completedAtMs - row.timeline.offeredAtMs;
    return { row, state: null, completion: Promise.resolve() };
  }
}

async function runSearch(baseUrl, token, row, signal, armStartNs, timeoutState) {
  return runJsonRequest(baseUrl, token, row, signal, armStartNs, timeoutState, {
    path: '/api/knowledge/search',
    method: 'POST',
    body: { query: 'admission measurement', limit: 10, mode: 'hybrid' },
  });
}

async function runJsonRequest(baseUrl, token, row, signal, armStartNs, timeoutState, request) {
  try {
    const requestHeaders = headers(
      request.body === undefined ? {} : { 'Content-Type': 'application/json' },
      token, row.contextId);
    if (request.mcpSession) requestHeaders['Mcp-Session-Id'] = request.mcpSession;
    const response = await fetch(`${baseUrl}${request.path}`, {
      method: request.method,
      headers: requestHeaders,
      ...(request.body === undefined ? {} : { body: JSON.stringify(request.body) }),
      signal,
    });
    row.status = response.status;
    row.retryAfter = response.headers.get('retry-after') ?? undefined;
    row.timeline.responseHeadersAt = elapsedMs(armStartNs);
    const parsed = await responseJson(response);
    const code = parsed?.errorCode ?? parsed?.code ?? parsed?.error?.data?.errorCode;
    row.code = typeof code === 'string' ? code : undefined;
    if (request.mcpSession) {
      row.mcp = {
        id: parsed?.id,
        errorCode: parsed?.error?.data?.errorCode,
        errorNumber: parsed?.error?.code,
      };
    }
    row.jsonValidated = true;
  } catch (error) {
    row.error = requestFailure(error, timeoutState.timedOut);
  } finally {
    row.timeline.completedAtMs = elapsedMs(armStartNs);
    row.durationMs = row.timeline.completedAtMs - row.timeline.offeredAtMs;
  }
}

async function readPolicy(baseUrl, token, signal) {
  const response = await fetch(`${baseUrl}/api/debug/effective-config`, {
    headers: headers({}, token, 'lane-f-policy'),
    ...(signal ? { signal } : {}),
  });
  if (!response.ok) throw new Error('POLICY_HTTP_FAILURE');
  const bytes = await readBoundedBytes(response.body);
  let document;
  try {
    document = JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    throw new Error('POLICY_INVALID_JSON');
  }
  const policy = document?.engineAdmission;
  if (!Number.isSafeInteger(policy?.perContextLimit)
      || !Number.isSafeInteger(policy?.aggregateLimit)
      || !Number.isSafeInteger(policy?.retryAfterSeconds)
      || policy.retryAfterSeconds < 1
      || !Number.isSafeInteger(policy?.activeWorkCount)) {
    throw new Error('POLICY_ENGINE_ADMISSION_ABSENT');
  }
  const holderCount = policy.aggregateLimit - policy.activeWorkCount;
  if (holderCount < 0) throw new Error('POLICY_INVALID_AVAILABLE_CAPACITY');
  return {
    perContextLimit: policy.perContextLimit,
    aggregateLimit: policy.aggregateLimit,
    retryAfterSeconds: policy.retryAfterSeconds,
    activeWorkCount: policy.activeWorkCount,
  };
}

function aggregatePolicy(policy) {
  const holderCount = policy.aggregateLimit - policy.activeWorkCount;
  if (holderCount < 2 || holderCount > 32) throw new Error('POLICY_INVALID_AVAILABLE_CAPACITY');
  if (policy.perContextLimit < holderCount * 2) {
    throw new Error('POLICY_CONTEXT_LIMIT_BINDS_LOAD');
  }
  return policy;
}

function fairnessPolicy(policy) {
  const available = policy.aggregateLimit - policy.activeWorkCount;
  if (policy.perContextLimit < 1 || policy.perContextLimit > 32
      || available < policy.perContextLimit + 2) {
    throw new Error('POLICY_FAIRNESS_BINDS_AGGREGATE');
  }
  return policy;
}

async function runArm(arm, baseUrl, token, policy) {
  const armStartNs = process.hrtime.bigint();
  const controller = new AbortController();
  const timeoutState = { timedOut: false };
  const timer = setTimeout(() => {
    timeoutState.timedOut = true;
    controller.abort();
  }, ARM_TIMEOUT_MS);
  const holderCount = policy.aggregateLimit - policy.activeWorkCount;
  const contexts = Array.from({ length: holderCount }, (_, index) =>
    arm === 'context-many' ? `lane-f-many-${index + 1}` : 'lane-f-one');
  const rows = contexts.map((contextId, index) =>
    newRow(`${arm}-chat-${index + 1}`, contextId, 'chat', armStartNs));
  let timingPrecondition = false;
  let overflowLaunchedAt;
  let afterPolicy;
  let armError;
  let chats = [];
  try {
    const runNonce = randomUUID();
    chats = await Promise.all(rows.map((row, index) => startChat(
      baseUrl, token, row, `lane-f-${arm}-${runNonce}-${index + 1}`,
      controller.signal, armStartNs, timeoutState)));
    await Promise.all(chats.map(chat => chat.state?.runStarted ?? Promise.resolve(false)));
    const liveAtLaunch = chats.every(chat => chat.state !== null && !chat.state.settled
      && chat.state.runStartedAt !== undefined
      && chat.state.doneCount === 0 && chat.state.errorCount === 0);

    overflowLaunchedAt = elapsedMs(armStartNs);
    const searches = contexts.map((contextId, index) =>
      newRow(`${arm}-search-${index + 1}`, contextId, 'search', armStartNs));
    rows.push(...searches);
    await Promise.all(searches.map(row => runSearch(
      baseUrl, token, row, controller.signal, armStartNs, timeoutState)));
    const latestOverflowHeaders = Math.max(...searches.map(
      row => row.timeline.responseHeadersAt ?? Number.POSITIVE_INFINITY));
    timingPrecondition = liveAtLaunch && Number.isFinite(latestOverflowHeaders)
      && chats.every(chat => chat.state.terminalAt === undefined
        || chat.state.terminalAt > latestOverflowHeaders);
    for (const chat of chats) chat.row.activeAtProbe = timingPrecondition;
    await Promise.all(chats.map(chat => chat.completion));
    afterPolicy = await readPolicy(baseUrl, token, controller.signal);
    timingPrecondition = timingPrecondition
      && afterPolicy.activeWorkCount === policy.activeWorkCount;
  } catch (error) {
    armError = requestFailure(error, timeoutState.timedOut);
    controller.abort();
    await Promise.allSettled(chats.map(chat => chat.completion));
  } finally {
    clearTimeout(timer);
  }
  if (timeoutState.timedOut || armError) {
    for (const row of rows) {
      if (!Number.isFinite(row.durationMs)) {
        row.error = armError ?? 'TIMED_OUT';
        row.timeline.completedAtMs = elapsedMs(armStartNs);
        row.durationMs = row.timeline.completedAtMs - row.timeline.offeredAtMs;
      }
    }
  }
  return {
    captureKind: 'live',
    arm,
    offered: holderCount * 2,
    concurrency: holderCount * 2,
    policy: {
      perContextLimit: policy.perContextLimit,
      aggregateLimit: policy.aggregateLimit,
      retryAfterSeconds: policy.retryAfterSeconds,
    },
    activeBaseline: policy.activeWorkCount,
    activeAfter: afterPolicy?.activeWorkCount,
    holderCount,
    timingPrecondition,
    timeline: { overflowLaunchedAt },
    durationMs: elapsedMs(armStartNs),
    ...(armError ? { error: armError } : {}),
    requests: rows,
  };
}

async function runFairnessArm(baseUrl, token, policy) {
  const arm = 'fairness';
  const armStartNs = process.hrtime.bigint();
  const controller = new AbortController();
  const timeoutState = { timedOut: false };
  const timer = setTimeout(() => {
    timeoutState.timedOut = true;
    controller.abort();
  }, ARM_TIMEOUT_MS);
  const holderCount = policy.perContextLimit;
  const holderContext = 'lane-f-fairness-full';
  const rows = Array.from({ length: holderCount }, (_, index) => ({
    ...newRow(`fairness-chat-${index + 1}`, holderContext, 'chat', armStartNs),
    role: 'holder',
  }));
  let chats = [];
  let probesLaunchedAt;
  let timingPrecondition = false;
  let afterPolicy;
  let armError;
  try {
    const runNonce = randomUUID();
    chats = await Promise.all(rows.map((row, index) => startChat(
      baseUrl, token, row, `lane-f-fairness-${runNonce}-${index + 1}`,
      controller.signal, armStartNs, timeoutState, {
        prompt: 'Count from 1 through 400, writing exactly one number per line. Do not summarize.',
        maxTokens: 512,
      })));
    await Promise.all(chats.map(chat => chat.state?.runStarted ?? Promise.resolve(false)));
    const liveAtLaunch = chats.every(chat => chat.state !== null && !chat.state.settled
      && chat.state.runStartedAt !== undefined
      && chat.state.doneCount === 0 && chat.state.errorCount === 0);

    probesLaunchedAt = elapsedMs(armStartNs);
    const sameSearch = {
      ...newRow('fairness-same-bucket-search', holderContext, 'search', armStartNs),
      role: 'same-bucket-search',
    };
    const sameSuggest = {
      ...newRow('fairness-same-bucket-suggest', holderContext, 'suggest', armStartNs),
      role: 'same-bucket-suggest',
    };
    const sameMcp = {
      ...newRow('fairness-same-bucket-mcp', holderContext, 'mcp', armStartNs),
      role: 'same-bucket-mcp',
    };
    const health = {
      ...newRow('fairness-same-bucket-health', holderContext, 'health', armStartNs),
      role: 'same-bucket-health',
    };
    const otherSearch = {
      ...newRow('fairness-other-bucket-search', 'lane-f-fairness-other', 'search', armStartNs),
      role: 'other-bucket-search',
    };
    rows.push(sameSearch, sameSuggest, sameMcp, health, otherSearch);
    await Promise.all([
      runSearch(baseUrl, token, sameSearch, controller.signal, armStartNs, timeoutState),
      runJsonRequest(baseUrl, token, sameSuggest, controller.signal, armStartNs, timeoutState, {
        path: '/api/knowledge/suggest?q=admission', method: 'GET',
      }),
      runJsonRequest(baseUrl, token, sameMcp, controller.signal, armStartNs, timeoutState, {
        path: '/mcp', method: 'POST', mcpSession: randomUUID(),
        body: { jsonrpc: '2.0', id: 'fairness-probe', method: 'ping' },
      }),
      runJsonRequest(baseUrl, token, health, controller.signal, armStartNs, timeoutState, {
        path: '/api/health', method: 'GET',
      }),
      runSearch(baseUrl, token, otherSearch, controller.signal, armStartNs, timeoutState),
    ]);
    const latestProbeHeaders = Math.max(...[sameSearch, sameSuggest, sameMcp, health, otherSearch]
      .map(row => row.timeline.responseHeadersAt ?? Number.POSITIVE_INFINITY));
    timingPrecondition = liveAtLaunch && Number.isFinite(latestProbeHeaders)
      && chats.every(chat => chat.state.terminalAt === undefined
        || chat.state.terminalAt > latestProbeHeaders);
    for (const chat of chats) chat.row.activeAtProbe = timingPrecondition;
    await Promise.all(chats.map(chat => chat.completion));
    afterPolicy = await readPolicy(baseUrl, token, controller.signal);
    timingPrecondition = timingPrecondition
      && afterPolicy.activeWorkCount === policy.activeWorkCount;
  } catch (error) {
    armError = requestFailure(error, timeoutState.timedOut);
    controller.abort();
    await Promise.allSettled(chats.map(chat => chat.completion));
  } finally {
    clearTimeout(timer);
  }
  if (timeoutState.timedOut || armError) {
    for (const row of rows) {
      if (!Number.isFinite(row.durationMs)) {
        row.error = armError ?? 'TIMED_OUT';
        row.timeline.completedAtMs = elapsedMs(armStartNs);
        row.durationMs = row.timeline.completedAtMs - row.timeline.offeredAtMs;
      }
    }
  }
  return {
    captureKind: 'live-fairness',
    arm,
    offered: holderCount + 5,
    concurrency: holderCount + 5,
    policy: {
      perContextLimit: policy.perContextLimit,
      aggregateLimit: policy.aggregateLimit,
      retryAfterSeconds: policy.retryAfterSeconds,
    },
    activeBaseline: policy.activeWorkCount,
    activeAfter: afterPolicy?.activeWorkCount,
    holderCount,
    timingPrecondition,
    timeline: { probesLaunchedAt },
    durationMs: elapsedMs(armStartNs),
    ...(armError ? { error: armError } : {}),
    requests: rows,
  };
}

function writeCapture(directory, capture) {
  fs.mkdirSync(directory, { recursive: true, mode: 0o700 });
  fs.writeFileSync(path.join(directory, `${capture.arm}.json`),
    `${JSON.stringify(capture, null, 2)}\n`, { mode: 0o600 });
}

async function liveConnection(directory, baseUrl, tokenFile, partialArm, captureKind = 'live') {
  let normalizedBase;
  let token;
  try {
    const parsedBase = new URL(baseUrl);
    if (!['http:', 'https:'].includes(parsedBase.protocol)) throw new Error('INVALID_BASE_URL');
    normalizedBase = parsedBase.href.replace(/\/$/, '');
    if (tokenFile) {
      const tokenStat = fs.statSync(tokenFile);
      if (!tokenStat.isFile() || tokenStat.size > 64 * 1024) throw new Error('INVALID_TOKEN_FILE');
      token = fs.readFileSync(tokenFile, 'utf8').trim();
      if (!token) throw new Error('EMPTY_TOKEN_FILE');
    } else {
      const response = await fetch(`${normalizedBase}/api/mcp/token`, {
        signal: AbortSignal.timeout(ARM_TIMEOUT_MS),
      });
      if (!response.ok) throw new Error('TOKEN_ENDPOINT_HTTP_FAILURE');
      const bytes = await readBoundedBytes(response.body);
      try {
        const value = JSON.parse(new TextDecoder().decode(bytes))?.token;
        if (typeof value !== 'string') throw new Error('TOKEN_ENDPOINT_INVALID');
        token = value;
      } catch (error) {
        if (error?.message === 'TOKEN_ENDPOINT_INVALID') throw error;
        throw new Error('TOKEN_ENDPOINT_INVALID');
      }
    }
  } catch (error) {
    const code = setupFailure(error);
    writeCapture(directory, {
      captureKind, arm: partialArm, error: code, requests: [],
    });
    throw new Error(code);
  }
  return { baseUrl: normalizedBase, token };
}

async function policyOrPartial(
    directory, arm, baseUrl, token, validate = value => value, captureKind = 'live') {
  try {
    return validate(await readPolicy(baseUrl, token, AbortSignal.timeout(ARM_TIMEOUT_MS)));
  } catch (error) {
    const code = setupFailure(error);
    writeCapture(directory, { captureKind, arm, error: code, requests: [] });
    throw new Error(code);
  }
}

async function captureLive(directory, baseUrl, tokenFile) {
  const connection = await liveConnection(directory, baseUrl, tokenFile, 'context-many');
  const captures = [];
  for (const arm of ['context-many', 'context-one']) {
    const policy = await policyOrPartial(
      directory, arm, connection.baseUrl, connection.token, aggregatePolicy);
    const capture = await runArm(arm, connection.baseUrl, connection.token, policy);
    writeCapture(directory, capture);
    captures.push(capture);
    if (capture.error) throw new Error(capture.error);
  }
  const result = analyzePair(...captures);
  if (result.many.counts.admitted !== captures[0].holderCount
      || result.many.counts.engineRejected !== captures[0].holderCount
      || result.one.counts.admitted !== captures[1].holderCount
      || result.one.counts.engineRejected !== captures[1].holderCount) {
    result.failures.push('live aggregate wave counts do not match available owner capacity');
    result.verdict = 'FAIL';
  }
  console.log(JSON.stringify(result, null, 2));
  if (result.verdict !== 'PASS') process.exitCode = 1;
}

async function captureFairness(directory, baseUrl, tokenFile) {
  const connection = await liveConnection(
    directory, baseUrl, tokenFile, 'fairness', 'live-fairness');
  const policy = await policyOrPartial(
    directory, 'fairness', connection.baseUrl, connection.token, fairnessPolicy, 'live-fairness');
  const capture = await runFairnessArm(connection.baseUrl, connection.token, policy);
  writeCapture(directory, capture);
  if (capture.error) throw new Error(capture.error);
  const result = analyzeFairness(capture);
  console.log(JSON.stringify(result, null, 2));
  if (result.verdict !== 'PASS') process.exitCode = 1;
}

function readCapture(directory, name) {
  const file = path.join(directory, `${name}.json`);
  if (fs.statSync(file).size > 16 * 1024 * 1024) throw new Error(`capture too large: ${name}`);
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function captureOptions(argv, commandFlag) {
  const options = {};
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    if (!value || ![commandFlag, '--base-url', '--token-file'].includes(flag)
        || options[flag] !== undefined) {
      throw new Error('INVALID_CAPTURE_ARGUMENTS');
    }
    options[flag] = value;
  }
  if (!options[commandFlag] || !options['--base-url']) {
    throw new Error('INVALID_CAPTURE_ARGUMENTS');
  }
  return options;
}

async function main() {
  if (process.argv.length === 3 && process.argv[2] === '--self-test') {
    await selfTest();
    return;
  }
  if (process.argv.length === 4 && process.argv[2] === '--analyze') {
    const directory = process.argv[3];
    const result = analyzePair(readCapture(directory, 'context-many'), readCapture(directory, 'context-one'));
    console.log(JSON.stringify(result, null, 2));
    process.exitCode = result.verdict === 'PASS' ? 0 : 1;
    return;
  }
  if (process.argv.includes('--capture')) {
    const options = captureOptions(process.argv.slice(2), '--capture');
    await captureLive(
      options['--capture'], options['--base-url'], options['--token-file']);
    return;
  }
  if (process.argv.includes('--capture-fairness')) {
    const options = captureOptions(process.argv.slice(2), '--capture-fairness');
    await captureFairness(
      options['--capture-fairness'], options['--base-url'], options['--token-file']);
    return;
  }
  throw new Error('usage: admission-loop.mjs --self-test | --analyze <capture-directory> | '
    + '--capture <outdir> --base-url <url> [--token-file <path>] | '
    + '--capture-fairness <outdir> --base-url <url> [--token-file <path>]');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    await main();
  } catch (error) {
    console.error(JSON.stringify({ verdict: 'FAIL', error: error.message }));
    process.exitCode = 1;
  }
}
