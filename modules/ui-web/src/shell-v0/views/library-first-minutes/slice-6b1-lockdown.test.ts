// SPDX-License-Identifier: Apache-2.0
// Slice 6b-1 in a real browser: the in-place Read everything again ask and the keyboard focus around it.
import { afterAll, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

let server: ViteDevServer, browser: Browser, scratch: string, origin: string;
/** Library over a stubbed backend with one watched folder; the read-again request is held until released. */
const harness = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
window.fetch = async () => new Response('{}', { status: 503, headers: { 'Content-Type': 'application/json' } });
const { createMockHostApi } = await import('/src/shell-v0/plugin-api/testHostApi.ts');
const { getOperationClient } = await import('/src/shell-v0/operations/OperationClient.ts');
const ai = await import('/src/shell-v0/state/aiStateStore.ts');
await import('/src/shell-v0/views/LibrarySurface.ts');
ai.__feedForTest({ status: { worker: { core: { indexedDocuments: 8, ownSearchableDocuments: 8, indexState: 'IDLE', indexHealthy: true },
  migration: { migrationState: 'IDLE' } }, readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } });
window.fixture = { invocations: 0, release: () => {} };
getOperationClient('').invokeWithConsent = () => {
  window.fixture.invocations++;
  return new Promise(resolve => { window.fixture.release = () => resolve({ success: true, message: 'Reading accepted',
    structuredData: { operationKey: '0194f72c-0000-7000-8000-000000000069' } }); });
};
const host = createMockHostApi({ data: { fetch: async url => url.startsWith('/api/indexing-roots/substrate')
  ? Response.json({ items: [{ pathHash: 'lockdown6b1', status: 'indexed', fileCount: 8, fileCountCapped: false, failedCount: 0, inFlightCount: 0, walkCompleted: true }] })
  : url.startsWith('/api/operation-history/') ? Response.json({ state: 'running', historySince: 0 }) : new Response('{}', { status: 503 }) } });
const el = document.createElement('jf-library-surface');
el.host_ = host; el.resolvedPaths = { lockdown6b1: '/Documents' };
document.body.append(el);
// A real browser resolves Response.json() on a task, not a microtask: settle lets tasks run too.
window.settle = async () => { for (let i = 0; i < 35; i++) { await new Promise(resolve => setTimeout(resolve)); await el.updateComplete; } };
window.focused = () => { let active = document.activeElement; while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
  return active === document.body || !active ? 'body' : active.hasAttribute('data-reread') ? 'action' : (active.textContent || '').trim(); };
</script></body></html>`;
beforeAll(async () => {
  scratch = await mkdtemp(path.join(tmpdir(), 'slice-6b1-browser-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: scratch,
    server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'slice-6b1-fixture', configureServer(vite) {
      vite.middlewares.use((req, res, next) => {
        if (!req.url?.startsWith('/slice-6b1.html')) return next();
        res.setHeader('Content-Type', 'text/html'); res.end(harness);
      });
    } }],
  });
  await server.listen();
  expect(server.resolvedUrls).not.toBeNull(); expect(server.resolvedUrls!.local[0]).toBeDefined();
  origin = server.resolvedUrls!.local[0]!;
  browser = await chromium.launch({ headless: true, args: ['--disable-gpu'] });
}, 30_000);
afterAll(async () => { await browser?.close(); await server?.close(); if (scratch) await rm(scratch, { recursive: true, force: true }); });

type FixtureWindow = Window & { settle(): Promise<void>; focused(): string; fixture: { invocations: number; release(): void } };
async function settle(page: Page) { await page.evaluate(() => (window as unknown as FixtureWindow).settle()); }
const focused = (page: Page) => page.evaluate(() => (window as unknown as FixtureWindow).focused());
const invocations = (page: Page) => page.evaluate(() => (window as unknown as FixtureWindow).fixture.invocations);
async function open(): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: 1024, height: 700 } });
  page.setDefaultTimeout(3000);
  const errors: Error[] = [];
  page.on('pageerror', error => { errors.push(error); });
  await page.goto(origin + 'slice-6b1.html');
  await page.waitForFunction(() => typeof Reflect.get(window, 'settle') === 'function');
  await settle(page);
  expect(errors).toEqual([]);
  expect(await page.getByRole('button', { name: 'Read everything again', exact: true }).count()).toBe(1);
  return page;
}
it('after confirming with the keyboard, focus stays on Read everything again through the delayed request and after it settles', async () => {
  const page = await open();
  try {
    const action = page.getByRole('button', { name: 'Read everything again', exact: true });
    await action.focus(); await page.keyboard.press('Enter'); await settle(page);
    const ask = page.getByRole('region', { name: 'Read every watched folder again?', exact: true });
    expect(await ask.count()).toBe(1);
    await ask.getByRole('button', { name: 'Read again', exact: true }).focus();
    await page.keyboard.press('Enter'); await settle(page);
    expect(await invocations(page)).toBe(1);
    expect(await ask.count()).toBe(0);
    expect(await focused(page)).toBe('action');
    expect(await action.getAttribute('aria-disabled')).toBe('true');
    // Blocked while in flight: activating it neither opens the ask nor asks the Engine again.
    await page.keyboard.press('Enter'); await page.keyboard.press(' '); await settle(page);
    expect(await ask.count()).toBe(0);
    expect(await invocations(page)).toBe(1);
    expect(await focused(page)).toBe('action');
    await page.evaluate(() => (window as unknown as FixtureWindow).fixture.release()); await settle(page);
    expect(await action.getAttribute('aria-disabled')).toBe('false');
    expect(await focused(page)).toBe('action');
    await page.keyboard.press('Enter'); await settle(page);
    expect(await ask.count()).toBe(1);
  } finally { await page.close(); }
});
it('Escape on either answer closes the ask, runs nothing and returns focus to Read everything again', async () => {
  const page = await open();
  try {
    const action = page.getByRole('button', { name: 'Read everything again', exact: true });
    const ask = page.getByRole('region', { name: 'Read every watched folder again?', exact: true });
    for (const answer of ['Read again', 'Cancel']) {
      await action.focus(); await page.keyboard.press('Enter'); await settle(page);
      expect(await ask.count()).toBe(1);
      await ask.getByRole('button', { name: answer, exact: true }).focus();
      expect(await focused(page)).toBe(answer);
      await page.keyboard.press('Escape'); await settle(page);
      expect(await ask.count()).toBe(0);
      expect(await focused(page)).toBe('action');
      expect(await action.getAttribute('aria-expanded')).toBe('false');
    }
    expect(await invocations(page)).toBe(0);
  } finally { await page.close(); }
});
it('Escape right after opening, with focus still on Read everything again, closes the ask and runs nothing', async () => {
  const page = await open();
  try {
    const action = page.getByRole('button', { name: 'Read everything again', exact: true });
    const ask = page.getByRole('region', { name: 'Read every watched folder again?', exact: true });
    await action.focus(); await page.keyboard.press('Enter'); await settle(page);
    expect(await ask.count()).toBe(1);
    expect(await focused(page)).toBe('action');
    await page.keyboard.press('Escape'); await settle(page);
    expect(await ask.count()).toBe(0);
    expect(await focused(page)).toBe('action');
    expect(await action.getAttribute('aria-expanded')).toBe('false');
    expect(await invocations(page)).toBe(0);
  } finally { await page.close(); }
});
