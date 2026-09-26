// @vitest-environment happy-dom
/**
 * Tempdoc 941 — the catalog boot is re-attempted when the shell sees the backend answering.
 *
 * Making each `boot*` re-attemptable is only half the fix: before this, NOTHING in production
 * ever called them a second time, so a boot that lost the race against a still-starting Head was
 * terminal for the session (raw `settings.*` keys, an empty surface rail, unresolvable operation
 * labels — until the user reloaded by hand). This pins the other half: the subscription exists,
 * it fires on the FALSE→TRUE readiness edge, and it does not fire while the state is unchanged.
 *
 * Every catalog module is mocked, so this asserts the WIRING and nothing else — each module's own
 * idempotence is covered in `i18n/errorCatalog.test.ts` and `i18n/resourceCatalog.test.ts`.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

type AiStateLike = { status: unknown; snapshotLive: boolean };

const hoisted = vi.hoisted(() => ({
  bootErrorCatalog: vi.fn(async () => {}),
  bootResourceCatalog: vi.fn(async () => {}),
  bootSurfaceCatalog: vi.fn(async () => {}),
  bootHealthEventsCatalog: vi.fn(async () => {}),
  bootOperationMessageCatalog: vi.fn(async () => {}),
  bootWorkflowCatalog: vi.fn(async () => {}),
  bootResourceRegistry: vi.fn(async () => {}),
  bootOperationRegistry: vi.fn(async () => {}),
  bootDiagnosticChannelRegistry: vi.fn(async () => {}),
  bootSurfaceRegistry: vi.fn(async () => {}),
  bootConversationShapeRegistry: vi.fn(async () => {}),
  bootstrapAggregateSubstrate: vi.fn(),
  resolveApiEndpoint: vi.fn(async () => ({ baseUrl: 'http://127.0.0.1:33221' })),
  listeners: [] as Array<(s: AiStateLike) => void>,
}));

vi.mock('./api/http', () => ({ resolveApiEndpoint: hoisted.resolveApiEndpoint }));
vi.mock('./i18n/errorCatalog', () => ({ bootErrorCatalog: hoisted.bootErrorCatalog }));
vi.mock('./i18n/resourceCatalog', () => ({
  bootResourceCatalog: hoisted.bootResourceCatalog,
  bootSurfaceCatalog: hoisted.bootSurfaceCatalog,
  bootHealthEventsCatalog: hoisted.bootHealthEventsCatalog,
  bootOperationMessageCatalog: hoisted.bootOperationMessageCatalog,
  bootWorkflowCatalog: hoisted.bootWorkflowCatalog,
}));
vi.mock('./api/registry/ResourceCatalogClient', () => ({
  bootResourceRegistry: hoisted.bootResourceRegistry,
}));
vi.mock('./api/registry/OperationCatalogClient', () => ({
  bootOperationRegistry: hoisted.bootOperationRegistry,
}));
vi.mock('./api/registry/DiagnosticChannelCatalogClient', () => ({
  bootDiagnosticChannelRegistry: hoisted.bootDiagnosticChannelRegistry,
}));
vi.mock('./api/registry/SurfaceCatalogClient', () => ({
  bootSurfaceRegistry: hoisted.bootSurfaceRegistry,
}));
vi.mock('./api/registry/ConversationShapeCatalogClient', () => ({
  bootConversationShapeRegistry: hoisted.bootConversationShapeRegistry,
}));
vi.mock('./shell-v0/aggregate-substrate/bootstrap', () => ({
  bootstrapAggregateSubstrate: hoisted.bootstrapAggregateSubstrate,
}));
vi.mock('./shell-v0/state/aiStateStore', () => ({
  subscribeAiState: (listener: (s: AiStateLike) => void) => {
    hoisted.listeners.push(listener);
    return () => {};
  },
}));

/** Every boot call the module makes, as one number: the whole list runs together. */
function bootCallCount(): number {
  return hoisted.bootErrorCatalog.mock.calls.length;
}

function emit(state: AiStateLike): void {
  for (const l of hoisted.listeners) l(state);
}

describe('i18n boot — backend-ready re-attempt (941)', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
    hoisted.listeners.length = 0;
  });

  it('does no discovery at module evaluation, then boots on explicit startup and the ready edge', async () => {
    const { startMessageCatalogs } = await import('./i18n');
    expect(hoisted.bootstrapAggregateSubstrate).toHaveBeenCalledOnce();
    expect(hoisted.resolveApiEndpoint).not.toHaveBeenCalled();
    expect(bootCallCount()).toBe(0);
    expect(hoisted.listeners).toHaveLength(0);
    startMessageCatalogs('http://127.0.0.1:33221');
    await vi.waitFor(() => expect(bootCallCount()).toBe(1));
    expect(hoisted.listeners.length).toBe(1);

    // Not ready yet — no snapshot at all, which is exactly the cold-start case.
    emit({ status: null, snapshotLive: false });
    expect(bootCallCount()).toBe(1);

    // A snapshot arrived and it is a live observation: the backend is answering.
    emit({ status: {}, snapshotLive: true });
    await vi.waitFor(() => expect(bootCallCount()).toBe(2));
    // The whole list re-runs, not just the error catalog.
    expect(hoisted.bootSurfaceRegistry.mock.calls.length).toBe(2);
    expect(hoisted.bootWorkflowCatalog.mock.calls.length).toBe(2);
  });

  it('fires on the edge only — a steady-state ready poll does no work', async () => {
    const { startMessageCatalogs } = await import('./i18n');
    startMessageCatalogs('http://127.0.0.1:33221');
    await vi.waitFor(() => expect(bootCallCount()).toBe(1));

    emit({ status: {}, snapshotLive: true });
    await vi.waitFor(() => expect(bootCallCount()).toBe(2));
    emit({ status: {}, snapshotLive: true });
    emit({ status: {}, snapshotLive: true });
    expect(bootCallCount()).toBe(2);

    // A round trip through unreachable earns another attempt — a restarted backend is a new
    // chance for a namespace that never resolved.
    emit({ status: {}, snapshotLive: false });
    emit({ status: {}, snapshotLive: true });
    await vi.waitFor(() => expect(bootCallCount()).toBe(3));
  });

  it('a snapshot that is no longer live is not "ready"', async () => {
    const { startMessageCatalogs } = await import('./i18n');
    startMessageCatalogs('http://127.0.0.1:33221');
    await vi.waitFor(() => expect(bootCallCount()).toBe(1));
    // Precision: `status !== null` alone must not pass — a retained-but-dead snapshot is the
    // stale-observation case `snapshotLive` exists to separate (807 A.3).
    emit({ status: {}, snapshotLive: false });
    expect(bootCallCount()).toBe(1);
  });
});
