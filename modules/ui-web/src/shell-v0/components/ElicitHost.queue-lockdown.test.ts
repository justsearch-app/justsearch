// SPDX-License-Identifier: Apache-2.0
// C2 in headless Chromium: queued required elicitation prompts survive navigation and remount, and
// each answers on a deliberate Submit, so no caller hangs.
// Run: npx vitest run --config vitest.config.lockdown.ts src/shell-v0/components/ElicitHost.queue-lockdown.test.ts
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

declare global {
  interface Window {
    ask(title: string): void;
    elicited: Record<string, unknown>;
    leave(): void;
    back(fresh: boolean): void;
  }
}

beforeAll(async () => {
  const temporaryRoot = path.resolve('../..', 'tmp');
  await mkdir(temporaryRoot, { recursive: true });
  output = await mkdtemp(path.join(temporaryRoot, 'elicit-queue-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'isolated-elicit-host',
      configureServer(vite) {
        vite.middlewares.use('/elicit-queue', (_req, res) => {
          res.setHeader('Content-Type', 'text/html');
          res.end(`<!doctype html><html style="font-size:16px"><head>
            <link rel="stylesheet" href="/src/styles/tokens.css">
            </head><body><main id="center"></main><script type="module">
            import '/src/shell-v0/components/ElicitHost.ts';
            import '/src/shell-v0/renderers/controls/TextControl.ts';
            import '/src/shell-v0/renderers/layouts/VerticalLayout.ts';
            import { elicit } from '/src/shell-v0/substrates/elicit/index.ts';
            window.elicited = {};
            // A required prompt (no Cancel), as an Action handler asks it; its answer is recorded.
            window.ask = title => {
              void elicit({ title, schema: { type: 'object', properties: { name: { type: 'string' } } },
                initialData: { name: title.toLowerCase() }, cancelLabel: null })
                .then(value => { window.elicited[title] = value; });
            };
            const center = document.getElementById('center');
            let host = document.createElement('jf-elicit-host');
            center.append(host);
            // Navigating away takes the host out of the page; coming back re-attaches it or renders a new one.
            window.leave = () => host.remove();
            window.back = fresh => { if (fresh) host = document.createElement('jf-elicit-host'); center.append(host); };
            document.body.dataset.ready = 'true';
            </script></body></html>`);
        });
      },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/elicit-queue`;
  // Playwright creates its fresh --user-data-dir under the caller's short TMPDIR.
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: process.env.TMPDIR ?? output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned headless Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
  // Load the page once so Vite's first transform is not paid inside the first case's time limit.
  const warm = await browser.newContext();
  const page = await warm.newPage();
  await page.goto(url);
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await warm.close();
}, 60_000);

afterEach(async () => { await context?.close(); });
afterAll(async () => {
  await browser?.close();
  await browserServer?.close();
  await server?.close();
  if (output) await rm(output, { recursive: true, force: true });
});

async function opened(): Promise<Page> {
  context = await browser.newContext({ viewport: { width: 1024, height: 768 } });
  const page = await context.newPage();
  await page.goto(url);
  await page.locator('body[data-ready="true"]').waitFor();
  return page;
}

it.each([
  ['stays mounted', null],
  ['is re-attached after navigating away and back', false],
  ['is replaced by a new host after navigating away and back', true],
] as const)('C2 two queued required prompts each answer on a deliberate Submit when the host %s', async (_, fresh) => {
  const page = await opened();
  await page.evaluate(() => { window.ask('First'); window.ask('Second'); });
  const dialog = page.locator('jf-elicit-host dialog');
  await dialog.getByRole('heading', { name: 'First' }).waitFor();
  if (fresh !== null) {
    await page.evaluate(() => window.leave());
    await page.waitForTimeout(200);
    await page.evaluate(isFresh => window.back(isFresh), fresh);
  }
  await dialog.getByRole('heading', { name: 'First' }).waitFor();
  expect(await dialog.evaluate(d => d.matches(':modal')), 'the queued prompt is modal again').toBe(true);
  const submit = dialog.getByRole('button', { name: 'Submit' });
  await submit.click();
  await expect.poll(() => page.evaluate(() => window.elicited)).toEqual({ First: { name: 'first' } });
  await dialog.getByRole('heading', { name: 'Second' }).waitFor();
  // Deliberate Submit clicks on the second prompt: the first one answers it.
  for (let i = 0; i < 3 && await dialog.getByRole('heading', { name: 'Second' }).count(); ++i) {
    await page.waitForTimeout(300);
    if (await submit.count()) await submit.click();
  }
  await expect.poll(() => page.evaluate(() => window.elicited), { message: 'its caller resolves' })
    .toEqual({ First: { name: 'first' }, Second: { name: 'second' } });
  await expect.poll(() => dialog.evaluate(d => (d as HTMLDialogElement).open), { message: 'nothing is left on screen' }).toBe(false);
}, 30_000);
