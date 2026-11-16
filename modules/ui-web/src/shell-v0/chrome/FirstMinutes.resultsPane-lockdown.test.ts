// SPDX-License-Identifier: Apache-2.0
// fm-04 / fm-05 in the real app: found words, the row the pane shows, and the pane's title, path and
// text — in dark, light and forced colours. Real Shell and Search in private headless Chromium over a
// private Vite server and a stubbed backend (no Engine, no shared dev stack). Run with
// npx vitest run --config vitest.config.lockdown.ts src/shell-v0/chrome/FirstMinutes.resultsPane-lockdown.test.ts
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Locator, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const SEARCH = 'core.search-v3-surface';
/** The packaged app's own failure: a long temporary folder whose last name broke mid-word. */
const FOLDER = '/home/reader/.cache/am/vael2d1y/tmp/run/home/Documents';

const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
const json = (body) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
const names = ['note-10-harbor.txt', 'note-22-harbor.txt', 'note-34-harbor.txt', 'plan-harbor.md'];
const hit = (name, i) => ({ id: 'doc-' + i, score: 1 - i / 10, fields: { title: name, filename: name, path: '${FOLDER}/' + name,
  content_preview: 'Note ' + i + ' about the harbor and the garden. The harbor lantern project.', collection: 'docs',
  mime: name.endsWith('.md') ? 'text/markdown' : 'text/plain', mime_base: 'text', file_kind: name.endsWith('.md') ? 'markdown' : 'text', language: 'en',
  modified_at: '2025-06-15T10:30:00Z', size_bytes: '157' }, matchedFields: ['content'], matchSpans: [], excerptRegions: [] });
