// SPDX-License-Identifier: Apache-2.0
// Browser-computed focus announcements; no Engine or desktop is needed.
// Run with npm run test:unit:lockdown -- --configLoader runner
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type CDPSession, type Route } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import type { CitationsPanel } from './CitationsPanel.js';

declare global {
  interface Window {
    citationsAvailabilityHarness: { panel: CitationsPanel; status: Element; announcements: string[] };
  }
}

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let context: BrowserContext;
let output: string;
let url: string;

beforeAll(async () => {
  output = await mkdtemp(path.join(os.tmpdir(), 'citations-availability-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'isolated-citations-availability',
      configureServer(vite) {
        vite.middlewares.use('/citations-availability', (_req, res) => {
          res.setHeader('Content-Type', 'text/html');
          res.end(`<!doctype html><html><head>
            <link rel="stylesheet" href="/src/styles/tokens.css">
            </head><body><script type="module">
            import '/src/shell-v0/components/chat/CitationsPanel.ts';
            const panel = document.createElement('jf-citations-panel');
            panel.sources = [{ parentDocId: '/docs/source.md', chunkIndex: 0,
              startLine: 0, endLine: 1, excerpt: 'Recorded evidence', headingText: '' }];
            panel.sourcesExpanded = true;
            panel.observeAvailability = true;
            panel.apiBase = location.origin;
            document.body.append(panel);
            await panel.updateComplete;
            const status = panel.shadowRoot.querySelector('[role="status"]');
            const announcements = [];
            new MutationObserver(() => {
              const text = status.textContent.trim();
              if (text) announcements.push(text);
            }).observe(status, { childList: true, characterData: true, subtree: true });
            window.citationsAvailabilityHarness = { panel, status, announcements };
            document.body.dataset.ready = 'true';
            </script></body></html>`);
        });
      },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/citations-availability`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1' };
  for (const name of Object.keys(env)) {
    if (name.startsWith('JUSTSEARCH_') || ['DISPLAY', 'WAYLAND_DISPLAY', 'JAVA_TOOL_OPTIONS',
      'JDK_JAVA_OPTIONS', 'JAVA_OPTS', '_JAVA_OPTIONS'].includes(name)) Reflect.deleteProperty(env, name);
  }
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

async function focusedNameAndDescription(cdp: CDPSession) {
  const { result } = await cdp.send('Runtime.evaluate', {
    expression: 'document.activeElement.shadowRoot.activeElement',
  });
  expect(result.objectId, 'the source button must be focused in its shadow root').toBeTruthy();
  const { nodes } = await cdp.send('Accessibility.getPartialAXTree', {
    objectId: result.objectId!, fetchRelatives: false,
  });
  const button = nodes.find(node => node.role?.value === 'button');
  expect(button?.ignored).toBe(false);
  expect(button?.properties?.find(property => property.name === 'focused')?.value.value).toBe(true);
  return { name: String(button?.name?.value ?? ''), description: String(button?.description?.value ?? '') };
}

it.each(['present', 'unknown', 'absent'])('computes the focused source name and description in Chromium (%s)', async state => {
  context = await browser.newContext();
  const page = await context.newPage();
  let intercepted!: (route: Route) => void;
  const presence = new Promise<Route>(resolve => { intercepted = resolve; });
  await page.route('**/api/documents/presence', route => { intercepted(route); });
  await page.goto(url);
  await page.locator('body[data-ready="true"]').waitFor();
  const route = await presence;
  expect(route.request().method()).toBe('POST');
  expect(route.request().postDataJSON()).toEqual({ docIds: ['/docs/source.md'] });
  const button = page.locator('jf-citations-panel button.source');
  await button.focus();
  const cdp = await context.newCDPSession(page);
  const before = await focusedNameAndDescription(cdp);
  expect(`${before.name} ${before.description}`).not.toContain('No longer available');
  const status = button.locator('[role="status"]');
  expect(await status.count()).toBe(1);
  expect(await status.getAttribute('aria-live')).toBe('polite');
  expect((await status.textContent())?.trim()).toBe('');

  const response = page.waitForResponse('**/api/documents/presence');
  await route.fulfill({ json: { states: { '/docs/source.md': state } } });
  await (await response).finished();
  // Drain response consumption and Lit rendering, including neutral replies with no visible change.
  await page.evaluate(async () => {
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    await window.citationsAvailabilityHarness.panel.updateComplete;
  });
  const after = await focusedNameAndDescription(cdp);
  console.info(state, { before, after });
  if (state === 'absent') expect(`${after.name} ${after.description}`).toContain('No longer available');
  else expect(after).toEqual(before);

  expect(await status.count()).toBe(1);
  expect(await status.getAttribute('aria-live')).toBe('polite');
  expect((await status.textContent())?.trim()).toBe(state === 'absent' ? 'No longer available' : '');
  // An unrelated render must retain the status node and not repeat the announcement.
  await page.evaluate(async () => {
    const { panel } = window.citationsAvailabilityHarness;
    panel.requestUpdate();
    await panel.updateComplete;
  });
  expect(await status.evaluate(el => el === window.citationsAvailabilityHarness.status)).toBe(true);
  expect(await page.evaluate(() => window.citationsAvailabilityHarness.announcements))
    .toEqual(state === 'absent' ? ['No longer available'] : []);
}, 15_000);
