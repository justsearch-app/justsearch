// SPDX-License-Identifier: Apache-2.0
// Production assets in real Chromium, with native IPC and HTTP boundary doubles.
// BOOT_FRAME_TREE selects an isolated baseline checkout for fail-before runs.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { preview, type PreviewServer } from 'vite';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { chromium, type Browser, type BrowserContext, type Page } from 'playwright-core';
import { mkdtemp, readFile, readdir, writeFile, rm } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';
import path from 'node:path';
let browser: Browser, server: PreviewServer, output: string, url: string, entry: string, builtHtml: string, probe: string, version: string, dragScript: string;
const contexts = new Set<BrowserContext>();
const dragFixture = path.resolve('src/boot/__fixtures__/tauri-drag.js.txt'), dragMarker = '// ---- upstream file below, byte-identical ----\n';
const key = 'justsearch.bootFrame.v1';
const secret = 'SECRET-token-key /home/person/private https://secret-host.invalid';
const saved = { mode: 'light', background: 'rgb(248, 249, 252)', raised: 'rgb(241, 242, 248)', text: 'rgb(20, 24, 32)', muted: 'rgb(50, 55, 65)', border: 'rgb(110, 115, 125)', accent: 'rgb(0, 110, 100)' };
const catalog = { schemaVersion: '1.0', catalogVersion: 1, namespace: 'registry-surface', primitive: 'Surface', entries: [{ id: 'core.library-surface', presentation: { labelKey: 'registry-surface.library-surface.label', descriptionKey: 'registry-surface.library-surface.description', iconHint: null, category: null }, audience: 'USER', placement: 'RAIL', altitude: 'PRODUCT', consumes: { resources: ['core.indexed-roots'], operations: [], prompts: [], diagnosticChannels: [], conversationShapes: [] }, mountTag: 'jf-library-surface', members: [], riskTier: 'LOW', stateSchema: null, provenance: { tier: 'CORE', contributorId: 'core', version: '1.0', identity: null } }] };
type Harness = { phase?: string; discoveryHold: boolean; hold: boolean; renderHold: boolean; emit: (payload: unknown) => void; renders: number; samples: string[]; inputs: string[]; calls: string[]; maximized: boolean; rejections: string[] };
// Independent inventory of native input paths, including document/window capture listeners.
const inputTypes = ['keydown', 'keyup', 'keypress', 'pointerdown', 'pointerup', 'pointermove', 'pointercancel',
  'mousedown', 'mouseup', 'click', 'wheel', 'touchstart', 'input', 'dragenter', 'dragover', 'dragleave', 'drop',
  'mouseenter', 'mouseleave', 'scroll', 'contextmenu', 'paste'];
declare global { interface Window { frameHarness: Harness; frameProbe: { createProfile: (id: string, label: string) => void; setActiveProfileId: (id: string) => void; dismissWalkthrough: (id: string) => void; applyAppearance: (appearance: { paletteId: string; theme: string }) => Promise<void>; gateFactoryReset: (root: HTMLElement) => Promise<boolean> } } }
beforeAll(async () => {
  output = await mkdtemp(path.join(tmpdir(), 'boot-frame-'));
  version = (await readFile(path.resolve('../../gradle.properties'), 'utf8')).match(/^version=(.+)$/m)![1]!;
  const root = process.env.BOOT_FRAME_TREE ? path.join(process.env.BOOT_FRAME_TREE, 'modules/ui-web') : process.cwd();
  // Test-only second entry exposes existing state APIs in this SAME production module graph;
  // it is never included by the normal build. No production hooks or test query parameters.
  const adapter = path.join(output, 'adapter.ts');
  await writeFile(adapter, `import { gateFactoryReset } from ${JSON.stringify(path.join(root, 'src/boot/factoryReset.ts'))};
import { createProfile, setActiveProfileId, dismissWalkthrough } from ${JSON.stringify(path.join(root, 'src/shell-v0/state/UserStateDocument.ts'))};
import { applyAppearance } from ${JSON.stringify(path.join(root, 'src/shell-v0/state/themeState.ts'))};
Object.assign(window, { frameProbe: { createProfile, setActiveProfileId, dismissWalkthrough, applyAppearance, gateFactoryReset } });`);
  const config = path.join(output, 'config.mjs');
  await writeFile(config, `import original from ${JSON.stringify(path.join(root, 'vite.config.js'))};
import { mergeConfig } from ${JSON.stringify(path.resolve('node_modules/vite/dist/node/index.js'))};
export default env => mergeConfig(original(env), {build:{rollupOptions:{input:{main:${JSON.stringify(path.join(root, 'index.html'))}, bootProbe:${JSON.stringify(adapter)}}}}});`);
  await promisify(execFile)(process.execPath, [path.resolve('node_modules/vite/bin/vite.js'), 'build', '--config', config, '--configLoader', 'runner', '--outDir', path.join(output, 'dist'), '--logLevel', 'silent'],
    { cwd: root, env: { ...process.env, NODE_ENV: 'production' }, maxBuffer: 8 * 1024 * 1024, timeout: 100_000 });
  probe = '/assets/' + (await readdir(path.join(output, 'dist/assets'))).find(file => file.startsWith('bootProbe-') && file.endsWith('.js'));
  builtHtml = await readFile(path.join(output, 'dist/index.html'), 'utf8');
  entry = builtHtml.match(/<script[^>]*type="module"[^>]*src="([^"]+)"/)![1]!;
  server = await preview({ root, build: { outDir: path.join(output, 'dist') }, preview: { host: '127.0.0.1', port: 0 }, logLevel: 'silent' });
  url = server.resolvedUrls!.local[0]!;
  browser = await chromium.launch({ headless: true });
  // The native double includes Tauri's own drag-region script (vendored copy of the locked
  // dependency's file), as the desktop runtime injects it, so drag regions are exercised by the
  // code that serves them.
  dragScript = (await readFile(dragFixture, 'utf8')).split(dragMarker)[1]!.replace('__TEMPLATE_os_name__', JSON.stringify('linux'));
}, 120_000);
afterEach(async () => {
  // One owner closes each context once, after the test has closed its pages.
  for (const context of contexts) { await context.close(); contexts.delete(context); }
});
afterAll(async () => {
  await browser?.close();
  if (server) await new Promise<void>((resolve, reject) => server.httpServer.close(error => error ? reject(error) : resolve()));
  if (output) await rm(output, { recursive: true, force: true });
});
type Options = { saved?: unknown; os?: 'light' | 'dark'; storageThrows?: boolean; desktop?: boolean; phase?: string; hold?: boolean; renderHold?: boolean; future?: string; settingsFail?: boolean; discoveryHold?: boolean;
  supervisor?: Record<string, unknown>; resetThrows?: boolean; maximized?: boolean; windowRejects?: boolean; theme?: 'light' | 'dark'; storage?: Record<string, string> };
