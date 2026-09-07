// @vitest-environment happy-dom

/**
 * Slice 516 FIX-T5 — UnifiedChatView abort-on-conversation-switch test.
 *
 * The bug: send()'s in-flight SSE stream wrote its onDone assistant
 * message into the thread that was current AT onDone time. If the user
 * switched conversations mid-stream, the message landed in the wrong
 * thread. FIX-T1 makes loadConversation/newConversation call
 * abortController.abort() so the stream never reaches onDone after a
 * conversation switch.
 *
 * This test verifies the abort-call contract directly. A full SSE
 * simulation would require mocking consumeShapeStream + the streams.ts
 * pipeline; the behavioural guarantee (abort fires on switch) is what
 * we need to lock in.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { LitElement } from 'lit';
import './UnifiedChatView.js';
import type { UnifiedChatView } from './UnifiedChatView.js';
import { setPendingAutoRun, setPendingForceShape, takePendingAutoRun, takePendingForceShape, takePendingSelection } from '../utils/compose.js';
import { SHAPE_LABELS, type ShapeId } from './unifiedChatRequest.js';
import { unifiedChatBodyStyles } from './unifiedChatStyles.js';
import { Control } from '../components/Control.js';
import { ModalityController, __resetModalityForTest } from '../primitives/modality.js';
// Search Thread Round-2 R2 — namespace import so `compose` can be spied on directly (the shift-held
// Ask AI staging test asserts the view calls the SAME compose() seam the pre-round-2 behavior used).
import * as composeModule from '../utils/compose.js';
import { restoreUnifiedChat, resetUnifiedChatState, getUnifiedChatState } from '../state/unifiedChatState.js';
import { consumeShapeStream, dispatchShapeEventToHandlers } from '../../api/streams.js';
// Tempdoc 822 §3d — the provenance-split assertions read the accumulator's own claim shape.
import type { Claim } from '../components/chat/citationTypes.js';
// Tempdoc 847 S1 — the record→evidence conversion these cases exercise is no longer a private method
// on this view: it is the SHARED authority both render paths read (`recordEvidence.ts`), so the
// assertions address it where it now lives. The cases themselves are unchanged — the same payloads,
// the same expectations — because the extraction had to be behaviour-identical.
import { claimsFromRecord, matchesFromRecord } from '../components/chat/recordEvidence.js';
// Tempdoc 609 — value imports for the 609 describes (modules are vi.mock'd below; vi.mocked() wraps them).
import { resumeConversation } from '../state/conversationListStore.js';
import { setAiActivity } from '../state/aiStateStore.js';
import { setLastViewedConversation, clearLastViewedConversation } from '../controllers/lastViewedConversation.js';
import {
  getSelectedSource,
  setSelectedSource,
  sourceKey,
  __resetSelectedSource,
} from '../state/selectedSource.js';
// Tempdoc 814 (finding 7) — the thread's background-run pointer drives this store.
import {
  isRetrospectiveOpen,
  takeRequestedTab,
  __resetRetrospectiveDrawer,
} from '../state/retrospectiveDrawer.js';
import {
  getAgentSessionController,
  __resetAgentSessionStore,
} from '../state/agentSessionStore.js';
import {
  getSelection,
  setSingleSelection,
  __resetSelectionForTest,
} from '../state/selectionState.js';
// Search Thread S6 — the reading-pane wiring tests exercise the REAL (unmocked) inspectorState store
// (the "open a document for reading" signal every card-open/citation-click flow funnels through) and a
// mock PluginHostApi (the internal openRetrieveHit/handleCommittedCardOpen call sites route through
// `host.ui.showInspector`, which in production is the plugin-API's own showInspector — see ui.ts).
import { setSelected, resetInspectorState, getInspectorState } from '../state/inspectorState.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
// Search Thread Round-2 R1a — the degradation banner's persisted "seen cause-set" bookmark lives in
// the REAL userConfig document (not mocked); tests reset it so each case starts as an unseen (first-
// sighting) cause-set, matching the pre-round-2 always-expanded assertions these tests carry forward.
import { __resetUserConfigForTest } from '../state/userConfigState.js';
import { known, UNKNOWN } from '../state/known.js';
import { setUiMode, __resetUiModeForTest } from '../state/uiModeState.js';

// Need to mock aiStateStore so connectedCallback doesn't try to start it
// against a real api.
const AI_STATE_READY = {
  capabilities: { chat: true, rag: true, extract: false, embedding: false },
  activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
  // Tempdoc 807 A.3 — "READY" means the backend is answering, so the snapshot is a LIVE observation.
  // `projectAvailability` now gates on this first (a dead backend leaves `capabilities.chat` true off
  // the retained snapshot), so a fixture that omits it would describe a disconnected backend.
  snapshotLive: true,
};
vi.mock('../state/aiStateStore.js', () => ({
  startAiStateStore: vi.fn(),
  stopAiStateStore: vi.fn(),
  // Fire the listener once synchronously with a chat-capable state so
  // this.aiState is populated at connect (the real store does the same on
  // first SSE frame). Existing tests are unaffected: maybeAutoRun is a no-op
  // unless an `answer` verb parked the auto-run flag.
  subscribeAiState: vi.fn((listener: (s: unknown) => void) => {
    listener(AI_STATE_READY);
    return () => {};
  }),
  setAiActivity: vi.fn(),
  getAiState: () => AI_STATE_READY,
}));

// Mock the streaming layer so consumeShapeStream can be controlled
// per-test (slice 517 FIX-U2). The default mock returns a never-resolving
// Promise so the test can simulate a stream in flight; individual tests
// override via vi.mocked() if they need a different shape.
vi.mock('../../api/streams.js', () => ({
  consumeShapeStream: vi.fn(
    (_url: string, _body: unknown, _onEvent: unknown, _signal?: AbortSignal) =>
      new Promise<void>(() => { /* never resolves */ }),
  ),
  dispatchShapeEventToHandlers: vi.fn(),
  // Tempdoc 859 §A — the run-identity event NAME. Real value, not a stand-in: it is read at module
  // scope by `AgentSessionController`'s reasoning-boundary exempt set (a name that must never cut a
  // region), and a mock that omitted it left the whole module unloadable.
  RUN_STARTED_EVENT: 'run_started',
}));

// Tempdoc 577 Goal 3 — control the retrieve base tier's search store. The view subscribes in
// connectedCallback; we capture the listener so a test can push fabricated search snapshots
// without touching the network (setQuery/submitSearch would otherwise issue a real fetch).
let searchListener: ((s: unknown) => void) | null = null;
const SEARCH_EMPTY = {
  query: '',
  results: [],
  totalHits: 0,
  isSearching: false,
  processingTimeMs: null,
  error: null,
  searchTrace: null,
};
// Search Thread D5 (stage S3) — control the scope-chip store the same way as the search store above:
// capture the subscribed listener so a test can push fabricated chip snapshots, and stub the mutators
// so we can assert on how the view calls them without touching the real module-level array.
let scopeChipsListener: ((chips: unknown) => void) | null = null;
const scopeChipsMock = {
  chips: [] as Array<{ kind: string; label: string; docIds: readonly string[] }>,
};
// S5b pin-parity — controllable pinned-search store mock (subscribe fires immediately, real-store
// parity). vi.hoisted: the factory below executes before top-level consts would initialize.
const pinsCtl = vi.hoisted(() => {
  const state = {
    listener: null as ((pins: unknown) => void) | null,
    pins: [] as Array<{ id: string; query: string; pinnedAt: number; runs: unknown[] }>,
  };
  return {
    state,
    reset(pins: Array<{ id: string; query: string; pinnedAt: number; runs: unknown[] }>) {
      state.pins = pins;
    },
    pinSearch: (query: string) => {
      state.pins = [...state.pins, { id: 'pin-' + query, query, pinnedAt: 1, runs: [] }];
      state.listener?.(state.pins);
      return state.pins[state.pins.length - 1];
    },
    unpinSearch: (id: string) => {
      state.pins = state.pins.filter((p) => p.id !== id);
      state.listener?.(state.pins);
      return true;
    },
  };
});
const pinSearchMock = vi.fn(pinsCtl.pinSearch);
const unpinSearchMock = vi.fn(pinsCtl.unpinSearch);
const recordRunMock = vi.fn();
vi.mock('../state/pinnedSearchState.js', () => ({
  subscribePinnedSearches: (listener: (pins: unknown) => void) => {
    pinsCtl.state.listener = listener;
    listener(pinsCtl.state.pins);
    return () => {
      pinsCtl.state.listener = null;
    };
  },
  getPinnedSearches: () => pinsCtl.state.pins,
  isPinned: (q: string) => pinsCtl.state.pins.some((p) => p.query === q.trim()),
  pinSearch: (q: string) => pinSearchMock(q),
  unpinSearch: (id: string) => unpinSearchMock(id),
  recordRun: (q: string, t: number) => recordRunMock(q, t),
}));

vi.mock('../state/searchState.js', () => ({
  subscribeSearch: vi.fn((listener: (s: unknown) => void) => {
    searchListener = listener;
    listener(SEARCH_EMPTY);
    return () => {
      searchListener = null;
    };
  }),
  setQuery: vi.fn(),
  submitSearch: vi.fn(),
  setSearchApiBase: vi.fn(),
  recordOpenDisposition: vi.fn(),
  getSearchState: vi.fn(() => SEARCH_EMPTY),
  subscribeScopeChips: vi.fn((listener: (chips: unknown) => void) => {
    scopeChipsListener = listener;
    listener(scopeChipsMock.chips);
    return () => {
      scopeChipsListener = null;
    };
  }),
  addScopeChip: vi.fn(
    (chip: { kind: string; label: string; docIds: readonly string[] }) => {
      scopeChipsMock.chips = [...scopeChipsMock.chips, chip];
      scopeChipsListener?.(scopeChipsMock.chips);
    },
  ),
  removeScopeChip: vi.fn((index: number) => {
    scopeChipsMock.chips = scopeChipsMock.chips.filter((_, i) => i !== index);
    scopeChipsListener?.(scopeChipsMock.chips);
  }),
}));

// Mock the network layer so resumeConversation + fetchMessageIds don't
// hit real endpoints.
vi.mock('../state/conversationListStore.js', async () => {
  const actual = await vi.importActual<typeof import('../state/conversationListStore.js')>(
    '../state/conversationListStore.js',
  );
  return {
    ...actual,
    setConversationApiBase: vi.fn(),
    resumeConversation: vi.fn(async (sessionId: string, shapeId: string) => ({
      sessionId,
      shapeId,
      messages: [],
    })),
    fetchMessageIds: vi.fn(async () => null),
    branchConversation: vi.fn(async () => 'uc-branch-new'),
    generateConversationTitle: vi.fn(),
    getRecentSessions: vi.fn(() => []),
    recordRecentSession: vi.fn(),
    createConversationId: () => 'uc-test-' + Math.random().toString(16).slice(2),
    exportConversationMarkdown: vi.fn(() => ''),
    editContextFloorSummary: vi.fn(async () => true),
    setMessageExcluded: vi.fn(async () => true),
    setSourceExcluded: vi.fn(async () => true),
  };
});

function mountView(): UnifiedChatView {
  document.body.innerHTML = '<jf-shell></jf-shell>';
  const view = document.createElement('jf-unified-chat-view') as UnifiedChatView;
  view.apiBase = 'http://localhost:5173';
  document.body.appendChild(view);
  return view;
}

// Tempdoc 738 — uiMode is a module-level store shared across tests; reset it after every test so a
// block that sets Detailed mode cannot leak into a later block that expects the Simple default.
afterEach(() => {
  __resetUiModeForTest();
  // 864 Layer 2(d) — modality depth is module state and outlives the DOM.
  __resetModalityForTest();
});

/**
 * Tempdoc 814 §D6 — window HEIGHT is now a real input to what this view renders (the block-axis
 * breakpoint gates Detailed-mode banner expansion). happy-dom's virtual window is 1024x768 — i.e.
 * already BELOW the 820px breakpoint — so leaving it implicit would silently run the whole file in
 * the short branch. The suite therefore DECLARES its viewport: tall (above-breakpoint, the roomy
 * case the pre-814 assertions describe) by default; the cases that exercise the yield set their own.
 */
const TALL_VIEWPORT_PX = 1000;
const SHORT_VIEWPORT_PX = 700;
function setViewportHeight(px: number): void {
  (
    window as unknown as { happyDOM: { setViewport: (v: { height: number }) => void } }
  ).happyDOM.setViewport({ height: px });
}
beforeEach(() => setViewportHeight(TALL_VIEWPORT_PX));

describe('UnifiedChatView — 637 #1 disconnected banner tone (Fix 1)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    __resetUserConfigForTest();
  });

  it('renders the disconnected banner with the verdict-SEVERITY tone (unreachable ⇒ error), not a hardcoded warning — matching SearchSurface', async () => {
    const view = mountView();
    await view.updateComplete;
    // Drive the ONE verdict the chat banner consumes to `unreachable` (the dead-binding state).
    (view as unknown as { aiState: unknown }).aiState = {
      ...AI_STATE_READY,
      verdict: { kind: 'unreachable', severity: 'error', reasons: ['binding.unreachable'] },
    };
    view.requestUpdate();
    await view.updateComplete;
    const banner = view.shadowRoot?.querySelector('[data-testid="chat-degradation"]');
    expect(banner).not.toBeNull();
    // The fix: tone tracks verdict severity (error/red), so chat and search cannot disagree.
    expect(banner?.getAttribute('tone')).toBe('error');
    expect(banner?.textContent).toContain('Backend disconnected');
  });
});

// Search Thread S5b — SearchSurface (the retired standalone Search rail surface) had its own B3
// retrieval-degraded banner (testid="search-degradation"), but it read the SAME ONE verdict/
// readinessNotice authority (aiStateStore → verdict.ts → readinessNotice) the chat banner already
// renders unconditionally (testid="chat-degradation", every affordance tier — see render()). These
// port SearchSurface.degradation.test.ts's scenarios onto that one shared banner, proving the
// retrieve tier gets the identical worded notice with no separate banner needed.
describe('UnifiedChatView retrieve-tier degradation banner (ports SearchSurface.degradation.test.ts, Search Thread S5b)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    __resetUserConfigForTest();
    __resetUiModeForTest();
  });

  function setVerdict(view: UnifiedChatView, verdict: { kind: string; severity: string; reasons: string[] }): void {
    (view as unknown as { aiState: unknown }).aiState = { ...AI_STATE_READY, verdict };
    view.affordance = 'retrieve';
    view.requestUpdate();
  }

  it('621/595 — an impairing degradation renders "Semantic search degraded" (warn) while on the retrieve tier', async () => {
    const view = mountView();
    await view.updateComplete;
    setVerdict(view, { kind: 'degraded', severity: 'warn', reasons: ['worker.health.embedding_not_ready'] });
    await view.updateComplete;
    const banner = view.shadowRoot?.querySelector('[data-testid="chat-degradation"]');
    expect(banner).not.toBeNull();
    expect(banner?.textContent).toContain('Semantic search degraded');
    const op = view.shadowRoot?.querySelector('[data-testid="chat-degradation-remedy-op"]');
    expect(op?.getAttribute('operation-id')).toBe('core.trigger-offline-processing');
  });

  it('round-14 finding 9 — an INFO-severity-only verdict renders NO banner-tier warning', async () => {
    // Supersedes the 595 §10.3 assertion that this same verdict renders a calm "Reduced search
    // capability" banner. 595's fix was the WORDING (never "keyword results" for a re-ranking gap);
    // round 14 measured the remaining defect as the TIER: a permanent, unconfigurable optional gap
    // held ~25% of the space above the fold behind an alert triangle, in the same slot a genuine
    // retrieval failure uses. Health still carries the cause (HealthSurface.render.test.ts).
    const view = mountView();
    await view.updateComplete;
    setVerdict(view, { kind: 'degraded', severity: 'info', reasons: ['lambdamart.not_configured'] });
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('[data-testid="chat-degradation"]')).toBeNull();
  });

  it('round-14 finding 9 — the same cause at WARN severity still gets the banner (the tier gate is severity, not the cause)', async () => {
    // Precision guard: proves the suppression above is not "the banner stopped rendering at all".
    const view = mountView();
    await view.updateComplete;
    setVerdict(view, {
      kind: 'degraded',
      severity: 'warn',
      reasons: ['lambdamart.not_configured', 'worker.health.embedding_not_ready'],
    });
    await view.updateComplete;
    const banner = view.shadowRoot?.querySelector('[data-testid="chat-degradation"]');
    expect(banner).not.toBeNull();
    expect(banner?.getAttribute('tone')).toBe('warning');
  });

  it('600 Design A — a compat-blocked index renders "Reindex required" naming the specific cause + the rebuild remedy', async () => {
    setUiMode('advanced'); // Tempdoc 738 — the specific cause bullets render in Detailed mode.
    const view = mountView();
    await view.updateComplete;
    // Two reindex causes (Round-2 R1a only dedups a SOLE cause bullet against the headline —
    // see the dedicated dedup test below), so the specific-cause bullet still renders here.
    setVerdict(view, {
      kind: 'degraded',
      severity: 'warn',
      reasons: ['index.blocked_legacy', 'index.schema_mismatch'],
    });
    await view.updateComplete;
    const banner = view.shadowRoot?.querySelector('[data-testid="chat-degradation"]');
    const text = banner?.textContent ?? '';
    expect(text).toContain('Reindex required');
    expect(text).toContain('built before semantic search was available');
    const op = view.shadowRoot?.querySelector('[data-testid="chat-degradation-remedy-op"]');
    expect(op?.getAttribute('operation-id')).toBe('core.rebuild-index');
  });

  it('falls back to the Open Health navigate remedy for an unknown/empty cause', async () => {
    const view = mountView();
    await view.updateComplete;
    setVerdict(view, { kind: 'degraded', severity: 'warn', reasons: [] });
    await view.updateComplete;
    const nav = view.shadowRoot?.querySelector('[data-testid="chat-degradation-remedy-nav"]');
    expect(nav).not.toBeNull();
    expect(nav?.textContent).toContain('Open Health');
  });
});

// Tempdoc 738 — the degradation banner's disclosure projects from the app-wide Simple/Detailed mode
// (uiMode), not a per-cause-set seen-hash. Simple (default) is the one-line pill; Detailed shows the
// raw causes; a severe (error) verdict opens expanded even in Simple; a local "See details" chevron
// opens a cosmetic notice on demand.
describe('UnifiedChatView degradation banner disclosure (Tempdoc 738)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    __resetUserConfigForTest();
    __resetUiModeForTest();
  });

  function setVerdict(view: UnifiedChatView, verdict: { kind: string; severity: string; reasons: string[] }): void {
    (view as unknown as { aiState: unknown }).aiState = { ...AI_STATE_READY, verdict };
    view.affordance = 'retrieve';
    view.requestUpdate();
  }

  it('Simple mode (default): a warn degradation renders the collapsed pill (headline + remedy, no raw causes)', async () => {
    const view = mountView();
    await view.updateComplete;
    setVerdict(view, { kind: 'degraded', severity: 'warn', reasons: ['worker.health.embedding_not_ready'] });
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('[data-testid="chat-degradation-causes"]')).toBeNull();
    const summary = view.shadowRoot?.querySelector('[data-testid="chat-degradation-summary"]');
    expect(summary?.textContent).toContain('1 cause');
    expect(summary?.textContent).toContain('Semantic search degraded');
    // The strongest remedy stays reachable even collapsed.
    expect(
      view.shadowRoot?.querySelector('[data-testid="chat-degradation-remedy-op"]')?.getAttribute('operation-id'),
    ).toBe('core.trigger-offline-processing');
  });

  it('Detailed mode: the same degradation renders expanded with the raw causes', async () => {
    setUiMode('advanced');
    const view = mountView();
    await view.updateComplete;
    setVerdict(view, { kind: 'degraded', severity: 'warn', reasons: ['worker.health.embedding_not_ready'] });
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('[data-testid="chat-degradation-causes"]')).not.toBeNull();
  });

  it('a severe (error) verdict opens expanded even in Simple, with no collapse chevron', async () => {
    const view = mountView();
    await view.updateComplete;
    setVerdict(view, { kind: 'degraded', severity: 'error', reasons: ['worker.restart_exhausted'] });
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('[data-testid="chat-degradation-causes"]')).not.toBeNull();
    expect(view.shadowRoot?.querySelector('[data-testid="chat-degradation-collapse"]')).toBeNull();
  });

  it('the local "See details" chevron opens a collapsed banner; the collapse chevron closes it (Simple)', async () => {
    const view = mountView();
    await view.updateComplete;
    setVerdict(view, { kind: 'degraded', severity: 'warn', reasons: ['worker.health.embedding_not_ready'] });
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('[data-testid="chat-degradation-causes"]')).toBeNull();

    (view.shadowRoot?.querySelector('[data-testid="chat-degradation-expand"]') as HTMLButtonElement).click();
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('[data-testid="chat-degradation-causes"]')).not.toBeNull();

    (view.shadowRoot?.querySelector('[data-testid="chat-degradation-collapse"]') as HTMLButtonElement).click();
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('[data-testid="chat-degradation-causes"]')).toBeNull();
  });

  // Tempdoc 814 §D2/§D6 — Detailed mode buys its extra height from the conversation, and below the
  // block-axis breakpoint there is none to buy: the same verdict renders the pill first and expands
  // on interaction. The 600 wording invariant is unaffected (headline + remedy stay in the pill,
  // every cause one click away) — this is a height policy, not a wording one.
  it('below the block-axis breakpoint, Detailed renders the COLLAPSED pill with a working expand affordance', async () => {
    setViewportHeight(SHORT_VIEWPORT_PX);
    setUiMode('advanced');
    const view = mountView();
    await view.updateComplete;
    setVerdict(view, { kind: 'degraded', severity: 'warn', reasons: ['worker.health.embedding_not_ready'] });
    await view.updateComplete;
    // Collapsed: the raw causes are not in flow …
    expect(view.shadowRoot?.querySelector('[data-testid="chat-degradation-causes"]')).toBeNull();
    // … the worded headline and the strongest remedy still are …
    expect(
      view.shadowRoot?.querySelector('[data-testid="chat-degradation-summary"]')?.textContent,
    ).toContain('Semantic search degraded');
    expect(
      view.shadowRoot?.querySelector('[data-testid="chat-degradation-remedy-op"]')?.getAttribute('operation-id'),
    ).toBe('core.trigger-offline-processing');
    // … and the detail is one click away, not gone.
    const expand = view.shadowRoot?.querySelector(
      '[data-testid="chat-degradation-expand"]',
    ) as HTMLButtonElement | null;
    expect(expand).not.toBeNull();
    expand!.click();
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('[data-testid="chat-degradation-causes"]')).not.toBeNull();
  });

  it('a severe (error) verdict still forces expansion below the breakpoint — the height gate is not a severity gate', async () => {
    // Precision guard for the case above: proves the short-viewport branch collapses Detailed's
    // DISCLOSURE choice, not a genuine failure that the user must be able to read without a click.
    setViewportHeight(SHORT_VIEWPORT_PX);
    setUiMode('advanced');
    const view = mountView();
    await view.updateComplete;
    setVerdict(view, { kind: 'degraded', severity: 'error', reasons: ['worker.restart_exhausted'] });
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('[data-testid="chat-degradation-causes"]')).not.toBeNull();
    expect(view.shadowRoot?.querySelector('[data-testid="chat-degradation-collapse"]')).toBeNull();
  });

  it('drops the single reindex cause bullet (dedup by code) when expanded — the headline already says it', async () => {
    setUiMode('advanced');
    const view = mountView();
    await view.updateComplete;
    setVerdict(view, { kind: 'degraded', severity: 'warn', reasons: ['index.blocked_legacy'] });
    await view.updateComplete;
    // Expanded (Detailed), but the sole redundant bullet is dropped: no <ul> renders.
    expect(view.shadowRoot?.querySelector('[data-testid="chat-degradation-causes"]')).toBeNull();
    expect(view.shadowRoot?.querySelector('.degradation-banner')?.textContent).toContain('Reindex required');
  });
});

// Round-14 finding 14 — the header control set is RUNG-INVARIANT. New chat + Export were gated by
// `!agentMode`, so crossing to Delegate removed both (GUI-verified in both directions at an identical
// 1462x800 with ~1000px of free header space, so responsive overflow was ruled out), stranding a
// finished, unresumable run with neither a reset nor a save affordance. Nothing in the code or the
// design history justified the gate.
describe('UnifiedChatView header controls are rung-invariant (round-14 finding 14)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    __resetUserConfigForTest();
    __resetUiModeForTest();
  });

  const RUNGS: Array<UnifiedChatView['affordance']> = ['retrieve', 'documents', 'extract', 'agent'];

  it('renders the SAME header controls on all four rungs for the same thread state', async () => {
    for (const rung of RUNGS) {
      const view = mountView();
      await view.updateComplete;
      (view as unknown as { thread: unknown[] }).thread = [
        { role: 'user', content: 'q', shapeId: 'core.free-chat' },
        { role: 'assistant', content: 'a', shapeId: 'core.free-chat' },
      ];
      view.affordance = rung;
      view.requestUpdate();
      await view.updateComplete;
      const labels = [...view.shadowRoot!.querySelectorAll('.header .new-chat-btn')].map((b) =>
        (b.textContent ?? '').trim(),
      );
      expect(labels, `rung ${rung}`).toEqual(['Activity', 'New chat', 'Export']);
    }
  });

  it('the surviving gate is thread state, not the rung — an empty thread hides Export (nothing to export) but keeps New chat visible+disabled on EVERY rung alike', async () => {
    // Precision guard: proves the assertion above is not "these buttons always render".
    for (const rung of RUNGS) {
      const view = mountView();
      await view.updateComplete;
      (view as unknown as { thread: unknown[] }).thread = [];
      view.affordance = rung;
      view.requestUpdate();
      await view.updateComplete;
      const labels = [...view.shadowRoot!.querySelectorAll('.header .new-chat-btn')].map((b) =>
        (b.textContent ?? '').trim(),
      );
      expect(labels, `rung ${rung}`).toEqual(['Activity', 'New chat']);
    }
  });
});

// Tempdoc 821 §4 — New chat used to be hidden ENTIRELY on a fresh/empty chat (thread.length > 0
// gate), leaving no visible entry point. It now always renders, aria-disabled (typed availability,
// 596 face 1.1) when there is nothing to reset (empty thread) and operable once the thread has
// content — the .ver-nav disabled idiom, with the reason reachable via aria-describedby instead of
// a `title` a browser hides on a disabled control.
describe('UnifiedChatView "New chat" control (tempdoc 821 §4, typed availability 596)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    __resetUserConfigForTest();
    __resetUiModeForTest();
  });

  function newChatControl(view: UnifiedChatView): Element | null {
    return [...view.shadowRoot!.querySelectorAll('.header .new-chat-btn')].find(
      (b) => (b.textContent ?? '').trim() === 'New chat',
    ) ?? null;
  }

  async function innerButton(control: Element): Promise<HTMLButtonElement> {
    await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    return control.shadowRoot!.querySelector('[part="control"]') as HTMLButtonElement;
  }

  it('renders New chat aria-disabled with a REACHABLE reason (not a suppressed title) when the thread is empty', async () => {
    const view = mountView();
    await view.updateComplete;
    (view as unknown as { thread: unknown[] }).thread = [];
    view.requestUpdate();
    await view.updateComplete;
    const control = newChatControl(view);
    expect(control).not.toBeNull();
    const btn = await innerButton(control!);
    expect(btn.getAttribute('aria-disabled')).toBe('true');
    // The whole point of the fix: the reason is reachable via aria-describedby, not a `title` a
    // browser never renders on a disabled control.
    const describedBy = btn.getAttribute('aria-describedby');
    expect(describedBy).toBeTruthy();
    const reasonEl = control!.shadowRoot!.getElementById(describedBy as string);
    expect(reasonEl?.textContent).toBe('Already a new chat');
  });

  it('renders New chat operable (no aria-disabled) once the thread has content', async () => {
    const view = mountView();
    await view.updateComplete;
    (view as unknown as { thread: unknown[] }).thread = [
      { role: 'user', content: 'q', shapeId: 'core.free-chat' },
      { role: 'assistant', content: 'a', shapeId: 'core.free-chat' },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const control = newChatControl(view);
    expect(control).not.toBeNull();
    const btn = await innerButton(control!);
    expect(btn.getAttribute('aria-disabled')).not.toBe('true');
  });

  // Precision guard for the two tests above. Both are satisfied by a control that renders the right
  // ARIA and does NOTHING — which is exactly what a jf-control conversion that dropped the action
  // wiring would look like. So assert the conversion at the seam that actually matters: activating
  // it resets the thread, and activating it while unavailable does not.
  it('activating the operable control starts a new conversation; activating it while unavailable does not', async () => {
    const view = mountView();
    await view.updateComplete;
    (view as unknown as { thread: unknown[] }).thread = [
      { role: 'user', content: 'q', shapeId: 'core.free-chat' },
      { role: 'assistant', content: 'a', shapeId: 'core.free-chat' },
    ];
    view.requestUpdate();
    await view.updateComplete;
    (await innerButton(newChatControl(view)!)).click();
    await view.updateComplete;
    expect((view as unknown as { thread: unknown[] }).thread).toEqual([]);

    // Now empty, so the control is unavailable: a soft-unavailable jf-control must BLOCK, not
    // silently run the action behind an aria-disabled that only looks like a guard.
    (view as unknown as { thread: unknown[] }).thread = [
      { role: 'user', content: 'kept', shapeId: 'core.free-chat' },
    ];
    const stale = newChatControl(view)!;
    await view.updateComplete;
    (view as unknown as { thread: unknown[] }).thread = [];
    view.requestUpdate();
    await view.updateComplete;
    (view as unknown as { inputDraft: string }).inputDraft = 'not cleared';
    (await innerButton(stale)).click();
    await view.updateComplete;
    expect((view as unknown as { inputDraft: string }).inputDraft).toBe('not cleared');
  });
});

// Typed availability makes the unavailable control FOCUSABLE (aria-disabled, not native [disabled]),
// which only helps if the focus ring survives. The outer-tree `::part(control)` rules are the one
// thing that can silently blank it: a ::part declaration from the consuming tree beats the
// primitive's own inner-tree rule regardless of specificity, so a stray `all:` or `outline:` there
// reaches into jf-control and neutralises `button:focus-visible` (Control.ts). jsdom resolves
// neither ::part nor adopted-stylesheet cascade, so this is pinned as style TEXT.
describe('UnifiedChatView .new-chat-btn ::part styling does not blank the primitive focus ring', () => {
  /** `[selectorList, declarationBlock]` for every top-level rule in a Lit stylesheet. */
  function rulesOf(cssText: string): Array<[string, string]> {
    const stripped = cssText.replace(/\/\*[\s\S]*?\*\//g, '');
    return [...stripped.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map(
      (m) => [(m[1] as string).trim(), (m[2] as string).trim()] as [string, string],
    );
  }
  const declaredProps = (block: string): string[] =>
    [...block.matchAll(/(^|;)\s*([\w-]+)\s*:/g)].map((m) => (m[2] as string).toLowerCase());

  const partRules = rulesOf(unifiedChatBodyStyles.cssText).filter(([sel]) =>
    sel.includes('jf-control.new-chat-btn::part(control)'),
  );

  it('styles the composed control through ::part at all (anti-vacuity for the rules below)', () => {
    expect(partRules.length).toBeGreaterThan(0);
  });

  it.each(['all', 'outline', 'outline-style', 'outline-width', 'outline-color'])(
    'no ::part(control) rule declares `%s`',
    (prop) => {
      const offenders = partRules.filter(([, block]) => declaredProps(block).includes(prop));
      expect(offenders.map(([sel]) => sel)).toEqual([]);
    },
  );

  it('the native <button> form keeps its own `all: unset`, which is what the ::part rule must not share', () => {
    // Precision guard: the rules above would also pass if `all: unset` had simply been deleted
    // outright, which would leave the native Activity/Export buttons wearing UA chrome.
    const nativeReset = rulesOf(unifiedChatBodyStyles.cssText).filter(
      ([sel, block]) =>
        sel.includes('button.new-chat-btn') &&
        !sel.includes('::part') &&
        declaredProps(block).includes('all'),
    );
    expect(nativeReset.length).toBeGreaterThan(0);
  });

  it('the primitive still supplies the focus ring the rules above are protecting', () => {
    // The other half of the invariant: if Control.ts stopped drawing a ring, the assertions above
    // would keep passing while the composed control lost focus indication entirely.
    const sheets = Array.isArray(Control.styles) ? Control.styles : [Control.styles];
    const css = sheets.map((s) => (s as { cssText: string }).cssText).join('\n');
    const focusRule = rulesOf(css).find(([sel]) => sel.includes('button:focus-visible'));
    expect(focusRule, 'Control.ts declares no button:focus-visible rule').toBeDefined();
    expect(declaredProps((focusRule as [string, string])[1])).toContain('outline');
  });
});

// Search Thread S5b — ports SearchSurface.stateRetention.test.ts's selection-retention scenarios
// (the standalone Search surface's `applySelection` re-publish/inspector-reopen) onto the retrieve
// tier's `republishRetrieveSelection` (called from connectedCallback).
describe('UnifiedChatView retrieve-tier selection retention (ports SearchSurface.stateRetention.test.ts, Search Thread S5b)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    __resetSelectionForTest();
  });

  const HITS = [
    { id: 'h1', title: 'Q1 invoice', path: '/docs/q1.md', snippet: 'total due', kind: 'markdown' },
    { id: 'h2', title: 'helper.ts', path: '/src/helper.ts', snippet: 'function pay()', kind: 'code' },
  ];
  function snapshotWith(results: typeof HITS): UnifiedChatView['searchSnapshot'] {
    return {
      query: 'invoice',
      results,
      totalHits: results.length,
      isSearching: false,
      processingTimeMs: 12,
      error: null,
      searchTrace: null,
    } as unknown as UnifiedChatView['searchSnapshot'];
  }

  it('re-publishes a retained single-hit selection to the GLOBAL selectionState on reconnect', () => {
    const view = mountView();
    (view as unknown as { searchSnapshot: unknown }).searchSnapshot = snapshotWith(HITS);
    (view as unknown as { retrieveSelectedIds: ReadonlySet<string> }).retrieveSelectedIds = new Set(['h2']);
    // The Shell clears the GLOBAL selection on surface change — simulate that gap before reconnect.
    __resetSelectionForTest();

    (view as unknown as { republishRetrieveSelection: () => void }).republishRetrieveSelection();

    const sel = getSelection();
    expect(sel.surfaceId).toBe('core.unified-chat-surface');
    expect(sel.items).toEqual([
      expect.objectContaining({ kind: 'search-hit', hitId: 'h2', path: '/src/helper.ts' }),
    ]);
    view.remove();
  });

  it('drops a stale id no longer present in the current snapshot instead of republishing garbage', () => {
    const view = mountView();
    (view as unknown as { searchSnapshot: unknown }).searchSnapshot = snapshotWith(HITS);
    (view as unknown as { retrieveSelectedIds: ReadonlySet<string> }).retrieveSelectedIds = new Set(['gone']);
    __resetSelectionForTest();

    (view as unknown as { republishRetrieveSelection: () => void }).republishRetrieveSelection();

    expect(getSelection().items).toEqual([]);
    expect((view as unknown as { retrieveSelectedIds: ReadonlySet<string> }).retrieveSelectedIds.size).toBe(0);
    view.remove();
  });

  it('is a no-op when there is no held selection', () => {
    const view = mountView();
    (view as unknown as { searchSnapshot: unknown }).searchSnapshot = snapshotWith(HITS);
    __resetSelectionForTest();

    expect(() =>
      (view as unknown as { republishRetrieveSelection: () => void }).republishRetrieveSelection(),
    ).not.toThrow();
    expect(getSelection().items).toEqual([]);
    view.remove();
  });
});

