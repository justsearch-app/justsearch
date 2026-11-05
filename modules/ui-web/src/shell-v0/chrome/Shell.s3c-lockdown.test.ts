// SPDX-License-Identifier: Apache-2.0
// Shell slice 3c — the top bar and the status panel's plugin row at every size, in the real Shell over a
// private Vite server and headless Chromium (stubbed backend; no Engine, no shared dev stack). Run with
// npx vitest run --config vitest.config.lockdown.ts src/shell-v0/chrome/Shell.s3c-lockdown.test.ts
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import path from 'node:path';

const SEARCH = 'core.search-v3-surface';
const LIBRARY = 'core.library-surface';
const BROWSE = 'core.browse-surface';
const BRAIN = 'core.brain-surface';
const SETTINGS = 'core.settings-surface';
const NOTICE = "Couldn't find that window";

/** The page: catalog, a stubbed backend and the real `<jf-shell>`; everything else is the app's own code. */
const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
const json = (body) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
// ?registryDelay=<ms> holds the boot's window-catalog response, as a busy engine does: the app
// keeps starting (Settings mounts ahead of its first opening) before the address is applied.
const registryDelay = Number(new URLSearchParams(location.search).get('registryDelay') ?? 0);
window.fetch = async (input) => {
  if (registryDelay && String(input).endsWith('/api/registry/surfaces')) await new Promise(r => setTimeout(r, registryDelay));
  return json({ entries: [] });
};
window.EventSource = class { constructor() { this.readyState = 0; } addEventListener() {} removeEventListener() {} close() {} };
const { __seedForTest } = await import('/src/api/registry/SurfaceCatalogClient.ts');
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/chrome/Shell.ts');
/** Search: a text box that takes focus whenever the window is shown, as the composer does. */
customElements.define('jf-frame-search-probe', class extends HTMLElement {
  connectedCallback() {
    if (!this.shadowRoot) this.attachShadow({ mode: 'open' }).innerHTML = '<textarea aria-label="Ask or search"></textarea>';
    requestAnimationFrame(() => this.shadowRoot.querySelector('textarea').focus());
  }
});
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL',
  presentation: { labelKey: id, descriptionKey: id }, consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('${SEARCH}', 'jf-frame-search-probe'),
  surface('${LIBRARY}', 'jf-library-surface', { members: ['${BROWSE}'] }),
  surface('${BROWSE}', 'jf-browse-surface', { placement: 'DEEPLINK' }),
  surface('${BRAIN}', 'jf-brain-surface'),
  surface('core.system-surface', 'jf-system-surface'),
  surface('${SETTINGS}', 'jf-settings-surface', { placement: 'MODAL' }),
] });
document.body.append(document.createElement('jf-shell'));
document.body.dataset.ready = 'true';
</script></body></html>`;

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let context: BrowserContext | undefined;
let output: string;
let url: string;

beforeAll(async () => {
  const temporaryRoot = path.resolve('../..', 'tmp');
  await mkdir(temporaryRoot, { recursive: true });
  output = await mkdtemp(path.join(temporaryRoot, 'frame-s3c-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'frame-harness',
      configureServer(vite) {
        vite.middlewares.use((req, res, next) => {
          if (req.url?.split('?')[0] !== '/frame') return next();
          res.setHeader('Content-Type', 'text/html');
          res.end(HARNESS);
        });
      },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/frame`;
  // Playwright creates its fresh --user-data-dir under the caller's short TMPDIR.
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

