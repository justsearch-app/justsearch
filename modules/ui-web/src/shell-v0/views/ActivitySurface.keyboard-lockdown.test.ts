// SPDX-License-Identifier: Apache-2.0
// Real Chromium, private Vite server; run with vitest.config.lockdown.ts.
// FC8a: at 600×400 with 200% text, moving through Activity's windowed feed with the keyboard
// (Ctrl+End to the oldest loaded record, Ctrl+Home back to the newest) after a filter change, a
// reload and a return from Health logs no uncaught "ResizeObserver loop completed with undelivered
// notifications" error, counted as the browser reports it with its own ResizeObserver. The real Shell opens System › Activity; its ledger arrives over the shared
// shell-events stream the way an Engine sends it: operations, approvals, permissions, index records,
// scans, copies and routine navigation, rows of mixed heights.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

const ROWS = 180;
// The browser reports an undelivered notification only in some frames (timing decides which
// observers fire together; on the before-state, at a quarter speed, about every second window), so
// the path runs in several fresh windows, the keys three times in each.
const WINDOWS = 6;
const ROUNDS = 3;
const CPU_SLOWDOWN = 4;

const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
const params = new URLSearchParams(location.search);
if (params.get('text')) document.documentElement.style.fontSize = params.get('text');
const json = (body) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
const encoder = new TextEncoder();
const seqs = new Map();
/** One SSE frame as SseEnvelopeWriter writes it: event "frame", the envelope as JSON data. */
const frame = (streamId, frameKind, payload, checkpoint) => {
  const seq = (seqs.get(streamId) ?? 0) + 1;
  seqs.set(streamId, seq);
  const resumeToken = checkpoint ? btoa(streamId + ':' + seq + ':incarnation-1') : '';
  return encoder.encode('event: frame\\ndata: ' + JSON.stringify({ streamId, frameKind, seq, ts: new Date().toISOString(), payload, resumeToken }) + '\\n\\n');
};
const at = (i) => new Date(Date.UTC(2030, 0, 1, 8) + i * 1000).toISOString();
const record = (i) => {
  const base = { occurredAt: at(i) };
  switch (i % 9) {
    case 0: return { ...base, id: 'index:' + i, kind: 'index', originator: 'system', transport: 'WORKER_INDEXER', pathHash: 'h' + i + 'abcdef',
      collection: 'quarterly-reports-and-notes-' + i, state: i % 2 ? 'DONE' : 'FAILED', outcome: i % 2 ? 'DONE' : 'FAILED' };
    case 1: return { ...base, id: 'gate:' + i, kind: 'gate', originator: 'agent', operationId: 'core.rebuild-index',
      disposition: i % 2 ? 'GATED' : 'DENIED', gateBehavior: 'TYPED_CONFIRM', pendingId: 'p' + i, transport: 'MCP' };
    case 2: return { ...base, id: 'grant:' + i, kind: 'grant', originator: 'agent', action: 'ISSUED', subject: 'core.search-index', grantId: 'g' + i };
    case 3: return { ...base, id: 'operation:scan' + i, kind: 'operation', originator: 'system', operationId: 'core.scan-root',
      outcome: i % 2 ? 'SUCCESS' : 'PARTIAL', collection: 'a-long-collection-name-' + i, docsDone: i * 3, docsFailed: i % 4, durationMs: 61_000 };
    case 4: return { ...base, id: 'effect:' + i, kind: 'effect', originator: 'agent', effectKind: 'copy-to-clipboard', subject: '' };
    case 5: return { ...base, id: 'nav:' + i, kind: 'navigation', originator: 'user', targetSurface: 'core.health-surface', transport: 'RAIL' };
    case 6: return { ...base, id: 'operation:r' + i, kind: 'operation', originator: 'agent', operationId: 'core.rebuild-index',
      outcome: i % 4 ? 'SUCCESS' : 'FAILURE', transport: 'AGENT_LOOP' };
    default: return { ...base, id: 'operation:k' + i, kind: 'operation', originator: i % 2 ? 'agent' : 'user',
      operationId: 'core.open-file', outcome: i % 5 ? 'SUCCESS' : 'FAILURE' };
  }
};
const entries = Array.from({ length: ${ROWS} }, (_, i) => record(i));
window.fetch = async (input) => {
  const path = new URL(String(input instanceof Request ? input.url : input), location.href).pathname;
  if (path.endsWith('/api/shell-events/stream')) {
    // The Engine on connect: the ledger channel's connected (no checkpoint), then its snapshot.
    return new Response(new ReadableStream({ start(controller) {
      controller.enqueue(frame('surface:action-ledger', 'LIFECYCLE', { kind: 'connected' }, false));
      controller.enqueue(frame('surface:action-ledger', 'LIFECYCLE', { kind: 'snapshot', entries }, true));
    } }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
  }
  if (path.endsWith('/api/action-ledger')) return json({ entries });
  return json({ entries: [], conversations: [], messages: [] });
};
const { __seedForTest } = await import('/src/api/registry/SurfaceCatalogClient.ts');
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/chrome/Shell.ts');
customElements.define('jf-rows-search-probe', class extends HTMLElement {});
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL',
  presentation: { labelKey: id, descriptionKey: id }, consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('core.search-v3-surface', 'jf-rows-search-probe'),
  surface('core.system-surface', 'jf-system-surface', { members: ['core.health-surface', 'core.logs-surface', 'core.activity-surface'] }),
  surface('core.health-surface', 'jf-health-surface', { placement: 'DEEPLINK', altitude: 'DIAGNOSTIC' }),
  surface('core.logs-surface', 'jf-log-surface', { placement: 'DEEPLINK', altitude: 'DIAGNOSTIC' }),
  surface('core.activity-surface', 'jf-activity-surface', { placement: 'DEEPLINK', altitude: 'TRUST' }),
] });
document.body.append(document.createElement('jf-shell'));
document.body.dataset.ready = 'true';
</script></body></html>`;

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let context: BrowserContext | undefined;
let output: string;
let url: string;

beforeAll(async () => {
  output = await mkdtemp(path.join(os.tmpdir(), 'activity-keys-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'activity-keys-harness',
      configureServer(vite) {
        vite.middlewares.use((req, res, next) => {
          if (req.url?.split('?')[0] !== '/system') return next();
          res.setHeader('Content-Type', 'text/html');
          res.end(HARNESS);
        });
      },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/system`;
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

