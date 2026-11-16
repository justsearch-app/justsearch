// SPDX-License-Identifier: Apache-2.0
// Slice 7a (S7a-10, S7a-4 keys) — AI Brain's Overview in real Chromium with isolated fixture responses:
// 1024×700 and 600 px, light and dark, 200% zoom; nothing clipped, text sizes and corners on the ladders,
// and a step's switch answers Space and Enter. No Engine, shared stack or GPU.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdir, mkdtemp, readFile, rm } from 'node:fs/promises';
import path from 'node:path';

let server: ViteDevServer, browserServer: BrowserServer, browser: Browser, context: BrowserContext | undefined;
let output: string, url: string;
const GB = 1024 ** 3, MB = 1024 ** 2;
type Picture = 'fm-10' | 'fm-13' | 'fm-14';
declare global { interface Window { showBrain(picture: string): Promise<void>; brainCalls: string[]; showDeveloperTools(show: boolean): void } }

beforeAll(async () => {
  const root = path.resolve('../..', 'tmp'); await mkdir(root, { recursive: true });
  output = await mkdtemp(path.join(root, 'brain-overview-layout-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: output, publicDir: false,
    logLevel: 'silent', server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'isolated-brain-overview',
      configureServer(vite) { vite.middlewares.use('/brain-overview', (_req, res) => {
        res.setHeader('Content-Type', 'text/html');
        res.end(`<!doctype html><html><head><meta charset="utf-8">
          <link rel="stylesheet" href="/src/styles/tokens.css">
          <link rel="stylesheet" href="/src/shell-v0/themes/default.css">
          <style>html, body { margin: 0; height: 100%; background: var(--surface-1); color: var(--text-primary); font-family: var(--font-native); } #host { height: 100vh; display: flex; flex-direction: column; }</style>
          </head><body><div id="host"></div><script type="module">
          import '/src/shell-v0/views/BrainSurface.ts';
          import { createMockHostApi } from '/src/shell-v0/plugin-api/testHostApi.ts';
          import { setShowDeveloperTools } from '/src/shell-v0/state/developerToolsState.ts';
          window.showDeveloperTools = show => setShowDeveloperTools(show);
          const GB = ${GB}, MB = ${MB};
          const settle = async () => { for (let i = 0; i < 4; i++) await new Promise(requestAnimationFrame); };
          window.brainCalls = [];
          const live = { phase: 'connected', installObservationLive: true, runtimeObservationLive: true, snapshotLive: true,
            index: { ownDocumentCount: { known: true, value: 8 } } };
          const states = {
            'fm-10': { ...live, aiEngine: { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null },
              runtime: { mode: 'offline', installed: { known: true, value: false } }, capabilities: { chat: false },
              installStatus: { state: 'idle', installedFully: false }, runtimeStatus: { onnxFeatures: [] },
              status: { gpu: { available: false, deviceCount: 0 }, embedding: { compatState: 'COMPATIBLE' }, operating: { gpu: { profile: 'NO_GPU', stale: false } } } },
            'fm-13': { ...live, aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null },
              runtime: { mode: 'offline', installed: { known: true, value: true } }, capabilities: { chat: false },
              installStatus: { state: 'idle', installedFully: true },
              runtimeStatus: { onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }, { id: 'reranker', status: 'inactive', modelActive: false }] },
              status: { gpu: { available: false, deviceCount: 0 }, embedding: { compatState: 'COMPATIBLE' }, operating: { gpu: { profile: 'NO_GPU', stale: false } } } },
            'fm-14': { ...live, aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
              runtime: { mode: 'online', installed: { known: true, value: true }, modelLabel: 'Answer model' }, capabilities: { chat: true },
              installStatus: { state: 'completed', installedFully: true },
              runtimeStatus: { active: { mmprojActive: false }, onnxFeatures: [{ id: 'embed', status: 'active', modelActive: true }, { id: 'reranker', status: 'active', modelActive: true }] },
              status: { inference: { chatEnabledSpec: true }, embedding: { compatState: 'COMPATIBLE' }, gpu: { totalVramBytes: 12 * GB, usedVramBytes: 5.8 * GB, available: true, cudaFunctional: true },
                operating: { gpu: { stale: false, adapter: 'RTX 4070' } } } },
          };
          window.showBrain = async picture => {
            document.querySelector('#host').replaceChildren();
            const brain = document.createElement('jf-brain-surface'); brain.apiBase = '';
            brain.host_ = createMockHostApi({ data: { invokeOperation: async id => { window.brainCalls.push(id); return { success: true, structuredData: null }; } } });
            document.querySelector('#host').append(brain); await settle();
            const s = states[picture];
            Object.assign(brain, { _unifiedAiState: s, installStatus: s.installStatus, runtimeStatus: s.runtimeStatus, systemStatus: s.status });
            brain.requestUpdate(); await settle();
          };
          document.body.dataset.ready = 'true';
          </script></body></html>`);
      }); },
    }] });
  await server.listen(); const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/brain-overview`;
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

function plan(picture: Picture) {
  const installed = picture !== 'fm-10';
  const c = (id: string, bytes: number, extra = {}) => ({ id, label: id, state: installed ? 'installed' : 'to-download', declinable: id !== 'embedding',
    necessity: id === 'embedding' ? 'required' : 'improves-results', totalBytes: bytes, downloadBytes: installed ? 0 : bytes, ...extra });
  const chat = picture === 'fm-14' ? c('chat', 5.7 * GB)
    : c('chat', 5.7 * GB, { state: 'unavailable', downloadBytes: 0, unavailableReason: 'Chat model requires a CUDA-capable GPU (none detected on this system).' });
  return { totalDownloadBytes: installed ? 0 : Math.round(1.2 * GB + 341 * MB), components: [c('embedding', 1.2 * GB), c('reranker', 341 * MB), chat] };
}
async function open(picture: Picture, theme: 'dark' | 'light', width: number, zoom = 1): Promise<Page> {
  // 200% zoom of a window is its CSS viewport halved at twice the device scale.
  context = await browser.newContext({ viewport: { width: Math.round(width / zoom), height: Math.round(700 / zoom) }, deviceScaleFactor: zoom });
  const page = await context.newPage(); page.setDefaultTimeout(5000);
  page.on('pageerror', error => console.error('Brain overview page error:', error.message));
  let reranker = picture === 'fm-14';
  await page.route(new URL('/api/**', url).href, route => {
    const request = route.request(), address = request.url();
    if (address.includes('/settings/v2') && request.method() === 'POST') {
      const body = JSON.parse(request.postData() ?? '{}') as { rerankerEnabled?: boolean; operationKey?: string };
      if (typeof body.rerankerEnabled === 'boolean') reranker = body.rerankerEnabled;
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ state: 'COMPLETE', operationKey: body.operationKey,
        witness: { acceptedRevision: 1, lastCommittedOperationKey: body.operationKey }, rerankerEnabled: reranker }) });
    }
    const body = address.includes('/settings/v2') ? { ui: { chatEnabled: picture === 'fm-14' }, llm: {}, rerankerEnabled: reranker,
      pictureReadingEnabled: false, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } }
      : address.includes('/plan-preview') ? plan(picture)
      : address.includes('/models/status') ? { packages: plan(picture).components.map(c => ({ id: c.id, complete: c.state === 'installed' })) }
      : address.includes('/downloaded-files') ? { parts: [] } : {};
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
  });
  await page.goto(url, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ state: 'attached', timeout: 45_000 });
  await page.evaluate(t => { document.documentElement.dataset.theme = t; }, theme);
  await page.evaluate(p => window.showBrain(p), picture);
  await expect.poll(() => page.locator('jf-brain-surface .path-step[data-feature="chat"]').count(), { timeout: 5000, message: 'Answer questions step' }).toBe(1);
  return page;
}
/**
 * Every element of the AI Brain surface, through nested shadow roots, under the repository's ladder
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
        const clips = ['hidden', 'clip'].includes(style.overflowX) && el.scrollWidth > el.clientWidth + 1;
        const outside = rect.right > window.innerWidth + 1 || rect.left < -1;
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

const cases = (['fm-10', 'fm-13', 'fm-14'] as const).flatMap(picture => (['dark', 'light'] as const).flatMap(theme =>
  [[1024, 1], [600, 1], [1024, 2]].map(([width, zoom]) => ({ picture, theme, width: width!, zoom: zoom! }))));
it.each(cases)('$picture $theme at $width px, zoom $zoom: nothing clipped, ladder sizes and corners', async ({ picture, theme, width, zoom }) => {
  const page = await open(picture, theme, width, zoom);
  const result = await page.evaluate(audit);
  expect(result.texts).toBeGreaterThan(10);
  expect(result.overflow, 'horizontal scroll').toBe(false);
  expect(result.clipped).toEqual([]);
  expect(result.offLadder).toEqual([]);
  expect(result.radius).toEqual([]);
}, 60_000);

it('the audit reports text off the size ladder and any corner off the radius ladder, unrounded', async () => {
  const page = await open('fm-13', 'dark', 1024);
  await page.evaluate(() => {
    const probe = document.createElement('p');
    probe.className = 'ladder-probe'; probe.textContent = 'Probe text';
    probe.setAttribute('style', 'font-size: 12.4px; border-top-right-radius: 7px');
    document.querySelector('jf-brain-surface')!.shadowRoot!.querySelector('.brain-scroll')!.append(probe);
  });
  const result = await page.evaluate(audit);
  expect(result.offLadder).toEqual(['p.ladder-probe "Probe text": font-size 12.4px']);
  expect(result.radius).toEqual(['p.ladder-probe borderTopRightRadius: 7px']);
}, 60_000);

it.each(['dark', 'light'] as const)('developer tools: the %s "You get / It costs" panel stays on the ladders', async theme => {
  const page = await open('fm-14', theme, 1024);
  await page.evaluate(() => window.showDeveloperTools(true));
  const opener = page.locator('jf-brain-surface .path-step[data-feature="reranker"] button.step-info');
  await expect.poll(() => opener.count(), { message: 'step opener with developer tools' }).toBe(1);
  await opener.click();
  await expect.poll(() => page.locator('jf-brain-surface .feature-panel').count()).toBe(1);
  const result = await page.evaluate(audit);
  expect(result.offLadder).toEqual([]);
  expect(result.radius).toEqual([]);
  await page.evaluate(() => window.showDeveloperTools(false));
}, 60_000);

it('the step switch answers Space and Enter', async () => {
  const page = await open('fm-14', 'dark', 1024);
  const control = page.locator('jf-brain-surface .path-step[data-feature="reranker"] [role="switch"]');
  await control.focus();
  await page.keyboard.press('Space');
  await expect.poll(() => control.getAttribute('aria-checked')).toBe('false');
  await expect.poll(() => control.getAttribute('aria-disabled')).toBe('false');
  await control.focus();
  await page.keyboard.press('Enter');
  await expect.poll(() => control.getAttribute('aria-checked')).toBe('true');
}, 60_000);

it('the Brain source sets no style= attribute in its templates', async () => {
  const source = await readFile(path.resolve('src/shell-v0/views/BrainSurface.ts'), 'utf8');
  expect(source.match(/[^.\w-]style=\$?\{?["'`]?/g) ?? []).toEqual([]);
});
