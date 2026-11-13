// SPDX-License-Identifier: Apache-2.0
// F-desktop B2 in real Chromium: the start-up frame through a slow discovery (FD1), the deadline
// over the Connecting page (SA7 SA10), the window controls' focus (FW1) and the context-menu policy
// (FW2). Production assets with the native IPC double and Tauri's drag script, as in
// boot-frame-lockdown.test.ts, with a harness of their own. One representative page per behaviour:
// the rules are page-independent (index.html's window layer at window capture, the one focus-return
// rule) and their other pages are covered by windowLayer.test.ts and desktopWindow.test.ts.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { preview, type PreviewServer } from 'vite';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { chromium, type Browser, type BrowserContext, type Page } from 'playwright-core';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

let browser: Browser, server: PreviewServer, output: string, url: string, entry: string, dragScript: string;
const contexts = new Set<BrowserContext>();
const dragFixture = path.resolve('src/boot/__fixtures__/tauri-drag.js.txt'), dragMarker = '// ---- upstream file below, byte-identical ----\n';
const catalog = { schemaVersion: '1.0', catalogVersion: 1, namespace: 'registry-surface', primitive: 'Surface', entries: [{ id: 'core.library-surface', presentation: { labelKey: 'registry-surface.library-surface.label', descriptionKey: 'registry-surface.library-surface.description', iconHint: null, category: null }, audience: 'USER', placement: 'RAIL', altitude: 'PRODUCT', consumes: { resources: ['core.indexed-roots'], operations: [], prompts: [], diagnosticChannels: [], conversationShapes: [] }, mountTag: 'jf-library-surface', members: [], riskTier: 'LOW', stateSchema: null, provenance: { tier: 'CORE', contributorId: 'core', version: '1.0', identity: null } }] };
type Harness = { discoveryHold: boolean; emit: (payload: unknown) => void; emitEvent: (name: string, payload: unknown) => void; samples: string[]; calls: string[]; maximized: boolean; lastMenu?: Event; recoverySeen?: boolean };
declare global { interface Window { chromeHarness: Harness } }

beforeAll(async () => {
  output = await mkdtemp(path.join(tmpdir(), 'desktop-chrome-'));
  await promisify(execFile)(process.execPath, [path.resolve('node_modules/vite/bin/vite.js'), 'build', '--configLoader', 'runner', '--outDir', path.join(output, 'dist'), '--logLevel', 'silent'],
    { cwd: process.cwd(), env: { ...process.env, NODE_ENV: 'production' }, maxBuffer: 8 * 1024 * 1024, timeout: 100_000 });
  entry = (await readFile(path.join(output, 'dist/index.html'), 'utf8')).match(/<script[^>]*type="module"[^>]*src="([^"]+)"/)![1]!;
  server = await preview({ build: { outDir: path.join(output, 'dist') }, preview: { host: '127.0.0.1', port: 0 }, logLevel: 'silent' });
  url = server.resolvedUrls!.local[0]!;
  browser = await chromium.launch({ headless: true });
  // Tauri's own drag-region script (vendored copy of the locked dependency's file), as the desktop
  // runtime injects it.
  dragScript = (await readFile(dragFixture, 'utf8')).split(dragMarker)[1]!.replace('__TEMPLATE_os_name__', JSON.stringify('linux'));
}, 120_000);
afterEach(async () => {
  for (const context of contexts) { await context.close(); contexts.delete(context); }
});
afterAll(async () => {
  await browser?.close();
  if (server) await new Promise<void>((resolve, reject) => server.httpServer.close(error => error ? reject(error) : resolve()));
  if (output) await rm(output, { recursive: true, force: true });
});

