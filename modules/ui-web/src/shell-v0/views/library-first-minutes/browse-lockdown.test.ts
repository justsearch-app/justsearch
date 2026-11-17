// SPDX-License-Identifier: Apache-2.0
import { afterAll, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

let server: ViteDevServer, browser: Browser, scratch: string, origin: string;
const harness = `<!doctype html><html lang="en"><head><style>html,body { height: 100%; margin: 0; }</style><link rel="stylesheet" href="/src/styles/tokens.css"></head><body>
<script type="module">
import { BrowseSurface } from '/src/shell-v0/views/BrowseSurface.ts';
const el = new BrowseSurface();
el.host_ = { data: { fetch: (url, init) => fetch(url, init) }, platform: { capabilities: new Set(['reveal-in-explorer']), revealInExplorer: async () => {} }, ui: {} };
window.opened = 0;
el.openFile = async () => { window.opened++; };
document.body.append(el);
</script></body></html>`;
beforeAll(async () => {
  scratch = await mkdtemp(path.join(tmpdir(), 'browse-browser-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: scratch,
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{ name: 'browse-fixture', configureServer(vite) {
      vite.middlewares.use((req, res, next) => {
        if (!req.url?.startsWith('/browse.html')) return next();
        res.setHeader('Content-Type', 'text/html'); res.end(harness);
      });
    } }],
  });
  await server.listen();
  expect(server.resolvedUrls).not.toBeNull();
  expect(server.resolvedUrls!.local[0]).toBeDefined(); origin = server.resolvedUrls!.local[0]!;
  browser = await chromium.launch({ headless: true, args: ['--disable-gpu'] });
}, 30_000);
afterAll(async () => { await browser?.close(); await server?.close(); if (scratch) await rm(scratch, { recursive: true, force: true }); });
async function open(failed = false): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: 1024, height: 700 } });
  page.setDefaultTimeout(3000);
  await page.route(url => new URL(url).pathname.startsWith('/api/'), async route => {
    const url = route.request().url();
    if (url.includes('/indexing/roots')) return route.fulfill({ json: { roots: [{ path: '/Documents', fileCount: 50000, fileCountCapped: true }] } });
    if (failed) return route.fulfill({ status: 503, json: {} });
    if (url.endsWith('/folders')) return route.fulfill({ json: { folders: [], truncated: false } });
    return route.fulfill({ json: { totalCount: 2, files: [
      { docId: '/Documents/a.csv', fields: { path: '/Documents/a.csv', filename: 'a.csv', file_kind: 'text', size_bytes: '0', modified_at: '946684800000' } },
      { docId: '/Documents/b.eml', fields: { path: '/Documents/b.eml', filename: 'b.eml', file_kind: 'binary', size_bytes: '90' } },
    ] } });
  });
  page.on('pageerror', error => { throw error; });
  await page.goto(origin + 'browse.html', { timeout: 45_000 });
  await expect.poll(() => page.getByRole('treeitem').first().isVisible(), { timeout: 3000 }).toBe(true); return page;
}
it('the browser tree has real roving focus, stable row geometry, reachable actions and a shared keyboard menu', async () => {
  const page = await open();
  try {
    const root = page.getByRole('treeitem').first(); await root.focus(); await page.keyboard.press('ArrowRight');
    const file = page.locator('.row.file').first();
    await expect.poll(() => file.isVisible(), { timeout: 3000 }).toBe(true);
    expect(await root.innerText()).toContain('50,000+ files');
    // PE1/PE2: Browse's Size column writes sizes as everywhere else ('0 B', '90 B'), never 'bytes'.
    expect(await file.innerText()).toContain('0 B'); expect(await file.innerText()).toContain('2000');
    expect(await page.locator('.row.file').nth(1).innerText()).toContain('90 B');
    expect(await page.locator('.row.file').allInnerTexts()).not.toContainEqual(expect.stringContaining('bytes'));
    expect(await page.locator('jf-browse-surface [style]').count()).toBe(0);
    await page.keyboard.press('ArrowRight');
    expect(await file.evaluate(el => el === (el.getRootNode() as ShadowRoot).activeElement)).toBe(true);
    expect(await file.getAttribute('aria-level')).toBe('2'); expect(await file.getAttribute('aria-posinset')).toBe('1');
    expect(await file.getAttribute('aria-setsize')).toBe('2');
    const bounds = await file.boundingBox(); expect(bounds).not.toBeNull(); const height = bounds!.height;
    await file.hover(); const hoveredBounds = await file.boundingBox(); expect(hoveredBounds).not.toBeNull();
    expect(hoveredBounds!.height).toBe(height);
    await page.keyboard.press('Tab');
    const action = page.getByRole('button', { name: 'Open file', exact: true }).first();
    expect(await action.count()).toBeGreaterThan(0);
    expect(await action.evaluate(el => el === (el.getRootNode() as ShadowRoot).activeElement)).toBe(true);
    await page.keyboard.press('Enter'); expect(await page.evaluate(() => (window as unknown as { opened: number }).opened)).toBe(1);
    await file.focus(); await page.keyboard.press('Shift+F10');
    await expect.poll(() => page.getByRole('menu', { name: 'File actions' }).isVisible(), { timeout: 3000 }).toBe(true);
    expect(await page.getByRole('menuitem').allTextContents()).toEqual(expect.arrayContaining([expect.stringContaining('Open file'), expect.stringContaining('Show in folder')]));
    await page.keyboard.press('Escape'); expect(await page.getByRole('menu').count()).toBe(0);
    expect(await file.evaluate(el => el === (el.getRootNode() as ShadowRoot).activeElement)).toBe(true);
    await page.keyboard.press('ArrowLeft'); expect(await root.evaluate(el => el === (el.getRootNode() as ShadowRoot).activeElement)).toBe(true);
    await page.keyboard.press('ArrowLeft'); expect(await page.locator('.row.file').count()).toBe(0);
    expect(await page.locator('[role="treeitem"][tabindex="0"]').count()).toBe(1);
  } finally { await page.close(); }
}, 20_000);
it('a failed browser load is a recoverable failure rather than an empty folder', async () => {
  const page = await open(true);
  try {
    await page.getByRole('treeitem').first().click();
    await expect.poll(() => page.getByRole('button', { name: 'Retry Documents', exact: true }).isVisible(), { timeout: 3000 }).toBe(true);
    expect(await page.locator('body').innerText()).not.toContain('No searchable files in this folder');
  } finally { await page.close(); }
}, 15_000);
