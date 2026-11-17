// SPDX-License-Identifier: Apache-2.0
// fm-03 / fm-05 in the real app: an emptied search field is the idle screen (the headline, the empty
// focused field, recent searches; no rows, no filter row, no pane), and the pane on a scanned file
// shows its text without the scan's page markers — in dark and light, at 600 px and with 200 % text.
// Real Shell and Search in private headless Chromium over a private Vite server and a stubbed backend
// (no Engine, no shared dev stack). Run with
// npx vitest run --config vitest.config.lockdown.ts src/shell-v0/chrome/FirstMinutes.clearedField-lockdown.test.ts
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const SEARCH = 'core.search-v3-surface';

// "inspection" finds a mail, a Word file and the scanned PDF, whose preview carries the OCR page
// markers the Engine stores between a scan's pages; "harbor" finds three text files.
const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
const SCAN = 'scanned-delivery\\n--- OCR page 1 ---\\nHARBOUR INSPECTION\\nCobalt marmalade delivery record\\nThe warehouse received twelve crates. Inspection complete.\\n--- OCR page 2 ---\\nNo damage found.';
const json = (body) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
const files = {
  inspection: [['message.eml', 'binary', 'message/rfc822'], ['schedule.docx', 'office', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'], ['scanned-delivery.pdf', 'pdf', 'application/pdf']],
  harbor: [['note-1.txt', 'text', 'text/plain'], ['note-2.txt', 'text', 'text/plain'], ['note-3.txt', 'text', 'text/plain']],
  ledger: [['ledger.sqlite', 'binary', 'application/x-sqlite3'], ['reply.eml', 'binary', 'message/rfc822'],
    ['ledger.bin', 'binary', 'application/octet-stream'], ['ledger.pdf', 'pdf', 'application/pdf']],
};
const hit = ([name, kind, mime], i) => ({ id: 'doc-' + name, score: 1 - i / 10, fields: { title: name, filename: name, path: '/home/reader/Documents/' + name,
  content_preview: 'The silver pelican inspection near the harbor.', collection: 'docs', mime, mime_base: mime, file_kind: kind, language: 'en',
  modified_at: '2025-11-12T10:30:00Z', size_bytes: '157' }, matchedFields: ['content'], matchSpans: [], excerptRegions: [] });
window.searches = [];
window.held = [];
window.holdSearches = false;
window.fetch = async (input, init) => {
  const url = decodeURIComponent(String(input?.url ?? input));
  if (url.includes('/api/knowledge/search')) {
    const body = JSON.parse(String(init?.body ?? '{}'));
    window.searches.push(body);
    const query = String(body.query ?? '');
    const all = files[Object.keys(files).find(name => query.includes(name)) ?? 'inspection'];
    if (window.holdSearches) await new Promise(resolve => window.held.push(resolve));
    const kinds = body.filters?.fileKind ?? [], types = body.filters?.mimeBase ?? [];
    const found = all.filter(f => (!kinds.length || kinds.includes(f[1])) && (!types.length || types.includes(f[2])));
    const counts = { file_kind: {}, mime_base: {} };
    for (const f of found) { counts.file_kind[f[1]] = (counts.file_kind[f[1]] ?? 0) + 1; counts.mime_base[f[2]] = (counts.mime_base[f[2]] ?? 0) + 1; }
    return json({ totalHits: found.length, matchCount: found.length, tookMs: 4, results: found.map(hit), nextCursor: null,
      facets: counts, facetsTruncated: false });
  }
  if (url.includes('/api/preview')) return json(url.includes('scanned-delivery')
    ? { content: SCAN, totalChars: SCAN.length, textProvenance: 'ocr' }
    : { content: 'The silver pelican inspection.', totalChars: 30 });
  return json({ entries: [], conversations: [] });
};
window.EventSource = class { constructor() { this.readyState = 0; } addEventListener() {} removeEventListener() {} close() {} };
const { __seedForTest } = await import('/src/api/registry/SurfaceCatalogClient.ts');
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/chrome/Shell.ts');
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL',
  presentation: { labelKey: id, descriptionKey: id }, consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('${SEARCH}', 'jf-sv3-window'),
  surface('core.settings-surface', 'jf-settings-surface', { placement: 'MODAL' }),
] });
const { applyAppearance } = await import('/src/shell-v0/state/themeState.ts');
await applyAppearance({ theme: 'system' }, async () => json({}));
document.body.append(document.createElement('jf-shell'));
document.body.dataset.ready = 'true';
</script></body></html>`;

type Palette = 'dark' | 'light';

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let context: BrowserContext | undefined;
let output: string;
let url: string;

beforeAll(async () => {
  output = await mkdtemp(path.join(process.env.TMPDIR ?? tmpdir(), 'cleared-field-'));
  server = await createServer({
    configFile: false, root: process.cwd(), cacheDir: output, publicDir: 'public', logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{ name: 'cleared-field-harness', configureServer(vite) {
      vite.middlewares.use((req, res, next) => {
        if (req.url?.split('?')[0] !== '/app') return next();
        res.setHeader('Content-Type', 'text/html');
        res.end(HARNESS);
      });
    } }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/app`;
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