type Options = { discoveryHold?: boolean; noEngine?: boolean };
async function create(options: Options = {}): Promise<Page> {
  const context = await browser.newContext({ colorScheme: 'dark' });
  contexts.add(context);
  const page = await context.newPage();
  await page.addInitScript(options => {
    const h: Harness = window.chromeHarness = { discoveryHold: !!options.discoveryHold, emit: () => {}, emitEvent: () => {}, samples: [], calls: [], maximized: false };
    // What the root shows at every frame: a cover (frame, failure, recovery, reset), the app, or nothing.
    const sample = () => {
      const root = document.getElementById('root');
      if (root) h.samples.push(root.querySelector('#boot-frame, #boot-failure, jf-engine-recovery, jf-factory-reset-recovery') ? 'covered' : root.querySelector('jf-shell')?.shadowRoot?.querySelector('jf-top-bar') ? 'rendered' : 'blank');
      requestAnimationFrame(sample);
    };
    requestAnimationFrame(sample);
    // The last context-menu event, held from before every page listener; read after its dispatch.
    window.addEventListener('contextmenu', event => { h.lastMenu = event; }, true);
    const callbacks = new Map<number, (event: unknown) => void>(), listeners = new Map<string, number>();
    let next = 0;
    h.emit = payload => callbacks.get(listeners.get('justsearch://supervisor-state')!)?.({ payload });
    h.emitEvent = (name, payload) => callbacks.get(listeners.get(name)!)?.({ payload });
    Object.assign(window, { __TAURI_INTERNALS__: {
      transformCallback: (callback: (event: unknown) => void) => { callbacks.set(++next, callback); return next; },
      invoke: async (command: string, args: { event: string; handler: number }) => {
        h.calls.push(command);
        if (command === 'plugin:window|is_maximized') return h.maximized;
        if (command === 'plugin:window|toggle_maximize' || command === 'plugin:window|internal_toggle_maximize') { h.maximized = !h.maximized; return null; }
        if (command === 'factory_reset_status') return { phase: 'idle', remaining: [] };
        if (command === 'plugin:event|listen') { listeners.set(args.event, args.handler); return args.handler; }
        if (command === 'supervisor_state') return { schemaVersion: 1, kind: 'engine-supervisor-state.v1', supervisor: 'tauri', state: 'running', incarnation: 1, restartCount: 0, maxRestartAttempts: 3 };
        if (command === 'api_port') {
          // The host's bounded wait found no engine to connect to.
          if (options.noEngine) return null;
          while (h.discoveryHold) await new Promise(resolve => setTimeout(resolve, 10));
          return Number(location.port);
        }
        if (command === 'session_token') return 'test-only';
        if (command === 'app_update_status') return { state: 'up_to_date', currentVersion: '1.0' };
        return null;
      },
    } });
  }, options);
  await page.addInitScript({ content: dragScript });
  await page.route(/\/(api|infra|registry)\//, route => {
    const pathname = new URL(route.request().url()).pathname;
    return route.fulfill({ status: 200, json: pathname === '/api/registry/surfaces' ? catalog : pathname === '/api/settings/v2' ? { ui: { theme: 'light', highContrast: false } } : {} });
  });
  return page;
}
/** The start-up frame with no application module: the entry never arrives. */
async function startUpFrame() {
  const page = await create();
  await page.route(`**${entry}`, () => new Promise(() => {}));
  await page.route('**/*.css', route => route.abort());
  await page.goto(url, { waitUntil: 'commit' });
  await page.waitForSelector('#window-controls', { state: 'attached', timeout: 4000 });
  expect(await page.locator('#root > #boot-frame').count()).toBe(1);
  return page;
}
async function runningApp() {
  const page = await create();
  await page.goto(url); await page.waitForSelector('jf-top-bar .more-trigger');
  await expect.poll(() => page.locator('#boot-frame').count(), { timeout: 5000 }).toBe(0);
  return page;
}
async function pauseClockBeforeNavigation(page: Page) {
  // install() alone keeps ticking in real time. Pause before the document creates its deadline.
  await page.clock.install({ time: new Date('2026-01-01T00:00:00Z') });
  await page.clock.pauseAt(new Date('2026-01-02T00:00:00Z'));
}

it('SA7 SA10 deadline survives a long connecting interval, which a running engine ends by reloading', async () => {
  const page = await create({ noEngine: true });
  try {
    await pauseClockBeforeNavigation(page); await page.goto(url); await page.waitForSelector('jf-engine-recovery');
    await page.clock.fastForward(60_001);
    expect(await page.locator('#boot-failure').count()).toBe(0);
    expect(await page.locator('#root > jf-engine-recovery').count()).toBe(1);
    const reloaded = page.waitForEvent('framenavigated');
    await page.evaluate(() => window.chromeHarness.emit({ schemaVersion: 1, kind: 'engine-supervisor-state.v1', supervisor: 'tauri', state: 'running', incarnation: 1, restartCount: 0, maxRestartAttempts: 3 }));
    await reloaded;
  } finally { await page.close(); }
}, 15_000);

