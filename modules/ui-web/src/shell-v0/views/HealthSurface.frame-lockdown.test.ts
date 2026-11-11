// SPDX-License-Identifier: Apache-2.0
// S6 piece 8 SH13 SH16 — Health inside the real Shell, router and System hub in real Chromium over a
// private Vite server and a stubbed backend: every row action and declared remedy reaches its window
// through the app's navigation path, and Health and Diagnostics reflow at 600x400 with 200% text and keep their
// multi-column layout at 900x700 and 1280x720 without scrolling sideways.
// The destination windows are probes. Run separately with
// npx vitest run --config vitest.config.lockdown.ts src/shell-v0/views/HealthSurface.frame-lockdown.test.ts
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
const json = body => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
// ?long=1 makes every reported name a long unbroken value.
const long = new URLSearchParams(location.search).has('long') ? 'a-folder-name-without-any-break-'.repeat(6) : 'local';
// A current status with unavailable GPU telemetry and no searchable documents;
// the runtime reports a model on the graphics card, so the Graphics card row stays unconfirmed.
const { default: live } = await import('/src/api/__fixtures__/status-response-live.json');
const starting = new URLSearchParams(location.search).get('engine') === 'starting';
const status = () => ({ ...live, indexBasePath: '/home/user/' + long, service: 'justsearch-' + long, gpu: { ...live.gpu, available: false },
  ...(starting ? { status: 'starting', readiness: { ...live.readiness, engineComponents: { ...live.readiness.engineComponents, api: { ...live.readiness.engineComponents.api, state: 'STARTING' } } } } : {}) });
window.statusReads = 0;
window.fetch = async (input) => {
  const path = new URL(String(input instanceof Request ? input.url : input), location.href).pathname;
  if (path.endsWith('/api/status')) { window.statusReads++; return json(status()); }
  if (path.endsWith('/api/inference')) return json({ mode: 'online', starting: false, available: true, generation: 2 });
  if (path.endsWith('/api/indexing/failed-jobs')) return json({ jobs: [{ path: '/home/user/' + long + '/report.pdf', errorMessage: 'ExtractionFailed: ' + long, attempts: 3, lastUpdatedMs: Date.now() }] });
  if (path.endsWith('/api/diagnostics/traces')) return json({ tracesAvailable: true, spans: [{ name: 'span-' + long, trace_id: 'trace-' + long, start: new Date().toISOString(), duration_ms: 12, status: 'OK' }] });
  if (path.endsWith('/api/inference/transitions')) return json({ transitions: [{ timestampMs: Date.now(), fromMode: 'offline', toMode: 'online', reason: 'reason-' + long, success: true, durationMs: 40 }] });
  if (path.endsWith('/api/ai/runtime/status')) return json({ active: { modelPath: '/models/' + long, gpuLayers: 99 } });
  if (path.endsWith('/api/ai/install/status')) return json({ state: 'idle', installedFully: true });
  if (path.includes('indexing-roots')) return json({ items: [], count: 0 });
  return json({ entries: [], jobs: [], runs: [], items: [] });
};
window.EventSource = class { constructor() { this.readyState = 0; } addEventListener() {} removeEventListener() {} close() {} };
const { __seedForTest } = await import('/src/api/registry/SurfaceCatalogClient.ts');
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/chrome/Shell.ts');
await import('/src/shell-v0/views/SystemSurface.ts');
await import('/src/shell-v0/views/HealthSurface.ts');
for (const name of ['search', 'library', 'brain', 'activity', 'logs']) customElements.define('jf-probe-' + name, class extends HTMLElement {
  connectedCallback() { if (!this.shadowRoot) this.attachShadow({ mode: 'open' }).innerHTML = '<p>' + name + '</p>'; }
});
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL', presentation: { labelKey: id, descriptionKey: id },
  consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] }, provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('core.search-v3-surface', 'jf-probe-search'),
  surface('core.library-surface', 'jf-probe-library'),
  surface('core.brain-surface', 'jf-probe-brain'),
  surface('core.system-surface', 'jf-system-surface', { members: ['core.health-surface', 'core.activity-surface', 'core.logs-surface'] }),
  surface('core.health-surface', 'jf-health-surface', { placement: 'DEEPLINK' }),
  surface('core.activity-surface', 'jf-probe-activity', { placement: 'DEEPLINK' }),
  surface('core.logs-surface', 'jf-probe-logs', { placement: 'DEEPLINK' }),
] });
document.body.append(document.createElement('jf-shell'));
document.body.dataset.ready = 'true';
</script></body></html>`;

let server: ViteDevServer;
let browser: Browser;
let browserServer: BrowserServer;
let context: BrowserContext | undefined;
let output: string;
let url: string;

beforeAll(async () => {
  output = await mkdtemp(path.join(process.env.TMPDIR ?? tmpdir(), 'health-frame-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: path.join(output, 'vite'), publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'health-frame', configureServer(vite) {
      vite.middlewares.use((req, res, next) => { if (req.url?.split('?')[0] !== '/health') return next();
        res.setHeader('Content-Type', 'text/html'); res.end(HARNESS); });
    } }] });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/health`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: process.env.TMPDIR ?? output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned Health-frame Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
}, 60_000);
afterEach(async () => { await context?.close(); context = undefined; });
afterAll(async () => { await browser?.close(); await browserServer?.close(); await server?.close(); if (output) await rm(output, { recursive: true, force: true }); });

