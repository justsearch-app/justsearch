// SPDX-License-Identifier: Apache-2.0
// Slice 6b-2 repairs R24–R26 in a real browser. R24: a page reload (the app reloads itself after an
// Engine restart) never drops a requested save: both editors restore their unsettled changes, settle an
// uncertain write first with its same key and bytes, and say what is not saved yet. R25: after either
// "Changed elsewhere" choice focus lands on a control of that editor, never the page. R26: "Add a rule"
// always adds exactly one empty field and focuses it.
import { afterAll, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type Locator, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

let server: ViteDevServer, browser: Browser, scratch: string, origin: string;
/**
 * Library over a stubbed settings owner whose saved state lives in localStorage, so it outlives a
 * reload as the Engine's does. `fx.mode`: 'hang' leaves a write unanswered and unsaved; 'commit-hang'
 * saves it and loses the answer. A replayed key answers with its first receipt.
 */
const harness = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
// R27 — a full sessionStorage: once capped, a record may not grow past the size it had (kept across a reload).
const setSession = Storage.prototype.setItem;
Storage.prototype.setItem = function (key, value) {
  const limit = this === sessionStorage ? JSON.parse(localStorage.getItem('sessionCap') ?? 'null')?.[key] : undefined;
  if (limit !== undefined && String(value).length > limit) throw new DOMException('The quota has been exceeded.', 'QuotaExceededError');
  return setSession.call(this, key, value);
};
const initial = { revision: 1, lastKey: '0194f72c-0000-7000-8000-000000000001', mode: null, posts: [], ops: [], receipts: {},
  ui: { theme: 'dark', excludePatterns: ['*.log'], indexingPause: { paused: false, untilEpochMs: null, reason: null, pauseOnBattery: false } } };
const s = JSON.parse(localStorage.getItem('fx') ?? 'null') ?? initial;
const keep = () => localStorage.setItem('fx', JSON.stringify(s));
keep();
const witness = () => ({ acceptedRevision: s.revision, lastCommittedOperationKey: s.lastKey });
const commit = key => { s.revision++; s.lastKey = key; };
const never = () => new Promise(() => {});
let hold = null, release = () => {};
const held = () => hold ?? Promise.resolve();
window.fixture = {
  holdWrites: () => { hold = new Promise(resolve => { release = () => { hold = null; resolve(); }; }); },
  release: () => release(),
  state: () => JSON.parse(JSON.stringify(s)),
  /** Storage is full: no kept record may grow from here on. */
  capSession: () => localStorage.setItem('sessionCap', JSON.stringify(Object.fromEntries(
    Object.keys(sessionStorage).map(key => [key, sessionStorage.getItem(key).length])))),
  uncapSession: () => localStorage.removeItem('sessionCap'),
  kept: () => Object.fromEntries(Object.keys(sessionStorage).map(key => [key, JSON.parse(sessionStorage.getItem(key))])),
  setMode: mode => { s.mode = mode; keep(); },
  /** Another window saves: the revision moves. */
  elsewhere: ui => { s.ui = { ...s.ui, ...ui }; commit('0194f72c-0000-7000-8000-0000000000' + (s.revision + 16).toString(16).slice(-2)); keep(); },
};
window.fetch = async (url, init) => {
  const id = String(url).split('/').at(-2);
  const body = JSON.parse(String(init?.body ?? '{}'));
  s.ops.push({ id, key: body.idempotencyKey, args: body.args }); keep();
  await held();
  if (s.receipts[body.idempotencyKey]) return Response.json(s.receipts[body.idempotencyKey]);
  if (s.mode === 'hang') return never();
  if (id !== 'core.set-indexing-rules') return Response.json({ success: false, message: 'unknown', errorClass: 'HANDLER_FAILURE' });
  const based = body.args.witness;
  if (based.acceptedRevision !== s.revision || based.lastCommittedOperationKey !== s.lastKey) {
    return Response.json({ success: false, message: 'Settings changed', errorClass: 'HANDLER_FAILURE', errorCode: 'VERSION_CONFLICT' });
  }
  const pause = { ...s.ui.indexingPause, pauseOnBattery: body.args.pauseOnBattery };
  if (body.args.schedule) pause.schedule = body.args.schedule; else delete pause.schedule;
  s.ui = { ...s.ui, indexingPause: pause };
  commit(body.idempotencyKey);
  const receipt = { success: true, message: 'Committed', structuredData: { witness: witness() } };
  s.receipts[body.idempotencyKey] = receipt; keep();
  return s.mode === 'commit-hang' ? never() : Response.json(receipt);
};
const { createMockHostApi } = await import('/src/shell-v0/plugin-api/testHostApi.ts');
const ai = await import('/src/shell-v0/state/aiStateStore.ts');
const reversals = await import('/src/shell-v0/state/ownReversals.ts');
await import('/src/shell-v0/views/LibrarySurface.ts');
window.undoLatest = () => reversals.undoLatestReversal();
ai.__feedForTest({ status: { worker: { core: { indexedDocuments: 8, ownSearchableDocuments: 8, indexState: 'IDLE', indexHealthy: true },
  migration: { migrationState: 'IDLE' } }, readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } });
const host = createMockHostApi({ data: {
  invokeOperation: async () => ({ success: true, structuredData: { dryRun: false, matchedFiles: 0, confirmedRemovals: 0, capped: false,
    perPattern: [], uncheckedScopes: [] } }),
  fetch: async (url, init) => {
    if (url === '/api/indexing/excludes') return Response.json({ source: 'settings.json', sourceDetail: null, patterns: s.ui.excludePatterns });
    if (url.startsWith('/api/indexing/forgets?')) return Response.json({ items: [], complete: true, nextId: null });
    if (url !== '/api/settings/v2') return new Response('{}', { status: 503 });
    if (init?.method !== 'POST') return Response.json({ settingsMode: 'persistent', ui: structuredClone(s.ui), witness: witness() });
    const attempt = JSON.parse(init.body);
    s.posts.push({ key: attempt.operationKey, body: init.body }); keep();
    await held();
    if (s.receipts[attempt.operationKey]) return Response.json(s.receipts[attempt.operationKey]);
    if (s.mode === 'hang') return never();
    if (attempt.witness.acceptedRevision !== s.revision || attempt.witness.lastCommittedOperationKey !== s.lastKey) {
      return Response.json({ state: 'FAILED', errorCode: 'VERSION_CONFLICT' }, { status: 409 });
    }
    s.ui = { ...s.ui, excludePatterns: attempt.ui.excludePatterns }; commit(attempt.operationKey);
    const receipt = { state: 'COMPLETE', operationKey: attempt.operationKey, witness: witness(), ui: structuredClone(s.ui) };
    s.receipts[attempt.operationKey] = receipt; keep();
    return s.mode === 'commit-hang' ? never() : Response.json(receipt);
  } } });
const el = document.createElement('jf-library-surface');
el.host_ = host; el.refresh = async () => true;
document.body.append(el);
window.settle = async () => { for (let i = 0; i < 35; i++) { await new Promise(resolve => setTimeout(resolve)); await el.updateComplete; } };
window.deepActive = () => { let active = document.activeElement; while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement; return active; };
</script></body></html>`;

beforeAll(async () => {
  scratch = await mkdtemp(path.join(tmpdir(), 'slice-6b2-reload-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: scratch,
    server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'slice-6b2-reload-fixture', configureServer(vite) {
      vite.middlewares.use((req, res, next) => {
        if (!req.url?.startsWith('/slice-6b2-reload.html')) return next();
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

type Saved = { revision: number; lastKey: string; posts: { key: string; body: string }[]; ops: { id: string; key: string; args: Record<string, unknown> }[];
  ui: { excludePatterns: unknown[]; indexingPause: Record<string, unknown> } };
type FixtureWindow = Window & { settle(): Promise<void>; deepActive(): Element | null; undoLatest(): boolean;
  fixture: { state(): Saved; setMode(mode: string | null): void; elsewhere(ui: Record<string, unknown>): void;
    holdWrites(): void; release(): void; capSession(): void; uncapSession(): void; kept(): Record<string, { snapshots: unknown[] }> } };
const win = (page: Page) => page.evaluate.bind(page);
const settle = (page: Page) => win(page)(() => (window as unknown as FixtureWindow).settle());
const saved = (page: Page) => win(page)(() => (window as unknown as FixtureWindow).fixture.state());
const setMode = (page: Page, mode: string | null) => win(page)(value => (window as unknown as FixtureWindow).fixture.setMode(value), mode);
const isFocused = (target: Locator) => target.evaluate(node => (window as unknown as FixtureWindow).deepActive() === node);
const rule = (page: Page, n: number) => page.getByRole('textbox', { name: `Skip rule ${n}`, exact: true });
const ruleCount = (page: Page) => page.getByRole('textbox', { name: /^Skip rule \d+$/ }).count();
/** A control is there before it is used: a missing one fails as an assertion, not a timeout. */
async function present(target: Locator): Promise<Locator> {
  expect(await target.count(), String(target)).toBe(1);
  return target;
}
const button = (page: Page, name: string) => present(page.getByRole('button', { name, exact: true }));
const call = (page: Page, name: 'capSession' | 'uncapSession') => win(page)(fn => { (window as unknown as FixtureWindow).fixture[fn](); }, name);
const kept = (page: Page) => win(page)(() => (window as unknown as FixtureWindow).fixture.kept());
const MAY_BE_LOST = 'Your changes may be lost if this window reloads';
const warnings = (page: Page) => page.getByText(MAY_BE_LOST).count();
const alerts = (page: Page) => page.getByRole('alert').allTextContents().then(all => all.map(text => text.replace(/\s+/g, ' ').trim()).filter(Boolean));

async function open(): Promise<{ page: Page; errors: Error[] }> {
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  page.setDefaultTimeout(3000);
  const errors: Error[] = [];
  page.on('pageerror', error => { errors.push(error); });
  await page.goto(origin + 'slice-6b2-reload.html', { timeout: 45_000 });
  await ready(page);
  expect(await rule(page, 1).inputValue()).toBe('*.log');
  return { page, errors };
}
async function ready(page: Page) {
  await page.waitForFunction(() => typeof Reflect.get(window, 'settle') === 'function', undefined, { timeout: 45_000 });
  await settle(page);
}
/** The app's own reload after an Engine restart: a full document reload in the same window. */
async function reload(page: Page) {
  await page.reload({ timeout: 45_000 });
  await ready(page);
}
async function typeRuleAndLeave(page: Page, value: string) {
  await (await button(page, 'Add a rule')).click(); await settle(page);
  await page.keyboard.type(value);
  await page.keyboard.press('Tab'); await settle(page);
}
const sentPatterns = (post: { body: string }) => (JSON.parse(post.body) as { ui: { excludePatterns: unknown[] } }).ui.excludePatterns;

it('R24: a skip-rule write unanswered at a reload is settled after it with its same key and bytes, then saved', async () => {
  const { page, errors } = await open();
  try {
    await setMode(page, 'hang');
    await typeRuleAndLeave(page, '*.tmp');
    const before = await saved(page);
    expect(before.posts).toHaveLength(1);
    expect(sentPatterns(before.posts[0]!)).toEqual(['*.log', '*.tmp']);
    await setMode(page, null);
    await reload(page);
    const after = await saved(page);
    expect(after.posts, 'the same write, sent again with its key and bytes').toEqual([before.posts[0], before.posts[0]]);
    expect(after.ui.excludePatterns).toEqual(['*.log', '*.tmp']);
    expect(await rule(page, 2).inputValue()).toBe('*.tmp');
    expect(await alerts(page)).toEqual([]);
    expect(await page.getByTestId('excludes-result').textContent()).toContain('Skip rules saved.');
    expect(errors).toEqual([]);
  } finally { await page.close(); }
}, 90_000);

it('R24: a reload with an uncertain skip-rule write and a newer queued one: the first is settled, the newer waits for Try again', async () => {
  const { page, errors } = await open();
  try {
    await setMode(page, 'commit-hang');
    await typeRuleAndLeave(page, '*.tmp');
    await typeRuleAndLeave(page, '*.bak');
    const before = await saved(page);
    expect(before.posts, 'one write in flight; the newer change waits').toHaveLength(1);
    expect(before.ui.excludePatterns, 'the first write was saved; its answer is lost').toEqual(['*.log', '*.tmp']);
    await setMode(page, null);
    await reload(page);
    let after = await saved(page);
    expect(after.posts, 'only the uncertain write is sent, unchanged').toEqual([before.posts[0], before.posts[0]]);
    expect(after.revision).toBe(before.revision);
    expect(await rule(page, 3).inputValue(), 'the newer change is still the draft').toBe('*.bak');
    expect(await alerts(page)).toEqual(['Your changes are not saved yet. Try again']);
    await (await button(page, 'Try again')).click(); await settle(page);
    after = await saved(page);
    expect(after.posts).toHaveLength(3);
    expect(after.ui.excludePatterns).toEqual(['*.log', '*.tmp', '*.bak']);
    expect(await alerts(page)).toEqual([]);
    await reload(page);
    expect((await saved(page)).posts, 'nothing is left to restore').toHaveLength(3);
    expect(await ruleCount(page)).toBe(3);
    expect(errors).toEqual([]);
  } finally { await page.close(); }
}, 90_000);

it('R24: a restored skip-rule change saved elsewhere meanwhile is not sent over it: Changed elsewhere', async () => {
  const { page } = await open();
  try {
    await setMode(page, 'hang');
    await typeRuleAndLeave(page, '*.tmp');
    await setMode(page, null);
    await win(page)(() => (window as unknown as FixtureWindow).fixture.elsewhere({ excludePatterns: ['*.iso'] }));
    await reload(page);
    const after = await saved(page);
    expect(after.ui.excludePatterns).toEqual(['*.iso']);
    expect(await alerts(page)).toContainEqual(expect.stringContaining('Changed elsewhere. Your draft is kept.'));
    expect(await rule(page, 2).inputValue()).toBe('*.tmp');
  } finally { await page.close(); }
}, 90_000);

it('R24: a reading-hours write unanswered at a reload is settled with its same key and bytes; a newer change waits for Try again', async () => {
  const { page, errors } = await open();
  try {
    await setMode(page, 'hang');
    await page.getByRole('radio', { name: 'Only between', exact: true }).check(); await settle(page);
    await page.getByRole('checkbox', { name: 'Pause while on battery', exact: true }).check(); await settle(page);
    const before = await saved(page);
    const rulesOps = before.ops.filter(op => op.id === 'core.set-indexing-rules');
    expect(rulesOps).toHaveLength(1);
    await setMode(page, null);
    await reload(page);
    let after = await saved(page);
    const replays = after.ops.filter(op => op.id === 'core.set-indexing-rules');
    expect(replays, 'only the unanswered write is sent, unchanged').toEqual([rulesOps[0], rulesOps[0]]);
    expect(after.ui.indexingPause).toMatchObject({ schedule: { enabled: true }, pauseOnBattery: false });
    expect(await page.getByRole('radio', { name: 'Only between', exact: true }).isChecked()).toBe(true);
    expect(await page.getByRole('checkbox', { name: 'Pause while on battery', exact: true }).isChecked(), 'the newer change is still the draft').toBe(true);
    expect(await alerts(page)).toEqual(['Your changes are not saved yet. Try again']);
    await (await button(page, 'Try again')).click(); await settle(page);
    after = await saved(page);
    expect(after.ui.indexingPause).toMatchObject({ schedule: { enabled: true }, pauseOnBattery: true });
    expect(await alerts(page)).toEqual([]);
    await reload(page);
    expect((await saved(page)).ops.filter(op => op.id === 'core.set-indexing-rules'), 'nothing is left to restore').toHaveLength(3);
    expect(errors).toEqual([]);
  } finally { await page.close(); }
}, 90_000);

it('R24: a reading-hours write saved but unanswered at a reload is settled from its receipt, not written again', async () => {
  const { page } = await open();
  try {
    await setMode(page, 'commit-hang');
    await page.getByRole('checkbox', { name: 'Pause while on battery', exact: true }).check(); await settle(page);
    const before = await saved(page);
    expect(before.ops.filter(op => op.id === 'core.set-indexing-rules')).toHaveLength(1);
    await setMode(page, null);
    await reload(page);
    const after = await saved(page);
    expect(after.revision).toBe(before.revision);
    expect(after.ops.filter(op => op.id === 'core.set-indexing-rules').map(op => op.key)).toEqual([before.ops[0]!.key, before.ops[0]!.key]);
    expect(await page.getByRole('checkbox', { name: 'Pause while on battery', exact: true }).isChecked()).toBe(true);
    expect(await alerts(page)).toEqual([]);
  } finally { await page.close(); }
}, 90_000);

it('R25: after either skip-rule conflict choice focus is on a rule field, by mouse and by keyboard', async () => {
  const { page } = await open();
  try {
    const conflict = async (value: string, list: string[]) => {
      await win(page)(excludePatterns => (window as unknown as FixtureWindow).fixture.elsewhere({ excludePatterns }), list);
      await typeRuleAndLeave(page, value);
      expect(await alerts(page)).toContainEqual(expect.stringContaining('Changed elsewhere.'));
    };
    await conflict('*.tmp', ['*.log', '*.iso']);
    await (await button(page, 'Use the saved rules')).click(); await settle(page);
    expect(await isFocused(rule(page, 1)), 'Use the saved rules: the first rule field').toBe(true);
    await conflict('*.bak', ['*.log', '*.iso', '*.zip']);
    const edited = await ruleCount(page);
    await (await button(page, 'Keep my draft')).focus();
    await page.keyboard.press('Enter'); await settle(page);
    expect(await isFocused(rule(page, edited)), 'Keep my draft: the field being edited').toBe(true);
    expect((await saved(page)).ui.excludePatterns).toEqual(['*.log', '*.iso', '*.bak']);
  } finally { await page.close(); }
}, 90_000);

it('R25: Use the saved rules with no rules saved focuses Add a rule', async () => {
  const { page } = await open();
  try {
    await win(page)(() => (window as unknown as FixtureWindow).fixture.elsewhere({ excludePatterns: [] }));
    await typeRuleAndLeave(page, '*.tmp');
    expect(await alerts(page)).toContainEqual(expect.stringContaining('Changed elsewhere.'));
    await (await button(page, 'Use the saved rules')).focus();
    await page.keyboard.press('Enter'); await settle(page);
    expect(await isFocused(page.getByRole('button', { name: 'Add a rule', exact: true }))).toBe(true);
  } finally { await page.close(); }
}, 90_000);

it('R25: after either reading-hours conflict choice focus is in the reading-hours editor', async () => {
  const { page } = await open();
  try {
    const battery = page.getByRole('checkbox', { name: 'Pause while on battery', exact: true });
    const conflict = async (startMinute: number) => {
      const pause = (await saved(page)).ui.indexingPause;
      await win(page)(value => (window as unknown as FixtureWindow).fixture.elsewhere({ indexingPause: value }),
        { ...pause, schedule: { enabled: true, days: [1, 2], startMinute, endMinute: 1200 } });
      await battery.click(); await settle(page);
      expect(await alerts(page)).toContainEqual(expect.stringContaining('Changed elsewhere.'));
    };
    await conflict(60);
    await (await button(page, 'Use the saved rules')).click(); await settle(page);
    expect(await isFocused(page.getByRole('radio', { name: 'Any time', exact: true })), 'Use the saved rules: the first control').toBe(true);
    await conflict(120);
    await (await button(page, 'Keep my draft')).focus();
    await page.keyboard.press('Enter'); await settle(page);
    expect(await isFocused(battery), 'Keep my draft: the control being edited').toBe(true);
  } finally { await page.close(); }
}, 90_000);

it('R26: Add a rule adds exactly one empty focused field after Keep them, after Undo, across a passive re-read and a completing save', async () => {
  const { page } = await open();
  try {
    const add = page.getByRole('button', { name: 'Add a rule', exact: true });
    const addsOne = async (how: string, meanwhile?: () => Promise<void>) => {
      const count = await ruleCount(page);
      await add.click(); await settle(page);
      if (meanwhile) await meanwhile();
      expect(await ruleCount(page), how).toBe(count + 1);
      expect(await rule(page, count + 1).inputValue(), how).toBe('');
      expect(await isFocused(rule(page, count + 1)), how).toBe(true);
    };
    const keepThem = async () => { await (await button(page, 'Keep them')).click(); await settle(page); };
    await typeRuleAndLeave(page, '*.tmp');
    await keepThem();
    await addsOne('after Keep them');
    await page.keyboard.type('*.bak'); await page.keyboard.press('Tab'); await settle(page);
    expect((await saved(page)).ui.excludePatterns).toEqual(['*.log', '*.tmp', '*.bak']);
    await keepThem();
    await win(page)(() => (window as unknown as FixtureWindow).undoLatest()); await settle(page);
    expect((await saved(page)).ui.excludePatterns).toEqual(['*.log', '*.tmp']);
    expect(await ruleCount(page)).toBe(2);
    await addsOne('after Undo');
    await addsOne('across a passive re-read', async () => {
      await win(page)(() => window.dispatchEvent(new Event('focus'))); await settle(page);
    });
    await page.keyboard.press('Shift+Tab'); await page.keyboard.press('Shift+Tab'); await settle(page);
    await (await button(page, 'Remove skip rule 4')).click(); await settle(page);
    await (await button(page, 'Remove skip rule 3')).click(); await settle(page);
    expect(await ruleCount(page)).toBe(2);
    await win(page)(() => (window as unknown as FixtureWindow).fixture.holdWrites());
    await typeRuleAndLeave(page, '*.dat');
    await addsOne('while a save completes', async () => {
      await win(page)(() => (window as unknown as FixtureWindow).fixture.release()); await settle(page);
      expect((await saved(page)).ui.excludePatterns).toEqual(['*.log', '*.tmp', '*.dat']);
    });
    await page.keyboard.type('*.iso'); await page.keyboard.press('Tab'); await settle(page);
    await keepThem();
    await win(page)(() => (window as unknown as FixtureWindow).fixture.holdWrites());
    await win(page)(() => (window as unknown as FixtureWindow).undoLatest()); await settle(page);
    const count = await ruleCount(page);
    await add.click(); await settle(page);
    await win(page)(() => (window as unknown as FixtureWindow).fixture.release()); await settle(page);
    expect((await saved(page)).ui.excludePatterns).toEqual(['*.log', '*.tmp', '*.dat']);
    expect(await ruleCount(page), 'an Undo completing after Add a rule keeps the new field').toBe(count);
    expect(await rule(page, count).inputValue()).toBe('');
    expect(await isFocused(rule(page, count))).toBe(true);
    expect(await rule(page, 3).inputValue()).toBe('*.dat');
  } finally { await page.close(); }
}, 120_000);

it('R27: a skip-rule checkpoint that cannot be written while a save is unanswered warns, and no older partial one is restored', async () => {
  const { page, errors } = await open();
  try {
    await setMode(page, 'hang');
    await typeRuleAndLeave(page, '*.tmp');
    expect(Object.values(await kept(page)).map(record => record.snapshots.length), 'the first change is kept').toEqual([1]);
    expect(await warnings(page)).toBe(0);
    await call(page, 'capSession');
    await typeRuleAndLeave(page, '*.bak');
    expect(await warnings(page), 'the newer change could not be kept: said before any reload').toBe(1);
    expect(await kept(page), 'never an older partial record').toEqual({});
    await setMode(page, null);
    await reload(page);
    const after = await saved(page);
    expect(after.posts, 'nothing restored is sent').toHaveLength(1);
    expect(after.ui.excludePatterns).toEqual(['*.log']);
    expect(await ruleCount(page), 'the saved rules show, not the older change alone').toBe(1);
    expect(await alerts(page)).toEqual([]);
    expect(errors).toEqual([]);
  } finally { await call(page, 'uncapSession'); await page.close(); }
}, 90_000);

it('R27: a reading-hours checkpoint that cannot be written while a save is unanswered warns, and no older partial one is restored', async () => {
  const { page, errors } = await open();
  try {
    await setMode(page, 'hang');
    await page.getByRole('radio', { name: 'Only between', exact: true }).check(); await settle(page);
    expect(Object.values(await kept(page)).map(record => record.snapshots.length), 'the first change is kept').toEqual([1]);
    await call(page, 'capSession');
    await page.getByRole('checkbox', { name: 'Pause while on battery', exact: true }).check(); await settle(page);
    expect(await warnings(page), 'the newer change could not be kept: said before any reload').toBe(1);
    expect(await kept(page), 'never an older partial record').toEqual({});
    await setMode(page, null);
    await reload(page);
    const after = await saved(page);
    expect(after.ops.filter(op => op.id === 'core.set-indexing-rules'), 'nothing restored is sent').toHaveLength(1);
    expect(await page.getByRole('radio', { name: 'Any time', exact: true }).isChecked(), 'the saved rules show').toBe(true);
    expect(await page.getByRole('checkbox', { name: 'Pause while on battery', exact: true }).isChecked()).toBe(false);
    expect(await alerts(page)).toEqual([]);
    expect(errors).toEqual([]);
  } finally { await call(page, 'uncapSession'); await page.close(); }
}, 90_000);

it('R27: the checkpoint is tried again on the next queue change; the warning goes once a whole one is kept or the queue settles', async () => {
  const { page, errors } = await open();
  try {
    await win(page)(() => (window as unknown as FixtureWindow).fixture.holdWrites());
    await typeRuleAndLeave(page, '*.tmp');
    await call(page, 'capSession');
    await typeRuleAndLeave(page, '*.bak');
    expect(await warnings(page)).toBe(1);
    await call(page, 'uncapSession');
    await typeRuleAndLeave(page, '*.zip');
    expect(await warnings(page), 'a whole checkpoint was kept').toBe(0);
    expect(Object.values(await kept(page)).map(record => record.snapshots.length), 'the whole queue').toEqual([3]);
    await call(page, 'capSession');
    await win(page)(() => (window as unknown as FixtureWindow).fixture.release()); await settle(page);
    expect((await saved(page)).ui.excludePatterns).toEqual(['*.log', '*.tmp', '*.bak', '*.zip']);
    expect(await warnings(page), 'the skip-rule queue settled').toBe(0);
    expect(await kept(page)).toEqual({});
    await call(page, 'uncapSession');
    await win(page)(() => (window as unknown as FixtureWindow).fixture.holdWrites());
    await page.getByRole('radio', { name: 'Only between', exact: true }).check(); await settle(page);
    await call(page, 'capSession');
    await page.getByRole('checkbox', { name: 'Pause while on battery', exact: true }).check(); await settle(page);
    expect(await warnings(page), 'reading hours could not be kept').toBe(1);
    expect(await kept(page)).toEqual({});
    await win(page)(() => (window as unknown as FixtureWindow).fixture.release()); await settle(page);
    expect((await saved(page)).ui.indexingPause).toMatchObject({ schedule: { enabled: true }, pauseOnBattery: true });
    expect(await warnings(page), 'the reading-hours queue settled while storage stayed full').toBe(0);
    expect(await kept(page)).toEqual({});
    expect(errors).toEqual([]);
  } finally { await call(page, 'uncapSession'); await page.close(); }
}, 120_000);
