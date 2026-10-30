// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentSessionController } from '../../controllers/AgentSessionController.js';
import { HaltBackend } from '../../controllers/testing/HaltBackend.js';
import { setAuthorizationPresenter } from '../../operations/authorizationBroker.js';
import { ComposerPort } from '../../state/composerPort.js';
import { Sv3RunDomain } from './sv3-run-domain.js';
import {
  adoptRunSession, latestTurnRef, settleAgentTurn, submitInSession, SV3_SESSIONS_EMPTY,
  type Sv3SessionList,
} from './sv3-sessions.js';

let backend: HaltBackend;
const controllers: AgentSessionController[] = [];
const tasks: Promise<unknown>[] = [];
const flush = async (): Promise<void> => { for (let i = 0; i < 30; i++) await Promise.resolve(); };

/** The host owns turns; the domain owns run bookkeeping. No Search element or private view seams. */
function windowOwner() {
  let sessions: Sv3SessionList = SV3_SESSIONS_EMPTY;
  const clock = {
    now: () => 1234,
    setTimeout: vi.fn((_callback: () => void, _ms: number) => 1),
    clearTimeout: vi.fn((_timer: number) => {}),
  };
  const host = {
    createConversationId: vi.fn(() => 'conversation'),
    activeConversationId: vi.fn(() => sessions.activeId),
    showingResults: vi.fn(() => false),
    hasPreparedHandoff: vi.fn(() => false),
    connected: vi.fn(() => true),
    turn: vi.fn<ConstructorParameters<typeof Sv3RunDomain>[0]['turn']>(ref =>
      sessions.sessions.find(session => session.id === ref.sessionId)?.turns.find(turn => turn.id === ref.turnId)),
    lastTurn: vi.fn((id: string) => sessions.sessions.find(session => session.id === id)?.turns.at(-1)),
    adoptRun: vi.fn<ConstructorParameters<typeof Sv3RunDomain>[0]['adoptRun']>((title, now, id, turnId) => {
      const adopted = adoptRunSession(sessions, title, now, id, turnId);
      sessions = adopted.list;
      return adopted.ref;
    }),
    ensureConversation: vi.fn(),
    setEvidence: vi.fn(),
    settleRun: vi.fn<ConstructorParameters<typeof Sv3RunDomain>[0]['settleRun']>((ref, outcome, calls, now, disposition) => {
      sessions = settleAgentTurn(sessions, ref, outcome, calls, now, '', disposition);
    }),
    applyRecord: vi.fn(),
    preserveFailedReceipt: vi.fn(),
    refreshRecord: vi.fn(async (_id: string) => false),
    announceStop: vi.fn(),
    persistSteerDraft: vi.fn(() => true),
    dockComposer: vi.fn(),
    focusDecision: vi.fn(),
    requestUpdate: vi.fn(),
  };
  let draft = { text: 'Inspect this folder', edit: {} };
  const source = {
    read: () => draft,
    isCurrent: () => true,
    replace: (text: string) => { draft = { text, edit: {} }; },
    clear: vi.fn(() => { draft = { text: '', edit: {} }; }),
  };
  const composer = new ComposerPort(source);
  let available = true;
  const ctrl = new AgentSessionController('http://owner.test', () => owner.onAgentUpdate(), backend.transport());
  const owner = new Sv3RunDomain(host, composer, {
    peek: () => available ? ctrl : null,
    get: () => ctrl,
    discover: async () => null,
  }, clock);
  controllers.push(ctrl);
  const submit = (text = 'Inspect this folder') => {
    composer.replace(text);
    sessions = submitInSession(sessions, text, clock.now(), 'agent', host.createConversationId());
    owner.dispatch(latestTurnRef(sessions)!, text, 'standard');
  };
  return { host, owner, ctrl, composer, source, clock, submit,
    get sessions() { return sessions; },
    snapshot: () => owner.snapshot(), absent: () => { available = false; } };
}

async function start() {
  const window = windowOwner(); window.submit(); await flush(); backend.identity(); await flush(); return window;
}
const lastTurn = (window: ReturnType<typeof windowOwner>) => window.sessions.sessions.at(-1)?.turns.at(-1);

