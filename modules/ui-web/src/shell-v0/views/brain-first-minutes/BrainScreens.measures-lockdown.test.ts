// SPDX-License-Identifier: Apache-2.0
// A5 (fm-12, fm-12b, fm-13, fm-14, fm-16) — AI Brain's installing, stopped, installed-without-a-card and
// ready screens, and the search field with Search / Ask, measured in real headless Chromium at 1024×700,
// dark and light. Boxes are measured, never where wrapped text ends. The page runs the real AI state
// store against routed responses: the install statuses, model presence and plan previews the Engine
// published in InstallFlowStatusLoopbackTest (brain-install/__fixtures__/engine-install-statuses.json),
// /api/status from src/api/__fixtures__/status-response-live.json, and settings, runtime and inference
// answers shaped as their generated schema types (api/generated/schema-types). Expected values are the
// pictures' CSS: .prog{margin-top:18px} .prog .bar{height:8px;border-radius:4px} .prog .pl{margin-top:8px}
// .parts{margin-top:18px} .part{grid-template-columns:1fr 190px 110px;gap:16px;height:42px} .part .bar{height:3px}
// .pacts{gap:6px} .morel{gap:28px} .sf .md{border-radius:8px;padding:2px} .sf .md span{border-radius:8px;padding:3px 9px;font-size:12px}.
// No Engine, shared stack, network or GPU.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdir, mkdtemp, readFile, rm } from 'node:fs/promises';
import path from 'node:path';

type Component = Record<string, unknown> & { id: string; label: string; state: string; downloadBytes: number };
type Recorded = { status?: Record<string, unknown>; presence?: { packages: Array<Record<string, unknown>> }; preview?: { components: Component[]; totalDownloadBytes: number } };
type Picture = 'fm-12' | 'fm-12b' | 'fm-13' | 'fm-14' | 'fm-16';
let server: ViteDevServer, browserServer: BrowserServer, browser: Browser, context: BrowserContext | undefined;
let output: string, url: string;
let engine: Record<string, Recorded>;
let liveStatus: Record<string, unknown>;
const MB = 1024 ** 2, GB = 1024 ** 3;
declare global { interface Window { mountBrain(): Promise<void>; mountField(): Promise<void>; readInstall(): Promise<void> } }

