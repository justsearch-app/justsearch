// SPDX-License-Identifier: Apache-2.0
// S5c X1 X2 SC9 — the engine-loss treatment in real Chromium: the real Shell, top bar, status pill,
// status store and Health over a private Vite server with a stubbed backend; Search is a probe
// window with a text box. The engine is lost by refusing every request and letting the page's own
// clock run past the contact window and the pill's grace. Run separately with
// npx vitest run --config vitest.config.lockdown.ts src/shell-v0/chrome/Shell.engineLoss-lockdown.test.ts
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
const json = body => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
window.engineDown = false;
window.sent = [];
window.fetch = async (input, init) => {
  const url = String(input?.url ?? input);
  if ((init?.method ?? 'GET') !== 'GET') window.sent.push(url);
  if (url.includes('/api/registry/surfaces')) return json({ entries: [] });
  if (window.engineDown) throw new TypeError('Failed to fetch');
  return json({ entries: [] });
};
window.EventSource = class { constructor() { this.readyState = 0; } addEventListener() {} removeEventListener() {} close() {} };
const { __seedForTest } = await import('/src/api/registry/SurfaceCatalogClient.ts');
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/chrome/Shell.ts');
await import('/src/shell-v0/views/SystemSurface.ts');
await import('/src/shell-v0/views/HealthSurface.ts');
customElements.define('jf-loss-search-probe', class extends HTMLElement {
  connectedCallback() { if (!this.shadowRoot) this.attachShadow({ mode: 'open' }).innerHTML = '<textarea aria-label="Ask or search"></textarea>'; }
});
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL', presentation: { labelKey: id, descriptionKey: id },
  consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] }, provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('core.search-v3-surface', 'jf-loss-search-probe'),
  surface('core.system-surface', 'jf-system-surface', { members: ['core.health-surface'] }),
  surface('core.health-surface', 'jf-health-surface', { placement: 'DEEPLINK' }),
  surface('core.settings-surface', 'jf-loss-search-probe', { placement: 'MODAL' }),
] });
window.ai = await import('/src/shell-v0/state/aiStateStore.ts');
document.body.append(document.createElement('jf-shell'));
document.body.dataset.ready = 'true';
</script></body></html>`;

type LossWindow = Window & { engineDown: boolean; sent: string[]; ai: { __feedContactForTest(): void } };
let server: ViteDevServer;
let browser: Browser;
let browserServer: BrowserServer;
let context: BrowserContext | undefined;
let output: string;
let url: string;

beforeAll(async () => {
  output = await mkdtemp(path.join(process.env.TMPDIR ?? tmpdir(), 'engine-loss-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: path.join(output, 'vite'), publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'engine-loss', configureServer(vite) {
      vite.middlewares.use((req, res, next) => { if (req.url?.split('?')[0] !== '/app') return next();
        res.setHeader('Content-Type', 'text/html'); res.end(HARNESS); });
    } }] });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/app`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: process.env.TMPDIR ?? output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned engine-loss Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
}, 60_000);
afterEach(async () => { await context?.close(); context = undefined; });
afterAll(async () => { await browser?.close(); await browserServer?.close(); await server?.close(); if (output) await rm(output, { recursive: true, force: true }); });

async function settle(page: Page): Promise<void> {
  await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
}

async function open(viewport = { width: 1280, height: 800 }): Promise<Page> {
  context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
  const page = await context.newPage();
  await page.clock.install();
  page.on('pageerror', error => console.info('engine-loss pageerror:', error.message));
  await page.goto(url);
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 60_000 });
  await page.locator('jf-top-bar').waitFor({ timeout: 60_000 });
  await page.evaluate(() => (window as unknown as LossWindow).ai.__feedContactForTest());
  await page.clock.runFor(1_000);
  await settle(page);
  return page;
}