describe('UnifiedChatView one-window agent affordance (561 P-B3)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
  });

  it('renders ONE conversation body — the agent run is inline, no separate <jf-agent-view> plane (561 P-B body-unification)', async () => {
    const view = mountView();
    await view.updateComplete;
    // No separate agent-view element — the agent run renders INLINE in the one thread.
    expect(view.shadowRoot?.querySelector('jf-agent-view')).toBeNull();
    expect(view.shadowRoot?.querySelector('jf-composer')).not.toBeNull();

    // cross the §2.1 agency threshold into the action plane.
    view.affordance = 'agent';
    await view.updateComplete;

    // Still ONE body: the answer plane (the conversation) is present and NOT hidden — no plane swap,
    // no separate agent surface.
    const answerPlane = view.shadowRoot?.querySelector('.answer-plane');
    expect(answerPlane).not.toBeNull();
    expect(answerPlane?.hasAttribute('hidden')).toBe(false);
    expect(view.shadowRoot?.querySelector('jf-agent-view')).toBeNull();
    expect(view.shadowRoot?.querySelector('jf-composer')).not.toBeNull();
    // Search Thread S5b — the affordance tab row is retired; the crossing is explicit-affordance-only
    // now. The shape-indicator still names the active tier (a passive readout, not a clickable tab).
    const shapeIndicator = view.shadowRoot?.querySelector('.shape-indicator');
    expect(shapeIndicator?.textContent?.trim()).toBe('Agent');
    expect(view.shadowRoot?.querySelector('.affordance-bar')).toBeNull();
  });

  it('561 C-2 — the supervision dial appears only at the agency crossing, and the chrome grades with it', async () => {
    const { setAutonomyLevel, __resetAutonomyForTest } = await import(
      '../substrates/autonomy/index.js'
    );
    __resetAutonomyForTest();
    const view = mountView();
    await view.updateComplete;
    // Answer plane (posture 0): no supervision dial.
    expect(view.shadowRoot?.querySelector('jf-autonomy-dial')).toBeNull();

    // Cross into agent mode: the dial appears (the phase transition is made visible).
    view.affordance = 'agent';
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('jf-autonomy-dial')).not.toBeNull();
    // assist default (posture 2): the composer copy grades.
    const composer = () => view.shadowRoot?.querySelector('jf-composer');
    expect(composer()?.getAttribute('placeholder')).toContain('writes need your OK');
    expect(composer()?.getAttribute('submit-label')).toBe('Send');

    // Raise the dial to auto (posture 3): the chrome grades up — send label + rail posture.
    setAutonomyLevel('auto');
    await view.updateComplete;
    expect(composer()?.getAttribute('submit-label')).toBe('Send & auto-run');
    const summary = view.shadowRoot?.querySelector('.activity-rail > summary');
    // Honest: the AUTO posture says irreversible writes still confirm (the C-4 floor).
    expect(summary?.textContent).toContain('confirming irreversible writes');
    __resetAutonomyForTest();
  });

  it('C8 (Tempdoc 738) — the run budget-gate state is plain in Simple, technical in Detailed', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    await view.updateComplete; // ensureAgentCtrl creates the real controller
    const ctrl = (view as unknown as { agentCtrl: { budgetGate: unknown } | null }).agentCtrl;
    if (ctrl) ctrl.budgetGate = { promptTokens: 200, contextWindow: 4096 };
    view.requestUpdate();
    await view.updateComplete;
    const summary = () => view.shadowRoot?.querySelector('.activity-rail > summary')?.textContent ?? '';
    // Simple (default): plain language, no "budget"/"tokens" jargon in the always-visible summary.
    expect(summary()).toContain('Paused — waiting to continue');
    expect(summary()).not.toContain('awaiting budget');
    // Detailed: the technical phrasing returns.
    setUiMode('advanced');
    view.requestUpdate();
    await view.updateComplete;
    expect(summary()).toContain('Paused — awaiting budget');
  });

  it('round-14 finding 12(a) — the run-telemetry band starts COLLAPSED', async () => {
    // Same shared-singleton hygiene the 12(b) test below records: a neighbouring test leaves a
    // budgetGate on the controller, which the 814 §D2 held-gate exception would (correctly) expand
    // the rail for — masking what THIS test asserts (the no-gate default).
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    await view.updateComplete;
    const rail = view.shadowRoot?.querySelector('[data-testid="activity-rail"]') as HTMLDetailsElement;
    expect(rail).not.toBeNull();
    expect(rail.open).toBe(false);
  });

  describe('814 §D2 — the held budget gate is content, not chrome', () => {
    const railOf = (view: UnifiedChatView) =>
      view.shadowRoot?.querySelector('[data-testid="activity-rail"]') as HTMLDetailsElement;

    const mountAgentView = async (): Promise<UnifiedChatView> => {
      __resetAgentSessionStore();
      const view = mountView();
      await view.updateComplete;
      view.affordance = 'agent';
      await view.updateComplete; // ensureAgentCtrl creates the real (reset) controller
      return view;
    };

    const holdBudgetGate = async (view: UnifiedChatView): Promise<void> => {
      const ctrl = (view as unknown as { agentCtrl: { budgetGate: unknown } | null }).agentCtrl;
      expect(ctrl).not.toBeNull();
      ctrl!.budgetGate = { tokensNeeded: 4000, tokensRemaining: 0, totalTokensConsumed: 20224 };
      view.requestUpdate();
      await view.updateComplete;
    };

    it('the transition INTO the held state opens the rail, so the decision row is on screen', async () => {
      const view = await mountAgentView();
      expect(railOf(view).open).toBe(false);
      await holdBudgetGate(view);
      expect(railOf(view).open).toBe(true);
      // The point of opening it: the decision row is what the user must act on.
      expect(view.shadowRoot?.querySelector('.budget-gate-row')).not.toBeNull();
    });

    it('a user who re-collapses while still parked keeps it collapsed (no re-force)', async () => {
      const view = await mountAgentView();
      await holdBudgetGate(view);
      const rail = railOf(view);
      expect(rail.open).toBe(true);
      // The user's own toggle — the same path the `@toggle` binding records.
      rail.open = false;
      rail.dispatchEvent(new Event('toggle'));
      await view.updateComplete;
      // The gate is STILL held; further re-renders must not re-open it.
      view.requestUpdate();
      await view.updateComplete;
      view.requestUpdate();
      await view.updateComplete;
      expect(railOf(view).open).toBe(false);
    });

    it('a DONE transition does NOT auto-expand — a terminal run is history, not a decision', async () => {
      const view = await mountAgentView();
      expect(railOf(view).open).toBe(false);
      (view as unknown as { unifiedLifecycles: unknown[] }).unifiedLifecycles = [
        {
          sessionId: 's1',
          state: 'DONE',
          actor: 'agent',
          turns: 1,
          iterations: 7,
          toolCalls: 6,
          actors: ['agent'],
          budget: { initial: 20224, consumed: 21431, remaining: 0, overBudget: true },
        },
      ];
      (view as unknown as { agentBudget: unknown }).agentBudget = {
        tokensConsumed: 21431,
        tokensRemaining: -1207,
      };
      view.requestUpdate();
      await view.updateComplete;
      expect(railOf(view).open).toBe(false);
    });
  });

  it('round-14 finding 12(b) — a COMPLETED (DONE) run states "Over budget" as a fact, not an alarm', async () => {
    // The agent session controller is a shared singleton; a neighbouring test leaves a budgetGate on
    // it, which would take the summary's held-gate branch and mask what this test is about.
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    const overBudget = {
      sessionId: 's1',
      state: 'RUNNING',
      actor: 'agent',
      turns: 1,
      iterations: 7,
      toolCalls: 6,
      actors: ['agent'],
      budget: { initial: 20224, consumed: 21431, remaining: 0, overBudget: true },
    };
    // The measured live numbers: 21431 tokens used against 20224 granted (over by 1207).
    const overBudgetUpdate = { tokensConsumed: 21431, tokensRemaining: -1207 };
    // In flight: the alarm treatment is correct — the run can still be raised or halted.
    (view as unknown as { unifiedLifecycles: unknown[] }).unifiedLifecycles = [overBudget];
    (view as unknown as { agentBudget: unknown }).agentBudget = overBudgetUpdate;
    view.requestUpdate();
    await view.updateComplete;
    const row = () => view.shadowRoot?.querySelector('[data-testid="activity-over-budget"]');
    const summaryChip = () => view.shadowRoot?.querySelector('.activity-rail > summary .over-budget');
    expect(row()?.className).toBe('over-budget');
    expect(summaryChip()).not.toBeNull();

    // DONE: same figure, same words, neutral treatment — and the collapsed summary drops the chip.
    (view as unknown as { unifiedLifecycles: unknown[] }).unifiedLifecycles = [
      { ...overBudget, state: 'DONE' },
    ];
    view.requestUpdate();
    await view.updateComplete;
    expect(row()?.className).toBe('budget-settled');
    expect(row()?.textContent).toContain('Over budget by');
    expect(summaryChip()).toBeNull();
  });

  it('867 — renders agent search evidence from the RECORD through the tool card\'s own level-2 body (not the raw dump)', async () => {
    const view = mountView();
    await view.updateComplete;
    // S5a — the B14 auto-upgrade is retired: land in the documents plane EXPLICITLY
    // (the tier a user now reaches by tab click / escalation), where the thread renders.
    view.affordance = 'documents';
    view.requestUpdate();
    await view.updateComplete;
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      {
        id: 't1', occurredAt: '2026-01-01T00:00:02Z', kind: 'TOOL_ACTIVITY', originator: 'agent',
        content: '',
        attributes: {
          callId: 'c1', toolName: 'core_search_index', status: 'completed',
          output: '[1] taxes (score: 0.92)\n    Path: C:/docs/taxes.md',
          structuredData: {
            query: 'taxes',
            resultCount: 1,
            searchResults: [{ title: 'Tax Notes', path: 'C:/docs/taxes.md', excerpt: 'deductible limits', line: 42 }],
          },
        },
      },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    // Tempdoc 565 §12.3.B — the record's tool activity renders through the SAME <jf-tool-call-card>
    // the live half uses. Tempdoc 867 replaced the nested `<jf-results-card>` mount with the tool
    // card's OWN level-2 evidence body.
    const toolCard = sr.querySelector('.tool-activity jf-tool-call-card') as
      | (Element & { shadowRoot: ShadowRoot; updateComplete: Promise<unknown> })
      | null;
    expect(toolCard).not.toBeNull();
    await toolCard!.updateComplete;
    const body = toolCard!.shadowRoot.querySelector('[data-testid="tool-search-body"]');
    expect(body, 'the level-2 search body mounts inside the tool card').not.toBeNull();
    const text = (body!.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('taxes');
    // Honesty: no fabricated "% RELEVANCE" badge from the uncalibrated ranking score (559 §5 / C-6).
    expect(text).not.toContain('%');
    // Tempdoc 871 §3b — level 2 is the model's FULL ranked list, so the hit renders as a row here
    // even though UnifiedChatView does not wire the run's evidence-path set for a record-hydrated
    // tool item (cross-run misattribution risk, disclosed limitation). Because the set is unwired,
    // the card must NOT mark the row either way: no `used` tag, and no accessory claim about it.
    expect(toolCard!.shadowRoot.querySelectorAll('[data-testid="tool-search-row"]')).toHaveLength(1);
    expect(toolCard!.shadowRoot.querySelector('[data-testid="tool-search-row-used"]')).toBeNull();
    expect(
      toolCard!.shadowRoot.querySelector('[data-testid="tool-card-accessory"]')?.textContent?.trim(),
    ).toBe('1 result');
    // Nothing is beyond the cap, so nothing is counted — and the old footer's "not in evidence"
    // claim (which the unwired card could never actually know) is gone.
    expect(toolCard!.shadowRoot.querySelector('[data-testid="tool-search-more"]')).toBeNull();
    // The raw monospace dump is still suppressed in favour of the structured body.
    expect(toolCard!.shadowRoot.querySelector('.tool-output')).toBeNull();
  });

  it('renders the unified interleaved thread (chat + agent tool activity) from the record (Slice 2)', async () => {
    const view = mountView();
    await view.updateComplete;
    // S5a — the B14 auto-upgrade is retired: land in the documents plane EXPLICITLY
    // (the tier a user now reaches by tab click / escalation), where the thread renders.
    view.affordance = 'documents';
    view.requestUpdate();
    await view.updateComplete;
    // Populate the canonical-record events (as GET /api/thread would return), out of input order to
    // prove the render projects by the authoritative timestamp.
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'found 12 invoices', attributes: {} },
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'find my invoices', attributes: {} },
      { id: 't1', occurredAt: '2026-01-01T00:00:02Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } },
    ];
    view.requestUpdate();
    await view.updateComplete;

    const sr = view.shadowRoot!;
    const userMsg = Array.from(sr.querySelectorAll('.message.user')).find((e) =>
      (e.textContent ?? '').includes('find my invoices'),
    );
    expect(userMsg).toBeDefined();
    // Tempdoc 565 §12.3.B — tool activity renders through the SAME <jf-tool-call-card> the live half
    // uses (ONE tool renderer). The wire toolName is carried on the card's `.toolCall` property; the
    // header renders the humanized label ("Search Index") in the card's shadow DOM, not the raw name.
    const tool = sr.querySelector('.tool-activity');
    const card = tool?.querySelector('jf-tool-call-card') as unknown as {
      toolCall?: { toolName?: string };
    } | null;
    expect(card).toBeTruthy();
    expect(card?.toolCall?.toolName).toBe('core_search_index');

    // Interleaved in authoritative-timestamp order: user -> tool -> assistant. The assistant text now
    // renders via <jf-markdown-block> (561 P-A evidence render), so assert by element order and
    // read the assistant text from the block's property (not light-DOM textContent).
    const msgs = Array.from(sr.querySelectorAll('.message'));
    const userIdx = msgs.findIndex((e) => (e.textContent ?? '').includes('find my invoices'));
    const toolIdx = msgs.findIndex((e) => e.classList.contains('tool-activity'));
    const asstIdx = msgs.findIndex((e) => e.querySelector('jf-markdown-block'));
    expect(userIdx).toBeGreaterThanOrEqual(0);
    expect(userIdx).toBeLessThan(toolIdx);
    expect(toolIdx).toBeLessThan(asstIdx);
    const stb = sr.querySelector(
      '.message.assistant jf-markdown-block',
    ) as unknown as { text: string };
    expect(stb.text).toContain('found 12 invoices');
  });

  // Tempdoc 577 Move 1 §3e — the resume card is DERIVED state that cannot co-exist with rendered
  // run content. The shared agent controller is the THIRD content source (live `conversation`); the
  // original fix checked only thread + unifiedEvents, so a populated singleton controller left the
  // card pinned above the thread (visually caught during the round-2 inspection).
  it('hides the resume card when the shared agent controller already has run content', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      showResumePrompt: boolean;
      recentSession: unknown;
      thread: unknown[];
      unifiedEvents: unknown[];
      ensureAgentCtrl: () => { conversation: unknown[] };
    };
    v.affordance = 'agent';
    v.showResumePrompt = true;
    v.recentSession = { sessionId: 's-prev', firstMessage: 'an earlier run', timestamp: 0 };
    v.thread = [];
    v.unifiedEvents = [];
    // Empty controller → the genuinely-empty state shows the card.
    v.ensureAgentCtrl();
    view.requestUpdate();
    await view.updateComplete;
    expect(view.shadowRoot!.querySelector('.resume-prompt'), 'empty state shows the card').toBeTruthy();

    // Seed the shared controller with run content → the card must hide (it would otherwise pin
    // above the controller-rendered thread).
    const ctrl = getAgentSessionController('http://localhost:5173');
    (ctrl as unknown as { conversation: unknown[] }).conversation = [
      { id: 'e1', type: 'user', content: 'an earlier run', timestamp: 0 },
      { id: 'e2', type: 'assistant-text', content: 'the answer', timestamp: 1 },
    ];
    view.requestUpdate();
    await view.updateComplete;
    expect(
      view.shadowRoot!.querySelector('.resume-prompt'),
      'a populated controller hides the card',
    ).toBeNull();
    view.remove();
  });

  // Tempdoc 577 §2.14 Root I (#19) — the run/session boundary seam: a thread restored from the
  // record (prior turns) plus a NEW live run must render a seam between them so the resumed thread
  // does not read as one continuous exchange. No seam for an all-record or all-live timeline.
  it('577 #19 — renders a run/session seam between restored record history and the live run', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      unifiedEvents: unknown[];
      ensureAgentCtrl: () => unknown;
    };
    v.affordance = 'agent';
    // Restored history (the record): an older user turn + its answer.
    v.unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'earlier question', attributes: {} },
      { id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'earlier answer', attributes: {} },
    ];
    v.ensureAgentCtrl();
    // A NEW live run continues in this session (distinct content → not deduped against the record;
    // later timestamp → sorts after the restored history).
    const ctrl = getAgentSessionController('http://localhost:5173');
    (ctrl as unknown as { conversation: unknown[] }).conversation = [
      { id: 'live-1', type: 'user', content: 'a fresh follow-up', timestamp: Date.parse('2026-01-01T00:00:10Z') },
    ];
    view.requestUpdate();
    await view.updateComplete;

    const seam = view.shadowRoot!.querySelector('.run-seam');
    expect(seam, 'a resumed thread with a new live run shows the boundary seam').toBeTruthy();
    expect((seam?.textContent ?? '').toLowerCase()).toContain('new run');
    view.remove();
  });

  it('577 #14 — the context-headroom meter persists across an iteration_start (no flicker)', async () => {
    // The defect: projectContextHorizon read budgetUpdates[last]; an iteration_start event carries
    // promptTokens 0, nulling the horizon and hiding the meter between iterations. The fix reads the
    // last update that actually carries occupancy, so the meter persists.
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      sessionId: string | null;
      agentCtrl: unknown;
      ensureAgentCtrl: () => unknown;
    };
    v.affordance = 'agent';
    v.sessionId = 'sess-meter';
    const ctrl = v.ensureAgentCtrl() as {
      conversationId: string | null;
      budgetUpdates: unknown[];
    };
    ctrl.conversationId = 'sess-meter'; // ctrlBudgetIsOurs → the rail projects this run's budget
    // A real LLM call carried occupancy, then the next iteration_start carried promptTokens 0.
    ctrl.budgetUpdates = [
      { phase: 'llm_response', tokensConsumed: 100, tokensRemaining: 6000, promptTokens: 2048, contextWindow: 8192 },
      { phase: 'iteration_start', tokensConsumed: 0, tokensRemaining: 6000, promptTokens: 0, contextWindow: 0 },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const meter = view.shadowRoot!.querySelector('[aria-label="Context window used"]');
    expect(meter, 'the context-headroom meter stays visible after an iteration_start').not.toBeNull();
    expect(meter?.getAttribute('aria-valuenow')).toBe('25'); // 2048 / 8192
    view.remove();
  });

  it('577 #19 — NO seam for an all-record thread (nothing live to separate)', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as { affordance: string; unifiedEvents: unknown[]; ensureAgentCtrl: () => unknown };
    v.affordance = 'agent';
    v.unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'only question', attributes: {} },
      { id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'only answer', attributes: {} },
    ];
    v.ensureAgentCtrl();
    view.requestUpdate();
    await view.updateComplete;
    expect(view.shadowRoot!.querySelector('.run-seam'), 'all-record → no seam').toBeNull();
    view.remove();
  });

  it('577 #19 — NO seam between a question and its own answer (the mid-turn false-positive)', async () => {
    // The defect: when the live ANSWER fails to dedup against the reconciled record answer, the
    // user turn is a record item and the answer is the first live item — keying the seam on that
    // answer drew "resumed · new run" mid-turn. The fix anchors the seam on a live USER turn only,
    // so a live assistant item following a record user turn produces NO seam.
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as { affordance: string; unifiedEvents: unknown[]; ensureAgentCtrl: () => unknown };
    v.affordance = 'agent';
    // The current turn's USER message is already reconciled into the record (record item).
    v.unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'the question', attributes: {} },
    ];
    v.ensureAgentCtrl();
    // The live answer (distinct content → not deduped) sorts AFTER the record user turn.
    const ctrl = getAgentSessionController('http://localhost:5173');
    (ctrl as unknown as { conversation: unknown[] }).conversation = [
      { id: 'live-ans', type: 'assistant-text', content: 'the live answer with [1] marks', timestamp: Date.parse('2026-01-01T00:00:09Z') },
    ];
    view.requestUpdate();
    await view.updateComplete;
    expect(
      view.shadowRoot!.querySelector('.run-seam'),
      'a live answer after a record user turn must NOT draw a seam (same turn, not a new run)',
    ).toBeNull();
    view.remove();
  });

  it('565 fix A — a multi-turn record renders one INDEPENDENTLY-collapsible trace per run', async () => {
    const view = mountView();
    await view.updateComplete;
    // S5a — the B14 auto-upgrade is retired: land in the documents plane EXPLICITLY
    // (the tier a user now reaches by tab click / escalation), where the thread renders.
    view.affordance = 'documents';
    view.requestUpdate();
    await view.updateComplete;
    // Two completed runs in one session: user1 · tool1 · answer1 · user2 · tool2 · answer2.
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'turn one', attributes: {} },
      { id: 't1', occurredAt: '2026-01-01T00:00:02Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } },
      { id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer one', attributes: {} },
      { id: 'u2', occurredAt: '2026-01-01T00:00:04Z', kind: 'USER_MESSAGE', originator: 'user', content: 'turn two', attributes: {} },
      { id: 't2', occurredAt: '2026-01-01T00:00:05Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c2', toolName: 'core_ingest_files', status: 'completed' } },
      { id: 'a2', occurredAt: '2026-01-01T00:00:06Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer two', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;

    // ONE trace segment per run; both default-collapsed (every answer has landed — neither is trailing).
    const traces = Array.from(sr.querySelectorAll('details.run-trace'));
    expect(traces.length).toBe(2);
    expect(traces.every((d) => !(d as HTMLDetailsElement).open)).toBe(true);

    // Expanding the FIRST run's trace must NOT expand the second (fix A — per-segment, not shared).
    const firstSummary = traces[0]!.querySelector('summary') as HTMLElement;
    firstSummary.click();
    await view.updateComplete;
    const after = Array.from(sr.querySelectorAll('details.run-trace')) as HTMLDetailsElement[];
    expect(after[0]!.open).toBe(true);
    expect(after[1]!.open).toBe(false);
  });

  it('565 §12.3.E — renders a source-chip row under a grounded answer + cross-highlights via the store', async () => {
    __resetSelectedSource();
    const view = mountView();
    await view.updateComplete;
    // S5a — the B14 auto-upgrade is retired: land in the documents plane EXPLICITLY
    // (the tier a user now reaches by tab click / escalation), where the thread renders.
    view.affordance = 'documents';
    view.requestUpdate();
    await view.updateComplete;
    const sources = [
      { parentDocId: 'd1', chunkIndex: 0, path: 'docs/a.md', title: 'Doc A', excerpt: 'x', startLine: 5, endLine: 9, headingText: '' },
      { parentDocId: 'd2', chunkIndex: 0, path: 'docs/b.md', title: 'Doc B', excerpt: 'y', startLine: 12, endLine: 14, headingText: '' },
    ];
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q', attributes: {} },
      { id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer', attributes: { sources } },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;

    // §13.8 P3 — the chips are collapsed behind a "Sources · N" disclosure when the wide rail shows
    // the detail; expand it first to assert the chip behavior.
    (sr.querySelector('.source-disclosure-summary') as HTMLElement).click();
    await view.updateComplete;

    // One chip per source, numbered, named.
    const chips = Array.from(sr.querySelectorAll('.source-chips .source-chip'));
    expect(chips.length).toBe(2);
    expect(chips[0]!.textContent).toContain('1');
    expect(chips[0]!.textContent).toContain('Doc A');
    expect(chips[1]!.textContent).toContain('Doc B');

    // External selection (an inline [n] mark / rail card) highlights the matching chip.
    setSelectedSource(sourceKey('d2', 12));
    await view.updateComplete;
    const chip2 = Array.from(sr.querySelectorAll('.source-chip'))[1] as HTMLElement;
    expect(chip2.classList.contains('selected')).toBe(true);
    expect(chip2.getAttribute('aria-current')).toBe('true');

    // A chip click sets the shared selection + dispatches the citation-select deep-link.
    let detail: { parentDocId?: string; startLine?: number } | null = null;
    view.addEventListener('citation-select', (e) => {
      detail = (e as CustomEvent).detail;
    });
    (Array.from(sr.querySelectorAll('.source-chip'))[0] as HTMLElement).click();
    expect(getSelectedSource()).toBe(sourceKey('d1', 5));
    expect(detail).not.toBeNull();
    expect(detail!.parentDocId).toBe('d1');
    expect(detail!.startLine).toBe(5);
    __resetSelectedSource();
  });

  it('565 §13.8 P3 — the source chips collapse behind a "Sources · N" disclosure; clicking toggles it', async () => {
    __resetSelectedSource();
    const view = mountView();
    await view.updateComplete;
    // S5a — the B14 auto-upgrade is retired: land in the documents plane EXPLICITLY
    // (the tier a user now reaches by tab click / escalation), where the thread renders.
    view.affordance = 'documents';
    view.requestUpdate();
    await view.updateComplete;
    const sources = [
      { parentDocId: 'd1', chunkIndex: 0, path: 'docs/a.md', title: 'Doc A', excerpt: 'x', startLine: 5, endLine: 9, headingText: '' },
      { parentDocId: 'd2', chunkIndex: 0, path: 'docs/b.md', title: 'Doc B', excerpt: 'y', startLine: 12, endLine: 14, headingText: '' },
    ];
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q', attributes: {} },
      { id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer', attributes: { sources } },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;

    // Collapsed by default (the wide rail owns the detail): the summary is present + a11y-correct,
    // the chip body is not rendered.
    const summary = sr.querySelector('.source-disclosure-summary') as HTMLButtonElement;
    expect(summary).not.toBeNull();
    expect(summary.tagName).toBe('BUTTON'); // keyboard-operable (559 operability)
    expect(summary.textContent).toContain('Sources · 2');
    expect(summary.getAttribute('aria-expanded')).toBe('false');
    expect(sr.querySelector('.source-chips')).toBeNull();

    // Click expands: aria-expanded flips, the chip body appears + is wired to the summary.
    summary.click();
    await view.updateComplete;
    expect(summary.getAttribute('aria-expanded')).toBe('true');
    const body = sr.querySelector('.source-chips') as HTMLElement;
    expect(body).not.toBeNull();
    expect(body.id).toBe(summary.getAttribute('aria-controls'));
    expect(body.querySelectorAll('.source-chip').length).toBe(2);

    // Click again collapses.
    summary.click();
    await view.updateComplete;
    expect(summary.getAttribute('aria-expanded')).toBe('false');
    expect(sr.querySelector('.source-chips')).toBeNull();
    __resetSelectedSource();
  });

  it('565 fix D — occurrence-aware dedup: two identical consecutive turns do NOT collapse', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    const ctrl = getAgentSessionController('http://localhost:5173');
    // The live run has TWO identical user turns; the record has only ONE so far (mid-stream).
    (ctrl as unknown as { conversation: unknown[] }).conversation = [
      { id: 'l1', type: 'user', content: 'ok', timestamp: 1 },
      { id: 'l2', type: 'user', content: 'ok', timestamp: 2 },
    ];
    (view as unknown as { agentCtrl: unknown }).agentCtrl = ctrl;
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'r1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'ok', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    // BOTH "ok" turns render (the record's one + the 2nd live one, not collapsed). Old Set-based dedup
    // would show only ONE. Match the user turn's `.message-body` (577 #19 added an ambient `.turn-time`
    // child, so the whole `.message.user` textContent now also carries the relative time).
    const okTurns = Array.from(sr.querySelectorAll('.message.user .message-body')).filter((m) =>
      (m.textContent ?? '').trim() === 'ok',
    );
    expect(okTurns.length).toBe(2);
    __resetAgentSessionStore();
  });

  it('565 §12.3.D — the left run-spine renders a status node per step + a terminal answer node', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    // Force the wide breakpoint (a MediaQueryList-shaped stub incl. the listener methods the teardown calls).
    (view as unknown as { wideRailMql: unknown }).wideRailMql = {
      matches: true,
      addEventListener() {},
      removeEventListener() {},
    };
    // Round-14 finding 15 — a SECOND turn, because the spine no longer mounts on an unsegmented
    // single-turn conversation (it has no boundaries worth marking). What this test asserts — the
    // prominence grading, the placement, the a11y contract — is unchanged.
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q', attributes: {} },
      { id: 't1', occurredAt: '2026-01-01T00:00:02Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } },
      { id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer', attributes: {} },
      { id: 'u2', occurredAt: '2026-01-01T00:00:04Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q2', attributes: {} },
      { id: 'a2', occurredAt: '2026-01-01T00:00:05Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer2', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    const spine = sr.querySelector('.run-spine');
    expect(spine).not.toBeNull();
    // §13 Pillar A — the WHOLE conversation: the user landmarks + the tool texture + the answer terminals.
    const nodes = spine!.querySelectorAll('.run-spine-node');
    expect(nodes.length).toBe(5);
    // §19.3 — prominence-graded by the DECLARED scale (PROMINENCE_SCALE / TERMINAL_NODE_WEIGHT) set
    // inline, not a hand-CSS class: the answer is the terminal landmark (0.8rem), the user turn primary
    // (0.62rem), the tool step secondary texture (0.36rem).
    const styleOf = (id: string) =>
      ([...nodes].find((x) => x.getAttribute('data-item-id') === id) as HTMLElement | undefined)?.getAttribute(
        'style',
      ) ?? '';
    expect(styleOf('a1')).toContain('--node-size:0.8rem'); // terminal answer landmark
    expect(styleOf('u1')).toContain('--node-size:0.62rem'); // primary turn
    expect(styleOf('t1')).toContain('--node-size:0.36rem'); // secondary tool texture
    // §19.4 — each node is placed at its conversation scroll fraction (an inline top:%).
    expect([...nodes].every((n) => /top:/.test(n.getAttribute('style') ?? ''))).toBe(true);
    // each node anchors to its timeline item (the scroll-spy / click-jump target).
    expect([...nodes].every((n) => n.getAttribute('data-item-id'))).toBe(true);
    // §13 P2 — the spine is an operable nav (the binding), not aria-hidden decorative; every node is a
    // keyboard-operable button with an accessible name (559 operability).
    expect(spine!.tagName.toLowerCase()).toBe('nav');
    expect(spine!.getAttribute('aria-label')).toBeTruthy();
    expect(
      [...nodes].every(
        (n) => n.tagName.toLowerCase() === 'button' && n.getAttribute('aria-label'),
      ),
    ).toBe(true);
  });

  it('565 §13 Pillar A — the spine spans the WHOLE conversation, not just the latest run', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    (view as unknown as { wideRailMql: unknown }).wideRailMql = {
      matches: true,
      addEventListener() {},
      removeEventListener() {},
    };
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q1', attributes: {} },
      { id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer1', attributes: {} },
      { id: 'u2', occurredAt: '2026-01-01T00:00:03Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q2', attributes: {} },
      { id: 'a2', occurredAt: '2026-01-01T00:00:04Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer2', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const spine = view.shadowRoot!.querySelector('.run-spine');
    // Pre-§13 (latest-run slice) would show only turn-2's node; the whole-conversation spine shows all 4.
    expect(spine!.querySelectorAll('.run-spine-node').length).toBe(4);
    // both answers carry the data-item-id matching their timeline items (the click-jump targets).
    expect(spine!.querySelector('[data-item-id="a1"]')).not.toBeNull();
    expect(spine!.querySelector('[data-item-id="a2"]')).not.toBeNull();
    __resetAgentSessionStore();
  });

  it('565 §13/§19.3 — only the FINAL assistant of a turn is the terminal "Answer"; intermediate assistants recede', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    (view as unknown as { wideRailMql: unknown }).wideRailMql = {
      matches: true,
      addEventListener() {},
      removeEventListener() {},
    };
    // One turn where the agent loop emits an INTERMEDIATE assistant message (a tool-call preamble) before
    // the final answer, then a second turn — proving the terminal landmark resets on the user boundary.
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q1', attributes: {} },
      { id: 'a1mid', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'let me search', attributes: {} },
      { id: 't1', occurredAt: '2026-01-01T00:00:03Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } },
      { id: 'a1final', occurredAt: '2026-01-01T00:00:04Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'the real answer', attributes: {} },
      { id: 'u2', occurredAt: '2026-01-01T00:00:05Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q2', attributes: {} },
      { id: 'a2final', occurredAt: '2026-01-01T00:00:06Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer two', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    const nodes = [...sr.querySelectorAll('.run-spine-node')];
    const nodeOf = (id: string) =>
      nodes.find((x) => x.getAttribute('data-item-id') === id) as HTMLElement | undefined;
    // The FINAL assistant of each turn is the terminal "Answer" landmark (0.8rem, labelled "Answer").
    expect(nodeOf('a1final')!.getAttribute('style')).toContain('--node-size:0.8rem');
    expect(nodeOf('a1final')!.getAttribute('aria-label')).toBe('Answer');
    expect(nodeOf('a2final')!.getAttribute('style')).toContain('--node-size:0.8rem');
    expect(nodeOf('a2final')!.getAttribute('aria-label')).toBe('Answer');
    // The INTERMEDIATE assistant recedes to secondary texture (0.36rem) and is NOT labelled "Answer".
    expect(nodeOf('a1mid')!.getAttribute('style')).toContain('--node-size:0.36rem');
    expect(nodeOf('a1mid')!.getAttribute('aria-label')).not.toBe('Answer');
    expect(nodeOf('a1mid')!.getAttribute('aria-label')).toBe('Working step');
    __resetAgentSessionStore();
  });

  it('565 §13 Pillar A — clicking a spine node scrolls the reading column to that item + marks it active', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    (view as unknown as { wideRailMql: unknown }).wideRailMql = {
      matches: true,
      addEventListener() {},
      removeEventListener() {},
    };
    // Round-14 finding 15 — two turns, so the spine mounts (it no longer does for a single turn).
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q1', attributes: {} },
      { id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer1', attributes: {} },
      { id: 'u2', occurredAt: '2026-01-01T00:00:03Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q2', attributes: {} },
      { id: 'a2', occurredAt: '2026-01-01T00:00:04Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer2', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    const target = sr.querySelector('.conversation [data-item-id="a1"]') as HTMLElement;
    expect(target).not.toBeNull();
    let scrolledTo: HTMLElement | null = null;
    target.scrollIntoView = function (this: HTMLElement): void {
      scrolledTo = this;
    } as unknown as typeof target.scrollIntoView;
    const node = sr.querySelector('.run-spine [data-item-id="a1"]') as HTMLButtonElement;
    expect(node).not.toBeNull();
    node.click();
    await view.updateComplete;
    expect(scrolledTo).toBe(target);
    expect(sr.querySelector('.run-spine-node.active[data-item-id="a1"]')).not.toBeNull();
    // The click PINS the ring to the clicked item (so the scroll-spy can't re-point it after scroll).
    expect((view as unknown as { nav: { pinned: string | null } }).nav.pinned).toBe('a1');
    __resetAgentSessionStore();
  });

  it('565 §13 — a click-jump pins the ring; the scroll-spy cannot override it until a user scroll releases the pin', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    (view as unknown as { wideRailMql: unknown }).wideRailMql = {
      matches: true,
      addEventListener() {},
      removeEventListener() {},
    };
    // Round-14 finding 15 — two turns, so the spine mounts (it no longer does for a single turn).
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q1', attributes: {} },
      { id: 't1', occurredAt: '2026-01-01T00:00:02Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } },
      { id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer', attributes: {} },
      { id: 'u2', occurredAt: '2026-01-01T00:00:04Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q2', attributes: {} },
      { id: 'a2', occurredAt: '2026-01-01T00:00:05Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer2', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    (sr.querySelector('.conversation [data-item-id="a1"]') as HTMLElement).scrollIntoView = function (
      this: HTMLElement,
    ): void {} as unknown as typeof HTMLElement.prototype.scrollIntoView;
    // §21 — the pin/focus/release apparatus now lives on the NavigationController (the chat-first
    // reading-position authority): a single live/pinned `intent`, FOCUS DERIVED from window×extents (no
    // IntersectionObserver). The view projects `nav.activeId`/`nav.pinned`.
    const nav = (view as unknown as {
      nav: {
        pinned: string | null;
        activeId: string;
        onUserScroll: () => void;
        landmarks: { id: string; extent: { topFrac: number; botFrac: number } }[];
        viewport: { topFrac: number; botFrac: number } | null;
      };
    }).nav;
    // Click the answer node → the intent pins the focus to it.
    (sr.querySelector('.run-spine [data-item-id="a1"]') as HTMLButtonElement).click();
    await view.updateComplete;
    // Inject a measured reading-state (happy-dom has no real layout) where the DERIVED focus would be the
    // tool step t1 (the reading window sits over it), to prove the pin overrides the live derivation.
    nav.landmarks = [
      { id: 'u1', extent: { topFrac: 0, botFrac: 0.2 } },
      { id: 't1', extent: { topFrac: 0.2, botFrac: 0.5 } },
      { id: 'a1', extent: { topFrac: 0.5, botFrac: 1 } },
    ];
    nav.viewport = { topFrac: 0.25, botFrac: 0.45 }; // window over t1 → deriveFocus would be t1
    expect(nav.pinned).toBe('a1');
    expect(nav.activeId).toBe('a1'); // pinned wins over the derived t1 — the highlight-steal is impossible
    // A genuine user scroll flips the intent to live; FOCUS now tracks the reading window (→ t1).
    nav.onUserScroll();
    expect(nav.pinned).toBeNull();
    expect(nav.activeId).toBe('t1');
    __resetAgentSessionStore();
  });

  it('565 §13 Pillar A — clicking a STEP node opens its collapsed run-trace so the jump lands (regression: scrollIntoView no-ops inside a closed <details>)', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    (view as unknown as { wideRailMql: unknown }).wideRailMql = {
      matches: true,
      addEventListener() {},
      removeEventListener() {},
    };
    // A completed run (user · tool-step · answer): because the answer has landed, the tool step renders
    // inside a DEFAULT-COLLAPSED <details class="run-trace"> — the ~half of spine nodes the prior review
    // found un-jumpable (scrollIntoView is a no-op on an element inside a closed <details>).
    // Round-14 finding 15 — two turns, so the spine mounts (it no longer does for a single turn).
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q1', attributes: {} },
      { id: 't1', occurredAt: '2026-01-01T00:00:02Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } },
      { id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer1', attributes: {} },
      { id: 'u2', occurredAt: '2026-01-01T00:00:04Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q2', attributes: {} },
      { id: 'a2', occurredAt: '2026-01-01T00:00:05Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer2', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    const target = sr.querySelector('.conversation [data-item-id="t1"]') as HTMLElement;
    expect(target).not.toBeNull();
    const details = target.closest('details.run-trace') as HTMLDetailsElement;
    expect(details).not.toBeNull();
    expect(details.open).toBe(false); // collapsed → scrollIntoView would no-op without the fix
    let scrolledTo: HTMLElement | null = null;
    target.scrollIntoView = function (this: HTMLElement): void {
      scrolledTo = this;
    } as unknown as typeof target.scrollIntoView;
    const node = sr.querySelector('.run-spine [data-item-id="t1"]') as HTMLButtonElement;
    expect(node).not.toBeNull();
    node.click();
    await view.updateComplete;
    // The fix: nav.jumpTo opens every ancestor <details> BEFORE scrollIntoView, so the step is laid out
    // and the jump lands instead of silently doing nothing.
    expect(details.open).toBe(true);
    expect(scrolledTo).toBe(target);
    expect(sr.querySelector('.run-spine-node.active[data-item-id="t1"]')).not.toBeNull();
    __resetAgentSessionStore();
  });

  it('565 fix F + §12.3.D — at the narrow breakpoint the rail AND the run-spine do not mount', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    // 574 F1 — the wide breakpoint now comes from the shared responsiveState authority; simulate
    // narrow (< 64rem of SURFACE width, 798) by setting the projected field directly.
    (view as unknown as { wideZone: boolean }).wideZone = false;
    const ctrl = getAgentSessionController('http://localhost:5173');
    (ctrl as unknown as { answerSources: unknown[] }).answerSources = [
      { parentDocId: 'd1', chunkIndex: 0, path: 'docs/a.md', title: 'Doc A', excerpt: 'x', startLine: 5, endLine: 9, headingText: '' },
    ];
    (view as unknown as { agentCtrl: unknown }).agentCtrl = ctrl;
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q', attributes: {} },
      { id: 't1', occurredAt: '2026-01-01T00:00:02Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } },
      { id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    // Neither the docked rail nor the left spine mounts at narrow — one SourcesPane per viewport, and the
    // spine is a wide-only margin element.
    expect(sr.querySelector('jf-sources-pane.evidence-rail')).toBeNull();
    expect(sr.querySelector('.run-spine')).toBeNull();
    __resetAgentSessionStore();
  });

  it('round-14 finding 15 — a SINGLE-TURN conversation renders no run spine (and keeps its native scrollbar)', async () => {
    // The spine is the RunSegmentRef / assignRunSegments node-boundary visualization ("the spine
    // marks node boundaries", 565 §26). Measured live against a single turn it drew ~10 markers in
    // three glyph types over four content blocks — machinery for structure that isn't there.
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    (view as unknown as { wideZone: boolean }).wideZone = true;
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q', attributes: {} },
      { id: 't1', occurredAt: '2026-01-01T00:00:02Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } },
      { id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    expect(sr.querySelector('.run-spine')).toBeNull();
    // …and the reading column keeps its native scrollbar: the column hides it only when the spine
    // (which IS the scroll control) is mounted, so the two gates must read the same predicate.
    expect(sr.querySelector('.conversation.jf-scrollbar-none')).toBeNull();
    __resetAgentSessionStore();
  });

  it('round-14 finding 15 — a SECOND turn brings the spine back (the gate is structure, not the agent rung)', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    (view as unknown as { wideZone: boolean }).wideZone = true;
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q', attributes: {} },
      { id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer', attributes: {} },
      { id: 'u2', occurredAt: '2026-01-01T00:00:03Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q2', attributes: {} },
      { id: 'a2', occurredAt: '2026-01-01T00:00:04Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer2', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr2 = view.shadowRoot!;
    expect(sr2.querySelector('.run-spine')).not.toBeNull();
    expect(sr2.querySelector('.conversation.jf-scrollbar-none')).not.toBeNull();
    __resetAgentSessionStore();
  });

  it('814 §D5 — with the evidence rail MOUNTED the rail head is the ONE source-count render', async () => {
    // Three renders of the same count within ~250px (finding 12's measured duplication): the rail head,
    // the in-answer "Based on N sources" line, and the in-answer "Sources · N" disclosure. The rail is
    // the authority when it is mounted; the other two stand down.
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    (view as unknown as { wideZone: boolean }).wideZone = true;
    const sources = [
      { parentDocId: 'docs/a.md', chunkIndex: 0, path: 'docs/a.md', title: 'a.md', excerpt: 'x', startLine: 1, endLine: 5, headingText: '' },
      { parentDocId: 'docs/b.md', chunkIndex: 1, path: 'docs/b.md', title: 'b.md', excerpt: 'y', startLine: 1, endLine: 5, headingText: '' },
    ];
    const ctrl = getAgentSessionController('http://localhost:5173');
    (ctrl as unknown as { answerSources: unknown[] }).answerSources = sources;
    (view as unknown as { agentCtrl: unknown }).agentCtrl = ctrl;
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q', attributes: {} },
      {
        id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent',
        content: 'The Head process hosts the UI. The Worker owns the index.',
        attributes: { sources, citations: [] },
      },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;

    expect(sr.querySelector('jf-sources-pane.evidence-rail')).not.toBeNull(); // the authority is mounted
    const text = (sr.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).not.toContain('Based on 2 sources'); // the in-answer count line stands down…
    expect(sr.querySelector('.source-disclosure')).toBeNull(); // …and so does the chip disclosure.
    // 814 W3 — the toolbar chip too: it used to RENDER and be CSS-hidden at wide, leaving a second
    // count in the DOM for the status-fact probe and for AT. The gate is now on the render itself.
    expect(sr.querySelector('.sources-affordance')).toBeNull();
    // The owner-credited grounding disclaimer is NOT a count and is untouched in this state.
    expect(text).toContain('per-sentence grounding not verified');
    __resetAgentSessionStore();
  });

  it('814 §D5 — with NO rail mounted (narrow) the in-answer count + disclosure return', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    (view as unknown as { wideZone: boolean }).wideZone = false; // no rail → the fallback surfaces own it
    const sources = [
      { parentDocId: 'docs/a.md', chunkIndex: 0, path: 'docs/a.md', title: 'a.md', excerpt: 'x', startLine: 1, endLine: 5, headingText: '' },
      { parentDocId: 'docs/b.md', chunkIndex: 1, path: 'docs/b.md', title: 'b.md', excerpt: 'y', startLine: 1, endLine: 5, headingText: '' },
    ];
    const ctrl = getAgentSessionController('http://localhost:5173');
    (ctrl as unknown as { answerSources: unknown[] }).answerSources = sources;
    (view as unknown as { agentCtrl: unknown }).agentCtrl = ctrl;
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q', attributes: {} },
      {
        id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent',
        content: 'The Head process hosts the UI. The Worker owns the index.',
        attributes: { sources, citations: [] },
      },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    expect(sr.querySelector('jf-sources-pane.evidence-rail')).toBeNull();
    expect((sr.textContent ?? '').replace(/\s+/g, ' ')).toContain('Based on 2 sources');
    expect(sr.querySelector('.source-disclosure')).not.toBeNull();
    expect(sr.querySelector('.sources-affordance')).not.toBeNull(); // 814 W3 — and the toolbar chip returns
    __resetAgentSessionStore();
  });

  it('814 finding 7 — a background-origin run segment renders a marked POINTER to its inbox item', async () => {
    // One authority, one pointer: a background run launched with a conversationId renders in the
    // thread AND in the drawer's Background-runs tab (`/api/presence`). The inbox item is the
    // authority; the thread appearance is marked as a reference to it, not an unmarked peer copy.
    __resetAgentSessionStore();
    __resetRetrospectiveDrawer();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      {
        id: 'bs', occurredAt: '2026-01-01T00:00:00Z', kind: 'PROGRESS', originator: 'agent', content: '',
        attributes: { nodeBoundary: 'start', originKind: 'background', nodeId: 'run-7', label: 'Background activity' },
      },
      { id: 'a1', occurredAt: '2026-01-01T00:00:01Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'done', attributes: {} },
      {
        id: 'be', occurredAt: '2026-01-01T00:00:02Z', kind: 'PROGRESS', originator: 'agent', content: '',
        attributes: { nodeBoundary: 'end', originKind: 'background', nodeId: 'run-7' },
      },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    expect(sr.querySelector('.run-segment.origin-background')).not.toBeNull();
    const ref = sr.querySelector('[data-testid="background-run-ref"]') as HTMLButtonElement | null;
    expect(ref).not.toBeNull();
    expect((ref!.textContent ?? '').toLowerCase()).toContain('background run');

    // Clicking the pointer opens the drawer store AT the Background-runs (inbox) tab.
    expect(isRetrospectiveOpen()).toBe(false);
    ref!.click();
    expect(isRetrospectiveOpen()).toBe(true);
    expect(takeRequestedTab()).toBe('inbox');
    __resetRetrospectiveDrawer();
    __resetAgentSessionStore();
  });

  it('814 §D4 — dense intra-run steps AGGREGATE into one counted, keyboard-operable cluster badge', async () => {
    // Density must be bounded by STRUCTURE, not event count: with a measured track, six tool steps
    // between two turn landmarks sit closer than the 14px aggregation threshold after de-overlap, so
    // they render as ONE badge that states what it stands for — not six dots piled into a smudge.
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    (view as unknown as { wideZone: boolean }).wideZone = true;
    const steps = [1, 2, 3, 4, 5, 6].map((n) => ({
      id: `t${n}`,
      occurredAt: `2026-01-01T00:00:0${n}Z`,
      kind: 'TOOL_ACTIVITY',
      originator: 'agent',
      content: '',
      attributes: { callId: `c${n}`, toolName: 'core_search_index', status: 'completed' },
    }));
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:00Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q', attributes: {} },
      ...steps,
      { id: 'a1', occurredAt: '2026-01-01T00:00:07Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer', attributes: {} },
      { id: 'u2', occurredAt: '2026-01-01T00:00:08Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q2', attributes: {} },
      { id: 'a2', occurredAt: '2026-01-01T00:00:09Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer2', attributes: {} },
    ];
    // A MEASURED track (jsdom lays nothing out, so the real controller measures 0 → %-placement and no
    // clustering). Stand in for the measured reading-position model with the same shape the render reads.
    let jumped: string | null = null;
    (view as unknown as { nav: unknown }).nav = {
      activeId: '',
      landmarks: [],
      trackPx: 120,
      viewport: null,
      fractions: new Map([['u1', 0], ['a1', 0.5], ['u2', 0.55], ['a2', 1]]),
      jumpTo(id: string) {
        jumped = id;
      },
    };
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;

    const cluster = sr.querySelector('.run-spine-cluster') as HTMLButtonElement | null;
    expect(cluster).not.toBeNull();
    expect(cluster!.tagName).toBe('BUTTON'); // keyboard-operable by construction (Enter/Space)
    expect(cluster!.getAttribute('data-cluster-size')).toBe('6');
    expect(cluster!.textContent?.trim()).toBe('6');
    // The badge NAMES what it aggregates (decodable without a legend), on both the a11y name + tooltip.
    expect(cluster!.getAttribute('aria-label')).toContain('6 steps');
    expect(cluster!.getAttribute('title')).toBe(cluster!.getAttribute('aria-label'));
    // The four turn LANDMARKS are never merged: they still render as their own markers.
    const nodeIds = [...sr.querySelectorAll('.run-spine-node')].map((n) => n.getAttribute('data-item-id'));
    expect(nodeIds).toEqual(['u1', 'a1', 'u2', 'a2']);
    // Operating it navigates to the group's first member.
    cluster!.click();
    expect(jumped).toBe('t1');
    __resetAgentSessionStore();
  });

  it('814 §D4 — spine texture markers draw as OUTLINE nodes; landmarks stay filled (no colour added)', async () => {
    // 809 finding 15's colour collision: a filled spine dot reads as the grounded-status dot. Fill is
    // reserved for LANDMARKS; texture is the same tone drawn as a ring — a non-colour cue, so the
    // statusTone vocabulary is untouched.
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    (view as unknown as { wideZone: boolean }).wideZone = true;
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q', attributes: {} },
      { id: 't1', occurredAt: '2026-01-01T00:00:02Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } },
      { id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer', attributes: {} },
      { id: 'u2', occurredAt: '2026-01-01T00:00:04Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q2', attributes: {} },
      { id: 'a2', occurredAt: '2026-01-01T00:00:05Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer2', attributes: {} },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    const glyphOf = (id: string): Element | null =>
      sr.querySelector(`.run-spine-node[data-item-id="${id}"] jf-run-node`);
    expect(glyphOf('t1')?.hasAttribute('outline')).toBe(true); // texture → ring
    expect(glyphOf('u1')?.hasAttribute('outline')).toBe(false); // landmark → filled
    expect(glyphOf('a1')?.hasAttribute('outline')).toBe(false);
    __resetAgentSessionStore();
  });

  it('603 D-4: a DOCUMENT-LEVEL agent answer shows the SOURCED provenance verdict, NOT "Grounded · 0 of N"', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent'; // currentShapeId → core.agent-run (grounded-index)
    // A persisted agent answer whose grounding sources are DOCUMENT-LEVEL (chunkIndex/startLine === -1
    // sentinel — the BLOCKED_LEGACY whole-doc case) with NO per-sentence cites. 603 D-1 showed this as
    // "No grounded sources"; the naive D-3 fix would show "Grounded · 0 of N". D-4: the SOURCED state.
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q', attributes: {} },
      {
        id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent',
        content: 'The Head process hosts the UI [1]. The Worker owns the index [2].',
        attributes: {
          sources: [
            { parentDocId: 'docs/a.md', chunkIndex: -1, path: 'docs/a.md', title: 'a.md', excerpt: 'x', startLine: -1, endLine: -1, headingText: '' },
            { parentDocId: 'docs/b.md', chunkIndex: -1, path: 'docs/b.md', title: 'b.md', excerpt: 'y', startLine: -1, endLine: -1, headingText: '' },
          ],
          citations: [],
        },
      },
    ];
    view.requestUpdate();
    await view.updateComplete;

    const text = (view.shadowRoot!.textContent ?? '').replace(/\s+/g, ' ');
    // The badge states provenance over the 2 documents…
    expect(text).toContain('Based on 2 documents');
    // …and the frame line is the honest SOURCED header, not the warning ungrounded one.
    expect(text).toContain('per-sentence grounding not verified');
    // The over-confident lie must NOT appear.
    expect(text).not.toContain('Grounded · 0');
    expect(view.shadowRoot!.querySelector('.grounding-badge-sourced')).not.toBeNull();
    __resetAgentSessionStore();
  });

  it('720: a SETTLED CHUNK-PRECISE agent answer with ZERO cites shows provenance, NOT "Grounded · 0 of N"', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent'; // currentShapeId → core.agent-run (grounded-index)
    // A persisted (settled) agent answer whose sources ARE chunk-precise (real chunkIndex >= 0) but whose
    // matcher tied NO sentence to a passage (empty citations). Pre-720 this fell through to the default
    // branch and rendered the self-contradictory "Grounded · 0 of N sentences". 720: the settled render
    // states provenance instead — the frame is `sourced`, never over-confident.
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q', attributes: {} },
      {
        id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent',
        content: 'The Head process hosts the UI. The Worker owns the index.',
        attributes: {
          sources: [
            { parentDocId: 'docs/a.md', chunkIndex: 0, path: 'docs/a.md', title: 'a.md', excerpt: 'x', startLine: 1, endLine: 5, headingText: '' },
            { parentDocId: 'docs/b.md', chunkIndex: 1, path: 'docs/b.md', title: 'b.md', excerpt: 'y', startLine: 1, endLine: 5, headingText: '' },
          ],
          citations: [],
        },
      },
    ];
    view.requestUpdate();
    await view.updateComplete;

    const text = (view.shadowRoot!.textContent ?? '').replace(/\s+/g, ' ');
    // The self-contradictory over-confidence must NOT appear.
    expect(text).not.toContain('Grounded · 0');
    // Provenance is stated honestly for the chunk-precise-but-unmatched settled case.
    expect(text).toContain('Based on 2 sources');
    expect(text).toContain('per-sentence grounding not verified');
    expect(view.shadowRoot!.querySelector('.grounding-badge-sourced')).not.toBeNull();
    __resetAgentSessionStore();
  });

  it('renders the projection as the single read-model: reconciled live turns dedupe, in-flight turns overlay (Pillar 2)', async () => {
    const view = mountView();
    await view.updateComplete;
    // S5a — the B14 auto-upgrade is retired: land in the documents plane EXPLICITLY
    // (the tier a user now reaches by tab click / escalation), where the thread renders.
    view.affordance = 'documents';
    view.requestUpdate();
    await view.updateComplete;
    // The record (GET /api/thread) holds two reconciled turns.
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'recorded question', attributes: {} },
      { id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'recorded answer', attributes: {} },
    ];
    // The live thread still holds the SAME user turn (not yet cleared) PLUS one in-flight user turn
    // the record has not reconciled. The single read-model must render the reconciled turn ONCE
    // (from the projection, deduped out of the overlay) and the in-flight turn via the overlay.
    // (User turns render as plain inline text; the assistant renders via <jf-markdown-block>,
    // so we assert dedup on the reliably-inlined user turns + the record's plain-text assistant.)
    (view as unknown as { thread: unknown[] }).thread = [
      { role: 'user', content: 'recorded question', shapeId: 'core.rag-ask' },
      { role: 'user', content: 'in-flight question', shapeId: 'core.rag-ask' },
    ];
    view.requestUpdate();
    await view.updateComplete;

    const text = (view.shadowRoot!.querySelector('.conversation') as HTMLElement).textContent ?? '';
    const count = (needle: string): number => text.split(needle).length - 1;
    // The reconciled user turn appears exactly once (no record-vs-live double render).
    expect(count('recorded question')).toBe(1);
    // The assistant renders once from the record via <jf-markdown-block> (561 P-A: its text is a
    // property, not light-DOM text), so assert on the single element + its .text.
    const assistants = view.shadowRoot!.querySelectorAll(
      '.message.assistant jf-markdown-block',
    );
    expect(assistants.length).toBe(1);
    expect((assistants[0] as unknown as { text: string }).text).toContain('recorded answer');
    // The in-flight turn the record hasn't caught up to still shows (live overlay).
    expect(count('in-flight question')).toBe(1);
  });

  it('the inline agent controller survives a chat↔agent round-trip (lossless, 561 P-B body-unification)', async () => {
    // Regression guard for the context-loss defect: the controller hosted INLINE must NOT be torn down
    // when the user crosses back to chat — its live session survives. Controller identity is the proxy:
    // a stable instance == the controller (and its run) was never destroyed.
    const view = mountView();
    await view.updateComplete;
    const ctrlOf = () => (view as unknown as { agentCtrl: unknown }).agentCtrl;
    // No controller before first entry (no idle cost for chat-only users); never a separate element.
    expect(ctrlOf()).toBeNull();
    expect(view.shadowRoot?.querySelector('jf-agent-view')).toBeNull();

    // Enter the action plane — lazily creates the hosted controller.
    view.affordance = 'agent';
    await view.updateComplete;
    const firstCtrl = ctrlOf();
    expect(firstCtrl).not.toBeNull();

    // Cross back to chat: the controller must NOT be destroyed.
    view.affordance = 'none';
    await view.updateComplete;
    expect(ctrlOf()).toBe(firstCtrl); // same instance == session preserved

    // Re-enter: the very same instance is reused (lossless round-trip end-to-end).
    view.affordance = 'agent';
    await view.updateComplete;
    expect(ctrlOf()).toBe(firstCtrl);
    expect(view.shadowRoot?.querySelector('jf-agent-view')).toBeNull();
  });

  // Tempdoc 621 Phase 4 (§F.3 regression oracle) — the live/record "prefer fresher evidence" reconciliation
  // is now computed ONCE by the merge authority (`attachLiveMatch` in `mergedTimeline`), not at render time.
  // This pins that invariant: a record assistant turn with a matching evidence-bearing live thread message
  // carries `attributes.live` (the renderer reads it); on reload (no live thread) it does NOT (render from
  // the record). The render outcomes are covered by the live==record + dedup tests above; this guards the
  // SEAM so a future change can't silently reintroduce a render-time cross-source reconciliation.
  it('621 §F.3 — the merge authority attaches the live match (prefer-fresher) once, not at render time', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      unifiedEvents: unknown[];
      thread: unknown[];
      mergedTimeline: () => Array<{ kind: string; content: string; attributes: Record<string, unknown> }>;
    };
    v.unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'Q', attributes: {} },
      { id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'A', attributes: {} },
    ];
    // In-session: the live thread holds the same answer WITH fresher evidence (sources).
    v.thread = [
      { role: 'user', content: 'Q', shapeId: 'core.rag-ask', id: 'u1' },
      { role: 'assistant', content: 'A', shapeId: 'core.rag-ask', sources: [{ parentDocId: 'd1', score: 0.9 }] },
    ];
    const merged = v.mergedTimeline();
    const asst = merged.find((it) => it.kind === 'assistant' && it.content === 'A')!;
    const user = merged.find((it) => it.kind === 'user' && it.content === 'Q')!;
    expect(asst.attributes.live, 'evidence-bearing live answer wins via the merge').toBeTruthy();
    expect(user.attributes.live, 'user turn matches the live thread by stable id').toBeTruthy();

    // Reload: the live thread is rebuilt WITHOUT evidence → no live match → render from the record.
    v.thread = [];
    const reloaded = v.mergedTimeline();
    const asstR = reloaded.find((it) => it.kind === 'assistant' && it.content === 'A')!;
    expect(asstR.attributes.live, 'reload renders from the record, not a stale live match').toBeUndefined();
  });

  // Tempdoc 621 Phase 4-full — a RELOADED RAG turn renders through the ONE chat/RAG body (renderMessage),
  // identically to live: it gains the SHAPE TAG (the convergence's user-visible delta) AND keeps the
  // record's citations. This is the full 610 §F.3 "live==record" closure — there is no longer a separate
  // (inline) record render path that can drift from the live one.
  it('621 Phase 4-full — a reloaded RAG turn renders via renderMessage (shape tag + record citations)', async () => {
    const view = mountView();
    await view.updateComplete;
    // The reloaded turn's shape tag/frame come from the window's CURRENT shape (621 Phase 4-full — per-message
    // shape is not persisted), so put the window in Documents mode: a reloaded Document-Q&A turn must read as
    // "Document Q&A", not the placeholder "Chat" the auto-restore seeds the thread with.
    view.affordance = 'documents';
    await view.updateComplete;
    const v = view as unknown as { unifiedEvents: unknown[]; thread: unknown[] };
    // The record (reload) holds a RAG answer with persisted citations + claimMatches; the live thread is
    // rebuilt role/content/id/shapeId only (no evidence) — the reload-durability case.
    v.unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'Q', attributes: {} },
      {
        id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'The answer.',
        attributes: {
          citations: [{ parentDocId: 'd1', chunkIndex: 0, score: 0.9, snippet: '', path: 'doc.txt' }],
          claimMatches: { matches: [{ sentenceIndex: 0, sentenceText: 'The answer.', chunkIndex: 0, similarity: 0.9, parentDocId: 'd1' }] },
        },
      },
    ];
    v.thread = [
      { role: 'user', content: 'Q', shapeId: 'core.rag-ask', id: 'u1' },
      { role: 'assistant', content: 'The answer.', shapeId: 'core.rag-ask', id: 'a1' },
    ];
    view.requestUpdate();
    await view.updateComplete;

    const assistant = view.shadowRoot!.querySelector('.message.assistant[data-item-id="a1"]');
    expect(assistant, 'the reloaded RAG answer renders as a chat assistant turn').not.toBeNull();
    // The convergence's visible delta: the shape tag that the old inline record branch omitted.
    expect(assistant!.querySelector('.message-shape-tag')?.textContent, 'reload gains the shape tag').toContain(
      'Document Q&A',
    );
    // The record's citations still render (no data lost) — the citations panel projects from the record.
    expect(assistant!.querySelector('jf-citations-panel'), 'record citations still render on reload').not.toBeNull();
  });

  // Tempdoc 621 review fix — a reloaded EXTRACT turn keeps its verbatim render (the `transform` frame), not
  // a markdown re-render. Extract carries no per-turn flag on the record, so `isExtract` is derived from the
  // window's current mode in the enrich; this pins that a reloaded extraction renders through the verbatim path.
  it('621 review — a reloaded extract turn renders verbatim (transform frame), not markdown', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'extract';
    await view.updateComplete;
    const v = view as unknown as { unifiedEvents: unknown[]; thread: unknown[] };
    v.unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', originator: 'user', content: 'Extract the fields', attributes: {} },
      { id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: '{"name":"X"}', attributes: {} },
    ];
    v.thread = [
      { role: 'user', content: 'Extract the fields', shapeId: 'core.extract', id: 'u1' },
      { role: 'assistant', content: '{"name":"X"}', shapeId: 'core.extract', id: 'a1' },
    ];
    view.requestUpdate();
    await view.updateComplete;

    const block = view.shadowRoot!.querySelector('.message.assistant[data-item-id="a1"] jf-markdown-block');
    expect(block, 'the reloaded extraction renders an answer block').not.toBeNull();
    expect(block!.getAttribute('frame'), 'reloaded extract uses the verbatim transform frame').toBe('transform');
  });
});

