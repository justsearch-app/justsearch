// SPDX-License-Identifier: Apache-2.0
// Isolated production build and initial-frame paint in headless Chromium.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { preview, type PreviewServer } from 'vite';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { chromium, type Browser, type BrowserContext, type Page } from 'playwright-core';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

let browser: Browser, server: PreviewServer, output: string, url: string, entry: string;
const contexts = new Set<BrowserContext>();

beforeAll(async () => {
  output = await mkdtemp(path.join(tmpdir(), 'boot-topbar-'));
  await promisify(execFile)(process.execPath, [path.resolve('node_modules/vite/bin/vite.js'),
    'build', '--configLoader', 'runner', '--outDir', path.join(output, 'dist'), '--logLevel', 'silent'],
    { env: { ...process.env, NODE_ENV: 'production' }, maxBuffer: 8 * 1024 * 1024, timeout: 100_000 });
  const builtHtml = await readFile(path.join(output, 'dist/index.html'), 'utf8');
  entry = builtHtml.match(/<script[^>]*type="module"[^>]*src="([^"]+)"/)![1]!;
  server = await preview({ build: { outDir: path.join(output, 'dist') }, preview: { host: '127.0.0.1', port: 0 }, logLevel: 'silent' });
  url = server.resolvedUrls!.local[0]!;
  browser = await chromium.launch({ headless: true });
}, 120_000);

afterEach(async () => {
  for (const context of contexts) { await context.close(); contexts.delete(context); }
});
afterAll(async () => {
  await browser?.close();
  if (server) await new Promise<void>((resolve, reject) => server.httpServer.close(error => error ? reject(error) : resolve()));
  if (output) await rm(output, { recursive: true, force: true });
});

async function create(options: { os: 'light' | 'dark' }): Promise<Page> {
  const context = await browser.newContext({ colorScheme: options.os });
  contexts.add(context);
  const page = await context.newPage();
  // Only the inline frame runs: the module request is fulfilled without mounting the app.
  // Native presence reserves the same window-control space as the desktop startup frame.
  await page.addInitScript(() => {
    Object.assign(window, { __TAURI_INTERNALS__: { invoke: async () => false } });
  });
  return page;
}

it('the startup bar has the new 44 px boundary, words, page background and menu', async () => {
  for (const os of ['light', 'dark'] as const) {
    const page = await create({ os });
    // End the module request without mounting the app: the initial frame remains, while the
    // production font preloads can finish before the evidence screenshot is taken.
    await page.route(`**${entry}`, route => route.fulfill({ contentType: 'text/javascript', body: '' }));
    await page.goto(url);
    try {
      for (const width of [1024, 600]) {
        await page.setViewportSize({ width, height: 700 });
        const bar = page.locator('.boot-topbar');
        expect(await bar.evaluate(el => el.getBoundingClientRect().height)).toBe(44);
        expect(await page.locator('.boot-areas span').allTextContents()).toEqual(['Search', 'Library', 'AI Brain']);
        expect(await page.locator('.boot-end').textContent()).toBe('Starting⋯');
        expect(await bar.evaluate(el => getComputedStyle(el).backgroundColor))
          .toBe(await page.locator('#boot-frame').evaluate(el => getComputedStyle(el).backgroundColor));
        const bounds = await page.locator('.boot-areas').evaluate(el => {
          const rect = el.getBoundingClientRect();
          const bar = el.closest('.boot-topbar')!;
          const style = getComputedStyle(bar);
          const reserved = parseFloat(style.paddingInlineEnd) - parseFloat(style.paddingInlineStart);
          return { centre: (rect.left + rect.right) / 2, target: (bar.clientWidth - reserved) / 2, top: rect.top, bottom: rect.bottom };
        });
        expect(bounds.top).toBeGreaterThanOrEqual(0);
        expect(bounds.bottom).toBeLessThanOrEqual(44);
        expect(Math.abs(bounds.centre - bounds.target)).toBeLessThanOrEqual(1);
        if (process.env.S3_EVIDENCE_DIR) {
          await page.screenshot({ path: path.resolve(process.env.S3_EVIDENCE_DIR, `boot-${width}-${os}.png`) });
        }
      }
    } finally { await page.close(); }
  }
}, 120_000);
