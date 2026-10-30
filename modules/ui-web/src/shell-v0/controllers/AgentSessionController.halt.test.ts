// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import fc from 'fast-check';
import '../components/AuthorizationHost.js';
import type { AuthorizationHost } from '../components/AuthorizationHost.js';
import { EPHEMERAL_TOAST_EVENT, type EphemeralToastSpec } from '../components/advisory/ephemeralToast.js';
import { AgentSessionController, type AgentSessionTransport } from './AgentSessionController.js';
import { directiveAvailable, dispatchRunControl } from './runControlIntent.js';
import { STOP_RESPONSE_WAIT_MS } from '../state/stopAttempt.js';
import { HaltBackend } from './testing/HaltBackend.js';
import { projectSv3RunFeed, projectSv3RunPrompts } from '../views/search-v3/sv3-run.js';

// Also supplies the unchanged baseline's default dependencies. The same backend drives both trees;
// the candidate additionally receives each window's own transport through its constructor.
const routes = vi.hoisted(() => new Map<string, AgentSessionTransport>());
const virtualDispatch = vi.hoisted(() => vi.fn());
vi.mock('../commands/VirtualToolDispatcher.js', () => ({ dispatchVirtualToolCall: virtualDispatch }));
vi.mock('../api/authorizedFetch.js', () => ({ authorizedFetch: (url: string, init?: RequestInit) => routes.get(new URL(url).origin)!.request(url, init) }));
vi.mock('../plugin-api/pumpHostAiStream.js', () => ({ streamViaHost: (options: Parameters<AgentSessionTransport['stream']>[0]) => routes.get(new URL(options.fallbackUrl).origin)!.stream(options) }));
vi.mock('../../api/streams.js', async (original) => ({ ...await original<object>(), consumeShapeStream: (...args: Parameters<AgentSessionTransport['consume']>) => routes.get(new URL(String(args[0])).origin)!.consume(...args) }));
vi.mock('./liveRuns.js', () => ({ discoverLiveAgentRunRow: (base: string, conversationId?: string | null) => routes.get(base)!.discover(base, conversationId) }));
vi.mock('../substrates/effects/index.js', async (original) => ({ ...await original<object>(), recordEffect: vi.fn(() => ({ id: 1 })), markUndoableOperation: vi.fn() }));

const controllers: AgentSessionController[] = [];
const tasks: Promise<unknown>[] = [];
let backend: HaltBackend;
let serial = 0;
const flush = async (): Promise<void> => { for (let i = 0; i < 20; i++) await Promise.resolve(); };
function window(): AgentSessionController {
  const base = `http://window-${serial++}.test`;
  const transport = backend.transport(); routes.set(base, transport);
  const ctrl = new AgentSessionController(base, () => {}, transport);
  ctrl.conversationId = 'conversation'; controllers.push(ctrl); return ctrl;
}
async function start(identity = true): Promise<AgentSessionController> {
  const ctrl = window(); const sending = ctrl.send('Inspect this folder');
  void sending.catch(() => {}); // Keep failed transports observed while the schedule holds their result.
  tasks.push(sending); await flush();
  if (identity) backend.identity(); await flush(); return ctrl;
}
beforeEach(() => { backend = new HaltBackend(); localStorage.clear(); virtualDispatch.mockClear(); });
afterEach(async () => {
  for (const ctrl of controllers.splice(0)) ctrl.destroy();
  for (const response of backend.responses) response.release();
  await Promise.allSettled(tasks.splice(0)); routes.clear(); vi.useRealTimers();
});

