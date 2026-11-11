// SPDX-License-Identifier: Apache-2.0
// Real Chromium, private Vite server; run with vitest.config.lockdown.ts.
// S6 Activity — the one feed in a real browser: windowed navigation over more than a thousand
// loaded records (A16), forced colours (A19), and a 600×400 window at 200% text (A20).
import { afterAll, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let output: string;
let url: string;

const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
import '/src/shell-v0/components/ActionLedgerView.ts';
import { recordEffect } from '/src/shell-v0/substrates/effects/index.ts';
const params = new URLSearchParams(location.search);
if (params.get('text')) document.documentElement.style.fontSize = params.get('text');
const sources = [];
let seq = 0;
function fake(url) {
  const listeners = {};
  const source = { url, readyState: 1,
    addEventListener: (type, fn) => { (listeners[type] ??= []).push(fn); },
    removeEventListener: (type, fn) => { listeners[type] = (listeners[type] ?? []).filter(f => f !== fn); },
    close() {},
    emit(frameKind, payload) {
      seq += 1;
      const data = JSON.stringify({ streamId: 'test', frameKind, seq, ts: '2026-11-01T00:00:00Z', resumeToken: 't' + seq, payload });
      for (const fn of listeners.frame ?? []) fn({ data });
    } };
  return source;
}
// Ledger rows are newer than this run's local records, so the newest is k<last> and the oldest a local one.
const op = (i, extra = {}) => ({ id: 'operation:k' + i, kind: 'operation', originator: i % 2 ? 'agent' : 'user',
  operationId: 'core.search-index', outcome: i % 7 ? 'SUCCESS' : 'FAILURE',
  occurredAt: new Date(Date.UTC(2030, 0, 1, 8, 0, 0) + i * 1000).toISOString(), ...extra });
window.__op = op;
window.__emit = (frameKind, payload) => { for (const s of sources) s.emit(frameKind, payload); };
window.__seed = (ledgerRows, localRows) => {
  for (let i = 0; i < localRows; i++) {
    const entry = recordEffect({ kind: 'open-pane', paneId: 'p' + i }, undefined, { originator: 'agent' });
    if (i === 0) window.__oldestLocal = entry.ledgerId;
  }
  window.__emit('LIFECYCLE', { kind: 'snapshot', entries: Array.from({ length: ledgerRows }, (_, i) => op(i)) });
};
const el = document.createElement('jf-action-ledger');
el.eventSourceFactory = (u) => { const s = fake(u); sources.push(s); return s; };
el.historyEventSourceFactory = (u) => fake(u);
el.ledgerFetch = async () => ({ ok: true, status: 200, json: async () => ({ entries: [] }) });
el.resolveHash = async () => null;
// The app scrolls inside its surface, not the page (tokens.css locks the page): mirror that.
const scroller = document.createElement('main');
scroller.id = 'scroller';
scroller.style.cssText = 'height: 100vh; overflow: auto; padding: 0 8px; box-sizing: border-box';
scroller.append(el);
document.body.append(scroller);
document.body.dataset.ready = 'true';
</script></body></html>`;

beforeAll(async () => {
  output = await mkdtemp(path.join(os.tmpdir(), 'activity-feed-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'activity-feed-harness',
      configureServer(vite) {
        vite.middlewares.use((req, res, next) => {
          if (!req.url?.startsWith('/feed')) return next();
          res.setHeader('Content-Type', 'text/html');
          res.end(HARNESS);
        });
      },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/feed`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: process.env.TMPDIR ?? output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned headless Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
}, 60_000);

afterAll(async () => {
  await browser?.close();
  await browserServer?.close();
  await server?.close();
  if (output) await rm(output, { recursive: true, force: true });
});

async function open(context: BrowserContext, query = ''): Promise<Page> {
  const page = await context.newPage();
  await page.goto(`${url}${query}`);
  await page.waitForSelector('body[data-ready="true"]');
  await expect.poll(() => page.locator('jf-action-ledger [data-testid="feed-quick"]').count(), { timeout: 10_000 }).toBe(1);
  return page;
}

