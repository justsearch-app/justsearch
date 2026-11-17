// SPDX-License-Identifier: Apache-2.0
// Wide and maximised windows (owner decision, 15 November): "the top bar spans the window as drawn;
// below it each area's content is one centred column of at most 960 px (tab-row lines span the
// window, content on the column); Search with side pane: list+pane centred at most 1280 px, pane 400
// px growing to at most 560; extra height to lists; first-launch/empty-search blocks centred as
// drawn." Search's list+pane half is replaced by the picture author's decision of 17 November (PJ1-PJ3:
// the pane docks to the window's right edge, max(400, 30 % of the window) up to 592, the list centred
// in what it leaves; Shell.paneDock-lockdown.test.ts holds the new rule). Measured through the real frame: the real Shell, Stage, top bar and every area, in headless
// Chromium over a private Vite server with a stubbed backend (no Engine, no shared dev stack). Run with
// npx vitest run --config vitest.config.lockdown.ts src/shell-v0/chrome/Shell.wide-lockdown.test.ts
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const SEARCH = 'core.search-v3-surface';
const LIBRARY = 'core.library-surface';
const BROWSE = 'core.browse-surface';
const BRAIN = 'core.brain-surface';
const MEMORY = 'core.memory-surface';
const HEALTH = 'core.health-surface';
const LOGS = 'core.logs-surface';
const ACTIVITY = 'core.activity-surface';
const HELP = 'core.help-surface';
const PANE_KEY = 'justsearch.searchV3.pane.width.v1';
const COLUMN = 960;

const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
const params = new URLSearchParams(location.search);
const json = (body) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
const hits = params.has('none') ? 0 : 40;
const hit = (i) => ({ id: 'doc-' + i, score: 1 - i / 100, fields: { title: 'Result ' + i, filename: 'result-' + i + '.md', path: 'C:/Docs/result-' + i + '.md', content_preview: 'A passage about heating and the boiler in the cellar, number ' + i, collection: 'docs', mime: 'text/markdown', mime_base: 'text', file_kind: 'markdown', language: 'en', modified_at: '2025-06-15T10:30:00Z', size_bytes: '100' }, matchedFields: ['title'], matchSpans: [], excerptRegions: [] });
const first = params.has('first');
const root = (i) => ({ pathHash: 'root-' + i, collection: 'default', fileCount: 200, lastIndexedIsoTime: '2026-09-02T00:00:00Z', status: 'indexed', walkError: '', inFlightCount: 0, failedCount: 0, walkCompleted: true });
const roots = first ? [] : Array.from({ length: 30 }, (_, i) => root(i));
window.conversations = params.has('saved') ? [{ sessionId: 'saved', title: 'Saved conversation', createdAtMs: 1, lastActiveAtMs: 2, messageCount: 2, shapeId: 'core.rag-ask' }] : [];
// System › Activity: the action ledger as the Engine sends it on the shared shell-events stream.
const encoder = new TextEncoder();
let seq = 0;
const frame = (frameKind, payload) => encoder.encode('event: frame\\ndata: ' + JSON.stringify({ streamId: 'surface:action-ledger', frameKind, seq: ++seq,
  ts: new Date().toISOString(), payload, resumeToken: btoa('surface:action-ledger:' + seq + ':incarnation-1') }) + '\\n\\n');
const entries = Array.from({ length: 30 }, (_, i) => ({ id: 'operation:019a0000-0000-7000-8000-' + String(i).padStart(12, '0'), kind: 'operation',
  originator: 'user', transport: 'BUTTON', operationId: 'core.open-file', outcome: 'SUCCESS', occurredAt: new Date(Date.UTC(2030, 0, 1, 8) + i * 1000).toISOString() }));