// Search Thread S7 (tempdoc decision 6) — the quiet per-turn receipt (grounding verdict + duration +
// model), EXTENDING the existing answer-frame line (never a second line). Full grounding-classification
// coverage lives in evidenceProjection.test.ts; these tests assert the render-site wiring only.
describe('UnifiedChatView per-turn receipt line (Search Thread S7, tempdoc decision 6)', () => {
  function chunkCitation(chunkIndex: number): {
    parentDocId: string;
    chunkIndex: number;
    chunkTotal: number;
    startChar: number;
    endChar: number;
    score: number;
    excerpt: string;
    startLine: number;
    endLine: number;
    headingText: string;
    headingLevel: number;
  } {
    return {
      parentDocId: 'doc-1',
      chunkIndex,
      chunkTotal: 1,
      startChar: 0,
      endChar: 10,
      score: 0.9,
      excerpt: 'excerpt',
      startLine: 1,
      endLine: 2,
      headingText: '',
      headingLevel: 0,
    };
  }

  afterEach(() => {
    // Restore the shared aiState fixture so other describe blocks see the original shape.
    delete (AI_STATE_READY as { runtime?: unknown }).runtime;
    __resetUiModeForTest();
  });

  it('Simple mode (default) omits the model name from the receipt (Tempdoc 738 C7)', async () => {
    (AI_STATE_READY as { runtime?: unknown }).runtime = { modelLabel: 'Llama 3 8B' };
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as { affordance: string; thread: unknown[] };
    v.affordance = 'documents';
    v.thread = [
      { role: 'user', content: 'q', shapeId: 'core.rag-ask', id: 'u1' },
      { role: 'assistant', content: 'a', shapeId: 'core.rag-ask', id: 'a1', sources: [chunkCitation(0)], durationMs: 3200 },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const line = view.shadowRoot!.querySelector('.message.assistant[data-item-id="a1"] .answer-frame');
    const text = (line!.textContent ?? '').replace(/\s+/g, ' ').trim();
    // Tempdoc 720 — a settled, zero-citation, chunk-precise answer now classifies as the `sourced`
    // (provenance) frame rather than `grounded`, so the receipt legitimately carries the honest
    // grounding badge text ahead of the duration tail (evidenceProjection.ts answerFrameLabel, `sourced` case).
    expect(text).toBe('Based on your documents — per-sentence grounding not verified · 3.2s');
    expect(text).not.toContain('Llama 3 8B');
    view.remove();
  });

  it('a grounded turn with duration + model shows ONLY the quiet receipt tail (no warning text), non-italic', async () => {
    (AI_STATE_READY as { runtime?: unknown }).runtime = { modelLabel: 'Llama 3 8B' };
    setUiMode('advanced');
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as { affordance: string; thread: unknown[] };
    v.affordance = 'documents';
    v.thread = [
      { role: 'user', content: 'q', shapeId: 'core.rag-ask', id: 'u1' },
      {
        role: 'assistant',
        content: 'a',
        shapeId: 'core.rag-ask',
        id: 'a1',
        sources: [chunkCitation(0)],
        // Tempdoc 720 — a genuinely grounded turn carries a per-sentence claim-match. A source WITHOUT
        // any matched cite is now the `sourced` (provenance) frame once settled, not silently "grounded";
        // this fixture tests the receipt tail on a GROUNDED answer, so it must actually be grounded.
        claims: [{ sentenceIndex: 0, sentenceText: 'a', verifiedScore: 0.9, lexicalScore: 0, verifiedRefs: [0], lexicalRefs: [] }],
        durationMs: 3200,
      },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const line = view.shadowRoot!.querySelector(
      '.message.assistant[data-item-id="a1"] .answer-frame',
    );
    expect(line, 'the receipt line renders even for a fully grounded answer').not.toBeNull();
    expect(line!.classList.contains('answer-frame-grounded')).toBe(true);
    const text = (line!.textContent ?? '').replace(/\s+/g, ' ').trim();
    expect(text).toBe('3.2s · Llama 3 8B');
    const receipt = line!.querySelector('.answer-receipt');
    expect(receipt, 'the duration+model tail is its own non-italic span').not.toBeNull();
    expect(getComputedStyle(receipt!).fontStyle).toBe('normal');
    // The receipt sits AFTER the answer block (under it), not before.
    const block = view.shadowRoot!.querySelector('.message.assistant[data-item-id="a1"] jf-markdown-block');
    expect(
      !!(block!.compareDocumentPosition(line!) & Node.DOCUMENT_POSITION_FOLLOWING),
      'the receipt line follows the answer block in document order',
    ).toBe(true);
    view.remove();
  });

  it('a partially-grounded turn keeps its warning text and appends the receipt tail on the SAME line', async () => {
    (AI_STATE_READY as { runtime?: unknown }).runtime = { modelLabel: 'Llama 3 8B' };
    setUiMode('advanced'); // Tempdoc 738 (C7) — the model name renders in Detailed mode.
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as { affordance: string; thread: unknown[] };
    v.affordance = 'documents';
    v.thread = [
      { role: 'user', content: 'q', shapeId: 'core.rag-ask', id: 'u1' },
      {
        role: 'assistant',
        content: 'a. b.',
        shapeId: 'core.rag-ask',
        id: 'a1',
        sources: [chunkCitation(0)],
        claims: [{ sentenceIndex: 0, sentenceText: 'a.', verifiedScore: 0.9, lexicalScore: 0, verifiedRefs: [0], lexicalRefs: [] }],
        durationMs: 500,
      },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const line = view.shadowRoot!.querySelector(
      '.message.assistant[data-item-id="a1"] .answer-frame',
    );
    expect(line).not.toBeNull();
    const text = (line!.textContent ?? '').replace(/\s+/g, ' ').trim();
    expect(text).toBe('Partly grounded — some statements are not backed by your documents · 500ms · Llama 3 8B');
    // Exactly ONE receipt-bearing line — never a second.
    expect(view.shadowRoot!.querySelectorAll('.message.assistant[data-item-id="a1"] .answer-frame').length).toBe(1);
    view.remove();
  });

  it('an extract (transform) turn keeps the unmissable banner BEFORE the answer, extended with the receipt', async () => {
    (AI_STATE_READY as { runtime?: unknown }).runtime = { modelLabel: 'Llama 3 8B' };
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as { affordance: string; thread: unknown[] };
    v.affordance = 'documents';
    v.thread = [
      { role: 'user', content: 'q', shapeId: 'core.extract', id: 'u1' },
      { role: 'assistant', content: '{}', shapeId: 'core.extract', id: 'a1', isExtract: true, durationMs: 800 },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const line = view.shadowRoot!.querySelector(
      '.message.assistant[data-item-id="a1"] .answer-frame-transform',
    );
    expect(line).not.toBeNull();
    expect((line!.textContent ?? '')).toContain('Model-generated structure');
    expect((line!.textContent ?? '')).toContain('800ms');
    const block = view.shadowRoot!.querySelector('.message.assistant[data-item-id="a1"] jf-markdown-block');
    expect(
      !!(line!.compareDocumentPosition(block!) & Node.DOCUMENT_POSITION_FOLLOWING),
      'the transform banner still precedes the answer block',
    ).toBe(true);
    view.remove();
  });

  it('omits duration (never fabricates) when the turn carries none — a reloaded turn with no stored durationMs', async () => {
    (AI_STATE_READY as { runtime?: unknown }).runtime = { modelLabel: 'Llama 3 8B' };
    setUiMode('advanced'); // Tempdoc 738 (C7) — the model name renders in Detailed mode.
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as { affordance: string; thread: unknown[] };
    v.affordance = 'documents';
    v.thread = [
      { role: 'user', content: 'q', shapeId: 'core.rag-ask', id: 'u1' },
      {
        role: 'assistant',
        content: 'a',
        shapeId: 'core.rag-ask',
        id: 'a1',
        sources: [chunkCitation(0)],
        // Tempdoc 720 — grounded fixture needs a matched cite (see the receipt-tail test above).
        claims: [{ sentenceIndex: 0, sentenceText: 'a', verifiedScore: 0.9, lexicalScore: 0, verifiedRefs: [0], lexicalRefs: [] }],
        // no durationMs — the reload case
      },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const line = view.shadowRoot!.querySelector(
      '.message.assistant[data-item-id="a1"] .answer-frame',
    );
    expect(line).not.toBeNull();
    expect((line!.textContent ?? '').replace(/\s+/g, ' ').trim()).toBe('Llama 3 8B');
    view.remove();
  });

  it('renders no line at all when neither a warning nor a duration/model receipt applies', async () => {
    delete (AI_STATE_READY as { runtime?: unknown }).runtime;
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as { affordance: string; thread: unknown[] };
    v.affordance = 'documents';
    v.thread = [
      { role: 'user', content: 'q', shapeId: 'core.rag-ask', id: 'u1' },
      // Tempdoc 720 — grounded fixture needs a matched cite; a bare source is now `sourced` once settled.
      { role: 'assistant', content: 'a', shapeId: 'core.rag-ask', id: 'a1', sources: [chunkCitation(0)], claims: [{ sentenceIndex: 0, sentenceText: 'a', verifiedScore: 0.9, lexicalScore: 0, verifiedRefs: [0], lexicalRefs: [] }] },
    ];
    view.requestUpdate();
    await view.updateComplete;
    expect(
      view.shadowRoot!.querySelector('.message.assistant[data-item-id="a1"] .answer-frame'),
    ).toBeNull();
    view.remove();
  });
});

describe('UnifiedChatView abort on conversation switch (Slice 516 FIX-T1)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('loadConversation aborts the in-flight AbortController', async () => {
    const view = mountView();
    // Simulate a send already in flight by assigning a real AbortController.
    const controller = new AbortController();
    const abortSpy = vi.spyOn(controller, 'abort');
    // @ts-expect-error — touching a private field intentionally for the test.
    view.abortController = controller;
    // @ts-expect-error — call the private method directly.
    await view.loadConversation('uc-other-session', 'core.free-chat');
    expect(abortSpy).toHaveBeenCalledTimes(1);
    // @ts-expect-error — sessionId is private but observable for the test.
    expect(view.sessionId).toBe('uc-other-session');
  });

  it('newConversation aborts the in-flight AbortController', () => {
    const view = mountView();
    const controller = new AbortController();
    const abortSpy = vi.spyOn(controller, 'abort');
    // @ts-expect-error — private field.
    view.abortController = controller;
    // @ts-expect-error — private method.
    view.newConversation();
    expect(abortSpy).toHaveBeenCalledTimes(1);
    expect(view.thread).toEqual([]);
  });

  it('abort is a no-op when no stream is in flight (abortController is null)', () => {
    const view = mountView();
    // @ts-expect-error — private field; start with no controller.
    view.abortController = null;
    // @ts-expect-error — private method; should not throw.
    expect(() => view.newConversation()).not.toThrow();
  });
});

describe('UnifiedChatView mid-stream conversation switch (Slice 517 FIX-U2)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('switching conversation mid-stream leaves the new conversation clean (bug-absence)', async () => {
    const view = mountView();
    // Wait for connectedCallback to settle (it awaits a few subscribes).
    await view.updateComplete;

    view.inputDraft = 'first message';
    view.affordance = 'documents';

    // Fire send() — consumeShapeStream is mocked to never resolve, so the
    // stream stays "in flight" indefinitely.
    // @ts-expect-error — private method.
    const sendPromise = view.send();
    // Yield to the microtask queue so send()'s synchronous setup completes
    // (this.abortController = new AbortController(); this.isStreaming = true).
    await new Promise((r) => setTimeout(r, 0));
    expect(view.isStreaming).toBe(true);

    // Mid-stream conversation switch — the bug being verified is that
    // the in-flight stream's onDone does NOT write into the new
    // conversation's thread.
    // @ts-expect-error — private method.
    await view.loadConversation('uc-other-session', 'core.free-chat');

    // The user message was appended during send() at line 1086, but
    // loadConversation resets the thread to the resumed conversation's
    // messages (which the mock returns as []).
    expect(view.thread).toEqual([]);
    // @ts-expect-error — sessionId is private but observable.
    expect(view.sessionId).toBe('uc-other-session');
    // Streaming flag is cleared via the abort's finally block in
    // consumeShapeStream's caller. Since our mock never resolves, the
    // try/finally never runs — but loadConversation's abort + the new
    // conversation's load completed without contamination, which is the
    // bug-absence guarantee.
    // (We don't assert isStreaming here because the mock doesn't simulate
    // the try/finally that would reset it; the abort-controller-aborted
    // signal is what matters.)
    // @ts-expect-error — private field; verify abort signal fired.
    expect(view.abortController?.signal.aborted).toBe(true);

    // Let the stale sendPromise hang — it never resolves, which simulates
    // a real in-flight stream that gets aborted but whose mock keeps the
    // Promise pending. Real fetch would reject with AbortError; our mock
    // simply doesn't resolve. Neither is a test failure since the assertion
    // is bug-absence (no contamination), not streamlifecycle completion.
    void sendPromise;
  });
});

