// SPDX-License-Identifier: Apache-2.0
import { afterAll, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

let server: ViteDevServer, browser: Browser, scratch: string, origin: string;
const harness = `<!doctype html><html lang="en"><body><script type="module">
import { BrowseSurface } from '/src/shell-v0/views/BrowseSurface.ts';
const el = new BrowseSurface();
window.fixture = { entries: ['keep.md', 'old.md'], reads: 0, hold: false, release: () => {} };
const fixture = window.fixture;
el.host_ = { data: { fetch: async (url, init) => {
  if (url.includes('/indexing/roots')) return Response.json({ roots: [{ path: '/Documents' }, { path: '/Documents/Nested' }] });
  const body = JSON.parse(init.body);
  if (url.endsWith('/folders')) return Response.json({ folders: body.parentPath === '/Documents' ? [{ path: '/Documents/Nested' }] : [] });
  fixture.reads++;
  if (fixture.hold) await new Promise(resolve => { fixture.release = resolve; });
  const parent = body.folderPath;
  return Response.json({ files: parent === '/Documents' ? [] : fixture.entries.map(name => ({ docId: parent + '/' + name, fields: { path: parent + '/' + name, filename: name } })) });
} }, platform: { capabilities: new Set() } };
document.body.append(el);
// A real browser resolves Response.json() on a task, not a microtask: settle lets tasks run too.
window.settle = async () => { for (let i = 0; i < 35; i++) { await new Promise(resolve => setTimeout(resolve)); await el.updateComplete; } };
</script></body></html>`;
const menuHarness = `<!doctype html><html lang="en"><body><button id="trigger">Open</button><span id="anchor"></span><button id="after">After</button>
<script type="module">
import '/src/shell-v0/components/Menu.ts';
window.closes = 0;
window.openMenu = async label => {
  const menu = document.createElement('jf-menu');
  const content = document.createDocumentFragment();
  for (const name of ['One', 'Two']) {
    const item = document.createElement('button');
    item.setAttribute('role', 'menuitem'); item.tabIndex = -1; item.textContent = label + ' ' + name; content.append(item);
  }
  menu.label = label; menu.trigger = document.getElementById('trigger'); menu.content = content;
  menu.addEventListener('menu-close', () => { window.closes++; menu.remove(); });
  document.getElementById('anchor').append(menu);
  await menu.updateComplete; await new Promise(resolve => setTimeout(resolve));
};
</script></body></html>`;
beforeAll(async () => {
  scratch = await mkdtemp(path.join(tmpdir(), 'review-repairs-browser-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: scratch,
    server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'review-repairs-fixture', configureServer(vite) {
      vite.middlewares.use((req, res, next) => {
        if (!req.url?.startsWith('/review-repairs.html') && !req.url?.startsWith('/review-repairs-menu.html')) return next();
        res.setHeader('Content-Type', 'text/html'); res.end(req.url.startsWith('/review-repairs-menu.html') ? menuHarness : harness);
      });
    } }],
  });
  await server.listen();
  expect(server.resolvedUrls).not.toBeNull(); expect(server.resolvedUrls!.local[0]).toBeDefined();
  origin = server.resolvedUrls!.local[0]!;
  browser = await chromium.launch({ headless: true, args: ['--disable-gpu'] });
}, 30_000);
afterAll(async () => { await browser?.close(); await server?.close(); if (scratch) await rm(scratch, { recursive: true, force: true }); });

