// SPDX-License-Identifier: Apache-2.0
/** Read-only frame projection. Urgency, freshness, acknowledgement and lifecycle are independent. */
import type { AiState } from './aiStateStore.js';
import { selectIndexingProgress, type IndexingProgress } from './indexingProgress.js';
import { isSearchUnavailableCause, isReindexCause } from './readinessNotice.js';
import { formatBytes, formatCount } from '../display/format.js';
import { indexingCountsLine, indexingCountsLabel, enrichingCountsLine, enrichingCountsLabel } from './statusProgress.js';
import { indexingTaskStatus } from './indexingProgress.js';
import { verdictHeadline } from './verdict.js';
import type { AdvisoryRecord } from '../components/advisory/AdvisoryStore.js';
import { advisoryClassChrome, healthAdvisoryReasonBody } from '../components/advisory/AdvisoryClassChrome.js';
import { interpolateMessage } from '../../i18n/resourceCatalog.js';
import { present } from '../display/present.js';
import type { Task } from '../substrates/tasks/index.js';
import type { PendingEffect } from '../substrates/pending-effects/index.js';
import type { AppUpdateStatus } from './appUpdateState.js';
import { folderAttention, folderStatus, rootCoverage } from './folderStatus.js';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';
import type { PresenceRun } from '../controllers/AgentSessionController.js';
import type { UnifiedActionEntry } from '../operations/ActionLedgerClient.js';
import type { OperationOutcomeView } from '../../api/generated/schema-types/index.js';

const RECONNECT_GRACE_MS = 10_000; // One complete status-poll interval.
export const FINISHED_DWELL_MS = 6_000;
const FILE_ACTIVITY_ID = 'indexing-files';
export type StatusSection = 'needs' | 'problems' | 'running' | 'earlier';
export interface StatusAction { readonly label: string; readonly kind: 'navigate' | 'advisory' | 'proposal' | 'reject-proposal' | 'authorization' | 'consent' | 'cancel-task' | 'plugin-copy' | 'dismiss-plugin' | 'install-toggle' | 'background-runs'; readonly target: string; }
export interface StatusFact {
  readonly id: string;
  readonly section: StatusSection;
  readonly title: string;
  readonly detail?: string;
  readonly occurredAt?: string;
  readonly unread?: boolean;
  readonly stale?: boolean;
  readonly percent?: number | null;
  readonly actions?: readonly StatusAction[];
  readonly indexing?: boolean;
  readonly indeterminate?: boolean;
  readonly progressLabel?: string;
  readonly details?: readonly StatusFact[];
  readonly confidence?: number;
  readonly diagnostics?: string;
  /** Consequence of not answering, relative to now ("Denied automatically in 4 min"). */
  readonly deadline?: string;
  /** How a finished or historical item ended; drawn as an icon plus words, never colour alone. */
  readonly tone?: 'success' | 'failure' | 'warning' | 'neutral';
}
interface PendingStatusAuthorization {
  readonly pendingId: string; readonly operationId: string; readonly argsSummary?: string;
  readonly requestedBy?: string; readonly expiresAt?: string;
}
export interface DurableStatusOperation {
  readonly id: string; readonly label: string; readonly outcome: OperationOutcomeView | null;
  readonly invocationTaskId?: string;
  readonly fresh?: boolean;
}
interface FrameStatusInput {
  readonly ai: AiState | null;
  readonly now: number;
  readonly contactLostAt: number | null;
  readonly advisories?: readonly AdvisoryRecord[];
  readonly proposals?: readonly PendingEffect[];
  readonly authorizations?: readonly PendingStatusAuthorization[];
  readonly consents?: readonly { readonly id: string; readonly pluginId: string; readonly capability: string }[];
  readonly tasks?: readonly Task[];
  readonly operations?: readonly DurableStatusOperation[];
  readonly update?: AppUpdateStatus | null;
  readonly conditions?: readonly StatusFact[];
  readonly roots?: readonly IndexedRootView[];
  /** Resolved folder paths by path hash (the observation carries hashes only). */
  readonly rootPaths?: ReadonlyMap<string, string>;
  readonly rootsLive?: boolean;
  readonly activity?: readonly UnifiedActionEntry[];
  readonly presence?: { readonly runs: readonly PresenceRun[]; readonly live: boolean };
  readonly finished?: { readonly title: string; readonly at: number } | null;
}
interface StatusPillState {
  readonly kind: 'unreachable' | 'limited' | 'needs' | 'problems' | 'working' | 'finished' | 'calm' | 'unknown';
  readonly label: string;
  readonly stale: boolean;
  readonly percent?: number | null;
  /** Needs-you count, shown as the pill's badge. */
  readonly count?: number;
}
export interface FrameStatus {
  readonly pill: StatusPillState;
  readonly sections: Readonly<Record<StatusSection, readonly StatusFact[]>>;
  readonly progress: IndexingProgress;
}
const go = (label: string, target: string): StatusAction => ({ label, target, kind: 'navigate' });
const TASK_STATUS_WORDS: Readonly<Record<string, string>> = {
  queued: 'Queued', running: 'Running', succeeded: 'Completed', failed: 'Failed', cancelled: 'Cancelled',
};
const OPERATION_STATE_WORDS: Readonly<Record<string, string>> = {
  accepted: 'Accepted · waiting to start', running: 'Running', complete: 'Completed', failed: 'Failed',
  unknown: 'Outcome not known yet · checking', expired: 'Outcome no longer available',
};

