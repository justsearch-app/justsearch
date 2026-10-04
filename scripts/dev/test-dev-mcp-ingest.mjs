import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import http from 'node:http';
import path from 'node:path';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const ENTRY = path.join(REPO_ROOT, 'scripts', 'dev', 'justsearch-dev-mcp.mjs');

function startMcp(entry, args = [], extraEnv = {}) {
  const child = spawn(process.execPath, [entry, ...args], {
    cwd: REPO_ROOT,
    env: { ...process.env, JUSTSEARCH_DEV_MCP_LOG_NDJSON: '0', ...extraEnv },
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

async function connectMcp(entry, args, env) {
  const client = startMcp(entry, args, env);
  try {
    const initialized = await client.request('initialize', {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'test-dev-mcp-ingest', version: '1' },
    });
    assert.equal(initialized.error, undefined, JSON.stringify(initialized.error));
    client.notify('notifications/initialized', {});
    return client;
  } catch (error) {
    await client.close();
    throw error;
  }
}

async function startMockApi(responses) {
  const ingestRequests = [];
  const apiRequests = [];
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
        apiRequests.push({ path: request.url });
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({
          state: 'running',
          historySince: 12,
          acceptedAt: 1_700_000_000_000,
          unitsCompleted: 3,
          unitsFailed: 0,
        }));
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
    apiRequests,
    close: () => new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve())),
  };
}

function structured(result) {
  return result.structuredContent ?? JSON.parse(result.content?.[0]?.text ?? '{}');
}

test('dev ingest forwards operation controls and transport, preserving 428 and failure responses', async (t) => {
  const challenge = {
    success: false,
    message: 'Explicit approval is required',
    executionId: null,
    structuredData: { operationKey: 'ingest-key', operationId: 'operation-7' },
    errorClass: 'ApprovalRequired',
    errorCode: 'CONFIRMATION_REQUIRED',
    errorDetails: { key: 'ingest-key', nonce: 'prepared-nonce', pendingId: 'pending-3' },
    retryable: false,
  };
  const accepted = {
    success: true,
    message: 'Operation accepted',
    executionId: 'execution-9',
    structuredData: { operationKey: 'ingest-key', operationId: 'operation-7', state: 'ACCEPTED' },
    errorClass: null,
    errorCode: null,
    errorDetails: {},
    retryable: false,
  };
  const rejected = {
    success: false,
    message: 'The path could not be prepared',
    executionId: null,
    structuredData: { operationKey: 'rejected-key' },
    errorClass: 'PreparationRefused',
    errorCode: 'BAD_REQUEST',
    errorDetails: { reason: 'unreadable' },
    retryable: false,
  };
  const backend = await startMockApi([
    { status: 428, body: challenge },
    { status: 200, body: accepted },
    { status: 200, body: rejected },
  ]);
  t.after(() => backend.close());
  const client = await connectMcp(ENTRY);
  t.after(() => client.close());
  const absolutePath = path.join(REPO_ROOT, 'AGENTS.md');

  const confirmation = structured((await client.request('tools/call', {
    name: 'justsearch.dev.ingest',
    arguments: {
      apiPort: backend.port,
      paths: ['AGENTS.md'],
      collection: '  research  ',
      idempotencyKey: ' stable-key ',
      preparationNonce: ' prepared-nonce ',
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
    idempotencyKey: ' stable-key ',
    preparationNonce: ' prepared-nonce ',
  }, 'the client must not add an approval token or rewrite the supplied controls');
  assert.equal(backend.ingestRequests[0].headers['x-justsearch-transport'], 'MCP');

  const resumed = structured((await client.request('tools/call', {
    name: 'justsearch.dev.ingest',
    arguments: {
      apiPort: backend.port,
      paths: ['AGENTS.md'],
      collection: '  research  ',
      idempotencyKey: ' stable-key ',
      confirmationToken: ' caller-approved-token ',
      preparationNonce: ' prepared-nonce ',
    },
  })).result);
  assert.equal(resumed.ok, true);
  assert.deepEqual(resumed.operationResponse, accepted);
  assert.deepEqual(backend.ingestRequests[1].body, {
    paths: [absolutePath],
    collection: '  research  ',
    idempotencyKey: ' stable-key ',
    confirmationToken: ' caller-approved-token ',
    preparationNonce: ' prepared-nonce ',
  });
  assert.equal(backend.ingestRequests[1].headers['x-justsearch-transport'], 'MCP');

  const failed = structured((await client.request('tools/call', {
    name: 'justsearch.dev.ingest',
    arguments: { apiPort: backend.port, paths: ['AGENTS.md'], collection: null },
  })).result);
  assert.equal(failed.ok, false, 'HTTP 200 with success=false must remain a failed operation');
  assert.equal(failed.operationResponse.errorCode, 'BAD_REQUEST');
  assert.deepEqual(backend.ingestRequests[2].body, { paths: [absolutePath], collection: null });

  const outside = await client.request('tools/call', {
    name: 'justsearch.dev.ingest',
    arguments: { apiPort: backend.port, paths: ['../outside.md'] },
  });
  assert.equal(outside.result?.isError, true, 'dev path containment must reject paths outside the repo');
  assert.equal(backend.ingestRequests.length, 3, 'a rejected path must not reach the HTTP backend');

  const operationKey = '019940c0-0000-7000-8000-000000000001';
  const historyPath = `/api/operation-history/${operationKey}`;
  const outcome = structured((await client.request('tools/call', {
    name: 'justsearch.dev.api_call',
    arguments: { apiPort: backend.port, method: 'GET', path: historyPath, outputMode: 'full' },
  })).result);
  assert.equal(outcome.ok, true);
  assert.equal(outcome.method, 'GET');
  assert.equal(outcome.path, historyPath);
  assert.deepEqual(outcome.json, {
    state: 'running',
    historySince: 12,
    acceptedAt: 1_700_000_000_000,
    unitsCompleted: 3,
    unitsFailed: 0,
  });
  assert.deepEqual(backend.apiRequests, [{ path: historyPath }],
    'the allowlisted normalized path must be the path sent to the backend');

  const postRejected = structured((await client.request('tools/call', {
    name: 'justsearch.dev.api_call',
    arguments: { apiPort: backend.port, method: 'POST', path: historyPath, body: {} },
  })).result);
  assert.equal(postRejected.ok, false, 'the operation outcome route is GET-only');
  assert.match(postRejected.error.message, /Method POST not allowed/);

  const traversalRejected = structured((await client.request('tools/call', {
    name: 'justsearch.dev.api_call',
    arguments: { apiPort: backend.port, method: 'GET', path: '/api/operation-history/%2e%2e' },
  })).result);
  assert.equal(traversalRejected.ok, false, 'encoded dot segments must be rejected before HTTP');
  assert.equal(backend.apiRequests.length, 1);
});
