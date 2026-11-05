// SPDX-License-Identifier: Apache-2.0
// Real pointer and keyboard activation in headless Chromium: one interaction answers one prompt.
// Run: npx vitest run --config vitest.config.lockdown.ts src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts
// Requires the same installed Chromium as boot-lockdown.test.ts; no Engine is needed.
import { afterAll, afterEach, beforeAll, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type BrowserContext, type BrowserServer, type Page } from 'playwright-core';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import path from 'node:path';

let server: ViteDevServer;
let browserServer: BrowserServer;
let browser: Browser;
let context: BrowserContext;
let output: string;
let url: string;

interface ConsentState { granted: Record<string, string | null>; pending: string[]; stored: string }
declare global {
  interface Window {
    requestPlugin(id: string): void;
    consentState(): ConsentState;
    requestApproval(id: string, typed: boolean): void;
    requestAgent(id: string, typed: boolean): void;
    requestLiveAgent(id: string, typed: boolean, cancellable?: boolean): void;
    requestPendingAgent(id: string): Promise<void>;
    answers: Record<string, boolean>;
  }
}

beforeAll(async () => {
  const temporaryRoot = path.resolve('../..', 'tmp');
  await mkdir(temporaryRoot, { recursive: true });
  output = await mkdtemp(path.join(temporaryRoot, 'authorization-activation-'));
  server = await createServer({
    configFile: false,
    root: process.cwd(),
    cacheDir: output,
    publicDir: false,
    logLevel: 'silent',
    server: { host: '127.0.0.1', port: 0 },
    plugins: [{
      name: 'isolated-authorization-host',
      configureServer(vite) {
        vite.middlewares.use('/authorization-activation', (_req, res) => {
          res.setHeader('Content-Type', 'text/html');
          res.end(`<!doctype html><html style="font-size:16px"><head>
            <link rel="stylesheet" href="/src/styles/tokens.css">
            </head><body><script type="module">
            import '/src/shell-v0/components/AuthorizationHost.ts';
            import '/src/shell-v0/components/StatusPill.ts';
            import { requestCapability, checkCapability, listPendingConsentRequests, restoreConsentFromStorage }
              from '/src/shell-v0/substrates/consent/index.ts';
            import { requestAuthorization } from '/src/shell-v0/operations/authorizationBroker.ts';
            import { OperationClient } from '/src/shell-v0/operations/OperationClient.ts';
            restoreConsentFromStorage();
            window.requestPlugin = id => { void requestCapability({ contributorId: 'plugin.' + id, capability: 'fs.read' }); };
            // An approved operation is approved over HTTP, as OperationClient does after the ceremony.
            const client = new OperationClient({ apiBase: '', fetchImpl: (input, init) => fetch(input, init) });
            window.requestApproval = (id, typed) => {
              void requestAuthorization({ pendingId: id, operationId: 'core.' + id, gateBehavior: typed ? 'TYPED_CONFIRM' : 'INLINE_CONFIRM' })
                .then(decision => decision.approved ? client.approveByPendingId(id, decision.allowAlways) : null);
            };
            // DE3 — an agent's request: it waits in Needs you; a typed one opens the security check from Review.
            window.answers = {};
            window.requestAgent = (id, typed) => {
              void requestAuthorization({ pendingId: id, operationId: 'core.' + id, gateBehavior: typed ? 'TYPED_CONFIRM' : 'INLINE_CONFIRM',
                riskTier: typed ? 'HIGH' : 'MEDIUM', requestedBy: 'Claude Desktop', arrival: 'needs-you', live: true })
                .then(decision => { window.answers[id] = decision.approved; });
            };
            // An outside agent's request as the pending bridge presents it: its live details are read
            // from the engine's pending store, and an approval is sent over HTTP.
            // \`cancellable\`: a Deny is sent to the engine's reject route first, as the bridge does.
            window.requestLiveAgent = (id, typed, cancellable) => {
              void requestAuthorization({ pendingId: id, operationId: 'core.' + id, gateBehavior: typed ? 'TYPED_CONFIRM' : 'INLINE_CONFIRM',
                riskTier: typed ? 'HIGH' : 'MEDIUM', requestedBy: 'Claude Desktop', argsSummary: '/home/me/' + id,
                arrival: 'needs-you', live: true, ...(cancellable ? { reject: () => client.rejectPending(id) } : {}),
                refresh: async () => {
                  const fresh = await client.peekPending(id);
                  return fresh && { operationId: fresh.operationId, gateBehavior: fresh.gateBehavior, riskTier: fresh.riskTier,
                    argsSummary: fresh.argsSummary, requestedBy: fresh.requestedBy };
                } })
                .then(decision => { window.answers[id] = decision.approved;
                  return decision.approved ? client.approveByPendingId(id, decision.allowAlways) : null; });
            };
            // Read the initially shown details from the same pending route as the Allow refresh.
            window.requestPendingAgent = async id => {
              const initial = await client.peekPending(id);
              void requestAuthorization({ ...initial, pendingId: id, gateBehavior: 'INLINE_CONFIRM',
                arrival: 'needs-you', live: true, refresh: () => client.peekPending(id) })
                .then(decision => { window.answers[id] = decision.approved;
                  return decision.approved ? client.approveByPendingId(id, decision.allowAlways) : null; });
            };
            window.consentState = () => ({
              granted: Object.fromEntries(['first', 'second'].map(id => [id, checkCapability('plugin.' + id, 'fs.read') ?? null])),
              pending: listPendingConsentRequests().map(request => request.contributorId),
              stored: JSON.stringify(localStorage),
            });
            const host = document.createElement('jf-authorization-host');
            const pill = document.createElement('jf-status-pill');
            pill.authorizationHost = () => host;
            host.addEventListener('status-requests-changed', () => pill.status.refresh());
            document.body.append(pill, host);
            document.body.dataset.ready = 'true';
            </script></body></html>`);
        });
      },
    }],
  });
  await server.listen();
  const address = server.httpServer!.address();
  if (!address || typeof address === 'string') throw new Error('No private Vite port');
  url = `http://127.0.0.1:${address.port}/authorization-activation`;
  // Playwright creates its fresh --user-data-dir under the caller's short TMPDIR.
  const env = { ...process.env, CUDA_VISIBLE_DEVICES: '-1', TMPDIR: process.env.TMPDIR ?? output };
  for (const name of ['DISPLAY', 'WAYLAND_DISPLAY']) Reflect.deleteProperty(env, name);
  browserServer = await chromium.launchServer({ headless: true, args: ['--disable-gpu'], env });
  console.info('Owned headless Chromium PID:', browserServer.process().pid);
  browser = await chromium.connect(browserServer.wsEndpoint());
  // Load the page once so Vite's first transform and dependency optimization (the status pill's
  // import graph) are not paid inside the first case's time limit.
  const warm = await browser.newContext();
  const page = await warm.newPage();
  await page.goto(url);
  await page.locator('body[data-ready="true"]').waitFor({ timeout: 45_000 });
  await warm.close();
}, 60_000);

