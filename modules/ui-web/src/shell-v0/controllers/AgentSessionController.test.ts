/**
 * @vitest-environment happy-dom
 *
 * Slice 495 — AgentSessionController tests.
 *
 * Migrated from AgentSurface.test.ts G1 harness + slice 495 Phase 1 extended
 * harness. Tests the controller directly — no DOM mounting needed.
 *
 * The controller implements CoreAgentRunHandlers; the handler methods
 * (onSessionStarted, onChunk, etc.) are called directly in these tests,
 * matching how dispatchShapeEventToHandlers routes SSE events to them.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  AgentSessionController,
  interruptedRunNotice,
  interruptedRunPresentation,
  sessionLabel,
  // Tempdoc 859 §A §1.2 — the REGISTER, imported rather than retyped. See C-6.
  REASONING_BOUNDARY_EXEMPT,
} from './AgentSessionController.js';
// §32 unify — auto-approval is now driven by the autonomy dial level.
import {
  setAutonomyLevel,
  __resetAutonomyForTest,
} from '../substrates/autonomy/index.js';
// Tempdoc 550 C3 — non-auto-approved tool calls route through the unified ceremony host
// via the broker; tests register a presenter to control the human decision deterministically.
import {
  setAuthorizationPresenter,
  setAuthorizationCanceller,
} from '../operations/authorizationBroker.js';
// Tempdoc 605 — the run-conclusion notice routes through the 559 single system-message channel;
// mock it to assert it fires exactly once on a denial and never when nothing was drained.
vi.mock('../components/advisory/ephemeralToast.js', () => ({ emitEphemeralToast: vi.fn() }));
import { emitEphemeralToast } from '../components/advisory/ephemeralToast.js';
// 543-fwd idea #0 — the controller now bridges successful tool-calls into the
// (module-global) Effect Journal; reset it per-test for determinism.
import {
  listJournalByOriginator,
  getUndoableOperation,
  __resetJournalForTest,
} from '../substrates/effects/index.js';

// ---------- SSE mock helpers ----------

function sseChunk(event: string, data: object): string {
  return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
}

function mockFetchSse(body: string): typeof fetch {
  return vi.fn(() => {
    const encoder = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(body));
        controller.close();
      },
    });
    return Promise.resolve(
      new Response(stream, {
        status: 200,
        headers: { 'content-type': 'text/event-stream' },
      }),
    );
  }) as unknown as typeof fetch;
}

function mockFetchJson(data: unknown, status = 200): typeof fetch {
  return vi.fn(() =>
    Promise.resolve(new Response(JSON.stringify(data), { status, headers: { 'content-type': 'application/json' } })),
  ) as unknown as typeof fetch;
}

function mockFetchError(status: number, body = ''): typeof fetch {
  return vi.fn(() =>
    Promise.resolve(new Response(body, { status })),
  ) as unknown as typeof fetch;
}

/** Existing short public-display gates, served through the new private read in approval tests. */
function mockFetchLiveApprovals(controller: AgentSessionController, overrides: Record<string, unknown> = {}): typeof fetch {
  return vi.fn(async (url: string) => {
    if (String(url).includes('/api/chat/approval?')) {
      const callId = new URL(String(url)).searchParams.get('callId')!;
      const call = controller.toolCalls[callId];
      return new Response(JSON.stringify({
        callId, operationId: call?.toolName, argsSummary: call?.arguments ?? '',
        riskTier: call?.risk?.toUpperCase(), gateBehavior: call?.gateBehavior?.toUpperCase() ?? null,
        ...overrides,
      }), { status: 200, headers: { 'content-type': 'application/json' } });
    }
    return new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } });
  }) as unknown as typeof fetch;
}

/** One row of `GET /api/chat/runs/live`, defaulted to a live agent run with no conversation. */
function liveRun(runId: string, conversationId: string | null = null, shapeId = 'core.agent-run'): object {
  return {
    runId,
    shapeId,
    conversationId,
    state: 'running',
    park: null,
    startedAtEpochMs: 1,
    updatedAtEpochMs: 1,
    observerCount: 0,
    snapshot: null,
  };
}

/**
 * The discovery + attach pair a cold reattach makes: `GET /api/chat/runs/live` answers `runs`, and
 * the follow-on `POST /api/chat/runs/{runId}/observe` answers `body` as an SSE stream. Any other URL
 * is a 404 so a stray call is visible rather than silently plausible.
 */
