// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './HealthSurface.js';
import type { HealthSurface } from './HealthSurface.js';
import type { JfOperation } from '../aggregate-substrate/components/JfOperation.js';
import type { OpButton } from '../components/OpButton.js';
import type { ActionButton } from '../components/ActionButton.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import { __resetForTest, __seedForTest } from '../../api/registry/OperationCatalogClient.js';
import { bootstrapAggregateSubstrate, __resetBootstrap } from '../aggregate-substrate/bootstrap.js';
import { __clearAggregateRegistry } from '../aggregate-substrate/aggregateRegistry.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
import { mergePluginRecoveryOverlays, removePluginRecoveryOverlays } from '../../api/registry/RecoveryOverlayClient.js';
import type { Operation } from '../../api/types/registry.js';

function operation(id: string, high = false): Operation {
  return {
    id,
    presentation: { labelKey: `ops.${id}.label`, descriptionKey: '', iconHint: null, category: null },
    intf: { errors: [], inputs: {}, result: {}, uiHints: {} },
    policy: { risk: high ? 'HIGH' : 'LOW', confirm: { kind: high ? 'INLINE' : 'NONE' }, audit: 'NONE', undoSupported: false },
    availability: {}, lineage: { affects: [], supersedes: [] },
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    executors: ['UI'], audience: 'USER', consumers: [],
  };
}