describe('UnifiedChatView answer auto-run (548 §4.5)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Drain one-shots + store so each test starts clean.
    takePendingAutoRun();
    takePendingForceShape();
    resetUnifiedChatState();
  });

  it('auto-fires send() once when an answer verb parked the prompt + auto-run flag', async () => {
    // Mirror the IntentRouter `answer` lowering: prompt prefilled in the store,
    // shape forced, auto-run flag parked — all before the surface mounts.
    restoreUnifiedChat({ query: 'what is rust' });
    setPendingForceShape('core.rag-ask');
    setPendingAutoRun(true);

    const view = mountView();
    await view.updateComplete;

    const streamMock = vi.mocked(consumeShapeStream);
    expect(streamMock).toHaveBeenCalledTimes(1);
    // The forced rag-ask shape + prefilled prompt rode the dispatch body
    // (rag-ask carries the text as `question`, not `prompt`).
    const body = streamMock.mock.calls[0]![1] as { shapeId?: string; question?: string };
    expect(body.shapeId).toBe('core.rag-ask');
    expect(body.question).toBe('what is rust');
    // One-shot: the flag is consumed, so no second fire is queued.
    expect(takePendingAutoRun()).toBe(false);
    void view;
  });

  it('does NOT auto-fire on a plain mount (no answer verb)', async () => {
    restoreUnifiedChat({ query: 'hello there' });
    const view = mountView();
    await view.updateComplete;
    expect(vi.mocked(consumeShapeStream)).not.toHaveBeenCalled();
    void view;
  });
});

describe('UnifiedChatView declares sessionId field', () => {
  // Lightweight sanity test — proves the view mounts in the test
  // environment so the abort tests above aren't testing against a
  // broken instance.
  it('mounts with a fresh sessionId', () => {
    const view = mountView();
    // @ts-expect-error — sessionId is private but observable for the test.
    const sid: string = view.sessionId;
    expect(typeof sid).toBe('string');
    expect(sid.startsWith('uc-')).toBe(true);
  });
});

// Tempdoc 565 §33 FIX C — J/K step-nav is a WINDOW-level shortcut (the conversation div is not
// focusable, so a div-scoped @keydown never fired for a keyboard user). The handler must: react to a
// real window keydown, only on 'j'/'k', only in the agent affordance, NEVER while an editable element
// is focused (descending through nested shadow roots), and be removed on disconnect (no leak).
describe('UnifiedChatView §33 — window-level J/K step navigation', () => {
  type NavStub = {
    landmarks: { id: string; extent: { topFrac: number; botFrac: number } }[];
    activeId: string;
    jumpTo: (id: string) => void;
  };
  function mountWithLandmarks(): { view: UnifiedChatView; nav: NavStub } {
    const view = mountView();
    view.affordance = 'agent'; // the handler bails unless affordance==='agent' && wideViewport (default true)
    const nav = (view as unknown as { nav: NavStub }).nav;
    nav.landmarks = [
      { id: 'u1', extent: { topFrac: 0, botFrac: 0.33 } },
      { id: 'a1', extent: { topFrac: 0.33, botFrac: 0.66 } },
      { id: 'u2', extent: { topFrac: 0.66, botFrac: 1 } },
    ];
    return { view, nav };
  }
  // The handler's own index math, mirrored so the expected target is independent of the derived activeId.
  const expectedTarget = (nav: NavStub, dir: 1 | -1): string => {
    const cur = nav.landmarks.findIndex((l) => l.id === nav.activeId);
    const next =
      cur < 0
        ? dir > 0
          ? 0
          : nav.landmarks.length - 1
        : Math.min(nav.landmarks.length - 1, Math.max(0, cur + dir));
    return nav.landmarks[next]!.id;
  };
  const pressWindow = (key: string): void => {
    window.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
  };

  it('a window "j"/"k" keydown jumps the nav forward/back; other keys are ignored', () => {
    const { nav } = mountWithLandmarks();
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    // jumpTo is mocked → activeId never changes → cur is stable across calls, so j and k from the same
    // state resolve to DIFFERENT landmarks (forward-most vs back-most), proving direction is honored.
    const fwd = expectedTarget(nav, 1);
    const back = expectedTarget(nav, -1);
    expect(fwd).not.toBe(back);

    pressWindow('j');
    expect(jumpTo).toHaveBeenLastCalledWith(fwd);
    pressWindow('k');
    expect(jumpTo).toHaveBeenLastCalledWith(back);
    expect(jumpTo).toHaveBeenCalledTimes(2);

    pressWindow('x'); // a non-nav key must not navigate
    expect(jumpTo).toHaveBeenCalledTimes(2);
  });

  it('never hijacks typing — a focused <input> (light DOM) blocks navigation', () => {
    const { nav } = mountWithLandmarks();
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    const input = document.createElement('input');
    document.body.appendChild(input);
    input.focus();
    expect(document.activeElement).toBe(input);
    pressWindow('j');
    expect(jumpTo).not.toHaveBeenCalled();
    input.remove();
  });

  it('never hijacks typing — descends nested shadow roots to the truly-focused editable (the steer input case)', () => {
    const { nav } = mountWithLandmarks();
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    // Emulate document.activeElement being a shadow HOST whose shadowRoot.activeElement is the real
    // focused <input> (exactly the jf-unified-chat-view → .run-steer__input chain at runtime). The
    // handler must DESCEND to the inner input and bail — the most error-prone part of the guard.
    const innerInput = { tagName: 'INPUT', isContentEditable: false, shadowRoot: null };
    const host = { shadowRoot: { activeElement: innerInput }, tagName: 'JF-UNIFIED-CHAT-VIEW' };
    // Shadow the inherited Document.prototype.activeElement getter with a configurable OWN property, so
    // a plain `delete` restores the original behavior afterwards (we never touch the prototype).
    Object.defineProperty(document, 'activeElement', { configurable: true, get: () => host });
    try {
      pressWindow('j');
      expect(jumpTo).not.toHaveBeenCalled();
    } finally {
      delete (document as unknown as Record<string, unknown>).activeElement;
    }
  });

  it('never hijacks typing — a focused <select> blocks navigation (tempdoc 857 PR-A: a fix, not a port)', () => {
    // This case FAILED before 857 PR-A. The inline guard here covered INPUT/TEXTAREA/contentEditable
    // and omitted SELECT, while `commands/KeybindingRegistry.ts:163-167` — the second copy of the
    // same check — always covered it. The omission was live: this view renders a
    // `<select class="workflow-picker">` (`views/UnifiedChatView.ts:3986`), and with it focused a
    // `j` press stole the element's own type-ahead. Re-pointing this handler at the shared
    // `isTypingTarget` union fixes it as a side effect of the port.
    const { nav } = mountWithLandmarks();
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    const picker = document.createElement('select');
    document.body.appendChild(picker);
    picker.focus();
    expect(document.activeElement).toBe(picker);
    pressWindow('j');
    expect(jumpTo).not.toHaveBeenCalled();
    picker.remove();
  });

  it('removes the window listener on disconnect (no leak after the view is gone)', () => {
    const { view, nav } = mountWithLandmarks();
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    view.remove(); // → disconnectedCallback → removeEventListener('keydown', boundWindowKeydown)
    pressWindow('j');
    expect(jumpTo).not.toHaveBeenCalled();
  });

  /**
   * Tempdoc 864 Layer 2(b)/(d) — this window's j/k listener has the same shape as the one 864's L10
   * defect was found on (Search v3's), so it takes the same two shared guards rather than being the
   * one global handler still leaking: IME composition + auto-repeat, and an open modal owning the
   * keyboard wherever focus sits.
   */
  it('864: stands down for IME composition, auto-repeat, and an open modal', () => {
    const { nav } = mountWithLandmarks();
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'j', isComposing: true, bubbles: true }));
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'j', repeat: true, bubbles: true }));
    expect(jumpTo).not.toHaveBeenCalled();

    const modality = new ModalityController({
      addController: () => {},
      removeController: () => {},
      requestUpdate: () => {},
      updateComplete: Promise.resolve(true),
    });
    modality.enter();
    try {
      pressWindow('j');
      expect(jumpTo, 'j navigated under an open modal').not.toHaveBeenCalled();
    } finally {
      modality.exit({ skipFocusRestore: true });
    }
    pressWindow('j');
    expect(jumpTo, 'the guards released with the modal').toHaveBeenCalledTimes(1);
  });
});

// Tempdoc 811 C-4 — the two "Searching N …" strings must describe the population a DEFAULT-scope
// search can actually return, not the whole index (which includes agent-run transcripts the default
// scope excludes and app-internal docs the user never added).
describe('UnifiedChatView corpus counts (tempdoc 811 C-4)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    searchListener = null;
  });

  function corpusText(view: UnifiedChatView): string {
    return view.shadowRoot?.querySelector('[data-testid="landing-corpus"]')?.textContent?.trim() ?? '';
  }

  async function previewText(view: UnifiedChatView, index: unknown): Promise<string> {
    view.aiState = { ...AI_STATE_READY, index } as unknown as UnifiedChatView['aiState'];
    view.affordance = 'documents';
    await view.updateComplete;
    return view.shadowRoot?.querySelector('.affordance-preview')?.textContent?.trim() ?? '';
  }

  it('the documents preview counts the searchable population, not the whole index', async () => {
    const view = mountView();
    await view.updateComplete;
    expect(await previewText(view, { documentCount: known(140), searchableDocumentCount: known(31) }))
      .toBe('Searching 31 documents');
    view.remove();
  });

  it('the documents preview falls back to the whole-index count when the field is absent', async () => {
    const view = mountView();
    await view.updateComplete;
    // Pre-811 backend: the field never arrives, so the store reports UNKNOWN.
    expect(await previewText(view, { documentCount: known(140), searchableDocumentCount: UNKNOWN }))
      .toBe('Searching 140 documents');
    view.remove();
  });

  it('a KNOWN searchable count of 0 is shown as 0, never silently replaced by the index count', async () => {
    const view = mountView();
    await view.updateComplete;
    // The precision this pins: `0` is a real value (an index of nothing but excluded collections),
    // NOT the absent case — a `||`-style fallback would wrongly print 140 here.
    expect(await previewText(view, { documentCount: known(140), searchableDocumentCount: known(0) }))
      .toBe('Searching 0 documents');
    view.remove();
  });

  it('the landing corpus line counts the searchable population, falling back when absent', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    view.aiState = {
      ...AI_STATE_READY,
      lastSettledIndex: { documentCount: 140, searchableDocumentCount: 31, indexSizeBytes: null },
    } as unknown as UnifiedChatView['aiState'];
    await view.updateComplete;
    expect(corpusText(view)).toBe('Searching 31 files');

    view.aiState = {
      ...AI_STATE_READY,
      lastSettledIndex: { documentCount: 140, searchableDocumentCount: null, indexSizeBytes: null },
    } as unknown as UnifiedChatView['aiState'];
    await view.updateComplete;
    expect(corpusText(view)).toBe('Searching 140 files');
    view.remove();
  });

  it('a searchable count of 0 offers "Add folders" instead of claiming to search 0 files', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    view.aiState = {
      ...AI_STATE_READY,
      lastSettledIndex: { documentCount: 140, searchableDocumentCount: 0, indexSizeBytes: null },
    } as unknown as UnifiedChatView['aiState'];
    await view.updateComplete;
    expect(corpusText(view)).not.toContain('Searching');
    expect(view.shadowRoot?.querySelector('.landing-add-folders')).not.toBeNull();
    view.remove();
  });
});

describe('UnifiedChatView retrieve base tier (577 Goal 3 §3.2)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    searchListener = null;
  });

  it('reaches the retrieve tier via an explicit affordance set, with no tab row rendered (Search Thread S5b)', async () => {
    const view = mountView();
    await view.updateComplete;
    // S5a — boot already derives 'retrieve' (no B14 auto-upgrade to leave from), so start from an
    // explicit documents pin to exercise the selection transition the (now-retired) tab used to.
    view.affordance = 'documents';
    await view.updateComplete;
    // Search Thread S5b — the affordance tab row is retired; the retrieve tier is reached only via
    // explicit affordance assignment (programmatic entry — palette/actions/deep-links) now.
    expect(view.shadowRoot?.querySelector('.affordance-bar')).toBeNull();
    view.affordance = 'retrieve';
    await view.updateComplete;
    expect(view.affordance).toBe('retrieve');
    // Search Thread D2/D3 (stage S2) — the chat thread is replaced by the bare LANDING (ephemeral, no
    // thread history), not the old static retrieve-empty-prompt (retired in favor of the landing bar).
    expect(view.shadowRoot?.querySelector('[data-testid="retrieve-empty-prompt"]')).toBeNull();
    expect(view.shadowRoot?.querySelector('[data-testid="landing-corpus"]')).not.toBeNull();
  });

  it('renders the ephemeral hit-list from the search store, never as thread turns', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    await view.updateComplete;
    expect(searchListener).not.toBeNull();
    // Push a fabricated quick-pass snapshot (no network).
    searchListener!({
      query: 'invoice',
      results: [
        { id: 'h1', title: 'Q1 invoice', path: '/docs/q1.md', snippet: 'total due', kind: 'markdown' },
        { id: 'h2', title: 'helper.ts', path: '/src/helper.ts', snippet: 'function pay()', kind: 'code' },
      ],
      totalHits: 2,
      isSearching: false,
      processingTimeMs: 12,
      error: null,
      searchTrace: null,
    });
    await view.updateComplete;
    // Search Thread S1 — the tier renders the ONE results card; rows live in its shadow root.
    const card = view.shadowRoot?.querySelector('jf-results-card') as LitElement | null;
    expect(card).not.toBeNull();
    await card!.updateComplete;
    const rows = card!.shadowRoot?.querySelectorAll('[data-testid="search-result-row"]');
    expect(rows?.length).toBe(2);
    // The hit-list is NOT a chat message — no assistant/user turn was created.
    expect(view.thread.length).toBe(0);
  });

  it('the retrieve tier drives the search store on input/submit, not the LLM send path', async () => {
    const { setQuery, submitSearch } = await import('../state/searchState.js');
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    await view.updateComplete;
    const composer = view.shadowRoot?.querySelector('jf-composer');
    expect(composer).not.toBeNull();
    composer!.dispatchEvent(
      new CustomEvent('composer-input', { detail: { value: 'budget' } }),
    );
    expect(setQuery).toHaveBeenCalledWith('budget');
    composer!.dispatchEvent(new CustomEvent('composer-submit'));
    expect(submitSearch).toHaveBeenCalled();
  });

  it('renders the shared facet chips + per-hit "why" disclosure in the retrieve tier (§3.9a parity)', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    await view.updateComplete;
    expect(searchListener).not.toBeNull();
    searchListener!({
      query: 'invoice',
      results: [
        {
          id: 'h1',
          title: 'Q1 invoice',
          path: '/docs/q1.md',
          snippet: 'total due',
          kind: 'markdown',
          trace: [{ id: 'sparse-retrieval', rank: 1, score: 5.5 }],
        },
      ],
      totalHits: 1,
      isSearching: false,
      processingTimeMs: 12,
      error: null,
      searchTrace: null,
      facets: { file_kind: { markdown: 3, pdf: 1 } },
    });
    await view.updateComplete;
    // Search Thread S1 — facet chips + why disclosures render inside the shared card.
    const card = view.shadowRoot?.querySelector('jf-results-card') as LitElement | null;
    expect(card).not.toBeNull();
    await card!.updateComplete;
    // Facet chips (shared render) appear above the list.
    expect(card!.shadowRoot?.querySelector('[data-testid="facet-row"]')).not.toBeNull();
    // Per-hit "Why this result?" disclosure (shared render) appears on the row.
    expect(card!.shadowRoot?.querySelector('[data-testid="hit-why"]')).not.toBeNull();
  });

  it('602 R3 — the retrieve row formats the path + highlights query terms like the Search surface', async () => {
    setUiMode('advanced'); // Tempdoc 738 (C4) — the middle-ellipsis full path is the Detailed form.
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    await view.updateComplete;
    expect(searchListener).not.toBeNull();
    const longPath =
      '/Users/alex/Documents/projects/justsearch/modules/ui-web/src/shell-v0/quarterly-report.md';
    searchListener!({
      query: 'invoice',
      results: [
        {
          id: 'h1',
          title: 'Q1 report',
          path: longPath,
          snippet: 'the quarterly invoice total is due',
          kind: 'markdown',
        },
      ],
      totalHits: 1,
      matchCount: 1,
      facetsTruncated: false,
      isSearching: false,
      processingTimeMs: 12,
      error: null,
      searchTrace: null,
    });
    await view.updateComplete;
    // Search Thread S1 — the row presentation lives in the shared card's shadow root.
    const card = view.shadowRoot!.querySelector('jf-results-card') as LitElement;
    await card.updateComplete;
    const sr = card.shadowRoot!;
    // Path is middle-ellipsis formatted (shared formatDisplayPath) — keeps the filename,
    // drops the middle — not the raw 90-char path; the raw path stays in the title attr.
    const pathEl = sr.querySelector('.row .path')!;
    expect(pathEl.textContent).toContain('…');
    expect(pathEl.textContent).toContain('quarterly-report.md');
    expect(pathEl.getAttribute('title')).toBe(longPath);
    // Query term is wrapped in the shared <mark class="hl"> highlight.
    const mark = sr.querySelector('.row .snippet mark.hl');
    expect(mark, 'snippet highlights the query term').not.toBeNull();
    expect(mark!.textContent?.toLowerCase()).toBe('invoice');
  });

  // Tempdoc 597 R-1 — the retrieve tier projects the SAME funnel count label as the dedicated
  // Search surface (shared matchCountLabel), reading `matchCount`, never the window `totalHits`.
  const retrieveHit = (id: string) => ({
    id,
    title: id,
    path: `/${id}.md`,
    snippet: '',
    kind: 'markdown' as const,
  });

  it('597 R-1 — shows the matchCount funnel label "Top N of M matches", not window-as-count', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    await view.updateComplete;
    searchListener!({
      query: 'the',
      results: [retrieveHit('a'), retrieveHit('b')],
      matchCount: 451, // the TRUE matched total → the headline
      totalHits: 110, // the bounded fused-union window → must NOT be the headline
      facetsTruncated: false,
      isSearching: false,
      processingTimeMs: 12,
      error: null,
      searchTrace: null,
    });
    await view.updateComplete;
    const card = view.shadowRoot!.querySelector('jf-results-card') as LitElement;
    await card.updateComplete;
    const meta = card.shadowRoot?.querySelector('[data-testid="card-meta"]')?.textContent ?? '';
    expect(meta).toContain('Top 2 of 451 matches');
    expect(meta).not.toContain('110 result'); // the old window-as-count is gone
  });

  it('597 R-1 — collapses to "M matches" when the whole match set is on screen', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    await view.updateComplete;
    searchListener!({
      query: 'budget',
      results: [retrieveHit('a'), retrieveHit('b'), retrieveHit('c')],
      matchCount: 3,
      totalHits: 50,
      facetsTruncated: false,
      isSearching: false,
      processingTimeMs: 5,
      error: null,
      searchTrace: null,
    });
    await view.updateComplete;
    const card = view.shadowRoot!.querySelector('jf-results-card') as LitElement;
    await card.updateComplete;
    const meta = card.shadowRoot?.querySelector('[data-testid="card-meta"]')?.textContent ?? '';
    expect(meta).toContain('3 matches');
    expect(meta).not.toContain('50 result');
  });

  it('boots into the retrieve base tier, not free-chat (577 Goal 3 §3.11)', () => {
    // The cold-boot default is the always-available `retrieve` tier (the search entry tier).
    // Checked pre-connect so the AI-capability auto-upgrade (which moves an online window to
    // `documents`) does not mask the constructor default. Offline, this is what the window lands in.
    const view = document.createElement('jf-unified-chat-view') as UnifiedChatView;
    expect(view.affordance).toBe('retrieve');
  });

  it('labels the retrieve submit control "Search", never "Send" or "AI Offline" (577 Goal 3 Fix B)', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    await view.updateComplete;
    const composer = view.shadowRoot?.querySelector('jf-composer');
    expect(composer?.getAttribute('submit-label')).toBe('Search');
    // The "AI offline" tooltip never applies to the retrieve tier (search needs no chat model).
    expect(composer?.getAttribute('submit-title')).toBe('');
  });

  it('keeps past chats reachable on the retrieve landing, hidden once searching (577 Goal 3 §3.13 / A2)', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      showResumePrompt: boolean;
      recentSession: unknown;
      thread: unknown[];
      unifiedEvents: unknown[];
      searchSnapshot: { query: string } | null;
    };
    // Boot tier with a past session available, no query yet (the bare landing).
    v.affordance = 'retrieve';
    v.showResumePrompt = true;
    v.recentSession = { sessionId: 's-prev', firstMessage: 'past chat', timestamp: 0 };
    v.thread = [];
    v.unifiedEvents = [];
    v.searchSnapshot = null;
    view.requestUpdate();
    await view.updateComplete;
    // The resume card is reachable in the retrieve base tier — past chats viewable even offline.
    expect(
      view.shadowRoot!.querySelector('.resume-prompt'),
      'retrieve landing (no query) shows the resume card',
    ).toBeTruthy();

    // Once a query is active the hit-list owns the zone, so the card steps aside (no clutter).
    v.searchSnapshot = { query: 'invoices' };
    view.requestUpdate();
    await view.updateComplete;
    expect(
      view.shadowRoot!.querySelector('.resume-prompt'),
      'an active retrieve query hides the resume card',
    ).toBeNull();
    view.remove();
  });

  // Search Thread D2/D3 (stage S2) — the floor rule, per-turn ROUTE, and the bare-landing search bar.
  describe('Search Thread D2/D3 (stage S2) — route + landing', () => {
    it('the floor rule feeds instant search on every keystroke, even outside retrieve', async () => {
      const { setQuery } = await import('../state/searchState.js');
      const view = mountView();
      await view.updateComplete;
      view.affordance = 'documents';
      await view.updateComplete;
      const composer = view.shadowRoot?.querySelector('jf-composer');
      expect(composer).not.toBeNull();
      composer!.dispatchEvent(
        new CustomEvent('composer-input', { detail: { value: 'quarterly report' } }),
      );
      expect(setQuery).toHaveBeenCalledWith('quarterly report');
      view.remove();
    });

    it('routes an interrogative draft to Ask (escalates), never to submitSearch', async () => {
      const { submitSearch } = await import('../state/searchState.js');
      const view = mountView();
      await view.updateComplete;
      view.affordance = 'retrieve';
      view.inputDraft = 'how do I configure ocr?';
      await view.updateComplete;
      const composer = view.shadowRoot?.querySelector('jf-composer');
      composer!.dispatchEvent(new CustomEvent('composer-submit'));
      await view.updateComplete;
      expect(view.affordance).toBe('documents');
      expect(submitSearch).not.toHaveBeenCalled();
      view.remove();
    });

    it('routes a keyword draft to Search (submitSearch), staying in the retrieve tier', async () => {
      const { submitSearch } = await import('../state/searchState.js');
      const view = mountView();
      await view.updateComplete;
      view.affordance = 'retrieve';
      view.inputDraft = 'invoice march';
      await view.updateComplete;
      const composer = view.shadowRoot?.querySelector('jf-composer');
      composer!.dispatchEvent(new CustomEvent('composer-submit'));
      await view.updateComplete;
      expect(submitSearch).toHaveBeenCalled();
      expect(view.affordance).toBe('retrieve');
      view.remove();
    });

    it('composer-submit-alt sends the OPPOSITE route from composer-submit', async () => {
      const { submitSearch } = await import('../state/searchState.js');
      const view = mountView();
      await view.updateComplete;
      view.affordance = 'retrieve';
      view.inputDraft = 'invoice march'; // heuristic guesses 'search'
      await view.updateComplete;
      const composer = view.shadowRoot?.querySelector('jf-composer');
      composer!.dispatchEvent(new CustomEvent('composer-submit-alt'));
      await view.updateComplete;
      // Ctrl+Enter sends the OTHER way: the opposite of 'search' is 'ask' — escalates.
      expect(view.affordance).toBe('documents');
      expect(submitSearch).not.toHaveBeenCalled();
      view.remove();
    });

    it('renders the route chip in retrieve; route-toggle flips the displayed route', async () => {
      const view = mountView();
      await view.updateComplete;
      view.affordance = 'retrieve';
      view.inputDraft = 'invoice march'; // heuristic guesses 'search'
      await view.updateComplete;
      const chip = view.shadowRoot?.querySelector('jf-route-chip') as
        | (HTMLElement & { route: string })
        | null;
      expect(chip).not.toBeNull();
      expect(chip!.route).toBe('search');
      chip!.dispatchEvent(new CustomEvent('route-toggle', { bubbles: true, composed: true }));
      await view.updateComplete;
      const chipAfter = view.shadowRoot?.querySelector('jf-route-chip') as
        | (HTMLElement & { route: string })
        | null;
      expect(chipAfter!.route).toBe('ask');
      view.remove();
    });

    it('pins the chip (and the route) to search when Ask is unavailable', async () => {
      const view = mountView();
      await view.updateComplete;
      view.affordance = 'retrieve';
      view.aiState = {
        ...AI_STATE_READY,
        capabilities: { ...AI_STATE_READY.capabilities, chat: false },
      } as unknown as UnifiedChatView['aiState'];
      view.inputDraft = 'how do I configure ocr?'; // heuristic would otherwise guess 'ask'
      await view.updateComplete;
      const chip = view.shadowRoot?.querySelector('jf-route-chip') as
        | (HTMLElement & { route: string; pinned: boolean })
        | null;
      expect(chip).not.toBeNull();
      expect(chip!.pinned).toBe(true);
      expect(chip!.route).toBe('search');
      view.remove();
    });

    // Tempdoc 807 A.3 (round-13 R13-F2) — a DEAD backend leaves `capabilities.chat` true (it is read
    // off the retained inference snapshot), so before the liveness gate the chip happily offered Ask
    // against a process that no longer existed. Liveness now pins it exactly as a chat gap does.
    it('807: pins to search when the snapshot is no longer live, even though chat still reads capable', async () => {
      const view = mountView();
      await view.updateComplete;
      view.affordance = 'retrieve';
      view.aiState = {
        ...AI_STATE_READY,
        capabilities: { ...AI_STATE_READY.capabilities, chat: true },
        snapshotLive: false,
      } as unknown as UnifiedChatView['aiState'];
      view.inputDraft = 'how do I configure ocr?';
      await view.updateComplete;
      const chip = view.shadowRoot?.querySelector('jf-route-chip') as
        | (HTMLElement & { route: string; pinned: boolean })
        | null;
      expect(chip!.pinned).toBe(true);
      expect(chip!.route).toBe('search');
      view.remove();
    });

    it('807 ANTI-REGRESSION: the SAME state with a live snapshot is not pinned', async () => {
      const view = mountView();
      await view.updateComplete;
      view.affordance = 'retrieve';
      view.aiState = { ...AI_STATE_READY } as unknown as UnifiedChatView['aiState'];
      view.inputDraft = 'how do I configure ocr?';
      await view.updateComplete;
      const chip = view.shadowRoot?.querySelector('jf-route-chip') as
        | (HTMLElement & { route: string; pinned: boolean })
        | null;
      expect(chip!.pinned).toBe(false);
      expect(chip!.route).toBe('ask');
      view.remove();
    });

    it('never escalates to Ask when pinned — submit runs a search instead', async () => {
      const { submitSearch } = await import('../state/searchState.js');
      const view = mountView();
      await view.updateComplete;
      view.affordance = 'retrieve';
      view.aiState = {
        ...AI_STATE_READY,
        capabilities: { ...AI_STATE_READY.capabilities, chat: false },
      } as unknown as UnifiedChatView['aiState'];
      view.inputDraft = 'how do I configure ocr?';
      await view.updateComplete;
      const composer = view.shadowRoot?.querySelector('jf-composer');
      composer!.dispatchEvent(new CustomEvent('composer-submit'));
      await view.updateComplete;
      expect(submitSearch).toHaveBeenCalled();
      expect(view.affordance).toBe('retrieve');
      view.remove();
    });

    it('renders the bare landing when empty, docks when a query is typed — exactly one jf-composer either way', async () => {
      const view = mountView();
      await view.updateComplete;
      view.affordance = 'retrieve';
      await view.updateComplete;
      expect(view.shadowRoot?.querySelector('[data-testid="landing-corpus"]')).not.toBeNull();
      expect(view.shadowRoot?.querySelector('[data-testid="retrieve-empty-prompt"]')).toBeNull();
      expect(view.shadowRoot?.querySelectorAll('jf-composer').length).toBe(1);
      // Landing mode is a CLASS on the stable bottom slot, not a different mount point.
      expect(view.shadowRoot?.querySelector('.composer.landing-dock')).not.toBeNull();
      const textareaBefore = view.shadowRoot?.querySelector('jf-composer textarea');

      expect(searchListener).not.toBeNull();
      searchListener!({ ...SEARCH_EMPTY, query: 'invoice' });
      await view.updateComplete;
      expect(view.shadowRoot?.querySelector('[data-testid="landing-corpus"]')).toBeNull();
      expect(view.shadowRoot?.querySelectorAll('jf-composer').length).toBe(1);
      expect(view.shadowRoot?.querySelector('.composer.landing-dock')).toBeNull();
      // Stable-slot invariant (live-validation finding): the textarea must be the SAME node
      // across the landing→docked transition — a re-parented textarea drops keystrokes that
      // race the first render, eating the user's sentence after its first character.
      const textareaAfter = view.shadowRoot?.querySelector('jf-composer textarea');
      expect(textareaAfter).toBe(textareaBefore);
      view.remove();
    });

    it('the jf-focus-composer window event focuses the composer textarea', async () => {
      const view = mountView();
      await view.updateComplete;
      const textarea = view.shadowRoot?.querySelector('jf-composer textarea') as
        | HTMLTextAreaElement
        | null;
      expect(textarea).not.toBeNull();
      const focusSpy = vi.spyOn(textarea!, 'focus');
      window.dispatchEvent(new CustomEvent('jf-focus-composer'));
      expect(focusSpy).toHaveBeenCalled();
      view.remove();
    });
  });
});

