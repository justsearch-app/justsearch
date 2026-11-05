// SPDX-License-Identifier: Apache-2.0
// X1/X2 for the confirm and undo tiers in headless Chromium: the in-place band (ref-06), the undo
// notice in the pill (ref-07) and the typed delete-all-data dialog (ref-16) in dark, light and forced
// colours at 1280×800, 900×800 and 600×400, without sideways overflow and with their answers on screen.
// Run: npx vitest run --config vitest.config.lockdown.ts src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts
// Set REVIEW_SHOTS=<dir> to keep screenshots. Requires the installed Chromium; no Engine is needed.
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

declare global {
  interface Window { openBand(): Promise<void>; offerUndo(): Promise<void>; openDelete(): Promise<void> }
}

beforeAll(async () => {
  const temporaryRoot = path.resolve('../..', 'tmp');
  await mkdir(temporaryRoot, { recursive: true });
  output = await mkdtemp(path.join(temporaryRoot, 'confirm-tiers-'));
  server = await createServer({
    configFile: false, root: process.cwd(), cacheDir: output, publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'isolated-confirm-tiers',
      configureServer(vite) {
        vite.middlewares.use('/confirm-tiers', (_req, res) => {
          res.setHeader('Content-Type', 'text/html');
          res.end(`<!doctype html><html style="font-size:16px"><head><meta charset="utf-8">
            <link rel="stylesheet" href="/src/styles/tokens.css">
            <link rel="stylesheet" href="/src/shell-v0/themes/default.css">
            <style>body { margin: 0; background: var(--surface-0); color: var(--text-primary); font-family: system-ui; }
              header { display: flex; justify-content: flex-end; padding: var(--jf-space-2); min-height: var(--frame-topbar-height); }
              main { padding: var(--jf-space-6); } .row { padding: var(--jf-space-3); border-block-end: 1px solid var(--border-subtle); }</style>
            </head><body><header id="bar"></header><main><div class="row">Notes · C:\\Users\\Sam\\Notes</div><div id="slot"></div></main>
            <script type="module">
            import '/src/shell-v0/components/ConfirmBand.ts';
            import '/src/shell-v0/components/StatusPill.ts';
            import '/src/shell-v0/views/SettingsSurface.ts';
            import { AdvisoryStore } from '/src/shell-v0/components/advisory/AdvisoryStore.ts';
            import { offerReversal } from '/src/shell-v0/state/ownReversals.ts';
            import { createMockHostApi } from '/src/shell-v0/plugin-api/testHostApi.ts';
            const store = new AdvisoryStore(); store.start();
            const pill = document.createElement('jf-status-pill'); pill.store = store;
            document.getElementById('bar').append(pill);
            window.openBand = async () => {
              const band = document.createElement('jf-confirm-band');
              band.heading = 'Remove Notes from your library?';
              band.consequence = 'Its 1,420 files leave search and any enrichment still running for it is stopped. Your files on disk stay where they are, and adding Notes again reads it from scratch.';
              band.actionLabel = 'Remove Notes';
              document.getElementById('slot').append(band);
              await band.updateComplete; await band.focusKeep();
            };
            window.offerUndo = async () => {
              offerReversal({ message: 'Removed Notes from your library', actionLabel: 'Add Notes again',
                reverse: async () => {}, reversed: 'Added Notes again' });
              await new Promise(r => setTimeout(r, 50)); pill.status.refresh(); await pill.updateComplete;
            };
            window.openDelete = async () => {
              const settings = document.createElement('jf-settings-surface');
              settings.host_ = createMockHostApi({ platform: { capabilities: new Set(['native-notifications']) } });
              document.getElementById('slot').append(settings);
              await new Promise(r => setTimeout(r, 50));
              settings.activeCategory = 'data'; await settings.updateComplete;
              settings.shadowRoot.querySelector('jf-button[label="Delete all local data"]').onActivate();
              await settings.updateComplete; await new Promise(r => setTimeout(r, 50));
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
  url = `http://127.0.0.1:${address.port}/confirm-tiers`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: process.env.TMPDIR ?? output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned headless Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
  const warm = await browser.newContext();
  const page = await warm.newPage();
  await page.goto(url);
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await warm.close();
}, 60_000);

afterEach(async () => { await context?.close(); context = undefined; });
afterAll(async () => {
  await browser?.close();
  await browserServer?.close();
  await server?.close();
  if (output) await rm(output, { recursive: true, force: true });
});

const looks = (['dark', 'light', 'forced'] as const).flatMap(look =>
  ([[1280, 800], [900, 800], [600, 400]] as const).map(([width, height]) => [look, width, height] as const));

async function open(look: typeof looks[number][0], width: number, height: number): Promise<Page> {
  context = await browser.newContext({ viewport: { width, height }, ...(look === 'forced' ? { forcedColors: 'active' as const } : {}) });
  const page = await context.newPage();
  await page.goto(url);
  await page.locator('body[data-ready="true"]').waitFor();
  if (look === 'light') await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'light'));
  return page;
}
const shot = async (page: Page, name: string) => {
  if (process.env.REVIEW_SHOTS) await page.screenshot({ path: path.join(process.env.REVIEW_SHOTS, `${name}.png`) });
};

it.each(looks)('X1/X2 the in-place band in %s at %s×%s fits, shows both answers and focuses Keep it', async (look, width, height) => {
  const page = await open(look, width, height);
  await page.evaluate(() => window.openBand());
  const result = await page.evaluate(() => {
    const band = document.querySelector('jf-confirm-band')!;
    const box = band.shadowRoot!.querySelector<HTMLElement>('.band')!;
    const answers = [...band.shadowRoot!.querySelectorAll<HTMLElement>('jf-button')].map(b => b.getBoundingClientRect());
    const style = getComputedStyle(box);
    return {
      page: document.documentElement.scrollWidth <= window.innerWidth,
      band: box.scrollWidth <= box.clientWidth,
      answersOnScreen: answers.every(r => r.width > 0 && r.left >= 0 && r.right <= window.innerWidth),
      edge: style.borderInlineStartWidth !== '0px' && style.borderInlineStartColor !== 'rgba(0, 0, 0, 0)',
      keep: band.shadowRoot!.activeElement?.classList.contains('keep') ?? false,
    };
  });
  expect(result).toEqual({ page: true, band: true, answersOnScreen: true, edge: true, keep: true });
  await shot(page, `band-${look}-${width}x${height}`);
}, 30_000);

it.each(looks)('X1/X2 the undo notice in %s at %s×%s keeps its words and its action on screen', async (look, width, height) => {
  const page = await open(look, width, height);
  await page.evaluate(() => window.offerUndo());
  const result = await page.evaluate(() => {
    const pill = document.querySelector('jf-status-pill')!.shadowRoot!;
    const action = pill.querySelector<HTMLElement>('.transient-action')!;
    const words = [...pill.querySelectorAll<HTMLElement>('.trigger .label, .notice-below')]
      .filter(el => getComputedStyle(el).display !== 'none' && el.textContent?.includes('Removed Notes'));
    const r = action.getBoundingClientRect();
    return { page: document.documentElement.scrollWidth <= window.innerWidth, action: action.textContent!.trim(),
      actionOnScreen: r.width > 0 && r.left >= 0 && r.right <= window.innerWidth,
      wordsOnScreen: words.length === 1 && words[0]!.getBoundingClientRect().right <= window.innerWidth };
  });
  expect(result).toEqual({ page: true, action: 'Add Notes againCtrl+Z', actionOnScreen: true, wordsOnScreen: true });
  await shot(page, `undo-${look}-${width}x${height}`);
}, 30_000);

it.each(looks)('X1/X2 the typed delete-all-data dialog in %s at %s×%s is modal, fits, and reaches its answers', async (look, width, height) => {
  const page = await open(look, width, height);
  await page.evaluate(() => window.openDelete());
  const result = await page.evaluate(() => {
    const dialog = document.querySelector('jf-settings-surface')!.shadowRoot!.querySelector<HTMLDialogElement>('dialog.delete-dialog')!;
    const box = dialog.getBoundingClientRect();
    const modal = dialog.matches(':modal');
    const answers = dialog.querySelector<HTMLElement>('.dialog-actions')!;
    answers.scrollIntoView({ block: 'nearest' });
    const a = answers.getBoundingClientRect();
    return { modal, page: document.documentElement.scrollWidth <= window.innerWidth,
      inside: box.left >= 0 && box.right <= window.innerWidth && box.top >= 0 && box.bottom <= window.innerHeight,
      noSideways: dialog.scrollWidth <= dialog.clientWidth,
      answersReachable: a.top >= box.top && a.bottom <= box.bottom };
  });
  expect(result).toEqual({ modal: true, page: true, inside: true, noSideways: true, answersReachable: true });
  await shot(page, `delete-${look}-${width}x${height}`);
}, 30_000);