async function create(options: Options = {}): Promise<Page> {
  const context = await browser.newContext({ colorScheme: options.os ?? 'dark' });
  contexts.add(context);
  const page = await context.newPage();
  await page.addInitScript(({ options, key, inputTypes }) => {
    if (options.saved !== undefined && !localStorage.getItem(key)) localStorage.setItem(key, JSON.stringify(options.saved));
    if (options.future && !localStorage.getItem('justsearch.userState.v2')) localStorage.setItem('justsearch.userState.v2', options.future);
    for (const [name, value] of Object.entries(options.storage ?? {})) if (localStorage.getItem(name) === null) localStorage.setItem(name, value);
    if (options.storageThrows) {
      Storage.prototype.getItem = () => { throw new Error('storage denied'); };
      Storage.prototype.setItem = () => { throw new Error('quota denied'); };
    }
    const h = window.frameHarness = { discoveryHold: !!options.discoveryHold, hold: !!options.hold, renderHold: !!options.renderHold, emit: (_payload: unknown) => {}, renders: 0, samples: [] as string[], inputs: [] as string[], calls: [] as string[], maximized: !!options.maximized, rejections: [] as string[] };
    window.addEventListener('unhandledrejection', event => h.rejections.push(String(event.reason)));
    const sample = () => {
      const root = document.getElementById('root');
      if (root) h.samples.push(root.querySelector('#boot-frame, #boot-failure, jf-engine-recovery, jf-factory-reset-recovery') ? 'covered' : root.querySelector('jf-shell')?.shadowRoot?.querySelector('jf-top-bar') ? 'rendered' : 'blank');
      requestAnimationFrame(sample);
    };
    requestAnimationFrame(sample);
    const define = customElements.define.bind(customElements);
    customElements.define = (name, ctor, config) => {
      if (name === 'jf-shell') {
        // Install observers at attachment, ahead of the shell's own listeners but after the
        // boot guard. A document-only guard cannot hide a leak at window capture from these.
        const connected = ctor.prototype.connectedCallback, disconnected = ctor.prototype.disconnectedCallback;
        let controller: AbortController;
        ctor.prototype.connectedCallback = function () {
          controller = new AbortController();
          for (const [target, name] of [[window, 'window'], [document, 'document']] as const) {
            for (const type of inputTypes) target.addEventListener(type, () => h.inputs.push(`${name}:${type}`), { capture: true, signal: controller.signal });
          }
          return connected.call(this);
        };
        ctor.prototype.disconnectedCallback = function () { controller.abort(); return disconnected.call(this); };
        const perform = ctor.prototype.performUpdate;
        ctor.prototype.performUpdate = async function () {
          while (h.hold) await new Promise(resolve => setTimeout(resolve, 10));
          const result = perform.call(this);
          // Render real controls beneath the frame, but keep Lit's hand-off promise pending.
          while (h.renderHold) await new Promise(resolve => setTimeout(resolve, 10));
          await result;
          h.renders++;
        };
      }
      define(name, ctor, config);
    };
    if (options.desktop === false) return;
    const callbacks = new Map<number, (event: unknown) => void>(), listeners = new Map<string, number>();
    let next = 0;
    h.emit = payload => callbacks.get(listeners.get('justsearch://supervisor-state')!)?.({ payload });
    Object.assign(window, { __TAURI_INTERNALS__: {
      transformCallback: (callback: (event: unknown) => void) => { callbacks.set(++next, callback); return next; },
      invoke: async (command: string, args: { event: string; handler: number }) => {
        h.calls.push(command);
        // A tiling compositor refuses window state operations: the layer's own commands reject.
        // Tauri's drag commands only queue a request (tao drag_window returns Ok), so they do nothing.
        if (options.windowRejects && /\|(is_maximized|minimize|toggle_maximize|close)$/.test(command)) throw new Error('not supported by this compositor');
        if (options.windowRejects && /\|(start_dragging|internal_toggle_maximize)$/.test(command)) return null;
        if (command === 'plugin:window|is_maximized') return h.maximized;
        if (command === 'plugin:window|toggle_maximize' || command === 'plugin:window|internal_toggle_maximize') { h.maximized = !h.maximized; return null; }
        if (command === 'factory_reset_status') {
          if (options.resetThrows) throw new Error('reset status unavailable');
          return { phase: window.frameHarness.phase ?? options.phase ?? 'idle', remaining: [] };
        }
        if (command === 'plugin:event|listen') { listeners.set(args.event, args.handler); return args.handler; }
        if (command === 'supervisor_state') return { schemaVersion: 1, kind: 'engine-supervisor-state.v1', supervisor: 'tauri', state: 'running', incarnation: 1, restartCount: 0, maxRestartAttempts: 3, ...options.supervisor };
        if (command === 'api_port') {
          while (h.discoveryHold) await new Promise(resolve => setTimeout(resolve, 10));
          return Number(location.port);
        }
        if (command === 'session_token') return 'test-only';
        if (command === 'app_update_status') return { state: 'up_to_date', currentVersion: '1.0' };
        return null;
      },
    } });
  }, { options, key, inputTypes });
  if (options.desktop !== false) await page.addInitScript({ content: dragScript });
  await page.route(/\/(api|infra|registry)\//, route => {
    const pathname = new URL(route.request().url()).pathname;
    return route.fulfill({ status: options.settingsFail && pathname === '/api/settings/v2' ? 503 : 200, json: pathname === '/api/registry/surfaces' ? catalog : pathname === '/api/settings/v2' ? { ui: { theme: options.theme ?? 'light', highContrast: false } } : {} });
  });
  return page;
}
async function blocked(options: Options = {}) {
  const page = await create(options);
  await page.route(`**${entry}`, () => new Promise(() => {}));
  await page.route('**/*.css', route => route.abort());
  await page.goto(url, { waitUntil: 'commit' });
  // A committed response can precede parsing and the inline appearance script.
  await page.waitForFunction(() => {
    const frame = document.getElementById('boot-frame');
    return frame && frame.style.colorScheme === document.documentElement.getAttribute('data-theme');
  }, undefined, { timeout: 4000 });
  return page;
}
async function failure(page: Page, kind: string) {
  await expect.poll(() => page.locator('#boot-failure').count(), { timeout: 4000 }).toBe(1);
  expect(await page.getByRole('heading', { name: "JustSearch couldn't open its window" }).count()).toBe(1);
  expect(await page.locator(':focus').textContent()).toContain('Reload');
  await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true }));
  await page.getByRole('button', { name: 'Copy report' }).click();
  const report = JSON.parse(await page.locator('#boot-report').inputValue());
  expect(Object.keys(report).sort()).toEqual(['failureKind', 'operatingSystem', 'productVersion', 'runtimeKind', 'time']);
  expect(report.failureKind).toBe(kind);
  expect(report.productVersion).toBe(version);
  expect(report.time).toMatch(/^\d{4}-\d\d-\d\dT/);
  expect(JSON.stringify(report)).not.toMatch(/SECRET|private|secret-host|token|http|\/home/);
}
async function pauseClockBeforeNavigation(page: Page) {
  // install() alone keeps ticking in real time. Pause before the document creates its
  // deadline, so loading and assertions cannot consume any of the tested 60 seconds.
  await page.clock.install({ time: new Date('2026-01-01T00:00:00Z') });
  await page.clock.pauseAt(new Date('2026-01-02T00:00:00Z'));
}
it('SA1 SA6 first paint precedes entry and is inert', async () => {
  const page = await blocked();
  try {
    expect(await page.locator('#boot-frame').count()).toBe(1);
    expect(await page.locator('#boot-frame').evaluate(el => ({ background: getComputedStyle(el).backgroundColor, width: el.getBoundingClientRect().width }))).toEqual({ background: 'rgb(10, 10, 12)', width: 1280 });
    expect(await page.locator('#root button, #root a, #root input, #root [tabindex], #root jf-top-bar').count()).toBe(0);
    // SW1/SW2: the desktop window layer outside #root holds the page's only controls; the frame stays inert.
    expect(await page.locator('button, a, input, select, textarea, [tabindex]').evaluateAll(els => els.map(el => el.getAttribute('aria-label')))).toEqual(['Minimize', 'Maximize', 'Close']);
    expect(await page.getByRole('status').textContent()).toBe('Starting');
    await page.keyboard.press('Tab'); expect(await page.locator('#boot-frame :focus').count()).toBe(0);
    expect(await page.evaluate(() => document.activeElement!.getAttribute('aria-label'))).toBe('Minimize');
  } finally { await page.close(); }
});
async function watchShellInput(page: Page) {
  await page.locator('jf-shell').evaluate(shell => {
    shell.setAttribute('data-clicks', '0'); shell.setAttribute('data-keys', '0');
    for (const [event, attribute] of [['click', 'data-clicks'], ['keydown', 'data-keys']] as const) {
      shell.addEventListener(event, () => shell.setAttribute(attribute, String(Number(shell.getAttribute(attribute)) + 1)));
    }
  });
}
async function tabThroughCover(page: Page) {
  const focused: string[] = [];
  for (let i = 0; i < 6; i++) {
    await page.keyboard.press('Tab');
    focused.push(await page.evaluate(() => document.activeElement!.localName));
  }
  return focused;
}
async function shellWorksAfterCover(page: Page) {
  const more = page.locator('jf-top-bar .more-trigger');
  for (let i = 0; i < 30 && !await more.evaluate(el => el.getRootNode() instanceof ShadowRoot && (el.getRootNode() as ShadowRoot).activeElement === el); i++) await page.keyboard.press('Tab');
  expect(await more.evaluate(el => (el.getRootNode() as ShadowRoot).activeElement === el)).toBe(true);
  await page.keyboard.press('Enter');
  await page.waitForSelector('jf-top-bar [role="menu"]');
  await page.keyboard.press('Escape');
  const box = (await more.boundingBox())!;
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  await expect.poll(() => page.locator('jf-top-bar [role="menu"]').count(), { timeout: 4000 }).toBe(1);
}
it('SA6 rendered shell rejects input until frame hand-off', async () => {
  const page = await create({ renderHold: true });
  try {
    await pauseClockBeforeNavigation(page); await page.goto(url); await page.waitForSelector('jf-top-bar .more-trigger');
    await page.addScriptTag({ type: 'module', url: new URL(probe, url).href });
    await page.evaluate(() => window.frameProbe.dismissWalkthrough('welcome'));
    await watchShellInput(page);
    expect(await page.locator('#boot-frame').count()).toBe(1);
    const box = (await page.locator('jf-top-bar .more-trigger').boundingBox())!;
    const focused = await tabThroughCover(page);
    // A coordinate click bypasses Playwright's actionability waiting for the cover to leave.
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
    expect({ shellFocused: focused.includes('jf-shell'), clicks: await page.locator('jf-shell').getAttribute('data-clicks'),
      keys: await page.locator('jf-shell').getAttribute('data-keys'), menus: await page.locator('jf-top-bar [role="menu"]').count() })
      .toEqual({ shellFocused: false, clicks: '0', keys: '0', menus: 0 });
    // Tab reaches only the desktop window controls beside the frame (SW2); hand-off leaves focus where it was.
    const before = await page.evaluateHandle(() => document.activeElement!);
    expect(await before.evaluate(el => el === document.body || el.closest('#window-controls') !== null)).toBe(true);
    await page.evaluate(() => { window.frameHarness.renderHold = false; }); await page.clock.resume();
    await expect.poll(() => page.locator('#boot-frame, #boot-failure').count()).toBe(0);
    expect(await before.evaluate(el => document.activeElement === el)).toBe(true);
    await shellWorksAfterCover(page);
  } finally { await page.close(); }
});
it('SA6 never-rendering shell stays unreachable and deadline Reload takes focus', async () => {
  const page = await create({ hold: true });
  try {
    await pauseClockBeforeNavigation(page); await page.goto(url); await page.waitForSelector('jf-shell', { state: 'attached' });
    await watchShellInput(page);
    const focused = await tabThroughCover(page); await page.mouse.click(20, 20);
    expect({ shellFocused: focused.includes('jf-shell'), clicks: await page.locator('jf-shell').getAttribute('data-clicks'),
      keys: await page.locator('jf-shell').getAttribute('data-keys'), inert: await page.locator('jf-shell').evaluate(shell => (shell as HTMLElement).inert) })
      .toEqual({ shellFocused: false, clicks: '0', keys: '0', inert: true });
    await page.clock.fastForward(60_001);
    expect(await page.locator('#boot-failure').count()).toBe(1);
    expect(await page.locator(':focus').textContent()).toBe('Reload');
    expect(await page.locator('jf-top-bar').count()).toBe(0);
    expect(await page.locator('jf-shell').count()).toBe(0);
    await page.evaluate(() => { window.frameHarness.hold = false; }); await page.clock.resume();
    await expect.poll(() => page.evaluate(() => window.frameHarness.renders), { timeout: 4000 }).toBeGreaterThan(0);
    expect(await page.locator('#boot-failure').count()).toBe(1);
    await Promise.all([page.waitForEvent('framenavigated'), page.keyboard.press('Enter')]);
    await page.waitForSelector('jf-shell', { state: 'attached' });
  } finally { await page.close(); }
});
const shellShortcuts = ['Control+k', 'Control+,', 'Control+1', 'Control+2', 'Control+3', 'F1', 'F6', 'Escape', 'Alt+ArrowLeft', 'Alt+ArrowRight', 'Space'];
async function heldShell() {
  const page = await create({ renderHold: true });
  await pauseClockBeforeNavigation(page); await page.goto(url); await page.waitForSelector('jf-top-bar .more-trigger');
  await page.addScriptTag({ type: 'module', url: new URL(probe, url).href });
  await page.evaluate(() => window.frameProbe.dismissWalkthrough('welcome'));
  await page.waitForLoadState('networkidle');
  return page;
}
async function releaseShell(page: Page) {
  await page.evaluate(() => { window.frameHarness.renderHold = false; }); await page.clock.resume();
  await expect.poll(() => page.locator('#boot-frame').count()).toBe(0);
}
async function dispatchInputs(page: Page) {
  return page.evaluate(types => types.map(type => {
    // Test both the document path and direct window dispatch (mouse navigation/drag listeners).
    return [document, window].map(target => {
      window.frameHarness.inputs.length = 0;
      const init = { bubbles: true, cancelable: true, composed: true };
      const event = type.startsWith('key') ? new KeyboardEvent(type, { ...init, key: 'Unidentified' })
        : type.startsWith('pointer') ? new PointerEvent(type, init)
          : type.startsWith('mouse') || ['click', 'contextmenu'].includes(type) ? new MouseEvent(type, init)
            : type === 'wheel' ? new WheelEvent(type, init)
              : type.startsWith('drag') || type === 'drop' ? new DragEvent(type, { ...init, dataTransfer: new DataTransfer() })
                : type === 'paste' ? new ClipboardEvent(type, { ...init, clipboardData: new DataTransfer() }) : new Event(type, init);
      target.dispatchEvent(event);
      return { type, target: target === window ? 'window' : 'document', seen: [...window.frameHarness.inputs], prevented: event.defaultPrevented };
    });
  }).flat(), inputTypes);
}
it('SA6 every input path is stopped at window capture under the frame and released after paint', async () => {
  const page = await heldShell();
  try {
    const blocked = await dispatchInputs(page);
    expect(blocked.filter(result => result.seen.length)).toEqual([]);
    await releaseShell(page);
    const released = await dispatchInputs(page);
    expect(released.filter(result => !result.seen.includes(`window:${result.type}`))).toEqual([]);
    expect(released.filter(result => result.target === 'document' && !result.seen.includes(`document:${result.type}`))).toEqual([]);
  } finally { await page.close(); }
});
it('SA6 shell shortcuts neither run nor queue beneath the frame, then normal shortcuts work', async () => {
  const page = await heldShell();
  try {
    const address = page.url();
    for (const chord of [...shellShortcuts, 'Tab', 'Shift+Tab']) {
      await page.evaluate(() => { window.frameHarness.inputs.length = 0; });
      await page.keyboard.press(chord);
      expect({ chord, seen: await page.evaluate(() => window.frameHarness.inputs), address: page.url() }).toEqual({ chord, seen: [], address });
    }
    await releaseShell(page);
    expect(page.url()).toBe(address);
    expect(await page.locator('jf-top-bar [role="menu"], jf-command-palette[open], jf-settings-window[open]').count()).toBe(0);
    // Every chord again reaches the real application's window listener after hand-off.
    for (const chord of shellShortcuts) {
      await page.evaluate(() => { window.frameHarness.inputs.length = 0; });
      await page.keyboard.press(chord);
      expect(await page.evaluate(() => window.frameHarness.inputs)).toContain('window:keydown');
      await page.keyboard.press('Escape');
    }
    await page.keyboard.press('Control+2');
    await expect.poll(() => page.url()).toContain('core.library-surface');
    await page.keyboard.press('F1');
    await expect.poll(() => page.url()).toContain('core.help-surface');
  } finally { await page.close(); }
});
it('SA6 pointer hover wheel context menu and mouse Back/Forward cannot reach the covered shell', async () => {
  const page = await heldShell();
  try {
    const address = page.url(), box = (await page.locator('jf-top-bar .more-trigger').boundingBox())!;
    await page.evaluate(() => { window.frameHarness.inputs.length = 0; });
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.wheel(0, 200);
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2, { button: 'right' });
    await page.evaluate(() => [3, 4].forEach(button => {
      const event = new MouseEvent('mouseup', { button, cancelable: true });
      window.dispatchEvent(event);
    }));
    expect(await page.evaluate(() => window.frameHarness.inputs)).toEqual([]);
    expect(page.url()).toBe(address);
    expect(await page.locator('jf-top-bar [role="menu"]').count()).toBe(0);
    await releaseShell(page); await shellWorksAfterCover(page);
  } finally { await page.close(); }
});
it('SA6 SA17 deadline discards rendered controls; failure page alone handles input and keyboard actions', async () => {
  const page = await heldShell();
  try {
    await page.clock.fastForward(60_001);
    expect(await page.locator('#boot-failure').count()).toBe(1);
    expect(await page.locator('jf-shell').count()).toBe(0);
    const address = page.url();
    await page.evaluate(() => { window.frameHarness.inputs.length = 0; });
    for (const chord of shellShortcuts.filter(chord => chord !== 'Space')) await page.keyboard.press(chord);
    expect(page.url()).toBe(address);
    expect(await page.evaluate(() => window.frameHarness.inputs)).toEqual([]);
    expect((await dispatchInputs(page)).filter(result => result.seen.length)).toEqual([]);
    await page.getByRole('button', { name: 'Reload', exact: true }).focus();
    await page.keyboard.press('Tab'); expect(await page.locator(':focus').textContent()).toBe('Copy report');
    await page.keyboard.press('Shift+Tab'); expect(await page.locator(':focus').textContent()).toBe('Reload');
    await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined }));
    for (const key of ['Enter', 'Space']) {
      await page.getByRole('button', { name: 'Copy report' }).focus(); await page.keyboard.press(key);
      expect(JSON.parse(await page.locator('#boot-report').inputValue()).failureKind).toBe('stalled');
      expect(await page.locator(':focus').getAttribute('id')).toBe('boot-report');
    }
    // Finishing the discarded render cannot remove the failure page or reclaim focus.
    await page.evaluate(() => { window.frameHarness.renderHold = false; }); await page.clock.resume();
    await expect.poll(() => page.evaluate(() => window.frameHarness.renders), { timeout: 4000 }).toBeGreaterThan(0);
    expect(await page.locator('#boot-failure').count()).toBe(1);
    for (const key of ['Enter', 'Space']) {
      await pauseClockBeforeNavigation(page);
      await page.getByRole('button', { name: 'Reload', exact: true }).focus();
      await Promise.all([page.waitForEvent('framenavigated'), page.keyboard.press(key)]);
      await page.waitForSelector('jf-top-bar'); await page.clock.fastForward(60_001);
      expect(await page.locator('#boot-failure').count()).toBe(1);
    }
  } finally { await page.close(); }
}, 15_000);
for (const stage of ['frame', 'failure', 'painted']) it(`SA6 browser reload keys retain their native default at ${stage}`, async () => {
  const page = await heldShell();
  try {
    if (stage === 'failure') await page.clock.fastForward(60_001);
    if (stage === 'painted') await releaseShell(page);
    const defaults = await page.evaluate(() => ['F5', 'r'].map(key => {
      const event = new KeyboardEvent('keydown', { key, ctrlKey: key === 'r', bubbles: true, cancelable: true });
      return { key, allowed: document.activeElement!.dispatchEvent(event), prevented: event.defaultPrevented };
    }));
    expect(defaults).toEqual([{ key: 'F5', allowed: true, prevented: false }, { key: 'r', allowed: true, prevented: false }]);
  } finally { await page.close(); }
});
for (const [label, value, os, mode] of [
  ['saved light over OS dark', saved, 'dark', 'light'],
  ['saved dark over OS light', { ...saved, mode: 'dark', background: 'rgb(10, 10, 12)' }, 'light', 'dark'],
  ['no copy follows OS light', undefined, 'light', 'light'],
  ['hostile copy follows OS dark', { ...saved, text: 'red; background:url(https://secret-host.invalid)' }, 'dark', 'dark'],
] as const) it(`SA2 SA3 SA4 ${label}`, async () => {
  const page = await blocked({ saved: value, os });
  try {
    expect(await page.locator('#boot-frame').count()).toBe(1);
    expect(await page.locator('html').getAttribute('data-theme')).toBe(mode);
    if (label.startsWith('saved')) expect(await page.locator('#boot-frame').evaluate(el => getComputedStyle(el).backgroundColor)).toBe(value!.background);
    expect(await page.locator('html').innerHTML()).not.toContain('secret-host');
  } finally { await page.close(); }
});
it('SA3 query theme wins', async () => {
  const page = await blocked({ saved });
  try {
    await page.goto(`${url}?theme=dark`, { waitUntil: 'commit' });
    await page.waitForFunction(() => document.documentElement.getAttribute('data-theme') === 'dark', undefined, { timeout: 4000 });
    expect(await page.locator('html').getAttribute('data-theme')).toBe('dark');
  }
  finally { await page.close(); }
});
it('SA4 denied storage still paints', async () => {
  const page = await blocked({ storageThrows: true, os: 'light' });
  try { expect(await page.locator('#boot-frame').count()).toBe(1); expect(await page.locator('html').getAttribute('data-theme')).toBe('light'); }
  finally { await page.close(); }
});
for (const fault of ['entry', 'parse', 'dependency', 'evaluation', 'bootstrap', 'no-engine-address'] as const) it(`SA8 SA12 ${fault} failure report`, async () => {
  const page = await create({ desktop: fault !== 'no-engine-address' });
  try {
    if (fault === 'entry') await page.route(`**${entry}`, route => route.abort());
    if (fault === 'parse') await page.route(`**${entry}`, route => route.fulfill({ contentType: 'text/javascript', body: 'const = ; // SECRET-token' }));
    if (fault === 'dependency' || fault === 'evaluation') {
      await page.route(`**${entry}`, async route => { const response = await route.fetch(); await route.fulfill({ response, body: `import '/fault.js';\n${await response.text()}` }); });
      await page.route('**/fault.js', route => fault === 'dependency' ? route.fulfill({ status: 404, body: secret }) : route.fulfill({ contentType: 'text/javascript', body: `throw new Error(${JSON.stringify(secret)})` }));
    }
    if (fault === 'bootstrap') await page.route('**/vendor-ses-*.js', route => route.fulfill({ contentType: 'text/javascript', body: `throw new Error(${JSON.stringify(secret)})` }));
    await page.goto(url);
    await failure(page, fault === 'entry' || fault === 'dependency' ? 'entry' : fault === 'evaluation' || fault === 'parse' ? 'evaluation' : fault);
  } finally { await page.close(); }
});
it('SA10 SA17 60-second deadline discards an attached shell even when rendering later completes', async () => {
  const page = await create({ hold: true });
  try {
    await pauseClockBeforeNavigation(page); await page.goto(url); await page.waitForSelector('jf-shell', { state: 'attached' });
    expect(await page.locator('#boot-frame').count()).toBe(1);
    await page.clock.fastForward(59_999); expect(await page.locator('#boot-failure').count()).toBe(0);
    await page.clock.fastForward(2); await failure(page, 'stalled');
    expect(await page.locator('jf-shell').count()).toBe(0);
    await page.evaluate(() => { window.frameHarness.hold = false; }); await page.clock.resume();
    await expect.poll(() => page.evaluate(() => window.frameHarness.renders), { timeout: 4000 }).toBeGreaterThan(0);
    expect(await page.locator('#boot-failure').count()).toBe(1);
  } finally { await page.close(); }
});
it('SA7 SA17 no blank interval through recovery removal and delayed render', async () => {
  const page = await create({ hold: true });
  try {
    await page.goto(url); await page.waitForSelector('jf-shell', { state: 'attached' });
    const samples = await page.evaluate(() => window.frameHarness.samples.length);
    await expect.poll(() => page.evaluate(() => window.frameHarness.samples.length), { timeout: 4000 }).toBeGreaterThanOrEqual(samples + 10);
    expect(await page.locator('#boot-frame').count()).toBe(1);
    await page.evaluate(() => { window.frameHarness.hold = false; }); await page.waitForSelector('jf-top-bar');
    await expect.poll(() => page.locator('#boot-frame').count()).toBe(0);
    expect(await page.evaluate(() => window.frameHarness.samples)).not.toContain('blank');
  } finally { await page.close(); }
});
it('SA14 terminal event during hand-off beats first render and deadline', async () => {
  const page = await create({ hold: true });
  try {
    await pauseClockBeforeNavigation(page); await page.goto(url); await page.waitForSelector('jf-shell', { state: 'attached' });
    await page.evaluate(() => { window.frameHarness.emit({ schemaVersion: 1, kind: 'engine-supervisor-state.v1', supervisor: 'tauri', state: 'exhausted', incarnation: 1, restartCount: 3, maxRestartAttempts: 3 }); window.frameHarness.hold = false; });
    await page.clock.fastForward(60_001);
    expect(await page.locator('#root > jf-engine-recovery').count()).toBe(1);
    expect(await page.locator('#boot-failure, #boot-frame, #root > jf-shell').count()).toBe(0);
  } finally { await page.close(); }
});
for (const phase of ['incomplete', 'awaiting_storage']) it(`SA14 reset ${phase} takes over`, async () => {
  const page = await create({ phase, saved });
  try {
    await pauseClockBeforeNavigation(page); await page.goto(url); await page.waitForSelector('jf-factory-reset-recovery'); await page.clock.fastForward(60_001);
    expect(await page.locator('#boot-frame, #boot-failure, jf-shell').count()).toBe(0);
    if (phase === 'awaiting_storage') expect(await page.evaluate(key => localStorage.getItem(key), key)).toBeNull();
  } finally { await page.close(); }
});
it('SA5 failed restore preserves previous paint cache', async () => {
  const page = await create({ saved, settingsFail: true });
  try { await page.goto(url); await page.waitForSelector('jf-top-bar'); expect(await page.evaluate(key => JSON.parse(localStorage.getItem(key)!), key)).toEqual(saved); }
  finally { await page.close(); }
});
it('SA16 SA18 newer-schema bytes survive complete boot and repeated reloads', async () => {
  const future = '{ "version": 999, "future": "preserve these bytes", "unknown": [1,2] }\n';
  const page = await create({ future });
  try { for (let i = 0; i < 3; i++) { await page.goto(url); await page.waitForSelector('jf-top-bar'); expect(await page.evaluate(() => localStorage.getItem('justsearch.userState.v2'))).toBe(future); } }
  finally { await page.close(); }
});
for (const os of ['dark', 'light'] as const) for (const width of [1280, 900, 600]) for (const forced of [false, true]) it(`SA11 X1 X2 ${os} ${width} forced=${forced} enlarged text`, async () => {
  const page = await create({ os });
  try {
    await page.setViewportSize({ width, height: width === 600 ? 400 : 800 }); await page.emulateMedia({ forcedColors: forced ? 'active' : 'none' });
    await page.route(`**${entry}`, route => route.abort()); await page.goto(url); await failure(page, 'entry');
    if (width === 1280 && !forced) await page.screenshot({ path: path.join(tmpdir(), 's5-failure.png') });
    await page.addScriptTag({ content: await readFile(path.resolve('node_modules/axe-core/axe.min.js'), 'utf8') });
    const violations = () => page.evaluate(async () => (await (window as unknown as { axe: { run: () => Promise<{ violations: { id: string }[] }> } }).axe.run()).violations.map(v => v.id));
    expect(await violations()).toEqual([]);
    await page.evaluate(() => { document.documentElement.style.fontSize = '200%'; });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    for (const name of ['Reload', 'Copy report']) { const button = page.getByRole('button', { name, exact: true }); await button.scrollIntoViewIfNeeded(); expect(await button.isVisible()).toBe(true); }
    expect(await violations()).toEqual([]);
  } finally { await page.close(); }
});