window.fetch = async (input) => {
  const url = String(input?.url ?? input);
  if (url.includes('/api/shell-events/stream')) {
    return new Response(new ReadableStream({ start(controller) {
      controller.enqueue(frame('LIFECYCLE', { kind: 'connected' }));
      controller.enqueue(frame('LIFECYCLE', { kind: 'snapshot', entries }));
    } }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
  }
  if (url.includes('/api/operation-history/019a')) return json({ state: 'complete', historySince: 0, unitsCompleted: 1 });
  if (url.includes('/api/action-ledger')) return json({ entries });
  if (url.includes('/api/preview')) return json({ content: 'Some document text. '.repeat(400), totalChars: 8000 });
  if (url.includes('/api/knowledge/search')) return json({ totalHits: hits, matchCount: hits, tookMs: 4, results: Array.from({ length: hits }, (_, i) => hit(i)), nextCursor: null, facets: {}, facetsTruncated: false });
  if (url.includes('/api/indexing-roots/substrate')) return json({ items: roots, count: roots.length });
  if (url.includes('/api/indexing/roots')) return json({ roots: Array.from({ length: 60 }, (_, i) => ({ path: 'C:/Docs/folder-' + String(i).padStart(2, '0'), fileCount: 10 })) });
  if (url.includes('/api/path-hash/resolve')) return json({ path: '/notes' });
  if (url.includes('/api/chat/conversations?')) return json({ sessions: window.conversations });
  return json({ entries: [], conversations: [] });
};
window.EventSource = class { constructor() { this.readyState = 0; } addEventListener() {} removeEventListener() {} close() {} };
const { __seedForTest, getSurface } = await import('/src/api/registry/SurfaceCatalogClient.ts');
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/chrome/Shell.ts');
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL',
  presentation: { labelKey: id, descriptionKey: id }, consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('${SEARCH}', 'jf-sv3-window'),
  surface('${LIBRARY}', 'jf-library-surface', { members: ['${BROWSE}'] }),
  surface('${BROWSE}', 'jf-browse-surface', { placement: 'DEEPLINK' }),
  surface('${BRAIN}', 'jf-brain-surface', { members: ['${MEMORY}'] }),
  surface('${MEMORY}', 'jf-memory-surface', { placement: 'DEEPLINK' }),
  surface('core.system-surface', 'jf-system-surface', { members: ['${HEALTH}', '${LOGS}', '${ACTIVITY}'] }),
  surface('${HEALTH}', 'jf-health-surface', { placement: 'DEEPLINK' }),
  surface('${LOGS}', 'jf-log-surface', { placement: 'DEEPLINK' }),
  surface('${ACTIVITY}', 'jf-activity-surface', { placement: 'DEEPLINK' }),
  surface('${HELP}', 'jf-help-surface', { placement: 'DEEPLINK' }),
  surface('core.settings-surface', 'jf-settings-surface', { placement: 'MODAL' }),
] });
window.firstUse = await import('/src/shell-v0/state/firstUse.ts').catch(() => null);
window.ai = await import('/src/shell-v0/state/aiStateStore.ts');
window.inspector = await import('/src/shell-v0/state/inspectorState.ts');
const observe = () => {
  window.firstUse?.setFirstUseRoots(roots, new Map(roots.map(r => [r.pathHash, '/notes'])));
  const count = first ? 0 : 40;
  window.ai.__feedForTest({ status: { conversationProtection: { state: 'unlocked' }, worker: { core: { indexState: 'IDLE', indexedDocuments: count, searchableDocuments: count, ownSearchableDocuments: count, pendingJobs: 0 } } } });
  window.ai.__feedContactForTest();
};
document.body.append(document.createElement('jf-shell'));
observe();
setInterval(observe, 2_000);
const stage = () => document.querySelector('jf-shell').shadowRoot.querySelector('jf-stage');
window.split = async (left, right, axis) => {
  const el = stage();
  el.surface = getSurface(left);
  el.secondarySurface = getSurface(right);
  el.splitAxis = axis;
  await el.updateComplete;
};
window.single = async (id) => {
  const el = stage();
  el.surface = getSurface(id);
  el.secondarySurface = null;
  el.splitAxis = null;
  await el.updateComplete;
};
document.body.dataset.ready = 'true';
</script></body></html>`;

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let context: BrowserContext | undefined;
let output: string;
let url: string;

beforeAll(async () => {
  output = await mkdtemp(path.join(process.env.TMPDIR ?? tmpdir(), 'wide-app-'));
  server = await createServer({
    configFile: false, root: process.cwd(), cacheDir: output, publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'wide-app-harness',
      configureServer(vite) {
        vite.middlewares.use((req, res, next) => {
          if (req.url?.split('?')[0] !== '/app') return next();
          res.setHeader('Content-Type', 'text/html');
          res.end(HARNESS);
        });
      },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/app`;
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

