import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { once } from 'node:events';
import { test } from 'node:test';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

test('MCP state readers use the runner state override, including outside the repository', async () => {
  const root = path.resolve(import.meta.dirname, '../..');
  // Windows CI TEMP may be an 8.3 alias; the file reader checks canonical containment.
  const state = fs.realpathSync.native(fs.mkdtempSync(path.join(os.tmpdir(), 'MCP state space ü ')));
  const runId = 'state-path-test';
  const runDir = path.join(state, 'runs', runId);
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ marker: 'configured-state-root', indexAvailable: true }));
  });
  const client = new Client({ name: 'state-path-test', version: '1' });
  try {
    server.listen(0, '127.0.0.1'); await once(server, 'listening');
    const port = server.address().port;
    fs.mkdirSync(path.join(runDir, 'logs'), { recursive: true });
    fs.writeFileSync(path.join(state, 'active.json'), JSON.stringify({ runId }));
    fs.writeFileSync(path.join(runDir, 'run.json'), JSON.stringify({ runId, apiPortActual: port,
      apiBaseUrl: `http://127.0.0.1:${port}`, dataDir: path.join(state, 'data'), pids: { backendRootPid: process.pid } }));
    fs.writeFileSync(path.join(runDir, 'logs/backend.stdout.log'), 'configured state log\n');
    await client.connect(new StdioClientTransport({ command: process.execPath,
      args: [path.join(root, 'scripts/dev/justsearch-dev-mcp.mjs')], cwd: root,
      env: { ...process.env, JUSTSEARCH_DEV_RUNNER_STATE_ROOT: state, JUSTSEARCH_DEV_MCP_LOG_NDJSON: '0' }, stderr: 'ignore' }));
    const call = async (name, args = {}) => {
      const value = await client.callTool({ name: `justsearch.dev.${name}`, arguments: args });
      return value.structuredContent ?? JSON.parse(value.content[0].text);
    };
    const health = await call('quick_health', { probe: true });
    assert.equal(health.runState, 'ACTIVE'); assert.equal(health.runId, runId);
    assert.equal(health.running, true); assert.equal(health.httpReady, true); assert.equal(health.workerReady, true);
    const log = await call('tail_log', { kind: 'backend_stdout' });
    assert.equal(log.ok, true); assert.equal(log.text, 'configured state log\n');
    const status = await call('fetch_api_json', { endpoint: 'status' });
    assert.equal(status.ok, true); assert.equal(status.runId, runId);
  } finally {
    await client.close();
    await new Promise(resolve => server.close(resolve));
    fs.rmSync(state, { recursive: true, force: true });
  }
});
