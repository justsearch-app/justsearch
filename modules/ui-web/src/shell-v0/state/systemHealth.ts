// SPDX-License-Identifier: Apache-2.0
/** Health reads the status controller's observations; this module owns no requests or persistence. */
import type { AiState } from './aiStateStore.js';
import { folderAttention, folderStatus } from './folderStatus.js';
import type { IndexedRootsObservation } from './indexedRoots.js';
import type { DurableStatusOperation } from './frameStatus.js';
import type { PresenceRun } from '../controllers/AgentSessionController.js';
import { selectIndexingProgress } from './indexingProgress.js';
import { presentVerdict } from './verdict.js';
import { presentAiEngineVerdict } from './aiVerdict.js';

// Two expected head reads; other REST sources get their cadence plus their read bound.
export const HEAD_HEALTH_MAX_AGE_MS = 20_000;
export const REMOTE_HEALTH_MAX_AGE_MS = 15_000;
const AI_HEALTH_MAX_AGE_MS = 10_000;
export function healthObservationCurrent(at: number | null | undefined, now: number, bound: number): boolean {
  return at != null && Number.isFinite(at) && at <= now && now - at <= bound;
}
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
  const headCurrent = !!ai?.status && healthObservationCurrent(input.statusAtMs, now, HEAD_HEALTH_MAX_AGE_MS);
  const progress = selectIndexingProgress(ai?.status ?? null, ai?.indexingStatusLive ?? false,
    ai?.episodeMaxPendingJobs ?? 0, ai?.enrichSettleSamples ?? []);
  const verdict = ai ? presentVerdict(ai.verdict) : null;
  const workerCurrent = !!ai?.indexingStatusLive && progress.activity.confirmed;
  const runtimeCurrent = !!ai?.runtimeObservationLive && healthObservationCurrent(ai.runtimeObservedAtMs, now, AI_HEALTH_MAX_AGE_MS);
  const installCurrent = !!ai?.installObservationLive && healthObservationCurrent(ai.installObservedAtMs, now, AI_HEALTH_MAX_AGE_MS);
  const row = (id: string, label: string, status: string, destination: string, action: string): HealthRow =>
    ({ id, label, status, destination, action });
  const engine = !headCurrent ? 'Engine status unconfirmed' : !ai?.connection.reachable ? 'Engine unavailable'
    : ai.status?.status === 'ok' || ai.status?.readiness?.engineComponents?.['api']?.state === 'READY' ? '' : 'Engine status unconfirmed';
  const rootsCurrent = roots.confirmed && healthObservationCurrent(roots.receivedAtMs, now, REMOTE_HEALTH_MAX_AGE_MS);
  const folders = roots.roots.map(root => {
    const state = folderStatus(root, { relativeTime: '', verifiedRelativeTime: '',
      provisional: ai?.stability.kind === 'provisional', enrichmentStages: progress.stages,
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
  const index = !workerCurrent ? (progress.activity.confirmed ? 'Index status unconfirmed' : progress.activity.summary) : ai?.verdict.kind !== 'operational'
    ? [verdict?.headline, verdict?.body].filter(Boolean).join(' — ')
    : !input.recoveryCurrent ? 'Recovery conditions unconfirmed' : input.recoveryCount > 0 ? 'Recovery actions available'
    : ai?.status?.worker?.core?.indexHealthy !== true ? 'Index health unconfirmed' : '';
  const model = !headCurrent || !workerCurrent || !runtimeCurrent || !installCurrent ? 'AI-model status unconfirmed'
    : ai?.chatFailureReason ? presentAiEngineVerdict(ai.aiEngine).body
    : ai?.runtimeStatus?.onnxFeatures?.some(feature => feature.status === 'failed') ? 'An AI model failed — open AI Brain'
    : ai?.chatDisabledBySpec && !['installing', 'install_failed', 'starting', 'paused'].includes(ai.aiEngine.kind) ? ''
    : ai?.aiEngine.kind === 'online' ? '' : ai ? presentAiEngineVerdict(ai.aiEngine).headline : 'AI-model status unconfirmed';
  const features = ai?.runtimeStatus?.onnxFeatures?.filter(feature => feature.modelActive) ?? [];
  const active = ai?.runtimeStatus?.active;
  const cpuLoaded = (features.length > 0 || !!active?.modelPath)
    && features.every(feature => feature.executionProvider === 'cpu' && !feature.gpuFallback)
    && (!active?.modelPath || active.gpuLayers === 0);
  const gpuFailed = ai?.status?.gpu?.available === true && ai.status.gpu.cudaFunctional === false
    || features.some(feature => feature.gpuFallback);
  const gpu = !headCurrent ? 'Graphics-card status unconfirmed' : gpuFailed ? 'Graphics acceleration needs attention'
    : ai?.status?.gpu?.available === true && ai.status.gpu.cudaFunctional === true ? ''
    : runtimeCurrent && cpuLoaded ? '' : 'Graphics-card status unconfirmed';
  const operations = unresolvedHealthOperations(remote.operations, input.seenCursor);
  /* Terminal receipts are immutable; acknowledgement retires a failed receipt as a current problem. */
  const operationWords = operations.map(operation => !operation.fresh || !operation.outcome || ['unknown', 'expired'].includes(operation.outcome.state)
    ? `${operation.label}: outcome unconfirmed` : operation.outcome.phase === 'awaiting_acceptance'
      ? `${operation.label}: review before continuing` : `${operation.label}: ${operation.outcome.state}`).join('; ');
  const presenceCurrent = remote.presenceLive && healthObservationCurrent(remote.presenceAtMs, now, REMOTE_HEALTH_MAX_AGE_MS);
  const runs = remote.presence.filter(run => !['DONE', 'ERROR'].includes(run.state));
  // Known work and holds keep their shared explanation (SH8); only an unconfirmed idle stays unconfirmed.
  const indexingWork = workerCurrent && (progress.pause || !['ready', 'idle'].includes(progress.phase))
    ? [progress.activity.summary, progress.activity.detail].filter(Boolean).join(' — ') : '';
  const work = operationWords || input.activeTasks.join('; ') || (runs.length ? 'Background agent working' : '') || indexingWork
    || (!workerCurrent || !runtimeCurrent || !installCurrent || !presenceCurrent ? 'Background-work status unconfirmed'
      : ai?.installStatus?.state === 'running' ? 'Installing AI models'
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
    return !operations.some(later => operation.operationId !== undefined && later.operationId === operation.operationId && later.outcome?.state === 'complete'
      && (later.outcome.completedAt ?? 0) > ended);
  });
}
