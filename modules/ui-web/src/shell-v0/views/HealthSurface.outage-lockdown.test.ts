// SPDX-License-Identifier: Apache-2.0
// Follow-up C1 FC1-FC3 — Health inside the real Shell and System hub, in real Chromium over a private Vite
// server, when the stubbed Engine stops answering: a refused connection, an aborted read or a server
// error. The outage is shared by every window of the origin and survives a reload.
// npx vitest run --config vitest.config.lockdown.ts src/shell-v0/views/HealthSurface.outage-lockdown.test.ts
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
// A current, fine status: an idle healthy index and a working graphics card, so every row starts quiet.
const live = { status: 'ok',
  worker: { core: { indexState: 'IDLE', indexHealthy: true, indexedDocuments: 5, searchableDocuments: 5, pendingJobs: 0 } },
  readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] }, aiFeatures: { state: 'READY', reasonCodes: [] } } },
  gpu: { available: true, cudaFunctional: true }, memoryUsedBytes: 5, memoryMaxBytes: 10 };
window.statusReads = 0; window.statusFailures = 0;
// The Engine's state is the origin's: every window and every reload sees the same outage.
window.fetch = async (input) => {
  const path = new URL(String(input instanceof Request ? input.url : input), location.href).pathname;
  const engine = localStorage.getItem('engine') ?? 'up';
  if (engine !== 'up' && path.endsWith('/api/status')) window.statusFailures++;
  if (engine === 'refused') throw new TypeError('Failed to fetch');
  if (engine === 'aborted') throw new DOMException('The user aborted a request.', 'AbortError');
  if (engine === 'server error') return json({ error: 'Internal Server Error' }, 500);
  // The worker sample is taken now, as a running Engine's is.
  if (path.endsWith('/api/status')) { window.statusReads++; return json({ ...live, meta: { workerRpcAtMs: Date.now(), workerRpcStale: false } }); }
  if (path.endsWith('/api/inference')) return json({ mode: 'online', starting: false, available: true, generation: 2 });
  if (path.endsWith('/api/ai/install/status')) return json({ state: 'idle', installedFully: true });
  if (path.endsWith('/api/ai/runtime/status')) return json({ active: { modelPath: '/models/local', gpuLayers: 99 } });
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
  output = await mkdtemp(path.join(process.env.TMPDIR ?? tmpdir(), 'health-outage-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: path.join(output, 'vite'), publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'health-outage', configureServer(vite) {
      vite.middlewares.use((req, res, next) => { if (req.url?.split('?')[0] !== '/outage') return next();
        res.setHeader('Content-Type', 'text/html'); res.end(HARNESS); });
    } }] });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/outage`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: process.env.TMPDIR ?? output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned Health-outage Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
}, 60_000);
afterEach(async () => { await context?.close(); context = undefined; });
afterAll(async () => { await browser?.close(); await browserServer?.close(); await server?.close(); if (output) await rm(output, { recursive: true, force: true }); });

const ROWS = ['engine', 'gpu', 'folders', 'index'] as const;
const HEALTH = '#justsearch://surface/core.health-surface';
const ELSEWHERE = '#justsearch://surface/core.activity-surface';

async function newWindow(forcedColors: 'active' | 'none' = 'none'): Promise<Page> {
  context ??= await browser.newContext({ viewport: { width: 1280, height: 720 }, reducedMotion: 'reduce', forcedColors });
  const page = await context.newPage();
  page.on('pageerror', error => console.info('health-outage pageerror:', error.message));
  return page;
}
/** Load the app at System › Health; with the Engine up, wait until Health is quiet. */
async function load(page: Page, up = true): Promise<void> {
  await page.goto(`${url}${HEALTH}`, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await page.locator('jf-health-surface .health-row').first().waitFor({ timeout: 45_000 });
  if (up) await expect.poll(() => rows(page), { timeout: 15_000 }).toEqual(['', '', '', '']);
}
const row = (page: Page, id: string) => page.locator(`jf-health-surface [data-health-row=${id}] dd`).textContent({ timeout: 5_000 }).then(t => t?.trim() ?? '');
const rows = (page: Page) => Promise.all(ROWS.map(id => row(page, id)));
const files = (page: Page) => page.locator('jf-health-surface .health-metrics > div', { hasText: 'Files' }).locator('dd').textContent().then(t => t?.trim() ?? '');
const healthText = (page: Page) => page.locator('jf-health-surface').evaluate(host => host.shadowRoot!.textContent ?? '');
const headline = (page: Page) => page.locator('jf-health-surface .header-left > p[role=status]').textContent().then(t => t?.trim() ?? '');
const failures = (page: Page) => page.evaluate(() => (window as unknown as { statusFailures: number }).statusFailures);
const setEngine = (page: Page, state: string) => page.evaluate(value => localStorage.setItem('engine', value), state);
/** The next status read after `before` failed; its time on this page's clock. */
async function failedRead(page: Page, before: number): Promise<number> {
  await expect.poll(() => failures(page), { timeout: 15_000, interval: 100 }).toBeGreaterThan(before);
  return Date.now();
}
/** Within one second of a failed read: every dependent row unconfirmed, the counts marked, words for the outage. */
async function unconfirmedAfter(page: Page, failedAt: number): Promise<void> {
  await expect.poll(async () => (await rows(page)).every(text => /unconfirmed/i.test(text)), { timeout: 1_000, interval: 100 }).toBe(true);
  expect(Date.now() - failedAt, 'within one failed read').toBeLessThan(5_000);
  expect(await files(page), 'Files count').toMatch(/last known|unconfirmed|—/i);
  expect(await headline(page), 'the headline in words').toMatch(/Engine/);
  expect(await healthText(page), 'no raw transport error').not.toMatch(/Failed to fetch|aborted a request|Health observation unavailable|\(500\)/);
  for (const id of ['index', 'folders']) expect(await row(page, id), id).not.toMatch(/Catching up|backend is busy|Rebuilding/i);
}
async function resumes(page: Page): Promise<void> {
  await setEngine(page, 'up');
  await expect.poll(() => rows(page), { timeout: 15_000 }).toEqual(['', '', '', '']);
  expect(await headline(page)).not.toMatch(/Can.t get/);
}

describe('FC1 FC2 FC3 the Engine stops answering', () => {
  it('refused with Health open: rows unconfirmed within one failed read, then quiet again without a reload', async () => {
    const page = await newWindow();
    await load(page);
    const before = await failures(page);
    await setEngine(page, 'refused');
    await unconfirmedAfter(page, await failedRead(page, before));
    await resumes(page);
  }, 120_000);

  it('aborted with Health hidden: unconfirmed on return; the Engine back while hidden: quiet on return', async () => {
    const page = await newWindow();
    await load(page);
    await page.evaluate(hash => { location.hash = hash; }, ELSEWHERE);
    await expect.poll(() => page.locator('jf-health-surface').count()).toBe(0);
    const before = await failures(page);
    await setEngine(page, 'aborted');
    const failedAt = await failedRead(page, before);
    await page.evaluate(hash => { location.hash = hash; }, HEALTH);
    await page.locator('jf-health-surface .health-row').first().waitFor({ timeout: 10_000 });
    await unconfirmedAfter(page, failedAt);
    await page.evaluate(hash => { location.hash = hash; }, ELSEWHERE);
    await setEngine(page, 'up');
    await expect.poll(() => page.evaluate(() => (window as unknown as { statusReads: number }).statusReads), { timeout: 15_000 }).toBeGreaterThan(0);
    const reads = await page.evaluate(() => (window as unknown as { statusReads: number }).statusReads);
    await expect.poll(() => page.evaluate(() => (window as unknown as { statusReads: number }).statusReads), { timeout: 15_000 }).toBeGreaterThan(reads);
    await page.evaluate(hash => { location.hash = hash; }, HEALTH);
    await page.locator('jf-health-surface .health-row').first().waitFor({ timeout: 10_000 });
    await expect.poll(() => rows(page), { timeout: 1_500 }).toEqual(['', '', '', '']);
  }, 120_000);

  it('server errors and a reload mid-outage: no raw error and no claim of health; quiet again when it answers', async () => {
    const page = await newWindow();
    await load(page);
    const before = await failures(page);
    await setEngine(page, 'server error');
    await unconfirmedAfter(page, await failedRead(page, before));
    await page.reload();
    await load(page, false);
    await expect.poll(() => failures(page), { timeout: 15_000 }).toBeGreaterThan(0);
    await page.waitForTimeout(1_000);
    for (const id of ROWS) expect(await row(page, id), id).toMatch(/unconfirmed/i);
    expect(await headline(page)).not.toContain('Everything is working');
    expect(await healthText(page)).not.toMatch(/Failed to fetch|Internal Server Error|\(500\)/);
    await resumes(page);
  }, 120_000);

  it('a second window: both say the rows are unconfirmed within one failed read of their own', async () => {
    const first = await newWindow();
    await load(first);
    const second = await newWindow();
    await load(second);
    const [a, b] = [await failures(first), await failures(second)];
    await setEngine(first, 'refused');
    const [failedFirst, failedSecond] = await Promise.all([failedRead(first, a), failedRead(second, b)]);
    await unconfirmedAfter(first, failedFirst);
    await unconfirmedAfter(second, failedSecond);
    await resumes(first);
    await expect.poll(() => rows(second), { timeout: 15_000 }).toEqual(['', '', '', '']);
  }, 120_000);

  it('forced colours: the outage is said in words that stay visible', async () => {
    const page = await newWindow('active');
    await load(page);
    expect(await page.evaluate(() => matchMedia('(forced-colors: active)').matches)).toBe(true);
    const before = await failures(page);
    await setEngine(page, 'refused');
    await unconfirmedAfter(page, await failedRead(page, before));
    const shown = await page.locator('jf-health-surface').evaluate(host => {
      const root = host.shadowRoot!;
      return [root.querySelector('.header [role=status]'), ...root.querySelectorAll('[data-health-row] dd p')].map(node => {
        const box = node!.getBoundingClientRect();
        const style = getComputedStyle(node!);
        return { text: node!.textContent?.trim() ?? '', visible: box.width > 0 && box.height > 0 && style.visibility === 'visible' && style.color !== style.backgroundColor };
      });
    });
    expect(shown.length, 'the headline and the unconfirmed rows').toBeGreaterThanOrEqual(5);
    for (const item of shown) expect(item.visible, `"${item.text}" visible`).toBe(true);
  }, 120_000);
});
