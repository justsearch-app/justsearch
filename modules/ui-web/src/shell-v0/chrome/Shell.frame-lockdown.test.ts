// SPDX-License-Identifier: Apache-2.0
// Shell slice 2 — the frame's navigation, address and layout in a real browser: the real Shell,
// router, journal, top bar, status pill, Settings window, command palette and window tab row over
// a private Vite server and headless Chromium. The backend is a stubbed `fetch`; Search is a probe
// window whose text box takes focus on arrival, the way the composer does. Run separately with
// npx vitest run --config vitest.config.lockdown.ts src/shell-v0/chrome/Shell.frame-lockdown.test.ts
// Requires the same installed Chromium as boot-lockdown.test.ts; no Engine is needed.
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import path from 'node:path';

const SEARCH = 'core.search-v3-surface';
const LIBRARY = 'core.library-surface';
const BROWSE = 'core.browse-surface';
const BRAIN = 'core.brain-surface';
const SETTINGS = 'core.settings-surface';

/** The page: catalog, a stubbed backend and the real `<jf-shell>`; everything else is the app's own code. */
const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
const json = (body) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
// ?registryDelay=<ms> holds the boot's window-catalog response, as a busy engine does: the app
// keeps starting (Settings mounts ahead of its first opening) before the address is applied.
const registryDelay = Number(new URLSearchParams(location.search).get('registryDelay') ?? 0);
window.fetch = async (input) => {
  if (registryDelay && String(input).endsWith('/api/registry/surfaces')) await new Promise(r => setTimeout(r, registryDelay));
  return json({ entries: [] });
};
window.EventSource = class { constructor() { this.readyState = 0; } addEventListener() {} removeEventListener() {} close() {} };
const { __seedForTest } = await import('/src/api/registry/SurfaceCatalogClient.ts');
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/chrome/Shell.ts');
/** Search: a text box that takes focus whenever the window is shown, as the composer does. */
customElements.define('jf-frame-search-probe', class extends HTMLElement {
  connectedCallback() {
    if (!this.shadowRoot) this.attachShadow({ mode: 'open' }).innerHTML = '<textarea aria-label="Ask or search"></textarea>';
    requestAnimationFrame(() => this.shadowRoot.querySelector('textarea').focus());
  }
});
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL',
  presentation: { labelKey: id, descriptionKey: id }, consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('${SEARCH}', 'jf-frame-search-probe'),
  surface('${LIBRARY}', 'jf-library-surface', { members: ['${BROWSE}'] }),
  surface('${BROWSE}', 'jf-browse-surface', { placement: 'DEEPLINK' }),
  surface('${BRAIN}', 'jf-brain-surface'),
  surface('core.system-surface', 'jf-system-surface'),
  surface('${SETTINGS}', 'jf-settings-surface', { placement: 'MODAL' }),
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
  const temporaryRoot = path.resolve('../..', 'tmp');
  await mkdir(temporaryRoot, { recursive: true });
  output = await mkdtemp(path.join(temporaryRoot, 'frame-browser-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'frame-harness',
      configureServer(vite) {
        vite.middlewares.use((req, res, next) => {
          if (req.url?.split('?')[0] !== '/frame') return next();
          res.setHeader('Content-Type', 'text/html');
          res.end(HARNESS);
        });
      },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/frame`;
  // Playwright creates its fresh --user-data-dir under the caller's short TMPDIR.
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

async function open(width = 1280, height = 800, query = '', hash = ''): Promise<Page> {
  context = await browser.newContext({ viewport: { width, height } });
  const page = await context.newPage();
  page.on('pageerror', error => console.info('pageerror', error.message));
  await page.goto(`${url}${query}${hash}`);
  await page.locator('body[data-ready="true"]').waitFor();
  await page.locator('jf-top-bar').waitFor();
  await settle(page);
  return page;
}

/** Let the router, popstate and Lit updates finish. */
async function settle(page: Page, ms = 150): Promise<void> {
  await page.waitForTimeout(ms);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

/** The window the stage shows, the address (decoded) and whether Settings is open. */
async function where(page: Page): Promise<{ stage: string | undefined; address: string; settings: boolean }> {
  return page.evaluate(() => {
    const shell = document.querySelector('jf-shell')!.shadowRoot!;
    const stage = shell.querySelector('jf-stage')!.shadowRoot!;
    const shown = [...stage.children].find(c => c.localName.startsWith('jf-'))?.localName;
    const settings = (shell.querySelector('jf-settings-window') as HTMLElement & { open: boolean }).open;
    return { stage: shown, address: decodeURIComponent(location.hash.replace(/^#justsearch:\/\/surface\//, '')), settings };
  });
}

async function area(page: Page, id: string): Promise<void> {
  await page.locator(`jf-top-bar nav [data-surface-id="${id}"]`).click();
  await settle(page);
}

async function openSettings(page: Page): Promise<void> {
  await page.locator('jf-top-bar [data-surface-id="core.settings-surface"]').click();
  await settle(page, 400);
}

/** The Library window's selected tab, by its visible name. */
async function libraryTab(page: Page): Promise<string | null> {
  return page.getByRole('tab', { selected: true }).textContent().then(t => t?.trim() ?? null);
}

/** The section Settings shows (its active category). */
async function settingsSection(page: Page): Promise<string | undefined> {
  return page.evaluate(() => {
    const find = (root: ParentNode): Element | null => {
      for (const el of root.querySelectorAll('*')) {
        if (el.localName === 'jf-settings-surface') return el;
        const inner = el.shadowRoot && find(el.shadowRoot);
        if (inner) return inner;
      }
      return null;
    };
    return (find(document) as (Element & { activeCategory?: string }) | null)?.activeCategory;
  });
}

const NOTICE = "Couldn't find that window";

/**
 * Every element showing the notice's words that a sighted reader can actually read: rendered at a
 * real size, inside the window, not clipped or cut off, not covered, and not hidden from assistive
 * technology. (The pill's live announcer holds the same words in a one-pixel clipped box.)
 */
async function readableNotices(page: Page): Promise<string[]> {
  return page.evaluate((words) => {
    const found: string[] = [];
    const visit = (root: ParentNode): void => {
      for (const el of root.querySelectorAll<HTMLElement>('*')) {
        if (el.shadowRoot) visit(el.shadowRoot);
        if (el.textContent?.trim() !== words || [...el.children].some(c => c.textContent?.trim() === words)) continue;
        const rect = el.getBoundingClientRect();
        const style = getComputedStyle(el);
        const inside = rect.left >= 0 && rect.top >= 0 && rect.right <= innerWidth && rect.bottom <= innerHeight;
        const uncut = el.scrollWidth <= el.clientWidth + 1 && el.scrollHeight <= el.clientHeight + 1;
        let hiddenFromAt = false;
        for (let node: Node | null = el; node; node = (node as Element).parentElement ?? ((node.getRootNode() as ShadowRoot).host ?? null)) {
          if (node instanceof Element && (node.getAttribute('aria-hidden') === 'true' || getComputedStyle(node).clipPath !== 'none')) hiddenFromAt = true;
          if (node === document.documentElement) break;
        }
        const x = rect.left + rect.width / 2;
        const y = rect.top + rect.height / 2;
        let top: Element | null = document.elementFromPoint(x, y);
        while (top?.shadowRoot?.elementFromPoint(x, y) && top.shadowRoot.elementFromPoint(x, y) !== top) top = top.shadowRoot.elementFromPoint(x, y);
        const uncovered = top === el || el.contains(top);
        if (rect.width >= 40 && rect.height >= 8 && style.visibility === 'visible' && Number(style.opacity) > 0 && inside && uncut
          && !hiddenFromAt && uncovered) found.push(el.className || el.localName);
      }
    };
    visit(document);
    return found;
  }, NOTICE);
}

/** The status pill's own words are never cut (L3). */
async function pillCut(page: Page): Promise<boolean> {
  return page.locator('jf-status-pill button.trigger').evaluate(el => el.scrollWidth > el.clientWidth + 1);
}

describe('L3 a notice for a window that does not exist is readable at every size', () => {
  it.each([[1280, 800], [900, 700], [600, 400]])('%i×%i: the notice can be read, the pill is not cut and "Choose destination" is offered', async (width, height) => {
    const page = await open(width, height);
    await page.evaluate(() => { location.hash = '#justsearch://surface/core.nope-window'; });
    await settle(page);
    expect(await readableNotices(page), `${width}×${height}`).not.toEqual([]);
    expect(await pillCut(page)).toBe(false);
    expect(await page.getByRole('button', { name: 'Choose destination' }).isVisible()).toBe(true);
  }, 120_000);
});

describe('C9 closing Settings once keeps the area and leaves no Settings entry behind it', () => {
  it('Settings, browser Back, browser Forward, close: the next Back does not reopen Settings', async () => {
    const page = await open();
    await area(page, BRAIN);
    await openSettings(page);
    expect(await where(page)).toMatchObject({ settings: true, stage: 'jf-brain-surface' });
    await page.goBack(); await settle(page, 400);
    expect(await where(page)).toMatchObject({ settings: false, stage: 'jf-brain-surface', address: BRAIN });
    await page.goForward(); await settle(page, 400);
    expect(await where(page)).toMatchObject({ settings: true, stage: 'jf-brain-surface' });
    await page.keyboard.press('Escape'); await settle(page, 400);
    expect(await where(page)).toMatchObject({ settings: false, stage: 'jf-brain-surface', address: BRAIN });
    await page.goBack(); await settle(page, 400);
    expect(await where(page)).toMatchObject({ settings: false, stage: 'jf-frame-search-probe', address: SEARCH });
  }, 120_000);

  it('reload with Settings open keeps the area underneath; closing returns to it once', async () => {
    const page = await open();
    await area(page, LIBRARY);
    await openSettings(page);
    await page.reload();
    await page.locator('body[data-ready="true"]').waitFor();
    await settle(page, 800);
    expect(await where(page)).toMatchObject({ settings: true, stage: 'jf-library-surface' });
    await page.keyboard.press('Escape'); await settle(page, 400);
    expect(await where(page)).toMatchObject({ settings: false, stage: 'jf-library-surface', address: LIBRARY });
    await page.goBack(); await settle(page, 400);
    expect(await where(page)).toMatchObject({ settings: false, stage: 'jf-frame-search-probe', address: SEARCH });
  }, 120_000);
});

describe('FR3 the Settings section in the address wins over the one Settings remembers', () => {
  it('a link to Settings › Developer opens Developer, and reload keeps it, while the engine is slow to answer', async () => {
    const page = await open(1280, 800, '?registryDelay=1500', `#justsearch://surface/${SETTINGS}?section=developer`);
    await settle(page, 2500);
    expect(await where(page)).toMatchObject({ settings: true, address: `${SETTINGS}?section=developer` });
    expect(await settingsSection(page)).toBe('developer');
    await page.reload();
    await page.locator('body[data-ready="true"]').waitFor();
    await settle(page, 2500);
    expect(await where(page)).toMatchObject({ settings: true, address: `${SETTINGS}?section=developer` });
    expect(await settingsSection(page)).toBe('developer');
  }, 120_000);
});

