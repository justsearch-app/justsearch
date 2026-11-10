// SPDX-License-Identifier: Apache-2.0
// L2–L8: real Library windows sharing one settings store, on an owned Vite/Chromium instance.
import { afterAll, afterEach, beforeAll, beforeEach, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type BrowserServer, type Browser, type BrowserContext, type Page, type Route } from 'playwright-core';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import path from 'node:path';

type Witness = { acceptedRevision: number; lastCommittedOperationKey: string | null };
type Entry = string | { literal: { version: number; id: string; path: string; scope: string } };
type View = { text: string; literals: Entry[]; witness: Witness; attempt: unknown; conflict: boolean; loaded: boolean; policy: unknown; error: unknown };
declare global {
  interface Window {
    windowfix: { state(): View; save(): Promise<void>; leave(): void; return(): void; undo(): Promise<void> };
  }
}
const literal: Entry = { literal: { version: 1, id: 'aa10bcec-4102-47f8-a26d-d02346c8a210',
  path: '/docs/report\\name[1].txt \t', scope: 'FILE' } };
const HARNESS = `<!doctype html><html><head><link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
import '/src/shell-v0/views/LibrarySurface.ts';
import { createMockHostApi } from '/src/shell-v0/plugin-api/testHostApi.ts';
import { undoLatestReversal } from '/src/shell-v0/state/ownReversals.ts';
const el = document.createElement('jf-library-surface');
el.host_ = createMockHostApi({data: {fetch: (path, init) => fetch(path, init)}});
document.body.append(el);
window.windowfix = {
 state: () => ({text:el.excludesText,literals:el.excludesLiterals,witness:el.excludesWitness,attempt:el.excludesAttempt,
   conflict:el.excludesConflict,loaded:el.excludesLoaded,policy:el.excludesPolicy,error:el.excludesError}),
 save: () => el.handleSaveExcludes(), leave: () => el.remove(), return: () => document.body.append(el),
 undo: async () => { undoLatestReversal(); },
};
document.body.dataset.ready = 'true';
</script></body></html>`;
let browserServer: BrowserServer;
let server: ViteDevServer, browser: Browser, context: BrowserContext, output: string, url: string;
let saved: Entry[], witness: Witness, invalid: string[], reads: number, writes: string[];
let override: { source: string; sourceDetail: string | null; patterns: string[] } | null;
let failRead: boolean, failPolicy: boolean, failSave: boolean;
let heldRead: { started: Promise<void>; release: () => void } | undefined;
let holdNext: boolean;
let heldSave: Promise<void> | null;
function holdRead() {
  let start!: () => void, release!: () => void;
  const started = new Promise<void>(resolve => { start = resolve; });
  const wait = new Promise<void>(resolve => { release = resolve; });
  holdNext = true;
  heldRead = { started, release };
  return { started, release, wait, start };
}
let held: ReturnType<typeof holdRead> | undefined;
const state = (page: Page) => page.evaluate(() => window.windowfix.state());
async function respond(route: Route, body: unknown, status = 200) {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}
beforeAll(async () => {
  const root = path.resolve('../..', 'tmp'); await mkdir(root, { recursive: true });
  output = await mkdtemp(path.join(root, 'library-refresh-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: output, publicDir: false,
    logLevel: 'silent', server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'library-refresh',
      configureServer(vite) { vite.middlewares.use('/refresh-library', (_req, res) => {
        res.setHeader('Content-Type', 'text/html'); res.end(HARNESS);
      }); },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/refresh-library`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  browser = await chromium.connect(browserServer.wsEndpoint());
}, 60_000);
beforeEach(async () => {
  saved = ['*.log', literal]; witness = { acceptedRevision: 0, lastCommittedOperationKey: null };
  reads = 0; writes = []; invalid = []; override = null;
  failRead = false; failPolicy = false; failSave = false; holdNext = false; heldSave = null; heldRead = undefined; held = undefined;
  context = await browser.newContext();
  await context.route(target => target.pathname.startsWith('/api/'), async route => {
    const req = route.request(), endpoint = new URL(req.url()).pathname;
    if (endpoint === '/api/settings/v2') {
      if (req.method() === 'POST') {
        writes.push(req.postData()!);
        if (heldSave) await heldSave;
        const attempt = req.postDataJSON();
        if (failSave) return respond(route, { errorCode: 'UNAVAILABLE', error: 'offline' }, 503);
        if (attempt.witness.acceptedRevision !== witness.acceptedRevision) {
          return respond(route, { errorCode: 'VERSION_CONFLICT', state: 'FAILED', error: 'Changed elsewhere' }, 409);
        }
        saved = attempt.ui.excludePatterns;
        witness = { acceptedRevision: witness.acceptedRevision + 1, lastCommittedOperationKey: attempt.operationKey };
        return respond(route, { state: 'COMPLETE', operationKey: attempt.operationKey, witness });
      }
      reads++;
      const observation = structuredClone({ ui: { excludePatterns: saved, invalidExcludePatterns: invalid }, witness });
      if (holdNext && held) { holdNext = false; held.start(); await held.wait; }
      return respond(route, failRead ? { error: 'offline' } : observation, failRead ? 503 : 200);
    }
    if (endpoint === '/api/indexing/excludes') return respond(route,
      override ?? { source: 'settings.json', sourceDetail: null, patterns: saved.filter(v => typeof v === 'string') }, failPolicy ? 503 : 200);
    if (endpoint === '/api/indexing-roots/substrate') return respond(route, { items: [] });
    if (endpoint === '/api/indexing/forgets') return respond(route, { items: [], complete: true, nextId: null });
    return respond(route, { entries: [], results: [], facets: {}, jobs: [], count: 0 });
  });
});
afterEach(async () => { heldRead?.release(); await context?.close(); });
afterAll(async () => { await browser?.close(); await browserServer?.close(); await server?.close(); if (output) await rm(output, { recursive: true, force: true }); });
async function open(): Promise<Page> {
  const page = await context.newPage(); page.setDefaultTimeout(5000);
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto(url, { timeout: 20_000 });
  await page.locator('body[data-ready="true"]').waitFor({ state: 'attached', timeout: 20_000 })
    .catch(error => { throw new Error(`${String(error)}\nPage errors: ${errors.join('\n')}`); });
  await expect.poll(async () => (await state(page)).loaded).toBe(true);
  await expect.poll(async () => (await state(page)).policy).not.toBeNull();
  return page;
}
async function activate(page: Page) {
  await page.bringToFront();
  // Chromium's headless window manager may keep both pages focused; deliver the browser focus event.
  await page.evaluate(() => window.dispatchEvent(new FocusEvent('focus')));
}
async function draft(page: Page, text: string) { await page.locator('.skip-rule input').first().fill(text); }
async function save(page: Page) { await page.evaluate(() => window.windowfix.save()); }

it('L2 a second clean window observes add, remove and undo on focus and on returning', async () => {
  const first = await open(), second = await open();
  await draft(first, '*.tmp'); await save(first);
  await activate(second);
  await expect.poll(async () => (await state(second)).text).toBe('*.tmp');
  await first.locator('jf-button[label^="Remove keep-out"] button').click(); await save(first);
  await second.evaluate(() => window.windowfix.leave());
  await second.evaluate(() => window.windowfix.return());
  await expect.poll(async () => (await state(second)).literals).toEqual([]);
  await first.evaluate(() => window.windowfix.undo());
  await expect.poll(() => saved).toEqual(['*.tmp', literal]);
  await activate(second);
  await expect.poll(async () => (await state(second)).literals).toEqual([literal]);
}, 30_000);

it.each(['text', 'exact-path'])('L3 a second window keeps its %s draft and original conflict witness', async kind => {
  const first = await open(), second = await open();
  const original = await state(second);
  if (kind === 'text') await draft(second, 'my draft');
  else await second.locator('jf-button[label^="Remove keep-out"] button').click();
  await draft(first, 'elsewhere'); await save(first); await activate(second);
  await second.evaluate(() => { window.windowfix.leave(); window.windowfix.return(); });
  expect((await state(second)).witness).toEqual(original.witness);
  expect((await state(second)).text).toBe(kind === 'text' ? 'my draft' : '*.log');
  expect((await state(second)).literals).toEqual(kind === 'text' ? [literal] : []);
  await save(second);
  const refused = await state(second);
  expect(refused.conflict).toBe(true);
  expect(saved[0]).toBe('elsewhere');
  await activate(second); await second.waitForTimeout(100);
  expect(await state(second), 'a focus keeps the conflict and its draft').toEqual(refused);
}, 30_000);

it.each(['text', 'exact-path'])('L4 a late refresh cannot replace a newer %s draft', async kind => {
  const first = await open(), second = await open();
  await draft(first, 'elsewhere'); await save(first);
  const original = await state(second);
  held = holdRead(); await activate(second);
  await Promise.race([held.started, new Promise((_, reject) => setTimeout(() => reject(new Error('No passive read started')), 5000))]);
  if (kind === 'text') await draft(second, 'new draft');
  else await second.locator('jf-button[label^="Remove keep-out"] button').click();
  held.release();
  await second.waitForTimeout(100);
  const after = await state(second);
  expect(after.witness).toEqual(original.witness);
  expect(after.text).toBe(kind === 'text' ? 'new draft' : '*.log');
  expect(after.literals).toEqual(kind === 'text' ? [literal] : []);
}, 30_000);

it('L4 a late refresh cannot rewind a save made in this window while the read was outstanding', async () => {
  const page = await open();
  saved = ['from another window'];
  witness = { acceptedRevision: 1, lastCommittedOperationKey: '01890000-0000-7000-8000-000000000001' };
  // Rebase the window on the store first, so its own save can succeed against the held read.
  await activate(page);
  await expect.poll(async () => (await state(page)).text).toBe('from another window');
  held = holdRead(); await activate(page);
  await Promise.race([held.started, new Promise((_, reject) => setTimeout(() => reject(new Error('No passive read started')), 5000))]);
  await draft(page, 'saved here'); await save(page);
  const afterSave = await state(page);
  expect(afterSave.text).toBe('saved here');
  held.release(); await page.waitForTimeout(150);
  const after = await state(page);
  expect(after.text, 'the older held observation does not replace the save').toBe('saved here');
  expect(after.witness).toEqual(afterSave.witness);
  expect(saved[0]).toBe('saved here');
}, 30_000);

it('L5 an uncertain save keeps its operation key and exact bytes through focus and remount', async () => {
  const page = await open(); await draft(page, 'draft'); failSave = true; await save(page);
  const attempt = (await state(page)).attempt;
  expect(attempt).not.toBeNull();
  await activate(page); await page.waitForTimeout(100);
  await page.evaluate(() => { window.windowfix.leave(); window.windowfix.return(); });
  await page.waitForTimeout(100);
  const after = await state(page);
  expect(after.attempt).toEqual(attempt);
  expect(after.text).toBe('draft');
  failSave = false; await save(page);
  expect(writes).toHaveLength(2);
  expect(writes[1]).toBe(writes[0]);
  expect(saved[0]).toBe('draft');
}, 30_000);

it('L5 a focus during a save that is still pending starts no competing read and keeps the outcome', async () => {
  const page = await open(); await draft(page, 'draft');
  let releaseSave!: () => void;
  const hold = new Promise<void>(resolve => { releaseSave = resolve; });
  heldSave = hold;
  const saving = save(page);
  await expect.poll(() => writes.length).toBe(1);
  const before = reads;
  await activate(page); await page.waitForTimeout(100);
  expect(reads, 'no passive read while the save is pending').toBe(before);
  releaseSave(); await saving;
  const after = await state(page);
  expect(after.attempt).toBeNull();
  expect(after.text).toBe('draft');
  expect(saved[0]).toBe('draft');
}, 30_000);

it('L6 a failed settings or policy refresh preserves the observation and a later read recovers', async () => {
  const page = await open(); const original = await state(page);
  for (const failure of ['settings', 'policy']) {
    failRead = failure === 'settings'; failPolicy = failure === 'policy';
    saved = ['changed'];
    const before = reads; await activate(page);
    await expect.poll(() => reads).toBeGreaterThan(before);
    await page.waitForTimeout(100);
    expect(await state(page)).toEqual(original);
  }
  failRead = false; failPolicy = false; await activate(page);
  await expect.poll(async () => (await state(page)).text).toBe('changed');
}, 30_000);

it('L6 leaving with an outstanding read discards its result, including after reconnect', async () => {
  const page = await open(); const original = await state(page);
  saved = ['changed']; held = holdRead(); await activate(page);
  await Promise.race([held.started, new Promise((_, reject) => setTimeout(() => reject(new Error('No passive read started')), 5000))]);
  await page.evaluate(() => window.windowfix.leave()); held.release(); await page.waitForTimeout(100);
  expect(await state(page)).toEqual(original);
  await page.evaluate(() => window.windowfix.return());
  await expect.poll(async () => (await state(page)).text).toBe('changed');
}, 30_000);

it.each(['reload', 'focus'])('L7 %s retains empty lists, glob strings, warnings and exact-path bytes on the next save', async how => {
  const page = await open();
  const observe = async () => how === 'reload'
    ? page.locator('jf-button[label="Reload saved patterns"] button').click() : activate(page);
  saved = []; await observe();
  await expect.poll(async () => (await state(page)).literals).toEqual([]);
  await expect.poll(async () => (await state(page)).text).toBe('');
  saved = ['*.log', '[broken', literal]; invalid = ['[broken']; await observe();
  await expect.poll(async () => (await state(page)).text).toBe('*.log\n[broken');
  expect(await page.getByRole('status').filter({ hasText: 'invalid pattern syntax' }).textContent()).toContain('[broken');
  await draft(page, '*.tmp'); await save(page);
  expect(saved).toEqual(['*.tmp', '[broken', literal]);
}, 30_000);

it('L8 effective override is refreshed with saved rules and gates Preview/Apply consistently', async () => {
  const page = await open();
  override = { source: 'env_var', sourceDetail: 'JUSTSEARCH_UI_EXCLUDE_PATTERNS', patterns: ['operator-only'] };
  await activate(page);
  await expect.poll(async () => (await state(page)).policy).toEqual(override);
  expect(await page.locator('#excludes-override').textContent()).toContain('operator-only');
  for (const label of ['Preview', 'Apply']) expect(await page.locator(`jf-button[label="${label}"] button`).isEnabled()).toBe(true);
}, 30_000);
