// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, type TemplateResult } from 'lit';
import '../views/BrowseSurface.js';
import '../views/BrainSurface.js';
import '../views/HealthSurface.js';
import '../views/search-v3/SearchV3View.js';
import './StatusDeck.js';
import './LivenessReadout.js';
import { IndexingOverlay } from './IndexingOverlay.js';
import { __feedForTest, __resetAiStateForTest, __tickClockForTest, getAiState, type AiState, type StatusSnapshot } from '../state/aiStateStore.js';
import { selectIndexingProgress } from '../state/indexingProgress.js';
import { readinessNotice, reasonFor } from '../state/readinessNotice.js';
import { projectSv3Degradation } from '../views/search-v3/sv3-degradation.js';
import { __resetPresentationForTest } from '../state/presentationRuntime.js';

const CASES = ['energy', 'game', 'external GPU', 'chat', 'age', 'worker stale', 'indexing stale', 'unavailable', 'Engine gone'] as const;
type Case = typeof CASES[number];
const UNKNOWN = 'Indexing status unconfirmed';
const isUnconfirmed = (which: Case) => !['energy', 'game', 'external GPU', 'chat'].includes(which);
const CODES = ['encoders.health.embedding_not_ready', 'encoders.health.embedding_probe_missing', 'encoders.health.semantic_stuck',
  'encoders.health.query_on_cpu', 'encoders.reloading', 'chunk_embedding.not_ready', 'chunk_embedding.in_progress'];
function observe(which: Case, jobs = 4, migrating = false): AiState {
  __resetAiStateForTest();
  const now = Date.parse('2026-10-20T19:00:00Z');
  vi.setSystemTime(now);
  const reasons = which === 'energy' ? ['energy_saver'] : which === 'game' ? ['game_mode']
    : which === 'external GPU' ? ['gpu_busy_external'] : which === 'chat' ? ['chat_generating'] : [];
  const status = {
    meta: { workerRpcAtMs: now, workerRpcStale: which === 'worker stale' },
    worker: {
      core: { indexState: which === 'unavailable' ? 'UNAVAILABLE' : jobs ? 'INDEXING' : 'IDLE', pendingJobs: jobs, indexHealthy: true },
      enrichment: { embeddingEnabled: true, embeddingDocCount: 100, embeddingPendingCount: 80, spladeEnabled: false, nerEnabled: false },
      migration: { migrationState: migrating ? 'MIGRATING' : 'IDLE', activeIndexedDocuments: 100, buildingIndexedDocuments: 60 },
    },
    operating: { indexing: { state: reasons.length ? 'PAUSED' : 'ACTIVE', reasons },
      freshness: { staleAfterMs: 30_000, staleSources: which === 'indexing stale' ? ['indexing'] : [] } },
    readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
  } as unknown as StatusSnapshot;
  __feedForTest({ status, inference: { mode: 'indexing', starting: false, embeddingQueueSize: 220, vduQueueSize: 99 } });
  if (which === 'age' || which === 'Engine gone') {
    vi.setSystemTime(now + (which === 'Engine gone' ? 120_001 : 40_001));
    if (which === 'age') __feedForTest({ inference: { mode: 'indexing', starting: false, embeddingQueueSize: 220, vduQueueSize: 99 } });
  }
  __tickClockForTest();
  return getAiState();
}
// Detached parents exercise production renderers without HTTP requests or subscriptions.
type Surface = {
  aiState: AiState; _unifiedAiState: AiState; status: StatusSnapshot; systemStatus: StatusSnapshot;
  inference: AiState['inference']; provisional: boolean; roots: unknown[];
  renderTree(): TemplateResult; renderRuntimeSection(): TemplateResult; renderStats(): TemplateResult;
  renderDegradationBanner(): TemplateResult; render(): TemplateResult; inferenceChipReadout(): { label: string };
};
function surface(tag: string, state: AiState): Surface {
  const el = document.createElement(tag) as unknown as Surface;
  el.aiState = state; el._unifiedAiState = state;
  if (tag !== 'jf-status-deck') el.status = state.status!;
  el.systemStatus = state.status!; el.inference = state.inference;
  el.provisional = state.stability.kind === 'provisional';
  return el;
}
function dom(tpl: TemplateResult): HTMLDivElement {
  const container = document.createElement('div'); render(tpl, container); return container;
}
function degraded(state: AiState, code: string): AiState {
  return { ...state, verdict: { kind: 'degraded', severity: 'warn', reasons: [code] } };
}
beforeEach(() => { vi.stubGlobal('fetch', vi.fn(async () => new Response('{}'))); vi.useFakeTimers(); __resetAiStateForTest(); __resetPresentationForTest(); });
afterEach(async () => {
  document.body.replaceChildren(); __resetAiStateForTest(); __resetPresentationForTest();
  await Promise.resolve(); vi.unstubAllGlobals(); vi.useRealTimers();
});

