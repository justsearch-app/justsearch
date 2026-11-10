// SPDX-License-Identifier: Apache-2.0
// Reading memory arrivals through the real Shell/router/dialog in private headless Chromium.
// Run by the CI lockdown config; no Engine or shared dev stack.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import type { BrainSurface } from './BrainSurface.js';
import type { SettingsSurface } from './SettingsSurface.js';

let server: ViteDevServer, browserServer: BrowserServer, browser: Browser;
let context: BrowserContext | undefined;
let output: string, url: string;
declare global {
  interface Window { openContextSettings(): void; openContextBrain(): void }
}

beforeAll(async () => {
  output = await mkdtemp(path.join(tmpdir(), 'context-home-'));
  const labelSource = await readFile('../app-api/src/main/resources/messages/registry-surface.en.properties', 'utf8');
  const labels = Object.fromEntries(labelSource.split('\n').filter(line => line.startsWith('settings.'))
    .map(line => { const i = line.indexOf('='); return [line.slice(0, i), line.slice(i + 1).trim()]; }));
  const harness = `<!doctype html><html><head><meta charset="utf-8"></head><body><script type="module">
import '/src/styles/tokens.css';
import '/src/shell-v0/renderers/registry.ts';
import { restoreActivePresentationOnBoot } from '/src/shell-v0/state/presentationState.ts';
restoreActivePresentationOnBoot();
import '/src/shell-v0/chrome/Shell.ts';
import '/src/shell-v0/views/SettingsSurface.ts';
import '/src/shell-v0/views/BrainSurface.ts';
import { __seedForTest as seedCatalog } from '/src/api/registry/SurfaceCatalogClient.ts';
import { __seedForTest as seedLabels } from '/src/i18n/resourceCatalog.ts';
import { requestSurfaceNavigation } from '/src/shell-v0/controllers/navigateRequest.ts';
seedLabels(${JSON.stringify(labels)});
const surface = (id, mountTag, placement = 'RAIL') => ({ id, mountTag, placement, audience: 'USER',
 presentation: { labelKey: id, descriptionKey: id }, consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
 provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' } });
customElements.define('jf-context-start', class extends HTMLElement {});
customElements.define('jf-context-memory', class extends HTMLElement { connectedCallback() { this.textContent = 'Memory'; } });
seedCatalog({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
 surface('core.search-v3-surface', 'jf-context-start'), { ...surface('core.brain-surface', 'jf-brain-surface'), members: ['core.memory-surface'] },
 surface('core.memory-surface', 'jf-context-memory', 'DEEPLINK'),
 surface('core.settings-surface', 'jf-settings-surface', 'MODAL') ] });
const shell = document.createElement('jf-shell'); document.body.append(shell);
window.openContextSettings = () => requestSurfaceNavigation('core.settings-surface', { section: 'agent' });
window.openContextBrain = () => requestSurfaceNavigation('core.brain-surface');
document.body.dataset.ready = 'true';
</script></body></html>`;
  server = await createServer({
    configFile: false, root: process.cwd(), cacheDir: output, publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'isolated-context-home',
      configureServer(vite) { vite.middlewares.use('/context-home', (_req, res) => {
        res.setHeader('Content-Type', 'text/html'); res.end(harness);
      }); },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/context-home`;
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
});
async function open(): Promise<{ page: Page; errors: string[] }> {
  context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10_000);
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route(new URL('/api/**', url).href, route => route.fulfill({ json: route.request().url().endsWith('/api/settings/v2')
    ? { ui: {}, llm: { contextWindow: 4096 }, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } }
    : { entries: [] } }));
  await page.goto(url, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ state: 'attached', timeout: 45_000 });
  expect(errors).toEqual([]);
  return { page, errors };
}

it.each(['cold', 'Memory tab'])('Settings arrives at Reading memory with focus from %s, without replay on normal return', async timing => {
  const { page, errors } = await open();
  if (timing === 'Memory tab') {
    await page.evaluate(() => window.openContextBrain());
    const brain = page.locator('jf-brain-surface');
    await brain.waitFor({ state: 'attached' });
    await brain.evaluate(el => { (el as BrainSurface).expanded = {}; });
    await brain.locator('button[data-tab-id="core.memory-surface"]').click();
    await expect.poll(() => page.evaluate(() => location.hash)).toContain('tab=core.memory-surface');
  }
  await page.evaluate(() => window.openContextSettings());
  const settings = page.locator('jf-settings-surface');
  await settings.waitFor({ state: 'attached' });
  await settings.evaluate(async el => { const surface = el as SettingsSurface; surface.activeCategory = 'agent'; await surface.updateComplete; });
  const link = settings.locator('[data-settings-anchor="context-window"] button').filter({ hasText: 'Reading memory (context window)' });
  expect(await link.count(), 'Settings provides the Reading memory link').toBe(1);
  expect(await settings.locator('#context-window-override').count(), 'Settings has no duplicate control').toBe(0);
  await link.click();
  const input = page.locator('jf-brain-surface #brain-contextWindow');
  await input.waitFor({ state: 'visible' });
  await expect.poll(() => page.evaluate(() => {
    let active = document.activeElement;
    while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
    return active?.id;
  }), { timeout: 5000 }).toBe('brain-contextWindow');
  expect(await page.locator('jf-settings-window').evaluate(el => (el as HTMLElement & { open: boolean }).open), 'Settings closes on navigation').toBe(false);
  expect(await page.evaluate(() => location.hash), 'the address names the visible AI Brain tab').toContain('tab=runtime');
  const arrival = await input.evaluate(el => ({
    visible: el.getBoundingClientRect().top >= 0 && el.getBoundingClientRect().bottom <= innerHeight,
    highlighting: el.closest('.advanced-row')!.getAnimations().some(a => (a as CSSAnimation).animationName === 'reading-memory-arrival' && a.playState === 'running'),
  }));
  expect(arrival, 'Reading memory is scrolled into view and briefly highlighted').toEqual({ visible: true, highlighting: true });
  await expect.poll(() => input.evaluate(el => el.closest('.advanced-row')!.getAnimations().filter(a => a.playState === 'running').length), { timeout: 5000 }).toBe(0);
  // Returning normally must not consume a stale focus request or replay the highlight.
  await page.locator('jf-brain-surface .brain-links button').first().focus();
  await page.evaluate(() => window.openContextSettings());
  await settings.waitFor({ state: 'attached' });
  await page.evaluate(() => window.openContextBrain());
  const brain = page.locator('jf-brain-surface');
  await brain.waitFor({ state: 'attached' });
  const replay = await brain.evaluate(async el => {
    await (el as BrainSurface).updateComplete;
    await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
    return {
      focused: el.shadowRoot!.activeElement?.id === 'brain-contextWindow',
      animations: el.shadowRoot!.querySelector('#brain-contextWindow')?.closest('.advanced-row')!.getAnimations().length ?? 0,
    };
  });
  expect(replay, 'normal return does not replay arrival focus or highlight').toEqual({ focused: false, animations: 0 });
  expect(errors).toEqual([]);
}, 60_000);

it.each([500, 409])('queued keyboard changes return to saved Reading memory after refusal (%i) and the next poll', async status => {
  const { page, errors } = await open();
  let saved = 4096;
  let release!: () => void;
  let posted = false;
  let reads = 0;
  const held = new Promise<void>(resolve => { release = resolve; });
  await page.route(new URL('/api/settings/v2', url).href, async route => {
    if (route.request().method() === 'POST') {
      posted = true;
      await held;
      await route.fulfill({ status, json: { error: 'Reading memory was refused', errorCode: status === 409 ? 'VERSION_CONFLICT' : 'SAVE_FAILED' } });
    } else {
      reads++;
      await route.fulfill({ json: { ui: {}, llm: { contextWindow: saved }, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } } });
    }
  });
  await page.evaluate(() => window.openContextBrain());
  const brain = page.locator('jf-brain-surface');
  await brain.locator('.brain-links button').first().click();
  const input = brain.locator('#brain-contextWindow');
  await input.focus(); await input.press('ArrowRight');
  await expect.poll(() => posted).toBe(true);
  await input.press('ArrowRight');
  release();
  await expect.poll(() => brain.locator('jf-error-alert[tone="error"]').textContent()).toMatch(status === 409 ? /Reload and review/ : /refused/);
  await expect.poll(() => input.inputValue()).toBe('4096');
  saved = 8192;
  const before = reads;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect.poll(() => reads).toBeGreaterThan(before);
  await expect.poll(() => input.inputValue()).toBe('8192');
  expect(await brain.locator('jf-error-alert[tone="error"]').textContent()).toMatch(status === 409 ? /Reload and review/ : /refused/);
  expect(errors).toEqual([]);
}, 60_000);