// Search Thread tempdoc D5 (stage S3) — pinned scope chips (a file / a result set) constrain both
// instant search and a grounded Ask. searchState's mutators are stubbed above (scopeChipsMock); these
// tests assert the VIEW's mount/render/handler wiring, not the store itself (covered by
// searchState.test.ts).
describe('Search Thread D5 (stage S3) — scope chips', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    scopeChipsMock.chips = [];
  });

  function pushHits(): void {
    searchListener!({
      query: 'invoice',
      results: [
        { id: 'h1', title: 'Q1 invoice', path: '/docs/q1.md', snippet: 'total due', kind: 'markdown' },
        { id: 'h2', title: 'helper.ts', path: '/src/helper.ts', snippet: 'function pay()', kind: 'code' },
      ],
      totalHits: 2,
      isSearching: false,
      processingTimeMs: 12,
      error: null,
      searchTrace: null,
    });
  }

  it('renders the scope-chip row from the subscribed store', async () => {
    const view = mountView();
    await view.updateComplete;
    expect(scopeChipsListener).not.toBeNull();
    scopeChipsListener!([{ kind: 'file', label: 'q1.md', docIds: ['/docs/q1.md'] }]);
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('[data-testid="scope-chip-row"]')).not.toBeNull();
    view.remove();
  });

  it('renders no scope-chip row when no chips are pinned', async () => {
    const view = mountView();
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('[data-testid="scope-chip-row"]')).toBeNull();
    view.remove();
  });

  it('card-scope-file ("Ask about this file") adds a file chip carrying the PATH, not the hit id', async () => {
    const { addScopeChip } = await import('../state/searchState.js');
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    await view.updateComplete;
    pushHits();
    await view.updateComplete;
    const card = view.shadowRoot?.querySelector('jf-results-card') as LitElement;
    expect(card).not.toBeNull();
    card.dispatchEvent(
      new CustomEvent('card-scope-file', {
        detail: { id: 'h1', path: '/docs/q1.md', title: 'Q1 invoice' },
      }),
    );
    await view.updateComplete;
    expect(addScopeChip).toHaveBeenCalledWith({
      kind: 'file',
      label: 'q1.md',
      docIds: ['/docs/q1.md'],
    });
    // Not pinned (chat capability is available per AI_STATE_READY) — routes to ask-readiness.
    expect(view.routeOverride).toBe('ask');
    view.remove();
  });

  it('card-scope-file does NOT flip routeOverride to ask when Ask is pinned to search', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    view.aiState = {
      ...AI_STATE_READY,
      capabilities: { ...AI_STATE_READY.capabilities, chat: false },
    } as unknown as UnifiedChatView['aiState'];
    await view.updateComplete;
    pushHits();
    await view.updateComplete;
    const card = view.shadowRoot?.querySelector('jf-results-card') as LitElement;
    card.dispatchEvent(
      new CustomEvent('card-scope-file', {
        detail: { id: 'h1', path: '/docs/q1.md', title: 'Q1 invoice' },
      }),
    );
    await view.updateComplete;
    expect(view.routeOverride).toBeNull();
    view.remove();
  });

  it('shows "Ask about these N results" only when >1 selected, and clicking pins a result-set chip of PATHS', async () => {
    const { addScopeChip } = await import('../state/searchState.js');
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    // handleRetrieveCardSelection's >1 branch publishes a real 'result-set' SelectionItem
    // (setInternalSelection, real selectionState module — not mocked); its subscription would
    // otherwise auto-flip affordance to 'documents' (refreshDocsFromSelection) since the affordance
    // here was set directly, not via toggleAffordance(). Pin it explicit, as a real click would.
    (view as unknown as { userToggledAffordance: boolean }).userToggledAffordance = true;
    await view.updateComplete;
    pushHits();
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('[data-testid="scope-selection-btn"]')).toBeNull();
    const card = view.shadowRoot?.querySelector('jf-results-card') as LitElement;
    card.dispatchEvent(
      new CustomEvent('card-selection', {
        detail: { ids: ['h1', 'h2'], primaryId: 'h1', primaryIndex: 0 },
      }),
    );
    await view.updateComplete;
    const btn = view.shadowRoot?.querySelector(
      '[data-testid="scope-selection-btn"]',
    ) as HTMLButtonElement | null;
    expect(btn).not.toBeNull();
    expect(btn!.textContent).toContain('2 results');
    btn!.click();
    await view.updateComplete;
    expect(addScopeChip).toHaveBeenCalledWith({
      kind: 'result-set',
      label: '2 results',
      docIds: ['/docs/q1.md', '/src/helper.ts'],
    });
    expect(view.routeOverride).toBe('ask');
    view.remove();
  });

  it('Backspace on an empty draft pops the last pinned scope chip', async () => {
    const { removeScopeChip } = await import('../state/searchState.js');
    const view = mountView();
    await view.updateComplete;
    scopeChipsListener!([
      { kind: 'file', label: 'q1.md', docIds: ['/docs/q1.md'] },
      { kind: 'file', label: 'helper.ts', docIds: ['/src/helper.ts'] },
    ]);
    view.inputDraft = '';
    await view.updateComplete;
    const composer = view.shadowRoot?.querySelector('jf-composer');
    expect(composer).not.toBeNull();
    composer!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace', bubbles: true }));
    await view.updateComplete;
    expect(removeScopeChip).toHaveBeenCalledWith(1);
    view.remove();
  });

  it('Backspace does NOT pop a chip while the draft has text', async () => {
    const { removeScopeChip } = await import('../state/searchState.js');
    const view = mountView();
    await view.updateComplete;
    scopeChipsListener!([{ kind: 'file', label: 'q1.md', docIds: ['/docs/q1.md'] }]);
    view.inputDraft = 'still typing';
    await view.updateComplete;
    const composer = view.shadowRoot?.querySelector('jf-composer');
    composer!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace', bubbles: true }));
    await view.updateComplete;
    expect(removeScopeChip).not.toHaveBeenCalled();
    view.remove();
  });

  it('removing a chip via its remove affordance re-runs submitSearch while a retrieve query is active', async () => {
    const { submitSearch, removeScopeChip } = await import('../state/searchState.js');
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    await view.updateComplete;
    pushHits();
    scopeChipsListener!([{ kind: 'file', label: 'q1.md', docIds: ['/docs/q1.md'] }]);
    await view.updateComplete;
    const chip = view.shadowRoot?.querySelector('jf-scope-chip');
    expect(chip).not.toBeNull();
    chip!.dispatchEvent(new CustomEvent('scope-remove', { bubbles: true, composed: true }));
    await view.updateComplete;
    expect(removeScopeChip).toHaveBeenCalledWith(0);
    expect(submitSearch).toHaveBeenCalled();
    view.remove();
  });

  it('removing a chip outside the retrieve tier does NOT re-run submitSearch', async () => {
    const { submitSearch } = await import('../state/searchState.js');
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'documents';
    scopeChipsListener!([{ kind: 'file', label: 'q1.md', docIds: ['/docs/q1.md'] }]);
    await view.updateComplete;
    const chip = view.shadowRoot?.querySelector('jf-scope-chip');
    expect(chip).not.toBeNull();
    chip!.dispatchEvent(new CustomEvent('scope-remove', { bubbles: true, composed: true }));
    await view.updateComplete;
    expect(submitSearch).not.toHaveBeenCalled();
    view.remove();
  });

  it('a grounded Ask forwards the union of pinnedDocIds and scope-chip docIds (both PATHS), deduped', async () => {
    const view = mountView();
    await view.updateComplete;
    view.pinnedDocIds = ['/legacy/pinned.md', '/docs/q1.md'];
    scopeChipsListener!([
      { kind: 'file', label: 'q1.md', docIds: ['/docs/q1.md'] }, // overlaps pinnedDocIds — deduped
      { kind: 'result-set', label: '2 results', docIds: ['/docs/a.md', '/docs/b.md'] },
    ]);
    view.affordance = 'documents';
    view.inputDraft = 'summarize these';
    await view.updateComplete;
    const composer = view.shadowRoot?.querySelector('jf-composer');
    expect(composer).not.toBeNull();
    composer!.dispatchEvent(new CustomEvent('composer-submit'));
    await view.updateComplete;
    const streamMock = vi.mocked(consumeShapeStream);
    expect(streamMock).toHaveBeenCalledTimes(1);
    const body = streamMock.mock.calls[0]![1] as { shapeId?: string; docIds?: string[] };
    expect(body.shapeId).toBe('core.rag-ask');
    expect(body.docIds).toEqual(['/legacy/pinned.md', '/docs/q1.md', '/docs/a.md', '/docs/b.md']);
    view.remove();
  });
});

describe('Tempdoc 610 Phase A — transcript edit/retry controls', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    // The shared mock leaves consumeShapeStream never-resolving (to hold a
    // stream open). The edit/retry flow ends with a re-dispatch (send →
    // consumeShapeStream); let it resolve so `await commitEdit/retryFrom`
    // completes. branchConversation is called BEFORE the re-dispatch, so the
    // assertions on it hold regardless.
    vi.mocked(consumeShapeStream).mockImplementation(() => Promise.resolve());
  });

  function seedThread(view: UnifiedChatView, thread: unknown[]): void {
    const v = view as unknown as { thread: unknown[]; affordance: string; isStreaming: boolean };
    v.affordance = 'documents'; // a chat tier so renderMessage runs
    v.isStreaming = false;
    v.thread = thread;
    view.requestUpdate();
  }

  it('§D.2 — per-turn action bar renders below user + assistant turns with a ⋯ overflow', async () => {
    const view = mountView();
    await view.updateComplete;
    seedThread(view, [
      { role: 'user', content: 'hello', shapeId: 'core.rag-ask', id: 'm0' },
      { role: 'assistant', content: 'hi there', shapeId: 'core.rag-ask', id: 'm1' },
    ]);
    await view.updateComplete;
    const sr = view.shadowRoot!;
    // The user action bar is a sibling row beneath the bubble; the assistant bar
    // sits inside the message box. Both carry the ⋯ overflow trigger.
    const userBar = sr.querySelector('.turn-actions.user-actions');
    expect(userBar, 'user action bar renders').not.toBeNull();
    expect(
      userBar!.querySelector('[aria-label="More message actions"]'),
      'user action bar has the ⋯ overflow',
    ).not.toBeNull();
    const assistantBar = sr.querySelector('.turn-actions.assistant-actions');
    expect(assistantBar, 'assistant action bar renders').not.toBeNull();
    expect(
      assistantBar!.querySelector('[aria-label="More message actions"]'),
      'assistant action bar has the ⋯ overflow',
    ).not.toBeNull();
    view.remove();
  });

  it('§D.2 — primary verbs are visible icons: Edit on user, Copy + Retry on assistant', async () => {
    const view = mountView();
    await view.updateComplete;
    seedThread(view, [
      { role: 'user', content: 'hello', shapeId: 'core.rag-ask', id: 'm0' },
      { role: 'assistant', content: 'hi there', shapeId: 'core.rag-ask', id: 'm1' },
    ]);
    await view.updateComplete;
    const sr = view.shadowRoot!;
    const userBar = sr.querySelector('.turn-actions.user-actions')!;
    // Edit is the user turn's visible defining action; copy/retry are not on it.
    expect(userBar.querySelector('[aria-label="Edit message"]'), 'user has visible Edit').not.toBeNull();
    expect(userBar.querySelector('[aria-label="Retry"]'), 'user Retry lives in ⋯, not inline').toBeNull();
    const assistantBar = sr.querySelector('.turn-actions.assistant-actions')!;
    expect(assistantBar.querySelector('[aria-label="Copy answer"]'), 'assistant has visible Copy').not.toBeNull();
    expect(assistantBar.querySelector('[aria-label="Retry"]'), 'assistant has visible Retry').not.toBeNull();
    expect(assistantBar.querySelector('[aria-label="Edit message"]'), 'assistant has no inline Edit').toBeNull();
    view.remove();
  });

  it('§D.2 — clicking the visible Edit icon morphs the bubble into the edit-in-place textarea', async () => {
    const view = mountView();
    await view.updateComplete;
    seedThread(view, [{ role: 'user', content: 'first question', shapeId: 'core.rag-ask', id: 'm0' }]);
    await view.updateComplete;
    const editBtn = view.shadowRoot!.querySelector(
      '.turn-actions.user-actions [aria-label="Edit message"]',
    ) as HTMLButtonElement | null;
    expect(editBtn, 'visible Edit icon present').not.toBeNull();
    editBtn!.click();
    await view.updateComplete;
    const textarea = view.shadowRoot!.querySelector('.msg-edit') as HTMLTextAreaElement | null;
    expect(textarea, 'clicking Edit opens the edit-in-place textarea').not.toBeNull();
    expect(textarea!.value).toBe('first question');
    view.remove();
  });

  it('suppresses the ⋯ menu on inherited (parent-branch) turns', async () => {
    const view = mountView();
    await view.updateComplete;
    seedThread(view, [
      { role: 'user', content: 'inherited q', shapeId: 'core.rag-ask', id: 'p0', inheritedFromParent: true },
    ]);
    await view.updateComplete;
    const userMsg = view.shadowRoot!.querySelector('.message.user');
    expect(userMsg, 'inherited user turn renders').not.toBeNull();
    // No controllable action bar on inherited turns (you can only act on your own
    // messages) — so no Edit/⋯ affordances render.
    expect(
      view.shadowRoot!.querySelector('.turn-actions [aria-label="Edit message"]'),
      'inherited turn must NOT offer edit',
    ).toBeNull();
    expect(
      view.shadowRoot!.querySelector('.turn-actions [aria-label="More message actions"]'),
      'inherited turn must NOT offer the ⋯ overflow',
    ).toBeNull();
    view.remove();
  });

  it('swaps a user turn for an editable textarea when editing', async () => {
    const view = mountView();
    await view.updateComplete;
    seedThread(view, [
      { role: 'user', content: 'first question', shapeId: 'core.rag-ask', id: 'm0' },
    ]);
    const v = view as unknown as { editingMessageId: string | null; editingDraft: string };
    v.editingMessageId = 'm0';
    v.editingDraft = 'first question';
    view.requestUpdate();
    await view.updateComplete;
    const textarea = view.shadowRoot!.querySelector('.msg-edit') as HTMLTextAreaElement | null;
    expect(textarea, 'edit-in-place textarea renders for the edited turn').not.toBeNull();
    expect(textarea!.value).toBe('first question');
    expect(
      view.shadowRoot!.querySelector('.msg-edit-save'),
      'Save action renders',
    ).not.toBeNull();
    view.remove();
  });

  it('edit of the first turn branches with the empty-prefix sentinel', async () => {
    const { branchConversation } = await import('../state/conversationListStore.js');
    const view = mountView();
    await view.updateComplete;
    seedThread(view, [
      { role: 'user', content: 'q0', shapeId: 'core.rag-ask', id: 'm0' },
      { role: 'assistant', content: 'a0', shapeId: 'core.rag-ask', id: 'm1' },
    ]);
    await view.updateComplete;
    // Drive the shared flow directly: editing turn 0 → branch-from-before turn 0,
    // which has no predecessor → empty-prefix sentinel.
    const v = view as unknown as {
      editingMessageId: string | null;
      editingDraft: string;
      commitEdit: (idx: number) => Promise<void>;
    };
    v.editingMessageId = 'm0';
    v.editingDraft = 'q0-edited';
    await v.commitEdit(0);
    expect(branchConversation).toHaveBeenCalledTimes(1);
    const args = (branchConversation as unknown as { mock: { calls: unknown[][] } }).mock.calls[0]!;
    // branchConversation(sessionId, fromMsgId, preview) — fromMsgId is the sentinel.
    expect(args[1]).toBe('__empty_prefix__');
    view.remove();
  });

  it('retry of a non-first answer branches from the preceding message id', async () => {
    const { branchConversation } = await import('../state/conversationListStore.js');
    const view = mountView();
    await view.updateComplete;
    seedThread(view, [
      { role: 'user', content: 'q0', shapeId: 'core.rag-ask', id: 'm0' },
      { role: 'assistant', content: 'a0', shapeId: 'core.rag-ask', id: 'm1' },
      { role: 'user', content: 'q1', shapeId: 'core.rag-ask', id: 'm2' },
      { role: 'assistant', content: 'a1', shapeId: 'core.rag-ask', id: 'm3' },
    ]);
    await view.updateComplete;
    // Retry the second answer (idx 3): prompting user turn is idx 2 (id m2);
    // branch-from-before that → its predecessor id m1.
    const v = view as unknown as { retryFrom: (idx: number) => Promise<void> };
    await v.retryFrom(3);
    expect(branchConversation).toHaveBeenCalledTimes(1);
    const args = (branchConversation as unknown as { mock: { calls: unknown[][] } }).mock.calls[0]!;
    expect(args[1]).toBe('m1');
    view.remove();
  });
});

describe('Tempdoc 610 Phase B — inline version pager', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
  });

  function convRow(id: string, opts: Record<string, unknown> = {}): unknown {
    return {
      id,
      title: null,
      createdAt: 0,
      lastActiveAt: 0,
      messageCount: 0,
      firstUserMessage: '',
      shapeId: 'core.rag-ask',
      ...opts,
    };
  }

  it('renders ‹ n/m › on a branch\'s first own turn and reports its version index', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      isStreaming: boolean;
      sessionId: string;
      branchParentId: string | null;
      branchPointId: string | null;
      conversations: unknown[];
      thread: unknown[];
    };
    v.affordance = 'documents';
    v.isStreaming = false;
    v.sessionId = 'B1';
    v.branchParentId = 'P';
    v.branchPointId = 'm1';
    v.conversations = [
      convRow('P'),
      convRow('B1', { parentSessionId: 'P', branchPointMessageId: 'm1', createdAt: 100 }),
      convRow('B2', { parentSessionId: 'P', branchPointMessageId: 'm1', createdAt: 200 }),
    ];
    v.thread = [
      { role: 'user', content: 'inherited q', shapeId: 'core.rag-ask', id: 'm1', inheritedFromParent: true },
      { role: 'user', content: 'edited q', shapeId: 'core.rag-ask', id: 'b1u' },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const pager = view.shadowRoot!.querySelector('.version-pager');
    expect(pager, 'pager renders on the branch first-own turn').not.toBeNull();
    // base P = version 1, B1 = version 2, B2 = version 3 → current (B1) is 2/3.
    expect(pager!.querySelector('.ver-count')!.textContent!.replace(/\s+/g, ' ').trim()).toBe('2 / 3');
    view.remove();
  });

  it('renders no pager when a turn has a single version', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      isStreaming: boolean;
      sessionId: string;
      branchParentId: string | null;
      branchPointId: string | null;
      conversations: unknown[];
      thread: unknown[];
    };
    v.affordance = 'documents';
    v.isStreaming = false;
    v.sessionId = 'root';
    v.branchParentId = null;
    v.branchPointId = null;
    v.conversations = [convRow('root')];
    v.thread = [{ role: 'user', content: 'q', shapeId: 'core.rag-ask', id: 'm0' }];
    view.requestUpdate();
    await view.updateComplete;
    expect(view.shadowRoot!.querySelector('.version-pager')).toBeNull();
    view.remove();
  });
});

describe('Tempdoc 610 Phase C — effective-context floor divider', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
  });

  it('renders the floor divider above the floor message and dims messages above it', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      isStreaming: boolean;
      contextFloorId: string | null;
      thread: unknown[];
    };
    v.affordance = 'documents';
    v.isStreaming = false;
    v.thread = [
      { role: 'user', content: 'q1', shapeId: 'core.rag-ask', id: 'm0' },
      { role: 'assistant', content: 'a1', shapeId: 'core.rag-ask', id: 'm1' },
      { role: 'user', content: 'q2', shapeId: 'core.rag-ask', id: 'm2' },
      { role: 'assistant', content: 'a2', shapeId: 'core.rag-ask', id: 'm3' },
    ];
    v.contextFloorId = 'm2'; // floor at the second question
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    expect(sr.querySelector('.context-floor-divider'), 'floor divider renders').not.toBeNull();
    expect(
      sr.querySelector('.cfd-restore'),
      'the divider carries a Restore control',
    ).not.toBeNull();
    // Messages above the floor (m0, m1) are out-of-context; the floor message
    // (m2) and below are not.
    const outs = sr.querySelectorAll('.message.out-of-context');
    expect(outs.length).toBe(2);
    view.remove();
  });

  it('renders no floor divider when no floor is set', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      isStreaming: boolean;
      contextFloorId: string | null;
      thread: unknown[];
    };
    v.affordance = 'documents';
    v.isStreaming = false;
    v.contextFloorId = null;
    v.thread = [
      { role: 'user', content: 'q1', shapeId: 'core.rag-ask', id: 'm0' },
      { role: 'assistant', content: 'a1', shapeId: 'core.rag-ask', id: 'm1' },
    ];
    view.requestUpdate();
    await view.updateComplete;
    expect(view.shadowRoot!.querySelector('.context-floor-divider')).toBeNull();
    expect(view.shadowRoot!.querySelectorAll('.message.out-of-context').length).toBe(0);
    view.remove();
  });

  it('shows the compacted-variant divider with an expandable summary', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      affordance: string;
      isStreaming: boolean;
      contextFloorId: string | null;
      contextFloorSummary: string | null;
      showFloorSummary: boolean;
      thread: unknown[];
    };
    v.affordance = 'documents';
    v.isStreaming = false;
    v.thread = [
      { role: 'user', content: 'q1', shapeId: 'core.rag-ask', id: 'm0' },
      { role: 'assistant', content: 'a1', shapeId: 'core.rag-ask', id: 'm1' },
      { role: 'user', content: 'q2', shapeId: 'core.rag-ask', id: 'm2' },
    ];
    v.contextFloorId = 'm2';
    v.contextFloorSummary = 'Earlier: user asked q1, assistant answered a1.';
    v.showFloorSummary = false;
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    const label = sr.querySelector('.context-floor-divider .cfd-label')!.textContent ?? '';
    expect(label).toContain('compacted');
    // Summary hidden until expanded.
    expect(sr.querySelector('.cfd-summary')).toBeNull();
    // Expand.
    v.showFloorSummary = true;
    view.requestUpdate();
    await view.updateComplete;
    const summary = sr.querySelector('.cfd-summary');
    expect(summary, 'expanded summary renders').not.toBeNull();
    expect(summary!.textContent).toContain('Earlier: user asked q1');
    view.remove();
  });
});

describe('UnifiedChatView state retention (tempdoc 609 M1)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    __resetSelectedSource();
    __resetAgentSessionStore();
    document.body.innerHTML = '';
  });

  it('does not reset the chat store (draft + mode) on connect', async () => {
    // A composer draft + mode the user left behind, held in the singleton store.
    restoreUnifiedChat({ query: 'unfinished draft', affordance: 'documents' });

    const view = mountView();
    await view.updateComplete;

    // Pre-609, connectedCallback called resetUnifiedChatState(), wiping this on
    // every navigation. The recoverable draft + mode must now survive the mount.
    expect(getUnifiedChatState().query).toBe('unfinished draft');
    expect(getUnifiedChatState().affordance).toBe('documents');
    view.remove();
  });

  it('clears the store only through the explicit New chat action', async () => {
    restoreUnifiedChat({ query: 'draft', affordance: 'documents' });
    const view = mountView();
    await view.updateComplete;
    expect(getUnifiedChatState().query).toBe('draft');

    // The explicit intent path is the ONE place that empties recoverable state.
    (view as unknown as { newConversation: () => void }).newConversation();
    expect(getUnifiedChatState().query).toBe('');
    expect((view as unknown as { inputDraft: string }).inputDraft).toBe('');
    view.remove();
  });
});

describe('UnifiedChatView last-viewed auto-restore (tempdoc 609 Phase 3)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    __resetSelectedSource();
    __resetAgentSessionStore();
    clearLastViewedConversation();
    document.body.innerHTML = '';
  });

  it('auto-restores the conversation this tab was viewing, with no resume card', async () => {
    // The user had this conversation open before navigating away.
    setLastViewedConversation('sess-xyz');

    const view = mountView();
    await view.updateComplete;

    // On return, the thread is reloaded silently (no manual "Continue" click).
    expect(vi.mocked(resumeConversation)).toHaveBeenCalledWith('sess-xyz', expect.any(String));
    expect((view as unknown as { showResumePrompt: boolean }).showResumePrompt).toBe(false);
    view.remove();
  });

  it('does not auto-restore on a cold start (no last-viewed pointer)', async () => {
    const view = mountView();
    await view.updateComplete;

    // Cold landing: the existing resume-card path owns recovery, no thread is reloaded.
    expect(vi.mocked(resumeConversation)).not.toHaveBeenCalled();
    view.remove();
  });

  it('forgets the pointer on New chat so a later return does not auto-restore', async () => {
    setLastViewedConversation('sess-xyz');
    const view = mountView();
    await view.updateComplete;
    vi.mocked(resumeConversation).mockClear();

    (view as unknown as { newConversation: () => void }).newConversation();
    view.remove();

    const view2 = mountView();
    await view2.updateComplete;
    expect(vi.mocked(resumeConversation)).not.toHaveBeenCalled();
    view2.remove();
  });

  it('does NOT re-fetch on reconnect when the retained instance already holds a thread', async () => {
    // Cold mount with a pointer auto-loads once (empty instance → thread.length === 0).
    setLastViewedConversation('sess-xyz');
    const view = mountView();
    await view.updateComplete;
    expect(vi.mocked(resumeConversation)).toHaveBeenCalledTimes(1);

    // Under instance-retention a same-session return reuses THIS instance with its thread intact.
    (view as unknown as { thread: unknown[] }).thread = [
      { role: 'user', content: 'kept', shapeId: 'core.free-chat' },
    ];
    vi.mocked(resumeConversation).mockClear();

    view.disconnectedCallback();
    view.connectedCallback();
    await view.updateComplete;

    // The `thread.length === 0` guard skips the auto-load, so no blank-then-reload flicker (§K.2).
    expect(vi.mocked(resumeConversation)).not.toHaveBeenCalled();
    expect((view as unknown as { thread: unknown[] }).thread.length).toBe(1);
    view.remove();
  });
});

describe('UnifiedChatView activity truthfulness on disconnect (tempdoc 609 Phase 4)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    __resetSelectedSource();
    __resetAgentSessionStore();
    clearLastViewedConversation();
    document.body.innerHTML = '';
  });

  // Investigation (609 §P Phase 4) confirmed the global activity signal is driven ONLY by the
  // plain-stream send() path; the `agent` affordance delegates to the shared controller and never
  // sets `isStreaming`/`setAiActivity`. These tests LOCK that truthfulness invariant: leaving a
  // continuing run must not force the indicator idle; an aborted plain stream correctly does.

  it('does not force the activity indicator idle when no plain stream is in flight', async () => {
    const view = mountView();
    await view.updateComplete;
    // An agent run continues server-side and never sets `isStreaming` — the view is not streaming.
    (view as unknown as { isStreaming: boolean }).isStreaming = false;
    vi.mocked(setAiActivity).mockClear();

    view.remove(); // disconnect

    expect(vi.mocked(setAiActivity)).not.toHaveBeenCalled();
  });

  it('idles the indicator when a plain stream is genuinely aborted on disconnect', async () => {
    const view = mountView();
    await view.updateComplete;
    (view as unknown as { isStreaming: boolean }).isStreaming = true;
    vi.mocked(setAiActivity).mockClear();

    view.remove(); // disconnect aborts the in-flight plain stream → idle is truthful

    expect(vi.mocked(setAiActivity)).toHaveBeenCalledWith(
      expect.objectContaining({ state: 'idle' }),
    );
  });
});

describe('UnifiedChatView settle transients on hide (tempdoc 609 instance-retention)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    __resetSelectedSource();
    __resetAgentSessionStore();
    clearLastViewedConversation();
    document.body.innerHTML = '';
  });

  it('resets in-flight/partial transient state on disconnect but KEEPS the thread + draft', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as {
      isStreaming: boolean;
      streamingText: string;
      errorMessage: string;
      thread: unknown[];
      inputDraft: string;
    };
    // Simulate a stream in flight with a populated thread + a typed draft (the stale-spinner setup).
    v.isStreaming = true;
    v.streamingText = 'half an answer';
    v.errorMessage = 'transient error';
    v.thread = [{ role: 'user', content: 'kept turn', shapeId: 'core.free-chat' }];
    v.inputDraft = 'a draft I am keeping';

    // Navigate away (the Stage retains the instance; JfElement.disconnectedCallback fires settle).
    view.disconnectedCallback();

    // Transient state settled — no stale "thinking" spinner / partial answer / stale error on return.
    expect(v.isStreaming).toBe(false);
    expect(v.streamingText).toBe('');
    expect(v.errorMessage).toBe('');
    // Recoverable state survives the hide (instance-retention's whole point).
    expect(v.thread.length).toBe(1);
    expect(v.inputDraft).toBe('a draft I am keeping');
    view.remove();
  });
});

