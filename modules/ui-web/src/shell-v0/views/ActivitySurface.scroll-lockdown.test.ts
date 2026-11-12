// SPDX-License-Identifier: Apache-2.0
// Real Chromium, private Vite server; run with vitest.config.lockdown.ts.
// S6 Activity in its real place: the real Shell opens System › Activity, whose ledger arrives over
// the shared shell-events stream the way an Engine sends it (the channel's `connected`, then its
// `snapshot` of 140 records). Exactly one scroller holds the feed, inside the window, and the wheel,
// touch and the keys move it to both ends, at 1280×720 and at 600×400 with 200% text. The Health
// and Logs tabs keep their own scrolling.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

const ROWS = 140;

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
const op = (i) => ({ id: 'operation:k' + i, kind: 'operation', originator: i % 2 ? 'agent' : 'user',
  operationId: 'core.open-file', outcome: i % 9 ? 'SUCCESS' : 'FAILURE',
  occurredAt: new Date(Date.UTC(2030, 0, 1, 8) + i * 1000).toISOString() });
const entries = Array.from({ length: ${ROWS} }, (_, i) => op(i));
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
customElements.define('jf-scroll-search-probe', class extends HTMLElement {});
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL',
  presentation: { labelKey: id, descriptionKey: id }, consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('core.search-v3-surface', 'jf-scroll-search-probe'),
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
  output = await mkdtemp(path.join(os.tmpdir(), 'activity-scroll-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'activity-scroll-harness',
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
    /** The element `tag` anywhere in the page, across shadow roots. */
    __deep(tag: string): Element | null;
    /** The ancestors of `el` (across shadow roots) that scroll and have more to show than they hold. */
    __scrollers(el: Element): Element[];
  }
}

async function open(width: number, height: number, text = ''): Promise<Page> {
  context = await browser.newContext({ viewport: { width, height }, hasTouch: true });
  const page = await context.newPage();
  page.on('pageerror', (error) => console.info('pageerror', error.message));
  await page.goto(`${url}${text ? `?text=${text}` : ''}#justsearch://surface/core.activity-surface`, { timeout: 45_000 });
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
    window.__scrollers = (el) => {
      const out: Element[] = [];
      let node: Node | null = el;
      while (node) {
        node = node instanceof ShadowRoot ? node.host : (node as Element).assignedSlot ?? node.parentNode;
        if (node instanceof Element && /(auto|scroll)/.test(getComputedStyle(node).overflowY)
          && node.scrollHeight > node.clientHeight + 1) out.push(node);
      }
      return out;
    };
  });
  await expect.poll(() => loadedEntries(page), { timeout: 15_000 }).toBeGreaterThanOrEqual(ROWS);
  return page;
}

/** Entries the windowed feed holds (it renders only those near the screen). */
function loadedEntries(page: Page): Promise<number> {
  return page.evaluate(() => (window.__deep('jf-action-ledger')?.shadowRoot?.querySelector('lit-virtualizer') as
    unknown as { items?: unknown[] } | null)?.items?.length ?? 0);
}

/** The one scroller that holds `tag`, its position and its box; fails unless there is exactly one, inside the window. */
async function theScroller(page: Page, tag: string): Promise<{ top: number; max: number; box: { x: number; y: number; width: number; height: number } }> {
  const { diagnosis, ...found } = await page.evaluate((tag) => {
    const el = window.__deep(tag)!;
    const scrollers = window.__scrollers(el);
    const one = scrollers[0];
    if (scrollers.length !== 1 || !one) {
      const items = (window.__deep('jf-action-ledger')?.shadowRoot?.querySelector('lit-virtualizer') as
        unknown as { items?: unknown[] } | null)?.items?.length ?? 0;
      const lines = [`viewport=${window.innerWidth}×${window.innerHeight} devicePixelRatio=${window.devicePixelRatio} items=${items}`];
      let node: Node | null = el;
      while (node) {
        if (node instanceof Element) {
          const style = getComputedStyle(node);
          lines.push(`${node.localName} class=${JSON.stringify(node.getAttribute('class') ?? '')} overflowY=${style.overflowY} display=${style.display} clientHeight=${node.clientHeight} scrollHeight=${node.scrollHeight}`);
        }
        node = node instanceof ShadowRoot ? node.host : (node as Element).assignedSlot ?? node.parentNode;
      }
      return { count: scrollers.length, names: scrollers.map((s) => s.localName + '.' + s.className), diagnosis: `\n${lines.join('\n')}` };
    }
    const r = one.getBoundingClientRect();
    return { count: 1, diagnosis: '', top: one.scrollTop, max: one.scrollHeight - one.clientHeight,
      box: { x: r.x, y: r.y, width: r.width, height: r.height }, bottom: r.bottom, innerHeight: window.innerHeight };
  }, tag);
  expect(found.count, `exactly one scroller holds ${tag} (found ${JSON.stringify(found)})${diagnosis}`).toBe(1);
  const { top, max, box, bottom, innerHeight } = found as { top: number; max: number; bottom: number; innerHeight: number;
    box: { x: number; y: number; width: number; height: number } };
  expect(bottom, 'the scroller ends inside the window').toBeLessThanOrEqual(innerHeight + 0.5);
  expect(box.height, 'the scroller shows something').toBeGreaterThan(40);
  return { top, max, box };
}

