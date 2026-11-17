// SPDX-License-Identifier: Apache-2.0
// Slice 7b (S7b-9) — AI Brain's install flow in real headless Chromium: keyboard and focus through the
// consent, Pause/Resume, a stopped download, Try again, Cancel install and completion; progress spoken
// politely, once per tenth; light and dark at 600 px. The page runs the real AI state store against
// routed responses: the install statuses the Engine published in InstallFlowStatusLoopbackTest. No
// Engine, shared stack, network or GPU.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdir, mkdtemp, readFile, rm } from 'node:fs/promises';
import path from 'node:path';

type Recorded = { status?: Record<string, unknown>; presence?: unknown; preview?: Record<string, unknown> };
let server: ViteDevServer, browserServer: BrowserServer, browser: Browser, context: BrowserContext | undefined;
let output: string, url: string;
let engine: Record<string, Recorded>;
let liveStatus: unknown;
declare global { interface Window { mountBrain(): Promise<void>; mountTopBar(): void; readInstall(): Promise<void>; brainCalls: string[]; spoken: string[]; refuseStart?: boolean } }

beforeAll(async () => {
  engine = JSON.parse(await readFile(path.resolve('src/shell-v0/views/brain-install/__fixtures__/engine-install-statuses.json'), 'utf8'));
  // A live /api/status, with the answer engine as a computer without AI reports it: down, no model.
  const live = JSON.parse(await readFile(path.resolve('src/api/__fixtures__/status-response-live.json'), 'utf8'));
  liveStatus = { ...live, inference: { ...live.inference, phase: 'OFFLINE', identity: null, chatEnabledSpec: false,
    engineState: 'Down', engineReason: 'not-installed', leaseHolder: null } };
  const root = path.resolve('../..', 'tmp'); await mkdir(root, { recursive: true });
  output = await mkdtemp(path.join(root, 'brain-install-focus-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: output, publicDir: false,
    logLevel: 'silent', server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'isolated-brain-install',
      configureServer(vite) { vite.middlewares.use('/brain-install', (_req, res) => {
        res.setHeader('Content-Type', 'text/html');
        res.end(`<!doctype html><html><head><meta charset="utf-8">
          <link rel="stylesheet" href="/src/styles/tokens.css">
          <link rel="stylesheet" href="/src/shell-v0/themes/default.css">
          <style>html, body { margin: 0; height: 100%; background: var(--surface-1); color: var(--text-primary); font-family: var(--font-native); } #host { height: 100vh; display: flex; flex-direction: column; }</style>
          </head><body><div id="host"></div><script type="module">
          import '/src/shell-v0/views/BrainSurface.ts';
          import '/src/shell-v0/components/StatusPill.ts';
          import { createMockHostApi } from '/src/shell-v0/plugin-api/testHostApi.ts';
          import { startAiStateStore } from '/src/shell-v0/state/aiStateStore.ts';
          import { refreshAiInstallNow } from '/src/shell-v0/utils/aiInstallPoll.ts';
          window.brainCalls = []; window.spoken = [];
          window.readInstall = () => refreshAiInstallNow();
          // The top bar's status entry over the same AI state, where a run's share stands (G3'').
          window.mountTopBar = () => { document.querySelector('#host').prepend(document.createElement('jf-status-pill')); };
          window.mountBrain = async () => {
            startAiStateStore('');
            const brain = document.createElement('jf-brain-surface'); brain.apiBase = '';
            brain.host_ = createMockHostApi({ data: { invokeOperation: async id => { window.brainCalls.push(id);
              if (window.refuseStart && id === 'core.start-ai-install') throw new Error('AI install is already running.');
              return { success: true, structuredData: { state: 'running' } }; } } });
            document.querySelector('#host').append(brain);
            await brain.updateComplete;
            // What a screen reader hears from the page's polite status region, change by change.
            const region = () => brain.shadowRoot.querySelector('[data-testid="install-announcement"]');
            let last = '';
            new MutationObserver(() => { const now = region()?.textContent ?? ''; if (now !== last) { last = now; if (now) window.spoken.push(now); } })
              .observe(brain.shadowRoot, { subtree: true, childList: true, characterData: true });
          };
          document.body.dataset.ready = 'true';
          </script></body></html>`);
      }); },
    }] });
  await server.listen(); const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/brain-install`;
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned headless Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
}, 60_000);
afterEach(async () => { await context?.close(); context = undefined; });
afterAll(async () => {
  await browser?.close(); await browserServer?.close(); await server?.close();
  if (output) await rm(output, { recursive: true, force: true });
}, 60_000);

// The shipped registry's CPU footprints, as in BrainInstall.flow.test.ts.
const SHIPPED: Record<string, number> = { embedding: 1_272_585_559, splade: 1_002_573_334, reranker: 357_942_777, ner: 138_036_009, 'citation-scorer': 23_724_624 };

/** The Engine as the page sees it: one recorded scenario at a time, switched by the test. */
class Engine {
  status: Record<string, unknown> = {};
  presence: unknown;
  preview: Record<string, unknown> = {};
  show(key: string, patch: Record<string, unknown> = {}) {
    const recorded = engine[key]!;
    if (recorded.status) this.status = { ...recorded.status, ...patch };
    if (recorded.presence) this.presence = recorded.presence;
    if (recorded.preview) this.preview = recorded.preview;
  }
}

async function open(start: string, opts: { theme?: 'dark' | 'light'; width?: number; shipped?: boolean } = {}): Promise<{ page: Page; engine: Engine }> {
  context = await browser.newContext({ viewport: { width: opts.width ?? 1024, height: 700 } });
  const page = await context.newPage(); page.setDefaultTimeout(10_000);
  page.on('pageerror', error => console.error('Brain install page error:', error.message));
  const state = new Engine(); state.show('firstRunPreview'); state.show(start);
  if (opts.shipped) {
    const recorded = engine.firstRunPreview!.preview! as { components: Array<{ id: string }> };
    const total = Object.values(SHIPPED).reduce((a, b) => a + b, 0);
    state.preview = { ...recorded, totalDownloadBytes: total, components: recorded.components.map(c => SHIPPED[c.id] ? { ...c, totalBytes: SHIPPED[c.id], downloadBytes: SHIPPED[c.id] } : c) };
  }
  await page.route(new URL('/api/**', url).href, route => {
    const request = route.request(), address = new URL(request.url()).pathname;
    const reply = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (address.endsWith('/install/pause') || address.endsWith('/install/resume')) {
      state.status = { ...state.status, paused: address.endsWith('/pause') };
      return reply(state.status);
    }
    if (address === '/api/status') return reply(liveStatus);
    if (address === '/api/inference/status') return reply({ mode: 'offline', available: false });
    if (address.endsWith('/install/status')) return reply({ ...state.status, updatedAtEpochMs: Date.now() });
    if (address.endsWith('/runtime/status')) return reply({ onnxFeatures: [] });
    if (address.endsWith('/packs/status')) return reply({ state: 'idle' });
    if (address.endsWith('/models/status')) return reply(state.presence);
    if (address.endsWith('/plan-preview')) return reply(state.preview);
    if (address.endsWith('/manifest')) return reply({ schemaVersion: 2, packages: Object.keys(SHIPPED).map(id => ({ id, label: id,
      license: id === 'ner' ? 'AFL-3.0' : 'Apache-2.0', termsUrl: `https://example.invalid/terms/${id}`, variants: [], supportingFiles: [] })) });
    if (address.endsWith('/settings/v2')) return reply({ ui: {}, llm: {}, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } });
    if (address.endsWith('/downloaded-files')) return reply({ parts: [] });
    return reply({});
  });
  await page.goto(url, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ state: 'attached', timeout: 45_000 });
  if (opts.theme) await page.evaluate(t => { document.documentElement.dataset.theme = t; }, opts.theme);
  await page.evaluate(() => window.mountBrain());
  return { page, engine: state };
}
/** The accessible name of whatever has focus, through shadow roots. */
async function focused(page: Page): Promise<string> {
  return page.evaluate(() => {
    let active: Element | null = document.activeElement;
    while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
    if (!active) return '';
    return (active.getAttribute('aria-label') || active.textContent || '').replace(/\s+/g, ' ').trim();
  });
}
const heading = (page: Page) => page.getByRole('heading', { level: 1 });
async function publish(page: Page, state: Engine, key: string, patch: Record<string, unknown> = {}) {
  state.show(key, patch);
  await page.evaluate(() => window.readInstall());
}