describe('UnifiedChatView context-budget meter (610 §E.4)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
  });

  const withWindow = (view: UnifiedChatView, contextWindow: number): void => {
    view.aiState = {
      ...AI_STATE_READY,
      runtime: {
        mode: 'online',
        modelId: 'm',
        modelLabel: 'M',
        contextWindow,
        gpu: null,
        installed: true,
        installing: false,
        loadStartedAtMs: null,
      },
    } as unknown as UnifiedChatView['aiState'];
  };

  it('renders the meter when occupancy + window are known, hides it when occupancy is absent', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'none';
    withWindow(view, 4096);
    view.contextPromptTokens = 1024;
    await view.updateComplete;
    const meter = view.shadowRoot?.querySelector('.context-meter [role="meter"]');
    expect(meter).not.toBeNull();
    expect(meter?.getAttribute('aria-valuenow')).toBe('25');

    view.contextPromptTokens = null;
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('.context-meter')).toBeNull();
    view.remove();
  });

  it('hides the meter in agent mode (the activity rail owns headroom there)', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    withWindow(view, 4096);
    view.contextPromptTokens = 1024;
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('.context-meter')).toBeNull();
    view.remove();
  });

  it('renders the per-phase attribution breakdown when contextBreakdown is present (610 §I.2)', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'none';
    withWindow(view, 4096);
    view.contextPromptTokens = 1024;
    view.contextBreakdown = { system: 120, conversation: 450, retrieved: 454 };
    await view.updateComplete;
    const breakdown = view.shadowRoot?.querySelector('.context-meter-breakdown');
    expect(breakdown).not.toBeNull();
    expect(breakdown?.textContent).toContain('system ~120');
    expect(breakdown?.textContent).toContain('documents ~454');
    expect(breakdown?.textContent).toContain('estimated');
    // No breakdown element when the split is absent.
    view.contextBreakdown = null;
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('.context-meter-breakdown')).toBeNull();
    view.remove();
  });

  it('the meter label opens the inspector, whose view projects the in-context turns + sources (610 §K)', async () => {
    const {
      isContextInspectorOpen,
      __resetContextInspectorDrawer,
    } = await import('../state/contextInspectorDrawer.js');
    __resetContextInspectorDrawer();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'none';
    withWindow(view, 4096);
    view.contextPromptTokens = 1024;
    view.contextBreakdown = { system: 120, conversation: 450, retrieved: 454 };
    view.thread = [
      { role: 'user', content: 'q1', id: 'm-1', shapeId: 'core.free-chat' },
      {
        role: 'assistant',
        content: 'a1',
        id: 'm-2',
        shapeId: 'core.free-chat',
        sources: [
          {
            parentDocId: 'C:/docs/taxes.md',
            chunkIndex: 2,
            chunkTotal: 5,
            startChar: 0,
            endChar: 10,
            score: 0.9,
            excerpt: 'the budget report',
            startLine: 42,
            endLine: 48,
            headingText: 'Budget',
            headingLevel: 2,
          },
        ],
      },
    ] as never;
    await view.updateComplete;

    // The meter label is the trigger.
    const trigger = view.shadowRoot?.querySelector('.context-meter-trigger') as HTMLButtonElement;
    expect(trigger).not.toBeNull();
    trigger.click();
    expect(isContextInspectorOpen()).toBe(true);

    // The projection: system from the breakdown, the conversation turns, the document source.
    const v = (view as unknown as { buildInspectorView(): import('../components/ContextInspectorPane.js').InspectorView }).buildInspectorView();
    expect(v.systemTokens).toBe(120);
    expect(v.totalTokens).toBe(1024);
    expect(v.windowTokens).toBe(4096);
    const conv = v.phases.find((p) => p.name === 'Conversation');
    const docs = v.phases.find((p) => p.name === 'Documents');
    expect(conv?.segments.length).toBe(2);
    expect(docs?.segments.length).toBe(1);
    expect(docs?.segments[0]?.label).toBe('Budget');
    __resetContextInspectorDrawer();
    view.remove();
  });
});

describe('UnifiedChatView editable compaction summary (610 §E.2)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
  });

  it('commits an in-place summary edit: persists via the store and updates the floor summary', async () => {
    const view = mountView();
    await view.updateComplete;
    (view as unknown as { sessionId: string }).sessionId = 'uc-test-edit';
    view.contextFloorId = 'm-2';
    view.contextFloorSummary = 'old summary';
    view.editingFloorSummary = true;
    view.floorSummaryDraft = 'corrected summary';
    await (
      view as unknown as { commitFloorSummaryEdit(): Promise<void> }
    ).commitFloorSummaryEdit();
    const { editContextFloorSummary } = await import('../state/conversationListStore.js');
    expect(editContextFloorSummary).toHaveBeenCalledWith('uc-test-edit', 'corrected summary');
    expect(view.contextFloorSummary).toBe('corrected summary');
    expect(view.editingFloorSummary).toBe(false);
    view.remove();
  });

  it('renders Edit on the expanded compaction summary and opens an editable textarea', async () => {
    const view = mountView();
    await view.updateComplete;
    // S5a — the B14 auto-upgrade is retired: land in the documents plane EXPLICITLY
    // (the tier a user now reaches by tab click / escalation), where the thread renders.
    view.affordance = 'documents';
    view.requestUpdate();
    await view.updateComplete;
    view.thread = [
      { role: 'user', content: 'q', id: 'm-1', shapeId: 'core.free-chat' },
      { role: 'assistant', content: 'a', id: 'm-2', shapeId: 'core.free-chat' },
    ] as never;
    view.contextFloorId = 'm-2';
    view.contextFloorSummary = 'a summary';
    view.showFloorSummary = true;
    await view.updateComplete;
    const editBtn = Array.from(
      view.shadowRoot?.querySelectorAll('.context-floor-divider .cfd-restore') ?? [],
    ).find((b) => b.textContent?.trim() === 'Edit') as HTMLButtonElement | undefined;
    expect(editBtn).not.toBeUndefined();
    editBtn!.click();
    await view.updateComplete;
    const textarea = view.shadowRoot?.querySelector('.cfd-summary-input') as
      | HTMLTextAreaElement
      | null;
    expect(textarea).not.toBeNull();
    expect(textarea?.value).toBe('a summary');
    view.remove();
  });
});

describe('UnifiedChatView per-message exclude (610 §E.3)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
  });

  it('toggles a message excluded: persists via the store, tracks the id, and dims the turn', async () => {
    const view = mountView();
    await view.updateComplete;
    // S5a — the B14 auto-upgrade is retired: land in the documents plane EXPLICITLY
    // (the tier a user now reaches by tab click / escalation), where the thread renders.
    view.affordance = 'documents';
    view.requestUpdate();
    await view.updateComplete;
    (view as unknown as { sessionId: string }).sessionId = 'uc-test-ex';
    view.thread = [
      { role: 'user', content: 'q', id: 'm-1', shapeId: 'core.free-chat' },
      { role: 'assistant', content: 'a', id: 'm-2', shapeId: 'core.free-chat' },
    ] as never;
    await view.updateComplete;
    const toggle = (view as unknown as { toggleMessageExcluded(i: number): Promise<void> })
      .toggleMessageExcluded;
    await toggle.call(view, 0);
    const { setMessageExcluded } = await import('../state/conversationListStore.js');
    expect(setMessageExcluded).toHaveBeenCalledWith('uc-test-ex', 'm-1', true);
    expect(view.excludedMessageIds.has('m-1')).toBe(true);
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('.message.excluded')).not.toBeNull();

    await toggle.call(view, 0);
    expect(setMessageExcluded).toHaveBeenCalledWith('uc-test-ex', 'm-1', false);
    expect(view.excludedMessageIds.has('m-1')).toBe(false);
    view.remove();
  });

  it('shows the "N hidden · Include all" aggregate and includeAll re-includes in bulk (610 §I.2)', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'none';
    (view as unknown as { sessionId: string }).sessionId = 'uc-test-agg';
    view.thread = [
      { role: 'user', content: 'q1', id: 'm-1', shapeId: 'core.free-chat' },
      { role: 'assistant', content: 'a1', id: 'm-2', shapeId: 'core.free-chat' },
    ] as never;
    view.excludedMessageIds = new Set(['m-1', 'm-2']);
    await view.updateComplete;
    expect(
      view.shadowRoot?.querySelector('.excluded-summary-label')?.textContent,
    ).toContain('2 turns hidden');
    await (view as unknown as { includeAll(): Promise<void> }).includeAll.call(view);
    const { setMessageExcluded } = await import('../state/conversationListStore.js');
    expect(setMessageExcluded).toHaveBeenCalledWith('uc-test-agg', 'm-1', false);
    expect(setMessageExcluded).toHaveBeenCalledWith('uc-test-agg', 'm-2', false);
    expect(view.excludedMessageIds.size).toBe(0);
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('.excluded-summary')).toBeNull();
    view.remove();
  });

  it('toggles a retrieved source excluded: persists via the store + tracks the unit-sep key (610 §J.3)', async () => {
    const view = mountView();
    await view.updateComplete;
    (view as unknown as { sessionId: string }).sessionId = 'uc-src-ex';
    const source = {
      parentDocId: 'C:/docs/x.md',
      chunkIndex: 2,
      path: 'C:/docs/x.md',
      title: 'x.md',
      excerpt: '',
      startLine: 1,
      endLine: 5,
      headingText: '',
    };
    const key = `C:/docs/x.md${String.fromCharCode(0x1f)}2`;
    const toggle = (
      view as unknown as { toggleSourceExcluded(s: unknown): Promise<void> }
    ).toggleSourceExcluded;

    const { getExcludedSources, __resetExcludedSources } = await import(
      '../state/excludedSources.js'
    );
    __resetExcludedSources();

    await toggle.call(view, source);
    const { setSourceExcluded } = await import('../state/conversationListStore.js');
    expect(setSourceExcluded).toHaveBeenCalledWith('uc-src-ex', key, true);
    expect(getExcludedSources().has(key)).toBe(true);

    await toggle.call(view, source);
    expect(setSourceExcluded).toHaveBeenCalledWith('uc-src-ex', key, false);
    expect(getExcludedSources().has(key)).toBe(false);
    view.remove();
  });

  it('floorFrameParts is the single authority for divider + dim-class (610 §F.3 frame parity)', async () => {
    const view = mountView();
    await view.updateComplete;
    view.thread = [
      { role: 'user', content: 'q1', id: 'm-1', shapeId: 'core.free-chat' },
      { role: 'assistant', content: 'a1', id: 'm-2', shapeId: 'core.free-chat' },
      { role: 'user', content: 'q2', id: 'm-3', shapeId: 'core.free-chat' },
    ] as never;
    view.contextFloorId = 'm-3'; // floor at idx 2
    view.excludedMessageIds = new Set(['m-1']);
    await view.updateComplete;
    const fp = (
      view as unknown as {
        floorFrameParts(
          id: string | undefined,
          idx: number,
        ): { divider: unknown; cls: string };
      }
    ).floorFrameParts.bind(view);
    // Above the floor AND individually excluded → both dim classes, no divider.
    const above = fp('m-1', 0);
    expect(above.cls).toBe(' out-of-context excluded');
    expect(typeof above.divider).toBe('symbol'); // lit `nothing`
    // The floor turn itself → the divider renders, no dim class.
    const atFloor = fp('m-3', 2);
    expect(atFloor.cls).toBe('');
    expect(typeof atFloor.divider).toBe('object'); // a TemplateResult
    view.remove();
  });
});

// Search Thread S4-final — commit-on-consequence (the user's own searches become committed,
// persisted thread events), the auto-collapse of older commits, restored SEARCH thread events, and
// the recent-query trail.
describe('Search Thread S4-final — commit-on-consequence + query trail', () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    searchListener = null;
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  /**
   * Push a fabricated refined-pass snapshot with 2 results through the mocked search store.
   *
   * Tempdoc 805 §G.2 — `passStage` is now part of what makes this a REFINED-pass fixture (it was
   * omitted before, when the frozen card took any trace it found, or defaulted to 'TEXT'). Pass
   * `'quick'` to fabricate the quick window instead: that pass genuinely runs `mode: 'text'`
   * (searchState.buildSearchIntent), so its identity must never be frozen as the search's own.
   */
  function pushSearch(
    view: UnifiedChatView,
    query: string,
    opts: { passStage?: 'quick' | 'refined'; effectiveMode?: string | null } = {},
  ): void {
    view.affordance = 'retrieve';
    expect(searchListener).not.toBeNull();
    const mode = opts.effectiveMode === undefined ? 'HYBRID' : opts.effectiveMode;
    searchListener!({
      query,
      results: [
        { id: 'h1', title: 'Q1 invoice', path: '/docs/q1.md', snippet: 'total due' },
        { id: 'h2', title: 'helper.ts', path: '/src/helper.ts', snippet: 'function pay()' },
      ],
      matchCount: 2,
      totalHits: 2,
      isSearching: false,
      processingTimeMs: 12,
      error: null,
      passStage: opts.passStage ?? 'refined',
      searchTrace: mode === null ? null : { effectiveMode: mode },
    });
  }

  it('commit-on-open: freezes the live search into a snapshot card above the live card, and POSTs the event', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: 'evt-1' }) });
    const view = mountView();
    await view.updateComplete;
    // connectedCallback's incidental `loadConversations()` fetch (unrelated to this test) is not
    // mocked away by the module-level conversationListStore mock — clear it so the assertions below
    // isolate the commit's OWN POST.
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockClear();
    (view as unknown as { sessionId: string }).sessionId = 'uc-commit-open';
    pushSearch(view, 'invoice audit');
    await view.updateComplete;

    const liveCardBefore = view.shadowRoot?.querySelector('jf-results-card:not([variant])');
    expect(liveCardBefore).not.toBeNull();
    liveCardBefore!.dispatchEvent(
      new CustomEvent('card-open', { detail: { id: 'h1' }, bubbles: true, composed: true }),
    );
    await view.updateComplete;

    const committed = (
      view as unknown as { committedSearches: Array<Record<string, unknown>> }
    ).committedSearches;
    expect(committed.length).toBe(1);
    expect(committed[0]).toMatchObject({
      query: 'invoice audit',
      mode: 'HYBRID',
      matchCount: 2,
      resultCount: 2,
      docIds: ['/docs/q1.md', '/src/helper.ts'],
    });

    // The snapshot card renders ABOVE the live card; the live card is still running (no variant attr).
    const cards = Array.from(view.shadowRoot?.querySelectorAll('jf-results-card') ?? []);
    expect(cards.length).toBe(2);
    expect(cards[0]!.getAttribute('variant')).toBe('snapshot');
    expect(cards[1]!.hasAttribute('variant')).toBe(false);

    // Filter to the commit's own POST — other view machinery (e.g. the incidental conversations-list
    // fetch on connect) shares the same mocked global fetch and is not this test's concern.
    const eventCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.filter((c) =>
      String(c[0]).includes('/events'),
    );
    expect(eventCalls.length).toBe(1);
    expect(eventCalls[0]![0]).toBe('http://localhost:5173/api/thread/uc-commit-open/events');
    const body = JSON.parse((eventCalls[0]![1] as RequestInit).body as string);
    expect(body).toMatchObject({
      kind: 'SEARCH',
      query: 'invoice audit',
      mode: 'HYBRID',
      matchCount: 2,
      resultCount: 2,
      docIds: ['/docs/q1.md', '/src/helper.ts'],
    });
    view.remove();
  });

  // ===== Tempdoc 805 §G.2 / derisk U5 — the frozen card's retrieval-mode IDENTITY. The quick pass
  // genuinely runs `mode: 'text'` (searchState.buildSearchIntent:330-332), so a commit landing inside
  // the quick window froze "Keyword" as the search's identity — round 11 saw that on a hybrid search —
  // and the removed `?? 'TEXT'` default asserted the same from a MISSING trace. =====

  it("805 §G.2: a commit during the QUICK window freezes mode 'UNKNOWN' and renders NO mode label", async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: 'evt-q' }) });
    const view = mountView();
    await view.updateComplete;
    (view as unknown as { sessionId: string }).sessionId = 'uc-commit-quick';
    // The quick pass' own honest trace: it really did run TEXT — but it is not the search's identity.
    pushSearch(view, 'invoice audit', { passStage: 'quick', effectiveMode: 'TEXT' });
    await view.updateComplete;

    view.shadowRoot
      ?.querySelector('jf-results-card:not([variant])')!
      .dispatchEvent(new CustomEvent('card-open', { detail: { id: 'h1' }, bubbles: true, composed: true }));
    await view.updateComplete;

    const committed = (view as unknown as { committedSearches: Array<Record<string, unknown>> })
      .committedSearches;
    expect(committed.length).toBe(1);
    expect(committed[0]!.mode).toBe('UNKNOWN');
    expect(committed[0]!.mode).not.toBe('TEXT');

    // The frozen card renders the provenance header WITHOUT a mode segment — and without a dangling
    // separator where the label used to be.
    const snapshotCard = view.shadowRoot?.querySelector('jf-results-card[variant="snapshot"]');
    await (snapshotCard as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    const header = snapshotCard!.shadowRoot?.querySelector('[data-testid="card-provenance"]');
    const text = header?.textContent ?? '';
    expect(text).toContain('invoice audit');
    expect(text).not.toContain('Keyword');
    expect(text).not.toContain('exact-word search');
    expect(text).not.toContain('UNKNOWN');
    expect(text.replace(/\s+/g, ' ')).not.toContain('· ·');
    view.remove();
  });

  it("805 §G.2: a MISSING trace on the refined pass also freezes 'UNKNOWN' (no `?? 'TEXT'` default)", async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: 'evt-n' }) });
    const view = mountView();
    await view.updateComplete;
    (view as unknown as { sessionId: string }).sessionId = 'uc-commit-notrace';
    pushSearch(view, 'invoice audit', { passStage: 'refined', effectiveMode: null });
    await view.updateComplete;

    view.shadowRoot
      ?.querySelector('jf-results-card:not([variant])')!
      .dispatchEvent(new CustomEvent('card-open', { detail: { id: 'h1' }, bubbles: true, composed: true }));
    await view.updateComplete;

    const committed = (view as unknown as { committedSearches: Array<Record<string, unknown>> })
      .committedSearches;
    expect(committed[0]!.mode).toBe('UNKNOWN');
    view.remove();
  });

  it('805 §G.2: a commit AFTER the refined pass keeps the real mode + renders its label', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: 'evt-r' }) });
    const view = mountView();
    await view.updateComplete;
    (view as unknown as { sessionId: string }).sessionId = 'uc-commit-refined';
    pushSearch(view, 'invoice audit', { passStage: 'refined', effectiveMode: 'HYBRID' });
    await view.updateComplete;

    view.shadowRoot
      ?.querySelector('jf-results-card:not([variant])')!
      .dispatchEvent(new CustomEvent('card-open', { detail: { id: 'h1' }, bubbles: true, composed: true }));
    await view.updateComplete;

    const committed = (view as unknown as { committedSearches: Array<Record<string, unknown>> })
      .committedSearches;
    expect(committed[0]!.mode).toBe('HYBRID');

    const snapshotCard = view.shadowRoot?.querySelector('jf-results-card[variant="snapshot"]');
    await (snapshotCard as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    const text =
      snapshotCard!.shadowRoot?.querySelector('[data-testid="card-provenance"]')?.textContent ?? '';
    // Simple mode is the default (tempdoc 738 C2) → the plain label.
    expect(text).toContain('meaning + words');
    view.remove();
  });

  it('commit-on-ask: escalateAsk commits the retrieve-tier query before the affordance flips to documents', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: 'evt-2' }) });
    const view = mountView();
    await view.updateComplete;
    (view as unknown as { sessionId: string }).sessionId = 'uc-commit-ask';
    pushSearch(view, 'invoice march');
    await view.updateComplete;
    view.inputDraft = 'invoice march'; // heuristic guesses 'search'; alt-submit sends the OPPOSITE (ask)
    await view.updateComplete;
    const composer = view.shadowRoot?.querySelector('jf-composer');
    composer!.dispatchEvent(new CustomEvent('composer-submit-alt'));
    await view.updateComplete;

    expect(view.affordance).toBe('documents');
    const committed = (
      view as unknown as { committedSearches: Array<Record<string, unknown>> }
    ).committedSearches;
    expect(committed.length).toBe(1);
    expect(committed[0]!.query).toBe('invoice march');
    // Only ONE commit's worth of /events POSTs, regardless of other fetches send() may issue
    // (e.g. the chat dispatch) — the same commit-isolation rationale as commit-on-open above.
    const eventCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.filter((c) =>
      String(c[0]).includes('/events'),
    );
    expect(eventCalls.length).toBe(1);
    view.remove();
  });

  // Search Thread Round-2 R2 — one gesture, one meaning: the card's Ask AI now sends immediately
  // (escalateAsk's own path — commit + affordance flip + send), matching the route chip's Enter; a
  // SHIFT-modified activation keeps the pre-round-2 stage-only behavior (compose(), no commit/flip).
  it('default (unmodified) card-ask-ai sends immediately: commits the search and flips to documents', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: 'evt-4' }) });
    const view = mountView();
    await view.updateComplete;
    (view as unknown as { sessionId: string }).sessionId = 'uc-ask-ai-send';
    pushSearch(view, 'invoice march');
    await view.updateComplete;

    const liveCard = view.shadowRoot?.querySelector('jf-results-card:not([variant])');
    expect(liveCard).not.toBeNull();
    liveCard!.dispatchEvent(
      new CustomEvent('card-ask-ai', {
        detail: { query: 'invoice march', shiftKey: false },
        bubbles: true,
        composed: true,
      }),
    );
    await view.updateComplete;

    expect(view.affordance).toBe('documents');
    const committed = (
      view as unknown as { committedSearches: Array<Record<string, unknown>> }
    ).committedSearches;
    expect(committed.length).toBe(1);
    expect(committed[0]!.query).toBe('invoice march');
  });

  it('a SHIFT-held card-ask-ai activation stages instead of sending (compose(), no commit/no affordance flip)', async () => {
    const composeSpy = vi.spyOn(composeModule, 'compose');
    const view = mountView();
    await view.updateComplete;
    pushSearch(view, 'invoice march');
    await view.updateComplete;

    const liveCard = view.shadowRoot?.querySelector('jf-results-card:not([variant])');
    expect(liveCard).not.toBeNull();
    liveCard!.dispatchEvent(
      new CustomEvent('card-ask-ai', {
        detail: { query: 'invoice march', shiftKey: true },
        bubbles: true,
        composed: true,
      }),
    );
    await view.updateComplete;

    expect(composeSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        operation: 'core.ask',
        userPrompt: 'invoice march',
        affordance: 'documents',
      }),
    );
    expect(view.affordance).toBe('retrieve'); // staged only — never escalated
    const committed = (view as unknown as { committedSearches: unknown[] }).committedSearches;
    expect(committed.length).toBe(0);
    composeSpy.mockRestore();
  });

  it('plain query iteration (no open/ask) commits nothing, but the superseded query lands on the trail', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: 'evt-3' }) });
    const view = mountView();
    await view.updateComplete;
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockClear();
    pushSearch(view, 'first query');
    await view.updateComplete;
    pushSearch(view, 'second query');
    await view.updateComplete;

    const committed = (view as unknown as { committedSearches: unknown[] }).committedSearches;
    expect(committed.length).toBe(0);
    const eventCalls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.filter((c) =>
      String(c[0]).includes('/events'),
    );
    expect(eventCalls.length).toBe(0);
    const trail = (view as unknown as { queryTrail: string[] }).queryTrail;
    expect(trail).toEqual(['first query']);
    view.remove();
  });

  it('auto-collapse: only the 3 most recent commits render as full snapshot; older ones collapse to excerpt', async () => {
    const view = mountView();
    await view.updateComplete;
    const committedList = Array.from({ length: 4 }, (_, i) => ({
      id: `cs-${i}`,
      query: `q${i}`,
      mode: 'TEXT',
      matchCount: 1,
      resultCount: 1,
      docIds: [`/docs/${i}.md`],
      executedAt: new Date().toISOString(),
      hits: [{ id: `h${i}`, title: `T${i}`, path: `/docs/${i}.md` }],
    }));
    (view as unknown as { committedSearches: unknown[] }).committedSearches = committedList;
    pushSearch(view, 'active query'); // an active query is required for the retrieve tier to render at all
    await view.updateComplete;

    const cards = Array.from(view.shadowRoot?.querySelectorAll('jf-results-card') ?? []);
    const excerptCount = cards.filter((c) => c.getAttribute('variant') === 'excerpt').length;
    const snapshotCount = cards.filter((c) => c.getAttribute('variant') === 'snapshot').length;
    expect(excerptCount).toBe(1);
    expect(snapshotCount).toBe(3);
    view.remove();
  });

  it('a restored SEARCH thread event renders as an excerpt card with a "Search again" affordance', async () => {
    const view = mountView();
    await view.updateComplete;
    // S5a — the B14 auto-upgrade is retired: land in the documents plane EXPLICITLY
    // (the tier a user now reaches by tab click / escalation), where the thread renders.
    view.affordance = 'documents';
    view.requestUpdate();
    await view.updateComplete;
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      {
        id: 'se1',
        occurredAt: '2026-01-01T00:00:01Z',
        kind: 'SEARCH',
        originator: 'user',
        content: '',
        attributes: {
          query: 'restored query',
          mode: 'HYBRID',
          matchCount: 5,
          resultCount: 5,
          docIds: ['/docs/a.md'],
          executedAt: '2026-01-01T00:00:01Z',
        },
      },
    ];
    view.requestUpdate();
    await view.updateComplete;

    const card = view.shadowRoot?.querySelector('jf-results-card[variant="excerpt"]') as
      | (Element & { updateComplete: Promise<unknown> })
      | null;
    expect(card).not.toBeNull();
    await card!.updateComplete;
    expect(card!.shadowRoot?.textContent).toContain('restored query');

    // Expand: no hits were persisted, so the honest empty note shows (not fabricated rows), plus
    // the fork affordance.
    (card!.shadowRoot?.querySelector('[data-testid="card-excerpt"]') as HTMLButtonElement).click();
    await card!.updateComplete;
    expect(card!.shadowRoot?.querySelector('[data-testid="snapshot-empty-note"]')).not.toBeNull();
    expect(card!.shadowRoot?.querySelector('[data-testid="card-fork-btn"]')).not.toBeNull();
    view.remove();
  });

  it('the query-trail dropdown lists recent queries newest-first (deduped); clicking one restores the draft + re-issues the query', async () => {
    const { setQuery } = await import('../state/searchState.js');
    const view = mountView();
    await view.updateComplete;
    pushSearch(view, 'alpha');
    await view.updateComplete;
    pushSearch(view, 'beta');
    await view.updateComplete;
    pushSearch(view, 'alpha'); // re-search alpha: supersedes beta
    await view.updateComplete;
    pushSearch(view, 'beta'); // re-search beta: supersedes alpha — dedup moves alpha to front, no duplicate
    await view.updateComplete;

    expect((view as unknown as { queryTrail: string[] }).queryTrail).toEqual(['alpha', 'beta']);

    const toggle = view.shadowRoot?.querySelector('[data-testid="query-trail-toggle"]') as HTMLButtonElement;
    expect(toggle).not.toBeNull();
    toggle.click();
    await view.updateComplete;
    const items = Array.from(view.shadowRoot?.querySelectorAll('[data-testid="query-trail-item"]') ?? []);
    expect(items.map((i) => i.textContent)).toEqual(['alpha', 'beta']);

    (items[1] as HTMLButtonElement).click(); // pick 'beta'
    await view.updateComplete;

    expect(view.inputDraft).toBe('beta');
    expect(setQuery).toHaveBeenCalledWith('beta');
    // The dropdown closes after picking an entry.
    expect(view.shadowRoot?.querySelector('[data-testid="query-trail-menu"]')).toBeNull();
    view.remove();
  });
});

// ---------------------------------------------------------------------------
// S5b pin-parity — pinned searches resurface on the landing + the bar's pin toggle
// (the retired SearchSurface header's persisted pin store, same authority).
// ---------------------------------------------------------------------------
describe('Search Thread S5b — pinned searches (landing strip + pin toggle)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    pinsCtl.reset([]);
  });

  it('renders the landing pinned strip from the store and one click re-runs the pin', async () => {
    pinsCtl.reset([{ id: 'p1', query: 'quarterly invoices', pinnedAt: 1, runs: [] }]);
    const view = mountView();
    await view.updateComplete;
    const strip = view.shadowRoot?.querySelector('[data-testid="landing-pins"]');
    expect(strip).not.toBeNull();
    const btn = strip?.querySelector('button.pinned-search-btn') as HTMLElement;
    expect(btn?.textContent?.trim()).toBe('quarterly invoices');
    btn.click();
    await view.updateComplete;
    expect(view.inputDraft).toBe('quarterly invoices');
    const { setQuery } = await import('../state/searchState.js');
    expect(vi.mocked(setQuery)).toHaveBeenCalledWith('quarterly invoices');
  });

  it('hides the strip when nothing is pinned', async () => {
    const view = mountView();
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('[data-testid="landing-pins"]')).toBeNull();
  });

  // Search Thread Round-2 R4 — the pin toggle is now a `jf-control` composition (skinned via
  // ::part(control), the RouteChip precedent), so pressed-state truth is carried by the accessible
  // label + the `data-pressed` presentation attribute (jf-control's internal button has no
  // aria-pressed passthrough) rather than a plain button's native `aria-pressed`.
  it('the bar pin toggle (jf-control) pins the active query and unpins a pinned one', async () => {
    const view = mountView();
    await view.updateComplete;
    expect(searchListener).not.toBeNull();
    searchListener!({ ...SEARCH_EMPTY, query: 'tax report' });
    await view.updateComplete;
    const toggle = view.shadowRoot?.querySelector('[data-testid="pin-toggle"]') as HTMLElement & {
      updateComplete: Promise<boolean>;
    };
    expect(toggle).not.toBeNull();
    expect(toggle.tagName.toLowerCase()).toBe('jf-control');
    expect(toggle.hasAttribute('data-pressed')).toBe(false);
    expect(toggle.getAttribute('label')).toBe('Pin this search');
    await toggle.updateComplete;
    (toggle.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
    await view.updateComplete;
    expect(pinSearchMock).toHaveBeenCalledWith('tax report');

    const after = view.shadowRoot?.querySelector('[data-testid="pin-toggle"]') as HTMLElement & {
      updateComplete: Promise<boolean>;
    };
    expect(after.hasAttribute('data-pressed')).toBe(true);
    expect(after.getAttribute('label')).toBe('Unpin this search');
    await after.updateComplete;
    (after.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
    await view.updateComplete;
    expect(unpinSearchMock).toHaveBeenCalledWith('pin-tax report');
  });

  it('a committed search records a run against the pin history', async () => {
    const view = mountView();
    await view.updateComplete;
    searchListener!({
      ...SEARCH_EMPTY,
      query: 'tax report',
      results: [{ id: 'h1', title: 'T', path: '/t.md', snippet: '', kind: 'markdown' }],
      matchCount: 1,
      totalHits: 3,
    });
    await view.updateComplete;
    (view as unknown as { commitLiveSearch(r: string): void }).commitLiveSearch('open');
    expect(recordRunMock).toHaveBeenCalledWith('tax report', 3);
  });
});

// Search Thread Round-2 R4 — the bar's secondary affordances conform to the `jf-control` atom
// (composed + skinned via ::part(control)), replacing four bespoke button classes. These tests lock
// in that the re-skinned controls are still keyboard-operable through the real DOM path (a native
// `<button>` inside jf-control's shadow root — Enter/Space activation is then a browser guarantee,
// not something the app has to wire up) and still fire their intended state changes.
describe('Search Thread Round-2 R4 — bar re-skin (jf-control compositions)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
  });

  async function clickJfControl(el: Element | null | undefined): Promise<void> {
    expect(el).not.toBeNull();
    expect(el!.tagName.toLowerCase()).toBe('jf-control');
    await (el as unknown as { updateComplete: Promise<boolean> }).updateComplete;
    (el!.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
  }

  it('schema-attach (jf-control) attaches the schema and is replaced by schema-detach', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    searchListener!({ ...SEARCH_EMPTY, query: 'invoices' });
    await view.updateComplete;

    await clickJfControl(view.shadowRoot?.querySelector('[data-testid="schema-attach"]'));
    await view.updateComplete;
    expect((view as unknown as { schemaAttached: boolean }).schemaAttached).toBe(true);

    view.affordance = 'extract';
    await view.updateComplete;
    await clickJfControl(view.shadowRoot?.querySelector('[data-testid="schema-detach"]'));
    await view.updateComplete;
    expect((view as unknown as { schemaAttached: boolean }).schemaAttached).toBe(false);
  });

  it('escalation-delegate (jf-control) is availability-gated: blocked offline, operable when AI is up', async () => {
    const view = mountView();
    await view.updateComplete;
    (view as unknown as { aiState: unknown }).aiState = {
      ...AI_STATE_READY,
      capabilities: { ...AI_STATE_READY.capabilities, chat: false },
    };
    view.requestUpdate();
    await view.updateComplete;

    const delegate = view.shadowRoot?.querySelector('[data-testid="escalation-delegate"]') as HTMLElement;
    expect(delegate).not.toBeNull();
    expect(delegate.tagName.toLowerCase()).toBe('jf-control');
    await clickJfControl(delegate);
    await view.updateComplete;
    expect(view.affordance).not.toBe('agent'); // blocked — offline

    (view as unknown as { aiState: unknown }).aiState = AI_STATE_READY; // chat: true
    view.requestUpdate();
    await view.updateComplete;
    await clickJfControl(view.shadowRoot?.querySelector('[data-testid="escalation-delegate"]'));
    await view.updateComplete;
    expect(view.affordance).toBe('agent');
  });

  // Tempdoc 804 §B9 (round-10 F14): Ask was the ONE escalation rung that failed SILENTLY with AI
  // offline — a plain <div>, so a click produced no mode change, no reason, and no disabled styling,
  // while Delegate showed tooltip+toast and Extract greyed to "AI Offline". Same affordance class →
  // same availability gate, same wording.
  it('escalation-ask (jf-control) is availability-gated with the sibling reason: blocked offline, operable when AI is up', async () => {
    const view = mountView();
    await view.updateComplete;
    (view as unknown as { aiState: unknown }).aiState = {
      ...AI_STATE_READY,
      capabilities: { ...AI_STATE_READY.capabilities, chat: false },
    };
    view.requestUpdate();
    await view.updateComplete;

    const ask = view.shadowRoot?.querySelector('[data-testid="escalation-ask"]') as HTMLElement & {
      availability?: { kind: string; reason?: string };
    };
    expect(ask).not.toBeNull();
    expect(ask.tagName.toLowerCase()).toBe('jf-control');
    // The reason is REACHABLE, not just absent-behaviour — and worded like its siblings.
    expect(ask.availability?.kind).toBe('unavailable');
    expect(ask.availability?.reason).toBe('The local AI model is offline');
    await clickJfControl(ask);
    await view.updateComplete;
    expect(view.affordance).not.toBe('documents'); // blocked — offline, and it SAYS so

    (view as unknown as { aiState: unknown }).aiState = AI_STATE_READY; // chat: true
    view.requestUpdate();
    await view.updateComplete;
    await clickJfControl(view.shadowRoot?.querySelector('[data-testid="escalation-ask"]'));
    await view.updateComplete;
    expect(view.affordance).toBe('documents');
  });
});

