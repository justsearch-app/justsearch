// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, type TemplateResult } from 'lit';
import '../views/LibrarySurface.js';
import '../views/search-v3/Sv3Composer.js';
import './StatusDeck.js';
import type { LibrarySurface } from '../views/LibrarySurface.js';
import type { Sv3Composer } from '../views/search-v3/Sv3Composer.js';
import { __feedForTest, __resetAiStateForTest, __tickClockForTest, getAiState, type AiState, type StatusSnapshot } from '../state/aiStateStore.js';
import { selectIndexingProgress } from '../state/indexingProgress.js';
import { projectAvailability } from '../state/availability.js';
import { known } from '../state/known.js';

type Case = 'fresh' | 'age' | 'worker stale' | 'indexing stale' | 'unavailable' | 'Engine gone' | 'energy';
const CASES: Case[] = ['fresh', 'age', 'worker stale', 'indexing stale', 'unavailable', 'Engine gone', 'fresh'];
const UNKNOWN = 'Indexing status unconfirmed';
function observe(which: Case, migrationState = 'IDLE'): AiState {
  __resetAiStateForTest();
  const now = Date.parse('2026-10-21T12:00:00Z');
  vi.setSystemTime(now);
  __feedForTest({
    status: {
      meta: { workerRpcAtMs: now, workerRpcStale: which === 'worker stale' },
      worker: {
        core: { indexState: which === 'unavailable' ? 'UNAVAILABLE' : 'INDEXING', pendingJobs: 4, indexedDocuments: 0, indexHealthy: true },
        migration: { migrationState, activeGenerationId: 'g1', buildingGenerationId: migrationState === 'IDLE' ? '' : 'g2' },
      },
      operating: { indexing: { state: which === 'energy' ? 'PAUSED' : 'ACTIVE', reasons: which === 'energy' ? ['energy_saver'] : [] },
        freshness: { staleAfterMs: 30_000, staleSources: which === 'indexing stale' ? ['indexing'] : [] } },
      readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] }, chat: { state: 'READY', reasonCodes: [] } } },
    } as unknown as StatusSnapshot,
    inference: { mode: 'online', available: true, activeModelId: 'test' } as AiState['inference'],
  });
  if (which === 'age' || which === 'Engine gone') {
    vi.setSystemTime(now + (which === 'age' ? 40_001 : 120_001));
    if (which === 'age') __feedForTest({ inference: { mode: 'online', available: true } as AiState['inference'] });
  }
  __tickClockForTest();
  return getAiState();
}
function library(state: AiState): LibrarySurface {
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.loading = false;
  el.provisional = state.stability.kind === 'provisional';
  const progress = selectIndexingProgress(state.status, state.indexingStatusLive, state.episodeMaxPendingJobs, state.enrichSettleSamples);
  el.indexingActivity = progress.activity;
  Object.assign(el, { indexProgress: progress });
  return el;
}
function dom(template: TemplateResult): HTMLDivElement {
  const output = document.createElement('div');
  render(template, output);
  return output;
}
beforeEach(() => { vi.useFakeTimers(); __resetAiStateForTest(); });
afterEach(async () => {
  document.body.replaceChildren();
  __resetAiStateForTest();
  await Promise.resolve();
  vi.useRealTimers();
});

