// SPDX-License-Identifier: Apache-2.0
// fm-07 / fm-06 design review, decisions PG1, PG2 and PG4, measured in headless Chromium over a private
// Vite server with a stubbed backend (no Engine, no shared dev stack):
//  PG1 Library (Folders and Browse): the tab row, the page content and the foot actions share one left
//      edge and one right edge at every width, maximised included; the tab row's line still spans the
//      window.
//  PG2 Library's foot actions "Activity" and "Read everything again" look the same: 13 px, medium (500),
//      the normal text colour.
//  PG4 Text actions "Pause reading" (Library), "Open AI Brain" and "Add a folder" (fm-06, Search found
//      nothing) are 13 px, medium (500), the normal text colour.
// Run with npx vitest run -c vitest.config.lockdown.ts src/shell-v0/views/library-first-minutes/alignment-lockdown.test.ts
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const LIBRARY = 'core.library-surface';
const BROWSE = 'core.browse-surface';

/** Helpers both pages share: a deep query through open shadow roots, the used colour of a token where
 *  an element is, and the contrast of a colour over the background behind an element. */
const HELPERS = `
window.deep = (selector, root = document) => {
  const hit = root.querySelector(selector); if (hit) return hit;
  for (const el of root.querySelectorAll('*')) { const inner = el.shadowRoot && window.deep(selector, el.shadowRoot); if (inner) return inner; }
  return null;
};
window.tokenAt = (node, name) => { const probe = document.createElement('i'); probe.style.color = 'var(' + name + ')';
  node.getRootNode().append(probe); const value = getComputedStyle(probe).color; probe.remove(); return value; };
window.backOf = node => {
  for (let at = node; at; at = at.parentElement ?? at.assignedSlot ?? at.getRootNode().host ?? null) {
    const color = getComputedStyle(at).backgroundColor;
    if (color !== 'rgba(0, 0, 0, 0)' && color !== 'transparent') return color;
  }
  return getComputedStyle(document.body).backgroundColor;
};
window.contrast = (fore, back) => {
  const canvas = document.createElement('canvas'); canvas.width = canvas.height = 1;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  const pixel = (...colors) => { ctx.clearRect(0, 0, 1, 1); for (const c of ['#808080', ...colors]) { ctx.fillStyle = c; ctx.fillRect(0, 0, 1, 1); }
    return [...ctx.getImageData(0, 0, 1, 1).data].slice(0, 3); };
  const lum = rgb => { const c = rgb.map(v => { const n = v / 255; return n <= .04045 ? n / 12.92 : ((n + .055) / 1.055) ** 2.4; });
    return .2126 * c[0] + .7152 * c[1] + .0722 * c[2]; };
  const b = lum(pixel(back)), f = lum(pixel(back, fore));
  return (Math.max(b, f) + .05) / (Math.min(b, f) + .05);
};
/** How a text action draws its words: the element that paints them, its size, weight and colour. */
window.look = node => { const s = getComputedStyle(node); const color = s.color;
  return { size: s.fontSize, weight: s.fontWeight, color, primary: window.tokenAt(node, '--text-primary'),
    contrast: window.contrast(color, window.backOf(node)) }; };
`;

