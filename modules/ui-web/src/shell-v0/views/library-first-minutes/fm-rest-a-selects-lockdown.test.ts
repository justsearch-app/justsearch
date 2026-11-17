// SPDX-License-Identifier: Apache-2.0
// fm-rest-a, measured in headless Chromium over a private Vite server with a stubbed backend (no Engine,
// no shared dev stack):
//  S10 every native select in shell-v0 is drawn by the page in the theme's colours, closed and open, in dark
//      and light: no platform appearance, the field and each option in --surface-1 / --text-primary as the
//      page's tokens resolve where the select is, the page's own chevron, and readable (4.5:1).
//  G2 the heading of System › Activity is on the size ladder's heading step, 20 px.
// Run with npx vitest run -c vitest.config.lockdown.ts src/shell-v0/views/library-first-minutes/fm-rest-a-selects-lockdown.test.ts
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

/**
 * Each component with a native select, mounted as the app mounts it, in its own place on the page; where a
 * select needs state the stub cannot give (an unlocked chat store, a recorded plugin decision, workspace
 * profiles, the developer audience) the element's own state is set, and it renders the select itself.
 */
const PAGE = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"><style>body { background: var(--surface-0); margin: 24px; }</style></head><body><script type="module">
const params = new URLSearchParams(location.search);
document.documentElement.setAttribute('data-theme', params.get('theme') || 'dark');
const json = (body) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
window.fetch = async () => json({ entries: [], items: [], conversations: [], sessions: [] });
window.EventSource = class { constructor() { this.readyState = 0; } addEventListener() {} removeEventListener() {} close() {} };
const { createMockHostApi } = await import('/src/shell-v0/plugin-api/testHostApi.ts');
const { selectIndexingProgress } = await import('/src/shell-v0/state/indexingProgress.ts');
await import('/src/shell-v0/components/IndexingControls.ts');
await import('/src/shell-v0/renderers/controls/EnterActionPickerRenderer.ts');
await import('/src/shell-v0/renderers/controls/EnumControl.ts');
await import('/src/shell-v0/components/PanePicker.ts');
await import('/src/shell-v0/components/ActionLedgerView.ts');
await import('/src/shell-v0/views/search-v3/Sv3Filters.ts');
await import('/src/shell-v0/views/SecuritySurface.ts');
await import('/src/shell-v0/views/SettingsSurface.ts');
await import('/src/shell-v0/views/ActivitySurface.ts');
const place = (el) => { const box = document.createElement('div'); box.append(el); document.body.append(box); return el; };

const pause = place(document.createElement('jf-indexing-controls'));
pause.dataset.select = 'IndexingControls'; pause.setAttribute('allow-pause', '');
pause.progress = selectIndexingProgress({ worker: { core: { indexState: 'INDEXING', pendingJobs: 8 } },
  operating: { indexing: { state: 'RUNNING', reasons: [] } } }, true, 16, []);

for (const [tag, name, renderer] of [['jf-enter-action-picker', 'EnterActionPickerRenderer', 'enter-action-select'], ['jf-enum-control', 'EnumControl', null]]) {
  const el = place(document.createElement(tag));
  el.dataset.select = name;
  el.schema = { type: 'string', title: 'Enter action', enum: ['open', 'reveal', 'preview'], ...(renderer ? { 'x-ui-renderer': renderer } : {}) };
  el.uischema = { type: 'Control', scope: '#/properties/defaultAction' };
  el.data = 'open'; el.enabled = true; el.visible = true; el.path = 'defaultAction'; el.onChange = () => {};
}

const pane = place(document.createElement('jf-pane-picker'));
pane.dataset.select = 'PanePicker';
pane.candidates = [{ id: 'core.library-surface', label: 'Library' }, { id: 'core.activity-surface', label: 'Activity' }];
pane.selectedId = 'core.library-surface';