async function settle(page: Page, ms = 200): Promise<void> {
  await page.waitForTimeout(ms);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

interface OpenOptions { query?: string; hash?: string; storage?: string | null | 'unavailable'; scheme?: 'light' | 'dark'; forced?: boolean }

async function open(width: number, height: number, options: OpenOptions = {}): Promise<Page> {
  context = await browser.newContext({ viewport: { width, height }, reducedMotion: 'reduce',
    colorScheme: options.scheme ?? 'dark', forcedColors: options.forced ? 'active' : 'none' });
  const page = await context.newPage();
  page.on('pageerror', error => console.info('pageerror', error.message));
  // The pane's remembered width as the reader left it, and every write to it from here on.
  await page.addInitScript(([key, stored]) => {
    const w = window as unknown as { paneWrites: string[] };
    w.paneWrites = [];
    if (stored === 'unavailable') {
      // The pane's memory cannot be read or written (private mode, quota); the rest of the app's storage works.
      for (const name of ['getItem', 'setItem', 'removeItem'] as const) {
        const own = Storage.prototype[name] as (this: Storage, ...args: string[]) => unknown;
        (Storage.prototype as unknown as Record<string, unknown>)[name] = function (this: Storage, ...args: string[]) {
          if (args[0] === key) throw new DOMException('denied', 'SecurityError');
          return own.apply(this, args);
        };
      }
      return;
    }
    if (stored !== null) localStorage.setItem(key, stored);
    const setItem = Storage.prototype.setItem;
    Storage.prototype.setItem = function (this: Storage, name: string, value: string) {
      if (name === key) w.paneWrites.push(value);
      setItem.call(this, name, value);
    };
  }, [PANE_KEY, options.storage ?? null] as const);
  await page.goto(`${url}${options.query ?? ''}${options.hash ?? ''}`, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await page.locator('jf-top-bar').waitFor();
  await settle(page, 400);
  return page;
}

async function area(page: Page, id: string): Promise<void> {
  await page.evaluate(target => { location.hash = `#justsearch://surface/${target}`; }, id);
  await settle(page, 600);
}

async function search(page: Page): Promise<void> {
  await page.locator('jf-sv3-composer textarea').fill('heating');
  await page.keyboard.press('Enter');
  await settle(page, 300);
}

const row = (page: Page) => page.locator('[data-testid="search-result-row"]');

async function openResult(page: Page): Promise<void> {
  await row(page).first().waitFor({ timeout: 15_000 });
  await row(page).first().click();
  await settle(page, 400);
  await page.locator('jf-sv3-pane').waitFor({ state: 'attached' });
}

interface Box { left: number; right: number; top: number; bottom: number; width: number }

/**
 * Where the area's content is: the union of every visible line of text and every text field below the
 * top bar, through open shadow roots, clipped by the boxes that clip it. The sidebar, side panes and
 * details panes are left out (they are not the page column).
 */
const contentBox = (page: Page): Promise<Box> => page.evaluate(() => {
  const skip = 'jf-top-bar, jf-sv3-sidebar, jf-sv3-pane, [data-details-pane], jf-details-pane, .side, jf-pane-picker';
  const stage = document.querySelector('jf-shell')!.shadowRoot!.querySelector('.stage')!;
  const parentOf = (n: Node): Element | null => (n.parentNode instanceof ShadowRoot ? n.parentNode.host
    : (n as Element).assignedSlot ?? n.parentElement);
  const clip = (n: Node, r: DOMRect) => {
    let box = { left: r.left, right: r.right };
    for (let el = parentOf(n); el; el = parentOf(el)) {
      const s = getComputedStyle(el);
      if (s.overflowX !== 'visible') {
        const c = el.getBoundingClientRect();
        box = { left: Math.max(box.left, c.left), right: Math.min(box.right, c.right) };
      }
    }
    return box;
  };
  const out = { left: Infinity, right: -Infinity, top: Infinity, bottom: -Infinity };
  const add = (n: Node, r: DOMRect) => {
    if (r.width <= 1 || r.height <= 1) return;
    const c = clip(n, r);
    if (c.right - c.left <= 1) return;
    out.left = Math.min(out.left, c.left); out.right = Math.max(out.right, c.right);
    out.top = Math.min(out.top, r.top); out.bottom = Math.max(out.bottom, r.bottom);
  };
  const walk = (root: ParentNode) => {
    for (const child of Array.from((root as Element).childNodes ?? [])) {
      if (child.nodeType === Node.TEXT_NODE) {
        if (!child.textContent?.trim()) continue;
        const parent = parentOf(child);
        if (parent && getComputedStyle(parent).visibility === 'hidden') continue;
        const range = document.createRange(); range.selectNodeContents(child);
        for (const r of Array.from(range.getClientRects())) add(child, r);
        continue;
      }
      if (!(child instanceof Element) || child.matches(skip) || child.localName === 'style' || child.localName === 'script') continue;
      if (child.matches('input:not([type="checkbox"]):not([type="radio"]), textarea')) add(child, child.getBoundingClientRect());
      if (child.shadowRoot) walk(child.shadowRoot);
      walk(child);
    }
  };
  walk(stage);
  return { ...out, width: out.right - out.left };
});

/** The box the stage gives the area (the whole width below the bar, less an inline details pane). */
const stageBox = (page: Page): Promise<Box> => page.evaluate(() => {
  const r = document.querySelector('jf-shell')!.shadowRoot!.querySelector('.stage')!.getBoundingClientRect();
  return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width };
});

/** A deep query from the document through open shadow roots. */
const deepRect = (page: Page, selector: string): Promise<Box | null> => page.evaluate(sel => {
  const find = (root: ParentNode): Element | null => {
    const hit = root.querySelector(sel); if (hit) return hit;
    for (const el of Array.from(root.querySelectorAll('*'))) {
      const inner = el.shadowRoot && find(el.shadowRoot); if (inner) return inner;
    }
    return null;
  };
  const el = find(document);
  if (!el) return null;
  const r = el.getBoundingClientRect();
  return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width };
}, selector);

