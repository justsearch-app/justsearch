// SPDX-License-Identifier: Apache-2.0
// SB2, SB3, SB10, SB12–SB15: real Shell and Search in private Chromium, no shared stack.
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
const json = body => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
const params = new URLSearchParams(location.search);
let roots = params.has('unknown') ? null : params.has('reading') ? 1 : 0;
let help = 5;
let count = params.has('unknown') ? null : params.has('reading') ? 2 : 0, pending = params.has('reading') ? 8 : 0;
const root = { pathHash: 'notes', collection: 'default', status: 'indexing', inFlightCount: 8, failedCount: 0, walkCompleted: false };
window.fetch = async (input) => {
  const url = String(input?.url ?? input);
  if (url.includes('/api/indexing-roots/substrate')) return roots === null ? new Response('', { status: 503 }) : json({ items: roots ? [root] : [], count: roots });
  if (url.includes('/api/indexing/suggested-roots')) return json({ suggestions: [{ label: 'Documents', path: '/Documents' }] });
  if (url.includes('/api/knowledge/search')) return json({ totalHits: 0, matchCount: 0, tookMs: 1, results: [], nextCursor: null, facets: {}, facetsTruncated: false });
  if (url.includes('/api/path-hash/resolve')) return json({ path: '/notes' });
  return json({ entries: [], conversations: [] });
};
window.EventSource = class { constructor() { this.readyState = 0; } addEventListener() {} removeEventListener() {} close() {} };
const { __seedForTest } = await import('/src/api/registry/SurfaceCatalogClient.ts');
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/chrome/Shell.ts');
await import('/src/shell-v0/views/LibrarySurface.ts');
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL', presentation: { labelKey: id, descriptionKey: id },
  consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] }, provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('core.search-v3-surface', 'jf-sv3-window'), surface('core.library-surface', 'jf-library-surface'),
  surface('core.brain-surface', 'jf-brain-surface'), surface('core.help-surface', 'jf-help-surface'),
  surface('core.settings-surface', 'jf-settings-surface', { placement: 'MODAL' }),
] });
window.firstUse = await import('/src/shell-v0/state/firstUse.ts').catch(() => null);
window.userState = await import('/src/shell-v0/state/UserStateDocument.ts');
window.tours = await import('/src/shell-v0/commands/WalkthroughRegistry.ts');
window.commands = await import('/src/shell-v0/commands/CommandRegistry.ts');
window.ai = await import('/src/shell-v0/state/aiStateStore.ts');
window.search = await import('/src/shell-v0/state/searchState.ts');
window.observe = (r, c, q = 0) => {
  roots = r; count = c; pending = q;
  window.firstUse?.setFirstUseRoots(roots === null ? null : roots ? [root] : [], new Map([['notes', '/notes']]));
  window.ai.__feedForTest({ status: count === null ? null : { worker: { core: { indexState: pending ? 'INDEXING' : 'IDLE', indexedDocuments: count + help, searchableDocuments: count + help, ownSearchableDocuments: params.has('own-unknown') ? null : count, pendingJobs: pending } } } });
  window.ai.__feedContactForTest();
};
window.updateHelp = n => { help = n; window.observe(roots, count, pending); };
window.addCalls = [];
window.folderHost = { platform: { capabilities: new Set(), pickFolder: async () => null }, data: {
  fetch: async () => json({ exists: true, isDir: true }),
  invokeOperation: (id, args) => { window.addCalls.push({ id, args }); return new Promise(resolve => { window.finishAdd = resolve; }); }
} };
const deep = (root, name) => {
  for (const el of root.querySelectorAll('*')) {
    if (el.localName === name) return el;
    const inner = el.shadowRoot && deep(el.shadowRoot, name); if (inner) return inner;
  }
};
window.attachHost = () => { const composer = deep(document, 'jf-sv3-composer'); if (composer) composer.host_ = window.folderHost; };
document.body.append(document.createElement('jf-shell'));
window.observe(roots, count, pending);
setInterval(() => window.observe(roots, count, pending), 2_000);
document.body.dataset.ready = 'true';
</script></body></html>`;

type AppWindow = Window & { observe(roots: number | null, count: number | null, pending?: number): void;
  attachHost(): void; addCalls: unknown[]; finishAdd(result: unknown): void;
  updateHelp(n: number): void;
  search: { setQuery(query: string): void; submitSearch(): void };
  firstUse: { hideGettingStarted(): void; reopenGettingStarted(): void };
  userState: { markWalkthroughStepComplete(id: string, step: string): void; createProfile(id: string, label: string): void; setActiveProfileId(id: string): void };
};
let server: ViteDevServer;
let browser: Browser;
let browserServer: BrowserServer;
let context: BrowserContext | undefined;
let output: string;
let url: string;
beforeAll(async () => {
  output = await mkdtemp(path.join(process.env.TMPDIR ?? tmpdir(), 'first-use-app-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: path.join(output, 'vite'), publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'first-use-app', configureServer(vite) {
      vite.middlewares.use((req, res, next) => { if (req.url?.split('?')[0] !== '/app') return next();
        res.setHeader('Content-Type', 'text/html'); res.end(HARNESS); });
    } }] });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/app`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: process.env.TMPDIR ?? output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned first-use Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
}, 60_000);
afterEach(async () => { await context?.close(); context = undefined; });
afterAll(async () => { await browser?.close(); await browserServer?.close(); await server?.close(); if (output) await rm(output, { recursive: true, force: true }); });
async function settle(page: Page) { await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))); }
async function open(query = '', hash = '', viewport = { width: 1280, height: 800 }) {
  context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
  const page = await context.newPage();
  await page.clock.install();
  page.on('pageerror', error => console.info('first-use pageerror:', error.message));
  await page.goto(`${url}${query}${hash}`);
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 60_000 });
  await page.locator('jf-top-bar').waitFor({ timeout: 60_000 });
  await settle(page); return page;
}
const add = (page: Page) => page.locator('jf-getting-started').getByRole('button', { name: 'Add a folder', exact: true }).first();