/** The real frame: Shell, Stage, top bar, Library with Browse as its member, over a stubbed backend. */
const APP = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
${HELPERS}
const params = new URLSearchParams(location.search);
const json = (body) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
const root = (i) => ({ pathHash: 'root-' + i, collection: 'default', fileCount: 8, lastIndexedIsoTime: '2026-09-02T00:00:00Z', status: 'indexed', walkError: '', inFlightCount: 0, failedCount: 0, walkCompleted: true });
const roots = [root(0)];
window.fetch = async (input) => {
  const url = String(input?.url ?? input);
  if (url.includes('/api/indexing-roots/substrate')) return json({ items: roots, count: roots.length });
  if (url.includes('/api/indexing/roots')) return json({ roots: Array.from({ length: 12 }, (_, i) => ({ path: 'C:/Docs/folder-' + String(i).padStart(2, '0'), fileCount: 10 })) });
  if (url.includes('/api/path-hash/resolve')) return json({ path: '/home/me/Documents' });
  return json({ entries: [], conversations: [], sessions: [] });
};
window.EventSource = class { constructor() { this.readyState = 0; } addEventListener() {} removeEventListener() {} close() {} };
const { __seedForTest } = await import('/src/api/registry/SurfaceCatalogClient.ts');
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/chrome/Shell.ts');
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL',
  presentation: { labelKey: id, descriptionKey: id }, consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('core.search-v3-surface', 'jf-sv3-window'),
  surface('${LIBRARY}', 'jf-library-surface', { members: ['${BROWSE}'] }),
  surface('${BROWSE}', 'jf-browse-surface', { placement: 'DEEPLINK' }),
  surface('core.settings-surface', 'jf-settings-surface', { placement: 'MODAL' }),
] });
const firstUse = await import('/src/shell-v0/state/firstUse.ts').catch(() => null);
const ai = await import('/src/shell-v0/state/aiStateStore.ts');
const observe = () => {
  firstUse?.setFirstUseRoots(roots, new Map(roots.map(r => [r.pathHash, '/home/me/Documents'])));
  ai.__feedForTest({ status: { conversationProtection: { state: 'unlocked' }, worker: { core: { indexState: 'IDLE', indexedDocuments: 8, searchableDocuments: 8, ownSearchableDocuments: 8, pendingJobs: 0, indexHealthy: true } } } });
  ai.__feedContactForTest();
};
document.body.append(document.createElement('jf-shell'));
observe();
setInterval(observe, 2_000);
const theme = () => document.documentElement.setAttribute('data-theme', params.get('theme') || 'dark');
theme(); setInterval(theme, 500);
document.body.dataset.ready = 'true';
</script></body></html>`;

/** Search found nothing for the words typed and the optional AI models are not installed (fm-06). */
const EMPTY = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"><style>body { background: var(--surface-0); }</style></head><body><script type="module">
${HELPERS}
const params = new URLSearchParams(location.search);
document.documentElement.setAttribute('data-theme', params.get('theme') || 'dark');
window.fetch = async () => { throw new TypeError('No backend in this page'); };
const ai = await import('/src/shell-v0/state/aiStateStore.ts');
const { Sv3Results } = await import('/src/shell-v0/views/search-v3/Sv3Results.ts');
const { SV3_RESULTS_IDLE } = await import('/src/shell-v0/views/search-v3/sv3-search.ts');
ai.__feedForTest({ status: { worker: { core: { indexState: 'IDLE', ownSearchableDocuments: 8 } } }, inference: { mode: 'offline', available: false } });
ai.__feedContactForTest(); ai.setInstallState(false, false);
const el = new Sv3Results();
el.view = { ...SV3_RESULTS_IDLE, status: 'empty', query: 'boiler',
  snapshot: { query: 'boiler', results: [], matchCount: 0, totalHits: 0, facetsTruncated: false, isSearching: false, error: null,
    processingTimeMs: 40, searchTrace: { stages: [{ id: 'sparse-retrieval', status: 'executed' }, { id: 'dense-retrieval', status: 'skipped' }, { id: 'splade-retrieval', status: 'skipped' }] } } };
el.style.cssText = 'display:block;width:720px;margin:24px';
document.body.append(el);
await el.updateComplete;
document.body.dataset.ready = 'true';
</script></body></html>`;

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let context: BrowserContext | undefined;
let output: string;
let origin: string;

