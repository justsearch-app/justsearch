// SPDX-License-Identifier: Apache-2.0
// Real Chromium, private Vite server; run with vitest.config.lockdown.ts.
// FC6: a pointer to a section of Activity (Ctrl+K "Show Effect Audit Log" arrives at this window's
// journal) is taken by the Activity its navigation shows, in the window the navigation lands on:
// its filters reset to this window's records and it has the keyboard. An Activity that is connected
// but not where the navigation went is untouched: a hidden one present first, and the one in the
// second window of a split. The real Shell runs the real navigation (System › Activity), at
// 1280×720, at 600×400 with 200% text and in forced colours; Activity reached from More as well.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

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
const record = (i) => i % 7 === 3
  ? { id: 'index:' + i, kind: 'index', originator: 'system', transport: 'WORKER_INDEXER', pathHash: 'h' + i + 'abcdef',
      collection: 'reports-' + i, state: i % 2 ? 'DONE' : 'FAILED', outcome: i % 2 ? 'DONE' : 'FAILED', occurredAt: at(i) }
  : i % 7 === 5
    ? { id: 'gate:' + i, kind: 'gate', originator: 'agent', operationId: 'core.rebuild-index', disposition: i % 2 ? 'GATED' : 'DENIED',
        gateBehavior: 'TYPED_CONFIRM', pendingId: 'p' + i, occurredAt: at(i) }
    : { id: 'operation:k' + i, kind: 'operation', originator: i % 2 ? 'agent' : 'user',
        operationId: 'core.open-file', outcome: i % 9 ? 'SUCCESS' : 'FAILURE', occurredAt: at(i) };
const entries = Array.from({ length: 6 }, (_, i) => record(i * 2));
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
await import('/src/shell-v0/views/ActivitySurface.ts');
customElements.define('jf-arrival-search-probe', class extends HTMLElement {});
const { invokeAction } = await import('/src/shell-v0/substrates/actions/index.ts');
const { setActiveLayoutId, setSecondaryActiveSurface } = await import('/src/shell-v0/state/userConfigState.ts');
// The core layouts, the split among them (the app itself seeds none: a finding outside FC6).
const { initLayoutCatalog } = await import('/src/shell-v0/layout/LayoutManifest.ts');
initLayoutCatalog();
window.__app = { invokeAction, setActiveLayoutId, setSecondaryActiveSurface };
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL',
  presentation: { labelKey: id, descriptionKey: id }, consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('core.search-v3-surface', 'jf-arrival-search-probe'),
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
  output = await mkdtemp(path.join(os.tmpdir(), 'activity-arrival-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'activity-arrival-harness',
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
    __app: {
      invokeAction(id: string): Promise<unknown>;
      setActiveLayoutId(id: string | undefined): void;
      setSecondaryActiveSurface(id: string | undefined): void;
    };
    /** Every `tag` in `root` and its shadow roots, in document order. */
    __all(tag: string, root?: ParentNode): Element[];
    /** The keyboard's element, through shadow roots. */
    __active(): Element | null;
    /** `node` is `el` or inside it, across shadow roots. */
    __within(node: Node | null, el: Element): boolean;
  }
}

/** What the arrival changed on an Activity: its feed's filter and whether the keyboard is in its feed. */
interface ActivityState { readonly scope: string; readonly quick: string; readonly focused: boolean }

