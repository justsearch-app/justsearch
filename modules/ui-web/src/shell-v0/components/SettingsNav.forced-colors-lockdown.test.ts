// SPDX-License-Identifier: Apache-2.0
// Real Chromium, private Vite server; run with vitest.config.lockdown.ts.
import { afterAll, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserServer } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let output: string;
let url: string;

beforeAll(async () => {
  output = await mkdtemp(path.join(os.tmpdir(), 'settings-nav-colors-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'settings-nav-colors-harness',
      configureServer(vite) {
        vite.middlewares.use((req, res, next) => {
          if (req.url !== '/nav') return next();
          res.setHeader('Content-Type', 'text/html');
          res.end(`<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
import '/src/shell-v0/components/SettingsNav.ts';
import { SETTINGS_REGISTER } from '/src/shell-v0/views/settingsRegister.ts';
const nav = document.createElement('jf-settings-nav');
nav.register = SETTINGS_REGISTER;
nav.activeCategory = 'general';
nav.addEventListener('category-select', event => { nav.activeCategory = event.detail.id; });
document.body.append(nav);
</script></body></html>`);
        });
      },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/nav`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: process.env.TMPDIR ?? output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned headless Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
}, 60_000);

afterAll(async () => {
  await browser?.close();
  await browserServer?.close();
  await server?.close();
  if (output) await rm(output, { recursive: true, force: true });
});

it.each(['light', 'dark'] as const)('Data & reset uses system selection and keyboard focus colours on a %s forced-colours palette', async (colorScheme) => {
  const context = await browser.newContext({ forcedColors: 'active', colorScheme });
  try {
    const page = await context.newPage();
    await page.goto(url);
    const row = page.locator('jf-settings-nav [data-settings-category="data-reset"]');
    await row.waitFor();
    const look = () => row.evaluate(el => {
      const c = getComputedStyle(el);
      return { background: c.backgroundColor, text: c.color, outline: c.outlineColor,
        adjustment: c.forcedColorAdjust, focused: el.matches(':focus-visible'),
        outlineStyle: c.outlineStyle, outlineWidth: c.outlineWidth };
    });
    // Inactive danger rows, including hover, leave the palette to the browser.
    expect((await look()).adjustment).toBe('auto');
    await row.hover();
    expect((await look()).adjustment).toBe('auto');
    await page.mouse.move(0, 0);
    // Tab enters search, then the roving category list; End reaches About, Up reaches Data & reset.
    await page.keyboard.press('Tab');
    await page.keyboard.press('Tab');
    await page.keyboard.press('End');
    await page.keyboard.press('ArrowUp');
    await page.keyboard.press('ArrowUp');
    await page.keyboard.press('ArrowUp');
    expect((await look()).focused).toBe(true);
    await page.keyboard.press('Enter');
    await expect.poll(() => row.getAttribute('aria-current')).toBe('true');
    const system = await page.evaluate(() => {
      const probe = document.createElement('div');
      probe.style.cssText = 'forced-color-adjust: none; background: Highlight; color: HighlightText';
      document.body.append(probe);
      const c = getComputedStyle(probe);
      const result = { background: c.backgroundColor, text: c.color, outline: c.color };
      probe.remove();
      return result;
    });
    for (const hovered of [false, true]) {
      if (hovered) await row.hover();
      expect(await look()).toEqual({ ...system, adjustment: 'none', focused: true,
        outlineStyle: 'solid', outlineWidth: '2px' });
    }
  } finally {
    await context.close();
  }
}, 60_000);