/** Let smooth scrolls and the windowed list's layout finish. */
async function settle(page: Page, ms = 400): Promise<void> {
  await page.waitForTimeout(ms);
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

/** The focused feed entry's position (1 is the newest) and whether it is on screen in the scroller. */
function focusedEntry(page: Page): Promise<{ pos: number; size: number; onScreen: boolean } | null> {
  return page.evaluate(() => {
    const feed = window.__deep('jf-action-ledger')!;
    const entry = feed.shadowRoot!.activeElement?.closest<HTMLElement>('.entry');
    if (!entry) return null;
    const scroller = window.__scrollers(feed)[0];
    const view = scroller ? scroller.getBoundingClientRect() : { top: 0, bottom: window.innerHeight };
    const box = entry.getBoundingClientRect();
    return { pos: Number(entry.getAttribute('aria-posinset')), size: Number(entry.getAttribute('aria-setsize')),
      onScreen: box.top >= view.top - 1 && box.top < view.bottom - 4 };
  });
}

async function press(page: Page, key: string): Promise<void> {
  await page.keyboard.press(key);
  await settle(page, 700);
}

for (const [width, height, text] of [[1280, 720, ''], [600, 400, '200%']] as const) {
  const size = `${width}×${height}${text ? ` with ${text} text` : ''}`;

  it(`at ${size}, one scroller holds the Activity feed, and the wheel, touch and keys reach both ends`, async () => {
    const page = await open(width, height, text);
    await expect.poll(() => page.evaluate(() => window.__scrollers(window.__deep('jf-action-ledger')!).length),
      { timeout: 15_000 }).toBe(1);
    const start = await theScroller(page, 'jf-action-ledger');
    expect(start.max, 'the feed is taller than its scroller').toBeGreaterThan(start.box.height);

    // The wheel over the feed.
    const feedBox = (await page.evaluate(() => {
      const r = window.__deep('jf-action-ledger')!.shadowRoot!.querySelector('.list')!.getBoundingClientRect();
      return { x: r.x, y: r.y, width: r.width };
    }));
    const point = { x: feedBox.x + Math.min(200, feedBox.width / 2), y: start.box.y + start.box.height / 2 };
    await page.mouse.move(point.x, point.y);
    await page.mouse.wheel(0, 600);
    await settle(page);
    const wheeled = await theScroller(page, 'jf-action-ledger');
    expect(wheeled.top, 'the wheel moves the feed').toBeGreaterThan(start.top + 100);

    // A touch drag over the feed (the finger moves up: the feed moves on).
    const cdp = await context!.newCDPSession(page);
    const touch = (type: 'touchStart' | 'touchMove' | 'touchEnd', y: number): Promise<unknown> => cdp.send('Input.dispatchTouchEvent',
      { type, touchPoints: type === 'touchEnd' ? [] : [{ x: point.x, y }] });
    await touch('touchStart', point.y);
    for (let step = 1; step <= 10; step += 1) await touch('touchMove', point.y - (step * start.box.height) / 25);
    await touch('touchEnd', 0);
    await settle(page);
    const touched = await theScroller(page, 'jf-action-ledger');
    expect(touched.top, 'touch moves the feed').toBeGreaterThan(wheeled.top + 20);

    // Keys from a record: Ctrl+End, Ctrl+Home, End and Home reach the oldest and the newest record.
    // Read on with the wheel until a record's start is on screen, and click it.
    const recordOnScreen = (): Promise<{ x: number; y: number } | null> => page.evaluate(() => {
      const feed = window.__deep('jf-action-ledger')!;
      const view = window.__scrollers(feed)[0]!.getBoundingClientRect();
      const entry = [...feed.shadowRoot!.querySelectorAll<HTMLElement>('.entry')]
        .find((e) => { const b = e.getBoundingClientRect(); return b.top >= view.top && b.top < view.bottom - 12; });
      const b = entry?.getBoundingClientRect();
      return b ? { x: b.x + 8, y: b.y + Math.min(8, b.height / 2) } : null;
    });
    let record = await recordOnScreen();
    for (let turn = 0; turn < 10 && !record; turn += 1) {
      await page.mouse.wheel(0, 200);
      await settle(page);
      record = await recordOnScreen();
    }
    expect(record, 'reading on reaches the records').not.toBeNull();
    await page.mouse.click(record!.x, record!.y);
    await settle(page, 200);
    // The click also opens the record's details (a sheet at 600×400); Escape closes them and puts
    // the keyboard back on the record.
    await press(page, 'Escape');
    expect(await focusedEntry(page), 'a click on a record focuses it').not.toBeNull();
    for (const [key, pos] of [['Control+End', 'last'], ['Control+Home', 1], ['End', 'last'], ['Home', 1]] as const) {
      await press(page, key);
      const focused = await focusedEntry(page);
      expect(focused, `${key} keeps focus on a record`).not.toBeNull();
      expect(focused!.pos, `${key} reaches the ${pos === 1 ? 'newest' : 'oldest'} record`).toBe(pos === 1 ? 1 : focused!.size);
      expect(focused!.onScreen, `${key}: the record reached is on screen`).toBe(true);
    }

    // Keys with focus on the page (a click on the Activity heading, which takes no focus): the
    // browser's own Ctrl+End, Ctrl+Home, End and Home move the one scroller to its end and its start.
    await page.evaluate(() => {
      const feed = window.__deep('jf-action-ledger')!;
      (feed.shadowRoot!.activeElement as HTMLElement | null)?.blur();
      window.__scrollers(feed)[0]!.scrollTop = 0;
    });
    await settle(page);
    const heading = await page.evaluate(() => {
      const b = window.__deep('jf-activity-surface')!.shadowRoot!.querySelector('h2')!.getBoundingClientRect();
      return { x: b.x + 4, y: b.y + b.height / 2 };
    });
    await page.mouse.click(heading.x, heading.y);
    for (const [key, end] of [['Control+End', true], ['Control+Home', false], ['End', true], ['Home', false]] as const) {
      await press(page, key);
      await expect.poll(async () => {
        const now = await theScroller(page, 'jf-action-ledger');
        return end ? now.max - now.top : now.top;
      }, { timeout: 5_000, message: `${key} with focus on the page takes the scroller to its ${end ? 'end' : 'start'}` })
        .toBeLessThanOrEqual(1);
    }
  }, 90_000);

  it(`at ${size}, the Health and Logs tabs keep their own scrolling`, async () => {
    const page = await open(width, height, text);
    for (const [id, tag] of [['core.health-surface', 'jf-health-surface'], ['core.logs-surface', 'jf-log-surface']] as const) {
      await page.locator(`jf-surface-tabs button[data-tab-id="${id}"]`).click();
      await expect.poll(() => page.evaluate((tag) => !!window.__deep(tag)?.shadowRoot?.querySelector('.body'), tag),
        { timeout: 15_000 }).toBe(true);
      await settle(page, 800);
      // The tab hands the member its height: the member ends inside the window, and its own body is
      // its scroller. The wheel moves the body when it overflows. In the short window (600×400 with
      // 200% text) the member scrolls as a whole, its header with its body, so a header taller than
      // the tab cannot leave the body out of reach (follow-up C1 FC5).
      const body = await page.evaluate((tag) => {
        const member = window.__deep(tag)!;
        const b = member.shadowRoot!.querySelector('.body')!;
        const r = b.getBoundingClientRect();
        const host = member.getBoundingClientRect();
        return { overflowY: getComputedStyle(b).overflowY, hostBottom: host.bottom, bottom: r.bottom,
          innerHeight: window.innerHeight, hostOverflowY: getComputedStyle(member).overflowY,
          hostMax: member.scrollHeight - member.clientHeight, hostX: host.x + 20, hostY: Math.max(host.y, 0) + 10,
          x: r.x + 20, y: Math.min(r.y + r.height / 2, host.bottom - 4), height: r.height, max: b.scrollHeight - b.clientHeight };
      }, tag);
      expect(body.hostBottom, `${tag} ends inside the window`).toBeLessThanOrEqual(body.innerHeight + 0.5);
      if (text) {
        expect(body.hostOverflowY, `${tag} scrolls as a whole`).toBe('auto');
        expect(body.hostMax, `${tag} is longer than its tab`).toBeGreaterThan(1);
        await page.mouse.move(body.hostX, body.hostY);
        await page.mouse.wheel(0, 300);
        await settle(page);
        const moved = await page.evaluate((tag) => window.__deep(tag)!.scrollTop, tag);
        expect(moved, `the wheel moves ${tag}`).toBeGreaterThan(0);
        continue;
      }
      expect(body.overflowY, `${tag} scrolls in its own body`).toBe('auto');
      expect(body.bottom, `${tag}'s body ends inside the window`).toBeLessThanOrEqual(body.innerHeight + 0.5);
      expect(body.height, `${tag}'s body shows something`).toBeGreaterThan(20);
      if (body.max > 1) {
        await page.mouse.move(body.x, body.y);
        await page.mouse.wheel(0, 300);
        await settle(page);
        const moved = await page.evaluate((tag) => window.__deep(tag)!.shadowRoot!.querySelector('.body')!.scrollTop, tag);
        expect(moved, `the wheel moves ${tag}'s body`).toBeGreaterThan(0);
      }
    }
  }, 90_000);
}