const activity = document.createElement('jf-activity-surface');
activity.host_ = createMockHostApi();
place(activity);
// The feed Activity embeds (System › Activity › Filter), on its own so its select reports under its component.
const ledger = place(document.createElement('jf-action-ledger'));
ledger.dataset.select = 'ActionLedgerView';

const filters = place(document.createElement('jf-sv3-filters'));
filters.dataset.select = 'Sv3Filters';
filters.setAttribute('open', '');

const security = document.createElement('jf-security-surface');
security.dataset.select = 'SecuritySurface';
security.host_ = createMockHostApi();
place(security);

// Settings three times: its first category (Keyboard's Enter action), Security & privacy (a plugin's
// recorded permission) and Advanced with developer tools (workspace profiles).
const settings = ['', 'security-privacy', 'advanced'].map(category => {
  const el = document.createElement('jf-settings-surface');
  el.dataset.select = 'SettingsSurface';
  el.dataset.category = category;
  el.host_ = createMockHostApi();
  return place(el);
});

for (const el of document.querySelectorAll('body > div > *')) await el.updateComplete;
await new Promise(resolve => setTimeout(resolve, 300));
security.encState = 'unlocked'; // Security › Chat protection, unlocked: its auto-lock select shows.
await security.updateComplete;
(await import('/src/shell-v0/state/viewerAudienceState.ts')).setViewerAudience('DEVELOPER');
(await import('/src/shell-v0/state/developerToolsState.ts')).setShowDeveloperTools(true);
for (const el of settings.filter(el => el.dataset.category)) {
  el.activeCategory = el.dataset.category;
  el.consents = [{ contributorId: 'example.plugin', capability: 'network', decision: 'deny', decidedAt: '2026-11-17T09:00:00Z' }];
  el.workspaceProfiles = [{ id: 'work', label: 'Work' }];
  await el.updateComplete;
}
await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
document.body.dataset.ready = 'true';
</script></body></html>`;

/** Every select under a component (through open shadow roots), as drawn, with the tokens where it is. */
const SEEN = `(() => {
  const all = (root) => [...root.querySelectorAll('select'), ...[...root.querySelectorAll('*')].flatMap(el => el.shadowRoot ? all(el.shadowRoot) : [])];
  const tokenAt = (node, name) => { const probe = document.createElement('i'); probe.style.color = 'var(' + name + ')';
    node.getRootNode().append(probe); const value = getComputedStyle(probe).color; probe.remove(); return value; };
  const canvas = document.createElement('canvas'); canvas.width = canvas.height = 1;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  const pixel = (...colors) => { ctx.clearRect(0, 0, 1, 1); for (const c of ['#808080', ...colors]) { ctx.fillStyle = c; ctx.fillRect(0, 0, 1, 1); }
    return [...ctx.getImageData(0, 0, 1, 1).data].slice(0, 3); };
  const lum = rgb => { const c = rgb.map(v => { const n = v / 255; return n <= .04045 ? n / 12.92 : ((n + .055) / 1.055) ** 2.4; });
    return .2126 * c[0] + .7152 * c[1] + .0722 * c[2]; };
  const contrast = (fore, back) => { const b = lum(pixel(back)), f = lum(pixel(back, fore)); return (Math.max(b, f) + .05) / (Math.min(b, f) + .05); };
  return [...document.querySelectorAll('[data-select]')].flatMap(host => all(host.shadowRoot ?? host).map(select => {
    const s = getComputedStyle(select);
    return { component: host.dataset.select, name: select.getAttribute('aria-label') || select.id || select.dataset.testid || [...(select.closest('label')?.childNodes ?? [])].filter(node => node.nodeType === 3).map(node => node.textContent).join('').trim() || '?',
      appearance: s.appearance, background: s.backgroundColor, color: s.color, chevron: s.backgroundImage,
      surface: tokenAt(select, '--surface-1'), text: tokenAt(select, '--text-primary'),
      contrast: contrast(s.color, s.backgroundColor),
      options: [...select.options].map(option => { const o = getComputedStyle(option); return { background: o.backgroundColor, color: o.color }; }) };
  }));
})()`;

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let context: BrowserContext | undefined;
let output: string;
let origin: string;

beforeAll(async () => {
  output = await mkdtemp(path.join(process.env.TMPDIR ?? tmpdir(), 'fm-rest-a-selects-'));
  server = await createServer({
    configFile: false, root: process.cwd(), cacheDir: output, publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{ name: 'fm-rest-a-selects', configureServer(vite) {
      vite.middlewares.use((req, res, next) => {
        if (req.url?.split('?')[0] !== '/selects') return next();
        res.setHeader('Content-Type', 'text/html');
        res.end(PAGE);
      });
    } }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  origin = `http://127.0.0.1:${address.port}`;
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