/** The last segment of a folder path, either separator, ignoring a trailing one ("/home/me/Documents/" → "Documents"). */
export function folderName(path: string): string {
  const trimmed = path.replace(/[\\/]+$/, '');
  return trimmed.split(/[\\/]/).pop() || path;
}

/** An unanswered agent request expires unusable (the engine refuses stale approvals): name that consequence. */
export function authorizationDeadline(expiresAt: string, now: number): string | undefined {
  const ms = Date.parse(expiresAt) - now;
  if (!Number.isFinite(ms)) return undefined;
  const minutes = Math.ceil(ms / 60_000);
  return `Denied automatically ${ms < 60_000 ? 'in less than a minute' : minutes < 60 ? `in ${minutes} min` : `in about ${Math.round(minutes / 60)} h`}`;
}

function advisoryStatusFact(record: AdvisoryRecord): StatusFact {
  const e = record.event;
  const extras = e.classExtras;
  const chrome = advisoryClassChrome(e.classId);
  const operationId = typeof extras.operationId === 'string' ? extras.operationId : '';
  const conditionId = typeof extras.conditionId === 'string' ? extras.conditionId : '';
  const reason = healthAdvisoryReasonBody(e.classId, extras);
  const body = reason ?? (e.bodyI18nKey ? present({ kind: 'resource', key: e.bodyI18nKey }).label : null);
  const detail = record.toast?.detail ?? (body ? interpolateMessage(body, extras) : null);
  const title = record.toast?.message ?? (e.classId === 'operation.completed'
    ? `${present({ kind: 'operation', id: operationId }).label}${extras.outcome === 'SUCCESS' ? ' completed' : extras.outcome === 'FAILURE' ? ' failed' : ''}`
    : e.classId === 'health.recoverable'
      ? `${present({ kind: 'condition', id: conditionId }).label}${extras.subject ? ` (${extras.subject})` : ''}`
      : chrome.label);
  const tone: StatusFact['tone'] = extras.outcome === 'FAILURE' || e.severity === 'error' ? 'failure'
    : e.classId === 'health.recoverable' || e.severity === 'warning' || e.classId === 'authorization.pending' ? 'warning'
      : extras.outcome === 'SUCCESS' || e.severity === 'success' || e.classId === 'core.verdict.settled' ? 'success' : 'neutral';
  return {
    id: `advisory:${record.key}`, section: 'earlier', title, tone,
    ...(detail ? { detail } : {}), occurredAt: e.occurredAt,
    // Read state never decides whether a condition is active or an approval is valid.
    unread: record.sourceRenderHint !== 'EPHEMERAL' && !record.acknowledged,
    ...(e.diagnosticsLink ? { diagnostics: e.diagnosticsLink } : {}),
    actions: record.toast?.destinations?.length ? record.toast.destinations.map(choice => go(`Open ${choice.label}`, choice.id)) : e.primaryAction ? [{ kind: 'advisory', target: record.key,
      label: e.primaryActionKind === 'undo' ? 'Undo' : /forget/.test(operationId) ? 'Add back to search' : present({ kind: 'operation', id: e.primaryAction.target }).label || 'Review' }]
      : record.toast?.onAction ? [{ kind: 'advisory', target: record.key, label: record.toast.actionLabel ?? 'Review' }] : [],
  };
}

