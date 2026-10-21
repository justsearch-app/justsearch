// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { spawn } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));

test('T15: sandbox pending-approval stream uses the proven manifest credential', { skip: process.platform !== 'linux' }, async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'sandbox-stream-auth-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const token = 'private-sandbox-credential';
  const instanceId = 'sandbox-stream-instance';
  const requests = [];
  const server = http.createServer((req, res) => {
    requests.push({ path: req.url, token: req.headers['x-justsearch-session'] });
    if (req.url === '/api/advisory/authorization-pending/stream') {
      if (req.headers['x-justsearch-session'] !== token) { res.writeHead(401); res.end('{}'); return; }
      res.writeHead(200, { 'Content-Type': 'text/event-stream' });
      res.end('event: frame\ndata: ' + JSON.stringify({ payload: { classExtras: { operationId: 'core.ingest-files', pendingId: 'owned-pending' } } }) + '\n\n');
      return;
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(req.url === '/api/runtime/live' ? { alive: true, pid: process.pid, instanceId } : { status: 'ok' }));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const data = path.join(root, 'io.justsearch.shell');
  await fs.mkdir(path.join(data, 'runtime'), { recursive: true });
  await fs.writeFile(path.join(data, 'runtime/manifest.json'), JSON.stringify({
    schemaVersion: 2, pid: process.pid, instanceId, lifecycle: 'LIFECYCLE_STATE_READY',
    head: { apiPort: server.address().port, sessionToken: token },
  }));
  const bridge = path.join(root, 'index.cjs');
  await fs.copyFile(path.resolve(here, '../../packaging/mcpb/server/engine-discovery.cjs'), path.join(root, 'engine-discovery.cjs'));
  await fs.writeFile(bridge, `require('node:readline').createInterface({input:process.stdin}).on('line', line => {
    const m=JSON.parse(line);
    if(m.id) process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:m.id,result:m.method==='initialize'?{protocolVersion:'2025-06-18'}:{isError:true,content:[{type:'text',text:'requires your approval (gate: TYPED_CONFIRM)'}]}})+'\\n');
  });`);
  const env = { ...process.env, APPDATA: root, XDG_DATA_HOME: root, JUSTSEARCH_DATA_DIR: data };
  delete env.JUSTSEARCH_API_PORT; delete env.JUSTSEARCH_SESSION_TOKEN;
  const child = spawn(process.execPath, [path.join(here, 'mcp-typed-confirm.mjs'), '--bridge', bridge, '--target', '/fixture', '--timeout', '3'], { env });
  t.after(() => { if (child.exitCode === null && child.signalCode === null) child.kill(); });
  let output = '', errors = '';
  child.stdout.on('data', bytes => { output += bytes; });
  child.stderr.on('data', bytes => { errors += bytes; });
  const exit = await new Promise((resolve, reject) => { child.once('error', reject); child.once('exit', resolve); });
  assert.equal(exit, 0, errors);
  assert.match(output, /PENDING_ID=owned-pending/);
  assert.ok(requests.some(row => row.path === '/api/advisory/authorization-pending/stream' && row.token === token));
  assert.ok(requests.filter(row => row.path !== '/api/advisory/authorization-pending/stream').every(row => !row.token), 'discovery probes stay unauthenticated');
});
