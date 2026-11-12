/**
 * Follow-up B1, FB2b — the production MCP server's justsearch_status tool gives no Worker count
 * while the Engine marks its counts unknown (`indexedDocumentsUnknown`): no document count, no queue
 * depth and no enrichment counts, never the placeholder 0s. A counted 0 is a count, and real
 * numbers return when the Engine has them.
 *
 * Runs the real server entry point over stdio against a local stand-in Engine on port 0, served by
 * this test process and named by a runtime manifest in a temporary data folder (discovery proves the
 * Engine by its manifest pid). The port is pinned, so discovery never falls back to another Engine.
 * Run with: node --test scripts/prod/test-prod-mcp-status-counts.mjs
 */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import http from 'node:http';
import path from 'node:path';
import { once } from 'node:events';
import fs from 'node:fs';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const ENTRY = path.join(REPO_ROOT, 'scripts', 'prod', 'justsearch-mcp.mjs');

function startMcp(args, dataDir) {
  const env = { ...process.env, JUSTSEARCH_DATA_DIR: dataDir, JUSTSEARCH_SESSION_TOKEN: 'mock-session-token',
    HOME: dataDir, XDG_DATA_HOME: dataDir };
  delete env.JUSTSEARCH_HOME;
  delete env.JUSTSEARCH_API_PORT;
  const child = spawn(process.execPath, [ENTRY, ...args], {
    cwd: dataDir,
    env,
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
      let message;
      try { message = JSON.parse(line); } catch { continue; }
      const waiter = pending.get(message.id);
      if (waiter) { pending.delete(message.id); clearTimeout(waiter.timer); waiter.resolve(message); }
    }
  });
  child.on('exit', (code, signal) => {
    for (const [id, waiter] of pending) {
      pending.delete(id); clearTimeout(waiter.timer);
      waiter.reject(new Error(`MCP server exited (${code ?? signal}); stderr=${stderr}`));
    }
  });
  const request = (method, params) => new Promise((resolve, reject) => {
    const id = nextId++;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`${method} timed out; stderr=${stderr}`)); }, 15_000);
    pending.set(id, { resolve, reject, timer });
    child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
  });
  const notify = (method, params) => child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method, params })}\n`);
  async function close() {
    if (child.exitCode !== null || child.signalCode !== null) return;
    const exited = once(child, 'exit');
    child.kill();
    await Promise.race([exited, new Promise((resolve) => setTimeout(resolve, 3000))]);
  }
  return { request, notify, close };
}

const INSTANCE = 'status-counts-test';

/**
 * A stand-in Engine whose /api/knowledge/status answers come from `next()`, with the runtime
 * manifest that names it in a temporary data folder.
 */
async function startEngine(next) {
  const server = http.createServer((request, response) => {
    const body = request.url === '/api/runtime/live' ? { alive: true, pid: process.pid, instanceId: INSTANCE }
      : request.url === '/api/health' || request.url === '/api/status' ? { status: 'ok' }
        : request.url === '/api/knowledge/status' ? next() : null;
    response.writeHead(body ? 200 : 404, { 'Content-Type': 'application/json' });
    response.end(body ? JSON.stringify(body) : undefined);
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const port = server.address().port;
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'mcp-status-counts-'));
  fs.mkdirSync(path.join(dataDir, 'runtime'));
  fs.writeFileSync(path.join(dataDir, 'runtime', 'manifest.json'), JSON.stringify({
    pid: process.pid, instanceId: INSTANCE, head: { apiPort: port, sessionToken: 'mock-session-token' } }));
  return { port, dataDir, close: async () => {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
    fs.rmSync(dataDir, { recursive: true, force: true });
  } };
}

const counted = (docs) => ({ state: 'READY', ready: true, healthy: true, indexState: 'IDLE', indexedDocuments: docs,
  pendingJobs: 0, embeddingCoveragePercent: 100, spladeCoveragePercent: 100, pendingNerCount: 0, completedNerCount: docs,
  indexedDocumentsUnknown: false });
// What the Engine answers while it has no Worker reading (KnowledgeSearchController's fallback),
// with the placeholder zeros an older reader would see.
const uncounted = { state: 'STARTING', ready: false, healthy: false, indexState: 'UNKNOWN', indexedDocuments: 0,
  pendingJobs: 0, embeddingCoveragePercent: 0, spladeCoveragePercent: 0, pendingNerCount: 0, completedNerCount: 0,
  statusStale: true, indexedDocumentsUnknown: true };
const WORKER_COUNTS = ['docCount', 'queueDepth', 'embeddingCoveragePercent', 'spladeCoveragePercent', 'pendingNerCount', 'completedNerCount'];

test('justsearch_status omits every Worker count while the Engine does not know them', async (t) => {
  const answers = [uncounted, counted(0), counted(7), uncounted];
  const engine = await startEngine(() => answers.shift());
  t.after(() => engine.close());
  const client = startMcp(['--port', String(engine.port)], engine.dataDir);
  t.after(() => client.close());
  const initialized = await client.request('initialize', {
    protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'test-prod-mcp-status-counts', version: '1' } });
  assert.equal(initialized.error, undefined, JSON.stringify(initialized.error));
  client.notify('notifications/initialized', {});
  const status = async () => {
    const result = (await client.request('tools/call', { name: 'justsearch_status', arguments: {} })).result;
    return result.structuredContent ?? JSON.parse(result.content?.[0]?.text ?? '{}');
  };

  const before = await status();
  assert.equal(before.ok, true, JSON.stringify(before));
  for (const key of WORKER_COUNTS) assert.equal(before[key], undefined, `${key} while unknown: ${JSON.stringify(before)}`);

  const empty = await status();
  assert.equal(empty.docCount, 0, 'a counted empty index is a known 0');
  assert.equal(empty.queueDepth, 0, 'a counted empty queue is a known 0');

  const seven = await status();
  assert.equal(seven.docCount, 7);
  assert.equal(seven.completedNerCount, 7);

  const again = await status();
  for (const key of WORKER_COUNTS) assert.equal(again[key], undefined, `${key} while unknown again: ${JSON.stringify(again)}`);
});
