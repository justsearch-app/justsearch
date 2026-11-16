// SPDX-License-Identifier: Apache-2.0
// Slice 8 (fm-15, S8-8', S8-9', H5', H4') — the status panel in real headless Chromium: focus recovery
// when a focused action leaves ("Open AI Brain" on arriving at AI Brain), Pause → Resume keeping focus,
// decision controls re-arming when rows move, developer tools on and off, and the picture's panel at
// 600 px with 200% text in light and dark, and in forced colours. The page renders the real panel over
// models the real frame-status derivation builds; no Engine, shared stack, network or GPU.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import path from 'node:path';

let server: ViteDevServer, browserServer: BrowserServer, browser: Browser, context: BrowserContext | undefined;
let output: string, url: string;
type Show = { model: string; area?: string; developer?: boolean };
declare global { interface Window { show(props: Show): Promise<void>; set(props: Partial<Show> & { noticeGone?: boolean }): Promise<void>; actions: Array<{ kind: string; target: string }> } }

beforeAll(async () => {
  const root = path.resolve('../..', 'tmp'); await mkdir(root, { recursive: true });
  output = await mkdtemp(path.join(root, 'status-panel-first-minutes-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: output, publicDir: false,
    logLevel: 'silent', server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'isolated-status-panel',
      configureServer(vite) { vite.middlewares.use('/status-panel', (_req, res) => {
        res.setHeader('Content-Type', 'text/html');
        res.end(`<!doctype html><html><head><meta charset="utf-8">
          <link rel="stylesheet" href="/src/styles/tokens.css">
          <link rel="stylesheet" href="/src/shell-v0/themes/default.css">
          <style>html, body { margin: 0; background: var(--surface-0, Canvas); color: var(--text-primary); font-family: var(--font-native); }
            #host { position: relative; padding: 8px; --frame-status-panel-top: 48px; }</style>
          </head><body><div id="host"></div><script type="module">
          import '/src/shell-v0/components/StatusPanel.ts';
          import { deriveFrameStatus } from '/src/shell-v0/state/frameStatus.ts';
          const now = Date.parse('2026-11-16T10:00:00Z'), MIN = 60000, GIB = 1024 ** 3;
          const install = (paused) => ({ state: 'running', paused, phase: 'download', totalBytes: 2.6 * GIB, downloadedBytes: 1.3 * GIB, remainingSeconds: 1440 });
          const ai = (installStatus) => ({ phase: 'connected', connection: { reachable: true, lastSuccessMs: now, lastContactMs: now, consecutiveFailures: 0 },
            verdict: installStatus ? { kind: 'degraded', severity: 'warn', reasons: ['index.dense_unavailable', 'lambdamart.not_configured',
              'inference.deactivated', 'encoders.health.embedding_not_ready'] } : { kind: 'operational', severity: 'ok', reasons: [] }, stability: { kind: 'settled' },
            aiEngine: { kind: installStatus ? 'installing' : 'online', stability: { kind: 'settled' }, installFailure: null },
            activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
            status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {} } },
            installStatus, installObservationLive: !!installStatus, indexingStatusLive: true, snapshotLive: true, episodeMaxPendingJobs: 0, enrichSettleSamples: [] });
          const READY = { pathHash: 'h-docs', collection: 'default', status: 'indexed', lastIndexedIsoTime: '2026-11-16T09:00:00Z', inFlightCount: 0, failedCount: 0, walkCompleted: true, fileCount: 8 };
          const READING = { ...READY, lastIndexedIsoTime: undefined, status: 'scanning', inFlightCount: 5 };
          const unread = { key: 'u1', origin: 'stream', acknowledged: false, sourceRenderHint: 'PERSISTED', event: { id: 'u1', classId: 'health.recoverable',
            occurredAt: '2026-11-16T09:30:00Z', renderHint: 'PERSISTED', classExtras: { conditionId: 'folder.unavailable', subject: 'Notes' },
            diagnosticsLink: null, provenance: null, primaryAction: null, bodyI18nKey: null } };
          const request = { pendingId: 'p1', operationId: 'core.reindex', argsSummary: '/home/me/Documents', riskTier: 'MEDIUM', arrival: 'needs-you',
            state: { busy: false, checking: false, readyAt: 0, durable: false } };
          function pictured({ paused = false, installing = true, developer = false, requests = [] } = {}) {
            const base = { ai: ai(installing ? install(paused) : null), contactLostAt: null, developer, advisories: [unread], authorizations: requests,
              gettingStarted: { visible: false, known: true, currentStep: 2, searchable: 8 }, rootPaths: new Map([['h-docs', '/home/me/Documents']]), rootsLive: true };
            const reading = deriveFrameStatus({ ...base, now: now - 13 * MIN, roots: [READING] });
            const ready = deriveFrameStatus({ ...base, now: now - 12 * MIN, roots: [READY], folders: reading.folders });
            return deriveFrameStatus({ ...base, now, roots: [READY], folders: ready.folders });
          }
          const MODELS = { pictured: () => pictured(), paused: () => pictured({ paused: true }), done: () => pictured({ installing: false }),
            developer: () => pictured({ developer: true }), approval: () => pictured({ requests: [request] }) };
          let panel = null;
          window.actions = [];
          window.show = async ({ model, area = 'core.search-v3-surface', developer = false }) => {
            panel?.remove();
            panel = document.createElement('jf-status-panel');
            panel.model = MODELS[model](); panel.area = area; panel.developer = developer;
            panel.addEventListener('status-action', e => window.actions.push(e.detail));
            document.querySelector('#host').append(panel);
            await panel.updateComplete;
          };
          window.set = async ({ model, area, developer, noticeGone }) => {
            if (model) panel.model = MODELS[model]();
            if (area !== undefined) panel.area = area;
            if (developer !== undefined) panel.developer = developer;
            if (noticeGone !== undefined) panel.noticeGone = noticeGone;
            await panel.updateComplete; await new Promise(r => requestAnimationFrame(() => r()));
          };
          document.body.dataset.ready = 'true';
          </script></body></html>`);
      }); },
    }] });
  await server.listen(); const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/status-panel`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned headless Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
}, 60_000);
afterEach(async () => { await context?.close(); context = undefined; });
afterAll(async () => {
  await browser?.close(); await browserServer?.close(); await server?.close();
  if (output) await rm(output, { recursive: true, force: true });
}, 60_000);

async function open(props: Show, opts: { width?: number; theme?: 'light' | 'dark'; text?: string; forcedColors?: boolean } = {}): Promise<Page> {
  context = await browser.newContext({ viewport: { width: opts.width ?? 1024, height: 900 } });
  const page = await context.newPage(); page.setDefaultTimeout(10_000);
  page.on('pageerror', error => console.error('Status panel page error:', error.message));
  await page.route(new URL('/api/**', url).href, route => route.fulfill({ status: 503, body: '{}' }));
  if (opts.forcedColors) await page.emulateMedia({ forcedColors: 'active' });
  await page.goto(url, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ state: 'attached', timeout: 45_000 });
  await page.evaluate(({ theme, text }) => {
    if (theme) document.documentElement.dataset.theme = theme;
    if (text) document.documentElement.style.fontSize = text;
  }, { theme: opts.theme, text: opts.text });
  await page.evaluate(props => window.show(props), props);
  return page;
}
/** What has focus, through shadow roots: its tag and its words. */
async function focused(page: Page): Promise<string> {
  return page.evaluate(() => {
    let active: Element | null = document.activeElement;
    while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
    if (!active || active === document.body) return 'body';
    return `${active.localName}:${(active.getAttribute('aria-label') || active.textContent || '').replace(/\s+/g, ' ').trim()}`;
  });
}
const install = (page: Page) => page.locator('article[data-fact="ai-install"]');

it('a focused "Open AI Brain" that leaves on arriving at AI Brain hands focus to its row\'s next control, not the page', async () => {
  const page = await open({ model: 'pictured' });
  await install(page).getByRole('button', { name: 'Open AI Brain' }).focus();
  expect(await focused(page)).toBe('button:Open AI Brain');
  await page.evaluate(() => window.set({ area: 'core.brain-surface' }));
  await expect.poll(() => install(page).getByRole('button').allTextContents()).toEqual(['Pause']);
  expect(await focused(page)).toBe('button:Pause');
}, 60_000);

it('preserved: a focused row that leaves hands focus to the panel', async () => {
  const page = await open({ model: 'pictured' });
  await install(page).getByRole('button', { name: /^Pause/ }).focus();
  await page.evaluate(() => window.set({ model: 'done' }));
  await expect.poll(() => install(page).count()).toBe(0);
  expect(await focused(page)).toBe("div:What's going on");
}, 60_000);

it('Pause, then the status says paused: the same control reads Resume and keeps focus', async () => {
  const page = await open({ model: 'pictured', area: 'core.brain-surface' });
  const pause = install(page).getByRole('button', { name: 'Pause' });
  await pause.focus(); await page.keyboard.press('Enter');
  expect(await page.evaluate(() => window.actions)).toContainEqual({ kind: 'install-toggle', target: 'pause', label: 'Pause' });
  await page.evaluate(() => window.set({ model: 'paused' }));
  await expect.poll(() => install(page).getByRole('button').allTextContents()).toEqual(['Resume']);
  expect(await focused(page)).toBe('button:Resume');
  expect((await install(page).locator('.text').textContent())!.trim()).toBe('Installing AI · Paused');
}, 60_000);

it('preserved: an answer control that moved re-arms before a click counts', async () => {
  const page = await open({ model: 'approval' });
  const allow = page.locator('article[data-fact="authorization:p1"]').getByRole('button', { name: 'Allow' });
  await page.waitForTimeout(1_200);
  await page.evaluate(() => window.set({ noticeGone: true }));
  await allow.click();
  expect(await page.evaluate(() => window.actions.filter(a => a.kind === 'authorization-allow'))).toEqual([]);
  await page.waitForTimeout(1_200);
  await allow.click();
  expect(await page.evaluate(() => window.actions.filter(a => a.kind === 'authorization-allow'))).toHaveLength(1);
}, 60_000);

it('developer tools on and off: the unread count, Mark all read and Earlier Details come and go', async () => {
  const page = await open({ model: 'pictured' });
  const earlier = page.locator('section[aria-label="Earlier"]');
  expect((await earlier.locator('h3').textContent())!.trim()).toBe('Earlier');
  expect(await earlier.getByRole('button', { name: /Mark all read|Details/ }).count()).toBe(0);
  await page.evaluate(() => window.set({ model: 'developer', developer: true }));
  await expect.poll(async () => (await earlier.locator('h3').textContent())!.replace(/\s+/g, ' ').trim()).toBe('Earlier 1 unread');
  expect(await earlier.getByRole('button', { name: 'Mark all read' }).count()).toBe(1);
  expect(await earlier.getByRole('button', { name: 'Details' }).count()).toBeGreaterThan(0);
  await page.evaluate(() => window.set({ model: 'pictured', developer: false }));
  await expect.poll(() => earlier.getByRole('button', { name: /Mark all read|Details/ }).count()).toBe(0);
}, 60_000);

it.each(['dark', 'light'] as const)('the picture\'s panel at 600 px with text at double size, %s theme: whole, readable, nothing clipped', async theme => {
  const page = await open({ model: 'pictured', area: 'core.brain-surface' }, { width: 600, theme, text: '200%' });
  const facts = await page.evaluate(() => {
    const panel = document.querySelector('jf-status-panel')!.shadowRoot!;
    const box = panel.querySelector('.panel')!;
    const rect = box.getBoundingClientRect();
    const clipped = [...panel.querySelectorAll('h2, h3, .text, .detail, time, button')].filter(el =>
      (el as HTMLElement).scrollWidth > (el as HTMLElement).clientWidth + 1).map(el => el.textContent!.trim());
    const style = getComputedStyle(box);
    return { left: rect.left, right: rect.right, overflowX: box.scrollWidth - box.clientWidth, clipped,
      fg: getComputedStyle(panel.querySelector('h2')!).color, bg: style.backgroundColor,
      words: (box.textContent ?? '').replace(/\s+/g, ' ') };
  });
  expect(facts.left).toBeGreaterThanOrEqual(0);
  expect(facts.right).toBeLessThanOrEqual(600);
  expect(facts.overflowX).toBeLessThanOrEqual(0);
  expect(facts.clipped).toEqual([]);
  expect(facts.fg).not.toBe(facts.bg);
  for (const words of ["What's going on", 'Search works', 'Running', 'Installing AI', '1.3 of 2.6 GB · about 24 min left', 'Pause',
    'Earlier', 'Your Documents folder is searchable', '12 min', 'All activity', 'Health']) expect(facts.words).toContain(words);
}, 60_000);

it('forced colours: the keyboard focus on Pause stays visible', async () => {
  const page = await open({ model: 'pictured', area: 'core.brain-surface' }, { forcedColors: true });
  await install(page).getByRole('button', { name: 'Pause' }).focus();
  await page.keyboard.press('Shift+Tab'); await page.keyboard.press('Tab');
  expect(await focused(page)).toBe('button:Pause');
  const outline = await install(page).getByRole('button', { name: 'Pause' }).evaluate(el => getComputedStyle(el).outlineStyle);
  expect(outline).toBe('solid');
}, 60_000);
