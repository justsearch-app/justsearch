// SPDX-License-Identifier: Apache-2.0
// Real production bundle + SES in headless Chromium. Run separately with
// npx playwright-core install chromium && npm run test:unit:lockdown -- --maxWorkers=4
// The default Vitest suite excludes lockdown tests; browser installation is not wired in CI.
import { afterAll, beforeAll, expect, it } from 'vitest';
import { preview, type PreviewServer } from 'vite';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { chromium, type Browser, type Page } from 'playwright-core';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import path from 'node:path';
import { surfaceCatalogSchema } from '../api/types/surface';

// RegistryController's envelope and CoreSurfaceCatalog's Library entry, with the
// precise wire fields also used in SurfaceCatalogClient.test.ts. A normal start
// needs a nonempty catalog; {} exercises first-install failure retries instead.
const normalSurfaceCatalog = surfaceCatalogSchema.parse({
  schemaVersion: '1.0', catalogVersion: 1, namespace: 'registry-surface', primitive: 'Surface',
  entries: [{
    id: 'core.library-surface',
    presentation: { labelKey: 'registry-surface.library-surface.label',
      descriptionKey: 'registry-surface.library-surface.description', iconHint: null, category: null },
    audience: 'USER', placement: 'RAIL', altitude: 'PRODUCT',
    consumes: { resources: ['core.indexed-roots'],
      operations: ['core.reindex', 'core.add-watched-root', 'core.remove-watched-root',
        'core.preview-excludes', 'core.apply-excludes'],
      prompts: [], diagnosticChannels: [], conversationShapes: [] },
    mountTag: 'jf-library-surface', members: ['core.browse-surface'], riskTier: 'LOW', stateSchema: null,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0', identity: null },
  }],
});

declare global {
  interface Window {
    bootHarness: { phase: string; failStatus: boolean; api: boolean; supervisorState: string; calls: string[];
      emit: (event: string, payload: unknown) => void };
  }
}
let browser: Browser;
let server: PreviewServer;
let output: string;
let url: string;
const errors = new WeakMap<Page, string[]>();
beforeAll(async () => {
  const temporaryRoot = path.resolve('..', '..', 'tmp');
  await mkdir(temporaryRoot, { recursive: true });
  output = await mkdtemp(path.join(temporaryRoot, 'boot-lockdown-'));
  await promisify(execFile)(process.execPath, [path.resolve('node_modules/vite/bin/vite.js'),
    'build', '--outDir', output, '--logLevel', 'silent'],
    { env: { ...process.env, NODE_ENV: 'production' }, maxBuffer: 8 * 1024 * 1024, timeout: 100_000 });
  server = await preview({ build: { outDir: output }, preview: { host: '127.0.0.1', port: 0 }, logLevel: 'silent' });
  url = server.resolvedUrls!.local[0]!;
  browser = await chromium.launch({ headless: true });
}, 120_000);
afterAll(async () => {
  await browser?.close();
  if (server) await new Promise<void>((resolve, reject) => server.httpServer.close((error) => error ? reject(error) : resolve()));
  if (output) await rm(output, { recursive: true, force: true });
});

