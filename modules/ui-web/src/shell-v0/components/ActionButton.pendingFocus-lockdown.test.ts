// SPDX-License-Identifier: Apache-2.0
// Real native input against production components, private Vite and mock HTTP only.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

let server: ViteDevServer, browserServer: BrowserServer, browser: Browser;
let context: BrowserContext | undefined;
let output: string, url: string;
declare global {
 interface Window {
  setupPendingFixture(embedded: boolean): Promise<void>;
  updatePendingFixture(): Promise<void>;
 }
}
beforeAll(async () => {
  output = await mkdtemp(path.join(tmpdir(), 'action-pending-focus-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: output, publicDir: false,
    logLevel: 'silent', server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'pending-focus-fixture',
      configureServer(vite) { vite.middlewares.use('/pending-focus', (_req, res) => {
        res.setHeader('Content-Type', 'text/html');
        res.end(`<!doctype html><html><head><meta charset="utf-8">
          <link rel="stylesheet" href="/src/styles/tokens.css">
          <link rel="stylesheet" href="/src/shell-v0/themes/default.css"></head><body>
          <button id="outside">Outside</button><div id="blank" style="height:80px">Blank page space</div>
          <script type="module">
          import '/src/shell-v0/views/LibrarySurface.ts';
          import '/src/shell-v0/components/FailedFilesList.ts';
          import { __feedForTest } from '/src/shell-v0/state/aiStateStore.ts';
          import { createMockHostApi } from '/src/shell-v0/plugin-api/testHostApi.ts';
          import { __seedForTest as seedOperations } from '/src/api/registry/OperationCatalogClient.ts';
          import { __seedForTest as seedResources } from '/src/api/registry/ResourceCatalogClient.ts';
          const settle = async () => { for (let i = 0; i < 4; i++) await new Promise(requestAnimationFrame); };
          const roots = n => [{ pathHash: 'folder', collection: 'default', fileCount: 12+n,
            failedCount: 2, walkCompleted: true }];
          const feed = n => __feedForTest({ status: { worker: { core: { indexState: n%2 ? 'INDEXING' : 'IDLE',
            indexedDocuments: 12+n, indexHealthy: true }, migration: { migrationState: 'IDLE' } },
            readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } });
          let library, generation = 0;
          window.setupPendingFixture = async embedded => {
            feed(0);
            if (embedded) {
              library = document.createElement('jf-library-surface');
              library.host_ = createMockHostApi({ data: { fetch: async path => path.startsWith('/api/indexing-roots/substrate')
                ? new Response(JSON.stringify({ items: roots(generation) })) : fetch(path) } });
              library.resolvedPaths = { folder: '/home/test/Notes' }; document.body.append(library);
            } else {
              const list = document.createElement('jf-failed-files-list'); list.folderPathHash = 'folder';
              document.body.append(list);
            }
            await settle();
          };
          window.updatePendingFixture = async () => {
            generation++; feed(generation);
            if (library) library.roots = roots(generation);
            await settle();
          };
          seedOperations({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Operation',
            entries: [{ id: 'core.cancel-indexing-job', policy: { risk: 'MEDIUM', confirm: { kind: 'INLINE' } },
              presentation: { labelKey: 'Cancel file', descriptionKey: '' }, lineage: { affects: [], supersedes: [] } }] });
          seedResources({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Resource',
            entries: [{ id: 'core.failed-indexing-jobs', primaryKey: 'pathHash', privacy: { pathPolicy: 'NO_PATHS', resolver: null, loopbackOnly: true }, itemOperations: ['core.cancel-indexing-job'] }] });
          document.body.dataset.ready = 'true';
          </script></body></html>`);
      }); },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/pending-focus`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned pending-focus Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
}, 60_000);
afterEach(async () => { await context?.close(); context = undefined; });
afterAll(async () => {
  await browser?.close(); await browserServer?.close(); await server?.close();
  if (output) await rm(output, { recursive: true, force: true });
});
const row = (pathHash: string) => ({ pathHash, state: 'FAILED', attempts: 3, lastUpdatedMs: 1700000000000,
  errorMessage: '', retryAfterMs: 0, collection: 'default', scanId: '' });
async function open(embedded = false, status = 200) {
  context = await browser.newContext();
  const page = await context.newPage(); page.setDefaultTimeout(3000);
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  let release!: () => void, requests = 0;
  const gate = new Promise<void>(resolve => { release = resolve; });
  let jobs = [row('target'), row('after')];
  await page.route(new URL('/api/**', url).href, async route => {
    const request = route.request();
    if (request.url().includes('core.cancel-indexing-job') && request.method() === 'POST') {
      requests++; await gate;
      await route.fulfill({ status, contentType: 'application/json', body: status === 200
        ? '{"success":true,"message":"Done"}' : '{"error":"Unavailable"}' });
    } else await route.fulfill({ status: 200, contentType: 'application/json',
      body: request.url().includes('/failed/by-prefix') ? JSON.stringify({ jobs, count: jobs.length }) : '{}' });
  });
  await page.goto(url, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ state: 'attached', timeout: 45_000 });
  await page.evaluate(embedded => window.setupPendingFixture(embedded), embedded);
  if (embedded) await page.locator('.folder-toggle').click();
  const list = page.locator('jf-failed-files-list');
  const action = list.locator('jf-row-actions[row-key="target"] jf-action-button');
  const opener = action.locator('button.invoke');
  await opener.waitFor({ state: 'visible' });
  const frames = async () => page.evaluate(async () => {
    for (let i = 0; i < 5; i++) await new Promise(requestAnimationFrame);
  });
  const focus = () => opener.evaluate(el => el.matches(':focus'));
  const begin = async (keyboard = false) => {
    await opener.focus(); await page.keyboard.press('Enter');
    const band = action.locator('jf-confirm-band');
    await expect.poll(() => band.locator('jf-button.keep button').evaluate(el => el.matches(':focus'))).toBe(true);
    if (keyboard) { await page.keyboard.press('Shift+Tab'); await page.keyboard.down('Enter'); }
    else await band.locator('jf-button.confirm button').click();
    await expect.poll(() => requests).toBe(1);
    await expect.poll(() => opener.isDisabled()).toBe(true);
    await frames();
  };
  const finish = async () => {
    release(); await expect.poll(() => opener.isEnabled()).toBe(true); await frames();
    expect(requests).toBe(1); expect(errors).toEqual([]);
  };
  const refresh = async (hashes: string[]) => {
    jobs = hashes.map(row);
    await list.evaluate(el => { const list = el as HTMLElement & { observedCount: number }; list.observedCount++; });
    await expect.poll(() => list.locator('jf-row-actions').count()).toBe(hashes.length);
    await frames();
  };
  return { page, list, action, opener, begin, finish, frames, focus, refresh, requests: () => requests, release };
}
it.each([200, 503])('delayed HTTP %s returns focus after automatic list fallback', async status => {
  const f = await open(false, status); await f.begin();
  expect(await f.focus()).toBe(false);
  await f.finish(); expect(await f.focus()).toBe(true);
});
it.each([false, true])('pending identity and stable final focus through fresh rows and status updates (Library=%s)', async embedded => {
  const f = await open(embedded); const original = await f.opener.elementHandle(); await f.begin();
  await f.refresh(['inserted', 'after', 'target']);
  await f.page.evaluate(() => window.updatePendingFixture());
  await f.list.evaluate(el => {
    const list = el as HTMLElement & { loading: boolean; error: string | null };
    list.loading = true; list.error = 'Offline';
  }); await f.frames();
  await f.list.evaluate(el => {
    const list = el as HTMLElement & { loading: boolean; error: string | null };
    list.loading = false; list.error = null;
  }); await f.frames();
  expect(await f.opener.evaluate((el, old) => el === old, original)).toBe(true);
  await f.finish(); expect(await f.focus()).toBe(true);
  await f.page.evaluate(() => window.updatePendingFixture()); await f.frames();
  expect(await f.focus()).toBe(true);
});
it.each(['Tab', 'Shift+Tab', 'pointer-control', 'pointer-blank'])('later deliberate %s relinquishes completion focus', async gesture => {
  const f = await open(); await f.begin();
  if (gesture === 'pointer-control') await f.page.locator('#outside').click();
  else if (gesture === 'pointer-blank') await f.page.locator('#blank').click();
  else await f.page.keyboard.press(gesture);
  const chosen = await f.page.evaluateHandle(() => {
    let el = document.activeElement; while (el?.shadowRoot?.activeElement) el = el.shadowRoot.activeElement; return el;
  });
  await f.finish(); expect(await f.focus()).toBe(false);
  expect(await chosen.evaluate(el => el === (() => {
    let active = document.activeElement; while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
    return active;
  })())).toBe(true);
});
it.each(['inert', 'held-enter'])('%s input keeps the claim and invokes once', async gesture => {
  const f = await open(); await f.begin(gesture === 'held-enter');
  if (gesture === 'held-enter') { await f.page.keyboard.down('Enter'); await f.page.keyboard.up('Enter'); }
  else { await f.page.keyboard.press('Shift'); await f.page.keyboard.press('a'); }
  await f.finish(); expect(await f.focus()).toBe(true); expect(f.requests()).toBe(1);
  expect(await f.action.locator('jf-confirm-band').count()).toBe(0);
});
it.each(['row-leaves', 'folder-switch', 'disconnect', 'hidden', 'collapse', 'reconnect-after-detach', 'hide-then-show', 'collapse-then-expand'])('%s invalidates obsolete completion focus', async change => {
  const f = await open(); await f.begin(); const old = await f.opener.elementHandle();
  if (change === 'row-leaves') await f.refresh(['after']);
  else if (change === 'folder-switch') await f.list.evaluate(el => {
    (el as HTMLElement & { folderPathHash: string }).folderPathHash = 'other-folder';
  });
  else if (change === 'disconnect' || change === 'reconnect-after-detach') {
    const list = await f.list.elementHandle(); await list!.evaluate(el => el.remove()); await f.frames();
    if (change === 'reconnect-after-detach') await list!.evaluate(el => document.body.append(el));
  }
  else if (change === 'hidden' || change === 'hide-then-show') {
    await f.action.evaluate(el => { (el as HTMLElement).hidden = true; }); await f.frames();
    if (change === 'hide-then-show') await f.action.evaluate(el => { (el as HTMLElement).hidden = false; });
  }
  else if (change === 'collapse' || change === 'collapse-then-expand') {
    await f.list.evaluate(el => { (el as HTMLElement & { collapsed: boolean }).collapsed = true; }); await f.frames();
    if (change === 'collapse-then-expand') await f.list.evaluate(el => { (el as HTMLElement & { collapsed: boolean }).collapsed = false; });
  }
  await f.frames(); f.release();
  await expect.poll(() => old!.evaluate(el => !(el as HTMLButtonElement).disabled)).toBe(true); await f.frames();
  expect(await old!.evaluate(el => el.matches(':focus'))).toBe(false);
  if (change === 'row-leaves') expect(await f.list.locator('jf-row-actions[row-key="after"] button.invoke').evaluate(el => el.matches(':focus'))).toBe(true);
});
it('a competing band in a different shadow root owns focus even after cancellation', async () => {
  const f = await open(true); await f.begin();
  const other = f.list.locator('jf-row-actions[row-key="after"] button.invoke');
  await other.click(); const band = f.list.locator('jf-row-actions[row-key="after"] jf-confirm-band');
  await expect.poll(() => band.locator('jf-button.keep button').evaluate(el => el.matches(':focus'))).toBe(true);
  await f.page.keyboard.press('Escape');
  await expect.poll(() => other.evaluate(el => el.matches(':focus'))).toBe(true);
  await f.finish(); expect(await other.evaluate(el => el.matches(':focus'))).toBe(true);
});
it('Escape and Keep it cancel without HTTP and permit a later confirmation', async () => {
  const f = await open();
  for (const cancel of ['Escape', 'Keep it']) {
    await f.opener.focus(); await f.page.keyboard.press('Enter');
    const band = f.action.locator('jf-confirm-band');
    await expect.poll(() => band.locator('jf-button.keep button').evaluate(el => el.matches(':focus'))).toBe(true);
    if (cancel === 'Escape') await f.page.keyboard.press('Escape'); else await band.locator('jf-button.keep button').click();
    await expect.poll(f.focus).toBe(true); expect(f.requests()).toBe(0);
  }
  await f.begin(); await f.finish(); expect(await f.focus()).toBe(true);
});
it('ancestor inline visibility hidden then shown invalidates the pending claim', async () => {
  const f = await open(true); await f.begin();
  await f.list.evaluate(el => { (el as HTMLElement).style.visibility = 'hidden'; });
  await f.frames(); expect(await f.opener.isVisible()).toBe(false);
  await f.list.evaluate(el => { (el as HTMLElement).style.visibility = 'visible'; });
  await f.frames(); expect(await f.opener.isVisible()).toBe(true);
  await f.finish(); expect(await f.focus()).toBe(false);
});
it.each(['Enter', 'Space'].flatMap(key => [false, true].map(cancel => ({ key, cancel }))))(
  'fresh $key transfers ownership to the automatic fallback band (cancel=$cancel)', async ({ key, cancel }) => {
    const f = await open(); await f.begin();
    const other = f.list.locator('jf-row-actions[row-key="after"] button.invoke');
    expect(await other.evaluate(el => el.matches(':focus'))).toBe(true);
    await f.page.keyboard.press(key);
    const keep = f.list.locator('jf-row-actions[row-key="after"] jf-confirm-band jf-button.keep button');
    await expect.poll(() => keep.evaluate(el => el.matches(':focus'))).toBe(true);
    if (cancel) { await f.page.keyboard.press('Escape'); await expect.poll(() => other.evaluate(el => el.matches(':focus'))).toBe(true); }
    await f.finish(); expect(await f.focus()).toBe(false);
    expect(await (cancel ? other : keep).evaluate(el => el.matches(':focus'))).toBe(true);
  },
);
it.each([200, 503])('actual OpButton INLINE returns its surviving opener after HTTP %s', async status => {
  const f = await open(false, status);
  const errors: string[] = []; f.page.on('pageerror', error => errors.push(error.message));
  // Browser-native module loading: page.evaluate dynamic imports are rewritten by the test runner.
  await f.page.addScriptTag({ type: 'module', url: new URL('/src/shell-v0/components/OpButton.ts', url).href });
  await f.page.evaluate(() => {
    const op = document.createElement('jf-op-button');
    op.setAttribute('operation-id', 'core.cancel-indexing-job');
    op.setAttribute('api-base', location.origin); document.body.append(op);
  });
  const action = f.page.locator('jf-op-button jf-action-button'), opener = action.locator('button.invoke');
  await opener.waitFor({ state: 'visible' }); const original = await opener.elementHandle();
  await opener.focus(); await f.page.keyboard.press('Enter');
  const band = action.locator('jf-confirm-band');
  await expect.poll(() => band.locator('jf-button.keep button').evaluate(el => el.matches(':focus'))).toBe(true);
  await f.page.keyboard.press('Shift+Tab'); await f.page.keyboard.press('Enter');
  await expect.poll(f.requests).toBe(1); await expect.poll(() => opener.isDisabled()).toBe(true);
  expect(await action.evaluate(el => el.hasAttribute('disabled') && el.hasAttribute('pending'))).toBe(true);
  await f.frames(); expect(await f.page.evaluate(() => document.activeElement === document.body)).toBe(true);
  f.release(); await expect.poll(() => opener.isEnabled()).toBe(true); await f.frames();
  expect(await opener.evaluate((el, old) => el === old, original)).toBe(true);
  expect(await opener.evaluate(el => el.matches(':focus'))).toBe(true);
  expect(f.requests()).toBe(1); expect(errors).toEqual([]);
});
it('a programmatic activation click transfers ownership before the next band renders', async () => {
  const f = await open(); await f.begin();
  const other = f.list.locator('jf-row-actions[row-key="after"] button.invoke');
  expect(await other.evaluate(el => el.matches(':focus'))).toBe(true);
  await other.evaluate(el => (el as HTMLButtonElement).click());
  const keep = f.list.locator('jf-row-actions[row-key="after"] jf-confirm-band jf-button.keep button');
  await expect.poll(() => keep.evaluate(el => el.matches(':focus'))).toBe(true);
  await f.finish(); expect(await keep.evaluate(el => el.matches(':focus'))).toBe(true);
});
it('a removed file returning as a new row inherits no old completion claim', async () => {
  const f = await open(); await f.begin(); const old = await f.opener.elementHandle();
  await f.refresh(['after']); await f.refresh(['target', 'after']);
  expect(await f.opener.evaluate((el, old) => el === old, old)).toBe(false);
  const other = f.list.locator('jf-row-actions[row-key="after"] button.invoke');
  expect(await other.evaluate(el => el.matches(':focus'))).toBe(true);
  f.release(); await expect.poll(() => old!.evaluate(el => !(el as HTMLButtonElement).disabled)).toBe(true);
  await f.frames(); expect(await other.evaluate(el => el.matches(':focus'))).toBe(true);
  expect(await f.focus()).toBe(false); expect(f.requests()).toBe(1);
});
it.each(['Enter', ' '])('synthetic composing repeat %j remains uncancelled (narrow IME guard only)', async key => {
  const f = await open(); await f.begin(true);
  const uncancelled = await f.page.evaluate(key => document.dispatchEvent(new KeyboardEvent('keydown', {
    key, repeat: true, isComposing: true, bubbles: true, composed: true, cancelable: true,
  })), key);
  expect(uncancelled).toBe(true);
  await f.page.keyboard.up('Enter'); await f.page.locator('#outside').click();
  await f.finish(); expect(await f.page.locator('#outside').evaluate(el => el.matches(':focus'))).toBe(true);
});