it('SA5 live mode contrast and presentation changes survive reload mid-boot', async () => {
  const page = await create();
  try {
    await page.goto(url); await page.waitForSelector('jf-top-bar');
    await page.waitForLoadState('networkidle');
    await page.route('**/api/settings/v2', route => route.fulfill({ json: { ui: { theme: 'dark', highContrast: true } } }));
    await page.evaluate(() => {
      document.dispatchEvent(new CustomEvent('jf-set-appearance', { detail: { theme: 'dark', highContrast: true } }));
      document.dispatchEvent(new CustomEvent('jf-apply-presentation', { detail: { presentationId: 'builtin.core-violet' } }));
    });
    await expect.poll(() => page.evaluate(key => JSON.parse(localStorage.getItem(key) ?? 'null')?.mode, key)).toBe('dark');
    const cache = await page.evaluate(key => JSON.parse(localStorage.getItem(key)!), key);
    expect(await page.evaluate(value => { const probe = document.createElement('span'); probe.style.color = value; document.body.append(probe); const color = getComputedStyle(probe).color; probe.remove(); return color; }, cache.text)).toBe('rgb(255, 255, 255)');
    expect(cache.accent).toBe('oklch(60% 0.18 290)');
    await page.route(`**${entry}`, () => new Promise(() => {}));
    await page.reload({ waitUntil: 'commit' });
    await page.waitForFunction(accent => document.documentElement.getAttribute('data-theme') === 'dark'
      && document.getElementById('boot-frame') && document.documentElement.style.getPropertyValue('--boot-frame-accent') === accent, cache.accent, { timeout: 4000 });
    expect(await page.locator('html').getAttribute('data-theme')).toBe('dark');
    expect(await page.locator('html').evaluate(el => el.style.getPropertyValue('--boot-frame-accent'))).toBe(cache.accent);
    await page.reload({ waitUntil: 'commit' });
    await page.waitForFunction(accent => document.documentElement.getAttribute('data-theme') === 'dark'
      && document.getElementById('boot-frame') && document.documentElement.style.getPropertyValue('--boot-frame-accent') === accent, cache.accent, { timeout: 4000 });
    expect(await page.evaluate(key => JSON.parse(localStorage.getItem(key)!), key)).toEqual(cache);
  } finally { await page.close(); }
}, 15_000);
it('SA5 a second window saves its look without an incidental stale overwrite', async () => {
  const first = await create();
  const second = await first.context().newPage();
  try {
    // Routes and initialization are page scoped; the second window uses the first window's context
    // for shared storage, and the browser-supported api_port path for its isolated HTTP fixtures.
    await first.goto(url); await first.waitForSelector('jf-top-bar');
    await first.waitForLoadState('networkidle');
    await second.route(/\/(api|infra|registry)\//, route => route.fulfill({ json: new URL(route.request().url()).pathname === '/api/registry/surfaces' ? catalog : { ui: { theme: 'light' } } }));
    await second.goto(`${url}?api_port=${new URL(url).port}`); await second.waitForSelector('jf-top-bar');
    await second.waitForLoadState('networkidle');
    await second.route('**/api/settings/v2', route => route.fulfill({ json: { ui: { theme: 'dark' } } }));
    await second.evaluate(() => document.dispatchEvent(new CustomEvent('jf-set-appearance', { detail: { theme: 'dark' } })));
    await expect.poll(() => first.evaluate(key => JSON.parse(localStorage.getItem(key) ?? 'null')?.mode, key)).toBe('dark');
    const before = await first.evaluate(key => localStorage.getItem(key), key);
    const samples = await first.evaluate(() => { document.documentElement.style.overflow = 'hidden'; return window.frameHarness.samples.length; });
    await expect.poll(() => first.evaluate(() => window.frameHarness.samples.length), { timeout: 4000 }).toBeGreaterThanOrEqual(samples + 2);
    expect(await first.evaluate(key => localStorage.getItem(key), key)).toBe(before);
  } finally { await second.close(); await first.close(); }
}, 15_000);
it('SA10 stalled catalog discovery raises failure and late mount replaces it before rendering', async () => {
  const page = await create({ hold: true });
  let release!: () => void;
  const waiting = new Promise<void>(resolve => { release = resolve; });
  try {
    await page.route('**/api/registry/surfaces', async route => { await waiting; await route.fulfill({ json: catalog }); });
    await pauseClockBeforeNavigation(page);
    const request = page.waitForRequest('**/api/registry/surfaces');
    await page.goto(url); await request;
    expect(await page.locator('#boot-frame').count()).toBe(1);
    await page.clock.fastForward(60_001); await failure(page, 'stalled');
    release(); await page.clock.resume(); await page.waitForSelector('jf-shell', { state: 'attached' });
    await expect.poll(() => page.locator('#boot-failure').count()).toBe(0);
    expect(await page.locator('jf-shell').evaluate(shell => (shell as HTMLElement).inert)).toBe(false);
    expect(await page.locator('jf-top-bar').count()).toBe(0);
    await page.evaluate(() => { window.frameHarness.hold = false; }); await page.waitForSelector('jf-top-bar');
  } finally { release(); await page.close(); }
}, 15_000);
it('SA9 optional theme and lazy chunk failures stay optional', async () => {
  const page = await create();
  try {
    await page.route('**/themeManifest-*.js', route => route.fulfill({ status: 404, body: secret }));
    await page.route('**/TokenEditorPlugin-*.js', route => route.fulfill({ contentType: 'text/javascript', body: `throw new Error(${JSON.stringify(secret)})` }));
    await page.goto(url); await page.waitForSelector('jf-top-bar');
    expect(await page.locator('#boot-failure').count()).toBe(0);
  } finally { await page.close(); }
});
it('SA4 denied storage does not block complete boot', async () => {
  const page = await create({ storageThrows: true });
  try { await page.goto(url); await page.waitForSelector('jf-top-bar'); expect(await page.locator('#boot-failure').count()).toBe(0); }
  finally { await page.close(); }
});
for (const value of [null, { ...saved, accent: 123 }, { ...saved, accent: 'x'.repeat(121) }, { ...saved, accent: 'notacolor' }, { ...saved, mode: 'system' }]) it(`SA4 malformed copy ${JSON.stringify(value)} is ignored`, async () => {
  const page = await blocked({ saved: value, os: 'dark' });
  try { expect(await page.locator('#boot-frame').count()).toBe(1); expect(await page.locator('html').getAttribute('data-theme')).toBe('dark'); }
  finally { await page.close(); }
});
for (const value of ['absent', 'valid', 'stale', 'malformed']) it(`SA15 catalog cache ${value} still boots`, async () => {
  const page = await create();
  try {
    await page.addInitScript(({ value, catalog }) => {
      if (value !== 'absent') {
        localStorage.setItem('justsearch.surfaceCatalog.body', value === 'malformed' ? '{invalid' : JSON.stringify({ ...catalog, catalogVersion: value === 'stale' ? 0 : 1 }));
        localStorage.setItem('justsearch.surfaceCatalog.etag', value);
      }
    }, { value, catalog });
    await page.goto(url); await page.waitForSelector('jf-top-bar'); expect(await page.locator('#boot-failure').count()).toBe(0);
  } finally { await page.close(); }
});
it('SA13 SA15 built HTML resolves placeholders and boots with hashed inline CSP', async () => {
  expect(builtHtml).not.toContain('%JUSTSEARCH_VERSION%'); expect(builtHtml).not.toContain('%BOOT_FRAME_TOKENS%');
  expect(builtHtml).not.toContain('/src/main.jsx'); expect(builtHtml).toContain(`content="${version}"`);
  const hashes = [...builtHtml.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => `'sha256-${createHash('sha256').update(m[1]!).digest('base64')}'`);
  expect(hashes.length).toBe(1);
  const page = await create();
  try {
    await page.route(url, async route => { const response = await route.fetch(); await route.fulfill({ response, headers: { ...response.headers(), 'content-security-policy': `default-src 'self'; script-src 'self' ${hashes.join(' ')}; style-src 'self' 'unsafe-inline'; connect-src 'self' http://127.0.0.1:*; img-src 'self' data:; font-src 'self' data:` } }); });
    await page.goto(url); await page.waitForSelector('jf-top-bar');
    expect(await page.locator('#boot-failure').count()).toBe(0);
  } finally { await page.close(); }
});

it('SA5 profile switch and palette changes cache the resulting live colours', async () => {
  const page = await create();
  try {
    await page.goto(url); await page.waitForSelector('jf-top-bar');
    await page.waitForLoadState('networkidle');
    await page.route('**/api/settings/v2', route => route.fulfill({ json: { ui: { theme: 'dark' } } }));
    await page.addScriptTag({ type: 'module', url: new URL(probe, url).href });
    await page.evaluate(async () => {
      window.frameProbe.createProfile('work', 'Work'); window.frameProbe.setActiveProfileId('work');
      await window.frameProbe.applyAppearance({ paletteId: 'core.nord', theme: 'dark' });
    });
    await expect.poll(() => page.evaluate(key => JSON.parse(localStorage.getItem(key) ?? 'null')?.mode, key)).toBe('dark');
    const cache = await page.evaluate(key => JSON.parse(localStorage.getItem(key)!), key);
    expect(cache.accent).toBeTruthy();
    await page.route(`**${entry}`, () => new Promise(() => {})); await page.reload({ waitUntil: 'commit' });
    await page.waitForFunction(accent => document.getElementById('boot-frame') && document.documentElement.style.getPropertyValue('--boot-frame-accent') === accent, cache.accent, { timeout: 4000 });
    expect(await page.locator('#boot-frame').count()).toBe(1);
    expect(await page.locator('html').evaluate(el => el.style.getPropertyValue('--boot-frame-accent'))).toBe(cache.accent);
  } finally { await page.close(); }
});
it('SA5 partly failed palette restore leaves the previous cache byte-identical', async () => {
  const page = await create();
  try {
    await page.goto(url); await page.waitForSelector('jf-top-bar');
    const before = await page.evaluate(key => localStorage.getItem(key), key);
    await page.evaluate(() => {
      const doc = JSON.parse(localStorage.getItem('justsearch.userState.v2')!);
      doc.profiles[doc.activeProfileId].activeThemeId = 'missing.theme';
      localStorage.setItem('justsearch.userState.v2', JSON.stringify(doc));
    });
    await page.route('**/themes/missing.theme.*', route => route.fulfill({ status: 404, body: secret }));
    await page.reload(); await page.waitForSelector('jf-top-bar');
    expect(await page.evaluate(key => localStorage.getItem(key), key)).toBe(before);
  } finally { await page.close(); }
});

it('SA14 factory reset taking over during hand-off beats the pending first render', async () => {
  const page = await create({ hold: true });
  try {
    await pauseClockBeforeNavigation(page); await page.goto(url); await page.waitForSelector('jf-shell', { state: 'attached' });
    await page.addScriptTag({ type: 'module', url: new URL(probe, url).href });
    const blocked = await page.evaluate(async () => {
      window.frameHarness.phase = 'incomplete';
      const ready = await window.frameProbe.gateFactoryReset(document.getElementById('root')!);
      window.frameHarness.hold = false;
      return ready;
    });
    expect(blocked).toBe(false);
    await page.clock.fastForward(60_001);
    expect(await page.locator('#root > jf-factory-reset-recovery').count()).toBe(1);
    expect(await page.locator('#boot-frame, #boot-failure, jf-shell').count()).toBe(0);
  } finally { await page.close(); }
});
for (const cover of ['frame', 'failure']) for (const takeover of ['recovery', 'reset']) it(`SA6 SA14 ${takeover} replaces ${cover} and its keyboard action works`, async () => {
  const page = await heldShell();
  try {
    if (cover === 'failure') await page.clock.fastForward(60_001);
    if (takeover === 'recovery') await page.evaluate(() => window.frameHarness.emit({ schemaVersion: 1,
      kind: 'engine-supervisor-state.v1', supervisor: 'tauri', state: 'exhausted', incarnation: 1, restartCount: 3, maxRestartAttempts: 3 }));
    else await page.evaluate(async () => {
      window.frameHarness.phase = 'incomplete';
      await window.frameProbe.gateFactoryReset(document.getElementById('root')!);
    });
    expect(await page.locator('#boot-frame, #boot-failure, jf-shell').count()).toBe(0);
    const button = page.getByRole('button', { name: takeover === 'recovery' ? 'Restart JustSearch' : 'Retry reset', exact: true });
    await button.focus(); await page.keyboard.press('Enter');
    await expect.poll(() => page.evaluate(() => window.frameHarness.calls)).toContain(takeover === 'recovery' ? 'restart_engine' : 'retry_factory_reset');
    await page.evaluate(() => { window.frameHarness.renderHold = false; }); await page.clock.resume();
    await expect.poll(() => page.evaluate(() => window.frameHarness.renders), { timeout: 4000 }).toBeGreaterThan(0);
    expect(await page.locator(takeover === 'recovery' ? '#root > jf-engine-recovery' : '#root > jf-factory-reset-recovery').count()).toBe(1);
  } finally { await page.close(); }
});
it('SA12 refused clipboard reveals only the fixed report ready to select', async () => {
  const page = await create();
  try {
    await page.route(`**${entry}`, route => route.abort()); await page.goto(url);
    await expect.poll(() => page.locator('#boot-failure').count(), { timeout: 2000 }).toBe(1);
    await page.evaluate(secret => Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: () => Promise.reject(new Error(secret)) } }), secret);
    await page.getByRole('button', { name: 'Copy report' }).click();
    await page.waitForSelector('#boot-report');
    const report = await page.locator('#boot-report').inputValue();
    expect(JSON.parse(report).failureKind).toBe('entry'); expect(report).not.toContain('SECRET');
    expect(await page.locator('#boot-report').evaluate(el => (el as HTMLTextAreaElement).selectionEnd - (el as HTMLTextAreaElement).selectionStart)).toBe(report.length);
    await page.getByRole('button', { name: 'Reload', exact: true }).focus();
    await Promise.all([page.waitForEvent('framenavigated'), page.keyboard.press('Enter')]);
    await expect.poll(() => page.locator('#boot-failure').count()).toBe(1);
  } finally { await page.close(); }
});
for (const os of ['light', 'dark'] as const) it(`X1 X2 ${os} frame colours and bounds with no application stylesheet`, async () => {
  const page = await blocked({ os });
  try {
    expect(await page.locator('#boot-frame').count()).toBe(1);
    for (const width of [1280, 900, 600]) {
      await page.setViewportSize({ width, height: width === 600 ? 400 : 800 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    }
    const contrast = await page.evaluate(() => {
      const ctx = document.createElement('canvas').getContext('2d')!;
      function rgba(value: string) { ctx.clearRect(0, 0, 1, 1); ctx.fillStyle = value; ctx.fillRect(0, 0, 1, 1); return [...ctx.getImageData(0, 0, 1, 1).data]; }
      function lum(values: number[]) { const linear = values.slice(0, 3).map(v => { v /= 255; return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }); return linear[0]! * .2126 + linear[1]! * .7152 + linear[2]! * .0722; }
      const bar = document.querySelector('.boot-topbar')!;
      const bg = rgba(getComputedStyle(bar).backgroundColor);
      return [...bar.querySelectorAll('.boot-brand, .boot-areas span, .boot-pill, .boot-symbol')].map(el => {
        const fg = rgba(getComputedStyle(el).color), alpha = fg[3]! / 255;
        const combined = fg.map((v, i) => i < 3 ? v * alpha + bg[i]! * (1 - alpha) : v);
        const a = lum(combined), b = lum(bg);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
      });
    });
    for (const ratio of contrast) expect(ratio).toBeGreaterThanOrEqual(4.5);
    await page.emulateMedia({ forcedColors: 'active', reducedMotion: 'reduce' });
    expect(await page.locator('#boot-frame').evaluate(el => getComputedStyle(el).animationName)).toBe('none');
  } finally { await page.close(); }
});

it('SA7 SA10 deadline survives a long connecting interval before the frame returns', async () => {
  const page = await create({ discoveryHold: true });
  let release!: () => void;
  const waiting = new Promise<void>(resolve => { release = resolve; });
  try {
    await page.route('**/api/registry/surfaces', async route => { await waiting; await route.fulfill({ json: catalog }); });
    await pauseClockBeforeNavigation(page); await page.goto(url); await page.waitForSelector('jf-engine-recovery');
    await page.clock.fastForward(60_001);
    expect(await page.locator('#boot-failure').count()).toBe(0);
    expect(await page.locator('#root > jf-engine-recovery').count()).toBe(1);
    const request = page.waitForRequest('**/api/registry/surfaces');
    await page.evaluate(() => { window.frameHarness.discoveryHold = false; });
    await page.clock.runFor(20); await request;
    await failure(page, 'stalled');
    release(); await page.clock.resume(); await page.waitForSelector('jf-top-bar');
    await expect.poll(() => page.locator('#boot-frame, #boot-failure').count()).toBe(0);
  } finally { release(); await page.close(); }
}, 15_000);

// Window layer (SW1-SW9). The native double answers window commands; Tauri's real drag script serves drag regions.
const exhausted = { state: 'exhausted', restartCount: 3 };
type Surface = { name: string; open: () => Promise<Page>; handOff?: boolean };
const loaded = (selector: string) => async (page: Page) => { await page.waitForSelector(selector, { state: 'attached' }); return page; };
const surfaces: Surface[] = [
  { name: 'start-up frame', open: () => blocked() },
  { name: 'entry failure', open: async () => { const page = await create(); await page.route(`**${entry}`, route => route.abort()); await page.goto(url); return loaded('#boot-failure')(page); } },
  { name: 'evaluation failure', open: async () => {
    const page = await create();
    await page.route(`**${entry}`, async route => { const response = await route.fetch(); await route.fulfill({ response, body: `import '/fault.js';\n${await response.text()}` }); });
    await page.route('**/fault.js', route => route.fulfill({ contentType: 'text/javascript', body: 'throw new Error("fault")' }));
    await page.goto(url); return loaded('#boot-failure')(page);
  } },
  { name: 'stalled failure', open: async () => { const page = await create({ hold: true }); await pauseClockBeforeNavigation(page); await page.goto(url); await page.waitForSelector('jf-shell', { state: 'attached' }); await page.clock.fastForward(60_001); return loaded('#boot-failure')(page); } },
  { name: 'connecting', open: async () => { const page = await create({ discoveryHold: true }); await page.goto(url); return loaded('#root > jf-engine-recovery')(page); } },
  { name: 'engine terminal', open: async () => { const page = await create({ supervisor: exhausted }); await page.goto(url); return loaded('#root > jf-engine-recovery')(page); } },
  { name: 'engine not ready', open: async () => { const page = await create({ supervisor: { notReady: 'The index is being checked.' } }); await page.goto(url); return loaded('#root > jf-engine-recovery')(page); } },
  { name: 'Linux update hold', open: async () => { const page = await create({ supervisor: { state: 'stopped', reason: 'upgrade' } }); await page.goto(url); return loaded('#root > jf-engine-recovery')(page); } },
  { name: 'reset busy', open: async () => { const page = await create({ phase: 'awaiting_storage' }); await page.goto(url); return loaded('#root > jf-factory-reset-recovery')(page); } },
  { name: 'reset error', open: async () => { const page = await create({ resetThrows: true }); await page.goto(url); return loaded('#root > jf-factory-reset-recovery')(page); } },
  { name: 'reset complete', open: async () => { const page = await create({ phase: 'complete' }); await page.goto(url); return loaded('#root > jf-factory-reset-recovery')(page); } },
  { name: 'running app', open: async () => { const page = await create(); await page.goto(url); await page.waitForSelector('jf-top-bar .more-trigger'); await expect.poll(() => page.locator('#boot-frame').count()).toBe(0); return page; } },
  { name: 'held hand-off', open: () => heldShell(), handOff: true },
];
const controlLabels = (page: Page) => page.locator('#window-controls button').evaluateAll(els => els.map(el => el.getAttribute('aria-label')));
function control(page: Page, name: string) {
  // The maximize control is named for what it does next; its own test asserts which.
  return page.getByRole('region', { name: 'Window' }).getByRole('button', name === 'Maximize' ? { name: /^(Maximize|Restore)$/ } : { name, exact: true });
}
/** Window commands that change the window; read-only state queries (is_*) are left out. */
async function windowCalls(page: Page, from = 0) {
  return page.evaluate(from => window.frameHarness.calls.slice(from).filter(call => call.startsWith('plugin:window|') && !call.startsWith('plugin:window|is_')), from);
}
const callCount = (page: Page) => page.evaluate(() => window.frameHarness.calls.length);
/** Deepest element at a point across shadow roots, as a real pointer would hit it. */
async function hitAt(page: Page, x: number, y: number) {
  return page.evaluate(([x, y]) => {
    let el = document.elementFromPoint(x, y);
    while (el?.shadowRoot) { const inner = el.shadowRoot.elementFromPoint(x, y); if (!inner || inner === el) break; el = inner; }
    return el ? `${el.localName}${el.id ? '#' + el.id : ''}${el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\s+/).join('.') : ''}` : null;
  }, [x, y] as const);
}
/** A primary-button mousedown dispatched at the deepest hit element; returns the drag commands it caused. */
async function mousedownAt(page: Page, x: number, y: number, detail = 1) {
  return page.evaluate(([x, y, detail]) => {
    let el = document.elementFromPoint(x, y);
    while (el?.shadowRoot) { const inner = el.shadowRoot.elementFromPoint(x, y); if (!inner || inner === el) break; el = inner; }
    const from = window.frameHarness.calls.length;
    el!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, composed: true, cancelable: true, button: 0, detail, clientX: x, clientY: y }));
    return window.frameHarness.calls.slice(from).filter(call => /\|(start_dragging|internal_toggle_maximize)$/.test(call));
  }, [x, y, detail] as const);
}
/** Visible interactive elements in the composed tree whose box starts inside the top area. */
async function topInteractive(page: Page) {
  return page.evaluate(() => {
    const out: { name: string; x: number; y: number; right: number; bottom: number; left: number; top: number }[] = [];
    const selector = 'button, a[href], input, select, textarea, summary, label, [role="button"], [role^="menuitem"], [role="tab"], [tabindex]:not([tabindex="-1"]), [role="menu"], .group-label';
    // An inert subtree (the app beneath the start-up frame) has no reachable controls.
    const inert = (el: Element) => { for (let node: Element | null = el; node; node = node.parentElement ?? ((node.getRootNode() as ShadowRoot).host ?? null)) if ((node as HTMLElement).inert) return true; return false; };
    const limit = parseFloat(getComputedStyle(document.documentElement).fontSize) * 3.5;
    const visit = (root: Document | ShadowRoot) => root.querySelectorAll('*').forEach(el => {
      if (el.matches(selector)) {
        const r = el.getBoundingClientRect();
        if (r.width > 0 && r.height > 0 && r.top < limit && getComputedStyle(el).visibility !== 'hidden' && !inert(el)) {
          out.push({ name: el.getAttribute('aria-label') ?? el.textContent!.trim().slice(0, 30) ?? el.localName, x: r.left + r.width / 2, y: r.top + r.height / 2, left: r.left, right: r.right, top: r.top, bottom: r.bottom });
          // A menu's padding and labels are part of the menu, never a drag region.
          if (el.matches('[role="menu"]')) out.push({ name: 'menu padding', x: r.left + 2, y: r.top + 2, left: r.left, right: r.right, top: r.top, bottom: r.bottom });
        }
      }
      if (el.shadowRoot) visit(el.shadowRoot);
    });
    visit(document);
    return out;
  });
}
async function expectHandOffHeld(page: Page) {
  expect({ frame: await page.locator('#boot-frame').count(), inert: await page.locator('jf-shell').evaluate(el => (el as HTMLElement).inert),
    inputs: await page.evaluate(() => window.frameHarness.inputs) }).toEqual({ frame: 1, inert: true, inputs: [] });
}