describe('FR3 Alt+Left and Alt+Right always navigate', () => {
  it('Back into Search puts focus in its text box; Alt+Right still goes Forward and edits nothing', async () => {
    const page = await open();
    await page.getByRole('textbox', { name: 'Ask or search' }).fill('notes');
    await area(page, LIBRARY);
    await page.keyboard.press('Alt+ArrowLeft'); await settle(page);
    expect(await where(page)).toMatchObject({ stage: 'jf-frame-search-probe', address: SEARCH });
    // A handle, not a locator: once Forward leaves Search its text box may be detached from the page.
    const box = (await page.getByRole('textbox', { name: 'Ask or search' }).elementHandle())!;
    expect(await box.evaluate(el => (el.getRootNode() as ShadowRoot).activeElement === el)).toBe(true);
    await page.keyboard.press('Alt+ArrowRight'); await settle(page);
    expect(await where(page)).toMatchObject({ stage: 'jf-library-surface', address: LIBRARY });
    expect(await box.evaluate(el => (el as HTMLTextAreaElement).value)).toBe('notes');
  }, 120_000);
});

describe('FR3 a window tab is a step in history', () => {
  it('Library › Browse adds an entry; Alt+arrows, mouse buttons and browser buttons all land on the same tabs', async () => {
    const page = await open();
    await area(page, LIBRARY);
    expect(await libraryTab(page)).toBe('Folders');
    const length = await page.evaluate(() => history.length);
    await page.getByRole('tab', { name: 'Browse' }).click(); await settle(page);
    expect(await page.evaluate(() => history.length)).toBe(length + 1);
    expect([await libraryTab(page), (await where(page)).address]).toEqual(['Browse', `${LIBRARY}?tab=${BROWSE}`]);
    const mouse = (button: 3 | 4) => page.evaluate(b => {
      window.dispatchEvent(new MouseEvent('mouseup', { button: b, bubbles: true, cancelable: true }));
    }, button);
    const ways: Array<[string, () => Promise<unknown>, () => Promise<unknown>]> = [
      ['Alt+arrows', () => page.keyboard.press('Alt+ArrowLeft'), () => page.keyboard.press('Alt+ArrowRight')],
      ['mouse buttons', () => mouse(3), () => mouse(4)],
      ['browser buttons', () => page.goBack(), () => page.goForward()],
    ];
    for (const [way, back, forward] of ways) {
      await back(); await settle(page);
      expect([await libraryTab(page), (await where(page)).stage], `${way}: Back`).toEqual(['Folders', 'jf-library-surface']);
      await forward(); await settle(page);
      expect([await libraryTab(page), (await where(page)).address], `${way}: Forward`).toEqual(['Browse', `${LIBRARY}?tab=${BROWSE}`]);
    }
    // Back past the tab step leaves Library for the view before it.
    await page.keyboard.press('Alt+ArrowLeft'); await settle(page);
    await page.keyboard.press('Alt+ArrowLeft'); await settle(page);
    expect((await where(page)).stage).toBe('jf-frame-search-probe');
  }, 120_000);
});