afterEach(async () => { await context?.close(); });
afterAll(async () => {
  await browser?.close();
  await browserServer?.close();
  await server?.close();
  if (output) await rm(output, { recursive: true, force: true });
});

/** Two plugin capability requests queued; the first prompt has been on screen long enough to arm. */
async function twoQueued(): Promise<Page> {
  context = await browser.newContext({ viewport: { width: 1024, height: 768 } });
  const page = await context.newPage();
  await page.goto(url);
  await page.locator('body[data-ready="true"]').waitFor();
  await page.evaluate(() => { window.requestPlugin('first'); window.requestPlugin('second'); });
  await page.getByText('plugin.first requests permission').waitFor();
  await page.waitForTimeout(600);
  return page;
}

const state = (page: Page) => page.evaluate(() => window.consentState());

async function afterReload(page: Page): Promise<ConsentState> {
  await page.reload();
  await page.locator('body[data-ready="true"]').waitFor();
  return state(page);
}

it('a native double-click on Allow always grants only the first plugin, across reload', async () => {
  const page = await twoQueued();
  await page.getByTestId('capability-allow-always').dblclick();
  await page.waitForTimeout(600);
  const now = await state(page);
  expect(now.granted).toEqual({ first: 'allow-always', second: null });
  expect(now.pending).toEqual(['plugin.second']);
  const reloaded = await afterReload(page);
  expect(reloaded.granted).toEqual({ first: 'allow-always', second: null });
  expect(reloaded.stored).not.toContain('plugin.second');
});

it('repeated native activation of the same button answers one prompt', async () => {
  const page = await twoQueued();
  const box = (await page.getByTestId('capability-allow-always').boundingBox())!;
  for (let i = 0; i < 4; ++i) {
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
    await page.waitForTimeout(60);
  }
  await page.waitForTimeout(600);
  const now = await state(page);
  expect(now.granted).toEqual({ first: 'allow-always', second: null });
  expect(now.pending).toEqual(['plugin.second']);
  expect((await afterReload(page)).granted.second).toBeNull();
});

it('Enter pressed and held on the next prompt before it arms answers nothing', async () => {
  const page = await twoQueued();
  await page.getByTestId('capability-allow-always').locator('button').focus();
  await page.keyboard.press('Enter');
  await page.getByText('plugin.second requests permission').waitFor();
  expect((await state(page)).granted.first).toBe('allow-always');
  // Straight onto the next prompt's first decision button, then the key is held: one keydown, the
  // platform's auto-repeat keydowns, the release.
  await page.keyboard.press('Tab');
  await page.keyboard.down('Enter');
  for (let i = 0; i < 12; ++i) {
    await page.waitForTimeout(50);
    await page.keyboard.down('Enter');
  }
  await page.keyboard.up('Enter');
  await page.waitForTimeout(100);
  const now = await state(page);
  expect(now.granted.second).toBeNull();
  expect(now.pending).toEqual(['plugin.second']);
});