for (const surface of surfaces) it(`SW1 SW2 SW3 SW7 window controls send their command on ${surface.name}, by pointer and keyboard`, async () => {
  const page = await surface.open();
  try {
    expect(await controlLabels(page)).toEqual(['Minimize', 'Maximize', 'Close']);
    if (surface.handOff) await page.evaluate(() => { window.frameHarness.inputs.length = 0; });
    for (const [name, command] of [['Minimize', 'minimize'], ['Maximize', 'toggle_maximize'], ['Close', 'close']] as const) {
      for (const how of ['pointer', 'Enter', 'Space'] as const) {
        const from = await callCount(page);
        const button = control(page, name);
        if (how === 'pointer') { const box = (await button.boundingBox())!; await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2); }
        else { await button.focus(); await page.keyboard.press(how); }
        // Exactly one native command per activation; the tray close path is the platform close request.
        await expect.poll(() => windowCalls(page, from), { timeout: 2000 }).toEqual([`plugin:window|${command}`]);
      }
    }
    if (surface.handOff) await expectHandOffHeld(page);
    // SW7: no control or listener asks for the window to be shown, focused or restored.
    expect([...new Set(await windowCalls(page))].sort()).toEqual(['plugin:window|close', 'plugin:window|minimize', 'plugin:window|toggle_maximize']);
  } finally { await page.close(); }
}, 20_000);

