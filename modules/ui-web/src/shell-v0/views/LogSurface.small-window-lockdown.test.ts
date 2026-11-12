// SPDX-License-Identifier: Apache-2.0
// Follow-up C1 amendment FC5a — Logs inside the real Shell, router and System hub, in real Chromium over a
// private Vite server and a stubbed backend. Wherever the small-window layout applies (600x400 with 200%
// text, 600x400, 1280x720 with 200% text) each log row's message has a usable width inside the window and
// nothing needs horizontal scrolling; at 600x400 with 200% text it had neither. At 1280x720 a row keeps its one line of time, level, logger and message.
// npx vitest run --config vitest.config.lockdown.ts src/shell-v0/views/LogSurface.small-window-lockdown.test.ts
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
const json = body => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
const { default: live } = await import('/src/api/__fixtures__/status-response-live.json');
window.fetch = async (input) => {
  const path = new URL(String(input instanceof Request ? input.url : input), location.href).pathname;
  if (path.endsWith('/api/status')) return json(live);
  if (path.endsWith('/api/inference')) return json({ mode: 'online', starting: false, available: true, generation: 2 });
  if (path.endsWith('/api/ai/install/status')) return json({ state: 'idle', installedFully: true });
  return json({ entries: [], jobs: [], runs: [], items: [] });
};
window.EventSource = class { constructor() { this.readyState = 0; } addEventListener() {} removeEventListener() {} close() {} };
const { __seedForTest } = await import('/src/api/registry/SurfaceCatalogClient.ts');
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/chrome/Shell.ts');
await import('/src/shell-v0/views/SystemSurface.ts');
await import('/src/shell-v0/views/LogSurface.ts');
for (const name of ['search', 'library', 'brain', 'health', 'activity']) customElements.define('jf-probe-' + name, class extends HTMLElement {
  connectedCallback() { if (!this.shadowRoot) this.attachShadow({ mode: 'open' }).innerHTML = '<p>' + name + '</p>'; }
});
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL', presentation: { labelKey: id, descriptionKey: id },
  consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] }, provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('core.search-v3-surface', 'jf-probe-search'),
  surface('core.library-surface', 'jf-probe-library'),
  surface('core.brain-surface', 'jf-probe-brain'),
  surface('core.system-surface', 'jf-system-surface', { members: ['core.health-surface', 'core.activity-surface', 'core.logs-surface'] }),
  surface('core.health-surface', 'jf-probe-health', { placement: 'DEEPLINK' }),
  surface('core.activity-surface', 'jf-probe-activity', { placement: 'DEEPLINK' }),
  surface('core.logs-surface', 'jf-log-surface', { placement: 'DEEPLINK' }),
] });
document.body.append(document.createElement('jf-shell'));
document.body.dataset.ready = 'true';
</script></body></html>`;

/** Engine log lines as the diagnostic channel carries them: sentences, paths, a long unbroken token. */
const EVENTS = [
  ['INFO', 'io.justsearch.indexer.worker.queue.JobDispatcher', 'Indexing job 4821 for /home/user/Documents/Projects/quarterly-review/report-final-v3.pdf completed in 1432 ms (extract=512 ms, embed=920 ms)'],
  ['WARN', 'io.justsearch.app.services.health.StatusController', 'Status read took 4210 ms, longer than the 2000 ms budget; the next read is delayed'],
  ['ERROR', 'io.justsearch.indexer.extract.PdfExtractor', 'Extraction failed: /home/user/Downloads/scans/2026-11-12_invoice_from_supplier_with_a_very_long_name_0001.pdf'],
  ['INFO', 'io.justsearch.app.http.EventStream', 'Stream client connected (2 open, peak 3)'],
  ['DEBUG', 'io.justsearch.indexer.embed.Batcher', 'sha256:9f2c4e1ab7d03c5e8f6a2b4c1d9e7f0a3b5c8d2e4f6a1b3c5d7e9f0a2b4c6d8e flushed'],
].map(([level, loggerName, message], i) => ({ level, loggerName, message, threadName: 'main', threadId: 1,
  timestamp: `2026-11-12T07:0${i}:12.345Z`, mdc: {}, dataClasses: [], subCategory: 'CORE_DIAGNOSTIC' }));

let server: ViteDevServer;
let browser: Browser;
let browserServer: BrowserServer;
let context: BrowserContext | undefined;
let output: string;
let url: string;

beforeAll(async () => {
  output = await mkdtemp(path.join(process.env.TMPDIR ?? tmpdir(), 'logs-small-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: path.join(output, 'vite'), publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'logs-small', configureServer(vite) {
      vite.middlewares.use((req, res, next) => { if (req.url?.split('?')[0] !== '/logs') return next();
        res.setHeader('Content-Type', 'text/html'); res.end(HARNESS); });
    } }] });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/logs`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: process.env.TMPDIR ?? output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned logs-small Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
}, 60_000);
afterEach(async () => { await context?.close(); context = undefined; });
afterAll(async () => { await browser?.close(); await browserServer?.close(); await server?.close(); if (output) await rm(output, { recursive: true, force: true }); });

async function settle(page: Page, ms = 150): Promise<void> {
  await page.waitForTimeout(ms);
  await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
}

