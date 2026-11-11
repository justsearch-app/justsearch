// SPDX-License-Identifier: Apache-2.0
// S6 piece 8 SH3 SH14 — Health inside the real Shell, status store, engine-loss treatment and System
// hub in real Chromium over a private Vite server with a stubbed backend and the page's own clock.
// SH3: every age in the expanded Diagnostics is dated by its own source while status stops and
// inference keeps answering. SH14: AI Brain's engine-history intent, arriving during an engine loss,
// keeps its destination through recovery. Run separately with
// npx vitest run --config vitest.config.lockdown.ts src/shell-v0/views/HealthSurface.freshness-lockdown.test.ts
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
// statusDown refuses only the status read; engineDown refuses every read.
window.statusDown = false;
window.engineDown = false;
window.inferenceReads = 0;
window.fetch = async (input) => {
  const path = new URL(String(input instanceof Request ? input.url : input), location.href).pathname;
  if (path.includes('/api/registry/surfaces')) return json({ entries: [] });
  if (window.engineDown) throw new TypeError('Failed to fetch');
  if (path.endsWith('/api/status')) {
    if (window.statusDown) throw new TypeError('Failed to fetch');
    return json({ ...live, meta: { workerRpcAtMs: Date.now(), workerRpcStale: false } });
  }
  if (path.endsWith('/api/inference/status')) { window.inferenceReads++; return json({ mode: 'online', starting: false, available: true, generation: 2 }); }
  if (path.endsWith('/api/ai/install/status')) return json({ state: 'idle', installedFully: true });
  return json({ entries: [], jobs: [], runs: [], items: [] });
};
window.EventSource = class { constructor() { this.readyState = 0; } addEventListener() {} removeEventListener() {} close() {} };
const { __seedForTest } = await import('/src/api/registry/SurfaceCatalogClient.ts');
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/chrome/Shell.ts');
await import('/src/shell-v0/views/SystemSurface.ts');
await import('/src/shell-v0/views/HealthSurface.ts');
window.memberTab = await import('/src/shell-v0/router/memberTabIntent.ts');
window.ai = await import('/src/shell-v0/state/aiStateStore.ts');
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL', presentation: { labelKey: id, descriptionKey: id },
  consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] }, provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('core.system-surface', 'jf-system-surface', { members: ['core.health-surface'] }),
  surface('core.health-surface', 'jf-health-surface', { placement: 'DEEPLINK' }),
] });
document.body.append(document.createElement('jf-shell'));
document.body.dataset.ready = 'true';
</script></body></html>`;

type FreshnessWindow = Window & { statusDown: boolean; engineDown: boolean; inferenceReads: number;
  memberTab: { requestMemberTab(host: string, member: string): void }; ai: { __feedContactForTest(): void } };
let server: ViteDevServer;
let browser: Browser;
let browserServer: BrowserServer;
let context: BrowserContext | undefined;
let output: string;
let url: string;

beforeAll(async () => {
  output = await mkdtemp(path.join(process.env.TMPDIR ?? tmpdir(), 'health-freshness-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: path.join(output, 'vite'), publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'health-freshness', configureServer(vite) {
      vite.middlewares.use((req, res, next) => { if (req.url?.split('?')[0] !== '/app') return next();
        res.setHeader('Content-Type', 'text/html'); res.end(HARNESS); });
    } }] });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/app`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: process.env.TMPDIR ?? output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned Health-freshness Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
}, 60_000);
afterEach(async () => { await context?.close(); context = undefined; });
afterAll(async () => { await browser?.close(); await browserServer?.close(); await server?.close(); if (output) await rm(output, { recursive: true, force: true }); });

const win = (page: Page) => page.evaluateHandle(() => window as unknown as FreshnessWindow);

async function settle(page: Page): Promise<void> {
  await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
}

/** System › Health in the real Shell with status and inference observed, on the page's own clock. */
async function openHealth(): Promise<Page> {
  context = await browser.newContext({ viewport: { width: 1280, height: 800 }, reducedMotion: 'reduce' });
  const page = await context.newPage();
  await page.clock.install();
  page.on('pageerror', error => console.info('health-freshness pageerror:', error.message));
  await page.goto(`${url}#justsearch://surface/core.health-surface`);
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 60_000 });
  await page.locator('jf-health-surface .health-row').first().waitFor({ timeout: 60_000 });
  await page.clock.runFor(6_000);
  await settle(page);
  expect(await (await win(page)).evaluate(w => w.inferenceReads), 'inference answered').toBeGreaterThan(0);
  return page;
}

/** Every key and value of a Diagnostics card, by its heading. */
const card = (page: Page, heading: string) => page.locator('jf-health-surface').evaluate((host, name) => {
  const found = [...host.shadowRoot!.querySelectorAll<HTMLElement>('details .card.section')]
    .find(node => node.querySelector('h3')?.textContent?.trim().startsWith(name));
  return { text: found?.textContent?.replace(/\s+/g, ' ').trim() ?? '',
    rows: Object.fromEntries([...found?.querySelectorAll('.data-row') ?? []].map(row =>
      [row.querySelector('.key')?.textContent?.trim() ?? '', row.querySelector('.val')?.textContent?.replace(/\s+/g, ' ').trim() ?? ''])) };
}, heading);

