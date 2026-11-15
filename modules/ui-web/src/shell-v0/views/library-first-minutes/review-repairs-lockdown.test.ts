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
/** The real Shell with Library hosting Browse, over a stubbed backend: many folders, so the tree scrolls. */
const appHarness = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
const json = body => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
const names = Array.from({ length: 40 }, (_, i) => 'Folder ' + String(i).padStart(2, '0'));
window.fetch = async input => {
  const url = String(input?.url ?? input);
  if (url.includes('/api/indexing/roots')) return json({ roots: names.map(name => ({ path: '/' + name, fileCount: 3, fileCountCapped: false })) });
  if (url.includes('/api/indexing-roots/substrate')) return json({ items: [], count: 0 });
  if (url.endsWith('/folders')) return json({ folders: [], truncated: false });
  return json({ entries: [], files: [] });
};
window.EventSource = class { constructor() { this.readyState = 0; } addEventListener() {} removeEventListener() {} close() {} };
const { __seedForTest } = await import('/src/api/registry/SurfaceCatalogClient.ts');
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/chrome/Shell.ts');
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL', presentation: { labelKey: id, descriptionKey: id },
  consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] }, provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('core.search-v3-surface', 'jf-sv3-window'),
  surface('core.library-surface', 'jf-library-surface', { members: ['core.browse-surface'] }),
  surface('core.browse-surface', 'jf-browse-surface', { placement: 'DEEPLINK' }),
  surface('core.settings-surface', 'jf-settings-surface', { placement: 'MODAL' }),
] });
const ai = await import('/src/shell-v0/state/aiStateStore.ts');
const observe = () => {
  ai.__feedForTest({ status: { worker: { core: { indexState: 'IDLE', indexHealthy: true, indexedDocuments: 120, searchableDocuments: 120, ownSearchableDocuments: 120, pendingJobs: 0 },
    migration: { migrationState: 'IDLE' } } } });
  ai.__feedContactForTest();
};
document.body.append(document.createElement('jf-shell'));
observe(); setInterval(observe, 1_000);
document.body.dataset.ready = 'true';
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
        if (req.url?.startsWith('/review-repairs-app.html')) { res.setHeader('Content-Type', 'text/html'); res.end(appHarness); return; }
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

