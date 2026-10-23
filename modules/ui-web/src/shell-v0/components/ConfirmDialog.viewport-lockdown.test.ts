// SPDX-License-Identifier: Apache-2.0
// Real layout/native modality uses the existing separate browser test selection.
// Run: npx vitest run --config vitest.config.lockdown.ts src/shell-v0/components/ConfirmDialog.viewport-lockdown.test.ts
// Requires the same installed Chromium as boot-lockdown.test.ts; no Engine is needed.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Locator } from 'playwright-core';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import path from 'node:path';

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let context: BrowserContext;
let output: string;
let url: string;

beforeAll(async () => {
  const temporaryRoot = path.resolve('../..', 'tmp');
  await mkdir(temporaryRoot, { recursive: true });
  output = await mkdtemp(path.join(temporaryRoot, 'confirm-viewport-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'isolated-confirm-dialog',
      configureServer(vite) {
        vite.middlewares.use('/confirm-viewport', (_req, res) => {
          res.setHeader('Content-Type', 'text/html');
          res.end(`<!doctype html><html style="font-size:16px"><head>
            <link rel="stylesheet" href="/src/styles/tokens.css">
            </head><body><button id="invoker">Open confirmation</button>
            <button id="behind">Behind dialog</button><script type="module">
            import { confirmAsync } from '/src/shell-v0/components/ConfirmDialog.ts';
            document.querySelector('#invoker').addEventListener('click', () => {
              void confirmAsync({ title: 'Forget this memory?',
                message: 'Forget this durable fact? This cannot be undone.',
                confirmLabel: 'Forget', cancelLabel: 'Cancel' });
            });
            document.body.dataset.ready = 'true';
            </script></body></html>`);
        });
      },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/confirm-viewport`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned headless Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
}, 30_000);

afterEach(async () => { await context?.close(); });
afterAll(async () => {
  await browser?.close();
  await browserServer?.close();
  await server?.close();
  if (output) await rm(output, { recursive: true, force: true });
});

async function open(width: number) {
  context = await browser.newContext({ viewport: { width, height: 800 } });
  const page = await context.newPage();
  await page.goto(url);
  await page.locator('body[data-ready="true"]').waitFor();
  await page.getByRole('button', { name: 'Open confirmation', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Forget this memory?', exact: true });
  await dialog.waitFor();
  // The primitive focuses its CTA on the next animation frame.
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  return { page, dialog };
}

const focused = (control: Locator) => control.evaluate(el => el.getRootNode() instanceof ShadowRoot
  && (el.getRootNode() as ShadowRoot).activeElement === el);

it('fits narrow viewports with a side gap in every theme (S53.7)', async () => {
  const { page, dialog } = await open(360);
  for (const theme of ['light', 'dark', 'forced-colors']) {
    await page.emulateMedia({ colorScheme: theme === 'light' ? 'light' : 'dark', forcedColors: theme === 'forced-colors' ? 'active' : 'none' });
    await page.evaluate(value => { document.documentElement.dataset.theme = value; }, theme === 'light' ? 'light' : 'dark');
    for (const width of [360, 320, 480, 800]) {
      await page.setViewportSize({ width, height: 800 });
      const bounds = await dialog.boundingBox();
      expect(bounds, `${theme} at ${width}px`).not.toBeNull();
      expect(bounds!.width, `dialog must fit ${width}px with 16px each side`).toBeLessThanOrEqual(width - 32);
      expect(bounds!.x).toBeGreaterThanOrEqual(16);
      expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width - 16);
    }
  }
});

it('preserves the wide dialog width (S53.7 preservation)', async () => {
  const { dialog } = await open(1280);
  // Before: 28rem content at 16px root size, plus two 1px borders.
  expect((await dialog.boundingBox())!.width).toBe(450);
});

it('keeps the page inert and returns from native browser focus with Tab and Shift+Tab (S53.7 preservation)', async () => {
  const { page, dialog } = await open(360);
  const confirm = dialog.getByRole('button', { name: 'Forget', exact: true });
  const close = dialog.getByRole('button', { name: 'Close', exact: true });
  const behind = page.getByRole('button', { name: 'Behind dialog', exact: true });
  await confirm.focus();
  await behind.evaluate(el => el.focus());
  expect(await focused(confirm)).toBe(true);
  await page.keyboard.press('Tab');
  const browserFocus = await page.evaluate(() => document.activeElement === document.body);
  await behind.evaluate(el => el.focus());
  expect(await behind.evaluate(el => document.activeElement === el)).toBe(false);
  await page.keyboard.press('Shift+Tab');
  expect(await focused(confirm)).toBe(true);
  await page.keyboard.press('Tab');
  if (browserFocus) await page.keyboard.press('Tab');
  expect(await focused(close)).toBe(true);
  console.info('Native focus left document for browser controls:', browserFocus,
    '; behind-page focus refused; Shift+Tab returned to Forget; Tab returned to Close.');
});
