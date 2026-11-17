// SPDX-License-Identifier: Apache-2.0
// fm-04 in the real app: the results filter row (kinds in a person's words, none for one kind, the
// chosen filter strong and the rest muted) and the search field's Clear — in dark and light. Real
// Shell and Search in private headless Chromium over a private Vite server and a stubbed backend (no
// Engine, no shared dev stack). Run with
// npx vitest run --config vitest.config.lockdown.ts src/shell-v0/chrome/FirstMinutes.filterRow-lockdown.test.ts
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Locator, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const SEARCH = 'core.search-v3-surface';

// "inspection" finds the picture's three files (mail, a Word file and a PDF); "harbor" finds only text
// files, as the packaged app's "text 36" did; "ledger" finds mail beside other files the Engine also
// calls "binary". The files carry the Engine's own kinds (mail is "binary", message/rfc822), and a
// kind or file-type filter in the request narrows them, with counts for both, as the Engine does.
const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
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
window.fetch = async (input, init) => {
  const url = decodeURIComponent(String(input?.url ?? input));
  if (url.includes('/api/knowledge/search')) {
    const body = JSON.parse(String(init?.body ?? '{}'));
    window.searches.push(body);
    const query = String(body.query ?? '');
    const all = files[Object.keys(files).find(name => query.includes(name)) ?? 'inspection'];
    const kinds = body.filters?.fileKind ?? [], types = body.filters?.mimeBase ?? [];
    const found = all.filter(f => (!kinds.length || kinds.includes(f[1])) && (!types.length || types.includes(f[2])));
    const counts = { file_kind: {}, mime_base: {} };
    for (const f of found) { counts.file_kind[f[1]] = (counts.file_kind[f[1]] ?? 0) + 1; counts.mime_base[f[2]] = (counts.mime_base[f[2]] ?? 0) + 1; }
    return json({ totalHits: found.length, matchCount: found.length, tookMs: 4, results: found.map(hit), nextCursor: null,
      facets: counts, facetsTruncated: false });
  }
  if (url.includes('/api/preview')) return json({ content: 'The silver pelican inspection.', totalChars: 30 });
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
  output = await mkdtemp(path.join(process.env.TMPDIR ?? tmpdir(), 'filter-row-'));
  server = await createServer({
    configFile: false, root: process.cwd(), cacheDir: output, publicDir: 'public', logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{ name: 'filter-row-harness', configureServer(vite) {
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
async function open(palette: Palette): Promise<Page> {
  context = await browser.newContext({ viewport: { width: 1024, height: 700 }, reducedMotion: 'reduce', colorScheme: palette });
  const page = await context.newPage();
  page.on('pageerror', error => console.info('pageerror', error.message));
  await page.goto(url, { timeout: 90_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 90_000 });
  await page.locator('jf-top-bar').waitFor();
  await settle(page, 400);
  expect(await page.evaluate(() => document.documentElement.dataset.theme)).toBe(palette);
  return page;
}

const field = (page: Page) => page.locator('[data-testid="sv3-composer-input"]');
const searches = (page: Page) => page.evaluate(() => (window as unknown as { searches: unknown[] }).searches.length);

async function search(page: Page, query: string): Promise<void> {
  const before = await searches(page);
  await field(page).fill(query);
  await field(page).press('Enter');
  await expect.poll(() => searches(page), { timeout: 15_000 }).toBeGreaterThan(before);
  await page.locator('[data-testid="search-result-row"]').first().waitFor({ timeout: 15_000 });
  await settle(page, 300);
}

const filterRow = (page: Page) => page.locator('jf-results-card .result-filters button');
const words = async (page: Page) => (await filterRow(page).allTextContents()).map(text => text.replace(/\s+/g, ' ').trim());

/** A colour token as it computes inside the target's own tree (so a theme that redefines it is honoured). */
const token = (target: Locator, name: string): Promise<string> => target.evaluate((element, variable) => {
  const probe = document.createElement('span');
  probe.style.setProperty('color', `var(${variable})`);
  element.append(probe);
  const computed = getComputedStyle(probe).color;
  probe.remove();
  return computed;
}, name);

/** The target's ink against everything painted under it, composed on a canvas: the contrast ratio. */
const contrast = (target: Locator) => target.evaluate(element => {
  const canvas = document.createElement('canvas');
  canvas.width = 1; canvas.height = 1;
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
  const ground = rgb();
  paint.fillStyle = getComputedStyle(element).color;
  paint.fillRect(0, 0, 1, 1);
  const ink = rgb();
  const luminance = (colour: number[]): number => {
    const [r, g, b] = colour.map(value => { const c = value / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; });
    return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!;
  };
  const [hi, lo] = [luminance(ground), luminance(ink)].sort((a, b) => b - a);
  return (hi! + 0.05) / (lo! + 0.05);
});

const look = (target: Locator) => target.evaluate(element => {
  const s = getComputedStyle(element);
  return { size: s.fontSize, weight: s.fontWeight, color: s.color };
});

describe('PF1 kind filters use the words a person would, with the count', () => {
  it.each(['dark', 'light'] as const)('the picture\'s row: All, Documents 2, Mail 1, Any time, More filters (%s)', async palette => {
    const page = await open(palette);
    await search(page, 'inspection');
    expect(await words(page)).toEqual(['All', 'Documents 2', 'Mail 1', 'Any time', 'More filters']);
    expect((await words(page)).join(' '), 'no raw type name').not.toMatch(/\b(email|office|pdf|text|binary)\b/);
    expect(await page.locator('jf-results-card [data-testid="more-filters"] svg').count(), 'More filters has its funnel').toBe(1);
  }, 120_000);

  it('choosing Documents narrows to both of its kinds in one search', async () => {
    const page = await open('dark');
    await search(page, 'inspection');
    const before = await searches(page);
    await filterRow(page).nth(1).click();
    await expect.poll(() => searches(page), { timeout: 15_000 }).toBe(before + 1);
    const sent = await page.evaluate(() => (window as unknown as { searches: Array<{ filters?: { fileKind?: string[] } }> }).searches.at(-1));
    expect(sent?.filters?.fileKind?.slice().sort()).toEqual(['office', 'pdf']);
    await expect.poll(() => page.locator('[data-testid="search-result-row"]').count()).toBe(2);
  }, 120_000);
});

describe('PF1\' Mail is what the rows show as mail, though the Engine calls it "binary"', () => {
  const sent = (page: Page) => page.evaluate(() =>
    (window as unknown as { searches: Array<{ filters?: { fileKind?: string[]; mimeBase?: string[] } }> }).searches.at(-1)?.filters ?? {});
  const rowNames = async (page: Page) => (await page.locator('[data-testid="search-result-row"]').allTextContents())
    .map(text => text.match(/[\w-]+\.(eml|pdf|bin|sqlite|docx)/)?.[0]);

  it('a .eml file (message/rfc822, kind "binary") is Mail; other binary files stay Other files', async () => {
    const page = await open('dark');
    await search(page, 'ledger');
    expect(await words(page)).toEqual(['All', 'Other files 2', 'Documents 1', 'Mail 1', 'Any time', 'More filters']);
  }, 120_000);

  it('choosing Mail selects exactly the mail rows; choosing Other files leaves them out of its count; All lets go', async () => {
    const page = await open('dark');
    await search(page, 'ledger');
    const before = await searches(page);
    await filterRow(page).filter({ hasText: 'Mail' }).click();
    await expect.poll(() => searches(page), { timeout: 15_000 }).toBe(before + 1);
    expect(await sent(page), 'by the mail file type, not the catch-all kind').toEqual({ mimeBase: ['message/rfc822'] });
    await expect.poll(() => rowNames(page), { timeout: 15_000 }).toEqual(['reply.eml']);
    await settle(page, 300);
    expect(await words(page), 'a chosen Mail stays on the row').toEqual(['All', 'Mail 1', 'Any time', 'More filters']);
    expect(await filterRow(page).filter({ hasText: 'Mail' }).getAttribute('aria-pressed')).toBe('true');
    expect(await page.locator('jf-results-card [data-testid="more-filters"]').getAttribute('aria-label'), 'not a More filters choice').toBeNull();
    await filterRow(page).filter({ hasText: 'All' }).click();
    await expect.poll(() => page.locator('[data-testid="search-result-row"]').count(), { timeout: 15_000 }).toBe(4);
    expect(await sent(page)).toEqual({});
  }, 120_000);
});

describe('PF2 one kind among the results shows no kind filters', () => {
  it.each(['dark', 'light'] as const)('only Any time and More filters (%s)', async palette => {
    const page = await open(palette);
    await search(page, 'harbor');
    expect(await words(page)).toEqual(['Any time', 'More filters']);
    expect(await page.locator('jf-results-card [data-testid="type-all"]').count()).toBe(0);
  }, 120_000);
});

describe('PF3 the chosen filter is the strong one; the rest are grey; all 12 px', () => {
  it.each(['dark', 'light'] as const)('as drawn, and after a choice (%s)', async palette => {
    const page = await open(palette);
    await search(page, 'inspection');
    const row = filterRow(page);
    const card = page.locator('jf-results-card .result-filters');
    const [normal, muted] = [await token(card, '--text-primary'), await token(card, '--text-secondary')];
    expect(normal).not.toBe(muted);
    const strong = { size: '12px', weight: '500', color: normal };
    const grey = { size: '12px', weight: '400', color: muted };
    const looks = async () => Promise.all((await row.all()).map(look));
    expect(await looks(), 'All chosen; Documents, Mail, Any time, More filters grey').toEqual([strong, grey, grey, grey, grey]);
    for (const button of await row.all()) expect(await contrast(button), await button.textContent() ?? '').toBeGreaterThanOrEqual(4.5);
    expect(await row.nth(0).getAttribute('aria-pressed')).toBe('true');

    await row.nth(2).click();
    await expect.poll(() => page.locator('[data-testid="search-result-row"]').count(), { timeout: 15_000 }).toBe(1);
    await settle(page, 300);
    expect(await words(page), 'a chosen kind stays on the row').toEqual(['All', 'Mail 1', 'Any time', 'More filters']);
    expect(await looks(), 'Mail chosen; All grey').toEqual([grey, strong, grey, grey]);
    expect(await row.nth(1).getAttribute('aria-pressed')).toBe('true');
    expect(await row.nth(0).getAttribute('aria-pressed')).toBe('false');

    await page.locator('jf-results-card [data-testid="more-filters"]').click();
    await settle(page);
    expect((await looks())[3], 'an open More filters holds no choice').toEqual(grey);
  }, 120_000);
});

describe('PF3 in forced colours the chosen filter keeps a visible state', () => {
  it('the pressed filter takes the system selection pair; the rest do not', async () => {
    context = await browser.newContext({ viewport: { width: 1024, height: 700 }, reducedMotion: 'reduce', colorScheme: 'dark', forcedColors: 'active' });
    const page = await context.newPage();
    await page.goto(url, { timeout: 90_000 });
    await page.locator('body[data-ready="true"]').waitFor({ timeout: 90_000 });
    await page.locator('jf-top-bar').waitFor();
    await settle(page, 400);
    await search(page, 'inspection');
    const pair = await page.evaluate(() => {
      const probe = document.createElement('span');
      probe.style.cssText = 'background-color: Highlight; color: HighlightText';
      document.body.append(probe);
      const s = getComputedStyle(probe);
      const out = { background: s.backgroundColor, color: s.color };
      probe.remove();
      return out;
    });
    const fills = await Promise.all((await filterRow(page).all()).map(button => button.evaluate(el => {
      const s = getComputedStyle(el);
      return { background: s.backgroundColor, color: s.color };
    })));
    expect(fills[0], 'All, chosen').toEqual(pair);
    for (const other of fills.slice(1)) expect(other.background).not.toBe(pair.background);
  }, 120_000);
});

const clear = (page: Page) => page.locator('[data-testid="sv3-search-clear"]');
const focusedIsField = (page: Page) => field(page).evaluate(el => el === (el.getRootNode() as ShadowRoot).activeElement);

/** What Search shows: the field, the composer's state, the results and the empty form's parts. */
const shown = (page: Page) => page.evaluate(() => {
  const win = document.querySelector('jf-shell')!.shadowRoot!.querySelector('jf-stage')!.shadowRoot!.querySelector('jf-sv3-window')!;
  const composer = win.shadowRoot!.querySelector('jf-sv3-composer')!;
  const deep = (root: Document | ShadowRoot, selector: string): Element[] => {
    const out = [...root.querySelectorAll(selector)];
    for (const el of root.querySelectorAll('*')) if (el.shadowRoot) out.push(...deep(el.shadowRoot, selector));
    return out;
  };
  return { value: composer.shadowRoot!.querySelector('textarea')!.value, state: composer.getAttribute('state'),
    results: composer.hasAttribute('results'), rows: deep(document, '[data-testid="search-result-row"]').length,
    cards: deep(document, 'jf-results-card').length, headline: deep(document, '#sv3-search-headline').length,
    window: [...win.attributes].map(a => `${a.name}=${a.value}`).sort().join(' ') };
});

describe('PF4 the search field has a Clear × while it holds text', () => {
  it.each(['dark', 'light'] as const)('shown only with text, at the field\'s end, 14 px and muted (%s)', async palette => {
    const page = await open(palette);
    expect(await clear(page).count(), 'an empty field has no Clear').toBe(0);
    await search(page, 'inspection');
    expect(await clear(page).count()).toBe(1);
    expect(await clear(page).getAttribute('aria-label')).toBe('Clear search');
    expect(await clear(page).evaluate(el => el.tagName)).toBe('BUTTON');
    const icon = await clear(page).locator('svg').boundingBox();
    expect([icon?.width, icon?.height]).toEqual([14, 14]);
    const box = await clear(page).boundingBox();
    expect(Math.min(box!.width, box!.height), 'a 24 px target').toBeGreaterThanOrEqual(24);
    const text = await field(page).boundingBox();
    const shell = await page.locator('[data-testid="sv3-composer-shell"]').boundingBox();
    expect(box!.x, 'after the text').toBeGreaterThanOrEqual(text!.x + text!.width - 0.5);
    expect(shell!.x + shell!.width - (box!.x + box!.width), 'at the field\'s end').toBeLessThanOrEqual(16);
    expect(Math.abs(box!.y + box!.height / 2 - (shell!.y + shell!.height / 2)), 'centred in the field').toBeLessThanOrEqual(1);
    const composer = page.locator('jf-sv3-composer [data-testid="sv3-composer-shell"]');
    expect((await look(clear(page))).color).toBe(await token(composer, '--muted-foreground'));
    expect(await contrast(clear(page)), 'the cross reads as a control').toBeGreaterThanOrEqual(3);
    await field(page).fill('');
    await settle(page);
    expect(await clear(page).count(), 'gone once the field is empty').toBe(0);
  }, 120_000);

  it('a click empties the field, keeps the caret there and leaves Search as clearing by keyboard does', async () => {
    const page = await open('dark');
    await search(page, 'inspection');
    await field(page).focus();
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.press('Backspace');
    await settle(page, 400);
    const byKeyboard = await shown(page);
    expect(byKeyboard.value).toBe('');
    await context?.close();

    const other = await open('dark');
    await search(other, 'inspection');
    await clear(other).click();
    await settle(other, 400);
    expect(await shown(other)).toEqual(byKeyboard);
    expect(await focusedIsField(other), 'the caret stays in the field').toBe(true);
    expect(await clear(other).count()).toBe(0);
  }, 180_000);

  it('by pointer five times in a row across new searches: each first click clears, and the × never moves', async () => {
    const page = await open('dark');
    const rounds: unknown[] = [];
    const expected: unknown[] = [];
    for (const query of ['inspection', 'ledger', 'harbor', 'inspection ledger', 'harbor inspection']) {
      await search(page, query);
      const box = (await clear(page).boundingBox())!;
      await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
      await page.mouse.down();
      await settle(page, 50);
      const pressed = await clear(page).boundingBox();
      const caretWhilePressed = await focusedIsField(page);
      await page.mouse.up();
      await settle(page);
      rounds.push({ query, box, pressed, caretWhilePressed, value: await field(page).inputValue(),
        caret: await focusedIsField(page), clear: await clear(page).count() });
      expected.push({ query, box: (rounds[0] as { box: unknown }).box, pressed: (rounds[0] as { box: unknown }).box,
        caretWhilePressed: true, value: '', caret: true, clear: 0 });
    }
    expect(rounds, 'each round: the × where it was in round one, unmoved while pressed, cleared by the first click, caret kept').toEqual(expected);
    const shell = (await page.locator('[data-testid="sv3-composer-shell"]').boundingBox())!;
    const first = (rounds[0] as { box: { x: number; width: number } }).box;
    expect(shell.x + shell.width - (first.x + first.width), 'at the field\'s end').toBeLessThanOrEqual(16);
  }, 180_000);

  it('the field is the same after Clear as after Ctrl+A, Backspace: no empty block left in it', async () => {
    const page = await open('dark');
    const fieldState = () => page.evaluate(() => {
      const win = document.querySelector('jf-shell')!.shadowRoot!.querySelector('jf-stage')!.shadowRoot!.querySelector('jf-sv3-window')!;
      const root = win.shadowRoot!.querySelector('jf-sv3-composer')!.shadowRoot!;
      const glass = root.querySelector('[data-testid="sv3-composer-shell"]')!;
      const box = (el: Element) => { const r = el.getBoundingClientRect(); return [r.x, r.y, r.width, r.height]; };
      const shape = (el: Element): unknown => ({ tag: el.tagName.toLowerCase(), cls: el.className, kids: [...el.children].map(shape) });
      return { glass: box(glass), textarea: box(root.querySelector('textarea')!), parts: shape(glass),
        blocks: [...glass.querySelectorAll('*')].filter(el => getComputedStyle(el).display !== 'none' && el.getBoundingClientRect().width > 0)
          .map(el => [el.tagName.toLowerCase(), el.className, ...box(el)]) };
    });
    await search(page, 'inspection');
    await field(page).focus();
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.press('Backspace');
    await settle(page, 400);
    const byKeyboard = await fieldState();
    await search(page, 'inspection');
    await clear(page).click();
    await settle(page, 400);
    expect(await fieldState()).toEqual(byKeyboard);
    expect(JSON.stringify(byKeyboard.parts), 'no empty control row in the field').not.toContain('footer');
  }, 180_000);

  it('reachable by keyboard: Tab from the field, then Enter', async () => {
    const page = await open('light');
    await search(page, 'inspection');
    await field(page).focus();
    await page.keyboard.press('Tab');
    expect(await clear(page).evaluate(el => el === (el.getRootNode() as ShadowRoot).activeElement), 'Tab lands on Clear').toBe(true);
    await page.keyboard.press('Enter');
    await settle(page);
    expect(await field(page).inputValue()).toBe('');
    expect(await focusedIsField(page)).toBe(true);
  }, 120_000);

  it('Escape in the field behaves as before: it does not empty the field', async () => {
    const page = await open('dark');
    await search(page, 'inspection');
    await field(page).focus();
    await page.keyboard.press('Escape');
    await settle(page, 400);
    expect(await field(page).inputValue()).toBe('inspection');
  }, 120_000);
});