async function open(phase = 'idle', failStatus = false, api = false, desktop = true, query = '', supervisorState = 'exhausted'): Promise<Page> {
  const page = await browser.newPage();
  const observed: string[] = [];
  errors.set(page, observed);
  page.on('pageerror', (error) => observed.push(error.message));
  page.on('console', (message) => { if (message.type() === 'error') observed.push(message.text()); });
  await page.route('https://fonts.**/*', (route) => route.abort());
  await page.addInitScript(({ phase, failStatus, api, desktop, supervisorState }) => {
    // Native IPC is the boundary double; boot, Lit, lockdown and recovery code are real.
    const callbacks = new Map<number, (event: unknown) => void>();
    const listeners = new Map<string, number>();
    let next = 0;
    const harness = window.bootHarness = { phase, failStatus, api, supervisorState, calls: [] as string[],
      emit: (event: string, payload: unknown) => callbacks.get(listeners.get(event)!)?.({ payload }) };
    if (!desktop) return;
    Object.assign(window, {
      __TAURI_INTERNALS__: {
        transformCallback: (callback: (event: unknown) => void) => { callbacks.set(++next, callback); return next; },
        invoke: async (command: string, args: { event: string; handler: number }) => {
          harness.calls.push(command);
          if (command === 'factory_reset_status') {
            if (harness.failStatus) throw new Error('reset status unavailable');
            return { phase: harness.phase, remaining: [] };
          }
          if (command === 'acknowledge_factory_reset') { sessionStorage.setItem('continued', '1'); return; }
          if (command === 'plugin:event|listen') { listeners.set(args.event, args.handler); return args.handler; }
          if (command === 'supervisor_state') return { schemaVersion: 1, kind: 'engine-supervisor-state.v1',
            supervisor: 'tauri', state: harness.api ? 'running' : harness.supervisorState, incarnation: 1, restartCount: 3, maxRestartAttempts: 3 };
          if (command === 'app_update_status') return { state: 'up_to_date', currentVersion: '1.0' };
          if (command === 'complete_factory_reset_storage') { sessionStorage.setItem('continued', '1'); return; }
          if (command === 'api_port') return harness.api ? Number(location.port) : null;
          if (command === 'session_token') return 'boot-test';
          return null;
        },
      },
    });
    if (sessionStorage.getItem('continued')) { harness.phase = 'idle'; harness.failStatus = false; }
    if (sessionStorage.getItem('engine-ready')) harness.api = true;
  }, { phase, failStatus, api, desktop, supervisorState });
  // Hold the reset module until real lockdown completes. This reproduces warm-cache
  // ordering deterministically, without mocking SES or changing the production entry.
  await page.route('**/factoryReset-*.js', async (route) => {
    await page.waitForFunction(() => Object.isFrozen(Symbol));
    await route.continue();
  });
  await page.route(/\/(api|infra|registry)\//, (route) => route.fulfill({
    json: new URL(route.request().url()).pathname === '/api/registry/surfaces' ? normalSurfaceCatalog : {},
  }));
  await page.goto(`${url}${query || (!desktop && api ? `?api_port=${new URL(url).port}` : '')}`, { waitUntil: 'domcontentloaded' });
  return page;
}

async function mounted(page: Page, element: string) {
  // An assertion (rather than an unhandled module error) records the fail-before.
  await expect.poll(async () => ({ mounted: await page.locator(`#root > ${element}`).count(),
    frozen: await page.evaluate(() => Object.isFrozen(Symbol)), errors: errors.get(page) }), { timeout: 8000 }).toMatchObject({ mounted: 1, frozen: true });
}

it('RS4 reset Continue and Try again reload into a mounted app under real lockdown', async () => {
  const page = await open('complete');
  try {
    await mounted(page, 'jf-factory-reset-recovery');
    expect(await page.evaluate(() => window.bootHarness.calls)).not.toContain('api_port');
    await Promise.all([page.waitForEvent('framenavigated'),
      page.getByRole('button', { name: 'Continue', exact: true }).click()]);
    await mounted(page, 'jf-engine-recovery');
    await page.reload();
    await mounted(page, 'jf-engine-recovery');
  } finally { await page.close(); }
  const failed = await open('idle', true);
  try {
    await mounted(failed, 'jf-factory-reset-recovery');
    expect(await failed.evaluate(() => window.bootHarness.calls)).not.toContain('api_port');
    await failed.evaluate(() => sessionStorage.setItem('continued', '1'));
    await Promise.all([failed.waitForEvent('framenavigated'),
      failed.getByRole('button', { name: 'Try again', exact: true }).click()]);
    await mounted(failed, 'jf-engine-recovery');
  } finally { await failed.close(); }
}, 30_000);

