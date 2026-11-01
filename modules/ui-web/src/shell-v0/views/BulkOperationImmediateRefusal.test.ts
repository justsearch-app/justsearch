// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createServer } from 'node:http';
import { once } from 'node:events';
import './HealthSurface.js';
import './BrainSurface.js';
import './LibrarySurface.js';
import type { HealthSurface } from './HealthSurface.js';
import type { BrainSurface } from './BrainSurface.js';
import type { LibrarySurface } from './LibrarySurface.js';
import type { JfOperation } from '../aggregate-substrate/components/JfOperation.js';
import type { OpButton } from '../components/OpButton.js';
import type { ActionButton } from '../components/ActionButton.js';
import type { JfButton } from '../components/Button.js';
import type { Operation } from '../../api/types/registry.js';
import { __resetForTest, __seedForTest } from '../../api/registry/OperationCatalogClient.js';
import { bootstrapAggregateSubstrate, __resetBootstrap } from '../aggregate-substrate/bootstrap.js';
import { __clearAggregateRegistry } from '../aggregate-substrate/aggregateRegistry.js';
import { setUiMode, __resetUiModeForTest } from '../state/uiModeState.js';
import { __feedForTest, __resetAiStateForTest, type StatusSnapshot } from '../state/aiStateStore.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { getOperationClient } from '../operations/OperationClient.js';

const key = '0194f72c-0000-7000-8000-000000000001';
const code = 'RECOVERY_AUTHORIZATION_REFUSED';
const message = 'The rebuild or migration could not validate its permissions or prepared model files; this request did not replace the existing index, so review access and model files before starting a new rebuild. (Code: RECOVERY_AUTHORIZATION_REFUSED)';
const laterCode = 'BULK_GENERATION_REFUSED';
const laterMessage = 'The rebuild or migration could not safely start or resume the new index; check index health and current settings before starting a new rebuild. (Code: BULK_GENERATION_REFUSED)';
const nativeFetch = globalThis.fetch.bind(globalThis);
let server: ReturnType<typeof createServer>;
let base: string;
let accepted = false;
let finished = false;
let gapDecision = false;
let reads = 0;
let invocations = 0;
let holdInvoke = false;
let pendingInvoke: (() => void) | null = null;
let invokeRefusal = { message, code };

