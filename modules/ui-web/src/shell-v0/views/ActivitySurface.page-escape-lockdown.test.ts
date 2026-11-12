// SPDX-License-Identifier: Apache-2.0
// Real Chromium, private Vite server; run with vitest.config.lockdown.ts.
// S6 Activity, candidate 2 — A17: Escape with the keyboard on the page (no window hears it) closes the
// open Activity details of the window in use, as it closes Search's and the Shell's details, and puts
// the keyboard back on the record they were for (the list brings the record back when its row was
// dropped). A dialog, the command palette and Settings keep their Escape; a window not on show and a
// window not in use are left alone. The real Shell opens System › Activity, whose ledger arrives over
// the shared shell-events stream as an Engine sends it.
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
const key = (i) => '019a0000-0000-7000-8000-' + String(i).padStart(12, '0');
const op = (i) => ({ id: 'operation:' + key(i), kind: 'operation', originator: i % 2 ? 'agent' : 'user', transport: 'BUTTON',
  operationId: 'core.open-file', outcome: i % 9 ? 'SUCCESS' : 'FAILURE',
  occurredAt: new Date(Date.UTC(2030, 0, 1, 8) + i * 1000).toISOString() });
const rows = Number(params.get('rows') ?? '140');
const entries = Array.from({ length: rows }, (_, i) => op(i));
window.fetch = async (input) => {
  const path = new URL(String(input instanceof Request ? input.url : input), location.href).pathname;
  if (path.endsWith('/api/shell-events/stream')) {
    return new Response(new ReadableStream({ start(controller) {
      controller.enqueue(frame('surface:action-ledger', 'LIFECYCLE', { kind: 'connected' }, false));
      controller.enqueue(frame('surface:action-ledger', 'LIFECYCLE', { kind: 'snapshot', entries }, true));
    } }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
  }
  if (path.startsWith('/api/operation-history/019a')) return json({ state: 'complete', historySince: 0, unitsCompleted: 1 });
  if (path.endsWith('/api/action-ledger')) return json({ entries });
  return json({ entries: [], conversations: [], messages: [] });
};
const { __seedForTest } = await import('/src/api/registry/SurfaceCatalogClient.ts');
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/chrome/Shell.ts');
// The Search window and Settings stand in as probes: a window with a control and no details of its own.
for (const tag of ['jf-escape-search-probe', 'jf-escape-settings-probe']) {
  customElements.define(tag, class extends HTMLElement {
    connectedCallback() {
      if (this.shadowRoot) return;
      this.attachShadow({ mode: 'open' }).innerHTML = '<button type="button" data-testid="probe-button">' + tag + '</button>';
      this.style.display = 'block';
      this.style.minHeight = '200px';
    }
  });
}
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL',
  presentation: { labelKey: id, descriptionKey: id }, consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('core.search-v3-surface', 'jf-escape-search-probe'),
  surface('core.system-surface', 'jf-system-surface', { members: ['core.health-surface', 'core.logs-surface', 'core.activity-surface'] }),
  surface('core.health-surface', 'jf-health-surface', { placement: 'DEEPLINK', altitude: 'DIAGNOSTIC' }),
  surface('core.logs-surface', 'jf-log-surface', { placement: 'DEEPLINK', altitude: 'DIAGNOSTIC' }),
  surface('core.activity-surface', 'jf-activity-surface', { placement: 'DEEPLINK', altitude: 'TRUST' }),
  surface('core.settings-surface', 'jf-escape-settings-probe', { placement: 'MODAL' }),
] });
document.body.append(document.createElement('jf-shell'));
window.__navigate = async (id) => (await import('/src/shell-v0/controllers/navigateRequest.ts')).requestSurfaceNavigation(id);
// The Split layout as a person picks it: the left pane follows navigation, the right one is chosen.
window.__split = async (left, right) => {
  (await import('/src/shell-v0/layout/LayoutManifest.ts')).initLayoutCatalog();
  const config = await import('/src/shell-v0/state/userConfigState.ts');
  config.setSecondaryActiveSurface(right);
  config.setActiveLayoutId('core.split');
  await window.__navigate(left);
};
window.__ask = async () => {
  const { elicit } = await import('/src/shell-v0/substrates/elicit/index.ts');
  void elicit({ title: 'Name the macro', schema: { type: 'object', properties: { name: { type: 'string' } } } });
};
document.body.dataset.ready = 'true';
</script></body></html>`;

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let context: BrowserContext | undefined;
let output: string;
let url: string;

beforeAll(async () => {
  output = await mkdtemp(path.join(os.tmpdir(), 'activity-page-escape-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'activity-page-escape-harness',
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
    __deep(tag: string): Element | null;
    __split(left: string, right: string): Promise<void>;
    __navigate(id: string): Promise<void>;
    __ask(): Promise<void>;
    /** Where the keyboard is: the innermost focused element across shadow roots. */
    __pageFocus(): { tag: string; key: string | null; inPane: boolean };
  }
}

interface Options { width: number; height: number; text?: string; forced?: boolean; rows?: number }

async function open({ width, height, text = '', forced = false, rows }: Options): Promise<Page> {
  context = await browser.newContext({ viewport: { width, height }, ...(forced ? { forcedColors: 'active' as const } : {}) });
  const page = await context.newPage();
  page.on('pageerror', (error) => console.info('pageerror', error.message));
  const query = new URLSearchParams({ ...(text ? { text } : {}), ...(rows !== undefined ? { rows: String(rows) } : {}) }).toString();
  await page.goto(`${url}${query ? `?${query}` : ''}#justsearch://surface/core.activity-surface`, { timeout: 45_000 });
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
    window.__pageFocus = () => {
      let el: Element | null = document.activeElement;
      while (el?.shadowRoot?.activeElement) el = el.shadowRoot.activeElement;
      const trail: Element[] = [];
      for (let node: Node | null = el; node; node = node instanceof ShadowRoot ? node.host : (node as Element).assignedSlot ?? node.parentNode) {
        if (node instanceof Element) trail.push(node);
      }
      return { tag: el?.localName ?? '', key: (el as HTMLElement | null)?.closest?.('.entry')?.getAttribute('data-entry-key') ?? null,
        inPane: trail.some((n) => n.localName === 'jf-details-pane') };
    };
  });
  await waitForFeed(page);
  return page;
}

