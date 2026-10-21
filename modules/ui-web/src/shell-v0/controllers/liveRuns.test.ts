// @vitest-environment happy-dom

/**
 * Tempdoc 834 §5.1/§15.3 — the live-run discovery service that replaced the localStorage pointer.
 *
 * The properties asserted as mechanisms:
 *  - **The token rides a GET.** `authorizedFetch` exempts GET, and this route does not, so the
 *    header is asserted on the request rather than assumed from the seam.
 *  - **Every failure degrades to "no live runs".** Non-2xx, a thrown fetch, and a body the generated
 *    Zod validator rejects are each driven separately; discovery runs on the mount path.
 *  - **The conversation guard is asymmetric**, which is the behaviour the retired pointer had and
 *    the one a symmetric server-side filter would have quietly broken.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const mocks = vi.hoisted(() => ({
  isTauriRuntime: vi.fn(() => true),
  invoke: vi.fn(),
}));

vi.mock('../../utils/tauriRuntime', () => ({ isTauriRuntime: mocks.isTauriRuntime }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));

const SESSION_HEADER = 'X-JustSearch-Session';
const AGENT_SHAPE_ID = 'core.agent-run';

const run = (runId: string, over: Record<string, unknown> = {}): Record<string, unknown> => ({
  runId,
  shapeId: AGENT_SHAPE_ID,
  conversationId: null,
  state: 'running',
  park: null,
  startedAtEpochMs: 1,
  updatedAtEpochMs: 1,
  observerCount: 0,
  snapshot: null,
  ...over,
});

let originalFetch: typeof fetch;
let fetchSpy: ReturnType<typeof vi.fn>;

/** The module under test, freshly imported so `http.ts`'s cached token starts clean per case. */
async function liveRuns(): Promise<typeof import('./liveRuns.js')> {
  return import('./liveRuns.js');
}

function answers(runs: unknown): void {
  fetchSpy.mockResolvedValue(
    new Response(JSON.stringify({ runs }), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    }),
  );
}

beforeEach(() => {
  originalFetch = globalThis.fetch;
  fetchSpy = vi.fn();
  globalThis.fetch = fetchSpy as unknown as typeof fetch;
  mocks.isTauriRuntime.mockReturnValue(true);
  mocks.invoke.mockReset();
  mocks.invoke.mockResolvedValue({ origin: 'http://127.0.0.1:43210', token: 'tok-1' });
  vi.resetModules(); // http.ts caches the resolved token in module state.
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  vi.clearAllMocks();
});

describe('fetchLiveRuns (834 §5.1)', () => {
  it('sends the session token on this GET, and narrows by shape at the server', async () => {
    answers([run('r1')]);
    const { fetchLiveRuns, AGENT_SHAPE_ID: shape } = await liveRuns();
    await fetchLiveRuns('http://127.0.0.1:43210', shape);
    const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('http://127.0.0.1:43210/api/chat/runs/live?shapeId=core.agent-run');
    expect((init.headers as Record<string, string>)[SESSION_HEADER]).toBe('tok-1');
  });

  it('never sends a conversationId filter — the caller guard is asymmetric, an equality filter is not', async () => {
    answers([run('r1')]);
    const { discoverLiveAgentRun } = await liveRuns();
    await discoverLiveAgentRun('http://127.0.0.1:43210', 'conv-1');
    expect(fetchSpy.mock.calls[0]![0] as string).not.toContain('conversationId');
  });

  it('degrades to no live runs on a non-2xx', async () => {
    fetchSpy.mockResolvedValue(new Response('nope', { status: 401 }));
    const { fetchLiveRuns } = await liveRuns();
    expect(await fetchLiveRuns('http://127.0.0.1:43210')).toEqual([]);
  });

  it('degrades to no live runs when the fetch throws', async () => {
    fetchSpy.mockRejectedValue(new Error('offline'));
    const { fetchLiveRuns } = await liveRuns();
    expect(await fetchLiveRuns('http://127.0.0.1:43210')).toEqual([]);
  });

  it('degrades to no live runs when the body fails the generated validator', async () => {
    // A shape the wire schema does not describe — accepting it would put unvalidated fields on a
    // path that then addresses a run stream by id.
    answers([{ runId: 'r1', unexpectedField: true }]);
    const { fetchLiveRuns } = await liveRuns();
    expect(await fetchLiveRuns('http://127.0.0.1:43210')).toEqual([]);
  });
});

describe('discoverLiveAgentRun (834 §15.3)', () => {
  it('takes the first row — the enumeration is already newest-first, so no re-sort is needed', async () => {
    answers([run('newest', { startedAtEpochMs: 9 }), run('older', { startedAtEpochMs: 2 })]);
    const { discoverLiveAgentRun } = await liveRuns();
    expect(await discoverLiveAgentRun('http://127.0.0.1:43210')).toBe('newest');
  });

  it('refuses a run of another shape even if the server returned one', async () => {
    answers([run('wf', { shapeId: 'core.workflow-run' })]);
    const { discoverLiveAgentRun } = await liveRuns();
    expect(await discoverLiveAgentRun('http://127.0.0.1:43210')).toBeNull();
  });

  it('a caller with NO conversation adopts the newest agent run — the cross-tab case', async () => {
    answers([run('r1', { conversationId: 'conv-somebody-else' })]);
    const { discoverLiveAgentRun } = await liveRuns();
    expect(await discoverLiveAgentRun('http://127.0.0.1:43210', null)).toBe('r1');
  });

  it('a caller pinned to a conversation refuses a run belonging to a DIFFERENT one', async () => {
    answers([run('r1', { conversationId: 'conv-A' })]);
    const { discoverLiveAgentRun } = await liveRuns();
    expect(await discoverLiveAgentRun('http://127.0.0.1:43210', 'conv-B')).toBeNull();
  });

  it('a pinned caller still adopts a run that carries no conversation of its own', async () => {
    answers([run('r1', { conversationId: null })]);
    const { discoverLiveAgentRun } = await liveRuns();
    expect(await discoverLiveAgentRun('http://127.0.0.1:43210', 'conv-B')).toBe('r1');
  });

  it('skips a mismatched row and takes the next adoptable one', async () => {
    answers([run('not-mine', { conversationId: 'conv-A' }), run('mine', { conversationId: 'conv-B' })]);
    const { discoverLiveAgentRun } = await liveRuns();
    expect(await discoverLiveAgentRun('http://127.0.0.1:43210', 'conv-B')).toBe('mine');
  });

  it('answers null on an empty enumeration', async () => {
    answers([]);
    const { discoverLiveAgentRun } = await liveRuns();
    expect(await discoverLiveAgentRun('http://127.0.0.1:43210')).toBeNull();
  });
});
