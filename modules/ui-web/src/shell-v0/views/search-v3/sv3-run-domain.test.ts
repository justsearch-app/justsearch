// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentSessionController } from '../../controllers/AgentSessionController.js';
import { HaltBackend } from '../../controllers/testing/HaltBackend.js';
import { setAuthorizationPresenter } from '../../operations/authorizationBroker.js';
import type { Sv3RunDecision, Sv3StopState } from './Sv3Main.js';
import type { Sv3RunFeedItem, Sv3RunView } from './sv3-run.js';
import type { Sv3SessionList } from './sv3-sessions.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';

let current: AgentSessionController | null = null;
vi.mock('../../state/agentSessionStore.js', () => ({
  peekAgentSessionController: () => current,
  getAgentSessionController: () => current,
  subscribeAgentSession: () => () => {},
}));
vi.mock('../../substrates/effects/index.js', async original => ({
  ...await original<object>(), recordEffect: vi.fn(() => ({ id: 1 })), markUndoableOperation: vi.fn(),
}));
import './SearchV3View.js';

interface Owner {
  onAgentUpdate(): void;
  snapshot(): Sv3RunView | null;
  haltRun(): void;
  runStopping(): Sv3StopState | null;
  steerLiveRun(text: string): void;
  decide(detail: Sv3RunDecision): void;
  syncRunPresence(): void;
  unconfirmedSteerNotes: ReadonlyMap<string, readonly Sv3RunFeedItem[]>;
}
interface WindowHarness {
  apiBase: string;
  sessions: Sv3SessionList;
  runDomain?: Owner;
  delegate(text: string, turnAction: boolean): void;
  onAgentUpdate(): void;
  projectRun(): Sv3RunView | null;
  haltRun(): void;
  runStopping(): Sv3StopState | null;
  steerLiveRun(text: string): void;
  onRunDecision(event: CustomEvent<Sv3RunDecision>): void;
  syncRunPresence(): void;
  unconfirmedSteerNotes: ReadonlyMap<string, readonly Sv3RunFeedItem[]>;
}

let backend: HaltBackend;
const controllers: AgentSessionController[] = [];
const views: WindowHarness[] = [];
const tasks: Promise<unknown>[] = [];
const flush = async (): Promise<void> => { for (let i = 0; i < 30; i++) await Promise.resolve(); };

/** No mounted component or DOM assertions: drive the window owner and its injected real transport.
 * The fallback runs the exact same probes against the original view-owned methods on the base.
 */
function windowOwner() {
  const view = document.createElement('jf-sv3-window') as unknown as WindowHarness;
  views.push(view);
  view.apiBase = 'http://owner.test';
  // Isolate submission preparation, navigation and record fetching; those stay in the view.
  Object.defineProperty(view, 'delegateUnavailableReason', { get: () => '' });
  Object.assign(view, {
    draftMayIssue: () => true, persistDraft: () => true,
    claimConversation: () => {}, leaveResultsBySending: () => {},
    setComposerState: async () => {}, refreshRecord: async () => false,
  });
  const owner: Owner = view.runDomain ?? {
    onAgentUpdate: () => view.onAgentUpdate(), snapshot: () => view.projectRun(),
    haltRun: () => view.haltRun(), runStopping: () => view.runStopping(),
    steerLiveRun: text => view.steerLiveRun(text),
    decide: detail => view.onRunDecision(new CustomEvent('decision', { detail })),
    syncRunPresence: () => view.syncRunPresence(),
    get unconfirmedSteerNotes() { return view.unconfirmedSteerNotes; },
  };
  let available = true;
  const ctrl = new AgentSessionController(view.apiBase, () => {
    current = available ? ctrl : null;
    owner.onAgentUpdate();
  }, backend.transport());
  controllers.push(ctrl);
  const use = () => { current = available ? ctrl : null; };
  const submit = (text = 'Inspect this folder') => { use(); view.delegate(text, true); };
  const snapshot = () => { use(); return owner.snapshot(); };
  return { view, owner, ctrl, use, submit, snapshot, absent: () => { available = false; use(); } };
}

async function start() {
  const window = windowOwner(); window.submit(); await flush(); backend.identity(); await flush(); return window;
}
const notes = (run: Sv3RunView | null) => run?.feed.items.flatMap(item => item.kind === 'note' ? [[item.label, item.text]] : []) ?? [];
const lastTurn = (view: WindowHarness) => view.sessions.sessions.at(-1)?.turns.at(-1);

beforeEach(() => {
  backend = new HaltBackend(); sessionStorage.clear(); localStorage.clear();
  __resetConversationListForTest(); __resetDraftProvidersForTest();
  vi.stubGlobal('fetch', vi.fn(async () => Response.json({})));
});
afterEach(async () => {
  for (const ctrl of controllers.splice(0)) ctrl.destroy();
  for (const response of backend.responses) if (!response.settled) response.release();
  for (const observation of backend.observations) observation.close();
  await Promise.allSettled(tasks.splice(0)); views.splice(0); current = null;
  setAuthorizationPresenter(null); vi.unstubAllGlobals();
});