/** The innermost element at a viewport point, through open shadow roots; the page's own hit test. */
function hitPath(x: number, y: number): Element[] {
  const path: Element[] = [];
  let top = document.elementFromPoint(x, y);
  while (top) {
    path.push(top);
    const inner = top.shadowRoot?.elementFromPoint(x, y);
    if (!inner || inner === top) break;
    top = inner;
  }
  return path;
}
async function browseInLibrary(theme: string): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: 1100, height: 560 } });
  page.setDefaultTimeout(5000);
  page.on('pageerror', error => console.info('pageerror', error.message));
  await page.goto(origin + 'review-repairs-app.html#justsearch://surface/core.library-surface', { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await page.evaluate(value => { document.documentElement.dataset.theme = value; }, theme);
  await page.locator('jf-surface-tabs [role="tab"][data-tab-id="core.browse-surface"]').click();
  await expect.poll(() => page.locator('jf-browse-surface [role="treeitem"]').count(), { timeout: 10_000 }).toBe(40);
  await expect.poll(() => page.locator('jf-indexing-controls #pause-toggle').getAttribute('aria-disabled'), { timeout: 10_000 }).toBe('false');
  return page;
}
it.each(['light', 'dark'])('P2: on Browse, the Pause dropdown and the More menu open above the column heads (%s)', async theme => {
  const page = await browseInLibrary(theme);
  try {
    await page.locator('jf-indexing-controls #pause-toggle').click();
    const options = page.locator('jf-indexing-controls #pause-options');
    expect(await options.count()).toBe(1);
    const heads = page.locator('jf-browse-surface .column-heads');
    expect(await heads.count()).toBe(1);
    const [box, head] = [await options.boundingBox(), await heads.boundingBox()];
    expect(box, 'dropdown box').not.toBeNull(); expect(head, 'column heads box').not.toBeNull();
    expect(box!.y < head!.y + head!.height && box!.y + box!.height > head!.y, 'the dropdown reaches over the column heads').toBe(true);
    // Every point of the dropdown that lies over the heads, and its centre, hit-tests to the dropdown.
    const over = await page.evaluate(({ hitPathSource, box, head }) => {
      const hit = new Function(`return (${hitPathSource})`)() as (x: number, y: number) => Element[];
      const options = document.querySelector('jf-shell')!.shadowRoot!.querySelector('jf-stage')!.shadowRoot!
        .querySelector('jf-library-surface')!.shadowRoot!.querySelector('jf-indexing-controls')!.shadowRoot!.querySelector('#pause-options')!;
      const points: [number, number][] = [[box.x + box.width / 2, box.y + box.height / 2]];
      for (let y = Math.max(box.y, head.y) + 1; y < Math.min(box.y + box.height, head.y + head.height) - 1; y += 3) points.push([box.x + box.width / 2, y]);
      return points.map(([x, y]) => { const path = hit(x, y); const last = path[path.length - 1]; return { y, owner: last && options.contains(last) ? 'dropdown' : (last?.className || last?.localName) ?? 'none' }; });
    }, { hitPathSource: hitPath.toString(), box: box!, head: head! });
    expect(over.filter(point => point.owner !== 'dropdown'), JSON.stringify(over)).toEqual([]);
    await page.keyboard.press('Escape');
    await page.locator('jf-library-surface .more-trigger').click();
    const menu = page.locator('jf-library-surface jf-menu [role="menu"]');
    expect(await menu.count()).toBe(1);
    const menuBox = await menu.boundingBox();
    expect(menuBox, 'menu box').not.toBeNull();
    const menuHit = await page.evaluate(({ hitPathSource, x, y }) => {
      const hit = new Function(`return (${hitPathSource})`)() as (x: number, y: number) => Element[];
      // The menu's items are its light-DOM children: walk the composed ancestors of the innermost hit.
      const path = hit(x, y);
      for (let node: Node | null = path[path.length - 1] ?? null; node; node = node.parentNode ?? (node as ShadowRoot).host ?? null) {
        if ((node as Element).localName === 'jf-menu') return 'menu';
      }
      return path.map(el => `${el.localName}.${el.className}`).join(' > ');
    }, { hitPathSource: hitPath.toString(), x: menuBox!.x + menuBox!.width / 2, y: menuBox!.y + menuBox!.height / 2 });
    expect(menuHit, 'the More menu is above the Browse tree').toBe('menu');
  } finally { await page.close(); }
}, 120_000);
it.each(['light', 'dark'])('P2: a scrolled Browse tree shows no row above the bottom of its column heads (%s)', async theme => {
  const page = await browseInLibrary(theme);
  try {
    const result = await page.evaluate(async hitPathSource => {
      const hit = new Function(`return (${hitPathSource})`)() as (x: number, y: number) => Element[];
      const browse = document.querySelector('jf-shell')!.shadowRoot!.querySelector('jf-stage')!.shadowRoot!
        .querySelector('jf-library-surface')!.shadowRoot!.querySelector('jf-surface-tabs')!.shadowRoot!.querySelector('jf-browse-surface')!;
      const body = browse.shadowRoot!.querySelector<HTMLElement>('.body')!;
      const heads = browse.shadowRoot!.querySelector<HTMLElement>('.column-heads')!;
      const scrollable = body.scrollHeight > body.clientHeight + 100;
      body.scrollTop = 300;
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      const top = body.getBoundingClientRect().top, bottom = heads.getBoundingClientRect().bottom;
      const left = heads.getBoundingClientRect().left, right = heads.getBoundingClientRect().right;
      const rows: string[] = [];
      for (let y = top + 0.5; y < bottom - 0.5; y += 1) {
        for (const x of [left + 4, (left + right) / 2, right - 4]) {
          const row = hit(x, y).find(el => el.getAttribute('role') === 'treeitem' || el.classList.contains('row'));
          if (row) rows.push(`${Math.round(y - top)}px: ${row.getAttribute('data-path') ?? row.className}`);
        }
      }
      // The heads and the band above them paint the colour behind the tree, so they hide rows and nothing else.
      const painted = (node: Element | null): string => {
        for (; node; node = node.parentElement ?? ((node.getRootNode() as ShadowRoot).host ?? null)) {
          const colour = getComputedStyle(node).backgroundColor;
          if (colour !== 'rgba(0, 0, 0, 0)' && colour !== 'transparent') return colour;
        }
        return 'none';
      };
      const colours = { behind: painted(body), heads: getComputedStyle(heads).backgroundColor, band: getComputedStyle(heads, '::before').backgroundColor };
      return { colours, scrollable, scrolled: body.scrollTop, headsTop: Math.round(heads.getBoundingClientRect().top - top), headsHeight: Math.round(bottom - heads.getBoundingClientRect().top), rows };
    }, hitPath.toString());
    expect(result.scrollable, JSON.stringify(result)).toBe(true);
    expect(result.scrolled).toBeGreaterThan(0);
    expect(result.rows, JSON.stringify(result)).toEqual([]);
  } finally { await page.close(); }
}, 120_000);
