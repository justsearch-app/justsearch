#!/usr/bin/env node
// Fresh production fixture under the desktop CSP. Run from the repo root; failures retain screenshots and selectors.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import { once } from 'node:events';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
// Vitest launches this as a child with NODE_ENV=test; this must still be a production UI.
process.env.NODE_ENV = 'production';
const root = path.resolve(process.env.JS_REPO_ROOT || process.cwd());
const evidence = path.resolve(process.argv[2] || 'tmp/type-ladder-chromium-evidence');
const ui = path.join(root, 'modules/ui-web');
const require = createRequire(path.join(ui, 'package.json'));
const { build } = await import(pathToFileURL(require.resolve('vite')));
const pw = await import(pathToFileURL(require.resolve('playwright-core'))); const chromium = pw.chromium ?? pw.default.chromium;
const here = path.dirname(fileURLToPath(import.meta.url));
await fs.mkdir(evidence, { recursive: true });
const scratch = await fs.mkdtemp(path.join(process.env.TMPDIR || os.tmpdir(), 'type-ladder-'));
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
  if (p === '/api/knowledge/search') return json(route, { results: [{ id: '/csp-root/note.md', score: 1, fields: { path: '/csp-root/note.md', file_kind: 'md', content_preview: 'Typography fixture result' } }], totalHits: 1, matchCount: 1 });
  if (p === '/api/preview') return json(route, { content: '# Fixture document\n\nTypography fixture document text.', textProvenance: 'original', truncated: false });
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

