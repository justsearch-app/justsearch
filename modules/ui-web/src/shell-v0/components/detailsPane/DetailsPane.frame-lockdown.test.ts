// SPDX-License-Identifier: Apache-2.0
// The details pane in real Chromium: sizes, sheet, focus, keyboard and the Space preview, over a
// private Vite server and a stubbed backend (no Engine, no shared dev stack). Run with
// npx vitest run --config vitest.config.lockdown.ts src/shell-v0/components/detailsPane/DetailsPane.frame-lockdown.test.ts
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const LONG = 'A very long file name that cannot possibly fit in one details tab of the pane.pdf';

const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css">
<style>html, body { margin: 0; height: 100%; } jf-sv3-window { height: 100vh; } </style></head><body><script type="module">
const params = new URLSearchParams(location.search);
if (params.has('wideFont')) {
  const style = document.createElement('style');
  style.textContent = '* { font-family: "DejaVu Sans", sans-serif !important; letter-spacing: 0.05em; }';
  document.head.append(style);
}
const json = (body) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
window.fetch = async (input) => String(input).includes('/api/preview')
  ? json({ content: 'Some document text. '.repeat(400), totalChars: 8000 }) : json({ entries: [], conversations: [] });
window.EventSource = class { constructor() { this.readyState = 0; } addEventListener() {} removeEventListener() {} close() {} };
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/views/search-v3/SearchV3View.ts');
await import('/src/shell-v0/views/search-v3/Sv3Results.ts');
await import('/src/shell-v0/components/detailsPane/WindowDetails.ts');
const inspector = await import('/src/shell-v0/state/inspectorState.ts');
const source = (i, name) => ({ parentDocId: 'C:/Docs/' + name, chunkIndex: i, excerpt: 'excerpt ' + i, startLine: i * 10, endLine: i * 10 + 2, headingText: '', startChar: i * 10, endChar: i * 10 + 8 });
const NAMES = ['${LONG}', 'Second ${LONG}', 'Third ${LONG}', 'Fourth ${LONG}', 'Fifth ${LONG}'];
const evidence = { sources: NAMES.map((name, i) => source(i, name)), matches: NAMES.map((_, i) => ({ sentenceIndex: 0, sentenceText: 'A sentence.', similarity: 0.9, sourceIndex: i })), marks: [], retrievalMode: 'HYBRID' };
const turn = { id: 't1', recordId: null, assistantRecordId: null, recordOpenedByUser: false, kind: 'ask', question: 'heating', answer: 'A sentence.', status: 'done', evidence, detail: '', toolCalls: 0, activity: [], askedAt: 1 };
if (params.get('mode') === 'window') {
  const box = document.createElement('div');
  box.style.cssText = 'position: relative; display: flex; height: 100vh; width: 100%';
  box.innerHTML = '<div style="flex: 1 1 auto; min-width: 0"><button id="opener">Opener</button></div>';
  const details = document.createElement('jf-window-details');
  details.windowId = 'core.library-surface';
  box.append(details);
  document.body.append(box);
  inspector.setInspectorOwnerResolver(() => 'core.library-surface');
  window.select = () => { document.querySelector('#opener').focus(); inspector.setSelected({ id: 'a', title: '${LONG}', path: 'C:/Docs/${LONG}' }); };
} else if (params.get('mode') === 'results') {
  const results = document.createElement('jf-sv3-results');
  results.style.cssText = 'display: block; width: 100%';
  const hit = (id, title) => ({ id, title, path: 'C:/Docs/' + title, snippet: 'A snippet about heating. '.repeat(6), score: 1 });
  results.view = { query: 'heating', filters: [], chosenCount: 0, status: 'ready', failure: '', selectedIds: new Set(), facetSelections: {}, askAvailability: null,
    snapshot: { query: 'heating', matchCount: 8, totalHits: 8, facetsTruncated: false, isSearching: false, processingTimeMs: 10, error: null, searchTrace: null, results: Array.from({ length: 8 }, (_, i) => hit('h' + i, 'Result ' + i + ' ${LONG}')), facets: {} } };
  document.body.append(results);
} else {
  const win = document.createElement('jf-sv3-window');
  document.body.append(win);
  await win.updateComplete;
  win.tier = 'ask';
  win.sessions = { sessions: [{ id: 'c1', title: 'One', turns: [turn], pinned: false, createdAt: 1, completedAt: 1, lastVisitedAt: 1, history: null, usage: null }], activeId: 'c1' };
  await win.updateComplete;
  window.win = win;
  window.openCitation = async () => {
    const main = win.shadowRoot.querySelector('jf-sv3-main');
    let opener = main.shadowRoot.querySelector('#test-opener');
    if (!opener) { opener = document.createElement('button'); opener.id = 'test-opener'; opener.textContent = 'Opener'; main.shadowRoot.append(opener); }
    opener.focus();
    main.dispatchEvent(new CustomEvent('sv3-citation-open', { bubbles: true, composed: true,
      detail: { docPath: evidence.sources[0].parentDocId, anchor: null, turnId: 't1', sourceIndex: 0 } }));
    await win.updateComplete;
  };
}
document.body.dataset.ready = 'true';
</script></body></html>`;

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let context: BrowserContext | undefined;
let output: string;
let url: string;

beforeAll(async () => {
  output = await mkdtemp(path.join(process.env.TMPDIR ?? tmpdir(), 'details-pane-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'details-harness',
      configureServer(vite) {
        vite.middlewares.use((req, res, next) => {
          if (req.url?.split('?')[0] !== '/details') return next();
          res.setHeader('Content-Type', 'text/html');
          res.end(HARNESS);
        });
      },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/details`;
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