async function open(width: number, height: number, text: string, forcedColors: boolean, address = 'core.search-v3-surface'): Promise<Page> {
  context = await browser.newContext({ viewport: { width, height }, forcedColors: forcedColors ? 'active' : 'none' });
  const page = await context.newPage();
  page.on('pageerror', (error) => console.info('pageerror', error.message));
  await page.goto(`${url}${text ? `?text=${text}` : ''}#justsearch://surface/${address}`, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await page.evaluate(() => {
    window.__all = (tag, root = document) => {
      const out: Element[] = [];
      const walk = (at: ParentNode): void => {
        for (const el of at.querySelectorAll('*')) {
          if (el.localName === tag) out.push(el);
          if (el.shadowRoot) walk(el.shadowRoot);
        }
      };
      walk(root);
      return out;
    };
    window.__active = () => {
      let at: Element | null = document.activeElement;
      while (at?.shadowRoot?.activeElement) at = at.shadowRoot.activeElement;
      return at;
    };
    window.__within = (node, el) => {
      for (let at: Node | null = node; at; at = at.parentNode ?? (at instanceof ShadowRoot ? at.host : null)) if (at === el) return true;
      return false;
    };
  });
  return page;
}

async function settle(page: Page, ms = 300): Promise<void> {
  await page.waitForTimeout(ms);
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

/** Each connected Activity's state: `where` says which (`hidden`: outside the stage; `primary`/`second`: the stage's windows). */
function activities(page: Page): Promise<Array<ActivityState & { where: string }>> {
  return page.evaluate(() => {
    const stage = window.__all('jf-stage')[0] as (Element & { primaryWindow(): Element | null }) | undefined;
    const primary = stage?.primaryWindow() ?? null;
    return window.__all('jf-activity-surface').map((activity) => {
      const feed = activity.shadowRoot?.querySelector('jf-action-ledger') as (Element & { filter: { scope: string; quick: string } }) | null;
      return {
        where: !stage || !window.__within(activity, stage) ? 'hidden' : primary && window.__within(activity, primary) ? 'primary' : 'second',
        scope: feed?.filter.scope ?? 'none',
        quick: feed?.filter.quick ?? 'none',
        focused: !!feed && window.__within(window.__active(), feed),
      };
    });
  });
}

/** Leave a filter on every connected Activity's feed that the journal's arrival must clear. */
async function filterEvery(page: Page): Promise<void> {
  await page.evaluate(() => {
    for (const activity of window.__all('jf-activity-surface')) {
      const feed = activity.shadowRoot!.querySelector('jf-action-ledger') as Element & { filter: Record<string, unknown> };
      feed.filter = { ...feed.filter, quick: 'problems' };
    }
  });
  await settle(page);
}

/** Ctrl+K › Show Effect Audit Log: the pointer to this window's journal, and its navigation to Activity. */
async function arriveAtJournal(page: Page): Promise<void> {
  await page.evaluate(() => { void window.__app.invokeAction('core.action.shell.show-audit-log'); });
  await expect.poll(async () => (await activities(page)).some((a) => a.where === 'primary'), { timeout: 15_000 }).toBe(true);
  await settle(page, 600);
}

/** The Activity navigation shows: its filters reset to this window's records, and it has the keyboard. */
function expectArrivedAt(state: ActivityState | undefined): void {
  expect(state, 'Activity is shown in the window the navigation went to').toBeDefined();
  expect(state!.scope, 'its feed is scoped to this window\'s records').toBe('local');
  expect(state!.quick, 'the filter left from earlier is cleared').toBe('all');
  expect(state!.focused, 'it has the keyboard').toBe(true);
}

for (const [width, height, text, forced] of [[1280, 720, '', false], [600, 400, '200%', false], [1280, 720, '', true]] as const) {
  const size = `${width}×${height}${text ? ` with ${text} text` : ''}${forced ? ' in forced colours' : ''}`;

  it(`FC6 at ${size}, a hidden Activity connected first leaves the arrival to the Activity navigation shows`, async () => {
    const page = await open(width, height, text, forced);
    await page.evaluate(() => {
      const hidden = document.createElement('div');
      hidden.hidden = true;
      hidden.append(document.createElement('jf-activity-surface'));
      document.body.prepend(hidden);
    });
    await expect.poll(async () => (await activities(page)).filter((a) => a.where === 'hidden' && a.scope !== 'none').length,
      { timeout: 15_000 }).toBe(1);
    await filterEvery(page);
    await arriveAtJournal(page);
    const after = await activities(page);
    expectArrivedAt(after.find((a) => a.where === 'primary'));
    expect(after.find((a) => a.where === 'hidden'), 'the hidden Activity is untouched').toMatchObject({ scope: 'all', quick: 'problems', focused: false });
  }, 90_000);
}

it('FC6 split: with Activity in the second window, the arrival goes to the Activity the navigation shows', async () => {
  const page = await open(1280, 720, '', false);
  await page.evaluate(() => {
    window.__app.setActiveLayoutId('core.split');
    window.__app.setSecondaryActiveSurface('core.system-surface');
  });
  await page.locator('jf-surface-tabs button[data-tab-id="core.activity-surface"]').click({ timeout: 15_000 });
  await expect.poll(async () => (await activities(page)).filter((a) => a.where === 'second' && a.scope !== 'none').length,
    { timeout: 15_000 }).toBe(1);
  await filterEvery(page);
  await arriveAtJournal(page);
  const after = await activities(page);
  expectArrivedAt(after.find((a) => a.where === 'primary'));
  for (const other of after.filter((a) => a.where !== 'primary')) expect(other.focused, 'no other Activity has the keyboard').toBe(false);
}, 90_000);

for (const [width, height, text] of [[1280, 720, ''], [600, 400, '200%']] as const) {
  it(`FC6 at ${width}×${height}${text ? ` with ${text} text` : ''}, Activity reached from More takes the arrival`, async () => {
    const page = await open(width, height, text, false);
    await page.locator('jf-top-bar button.more-trigger').click({ timeout: 15_000 });
    await page.locator('jf-top-bar [role="menuitem"][data-surface-id="core.system-surface"]').click();
    await page.locator('jf-surface-tabs button[data-tab-id="core.activity-surface"]').click({ timeout: 15_000 });
    await expect.poll(async () => (await activities(page)).filter((a) => a.where === 'primary' && a.scope !== 'none').length,
      { timeout: 15_000 }).toBe(1);
    await filterEvery(page);
    await arriveAtJournal(page);
    expectArrivedAt((await activities(page)).find((a) => a.where === 'primary'));
  }, 90_000);
}