it('a fresh Enter press after the delay answers the next prompt exactly once', async () => {
  const page = await twoQueued();
  await page.getByTestId('capability-allow-always').locator('button').focus();
  await page.keyboard.press('Enter');
  await page.getByText('plugin.second requests permission').waitFor();
  await page.keyboard.press('Tab');
  await page.waitForTimeout(450);
  await page.keyboard.press('Enter');
  await page.waitForTimeout(100);
  const now = await state(page);
  expect(now.granted).toEqual({ first: 'allow-always', second: 'deny' });
  expect(now.pending).toEqual([]);
});

it('a deliberate click after the delay answers the next prompt exactly once', async () => {
  const page = await twoQueued();
  await page.getByTestId('capability-allow-always').click();
  await page.getByText('plugin.second requests permission').waitFor();
  await page.waitForTimeout(450);
  await page.getByTestId('capability-deny').click();
  await page.waitForTimeout(100);
  const now = await state(page);
  expect(now.granted).toEqual({ first: 'allow-always', second: 'deny' });
  expect(now.pending).toEqual([]);
  expect(await page.locator('jf-authorization-host').evaluate(host => host.shadowRoot!.querySelector('dialog')!.open)).toBe(false);
});

/** The JSON bodies of every POST /api/authorizations/approve the page sent. */
async function approvals(page: Page): Promise<Array<{ pendingId: string; allowAlways: boolean }>> {
  const bodies: Array<{ pendingId: string; allowAlways: boolean }> = [];
  await page.route('**/api/authorizations/approve', route => {
    bodies.push(route.request().postDataJSON() as { pendingId: string; allowAlways: boolean });
    return route.fulfill({ json: {} });
  });
  return bodies;
}

async function opened(): Promise<Page> {
  context = await browser.newContext({ viewport: { width: 1024, height: 768 } });
  const page = await context.newPage();
  await page.goto(url);
  await page.locator('body[data-ready="true"]').waitFor();
  return page;
}

it.each(['checkbox', 'label'] as const)('S1 a %s click on the next approval’s Always allow 30 ms after it appears does not tick it; a later deliberate Approve sends allowAlways false', async part => {
  const page = await opened();
  const sent = await approvals(page);
  await page.evaluate(() => { window.requestApproval('first', false); window.requestApproval('second', false); });
  // L17 — a one-click prompt names its action in words; its question is the wait anchor.
  await page.getByText('Run “First”?', { exact: true }).waitFor();
  await page.waitForTimeout(600);
  // The next prompt lays out like the first: a stray click lands where the first one's control was.
  const box = page.getByTestId('authorization-allow-always').locator('input');
  const at = (await (part === 'checkbox' ? box : page.getByTestId('authorization-allow-always')).boundingBox())!;
  await page.getByTestId('authorization-approve').click();
  await page.getByText('Run “Second”?', { exact: true }).waitFor();
  const appeared = Date.now();
  await page.waitForTimeout(30);
  await page.mouse.click(at.x + at.width / 2, at.y + at.height / 2);
  expect(Date.now() - appeared, 'the click landed before the prompt armed').toBeLessThan(300);
  expect(await box.isChecked(), 'the early click did not tick it').toBe(false);
  await page.waitForTimeout(450);
  await page.getByTestId('authorization-approve').click();
  await expect.poll(() => sent.length).toBe(2);
  expect(sent).toEqual([{ pendingId: 'first', allowAlways: false }, { pendingId: 'second', allowAlways: false }]);
});

it('S1 a deliberate click on Always allow after arming ticks it and the approval sends allowAlways true', async () => {
  const page = await opened();
  const sent = await approvals(page);
  await page.evaluate(() => window.requestApproval('only', false));
  await page.getByText('Run “Only”?', { exact: true }).waitFor();
  await page.waitForTimeout(450);
  await page.getByTestId('authorization-allow-always').click();
  expect(await page.getByTestId('authorization-allow-always').locator('input').isChecked()).toBe(true);
  await page.getByTestId('authorization-approve').click();
  await expect.poll(() => sent.length).toBe(1);
  expect(sent).toEqual([{ pendingId: 'only', allowAlways: true }]);
});