describe('WP3.3 independent inventory regressions', () => {
  it('S33.4 S33.6 encoder and passage readiness observations are qualified on mounted Search', async () => {
    for (const which of CASES) for (const code of CODES) {
      const state = degraded(observe(which), code);
      const activity = selectIndexingProgress(state.status, state.indexingStatusLive, state.episodeMaxPendingJobs, state.enrichSettleSamples).activity;
      const notice = readinessNotice(state.verdict, activity)!;
      const v3 = projectSv3Degradation(state)!;
      const banner = await searchBanner(state);
      for (const [name, text] of [['notice', notice.headline], ['v3', v3.headline], ['shipped', banner.textContent]]) {
        expect(text?.includes(UNKNOWN), `${which}: ${code}: ${name}`).toBe(isUnconfirmed(which));
      }
      expect(reasonFor(code, activity).wording.includes('last observed'), `${which}: ${code}`).toBe(isUnconfirmed(which));
      expect(reasonFor('inference.model_not_configured', activity).wording).toBe(reasonFor('inference.model_not_configured').wording);
    }
  });
  it('S33.4 S33.6 Browse empty roots never infer a current rebuild from unconfirmed migration', () => {
    for (const which of CASES) {
      const el = surface('jf-browse-surface', observe(which, 4, true)); el.roots = [];
      const text = dom(el.renderTree()).textContent!;
      expect(text.includes(UNKNOWN), which).toBe(isUnconfirmed(which));
      expect(text.includes('your watched folders will reappear when it finishes'), which).toBe(!isUnconfirmed(which));
      expect(text).not.toContain('No watched folders');
    }
  });
  it('S33.4 S33.6 migration mode pills qualify retained rebuilding while preserving fresh migration', () => {
    for (const which of CASES) {
      const state = observe(which, 4, true);
      expect(surface('jf-status-deck', state).inferenceChipReadout().label, which).toBe(['Engine gone', 'unavailable'].includes(which) ? state.statusLabel
        : isUnconfirmed(which) ? UNKNOWN : state.statusLabel);
    }
  });
  it('S33.4 S33.6 declared liveness readouts qualify retained migration and its accessible name', () => {
    for (const which of CASES) {
      const state = observe(which, 4, true); const readout = dom(surface('jf-liveness-readout', state).render());
      if (!['Engine gone', 'unavailable'].includes(which)) {
        expect(readout.textContent?.includes(UNKNOWN), which).toBe(isUnconfirmed(which));
        expect(readout.querySelector('[role="img"]')?.getAttribute('aria-label')?.includes(UNKNOWN), which).toBe(isUnconfirmed(which));
        if (isUnconfirmed(which)) expect(readout.querySelector('.dot')?.getAttribute('data-tone')).toBe('idle');
      } else expect(readout.textContent).toContain(state.statusLabel);
    }
  });
  it('S33.1 S33.4 overlay activity animation stops during deferred work and uncertainty, then resumes', () => {
    const style = document.createElement('style'); style.textContent = IndexingOverlay.styles.cssText; document.body.append(style);
    const host = document.createElement('jf-indexing-overlay-host');
    const container = document.createElement('div'); document.body.append(container);
    for (const which of [...CASES, 'chat'] as const) for (const jobs of [0, 4]) {
      const state = observe(which, jobs);
      Object.assign(host, { aiState: state });
      const hosted = dom((host as unknown as Surface).render());
      const el = hosted.querySelector('jf-indexing-overlay') as IndexingOverlay | null;
      if (which === 'Engine gone') {
        expect(el).toBeNull();
        continue;
      }
      expect(el, `${which}: ${jobs} files`).not.toBeNull();
      render(el!.render(), container);
      const animation = getComputedStyle(container.querySelector('.icon-box')!).animation;
      expect(animation.includes('spin'), `${which}: ${jobs} files`).toBe(!isUnconfirmed(which) && (jobs > 0 || which === 'chat'));
    }
  });
  it('S33.4 S33.6 migration announcements use one region and withdraw stale rebuild claims', async () => {
    const initial = observe('chat', 4, true);
    const deck = surface('jf-status-deck', initial);
    const output = dom(deck.render());
    document.body.append(output);
    await Promise.resolve();
    const before = [...output.querySelectorAll('jf-system-notice')].filter(n => n.textContent?.includes('Rebuilding'));
    expect(before.length).toBe(1);
    deck.aiState = observe('indexing stale', 4, true);
    render(deck.render(), output);
    await Promise.resolve();
    expect(output.querySelector('[data-testid="indexing-announcer"]')?.textContent).toContain(UNKNOWN);
    expect([...output.querySelectorAll('jf-system-notice')].some(n => n.textContent?.includes('Rebuilding'))).toBe(false);
  });
  it('S33.4 Brain runtime queues use worker counts and confirmation even with fresh inference contact', () => {
    for (const which of CASES) {
      const runtime = dom(surface('jf-brain-surface', observe(which)).renderRuntimeSection());
      expect(runtime.textContent, which).toContain(`embed queue: ${which === 'unavailable' ? '—' : '80'}`);
      expect(runtime.textContent, which).toContain(`VDU queue: ${which === 'unavailable' ? '—' : '0'}`);
      expect(runtime.textContent).not.toContain('220'); expect(runtime.textContent).not.toContain('99');
      expect(runtime.textContent?.includes(UNKNOWN), which).toBe(isUnconfirmed(which));
      if (isUnconfirmed(which) && which !== 'unavailable') expect(runtime.textContent).toContain('last observed');
    }
  });
  it('S33.4 Health fallback queue dot cannot animate an unconfirmed observation', () => {
    for (const which of CASES) {
      const el = surface('jf-health-surface', observe(which)); el.provisional = false;
      const stats = dom(el.renderStats());
      const queue = [...stats.querySelectorAll('.card')].find(c => c.querySelector('.card-label')?.textContent === 'Queue')!;
      expect(queue.querySelector('jf-status-dot')?.hasAttribute('live'), which).toBe(!isUnconfirmed(which));
      if (isUnconfirmed(which)) expect(queue.querySelector('jf-status-dot')?.getAttribute('tone')).toBe('neutral');
    }
  });
});

type MountedSearch = HTMLElement & { updateComplete: Promise<unknown>; requestUpdate(): void };
async function searchBanner(state: AiState): Promise<HTMLElement> {
  const view = document.createElement('jf-sv3-window') as MountedSearch;
  document.body.append(view); await view.updateComplete;
  Object.assign(view, { aiSnapshot: state }); view.requestUpdate(); await view.updateComplete;
  const composer = view.shadowRoot!.querySelector('jf-sv3-composer') as MountedSearch;
  expect(composer, 'positive mounted Search witness').not.toBeNull(); await composer.updateComplete;
  const banner = composer.shadowRoot!.querySelector<HTMLElement>('[data-testid="sv3-degradation"]');
  expect(banner).not.toBeNull();
  expect(banner!.querySelector('[aria-live], [role="status"], [role="alert"]')).toBeNull();
  view.remove(); return banner!;
}