type FixtureWindow = Window & { settle(): Promise<void>; fixture: { entries: string[]; reads: number; hold: boolean; release(): void } };
async function settle(page: Page) { await page.evaluate(() => (window as unknown as FixtureWindow).settle()); }
async function open() {
  const page = await browser.newPage();
  page.setDefaultTimeout(3000);
  await page.goto(origin + 'review-repairs.html');
  await page.waitForFunction(() => typeof Reflect.get(window, 'settle') === 'function');
  await settle(page);
  expect(await page.locator('[role="treeitem"]').count()).toBe(2);
  return page;
}
it('duplicate folder occurrences keep one real tree tab stop and keyboard navigation follows the focused occurrence', async () => {
  const page = await open();
  try {
    await page.locator('[data-path="/Documents"]').click(); await settle(page);
    const copies = page.locator('[data-path="/Documents/Nested"]');
    expect(await copies.count()).toBe(2);
    await copies.nth(1).focus(); await settle(page);
    expect(await page.locator('[role="treeitem"][tabindex="0"]').count()).toBe(1);
    await page.keyboard.press('ArrowUp'); await settle(page);
    expect(await copies.nth(0).evaluate(el => el === (el.getRootNode() as ShadowRoot).activeElement)).toBe(true);
    await page.keyboard.press('ArrowRight'); await settle(page);
    expect(await copies.nth(0).getAttribute('aria-expanded')).toBe('true');
    expect(await copies.nth(1).getAttribute('aria-expanded')).toBe('false');
    await copies.nth(1).focus(); await page.keyboard.press('ArrowLeft'); await settle(page);
    expect(await copies.nth(1).evaluate(el => el === (el.getRootNode() as ShadowRoot).activeElement)).toBe(true);
  } finally { await page.close(); }
});
it('a background child reload retains cached rows and real focus, then recovers to the correct parent if the focused file disappears', async () => {
  const page = await open();
  try {
    const folder = page.locator('[data-path="/Documents/Nested"]');
    expect(await folder.count()).toBe(1);
    await folder.click(); await settle(page); await folder.click(); await settle(page);
    await page.evaluate(() => { (window as unknown as FixtureWindow).fixture.hold = true; });
    await folder.click(); await settle(page);
    expect(await page.evaluate(() => (window as unknown as FixtureWindow).fixture.reads)).toBe(2);
    const keep = page.locator('[data-path="/Documents/Nested/keep.md"]');
    expect(await keep.count()).toBe(1);
    await keep.focus(); await settle(page);
    await page.evaluate(() => { const fixture = (window as unknown as FixtureWindow).fixture; fixture.entries = ['new.md', 'keep.md']; fixture.release(); });
    await settle(page);
    expect(await keep.evaluate(el => el === (el.getRootNode() as ShadowRoot).activeElement)).toBe(true);
    expect(await page.locator('[data-path="/Documents/Nested/old.md"]').count()).toBe(0);
    expect(await page.locator('[data-path="/Documents/Nested/new.md"]').count()).toBe(1);
    await folder.click(); await settle(page); await folder.click(); await settle(page);
    expect(await keep.count()).toBe(1); await keep.focus(); await settle(page);
    await page.evaluate(() => { const fixture = (window as unknown as FixtureWindow).fixture; fixture.entries = ['new.md']; fixture.release(); });
    await settle(page);
    expect(await folder.evaluate(el => el === (el.getRootNode() as ShadowRoot).activeElement)).toBe(true);
    expect(await page.locator('[role="treeitem"][tabindex="0"]').count()).toBe(1);
  } finally { await page.close(); }
});
it('V4: an open folder with nothing to say adds no band above the tree', async () => {
  const page = await open();
  try {
    const folder = page.locator('[data-path="/Documents/Nested"]');
    expect(await folder.count()).toBe(1);
    await folder.click(); await settle(page);
    expect(await page.locator('[data-path="/Documents/Nested/keep.md"]').count()).toBe(1);
    const notices = page.locator('.notice');
    expect(await notices.count()).toBe(1);
    expect((await notices.first().textContent())?.trim()).toBe('');
    expect(await notices.first().evaluate(el => el.getBoundingClientRect().height)).toBe(0);
  } finally { await page.close(); }
});
type MenuWindow = Window & { openMenu(label: string): Promise<void>; closes: number };
it('V8: moving focus out of an open menu closes it, by Shift+Tab or otherwise; one menu is open at a time', async () => {
  const page = await browser.newPage();
  page.setDefaultTimeout(3000);
  try {
    await page.goto(origin + 'review-repairs-menu.html');
    await page.waitForFunction(() => typeof Reflect.get(window, 'openMenu') === 'function');
    const open = (label: string) => page.evaluate(name => (window as unknown as MenuWindow).openMenu(name), label);
    const menus = page.locator('jf-menu');
    await open('First');
    expect(await menus.count()).toBe(1);
    expect(await page.evaluate(() => document.activeElement?.textContent)).toBe('First One');
    await page.keyboard.press('Shift+Tab');
    expect(await menus.count()).toBe(0);
    await open('First');
    expect(await menus.count()).toBe(1);
    await page.evaluate(() => document.getElementById('after')!.focus());
    expect(await menus.count()).toBe(0);
    await open('First'); await open('Second');
    expect(await menus.count()).toBe(1);
    expect(await page.evaluate(() => document.activeElement?.textContent)).toBe('Second One');
    // A press on the trigger moves focus there too, but its own click toggles (this harness has no
    // toggle): focus moving by pointer must not close it first and let that click reopen it.
    await page.locator('#trigger').click();
    expect(await menus.count()).toBe(1);
  } finally { await page.close(); }
});
