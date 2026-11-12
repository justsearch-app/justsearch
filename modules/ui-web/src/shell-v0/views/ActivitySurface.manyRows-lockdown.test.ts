// SPDX-License-Identifier: Apache-2.0
// Real Chromium, private Vite server; run with vitest.config.lockdown.ts.
// FC8: Activity showing more than 100 records (the feed windows its list) logs no uncaught
// "ResizeObserver loop completed with undelivered notifications" error, while it first draws and
// while the reader scrolls to the oldest record and back, at 1280×720, at 600×400 with 200% text
// and in forced colours. The real Shell opens System › Activity; its ledger arrives over the shared
// shell-events stream the way an Engine sends it. Records mix one-line operations with index
// records and approvals, whose rows are of other heights.
// FC9 in the real page: the rows on screen read in words, not in recorded codes.
// FC12: the windowed feed draws its first rows as soon as they are loaded, also while the list starts
// below what the window shows (600×400 with 200% text), so they are there when the person scrolls to them.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

const ROWS = 160;

const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
const params = new URLSearchParams(location.search);
if (params.get('text')) document.documentElement.style.fontSize = params.get('text');
const json = (body) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
const encoder = new TextEncoder();
const seqs = new Map();
/** One SSE frame as SseEnvelopeWriter writes it: event "frame", the envelope as JSON data. */
const frame = (streamId, frameKind, payload, checkpoint) => {
  const seq = (seqs.get(streamId) ?? 0) + 1;
  seqs.set(streamId, seq);
  const resumeToken = checkpoint ? btoa(streamId + ':' + seq + ':incarnation-1') : '';
  return encoder.encode('event: frame\\ndata: ' + JSON.stringify({ streamId, frameKind, seq, ts: new Date().toISOString(), payload, resumeToken }) + '\\n\\n');
};
const at = (i) => new Date(Date.UTC(2030, 0, 1, 8) + i * 1000).toISOString();
const record = (i) => i % 7 === 3
  ? { id: 'index:' + i, kind: 'index', originator: 'system', transport: 'WORKER_INDEXER', pathHash: 'h' + i + 'abcdef',
      collection: 'reports-' + i, state: i % 2 ? 'DONE' : 'FAILED', outcome: i % 2 ? 'DONE' : 'FAILED', occurredAt: at(i) }
  : i % 7 === 5
    ? { id: 'gate:' + i, kind: 'gate', originator: 'agent', operationId: 'core.rebuild-index', disposition: i % 2 ? 'GATED' : 'DENIED',
        gateBehavior: 'TYPED_CONFIRM', pendingId: 'p' + i, occurredAt: at(i) }
    : { id: 'operation:k' + i, kind: 'operation', originator: i % 2 ? 'agent' : 'user',
        operationId: 'core.open-file', outcome: i % 9 ? 'SUCCESS' : 'FAILURE', occurredAt: at(i) };