beforeAll(async () => {
  engine = JSON.parse(await readFile(path.resolve('src/shell-v0/views/brain-install/__fixtures__/engine-install-statuses.json'), 'utf8'));
  liveStatus = JSON.parse(await readFile(path.resolve('src/api/__fixtures__/status-response-live.json'), 'utf8'));
  const root = path.resolve('../..', 'tmp'); await mkdir(root, { recursive: true });
  output = await mkdtemp(path.join(root, 'brain-screens-measures-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: output, publicDir: false,
    logLevel: 'silent', server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'isolated-brain-screens',
      configureServer(vite) { vite.middlewares.use('/brain-screens', (_req, res) => {
        res.setHeader('Content-Type', 'text/html');
        res.end(`<!doctype html><html><head><meta charset="utf-8">
          <link rel="stylesheet" href="/src/styles/tokens.css">
          <link rel="stylesheet" href="/src/shell-v0/themes/default.css">
          <style>html, body { margin: 0; height: 100%; background: var(--surface-1); color: var(--text-primary); font-family: var(--font-native); }
            #host { height: 100vh; display: flex; flex-direction: column; } #field { display: block; width: 640px; margin: 120px auto 0; }</style>
          </head><body><div id="host"></div><script type="module">
          import '/src/shell-v0/views/BrainSurface.ts';
          import '/src/shell-v0/views/search-v3/Sv3Composer.ts';
          import { createMockHostApi } from '/src/shell-v0/plugin-api/testHostApi.ts';
          import { startAiStateStore } from '/src/shell-v0/state/aiStateStore.ts';
          import { refreshAiInstallNow } from '/src/shell-v0/utils/aiInstallPoll.ts';
          import { sv3Tokens } from '/src/shell-v0/views/search-v3/sv3-tokens.css.ts';
          // The Search window's tokens, adopted on the field's host as the window adopts them on its own.
          customElements.define('sv3-token-host', class extends HTMLElement {
            constructor() { super(); const root = this.attachShadow({ mode: 'open' }); root.adoptedStyleSheets = [sv3Tokens.styleSheet]; root.append(document.createElement('slot')); }
          });
          const settle = async () => { for (let i = 0; i < 6; i++) await new Promise(requestAnimationFrame); };
          window.readInstall = () => refreshAiInstallNow();
          window.mountBrain = async () => {
            startAiStateStore('');
            const brain = document.createElement('jf-brain-surface'); brain.apiBase = '';
            brain.host_ = createMockHostApi({ data: { invokeOperation: async () => ({ success: true, structuredData: null }) } });
            document.querySelector('#host').append(brain); await brain.updateComplete; await settle();
          };
          window.mountField = async () => {
            startAiStateStore('');
            const box = document.createElement('sv3-token-host'); box.id = 'field';
            if (document.documentElement.dataset.theme === 'light') box.setAttribute('theme', 'light');
            const field = document.createElement('jf-sv3-composer'); field.state = 'hero'; field.tier = 'search';
            box.append(field); document.querySelector('#host').append(box); await field.updateComplete; await settle();
          };
          document.body.dataset.ready = 'true';
          </script></body></html>`);
      }); },
    }] });
  await server.listen(); const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/brain-screens`;
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

/** The Engine's answers for one picture. */
function answers(picture: Picture) {
  const card = picture === 'fm-14' || picture === 'fm-16';
  const installed = picture === 'fm-13' || card;
  // The fixture's operating block, each part kept valid for the strict status schema (status-response.ts).
  const operating = liveStatus.operating as Record<string, Record<string, unknown>>;
  const chat = { ...operating.chat, state: card ? 'READY' : 'OFF', reason: null, reasonCode: null, load: null, queued: 0, blockedBy: null };
  const gpu = card ? { ...operating.gpu, adapter: 'RTX 4070' } : { ...operating.gpu, profile: 'NO_GPU', profileSource: null, adapter: null,
    adapterFreeBytes: null, adapterTotalBytes: null, ourDedicatedBytes: null, ourSharedBytes: null, externalUsedBytes: null, spillSuspected: null, source: 'NONE' };
  const status = { ...liveStatus,
    inference: { ...(liveStatus.inference as object), ...(card ? { phase: 'ONLINE', chatEnabledSpec: true, engineState: 'Healthy', engineReason: 'engine-healthy', procedure: '' }
      : { phase: 'OFFLINE', chatEnabledSpec: false, engineState: 'Down', engineReason: installed ? 'no-gpu' : 'not-installed', procedure: '' }) },
    gpu: card ? { available: true, cudaFunctional: true, totalVramBytes: 12 * GB, usedVramBytes: Math.round(5.8 * GB), recentUtilizationPercent: [], recentMemoryUtilizationPercent: [] }
      : { available: false, deviceCount: 0, recentUtilizationPercent: [], recentMemoryUtilizationPercent: [] },
    worker: { ...(liveStatus.worker as Record<string, object>), core: { ...(liveStatus.worker as Record<string, object>).core, indexedDocuments: 8, searchableDocuments: 8, ownSearchableDocuments: 8 } },
    operating: { ...operating, degradations: [], chat, gpu, indexing: { ...operating.indexing, state: 'IDLE', reasons: [], pendingJobs: 0 } } };
  const inference = card ? { mode: 'online', available: true, activeModelId: 'qwen3.5-9b', gpu: { cudaAvailable: true, vramDescription: 'RTX 4070' } }
    : { mode: 'offline', available: false };
  const fullyInstalled = { ...engine.completed!.status!, installedFully: true, phase: 'idle', message: '', repairNeeded: false };
  const recorded = picture === 'fm-12' ? engine.running! : picture === 'fm-12b' ? engine.failedPartsMissing! : engine.completed!;
  let install: Record<string, unknown> = installed ? fullyInstalled : recorded.status!;
  if (picture === 'fm-12b') {
    const stopped = structuredClone(recorded.status!) as { packages: Array<{ packageId: string; bytesDownloaded: number; bytesTotal: number }> };
    const splade = stopped.packages.find(p => p.packageId === 'splade')!;
    splade.bytesDownloaded = 120 * MB; splade.bytesTotal = 956 * MB;
    install = stopped as never;
  }
  const components: Component[] = (engine.firstRunPreview!.preview!.components).filter(c => c.id !== 'chat').map(c => ({ ...c, state: installed ? 'installed' : c.state, downloadBytes: installed ? 0 : c.downloadBytes }));
  components.push({ id: 'chat', label: 'Chat model', declinable: true, declined: false, necessity: 'adds-feature', tier: 'llm', totalBytes: Math.round(5.7 * GB),
    downloadBytes: 0, state: card ? 'installed' : 'unavailable', unavailableReason: card ? '' : 'Chat model requires a CUDA-capable GPU (none detected on this system).' });
  const preview = { ...engine.firstRunPreview!.preview!, totalDownloadBytes: installed ? 0 : engine.firstRunPreview!.preview!.totalDownloadBytes, components };
  const presence = installed ? { packages: components.filter(c => c.state === 'installed').map(c => ({ id: c.id, label: c.label, complete: true, blockingIncomplete: false, presentVariantFiles: ['model'], missingFiles: [] })) }
    : recorded.presence ?? engine.restartAfterFailedNothingPlaced!.presence;
  const runtime = { onnxFeatures: installed ? [{ id: 'embed', status: 'active', modelActive: true }, { id: 'reranker', status: card ? 'active' : 'inactive', modelActive: card }] : [],
    ...(card ? { active: { chatProfile: 'standard', mmprojActive: false, gpuLayers: 99, modelPath: '/models/chat/model.gguf' } } : {}) };
  const settings = { ui: { chatEnabled: card }, llm: {}, rerankerEnabled: card, pictureReadingEnabled: false, witness: { acceptedRevision: 0, lastCommittedOperationKey: null } };
  return { status, inference, install, preview, presence, runtime, settings };
}

async function open(picture: Picture, theme: 'dark' | 'light'): Promise<Page> {
  context = await browser.newContext({ viewport: { width: 1024, height: 700 } });
  const page = await context.newPage(); page.setDefaultTimeout(10_000);
  page.on('pageerror', error => console.error('Brain screens page error:', error.message));
  const a = answers(picture);
  await page.route(new URL('/api/**', url).href, route => {
    const address = new URL(route.request().url()).pathname;
    const reply = (body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
    if (address === '/api/status') return reply(a.status);
    if (address === '/api/inference/status') return reply(a.inference);
    if (address.endsWith('/install/status')) return reply({ ...a.install, updatedAtEpochMs: Date.now() });
    if (address.endsWith('/runtime/status')) return reply(a.runtime);
    if (address.endsWith('/packs/status')) return reply({ state: 'idle' });
    if (address.endsWith('/models/status')) return reply(a.presence);
    if (address.endsWith('/plan-preview')) return reply(a.preview);
    if (address.endsWith('/manifest')) return reply({ schemaVersion: 2, packages: [] });
    if (address.endsWith('/settings/v2')) return reply(a.settings);
    if (address.endsWith('/downloaded-files')) return reply({ parts: [] });
    return reply({});
  });
  await page.goto(url, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ state: 'attached', timeout: 45_000 });
  await page.evaluate(t => { document.documentElement.dataset.theme = t; }, theme);
  await page.evaluate(p => p === 'fm-16' ? window.mountField() : window.mountBrain(), picture);
  return page;
}
const HEADLINES: Record<Exclude<Picture, 'fm-16'>, string> = {
  'fm-12': 'Installing AI', 'fm-12b': 'The download stopped', 'fm-13': 'Search understands meaning', 'fm-14': 'AI is ready',
};
async function brainAt(picture: Exclude<Picture, 'fm-16'>, theme: 'dark' | 'light'): Promise<Page> {
  const page = await open(picture, theme);
  await expect.poll(() => page.getByRole('heading', { level: 1 }).textContent(), { timeout: 10_000, message: `${picture} headline` }).toBe(HEADLINES[picture]);
  if (picture === 'fm-12' || picture === 'fm-12b') await expect.poll(() => page.locator('jf-brain-surface ul[aria-label="Parts"] > li').count(), { message: 'parts' }).toBeGreaterThan(2);
  return page;
}

/** The install flow's boxes, in CSS pixels. */
function flowBoxes() {
  const root = document.querySelector('jf-brain-surface')!.shadowRoot!;
  const box = (el: Element | null) => { const r = el!.getBoundingClientRect(); return { top: r.top, bottom: r.bottom, left: r.left, right: r.right, width: r.width, height: r.height }; };
  const bar = root.querySelector('.install-flow .flow-bar');
  const rows = [...root.querySelectorAll('ul[aria-label="Parts"] > li')];
  const actions = [...root.querySelectorAll('.flow-actions > jf-button')];
  return {
    sentence: box(root.querySelector('.brain-sentence')), bar: box(bar), barRadius: getComputedStyle(bar!).borderTopLeftRadius,
    line: box(root.querySelector('.flow-line')), parts: box(root.querySelector('ul[aria-label="Parts"]')),
    rows: rows.map(row => ({ height: row.getBoundingClientRect().height, columns: [...row.children].map(c => c.getBoundingClientRect().width),
      gaps: [...row.children].slice(1).map((c, i) => c.getBoundingClientRect().left - row.children[i]!.getBoundingClientRect().right), width: row.getBoundingClientRect().width })),
    partBars: [...root.querySelectorAll('.flow-part-bar')].map(b => b.getBoundingClientRect().height),
    actionGap: actions.length === 2 ? actions[1]!.getBoundingClientRect().left - actions[0]!.getBoundingClientRect().right : null,
  };
}

const themes = ['dark', 'light'] as const;
const flows = (['fm-12', 'fm-12b'] as const).flatMap(picture => themes.map(theme => ({ picture, theme })));
it.each(flows)('A5 $picture $theme: the install bar, its gaps, the part rows and the actions measure as drawn', async ({ picture, theme }) => {
  const page = await brainAt(picture, theme);
  const m = await page.evaluate(flowBoxes);
  expect(m.bar.height, 'bar height').toBe(8);
  expect(m.barRadius, 'bar corners').toBe('4px');
  expect(m.bar.top - m.sentence.bottom, 'gap above the bar').toBe(18);
  expect(m.line.top - m.bar.bottom, 'gap above the amounts line').toBe(8);
  expect(m.parts.top - m.line.bottom, 'gap above the parts list').toBe(18);
  expect(m.rows.length).toBeGreaterThan(2);
  for (const row of m.rows) {
    expect(row.columns.slice(1), 'second and third columns').toEqual([190, 110]);
    expect(row.columns[0], 'the name takes the rest').toBeCloseTo(row.width - 190 - 110 - 2 * 16, 3);
    expect(row.gaps, 'column gaps').toEqual([16, 16]);
    expect(row.height, 'row height').toBe(42);
  }
  for (const height of m.partBars) expect(height, 'part bar height').toBe(3);
  expect(m.actionGap, 'gap between the two actions').toBe(6);
}, 60_000);

it.each([
  ...themes.map(theme => ({ picture: 'fm-13' as const, theme, links: ['Advanced settings', 'Remove AI models'] })),
  ...themes.map(theme => ({ picture: 'fm-14' as const, theme, links: ['Advanced settings', 'Engine history in Health'] })),
])('A5 $picture $theme: the foot links and their gap', async ({ picture, theme, links }) => {
  const page = await brainAt(picture, theme);
  const foot = await page.evaluate(() => {
    const items = [...document.querySelector('jf-brain-surface')!.shadowRoot!.querySelectorAll('.brain-links > button, .brain-links > a')];
    return { words: items.map(i => (i.textContent ?? '').replace(/\s+/g, ' ').trim()), gap: items.length === 2 ? items[1]!.getBoundingClientRect().left - items[0]!.getBoundingClientRect().right : null,
      sizes: items.map(i => getComputedStyle(i).fontSize) };
  });
  // .branch{top:22px} .nd{top:124px}: the step circles stand 102 px below the "Text in scans and images" circle.
  const rows = await page.evaluate(() => {
    const root = document.querySelector('jf-brain-surface')!.shadowRoot!;
    const top = (sel: string) => root.querySelector(sel)!.getBoundingClientRect().top;
    return top('.brain-path > .path-step[data-feature="meaning"] > .step-symbol') - top('.picture-branch .step-symbol');
  });
  expect(rows, 'branch circle to step circles').toBe(102);
  expect(foot.words).toEqual(links);
  expect(foot.gap, 'gap between the links').toBe(28);
  expect(foot.sizes).toEqual(['13px', '13px']);
}, 60_000);

it.each(themes)('A5 fm-16 %s: Search / Ask has 8 px corners and the empty field keeps no room for Clear', async theme => {
  const page = await open('fm-16', theme);
  const control = page.locator('jf-sv3-composer [data-testid="sv3-composer-tier"]');
  await expect.poll(() => control.count(), { timeout: 10_000, message: 'Search / Ask' }).toBe(1);
  const m = await page.evaluate(() => {
    const root = document.querySelector('jf-sv3-composer')!.shadowRoot!;
    const control = root.querySelector('[data-testid="sv3-composer-tier"]')!;
    const corners = (el: Element) => { const s = getComputedStyle(el); return [s.borderTopLeftRadius, s.borderTopRightRadius, s.borderBottomRightRadius, s.borderBottomLeftRadius]; };
    const field = root.querySelector('.search-field .field')!.getBoundingClientRect();
    const editor = root.querySelector('.search-field .editor')!.getBoundingClientRect();
    const options = [...control.querySelectorAll('button')];
    return { words: options.map(o => o.textContent?.trim()), control: corners(control), options: options.map(corners),
      height: control.getBoundingClientRect().height, optionHeights: options.map(o => o.getBoundingClientRect().height),
      optionSizes: options.map(o => getComputedStyle(o).fontSize), room: field.right - editor.right,
      clear: root.querySelector('[data-testid="sv3-search-clear"]') !== null };
  });
  expect(m.words).toEqual(['Search', 'Ask']);
  expect(m.control).toEqual(['8px', '8px', '8px', '8px']);
  for (const option of m.options) expect(option).toEqual(['8px', '8px', '8px', '8px']);
  expect(m.height, 'control height').toBe(28);
  expect(m.optionHeights).toEqual([24, 24]);
  expect(m.optionSizes).toEqual(['12px', '12px']);
  expect(m.clear).toBe(false);
  expect(m.room, 'space kept after the empty text box').toBe(0);
}, 60_000);