it('Escape and Cancel close the consent and return focus to Install AI; Accept is reached by keyboard', async () => {
  const { page } = await open('restartAfterFailedNothingPlaced', { shipped: true });
  await expect.poll(() => heading(page).textContent()).toBe("AI isn't installed yet");
  const install = page.getByRole('button', { name: /^Install AI/ });
  await install.focus();
  await page.keyboard.press('Enter');
  const consent = page.getByRole('dialog', { name: 'Download AI models?' });
  await expect.poll(() => consent.isVisible()).toBe(true);
  await expect.poll(() => consent.getByRole('button', { name: 'Accept and download' }).isEnabled()).toBe(true);
  const reached: string[] = [];
  for (let i = 0; i < 12 && reached.at(-1) !== 'Accept and download'; i++) {
    await page.keyboard.press('Tab');
    reached.push(await focused(page));
  }
  expect(reached).toContain('Accept and download');
  await page.keyboard.press('Escape');
  await expect.poll(() => consent.isVisible()).toBe(false);
  await expect.poll(() => focused(page)).toMatch(/^Install AI/);
  await page.keyboard.press('Enter');
  await expect.poll(() => consent.isVisible()).toBe(true);
  await consent.getByRole('button', { name: 'Cancel' }).focus();
  await page.keyboard.press('Enter');
  await expect.poll(() => consent.isVisible()).toBe(false);
  await expect.poll(() => focused(page)).toMatch(/^Install AI/);
  expect(await page.evaluate(() => window.brainCalls)).toEqual([]);
}, 60_000);

