// SPDX-License-Identifier: Apache-2.0
// Real keyboard interaction on isolated Library surfaces in Chromium; no shared dev stack.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

let server: ViteDevServer, browserServer: BrowserServer, browser: Browser;
let context: BrowserContext | undefined;
let output: string, url: string;
declare global {
  interface Window {
    prepareListBands(themed: boolean): Promise<void>;
    updateListBands(): Promise<number>;
    updateFailedBands(failure: boolean): Promise<void>;
    bandInvocations: number;
  }
}
beforeAll(async () => {
  output = await mkdtemp(path.join(tmpdir(), 'library-band-focus-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: output, publicDir: false,
    logLevel: 'silent', server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'isolated-library-bands',
      configureServer(vite) { vite.middlewares.use('/band-focus', (_req, res) => {
        res.setHeader('Content-Type', 'text/html');
        res.end(`<!doctype html><html><head><meta charset="utf-8">
          <link rel="stylesheet" href="/src/styles/tokens.css">
          <link rel="stylesheet" href="/src/shell-v0/themes/default.css"></head><body><script type="module">
          import '/src/shell-v0/views/LibrarySurface.ts';
          import '/src/shell-v0/renderers/layouts/VerticalLayout.ts';
          import '/src/shell-v0/renderers/controls/XUiRendererControl.ts';
          import '/src/shell-v0/renderers/controls/FolderCardRenderer.ts';
          import { applyPresentationBodies } from '/src/shell-v0/state/presentationRuntime.ts';
          import { CORE_DECLARED } from '/src/shell-v0/themes/builtinPresentations.ts';
          import { __feedForTest } from '/src/shell-v0/state/aiStateStore.ts';
          import { createMockHostApi } from '/src/shell-v0/plugin-api/testHostApi.ts';
          import { __seedForTest as seedOperations } from '/src/api/registry/OperationCatalogClient.ts';
          import { __seedForTest as seedResources } from '/src/api/registry/ResourceCatalogClient.ts';
          const settle = async () => { for (let i = 0; i < 3; i++) await new Promise(requestAnimationFrame); };
          const feed = n => __feedForTest({ status: { worker: { core: { indexState: n % 2 ? 'INDEXING' : 'IDLE',
            indexedDocuments: 12 + n, indexHealthy: true }, migration: { migrationState: 'IDLE' } },
            readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } });
          const roots = n => [{ pathHash: 'a', collection: 'default', fileCount: 12 + n, walkCompleted: true }];
          let library, drawer, generation = 0, removed = false;
          window.bandInvocations = 0;
          window.prepareListBands = async themed => {
            if (themed) applyPresentationBodies(CORE_DECLARED);
            feed(0); library = document.createElement('jf-library-surface');
            library.host_ = createMockHostApi({ data: {
              fetch: async path => new Response(JSON.stringify(path.startsWith('/api/indexing-roots/substrate')
                ? { items: removed ? [] : roots(generation) } : {})),
              invokeOperation: async () => { window.bandInvocations++; removed = true; return { success: true }; }
            } });
            library.resolvedPaths = { a: '/home/sam/Notes' };
            document.body.append(library); await settle();
            library.otherSources = { sources: [{ collection: 'mcp-ingest', docCount: 12 }], truncated: false };
            await settle();
          };
          window.updateListBands = async () => {
            generation++;
            // Fresh status snapshots drive the production subscription; fresh rows simulate its
            // counts-free live refresh payload without running an indexing service.
            feed(generation); library.roots = roots(generation);
            library.otherSources = { sources: [{ collection: 'new-source', docCount: generation },
              { collection: 'mcp-ingest', docCount: 12 + generation }], truncated: false };
            await settle(); return 12 + generation;
          };
          seedOperations({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Operation',
            entries: [{ id: 'core.cancel-indexing-job', policy: { risk: 'MEDIUM', confirm: { kind: 'INLINE' } },
              presentation: { labelKey: 'Cancel file', descriptionKey: '' }, lineage: { affects: [], supersedes: [] } }] });
          seedResources({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Resource',
            entries: [{ id: 'core.failed-indexing-jobs', primaryKey: 'pathHash', itemOperations: ['core.cancel-indexing-job'] }] });
          const row = hash => ({ pathHash: hash, state: 'FAILED', attempts: 3, lastUpdatedMs: 1700000000000,
            errorMessage: '', retryAfterMs: 0, collection: 'default', scanId: '' });
          window.updateFailedBands = async failure => {
            if (!drawer) {
              drawer = document.createElement('jf-failed-jobs-drawer'); drawer.inline = true;
              drawer.folderPathHash = 'a'; document.body.append(drawer); await settle();
            }
            drawer.rows = [row('before'), row('target')]; drawer.loading = false;
            drawer.error = failure ? 'Offline' : null; await settle();
          };
          document.body.dataset.ready = 'true';
          </script></body></html>`);
      }); },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/band-focus`;
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
async function open(themed = false): Promise<Page> {
  context = await browser.newContext();
  const page = await context.newPage();
  page.setDefaultTimeout(3000);
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route(new URL('/api/**', url).href, route => route.fulfill({ status: 200,
    contentType: 'application/json', body: route.request().url().includes('/failed/by-prefix')
      ? '{"jobs":[],"count":0}' : route.request().url().includes('/invoke') ? '{"success":true}' : route.request().url().includes('collection-facet')
        ? '{"totalHits":0,"results":[],"facets":{"collection":{"mcp-ingest":12}}}' : '{}' }));
  await page.goto(url, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ state: 'attached', timeout: 45_000 });
  await page.evaluate(themed => window.prepareListBands(themed), themed);
  expect(errors).toEqual([]);
  return page;
}
it.each([false, true])('folder band keeps its identity, keyboard answer and opener through status updates (themed=%s)', async themed => {
  const page = await open(themed);
  await page.locator('.folder-toggle').click();
  const opener = page.locator('jf-button[label="Remove from library"] button');
  await opener.focus(); await page.keyboard.press('Enter');
  const band = page.locator('jf-confirm-band');
  const keep = band.locator('jf-button.keep button'), remove = band.locator('jf-button.confirm button');
  await expect.poll(() => keep.evaluate(el => el.matches(':focus'))).toBe(true);
  const original = await band.elementHandle(), originalOpener = await opener.elementHandle();
  await page.keyboard.press('Shift+Tab');
  expect(await remove.evaluate(el => el.matches(':focus'))).toBe(true);
  for (let i = 0; i < 20; i++) {
    const count = await page.evaluate(() => window.updateListBands());
    expect(await page.locator('.files-found').textContent()).toContain(String(count));
    expect(await remove.evaluate(el => el.matches(':focus'))).toBe(true);
    expect(await band.evaluate((el, old) => el === old, original)).toBe(true);
  }
  await page.keyboard.press('Escape');
  await expect.poll(() => band.count()).toBe(0);
  expect(await opener.evaluate((el, old) => el === old, originalOpener)).toBe(true);
  expect(await opener.evaluate(el => el.matches(':focus'))).toBe(true);
  expect(await page.evaluate(() => window.bandInvocations)).toBe(0);
  await page.keyboard.press('Enter');
  await expect.poll(() => keep.evaluate(el => el.matches(':focus'))).toBe(true);
  await page.evaluate(() => window.updateListBands()); await keep.click();
  await expect.poll(() => band.count()).toBe(0);
  expect(await opener.evaluate(el => el.matches(':focus'))).toBe(true);
  await page.keyboard.press('Enter');
  await expect.poll(() => keep.evaluate(el => el.matches(':focus'))).toBe(true);
  await page.evaluate(() => window.updateListBands());
  await page.keyboard.press('Shift+Tab'); await page.keyboard.press('Enter');
  await expect.poll(() => page.evaluate(() => window.bandInvocations)).toBe(1);
  await expect.poll(() => page.locator('.folder-toggle').count()).toBe(0);
}, 30_000);
it('collection band keeps its identity and answer when another source arrives and restores its opener on every close', async () => {
  const page = await open();
  await page.locator('.bottom-links summary').filter({ hasText: 'Added by other apps' }).click();
  const opener = page.locator('jf-button[label="Remove mcp-ingest"] button');
  await opener.focus(); await page.keyboard.press('Enter');
  const band = page.locator('.other-source-confirm jf-confirm-band');
  await expect.poll(() => band.locator('jf-button.keep button').evaluate(el => el.matches(':focus'))).toBe(true);
  const original = await band.elementHandle();
  await page.keyboard.press('Shift+Tab'); await page.evaluate(() => window.updateListBands());
  expect(await band.evaluate((el, old) => el === old, original)).toBe(true);
  expect(await band.locator('jf-button.confirm button').evaluate(el => el.matches(':focus'))).toBe(true);
  await page.keyboard.press('Escape'); await expect.poll(() => band.count()).toBe(0);
  expect(await opener.evaluate(el => el.matches(':focus'))).toBe(true);
  await page.keyboard.press('Enter'); await band.locator('jf-button.keep').click();
  await expect.poll(() => band.count()).toBe(0);
  expect(await opener.evaluate(el => el.matches(':focus'))).toBe(true);
  await page.keyboard.press('Enter'); await band.locator('jf-button.confirm').click();
  await expect.poll(() => band.count()).toBe(0);
  expect(await opener.evaluate(el => el.matches(':focus'))).toBe(true);
}, 30_000);
it('failed-file band stays with its file across refreshes and errors and restores its opener', async () => {
  const page = await open();
  await page.evaluate(() => window.updateFailedBands(false));
  await page.locator('jf-failed-jobs-drawer').evaluate(el => {
    const drawer = el as HTMLElement & { rows: { pathHash: string }[] };
    drawer.rows = drawer.rows.filter(row => row.pathHash === 'target');
  });
  const action = page.locator('jf-row-actions[row-key="target"] jf-action-button');
  const opener = action.locator('button.invoke');
  await opener.focus(); await page.keyboard.press('Enter');
  const band = action.locator('jf-confirm-band');
  await expect.poll(() => band.locator('jf-button.keep button').evaluate(el => el.matches(':focus'))).toBe(true);
  const original = await band.elementHandle();
  await page.keyboard.press('Shift+Tab');
  for (const failure of [false, true, false]) {
    await page.evaluate(failure => window.updateFailedBands(failure), failure);
    expect(await original!.evaluate(el => el.isConnected), 'the open band stays attached to its file').toBe(true);
    expect(await action.evaluate((el, old) => el.shadowRoot?.querySelector('jf-confirm-band') === old, original),
      'the same band still belongs to the target file').toBe(true);
    expect(await band.locator('jf-button.confirm button').evaluate(el => el.matches(':focus'))).toBe(true);
  }
  await page.keyboard.press('Escape'); await expect.poll(() => band.count()).toBe(0);
  expect(await opener.evaluate(el => el.matches(':focus'))).toBe(true);
  await page.keyboard.press('Enter'); await band.locator('jf-button.keep').click();
  await expect.poll(() => band.count()).toBe(0);
  expect(await opener.evaluate(el => el.matches(':focus'))).toBe(true);
  await page.keyboard.press('Enter'); await band.locator('jf-button.confirm').click();
  await expect.poll(() => band.count()).toBe(0);
  // The band closes when invocation starts; focus returns once the pending action re-enables its opener.
  await page.waitForFunction(el => el?.matches(':enabled'), await opener.elementHandle());
  await expect.poll(() => opener.evaluate(el => el.matches(':focus'))).toBe(true);
}, 30_000);