/** Logs in the System hub at this window size and text scale, holding EVENTS. */
async function open(width: number, height: number, textScale: number): Promise<Page> {
  context = await browser.newContext({ viewport: { width, height }, reducedMotion: 'reduce' });
  const page = await context.newPage();
  page.on('pageerror', error => console.info('logs-small pageerror:', error.message));
  await page.goto(`${url}#justsearch://surface/core.logs-surface`, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await page.evaluate(scale => { document.documentElement.style.fontSize = `${scale * 100}%`; }, textScale);
  await page.locator('jf-log-surface .body').waitFor({ state: 'attached', timeout: 45_000 });
  await page.locator('jf-log-surface').evaluate((host, events) => { (host as HTMLElement & { events: unknown }).events = events; }, EVENTS);
  await page.locator('jf-log-surface .row').first().waitFor({ state: 'attached', timeout: 15_000 });
  await settle(page, 300);
  return page;
}

/** Each row's cells, in the window's coordinates, and what scrolls sideways. */
const layout = (page: Page) => page.locator('jf-log-surface').evaluate(host => {
  const root = host.shadowRoot!;
  const body = root.querySelector<HTMLElement>('.body')!;
  const box = (node: Element) => { const b = node.getBoundingClientRect(); return { left: Math.round(b.left), right: Math.round(b.right), top: Math.round(b.top), width: Math.round(b.width) }; };
  const rows = [...root.querySelectorAll('.row')].map(row => ({ row: box(row),
    ts: box(row.querySelector('.ts')!), level: box(row.querySelector('.level')!), logger: box(row.querySelector('.logger')!), msg: box(row.querySelector('.msg')!) }));
  // A usable measure: twenty characters of the message's own font per line.
  const probe = document.createElement('span');
  probe.textContent = '0'.repeat(20);
  probe.style.cssText = `position:absolute;white-space:pre;font:${getComputedStyle(root.querySelector('.row .msg')!).font}`;
  root.append(probe);
  const measure = Math.round(probe.getBoundingClientRect().width);
  probe.remove();
  const frame = host.getBoundingClientRect();
  return { rows, measure, window: { left: Math.max(Math.round(frame.left), 0), right: Math.min(Math.round(frame.right), innerWidth) },
    rem: parseFloat(getComputedStyle(document.documentElement).fontSize),
    sideways: { host: host.scrollWidth - host.clientWidth, body: body.scrollWidth - body.clientWidth, page: document.documentElement.scrollWidth - innerWidth } };
});

/** The message is inside the window with at least a usable measure (or the whole row, if the row is narrower); nothing scrolls sideways. */
async function expectReadable(width: number, height: number, scale: number): Promise<void> {
  const page = await open(width, height, scale);
  const g = await layout(page);
  expect(g.rows.length, 'the log rows render').toBe(EVENTS.length);
  expect(g.sideways, `no horizontal scrolling ${JSON.stringify(g.sideways)}`).toEqual({ host: 0, body: 0, page: 0 });
  for (const [i, row] of g.rows.entries()) {
    const usable = Math.min(g.measure, row.row.width);
    expect(row.msg.left, `row ${i} message starts inside the window ${JSON.stringify(row)}`).toBeGreaterThanOrEqual(g.window.left);
    expect(row.msg.right, `row ${i} message ends inside the window ${JSON.stringify(row)}`).toBeLessThanOrEqual(g.window.right + 1);
    expect(row.msg.width, `row ${i} message width, usable measure ${g.measure} px ${JSON.stringify(row)}`).toBeGreaterThanOrEqual(usable);
    for (const cell of ['ts', 'level', 'logger'] as const) {
      expect(row[cell].right, `row ${i} ${cell} ends inside the window ${JSON.stringify(row)}`).toBeLessThanOrEqual(g.window.right + 1);
    }
  }
}

describe('FC5a Logs is readable wherever the small-window layout applies', () => {
  it('600x400 with 200% text: every message has a usable width inside the window; nothing scrolls sideways', () =>
    expectReadable(600, 400, 2), 120_000);

  // The other sizes the small-window layout covers stay readable (they were before, too).
  it.each([['600x400', 600, 400, 1], ['1280x720 with 200% text', 1280, 720, 2]] as const)(
    '%s: every message keeps a usable width inside the window; nothing scrolls sideways',
    async (_name, width, height, scale) => expectReadable(width, height, scale), 120_000);

  it('1280x720: each row is one line of time, level, logger and message in their columns', async () => {
    const page = await open(1280, 720, 1);
    const g = await layout(page);
    expect(g.rows.length).toBe(EVENTS.length);
    const gap = 0.5 * g.rem;
    for (const [i, row] of g.rows.entries()) {
      const at = (rem: number, gaps: number) => Math.round(row.row.left + rem * g.rem + gaps * gap);
      expect([row.ts.left, row.level.left, row.logger.left, row.msg.left], `row ${i} columns ${JSON.stringify(row)}`)
        .toEqual([at(0, 0), at(9, 1), at(13, 2), at(22, 3)]);
      expect([row.ts.width, row.level.width, row.logger.width], `row ${i} column widths`).toEqual([9, 4, 9].map(rem => Math.round(rem * g.rem)));
      expect(row.msg.right, `row ${i} message fills the line`).toBe(row.row.right);
      expect(new Set([row.ts.top, row.level.top, row.logger.top]).size, `row ${i} one line ${JSON.stringify(row)}`).toBe(1);
    }
  }, 120_000);
});
