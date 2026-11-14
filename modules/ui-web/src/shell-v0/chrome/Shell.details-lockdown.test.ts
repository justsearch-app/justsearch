// SPDX-License-Identifier: Apache-2.0
// The details pane in the real app: the real Shell, Stage, Search, Library, Browse, AI Brain and
// their panes in headless Chromium, over a private Vite server and a stubbed backend (no Engine, no
// shared dev stack). Run with
// npx vitest run --config vitest.config.lockdown.ts src/shell-v0/chrome/Shell.details-lockdown.test.ts
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const SEARCH = 'core.search-v3-surface';
const LIBRARY = 'core.library-surface';
const BROWSE = 'core.browse-surface';
const BRAIN = 'core.brain-surface';
const MEMORY = 'core.memory-surface';

const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
const params = new URLSearchParams(location.search);
if (params.has('wideFont')) {
  const style = document.createElement('style');
  style.textContent = '* { font-family: "DejaVu Sans", sans-serif !important; letter-spacing: 0.05em; }';
  document.head.append(style);
}
const json = (body) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
const hit = (i) => ({ id: 'doc-' + i, score: 1 - i / 10, fields: { title: 'Result ' + i, filename: 'result-' + i + '.md', path: 'C:/Docs/result-' + i + '.md', content_preview: 'A passage about heating ' + i, collection: 'docs', mime: 'text/markdown', mime_base: 'text', file_kind: 'markdown', language: 'en', modified_at: '2025-06-15T10:30:00Z', size_bytes: '100' }, matchedFields: ['title'], matchSpans: [], excerptRegions: [] });
const hiddenBy = {};
window.fetch = async (input, init) => {
  const url = String(input?.url ?? input);
  const excluding = url.match(/conversations\\/([^/]+)\\/sources\\/exclude/);
  if (excluding) {
    const { sourceId } = JSON.parse(init.body);
    await new Promise(resolve => { window.releaseExclude = resolve; });
    (hiddenBy[excluding[1]] ??= []).push(sourceId);
    return json({});
  }
  const history = url.match(/conversations\\/([^/]+)\\/history/);
  if (history) return json({ messages: [], excludedSourceIds: hiddenBy[history[1]] ?? [] });
  if (url.includes('/api/preview')) return json({ content: 'Some document text. '.repeat(400), totalChars: 8000 });
  if (url.includes('/api/knowledge/search')) return json({ totalHits: 4, matchCount: 4, tookMs: 4, results: [0, 1, 2, 3].map(hit), nextCursor: null, facets: {}, facetsTruncated: false });
  if (url.includes('/api/indexing-roots/substrate')) return json({ items: [root('a'), root('b')], count: 2 });
  if (url.includes('/api/indexing-jobs/failed/by-prefix')) return json({ count: 14, jobs: Array.from({ length: 14 }, (_, i) => ({ pathHash: 'file-' + i, state: 'FAILED', attempts: 3, lastUpdatedMs: 1700000000000, errorMessage: 'Could not read this file', retryAfterMs: 0, collection: 'default', scanId: '' })) });
  return json({ entries: [], conversations: [] });
};
const root = (pathHash) => ({ pathHash, collection: 'default', fileCount: 200, lastIndexedIsoTime: '2026-09-02T00:00:00Z', status: 'indexed', walkError: '', inFlightCount: 0, failedCount: 14, walkCompleted: true });
window.EventSource = class { constructor() { this.readyState = 0; } addEventListener() {} removeEventListener() {} close() {} };
const { __seedForTest } = await import('/src/api/registry/SurfaceCatalogClient.ts');
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/chrome/Shell.ts');
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL',
  presentation: { labelKey: id, descriptionKey: id }, consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('${SEARCH}', 'jf-sv3-window'),
  surface('${LIBRARY}', 'jf-library-surface', { members: ['${BROWSE}'] }),
  surface('${BROWSE}', 'jf-browse-surface', { placement: 'DEEPLINK' }),
  surface('${BRAIN}', 'jf-brain-surface', { members: ['${MEMORY}'] }),
  surface('${MEMORY}', 'jf-memory-surface', { placement: 'DEEPLINK' }),
  surface('core.settings-surface', 'jf-settings-surface', { placement: 'MODAL' }),
] });
document.body.append(document.createElement('jf-shell'));
window.inspector = await import('/src/shell-v0/state/inspectorState.ts');
window.developerTools = await import('/src/shell-v0/state/developerToolsState.ts');
window.tabs = await import('/src/shell-v0/commands/InspectorTabRegistry.ts');
const { getSurface } = await import('/src/api/registry/SurfaceCatalogClient.ts');
const stage = () => document.querySelector('jf-shell').shadowRoot.querySelector('jf-stage');
window.split = async (left, right) => {
  const el = stage();
  el.surface = getSurface(left);
  el.secondarySurface = getSurface(right);
  el.splitAxis = 'horizontal';
  await el.updateComplete;
};
const source = (i) => ({ parentDocId: 'C:/Docs/Source ' + i + ' with a name too long for one details tab.md', chunkIndex: i, excerpt: 'excerpt ' + i, startLine: i * 10, endLine: i * 10 + 2, headingText: '', startChar: i * 10, endChar: i * 10 + 8 });
window.converse = async (count, { excerpt, conversations = ['c1'] } = {}) => {
  const win = stage().shadowRoot.querySelector('jf-sv3-window');
  const sources = Array.from({ length: count }, (_, i) => ({ ...source(i), ...(excerpt ? { excerpt } : {}) }));
  const marks = sources.map((item, i) => ({ sentenceText: 'A sentence about heating.', similarity: 0.9, sentenceIndex: 0, label: i + 1,
    detail: { parentDocId: item.parentDocId, startLine: item.startLine, endLine: item.endLine, excerpt: item.excerpt, startChar: item.startChar, endChar: item.endChar },
    hover: { excerpt: item.excerpt, title: 'source', headingText: '' } }));
  const matches = sources.map((_, i) => ({ sentenceIndex: 0, sentenceText: 'A sentence about heating.', similarity: 0.9, sourceIndex: i }));
  const turn = { id: 't1', recordId: null, assistantRecordId: null, recordOpenedByUser: false, kind: 'ask', question: 'heating', answer: 'A sentence about heating.', status: 'complete',
    evidence: { sources, matches, marks, retrievalMode: 'HYBRID' }, detail: '', toolCalls: 0, activity: [], reasoning: [], askedAt: 1 };
  win.tier = 'ask';
  win.sessions = { sessions: conversations.map(id => ({ id, title: id, turns: [turn], pinned: false, createdAt: 1, completedAt: 1, lastVisitedAt: 1, history: null, usage: null })), activeId: conversations[0] };
  await win.updateComplete;
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
  output = await mkdtemp(path.join(process.env.TMPDIR ?? tmpdir(), 'details-app-'));
  server = await createServer({
    configFile: false, root: process.cwd(), cacheDir: output, publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'details-app-harness',
      configureServer(vite) {
        vite.middlewares.use((req, res, next) => {
          if (req.url?.split('?')[0] !== '/app') return next();
          res.setHeader('Content-Type', 'text/html');
          res.end(HARNESS);
        });
      },
    }],
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

async function open(width: number, height: number, hash = ''): Promise<Page> {
  context = await browser.newContext({ viewport: { width, height } });
  const page = await context.newPage();
  page.on('pageerror', error => console.info('pageerror', error.message));
  await page.goto(`${url}${hash}`, { timeout: 90_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 90_000 });
  await page.locator('jf-top-bar').waitFor();
  await settle(page, 400);
  return page;
}

async function area(page: Page, id: string): Promise<void> {
  await page.evaluate(target => { location.hash = `#justsearch://surface/${target}`; }, id);
  await settle(page, 600);
}

/** Search's real composer: the query goes in as the reader types it, and the list comes back. */
async function search(page: Page): Promise<void> {
  await page.locator('jf-sv3-composer textarea').fill('heating');
  await page.keyboard.press('Enter');
  await page.locator('[data-testid="search-result-row"]').first().waitFor({ timeout: 15_000 });
  await settle(page);
}

const row = (page: Page) => page.locator('[data-testid="search-result-row"]').first();
const pane = (page: Page) => page.locator('[data-testid="details-pane"]');
const close = (page: Page) => page.locator('[data-testid="details-pane-close"] button');

/** The element with the keyboard, through open shadow roots, as `tag.class[role]`. */
const focusNow = (page: Page): Promise<string> => page.evaluate(() => {
  let active = document.activeElement;
  while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
  return active ? `${active.localName}.${active.getAttribute('class') ?? ''}[${active.getAttribute('role') ?? ''}]` : 'none';
});

/** Show a document in the window holding the keyboard, as `host.ui.showInspector` does. */
const showDocument = async (page: Page, name: string): Promise<void> => {
  await page.evaluate(file => {
    (window as unknown as { inspector: { setSelected(item: unknown): void } }).inspector
      .setSelected({ id: file, title: file, path: `C:/Docs/${file}` });
  }, name);
  await settle(page, 300);
};

/** Move the keyboard into a window, without touching its controls' state. */
const focusIn = (page: Page, tag: string): Promise<void> => page.evaluate(name => {
  const deep = (root: ParentNode): HTMLElement | null => {
    for (const el of root.querySelectorAll<HTMLElement>('*')) {
      if (el.localName === name) return el;
      const inner = el.shadowRoot && deep(el.shadowRoot);
      if (inner) return inner;
    }
    return null;
  };
  const inside = (root: ParentNode): HTMLElement | null => {
    for (const el of root.querySelectorAll<HTMLElement>('*')) {
      if (el.matches('button, [tabindex="0"]') && el.getClientRects().length > 0) return el;
      const inner = el.shadowRoot && inside(el.shadowRoot);
      if (inner) return inner;
    }
    return null;
  };
  const host = deep(document)!;
  inside(host.shadowRoot ?? host)!.focus();
}, tag);

/** The widest box a window draws, through open shadow roots: `display: contents` hosts have none. */
const windowWidth = (page: Page, tag: string): Promise<number> => page.evaluate(name => {
  const deep = (root: ParentNode): Element | null => {
    for (const el of root.querySelectorAll('*')) {
      if (el.localName === name) return el;
      const inner = el.shadowRoot && deep(el.shadowRoot);
      if (inner) return inner;
    }
    return null;
  };
  let widest = 0;
  const walk = (root: ParentNode): void => {
    for (const el of root.querySelectorAll('*')) {
      widest = Math.max(widest, el.getBoundingClientRect().width);
      if (el.shadowRoot) walk(el.shadowRoot);
    }
  };
  const host = deep(document)!;
  walk(host.shadowRoot ?? host);
  walk(host);
  return Math.round(widest);
}, tag);

const WINDOWS = [[LIBRARY, 'jf-library-surface'], [BROWSE, 'jf-library-surface'], [MEMORY, 'jf-brain-surface']] as const;

describe('X4 every window fills its area', () => {
  it.each([900, 1280, 1600])('at %i px Library Folders, Browse and AI Brain › Memory are as wide as the window', async width => {
    const page = await open(width, 800);
    for (const [id, tag] of WINDOWS) {
      await area(page, id);
      expect(await windowWidth(page, tag), id).toBe(width);
    }
  }, 120_000);

  it('in a split at 1600 px each window fills its half', async () => {
    const page = await open(1600, 800);
    await page.evaluate(([left, right]) => (window as unknown as { split(l: string, r: string): Promise<void> }).split(left!, right!), [LIBRARY, BRAIN]);
    await settle(page, 800);
    expect(Math.abs(await windowWidth(page, 'jf-library-surface') - 800)).toBeLessThanOrEqual(1);
    expect(Math.abs(await windowWidth(page, 'jf-brain-surface') - 800)).toBeLessThanOrEqual(1);
  }, 120_000);

  it('with a document open inline a window takes exactly what its pane leaves, and closing gives it back', async () => {
    const page = await open(1280, 800);
    await area(page, LIBRARY);
    await focusIn(page, 'jf-library-surface');
    await showDocument(page, 'report.md');
    const rect = await pane(page).boundingBox();
    expect(rect!.x + rect!.width).toBe(1280);
    const footer = await page.locator('[data-testid="details-open-file"]').boundingBox();
    expect(footer!.y + footer!.height).toBeLessThanOrEqual(800);
    expect(await windowWidth(page, 'jf-library-surface')).toBe(1280 - Math.round(rect!.width));
    await close(page).click();
    await settle(page);
    expect(await windowWidth(page, 'jf-library-surface')).toBe(1280);
  }, 120_000);
});

describe('DP1 a result opens the sheet with the keyboard in it', () => {
  it.each(['click', 'Enter'])('at 900×800 %s on a result: the sheet takes focus, Escape closes it, focus returns to the result', async how => {
    const page = await open(900, 800);
    await search(page);
    if (how === 'click') await row(page).click();
    else { await row(page).focus(); await page.keyboard.press('Enter'); }
    await settle(page, 300);
    expect(await page.locator('[data-testid="details-pane-backdrop"]').isVisible()).toBe(true);
    expect(await focusNow(page)).toContain('[tab]');
    await page.keyboard.press('Escape');
    await settle(page);
    expect(await pane(page).count()).toBe(0);
    expect(await focusNow(page)).toContain('[listitem]');
  }, 120_000);
});

describe('DP1 closing returns the keyboard to what opened the pane', () => {
  const mark = (page: Page) => page.locator('.cite-ref').first();

  it.each([['Escape', 1280], ['close', 1280], ['Escape', 900]] as const)('%s from an inline citation lands on that citation at %i px', async (how, width) => {
    const page = await open(width, 800);
    await page.evaluate(() => (window as unknown as { converse(count: number): Promise<void> }).converse(2));
    await settle(page, 300);
    await mark(page).click();
    await settle(page, 300);
    expect(await focusNow(page)).toContain('[tab]');
    if (how === 'Escape') await page.keyboard.press('Escape');
    else await close(page).click();
    await settle(page);
    expect(await pane(page).count()).toBe(0);
    expect(await focusNow(page)).toContain('.cite-ref');
  }, 120_000);

  it.each(['Escape', 'close'])('%s from inside a pane a result opened lands on the result', async how => {
    const page = await open(1280, 800);
    await search(page);
    await row(page).focus();
    await page.keyboard.press('Enter');
    await settle(page, 300);
    await page.locator('[data-testid="details-pane"] [role="tab"]').first().focus();
    if (how === 'Escape') await page.keyboard.press('Escape');
    else await close(page).click();
    await settle(page);
    expect(await pane(page).count()).toBe(0);
    expect(await focusNow(page)).toContain('[listitem]');
  }, 120_000);
});

describe('X3 F6 walks the frame in the design\u2019s order', () => {
  async function regions(page: Page, presses: number): Promise<string[]> {
    const seen: string[] = [];
    for (let i = 0; i < presses; i += 1) {
      await page.keyboard.press('F6');
      await settle(page, 60);
      seen.push(await page.evaluate(() => {
        const inside = (selector: string): boolean => {
          let node: Element | null = document.activeElement;
          while (node?.shadowRoot?.activeElement) node = node.shadowRoot.activeElement;
          for (; node; node = node.parentElement ?? ((node.getRootNode() as ShadowRoot).host ?? null)) if (node.matches(selector)) return true;
          return false;
        };
        return inside('[data-details-pane]') ? 'details' : inside('jf-top-bar') ? 'top bar'
          : inside('jf-surface-tabs') ? 'window tabs' : inside('.stage') ? 'content' : 'elsewhere';
      }));
    }
    return seen;
  }

  it('Search with its pane open: top bar → content → details, and around again', async () => {
    const page = await open(1280, 800);
    await page.evaluate(() => (window as unknown as { converse(count: number): Promise<void> }).converse(2));
    await settle(page, 300);
    await page.locator('.cite-ref').first().click();
    await settle(page, 300);
    expect(await regions(page, 6)).toEqual(['top bar', 'content', 'details', 'top bar', 'content', 'details']);
  }, 120_000);

  it('Library with its pane open: top bar → window tabs → content → details', async () => {
    const page = await open(1280, 800);
    await area(page, LIBRARY);
    await focusIn(page, 'jf-library-surface');
    await showDocument(page, 'report.md');
    await page.locator('[data-testid="details-pane"] [role="tab"]').first().focus();
    expect(await regions(page, 4)).toEqual(['top bar', 'window tabs', 'content', 'details']);
  }, 120_000);
});

describe('DP4 tabs keep readable labels', () => {
  /** What the strip shows: which tabs lost words, and whether the selected tab is inside it. */
  const strip = (page: Page) => page.locator('[data-testid="details-pane"] [role="tablist"]').evaluate(list => {
    const tabs = [...list.querySelectorAll<HTMLElement>('[role="tab"]')];
    const widest = Math.max(...tabs.map(tab => Number.parseFloat(getComputedStyle(tab).maxInlineSize)));
    const edge = list.getBoundingClientRect();
    const selected = list.querySelector('[aria-selected="true"]')!.getBoundingClientRect();
    return {
      // A name longer than a tab's limit may be cut at the limit; nothing else may be cut at all.
      short: tabs.filter(tab => tab.getBoundingClientRect().width < widest - 1
        && tab.querySelector('.label')!.scrollWidth > tab.querySelector<HTMLElement>('.label')!.clientWidth + 1).map(tab => tab.textContent?.trim()),
      narrow: tabs.filter(tab => tab.getBoundingClientRect().width < 40).length,
      selectedVisible: selected.left >= edge.left - 1 && selected.right <= edge.right + 1,
      scrolls: list.scrollWidth > list.clientWidth,
      overflow: getComputedStyle(list).overflowX,
    };
  });

  it.each(['', '?wideFont'])('in a split with plugin tabs %s: labels stay whole and the strip scrolls', async query => {
    const page = await open(1280, 800, query);
    await page.evaluate(`(async () => {
      const { makePluginProvenance } = await import('/src/shell-v0/primitives/provenance.ts');
      for (const label of ['Entities', 'Timeline', 'Relations']) window.tabs.registerInspectorTab({ id: 'acme.' + label, label, source: 'plugin',
        provenance: makePluginProvenance('acme.tabs', '1.0.0'), render: () => document.createElement('div') });
    })()`);
    await page.evaluate(([left, right]) => (window as unknown as { split(l: string, r: string): Promise<void> }).split(left!, right!), [LIBRARY, BRAIN]);
    await settle(page, 600);
    await focusIn(page, 'jf-library-surface');
    await showDocument(page, 'A very long file name that cannot possibly fit in one details tab of the pane.pdf');
    expect(await strip(page)).toMatchObject({ short: [], narrow: 0, selectedVisible: true, scrolls: true });
    await page.locator('[data-testid="details-pane"] [role="tab"]').first().focus();
    await page.keyboard.press('End');
    await settle(page, 300);
    expect(await strip(page)).toMatchObject({ short: [], selectedVisible: true });
  }, 120_000);
});

type Converse = { converse(count: number, options?: { excerpt?: string; conversations?: string[] }): Promise<void> };

const conversed = async (page: Page, count: number, options?: { excerpt?: string; conversations?: string[] }): Promise<void> => {
  await page.evaluate(([sources, shape]) => (window as unknown as Converse).converse(sources as number, shape as { excerpt?: string }), [count, options] as const);
  await settle(page, 300);
};

/** Follow citation 1 from the keyboard, which works whatever overlaps it at the smallest window. */
const openWithEnter = async (page: Page): Promise<void> => {
  await page.locator('.cite-ref').first().focus();
  await page.keyboard.press('Enter');
  await settle(page, 300);
};

const addPluginTabs = (page: Page, labels: string[]): Promise<void> => page.evaluate(`(async () => {
  const { makePluginProvenance } = await import('/src/shell-v0/primitives/provenance.ts');
  for (const label of ${JSON.stringify(labels)}) window.tabs.registerInspectorTab({ id: 'acme.' + label, label, source: 'plugin',
    provenance: makePluginProvenance('acme.tabs', '1.0.0'), render: () => document.createElement('div') });
})()`);

const tabStrip = (page: Page) => page.locator('[data-testid="details-pane"] [role="tablist"]');
const selectedTab = (page: Page) => page.locator('[data-testid="details-pane"] [role="tab"][aria-selected="true"]');
const SOURCE_NAME = (i: number): string => `Source ${i} with a name too long for one details tab.md`;

/** Where each tab sits against the strip, and whether the selected one clears the edge affordances. */
const stripFacts = (page: Page) => tabStrip(page).evaluate(list => {
  const box = list.getBoundingClientRect();
  const tabs = [...list.querySelectorAll<HTMLElement>('[role="tab"]')];
  const selected = list.querySelector('[aria-selected="true"]')!.getBoundingClientRect();
  const edges = [...list.parentElement!.querySelectorAll<HTMLElement>('.edge')].filter(edge => getComputedStyle(edge).display !== 'none')
    .map(edge => edge.getBoundingClientRect());
  return {
    shown: tabs.map(tab => tab.textContent?.trim()),
    cut: tabs.filter(tab => tab.getBoundingClientRect().left < box.left - 1 || tab.getBoundingClientRect().right > box.right + 1).map(tab => tab.textContent?.trim()),
    selectedClear: selected.left >= box.left - 1 && selected.right <= box.right + 1
      && edges.every(edge => selected.right <= edge.left + 1 || selected.left >= edge.right - 1),
    scrollLeft: list.scrollLeft,
  };
});

describe('DP4 tabs with source numbers', () => {
  const SIZES = [[1280, 800], [900, 800], [600, 400]] as const;
  const FONTS = ['', '?wideFont'] as const;
  const CASES = SIZES.flatMap(([width, height]) => FONTS.map(font => [width, height, font] as const));

  it.each(CASES)('at %ix%i %s six sources, Context and a plugin tab all fit, sources by number', async (width, height, font) => {
    const page = await open(width, height, font);
    await addPluginTabs(page, ['Entities']);
    await conversed(page, 6);
    await openWithEnter(page);
    const facts = await stripFacts(page);
    expect(facts.shown).toEqual(['1', '2', '3', '4', '5', '6', 'Context', 'Entities']);
    expect(facts.cut).toEqual([]);
    expect(await tabStrip(page).evaluate(list => list.scrollWidth <= list.clientWidth)).toBe(true);
  }, 120_000);

  it.each(FONTS)('a source tab is named and tipped by its full name, and the header names the selected source %s', async font => {
    const page = await open(1280, 800, font);
    await conversed(page, 3);
    await openWithEnter(page);
    for (const i of [0, 1, 2]) {
      const full = `C:/Docs/${SOURCE_NAME(i)}`;
      const tab = page.getByRole('tab', { name: full });
      expect(await tab.count(), full).toBe(1);
      expect(await tab.getAttribute('title')).toBe(full);
      expect((await tab.textContent())?.trim()).toBe(String(i + 1));
    }
    const header = page.locator('[data-testid="details-source-name"]');
    expect(await header.textContent()).toBe(SOURCE_NAME(0));
    await page.keyboard.press('ArrowRight');
    await settle(page, 300);
    expect(await header.textContent()).toBe(SOURCE_NAME(1));
    expect(await header.getAttribute('title')).toBe(`C:/Docs/${SOURCE_NAME(1)}`);
    await page.keyboard.press('End');
    await settle(page, 300);
    expect(await header.count()).toBe(0);
    expect((await selectedTab(page).textContent())?.trim()).toBe('Context');
  }, 120_000);

  it.each(FONTS)('past what fits the strip takes the wheel, shows its edges and keeps the selected tab clear of them %s', async font => {
    const page = await open(1280, 800, font);
    await conversed(page, 14);
    await openWithEnter(page);
    const before = page.locator('[data-testid="details-tabs-more-before"]');
    const after = page.locator('[data-testid="details-tabs-more-after"]');
    expect(await tabStrip(page).evaluate(list => list.scrollWidth > list.clientWidth)).toBe(true);
    expect(await after.isVisible()).toBe(true);
    expect(await before.isVisible()).toBe(false);
    const strip = await tabStrip(page).boundingBox();
    await page.mouse.move(strip!.x + strip!.width / 2, strip!.y + strip!.height / 2);
    await page.mouse.wheel(0, 120);
    await settle(page);
    expect((await stripFacts(page)).scrollLeft).toBeGreaterThan(0);
    expect(await before.isVisible()).toBe(true);
    await selectedTab(page).focus();
    for (let i = 0; i < 14; i += 1) {
      await page.keyboard.press('ArrowRight');
      await settle(page, 60);
      expect((await stripFacts(page)).selectedClear, `after ${i + 1} presses`).toBe(true);
    }
    expect((await selectedTab(page).textContent())?.trim()).toBe('Context');
    expect(await after.isVisible()).toBe(false);
    await page.keyboard.press('Home');
    await settle(page, 300);
    expect((await stripFacts(page)).selectedClear).toBe(true);
    expect(await before.isVisible()).toBe(false);
  }, 120_000);
});

describe('DP4 the keyboard stays on the selected tab', () => {
  const keyboardOn = (page: Page): Promise<string | null> => page.evaluate(() => {
    let active = document.activeElement;
    while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
    return active?.getAttribute('data-tab') ?? null;
  });

  async function walk(page: Page, ids: string[]): Promise<void> {
    expect(await keyboardOn(page)).toBe(ids[0]);
    for (const id of ids.slice(1)) {
      await page.keyboard.press('ArrowRight');
      await settle(page, 120);
      expect(await selectedTab(page).getAttribute('data-tab')).toBe(id);
      expect(await keyboardOn(page)).toBe(id);
    }
    await page.keyboard.press('Enter');
    await settle(page, 120);
    expect(await selectedTab(page).getAttribute('data-tab')).toBe(ids.at(-1));
    await page.keyboard.press('Home');
    await settle(page, 120);
    expect(await selectedTab(page).getAttribute('data-tab')).toBe(ids[0]);
    expect(await keyboardOn(page)).toBe(ids[0]);
    await page.keyboard.press('End');
    await settle(page, 120);
    expect(await selectedTab(page).getAttribute('data-tab')).toBe(ids.at(-1));
    expect(await keyboardOn(page)).toBe(ids.at(-1));
  }

  it('in Search, from a citation opened with Enter', async () => {
    const page = await open(1280, 800);
    await conversed(page, 3);
    await openWithEnter(page);
    await walk(page, ['source:0', 'source:1', 'source:2', 'context']);
  }, 120_000);

  it('in Library\u2019s pane', async () => {
    const page = await open(1280, 800);
    await addPluginTabs(page, ['Entities', 'Timeline', 'Relations']);
    await area(page, LIBRARY);
    await focusIn(page, 'jf-library-surface');
    await showDocument(page, 'report.md');
    await page.locator('[data-testid="details-pane"] [role="tab"]').first().focus();
    await walk(page, ['document', 'plugin:acme.Entities', 'plugin:acme.Timeline', 'plugin:acme.Relations']);
  }, 120_000);
});

describe('C4 a late hide belongs to its own conversation', () => {
  const toggle = (page: Page) => page.locator('[data-testid="sv3-pane-toggle-hidden"] button');
  const conversation = (page: Page, name: string) => page.locator('[data-testid="sv3-sidebar-row"]').filter({ hasText: name });

  it('a response released in B leaves B\u2019s Hide alone, and A has it when the reader returns', async () => {
    const page = await open(1280, 800);
    await conversed(page, 2, { conversations: ['First', 'Second'] });
    await page.locator('.cite-ref').first().click();
    await settle(page, 300);
    await toggle(page).click();
    await conversation(page, 'Second').click();
    await settle(page, 400);
    await page.locator('.cite-ref').first().click();
    await settle(page, 300);
    expect(await toggle(page).textContent()).toContain('Hide');
    await page.evaluate(() => (window as unknown as { releaseExclude(): void }).releaseExclude());
    await settle(page, 300);
    expect(await toggle(page).textContent()).toContain('Hide');
    await conversation(page, 'First').click();
    await settle(page, 400);
    await page.locator('.cite-ref').first().click();
    await settle(page, 300);
    expect(await toggle(page).textContent()).toContain('Restore');
  }, 120_000);
});

describe('DP1 a citation opens from the mouse at every width', () => {
  const LONG_EXCERPT = 'A passage long enough to fill the preview to its three lines. '.repeat(12);

  it.each([[1280, 800], [900, 800], [600, 800]] as const)('at %ix%i the preview sits under the mark and the click reaches it', async (width, height) => {
    const page = await open(width, height);
    await conversed(page, 3, { excerpt: LONG_EXCERPT });
    const mark = await page.locator('.cite-ref').first().boundingBox();
    const [x, y] = [mark!.x + mark!.width / 2, mark!.y + mark!.height / 2];
    await page.mouse.move(x, y);
    await settle(page, 300);
    const card = await page.locator('[data-testid="sv3-citation-hover"] .card').boundingBox();
    await page.mouse.down();
    await page.mouse.up();
    await settle(page, 300);
    expect(await pane(page).count()).toBe(1);
    expect(Math.round(card!.x)).toBe(Math.round(mark!.x));
    expect(Math.round(card!.y)).toBe(Math.round(mark!.y + mark!.height) + 4);
  }, 120_000);
});

describe('DP5 the reader on a search result', () => {
  it('has no citation notice: a result is not a citation', async () => {
    const page = await open(1280, 800);
    await search(page);
    await row(page).click();
    await page.locator('jf-document-pane [role="region"][aria-label="Document content"]').first().waitFor();
    await settle(page, 300);
    expect(await page.locator('jf-document-pane [role="note"]').allTextContents()).toEqual([]);
  }, 120_000);
});

describe('X1 the reader\u2019s quiet highlight', () => {
  /** The ink and fill of the passage as painted, composed over the pane through a canvas. */
  const painted = (page: Page, selector: string) => page.locator(`jf-document-pane ${selector}`).first().evaluate(mark => {
    const canvas = document.createElement('canvas');
    canvas.width = 1;
    canvas.height = 1;
    const paint = canvas.getContext('2d', { willReadFrequently: true })!;
    const fills: string[] = [];
    for (let at: Element | null = mark; at !== null; at = at.parentElement ?? ((at.getRootNode() as ShadowRoot).host ?? null)) {
      const fill = getComputedStyle(at).backgroundColor;
      if (fill !== 'rgba(0, 0, 0, 0)') fills.unshift(fill);
    }
    for (const fill of fills) { paint.fillStyle = fill; paint.fillRect(0, 0, 1, 1); }
    const rgb = (): number[] => [...paint.getImageData(0, 0, 1, 1).data.slice(0, 3)];
    const fill = rgb();
    let inked: Element = mark;
    while (inked.firstElementChild !== null) inked = inked.firstElementChild;
    paint.fillStyle = getComputedStyle(inked).color;
    paint.fillRect(0, 0, 1, 1);
    const ink = rgb();
    const luminance = (colour: number[]): number => {
      const [r, g, b] = colour.map(value => { const c = value / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; });
      return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!;
    };
    const [hi, lo] = [luminance(fill), luminance(ink)].sort((a, b) => b - a);
    return { ink, fill, ratio: (hi! + 0.05) / (lo! + 0.05) };
  });

  async function openAt(page: Page, theme: 'light' | 'dark', view: 'Rendered' | 'Source'): Promise<void> {
    await page.evaluate(mode => document.documentElement.setAttribute('data-theme', mode), theme);
    await conversed(page, 2);
    await openWithEnter(page);
    await page.evaluate(() => (window as unknown as { developerTools: { setShowDeveloperTools(show: boolean): void } }).developerTools.setShowDeveloperTools(true));
    await page.getByRole('radio', { name: view }).click();
    await page.locator('jf-document-pane .hl-weak').first().waitFor({ timeout: 10_000 });
    await settle(page, 400);
  }

  it.each(['Rendered', 'Source'] as const)('reads at 4.5:1 in the light theme, %s view', async view => {
    const page = await open(1280, 800);
    await openAt(page, 'light', view);
    expect((await painted(page, '.hl-weak')).ratio).toBeGreaterThanOrEqual(4.5);
  }, 120_000);

  it.each([['dark', 'Rendered', '.blocks'], ['dark', 'Source', 'pre.source'], ['forced', 'Rendered', '.blocks'], ['forced', 'Source', 'pre.source']] as const)('in %s colours, %s view, the passage keeps the ink around it', async (palette, view, around) => {
    const page = await open(1280, 800);
    if (palette === 'forced') await page.emulateMedia({ forcedColors: 'active' });
    await openAt(page, 'dark', view);
    const ink = (selector: string): Promise<string> => page.locator(`jf-document-pane ${selector}`).first().evaluate(element => getComputedStyle(element).color);
    expect(await ink('.hl-weak')).toBe(await ink(around));
  }, 120_000);
});

describe('X1 a tab\u2019s number is sized from the scale', () => {
  it('its least width is the token', async () => {
    const page = await open(1280, 800);
    await page.evaluate(() => document.documentElement.style.setProperty('--jf-space-4', '40px'));
    await conversed(page, 2);
    await openWithEnter(page);
    expect(await page.locator('[data-testid="details-pane"] [role="tab"] .number').first().evaluate(number => getComputedStyle(number).minInlineSize)).toBe('40px');
  }, 120_000);
});

describe('DP4 a document shown in a conversation with sources', () => {
  it('has a tab of its own, selected, and the panel is labelled by it', async () => {
    const page = await open(1280, 800);
    await page.evaluate(() => (window as unknown as { converse(count: number): Promise<void> }).converse(2));
    await settle(page, 300);
    await page.locator('jf-sv3-composer textarea').focus();
    await showDocument(page, 'report.md');
    const selected = page.locator('[data-testid="details-pane"] [role="tab"][aria-selected="true"]');
    expect(await selected.count()).toBe(1);
    expect(await selected.textContent()).toContain('report.md');
    const labelledBy = await page.locator('[data-testid="details-pane"] [role="tabpanel"]').getAttribute('aria-labelledby');
    expect(await selected.getAttribute('id')).toBe(labelledBy);
  }, 120_000);
});

describe('DP1 Escape with the keyboard on the page', () => {
  async function bothOpen(page: Page): Promise<void> {
    await page.evaluate(([left, right]) => (window as unknown as { split(l: string, r: string): Promise<void> }).split(left!, right!), [LIBRARY, BRAIN]);
    await settle(page, 600);
    await focusIn(page, 'jf-library-surface');
    await showDocument(page, 'left.md');
    await focusIn(page, 'jf-brain-surface');
    await showDocument(page, 'right.md');
    expect(await pane(page).count()).toBe(2);
  }
  const blur = (page: Page) => page.evaluate(() => {
    let active = document.activeElement as HTMLElement | null;
    while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement as HTMLElement;
    active?.blur();
  });
  const shown = (page: Page) => page.locator('[data-testid="details-pane"] [role="tab"][aria-selected="true"]').allTextContents()
    .then(names => names.map(name => name.trim()));

  it('closes the pane of the window the pointer is in, and only that one', async () => {
    const page = await open(1280, 800);
    await bothOpen(page);
    await blur(page);
    await page.mouse.move(100, 400);
    await page.keyboard.press('Escape');
    await settle(page);
    expect(await shown(page)).toEqual(['right.md']);
  }, 120_000);

  it('with the pointer still, closes the pane of the window focus was last in', async () => {
    const page = await open(1280, 800);
    await bothOpen(page);
    await blur(page);
    await page.keyboard.press('Escape');
    await settle(page);
    expect(await shown(page)).toEqual(['left.md']);
  }, 120_000);

  it('Search alone: with focus dropped to the page, Escape still closes and returns to the citation', async () => {
    const page = await open(1280, 800);
    await page.evaluate(() => (window as unknown as { converse(count: number): Promise<void> }).converse(2));
    await settle(page, 300);
    await page.locator('.cite-ref').first().click();
    await settle(page, 300);
    await blur(page);
    await page.keyboard.press('Escape');
    await settle(page);
    expect(await pane(page).count()).toBe(0);
    expect(await focusNow(page)).toContain('.cite-ref');
  }, 120_000);
});

describe('C4 each window\u2019s close is its own', () => {
  async function bothOpen(page: Page): Promise<void> {
    await page.evaluate(([left, right]) => (window as unknown as { split(l: string, r: string): Promise<void> }).split(left!, right!), [SEARCH, LIBRARY]);
    await settle(page, 600);
    await page.evaluate(() => (window as unknown as { converse(count: number): Promise<void> }).converse(2));
    await settle(page, 300);
    await page.locator('.cite-ref').first().click();
    await settle(page, 300);
    await focusIn(page, 'jf-library-surface');
    await showDocument(page, 'library.md');
    expect(await pane(page).count()).toBe(2);
  }
  const library = (page: Page) => page.locator('jf-window-details [data-testid="details-pane"]');
  const searchPane = (page: Page) => page.locator('jf-sv3-pane [data-testid="details-pane"]');

  it('Search\u2019s Close leaves Library\u2019s document open', async () => {
    const page = await open(1600, 800);
    await bothOpen(page);
    await page.locator('jf-sv3-pane [data-testid="details-pane-close"] button').click();
    await settle(page);
    expect(await searchPane(page).count()).toBe(0);
    expect(await library(page).count()).toBe(1);
  }, 120_000);

  it('Library\u2019s Close leaves Search\u2019s document open', async () => {
    const page = await open(1600, 800);
    await bothOpen(page);
    await page.locator('jf-window-details [data-testid="details-pane-close"] button').click();
    await settle(page);
    expect(await library(page).count()).toBe(0);
    expect(await searchPane(page).count()).toBe(1);
  }, 120_000);
});

describe('X1 the frame\u2019s numbers are tokens', () => {
  it('the Space preview sits one step of the scale inside its row', async () => {
    const page = await open(1280, 800);
    await search(page);
    await page.evaluate(() => document.documentElement.style.setProperty('--jf-space-2', '24px'));
    await row(page).focus();
    await page.keyboard.press('Space');
    await settle(page);
    const rect = await page.locator('[data-testid="sv3-result-preview"]').boundingBox();
    const anchor = await row(page).boundingBox();
    expect(Math.round(anchor!.x + anchor!.width - (rect!.x + rect!.width))).toBe(24);
    expect(Math.round(rect!.y - anchor!.y)).toBe(24);
  }, 120_000);

  it('the window pane\u2019s grip takes its layer from the token', async () => {
    const page = await open(1280, 800);
    await page.evaluate(() => document.documentElement.style.setProperty('--z-base', '7'));
    await area(page, LIBRARY);
    await focusIn(page, 'jf-library-surface');
    await showDocument(page, 'report.md');
    expect(await page.locator('[data-testid="details-pane-grip"]').evaluate(grip => getComputedStyle(grip).zIndex)).toBe('7');
  }, 120_000);
});

describe('governance', () => {
  it('the pane\u2019s decisions are a declared execution surface', async () => {
    const register = JSON.parse(await readFile(path.resolve('../../governance/execution-surfaces.v1.json'), 'utf8')) as { surfaces: Array<{ path: string }> };
    expect(register.surfaces.map(surface => surface.path)).toContain('modules/ui-web/src/shell-v0/views/search-v3/sv3-details-domain.ts');
  });
});

describe('DP3 a folder\u2019s failed files', () => {
  const lists = (page: Page) => page.locator('jf-failed-files-list .list:visible');
  const toggle = (page: Page) => page.locator('.folder-toggle').first();

  it('stay closed under a closed folder, open and close with it, and scroll past the token\u2019s height', async () => {
    const page = await open(1280, 800);
    await area(page, LIBRARY);
    await page.locator('.folder-toggle').first().waitFor();
    await settle(page, 400);
    expect(await lists(page).count()).toBe(0);
    await toggle(page).click();
    await settle(page, 300);
    expect(await lists(page).count()).toBe(1);
    const size = await lists(page).first().evaluate(list => ({ height: list.clientHeight, full: list.scrollHeight,
      bound: Number.parseFloat(getComputedStyle(document.documentElement).fontSize) * Number.parseFloat(getComputedStyle(list).getPropertyValue('--frame-failed-list-max')) }));
    expect(size.full).toBeGreaterThan(size.height);
    expect(size.height).toBeLessThanOrEqual(size.bound);
    await toggle(page).click();
    await settle(page, 300);
    expect(await lists(page).count()).toBe(0);
  }, 120_000);

  it('the failed chip opens a closed folder and puts the keyboard on the list', async () => {
    const page = await open(1280, 800);
    await area(page, LIBRARY);
    await page.locator('jf-button.failed-chip').first().waitFor();
    await settle(page, 400);
    await page.locator('jf-button.failed-chip button').first().click();
    await settle(page, 300);
    expect(await lists(page).count()).toBe(1);
    expect(await focusNow(page)).toContain('list');
  }, 120_000);
});

describe('DP3 a failed file\u2019s actions', () => {
  it('wear the shared button styling', async () => {
    const page = await open(1280, 800);
    await page.evaluate(`(async () => {
      const { __seedForTest: resources } = await import('/src/api/registry/ResourceCatalogClient.ts');
      const { __seedForTest: operations } = await import('/src/api/registry/OperationCatalogClient.ts');
      const operation = (id) => ({ id, policy: { risk: 'LOW' }, presentation: { labelKey: id, descriptionKey: id }, provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' } });
      resources({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Resource', entries: [{ id: 'core.failed-indexing-jobs', itemOperations: ['core.retry-indexing-job', 'core.cancel-indexing-job'], collectionOperations: [], primaryKey: 'pathHash', provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' } }] });
      operations({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Operation', entries: [operation('core.retry-indexing-job'), operation('core.cancel-indexing-job')] });
      await import('/src/shell-v0/components/ActionButton.ts');
      const shared = document.createElement('jf-action-button');
      shared.id = 'shared';
      shared.setAttribute('label', 'Shared');
      document.body.append(shared);
    })()`);
    await area(page, LIBRARY);
    await page.locator('.folder-toggle').first().click();
    await settle(page, 400);
    const button = page.locator('jf-failed-files-list jf-row-actions button.invoke').first();
    await button.waitFor();
    const style = (element: Element): string[] => {
      const css = getComputedStyle(element);
      return [css.borderTopColor, css.borderTopWidth, css.backgroundColor, css.paddingBlockStart, css.paddingInlineStart, css.borderTopLeftRadius];
    };
    const row = await button.evaluate(style);
    expect(row).toEqual(await page.locator('#shared button.invoke').evaluate(style));
    expect(row[0]).not.toBe('rgba(0, 0, 0, 0)');
  }, 120_000);
});

describe('DP2 the Space preview follows the keyboard', () => {
  it('closes when Tab moves on from the row', async () => {
    const page = await open(1280, 800);
    await search(page);
    await row(page).focus();
    await page.keyboard.press('Space');
    await settle(page);
    const card = page.locator('[data-testid="sv3-result-preview"]');
    expect(await card.count()).toBe(1);
    await page.keyboard.press('Tab');
    await settle(page);
    expect(await focusNow(page)).not.toContain('[listitem]');
    expect(await card.count()).toBe(0);
  }, 120_000);
});
