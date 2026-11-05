// SPDX-License-Identifier: Apache-2.0
// L18/L21 — real keyboard focus and confirmation bands on isolated Library and failed-files lists in
// headless Chromium (private Vite server; no Engine, no shared dev stack). Run with
// npx vitest run --config vitest.config.lockdown.ts src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

let server: ViteDevServer, browserServer: BrowserServer, browser: Browser;
let context: BrowserContext | undefined;
let output: string, url: string;
interface Focus { readonly page: boolean; readonly control: string | null; readonly row: string | null }
declare global {
  interface Window {
    prepareLists(themed: boolean): Promise<void>;
    setRoots(hashes: string[]): Promise<void>;
    setSources(sources: [string, number][]): Promise<void>;
    prepareFailedDrawer(): Promise<void>;
    refreshFailedDrawer(listed: string[]): Promise<void>;
    focusNow(): Focus;
  }
}
beforeAll(async () => {
  output = await mkdtemp(path.join(tmpdir(), 'library-focus-keeper-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: output, publicDir: false,
    logLevel: 'silent', server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'isolated-library-focus',
      configureServer(vite) { vite.middlewares.use('/focus-keeper', (_req, res) => {
        res.setHeader('Content-Type', 'text/html');
        res.end(`<!doctype html><html><head><meta charset="utf-8">
          <link rel="stylesheet" href="/src/styles/tokens.css">
          <link rel="stylesheet" href="/src/shell-v0/themes/default.css"></head><body><script type="module">
          import '/src/shell-v0/views/LibrarySurface.ts';
          import '/src/shell-v0/components/FailedJobsDrawer.ts';
          import '/src/shell-v0/renderers/layouts/VerticalLayout.ts';
          import '/src/shell-v0/renderers/controls/XUiRendererControl.ts';
          import '/src/shell-v0/renderers/controls/FolderCardRenderer.ts';
          import { applyPresentationBodies } from '/src/shell-v0/state/presentationRuntime.ts';
          import { CORE_DECLARED } from '/src/shell-v0/themes/builtinPresentations.ts';
          import { __feedForTest } from '/src/shell-v0/state/aiStateStore.ts';
          import { createMockHostApi } from '/src/shell-v0/plugin-api/testHostApi.ts';
          import { openFailedJobs } from '/src/shell-v0/state/failedJobsDrawer.ts';
          import { __seedForTest as seedOperations } from '/src/api/registry/OperationCatalogClient.ts';
          import { __seedForTest as seedResources } from '/src/api/registry/ResourceCatalogClient.ts';
          const settle = async () => { for (let i = 0; i < 4; i++) await new Promise(requestAnimationFrame); };
          const names = { a: '/home/sam/Notes', b: '/home/sam/Docs', c: '/home/sam/Music' };
          let library, drawer, rows = ['a', 'b', 'c'], failed = [];
          __feedForTest({ status: { worker: { core: { indexState: 'IDLE', indexedDocuments: 12, indexHealthy: true },
            migration: { migrationState: 'IDLE' } }, readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } });
          const root = hash => ({ pathHash: hash, collection: 'default', fileCount: 12, walkCompleted: true });
          window.prepareLists = async themed => {
            if (themed) applyPresentationBodies(CORE_DECLARED);
            library = document.createElement('jf-library-surface');
            library.host_ = createMockHostApi({ data: {
              fetch: async path => new Response(JSON.stringify(path.startsWith('/api/indexing-roots/substrate')
                ? { items: rows.map(root) } : {})),
              invokeOperation: async () => ({ success: true }) } });
            library.resolvedPaths = names;
            document.body.append(library); await settle();
            library.otherSources = { sources: [{ collection: 'mcp-ingest', docCount: 12 }, { collection: 'other-app', docCount: 5 }], truncated: false };
            await settle();
          };
          window.setRoots = async hashes => { rows = hashes; library.roots = hashes.map(root); await settle(); };
          window.setSources = async sources => {
            library.otherSources = { sources: sources.map(([collection, docCount]) => ({ collection, docCount })), truncated: false };
            await settle();
          };
          seedOperations({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Operation',
            entries: [{ id: 'core.cancel-indexing-job', policy: { risk: 'MEDIUM', confirm: { kind: 'INLINE' } },
              presentation: { labelKey: 'Cancel file', descriptionKey: '' }, lineage: { affects: [], supersedes: [] } }] });
          seedResources({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Resource',
            entries: [{ id: 'core.failed-indexing-jobs', primaryKey: 'pathHash', itemOperations: ['core.cancel-indexing-job'] }] });
          const job = hash => ({ pathHash: hash, state: 'FAILED', attempts: 3, lastUpdatedMs: 1700000000000,
            errorMessage: '', retryAfterMs: 0, collection: 'default', scanId: '' });
          window.prepareFailedDrawer = async () => {
            failed = ['first', 'target', 'last'];
            drawer = document.createElement('jf-failed-jobs-drawer');
            drawer.host_ = createMockHostApi({ data: { fetch: async () => new Response(JSON.stringify({ jobs: failed.map(job), count: failed.length })) } });
            document.body.append(drawer); openFailedJobs('a');
            for (let i = 0; i < 20 && drawer.rows.length !== failed.length; i++) await settle();
          };
          // The drawer's own refresh, as it runs when the failed-files list is read again.
          window.refreshFailedDrawer = async listed => { failed = listed; await drawer.refresh(); await settle(); };
          window.focusNow = () => {
            let active = document.activeElement;
            while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
            const up = node => node.parentNode ?? node.host ?? null;
            let control = null, row = null;
            for (let at = active; at; at = up(at)) {
              if (!(at instanceof Element)) continue;
              control ??= at.getAttribute('label') || at.getAttribute('aria-label') || null;
              row ??= at.matches('.card') ? at.querySelector('.folder-toggle')?.getAttribute('aria-controls') ?? null
                : at.matches('.row') && at.querySelector('jf-row-actions') ? 'file:' + at.querySelector('jf-row-actions').getAttribute('row-key')
                : at.matches('.other-source-card') ? 'source:' + at.querySelector('[data-testid="library-other-source-name"]').textContent : null;
            }
            if (!control && active?.classList.contains('folder-toggle')) control = 'folder-toggle';
            return { page: !active || active === document.body || active === document.documentElement, control, row };
          };
          document.body.dataset.ready = 'true';
          </script></body></html>`);
      }); },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/focus-keeper`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned headless Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
}, 60_000);
afterEach(async () => { await context?.close(); context = undefined; });
afterAll(async () => {
  await browser?.close(); await browserServer?.close(); await server?.close();
  if (output) await rm(output, { recursive: true, force: true });
});
/** The collections the stubbed engine's facet probe lists. */
let collections: Record<string, number> = {};
async function open(themed = false): Promise<Page> {
  context = await browser.newContext({ viewport: { width: 1280, height: 1600 } });
  const page = await context.newPage();
  page.setDefaultTimeout(3000);
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  collections = { 'mcp-ingest': 12, 'other-app': 5 };
  await page.route(new URL('/api/**', url).href, route => route.fulfill({ status: 200, contentType: 'application/json',
    body: route.request().url().includes('collection-facet') ? JSON.stringify({ totalHits: 0, results: [], facets: { collection: collections } }) : '{}' }));
  await page.goto(url, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ state: 'attached', timeout: 45_000 });
  await page.evaluate(themed => window.prepareLists(themed), themed);
  for (const hash of ['a', 'b', 'c']) {
    const toggle = page.locator(`.folder-toggle[aria-controls="folder-details-${hash}"]`);
    if (await toggle.getAttribute('aria-expanded') === 'false') { await toggle.focus(); await page.keyboard.press('Enter'); }
    await expect.poll(() => toggle.getAttribute('aria-expanded')).toBe('true');
  }
  await page.locator('body').evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
  expect(errors).toEqual([]);
  return page;
}
const focusNow = (page: Page) => page.evaluate(() => window.focusNow());
/** Open "Added by other apps" with the keyboard (no pointer press). */
async function openSources(page: Page): Promise<void> {
  await page.locator('.bottom-links summary').filter({ hasText: 'Added by other apps' }).focus();
  await page.keyboard.press('Enter');
}
/** Focus a control by its label with the keyboard's focus (no pointer press). */
async function focusControl(page: Page, label: string, row?: string): Promise<void> {
  const scope = row ? page.locator(`.card:has([aria-controls="${row}"])`) : page;
  await scope.locator(`jf-button[label="${label}"] button`).first().focus();
}

it.each([false, true])('L21 focus stays on its control when the folder rows reorder (themed=%s)', async themed => {
  const page = await open(themed);
  await focusControl(page, 'Remove from library', 'folder-details-a');
  expect(await focusNow(page)).toEqual({ page: false, control: 'Remove from library', row: 'folder-details-a' });
  for (const order of [['c', 'b', 'a'], ['b', 'a', 'c'], ['a', 'b', 'c']]) {
    await page.evaluate(order => window.setRoots(order), order);
    await expect.poll(() => focusNow(page), { message: `after reordering to ${order.join(',')}` })
      .toEqual({ page: false, control: 'Remove from library', row: 'folder-details-a' });
  }
}, 30_000);

it('L21 focus stays on a source\'s control when the sources reorder', async () => {
  const page = await open();
  await openSources(page);
  await page.locator('jf-button[label="Remove mcp-ingest"] button').focus();
  await page.evaluate(() => window.setSources([['other-app', 40], ['mcp-ingest', 12]]));
  await expect.poll(() => focusNow(page)).toEqual({ page: false, control: 'Remove mcp-ingest', row: 'source:mcp-ingest' });
}, 30_000);

it.each([false, true])('L21 a band whose folder another update removed hands focus to the next folder, never the page (themed=%s)', async themed => {
  const page = await open(themed);
  await focusControl(page, 'Remove from library', 'folder-details-a');
  await page.keyboard.press('Enter');
  const keep = page.locator('jf-confirm-band jf-button.keep button');
  await expect.poll(() => keep.evaluate(el => el.matches(':focus'))).toBe(true);
  // Another window removes Notes.
  await page.evaluate(() => window.setRoots(['b', 'c']));
  await expect.poll(() => page.locator('jf-confirm-band').count()).toBe(0);
  await expect.poll(() => focusNow(page)).toMatchObject({ page: false, row: 'folder-details-b' });
}, 30_000);

it('L18 a collection band closes for good when its source leaves; it opens again only on a press', async () => {
  const page = await open();
  await openSources(page);
  await page.locator('jf-button[label="Remove mcp-ingest"] button').focus();
  await page.keyboard.press('Enter');
  const band = page.locator('.other-source-confirm jf-confirm-band');
  await expect.poll(() => band.count()).toBe(1);
  await page.evaluate(() => window.setSources([['other-app', 5]]));
  await expect.poll(() => band.count()).toBe(0);
  await page.evaluate(() => window.setSources([['mcp-ingest', 12], ['other-app', 5]]));
  expect(await band.count(), 'the source came back: its band stays closed').toBe(0);
  for (let i = 1; i <= 3; i++) await page.evaluate(i => window.setSources([['mcp-ingest', 12 + i], ['other-app', 5]]), i);
  expect(await band.count()).toBe(0);
  await page.locator('jf-button[label="Remove mcp-ingest"] button').focus();
  await page.keyboard.press('Enter');
  await expect.poll(() => band.count(), { message: 'a press opens it' }).toBe(1);
}, 30_000);

it('L21 a band whose source another update removed hands focus to the next source', async () => {
  const page = await open();
  await openSources(page);
  await page.locator('jf-button[label="Remove mcp-ingest"] button').focus();
  await page.keyboard.press('Enter');
  const band = page.locator('.other-source-confirm jf-confirm-band');
  await expect.poll(() => band.locator('jf-button.keep button').evaluate(el => el.matches(':focus'))).toBe(true);
  await page.evaluate(() => window.setSources([['other-app', 5]]));
  await expect.poll(() => band.count()).toBe(0);
  await expect.poll(() => focusNow(page)).toEqual({ page: false, control: 'Remove other-app', row: 'source:other-app' });
}, 30_000);

it('L21 a confirmed collection removal hands focus to the next source', async () => {
  const page = await open();
  await openSources(page);
  await page.locator('jf-button[label="Remove mcp-ingest"] button').focus();
  await page.keyboard.press('Enter');
  const band = page.locator('.other-source-confirm jf-confirm-band');
  await expect.poll(() => band.locator('jf-button.keep button').evaluate(el => el.matches(':focus'))).toBe(true);
  // The removal runs: from now on the engine, and every later read of it, no longer lists the collection.
  collections = { 'other-app': 5 };
  await page.keyboard.press('Shift+Tab'); await page.keyboard.press('Enter');
  await expect.poll(() => band.count()).toBe(0);
  await page.evaluate(() => window.setSources([['other-app', 5]]));
  await expect.poll(() => page.locator('[data-testid="library-other-source-name"]').allTextContents()).toEqual(['other-app']);
  await expect.poll(() => focusNow(page)).toEqual({ page: false, control: 'Remove other-app', row: 'source:other-app' });
}, 30_000);

it('L21 a failed-files refresh keeps an open band for a file still listed, with focus; a file that leaves hands focus on', async () => {
  const page = await open();
  await page.evaluate(() => window.prepareFailedDrawer());
  const action = page.locator('jf-failed-jobs-drawer jf-row-actions[row-key="target"] jf-action-button');
  await action.locator('button.invoke').focus(); await page.keyboard.press('Enter');
  const band = action.locator('jf-confirm-band');
  await expect.poll(() => band.locator('jf-button.keep button').evaluate(el => el.matches(':focus'))).toBe(true);
  const original = await band.elementHandle();
  await page.evaluate(() => window.refreshFailedDrawer(['target', 'last']));
  expect(await original!.evaluate(el => el.isConnected), 'the open band stays with its file').toBe(true);
  expect(await band.locator('jf-button.keep button').evaluate(el => el.matches(':focus'))).toBe(true);
  // The file leaves the list (fixed elsewhere): focus moves to the next file, never the page.
  await page.evaluate(() => window.refreshFailedDrawer(['first', 'last']));
  await expect.poll(() => band.count()).toBe(0);
  await expect.poll(() => focusNow(page)).toMatchObject({ page: false, row: 'file:last' });
}, 30_000);