// Tempdoc 807 B.2 — the rung-reachability class, root-caused: the escalation strip rendered ONLY
// while `isLanding()`, so after ANY search every rung control left the DOM. Round 11 lost ~8 minutes
// to a Delegate reachable only from the empty landing; round 13 found no route back to Structured
// after a search and forfeited its `shape:core.extract` coverage gate. These tests assert the rungs
// are PRESENT AND ACTIVATABLE in the post-search state — the assertion both rounds would have failed.
describe('Tempdoc 807 B.2 — escalation rungs survive the landing → post-search transition', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    searchListener = null;
  });

  async function clickRung(view: UnifiedChatView, testid: string): Promise<void> {
    const el = view.shadowRoot?.querySelector(`[data-testid="${testid}"]`);
    expect(el, `${testid} must be in the DOM to be clickable`).toBeTruthy();
    expect(el!.tagName.toLowerCase()).toBe('jf-control');
    await (el as unknown as { updateComplete: Promise<boolean> }).updateComplete;
    (el!.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
  }

  function shapeOf(view: UnifiedChatView): string {
    return (view as unknown as { currentShapeId(): string }).currentShapeId();
  }

  /** A POST-SEARCH state: a live query + hits, so `isLanding()` is false and the bar is docked. */
  async function mountPostSearch(): Promise<UnifiedChatView> {
    const view = mountView();
    await view.updateComplete;
    searchListener!({
      ...SEARCH_EMPTY,
      query: 'quarterly report',
      results: [{ id: 'h1', title: 'T', path: '/t.md', snippet: '', kind: 'markdown' }],
      matchCount: 1,
      totalHits: 1,
    });
    await view.updateComplete;
    expect(
      view.shadowRoot?.querySelector('[data-testid="escalation-strip-docked"]'),
      'the docked strip must exist once a search has run',
    ).not.toBeNull();
    return view;
  }

  it('every rung is reachable AFTER a search has run — Delegate (round 11), Structured (round 13), Ask, and back to the floor', async () => {
    const view = await mountPostSearch();
    expect(view.affordance).toBe('retrieve');

    // Round 11: Delegate existed only on the empty landing; a cold restart was the only route.
    await clickRung(view, 'escalation-delegate');
    await view.updateComplete;
    expect(view.affordance).toBe('agent');

    // Round 13: no route back to Structured after a search — and from a non-retrieve tier the route
    // row (with its "+ Schema") is not rendered at all, so the strip is the ONLY way in.
    await clickRung(view, 'escalation-structured');
    await view.updateComplete;
    expect(view.affordance).toBe('extract');
    expect(shapeOf(view)).toBe('core.extract');

    await clickRung(view, 'escalation-ask');
    await view.updateComplete;
    expect(view.affordance).toBe('documents');
    expect(shapeOf(view)).toBe('core.rag-ask');

    await clickRung(view, 'escalation-search');
    await view.updateComplete;
    expect(view.affordance).toBe('retrieve');
  });

  it('the Structured rung ATTACHES (it does not pin the tier), so "Detach schema" still returns to the floor', async () => {
    const view = await mountPostSearch();
    // Pin a tier first: `explicit` outranks the attachment in deriveAffordance, so a rung that only
    // set `schemaAttached` from here would change nothing at all.
    await clickRung(view, 'escalation-ask');
    await view.updateComplete;
    expect(view.affordance).toBe('documents');

    await clickRung(view, 'escalation-structured');
    await view.updateComplete;
    expect((view as unknown as { schemaAttached: boolean }).schemaAttached).toBe(true);
    expect((view as unknown as { explicitAffordance: unknown }).explicitAffordance).toBeNull();
    expect(view.affordance).toBe('extract');

    await clickRung(view, 'schema-detach');
    await view.updateComplete;
    expect(view.affordance).toBe('retrieve');
  });

  it('post-search, an AI-requiring rung still NAMES its reason offline instead of dying silently (804 §B9 semantics survive)', async () => {
    const view = await mountPostSearch();
    (view as unknown as { aiState: unknown }).aiState = {
      ...AI_STATE_READY,
      capabilities: { ...AI_STATE_READY.capabilities, chat: false },
    };
    view.requestUpdate();
    await view.updateComplete;

    for (const id of ['escalation-ask', 'escalation-delegate', 'escalation-structured']) {
      const el = view.shadowRoot?.querySelector(`[data-testid="${id}"]`) as HTMLElement & {
        availability?: { kind: string; reason?: string };
      };
      expect(el, id).not.toBeNull();
      expect(el.availability?.kind, id).toBe('unavailable');
      expect(el.availability?.reason, id).toBe('The local AI model is offline');
    }

    await clickRung(view, 'escalation-structured');
    await view.updateComplete;
    expect(view.affordance).toBe('retrieve'); // blocked — and it says why

    // The search floor is never AI-gated: its rung stays operable with the model down.
    const floor = view.shadowRoot?.querySelector('[data-testid="escalation-search"]') as HTMLElement & {
      availability?: { kind: string };
    };
    expect(floor.availability).toBeUndefined();
  });

  it('the landing strip is UNCHANGED: one strip, landing copy, no docked-only rungs, no pressed state', async () => {
    const view = mountView();
    await view.updateComplete;

    const strips = view.shadowRoot!.querySelectorAll('.escalation-strip');
    expect(strips.length).toBe(1);
    const strip = strips[0] as HTMLElement;
    expect(strip.className).toBe('escalation-strip');
    expect(view.shadowRoot?.querySelector('[data-testid="escalation-strip-docked"]')).toBeNull();
    expect(strip.textContent).toContain('Search instantly');
    expect(strip.querySelector('[data-testid="escalation-ask"]')).not.toBeNull();
    expect(strip.querySelector('[data-testid="escalation-delegate"]')).not.toBeNull();
    // The docked-only rungs stay off the landing — there, Structured is still the route row's
    // "+ Schema" attachment and the floor is where you already are.
    expect(view.shadowRoot?.querySelector('[data-testid="escalation-structured"]')).toBeNull();
    expect(view.shadowRoot?.querySelector('[data-testid="escalation-search"]')).toBeNull();
    expect(strip.querySelector('[data-pressed]')).toBeNull();
    expect(view.shadowRoot?.querySelector('[data-testid="schema-attach"]')).not.toBeNull();
  });

  it('the docked strip marks the tier you are ON, so no rung offers a click that would change nothing', async () => {
    const view = await mountPostSearch();
    const pressed = (id: string): boolean =>
      view.shadowRoot!.querySelector(`[data-testid="${id}"]`)!.hasAttribute('data-pressed');
    // Round-13 review (P3): `data-pressed` is a CSS hook and NOTHING else — `jf-control` has no
    // `aria-pressed` passthrough, so asserting it alone passes for the wrong reason (a sighted-only
    // signal). The state a screen reader gets is the accessible NAME (the `renderPinToggle`
    // convention, 200 lines up), so assert that too — read off the rendered button, not the property.
    const accName = async (id: string): Promise<string> => {
      const el = view.shadowRoot!.querySelector(`[data-testid="${id}"]`)!;
      await (el as unknown as { updateComplete: Promise<boolean> }).updateComplete;
      return el.shadowRoot!.querySelector('button')!.getAttribute('aria-label') ?? '';
    };

    expect(pressed('escalation-search')).toBe(true);
    expect(pressed('escalation-delegate')).toBe(false);
    expect(await accName('escalation-search')).toContain('(current mode)');
    for (const id of ['escalation-delegate', 'escalation-ask', 'escalation-structured']) {
      expect(await accName(id), id).not.toContain('(current mode)');
    }

    await clickRung(view, 'escalation-delegate');
    await view.updateComplete;
    expect(pressed('escalation-delegate')).toBe(true);
    expect(pressed('escalation-search')).toBe(false);
    expect(await accName('escalation-delegate')).toBe(
      'Delegate a multi-step task to the agent (current mode)',
    );
    expect(await accName('escalation-search')).toBe('Back to instant search — no AI needed');

    await clickRung(view, 'escalation-structured');
    await view.updateComplete;
    expect(await accName('escalation-structured')).toBe(
      'Extract structured fields against a JSON schema (current mode)',
    );
    expect(await accName('escalation-delegate')).not.toContain('(current mode)');
  });
});

// Search Thread S6 (the Reading Stage) — the reading pane (`<jf-document-pane>`), mounted as the
// conversation-zone's 5th column, replacing the retired InspectorPane drawer. `readingDocPath` is
// driven by the shared inspectorState "open a document for reading" signal (the same store
// `host.ui.showInspector` and Shell's citation-select rework both push to), so these tests exercise
// the REAL (unmocked) inspectorState module directly rather than re-mocking it.
describe('Search Thread S6 — the reading pane (DocumentPane mount + open flows)', () => {
  type ReadingFields = {
    readingDocPath: string | null;
    readingHighlightRange: { startLine: number; endLine: number } | null;
    host_: unknown;
  };

  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    resetInspectorState();
    searchListener = null;
    scopeChipsListener = null;
    scopeChipsMock.chips = [];
  });

  afterEach(() => {
    resetInspectorState();
  });

  /** Push a fabricated single-hit live-search snapshot (mirrors the S4-final suite's helper above). */
  function pushSearch(view: UnifiedChatView, query: string): void {
    view.affordance = 'retrieve';
    expect(searchListener).not.toBeNull();
    searchListener!({
      query,
      results: [{ id: 'h1', title: 'Q1 invoice', path: '/docs/q1.md', snippet: 'total due' }],
      matchCount: 1,
      totalHits: 1,
      isSearching: false,
      processingTimeMs: 12,
      error: null,
      searchTrace: { effectiveMode: 'HYBRID' },
    });
  }

  /** Wire a minimal PluginHostApi whose `ui.showInspector`/`search.hitToSelectedItem` reach the REAL
   *  inspectorState store — the same path production's HostApiImpl/ui.ts capability takes. */
  function stubHost(view: UnifiedChatView): void {
    (view as unknown as ReadingFields).host_ = createMockHostApi({
      ui: {
        showInspector: (item) => setSelected({ id: item.id, title: item.title, path: item.path ?? '' }),
      },
      search: {
        hitToSelectedItem: (hit) => ({ id: hit.id, title: hit.title, path: hit.path }),
      },
    });
  }

  it('is unmounted by default and mounts <jf-document-pane> when readingDocPath is set', async () => {
    const view = mountView();
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('jf-document-pane')).toBeNull();
    (view as unknown as ReadingFields).readingDocPath = '/docs/q1.md';
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('jf-document-pane')).not.toBeNull();
    view.remove();
  });

  it('card-open on the LIVE retrieve card sets readingDocPath and auto-pins the opened hit\'s file scope chip', async () => {
    const { addScopeChip } = await import('../state/searchState.js');
    const view = mountView();
    stubHost(view);
    await view.updateComplete;
    pushSearch(view, 'invoice audit');
    await view.updateComplete;
    const liveCard = view.shadowRoot?.querySelector('jf-results-card:not([variant])');
    expect(liveCard).not.toBeNull();
    liveCard!.dispatchEvent(
      new CustomEvent('card-open', { detail: { id: 'h1' }, bubbles: true, composed: true }),
    );
    await view.updateComplete;
    expect((view as unknown as ReadingFields).readingDocPath).toBe('/docs/q1.md');
    // The handler calls the shared addScopeChip seam with the opened hit's path — dedup for a repeat
    // open of the SAME hit is addScopeChip's OWN job (kind+docId-set no-op, unit-tested in
    // searchState.scope.test.ts), not re-verified here against the module mock's simplified (always-
    // append) recording.
    expect(vi.mocked(addScopeChip)).toHaveBeenCalledWith({
      kind: 'file',
      label: 'q1.md',
      docIds: ['/docs/q1.md'],
    });
    expect(scopeChipsMock.chips).toContainEqual({ kind: 'file', label: 'q1.md', docIds: ['/docs/q1.md'] });
    view.remove();
  });

  it('a committed (historical snapshot) card-open also sets readingDocPath and auto-pins the chip', async () => {
    const view = mountView();
    stubHost(view);
    await view.updateComplete;
    (
      view as unknown as {
        committedSearches: Array<{
          id: string;
          query: string;
          mode: string;
          matchCount: number;
          resultCount: number;
          docIds: string[];
          executedAt: string;
          hits: Array<{ id: string; title: string; path: string; snippet: string }>;
        }>;
      }
    ).committedSearches = [
      {
        id: 'cs-1',
        query: 'q1',
        mode: 'HYBRID',
        matchCount: 1,
        resultCount: 1,
        docIds: ['/docs/q1.md'],
        executedAt: new Date().toISOString(),
        hits: [{ id: 'h1', title: 'Q1 invoice', path: '/docs/q1.md', snippet: 'total due' }],
      },
    ];
    (
      view as unknown as { handleCommittedCardOpen(hitId: string): void }
    ).handleCommittedCardOpen('h1');
    await view.updateComplete;
    expect((view as unknown as ReadingFields).readingDocPath).toBe('/docs/q1.md');
    expect(scopeChipsMock.chips).toEqual([{ kind: 'file', label: 'q1.md', docIds: ['/docs/q1.md'] }]);
    view.remove();
  });

  // Search Thread S7 (tempdoc decision 4) — a `card-open` bubbling out of the agent-search results
  // card nested inside `<jf-tool-call-card>` resolves the hit back out of the SAME structuredData
  // the card rendered from (no independent hit store) and opens through the same reading-pane path.
  it('a card-open from the agent-search tool card also sets readingDocPath and auto-pins the chip', async () => {
    const view = mountView();
    stubHost(view);
    await view.updateComplete;
    const toolCall = {
      callId: 'c1',
      toolName: 'core_search_index',
      arguments: '{"query":"invoice audit"}',
      risk: 'LOW',
      status: 'completed',
      structuredData: {
        query: 'invoice audit',
        resultCount: 1,
        searchResults: [{ title: 'Q1 invoice', path: '/docs/q1.md', excerpt: 'total due', line: 0 }],
      },
    } as unknown as import('../controllers/AgentSessionController.js').ToolCall;
    (
      view as unknown as { handleToolEvidenceOpen(toolCall: unknown, hitId: string): void }
    ).handleToolEvidenceOpen(toolCall, '/docs/q1.md');
    await view.updateComplete;
    expect((view as unknown as ReadingFields).readingDocPath).toBe('/docs/q1.md');
    expect(scopeChipsMock.chips).toEqual([{ kind: 'file', label: 'q1.md', docIds: ['/docs/q1.md'] }]);
    view.remove();
  });

  it('a citation-select-driven inspectorState.selected (Shell.onCitationSelect) sets readingDocPath + readingHighlightRange', async () => {
    const view = mountView();
    await view.updateComplete;
    // Mirrors what Shell.onCitationSelect now does — push the passage line range onto the shared
    // inspectorState `selected`, rather than reaching into a specific pane instance.
    setSelected({
      id: '/docs/report.md',
      title: 'report.md',
      path: '/docs/report.md',
      highlightStartLine: 4,
      highlightEndLine: 9,
    });
    await view.updateComplete;
    const fields = view as unknown as ReadingFields;
    expect(fields.readingDocPath).toBe('/docs/report.md');
    expect(fields.readingHighlightRange).toEqual({ startLine: 4, endLine: 9 });
    expect(view.shadowRoot?.querySelector('jf-document-pane')).not.toBeNull();
    view.remove();
  });

  it("the pane's pane-close clears readingDocPath and closes the shared inspectorState", async () => {
    const view = mountView();
    await view.updateComplete;
    setSelected({ id: '/docs/report.md', title: 'report.md', path: '/docs/report.md' });
    await view.updateComplete;
    const pane = view.shadowRoot?.querySelector('jf-document-pane');
    expect(pane).not.toBeNull();
    pane!.dispatchEvent(new CustomEvent('pane-close', { bubbles: true, composed: true }));
    await view.updateComplete;
    expect((view as unknown as ReadingFields).readingDocPath).toBeNull();
    expect(view.shadowRoot?.querySelector('jf-document-pane')).toBeNull();
    expect(getInspectorState().isOpen).toBe(false);
    view.remove();
  });

  it('the retired jf-inspector-pane never appears in the template', async () => {
    const view = mountView();
    await view.updateComplete;
    setSelected({ id: '/docs/report.md', title: 'report.md', path: '/docs/report.md' });
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('jf-inspector-pane')).toBeNull();
    view.remove();
  });

  it('scoped-ask coherence: card-open sets readingDocPath + pins the chip, and an escalated Ask forwards the opened path in docIds', async () => {
    vi.mocked(consumeShapeStream).mockImplementation(() => Promise.resolve());
    const view = mountView();
    stubHost(view);
    await view.updateComplete;
    pushSearch(view, 'invoice audit');
    await view.updateComplete;
    const liveCard = view.shadowRoot?.querySelector('jf-results-card:not([variant])');
    liveCard!.dispatchEvent(
      new CustomEvent('card-open', { detail: { id: 'h1' }, bubbles: true, composed: true }),
    );
    await view.updateComplete;
    expect((view as unknown as ReadingFields).readingDocPath).toBe('/docs/q1.md');
    expect(scopeChipsMock.chips).toEqual([{ kind: 'file', label: 'q1.md', docIds: ['/docs/q1.md'] }]);

    view.affordance = 'documents';
    view.inputDraft = 'what does this say about totals?';
    await view.updateComplete;
    const composer = view.shadowRoot?.querySelector('jf-composer');
    expect(composer).not.toBeNull();
    composer!.dispatchEvent(new CustomEvent('composer-submit'));
    await view.updateComplete;

    const streamMock = vi.mocked(consumeShapeStream);
    expect(streamMock).toHaveBeenCalled();
    const lastCall = streamMock.mock.calls[streamMock.mock.calls.length - 1]!;
    const body = lastCall[1] as { docIds?: string[] };
    expect(body.docIds).toContain('/docs/q1.md');
    view.remove();
  });
});

describe('Extraction dispatches the chosen mode, WITH the selected document', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    __resetSelectionForTest();
    takePendingForceShape();
    takePendingSelection();
    vi.mocked(consumeShapeStream).mockImplementation(() => Promise.resolve());
  });

  afterEach(() => __resetSelectionForTest());

  function selectDocument(): void {
    setSingleSelection(
      {
        kind: 'search-hit',
        hitId: '/docs/invoice.md',
        title: 'invoice.md',
        path: '/docs/invoice.md',
        capabilities: new Set(['open']),
      },
      'core.search-surface',
    );
  }

  async function sendInExtractMode(view: UnifiedChatView): Promise<Record<string, unknown>> {
    view.affordance = 'extract';
    view.inputDraft = 'pull the totals';
    await view.updateComplete;
    const composer = view.shadowRoot?.querySelector('jf-composer');
    expect(composer).not.toBeNull();
    composer!.dispatchEvent(new CustomEvent('composer-submit'));
    await view.updateComplete;
    const streamMock = vi.mocked(consumeShapeStream);
    expect(streamMock).toHaveBeenCalledTimes(1);
    return streamMock.mock.calls[0]![1] as Record<string, unknown>;
  }

  it('sends core.extract even though a document is selected (selection no longer overrides the mode)', async () => {
    selectDocument();
    const view = mountView();
    await view.updateComplete;
    const body = await sendInExtractMode(view);
    expect(body.shapeId).toBe('core.extract');
    expect(body.schema).toBe(view.schemaDraft);
    view.remove();
  });

  it('forwards the LIVE selection as body.selection, so the extraction has a document behind it', async () => {
    selectDocument();
    const view = mountView();
    await view.updateComplete;
    const body = await sendInExtractMode(view);
    expect(body.selection).toEqual({
      kind: 'item',
      itemKind: 'search-hit',
      itemId: '/docs/invoice.md',
      label: 'invoice.md',
    });
    view.remove();
  });

  it('the mode chip names the shape that is actually dispatched', async () => {
    selectDocument();
    const view = mountView();
    view.affordance = 'extract';
    await view.updateComplete;
    const chipLabel = view.shadowRoot?.querySelector('.shape-indicator')?.textContent?.trim();
    const body = await sendInExtractMode(view);
    // The chip used to read "Extraction" while `core.rag-ask` ("Document Q&A") was dispatched.
    expect(chipLabel).toBe('Extraction');
    expect(SHAPE_LABELS[body.shapeId as ShapeId]).toBe(chipLabel);
    view.remove();
  });

  it('with NO explicit mode, a selected document still resolves the shape — and the chip follows it', async () => {
    const view = mountView();
    view.affordance = 'none';
    await view.updateComplete;
    expect(view.shadowRoot?.querySelector('.shape-indicator')?.textContent?.trim()).toBe('Chat');

    // Selecting a document changes the shape the next Send will dispatch, so the chip must move
    // with it. The chip used to resolve with the selection kind hardcoded to 'none' and kept
    // reading "Chat" while Send dispatched core.rag-ask.
    selectDocument();
    await view.updateComplete;
    const chipLabel = view.shadowRoot?.querySelector('.shape-indicator')?.textContent?.trim();

    view.inputDraft = 'what does this say?';
    await view.updateComplete;
    const composer = view.shadowRoot?.querySelector('jf-composer');
    composer!.dispatchEvent(new CustomEvent('composer-submit'));
    await view.updateComplete;
    const body = vi.mocked(consumeShapeStream).mock.calls[0]![1] as Record<string, unknown>;
    expect(body.shapeId).toBe('core.rag-ask');
    expect(chipLabel).toBe('Document Q&A');
    expect(SHAPE_LABELS[body.shapeId as ShapeId]).toBe(chipLabel);
    view.remove();
  });
});

// 798 round 8 — clearing the query with a document preview open pushed the composer and the escalation
// ladder below the viewport (reproduced twice at 1040x709; recoverable only by navigating away and
// back). `.landing-collapsed` swaps the conversation zone's `flex: 1; min-height: 0` for `flex: 0 0
// auto`, which is sound only while the zone is EMPTY — with a `<jf-document-pane>` mounted, its
// `height: 100%` loses its definite basis, its scroll region stops being bounded, and the document lays
// out at full height inside a content-sized zone. happy-dom does no layout, so these assert the
// mechanism: the class that content-sizes the zone, and the declaration it resolves to.
describe('798 — the landing collapse yields to a mounted reading pane', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
    resetInspectorState();
    searchListener = null;
  });
  afterEach(() => resetInspectorState());

  /** The zone element whose flex sizing the collapse class changes. */
  const zone = (view: UnifiedChatView) =>
    view.shadowRoot!.querySelector('.conversation-zone') as HTMLElement;

  it('.landing-collapsed is the declaration that content-sizes the zone (the class is load-bearing)', () => {
    // Precondition for the two tests below: if this rule ever stops removing the zone's flex bound,
    // asserting on the class name would be asserting on nothing.
    const rule = /\.conversation-zone\.landing-collapsed\s*\{([^}]*)\}/.exec(
      unifiedChatBodyStyles.cssText,
    );
    expect(rule).not.toBeNull();
    expect(rule![1]!.replace(/\s+/g, ' ').trim()).toBe('flex: 0 0 auto;');
  });

  it('keeps the zone bounded when the query is cleared while the reading pane is mounted', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    expect(searchListener).not.toBeNull();
    searchListener!({ ...SEARCH_EMPTY, query: 'invoice', totalHits: 1 });
    (view as unknown as { readingDocPath: string | null }).readingDocPath = '/docs/q1.md';
    await view.updateComplete;
    expect(view.shadowRoot!.querySelector('jf-document-pane')).not.toBeNull();

    // The reported defect: clear the query (results + query go away, the surface returns to landing).
    searchListener!({ ...SEARCH_EMPTY });
    await view.updateComplete;

    // The preview a user opened deliberately survives a query-scoped action...
    expect(view.shadowRoot!.querySelector('jf-document-pane')).not.toBeNull();
    // ...and the zone keeps its flex bound, so the pane's `height: 100%` keeps a definite basis and
    // the composer below it stays on screen.
    expect(zone(view).classList.contains('landing-collapsed')).toBe(false);
    expect(view.shadowRoot!.querySelector('jf-composer')).not.toBeNull();
    view.remove();
  });

  it('still collapses on a cleared query when no reading pane is mounted (the gate is the pane, not the clear)', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    searchListener!({ ...SEARCH_EMPTY, query: 'invoice', totalHits: 1 });
    await view.updateComplete;
    expect(zone(view).classList.contains('landing-collapsed')).toBe(false);

    searchListener!({ ...SEARCH_EMPTY });
    await view.updateComplete;
    // No pane ⇒ the zone really is empty ⇒ 687 R5a's composer centring is still correct.
    expect(view.shadowRoot!.querySelector('jf-document-pane')).toBeNull();
    expect(zone(view).classList.contains('landing-collapsed')).toBe(true);
    view.remove();
  });
});

// 798 round 8 — the `@container chat-surface` query container must actually WRAP the zones that query
// it. A container declared on an element outside the grid's ancestor chain makes every wide-layout
// rule inert: the surface would silently stick to the narrow single-column stack at every width.
describe('798 — the wide-layout query container wraps the zones that query it', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
  });

  it('.conversation-zone and the composer both descend from .answer-plane', async () => {
    const view = mountView();
    await view.updateComplete;
    const plane = view.shadowRoot!.querySelector('.answer-plane');
    expect(plane).not.toBeNull();
    expect(plane!.querySelector('.conversation-zone')).not.toBeNull();
    // `.sources-affordance` is conditional on grounded agent sources; the composer that hosts it is not.
    expect(plane!.querySelector('.composer')).not.toBeNull();
    view.remove();
  });
});

// Review 2026-08 (FE review-fix bundle, item 3) — PR #373 claimed FE tests for the locked-send
// affordance (`sendBlockedReason`) and the 423-dispatch handler (`noteRefusedWhileLocked`). No such
// tests existed anywhere in the tree (grep: zero references to either symbol, or to
// `lockedSendNotice` / `.locked-send-notice`, outside the view itself). The production code DID ship
// (tempdoc 734 round-14 F4), so these tests are written against the SHIPPED behaviour.
describe('734 round-14 F4 — a locked chat store blocks Send and names why', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
  });

  /** The composer element the docked/landing block renders (one stable slot in every state). */
  function composer(view: UnifiedChatView): Element {
    const el = view.shadowRoot!.querySelector('jf-composer');
    expect(el).not.toBeNull();
    return el!;
  }

  it('locked ⇒ Send is disabled and the reason names the lock AND where to unlock it', async () => {
    const view = mountView();
    await view.updateComplete;
    // The retrieve tier is deliberately EXEMPT (a plain search is neither AI-dependent nor
    // encrypted), so the lock case only exists above it.
    view.affordance = 'documents';
    (view as unknown as { inputDraft: string }).inputDraft = 'a message worth keeping';
    await view.updateComplete;

    // Positive control FIRST: with the same draft and no lock, Send is live and names no reason.
    expect(composer(view).hasAttribute('submit-disabled')).toBe(false);
    expect(composer(view).getAttribute('submit-title')).toBe('');

    (view as unknown as { historyLocked: boolean }).historyLocked = true;
    await view.updateComplete;
    expect(composer(view).hasAttribute('submit-disabled')).toBe(true);
    const reason = composer(view).getAttribute('submit-title') ?? '';
    expect(reason).toContain('encrypted and locked');
    expect(reason).toContain('Security');
    view.remove();
  });

  it('the retrieve tier stays sendable while locked (the exemption is deliberate, not an oversight)', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'retrieve';
    (view as unknown as { inputDraft: string }).inputDraft = 'invoice';
    (view as unknown as { historyLocked: boolean }).historyLocked = true;
    await view.updateComplete;
    expect(composer(view).getAttribute('submit-title')).toBe('');
    expect(composer(view).hasAttribute('submit-disabled')).toBe(false);
    view.remove();
  });

  it('a 423 dispatch renders the notice + Unlock affordance, restores the draft, and takes back the optimistic bubble', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'documents';
    const text = 'please summarise the contract';
    (view as unknown as { inputDraft: string }).inputDraft = text;

    vi.mocked(consumeShapeStream).mockImplementationOnce(() =>
      Promise.reject(Object.assign(new Error('locked'), { status: 423 })),
    );
    await (view as unknown as { send(): Promise<void> }).send();
    await view.updateComplete;

    // 1. The surface adopts the locked state the server just reported.
    expect((view as unknown as { historyLocked: boolean }).historyLocked).toBe(true);
    // 2. The optimistic user bubble is taken back — a message shown as sent that was never recorded
    //    is the same lie in the UI as a 200 would have been on the wire.
    expect(
      (view as unknown as { thread: Array<{ role: string; content: string }> }).thread.some(
        (m) => m.role === 'user' && m.content === text,
      ),
    ).toBe(false);
    // 3. The text is back in the composer — it is the user's and nothing else holds it.
    expect((view as unknown as { inputDraft: string }).inputDraft).toBe(text);

    // …and the rendered notice says what became of the message, next to the remedy that fixes it.
    const sr = view.shadowRoot!;
    const notice = sr.querySelector('.locked-send-notice');
    expect(notice).not.toBeNull();
    expect(notice!.textContent).toContain('was not sent');
    expect(notice!.getAttribute('role')).toBe('alert');
    const locked = sr.querySelector('.history-locked')!;
    expect(locked.textContent).toContain('encrypted and locked');
    // The Unlock affordance routes to the Security surface (the remedy on the ONE CAUSE_ROWS row).
    const unlock = locked.querySelector('jf-button');
    expect(unlock).not.toBeNull();
    expect(unlock!.textContent).toContain('Unlock in Security');
    view.remove();
  });

  it('negative control: a 500 dispatch is an ERROR, never treated as locked', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'documents';
    const text = 'please summarise the contract';
    (view as unknown as { inputDraft: string }).inputDraft = text;

    vi.mocked(consumeShapeStream).mockImplementationOnce(() =>
      Promise.reject(Object.assign(new Error('boom'), { status: 500 })),
    );
    await (view as unknown as { send(): Promise<void> }).send();
    await view.updateComplete;

    expect((view as unknown as { historyLocked: boolean }).historyLocked).toBe(false);
    expect(view.shadowRoot!.querySelector('.history-locked')).toBeNull();
    // The non-423 path keeps the ordinary error contract: message shown, draft consumed, bubble kept.
    expect((view as unknown as { errorMessage: string }).errorMessage).not.toBe('');
    expect(
      (view as unknown as { thread: Array<{ role: string; content: string }> }).thread.some(
        (m) => m.role === 'user' && m.content === text,
      ),
    ).toBe(true);
    view.remove();
  });
});