it('S1 a key pressed in the next typed approval before it arms, and its auto-repeat after, enters nothing; typing after it arms does', async () => {
  const page = await opened();
  const sent = await approvals(page);
  await page.evaluate(() => { window.requestApproval('first', true); window.requestApproval('second', true); });
  await page.getByText('core.first').first().waitFor();
  await page.waitForTimeout(600);
  await page.getByTestId('authorization-deny').click();
  await page.getByText('core.second').first().waitFor();
  const field = page.getByTestId('authorization-typed-input');
  await field.focus();
  const appeared = Date.now();
  await page.keyboard.down('c');
  expect(Date.now() - appeared, 'the key went down before the prompt armed').toBeLessThan(300);
  // Held past the arming delay: the platform's auto-repeat keydowns.
  await page.waitForTimeout(450);
  for (let i = 0; i < 5; ++i) await page.keyboard.down('c');
  await page.keyboard.up('c');
  expect(await field.inputValue(), 'nothing typed by a press begun before arming').toBe('');
  await page.keyboard.type('core.second');
  expect(await field.inputValue()).toBe('core.second');
  await page.getByTestId('authorization-approve').click();
  await expect.poll(() => sent.length).toBe(1);
  expect(sent).toEqual([{ pendingId: 'second', allowAlways: false }]);
});

type Close = 'Escape' | 'dialog.close()';
const closes: Array<[string, Close]> = (['typed', 'capability', 'lightweight'] as const)
  .flatMap(kind => (['Escape', 'dialog.close()'] as const).map(close => [kind, close] as [string, Close]))
  .filter(([kind, close]) => !(kind === 'lightweight' && close === 'Escape')); // a non-modal dialog has no Escape close

it.each(closes)('S2 a %s ceremony closed by %s releases modality; the pill opens the panel and Review reopens it in its modality', async (kind, close) => {
  const page = await opened();
  const sent = await approvals(page);
  if (kind === 'capability') await page.evaluate(() => window.requestPlugin('first'));
  else await page.evaluate(typed => window.requestApproval('arrival', typed), kind === 'typed');
  const dialog = page.locator('jf-authorization-host dialog');
  await expect.poll(() => dialog.evaluate(d => (d as HTMLDialogElement).open)).toBe(true);
  const modal = kind !== 'lightweight';
  expect(await dialog.evaluate(d => d.matches(':modal'))).toBe(modal);
  await page.waitForTimeout(450);
  if (close === 'Escape') {
    await page.getByTestId(kind === 'capability' ? 'capability-deny' : 'authorization-deny').locator('button').focus();
    // The first Escape is refused (cancel prevented); the platform closes a modal on a repeated one.
    for (let i = 0; i < 3 && await dialog.evaluate(d => (d as HTMLDialogElement).open); ++i) await page.keyboard.press('Escape');
  } else await dialog.evaluate(d => (d as HTMLDialogElement).close());
  await expect.poll(() => dialog.evaluate(d => (d as HTMLDialogElement).open)).toBe(false);
  // The platform queues the close event after the dialog closes; the host releases on that event.
  await expect.poll(() => page.evaluate(() => document.documentElement.style.overflow), { message: 'scroll lock released' }).toBe('');
  const panel = page.locator('jf-status-pill jf-status-panel');
  const row = panel.locator(kind === 'capability' ? '[data-fact^="consent:"]' : '[data-fact="authorization:arrival"]');
  await page.locator('jf-status-pill .trigger').click();
  await expect.poll(() => panel.evaluate(p => !p.hasAttribute('hidden')), { message: 'the pill opens the panel' }).toBe(true);
  await row.getByRole('button', { name: /^Review/ }).click();
  await expect.poll(() => dialog.evaluate(d => (d as HTMLDialogElement).open), { message: 'Review reopens it' }).toBe(true);
  expect(await dialog.evaluate(d => d.matches(':modal')), 'in its own modality').toBe(modal);
  expect(await page.evaluate(() => document.documentElement.style.overflow)).toBe(modal ? 'hidden' : '');
  expect(await dialog.getAttribute('data-testid')).toBe(kind === 'capability' ? 'capability-ceremony' : 'authorization-ceremony');
  expect(sent, 'nothing was decided').toEqual([]);
  expect((await state(page)).pending).toEqual(kind === 'capability' ? ['plugin.first'] : []);
});

type Protected = 'typed' | 'capability' | 'agent-typed';
const permutations = (items: Protected[]): Protected[][] => items.length <= 1 ? [items]
  : items.flatMap(item => permutations(items.filter(other => other !== item)).map(rest => [item, ...rest]));

