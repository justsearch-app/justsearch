// SPDX-License-Identifier: Apache-2.0
// L21 — a confirmed delete in Settings hands keyboard focus to a neighbour, never the page. Rendered
// Settings surface in headless Chromium over a private Vite server; no Engine, no shared dev stack. Run with
// npx vitest run --config vitest.config.lockdown.ts src/shell-v0/views/SettingsSurface.focusKeeper-lockdown.test.ts
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

let server: ViteDevServer, browserServer: BrowserServer, browser: Browser;
let context: BrowserContext | undefined;
let output: string, url: string;
interface Focus { readonly page: boolean; readonly control: string | null }
declare global {
  interface Window { focusNow(): Focus; themeIds(): string[] }
}
beforeAll(async () => {
  output = await mkdtemp(path.join(tmpdir(), 'settings-focus-keeper-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: output, publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'isolated-settings-focus',
      configureServer(vite) { vite.middlewares.use('/settings-focus', (_req, res) => {
        res.setHeader('Content-Type', 'text/html');
        res.end(`<!doctype html><html><head><meta charset="utf-8">
          <link rel="stylesheet" href="/src/styles/tokens.css">
          <link rel="stylesheet" href="/src/shell-v0/themes/default.css"></head><body><script type="module">
          import '/src/shell-v0/views/SettingsSurface.ts';
          import { createMockHostApi } from '/src/shell-v0/plugin-api/testHostApi.ts';
          import { createThemeApi } from '/src/shell-v0/plugin-api/capabilities/theme.ts';
          import { syncCustomThemesToCatalog } from '/src/shell-v0/state/themeState.ts';
          const theme = createThemeApi('CORE', 'settings-focus');
          syncCustomThemesToCatalog();
          for (const name of ['Alpha', 'Beta', 'Gamma']) theme.saveTheme({ id: 'custom.' + name.toLowerCase(), displayName: name, tokens: { 'h-teal': '120' } });
          const settings = document.createElement('jf-settings-surface');
          settings.host_ = createMockHostApi({ theme });
          document.body.append(settings);
          await new Promise(r => setTimeout(r, 50));
          settings.activeCategory = 'appearance'; await settings.updateComplete;
          window.themeIds = () => theme.listThemes().map(t => t.id);
          window.focusNow = () => {
            let active = document.activeElement;
            while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
            let control = null;
            for (let at = active; at && !control; at = at.parentNode ?? at.host ?? null) {
              if (at instanceof Element) control = at.getAttribute('label') || at.getAttribute('aria-label') || null;
            }
            return { page: !active || active === document.body || active === document.documentElement, control };
          };
          document.body.dataset.ready = 'true';
          </script></body></html>`);
      }); },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/settings-focus`;
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
async function open(): Promise<Page> {
  context = await browser.newContext({ viewport: { width: 1280, height: 1600 } });
  const page = await context.newPage();
  page.setDefaultTimeout(3000);
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route(new URL('/api/**', url).href, route => route.fulfill({ status: 200, contentType: 'application/json', body: '{"ui":{}}' }));
  await page.goto(url, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ state: 'attached', timeout: 45_000 });
  expect(errors).toEqual([]);
  return page;
}

it('L21 a confirmed delete of the last custom theme by keyboard hands focus to a neighbour, never the page', async () => {
  const page = await open();
  // The last card: no later card can take its place.
  await page.locator('jf-button[label="Delete custom theme Gamma"] button').focus();
  await page.keyboard.press('Enter');
  const band = page.locator('jf-confirm-band.theme-delete-confirm');
  await expect.poll(() => band.locator('jf-button.keep button').evaluate(el => el.matches(':focus'))).toBe(true);
  await page.keyboard.press('Shift+Tab'); await page.keyboard.press('Enter');
  await expect.poll(() => page.evaluate(() => window.themeIds())).not.toContain('custom.gamma');
  await expect.poll(() => band.count()).toBe(0);
  await expect.poll(() => page.evaluate(() => window.focusNow())).toMatchObject({ page: false });
  expect((await page.evaluate(() => window.focusNow())).control, 'a named control has focus').not.toBeNull();
}, 30_000);
