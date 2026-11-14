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
const card = (page: Page) => page.locator('jf-getting-started .card');
const add = (page: Page) => page.locator('jf-getting-started').getByRole('button', { name: 'Add a folder', exact: true }).first();
const focusedLabel = (page: Page) => page.evaluate(() => { let el = document.activeElement; while (el?.shadowRoot?.activeElement) el = el.shadowRoot.activeElement;
  return el?.getAttribute('aria-label') ?? el?.textContent?.trim(); });

describe('first use through the real frame', { timeout: 60_000 }, () => {
  it('unknown → zero → positive changes the hero only with confirmed facts and preserves the ordinary composer', async () => {
    const page = await open('?unknown');
    expect(await page.getByText('Nothing to search yet', { exact: true }).count()).toBe(0);
    expect(await page.locator('jf-sv3-composer textarea').count()).toBe(1);
    await page.evaluate(() => (window as unknown as AppWindow).observe(0, 0)); await settle(page);
    expect(await page.getByText('Nothing to search yet', { exact: true }).count()).toBe(1);
    await page.evaluate(() => (window as unknown as AppWindow).observe(1, 2, 8)); await settle(page);
    expect(await page.getByText('Nothing to search yet', { exact: true }).count()).toBe(0);
    expect(await page.locator('jf-sv3-composer textarea').count()).toBe(1);
    expect(await page.getByText('2 files searchable so far', { exact: true }).count()).toBe(1);
  });
  it('Help alone keeps setup current, and unknown own counts stay unknown', async () => {
    const page = await open();
    expect(await page.getByText('Nothing to search yet', { exact: true }).count()).toBe(1);
    expect(await page.locator('jf-status-pill').getByRole('button', { name: 'Getting started · 1 of 3', exact: true }).count()).toBe(0);
    await page.evaluate(() => { const w = window as unknown as AppWindow; w.search.setQuery('help'); w.search.submitSearch(); }); await settle(page);
    expect(await card(page).count()).toBe(0);
    expect(await page.locator('jf-getting-started [aria-label="Done"]').count()).toBe(0);
    await page.evaluate(() => (window as unknown as AppWindow).updateHelp(8)); await page.clock.runFor(60_000); await settle(page);
    expect(await card(page).count()).toBe(0);
    expect(await page.locator('jf-getting-started [aria-label="Done"]').count()).toBe(0);
    await page.goto(url + '?own-unknown'); await page.locator('body[data-ready="true"]').waitFor(); await settle(page);
    expect(await card(page).count()).toBe(0);
    expect(await page.getByText('Nothing to search yet', { exact: true }).count()).toBe(0);
  });
  it('reading exposes only the partial count; settled folders repeat no corpus fact', async () => {
    const page = await open();
    await page.evaluate(() => (window as unknown as AppWindow).observe(1, 0, 8)); await settle(page);
    expect(await card(page).count()).toBe(0);
    expect(await page.getByText('0 files searchable so far', { exact: true }).count()).toBe(1);
    await page.evaluate(() => (window as unknown as AppWindow).observe(1, 0)); await settle(page);
    expect(await page.getByText('0 files searchable so far', { exact: true }).count()).toBe(0);
    expect(await page.locator('jf-sv3-composer textarea').count()).toBe(1);
    await page.evaluate(() => (window as unknown as AppWindow).observe(0, 1)); await settle(page);
    expect(await page.getByText('Nothing to search yet', { exact: true }).count()).toBe(0);
    expect(await page.getByText('1 file searchable so far', { exact: true }).count()).toBe(0);
    await page.evaluate(() => (window as unknown as AppWindow).updateHelp(8)); await page.clock.runFor(60_000); await settle(page);
    expect(await page.getByText('1 file searchable so far', { exact: true }).count()).toBe(0);
  });
  it('browser cancel and add failure restore keyboard focus; duplicate submission stays single', async () => {
    const page = await open();
    expect(await card(page).count()).toBe(0);
    await page.evaluate(() => (window as unknown as AppWindow).attachHost()); await settle(page);
    await add(page).click();
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    expect(await focusedLabel(page)).toBe('Add a folder');
    await add(page).click(); await page.getByRole('textbox', { name: 'Folder path', exact: true }).fill('/missing');
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    await page.getByRole('textbox', { name: 'Folder path', exact: true }).press('Enter');
    expect(await page.evaluate(() => (window as unknown as AppWindow).addCalls.length)).toBe(1);
    await page.evaluate(() => (window as unknown as AppWindow).finishAdd({ success: false, message: 'Path not found' })); await settle(page);
    expect(await page.getByRole('alert').filter({ hasText: 'Path not found' }).count()).toBe(1);
    expect(await focusedLabel(page)).toBe('Folder path');
    await page.getByRole('textbox', { name: 'Folder path', exact: true }).fill('/notes');
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    await page.evaluate(() => (window as unknown as AppWindow).finishAdd({ success: true })); await settle(page);
    expect(await focusedLabel(page)).toBe('Add a folder');
    await page.evaluate(() => (window as unknown as AppWindow).observe(1, 1)); await settle(page);
    expect(await page.getByText('Folder added. Indexing activity is shown below.', { exact: true }).count()).toBe(0);
    await page.evaluate(() => (window as unknown as AppWindow).observe(0, 0)); await settle(page);
    expect(await page.getByText('Nothing to search yet', { exact: true }).count()).toBe(1);
  });
  it('legacy progress remains shared across windows, reload and profiles without resurrecting removed UI', async () => {
    const page = await open();
    const other = await context!.newPage(); await other.goto(url); await other.locator('body[data-ready="true"]').waitFor();
    await page.evaluate(() => (window as unknown as AppWindow).firstUse.hideGettingStarted());
    await other.waitForFunction(() => JSON.parse(localStorage.getItem('justsearch.userState.v2') || '{}').walkthroughState?.['core.getting-started']?.dismissed);
    await settle(other); expect(await card(other).count()).toBe(0);
    await page.reload(); await page.locator('body[data-ready="true"]').waitFor(); await settle(page);
    expect(await card(page).count()).toBe(0);
    await page.evaluate(() => (window as unknown as AppWindow).firstUse.reopenGettingStarted());
    await other.waitForFunction(() => JSON.parse(localStorage.getItem('justsearch.userState.v2') || '{}').walkthroughState?.['core.getting-started']?.dismissed === false);
    await settle(other); expect(await card(other).count()).toBe(0);
    await page.evaluate(() => { const w = window as unknown as AppWindow; w.userState.createProfile('work', 'Work'); w.userState.setActiveProfileId('work'); });
    await settle(page); expect(await card(page).count()).toBe(0);
    expect(await add(page).count()).toBe(1);
  });
  it('explicit addresses and the saved journal win without an onboarding history entry', async () => {
    const page = await open('', '#justsearch://surface/core.library-surface');
    expect(await page.locator('jf-library-surface').count()).toBe(1);
    expect(await card(page).count()).toBe(0);
    const initial = await page.evaluate(() => ({ hash: location.hash, journal: localStorage.getItem('justsearch.navigationJournal.v1'), length: history.length }));
    await page.evaluate(() => (window as unknown as AppWindow).observe(0, 0)); await settle(page);
    expect(await page.evaluate(() => ({ hash: location.hash, journal: localStorage.getItem('justsearch.navigationJournal.v1'), length: history.length }))).toEqual(initial);
    await page.goto(url); await page.locator('body[data-ready="true"]').waitFor(); await settle(page);
    expect(await page.locator('jf-library-surface').count()).toBe(1);
    expect(await card(page).count()).toBe(0);
  });
  it('keyword search needs no AI and writes no first-use completion', async () => {
    const page = await open();
    await page.evaluate(() => (window as unknown as AppWindow).observe(1, 2)); await settle(page);
    await page.locator('jf-sv3-composer textarea').fill('notes'); await page.keyboard.press('Enter'); await settle(page);
    expect(await page.locator('jf-sv3-results').count()).toBe(1);
    expect(await page.evaluate(() => JSON.parse(localStorage.getItem('justsearch.userState.v2') || '{}').walkthroughState?.['core.getting-started']?.completedStepIds ?? [])).not.toContain('try-search');
    await page.evaluate(() => { location.hash = '#justsearch://surface/core.help-surface'; });
    await page.locator('jf-help-surface').waitFor();
    expect(await page.locator('jf-help-surface').getByRole('button', { name: 'Getting started', exact: true }).count()).toBe(0);
    expect(await page.evaluate(() => (window as unknown as AppWindow).addCalls.length)).toBe(0);
  });
  it('reloading during indexing rediscovers the folder and live count before offering search', async () => {
    const page = await open('?reading'); expect(await card(page).count()).toBe(0);
    for (let i = 0; i < 2; i++) {
      expect(await page.getByText('2 files searchable so far', { exact: true }).count()).toBe(1);
      expect(await page.locator('jf-sv3-composer textarea').count()).toBe(1);
      expect(await page.getByText('Nothing to search yet', { exact: true }).count()).toBe(0);
      if (i === 0) { await page.reload(); await page.locator('body[data-ready="true"]').waitFor(); await settle(page); }
    }
  });
  it('leaving and returning during an add keeps failure actionable and focuses the new path field', async () => {
    const page = await open(); expect(await card(page).count()).toBe(0);
    await page.evaluate(() => (window as unknown as AppWindow).attachHost()); await settle(page);
    await add(page).click(); await page.getByRole('textbox', { name: 'Folder path', exact: true }).fill('/notes');
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    await page.evaluate(() => { location.hash = '#justsearch://surface/core.help-surface'; });
    await page.locator('jf-help-surface').waitFor();
    await page.evaluate(() => { location.hash = '#justsearch://surface/core.search-v3-surface'; });
    await page.getByRole('textbox', { name: 'Folder path', exact: true }).waitFor();
    await page.evaluate(() => (window as unknown as AppWindow).finishAdd({ success: false, message: 'Folder vanished' })); await settle(page);
    expect(await page.getByRole('alert').filter({ hasText: 'Folder vanished' }).count()).toBe(1);
    expect(await focusedLabel(page)).toBe('Folder path');
    expect(await page.evaluate(() => (window as unknown as AppWindow).addCalls.length)).toBe(1);
  });
  for (const [width, height] of [[1280, 800], [900, 800], [600, 400]] as const) {
    for (const theme of ['dark', 'light', 'forced']) it(`${width}x${height}, ${theme}, enlarged text keeps setup actions reachable without horizontal overflow`, async () => {
      const page = await open('', '', { width, height });
      expect(await card(page).count()).toBe(0);
      await page.emulateMedia({ colorScheme: theme === 'light' ? 'light' : 'dark', forcedColors: theme === 'forced' ? 'active' : 'none' });
      await page.evaluate(palette => { document.documentElement.setAttribute('data-theme', palette); document.documentElement.style.fontSize = '200%'; }, theme === 'light' ? 'light' : 'dark');
      await settle(page);
      const overflow = await page.locator('jf-getting-started').evaluate(el => {
        const bad: string[] = [];
        const walk = (root: ParentNode) => { for (const node of root.querySelectorAll<HTMLElement>('*')) {
          const rect = node.getBoundingClientRect();
          if (rect.width > 0 && (rect.left < -1 || rect.right > innerWidth + 1)) bad.push(node.localName + '.' + node.className);
          if (node.shadowRoot) walk(node.shadowRoot);
        } }; walk(el.shadowRoot!); return bad;
      });
      expect(overflow).toEqual([]);
      await add(page).scrollIntoViewIfNeeded();
      expect(await add(page).isVisible()).toBe(true);
      await add(page).click();
      expect(await page.getByRole('textbox', { name: 'Folder path', exact: true }).isVisible()).toBe(true);
    });
  }
});