it.each(permutations(['typed', 'capability', 'agent-typed']))(
  'DE4 %s → %s → %s: each protected request opens in the top layer above a skin overlay; an agent request opens no dialog on arrival',
  async (...order) => {
    const page = await opened();
    await approvals(page);
    // A skin's own overlay at the highest z-index cannot cover or spoof the security check.
    await page.evaluate(() => {
      const skin = document.createElement('div');
      skin.id = 'skin-overlay';
      skin.style.cssText = 'position:fixed;inset:0;z-index:99999;background:rgba(255,0,0,.2)';
      document.body.append(skin);
    });
    const dialog = page.locator('jf-authorization-host dialog');
    const isOpen = () => dialog.evaluate(d => (d as HTMLDialogElement).open);
    await page.evaluate(() => window.requestAgent('agent-inline', false));
    for (const kind of order) {
      if (kind === 'capability') await page.evaluate(() => window.requestPlugin('first'));
      else if (kind === 'typed') await page.evaluate(() => window.requestApproval('own-typed', true));
      else await page.evaluate(() => window.requestAgent('agent-typed', true));
    }
    const seen: string[] = [];
    for (let step = 0; step < 3; ++step) {
      if (!await isOpen()) {
        // Only the agent's typed request is left: it waits until Review opens the security check.
        expect(order.indexOf('agent-typed')).toBeGreaterThanOrEqual(0);
        // The skin's overlay also covers the pill; it steps aside only while the pill is used.
        await page.evaluate(() => { document.getElementById('skin-overlay')!.style.display = 'none'; });
        await page.locator('jf-status-pill .trigger').click();
        await page.locator('jf-status-pill jf-status-panel [data-fact="authorization:agent-typed"]').getByRole('button', { name: 'Review' }).click();
        await expect.poll(isOpen).toBe(true);
        await page.evaluate(() => { document.getElementById('skin-overlay')!.style.display = ''; });
      }
      expect(await dialog.evaluate(d => d.matches(':modal')), 'the security check is modal (top layer)').toBe(true);
      const shown = await dialog.getAttribute('data-testid') === 'capability-ceremony' ? 'capability'
        : await dialog.evaluate(d => d.textContent!.includes('core.agent-typed')) ? 'agent-typed' : 'typed';
      expect(await dialog.evaluate(d => d.textContent!.includes('core.agent-inline')), 'the inline agent request never opens').toBe(false);
      await expect.poll(() => page.getByTestId('security-check-header').isVisible()).toBe(true);
      const box = (await dialog.boundingBox())!;
      expect(await page.evaluate(([x, y]) => document.elementFromPoint(x!, y!)?.id !== 'skin-overlay', [box.x + box.width / 2, box.y + 20]),
        'nothing paints over the security check').toBe(true);
      if (shown === 'capability') {
        expect(await page.getByTestId('capability-allow-once').getAttribute('variant'), 'Allow once is the primary').toBe('primary');
        expect(await page.getByTestId('capability-allow-always').getAttribute('variant')).not.toBe('primary');
      }
      seen.push(shown);
      await page.waitForTimeout(450);
      await page.getByTestId(shown === 'capability' ? 'capability-deny' : 'authorization-deny').click();
      const marker = shown === 'capability' ? 'plugin.first' : shown === 'agent-typed' ? 'core.agent-typed' : 'core.own-typed';
      await expect.poll(async () => await isOpen() && await dialog.evaluate((d, text) => d.textContent!.includes(text), marker)).toBe(false);
    }
    expect(seen.slice().sort()).toEqual(['agent-typed', 'capability', 'typed']);
    // The agent's typed request was presented only when reviewed, after the others that opened on arrival.
    expect(seen.indexOf('agent-typed')).toBe(2);
    expect(await page.evaluate(() => window.answers)).toEqual({ 'agent-typed': false });
    expect(await isOpen()).toBe(false);
    await page.evaluate(() => document.getElementById('skin-overlay')!.remove());
    await page.locator('jf-status-pill .trigger').click();
    await page.locator('jf-status-pill jf-status-panel [data-fact="authorization:agent-inline"]').waitFor();
  },
  30_000,
);

const looks = (['dark', 'light', 'forced'] as const).flatMap(look =>
  ([[1280, 800], [900, 800], [600, 400]] as const).map(([width, height]) => [look, width, height] as const));

it.each(looks)('X1/X2 the panel review in %s at %s×%s fits without sideways overflow', async (look, width, height) => {
  context = await browser.newContext({ viewport: { width, height }, ...(look === 'forced' ? { forcedColors: 'active' as const } : {}) });
  const page = await context.newPage();
  await page.goto(url);
  await page.locator('body[data-ready="true"]').waitFor();
  // The frame's own tokens (panel width, spacing), as the app loads them.
  await page.addStyleTag({ url: '/src/shell-v0/themes/default.css' });
  if (look === 'light') await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'light'));
  await page.evaluate(() => window.requestAgent('downloads-' + 'segment/'.repeat(30) + 'end', false));
  await page.locator('jf-status-pill .trigger').click();
  await page.locator('jf-status-pill jf-status-panel article[data-fact^="authorization:"]').getByRole('button', { name: 'Review' }).click();
  const review = page.locator('jf-status-pill jf-status-panel [data-testid="status-review"]');
  await expect.poll(() => review.count(), { message: 'the review opens inside the panel', timeout: 5_000 }).toBe(1);
  const fits = await page.evaluate(() => {
    const panel = document.querySelector('jf-status-pill')!.shadowRoot!.querySelector('jf-status-panel')!.shadowRoot!.querySelector<HTMLElement>('.panel')!;
    const box = panel.getBoundingClientRect();
    return { page: document.documentElement.scrollWidth <= window.innerWidth, panel: panel.scrollWidth <= panel.clientWidth,
      inside: box.left >= 0 && box.right <= window.innerWidth };
  });
  expect(fits).toEqual({ page: true, panel: true, inside: true });
  if (process.env.REVIEW_SHOTS) await page.screenshot({ path: path.join(process.env.REVIEW_SHOTS, `review-${look}-${width}x${height}.png`) });
}, 30_000);