const GRANT_WORDS: Readonly<Record<string, readonly [string, NonNullable<StatusFact['tone']>]>> = {
  ISSUED: ['Allowed', 'success'], CONSUMED: ['Used', 'neutral'], REVOKED: ['Withdrawn', 'neutral'], EXPIRED: ['Expired', 'neutral'],
};
/** Ledger rows in plain words: the structured outcome and gate disposition, never their raw codes. */
function activityWords(entry: UnifiedActionEntry): { title: string; detail?: string; tone: NonNullable<StatusFact['tone']> } {
  // R3 — grant lifecycle in plain words, never the raw action code ("grant CONSUMED").
  if (entry.grant) {
    const word = GRANT_WORDS[entry.grant.action.toUpperCase()];
    return { title: `Permission${entry.grant.subject ? ` for ${entry.grant.subject}` : ''}`,
      detail: word?.[0] ?? 'Permission changed', tone: word?.[1] ?? 'neutral' };
  }
  if (entry.gate) {
    const { disposition, operation } = entry.gate;
    return disposition === 'DENIED' ? { title: `Approval denied: ${operation}`, tone: 'failure' }
      : disposition === 'APPROVED' ? { title: `Approved: ${operation}`, tone: 'success' }
        : { title: `Approval requested: ${operation}`, tone: 'warning' };
  }
  const outcome = ({ SUCCESS: ['Completed', 'success'], FAILURE: ['Failed', 'failure'], FAILED: ['Failed', 'failure'],
    UNDONE: ['Undone', 'neutral'], PARTIAL: ['Finished incomplete', 'warning'], STARTED: ['Started', 'neutral'] } as const)[
    entry.outcome as 'SUCCESS'] as readonly [string, NonNullable<StatusFact['tone']>] | undefined;
  return { title: entry.label, ...(outcome ? { detail: outcome[0] } : {}),
    tone: outcome?.[1] ?? (/^Index (failed|gave up)/.test(entry.label) ? 'failure' : 'neutral') };
}