// Review 2026-08 (FE review-fix bundle, item 4) — `spineItems()` suppresses the run spine unless the
// conversation has structure to index: `turns >= 2` OR `>= 2` distinct workflow-node boundaries. Only
// the TURN arm was tested (round-14 finding 15's pair above); this is the node-boundary arm, which is
// the arm the spine was actually built for ("the spine marks node boundaries", 565 §26).
describe('round-14 finding 15 — the spine gate is a DISJUNCTION: node boundaries are the second arm', () => {
  it('a SINGLE-turn run with two node boundaries renders the spine', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    (view as unknown as { wideZone: boolean }).wideZone = true;
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:00Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q', attributes: {} },
      { id: 's1', occurredAt: '2026-01-01T00:00:01Z', kind: 'PROGRESS', originator: 'agent', content: '', attributes: { nodeBoundary: 'start', nodeId: 'think', nodeKind: 'llm', label: 'think' } },
      { id: 't1', occurredAt: '2026-01-01T00:00:02Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } },
      { id: 'e1', occurredAt: '2026-01-01T00:00:03Z', kind: 'PROGRESS', originator: 'agent', content: '', attributes: { nodeBoundary: 'end', nodeId: 'think' } },
      { id: 's2', occurredAt: '2026-01-01T00:00:04Z', kind: 'PROGRESS', originator: 'agent', content: '', attributes: { nodeBoundary: 'start', nodeId: 'act', nodeKind: 'tool', label: 'act' } },
      { id: 'a1', occurredAt: '2026-01-01T00:00:05Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer', attributes: {} },
      { id: 'e2', occurredAt: '2026-01-01T00:00:06Z', kind: 'PROGRESS', originator: 'agent', content: '', attributes: { nodeBoundary: 'end', nodeId: 'act' } },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const sr = view.shadowRoot!;
    // ONE user turn — the turn arm cannot be what mounted this.
    expect(
      (view as unknown as { unifiedEvents: Array<{ kind: string }> }).unifiedEvents.filter(
        (e) => e.kind === 'USER_MESSAGE',
      ),
    ).toHaveLength(1);
    expect(sr.querySelector('.run-spine')).not.toBeNull();
    // The ONE predicate: the reading column hides its native scrollbar only when the spine (which IS
    // the scroll control) mounts, so both gates must read the same disjunction.
    expect(sr.querySelector('.conversation.jf-scrollbar-none')).not.toBeNull();
    __resetAgentSessionStore();
    view.remove();
  });

  it('ONE node boundary is not a boundary — it is the whole run, so the spine stays suppressed', async () => {
    __resetAgentSessionStore();
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'agent';
    (view as unknown as { wideZone: boolean }).wideZone = true;
    (view as unknown as { unifiedEvents: unknown[] }).unifiedEvents = [
      { id: 'u1', occurredAt: '2026-01-01T00:00:00Z', kind: 'USER_MESSAGE', originator: 'user', content: 'q', attributes: {} },
      { id: 's1', occurredAt: '2026-01-01T00:00:01Z', kind: 'PROGRESS', originator: 'agent', content: '', attributes: { nodeBoundary: 'start', nodeId: 'think', nodeKind: 'llm', label: 'think' } },
      { id: 't1', occurredAt: '2026-01-01T00:00:02Z', kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', toolName: 'core_search_index', status: 'completed' } },
      { id: 'a1', occurredAt: '2026-01-01T00:00:03Z', kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'answer', attributes: {} },
      { id: 'e1', occurredAt: '2026-01-01T00:00:04Z', kind: 'PROGRESS', originator: 'agent', content: '', attributes: { nodeBoundary: 'end', nodeId: 'think' } },
    ];
    view.requestUpdate();
    await view.updateComplete;
    expect(view.shadowRoot!.querySelector('.run-spine')).toBeNull();
    __resetAgentSessionStore();
    view.remove();
  });
});

/* ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Tempdoc 822 §3d — THE PROVENANCE GATE at the SHIPPED window's accumulator.
 *
 * One defect, two accumulators (sv3's is asserted in `SearchV3View.honesty.test.ts`). The two
 * citation events are already separate handlers, so the gate needs no payload field: the streaming
 * lexical matcher's word-overlap ratio lands in `lexicalScore` and never reaches a tier, and the
 * cross-encoder's probability lands in `verifiedScore` — the only score a mark, an underline or a
 * grounded/weak count may be computed from.
 *
 * The handlers are reached the way the stream reaches them: `consumeShapeStream` is mocked, so its
 * `onEvent` callback hands the real handler object to the (also mocked) shape dispatcher, and the
 * test drives the real handler from there. No hand-built claim objects.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════ */
describe('Tempdoc 822 §3d — the shipped window keeps lexical and verified scores apart', () => {
  interface RagHandlers {
    onRagCitations?(p: unknown): void;
    onRagCitationDelta?(p: unknown): void;
    onRagCitationMatches?(p: unknown): void;
  }

  /** Send one Ask and return the live handler object the stream would drive. */
  async function askAndCaptureHandlers(view: UnifiedChatView): Promise<RagHandlers> {
    view.affordance = 'documents';
    view.inputDraft = 'why did the renewal fail?';
    await view.updateComplete;
    view.shadowRoot?.querySelector('jf-composer')?.dispatchEvent(new CustomEvent('composer-submit'));
    await view.updateComplete;
    const onEvent = vi.mocked(consumeShapeStream).mock.calls.at(-1)![2] as (
      e: string,
      p: unknown,
    ) => void;
    const dispatchMock = vi.mocked(dispatchShapeEventToHandlers);
    dispatchMock.mockClear();
    onEvent('probe', {});
    return dispatchMock.mock.calls.at(-1)![0] as RagHandlers;
  }

  const claimsOf = (view: UnifiedChatView): readonly Claim[] =>
    (view as unknown as { claims: Claim[] }).claims;

  /** One chunk-precise retrieval source, in the shape `rag.citations` mints. */
  const ragSource = (chunkIndex: number): Record<string, unknown> => ({
    parentDocId: 'docs/a.md',
    chunkIndex,
    chunkTotal: 1,
    startChar: 0,
    endChar: 10,
    score: 0.9,
    excerpt: 'excerpt',
    startLine: 1,
    endLine: 2,
    headingText: '',
    headingLevel: 0,
  });

  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
  });

  it('a citation_delta contributes a LEXICAL score only — no verified score, no mark', async () => {
    const view = mountView();
    await view.updateComplete;
    const h = await askAndCaptureHandlers(view);
    h.onRagCitations?.({ citations: [ragSource(0)] });
    // Word overlap of 1.0 — the strongest possible reading, on the WRONG scale.
    h.onRagCitationDelta?.({
      sentenceIndex: 0,
      sentenceText: 'The lock held.',
      citations: [{ parentDocId: 'docs/a.md', sourceIndex: 0, score: 1 }],
    });
    await view.updateComplete;

    const claims = claimsOf(view);
    expect(claims).toHaveLength(1);
    expect(claims[0]!.verifiedScore).toBeNull();
    expect(claims[0]!.lexicalScore).toBe(1);
    // …and the resolver mints nothing from it: no mark, no underline, no grounded/weak count.
    const resolved = (
      view as unknown as {
        resolveClaimCitations(c: readonly Claim[], s: readonly unknown[]): unknown[];
      }
    ).resolveClaimCitations(claims, (view as unknown as { sources: unknown[] }).sources);
    expect(resolved).toEqual([]);
    view.remove();
  });

  it('a citation_matches merge sets the VERIFIED score and never maxes across the two scales', async () => {
    const view = mountView();
    await view.updateComplete;
    const h = await askAndCaptureHandlers(view);
    h.onRagCitations?.({ citations: [ragSource(0)] });
    h.onRagCitationDelta?.({
      sentenceIndex: 0,
      sentenceText: 'The lock held.',
      citations: [{ parentDocId: 'docs/a.md', sourceIndex: 0, score: 0.95 }],
    });
    h.onRagCitationMatches?.({
      matches: [
        {
          sentenceIndex: 0,
          sentenceText: 'The lock held.',
          sourceIndex: 0,
          similarity: 0.52,
          parentDocId: 'docs/a.md',
        },
      ],
    });
    await view.updateComplete;

    const claims = claimsOf(view);
    expect(claims).toHaveLength(1);
    // The pre-822 merge kept `Math.max(0.95, 0.52)` — a weak sentence reading 'grounded'.
    expect(claims[0]!.verifiedScore).toBe(0.52);
    expect(claims[0]!.lexicalScore).toBe(0.95);
    const resolved = (
      view as unknown as {
        resolveClaimCitations(c: readonly Claim[], s: readonly unknown[]): Array<{ similarity: number }>;
      }
    ).resolveClaimCitations(claims, (view as unknown as { sources: unknown[] }).sources);
    expect(resolved).toHaveLength(1);
    expect(resolved[0]!.similarity).toBe(0.52);
    view.remove();
  });

  it('a citation_matches merge REPLACES the draft sentence text at the same index (847 S5)', async () => {
    const view = mountView();
    await view.updateComplete;
    const h = await askAndCaptureHandlers(view);
    h.onRagCitations?.({ citations: [ragSource(0)] });
    // The draft cuts an incomplete markdown buffer as prose, so a whole list arrives as one
    // "sentence"; the final matches cut parsed block nodes. Same index, different sentence.
    h.onRagCitationDelta?.({
      sentenceIndex: 0,
      sentenceText: 'The lock held.\n- And the renewal date passed.',
      citations: [{ parentDocId: 'docs/a.md', sourceIndex: 0, score: 0.9 }],
    });
    h.onRagCitationMatches?.({
      matches: [
        {
          sentenceIndex: 0,
          sentenceText: 'The lock held.',
          sourceIndex: 0,
          similarity: 0.61,
          parentDocId: 'docs/a.md',
        },
      ],
    });
    await view.updateComplete;

    const claims = claimsOf(view);
    expect(claims).toHaveLength(1);
    // Keeping the draft's text would anchor this mark by a key naming a sentence that earned no
    // score, and make the live render disagree with the reload (which only ever sees the final).
    expect(claims[0]!.sentenceText).toBe('The lock held.');
    view.remove();
  });

  it('a rendered turn weaves marks for verified claims only — the lexical one stays plain prose', async () => {
    const view = mountView();
    await view.updateComplete;
    const v = view as unknown as { thread: unknown[]; affordance: string; isStreaming: boolean };
    v.affordance = 'documents';
    v.isStreaming = false;
    v.thread = [
      {
        role: 'assistant',
        content: 'One verified sentence. One lexical sentence.',
        shapeId: 'core.rag-ask',
        id: 'a1',
        sources: [ragSource(0)],
        claims: [
          {
            sentenceIndex: 0,
            sentenceText: 'One verified sentence.',
            verifiedScore: 0.9,
            lexicalScore: 0,
            verifiedRefs: [0],
            lexicalRefs: [],
          },
          {
            sentenceIndex: 1,
            sentenceText: 'One lexical sentence.',
            verifiedScore: null,
            lexicalScore: 0.88,
            verifiedRefs: [],
            lexicalRefs: [0],
          },
        ],
      },
    ];
    view.requestUpdate();
    await view.updateComplete;
    const block = view.shadowRoot!.querySelector(
      '.message.assistant[data-item-id="a1"] jf-markdown-block',
    ) as (HTMLElement & { citations: Array<{ similarity: number; sentenceText: string }> }) | null;
    expect(block).not.toBeNull();
    expect(block!.citations).toHaveLength(1);
    expect(block!.citations[0]!.sentenceText).toBe('One verified sentence.');
    expect(block!.citations[0]!.similarity).toBe(0.9);
    view.remove();
  });

  /* ── Tempdoc 822 §3b — the numbering contract in THIS window's accumulator. One defect, two
        accumulators: the same three assertions run against `sv3-ask`'s merge (see
        `SearchV3View.honesty.test.ts`), because a fix in one window is not a fix. ────────────── */

  it('a doubly-matched sentence resolves through the VERIFIED ref, not the delta that arrived first', async () => {
    const view = mountView();
    await view.updateComplete;
    const h = await askAndCaptureHandlers(view);
    h.onRagCitations?.({ citations: [ragSource(0), ragSource(1), ragSource(2)] });
    // The delta streams FIRST and guesses source 0 …
    h.onRagCitationDelta?.({
      sentenceIndex: 0,
      sentenceText: 'The lock held.',
      citations: [{ parentDocId: 'docs/a.md', sourceIndex: 0, score: 0.9 }],
    });
    // … then the authoritative matcher ties the sentence to source 2.
    h.onRagCitationMatches?.({
      matches: [
        {
          sentenceIndex: 0,
          sentenceText: 'The lock held.',
          sourceIndex: 2,
          similarity: 0.8,
          parentDocId: 'docs/a.md',
        },
      ],
    });
    await view.updateComplete;

    const claims = claimsOf(view);
    expect(claims[0]!.verifiedRefs).toEqual([2]);
    expect(claims[0]!.lexicalRefs).toEqual([0]);
    const resolved = (
      view as unknown as {
        resolveClaimCitations(c: readonly Claim[], s: readonly unknown[]): Array<{ label: number }>;
      }
    ).resolveClaimCitations(claims, (view as unknown as { sources: unknown[] }).sources);
    // The pre-822 merge put the delta's ref first in one set, so the mark read [1] and deep-linked
    // to the wrong passage.
    expect(resolved).toHaveLength(1);
    expect(resolved[0]!.label).toBe(3);
    view.remove();
  });

  it('an out-of-range streamed index mints NO mark — the 59-against-5 reproduction fails to reproduce', async () => {
    const view = mountView();
    await view.updateComplete;
    const h = await askAndCaptureHandlers(view);
    h.onRagCitations?.({ citations: [0, 1, 2, 3, 4].map((i) => ragSource(i)) });
    h.onRagCitationMatches?.({
      matches: [
        {
          sentenceIndex: 0,
          sentenceText: 'The lock held.',
          sourceIndex: 59,
          similarity: 0.9,
          parentDocId: 'docs/a.md',
        },
      ],
    });
    await view.updateComplete;

    const resolved = (
      view as unknown as {
        resolveClaimCitations(
          c: readonly Claim[],
          s: readonly unknown[],
        ): Array<{ label: number; detail: { parentDocId: string } }>;
      }
    ).resolveClaimCitations(claimsOf(view), (view as unknown as { sources: unknown[] }).sources);
    // BEFORE: one mark labelled 60, deep-linking to sources[0] via the removed fallback.
    expect(resolved).toEqual([]);
    view.remove();
  });

  it('reads a legacy persisted record under its old `chunkIndex` key (user data, no migration)', () => {
    // Persisted BEFORE the rename. The stored values were already positional (they come from the
    // authoritative `matchCitations` call), so the old record renders correctly under the new reader.
    const legacy = {
      matches: [
        { sentenceIndex: 0, sentenceText: 'The lock held.', chunkIndex: 2, similarity: 0.9, parentDocId: 'docs/a.md' },
      ],
    };
    expect(claimsFromRecord(legacy)[0]!.verifiedRefs).toEqual([2]);
    expect(matchesFromRecord(legacy)[0]!.sourceIndex).toBe(2);
    // A record written after the rename reads the same way.
    const current = {
      matches: [
        { sentenceIndex: 0, sentenceText: 'The lock held.', sourceIndex: 2, similarity: 0.9, parentDocId: 'docs/a.md' },
      ],
    };
    expect(claimsFromRecord(current)[0]!.verifiedRefs).toEqual([2]);
    expect(matchesFromRecord(current)[0]!.sourceIndex).toBe(2);
  });
});

/* ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Tempdoc 836 S2S3-A.6f — the coverage facts reach BOTH render paths, and the producer gate
 * applies to both.
 *
 * The window has TWO sites that write `Claim.verifiedScore`: the live `rag.citation_matches`
 * handler and `claimsFromRecord` (the persisted replay). A gate applied to only one of them is the
 * 561 P-A divergence — the same payload would mark differently before and after a reload — so
 * every assertion here runs the SAME payload through both.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════ */
describe('Tempdoc 836 S2S3 — coverage honesty and the producer gate on both render paths', () => {
  interface RagHandlers {
    onRagCitations?(p: unknown): void;
    onRagCitationMatches?(p: unknown): void;
  }

  async function askAndCaptureHandlers(view: UnifiedChatView): Promise<RagHandlers> {
    view.affordance = 'documents';
    view.inputDraft = 'why did the renewal fail?';
    await view.updateComplete;
    view.shadowRoot?.querySelector('jf-composer')?.dispatchEvent(new CustomEvent('composer-submit'));
    await view.updateComplete;
    const onEvent = vi.mocked(consumeShapeStream).mock.calls.at(-1)![2] as (
      e: string,
      p: unknown,
    ) => void;
    const dispatchMock = vi.mocked(dispatchShapeEventToHandlers);
    dispatchMock.mockClear();
    onEvent('probe', {});
    return dispatchMock.mock.calls.at(-1)![0] as RagHandlers;
  }

  const claimsOf = (view: UnifiedChatView): readonly Claim[] =>
    (view as unknown as { claims: Claim[] }).claims;

  const matchPayload = (scorer: string) => ({
    scorer,
    sentencesTotal: 4,
    sentencesScored: 4,
    sourceCoverage: [{ sourceIndex: 0, windowsConsidered: 12, windowsScored: 3 }],
    matches: [
      {
        sentenceIndex: 0,
        sentenceText: 'The lock held.',
        sourceIndex: 0,
        similarity: 0.82,
        parentDocId: 'docs/a.md',
        textSource: 'SUPPLIED',
      },
    ],
  });

  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
  });

  it('A.6f — the same payload yields identical claims live and on persisted replay', async () => {
    const view = mountView();
    await view.updateComplete;
    const h = await askAndCaptureHandlers(view);
    h.onRagCitationMatches?.(matchPayload('CROSS_ENCODER'));
    await view.updateComplete;
    const live = claimsOf(view);

    const replayed = claimsFromRecord(matchPayload('CROSS_ENCODER'));

    expect(replayed).toEqual([...live]);
    expect(live[0]!.verifiedScore).toBe(0.82);
    expect(live[0]!.verifiedRefs).toEqual([0]);
    view.remove();
  });

  it('A.6f — a cosine-fallback payload mints no verified score on EITHER path', async () => {
    const view = mountView();
    await view.updateComplete;
    const h = await askAndCaptureHandlers(view);
    h.onRagCitationMatches?.(matchPayload('EMBEDDING_COSINE'));
    await view.updateComplete;
    const live = claimsOf(view);

    const replayed = claimsFromRecord(matchPayload('EMBEDDING_COSINE'));

    // The claim still EXISTS — it is what arrived — but its score is on a scale the grounding
    // thresholds are not calibrated for, so it may not become a verified score on either path.
    expect(live[0]!.verifiedScore).toBeNull();
    expect(live[0]!.verifiedRefs).toEqual([]);
    expect(replayed).toEqual([...live]);
    // …and the resolver mints no mark from it.
    const resolved = (
      view as unknown as {
        resolveClaimCitations(c: readonly Claim[], s: readonly unknown[]): unknown[];
      }
    ).resolveClaimCitations(live, (view as unknown as { sources: unknown[] }).sources);
    expect(resolved).toEqual([]);
    view.remove();
  });

  it('847 §1.5b — the SOURCES panel answers to the same producer verdict, live and on replay', async () => {
    // The gate's second arm: `citations` (the CitationMatch[] the panel groups by) reached
    // `sourceGrounding` ungated, so a cosine payload rendered markless prose beside a panel still
    // announcing "Grounds N sentences" at a cosine-derived tier — two surfaces of one verdict,
    // disagreeing. Paired with the cross-encoder twin so the empty below is the gate, not the
    // fixture.
    const view = mountView();
    await view.updateComplete;
    const h = await askAndCaptureHandlers(view);
    h.onRagCitationMatches?.(matchPayload('EMBEDDING_COSINE'));
    await view.updateComplete;
    const gatedLive = (view as unknown as { citations: unknown[] }).citations;
    expect(gatedLive).toEqual([]);
    expect(matchesFromRecord(matchPayload('EMBEDDING_COSINE'))).toEqual([]);

    h.onRagCitationMatches?.(matchPayload('CROSS_ENCODER'));
    await view.updateComplete;
    expect((view as unknown as { citations: unknown[] }).citations).toHaveLength(1);
    expect(matchesFromRecord(matchPayload('CROSS_ENCODER'))).toHaveLength(1);
    view.remove();
  });

  it('a record written before the producer field existed keeps its marks (absence is not a verdict)', () => {
    const legacy = {
      matches: [
        {
          sentenceIndex: 0,
          sentenceText: 'The lock held.',
          sourceIndex: 2,
          similarity: 0.9,
          parentDocId: 'docs/a.md',
        },
      ],
    };
    const replayed = claimsFromRecord(legacy);
    expect(replayed[0]!.verifiedScore).toBe(0.9);
    expect(replayed[0]!.verifiedRefs).toEqual([2]);
  });

  it('the coverage facts land on the view, live', async () => {
    const view = mountView();
    await view.updateComplete;
    const h = await askAndCaptureHandlers(view);
    h.onRagCitationMatches?.(matchPayload('CROSS_ENCODER'));
    await view.updateComplete;

    const v = view as unknown as {
      coverage: { textIncomplete: boolean; sentencesTotal: number } | null;
      sourceCoverage: Array<{ windowsConsidered: number; windowsScored: number }>;
    };
    expect(v.coverage).not.toBeNull();
    expect(v.coverage!.textIncomplete).toBe(true);
    expect(v.coverage!.sentencesTotal).toBe(4);
    expect(v.sourceCoverage).toEqual([
      { sourceIndex: 0, windowsConsidered: 12, windowsScored: 3 },
    ]);
    view.remove();
  });
});

/* ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Tempdoc 848 — reasoning is TURN data, not stream decoration.
 *
 * Three legs, and the phase split matters: the streaming block renders the thinking from the first
 * reasoning token until `done` (two branches — pre-content, then answer-phase), and the COMMITTED
 * message renders it from there on. The committed block carries its own testid because
 * `jf-reasoning-block` alone matches the streaming one too, and an assertion that cannot tell them
 * apart passes for the wrong reason.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════ */
describe('Tempdoc 848 — the turn keeps its thinking after done, and after a reload', () => {
  interface ReasoningHandlers {
    onReasoningChunk?(p: unknown): void;
    onChunk?(p: unknown): void;
    onDone?(p: unknown): void;
  }

  beforeEach(() => {
    vi.clearAllMocks();
    resetUnifiedChatState();
  });

  async function askAndCaptureHandlers(view: UnifiedChatView): Promise<ReasoningHandlers> {
    view.inputDraft = 'why did the renewal fail?';
    await view.updateComplete;
    view.shadowRoot?.querySelector('jf-composer')?.dispatchEvent(new CustomEvent('composer-submit'));
    await view.updateComplete;
    const onEvent = vi.mocked(consumeShapeStream).mock.calls.at(-1)![2] as (
      e: string,
      p: unknown,
    ) => void;
    const dispatchMock = vi.mocked(dispatchShapeEventToHandlers);
    dispatchMock.mockClear();
    onEvent('probe', {});
    return dispatchMock.mock.calls.at(-1)![0] as ReasoningHandlers;
  }

  it('renders the thinking MID-STREAM, from the first answer token until done', async () => {
    // The answer-streaming phase is served by the streaming block's completed-blocks branch:
    // `onChunk` ends the thinking (pushing the block) and appends text in the same call. Deleting
    // that branch as "unreachable" would blank the block for the whole answer phase.
    const view = mountView();
    await view.updateComplete;
    const h = await askAndCaptureHandlers(view);
    h.onReasoningChunk?.({ text: 'weighing the options' });
    await view.updateComplete;
    expect(
      view.shadowRoot!.querySelector('jf-reasoning-block'),
      'the pre-content thinking phase renders the live controller',
    ).not.toBeNull();

    h.onChunk?.({ text: 'Because the lock held.' });
    await view.updateComplete;
    const v = view as unknown as { reasoning: { isThinking: boolean; reasoningBlocks: unknown[] } };
    expect(v.reasoning.isThinking, 'the first content token ends thinking').toBe(false);
    expect(v.reasoning.reasoningBlocks).toHaveLength(1);
    expect(
      view.shadowRoot!.querySelector('jf-reasoning-block'),
      'and the finalized block keeps rendering through the answer phase',
    ).not.toBeNull();
    expect(
      view.shadowRoot!.querySelector('[data-testid="chat-turn-reasoning"]'),
      'nothing is committed yet',
    ).toBeNull();
    view.remove();
  });

  it('keeps the thinking on the SETTLED turn after done (the 835 §9e loss)', async () => {
    const view = mountView();
    await view.updateComplete;
    const h = await askAndCaptureHandlers(view);
    h.onReasoningChunk?.({ text: 'weighing the options' });
    h.onChunk?.({ text: 'Because the lock held.' });
    h.onDone?.({});
    await view.updateComplete;

    const committed = view.shadowRoot!.querySelector('[data-testid="chat-turn-reasoning"]');
    expect(committed, 'the committed turn renders its own reasoning block').not.toBeNull();
    expect((committed as unknown as { text: string }).text).toBe('weighing the options');
    view.remove();
  });

  it('renders the thinking a FAILED run recorded on its terminal error event (848 D-7)', async () => {
    // The agent fold attaches a halted/errored run's trailing blocks to its ERROR event; without a
    // consumer here the fold would be writing to a reader that does not exist.
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'documents';
    await view.updateComplete;
    const v = view as unknown as { unifiedEvents: unknown[]; thread: unknown[] };
    v.unifiedEvents = [
      {
        id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE',
        originator: 'user', content: 'do the thing', attributes: {},
      },
      {
        id: 'e1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ERROR', originator: 'agent',
        content: 'the model went away',
        attributes: {
          errorCode: 'LLM_ERROR',
          reasoning: [{ text: 'got as far as the lock table', durationMs: 700 }],
        },
      },
    ];
    v.thread = [];
    view.requestUpdate();
    await view.updateComplete;

    expect(view.shadowRoot!.querySelector('.error')?.textContent).toContain('the model went away');
    const block = view.shadowRoot!.querySelector('[data-testid="chat-turn-reasoning"]');
    expect(block, 'a failed turn still shows what the model worked out').not.toBeNull();
    expect((block as unknown as { text: string }).text).toBe('got as far as the lock table');
    view.remove();
  });

  it('renders the thinking from the RECORD on reload, with no live thread entry', async () => {
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'documents';
    await view.updateComplete;
    const v = view as unknown as { unifiedEvents: unknown[]; thread: unknown[] };
    v.unifiedEvents = [
      {
        id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE',
        originator: 'user', content: 'Q', attributes: {},
      },
      {
        id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE',
        originator: 'agent', content: 'The answer.',
        attributes: { reasoning: [{ text: 'recorded thinking', durationMs: 1840 }] },
      },
    ];
    v.thread = [];
    view.requestUpdate();
    await view.updateComplete;

    // Tempdoc 859 §A §3.5 — ONCE, counted. This is the ordinary assistant-with-thinking shape, and
    // it is the one that goes THROUGH `renderMessage` (which draws `ThreadMessage.reasoning`), so it
    // is the real double-render path: it is the shape that renders twice if the lifted read above
    // the kind switch stops excluding the arms that already draw their own. A `querySelector` here
    // could not see that — it would find the first of two and pass.
    const blocks = [...view.shadowRoot!.querySelectorAll('[data-testid="chat-turn-reasoning"]')];
    expect(blocks, 'a reloaded turn renders its thinking FROM the record, exactly once').toHaveLength(1);
    expect((blocks[0] as unknown as { text: string }).text).toBe('recorded thinking');
    expect((blocks[0] as unknown as { durationMs: number }).durationMs).toBe(1840);
    view.remove();
  });

  it('U-1 (859 §A D-2b): reasoning riding a TOOL-ACTIVITY carrier renders here too', async () => {
    // Fails on the shipped read, which looked at `assistant` and `error` only. Once the record fold
    // flushes a block onto the next event that PROJECTS, most carriers are tool-activity, handoff or
    // progress items — so leaving the per-arm read in place would have silently DELETED a delegate
    // run's reasoning from this window while the new window gained it.
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'documents';
    await view.updateComplete;
    const v = view as unknown as { unifiedEvents: unknown[]; thread: unknown[] };
    v.unifiedEvents = [
      {
        id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE',
        originator: 'user', content: 'index the vendor folder', attributes: {},
      },
      {
        id: 'c1:proposed', occurredAt: '2026-01-01T00:00:02Z', kind: 'TOOL_ACTIVITY',
        originator: 'agent', content: '',
        attributes: {
          callId: 'c1', toolName: 'core_search', status: 'completed', risk: 'low',
          reasoning: [{ text: 'search before reading', durationMs: 900 }],
        },
      },
    ];
    v.thread = [];
    view.requestUpdate();
    await view.updateComplete;

    const blocks = [...view.shadowRoot!.querySelectorAll('[data-testid="chat-turn-reasoning"]')];
    expect(blocks, 'the tool step’s own thinking is on screen').toHaveLength(1);
    expect((blocks[0] as unknown as { text: string }).text).toBe('search before reading');
    view.remove();
  });

  it('U-1b: a turn’s thinking is never drawn TWICE — one read, above the switch', async () => {
    // The other half of the lift: with the per-arm reads still in place beside the lifted one, every
    // reloaded answer would render its blocks once from each.
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'documents';
    await view.updateComplete;
    const v = view as unknown as { unifiedEvents: unknown[]; thread: unknown[] };
    v.unifiedEvents = [
      {
        id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE',
        originator: 'user', content: 'Q', attributes: {},
      },
      {
        id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE',
        originator: 'agent', content: 'The answer.',
        attributes: {
          sources: [{ parentDocId: 'f:/docs/x.md', startLine: 1, endLine: 3, excerpt: 'x' }],
          citations: [],
          reasoning: [{ text: 'exactly one copy', durationMs: 500 }],
        },
      },
    ];
    v.thread = [];
    view.requestUpdate();
    await view.updateComplete;

    expect(view.shadowRoot!.querySelectorAll('[data-testid="chat-turn-reasoning"]')).toHaveLength(1);
  });
});

/**
 * Tempdoc 941 round 19 finding F4 — a RELOADED turn is framed by the TURN's shape, not by the
 * window's current mode.
 *
 * Observed: a Documents-rung (`core.rag-ask`) answer rendered live as "Based on your documents"
 * with five source cards. After a four-process cold restart and reopening the conversation from
 * history, the same answer text and the same five sources came back from the record — but the card
 * now read "Model answer - this mode does not search your documents". The evidence survived; the
 * epistemic frame did not.
 *
 * Mechanism: `renderUnifiedItemBody`'s record arm stamped `enriched.shapeId = this.currentShapeId()`
 * — a read of `this.affordance`, i.e. which mode the window is in RIGHT NOW. A cold restart drops
 * the affordance back to `'none'`, so every reloaded turn was re-declared `core.free-chat`, whose
 * `declaredGroundingClass` is `ungrounded-llm`. The frame authority then framed correctly the shape
 * it was handed; it was handed the wrong shape.
 *
 * The turn's own shape IS on the record (tempdoc 863 §4.A.5 F1 — `FileConversationStore.enrichMessage`
 * stamps `shapeId` per message and `InteractionThreadController.chatTurn` puts it on
 * `attributes.shapeId`), and `sv3-record.ts`'s `declaredShapeOf` already reads it. This window did not.
 */
describe('UnifiedChatView reloaded-turn shape provenance (tempdoc 941 F4)', () => {
  /** The real wire shape of a persisted RAG source: `RetrievalCitation` under `attributes.citations`. */
  function ragSource(n: number): Record<string, unknown> {
    return {
      parentDocId: `f:/docs/doc-${n}.md`,
      chunkIndex: n,
      chunkTotal: 5,
      startChar: 0,
      endChar: 120,
      score: 0.8,
      excerpt: `excerpt ${n}`,
      startLine: 1,
      endLine: 4,
      headingText: '',
      headingLevel: 0,
    };
  }

  /**
   * The `GET /api/thread/{id}` rows for the observed conversation, built from the producing code —
   * `InteractionThreadController.toWireRow` (id/occurredAt/kind/originator/content/attributes) over
   * `chatTurn`, which emits `attributes.citations` (the retrieval sources) and `attributes.shapeId`
   * (the dispatching shape, 863 F1). Deliberately NO `attributes.sources` key: that is the ACTION
   * plane's discriminator, and this is an answer-plane RAG turn.
   */
  function ragRecord(): unknown[] {
    return [
      {
        id: 'u1',
        occurredAt: '2026-01-01T00:00:01Z',
        kind: 'USER_MESSAGE',
        originator: 'user',
        content: 'What does the Worker own?',
        attributes: { shapeId: 'core.rag-ask' },
      },
      {
        id: 'a1',
        occurredAt: '2026-01-01T00:00:02Z',
        kind: 'ASSISTANT_MESSAGE',
        originator: 'agent',
        content: 'The Worker owns all index I/O.',
        attributes: {
          shapeId: 'core.rag-ask',
          citations: [ragSource(0), ragSource(1), ragSource(2), ragSource(3), ragSource(4)],
        },
      },
    ];
  }

  it('F4: a reloaded core.rag-ask turn keeps its grounded frame after a cold restart drops the affordance', async () => {
    const view = mountView();
    await view.updateComplete;
    // The cold-restart state: `unifiedChatState` died with the process, so the window lands on the
    // default affordance. The user reopens the conversation from history — `loadConversation` never
    // sets an affordance, so `currentShapeId()` answers `core.free-chat`.
    (view as unknown as { affordance: string }).affordance = 'none';
    const v = view as unknown as { unifiedEvents: unknown[]; thread: unknown[] };
    v.unifiedEvents = ragRecord();
    // The reload-durability case: `loadConversation` rebuilds role/content/id only, so the live
    // thread carries NO evidence and `attachLiveMatch` leaves the record to render.
    v.thread = [];
    view.requestUpdate();
    await view.updateComplete;

    const turn = view.shadowRoot!.querySelector('.message.assistant[data-item-id="a1"]');
    expect(turn, 'the reloaded turn renders from the record').not.toBeNull();
    // The five persisted sources are still there — that half never broke.
    const panel = turn!.querySelector('jf-citations-panel') as unknown as {
      sources?: unknown[];
    } | null;
    expect(panel?.sources).toHaveLength(5);

    const text = (turn!.textContent ?? '').replace(/\s+/g, ' ');
    // The frame must state the provenance the record carries…
    expect(text).toContain('Based on your documents');
    // …and must NOT re-declare a Documents-rung answer an ungrounded model answer.
    expect(text).not.toContain('this mode does not search your documents');
    view.remove();
  });

  it('F4: the reloaded turn shape tag names the shape that answered, not the window mode', async () => {
    const view = mountView();
    await view.updateComplete;
    (view as unknown as { affordance: string }).affordance = 'none';
    const v = view as unknown as { unifiedEvents: unknown[]; thread: unknown[] };
    v.unifiedEvents = ragRecord();
    v.thread = [];
    view.requestUpdate();
    await view.updateComplete;

    const tag = view.shadowRoot!.querySelector(
      '.message.assistant[data-item-id="a1"] .message-shape-tag',
    );
    expect(tag?.textContent?.trim()).toBe(SHAPE_LABELS['core.rag-ask']);
    view.remove();
  });

  it('F4: a record turn that declares NO shape still falls back to the window mode (pre-863 rows)', async () => {
    // No backfill exists for messages written before 863 stamped `shapeId`, so the legacy class must
    // keep the only answer available to it — the window's current mode — rather than degrade to a
    // hardcoded default.
    const view = mountView();
    await view.updateComplete;
    view.affordance = 'documents';
    const v = view as unknown as { unifiedEvents: unknown[]; thread: unknown[] };
    v.unifiedEvents = [
      {
        id: 'u1', occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE',
        originator: 'user', content: 'q', attributes: {},
      },
      {
        id: 'a1', occurredAt: '2026-01-01T00:00:02Z', kind: 'ASSISTANT_MESSAGE',
        originator: 'agent', content: 'The Worker owns all index I/O.',
        attributes: { citations: [ragSource(0)] },
      },
    ];
    v.thread = [];
    view.requestUpdate();
    await view.updateComplete;

    const text = (
      view.shadowRoot!.querySelector('.message.assistant[data-item-id="a1"]')!.textContent ?? ''
    ).replace(/\s+/g, ' ');
    expect(text).toContain('Based on your documents');
    expect(text).not.toContain('this mode does not search your documents');
    view.remove();
  });
});
