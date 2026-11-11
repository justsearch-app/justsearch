// SPDX-License-Identifier: Apache-2.0
// Real Chromium, private Vite server; run with vitest.config.lockdown.ts.
// S6 Activity, candidate 2 — the selected record's details in the real Shell: System › Activity,
// whose ledger arrives over the shared shell-events stream as an Engine sends it. The details sit in
// the shared details pane: beside the feed in a wide window (light and dark), a sheet over the
// window at 600×400 with 200% text. The keyboard reaches them with Tab and F6, Escape returns it to
// the record (also once the windowed list has dropped that record's row), and forced colours keep
// the selected and the focused record apart without colour.
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
const key = (i) => '019a0000-0000-7000-8000-' + String(i).padStart(12, '0');
const op = (i) => ({ id: 'operation:' + key(i), kind: 'operation', originator: i % 2 ? 'agent' : 'user', transport: 'BUTTON',
  operationId: 'core.open-file', outcome: i % 9 ? 'SUCCESS' : 'FAILURE',
  occurredAt: new Date(Date.UTC(2030, 0, 1, 8) + i * 1000).toISOString() });
const rows = Number(params.get('rows') ?? '${ROWS}');
const entries = Array.from({ length: rows }, (_, i) => op(i));
window.fetch = async (input) => {
  const path = new URL(String(input instanceof Request ? input.url : input), location.href).pathname;
  if (path.endsWith('/api/shell-events/stream')) {
    // The Engine on connect: the ledger channel's connected (no checkpoint), then its snapshot.
    return new Response(new ReadableStream({ start(controller) {
      controller.enqueue(frame('surface:action-ledger', 'LIFECYCLE', { kind: 'connected' }, false));
      controller.enqueue(frame('surface:action-ledger', 'LIFECYCLE', { kind: 'snapshot', entries }, true));
    } }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
  }
  if (path.startsWith('/api/operation-history/019a')) return json({ state: 'complete', historySince: 0, unitsCompleted: 1 });
  if (path.endsWith('/api/action-ledger')) return json({ entries });
  return json({ entries: [], conversations: [], messages: [] });
};
const { __seedForTest } = await import('/src/api/registry/SurfaceCatalogClient.ts');
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/chrome/Shell.ts');
// A turn the assistant takes in this window once the app is up: two reversible steps.
window.__recordTurn = async () => {
  const effects = await import('/src/shell-v0/substrates/effects/index.ts');
  const { CORE_PROVENANCE } = await import('/src/shell-v0/primitives/provenance.ts');
  const root = effects.recordEffect({ kind: 'open-pane', paneId: 'sources' }, CORE_PROVENANCE,
    { originator: 'agent' });
  effects.recordEffect({ kind: 'open-pane', paneId: 'outline' }, CORE_PROVENANCE,
    { originator: 'agent', causation: root.id });
  return root.ledgerId;
};
customElements.define('jf-details-search-probe', class extends HTMLElement {});
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL',
  presentation: { labelKey: id, descriptionKey: id }, consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('core.search-v3-surface', 'jf-details-search-probe'),
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
  output = await mkdtemp(path.join(os.tmpdir(), 'activity-details-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'activity-details-harness',
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
    __deep(tag: string): Element | null;
    __recordTurn(): Promise<string>;
    /** Where the keyboard is: the innermost focused element across shadow roots, with its trail. */
    __focus(): { tag: string; testid: string | null; key: string | null; role: string | null; inPane: boolean; inGrip: boolean };
  }
}

interface Options { width: number; height: number; text?: string; theme?: 'light' | 'dark'; forced?: boolean; rows?: number }

async function open({ width, height, text = '', theme, forced = false, rows }: Options): Promise<Page> {
  context = await browser.newContext({ viewport: { width, height }, ...(forced ? { forcedColors: 'active' as const } : {}) });
  const page = await context.newPage();
  page.on('pageerror', (error) => console.info('pageerror', error.message));
  const query = new URLSearchParams({ ...(text ? { text } : {}), ...(rows !== undefined ? { rows: String(rows) } : {}) }).toString();
  await page.goto(`${url}${query ? `?${query}` : ''}#justsearch://surface/core.activity-surface`, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await page.evaluate((theme) => {
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
    window.__focus = () => {
      let el: Element | null = document.activeElement;
      while (el?.shadowRoot?.activeElement) el = el.shadowRoot.activeElement;
      const trail: Element[] = [];
      for (let node: Node | null = el; node; node = node instanceof ShadowRoot ? node.host : (node as Element).assignedSlot ?? node.parentNode) {
        if (node instanceof Element) trail.push(node);
      }
      return { tag: el?.localName ?? '', testid: el?.getAttribute('data-testid') ?? null,
        key: (el as HTMLElement | null)?.closest?.('.entry')?.getAttribute('data-entry-key') ?? null,
        role: el?.getAttribute('role') ?? null,
        inPane: trail.some((n) => n.localName === 'jf-details-pane'), inGrip: el?.getAttribute('data-testid') === 'details-pane-grip' };
    };
    if (theme) document.documentElement.setAttribute('data-theme', theme);
  }, theme);
  await expect.poll(() => page.evaluate(() => {
    const feed = window.__deep('jf-action-ledger');
    feed?.scrollIntoView({ block: 'start' });
    return feed?.shadowRoot?.querySelectorAll('.entry').length ?? 0;
  }), { timeout: 15_000 }).toBeGreaterThan(0);
  return page;
}

async function settle(page: Page, ms = 300): Promise<void> {
  await page.waitForTimeout(ms);
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

/**
 * Click the record whose key is `key` (or the first one on screen), where it is drawn — once the
 * point is really on that record (the Shell may still be laying out its window).
 */
async function clickRecord(page: Page, key?: string): Promise<string> {
  const locate = (): Promise<{ x: number; y: number; key: string; hit: boolean } | null> => page.evaluate((key) => {
    const feed = window.__deep('jf-action-ledger')!;
    const entry = [...feed.shadowRoot!.querySelectorAll<HTMLElement>('.entry')]
      .find((e) => (key ? e.dataset.entryKey === key : e.getBoundingClientRect().top >= 0));
    if (!entry) return null;
    entry.scrollIntoView({ block: 'center' });
    const box = entry.getBoundingClientRect();
    const x = box.x + 12;
    const y = box.y + Math.min(10, box.height / 2);
    let hit: Element | null = document.elementFromPoint(x, y);
    while (hit?.shadowRoot) {
      const inner = hit.shadowRoot.elementFromPoint(x, y);
      if (!inner || inner === hit) break;
      hit = inner;
    }
    return { x, y, key: entry.dataset.entryKey!, hit: !!hit && entry.contains(hit) };
  }, key);
  await expect.poll(async () => (await locate())?.hit ?? false, { timeout: 15_000, message: 'the record can be clicked' }).toBe(true);
  const target = (await locate())!;
  await page.mouse.click(target.x, target.y);
  await settle(page);
  return target.key;
}

function paneState(page: Page): Promise<{ open: boolean; overlay: boolean; title: string; paneLeft: number; mainRight: number;
  mainInert: boolean; pageOverflow: number }> {
  return page.evaluate(() => {
    const surface = window.__deep('jf-activity-surface')!;
    const pane = surface.shadowRoot!.querySelector('jf-details-pane');
    // The feed's column (absent in a version of Activity without the details).
    const main = surface.shadowRoot!.querySelector<HTMLElement>('.main');
    const details = surface.shadowRoot!.querySelector('jf-activity-details');
    return { open: !!pane, overlay: !!pane?.hasAttribute('overlay'),
      title: details?.shadowRoot?.querySelector('[data-testid="activity-details-title"]')?.textContent?.trim() ?? '',
      paneLeft: pane?.shadowRoot?.querySelector('.surface')?.getBoundingClientRect().left ?? 0,
      mainRight: main?.getBoundingClientRect().right ?? 0, mainInert: main?.inert ?? false,
      pageOverflow: document.documentElement.scrollWidth - window.innerWidth };
  });
}

for (const theme of ['light', 'dark'] as const) {
  it(`at 1280×720 (${theme}), a record's details sit beside the feed; Tab and F6 reach them; Escape returns to the record`, async () => {
    const page = await open({ width: 1280, height: 720, theme });
    const key = await clickRecord(page);
    await expect.poll(async () => (await paneState(page)).open, { timeout: 5_000 }).toBe(true);
    const state = await paneState(page);
    expect(state.overlay, 'wide window: inline, not a sheet').toBe(false);
    expect(state.paneLeft, 'the pane is beside the feed, not over it').toBeGreaterThanOrEqual(state.mainRight - 1);
    expect(state.mainInert).toBe(false);
    expect(state.pageOverflow, 'no sideways page overflow').toBeLessThanOrEqual(0);
    expect((await page.evaluate(() => window.__focus())).key, 'an inline pane leaves the keyboard on the record').toBe(key);

    // Tab moves on from the record into the details (the resize grip is their first stop).
    let reached = false;
    for (let i = 0; i < 4 && !reached; i += 1) {
      await page.keyboard.press('Tab');
      const now = await page.evaluate(() => window.__focus());
      reached = now.inPane || now.inGrip;
    }
    expect(reached, 'Tab reaches the details').toBe(true);

    // Back on the record, F6 goes to the details region: its tab.
    await clickRecord(page, key);
    await page.keyboard.press('F6');
    await settle(page, 150);
    const f6 = await page.evaluate(() => window.__focus());
    expect(f6.inPane, 'F6 reaches the details pane').toBe(true);
    expect(f6.role).toBe('tab');

    await page.keyboard.press('Escape');
    await settle(page);
    expect((await paneState(page)).open, 'Escape closes the details').toBe(false);
    expect((await page.evaluate(() => window.__focus())).key, 'the keyboard is back on the record').toBe(key);
  }, 90_000);
}

it('the windowed list dropped the record\'s row: Escape still returns the keyboard to that record', async () => {
  const page = await open({ width: 1280, height: 720 });
  const key = await clickRecord(page);
  await expect.poll(async () => (await paneState(page)).open, { timeout: 5_000 }).toBe(true);
  // Read far down the feed: the windowed list no longer holds the selected record's row.
  await page.evaluate(() => {
    const main = window.__deep('jf-activity-surface')!.shadowRoot!.querySelector<HTMLElement>('.main')!;
    main.scrollTop = main.scrollHeight;
  });
  await settle(page, 600);
  expect(await page.evaluate((key) => !!window.__deep('jf-action-ledger')!.shadowRoot!
    .querySelector(`.entry[data-entry-key="${key}"]`), key), 'the row is gone from the windowed list').toBe(false);
  await page.evaluate(() => {
    (window.__deep('jf-activity-surface')!.shadowRoot!.querySelector('jf-details-pane') as unknown as { focusActiveTab(): void }).focusActiveTab();
  });
  await page.keyboard.press('Escape');
  await expect.poll(async () => (await page.evaluate(() => window.__focus())).key, { timeout: 5_000 }).toBe(key);
}, 90_000);

it('at 600×400 with 200% text, the details are a sheet: the window behind is inert, the sheet scrolls to its actions, and nothing overflows sideways', async () => {
  const page = await open({ width: 600, height: 400, text: '200%', rows: 8 });
  // A turn the assistant took in this window once the app is up: two reversible steps.
  const journalKey = await page.evaluate(() => window.__recordTurn());
  await clickRecord(page, journalKey);
  await expect.poll(async () => (await paneState(page)).open, { timeout: 5_000 }).toBe(true);
  const state = await paneState(page);
  expect(state.overlay, 'narrow window: a sheet').toBe(true);
  expect(state.mainInert, 'the window behind the sheet is inert').toBe(true);
  expect(state.pageOverflow, 'no sideways page overflow').toBeLessThanOrEqual(0);
  expect((await page.evaluate(() => window.__focus())).inPane, 'opening a sheet moves the keyboard into it').toBe(true);

  // Tab through the sheet to Restore to this point: it is reached, and scrolled into view inside the sheet.
  let restore = false;
  for (let i = 0; i < 25 && !restore; i += 1) {
    await page.keyboard.press('Tab');
    const now = await page.evaluate(() => window.__focus());
    expect(now.inPane, 'Tab stays within the sheet').toBe(true);
    restore = await page.evaluate(() => {
      const details = window.__deep('jf-activity-surface')!.shadowRoot!.querySelector('jf-activity-details')!;
      const button = details.shadowRoot!.querySelector('[data-testid="activity-details-restore"]');
      return !!button && button.matches(':focus-within');
    });
  }
  expect(restore, 'Tab reaches Restore to this point').toBe(true);
  await settle(page, 200);
  const shown = await page.evaluate(() => {
    const details = window.__deep('jf-activity-surface')!.shadowRoot!.querySelector('jf-activity-details')!;
    const box = details.shadowRoot!.querySelector('[data-testid="activity-details-restore"]')!.getBoundingClientRect();
    const scroller = details.getBoundingClientRect();
    return { top: box.top, bottom: box.bottom, viewTop: scroller.top, viewBottom: scroller.bottom,
      scrolls: details.scrollHeight > details.clientHeight, scrolled: details.scrollTop };
  });
  expect(shown.scrolls, 'at 200% text the sheet content is taller than the sheet').toBe(true);
  expect(shown.scrolled, 'and it scrolled to bring the action into view').toBeGreaterThan(0);
  expect(shown.bottom).toBeLessThanOrEqual(shown.viewBottom + 1);
  expect(shown.top).toBeGreaterThanOrEqual(shown.viewTop - 1);
  expect((await paneState(page)).pageOverflow).toBeLessThanOrEqual(0);

  await page.keyboard.press('Escape');
  await settle(page);
  expect((await paneState(page)).open).toBe(false);
  expect((await page.evaluate(() => window.__focus())).key, 'the keyboard is back on the record').toBe(journalKey);
}, 90_000);

it('in forced colours, the selected record and the focused record are told apart without colour', async () => {
  const page = await open({ width: 1280, height: 720, forced: true, rows: 6 });
  const key = await clickRecord(page);
  // The keyboard moves on to the oldest record (End): it is focused, not selected.
  await page.keyboard.press('End');
  await settle(page, 300);
  const other = (await page.evaluate(() => window.__focus())).key!;
  expect(other).not.toBe(key);
  const look = await page.evaluate(({ key, other }) => {
    const feed = window.__deep('jf-action-ledger')!;
    const style = (k: string) => {
      const s = getComputedStyle(feed.shadowRoot!.querySelector<HTMLElement>(`.entry[data-entry-key="${k}"]`)!);
      return { bar: parseFloat(s.borderInlineStartWidth), barStyle: s.borderInlineStartStyle, outline: s.outlineStyle,
        outlineWidth: parseFloat(s.outlineWidth), selected: feed.shadowRoot!.querySelector(`.entry[data-entry-key="${k}"]`)!.getAttribute('aria-selected') };
    };
    return { forced: matchMedia('(forced-colors: active)').matches, selected: style(key), focused: style(other) };
  }, { key, other });
  expect(look.forced).toBe(true);
  expect(look.selected.selected).toBe('true');
  expect(look.selected.bar, 'the selected record has a bar at its start').toBeGreaterThan(0);
  expect(look.selected.barStyle).toBe('solid');
  expect(look.focused.bar, 'an unselected record has none').toBe(0);
  // The ring is drawn once the keyboard's focus is shown (:focus-visible).
  await expect.poll(() => page.evaluate((other) => getComputedStyle(window.__deep('jf-action-ledger')!.shadowRoot!
    .querySelector<HTMLElement>(`.entry[data-entry-key="${other}"]`)!).outlineStyle, other),
  { timeout: 5_000, message: 'the focused record has a focus ring' }).not.toBe('none');
  expect(look.focused.outlineWidth).toBeGreaterThan(0);
  expect(look.selected.outline, 'the selected record, not focused, has no focus ring').toBe('none');
}, 90_000);