/**
 * How the engine's pending store answers a live-details read: its details, a failure, a slow failure,
 * its details slowly, or a given body.
 */
type Pending = 'same' | 'changed' | 503 | 'slow-503' | 'slow-same' | { body: unknown };
async function pendingStore(page: Page, answers: Record<string, Pending>): Promise<string[]> {
  const reads: string[] = [];
  await page.route('**/api/authorizations/pending/*', async route => {
    const id = decodeURIComponent(new URL(route.request().url()).pathname.split('/').pop()!);
    reads.push(id);
    const answer = answers[id] ?? 'same';
    if (typeof answer === 'object') return route.fulfill({ json: answer.body });
    if (answer === 'slow-503' || answer === 'slow-same') await new Promise(resolve => setTimeout(resolve, 1_500));
    if (answer === 503 || answer === 'slow-503') return route.fulfill({ status: 503, json: {} });
    const typed = id.startsWith('typed');
    return route.fulfill({ json: { pendingId: id, operationId: 'core.' + id, gateBehavior: typed ? 'TYPED_CONFIRM' : 'INLINE_CONFIRM',
      riskTier: typed ? 'HIGH' : 'MEDIUM', argsSummary: answer === 'changed' ? '/home/me' : '/home/me/' + id, requestedBy: 'Claude Desktop' } });
  });
  return reads;
}
const panelRow = (page: Page, id: string) => page.locator(`jf-status-pill jf-status-panel article[data-fact="authorization:${id}"]`);
async function openList(page: Page): Promise<void> {
  await page.locator('jf-status-pill .trigger').click();
  await page.locator('jf-status-pill jf-status-panel article[data-fact^="authorization:"]').first().waitFor();
}

it('DE3 an Allow pressed while the review is still reading the live details sends nothing; unavailable details stay answerable', async () => {
  const page = await opened();
  const sent = await approvals(page);
  const answers: Record<string, Pending> = { pick: 'slow-503' };
  const reads = await pendingStore(page, answers);
  await page.evaluate(() => window.requestLiveAgent('pick', false));
  await openList(page);
  await panelRow(page, 'pick').getByRole('button', { name: 'Review' }).click();
  const review = page.locator('jf-status-pill jf-status-panel [data-testid="status-review"]');
  await review.waitFor();
  await page.waitForTimeout(600);
  // Armed, but the details read on opening have not come back yet.
  await review.locator('[data-answer="allow"]').click();
  await expect.poll(() => reads.length).toBeGreaterThanOrEqual(1);
  await page.waitForTimeout(1_800);
  expect(sent, 'an Allow before the details came back sends nothing').toEqual([]);
  expect(await review.getByRole('alert').textContent()).toContain('details are unavailable');
  answers.pick = 503;
  const before = reads.length;
  await review.locator('[data-answer="allow"]').click();
  await expect.poll(() => reads.length, { message: 'the press reads the details again' }).toBeGreaterThan(before);
  await page.waitForTimeout(300);
  expect(sent, 'nothing is allowed on details that were not read').toEqual([]);
  expect(await page.evaluate(() => window.answers)).toEqual({});
  await page.waitForTimeout(450);
  await review.locator('[data-answer="deny"]').click();
  await expect.poll(() => page.evaluate(() => window.answers)).toEqual({ pick: false });
  expect(sent).toEqual([]);
}, 30_000);

it('DE3 the list Allow reads the live details as part of the press: unavailable or different details allow nothing', async () => {
  const page = await opened();
  const sent = await approvals(page);
  const answers: Record<string, Pending> = { listed: 503 };
  await pendingStore(page, answers);
  await page.evaluate(() => window.requestLiveAgent('listed', false));
  await openList(page);
  await page.waitForTimeout(600);
  const allow = panelRow(page, 'listed').getByRole('button', { name: 'Allow', exact: true });
  await allow.click();
  await page.waitForTimeout(300);
  expect(sent, 'unavailable details allow nothing').toEqual([]);
  expect(await panelRow(page, 'listed').getByRole('alert').textContent()).toContain('details are unavailable');
  answers.listed = 'changed';
  await page.waitForTimeout(450);
  await allow.click();
  await expect.poll(() => panelRow(page, 'listed').getByRole('alert').textContent()).toContain('details changed');
  expect(await panelRow(page, 'listed').locator('.detail').first().textContent(), 'the current details are shown').toBe('/home/me');
  expect(sent).toEqual([]);
  await page.waitForTimeout(450);
  await allow.click();
  await expect.poll(() => sent.map(body => body.pendingId)).toEqual(['listed']);
}, 30_000);