beforeEach(() => {
  backend = new HaltBackend(); sessionStorage.clear(); localStorage.clear();
  vi.stubGlobal('fetch', vi.fn(async () => Response.json({})));
});
afterEach(async () => {
  for (const ctrl of controllers.splice(0)) ctrl.destroy();
  for (const response of backend.responses) if (!response.settled) response.release();
  for (const observation of backend.observations) observation.close();
  await Promise.allSettled(tasks.splice(0));
  setAuthorizationPresenter(null); vi.unstubAllGlobals();
});

describe('the window run owner against the injected backend', () => {
  it('SW-RUN-01 dispatches the opened turn and settles duplicate terminals once', async () => {
    const window = windowOwner();
    const send = vi.spyOn(window.ctrl, 'send');
    window.submit();
    expect(lastTurn(window)?.status).toBe('streaming');
    expect(send).toHaveBeenCalledOnce();
    expect(window.composer.snapshot().text).toBe('');
    expect(window.source.clear).toHaveBeenCalledOnce();
    expect(window.host.dockComposer).toHaveBeenCalledOnce();
    await flush(); backend.identity(); backend.terminal('run-1', 'DONE'); await flush();
    expect(lastTurn(window)?.status).toBe('complete');
    expect(window.host.settleRun).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ turnId: lastTurn(window)!.id }), 'complete', 0, window.clock.now(), 'COMPLETED');
    expect(window.host.refreshRecord).toHaveBeenCalledExactlyOnceWith(window.ctrl.conversationId);
    window.owner.onAgentUpdate(); window.owner.onAgentUpdate();
    expect(window.host.settleRun).toHaveBeenCalledOnce();
    expect(window.host.refreshRecord).toHaveBeenCalledOnce();
    expect(backend.observations).toHaveLength(1);
  });

  it('SW-RUN-08 absence creates no run and reload adopts a matching live run once without navigation', async () => {
    const idle = windowOwner(); idle.absent(); idle.owner.syncRunPresence();
    expect(backend.requests).toHaveLength(0); expect(idle.sessions.sessions).toHaveLength(0);
    const first = await start(); const conversationId = first.ctrl.conversationId!;
    const reopened = windowOwner(); reopened.ctrl.conversationId = conversationId;
    tasks.push(reopened.ctrl.reattachActiveRunOnLoad()); await flush();
    backend.identity(); await flush(); reopened.owner.syncRunPresence(); reopened.owner.syncRunPresence();
    expect(reopened.sessions.activeId).toBeNull();
    expect(reopened.sessions.sessions).toHaveLength(1);
    expect(reopened.sessions.sessions[0]?.id).toBe(conversationId);
    expect(reopened.sessions.sessions[0]?.turns).toHaveLength(1);
    const other = windowOwner(); other.ctrl.conversationId = 'other';
    tasks.push(other.ctrl.reattachActiveRunOnLoad()); await flush();
    expect(other.sessions.sessions).toHaveLength(0);
  });

  it('SW-RUN-09 duplicate approval requests share one lookup and stale details cannot prompt a replacement', async () => {
    const window = await start(); backend.holdResponses.add('/api/chat/approval');
    const presenter = vi.fn(async () => ({ approved: true, allowAlways: false })); setAuthorizationPresenter(presenter);
    const pending = { callId: 'c1', toolName: 'core.search', arguments: '{}', risk: 'LOW', gateBehavior: 'inline_confirm' };
    const detail = { callId: 'c1', operationId: 'core.search', argsSummary: 'Search documents', riskTier: 'LOW', gateBehavior: 'INLINE_CONFIRM' };
    backend.emit('tool_call_pending', pending); backend.emit('tool_call_pending', pending); await flush();
    const lookups = backend.responses.filter(response => response.path === '/api/chat/approval');
    expect(lookups).toHaveLength(1);
    expect(window.snapshot()?.prompts.some(prompt => prompt.kind === 'approval')).toBe(true);
    lookups[0]!.release(200, detail); await flush();
    expect(presenter).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ pendingId: 'c1', owningRunId: 'run-1' }));
    const approvalPosts = () => backend.requests.filter(request => request.path === '/api/chat/approve' && request.init?.method === 'POST');
    expect(approvalPosts()).toHaveLength(1);
    presenter.mockClear();

    backend.emit('tool_call_pending', { ...pending, callId: 'c2' });
    backend.emit('tool_call_pending', { ...pending, callId: 'c2' }); await flush();
    const stale = backend.responses.filter(response => response.path === '/api/chat/approval').at(-1)!;
    expect(backend.responses.filter(response => response.path === '/api/chat/approval')).toHaveLength(2);
    backend.terminal('run-1', 'DONE'); await flush();
    window.submit('Replacement task'); await flush(); backend.identity(); await flush();
    expect(window.ctrl.sessionId).toBe('run-2');
    expect(window.snapshot()?.phase).toBe('running');
    stale.release(200, { ...detail, callId: 'c2' }); await flush();
    expect(presenter).not.toHaveBeenCalled();
    expect(approvalPosts()).toHaveLength(1);
  });

  it('SW-RUN-10 a failed approval remains an approval error in the owner feed', async () => {
    const window = await start(); backend.holdResponses.add('/api/chat/approve');
    const approving = window.ctrl.approveCall('c1'); await flush();
    backend.responses[0]!.release(404); await approving; await flush();
    expect(window.snapshot()?.feed.errored).toBe(true);
    expect(window.snapshot()?.feed.items).toContainEqual(expect.objectContaining({
      kind: 'note', label: 'Error',
      text: expect.stringContaining("Couldn't send your approval (HTTP 404) — the action was not approved."),
    }));
  });

  it('SW-RUN-11 Stop preserves arriving prose and tools in both windows through the halted terminal', async () => {
    const first = await start(); const observer = windowOwner();
    observer.ctrl.conversationId = first.ctrl.conversationId;
    tasks.push(observer.ctrl.reattachActiveRunOnLoad()); await flush(); backend.identity(); await flush();
    first.owner.haltRun(); first.owner.haltRun(); await flush();
    expect(backend.responses).toHaveLength(1); expect(first.owner.runStopping()?.state).toBe('stopping');
    backend.emit('chunk', { text: 'Still arriving' }, 0); backend.emit('chunk', { text: 'Still arriving' }, 1); await flush();
    for (const window of [first, observer]) {
      expect(window.snapshot()?.phase).toBe('running');
      expect(lastTurn(window)?.status).toBe('streaming');
    }
    // The stopping owner's feed includes buffered prose before a tool commits it for both windows.
    expect(first.snapshot()?.feed.items).toContainEqual(expect.objectContaining({ kind: 'text', text: 'Still arriving' }));
    expect(observer.ctrl.streamingText).toBe('Still arriving');
    for (const index of [0, 1]) {
      backend.emit('tool_call_proposed', { callId: 'stopping-tool', toolName: 'core.search', arguments: '{}', risk: 'LOW' }, index);
      backend.emit('tool_exec_started', { callId: 'stopping-tool', toolName: 'core.search' }, index);
      backend.emit('tool_exec_completed', { callId: 'stopping-tool', success: true, output: 'Found while stopping' }, index);
    }
    await flush();
    const expectArrivingOutput = (window: ReturnType<typeof windowOwner>) => {
      expect(window.snapshot()?.feed.items).toEqual(expect.arrayContaining([
        expect.objectContaining({ kind: 'text', text: 'Still arriving' }),
        expect.objectContaining({ kind: 'tool', id: 'stopping-tool', call: expect.objectContaining({
          toolName: 'core.search', status: 'completed', success: true, output: 'Found while stopping',
        }) }),
      ]));
      expect(window.snapshot()?.feed.toolCallCount).toBe(1);
    };
    expect(first.owner.runStopping()?.state).toBe('stopping');
    for (const window of [first, observer]) {
      expect(window.snapshot()?.phase).toBe('running');
      expect(lastTurn(window)?.status).toBe('streaming');
      expectArrivingOutput(window);
    }
    backend.responses[0]!.release(); backend.terminal('run-1', 'CANCELLED'); await flush();
    for (const window of [first, observer]) {
      expect(lastTurn(window)?.status).toBe('halted');
      expect(window.snapshot()?.phase).toBe('ended');
      expectArrivingOutput(window);
    }
  });
});