// PH5 (fm-11) — the consent sits centred in the area below the top bar, its dimming starts under the bar,
// and it opens on Cancel, never on a Terms link that leaves the app.
it.each(['dark', 'light'] as const)('the consent is centred below the top bar and opens on Cancel in %s', async theme => {
  const { page } = await open('restartAfterFailedNothingPlaced', { shipped: true, theme });
  await expect.poll(() => heading(page).textContent()).toBe("AI isn't installed yet");
  await page.getByRole('button', { name: /^Install AI/ }).click();
  const consent = page.getByRole('dialog', { name: 'Download AI models?' });
  await expect.poll(() => consent.isVisible()).toBe(true);
  await expect.poll(() => consent.getByRole('button', { name: 'Accept and download' }).isEnabled()).toBe(true);
  await expect.poll(() => focused(page)).toBe('Cancel');
  const g = await page.evaluate(() => {
    const brain = document.querySelector('jf-brain-surface')!;
    const dialog = brain.shadowRoot!.querySelector('dialog.consent')!;
    const root = getComputedStyle(document.documentElement);
    const bar = parseFloat(root.getPropertyValue('--frame-topbar-height')) * parseFloat(root.fontSize);
    const r = dialog.getBoundingClientRect(), backdrop = getComputedStyle(dialog, '::backdrop');
    return { bar, top: r.top, bottom: r.bottom, height: innerHeight, backdropTop: parseFloat(backdrop.top), dim: backdrop.backgroundColor,
      overBar: brain.shadowRoot!.elementFromPoint(innerWidth / 2, bar / 2) === dialog };
  });
  expect(g.bar).toBe(44);
  expect(g.top).toBeGreaterThanOrEqual(g.bar);
  expect(Math.abs((g.top + g.bottom) / 2 - (g.bar + g.height) / 2)).toBeLessThanOrEqual(1);
  expect(g.backdropTop).toBe(g.bar);
  expect(g.dim).not.toMatch(/^(transparent|rgba\(0, 0, 0, 0\))$/);
  expect(g.overBar).toBe(false);
}, 60_000);

