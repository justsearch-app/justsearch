// SPDX-License-Identifier: Apache-2.0
// Real pointer and keyboard activation in headless Chromium: one interaction answers one prompt.
// Run: npx vitest run --config vitest.config.lockdown.ts src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts
// Requires the same installed Chromium as boot-lockdown.test.ts; no Engine is needed.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import path from 'node:path';

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let context: BrowserContext;
let output: string;
let url: string;

interface ConsentState { granted: Record<string, string | null>; pending: string[]; stored: string }
declare global {
  interface Window {
    requestPlugin(id: string): void;
    consentState(): ConsentState;
  }
}

beforeAll(async () => {
  const temporaryRoot = path.resolve('../..', 'tmp');
  await mkdir(temporaryRoot, { recursive: true });
  output = await mkdtemp(path.join(temporaryRoot, 'authorization-activation-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'isolated-authorization-host',
      configureServer(vite) {
        vite.middlewares.use('/authorization-activation', (_req, res) => {
          res.setHeader('Content-Type', 'text/html');
          res.end(`<!doctype html><html style="font-size:16px"><head>
            <link rel="stylesheet" href="/src/styles/tokens.css">
            </head><body><script type="module">
            import '/src/shell-v0/components/AuthorizationHost.ts';
            import { requestCapability, checkCapability, listPendingConsentRequests, restoreConsentFromStorage }
              from '/src/shell-v0/substrates/consent/index.ts';
            restoreConsentFromStorage();
            window.requestPlugin = id => { void requestCapability({ contributorId: 'plugin.' + id, capability: 'fs.read' }); };
            window.consentState = () => ({
              granted: Object.fromEntries(['first', 'second'].map(id => [id, checkCapability('plugin.' + id, 'fs.read') ?? null])),
              pending: listPendingConsentRequests().map(request => request.contributorId),
              stored: JSON.stringify(localStorage),
            });
            document.body.append(document.createElement('jf-authorization-host'));
            document.body.dataset.ready = 'true';
            </script></body></html>`);
        });
      },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/authorization-activation`;
  // Playwright creates its fresh --user-data-dir under the caller's short TMPDIR.
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: process.env.TMPDIR ?? output };
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

/** Two plugin capability requests queued; the first prompt has been on screen long enough to arm. */
async function twoQueued(): Promise<Page> {
  context = await browser.newContext({ viewport: { width: 1024, height: 768 } });
  const page = await context.newPage();
  await page.goto(url);
  await page.locator('body[data-ready="true"]').waitFor();
  await page.evaluate(() => { window.requestPlugin('first'); window.requestPlugin('second'); });
  await page.getByText('plugin.first requests permission').waitFor();
  await page.waitForTimeout(600);
  return page;
}

const state = (page: Page) => page.evaluate(() => window.consentState());

async function afterReload(page: Page): Promise<ConsentState> {
  await page.reload();
  await page.locator('body[data-ready="true"]').waitFor();
  return state(page);
}

it('a native double-click on Allow always grants only the first plugin, across reload', async () => {
  const page = await twoQueued();
  await page.getByTestId('capability-allow-always').dblclick();
  await page.waitForTimeout(600);
  const now = await state(page);
  expect(now.granted).toEqual({ first: 'allow-always', second: null });
  expect(now.pending).toEqual(['plugin.second']);
  const reloaded = await afterReload(page);
  expect(reloaded.granted).toEqual({ first: 'allow-always', second: null });
  expect(reloaded.stored).not.toContain('plugin.second');
});

it('repeated native activation of the same button answers one prompt', async () => {
  const page = await twoQueued();
  const box = (await page.getByTestId('capability-allow-always').boundingBox())!;
  for (let i = 0; i < 4; ++i) {
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
    await page.waitForTimeout(60);
  }
  await page.waitForTimeout(600);
  const now = await state(page);
  expect(now.granted).toEqual({ first: 'allow-always', second: null });
  expect(now.pending).toEqual(['plugin.second']);
  expect((await afterReload(page)).granted.second).toBeNull();
});

it('Enter pressed and held on the next prompt before it arms answers nothing', async () => {
  const page = await twoQueued();
  await page.getByTestId('capability-allow-always').locator('button').focus();
  await page.keyboard.press('Enter');
  await page.getByText('plugin.second requests permission').waitFor();
  expect((await state(page)).granted.first).toBe('allow-always');
  // Straight onto the next prompt's first decision button, then the key is held: one keydown, the
  // platform's auto-repeat keydowns, the release.
  await page.keyboard.press('Tab');
  await page.keyboard.down('Enter');
  for (let i = 0; i < 12; ++i) {
    await page.waitForTimeout(50);
    await page.keyboard.down('Enter');
  }
  await page.keyboard.up('Enter');
  await page.waitForTimeout(100);
  const now = await state(page);
  expect(now.granted.second).toBeNull();
  expect(now.pending).toEqual(['plugin.second']);
});

it('a fresh Enter press after the delay answers the next prompt exactly once', async () => {
  const page = await twoQueued();
  await page.getByTestId('capability-allow-always').locator('button').focus();
  await page.keyboard.press('Enter');
  await page.getByText('plugin.second requests permission').waitFor();
  await page.keyboard.press('Tab');
  await page.waitForTimeout(450);
  await page.keyboard.press('Enter');
  await page.waitForTimeout(100);
  const now = await state(page);
  expect(now.granted).toEqual({ first: 'allow-always', second: 'deny' });
  expect(now.pending).toEqual([]);
});

it('a deliberate click after the delay answers the next prompt exactly once', async () => {
  const page = await twoQueued();
  await page.getByTestId('capability-allow-always').click();
  await page.getByText('plugin.second requests permission').waitFor();
  await page.waitForTimeout(450);
  await page.getByTestId('capability-deny').click();
  await page.waitForTimeout(100);
  const now = await state(page);
  expect(now.granted).toEqual({ first: 'allow-always', second: 'deny' });
  expect(now.pending).toEqual([]);
  expect(await page.locator('jf-authorization-host').evaluate(host => host.shadowRoot!.querySelector('dialog')!.open)).toBe(false);
});