async function theme(page: Page, palette: 'dark' | 'light' | 'forced', textScale = 1): Promise<void> {
  await page.emulateMedia({ colorScheme: palette === 'light' ? 'light' : 'dark', forcedColors: palette === 'forced' ? 'active' : 'none' });
  await page.evaluate(([mode, scale]) => {
    document.documentElement.setAttribute('data-theme', mode as string);
    document.documentElement.style.fontSize = `${Number(scale) * 100}%`;
  }, [palette === 'light' ? 'light' : 'dark', textScale] as const);
  await settle(page);
}

/** Lose the engine and let the page's clock pass the contact window and the pill's grace. */
async function outage(page: Page): Promise<void> {
  await page.evaluate(() => { (window as unknown as LossWindow).engineDown = true; });
  await page.clock.runFor(60_000);
  await settle(page);
}

async function restore(page: Page): Promise<void> {
  await page.evaluate(() => { const w = window as unknown as LossWindow; w.engineDown = false; w.ai.__feedContactForTest(); });
  await page.clock.runFor(2_000);
  await settle(page);
}

const section = (page: Page) => page.locator('jf-engine-loss [data-testid="engine-loss"]');

/** The treatment is on screen (asserted, so its absence fails as an assertion). */
async function shown(page: Page): Promise<void> {
  await section(page).waitFor({ state: 'visible', timeout: 10_000 }).catch(() => undefined);
  expect(await section(page).isVisible(), 'the treatment shows').toBe(true);
}

/** The deepest focused element, described by its role, name or text. */
const focused = (page: Page) => page.evaluate(() => {
  let el = document.activeElement;
  while (el?.shadowRoot?.activeElement) el = el.shadowRoot.activeElement;
  // A jf-button's name is its slotted text, on the host above the focused native button.
  let named: Element | null | undefined = el;
  while (named && named.localName !== 'jf-button' && named !== document.documentElement) {
    named = named.parentElement ?? ((named.getRootNode() as ShadowRoot).host ?? null);
  }
  return { tag: el?.localName ?? '', testid: el?.getAttribute('data-testid') ?? '', label: el?.getAttribute('aria-label') ?? '',
    text: ((named?.localName === 'jf-button' ? named : el)?.textContent ?? '').replace(/\s+/g, ' ').trim(), outline: el ? getComputedStyle(el).outlineStyle : '',
    outlineWidth: el ? parseFloat(getComputedStyle(el).outlineWidth) : 0, visible: el?.matches(':focus-visible') ?? false };
});

/** Ink against what is painted behind it, composed through a canvas, for each text of the treatment. */
const contrasts = (page: Page) => section(page).evaluate(root => {
  const canvas = document.createElement('canvas');
  canvas.width = 1; canvas.height = 1;
  const paint = canvas.getContext('2d', { willReadFrequently: true })!;
  const rgb = (): number[] => [...paint.getImageData(0, 0, 1, 1).data.slice(0, 3)];
  const luminance = (colour: number[]): number => {
    const [r, g, b] = colour.map(value => { const c = value / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; });
    return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!;
  };
  const up = (at: Element): Element | null => at.parentElement ?? ((at.getRootNode() as ShadowRoot).host ?? null);
  const measure = (el: Element): number => {
    const fills: string[] = [];
    for (let at: Element | null = el; at; at = up(at)) {
      const fill = getComputedStyle(at).backgroundColor;
      if (fill !== 'rgba(0, 0, 0, 0)' && fill !== 'transparent') fills.unshift(fill);
      if (at === document.documentElement) break;
    }
    paint.clearRect(0, 0, 1, 1);
    for (const fill of fills) { paint.fillStyle = fill; paint.fillRect(0, 0, 1, 1); }
    const fill = rgb();
    paint.fillStyle = getComputedStyle(el).color; paint.fillRect(0, 0, 1, 1);
    const ink = rgb();
    const [hi, lo] = [luminance(fill), luminance(ink)].sort((a, b) => b - a);
    return (hi! + 0.05) / (lo! + 0.05);
  };
  const texts: { text: string; ratio: number }[] = [];
  const visit = (scope: ParentNode): void => {
    for (const el of scope.querySelectorAll('*')) {
      if (el.shadowRoot) visit(el.shadowRoot);
      const own = [...el.childNodes].filter(n => n.nodeType === Node.TEXT_NODE).map(n => n.textContent!.trim()).join(' ').trim();
      if (own && (el as HTMLElement).offsetParent !== null) texts.push({ text: own.slice(0, 40), ratio: measure(el) });
    }
  };
  visit(root);
  return texts;
});