it('FD1 a slow discovery keeps the start-up frame, never a Connecting page, until the app takes over', async () => {
  const page = await create({ discoveryHold: true });
  try {
    await page.addInitScript(() => {
      window.chromeHarness.recoverySeen = false;
      new MutationObserver(() => { if (document.querySelector('#root > jf-engine-recovery')) window.chromeHarness.recoverySeen = true; })
        .observe(document, { childList: true, subtree: true });
    });
    await page.goto(url);
    // Page load and module evaluation precede discovery; a loaded machine takes seconds for them.
    await expect.poll(() => page.evaluate(() => window.chromeHarness.calls.includes('api_port')), { timeout: 10_000 }).toBe(true);
    await page.waitForTimeout(500);
    expect(await page.locator('#root > #boot-frame').count(), 'the frame stays while discovery runs').toBe(1);
    await page.evaluate(() => { window.chromeHarness.discoveryHold = false; });
    await page.waitForSelector('jf-top-bar .more-trigger');
    await expect.poll(() => page.locator('#boot-frame').count(), { timeout: 5000 }).toBe(0);
    expect(await page.evaluate(() => window.chromeHarness.recoverySeen), 'no Connecting page at any moment').toBe(false);
    expect(await page.evaluate(() => window.chromeHarness.samples)).not.toContain('blank');
  } finally { await page.close(); }
}, 20_000);

it('FD1a a crash-looping engine on the running app: restart events between kills, then exhaustion; no reload, the app says it could not restart', async () => {
  const page = await runningApp();
  try {
    // A reload loads a new document; the router's own hash changes are no reload.
    const loads: string[] = [];
    page.on('load', () => loads.push(page.url()));
    await page.evaluate(() => {
      const h = window.chromeHarness;
      h.recoverySeen = false;
      new MutationObserver(() => { if (document.querySelector('#root > jf-engine-recovery')) h.recoverySeen = true; })
        .observe(document, { childList: true, subtree: true });
      const state = (patch: object) => h.emit({ schemaVersion: 1, kind: 'engine-supervisor-state.v1', supervisor: 'tauri', state: 'running', incarnation: 1, restartCount: 0, maxRestartAttempts: 3, ...patch });
      const killed = (incarnation: number, restartCount: number) => state({ state: 'restarting', incarnation, restartCount, lastExit: { code: -9, reason: 'unknown(-9)', class: 'TRANSIENT', incarnation } });
      // The desktop run's timeline, compressed: a new incarnation answers its manifest (the host's
      // restart event), the supervisor may call it running, and it dies again within a second.
      const steps: (() => void)[] = [
        () => killed(1, 1), () => state({ state: 'starting', incarnation: 2, restartCount: 1 }),
        () => { h.emitEvent('justsearch://backend-restart', Number(location.port)); state({ state: 'running', incarnation: 2, restartCount: 1 }); },
        () => killed(2, 2), () => state({ state: 'starting', incarnation: 3, restartCount: 2 }),
        () => h.emitEvent('justsearch://backend-restart', Number(location.port)),
        () => state({ state: 'exhausted', incarnation: 3, restartCount: 3, reason: 'ENGINE_RESTART_EXHAUSTED:unknown(-9)',
          lastExit: { code: -9, reason: 'unknown(-9)', class: 'TRANSIENT', incarnation: 3 }, recentExits: { count: 3, codes: { SIGKILL: 3 } } }),
      ];
      steps.forEach((step, i) => setTimeout(step, 400 * i));
    });
    await page.waitForTimeout(400 * 7 + 1500);
    expect(loads, 'no reload lands while the engine flaps').toEqual([]);
    expect(await page.evaluate(() => window.chromeHarness.recoverySeen), 'no full-page recovery at any moment').toBe(false);
    await expect.poll(() => page.locator('jf-shell [data-testid="engine-loss"]').textContent(), { timeout: 5000 })
      .toContain('The engine stopped 3 times, so JustSearch stopped restarting it.');
  } finally { await page.close(); }
}, 20_000);

