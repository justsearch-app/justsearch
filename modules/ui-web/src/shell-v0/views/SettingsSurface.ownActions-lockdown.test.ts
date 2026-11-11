// SPDX-License-Identifier: Apache-2.0
// DE1/C1/X1 through the rendered Settings surface in isolated Chromium. No Engine or shared stack.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import type { SettingsSurface } from './SettingsSurface.js';

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let context: BrowserContext | undefined;
let output: string;
let url: string;

declare global {
  interface Window {
    prepareOwnThemes(active: boolean): Promise<void>;
    prepareOwnLibrary(): Promise<void>;
    renameOwnTheme(name: string): Promise<void>;
    exhaustOwnThemeStorage(): string;
    ownThemeState(): { stored: string | null; ids: readonly string[]; active: string | null; styled: boolean };
  }
}

beforeAll(async () => {
  output = await mkdtemp(path.join(tmpdir(), 'settings-own-actions-'));
  server = await createServer({
    configFile: false, root: process.cwd(), cacheDir: output, publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'isolated-settings-own-actions',
      configureServer(vite) {
        vite.middlewares.use('/own-actions', (_req, res) => {
          res.setHeader('Content-Type', 'text/html');
          res.end(`<!doctype html><html><head><meta charset="utf-8">
            <link rel="stylesheet" href="/src/styles/tokens.css">
            <link rel="stylesheet" href="/src/shell-v0/themes/default.css">
            </head><body><script type="module">
            import '/src/shell-v0/views/SettingsSurface.ts';
            import '/src/shell-v0/views/LibrarySurface.ts';
            import '/src/shell-v0/components/StatusPill.ts';
            import { AdvisoryStore } from '/src/shell-v0/components/advisory/AdvisoryStore.ts';
            import { __feedForTest } from '/src/shell-v0/state/aiStateStore.ts';
            import { createMockHostApi } from '/src/shell-v0/plugin-api/testHostApi.ts';
            import { createThemeApi } from '/src/shell-v0/plugin-api/capabilities/theme.ts';
            import { syncCustomThemesToCatalog } from '/src/shell-v0/state/themeState.ts';
            import { mutateDocument, __DOCUMENT_STORAGE_KEY, __getLastPersistError } from '/src/shell-v0/state/UserStateDocument.ts';
            const theme = createThemeApi('CORE', 'own-actions');
            syncCustomThemesToCatalog();
            if (!localStorage.getItem('own-themes-seeded')) {
              for (const name of ['Alpha', 'Beta']) theme.saveTheme({
                id: 'custom.' + name.toLowerCase(), displayName: name, tokens: { 'h-teal': '120' }
              });
              localStorage.setItem('own-themes-seeded', 'true');
            }
            const settings = document.createElement('jf-settings-surface');
            settings.host_ = createMockHostApi({ theme, platform: { capabilities: new Set(['native-notifications']) } });
            const store = new AdvisoryStore(); store.start();
            const pill = document.createElement('jf-status-pill'); pill.store = store;
            document.body.append(pill, settings);
            window.renameOwnTheme = async name => {
              theme.renameTheme('custom.alpha', name); settings.requestUpdate(); await settings.updateComplete;
            };
            window.prepareOwnLibrary = async () => {
              __feedForTest({ status: { worker: { core: { indexState: 'IDLE', indexedDocuments: 12, indexHealthy: true },
                migration: { migrationState: 'IDLE' } }, readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } });
              const library = document.createElement('jf-library-surface');
              library.host_ = createMockHostApi({ data: { fetch: async path => new Response(JSON.stringify(
                path.startsWith('/api/indexing-roots/substrate') ? { items: [{ pathHash: 'a', collection: 'default', fileCount: 12, walkCompleted: true }] } : {})),
                invokeOperation: async () => ({ success: true }) } });
              library.resolvedPaths = { a: '/home/sam/Notes' };
              document.body.prepend(library);
              await new Promise(r => setTimeout(r, 100)); await library.updateComplete;
              for (const toggle of library.shadowRoot.querySelectorAll('.folder-toggle')) toggle.click();
              await library.updateComplete;
            };
            await new Promise(r => setTimeout(r, 50));
            settings.activeCategory = 'appearance'; await settings.updateComplete;
            window.prepareOwnThemes = async active => {
              await theme.selectTheme(active ? 'custom.alpha' : 'custom.beta');
              await settings.updateComplete;
            };
            window.ownThemeState = () => ({ stored: localStorage.getItem(__DOCUMENT_STORAGE_KEY),
              ids: theme.listThemes().map(t => t.id), active: theme.getActiveThemeId(),
              styled: !!document.getElementById('jf-active-theme') });
            window.exhaustOwnThemeStorage = () => {
              // Fill this context's real localStorage. Then an ordinary session-only edit makes
              // the next document write larger than the stored one, even after deleting a theme.
              let low = 0, high = 6 * 1024 * 1024;
              while (low + 1 < high) {
                const mid = Math.floor((low + high) / 2);
                try { localStorage.setItem('quota-fill', 'x'.repeat(mid)); low = mid; }
                catch (error) { if (error.name !== 'QuotaExceededError') throw error; high = mid; }
              }
              mutateDocument(doc => ({ ...doc, recentCommandIds: ['x'.repeat(4096)] }));
              const error = __getLastPersistError();
              return error instanceof DOMException ? error.name : String(error);
            };
            document.body.dataset.ready = 'true';
            </script></body></html>`);
        });
      },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/own-actions`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned headless Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
}, 60_000);

afterEach(async () => { await context?.close(); context = undefined; });
afterAll(async () => {
  await browser?.close();
  await browserServer?.close();
  await server?.close();
  if (output) await rm(output, { recursive: true, force: true });
});

async function open(): Promise<Page> {
  context = await browser.newContext();
  const page = await context.newPage();
  const pageError = new Promise<never>((_resolve, reject) => page.once('pageerror', reject));
  await page.route(new URL('/api/**', url).href, route => route.fulfill({ status: 200, contentType: 'application/json', body: route.request().url().includes('collection-facet') ? '{"totalHits":0,"results":[],"facets":{"collection":{"mcp-ingest":12}}}' : '{"ui":{}}' }));
  await page.goto(url);
  await Promise.race([page.locator('body[data-ready="true"]').waitFor({ state: 'attached', timeout: 45_000 }), pageError]);
  return page;
}

it('DE1 switching from Delete Alpha to Delete Beta leaves one band, focuses Keep it, and Escape restores Delete Beta', async () => {
  const page = await open();
  const alpha = page.locator('jf-button[label="Delete custom theme Alpha"]');
  const beta = page.locator('jf-button[label="Delete custom theme Beta"]');
  const band = page.locator('jf-confirm-band.theme-delete-confirm');
  const keep = band.locator('jf-button.keep button');
  await alpha.click();
  await expect.poll(() => keep.evaluate(el => el.matches(':focus'))).toBe(true);
  await beta.click();
  expect(await band.count()).toBe(1);
  expect(await band.getAttribute('action-label')).toBe('Delete Beta');
  await expect.poll(() => keep.evaluate(el => el.matches(':focus'))).toBe(true);
  await page.keyboard.press('Escape');
  await expect.poll(() => band.count()).toBe(0);
  expect(await beta.locator('button').evaluate(el => el.matches(':focus'))).toBe(true);
  expect((await page.evaluate(() => window.ownThemeState())).ids).toEqual(expect.arrayContaining(['custom.alpha', 'custom.beta']));
}, 60_000);

it.each([false, true])('C1 a real quota failure keeps the custom theme and its active state, reports it by the grid, and permits retry (active=%s)', async active => {
  const page = await open();
  await page.evaluate(value => window.prepareOwnThemes(value), active);
  const before = await page.evaluate(() => window.ownThemeState());
  expect(before.stored).toContain('custom.alpha');
  expect(before.active).toBe(active ? 'custom.alpha' : 'custom.beta');
  expect(before.styled).toBe(true);
  expect(await page.evaluate(() => window.exhaustOwnThemeStorage())).toBe('QuotaExceededError');
  const del = page.locator('jf-button[label="Delete custom theme Alpha"]');
  await del.click();
  await page.locator('jf-confirm-band.theme-delete-confirm jf-button.confirm').click();
  await expect.poll(() => page.evaluate(() => window.ownThemeState())).toEqual(before);
  expect(await del.count()).toBe(1);
  const error = page.locator('[data-testid="settings-themes"] jf-error-alert.theme-delete-error');
  expect(await error.textContent()).toContain("Couldn't delete Alpha");
  expect(await error.textContent()).toContain('kept');
  await page.evaluate(() => localStorage.removeItem('quota-fill'));
  await del.click();
  await page.locator('jf-confirm-band.theme-delete-confirm jf-button.confirm').click();
  await expect.poll(() => del.count()).toBe(0);
  expect(await error.count()).toBe(0);
  const after = await page.evaluate(() => window.ownThemeState());
  expect(after.ids).not.toContain('custom.alpha');
  expect(after.stored).not.toContain('custom.alpha');
  expect(after.active).toBe(active ? null : 'custom.beta');
  expect(after.styled).toBe(!active);
  await page.reload();
  await page.locator('body[data-ready="true"]').waitFor({ state: 'attached' });
  expect((await page.evaluate(() => window.ownThemeState())).ids).not.toContain('custom.alpha');
  expect((await page.evaluate(() => window.ownThemeState())).active).toBe(after.active);
}, 60_000);

it('X1 the typed delete input uses the spacing token through its class', async () => {
  const page = await open();
  await page.evaluate(async () => {
    const settings = document.querySelector<SettingsSurface>('jf-settings-surface')!;
    settings.activeCategory = 'data-reset'; await settings.updateComplete;
  });
  await page.locator('jf-button[label="Delete all local data"]').click();
  const input = page.locator('#delete-data-typed');
  const original = await input.evaluate(el => getComputedStyle(el).flexBasis);
  expect(original).toBe('192px');
  await page.evaluate(() => document.querySelector<SettingsSurface>('jf-settings-surface')!.style.setProperty('--jf-space-8', '40px'));
  expect(await input.evaluate(el => getComputedStyle(el).flexBasis)).toBe('240px');
  expect(await input.getAttribute('class')).toBe('typed-input');
}, 60_000);


it('DE1 folder, collection and theme bands share one window slot and Escape restores the latest opener', async () => {
  const page = await open();
  await page.evaluate(() => window.prepareOwnLibrary());
  const library = page.locator('jf-library-surface');
  await library.locator('.bottom-links summary').filter({ hasText: 'Added by other apps' }).click();
  const folder = library.locator('.card jf-button[label="Remove from library"]').first();
  const collection = library.locator('jf-button[label="Remove mcp-ingest"]');
  const theme = page.locator('jf-button[label="Delete custom theme Alpha"]');
  for (const [previous, next, label] of [
    [folder, collection, 'Remove mcp-ingest'],
    [collection, folder, 'Remove Notes'],
    [folder, theme, 'Delete Alpha'],
    [theme, collection, 'Remove mcp-ingest'],
  ] as const) {
    await previous.click();
    await expect.poll(() => page.locator('jf-confirm-band jf-button.keep button').evaluate(el => el.matches(':focus'))).toBe(true);
    await next.click();
    await expect.poll(() => page.locator('jf-confirm-band').count()).toBe(1);
    expect(await page.locator('jf-confirm-band').getAttribute('action-label')).toBe(label);
    await expect.poll(() => page.locator('jf-confirm-band jf-button.keep button').evaluate(el => el.matches(':focus'))).toBe(true);
    await page.keyboard.press('Escape');
    await expect.poll(() => page.locator('jf-confirm-band').count()).toBe(0);
    expect(await next.locator('button').evaluate(el => el.matches(':focus'))).toBe(true);
  }
}, 60_000);

it('C1 deleting Alpha shows a success receipt in the pill and announces it politely', async () => {
  const page = await open();
  await page.locator('jf-button[label="Delete custom theme Alpha"]').click();
  await page.locator('jf-confirm-band jf-button.confirm').click();
  const pill = page.locator('jf-status-pill');
  await expect.poll(() => pill.locator('span[role="status"][aria-live="polite"]').textContent()).toContain('Alpha deleted');
  expect(await pill.locator('.trigger, .notice-below').allTextContents()).toEqual(expect.arrayContaining([expect.stringContaining('Alpha deleted')]));
  expect((await page.evaluate(() => window.ownThemeState())).ids).not.toContain('custom.alpha');
}, 60_000);

it.each([[600, 400], [900, 800], [1280, 800]])('X2 a 200-character theme name keeps the band and both answers within %s×%s', async (width, height) => {
  const page = await open();
  await page.setViewportSize({ width, height });
  const name = 'LongTheme'.repeat(22) + 'XX';
  expect(name).toHaveLength(200);
  await page.evaluate(value => window.renameOwnTheme(value), name);
  await page.locator(`jf-button[label="Delete custom theme ${name}"]`).click();
  const band = page.locator('jf-confirm-band');
  const result = await band.evaluate(el => {
    const box = el.shadowRoot!.querySelector<HTMLElement>('.band')!;
    return {
      fits: box.scrollWidth <= box.clientWidth,
      inside: [box, ...box.querySelectorAll('jf-button')].every(button => {
        const rect = button.getBoundingClientRect();
        return rect.width > 0 && rect.left >= 0 && rect.right <= innerWidth;
      }),
    };
  });
  expect(result).toEqual({ fits: true, inside: true });
  expect(await band.locator('jf-button.confirm').getAttribute('title')).toBe(`Delete ${name}`);
  expect(await band.getByRole('button', { name: `Delete ${name}`, exact: true }).count()).toBe(1);
  await band.locator('jf-button.keep').evaluate(el => el.scrollIntoView({ block: 'center' }));
  const keepBox = await band.locator('jf-button.keep').boundingBox();
  expect(keepBox!.y).toBeGreaterThanOrEqual(0);
  expect(keepBox!.y + keepBox!.height).toBeLessThanOrEqual(height);
}, 60_000);
