// SPDX-License-Identifier: Apache-2.0
// Real Chromium, private Vite server; run with vitest.config.lockdown.ts.
// S6 Activity details, repair after the real-Engine check of 2026-11-12 (System › Activity in the real
// Shell, whose ledger arrives over the shared shell-events stream as an Engine sends it):
//   - safety: going straight from one record's details to another's, every details action acts on the
//     record now shown (Restore to this point, Undo this AI turn, Ask the engine to undo), at 1280×720;
//   - A7: the keyboard stays on a selected running operation when its completed ledger record joins it
//     and moves it past newer records.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
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
const start = Date.now() - 3600_000;
const op = (i, at) => ({ id: 'operation:' + key(i), kind: 'operation', originator: 'agent', transport: 'BUTTON',
  operationId: 'core.open-file', outcome: 'SUCCESS', occurredAt: new Date(at ?? start + i * 1000).toISOString() });
const entries = Array.from({ length: 40 }, (_, i) => op(i));
let ledger = null;
let next = 1000;
/** The Engine records \`count\` newer operations, each as it happens: a ledger update. */
window.__arrive = (count) => {
  for (let i = 0; i < count; i += 1) {
    ledger.enqueue(frame('surface:action-ledger', 'UPDATE', op(next, Date.now()), true));
    next += 1;
  }
};
/** The Engine records an operation's completion: its ledger record, as it happens. */
window.__complete = (operationKey, operationId) => ledger.enqueue(frame('surface:action-ledger', 'UPDATE',
  { id: 'operation:' + operationKey, kind: 'operation', originator: 'user', transport: 'BUTTON', operationId,
    outcome: 'SUCCESS', occurredAt: new Date().toISOString() }, true));
