// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Control } from '../../components/Control.js';
import type { Sv3Session, Sv3Turn } from './sv3-sessions.js';
import { projectSv3RecordTurns } from './sv3-record.js';
import type { ThreadEvent } from '../unifiedThreadProjection.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetAiStateForTest, __feedForTest } from '../../state/aiStateStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetModalityForTest } from '../../primitives/modality.js';
import { EPHEMERAL_TOAST_EVENT } from '../../components/advisory/ephemeralToast.js';
import { resetSearchState } from '../../state/searchState.js';

vi.mock('../../state/agentSessionStore.js', () => ({
  peekAgentSessionController: () => null,
  subscribeAgentSession: () => () => {},
}));
type Button = HTMLElement & { onActivate: () => Promise<void> | void; disabled: boolean; updateComplete: Promise<unknown> };
type ExportElement = HTMLElement & { updateComplete: Promise<unknown> };
const ID = 'export-conversation';
let events: ThreadEvent[];
let history: Record<string, unknown>;
let lifecycles: unknown[];
let threadStatus: number;
let historyStatus: number;
let threadGate: Promise<void> | null;
let copy: ReturnType<typeof vi.fn>;
let clicks: Array<{ name: string; href: string }>;
let blobs: Blob[];
let fetches: string[];
let serial = 0;
const event = (id: string, kind: ThreadEvent['kind'], content: string, attributes: Record<string, unknown> = {}): ThreadEvent => ({
  id, kind, content, attributes, originator: 'agent', occurredAt: new Date(1000 * serial++).toISOString(),
});
const source = (path: string, line: number) => ({
  parentDocId: path, chunkIndex: 0, chunkTotal: 1, excerpt: 'source passage',
  startLine: line, endLine: line + 2, startChar: 0, endChar: 20, headingText: 'Evidence', headingLevel: 1, score: .9,
});
const grounded = (id: string, answer: string, path: string, line: number) => event(id, 'ASSISTANT_MESSAGE', answer, {
  citations: [source(path, line)], claimMatches: {
    scorer: 'CROSS_ENCODER', matches: [{ sentenceText: answer, sentenceIndex: 0, sourceIndex: 0, similarity: .99 }],
  },
});
function session(turns: readonly Sv3Turn[] = projectSv3RecordTurns(events), id = ID): Sv3Session {
  return { id, title: `Title ${id}`, turns, history: null, storeBacked: true, renamed: true,
    pinned: false, completedAt: null, lastVisitedAt: 0, contextUsage: null, createdAt: 0, updatedAt: 0 };
}
async function settle(el: { updateComplete: Promise<unknown> }): Promise<void> {
  for (let i = 0; i < 8; i++) await new Promise(resolve => setTimeout(resolve, 0));
  await el.updateComplete;
}
async function mount(value = session()): Promise<SearchV3View> {
  const view = document.createElement('jf-sv3-window'); document.body.append(view); await settle(view);
  view.sessions = { activeId: value.id, sessions: [value] }; await settle(view); return view;
}
async function open(view: SearchV3View): Promise<ExportElement> {
  const sidebar = view.shadowRoot!.querySelector('jf-sv3-sidebar')!; await sidebar.updateComplete;
  const row = sidebar.shadowRoot!.querySelector('jf-sv3-session-row')!; await row.updateComplete;
  const trigger = row.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-session-row-export"]');
  expect(trigger, 'current conversation has a visible, named export action').not.toBeNull();
  expect(trigger!.getAttribute('aria-label')).toBe(`Export ${view.sessions.sessions[0]!.title}`);
  trigger!.click(); await settle(view);
  const panel = view.shadowRoot!.querySelector<ExportElement>('jf-sv3-conversation-export'); expect(panel).not.toBeNull();
  await settle(panel!); return panel!;
}
function button(panel: ExportElement, label: string): Button {
  const found = [...panel.shadowRoot!.querySelectorAll<Button>('jf-button')].find(el => el.textContent!.trim() === label);
  expect(found, label).toBeDefined(); return found!;
}
async function act(panel: ExportElement, label: string): Promise<void> {
  const target = button(panel, label); expect(target.disabled, label).toBe(false);
  await target.onActivate(); await settle(panel);
}
const receipt = (panel: ExportElement): string => panel.shadowRoot!.querySelector('[role="status"]')!.textContent!;