beforeAll(async () => {
  output = await mkdtemp(path.join(process.env.TMPDIR ?? tmpdir(), 'fm-alignment-'));
  server = await createServer({
    configFile: false, root: process.cwd(), cacheDir: output, publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{ name: 'fm-alignment-harness', configureServer(vite) {
      vite.middlewares.use((req, res, next) => {
        const at = req.url?.split('?')[0];
        if (at !== '/app' && at !== '/empty') return next();
        res.setHeader('Content-Type', 'text/html');
        res.end(at === '/app' ? APP : EMPTY);
      });
    } }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  origin = `http://127.0.0.1:${address.port}`;
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

type Theme = 'dark' | 'light';
async function open(at: '/app' | '/empty', width: number, height: number, theme: Theme): Promise<Page> {
  context = await browser.newContext({ viewport: { width, height }, reducedMotion: 'reduce', colorScheme: theme });
  const page = await context.newPage();
  page.on('pageerror', error => console.info('pageerror', error.message));
  await page.goto(`${origin}${at}?theme=${theme}`, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await settle(page, 400);
  return page;
}

async function library(width: number, height: number, theme: Theme = 'dark'): Promise<Page> {
  const page = await open('/app', width, height, theme);
  await page.evaluate(target => { location.hash = `#justsearch://surface/${target}`; }, LIBRARY);
  await settle(page, 600);
  await page.getByRole('heading', { name: 'Your files are searchable' }).waitFor({ timeout: 15_000 });
  await page.getByRole('button', { name: 'Read everything again' }).waitFor({ timeout: 15_000 });
  await settle(page, 300);
  return page;
}

interface Edges { left: number; right: number }
interface LibraryEdges {
  window: number;
  tabLine: Edges;
  /** Where the first tab's label begins, and the outer edge of the last tab-row action. */
  tabLabel: number; actionsRight: number;
  /** The page content's box (inside the page's own padding). */
  content: Edges;
  heading: number; folderList: Edges;
  /** Where each foot action's words begin. */
  foot: { activity: number; reread: number };
}

/** The edges PG1 compares on Folders, through the real frame's shadow roots. */
const folderEdges = (page: Page): Promise<LibraryEdges> => page.evaluate(() => {
  type W = Window & { deep(s: string, r?: ParentNode): Element | null };
  const w = window as unknown as W;
  const lib = w.deep('jf-library-surface')!.shadowRoot!;
  const tabs = lib.querySelector('jf-surface-tabs')!.shadowRoot!;
  const textLeft = (el: Element) => {
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, { acceptNode: n => n.textContent?.trim() ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP });
    const node = walker.nextNode(); if (!node) throw new Error('no words in ' + el.outerHTML.slice(0, 80));
    const range = document.createRange(); range.selectNodeContents(node);
    // The words, not the white space around them.
    const text = node.textContent!; const start = text.length - text.trimStart().length;
    range.setStart(node, start); range.setEnd(node, start + text.trim().length);
    return range.getBoundingClientRect().left;
  };
  const bar = tabs.querySelector('.tab-bar')!.getBoundingClientRect();
  const scroll = lib.querySelector('.folders-scroll')!;
  const s = getComputedStyle(scroll); const r = scroll.getBoundingClientRect();
  const addControl = (lib.querySelector('[data-add-folder]')!.shadowRoot!.querySelector('jf-control')!.shadowRoot!.querySelector('button')!).getBoundingClientRect();
  const list = lib.querySelector('jf-declared-surface, [role="list"]')!.getBoundingClientRect();
  return {
    window: innerWidth,
    tabLine: { left: bar.left, right: bar.right },
    tabLabel: textLeft(tabs.querySelector('button[data-tab-id="folders"]')!),
    actionsRight: addControl.right,
    content: { left: r.left + parseFloat(s.paddingLeft), right: r.right - parseFloat(s.paddingRight) },
    heading: lib.querySelector('.header-left h2')!.getBoundingClientRect().left,
    folderList: { left: list.left, right: list.right },
    foot: { activity: textLeft(lib.querySelector('.bottom-links [label="Activity"]')!), reread: textLeft(lib.querySelector('.bottom-links [data-reread]')!) },
  };
});

/** The edges PG1 compares on Browse: the tab row against the filter field and the file table. */
const browseEdges = (page: Page) => page.evaluate(() => {
  type W = Window & { deep(s: string, r?: ParentNode): Element | null };
  const w = window as unknown as W;
  const lib = w.deep('jf-library-surface')!.shadowRoot!;
  const tabs = lib.querySelector('jf-surface-tabs')!.shadowRoot!;
  const browse = tabs.querySelector('jf-browse-surface')!.shadowRoot!;
  const label = tabs.querySelector('button[data-tab-id="core.browse-surface"]')!;
  const first = tabs.querySelector('button[role="tab"]')!;
  const range = document.createRange(); range.selectNodeContents(first);
  const words = [...range.getClientRects()].filter(b => b.width > 1);
  const addControl = (lib.querySelector('[data-add-folder]')!.shadowRoot!.querySelector('jf-control')!.shadowRoot!.querySelector('button')!).getBoundingClientRect();
  const input = browse.querySelector('.filter-input');
  const field = input ? input.getBoundingClientRect() : null;
  const heads = browse.querySelector('.column-heads')!.getBoundingClientRect();
  const bar = tabs.querySelector('.tab-bar')!.getBoundingClientRect();
  return { window: innerWidth, selected: label.getAttribute('aria-selected'), tabLine: { left: bar.left, right: bar.right },
    tabLabel: Math.min(...words.map(b => b.left)), actionsRight: addControl.right,
    field: field ? { left: field.left, right: field.right } : null, table: { left: heads.left, right: heads.right } };
});

const near = (a: number, b: number, why: string) => expect(Math.abs(a - b), `${why}: ${a.toFixed(1)} against ${b.toFixed(1)}`).toBeLessThanOrEqual(1);

describe('PG1 Library: one left edge and one right edge for the tab row, the page and the foot', () => {
  it.each([[1024, 700], [1920, 1080], [1280, 800], [800, 700], [600, 700]] as const)('Folders at %ix%i', async (width, height) => {
    const page = await library(width, height);
    const e = await folderEdges(page);
    // The tab row's line spans the window (the wide-window rule).
    near(e.tabLine.left, 0, 'tab line starts at the window edge');
    near(e.tabLine.right, e.window, 'tab line ends at the window edge');
    near(e.heading, e.content.left, 'heading on the page edge');
    near(e.folderList.left, e.content.left, 'folder list on the page edge');
    near(e.folderList.right, e.content.right, 'folder list to the page right edge');
    near(e.tabLabel, e.content.left, 'first tab label on the page left edge');
    near(e.actionsRight, e.content.right, 'tab-row actions end on the page right edge');
    near(e.foot.activity, e.content.left, 'first foot action on the page left edge');
    if (width === 1920) {
      // Maximised: the page column is 960 px wide, centred, and the tab row keeps to it.
      near(e.content.left, (width - 960) / 2 + 40, 'maximised column left');
      near(e.content.right, width - (width - 960) / 2 - 40, 'maximised column right');
    }
  }, 90_000);

  it.each([[1024, 700], [1920, 1080], [800, 700], [640, 700], [600, 700]] as const)('Browse at %ix%i', async (width, height) => {
    const page = await library(width, height);
    await page.getByRole('tab', { name: 'core.browse-surface' }).or(page.getByRole('tab', { name: /Browse/ })).first().click();
    await page.getByRole('treeitem', { name: /folder-00/ }).waitFor({ timeout: 15_000 });
    await settle(page, 400);
    const e = await browseEdges(page);
    expect(e.selected).toBe('true');
    near(e.tabLine.left, 0, 'tab line starts at the window edge');
    near(e.tabLine.right, e.window, 'tab line ends at the window edge');
    near(e.tabLabel, e.table.left, 'first tab label on the page left edge');
    near(e.actionsRight, e.table.right, 'tab-row actions end on the page right edge');
    // Developer tools add a filter field above the table; it keeps to the same edges.
    await page.evaluate(() => { const w = window as unknown as { deep(s: string): (Element & { developer: boolean }) | null }; w.deep('jf-browse-surface')!.developer = true; });
    await page.locator('input[aria-label="Filter files"]').waitFor({ timeout: 15_000 });
    await settle(page, 300);
    const d = await browseEdges(page);
    near(d.field!.left, d.table.left, 'filter field on the table edge');
    near(d.field!.right, d.table.right, 'filter field to the table right edge');
  }, 90_000);
});

type Look = { size: string; weight: string; color: string; primary: string; contrast: number };
const textAction = (look: Look, name: string) => {
  expect(look.size, `${name} size`).toBe('13px');
  expect(look.weight, `${name} weight`).toBe('500');
  expect(look.color, `${name} is the normal text colour`).toBe(look.primary);
  expect(look.contrast, `${name} contrast`).toBeGreaterThanOrEqual(4.5);
};

describe('PG2 / PG4 text actions: 13 px, medium, the normal text colour, in both themes', () => {
  const primaries: Record<string, string> = {};
  it.each(['dark', 'light'] as const)('Library foot actions and Pause reading (%s)', async theme => {
    const page = await library(1024, 700, theme);
    const looks = await page.evaluate(() => {
      type W = Window & { deep(s: string, r?: ParentNode): Element | null; look(n: Element): Look };
      const w = window as unknown as W;
      const lib = w.deep('jf-library-surface')!.shadowRoot!;
      const activity = lib.querySelector('.bottom-links [label="Activity"]')!.shadowRoot!.querySelector('jf-control')!.shadowRoot!.querySelector('button')!;
      const pause = lib.querySelector('[data-testid="library-indexing-pause"]')!.shadowRoot!.querySelector('#pause-toggle')!;
      return { activity: w.look(activity), reread: w.look(lib.querySelector('.bottom-links [data-reread]')!), pause: w.look(pause),
        pauseWords: pause.textContent!.trim() };
    });
    textAction(looks.activity, 'Activity');
    textAction(looks.reread, 'Read everything again');
    expect(looks.pauseWords).toBe('Pause reading');
    textAction(looks.pause, 'Pause reading');
    // Siblings, same style, also under the pointer: no box appears, the words are underlined.
    expect(looks.reread).toEqual(looks.activity);
    for (const name of ['Activity', 'Read everything again']) {
      await page.getByRole('button', { name, exact: true }).hover();
      await settle(page, 100);
      const hovered = await page.evaluate(which => {
        const w = window as unknown as { deep(s: string): Element | null };
        const lib = w.deep('jf-library-surface')!.shadowRoot!;
        const node = which === 'Activity'
          ? lib.querySelector('.bottom-links [label="Activity"]')!.shadowRoot!.querySelector('jf-control')!.shadowRoot!.querySelector('button')!
          : lib.querySelector('.bottom-links [data-reread]')!;
        const s = getComputedStyle(node);
        return { back: s.backgroundColor, line: s.textDecorationLine };
      }, name);
      expect(hovered, `${name} under the pointer`).toEqual({ back: 'rgba(0, 0, 0, 0)', line: 'underline' });
    }
    primaries[theme] = looks.activity.primary;
    if (primaries.dark && primaries.light) expect(primaries.dark, 'the two themes differ').not.toBe(primaries.light);
  }, 90_000);

  it.each(['dark', 'light'] as const)('fm-06: Open AI Brain and Add a folder when Search finds nothing (%s)', async theme => {
    const page = await open('/empty', 1024, 700, theme);
    const looks = await page.evaluate(() => {
      type W = Window & { deep(s: string, r?: ParentNode): Element | null; look(n: Element): Look };
      const w = window as unknown as W;
      const results = w.deep('jf-sv3-results')!.shadowRoot!;
      const brain = results.querySelector('[data-testid="empty-ai"] button')!;
      const add = results.querySelector('jf-getting-started')!.shadowRoot!.querySelector('.folder-row button')!;
      return { brain: w.look(brain), brainWords: brain.textContent!.trim(), add: w.look(add), addWords: add.textContent!.trim() };
    });
    expect(looks.brainWords).toBe('Open AI Brain');
    expect(looks.addWords).toBe('Add a folder');
    textAction(looks.brain, 'Open AI Brain');
    textAction(looks.add, 'Add a folder');
  }, 90_000);
});
