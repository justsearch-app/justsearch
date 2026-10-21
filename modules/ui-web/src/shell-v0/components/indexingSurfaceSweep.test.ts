// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, type TemplateResult } from 'lit';
import '../views/BrainSurface.js';
import '../views/HealthSurface.js';
import '../views/LibrarySurface.js';
import '../views/SystemSelfView.js';
import './TaskList.js';
import './StatusDeck.js';
import './LivenessReadout.js';
import '../views/UnifiedChatView.js';
import './CapabilityMap.js';
import { __feedForTest, __resetAiStateForTest, __tickClockForTest, getAiState, type AiState, type StatusSnapshot } from '../state/aiStateStore.js';
import { __resetTasksForTest, upsertMirroredTask } from '../substrates/tasks/index.js';
import { selectIndexingProgress } from '../state/indexingProgress.js';
import { projectAvailability } from '../state/availability.js';
import { known } from '../state/known.js';
import { projectSv3Degradation } from '../views/search-v3/sv3-degradation.js';
import { applyPresentationBodies, __resetPresentationForTest } from '../state/presentationRuntime.js';
import { CORE_DECLARED } from '../themes/builtinPresentations.js';
import '../renderers/controls/FolderCardRenderer.js';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';

// Detached elements exercise the real renderers without starting transport or timers.
// Observation age and reachability still come from the production store.
type Harness = {
  status: StatusSnapshot; systemStatus: StatusSnapshot; aiState: AiState; _unifiedAiState: AiState;
  connPhase: string; verdict: AiState['verdict']; policy: object; busy: object;
  progress: ReturnType<typeof selectIndexingProgress>; filesOpen: boolean;
  inference: AiState['inference']; host_: unknown; enrichmentStages: unknown; enrichmentPending: boolean;
  indexingActivity: ReturnType<typeof selectIndexingProgress>['activity']; provisional: boolean;
  inferenceChipReadout(): { label: string }; renderCoreItem(id: string): TemplateResult; renderConnection(): TemplateResult; renderHeader(): TemplateResult; renderRecommendedActions(): TemplateResult;
  renderRebuildProgress(): TemplateResult; renderSimplePanel(): TemplateResult; renderEnrichmentProgress(): TemplateResult;
  renderCompatibilityCallouts(): TemplateResult; renderDegradationBanner(): TemplateResult; renderAiEngine(): TemplateResult; renderQueueDb(): TemplateResult; renderCardsRegion(): TemplateResult; renderCard(root: IndexedRootView): TemplateResult; render(): TemplateResult;
};
const CASES = ['energy', 'game', 'external GPU', 'chat', 'age', 'explicit stale', 'Engine gone'] as const;
type Case = typeof CASES[number];
const UNCONFIRMED = 'Indexing status unconfirmed';
function observe(which: Case, jobs = 0, pending = 80, rebuilding = false): AiState {
  __resetAiStateForTest();
  const now = new Date('2026-10-20T18:00:00Z').getTime();
  vi.setSystemTime(now);
  const reasons = which === 'energy' ? ['energy_saver'] : which === 'game' ? ['game_mode']
    : which === 'external GPU' ? ['gpu_busy_external'] : which === 'chat' ? ['chat_generating'] : [];
  const status = {
    meta: { workerRpcAtMs: now, workerRpcStale: false },
    worker: {
      core: { indexState: jobs ? 'INDEXING' : 'IDLE', pendingJobs: jobs, indexHealthy: true },
      enrichment: { embeddingEnabled: true, embeddingDocCount: 100, embeddingPendingCount: pending, spladeEnabled: false, nerEnabled: false },
      migration: { migrationState: rebuilding ? 'MIGRATING' : 'IDLE', activeIndexedDocuments: 100, buildingIndexedDocuments: 60 },
    },
    operating: { indexing: { state: reasons.length ? 'PAUSED' : 'ACTIVE', reasons },
      freshness: { staleAfterMs: 30_000, staleSources: which === 'explicit stale' ? ['indexing'] : [] } },
    readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
  } as unknown as StatusSnapshot;
  __feedForTest({ status, inference: { mode: 'indexing', starting: false } });
  if (which === 'age' || which === 'Engine gone') vi.setSystemTime(now + (which === 'Engine gone' ? 120_001 : 30_001));
  if (which === 'age') __feedForTest({ inference: { mode: 'indexing', starting: false } });
  __tickClockForTest();
  return getAiState();
}
function harness(tag: string, state: AiState): Harness {
  const el = document.createElement(tag) as unknown as Harness;
  if (tag !== 'jf-status-deck') el.status = state.status!;
  el.systemStatus = state.status!;
  el.inference = state.inference;
  el.aiState = state;
  el._unifiedAiState = { ...state, aiEngine: { kind: 'indexing', stability: { kind: 'settled' }, installFailure: null } };
  el.verdict = state.verdict;
  el.connPhase = state.phase;
  el.policy = {};
  el.busy = {};
  el.progress = selectIndexingProgress(state.status, state.indexingStatusLive, state.episodeMaxPendingJobs, state.enrichSettleSamples);
  return el;
}
function dom(tpl: TemplateResult): HTMLDivElement {
  const container = document.createElement('div');
  render(tpl, container);
  return container;
}
function expectedReason(which: Case): string {
  return which === 'energy' ? 'Idle enrichment deferred to save energy.'
    : which === 'game' ? 'GPU-bound idle enrichment deferred while a game is running.'
      : which === 'external GPU' ? 'GPU-bound idle enrichment deferred while another app is using the GPU.'
        : which === 'chat' ? 'Indexing shares time with chat.' : UNCONFIRMED;
}
function unconfirmed(which: Case) { return ['age', 'explicit stale', 'Engine gone'].includes(which); }