for (const surface of surfaces) it(`SW1 SW2 top area of ${surface.name} moves the window; interactive controls never start a drag`, async () => {
  const page = await surface.open();
  try {
    if (surface.handOff) await page.evaluate(() => { window.frameHarness.inputs.length = 0; });
    const root = parseFloat(await page.evaluate(() => getComputedStyle(document.documentElement).fontSize));
    // An empty point at the start of the top area, beside the brand where there is one.
    const point = { x: root * 0.5, y: root * 0.5 };
    let from = await callCount(page);
    await page.mouse.move(point.x, point.y); await page.mouse.down(); await page.mouse.up();
    expect(await windowCalls(page, from)).toEqual(['plugin:window|start_dragging']);
    from = await callCount(page);
    await page.mouse.dblclick(point.x, point.y);
    expect(await windowCalls(page, from)).toEqual(['plugin:window|start_dragging', 'plugin:window|internal_toggle_maximize']);
    const check = async (state: string) => {
      const targets = await topInteractive(page);
      expect(targets.length, state).toBeGreaterThan(0);
      for (const target of targets) expect({ state, target: target.name, drag: await mousedownAt(page, target.x, target.y), detail: await mousedownAt(page, target.x, target.y, 2) })
        .toEqual({ state, target: target.name, drag: [], detail: [] });
    };
    await check('closed');
    if (surface.name === 'running app') {
      await page.locator('jf-top-bar .more-trigger').click();
      await page.waitForSelector('jf-top-bar [role="menu"]');
      await check('more menu');
      await page.keyboard.press('Escape');
      await page.setViewportSize({ width: 600, height: 400 });
      await page.locator('jf-top-bar .area-trigger').click();
      await page.waitForSelector('jf-top-bar [role="menu"]');
      await check('areas menu');
    }
    if (surface.handOff) await expectHandOffHeld(page);
  } finally { await page.close(); }
}, 20_000);

