// SPDX-License-Identifier: Apache-2.0
// Production assets in real Chromium, with native IPC and HTTP boundary doubles.
// BOOT_FRAME_TREE selects an isolated baseline checkout for fail-before runs.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { preview, type PreviewServer } from 'vite';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { chromium, type Browser, type BrowserContext, type Page } from 'playwright-core';
import { mkdtemp, readFile, readdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';
import path from 'node:path';
let browser: Browser, server: PreviewServer, output: string, url: string, entry: string, builtHtml: string, probe: string, version: string;
const contexts = new Set<BrowserContext>();
const key = 'justsearch.bootFrame.v1';
const secret = 'SECRET-token-key /home/person/private https://secret-host.invalid';
const saved = { mode: 'light', background: 'rgb(248, 249, 252)', raised: 'rgb(241, 242, 248)', text: 'rgb(20, 24, 32)', muted: 'rgb(50, 55, 65)', border: 'rgb(110, 115, 125)', accent: 'rgb(0, 110, 100)' };
const catalog = { schemaVersion: '1.0', catalogVersion: 1, namespace: 'registry-surface', primitive: 'Surface', entries: [{ id: 'core.library-surface', presentation: { labelKey: 'registry-surface.library-surface.label', descriptionKey: 'registry-surface.library-surface.description', iconHint: null, category: null }, audience: 'USER', placement: 'RAIL', altitude: 'PRODUCT', consumes: { resources: ['core.indexed-roots'], operations: [], prompts: [], diagnosticChannels: [], conversationShapes: [] }, mountTag: 'jf-library-surface', members: [], riskTier: 'LOW', stateSchema: null, provenance: { tier: 'CORE', contributorId: 'core', version: '1.0', identity: null } }] };
type Harness = { phase?: string; discoveryHold: boolean; hold: boolean; renderHold: boolean; emit: (payload: unknown) => void; samples: string[]; inputs: string[]; calls: string[] };
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
type Options = { saved?: unknown; os?: 'light' | 'dark'; storageThrows?: boolean; desktop?: boolean; phase?: string; hold?: boolean; renderHold?: boolean; future?: string; settingsFail?: boolean; discoveryHold?: boolean };
async function create(options: Options = {}): Promise<Page> {
  const context = await browser.newContext({ colorScheme: options.os ?? 'dark' });
  contexts.add(context);
  const page = await context.newPage();
  await page.addInitScript(({ options, key, inputTypes }) => {
    if (options.saved !== undefined && !localStorage.getItem(key)) localStorage.setItem(key, JSON.stringify(options.saved));
    if (options.future && !localStorage.getItem('justsearch.userState.v2')) localStorage.setItem('justsearch.userState.v2', options.future);
    if (options.storageThrows) {
      Storage.prototype.getItem = () => { throw new Error('storage denied'); };
      Storage.prototype.setItem = () => { throw new Error('quota denied'); };
    }
    const h = window.frameHarness = { discoveryHold: !!options.discoveryHold, hold: !!options.hold, renderHold: !!options.renderHold, emit: (_payload: unknown) => {}, samples: [] as string[], inputs: [] as string[], calls: [] as string[] };
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
          return result;
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
        if (command === 'factory_reset_status') return { phase: window.frameHarness.phase ?? options.phase ?? 'idle', remaining: [] };
        if (command === 'plugin:event|listen') { listeners.set(args.event, args.handler); return args.handler; }
        if (command === 'supervisor_state') return { schemaVersion: 1, kind: 'engine-supervisor-state.v1', supervisor: 'tauri', state: 'running', incarnation: 1, restartCount: 0, maxRestartAttempts: 3 };
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
  await page.route(/\/(api|infra|registry)\//, route => {
    const pathname = new URL(route.request().url()).pathname;
    return route.fulfill({ status: options.settingsFail && pathname === '/api/settings/v2' ? 503 : 200, json: pathname === '/api/registry/surfaces' ? catalog : pathname === '/api/settings/v2' ? { ui: { theme: 'light', highContrast: false } } : {} });
  });
  return page;
}
async function blocked(options: Options = {}) {
  const page = await create(options);
  await page.route(`**${entry}`, () => new Promise(() => {}));
  await page.route('**/*.css', route => route.abort());
  await page.goto(url, { waitUntil: 'commit' });
  await page.waitForFunction(() => document.readyState !== 'loading');
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
    expect(await page.getByRole('status').textContent()).toBe('Starting');
    await page.keyboard.press('Tab'); expect(await page.locator('#boot-frame :focus').count()).toBe(0);
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
  expect(await page.locator('jf-top-bar [role="menu"]').count()).toBe(1);
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
    await page.evaluate(() => { window.frameHarness.renderHold = false; }); await page.clock.resume();
    await expect.poll(() => page.locator('#boot-frame, #boot-failure').count()).toBe(0);
    expect(await page.evaluate(() => document.activeElement!.localName)).toBe('body');
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
    await page.waitForTimeout(100);
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
    await page.waitForTimeout(100);
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
  try { await page.goto(`${url}?theme=dark`, { waitUntil: 'commit' }); expect(await page.locator('html').getAttribute('data-theme')).toBe('dark'); }
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
    await page.waitForTimeout(100);
    expect(await page.locator('#boot-failure').count()).toBe(1);
  } finally { await page.close(); }
});
it('SA7 SA17 no blank interval through recovery removal and delayed render', async () => {
  const page = await create({ hold: true });
  try {
    await page.goto(url); await page.waitForSelector('jf-shell', { state: 'attached' });
    expect(await page.locator('#boot-frame').count()).toBe(1); await page.waitForTimeout(150);
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
    await page.waitForFunction(() => document.readyState !== 'loading');
    expect(await page.locator('html').getAttribute('data-theme')).toBe('dark');
    expect(await page.locator('#boot-frame').evaluate(el => el.style.getPropertyValue('--boot-frame-accent'))).toBe(cache.accent);
    await page.reload({ waitUntil: 'commit' });
    await page.waitForFunction(() => document.readyState !== 'loading');
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
    await first.evaluate(() => { document.documentElement.style.overflow = 'hidden'; });
    await first.waitForTimeout(50);
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
    await page.waitForFunction(() => document.readyState !== 'loading');
    expect(await page.locator('#boot-frame').count()).toBe(1);
    expect(await page.locator('#boot-frame').evaluate(el => el.style.getPropertyValue('--boot-frame-accent'))).toBe(cache.accent);
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
    await page.waitForTimeout(100);
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