/** The deepest focused element: its id, tag and accessible label. */
const focused = (page: Page) => page.evaluate(() => {
  let el = document.activeElement;
  while (el?.shadowRoot?.activeElement) el = el.shadowRoot.activeElement;
  return { id: el?.id ?? '', tag: el?.localName ?? '', label: el?.getAttribute('aria-label') ?? '' };
});

const diagnosticsOpen = (page: Page) => page.locator('jf-health-surface').evaluate(host =>
  (host.shadowRoot!.querySelector('details') as HTMLDetailsElement | null)?.open ?? null);

/** Refuse every read until the shell enters engine loss (Health open: the treatment is a band). */
async function loseEngine(page: Page): Promise<void> {
  await (await win(page)).evaluate(w => { w.engineDown = true; });
  await page.clock.runFor(60_000);
  await settle(page);
  await page.locator('[data-testid="health-last-known"]').waitFor({ state: 'visible', timeout: 10_000 });
  expect(await page.locator('jf-engine-loss').getAttribute('mode'), 'engine loss, Health open').toBe('band');
}

async function restore(page: Page): Promise<void> {
  await (await win(page)).evaluate(w => { w.engineDown = false; w.ai.__feedContactForTest(); });
  await page.clock.runFor(2_000);
  await settle(page);
  await page.locator('jf-health-surface .health-row').first().waitFor({ timeout: 10_000 });
}

describe('SH3 every Diagnostics age is dated by its own source', () => {
  it('status stops for 45 s while inference answers: the status age reads its own receipt, contact and inference stay just now', async () => {
    const page = await openHealth();
    await (await win(page)).evaluate(w => { w.statusDown = true; });
    const before = await (await win(page)).evaluate(w => w.inferenceReads);
    await page.clock.runFor(45_000);
    await settle(page);
    expect(await (await win(page)).evaluate(w => w.inferenceReads), 'inference kept answering').toBeGreaterThan(before);
    await page.locator('jf-health-surface details > summary').click();
    await settle(page);
    const connection = await card(page, 'Connection');
    // The status body arrived about 48 s ago (its last answer before the 45 s without status).
    const data = Object.entries(connection.rows).filter(([key]) => /updated/i.test(key));
    expect(data.length, `a data-age readout in ${JSON.stringify(connection.rows)}`).toBe(1);
    expect(data[0]![1], 'status data age').toMatch(/^(4\d|5\d)s ago\b/);
    expect(data[0]![1], 'the worker sample in that status').toMatch(/worker sample (4\d|5\d)s ago/);
    // Contact is any answer: inference answered seconds ago. It is its own row, not the data age.
    const contact = Object.entries(connection.rows).filter(([key]) => /contact|reachable/i.test(key));
    expect(contact.map(([, value]) => value), 'contact age').toEqual(['just now']);
    // Uptime was reported in that status, and says when.
    expect(connection.rows['Uptime'], 'uptime context').toMatch(/ as of (4\d|5\d)s ago$/);
    // No stream frame arrived in this harness.
    expect(connection.rows['Connections'], 'stream frame age').toMatch(/last frame —$/);
    const engine = await card(page, 'AI Engine');
    expect(engine.text, 'inference age, from inference').toMatch(/AI state updated (just now|\ds ago)/);
    expect(engine.text).not.toMatch(/\bOnline\b/);
    const currency = await page.locator('jf-health-surface .diagnostic-currency').textContent();
    expect(currency, 'the currency note dates the status').toMatch(/status received (4\d|5\d)s ago/);
  }, 120_000);
});

describe('SH14 a section intent keeps its destination across engine loss and recovery', () => {
  it('AI Brain\'s engine-history intent during the loss: after recovery Diagnostics is open and focus stays on the AI Engine section', async () => {
    const page = await openHealth();
    expect(await diagnosticsOpen(page), 'Diagnostics starts collapsed').toBe(false);
    await loseEngine(page);
    await (await win(page)).evaluate(w => w.memberTab.requestMemberTab('core.health-surface', 'ai-engine'));
    await settle(page);
    await restore(page);
    expect(await diagnosticsOpen(page), 'Diagnostics expanded').toBe(true);
    expect(await focused(page), 'focus on the requested section').toMatchObject({ id: 'ai-engine' });
    await page.clock.runFor(5_000);
    await settle(page);
    expect(await focused(page), 'focus stays on the requested section').toMatchObject({ id: 'ai-engine' });
  }, 120_000);

  it('with no intent pending, recovery still moves focus from the treatment to the main content', async () => {
    const page = await openHealth();
    await loseEngine(page);
    expect(await focused(page), 'focus in the treatment').toMatchObject({ tag: 'section' });
    await restore(page);
    expect(await focused(page), 'focus on the window area').toMatchObject({ tag: 'div', label: 'Main content' });
    expect(await diagnosticsOpen(page), 'Diagnostics stays collapsed').toBe(false);
  }, 120_000);
});