it('SW2 the vendored Tauri drag script is the tauri version Cargo.lock pins, unchanged', async () => {
  const [header, upstream] = (await readFile(dragFixture, 'utf8')).split(dragMarker);
  const locked = (await readFile(path.resolve('../shell/src-tauri/Cargo.lock'), 'utf8')).match(/name = "tauri"\nversion = "([^"]+)"/)![1];
  expect(header!.match(/^\/\/ Version: tauri (\S+)$/m)?.[1]).toBe(locked);
  expect(createHash('sha256').update(upstream!).digest('hex')).toBe(header!.match(/^\/\/ Upstream sha256: ([0-9a-f]{64})$/m)?.[1]);
});
it('SW2 controls and moving keep working after the hand-off releases the app', async () => {
  const page = await heldShell();
  try {
    expect(await controlLabels(page)).toEqual(['Minimize', 'Maximize', 'Close']);
    await control(page, 'Minimize').click();
    await releaseShell(page);
    expect(await page.locator('jf-shell').evaluate(el => (el as HTMLElement).inert)).toBe(false);
    const from = await callCount(page);
    await control(page, 'Close').click();
    await page.mouse.move(8, 8); await page.mouse.down(); await page.mouse.up();
    expect(await windowCalls(page, from)).toEqual(['plugin:window|close', 'plugin:window|start_dragging']);
    await shellWorksAfterCover(page);
  } finally { await page.close(); }
});
it('R1 Settings opened beneath the start-up frame stays modal after hand-off and closes by X and Escape', async () => {
  const page = await heldShell();
  try {
    const settings = page.locator('jf-settings-window dialog');
    // Settings restored at launch opens while the frame still covers the app.
    await page.locator('jf-settings-window').evaluate(el => { (el as HTMLElement & { open: boolean }).open = true; });
    await expect.poll(() => settings.evaluate(el => (el as HTMLDialogElement).open)).toBe(true);
    // The hand-off must never take the application out of the document (that drops the top layer).
    await page.evaluate(() => {
      const removed: string[] = (window as unknown as { removedFromRoot: string[] }).removedFromRoot = [];
      new MutationObserver(records => { for (const record of records) for (const node of record.removedNodes) removed.push(node.nodeName.toLowerCase()); })
        .observe(document.getElementById('root')!, { childList: true });
    });
    await releaseShell(page);
    expect(await page.evaluate(() => (window as unknown as { removedFromRoot: string[] }).removedFromRoot.filter(name => !name.startsWith('#')))).toEqual(['div']);
    await expect.poll(() => page.locator('jf-shell').evaluate(el => (el as HTMLElement).inert)).toBe(false);
    expect(await settings.evaluate(el => el.matches(':modal') && getComputedStyle(el, '::backdrop').backgroundColor !== 'rgba(0, 0, 0, 0)')).toBe(true);
    // In the top layer, the X is the topmost element at its own centre (not a window control).
    const x = page.locator('jf-settings-window [data-testid="settings-window-close"]');
    expect(await x.evaluate(el => { const r = el.getBoundingClientRect();
      return el.contains((el.getRootNode() as ShadowRoot).elementFromPoint(r.x + r.width / 2, r.y + r.height / 2)); })).toBe(true);
    await x.click();
    await expect.poll(() => settings.evaluate(el => (el as HTMLDialogElement).open)).toBe(false);
    await page.locator('jf-settings-window').evaluate(el => { (el as HTMLElement & { open: boolean }).open = true; });
    await expect.poll(() => settings.evaluate(el => el.matches(':modal'))).toBe(true);
    await page.keyboard.press('Escape');
    await expect.poll(() => settings.evaluate(el => (el as HTMLDialogElement).open)).toBe(false);
  } finally { await page.close(); }
});

for (const where of ['running app', 'entry failure', 'start-up frame']) it(`SW4 maximize control follows the real window state on ${where}`, async () => {
  const page = await (where === 'start-up frame' ? blocked({ maximized: true }) : (async () => {
    const page = await create({ maximized: true });
    if (where === 'entry failure') await page.route(`**${entry}`, route => route.abort());
    await page.goto(url); await page.waitForSelector(where === 'entry failure' ? '#boot-failure' : 'jf-top-bar');
    return page;
  })());
  try {
    // Saved state restored the window maximized before it was shown.
    await expect.poll(() => controlLabels(page)).toEqual(['Minimize', 'Restore', 'Close']);
    expect(await page.locator('#window-controls [aria-label="Restore"]').getAttribute('title')).toBe('Restore');
    // Another means (keyboard, desktop, compositor) restores it; the window resizes.
    await page.evaluate(() => { window.frameHarness.maximized = false; });
    await page.setViewportSize({ width: 1200, height: 760 });
    await expect.poll(() => controlLabels(page)).toEqual(['Minimize', 'Maximize', 'Close']);
    // The control maximizes; the window's resize reports the new state.
    await control(page, 'Maximize').click();
    await page.setViewportSize({ width: 1280, height: 800 });
    await expect.poll(() => controlLabels(page)).toEqual(['Minimize', 'Restore', 'Close']);
    // Double-click on the top area restores it.
    await page.mouse.dblclick(8, 8);
    await page.setViewportSize({ width: 1200, height: 760 });
    await expect.poll(() => controlLabels(page)).toEqual(['Minimize', 'Maximize', 'Close']);
    expect(await page.evaluate(() => window.frameHarness.maximized)).toBe(false);
  } finally { await page.close(); }
});

for (const surface of [surfaces[0]!, surfaces[1]!, surfaces[11]!]) it(`SW9 refused window operations on ${surface.name} do nothing harmful and raise no error`, async () => {
  const page = await (surface.name === 'start-up frame' ? blocked({ windowRejects: true }) : (async () => {
    const page = await create({ windowRejects: true });
    if (surface.name === 'entry failure') await page.route(`**${entry}`, route => route.abort());
    await page.goto(url); await page.waitForSelector(surface.name === 'entry failure' ? '#boot-failure' : 'jf-top-bar');
    return page;
  })());
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  try {
    expect(await controlLabels(page)).toEqual(['Minimize', 'Maximize', 'Close']);
    for (const name of ['Minimize', 'Maximize', 'Close']) await control(page, name).click();
    await page.mouse.dblclick(8, 8);
    await expect.poll(() => windowCalls(page)).toHaveLength(5);
    await page.waitForTimeout(100);
    // Only the refusals matter here; unrelated harness rejections (no event-plugin internals) are not this check's.
    const rejections = (await page.evaluate(() => window.frameHarness.rejections)).filter(reason => reason.includes('compositor'));
    expect({ errors, rejections, labels: await controlLabels(page) })
      .toEqual({ errors: [], rejections: [], labels: ['Minimize', 'Maximize', 'Close'] });
  } finally { await page.close(); }
});

it('SW6 browser runtime has no window layer, drag regions or native commands on any surface', async () => {
  for (const state of ['frame', 'failure', 'app'] as const) {
    const page = state === 'frame' ? await blocked({ desktop: false }) : await create({ desktop: false });
    const errors: string[] = [];
    page.on('pageerror', error => errors.push(error.message));
    try {
      if (state === 'failure') { await page.route(`**${entry}`, route => route.abort()); await page.goto(url); await page.waitForSelector('#boot-failure'); }
      if (state === 'app') { await page.goto(`${url}?api_port=${new URL(url).port}`); await page.waitForSelector('jf-top-bar .more-trigger'); await expect.poll(() => page.locator('#boot-frame').count()).toBe(0); }
      const found = await page.evaluate(() => {
        const out: string[] = [];
        const visit = (root: Document | ShadowRoot) => root.querySelectorAll('*').forEach(el => {
          if (el.hasAttribute('data-tauri-drag-region') || el.id === 'window-controls') out.push(el.localName);
          if (el.shadowRoot) visit(el.shadowRoot);
        });
        visit(document);
        return { out, reserved: getComputedStyle(document.documentElement).getPropertyValue('--window-controls-inline').trim(), attribute: document.documentElement.hasAttribute('data-window-controls') };
      });
      expect({ state, found, errors }).toEqual({ state, found: { out: [], reserved: '', attribute: false }, errors: [] });
      expect(await page.getByRole('region', { name: 'Window' }).count()).toBe(0);
    } finally { await page.close(); }
  }
}, 20_000);

