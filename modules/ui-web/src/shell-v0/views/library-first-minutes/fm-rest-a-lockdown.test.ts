// SPDX-License-Identifier: Apache-2.0
// fm-07 / fm-15 (fm-rest-a), measured in headless Chromium over a private Vite server with a stubbed
// backend (no Engine, no shared dev stack):
//  L5 Library's folder row: folder name 15 px, path 12 px, the state ("Up to date"), the count ("8 files")
//     and the open row's facts 13 px (tmp/design/fm-07-library.html: .nm b, .nm span, .stt, .cnt, .kv).
//  L6 "Remove from library" (words and icon) in the danger colour, dark and light, a text action (no
//     fill, no border), at least 4.5:1 against the row.
//  L7 (kept) the row keeps its height under the pointer and with focus; at 600 px and with 200 % text
//     nothing in the row spills past the page.
//  S1 the panel's "Pause reading" is drawn as the Installing AI entry's "Pause" (fm-15): the same text
//     action; S3 no native select in the panel, and the duration select that still renders (Settings ›
//     Indexing) draws itself with the theme's colours, readable in dark and light.
// Run with npx vitest run -c vitest.config.lockdown.ts src/shell-v0/views/library-first-minutes/fm-rest-a-lockdown.test.ts
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const LIBRARY = 'core.library-surface';

/** A deep query through open shadow roots, a token's used colour where an element is, and contrast. */
const HELPERS = `
window.deep = (selector, root = document) => {
  const hit = root.querySelector(selector); if (hit) return hit;
  for (const el of root.querySelectorAll('*')) { const inner = el.shadowRoot && window.deep(selector, el.shadowRoot); if (inner) return inner; }
  return null;
};
window.deepAll = (selector, root = document) => [...root.querySelectorAll(selector),
  ...[...root.querySelectorAll('*')].flatMap(el => el.shadowRoot ? window.deepAll(selector, el.shadowRoot) : [])];
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
`;

/** The real frame: Shell, top bar and Library over a stubbed backend, one folder "Documents" of 8 files. */
const APP = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
${HELPERS}
const params = new URLSearchParams(location.search);
const json = (body) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
const roots = [{ pathHash: 'root-0', collection: 'default', fileCount: 8, lastIndexedIsoTime: '2026-09-02T00:00:00Z', status: 'indexed', walkError: '', inFlightCount: 0, failedCount: 0, walkCompleted: true }];
window.fetch = async (input) => {
  const url = String(input?.url ?? input);
  if (url.includes('/api/indexing-roots/substrate')) return json({ items: roots, count: roots.length });
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
  surface('${LIBRARY}', 'jf-library-surface'),
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

/** fm-15's panel while files are read and AI installs, and the reading controls as Settings › Indexing draws them. */
const PANEL = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"><style>body { background: var(--surface-0); margin: 24px; }</style></head><body><script type="module">
${HELPERS}
const params = new URLSearchParams(location.search);
document.documentElement.setAttribute('data-theme', params.get('theme') || 'dark');
window.fetch = async () => { throw new TypeError('No backend in this page'); };
const { deriveFrameStatus } = await import('/src/shell-v0/state/frameStatus.ts');
const { selectIndexingProgress } = await import('/src/shell-v0/state/indexingProgress.ts');
await import('/src/shell-v0/components/StatusPanel.ts');
const now = Date.now();
const GIB = 1024 ** 3;
const state = { phase: 'connected', connection: { reachable: true, lastSuccessMs: now, lastContactMs: now, consecutiveFailures: 0 },
  verdict: { kind: 'operational', severity: 'ok', reasons: [] }, stability: { kind: 'settled' },
  aiEngine: { kind: 'installing', stability: { kind: 'settled' }, installFailure: null },
  activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
  status: { worker: { core: { indexState: 'INDEXING', pendingJobs: 8 }, enrichment: {} } },
  installStatus: { state: 'running', paused: false, phase: 'download', totalBytes: 2.6 * GIB, downloadedBytes: 1.3 * GIB, remainingSeconds: 24 * 60 },
  installObservationLive: true, indexingStatusLive: true, snapshotLive: true, episodeMaxPendingJobs: 16, enrichSettleSamples: [] };
const panel = document.createElement('jf-status-panel');
panel.model = deriveFrameStatus({ ai: state, now, contactLostAt: null, rootsLive: true, rootPaths: new Map([['docs', '/home/me/Documents']]),
  roots: [{ pathHash: 'docs', collection: 'default', status: 'scanning', inFlightCount: 8, failedCount: 0, walkCompleted: true, fileCount: 8 }] });
document.body.append(panel);
const settings = document.createElement('jf-indexing-controls');
settings.id = 'settings-form';
settings.setAttribute('allow-pause', '');
settings.progress = selectIndexingProgress({ worker: { core: { indexState: 'INDEXING', pendingJobs: 8 } },
  operating: { indexing: { state: 'RUNNING', reasons: [] } } }, true, 16, []);
document.body.append(settings);
await panel.updateComplete; await settings.updateComplete;
document.body.dataset.ready = 'true';
</script></body></html>`;

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let context: BrowserContext | undefined;
let output: string;
let origin: string;

beforeAll(async () => {
  output = await mkdtemp(path.join(process.env.TMPDIR ?? tmpdir(), 'fm-rest-a-'));
  server = await createServer({
    configFile: false, root: process.cwd(), cacheDir: output, publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{ name: 'fm-rest-a-harness', configureServer(vite) {
      vite.middlewares.use((req, res, next) => {
        const at = req.url?.split('?')[0];
        if (at !== '/app' && at !== '/panel') return next();
        res.setHeader('Content-Type', 'text/html');
        res.end(at === '/app' ? APP : PANEL);
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
async function open(at: '/app' | '/panel', width: number, height: number, theme: Theme): Promise<Page> {
  context = await browser.newContext({ viewport: { width, height }, reducedMotion: 'reduce', colorScheme: theme });
  const page = await context.newPage();
  page.on('pageerror', error => console.info('pageerror', error.message));
  await page.goto(`${origin}${at}?theme=${theme}`, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await settle(page, 400);
  return page;
}

/** Library with the Documents row open. */
async function library(width: number, height: number, theme: Theme = 'dark'): Promise<Page> {
  const page = await open('/app', width, height, theme);
  await page.evaluate(target => { location.hash = `#justsearch://surface/${target}`; }, LIBRARY);
  await settle(page, 600);
  await page.getByRole('heading', { name: 'Your files are searchable' }).waitFor({ timeout: 15_000 });
  await page.locator('.folder-toggle').first().click();
  await page.waitForFunction(() => !!(window as unknown as { deep(s: string): Element | null }).deep('jf-library-surface')
    ?.shadowRoot?.querySelector('.folder-details .remove-action'), undefined, { timeout: 15_000 });
  await settle(page, 300);
  return page;
}