/** The scroller a wheel at (x, y) would move: the nearest scrollable box under the point. */
const scrollerAt = (page: Page, x: number, y: number): Promise<string> => page.evaluate(([px, py]) => {
  let el: Element | null = document.elementFromPoint(px!, py!);
  while (el?.shadowRoot) {
    const inner = el.shadowRoot.elementFromPoint(px!, py!);
    if (!inner || inner === el) break;
    el = inner;
  }
  const parentOf = (n: Element): Element | null => (n.parentNode instanceof ShadowRoot ? n.parentNode.host : n.assignedSlot ?? n.parentElement);
  for (let node = el; node; node = parentOf(node)) {
    const s = getComputedStyle(node);
    if (['auto', 'scroll'].includes(s.overflowY) && node.scrollHeight > node.clientHeight) {
      return `${node.localName}.${node.getAttribute('class') ?? ''}`;
    }
  }
  return `none (${el?.localName ?? 'nothing'})`;
}, [x, y] as const);

/** Scroll by the wheel at (x, y); the distance the scroller under the centre moved. */
async function wheelMoves(page: Page, x: number, y: number, centreX: number): Promise<number> {
  const top = () => page.evaluate(([cx, py]) => {
    let el: Element | null = document.elementFromPoint(cx!, py!);
    while (el?.shadowRoot) { const inner = el.shadowRoot.elementFromPoint(cx!, py!); if (!inner || inner === el) break; el = inner; }
    const parentOf = (n: Element): Element | null => (n.parentNode instanceof ShadowRoot ? n.parentNode.host : n.assignedSlot ?? n.parentElement);
    for (let node = el; node; node = parentOf(node)) {
      const s = getComputedStyle(node);
      if (['auto', 'scroll'].includes(s.overflowY) && node.scrollHeight > node.clientHeight) return node.scrollTop;
    }
    return -1;
  }, [centreX, y] as const);
  const before = await top();
  await page.mouse.move(x, y);
  await page.mouse.wheel(0, 300);
  await settle(page, 250);
  return (await top()) - before;
}

/** The column the rule draws in a box `width` wide that starts at `left`. */
const column = (box: { left: number; width: number }, cap = COLUMN) => {
  const gutter = Math.max(0, (box.width - cap) / 2);
  return { left: box.left + gutter, right: box.left + box.width - gutter };
};

/** Content sits on the column: inside it, and starting at its start (it is centred, not left-aligned at the window edge). */
async function expectOnColumn(page: Page, label: string, align: 'start' | 'centre'): Promise<void> {
  const stage = await stageBox(page);
  const col = column(stage);
  const content = await contentBox(page);
  expect(content.left, `${label}: content left ${content.left} within column from ${col.left}`).toBeGreaterThanOrEqual(col.left - 1);
  expect(content.right, `${label}: content right ${content.right} within column to ${col.right}`).toBeLessThanOrEqual(col.right + 1);
  if (align === 'start') expect(content.left - col.left, `${label}: content starts at the column's start`).toBeLessThanOrEqual(64);
  // Centred to within a scrollbar's width: the list's scroller draws its bar on one side.
  else expect(Math.abs((content.left - col.left) - (col.right - content.right)), `${label}: content centred`).toBeLessThanOrEqual(16);
}

/** A tab row's line spans the stage; its tabs start on the column. */
async function expectTabRow(page: Page, label: string): Promise<void> {
  const stage = await stageBox(page);
  const bar = await deepRect(page, '.tab-bar');
  expect(bar, `${label}: tab row`).not.toBeNull();
  expect(Math.round(bar!.left), `${label}: tab line starts at the window edge`).toBe(Math.round(stage.left));
  expect(Math.round(bar!.right), `${label}: tab line ends at the window edge`).toBe(Math.round(stage.right));
  const tab = await deepRect(page, '[role="tab"]');
  const col = column(stage);
  expect(tab!.left, `${label}: first tab on the column`).toBeGreaterThanOrEqual(col.left);
  expect(tab!.left - col.left, `${label}: first tab at the column's start`).toBeLessThanOrEqual(64);
}

/** A wheel in either gutter moves the same scroller as one over the content. */
async function expectGuttersScroll(page: Page, label: string): Promise<void> {
  const stage = await stageBox(page);
  const y = Math.round(stage.top + stage.width * 0 + (stage.bottom - stage.top) / 2);
  const centre = Math.round(stage.left + stage.width / 2);
  const middle = await scrollerAt(page, centre, y);
  expect(middle, `${label}: a scroller under the content`).not.toMatch(/^none/);
  for (const x of [Math.round(stage.left + 12), Math.round(stage.right - 12)]) {
    expect(await scrollerAt(page, x, y), `${label}: wheel at x=${x}`).toBe(middle);
  }
  expect(await wheelMoves(page, Math.round(stage.left + 12), y, centre), `${label}: left gutter wheel scrolls`).toBeGreaterThan(0);
}