export function deriveFrameStatus(input: FrameStatusInput): FrameStatus {
  const { ai, now } = input;
  const progress = selectIndexingProgress(ai?.status, ai?.indexingStatusLive ?? false,
    ai?.episodeMaxPendingJobs ?? 0, ai?.enrichSettleSamples ?? []);
  if (!progress.activity.confirmed && ai?.status && ai.connection.lastSuccessMs != null) {
    const retained = selectIndexingProgress({ ...ai.status,
      meta: { ...ai.status.meta, workerRpcStale: false },
      ...(ai.status.operating ? { operating: { ...ai.status.operating,
        freshness: { ...ai.status.operating.freshness, staleSources: [] } } } : {}),
    }, true, ai.episodeMaxPendingJobs, ai.enrichSettleSamples);
    if (retained.activity.confirmed) {
      progress.activity = { ...progress.activity, detail: `Last observed ${retained.phase}.` };
      progress.pause = retained.pause;
    }
  }
  const sections: Record<StatusSection, StatusFact[]> = { needs: [], problems: [], running: [], earlier: [] };
  const add = (fact: StatusFact): void => {
    if (!sections[fact.section].some(row => row.id === fact.id)) sections[fact.section].push(fact);
  };
  for (const fact of input.conditions ?? []) add(fact);
  // L4b — a request that still waits lives in Needs you only; its advisory joins Earlier once the
  // request is answered, expires or is withdrawn (it then leaves `authorizations`).
  const waiting = new Set((input.authorizations ?? [])
    .filter(request => !request.expiresAt || Date.parse(request.expiresAt) > now)
    .map(request => request.pendingId));
  for (const record of input.advisories ?? []) {
    const pendingId = record.event.classExtras.pendingId;
    if (record.event.classId === 'authorization.pending' && typeof pendingId === 'string' && waiting.has(pendingId)) continue;
    const fact = advisoryStatusFact(record);
    // Retained authorization advisories are history, never another execution route.
    add(record.event.classId === 'authorization.pending' ? { ...fact, actions: [] } : fact);
  }
  for (const entry of input.activity ?? []) {
    if (entry.isRoutine || entry.operationOutcomeKey) continue;
    const words = activityWords(entry);
    add({ id: `activity:${entry.id}`, section: 'earlier', title: words.title,
      ...(words.detail ? { detail: words.detail } : {}), tone: words.tone, occurredAt: entry.occurredAt,
      actions: [go('All activity', 'core.activity-surface')] });
  }
  for (const root of input.roots ?? []) {
    // R4 — a folder is named by its folder name, with the full path as detail; the collection
    // ("default") is not a folder name. Until the path is resolved the row says what it is.
    const path = root.pathHash ? input.rootPaths?.get(root.pathHash) ?? null : null;
    const name = (path && folderName(path)) || 'A watched folder';
    const state = folderStatus(root, { relativeTime: '', verifiedRelativeTime: '',
      provisional: false, enrichmentStages: progress.stages,
      enrichmentPending: progress.enrichingPending > 0, enrichmentBlocked: progress.blockedPending > 0,
      indexingPaused: !!progress.pause, retryWaiting: progress.phase === 'waiting' });
    const actions = [go('Show in Library', 'core.library-surface')];
    // ST5/B1 — the same folder conditions the Library headline counts (one source, folderStatus.ts).
    for (const item of folderAttention(root, state, name)) add({ id: item.kind === 'missing' ? `folder:${root.pathHash}` : item.kind === 'files' ? `files:${root.pathHash}` : `folder-${item.key}`, section: 'problems',
      title: item.title, detail: [item.detail, path].filter(Boolean).join(' · '), stale: !input.rootsLive || (item.kind === 'files' && state.failedIsLastKnown === true), actions });
    if (['scanning', 'indexing', 'enriching', 'waiting'].includes(state.state)) add({
      id: `folder-work:${root.pathHash}`, section: 'running', title: `${name}: ${state.metaText.replace(`${root.collection ?? 'default'} · `, '')}`, ...(path ? { detail: path } : {}),
      stale: !input.rootsLive, percent: state.state === 'enriching' ? rootCoverage(root, progress.stages)?.percent ?? null : null, actions });
  }
  // Deduplicate the broadcast and request-scoped representations by the engine-issued identity.
  for (const request of input.authorizations ?? []) {
    if (request.expiresAt && Date.parse(request.expiresAt) <= now) continue;
    add({ id: `authorization:${request.pendingId}`, section: 'needs',
      title: `${request.requestedBy ?? 'An agent'} requests ${present({ kind: 'operation', id: request.operationId }).label}`,
      ...(request.argsSummary ? { detail: request.argsSummary } : {}),
      ...(request.expiresAt && authorizationDeadline(request.expiresAt, now) ? { deadline: authorizationDeadline(request.expiresAt, now)! } : {}),
      actions: [{ kind: 'authorization', target: request.pendingId, label: 'Review request' }] });
  }
  for (const p of [...input.proposals ?? []].sort((a, b) => (a.confidence ?? 1) - (b.confidence ?? 1))) add({ id: `proposal:${p.id}`, section: 'needs',
    ...(p.confidence == null ? {} : { confidence: p.confidence }),
    title: `Agent suggestion: ${present({ kind: 'effect', effect: p.effect }).label}`,
    detail: [p.rationale, p.confidence == null ? '' : `Confidence: ${Math.round(p.confidence * 100)}%`].filter(Boolean).join(' · '),
    actions: [{ kind: 'proposal', target: String(p.id), label: 'Allow suggestion' }, { kind: 'reject-proposal', target: String(p.id), label: 'Reject' }] });
  for (const c of input.consents ?? []) add({ id: `consent:${c.id}`, section: 'needs',
    title: `${c.pluginId} requests ${c.capability}`, actions: [{ kind: 'consent', target: c.id, label: 'Review permissions' }] });
  // The SSE file window is a narrower scope of the aggregate. Rows the aggregate no longer confirms
  // read "last observed …" (indexingTaskStatus) and never decide the pill.
  const fileRows: StatusFact[] = [
    ...(input.tasks ?? []).filter(task => task.id.startsWith('idxjob:') && task.status !== 'queued').slice(0, 8)
      .map(task => ({ id: task.id, section: 'running' as const, title: task.label,
        detail: indexingTaskStatus(task, progress.activity, progress.phase), stale: !progress.activity.confirmed })),
    ...((input.tasks ?? []).some(task => task.id.startsWith('idxjob:')) ? [{ id: 'file-window', section: 'running' as const,
      title: 'File details are a live window, showing up to 8 tasks; queue totals include files outside it.' }] : []),
  ];
  if (progress.pause) add({ id: 'indexing-hold', section: 'needs', indexing: true,
    title: progress.activity.summary, detail: progress.activity.detail ?? progress.pause.reason,
    stale: !progress.activity.confirmed });
  else if (progress.phase !== 'ready' && progress.phase !== 'unknown') add({ id: 'indexing',
    section: progress.phase === 'error' ? 'problems' : progress.phase === 'blocked' ? 'needs' : 'running', indexing: true,
    title: progress.activity.summary,
    detail: progress.activity.confirmed ? [progress.phase === 'indexing' ? indexingCountsLine(progress)
      : progress.phase === 'enriching' ? progress.activity.enrichmentDeferred
        ? `${progress.enrichingPercent == null ? '' : `${progress.enrichingPercent}% · `}${progress.enrichingPending} enrichment steps waiting`
        : enrichingCountsLine(progress) : '', progress.activity.detail].filter(Boolean).join(' · ') : progress.activity.detail ?? undefined,
    progressLabel: (progress.phase === 'indexing' ? indexingCountsLabel(progress) : enrichingCountsLabel(progress)) ?? undefined,
    indeterminate: progress.activity.confirmed && progress.phase === 'indexing' && progress.indexingPercent == null,
    details: [
      ...(progress.jobsQueued > 0 ? [{ id: 'queued', section: 'running' as const,
        title: `${formatCount(progress.jobsQueued)} files queued`, stale: !progress.activity.confirmed }] : []),
      ...((ai?.status?.worker?.failure?.failedJobs ?? 0) > 0 ? [{ id: 'failed-files', section: 'problems' as const,
        title: `${formatCount(ai!.status!.worker!.failure!.failedJobs!)} failed`, stale: !progress.activity.confirmed }] : []),
      ...progress.enrichingStages.map(stage => ({ id: `stage:${stage.id}`, section: 'running' as const,
        stale: !progress.activity.confirmed,
        title: ({ embedding: 'Semantic vectors', splade: 'Keyword expansion', ner: 'Entity recognition', chunkVectors: 'Passage vectors', chunkSplade: 'Keyword expansion' })[stage.id],
        detail: `${stage.total - stage.pending} / ${stage.total}${stage.failed ? ` · ${stage.failed} failed` : ''}${stage.missing ? ` · ${stage.missing} missing` : ''}${stage.pending === 0 && !stage.failed && !stage.missing ? ' ✓' : ''}` })),
      ...(progress.vduPending > 0 ? [{ id: 'extraction', section: 'running' as const, stale: !progress.activity.confirmed, title: `Content extraction — ${progress.vduPending} remaining` }] : []),
      ...fileRows,
    ],
    stale: !progress.activity.confirmed,
    actions: [go('Open AI Brain', 'core.brain-surface')],
    percent: !progress.activity.confirmed || progress.activity.enrichmentDeferred ? null : progress.phase === 'indexing' ? progress.indexingPercent
      : progress.phase === 'enriching' ? progress.enrichingPercent : null });
  if (fileRows.length && !Object.values(sections).flat().some(row => row.id === 'indexing')) add({ id: FILE_ACTIVITY_ID,
    section: 'running', title: 'Recent file activity', stale: true, details: fileRows,
    actions: [go('Open AI Brain', 'core.brain-surface')] });
  const limited = ai?.verdict.reasons.some(code => isSearchUnavailableCause(code) || isReindexCause(code)) ?? false;
  if (limited && ai) add({ id: 'search-limited', section: 'problems', title: 'Search limited',
    detail: verdictHeadline(ai.verdict), stale: ai.phase !== 'connected', actions: [go('Health', 'core.health-surface')] });
  if (ai?.aiEngine.kind === 'install_failed') add({ id: 'ai-install-failed', section: 'problems',
    title: 'AI install failed', detail: ai.aiEngine.installFailure ?? 'Review the AI models and try again.',
    stale: !ai.snapshotLive, actions: [go('Open AI Brain', 'core.brain-surface')] });
  const install = ai?.installStatus;
  if (ai && (install?.state === 'running' || ['installing', 'starting'].includes(ai.aiEngine.kind))) add({ id: 'ai-install', section: 'running',
    title: install?.paused ? 'AI download paused' : ai.aiEngine.kind === 'starting' ? 'Starting AI models' : 'Installing AI models',
    stale: !ai.installObservationLive,
    percent: ai.installObservationLive && (install?.totalBytes ?? 0) > 0 && (install?.downloadedBytes ?? -1) >= 0
      ? Math.min(100, Math.round(install!.downloadedBytes! / install!.totalBytes! * 100)) : null,
    detail: (install?.totalBytes ?? 0) > 0 && install?.downloadedBytes != null
      ? `${formatBytes(install.downloadedBytes)} of ${formatBytes(install.totalBytes!)}` : install?.message ?? undefined,
    actions: [...(install?.state === 'running' ? [{ kind: 'install-toggle' as const, target: install.paused ? 'resume' : 'pause', label: install.paused ? 'Resume download' : 'Pause download' }] : []),
      go('Open AI Brain', 'core.brain-surface')] });
  if (ai?.status?.worker?.migration?.migrationState === 'AWAITING_ACCEPTANCE') add({
    id: 'rebuild-gaps', section: 'needs', title: 'Rebuild needs a decision about missing files',
    stale: !ai.indexingStatusLive, actions: [go('Review gaps in Library', 'core.library-surface')] });
  if (ai && ai.activity.state !== 'idle') add({ id: 'answering', section: 'running', title: 'Answering in Search',
    actions: [go('Return to Search', 'core.search-v3-surface')] });
  if (ai && (ai.stability.kind === 'provisional' && ['rebuilding', 'generation-switch'].includes(ai.stability.cause)
    || ['MIGRATING', 'SWITCHING'].includes(ai.status?.worker?.migration?.migrationState ?? ''))) add({
    id: 'rebuilding', section: 'running', title: 'Rebuilding search', percent: progress.rebuildPercent,
    stale: !progress.activity.confirmed, actions: [go('Open Library', 'core.library-surface')] });
  for (const operation of input.operations ?? []) {
    const outcome = operation.outcome;
    const gaps = outcome?.phase === 'awaiting_acceptance';
    add({ id: `operation:${operation.id}`, section: gaps ? 'needs' : outcome?.state === 'failed' ? 'problems'
      : outcome?.state === 'complete' ? 'earlier' : 'running', title: operation.label,
    detail: gaps ? 'Completed with gaps — review before continuing.' : outcome?.message ?? (outcome ? OPERATION_STATE_WORDS[outcome.state] ?? undefined : 'Last seen running · checking'),
    tone: gaps ? 'warning' : outcome?.state === 'failed' ? 'failure' : outcome?.state === 'complete' ? 'success' : 'neutral',
    stale: operation.fresh === false || !outcome || ['unknown', 'expired'].includes(outcome.state),
    actions: [go('Review in Library', 'core.library-surface')] });
  }
  const durableTasks = new Set((input.operations ?? []).map(o => o.invocationTaskId));
  for (const task of input.tasks ?? []) {
    if (durableTasks.has(task.id) || task.operationKey || task.id.startsWith('ai:')) continue;
    // Indexing SSE jobs can outlive their observation. Never let their retained "running" claim
    // override the aggregate selector, and do not duplicate the aggregate as a finished task.
    if (task.id.startsWith('idxjob:')) continue;
    add({ id: `task:${task.id}`, section: task.status === 'running' || task.status === 'queued' ? 'running' : 'earlier',
      title: task.label, detail: TASK_STATUS_WORDS[task.status] ?? task.status, occurredAt: task.startedAt,
      tone: task.status === 'failed' ? 'failure' : task.status === 'succeeded' ? 'success' : 'neutral',
      percent: task.progress === undefined ? null : Math.round(task.progress * 100),
      actions: [...(task.originSurfaceId ? [go('Return to it', task.originSurfaceId)] : []),
        ...(task.cancellable && task.status === 'running' ? [{ kind: 'cancel-task' as const, target: task.id, label: 'Cancel' }] : [])] });
  }
  for (const run of input.presence?.runs ?? []) {
    const terminal = ['DONE', 'ERROR'].includes(run.state);
    add({ id: `background:${run.sessionId}`, section: terminal ? 'earlier'
      : ['WAITING_BUDGET', 'WAITING_CONTEXT'].includes(run.state) ? 'needs' : 'running',
      title: `Background agent ${run.state === 'DONE' ? 'finished' : run.state === 'ERROR' ? 'failed' : 'run'}`,
      tone: run.state === 'ERROR' ? 'failure' : run.state === 'DONE' ? 'success' : 'neutral',
      detail: run.state.toLowerCase().replaceAll('_', ' '), stale: !terminal && !input.presence!.live,
      actions: [{ kind: 'background-runs', target: run.sessionId, label: 'View background runs' }] });
  }
  const update = input.update;
  if (update && ['available', 'repair_required'].includes(update.state)) add({ id: 'app-update', section: 'needs',
    title: update.state === 'available' ? `JustSearch ${update.availableVersion ?? 'update'} is available` : 'The app update needs repair',
    actions: [go(update.state === 'available' ? 'Review and install' : 'Repair instructions', 'core.settings-surface')] });
  // Running leads with the work the whole app waits on; per-folder rows are its narrower scope.
  const runningRank = (fact: StatusFact): number => ['indexing', 'rebuilding', 'ai-install'].includes(fact.id)
    ? ['indexing', 'rebuilding', 'ai-install'].indexOf(fact.id) : fact.id.startsWith('folder-work:') ? 4 : fact.id === FILE_ACTIVITY_ID ? 5 : 3;
  sections.running.sort((a, b) => runningRank(a) - runningRank(b));
  sections.earlier.sort((a, b) => Number(!!b.unread) - Number(!!a.unread)
    || (b.occurredAt ?? '').localeCompare(a.occurredAt ?? ''));
  const stale = !ai || ai.phase !== 'connected';
  let pill: StatusPillState;
  const pastGrace = input.contactLostAt !== null && now - input.contactLostAt >= RECONNECT_GRACE_MS;
  if (ai && !ai.connection.reachable && pastGrace) pill = { kind: 'unreachable', label: "Can't reach the engine", stale: false };
  else if (limited) pill = { kind: 'limited', label: 'Search limited', stale };
  else if (sections.needs.length) pill = { kind: 'needs', label: `${sections.needs.length} ${sections.needs.length === 1 ? 'needs' : 'need'} you`, count: sections.needs.length, stale: sections.needs.every(row => row.stale === true) };
  else if (sections.problems.length) pill = { kind: 'problems', label: `Problems right now (${sections.problems.length})`, stale };
  else if (sections.running.some(row => row.id !== FILE_ACTIVITY_ID)) {
    const row = sections.running.find(r => r.id !== FILE_ACTIVITY_ID)!;
    pill = { kind: 'working', label: row.title, stale: row.stale ?? stale, ...(row.percent != null ? { percent: row.percent } : {}) };
  // A completion is a ready claim: it needs a fresh observation like calm does (ST2).
  } else if (!stale && input.finished && now - input.finished.at < FINISHED_DWELL_MS) pill = { kind: 'finished', label: input.finished.title, stale: false };
  else if (!stale && ai?.verdict.kind === 'operational' && progress.phase === 'ready') pill = { kind: 'calm', label: 'Up to date', stale: false };
  else pill = { kind: 'unknown', label: 'Checking search status', stale: true };
  if (pill.stale) {
    const age = ai?.connection.lastSuccessMs == null ? null : Math.max(0, Math.floor((now - ai.connection.lastSuccessMs) / 60_000));
    // No retained calm claim while an observation is stale. The contact signal determines ONLY
    // "reconnecting"; a reachable origin with a stale poll says "checking".
    pill = { ...pill, label: `${pill.kind === 'calm' ? 'Search status' : pill.label} · ${age === null ? 'not yet observed' : `last seen ${age < 1 ? 'just now' : `${age} min ago`}`} · ${ai?.connection.reachable || !pastGrace ? 'checking' : 'reconnecting'}` };
  }
  return { pill, sections, progress };
}

/**
 * R1 — where a passing notice (own-action receipt, undo, "just finished") shows. It never displaces
 * a more urgent state: engine unreachable, search limited, needs you and problems keep the pill and
 * the notice shows beside it; the engine's own wait notices say nothing while the engine is
 * unreachable. Every notice stays recoverable in Earlier.
 */
export function transientPlacement(pill: Pick<StatusPillState, 'kind'>, classId: string): 'pill' | 'beside' | 'hidden' {
  if (pill.kind === 'unreachable' && classId === 'core.engine.wait') return 'hidden';
  return ['unreachable', 'limited', 'needs', 'problems'].includes(pill.kind) ? 'beside' : 'pill';
}

/** Completion edges require an observed active phase; initial/replayed terminal values are silent. */
export function completedLongWork(before: FrameStatus | null, after: FrameStatus): string | null {
  if (!before) return null;
  const wasActive = before.progress.activity.confirmed && ['indexing', 'enriching'].includes(before.progress.phase);
  if (wasActive && after.progress.activity.confirmed && after.progress.phase === 'ready') return 'Search is up to date';
  return null;
}
