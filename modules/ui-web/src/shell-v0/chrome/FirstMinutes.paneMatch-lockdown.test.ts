// SPDX-License-Identifier: Apache-2.0
// fm-pane-match in the real app: a search result opened in the side pane shows the place that
// matched, inside the pane's visible area without scrolling, for a passage beyond the first window
// of a long file and for one below the fold of a file that fits in one window. Long files are found
// through their chunks, as the Engine sends them (SearchResponseBuilder: a chunk hit's `fields` carry
// `parent_doc_id`, `chunk_start_char`, `chunk_end_char` as strings, and its excerpt regions count from
// the chunk's start); the short file is a whole-file hit with file positions. Real Shell and Search
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
const DEEP_PASSAGE = 'Deep passage: the harbour crane lifts the last container at noon.';
/** deep-long.txt as the verifier saw it: the chunk starts at 249,619 and the region at 345 of it. */
const DEEP_CHUNK_START = 249_619;
const DEEP_AT = DEEP_CHUNK_START + 345;
/** A file of identical lines (23 characters with the break); the passage is five of them in a chunk at line 2,400. */
const SAME = 'The harbour wall line.';
const REPEAT_CHUNK_START = 2400 * 23;
const REPEAT_REGION = { start: 345, end: 345 + 5 * 23 - 1 };

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
// Lines of one length up to exactly that many characters; the deep passage then starts at 249,964.
const fillTo = (length) => { let out = ''; for (let i = 0; length - out.length >= 48; i++) out += 'Deep filler ' + pad(i) + ' of the long log....\\n';
  return out + '.'.repeat(length - out.length - 1) + '\\n'; };
const deep = fillTo(${DEEP_AT}) + '${DEEP_PASSAGE}\\n' + fillTo(6000);
const repeated = Array.from({ length: 3000 }, () => '${SAME}').join('\\n');
// N5: a passage of six lines deep in a long log, and one of four lines that ends another log.
const landingPassage = Array.from({ length: 6 }, (_, i) => 'Landing ' + i + ': the harbour pilot boards at the outer buoy.').join('\\n');
const landing = [...Array.from({ length: 600 }, (_, i) => 'Log ' + pad(i) + ' records a quiet watch on deck.'), landingPassage,
  ...Array.from({ length: 300 }, (_, i) => 'Later ' + pad(i) + ' of the log.')].join('\\n');
const tailPassage = Array.from({ length: 4 }, (_, i) => 'Tail ' + i + ': the harbour log ends with the last tide.').join('\\n');
const tail = [...Array.from({ length: 600 }, (_, i) => 'Entry ' + pad(i) + ' notes the wind and the swell.'), tailPassage].join('\\n') + '\\n';
const docs = { '${FOLDER}/long-manual.md': manual, '${FOLDER}/short-notes.txt': notes, '${FOLDER}/deep-long.txt': deep, '${FOLDER}/repeated.txt': repeated,
  '${FOLDER}/landing.txt': landing, '${FOLDER}/tail.txt': tail };
window.docs = docs;
// A whole-file hit's region is the passage's place in the file; a chunk hit's counts from its chunk's start.
const region = (text, startChar) => ({ text, startChar, endChar: startChar + text.length, approxLine: 1, matchSpans: [] });
const chunk = (doc, start, end) => ({ doc_id: 'chunk-' + start, parent_doc_id: doc, chunk_index: '3', chunk_total: '9',
  chunk_start_char: String(start), chunk_end_char: String(end), chunk_start_line: '0', chunk_end_line: '0' });
const hit = (doc, text, i, place) => { const name = doc.split('/').pop(); return { id: 'doc-' + i, score: 1 - i / 10,
  fields: { title: name, filename: name, path: doc, content_preview: text, collection: 'docs', mime: name.endsWith('.md') ? 'text/markdown' : 'text/plain',
    mime_base: 'text', file_kind: name.endsWith('.md') ? 'markdown' : 'text', language: 'en', modified_at: '2025-06-15T10:30:00Z', size_bytes: String(docs[doc].length),
    ...(place.chunk ?? {}) },
  matchedFields: ['content'], matchSpans: [], excerptRegions: [region(text, place.at)] }; };