async function settle(page: Page, ms = 200): Promise<void> {
  await page.waitForTimeout(ms);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

async function ready(page: Page): Promise<void> {
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 90_000 });
  await page.locator('jf-top-bar').waitFor();
  await settle(page, 400);
}

/** The packaged app's window size unless told otherwise; the theme comes from the system. */
async function open(palette: Palette, width = 1024, height = 700): Promise<Page> {
  context = await browser.newContext({ viewport: { width, height }, reducedMotion: 'reduce', colorScheme: palette });
  const page = await context.newPage();
  page.on('pageerror', error => console.info('pageerror', error.message));
  await page.goto(url, { timeout: 90_000 });
  await ready(page);
  expect(await page.evaluate(() => document.documentElement.dataset.theme)).toBe(palette);
  return page;
}

const field = (page: Page) => page.locator('[data-testid="sv3-composer-input"]');
const searches = (page: Page) => page.evaluate(() => (window as unknown as { searches: unknown[] }).searches.length);
const rows = (page: Page) => page.locator('[data-testid="search-result-row"]');
const paneText = (page: Page) => page.locator('jf-document-pane [role="region"][aria-label="Document content"]');

async function search(page: Page, query: string): Promise<void> {
  const before = await searches(page);
  await field(page).fill(query);
  await field(page).press('Enter');
  await expect.poll(() => searches(page), { timeout: 15_000 }).toBeGreaterThan(before);
  await rows(page).first().waitFor({ timeout: 15_000 });
  await settle(page, 300);
}

async function openScan(page: Page): Promise<void> {
  await rows(page).filter({ hasText: 'scanned-delivery.pdf' }).click();
  await paneText(page).first().waitFor();
  await page.mouse.move(1, 690);
  await settle(page, 300);
}

const emptyBy = {
  Clear: async (page: Page) => { await page.locator('[data-testid="sv3-search-clear"]').click(); await settle(page, 400); },
  keyboard: async (page: Page) => {
    await field(page).focus();
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.press('Backspace');
    await settle(page, 400);
  },
} as const;

/** What Search shows, read from the rendered page: the idle screen's parts and the results' parts. */
const shown = (page: Page) => page.evaluate(() => {
  const deep = (root: Document | ShadowRoot, selector: string): Element[] => {
    const out = [...root.querySelectorAll(selector)];
    for (const el of root.querySelectorAll('*')) if (el.shadowRoot) out.push(...deep(el.shadowRoot, selector));
    return out;
  };
  const visible = (el: Element): boolean => {
    const box = el.getBoundingClientRect();
    return box.width > 0 && box.height > 0 && box.bottom > 0 && box.top < innerHeight && box.right > 0 && box.left < innerWidth;
  };
  const textarea = deep(document, '[data-testid="sv3-composer-input"]')[0] as HTMLTextAreaElement;
  const recents = deep(document, '[data-testid="sv3-recents"]').filter(visible);
  return {
    headline: deep(document, '#sv3-search-headline').filter(visible).map(el => el.textContent?.trim() !== ''),
    value: textarea.value,
    focused: textarea === (textarea.getRootNode() as ShadowRoot).activeElement,
    fieldVisible: visible(textarea),
    recent: recents.flatMap(list => [...list.querySelectorAll('.recent-query')].map(item => item.textContent?.trim())),
    rows: deep(document, '[data-testid="search-result-row"]').filter(visible).length,
    cards: deep(document, 'jf-results-card').length,
    filterRow: deep(document, '.result-filters').filter(visible).length,
    pane: deep(document, '[data-testid="details-pane"]').length + deep(document, 'jf-document-pane').length,
  };
});