declare global {
  interface Window {
    /** Every uncaught error the page reported, by its message. */
    __uncaught: string[];
    /** The element `tag` anywhere in the page, across shadow roots. */
    __deep(tag: string): Element | null;
  }
}

interface Opened { readonly page: Page; readonly pageErrors: string[] }

async function open(width: number, height: number, text: string, forcedColors: boolean): Promise<Opened> {
  context = await browser.newContext({ viewport: { width, height }, forcedColors: forcedColors ? 'active' : 'none' });
  const page = await context.newPage();
  // A slower computer (a quarter of this one's speed): the frames the keys cross take longer, as on a laptop on battery.
  await (await context.newCDPSession(page)).send('Emulation.setCPUThrottlingRate', { rate: CPU_SLOWDOWN });
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  // Counted from before the page's own scripts run, with the browser's own ResizeObserver: the
  // window's error event is how the browser reports an undelivered ResizeObserver notification.
  await page.addInitScript(() => {
    window.__uncaught = [];
    window.addEventListener('error', (event) => { window.__uncaught.push(String(event.message ?? event.error ?? 'error')); });
  });
  await page.goto(`${url}${text ? `?text=${text}` : ''}#justsearch://surface/core.activity-surface`, { timeout: 45_000 });
  await loaded(page);
  return { page, pageErrors };
}

