#!/usr/bin/env node
//
// Tempdoc 844 §12.2 — the honesty fixes AS THE AGENT REACHES THEM: over stdio, through the real
// tool handlers, not through the exported helpers.
//
// scripts/dev/test-dev-mcp-surface-honesty.mjs proves the pure functions. This file proves the
// wiring — a green helper with an unwired handler is exactly the `audit-without-test` failure the
// contributing docs name. It spawns the MCP server, points the read tools at a throwaway loopback
// HTTP server via the `apiPort` escape hatch, and asserts on the tool results.
//
// Starts NO dev stack: `tools/list` and the read tools touch no run state, and `quick_health` is
// called with probe:false / detail:"summary", which spawns no subprocess and issues no HTTP.
//
// Run: node scripts/dev/test-dev-mcp-projection-live.mjs
//

import assert from 'node:assert/strict';
import http from 'node:http';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const SERVER_ENTRY = path.join('scripts', 'dev', 'justsearch-dev-mcp.mjs');

/* ── a throwaway backend, big enough to truncate ───────────────────────────────────────────── */

const FAT_BODY = {
  worker: { compatibility: { embeddingCompatState: 'COMPATIBLE' } },
  items: Array.from({ length: 40 }, (_, i) => ({ name: `item-${i}`, blob: 'x'.repeat(120) })),
  marker: 'CANARY-BODY-STRING',
};

function startFakeBackend() {
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(FAT_BODY));
    });
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
  });
}

/* ── a minimal MCP stdio client ────────────────────────────────────────────────────────────── */