it('SW5 every control is reachable by keyboard in order, with a visible focus ring, after the app and on the failure page', async () => {
  const page = await create();
  try {
    await page.goto(url); await page.waitForSelector('jf-top-bar .more-trigger'); await expect.poll(() => page.locator('#boot-frame').count()).toBe(0);
    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
    const order: (string | null)[] = [];
    for (let i = 0; i < 3; i++) { await page.keyboard.press('Shift+Tab'); order.push(await page.evaluate(() => document.activeElement!.getAttribute('aria-label'))); }
    expect(order).toEqual(['Close', 'Maximize', 'Minimize']);
    for (const name of ['Minimize', 'Maximize', 'Close']) {
      await control(page, name).focus();
      expect(await control(page, name).evaluate(el => { const s = getComputedStyle(el); return { style: s.outlineStyle, width: parseFloat(s.outlineWidth) >= 2, visible: el.matches(':focus-visible') }; }))
        .toEqual({ style: 'solid', width: true, visible: true });
    }
  } finally { await page.close(); }
  const failed = await create();
  try {
    await failed.route(`**${entry}`, route => route.abort()); await failed.goto(url); await failed.waitForSelector('#boot-failure');
    expect(await failed.locator(':focus').textContent()).toBe('Reload');
    const order: (string | null)[] = [];
    for (let i = 0; i < 4; i++) { await failed.keyboard.press('Tab'); order.push(await failed.evaluate(() => document.activeElement!.getAttribute('aria-label') ?? document.activeElement!.textContent)); }
    expect(order).toEqual(['Copy report', 'Minimize', 'Maximize', 'Close']);
  } finally { await failed.close(); }
});

const roomSurfaces = [surfaces[1]!, surfaces[5]!, surfaces[10]!, surfaces[11]!, surfaces[0]!];
for (const os of ['dark', 'light'] as const) for (const forced of [false, true]) for (const surface of roomSurfaces) it(`SW5 X1 X2 ${surface.name} ${os} forced=${forced}: controls fit at every size and enlarged text, axe clean`, async () => {
  const page = await (async () => {
    if (surface.name === 'start-up frame') return blocked({ os });
    const page = await create({ os, supervisor: surface.name === 'engine terminal' ? exhausted : undefined, phase: surface.name === 'reset complete' ? 'complete' : undefined });
    if (surface.name === 'entry failure') await page.route(`**${entry}`, route => route.abort());
    await page.goto(url);
    await page.waitForSelector({ 'entry failure': '#boot-failure', 'engine terminal': '#root > jf-engine-recovery', 'reset complete': '#root > jf-factory-reset-recovery', 'running app': 'jf-top-bar .more-trigger' }[surface.name]!);
    if (surface.name === 'running app') await expect.poll(() => page.locator('#boot-frame').count()).toBe(0);
    return page;
  })();
  try {
    expect(await controlLabels(page)).toEqual(['Minimize', 'Maximize', 'Close']);
    await page.emulateMedia({ forcedColors: forced ? 'active' : 'none', reducedMotion: 'reduce' });
    if (surface.name !== 'start-up frame') await page.addScriptTag({ content: await readFile(path.resolve('node_modules/axe-core/axe.min.js'), 'utf8') });
    const sizes: Record<string, string> = {};
    for (const width of [1280, 900, 600]) for (const text of ['100%', '200%']) {
      await page.setViewportSize({ width, height: width === 600 ? 400 : 800 });
      await page.evaluate(text => { document.documentElement.style.fontSize = text; }, text);
      const state = `${width} ${text}`;
      const layer = (await page.locator('#window-controls').boundingBox())!;
      expect({ state, inside: layer.x >= 0 && layer.x + layer.width <= width + 0.5 && layer.y >= 0, overflow: await page.evaluate(() => document.documentElement.scrollWidth > innerWidth) })
        .toEqual({ state, inside: true, overflow: false });
      // Nothing interactive in the top area is covered: each control's centre still hits itself.
      for (const target of await topInteractive(page)) {
        const overlaps = target.left < layer.x + layer.width && target.right > layer.x && target.top < layer.y + layer.height && target.bottom > layer.y;
        const inLayer = ['Minimize', 'Maximize', 'Close'].includes(target.name);
        expect({ state, target: target.name, overlaps: inLayer ? false : overlaps, inView: target.right <= width + 0.5 && target.left >= -0.5 }).toEqual({ state, target: target.name, overlaps: false, inView: true });
      }
      // The room the bar reserves leaves its own controls apart from each other, as in the browser bar.
      const targets = (await topInteractive(page)).filter(target => target.name !== 'menu padding');
      const crowded = targets.flatMap((a, i) => targets.slice(i + 1).filter(b => a.left < b.right - 1 && a.right > b.left + 1 && a.top < b.bottom - 1 && a.bottom > b.top + 1).map(b => `${a.name} × ${b.name}`));
      expect({ state, crowded }).toEqual({ state, crowded: [] });
      // Recovery actions stay clear of the layer and reachable (the app's top bar is checked above).
      for (const button of surface.name === 'running app' ? [] : await page.locator('#root button, #root jf-button').all()) {
        if (!await button.isVisible()) continue;
        await button.scrollIntoViewIfNeeded();
        const box = (await button.boundingBox())!;
        const hit = await hitAt(page, box.x + box.width / 2, box.y + box.height / 2);
        expect({ state, hit: hit?.startsWith('button') || hit?.startsWith('jf-button') || false, clear: box.y >= layer.y + layer.height - 0.5 || box.x + box.width <= layer.x })
          .toEqual({ state, hit: true, clear: true });
      }
      for (const name of ['Minimize', 'Maximize', 'Close']) {
        const button = control(page, name);
        expect({ state, name, visible: await button.isVisible(), transition: await button.evaluate(el => getComputedStyle(el).transitionDuration) }).toEqual({ state, name, visible: true, transition: '0s' });
        // Named icon buttons keep one size at any text size, never below the 24 × 24 px target.
        const box = (await button.boundingBox())!;
        sizes[name] ??= `${box.width}×${box.height}`;
        expect({ state, name, size: `${box.width}×${box.height}`, target: box.width >= 24 && box.height >= 24 }).toEqual({ state, name, size: sizes[name], target: true });
        if (!forced) {
          // Glyph contrast against the surface painted beneath the layer.
          const ratio = await button.evaluate(el => {
            const ctx = document.createElement('canvas').getContext('2d')!;
            const rgba = (value: string) => { ctx.clearRect(0, 0, 1, 1); ctx.fillStyle = value; ctx.fillRect(0, 0, 1, 1); return [...ctx.getImageData(0, 0, 1, 1).data]; };
            const lum = (v: number[]) => { const l = v.slice(0, 3).map(c => { c /= 255; return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; }); return l[0]! * .2126 + l[1]! * .7152 + l[2]! * .0722; };
            const r = el.getBoundingClientRect();
            el.closest<HTMLElement>('#window-controls')!.style.visibility = 'hidden';
            let under = document.elementFromPoint(r.left + 1, r.top + 1);
            while (under?.shadowRoot) { const inner = under.shadowRoot.elementFromPoint(r.left + 1, r.top + 1); if (!inner || inner === under) break; under = inner; }
            el.closest<HTMLElement>('#window-controls')!.style.visibility = '';
            let bg = [0, 0, 0, 0];
            for (let node: Element | null = under; node && bg[3] === 0; node = node.parentElement ?? ((node.getRootNode() as ShadowRoot).host ?? null)) bg = rgba(getComputedStyle(node).backgroundColor);
            const fg = rgba(getComputedStyle(el).color);
            const a = lum(fg), b = lum(bg);
            return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
          });
          expect({ state, name, ok: ratio >= 4.5 }).toEqual({ state, name, ok: true });
        }
      }
      if (surface.name !== 'start-up frame') {
        // The layer itself is clean, and it adds no page violation. Recovery and reset pages carry
        // their own landmark findings from before the layer; those are compared, not hidden.
        const audit = await page.evaluate(async () => {
          type Axe = { run: (context?: unknown) => Promise<{ violations: { id: string }[] }> };
          const axe = (window as unknown as { axe: Axe }).axe;
          const ids = async (context?: unknown) => (await axe.run(context ?? document)).violations.map(v => v.id).sort();
          const layer = document.getElementById('window-controls')!;
          const own = await ids({ include: [['#window-controls']] });
          const withLayer = await ids();
          const bands = [...document.querySelectorAll<HTMLElement>('.boot-window-top')];
          const next = layer.nextSibling;
          layer.remove(); bands.forEach(band => { band.hidden = true; });
          const without = await ids();
          next ? next.before(layer) : document.body.append(layer); bands.forEach(band => { band.hidden = false; });
          return { own, added: withLayer.filter(id => !without.includes(id)), withLayer };
        });
        expect({ state, own: audit.own, added: audit.added }).toEqual({ state, own: [], added: [] });
        // The failure page was fully clean before the layer (SA11); the app keeps its measured-axe baseline.
        if (surface.name === 'entry failure') expect({ state, all: audit.withLayer }).toEqual({ state, all: [] });
      }
    }
    // Forced colours: the ring is the surface's text colour, drawn outside the control.
    if (forced) expect(await control(page, 'Close').evaluate(el => { el.focus(); const s = getComputedStyle(el); return { color: s.outlineColor, outside: parseFloat(s.outlineOffset) > 0 }; }))
      .toEqual({ color: await page.evaluate(() => { const probe = document.createElement('span'); probe.style.color = 'CanvasText'; document.body.append(probe); const c = getComputedStyle(probe).color; probe.remove(); return c; }), outside: true });
  } finally { await page.close(); }
}, 30_000);

// X1 SW5: the layer takes its colours from the surface beneath it, for every shipped palette and state.
const shippedPalettes = (JSON.parse(readFileSync(path.resolve('public/themes/manifest.json'), 'utf8')) as { themes: { id: string }[] }).themes.map(theme => theme.id);
type ControlState = { state: string; measure: { icon: boolean; ring: boolean }; pseudo: string[] };
/**
 * The six states, with the pseudo-classes keyboard and pointer input give a control in each:
 * keyboard focus shows the ring, the pointer over it hovers, a primary button held on it presses
 * it, and keyboard focus is kept while the pointer hovers or presses.
 */
const controlStates: ControlState[] = [
  { state: 'icon', measure: { icon: true, ring: false }, pseudo: [] },
  { state: 'focus ring', measure: { icon: false, ring: true }, pseudo: ['focus', 'focus-visible'] },
  { state: 'hover', measure: { icon: true, ring: false }, pseudo: ['hover'] },
  { state: 'pressed', measure: { icon: true, ring: false }, pseudo: ['hover', 'active'] },
  { state: 'focus ring + hover', measure: { icon: true, ring: true }, pseudo: ['focus', 'focus-visible', 'hover'] },
  { state: 'focus ring + pressed', measure: { icon: true, ring: true }, pseudo: ['focus', 'focus-visible', 'hover', 'active'] },
];
const controlNames = ['Minimize', 'Maximize', 'Close'];
/** Each control's colours as painted, in document order (Minimize, Maximize or Restore, Close). */
function measureControls(ring: boolean) {
  return [...document.querySelectorAll<HTMLElement>('#window-controls button')].map(el => {
    const ctx = document.createElement('canvas').getContext('2d', { willReadFrequently: true })!;
    const rgba = (value: string) => { ctx.clearRect(0, 0, 1, 1); ctx.fillStyle = value; ctx.fillRect(0, 0, 1, 1); return [...ctx.getImageData(0, 0, 1, 1).data].map((v, i) => i < 3 ? v : v / 255); };
    const over = (top: number[], bottom: number[]) => [...top.slice(0, 3).map((v, i) => v * top[3]! + bottom[i]! * (1 - top[3]!)), 1];
    const lum = (v: number[]) => { const l = v.slice(0, 3).map(c => { c /= 255; return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; }); return l[0]! * .2126 + l[1]! * .7152 + l[2]! * .0722; };
    const ratio = (x: number[], y: number[]) => { const a = lum(x), b = lum(y); return (Math.max(a, b) + .05) / (Math.min(a, b) + .05); };
    const css = (v: number[]) => `rgb(${v.slice(0, 3).map(Math.round).join(', ')})`;
    const own = getComputedStyle(el);
    const fill = rgba(own.backgroundColor);
    const visible = !ring || (el.matches(':focus-visible') && own.outlineStyle !== 'none' && parseFloat(own.outlineWidth) > 0);
    // What the surfaces beneath the layer paint at the control's centre, over the page canvas.
    const layer = el.closest<HTMLElement>('#window-controls')!;
    const r = el.getBoundingClientRect();
    layer.style.visibility = 'hidden';
    let under = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
    while (under?.shadowRoot) { const inner = under.shadowRoot.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2); if (!inner || inner === under) break; under = inner; }
    layer.style.visibility = '';
    const chain: number[][] = [];
    for (let node: Element | null = under; node; node = node.parentElement ?? ((node.getRootNode() as ShadowRoot).host ?? null)) chain.push(rgba(getComputedStyle(node).backgroundColor));
    const probe = document.createElement('span'); probe.style.color = 'Canvas'; document.documentElement.append(probe);
    let beside = rgba(getComputedStyle(probe).color); probe.remove();
    for (const paint of chain.reverse()) beside = over(paint, beside);
    const filled = over(fill, beside);
    const icon = over(rgba(own.color), filled);
    // The ring as painted: over the fill when inset, over the surface when drawn outside the control.
    const outline = over(rgba(own.outlineColor), parseFloat(own.outlineOffset) < 0 ? filled : beside);
    return { visible, icon: ratio(icon, filled), ringFill: ratio(outline, filled), ringSurface: ratio(outline, beside),
      ink: own.color, ring: own.outlineColor, fill: css(filled), beside: css(beside) };
  });
}
type Measured = ReturnType<typeof measureControls>[number];
type StateMeasures = { forced: boolean; state: string; measured: Measured[] }[];
/**
 * All three controls in each of the six states, in normal and forced colours. Chromium's DevTools
 * protocol sets each state's pseudo-classes on the three controls at once (their rules depend on
 * no other element's state), so one evaluation per state measures them. realInputMeasures shows
 * keyboard and pointer input paint the same.
 */
