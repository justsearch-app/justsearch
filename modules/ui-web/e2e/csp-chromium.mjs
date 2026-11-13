#!/usr/bin/env node
// Serves a production build under the desktop window policy (tauri.conf.json plus a nonce, as Tauri delivers it) in headless Chromium and checks per-element styles and runtime sheets apply. Run from the repository root; run by src/boot/csp-styles-lockdown.test.ts.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import { once } from 'node:events';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
const root = path.resolve(process.env.JS_REPO_ROOT || process.cwd());
const evidence = path.resolve(process.argv[2] || 'tmp/csp-chromium-evidence');
const ui = path.join(root, 'modules/ui-web');
const require = createRequire(path.join(ui, 'package.json'));
const { build } = await import(pathToFileURL(require.resolve('vite')));
const pw = await import(pathToFileURL(require.resolve('playwright-core'))); const chromium = pw.chromium ?? pw.default.chromium;
const here = path.dirname(fileURLToPath(import.meta.url));
await fs.mkdir(evidence, { recursive: true });
const scratch = await fs.mkdtemp(path.join(process.env.TMPDIR || os.tmpdir(), 'csp-check-'));
const dist = path.join(scratch, 'dist');
let server, browser;
const checks = [];
const failures = [];
const traffic = [];
const nonce = 'anVzdHNlYXJjaC1jc3AtdmVyaWZpZXI=';
const config = JSON.parse(await fs.readFile(path.join(root, 'modules/shell/src-tauri/tauri.conf.json'), 'utf8'));
assert.ok(!config.app.security.dangerousDisableAssetCspModification, 'Tauri CSP modification must remain enabled');
const originalPolicy = config.app.security.csp;
assert.equal(typeof originalPolicy, 'string', 'Update the harness explicitly if CSP becomes a map');
const policy = originalPolicy.split(';').map(piece => {
  const p = piece.trim();
  return /^(style-src|script-src)\s/.test(p) ? `${p} 'nonce-${nonce}'` : p;
}).join('; ');
assert.match(policy, /style-src[^;]*'nonce-/);
assert.match(policy, /script-src[^;]*'nonce-/);
const json = async (route, body) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
async function fixture(route) {
  const req = route.request(), url = new URL(req.url());
  traffic.push({ method: req.method(), path: url.pathname, body: req.postData() });
  const p = url.pathname;
  if (p === '/api/indexing/roots') return json(route, { roots: [{ path: '/csp-root', fileCount: 2 }] });
  if (p === '/api/knowledge/folders') {
    const parent = req.postDataJSON().parentPath;
    return json(route, { folders: parent === '/csp-root' ? [{ path: '/csp-root/child', childFolderCount: 0, childFileCount: 1 }] : [] });
  }
  if (p === '/api/knowledge/folder-files') {
    const folder = req.postDataJSON().folderPath;
    return json(route, { files: [{ docId: folder + '/note.txt', fields: { path: folder + '/note.txt', filename: 'note.txt', file_kind: 'txt', size_bytes: '123' } }] });
  }
  if (p === '/api/ai/install/status') return json(route, { state: 'running', phase: 'download', installedFully: false,
    downloadedBytes: 450, totalBytes: 1000,
    packages: [{ packageId: 'chat', label: 'Chat model', state: 'downloading', bytesDownloaded: 450, bytesTotal: 1000 }] });
  if (p === '/api/ai/runtime/status') return json(route, { installed: false, mode: 'offline', active: { gpuLayers: 0 }, onnxFeatures: [] });
  if (p === '/api/ai/packs/status') return json(route, { state: 'idle', packs: [] });
  if (p === '/api/status' || p === '/api/system/status') return json(route, { worker: { core: { indexState: 'IDLE', indexedDocuments: 2, indexHealthy: true }, migration: { migrationState: 'IDLE' } }, readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } });
  if (p === '/api/settings/v2') return json(route, { ui: {}, ai: { enabled: true } });
  if (p === '/api/ai/install/plan-preview') return json(route, { totalDownloadBytes: 1000, components: [{ id: 'chat', label: 'Chat model', state: 'to-download', downloadBytes: 1000 }] });
  if (p === '/api/ai/install/downloaded-files') return json(route, { parts: [] });
  if (p === '/api/indexing-roots/substrate') return json(route, { items: [] });
  if (p === '/api/inference/status') return json(route, { mode: 'offline', installed: false });
  return json(route, { entries: [], items: [], results: [], facets: {}, jobs: [], count: 0 });
}
async function check(id, run) {
  try { const detail = await run(); checks.push({ id, result: 'pass', detail }); }
  catch (error) { checks.push({ id, result: 'fail', error: String(error) }); failures.push(id); }
}
try {
  // A fresh production build from this checkout. Never reuse its ignored dist directory.
  await build({ root: ui, configFile: path.join(ui, 'vite.config.js'),
    plugins: [{ name: 'verifier-csp-entry',
      transformIndexHtml: { order: 'pre', handler: html => html.replace('/src/main.jsx', '/@csp-fixture-entry') },
      resolveId: id => id === '/@csp-fixture-entry' ? '\0csp-fixture-entry' : null,
      load: id => id === '\0csp-fixture-entry' ? fs.readFile(path.join(here, 'csp-fixture.js'), 'utf8') : null,
    }], build: { outDir: dist, emptyOutDir: true },
  });
  const index = await fs.readFile(path.join(dist, 'index.html'), 'utf8');
  const noncedIndex = index.replace(/<(script|style)\b([^>]*)>/gi, (_m, tag, attrs) =>
    tag.toLowerCase() === 'script' && /\bsrc\s*=/.test(attrs) ? `<${tag}${attrs}>` : `<${tag} nonce="${nonce}"${attrs}>`);
  await fs.writeFile(path.join(evidence, 'policy.txt'), policy + '\n');
  await fs.writeFile(path.join(evidence, 'served-index.html'), noncedIndex);
  if (process.argv.includes('--build-only')) { console.log('PASS fresh fixture build; browser not run'); }
  else {
    const canary = `<!doctype html><style nonce="${nonce}">#probe{width:200px;height:10px}</style><div id="probe"></div>`;
    server = http.createServer((req, res) => { void (async () => {
      const url = new URL(req.url, 'http://127.0.0.1');
      if (!url.searchParams.has('no-csp')) res.setHeader('Content-Security-Policy', policy);
      const html = url.pathname === '/' || url.pathname === '/canary';
      if (html) { res.setHeader('Content-Type', 'text/html'); res.end(url.pathname === '/canary' ? canary : noncedIndex); return; }
      const file = path.resolve(dist, '.' + decodeURIComponent(url.pathname));
      if (!file.startsWith(dist + path.sep)) { res.writeHead(403).end(); return; }
      res.setHeader('Content-Type', file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'application/octet-stream');
      res.end(await fs.readFile(file));
    })().catch(e => { res.writeHead(500); res.end(String(e)); }); });
    server.listen(0, '127.0.0.1'); await once(server, 'listening');
    const origin = `http://127.0.0.1:${server.address().port}`;
    const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: scratch };
    delete env.DISPLAY; delete env.WAYLAND_DISPLAY; delete env.XDG_SESSION_TYPE;
    browser = await chromium.launch({ headless: true, env, args: ['--disable-gpu', '--disable-extensions'] });
    const canaryContext = await browser.newContext(); const canaryPage = await canaryContext.newPage();
    await canaryPage.goto(origin + '/canary');
    await check('P1-policy-canary', async () => {
      const result = await canaryPage.evaluate(() => {
        const el = document.getElementById('probe'); const before = el.getBoundingClientRect().width;
        el.setAttribute('style', 'width:50px'); return { before, after: el.getBoundingClientRect().width };
      });
      assert.deepEqual(result, { before: 200, after: 200 }); return result;
    });
    await canaryContext.close();
    for (const constrained of [false, true]) {
      const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
      await context.route('**/api/**', fixture);
      const page = await context.newPage(); page.setDefaultTimeout(12000);
      const consoleRefusals = [], pageErrors = [];
      page.on('console', message => { if (/violat|refused|content.security.policy|applying inline style/i.test(message.text())) consoleRefusals.push(message.text()); });
      page.on('pageerror', error => pageErrors.push(String(error)));
      await page.addInitScript(() => {
        window.cspViolations = [];
        document.addEventListener('securitypolicyviolation', e => window.cspViolations.push({
          effectiveDirective: e.effectiveDirective, blockedURI: e.blockedURI, sourceFile: e.sourceFile, lineNumber: e.lineNumber, sample: e.sample,
        }));
      });
      const prefix = constrained ? 'policy' : 'browser-control';
      await page.goto(origin + (constrained ? '/' : '/?no-csp'));
      await page.locator('body[data-csp-ready="true"]').waitFor({ state: 'attached' });
      await check(prefix + '-S2-nested-browse', async () => {
        await page.locator('jf-library-surface [data-tab-id="core.browse-surface"]').click();
        const browse = page.locator('jf-browse-surface');
        const parent = browse.locator('[role="treeitem"]').filter({ has: page.locator('.name', { hasText: /^csp-root$/ }) });
        await parent.click();
        const child = browse.locator('[role="treeitem"]').filter({ has: page.locator('.name', { hasText: /^child$/ }) });
        await child.waitFor();
        const padding = async el => el.evaluate(e => parseFloat(getComputedStyle(e).paddingLeft));
        const result = { parent: await padding(parent), child: await padding(child) };
        assert.ok(result.child > result.parent, JSON.stringify(result));
        // Added case: collapse and reopen the same branch; the indentation must remain.
        await parent.click(); await parent.click();
        assert.ok(await padding(child) > await padding(parent)); return result;
      });
      await check(prefix + '-S1-download-45', async () => {
        await page.getByRole('button', { name: 'AI Brain', exact: true }).click();
        const brain = page.locator('jf-brain-surface');
        await brain.locator('.brain-headline').filter({ hasText: '45%' }).waitFor();
        const bar = brain.locator('[data-testid="brain-simple-panel"] .progress-bar').first();
        await bar.waitFor();
        const ratio = await bar.evaluate(el => el.getBoundingClientRect().width / el.parentElement.getBoundingClientRect().width);
        assert.ok(Math.abs(ratio - 0.45) < 0.015, `45% bar rendered as ${(ratio * 100).toFixed(2)}%`); return { ratio };
      });
      await check(prefix + '-S5-theme-selected', async () => {
        await page.getByRole('button', { name: 'Settings', exact: true }).click();
        const tile = page.locator('jf-settings-surface button[aria-label^="CSP proof:"]');
        await tile.click();
        await page.waitForFunction(() => document.querySelector('jf-settings-surface')?.shadowRoot.querySelector('button[aria-label^="CSP proof:"]')?.getAttribute('aria-pressed') === 'true');
        // The chosen theme must also take effect, not only show as chosen.
        await page.waitForFunction(() => getComputedStyle(document.documentElement).getPropertyValue('--h-teal').trim() === '120', null, { timeout: 5000 });
        return { selected: await tile.getAttribute('aria-pressed') };
      });
      await page.screenshot({ path: path.join(evidence, prefix + '.png'), fullPage: true });
      const violations = await page.evaluate(() => window.cspViolations);
      await fs.writeFile(path.join(evidence, prefix + '-diagnostics.json'), JSON.stringify({ violations, consoleRefusals, pageErrors }, null, 2));
      await check(prefix + '-S6-no-style-violations', async () => {
        assert.deepEqual(violations.filter(v => v.effectiveDirective.startsWith('style-src')), []);
        assert.deepEqual(consoleRefusals.filter(s => /style/i.test(s)), []); assert.deepEqual(pageErrors, []);
      });
      await context.close();
    }
    console.log(JSON.stringify({ chromium: browser.version(), policy, checks }, null, 2));
    if (failures.length) process.exitCode = 1;
  }
} catch (error) {
  console.error(error); checks.push({ id: 'harness', result: 'unverified', error: String(error) }); process.exitCode = 2;
} finally {
  await fs.writeFile(path.join(evidence, 'results.json'), JSON.stringify({ policy, checks, traffic }, null, 2));
  await browser?.close();
  if (server) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
  await fs.rm(scratch, { recursive: true, force: true });
}
