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

/** Where the feed's focus is, and whether that record is rendered and on screen. */
function focusedRecord(page: Page): Promise<{ key: string | null; onScreen: boolean; rendered: boolean }> {
  return page.evaluate(() => {
    const root = document.querySelector('jf-action-ledger')!.shadowRoot!;
    const view = document.getElementById('scroller')!.getBoundingClientRect();
    const active = root.activeElement;
    const row = active?.querySelector('[data-key]') ?? active?.closest('[data-key]') ?? null;
    const box = row?.getBoundingClientRect();
    return { key: row?.getAttribute('data-key') ?? null, rendered: !!row?.isConnected,
      onScreen: !!box && box.top >= view.top - 1 && box.bottom <= view.bottom + 1 };
  });
}

it.each([
  [1280, 720, '', ''], [600, 400, ' with text at 200 percent', '?text=200%25'],
] as const)('A16 — at %i×%i%s, Home, End, Ctrl+Home, Ctrl+End, PageUp and PageDown reach the newest and oldest loaded rows, rendered and focused', async (width, height, _text, query) => {
  const context = await browser.newContext({ viewport: { width, height } });
  try {
    const page = await open(context, query);
    await page.evaluate(() => (window as unknown as { __seed(a: number, b: number): void }).__seed(520, 520));
    await expect.poll(() => renderedKeys(page)).toContain('operation:k519');
    // The ends of the loaded feed, newest first; this run's local records share instants, so the
    // oldest is whichever of them the feed orders last.
    const [newest, oldest] = await page.evaluate(() => {
      const items = (document.querySelector('jf-action-ledger')!.shadowRoot!
        .querySelector('lit-virtualizer') as unknown as { items: Array<{ key: string }> }).items;
      return [items[0]!.key, items[items.length - 1]!.key];
    });
    expect(newest).toBe('operation:k519');
    expect(oldest).toMatch(/^fe-effect:/);
    // Read down the feed with the wheel, then click a record there, as a reader would.
    await page.mouse.move(width / 2, height / 2);
    for (let i = 0; i < 30; i++) {
      await page.mouse.wheel(0, 600);
      await frames(page, 1);
    }
    await frames(page);
    expect(await renderedKeys(page), 'scrolled away from the newest record').not.toContain(newest);
    await page.locator('jf-action-ledger .item[data-key]').nth(2).click({ position: { x: 2, y: 2 } });
    const onScreen = (key: string): Promise<boolean> => page.evaluate((k) => {
      const view = document.getElementById('scroller')!.getBoundingClientRect();
      const box = document.querySelector('jf-action-ledger')!.shadowRoot!
        .querySelector(`.item[data-key="${k}"]`)?.getBoundingClientRect();
      return !!box && box.top >= view.top - 1 && box.bottom <= view.bottom + 1;
    }, key);
    const press = async (key: string, expected: string): Promise<void> => {
      await page.keyboard.press(key);
      await expect.poll(() => onScreen(expected), { timeout: 5_000, message: `${key} brings ${expected} on screen` }).toBe(true);
      await expect.poll(() => focusedRecord(page), { timeout: 5_000, message: `${key} focuses ${expected}` })
        .toEqual({ key: expected, rendered: true, onScreen: true });
    };
    await press('Control+Home', newest);
    await press('Control+End', oldest);
    await press('Home', newest);
    await press('End', oldest);
    // PageUp and PageDown move a screenful, every step rendered and on screen, and reach the ends.
    const indexOf = (key: string | null): Promise<number> => page.evaluate((k) => (document.querySelector('jf-action-ledger')!
      .shadowRoot!.querySelector('lit-virtualizer') as unknown as { items: Array<{ key: string }> }).items
      .findIndex((item) => item.key === k), key);
    const pageTo = async (key: 'PageUp' | 'PageDown', expected: string): Promise<void> => {
      let last = await indexOf((await focusedRecord(page)).key);
      for (let step = 0; step < 5; step++) {
        await page.keyboard.press(key);
        await expect.poll(async () => indexOf((await focusedRecord(page)).key), { message: `${key} moves` })
          .not.toBe(last);
        const now = await focusedRecord(page);
        expect(now).toMatchObject({ rendered: true, onScreen: true });
        const index = await indexOf(now.key);
        expect(key === 'PageUp' ? index < last : index > last, `${key} moves ${key === 'PageUp' ? 'up' : 'down'}`).toBe(true);
        if (now.key === expected) return;
        last = index;
      }
      throw new Error(`${key} did not reach ${expected}`);
    };
    await page.keyboard.press('PageUp');
    await expect.poll(async () => (await focusedRecord(page)).key).not.toBe(oldest);
    await pageTo('PageDown', oldest);
    await press('Home', newest);
    await page.keyboard.press('PageDown');
    await expect.poll(async () => (await focusedRecord(page)).key).not.toBe(newest);
    await pageTo('PageUp', newest);
  } finally {
    await context.close();
  }
}, 60_000);

