// SPDX-License-Identifier: Apache-2.0
// SB6 through the production card in owned headless Chromium; no shared stack.
import { afterAll, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserServer } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const HARNESS = `<!doctype html><html lang="en"><head><style>html, body { height: 100%; margin: 0; }</style></head><body><script type="module">
window.fetch = async () => new Response('{"suggestions":[]}', { status: 200 });
await import('/src/shell-v0/views/search-v3/Sv3Composer.ts');
const firstUse = await import('/src/shell-v0/state/firstUse.ts');
const ai = await import('/src/shell-v0/state/aiStateStore.ts');
firstUse.setFirstUseRoots([{ pathHash: 'notes' }], new Map([['notes', '/notes']]));
window.observe = (indexState, pendingJobs, paused = false, state = 'fresh') => {
  ai.__feedForTest({ status: { worker: { core: { indexState, indexedDocuments: 8, searchableDocuments: 8, ownSearchableDocuments: 3,
    ...(pendingJobs === null ? {} : { pendingJobs }) }, countObservations: { searchable: { state, value: 8 } } },
    ...(paused ? { operating: { indexing: { state: 'PAUSED', reasons: ['user_paused'], untilEpochMs: null, waitingForResume: true } } } : {}) } });
  ai.__feedContactForTest();
};
window.observe('IDLE', 0);
document.body.append(document.createElement('jf-sv3-composer'));
document.body.dataset.ready = 'true';
</script></body></html>`;
let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let output: string;
let url: string;
beforeAll(async () => {
  output = await mkdtemp(path.join(process.env.TMPDIR ?? tmpdir(), 'first-use-reading-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: path.join(output, 'vite'), publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'reading-card', configureServer(vite) {
      vite.middlewares.use((req, res, next) => {
        if (req.url !== '/reading') return next();
        res.setHeader('Content-Type', 'text/html'); res.end(HARNESS);
      });
    } }] });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/reading`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1' };
  for (const key of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, key);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned SB6 Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
}, 60_000);
afterAll(async () => { await browser?.close(); await browserServer?.close(); await server?.close(); if (output) await rm(output, { recursive: true, force: true }); });

it.each([null, 0])('shows the partial own count during live INDEXING with queue %s', async pending => {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    await page.goto(url); await page.locator('body[data-ready="true"]').waitFor();
    await page.evaluate(q => (window as unknown as { observe(state: string, pending: number | null): void }).observe('INDEXING', q), pending);
    await expect.poll(() => page.locator('[data-testid="first-use-progress"]').textContent()).toBe('3 files searchable so far');
  } finally { await context.close(); }
}, 60_000);