function operation(id: string): Operation {
  return { id, presentation: { labelKey: id, descriptionKey: '', iconHint: null, category: null },
    intf: { errors: [], inputs: {}, result: {}, uiHints: {} },
    policy: { risk: 'HIGH', confirm: { kind: 'INLINE' }, audit: 'NONE', undoSupported: false },
    availability: {}, lineage: { affects: [], supersedes: [] },
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    executors: ['UI'], audience: 'USER', consumers: [] };
}
function feed(migrationState = 'IDLE') {
  __feedForTest({ status: { worker: { core: { indexHealthy: true, indexState: 'IDLE' },
    migration: { migrationState, buildingGenerationId: migrationState === 'IDLE' ? null : `g-${key}` } },
    embedding: { compatState: 'BLOCKED_LEGACY' }, schema: { compatState: 'COMPATIBLE' },
  } as unknown as StatusSnapshot, inference: { mode: 'offline', available: false } });
}
beforeEach(async () => {
  accepted = false; finished = false; gapDecision = false; reads = 0; invocations = 0;
  holdInvoke = false; pendingInvoke = null; invokeRefusal = { message, code };
  server = createServer((req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Headers', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
    if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }
    let body: unknown = {};
    let status = 503;
    if (req.url?.includes('/invoke')) {
      invocations++;
      const decision = req.url.includes('core.accept-gaps') ? ['Candidate gap witness is unavailable', 'GAP_ACCEPTANCE_UNAVAILABLE']
        : req.url.includes('core.cancel-reindex') ? ['Rebuild owner is temporarily unavailable', 'REINDEX_CANCEL_UNAVAILABLE']
        : [invokeRefusal.message, invokeRefusal.code];
      status = accepted ? 200 : 500;
      body = accepted ? { success: true, message: 'Accepted', structuredData: { operationKey: key } }
        : { success: false, message: decision[0], errorClass: 'HANDLER_FAILURE', errorCode: decision[1] };
    } else if (req.url === `/api/operation-history/${key}`) {
      reads++; status = 200;
      body = finished ? { state: 'failed', historySince: 0, message: laterMessage, reason: laterCode, result: { code: laterCode } }
        : { state: 'running', historySince: 0, ...(gapDecision ? { phase: 'awaiting_acceptance',
          result: { gapListHash: 'a'.repeat(64), gaps: [{ unitId: 'doc', reason: 'PARSER_FAILED' }] } } : {}) };
    }
    const respond = () => {
      res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body));
    };
    if (holdInvoke && req.url?.includes('/invoke')) pendingInvoke = respond;
    else respond();
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  // Only this test's server can receive network requests; shared status polls get a local response.
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => String(input).startsWith(base)
    ? nativeFetch(input, init) : Promise.resolve(new Response('{}', { status: 503 })));
  __resetForTest(); __clearAggregateRegistry(); __resetBootstrap(); bootstrapAggregateSubstrate();
  __resetUiModeForTest(); setUiMode('advanced');
  __seedForTest({ schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Operation',
    entries: [operation('core.rebuild-index'), operation('core.activate-installed-models')] });
  feed();
});
afterEach(async () => {
  pendingInvoke?.(); pendingInvoke = null;
  document.body.replaceChildren(); __resetAiStateForTest(); __resetUiModeForTest();
  __resetForTest(); __clearAggregateRegistry(); __resetBootstrap(); vi.restoreAllMocks(); vi.unstubAllGlobals();
  server.closeAllConnections(); await new Promise<void>(resolve => server.close(() => resolve()));
});
function host() {
  return createMockHostApi({ data: {
    fetch: path => path.startsWith('/api/operation-history/') ? fetch(base + path) : Promise.resolve(new Response('{}', { status: 503 })),
    invokeOperation: (id, args, opts) => getOperationClient(base).invokeWithConsent(id, { args: args ?? {} }, opts ?? {}),
  }, ui: { showConfirmDialog: async () => true } });
}
async function pump(el: HealthSurface | BrainSurface | LibrarySurface) {
  for (let i = 0; i < 30; i++) { await Promise.resolve(); await el.updateComplete; }
}
async function clickButton(button: JfButton) {
  await button.updateComplete;
  const control = button.shadowRoot!.querySelector('jf-control') as HTMLElement & { updateComplete: Promise<unknown> };
  await control.updateComplete;
  control.shadowRoot!.querySelector<HTMLButtonElement>('button')!.click();
}
async function clickRebuild(el: HealthSurface | BrainSurface, recommended: boolean) {
  const aggregate = el.shadowRoot!.querySelector(`${recommended ? '.recommended ' : ''}jf-operation[operation-id="core.rebuild-index"]`) as JfOperation;
  expect(aggregate).not.toBeNull(); await aggregate.updateComplete;
  const op = aggregate.querySelector('jf-op-button') as OpButton;
  expect(op).not.toBeNull(); await op.updateComplete;
  const action = op.shadowRoot!.querySelector('jf-action-button') as ActionButton;
  await action.updateComplete;
  action.shadowRoot!.querySelector<HTMLButtonElement>('button.invoke')!.click();
  await action.updateComplete;
  action.shadowRoot!.querySelector<HTMLButtonElement>('.confirm-row button')!.click();
}

const bulkControls = ['health', 'brain', 'health-recommended', 'activation'] as const;
async function startPendingControl(surface: typeof bulkControls[number]) {
  holdInvoke = true; invokeRefusal = { message: laterMessage, code: laterCode };
  const brain = surface === 'brain' || surface === 'activation';
  const el = document.createElement(brain ? 'jf-brain-surface' : 'jf-health-surface') as BrainSurface | HealthSurface;
  el.apiBase = base; el.host_ = host();
  if (brain) vi.spyOn(el as unknown as { refreshAll(): Promise<void> }, 'refreshAll').mockResolvedValue();
  document.body.append(el); await pump(el);
  if (surface === 'health-recommended') {
    (el as HealthSurface).recommendedActions = new Map([['index.embedding_legacy|index', { target: 'core.rebuild-index', args: {} }]]);
  }
  if (surface === 'activation') {
    const b = el as BrainSurface;
    b.expanded = { ...b.expanded, install: true };
    b.installStatus = { state: 'idle', phase: 'activation_required' } as NonNullable<BrainSurface['installStatus']>;
  }
  await pump(el);
  const invoke = vi.spyOn(getOperationClient(base), 'invokeWithConsent');
  if (surface === 'activation') await clickButton(el.shadowRoot!.querySelector('jf-button[label="Activate downloaded models"]') as JfButton);
  else await clickRebuild(el, surface === 'health-recommended');
  await vi.waitFor(() => expect(pendingInvoke).not.toBeNull());
  return { el, response: invoke.mock.results[0]!.value as Promise<unknown> };
}
function releaseInvoke() {
  expect(pendingInvoke).not.toBeNull();
  const respond = pendingInvoke!; pendingInvoke = null; respond();
}