describe('S4 first minutes in Chromium', { timeout: 60_000 }, () => {
  it('S4-1 first launch has one message/action and no field, checklist, sidebar or status', async () => {
    const page = await open();
    expect(await page.getByText('Nothing to search yet', { exact: true }).count()).toBe(1);
    expect(await add(page).count()).toBe(1);
    expect(await page.locator('jf-sv3-composer textarea, jf-getting-started .card, jf-sv3-sidebar').count()).toBe(0);
    expect(await page.locator('jf-top-bar').textContent()).not.toContain('Getting started');
    expect(await page.getByText('Documents', { exact: true }).count()).toBe(1);
  });
  it('S4-2 unknown observations do not claim an empty library', async () => {
    const page = await open('?unknown');
    expect(await page.getByText('Nothing to search yet', { exact: true }).count()).toBe(0);
    expect(await page.locator('jf-sv3-composer textarea').count()).toBe(1);
    expect(await page.locator('jf-getting-started .card').count()).toBe(0);
  });
  it('S4-8 browser path entry has no Collection; acceptance focuses Search and reading count expires', async () => {
    const page = await open(); await page.evaluate(() => (window as unknown as AppWindow).attachHost());
    await add(page).click();
    expect(await page.getByRole('textbox', { name: 'Collection (optional)' }).count()).toBe(0);
    await page.getByRole('textbox', { name: 'Folder path', exact: true }).fill('/notes');
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    await page.evaluate(() => { const w = window as unknown as AppWindow; w.observe(1, 2, 8); w.finishAdd({ success: true }); }); await settle(page);
    expect(await page.getByText('2 files searchable so far', { exact: true }).count()).toBe(1);
    expect(await page.locator('jf-status-pill .ring.indeterminate').count()).toBe(1);
    expect(await page.locator('jf-status-pill .ring').getAttribute('data-percent')).toBeNull();
    expect(await page.locator('jf-sv3-composer textarea').evaluate(el => el === (el.getRootNode() as ShadowRoot).activeElement)).toBe(true);
    await page.evaluate(() => (window as unknown as AppWindow).observe(1, 2)); await settle(page);
    expect(await page.getByText('2 files searchable so far', { exact: true }).count()).toBe(0);
    expect(await page.locator('jf-sv3-sidebar').count()).toBe(0);
  });
  for (const [width, height] of [[1024,700], [600,400]] as const) for (const theme of ['dark','light','forced']) {
    it(`S4-19 ${width}x${height} ${theme} with 200% text keeps first action reachable`, async () => {
      const page = await open('', '', {width,height});
      await page.emulateMedia({colorScheme: theme === 'light' ? 'light':'dark', forcedColors: theme === 'forced' ? 'active':'none'});
      await page.evaluate(() => { document.documentElement.style.fontSize = '200%'; }); await settle(page);
      await add(page).scrollIntoViewIfNeeded(); expect(await add(page).isVisible()).toBe(true);
      const rect = await add(page).boundingBox(); expect(rect!.x).toBeGreaterThanOrEqual(0); expect(rect!.x + rect!.width).toBeLessThanOrEqual(width);
      expect(await page.locator('jf-getting-started .card').count()).toBe(0);
    });
  }
});