describe('FR3 one history entry per navigation', () => {
  it('opening an area from the Ctrl+K palette is one step: one Back returns', async () => {
    const page = await open();
    const length = await page.evaluate(() => history.length);
    await page.keyboard.press('Control+k'); await settle(page);
    await page.keyboard.type('Go to Library'); await settle(page);
    await page.keyboard.press('Enter'); await settle(page, 400);
    expect(await where(page)).toMatchObject({ stage: 'jf-library-surface', address: LIBRARY });
    expect(await page.evaluate(() => history.length)).toBe(length + 1);
    await page.keyboard.press('Alt+ArrowLeft'); await settle(page);
    expect(await where(page)).toMatchObject({ stage: 'jf-frame-search-probe', address: SEARCH });
  }, 120_000);
});

describe('X1 forced colours', () => {
  it('the selected window tab is told apart from the others by a shape in a system colour', async () => {
    const page = await open();
    await area(page, LIBRARY);
    await page.emulateMedia({ forcedColors: 'active' });
    await settle(page);
    const look = (name: string) => page.getByRole('tab', { name }).evaluate(el => {
      const c = getComputedStyle(el);
      // The underline is drawn only when its colour differs from what is behind it.
      return { underline: c.borderBottomStyle !== 'none' && parseFloat(c.borderBottomWidth) >= 2
        && c.borderBottomColor !== c.backgroundColor && !/rgba\(.*, 0\)$/.test(c.borderBottomColor), colour: c.borderBottomColor };
    });
    const [selected, other] = [await look('Folders'), await look('Browse')];
    expect(selected.underline, JSON.stringify(selected)).toBe(true);
    expect(other.underline, JSON.stringify(other)).toBe(false);
    // Highlight is a system colour: the same value a forced-colours element gets for it.
    const highlight = await page.evaluate(() => {
      const probe = document.createElement('div');
      probe.style.color = 'Highlight';
      document.body.append(probe);
      const value = getComputedStyle(probe).color;
      probe.remove();
      return value;
    });
    expect(selected.colour).toBe(highlight);
  }, 120_000);
});