it('the install view takes the page without losing focus: after Accept, and from a step, focus moves to Pause', async () => {
  const { page, engine: state } = await open('restartAfterFailedNothingPlaced', { shipped: true });
  await expect.poll(() => heading(page).textContent()).toBe("AI isn't installed yet");
  await page.getByRole('button', { name: /^Install AI/ }).focus();
  await page.keyboard.press('Enter');
  const consent = page.getByRole('dialog', { name: 'Download AI models?' });
  const accept = consent.getByRole('button', { name: 'Accept and download' });
  await expect.poll(() => accept.isEnabled()).toBe(true);
  await accept.focus();
  await page.keyboard.press('Enter');
  await expect.poll(() => page.evaluate(() => window.brainCalls)).toEqual(['core.start-ai-install']);
  await publish(page, state, 'running');
  await expect.poll(() => focused(page), { message: 'focus after Accept' }).toBe('Pause');
  expect(await page.locator('jf-brain-surface .brain-path').count(), 'steps while installing').toBe(0);
  // A run started elsewhere while focus is on a step: the steps leave, focus goes to Pause.
  await publish(page, state, 'restartAfterFailedNothingPlaced');
  await expect.poll(() => heading(page).textContent()).toBe("AI isn't installed yet");
  const step = page.locator('jf-brain-surface .brain-path').locator('button, a[href], input, [tabindex="0"]').first();
  await step.focus();
  expect(await page.evaluate(() => {
    let active: Element | null = document.activeElement;
    while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
    return active?.closest('.brain-path') !== null;
  })).toBe(true);
  await publish(page, state, 'running');
  await expect.poll(() => page.locator('jf-brain-surface .brain-path').count()).toBe(0);
  await expect.poll(() => focused(page)).toBe('Pause');
}, 60_000);

it('Pause keeps focus on its button as it becomes Resume; the pause is said once, politely', async () => {
  const { page, engine: state } = await open('running');
  const pause = page.getByRole('button', { name: 'Pause', exact: true });
  await expect.poll(() => pause.isVisible()).toBe(true);
  await pause.focus();
  await page.keyboard.press('Space');
  await publish(page, state, 'pausedFileFinishing');
  await expect.poll(() => page.locator('jf-brain-surface .flow-paused').textContent()).toBe('Paused. The file being downloaded may still finish.');
  await expect.poll(() => focused(page)).toBe('Resume');
  const region = page.locator('jf-brain-surface [data-testid="install-announcement"]');
  expect(await region.getAttribute('role')).toBe('status');
  await expect.poll(() => page.evaluate(() => window.spoken.at(-1))).toBe('Paused. The file being downloaded may still finish.');
  await page.keyboard.press('Enter');
  await publish(page, state, 'running');
  await expect.poll(() => focused(page)).toBe('Pause');
}, 60_000);