async function open(theme: 'dark' | 'light'): Promise<Page> {
  context = await browser.newContext({ viewport: { width: 1200, height: 2400 }, reducedMotion: 'reduce', colorScheme: theme });
  const page = await context.newPage();
  page.on('pageerror', error => console.info('pageerror', error.message));
  await page.goto(`${origin}/selects?theme=${theme}`, { timeout: 45_000 });
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  return page;
}

/** The selects the app draws, by component and name (S10's list). */
const EXPECTED = [
  ['IndexingControls', 'Pause duration'],
  ['EnterActionPickerRenderer', 'Enter action'],
  ['EnumControl', 'Enter action'],
  ['PanePicker', 'Choose right-pane surface'],
  ['ActionLedgerView', 'routine-choice'],
  // The page loads no message catalogue: the filters' names read as their keys.
  ['Sv3Filters', 'search.filters.minimum-unit'],
  ['Sv3Filters', 'search.filters.maximum-unit'],
  ['SecuritySurface', 'enc-autolock'],
  ['SettingsSurface', 'consent-decision-select'],
  ['SettingsSurface', 'workspace-profile-inherits-from'],
  ['SettingsSurface', 'workspace-profile-switcher'],
];

describe('S10 every native select is drawn in the theme\'s colours', () => {
  it.each(['dark', 'light'] as const)('%s: closed and open, every select uses --surface-1 / --text-primary and its own chevron', async theme => {
    const page = await open(theme);
    const seen = await page.evaluate(SEEN) as Array<{ component: string; name: string; appearance: string; background: string; color: string;
      chevron: string; surface: string; text: string; contrast: number; options: Array<{ background: string; color: string }> }>;
    const found = seen.map(select => [select.component, select.name]);
    for (const select of EXPECTED) expect(found, `${select.join(' · ')} drawn`).toContainEqual(select);
    for (const select of seen) {
      const at = `${select.component} · ${select.name}`;
      expect(select.appearance, at).toBe('none');
      expect(select.background, at).toBe(select.surface);
      expect(select.color, at).toBe(select.text);
      expect(select.contrast, at).toBeGreaterThanOrEqual(4.5);
      expect(select.options.length, at).toBeGreaterThan(0);
      for (const option of select.options) expect(option, `${at}: open, its options`).toEqual({ background: select.surface, color: select.text });
    }
    // With no platform arrow, each select shows the shared fragment's own chevron.
    for (const select of seen) expect(select.chevron, `${select.component} · ${select.name}: the page's own chevron`).toContain('linear-gradient');
  }, 120_000);
});

describe('G2 the heading of System › Activity is on the size ladder', () => {
  it('20 px, the ladder\'s heading step (--font-size-lg)', async () => {
    const page = await open('dark');
    const heading = await page.evaluate(() => {
      const h2 = document.querySelector('jf-activity-surface')!.shadowRoot!.querySelector('.header h2')!;
      return { words: h2.textContent!.trim(), size: getComputedStyle(h2).fontSize };
    });
    expect(heading).toEqual({ words: 'Activity', size: '20px' });
  }, 90_000);
});
