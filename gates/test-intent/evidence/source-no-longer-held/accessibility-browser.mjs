// Run: node accessibility-browser.mjs <checkout-root> <report.json>
// Loads the production Search v3 bundle from an owned vite preview. Only the
// document fixture and presence HTTP response are stubbed; no Engine is used.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const [root, output] = process.argv.slice(2);
const ui = path.join(root, 'modules/ui-web');
const { chromium } = await import(pathToFileURL(path.join(ui, 'node_modules/playwright-core/index.mjs')));
const manifest = JSON.parse(fs.readFileSync(path.join(ui, 'dist/.vite/manifest.json'), 'utf8'));
const entry = manifest['src/shell-v0/views/search-v3/SearchV3View.ts'].file;
const tmp = fs.mkdtempSync('/tmp/jsv-');
const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: tmp, VITE_JUSTSEARCH_API_PORT: '1' };
delete env.DISPLAY;
delete env.WAYLAND_DISPLAY;
const report = { startedUtc: new Date().toISOString(), root, entry, checks: [], requests: [] };
const preview = spawn(process.execPath, [path.join(ui, 'node_modules/vite/bin/vite.js'), 'preview',
  '--configLoader', 'runner', '--host', '127.0.0.1', '--port', '0', '--strictPort'], { cwd: ui, env, stdio: ['ignore', 'pipe', 'pipe'] });