it('RN3 nonterminal Engine recovery Retry and both desktop event reloads mount under real lockdown', async () => {
  const page = await open('idle', false, false, true, '', 'starting');
  try {
    await mounted(page, 'jf-engine-recovery');
    await Promise.all([page.waitForEvent('framenavigated'), page.getByText('Try connecting again', { exact: true }).click()]);
    await mounted(page, 'jf-engine-recovery');
    for (const event of ['justsearch://supervisor-state', 'justsearch://backend-restart']) {
      await page.waitForFunction(() => window.bootHarness.calls.includes('api_port'));
      await Promise.all([page.waitForEvent('framenavigated'), page.evaluate((event) => window.bootHarness.emit(event,
        { schemaVersion: 1, kind: 'engine-supervisor-state.v1', supervisor: 'tauri', state: 'running',
          incarnation: 2, restartCount: 0, maxRestartAttempts: 3 }), event)]);
      await mounted(page, 'jf-engine-recovery');
    }
  } finally { await page.close(); }
}, 30_000);

it('E3 terminal Engine recovery restarts through native IPC and reconnects under real lockdown', async () => {
  const page = await open();
  try {
    await mounted(page, 'jf-engine-recovery');
    const restart = page.getByRole('button', { name: 'Restart JustSearch', exact: true });
    await expect.poll(() => restart.count(), { timeout: 2000 }).toBe(1);
    const navigations: string[] = [];
    page.on('framenavigated', (frame) => navigations.push(frame.url()));
    await restart.click();
    await expect.poll(async () => ({ calls: await page.evaluate(() => window.bootHarness.calls.filter((c) => c === 'restart_engine').length),
      navigations })).toEqual({ calls: 1, navigations: [] });
    expect(navigations).toEqual([]);
    await mounted(page, 'jf-engine-recovery');
    await Promise.all([page.waitForEvent('framenavigated'), page.evaluate(() => {
      window.bootHarness.api = true;
      sessionStorage.setItem('engine-ready', '1');
      window.bootHarness.emit('justsearch://supervisor-state',
        { schemaVersion: 1, kind: 'engine-supervisor-state.v1', supervisor: 'tauri', state: 'running',
          incarnation: 2, restartCount: 0, maxRestartAttempts: 3 });
    })]);
    await mounted(page, 'jf-shell');
  } finally { await page.close(); }
}, 30_000);

it('RS4 incomplete and storage-cleanup starts stay gated under real lockdown', async () => {
  for (const phase of ['incomplete', 'awaiting_storage']) {
    const page = await open(phase);
    try {
      await mounted(page, 'jf-factory-reset-recovery');
      expect(await page.evaluate(() => window.bootHarness.calls)).not.toContain('api_port');
      expect(await page.locator('#root > jf-shell').count()).toBe(0);
      if (phase === 'incomplete') {
        expect(await page.getByRole('button', { name: 'Retry reset', exact: true }).count()).toBe(1);
        expect(await page.getByRole('button', { name: 'Cancel reset', exact: true }).count()).toBe(1);
      } else {
        await page.waitForFunction(() => window.bootHarness.calls.includes('complete_factory_reset_storage'));
        await page.reload();
        await mounted(page, 'jf-engine-recovery');
      }
    } finally { await page.close(); }
  }
}, 30_000);

it('RN3 normal desktop and browser starts and debug routes mount after lockdown', async () => {
  for (const desktop of [true, false]) {
    const page = await open('idle', false, true, desktop);
    try {
      await mounted(page, 'jf-shell');
      await page.reload();
      await mounted(page, 'jf-shell');
    } finally { await page.close(); }
  }
  for (const route of ['shell-demo', 'lit-health', 'lit-health-trends', 'presentation-demo']) {
    const page = await open('idle', false, false, true, `?${route}=1`);
    try {
      const selector = route === 'presentation-demo' ? '#root > h1' : '#root > .jf-shell-dock';
      await expect.poll(() => page.locator(selector).count(), { timeout: 8000 }).toBe(1);
      await expect.poll(() => page.evaluate(() => Object.isFrozen(Symbol)), { timeout: 8000 }).toBe(true);
      await page.reload();
      await expect.poll(() => page.locator(selector).count(), { timeout: 8000 }).toBe(1);
    } finally { await page.close(); }
  }
}, 60_000);