/** The folder row's words and the removal action, as drawn. */
const row = (page: Page) => page.evaluate(() => {
  type W = Window & { deep(s: string, r?: ParentNode): Element | null; tokenAt(n: Element, t: string): string;
    backOf(n: Element): string; contrast(f: string, b: string): number };
  const w = window as unknown as W;
  const lib = w.deep('jf-library-surface')!.shadowRoot!;
  const size = (el: Element | null) => el ? getComputedStyle(el).fontSize : 'missing';
  const remove = lib.querySelector('.remove-action')!;
  const control = remove.shadowRoot!.querySelector('jf-control')!.shadowRoot!.querySelector('button')!;
  const glyph = remove.querySelector('svg') ?? control.querySelector('svg');
  const s = getComputedStyle(control);
  return {
    name: size(lib.querySelector('.folder-name')), path: size(lib.querySelector('.folder-path')),
    state: size(lib.querySelector('.card-meta')), stateWords: lib.querySelector('.card-meta')!.textContent!.trim(),
    count: size(lib.querySelector('.files-found')), countWords: lib.querySelector('.files-found')!.textContent!.trim(),
    facts: [...lib.querySelectorAll('.folder-details dt, .folder-details dd')].map(el => [el.textContent!.trim(), size(el)]),
    remove: { color: s.color, icon: glyph ? getComputedStyle(glyph).color : 'missing', danger: w.tokenAt(control, '--text-danger'),
      background: s.backgroundColor, border: s.borderTopWidth === '0px' || s.borderTopColor === 'rgba(0, 0, 0, 0)' || s.borderTopStyle === 'none',
      contrast: w.contrast(s.color, w.backOf(control)) },
  };
});

describe('L5 the folder row\'s sizes as drawn in fm-07', () => {
  it('name 15 px, path 12 px, state, count and facts 13 px', async () => {
    const page = await library(1024, 700);
    const r = await row(page);
    expect(r.stateWords).toBe('Up to date');
    expect(r.countWords).toBe('8 files');
    expect({ name: r.name, path: r.path, state: r.state, count: r.count }).toEqual({ name: '15px', path: '12px', state: '13px', count: '13px' });
    expect(r.facts.length).toBeGreaterThanOrEqual(4);
    for (const [words, size] of r.facts) expect(size, `fact "${words}"`).toBe('13px');
  }, 90_000);
});