beforeEach(() => {
  __resetConversationListForTest(); __resetAiStateForTest(); __resetDraftProvidersForTest(); __resetModalityForTest();
  localStorage.clear(); sessionStorage.clear(); serial = 0;
  threadStatus = historyStatus = 200; threadGate = null; lifecycles = [];
  events = [event('idx-0', 'USER_MESSAGE', 'First question'), grounded('idx-1', 'First answer.', '/docs/first.md', 7)];
  history = { messages: [{ id: 'idx-0', role: 'user', content: 'First question' }, { id: 'idx-1', role: 'assistant', content: 'First answer.' }] };
  fetches = []; copy = vi.fn(async () => {}); clicks = []; blobs = [];
  vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText: copy } });
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input); fetches.push(url);
    if (url.includes('/api/thread/')) {
      await threadGate; return new Response(JSON.stringify({ conversationId: ID, events, lifecycles }), { status: threadStatus });
    }
    if (url.endsWith('/history')) return new Response(JSON.stringify(history), { status: historyStatus });
    if (url.includes('existsOnly')) return new Response(JSON.stringify({ exists: true }));
    if (url.includes('/api/chat/conversations?')) return new Response(JSON.stringify({ conversations: [] }));
    return new Response('{}');
  }));
  vi.spyOn(URL, 'createObjectURL').mockImplementation(blob => { blobs.push(blob as Blob); return 'blob:export'; });
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    clicks.push({ name: this.download, href: this.href });
  });
});
afterEach(() => {
  document.body.replaceChildren(); resetSearchState(); __resetConversationListForTest();
  __resetAiStateForTest(); __resetDraftProvidersForTest(); __resetModalityForTest();
  vi.unstubAllGlobals(); vi.restoreAllMocks();
});