const AREAS = [
  ['Library \u203a Folders', LIBRARY, 'start', true],
  ['Library \u203a Browse', BROWSE, 'start', true],
  ['AI Brain', BRAIN, 'start', false],
  ['AI Brain \u203a Memory', MEMORY, 'start', true],
  ['System \u203a Health', HEALTH, 'start', true],
  ['System \u203a Logs', LOGS, 'start', true],
  ['System \u203a Activity', ACTIVITY, 'start', true],
  ['Help', HELP, 'start', false],
] as const;

describe('SW-1 maximised (1920\u00d71080): one centred column per area, tab lines span', () => {
  it('the top bar spans the window', async () => {
    const page = await open(1920, 1080);
    const bar = await page.locator('jf-top-bar').boundingBox();
    expect(Math.round(bar!.x)).toBe(0);
    expect(Math.round(bar!.width)).toBe(1920);
  }, 60_000);

  it.each(AREAS)('%s: content on the centred column of at most 960 px', async (label, id, align, tabs) => {
    const page = await open(1920, 1080);
    await area(page, id);
    await expectOnColumn(page, label, align);
    if (tabs) await expectTabRow(page, label);
  }, 60_000);

  it('Search results with the pane closed: the list is on the centred column', async () => {
    const page = await open(1920, 1080);
    await search(page);
    await row(page).first().waitFor({ timeout: 15_000 });
    await expectOnColumn(page, 'Search results', 'centre');
  }, 60_000);

  it('Library with a document open: the column is centred in what the details pane leaves', async () => {
    const page = await open(1920, 1080);
    await area(page, LIBRARY);
    await page.locator('jf-library-surface').evaluate(() => undefined);
    await page.evaluate(() => {
      const deep = (root: ParentNode): HTMLElement | null => {
        for (const el of Array.from(root.querySelectorAll<HTMLElement>('*'))) {
          if (el.localName === 'jf-library-surface') return el;
          const inner = el.shadowRoot && deep(el.shadowRoot); if (inner) return inner;
        }
        return null;
      };
      const lib = deep(document)!;
      const inside = (root: ParentNode): HTMLElement | null => {
        for (const el of Array.from(root.querySelectorAll<HTMLElement>('*'))) {
          if (el.matches('button, [tabindex="0"]') && el.getClientRects().length > 0) return el;
          const inner = el.shadowRoot && inside(el.shadowRoot); if (inner) return inner;
        }
        return null;
      };
      inside(lib.shadowRoot ?? lib)!.focus();
      (window as unknown as { inspector: { setSelected(item: unknown): void } }).inspector
        .setSelected({ id: 'report.md', title: 'report.md', path: 'C:/Docs/report.md' });
    });
    await settle(page, 400);
    const pane = await page.locator('[data-testid="details-pane"]').boundingBox();
    expect(Math.round(pane!.x + pane!.width), 'the details pane stays at the window edge').toBe(1920);
    const room = { left: 0, width: Math.round(pane!.x) };
    const col = column(room);
    const content = await contentBox(page);
    expect(content.left).toBeGreaterThanOrEqual(col.left - 1);
    expect(content.right).toBeLessThanOrEqual(col.right + 1);
    expect(content.left - col.left).toBeLessThanOrEqual(64);
  }, 60_000);
});

describe('SW-1 Activity with a record open', () => {
  it('System \u203a Activity: the side pane stays at the window edge; the feed is on the column centred in what it leaves', async () => {
    const page = await open(1920, 1080);
    await area(page, ACTIVITY);
    const point = async () => page.evaluate(() => {
      const find = (root: ParentNode): Element | null => {
        for (const el of Array.from(root.querySelectorAll('*'))) {
          if (el.localName === 'jf-action-ledger') return el;
          const inner = el.shadowRoot && find(el.shadowRoot); if (inner) return inner;
        }
        return null;
      };
      const entry = find(document)?.shadowRoot?.querySelector<HTMLElement>('.entry');
      if (!entry) return null;
      const box = entry.getBoundingClientRect();
      return { x: box.x + 12, y: box.y + Math.min(10, box.height / 2) };
    });
    await expect.poll(point, { timeout: 15_000 }).not.toBeNull();
    const target = (await point())!;
    await page.mouse.click(target.x, target.y);
    await settle(page, 400);
    const side = await page.evaluate(() => {
      const find = (root: ParentNode): Element | null => {
        for (const el of Array.from(root.querySelectorAll('*'))) {
          if (el.localName === 'jf-activity-surface') return el;
          const inner = el.shadowRoot && find(el.shadowRoot); if (inner) return inner;
        }
        return null;
      };
      const r = find(document)?.shadowRoot?.querySelector('.side')?.getBoundingClientRect();
      return r ? { left: r.left, right: r.right } : null;
    });
    expect(side, 'the details sit beside the feed').not.toBeNull();
    expect(Math.round(side!.right)).toBe(1920);
    const col = column({ left: 0, width: side!.left });
    const content = await contentBox(page);
    expect(content.left).toBeGreaterThanOrEqual(col.left - 1);
    expect(content.right).toBeLessThanOrEqual(col.right + 1);
    expect(content.left - col.left).toBeLessThanOrEqual(64);
  }, 60_000);
});