function startMcpClient({ timeoutMs = 40_000 } = {}) {
  const child = spawn(process.execPath, [SERVER_ENTRY], {
    cwd: REPO_ROOT, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true,
  });
  const pending = new Map();
  let nextId = 1;
  let buf = '';
  let stderrTail = '';

  child.stderr.on('data', (c) => { stderrTail = (stderrTail + c.toString('utf8')).slice(-4000); });
  child.stdout.on('data', (chunk) => {
    buf += chunk.toString('utf8');
    let i;
    while ((i = buf.indexOf('\n')) !== -1) {
      const line = buf.slice(0, i).trim();
      buf = buf.slice(i + 1);
      if (!line) continue;
      let msg;
      try { msg = JSON.parse(line); } catch { continue; }
      const waiter = pending.get(msg.id);
      if (waiter) { pending.delete(msg.id); waiter(msg); }
    }
  });

  const call = (method, params) => new Promise((resolve, reject) => {
    const id = nextId++;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`${method} timed out. stderr=${stderrTail}`)); }, timeoutMs);
    pending.set(id, (msg) => { clearTimeout(timer); resolve(msg); });
    child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
  });

  return {
    child,
    call,
    notify: (method, params) => child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method, params })}\n`),
    close: () => { try { child.kill(); } catch { /* already gone */ } },
  };
}

/** Call a tool and return its structuredContent (the JSON payload the agent sees). */
async function callTool(client, name, args) {
  const msg = await client.call('tools/call', { name, arguments: args });
  if (msg.error) throw new Error(`${name} failed: ${JSON.stringify(msg.error)}`);
  const sc = msg.result?.structuredContent;
  return sc ?? JSON.parse(msg.result?.content?.[0]?.text ?? '{}');
}

/* ── run ───────────────────────────────────────────────────────────────────────────────────── */

const { server, port } = await startFakeBackend();
const client = startMcpClient();
let pass = 0;
const failures = [];
const check = (label, fn) => {
  try { fn(); console.log(`  PASS  ${label}`); pass += 1; }
  catch (e) { console.error(`  FAIL  ${label}: ${e.message}`); failures.push(label); }
};

try {
  await client.call('initialize', {
    protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'test-dev-mcp-projection-live', version: '1' },
  });
  client.notify('notifications/initialized', {});

  /* --- the advertised surface --- */
  const listed = await client.call('tools/list', {});
  const tools = listed.result?.tools ?? [];
  const byName = Object.fromEntries(tools.map((t) => [t.name, t]));

  check('the server registers 12 tools (unchanged by this lane)', () => assert.equal(tools.length, 12));
  check('preflight advertises distFrom (tempdoc 844 B1)', () =>
    assert.ok(byName['justsearch.dev.preflight']?.inputSchema?.properties?.distFrom, 'distFrom missing from preflight inputSchema'));
  check('api_call advertises jsonPath (tempdoc 844 B4c)', () =>
    assert.ok(byName['justsearch.dev.api_call']?.inputSchema?.properties?.jsonPath, 'jsonPath missing from api_call inputSchema'));
  check('quick_health output declares foreignRuns (tempdoc 844 B3)', () =>
    assert.ok(byName['justsearch.dev.quick_health']?.description?.includes('foreignRuns')));

  /* --- B4a: a jsonPath miss returns a hint, not the payload --- */
  const miss = await callTool(client, 'justsearch.dev.fetch_api_json', { apiPort: port, endpoint: 'status', jsonPath: 'wroker.compatibility' });
  check('fetch_api_json jsonPath miss → JSON_PATH_MISS', () => assert.equal(miss.error?.code, 'JSON_PATH_MISS'));
  check('…names the keys that ARE available', () => {
    assert.ok(Array.isArray(miss.jsonPathAvailable?.keys), 'jsonPathAvailable.keys missing');
    assert.deepEqual(miss.jsonPathAvailable.keys, ['worker', 'items', 'marker']);
  });
  check('…and does NOT dump the response body', () => {
    assert.equal(miss.textTail, undefined, 'textTail must be withheld on a jsonPath miss');
    assert.ok(!JSON.stringify(miss).includes('CANARY-BODY-STRING'), 'the body leaked into a jsonPath-miss result');
  });

  const nested = await callTool(client, 'justsearch.dev.fetch_api_json', { apiPort: port, endpoint: 'status', jsonPath: 'worker.compat' });
  check('a nested miss names the deepest level that resolved', () => {
    assert.equal(nested.error?.code, 'JSON_PATH_MISS');
    assert.deepEqual(nested.jsonPathAvailable?.keys, ['compatibility']);
  });

  const indexed = await callTool(client, 'justsearch.dev.fetch_api_json', { apiPort: port, endpoint: 'status', jsonPath: 'items[2].name' });
  check('array indexing resolves through the real handler', () => {
    assert.equal(indexed.ok, true);
    assert.equal(indexed.json, 'item-2');
  });

  /* --- B4b: maxBytes truncates with an unmissable notice --- */
  const cut = await callTool(client, 'justsearch.dev.fetch_api_json', { apiPort: port, endpoint: 'status', maxBytes: 500, outputMode: 'full' });
  check('a small maxBytes truncates instead of failing the call', () => {
    assert.equal(cut.truncated, true, 'truncated flag missing');
    assert.equal(cut.statusCode, 200, 'the HTTP call itself succeeded');
    assert.equal(cut.bytesRead, 500);
    assert.equal(cut.maxBytesLimit, 500);
  });
  check('…and says so explicitly rather than returning a bare textTail', () => {
    assert.equal(cut.error?.code, 'RESPONSE_TRUNCATED');
    assert.match(cut.error.message, /TRUNCATED/);
    assert.ok(typeof cut.textTail === 'string' && cut.textTail.length > 0, 'the partial body the caller budgeted for should still come back');
  });

  /* --- B4c: api_call shares the projection --- */
  const viaApiCall = await callTool(client, 'justsearch.dev.api_call', { apiPort: port, path: '/api/knowledge/status', jsonPath: 'items[0].name' });
  check('api_call accepts jsonPath and projects with it', () => {
    assert.equal(viaApiCall.ok, true);
    assert.equal(viaApiCall.json, 'item-0');
  });
  const apiCallMiss = await callTool(client, 'justsearch.dev.api_call', { apiPort: port, path: '/api/knowledge/status', jsonPath: 'nope' });
  check('api_call jsonPath miss behaves identically (one implementation)', () => {
    assert.equal(apiCallMiss.error?.code, 'JSON_PATH_MISS');
    assert.deepEqual(apiCallMiss.jsonPathAvailable?.keys, ['worker', 'items', 'marker']);
    assert.ok(!JSON.stringify(apiCallMiss).includes('CANARY-BODY-STRING'));
  });

  /* --- B1: preflight checks the tree start will launch from --- */
  // Resolution failures return BEFORE any check runs, so this asserts the wiring without touching
  // ports, models, or an active run.
  const badDist = await callTool(client, 'justsearch.dev.preflight', { distFrom: 'no-such-worktree-844' });
  check('preflight { distFrom } refuses an unknown worktree and does not pretend the checks ran', () => {
    assert.equal(badDist.ok, false);
    assert.equal(badDist.error?.code, 'INVALID_DIST_FROM');
    assert.equal(badDist.checksRun, false);
    assert.equal(badDist.checks, undefined, 'checks must be absent, not reported as failed');
    assert.equal(badDist.ready, undefined);
  });
  check('…and the refusal reports the available-worktree inventory honestly', () => {
    assert.match(
      badDist.error.message,
      /(?:Worktrees that DO exist: .+|No worktrees exist under \.claude\/worktrees\.)/,
    );
  });

  // The positive case: a real sibling worktree, checked instead of the invoking checkout. Only the
  // dist-path assertions are made — a neighbour's running stack legitimately makes `ready` false.
  const selfName = path.basename(REPO_ROOT);
  const isWorktree = REPO_ROOT.replace(/\\/g, '/').includes('/.claude/worktrees/');
  if (isWorktree) {
    const pf = await callTool(client, 'justsearch.dev.preflight', { distFrom: selfName });
    check('preflight { distFrom: "<worktree name>" } resolves the name and reports the root it checked', () => {
      assert.equal(pf.distFromResolvedVia, 'worktree-name');
      assert.equal(path.resolve(pf.distCheckedRoot), path.resolve(REPO_ROOT));
    });
    // Lane F stage A item A13: this used to read both `details.workerDist` and `details.headDist`.
    // The Worker distribution and its preflight check are gone, so the same property — the dist
    // check names a path under the root it reported checking — is asserted on the one that remains.
    check('…and the dist check names paths under THAT root, not the invoking checkout by accident', () => {
      const shown = `${pf.details.headDist}`;
      assert.ok(shown.includes(path.join(REPO_ROOT, 'modules', 'ui')), `headDist did not name the checked root: ${pf.details.headDist}`);
    });
  } else {
    console.log('  SKIP  preflight { distFrom: "<worktree name>" } — not running inside a worktree');
  }

  /* --- B3: "did not look" is reported as null, not as an empty finding --- */
  const qhNoProbe = await callTool(client, 'justsearch.dev.quick_health', { probe: false });
  check('quick_health { probe:false } → foreignRuns is null, NOT []', () => {
    assert.ok('foreignRuns' in qhNoProbe, 'foreignRuns must always be present');
    assert.equal(qhNoProbe.foreignRuns, null);
    assert.equal(qhNoProbe.foreignRunsNotice, undefined);
  });
} finally {
  client.close();
  await new Promise((r) => server.close(r));
}

if (failures.length > 0) {
  console.error(`test-dev-mcp-projection-live: FAIL (${failures.length})`);
  process.exit(1);
}
console.log(`test-dev-mcp-projection-live: OK (${pass} assertions)`);