describe('WP3.3 sixth verifier regressions', () => {
  it('S33.4 S33.6 Library empty roots qualify retained migration and recover on fresh observations', () => {
    for (const migration of ['MIGRATING', 'SWITCHING']) for (const which of CASES) {
      const text = dom(library(observe(which, migration)).render()).querySelector('.empty')!.textContent!;
      expect(text.includes(UNKNOWN), `${migration}: ${which}`).toBe(which !== 'fresh');
      expect(text.includes('your watched folders will reappear when it finishes'), `${migration}: ${which}`).toBe(which === 'fresh');
      expect(text).not.toContain('No watched folders');
    }
    expect(dom(library(observe('fresh')).render()).querySelector('h2')!.textContent).toBe('Add a folder to start searching');
  });

  it('S33.4 Library rebuild controls qualify retained build and activation observations', () => {
    for (const migration of ['MIGRATING', 'SWITCHING', 'AWAITING_ACCEPTANCE']) for (const which of CASES) {
      const el = library(observe(which, migration));
      // The same keys the subscription retains after receiving a build observation.
      Object.assign(el, { reindexCancelKey: 'g2', observedGapKey: migration === 'AWAITING_ACCEPTANCE' ? 'g2' : null,
        gapDecision: migration === 'AWAITING_ACCEPTANCE' ? { gaps: [{ unitId: 'a.pdf', reason: 'unreadable' }] } : null });
      const control = dom(el.render()).querySelector('.gap-decision')!;
      const title = control.querySelector('h3')!.textContent!;
      expect(title.includes(UNKNOWN), `${migration}: ${which}: headline`).toBe(which !== 'fresh');
      expect(control.textContent!.includes(UNKNOWN), `${migration}: ${which}`).toBe(which !== 'fresh');
      if (which !== 'fresh') {
        expect(control.textContent).not.toContain('New index is being built');
        expect(control.textContent).not.toContain('Your current index stays available');
        expect(control.textContent).not.toContain('activation requires your decision');
      } else expect(control.textContent).toContain(migration === 'AWAITING_ACCEPTANCE' ? 'activation requires your decision' : 'New index is being built');
      expect(control.querySelector('jf-button[label="Cancel rebuild"]')).not.toBeNull();
    }
  });

  it('S33.6 Search v3 Ask and deck announce indexing changes once and repeat polls silently', async () => {
    const composer = document.createElement('jf-sv3-composer') as Sv3Composer;
    composer.draft = 'Where is the report?';
    composer.tier = 'ask';
    const deck = document.createElement('jf-status-deck') as unknown as { aiState: AiState; render(): TemplateResult };
    const composerDom = document.createElement('div');
    const deckDom = document.createElement('div');
    document.body.append(composerDom, deckDom);
    const settle = async () => {
      await Promise.resolve();
      await Promise.all([...document.querySelectorAll('jf-system-notice')].map(n => (n as unknown as { updateComplete: Promise<unknown> }).updateComplete));
      await Promise.resolve();
    };
    const update = async (which: Case) => {
      const state = observe(which);
      state.capabilities = { ...state.capabilities, chat: true };
      state.index = { ...state.index, documentCount: known(0), pendingJobs: known(4) };
      const availability = projectAvailability('documents', state);
      expect(availability.kind).toBe('unavailable');
      composer.unavailableReason = availability.kind === 'unavailable' ? availability.reason : '';
      deck.aiState = state;
      render(composer.render(), composerDom);
      render(deck.render(), deckDom);
      await settle();
    };
    await update('fresh');
    const changed = new Set<Element>();
    const observer = new MutationObserver(records => {
      for (const record of records) {
        const el = record.target.nodeType === Node.ELEMENT_NODE ? record.target as Element : record.target.parentElement;
        const live = el?.closest('[aria-live="polite"], [role="status"]');
        if (live) changed.add(live);
      }
    });
    observer.observe(document.body, { subtree: true, childList: true, characterData: true });
    try {
      for (const which of ['indexing stale', 'fresh', 'energy', 'age', 'fresh'] as Case[]) {
        changed.clear();
        await update(which);
        expect(changed.size, which).toBe(1);
        const notice = composerDom.querySelector('#sv3-composer-notice')!;
        expect(notice.textContent!.trim()).toBe(composer.unavailableReason);
        // The refusal stays reachable when the user focuses Send.
        expect(composerDom.querySelector('[aria-describedby="sv3-composer-notice"]')).not.toBeNull();
        changed.clear();
        await update(which);
        expect(changed.size, `${which}: identical poll`).toBe(0);
      }
    } finally { observer.disconnect(); }
  });
});
