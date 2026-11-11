// SPDX-License-Identifier: Apache-2.0
// Shell S6 SP8/SP9/SP10c: the palette over the real Shell's chrome, in real Chromium. The page is the
// real `<jf-shell>` (top bar, status pill, overlay host, palette, peek) over a stubbed backend; the
// areas are probe windows with ordinary controls, so the palette's own behaviour is what is measured.
// Run: npx vitest run --config vitest.config.lockdown.ts src/shell-v0/commands/CommandPalette.modal-lockdown.test.ts
// Requires the same installed Chromium as Shell.frame-lockdown.test.ts; no Engine is needed.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import path from 'node:path';

const HARNESS = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"></head><body><script type="module">
const json = (body) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
window.fetch = async () => json({ entries: [], conversations: [], messages: [], sessions: [], items: [] });
window.EventSource = class { constructor() { this.readyState = 0; } addEventListener() {} removeEventListener() {} close() {} };
const { __seedForTest } = await import('/src/api/registry/SurfaceCatalogClient.ts');
await import('/src/shell-v0/index.ts');
await import('/src/shell-v0/chrome/Shell.ts');
/** An area with a text box and a button: the background a modal palette must keep out of reach. */
for (const tag of ['jf-modal-search-probe', 'jf-modal-library-probe', 'jf-modal-settings-probe']) {
  customElements.define(tag, class extends HTMLElement {
    connectedCallback() {
      if (this.shadowRoot) return;
      this.attachShadow({ mode: 'open' }).innerHTML = '<textarea aria-label="Ask or search"></textarea><button type="button">Background</button>';
      this.shadowRoot.querySelector('button').addEventListener('click', () => { window.backgroundClicks = (window.backgroundClicks ?? 0) + 1; });
    }
  });
}
const surface = (id, mountTag, extra = {}) => ({ id, mountTag, audience: 'USER', placement: 'RAIL',
  presentation: { labelKey: id, descriptionKey: id }, consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra });
__seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
  surface('core.search-v3-surface', 'jf-modal-search-probe'),
  surface('core.library-surface', 'jf-modal-library-probe'),
  surface('core.settings-surface', 'jf-modal-settings-probe', { placement: 'MODAL' }),
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
  output = await mkdtemp(path.join(temporaryRoot, 'palette-modal-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'palette-modal-harness',
      configureServer(vite) {
        vite.middlewares.use((req, res, next) => {
          if (req.url?.split('?')[0] !== '/palette-modal') return next();
          res.setHeader('Content-Type', 'text/html');
          res.end(HARNESS);
        });
      },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/palette-modal`;
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

type Theme = 'light' | 'dark' | 'forced-colors';

async function open(theme: Theme, width: number, height: number, text: string): Promise<Page> {
  context = await browser.newContext({ viewport: { width, height } });
  const page = await context.newPage();
  page.on('pageerror', error => console.info('pageerror', error.message));
  await page.emulateMedia({ colorScheme: theme === 'light' ? 'light' : 'dark', forcedColors: theme === 'forced-colors' ? 'active' : 'none' });
  await page.goto(url, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await page.locator('jf-top-bar').waitFor();
  await page.locator('jf-status-pill .trigger').waitFor();
  await page.evaluate(([value, size]) => {
    document.documentElement.dataset.theme = value!;
    document.documentElement.style.fontSize = size!;
  }, [theme === 'light' ? 'light' : 'dark', text]);
  await settle(page);
  return page;
}

async function settle(page: Page, ms = 150): Promise<void> {
  await page.waitForTimeout(ms);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

/** Open the palette from the background button with the real chord. */
async function openPalette(page: Page): Promise<void> {
  await page.locator('jf-modal-search-probe button').focus();
  await page.keyboard.press('Control+k');
  await page.locator('jf-command-palette input[role="combobox"]').waitFor({ state: 'visible' });
  await settle(page);
}

/** What the palette's field shares with the status trigger, the deepest element painted there, and focus. */
const probe = (page: Page) => page.evaluate(() => {
  const deepAt = (x: number, y: number): Element | null => {
    let root: Document | ShadowRoot = document;
    let hit: Element | null = null;
    for (;;) {
      const next: Element | null = root.elementFromPoint(x, y);
      if (!next || next === hit) return hit;
      hit = next;
      if (!next.shadowRoot) return hit;
      root = next.shadowRoot;
    }
  };
  const deepActive = (): Element | null => {
    let active = document.activeElement;
    while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
    return active;
  };
  const shell = document.querySelector('jf-shell')!.shadowRoot!;
  const palette = shell.querySelector('jf-command-palette')!;
  const field = palette.shadowRoot!.querySelector('input[role="combobox"]');
  if (!field) return { open: (palette as HTMLElement & { open: boolean }).open, overlaps: false, point: { x: 0, y: 0 }, hitIsField: false, hit: 'nothing',
    fieldFocused: false, focusInPalette: false, active: 'nothing', value: '', actions: false };
  const trigger = shell.querySelector('jf-status-pill')!.shadowRoot!.querySelector('.trigger')!;
  const f = field.getBoundingClientRect();
  const t = trigger.getBoundingClientRect();
  const overlap = { left: Math.max(f.left, t.left), right: Math.min(f.right, t.right), top: Math.max(f.top, t.top), bottom: Math.min(f.bottom, t.bottom) };
  const overlaps = overlap.right - overlap.left > 2 && overlap.bottom - overlap.top > 2;
  // Where the trigger paints over the field, if it does; otherwise the field's own centre.
  const point = overlaps
    ? { x: (overlap.left + overlap.right) / 2, y: (overlap.top + overlap.bottom) / 2 }
    : { x: (f.left + f.right) / 2, y: (f.top + f.bottom) / 2 };
  const hit = deepAt(point.x, point.y);
  const describe = (el: Element | null) => el ? `${el.localName}${el.className ? '.' + String(el.className).replace(/ /g, '.') : ''}` : 'nothing';
  return { overlaps, point, hitIsField: hit === field, hit: describe(hit), fieldFocused: deepActive() === field,
    focusInPalette: palette.shadowRoot!.contains(deepActive()), active: describe(deepActive()), value: (field as HTMLInputElement).value,
    open: (palette as HTMLElement & { open: boolean }).open, actions: !!palette.shadowRoot!.querySelector('#palette-actions') };
});

const CASES: ReadonlyArray<[Theme, number, number, string]> = [
  ['light', 600, 400, '200%'], ['dark', 600, 400, '200%'], ['forced-colors', 600, 400, '200%'],
  ['light', 1280, 720, '100%'], ['dark', 1280, 720, '100%'], ['forced-colors', 1280, 720, '100%'],
];

it.each(CASES)('SP8 the palette paints above all chrome and keeps the page behind out of reach (%s, %ix%i, text %s)', async (theme, width, height, text) => {
  const page = await open(theme, width, height, text);
  await openPalette(page);
  const where = `${theme}, ${width}x${height}, text ${text}`;
  const at = await probe(page);
  // At the smallest size the status trigger lies under the field: this is the case the check is for.
  if (width === 600) expect(at.overlaps, `the status trigger shares the field's area: ${where}`).toBe(true);

  // A click there focuses the field and typing arrives in it.
  await page.mouse.click(at.point.x, at.point.y);
  await page.keyboard.type('lib');
  const typed = await probe(page);
  expect(typed.fieldFocused, `focus after the click at ${JSON.stringify(at.point)}: ${typed.active} (${where})`).toBe(true);
  expect(typed.value, where).toBe('lib');
  // And the field is what is painted there.
  const painted = await probe(page);
  expect(painted.hit, `painted at the field's point ${JSON.stringify(painted.point)}: ${where}`).toBe('input');
  expect(painted.hitIsField, `the field is what is painted there: ${where}`).toBe(true);

  // Tab and Shift Tab never reach the page behind; Tab on a result shows its actions, Escape goes back first.
  for (const chord of ['Tab', 'Shift+Tab', 'Tab', 'Tab', 'Shift+Tab', 'Shift+Tab']) {
    await page.keyboard.press(chord);
    const now = await probe(page);
    expect(now.focusInPalette, `focus after ${chord}: ${now.active} (${where})`).toBe(true);
  }
  await page.keyboard.press('Tab'); await settle(page, 50);
  expect((await probe(page)).actions, `Tab shows the result's actions: ${where}`).toBe(true);
  await page.keyboard.press('Escape'); await settle(page, 50);
  const back = await probe(page);
  expect([back.open, back.actions, back.value], `the first Escape returns to the results: ${where}`).toEqual([true, false, 'lib']);

  // Pointer: a click on a background control outside the palette reaches nothing behind it.
  const outside = await page.evaluate(() => {
    const shell = document.querySelector('jf-shell')!.shadowRoot!;
    const panel = shell.querySelector('jf-command-palette')!.shadowRoot!.querySelector('input')!.closest('dialog, [role="dialog"]')!.getBoundingClientRect();
    const button = shell.querySelector('jf-stage')!.shadowRoot!.querySelector('jf-modal-search-probe')!.shadowRoot!.querySelector('button')!.getBoundingClientRect();
    const inside = (x: number, y: number) => x >= panel.left && x <= panel.right && y >= panel.top && y <= panel.bottom;
    const x = button.left + 4, y = button.top + button.height / 2;
    return inside(x, y) ? null : { x, y };
  });
  if (outside) {
    await page.mouse.click(outside.x, outside.y);
    expect(await page.evaluate(() => (window as unknown as { backgroundClicks?: number }).backgroundClicks ?? 0), `background clicks: ${where}`).toBe(0);
  }
}, 60_000);

