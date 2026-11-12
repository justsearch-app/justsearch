// SPDX-License-Identifier: Apache-2.0
// FA1 — the top bar's menus and its area switcher at small sizes, with the desktop window controls
// beside the bar: the real Shell, top bar and status pill over a private Vite server and headless
// Chromium. Bounds are read from the elements themselves, never from the document's scroll width
// (a menu that runs off the window's start edge does not widen the document).
// Run with npx vitest run --config vitest.config.lockdown.ts src/shell-v0/chrome/TopBar.placement-lockdown.test.ts
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, mkdir, readFile, rm } from 'node:fs/promises';
import path from 'node:path';

/** The desktop window layer's room rule, read from the app's own index.html rather than copied. */
async function windowControlsRule(): Promise<string> {
  const page = await readFile(path.resolve('index.html'), 'utf8');
  const rule = page.match(/:root\[data-window-controls\]\s*\{[^}]*\}/)?.[0];
  if (!rule) throw new Error('index.html no longer declares the window controls room');
  return rule;
}

function harness(rule: string): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"><style>${rule}</style></head><body><script type="module">
const params = new URLSearchParams(location.search);
if (params.has('controls')) document.documentElement.setAttribute('data-window-controls', '');
const json = (body) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
window.fetch = async () => json({ entries: [], conversations: [], messages: [] });
window.EventSource = class { constructor() { this.readyState = 0; } addEventListener() {} removeEventListener() {} close() {} };
const { __seedForTest } = await import('/src/api/registry/SurfaceCatalogClient.ts');
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/chrome/Shell.ts');
customElements.define('jf-placement-probe', class extends HTMLElement {});
const surface = (id, extra = {}) => ({ id, mountTag: 'jf-placement-probe', audience: 'USER', placement: 'RAIL',
  presentation: { labelKey: id, descriptionKey: id }, consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('core.search-v3-surface'), surface('core.library-surface'), surface('core.brain-surface'),
  surface('core.system-surface'), surface('core.help-surface', { placement: 'DEEPLINK' }),
  surface('core.settings-surface', { placement: 'MODAL' }),
  surface('vendor.notes-window', { provenance: { tier: 'PLUGIN', contributorId: 'vendor', version: '1.0.0' } }),
] });
document.body.append(document.createElement('jf-shell'));
document.body.dataset.ready = 'true';
</script></body></html>`;
}

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let context: BrowserContext | undefined;
let output: string;
let url: string;

beforeAll(async () => {
  const temporaryRoot = path.resolve('../..', 'tmp');
  await mkdir(temporaryRoot, { recursive: true });
  output = await mkdtemp(path.join(temporaryRoot, 'top-bar-placement-'));
  const page = harness(await windowControlsRule());
  server = await createServer({
    configFile: false, root: process.cwd(), cacheDir: output, publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{ name: 'placement-harness', configureServer(vite) {
      vite.middlewares.use((req, res, next) => {
        if (req.url?.split('?')[0] !== '/frame') return next();
        res.setHeader('Content-Type', 'text/html');
        res.end(page);
      });
    } }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/frame`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: process.env.TMPDIR ?? output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned headless Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
}, 60_000);

afterEach(async () => { await context?.close(); context = undefined; });
afterAll(async () => {
  await browser?.close();
  await browserServer?.close();
  await server?.close();
  if (output) await rm(output, { recursive: true, force: true });
});

interface Size { readonly width: number; readonly height: number; readonly text: number; readonly controls: boolean; readonly forced?: boolean }