async function open(width = 1280, height = 800, query = '', hash = ''): Promise<Page> {
  context = await browser.newContext({ viewport: { width, height } });
  const page = await context.newPage();
  page.on('pageerror', error => console.info('pageerror', error.message));
  await page.goto(`${url}${query}${hash}`, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await page.locator('jf-top-bar').waitFor();
  await settle(page);
  return page;
}

/** Let the router, popstate and Lit updates finish. */
async function settle(page: Page, ms = 150): Promise<void> {
  await page.waitForTimeout(ms);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

/**
 * Every element showing the notice's words that a sighted reader can actually read: rendered at a
 * real size, inside the window, not clipped or cut off, not covered, and not hidden from assistive
 * technology. (The pill's live announcer holds the same words in a one-pixel clipped box.)
 */
async function readableNotices(page: Page): Promise<string[]> {
  return page.evaluate((words) => {
    const found: string[] = [];
    const visit = (root: ParentNode): void => {
      for (const el of root.querySelectorAll<HTMLElement>('*')) {
        if (el.shadowRoot) visit(el.shadowRoot);
        if (el.textContent?.trim() !== words || [...el.children].some(c => c.textContent?.trim() === words)) continue;
        const rect = el.getBoundingClientRect();
        const style = getComputedStyle(el);
        const inside = rect.left >= 0 && rect.top >= 0 && rect.right <= innerWidth && rect.bottom <= innerHeight;
        const uncut = el.scrollWidth <= el.clientWidth + 1 && el.scrollHeight <= el.clientHeight + 1;
        let hiddenFromAt = false;
        for (let node: Node | null = el; node; node = (node as Element).parentElement ?? ((node.getRootNode() as ShadowRoot).host ?? null)) {
          if (node instanceof Element && (node.getAttribute('aria-hidden') === 'true' || getComputedStyle(node).clipPath !== 'none')) hiddenFromAt = true;
          if (node === document.documentElement) break;
        }
        const x = rect.left + rect.width / 2;
        const y = rect.top + rect.height / 2;
        let top: Element | null = document.elementFromPoint(x, y);
        while (top?.shadowRoot?.elementFromPoint(x, y) && top.shadowRoot.elementFromPoint(x, y) !== top) top = top.shadowRoot.elementFromPoint(x, y);
        const uncovered = top === el || el.contains(top);
        if (rect.width >= 40 && rect.height >= 8 && style.visibility === 'visible' && Number(style.opacity) > 0 && inside && uncut
          && !hiddenFromAt && uncovered) found.push(el.className || el.localName);
      }
    };
    visit(document);
    return found;
  }, NOTICE);
}

/** A plugin failed to load: a problem the pill shows until it is dismissed. */
async function problem(page: Page): Promise<void> {
  await page.evaluate(() => document.dispatchEvent(new CustomEvent('jf-plugin-load-error', {
    detail: { pluginId: 'acme.clock', message: 'The plugin could not start.' } })));
  await settle(page, 200);
}

/** Register a plugin status item (a string, so the runner leaves the page's own imports alone). */
async function pluginItem(page: Page, id: string, contributor: string, render: string, extra = ''): Promise<void> {
  await page.evaluate(`(async () => {
    const { registerStatusBarItem } = await import('/src/shell-v0/commands/StatusBarRegistry.ts');
    const { makePluginProvenance } = await import('/src/shell-v0/primitives/provenance.ts');
    registerStatusBarItem({ id: ${JSON.stringify(id)}, source: 'plugin', provenance: makePluginProvenance(${JSON.stringify(contributor)}, '1.0.0'),
      priority: 1, position: 'right', render: () => { ${render} }, ${extra} });
  })()`);
}

/** The open status panel's root. */
async function openPanel(page: Page): Promise<void> {
  await page.locator('jf-status-pill button.trigger').click();
  await settle(page, 300);
}

it('11 the narrow area menu closes when the window widens past its button, and focus stays on the switcher', async () => {
  const page = await open(600, 400);
  await page.locator('jf-top-bar button.area-trigger').click();
  await settle(page);
  const menu = page.locator('jf-top-bar [role="menu"][aria-label="Areas"]');
  expect(await menu.count()).toBe(1);
  expect(await menu.locator('[role="menuitemradio"]').first().evaluate(el => el.matches(':focus'))).toBe(true);
  for (const width of [800, 1100]) {
    await page.setViewportSize({ width, height: 800 });
    await settle(page);
    expect(await menu.count(), `${width} px: no menu apart from its button`).toBe(0);
    expect(await page.locator('jf-top-bar button.area-trigger').getAttribute('aria-expanded')).toBe('false');
    const focused = await page.evaluate(() => {
      let active: Element | null = document.activeElement;
      while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
      return active ? `${active.localName}.${active.className}` : null;
    });
    expect(focused, 'focus moves to the active area, never the page').toBe('button.segment');
  }
}, 120_000);

it('11 at 1280 px a notice beside a problem leaves the switcher centred; pill and notice stay readable', async () => {
  const page = await open(1280, 800);
  await problem(page);
  await page.evaluate(() => { location.hash = '#justsearch://surface/core.nope-window'; });
  await settle(page, 400);
  const layout = await page.evaluate(() => {
    const bar = document.querySelector('jf-shell')!.shadowRoot!.querySelector('jf-top-bar')!;
    const nav = bar.shadowRoot!.querySelector('nav')!.getBoundingClientRect();
    const pill = document.querySelector('jf-shell')!.shadowRoot!.querySelector('jf-status-pill')!.shadowRoot!;
    return { centre: (nav.left + nav.right) / 2, half: innerWidth / 2, kind: pill.querySelector('.trigger')!.className,
      beside: pill.querySelector('.beside-notice')?.textContent?.trim() };
  });
  expect(layout.kind).toContain('problems');
  expect(layout.beside).toBe(NOTICE);
  expect(Math.abs(layout.centre - layout.half), JSON.stringify(layout)).toBeLessThanOrEqual(1);
  expect(await page.locator('jf-status-pill button.trigger').evaluate(el => el.scrollWidth > el.clientWidth + 1)).toBe(false);
  expect(await readableNotices(page)).toContain('beside-notice');
  expect(await page.getByRole('button', { name: 'Choose destination' }).isVisible()).toBe(true);
}, 120_000);

it('11 at 600 px the pill shows the number of problems, as it does for needs-you', async () => {
  const page = await open(600, 400);
  await problem(page);
  const trigger = page.locator('jf-status-pill button.trigger');
  expect(await trigger.getAttribute('aria-label')).toMatch(/^Problems right now \(1\)/);
  expect(await trigger.locator('.count').count(), 'a count badge').toBe(1);
  expect(await trigger.locator('.count').textContent()).toBe('1');
  expect(await trigger.locator('.symbol').count(), 'no "!" in place of the count').toBe(0);
  expect(await trigger.evaluate(el => el.scrollWidth > el.clientWidth + 1)).toBe(false);
}, 120_000);

it('L6 a plugin item whose words are in its own shadow root keeps them as its tooltip; an empty one its declared label', async () => {
  const page = await open(1280, 800);
  const words = 'Focus timer: 25 minutes left in this session, then a five minute break';
  await pluginItem(page, 'plugin.shadow-words', 'acme.timer', `
    const el = document.createElement('span');
    el.attachShadow({ mode: 'open' }).innerHTML = '<style>:host{display:block;width:1000px}</style><b>' + ${JSON.stringify(words)} + '</b>';
    return el;`);
  await pluginItem(page, 'plugin.no-words', 'acme.dot', `return document.createElement('span');`, `accessibleLabel: 'Sync status'`);
  await openPanel(page);
  const titles = await page.evaluate(() => {
    const panel = document.querySelector('jf-shell')!.shadowRoot!.querySelector('jf-status-pill')!.shadowRoot!
      .querySelector('jf-status-panel')!.shadowRoot!;
    const titleOf = (id: string) => panel.querySelector<HTMLElement>(`[data-plugin-item="${id}"] .plugin-body`)?.title ?? null;
    return { shadow: titleOf('plugin.shadow-words'), label: titleOf('plugin.no-words') };
  });
  expect(titles.shadow).toBe(words);
  expect(titles.label).toBe('Sync status');
}, 120_000);

it('11 a long plugin item\'s provenance chip keeps whole words; its full text stays available', async () => {
  const contributor = 'acme.extraordinarily-long-contributor-name-for-status';
  const page = await open(900, 700);
  await pluginItem(page, 'plugin.long-words', contributor, `
    const el = document.createElement('span');
    el.textContent = 'Background sync of every shared notebook folder with the remote archive is running';
    return el;`);
  await openPanel(page);
  const chip = await page.evaluate(() => {
    const panel = document.querySelector('jf-shell')!.shadowRoot!.querySelector('jf-status-pill')!.shadowRoot!
      .querySelector('jf-status-panel')!.shadowRoot!;
    const host = panel.querySelector('[data-plugin-item="plugin.long-words"] jf-provenance-chip')!;
    const box = host.shadowRoot!.querySelector<HTMLElement>('.chip')!;
    const lines = [...box.querySelectorAll<HTMLElement>(':scope > span')].map(span => {
      const range = document.createRange(); range.selectNodeContents(span);
      return { text: span.textContent!.trim(), lines: new Set([...range.getClientRects()].map(r => Math.round(r.top))).size };
    });
    return { title: box.title, lines, height: box.getBoundingClientRect().height,
      lineHeight: parseFloat(getComputedStyle(box).lineHeight) || parseFloat(getComputedStyle(box).fontSize) * 1.5 };
  });
  for (const span of chip.lines) expect(span.lines, `${span.text} on one line: ${JSON.stringify(chip)}`).toBe(1);
  expect(chip.height, JSON.stringify(chip)).toBeLessThan(chip.lineHeight * 2);
  expect(chip.title).toContain(contributor);
}, 120_000);
