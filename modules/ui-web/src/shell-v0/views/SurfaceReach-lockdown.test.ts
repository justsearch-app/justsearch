// SPDX-License-Identifier: Apache-2.0
// Follow-up C1 FC5 — Health and Logs (System) and Library › Browse inside the real Shell, router and tab
// hosts, in real Chromium over a private Vite server and a stubbed backend. At 600x400 with 200% text
// their headers must not fill the tab panel: the wheel and the keyboard reach the end of the content.
// At 1280x720 the header stays fixed above a body that scrolls on its own.
// npx vitest run --config vitest.config.lockdown.ts src/shell-v0/views/SurfaceReach-lockdown.test.ts
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
// Enough folders and failed files that each body is longer than the window.
const roots = Array.from({ length: 40 }, (_, i) => ({ path: '/home/user/folder-' + i, fileCount: i, status: 'indexed' }));
window.fetch = async (input) => {
  const path = new URL(String(input instanceof Request ? input.url : input), location.href).pathname;
  if (path.endsWith('/api/status')) return json(live);
  if (path.endsWith('/api/inference')) return json({ mode: 'online', starting: false, available: true, generation: 2 });
  if (path.endsWith('/api/indexing/failed-jobs')) return json({ jobs: Array.from({ length: 10 }, (_, i) => ({ path: '/home/user/file-' + i + '.pdf', errorMessage: 'ExtractionFailed', attempts: 3, lastUpdatedMs: Date.now() })) });
  if (path.endsWith('/api/indexing/roots')) return json({ roots, items: roots });
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
await import('/src/shell-v0/views/LogSurface.ts');
await import('/src/shell-v0/views/LibrarySurface.ts');
await import('/src/shell-v0/views/BrowseSurface.ts');
for (const name of ['search', 'brain', 'activity']) customElements.define('jf-probe-' + name, class extends HTMLElement {
  connectedCallback() { if (!this.shadowRoot) this.attachShadow({ mode: 'open' }).innerHTML = '<p>' + name + '</p>'; }
});
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL', presentation: { labelKey: id, descriptionKey: id },
  consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] }, provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('core.search-v3-surface', 'jf-probe-search'),
  surface('core.library-surface', 'jf-library-surface', { members: ['core.browse-surface'] }),
  surface('core.browse-surface', 'jf-browse-surface', { placement: 'DEEPLINK' }),
  surface('core.brain-surface', 'jf-probe-brain'),
  surface('core.system-surface', 'jf-system-surface', { members: ['core.health-surface', 'core.activity-surface', 'core.logs-surface'] }),
  surface('core.health-surface', 'jf-health-surface', { placement: 'DEEPLINK' }),
  surface('core.activity-surface', 'jf-probe-activity', { placement: 'DEEPLINK' }),
  surface('core.logs-surface', 'jf-log-surface', { placement: 'DEEPLINK' }),
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
  output = await mkdtemp(path.join(process.env.TMPDIR ?? tmpdir(), 'surface-reach-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: path.join(output, 'vite'), publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'surface-reach', configureServer(vite) {
      vite.middlewares.use((req, res, next) => { if (req.url?.split('?')[0] !== '/reach') return next();
        res.setHeader('Content-Type', 'text/html'); res.end(HARNESS); });
    } }] });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/reach`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: process.env.TMPDIR ?? output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned surface-reach Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
}, 60_000);
afterEach(async () => { await context?.close(); context = undefined; });
afterAll(async () => { await browser?.close(); await browserServer?.close(); await server?.close(); if (output) await rm(output, { recursive: true, force: true }); });

async function settle(page: Page, ms = 150): Promise<void> {
  await page.waitForTimeout(ms);
  await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
}

/** Each surface: its address, its element, and what its body holds once loaded. */
const SURFACES = {
  Health: { address: 'core.health-surface', tag: 'jf-health-surface', ready: '.health-row' },
  Logs: { address: 'core.logs-surface', tag: 'jf-log-surface', ready: '.body' },
  'Library › Browse': { address: 'core.browse-surface', tag: 'jf-browse-surface', ready: '.body [role=treeitem], .body .tree-row, .body > *' },
} as const;
type Name = keyof typeof SURFACES;

async function open(name: Name, width: number, height: number, textScale: number): Promise<Page> {
  context = await browser.newContext({ viewport: { width, height }, reducedMotion: 'reduce' });
  const page = await context.newPage();
  page.on('pageerror', error => console.info('surface-reach pageerror:', error.message));
  await page.goto(`${url}#justsearch://surface/${SURFACES[name].address}`, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await page.evaluate(scale => { document.documentElement.style.fontSize = `${scale * 100}%`; }, textScale);
  await page.locator(`${SURFACES[name].tag} ${SURFACES[name].ready}`).first().waitFor({ state: 'attached', timeout: 45_000 });
  if (name === 'Health') {
    // Diagnostics open: the longest Health there is.
    const summary = page.locator('jf-health-surface details > summary', { hasText: /^Diagnostics$/ });
    if (!await summary.evaluate(node => (node.parentElement as HTMLDetailsElement).open)) await summary.evaluate(node => (node as HTMLElement).click());
  }
  await settle(page, 400);
  return page;
}