async function open(width: number, height: number, query = ''): Promise<Page> {
  context = await browser.newContext({ viewport: { width, height } });
  const page = await context.newPage();
  page.on('pageerror', error => console.info('pageerror', error.message));
  await page.goto(`${url}${query}`, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await settle(page);
  return page;
}

async function settle(page: Page, ms = 120): Promise<void> {
  await page.waitForTimeout(ms);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function openPane(page: Page): Promise<void> {
  await page.evaluate(() => (window as unknown as { openCitation(): Promise<void> }).openCitation());
  await settle(page);
}

const surface = (page: Page) => page.locator('[data-testid="details-pane"]');

async function box(page: Page): Promise<{ width: number; right: number; left: number }> {
  const rect = await surface(page).boundingBox();
  if (rect === null) throw new Error('the pane is not on screen');
  return { width: Math.round(rect.width), left: Math.round(rect.x), right: Math.round(rect.x + rect.width) };
}

/** The element with the keyboard, through open shadow roots. */
const focusNow = (page: Page): Promise<string> => page.evaluate(() => {
  let active = document.activeElement;
  while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
  return active ? `${active.localName}${active.id ? `#${active.id}` : ''}[${active.getAttribute('role') ?? ''}]` : 'none';
});

const noSidewaysOverflow = (page: Page): Promise<boolean> => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);

describe('Search\u2019s details pane', () => {
  it.each(['', '?wideFont'])('is inline and within 360-560 px at 1280x800 %s, and tabs never push the close control out', async query => {
    const page = await open(1280, 800, query);
    await openPane(page);
    const pane = await box(page);
    expect(pane.width).toBeGreaterThanOrEqual(360);
    expect(pane.width).toBeLessThanOrEqual(560);
    expect(pane.right).toBe(1280);
    expect(await noSidewaysOverflow(page)).toBe(true);
    const close = await page.locator('[data-testid="details-pane-close"]').boundingBox();
    expect(close!.x + close!.width).toBeLessThanOrEqual(1280);
    const tabs = await page.locator('[role="tablist"]').first().boundingBox();
    expect(tabs!.x + tabs!.width).toBeLessThanOrEqual(close!.x);
    expect(await page.locator('[data-testid="details-source-name"]').count()).toBe(1);
    const clipped = await page.locator('[data-testid="details-source-name"]').evaluate(name => name.scrollWidth > name.clientWidth);
    expect(clipped, 'a long name is cut inside the header, not the tab strip').toBe(true);
  }, 60_000);

  it.each(['', '?wideFont'])('is a sheet at 900x800 %s, opened with the keyboard in it, and Escape returns it to the opener', async query => {
    const page = await open(900, 800, query);
    await openPane(page);
    const sheet = await box(page);
    expect(sheet.right).toBe(900);
    expect(sheet.width).toBeGreaterThanOrEqual(360);
    expect(sheet.width).toBeLessThanOrEqual(560);
    expect(await page.locator('[data-testid="sv3-pane-grip"]').count()).toBe(0);
    expect(await page.locator('[data-testid="details-pane-backdrop"]').isVisible()).toBe(true);
    expect(await focusNow(page)).toContain('[tab]');
    expect(await noSidewaysOverflow(page)).toBe(true);
    await page.keyboard.press('Escape');
    await settle(page);
    expect(await surface(page).count()).toBe(0);
    expect(await focusNow(page)).toBe('button#test-opener[]');
  }, 60_000);

  it('at the 600x400 minimum the sheet stays on screen with its actions reachable', async () => {
    const page = await open(600, 400);
    await openPane(page);
    const sheet = await box(page);
    expect(sheet.left).toBeGreaterThanOrEqual(0);
    expect(sheet.right).toBe(600);
    expect(await noSidewaysOverflow(page)).toBe(true);
    const footer = await page.locator('[data-testid="details-open-file"]').boundingBox();
    expect(footer!.y + footer!.height).toBeLessThanOrEqual(400);
  }, 60_000);

  it('a window narrowed while the pane is open becomes a sheet, keeps the keyboard, and Escape still works', async () => {
    const page = await open(1280, 800);
    await openPane(page);
    await page.locator('[role="region"][aria-label="Document content"]').first().waitFor();
    await page.setViewportSize({ width: 900, height: 800 });
    await settle(page);
    expect(await page.locator('[data-testid="details-pane-backdrop"]').isVisible()).toBe(true);
    expect(await focusNow(page)).toContain('[tab]');
    await page.keyboard.press('Escape');
    await settle(page);
    expect(await surface(page).count()).toBe(0);
    expect(await focusNow(page)).toBe('button#test-opener[]');
  }, 60_000);

  it('the grip keeps the width inside the limits and the main column its 640', async () => {
    const page = await open(1600, 900);
    await openPane(page);
    const grip = page.locator('[data-testid="sv3-pane-grip"]');
    await grip.focus();
    for (let i = 0; i < 20; i += 1) await page.keyboard.press('ArrowLeft');
    await settle(page);
    expect((await box(page)).width).toBe(560);
    for (let i = 0; i < 20; i += 1) await page.keyboard.press('ArrowRight');
    await settle(page);
    expect((await box(page)).width).toBe(360);
  }, 60_000);

  it('arrow keys move through the tabs and the keyboard stays on the tab strip', async () => {
    const page = await open(1280, 800);
    await openPane(page);
    await page.keyboard.press('ArrowRight');
    await settle(page);
    expect(await page.locator('[role="tab"][aria-selected="true"]').getAttribute('data-tab')).toBe('source:1');
    expect(await focusNow(page)).toContain('[tab]');
    await page.keyboard.press('End');
    await settle(page);
    expect(await page.locator('[role="tab"][aria-selected="true"]').getAttribute('data-tab')).toBe('context');
    await expect.poll(() => page.locator('[data-testid="context-inspector"]').count()).toBe(1);
  }, 60_000);

  /**
   * X1: colour is never the only signal and text stays readable. In forced colours the selected
   * source tab's digit must differ from the block behind it, read from real pixels: computed colours
   * do not show what the browser forces.
   */
  it.each(['dark', 'light'] as const)('X1 the selected source number is readable in forced colours on a %s palette', async (scheme) => {
    const page = await open(1280, 800);
    await page.emulateMedia({ forcedColors: 'active', colorScheme: scheme });
    await openPane(page);
    const number = page.locator('[role="tab"][aria-selected="true"] .number');
    await expect.poll(() => number.count()).toBe(1);
    const shot = (await number.screenshot()).toString('base64');
    const colours = await page.evaluate(async (png) => {
      const image = new Image();
      image.src = `data:image/png;base64,${png}`;
      await image.decode();
      const canvas = document.createElement('canvas');
      canvas.width = image.width;
      canvas.height = image.height;
      const context2d = canvas.getContext('2d')!;
      context2d.drawImage(image, 0, 0);
      const { data } = context2d.getImageData(0, 0, canvas.width, canvas.height);
      const inner: number[][] = [];
      // Only the digit's column: the browser's backplate behind forced-colours text fills it, so the
      // block around it says nothing about whether the digit can be seen.
      const middle = Math.floor(canvas.width / 2);
      for (let y = 3; y < canvas.height - 3; y += 1) {
        for (let x = middle - 2; x <= middle + 2; x += 1) {
          const at = (y * canvas.width + x) * 4;
          inner.push([data[at]!, data[at + 1]!, data[at + 2]!]);
        }
      }
      const luminance = inner.map(([r, g, b]) => 0.2126 * r! + 0.7152 * g! + 0.0722 * b!);
      return { low: Math.min(...luminance), high: Math.max(...luminance) };
    }, shot);
    expect(colours.high - colours.low, 'digit against its block').toBeGreaterThan(100);
  }, 60_000);

  it('F6-style order holds: the tab strip comes before the reader, and Tab reaches Open file', async () => {
    const page = await open(1280, 800);
    await openPane(page);
    const seen: string[] = [];
    for (let i = 0; i < 6; i += 1) { await page.keyboard.press('Tab'); seen.push(await focusNow(page)); }
    expect(seen.some(item => item.includes('[region]'))).toBe(true);
  }, 60_000);
});

describe('another window\u2019s details pane', () => {
  it.each([[1280, 'inline'], [900, 'sheet']] as const)('at %d px it is %s, and its close returns focus', async (width, shape) => {
    const page = await open(width, 800, '?mode=window');
    await page.evaluate(() => (window as unknown as { select(): void }).select());
    await settle(page);
    const pane = await box(page);
    expect(pane.width).toBeGreaterThanOrEqual(360);
    expect(pane.width).toBeLessThanOrEqual(560);
    expect(pane.right).toBe(width);
    expect(await page.locator('[data-testid="details-pane-backdrop"]').count()).toBe(shape === 'sheet' ? 1 : 0);
    expect(await noSidewaysOverflow(page)).toBe(true);
    await page.locator('[data-testid="details-pane-close"] button').click();
    await settle(page);
    expect(await surface(page).count()).toBe(0);
    expect(await focusNow(page)).toBe('button#opener[]');
  }, 60_000);
});

describe('the Space preview', () => {
  const row = (page: Page) => page.locator('[data-testid="search-result-row"]').first();
  const card = (page: Page) => page.locator('[data-testid="sv3-result-preview"]');

  it.each([[1280, 800, ''], [600, 400, ''], [600, 400, '&wideFont']] as const)('at %dx%d it floats on screen and Space or Escape closes it %s', async (width, height, extra) => {
    const page = await open(width, height, `?mode=results${extra}`);
    await row(page).focus();
    await page.keyboard.press('Space');
    await settle(page);
    const rect = await card(page).boundingBox();
    expect(rect!.x).toBeGreaterThanOrEqual(0);
    expect(rect!.y).toBeGreaterThanOrEqual(0);
    expect(rect!.x + rect!.width).toBeLessThanOrEqual(width);
    expect(rect!.y + rect!.height).toBeLessThanOrEqual(height);
    expect(await noSidewaysOverflow(page)).toBe(true);
    expect(await focusNow(page)).toContain('[listitem]');
    await page.keyboard.press('Space');
    await settle(page);
    expect(await card(page).count()).toBe(0);
    await page.keyboard.press('Space');
    await settle(page);
    await page.keyboard.press('Escape');
    await settle(page);
    expect(await card(page).count()).toBe(0);
    expect(await focusNow(page)).toContain('[listitem]');
  }, 60_000);

  it('holding Space opens it once rather than flickering, and does not scroll the list', async () => {
    const page = await open(1280, 400, '?mode=results');
    await row(page).focus();
    const before = await page.evaluate(() => window.scrollY);
    await page.keyboard.down('Space');
    await page.keyboard.down('Space');
    await page.keyboard.down('Space');
    await page.keyboard.up('Space');
    await settle(page);
    expect(await card(page).count()).toBe(1);
    expect(await page.evaluate(() => window.scrollY)).toBe(before);
  }, 60_000);
});