it("DE3 a typed request's Approve in the security check reads the live details as part of the press", async () => {
  const page = await opened();
  const sent = await approvals(page);
  const answers: Record<string, Pending> = { 'typed-check': 503 };
  await pendingStore(page, answers);
  await page.evaluate(() => window.requestLiveAgent('typed-check', true));
  await openList(page);
  await panelRow(page, 'typed-check').getByRole('button', { name: 'Review' }).click();
  const dialog = page.locator('jf-authorization-host dialog');
  await expect.poll(() => dialog.evaluate(d => (d as HTMLDialogElement).open)).toBe(true);
  await page.waitForTimeout(450);
  await page.getByTestId('authorization-typed-input').fill('core.typed-check');
  await page.getByTestId('authorization-approve').click();
  await page.waitForTimeout(300);
  expect(sent, 'the security check allows nothing on unavailable details').toEqual([]);
  expect(await dialog.getByRole('alert').textContent()).toContain('details are unavailable');
  answers['typed-check'] = 'same';
  await page.getByTestId('authorization-approve').click();
  await expect.poll(() => sent.map(body => body.pendingId)).toEqual(['typed-check']);
}, 30_000);

/** Two waiting agent requests, listed one above the other, armed. */
async function twoListed(): Promise<{ page: Page; sent: Array<{ pendingId: string; allowAlways: boolean }> }> {
  const page = await opened();
  const sent = await approvals(page);
  await pendingStore(page, {});
  await page.evaluate(() => { window.requestLiveAgent('first', false); window.requestLiveAgent('second', false); });
  await openList(page);
  await panelRow(page, 'second').waitFor();
  await page.waitForTimeout(600);
  return { page, sent };
}

it("C12 a native double-click on the first row's Allow answers only the first request", async () => {
  const { page, sent } = await twoListed();
  await panelRow(page, 'first').getByRole('button', { name: 'Allow', exact: true }).dblclick();
  await page.waitForTimeout(700);
  expect(sent.map(body => body.pendingId)).toEqual(['first']);
  expect(await page.evaluate(() => window.answers)).toEqual({ first: true });
  expect(await panelRow(page, 'second').count(), 'the second request still waits').toBe(1);
});

it('C12 a click where the next row moved in answers nothing; a fresh click after the delay answers it once', async () => {
  const { page, sent } = await twoListed();
  const box = (await panelRow(page, 'first').getByRole('button', { name: 'Allow', exact: true }).boundingBox())!;
  const at = [box.x + box.width / 2, box.y + box.height / 2] as const;
  await page.mouse.click(...at);
  await expect.poll(() => panelRow(page, 'first').count()).toBe(0);
  const moved = (await panelRow(page, 'second').getByRole('button', { name: 'Allow', exact: true }).boundingBox())!;
  expect(Math.abs(moved.y - box.y), "the second row's Allow is now under the pointer").toBeLessThan(2);
  await page.waitForTimeout(100);
  await page.mouse.click(...at);
  await page.waitForTimeout(300);
  expect(sent.map(body => body.pendingId)).toEqual(['first']);
  await page.waitForTimeout(450);
  await page.mouse.click(...at);
  await expect.poll(() => sent.map(body => body.pendingId)).toEqual(['first', 'second']);
});

it("C12 Enter held on the first row's Allow answers at most one request", async () => {
  const { page, sent } = await twoListed();
  await panelRow(page, 'first').getByRole('button', { name: 'Allow', exact: true }).focus();
  await page.keyboard.down('Enter');
  for (let i = 0; i < 16; ++i) {
    await page.waitForTimeout(50);
    await page.keyboard.down('Enter');
  }
  await page.keyboard.up('Enter');
  await page.waitForTimeout(600);
  expect(sent.length).toBeLessThanOrEqual(1);
  expect(await panelRow(page, 'second').count(), 'the second request still waits').toBe(1);
});

/** The engine's reject route: every Deny it receives, answered with `status` (503 refuses it). */
async function rejections(page: Page, status: { code: number }): Promise<string[]> {
  const received: string[] = [];
  await page.route('**/api/authorizations/reject', route => {
    received.push((route.request().postDataJSON() as { pendingId: string }).pendingId);
    return status.code === 200 ? route.fulfill({ json: { rejected: true } }) : route.fulfill({ status: status.code, json: {} });
  });
  return received;
}