/** What the Engine's receipt for any invocation says. */
window.__receipt = { state: 'running', historySince: 0, phase: 'Working' };
window.fetch = async (input) => {
  const path = new URL(String(input instanceof Request ? input.url : input), location.href).pathname;
  if (path.endsWith('/api/shell-events/stream')) {
    return new Response(new ReadableStream({ start(controller) {
      ledger = controller;
      controller.enqueue(frame('surface:action-ledger', 'LIFECYCLE', { kind: 'connected' }, false));
      controller.enqueue(frame('surface:action-ledger', 'LIFECYCLE', { kind: 'snapshot', entries }, true));
    } }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
  }
  if (path.startsWith('/api/operation-history/019a')) return json(window.__receipt);
  if (path.endsWith('/api/action-ledger')) return json({ entries });
  return json({ entries: [], conversations: [], messages: [] });
};
const { __seedForTest } = await import('/src/api/registry/SurfaceCatalogClient.ts');
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/chrome/Shell.ts');
const effects = await import('/src/shell-v0/substrates/effects/index.ts');
const { CORE_PROVENANCE } = await import('/src/shell-v0/primitives/provenance.ts');
/** A turn the assistant takes in this window: \`steps\` reversible steps; the ledger id and journal id of each. */
window.__recordSteps = (name, steps) => {
  const root = effects.recordEffect({ kind: 'open-pane', paneId: name + '-1' }, CORE_PROVENANCE, { originator: 'agent' });
  const all = [root];
  for (let i = 2; i <= steps; i += 1) {
    all.push(effects.recordEffect({ kind: 'open-pane', paneId: name + '-' + i }, CORE_PROVENANCE, { originator: 'agent', causation: root.id }));
  }
  return all.map((e) => ({ key: e.ledgerId, id: e.id }));
};
/** An operation this window ran whose execution the Engine can be asked to undo. */
window.__recordUndoable = (executionId) => effects.recordEffect({ kind: 'invoke-operation', operationId: 'core.clear-failed-jobs' },
  CORE_PROVENANCE, { originator: 'user', executionId }).ledgerId;
/** An operation this window started, associated with its invocation key, still running. */
window.__recordRunning = (operationKey) => effects.recordEffect({ kind: 'invoke-operation', operationId: 'core.clear-failed-jobs' },
  CORE_PROVENANCE, { originator: 'user', operationKey }).ledgerId;
/** The undo requests this window has dispatched, newest last: what each names. */
window.__undoRequests = () => effects.listJournal().filter((e) => e.effect.kind === 'undo-operation').map((e) => e.effect.executionId);
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL',
  presentation: { labelKey: id, descriptionKey: id }, consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
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
  output = await mkdtemp(path.join(os.tmpdir(), 'activity-details-integrity-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'activity-details-integrity-harness',
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

interface Step { key: string; id: number }

declare global {
  interface Window {
    __deep(tag: string): Element | null;
    __arrive(count: number): void;
    __complete(operationKey: string, operationId: string): void;
    __receipt: unknown;
    __recordSteps(name: string, steps: number): Step[];
    __recordUndoable(executionId: string): string;
    __recordRunning(operationKey: string): string;
    __undoRequests(): string[];
    /** The record the keyboard is on (the key its row shows now), or the element it is on. */
    __focused(): { tag: string; key: string | null };
    /** What Activity's details show: the selected record, and the preview's rows when one is open. */
    __details(): { selected: string | null; preview: string | null; rows: string[] };
  }
}

async function open(): Promise<Page> {
  context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const page = await context.newPage();
  page.on('pageerror', (error) => console.info('pageerror', error.message));
  await page.goto(`${url}#justsearch://surface/core.activity-surface`, { timeout: 45_000 });
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
    window.__focused = () => {
      let el: Element | null = document.activeElement;
      while (el?.shadowRoot?.activeElement) el = el.shadowRoot.activeElement;
      return { tag: el?.localName ?? '', key: (el as HTMLElement | null)?.closest?.('.entry')?.getAttribute('data-entry-key') ?? null };
    };
    window.__details = () => {
      const root = window.__deep('jf-activity-surface')!.shadowRoot!.querySelector('jf-activity-details')?.shadowRoot;
      const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
      const preview = root?.querySelector<HTMLElement>('[data-testid="activity-details-preview"]');
      const selected = window.__deep('jf-action-ledger')!.shadowRoot!.querySelector<HTMLElement>('.entry[aria-selected="true"]');
      return { selected: root?.querySelector('[data-testid="activity-details"]') ? selected?.dataset.entryKey ?? null : null,
        preview: preview?.dataset.preview ?? null,
        rows: [...root?.querySelectorAll('[data-testid="activity-details-preview-row"]') ?? []].map(text) };
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

/** Click the record `key` where it is drawn, as a person does, once the point is really on it. */
async function clickRecord(page: Page, key: string): Promise<void> {
  const locate = (): Promise<{ x: number; y: number; hit: boolean } | null> => page.evaluate((key) => {
    const feed = window.__deep('jf-action-ledger')!;
    const entry = [...feed.shadowRoot!.querySelectorAll<HTMLElement>('.entry')].find((e) => e.dataset.entryKey === key);
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
    return { x, y, hit: !!hit && entry.contains(hit) };
  }, key);
  await expect.poll(async () => (await locate())?.hit ?? false, { timeout: 15_000, message: `the record ${key} can be clicked` }).toBe(true);
  const target = (await locate())!;
  await page.mouse.click(target.x, target.y);
  await settle(page);
}

/** Click the details control `testid` where it is drawn. */
async function clickControl(page: Page, testid: string): Promise<void> {
  const point = await page.evaluate((testid) => {
    const details = window.__deep('jf-activity-surface')!.shadowRoot!.querySelector('jf-activity-details')!;
    const control = details.shadowRoot!.querySelector<HTMLElement>(`[data-testid="${testid}"]`);
    if (!control) return null;
    control.scrollIntoView({ block: 'nearest', behavior: 'instant' });
    const box = control.getBoundingClientRect();
    return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
  }, testid);
  expect(point, `${testid} is in the details`).not.toBeNull();
  await page.mouse.click(point!.x, point!.y);
  await settle(page);
}

const shown = (page: Page): Promise<string | null> => page.evaluate(() => window.__details().selected);

it('going straight from one record\'s details to another\'s, Restore to this point and Undo this AI turn preview the record shown', async () => {
  const page = await open();
  const first = await page.evaluate(() => window.__recordSteps('first', 3));
  const second = await page.evaluate(() => window.__recordSteps('second', 3));
  await settle(page);
  // Restore: the first turn's start, then straight to the second turn's start.
  await clickRecord(page, first[0]!.key);
  await expect.poll(() => shown(page)).toBe(first[0]!.key);
  await clickRecord(page, second[0]!.key);
  await expect.poll(() => shown(page)).toBe(second[0]!.key);
  await clickControl(page, 'activity-details-restore');
  const restore = await page.evaluate(() => window.__details());
  expect(restore.preview, 'Restore previews for the record shown').toBe('restore');
  expect(restore.rows, 'what is done after the record shown, newest first').toEqual(['Open pane "second-3"', 'Open pane "second-2"']);
  await clickControl(page, 'activity-details-preview-cancel');
  // Undo this AI turn: a step of the first turn, then straight to a step of the second, back and forth.
  for (const key of [first[1]!.key, second[1]!.key, first[2]!.key, second[2]!.key]) await clickRecord(page, key);
  await expect.poll(() => shown(page)).toBe(second[2]!.key);
  await clickControl(page, 'activity-details-undo-turn');
  const turn = await page.evaluate(() => window.__details());
  expect(turn.preview, 'Undo this AI turn previews for the record shown').toBe('turn');
  expect(turn.rows.every((row) => row.includes('second-')), `only the shown record's turn: ${turn.rows.join(', ')}`).toBe(true);
  expect(turn.rows).toHaveLength(3);
}, 90_000);

it('going straight from one record\'s details to another\'s, Ask the engine to undo names the record shown', async () => {
  const page = await open();
  const earlier = await page.evaluate(() => window.__recordUndoable('execution-earlier'));
  const current = await page.evaluate(() => window.__recordUndoable('execution-shown'));
  await settle(page);
  await clickRecord(page, earlier);
  await clickRecord(page, current);
  await clickControl(page, 'activity-details-undo-operation');
  expect(await page.evaluate(() => window.__undoRequests()), 'one request, for the execution shown').toEqual(['execution-shown']);
}, 90_000);

it('A7: the keyboard stays on a selected running operation when its completed record joins it past five newer ones', async () => {
  const page = await open();
  const operationKey = '019a0000-0000-7000-8000-00000000abcd';
  const local = await page.evaluate((k) => window.__recordRunning(k), operationKey);
  await settle(page);
  await clickRecord(page, local);
  expect((await page.evaluate(() => window.__focused())).key, 'the keyboard is on the record').toBe(local);
  for (let i = 0; i < 5; i += 1) {
    await page.evaluate(() => window.__arrive(1));
    await settle(page, 60);
  }
  expect((await page.evaluate(() => window.__focused())).key, 'still on the record after newer ones arrived').toBe(local);
  await page.evaluate(() => { window.__receipt = { state: 'complete', historySince: 0, unitsCompleted: 1 }; });
  await page.evaluate((k) => window.__complete(k, 'core.clear-failed-jobs'), operationKey);
  await settle(page, 600);
  const keyboard = await page.evaluate(() => window.__focused());
  expect(keyboard.tag, 'the keyboard is not left on the page').not.toBe('body');
  expect(keyboard.key, 'the keyboard is on the same record, now answering to its operation').toBe(`operation:${operationKey}`);
  const selected = await page.evaluate(() => window.__deep('jf-action-ledger')!.shadowRoot!
    .querySelector<HTMLElement>('.entry[aria-selected="true"]')?.dataset.entryKey ?? null);
  expect(selected, 'the selection followed it').toBe(`operation:${operationKey}`);
}, 90_000);
