#!/usr/bin/env node
/**
 * Smoke test for the MCPB stdio bridge (packaging/mcpb/server/index.js).
 * Dependency-free scripted MCP client over stdio.
 *
 * Success path (JustSearch app must be running):
 *   node packaging/mcpb/test/smoke.mjs
 *   -> initialize, notifications/initialized, tools/list (expects the 7
 *      justsearch_* tools), tools/call justsearch_status. Exit 0 on pass.
 *
 * Failure path (JustSearch app must NOT be running):
 *   node packaging/mcpb/test/smoke.mjs --expect-unreachable
 *   -> asserts the bridge exits non-zero with the actionable
 *      "install and launch JustSearch" stderr message.
 */

import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const bridgePath = path.join(here, '..', 'server', 'index.js');
const expectUnreachable = process.argv.includes('--expect-unreachable');

// McpContractVersions TOOL_SURFACE_VERSION 0.9.0 / McpToolSurface.listTools.
const EXPECTED_TOOLS = [
  'justsearch_answer',
  'justsearch_search',
  'justsearch_browse',
  'justsearch_ingest',
  'justsearch_status',
  'justsearch_runtime_manifest',
  'justsearch_operation_outcome',
];

function fail(msg) {
  console.error(`SMOKE FAIL: ${msg}`);
  process.exit(1);
}

const bridge = spawn(process.execPath, [bridgePath], {
  stdio: ['pipe', 'pipe', 'pipe'],
});

let stderrText = '';
bridge.stderr.on('data', (d) => {
  stderrText += d;
  process.stderr.write(`  [bridge stderr] ${d}`);
});

// ---------------------------------------------------------------------------
// Failure-path mode: just observe exit code + stderr.
// ---------------------------------------------------------------------------
if (expectUnreachable) {
  bridge.on('exit', (code) => {
    if (code === 0) fail('bridge exited 0 although JustSearch should be unreachable');
    const needles = ['Could not reach a running JustSearch', 'releases', 'Launch JustSearch'];
    for (const needle of needles) {
      if (!stderrText.includes(needle)) {
        fail(`stderr missing expected guidance snippet: "${needle}"\n---\n${stderrText}`);
      }
    }
    console.log(`SMOKE PASS (failure path): exit code ${code}, actionable stderr present.`);
    process.exit(0);
  });
  setTimeout(() => fail('bridge did not exit within 20s'), 20000).unref();
} else {
  runSuccessPath().catch((e) => fail(e.stack || String(e)));
}

// ---------------------------------------------------------------------------
// Success-path mode: scripted MCP session.
// ---------------------------------------------------------------------------
async function runSuccessPath() {
  const pending = new Map(); // id -> resolve
  let buf = '';
  bridge.stdout.on('data', (chunk) => {
    buf += chunk;
    let nl;
    while ((nl = buf.indexOf('\n')) !== -1) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      if (!line) continue;
      let msg;
      try {
        msg = JSON.parse(line);
      } catch {
        fail(`bridge emitted non-JSON stdout line: ${line.slice(0, 300)}`);
      }
      if (msg.id !== undefined && pending.has(msg.id)) {
        pending.get(msg.id)(msg);
        pending.delete(msg.id);
      }
      // notifications from the server are fine; ignore.
    }
  });
  bridge.on('exit', (code) => {
    if (pending.size > 0) fail(`bridge exited (code ${code}) with requests in flight`);
  });

  let nextId = 0;
  const request = (method, params, timeoutMs = 60000) =>
    new Promise((resolve, reject) => {
      const id = ++nextId;
      pending.set(id, resolve);
      bridge.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
      setTimeout(() => {
        if (pending.has(id)) {
          pending.delete(id);
          reject(new Error(`timeout waiting for ${method} response`));
        }
      }, timeoutMs).unref();
    });
  const notify = (method, params) =>
    bridge.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method, params })}\n`);

  // 1. initialize
  const init = await request('initialize', {
    protocolVersion: '2025-06-18',
    capabilities: {},
    clientInfo: { name: 'justsearch-mcpb-smoke', version: '0.0.1' },
  });
  if (!init.result || !init.result.serverInfo) {
    fail(`initialize: unexpected response ${JSON.stringify(init)}`);
  }
  console.log(
    `1. initialize OK: server=${init.result.serverInfo.name}@${init.result.serverInfo.version} protocol=${init.result.protocolVersion}`
  );
  notify('notifications/initialized');

  // 2. tools/list
  const list = await request('tools/list', {});
  if (!list.result || !Array.isArray(list.result.tools)) {
    fail(`tools/list: unexpected response ${JSON.stringify(list).slice(0, 500)}`);
  }
  const names = list.result.tools.map((t) => t.name).sort();
  const missing = EXPECTED_TOOLS.filter((t) => !names.includes(t));
  if (names.length !== EXPECTED_TOOLS.length || missing.length > 0) {
    fail(`tools/list: expected the ${EXPECTED_TOOLS.length} justsearch tools, got [${names.join(', ')}]`);
  }
  console.log(`2. tools/list OK: ${EXPECTED_TOOLS.length} tools [${names.join(', ')}]`);

  // 3. tools/call justsearch_status
  const status = await request('tools/call', { name: 'justsearch_status', arguments: {} });
  if (!status.result || status.result.isError) {
    fail(`justsearch_status: unexpected response ${JSON.stringify(status).slice(0, 500)}`);
  }
  const text = (status.result.content || [])
    .filter((c) => c.type === 'text')
    .map((c) => c.text)
    .join('\n');
  console.log(`3. justsearch_status OK: ${text.slice(0, 200).replace(/\n/g, ' | ')}${text.length > 200 ? '...' : ''}`);

  console.log('SMOKE PASS (success path).');
  bridge.stdin.end();
  process.exit(0);
}
