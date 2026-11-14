// SPDX-License-Identifier: Apache-2.0
/** One first-use projection. Unknown observations never become setup advice. */
import { getAiState, subscribeAiState, type AiState } from './aiStateStore.js';
import { isWorkerReportedIndex, selectIndexingProgress } from './indexingProgress.js';
import { getWalkthroughProgress, mutateDocument, subscribeWalkthroughProgress, type WalkthroughProgress } from './UserStateDocument.js';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';
import { listWalkthroughs, onWalkthroughCatalogChange } from '../commands/WalkthroughRegistry.js';

export const GETTING_STARTED_ID = 'core.getting-started';
export const REOPENED_MARKER = 'reopened-from-help';
export interface FirstUseFacts {
  readonly roots: number | null;
  readonly searchable: number | null;
  readonly waiting: number | null;
  readonly reading: boolean;
  readonly aiActive: boolean;
}
function validProgress(value: unknown): WalkthroughProgress | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const p = value as Partial<WalkthroughProgress>;
  return Number.isInteger(p.activeStepIndex) && p.activeStepIndex! >= 0 && Array.isArray(p.completedStepIds)
    && p.completedStepIds.every(s => typeof s === 'string') && typeof p.dismissed === 'boolean'
    ? p as WalkthroughProgress : undefined;
}
export function projectFirstUse(facts: FirstUseFacts, saved?: unknown, _legacy?: unknown, suppressed = false, _pluginPending = false) {
  const progress = validProgress(saved);
  const own = facts.searchable;
  const count = own !== null && Number.isSafeInteger(own) && own >= 0 ? own : null;
  const done = [(count ?? 0) > 0,
    progress?.completedStepIds.includes('try-search') ?? false, facts.aiActive] as const;
  const reopened = progress?.completedStepIds.includes(REOPENED_MARKER) ?? false;
  const known = facts.roots !== null && count !== null;
  const coreVisible = false;
  const visible = false;
  const reading = facts.waiting !== null && facts.waiting > 0
    ? `${facts.waiting.toLocaleString()} waiting to be read` : facts.reading ? 'still reading' : '';
  return { ...facts, searchable: count, done, reopened, visible, coreVisible, known,
    currentStep: done[0] ? done[1] ? 3 : 2 : 1,
    folderUnderWay: (facts.roots ?? 0) > 0 && count === 0 && (facts.reading || (facts.waiting ?? 0) > 0),
    empty: !suppressed && known && facts.roots === 0 && count === 0,
    completed: done.filter(Boolean).length,
    folderNeedsAttention: (facts.roots ?? 0) > 0 && count === 0 && !facts.reading && !(facts.waiting ?? 0),
    progress: count === null ? null : `${count.toLocaleString()} ${count === 1 ? 'file' : 'files'} searchable so far${reading ? ` · ${reading}` : ''}`,
  };
}
export type FirstUse = ReturnType<typeof projectFirstUse>;

let roots: readonly IndexedRootView[] | null = null;
let paths: ReadonlyMap<string, string> = new Map();
const listeners = new Set<() => void>();
/** Fed by the existing frame observation; no new roots poller. Paths are retained on worker loss. */
export function setFirstUseRoots(value: readonly IndexedRootView[] | null, resolved: ReadonlyMap<string, string>): void {
  roots = value; paths = new Map(resolved);
  for (const listener of listeners) listener();
}
export function firstUseWatchedPaths(): readonly string[] | null {
  if (roots === null || roots.some(root => !root.pathHash || !paths.has(root.pathHash))) return null;
  return roots.map(root => paths.get(root.pathHash!)!);
}
export function firstUseFacts(ai: AiState): FirstUseFacts {
  const measured = ai.status?.worker?.core?.ownSearchableDocuments;
  const count = ai.indexingStatusLive && isWorkerReportedIndex(ai.status)
    && !['stale', 'unknown'].includes(ai.status?.worker?.countObservations?.searchable?.state ?? 'fresh')
    ? measured ?? null : null;
  const progress = selectIndexingProgress(ai.status, ai.indexingStatusLive, ai.episodeMaxPendingJobs, ai.enrichSettleSamples);
  const queueKnown = ai.indexingStatusLive && isWorkerReportedIndex(ai.status)
    && typeof ai.status?.worker?.core?.pendingJobs === 'number';
  const readingObserved = ai.indexingStatusLive && isWorkerReportedIndex(ai.status)
    && ai.status?.meta?.workerRpcStale !== true
    && !ai.status?.operating?.freshness?.staleSources?.includes('indexing');
  return { roots: roots === null ? null : roots.length,
    searchable: count !== null && Number.isInteger(count) && count >= 0 ? count : null,
    waiting: queueKnown ? progress.jobsQueued : null,
    // A scan can still be finding files while its observed queue is empty or absent.
    reading: readingObserved && !progress.pause
      && (progress.phase === 'indexing' || ai.status?.worker?.core?.indexState === 'INDEXING'),
    aiActive: ai.snapshotLive && ai.runtime.mode === 'online' && ai.capabilities.chat,
  };
}
export function getFirstUse(): FirstUse {
  const suppressed = typeof location !== 'undefined' && new URLSearchParams(location.search).get('demoMode') === '1';
  const pending = listWalkthroughs().some(w => {
    const p = getWalkthroughProgress(w.id);
    return !p?.dismissed && (p?.activeStepIndex ?? 0) < w.steps.length;
  });
  return projectFirstUse(firstUseFacts(getAiState()), getWalkthroughProgress(GETTING_STARTED_ID), getWalkthroughProgress('welcome'), suppressed, pending);
}
export function subscribeFirstUse(listener: () => void): () => void {
  listeners.add(listener);
  const stopAi = subscribeAiState(listener);
  const stopProgress = subscribeWalkthroughProgress(listener);
  const stopCatalog = onWalkthroughCatalogChange(listener);
  return () => { listeners.delete(listener); stopAi(); stopProgress(); stopCatalog(); };
}
export function hideGettingStarted(): void {
  mutateDocument(doc => { const prev = doc.walkthroughState?.[GETTING_STARTED_ID];
    return { ...doc, walkthroughState: { ...doc.walkthroughState, [GETTING_STARTED_ID]: {
      activeStepIndex: 0, completedStepIds: (prev?.completedStepIds ?? []).filter(id => id !== REOPENED_MARKER), dismissed: true,
    } } };
  });
}
export function reopenGettingStarted(): void {
  mutateDocument(doc => { const ids = doc.walkthroughState?.[GETTING_STARTED_ID]?.completedStepIds ?? [];
    return { ...doc, walkthroughState: { ...doc.walkthroughState, [GETTING_STARTED_ID]: {
      activeStepIndex: 0, completedStepIds: [...new Set([...ids, REOPENED_MARKER])], dismissed: false,
    } } };
  });
}
