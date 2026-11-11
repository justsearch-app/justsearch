// SPDX-License-Identifier: Apache-2.0
// Shell S6 SP8: the palette's real layout in the overlay host's center slot, in real Chromium.
// Run: npx vitest run --config vitest.config.lockdown.ts src/shell-v0/commands/CommandPalette.layout-lockdown.test.ts
// Requires the same installed Chromium as ConfirmDialog.viewport-lockdown.test.ts; no Engine is needed.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import path from 'node:path';

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let context: BrowserContext | undefined;
let output: string;
let url: string;

beforeAll(async () => {
  const temporaryRoot = path.resolve('../..', 'tmp');
  await mkdir(temporaryRoot, { recursive: true });
  output = await mkdtemp(path.join(temporaryRoot, 'palette-layout-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'isolated-palette-layout',
      configureServer(vite) {
        vite.middlewares.use('/palette-layout', (_req, res) => {
          res.setHeader('Content-Type', 'text/html');
          // The app's composition: inside the shell's shadow root, the palette is an occupant of the
          // overlay host's center slot (so the token sheet's document-level reset does not reach it).
          res.end(`<!doctype html><html><head>
            <link rel="stylesheet" href="/src/styles/tokens.css">
            </head><body><div id="shell"></div>
            <script type="module">
            import '/src/shell-v0/chrome/OverlayHost.ts';
            import '/src/shell-v0/commands/CommandPalette.ts';
            const root = document.querySelector('#shell').attachShadow({ mode: 'open' });
            root.innerHTML = '<jf-overlay-host><jf-command-palette slot="center"></jf-command-palette></jf-overlay-host>';
            await customElements.whenDefined('jf-command-palette');
            root.querySelector('jf-command-palette').show();
            document.body.dataset.ready = 'true';
            </script></body></html>`);
        });
        // No backend: the palette's provider reads get an empty, valid answer.
        vite.middlewares.use('/api', (_req, res) => {
          res.setHeader('Content-Type', 'application/json');
          res.end('{}');
        });
      },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/palette-layout`;
  // Playwright creates its fresh --user-data-dir under the caller's short TMPDIR.
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: process.env.TMPDIR ?? output };
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

/** The palette's dialog: the native <dialog> (before it was one, the panel carried role dialog). */
const PANEL = ':is(dialog, [role="dialog"])';

async function openPalette(): Promise<Page> {
  context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const page = await context.newPage();
  await page.goto(url);
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 30_000 });
  await page.locator(`#shell jf-command-palette ${PANEL}`).waitFor();
  return page;
}

/** The panel's box, and what the palette's own rules ask of it at this size and text size. */
const measure = (page: Page) => page.evaluate((selector) => {
  const palette = document.querySelector('#shell')!.shadowRoot!.querySelector('jf-command-palette')!;
  const panel = palette.shadowRoot!.querySelector(selector)!.getBoundingClientRect();
  const rem = parseFloat(getComputedStyle(document.documentElement).fontSize);
  const top = matchMedia('(max-height: 30rem)').matches ? 0.5 * rem : Math.min(0.15 * innerHeight, 8 * rem);
  return { left: panel.left, right: panel.right, top: panel.top, bottom: panel.bottom, width: panel.width,
    viewport: { width: innerWidth, height: innerHeight }, expected: { width: Math.min(40 * rem, innerWidth - rem), top } };
}, PANEL);

// One case per theme: each boots the app (openPalette waits up to 30 s for it), so a single case
// for all three themes outran Vitest's default 5 s limit on loaded CI runners.
for (const theme of ['light', 'dark', 'forced-colors'] as const) it(`SP8 the palette is horizontally centred inside the viewport at every size and text size in ${theme}, its width and top unchanged`, async () => {
  const page = await openPalette();
  {
    await page.emulateMedia({ colorScheme: theme === 'light' ? 'light' : 'dark', forcedColors: theme === 'forced-colors' ? 'active' : 'none' });
    await page.evaluate(value => { document.documentElement.dataset.theme = value; }, theme === 'light' ? 'light' : 'dark');
    for (const text of ['100%', '200%']) {
      await page.evaluate(size => { document.documentElement.style.fontSize = size; }, text);
      for (const [width, height] of [[1280, 720], [900, 700], [600, 400]] as const) {
        await page.setViewportSize({ width, height });
        const box = await measure(page);
        const where = `${theme}, ${width}x${height}, text ${text}: ${JSON.stringify(box)}`;
        expect(box.left, `centred: ${where}`).toBeCloseTo(width - box.right, 0);
        expect(box.left, `inside the viewport: ${where}`).toBeGreaterThanOrEqual(0);
        expect(box.right, `inside the viewport: ${where}`).toBeLessThanOrEqual(width);
        expect(box.bottom, `inside the viewport: ${where}`).toBeLessThanOrEqual(height);
        expect(box.width, `width unchanged: ${where}`).toBeCloseTo(box.expected.width, 0);
        expect(box.top, `top unchanged: ${where}`).toBeCloseTo(box.expected.top, 0);
      }
    }
  }
}, 30_000);