// Window controls (FW1). The native double answers window commands.
function control(page: Page, name: string) {
  // The maximize control is named for what it does next.
  return page.getByRole('region', { name: 'Window' }).getByRole('button', name === 'Maximize' ? { name: /^(Maximize|Restore)$/ } : { name, exact: true });
}
/** Window commands that change the window; read-only state queries (is_*) are left out. */
async function windowCalls(page: Page, from = 0) {
  return page.evaluate(from => window.chromeHarness.calls.slice(from).filter(call => call.startsWith('plugin:window|') && !call.startsWith('plugin:window|is_')), from);
}
const callCount = (page: Page) => page.evaluate(() => window.chromeHarness.calls.length);
const onControl = (page: Page) => page.evaluate(() => {
  let el = document.activeElement;
  while (el?.shadowRoot?.activeElement) el = el.shadowRoot.activeElement;
  return !!el?.closest('#window-controls');
});
/** Use one window control by pointer, Enter and Space; after each, focus is off the controls and one more key sends nothing. */
async function sendsAwayOnce(page: Page, name: 'Minimize' | 'Close', before?: () => Promise<void>, back?: () => Promise<boolean>) {
  const command = name === 'Minimize' ? 'minimize' : 'close';
  for (const how of ['pointer', 'Enter', 'Space'] as const) {
    await before?.();
    const from = await callCount(page);
    const button = control(page, name);
    if (how === 'pointer') { const box = (await button.boundingBox())!; await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2); }
    else { await button.focus(); await page.keyboard.press(how); }
    await expect.poll(() => windowCalls(page, from), { timeout: 2000 }).toEqual([`plugin:window|${command}`]);
    // The window comes back (the host shows it again); the document's focus is what it returns to.
    expect(await onControl(page), `${name} by ${how}: focus left the control`).toBe(false);
    if (back) expect(await back(), `${name} by ${how}: focus is back where it was`).toBe(true);
    const after = await callCount(page);
    await page.keyboard.press(how === 'Enter' ? 'Enter' : 'Space');
    // A key on the control that had focus before may open its menu; that is not a window command.
    if (back) await page.keyboard.press('Escape');
    expect(await windowCalls(page, after), `${name} by ${how}: one more key sends no window command`).toEqual([]);
  }
}
it('FW1 Minimize on the running app returns focus to where it was before the control, so one key cannot minimize again; Maximize keeps its focus', async () => {
  const page = await runningApp();
  try {
    const more = page.locator('jf-top-bar .more-trigger');
    await sendsAwayOnce(page, 'Minimize', () => more.focus(), () => more.evaluate(el => (el.getRootNode() as ShadowRoot).activeElement === el));
    // Maximize keeps its focus: the window stays and may be toggled again.
    await control(page, 'Maximize').focus();
    await page.keyboard.press('Enter');
    expect(await onControl(page)).toBe(true);
  } finally { await page.close(); }
}, 20_000);
it('FW1 Close on the start-up frame lets go of focus with no application module, so one key cannot hide the window again', async () => {
  const page = await startUpFrame();
  try {
    await sendsAwayOnce(page, 'Close');
  } finally { await page.close(); }
}, 20_000);

// Context-menu policy (FW2).
/** Right-click at a point; whether the web view's own menu would open (the event's default was not prevented). */
async function webviewMenuAt(page: Page, x: number, y: number) {
  await page.evaluate(() => { delete window.chromeHarness.lastMenu; });
  await page.mouse.click(x, y, { button: 'right' });
  return page.evaluate(([x, y]) => {
    const event = window.chromeHarness.lastMenu;
    let el = document.elementFromPoint(x, y);
    while (el?.shadowRoot) { const inner = el.shadowRoot.elementFromPoint(x, y); if (!inner || inner === el) break; el = inner; }
    return event ? { menu: !event.defaultPrevented, at: el?.localName } : null;
  }, [x, y] as const);
}
it('FW2 production build: right-click on the top area and the content of the running app opens no web view menu', async () => {
  const page = await runningApp();
  try {
    const root = parseFloat(await page.evaluate(() => getComputedStyle(document.documentElement).fontSize));
    const viewport = page.viewportSize()!;
    expect(await webviewMenuAt(page, root * 0.5, root * 0.5), 'the drag region of the top area').toMatchObject({ menu: false });
    // The content area outside any text field (the running app's centre holds the search box).
    let checked = 0;
    for (const y of [0.3, 0.6, 0.9]) {
      const result = await webviewMenuAt(page, viewport.width / 2, viewport.height * y);
      if (['input', 'textarea'].includes(result?.at ?? '')) continue;
      expect(result, `the content area at ${y}`).toMatchObject({ menu: false });
      checked++;
    }
    expect(checked, 'some content outside text fields was right-clicked').toBeGreaterThan(0);
  } finally { await page.close(); }
}, 20_000);
it('FW2 production build: a text field keeps the platform edit menu; the app opens its own menus as before', async () => {
  const page = await runningApp();
  try {
    await page.keyboard.press('Control+k');
    const field = page.locator('jf-command-palette input').first();
    await field.waitFor({ state: 'visible' });
    const box = (await field.boundingBox())!;
    expect(await webviewMenuAt(page, box.x + box.width / 2, box.y + box.height / 2), 'the palette field').toMatchObject({ menu: true, at: 'input' });
  } finally { await page.close(); }
}, 15_000);