describe('WP5.7 conversation export', () => {
  it('S571 copies Markdown with per-answer marks and sources, including inherited and excluded messages', async () => {
    events.push(grounded('idx-2', 'Another answer.', '/docs/second.md', 40));
    history.excludedMessageIds = ['idx-0', 'idx-1']; history.parentSessionId = 'parent'; history.branchPointMessageId = 'idx-1';
    const panel = await open(await mount()); await act(panel, 'Copy Markdown');
    const text = copy.mock.calls[0]![0] as string;
    for (const expected of ['# Title export-conversation', 'Branched from parent at idx-1', 'User (excluded from context)',
      'Assistant (excluded from context)', 'First answer. [1]', '[1] /docs/first.md (lines 7–9)',
      'Another answer. [1]', '[1] /docs/second.md (lines 40–42)']) expect(text).toContain(expected);
    expect(receipt(panel)).toBe('Markdown copied.');
  });
  it('S572 JSON versions mixed Ask and Delegate content and keeps each answer’s own sources', async () => {
    events.push(event('idx-2', 'USER_MESSAGE', 'Delegate work', { shapeId: 'core.agent-run' }),
      event('run-answer', 'ASSISTANT_MESSAGE', 'Delegate answer.', {
        shapeId: 'core.agent-run', sources: [{ ...source('doc-id', 20), path: 'C:/docs/delegate.md', title: 'Delegate' }],
        citations: [{ sentenceText: 'Delegate answer.', sourceIndex: 0, similarity: .99 }], citationScorer: 'CROSS_ENCODER',
      }));
    history.excludedMessageIds = ['idx-1'];
    const panel = await open(await mount()); await act(panel, 'Copy JSON');
    const data = JSON.parse(copy.mock.calls[0]![0] as string);
    expect(data.formatVersion).toBe(1); expect(data.conversationId).toBe(ID);
    expect(data.turns.map((turn: { kind: string }) => turn.kind)).toEqual(['ask', 'agent']);
    expect(data.turns[0].messages[1].excludedFromContext).toBe(true);
    expect(data.turns[0].messages[1].sources[0].path).toBe('/docs/first.md');
    expect(data.turns[1].messages[1].sources[0]).toMatchObject({ path: 'C:/docs/delegate.md', startLine: 20, endLine: 22 });
    expect(data.turns[1].messages[1].citations).toEqual([{ sentence: 'Delegate answer.', labels: [1] }]);
    expect(receipt(panel)).toBe('JSON copied.');
  });
  it('S571 labels stopped, failed and refused outcomes and omits streaming turns in both formats', async () => {
    const first = projectSv3RecordTurns(events)[0]!;
    const turns: Sv3Turn[] = ['halted', 'failed', 'refused', 'streaming'].map((status, i) => ({ ...first,
      id: `local-${i}`, recordId: null, assistantRecordId: null, question: `question-${i}`, answer: `partial-${i}`,
      answerMessages: undefined, status: status as Sv3Turn['status'], detail: status === 'failed' ? 'Network failed' : '',
    }));
    const panel = await open(await mount(session(turns))); await act(panel, 'Copy JSON');
    const data = JSON.parse(copy.mock.calls[0]![0] as string);
    expect(data.turns.map((turn: { status: string }) => turn.status)).toEqual(['complete', 'halted', 'failed', 'refused']);
    await act(panel, 'Copy Markdown'); const markdown = copy.mock.calls[1]![0] as string;
    for (const status of ['stopped', 'failed', 'refused']) expect(markdown).toContain(`; ${status})`);
    expect(markdown).toContain('Network failed'); expect(markdown).not.toContain('partial-3');
  });
  it('S573 waits for full history and includes older turns absent from the screen', async () => {
    let release!: () => void; threadGate = new Promise(resolve => { release = resolve; });
    const panel = await open(await mount(session([])));
    expect(receipt(panel)).toContain('Loading the complete'); expect(button(panel, 'Copy Markdown').disabled).toBe(true);
    expect(copy).not.toHaveBeenCalled(); release(); await settle(panel); await act(panel, 'Copy Markdown');
    expect(copy.mock.calls[0]![0]).toContain('First question');
    expect(fetches.some(url => url.endsWith(`/api/thread/${ID}`))).toBe(true);
    expect(fetches.some(url => url.endsWith(`/${ID}/history`))).toBe(true);
  });
  it.each(['thread', 'history', 'malformed', 'dropped'] as const)('S573 refuses a partial export on %s failure', async failure => {
    const view = await mount();
    if (failure === 'thread') threadStatus = 500;
    if (failure === 'history') historyStatus = 500;
    if (failure === 'malformed') history = {};
    if (failure === 'dropped') events.push({ id: 'bad' } as ThreadEvent);
    const panel = await open(view); expect(receipt(panel)).toContain('Export failed');
    expect(button(panel, 'Copy Markdown').disabled).toBe(true); expect(button(panel, 'Save JSON').disabled).toBe(true);
    expect(copy).not.toHaveBeenCalled(); expect(clicks).toHaveLength(0);
  });
  it('S574 reports clipboard rejection without a success receipt', async () => {
    copy.mockRejectedValue(new Error('clipboard denied'));
    const panel = await open(await mount()); await act(panel, 'Copy Markdown');
    expect(receipt(panel)).toContain('Copy failed'); expect(receipt(panel)).not.toBe('Markdown copied.');
  });
  it.each(['Markdown', 'JSON'])('S574 starts a %s download with exact bytes and no saved claim', async format => {
    const panel = await open(await mount()); await act(panel, `Copy ${format}`); await act(panel, `Save ${format}`);
    expect(clicks).toEqual([{ name: `justsearch-conversation-${ID}.${format === 'JSON' ? 'json' : 'md'}`, href: 'blob:export' }]);
    expect(await blobs[0]!.text()).toBe(copy.mock.calls[0]![0]);
    expect(receipt(panel)).toContain(`${format} download started`); expect(receipt(panel)).not.toContain('saved');
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:export');
  });
  it('S574 reports a download failure and cleans up its resources', async () => {
    vi.mocked(HTMLAnchorElement.prototype.click).mockImplementation(() => { throw new Error('blocked download'); });
    const panel = await open(await mount()); await act(panel, 'Save JSON');
    expect(receipt(panel)).toContain('Download failed');
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:export'); expect(document.querySelector('a[download]')).toBeNull();
  });
  it('S574 Escape cancels a pending read and never produces output after it lands', async () => {
    let release!: () => void; threadGate = new Promise(resolve => { release = resolve; });
    const toasts: string[] = []; const listener = (e: Event) => toasts.push((e as CustomEvent<{ message: string }>).detail.message);
    document.addEventListener(EPHEMERAL_TOAST_EVENT, listener);
    try {
      const panel = await open(await mount()); const dialog = panel.shadowRoot!.querySelector('dialog')!;
      dialog.dispatchEvent(new Event('cancel', { cancelable: true }));
      release(); await settle(panel); expect(toasts).toContain('Export cancelled.');
      expect(dialog.open).toBe(false); expect(copy).not.toHaveBeenCalled(); expect(clicks).toHaveLength(0);
    } finally { document.removeEventListener(EPHEMERAL_TOAST_EVENT, listener); }
  });
  it('S576 pins the conversation before a pending load and a navigation', async () => {
    let release!: () => void; threadGate = new Promise(resolve => { release = resolve; });
    const view = await mount(); const panel = await open(view); const other = session([], 'another-conversation');
    view.sessions = { activeId: other.id, sessions: [...view.sessions.sessions, other] };
    release(); await settle(panel); await act(panel, 'Copy JSON'); const data = JSON.parse(copy.mock.calls[0]![0] as string);
    expect(data.conversationId).toBe(ID); expect(data.title).toBe(`Title ${ID}`);
    expect(data.turns[0].messages[0].content).toBe('First question');
    expect(fetches.filter(url => url.includes('another-conversation'))).toEqual([]);
  });
  it.each(['empty', 'streaming'] as const)('S577 explains nothing to export for %s conversations', async kind => {
    const turns = projectSv3RecordTurns(events).map(turn => ({ ...turn, status: 'streaming' as const })); events = [];
    const panel = await open(await mount(session(kind === 'empty' ? [] : turns)));
    expect(receipt(panel)).toContain('nothing to export');
    expect(button(panel, 'Copy JSON').disabled).toBe(true); expect(button(panel, 'Save Markdown').disabled).toBe(true);
  });
  it('S578 refuses a locked response with a reason', async () => {
    const view = await mount(); historyStatus = 423; const panel = await open(view);
    expect(receipt(panel)).toContain('history is locked'); expect(receipt(panel)).toContain('Security & Privacy');
    expect(button(panel, 'Copy JSON').disabled).toBe(true); expect(clicks).toHaveLength(0);
  });
  it('S578 refuses sealed locked placeholders even in a successful history response', async () => {
    const view = await mount(); history = { messages: [{ role: 'locked', content: '[Encrypted message]', locked: true }] };
    const panel = await open(view);
    expect(receipt(panel)).toContain('history is locked');
    expect(button(panel, 'Copy Markdown').disabled).toBe(true); expect(copy).not.toHaveBeenCalled();
  });
  it('S571 omits interim Delegate answers while its saved lifecycle is still running', async () => {
    events.push(event('active-run:user', 'USER_MESSAGE', 'Still working', { shapeId: 'core.agent-run' }),
      event('interim', 'ASSISTANT_MESSAGE', 'An interim thought.', { shapeId: 'core.agent-run' }));
    lifecycles = [{ sessionId: 'active-run', state: 'RUNNING', actor: 'primary', turns: 1, iterations: 2,
      toolCalls: 0, actors: ['primary'], budget: { initial: 1000, consumed: 100, remaining: 900, overBudget: false } }];
    const panel = await open(await mount()); await act(panel, 'Copy JSON');
    const data = JSON.parse(copy.mock.calls[0]![0] as string);
    expect(data.turns).toHaveLength(1); expect(data.turns[0].messages[0].content).toBe('First question');
    expect(copy.mock.calls[0]![0]).not.toContain('An interim thought.');
  });
  it('S578 invalidates a prepared export when the store locks', async () => {
    const view = await mount(); const panel = await open(view);
    __feedForTest({ status: { conversationProtection: { state: 'locked' } } as never }); await settle(view); await settle(panel);
    expect(receipt(panel)).toContain('history is locked'); expect(button(panel, 'Copy JSON').disabled).toBe(true);
  });
  it('S579 names the dialog and buttons and releases modal ownership on close', async () => {
    const panel = await open(await mount()); const dialog = panel.shadowRoot!.querySelector('dialog')!;
    expect(dialog.open).toBe(true); expect(dialog.getAttribute('aria-labelledby')).toBe('export-heading');
    expect(dialog.getAttribute('aria-describedby')).toBe('export-receipt');
    for (const format of ['Markdown', 'JSON']) for (const action of ['Copy', 'Save']) {
      const control = button(panel, `${action} ${format}`); await control.updateComplete;
      const inner = control.shadowRoot!.querySelector<Control>('jf-control')!; await inner.updateComplete;
      expect(inner.shadowRoot!.querySelector('button')).not.toBeNull();
    }
    expect(document.documentElement.style.overflow).toBe('hidden'); await act(panel, 'Close export');
    expect(dialog.open).toBe(false); expect(document.documentElement.style.overflow).not.toBe('hidden');
  });
});