// G3'' — the heading stays "Installing AI"; the share stands once, in the top bar; the polite
// announcement may carry it, once per tenth (S7b-9).
it('progress is spoken once per tenth, not on every poll; the share shows in the top bar, not the heading', async () => {
  const { page, engine: state } = await open('running', {});
  await page.evaluate(() => window.mountTopBar());
  const topBar = page.locator('jf-status-pill .label');
  await expect.poll(() => topBar.textContent()).toBe('Installing AI · 18%');
  expect(await heading(page).textContent()).toBe('Installing AI');
  await page.evaluate(() => { window.spoken.length = 0; });
  for (const done of [200_000, 210_000, 230_000, 260_000, 290_000]) {
    await publish(page, state, 'running', { downloadedBytes: done });
    await expect.poll(() => topBar.textContent()).toBe(`Installing AI · ${Math.round(done / 949_616 * 100)}%`);
    expect(await heading(page).textContent()).toBe('Installing AI');
  }
  expect(await page.evaluate(() => window.spoken)).toEqual(['Installing AI, 20%', 'Installing AI, 30%']);
}, 60_000);

it('focus follows the flow: a failure to its heading, Try again to Pause, completion and Cancel install to the heading', async () => {
  const { page, engine: state } = await open('running');
  await expect.poll(() => page.getByRole('button', { name: 'Pause', exact: true }).count(), { message: 'Pause' }).toBe(1);
  await page.getByRole('button', { name: 'Pause', exact: true }).focus();
  await publish(page, state, 'failedPartsMissing');
  await expect.poll(() => heading(page).textContent()).toBe('The download stopped');
  await expect.poll(() => focused(page)).toBe('The download stopped');
  await expect.poll(() => page.evaluate(() => window.spoken.at(-1))).toBe('The download stopped');
  await page.getByRole('button', { name: 'Try again' }).focus();
  await page.keyboard.press('Enter');
  await expect.poll(() => page.evaluate(() => window.brainCalls)).toEqual(['core.start-ai-install']);
  await publish(page, state, 'retryRunning');
  await expect.poll(() => focused(page)).toBe('Pause');
  await publish(page, state, 'completed');
  await expect.poll(() => page.getByRole('button', { name: 'Pause', exact: true }).count()).toBe(0);
  await expect.poll(() => focused(page)).toBe(await heading(page).textContent());
  await publish(page, state, 'failedPartsMissing', { startedAtEpochMs: Date.now() });
  await expect.poll(() => heading(page).textContent()).toBe('The download stopped');
  await page.getByRole('button', { name: 'Cancel install' }).focus();
  await page.keyboard.press('Enter');
  await expect.poll(() => heading(page).textContent()).not.toBe('The download stopped');
  await expect.poll(() => focused(page)).toBe(await heading(page).textContent());
}, 60_000);

it('RS6 focus after Try again resolves on every outcome: completion, another failure, a refused request', async () => {
  const { page, engine: state } = await open('failedPartsMissing');
  const tryAgain = async () => {
    await expect.poll(() => heading(page).textContent()).toBe('The download stopped');
    await page.getByRole('button', { name: 'Try again' }).focus();
    await page.keyboard.press('Enter');
  };
  // The new run completes before any poll sees it running: focus goes to the heading.
  await tryAgain();
  await expect.poll(() => page.evaluate(() => window.brainCalls)).toEqual(['core.start-ai-install']);
  await publish(page, state, 'completed');
  await expect.poll(() => page.getByRole('button', { name: 'Try again' }).count()).toBe(0);
  await expect.poll(() => focused(page), { message: 'focus after completion' }).toBe(await heading(page).textContent());
  // The new run fails before any poll sees it running: focus goes to the stopped view's heading.
  await publish(page, state, 'failedPartsMissing', { startedAtEpochMs: Date.now() });
  await tryAgain();
  await expect.poll(() => page.evaluate(() => window.brainCalls.length)).toBe(2);
  await publish(page, state, 'failedPartsMissing', { startedAtEpochMs: Date.now() + 1 });
  await expect.poll(() => focused(page), { message: 'focus after another failure' }).toBe('The download stopped');
  // The Engine refuses the start: the stopped view stays and focus goes to its heading.
  await page.evaluate(() => { window.refuseStart = true; });
  await tryAgain();
  await expect.poll(() => page.evaluate(() => window.brainCalls.length)).toBe(3);
  await expect.poll(() => focused(page), { message: 'focus after a refused start' }).toBe('The download stopped');
}, 60_000);