describe('L6 "Remove from library" in the danger colour', () => {
  it.each(['dark', 'light'] as const)('%s: words and icon in the danger colour, no fill, at least 4.5:1', async theme => {
    const page = await library(1024, 700, theme);
    const { remove } = await row(page);
    expect(remove.color, 'words').toBe(remove.danger);
    expect(remove.icon, 'icon').toBe(remove.danger);
    expect(remove.background, 'a text action, not a filled button').toBe('rgba(0, 0, 0, 0)');
    expect(remove.border, 'no border').toBe(true);
    expect(remove.contrast).toBeGreaterThanOrEqual(4.5);
  }, 90_000);
});

describe('L7 (kept) the row under the pointer, with focus, narrow and with large text', () => {
  it('hover and focus keep the row\'s height', async () => {
    const page = await library(1024, 700);
    const height = () => page.evaluate(() => (window as unknown as { deep(s: string): Element | null }).deep('jf-library-surface')!
      .shadowRoot!.querySelector('.folder-summary')!.getBoundingClientRect().height);
    const before = await height();
    await page.locator('.folder-toggle').first().hover(); await settle(page, 100);
    expect(await height()).toBe(before);
    await page.locator('.folder-toggle').first().focus(); await settle(page, 100);
    expect(await height()).toBe(before);
  }, 90_000);
  it.each([[600, '100%'], [1024, '200%']] as const)('at %i px with %s text nothing in the row spills past the page', async (width, text) => {
    const page = await library(width, 700);
    await page.evaluate(size => { document.documentElement.style.fontSize = size; }, text);
    await settle(page, 400);
    const spill = await page.evaluate(() => {
      const lib = (window as unknown as { deep(s: string): Element | null }).deep('jf-library-surface')!.shadowRoot!;
      const scroll = lib.querySelector('.folders-scroll')!.getBoundingClientRect();
      return [...lib.querySelectorAll('.card *')].filter(el => {
        const box = el.getBoundingClientRect();
        return box.width > 0 && (box.right > scroll.right + 1 || box.left < scroll.left - 1);
      }).map(el => el.className || el.localName);
    });
    expect(spill).toEqual([]);
  }, 90_000);
});

describe('S1 / S3 the status panel while files are read', () => {
  it.each(['dark', 'light'] as const)('%s: "Pause reading" is drawn as Installing AI\'s "Pause"; no select in the panel', async theme => {
    const page = await open('/panel', 1024, 900, theme);
    const seen = await page.evaluate(() => {
      type W = Window & { deep(s: string, r?: ParentNode): Element | null; deepAll(s: string, r?: ParentNode): Element[] };
      const w = window as unknown as W;
      const panel = document.querySelector('jf-status-panel')!.shadowRoot!;
      const look = (el: Element) => { const s = getComputedStyle(el);
        return { size: s.fontSize, weight: s.fontWeight, color: s.color, background: s.backgroundColor, border: s.borderTopWidth }; };
      const install = [...panel.querySelectorAll('article[data-fact="ai-install"] .actions button')].find(b => b.textContent!.trim() === 'Pause')!;
      const reading = panel.querySelector('article[data-fact="indexing"]')!;
      const pause = [...reading.querySelector('jf-indexing-controls')!.shadowRoot!.querySelectorAll('button')]
        .find(b => b.textContent!.trim() === 'Pause reading');
      return { install: look(install), pause: pause ? look(pause) : null, selects: w.deepAll('select', document.querySelector('jf-status-panel')!.shadowRoot!).length };
    });
    expect(seen.pause).not.toBeNull();
    expect(seen.pause).toEqual(seen.install);
    expect(seen.selects).toBe(0);
  }, 90_000);

  it.each(['dark', 'light'] as const)('%s: the duration select Settings still draws uses the theme\'s colours itself, readable', async theme => {
    const page = await open('/panel', 1024, 900, theme);
    const seen = await page.evaluate(() => {
      type W = Window & { tokenAt(n: Element, t: string): string; contrast(f: string, b: string): number };
      const w = window as unknown as W;
      const select = document.getElementById('settings-form')!.shadowRoot!.querySelector('select');
      if (!select) return null;
      const s = getComputedStyle(select);
      return { appearance: s.appearance, color: s.color, background: s.backgroundColor, surface: w.tokenAt(select, '--surface-1'),
        text: w.tokenAt(select, '--text-primary'), contrast: w.contrast(s.color, s.backgroundColor) };
    });
    expect(seen, 'Settings › Indexing still offers the durations').not.toBeNull();
    // Drawn by the page, not by the platform's own (light) control: WebKit's view included.
    expect(seen!.appearance).toBe('none');
    expect(seen!.background).toBe(seen!.surface);
    expect(seen!.color).toBe(seen!.text);
    expect(seen!.contrast).toBeGreaterThanOrEqual(4.5);
  }, 90_000);
});