function index(target: string, defaultArgsJson: unknown = '{}') {
  return { entries: [{ target, conditions: [{ conditionId: 'schema.reindex-required', subject: 'worker.schema', defaultArgsJson }] }] };
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

let stream: ReadableStreamDefaultController<Uint8Array> | undefined;
let mounted: HealthSurface | undefined;
let fetchOp: ReturnType<typeof vi.fn<typeof fetch>>;

async function mount(initial: unknown): Promise<HealthSurface> {
  const el = document.createElement('jf-health-surface') as HealthSurface;
  el.apiBase = 'http://localhost';
  el.host_ = createMockHostApi({ data: { fetch: async (path) => {
    if (path === '/api/condition-recovery-index') return json(await initial);
    if (path === '/api/condition-recovery-index/stream') {
      return new Response(new ReadableStream<Uint8Array>({ start(controller) { stream = controller; } }),
        { headers: { 'Content-Type': 'text/event-stream' } });
    }
    return json({ jobs: [] }, path.includes('/stream') ? 404 : 200);
  } } });
  document.body.appendChild(el);
  mounted = el;
  await vi.waitFor(() => expect(el.loading).toBe(false));
  await el.updateComplete;
  return el;
}

async function action(el: HealthSurface): Promise<ActionButton> {
  await el.updateComplete;
  const aggregate = el.shadowRoot!.querySelector('.recommended jf-operation') as JfOperation;
  expect(aggregate).not.toBeNull();
  await aggregate.updateComplete;
  const op = aggregate.querySelector('jf-op-button') as OpButton;
  expect(op).not.toBeNull();
  await op.updateComplete;
  const button = op.shadowRoot!.querySelector('jf-action-button') as ActionButton;
  await button.updateComplete;
  return button;
}

function invokeCalls() { return fetchOp.mock.calls.filter(c => String(c[0]).includes('/invoke')); }

beforeEach(() => {
  __resetForTest(); __clearAggregateRegistry(); __resetBootstrap(); __resetAiStateForTest();
  bootstrapAggregateSubstrate();
  __seedForTest({ schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Operation',
    entries: [operation('core.reindex'), operation('core.rebuild-index', true), operation('vendor.recovery.fix')] });
  fetchOp = vi.fn<typeof fetch>().mockResolvedValue(json({ success: true, message: 'ok' }));
  vi.stubGlobal('fetch', fetchOp);
});

afterEach(() => {
  mounted?.remove(); mounted = undefined; stream?.close(); stream = undefined;
  removePluginRecoveryOverlays('recovery');
  document.body.innerHTML = '';
  __resetForTest(); __clearAggregateRegistry(); __resetBootstrap(); __resetAiStateForTest();
  vi.unstubAllGlobals();
});

describe('health recovery invocation', () => {
  it('REST projection dispatches the exact force args through the catalog control', async () => {
    const el = await mount(index('core.reindex', '{"force":true}'));
    const button = await action(el);
    button.shadowRoot!.querySelector<HTMLButtonElement>('button.invoke')!.click();
    await vi.waitFor(() => expect(invokeCalls()).toHaveLength(1));
    expect(JSON.parse(String(invokeCalls()[0]![1]!.body)).args).toEqual({ force: true });
  });

  it('HIGH rebuild waits for inline confirmation and carries consent through the server gate', async () => {
    fetchOp.mockImplementation(async (input, init) => {
      if (String(input).includes('/authorizations/approve')) return json({ capsule: 'approved-capsule' });
      if (!String(input).includes('/invoke')) return json({});
      const body = JSON.parse(String(init?.body));
      return body.confirmationToken ? json({ success: true, message: 'ok' })
        : json({ success: false, errorClass: 'CONFIRMATION_REQUIRED', pendingId: 'recovery-pending' }, 428);
    });
    const el = await mount(index('core.rebuild-index'));
    const button = await action(el);
    expect(button.confirmKind).toBe('INLINE');
    button.shadowRoot!.querySelector<HTMLButtonElement>('button.invoke')!.click();
    await button.updateComplete;
    expect(invokeCalls()).toHaveLength(0);
    expect(fetchOp.mock.calls.filter(c => String(c[0]).includes('/authorizations/approve'))).toHaveLength(0);
    button.shadowRoot!.querySelector<HTMLButtonElement>('.confirm-row button')!.click();
    await vi.waitFor(() => expect(invokeCalls()).toHaveLength(2));
    const approval = fetchOp.mock.calls.find(c => String(c[0]).includes('/authorizations/approve'));
    expect(JSON.parse(String(approval?.[1]?.body)).pendingId).toBe('recovery-pending');
    expect(JSON.parse(String(invokeCalls()[1]![1]!.body))).toMatchObject({ args: {}, confirmationToken: 'approved-capsule' });
  });

  it('shows a failed recovery and clears its error after a successful retry', async () => {
    fetchOp.mockImplementation(async input => String(input).includes('/invoke')
      ? json({ success: false, errorClass: 'HANDLER_FAILURE', message: 'Recovery was refused.' }, 500)
      : json({}));
    const el = await mount(index('core.reindex', '{"force":true}'));
    const button = await action(el);
    button.shadowRoot!.querySelector<HTMLButtonElement>('button.invoke')!.click();
    await vi.waitFor(() => expect(el.shadowRoot!.querySelector('jf-error-alert')?.textContent).toContain('Recovery was refused.'));
    fetchOp.mockResolvedValue(json({ success: true, message: 'Recovered' }));
    const retry = await action(el);
    retry.shadowRoot!.querySelector<HTMLButtonElement>('button.invoke')!.click();
    await vi.waitFor(() => expect(invokeCalls()).toHaveLength(2));
    await vi.waitFor(() => expect(el.shadowRoot!.querySelector('jf-error-alert')).toBeNull());
  });

  it('SSE lifecycle and update replace the complete invocation including arguments', async () => {
    const el = await mount(index('core.rebuild-index'));
    stream!.enqueue(new TextEncoder().encode(`data: ${JSON.stringify({ frameKind: 'LIFECYCLE', payload: { kind: 'snapshot', index: index('core.reindex', '{"force":true}') } })}\n\n`));
    await vi.waitFor(() => expect(el.recommendedActions.get('schema.reindex-required|worker.schema')?.args).toEqual({ force: true }));
    stream!.enqueue(new TextEncoder().encode(`data: ${JSON.stringify({ frameKind: 'UPDATE', payload: index('core.reindex', '{"force":false}') })}\n\n`));
    await vi.waitFor(() => expect(el.recommendedActions.get('schema.reindex-required|worker.schema')?.args).toEqual({ force: false }));
    const button = await action(el);
    button.shadowRoot!.querySelector<HTMLButtonElement>('button.invoke')!.click();
    await vi.waitFor(() => expect(invokeCalls()).toHaveLength(1));
    expect(JSON.parse(String(invokeCalls()[0]![1]!.body)).args).toEqual({ force: false });
  });

  it('a late initial REST snapshot cannot replace a newer streamed remedy, and reconnect accepts REST again', async () => {
    let resolveRest!: (value: unknown) => void;
    const delayed = new Promise<unknown>(resolve => { resolveRest = resolve; });
    const el = await mount(delayed);
    const current = { ...index('core.rebuild-index'), catalogVersion: 2 };
    stream!.enqueue(new TextEncoder().encode(`data: ${JSON.stringify({ frameKind: 'UPDATE', payload: current })}\n\n`));
    await vi.waitFor(() => expect(el.recommendedActions.get('schema.reindex-required|worker.schema')?.target).toBe('core.rebuild-index'));
    resolveRest({ ...index('core.reindex', '{"force":true}'), catalogVersion: 1 });
    // Drain the resolved response and its JSON promise before checking that it was rejected.
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(el.recommendedActions.get('schema.reindex-required|worker.schema')).toEqual({ target: 'core.rebuild-index', args: {} });
    el.remove(); stream!.close(); stream = undefined;
    document.body.appendChild(el);
    await vi.waitFor(() => expect(el.recommendedActions.get('schema.reindex-required|worker.schema')).toEqual({ target: 'core.reindex', args: { force: true } }));
  });

  it('ignores a disconnected request even when its transport ignores abort', async () => {
    let resolveRest!: (value: unknown) => void;
    const el = await mount(new Promise<unknown>(resolve => { resolveRest = resolve; }));
    el.remove(); stream!.close(); stream = undefined;
    el.host_ = createMockHostApi({ data: { fetch: async path =>
      json(path === '/api/condition-recovery-index' ? index('core.rebuild-index') : { jobs: [] },
        path.includes('/stream') ? 404 : 200),
    } });
    document.body.appendChild(el);
    await vi.waitFor(() => expect(el.recommendedActions.get('schema.reindex-required|worker.schema')?.target).toBe('core.rebuild-index'));
    resolveRest(index('core.reindex', '{"force":true}'));
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(el.recommendedActions.get('schema.reindex-required|worker.schema')).toEqual({ target: 'core.rebuild-index', args: {} });
  });

  it.each(['{bad}', '[]', 'null', '42', null])('omits invalid invocation args %s instead of inventing defaults', async bad => {
    const el = await mount(index('core.reindex', bad));
    expect(el.recommendedActions.size).toBe(0);
    expect(el.shadowRoot!.querySelector('.recommended jf-operation')).toBeNull();
    expect(invokeCalls()).toHaveLength(0);
    expect(fetchOp.mock.calls.filter(c => String(c[0]).includes('/authorizations/approve'))).toHaveLength(0);
  });

  it('a trusted plugin target replaces the whole invocation without inheriting core args', async () => {
    mergePluginRecoveryOverlays([{ pluginId: 'recovery', conditionId: 'schema.reindex-required', subject: 'worker.schema',
      operationRef: 'vendor.recovery.fix', provenance: { tier: 'TRUSTED_PLUGIN', contributorId: 'recovery', version: '1.0' } }]);
    const el = await mount(index('core.reindex', '{"force":true}'));
    const button = await action(el);
    button.shadowRoot!.querySelector<HTMLButtonElement>('button.invoke')!.click();
    await vi.waitFor(() => expect(invokeCalls()).toHaveLength(1));
    expect(String(invokeCalls()[0]![0])).toContain('vendor.recovery.fix');
    expect(JSON.parse(String(invokeCalls()[0]![1]!.body)).args).toEqual({});
  });
});