/** The page is up and Activity holds its records in the windowed list. */
async function loaded(page: Page): Promise<void> {
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await page.evaluate(() => {
    window.__deep = (tag) => {
      const walk = (root: ParentNode): Element | null => {
        for (const el of root.querySelectorAll('*')) {
          if (el.localName === tag) return el;
          const inner = el.shadowRoot && walk(el.shadowRoot);
          if (inner) return inner;
        }
        return null;
      };
      return walk(document);
    };
  });
  await expect.poll(() => page.evaluate(() => (window.__deep('jf-action-ledger')?.shadowRoot?.querySelector('lit-virtualizer') as
    unknown as { items?: unknown[] } | null)?.items?.length ?? 0), { timeout: 15_000 }).toBeGreaterThan(100);
}

async function settle(page: Page, ms = 400): Promise<void> {
  await page.waitForTimeout(ms);
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

/** The uncaught ResizeObserver errors so far, from the window's error event and the page's own report. */
async function observerErrors({ page, pageErrors }: Opened): Promise<string[]> {
  const reported = await page.evaluate(() => window.__uncaught);
  return [...reported, ...pageErrors].filter((message) => /ResizeObserver/i.test(message));
}

/** The feed entry with the keyboard, by its index; null when the keyboard is elsewhere. */
function focusedIndex(page: Page): Promise<number | null> {
  return page.evaluate(() => {
    const active = window.__deep('jf-action-ledger')!.shadowRoot!.activeElement as HTMLElement | null;
    return active?.classList.contains('entry') ? Number(active.dataset.index) : null;
  });
}

/** FC8a's path in a fresh window, then the keys through rows not yet measured; the uncaught ResizeObserver errors it logged. */
async function traverse(): Promise<string[]> {
  const opened = await open(600, 400, '200%', false);
  const { page } = opened;
  try {
    // A filter change, a reload and a return from Health.
    const quick = page.locator('jf-action-ledger [data-testid="feed-quick"]');
    await quick.evaluate((group) => group.scrollIntoView({ block: 'start', behavior: 'instant' }));
    await quick.locator('[data-quick="agents"]').click();
    await quick.locator('[data-quick="all"]').click();
    await page.reload({ timeout: 45_000 });
    await loaded(page);
    await page.getByRole('tab', { name: 'Health' }).click();
    await page.getByRole('tab', { name: /^Activity/ }).click();
    await loaded(page);
    await settle(page, 600);

    const count = await page.evaluate(() => (window.__deep('jf-action-ledger')!.shadowRoot!.querySelector('lit-virtualizer') as
      unknown as { items: unknown[] }).items.length);
    await expect.poll(() => page.evaluate(() => !!window.__deep('jf-action-ledger')!.shadowRoot!.querySelector('.entry[data-index="0"]')),
      { message: 'the newest record is drawn', timeout: 8_000 }).toBe(true);
    await page.evaluate(() => window.__deep('jf-action-ledger')!.shadowRoot!.querySelector<HTMLElement>('.entry[data-index="0"]')!.focus());
    for (let round = 0; round < ROUNDS; round += 1) {
      await page.keyboard.press('Control+End');
      await expect.poll(() => focusedIndex(page), { message: 'Ctrl+End reaches the oldest loaded record', timeout: 8_000 }).toBe(count - 1);
      await settle(page, 600);
      await page.keyboard.press('Control+Home');
      await expect.poll(() => focusedIndex(page), { message: 'Ctrl+Home reaches the newest record', timeout: 8_000 }).toBe(0);
      await settle(page, 600);
    }
    return await observerErrors(opened);
  } finally { await context?.close(); context = undefined; }
}

it('FC8a at 600×400 with 200% text, Ctrl+End and Ctrl+Home through the feed log no uncaught ResizeObserver loop error', async () => {
  const errors: string[] = [];
  for (let attempt = 1; attempt <= WINDOWS; attempt += 1) errors.push(...(await traverse()).map((message) => `window ${attempt}: ${message}`));
  expect(errors, 'no uncaught ResizeObserver error while the keys move through the feed').toEqual([]);
}, 600_000);