window.fetch = async (input) => {
  const url = decodeURIComponent(String(input?.url ?? input));
  if (url.includes('/api/preview')) return json(url.includes('.md')
    ? { content: '# Harbor plan\\n\\nThe harbor lantern is lit.\\n\\nNothing else here.', totalChars: 60 }
    : { content: 'Note about the harbor and the garden.\\nThe invoice plan mentions harbor again.\\nShared words: meadow river.', totalChars: 110 });
  if (url.includes('/api/knowledge/search')) return json({ totalHits: 4, matchCount: 4, tookMs: 4, results: names.map(hit), nextCursor: null, facets: {}, facetsTruncated: false });
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
// The app's own "System" appearance: the theme follows the system preference, as on first launch.
const { applyAppearance } = await import('/src/shell-v0/state/themeState.ts');
await applyAppearance({ theme: 'system' }, async () => json({}));
document.body.append(document.createElement('jf-shell'));
const stage = () => document.querySelector('jf-shell').shadowRoot.querySelector('jf-stage');
window.converse = async () => {
  const win = stage().shadowRoot.querySelector('jf-sv3-window');
  const item = { parentDocId: '${FOLDER}/note-10-harbor.txt', chunkIndex: 0, excerpt: 'harbor', startLine: 0, endLine: 1, headingText: '', startChar: 0, endChar: 20 };
  const marks = [{ sentenceText: 'A sentence about the harbor.', similarity: 0.9, sentenceIndex: 0, label: 1,
    detail: { parentDocId: item.parentDocId, startLine: 0, endLine: 1, excerpt: item.excerpt, startChar: 0, endChar: 20 }, hover: { excerpt: item.excerpt, title: 'source', headingText: '' } }];
  const turn = { id: 't1', recordId: null, assistantRecordId: null, recordOpenedByUser: false, kind: 'ask', question: 'harbor', answer: 'A sentence about the harbor.', status: 'complete',
    evidence: { sources: [item], matches: [{ sentenceIndex: 0, sentenceText: 'A sentence about the harbor.', similarity: 0.9, sourceIndex: 0 }], marks, retrievalMode: 'HYBRID' },
    detail: '', toolCalls: 0, activity: [], reasoning: [], askedAt: 1 };
  win.tier = 'ask';
  win.sessions = { sessions: [{ id: 'c1', title: 'c1', turns: [turn], pinned: false, createdAt: 1, completedAt: 1, lastVisitedAt: 1, history: null, usage: null }], activeId: 'c1' };
  await win.updateComplete;
};
document.body.dataset.ready = 'true';
</script></body></html>`;

type Palette = 'dark' | 'light' | 'forced';

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let context: BrowserContext | undefined;
let output: string;
let url: string;

beforeAll(async () => {
  output = await mkdtemp(path.join(process.env.TMPDIR ?? tmpdir(), 'results-pane-'));
  server = await createServer({
    configFile: false, root: process.cwd(), cacheDir: output, publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{ name: 'results-pane-harness', configureServer(vite) {
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
  context = await browser.newContext({ viewport: { width, height }, reducedMotion: 'reduce',
    colorScheme: palette === 'light' ? 'light' : 'dark', forcedColors: palette === 'forced' ? 'active' : 'none' });
  const page = await context.newPage();
  page.on('pageerror', error => console.info('pageerror', error.message));
  await page.goto(url, { timeout: 90_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 90_000 });
  await page.locator('jf-top-bar').waitFor();
  await settle(page, 400);
  if (palette !== 'forced') expect(await page.evaluate(() => document.documentElement.dataset.theme)).toBe(palette);
  return page;
}

async function search(page: Page): Promise<void> {
  await page.locator('jf-sv3-composer textarea').fill('harbor lantern');
  await page.keyboard.press('Enter');
  await page.locator('[data-testid="search-result-row"]').first().waitFor({ timeout: 15_000 });
  await settle(page);
}

const rows = (page: Page) => page.locator('[data-testid="search-result-row"]');
const paneText = (page: Page) => page.locator('jf-document-pane [role="region"][aria-label="Document content"]');

async function openRow(page: Page, index: number): Promise<void> {
  await rows(page).nth(index).click();
  await paneText(page).first().waitFor();
  await page.mouse.move(1, 690);
  await settle(page, 300);
}

/** What a colour written in CSS computes to here: the picture's tokens, and the system pairs. */
const resolve = (page: Page, property: 'color' | 'background-color' | 'outline-color', value: string): Promise<string> =>
  page.evaluate(([name, written]) => {
    const probe = document.createElement('span');
    probe.style.setProperty(name!, written!);
    document.body.append(probe);
    const computed = getComputedStyle(probe).getPropertyValue(name!);
    probe.remove();
    return computed;
  }, [property, value]);

/** The mark's ink against its tint as painted: every fill from the page down, composed on a canvas. */
const painted = (mark: Locator) => mark.evaluate(element => {
  const canvas = document.createElement('canvas');
  canvas.width = 1;
  canvas.height = 1;
  const paint = canvas.getContext('2d', { willReadFrequently: true })!;
  const fills: string[] = [];
  for (let at: Element | null = element; at !== null; at = at.parentElement ?? ((at.getRootNode() as ShadowRoot).host ?? null)) {
    const fill = getComputedStyle(at).backgroundColor;
    if (fill !== 'rgba(0, 0, 0, 0)') fills.unshift(fill);
  }
  paint.fillStyle = getComputedStyle(document.documentElement).colorScheme.includes('light') ? '#fff' : '#000';
  paint.fillRect(0, 0, 1, 1);
  for (const fill of fills) { paint.fillStyle = fill; paint.fillRect(0, 0, 1, 1); }
  const rgb = (): number[] => [...paint.getImageData(0, 0, 1, 1).data.slice(0, 3)];
  const fill = rgb();
  paint.fillStyle = getComputedStyle(element).color;
  paint.fillRect(0, 0, 1, 1);
  const ink = rgb();
  const luminance = (colour: number[]): number => {
    const [r, g, b] = colour.map(value => { const c = value / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; });
    return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!;
  };
  const [hi, lo] = [luminance(fill), luminance(ink)].sort((a, b) => b - a);
  return { ink, fill, ratio: (hi! + 0.05) / (lo! + 0.05) };
});

const style = (target: Locator) => target.evaluate(element => {
  const s = getComputedStyle(element);
  return { background: s.backgroundColor, color: s.color, radius: s.borderTopLeftRadius, padding: s.padding, shadow: s.boxShadow,
    outlineStyle: s.outlineStyle, outlineColor: s.outlineColor, outlineWidth: s.outlineWidth, bottomColor: s.borderBottomColor,
    bottomWidth: s.borderBottomWidth, decoration: s.textDecorationLine };
});

/** The picture's tokens (tmp/design/fm-05: --mark, --mark-ring), per theme. */
const PICTURE = {
  dark: { mark: 'rgba(240, 184, 96, 0.22)', ring: 'rgba(240, 184, 96, 0.08)' },
  light: { mark: 'oklch(86% 0.14 85 / 0.55)', ring: 'oklch(86% 0.14 85 / 0.2)' },
} as const;

describe('PA1 found words are a faint warm tint behind text in its normal colour', () => {
  it.each(['dark', 'light'] as const)('in the result snippets, %s', async palette => {
    const page = await open(palette);
    await search(page);
    const mark = rows(page).first().locator('.snippet mark.hl').first();
    expect(await mark.textContent()).toBe('harbor');
    const title = rows(page).first().locator('.title-text');
    const look = await style(mark);
    expect(look.background).toBe(await resolve(page, 'background-color', PICTURE[palette].mark));
    expect(look.color, 'the word keeps the text colour').toBe((await style(title)).color);
    expect(look.radius).toBe('4px');
    expect(look.padding).toBe('0px 2px');
    expect((await painted(mark)).ratio).toBeGreaterThanOrEqual(4.5);
  }, 120_000);

  it.each(['dark', 'light'] as const)('in the pane’s document text, with the picture’s ring, %s', async palette => {
    const page = await open(palette);
    await search(page);
    await openRow(page, 0);
    const marks = page.locator('jf-document-pane pre.source mark.hl');
    expect(await marks.allTextContents()).toEqual(['harbor', 'harbor']);
    const look = await style(marks.first());
    expect(look.background).toBe(await resolve(page, 'background-color', PICTURE[palette].mark));
    expect(look.color).toBe((await style(page.locator('jf-document-pane pre.source'))).color);
    expect(look.radius).toBe('4px');
    expect(look.padding).toBe('1px 2px');
    expect(look.shadow).toBe(`${await resolve(page, 'color', PICTURE[palette].ring)} 0px 0px 0px 3px`);
    expect((await painted(marks.first())).ratio).toBeGreaterThanOrEqual(4.5);
  }, 120_000);

  it('in forced colours both take the system Mark pair', async () => {
    const page = await open('forced');
    await search(page);
    await openRow(page, 0);
    expect(await page.locator('jf-document-pane mark.hl').count()).toBeGreaterThan(0);
    const [mark, text] = [await resolve(page, 'background-color', 'Mark'), await resolve(page, 'color', 'MarkText')];
    for (const target of [rows(page).first().locator('.snippet mark.hl').first(), page.locator('jf-document-pane mark.hl').first()]) {
      const look = await style(target);
      expect(look.background).toBe(mark);
      expect(look.color).toBe(text);
    }
  }, 120_000);
});

describe('PA2 the pane marks the found words only for the search result it shows', () => {
  it('a Markdown result is marked in its rendered text as well', async () => {
    const page = await open('dark');
    await search(page);
    await openRow(page, 3);
    expect(await page.locator('jf-document-pane .blocks mark.hl').allTextContents()).toEqual(['Harbor', 'harbor', 'lantern']);
  }, 120_000);

  it('a cited document keeps what it shows today: no marks, and its own close label', async () => {
    const page = await open('dark');
    await page.evaluate(() => (window as unknown as { converse(): Promise<void> }).converse());
    await settle(page, 300);
    await page.locator('.cite-ref').first().focus();
    await page.keyboard.press('Enter');
    await paneText(page).first().waitFor();
    await settle(page, 300);
    expect(await page.locator('jf-document-pane mark').count()).toBe(0);
    expect(await page.locator('[data-testid="details-pane-close"]').getAttribute('label')).toBe('Close the cited document');
  }, 120_000);
});

describe('PA3 the row whose file is in the pane looks selected', () => {
  it.each(['dark', 'light'] as const)('pointer and keyboard give the same look, %s', async palette => {
    const page = await open(palette);
    await search(page);
    await openRow(page, 1);
    const textLeft = await rows(page).nth(0).locator('.title-text').evaluate(el => el.getBoundingClientRect().left);
    const byPointer = await style(rows(page).nth(1));
    expect(await rows(page).nth(1).getAttribute('aria-current')).toBe('true');
    expect(await rows(page).nth(0).getAttribute('aria-current')).toBe('false');
    expect(byPointer.radius).toBe('8px');
    expect(byPointer.background).not.toBe('rgba(0, 0, 0, 0)');
    expect(byPointer.bottomWidth === '0px' || byPointer.bottomColor === 'rgba(0, 0, 0, 0)', 'no line under the selected row').toBe(true);
    expect((await style(rows(page).nth(0))).background).toBe('rgba(0, 0, 0, 0)');
    expect(await rows(page).nth(1).locator('.title-text').evaluate(el => el.getBoundingClientRect().left), 'the text stays where it was').toBe(textLeft);

    await rows(page).nth(2).focus();
    await page.keyboard.press('Enter');
    await settle(page, 300);
    expect(await rows(page).nth(2).getAttribute('aria-current')).toBe('true');
    expect(await rows(page).nth(1).getAttribute('aria-current')).toBe('false');
    const byKeyboard = await rows(page).nth(2).evaluate(element => {
      const s = getComputedStyle(element);
      return { background: s.backgroundColor, radius: s.borderTopLeftRadius };
    });
    expect(byKeyboard).toEqual({ background: byPointer.background, radius: byPointer.radius });
    expect((await style(rows(page).nth(1))).background).toBe('rgba(0, 0, 0, 0)');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }, 120_000);

  it('in forced colours the selected row has a system-colour outline', async () => {
    const page = await open('forced');
    await search(page);
    await openRow(page, 1);
    const look = await style(rows(page).nth(1));
    expect(look.outlineStyle).toBe('solid');
    expect(look.outlineColor).toBe(await resolve(page, 'outline-color', 'Highlight'));
    expect((await style(rows(page).nth(0))).outlineStyle).toBe('none');
  }, 120_000);
});

describe('PA4 the pane’s title, path and text', () => {
  it.each(['dark', 'light'] as const)('the text is the normal text colour, %s', async palette => {
    const page = await open(palette);
    await search(page);
    await openRow(page, 0);
    const ink = (await style(page.locator('jf-document-pane pre.source'))).color;
    const title = page.locator('[data-testid="details-pane"] [role="tab"][aria-selected="true"]');
    expect(ink).toBe((await style(title)).color);
  }, 120_000);

  it.each(['dark', 'light'] as const)('the name is a plain title with a close button named for the file, %s', async palette => {
    const page = await open(palette);
    await search(page);
    await openRow(page, 0);
    const title = page.locator('[data-testid="details-pane"] [role="tab"][aria-selected="true"]');
    expect((await title.textContent())?.trim()).toBe('note-10-harbor.txt');
    const look = await style(title);
    expect(look.bottomWidth === '0px' || look.bottomColor === 'rgba(0, 0, 0, 0)', 'no underline').toBe(true);
    expect(look.background).toBe('rgba(0, 0, 0, 0)');
    expect(await page.locator('[data-testid="details-pane-close"]').getAttribute('label')).toBe('Close note-10-harbor.txt');
    await page.locator('[data-testid="details-pane-close"] button').click();
    await settle(page, 300);
    expect(await page.locator('[data-testid="details-pane"]').count()).toBe(0);
  }, 120_000);

  it('in forced colours the title has no underline either', async () => {
    const page = await open('forced');
    await search(page);
    await openRow(page, 0);
    const look = await style(page.locator('[data-testid="details-pane"] [role="tab"][aria-selected="true"]'));
    const canvas = await resolve(page, 'color', 'Canvas');
    expect(look.bottomWidth === '0px' || [canvas, 'rgba(0, 0, 0, 0)'].includes(look.bottomColor), `the edge is the page's colour: ${JSON.stringify(look)}`).toBe(true);
  }, 120_000);

  it.each(['dark', 'light'] as const)('the path is one line, shortened in the middle, with the whole path kept, %s', async palette => {
    const page = await open(palette);
    await search(page);
    await openRow(page, 0);
    const line = page.locator('jf-document-pane .header .path');
    const geometry = await line.evaluate(element => {
      const tops: number[] = [];
      const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
      for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
        const range = document.createRange();
        range.selectNodeContents(node);
        for (const rect of range.getClientRects()) if (rect.width > 0) tops.push(Math.round(rect.top));
      }
      const lines = new Set(tops);
      const parts = [...element.children].map(child => ({ text: child.textContent, cut: child.scrollWidth > child.clientWidth,
        right: child.getBoundingClientRect().right }));
      return { lines: lines.size, parts, right: element.getBoundingClientRect().right, text: element.textContent };
    });
    expect(geometry.lines, JSON.stringify(geometry)).toBe(1);
    expect(geometry.text?.replace(/\s+/g, ' ').trim()).toBe(`${FOLDER} · 157 B`);
    expect(await line.getAttribute('title')).toBe(`${FOLDER}/note-10-harbor.txt`);
    const [head, tail, rest] = geometry.parts;
    expect(head).toMatchObject({ cut: true });
    expect(tail).toMatchObject({ text: '/Documents', cut: false });
    expect(rest?.text?.trim()).toBe('· 157 B');
    expect(rest!.right).toBeLessThanOrEqual(geometry.right + 0.5);
  }, 120_000);

  it('a short path is shown whole, with no ellipsis', async () => {
    const page = await open('dark', 1600, 900);
    await search(page);
    await openRow(page, 0);
    const parts = await page.locator('jf-document-pane .header .path').evaluate(element =>
      [...element.children].map(child => child.scrollWidth > child.clientWidth));
    expect(parts).toEqual([false, false, false]);
  }, 120_000);
});

describe('PA5 nothing else in the list or pane changes', () => {
  it('the pane keeps its two actions and the list its snippet size', async () => {
    const page = await open('dark');
    await search(page);
    const before = await rows(page).first().locator('.snippet').evaluate(el => getComputedStyle(el).fontSize);
    await openRow(page, 0);
    expect(await page.locator('[data-testid="details-open-file"]').count()).toBe(1);
    expect(await page.locator('[data-testid="details-pane"] [role="tab"]').count()).toBe(1);
    expect(await rows(page).first().locator('.snippet').evaluate(el => getComputedStyle(el).fontSize)).toBe(before);
  }, 120_000);
});