describe('SW-2 Search with the pane open at 1920: the pane at the window edge, the list centred in what it leaves', () => {
  it.each([
    ['no sidebar', '', null, 0],
    ['an expanded sidebar', '?saved', '0', 256],
    ['a collapsed sidebar', '?saved', '1', 48],
  ] as const)('with %s', async (_label, query, collapsed, sidebar) => {
    context = await browser.newContext({ viewport: { width: 1920, height: 1080 }, reducedMotion: 'reduce' });
    await context.addInitScript(value => { if (value !== null) localStorage.setItem('justsearch.searchV3.sidebar.collapsed.v1', value); }, collapsed);
    const page = await context.newPage();
    await page.goto(`${url}${query}`, { timeout: 45_000 });
    await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
    if (sidebar) await page.locator('jf-sv3-sidebar').waitFor({ timeout: 15_000 });
    await settle(page, 400);
    await search(page);
    await openResult(page);
    const pane = (await page.locator('jf-sv3-pane').boundingBox())!;
    expect(Math.round(pane.width)).toBe(576);
    expect(Math.abs(pane.x + pane.width - 1920), 'pane ends at the window edge').toBeLessThanOrEqual(1);
    const col = column({ left: sidebar, width: pane.x - sidebar });
    const content = await contentBox(page);
    expect(content.left, 'list starts on the column').toBeGreaterThanOrEqual(col.left - 1);
    expect(content.right, 'list ends before the pane').toBeLessThanOrEqual(pane.x + 1);
    expect(pane.x - sidebar, 'the main column keeps its 640').toBeGreaterThanOrEqual(640);
  }, 60_000);

  it('a wheel in either gutter moves the list', async () => {
    const page = await open(1920, 1080);
    await search(page);
    await openResult(page);
    const pane = (await page.locator('jf-sv3-pane').boundingBox())!;
    expect(await wheelMoves(page, 100, 600, 700)).toBeGreaterThan(0);
    expect(await wheelMoves(page, Math.round(pane.x - 40), 600, 700)).toBeGreaterThan(0);
  }, 60_000);

  it('live resize: the automatic pane and the gutter follow the window', async () => {
    const page = await open(1920, 1080);
    await search(page);
    await openResult(page);
    await page.setViewportSize({ width: 1100, height: 900 });
    await settle(page, 400);
    let pane = (await page.locator('jf-sv3-pane').boundingBox())!;
    expect(Math.round(pane.width)).toBe(400);
    expect(Math.round(pane.x + pane.width)).toBe(1100);
    await page.setViewportSize({ width: 1920, height: 1080 });
    await settle(page, 400);
    pane = (await page.locator('jf-sv3-pane').boundingBox())!;
    expect(Math.round(pane.width)).toBe(576);
    expect(Math.round(pane.x + pane.width)).toBe(1920);
  }, 60_000);
});

describe('SW-3 extra height goes to lists; first-launch and empty-search blocks centred', () => {
  it.each([
    ['Search results', SEARCH],
    ['Library \u203a Folders', LIBRARY],
    ['Library \u203a Browse', BROWSE],
  ] as const)('%s: the list reaches the window bottom and its last row is reachable', async (label, id) => {
    const page = await open(1920, 1080);
    if (id === SEARCH) { await search(page); await row(page).first().waitFor({ timeout: 15_000 }); }
    else await area(page, id);
    const stage = await stageBox(page);
    const y = Math.round(stage.top + (stage.bottom - stage.top) / 2);
    const scroller = await page.evaluate(([cx, py]) => {
      let el: Element | null = document.elementFromPoint(cx!, py!);
      while (el?.shadowRoot) { const inner = el.shadowRoot.elementFromPoint(cx!, py!); if (!inner || inner === el) break; el = inner; }
      const parentOf = (n: Element): Element | null => (n.parentNode instanceof ShadowRoot ? n.parentNode.host : n.assignedSlot ?? n.parentElement);
      for (let node = el; node; node = parentOf(node)) {
        const s = getComputedStyle(node);
        if (['auto', 'scroll'].includes(s.overflowY) && node.scrollHeight > node.clientHeight) {
          node.scrollTop = node.scrollHeight;
          const r = node.getBoundingClientRect();
          return { bottom: r.bottom, scrolled: node.scrollTop > 0, end: node.scrollTop + node.clientHeight >= node.scrollHeight - 1 };
        }
      }
      return null;
    }, [Math.round(stage.left + stage.width / 2), y] as const);
    expect(scroller, `${label}: the list scrolls`).not.toBeNull();
    expect(scroller!.bottom, `${label}: the list takes the height`).toBeGreaterThanOrEqual(1080 - 2);
    expect(scroller!.end, `${label}: scrolled to its end`).toBe(true);
  }, 60_000);

  it.each([
    ['first launch', '?first', false],
    ['an empty Search', '', false],
    ['zero matches', '?none', true],
  ] as const)('%s: the block is centred in the window', async (_label, query, searched) => {
    const page = await open(1920, 1080, { query });
    if (searched) await search(page);
    const content = await contentBox(page);
    expect(Math.abs((content.left + content.right) / 2 - 960)).toBeLessThanOrEqual(8);
    expect(content.width).toBeLessThanOrEqual(COLUMN);
  }, 60_000);
});