describe('the window run owner against the injected backend', () => {
  it('SW-RUN-01 opens its turn before dispatch and settles duplicate terminals once', async () => {
    const window = windowOwner(); window.submit();
    expect(lastTurn(window.view)?.status).toBe('streaming');
    await flush(); backend.identity(); backend.terminal('run-1', 'DONE'); await flush();
    const turn = lastTurn(window.view);
    expect(turn?.status).toBe('complete');
    window.owner.onAgentUpdate(); window.owner.onAgentUpdate();
    expect(lastTurn(window.view)).toBe(turn);
    expect(backend.observations).toHaveLength(1);
  });

  it('SW-RUN-02 projects actual tool activity and leaves an unreported step count unknown', async () => {
    const window = await start();
    expect(window.ctrl.iterationsUsed).toBeNull();
    backend.emit('tool_call_proposed', { callId: 'c1', toolName: 'core.search', arguments: '{}', risk: 'LOW' });
    backend.emit('tool_exec_started', { callId: 'c1', toolName: 'core.search' });
    backend.emit('tool_exec_completed', { callId: 'c1', success: true, output: 'Found it' }); await flush();
    expect(window.snapshot()?.feed.toolCallCount).toBe(1);
    expect(window.snapshot()?.feed.items.some(item => item.kind === 'tool')).toBe(true);
    expect(window.ctrl.iterationsUsed).toBeNull();
  });

  it('SW-RUN-03 routes a held budget choice through the issuance seam', async () => {
    const window = await start();
    backend.emit('budget_gate', { tokensNeeded: 100, tokensRemaining: 0, totalTokensConsumed: 100 }); await flush();
    expect(window.snapshot()?.prompts.some(prompt => prompt.kind === 'budget')).toBe(true);
    window.owner.decide({ kind: 'budget', decision: 'raise', addTokens: 100 }); await flush();
    expect(backend.requests.filter(request => request.path.includes('/budget'))).toHaveLength(1);
  });

  it('SW-RUN-04 concurrent steering stays on one run and opens no competing turn', async () => {
    const window = await start();
    window.owner.steerLiveRun('Focus.'); window.owner.steerLiveRun('Focus.'); await flush();
    expect(backend.requests.filter(request => request.path.endsWith('/steer'))).toHaveLength(2);
    expect(backend.observations).toHaveLength(1);
    expect(window.view.sessions.sessions[0]?.turns).toHaveLength(1);
  });

  it('SW-RUN-05 applied queued not applied and unconfirmed directions remain distinct', async () => {
    const window = await start();
    for (const [directiveId, outcome] of [['a', 'applied'], ['b', 'queued'], ['c', 'not_applied']] as const) {
      backend.emit('directive_acknowledged', { directiveId, directiveText: directiveId, outcome });
    }
    backend.holdResponses.add('/api/chat/agent/steer');
    window.owner.steerLiveRun('unconfirmed'); await flush();
    expect(backend.responses).toHaveLength(1);
    backend.responses[0]!.fail(); await flush();
    expect([...window.owner.unconfirmedSteerNotes.values()].flat()).toHaveLength(1);
    const labels = notes(window.snapshot()).map(note => note[0]);
    expect(labels).toContain('Steered'); expect(labels).toContain('Direction queued'); expect(labels).toContain('Not applied');
  });

  it('SW-RUN-06 an unrelated report cannot settle either of two identical lost sends', async () => {
    const window = await start();
    const path = '/api/chat/agent/steer'; backend.holdResponses.add(path);
    window.owner.steerLiveRun('Focus.'); window.owner.steerLiveRun('Focus.'); await flush();
    const sends = backend.responses.filter(response => response.path === path);
    expect(sends).toHaveLength(2);
    for (const send of sends) send.fail(); await flush();
    expect([...window.owner.unconfirmedSteerNotes.values()].flat()).toHaveLength(2);
    backend.emit('directive_acknowledged', { directiveId: 'other', directiveText: 'Focus.', outcome: 'applied' }); await flush();
    expect([...window.owner.unconfirmedSteerNotes.values()].flat()).toHaveLength(2);
    const clientRequestId = (JSON.parse(String(sends[1]!.init?.body)) as { clientRequestId: string }).clientRequestId;
    backend.emit('directive_acknowledged', { directiveId: 'own', directiveText: 'Focus.', outcome: 'queued', clientRequestId }); await flush();
    expect([...window.owner.unconfirmedSteerNotes.values()].flat()).toHaveLength(1);
  });

  it('SW-RUN-07 a lost steer note survives terminal until its own report arrives', async () => {
    const window = await start(); backend.holdResponses.add('/api/chat/agent/steer');
    window.owner.steerLiveRun('Focus.'); await flush();
    const send = backend.responses[0]!; send.fail(); await flush();
    const clientRequestId = (JSON.parse(String(send.init?.body)) as { clientRequestId: string }).clientRequestId;
    backend.terminal('run-1', 'DONE'); await flush();
    expect([...window.owner.unconfirmedSteerNotes.values()].flat()).toHaveLength(1);
    backend.emit('directive_acknowledged', { directiveId: 'own', directiveText: 'Focus.', outcome: 'queued', clientRequestId }); await flush();
    expect([...window.owner.unconfirmedSteerNotes.values()].flat()).toHaveLength(1);
    // A closed stream is stale; the controller's authoritative report settles the send.
    window.ctrl.onDirectiveAcknowledged({ directiveId: 'own', directiveText: 'Focus.', outcome: 'queued', clientRequestId });
    await flush();
    expect([...window.owner.unconfirmedSteerNotes.values()].flat()).toHaveLength(0);
  });

  it('SW-RUN-08 absence creates no run and reload adopts a matching live run once without navigation', async () => {
    const idle = windowOwner(); idle.absent(); idle.owner.syncRunPresence();
    expect(backend.requests).toHaveLength(0); expect(idle.view.sessions.sessions).toHaveLength(0);
    const first = await start(); const conversationId = first.ctrl.conversationId!;
    const reopened = windowOwner(); reopened.ctrl.conversationId = conversationId;
    tasks.push(reopened.ctrl.reattachActiveRunOnLoad()); await flush();
    backend.identity(); await flush(); reopened.use(); reopened.owner.syncRunPresence(); reopened.owner.syncRunPresence();
    expect(reopened.view.sessions.activeId).toBeNull();
    expect(reopened.view.sessions.sessions).toHaveLength(1);
    expect(reopened.view.sessions.sessions[0]?.id).toBe(conversationId);
    expect(reopened.view.sessions.sessions[0]?.turns).toHaveLength(1);
    const other = windowOwner(); other.ctrl.conversationId = 'other';
    tasks.push(other.ctrl.reattachActiveRunOnLoad()); await flush();
    expect(other.view.sessions.sessions).toHaveLength(0);
  });

  it('SW-RUN-09 duplicate approval requests share one lookup and stale details cannot prompt a replacement', async () => {
    const window = await start(); backend.holdResponses.add('/api/chat/approval');
    const presenter = vi.fn(async () => ({ approved: true, allowAlways: false })); setAuthorizationPresenter(presenter);
    const pending = { callId: 'c1', toolName: 'core.search', arguments: '{}', risk: 'LOW', gateBehavior: 'inline_confirm' };
    backend.emit('tool_call_pending', pending); backend.emit('tool_call_pending', pending); await flush();
    const lookups = backend.responses.filter(response => response.path === '/api/chat/approval');
    expect(lookups).toHaveLength(1);
    expect(window.snapshot()?.prompts.some(prompt => prompt.kind === 'approval')).toBe(true);
    backend.terminal('run-1', 'DONE'); await flush();
    lookups[0]!.release(); await flush(); expect(presenter).not.toHaveBeenCalled();
  });

  it('SW-RUN-10 a failed approval remains an approval error in the owner feed', async () => {
    const window = await start(); backend.holdResponses.add('/api/chat/approve');
    const approving = window.ctrl.approveCall('c1'); await flush();
    backend.responses[0]!.release(404); await approving; await flush();
    expect(window.snapshot()?.feed.items.some(item => item.kind === 'note' && /approval/i.test(item.text))).toBe(true);
  });

  it('SW-RUN-11 Stop keeps both windows observing output until the backend terminal', async () => {
    const first = await start(); const observer = windowOwner();
    observer.ctrl.conversationId = first.ctrl.conversationId;
    tasks.push(observer.ctrl.reattachActiveRunOnLoad()); await flush(); backend.identity(); await flush();
    first.use(); first.owner.haltRun(); first.owner.haltRun(); await flush();
    expect(backend.responses).toHaveLength(1); expect(first.owner.runStopping()?.state).toBe('stopping');
    backend.emit('chunk', { text: 'Still arriving' }, 0); backend.emit('chunk', { text: 'Still arriving' }, 1); await flush();
    expect(first.snapshot()?.phase).toBe('running'); expect(observer.snapshot()?.phase).toBe('running');
    backend.responses[0]!.release(); backend.terminal('run-1', 'CANCELLED'); await flush();
    expect(lastTurn(first.view)?.status).toBe('halted'); expect(lastTurn(observer.view)?.status).toBe('halted');
  });
});
