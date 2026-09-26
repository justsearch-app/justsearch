import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import http from 'node:http';
import path from 'node:path';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const ENTRY = path.join(REPO_ROOT, 'scripts', 'prod', 'justsearch-mcp.mjs');

function startMcp(entry, args = []) {
  const child = spawn(process.execPath, [entry, ...args], {
    cwd: REPO_ROOT,
    env: process.env,
    stdio: ['pipe', 'pipe', 'pipe'],
    windowsHide: true,
  });
  const pending = new Map();
  let nextId = 1;
  let buffer = '';
  let stderr = '';

  child.stderr.on('data', (chunk) => { stderr = (stderr + chunk.toString('utf8')).slice(-8000); });
  child.stdout.on('data', (chunk) => {
    buffer += chunk.toString('utf8');
    let newline;
    while ((newline = buffer.indexOf('\n')) !== -1) {
      const line = buffer.slice(0, newline).trim();
      buffer = buffer.slice(newline + 1);
      if (!line) continue;
      let message;
      try {
        message = JSON.parse(line);
      } catch {
        continue;
      }
      const waiter = pending.get(message.id);
      if (waiter) {
        pending.delete(message.id);
        clearTimeout(waiter.timer);
        waiter.resolve(message);
      }
    }
  });
  child.on('exit', (code, signal) => {
    for (const [id, waiter] of pending) {
      pending.delete(id);
      clearTimeout(waiter.timer);
      waiter.reject(new Error(`MCP server exited (${code ?? signal}); stderr=${stderr}`));
    }
  });

  function request(method, params, timeoutMs = 15_000) {
    return new Promise((resolve, reject) => {
      const id = nextId++;
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error(`${method} timed out; stderr=${stderr}`));
      }, timeoutMs);
      pending.set(id, { resolve, reject, timer });
      child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
    });
  }

  async function close() {
    if (child.exitCode !== null || child.signalCode !== null) return;
    const exited = once(child, 'exit');
    child.kill();
    await Promise.race([exited, new Promise((resolve) => setTimeout(resolve, 3000))]);
  }

  return {
    request,
    notify(method, params) {
      child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method, params })}\n`);
    },
    close,
  };
}

async function connectMcp(entry, args) {
  const client = startMcp(entry, args);
  try {
    const initialized = await client.request('initialize', {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'test-prod-mcp-ingest', version: '1' },
    });
    assert.equal(initialized.error, undefined, JSON.stringify(initialized.error));
    client.notify('notifications/initialized', {});
    return client;
  } catch (error) {
    await client.close();
    throw error;
  }
}

async function startMockApi(responses, outcomeResponses = []) {
  const ingestRequests = [];
  const outcomeRequests = [];
  const server = http.createServer((request, response) => {
    let text = '';
    request.setEncoding('utf8');
    request.on('data', (chunk) => { text += chunk; });
    request.on('end', () => {
      if (request.method === 'GET' && request.url === '/api/status') {
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end('{"status":"ok"}');
        return;
      }
      if (request.method === 'GET' && request.url === '/api/mcp/token') {
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end('{"token":"mock-session-token"}');
        return;
      }
      if (request.method === 'GET' && request.url.startsWith('/api/operation-history/')) {
        outcomeRequests.push({ path: request.url });
        const next = outcomeResponses.shift() ?? { status: 500, body: { message: 'missing outcome response' } };
        response.writeHead(next.status, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify(next.body));
        return;
      }
      if (request.method === 'POST' && request.url === '/api/knowledge/ingest') {
        ingestRequests.push({
          headers: request.headers,
          body: JSON.parse(text),
        });
        const next = responses.shift() ?? { status: 500, body: { message: 'missing mock response' } };
        response.writeHead(next.status, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify(next.body));
        return;
      }
      response.writeHead(404);
      response.end();
    });
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  return {
    port: server.address().port,
    ingestRequests,
    outcomeRequests,
    close: () => new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve())),
  };
}

function structured(result) {
  return result.structuredContent ?? JSON.parse(result.content?.[0]?.text ?? '{}');
}

test('production ingest preserves the operation response, confirmation refusal, and token headers', async (t) => {
  const challenge = {
    success: false,
    message: 'Explicit approval is required',
    executionId: null,
    structuredData: { operationKey: 'prod-ingest-key', operationId: 'operation-12' },
    errorClass: 'ApprovalRequired',
    errorCode: 'CONFIRMATION_REQUIRED',
    errorDetails: { key: 'prod-ingest-key', nonce: 'prod-nonce', pendingId: 'prod-pending' },
    retryable: false,
  };
  const accepted = {
    success: true,
    message: 'Operation accepted',
    executionId: 'execution-14',
    structuredData: { operationKey: 'prod-ingest-key', operationId: 'operation-12', state: 'ACCEPTED' },
    errorClass: null,
    errorCode: null,
    errorDetails: {},
    retryable: false,
  };
  const rejected = {
    success: false,
    message: 'The path could not be prepared',
    executionId: null,
    structuredData: { operationKey: 'prod-rejected-key' },
    errorClass: 'PreparationRefused',
    errorCode: 'BAD_REQUEST',
    errorDetails: { reason: 'unreadable' },
    retryable: false,
  };
  const failedOutcome = {
    state: 'failed',
    phase: 'complete',
    historySince: 19,
    acceptedAt: 1_700_000_000_010,
    completedAt: 1_700_000_000_040,
    unitsCompleted: 4,
    unitsFailed: 1,
    reason: 'preparation_refused',
    result: { code: 'BAD_REQUEST', executionId: 'execution-failed' },
  };
  const unknownOutcome = { state: 'unknown', historySince: 19 };
  const expiredOutcome = { state: 'expired', historySince: 19 };
  const invalidOutcome = {
    error: 'operationKey is outside the accepted clock range',
    errorCode: 'OPERATION_KEY_INVALID', errorClass: 'BAD_REQUEST', retryable: false,
  };
  const storageFailure = {
    error: 'history storage unavailable',
    errorCode: 'OPERATION_STORAGE_FAILED', errorClass: 'HANDLER_ERROR', retryable: false,
  };
  const backend = await startMockApi([
    { status: 428, body: challenge },
    { status: 200, body: accepted },
    { status: 200, body: rejected },
  ], [
    { status: 200, body: failedOutcome },
    { status: 200, body: unknownOutcome },
    { status: 200, body: expiredOutcome },
    { status: 400, body: invalidOutcome },
    { status: 500, body: storageFailure },
  ]);
  t.after(() => backend.close());
  const client = await connectMcp(ENTRY, ['--port', String(backend.port)]);
  t.after(() => client.close());
  const absolutePath = path.join(REPO_ROOT, 'AGENTS.md');

  const confirmation = structured((await client.request('tools/call', {
    name: 'justsearch_ingest',
    arguments: {
      paths: [absolutePath],
      collection: '  research  ',
      idempotencyKey: ' prod-stable-key ',
      preparationNonce: ' prod-nonce ',
    },
  })).result);
  assert.equal(confirmation.ok, false, 'HTTP 428 must remain an unapproved refusal');
  assert.equal(confirmation.statusCode, 428);
  assert.deepEqual(confirmation.operationResponse, challenge,
    'the key, nonce, pending identity, and error details must survive the MCP result');
  assert.equal(confirmation.error.code, 'CONFIRMATION_REQUIRED');
  assert.deepEqual(backend.ingestRequests[0].body, {
    paths: [absolutePath],
    collection: '  research  ',
    idempotencyKey: ' prod-stable-key ',
    preparationNonce: ' prod-nonce ',
  }, 'the client must not add an approval token or rewrite supplied controls');
  assert.equal(backend.ingestRequests[0].headers['x-justsearch-transport'], 'MCP');
  assert.equal(backend.ingestRequests[0].headers['x-justsearch-session'], 'mock-session-token');

  const resumed = structured((await client.request('tools/call', {
    name: 'justsearch_ingest',
    arguments: {
      paths: [absolutePath],
      collection: '  research  ',
      idempotencyKey: ' prod-stable-key ',
      confirmationToken: ' caller-approved-token ',
      preparationNonce: ' prod-nonce ',
    },
  })).result);
  assert.equal(resumed.ok, true);
  assert.deepEqual(resumed.operationResponse, accepted);
  assert.deepEqual(backend.ingestRequests[1].body, {
    paths: [absolutePath],
    collection: '  research  ',
    idempotencyKey: ' prod-stable-key ',
    confirmationToken: ' caller-approved-token ',
    preparationNonce: ' prod-nonce ',
  });
  assert.equal(backend.ingestRequests[1].headers['x-justsearch-transport'], 'MCP');
  assert.equal(backend.ingestRequests[1].headers['x-justsearch-session'], 'mock-session-token');

  const failed = structured((await client.request('tools/call', {
    name: 'justsearch_ingest',
    arguments: { paths: [absolutePath], collection: null },
  })).result);
  assert.equal(failed.ok, false, 'HTTP 200 with success=false must remain a failed operation');
  assert.deepEqual(failed.operationResponse, rejected);
  assert.deepEqual(backend.ingestRequests[2].body, { paths: [absolutePath], collection: null });

  const listedTools = (await client.request('tools/list', {})).result.tools;
  const outcomeTool = listedTools.find((tool) => tool.name === 'justsearch_operation_outcome');
  assert.ok(outcomeTool, 'the prod MCP must expose an operation outcome read tool');
  assert.equal(outcomeTool.annotations.readOnlyHint, true);
  assert.ok(outcomeTool.description.includes('GET /api/operation-history/{operationKey}'));
  assert.deepEqual(outcomeTool.inputSchema.required, ['operationKey']);

  const operationKey = '019940c0-0000-7000-8000-000000000001';
  for (const [key, expected] of [
    [operationKey, failedOutcome],
    ['019940c0-0000-7000-8000-000000000002', unknownOutcome],
    ['019940c0-0000-7000-8000-000000000003', expiredOutcome],
  ]) {
    const response = await client.request('tools/call', {
      name: 'justsearch_operation_outcome',
      arguments: { operationKey: key },
    });
    assert.equal(response.result?.isError, undefined, `state=${expected.state} is a successful read`);
    assert.deepEqual(response.result?.structuredContent, expected,
      `the complete ${expected.state} outcome DTO must survive the HTTP bridge`);
  }

  for (const operationKey of ['..', 'caller key/with?reserved']) {
    const invalid = await client.request('tools/call', {
      name: 'justsearch_operation_outcome', arguments: { operationKey },
    });
    assert.equal(invalid.result?.isError, true);
    assert.equal(backend.outcomeRequests.length, 3, 'an invalid key must not select a different route');
  }
  const futureKey = 'ffffffff-ffff-7000-8000-000000000001';
  const invalidKeyResponse = await client.request('tools/call', {
    name: 'justsearch_operation_outcome',
    arguments: { operationKey: futureKey },
  });
  assert.equal(invalidKeyResponse.result?.isError, true);
  assert.equal(invalidKeyResponse.result?.structuredContent?.error?.code, 'OPERATION_KEY_INVALID');
  assert.deepEqual(invalidKeyResponse.result?.structuredContent?.operationResponse, invalidOutcome);
  assert.equal(backend.outcomeRequests[3].path,
    `/api/operation-history/${futureKey}`);

  const httpFailure = await client.request('tools/call', {
    name: 'justsearch_operation_outcome',
    arguments: { operationKey },
  });
  assert.equal(httpFailure.result?.isError, true);
  assert.equal(httpFailure.result?.structuredContent?.error?.code, 'OPERATION_STORAGE_FAILED');
  assert.deepEqual(httpFailure.result?.structuredContent?.operationResponse, storageFailure);
  assert.equal(httpFailure.result?.structuredContent?.ok, false);
  assert.equal(backend.outcomeRequests[4].path, `/api/operation-history/${operationKey}`);
});