function mockFetchDiscoverThenObserve(runs: object[], body: string): typeof fetch {
  const encoder = new TextEncoder();
  return vi.fn((url: string) => {
    if (String(url).includes('/api/chat/runs/live')) {
      return Promise.resolve(
        new Response(JSON.stringify({ runs }), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
      );
    }
    const stream = new ReadableStream<Uint8Array>({
      start(c) {
        c.enqueue(encoder.encode(body));
        c.close();
      },
    });
    return Promise.resolve(
      new Response(stream, { status: 200, headers: { 'content-type': 'text/event-stream' } }),
    );
  }) as unknown as typeof fetch;
}

/** The managed run stream's answer for an unknown/retired run — the typed 404 (tempdoc 834 §1.6). */
function runNotFoundBody(runId: string, reason: 'unknown' | 'retired' = 'retired'): string {
  return JSON.stringify({ runId, reason, recordHint: `/api/chat/sessions/${runId}/events` });
}

let notifyCount: number;
let ctrl: AgentSessionController;

beforeEach(() => {
  notifyCount = 0;
  __resetAutonomyForTest(); // autonomy level is module-global; reset for determinism
  __resetJournalForTest(); // journal is module-global; the bridge writes to it
  ctrl = new AgentSessionController('http://test', () => { notifyCount++; });
  if (typeof globalThis.fetch !== 'function') {
    globalThis.fetch = (() =>
      Promise.reject(new Error('fetch unmocked'))) as unknown as typeof fetch;
  }
});

afterEach(() => {
  ctrl.destroy();
  vi.restoreAllMocks();
  __resetAutonomyForTest();
  setAuthorizationPresenter(null);
});

// ==================== G1 migration: SSE event handling ====================

describe('AgentSessionController SSE handlers (G1 migration)', () => {
  // ===== 1. session_started =====
  it('onSessionStarted sets sessionId', () => {
    ctrl.onSessionStarted({ sessionId: 'sess-123' });
    expect(ctrl.sessionId).toBe('sess-123');
  });

  // ===== 1b. run_started (tempdoc 834 §1.6) — run identity, with session_started dual-read =====
  it('onRunStarted is the identity frame: runId IS the sessionId', () => {
    ctrl.onRunStarted({ runId: 'run-42', shapeId: 'core.agent-run', conversationId: 'conv-9' });
    expect(ctrl.sessionId).toBe('run-42');
    expect(ctrl.conversationId).toBe('conv-9');
  });

  it('onRunStarted does NOT move a controller already pinned to a conversation', () => {
    ctrl.conversationId = 'conv-mine';
    ctrl.onRunStarted({ runId: 'run-42', shapeId: 'core.agent-run', conversationId: 'conv-other' });
    expect(ctrl.sessionId).toBe('run-42');
    expect(ctrl.conversationId).toBe('conv-mine');
  });

  it('session_started still sets identity — dual-read, never deleted (the persisted ledger reads it)', () => {
    ctrl.onSessionStarted({ sessionId: 'legacy-sess' });
    expect(ctrl.sessionId).toBe('legacy-sess');
  });

  // ===== 1c. state_snapshot (tempdoc 834 §6.1) — every fact needed to ACT on the run =====
  it('onStateSnapshot reads the park and the run autonomy level, not just the active agent', () => {
    ctrl.onStateSnapshot({
      iteration: 3,
      budgetRemaining: 100,
      toolCallsExecuted: 2,
      messageCount: 5,
      activeAgentId: 'primary',
      autonomyLevel: 'GUARDED',
      park: { kind: 'budget', sinceEpochMs: 111, detail: 'needs 500 more' },
    });
    expect(ctrl.activeAgentId).toBe('primary');
    expect(ctrl.snapshotAutonomyLevel).toBe('GUARDED');
    expect(ctrl.runPark).toEqual({ kind: 'budget', sinceEpochMs: 111, detail: 'needs 500 more' });
  });

  // ===== 1d. 859 D live-defect D1 — the run's step count, MID-RUN =====
  describe('859 D1 — the gate facts are known WHILE the run is running', () => {
    it('starts as NOT-REPORTED, so nothing can render a step count as a confident zero', () => {
      // The defect in one line: the field was `0` and `onDone` was its only writer, so every
      // mid-run reader — the budget gate's fact panel above all — was told "0 steps" by a
      // controller that had simply never been told. `null` is what "not told" has to look like.
      expect(ctrl.iterationsUsed).toBeNull();
    });

    it('learns the step count from the run\'s own progress frames, before any terminal', () => {
      // `iteration` on a progress frame IS `AgentSession.iterationsUsed()` at that moment
      // (AgentStepRunner increments once per pass and stamps `iteration + 1` on every note), and
      // BOTH gates announce themselves with one of these immediately before the gate event — so a
      // gate is structurally never reached with an unknown count.
      ctrl.onProgress({ phase: 'llm_call', message: 'Thinking', iteration: 1, maxIterations: 10 });
      expect(ctrl.iterationsUsed).toBe(1);
      ctrl.onProgress({ phase: 'llm_call', message: 'Thinking', iteration: 5, maxIterations: 10 });
      ctrl.onProgress({
        phase: 'budget_gate_held',
        message: 'Waiting on budget',
        iteration: 5,
        maxIterations: 10,
      });
      expect(ctrl.iterationsUsed).toBe(5);
    });

    it('never walks the count backwards, and never invents one from a malformed frame', () => {
      ctrl.onProgress({ phase: 'llm_call', message: '', iteration: 4, maxIterations: 10 });
      // Frames can arrive out of order (a replay behind a live stream); a run does not un-take a step.
      ctrl.onProgress({ phase: 'llm_call', message: '', iteration: 2, maxIterations: 10 });
      expect(ctrl.iterationsUsed).toBe(4);
      // And a frame that says nothing usable leaves the field exactly as it was.
      ctrl.onProgress({ phase: 'llm_call', message: '' });
      ctrl.onProgress({ phase: 'llm_call', message: '', iteration: 'lots' });
      expect(ctrl.iterationsUsed).toBe(4);
    });

    it('a REATTACHING tab reads the step count off the primer, whose frames the ring may have evicted', () => {
      // The ring carries narrative only and evicts, so a long run parked at a gate can have every
      // progress frame gone while the gate is still open and answerable. The snapshot is then the
      // only authority left, and it comes straight off `AgentSession`.
      ctrl.onStateSnapshot({
        iteration: 7,
        budgetRemaining: 10,
        toolCallsExecuted: 4,
        messageCount: 12,
        activeAgentId: 'primary',
      });
      expect(ctrl.iterationsUsed).toBe(7);
    });

    it('a fresh dispatch forgets the previous run\'s counts rather than carrying them over', () => {
      ctrl.onProgress({ phase: 'llm_call', message: '', iteration: 6, maxIterations: 10 });
      ctrl.onDone({
        finalResponse: 'done',
        iterationsUsed: 6,
        toolCallsExecuted: 3,
        totalTokensUsed: 900,
      });
      expect(ctrl.iterationsUsed).toBe(6);
      // `exitReplay` is the public door onto the same reset the next dispatch performs.
      ctrl.exitReplay();
      expect(ctrl.iterationsUsed).toBeNull();
    });
  });

  it('a snapshot-carried held call is renderable AND answerable after the ring evicted its frame', async () => {
    // The reattach case the ring cannot serve: no `tool_call_pending` frame ever arrives, so before
    // 834 §6.1 the gate existed on the backend and nowhere on screen.
    const decisions: string[] = [];
    setAuthorizationPresenter(async (p) => {
      decisions.push(p.pendingId);
      return { approved: true, allowAlways: false };
    });
    ctrl.sessionId = 'sess-parked';
    globalThis.fetch = mockFetchLiveApprovals(ctrl);

    ctrl.onStateSnapshot({
      iteration: 9,
      budgetRemaining: 10,
      toolCallsExecuted: 4,
      messageCount: 8,
      activeAgentId: 'primary',
      park: { kind: 'approval', sinceEpochMs: 222, detail: 'core_delete_file' },
      pendingApprovals: [
        {
          callId: 'call-held',
          toolName: 'core_delete_file',
          arguments: '{"path":"a.txt"}',
          risk: 'HIGH',
          gateBehavior: 'typed_confirm',
        },
      ],
    });

    // Renderable: the call is on the controller AND in the conversation, so the feed projection
    // (which walks `tool-call-group` entries) finds it exactly as it finds a live one.
    expect(ctrl.toolCalls['call-held']?.status).toBe('pending');
    expect(
      ctrl.conversation.some((e) => e.type === 'tool-call-group' && e.callIds?.includes('call-held')),
    ).toBe(true);
    // Answerable: the ONE authorization ceremony was opened for it.
    await new Promise<void>((r) => setTimeout(r, 0));
    expect(decisions).toEqual(['call-held']);
  });

  it('a snapshot approval the replay ring ALSO announces opens exactly one ceremony', async () => {
    const opened: string[] = [];
    setAuthorizationPresenter(async (p) => {
      opened.push(p.pendingId);
      return { approved: false, allowAlways: false };
    });
    ctrl.sessionId = 'sess-dup';
    globalThis.fetch = mockFetchLiveApprovals(ctrl);
    const approval = {
      callId: 'call-dup',
      toolName: 'core_search',
      arguments: '{}',
      risk: 'LOW',
    };
    ctrl.onStateSnapshot({
      iteration: 1,
      budgetRemaining: 1,
      toolCallsExecuted: 0,
      messageCount: 1,
      activeAgentId: 'primary',
      pendingApprovals: [approval],
    });
    ctrl.onToolCallPending({ ...approval, sessionId: 'sess-dup' }); // the ring had NOT evicted it
    await new Promise<void>((r) => setTimeout(r, 0));
    expect(opened).toEqual(['call-dup']);
    // ...and the group carries the call once, so the receipt cannot count it twice.
    const groups = ctrl.conversation.filter((e) => e.type === 'tool-call-group');
    expect(groups.flatMap((g) => g.callIds ?? [])).toEqual(['call-dup']);
  });

  it('a snapshot does NOT re-hold a call the run has already moved past', () => {
    ctrl.onToolCallPending({ callId: 'call-done', toolName: 'core_search', arguments: '{}', risk: 'LOW', gateBehavior: 'auto' });
    ctrl.onToolCallApproved({ callId: 'call-done' });
    expect(ctrl.toolCalls['call-done']?.status).toBe('approved');
    ctrl.onStateSnapshot({
      iteration: 2,
      budgetRemaining: 1,
      toolCallsExecuted: 1,
      messageCount: 2,
      activeAgentId: 'primary',
      pendingApprovals: [
        { callId: 'call-done', toolName: 'core_search', arguments: '{}', risk: 'LOW' },
      ],
    });
    expect(ctrl.toolCalls['call-done']?.status).toBe('approved');
  });

  // ===== 2. chunk =====
  it('onChunk appends text to streamingText (multiple chunks concatenate)', () => {
    ctrl.onChunk({ text: 'Hello ' });
    ctrl.onChunk({ text: 'world' });
    expect(ctrl.streamingText).toBe('Hello world');
  });

  // ===== 3. tool_call_proposed =====
  it('onToolCallProposed adds a tool call with status="proposed"', () => {
    ctrl.onToolCallProposed({
      callId: 'c1', toolName: 'core_search_index', arguments: '{"query":"x"}', risk: 'LOW',
    });
    expect(ctrl.toolCalls.c1?.status).toBe('proposed');
    expect(ctrl.toolCalls.c1?.toolName).toBe('core_search_index');
    expect(ctrl.toolCalls.c1?.risk).toBe('LOW');
  });

  // ===== 3b. tool_batch_proposed (tempdoc 550 N1) =====
  it('onToolBatchProposed records the turn\'s proposed tool-call batch', () => {
    ctrl.onToolBatchProposed({
      calls: [
        { callId: 'c1', toolName: 'core_search_index' },
        { callId: 'c2', toolName: 'core_file_operations' },
      ],
    });
    expect(ctrl.currentToolBatch.map(c => c.toolName)).toEqual([
      'core_search_index',
      'core_file_operations',
    ]);
    expect(ctrl.currentToolBatch.map(c => c.callId)).toEqual(['c1', 'c2']);
  });

  it('clears currentToolBatch on done and on a new session (F2 — no stale plan)', () => {
    ctrl.onToolBatchProposed({ calls: [{ callId: 'c1', toolName: 'core_search_index' }] });
    expect(ctrl.currentToolBatch).toHaveLength(1);
    ctrl.onDone({ finalResponse: 'ok', iterationsUsed: 1, toolCallsExecuted: 1, totalTokensUsed: 0 });
    expect(ctrl.currentToolBatch).toHaveLength(0);
    // And a fresh run also starts clean.
    ctrl.onToolBatchProposed({ calls: [{ callId: 'c2', toolName: 'core_browse_folders' }] });
    expect(ctrl.currentToolBatch).toHaveLength(1);
    ctrl.onSessionStarted({ sessionId: 'sess-new' });
    expect(ctrl.currentToolBatch).toHaveLength(0);
  });

  // ===== 4. tool_call_pending =====
  it('onToolCallPending adds a tool call with status="pending" AND commits streamingText into a tool-call-group', () => {
    ctrl.onChunk({ text: 'Pre-tool text. ' });
    ctrl.onToolCallPending({
      callId: 'c2', toolName: 'core_browse_folders', arguments: '{"path":"/"}', risk: 'MEDIUM',
    });
    expect(ctrl.toolCalls.c2?.status).toBe('pending');
    const assistantText = ctrl.conversation.find(e => e.type === 'assistant-text');
    expect(assistantText?.content).toBe('Pre-tool text. ');
    const group = ctrl.conversation.find(e => e.type === 'tool-call-group');
    expect(group?.callIds).toContain('c2');
    expect(ctrl.streamingText).toBe('');
  });

  it('onToolCallProposed does NOT commit streamingText (only pending does)', () => {
    ctrl.onChunk({ text: 'Pre. ' });
    ctrl.onToolCallProposed({
      callId: 'cp', toolName: 'core_search_index', arguments: '{}', risk: 'LOW',
    });
    expect(ctrl.streamingText).toBe('Pre. ');
    expect(ctrl.conversation.find(e => e.type === 'tool-call-group')).toBeUndefined();
  });

  // ===== 5. tool_call_approved =====
  it('onToolCallApproved updates status to "approved" when toolCall exists', () => {
    ctrl.onToolCallPending({ callId: 'c3', toolName: 't', arguments: '{}', risk: 'LOW' });
    ctrl.onToolCallApproved({ callId: 'c3' });
    expect(ctrl.toolCalls.c3?.status).toBe('approved');
  });

  it('onToolCallApproved is a no-op for unknown callIds', () => {
    ctrl.onToolCallApproved({ callId: 'never-seen' });
    expect(ctrl.toolCalls['never-seen']).toBeUndefined();
  });

  // ===== 6. tool_exec_started (dedup-checks grouping) =====
  it('onToolExecStarted commits streamingText when the call is NOT already grouped', () => {
    ctrl.onToolCallProposed({ callId: 'c4', toolName: 't', arguments: '{}', risk: 'LOW' });
    ctrl.onChunk({ text: 'streaming midway ' });
    ctrl.onToolExecStarted({ callId: 'c4', toolName: 't' });
    expect(ctrl.toolCalls.c4?.status).toBe('executing');
    const group = ctrl.conversation.find(
      e => e.type === 'tool-call-group' && e.callIds?.includes('c4'),
    );
    expect(group).toBeDefined();
  });

  it('onToolExecStarted skips re-committing when the call is already grouped (pending → started)', () => {
    ctrl.onChunk({ text: 'pre. ' });
    ctrl.onToolCallPending({ callId: 'c5', toolName: 't', arguments: '{}', risk: 'LOW' });
    const groupsAfterPending = ctrl.conversation.filter(e => e.type === 'tool-call-group').length;
    ctrl.onToolExecStarted({ callId: 'c5', toolName: 't' });
    const groupsAfterStarted = ctrl.conversation.filter(e => e.type === 'tool-call-group').length;
    expect(groupsAfterStarted).toBe(groupsAfterPending);
    expect(ctrl.toolCalls.c5?.status).toBe('executing');
  });

  it('onToolExecStarted is a no-op for unknown callIds', () => {
    ctrl.onToolExecStarted({ callId: 'unknown', toolName: 't' });
    expect(ctrl.toolCalls.unknown).toBeUndefined();
  });

  // ===== 7. tool_exec_completed =====
  it('onToolExecCompleted sets status, success, output, executionId from result wrapper', () => {
    ctrl.onToolCallPending({ callId: 'c6', toolName: 't', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({
      callId: 'c6', result: { success: true, output: 'result text', executionId: 'ex-1' },
    });
    expect(ctrl.toolCalls.c6?.status).toBe('completed');
    expect(ctrl.toolCalls.c6?.success).toBe(true);
    expect(ctrl.toolCalls.c6?.output).toBe('result text');
    expect(ctrl.toolCalls.c6?.executionId).toBe('ex-1');
  });

  it('onToolExecCompleted falls back to flat fields when result wrapper is absent', () => {
    ctrl.onToolCallPending({ callId: 'c6b', toolName: 't', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({ callId: 'c6b', success: false, output: 'failure text', executionId: 'ex-2' });
    expect(ctrl.toolCalls['c6b']?.success).toBe(false);
    expect(ctrl.toolCalls['c6b']?.output).toBe('failure text');
    expect(ctrl.toolCalls['c6b']?.executionId).toBe('ex-2');
  });

  // ===== Tempdoc 865 §7.1: the per-call grounding delta =====
  const deltaSource = (parentDocId: string) => ({
    parentDocId,
    chunkIndex: 0,
    path: `f:/${parentDocId}`,
    title: parentDocId,
    excerpt: 'a passage',
    startLine: 1,
    endLine: 5,
    headingText: '',
  });

  it('accumulates each call\u2019s grounding delta, in call order, without deduping', () => {
    // The backend already applied the run-wide dedup before splitting the set into deltas, so this
    // side only concatenates — re-deduping here would be a second mint free to disagree about ORDER,
    // which is what AgentSentenceCite.sourceIndex resolves through.
    ctrl.sessionId = 'run-1';
    ctrl.onToolCallPending({ callId: 'g1', toolName: 'core_search_index', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({
      callId: 'g1',
      result: {
        success: true,
        output: 'ok',
        structuredData: { grounding: [deltaSource('docs/a.md'), deltaSource('docs/b.md')] },
      },
    });
    ctrl.onToolCallPending({ callId: 'g2', toolName: 'core_search_index', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({
      callId: 'g2',
      result: { success: true, output: 'ok', structuredData: { grounding: [deltaSource('docs/c.md')] } },
    });

    expect(ctrl.groundingDeltas.map((s) => s.parentDocId)).toEqual([
      'docs/a.md',
      'docs/b.md',
      'docs/c.md',
    ]);
    expect(ctrl.groundingDeltasRunId).toBe('run-1');
  });

  it('a call with no grounding key adds nothing — absent means "established nothing"', () => {
    ctrl.sessionId = 'run-1';
    ctrl.onToolCallPending({ callId: 'g3', toolName: 'core_file_read', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({ callId: 'g3', result: { success: true, output: 'ok' } });
    expect(ctrl.groundingDeltas).toEqual([]);
    // Never stamped, so no run owns an empty set — the reader compares the stamp, not truthiness.
    expect(ctrl.groundingDeltasRunId).toBeNull();
  });

  it('a delta from a NEW run starts the set over rather than appending to the previous run\u2019s', () => {
    ctrl.sessionId = 'run-1';
    ctrl.onToolCallPending({ callId: 'g4', toolName: 'core_search_index', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({
      callId: 'g4',
      result: { success: true, output: 'ok', structuredData: { grounding: [deltaSource('docs/a.md')] } },
    });
    ctrl.sessionId = 'run-2';
    ctrl.onToolCallPending({ callId: 'g5', toolName: 'core_search_index', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({
      callId: 'g5',
      result: { success: true, output: 'ok', structuredData: { grounding: [deltaSource('docs/z.md')] } },
    });
    expect(ctrl.groundingDeltas.map((s) => s.parentDocId)).toEqual(['docs/z.md']);
    expect(ctrl.groundingDeltasRunId).toBe('run-2');
  });

  // ===== Tempdoc 867: groundingDeltaPaths — the tool-card evidence-set read =====
  it('groundingDeltaPaths joins groundingDeltas by `path`, live, on every access', () => {
    ctrl.sessionId = 'run-1';
    expect(ctrl.groundingDeltaPaths.size).toBe(0);
    ctrl.onToolCallPending({ callId: 'g6', toolName: 'core_search_index', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({
      callId: 'g6',
      result: {
        success: true,
        output: 'ok',
        structuredData: { grounding: [deltaSource('docs/a.md'), deltaSource('docs/b.md')] },
      },
    });
    expect(ctrl.groundingDeltaPaths).toEqual(new Set(['f:/docs/a.md', 'f:/docs/b.md']));
    // A second call's delta is APPENDED (867 reads the live accumulation, not a snapshot).
    ctrl.onToolCallPending({ callId: 'g7', toolName: 'core_search_index', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({
      callId: 'g7',
      result: { success: true, output: 'ok', structuredData: { grounding: [deltaSource('docs/c.md')] } },
    });
    expect(ctrl.groundingDeltaPaths).toEqual(new Set(['f:/docs/a.md', 'f:/docs/b.md', 'f:/docs/c.md']));
  });

  // ===== 543-fwd idea #0: agent→journal bridge =====
  it('journals a successful tool-call as an originator:agent invoke-operation entry + wires undo via executionId', () => {
    ctrl.onToolCallPending({ callId: 'b1', toolName: 'core_search_index', arguments: '{"query":"x"}', risk: 'LOW' });
    ctrl.onToolExecCompleted({ callId: 'b1', result: { success: true, output: 'ok', executionId: 'exec-9' } });
    const entries = listJournalByOriginator('agent');
    expect(entries).toHaveLength(1);
    expect(entries[0]!.effect).toEqual({ kind: 'invoke-operation', operationId: 'core_search_index', args: { query: 'x' } });
    expect(getUndoableOperation(entries[0]!.id)).toEqual({ operationId: 'core_search_index', executionId: 'exec-9' });
  });

  it('does NOT journal a failed tool-call (no state change to undo/digest)', () => {
    ctrl.onToolCallPending({ callId: 'b2', toolName: 'core_search_index', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({ callId: 'b2', result: { success: false, output: 'err' } });
    expect(listJournalByOriginator('agent')).toHaveLength(0);
  });

  it('skips vop_ tools (they self-journal via the virtual path)', () => {
    ctrl.onToolCallPending({ callId: 'b3', toolName: 'vop_open_pane', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({ callId: 'b3', result: { success: true, output: 'ok', executionId: 'exec-x' } });
    expect(listJournalByOriginator('agent')).toHaveLength(0);
  });

  it('journals without an executionId → entry exists but carries no undoable mapping', () => {
    ctrl.onToolCallPending({ callId: 'b4', toolName: 'core_browse_folders', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({ callId: 'b4', result: { success: true, output: 'ok' } });
    const entries = listJournalByOriginator('agent');
    expect(entries).toHaveLength(1);
    expect(getUndoableOperation(entries[0]!.id)).toBeUndefined();
  });

  // ===== 543-fwd #6: causation enrichment — chain a turn's tool-calls =====
  it('chains tool-calls within a turn via causation; a new session starts a fresh chain', () => {
    ctrl.onSessionStarted({ sessionId: 'turn-1' });
    ctrl.onToolCallPending({ callId: 't1a', toolName: 'core_search_index', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({ callId: 't1a', result: { success: true, output: 'ok' } });
    ctrl.onToolCallPending({ callId: 't1b', toolName: 'core_search_index', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({ callId: 't1b', result: { success: true, output: 'ok' } });
    const turn1 = listJournalByOriginator('agent');
    expect(turn1).toHaveLength(2);
    expect(turn1[0]!.causation).toBeUndefined(); // first call = chain root
    expect(turn1[1]!.causation).toBe(turn1[0]!.id); // second chained to first
    // A new session resets the chain — its first call has no parent.
    ctrl.onSessionStarted({ sessionId: 'turn-2' });
    ctrl.onToolCallPending({ callId: 't2a', toolName: 'core_search_index', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({ callId: 't2a', result: { success: true, output: 'ok' } });
    const all = listJournalByOriginator('agent');
    expect(all).toHaveLength(3);
    expect(all[2]!.causation).toBeUndefined(); // new turn = fresh root
  });

  // ===== 8. tool_call_rejected =====
  it('onToolCallRejected sets status="rejected" with rejectReason', () => {
    ctrl.onToolCallPending({ callId: 'c7', toolName: 't', arguments: '{}', risk: 'HIGH' });
    ctrl.onToolCallRejected({ callId: 'c7', reason: 'user denied' });
    expect(ctrl.toolCalls.c7?.status).toBe('rejected');
    expect(ctrl.toolCalls.c7?.rejectReason).toBe('user denied');
  });

  // ===== 9. done (dedup against streamingText) =====
  it('onDone commits streamingText, then appends assistant-text when finalResp differs', () => {
    ctrl.isStreaming = true;
    ctrl.onChunk({ text: 'partial stream' });
    ctrl.onDone({ finalResponse: 'final canonical response', iterationsUsed: 3, toolCallsExecuted: 1, totalTokensUsed: 1234 });
    const assistantTexts = ctrl.conversation.filter(e => e.type === 'assistant-text');
    expect(assistantTexts.map(e => e.content)).toEqual(['partial stream', 'final canonical response']);
    expect(ctrl.iterationsUsed).toBe(3);
    expect(ctrl.totalTokensUsed).toBe(1234);
    expect(ctrl.isStreaming).toBe(false);
  });

  it('onDone does NOT double-append when finalResp === streamingText.trim (dedup invariant)', () => {
    ctrl.isStreaming = true;
    ctrl.onChunk({ text: 'identical text' });
    ctrl.onDone({ finalResponse: 'identical text', iterationsUsed: 1, toolCallsExecuted: 0, totalTokensUsed: 0 });
    const assistantTexts = ctrl.conversation.filter(e => e.type === 'assistant-text');
    expect(assistantTexts.map(e => e.content)).toEqual(['identical text']);
  });

  it('onDone with no finalResponse still commits streamingText + clears isStreaming', () => {
    ctrl.isStreaming = true;
    ctrl.onChunk({ text: 'only stream' });
    ctrl.onDone({ finalResponse: '', iterationsUsed: 0, toolCallsExecuted: 0, totalTokensUsed: 0 });
    const assistantTexts = ctrl.conversation.filter(e => e.type === 'assistant-text');
    expect(assistantTexts.map(e => e.content)).toEqual(['only stream']);
    expect(ctrl.isStreaming).toBe(false);
  });

  // Tempdoc 565 §27.2 — the PRODUCTION-side guard for the zero-padded entry id. The projection-side
  // tie test (unifiedThreadProjection.test.ts) proves the sort contract with a local pad() helper;
  // this one pins that `nextEntryId()` itself emits the padded format, so a revert of the
  // `padStart(6,'0')` in AgentSessionController would fail here, not slip through (the reviewer's
  // §28 MINOR: the emitter format must be guarded, not just the consumer's assumption about it).
  it('nextEntryId emits zero-padded ids so lexical order == temporal order past the 9->10 boundary', () => {
    // onDone appends one assistant-text via nextEntryId() per distinct finalResponse (no stream to dedup).
    for (let i = 1; i <= 12; i++) {
      ctrl.onDone({ finalResponse: `response ${i}`, iterationsUsed: 1, toolCallsExecuted: 0, totalTokensUsed: 0 });
    }
    const ids = ctrl.conversation.filter(e => e.type === 'assistant-text').map(e => e.id);
    expect(ids.length).toBeGreaterThan(9); // crossed the 9->10 boundary where padding matters
    // Every production id is zero-padded to 6 digits — an unpadded `e-10` would fail this regex.
    for (const id of ids) expect(id).toMatch(/^e-\d{6}$/);
    // Insertion order is temporal; assert it is ALSO lexical order. The pre-fix `e-${n}` format
    // would invert here (`e-10` < `e-9` lexically), so this distinguishes the right reason.
    expect([...ids].sort()).toEqual(ids);
  });

  // Tempdoc 565 §3.A / §13.8 — the typed onDone reads the now-truthful done-event grounding fields
  // (was a loose Record<unknown> cast). These pin the source/citation population the de-risk + the
  // live browser check confirmed but no unit test covered.
  it('onDone populates answerSources + answerCitations from the typed done payload', () => {
    ctrl.onDone({
      finalResponse: 'a [1]',
      iterationsUsed: 2,
      toolCallsExecuted: 1,
      totalTokensUsed: 10,
      sources: [
        { parentDocId: 'd1', chunkIndex: 0, path: '/a.md', title: 'A', excerpt: 'x', startLine: 5, endLine: 9, headingText: '' },
        { parentDocId: 'd2', chunkIndex: 1, path: '/b.md', title: 'B', excerpt: 'y', startLine: 1, endLine: 4, headingText: '' },
      ],
      citations: [{ sentenceText: 'a', sourceIndex: 0, similarity: 0.9 }],
    });
    expect(ctrl.answerSources.map(s => s.parentDocId)).toEqual(['d1', 'd2']);
    expect(ctrl.answerSources[0]!.startLine).toBe(5);
    expect(ctrl.answerCitations).toHaveLength(1);
  });

  it('onDone with absent grounding fields → empty answerSources/answerCitations (ungrounded run)', () => {
    ctrl.onDone({ finalResponse: 'a', iterationsUsed: 1, toolCallsExecuted: 0, totalTokensUsed: 0 });
    expect(ctrl.answerSources).toEqual([]);
    expect(ctrl.answerCitations).toEqual([]);
  });

  // ===== 10. budget_update =====
  it('onBudgetUpdate appends to budgetUpdates with default-zero filling', () => {
    ctrl.onBudgetUpdate({ phase: 'p1', tokensConsumed: 100, tokensRemaining: 900 });
    ctrl.onBudgetUpdate({ phase: 'p2', tokensConsumed: 50 });
    expect(ctrl.budgetUpdates).toEqual([
      { phase: 'p1', tokensConsumed: 100, tokensRemaining: 900 },
      { phase: 'p2', tokensConsumed: 50, tokensRemaining: 0 },
    ]);
  });

  // Tempdoc 577 Move 2 — the held budget gate.
  it('onBudgetGate parks the run; a budget_update or new run clears it', () => {
    ctrl.onBudgetGate({ tokensNeeded: 1200, tokensRemaining: -700, totalTokensConsumed: 6700 });
    expect(ctrl.budgetGate).toEqual({
      tokensNeeded: 1200,
      tokensRemaining: -700,
      totalTokensConsumed: 6700,
    });
    // Any budget movement means the gate resolved (CONTINUE emits the next iteration_start).
    ctrl.onBudgetUpdate({ phase: 'iteration_start', tokensConsumed: 10, tokensRemaining: 3000 });
    expect(ctrl.budgetGate).toBeNull();
  });

  // ===== 11. error =====
  it('onError appends an error entry + clears isStreaming', () => {
    ctrl.isStreaming = true;
    ctrl.onError({ error: 'AI_OFFLINE', errorCode: 'AI_OFFLINE' });
    const errorEntry = ctrl.conversation.find(e => e.type === 'error');
    expect(errorEntry?.content).toBe('AI_OFFLINE');
    expect(errorEntry?.errorCode).toBe('AI_OFFLINE');
    expect(ctrl.isStreaming).toBe(false);
  });

  it('onError with no error field falls back to "Unknown error"', () => {
    ctrl.onError({});
    const errorEntry = ctrl.conversation.find(e => e.type === 'error');
    expect(errorEntry?.content).toBe('Unknown error');
  });

  // ===== 12. progress =====
  it('onProgress appends a progress entry with message or falls back to phase', () => {
    ctrl.onProgress({ message: 'doing things' });
    ctrl.onProgress({ phase: 'phase-2' });
    const progressEntries = ctrl.conversation.filter(e => e.type === 'progress');
    expect(progressEntries.map(e => e.content)).toEqual(['doing things', 'phase-2']);
  });

  // Tempdoc 561 #5: severity rides the wire so the renderer decorates by intent (no ⚠ on routine).
  it('onProgress carries the backend severity (undefined when absent)', () => {
    ctrl.onProgress({ message: 'routine', severity: 'info' });
    ctrl.onProgress({ message: 'a failure', severity: 'error' });
    ctrl.onProgress({ message: 'no severity field' });
    const progress = ctrl.conversation.filter(e => e.type === 'progress');
    expect(progress.map(e => e.severity)).toEqual(['info', 'error', undefined]);
  });

  // ===== 13. handoff_proposed =====
  it('onHandoffProposed appends a handoff entry with from/to/reason in content', () => {
    ctrl.onHandoffProposed({ fromAgentId: 'manager', toAgentId: 'specialist', reason: 'needs domain expertise' });
    const handoff = ctrl.conversation.find(e => e.type === 'handoff');
    expect(handoff?.content).toBe('Handoff: manager → specialist: needs domain expertise');
    expect(handoff?.fromAgentId).toBe('manager');
    expect(handoff?.toAgentId).toBe('specialist');
  });

  // ===== 14. handoff_executed =====
  it('onHandoffExecuted sets activeAgentId to toAgentId', () => {
    ctrl.onHandoffExecuted({ fromAgentId: 'manager', toAgentId: 'specialist' });
    expect(ctrl.activeAgentId).toBe('specialist');
  });

  // ===== Cross-cutting: tool_call_pending side-effect sets sessionId =====
  it('onToolCallPending populates sessionId when it arrives in the event payload', () => {
    expect(ctrl.sessionId).toBeNull();
    ctrl.onToolCallPending({
      callId: 'c8', toolName: 't', arguments: '{}', risk: 'LOW', sessionId: 'sess-from-tool-pending',
    });
    expect(ctrl.sessionId).toBe('sess-from-tool-pending');
  });
});

// ==================== Extended harness: interaction methods ====================

describe('sessionLabel (561 #4 — human label, never the raw UUID)', () => {
  it('uses the backend first-user-message preview when present', () => {
    expect(sessionLabel({ sessionId: 'abc-123', preview: 'find my tax docs' })).toBe(
      'find my tax docs',
    );
  });
  it('falls back to initialMessage, then a neutral label — never the UUID', () => {
    expect(sessionLabel({ sessionId: 'abc-123', initialMessage: 'older field' })).toBe(
      'older field',
    );
    const label = sessionLabel({ sessionId: '475d1d1f-aaaa-bbbb-cccc-ddddeeeeffff' });
    expect(label).toBe('Untitled session');
    expect(label).not.toContain('475d1d1f');
  });
  it('treats a blank preview as absent', () => {
    expect(sessionLabel({ sessionId: 'abc-123', preview: '   ' })).toBe('Untitled session');
  });
});

/**
 * Tempdoc 834 §5.2 — a verbatim mirror of `InterruptedRunPresentation.java`. The cases below are the
 * Java's own branches in its own order, so the two derive the same answer from the same triple.
 */
describe('interruptedRunPresentation (834 §5.2 — the four honest rows)', () => {
  const row = (over: Partial<Parameters<typeof interruptedRunPresentation>[0]>) =>
    ({ sessionId: 's', ...over }) as Parameters<typeof interruptedRunPresentation>[0];

  it('a terminal run is FINISHED, with no interruption marker, even if interruptedAt is stamped', () => {
    expect(interruptedRunPresentation(row({ status: 'DONE', resumable: false }))).toBe('FINISHED');
    expect(
      interruptedRunPresentation(row({ status: 'ERROR', interruptedAt: '2026-08-18T10:00:00Z' })),
    ).toBe('FINISHED');
    expect(interruptedRunNotice(row({ status: 'DONE' }))).toBeNull();
  });

  it('a non-terminal run with no interruptedAt is NOT_INTERRUPTED — the ordinary live/idle case', () => {
    expect(interruptedRunPresentation(row({ status: 'READY_FOR_LLM', resumable: true }))).toBe(
      'NOT_INTERRUPTED',
    );
    expect(interruptedRunPresentation(row({ status: 'READY_FOR_LLM', interruptedAt: '  ' }))).toBe(
      'NOT_INTERRUPTED',
    );
  });

  it('mid-step states are RESUMABLE — "Interrupted when the app closed. Resume."', () => {
    for (const status of ['READY_FOR_LLM', 'AFTER_TOOL_RESULT']) {
      const s = row({ status, resumable: true, interruptedAt: '2026-08-18T10:00:00Z' });
      expect(interruptedRunPresentation(s)).toBe('RESUMABLE');
      expect(interruptedRunNotice(s)).toBe('Interrupted when the app closed. Resume.');
    }
  });

  it('WAITING_APPROVAL is RESUMABLE_AT_APPROVAL — same offer, different copy', () => {
    const s = row({ status: 'WAITING_APPROVAL', resumable: true, interruptedAt: '2026-08-18T10:00:00Z' });
    expect(interruptedRunPresentation(s)).toBe('RESUMABLE_AT_APPROVAL');
    expect(interruptedRunNotice(s)).toBe('Interrupted while waiting for your approval. Resume.');
  });

  it('the budget/context gates are FORK_ONLY — non-terminal but NOT resumable', () => {
    for (const status of ['WAITING_BUDGET', 'WAITING_CONTEXT']) {
      // Even a record claiming `resumable: true` is FORK_ONLY here: the held decision lived only in
      // memory, so the state name decides before the flag is consulted (the Java's own ordering).
      const s = row({ status, resumable: true, interruptedAt: '2026-08-18T10:00:00Z' });
      expect(interruptedRunPresentation(s)).toBe('FORK_ONLY');
      expect(interruptedRunNotice(s)).toBe(
        'Interrupted while waiting for your decision about tokens/context. Cannot be resumed — start a new run from this transcript',
      );
    }
  });

  it('defensive branch: an interrupted non-terminal state the store did not mark resumable is FORK_ONLY', () => {
    expect(
      interruptedRunPresentation(
        row({ status: 'SOMETHING_NEW', resumable: false, interruptedAt: '2026-08-18T10:00:00Z' }),
      ),
    ).toBe('FORK_ONLY');
  });
});

describe('AgentSessionController interaction methods', () => {
  // ===== checkAvailability =====
  it('checkAvailability sets available=true and populates tools on 200', async () => {
    globalThis.fetch = mockFetchJson({ available: true, tools: [{ name: 'search' }, { name: 'browse' }] });
    await ctrl.checkAvailability();
    expect(ctrl.available).toBe(true);
    expect(ctrl.tools).toEqual([{ name: 'search' }, { name: 'browse' }]);
  });

  it('checkAvailability sets available=false on non-200', async () => {
    globalThis.fetch = mockFetchError(503);
    await ctrl.checkAvailability();
    expect(ctrl.available).toBe(false);
    expect(ctrl.tools).toEqual([]);
  });

  it('checkAvailability sets available=false on fetch error', async () => {
    globalThis.fetch = vi.fn(() => Promise.reject(new Error('network'))) as unknown as typeof fetch;
    await ctrl.checkAvailability();
    expect(ctrl.available).toBe(false);
    expect(ctrl.tools).toEqual([]);
  });

  // ===== send (full SSE round-trip via consumeShapeStream) =====
  it('send posts message and processes SSE events into state', async () => {
    const body =
      sseChunk('session_started', { sessionId: 'sess-1' }) +
      sseChunk('chunk', { text: 'Response text' }) +
      sseChunk('done', { finalResponse: 'Response text', iterationsUsed: 1, toolCallsExecuted: 0 });
    const fetchSpy = mockFetchSse(body);
    globalThis.fetch = fetchSpy;

    await ctrl.send('hello agent');

    expect(fetchSpy).toHaveBeenCalled();
    const callArgs = (fetchSpy as ReturnType<typeof vi.fn>).mock.calls[0]!;
    expect(callArgs[0]).toBe('http://test/api/chat/agent');
    expect(ctrl.sessionId).toBe('sess-1');
    expect(ctrl.isStreaming).toBe(false);
    expect(ctrl.iterationsUsed).toBe(1);
    const userEntries = ctrl.conversation.filter(e => e.type === 'user');
    expect(userEntries.length).toBe(1);
    expect(userEntries[0]?.content).toBe('hello agent');
    const assistantEntries = ctrl.conversation.filter(e => e.type === 'assistant-text');
    expect(assistantEntries.length).toBe(1);
  });

  it('send appends error entry on non-200 HTTP (via consumeShapeStream throw)', async () => {
    globalThis.fetch = mockFetchError(500, 'Internal Server Error');
    await ctrl.send('test');
    const errors = ctrl.conversation.filter(e => e.type === 'error');
    expect(errors.length).toBe(1);
    expect(ctrl.isStreaming).toBe(false);
  });

  it('send does not double-append error when SSE error event fires + consumeShapeStream throws (D1)', async () => {
    const body = sseChunk('error', { error: 'AI_OFFLINE', errorCode: 'AI_OFFLINE' });
    globalThis.fetch = mockFetchSse(body);
    await ctrl.send('test');
    const errors = ctrl.conversation.filter(e => e.type === 'error');
    expect(errors.length).toBe(1);
    expect(errors[0]?.content).toBe('AI_OFFLINE');
  });

  // ===== Tempdoc 577 Root I (#13/#1d): reattach on a mid-run stream drop =====
  it('reattaches to the live run when the stream DROPS mid-run (after session_started)', async () => {
    const encoder = new TextEncoder();
    let call = 0;
    const fetchSpy = vi.fn((_url: string) => {
      call += 1;
      if (call === 1) {
        // The send stream establishes the run (session_started) then DROPS: it ends WITHOUT a
        // terminal (done/error) event — exactly a mid-run socket drop. consumeShapeStream processes
        // session_started, then throws STREAM_INCOMPLETE (not an AbortError) → the reattach path.
        const stream = new ReadableStream<Uint8Array>({
          start(c) {
            c.enqueue(encoder.encode(sseChunk('session_started', { sessionId: 'sess-1' })));
            c.close();
          },
        });
        return Promise.resolve(
          new Response(stream, { status: 200, headers: { 'content-type': 'text/event-stream' } }),
        );
      }
      // The reattach stream: the run already ended, so the MANAGED route answers a typed 404
      // (tempdoc 834 §15.3 — the legacy `attach_not_live` event's replacement).
      return Promise.resolve(new Response(runNotFoundBody('sess-1'), { status: 404 }));
    });
    globalThis.fetch = fetchSpy as unknown as typeof fetch;

    await ctrl.send('do the work');

    // A second fetch fired — the reattach onto the MANAGED observe route, NOT a false error on the FE.
    expect(fetchSpy).toHaveBeenCalledTimes(2);
    expect((fetchSpy.mock.calls[1]![0] as string)).toContain('/api/chat/runs/sess-1/observe');
    // The drop was NOT surfaced as a conversation error (the run was reattached, then fell back cleanly).
    expect(ctrl.conversation.filter((e) => e.type === 'error').length).toBe(0);
    expect(ctrl.isStreaming).toBe(false);
  });

  it('does NOT reattach when the initial send POST fails (no run established)', async () => {
    // A 500 on the initial POST means no run exists; with no session_started this stream, the
    // reattach guard (runStartedThisStream) is false, so it surfaces an error instead of reattaching.
    ctrl.sessionId = 'stale-prior'; // a stale id from a prior run must NOT trigger a wrong reattach
    const fetchSpy = mockFetchError(500, 'Internal Server Error');
    globalThis.fetch = fetchSpy;
    await ctrl.send('new message');
    expect(fetchSpy).toHaveBeenCalledTimes(1); // no reattach attempt
    expect(ctrl.conversation.filter((e) => e.type === 'error').length).toBe(1);
  });

  // ===== Tempdoc 834 §15.3: cross-tab reattach on load, from the backend's live-run enumeration =====
  it('reattaches on load to a live run ANOTHER tab started (found by the enumeration)', async () => {
    const fetchSpy = mockFetchDiscoverThenObserve(
      [liveRun('sess-x')],
      sseChunk('done', { finalResponse: 'the other tab run finished here' }),
    );
    globalThis.fetch = fetchSpy;

    await ctrl.reattachActiveRunOnLoad();

    // Discovery, then the attach onto the MANAGED observe route — a run this tab never started.
    const calls = (fetchSpy as unknown as ReturnType<typeof vi.fn>).mock.calls;
    expect(calls).toHaveLength(2);
    expect(calls[0]![0] as string).toContain('/api/chat/runs/live');
    expect(calls[1]![0] as string).toContain('/api/chat/runs/sess-x/observe');
    expect(ctrl.sessionId).toBe('sess-x');
  });

  it('does NOT reattach on load when this tab already owns/observes a run', async () => {
    ctrl.isStreaming = true; // this tab is already streaming its own run — must not steal/double-attach
    const fetchSpy = vi.fn();
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    await ctrl.reattachActiveRunOnLoad();
    // Not even the enumeration is asked: a tab that owns a run has nothing to discover.
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('does nothing on load when the enumeration reports no live run', async () => {
    const fetchSpy = mockFetchJson({ runs: [] });
    globalThis.fetch = fetchSpy;
    await ctrl.reattachActiveRunOnLoad();
    // The enumeration is asked exactly once, and NO observe stream follows it.
    expect((fetchSpy as unknown as ReturnType<typeof vi.fn>).mock.calls).toHaveLength(1);
    expect(ctrl.sessionId).toBeNull();
  });

  it('reattaches when the live run conversation matches this tab', async () => {
    ctrl.conversationId = 'conv-1';
    const fetchSpy = mockFetchDiscoverThenObserve(
      [liveRun('sess-m', 'conv-1')],
      sseChunk('done', { finalResponse: 'ok' }),
    );
    globalThis.fetch = fetchSpy;
    await ctrl.reattachActiveRunOnLoad();
    const calls = (fetchSpy as unknown as ReturnType<typeof vi.fn>).mock.calls;
    expect(calls).toHaveLength(2);
    expect(calls[1]![0] as string).toContain('/api/chat/runs/sess-m/observe');
  });

  it('does NOT reattach when the live run belongs to a DIFFERENT conversation', async () => {
    ctrl.conversationId = 'conv-B'; // this tab is pinned to a different conversation
    const fetchSpy = mockFetchDiscoverThenObserve(
      [liveRun('sess-a', 'conv-A')],
      sseChunk('done', { finalResponse: 'must not be reached' }),
    );
    globalThis.fetch = fetchSpy;
    await ctrl.reattachActiveRunOnLoad();
    // The enumeration ran; the conversation guard refused its one row, so no observe stream opened.
    const calls = (fetchSpy as unknown as ReturnType<typeof vi.fn>).mock.calls;
    expect(calls).toHaveLength(1);
    expect(ctrl.sessionId).toBeNull();
  });

  it('does NOT adopt a live run of another SHAPE (a workflow run is not reattachable here)', async () => {
    const fetchSpy = mockFetchDiscoverThenObserve(
      [liveRun('wf-1', null, 'core.workflow-run')],
      sseChunk('done', { finalResponse: 'must not be reached' }),
    );
    globalThis.fetch = fetchSpy;
    await ctrl.reattachActiveRunOnLoad();
    expect((fetchSpy as unknown as ReturnType<typeof vi.fn>).mock.calls).toHaveLength(1);
    expect(ctrl.sessionId).toBeNull();
  });

  it('takes the NEWEST agent run — the enumeration is already ordered, so the first row wins', async () => {
    const fetchSpy = mockFetchDiscoverThenObserve(
      [liveRun('newest'), liveRun('older')],
      sseChunk('done', { finalResponse: 'ok' }),
    );
    globalThis.fetch = fetchSpy;
    await ctrl.reattachActiveRunOnLoad();
    const calls = (fetchSpy as unknown as ReturnType<typeof vi.fn>).mock.calls;
    expect(calls[1]![0] as string).toContain('/api/chat/runs/newest/observe');
  });

  it('degrades to "no live run" when the enumeration fails — discovery never throws into mount', async () => {
    const fetchSpy = mockFetchError(500, 'boom');
    globalThis.fetch = fetchSpy;
    await expect(ctrl.reattachActiveRunOnLoad()).resolves.toBeUndefined();
    expect((fetchSpy as unknown as ReturnType<typeof vi.fn>).mock.calls).toHaveLength(1);
    expect(ctrl.sessionId).toBeNull();
  });

  it('falls back quietly to the record when the observed run answers the typed 404', async () => {
    const fetchSpy = vi.fn((url: string) =>
      String(url).includes('/api/chat/runs/live')
        ? Promise.resolve(
            new Response(JSON.stringify({ runs: [liveRun('sess-gone')] }), {
              status: 200,
              headers: { 'content-type': 'application/json' },
            }),
          )
        : Promise.resolve(new Response(runNotFoundBody('sess-gone'), { status: 404 })),
    );
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    await ctrl.reattachActiveRunOnLoad();
    // The retired run is NOT an error the reader sees — it is the record fallback (onAttachNotLive).
    expect(ctrl.conversation.filter((e) => e.type === 'error')).toHaveLength(0);
    expect(ctrl.isStreaming).toBe(false);
    expect(ctrl.runKind).toBeNull();
  });

  // ===== approveCall / rejectCall =====
  it('approveCall sends POST with sessionId and callId', async () => {
    ctrl.sessionId = 'sess-a';
    const fetchSpy = mockFetchJson({});
    globalThis.fetch = fetchSpy;
    await ctrl.approveCall('call-1');
    expect(fetchSpy).toHaveBeenCalled();
    const callArgs = (fetchSpy as ReturnType<typeof vi.fn>).mock.calls[0]!;
    expect(callArgs[0]).toBe('http://test/api/chat/approve');
    const body = JSON.parse(callArgs[1].body);
    expect(body.sessionId).toBe('sess-a');
    expect(body.callId).toBe('call-1');
  });

  it('approveCall is a no-op when sessionId is null', async () => {
    ctrl.sessionId = null;
    const fetchSpy = vi.fn() as unknown as typeof fetch;
    globalThis.fetch = fetchSpy;
    await ctrl.approveCall('call-1');
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('rejectCall sends POST with sessionId, callId, and reason', async () => {
    ctrl.sessionId = 'sess-b';
    const fetchSpy = mockFetchJson({});
    globalThis.fetch = fetchSpy;
    await ctrl.rejectCall('call-2', 'not now');
    const callArgs = (fetchSpy as ReturnType<typeof vi.fn>).mock.calls[0]!;
    expect(callArgs[0]).toBe('http://test/api/chat/reject');
    const body = JSON.parse(callArgs[1].body);
    expect(body.reason).toBe('not now');
  });

  // Tempdoc 565 §30 — the DIRECTION authority's interject.
  it('steer sends POST to /api/chat/agent/steer with sessionId and text', async () => {
    ctrl.sessionId = 'sess-steer';
    const fetchSpy = mockFetchJson({ status: 'injected' });
    globalThis.fetch = fetchSpy;
    const ok = await ctrl.steer('  Focus only on Q3.  ');
    expect(ok).toBe(true);
    const callArgs = (fetchSpy as ReturnType<typeof vi.fn>).mock.calls[0]!;
    expect(callArgs[0]).toBe('http://test/api/chat/agent/steer');
    const body = JSON.parse(callArgs[1].body);
    expect(body.sessionId).toBe('sess-steer');
    expect(body.text).toBe('Focus only on Q3.'); // trimmed
    // §33 — a SUCCESSFUL steer must NOT append a failure note (the §33 note is for the 404 path only;
    // this pins "passes for the right reason" so a future regression can't make every steer noisy).
    expect(
      ctrl.conversation.filter(
        (e) => e.type === 'progress' && /could not steer/i.test(String(e.content ?? '')),
      ),
    ).toHaveLength(0);
  });

  it('steer is a no-op (returns false, no fetch) when there is no live session', async () => {
    ctrl.sessionId = null;
    const fetchSpy = vi.fn() as unknown as typeof fetch;
    globalThis.fetch = fetchSpy;
    expect(await ctrl.steer('go')).toBe(false);
    expect(await ctrl.steer('   ')).toBe(false); // blank
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('onDirectiveAcknowledged appends a human-origin steer-directive entry', () => {
    ctrl.onDirectiveAcknowledged({ directiveText: 'Focus only on Q3.' });
    const steer = ctrl.conversation.filter((e) => e.type === 'steer-directive');
    expect(steer.length).toBe(1);
    expect(steer[0]?.content).toBe('Focus only on Q3.');
    // a blank/absent text is ignored (no phantom entry)
    ctrl.onDirectiveAcknowledged({});
    expect(ctrl.conversation.filter((e) => e.type === 'steer-directive').length).toBe(1);
  });

  // Tempdoc 565 §33 — a failed steer (404: the run finished) must surface a note, not be silent.
  it('steer surfaces a system note when the POST fails (404)', async () => {
    ctrl.sessionId = 'gone';
    globalThis.fetch = mockFetchJson({ error: 'session not found' }, 404);
    const ok = await ctrl.steer('focus on Q3');
    expect(ok).toBe(false);
    const notes = ctrl.conversation.filter(
      (e) => e.type === 'progress' && /could not steer/i.test(e.content),
    );
    expect(notes.length).toBe(1);
    expect(notes[0]?.severity).toBe('warn');
  });

  // Tempdoc 565 §33 — runKind: only an `agent` run is steerable; the view gates the steer input on it.
  it('runKind: defaults null, set on start, cleared on terminal', () => {
    expect(ctrl.runKind).toBeNull();
    globalThis.fetch = mockFetchSse('event: done\ndata: {}\n\n');
    void ctrl.send('hi'); // set synchronously before the stream await
    expect(ctrl.runKind).toBe('agent');
    ctrl.onDone({ finalResponse: 'x', iterationsUsed: 1, toolCallsExecuted: 0, totalTokensUsed: 0 });
    expect(ctrl.runKind).toBeNull();
  });

  it('runKind: runWorkflow marks the run NOT steerable (workflow)', () => {
    globalThis.fetch = mockFetchSse('event: done\ndata: {}\n\n');
    void ctrl.runWorkflow('core.demo-compose');
    expect(ctrl.runKind).toBe('workflow');
    ctrl.onError({ error: 'boom' });
    expect(ctrl.runKind).toBeNull();
  });

  // Fix B (issue 2 robustness) — a non-ok approve/reject POST must SURFACE an error, not be swallowed
  // (a stale backend without the §15.J unified route 404s, otherwise leaving the card stuck PENDING).
  it('approveCall surfaces an error when the POST is not ok (no silent stuck-PENDING)', async () => {
    ctrl.sessionId = 'sess-x';
    globalThis.fetch = mockFetchJson({}, 404);
    await ctrl.approveCall('call-1');
    const errors = ctrl.conversation.filter(e => e.type === 'error');
    expect(errors.length).toBe(1);
    expect(errors[0]?.content).toMatch(/approval/i);
  });

  it('rejectCall surfaces an error when the POST is not ok', async () => {
    ctrl.sessionId = 'sess-x';
    globalThis.fetch = mockFetchJson({}, 500);
    await ctrl.rejectCall('call-1', 'no');
    const errors = ctrl.conversation.filter(e => e.type === 'error');
    expect(errors.length).toBe(1);
    expect(errors[0]?.content).toMatch(/rejection/i);
  });

  // Fix A (issue 1) — the single-window chat agent must issue a SINGLE-agent run request: an empty
  // agentProfiles + no initialAgentId, so the backend cannot treat it as a handed-off sub-agent and
  // force `core_ingest_files` via the E0a multi-agent policy. A lone non-primary profile was the bug.
  it('send issues a single-agent run request (empty agentProfiles, no initialAgentId)', async () => {
    const fetchSpy = mockFetchSse('');
    globalThis.fetch = fetchSpy;
    await ctrl.send('hello');
    const call = (fetchSpy as ReturnType<typeof vi.fn>).mock.calls.find((c) =>
      String(c[0]).includes('/api/chat/agent'),
    );
    expect(call).toBeTruthy();
    const body = JSON.parse((call as unknown as [string, { body: string }])[1].body);
    expect(body.agentProfiles).toEqual([]);
    expect(body.initialAgentId).toBeUndefined();
  });

  // ===== cancelSession =====
  it('cancelSession commits streaming text and sends DELETE for session', async () => {
    ctrl.sessionId = 'sess-c';
    ctrl.isStreaming = true;
    ctrl.streamingText = 'partial';
    const fetchSpy = mockFetchJson({});
    globalThis.fetch = fetchSpy;
    await ctrl.cancelSession();
    expect(ctrl.isStreaming).toBe(false);
    const committed = ctrl.conversation.find(e => e.type === 'assistant-text');
    expect(committed?.content).toBe('partial');
    expect(fetchSpy).toHaveBeenCalled();
    const callArgs = (fetchSpy as ReturnType<typeof vi.fn>).mock.calls[0]!;
    expect(callArgs[0]).toContain('/api/chat/sessions/sess-c');
    expect(callArgs[1].method).toBe('DELETE');
  });

  // ===== loadSessions / loadHistory =====
  it('loadSessions populates sessions array', async () => {
    globalThis.fetch = mockFetchJson({ sessions: [{ sessionId: 's1' }, { sessionId: 's2' }] });
    await ctrl.loadSessions();
    expect(ctrl.sessions.length).toBe(2);
    expect(ctrl.sessions[0]?.sessionId).toBe('s1');
  });

  // Tempdoc 821 §4 — the backend (`AgentSessionSummary.java` / the generated
  // `agent-sessions-response.ts` wire schema) emits `startedAt` (ISO-8601 string) and `state`, not
  // `startedAtEpochMs`/`status`. loadSessions used to cast the raw JSON straight through, so every
  // row's `startedAtEpochMs` was undefined and RetrospectivePanel rendered a blank timestamp for
  // every session. This is the realistic backend payload shape (mirrors AgentRunStore.toSessionSummary).
  it('loadSessions maps the real backend field names (startedAt/state) onto startedAtEpochMs/status', async () => {
    const startedAtIso = '2026-08-12T09:30:00.000Z';
    globalThis.fetch = mockFetchJson({
      sessions: [
        {
          sessionId: 's1',
          startedAt: startedAtIso,
          updatedAt: startedAtIso,
          state: 'READY_FOR_LLM',
          resumable: true,
          iterationsUsed: 2,
          toolCallsExecuted: 1,
          totalTokensUsed: 100,
          activeAgentId: 'primary',
          terminationReason: null,
          preview: 'summarize this doc',
          // Tempdoc 834 §5.3 — S2 ships this on the wire; the FE dropped it until S5.
          interruptedAt: '2026-08-12T09:31:00.000Z',
        },
      ],
    });
    await ctrl.loadSessions();
    expect(ctrl.sessions.length).toBe(1);
    const s = ctrl.sessions[0]!;
    expect(s.sessionId).toBe('s1');
    expect(s.interruptedAt).toBe('2026-08-12T09:31:00.000Z');
    expect(s.startedAtEpochMs).toBe(Date.parse(startedAtIso));
    expect(s.status).toBe('READY_FOR_LLM');
    expect(s.preview).toBe('summarize this doc');
    expect(s.resumable).toBe(true);
  });

  // Precision guard: a payload that already speaks the FE shape (startedAtEpochMs/status) must
  // still round-trip unchanged — the normalizer tolerates both shapes, it doesn't only accept the
  // backend one.
  it('loadSessions tolerates a payload already in the startedAtEpochMs/status shape', async () => {
    const epochMs = 1_723_000_000_000;
    globalThis.fetch = mockFetchJson({
      sessions: [{ sessionId: 's1', startedAtEpochMs: epochMs, status: 'done' }],
    });
    await ctrl.loadSessions();
    expect(ctrl.sessions[0]?.startedAtEpochMs).toBe(epochMs);
    expect(ctrl.sessions[0]?.status).toBe('done');
  });

  // Tempdoc 561 P-B1: History is a projection of the ONE action ledger, filtered to this session
  // via the cross-domain correlationId join key — not the old FileOperationLog-backed
  // /api/chat/agent/history (which is exactly why a completed search left History empty).
  it('loadHistory projects agent operation rows from the one action ledger, filtered to the session', async () => {
    ctrl.sessionId = 'sess-xyz';
    let requestedUrl = '';
    globalThis.fetch = (async (url: string) => {
      requestedUrl = String(url);
      return {
        ok: true,
        json: async () => ({
          entries: [
            {
              id: 'operation:t1:core.search-index:SUCCESS',
              kind: 'operation',
              occurredAt: '2026-05-30T00:00:01Z',
              originator: 'agent',
              operationId: 'core.search-index',
              outcome: 'SUCCESS',
              correlationId: 'sess-xyz',
            },
            // a non-operation row in the same session must be excluded from the tool-call History
            {
              id: 'gate:t2:core.ingest:GATED',
              kind: 'gate',
              occurredAt: '2026-05-30T00:00:02Z',
              originator: 'agent',
              correlationId: 'sess-xyz',
            },
          ],
        }),
      } as Response;
    }) as typeof fetch;

    await ctrl.loadHistory();

    // requests the session-scoped, agent-only projection of the one ledger
    expect(requestedUrl).toContain('/api/action-ledger');
    expect(requestedUrl).toContain('originator=agent');
    expect(requestedUrl).toContain('correlationId=sess-xyz');
    // only the operation row maps; the gate row is excluded
    expect(ctrl.history.length).toBe(1);
    // tempdoc 558 §S1 — History now projects through the ONE shared projection (UnifiedActionEntry):
    // the outcome is a structured field and the operation id is humanized into the label (no raw id).
    expect(ctrl.history[0]?.id).toBe('operation:t1:core.search-index:SUCCESS');
    expect(ctrl.history[0]?.outcome).toBe('SUCCESS');
    expect(ctrl.history[0]?.label).toContain('Search Index');
    expect(ctrl.history[0]?.label).not.toContain('core.search-index');
  });

  // Tempdoc 561 P-B2: Timeline is a DISTINCT projection of the one ledger (the workspace activity
  // stream), not a copy of the Sessions roster.
  it('loadTimeline projects the workspace activity stream from the one ledger', async () => {
    let requestedUrl = '';
    globalThis.fetch = (async (url: string) => {
      requestedUrl = String(url);
      return {
        ok: true,
        json: async () => ({
          entries: [
            {
              id: 'operation:t1:core.search-index:SUCCESS',
              kind: 'operation',
              occurredAt: '2026-05-30T00:00:01Z',
              originator: 'agent',
              operationId: 'core.search-index',
              outcome: 'SUCCESS',
            },
            {
              id: 'index:t2:default:DONE',
              kind: 'index',
              occurredAt: '2026-05-30T00:00:02Z',
              originator: 'system',
              collection: 'default',
              state: 'DONE',
            },
          ],
        }),
      } as Response;
    }) as typeof fetch;

    await ctrl.loadTimeline();

    expect(requestedUrl).toContain('/api/action-ledger');
    // both ledger kinds appear in the activity stream (distinct from the Sessions roster)
    expect(ctrl.timeline.length).toBe(2);
    expect(ctrl.timeline.map(e => e.kind)).toEqual(expect.arrayContaining(['operation', 'index']));
    expect(ctrl.timeline.every(e => typeof e.label === 'string' && e.label.length > 0)).toBe(true);
  });

  it('loadHistory is empty (no fetch) when there is no active session', async () => {
    ctrl.sessionId = null;
    let fetched = false;
    globalThis.fetch = (async () => {
      fetched = true;
      return { ok: true, json: async () => ({ entries: [] }) } as Response;
    }) as typeof fetch;
    await ctrl.loadHistory();
    expect(fetched).toBe(false);
    expect(ctrl.history.length).toBe(0);
  });

  // ===== resumeSession =====
  it('resumeSession consumes SSE and sets session state', async () => {
    const body =
      sseChunk('session_started', { sessionId: 'sess-resumed' }) +
      sseChunk('chunk', { text: 'Resumed text' }) +
      sseChunk('done', { finalResponse: 'Resumed text', iterationsUsed: 2, toolCallsExecuted: 0 });
    globalThis.fetch = mockFetchSse(body);
    await ctrl.resumeSession('sess-old');
    expect(ctrl.sessionId).toBe('sess-resumed');
    expect(ctrl.isStreaming).toBe(false);
  });

  it('resumeSession appends error on non-200 HTTP', async () => {
    globalThis.fetch = mockFetchError(404);
    await ctrl.resumeSession('sess-gone');
    const errors = ctrl.conversation.filter(e => e.type === 'error');
    expect(errors.length).toBe(1);
    expect(ctrl.isStreaming).toBe(false);
  });

  // ===== notify dedup =====
  it('notify batches multiple synchronous mutations into one onUpdate call', async () => {
    ctrl.onChunk({ text: 'a' });
    ctrl.onChunk({ text: 'b' });
    ctrl.onChunk({ text: 'c' });
    expect(notifyCount).toBe(0);
    await new Promise(r => setTimeout(r, 0));
    expect(notifyCount).toBe(1);
  });

  // ===== lifecycle =====
  it('destroy stops polling and aborts active stream', () => {
    ctrl.startPolling();
    ctrl.destroy();
    ctrl.destroy();
  });

  // ===== fork =====
  it('fork returns a shallow copy of conversation up to fromIndex', () => {
    ctrl.onChunk({ text: 'hello' });
    ctrl.onDone({ finalResponse: 'hello', iterationsUsed: 1, toolCallsExecuted: 0, totalTokensUsed: 0 });
    ctrl.conversation = [
      ...ctrl.conversation,
      { id: 'u1', type: 'user' as const, content: 'msg1', timestamp: 1 },
      { id: 'a1', type: 'assistant-text' as const, content: 'resp1', timestamp: 2 },
      { id: 'u2', type: 'user' as const, content: 'msg2', timestamp: 3 },
      { id: 'a2', type: 'assistant-text' as const, content: 'resp2', timestamp: 4 },
    ];
    const forked = ctrl.fork(1);
    expect(forked.length).toBe(2);
    expect(forked[0]?.content).toBe(ctrl.conversation[0]?.content);
    expect(forked[1]?.content).toBe(ctrl.conversation[1]?.content);
  });

  it('fork returns empty array when fromIndex is -1', () => {
    ctrl.conversation = [{ id: 'u1', type: 'user' as const, content: 'msg', timestamp: 1 }];
    const forked = ctrl.fork(-1);
    expect(forked.length).toBe(0);
  });

  it('fork returns full conversation when fromIndex exceeds length', () => {
    ctrl.conversation = [{ id: 'u1', type: 'user' as const, content: 'msg', timestamp: 1 }];
    const forked = ctrl.fork(100);
    expect(forked.length).toBe(1);
  });

  // ===== loadForkedConversation =====
  it('loadForkedConversation resets state and retains referenced toolCalls', () => {
    ctrl.toolCalls = {
      'c1': { callId: 'c1', toolName: 'search', arguments: '{}', risk: 'LOW', status: 'completed' },
      'c2': { callId: 'c2', toolName: 'browse', arguments: '{}', risk: 'LOW', status: 'completed' },
    };
    const entries = [
      { id: 'u1', type: 'user' as const, content: 'msg', timestamp: 1, callIds: undefined },
      { id: 'g1', type: 'tool-call-group' as const, content: '', callIds: ['c1'], timestamp: 2 },
    ];
    ctrl.loadForkedConversation(entries);
    expect(ctrl.conversation.length).toBe(2);
    expect(ctrl.toolCalls['c1']).toBeDefined();
    expect(ctrl.toolCalls['c2']).toBeUndefined();
    expect(ctrl.sessionId).toBeNull();
    expect(ctrl.isStreaming).toBe(false);
  });

  // ===== auto-approval driven by the autonomy dial (§32 unify) =====
  it('backend AUTO verdict: queues callId when sessionId is null, flushes (approves) on session_started', async () => {
    // Tempdoc 561 P-D collapse: the FE OBEYS the backend gateBehavior. The backend decided AUTO
    // (e.g. a read-only call under assist) — the FE auto-approves; it no longer re-derives from risk.
    const fetchSpy = mockFetchLiveApprovals(ctrl);
    globalThis.fetch = fetchSpy;

    ctrl.onToolCallPending({
      callId: 'auto1', toolName: 't', arguments: '{}', risk: 'LOW', gateBehavior: 'auto',
    });
    // sessionId is null → should NOT have called fetch (approveCall returns early; callId queued)
    expect((fetchSpy as ReturnType<typeof vi.fn>).mock.calls.length).toBe(0);

    // Now session starts → should flush the queue
    ctrl.onSessionStarted({ sessionId: 'sess-x' });
    await new Promise(r => setTimeout(r, 10));
    expect((fetchSpy as ReturnType<typeof vi.fn>).mock.calls.length).toBe(1);
    const callArgs = (fetchSpy as ReturnType<typeof vi.fn>).mock.calls[0]!;
    expect(callArgs[0]).toContain('/approve');
  });

  it('the backend verdict is the SOLE auto-approval authority: AUTO approves, non-AUTO does not', async () => {
    ctrl.sessionId = 'sess-z';
    const fetchSpy = mockFetchLiveApprovals(ctrl);
    globalThis.fetch = fetchSpy;
    // A typed_confirm call routes to the ceremony; deny it deterministically.
    setAuthorizationPresenter(async () => ({ approved: false, allowAlways: false }));
    const approveCount = () =>
      (fetchSpy as ReturnType<typeof vi.fn>).mock.calls.filter(c =>
        String(c[0]).includes('/approve'),
      ).length;

    // Backend AUTO (e.g. the auto dial trusting the agent on a MEDIUM write) → the FE obeys + approves.
    ctrl.onToolCallPending({
      callId: 'ok-m', toolName: 't', arguments: '{}', risk: 'MEDIUM', gateBehavior: 'auto',
    });
    await new Promise(r => setTimeout(r, 10));
    expect(approveCount()).toBe(1);

    // Backend typed_confirm → NOT auto-clicked (routes to the ceremony) — the FE never auto-approves
    // without an explicit backend AUTO (the collapsed second authority is gone).
    ctrl.onToolCallPending({
      callId: 'blk-m', toolName: 't', arguments: '{}', risk: 'MEDIUM', gateBehavior: 'typed_confirm',
    });
    await new Promise(r => setTimeout(r, 10));
    expect(approveCount()).toBe(1); // still 1 — the typed_confirm call did not auto-approve
    expect(ctrl.toolCalls['blk-m']?.gateBehavior).toBe('typed_confirm');
  });

  it('assist + LOW: calls approveCall immediately when sessionId is set', async () => {
    setAutonomyLevel('assist');
    ctrl.sessionId = 'sess-y';
    const fetchSpy = mockFetchLiveApprovals(ctrl);
    globalThis.fetch = fetchSpy;

    ctrl.onToolCallPending({
      callId: 'auto2', toolName: 't', arguments: '{}', risk: 'LOW', gateBehavior: 'auto',
    });
    await new Promise(r => setTimeout(r, 10));
    expect((fetchSpy as ReturnType<typeof vi.fn>).mock.calls.filter(c =>
      String(c[0]).endsWith('/api/chat/approve'))).toHaveLength(1);
  });

  it('assist: does NOT auto-approve MEDIUM or HIGH', async () => {
    setAutonomyLevel('assist');
    ctrl.sessionId = 'sess-z';
    const fetchSpy = mockFetchLiveApprovals(ctrl);
    globalThis.fetch = fetchSpy;

    ctrl.onToolCallPending({ callId: 'med', toolName: 't', arguments: '{}', risk: 'MEDIUM', gateBehavior: 'inline_confirm' });
    ctrl.onToolCallPending({ callId: 'high', toolName: 't', arguments: '{}', risk: 'HIGH', gateBehavior: 'typed_confirm' });
    await new Promise(r => setTimeout(r, 0));
    expect((fetchSpy as ReturnType<typeof vi.fn>).mock.calls.filter(c =>
      String(c[0]).endsWith('/api/chat/approve'))).toHaveLength(0);
  });

  it('backend AUTO: auto-approves MEDIUM directly; backend typed_confirm HIGH routes to the ceremony host', async () => {
    ctrl.sessionId = 'sess-a';
    const fetchSpy = mockFetchLiveApprovals(ctrl);
    globalThis.fetch = fetchSpy;
    const prompts: Array<{ operationId: string; gateBehavior: string }> = [];
    // Presenter denies in-test (so a routed HIGH call resolves deterministically to reject).
    setAuthorizationPresenter(async (p) => {
      prompts.push(p);
      return { approved: false, allowAlways: false };
    });

    // Tempdoc 561 P-D: the backend issued AUTO (the auto dial trusting the agent) → the FE obeys.
    ctrl.onToolCallPending({ callId: 'med2', toolName: 't', arguments: '{}', risk: 'MEDIUM', gateBehavior: 'auto' });
    await new Promise(r => setTimeout(r, 10));
    // MEDIUM auto-approved directly — no ceremony, one approve fetch.
    expect((fetchSpy as ReturnType<typeof vi.fn>).mock.calls.length).toBe(1);
    expect(prompts.length).toBe(0);

    // HIGH is never AUTO (the backend safety floor) → routes through the unified ceremony host.
    ctrl.onToolCallPending({ callId: 'high2', toolName: 't', arguments: '{}', risk: 'HIGH', gateBehavior: 'typed_confirm' });
    await new Promise(r => setTimeout(r, 10));
    expect(prompts.length).toBe(1);
    expect(prompts[0]!.operationId).toBe('t');
    expect(prompts[0]!.gateBehavior).toBe('TYPED_CONFIRM');
  });

  it('watch: auto-approves NOTHING — routes even LOW through the ceremony host', async () => {
    setAutonomyLevel('watch'); // watch → manual approval for everything
    ctrl.sessionId = 'sess-b';
    const fetchSpy = mockFetchLiveApprovals(ctrl);
    globalThis.fetch = fetchSpy;
    const prompts: Array<{ gateBehavior: string }> = [];
    setAuthorizationPresenter(async (p) => {
      prompts.push(p);
      return { approved: false, allowAlways: false };
    });

    ctrl.onToolCallPending({ callId: 'low3', toolName: 't', arguments: '{}', risk: 'LOW', gateBehavior: 'inline_confirm' });
    await new Promise(r => setTimeout(r, 10));
    // Not auto-approved → routed to the ceremony (INLINE for LOW), not silently fired.
    expect(prompts.length).toBe(1);
    expect(prompts[0]!.gateBehavior).toBe('INLINE_CONFIRM');
  });

  it('ceremony APPROVAL drives the unified approve endpoint (tempdoc 550 C3 / 565 §15.C)', async () => {
    setAutonomyLevel('watch'); // everything manual → routes to the ceremony
    ctrl.sessionId = 'sess-c';
    const fetchSpy = mockFetchLiveApprovals(ctrl);
    globalThis.fetch = fetchSpy;
    setAuthorizationPresenter(async () => ({ approved: true, allowAlways: false })); // user approves

    ctrl.onToolCallPending({ callId: 'c-ok', toolName: 't', arguments: '{}', risk: 'MEDIUM' });
    await new Promise(r => setTimeout(r, 10));

    const approveCalls = (fetchSpy as ReturnType<typeof vi.fn>).mock.calls.filter(c =>
      String(c[0]).endsWith('/api/chat/approve'),
    );
    expect(approveCalls.length).toBe(1);
    expect(JSON.parse(approveCalls[0]![1]!.body as string)).toMatchObject({ callId: 'c-ok' });
  });
});

describe('private live tool approval display', () => {
  const pending = { callId: 'private-call', toolName: 'event-alias', arguments: 'PRIVATE_INPUT',
    risk: 'LOW', gateBehavior: 'inline_confirm' };
  const detail = { callId: 'private-call', operationId: 'core.frozen-target', argsSummary: 'Frozen server target',
    riskTier: 'HIGH', gateBehavior: 'TYPED_CONFIRM' };

  it('uses the complete frozen server display and verdict before approving the owning run', async () => {
    ctrl.sessionId = 'private-run';
    const frozen = 'C:/' + 'segment/'.repeat(900) + 'target.md';
    const fetchSpy = mockFetchLiveApprovals(ctrl, { ...detail, argsSummary: frozen });
    globalThis.fetch = fetchSpy;
    const presenter = vi.fn(async (prompt: { operationId: string; argsSummary?: string; gateBehavior: string }) => {
      expect(prompt.argsSummary).toBe(frozen);
      return { approved: true, allowAlways: false };
    });
    setAuthorizationPresenter(presenter);
    ctrl.onToolCallPending(pending);
    await vi.waitFor(() => expect(presenter).toHaveBeenCalledTimes(1));
    const prompt = presenter.mock.calls[0]![0];
    expect(prompt.operationId).toBe('core.frozen-target');
    expect(prompt.gateBehavior).toBe('TYPED_CONFIRM');
    expect(JSON.stringify(prompt)).not.toContain('PRIVATE_INPUT');
    const requests = (fetchSpy as ReturnType<typeof vi.fn>).mock.calls;
    expect(String(requests[0]![0])).toContain('/api/chat/approval?sessionId=private-run&callId=private-call');
    await vi.waitFor(() => expect(requests.filter(c => String(c[0]).endsWith('/api/chat/approve'))).toHaveLength(1));
    const approval = requests.find(c => String(c[0]).endsWith('/api/chat/approve'))!;
    expect(JSON.parse(approval[1].body)).toEqual({ sessionId: 'private-run', callId: 'private-call' });
  });

  it.each([404, 500, 200, 0])('refuses unavailable or malformed live details (status %s) without raw fallback', async (status) => {
    ctrl.sessionId = 'private-run';
    const presenter = vi.fn(async () => ({ approved: true, allowAlways: false }));
    setAuthorizationPresenter(presenter);
    const fetchSpy = vi.fn(async (url: string) => {
      if (url.includes('/api/chat/approval?') && status === 0) throw new Error('offline');
      return new Response('{}', { status: url.includes('/api/chat/approval?') ? status : 200 });
    });
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    ctrl.onToolCallPending(pending);
    await vi.waitFor(() => expect(fetchSpy.mock.calls.some(c => c[0].endsWith('/api/chat/reject'))).toBe(true));
    expect(presenter).not.toHaveBeenCalled();
    expect(fetchSpy.mock.calls.some(c => c[0].endsWith('/api/chat/approve'))).toBe(false);
    expect(emitEphemeralToast).toHaveBeenCalledWith(expect.objectContaining({
      message: 'Approval details are unavailable. The action was not approved.',
    }));
  });

  it.each(['done', 'error', 'replacement', 'approved', 'destroy'] as const)(
    'ignores a lookup that arrives after %s', async (transition) => {
      ctrl.sessionId = 'private-run';
      let resolveLookup!: (response: Response) => void;
      const lookup = new Promise<Response>(resolve => { resolveLookup = resolve; });
      const fetchSpy = vi.fn(() => lookup);
      globalThis.fetch = fetchSpy as unknown as typeof fetch;
      const presenter = vi.fn(async () => ({ approved: true, allowAlways: false }));
      setAuthorizationPresenter(presenter);
      ctrl.onToolCallPending(pending);
      if (transition === 'done') ctrl.onDone({ finalResponse: 'done', iterationsUsed: 1, toolCallsExecuted: 0, totalTokensUsed: 1 });
      if (transition === 'error') ctrl.onError({ error: 'run ended' });
      if (transition === 'replacement') ctrl.onSessionStarted({ sessionId: 'replacement-run' });
      if (transition === 'approved') ctrl.onToolCallApproved({ callId: pending.callId });
      if (transition === 'destroy') ctrl.destroy();
      resolveLookup(new Response(JSON.stringify(detail), { status: 200 }));
      await new Promise(resolve => setTimeout(resolve, 0));
      expect(presenter).not.toHaveBeenCalled();
      expect(fetchSpy).toHaveBeenCalledTimes(1);
    },
  );
});

// ==================== Tempdoc 585 §D Phase 2 (D3): shareable replay ====================

describe('loadReplayFromExport (585 §D Phase 2 — D3 shareable replay)', () => {
  it('replays an exported transcript through the same handlers and enters replay mode', () => {
    const ok = ctrl.loadReplayFromExport({
      meta: { sessionId: 'shared-1' },
      events: [
        { eventType: 'session_started', payload: { sessionId: 'shared-1' } },
        { eventType: 'chunk', payload: { text: 'Hello from a shared run' } },
        { eventType: 'done', payload: { finalResponse: 'Hello from a shared run', iterationsUsed: 1, toolCallsExecuted: 0, totalTokensUsed: 0 } },
      ],
    });
    expect(ok).toBe(true);
    expect(ctrl.replayMode).toBe(true);
    expect(ctrl.sessionId).toBe('shared-1');
    // The exported events drove the SAME projection the live/C1 paths use.
    const text = ctrl.conversation.map((e) => e.content).join(' ');
    expect(text).toContain('Hello from a shared run');
  });

  it('returns false (and does not enter replay) for a transcript with no usable events', () => {
    expect(ctrl.loadReplayFromExport({ meta: {}, events: [] })).toBe(false);
    expect(ctrl.loadReplayFromExport({ junk: true })).toBe(false);
    expect(ctrl.replayMode).toBe(false);
  });
});

// ==================== Tempdoc 605 — single-live-run ceremony invariant ====================

describe('605 — run identity + conclude-drain', () => {
  it('approveCall routes to the explicit owner run, not a later/stale this.sessionId (M2)', async () => {
    globalThis.fetch = mockFetchJson({ ok: true });
    ctrl.sessionId = 'run-2-current'; // a LATER run is now current
    await ctrl.approveCall('call-x', 'run-1-owner'); // approve a ceremony OWNED by the earlier run
    const body = JSON.parse((globalThis.fetch as unknown as { mock: { calls: [string, { body: string }][] } }).mock.calls[0]![1].body);
    expect(body.sessionId).toBe('run-1-owner');
    expect(body.callId).toBe('call-x');
  });

  it('rejectCall routes to the explicit owner run too', async () => {
    globalThis.fetch = mockFetchJson({ ok: true });
    ctrl.sessionId = 'run-2-current';
    await ctrl.rejectCall('call-y', 'denied', 'run-1-owner');
    const body = JSON.parse((globalThis.fetch as unknown as { mock: { calls: [string, { body: string }][] } }).mock.calls[0]![1].body);
    expect(body.sessionId).toBe('run-1-owner');
  });

  it('onError (the abnormal terminal — the M1 trigger) drains the run and surfaces ONE notice when ≥1 was denied', () => {
    vi.mocked(emitEphemeralToast).mockClear();
    const drained: string[] = [];
    setAuthorizationCanceller((runId) => { drained.push(runId); return 2; });
    ctrl.sessionId = 'run-err';
    ctrl.onError({ error: 'stream dropped' });
    expect(drained).toEqual(['run-err']);
    expect(emitEphemeralToast).toHaveBeenCalledTimes(1);
    setAuthorizationCanceller(null);
  });

  it('cancelSession drains the halted run', async () => {
    vi.mocked(emitEphemeralToast).mockClear();
    globalThis.fetch = mockFetchJson({ ok: true }); // the DELETE
    const drained: string[] = [];
    setAuthorizationCanceller((runId) => { drained.push(runId); return 1; });
    ctrl.sessionId = 'run-halt';
    await ctrl.cancelSession();
    expect(drained).toEqual(['run-halt']);
    expect(emitEphemeralToast).toHaveBeenCalledTimes(1);
    setAuthorizationCanceller(null);
  });

  it('a terminal with no open ceremony (0 denied) surfaces NO notice', () => {
    vi.mocked(emitEphemeralToast).mockClear();
    setAuthorizationCanceller(() => 0);
    ctrl.sessionId = 'run-clean';
    ctrl.onError({ error: 'x' });
    expect(emitEphemeralToast).not.toHaveBeenCalled();
    setAuthorizationCanceller(null);
  });

  it('a REPLAY terminal does NOT drain (replay is not a live run conclusion)', () => {
    const drained: string[] = [];
    setAuthorizationCanceller((runId) => { drained.push(runId); return 1; });
    // loadReplayFromExport runs session_started + chunk + done IN REPLAY MODE.
    ctrl.loadReplayFromExport({
      meta: { sessionId: 'replay-1' },
      events: [
        { eventType: 'session_started', payload: { sessionId: 'replay-1' } },
        { eventType: 'done', payload: { finalResponse: 'r', iterationsUsed: 1, toolCallsExecuted: 0, totalTokensUsed: 0 } },
      ],
    });
    expect(ctrl.replayMode).toBe(true);
    expect(drained).toEqual([]); // the replay done() never drained a real ceremony
    setAuthorizationCanceller(null);
  });
});

/**
 * Tempdoc 859 §A §1.2 — THE reasoning-region boundary, at the ONE dispatch chokepoint.
 *
 * Driven through `loadReplayFromExport`, which is the one SYNCHRONOUS public path that routes a
 * list of event names through `dispatchEvent` — i.e. through the chokepoint itself. Calling the
 * handler methods directly (as the cases above do) would bypass the rule under test entirely and
 * pass against any implementation.
 */
describe('the reasoning-region boundary rule (859 §A)', () => {
  const drive = (
    events: ReadonlyArray<{ eventType: string; payload?: unknown }>,
  ): AgentSessionController => {
    ctrl.loadReplayFromExport({
      meta: { sessionId: 'run-859a' },
      events: events.map((e) => ({ eventType: e.eventType, payload: e.payload ?? {} })),
    });
    return ctrl;
  };
  const types = (): string[] => ctrl.conversation.map((e) => e.type);
  const think = (text: string) => ({ eventType: 'reasoning_chunk', payload: { text } });

  it('C-4: a tool boundary closes the region INTO the conversation, in order', () => {
    drive([think('I should search'), { eventType: 'tool_call_pending', payload: { callId: 'c1', toolName: 'core_search', risk: 'low' } }]);
    expect(types()).toEqual(['reasoning', 'tool-call-group']);
    expect(ctrl.conversation[0]?.content).toBe('I should search');
  });

  it('C-5: a text chunk does NOT close the region — one entry, with the prose ordered BEFORE it', () => {
    // `chunk` transparency, applied live for the first time: on a think-tag-leaking build the filter
    // reroutes inline <think> markup mid-stream, and cutting on bare contiguity would shatter one
    // LLM step into several blocks on one build family and not the other.
    drive([
      think('part one '),
      { eventType: 'chunk', payload: { text: 'visible prose' } },
      think('part two'),
      { eventType: 'tool_call_pending', payload: { callId: 'c1', toolName: 'grep', risk: 'low' } },
    ]);
    expect(types()).toEqual(['assistant-text', 'reasoning', 'tool-call-group']);
    expect(ctrl.conversation[1]?.content).toBe('part one part two');
  });

  it('C-6: the exempt set is EXACTLY these four names — every other event cuts', () => {
    // The register itself, pinned. Asserted against the PRODUCTION set, imported: a test that
    // retyped the four names would be a fork of the very register it guards, and a fifth name added
    // to production would pass silently — which is precisely the live/record cut-set drift this rule
    // exists to make impossible. Membership is exact, not a superset, so widening the exemption is a
    // decision that has to be made here too.
    expect([...REASONING_BOUNDARY_EXEMPT].sort()).toEqual([
      // Unmapped, and pure proof-of-life: it carries no run content at all.
      'heartbeat',
      // The three primers. They describe the run, or open it; none advances it, and all three arrive
      // before any reasoning could exist.
      'run_started',
      'session_started',
      'state_snapshot',
    ]);
  });

  it('C-6b: every dispatch name is classified — cut, transparent, or deliberately exempt', () => {
    // The vocabulary walk. "Which events cut a region" used to be an implicit consequence of where
    // `endThinking()` happened to be called, which is how the live cut set came to have exactly one
    // member while the record fold cut on every step. An unclassified name fails HERE.
    const unclassified = ctrl
      .eventNames()
      .filter(
        (name) =>
          name !== 'reasoning_chunk' && name !== 'chunk' && !REASONING_BOUNDARY_EXEMPT.has(name),
      );
    // Everything left is in the CUT set by construction — the rule has no per-handler list. Naming
    // the real steps keeps this from passing on an empty or accidentally-gutted dispatch map.
    expect(unclassified.length).toBeGreaterThan(0);
    for (const name of [
      'tool_call_pending',
      'tool_batch_proposed',
      'context_compacted',
      'done',
      'error',
      'progress',
      'budget_update',
      'handoff_proposed',
    ]) {
      expect(unclassified).toContain(name);
    }
    // Every exempt name is one the dispatcher actually knows — except `heartbeat`, which is exempt
    // BECAUSE it is unmapped, and would otherwise look like a typo nobody could see.
    const known = new Set(ctrl.eventNames());
    for (const name of REASONING_BOUNDARY_EXEMPT) {
      expect(name === 'heartbeat' || known.has(name)).toBe(true);
    }
    expect(known.has('heartbeat')).toBe(false);
  });

  it('C-7: budget_update CUTS the region — the highest-frequency real cut in a journal', () => {
    // `AgentLlmCaller` emits it the instant each LLM stream ends, so on a real run it is what cuts
    // nearly every region. It appends no entry of its own, so the reasoning entry is the ONLY thing
    // in the conversation after it.
    drive([think('a whole step of thinking'), { eventType: 'budget_update', payload: { phase: 'llm_response' } }]);
    expect(types()).toEqual(['reasoning']);
  });

  it('C-7b: slice D’s budget_raised progress note is a legitimate flush carrier', () => {
    drive([
      think('this will need more room'),
      { eventType: 'progress', payload: { phase: 'budget_raised', message: '+12,000 tokens — continuing' } },
    ]);
    expect(types()).toEqual(['reasoning', 'progress']);
    // Tempdoc 859 §D F6 — this is the semantic the RECORD side was made to match, so the assertion
    // now names what it must match: the note carries the AMOUNT (a raise without the number is not an
    // accountability record) and the typed phase the label authority projects from.
    expect(ctrl.conversation[1]?.content).toBe('+12,000 tokens — continuing');
    expect(ctrl.conversation[1]?.phase).toBe('budget_raised');
  });

  it('C-7c: the durable/ephemeral split is RECORD-side — live, EVERY progress phase carries', () => {
    // Tempdoc 859 §D F6 — the asymmetry that remains, stated rather than discovered. The classification
    // decides what OUTLIVES the run; it does not touch the live stream, where a spinner still cuts the
    // region and appends its own entry exactly as `budget_raised` does. This is why the record's
    // carrier set is a strict SUBSET of the live one, and why the difference is covered losslessly by
    // the fold's hold rule (`AgentInteractionMapperTest` M-2) rather than by dropping a block.
    drive([think('what next'), { eventType: 'progress', payload: { phase: 'llm_call', message: 'Calling LLM' } }]);
    expect(types()).toEqual(['reasoning', 'progress']);
    expect(ctrl.conversation[1]?.phase).toBe('llm_call');
  });

  it('C-8: an AUTO-RUN tool (exec_started with no prior pending) closes the region too', () => {
    drive([
      { eventType: 'tool_call_proposed', payload: { callId: 'c1', toolName: 'grep', risk: 'low' } },
      think('reading the output'),
      { eventType: 'tool_exec_started', payload: { callId: 'c1', toolName: 'grep' } },
    ]);
    expect(types()).toEqual(['reasoning', 'tool-call-group']);
  });

  it('C-9: handoff_proposed closes on the PRODUCING agent’s side of the boundary', () => {
    drive([
      think('the researcher should take this'),
      { eventType: 'handoff_proposed', payload: { fromAgentId: 'primary', toAgentId: 'researcher', reason: 'scope' } },
    ]);
    expect(types()).toEqual(['reasoning', 'handoff']);
  });

  it('C-10: onDone closes the open region BEFORE the terminal answer entry', () => {
    drive([think('one last check'), { eventType: 'done', payload: { finalResponse: 'the answer' } }]);
    expect(types()).toEqual(['reasoning', 'assistant-text']);
    expect(ctrl.conversation[1]?.content).toBe('the answer');
  });

  it('a mid-run cut that commits the prose does not make `done` render the answer twice', () => {
    // The interaction the cut rule introduces: `done`'s duplicate-answer guard used to read the LIVE
    // buffer, which survived until the terminal. A reasoning cut now commits that buffer mid-run, so
    // the guard reads what was committed as well — otherwise the whole answer lands a second time
    // directly under the first copy.
    drive([
      think('working it out'),
      { eventType: 'chunk', payload: { text: 'the answer' } },
      { eventType: 'budget_update', payload: { phase: 'llm_response' } },
      { eventType: 'done', payload: { finalResponse: 'the answer' } },
    ]);
    expect(types()).toEqual(['assistant-text', 'reasoning']);
    expect(ctrl.conversation.filter((e) => e.type === 'assistant-text')).toHaveLength(1);
  });

  const doneWith = (finalResponse: string): void => {
    ctrl.onDone({ finalResponse, iterationsUsed: 1, toolCallsExecuted: 0, totalTokensUsed: 0 });
  };

  it('F3: a NEW run does not inherit the previous run’s committed prose', () => {
    // `lastStreamedAnswer` is `onDone`'s duplicate-answer guard, and it is PER RUN. A run that ends
    // without `done` — an error, a halt, a transport drop — never reaches the read that clears it,
    // so a run-start that forgets to clear inherits it. The reachable failure is a re-roll that
    // streams no chunks and returns the SAME final response: its answer is suppressed entirely,
    // and the reader is left looking at the previous run's text believing it is the new one's.
    ctrl.streamingText = 'the answer';
    ctrl.commitStreamingText();
    ctrl.onError({ error: 'transport drop' });

    // The clear is synchronous, ahead of the stream — no network is needed to observe it.
    void ctrl.resumeSession('run-2');
    doneWith('the answer');

    const answers = ctrl.conversation.filter(
      (e) => e.type === 'assistant-text' && e.content === 'the answer',
    );
    expect(answers, 'the resumed run printed its own answer').toHaveLength(2);
  });

  it('F3b: a FORK re-roll returning the same text still prints its answer', () => {
    ctrl.streamingText = 'the answer';
    ctrl.commitStreamingText();
    ctrl.onError({ error: 'transport drop' });

    // A fork rewinds the whole run (`exitReplay` → `resetRunState`), so the conversation starts
    // empty; the assertion is that the re-roll's identical answer is NOT swallowed as a duplicate
    // of the run it forked from.
    void ctrl.forkRun('run-1', '');
    doneWith('the answer');

    expect(
      ctrl.conversation.filter((e) => e.type === 'assistant-text' && e.content === 'the answer'),
    ).toHaveLength(1);
  });

  it('an empty region produces no entry — a blank thought is not a thought', () => {
    drive([{ eventType: 'reasoning_chunk', payload: { text: '' } }, { eventType: 'budget_update', payload: {} }]);
    expect(types()).toEqual([]);
  });

  it('the three primers do NOT cut — they describe or open the run rather than advance it', () => {
    drive([
      think('still going'),
      { eventType: 'session_started', payload: { sessionId: 'run-859a' } },
      { eventType: 'state_snapshot', payload: {} },
      { eventType: 'run_started', payload: { runId: 'run-859a' } },
    ]);
    expect(types()).toEqual([]);
    expect(ctrl.reasoning.isThinking).toBe(true);
  });
});
