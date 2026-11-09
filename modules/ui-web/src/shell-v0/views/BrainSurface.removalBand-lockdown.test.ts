// SPDX-License-Identifier: Apache-2.0
// D1-D7/D9: real Chromium with isolated fixture responses; no engine, shared stack or GPU.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import path from 'node:path';
let server: ViteDevServer, browserServer: BrowserServer, browser: Browser, context: BrowserContext | undefined;
let output: string, url: string;
const digest = 'b'.repeat(64), longPath = `models/${'long-folder/'.repeat(18)}custom.gguf`;
const part = (incomplete = false) => ({ id: 'chat', label: 'Chat model', state: incomplete ? 'incomplete' : 'downloaded',
  sizeOnDiskBytes: incomplete ? null : 3145728, removable: true, previewDigest: digest,
  unavailableAfter: 'Answers and Text in scans and images', delete: [{ path: 'answer.gguf', sizeBytes: incomplete ? null : 3145728 }],
  kept: [{ path: longPath, sizeBytes: null, reason: 'not verified as JustSearch’s download' }] });
interface FixtureView { parts: Array<ReturnType<typeof part> & { declined?: boolean }>; removal: Record<string, unknown> | null }
declare global {
  interface Window {
    prepareRemovalBrain(): Promise<void>;
    removalFixture(action: string, value?: unknown): Promise<void>;
    removalCalls: Array<{ id: string; args: unknown }>;
    removalOuterEscapes: number;
    otherRemovalDraft: boolean;
  }
}
beforeAll(async () => {
  const root = path.resolve('../..', 'tmp'); await mkdir(root, { recursive: true });
  output = await mkdtemp(path.join(root, 'brain-removal-band-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: output, publicDir: false,
    logLevel: 'silent', server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'isolated-brain-removal',
      configureServer(vite) { vite.middlewares.use('/brain-removal', (_req, res) => {
        res.setHeader('Content-Type', 'text/html');
        res.end(`<!doctype html><html><head><meta charset="utf-8">
          <link rel="stylesheet" href="/src/styles/tokens.css">
          <link rel="stylesheet" href="/src/shell-v0/themes/default.css"></head><body>
          <button id="other-opener">Other confirmation</button><div id="other"></div><script type="module">
          import '/src/shell-v0/views/BrainSurface.ts';
          import '/src/shell-v0/components/ConfirmBand.ts';
          import { __feedForTest } from '/src/shell-v0/state/aiStateStore.ts';
          import { createMockHostApi } from '/src/shell-v0/plugin-api/testHostApi.ts';
          let brain, refusal = '', release;
          const settle = async () => { for (let i = 0; i < 3; i++) await new Promise(requestAnimationFrame); };
          window.removalCalls = []; window.removalOuterEscapes = 0; window.otherRemovalDraft = false;
          document.addEventListener('keydown', e => { if (e.key === 'Escape') window.removalOuterEscapes++; });
          document.querySelector('#other-opener').onclick = () => {
            window.otherRemovalDraft = true;
            const band = document.createElement('jf-confirm-band');
            band.heading = 'Remove another item?'; band.consequence = 'The other item leaves.'; band.actionLabel = 'Remove other item';
            band.opener = document.querySelector('#other-opener');
            band.addEventListener('confirm-band-cancel', () => { window.otherRemovalDraft = false; band.remove(); });
            document.querySelector('#other').replaceChildren(band);
          };
          window.prepareRemovalBrain = async () => {
            __feedForTest({ installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
              aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
              runtime: { mode: 'online', installed: { known: true, value: true } },
              installStatus: { state: 'completed', installedFully: true }, capabilities: { chat: true },
              runtimeStatus: { onnxFeatures: [{ id: 'reranker', status: 'inactive', modelActive: false }] } });
            brain = document.createElement('jf-brain-surface'); brain.apiBase = '';
            brain.host_ = createMockHostApi({ data: { invokeOperation: async (id, args) => {
              window.removalCalls.push({ id, args });
              if (release !== undefined) await new Promise(resolve => release = resolve);
              if (refusal) throw new Error(refusal);
              return { success: true, structuredData: { state: 'running' } };
            } } });
            document.body.append(brain); await settle();
            [...brain.shadowRoot.querySelectorAll('button')].find(b => b.textContent.includes('Advanced settings')).click();
            await settle();
          };
          window.removalFixture = async (action, value) => {
            if (action === 'refresh') await brain.refreshDownloadedFiles();
            if (action === 'refusal') refusal = value;
            if (action === 'hold') release = null;
            if (action === 'release') { const resolve = release; release = undefined; resolve?.(); }
            if (action === 'hide') brain.remove();
            if (action === 'reveal') document.body.append(brain);
            if (action === 'hide-runtime') brain.activeTab = 'memory';
            if (action === 'show-runtime') brain.activeTab = 'runtime';
            await settle();
          };
          document.body.dataset.ready = 'true';
          </script></body></html>`);
      }); },
    }] });
  await server.listen(); const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/brain-removal`;
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
async function prepare(page: Page, view: FixtureView) {
  page.setDefaultTimeout(3000);
  page.on('pageerror', error => console.error('Removal fixture page error:', error.message));
  page.on('console', message => { if (message.type() === 'error') console.error('Removal fixture console:', message.text()); });
  page.on('requestfailed', request => console.error('Removal fixture request failed:', request.url(), request.failure()?.errorText));
  await page.route(new URL('/api/**', url).href, route => {
    const request = route.request().url();
    const body = request.includes('/downloaded-files') ? view : request.includes('/settings/v2')
      ? { ui: { chatEnabled: false }, rerankerEnabled: false, pictureReadingEnabled: false,
        witness: { acceptedRevision: 0, lastCommittedOperationKey: null } }
      : request.includes('/plan-preview') ? { totalDownloadBytes: 0, components: [] } : {};
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
  });
  await page.goto(url, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ state: 'attached', timeout: 45_000 });
  await page.evaluate(() => window.prepareRemovalBrain());
}
async function open(incomplete = false) {
  context = await browser.newContext({ viewport: { width: 1100, height: 800 } });
  const page = await context.newPage(), view: FixtureView = { parts: [part(incomplete)], removal: null };
  await prepare(page, view); return { page, view };
}
const opener = (p: Page) => p.locator('[data-remove="chat"]');
const band = (p: Page) => p.locator('[data-testid="removal-confirm"] jf-confirm-band');
const confirm = (p: Page) => p.locator('[data-testid="removal-confirm"] .confirm-remove, [data-testid="removal-confirm"] jf-button.confirm button');
const keep = (p: Page) => p.locator('[data-testid="removal-confirm"] jf-button.keep button');
async function cancel(p: Page) {
  const legacy = p.locator('[data-testid="removal-confirm"] button').filter({ hasText: /^Cancel$/ });
  await (await keep(p).count() ? keep(p) : legacy).click();
}
it.each(['pointer', 'keyboard'])('D1 %s opens on Keep it; Escape cancels only the band and restores its opener', async method => {
  const { page } = await open();
  if (method === 'pointer') await opener(page).click();
  else { await opener(page).focus(); await page.keyboard.press('Enter'); }
  expect(await band(page).count(), 'shared band is the confirmation').toBe(1);
  await expect.poll(() => keep(page).evaluate(el => el.matches(':focus'))).toBe(true);
  await page.keyboard.press('Escape'); await expect.poll(() => band(page).count()).toBe(0);
  expect(await opener(page).evaluate(el => el.matches(':focus'))).toBe(true);
  expect(await page.evaluate(() => [window.removalOuterEscapes, window.removalCalls.length])).toEqual([0, 0]);
}, 60_000);
it('D3 either host replaces the other draft in this document; a second window stays independent', async () => {
  const { page } = await open();
  const second = await context!.newPage(); await prepare(second, { parts: [part()], removal: null }); await opener(second).click();
  await page.locator('#other-opener').click(); await opener(page).click();
  expect(await page.evaluate(() => window.otherRemovalDraft), 'Brain cancels the other host draft').toBe(false);
  expect(await page.locator('#other jf-confirm-band').count()).toBe(0);
  await page.locator('#other-opener').click();
  await expect.poll(() => page.locator('[data-testid="removal-confirm"]').count()).toBe(0);
  await page.locator('#other jf-button.keep button').click(); await page.evaluate(() => window.removalFixture('refresh'));
  expect(await page.locator('[data-testid="removal-confirm"]').count()).toBe(0);
  expect(await second.locator('[data-testid="removal-confirm"]').count()).toBe(1);
  expect(await page.evaluate(() => window.removalCalls.length)).toBe(0);
}, 60_000);
it.each(['collapse', 'hide', 'hide-runtime', 'missing'])('D6 %s clears the draft permanently when the confirmation leaves', async mode => {
  const { page, view } = await open(); await opener(page).click();
  if (mode === 'collapse') {
    const advanced = page.getByRole('button', { name: 'Advanced settings', exact: true }); await advanced.click(); await advanced.click();
  } else if (mode === 'missing') {
    view.parts = []; await page.evaluate(() => window.removalFixture('refresh'));
    expect(await page.locator('[data-testid="removal-confirm"]').count()).toBe(0);
    view.parts = [part()]; await page.evaluate(() => window.removalFixture('refresh'));
  } else {
    await page.evaluate(mode => window.removalFixture(mode), mode);
    await page.evaluate(mode => window.removalFixture(mode === 'hide' ? 'reveal' : 'show-runtime'), mode);
  }
  expect(await page.locator('[data-testid="removal-confirm"]').count()).toBe(0);
  expect(await page.evaluate(() => window.removalCalls.length)).toBe(0);
}, 60_000);
it.each(['double-click', 'Enter', 'Space'])('D2 %s answers at most once; cancellation never invokes removal', async method => {
  const { page } = await open(); await opener(page).click(); await cancel(page);
  expect(await page.evaluate(() => window.removalCalls.length)).toBe(0);
  await opener(page).click(); await page.evaluate(() => window.removalFixture('hold'));
  if (method === 'double-click') await confirm(page).evaluate(el => { (el as HTMLElement).click(); (el as HTMLElement).click(); });
  else { await confirm(page).focus(); const key = method === 'Space' ? ' ' : 'Enter';
    await page.keyboard.down(key); await page.keyboard.down(key); await page.keyboard.down(key); await page.keyboard.up(key); }
  await expect.poll(() => page.evaluate(() => window.removalCalls.length)).toBe(1);
  await page.evaluate(() => window.removalFixture('release'));
  expect(await page.evaluate(() => window.removalCalls)).toEqual([{ id: 'core.remove-ai-files', args: { packageId: 'chat', previewDigest: digest } }]);
}, 60_000);
it.each([false, true])('D4 complete/incomplete=%s keeps preview paths, sizes, features and assurances', async incomplete => {
  const { page } = await open(incomplete); await opener(page).click();
  const preview = page.locator('[data-testid="removal-confirm"]');
  const text = ((await preview.textContent()) + ' ' + (await band(page).count() ? await band(page).getAttribute('consequence') : '')).replace(/\s+/g, ' ');
  expect(text).toContain(`This deletes 1 file (${incomplete ? 'size unknown' : '3.0 MB'}) from this computer.`);
  expect(text).toContain("Answers and Text in scans and images won't work until you download it again.");
  expect(text).toContain("Each file is checked first; a file JustSearch can't confirm it downloaded is kept.");
  expect(text).toContain('Your documents, the index and conversations are not touched.');
  expect(await preview.locator('li').allTextContents()).toEqual([
    `answer.gguf · ${incomplete ? 'size unknown' : '3.0 MB'}`, `${longPath} · size unknown · not verified as JustSearch’s download`]);
  const heading = await band(page).count() ? await band(page).getAttribute('heading') : await preview.locator('strong').textContent();
  expect(heading).toBe('Remove Chat model files?');
}, 60_000);
it('D5 the accepted digest survives a later poll and a stale-preview refusal stays visible', async () => {
  const { page, view } = await open(); await opener(page).click();
  await page.evaluate(async () => { await window.removalFixture('refusal', 'Preview changed; review the files again'); await window.removalFixture('hold'); });
  await confirm(page).click(); await expect.poll(() => page.evaluate(() => window.removalCalls.length)).toBe(1);
  view.parts = [{ ...part(), previewDigest: 'c'.repeat(64) }];
  await page.evaluate(async () => { await window.removalFixture('refresh'); await window.removalFixture('release'); });
  await expect.poll(() => page.locator('[data-part="chat"] [role="alert"]').textContent()).toBe('Preview changed; review the files again');
  expect(await page.evaluate(() => window.removalCalls)).toEqual([{ id: 'core.remove-ai-files', args: { packageId: 'chat', previewDigest: digest } }]);
  expect(await opener(page).evaluate(el => el.matches(':focus'))).toBe(true);
}, 60_000);
it.each(['removed', 'refused', 'partial', 'interrupted'])('D7 %s outcome and focus survive the opener disappearing during removal', async state => {
  const { page, view } = await open(); await opener(page).click(); await page.evaluate(() => window.removalFixture('hold')); await confirm(page).click();
  await expect.poll(() => page.evaluate(() => window.removalCalls.length)).toBe(1);
  view.parts = []; view.removal = { state, label: 'Chat model', packageId: 'chat', message: 'Observed outcome',
    deleted: [{ path: 'answer.gguf', sizeBytes: 3145728 }], kept: [{ path: longPath, reason: 'custom file kept' }] };
  await page.evaluate(async () => { await window.removalFixture('refresh'); await window.removalFixture('release'); });
  const outcome = page.locator('[data-testid="removal-outcome"]'); await expect.poll(() => outcome.evaluate(el => el.matches(':focus'))).toBe(true);
  expect(await outcome.getAttribute('role')).toBe(state === 'removed' ? 'status' : 'alert');
  expect(await outcome.textContent()).toContain('Observed outcome');
  expect(await outcome.locator('li').allTextContents()).toEqual(['answer.gguf · 3.0 MB', `${longPath} · custom file kept`]);
}, 60_000);
it('D7 a running removal observed after reload blocks conflicting actions', async () => {
  const { page, view } = await open(); view.removal = { state: 'running', packageId: 'chat', label: 'Chat model' };
  await page.reload({ timeout: 45_000 }); await page.locator('body[data-ready="true"]').waitFor({ state: 'attached', timeout: 45_000 });
  await page.evaluate(() => window.prepareRemovalBrain());
  expect(await opener(page).isDisabled()).toBe(true);
  expect(await page.locator('[data-testid="downloaded-files"] [role="status"]').textContent()).toBe('Removing files…');
  expect(await page.evaluate(() => window.removalCalls.length)).toBe(0);
}, 60_000);
it.each([false, true])('D1/D4 narrow window and wide font (forced colours=%s) keep both answers and paths readable', async forced => {
  const { page } = await open(true); await page.setViewportSize({ width: 600, height: 500 });
  if (forced) await page.emulateMedia({ forcedColors: 'active' });
  await page.locator('jf-brain-surface').evaluate(el => { const style = document.createElement('style');
    style.textContent = '* { font-family: monospace !important; letter-spacing: 2px !important; }'; el.shadowRoot!.append(style); });
  await opener(page).click(); expect(await band(page).count()).toBe(1);
  await band(page).evaluate(el => {
    const widen = (root: ShadowRoot) => {
      const style = document.createElement('style');
      style.textContent = '* { font-family: monospace !important; letter-spacing: 2px !important; }';
      root.append(style);
      root.querySelectorAll('*').forEach(child => { if (child.shadowRoot) widen(child.shadowRoot); });
    };
    widen(el.shadowRoot!);
  });
  await keep(page).scrollIntoViewIfNeeded();
  const result = await band(page).evaluate(el => { const box = el.shadowRoot!.querySelector<HTMLElement>('.band')!;
    return { fits: box.scrollWidth <= box.clientWidth, answers: [...box.querySelectorAll('jf-button')].every(b => {
      const r = b.getBoundingClientRect(); return r.width > 0 && r.left >= 0 && r.right <= innerWidth;
    }), edge: getComputedStyle(box).borderInlineStartWidth !== '0px' }; });
  expect(result).toEqual({ fits: true, answers: true, edge: true });
  expect(await page.locator('[data-testid="removal-confirm"]').evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true);
  await page.keyboard.press('Escape'); expect(await opener(page).evaluate(el => el.matches(':focus'))).toBe(true);
}, 60_000);

it.each(['Enter', 'Space'])('D2 holding %s from the opener cannot answer the new band', async method => {
  const { page } = await open(); await opener(page).focus();
  const key = method === 'Space' ? ' ' : 'Enter';
  await page.keyboard.down(key); await page.keyboard.down(key); await page.keyboard.down(key); await page.keyboard.up(key);
  expect(await page.evaluate(() => window.removalCalls.length)).toBe(0);
  await cancel(page);
  expect(await page.evaluate(() => window.removalCalls.length)).toBe(0);
}, 60_000);
it('D7 switching screens during removal keeps the pending guard and shows its late outcome', async () => {
  const { page, view } = await open(); await opener(page).click();
  await page.evaluate(() => window.removalFixture('hold')); await confirm(page).click();
  await page.evaluate(async () => { await window.removalFixture('hide'); await window.removalFixture('reveal'); });
  expect(await opener(page).isDisabled()).toBe(true);
  view.parts = []; view.removal = { state: 'interrupted', label: 'Chat model', message: 'Interrupted while away' };
  await page.evaluate(() => window.removalFixture('release'));
  await expect.poll(() => page.locator('[data-testid="removal-outcome"]').textContent()).toContain('Interrupted while away');
  expect(await page.evaluate(() => window.removalCalls.length)).toBe(1);
}, 60_000);
it('D7 a backend refusal before another removal is observed stays visible', async () => {
  const { page } = await open(); await opener(page).click();
  await page.evaluate(() => window.removalFixture('refusal', 'Another removal is running'));
  await confirm(page).click();
  await expect.poll(() => page.locator('[data-part="chat"] [role="alert"]').textContent()).toBe('Another removal is running');
  expect(await page.evaluate(() => window.removalCalls.length)).toBe(1);
}, 60_000);

it.each(['removed', 'partial', 'no-action'])('D9 %s waits for a later removal poll and focuses the settled row action or outcome', async state => {
  const { page, view } = await open(); await opener(page).click();
  view.removal = { state: 'running', packageId: 'chat', label: 'Chat model' };
  // The operation acknowledges running before the Engine finishes. The old focus attempt
  // therefore hits a disabled row; only a later, real poll exposes the final action.
  await Promise.all([page.waitForResponse(response => response.url().includes('/settings/v2')), confirm(page).click()]);
  await page.evaluate(() => window.removalFixture('settle'));
  expect(await opener(page).isDisabled()).toBe(true);
  view.parts = state === 'removed' ? [{ ...part(), state: 'missing', declined: true }]
    : [{ ...part(true), removable: state !== 'no-action' }];
  view.removal = { state: state === 'removed' ? 'removed' : 'partial', packageId: 'chat', label: 'Chat model' };
  const target = state === 'removed' ? page.locator('[data-download-again="chat"]')
    : state === 'partial' ? opener(page) : page.locator('[data-testid="removal-outcome"]');
  await expect.poll(() => target.evaluateAll(elements => elements.some(el => el.matches(':focus')))).toBe(true);
  await page.evaluate(() => window.removalFixture('refresh'));
  expect(await target.evaluate(el => el.matches(':focus')), 'a later render preserves useful focus').toBe(true);
}, 60_000);

it.each(['row', 'outcome', 'elsewhere'])('D9 polling closes a focused band onto %s without stealing focus elsewhere', async destination => {
  const { page, view } = await open(); await opener(page).click();
  if (destination === 'elsewhere') await page.locator('#other-opener').focus();
  view.parts = destination === 'outcome' ? [] : [{ ...part(), state: 'missing', declined: true }];
  view.removal = { state: 'removed', packageId: 'chat', label: 'Chat model' };
  await page.evaluate(() => window.removalFixture('refresh'));
  expect(await band(page).count()).toBe(0);
  const target = destination === 'elsewhere' ? page.locator('#other-opener')
    : destination === 'row' ? page.locator('[data-download-again="chat"]') : page.locator('[data-testid="removal-outcome"]');
  expect(await target.evaluate(el => el.matches(':focus'))).toBe(true);
}, 60_000);

it('D9 a later removal outcome keeps focus deliberately moved elsewhere while running', async () => {
  const { page, view } = await open(); await opener(page).click();
  view.removal = { state: 'running', packageId: 'chat', label: 'Chat model' };
  await Promise.all([page.waitForResponse(response => response.url().includes('/settings/v2')), confirm(page).click()]);
  await page.locator('#other-opener').focus();
  view.parts = []; view.removal = { state: 'removed', packageId: 'chat', label: 'Chat model' };
  await page.locator('[data-testid="removal-outcome"]').waitFor();
  expect(await page.locator('#other-opener').evaluate(el => el.matches(':focus'))).toBe(true);
}, 60_000);