it('SP9 the second Escape closes the palette and gives focus back to its invoker; nothing beneath hears either', async () => {
  const page = await open('dark', 1280, 720, '100%');
  await page.evaluate(() => { document.addEventListener('keydown', e => { if (e.key === 'Escape') (window as unknown as { heard: number }).heard = ((window as unknown as { heard?: number }).heard ?? 0) + 1; }); });
  await openPalette(page);
  await page.keyboard.type('lib');
  await page.keyboard.press('Tab'); await settle(page, 50);
  await page.keyboard.press('Escape'); await settle(page, 50);
  expect((await probe(page)).open).toBe(true);
  await page.keyboard.press('Escape'); await settle(page, 50);
  expect((await probe(page)).open).toBe(false);
  expect(await page.evaluate(() => (window as unknown as { heard?: number }).heard ?? 0)).toBe(0);
  expect(await page.locator('jf-modal-search-probe button').evaluate(el => el.matches(':focus'))).toBe(true);
}, 60_000);

it('SP10c the Alt peek paints above the open palette where they overlap, and ends with Alt release', async () => {
  // At 900x500 the centred peek overlaps the palette's lower part.
  const page = await open('dark', 900, 500, '100%');
  await openPalette(page);
  await page.keyboard.type('Go to Library');
  const row = page.locator('jf-command-palette [role="option"]').filter({ hasText: 'Library' }).first();
  await row.waitFor();
  const box = (await row.boundingBox())!;
  await page.keyboard.down('Alt');
  await page.mouse.move(box.x + 10, box.y + box.height / 2);
  await page.mouse.move(box.x + 12, box.y + box.height / 2);
  await settle(page);
  // The peek previews the Library probe: its white text box, painted where the palette also is.
  const where = await page.evaluate(() => {
    const shell = document.querySelector('jf-shell')!.shadowRoot!;
    const panel = shell.querySelector('jf-peek')!.shadowRoot!.querySelector('.panel')!;
    const dialog = shell.querySelector('jf-command-palette')!.shadowRoot!.querySelector('input')!.closest('dialog, [role="dialog"]')!.getBoundingClientRect();
    const field = panel.querySelector('jf-modal-library-probe')!.shadowRoot!.querySelector('textarea')!.getBoundingClientRect();
    const x = Math.round(field.left + field.width / 2), y = Math.round(field.top + Math.min(field.height / 2, dialog.bottom - field.top - 3));
    return { open: panel.matches(':popover-open'), x, y, overPalette: x > dialog.left && x < dialog.right && y > dialog.top && y < dialog.bottom };
  });
  expect(where.open, 'the peek is open').toBe(true);
  expect(where.overPalette, `the sampled point lies over the palette: ${JSON.stringify(where)}`).toBe(true);
  const shot = (await page.screenshot({ clip: { x: where.x, y: where.y, width: 1, height: 1 } })).toString('base64');
  const pixel = await page.evaluate(async png => {
    const image = new Image();
    image.src = `data:image/png;base64,${png}`;
    await image.decode();
    const canvas = Object.assign(document.createElement('canvas'), { width: 1, height: 1 });
    const paint = canvas.getContext('2d')!;
    paint.drawImage(image, 0, 0);
    return [...paint.getImageData(0, 0, 1, 1).data.slice(0, 3)];
  }, shot);
  // The text box's face is near white; beneath the palette's dark panel or its backdrop it would not be.
  expect(Math.min(pixel[0]!, pixel[1]!, pixel[2]!), `the peek's pixel at ${where.x},${where.y}`).toBeGreaterThan(200);
  await page.keyboard.up('Alt'); await settle(page);
  expect(await page.evaluate(() => document.querySelector('jf-shell')!.shadowRoot!.querySelector('jf-peek')!.shadowRoot!.querySelector('.panel')!.matches(':popover-open'))).toBe(false);
  expect((await probe(page)).open, 'the palette stays open').toBe(true);
}, 60_000);