describe('SW-4 at or below 960 nothing changes; the 980 switch is unchanged', () => {
  /** Inline paddings the areas had before the rule, for a window at or below the column. */
  const PADDINGS = [
    [LIBRARY, '.folders-scroll', { 960: 40, 800: 20 }],
    [BROWSE, '.body', { 960: 40, 800: 40 }],
    [HEALTH, '.body', { 960: 24, 800: 24 }],
    [LOGS, '.body', { 960: 16, 800: 16 }],
    [LIBRARY, '.tab-bar', { 960: 0, 800: 0 }],
  ] as const;
  it.each([960, 800] as const)('at %i px each area keeps its own padding and no gutter', async width => {
    const page = await open(width, 800);
    for (const [id, selector, widths] of PADDINGS) {
      const px = widths[width];
      await area(page, id);
      const padding = await page.evaluate(sel => {
        const find = (root: ParentNode): Element | null => {
          for (const el of Array.from(root.querySelectorAll('*'))) {
            if (el.matches(sel) && el.getClientRects().length > 0) return el;
            const inner = el.shadowRoot && find(el.shadowRoot); if (inner) return inner;
          }
          return null;
        };
        const el = find(document);
        return el ? [getComputedStyle(el).paddingLeft, getComputedStyle(el).paddingRight] : null;
      }, selector);
      expect(padding, `${id} ${selector}`).toEqual([`${px}px`, `${px}px`]);
    }
  }, 60_000);

  it.each([[979, true], [980, false]] as const)('at %i px the pane is an overlay: %s', async (width, overlay) => {
    const page = await open(width, 800);
    await search(page);
    await openResult(page);
    expect(await page.locator('jf-sv3-window').evaluate(el => el.hasAttribute('pane-overlay'))).toBe(overlay);
  }, 60_000);

  it.each([[1040, 400], [1100, 400]] as const)('at %i px with no sidebar the automatic pane is %i px wide, at the window edge', async (width, px) => {
    const page = await open(width, 800);
    await search(page);
    await openResult(page);
    const pane = (await page.locator('jf-sv3-pane').boundingBox())!;
    expect(Math.round(pane.width)).toBe(px);
    expect(Math.round(pane.x + pane.width)).toBe(width);
  }, 60_000);

  it('at 1280 px every area is on the centred column', async () => {
    const page = await open(1280, 800);
    for (const [label, id, align] of AREAS) {
      await area(page, id);
      await expectOnColumn(page, `${label} at 1280`, align);
    }
  }, 120_000);
});

describe('SW-5 the remembered pane width: stored value against effective width, no writes on reflow', () => {
  it.each([
    ['absent', null, 576],
    ['360', '360', 360],
    ['450', '450', 450],
    ['600', '600', 592],
    ['invalid', 'wide', 576],
    ['storage unavailable', 'unavailable', 576],
  ] as const)('stored %s: the pane opens at its effective width and reflow writes nothing', async (_label, stored, px) => {
    const page = await open(1920, 1080, { storage: stored });
    await search(page);
    await openResult(page);
    expect(Math.round((await page.locator('jf-sv3-pane').boundingBox())!.width)).toBe(px);
    await page.setViewportSize({ width: 1300, height: 900 });
    await settle(page, 300);
    await page.setViewportSize({ width: 1920, height: 1080 });
    await settle(page, 300);
    expect(Math.round((await page.locator('jf-sv3-pane').boundingBox())!.width)).toBe(px);
    if (stored !== 'unavailable') {
      expect(await page.evaluate(key => localStorage.getItem(key), PANE_KEY)).toBe(stored);
      expect(await page.evaluate(() => (window as unknown as { paneWrites: string[] }).paneWrites)).toEqual([]);
    }
  }, 60_000);

  it('dragging clamps to 392-592 outer and remembers; reset returns to automatic and forgets', async () => {
    const page = await open(1920, 1080);
    await search(page);
    await openResult(page);
    const grip = page.locator('[data-testid="sv3-pane-grip"]');
    const box = (await grip.boundingBox())!;
    const y = box.y + box.height / 2;
    await page.mouse.move(box.x + box.width / 2, y);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width / 2 + 600, y, { steps: 5 });
    await page.mouse.up();
    await settle(page, 300);
    expect(Math.round((await page.locator('jf-sv3-pane').boundingBox())!.width)).toBe(392);
    expect(await page.evaluate(key => localStorage.getItem(key), PANE_KEY)).toBe('392');
    await grip.dblclick();
    await settle(page, 300);
    expect(Math.round((await page.locator('jf-sv3-pane').boundingBox())!.width)).toBe(576);
    expect(await page.evaluate(key => localStorage.getItem(key), PANE_KEY)).toBeNull();
  }, 60_000);
});

