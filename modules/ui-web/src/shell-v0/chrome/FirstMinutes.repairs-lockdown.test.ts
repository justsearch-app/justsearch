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
window.searches = [];
window.protection = 'unlocked';
window.conversations = params.has('saved') ? [{sessionId:'saved', title:'Saved conversation', createdAtMs:1, lastActiveAtMs:2, messageCount:2, shapeId:'core.rag-ask'}] : [];
if(params.has('multiline')) localStorage.setItem('justsearch.draft.search-v3.composer', 'old\\nexact draft');
let count = params.has('unknown') ? null : params.has('reading') ? 2 : 0, pending = params.has('reading') ? 8 : 0;
const root = { pathHash: 'notes', collection: 'default', status: 'indexing', inFlightCount: 8, failedCount: 0, walkCompleted: false };
window.fetch = async (input, init) => {
  const url = String(input?.url ?? input);
  if (url.includes('/api/indexing-roots/substrate')) return roots === null ? new Response('', { status: 503 }) : json({ items: roots ? [root] : [], count: roots });
  if (url.includes('/api/indexing/suggested-roots')) return json({ suggestions: [{ label: 'Documents', path: '/Documents' }] });
  if (url.includes('/api/knowledge/search')) { window.searches.push(JSON.parse(init.body)); return json({ totalHits: 0, matchCount: 0, tookMs: 1, results: [], nextCursor: null, facets: {}, facetsTruncated: false }); }
  if (url.includes('/api/chat/conversations?')) return json({sessions:window.conversations});
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
window.appearance = await import('/src/shell-v0/state/themeState.ts');
window.search = await import('/src/shell-v0/state/searchState.ts');
window.filters = await import('/src/shell-v0/state/searchFiltersState.ts');
window.conversationStore = await import('/src/shell-v0/state/conversationListStore.ts');
window.observe = (r, c, q = 0) => {
  roots = r; count = c; pending = q;
  window.firstUse?.setFirstUseRoots(roots === null ? null : roots ? [root] : [], new Map([['notes', '/notes']]));
  window.ai.__feedForTest({ status: count === null ? null : { conversationProtection: {state:window.protection}, worker: { core: { indexState: pending ? 'INDEXING' : 'IDLE', indexedDocuments: count + help, searchableDocuments: count + help, ownSearchableDocuments: params.has('own-unknown') ? null : count, pendingJobs: pending } } } });
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

type AppWindow = Window & { protection:'locked'|'unlocked'; conversations:Record<string,unknown>[]; conversationStore:{loadConversations():Promise<boolean>}; searches: {query:string;filters?:unknown}[]; filters:{toggleFacetValue(facet:string,value:string):void}; observe(roots: number | null, count: number | null, pending?: number): void;
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


describe('first-minutes repairs through the running app', { timeout: 60_000 }, () => {
it('folder suggestions use normal text ink at weight 500', async () => {
  const page = await open();
  const button = page.getByRole('button', { name: 'Add Documents', exact: true });
  await button.waitFor();
  const style = await button.evaluate(el => ({ weight: getComputedStyle(el).fontWeight,
    color: getComputedStyle(el).color, normal: getComputedStyle(el.closest('.intro')!).color }));
  expect(style).toMatchObject({ weight: '500', color: style.normal });
});

it.each(['pointer', 'Enter', 'Space'])('recent %s returns focus to Search', async method => {
  const page = await open('?reading&saved');
  const field = page.locator('[data-testid="sv3-composer-input"]');
  await field.fill('budget'); await field.press('Enter');
  await page.waitForFunction(() => (window as unknown as AppWindow).searches.length === 1);
  await page.locator('[data-testid="sv3-sidebar-new"]').click();
  const recent = page.locator('[data-testid="sv3-recents"] button[data-kind="search"]');
  if (method === 'pointer') await recent.click();
  else { await recent.focus(); await recent.press(method); }
  await page.waitForFunction(() => (window as unknown as AppWindow).searches.length === 2);
  await expect.poll(() => field.evaluate(el => el === (el.getRootNode() as ShadowRoot).activeElement)).toBe(true);
});

it('Clear returns focus to Search', async () => {
  const page = await open('?reading&saved');
  const field = page.locator('[data-testid="sv3-composer-input"]');
  await field.fill('budget'); await field.press('Enter');
  await page.waitForFunction(() => (window as unknown as AppWindow).searches.length === 1);
  await page.locator('[data-testid="sv3-sidebar-new"]').click();
  await page.locator('[data-testid="sv3-recent-clear"]').click();
  await expect.poll(() => field.evaluate(el => el === (el.getRootNode() as ShadowRoot).activeElement)).toBe(true);
});

it.each(['search', 'ask'])('choosing %s in the switch returns focus to the field', async tier => {
  const page = await open('?reading');
  await page.locator('jf-sv3-composer').evaluate(el => {
    Object.assign(el, { tier: 'ask', askUnavailable: false });
  });
  const option = page.locator(`[data-testid="sv3-composer-tier-option"][data-tier="${tier}"]`);
  await option.click();
  const field = page.locator('[data-testid="sv3-composer-input"]');
  await expect.poll(() => field.evaluate(el => el === (el.getRootNode() as ShadowRoot).activeElement)).toBe(true);
});

it('restarting while existing folders are read shows no first-reading count', async () => {
  const page = await open('?reading');
  expect(await page.locator('[data-testid="first-use-progress"]').count()).toBe(0);
  await page.reload(); await page.locator('body[data-ready="true"]').waitFor(); await settle(page);
  expect(await page.locator('[data-testid="first-use-progress"]').count()).toBe(0);
});

it('a newly added folder has a count only through its first reading, including navigation', async () => {
  const page = await open(); await page.evaluate(() => (window as unknown as AppWindow).attachHost());
  await add(page).click();
  await page.getByRole('textbox', { name: 'Folder path', exact: true }).fill('/notes');
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await page.evaluate(() => { const w = window as unknown as AppWindow; w.observe(1, 2, 8); w.finishAdd({ success: true }); });
  await page.locator('[data-testid="first-use-progress"]').waitFor();
  expect(await page.locator('jf-status-pill .trigger').getAttribute('aria-label')).toBe('Reading files');
  expect(await page.locator('jf-status-pill .label').textContent()).toBe('Reading files');
  await page.evaluate(() => { location.hash = '#justsearch://surface/core.help-surface'; });
  await page.locator('jf-help-surface').waitFor();
  await page.evaluate(() => { location.hash = '#justsearch://surface/core.search-v3-surface'; });
  await page.locator('[data-testid="first-use-progress"]').waitFor({ state: 'visible' });
  await page.evaluate(() => (window as unknown as AppWindow).observe(1, 2)); await settle(page);
  expect(await page.locator('[data-testid="first-use-progress"]').count()).toBe(0);
  await page.evaluate(() => (window as unknown as AppWindow).observe(1, 2, 8)); await settle(page);
  expect(await page.locator('[data-testid="first-use-progress"]').count()).toBe(0);
});

it.each(['calm', 'working', 'first-use', 'unreachable', 'problems', 'limited', 'needs'])('first launch handles %s status', async kind => {
  const page = await open();
  await page.locator('jf-status-pill').evaluate((el, kind) => {
    const pill = el as HTMLElement & { status: { model: { pill: unknown } }; requestUpdate(): void };
    const model = { ...pill.status.model, pill: { kind, label: 'Status witness', stale: false } };
    Object.defineProperty(pill.status, 'model', { get: () => model }); pill.requestUpdate();
  }, kind); await settle(page);
  expect(await page.locator('jf-status-pill .trigger').count()).toBe(['calm', 'working', 'first-use'].includes(kind) ? 0 : 1);
});

it.each(['dark', 'light', 'hc-dark', 'hc-light'])('primary button text clears AA in %s', async theme => {
  const page = await open();
  await page.evaluate(async theme => {
    await (window as unknown as { appearance: { applyAppearance(value: unknown): Promise<void> } }).appearance
      .applyAppearance({ theme: theme.includes('light') ? 'light' : 'dark', highContrast: theme.startsWith('hc-') });
  }, theme);
  await page.evaluate(() => {
    for (const tone of ['', 'success', 'warning', 'error', 'info']) {
      const button = document.createElement('jf-button'); button.setAttribute('variant', 'primary');
      if (tone) button.setAttribute('tone', tone);
      button.dataset.contrastPrimary = tone || 'default'; button.textContent = 'Add a folder'; document.body.append(button);
    }
  });
  for (const element of await page.locator('[data-contrast-primary]').all()) {
    const metrics = await element.evaluate(el => {
      const button = el.shadowRoot!.querySelector('jf-control')!.shadowRoot!.querySelector('button')!;
      const text = el;
      const canvas = document.createElement('canvas'); canvas.width = canvas.height = 1;
      const ctx = canvas.getContext('2d')!;
      const luminance = (color: string) => {
        ctx.fillStyle = color; ctx.fillRect(0, 0, 1, 1);
        const c = [...ctx.getImageData(0, 0, 1, 1).data].slice(0, 3).map(v => {
          const n = v / 255; return n <= .04045 ? n / 12.92 : ((n + .055) / 1.055) ** 2.4;
        });
        return .2126 * c[0]! + .7152 * c[1]! + .0722 * c[2]!;
      };
      const bg = luminance(getComputedStyle(button).backgroundColor), fg = luminance(getComputedStyle(text).color);
      return { ratio: (Math.max(bg, fg) + .05) / (Math.min(bg, fg) + .05) };
    });
    expect(metrics.ratio, await element.getAttribute('data-contrast-primary') ?? 'default').toBeGreaterThanOrEqual(4.5);
  }
});

});