it.each([
  [1280, 720, '', ''], [600, 400, ' with text at 200 percent', '?text=200%25'],
] as const)('A16 — at %i×%i%s, with focus on the page, not the feed, the scrolling keys move the feed to its newest and oldest loaded rows', async (width, height, _text, query) => {
  const context = await browser.newContext({ viewport: { width, height } });
  try {
    const page = await open(context, query);
    await page.evaluate(() => (window as unknown as { __seed(a: number, b: number): void }).__seed(520, 520));
    await expect.poll(() => renderedKeys(page)).toContain('operation:k519');
    const [newest, oldest] = await page.evaluate(() => {
      const items = (document.querySelector('jf-action-ledger')!.shadowRoot!
        .querySelector('lit-virtualizer') as unknown as { items: Array<{ key: string }> }).items;
      return [items[0]!.key, items[items.length - 1]!.key];
    });
    // Read down the feed with the wheel, then click the scroller beside the records (its padding, which
    // takes no focus): the page keeps focus, and the browser's keys now scroll the feed's scroller.
    await page.mouse.move(width / 2, height / 2);
    for (let i = 0; i < 30; i++) {
      await page.mouse.wheel(0, 600);
      await frames(page, 1);
    }
    await frames(page);
    expect(await renderedKeys(page), 'scrolled away from the newest record').not.toContain(newest);
    await page.mouse.click(3, height / 2);
    /** Where the scroller rests, and the feed's rows on screen: fully, and in part (by position in the feed). */
    type Rest = { top: number; max: number; focus: string; shown: string[]; first: number; last: number; newestTop: number | null };
    const state = (): Promise<Rest> => page.evaluate(() => {
      const scroller = document.getElementById('scroller')!;
      const view = scroller.getBoundingClientRect();
      const root = document.querySelector('jf-action-ledger')!.shadowRoot!;
      const items = (root.querySelector('lit-virtualizer') as unknown as { items: Array<{ key: string }> }).items;
      const rows = Array.from(root.querySelectorAll('.item[data-key]')).map((row) => ({ key: row.getAttribute('data-key')!,
        box: row.getBoundingClientRect() }));
      const visible = rows.filter(({ box }) => box.bottom > view.top && box.top < view.bottom)
        .map(({ key }) => items.findIndex((item) => item.key === key));
      const newest = rows.find(({ key }) => key === items[0]!.key);
      return { top: scroller.scrollTop, max: scroller.scrollHeight - scroller.clientHeight,
        focus: document.activeElement?.localName ?? '',
        shown: rows.filter(({ box }) => box.top >= view.top - 1 && box.bottom <= view.bottom + 1).map(({ key }) => key),
        first: Math.min(...visible), last: Math.max(...visible), newestTop: newest ? newest.box.top - view.top : null };
    });
    expect((await state()).focus).toBe('body');
    /** Press `key` and return where the feed comes to rest once it has been still for half a second. */
    const settle = async (key: string): Promise<Rest> => {
      await page.keyboard.press(key);
      let last = await state();
      for (let still = 0; still < 15;) {
        await frames(page, 2);
        const now = await state();
        still = now.top === last.top && now.max === last.max ? still + 1 : 0;
        last = now;
      }
      return last;
    };
    const atStart = (rest: Rest, key: string): void => {
      expect(rest.top, `${key} reaches the start of the scroller`).toBeCloseTo(0, 0);
      expect(rest.newestTop, `${key} shows the start of the newest record`).not.toBeNull();
      expect(rest.newestTop!).toBeGreaterThanOrEqual(-1);
    };
    const atEnd = (rest: Rest, key: string): void => {
      expect(rest.top, `${key} reaches the end of the scroller`).toBeCloseTo(rest.max, 0);
      expect(rest.shown, `${key} brings ${oldest} on screen`).toContain(oldest);
    };
    for (const key of ['Control+Home', 'Control+End', 'Home', 'End'] as const) {
      const rest = await settle(key);
      if (key.endsWith('Home')) atStart(rest, key);
      else atEnd(rest, key);
      expect(rest.focus, `${key} leaves focus where it was`).toBe('body');
    }
    // PageUp and PageDown move a screenful at a time, never past a row unseen, and reach the ends.
    const pageTo = async (key: 'PageUp' | 'PageDown'): Promise<void> => {
      let before = await state();
      for (let step = 0; step < 200; step++) {
        const now = await settle(key);
        expect(key === 'PageUp' ? now.top < before.top : now.top > before.top, `${key} moves`).toBe(true);
        if (key === 'PageDown') expect(now.first, 'PageDown skips no row').toBeLessThanOrEqual(before.last + 1);
        else expect(now.last, 'PageUp skips no row').toBeGreaterThanOrEqual(before.first - 1);
        expect(now.focus).toBe('body');
        if (key === 'PageUp' ? now.top < 1 : now.top > now.max - 1) {
          if (key === 'PageUp') atStart(now, key);
          else atEnd(now, key);
          return;
        }
        before = now;
      }
      throw new Error(`${key} did not reach the end`);
    };
    await settle('Home');
    for (let i = 0; i < 6; i++) await settle('PageDown');
    await pageTo('PageUp');
    await settle('End');
    for (let i = 0; i < 6; i++) await settle('PageUp');
    await pageTo('PageDown');
  } finally {
    await context.close();
  }
}, 120_000);

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