describe('Halt follows the backend across windows and reloads', () => {
  it('S1 keeps observation, prose, reasoning, tools and evidence until CANCELLED', async () => {
    const ctrl = await start(); tasks.push(ctrl.cancelSession()); await flush();
    expect(ctrl.isStreaming, 'Stop must keep observing before the terminal').toBe(true);
    expect(ctrl.concludedRunId).toBeNull();
    backend.emit('reasoning_chunk', { text: 'Still thinking' });
    backend.emit('chunk', { text: 'Still answering' });
    backend.emit('tool_call_proposed', { callId: 'c1', toolName: 'core.search', arguments: '{}', risk: 'LOW' });
    backend.emit('tool_exec_started', { callId: 'c1', toolName: 'core.search' });
    backend.emit('tool_exec_completed', { callId: 'c1', success: true, output: 'Found it', structuredData: { grounding: [{ path: '/a', title: 'A' }] } });
    await flush();
    expect(projectSv3RunFeed(ctrl, 0).toolCallCount).toBe(1);
    expect(ctrl.conversation.some((entry) => entry.type === 'reasoning' && entry.content === 'Still thinking')).toBe(true);
    expect(ctrl.streamingText || ctrl.conversation.find((entry) => entry.type === 'assistant-text')?.content).toBe('Still answering');
    expect(ctrl.groundingDeltas.map((source) => source.path)).toEqual(['/a']);
    backend.responses[0]!.release(); await flush(); expect(ctrl.isStreaming).toBe(true);
    backend.terminal('run-1', 'CANCELLED'); await flush();
    expect(ctrl.isStreaming).toBe(false); expect(ctrl.terminalOutcome).toBe('cancelled');
  });

  it('S2 completion keeps the completed answer and disposition after Stop', async () => {
    const ctrl = await start(); tasks.push(ctrl.cancelSession()); await flush();
    backend.responses[0]!.release(); backend.terminal('run-1', 'DONE'); await flush();
    expect(ctrl.terminalDisposition).toBe('COMPLETED');
    expect(ctrl.conversation.find((entry) => entry.type === 'assistant-text')?.content).toBe('Completed answer');
  });

  it.each(['refused', 'network', 'no-answer'] as const)('S3 S6 %s is unconfirmed and permits exactly one explicit retry', async (failure) => {
    vi.useFakeTimers();
    const ctrl = await start(); tasks.push(ctrl.cancelSession()); tasks.push(ctrl.cancelSession()); await flush();
    expect(backend.responses).toHaveLength(1);
    if (failure === 'refused') backend.responses[0]!.release(409);
    if (failure === 'network') backend.responses[0]!.fail();
    if (failure === 'no-answer') await vi.advanceTimersByTimeAsync(10_000);
    await flush();
    expect(ctrl.isStreaming, 'An unconfirmed request must not halt the run').toBe(true);
    expect(ctrl.stopAttempt.outcome).toBe(failure === 'refused' ? 'refused' : 'unknown');
    expect(ctrl.concludedRunId).toBeNull();
    tasks.push(ctrl.cancelSession()); tasks.push(ctrl.cancelSession()); await flush();
    expect(backend.responses).toHaveLength(2);
    backend.terminal('run-1', 'CANCELLED');
  });

  it.each(['CANCELLED', 'DONE', 'READY_FOR_LLM'] as const)('S4 lost stream reconciles %s from discovery and record', async (state) => {
    const ctrl = await start(); tasks.push(ctrl.cancelSession()); backend.responses[0]!.release(); await flush();
    backend.runs.get('run-1')!.state = state;
    backend.observations[0]!.lose(); await flush();
    expect(backend.discoveries, 'A lost stopping stream must reconcile with the backend').toBeGreaterThan(0);
    if (state === 'READY_FOR_LLM') {
      expect(backend.observations).toHaveLength(2); backend.identity();
      backend.observations[1]!.lose(); await flush();
      expect(ctrl.isStreaming).toBe(true); expect(ctrl.concludedRunId).toBeNull();
      expect(ctrl.stopAttempt.outcome).toBe('accepted');
      expect(ctrl.stopObservationLost).toBe(true);
      tasks.push(ctrl.cancelSession()); await flush(); expect(backend.responses).toHaveLength(2);
    } else {
      expect(ctrl.terminalOutcome).toBe(state === 'DONE' ? 'completed' : 'cancelled');
      expect(ctrl.isStreaming).toBe(false);
    }
  });

  it.each(['resume', 'fork', 'workflow'] as const)('R2 %s reconciles a lost stopping stream and keeps retry available', async (entry) => {
    const ctrl = window();
    tasks.push(entry === 'resume' ? ctrl.resumeSession('prior')
      : entry === 'fork' ? ctrl.forkRun('prior', 'Edited question') : ctrl.runWorkflow('workflow'));
    await flush(); backend.identity(); await flush();
    tasks.push(ctrl.cancelSession()); backend.responses[0]!.release(); await flush();
    backend.observations[0]!.lose(); await flush();
    expect(backend.discoveries, 'Stopping must reconcile before treating a stream failure as an end').toBe(1);
    expect(backend.observations).toHaveLength(2);
    backend.identity(); backend.observations[1]!.lose(); await flush();
    expect(ctrl.terminalOutcome).toBeNull(); expect(ctrl.concludedRunId).toBeNull();
    expect(ctrl.isStreaming).toBe(true); expect(ctrl.runInFlight).toBe(true);
    expect(ctrl.stopObservationLost).toBe(true);
    tasks.push(ctrl.cancelSession()); await flush(); expect(backend.responses).toHaveLength(2);
    backend.terminal('run-1', 'CANCELLED', false);
    ctrl.reconcileRunLifecycle('run-1', 'CANCELLED');
    expect(ctrl.terminalOutcome).toBe('cancelled'); expect(ctrl.isStreaming).toBe(false);
  });

  it.each(['refused', 'cancelled-stream', 'cancelled-record'] as const)('R3 an open manual ceremony closes on Halt but the run-ended toast waits for %s', async (outcome) => {
    const host = document.createElement('jf-authorization-host') as AuthorizationHost;
    document.body.appendChild(host);
    const notices: string[] = [];
    const capture = (event: Event): void => { notices.push((event as CustomEvent<EphemeralToastSpec>).detail.message); };
    document.addEventListener(EPHEMERAL_TOAST_EVENT, capture);
    try {
      const ctrl = await start();
      backend.emit('tool_call_pending', { callId: 'manual', toolName: 'core.search', arguments: '{}', risk: 'LOW', gateBehavior: 'inline_confirm' });
      await flush(); await host.updateComplete;
      expect(host.shadowRoot!.querySelector('[data-testid="authorization-ceremony"]')).not.toBeNull();
      tasks.push(ctrl.cancelSession()); await flush(); await host.updateComplete;
      expect(host.shadowRoot!.querySelector('[data-testid="authorization-ceremony"]')).toBeNull();
      expect(notices.filter((message) => message.includes('because the run ended'))).toEqual([]);
      backend.responses[0]!.release(outcome === 'refused' ? 409 : 200); await flush();
      expect(notices.filter((message) => message.includes('because the run ended'))).toEqual([]);
      if (outcome !== 'refused') {
        if (outcome === 'cancelled-stream') backend.terminal('run-1', 'CANCELLED');
        else ctrl.reconcileRunLifecycle('run-1', 'CANCELLED');
        await flush();
        expect(notices.filter((message) => message.includes('because the run ended'))).toHaveLength(1);
        ctrl.reconcileRunLifecycle('run-1', 'CANCELLED'); await flush();
        expect(notices.filter((message) => message.includes('because the run ended'))).toHaveLength(1);
      }
      expect(backend.requests.filter((request) => request.init?.method === 'POST')).toEqual([]);
    } finally {
      host.remove(); document.removeEventListener(EPHEMERAL_TOAST_EVENT, capture);
    }
  });

  it.each(['network', 'malformed', 'late-replacement'] as const)('S4 held record %s keeps observation unconfirmed and cannot conclude a replacement', async (outcome) => {
    const ctrl = await start(); tasks.push(ctrl.cancelSession()); backend.responses[0]!.release(); await flush();
    backend.runs.get('run-1')!.state = 'DONE'; backend.holdResponses.add('/api/thread/conversation');
    backend.observations[0]!.lose(); await flush();
    expect(backend.discoveries, 'A lost stopping stream must reconcile with the backend').toBe(1);
    expect(ctrl.isStreaming).toBe(true); expect(ctrl.concludedRunId).toBeNull();
    expect(ctrl.stopObservationLost).toBe(true);
    if (outcome === 'late-replacement') {
      tasks.push(ctrl.send('Replacement')); await flush(); backend.identity();
      backend.responses[1]!.release(200, { lifecycles: [{ sessionId: 'run-1', state: 'CANCELLED' }] });
    } else if (outcome === 'network') backend.responses[1]!.fail();
    else backend.responses[1]!.release(200, { lifecycles: 'not a lifecycle list' });
    await flush(); expect(ctrl.concludedRunId).toBeNull(); expect(ctrl.isStreaming).toBe(true);
    if (outcome === 'late-replacement') expect(ctrl.sessionId).toBe('run-2');
  });

  it('S5 latches Stop before identity and delivers once when identity arrives', async () => {
    const ctrl = await start(false); tasks.push(ctrl.cancelSession()); await flush();
    expect(ctrl.isStreaming, 'Stop before identity must not abort delivery').toBe(true);
    expect(backend.responses).toHaveLength(0); backend.identity(); backend.identity(); await flush();
    expect(backend.responses).toHaveLength(1); expect(backend.responses[0]!.path).toBe('/api/chat/sessions/run-1');
    backend.terminal('run-1', 'CANCELLED');
  });

  it.each(['http', 'network', 'lost'] as const)('N1 N2 early Stop then %s failure matches the no-Stop outcome without discovering another window', async (failure) => {
    const control = await start(false);
    const error = failure === 'http' ? Object.assign(new Error('HTTP 503'), { status: 503 })
      : failure === 'network' ? new Error('connection lost')
      : Object.assign(new Error('Stream ended without terminal event'), { code: 'STREAM_INCOMPLETE' });
    backend.observations[0]!.lose(error); await flush();
    const expectedError = control.conversation.find((entry) => entry.type === 'error')?.content;
    expect(expectedError).toBeTruthy();
    const ctrl = await start(false); tasks.push(ctrl.cancelSession());
    // A separate window's live run shares the conversation but is never this dispatch's identity.
    const other = await start();
    expect(other.sessionId).toBe('run-3');
    backend.observations[1]!.lose(error); await flush();
    expect(ctrl.conversation.find((entry) => entry.type === 'error')?.content).toBe(expectedError);
    expect(ctrl.isStreaming).toBe(false); expect(ctrl.runInFlight).toBe(false);
    expect(ctrl.stopAttempt.intent).toBe(false); expect(ctrl.stopObservationLost).toBe(false);
    expect(ctrl.terminalOutcome).not.toBe('cancelled');
    expect(directiveAvailable(ctrl, { kind: 'halt' })).toBe(false);
    expect(backend.discoveries).toBe(0);
    expect(backend.requests).toEqual([]);
    backend.terminal('run-3', 'DONE');
  });

  it.each(['send', 'resume', 'fork', 'workflow'] as const)('G1 %s never answers before identity: Stop releases the composer at its deadline without discovering another run', async (entry) => {
    vi.useFakeTimers();
    const ctrl = window();
    tasks.push(entry === 'send' ? ctrl.send('Inspect this folder')
      : entry === 'resume' ? ctrl.resumeSession('prior')
      : entry === 'fork' ? ctrl.forkRun('prior', 'Edited question') : ctrl.runWorkflow('workflow'));
    await flush();
    tasks.push(ctrl.cancelSession()); await flush();
    await vi.advanceTimersByTimeAsync(STOP_RESPONSE_WAIT_MS - 1);
    expect(ctrl.isStreaming).toBe(true);
    tasks.push(ctrl.cancelSession()); await flush();
    await vi.advanceTimersByTimeAsync(2); await flush();
    expect(ctrl.isStreaming).toBe(false); expect(ctrl.runInFlight).toBe(false);
    expect(ctrl.stopAttempt.intent).toBe(false); expect(ctrl.stopObservationLost).toBe(false);
    expect(ctrl.conversation.find((entry) => entry.type === 'error')?.content).toBeTruthy();
    expect(ctrl.terminalOutcome).not.toBe('cancelled'); expect(ctrl.concludedRunId).toBeNull();
    expect(directiveAvailable(ctrl, { kind: 'halt' })).toBe(false);
    expect(backend.discoveries).toBe(0); expect(backend.requests).toEqual([]);
    backend.identity(); await flush();
    expect(ctrl.sessionId).toBeNull(); expect(backend.requests).toEqual([]);
  });

  it('N2 early Stop then completion before identity releases the composer and offers no permanent Stop', async () => {
    const ctrl = await start(false); tasks.push(ctrl.cancelSession());
    backend.terminal('run-1', 'DONE'); await flush();
    expect(ctrl.terminalOutcome).toBe('completed');
    expect(ctrl.isStreaming).toBe(false); expect(ctrl.runInFlight).toBe(false);
    expect(directiveAvailable(ctrl, { kind: 'halt' })).toBe(false);
    expect(backend.requests).toEqual([]);
  });

  it('R1 case 4 discovery network failure leaves an identified stop unconfirmed and retryable', async () => {
    const ctrl = await start(); tasks.push(ctrl.cancelSession()); backend.responses[0]!.release(); await flush();
    backend.holdResponses.add('/api/chat/runs/live');
    backend.observations[0]!.lose(); await flush(); backend.responses[1]!.fail(); await flush();
    await expect(tasks[0]).resolves.toBeUndefined();
    expect(ctrl.terminalOutcome).toBeNull(); expect(ctrl.concludedRunId).toBeNull();
    expect(ctrl.stopObservationLost).toBe(true);
    expect(directiveAvailable(ctrl, { kind: 'halt' })).toBe(true);
    tasks.push(dispatchRunControl(ctrl, { kind: 'halt' })); await flush();
    expect(backend.responses.filter((response) => response.init?.method === 'DELETE')).toHaveLength(2);
    backend.terminal('run-1', 'CANCELLED', false); ctrl.reconcileRunLifecycle('run-1', 'CANCELLED');
  });

  it('R1 case 5 a CANCELLED terminal arriving after the stop deadline still settles halted', async () => {
    vi.useFakeTimers(); const ctrl = await start(); tasks.push(ctrl.cancelSession()); await flush();
    await vi.advanceTimersByTimeAsync(STOP_RESPONSE_WAIT_MS + 1);
    expect(ctrl.stopAttempt.outcome).toBe('unknown'); expect(ctrl.stopAttempt.pending).toBe(false);
    expect(ctrl.isStreaming).toBe(true); expect(ctrl.terminalOutcome).toBeNull();
    backend.terminal('run-1', 'CANCELLED'); await flush();
    expect(ctrl.terminalOutcome).toBe('cancelled'); expect(ctrl.isStreaming).toBe(false);
  });

  it.each(['CANCELLED', 'DONE'] as const)('R2 case 6 a resumed run reconciles its real conversation record as %s', async (state) => {
    const ctrl = window(); ctrl.conversationId = 'generated-window-conversation';
    tasks.push(ctrl.resumeSession('prior')); await flush(); backend.identity(); await flush();
    expect(ctrl.conversationId).toBe('generated-window-conversation');
    tasks.push(ctrl.cancelSession()); backend.responses[0]!.release(); await flush();
    backend.terminal('run-1', state, false); backend.observations[0]!.lose(); await flush();
    expect(backend.requests.some((request) => request.path === '/api/thread/conversation')).toBe(true);
    expect(ctrl.terminalOutcome).toBe(state === 'CANCELLED' ? 'cancelled' : 'completed');
    expect(ctrl.isStreaming).toBe(false); expect(ctrl.concludedRunId).toBe('run-1');
  });

  it('S7 late response, terminal and failure from a replaced run cannot change its replacement', async () => {
    const ctrl = await start(); tasks.push(ctrl.cancelSession());
    backend.hold('error', { error: 'Session cancelled', errorCode: 'CANCELLED' }, 0);
    tasks.push(ctrl.send('New task')); await flush(); backend.identity();
    backend.responses[0]!.fail(); backend.release(0); backend.observations[0]!.lose(); await flush();
    expect(ctrl.sessionId).toBe('run-2'); expect(ctrl.isStreaming).toBe(true);
    expect(ctrl.concludedRunId).toBeNull(); expect(ctrl.stopAttempt?.intent ?? false).toBe(false);
    backend.terminal('run-2', 'DONE');
  });

  it.each(['CANCELLED', 'DONE'] as const)('S8 observers and a reloaded window follow %s without restarting or claiming Stop', async (terminal) => {
    const owner = await start(); tasks.push(owner.cancelSession()); await flush();
    const observer = window(); tasks.push(observer.reattachActiveRunOnLoad()); await flush(); backend.identity();
    owner.destroy(); const reloaded = window(); tasks.push(reloaded.reattachActiveRunOnLoad()); await flush(); backend.identity();
    expect(backend.runs.size).toBe(1); expect(backend.responses).toHaveLength(1);
    expect(observer.stopAttempt?.intent ?? false).toBe(false); expect(reloaded.stopAttempt?.intent ?? false).toBe(false);
    backend.terminal('run-1', terminal); await flush();
    for (const ctrl of [observer, reloaded]) {
      expect(ctrl.isStreaming).toBe(false);
      expect(ctrl.concludedRunId).toBe('run-1');
      if (terminal === 'DONE') expect(ctrl.terminalDisposition).toBe('COMPLETED');
    }
  });

  it('P6 stop attempts stay in memory across reload and create no storage key', async () => {
    const ctrl = await start();
    const stored = (): unknown => [localStorage, sessionStorage].map((storage) =>
      Object.keys(storage).sort().map((key) => [key, storage.getItem(key)]));
    const before = stored();
    tasks.push(ctrl.cancelSession()); await flush();
    backend.responses[0]!.release(); await flush();
    expect(stored()).toEqual(before);
    ctrl.destroy();
    const reloaded = window(); tasks.push(reloaded.reattachActiveRunOnLoad()); await flush(); backend.identity();
    expect(stored()).toEqual(before);
    expect(reloaded.stopAttempt?.intent ?? false).toBe(false);
    expect(backend.runs.size).toBe(1); expect(backend.responses).toHaveLength(1);
    backend.terminal('run-1', 'CANCELLED');
  });

  it.each(['budget', 'context', 'approval', 'virtual'] as const)('S11 Halt closes %s and later frames cannot reopen decisions or send continuations', async (gate) => {
    const ctrl = await start();
    const event = gate === 'budget' ? 'budget_gate' : gate === 'context' ? 'context_gate' : gate === 'approval' ? 'tool_call_pending' : 'tool_call_virtual';
    const payload = gate === 'budget' ? { tokensNeeded: 1, tokensRemaining: 0 }
      : gate === 'context' ? { promptTokens: 10, contextWindow: 10 }
      : { callId: 'c1', toolName: 'core.search', wireName: 'vop_test', arguments: '{}', risk: 'LOW', gateBehavior: 'auto' };
    backend.emit('state_snapshot', { park: { kind: gate, sinceEpochMs: 1 }, pendingApprovals: [] });
    if (gate !== 'virtual') backend.emit(event, payload);
    expect(ctrl.runPark?.kind).toBe(gate);
    tasks.push(ctrl.cancelSession()); await flush();
    const requestsAtHalt = backend.requests.length;
    backend.emit(event, payload);
    if (gate === 'virtual') await vi.dynamicImportSettled();
    backend.emit('state_snapshot', { park: { kind: gate, sinceEpochMs: 1 }, pendingApprovals: [] }); await flush();
    expect(projectSv3RunPrompts(ctrl, projectSv3RunFeed(ctrl, 0))).toEqual([]);
    expect(ctrl.budgetGate).toBeNull(); expect(ctrl.contextGate).toBeNull(); expect(ctrl.runPark).toBeNull();
    expect(backend.requests.slice(requestsAtHalt).filter((request) => request.init?.method === 'POST')).toEqual([]);
    expect(virtualDispatch).not.toHaveBeenCalled();
    expect(ctrl.isStreaming, 'The parked run must keep observing until its backend terminal').toBe(true);
    backend.terminal('run-1', 'CANCELLED'); await flush(); expect(ctrl.terminalOutcome).toBe('cancelled');
  });

  it('R3 generated repair schedules cross the stop deadline and eventually settle all six cases', async () => {
    vi.useFakeTimers();
    const covered = new Set<number>();
    await fc.assert(fc.asyncProperty(fc.integer({ min: 1, max: 6 }),
      fc.constantFrom('accepted', 'refused', 'unknown'), fc.constantFrom('CANCELLED', 'DONE', 'missing', 'live'),
      fc.array(fc.constantFrom('halt', 'chunk', 'observer', 'reload'), { maxLength: 8 }),
      async (scenario, response, state, noise) => {
        covered.add(scenario); backend = new HaltBackend();
        let ctrl: AgentSessionController;
        if (scenario === 6) {
          ctrl = window(); ctrl.conversationId = 'generated-window-conversation';
          tasks.push(ctrl.resumeSession('prior')); await flush(); backend.identity(); await flush();
        } else ctrl = await start(scenario === 4 || scenario === 5);
        tasks.push(ctrl.cancelSession()); await flush();
        for (const action of noise) {
          if (action === 'halt') tasks.push(ctrl.cancelSession());
          if (action === 'chunk') backend.emit('chunk', { text: 'Output during Stop' }, 0);
          if (action === 'observer' || action === 'reload') {
            const observer = window(); tasks.push(observer.reattachActiveRunOnLoad()); await flush();
            backend.identity();
            if (action === 'reload') observer.destroy();
          }
          await flush();
        }
        if (scenario === 1) backend.identity(0);
        if (scenario === 2) {
          if (state === 'missing') backend.runs.delete('run-1');
          if (state === 'CANCELLED' || state === 'DONE') backend.terminal('run-1', state, false);
          backend.observations[0]!.lose();
        }
        if (scenario === 3) {
          backend.runs.delete('run-1');
          backend.observations[0]!.lose(Object.assign(new Error('HTTP 503'), { status: 503 }));
        }
        if (scenario === 4) {
          backend.holdResponses.add('/api/chat/runs/live'); backend.observations[0]!.lose(); await flush();
          backend.responses.find((held) => held.path === '/api/chat/runs/live')!.fail();
        }
        if (scenario === 6) {
          backend.terminal('run-1', state === 'DONE' ? 'DONE' : 'CANCELLED', false);
          backend.observations[0]!.lose();
        }
        await flush();
        const deletes = backend.responses.filter((held) => held.init?.method === 'DELETE');
        expect(deletes.length).toBeLessThanOrEqual(1);
        if (response === 'accepted') deletes[0]?.release();
        if (response === 'refused') deletes[0]?.release(409);
        await vi.advanceTimersByTimeAsync(STOP_RESPONSE_WAIT_MS + 1); await flush();
        expect(ctrl.stopAttempt.pending).toBe(false);
        if (scenario === 1 || scenario === 5) {
          // A late terminal still wins after an unanswered request's deadline.
          backend.terminal('run-1', scenario === 5 || state !== 'DONE' ? 'CANCELLED' : 'DONE'); await flush();
          expect(ctrl.isStreaming).toBe(false);
          expect(ctrl.terminalOutcome).toBe(scenario === 5 || state !== 'DONE' ? 'cancelled' : 'completed');
        } else if (scenario === 2 || scenario === 3) {
          expect(ctrl.isStreaming).toBe(false); expect(ctrl.runInFlight).toBe(false);
          expect(ctrl.stopAttempt.intent).toBe(false);
        } else if (scenario === 6) {
          expect(ctrl.isStreaming).toBe(false);
          expect(ctrl.terminalOutcome).toBe(state === 'DONE' ? 'completed' : 'cancelled');
        } else {
          expect(ctrl.terminalOutcome).toBeNull(); expect(ctrl.stopObservationLost).toBe(true);
          expect(directiveAvailable(ctrl, { kind: 'halt' })).toBe(true);
          if (!ctrl.sessionId) { expect(ctrl.isStreaming).toBe(false); expect(ctrl.runInFlight).toBe(false); }
        }
        for (const controller of controllers.splice(0)) controller.destroy();
        for (const held of backend.responses) held.release();
        await Promise.allSettled(tasks.splice(0));
      }), { numRuns: 90, seed: 50831 });
    expect([...covered].sort()).toEqual([1, 2, 3, 4, 5, 6]);
  });

  it('S10 generated Halt schedules preserve S1-S8 invariants against one backend', async () => {
    vi.useFakeTimers();
    await fc.assert(fc.asyncProperty(fc.array(fc.constantFrom('halt', 'accept', 'refuse', 'network', 'chunk', 'identity', 'observer', 'reload', 'lose', 'replace', 'accept-oldest', 'fail-oldest', 'cancelled', 'done', 'deadline'), { minLength: 1, maxLength: 35 }), async (schedule) => {
      backend = new HaltBackend();
      let ctrl = await start(false);
      let currentObservation = backend.observations.length - 1;
      for (const action of schedule) {
        const runId = backend.observations[currentObservation]!.runId;
        if (action === 'deadline') await vi.advanceTimersByTimeAsync(STOP_RESPONSE_WAIT_MS + 1);
        if (action === 'halt' && directiveAvailable(ctrl, { kind: 'halt' })) tasks.push(ctrl.cancelSession());
        if (action === 'identity') backend.identity(currentObservation);
        if (action === 'chunk') backend.emit('chunk', { text: 'arriving output' }, currentObservation);
        if (action === 'accept') backend.responses.at(-1)?.release();
        if (action === 'accept-oldest') backend.responses.find((response) => !response.settled)?.release();
        if (action === 'fail-oldest') backend.responses.find((response) => !response.settled)?.fail();
        if (action === 'refuse') backend.responses.at(-1)?.release(409);
        if (action === 'network') backend.responses.at(-1)?.fail();
        if (action === 'cancelled' || action === 'done') backend.terminal(runId, action === 'done' ? 'DONE' : 'CANCELLED');
        if (action === 'lose') backend.observations[currentObservation]!.lose();
        if (action === 'observer' || action === 'reload') {
          if (action === 'reload') ctrl.destroy();
          const next = window(); tasks.push(next.reattachActiveRunOnLoad()); await flush();
          if (backend.observations.length > currentObservation + 1) {
            ctrl = next; currentObservation = backend.observations.length - 1; backend.identity(currentObservation);
          }
        }
        if (action === 'replace') { tasks.push(ctrl.send('Replacement')); await flush(); currentObservation = backend.observations.length - 1; }
        await flush();
        for (const observed of controllers) {
          if (observed.concludedRunId !== null) expect(['DONE', 'CANCELLED', 'ERROR']).toContain(backend.runs.get(observed.concludedRunId)?.state);
          if (observed.stopAttempt?.intent && observed.terminalOutcome === null && observed.stopObservationLost) {
            expect(directiveAvailable(observed, { kind: 'halt' })).toBe(true);
            if (!observed.sessionId) expect(observed.isStreaming).toBe(false);
          }
        }
        const pendingPerOwnerAndRun = new Map<string, number>();
        for (const response of backend.responses.filter((response) => !response.settled && response.init?.method === 'DELETE')) {
          const key = `${response.owner}:${response.path}`;
          pendingPerOwnerAndRun.set(key, (pendingPerOwnerAndRun.get(key) ?? 0) + 1);
        }
        for (const count of pendingPerOwnerAndRun.values()) expect(count, 'One request per pending attempt in every window').toBe(1);
      }
      await vi.advanceTimersByTimeAsync(STOP_RESPONSE_WAIT_MS + 1);
      for (const observed of controllers) expect(observed.stopAttempt.pending).toBe(false);
      for (const controller of controllers.splice(0)) controller.destroy();
      for (const response of backend.responses) response.release();
      await Promise.allSettled(tasks.splice(0));
    }), { numRuns: 60, seed: 43119 });
  });
});