/** Let the windowed list lay out after a scroll or an arrival. */
function frames(page: Page, count = 4): Promise<unknown> {
  return page.evaluate((n) => new Promise((resolve) => {
    let left = n;
    const next = (): void => { if (--left > 0) requestAnimationFrame(next); else resolve(null); };
    requestAnimationFrame(next);
  }), count);
}

/** Keys of the feed rows currently in the document (a windowed list holds only some). */
function renderedKeys(page: Page): Promise<string[]> {
  return page.evaluate(() => Array.from(document.querySelector('jf-action-ledger')!.shadowRoot!
    .querySelectorAll('.item[data-key]')).map((row) => row.getAttribute('data-key')!));
}

it('A16 — more than 500 ledger and 500 local records are all reachable, newest to oldest, through a windowed list', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  try {
    const page = await open(context);
    await page.evaluate(() => (window as unknown as { __seed(a: number, b: number): void }).__seed(520, 520));
    await expect.poll(() => renderedKeys(page)).toContain('operation:k519');
    const rendered = await renderedKeys(page);
    expect(rendered.length, 'only a window of the 1,040 rows is in the document').toBeLessThan(200);
    const items = await page.evaluate(() => (document.querySelector('jf-action-ledger')!.shadowRoot!
      .querySelector('lit-virtualizer') as unknown as { items: unknown[] }).items.length);
    expect(items).toBe(1040);
    const oldest = await page.evaluate(() => (window as unknown as { __oldestLocal: string }).__oldestLocal);
    await expect.poll(async () => {
      await page.evaluate(() => { const m = document.getElementById('scroller')!; m.scrollTop = m.scrollHeight; });
      return renderedKeys(page);
    }, { timeout: 10_000 }).toContain(oldest);
    await expect.poll(async () => {
      await page.evaluate(() => { document.getElementById('scroller')!.scrollTop = 0; });
      return renderedKeys(page);
    }, { timeout: 10_000 }).toContain('operation:k519');
  } finally {
    await context.close();
  }
}, 60_000);

it('A16 — a live arrival keeps the reader where they were', async () => {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  try {
    const page = await open(context);
    await page.evaluate(() => (window as unknown as { __seed(a: number, b: number): void }).__seed(400, 0));
    await expect.poll(() => renderedKeys(page)).toContain('operation:k399');
    await page.evaluate(() => { document.getElementById('scroller')!.scrollTop = 4000; });
    await frames(page);
    const anchor = await page.evaluate(() => {
      const rows = Array.from(document.querySelector('jf-action-ledger')!.shadowRoot!.querySelectorAll('.item[data-key]'));
      const row = rows.find((r) => r.getBoundingClientRect().top >= 0)!;
      return { key: row.getAttribute('data-key')!, top: row.getBoundingClientRect().top };
    });
    await page.evaluate(() => {
      const w = window as unknown as { __emit(k: string, p: unknown): void; __op(i: number, extra?: object): unknown };
      w.__emit('UPDATE', w.__op(1000, { occurredAt: '2031-01-01T00:00:00.000Z' }));
    });
    await expect.poll(() => page.evaluate(() => (document.querySelector('jf-action-ledger')!.shadowRoot!
      .querySelector('lit-virtualizer') as unknown as { items: unknown[] }).items.length)).toBe(401);
    await frames(page);
    const after = await page.evaluate((key) => document.querySelector('jf-action-ledger')!.shadowRoot!
      .querySelector(`.item[data-key="${key}"]`)?.getBoundingClientRect().top ?? null, anchor.key);
    expect(after).not.toBeNull();
    expect(Math.abs(after! - anchor.top)).toBeLessThan(2);
  } finally {
    await context.close();
  }
}, 60_000);