describe('SW-7 scrolling and reach', () => {
  it.each([
    ['Search results', SEARCH],
    ['Library \u203a Folders', LIBRARY],
    ['Library \u203a Browse', BROWSE],
  ] as const)('%s at 1920: a wheel in either gutter moves the same scroller as one over the content', async (label, id) => {
    const page = await open(1920, 1080);
    if (id === SEARCH) { await search(page); await row(page).first().waitFor({ timeout: 15_000 }); }
    else await area(page, id);
    await expectGuttersScroll(page, label);
  }, 60_000);

  it('a short maximised window (1920\u00d7500): the last result is reachable by keyboard focus', async () => {
    const page = await open(1920, 500);
    await search(page);
    await row(page).first().waitFor({ timeout: 15_000 });
    await row(page).last().focus();
    await settle(page);
    const last = (await row(page).last().boundingBox())!;
    expect(last.y + last.height).toBeLessThanOrEqual(500);
    expect(last.y).toBeGreaterThan(0);
  }, 60_000);
});

describe('SW-8 themes, text size and the split stage', () => {
  it.each([['light', false], ['dark', false], ['dark', true]] as const)('Library at 1920 in %s (forced colours %s) is on the column', async (scheme, forced) => {
    const page = await open(1920, 1080, { scheme, forced });
    await area(page, LIBRARY);
    await expectOnColumn(page, `Library ${scheme}`, 'start');
    await expectTabRow(page, `Library ${scheme}`);
  }, 60_000);

  it('200% text at 1920: Search results and Library stay on the column', async () => {
    const page = await open(1920, 1080);
    await page.evaluate(() => { document.documentElement.style.fontSize = '200%'; });
    await settle(page);
    await search(page);
    await row(page).first().waitFor({ timeout: 15_000 });
    await expectOnColumn(page, 'Search 200%', 'centre');
    await area(page, LIBRARY);
    await expectOnColumn(page, 'Library 200%', 'start');
  }, 60_000);

  it.each(['horizontal', 'vertical'] as const)('a %s split at 1920 is unchanged', async axis => {
    const page = await open(1920, 1080);
    await area(page, LIBRARY);
    await page.evaluate(([l, r, a]) => (window as unknown as { split(l: string, r: string, a: string): Promise<void> }).split(l!, r!, a!), [LIBRARY, BRAIN, axis] as const);
    await settle(page, 600);
    const inSplit = await page.evaluate(() => {
      const find = (root: ParentNode, sel: string): Element | null => {
        for (const el of Array.from(root.querySelectorAll('*'))) {
          if (el.matches(sel)) return el;
          const inner = el.shadowRoot && find(el.shadowRoot, sel); if (inner) return inner;
        }
        return null;
      };
      const pad = (sel: string) => { const el = find(document, sel); return el ? getComputedStyle(el).paddingLeft : null; };
      const body = find(document, 'jf-brain-surface')?.shadowRoot?.querySelector('.body');
      return { folders: pad('.folders-scroll'), tabs: pad('.tab-bar'), brain: body ? Math.round(body.getBoundingClientRect().width) : null };
    });
    expect(inSplit.folders).toBe('40px');
    expect(inSplit.tabs).toBe('0px');
    expect(inSplit.brain).toBe(960);
  }, 60_000);

  it('from a split back to the single stage, the area returns to the centred column', async () => {
    const page = await open(1920, 1080);
    await area(page, LIBRARY);
    await page.evaluate(([l, r]) => (window as unknown as { split(l: string, r: string, a: string): Promise<void> }).split(l!, r!, 'vertical'), [LIBRARY, BRAIN] as const);
    await settle(page, 600);
    await page.evaluate(id => (window as unknown as { single(id: string): Promise<void> }).single(id), LIBRARY);
    await settle(page, 600);
    await expectOnColumn(page, 'Library after the split', 'start');
    await expectTabRow(page, 'Library after the split');
  }, 60_000);
});