it.each(bulkControls.flatMap(surface => (['hidden', 'returned'] as const).map(timing => ({ surface, timing }))))(
  '$surface handles an invoke refusal arriving while $timing', async ({ surface, timing }) => {
    const { el, response } = await startPendingControl(surface);
    el.remove();
    if (timing === 'returned') { document.body.append(el); await pump(el); }
    releaseInvoke();
    await expect(response).rejects.toMatchObject({ errorCode: laterCode });
    for (let i = 0; i < 30; i++) await Promise.resolve();
    if (timing === 'hidden') {
      expect.soft(el.bulkError, 'a hidden surface must not acquire a transient refusal').toBeNull();
      document.body.append(el); await pump(el);
      expect(el.bulkError).toBeNull();
      expect(el.shadowRoot!.textContent).not.toContain(laterMessage);
      expect(el.shadowRoot!.textContent).not.toContain(laterCode);
    } else {
      await pump(el);
      expect(el.bulkError?.message).toBe(laterMessage);
      expect(el.shadowRoot!.textContent).toContain(laterMessage);
      expect(el.shadowRoot!.textContent?.split(`Code: ${laterCode}`)).toHaveLength(2);
    }
  },
);

it.each(bulkControls)('%s retains acceptance received while hidden and follows failure after return', async surface => {
  accepted = true;
  const { el, response } = await startPendingControl(surface);
  el.remove(); releaseInvoke();
  await expect(response).resolves.toMatchObject({ structuredData: { operationKey: key } });
  for (let i = 0; i < 30; i++) await Promise.resolve();
  expect(el.bulkOutcomeKey).toBe(key);
  document.body.append(el); await pump(el);
  await vi.waitFor(() => expect(reads).toBeGreaterThan(0));
  expect(el.bulkOutcomeKey).toBe(key);
  expect(el.bulkError).toBeNull();
  finished = true;
  await vi.waitFor(() => {
    const detail = el.shadowRoot!.querySelector('jf-operation-outcome-detail');
    expect(detail?.shadowRoot?.textContent).toContain(laterMessage);
    expect(detail?.shadowRoot?.textContent?.split(`Code: ${laterCode}`)).toHaveLength(2);
  }, { timeout: 6_000 });
}, 10_000);