async function waitForFeed(page: Page): Promise<void> {
  await expect.poll(() => page.evaluate(() => {
    const feed = window.__deep('jf-action-ledger');
    feed?.scrollIntoView({ block: 'start' });
    return feed?.shadowRoot?.querySelectorAll('.entry').length ?? 0;
  }), { timeout: 15_000 }).toBeGreaterThan(0);
}

async function settle(page: Page, ms = 300): Promise<void> {
  await page.waitForTimeout(ms);
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

/** Click the first record on screen, where it is drawn, once the point is really on it; its key. */
async function clickRecord(page: Page): Promise<string> {
  const locate = (): Promise<{ x: number; y: number; key: string; hit: boolean } | null> => page.evaluate(() => {
    const feed = window.__deep('jf-action-ledger')!;
    const entry = [...feed.shadowRoot!.querySelectorAll<HTMLElement>('.entry')].find((e) => e.getBoundingClientRect().top >= 0);
    if (!entry) return null;
    entry.scrollIntoView({ block: 'center' });
    const box = entry.getBoundingClientRect();
    const x = box.x + 12;
    const y = box.y + Math.min(10, box.height / 2);
    let hit: Element | null = document.elementFromPoint(x, y);
    while (hit?.shadowRoot) {
      const inner = hit.shadowRoot.elementFromPoint(x, y);
      if (!inner || inner === hit) break;
      hit = inner;
    }
    return { x, y, key: entry.dataset.entryKey!, hit: !!hit && entry.contains(hit) };
  });
  await expect.poll(async () => (await locate())?.hit ?? false, { timeout: 15_000, message: 'the record can be clicked' }).toBe(true);
  const target = (await locate())!;
  await page.mouse.click(target.x, target.y);
  await settle(page);
  return target.key;
}

/** Whether Activity's details are open, and its record selection. */
function activity(page: Page): Promise<{ open: boolean; overlay: boolean; selected: string | null; mainInert: boolean }> {
  return page.evaluate(() => {
    const surface = window.__deep('jf-activity-surface');
    const pane = surface?.shadowRoot?.querySelector('jf-details-pane');
    const feed = window.__deep('jf-action-ledger');
    return { open: !!pane, overlay: !!pane?.hasAttribute('overlay'),
      selected: feed?.shadowRoot?.querySelector('.entry[aria-selected="true"]')?.getAttribute('data-entry-key') ?? null,
      mainInert: surface?.shadowRoot?.querySelector<HTMLElement>('.main')?.inert ?? false };
  });
}

/** Drop the keyboard to the page: nothing focused, as after a click on empty space or a closed control. */
async function toPage(page: Page): Promise<void> {
  await page.evaluate(() => {
    let active = document.activeElement as HTMLElement | null;
    while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement as HTMLElement;
    active?.blur();
  });
  expect((await page.evaluate(() => window.__pageFocus())).tag, 'the keyboard is on the page').toBe('body');
}

async function openDetails(page: Page): Promise<string> {
  const key = await clickRecord(page);
  await expect.poll(async () => (await activity(page)).open, { timeout: 5_000, message: 'the details open' }).toBe(true);
  return key;
}

it('at 1280×720, Escape with the keyboard on the page closes Activity\'s details and returns the keyboard to the record', async () => {
  const page = await open({ width: 1280, height: 720 });
  const key = await openDetails(page);
  await toPage(page);
  await page.keyboard.press('Escape');
  await settle(page);
  expect((await activity(page)).open, 'Escape on the page closes the details').toBe(false);
  expect((await activity(page)).selected, 'no record stays selected').toBeNull();
  await expect.poll(async () => (await page.evaluate(() => window.__pageFocus())).key, { timeout: 5_000 }).toBe(key);
}, 90_000);

it('the windowed list dropped the record\'s row: Escape on the page still returns the keyboard to that record', async () => {
  const page = await open({ width: 1280, height: 720 });
  const key = await openDetails(page);
  await page.evaluate(() => {
    const main = window.__deep('jf-activity-surface')!.shadowRoot!.querySelector<HTMLElement>('.main')!;
    main.scrollTop = main.scrollHeight;
  });
  await settle(page, 600);
  expect(await page.evaluate((key) => !!window.__deep('jf-action-ledger')!.shadowRoot!
    .querySelector(`.entry[data-entry-key="${key}"]`), key), 'the row is gone from the windowed list').toBe(false);
  await toPage(page);
  await page.keyboard.press('Escape');
  await settle(page);
  expect((await activity(page)).open).toBe(false);
  await expect.poll(async () => (await page.evaluate(() => window.__pageFocus())).key, { timeout: 5_000 }).toBe(key);
}, 90_000);

it('at 600×400 with 200% text, Escape on the page closes the sheet, the window behind is live again, and the keyboard is on the record', async () => {
  const page = await open({ width: 600, height: 400, text: '200%', rows: 8 });
  const key = await openDetails(page);
  const sheet = await activity(page);
  expect(sheet.overlay, 'narrow window: a sheet').toBe(true);
  expect(sheet.mainInert).toBe(true);
  await toPage(page);
  await page.keyboard.press('Escape');
  await settle(page);
  const after = await activity(page);
  expect(after.open, 'Escape on the page closes the sheet').toBe(false);
  expect(after.mainInert, 'the window behind is no longer inert').toBe(false);
  await expect.poll(async () => (await page.evaluate(() => window.__pageFocus())).key, { timeout: 5_000 }).toBe(key);
}, 90_000);

it('in forced colours, Escape on the page closes the details: the record loses its selection bar and shows the focus ring', async () => {
  const page = await open({ width: 1280, height: 720, forced: true, rows: 6 });
  const key = await openDetails(page);
  const bar = (): Promise<{ forced: boolean; bar: number; outline: string }> => page.evaluate((key) => {
    const entry = window.__deep('jf-action-ledger')!.shadowRoot!.querySelector<HTMLElement>(`.entry[data-entry-key="${key}"]`)!;
    const style = getComputedStyle(entry);
    return { forced: matchMedia('(forced-colors: active)').matches, bar: parseFloat(style.borderInlineStartWidth), outline: style.outlineStyle };
  }, key);
  const before = await bar();
  expect(before.forced).toBe(true);
  expect(before.bar, 'the selected record has its bar').toBeGreaterThan(0);
  await toPage(page);
  await page.keyboard.press('Escape');
  await settle(page);
  expect((await activity(page)).open).toBe(false);
  await expect.poll(async () => (await page.evaluate(() => window.__pageFocus())).key, { timeout: 5_000 }).toBe(key);
  const after = await bar();
  expect(after.bar, 'no record is drawn as selected').toBe(0);
  await expect.poll(async () => (await bar()).outline, { timeout: 5_000, message: 'the record shows the focus ring' }).not.toBe('none');
}, 90_000);

it('System in the second pane: Escape on the page closes Activity\'s details only when that pane is the window in use', async () => {
  const page = await open({ width: 1600, height: 800 });
  await page.evaluate(() => window.__split('core.search-v3-surface', 'core.system-surface'));
  await settle(page, 600);
  await waitForFeed(page);
  const key = await openDetails(page);
  // The other pane is used last: Escape on the page is its, and it has no details.
  const other = await page.evaluate(() => {
    const box = window.__deep('jf-escape-search-probe')!.shadowRoot!.querySelector('button')!.getBoundingClientRect();
    return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
  });
  await page.mouse.click(other.x, other.y);
  await toPage(page);
  await page.keyboard.press('Escape');
  await settle(page);
  expect((await activity(page)).open, 'the window in use is the other pane: Activity\'s details stay').toBe(true);
  // Back in System's pane (a click in Activity's details, a sheet over its half), Escape on the page is Activity's.
  await page.locator('jf-activity-details [data-testid="activity-details-title"]').click();
  await settle(page);
  await toPage(page);
  await page.keyboard.press('Escape');
  await settle(page);
  expect((await activity(page)).open, 'System\'s pane in use: Escape closes Activity\'s details').toBe(false);
  await expect.poll(async () => (await page.evaluate(() => window.__pageFocus())).key, { timeout: 5_000 }).toBe(key);
}, 90_000);

it('a dialog open: Escape is the dialog\'s, Activity\'s details stay open', async () => {
  const page = await open({ width: 1280, height: 720 });
  const key = await openDetails(page);
  await page.evaluate(() => window.__ask());
  const dialog = page.locator('jf-elicit-host dialog[open]');
  await dialog.waitFor({ timeout: 5_000 });
  await toPage(page);
  await page.keyboard.press('Escape');
  await settle(page);
  const after = await activity(page);
  expect(after.open, 'Activity\'s details stay open').toBe(true);
  expect(after.selected).toBe(key);
  expect(await dialog.count(), 'Escape closed the dialog').toBe(0);
}, 90_000);

it('the command palette open: Escape is the palette\'s, Activity\'s details stay open', async () => {
  const page = await open({ width: 1280, height: 720 });
  const key = await openDetails(page);
  await page.keyboard.press('Control+k');
  const input = page.locator('jf-command-palette input[role="combobox"]');
  await input.waitFor({ state: 'visible', timeout: 5_000 });
  await toPage(page);
  await page.keyboard.press('Escape');
  await settle(page);
  const after = await activity(page);
  expect(after.open, 'Activity\'s details stay open').toBe(true);
  expect(after.selected).toBe(key);
  expect(await input.isVisible(), 'Escape closed the palette').toBe(false);
}, 90_000);

it('Settings open: Escape is Settings\', Activity\'s details stay open', async () => {
  const page = await open({ width: 1280, height: 720 });
  const key = await openDetails(page);
  await page.evaluate(() => window.__navigate('core.settings-surface'));
  const probe = page.locator('jf-escape-settings-probe');
  await probe.waitFor({ state: 'visible', timeout: 5_000 });
  await toPage(page);
  await page.keyboard.press('Escape');
  await settle(page);
  const after = await activity(page);
  expect(after.open, 'Activity\'s details stay open').toBe(true);
  expect(after.selected).toBe(key);
  expect(await probe.isVisible(), 'Escape closed Settings').toBe(false);
}, 90_000);

it('Activity not on show: Escape on the page leaves its details as they were', async () => {
  const page = await open({ width: 1280, height: 720 });
  const key = await openDetails(page);
  await page.evaluate(() => window.__navigate('core.search-v3-surface'));
  await page.locator('jf-escape-search-probe').waitFor({ state: 'visible', timeout: 5_000 });
  await toPage(page);
  await page.keyboard.press('Escape');
  await settle(page);
  await page.evaluate(() => window.__navigate('core.activity-surface'));
  await settle(page, 600);
  await waitForFeed(page);
  const after = await activity(page);
  expect(after.open, 'back on Activity, its details are still open').toBe(true);
  expect(after.selected).toBe(key);
}, 90_000);