const manualAt = manual.indexOf('${LONG_PASSAGE}');
const manualChunk = manualAt - 1500;
window.previews = [];
window.previewDelay = 0;
window.fetch = async (input, init) => {
  const url = String(input?.url ?? input);
  if (url.includes('/api/preview')) {
    const params = new URL(url, location.href).searchParams;
    const doc = params.get('docId'); const offset = Number(params.get('offsetChars')); const max = Number(params.get('maxChars'));
    window.previews.push({ doc, offset, max });
    if (window.previewDelay) await new Promise(resolve => setTimeout(resolve, window.previewDelay));
    const text = docs[doc] ?? '';
    return json({ content: text.slice(offset, offset + max), truncated: offset + max < text.length, offsetChars: offset, totalChars: text.length });
  }
  if (url.includes('/api/knowledge/search')) return json({ totalHits: 6, matchCount: 6, tookMs: 4, nextCursor: null, facets: {}, facetsTruncated: false,
    results: [
      hit('${FOLDER}/long-manual.md', '${LONG_PASSAGE}', 0, { at: manualAt - manualChunk, chunk: chunk('${FOLDER}/long-manual.md', manualChunk, manualChunk + 4000) }),
      hit('${FOLDER}/short-notes.txt', '${SHORT_PASSAGE}', 1, { at: notes.indexOf('${SHORT_PASSAGE}') }),
      hit('${FOLDER}/deep-long.txt', '${DEEP_PASSAGE}', 2, { at: 345, chunk: chunk('${FOLDER}/deep-long.txt', ${DEEP_CHUNK_START}, ${DEEP_CHUNK_START} + 1600) }),
      hit('${FOLDER}/repeated.txt', repeated.slice(${REPEAT_CHUNK_START + REPEAT_REGION.start}, ${REPEAT_CHUNK_START + REPEAT_REGION.end}), 3,
        { at: ${REPEAT_REGION.start}, chunk: chunk('${FOLDER}/repeated.txt', ${REPEAT_CHUNK_START}, ${REPEAT_CHUNK_START} + 1200) }),
      hit('${FOLDER}/landing.txt', landingPassage, 4, { at: landing.indexOf(landingPassage) }),
      hit('${FOLDER}/tail.txt', tailPassage, 5, { at: tail.indexOf(tailPassage) }),
    ] });
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
type AppWindow = Window & { previews: Array<{ doc: string; offset: number; max: number }>; previewDelay: number; docs: Record<string, string> };

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
const previews = (page: Page): Promise<Array<{ doc: string; offset: number; max: number }>> =>
  page.evaluate(() => (window as unknown as AppWindow).previews);

/**
 * Where in the FILE the pane's tinted lines are (source view): the pane's text is the end of the
 * last served slice, so its first character's file position is known, and each tinted line's
 * `data-line` gives its own. Returns the first tinted line's file position and how many are tinted.
 */
const tintedPlace = (page: Page, doc: string): Promise<{ at: number; lines: number; text: string } | null> =>
  page.locator('jf-document-pane').first().evaluate((element, path) => {
    const app = window as unknown as AppWindow;
    const pane = element as HTMLElement & { content: string };
    const spans = [...pane.shadowRoot!.querySelectorAll('pre.source span.hl-weak')];
    if (spans.length === 0) return null;
    const last = app.previews.filter(p => p.doc === path).at(-1)!;
    const served = app.docs[path]!.slice(last.offset, last.offset + last.max);
    if (!served.endsWith(pane.content)) throw new Error('the pane does not show the end of the served slice');
    const contentAt = last.offset + served.length - pane.content.length;
    const before = pane.content.split('\n').slice(0, Number(spans[0]!.getAttribute('data-line')));
    return { at: contentAt + before.reduce((sum, line) => sum + line.length + 1, 0), lines: spans.length,
      text: spans.map(span => (span.textContent ?? '').replace(/\n$/, '')).join('\n') };
  }, doc);

describe.each(['dark', 'light'] as const)('a result opened in the side pane shows its passage (%s, 1024x700)', palette => {
  it(`a long file: the window around the passage, the passage visible without scrolling, its found word marked (${palette})`, async () => {
    const page = await open(palette);
    await search(page);
    await rows(page).nth(0).click();
    await shown(page);
    const requested = await previews(page);
    expect(requested).toHaveLength(1);
    expect(requested[0]!.offset).toBeGreaterThan(0);
    // A chunk hit: the window is around the chunk's start plus the region's, which lies past 12,000.
    expect(requested[0]!.offset).toBeGreaterThan(8000);
    expect(await passage(page)).toMatchObject({ text: LONG_PASSAGE, marks: ['harbour'], inPane: true, inWindow: true });
    expect(await note(page)).toBe('Showing the part of this document around the match.');
  });

  it(`a file that fits in one window, opened by keyboard: the passage below the fold is visible without scrolling (${palette})`, async () => {
    const page = await open(palette);
    await search(page);
    await rows(page).nth(1).focus();
    await page.keyboard.press('Enter');
    await shown(page);
    expect((await previews(page)).map(({ doc, offset }) => ({ doc, offset }))).toEqual([{ doc: `${FOLDER}/short-notes.txt`, offset: 0 }]);
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

describe('chunk hits far into a file land on their own place (R1, R3)', () => {
  it('deep-long.txt, chunk at 249,619, region at 345: the window around 249,964, the passage tinted there and visible (dark)', async () => {
    const page = await open('dark');
    await search(page);
    await rows(page).nth(2).click();
    await shown(page);
    const requested = await previews(page);
    expect(requested).toHaveLength(1);
    expect(requested[0]!.offset).toBeGreaterThan(DEEP_AT - 5000);
    expect(requested[0]!.offset).toBeLessThanOrEqual(DEEP_AT);
    expect(requested[0]!.offset + requested[0]!.max).toBeGreaterThanOrEqual(DEEP_AT + DEEP_PASSAGE.length);
    expect(await tintedPlace(page, `${FOLDER}/deep-long.txt`)).toEqual({ at: DEEP_AT, lines: 1, text: DEEP_PASSAGE });
    expect(await passage(page)).toMatchObject({ text: DEEP_PASSAGE, marks: ['harbour'], inPane: true, inWindow: true });
    expect(await note(page)).toBe('Showing the part of this document around the match.');
  });

  it('a file of identical lines: the five lines at the chunk\'s place are tinted and visible, none near the top (light)', async () => {
    const page = await open('light');
    await search(page);
    await rows(page).nth(3).click();
    await shown(page);
    const at = REPEAT_CHUNK_START + REPEAT_REGION.start;
    const requested = await previews(page);
    expect(requested).toHaveLength(1);
    expect(requested[0]!.offset).toBeGreaterThan(at - 5000);
    expect(requested[0]!.offset).toBeLessThanOrEqual(at);
    expect(await tintedPlace(page, `${FOLDER}/repeated.txt`)).toMatchObject({ at, lines: 5 });
    expect(await passage(page)).toMatchObject({ text: SAME, inPane: true, inWindow: true });
    expect(await note(page)).toBe('Showing the part of this document around the match.');
  });
});

/**
 * N5 — where the passage lands: its tinted lines' boxes against the pane's visible box (the scroll
 * area's box, cut to the window), and how far the scroll area could still move down.
 */
async function landing(page: Page): Promise<{ lines: number; top: number; bottom: number; frameTop: number; frameHeight: number; frameBottom: number; scrolled: number; maxScroll: number }> {
  return page.locator('jf-document-pane').first().evaluate(element => {
    const root = element.shadowRoot!;
    const area = root.querySelector<HTMLElement>('.scroll-region')!;
    const lines = [...root.querySelectorAll('pre.source span.hl-weak')];
    const frame = area.getBoundingClientRect();
    const frameTop = Math.max(frame.top, 0);
    const frameBottom = Math.min(frame.bottom, window.innerHeight);
    // Nothing tinted reads as no box at all (NaN), so the line count's assertion is what fails.
    return { lines: lines.length, top: lines[0]?.getBoundingClientRect().top ?? Number.NaN, bottom: lines.at(-1)?.getBoundingClientRect().bottom ?? Number.NaN,
      frameTop, frameBottom, frameHeight: frameBottom - frameTop, scrolled: area.scrollTop, maxScroll: area.scrollHeight - area.clientHeight };
  });
}

describe.each(['dark', 'light'] as const)('N5 the passage lands in the upper quarter of the pane (%s, 1024x700)', palette => {
  it(`a deep passage of six lines: its first line in the upper quarter, the whole passage inside the pane (${palette})`, async () => {
    const page = await open(palette);
    await search(page);
    await rows(page).nth(4).click();
    await shown(page);
    expect((await previews(page))[0]!.offset).toBeGreaterThan(0);
    const place = await landing(page);
    expect(place.lines).toBe(6);
    expect(place.bottom - place.top).toBeLessThan(place.frameHeight * 0.75);
    expect(place.top).toBeGreaterThanOrEqual(place.frameTop - 0.5);
    expect(place.top - place.frameTop).toBeLessThanOrEqual(place.frameHeight / 4);
    expect(place.bottom).toBeLessThanOrEqual(place.frameBottom + 0.5);
  });

  it(`a passage that ends the file, too little text below it: the whole passage inside the pane (${palette})`, async () => {
    const page = await open(palette);
    await search(page);
    await rows(page).nth(5).click();
    await shown(page);
    const place = await landing(page);
    expect(place.lines).toBe(4);
    expect(place.top).toBeGreaterThanOrEqual(place.frameTop - 0.5);
    expect(place.bottom).toBeLessThanOrEqual(place.frameBottom + 0.5);
    expect(place.scrolled).toBeGreaterThanOrEqual(place.maxScroll - 1);
  });
});