report.previewPid = preview.pid;
let log = '';
preview.stdout.on('data', data => { log += data; });
preview.stderr.on('data', data => { log += data; });
const closed = new Promise(resolve => preview.once('exit', (code, signal) => resolve({ code, signal })));
let context;
try {
  const origin = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { clearInterval(poll); reject(new Error('vite preview startup timed out: ' + log)); }, 15000);
    const poll = setInterval(() => {
      const match = log.match(/http:\/\/127\.0\.0\.1:\d+/);
      if (match) { clearTimeout(timeout); clearInterval(poll); resolve(match[0]); }
    }, 25);
    preview.once('error', reject);
    closed.then(() => { clearTimeout(timeout); clearInterval(poll); reject(new Error('vite preview exited: ' + log)); });
  });
  report.origin = origin;
  context = await chromium.launchPersistentContext(path.join(tmp, 'profile'), {
    headless: true, env, args: ['--no-sandbox', '--disable-dev-shm-usage'], viewport: { width: 1100, height: 820 },
  });
  const page = await context.newPage();
  let release;
  const reply = new Promise(resolve => { release = resolve; });
  await context.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin !== origin) return route.abort();
    if (url.pathname === '/api/documents/presence') {
      const { docIds } = route.request().postDataJSON();
      report.requests.push(docIds);
      await reply;
      return route.fulfill({ json: { states: Object.fromEntries(docIds.map(id => [id, path.basename(id, '.md')])) } });
    }
    if (url.pathname.startsWith('/api/')) return route.fulfill({ status: 503, json: {} });
    if (url.pathname === '/') return route.fulfill({ contentType: 'text/html', body:
      `<!doctype html><html><body><script type="module" src="/${entry}"></script></body></html>` });
    return route.continue();
  });
  await page.goto(origin);
  await page.evaluate(async () => {
    await customElements.whenDefined('jf-sv3-main');
    const main = document.createElement('jf-sv3-main');
    main.conversationId = 'wp58-accessibility';
    main.apiBase = location.origin;
    main.turns = ['ask', 'agent'].map(kind => ({
      id: kind, recordId: kind + '-question', assistantRecordId: kind + '-answer', recordOpenedByUser: false,
      kind, question: kind + ' question', answer: kind + ' answer', status: 'complete',
      evidence: { sources: ['present', 'unknown', 'absent'].map(state => ({
        parentDocId: '/docs/' + state + '.md', chunkIndex: 0, startLine: 0, endLine: 1,
        excerpt: state + ' recorded passage', headingText: '',
      })), matches: [], marks: [], retrievalMode: '' },
      detail: '', toolCalls: 0, activity: [], askedAt: 1, standaloneQuestion: '', reasoning: [],
      durationMs: null, modelLabel: null, disposition: null,
    }));
    document.body.append(main);
    await main.updateComplete;
  });
  const disclosures = page.locator('[data-testid="sv3-turn-sources"]');
  assert.equal(await disclosures.count(), 2);
  await disclosures.nth(0).click();
  await disclosures.nth(1).click();
  await page.waitForFunction(() => document.querySelector('jf-sv3-main').shadowRoot.querySelectorAll('jf-citations-panel').length === 2);
  const cdp = await context.newCDPSession(page);
  const selector = (panel, source) => `document.querySelector('jf-sv3-main').shadowRoot.querySelectorAll('jf-citations-panel')[${panel}].shadowRoot.querySelectorAll('button.source')[${source}]`;
  async function accessible(panel, source) {
    const { result } = await cdp.send('Runtime.evaluate', { expression: selector(panel, source) });
    const { nodes } = await cdp.send('Accessibility.getPartialAXTree', { objectId: result.objectId, fetchRelatives: false });
    await cdp.send('Runtime.releaseObject', { objectId: result.objectId });
    const button = nodes.find(node => node.role?.value === 'button');
    assert(button && !button.ignored, 'real button must be in the Chromium accessibility tree');
    return { name: button.name?.value ?? '', description: button.description?.value ?? '',
      focused: button.properties?.some(property => property.name === 'focused' && property.value.value === true) ?? false };
  }
  async function focusSource(panel, source) {
    const button = page.locator('jf-citations-panel').nth(panel).locator('button.source').nth(source);
    await button.focus();
    await page.keyboard.press('Shift+Tab');
    await page.keyboard.press('Tab');
  }
  const initial = [];
  for (let panel = 0; panel < 2; panel++) {
    initial[panel] = [];
    for (let source = 0; source < 3; source++) {
      await focusSource(panel, source);
      initial[panel][source] = await accessible(panel, source);
    }
  }
  await page.evaluate(() => {
    window.statusChanges = [];
    for (const panel of document.querySelector('jf-sv3-main').shadowRoot.querySelectorAll('jf-citations-panel')) {
      for (const status of panel.shadowRoot.querySelectorAll('[role="status"]')) {
        new MutationObserver(() => {
          if (status.textContent.trim()) window.statusChanges.push(status.textContent.trim());
        }).observe(status, { childList: true, subtree: true, characterData: true });
      }
    }
  });
  release();
  await page.waitForFunction(() => window.statusChanges.length === 2);
  for (let panel = 0; panel < 2; panel++) {
    for (let source = 0; source < 3; source++) {
      // Tab onto each source button through the browser's keyboard focus order.
      await focusSource(panel, source);
      const actual = await accessible(panel, source);
      assert(actual.focused, 'source button must receive keyboard focus');
      assert.equal(await page.locator('jf-citations-panel').nth(panel).getByRole('button', { name: actual.name, exact: true }).count(), 1);
      const state = ['present', 'unknown', 'absent'][source];
      const passed = state === 'absent' ? /No longer available/.test(actual.name + ' ' + actual.description)
        : actual.name === initial[panel][source].name && actual.description === initial[panel][source].description;
      report.checks.push({ mode: ['Ask', 'Delegate'][panel], state, initial: initial[panel][source], actual, passed });
    }
  }
  await page.evaluate(async () => {
    for (const panel of document.querySelector('jf-sv3-main').shadowRoot.querySelectorAll('jf-citations-panel')) {
      panel.requestUpdate();
      await panel.updateComplete;
    }
  });
  report.statusChanges = await page.evaluate(() => window.statusChanges);
  assert.deepEqual(report.statusChanges, ['No longer available', 'No longer available']);
  for (const status of await page.locator('.source-availability').all()) {
    assert.equal(await status.getAttribute('role'), 'status');
    assert.equal(await status.getAttribute('aria-live'), 'polite');
  }
  assert.equal(report.requests.length, 2);
  assert(report.checks.every(check => check.passed), 'absent source must expose No longer available in its computed accessible name or description; present and unknown must stay unchanged');
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.error = String(error.stack ?? error);
  process.exitCode = 1;
} finally {
  if (context) await context.close();
  preview.kill('SIGTERM');
  report.previewExit = await closed;
  fs.rmSync(tmp, { recursive: true, force: true });
  report.temporaryResourcesRemoved = !fs.existsSync(tmp);
  report.finishedUtc = new Date().toISOString();
  fs.writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
}
