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

declare global {
  interface Window { loadSettingsModule<T>(id: string): Promise<T> }
}

const SEARCH = 'core.search-v3-surface';
const LIBRARY = 'core.library-surface';
const BROWSE = 'core.browse-surface';
const BRAIN = 'core.brain-surface';
const SETTINGS = 'core.settings-surface';

/** The page: catalog, a stubbed backend and the real `<jf-shell>`; everything else is the app's own code. */
const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
// Exercise font metrics independently of the machine's default installed fonts.
if (new URLSearchParams(location.search).has('wideFont')) {
  document.body.style.fontFamily = '"DejaVu Sans", sans-serif';
}
const json = (body) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
// ?registryDelay=<ms> holds the boot's window-catalog response, as a busy engine does: the app
// keeps starting (Settings mounts ahead of its first opening) before the address is applied.
const registryDelay = Number(new URLSearchParams(location.search).get('registryDelay') ?? 0);
window.fetch = async (input) => {
  if (registryDelay && String(input).endsWith('/api/registry/surfaces')) await new Promise(r => setTimeout(r, registryDelay));
  return json({ entries: [], conversations: [], messages: [] });
};
window.loadSettingsModule = id => import(id);
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
  surface('${SEARCH}', new URLSearchParams(location.search).has('realSearch') ? 'jf-sv3-window' : 'jf-frame-search-probe'),
  surface('${LIBRARY}', 'jf-library-surface', { members: ['${BROWSE}'] }),
  surface('${BROWSE}', 'jf-browse-surface', { placement: 'DEEPLINK' }),
  surface('${BRAIN}', 'jf-brain-surface'),
  surface('core.system-surface', 'jf-system-surface'),
  surface('${SETTINGS}', 'jf-settings-surface', { placement: 'MODAL', members: ['core.security-surface', 'core.presentation-gallery-surface', 'core.presentation-editor-surface'] }),
  surface('core.security-surface', 'jf-security-surface', { placement: 'DEEPLINK' }),
  surface('core.presentation-gallery-surface', 'jf-presentation-gallery-surface', { placement: 'DEEPLINK' }),
  surface('core.presentation-editor-surface', 'jf-presentation-editor-surface', { placement: 'DEEPLINK' }),
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
  await page.goto(`${url}${query}${hash}`, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await page.locator('jf-top-bar').waitFor();
  if (query.includes('wideFont')) {
    await page.locator('jf-status-pill').evaluate(pill => {
      const style = document.createElement('style');
      style.textContent = '.beside-notice, .notice-below, .trigger.notice .label { font-family: "DejaVu Sans", sans-serif; letter-spacing: 3px; }';
      pill.shadowRoot!.append(style);
    });
  }
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

describe.each(['default', 'wide'])('L3 a notice for a window that does not exist is readable at every size (%s font)', (font) => {
  it.each([[1280, 800], [900, 700], [900, 800], [600, 400]])('%i×%i: the notice can be read, the pill is not cut and "Choose destination" is offered', async (width, height) => {
    const page = await open(width, height, font === 'wide' ? '?wideFont=1' : '');
    await page.evaluate(() => { location.hash = '#justsearch://surface/core.nope-window'; });
    await settle(page);
    expect(await readableNotices(page), `${width}×${height}`).not.toEqual([]);
    const layout = await page.evaluate(() => {
      const bar = document.querySelector('jf-shell')!.shadowRoot!.querySelector('jf-top-bar')!;
      const nav = bar.shadowRoot!.querySelector('nav')!.getBoundingClientRect();
      const root = bar.querySelector('jf-status-pill')!.shadowRoot!;
      const trigger = root.querySelector('.trigger')!;
      const pill = trigger.getBoundingClientRect();
      const text = [...root.querySelectorAll('.beside-notice, .notice-below, .trigger.notice .label')].find(el => {
        const rect = el.getBoundingClientRect();
        return rect.width > 0 && rect.height > 0;
      })!;
      const notice = text.getBoundingClientRect();
      const overlaps = (r: DOMRect) => notice.left < r.right && notice.right > r.left && notice.top < r.bottom && notice.bottom > r.top;
      return { centre: (nav.left + nav.right) / 2, half: innerWidth / 2, coversPill: !trigger.contains(text) && overlaps(pill),
        coversSwitcher: overlaps(nav), sideways: document.documentElement.scrollWidth > innerWidth };
    });
    expect(Math.abs(layout.centre - layout.half), JSON.stringify(layout)).toBeLessThanOrEqual(2);
    expect(layout).toMatchObject({ coversPill: false, coversSwitcher: false, sideways: false });
    expect(await pillCut(page)).toBe(false);
    expect(await page.getByRole('button', { name: 'Choose destination' }).isVisible()).toBe(true);
  }, 120_000);

  it.each(['vendor.nope-window', 'nope-window'])('C1 an unknown destination %s shows the same notice', async (id) => {
    const page = await open(1280, 800, font === 'wide' ? '?wideFont=1' : '');
    await page.evaluate((target) => { location.hash = `#justsearch://surface/${target}`; }, id);
    await settle(page);
    expect(await readableNotices(page), id).not.toEqual([]);
    expect(await page.getByRole('button', { name: 'Choose destination' }).isVisible()).toBe(true);
  }, 120_000);
});

describe('L2 the status panel never scrolls sideways', () => {
  it('a plugin item that renders a fixed 1000 px wide element stays inside its row, its words and provenance reachable', async () => {
    const page = await open();
    const words = 'Wide plugin: a fixed one-thousand-pixel element with a status line in it';
    // A string, so the test runner leaves the page's own module imports alone.
    await page.evaluate(`(async () => {
      const { registerStatusBarItem } = await import('/src/shell-v0/commands/StatusBarRegistry.ts');
      const { makePluginProvenance } = await import('/src/shell-v0/primitives/provenance.ts');
      registerStatusBarItem({ id: 'plugin.wide-element', source: 'plugin', provenance: makePluginProvenance('acme.wide', '1.0.0'),
        priority: 1, position: 'right',
        render: () => {
          const el = document.createElement('div');
          el.style.width = '1000px';
          el.style.minWidth = '1000px';
          el.textContent = ${JSON.stringify(words)};
          return el;
        } });
    })()`);
    await page.locator('jf-status-pill button.trigger').click();
    await settle(page, 300);
    const layout = await page.evaluate(() => {
      const panelRoot = document.querySelector('jf-shell')!.shadowRoot!.querySelector('jf-status-pill')!.shadowRoot!
        .querySelector('jf-status-panel')!.shadowRoot!;
      const panel = panelRoot.querySelector<HTMLElement>('.panel')!;
      const item = panelRoot.querySelector<HTMLElement>('[data-plugin-item="plugin.wide-element"]')!;
      const chip = item.querySelector('jf-provenance-chip')!;
      const box = panel.getBoundingClientRect();
      const inside = (r: DOMRect) => r.width > 0 && r.left >= box.left - 0.5 && r.right <= box.right + 0.5;
      const titled = [...item.querySelectorAll<HTMLElement>('[title]')].map(el => el.title);
      return { scrollWidth: panel.scrollWidth, clientWidth: panel.clientWidth, item: inside(item.getBoundingClientRect()),
        chip: inside(chip.getBoundingClientRect()), titled };
    });
    expect(layout.scrollWidth, JSON.stringify(layout)).toBeLessThanOrEqual(layout.clientWidth);
    expect(layout, JSON.stringify(layout)).toMatchObject({ item: true, chip: true });
    expect(layout.titled).toContain(words);
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

  it('with Settings open, Alt+Left is browser Back and Alt+Right browser Forward', async () => {
    const page = await open();
    await area(page, BRAIN);
    await openSettings(page);
    expect(await where(page)).toMatchObject({ settings: true, stage: 'jf-brain-surface' });
    await page.keyboard.press('Alt+ArrowLeft'); await settle(page, 400);
    expect(await where(page)).toMatchObject({ settings: false, stage: 'jf-brain-surface', address: BRAIN });
    await page.keyboard.press('Alt+ArrowRight'); await settle(page, 400);
    expect(await where(page)).toMatchObject({ settings: true, stage: 'jf-brain-surface' });
    await page.keyboard.press('Escape'); await settle(page, 400);
    expect(await where(page)).toMatchObject({ settings: false, stage: 'jf-brain-surface', address: BRAIN });
    await page.keyboard.press('Alt+ArrowLeft'); await settle(page, 400);
    expect(await where(page)).toMatchObject({ settings: false, stage: 'jf-frame-search-probe', address: SEARCH });
  }, 120_000);

  it('reload with Settings open keeps the area underneath; closing returns to it once', async () => {
    const page = await open();
    await area(page, LIBRARY);
    await openSettings(page);
    await page.reload();
    await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
    await settle(page, 800);
    expect(await where(page)).toMatchObject({ settings: true, stage: 'jf-library-surface' });
    await page.keyboard.press('Escape'); await settle(page, 400);
    expect(await where(page)).toMatchObject({ settings: false, stage: 'jf-library-surface', address: LIBRARY });
    await page.goBack(); await settle(page, 400);
    expect(await where(page)).toMatchObject({ settings: false, stage: 'jf-frame-search-probe', address: SEARCH });
  }, 120_000);
});

describe('FR3 the window under Settings shows its addressed tab', () => {
  it('reload with Settings open over Library › Browse shows Browse behind Settings from the start', async () => {
    const page = await open();
    await area(page, LIBRARY);
    await page.getByRole('tab', { name: 'Browse' }).click(); await settle(page);
    await openSettings(page);
    // Read through the DOM: the window behind an open modal is inert, out of the accessibility tree.
    const tabBehind = () => page.evaluate(() => {
      const find = (root: ParentNode): Element | null => {
        for (const el of root.querySelectorAll('*')) {
          if (el.localName === 'jf-library-surface') return el;
          const inner = el.shadowRoot && find(el.shadowRoot);
          if (inner) return inner;
        }
        return null;
      };
      const tabs = find(document)?.shadowRoot?.querySelector('jf-surface-tabs')?.shadowRoot;
      return tabs?.querySelector('[role="tab"][aria-selected="true"]')?.textContent?.trim() ?? null;
    });
    expect(await tabBehind()).toBe('Browse');
    await page.reload();
    await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
    await settle(page, 800);
    expect(await where(page)).toMatchObject({ settings: true, stage: 'jf-library-surface' });
    expect(await tabBehind()).toBe('Browse');
    await page.keyboard.press('Escape'); await settle(page, 400);
    expect([await libraryTab(page), (await where(page)).address]).toEqual(['Browse', `${LIBRARY}?tab=${BROWSE}`]);
  }, 120_000);
});

describe('FR3 the Settings section in the address wins over the one Settings remembers', () => {
  it('a legacy Settings › Developer link opens Advanced at View tier, and reload keeps it, while the engine is slow to answer', async () => {
    const page = await open(1280, 800, '?registryDelay=1500', `#justsearch://surface/${SETTINGS}?section=developer`);
    await settle(page, 2500);
    expect(await where(page)).toMatchObject({ settings: true, address: `${SETTINGS}?anchor=view-tier&section=advanced` });
    expect(await settingsSection(page)).toBe('advanced');
    await page.reload();
    await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
    await settle(page, 2500);
    expect(await where(page)).toMatchObject({ settings: true, address: `${SETTINGS}?anchor=view-tier&section=advanced` });
    expect(await settingsSection(page)).toBe('advanced');
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

  it.each(['light', 'dark'] as const)('the dark app theme on a %s system palette: every tab label is readable, the selected one too', async (scheme) => {
    const page = await open();
    await area(page, LIBRARY);
    await page.emulateMedia({ forcedColors: 'active', colorScheme: scheme });
    await settle(page);
    const system = (keyword: string) => page.evaluate((value) => {
      const probe = document.createElement('div');
      probe.style.color = value;
      document.body.append(probe);
      const resolved = getComputedStyle(probe).color;
      probe.remove();
      return resolved;
    }, keyword);
    const [text, canvas, highlight] = [await system('CanvasText'), await system('Canvas'), await system('Highlight')];
    expect(text).not.toBe(canvas);
    for (const [name, underline] of [['Folders', highlight], ['Browse', canvas]] as const) {
      const tab = page.getByRole('tab', { name });
      await tab.hover(); await settle(page);
      const look = await tab.evaluate(el => { const c = getComputedStyle(el); return { color: c.color, background: c.backgroundColor, underline: c.borderBottomColor }; });
      // Selected stays told apart while hovered: its underline is Highlight, the others' is the canvas.
      expect(look, `${scheme}: ${name}`).toEqual({ color: text, background: canvas, underline });
    }
  }, 120_000);
});

it('SG1 browser Ctrl+comma opens once from a composer and restores the editable control and caret', async () => {
  const page = await open();
  const composer = page.locator('jf-frame-search-probe textarea');
  await composer.fill('keep this draft');
  await composer.evaluate((el: HTMLTextAreaElement) => { el.focus(); el.setSelectionRange(5, 9); });
  const count = await page.evaluate(() => history.length);
  await page.keyboard.press('Control+,'); await settle(page);
  expect((await where(page)).settings).toBe(true);
  expect((await where(page)).stage).toBe('jf-frame-search-probe');
  expect(await composer.inputValue()).toBe('keep this draft');
  await page.locator('jf-settings-nav [data-settings-category="appearance"]').click();
  const address = (await where(page)).address;
  await page.keyboard.press('Control+,'); await settle(page);
  expect((await where(page)).address).toBe(address);
  expect(await page.evaluate(() => history.length)).toBe(count + 1);
  await page.keyboard.press('Escape'); await settle(page);
  expect((await where(page)).settings).toBe(false);
  expect(await composer.evaluate((el: HTMLTextAreaElement) => [el.matches(':focus'), el.selectionStart, el.selectionEnd])).toEqual([true, 5, 9]);
}, 30_000);

it('SG1 command guard blocks the palette and another modal', async () => {
  const page = await open();
  await page.keyboard.press('Control+k'); await settle(page);
  expect(await page.locator('jf-command-palette').evaluate((el: HTMLElement & { open: boolean }) => el.open)).toBe(true);
  const before = (await where(page)).address;
  await page.keyboard.press('Control+,'); await settle(page);
  expect((await where(page)).settings).toBe(false); expect((await where(page)).address).toBe(before);
  // Invoke the command directly too: the protection belongs to the command, not just its binding.
  await page.evaluate(async () => {
    const commands = await window.loadSettingsModule<typeof import('../commands/CommandRegistry.js')>('/src/shell-v0/commands/CommandRegistry.ts');
    await commands.invokeCommand('shell.go-to-settings');
  });
  expect((await where(page)).settings).toBe(false);
  await page.keyboard.press('Escape'); await settle(page);
  await page.evaluate(async () => {
    const { ModalityController } = await window.loadSettingsModule<typeof import('../primitives/modality.js')>('/src/shell-v0/primitives/modality.ts');
    const host = { addController() {}, removeController() {}, requestUpdate() {}, updateComplete: Promise.resolve(true) };
    const modal = new ModalityController(host); modal.enter();
    (window as unknown as { releaseSettingsGuard(): void }).releaseSettingsGuard = () => modal.exit();
  });
  await page.keyboard.press('Control+,'); await settle(page); expect((await where(page)).settings).toBe(false);
  await page.evaluate(() => (window as unknown as { releaseSettingsGuard(): void }).releaseSettingsGuard());
}, 30_000);

it('SG1 user and plugin chords outrank the Settings default, which opens after they are removed', async () => {
  const page = await open();
  for (const source of ['plugin', 'user'] as const) {
    await page.evaluate(async source => {
      const registry = await window.loadSettingsModule<typeof import('../commands/KeybindingRegistry.js')>('/src/shell-v0/commands/KeybindingRegistry.ts');
      const { makeCoreProvenance } = await window.loadSettingsModule<typeof import('../primitives/provenance.js')>('/src/shell-v0/primitives/provenance.ts');
      registry.registerKeybinding({ key: 'mod+,', commandId: 'shell.focus-composer', source, provenance: makeCoreProvenance() });
    }, source);
    await page.keyboard.press('Control+,'); await settle(page); expect((await where(page)).settings).toBe(false);
  }
  await page.evaluate(async () => {
    const registry = await window.loadSettingsModule<typeof import('../commands/KeybindingRegistry.js')>('/src/shell-v0/commands/KeybindingRegistry.ts');
    registry.unregisterKeybinding('mod+,', 'shell.focus-composer');
  });
  await page.keyboard.press('Control+,'); await settle(page);
  expect((await where(page)).settings).toBe(true);
}, 30_000);

it.each(['jf-top-bar .more-trigger', 'jf-status-pill .trigger'])('SG1 the Settings command dismisses %s and closes onto its surviving trigger', async selector => {
  const page = await open();
  await page.locator(selector).click(); await settle(page);
  await page.evaluate(async () => {
    const commands = await window.loadSettingsModule<typeof import('../commands/CommandRegistry.js')>('/src/shell-v0/commands/CommandRegistry.ts');
    await commands.invokeCommand('shell.go-to-settings');
  }); await settle(page);
  expect((await where(page)).settings).toBe(true);
  expect(await page.locator('jf-top-bar [role="menu"]').count()).toBe(0);
  expect(await page.locator('jf-status-pill').evaluate((el: HTMLElement & { open: boolean }) => el.open)).toBe(false);
  await page.keyboard.press('Escape'); await settle(page);
  expect(await page.locator(selector).evaluate(el => el.matches(':focus')), `return focus to ${selector}`).toBe(true);
}, 30_000);

it('SG3 SG4 an addressed entry survives underlying background projection, reload and a one-step close; section changes remove its anchor', async () => {
  const page = await open();
  await page.evaluate(async () => {
    const { setQuery } = await window.loadSettingsModule<typeof import('../state/searchState.js')>('/src/shell-v0/state/searchState.ts');
    setQuery('pending search');
    location.hash = 'justsearch://surface/core.settings-surface?section=layout&anchor=keyboard';
  });
  await settle(page, 300);
  expect((await where(page)).settings).toBe(true);
  expect((await where(page)).address).toBe(`${SETTINGS}?anchor=keyboard&section=general`);
  await page.evaluate(async () => {
    const { setQuery } = await window.loadSettingsModule<typeof import('../state/searchState.js')>('/src/shell-v0/state/searchState.ts'); setQuery('background update');
  });
  await settle(page, 300);
  expect((await where(page)).address).toBe(`${SETTINGS}?anchor=keyboard&section=general`);
  await page.reload(); await settle(page, 300);
  expect((await where(page)).address).toBe(`${SETTINGS}?anchor=keyboard&section=general`);
  await page.keyboard.press('Escape'); await settle(page); expect((await where(page)).settings).toBe(false);
  expect((await where(page)).stage).toBe('jf-frame-search-probe');
  await page.evaluate(() => { location.hash = 'justsearch://surface/core.settings-surface?section=appearance&anchor=theme'; });
  await settle(page);
  await page.locator('jf-settings-nav [data-settings-category="general"]').click(); await settle(page);
  expect((await where(page)).address).toBe(`${SETTINGS}?section=general`);
  await page.reload(); await settle(page, 300);
  expect((await where(page)).address).toBe(`${SETTINGS}?section=general`);
}, 30_000);

it('SG8 at 600x400 and 200 percent text every section remains reachable, and Escape cancels JSON import before closing', async () => {
  const page = await open(600, 400);
  await page.evaluate(() => { document.documentElement.style.fontSize = '200%'; });
  await openSettings(page);
  for (const id of ['general', 'appearance', 'security-privacy', 'data-reset', 'plugins', 'advanced', 'about']) {
    const row = page.locator(`jf-settings-nav [data-settings-category="${id}"]`);
    expect(await row.count(), `section ${id} is rendered`).toBe(1);
    await row.focus(); await page.keyboard.press('Enter'); await settle(page);
    expect(await row.getAttribute('aria-current')).toBe('true');
    expect(await row.evaluate(el => { const r = el.getBoundingClientRect(); return r.top >= 0 && r.bottom <= innerHeight; }), `${id}: ${await row.evaluate(el => JSON.stringify([el.getBoundingClientRect().top, el.getBoundingClientRect().bottom, (document.querySelector('jf-shell')!.shadowRoot!.querySelector('jf-settings-window')!.querySelector('jf-settings-surface') ?? document.body).localName]))}`).toBe(true);
  }
  await page.locator('jf-settings-nav [data-settings-category="appearance"]').focus(); await page.keyboard.press('Enter');
  await page.locator('jf-button[label="Import a theme from JSON"] button').focus(); await page.keyboard.press('Enter');
  await page.locator('textarea.theme-import-json').fill('unfinished draft');
  await page.keyboard.press('Escape'); await settle(page);
  expect(await page.locator('textarea.theme-import-json').count()).toBe(0);
  expect((await where(page)).settings).toBe(true);
  await page.keyboard.press('Escape'); await settle(page); expect((await where(page)).settings).toBe(false);
}, 30_000);

it('SG4 selecting another section removes the old Theme anchor before reload', async () => {
  const page = await open(1280, 800, '', `#justsearch://surface/${SETTINGS}?section=appearance&anchor=theme`);
  await page.locator('jf-settings-nav [data-settings-category="plugins"]').click(); await settle(page);
  expect((await where(page)).address).toBe(`${SETTINGS}?section=plugins`);
  await page.reload();
  await expect.poll(() => settingsSection(page), { timeout: 5000 }).toBe('plugins');
  expect((await where(page)).address).not.toContain('anchor=theme');
}, 30_000);

it('SG9 real Gallery and Editor keep sibling instances and editor drafts on apply and section return', async () => {
  const page = await open(); await openSettings(page);
  await page.locator('jf-settings-nav [data-settings-category="appearance"]').click();
  const editor = page.locator('jf-presentation-editor-surface'); const gallery = page.locator('jf-presentation-gallery-surface');
  await expect.poll(() => gallery.count(), { message: 'Appearance contains the Gallery alongside its native controls' }).toBe(1);
  await expect.poll(() => editor.count(), { message: 'Appearance contains the Editor alongside the Gallery' }).toBe(1);
  await editor.locator('textarea[aria-label="Presentation declaration JSON"]').fill('{"draft":"keep me"}');
  await editor.evaluate(el => { el.setAttribute('data-instance-witness', 'retained'); });
  await gallery.evaluate(el => { el.setAttribute('data-instance-witness', 'retained'); });
  await gallery.getByRole('button', { name: 'Apply', exact: true }).first().click(); await settle(page);
  expect(await editor.getAttribute('data-instance-witness')).toBe('retained');
  expect(await editor.locator('textarea[aria-label="Presentation declaration JSON"]').inputValue()).toBe('{"draft":"keep me"}');
  await page.locator('jf-settings-nav [data-settings-category="general"]').click(); await settle(page);
  expect(await editor.count()).toBe(0);
  await page.locator('jf-settings-nav [data-settings-category="appearance"]').click(); await settle(page);
  expect(await editor.getAttribute('data-instance-witness')).toBe('retained'); expect(await gallery.getAttribute('data-instance-witness')).toBe('retained');
  expect(await editor.locator('textarea[aria-label="Presentation declaration JSON"]').inputValue()).toBe('{"draft":"keep me"}');
}, 30_000);

it.each(['Escape', 'Back', 'Close'])('SG3 dismissal by %s preserves the real Search window current conversation and reload address', async (dismissal) => {
  const page = await open(1280, 720, '?realSearch=1');
  await page.locator('jf-sv3-window').waitFor();
  const claim = async (id: string): Promise<void> => {
    await page.evaluate(async (conversation) => {
      const { setActiveConversation } = await window.loadSettingsModule<typeof import('../state/conversationListStore.js')>('/src/shell-v0/state/conversationListStore.ts');
      setActiveConversation(conversation);
    }, id);
    await settle(page, 300);
  };
  const active = () => page.evaluate(async () => {
    const { getConversationListState } = await window.loadSettingsModule<typeof import('../state/conversationListStore.js')>('/src/shell-v0/state/conversationListStore.ts');
    return getConversationListState().activeId;
  });
  await claim('settings-conversation-A');
  expect((await where(page)).address).toContain('conversationId=settings-conversation-A');
  await openSettings(page);
  const settingsAddress = (await where(page)).address;
  await claim('settings-conversation-B');
  expect(await active()).toBe('settings-conversation-B');
  expect((await where(page)).address).toBe(settingsAddress);
  const length = await page.evaluate(() => history.length);
  if (dismissal === 'Back') await page.goBack();
  else if (dismissal === 'Close') await page.locator('jf-settings-window button.close').click();
  else await page.keyboard.press('Escape');
  await settle(page, 300);
  expect((await where(page)).settings).toBe(false);
  expect(await active()).toBe('settings-conversation-B');
  expect((await where(page)).address).toContain('conversationId=settings-conversation-B');
  expect((await where(page)).stage).toBe('jf-sv3-window');
  expect(await page.evaluate(() => history.length)).toBe(length);
  await page.goForward(); await settle(page);
  expect((await where(page)).settings).toBe(true);
  await page.keyboard.press('Escape'); await settle(page);
  expect((await where(page)).settings).toBe(false);
  expect(await active()).toBe('settings-conversation-B');
  await page.reload(); await settle(page, 400);
  expect(await active()).toBe('settings-conversation-B');
  expect((await where(page)).address).toContain('conversationId=settings-conversation-B');
  expect((await where(page)).settings).toBe(false);
}, 30_000);

it.each([
  ['core.security-surface', 'security-privacy'],
  ['core.presentation-gallery-surface', 'appearance'],
  ['core.presentation-editor-surface', 'appearance'],
])('SG4 member %s arrivals replace the address before and after Settings mounts without another history entry', async (member, section) => {
  const page = await open(1280, 720, '', `#justsearch://surface/${member}`);
  expect((await where(page)).address).toBe(`${SETTINGS}?anchor=${member}&section=${section}`);
  expect(await page.evaluate(() => history.length)).toBe(2);
  for (const mounted of [false, true]) {
    await page.keyboard.press('Escape'); await settle(page);
    if (mounted) await openSettings(page);
    const length = await page.evaluate(() => history.length);
    await page.evaluate((id) => { location.hash = `justsearch://surface/${id}`; }, member);
    await settle(page, 400);
    expect((await where(page)).address).toBe(`${SETTINGS}?anchor=${member}&section=${section}`);
    expect(await settingsSection(page)).toBe(section);
    expect(await page.evaluate(() => history.length)).toBe(length + 1);
    expect(await page.locator(`div[data-settings-anchor="${member}"]`).evaluate(el => (el.getRootNode() as ShadowRoot).activeElement === el)).toBe(true);
  }
}, 30_000);

/**
 * SG8 oracle, per section: every enabled control Tab reaches is in view once focused (its box meets
 * every clipping ancestor; one taller than that viewport shows its caret line), no visible button is
 * cut off sideways, and, in a short window, the Settings content has exactly one scroll container.
 */
function sg8Probe(root: Element): { controls: string[]; focused: number; visible: boolean; detail: string; sideways: string[]; scrollers: string[] } {
  const all: HTMLElement[] = [root as HTMLElement];
  const visit = (node: ParentNode): void => {
    for (const el of node.querySelectorAll<HTMLElement>('*')) { all.push(el); if (el.shadowRoot) visit(el.shadowRoot); }
  };
  visit(root);
  const up = (el: Element): Element | null => el.parentElement ?? ((el.getRootNode() as ShadowRoot).host ?? null);
  const pane = (root.getRootNode() as ShadowRoot).querySelector('.settings-content-pane')!;
  const inPane = (el: Element): boolean => { for (let p: Element | null = el; p; p = up(p)) if (p === pane) return true; return false; };
  const enabled = all.filter(el => inPane(el) && el.matches('button, input, select, textarea, a[href], [tabindex]') &&
    !(el instanceof HTMLInputElement && el.type === 'radio' && !el.checked) && el.tabIndex >= 0 &&
    !el.matches(':disabled') && el.getClientRects().length > 0 && getComputedStyle(el).visibility === 'visible');
  const sideways = all.filter(el => el.localName === 'button' && el.getClientRects().length > 0).flatMap(button => {
    const r = button.getBoundingClientRect();
    let cut = r.left < -1 || r.right > innerWidth + 1 ||
      (getComputedStyle(button).overflowX !== 'visible' && button.scrollWidth > button.clientWidth + 1);
    for (let p = up(button); p && !cut; p = up(p)) {
      const b = p.getBoundingClientRect();
      if (getComputedStyle(p).overflowX !== 'visible' && (r.left < b.left - 1 || r.right > b.right + 1)) cut = true;
    }
    const host = (button.getRootNode() as ShadowRoot).host;
    return cut ? [`${(host ?? button).textContent?.trim().replace(/\s+/g, ' ').slice(0, 60)} ${JSON.stringify([r.left, r.right])}`] : [];
  });
  const scrollers = all.filter(el => !el.matches('textarea, input, select') && ['auto', 'scroll'].includes(getComputedStyle(el).overflowY) &&
    el.scrollHeight > el.clientHeight + 1).map(el => `${el.localName}.${el.className}`);
  let el = document.activeElement as HTMLElement | null;
  while (el?.shadowRoot?.activeElement) el = el.shadowRoot.activeElement as HTMLElement;
  const focused = el ? enabled.indexOf(el) : -1;
  let visible = false; let detail = '';
  if (el && focused >= 0) {
    const r = el.getBoundingClientRect();
    let top = 0; let bottom = innerHeight; let left = 0; let right = innerWidth;
    for (let p = up(el); p; p = up(p)) {
      const style = getComputedStyle(p); const b = p.getBoundingClientRect();
      if (style.overflowY !== 'visible') { top = Math.max(top, b.top); bottom = Math.min(bottom, b.bottom); }
      if (style.overflowX !== 'visible') { left = Math.max(left, b.left); right = Math.min(right, b.right); }
    }
    let caretTop = r.top; let caretBottom = r.bottom;
    if (el instanceof HTMLTextAreaElement && r.height > bottom - top) {
      // The caret line: lay the text before the caret out the way the textarea does.
      const style = getComputedStyle(el); const mirror = document.createElement('div');
      for (const key of ['font', 'letterSpacing', 'lineHeight', 'padding', 'border', 'boxSizing', 'width', 'tabSize'] as const) mirror.style[key] = style[key];
      Object.assign(mirror.style, { position: 'absolute', visibility: 'hidden', whiteSpace: 'pre-wrap', overflowWrap: 'break-word' });
      mirror.textContent = el.value.slice(0, el.selectionStart ?? 0);
      const caret = document.createElement('span'); caret.textContent = '\u200b'; mirror.append(caret);
      document.body.append(mirror);
      caretTop = r.top + caret.offsetTop - el.scrollTop; caretBottom = caretTop + caret.offsetHeight;
      mirror.remove();
    }
    const meets = Math.min(r.bottom, bottom) - Math.max(r.top, top) > 0 && Math.min(r.right, right) - Math.max(r.left, left) > 0;
    visible = meets && (r.height <= bottom - top || (caretTop >= top - 1 && caretBottom <= bottom + 1));
    detail = JSON.stringify({ el: el.outerHTML.slice(0, 160), rect: [r.top, r.bottom, r.left, r.right], caret: [caretTop, caretBottom], viewport: [top, bottom, left, right] });
  }
  return { controls: enabled.map(c => c.outerHTML.slice(0, 120)), focused, visible, detail, sideways, scrollers };
}

it.each([[600, 400, 200], [1280, 720, 100]])('SG8 at %ix%i and %i percent text every section and enabled control is in view on keyboard focus, no button is clipped sideways, one scroller when short', async (width, height, text) => {
  const page = await open(width, height);
  await page.evaluate((size) => { document.documentElement.style.fontSize = `${size}%`; }, text);
  await openSettings(page);
  await page.addScriptTag({ content: `window.sg8Probe = ${sg8Probe.toString()}` });
  const probe = () => page.locator('jf-settings-surface .settings-root').evaluate(root => (window as unknown as { sg8Probe: typeof sg8Probe }).sg8Probe(root));
  for (const section of ['general', 'appearance', 'security-privacy', 'data-reset', 'plugins', 'advanced', 'about']) {
    const row = page.locator(`jf-settings-nav [data-settings-category="${section}"]`);
    await row.focus(); await page.keyboard.press('Enter'); await settle(page);
    expect(await row.getAttribute('aria-current')).toBe('true');
    const layout = await probe();
    const reached = new Set<number>();
    for (let step = 0; step < layout.controls.length + 20 && reached.size < layout.controls.length; step++) {
      await page.keyboard.press('Tab'); await settle(page, 50);
      const focus = await probe();
      if (focus.focused < 0) continue;
      expect(focus.visible, `${section}: ${focus.detail}`).toBe(true);
      reached.add(focus.focused);
    }
    expect(layout.controls.filter((_, index) => !reached.has(index)), `${section}: enabled controls not reached by Tab`).toEqual([]);
    expect(layout.sideways, `${section}: buttons cut off sideways`).toEqual([]);
    if (height === 400) expect(layout.scrollers, `${section}: Settings content scroll containers`).toHaveLength(1);
  }
}, 90_000);
