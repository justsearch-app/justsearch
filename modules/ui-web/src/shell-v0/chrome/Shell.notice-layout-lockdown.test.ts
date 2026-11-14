// SPDX-License-Identifier: Apache-2.0
// Notice layout regression in the real Shell and Library, on an owned headless browser and Vite server.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';

const SEARCH = 'core.search-v3-surface';
const LIBRARY = 'core.library-surface';
const BROWSE = 'core.browse-surface';
const BRAIN = 'core.brain-surface';
const SETTINGS = 'core.settings-surface';
const HEALTH = 'core.health-surface';
const LONG_WINDOW = 'vendor.example.a-window-with-a-very-long-plain-name-for-the-redirect-notice-surface';
const NOTICE = 'Skip rule added';

/** The page: catalog, a stubbed backend and the real `<jf-shell>`; everything else is the app's own code. */
const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
// Exercise font metrics independently of the machine's default installed fonts.
if (new URLSearchParams(location.search).has('wideFont')) {
  document.body.style.fontFamily = '"DejaVu Sans", sans-serif';
}
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
  surface('${SEARCH}', 'jf-sv3-window'),
  surface('${LIBRARY}', 'jf-library-surface', { members: ['${BROWSE}'] }),
  surface('${BROWSE}', 'jf-browse-surface', { placement: 'DEEPLINK' }),
  surface('${BRAIN}', 'jf-brain-surface'),
  surface('core.system-surface', 'jf-system-surface', { members: ['${HEALTH}', '${LONG_WINDOW}'] }),
  surface('${HEALTH}', 'jf-health-surface', { placement: 'DEEPLINK' }),
  surface('${LONG_WINDOW}', 'jf-health-surface', { placement: 'DEEPLINK' }),
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
  output = await mkdtemp(path.join(temporaryRoot, 'notice-layout-'));
  server = await createServer({
    configFile: false,
    root: process.env.S3_NOTICE_TREE ? path.join(process.env.S3_NOTICE_TREE, 'modules/ui-web') : process.cwd(),
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
  if (query.includes('wideFont')) {
    await page.locator('jf-status-pill').evaluate(pill => {
      const style = document.createElement('style');
      style.textContent = '.beside-notice, .notice-below, .trigger.notice .label { font-family: "DejaVu Sans", sans-serif; letter-spacing: 3px; }';
      pill.shadowRoot!.append(style);
    });
  }
  await settle(page);
  return page;
}

/** Let the router, popstate and Lit updates finish. */
async function settle(page: Page, ms = 150): Promise<void> {
  await page.waitForTimeout(ms);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}


async function offer(page: Page, words = NOTICE): Promise<void> {
  // Same public offer used after Library saves a skip rule; no saved settings in this fixture.
  await page.evaluate(`(async () => {
    const { offerReversal } = await import('/src/shell-v0/state/ownReversals.ts');
    offerReversal({ message: ${JSON.stringify(words)}, reverse: async () => {}, reversed: 'Skip rules restored' });
  })()`);
  await settle(page);
}

async function noticeLayout(page: Page, words = NOTICE) {
  return page.evaluate(words => {
    const shell = document.querySelector('jf-shell')!.shadowRoot!;
    const bar = shell.querySelector('jf-top-bar')!.getBoundingClientRect();
    const pill = shell.querySelector('jf-status-pill')!.shadowRoot!;
    const candidates = [...pill.querySelectorAll<HTMLElement>('.label, .notice-below, .beside-notice')]
      .filter(el => el.textContent?.trim() === words && el.getBoundingClientRect().height > 1);
    const notice = candidates.find(el => getComputedStyle(el).display !== 'none')!;
    const box = notice.getBoundingClientRect();
    const range = document.createRange(); range.selectNodeContents(notice);
    const painted = [...range.getClientRects()];
    const library = shell.querySelector('jf-stage')!.shadowRoot!.querySelector('jf-library-surface')!.shadowRoot!;
    const controls = [...library.querySelectorAll<HTMLElement>('jf-button, button'),
      ...library.querySelector('jf-surface-tabs')!.shadowRoot!.querySelectorAll<HTMLElement>('[role="tab"]')]
      .filter(el => el.getAttribute('role') === 'tab' || el.textContent?.includes('Add folder'))
      .filter(el => el.getBoundingClientRect().height > 1);
    const overlap = (r: DOMRect) => box.left < r.right && box.right > r.left && box.top < r.bottom && box.bottom > r.top;
    const uncovered = painted.every(r => {
      const x = (r.left + r.right) / 2, y = (r.top + r.bottom) / 2;
      let top = document.elementFromPoint(x, y);
      while (top?.shadowRoot?.elementFromPoint(x, y) && top.shadowRoot.elementFromPoint(x, y) !== top) top = top.shadowRoot.elementFromPoint(x, y);
      return top === notice || notice.contains(top);
    });
    const undo = pill.querySelector('.transient-action')!.getBoundingClientRect();
    return { barHeight: bar.height, top: box.top, bottom: box.bottom, barBottom: bar.bottom,
      controls: controls.map(el => el.textContent?.trim()),
      overlaps: controls.filter(el => overlap(el.getBoundingClientRect())).map(el => el.textContent?.trim()),
      readable: notice.scrollWidth <= notice.clientWidth + 1 && notice.scrollHeight <= notice.clientHeight + 1
        && painted.every(r => r.left >= 0 && r.right <= innerWidth && r.top >= 0 && r.bottom <= innerHeight) && uncovered,
      undoInside: undo.top >= bar.top && undo.bottom <= bar.bottom,
      stageTop: shell.querySelector('.stage')!.getBoundingClientRect().top,
    };
  }, words);
}

it.each(['light', 'dark'])('Library at 600 px keeps Skip rule added and Undo readable without covering controls (%s)', async theme => {
  const page = await open(600, 400, '', `#justsearch://surface/${LIBRARY}`);
  await page.evaluate(theme => {
    document.documentElement.dataset.theme = theme;
    document.documentElement.style.setProperty('--window-controls-inline', '112px');
  }, theme);
  await offer(page);
  const layout = await noticeLayout(page);
  expect(layout.controls.some(text => text?.includes('Add folder')), JSON.stringify(layout)).toBe(true);
  expect(layout.controls.length).toBeGreaterThanOrEqual(3);
  expect(layout.overlaps, JSON.stringify(layout)).toEqual([]);
  expect(layout.readable, JSON.stringify(layout)).toBe(true);
  expect(layout.undoInside, JSON.stringify(layout)).toBe(true);
  expect(layout.barHeight).toBe(44);
  expect(layout.bottom, 'short notice fits inside the bar').toBeLessThanOrEqual(layout.barBottom);
}, 120_000);

it('an oversized notice reserves space, and releases it when a short notice fits again', async () => {
  const page = await open(600, 400, '', `#justsearch://surface/${LIBRARY}`);
  // Desktop controls reduce the room available to the bar independently of the viewport.
  await page.evaluate(() => document.documentElement.style.setProperty('--window-controls-inline', '112px'));
  const long = 'Skip rules changed. The additional file names are skipped from now on; Undo saves the skip rules as they were before.';
  await offer(page, long);
  const layout = await noticeLayout(page, long);
  expect(layout.top).toBeGreaterThanOrEqual(layout.barBottom);
  expect(layout.overlaps, JSON.stringify(layout)).toEqual([]);
  expect(layout.readable, JSON.stringify(layout)).toBe(true);
  expect(layout.stageTop).toBeGreaterThanOrEqual(layout.bottom);
  await page.setViewportSize({ width: 1024, height: 700 });
  await offer(page);
  const short = await noticeLayout(page);
  expect(short.bottom).toBeLessThanOrEqual(short.barBottom);
  expect(short.stageTop).toBe(short.barBottom);
}, 120_000);

it.each(['light', 'dark'])('the bar follows the page token in all areas (%s)', async theme => {
  const page = await open();
  await page.evaluate(theme => { document.documentElement.dataset.theme = theme; }, theme);
  for (const id of [SEARCH, LIBRARY, BRAIN, 'core.system-surface']) {
    await page.evaluate(id => { location.hash = `#justsearch://surface/${id}`; }, id);
    await settle(page);
    const colors = await page.locator('jf-shell').evaluate(shell => {
      let window: Element | null = shell.shadowRoot!.querySelector('jf-stage')!.shadowRoot!
        .querySelector('jf-sv3-window, jf-library-surface, jf-brain-surface, jf-system-surface');
      let page = 'rgba(0, 0, 0, 0)';
      while (window && page === 'rgba(0, 0, 0, 0)') {
        page = getComputedStyle(window).backgroundColor;
        window = window.parentElement ?? (window.getRootNode() as ShadowRoot).host;
      }
      return { page, bar: getComputedStyle(shell.shadowRoot!.querySelector('jf-top-bar')!).backgroundColor };
    });
    expect(colors.bar, id).toBe(colors.page);
  }
  await page.evaluate(() => document.documentElement.style.setProperty('--surface-0', 'rgb(41, 47, 53)'));
  expect(await page.locator('jf-top-bar').evaluate(el => getComputedStyle(el).backgroundColor)).toBe('rgb(41, 47, 53)');
}, 120_000);

it('captures Library notice evidence at 1024 and 600 px in both themes', async () => {
  if (!process.env.S3_EVIDENCE_DIR) return;
  const evidence = path.resolve(process.env.S3_EVIDENCE_DIR);
  await mkdir(evidence, { recursive: true });
  const metrics = [];
  for (const theme of ['light', 'dark']) for (const width of [1024, 600]) {
    const page = await open(width, 700, '', `#justsearch://surface/${LIBRARY}`);
    await page.evaluate(theme => {
      document.documentElement.dataset.theme = theme;
      document.documentElement.style.setProperty('--window-controls-inline', '112px');
    }, theme);
    await offer(page);
    metrics.push({ theme, width, ...await noticeLayout(page) });
    await page.screenshot({ path: path.join(evidence, `library-${width}-${theme}-notice.png`) });
    await context!.close(); context = undefined;
  }
  await writeFile(path.join(evidence, 'library-notice-metrics.json'), JSON.stringify(metrics, null, 2));
}, 120_000);