/**
 * As in BrainOverview.layout-lockdown.test.ts (slice 7a), with screen-reader-only text exempt from the
 * clipping check: every element of the AI Brain surface, through nested shadow roots, under the repository's ladder
 * rule exactly as e2e/type-ladder-chromium.mjs applies it: each axis of all four corners is 50% or a
 * px value on the radius ladder; rendered text, and generated ::before/::after text, has a font size
 * on the size ladder; text at 15 px or less does not inherit the display optical size, and 20/28 px
 * text sets opsz 32. No rounding. Rendered text must also not be clipped or pushed off screen.
 */
function audit() {
  const sizes = [11, 12, 13, 15, 20, 28], radii = [0, 4, 8, 14, 9999];
  const displayCut = (value: string) => value.split(',').some(axis => /^["']opsz["']\s+32(?:\.0+)?$/.test(axis.trim()));
  const out = { clipped: [] as string[], offLadder: [] as string[], radius: [] as string[], overflow: false, texts: 0 };
  const name = (el: Element) => `${el.localName}${el.id ? `#${el.id}` : ''}${typeof el.className === 'string' && el.className.trim() ? `.${el.className.trim().split(/\s+/).join('.')}` : ''}`;
  const brain = document.querySelector('jf-brain-surface')!;
  const scroll = brain.shadowRoot!.querySelector('.brain-scroll') as HTMLElement;
  out.overflow = scroll.scrollWidth > scroll.clientWidth + 1;
  const walk = (root: Element | ShadowRoot) => {
    for (const el of [...root.children]) {
      if (el.hasAttribute('data-ladder-controls') || ['style', 'script', 'template', 'link', 'meta', 'head'].includes(el.localName)) continue;
      const style = getComputedStyle(el);
      for (const corner of ['borderTopLeftRadius', 'borderTopRightRadius', 'borderBottomLeftRadius', 'borderBottomRightRadius'] as const) {
        const value = style[corner];
        for (const axis of value.split(/\s+/)) if (!(axis === '50%' || axis.endsWith('px') && radii.includes(parseFloat(axis)))) out.radius.push(`${name(el)} ${corner}: ${value}`);
      }
      const rendered = el.getClientRects().length > 0 && style.visibility === 'visible';
      const text = [...el.childNodes].some(n => n.nodeType === Node.TEXT_NODE && n.textContent!.trim() !== '')
        || (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) && !!(el.value || el.placeholder);
      if (rendered && text) {
        out.texts++;
        const label = `${name(el)} "${el.textContent!.trim().slice(0, 30)}"`;
        if (!sizes.includes(parseFloat(style.fontSize))) out.offLadder.push(`${label}: font-size ${style.fontSize}`);
        if (parseFloat(style.fontSize) <= 15 && displayCut(style.fontVariationSettings)) out.offLadder.push(`${label}: text tier inherits display opsz`);
        if (['20px', '28px'].includes(style.fontSize) && !displayCut(style.fontVariationSettings)) out.offLadder.push(`${label}: display text lacks explicit opsz 32`);
        const rect = el.getBoundingClientRect();
        // Screen-reader-only text (a 1×1 px box: the install announcement, a switch's reason) is clipped by design.
        const srOnly = rect.width <= 1 && rect.height <= 1;
        const clips = !srOnly && ['hidden', 'clip'].includes(style.overflowX) && el.scrollWidth > el.clientWidth + 1;
        const outside = !srOnly && (rect.right > window.innerWidth + 1 || rect.left < -1);
        if (clips || outside) out.clipped.push(label);
      }
      for (const pseudo of ['::before', '::after']) {
        const ps = getComputedStyle(el, pseudo);
        if (rendered && !['none', 'normal', '""', "''"].includes(ps.content)) {
          if (!sizes.includes(parseFloat(ps.fontSize))) out.offLadder.push(`${name(el)}${pseudo}: font-size ${ps.fontSize}`);
          if (['20px', '28px'].includes(ps.fontSize) && !displayCut(ps.fontVariationSettings)) out.offLadder.push(`${name(el)}${pseudo}: display text lacks explicit opsz 32`);
        }
      }
      if (el.shadowRoot) walk(el.shadowRoot);
      walk(el);
    }
  };
  walk(brain.shadowRoot!);
  return out;
}

for (const theme of ['dark', 'light'] as const) {
  it(`${theme}, 600 px: the run and the stopped download fit; the stopped view has no red and no teal but Try again`, async () => {
    const { page, engine: state } = await open('runningRetrying', { theme, width: 600 });
    await expect.poll(() => heading(page).textContent()).toBe('Installing AI');
    await expect.poll(() => page.locator('jf-brain-surface [role="progressbar"]').count()).toBe(1);
    expect(await page.evaluate(audit)).toMatchObject({ clipped: [], offLadder: [], radius: [], overflow: false });
    const fits = () => page.evaluate(() => {
      const brain = document.querySelector('jf-brain-surface')!;
      const flow = brain.shadowRoot!.querySelector('[data-testid="install-flow"]');
      const nodes = [document.documentElement, ...(flow ? [flow, ...flow.querySelectorAll('*')] : [])];
      return nodes.filter(n => n.scrollWidth > n.clientWidth + 1 && getComputedStyle(n).overflowX !== 'hidden').map(n => n.className || n.tagName);
    });
    expect(await fits()).toEqual([]);
    await publish(page, state, 'failedPartsMissing');
    await expect.poll(() => heading(page).textContent()).toBe('The download stopped');
    expect(await fits()).toEqual([]);
    expect(await page.evaluate(audit)).toMatchObject({ clipped: [], offLadder: [], radius: [], overflow: false });
    const colours = await page.evaluate(() => {
      const brain = document.querySelector('jf-brain-surface')!;
      const root = brain.shadowRoot!;
      const accent = getComputedStyle(root.querySelector('[data-testid="install-flow"]')!).getPropertyValue('--accent-tint').trim();
      const probe = document.createElement('i'); probe.style.color = accent; document.body.append(probe);
      const teal = getComputedStyle(probe).color; probe.remove();
      const bar = root.querySelector('[data-testid="install-flow"] [role="progressbar"] .progress-bar')!;
      return { teal, bar: getComputedStyle(bar).backgroundColor, bars: [...root.querySelectorAll('[data-testid="install-flow"] .progress-bar')].map(b => getComputedStyle(b).backgroundColor),
        errors: root.querySelectorAll('jf-error-alert[tone="error"]').length,
        filled: [...root.querySelectorAll('[data-testid="install-flow"] jf-button[variant="primary"]')].map(b => b.getAttribute('label')) };
    });
    expect(colours.errors).toBe(0);
    expect(colours.bars).not.toContain(colours.teal);
    expect(colours.filled).toEqual(['Try again']);
  }, 60_000);
}

for (const theme of ['dark', 'light'] as const) {
  it(`${theme}: the consent (fm-11) stays on the ladders and fits at 600 px`, async () => {
    const { page } = await open('restartAfterFailedNothingPlaced', { shipped: true, theme, width: 600 });
    await expect.poll(() => heading(page).textContent()).toBe("AI isn't installed yet");
    await page.getByRole('button', { name: /^Install AI/ }).click();
    const consent = page.getByRole('dialog', { name: 'Download AI models?' });
    await expect.poll(() => consent.getByRole('button', { name: 'Accept and download' }).isEnabled()).toBe(true);
    expect(await page.evaluate(audit)).toMatchObject({ clipped: [], offLadder: [], radius: [] });
  }, 60_000);
}