/** Elements of the treatment or top bar that leave the window sideways, and overlapping actions. */
const layout = (page: Page) => page.evaluate(() => {
  const shell = document.querySelector('jf-shell')!.shadowRoot!;
  const outside: string[] = [];
  const walk = (scope: ParentNode) => {
    for (const node of scope.querySelectorAll<HTMLElement>('*')) {
      const rect = node.getBoundingClientRect();
      if (rect.width > 0 && (rect.left < -1 || rect.right > innerWidth + 1)) outside.push(`${node.localName}.${node.className}`);
      if (node.shadowRoot) walk(node.shadowRoot);
    }
  };
  walk(shell.querySelector('jf-engine-loss')!.shadowRoot!);
  walk(shell.querySelector('jf-top-bar')!.shadowRoot!);
  const buttons = [...shell.querySelector('jf-engine-loss')!.shadowRoot!.querySelectorAll('jf-button')].map(b => b.getBoundingClientRect());
  const overlaps = buttons.some((a, i) => buttons.some((b, j) => j > i && a.left < b.right - 1 && b.left < a.right - 1 && a.top < b.bottom - 1 && b.top < a.bottom - 1));
  const gear = shell.querySelector('jf-top-bar')!.shadowRoot!.querySelector<HTMLElement>('[data-surface-id="core.settings-surface"]');
  const box = gear?.getBoundingClientRect();
  let hit: Element | null = box ? document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2) : null;
  while (hit?.shadowRoot) {
    const inner = box ? hit.shadowRoot.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2) : null;
    if (!inner || inner === hit) break;
    hit = inner;
  }
  let inBar = false;
  for (let at: Node | null = hit; at; at = (at as Element).parentElement ?? ((at.getRootNode() as ShadowRoot).host ?? null)) {
    if ((at as Element).localName === 'jf-top-bar') { inBar = true; break; }
    if (at === document.documentElement) break;
  }
  return { outside, overlaps, sideways: document.scrollingElement!.scrollWidth > innerWidth, settingsReachable: inBar };
});

