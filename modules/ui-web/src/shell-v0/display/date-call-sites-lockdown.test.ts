// SPDX-License-Identifier: Apache-2.0
// Real Lit renderers after real SES lockdown, on a private Vite server and headless browser.
import { afterAll, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import path from 'node:path';

const HARNESS = `<!doctype html><html><head><meta charset="utf-8"></head><body>
<script type="module">
try {
// Match main.jsx: Lit initializes Symbol metadata before SES freezes intrinsics.
const { render } = await import('/node_modules/lit/index.js');
await import('/node_modules/ses/index.js');
lockdown({ errorTaming: 'unsafe', consoleTaming: 'unsafe', domainTaming: 'unsafe', overrideTaming: 'severe' });
const date = new Date('2026-11-09T13:24:35Z');
const stamp = date.getTime();
const iso = date.toISOString();
const full = new Intl.DateTimeFormat(undefined, {
  year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric',
}).format(date);
const clock = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: 'numeric', second: 'numeric' });
const minutes = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' }).format(date);
const results = {};
const read = (name, actual, expected) => { results[name] = { actual, expected }; };
const show = (template) => { const root = document.createElement('div'); render(template, root); return root; };
const text = node => node?.textContent.trim();

const { SettingsSurface } = await import('/src/shell-v0/views/SettingsSurface.ts');
const settings = new SettingsSurface();
settings.viewerAudience = 'DEVELOPER';
settings.consents = [{ contributorId: 'date-test', capability: 'read', decision: 'deny', decidedAt: stamp }];
read('consent timestamp', text(show(settings.renderPluginPermissions()).querySelector('tbody td:nth-child(4)')), [full]);
const profiles = await import('/src/shell-v0/substrates/profiles/index.ts');
const oldLabel = 'Snapshot @ previously saved text';
profiles.createProfileFromCurrent('date-test.old', oldLabel);
const before = new Date();
await settings.handleSnapshotWorkspaceProfile();
const after = new Date();
const options = [...show(settings.renderWorkspaceProfilesDeveloper()).querySelectorAll('[data-testid="workspace-profile-switcher"] option')].map(text);
read('new snapshot label', options.find(label => label.startsWith('Snapshot @') && label !== oldLabel),
  [before, after].map(date => 'Snapshot @ ' + clock.format(date)));
read('saved snapshot label', options.find(label => label === oldLabel), [oldLabel]);

const { HealthSurface } = await import('/src/shell-v0/views/HealthSurface.ts');
const health = new HealthSurface();
health.inference = { generation: 1 };
health.transitions = [{ timestampMs: stamp, fromMode: 'OFFLINE', toMode: 'ONLINE', reason: 'READY', durationMs: 10, success: true }];
let root = show(health.renderTransitionTimeline());
read('transition tooltip', text(root.querySelector('circle title')), [clock.format(date) + ' · gen=1 · offline → online · ready · 10ms']);
read('transition time', text(root.querySelector('[data-testid="brain-transition-row"] span:nth-child(2)')), [clock.format(date)]);
health.tracesAvailable = true;
health.recentSpans = [{ start: iso, name: 'test span' }];
read('trace start', text(show(health.renderTraceExplorer()).querySelector('[data-testid="brain-trace-row"] span:nth-child(2)')), [clock.format(date)]);
health.recentSpans = [{ name: 'undated span' }];
read('missing trace start', text(show(health.renderTraceExplorer()).querySelector('[data-testid="brain-trace-row"] span:nth-child(2)')), ['—']);

const { EngineRecovery } = await import('/src/shell-v0/components/EngineRecovery.ts');
const recovery = new EngineRecovery();
recovery.supervisor = { state: 'starting', retryAt: stamp };
read('automatic retry', [...show(recovery.render()).querySelectorAll('p')].map(text).find(t => t.startsWith('Automatic retry')), ['Automatic retry at ' + clock.format(date) + '.']);
const { engineLossReason } = await import('/src/shell-v0/state/engineLoss.ts');
read('engine loss retry', engineLossReason({ cause: 'stopped', action: { kind: 'idle' }, supervisor: { state: 'exhausted', retryAt: stamp } }),
  ['The engine stopped 0 times, so JustSearch stopped restarting it. JustSearch tries once more at ' + minutes + '.']);

const { renderEventWhen } = await import('/src/shell-v0/components/eventRow.ts');
read('ledger tooltip', show(renderEventWhen(iso)).querySelector('time')?.title, [full]);
read('invalid ledger timestamp', show(renderEventWhen('invalid')).querySelector('time')?.title, ['invalid']);
const { EffectLine } = await import('/src/shell-v0/components/EffectLine.ts');
const effect = new EffectLine();
effect.effect = { kind: 'navigate', to: 'core.library-surface' };
effect.timestamp = iso;
read('effect tooltip', show(effect.render()).querySelector('time')?.title, [full]);

const { projectFact } = await import('/src/shell-v0/display/facts.ts');
const { __feedForTest, getAiState } = await import('/src/shell-v0/state/aiStateStore.ts');
__feedForTest({ status: { worker: { core: { indexedDocuments: 12 }, countObservations: { searchable: { state: 'stale', observedAtMs: stamp } } } } });
read('observation provenance', projectFact('core.files', getAiState()).provenance, ['Last known · observed ' + full]);
const { HealthLitView } = await import('/src/shell-v0/views/HealthLitView.ts');
read('health event time', text(show(new HealthLitView().renderOccurrence({ timestamp: iso, severity: 'INFO', id: 'test', body: {} })).querySelector('.occurrence-time')), [clock.format(date)]);
const { AgentEmitterDemo } = await import('/src/shell-v0/components/AgentEmitterDemo.ts');
const demo = new AgentEmitterDemo();
const logBefore = new Date();
demo.appendLog({ kind: 'data-recorded', resultKey: 'date-test' });
read('agent demo time', demo.log[0].at, [logBefore, new Date()].map(date => clock.format(date)));

const { confirmConversationTree } = await import('/src/shell-v0/components/chat/conversationDelete.ts');
const abort = new AbortController();
const pending = confirmConversationTree([], [{ id: 'date-test', title: 'Dated conversation', depth: 0, createdAt: stamp }], '', abort.signal);
await new Promise(resolve => setTimeout(resolve, 0));
const dialog = document.querySelector('jf-confirm-dialog');
read('conversation creation time', text(show(dialog.message).querySelector('li > div')), ['date-test · Created ' + full]);
abort.abort();
await pending;
window.dateReadings = { frozen: Object.isFrozen(Date.prototype), results };
} catch (error) { window.dateHarnessError = String(error); }
</script></body></html>`;

let server: ViteDevServer;
let browser: Browser;
let output: string;
const readings: Record<string, { actual: string; expected: string[] }>[] = [];

beforeAll(async () => {
  output = await mkdtemp(path.resolve('../..', 'tmp-date-lockdown-'));
  server = await createServer({
    configFile: false, root: process.cwd(), cacheDir: output, publicDir: false, logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{ name: 'date-lockdown-harness', configureServer(vite) {
      vite.middlewares.use((req, res, next) => {
        if (req.url !== '/dates') return next();
        res.setHeader('Content-Type', 'text/html');
        res.end(HARNESS);
      });
    } }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  browser = await chromium.launch({ headless: true, args: ['--disable-gpu'] });
  for (const [locale, timezoneId] of [['en-US', 'America/New_York'], ['de-DE', 'Europe/Berlin']]) {
    const page = await browser.newPage({ locale, timezoneId });
    try {
      page.on('pageerror', error => console.error(error));
      await page.route(url => url.pathname.startsWith('/api/'), route => route.fulfill({ json: {} }));
      await page.goto(`http://127.0.0.1:${address.port}/dates`);
      await page.waitForFunction(() => 'dateReadings' in window || 'dateHarnessError' in window);
      expect(await page.evaluate(() => Reflect.get(window, 'dateHarnessError'))).toBeUndefined();
      const result = await page.evaluate(() => Reflect.get(window, 'dateReadings'));
      expect(result.frozen).toBe(true);
      readings.push(result.results);
    } finally { await page.close(); }
  }
}, 60_000);

afterAll(async () => {
  await browser?.close();
  await server?.close();
  if (output) await rm(output, { recursive: true, force: true });
});

it.each([
  'consent timestamp', 'new snapshot label', 'transition tooltip', 'transition time', 'trace start',
  'automatic retry', 'engine loss retry', 'ledger tooltip', 'effect tooltip', 'observation provenance',
  'health event time', 'agent demo time', 'conversation creation time',
])('formats %s as requested under SES lockdown', name => {
  for (const result of readings) {
    expect(result[name]?.expected).toContain(result[name]?.actual);
  }
});

it('preserves saved snapshot labels and missing or invalid timestamp fallbacks', () => {
  for (const result of readings) {
    for (const name of ['saved snapshot label', 'missing trace start', 'invalid ledger timestamp']) {
      expect(result[name]?.expected).toContain(result[name]?.actual);
    }
  }
});