const entries = Array.from({ length: ${ROWS} }, (_, i) => record(i));
window.fetch = async (input) => {
  const path = new URL(String(input instanceof Request ? input.url : input), location.href).pathname;
  if (path.endsWith('/api/shell-events/stream')) {
    // The Engine on connect: the ledger channel's connected (no checkpoint), then its snapshot.
    return new Response(new ReadableStream({ start(controller) {
      controller.enqueue(frame('surface:action-ledger', 'LIFECYCLE', { kind: 'connected' }, false));
      controller.enqueue(frame('surface:action-ledger', 'LIFECYCLE', { kind: 'snapshot', entries }, true));
    } }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
  }
  if (path.endsWith('/api/action-ledger')) return json({ entries });
  return json({ entries: [], conversations: [], messages: [] });
};
const { __seedForTest } = await import('/src/api/registry/SurfaceCatalogClient.ts');
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/chrome/Shell.ts');
customElements.define('jf-rows-search-probe', class extends HTMLElement {});
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL',
  presentation: { labelKey: id, descriptionKey: id }, consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('core.search-v3-surface', 'jf-rows-search-probe'),
  surface('core.system-surface', 'jf-system-surface', { members: ['core.health-surface', 'core.logs-surface', 'core.activity-surface'] }),
  surface('core.health-surface', 'jf-health-surface', { placement: 'DEEPLINK', altitude: 'DIAGNOSTIC' }),
  surface('core.logs-surface', 'jf-log-surface', { placement: 'DEEPLINK', altitude: 'DIAGNOSTIC' }),
  surface('core.activity-surface', 'jf-activity-surface', { placement: 'DEEPLINK', altitude: 'TRUST' }),
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
  output = await mkdtemp(path.join(os.tmpdir(), 'activity-rows-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'activity-rows-harness',
      configureServer(vite) {
        vite.middlewares.use((req, res, next) => {
          if (req.url?.split('?')[0] !== '/system') return next();
          res.setHeader('Content-Type', 'text/html');
          res.end(HARNESS);
        });
      },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/system`;
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

declare global {
  interface Window {
    /** Every uncaught error the page reported, by its message. */
    __uncaught: string[];
    /** The element `tag` anywhere in the page, across shadow roots. */
    __deep(tag: string): Element | null;
  }
}

interface Opened { readonly page: Page; readonly pageErrors: string[] }

async function open(width: number, height: number, text: string, forcedColors: boolean): Promise<Opened> {
  context = await browser.newContext({ viewport: { width, height }, forcedColors: forcedColors ? 'active' : 'none' });
  const page = await context.newPage();
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  // Counted from before the page's own scripts run: the window's error event is how the browser
  // reports an undelivered ResizeObserver notification.
  await page.addInitScript(() => {
    window.__uncaught = [];
    window.addEventListener('error', (event) => { window.__uncaught.push(String(event.message ?? event.error ?? 'error')); });
  });
  await page.goto(`${url}${text ? `?text=${text}` : ''}#justsearch://surface/core.activity-surface`, { timeout: 45_000 });
  await loaded(page);
  return { page, pageErrors };
}

/** The page is up and Activity holds its records in the windowed list. */
async function loaded(page: Page): Promise<void> {
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await page.evaluate(() => {
    window.__deep = (tag) => {
      const walk = (root: ParentNode): Element | null => {
        for (const el of root.querySelectorAll('*')) {
          if (el.localName === tag) return el;
          const inner = el.shadowRoot && walk(el.shadowRoot);
          if (inner) return inner;
        }
        return null;
      };
      return walk(document);
    };
  });
  await expect.poll(() => page.evaluate(() => (window.__deep('jf-action-ledger')?.shadowRoot?.querySelector('lit-virtualizer') as
    unknown as { items?: unknown[] } | null)?.items?.length ?? 0), { timeout: 15_000 }).toBeGreaterThan(100);
}

async function settle(page: Page, ms = 400): Promise<void> {
  await page.waitForTimeout(ms);
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

/** The uncaught ResizeObserver-loop errors so far, from the window's error event and the page's own report. */
async function loopErrors({ page, pageErrors }: Opened): Promise<string[]> {
  const reported = await page.evaluate(() => window.__uncaught);
  return [...reported, ...pageErrors].filter((message) => /ResizeObserver loop/i.test(message));
}

/** Scroll the feed's scroller to `where` (0: the newest, 1: the oldest) a screenful at a time, letting the list measure each. */
async function scrollFeed(page: Page, where: 0 | 1): Promise<void> {
  for (let step = 0; step < 60; step += 1) {
    const done = await page.evaluate((where) => {
      const feed = window.__deep('jf-action-ledger')!;
      let node: Node | null = feed;
      let scroller: Element | null = null;
      while (node && !scroller) {
        node = node instanceof ShadowRoot ? node.host : (node as Element).assignedSlot ?? node.parentNode;
        if (node instanceof Element && /(auto|scroll)/.test(getComputedStyle(node).overflowY) && node.scrollHeight > node.clientHeight + 1) scroller = node;
      }
      if (!scroller) return true;
      const max = scroller.scrollHeight - scroller.clientHeight;
      const target = where === 1 ? max : 0;
      scroller.scrollTop = where === 1 ? Math.min(max, scroller.scrollTop + scroller.clientHeight) : Math.max(0, scroller.scrollTop - scroller.clientHeight);
      return Math.abs(scroller.scrollTop - target) <= 1;
    }, where);
    await settle(page, 150);
    if (done) break;
  }
}

for (const [width, height, text, forced] of [[1280, 720, '', false], [600, 400, '200%', false], [1280, 720, '', true]] as const) {
  const size = `${width}×${height}${text ? ` with ${text} text` : ''}${forced ? ' in forced colours' : ''}`;

  it(`FC8 at ${size}, ${ROWS} records draw and scroll without an uncaught ResizeObserver loop error`, async () => {
    const opened = await open(width, height, text, forced);
    await settle(opened.page, 800);
    expect(await loopErrors(opened), 'no loop error while the feed first draws').toEqual([]);
    await scrollFeed(opened.page, 1);
    await scrollFeed(opened.page, 0);
    await settle(opened.page, 600);
    expect(await loopErrors(opened), 'no loop error while scrolling to the oldest record and back').toEqual([]);
  }, 90_000);
}

for (const [width, height, text, forced] of [[1280, 720, '', false], [600, 400, '200%', false], [1280, 720, '', true]] as const) {
  const size = `${width}×${height}${text ? ` with ${text} text` : ''}${forced ? ' in forced colours' : ''}`;

  it(`FC9 at ${size}, the rows on screen read in words, never in recorded codes`, async () => {
    const opened = await open(width, height, text, forced);
    // At 600×400 with 200% text the list starts below what the window shows: bring it on screen first.
    await opened.page.evaluate(() => window.__deep('jf-action-ledger')!.shadowRoot!.querySelector('[data-testid="feed-list"]')!
      .scrollIntoView({ block: 'start', behavior: 'instant' }));
    await settle(opened.page, 600);
    const rows = await opened.page.evaluate(() => [...window.__deep('jf-action-ledger')!.shadowRoot!.querySelectorAll<HTMLElement>('.entry')]
      .map((entry) => entry.innerText.replace(/\s+/g, ' ').trim()));
    expect(rows.length, 'rows are drawn').toBeGreaterThan(5);
    for (const row of rows) {
      expect(row, `row text: ${row}`).not.toMatch(/\b(SUCCESS|FAILURE|DENIED|GATED|DONE|FAILED|TYPED_CONFIRM)\b|\bbackend\b/);
    }
  }, 90_000);
}

/**
 * The feed's rows as the person finds them: how many are drawn, how far the feed's scroller is
 * scrolled, and whether the first row is on screen at once when the list is brought to the top of
 * the view (read in the same task, before the list could draw anything in response).
 */
function firstRows(page: Page): Promise<{ drawn: number; scrollTop: number; firstOnScreen: boolean }> {
  return page.evaluate(() => {
    const root = window.__deep('jf-action-ledger')!.shadowRoot!;
    let node: Node | null = root.host;
    let scroller: Element | null = null;
    while (node && !scroller) {
      node = node instanceof ShadowRoot ? node.host : (node as Element).assignedSlot ?? node.parentNode;
      if (node instanceof Element && /(auto|scroll)/.test(getComputedStyle(node).overflowY)) scroller = node;
    }
    const drawn = root.querySelectorAll('.entry').length;
    const scrollTop = scroller!.scrollTop;
    const list = root.querySelector('[data-testid="feed-list"]')!;
    scroller!.scrollTop += list.getBoundingClientRect().top - scroller!.getBoundingClientRect().top;
    const view = scroller!.getBoundingClientRect();
    const first = root.querySelector('.entry[data-index="0"]')?.getBoundingClientRect();
    const firstOnScreen = !!first && first.height > 0 && first.top < view.bottom && first.bottom > view.top;
    scroller!.scrollTop = scrollTop;
    return { drawn, scrollTop, firstOnScreen };
  });
}

/** Without the person scrolling, the first rows are drawn and on screen as soon as the list is. */
async function expectFirstRows(page: Page, when: string): Promise<void> {
  await expect.poll(() => page.evaluate(() => window.__deep('jf-action-ledger')!.shadowRoot!.querySelectorAll('.entry').length),
    { message: `rows drawn ${when}, before any scroll`, timeout: 4_000 }).toBeGreaterThan(0);
  const rows = await firstRows(page);
  expect(rows.firstOnScreen, `the first row is on screen as soon as the list is, ${when}`).toBe(true);
}

for (const [width, height, text, forced] of [[1280, 720, '', false], [600, 400, '200%', false], [600, 400, '200%', true]] as const) {
  const size = `${width}×${height}${text ? ` with ${text} text` : ''}${forced ? ' in forced colours' : ''}`;

  it(`FC12 at ${size}, the first rows are drawn on opening, after a filter change, a reload and a return from Health`, async () => {
    const { page } = await open(width, height, text, forced);
    expect((await firstRows(page)).scrollTop, 'Activity opens at its top').toBe(0);
    await expectFirstRows(page, 'on opening');

    // The person brings the quick filters to the top of the view and changes the filter there.
    const quick = page.locator('jf-action-ledger [data-testid="feed-quick"]');
    await quick.evaluate((group) => group.scrollIntoView({ block: 'start', behavior: 'instant' }));
    await quick.locator('[data-quick="agents"]').click();
    await quick.locator('[data-quick="all"]').click();
    await expectFirstRows(page, 'after a filter change');

    await page.reload({ timeout: 45_000 });
    await loaded(page);
    await expectFirstRows(page, 'after a reload');

    await page.getByRole('tab', { name: 'Health' }).click();
    await page.getByRole('tab', { name: /^Activity/ }).click();
    await loaded(page);
    await expectFirstRows(page, 'after returning from Health');
  }, 90_000);
}
