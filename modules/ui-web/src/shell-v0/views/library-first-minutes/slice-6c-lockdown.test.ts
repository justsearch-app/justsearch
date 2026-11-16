// SPDX-License-Identifier: Apache-2.0
// Slice 6c in a real browser: "Read waiting files now" under "When to read files" — placement,
// keyboard, the polite announcement, focus, light and dark, and a 600 px wide window.
import { afterAll, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

let server: ViteDevServer, browser: Browser, scratch: string, origin: string;
/** Library outside reading hours with three files waiting; the read-now request is held until released. */
const harness = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
window.fetch = async () => new Response('{}', { status: 503, headers: { 'Content-Type': 'application/json' } });
const { createMockHostApi } = await import('/src/shell-v0/plugin-api/testHostApi.ts');
const { getOperationClient } = await import('/src/shell-v0/operations/OperationClient.ts');
const ai = await import('/src/shell-v0/state/aiStateStore.ts');
await import('/src/shell-v0/views/LibrarySurface.ts');
const params = new URLSearchParams(location.search);
if (params.get('theme')) document.documentElement.dataset.theme = params.get('theme');
window.status6c = ({ active = false, waiting = true, reasons = ['schedule'] } = {}) => ai.__feedForTest({ status: {
  operating: { indexing: { state: active ? 'RUNNING' : 'PAUSED', reasons, reasonCode: null, since: '2026-11-16T00:00:00Z',
    affects: ['INDEXING', 'VDU'], pendingJobs: 3, agingReleaseAt: null, sampledAt: '2026-11-16T00:00:00Z', untilEpochMs: null,
    waitingForResume: false, readingRuleReasons: active ? [] : reasons.filter(r => r === 'schedule' || r === 'on_battery'),
    heldFilesWaiting: !active && waiting, release: { active } } },
  worker: { core: { indexedDocuments: 8, ownSearchableDocuments: 8, indexState: 'IDLE', indexHealthy: true, pendingJobs: 3 },
    migration: { migrationState: 'IDLE', pendingReadyJobsCount: 3 } },
  readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } });
window.status6c();
window.fixture = { invocations: [], release: () => {} };
getOperationClient('').invokeWithConsent = (id, request) => {
  window.fixture.invocations.push({ id, args: request.args });
  return new Promise(resolve => { window.fixture.release = () => resolve({ success: true, message: 'started',
    structuredData: { outcome: 'started' } }); });
};
const host = createMockHostApi({ data: { fetch: async url => url.startsWith('/api/indexing-roots/substrate')
  ? Response.json({ items: [{ pathHash: 'lockdown6c', status: 'indexed', fileCount: 8, fileCountCapped: false, failedCount: 0, inFlightCount: 3, walkCompleted: true }] })
  : new Response('{}', { status: 503 }) } });
const el = document.createElement('jf-library-surface');
el.host_ = host; el.resolvedPaths = { lockdown6c: '/Documents' };
document.body.append(el);
window.editor = () => el.shadowRoot.querySelector('jf-indexing-rules-editor');
window.settle = async () => { for (let i = 0; i < 35; i++) { await new Promise(resolve => setTimeout(resolve)); await el.updateComplete; await window.editor()?.updateComplete; } };
window.focused = () => { let active = document.activeElement; while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
  return active === document.body || !active ? 'body' : active.id || (active.textContent || '').trim(); };
</script></body></html>`;
beforeAll(async () => {
  scratch = await mkdtemp(path.join(tmpdir(), 'slice-6c-browser-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: scratch,
    server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'slice-6c-fixture', configureServer(vite) {
      vite.middlewares.use((req, res, next) => {
        if (!req.url?.startsWith('/slice-6c.html')) return next();
        res.setHeader('Content-Type', 'text/html'); res.end(harness);
      });
    } }],
  });
  await server.listen();
  expect(server.resolvedUrls).not.toBeNull(); expect(server.resolvedUrls!.local[0]).toBeDefined();
  origin = server.resolvedUrls!.local[0]!;
  browser = await chromium.launch({ headless: true, args: ['--disable-gpu'] });
}, 30_000);
afterAll(async () => { await browser?.close(); await server?.close(); if (scratch) await rm(scratch, { recursive: true, force: true }); });

type FixtureWindow = Window & { settle(): Promise<void>; focused(): string; status6c(hold?: object): void;
  fixture: { invocations: { id: string; args: object }[]; release(): void } };
async function settle(page: Page) { await page.evaluate(() => (window as unknown as FixtureWindow).settle()); }
const focused = (page: Page) => page.evaluate(() => (window as unknown as FixtureWindow).focused());
async function open(width = 1024, theme = 'dark'): Promise<Page> {
  const page = await browser.newPage({ viewport: { width, height: 800 }, colorScheme: theme === 'light' ? 'light' : 'dark' });
  page.setDefaultTimeout(3000);
  const errors: Error[] = [];
  page.on('pageerror', error => { errors.push(error); });
  await page.goto(origin + 'slice-6c.html?theme=' + theme, { timeout: 45_000 });
  await page.waitForFunction(() => typeof Reflect.get(window, 'settle') === 'function');
  await settle(page);
  expect(errors).toEqual([]);
  return page;
}
const action = (page: Page) => page.getByRole('button', { name: 'Read waiting files now', exact: true });

it('keyboard: Enter reads the waiting files once, the line is announced politely and keeps focus until the release ends', async () => {
  const page = await open();
  try {
    expect(await action(page).count()).toBe(1);
    const group = page.getByRole('region', { name: 'When to read files' });
    expect(await group.getByRole('button', { name: 'Read waiting files now', exact: true }).count(), 'under "When to read files"').toBe(1);
    await action(page).focus();
    await page.keyboard.press('Enter'); await settle(page);
    await page.keyboard.press('Enter'); await settle(page);
    const fixture = await page.evaluate(() => (window as unknown as FixtureWindow).fixture.invocations);
    expect(fixture).toEqual([{ id: 'core.indexing-read-now', args: {} }]);
    await page.evaluate(() => (window as unknown as FixtureWindow).fixture.release()); await settle(page);
    const status = page.getByRole('status').filter({ hasText: 'Reading the waiting files. Your reading rules are unchanged.' });
    expect(await status.count()).toBe(1);
    expect(await status.getAttribute('id')).toBe('read-now-status');
    expect(await action(page).count()).toBe(0);
    expect(await focused(page)).toBe('read-now-status');
    await page.evaluate(() => (window as unknown as FixtureWindow).status6c({ active: true })); await settle(page);
    expect(await status.count()).toBe(1);
    await page.evaluate(() => (window as unknown as FixtureWindow).status6c({ waiting: false })); await settle(page);
    expect(await status.count()).toBe(0);
    expect(await action(page).count()).toBe(0);
  } finally { await page.close(); }
}, 60_000);

it('Tab reaches the action after the reading-rule choices; it hides when files stop waiting and focus goes to the heading', async () => {
  const page = await open();
  try {
    expect(await action(page).count()).toBe(1);
    const battery = page.getByRole('checkbox', { name: 'Pause while on battery' });
    expect(await battery.isDisabled()).toBe(true);
    await action(page).focus();
    await page.keyboard.press('Shift+Tab');
    expect(await focused(page), 'the previous stop is in the rules group').not.toBe('Read waiting files now');
    await page.keyboard.press('Tab');
    expect(await focused(page)).toBe('Read waiting files now');
    await page.evaluate(() => (window as unknown as FixtureWindow).status6c({ reasons: ['user_paused', 'schedule'] })); await settle(page);
    expect(await action(page).count()).toBe(0);
    expect(await focused(page)).toBe('when-to-index');
  } finally { await page.close(); }
}, 60_000);

for (const [width, theme] of [[600, 'dark'], [600, 'light'], [1024, 'light']] as const) {
  it(`${width} px, ${theme}: the action is visible, readable and inside the page`, async () => {
    const page = await open(width, theme);
    try {
      expect(await action(page).count()).toBe(1);
      const box = await action(page).boundingBox();
      expect(box).not.toBeNull();
      expect(box!.x).toBeGreaterThanOrEqual(0);
      expect(box!.x + box!.width).toBeLessThanOrEqual(width);
      expect(await action(page).isVisible()).toBe(true);
      const colors = await action(page).evaluate(node => {
        const style = getComputedStyle(node);
        let back = 'rgba(0, 0, 0, 0)';
        for (let at: Element | null = node; at && back === 'rgba(0, 0, 0, 0)'; ) {
          back = getComputedStyle(at).backgroundColor;
          at = at.parentElement ?? ((at.getRootNode() as ShadowRoot).host ?? null);
        }
        return { color: style.color, back };
      });
      expect(colors.color).not.toBe(colors.back);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    } finally { await page.close(); }
  }, 60_000);
}