it.each([
  ['no arguments', {}],
  ['null arguments', { argsSummary: null }],
  ['non-text arguments', { argsSummary: 7 }],
])('DE3 initial and fresh pending details with %s refuse Allow, send no approval, and stay answerable', async (_, args) => {
  const page = await opened();
  const sent = await approvals(page);
  const body = { pendingId: 'incomplete', operationId: 'core.incomplete', requestedBy: 'Claude Desktop', ...args };
  const answers: Record<string, Pending> = { incomplete: { body } };
  const reads = await pendingStore(page, answers);
  await page.evaluate(() => window.requestPendingAgent('incomplete'));
  await openList(page);
  await page.waitForTimeout(600);
  await panelRow(page, 'incomplete').getByRole('button', { name: 'Allow', exact: true }).click();
  await expect.poll(() => reads.length).toBe(2);
  await page.waitForTimeout(600);
  expect(sent, 'identical incomplete initial and fresh details cannot authorize a POST').toEqual([]);
  expect(await page.evaluate(() => window.answers)).toEqual({});
  expect((await panelRow(page, 'incomplete').getByRole('alert').allTextContents()).join('')).toContain('details are unavailable');
  answers.incomplete = 'same';
  await panelRow(page, 'incomplete').getByRole('button', { name: 'Allow', exact: true }).click();
  await expect.poll(() => reads.length).toBe(3);
  await page.waitForTimeout(600);
  expect(sent, 'a complete refresh cannot authorize arguments never shown as text').toEqual([]);
  expect((await panelRow(page, 'incomplete').getByRole('alert').allTextContents()).join('')).toContain('details are unavailable');
  await panelRow(page, 'incomplete').getByRole('button', { name: 'Deny', exact: true }).click();
  await expect.poll(() => page.evaluate(() => window.answers)).toEqual({ incomplete: false });
  expect(sent).toEqual([]);
}, 30_000);

it.each([
  ['an empty body', {}],
  ['no operation', { pendingId: 'bad', argsSummary: '/home/me/bad', requestedBy: 'Claude Desktop' }],
  ['no arguments', { pendingId: 'bad', operationId: 'core.bad', requestedBy: 'Claude Desktop' }],
  ['no requester', { pendingId: 'bad', operationId: 'core.bad', argsSummary: '/home/me/bad' }],
  ['a malformed operation', { pendingId: 'bad', operationId: 7, argsSummary: '/home/me/bad', requestedBy: 'Claude Desktop' }],
  ['a null body', null],
] as const)('DE3 a live-details read answering 200 with %s allows nothing and the request stays answerable', async (_, body) => {
  const page = await opened();
  const sent = await approvals(page);
  const answers: Record<string, Pending> = { bad: { body } };
  await pendingStore(page, answers);
  await page.evaluate(() => window.requestLiveAgent('bad', false));
  await openList(page);
  await page.waitForTimeout(600);
  const allow = panelRow(page, 'bad').getByRole('button', { name: 'Allow', exact: true });
  await allow.click();
  await page.waitForTimeout(600);
  expect(sent, 'nothing is allowed on details that are not all there').toEqual([]);
  expect(await page.evaluate(() => window.answers)).toEqual({});
  expect((await panelRow(page, 'bad').getByRole('alert').allTextContents()).join(''), 'and the request stays listed, saying why')
    .toContain('details are unavailable');
  answers.bad = 'same';
  // The message moved the row's controls: a fresh press after the arming delay.
  await page.waitForTimeout(450);
  await allow.click();
  await expect.poll(() => sent.map(request => request.pendingId), { message: 'still answerable' }).toEqual(['bad']);
}, 30_000);

it('C12 a Deny pressed 100 ms after an Allow whose read is slow abandons that Allow: the failed Deny leaves nothing sent', async () => {
  const page = await opened();
  const sent = await approvals(page);
  const answers: Record<string, Pending> = { race: 'same' };
  const reads = await pendingStore(page, answers);
  const status = { code: 503 };
  const denied = await rejections(page, status);
  await page.evaluate(() => window.requestLiveAgent('race', false, true));
  await openList(page);
  await panelRow(page, 'race').getByRole('button', { name: 'Review' }).click();
  const review = page.locator('jf-status-pill jf-status-panel [data-testid="status-review"]');
  await review.waitFor();
  await expect.poll(() => reads.length).toBeGreaterThanOrEqual(1);
  await page.waitForTimeout(600);
  answers.race = 'slow-same';
  const before = reads.length;
  await review.locator('[data-answer="allow"]').click();
  await expect.poll(() => reads.length, { message: 'the Allow reads the details' }).toBeGreaterThan(before);
  await page.waitForTimeout(100);
  await review.locator('[data-answer="deny"]').click();
  await expect.poll(() => denied).toEqual(['race']);
  await expect.poll(() => review.getByRole('alert').textContent()).toContain('could not be cancelled');
  // The Allow's slow read has come back by now.
  await page.waitForTimeout(1_900);
  expect(sent, 'an Allow abandoned by a later Deny never sends').toEqual([]);
  expect(await page.evaluate(() => window.answers)).toEqual({});
  expect(await review.getByRole('alert').textContent()).toContain('could not be cancelled');
  status.code = 200;
  await review.locator('[data-answer="deny"]').click();
  await expect.poll(() => page.evaluate(() => window.answers), { message: 'the last press decides' }).toEqual({ race: false });
  expect(denied).toEqual(['race', 'race']);
  expect(sent).toEqual([]);
}, 30_000);