describe('X1 X2 SC9 the treatment at every size and palette', { timeout: 120_000 }, () => {
  const cases = [
    ...([[1280, 800], [900, 800], [600, 400]] as const).flatMap(([width, height]) =>
      (['dark', 'light', 'forced'] as const).map(palette => ({ width, height, palette, scale: 1 }))),
    { width: 600, height: 400, palette: 'dark' as const, scale: 2 },
    { width: 900, height: 800, palette: 'light' as const, scale: 2 },
  ];
  it.each(cases)('$width x $height, $palette, text x$scale: readable, inside the window, focus moved in, top bar usable', async ({ width, height, palette, scale }) => {
    const page = await open({ width, height });
    await theme(page, palette, scale);
    await outage(page);
    await shown(page);
    expect(await focused(page), 'focus moved into the treatment').toMatchObject({ testid: 'engine-loss' });
    const placed = await layout(page);
    expect(placed.outside, 'nothing leaves the window sideways').toEqual([]);
    expect(placed.sideways).toBe(false);
    expect(placed.overlaps, 'the actions do not overlap').toBe(false);
    expect(placed.settingsReachable, 'Settings in the top bar is not covered').toBe(true);
    for (const { text, ratio } of await contrasts(page)) expect(ratio, `contrast of "${text}"`).toBeGreaterThanOrEqual(4.5);
    // Keyboard: every action is reached in order with a visible focus ring.
    for (const name of ['Try again', 'Open Health', 'Copy report']) {
      await page.keyboard.press('Tab');
      const now = await focused(page);
      expect(now.text, `Tab reaches ${name}`).toBe(name);
      expect(now.visible && now.outline !== 'none' && now.outlineWidth > 0, `${name} shows its focus ring`).toBe(true);
      const box = await page.evaluate(() => { let el = document.activeElement; while (el?.shadowRoot?.activeElement) el = el.shadowRoot.activeElement;
        const r = el!.getBoundingClientRect(); return { left: r.left, right: r.right }; });
      expect(box.left >= 0 && box.right <= width, `${name} is inside the window`).toBe(true);
    }
    expect(await section(page).evaluate(el => el.getAnimations({ subtree: true }).length), 'no motion').toBe(0);
  });

  it('adds no axe violation, in both themes and forced colours', async () => {
    const page = await open();
    await outage(page);
    await shown(page);
    await page.addScriptTag({ path: path.resolve('node_modules/axe-core/axe.min.js') });
    for (const palette of ['dark', 'light', 'forced'] as const) {
      await theme(page, palette);
      const found = await page.evaluate(async () => {
        const axe = (window as unknown as { axe: { run(context: unknown, options: unknown): Promise<{ violations: { id: string; nodes: { target: unknown[] }[] }[] }> } }).axe;
        const result = await axe.run({ include: [['jf-shell', 'jf-engine-loss']] }, { resultTypes: ['violations'] });
        return result.violations.map(v => v.id);
      });
      expect(found, palette).toEqual([]);
    }
  });
});

describe('SC9 SC6 focus and leaving in a real browser', { timeout: 120_000 }, () => {
  it('focus returns to the text box when the engine is back, the draft is kept, nothing is sent, twice', async () => {
    const page = await open();
    const box = page.getByRole('textbox', { name: 'Ask or search' });
    await box.fill('heating repairs');
    await box.focus();
    for (const round of [1, 2]) {
      await outage(page);
      await shown(page);
      expect(await focused(page), `round ${round}: in`).toMatchObject({ testid: 'engine-loss' });
      // The dimmed window takes no pointer or keyboard input.
      await page.mouse.click(640, 700);
      expect(await focused(page), `round ${round}: a click on the dimmed window does not reach it`).not.toMatchObject({ label: 'Ask or search' });
      await restore(page);
      expect(await section(page).count(), `round ${round}: gone`).toBe(0);
      expect(await focused(page), `round ${round}: back`).toMatchObject({ label: 'Ask or search' });
      expect(await box.inputValue()).toBe('heating repairs');
    }
    // The app's own background reports (activity heartbeat, action ledger) are not requests the
    // person made; nothing else is sent.
    const sent = await page.evaluate(() => (window as unknown as LossWindow).sent);
    expect(sent.filter(u => !/\/api\/(ui\/activity|action-ledger\/events)/.test(u)), 'nothing resubmitted').toEqual([]);
  });

  it('Open Health at 600x400 steps aside to a band; Health is readable and takes input', async () => {
    const page = await open({ width: 600, height: 400 });
    await outage(page);
    await shown(page);
    await page.getByRole('button', { name: 'Open Health', exact: true }).click();
    await page.clock.runFor(1_000);
    await settle(page);
    await page.locator('[data-testid="health-last-known"]').waitFor({ state: 'visible' });
    const placed = await layout(page);
    expect(placed.outside).toEqual([]);
    expect(placed.sideways).toBe(false);
    expect(await page.locator('jf-shell').evaluate(shell => shell.shadowRoot!.querySelector<HTMLElement>('.stage')!.inert)).toBe(false);
    expect(await page.locator('jf-engine-loss').getAttribute('mode')).toBe('band');
    expect(await page.getByText('Nothing here is live.', { exact: false }).isVisible()).toBe(true);
  });
});
