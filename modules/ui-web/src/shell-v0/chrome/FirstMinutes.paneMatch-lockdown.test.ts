// SPDX-License-Identifier: Apache-2.0
// fm-pane-match in the real app: a search result opened in the side pane shows the place that
// matched, inside the pane's visible area without scrolling, for a passage beyond the first window
// of a long file and for one below the fold of a file that fits in one window. Real Shell and Search
// in private headless Chromium over a private Vite server and a stubbed backend (no Engine, no
// shared dev stack); `/api/preview` is served by offset. Run with
// npx vitest run --config vitest.config.lockdown.ts src/shell-v0/chrome/FirstMinutes.paneMatch-lockdown.test.ts
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

vi.setConfig({ testTimeout: 30_000 });

const FOLDER = '/home/reader/Documents';
const LONG_PASSAGE = 'The harbour gate opens at dawn for the inspection boats and closes at dusk.';
const SHORT_PASSAGE = 'Harbour note: the ferry waits by the northern pier until the tide turns.';

const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const pad = (i) => String(i).padStart(4, '0');
// A long manual: the passage sits near character 12,000, far past the pane's first window.
const manual = ['# Long manual', '', ...Array.from({ length: 300 }, (_, i) => 'Section ' + pad(i) + ' covers routine upkeep of the yard.'),
  '', '## Gates', '', '${LONG_PASSAGE}', '', ...Array.from({ length: 200 }, (_, i) => 'Appendix ' + pad(i) + ' lists the spare parts.')].join('\\n');
// Short notes: the whole file fits in one window, and the passage is far below the fold.
const notes = [...Array.from({ length: 70 }, (_, i) => 'Note ' + pad(i) + ' about the garden beds.'), '${SHORT_PASSAGE}',
  ...Array.from({ length: 10 }, (_, i) => 'Tail ' + pad(i) + ' of the notes.')].join('\\n');
const docs = { '${FOLDER}/long-manual.md': manual, '${FOLDER}/short-notes.txt': notes };
const region = (doc, text) => { const startChar = docs[doc].indexOf(text); return { text, startChar, endChar: startChar + text.length, approxLine: 1, matchSpans: [] }; };
const hit = (doc, text, i) => { const name = doc.split('/').pop(); return { id: 'doc-' + i, score: 1 - i / 10,
  fields: { title: name, filename: name, path: doc, content_preview: text, collection: 'docs', mime: name.endsWith('.md') ? 'text/markdown' : 'text/plain',
    mime_base: 'text', file_kind: name.endsWith('.md') ? 'markdown' : 'text', language: 'en', modified_at: '2025-06-15T10:30:00Z', size_bytes: String(docs[doc].length) },
  matchedFields: ['content'], matchSpans: [], excerptRegions: [region(doc, text)] }; };
