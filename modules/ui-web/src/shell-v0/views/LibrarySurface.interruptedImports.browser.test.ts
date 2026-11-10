// SPDX-License-Identifier: Apache-2.0
import { afterAll, afterEach, beforeAll, beforeEach, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type BrowserServer, type Browser, type BrowserContext, type Page, type Route } from 'playwright-core';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import path from 'node:path';

const HARNESS = `<!doctype html><html><head><link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
import '/src/shell-v0/views/LibrarySurface.ts';
import '/src/shell-v0/components/AuthorizationHost.ts';
import { createMockHostApi } from '/src/shell-v0/plugin-api/testHostApi.ts';
const library = document.createElement('jf-library-surface');
library.host_ = createMockHostApi({data:{fetch:(path,init)=>fetch(path,init)}});
document.body.append(document.createElement('jf-authorization-host'), library);
document.body.dataset.ready = 'true';
</script></body></html>`;
const row = (revision = 3) => ({ operationKey: 'import-one', interruptionRevision: revision,
  reason: 'RECOVERY_AUTHORIZATION_REFUSED', folders: [{ path: '/original/private', collection: 'frozen',
    force: true, watch: false, excludePatterns: ['*.secret'], excludeLiterals: [] }], resumeAvailable: true });
let server: ViteDevServer, browserServer: BrowserServer, browser: Browser, context: BrowserContext;
let output: string, url: string;
let imports: ReturnType<typeof row>[], invocations: Record<string, unknown>[], reads: number;
let holdInvocation: Promise<void> | null, releaseInvocation: (() => void) | undefined;
let holdRead: Promise<void> | null, releaseRead: (() => void) | undefined, heldReads: number;
let outcome: 'success' | 'stale', failRead: boolean;
const panel = (page: Page) => page.locator('jf-interrupted-imports');
const resume = (page: Page) => panel(page).getByRole('button', { name: 'Resume', exact: true });
async function respond(route: Route, body: unknown, status = 200) {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

beforeAll(async () => {
  const root = path.resolve('../..', 'tmp'); await mkdir(root, { recursive: true });
  output = await mkdtemp(path.join(root, 'interrupted-imports-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: output, publicDir: false,
    logLevel: 'silent', server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'interrupted-imports-harness',
      configureServer(vite) { vite.middlewares.use('/interrupted-library', (_req, res) => {
        res.setHeader('Content-Type', 'text/html'); res.end(HARNESS);
      }); },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/interrupted-library`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  browser = await chromium.connect(browserServer.wsEndpoint());
}, 60_000);

beforeEach(async () => {
  imports = [row()]; invocations = []; reads = 0; heldReads = 0; failRead = false; outcome = 'success';
  holdInvocation = null; releaseInvocation = undefined; holdRead = null; releaseRead = undefined;
  context = await browser.newContext();
  await context.route(target => target.pathname.startsWith('/api/'), async route => {
    const request = route.request(), endpoint = new URL(request.url()).pathname;
    if (endpoint === '/api/indexing/imports/interrupted') {
      reads++; const snapshot = structuredClone(imports);
      if (holdRead) { const held = holdRead; holdRead = null; heldReads++; await held; }
      return respond(route, failRead ? { error: 'Unavailable' } : { imports: snapshot }, failRead ? 503 : 200);
    }
    if (endpoint === '/api/operations/core.resume-import/invoke') {
      const body = request.postDataJSON(); invocations.push(body);
      if (!body.confirmationToken) return respond(route, { success: false, errorClass: 'CONFIRMATION_REQUIRED',
        pendingId: `pending-${invocations.length}`, gateBehavior: 'INLINE_CONFIRM', argsSummary: '/original/private',
        riskTier: 'MEDIUM', message: 'Approval is needed to resume this import.' }, 428);
      if (holdInvocation) await holdInvocation;
      if (outcome === 'stale') { imports = [row(4)]; return respond(route, { success: false,
        errorClass: 'HANDLER_FAILURE', errorCode: 'IMPORT_RESUME_STALE', message: 'Internal support details' }, 409); }
      imports = []; return respond(route, { success: true, message: 'Resumed' });
    }
    if (endpoint === '/api/authorizations/approve') return respond(route, { capsule: 'approved' });
    if (endpoint.endsWith('/reject')) return respond(route, { rejected: true });
    if (endpoint === '/api/settings/v2') return respond(route, { ui: { excludePatterns: [] },
      witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
    if (endpoint === '/api/indexing/excludes') return respond(route, { source: 'settings.json', sourceDetail: null, patterns: [] });
    if (endpoint === '/api/indexing-roots/substrate') return respond(route, { items: [] });
    if (endpoint === '/api/indexing/forgets') return respond(route, { items: [], complete: true, nextId: null });
    return respond(route, { entries: [], results: [], facets: {}, jobs: [], count: 0 });
  });
});
afterEach(async () => { releaseInvocation?.(); releaseRead?.(); await context?.close(); });
afterAll(async () => {
  await browser?.close(); await browserServer?.close(); await server?.close();
  if (output) await rm(output, { recursive: true, force: true });
});

async function open() {
  const page = await context.newPage(); page.setDefaultTimeout(5000);
  await page.goto(url, { timeout: 20_000 });
  await page.locator('body[data-ready="true"]').waitFor({ state: 'attached', timeout: 20_000 });
  await resume(page).waitFor(); return page;
}
async function focus(page: Page) {
  await page.bringToFront(); await page.evaluate(() => window.dispatchEvent(new FocusEvent('focus')));
}
async function approve(page: Page) {
  const dialog = page.locator('jf-authorization-host dialog[open]');
  await dialog.waitFor();
  expect(await dialog.textContent()).toContain('/original/private');
  // The shared security dialog intentionally ignores the activation that opened it for 400ms.
  await page.waitForTimeout(450);
  await dialog.getByRole('button', { name: 'Approve', exact: true }).click();
}
async function tab(page: Page, activeTab: string) {
  await page.locator('jf-library-surface').evaluate((element, value) => {
    (element as HTMLElement & { activeTab: string }).activeTab = value;
  }, activeTab);
}

it('a real untrusted Resume opens normal consent, coalesces two presses and refreshes a second Library window on focus', async () => {
  const first = await open(), second = await open();
  await resume(first).click();
  await pageButtonPressAgain(first);
  expect(invocations).toHaveLength(1);
  expect(await panel(first).getByRole('button').isDisabled()).toBe(true);
  await approve(first);
  await expect.poll(() => invocations.length).toBe(2);
  expect(invocations[1]).toEqual({ args: { operationKey: 'import-one', interruptionRevision: 3 }, confirmationToken: 'approved' });
  await expect.poll(() => panel(first).locator('section').count()).toBe(0);
  expect(await resume(second).count()).toBe(1);
  await focus(second); await expect.poll(() => panel(second).locator('section').count()).toBe(0);
}, 30_000);

async function pageButtonPressAgain(page: Page) {
  // Deliver another activation immediately, including before the native disabled render settles.
  await panel(page).locator('jf-button').evaluate(button => {
    void (button as HTMLElement & { onActivate: () => Promise<void> }).onActivate();
  });
}

it('reload during an accepted Resume uses the retained inventory and does not silently re-invoke', async () => {
  const page = await open();
  holdInvocation = new Promise(resolve => { releaseInvocation = resolve; });
  await resume(page).click(); await approve(page);
  await expect.poll(() => invocations.length).toBe(2);
  await page.reload(); await resume(page).waitFor();
  expect(invocations).toHaveLength(2);
  expect(await panel(page).locator('section').textContent()).toContain('/original/private');
  releaseInvocation!(); await expect.poll(() => imports.length).toBe(0);
  await focus(page); await expect.poll(() => panel(page).locator('section').count()).toBe(0);
}, 30_000);

it('a stale revision stays visible with a short error, refreshes, and uses the new revision on retry', async () => {
  const page = await open(); outcome = 'stale';
  const before = reads; await resume(page).click(); await approve(page);
  await expect.poll(() => panel(page).getByRole('alert').textContent()).toContain('changed');
  expect(reads).toBeGreaterThan(before); await expect.poll(() => resume(page).isEnabled()).toBe(true);
  outcome = 'success'; await resume(page).click(); await approve(page);
  await expect.poll(() => invocations.length).toBe(4);
  expect(invocations[2]).toEqual({ args: { operationKey: 'import-one', interruptionRevision: 4 } });
  await expect.poll(() => panel(page).locator('section').count()).toBe(0);
}, 30_000);

it('a refusal stays visible, and switching away and back reads current inventory', async () => {
  const page = await open(); await resume(page).click();
  const deny = page.locator('jf-authorization-host dialog[open]').getByRole('button', { name: 'Deny', exact: true });
  await deny.waitFor(); await page.waitForTimeout(450); await deny.click();
  await expect.poll(() => panel(page).getByRole('alert').textContent()).toContain('not approved');
  expect(invocations).toHaveLength(1);
  await tab(page, 'core.browse-surface'); await expect.poll(() => panel(page).count()).toBe(0);
  imports = [row(4)]; const before = reads;
  await tab(page, 'folders'); await resume(page).waitFor(); expect(reads).toBeGreaterThan(before);
  await resume(page).click();
  await expect.poll(() => invocations.length).toBe(2);
  expect(invocations[1]).toEqual({ args: { operationKey: 'import-one', interruptionRevision: 4 } });
}, 30_000);

it('an old inventory response cannot resurrect a finished import; failed refresh renders no empty-state claim', async () => {
  const page = await open();
  holdRead = new Promise(resolve => { releaseRead = resolve; });
  await focus(page); await expect.poll(() => heldReads).toBe(1);
  imports = []; await focus(page); await expect.poll(() => panel(page).locator('section').count()).toBe(0);
  releaseRead!(); await page.waitForTimeout(100);
  expect(await panel(page).locator('section').count()).toBe(0);
  imports = [row()]; await focus(page); await resume(page).waitFor();
  failRead = true; await focus(page); await expect.poll(() => panel(page).locator('section').count()).toBe(0);
  expect((await panel(page).textContent())!.trim()).toBe('');
}, 30_000);