async function forcedStateMeasures(page: Page): Promise<StateMeasures> {
  const out: StateMeasures = [];
  // As before each real-input pass: the pointer is away from the controls.
  await page.mouse.move(1, page.viewportSize()!.height - 1);
  const cdp = await page.context().newCDPSession(page);
  try {
    await cdp.send('DOM.enable'); await cdp.send('CSS.enable');
    const { root } = await cdp.send('DOM.getDocument', { depth: 0 });
    const { nodeIds } = await cdp.send('DOM.querySelectorAll', { nodeId: root.nodeId, selector: '#window-controls button' });
    expect(nodeIds).toHaveLength(3);
    const force = (pseudo: string[]) => Promise.all(nodeIds.map(nodeId => cdp.send('CSS.forcePseudoState', { nodeId, forcedPseudoClasses: pseudo })));
    try {
      for (const forced of [false, true]) {
        await page.emulateMedia({ forcedColors: forced ? 'active' : 'none' });
        for (const { state, measure, pseudo } of controlStates) {
          await force(pseudo);
          out.push({ forced, state, measured: await page.evaluate(measureControls, measure.ring) });
        }
      }
    } finally { await force([]); }
  } finally { await cdp.detach(); await page.emulateMedia({ forcedColors: 'none' }); }
  return out;
}
/**
 * Each control's icon, hover, pressed and focus ring against what is painted behind it, in normal
 * and forced colours, alone and with keyboard focus kept while the pointer hovers or presses: the
 * icon at least 4.5:1 against its fill; the ring at least 4.5:1 alone and, over a fill, at least
 * 3:1 against both the fill and the surface beside it.
 */
function controlContrasts(measures: StateMeasures, surface: string) {
  const failures: string[] = [];
  for (const { forced, state, measured } of measures) {
    const { measure } = controlStates.find(one => one.state === state)!;
    measured.forEach((one, i) => {
      const where = `${surface}${forced ? ' forced' : ''} ${controlNames[i]} ${state}`;
      if (!one.visible) { failures.push(`${where}: no ring`); return; }
      if (measure.icon && one.icon < 4.5) failures.push(`${where}: icon ${one.icon.toFixed(2)} (${one.ink} on ${one.fill})`);
      // Alone, the ring is beside the surface only; over a fill the table's 3:1 applies to both.
      const least = measure.icon ? 3 : 4.5;
      if (measure.ring && (one.ringFill < least || one.ringSurface < least)) failures.push(`${where}: ring ${one.ringFill.toFixed(2)} against the fill ${one.fill}, ${one.ringSurface.toFixed(2)} against the surface ${one.beside} (${one.ring})`);
    });
  }
  return failures;
}
/** The same six states by real keyboard and pointer input on one control, measured the same way. */
async function realInputMeasures(page: Page, name: string) {
  const out: { forced: boolean; state: string; measured: Measured }[] = [];
  const index = controlNames.indexOf(name);
  const away = { x: 1, y: page.viewportSize()!.height - 1 };
  const button = control(page, name);
  const box = (await button.boundingBox())!;
  const centre = { x: box.x + box.width / 2, y: box.y + box.height / 2 };
  // A key press first, so programmatic focus shows the keyboard focus ring.
  const focus = async () => { await page.keyboard.press('Shift'); await button.focus(); };
  const blur = async () => { await button.evaluate(el => (el as HTMLElement).blur()); };
  // Pressed states are released away from the control, so no window command runs.
  const release = async () => { await page.mouse.move(away.x, away.y); await page.mouse.up(); };
  const input: Record<string, [() => Promise<void>, () => Promise<void>]> = {
    'icon': [async () => { await page.mouse.move(away.x, away.y); await blur(); }, async () => {}],
    'focus ring': [focus, blur],
    'hover': [async () => { await page.mouse.move(centre.x, centre.y); }, async () => {}],
    'pressed': [async () => { await page.mouse.move(centre.x, centre.y); await page.mouse.down(); }, release],
    'focus ring + hover': [async () => { await focus(); await page.mouse.move(centre.x, centre.y); }, async () => { await page.mouse.move(away.x, away.y); await blur(); }],
    'focus ring + pressed': [async () => { await focus(); await page.mouse.move(centre.x, centre.y); await page.mouse.down(); }, async () => { await release(); await blur(); }],
  };
  try {
    for (const forced of [false, true]) {
      await page.emulateMedia({ forcedColors: forced ? 'active' : 'none' });
      for (const { state, measure } of controlStates) {
        const [enter, leave] = input[state]!;
        await enter();
        out.push({ forced, state, measured: (await page.evaluate(measureControls, measure.ring))[index]! });
        await leave();
      }
    }
  } finally { await page.emulateMedia({ forcedColors: 'none' }); }
  return out;
}
/**
 * Booted once per mode, outside any one case's contexts (closing the browser after all cases closes
 * them): the running app takes its colours from the app's tokens, which a palette changes at
 * runtime, so each case applies its palette to it through the app's own appearance writer. The
 * other surfaces boot per case: the start-up frame, failure page and hand-off paint the saved copy
 * the early HTML script reads once at boot; connecting and factory reset paint before the saved
 * palette is restored; and engine recovery restores it while the app boots behind it. A hand-off
 * is a start still in progress, so it cannot be kept: the start-up script replaces a frame shown
 * for 60 s with the failure page.
 */
const runningApps = new Map<'dark' | 'light', Promise<Page>>();
function runningAppFor(mode: 'dark' | 'light') {
  if (!runningApps.has(mode)) runningApps.set(mode, (async () => {
    const page = await create({ os: mode, theme: mode });
    contexts.delete(page.context());
    await page.goto(url); await page.waitForSelector('jf-top-bar .more-trigger', { state: 'attached' });
    await page.addScriptTag({ type: 'module', url: new URL(probe, url).href });
    await page.waitForFunction(() => 'frameProbe' in window);
    // It writes the saved frame copy the next start paints.
    return page;
  })());
  return runningApps.get(mode)!;
}
const legibilityCases = shippedPalettes.flatMap(palette => (['dark', 'light'] as const).map(mode => ({ palette, mode })));
legibilityCases.forEach(({ palette, mode }, caseIndex) => it(`SW5 X1 window controls are legible over every surface with ${palette} ${mode}, and in forced colours`, async () => {
  const failures: string[] = [];
  const app = await runningAppFor(mode);
  // Every surface is also measured under real keyboard and pointer input once across these cases.
  let surfaceIndex = 0;
  const measureSurface = async (page: Page, surface: string) => {
    const measures = await forcedStateMeasures(page);
    failures.push(...controlContrasts(measures, surface));
    if (surfaceIndex++ % legibilityCases.length === caseIndex) {
      const name = controlNames[caseIndex % controlNames.length]!, index = controlNames.indexOf(name);
      expect({ surface, name, real: await realInputMeasures(page, name) })
        .toEqual({ surface, name, real: measures.map(({ forced, state, measured }) => ({ forced, state, measured: measured[index] })) });
    }
  };
  await expect.poll(() => app.locator('#boot-frame').count()).toBe(0);
  expect(await controlLabels(app)).toEqual(['Minimize', 'Maximize', 'Close']);
  await app.evaluate(({ palette, mode }) => window.frameProbe.applyAppearance({ paletteId: palette, theme: mode }), { palette, mode });
  await expect.poll(() => app.evaluate(key => JSON.parse(localStorage.getItem(key) ?? 'null')?.text === getComputedStyle(document.documentElement).getPropertyValue('--text-primary').trim(), key)).toBe(true);
  await measureSurface(app, 'running app');
  const storage: Record<string, string> = await app.evaluate(() => Object.fromEntries(Object.entries(localStorage)));
  const noCopy = Object.fromEntries(Object.entries(storage).filter(([name]) => name !== key));
  const start = async (options: Options, styles: boolean, entryFails: boolean) => {
    const page = await create({ os: mode, theme: mode, ...options });
    await page.route(`**${entry}`, route => entryFails ? route.abort() : new Promise(() => {}));
    if (!styles) await page.route('**/*.css', route => route.abort());
    await page.goto(url, { waitUntil: 'commit' });
    await page.waitForSelector(entryFails ? '#boot-failure' : '#boot-frame', { state: 'attached' });
    if (styles) await page.waitForFunction(() => [...document.styleSheets].some(sheet => sheet.href?.endsWith('.css')));
    return page;
  };
  const handOff = async (options: Options) => {
    const page = await create({ os: mode, theme: mode, renderHold: true, ...options });
    await page.goto(url); await page.waitForSelector('jf-top-bar .more-trigger');
    return page;
  };
  const opened = async (options: Options, selector: string) => { const page = await create({ os: mode, theme: mode, ...options }); await page.goto(url); return loaded(selector)(page); };
  const states: [string, () => Promise<Page>][] = [
    ['start-up frame, styles blocked', () => start({ storage }, false, false)],
    ['start-up frame', () => start({ storage }, true, false)],
    ['start-up frame without a saved copy', () => start({ storage: noCopy }, false, false)],
    ['failure page, styles blocked', () => start({ storage }, false, true)],
    ['failure page', () => start({ storage }, true, true)],
    ['failure page without a saved copy', () => start({ storage: noCopy }, true, true)],
    ['hand-off', () => handOff({ storage })],
    ['hand-off without a saved copy', () => handOff({ storage: noCopy })],
    // Each boots with this palette's storage as a start would.
    ['connecting', () => opened({ storage, discoveryHold: true }, '#root > jf-engine-recovery')],
    ['engine recovery', () => opened({ storage, supervisor: exhausted }, '#root > jf-engine-recovery')],
    ['factory reset', () => opened({ storage, phase: 'complete' }, '#root > jf-factory-reset-recovery')],
  ];
  for (const [surface, open] of states) {
    const page = await open();
    try {
      // Measured once the saved palette is applied: the hand-offs under the frame, and engine recovery
      // while the app boots behind it (it mounts first, and the palette follows some frames later).
      if (surface.startsWith('hand-off') || surface === 'engine recovery') await page.waitForFunction(palette => document.querySelector(`style[data-theme-id="${palette}"]`) !== null, palette);
      if (surface.startsWith('hand-off')) expect({ surface, frame: await page.locator('#boot-frame').count() }).toEqual({ surface, frame: 1 });
      expect({ surface, labels: await controlLabels(page) }).toEqual({ surface, labels: ['Minimize', 'Maximize', 'Close'] });
      await measureSurface(page, surface);
    } finally { await page.close(); }
  }
  expect(failures).toEqual([]);
}, 90_000));