it.each(['light', 'dark'] as const)('A19 — on a %s forced-colours palette, selection, focus, unread and outcome do not rely on colour', async (colorScheme) => {
  const context = await browser.newContext({ forcedColors: 'active', colorScheme, viewport: { width: 1280, height: 800 } });
  try {
    const page = await open(context);
    await page.evaluate(() => (window as unknown as { __seed(a: number, b: number): void }).__seed(8, 0));
    await expect.poll(() => renderedKeys(page)).toContain('operation:k7');
    const system = await page.evaluate(() => {
      const probe = document.createElement('div');
      probe.style.cssText = 'forced-color-adjust: none; background: CanvasText; color: Highlight';
      document.body.append(probe);
      const c = getComputedStyle(probe);
      const result = { canvasText: c.backgroundColor, highlight: c.color };
      probe.remove();
      return result;
    });
    const look = () => page.evaluate(() => {
      const root = document.querySelector('jf-action-ledger')!.shadowRoot!;
      const quick = Array.from(root.querySelectorAll<HTMLElement>('[data-quick]')).map((b) => {
        const c = getComputedStyle(b);
        return { checked: b.getAttribute('aria-checked'), border: c.borderTopWidth, borderColor: c.borderTopColor,
          focused: b.matches(':focus-visible'), outlineStyle: c.outlineStyle, outlineColor: c.outlineColor };
      });
      const dot = root.querySelector('[data-testid="ledger-new-dot"]')!;
      const failed = root.querySelector('.item[data-key="operation:k7"] [data-testid="ledger-outcome"]')!;
      return { quick, dot: getComputedStyle(dot).backgroundColor, dotLabel: dot.getAttribute('aria-label'),
        outcome: failed.textContent!.replace(/\s+/g, ' ').trim() };
    });
    await page.keyboard.press('Tab');
    await page.keyboard.press('ArrowRight');
    const state = await look();
    const [all, agents] = state.quick;
    expect(agents).toMatchObject({ checked: 'true', border: '2px', borderColor: system.highlight, focused: true,
      outlineStyle: 'solid', outlineColor: system.highlight });
    expect(all).toMatchObject({ checked: 'false', border: '1px', focused: false });
    expect(state.dot).toBe(system.canvasText);
    expect(state.dotLabel).toBe('new since you looked');
    expect(state.outcome).toMatch(/^\S+ FAILURE$/);
  } finally {
    await context.close();
  }
}, 60_000);

it.each([
  ['light', 600, 400], ['dark', 600, 400], ['light', 1280, 800],
] as const)('A20 — %s at %i×%i with text at 200 percent: filters and advanced controls are reachable without sideways scrolling', async (colorScheme, width, height) => {
  const context = await browser.newContext({ colorScheme, viewport: { width, height } });
  try {
    const page = await open(context, '?text=200%25');
    await page.evaluate(() => (window as unknown as { __seed(a: number, b: number): void }).__seed(30, 3));
    await expect.poll(() => renderedKeys(page)).toContain('operation:k29');
    await page.locator('jf-action-ledger [data-testid="feed-advanced"] summary').click();
    const layout = await page.evaluate(() => {
      const root = document.querySelector('jf-action-ledger')!.shadowRoot!;
      const controls = Array.from(root.querySelectorAll<HTMLElement>('[data-quick], [data-testid="feed-advanced"] summary, '
        + '[data-testid="feed-routine-choice"], [data-testid="feed-date-today"], [data-testid="ledger-mark-read"]'));
      const scroller = document.getElementById('scroller')!;
      return { page: Math.max(document.documentElement.scrollWidth, scroller.scrollWidth), viewport: scroller.clientWidth,
        rights: controls.map((c) => Math.ceil(c.getBoundingClientRect().right)), count: controls.length };
    });
    expect(layout.count).toBe(9);
    expect(layout.page, 'no page-level sideways overflow').toBeLessThanOrEqual(layout.viewport);
    for (const right of layout.rights) expect(right).toBeLessThanOrEqual(layout.viewport);
  } finally {
    await context.close();
  }
}, 60_000);