async function open(size: Size): Promise<Page> {
  context = await browser.newContext({ viewport: { width: size.width, height: size.height } });
  const page = await context.newPage();
  page.on('pageerror', error => console.info('pageerror', error.message));
  if (size.forced) await page.emulateMedia({ forcedColors: 'active' });
  await page.goto(`${url}${size.controls ? '?controls=1' : ''}`, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await page.locator('jf-top-bar').waitFor();
  await page.evaluate(text => { document.documentElement.style.fontSize = `${text}%`; }, size.text);
  await settle(page);
  return page;
}

async function settle(page: Page, ms = 200): Promise<void> {
  await page.waitForTimeout(ms);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

interface Box { readonly left: number; readonly right: number; readonly top: number; readonly bottom: number }
interface MenuLayout { readonly window: number; readonly menu: Box; readonly trigger: Box; readonly clipped: readonly string[] }

/** The open top-bar menu, its button, and every row whose words do not fit inside it or the window. */
async function menuLayout(page: Page, trigger: string): Promise<MenuLayout> {
  return page.evaluate(selector => {
    const bar = document.querySelector('jf-shell')!.shadowRoot!.querySelector('jf-top-bar')!.shadowRoot!;
    const box = (el: Element) => { const r = el.getBoundingClientRect(); return { left: r.left, right: r.right, top: r.top, bottom: r.bottom }; };
    const menu = bar.querySelector('[role="menu"]')!;
    const outer = menu.getBoundingClientRect();
    const clipped = [...menu.querySelectorAll<HTMLElement>('[role^="menuitem"]')].filter(row => {
      const r = row.getBoundingClientRect();
      const words = [...row.querySelectorAll<HTMLElement>('.item-label, .item-detail, kbd')].map(el => el.getBoundingClientRect());
      return row.scrollWidth > row.clientWidth + 1 || r.left < outer.left - 0.5 || r.right > outer.right + 0.5
        || words.some(w => w.left < Math.max(0, outer.left) - 0.5 || w.right > Math.min(innerWidth, outer.right) + 0.5);
    }).map(row => row.textContent!.trim());
    return { window: innerWidth, menu: box(menu), trigger: box(bar.querySelector(selector)!), clipped };
  }, trigger);
}

async function openMenu(page: Page, trigger: string): Promise<MenuLayout> {
  await page.locator(`jf-top-bar ${trigger}`).click();
  await page.locator('jf-top-bar [role="menu"]').waitFor();
  await settle(page, 100);
  return menuLayout(page, trigger);
}

function inside(layout: MenuLayout): void {
  const where = JSON.stringify(layout);
  expect(layout.menu.left, where).toBeGreaterThanOrEqual(0);
  expect(layout.menu.right, where).toBeLessThanOrEqual(layout.window);
  expect(layout.clipped, where).toEqual([]);
}

const SMALL: Size = { width: 600, height: 400, text: 200, controls: true };

describe('FA1 the top bar at 600x400 with 200% text and the window controls', () => {
  it.each([false, true])('More opens entirely inside the window, its words not cut (forced colours: %s)', async forced => {
    const page = await open({ ...SMALL, forced });
    inside(await openMenu(page, '.more-trigger'));
  }, 120_000);

  it.each([false, true])('the area switcher and its menu fit inside the window (forced colours: %s)', async forced => {
    const page = await open({ ...SMALL, forced });
    const nav = await page.evaluate(() => {
      const bar = document.querySelector('jf-shell')!.shadowRoot!.querySelector('jf-top-bar')!.shadowRoot!;
      const parts = [bar.querySelector('nav')!, ...bar.querySelectorAll('nav button')].filter(el => el.getClientRects().length > 0);
      return { window: innerWidth, boxes: parts.map(el => { const r = el.getBoundingClientRect(); return { left: r.left, right: r.right }; }) };
    });
    for (const part of nav.boxes) {
      expect(part.left, JSON.stringify(nav)).toBeGreaterThanOrEqual(0);
      expect(part.right, JSON.stringify(nav)).toBeLessThanOrEqual(nav.window);
    }
    inside(await openMenu(page, '.area-trigger'));
  }, 120_000);

  it('the status panel opens inside the window', async () => {
    const page = await open(SMALL);
    await page.locator('jf-status-pill button.trigger').click();
    await page.locator('jf-status-pill jf-status-panel').waitFor();
    await settle(page, 200);
    const panel = await page.locator('jf-status-pill jf-status-panel').evaluate(el => {
      const r = el.getBoundingClientRect();
      return { left: r.left, right: r.right, window: innerWidth };
    });
    expect(panel.left, JSON.stringify(panel)).toBeGreaterThanOrEqual(0);
    expect(panel.right, JSON.stringify(panel)).toBeLessThanOrEqual(panel.window);
  }, 120_000);

  it('a menu opened in a small window follows the window when it grows and shrinks', async () => {
    const page = await open(SMALL);
    await openMenu(page, '.more-trigger');
    await page.setViewportSize({ width: 900, height: 800 });
    await settle(page);
    const wide = await menuLayout(page, '.more-trigger');
    inside(wide);
    expect(Math.abs(wide.menu.right - wide.trigger.right), JSON.stringify(wide)).toBeLessThanOrEqual(1);
    await page.setViewportSize({ width: 600, height: 400 });
    await settle(page);
    inside(await menuLayout(page, '.more-trigger'));
  }, 120_000);
});

/** The open status panel's edges and the window's width. */
async function panelBox(page: Page): Promise<{ left: number; right: number; window: number }> {
  return page.locator('jf-status-pill jf-status-panel').evaluate(el => {
    const r = el.getBoundingClientRect();
    return { left: r.left, right: r.right, window: innerWidth };
  });
}

describe('FA1a what is open while the window shrinks stays inside it', () => {
  it('the status panel opened at 900x800 stays inside as the window shrinks to 600x400 and the text grows to 200%', async () => {
    const page = await open({ width: 900, height: 800, text: 100, controls: true });
    await page.locator('jf-status-pill button.trigger').click();
    await page.locator('jf-status-pill jf-status-panel').waitFor();
    await settle(page);
    for (const step of ['shrink', 'text'] as const) {
      if (step === 'shrink') await page.setViewportSize({ width: 600, height: 400 });
      else await page.evaluate(() => { document.documentElement.style.fontSize = '200%'; });
      await settle(page);
      const panel = await panelBox(page);
      expect(panel.left, `${step} ${JSON.stringify(panel)}`).toBeGreaterThanOrEqual(0);
      expect(panel.right, `${step} ${JSON.stringify(panel)}`).toBeLessThanOrEqual(panel.window);
    }
  }, 120_000);

  it('More opened at 900x800 stays inside as the window shrinks to 600x400 and the text grows to 200%', async () => {
    const page = await open({ width: 900, height: 800, text: 100, controls: true });
    await openMenu(page, '.more-trigger');
    await page.setViewportSize({ width: 600, height: 400 });
    await settle(page);
    inside(await menuLayout(page, '.more-trigger'));
    await page.evaluate(() => { document.documentElement.style.fontSize = '200%'; });
    await settle(page);
    inside(await menuLayout(page, '.more-trigger'));
  }, 120_000);
});

describe('FA1 the larger windows keep their menus where they were', () => {
  it.each([[1280, 720], [900, 800]])('%ix%i: More hangs under its button, its end edge on the button\'s', async (width, height) => {
    for (const controls of [false, true]) {
      const page = await open({ width, height, text: 100, controls });
      const layout = await openMenu(page, '.more-trigger');
      inside(layout);
      expect(Math.abs(layout.menu.right - layout.trigger.right), JSON.stringify(layout)).toBeLessThanOrEqual(1);
      expect(layout.menu.top, JSON.stringify(layout)).toBeGreaterThan(layout.trigger.bottom);
      await context?.close(); context = undefined;
    }
  }, 120_000);

  it('600x400 at normal text: the area menu hangs under its button, its start edge on the button\'s', async () => {
    const page = await open({ width: 600, height: 400, text: 100, controls: false });
    const layout = await openMenu(page, '.area-trigger');
    inside(layout);
    expect(Math.abs(layout.menu.left - layout.trigger.left), JSON.stringify(layout)).toBeLessThanOrEqual(1);
  }, 120_000);
});
