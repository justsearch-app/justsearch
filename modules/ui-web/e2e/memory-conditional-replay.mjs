// SPDX-License-Identifier: Apache-2.0
// node modules/ui-web/e2e/memory-conditional-replay.mjs <engine checkout> <frontend checkout> <evidence dir>
// Uses a private Engine and a fresh headless Chromium profile; no browser plugins or desktop session.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createRequire } from 'node:module';
import { createServer } from 'vite';
import { chromium } from 'playwright-core';

const [engineRoot, frontendRoot, evidenceDir] = process.argv.slice(2).map(p => path.resolve(p));
await fs.mkdir(evidenceDir, { recursive: true });
const data = await fs.mkdtemp(path.join(process.env.TMPDIR || os.tmpdir(), 'memory-replay-'));
const traffic = [];
let engine, vite, proxy, browser, engineLog;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function eventually(action, timeout = 90000) {
  const deadline = Date.now() + timeout;
  let error;
  do {
    try { return await action(); } catch (e) { error = e; }
    await sleep(100);
  } while (Date.now() < deadline);
  throw error;
}
try {
  const portProbe = http.createServer();
  portProbe.listen(0, '127.0.0.1'); await once(portProbe, 'listening');
  const enginePort = portProbe.address().port;
  await new Promise(resolve => portProbe.close(resolve));
  engineLog = await fs.open(path.join(evidenceDir, 'engine.log'), 'w');
  engine = spawn('/usr/lib/jvm/temurin-25-jdk-amd64/bin/java', [
    '-Xmx768m', '--enable-native-access=ALL-UNNAMED',
    `-Djustsearch.data.dir=${data}`, `-Djustsearch.home=${data}`,
    `-Djustsearch.api.port=${enginePort}`, '-Djustsearch.prod=true',
    '-Djustsearch.ai.disabled=true', '-Djustsearch.gpu.enabled=false',
    '-Djustsearch.mode=mcp-lite', '-cp', path.join(engineRoot, 'modules/ui/build/install/ui/lib/*'),
    'io.justsearch.ui.HeadlessApp',
  ], { detached: true, cwd: engineRoot, stdio: ['ignore', engineLog.fd, engineLog.fd],
    env: { ...process.env, JUSTSEARCH_DATA_DIR: data, JUSTSEARCH_HOME: data,
      JUSTSEARCH_AI_DISABLED: 'true', JUSTSEARCH_GPU_ENABLED: 'false', JUSTSEARCH_AI_AUTOSTART_DISABLED: 'true' } });
  const manifest = await eventually(async () => {
    assert.equal(engine.exitCode, null, 'Engine exited before readiness');
    const value = JSON.parse(await fs.readFile(path.join(data, 'runtime/manifest.json'), 'utf8'));
    assert.ok(value.head?.sessionToken, 'private production manifest must supply a token');
    return value;
  });
  const engineBase = `http://127.0.0.1:${enginePort}`;
  const auth = { 'X-JustSearch-Session': manifest.head.sessionToken, 'Content-Type': 'application/json' };
  const call = async (suffix, init = {}) => {
    const response = await fetch(engineBase + '/api/memory' + suffix, { ...init, headers: { ...auth, ...init.headers } });
    assert.equal(response.status, 200, await response.clone().text());
    return response.json();
  };
  await eventually(() => call(''));
  const denied = await fetch(engineBase + '/api/memory/same', { method: 'DELETE' });
  assert.equal(denied.status, 401, 'F6: DELETE still requires the API token');
  await call('', { method: 'POST', body: JSON.stringify({ id: 'same', content: 'original memory' }) });

  process.env.VITE_API_PORT = String(enginePort);
  // Load the selected tree's Vite implementation and configuration without sharing dependency writes.
  const requireFrontend = createRequire(path.join(frontendRoot, 'modules/ui-web/package.json'));
  const { createServer: startVite } = await import(requireFrontend.resolve('vite'));
  vite = await startVite({ root: path.join(frontendRoot, 'modules/ui-web'),
    configFile: path.join(frontendRoot, 'modules/ui-web/vite.config.js'),
    server: { host: '127.0.0.1', port: 0, hmr: false } });
  await vite.listen();
  const vitePort = vite.httpServer.address().port;
  let reset = true;
  proxy = http.createServer((req, res) => {
    const isApi = req.url.startsWith('/api/');
    const entry = { method: req.method, path: req.url, ifMatch: req.headers['if-match'] ?? null,
      socket: req.socket.remotePort, at: new Date().toISOString() };
    if (isApi) traffic.push(entry);
    const upstream = http.request({ host: '127.0.0.1', port: isApi ? enginePort : vitePort,
      path: req.url, method: req.method,
      headers: { ...req.headers, host: `127.0.0.1:${isApi ? enginePort : vitePort}` } }, async response => {
      if (isApi) entry.status = response.statusCode;
      if (req.method === 'DELETE' && req.url === '/api/memory/same' && reset) {
        reset = false;
        const chunks = []; for await (const chunk of response) chunks.push(chunk);
        entry.completedBody = Buffer.concat(chunks).toString();
        // Wait until the real Engine completed DELETE, then replace the id before losing headers.
        await call('', { method: 'POST', body: JSON.stringify({ id: 'same', content: 'replacement memory' }) });
        entry.replacementInserted = true;
        entry.resetAfterCompletion = true;
        req.socket.resetAndDestroy();
        return;
      }
      res.writeHead(response.statusCode, response.headers); response.pipe(res);
    });
    upstream.on('error', e => { if (!res.destroyed) res.destroy(e); });
    req.pipe(upstream);
  });
  proxy.keepAliveTimeout = 60000;
  proxy.listen(0, '127.0.0.1'); await once(proxy, 'listening');
  const origin = `http://127.0.0.1:${proxy.address().port}`;
  const candidates = (await fs.readdir(path.join(os.homedir(), '.cache/ms-playwright')))
    .filter(name => name.startsWith('chromium-')).sort().reverse();
  const executablePath = path.join(os.homedir(), '.cache/ms-playwright', candidates[0], 'chrome-linux64/chrome');
  browser = await chromium.launch({ executablePath, headless: true, args: ['--disable-extensions'] });
  const page = await browser.newPage({ extraHTTPHeaders: { 'X-JustSearch-Session': manifest.head.sessionToken } });
  // Load the actual application and use its navigation, member tabs and confirmation dialog.
  await page.goto(origin);
  await page.evaluate(async () => {
    const { requestSurfaceNavigation } = await import('/src/shell-v0/controllers/navigateRequest.ts');
    requestSurfaceNavigation('core.brain-surface');
  });
  await page.getByRole('tab', { name: 'Memory', exact: true }).click();
  await page.locator('jf-memory-surface').getByRole('button', { name: 'Forget: original memory', exact: true }).click();
  await page.locator('jf-confirm-dialog').getByRole('button', { name: 'Forget', exact: true }).click();
  await eventually(async () => {
    const deletes = traffic.filter(e => e.method === 'DELETE' && e.path === '/api/memory/same');
    assert.equal(deletes.length, 2, 'Chromium itself must resend the completed DELETE');
    assert.ok(deletes[1].status);
    return deletes;
  });
  const rows = await call('');
  const deletes = traffic.filter(e => e.method === 'DELETE' && e.path === '/api/memory/same');
  const ui = await page.locator('jf-memory-surface').innerText();
  await fs.writeFile(path.join(evidenceDir, 'result.json'), JSON.stringify({ data, enginePid: engine.pid,
    chromium: browser.version(), traffic, rows, ui }, null, 2));
  assert.equal(deletes[0].status, 200);
  assert.equal(deletes[1].status, 412, 'F3: replay must refuse the replacement');
  assert.equal(deletes[0].ifMatch, deletes[1].ifMatch);
  assert.match(deletes[0].ifMatch, /^"[0-9a-f]{64}"$/);
  assert.notEqual(deletes[0].socket, deletes[1].socket, 'the resend uses a new connection');
  assert.equal(rows.memories[0]?.content, 'replacement memory');
  assert.doesNotMatch(ui, /Forgotten/);
  await page.locator('jf-memory-surface').getByText('The current item was kept;', { exact: false }).waitFor();
  console.log(JSON.stringify({ scenario: 'F3', result: 'pass', deletes, data }));
} finally {
  if (browser) await browser.close();
  if (proxy) { proxy.closeAllConnections(); await new Promise(resolve => proxy.close(resolve)); }
  if (vite) await vite.close();
  if (engine && engine.exitCode === null) {
    process.kill(-engine.pid, 'SIGTERM');
    await Promise.race([once(engine, 'exit'), sleep(10000)]);
    if (engine.exitCode === null) { process.kill(-engine.pid, 'SIGKILL'); await once(engine, 'exit'); }
  }
  await engineLog?.close();
}