// Run the pre-existing palette route on the baseline, and the visible replacement on the candidate.
// These cases reach the old serializer too: their fail-before is the missing fact, not a missing import.
async function markdownThroughAvailableRoute(view: SearchV3View): Promise<{ text: string; receipt: string }> {
  if (view.shadowRoot!.querySelector('jf-sv3-conversation-export')) {
    const panel = await open(view); await act(panel, 'Copy Markdown');
    return { text: copy.mock.calls[0]?.[0] as string ?? '', receipt: receipt(panel) };
  }
  view.shadowRoot!.querySelector('jf-sv3-palette')!.dispatchEvent(new CustomEvent('sv3-palette-run', {
    bubbles: true, composed: true, detail: { id: 'cmd-export-markdown' },
  }));
  await settle(view);
  return { text: copy.mock.calls[0]?.[0] as string ?? '', receipt: view.shadowRoot!.textContent ?? '' };
}
it('S571 baseline reproduction retains source labels and locations per answer', async () => {
  const { text } = await markdownThroughAvailableRoute(await mount());
  expect(text).toContain('First answer. [1]'); expect(text).toContain('/docs/first.md (lines 7–9)');
});
it.each(['halted', 'failed', 'refused'] as const)('S571 baseline reproduction labels %s instead of a complete answer', async status => {
  const first = projectSv3RecordTurns(events)[0]!;
  const value = session([{ ...first, status, detail: 'Outcome detail' }]);
  const { text } = await markdownThroughAvailableRoute(await mount(value));
  expect(text).toContain(`; ${status === 'halted' ? 'stopped' : status})`);
  expect(text).toContain('Outcome detail');
});
it('S573 baseline reproduction includes old saved turns absent from the view', async () => {
  events.push(event('idx-2', 'USER_MESSAGE', 'Recent question'), event('idx-3', 'ASSISTANT_MESSAGE', 'Recent answer.'));
  const tail = projectSv3RecordTurns(events).slice(-1);
  const { text } = await markdownThroughAvailableRoute(await mount(session(tail)));
  expect(text).toContain('First question'); expect(text).toContain('Recent question');
});
it('S574 baseline reproduction reports a failed clipboard write', async () => {
  copy.mockRejectedValue(new Error('denied'));
  const result = await markdownThroughAvailableRoute(await mount());
  expect(result.receipt).toContain('Copy failed'); expect(result.receipt).not.toBe('Markdown copied.');
});