async function settle(page: Page, ms = 150): Promise<void> {
  await page.waitForTimeout(ms);
  await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
}

/** Open the app at System › Health with current status observed. */
async function openHealth(width: number, height: number, textScale = 1, query = ''): Promise<Page> {
  context = await browser.newContext({ viewport: { width, height }, reducedMotion: 'reduce' });
  const page = await context.newPage();
  page.on('pageerror', error => console.info('health-frame pageerror:', error.message));
  await page.goto(`${url}${query}#justsearch://surface/core.health-surface`, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await page.evaluate(scale => { document.documentElement.style.fontSize = `${scale * 100}%`; }, textScale);
  await page.locator('jf-health-surface .health-row').first().waitFor({ timeout: 45_000 });
  await expect.poll(() => page.evaluate(() => (window as unknown as { statusReads: number }).statusReads), { timeout: 15_000 }).toBeGreaterThan(0);
  if (!query.includes('engine=')) await expect.poll(() => page.locator('jf-health-surface [data-health-row=engine] dd').textContent().then(t => t?.trim()), { timeout: 15_000 }).toBe('');
  await settle(page);
  return page;
}

/** Open Diagnostics and every disclosure inside it, so each readout is laid out. */
async function openDiagnostics(page: Page): Promise<void> {
  const summary = page.locator('jf-health-surface details > summary', { hasText: /^Diagnostics$/ });
  // Diagnostics keeps its open state for the session.
  if (!await summary.evaluate(node => (node.parentElement as HTMLDetailsElement).open)) await summary.click();
  await page.locator('jf-health-surface').evaluate(host => { for (const inner of host.shadowRoot!.querySelectorAll('details details')) (inner as HTMLDetailsElement).open = true; });
  await settle(page, 300);
}

/** The window the stage shows (a System member counts as the member) and the decoded address. */
const where = (page: Page) => page.evaluate(() => {
  const stage = document.querySelector('jf-shell')!.shadowRoot!.querySelector('jf-stage')!.shadowRoot!;
  const deep = (root: ParentNode): Element | undefined => {
    for (const node of root.querySelectorAll('*')) {
      if (/^jf-(health-surface|probe-)/.test(node.localName) && (node as HTMLElement).offsetParent !== null) return node;
      const inner = node.shadowRoot && deep(node.shadowRoot);
      if (inner) return inner;
    }
    return undefined;
  };
  let shown = [...stage.children].find(c => c.localName.startsWith('jf-'));
  if (shown?.localName === 'jf-system-surface') shown = deep(shown.shadowRoot ?? shown) ?? deep(shown) ?? shown;
  return { stage: shown?.localName, address: decodeURIComponent(location.hash.replace(/^#justsearch:\/\/surface\//, '')) };
});

/** System members are addressed through the System window's tab. */
const DESTINATIONS: Record<string, { stage: string; address: string }> = {
  'Open Logs': { stage: 'jf-probe-logs', address: 'core.system-surface?tab=core.logs-surface' },
  'Open Library': { stage: 'jf-probe-library', address: 'core.library-surface' },
  'Open AI Brain': { stage: 'jf-probe-brain', address: 'core.brain-surface' },
  'Recent activity': { stage: 'jf-probe-activity', address: 'core.system-surface?tab=core.activity-surface' },
  'Add documents': { stage: 'jf-probe-library', address: 'core.library-surface' },
  'Open Brain': { stage: 'jf-probe-brain', address: 'core.brain-surface' },
  'Open Health': { stage: 'jf-health-surface', address: 'core.system-surface?tab=core.health-surface' },
};
const HEALTH = { stage: 'jf-health-surface', address: 'core.health-surface' };

async function backToHealth(page: Page): Promise<void> {
  await page.evaluate(() => { location.hash = '#justsearch://surface/core.health-surface'; });
  await page.locator('jf-health-surface .health-row').first().waitFor({ timeout: 10_000 });
  await settle(page);
  expect(await where(page), 'back at System › Health').toEqual(HEALTH);
}

/** Click every row action and every remedy Diagnostics declares; each must reach its window. */
async function navigatesEverywhere(query: string): Promise<void> {
  const page = await openHealth(1280, 720, 1, query);
  const rows = await page.locator('jf-health-surface .health-row').evaluateAll(nodes => nodes.map(node => ({
    id: node.getAttribute('data-health-row') ?? '', action: node.querySelector('jf-button')?.textContent?.trim() ?? '' })));
  expect(rows.find(row => row.id === 'gpu')?.action, 'the Graphics card row').toBe('Open AI Brain');
  const actions = rows.filter(row => row.action);
  expect(new Set(actions.map(row => row.action))).toEqual(new Set([...(query ? ['Open Logs'] : []), 'Open Library', 'Open AI Brain', 'Recent activity']));
  for (const { id, action } of actions) {
    await backToHealth(page);
    await page.locator(`jf-health-surface [data-health-row=${id}] jf-button button`).click();
    await settle(page);
    expect(await where(page), `${id} row: ${action}`).toEqual(DESTINATIONS[action]);
  }
  await backToHealth(page);
  await openDiagnostics(page);
  const remedies = await page.locator('jf-health-surface jf-capability-map .cap-remedy').evaluateAll(nodes => nodes.map(node => node.textContent?.trim() ?? ''));
  expect(remedies.length, 'declared remedies in Diagnostics').toBeGreaterThan(0);
  for (const [index, label] of remedies.entries()) {
    expect(Object.keys(DESTINATIONS), `a destination for "${label}"`).toContain(label);
    await backToHealth(page);
    await openDiagnostics(page);
    await page.locator('jf-health-surface jf-capability-map .cap-remedy').nth(index).locator('button').click();
    await settle(page);
    expect(await where(page), `declared remedy: ${label}`).toEqual(DESTINATIONS[label]);
  }
}

describe('SH13 Health row actions and declared remedies navigate', () => {
  it('every row action and Diagnostics remedy reaches its window through the shell and the address follows (current status, GPU telemetry unavailable, GPU-backed model)',
    () => navigatesEverywhere(''), 120_000);
  // The engine row offers its action only while the engine is not confirmed ready.
  it('every row action and Diagnostics remedy reaches its window through the shell and the address follows (engine starting)',
    () => navigatesEverywhere('?engine=starting'), 120_000);
});

/** Health's sideways overflow: its own body and the open Diagnostics, and elements leaving the window. */
const reflow = (page: Page) => page.locator('jf-health-surface').evaluate(host => {
  const root = host.shadowRoot!;
  const body = root.querySelector<HTMLElement>('.body')!;
  const details = root.querySelector<HTMLElement>('details')!;
  const right = host.getBoundingClientRect().right;
  const outside: string[] = [];
  const cut: string[] = [];
  const walk = (scope: ParentNode): void => {
    for (const node of scope.querySelectorAll<HTMLElement>('*')) {
      const rect = node.getBoundingClientRect();
      if (rect.width > 0 && rect.right > right + 1) outside.push(`${node.localName}.${node.className}`);
      // A value cut by an ellipsis keeps its full text available.
      const style = getComputedStyle(node);
      if (style.textOverflow === 'ellipsis' && node.scrollWidth > node.clientWidth + 1 && node.title !== node.textContent?.trim()) cut.push(node.textContent?.trim().slice(0, 40) ?? '');
      if (node.shadowRoot) walk(node.shadowRoot);
    }
  };
  walk(root);
  return { host: [host.clientWidth, host.scrollWidth], body: [body.clientWidth, body.scrollWidth], details: [details.clientWidth, details.scrollWidth],
    outside: [...new Set(outside)].slice(0, 8), cut };
});

/** Health's layout landmarks with Diagnostics open: each row and metric in document order with its box, and the boxes of the
 *  Diagnostics stat cards and of the first two-column readout group. Rounded to whole pixels; only relations between boxes are asserted. */
const layout = (page: Page) => page.locator('jf-health-surface').evaluate(host => {
  const root = host.shadowRoot!;
  const box = (node: Element) => { const r = node.getBoundingClientRect(); return { left: Math.round(r.left), top: Math.round(r.top), right: Math.round(r.right), bottom: Math.round(r.bottom) }; };
  const edge = box(host);
  return { edge,
    rows: [...root.querySelectorAll('.health-row')].map(node => ({ id: node.getAttribute('data-health-row') ?? '', ...box(node) })),
    metrics: [...root.querySelectorAll('.health-metrics > div')].map(node => ({ id: node.querySelector('dt')?.textContent?.trim() ?? '', ...box(node) })),
    stats: [...root.querySelectorAll('details .stats > *')].map(box),
    readouts: [...root.querySelector('details .grid-2')?.children ?? []].map(box) };
});

type Box = { left: number; top: number; right: number; bottom: number };
/** Boxes that start on the same line as the first one. */
const firstLine = (boxes: readonly Box[]) => boxes.filter(b => Math.abs(b.top - boxes[0]!.top) <= 1);
/** Document order is reading order: each box is right of the one before on its line, or on a later line. */
const readingOrder = (boxes: readonly Box[]) => boxes.every((b, i) => i === 0 || (Math.abs(b.top - boxes[i - 1]!.top) <= 1 ? b.left >= boxes[i - 1]!.right : b.top >= boxes[i - 1]!.bottom - 1));

describe('SH16 Health and Diagnostics reflow', () => {
  it('600x400 with 200% text: Health and the open Diagnostics never scroll sideways and long values stay readable', async () => {
    const page = await openHealth(600, 400, 2, '?long=1');
    await openDiagnostics(page);
    const measured = await reflow(page);
    expect(measured.body[1], `Health body ${measured.body.join(' / ')}; outside: ${measured.outside.join(' ')}`).toBeLessThanOrEqual(measured.body[0]! + 1);
    expect(measured.details[1], `Diagnostics ${measured.details.join(' / ')}`).toBeLessThanOrEqual(measured.details[0]! + 1);
    expect(measured.host[1], 'Health').toBeLessThanOrEqual(measured.host[0]! + 1);
    expect(measured.outside, 'elements beyond Health\'s right edge').toEqual([]);
    expect(measured.cut, 'truncated values without their full text').toEqual([]);
  }, 120_000);

  // The ordinary window sizes with long unbroken values: nothing scrolls sideways, every health row and metric is shown inside
  // Health in its order, the metrics and Diagnostics stat cards share lines, and Diagnostics' readouts pair up only when the
  // window is wider than 60rem (the design's breakpoint, measured against the browser's default text size).
  it.each([[900, 700], [1280, 720]])('%ix%i at 100% with long values: no sideways overflow, every row and metric shown in order, the multi-column layout kept', async (width, height) => {
    const page = await openHealth(width, height, 1, '?long=1');
    await openDiagnostics(page);
    const measured = await reflow(page);
    expect(measured.body[1], `Health body ${measured.body.join(' / ')}; outside: ${measured.outside.join(' ')}`).toBeLessThanOrEqual(measured.body[0]! + 1);
    expect(measured.details[1], `Diagnostics ${measured.details.join(' / ')}`).toBeLessThanOrEqual(measured.details[0]! + 1);
    expect(measured.host[1], 'Health').toBeLessThanOrEqual(measured.host[0]! + 1);
    expect(measured.outside, 'elements beyond Health\'s right edge').toEqual([]);
    expect(measured.cut, 'truncated values without their full text').toEqual([]);
    const { edge, rows, metrics, stats, readouts } = await layout(page);
    expect(rows.map(row => row.id), 'health rows').toEqual(['engine', 'folders', 'index', 'models', 'gpu', 'work']);
    expect(metrics.map(metric => metric.id), 'metrics').toEqual(['Files', 'Size', 'Memory', 'Queue']);
    for (const b of [...rows, ...metrics, ...stats, ...readouts]) {
      expect(b.right > b.left && b.bottom > b.top, `${JSON.stringify(b)} has a size`).toBe(true);
      expect(b.left >= edge.left && b.right <= edge.right + 1, `${JSON.stringify(b)} inside Health ${edge.left}..${edge.right}`).toBe(true);
    }
    expect(rows.every((row, i) => i === 0 || row.top >= rows[i - 1]!.bottom - 1), 'rows stacked in order').toBe(true);
    expect(readingOrder(metrics), 'metrics in reading order').toBe(true);
    expect(firstLine(metrics).length, 'metrics on one line').toBe(metrics.length);
    expect(stats.length, 'Diagnostics stat cards').toBeGreaterThan(1);
    expect(readingOrder(stats), 'stat cards in reading order').toBe(true);
    expect(firstLine(stats).length, 'stat cards sharing the first line').toBeGreaterThan(1);
    expect(readouts.length, 'Connection and GPU readouts').toBe(2);
    expect(readingOrder(readouts), 'readouts in reading order').toBe(true);
    expect(firstLine(readouts).length, 'readouts side by side').toBe(width > 960 ? 2 : 1);
  }, 120_000);
});