const idle = (recent: string[]) => ({ headline: [true], value: '', focused: true, fieldVisible: true, recent, rows: 0, cards: 0, filterRow: 0, pane: 0 });

describe('Q1 an emptied search field is the idle screen', () => {
  for (const palette of ['dark', 'light'] as const) for (const how of ['Clear', 'keyboard'] as const) {
    it(`after a search with the pane open, emptying it by ${how} shows the idle screen (${palette})`, async () => {
      const page = await open(palette);
      await search(page, 'inspection');
      await openScan(page);
      expect((await shown(page)).pane, 'the pane is open').toBeGreaterThan(0);
      await emptyBy[how](page);
      expect(await shown(page)).toEqual(idle(['inspection']));
    }, 120_000);
  }

  it('at 600 px wide with 200 % text the idle screen is the same, its parts on screen', async () => {
    const page = await open('dark', 600, 700);
    await page.evaluate(() => { document.documentElement.style.fontSize = '200%'; });
    await settle(page);
    await search(page, 'harbor');
    await emptyBy.Clear(page);
    expect(await shown(page)).toEqual(idle(['harbor']));
  }, 120_000);
});

describe('Q2 a late answer to the earlier search does not bring results back', () => {
  it('the field is emptied while the search is out; its answer lands on the idle screen', async () => {
    const page = await open('dark');
    await search(page, 'inspection');
    await page.evaluate(() => { (window as unknown as { holdSearches: boolean }).holdSearches = true; });
    const before = await searches(page);
    await field(page).fill('harbor');
    await field(page).press('Enter');
    await expect.poll(() => searches(page), { timeout: 15_000 }).toBeGreaterThan(before);
    await emptyBy.Clear(page);
    await page.evaluate(() => {
      const w = window as unknown as { holdSearches: boolean; held: Array<() => void> };
      w.holdSearches = false;
      for (const release of w.held.splice(0)) release();
    });
    await settle(page, 800);
    expect(await shown(page)).toEqual(idle(['harbor', 'inspection']));
  }, 120_000);
});

describe('Q5 a reload with an emptied field opens on the idle screen', () => {
  it('emptied, then reloaded', async () => {
    const page = await open('light');
    await search(page, 'inspection');
    await emptyBy.keyboard(page);
    await page.reload({ timeout: 90_000 });
    await ready(page);
    await field(page).focus();
    await settle(page);
    const after = await shown(page);
    expect({ ...after, recent: [] }).toEqual(idle([]));
  }, 120_000);
});

describe('Q6 the pane on a scanned file shows its text without page markers', () => {
  it.each(['dark', 'light'] as const)('no "--- OCR page N ---" line; the text and found words stay (%s)', async palette => {
    const page = await open(palette);
    await search(page, 'inspection');
    await openScan(page);
    const lines = await page.locator('jf-document-pane pre.source span[data-line]').evaluateAll(spans =>
      spans.map(span => [span.getAttribute('data-line'), span.textContent!.replace(/\n$/, '')]));
    expect(lines).toEqual([
      ['0', 'scanned-delivery'],
      ['2', 'HARBOUR INSPECTION'],
      ['3', 'Cobalt marmalade delivery record'],
      ['4', 'The warehouse received twelve crates. Inspection complete.'],
      ['6', 'No damage found.'],
    ]);
    expect(await paneText(page).innerText()).not.toContain('OCR page');
    expect((await page.locator('jf-document-pane pre.source mark.hl').allTextContents()).map(word => word.toLowerCase()))
      .toEqual(['inspection', 'inspection']);
  }, 120_000);
});