window.previews = [];
window.previewDelay = 0;
window.fetch = async (input, init) => {
  const url = String(input?.url ?? input);
  if (url.includes('/api/preview')) {
    const params = new URL(url, location.href).searchParams;
    const doc = params.get('docId'); const offset = Number(params.get('offsetChars')); const max = Number(params.get('maxChars'));
    window.previews.push({ doc, offset });
    if (window.previewDelay) await new Promise(resolve => setTimeout(resolve, window.previewDelay));
    const text = docs[doc] ?? '';
    return json({ content: text.slice(offset, offset + max), truncated: offset + max < text.length, offsetChars: offset, totalChars: text.length });
  }
  if (url.includes('/api/knowledge/search')) return json({ totalHits: 2, matchCount: 2, tookMs: 4, nextCursor: null, facets: {}, facetsTruncated: false,
    results: [hit('${FOLDER}/long-manual.md', '${LONG_PASSAGE}', 0), hit('${FOLDER}/short-notes.txt', '${SHORT_PASSAGE}', 1)] });
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
  surface('core.search-v3-surface', 'jf-sv3-window'), surface('core.help-surface', 'jf-help-surface'),
  surface('core.settings-surface', 'jf-settings-surface', { placement: 'MODAL' }),
] });
const { applyAppearance } = await import('/src/shell-v0/state/themeState.ts');
await applyAppearance({ theme: 'system' }, async () => json({}));
document.body.append(document.createElement('jf-shell'));
document.body.dataset.ready = 'true';
</script></body></html>`;

type Palette = 'dark' | 'light';
type AppWindow = Window & { previews: Array<{ doc: string; offset: number }>; previewDelay: number };

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let context: BrowserContext | undefined;
let output: string;
let url: string;

beforeAll(async () => {
  output = await mkdtemp(path.join(process.env.TMPDIR ?? tmpdir(), 'pane-match-'));
  server = await createServer({
    configFile: false, root: process.cwd(), cacheDir: output, publicDir: 'public', logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{ name: 'pane-match-harness', configureServer(vite) {
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

/** The packaged app's window size; the theme comes from the system, as on first launch. */
async function open(palette: Palette, width = 1024, height = 700): Promise<Page> {
  context = await browser.newContext({ viewport: { width, height }, reducedMotion: 'reduce', colorScheme: palette });
  const page = await context.newPage();
  page.on('pageerror', error => console.info('pageerror', error.message));
  await page.goto(url, { timeout: 90_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 90_000 });
  await page.locator('jf-top-bar').waitFor();
  await settle(page, 400);
  expect(await page.evaluate(() => document.documentElement.dataset.theme)).toBe(palette);
  return page;
}

async function search(page: Page): Promise<void> {
  await page.locator('jf-sv3-composer textarea').fill('harbour');
  await page.keyboard.press('Enter');
  await page.locator('[data-testid="search-result-row"]').nth(1).waitFor({ timeout: 15_000 });
  await settle(page);
}

const rows = (page: Page) => page.locator('[data-testid="search-result-row"]');
const paneText = (page: Page) => page.locator('jf-document-pane [role="region"][aria-label="Document content"]');

/** Wait for the pane's text, then for the scroll to land; the reader does not move anything. */
async function shown(page: Page): Promise<void> {
  await paneText(page).first().waitFor({ timeout: 15_000 });
  await page.mouse.move(1, 1);
  await settle(page, 400);
}

/**
 * Where the passage is on screen: the element the pane tints for the passage, its words, and whether
 * it lies wholly inside the pane's scroll area and inside the window, with no scrolling by the reader.
 */
async function passage(page: Page): Promise<{ text: string; marks: string[]; inPane: boolean; inWindow: boolean; scrolled: number } | null> {
  const tint = page.locator('jf-document-pane .hl-weak');
  if (await tint.count() === 0) return null;
  return tint.first().evaluate(element => {
    const area = (element.getRootNode() as ShadowRoot).querySelector('.scroll-region')!;
    const box = element.getBoundingClientRect();
    const frame = area.getBoundingClientRect();
    return {
      text: (element.textContent ?? '').trim(),
      marks: [...element.querySelectorAll('mark.hl')].map(mark => mark.textContent ?? ''),
      inPane: box.height > 0 && box.top >= frame.top - 0.5 && box.bottom <= frame.bottom + 0.5,
      inWindow: box.top >= 0 && box.bottom <= window.innerHeight && box.left >= 0 && box.right <= window.innerWidth,
      scrolled: area.scrollTop,
    };
  });
}
const note = (page: Page): Promise<string | null> =>
  page.locator('jf-document-pane .window-note').first().textContent({ timeout: 1000 }).then(t => t?.trim() ?? null).catch(() => null);
const previews = (page: Page): Promise<Array<{ doc: string; offset: number }>> =>
  page.evaluate(() => (window as unknown as AppWindow).previews);

describe.each(['dark', 'light'] as const)('a result opened in the side pane shows its passage (%s, 1024x700)', palette => {
  it(`a long file: the window around the passage, the passage visible without scrolling, its found word marked (${palette})`, async () => {
    const page = await open(palette);
    await search(page);
    await rows(page).nth(0).click();
    await shown(page);
    const requested = await previews(page);
    expect(requested).toHaveLength(1);
    expect(requested[0]!.offset).toBeGreaterThan(0);
    expect(await passage(page)).toMatchObject({ text: LONG_PASSAGE, marks: ['harbour'], inPane: true, inWindow: true });
    expect(await note(page)).toBe('Showing the part of this document around the match.');
  });

  it(`a file that fits in one window, opened by keyboard: the passage below the fold is visible without scrolling (${palette})`, async () => {
    const page = await open(palette);
    await search(page);
    await rows(page).nth(1).focus();
    await page.keyboard.press('Enter');
    await shown(page);
    expect(await previews(page)).toEqual([{ doc: `${FOLDER}/short-notes.txt`, offset: 0 }]);
    const place = await passage(page);
    expect(place).toMatchObject({ text: SHORT_PASSAGE, marks: ['Harbour'], inPane: true, inWindow: true });
    expect(place!.scrolled).toBeGreaterThan(0);
  });
});

describe('moving, late answers, a narrow window and switching areas', () => {
  it('moving to another result while the pane is open shows that result\'s passage (dark)', async () => {
    const page = await open('dark');
    await search(page);
    await rows(page).nth(1).click();
    await shown(page);
    await rows(page).nth(0).click();
    await shown(page);
    expect(await passage(page)).toMatchObject({ text: LONG_PASSAGE, inPane: true, inWindow: true });
  });

  it('a preview that answers late still lands on the passage (light)', async () => {
    const page = await open('light');
    await search(page);
    await page.evaluate(() => { (window as unknown as AppWindow).previewDelay = 1500; });
    await rows(page).nth(0).click();
    await settle(page, 300);
    expect(await page.locator('jf-document-pane').getByText('Loading…').count()).toBe(1);
    await shown(page);
    await settle(page, 1500);
    expect(await passage(page)).toMatchObject({ text: LONG_PASSAGE, inPane: true, inWindow: true });
  });

  it('at 600 px wide with 200 % text the passage is visible without scrolling (dark)', async () => {
    const page = await open('dark', 600, 700);
    await page.evaluate(() => { document.documentElement.style.fontSize = '200%'; });
    await search(page);
    await rows(page).nth(0).click();
    await shown(page);
    expect(await passage(page)).toMatchObject({ text: LONG_PASSAGE, inPane: true, inWindow: true });
  });

  it('leaving Search and coming back shows the passage again (light)', async () => {
    const page = await open('light');
    await search(page);
    await rows(page).nth(0).click();
    await shown(page);
    await page.evaluate(() => { location.hash = '#justsearch://surface/core.help-surface'; });
    await page.locator('jf-help-surface').waitFor();
    await page.evaluate(() => { location.hash = '#justsearch://surface/core.search-v3-surface'; });
    await page.locator('jf-sv3-window').waitFor();
    await settle(page, 600);
    expect(await passage(page)).toMatchObject({ text: LONG_PASSAGE, inPane: true, inWindow: true });
  });
});
