// SPDX-License-Identifier: Apache-2.0
// Real Chromium, private Vite server; run with vitest.config.lockdown.ts.
// S6 Activity, candidate 2 — A17 and A20 for the details in the real Shell (System › Activity, whose
// ledger arrives over the shared shell-events stream as an Engine sends it, and keeps arriving):
//   - with records chosen for a macro or an export, every details control and the choice's own
//     controls can be reached and are where a click lands, at 1280×720 and at 600×400 with 200% text;
//   - Cancel on a Restore preview puts the keyboard back on the control that opened it;
//   - Settings opened and closed with the keyboard returns it to the same record, also when new
//     records arrived meanwhile and the row that showed it now shows another.
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
const rows = Number(params.get('rows') ?? '40');
const entries = Array.from({ length: rows }, (_, i) => op(i));
let ledger = null;
let next = rows;
/** The Engine records \`count\` newer operations: each arrives as a ledger update. */
window.__arrive = (count) => {
  for (let i = 0; i < count; i += 1) {
    ledger.enqueue(frame('surface:action-ledger', 'UPDATE', op(next), true));
    next += 1;
  }
};
window.fetch = async (input) => {
  const path = new URL(String(input instanceof Request ? input.url : input), location.href).pathname;
  if (path.endsWith('/api/shell-events/stream')) {
    // The Engine on connect: the ledger channel's connected (no checkpoint), then its snapshot; updates follow.
    return new Response(new ReadableStream({ start(controller) {
      ledger = controller;
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
// A turn the assistant takes in this window once the app is up: two reversible steps.
window.__recordTurn = async () => {
  const effects = await import('/src/shell-v0/substrates/effects/index.ts');
  const { CORE_PROVENANCE } = await import('/src/shell-v0/primitives/provenance.ts');
  const root = effects.recordEffect({ kind: 'open-pane', paneId: 'sources' }, CORE_PROVENANCE, { originator: 'agent' });
  effects.recordEffect({ kind: 'open-pane', paneId: 'outline' }, CORE_PROVENANCE, { originator: 'agent', causation: root.id });
  return root.ledgerId;
};
// The Search window and Settings stand in as probes: a window with a control and no details of its own.
for (const tag of ['jf-reach-search-probe', 'jf-reach-settings-probe']) {
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
  surface('core.search-v3-surface', 'jf-reach-search-probe'),
  surface('core.system-surface', 'jf-system-surface', { members: ['core.health-surface', 'core.logs-surface', 'core.activity-surface'] }),
  surface('core.health-surface', 'jf-health-surface', { placement: 'DEEPLINK', altitude: 'DIAGNOSTIC' }),
  surface('core.logs-surface', 'jf-log-surface', { placement: 'DEEPLINK', altitude: 'DIAGNOSTIC' }),
  surface('core.activity-surface', 'jf-activity-surface', { placement: 'DEEPLINK', altitude: 'TRUST' }),
  surface('core.settings-surface', 'jf-reach-settings-probe', { placement: 'MODAL' }),
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
  output = await mkdtemp(path.join(os.tmpdir(), 'activity-details-reach-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'activity-details-reach-harness',
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

interface Reach { testid: string; found: boolean; hit: string | null; visibleHeight: number }

declare global {
  interface Window {
    __deep(tag: string): Element | null;
    __recordTurn(): Promise<string>;
    __arrive(count: number): void;
    /** Where the keyboard is: the record it is on, and the test ids of the elements around it. */
    __keyboard(): { tag: string; key: string | null; testids: string[] };
    /** Bring the control `testid` of Activity's details into view and say what a click at its centre lands on. */
    __reach(testid: string): Reach;
  }
}

interface Options { width: number; height: number; text?: string }

async function open({ width, height, text = '' }: Options): Promise<Page> {
  context = await browser.newContext({ viewport: { width, height } });
  const page = await context.newPage();
  page.on('pageerror', (error) => console.info('pageerror', error.message));
  const query = new URLSearchParams({ ...(text ? { text } : {}) }).toString();
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
    const trailOf = (el: Element | null): Element[] => {
      const trail: Element[] = [];
      for (let node: Node | null = el; node; node = node instanceof ShadowRoot ? node.host : (node as Element).assignedSlot ?? node.parentNode) {
        if (node instanceof Element) trail.push(node);
      }
      return trail;
    };
    window.__keyboard = () => {
      let el: Element | null = document.activeElement;
      while (el?.shadowRoot?.activeElement) el = el.shadowRoot.activeElement;
      return { tag: el?.localName ?? '', key: (el as HTMLElement | null)?.closest?.('.entry')?.getAttribute('data-entry-key') ?? null,
        testids: trailOf(el).map((n) => n.getAttribute('data-testid')).filter((t): t is string => !!t) };
    };
    window.__reach = (testid) => {
      const surface = window.__deep('jf-activity-surface')!;
      const details = surface.shadowRoot!.querySelector('jf-activity-details')!;
      const control = details.shadowRoot!.querySelector<HTMLElement>(`[data-testid="${testid}"]`)
        ?? surface.shadowRoot!.querySelector<HTMLElement>(`[data-testid="${testid}"]`);
      if (!control) return { testid, found: false, hit: null, visibleHeight: 0 };
      control.scrollIntoView({ block: 'nearest', behavior: 'instant' });
      const box = control.getBoundingClientRect();
      const x = box.x + box.width / 2;
      const y = box.y + box.height / 2;
      let hit: Element | null = document.elementFromPoint(x, y);
      while (hit?.shadowRoot) {
        const inner = hit.shadowRoot.elementFromPoint(x, y);
        if (!inner || inner === hit) break;
        hit = inner;
      }
      // What the click lands on, named by the nearest test id around it.
      const named = trailOf(hit).find((n) => n.hasAttribute('data-testid'))?.getAttribute('data-testid') ?? hit?.localName ?? null;
      return { testid, found: true, hit: hit && trailOf(hit).includes(control) ? testid : named, visibleHeight: details.clientHeight };
    };
  });
  await expect.poll(() => page.evaluate(() => {
    const feed = window.__deep('jf-action-ledger');
    feed?.scrollIntoView({ block: 'start' });
    return feed?.shadowRoot?.querySelectorAll('.entry').length ?? 0;
  }), { timeout: 15_000 }).toBeGreaterThan(0);
  return page;
}

async function settle(page: Page, ms = 300): Promise<void> {
  await page.waitForTimeout(ms);
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

/** Click the record whose key is `key` (or the first one on screen), where it is drawn, once the point is really on it. */
async function clickRecord(page: Page, key?: string): Promise<string> {
  const locate = (): Promise<{ x: number; y: number; key: string; hit: boolean } | null> => page.evaluate((key) => {
    const feed = window.__deep('jf-action-ledger')!;
    const entry = [...feed.shadowRoot!.querySelectorAll<HTMLElement>('.entry')]
      .find((e) => (key ? e.dataset.entryKey === key : e.getBoundingClientRect().top >= 0));
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
  }, key);
  await expect.poll(async () => (await locate())?.hit ?? false, { timeout: 15_000, message: 'the record can be clicked' }).toBe(true);
  const target = (await locate())!;
  await page.mouse.click(target.x, target.y);
  await settle(page);
  return target.key;
}

function detailsOpen(page: Page): Promise<boolean> {
  return page.evaluate(() => !!window.__deep('jf-activity-surface')!.shadowRoot!.querySelector('jf-details-pane'));
}

/** Activate the details control `testid` from the keyboard: focus it, then Enter. */
async function press(page: Page, testid: string): Promise<void> {
  await page.evaluate((testid) => {
    const details = window.__deep('jf-activity-surface')!.shadowRoot!.querySelector('jf-activity-details')!;
    const host = details.shadowRoot!.querySelector<HTMLElement>(`[data-testid="${testid}"]`)!;
    (host.shadowRoot?.querySelector<HTMLElement>('button') ?? host).focus();
  }, testid);
  await page.keyboard.press('Enter');
  await settle(page);
}

for (const [width, height, text] of [[1280, 720, ''], [600, 400, '200%']] as const) {
  it(`at ${width}×${height}${text ? ` with ${text} text` : ''}, with a record chosen, every details control and the choice's controls are reached where a click lands`, async () => {
    const page = await open({ width, height, text });
    const journalKey = await page.evaluate(() => window.__recordTurn());
    await clickRecord(page, journalKey);
    await expect.poll(() => detailsOpen(page), { timeout: 5_000 }).toBe(true);
    // Choose the record for a macro or an export, as a person does: the box, by a click where it is.
    const box = await page.evaluate(() => window.__reach('activity-details-choose'));
    expect(box.hit, 'the choose box is where a click lands').toBe('activity-details-choose');
    await page.evaluate(() => {
      const details = window.__deep('jf-activity-surface')!.shadowRoot!.querySelector('jf-activity-details')!;
      details.shadowRoot!.querySelector<HTMLInputElement>('[data-testid="activity-details-choose"]')!.click();
    });
    await settle(page);
    expect(await page.evaluate(() => !!window.__deep('jf-activity-surface')!.shadowRoot!
      .querySelector('[data-testid="activity-details-chosen"]')), 'the choice\'s controls are shown').toBe(true);

    for (const testid of ['activity-details-title', 'activity-details-restore', 'activity-details-choose',
      'activity-save-chosen', 'activity-export-chosen', 'activity-clear-chosen']) {
      const reach = await page.evaluate((testid) => window.__reach(testid), testid);
      expect(reach.found, `${testid} is in the details`).toBe(true);
      expect(reach.hit, `a click aimed at ${testid} lands on it`).toBe(testid);
    }
    // The details keep room to be read: the choice does not squeeze them to a sliver.
    const reach = await page.evaluate(() => window.__reach('activity-details-title'));
    const sheet = await page.evaluate(() => window.__deep('jf-activity-surface')!.shadowRoot!.querySelector('jf-details-pane')!
      .shadowRoot!.querySelector('.surface')!.getBoundingClientRect().height);
    expect(reach.visibleHeight, 'the details keep at least a third of the pane').toBeGreaterThanOrEqual(sheet / 3);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth), 'no sideways page overflow')
      .toBeLessThanOrEqual(0);
  }, 90_000);
}

it('Cancel on a Restore preview puts the keyboard back on Restore to this point', async () => {
  const page = await open({ width: 1280, height: 720 });
  const journalKey = await page.evaluate(() => window.__recordTurn());
  await clickRecord(page, journalKey);
  await expect.poll(() => detailsOpen(page), { timeout: 5_000 }).toBe(true);
  await press(page, 'activity-details-restore');
  expect((await page.evaluate(() => window.__reach('activity-details-preview'))).found, 'the preview is shown').toBe(true);
  await press(page, 'activity-details-preview-cancel');
  expect((await page.evaluate(() => window.__reach('activity-details-preview'))).found, 'Cancel closed the preview').toBe(false);
  await expect.poll(async () => (await page.evaluate(() => window.__keyboard())).testids, { timeout: 5_000,
    message: 'the keyboard is on the control that opened the preview' }).toContain('activity-details-restore');
}, 90_000);

it('Settings opened and closed with the keyboard returns it to the same record, after new records arrived meanwhile', async () => {
  const page = await open({ width: 1280, height: 720 });
  const key = await clickRecord(page);
  await expect.poll(() => detailsOpen(page), { timeout: 5_000 }).toBe(true);
  expect((await page.evaluate(() => window.__keyboard())).key, 'the keyboard is on the record').toBe(key);
  await page.keyboard.press('Control+Comma');
  const probe = page.locator('jf-reach-settings-probe');
  await probe.waitFor({ state: 'visible', timeout: 5_000 });
  await page.evaluate(() => window.__arrive(3));
  await settle(page, 600);
  await page.keyboard.press('Escape');
  await settle(page, 600);
  expect(await probe.isVisible(), 'Escape closed Settings').toBe(false);
  await expect.poll(async () => (await page.evaluate(() => window.__keyboard())).key, { timeout: 5_000,
    message: 'the keyboard is back on the same record' }).toBe(key);
}, 90_000);