beforeEach(() => { vi.useFakeTimers(); __resetAiStateForTest(); __resetTasksForTest(); });
afterEach(async () => {
  __resetAiStateForTest(); __resetTasksForTest(); __resetPresentationForTest();
  await Promise.resolve();
  vi.useRealTimers();
});

describe('WP3.3 complete surface sweep', () => {
  it('S33.1 S33.4 S33.6 Health index-state row uses confirmation and scoped reasons in every case', () => {
    for (const which of CASES) for (const jobs of [4, 0]) for (const pending of [80, 0]) {
      const el = harness('jf-health-surface', observe(which, jobs, pending));
      const row = [...dom(el.renderConnection()).querySelectorAll('.data-row')]
        .find(row => row.querySelector('.key')?.textContent === 'Index state')!;
      expect(row.textContent, `${which}, ${jobs} files, ${pending} enrichment`).toContain(expectedReason(which));
      if (unconfirmed(which)) expect(row.querySelector('.val')?.getAttribute('style')).not.toContain('accent-success');
      if (pending > 0) expect(row.textContent).not.toMatch(/Index state\s*Ready/);
    }
  });
  it('S33.4 Health header and summary withhold operational claims for all unconfirmed observations', () => {
    for (const which of CASES) {
      const el = harness('jf-health-surface', observe(which));
      const summary = dom(el.renderRecommendedActions());
      expect(summary.textContent, which).toContain(expectedReason(which));
      if (unconfirmed(which)) {
        const header = dom(el.renderHeader());
        expect(header.textContent, which).toContain(UNCONFIRMED);
        expect(summary.textContent).not.toContain('All systems operational');
        expect(header.querySelector('jf-status-badge')?.getAttribute('tone')).not.toBe('success');
      }
    }
  });
  it('S33.4 Health rebuild bar never asserts current progress without confirmation', () => {
    for (const which of CASES) {
      const el = harness('jf-health-surface', observe(which, 0, 80, true));
      const bar = dom(el.renderRebuildProgress()).querySelector('[role="progressbar"]');
      expect(bar === null, which).toBe(unconfirmed(which));
      if (bar) expect(bar.getAttribute('aria-valuenow')).toBe('60');
    }
  });
  it('S33.1 S33.2 S33.3 S33.4 S33.6 Brain cards explain holds and never animate deferred or unconfirmed enrichment', () => {
    for (const which of CASES) {
      const el = harness('jf-brain-surface', observe(which));
      const card = dom(el.renderEnrichmentProgress());
      expect(card.textContent, which).toContain(expectedReason(which));
      expect(card.querySelector('.spin, [class*="spin"]') === null, which).toBe(which !== 'chat');
      if (which !== 'chat') expect(card.textContent, which).not.toContain('Building semantic search');
      if (unconfirmed(which)) expect(card.textContent).toContain('pending when last observed');
    }
  });
  it('S33.1 S33.4 S33.6 Brain simple panel describes observed work rather than inferring activity from runtime mode', () => {
    for (const which of CASES) for (const jobs of [4, 0]) for (const awaitingChatEnable of [false, true]) {
      const el = harness('jf-brain-surface', observe(which, jobs));
      Object.assign(el._unifiedAiState.aiEngine, { awaitingChatEnable });
      const panel = dom(el.renderSimplePanel());
      expect(panel.textContent, `${which}, ${jobs} files`).toContain(expectedReason(which));
      expect(panel.textContent).not.toContain('Indexing embeddings');
    }
  });
  it('S33.4 Library row cannot show a completion glyph from an unconfirmed index observation', () => {
    for (const which of CASES) {
      const el = harness('jf-library-surface', observe(which, 0, 0));
      el.indexingActivity = el.progress.activity;
      el.provisional = false; // Isolate confirmation from unrelated rebuild stability.
      el.enrichmentStages = el.progress.stages;
      el.enrichmentPending = false;
      el.host_ = { utilities: { formatRelativeTime: () => 'just now' } };
      const root = { pathHash: 'folder', status: 'indexed', walkCompleted: true, fileCount: 10,
        inFlightCount: 0, failedCount: 0 } as IndexedRootView;
      const row = dom(el.renderCard(root));
      if (unconfirmed(which)) {
        expect(row.textContent, which).toContain(UNCONFIRMED);
        expect(row.querySelector('.status-icon.indexed'), which).toBeNull();
      } else expect(row.querySelector('.status-icon.indexed'), which).not.toBeNull();
    }
  });
  it('S33.4 Tasks and System self-view qualify retained indexing rows while preserving independent agent tasks', () => {
    for (const which of CASES) {
      const state = observe(which, 4);
      __resetTasksForTest();
      upsertMirroredTask({ id: 'idxjob:one', label: 'one.pdf', status: 'running', progress: 0.4 });
      upsertMirroredTask({ id: 'agent:one', label: 'Agent task', status: 'running' });
      for (const tag of ['jf-task-list', 'jf-system-self-view']) {
        const el = harness(tag, state);
        if (tag === 'jf-task-list') {
          el.filesOpen = true;
          Object.assign(el, { tasks: [
            { id: 'idxjob:one', label: 'one.pdf', status: 'running', progress: 0.4 },
            { id: 'agent:one', label: 'Agent task', status: 'running' },
          ] });
        }
        const output = dom(el.render());
        const row = output.querySelector(tag === 'jf-task-list' ? '[data-testid="task-idxjob:one"]' : '[data-testid="self-view-task"]')!;
        expect(row.textContent, `${tag}: ${which}`).toContain(unconfirmed(which) ? 'last observed running' : 'running');
        if (unconfirmed(which)) expect(row.querySelector('.bar')).toBeNull();
        expect(output.textContent).toContain('Agent task');
      }
    }
  });
  it('S33.4 status-deck running-job chip marks retained indexing activity as last observed', () => {
    for (const which of CASES) {
      const el = harness('jf-status-deck', observe(which, 4));
      Object.assign(el, { runningTasks: [{ id: 'idxjob:one', label: 'one.pdf', status: 'running' }] });
      const chip = dom(el.renderCoreItem('core.running-job'));
      const label = chip.querySelector('[aria-label]')?.getAttribute('aria-label') ?? '';
      expect(label, which).toContain(unconfirmed(which) ? 'last observed' : 'one.pdf');
      if (unconfirmed(which)) expect(chip.textContent).toContain('last observed');
    }
  });

  it('S33.1 S33.4 Health AI queue rows use the status observation and its hold reasons instead of inference residue', () => {
    for (const which of CASES) {
      const el = harness('jf-health-surface', observe(which));
      el.inference = { ...el.inference, embeddingQueueSize: 999, vduQueueSize: 999 } as AiState['inference'];
      const card = dom(el.renderAiEngine());
      expect(card.textContent, which).toContain(expectedReason(which));
      expect(card.textContent).not.toContain('999');
      expect(card.textContent).toContain(unconfirmed(which) ? 'Last observed embed queue' : 'Embed queue');
      expect(card.textContent).toContain('80');
    }
  });
  it('S33.4 Health queue health qualifies its retained diagnostic when indexing is unconfirmed', () => {
    for (const which of CASES) {
      const card = dom(harness('jf-health-surface', observe(which)).renderQueueDb());
      if (unconfirmed(which)) {
        expect(card.textContent, which).toContain('Healthy (last observed)');
        expect(card.querySelector('.val')?.getAttribute('style')).not.toContain('accent-success');
      } else expect(card.textContent).toContain('Healthy');
    }
  });
  it('S33.4 declared Library cards consume the same confirmation verdict as fallback cards', () => {
    expect(applyPresentationBodies(CORE_DECLARED).ok).toBe(true);
    for (const which of CASES) {
      const el = harness('jf-library-surface', observe(which, 0, 0));
      el.indexingActivity = el.progress.activity;
      el.provisional = false;
      el.enrichmentStages = el.progress.stages;
      el.enrichmentPending = false;
      el.host_ = { utilities: { formatRelativeTime: () => 'just now' } };
      Object.assign(el, { roots: [{ pathHash: 'folder', status: 'indexed', walkCompleted: true, fileCount: 10, inFlightCount: 0, failedCount: 0 }] });
      const declared = dom(el.renderCardsRegion()).querySelector('jf-declared-surface') as unknown as { data: { folders: unknown[] } };
      const renderer = document.createElement('jf-folder-card') as unknown as { data: unknown; visible: boolean; render(): TemplateResult };
      renderer.data = declared.data.folders;
      renderer.visible = true;
      const card = dom(renderer.render());
      expect(card.querySelector('.status-icon.indexed') === null, which).toBe(unconfirmed(which));
      if (unconfirmed(which)) expect(card.textContent).toContain(UNCONFIRMED);
    }
  });
  it('S33.4 Tasks label the entire stale stage disclosure as last observed', () => {
    for (const which of CASES) {
      const el = harness('jf-task-list', observe(which));
      el.filesOpen = true;
      const files = dom(el.render()).querySelector('[data-testid="tasks-files"]')!;
      if (unconfirmed(which)) expect(files.textContent, which).toContain('Last observed indexing and enrichment details');
      expect(files.textContent).toContain('Semantic vectors');
    }
  });
  it('S33.1 S33.4 mode pills cannot assert indexing from the parked AI runtime alone', () => {
    for (const which of CASES) {
      const state = observe(which);
      const el = harness('jf-status-deck', state);
      el.aiState = { ...state, statusLabel: 'Indexing', aiEngine: { kind: 'indexing', stability: { kind: 'settled' }, installFailure: null } };
      expect(el.inferenceChipReadout().label, which).toBe(unconfirmed(which) ? UNCONFIRMED
        : which === 'energy' ? 'Enrichment deferred' : which === 'chat' ? 'Search is ready — still improving' : 'Enrichment pending');
    }
  });
  it('S33.1 S33.4 Search composer and chat-off explanations use observed indexing activity and scoped reasons', () => {
    for (const which of CASES) {
      const state = observe(which, 4);
      // Healthy chat with a zero-document corpus exercises the RAG indexing explanation.
      state.capabilities = { ...state.capabilities, chat: true };
      state.index = { ...state.index, documentCount: known(0), pendingJobs: known(4) };
      const available = projectAvailability('documents', state);
      expect(available.kind).toBe('unavailable');
      if (available.kind === 'unavailable') expect(available.reason, which).toContain(which === 'Engine gone' ? 'connection to the search backend was lost' : expectedReason(which));
      // A GPU-yield chat reason is a runtime mode, not evidence that enrichment is running.
      state.capabilities = { ...state.capabilities, chat: false };
      state.chatFailureReason = 'inference.gpu_yielded_to_indexing';
      const chat = projectAvailability('agent', state);
      if (chat.kind === 'unavailable') expect(chat.reason, which).toContain(which === 'Engine gone' ? 'connection to the search backend was lost' : expectedReason(which));
    }
  });
  it('S33.4 Search v3 diagnostic causes cannot assert stale indexing activity', () => {
    for (const which of CASES) {
      const state = observe(which);
      state.verdict = { kind: 'degraded', severity: 'warn', reasons: ['index.throughput_stalled'] };
      const banner = projectSv3Degradation(state);
      expect(banner).not.toBeNull();
      if (unconfirmed(which)) expect(banner!.causes.map(cause => cause.wording).join(' '), which).toContain(UNCONFIRMED);
    }
  });

  it('S33.1 S33.4 a parked indexing runtime with no observed work does not promise active indexing to the Search composer', () => {
    const state = observe('chat', 0, 0);
    state.capabilities = { ...state.capabilities, chat: true };
    state.index = { ...state.index, documentCount: known(0) };
    const result = projectAvailability('documents', state);
    expect(result.kind).toBe('unavailable');
    if (result.kind === 'unavailable') {
      expect(result.transient).toBeFalsy();
      expect(result.reason).not.toContain('Indexing in progress');
      expect(result.reason).toContain('documents');
    }
  });

  it('S33.5 an unfinished rebuild never presents a completion figure when the old generation count is reached', () => {
    const state = observe('chat', 4, 80, true);
    state.status!.worker!.migration!.buildingIndexedDocuments = 100;
    const bar = dom(harness('jf-health-surface', state).renderRebuildProgress()).querySelector('[role="progressbar"]')!;
    expect(Number(bar.getAttribute('aria-valuenow'))).toBeLessThan(100);
  });

  it('S33.1 S33.4 S33.6 declared liveness readouts share indexing confirmation and hold wording', () => {
    for (const which of CASES) {
      const state = observe(which);
      const el = harness('jf-liveness-readout', state);
      el.aiState = { ...state, statusLabel: 'Indexing', statusTone: 'success',
        aiEngine: { kind: 'indexing', stability: { kind: 'settled' }, installFailure: null } };
      const readout = dom(el.render());
      expect(readout.textContent, which).toContain(expectedReason(which));
      if (unconfirmed(which)) expect(readout.querySelector('.dot')?.getAttribute('data-tone')).toBe('idle');
    }
  });

  it('S33.6 the deck has one indexing announcement even while the AI runtime is in indexing mode', () => {
    const state = observe('energy');
    const el = harness('jf-status-deck', state);
    el.aiState = { ...state, aiEngine: { kind: 'indexing', stability: { kind: 'settled' }, installFailure: null } };
    const deck = dom(el.render());
    expect(deck.querySelector('[data-testid="indexing-announcer"]')?.textContent).toContain(expectedReason('energy'));
    expect(deck.querySelector('[data-testid="ai-engine-announcer"]')?.textContent?.trim() ?? '').toBe('');
  });

  it('S33.4 Brain compatibility diagnostics qualify retained indexing readiness', () => {
    for (const which of CASES) for (const reasons of [['index.blocked_legacy'], []]) {
      const state = observe(which);
      state.status!.embedding = { compatState: 'BLOCKED_LEGACY' } as StatusSnapshot['embedding'];
      state.verdict = { kind: 'degraded', severity: 'warn', reasons };
      const el = harness('jf-brain-surface', state);
      const callout = dom(el.renderCompatibilityCallouts());
      expect(callout.textContent).toContain(reasons.length ? 'built before semantic search' : 'Rebuild the index');
      if (unconfirmed(which)) expect(callout.textContent, which).toContain(UNCONFIRMED);
    }
  });

  it('S33.4 shipped Search banner qualifies index diagnostics on the real renderer', () => {
    for (const which of CASES) {
      const state = observe(which);
      state.verdict = { kind: 'degraded', severity: 'warn', reasons: ['index.throughput_stalled'] };
      const el = harness('jf-unified-chat-view', state);
      const banner = dom(el.renderDegradationBanner());
      if (unconfirmed(which)) expect(banner.textContent, which).toContain(UNCONFIRMED);
      else expect(banner.textContent, which).toContain('Semantic search degraded');
    }
  });

});