// This function executes in Chromium and traverses the composed app, including nested shadow roots.
function auditSurface() {
  const failures = [], samples = [], faces = [];
  let elements = 0, textElements = 0;
  const sizes = [11, 12, 13, 15, 20, 28], radii = [0, 4, 8, 14, 9999];
  const selector = el => {
    const step = el.localName + (el.id ? '#' + el.id : el.getAttribute('data-testid') ? `[data-testid="${el.getAttribute('data-testid')}"]` : `:nth-child(${[...el.parentNode.children].indexOf(el) + 1})`);
    const parent = el.parentElement || el.getRootNode().host;
    return parent ? selector(parent) + (el.parentElement ? ' > ' : ' >>> ') + step : step;
  };
  const add = (el, property, value, reason) => failures.push({ selector: selector(el), property, value, reason });
  const sheets = new Set();
  function sheet(s) {
    if (!s || sheets.has(s)) return; sheets.add(s);
    function rules(list) {
      for (const rule of list) {
        if (rule.type === CSSRule.FONT_FACE_RULE) faces.push({ family: rule.style.getPropertyValue('font-family').replace(/["']/g, '').trim(),
          src: rule.style.getPropertyValue('src'), style: rule.style.getPropertyValue('font-style') || 'normal', weight: rule.style.getPropertyValue('font-weight'), href: s.href || location.href });
        if (rule.styleSheet) sheet(rule.styleSheet);
        if (rule.cssRules) rules(rule.cssRules);
      }
    }
    try { rules(s.cssRules); } catch (e) { failures.push({ selector: 'stylesheet', property: 'font-face', value: s.href, reason: String(e) }); }
  }
  function walk(root) {
    for (const s of root.styleSheets || []) sheet(s);
    for (const s of root.adoptedStyleSheets || []) sheet(s);
    for (const el of root.children || []) {
      // Fixture navigation is outside every product surface and deliberately has no product CSS.
      if (el.hasAttribute('data-ladder-controls') || ['style', 'script', 'template', 'link', 'meta', 'head'].includes(el.localName)) continue;
      elements++;
      const style = getComputedStyle(el);
      for (const corner of ['borderTopLeftRadius', 'borderTopRightRadius', 'borderBottomLeftRadius', 'borderBottomRightRadius']) {
        const value = style[corner];
        for (const axis of value.split(/\s+/)) if (!(axis === '50%' || axis.endsWith('px') && radii.includes(parseFloat(axis)))) add(el, corner, value, 'corner axis outside ladder');
      }
      const rendered = el.getClientRects().length && style.visibility === 'visible';
      const text = [...el.childNodes].some(n => n.nodeType === Node.TEXT_NODE && n.textContent.trim())
        || (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) && (el.value || el.placeholder);
      if (rendered && text) {
        textElements++;
        if (!sizes.includes(parseFloat(style.fontSize))) add(el, 'font-size', style.fontSize, 'text outside ladder');
        if (['20px', '28px'].includes(style.fontSize) && style.fontOpticalSizing !== 'auto' && !/["']opsz["']\s+(20|28)/.test(style.fontVariationSettings)) add(el, 'font-optical-sizing', `${style.fontOpticalSizing}; ${style.fontVariationSettings}`, 'display text lacks optical sizing');
        samples.push({ selector: selector(el), tag: el.localName, fontSize: style.fontSize, family: style.fontFamily, weight: style.fontWeight, style: style.fontStyle, optical: style.fontOpticalSizing, variation: style.fontVariationSettings });
      }
      for (const pseudo of ['::before', '::after']) {
        const ps = getComputedStyle(el, pseudo);
        if (rendered && !['none', 'normal', '""', "''"].includes(ps.content) && !sizes.includes(parseFloat(ps.fontSize))) add(el, `font-size${pseudo}`, ps.fontSize, 'generated text outside ladder');
      }
      if (el.shadowRoot) walk(el.shadowRoot);
      walk(el);
    }
  }
  walk(document);
  const rootSize = getComputedStyle(document.documentElement).fontSize;
  if (rootSize !== '16px') add(document.documentElement, 'font-size', rootSize, 'root must remain 16px');
  return { failures, elements, textElements, samples, faces, fontFaces: [...document.fonts].map(f => ({ family: f.family.replace(/["']/g, ''), status: f.status, style: f.style, weight: f.weight })) };
}

async function settle(page) {
  await page.evaluate(async () => { await document.fonts.ready; await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))); });
  await page.waitForTimeout(250);
}
async function capture(page, theme, surface, ready) {
  const id = `${theme}/${surface}`;
  try {
    await ready(); await settle(page);
    const result = await page.evaluate(auditSurface);
    assert.ok(result.textElements > 0, 'surface has no rendered text');
    await page.screenshot({ path: path.join(evidence, `${theme}-${surface}.png`), fullPage: true });
    for (const failure of result.failures) failures.push({ surface: id, ...failure });
    checks.push({ id, result: result.failures.length ? 'fail' : 'pass', ...result });
    return result;
  } catch (error) {
    failures.push({ surface: id, selector: 'surface', value: String(error), reason: 'surface not reached' });
    checks.push({ id, result: 'unverified', error: String(error) });
    await page.screenshot({ path: path.join(evidence, `${theme}-${surface}-unverified.png`), fullPage: true }).catch(() => {});
    return null;
  }
}
const fontResponses = [], fontRequests = [], interUrls = new Set();
async function fontCheck(page, origin, theme, result) {
  const fail = (selector, value, reason) => failures.push({ surface: `${theme}/font`, selector, value, reason });
  const faces = result.faces.filter(f => f.family === 'JustSearch Inter');
  for (const style of ['normal', 'italic']) {
    if (!faces.some(f => f.style === style)) fail('@font-face', style, 'missing JustSearch Inter face');
  }
  for (const face of faces) {
    if (/local\(/i.test(face.src)) fail('@font-face src', face.src, 'local() would depend on installed fonts');
    const urls = [...face.src.matchAll(/url\(["']?([^"')]+)["']?\)/g)].map(m => new URL(m[1], face.href));
    if (!urls.length) fail('@font-face src', face.src, 'no file source');
    for (const url of urls) interUrls.add(url.href);
    for (const url of urls) if (url.origin !== origin || !url.pathname.startsWith('/fonts/')) fail('@font-face src', url.href, 'font must ship under public/fonts on the app origin');
  }
  // Inherit from actual inner prose; setting only weight/style must still select the app face.
  const probes = await page.locator('jf-markdown-block p').first().evaluate(async parent => {
    const out = [];
    for (const weight of [400, 500, 600]) for (const italic of [false, true]) {
      const el = document.createElement('span'); el.textContent = 'Hamburgefontsiv 0123456789 Wwmil';
      el.style.fontWeight = String(weight); el.style.fontStyle = italic ? 'italic' : 'normal';
      parent.append(el);
      const cs = getComputedStyle(el);
      await document.fonts.load(`${cs.fontStyle} ${weight} 28px "JustSearch Inter"`, el.textContent);
      await document.fonts.ready;
      const canvas = document.createElement('canvas'), ctx = canvas.getContext('2d');
      const prefix = `${cs.fontStyle} ${weight} 28px `;
      ctx.font = prefix + '"JustSearch Inter", monospace'; const inter = ctx.measureText(el.textContent).width;
      ctx.font = prefix + '"Missing type ladder font 72ab", monospace'; const missing = ctx.measureText(el.textContent).width;
      out.push({ weight, style: cs.fontStyle, family: cs.fontFamily, inter, missing }); el.remove();
    }
    return { probes: out, loaded: [...document.fonts].map(f => ({ family: f.family.replace(/["']/g, ''), status: f.status, style: f.style, weight: f.weight })) };
  });
  for (const p of probes.probes) {
    if (p.family.split(',')[0].replace(/["']/g, '').trim() !== 'JustSearch Inter') fail('jf-markdown-block p', p, 'inner text does not start with JustSearch Inter');
    if (Math.abs(p.inter - p.missing) < 0.1) fail('canvas font measurement', p, 'app font renders like deliberately missing family');
    if (!probes.loaded.some(f => f.family === 'JustSearch Inter' && f.status === 'loaded' && f.style === p.style)) fail('document.fonts', p, 'no loaded app FontFace for style');
  }
  checks.push({ id: `${theme}/font-probes`, ...probes, faces });
}

try {
  await build({ root: ui, define: { 'import.meta.env.VITE_JUSTSEARCH_API_PORT': 'undefined', 'import.meta.env.VITE_API_PORT': 'undefined' }, configFile: path.join(ui, 'vite.config.js'),
    plugins: [{ name: 'type-ladder-entry',
      transformIndexHtml: { order: 'pre', handler: html => html.replace('/src/main.jsx', '/@type-ladder-entry') },
      resolveId: id => id === '/@type-ladder-entry' ? '\0type-ladder-entry' : null,
      load: id => id === '\0type-ladder-entry' ? `import ${JSON.stringify(path.join(here, 'type-ladder-fixture.js'))};` : null,
    }], build: { outDir: dist, emptyOutDir: true },
  });
  const index = await fs.readFile(path.join(dist, 'index.html'), 'utf8');
  const noncedIndex = index.replace(/<(script|style)\b([^>]*)>/gi, (_m, tag, attrs) =>
    tag.toLowerCase() === 'script' && /\bsrc\s*=/.test(attrs) ? `<${tag}${attrs}>` : `<${tag} nonce="${nonce}"${attrs}>`);
  await fs.writeFile(path.join(evidence, 'policy.txt'), policy + '\n');
  server = http.createServer((req, res) => { void (async () => {
    const url = new URL(req.url, 'http://127.0.0.1');
    res.setHeader('Content-Security-Policy', policy);
    if (url.pathname === '/') { res.setHeader('Content-Type', 'text/html'); res.end(noncedIndex); return; }
    const file = path.resolve(dist, '.' + decodeURIComponent(url.pathname));
    if (!file.startsWith(dist + path.sep)) { res.writeHead(403).end(); return; }
    res.setHeader('Content-Type', file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : file.endsWith('.woff2') ? 'font/woff2' : 'application/octet-stream');
    res.end(await fs.readFile(file));
  })().catch(e => { res.writeHead(500); res.end(String(e)); }); });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const origin = `http://127.0.0.1:${server.address().port}`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: scratch };
  delete env.DISPLAY; delete env.WAYLAND_DISPLAY; delete env.XDG_SESSION_TYPE;
  browser = await chromium.launch({ headless: true, env, args: ['--disable-gpu', '--disable-extensions'] });
  for (const theme of ['dark', 'light']) {
    const context = await browser.newContext({ viewport: { width: 1024, height: 700 }, colorScheme: theme });
    await context.route('**/api/**', fixture);
    // Never access external services, even if a future font declaration points outside the app.
    await context.route('**/*', route => {
      if (new URL(route.request().url()).origin !== origin) { if (route.request().resourceType() === 'font') fontRequests.push(route.request().url()); return route.abort(); }
      return route.fallback();
    });
    const page = await context.newPage(); page.setDefaultTimeout(8000);
    const policyViolations = [];
    await page.addInitScript(() => {
      window.typeLadderPolicyViolations = [];
      document.addEventListener('securitypolicyviolation', e => window.typeLadderPolicyViolations.push({ directive: e.effectiveDirective, uri: e.blockedURI }));
    });
    page.on('request', req => { if (req.resourceType() === 'font') fontRequests.push(req.url()); });
    page.on('response', res => {
      if (res.request().resourceType() === 'font') fontResponses.push(res.body().then(body => ({ url: res.url(), status: res.status(), body })).catch(error => ({ url: res.url(), error: String(error) })));
    });
    await page.goto(`${origin}/?theme=${theme}`);
    await page.locator('body[data-type-ladder-ready="true"]').waitFor({ state: 'attached' });
    await page.evaluate(t => { document.documentElement.dataset.theme = t; }, theme);
    const click = name => page.locator('nav[data-ladder-controls]').getByRole('button', { name, exact: true }).click();
    const idle = await capture(page, theme, 'search-idle', () => page.locator('[data-testid="sv3-composer-headline"]').waitFor());
    if (idle) {
      const headline = await page.locator('[data-testid="sv3-composer-headline"]').evaluate(el => getComputedStyle(el).fontSize);
      if (headline !== '28px') failures.push({ surface: `${theme}/search-idle`, selector: 'jf-sv3-composer >>> [data-testid="sv3-composer-headline"]', value: headline, reason: 'page headline tier must be 28px' });
      const composer = await page.locator('jf-sv3-composer textarea[data-testid="sv3-composer-input"]').evaluate(el => getComputedStyle(el).fontSize);
      if (composer !== '15px') failures.push({ surface: `${theme}/search-idle`, selector: 'jf-sv3-composer >>> textarea[data-testid="sv3-composer-input"]', value: composer, reason: 'Search field must be 15px' });
    }
    await capture(page, theme, 'search-results', async () => {
      await page.locator('[data-testid="sv3-composer-input"]').fill('Typography');
      await page.locator('[data-testid="sv3-composer-input"]').press('Enter');
      await page.locator('[data-testid="search-result-row"]').first().waitFor();
    });
    await capture(page, theme, 'search-side-pane', async () => {
      await page.locator('[data-testid="search-result-row"]').first().click();
      await page.locator('jf-sv3-pane jf-document-pane').waitFor();
      await page.locator('jf-document-pane').getByText('Typography fixture document text.', { exact: false }).first().waitFor();
    });
    await capture(page, theme, 'library', async () => { await click('Library'); await page.locator('jf-library-surface h2').first().waitFor(); });
    await capture(page, theme, 'library-browse', async () => {
      await page.locator('jf-library-surface [data-tab-id="core.browse-surface"]').click();
      await page.locator('jf-browse-surface [role="treeitem"]').first().waitFor();
    });
    await capture(page, theme, 'ai-brain', async () => { await click('AI Brain'); await page.locator('jf-brain-surface .brain-headline').waitFor(); });
    await capture(page, theme, 'settings', async () => { await click('Settings'); await page.locator('jf-settings-surface h2').first().waitFor(); });
    await capture(page, theme, 'system-health', async () => { await click('System/Health'); await page.locator('jf-health-surface h2').first().waitFor(); });
    await capture(page, theme, 'status-panel', async () => { await click('Status panel'); await page.locator('jf-status-panel h2').waitFor(); });
    await capture(page, theme, 'confirm-dialog', async () => { await click('Confirm dialog'); await page.locator('jf-confirm-dialog dialog[open]').waitFor(); });
    // Close the real modal before using fixture navigation again; no destructive confirmation.
    await page.locator('jf-confirm-dialog').evaluate(el => { el.open = false; });
    await capture(page, theme, 'engine-recovery', async () => { await click('Engine recovery'); await page.locator('jf-engine-recovery h1').waitFor(); });
    for (const phase of ['requested', 'incomplete', 'newer-version', 'awaiting_storage', 'complete', 'error']) {
      await capture(page, theme, `factory-reset-${phase}`, async () => {
        await click('Factory reset');
        await page.locator('jf-factory-reset-recovery').evaluate((el, phase) => { el.status = { phase: phase === 'newer-version' ? 'incomplete' : phase, ...(phase === 'newer-version' ? { reason: 'newer_version' } : {}), remaining: phase === 'incomplete' ? [{ path: '/fixture/cache', error: 'Storage unavailable' }] : [] }; if (phase === 'error') { el.status = null; el.error = 'Unable to read the data reset status. Try again.'; } }, phase);
        await page.locator('jf-factory-reset-recovery h1').waitFor();
      });
    }
    const md = await capture(page, theme, 'markdown', async () => {
      await click('Markdown');
      for (const tag of ['h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'em', 'strong', 'code', 'pre code', 'table', '.cite-ref']) await page.locator(`jf-markdown-block ${tag}`).first().waitFor({ state: 'attached' });
    });
    if (md) await fontCheck(page, origin, theme, md);
    await capture(page, theme, 'connection-error', async () => { await click('Connection error'); await page.locator('#boot-failure-title').waitFor(); });
    policyViolations.push(...await page.evaluate(() => window.typeLadderPolicyViolations));
    checks.push({ id: `${theme}/policy-diagnostics`, policyViolations });
    for (const v of policyViolations.filter(v => /^(style-src|font-src)/.test(v.directive))) failures.push({ surface: `${theme}/policy`, selector: v.directive, value: v.uri, reason: 'CSP violation' });
    await context.close();
  }
  const fonts = await Promise.all(fontResponses);
  for (const url of fontRequests) if (new URL(url).origin !== origin) failures.push({ surface: 'fonts', selector: 'network', value: url, reason: 'external font request' });
  for (const [i, font] of fonts.entries()) {
    if (font.error || font.status !== 200) { failures.push({ surface: 'fonts', selector: 'network', value: font.url, reason: font.error || `HTTP ${font.status}` }); continue; }
    if (!interUrls.has(font.url)) continue;
    const file = path.join(scratch, `font-${i}`); await fs.writeFile(file, font.body);
    try {
      // Inspect the downloaded bytes, rather than trusting font-face descriptors or system fonts.
      const { stdout } = await promisify(execFile)('/usr/bin/python3', ['-c', "import json,sys; from fontTools.ttLib import TTFont; f=TTFont(sys.argv[1]); print(json.dumps({a.axisTag:[a.minValue,a.maxValue] for a in f['fvar'].axes} if 'fvar' in f else {}))", file], { env: { ...process.env, PYTHONPATH: '', PYTHONNOUSERSITE: '1' } });
      const axes = JSON.parse(stdout);
      checks.push({ id: 'font-file-axes', url: font.url, axes });
      if (!(axes.opsz?.[0] <= 20 && axes.opsz?.[1] >= 28 && axes.wght?.[0] <= 400 && axes.wght?.[1] >= 600)) failures.push({ surface: 'fonts', selector: font.url, value: axes, reason: 'file lacks display opsz or variable weight range' });
    } catch (error) { failures.push({ surface: 'fonts', selector: font.url, value: String(error), reason: 'font axes unverified (requires system python3 fontTools and brotli)' }); }
  }
  if (!fonts.some(f => interUrls.has(f.url))) failures.push({ surface: 'fonts', selector: 'network', value: 'no font responses', reason: 'no app font file loaded' });
  for (const f of failures) console.error(`${f.surface}: ${f.selector}: ${JSON.stringify(f.value)} (${f.reason})`);
  console.log(JSON.stringify({ chromium: browser.version(), surfaces: checks.filter(c => c.textElements).length, violations: failures.length, breakdown: Object.fromEntries([...new Set(failures.map(f => f.reason))].map(r => [r, failures.filter(f => f.reason === r).length])) }, null, 2));
  process.exitCode = failures.length ? 1 : 0;
} catch (error) {
  console.error(error); checks.push({ id: 'harness', result: 'unverified', error: String(error) }); process.exitCode = 2;
} finally {
  await fs.writeFile(path.join(evidence, 'results.json'), JSON.stringify({ policy, checks, failures, traffic, fontRequests }, null, 2));
  await browser?.close();
  if (server) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
  await fs.rm(scratch, { recursive: true, force: true });
}
