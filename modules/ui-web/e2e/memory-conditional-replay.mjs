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
import { chromium } from 'playwright-core';

const [engineRoot, frontendRoot, evidenceDir] = process.argv.slice(2).map(p => path.resolve(p));
await fs.mkdir(evidenceDir, { recursive: true });
const data = await fs.mkdtemp(path.join(process.env.TMPDIR || os.tmpdir(), 'memory-replay-'));
const traffic = [];
let engine, vite, proxy, browser, engineLog, proxyFailure;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function eventually(action, timeout = 90000) {
  const deadline = Date.now() + timeout;
  let error;
  do {
    if (proxyFailure) throw proxyFailure;
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
  const engineArgs = [
    '-Xmx768m', '--enable-native-access=ALL-UNNAMED',
    `-Djustsearch.data.dir=${data}`, `-Djustsearch.home=${data}`, `-Duser.home=${data}/home`,
    `-Djustsearch.api.port=${enginePort}`, '-Djustsearch.prod=true',
    '-Djustsearch.ai.disabled=true', '-Djustsearch.gpu.enabled=false',
    '-Djustsearch.mode=mcp-lite', '-cp', path.join(engineRoot, 'modules/ui/build/install/ui/lib/*'),
    'io.justsearch.ui.HeadlessApp',
  ];
  const engineOptions = { detached: true, cwd: engineRoot, stdio: ['ignore', engineLog.fd, engineLog.fd],
    env: { ...process.env, JUSTSEARCH_DATA_DIR: data, JUSTSEARCH_HOME: data,
      JUSTSEARCH_AI_DISABLED: 'true', JUSTSEARCH_GPU_ENABLED: 'false', JUSTSEARCH_AI_AUTOSTART_DISABLED: 'true' } };
  engine = spawn('/usr/lib/jvm/temurin-25-jdk-amd64/bin/java', engineArgs, engineOptions);
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
  const encryption = async (action, body = {}) => {
    const response = await fetch(engineBase + '/api/conversations/encryption/' + action,
      { method: 'POST', headers: auth, body: JSON.stringify(body) });
    assert.equal(response.status, 200, await response.clone().text());
    return response.json();
  };
  const passphrase = 'private-memory-test-passphrase';
  // F6 preservation runs before the repair probe, including on the unsafe starting commit.
  await call('', { method: 'POST', body: JSON.stringify({ id: 'same', content: 'first content' }) });
  await call('', { method: 'POST', body: JSON.stringify({ id: 'same', content: 'replacement content' }) });
  assert.equal((await call('')).memories[0].content, 'replacement content');
  const disk = JSON.parse(await fs.readFile(path.join(data, 'memories/memory.json'), 'utf8'));
  assert.equal(disk.schemaVersion, 1);
  assert.deepEqual(Object.keys(disk).sort(), ['memories', 'schemaVersion']);
  assert.deepEqual(Object.keys(disk.memories[0]).sort(),
    ['actor', 'content', 'createdAt', 'id', 'kind', 'sourceConversationId']);
  await encryption('setup', { passphrase });
  const preserved = (await call('')).memories[0];
  const preservedBackup = await encryption('export');
  await call('/same', { method: 'DELETE' });
  assert.equal((await call('')).memories.length, 0);
  const preservedImport = await encryption('import', { container: preservedBackup, passphrase });
  assert.equal(preservedImport.restored.memories, 1);
  const { revision: unusedBeforeRevision, ...preservedFields } = preserved;
  const { revision: unusedAfterRevision, ...restoredFields } = (await call('')).memories[0];
  assert.deepEqual(restoredFields, preservedFields);
  const repeatedImport = await encryption('import', { container: preservedBackup, passphrase });
  assert.equal(repeatedImport.restored.memories, 0);
  await call('', { method: 'POST', body: JSON.stringify({ id: 'same', content: 'newer than backup' }) });
  const skippedImport = await encryption('import', { container: preservedBackup, passphrase });
  assert.equal(skippedImport.restored.memories, 0);
  assert.equal((await call('')).memories[0].content, 'newer than backup');
  await call('/same', { method: 'DELETE' });
  assert.equal((await call('')).memories.length, 0);
  await fs.writeFile(path.join(evidenceDir, 'preservation.json'), JSON.stringify({ tokenDenied: denied.status,
    format: disk, preservedFields, restoredFields, importCount: preservedImport.restored.memories }, null, 2));
  console.log(JSON.stringify({ scenario: 'F6', case: 'token, remember, legacy DELETE, format, real backup import', result: 'pass' }));
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
  let afterDelete;
  let failMemoryReads = false;
  proxy = http.createServer((req, res) => {
    const isApi = req.url.startsWith('/api/');
    const entry = { method: req.method, path: req.url, ifMatch: req.headers['if-match'] ?? null,
      socket: req.socket.remotePort, at: new Date().toISOString() };
    if (isApi) traffic.push(entry);
    if (failMemoryReads && req.method === 'GET' && req.url === '/api/memory') {
      entry.status = 503;
      res.writeHead(503, { 'Content-Type': 'application/json' });
      res.end('{"error":"injected read failure"}');
      return;
    }
    const upstream = http.request({ host: '127.0.0.1', port: isApi ? enginePort : vitePort,
      path: req.url, method: req.method,
      headers: { ...req.headers, host: `127.0.0.1:${isApi ? enginePort : vitePort}` } }, response => { void (async () => {
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
      if (req.method === 'DELETE' && req.url === '/api/memory/same' && afterDelete) {
        const chunks = []; for await (const chunk of response) chunks.push(chunk);
        entry.completedBody = Buffer.concat(chunks).toString();
        await afterDelete(entry);
        res.writeHead(response.statusCode, response.headers); res.end(Buffer.concat(chunks));
        return;
      }
      res.writeHead(response.statusCode, response.headers); response.pipe(res);
    })().catch(error => { proxyFailure = error; res.destroy(error); }); });
    upstream.on('error', e => { if (!res.destroyed) res.destroy(e); });
    req.pipe(upstream);
  });
  proxy.keepAliveTimeout = 60000;
  proxy.listen(0, '127.0.0.1'); await once(proxy, 'listening');
  const origin = `http://127.0.0.1:${proxy.address().port}`;
  browser = await chromium.launch({ headless: true, args: ['--disable-extensions'] });
  const page = await browser.newPage({ extraHTTPHeaders: { 'X-JustSearch-Session': manifest.head.sessionToken } });
  // Load the actual application and use its navigation, member tabs and confirmation dialog.
  await page.goto(origin + '?api_port=' + proxy.address().port);
  await page.locator('jf-shell').waitFor();
  await page.evaluate(() => document.dispatchEvent(new CustomEvent('jf-navigate-to-surface', {
    detail: { surfaceId: 'core.brain-surface' },
  })));
  await page.getByRole('tab', { name: 'Memory', exact: true }).click();
  await page.locator('jf-memory-surface').getByRole('button', { name: 'Forget: original memory', exact: true }).click();
  await page.evaluate(() => fetch('/api/memory'));
  await page.locator('jf-confirm-dialog').getByRole('button', { name: 'Forget', exact: true }).click();
  await eventually(async () => {
    const deletes = traffic.filter(e => e.method === 'DELETE' && e.path === '/api/memory/same');
    assert.equal(deletes.length, 2, 'Chromium itself must resend the completed DELETE');
    assert.ok(deletes[1].status);
    return deletes;
  });
  await eventually(async () => assert.equal(await page.locator('jf-memory-surface').evaluate(el => el.busy), false));
  const rows = await call('');
  const deletes = traffic.filter(e => e.method === 'DELETE' && e.path === '/api/memory/same');
  const ui = await page.locator('jf-memory-surface').evaluate(el => el.shadowRoot.textContent);
  await fs.writeFile(path.join(evidenceDir, 'result.json'), JSON.stringify({ data, enginePid: engine.pid,
    chromium: browser.version(), traffic, rows, ui }, null, 2));
  assert.equal(deletes[0].status, 200);
  assert.equal(deletes[1].status, 412, 'F3: replay must refuse the replacement');
  assert.equal(deletes[0].ifMatch, deletes[1].ifMatch);
  assert.match(deletes[0].ifMatch, /^"[0-9a-f]{64}"$/);
  assert.notEqual(deletes[0].socket, deletes[1].socket, 'the resend uses a new connection');
  assert.ok(traffic.slice(0, traffic.indexOf(deletes[0])).some(entry => entry.socket === deletes[0].socket),
    'the completed DELETE must have used a previously used connection');
  assert.equal(rows.memories[0]?.content, 'replacement memory');
  assert.doesNotMatch(ui, /Forgotten/);
  await page.locator('jf-memory-surface').getByText('The current item was kept;', { exact: false }).waitFor();
  console.log(JSON.stringify({ scenario: 'F3', result: 'pass', deletes, data }));
  const navigation = async id => page.evaluate(target => document.dispatchEvent(
    new CustomEvent('jf-navigate-to-surface', { detail: { surfaceId: target } }),
  ), id);
  const openMemory = async surface => {
    await navigation('core.search-v3-surface');
    await page.locator('jf-sv3-window').waitFor();
    if (surface === 'Brain') {
      await navigation('core.brain-surface');
      await page.getByRole('tab', { name: 'Memory', exact: true }).click();
    } else {
      const palette = page.locator('jf-sv3-palette');
      await palette.evaluate(el => el.show());
      await palette.locator('input').fill('memory');
      await palette.locator('input').press('Enter');
    }
    await page.locator('jf-memory-surface').waitFor();
    await eventually(async () => assert.equal(await page.locator('jf-memory-surface').evaluate(el => el.loading), false));
  };
  const readUi = () => page.locator('jf-memory-surface').evaluate(el => ({
    message: el.mutationError, announcement: el.announcement,
    text: el.shadowRoot.textContent, locked: el.locked, loadError: el.loadError, busy: el.busy,
  }));
  const surfaceResults = [];
  for (const surface of ['Brain', 'Search v3']) {
    for (const scenario of ['removed', 'absent', 'conflict', 'refresh failure', 'locked', 'second replacement', 'navigation', 'double activation']) {
      failMemoryReads = false; afterDelete = undefined;
      await call('/same', { method: 'DELETE' });
      await call('', { method: 'POST', body: JSON.stringify({ id: 'same', content: 'original memory' }) });
      await openMemory(surface);
      const start = traffic.length;
      await page.locator('jf-memory-surface').getByRole('button', { name: 'Forget: original memory', exact: true }).click();
      if (scenario === 'absent') await call('/same', { method: 'DELETE' });
      if (['conflict', 'refresh failure', 'locked', 'second replacement'].includes(scenario)) {
        await call('', { method: 'POST', body: JSON.stringify({ id: 'same', content: 'replacement memory' }) });
      }
      let release, deletion;
      const started = entry => { deletion = entry; };
      const held = new Promise(resolve => { release = resolve; });
      afterDelete = async entry => {
        if (scenario === 'refresh failure') failMemoryReads = true;
        if (scenario === 'locked') await encryption('lock');
        if (scenario === 'second replacement') {
          await call('', { method: 'POST', body: JSON.stringify({ id: 'same', content: 'second replacement memory' }) });
        }
        started(entry);
        if (['navigation', 'double activation'].includes(scenario)) await held;
      };
      if (scenario === 'double activation') {
        await page.locator('jf-memory-surface').evaluate(el => {
          const button = el.shadowRoot.querySelector('.list jf-button');
          void button.onActivate(); void button.onActivate();
        });
        assert.equal(await page.locator('jf-confirm-dialog[open]').count(), 1);
      }
      await page.locator('jf-confirm-dialog[open]').getByRole('button', { name: 'Forget', exact: true }).click();
      await eventually(() => assert.ok(deletion, 'the conditional DELETE must reach the Engine'));
      if (scenario === 'double activation') {
        await page.locator('jf-memory-surface').evaluate(el => {
          const button = el.shadowRoot.querySelector('.list jf-button');
          void button.onActivate(); void button.onActivate();
        });
        release();
      }
      if (scenario === 'navigation') {
        await navigation('core.search-v3-surface');
        await page.locator('jf-sv3-window').waitFor();
        release();
        await openMemory(surface);
      }
      await eventually(async () => assert.equal((await readUi()).busy, false));
      const state = await readUi();
      const deletes = traffic.slice(start).filter(e => e.method === 'DELETE' && e.path === '/api/memory/same');
      assert.equal(deletes.length, 1, surface + ' / ' + scenario);
      if (scenario === 'removed' || scenario === 'double activation') {
        assert.equal(state.announcement, 'Forgotten.');
        assert.equal((await call('')).memories.length, 0);
      } else if (scenario === 'absent') {
        assert.match(state.announcement, /already gone/);
        assert.doesNotMatch(state.announcement, /Forgotten/);
      } else if (scenario === 'navigation') {
        assert.equal(state.message, ''); assert.equal(state.announcement, '');
      } else {
        assert.equal(deletion.status, 412);
        assert.match(state.message, /The current item was kept; the earlier attempt may have completed/);
        assert.doesNotMatch(state.announcement, /Forgotten/);
        if (scenario === 'refresh failure') {
          assert.ok(state.loadError); assert.doesNotMatch(state.text, /original memory/);
        } else if (scenario === 'locked') {
          assert.equal(state.locked, true); assert.doesNotMatch(state.text, /original memory/);
          await encryption('unlock', { passphrase });
        } else assert.match(state.text, scenario === 'second replacement' ? /second replacement memory/ : /replacement memory/);
        assert.equal((await call('')).memories[0].content,
          scenario === 'second replacement' ? 'second replacement memory' : 'replacement memory');
      }
      surfaceResults.push({ surface, scenario, state, deletes });
      console.log(JSON.stringify({ scenario: 'F4', surface, case: scenario, result: 'pass' }));
    }
  }
  failMemoryReads = false; afterDelete = undefined;
  // F6: the real backup-import sink restores all original fields but creates a new incarnation.
  await call('/same', { method: 'DELETE' });
  await call('', { method: 'POST', body: JSON.stringify({ id: 'same', content: 'backup memory',
    kind: 'preference', actor: 'chat', sourceConversationId: 'source-conversation' }) });
  const exportedRow = (await call('')).memories[0];
  const backup = await encryption('export');
  await call('/same', { method: 'DELETE' });
  const restored = await encryption('import', { container: backup, passphrase });
  assert.equal(restored.restored.memories, 1);
  const importedRow = (await call('')).memories[0];
  const { revision: oldRevision, ...originalFields } = exportedRow;
  const { revision: importedRevision, ...importedFields } = importedRow;
  assert.deepEqual(importedFields, originalFields);
  assert.notEqual(importedRevision, oldRevision);
  const replayAfterImport = await fetch(engineBase + '/api/memory/same', {
    method: 'DELETE', headers: { ...auth, 'If-Match': oldRevision },
  });
  assert.equal(replayAfterImport.status, 412);
  assert.deepEqual((await call('')).memories[0], importedRow);
  await fs.writeFile(path.join(evidenceDir, 'surfaces.json'), JSON.stringify({ surfaceResults, backupImport: {
    originalFields, importedFields, oldRevision, importedRevision, replayStatus: replayAfterImport.status,
  } }, null, 2));
  console.log(JSON.stringify({ scenario: 'F5', case: 'identical backup restoration has a new revision', result: 'pass' }));
  await browser.close(); browser = undefined;
  process.kill(-engine.pid, 'SIGTERM'); await once(engine, 'exit');
  engine = spawn('/usr/lib/jvm/temurin-25-jdk-amd64/bin/java', engineArgs, engineOptions);
  const restarted = await eventually(async () => {
    assert.equal(engine.exitCode, null, 'Engine restart failed');
    const value = JSON.parse(await fs.readFile(path.join(data, 'runtime/manifest.json'), 'utf8'));
    assert.notEqual(value.instanceId, manifest.instanceId);
    assert.equal(value.pid, engine.pid);
    assert.ok(value.head?.sessionToken, 'the restarted Engine must publish its new token before use');
    assert.equal(value.head.apiPort, enginePort);
    return value;
  });
  auth['X-JustSearch-Session'] = restarted.head.sessionToken;
  await eventually(() => encryption('unlock', { passphrase }));
  const restartedRow = (await call('')).memories[0];
  assert.notEqual(restartedRow.revision, importedRevision);
  const staleAfterRestart = await fetch(engineBase + '/api/memory/same', {
    method: 'DELETE', headers: { ...auth, 'If-Match': importedRevision },
  });
  assert.equal(staleAfterRestart.status, 412);
  assert.deepEqual((await call('')).memories[0], restartedRow);
  await fs.writeFile(path.join(evidenceDir, 'restart.json'), JSON.stringify({ importedRevision,
    restartedRow, staleStatus: staleAfterRestart.status }, null, 2));
  console.log(JSON.stringify({ scenario: 'F5', case: 'Engine restart refuses the old revision', result: 'pass' }));

} finally {
  await fs.writeFile(path.join(evidenceDir, 'traffic.json'), JSON.stringify({ traffic,
    proxyFailure: proxyFailure?.stack }, null, 2));
  if (browser) await browser.close();
  if (proxy) { proxy.closeAllConnections(); await new Promise(resolve => proxy.close(resolve)); }
  if (vite) await vite.close();
  if (engine && engine.exitCode === null && engine.signalCode === null) {
    process.kill(-engine.pid, 'SIGTERM');
    await Promise.race([once(engine, 'exit'), sleep(10000)]);
    if (engine.exitCode === null && engine.signalCode === null) { process.kill(-engine.pid, 'SIGKILL'); await once(engine, 'exit'); }
  }
  await engineLog?.close();
}