it.each(['health', 'brain', 'health-recommended', 'activation'] as const)(
  '%s shows an immediate refusal and a later failure after retry through its real control', async surface => {
    const brain = surface === 'brain' || surface === 'activation';
    const el = document.createElement(brain ? 'jf-brain-surface' : 'jf-health-surface') as BrainSurface | HealthSurface;
    el.apiBase = base; el.host_ = host();
    if (brain) vi.spyOn(el as unknown as { refreshAll(): Promise<void> }, 'refreshAll').mockResolvedValue();
    document.body.append(el); await pump(el);
    if (surface === 'health-recommended') {
      (el as HealthSurface).recommendedActions = new Map([['index.embedding_legacy|index', { target: 'core.rebuild-index', args: {} }]]);
    }
    if (surface === 'activation') {
      const b = el as BrainSurface;
      b.expanded = { ...b.expanded, install: true };
      b.installStatus = { state: 'idle', phase: 'activation_required' } as NonNullable<BrainSurface['installStatus']>;
    }
    await pump(el);
    const click = async () => {
      if (surface === 'activation') {
        const button = el.shadowRoot!.querySelector('jf-button[label="Activate downloaded models"]') as JfButton;
        expect(button).not.toBeNull(); await clickButton(button);
      } else await clickRebuild(el, surface === 'health-recommended');
    };
    const errorDelivered = surface === 'activation' ? null : new Promise<void>(resolve => {
      el.addEventListener('op-error', () => resolve(), { once: true });
    });
    await click();
    await vi.waitFor(() => expect(invocations).toBe(1));
    if (errorDelivered) await errorDelivered;
    else await vi.waitFor(() => expect((el as BrainSurface).busy['install-activation']).toBe(false));
    await pump(el);
    // A refusal's sentence and its code must both be visible; a typed event alone is insufficient.
    expect.soft([...el.shadowRoot!.querySelectorAll('jf-error-alert')].map(e => e.textContent).join(' ')).toContain(message);
    expect.soft([...el.shadowRoot!.querySelectorAll('jf-error-alert')].map(e => e.textContent).join(' ')).toContain(`Code: ${code}`);
    expect([...el.shadowRoot!.querySelectorAll('jf-error-alert')].map(e => e.textContent).join(' ').split(`Code: ${code}`)).toHaveLength(2);
    accepted = true;
    await click();
    await vi.waitFor(() => expect(reads).toBeGreaterThan(0)); await pump(el);
    expect([...el.shadowRoot!.querySelectorAll('jf-error-alert')].map(e => e.textContent).join(' ')).not.toContain(message);
    finished = true;
    await vi.waitFor(() => {
      const detail = el.shadowRoot!.querySelector('jf-operation-outcome-detail');
      expect(detail?.shadowRoot?.textContent).toContain(laterMessage);
      expect(detail?.shadowRoot?.textContent?.split(`Code: ${laterCode}`)).toHaveLength(2);
    }, { timeout: 6_000 });
  }, 10_000,
);

it.each(['health', 'brain'] as const)('%s clears a refused rebuild when navigating away and back', async surface => {
  const el = document.createElement(surface === 'brain' ? 'jf-brain-surface' : 'jf-health-surface') as BrainSurface | HealthSurface;
  el.apiBase = base; el.host_ = host();
  if (surface === 'brain') vi.spyOn(el as unknown as { refreshAll(): Promise<void> }, 'refreshAll').mockResolvedValue();
  document.body.append(el); await pump(el);
  await clickRebuild(el, false);
  await vi.waitFor(() => expect(el.bulkError?.message).toBe(message));
  await pump(el);
  expect(el.shadowRoot!.textContent).toContain(message);

  // Navigation retains the instance, but an old refusal must not return with it.
  el.remove();
  document.body.append(el); await pump(el);
  expect.soft(el.bulkError).toBeNull();
  expect(el.shadowRoot!.textContent).not.toContain(message);
  expect(el.shadowRoot!.textContent).not.toContain(code);
});

it.each(['cancel', 'accept-gaps'] as const)('Library %s keeps an immediate decision refusal code', async action => {
  gapDecision = action === 'accept-gaps'; feed(gapDecision ? 'AWAITING_ACCEPTANCE' : 'MIGRATING');
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.apiBase = base; el.host_ = host(); document.body.append(el);
  await vi.waitFor(() => expect(el.shadowRoot!.querySelector(`jf-button[label="${gapDecision ? 'Use the new index without them' : 'Cancel rebuild'}"]`)).not.toBeNull());
  const button = el.shadowRoot!.querySelector(`jf-button[label="${gapDecision ? 'Use the new index without them' : 'Cancel rebuild'}"]`) as JfButton;
  await clickButton(button);
  await vi.waitFor(() => expect(invocations).toBe(1));
  await vi.waitFor(() => expect(gapDecision ? el.gapDecisionBusy : el.reindexCancelBusy).toBe(false));
  await pump(el);
  const expectedMessage = gapDecision ? 'Candidate gap witness is unavailable' : 'Rebuild owner is temporarily unavailable';
  const expectedCode = gapDecision ? 'GAP_ACCEPTANCE_UNAVAILABLE' : 'REINDEX_CANCEL_UNAVAILABLE';
  expect(el.shadowRoot!.textContent).toContain(expectedMessage);
  expect([...el.shadowRoot!.querySelectorAll('[role="alert"] small')].map(e => e.textContent).join(' ')).toContain(`Code: ${expectedCode}`);
});
