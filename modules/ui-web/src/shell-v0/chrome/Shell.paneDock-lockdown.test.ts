// SPDX-License-Identifier: Apache-2.0
// Search's side pane docks to the window's right edge (the picture author's decision of 17 November,
// delegated by the owner: "The pane docks to the window's right edge. Pane width: 400 px at the
// default window, then 30 % of the window width, up to 592 px (560 of content plus 16 padding each
// side). Title, close button, lines and foot actions all span that width, so nothing stops mid-air.
// The 1280 cap: dropped. The list keeps the maximum width it already has for plain results and is
// centred in the space left of the pane. At 1024×700: nothing changes"), as the orchestrator's PJ1-PJ4
// read it. Measured through the real Shell, Stage and Search window in headless Chromium over a
// private Vite server with a stubbed backend (no Engine, no shared dev stack). Run with
// npx vitest run --config vitest.config.lockdown.ts src/shell-v0/chrome/Shell.paneDock-lockdown.test.ts
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const SEARCH = 'core.search-v3-surface';
const LIBRARY = 'core.library-surface';
const COLUMN = 960;
const PANE_KEY = 'justsearch.searchV3.pane.width.v1';
const COLLAPSED_KEY = 'justsearch.searchV3.sidebar.collapsed.v1';

const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
const params = new URLSearchParams(location.search);
const json = (body) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
const hit = (i) => ({ id: 'doc-' + i, score: 1 - i / 100, fields: { title: 'Result ' + i, filename: 'result-' + i + '.md', path: 'C:/Docs/result-' + i + '.md', content_preview: 'A passage about heating and the boiler in the cellar, number ' + i, collection: 'docs', mime: 'text/markdown', mime_base: 'text', file_kind: 'markdown', language: 'en', modified_at: '2025-06-15T10:30:00Z', size_bytes: '100' }, matchedFields: ['title'], matchSpans: [], excerptRegions: [] });
const root = (i) => ({ pathHash: 'root-' + i, collection: 'default', fileCount: 200, lastIndexedIsoTime: '2026-09-02T00:00:00Z', status: 'indexed', walkError: '', inFlightCount: 0, failedCount: 0, walkCompleted: true });
const roots = Array.from({ length: 3 }, (_, i) => root(i));
const conversations = params.has('saved') ? [{ sessionId: 'saved', title: 'Saved conversation', createdAtMs: 1, lastActiveAtMs: 2, messageCount: 2, shapeId: 'core.rag-ask' }] : [];
window.fetch = async (input) => {
  const url = String(input?.url ?? input);
  if (url.includes('/api/preview')) return json({ content: 'Some document text about the boiler in the cellar. '.repeat(400), totalChars: 20000 });
  if (url.includes('/api/knowledge/search')) return json({ totalHits: 40, matchCount: 40, tookMs: 4, results: Array.from({ length: 40 }, (_, i) => hit(i)), nextCursor: null, facets: {}, facetsTruncated: false });
  if (url.includes('/api/indexing-roots/substrate')) return json({ items: roots, count: roots.length });
  if (url.includes('/api/path-hash/resolve')) return json({ path: '/notes' });
  if (url.includes('/api/chat/conversations?')) return json({ sessions: conversations });
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
  surface('${LIBRARY}', 'jf-library-surface'),
  surface('core.settings-surface', 'jf-settings-surface', { placement: 'MODAL' }),
] });
const firstUse = await import('/src/shell-v0/state/firstUse.ts').catch(() => null);
const ai = await import('/src/shell-v0/state/aiStateStore.ts');
const observe = () => {
  firstUse?.setFirstUseRoots(roots, new Map(roots.map(r => [r.pathHash, '/notes'])));
  ai.__feedForTest({ status: { conversationProtection: { state: 'unlocked' }, worker: { core: { indexState: 'IDLE', indexedDocuments: 40, searchableDocuments: 40, ownSearchableDocuments: 40, pendingJobs: 0 } } } });
  ai.__feedContactForTest();
};
document.body.append(document.createElement('jf-shell'));
observe();
setInterval(observe, 2_000);
window.split = async (left, right, axis) => {
  const el = document.querySelector('jf-shell').shadowRoot.querySelector('jf-stage');
  el.surface = getSurface(left);
  el.secondarySurface = getSurface(right);
  el.splitAxis = axis;
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
  output = await mkdtemp(path.join(process.env.TMPDIR ?? tmpdir(), 'pane-dock-'));
  server = await createServer({
    configFile: false, root: process.cwd(), cacheDir: output, publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'pane-dock-harness',
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

type Sidebar = 'none' | 'expanded' | 'collapsed';
interface OpenOptions { scheme?: 'light' | 'dark'; sidebar?: Sidebar; stored?: string }

/** Search at `width`×`height` with a result open in the pane. */
async function openPane(width: number, height: number, options: OpenOptions = {}): Promise<Page> {
  const sidebar = options.sidebar ?? 'none';
  context = await browser.newContext({ viewport: { width, height }, reducedMotion: 'reduce', colorScheme: options.scheme ?? 'dark' });
  await context.addInitScript(([collapsedKey, collapsed, paneKey, stored]) => {
    if (collapsed !== null) localStorage.setItem(collapsedKey!, collapsed);
    if (stored !== null) localStorage.setItem(paneKey!, stored!);
  }, [COLLAPSED_KEY, sidebar === 'none' ? null : sidebar === 'collapsed' ? '1' : '0', PANE_KEY, options.stored ?? null] as const);
  const page = await context.newPage();
  page.on('pageerror', error => console.info('pageerror', error.message));
  await page.goto(`${url}${sidebar === 'none' ? '' : '?saved'}`, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  if (sidebar !== 'none') await page.locator('jf-sv3-sidebar').waitFor({ timeout: 15_000 });
  await settle(page, 400);
  await page.locator('jf-sv3-composer textarea').fill('heating');
  await page.keyboard.press('Enter');
  await settle(page, 300);
  const row = page.locator('[data-testid="search-result-row"]');
  await row.first().waitFor({ timeout: 15_000 });
  await row.first().click();
  await settle(page, 600);
  await page.locator('jf-sv3-pane').waitFor({ state: 'attached' });
  return page;
}

interface Box { left: number; right: number; top: number; bottom: number; width: number }

/** Every box the pane's frame draws, through open shadow roots, and the lines it draws. */
const paneParts = (page: Page) => page.evaluate(() => {
  const find = (root: ParentNode, sel: string): Element | null => {
    const hit = root.querySelector(sel); if (hit) return hit;
    for (const el of Array.from(root.querySelectorAll('*'))) {
      const inner = el.shadowRoot && find(el.shadowRoot, sel); if (inner) return inner;
    }
    return null;
  };
  const box = (el: Element | null) => {
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width };
  };
  const pane = find(document, 'jf-sv3-pane')!;
  const frame = find(pane.shadowRoot!, 'jf-details-pane')!.shadowRoot!;
  const surface = frame.querySelector('.surface')!;
  const bar = frame.querySelector('.bar')!;
  const actions = frame.querySelector('.actions')!;
  const s = getComputedStyle(surface);
  return {
    pane: box(pane)!, surface: box(surface)!, bar: box(bar)!, barLine: getComputedStyle(bar).borderBottomWidth,
    close: box(frame.querySelector('[data-testid="details-pane-close"]'))!,
    title: box(frame.querySelector('[role="tab"]'))!,
    actions: actions.hasAttribute('hidden') ? null : box(actions), actionsLine: getComputedStyle(actions).borderTopWidth,
    background: s.backgroundColor, edgeLine: s.borderLeftWidth,
    grip: box(find(document, '[data-testid="sv3-pane-grip"]')),
  };
});

/** The union of the visible lines of text in the list (the main column), clipped by what clips them. */
const listBox = (page: Page): Promise<Box> => page.evaluate(() => {
  const findAll = (root: ParentNode, sel: string, out: Element[] = []): Element[] => {
    out.push(...Array.from(root.querySelectorAll(sel)));
    for (const el of Array.from(root.querySelectorAll('*'))) if (el.shadowRoot) findAll(el.shadowRoot, sel, out);
    return out;
  };
  const parentOf = (n: Node): Element | null => (n.parentNode instanceof ShadowRoot ? n.parentNode.host : (n as Element).assignedSlot ?? n.parentElement);
  const out = { left: Infinity, right: -Infinity, top: Infinity, bottom: -Infinity };
  const main = findAll(document, 'jf-sv3-main')[0]!;
  const walk = (root: ParentNode) => {
    for (const child of Array.from((root as Element).childNodes ?? [])) {
      if (child.nodeType === Node.TEXT_NODE) {
        if (!child.textContent?.trim()) continue;
        const range = document.createRange(); range.selectNodeContents(child);
        for (const r of Array.from(range.getClientRects())) {
          if (r.width <= 1 || r.height <= 1) continue;
          let left = r.left; let right = r.right;
          for (let el = parentOf(child); el; el = parentOf(el)) {
            if (getComputedStyle(el).overflowX !== 'visible') { const c = el.getBoundingClientRect(); left = Math.max(left, c.left); right = Math.min(right, c.right); }
          }
          if (right - left <= 1) continue;
          out.left = Math.min(out.left, left); out.right = Math.max(out.right, right);
          out.top = Math.min(out.top, r.top); out.bottom = Math.max(out.bottom, r.bottom);
        }
        continue;
      }
      if (!(child instanceof Element) || child.localName === 'style' || child.localName === 'script') continue;
      if (child.shadowRoot) walk(child.shadowRoot);
      walk(child);
    }
  };
  walk(main.shadowRoot!);
  return { ...out, width: out.right - out.left };
});

/** The box of the main column the list shares with nothing else: the Search window's main column. */
const mainColumn = (page: Page): Promise<Box> => page.evaluate(() => {
  const find = (root: ParentNode, sel: string): Element | null => {
    const hit = root.querySelector(sel); if (hit) return hit;
    for (const el of Array.from(root.querySelectorAll('*'))) {
      const inner = el.shadowRoot && find(el.shadowRoot, sel); if (inner) return inner;
    }
    return null;
  };
  const r = find(document, 'jf-sv3-main')!.getBoundingClientRect();
  return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width };
});

/** The list's column: the main column's scroller less its inline padding (scrollbar excluded). */
const listColumn = (page: Page): Promise<{ left: number; right: number }> => page.evaluate(() => {
  const find = (root: ParentNode, sel: string): Element | null => {
    const hit = root.querySelector(sel); if (hit) return hit;
    for (const el of Array.from(root.querySelectorAll('*'))) {
      const inner = el.shadowRoot && find(el.shadowRoot, sel); if (inner) return inner;
    }
    return null;
  };
  const scroller = find(document, 'jf-sv3-main')!.shadowRoot!.querySelector('.scroller')!;
  const r = scroller.getBoundingClientRect();
  const s = getComputedStyle(scroller);
  return { left: r.left + parseFloat(s.paddingLeft), right: r.left + scroller.clientWidth - parseFloat(s.paddingRight) };
});

/** The scroller under (x, y) and how far a wheel there moves the scroller under (cx, y). */
async function wheelMoves(page: Page, x: number, y: number, cx: number): Promise<number> {
  const top = () => page.evaluate(([px, py]) => {
    let el: Element | null = document.elementFromPoint(px!, py!);
    while (el?.shadowRoot) { const inner = el.shadowRoot.elementFromPoint(px!, py!); if (!inner || inner === el) break; el = inner; }
    const parentOf = (n: Element): Element | null => (n.parentNode instanceof ShadowRoot ? n.parentNode.host : n.assignedSlot ?? n.parentElement);
    for (let node = el; node; node = parentOf(node)) {
      const s = getComputedStyle(node);
      if (['auto', 'scroll'].includes(s.overflowY) && node.scrollHeight > node.clientHeight) return node.scrollTop;
    }
    return -1;
  }, [cx, y] as const);
  const before = await top();
  await page.mouse.move(x, y);
  await page.mouse.wheel(0, 300);
  await settle(page, 250);
  return (await top()) - before;
}

/** Whether the point (x, y) is painted by the pane (the topmost element there is inside it). */
const inPane = (page: Page, x: number, y: number): Promise<boolean> => page.evaluate(([px, py]) => {
  let el: Element | null = document.elementFromPoint(px!, py!);
  while (el?.shadowRoot) { const inner = el.shadowRoot.elementFromPoint(px!, py!); if (!inner || inner === el) break; el = inner; }
  const parentOf = (n: Element): Element | null => (n.parentNode instanceof ShadowRoot ? n.parentNode.host : n.assignedSlot ?? n.parentElement);
  for (let node = el; node; node = parentOf(node)) if (node.localName === 'jf-sv3-pane') return true;
  return false;
}, [x, y] as const);

const SIDEBARS = [['no sidebar', 'none', 0], ['an expanded sidebar', 'expanded', 256], ['a collapsed sidebar', 'collapsed', 48]] as const;

describe('PJ1 the pane docks to the window\u2019s right edge; its surface and lines span its width', () => {
  it.each(SIDEBARS.flatMap(([label, sidebar, px]) => (['dark', 'light'] as const).map(scheme => [label, sidebar, px, scheme] as const)))(
    'at 1920 with %s (%s/%s px) in %s', async (_label, sidebar, _px, scheme) => {
      const page = await openPane(1920, 1080, { sidebar, scheme });
      const p = await paneParts(page);
      expect(Math.round(p.pane.right), 'the pane ends at the window edge').toBe(1920);
      expect(Math.round(p.surface.right), 'its surface ends at the window edge').toBe(1920);
      expect(Math.round(p.surface.left), 'its surface starts where the pane starts').toBe(Math.round(p.pane.left));
      expect(p.background, 'the surface is painted').not.toMatch(/rgba\(0, 0, 0, 0\)|transparent/);
      expect(p.edgeLine, 'its start edge draws a line').not.toBe('0px');
      // The title row and its line span the pane; the close button sits at its end, not mid-air.
      expect(p.bar.left - p.pane.left, 'the title row starts inside the start edge\u2019s hairline').toBeLessThanOrEqual(1);
      expect(Math.round(p.bar.right), 'the title row\u2019s line runs to the window edge').toBe(1920);
      expect(p.barLine).not.toBe('0px');
      expect(1920 - p.close.right, 'the close button at the row\u2019s end').toBeLessThanOrEqual(16);
      expect(p.title.left - p.pane.left, 'the title at the row\u2019s start').toBeLessThanOrEqual(16);
      expect(p.actions, 'the foot actions are shown').not.toBeNull();
      expect(p.actions!.left - p.pane.left).toBeLessThanOrEqual(1);
      expect(Math.round(p.actions!.right), 'the foot actions\u2019 line runs to the window edge').toBe(1920);
      expect(p.actionsLine).not.toBe('0px');
      expect(await inPane(page, 1919, Math.round((p.pane.top + p.pane.bottom) / 2)), 'the last pixel column is the pane').toBe(true);
      expect(await inPane(page, 1919, Math.round(p.bar.top + 4)), 'the title row reaches the last pixel column').toBe(true);
      // The grip straddles the pane's start edge.
      expect(Math.abs((p.grip!.left + p.grip!.right) / 2 - p.pane.left)).toBeLessThanOrEqual(1);
    }, 60_000);
});

describe('PJ2 the automatic pane: max(400, 30 % of the window), at most 592', () => {
  it.each([[1100, 400], [1400, 420], [1600, 480], [1920, 576], [2400, 592]] as const)(
    'at %i px wide the automatic pane is %i px, at the window edge', async (width, px) => {
      const page = await openPane(width, 900);
      const p = await paneParts(page);
      expect(Math.round(p.pane.width)).toBe(px);
      expect(Math.round(p.pane.right)).toBe(width);
    }, 60_000);

  /**
   * PJ2'': the automatic pane is at most 592 px outer and its content column is that width less the
   * pane's own padding (14 px each side) and its 1 px start border, unchanged from the pane's styling.
   * This measures boxes, not where wrapped text ends, so it holds whatever fonts the machine has.
   */
  it('at 2400 px the pane is 592 outer and its content box is 592 less its padding and border, inset by the padding', async () => {
    const OUTER = 592, PADDING = 14, BORDER = 1;
    const page = await openPane(2400, 900);
    const p = await paneParts(page);
    const content = await page.evaluate(() => {
      const find = (root: ParentNode, sel: string): Element | null => {
        const hit = root.querySelector(sel); if (hit) return hit;
        for (const el of Array.from(root.querySelectorAll('*'))) { const inner = el.shadowRoot && find(el.shadowRoot, sel); if (inner) return inner; }
        return null;
      };
      const doc = find(document, '[data-testid="sv3-pane-document"]')!;
      const surface = find(find(document, 'jf-sv3-pane')!.shadowRoot!, 'jf-details-pane')!.shadowRoot!.querySelector('.surface')!;
      const region = find(doc.shadowRoot ?? doc, '.scroll-region')!;
      const blocks = region.querySelector('.blocks')!.getBoundingClientRect();
      const r = getComputedStyle(region), f = getComputedStyle(surface);
      const px = (v: string) => parseFloat(v) || 0;
      return {
        left: blocks.left, right: blocks.right, width: blocks.width,
        padding: px(r.paddingLeft) + px(r.paddingRight) + px(r.borderLeftWidth) + px(r.borderRightWidth),
        border: px(f.borderLeftWidth) + px(f.borderRightWidth) + px(f.paddingLeft) + px(f.paddingRight),
      };
    });
    expect(Math.round(p.pane.width), 'the pane is 592 outer').toBe(OUTER);
    expect(Math.round(p.pane.right), 'at the window edge').toBe(2400);
    expect(Math.round(content.width), 'content box = outer less computed padding and border')
      .toBe(Math.round(p.pane.width - content.padding - content.border));
    expect(Math.round(content.width), 'content box = 592 - 2 * 14 - 1').toBe(OUTER - 2 * PADDING - BORDER);
    expect(Math.round(content.left - p.pane.left), '14 px in after the 1 px border').toBe(BORDER + PADDING);
    expect(Math.round(p.pane.right - content.right), '14 px in from the right edge').toBe(PADDING);
  }, 60_000);

  it('live resize follows the window and writes nothing', async () => {
    const page = await openPane(1920, 1000);
    await page.evaluate(key => {
      const w = window as unknown as { paneWrites: string[] };
      w.paneWrites = [];
      const setItem = Storage.prototype.setItem;
      Storage.prototype.setItem = function (this: Storage, name: string, value: string) { if (name === key) w.paneWrites.push(value); setItem.call(this, name, value); };
    }, PANE_KEY);
    for (const [width, px] of [[1400, 420], [2400, 592], [1100, 400], [1920, 576]] as const) {
      await page.setViewportSize({ width, height: 1000 });
      await settle(page, 400);
      const p = await paneParts(page);
      expect(Math.round(p.pane.width), `at ${width}`).toBe(px);
      expect(Math.round(p.pane.right), `at ${width}`).toBe(width);
    }
    expect(await page.evaluate(key => localStorage.getItem(key), PANE_KEY)).toBeNull();
    expect(await page.evaluate(() => (window as unknown as { paneWrites: string[] }).paneWrites)).toEqual([]);
  }, 60_000);

  it('a remembered width still wins over the automatic one, at the window edge', async () => {
    const page = await openPane(1920, 1000, { stored: '450' });
    const p = await paneParts(page);
    expect(Math.round(p.pane.width)).toBe(450);
    expect(Math.round(p.pane.right)).toBe(1920);
  }, 60_000);
});

describe('PJ3 no list+pane cap: the list keeps its 960 and is centred in what the pane leaves', () => {
  it.each([...SIDEBARS.map(([label, sidebar, px]) => [label, sidebar, px, 1920] as const), ['no sidebar', 'none', 0, 2400] as const])(
    'with %s (%s/%s px) at %i', async (_label, sidebar, px, width) => {
      const page = await openPane(width, 1000, { sidebar });
      const p = await paneParts(page);
      const main = await mainColumn(page);
      expect(Math.round(main.left), 'the main column starts after the sidebar').toBe(px);
      expect(Math.round(main.right), 'the main column ends at the pane').toBe(Math.round(p.pane.left));
      const room = p.pane.left - px;
      const gutter = Math.max(0, (room - COLUMN) / 2);
      const list = await listBox(page);
      expect(list.left, 'list within the centred column').toBeGreaterThanOrEqual(px + gutter - 1);
      expect(list.right, 'list within the centred column').toBeLessThanOrEqual(p.pane.left - gutter + 1);
      expect(list.width, 'the list keeps its plain-results maximum').toBeLessThanOrEqual(COLUMN);
      // The list's column is centred between the sidebar and the pane, to within the scroller's bar.
      const col = await listColumn(page);
      const inset = 12; // --floating-content-inset (0.75rem) at the default text size
      expect(Math.abs(col.left - (px + gutter + inset)), `column starts at ${px + gutter + inset}`).toBeLessThanOrEqual(1);
      expect(Math.abs((col.left - px) - (p.pane.left - col.right)), 'column centred between sidebar and pane').toBeLessThanOrEqual(16);
      expect(col.right - col.left, 'column at most 960 less its inset').toBeLessThanOrEqual(COLUMN - 2 * inset + 1);
      expect(list.left).toBeGreaterThanOrEqual(col.left - 1);
      expect(list.right).toBeLessThanOrEqual(col.right + 1);
    }, 60_000);

  it('the frame publishes no list+pane width any more', async () => {
    const page = await openPane(1920, 1000);
    const raw = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--surface-content-with-pane-max-width').trim());
    expect(raw).toBe('');
  }, 60_000);

  it('a wheel in either gutter beside the list moves the list', async () => {
    const page = await openPane(1920, 1000);
    const p = await paneParts(page);
    const centre = Math.round(p.pane.left / 2);
    expect(await wheelMoves(page, 40, 600, centre), 'left gutter').toBeGreaterThan(0);
    expect(await wheelMoves(page, Math.round(p.pane.left - 40), 600, centre), 'gutter before the pane').toBeGreaterThan(0);
  }, 60_000);
});

describe('PJ4 at the default window and in a split nothing changes', () => {
  /** Measured on the base (5a2ac8226e) in this harness. */
  const BASE_1024 = { pane: [640, 384], grip: [628, 24], main: [0, 640], list: [12, 624] } as const;

  it('at 1024\u00d7700 the pane, grip, main column and list keep their pixel geometry', async () => {
    const page = await openPane(1024, 700);
    const p = await paneParts(page);
    const main = await mainColumn(page);
    const list = await listBox(page);
    const measured = {
      pane: [Math.round(p.pane.left), Math.round(p.pane.width)],
      grip: [Math.round(p.grip!.left), Math.round(p.grip!.width)],
      main: [Math.round(main.left), Math.round(main.width)],
      list: [Math.round(list.left), Math.round(list.right)],
    };
    console.info('1024x700 geometry', JSON.stringify(measured));
    expect(measured).toEqual(BASE_1024);
  }, 60_000);

  it('in a stacked split at 1920 the pane keeps the width it had (540) at its split\u2019s edge', async () => {
    const page = await openPane(1920, 1080);
    await page.evaluate(([l, r]) => (window as unknown as { split(l: string, r: string, a: string): Promise<void> }).split(l!, r!, 'vertical'), [SEARCH, LIBRARY] as const);
    await settle(page, 600);
    const p = await paneParts(page);
    expect(Math.round(p.pane.width)).toBe(540);
    expect(Math.round(p.pane.right)).toBe(1920);
  }, 60_000);
});

/** The width of the document's text column in the pane: its blocks' box, inside the pane's edge and padding. */
const contentWidth = (page: Page): Promise<number> => page.evaluate(() => {
  const find = (root: ParentNode, sel: string): Element | null => {
    const hit = root.querySelector(sel); if (hit) return hit;
    for (const el of Array.from(root.querySelectorAll('*'))) { const inner = el.shadowRoot && find(el.shadowRoot, sel); if (inner) return inner; }
    return null;
  };
  const doc = find(document, '[data-testid="sv3-pane-document"]')!;
  return find(doc.shadowRoot ?? doc, '.blocks')!.getBoundingClientRect().width;
});

const paneWidth = async (page: Page): Promise<number> => Math.round((await paneParts(page)).pane.width);
const stored = (page: Page): Promise<string | null> => page.evaluate(key => localStorage.getItem(key), PANE_KEY);

/** Press `key` on the pane's grip and let the width settle. */
async function nudge(page: Page, key: 'ArrowLeft' | 'ArrowRight' | 'Home'): Promise<void> {
  await page.locator('[data-testid="sv3-pane-grip"]').focus();
  await page.keyboard.press(key);
  await settle(page, 300);
}

describe('PJ2′ the automatic width and a drag measure the same outer box (392-592)', () => {
  it.each([[1920, 576], [2400, 592]] as const)(
    'at %i px a nudge from the automatic %i px moves one step, no jump, and Home returns to automatic', async (width, px) => {
      const page = await openPane(width, 1000);
      expect(await paneWidth(page), 'automatic').toBe(px);
      await nudge(page, 'ArrowRight');
      expect(await paneWidth(page), 'one step narrower').toBe(px - 24);
      expect(await stored(page)).toBe(String(px - 24));
      await nudge(page, 'Home');
      expect(await paneWidth(page), 'automatic again').toBe(px);
      expect(await stored(page)).toBeNull();
      await nudge(page, 'ArrowLeft');
      expect(await paneWidth(page), 'one step wider, at most 592').toBe(Math.min(592, px + 24));
      expect(await stored(page)).toBe(String(Math.min(592, px + 24)));
    }, 60_000);

  it('a drag clamps at 392 and 592 outer, and a remembered 592 reopens at 592', async () => {
    const page = await openPane(1920, 1000);
    const grip = page.locator('[data-testid="sv3-pane-grip"]');
    const drag = async (dx: number): Promise<void> => {
      const box = (await grip.boundingBox())!;
      const x = box.x + box.width / 2; const y = box.y + box.height / 2;
      await page.mouse.move(x, y);
      await page.mouse.down();
      await page.mouse.move(x + dx, y, { steps: 5 });
      await page.mouse.up();
      await settle(page, 300);
    };
    await drag(600);
    expect(await paneWidth(page), 'the floor').toBe(392);
    expect(await stored(page)).toBe('392');
    await drag(-600);
    expect(await paneWidth(page), 'the ceiling').toBe(592);
    expect(await stored(page)).toBe('592');
    await context?.close();
    const again = await openPane(1920, 1000, { stored: '592' });
    expect(await paneWidth(again)).toBe(592);
  }, 60_000);

  /** Measured on the base (5a2ac8226e) in this harness: pane width and its text column. */
  it.each([['360', 360, 331], ['450', 450, 421], ['560', 560, 531]] as const)(
    'a width remembered before (%s) shows the pane and its text column as on the base', async (value, px, content) => {
      const page = await openPane(1920, 1000, { stored: value });
      expect(await paneWidth(page)).toBe(px);
      expect(Math.round(await contentWidth(page))).toBe(content);
      expect(await stored(page), 'storage is not rewritten on read').toBe(value);
    }, 60_000);
});
