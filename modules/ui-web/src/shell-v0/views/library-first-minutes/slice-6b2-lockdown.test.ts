// SPDX-License-Identifier: Apache-2.0
// Slice 6b-2 in a real browser: skip rules save when the field is left, the removal ask is announced in
// place without taking focus, a save in flight never disables the field being typed in, reading hours
// save from the keyboard, and the ask stays usable in light and dark from 600 px to wide.
import { afterAll, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const ASK = 'Remove files already read that match your skip rules from search? Files on disk are not touched.';
let server: ViteDevServer, browser: Browser, scratch: string, origin: string;
/** Library over a stubbed settings owner (witness + keyed receipts); writes can be held until released. */
const harness = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
const s = { revision: 1, lastKey: '0194f72c-0000-7000-8000-000000000001', ui: { theme: 'dark', excludePatterns: ['*.log'],
  indexingPause: { paused: false, untilEpochMs: null, reason: null, pauseOnBattery: false } } };
window.fixture = { posts: [], ops: [], hold: null, release: () => {} };
const fx = window.fixture;
const witness = () => ({ acceptedRevision: s.revision, lastCommittedOperationKey: s.lastKey });
const held = () => fx.hold ? fx.hold : Promise.resolve();
window.fixture.holdWrites = () => { fx.hold = new Promise(resolve => { fx.release = () => { fx.hold = null; resolve(); }; }); };
window.fetch = async (url, init) => {
  const id = String(url).split('/').at(-2);
  const body = JSON.parse(String(init?.body ?? '{}'));
  fx.ops.push({ id, args: body.args });
  await held();
  if (id === 'core.set-indexing-rules') {
    const pause = { ...s.ui.indexingPause, pauseOnBattery: body.args.pauseOnBattery };
    if (body.args.schedule) pause.schedule = body.args.schedule; else delete pause.schedule;
    s.ui = { ...s.ui, indexingPause: pause };
  }
  s.revision++; s.lastKey = body.idempotencyKey;
  return Response.json({ success: true, message: 'Committed', structuredData: { witness: witness() } });
};
const { createMockHostApi } = await import('/src/shell-v0/plugin-api/testHostApi.ts');
const ai = await import('/src/shell-v0/state/aiStateStore.ts');
await import('/src/shell-v0/views/LibrarySurface.ts');
ai.__feedForTest({ status: { worker: { core: { indexedDocuments: 8, ownSearchableDocuments: 8, indexState: 'IDLE', indexHealthy: true },
  migration: { migrationState: 'IDLE' } }, readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } });
const host = createMockHostApi({ data: {
  invokeOperation: async () => { fx.ops.push({ id: 'invoke' }); return { success: true, structuredData: { dryRun: false, matchedFiles: 0,
    confirmedRemovals: 0, capped: false, perPattern: [], uncheckedScopes: [] } }; },
  fetch: async (url, init) => {
    if (url === '/api/indexing/excludes') return Response.json({ source: 'settings.json', sourceDetail: null, patterns: s.ui.excludePatterns });
    if (url.startsWith('/api/indexing/forgets?')) return Response.json({ items: [], complete: true, nextId: null });
    if (url !== '/api/settings/v2') return new Response('{}', { status: 503 });
    if (init?.method !== 'POST') return Response.json({ settingsMode: 'persistent', ui: structuredClone(s.ui), witness: witness() });
    const attempt = JSON.parse(init.body);
    fx.posts.push(attempt.ui.excludePatterns);
    await held();
    if (attempt.witness.acceptedRevision !== s.revision) return Response.json({ state: 'FAILED', errorCode: 'VERSION_CONFLICT' }, { status: 409 });
    s.ui = { ...s.ui, excludePatterns: attempt.ui.excludePatterns }; s.revision++; s.lastKey = attempt.operationKey;
    return Response.json({ state: 'COMPLETE', operationKey: attempt.operationKey, witness: witness(), ui: structuredClone(s.ui) });
  } } });
const el = document.createElement('jf-library-surface');
el.host_ = host; el.refresh = async () => true;
document.body.append(el);
window.settle = async () => { for (let i = 0; i < 35; i++) { await new Promise(resolve => setTimeout(resolve)); await el.updateComplete; } };
window.focused = () => { let active = document.activeElement; while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
  if (!active || active === document.body) return 'body';
  return active.getAttribute('aria-label') || (active.textContent || '').trim() || active.id || active.tagName; };
</script></body></html>`;
beforeAll(async () => {
  scratch = await mkdtemp(path.join(tmpdir(), 'slice-6b2-browser-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: scratch,
    server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'slice-6b2-fixture', configureServer(vite) {
      vite.middlewares.use((req, res, next) => {
        if (!req.url?.startsWith('/slice-6b2.html')) return next();
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

type Fixture = { posts: unknown[][]; ops: { id: string; args?: Record<string, unknown> }[]; holdWrites(): void; release(): void };
type FixtureWindow = Window & { settle(): Promise<void>; focused(): string; fixture: Fixture };
const settle = (page: Page) => page.evaluate(() => (window as unknown as FixtureWindow).settle());
const focused = (page: Page) => page.evaluate(() => (window as unknown as FixtureWindow).focused());
const fixture = (page: Page) => page.evaluate(() => {
  const f = (window as unknown as FixtureWindow).fixture; return { posts: f.posts, ops: f.ops.map(o => ({ id: o.id, args: o.args })) };
});
async function open(width = 1024, theme = 'light'): Promise<Page> {
  const page = await browser.newPage({ viewport: { width, height: 900 } });
  page.setDefaultTimeout(3000);
  const errors: Error[] = [];
  page.on('pageerror', error => { errors.push(error); });
  await page.goto(origin + 'slice-6b2.html', { timeout: 45_000 });
  await page.waitForFunction(() => typeof Reflect.get(window, 'settle') === 'function');
  await page.evaluate(value => { document.documentElement.dataset.theme = value; }, theme);
  await settle(page);
  expect(errors).toEqual([]);
  expect(await page.getByRole('textbox', { name: 'Skip rule 1', exact: true }).inputValue()).toBe('*.log');
  return page;
}
async function addRuleWithKeyboard(page: Page, value: string) {
  await page.getByRole('button', { name: 'Add a rule', exact: true }).focus();
  await page.keyboard.press('Enter'); await settle(page);
  expect(await focused(page)).toMatch(/^Skip rule \d+$/);
  await page.keyboard.type(value);
}

it('Tab out of a new rule saves it, the ask is announced in place and focus stays where the person went', async () => {
  const page = await open();
  try {
    await addRuleWithKeyboard(page, '*.tmp');
    expect((await fixture(page)).posts).toEqual([]);
    await page.keyboard.press('Tab'); const after = await focused(page); await settle(page);
    expect((await fixture(page)).posts).toEqual([['*.log', '*.tmp']]);
    expect(after).not.toBe('body');
    expect(await focused(page), 'the ask does not take focus').toBe(after);
    const ask = page.getByRole('region', { name: ASK, exact: true });
    expect(await ask.count()).toBe(1);
    expect(await ask.evaluate(node => node.closest('[aria-live="polite"]') !== null)).toBe(true);
    expect((await fixture(page)).ops).toEqual([]);
    await ask.getByRole('button', { name: 'Keep them', exact: true }).focus();
    await page.keyboard.press('Enter'); await settle(page);
    expect(await ask.count()).toBe(0);
    expect(await focused(page), 'focus returns to the rule list').toMatch(/^Skip rule \d+$|^Add a rule$/);
    expect((await fixture(page)).ops).toEqual([]);
  } finally { await page.close(); }
}, 60_000);

it('a save in flight never disables the field being typed in; the later text is saved when the field is left', async () => {
  const page = await open();
  try {
    await page.evaluate(() => (window as unknown as FixtureWindow).fixture.holdWrites());
    const first = page.getByRole('textbox', { name: 'Skip rule 1', exact: true });
    await first.focus(); await page.keyboard.press('End'); await page.keyboard.type('s');
    await page.keyboard.press('Tab'); await settle(page);
    expect((await fixture(page)).posts).toEqual([['*.logs']]);
    await page.keyboard.press('Shift+Tab'); await settle(page);
    expect(await focused(page)).toBe('Skip rule 1');
    await page.keyboard.press('End'); await page.keyboard.type('2'); await settle(page);
    expect(await first.isDisabled()).toBe(false);
    expect(await focused(page)).toBe('Skip rule 1');
    expect(await first.inputValue()).toBe('*.logs2');
    await page.evaluate(() => (window as unknown as FixtureWindow).fixture.release()); await settle(page);
    expect(await focused(page)).toBe('Skip rule 1');
    expect((await fixture(page)).posts).toHaveLength(1);
    await page.keyboard.press('Tab'); await settle(page);
    expect((await fixture(page)).posts).toEqual([['*.logs'], ['*.logs2']]);
  } finally { await page.close(); }
}, 60_000);

it('reading hours save from the keyboard and keep focus on the control', async () => {
  const page = await open();
  try {
    const anyTime = page.getByRole('radio', { name: 'Any time', exact: true });
    await anyTime.focus(); await page.keyboard.press('ArrowDown'); await settle(page);
    expect(await page.getByRole('radio', { name: 'Only between', exact: true }).isChecked()).toBe(true);
    expect(await focused(page)).toBe('indexing-hours');
    const ops = (await fixture(page)).ops;
    expect(ops.map(o => o.id)).toEqual(['core.set-indexing-rules']);
    expect(ops[0]!.args).toMatchObject({ schedule: { enabled: true }, pauseOnBattery: false, witness: { acceptedRevision: 1 } });
    await page.getByRole('checkbox', { name: 'Pause while on battery', exact: true }).focus();
    await page.keyboard.press('Space'); await settle(page);
    expect(await focused(page)).toBe('indexing-battery');
    expect((await fixture(page)).ops.at(-1)!.args).toMatchObject({ pauseOnBattery: true, witness: { acceptedRevision: 2 } });
  } finally { await page.close(); }
}, 60_000);

for (const theme of ['light', 'dark']) {
  for (const width of [600, 1280]) {
    it(`the removal ask fits and reads at ${width} px in ${theme}`, async () => {
      const page = await open(width, theme);
      try {
        await addRuleWithKeyboard(page, '*.tmp');
        await page.keyboard.press('Tab'); await settle(page);
        const ask = page.getByRole('region', { name: ASK, exact: true });
        expect(await ask.count()).toBe(1);
        for (const name of ['Remove from search', 'Keep them']) {
          const box = await ask.getByRole('button', { name, exact: true }).boundingBox();
          expect(box, name).not.toBeNull();
          expect(box!.x).toBeGreaterThanOrEqual(0);
          expect(box!.x + box!.width).toBeLessThanOrEqual(width);
        }
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
        const colors = await ask.evaluate(node => {
          const text = getComputedStyle(node.querySelector('p') ?? node).color;
          let bg = 'rgba(0, 0, 0, 0)'; let at: Element | null = node;
          while (at && /rgba\(0, 0, 0, 0\)|transparent/.test(bg)) {
            bg = getComputedStyle(at).backgroundColor;
            at = at.parentElement ?? ((at.getRootNode() as ShadowRoot).host ?? null);
          }
          if (/rgba\(0, 0, 0, 0\)|transparent/.test(bg)) bg = getComputedStyle(document.body).backgroundColor;
          return { text, bg };
        });
        expect(colors.text).not.toBe(colors.bg);
      } finally { await page.close(); }
    }, 60_000);
  }
}