/**
 * Geometry of the surface: the window it shows in (its own box clipped to the viewport), its header,
 * its body, and the last thing the body holds. Scroll offsets of the surface and of its body.
 */
const geometry = (page: Page, tag: string) => page.locator(tag).evaluate(host => {
  const root = host.shadowRoot!;
  const body = root.querySelector<HTMLElement>('.body')!;
  const header = root.querySelector<HTMLElement>('.header')!;
  // The last laid-out element the body holds, in document order.
  const last = [...body.querySelectorAll('*')].reverse().find(node => node.getBoundingClientRect().height > 0) ?? body;
  const r = (node: Element) => { const b = node.getBoundingClientRect(); return { top: Math.round(b.top), bottom: Math.round(b.bottom), height: Math.round(b.height) }; };
  const frame = r(host);
  const visible = { top: Math.max(frame.top, 0), bottom: Math.min(frame.bottom, innerHeight) };
  return { visible, header: r(header), body: r(body), last: r(last), lastText: last.textContent?.trim().slice(0, 40) ?? '',
    hostScroll: host.scrollTop, bodyScroll: body.scrollTop };
});

/** The end of the content is on screen: the bottom of the body's last element is inside the surface's visible window. */
function atEnd(g: Awaited<ReturnType<typeof geometry>>): boolean {
  return g.last.height > 0 && g.last.bottom > g.visible.top && g.last.bottom <= g.visible.bottom + 1;
}

describe('FC5 a short window with large text keeps every surface reachable', () => {
  it.each(Object.keys(SURFACES) as Name[])('%s at 600x400 with 200% text: the wheel and End reach the end of the content', async name => {
    const page = await open(name, 600, 400, 2);
    const tag = SURFACES[name].tag;
    const start = await geometry(page, tag);
    expect(start.header.height, `${name} header within the window ${JSON.stringify(start)}`).toBeGreaterThan(0);

    // The wheel, over the surface.
    const box = (await page.locator(tag).boundingBox())!;
    await page.mouse.move(box.x + box.width / 2, Math.max(box.y, 0) + 10);
    for (let i = 0; i < 40 && !atEnd(await geometry(page, tag)); i++) { await page.mouse.wheel(0, 300); await settle(page, 50); }
    const wheeled = await geometry(page, tag);
    expect(atEnd(wheeled), `${name}: the wheel reaches "${wheeled.lastText}" ${JSON.stringify(wheeled)}`).toBe(true);

    // The keyboard: back to the top, then End from inside the surface.
    await page.locator(tag).evaluate(host => { host.scrollTop = 0; const body = host.shadowRoot!.querySelector<HTMLElement>('.body')!; body.scrollTop = 0; });
    await settle(page);
    const top = await geometry(page, tag);
    await page.mouse.click(box.x + box.width - 4, Math.max(top.header.top, top.visible.top) + 4);
    await page.keyboard.press('End');
    await settle(page, 300);
    const ended = await geometry(page, tag);
    expect(atEnd(ended), `${name}: End reaches "${ended.lastText}" ${JSON.stringify(ended)}`).toBe(true);
  }, 120_000);

  it.each(Object.keys(SURFACES) as Name[])('%s at 1280x720: the header stays fixed while the body scrolls on its own', async name => {
    const page = await open(name, 1280, 720, 1);
    const tag = SURFACES[name].tag;
    const start = await geometry(page, tag);
    const box = (await page.locator(tag).boundingBox())!;
    await page.mouse.move(box.x + box.width / 2, start.body.top + 40);
    for (let i = 0; i < 10; i++) { await page.mouse.wheel(0, 300); await settle(page, 50); }
    const after = await geometry(page, tag);
    expect(after.hostScroll, `${name} itself does not scroll`).toBe(0);
    expect(after.header.top, `${name} header stays in place`).toBe(start.header.top);
    expect(after.body.top, `${name} body stays below the header`).toBe(start.body.top);
  }, 120_000);
});
