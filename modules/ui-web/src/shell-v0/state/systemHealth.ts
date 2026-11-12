// SPDX-License-Identifier: Apache-2.0
/** Health reads the status controller's observations; this module owns no requests or persistence. */
import type { AiState } from './aiStateStore.js';
import { folderAttention, folderStatus } from './folderStatus.js';
import type { IndexedRootsObservation } from './indexedRoots.js';
import type { DurableStatusOperation } from './frameStatus.js';
import type { PresenceRun } from '../controllers/AgentSessionController.js';
import { selectIndexingProgress, type IndexingProgress } from './indexingProgress.js';
import { presentVerdict } from './verdict.js';
import { presentAiEngineVerdict } from './aiVerdict.js';

// Two expected head reads; other REST sources get their cadence plus their read bound.
export const HEAD_HEALTH_MAX_AGE_MS = 20_000;
export const REMOTE_HEALTH_MAX_AGE_MS = 15_000;
const AI_HEALTH_MAX_AGE_MS = 10_000;
/**
 * The one currency rule for Health's facts: a source's facts are current while its last answer is
 * within the source's bound and no later read of it failed definitively (refused, aborted, an error
 * answer). Expiry stays the fallback when reads simply stop answering.
 */
export function healthObservationCurrent(at: number | null | undefined, now: number, bound: number, readFailed = false): boolean {
  return !readFailed && at != null && Number.isFinite(at) && at <= now && now - at <= bound;
}
/** The status read's facts (Engine, graphics card, memory), and the worker sample it carries (folders, index, counts). */
export interface StatusCurrency {
  readonly head: boolean;
  readonly worker: boolean;
}
export function statusCurrency(ai: AiState | null, statusAtMs: number | null, now: number): StatusCurrency {
  const head = !!ai?.status && healthObservationCurrent(statusAtMs, now, HEAD_HEALTH_MAX_AGE_MS, ai.statusReadFailed);
  return { head, worker: head && ai!.indexingStatusLive };
}
/** The shared indexing projection over the status Health holds, confirmed only while that status is current. */
export function healthIndexingProgress(ai: AiState | null, currency: StatusCurrency): IndexingProgress {
  return selectIndexingProgress(ai?.status ?? null, currency.worker, ai?.episodeMaxPendingJobs ?? 0, ai?.enrichSettleSamples ?? []);
}
// Read-freshness causes describe Health's reads, not the index: they never stand as the index's or a folder's state.
const READ_CAUSES: readonly string[] = ['updating', 'channel-stale', 'initial-load'];
export interface HealthRemoteObservations {
  readonly presence: readonly PresenceRun[];
  readonly presenceAtMs: number | null;
  readonly presenceLive: boolean;
  readonly operations: readonly DurableStatusOperation[];
}
const empty = (): HealthRemoteObservations => ({ presence: [], presenceAtMs: null, presenceLive: false, operations: [] });
let remote = empty();
const listeners = new Set<() => void>();
export function getHealthRemoteObservations(): HealthRemoteObservations { return remote; }
export function subscribeHealthObservations(listener: () => void): () => void {
  listeners.add(listener); return () => listeners.delete(listener);
}
/** Called only by the always-mounted status controller. */
export function publishHealthObservations(value: HealthRemoteObservations | null): void {
  remote = value ?? empty();
  for (const listener of listeners) listener();
}
export interface HealthRow {
  readonly id: string;
  readonly label: string;
  /** Empty only for a current satisfactory assessment; never an interactive blank cell. */
  readonly status: string;
  readonly destination: string;
  readonly action: string;
}
export interface HealthInput {
  readonly ai: AiState | null;
  readonly statusAtMs: number | null;
  readonly now: number;
  readonly roots: IndexedRootsObservation;
  readonly remote: HealthRemoteObservations;
  readonly recoveryCurrent: boolean;
  readonly recoveryCount: number;
  readonly seenCursor: string;
  readonly activeTasks: readonly string[];
}
export function projectSystemHealth(input: HealthInput): HealthRow[] {
  const { ai, now, roots, remote } = input;
  const currency = statusCurrency(ai, input.statusAtMs, now);
  const headCurrent = currency.head;
  const progress = healthIndexingProgress(ai, currency);
  const verdict = ai ? presentVerdict(ai.verdict) : null;
  const workerCurrent = progress.activity.confirmed;
  const readsBehind = ai?.stability.kind === 'provisional' && READ_CAUSES.includes(ai.stability.cause);
  const indexProvisional = workerCurrent && ai?.stability.kind === 'provisional' && !readsBehind;
  const runtimeCurrent = !!ai && healthObservationCurrent(ai.runtimeObservedAtMs, now, AI_HEALTH_MAX_AGE_MS, !ai.runtimeObservationLive);
  const installCurrent = !!ai && healthObservationCurrent(ai.installObservedAtMs, now, AI_HEALTH_MAX_AGE_MS, !ai.installObservationLive);
  const row = (id: string, label: string, status: string, destination: string, action: string): HealthRow =>
    ({ id, label, status, destination, action });
  const engine = !headCurrent ? 'Engine status unconfirmed' : !ai?.connection.reachable ? 'Engine unavailable'
    : ai.status?.status === 'ok' || ai.status?.readiness?.engineComponents?.['api']?.state === 'READY' ? '' : 'Engine status unconfirmed';
  const rootsCurrent = healthObservationCurrent(roots.receivedAtMs, now, REMOTE_HEALTH_MAX_AGE_MS, !roots.confirmed);
  const folders = roots.roots.map(root => {
    const state = folderStatus(root, { relativeTime: '', verifiedRelativeTime: '',
      provisional: indexProvisional, enrichmentStages: progress.stages,
      enrichmentPending: progress.enrichingPending > 0, enrichmentBlocked: progress.phase === 'blocked',
      enrichmentBlockedDetail: progress.phase === 'blocked' ? progress.activity.detail : null,
      retryWaiting: progress.phase === 'waiting', indexingPaused: progress.pause?.state === 'paused',
      indexingPauseReason: progress.pause?.reason, indexingPauseReasons: progress.pause?.reasons,
      lastKnownFailed: roots.lastKnownFailed?.[root.pathHash ?? ''] });
    const problems = folderAttention(root, state, roots.paths[root.pathHash ?? ''] ?? 'A watched folder');
    return problems.map(problem => problem.title).join('; ') ||
      (['ready', 'empty'].includes(state.state) ? '' : state.metaText);
  }).filter(Boolean).join('; ');
  const folderWords = !rootsCurrent || !workerCurrent ? `${folders ? `Last observed: ${folders}. ` : ''}Folder status unconfirmed` : folders;
  // The shared indexing projection's own wording when its observation is unconfirmed.
  // A transition that is only Health's reads falling behind is no index state.
  const index = !workerCurrent ? progress.activity.summary : ai?.verdict.kind === 'transitioning' && readsBehind ? 'Index status unconfirmed' : ai?.verdict.kind !== 'operational'
    ? [verdict?.headline, verdict?.body].filter(Boolean).join(' — ')
    : !input.recoveryCurrent ? 'Recovery conditions unconfirmed' : input.recoveryCount > 0 ? 'Recovery actions available'
    : ai?.status?.worker?.core?.indexHealthy !== true ? 'Index health unconfirmed' : '';
  const model = !headCurrent || !workerCurrent || !runtimeCurrent || !installCurrent ? 'AI-model status unconfirmed'
    : ai?.chatFailureReason ? presentAiEngineVerdict(ai.aiEngine).body
    : ai?.aiEngine.failedModels?.length ? 'An AI model failed — open AI Brain'
    : ai?.chatDisabledBySpec && !['installing', 'install_failed', 'starting', 'paused'].includes(ai.aiEngine.kind) ? ''
    : ai?.aiEngine.kind === 'online' ? '' : ai ? presentAiEngineVerdict(ai.aiEngine).headline : 'AI-model status unconfirmed';
  const placement = ai?.aiEngine.activeModelPlacement;
  const active = ai?.runtimeStatus?.active;
  const cpuLoaded = (!!placement || !!active?.modelPath) && (placement?.cpuOnly ?? true)
    && (!active?.modelPath || active.gpuLayers === 0);
  const gpuFailed = ai?.status?.gpu?.available === true && ai.status.gpu.cudaFunctional === false
    || placement?.gpuFallback === true;
  const gpu = !headCurrent ? 'Graphics-card status unconfirmed' : gpuFailed ? 'Graphics acceleration needs attention'
    : ai?.status?.gpu?.available === true && ai.status.gpu.cudaFunctional === true ? ''
    : runtimeCurrent && cpuLoaded ? '' : 'Graphics-card status unconfirmed';
  const operations = unresolvedHealthOperations(remote.operations, input.seenCursor);
  /* Terminal receipts are immutable; acknowledgement retires a failed receipt as a current problem. */
  const operationWords = operations.map(operation => !operation.fresh || !operation.outcome || ['unknown', 'expired'].includes(operation.outcome.state)
    ? `${operation.label}: outcome unconfirmed` : operation.outcome.phase === 'awaiting_acceptance'
      ? `${operation.label}: review before continuing` : `${operation.label}: ${operation.outcome.state}`).join('; ');
  const presenceCurrent = healthObservationCurrent(remote.presenceAtMs, now, REMOTE_HEALTH_MAX_AGE_MS, !remote.presenceLive);
  const runs = remote.presence.filter(run => !['DONE', 'ERROR'].includes(run.state));
  // Known work and holds keep their shared explanation (SH8); only an unconfirmed idle stays unconfirmed.
  const indexingWork = workerCurrent && (progress.pause || !['ready', 'idle'].includes(progress.phase))
    ? [progress.activity.summary, progress.activity.detail].filter(Boolean).join(' — ') : '';
  const work = operationWords || input.activeTasks.join('; ') || (runs.length ? 'Background agent working' : '') || indexingWork
    || (!workerCurrent || !runtimeCurrent || !installCurrent || !presenceCurrent ? 'Background-work status unconfirmed'
      : ai?.aiEngine.kind === 'installing' ? 'Installing AI models'
      : ai?.runtimeStatus?.activation?.state === 'running' ? 'Starting AI models' : '');
  return [row('engine', 'Engine', engine, 'core.logs-surface', 'Open Logs'),
    row('folders', 'Folders', folderWords, 'core.library-surface', 'Open Library'),
    row('index', 'Index', index, 'core.library-surface', 'Open Library'),
    row('models', 'AI models', model, 'core.brain-surface', 'Open AI Brain'),
    row('gpu', 'Graphics card', gpu, 'core.brain-surface', 'Open AI Brain'),
    row('work', 'Background work', work, 'core.activity-surface', 'Recent activity')];
}
export function unresolvedHealthOperations(operations: readonly DurableStatusOperation[], seenCursor: string): readonly DurableStatusOperation[] {
  return operations.filter(operation => {
    const outcome = operation.outcome;
    if (outcome?.state === 'complete' && outcome.phase !== 'awaiting_acceptance') return false;
    if (outcome?.state !== 'failed') return true;
    const ended = outcome.completedAt ?? 0;
    if (ended > 0 && new Date(ended).toISOString() <= seenCursor) return false;
    return !failureSuperseded(operation, operations);
  });
}
/** A failed receipt that a later successful invocation of the same operation retired. */
export function failureSuperseded(operation: DurableStatusOperation, operations: readonly DurableStatusOperation[]): boolean {
  const ended = operation.outcome?.completedAt ?? 0;
  return operation.outcome?.state === 'failed' && operation.operationId !== undefined
    && operations.some(later => later.operationId === operation.operationId && later.outcome?.state === 'complete'
      && (later.outcome.completedAt ?? 0) > ended);
}
