// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { deriveFrameStatus, completedLongWork, FINISHED_DWELL_MS, authorizationDeadline, transientPlacement, folderName,
  type StatusFact } from './frameStatus.js';
import type { AiState } from './aiStateStore.js';
import type { AdvisoryRecord } from '../components/advisory/AdvisoryStore.js';
import { projectBackend, type BackendLedgerEntry } from '../operations/ActionLedgerClient.js';
const now = 100_000;
const RECONNECT_GRACE_MS = 10_000;
type FrameStatusInput = Parameters<typeof deriveFrameStatus>[0];
function ai(overrides: Partial<AiState> = {}): AiState {
  return { phase: 'connected', connection: { reachable: true, lastSuccessMs: now, lastContactMs: now, consecutiveFailures: 0 },
    verdict: { kind: 'operational', severity: 'info', reasons: [] }, stability: { kind: 'settled' },
    aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null },
    activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
    status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {} } },
    indexingStatusLive: true, snapshotLive: true, episodeMaxPendingJobs: 0, enrichSettleSamples: [], ...overrides,
  } as AiState;
}
const condition = (section: StatusFact['section']): StatusFact => ({ id: section, section, title: section });
function derive(overrides: Partial<FrameStatusInput> = {}) {
  return deriveFrameStatus({ ai: ai(), now, contactLostAt: null, ...overrides });
}
function advisory(acknowledged = false): AdvisoryRecord {
  return { key: 'h1', origin: 'stream', acknowledged, sourceRenderHint: 'PERSISTED', event: {
    id: 'h1', classId: 'health.recoverable', occurredAt: '2026-11-03T10:00:00Z', renderHint: 'PERSISTED',
    classExtras: { conditionId: 'folder.unavailable', subject: 'Documents' }, diagnosticsLink: null, provenance: null,
    primaryAction: null, bodyI18nKey: null,
  } };
}
describe('frame status projection', () => {
  it('ST1 keeps concurrent facts discoverable while the pill chooses one urgency', () => {
    const conditions = [condition('needs'), condition('problems'), condition('running')];
    const unreachable = ai({ connection: { reachable: false, lastSuccessMs: now - 50_000, lastContactMs: now - 50_000, consecutiveFailures: 3 } });
    const all = derive({ ai: unreachable, conditions, contactLostAt: now - RECONNECT_GRACE_MS });
    expect(all.pill.kind).toBe('unreachable');
    expect(all.sections.needs).toHaveLength(1);
    expect(all.sections.problems).toHaveLength(1);
    expect(all.sections.running).toHaveLength(1);
    expect(derive({ ai: ai({ verdict: { kind: 'degraded', severity: 'warn', reasons: ['index.schema_open_refused'] } }), conditions }).pill.kind).toBe('limited');
    expect(derive({ conditions }).pill.kind).toBe('needs');
    expect(derive({ conditions: conditions.slice(1) }).pill.kind).toBe('problems');
    expect(derive({ conditions: conditions.slice(2) }).pill.kind).toBe('working');
    expect(derive({ finished: { title: 'Finished indexing', at: now } }).pill.kind).toBe('finished');
    expect(derive().pill).toEqual({ kind: 'calm', label: 'Up to date', stale: false });
  });
  it('ST2 separates contact from poll freshness and waits a full poll before unreachable', () => {
    const stale = ai({ phase: 'stale' });
    expect(derive({ ai: stale }).pill.label).toContain('last seen');
    expect(derive({ ai: stale }).pill.label).not.toMatch(/Up to date|reach|reconnecting/);
    const lost = { ...stale, connection: { ...stale.connection, reachable: false } };
    expect(derive({ ai: lost, contactLostAt: now - RECONNECT_GRACE_MS + 1 }).pill.kind).not.toBe('unreachable');
    expect(derive({ ai: lost, contactLostAt: now - RECONNECT_GRACE_MS }).pill.kind).toBe('unreachable');
    expect(derive({ ai: null }).pill.label).toContain('not yet observed');
  });
  it('ST6 durable acceptance and gaps suppress an invocation-return completion', () => {
    const tasks = [{ id: 'invoke1', label: 'Rebuild', status: 'succeeded' as const, startedAt: '2026-11-03T10:00:00Z', cancellable: false }];
    const accepted = derive({ tasks, operations: [{ id: 'durable1', label: 'Rebuild', invocationTaskId: 'invoke1', outcome: { state: 'accepted', historySince: 0 } }] });
    expect(accepted.sections.running.map(row => row.id)).toContain('operation:durable1');
    expect(accepted.sections.earlier).toEqual([]);
    const gaps = derive({ tasks, operations: [{ id: 'durable1', label: 'Rebuild', invocationTaskId: 'invoke1', outcome: { state: 'complete', phase: 'awaiting_acceptance', historySince: 0, result: { gaps: [{ unitId: 'folder1', reason: 'unavailable' }] } } }] });
    expect(gaps.sections.needs[0]?.detail).toContain('gaps');
    expect(gaps.sections.earlier).toEqual([]);
  });
  it('ST7 read state never decides active/resolved and Earlier stays newest first', () => {
    const read = advisory(true);
    const unread = { ...advisory(), key: 'h2', event: { ...read.event, id: 'h2', occurredAt: '2026-11-03T11:00:00Z' } };
    const result = derive({ advisories: [read, unread], conditions: [condition('problems')] });
    expect(result.sections.earlier.map(row => row.unread)).toEqual([true, false]);
    expect(result.sections.problems).toHaveLength(1);
    expect(derive({ advisories: [read, unread] }).sections.problems).toEqual([]);
  });
  it('ST8 C6 terminal replay is silent; only an observed active-to-ready edge completes', () => {
    const ready = derive();
    expect(completedLongWork(null, ready)).toBeNull();
    expect(completedLongWork(ready, ready)).toBeNull();
    const active = derive({ ai: ai({ status: { worker: { core: { indexState: 'INDEXING', pendingJobs: 5 }, enrichment: {} } } as AiState['status'] }) });
    expect(completedLongWork(active, ready)).toBe('Search is up to date');
    expect(derive({ finished: { title: 'Finished', at: now - FINISHED_DWELL_MS } }).pill.kind).toBe('calm');
    // The completion window withdraws when the next observation cannot be read.
    const unread = derive({ ai: ai({ phase: 'stale' }), finished: { title: 'Search is up to date', at: now } });
    expect(unread.pill.kind).toBe('unknown'); expect(unread.pill.label).not.toContain('up to date');
  });
  it('ST6 AI downloads preserve measured progress and the existing pause/resume route', () => {
    const result = derive({ ai: ai({ installStatus: { state: 'running', downloadedBytes: 100, totalBytes: 200 },
      installObservationLive: true, aiEngine: { kind: 'installing', stability: { kind: 'settled' }, installFailure: null } }) });
    const install = result.sections.running.find(row => row.id === 'ai-install')!;
    expect(install.percent).toBe(50);
    expect(install.actions).toContainEqual({ kind: 'install-toggle', target: 'pause', label: 'Pause download' });
    const paused = derive({ ai: ai({ installStatus: { state: 'running', paused: true }, installObservationLive: true }) });
    expect(paused.sections.running.find(row => row.id === 'ai-install')!.actions).toContainEqual({ kind: 'install-toggle', target: 'resume', label: 'Resume download' });
  });
  it('ref-09 agent request deadlines read as their consequence relative to now, never as a raw time', () => {
    const expiresAt = new Date(now + 4 * 60_000).toISOString();
    const fact = derive({ authorizations: [{ pendingId: 'p', operationId: 'core.reindex', expiresAt, argsSummary: 'Downloads' }] }).sections.needs[0]!;
    expect(fact.deadline).toBe('Denied automatically in 4 min');
    expect(fact.detail).toBe('Downloads');
    expect(JSON.stringify(fact)).not.toContain(expiresAt);
    // The countdown follows the derivation clock; it rounds up so it never claims more time than is left.
    expect(derive({ now: now + 3 * 60_000 + 1, authorizations: [{ pendingId: 'p', operationId: 'core.reindex', expiresAt }] }).sections.needs[0]!.deadline)
      .toBe('Denied automatically in less than a minute');
    expect(authorizationDeadline(new Date(now + 90 * 60_000).toISOString(), now)).toBe('Denied automatically in about 2 h');
    expect(authorizationDeadline(new Date(now + 61_000).toISOString(), now)).toBe('Denied automatically in 2 min');
    // No deadline line without a parseable deadline.
    expect(derive({ authorizations: [{ pendingId: 'p', operationId: 'core.reindex', expiresAt: 'soon' }] }).sections.needs[0]!.deadline).toBeUndefined();
    expect(derive({ authorizations: [{ pendingId: 'p', operationId: 'core.reindex' }] }).sections.needs[0]!.deadline).toBeUndefined();
  });
  it('ref-09 the needs-you pill carries its count for the badge', () => {
    expect(derive({ conditions: [condition('needs')] }).pill).toMatchObject({ kind: 'needs', label: '1 needs you', count: 1 });
    expect(derive({ conditions: [condition('needs'), { ...condition('needs'), id: 'n2' }] }).pill).toMatchObject({ label: '2 need you', count: 2 });
  });
  it('C12 the broadcast advisory and the request-scoped prompt for one authorization give one decision route', () => {
    const request = { pendingId: 'exact-target1', operationId: 'core.reindex', expiresAt: new Date(now + 50_000).toISOString() };
    const broadcast: AdvisoryRecord = { ...advisory(), key: 'auth-adv', sourceRenderHint: 'REQUIRES_ACK', event: { ...advisory().event, id: 'auth-adv',
      classId: 'authorization.pending', renderHint: 'REQUIRES_ACK', classExtras: { pendingId: 'exact-target1', operationId: 'core.reindex' },
      primaryAction: { target: 'core.reindex', defaultArgsJson: '{}' } } };
    const m = derive({ authorizations: [request], advisories: [broadcast] });
    expect(m.sections.needs.map(f => f.id)).toEqual(['authorization:exact-target1']);
    expect(m.sections.needs[0]!.actions).toEqual([{ kind: 'authorization', target: 'exact-target1', label: 'Review request' }]);
    // L4b/L5: while waiting, its broadcast cannot duplicate the request in Earlier.
    expect(m.sections.earlier.find(f => f.id === 'advisory:auth-adv')).toBeUndefined();
    expect(m.sections.earlier.map(f => f.id)).not.toContain('advisory:auth-adv');
    // Answered elsewhere / withdrawn: the request leaves, the history stays inert.
    const answered = derive({ authorizations: [], advisories: [broadcast] });
    expect(answered.sections.needs).toEqual([]);
    expect(answered.sections.earlier[0]!.actions).toEqual([]);
  });
  it('L4b a waiting approval is in Needs you only; it enters Earlier once answered, expired or withdrawn', () => {
    const request = { pendingId: 'p-wait', operationId: 'core.reindex', expiresAt: new Date(now + 50_000).toISOString() };
    const broadcast: AdvisoryRecord = { ...advisory(), key: 'auth-wait', sourceRenderHint: 'REQUIRES_ACK', event: { ...advisory().event, id: 'auth-wait',
      classId: 'authorization.pending', renderHint: 'REQUIRES_ACK', classExtras: { pendingId: 'p-wait', operationId: 'core.reindex' } } };
    const waiting = derive({ authorizations: [request], advisories: [broadcast] });
    expect(waiting.sections.needs.map(f => f.id)).toEqual(['authorization:p-wait']);
    expect(waiting.sections.earlier.map(f => f.id)).toEqual([]);
    // Expired: the request cannot be answered any more; the history row (inert, unread) appears.
    const expired = derive({ authorizations: [{ ...request, expiresAt: new Date(now - 1).toISOString() }], advisories: [broadcast] });
    expect(expired.sections.needs).toEqual([]);
    expect(expired.sections.earlier.map(f => [f.id, f.unread, f.actions])).toEqual([['advisory:auth-wait', true, []]]);
    // Answered or withdrawn: the request left `authorizations`.
    const answered = derive({ authorizations: [], advisories: [broadcast] });
    expect(answered.sections.earlier.map(f => f.id)).toEqual(['advisory:auth-wait']);
    // Another request waiting does not hold back this one's history.
    const other = derive({ authorizations: [{ ...request, pendingId: 'p-other' }], advisories: [broadcast] });
    expect(other.sections.earlier.map(f => f.id)).toEqual(['advisory:auth-wait']);
  });
  it('L4b an MCP request also recorded as a GATED ledger row waits in Needs you only; the row joins Earlier once it is answered or expires', () => {
    // The real shapes: PendingAuthorizationAdvisoryProjector's advisory (classExtras pendingId,
    // operationId, riskTier, gateBehavior, expiresAt) and ActionLedgerProjection.toWireRow's gate row
    // for the same firing (the exact pending id), projected by the ledger client the controller uses.
    // The older row predates request identity and must remain visible.
    const expiresAt = new Date(now + 120_000).toISOString();
    const request = { pendingId: '7f1c2a9e-mcp', operationId: 'core.ingest-files', expiresAt };
    const broadcast: AdvisoryRecord = { key: '7f1c2a9e-mcp', origin: 'stream', acknowledged: false, sourceRenderHint: 'REQUIRES_ACK', event: {
      id: '7f1c2a9e-mcp', classId: 'authorization.pending', occurredAt: new Date(now - 1_000).toISOString(), renderHint: 'REQUIRES_ACK',
      classExtras: { pendingId: '7f1c2a9e-mcp', operationId: 'core.ingest-files', riskTier: 'MEDIUM', gateBehavior: 'CONFIRM', expiresAt },
      diagnosticsLink: null, provenance: null, primaryAction: null, bodyI18nKey: null } };
    const row = (occurredAt: string, disposition: string, pendingId?: string): BackendLedgerEntry => ({ id: `gate:${occurredAt}:core.ingest-files:${disposition}`,
      kind: 'gate', occurredAt, originator: 'agent', transport: 'MCP', operationId: 'core.ingest-files', outcome: disposition,
      disposition, gateBehavior: 'CONFIRM', sourceTier: 'USER', ...(pendingId ? { pendingId } : {}) } as BackendLedgerEntry);
    const gated = projectBackend(row(new Date(now - 1_000).toISOString(), 'GATED', request.pendingId));
    const earlierGate = projectBackend(row(new Date(now - 3_600_000).toISOString(), 'GATED'));
    // Observe the same gate history rows independent of their terminal outcome wording.
    const requested = (m: ReturnType<typeof derive>) => m.sections.earlier
      .filter(f => f.id === `activity:${gated.id}` || f.id === `activity:${earlierGate.id}`).map(f => f.id);
    const waiting = derive({ authorizations: [request], advisories: [broadcast], activity: [earlierGate, gated] });
    expect(waiting.sections.needs.map(f => f.id)).toEqual(['authorization:7f1c2a9e-mcp']);
    // The earlier, already answered request for the same operation stays history.
    expect(requested(waiting)).toEqual([`activity:${earlierGate.id}`]);
    // Answered elsewhere: the Engine says it no longer waits (withdrawn), and this window lists it no more.
    const answered = derive({ authorizations: [], advisories: [broadcast], activity: [earlierGate, gated], withdrawn: new Set([request.pendingId]) });
    expect(answered.sections.needs).toEqual([]);
    expect(requested(answered)).toEqual(expect.arrayContaining([`activity:${gated.id}`, `activity:${earlierGate.id}`]));
    const expired = derive({ authorizations: [{ ...request, expiresAt: new Date(now - 1).toISOString() }], advisories: [broadcast], activity: [gated] });
    expect(requested(expired)).toEqual(expect.arrayContaining([`activity:${gated.id}`]));
  });
  it('ST1 working shows the most important running item with its measured progress only', () => {
    const indexing = ai({ status: { worker: { core: { indexState: 'INDEXING', pendingJobs: 400 }, enrichment: {} } } as AiState['status'], episodeMaxPendingJobs: 1600 });
    const m = derive({ ai: indexing, conditions: [{ id: 'other', section: 'running', title: 'Other work' }] });
    expect(m.pill).toMatchObject({ kind: 'working', percent: 75 });
    expect(m.pill.label).toBe(m.sections.running.find(f => f.id === 'indexing')!.title);
    const unknownFraction = derive({ ai: ai({ status: { worker: { core: { indexState: 'INDEXING', pendingJobs: 400 }, enrichment: {} } } as AiState['status'] }) });
    expect(unknownFraction.pill.kind).toBe('working'); expect(unknownFraction.pill.percent).toBeUndefined();
  });
  it('ST2 a reachable engine with a stale poll says checking, never reconnecting, and never claims calm', () => {
    const stalePoll = ai({ phase: 'stale', connection: { reachable: true, lastSuccessMs: now - 3 * 60_000, lastContactMs: now, consecutiveFailures: 0 } });
    const m = derive({ ai: stalePoll });
    expect(m.pill.kind).toBe('unknown'); expect(m.pill.stale).toBe(true);
    expect(m.pill.label).toBe('Checking search status · last seen 3 min ago · checking');
    const lostPastGrace = { ...stalePoll, connection: { ...stalePoll.connection, reachable: false } };
    expect(derive({ ai: lostPastGrace, contactLostAt: now - RECONNECT_GRACE_MS }).pill.label).toBe("Can't reach the engine");
    expect(derive({ ai: lostPastGrace, contactLostAt: now - 1 }).pill.label).toContain('checking');
  });
  it('C12 deduplicates the same backend authorization by pending id', () => {
    const request = { pendingId: 'exact-target1', operationId: 'core.reindex', expiresAt: new Date(now + 50_000).toISOString() };
    expect(derive({ authorizations: [request, request] }).sections.needs).toHaveLength(1);
    expect(derive({ authorizations: [{ ...request, expiresAt: new Date(now).toISOString() }] }).sections.needs).toEqual([]);
  });
});


describe('indexing detail moved from the task tray (813 §5/§20)', () => {
  it('stage rows mark only finished stages, extraction has no fraction, and file rows come after stages', () => {
    const m = derive({ ai: ai({ status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0, pendingVduCount: 7 }, enrichment: {
      embeddingEnabled: true, embeddingAvailable: true, embeddingDocCount: 100, embeddingPendingCount: 0,
      spladeEnabled: true, spladeDocCount: 100, spladePendingCount: 30, nerEnabled: false } } } as unknown as AiState['status'] }),
      tasks: [{ id: 'idxjob:a', label: 'a.txt', status: 'running', startedAt: '2026-11-03T10:00:00Z', cancellable: false }] });
    const details = m.sections.running.find(f => f.id === 'indexing')?.details ?? [];
    const ids = details.map(d => d.id);
    expect(details.find(d => d.id === 'stage:embedding')!.detail).toContain('✓');
    expect(details.find(d => d.id === 'stage:splade')!.detail).not.toContain('✓');
    expect(ids.indexOf('idxjob:a')).toBeGreaterThan(ids.indexOf('stage:splade'));
    expect(details.find(d => d.id === 'extraction')!.title).toBe('Content extraction — 7 remaining');
    expect(derive().sections.running.flatMap(f => f.details ?? []).some(d => d.id === 'extraction')).toBe(false);
  });
  it('a terminal indexing failure is a current problem, never a completion', () => {
    const failed = ai({ status: { worker: { core: { indexState: 'ERROR', pendingJobs: 0 }, enrichment: {} } } as AiState['status'] });
    const m = derive({ ai: failed, finished: { title: 'Search is up to date', at: now } });
    expect(m.sections.problems.map(f => f.id)).toContain('indexing');
    expect(m.pill.kind).toBe('problems');
  });
});

describe('concurrent source facts', () => {
  it.each(['index.failed', 'index.blocked_legacy', 'index.schema_open_refused'])('ST1 search restriction %s precedes decisions and remains actionable', reason => {
    const m = derive({ ai: ai({ verdict: { kind: 'degraded', severity: 'warn', reasons: [reason] } }), conditions: [condition('needs')] });
    expect(m.pill.kind).toBe('limited'); expect(m.sections.problems.find(f => f.id === 'search-limited')!.actions![0]!.target).toBe('core.health-surface');
    expect(m.sections.needs).toHaveLength(1);
  });
  it('ST4 keeps all decisions, holds and running work visible together, including all overlapping holds', () => {
    const held = ai({ status: { operating: { indexing: { state: 'PAUSING', reasons: ['user_paused', 'schedule', 'on_battery', 'settings_recovery'], waitingForResume: true } },
      worker: { core: { indexState: 'INDEXING', pendingJobs: 4 }, migration: { migrationState: 'AWAITING_ACCEPTANCE' }, enrichment: {} } } as AiState['status'] });
    const m = derive({ ai: held, update: { state: 'repair_required' } as FrameStatusInput['update'],
      consents: [{ id: 'plugin1', pluginId: 'timer', capability: 'external-fetch' }],
      authorizations: [{ pendingId: 'backend', operationId: 'core.reindex', expiresAt: new Date(now + 50000).toISOString() }] });
    expect(m.sections.needs.map(f => f.id)).toEqual(['authorization:backend', 'consent:plugin1', 'indexing-hold', 'rebuild-gaps', 'app-update']);
    const pause = m.sections.needs.find(f => f.id === 'indexing-hold')!;
    expect(pause.title).toBe('Pausing — finishing current files');
    for (const reason of ['Paused by you', 'Outside indexing hours', 'on battery', 'Settings recovery']) expect(pause.detail).toContain(reason);
    expect(m.progress.pause!.reasons).toHaveLength(4);
  });
  it('ST5 active folder failures and AI failure leave Problems on recovery without turning acknowledgement into recovery', () => {
    const root = { pathHash: 'notes', collection: 'Notes', status: 'unavailable', fileCount: 4, failedCount: 2 } as NonNullable<FrameStatusInput['roots']>[number];
    const m = derive({ roots: [root], rootsLive: true, ai: ai({ aiEngine: { kind: 'install_failed', stability: { kind: 'settled' }, installFailure: 'No space' } }), advisories: [advisory(true)] });
    expect(m.sections.problems.map(f => f.id)).toEqual(['folder:notes', 'files:notes', 'ai-install-failed']);
    expect(m.sections.problems[0]!.actions![0]!.target).toBe('core.library-surface');
    const recovered = derive({ roots: [{ ...root, status: 'indexed', failedCount: 0 }], rootsLive: true, advisories: [advisory(true)] });
    expect(recovered.sections.problems).toEqual([]); expect(recovered.sections.earlier).toHaveLength(1);
  });
  it('B1 a folder whose scan errored is a problem right now, as the Library headline counts it, and the pill never claims calm', () => {
    const root = { pathHash: 'locked', collection: 'Locked', status: 'indexed', fileCount: 4, failedCount: 0,
      walkError: 'INCOMPLETE_COVERAGE' } as NonNullable<FrameStatusInput['roots']>[number];
    const rootPaths = new Map([['locked', '/home/me/Locked']]);
    const m = derive({ ai: ai(), roots: [root], rootPaths, rootsLive: true });
    const row = m.sections.problems.find(f => f.id === 'folder-walk:locked');
    expect(row?.title).toBe('Locked needs attention');
    expect(row?.detail).not.toContain('INCOMPLETE_COVERAGE');
    expect(row?.actions?.[0]?.target).toBe('core.library-surface');
    expect(m.pill.kind).toBe('problems');
    expect(m.pill.label).not.toBe('Up to date');
    const unverified = derive({ ai: ai(), roots: [{ ...root, walkError: '', deleteDetectionUnverified: true }], rootPaths, rootsLive: true });
    expect(unverified.sections.problems.map(f => f.title)).toEqual(['Locked: deletions not verified']);
    expect(derive({ ai: ai(), roots: [{ ...root, walkError: '' }], rootsLive: true }).pill.kind).toBe('calm');
  });
  it('ST6 answering, per-folder work, rebuild and background runs retain separate freshness and real fractions', () => {
    const m = derive({ ai: ai({ activity: { state: 'generating' } as unknown as AiState['activity'], stability: { kind: 'provisional', cause: 'rebuilding' } as AiState['stability'] }),
      roots: [{ pathHash: 'notes', collection: 'Notes', status: 'indexing', inFlightCount: 3, fileCount: 8 } as NonNullable<FrameStatusInput['roots']>[number]], rootsLive: false,
      presence: { live: false, runs: [{ sessionId: 'agent1', state: 'RUNNING' } as NonNullable<FrameStatusInput['presence']>['runs'][number]] } });
    expect(m.sections.running.map(f => f.id)).toEqual(['rebuilding', 'answering', 'background:agent1', 'folder-work:notes']);
    expect(m.pill.label).toContain('Rebuilding search');
    expect(m.sections.running.find(f => f.id === 'folder-work:notes')!.stale).toBe(true);
    expect(m.sections.running.find(f => f.id === 'background:agent1')!.stale).toBe(true);
  });
  it('ST7 Earlier orders advisories and completed operations newest first regardless of read state', () => {
    const read = advisory(true); const unread = { ...advisory(), key: 'older', event: { ...read.event, occurredAt: '2025-01-01T00:00:00Z' } };
    const activity = [projectBackend({ id: 'done', kind: 'operation', operationId: 'core.reindex', outcome: 'SUCCESS',
      originator: 'user', occurredAt: '2026-11-03T09:00:00Z' })];
    const result = derive({ advisories: [unread, read], activity });
    expect(result.sections.earlier.map(f => f.id)).toEqual(['advisory:h1', 'activity:done', 'advisory:older']);
    expect(result.sections.earlier.map(f => !!f.unread)).toEqual([false, false, true]);
    expect(result.sections.problems).toEqual([]);
  });
  it.each(['available', 'repair_required'])('ST4 update %s retains its existing Settings action', state => {
    expect(derive({ update: { state, availableVersion: '1.2.3' } as FrameStatusInput['update'] }).sections.needs[0]!.actions![0]!.target).toBe('core.settings-surface');
    expect(derive({ update: { state: 'idle' } as FrameStatusInput['update'] }).sections.needs).toEqual([]);
  });
});

describe('round 7 repairs', () => {
  it('R1 a passing notice never takes the pill from a more urgent state; engine waits say nothing while unreachable', () => {
    for (const kind of ['unreachable', 'limited', 'needs', 'problems'] as const) {
      expect(transientPlacement({ kind }, 'core.ephemeral')).toBe('beside');
      expect(transientPlacement({ kind }, 'core.verdict.settled')).toBe('beside');
    }
    for (const kind of ['working', 'finished', 'calm', 'unknown'] as const) expect(transientPlacement({ kind }, 'core.ephemeral')).toBe('pill');
    expect(transientPlacement({ kind: 'unreachable' }, 'core.engine.wait')).toBe('hidden');
    expect(transientPlacement({ kind: 'unknown' }, 'core.engine.wait')).toBe('pill');
  });
  it('R3 permission rows read in plain words, never the raw grant action', () => {
    const entry = (action: string) => ({ id: `g-${action}`, source: 'backend' as const, kind: 'grant', occurredAt: '2026-11-03T10:00:00Z',
      originator: 'user' as never, label: `grant ${action} Search Index`, grant: { action, subject: 'Search Index' } });
    const rows = derive({ activity: ['ISSUED', 'CONSUMED', 'REVOKED', 'EXPIRED'].map(entry) }).sections.earlier;
    expect(rows.map(r => r.detail)).toEqual(expect.arrayContaining(['Allowed', 'Used', 'Withdrawn', 'Expired']));
    for (const row of rows) {
      expect(row.title).toBe('Permission for Search Index');
      expect(`${row.title} ${row.detail}`).not.toMatch(/grant|ISSUED|CONSUMED|REVOKED|EXPIRED/);
    }
  });
  it('R3 a removal notice keeps its add-again action and plain consequence in Earlier', () => {
    const onAction = () => {};
    const record = { key: 'local:1', origin: 'local', acknowledged: false, sourceRenderHint: 'EPHEMERAL',
      toast: { message: 'Removed Documents from your library', detail: '/home/me/Documents. Adding it again reads its files again.',
        actionLabel: 'Add Documents again', onAction },
      event: { id: 'local:1', classId: 'core.ephemeral', occurredAt: '2026-11-03T10:00:00Z', renderHint: 'EPHEMERAL', severity: 'info',
        classExtras: {}, diagnosticsLink: null, provenance: null, primaryAction: null, primaryActionKind: null, bodyI18nKey: null } } as unknown as AdvisoryRecord;
    const row = derive({ advisories: [record] }).sections.earlier[0]!;
    expect(row.title).toBe('Removed Documents from your library');
    expect(row.detail ?? '').toContain('reads its files again');
    expect(row.actions).toEqual([{ kind: 'advisory', target: 'local:1', label: 'Add Documents again' }]);
  });
  it('R4 folder rows are named by folder with the path as detail, never by collection', () => {
    const roots = [{ pathHash: 'docs', collection: 'default', status: 'indexing', inFlightCount: 3, fileCount: 8 },
      { pathHash: 'docs2', collection: 'default', status: 'indexed', fileCount: 8, walkError: 'INCOMPLETE_COVERAGE' }] as NonNullable<FrameStatusInput['roots']>;
    const m = derive({ roots, rootPaths: new Map([['docs', 'C:\\Users\\me\\Documents\\'], ['docs2', 'C:\\Users\\me\\Documents']]), rootsLive: true });
    const work = m.sections.running.find(f => f.id === 'folder-work:docs')!;
    expect(work.title).toMatch(/^Documents: /);
    expect(work.detail).toBe('C:\\Users\\me\\Documents\\');
    const problem = m.sections.problems.find(f => f.id === 'folder-walk:docs2')!;
    expect(problem.title).toBe('Documents needs attention');
    expect(problem.detail).toContain('C:\\Users\\me\\Documents');
    for (const row of [...m.sections.running, ...m.sections.problems]) expect(row.title).not.toMatch(/default/);
    // Before the path is resolved the row names what it is, not the collection.
    expect(derive({ roots, rootsLive: true }).sections.problems[0]!.title).toBe('A watched folder needs attention');
    expect(folderName('/home/me/Documents/')).toBe('Documents');
  });
});

describe('L1/ST7 the way back after a folder removal, in every window', () => {
  const removal = (removedFolder?: unknown, outcome = 'SUCCESS'): BackendLedgerEntry => ({
    id: 'operation:0194f72c-0000-7000-8000-00000000000a', kind: 'operation', occurredAt: '2026-11-05T09:00:00Z',
    originator: 'user', transport: 'BUTTON', operationId: 'core.remove-watched-root', outcome,
    ...(removedFolder === undefined ? {} : { removedFolder: removedFolder as BackendLedgerEntry['removedFolder'] }),
  });
  const localNotice = (path: string, available = true): AdvisoryRecord => ({ key: 'local:9', origin: 'local', acknowledged: false,
    sourceRenderHint: 'EPHEMERAL', toast: { message: 'Removed Notes from your library', actionLabel: 'Add Notes again', onAction: () => {},
      reversal: true, restoresFolder: path, actionAvailable: () => available },
    event: { id: 'local:9', classId: 'core.own-action', occurredAt: '2026-11-05T09:00:00Z', renderHint: 'EPHEMERAL', severity: 'info',
      classExtras: {}, diagnosticsLink: null, provenance: null, primaryAction: null, primaryActionKind: null, bodyI18nKey: null } } as unknown as AdvisoryRecord);

  it('a window that did not remove the folder (or a reload) offers "Add <folder> again" from the removal record', () => {
    const row = derive({ activity: [projectBackend(removal({ path: 'C:\\Users\\Sam\\Notes', collection: 'reports' }))] })
      .sections.earlier.find(fact => fact.id.startsWith('activity:'))!;
    expect(row.title).toBe('Removed Notes from your library');
    expect(row.detail).toContain('Adding it again reads its files again');
    expect(row.actions![0]).toEqual({ kind: 'add-folder-again', target: 'C:\\Users\\Sam\\Notes', label: 'Add Notes again', collection: 'reports' });
  });

  it('an older removal record without a path, a failed removal and other operations offer no re-add', () => {
    for (const entry of [removal(), removal({ path: '/notes' }, 'FAILURE'), removal({ path: 42 }),
      { ...removal({ path: '/notes' }), operationId: 'core.add-watched-root' }]) {
      const rows = derive({ activity: [projectBackend(entry)] }).sections.earlier;
      expect(rows.flatMap(fact => fact.actions ?? []).map(action => action.kind)).not.toContain('add-folder-again');
    }
  });

  it('the re-add is not offered while the folder is watched again (added back here or elsewhere)', () => {
    const roots = [{ pathHash: 'n', collection: 'default', status: 'indexed', fileCount: 3 }] as NonNullable<FrameStatusInput['roots']>;
    const activity = [projectBackend(removal({ path: 'C:\\Users\\Sam\\Notes\\' }))];
    const back = derive({ activity, roots, rootPaths: new Map([['n', 'c:\\users\\sam\\notes']]), rootsLive: true }).sections.earlier;
    expect(back.flatMap(fact => fact.actions ?? []).map(action => action.kind)).not.toContain('add-folder-again');
  });

  it('the window that removed it shows one row: its own notice, whose action goes once it can no longer run', () => {
    const activity = [projectBackend(removal({ path: '/home/sam/Notes' }))];
    const own = derive({ activity, advisories: [localNotice('/home/sam/Notes')] }).sections.earlier;
    expect(own.map(fact => fact.title)).toEqual(['Removed Notes from your library']);
    expect(own[0]!.actions).toEqual([{ kind: 'advisory', target: 'local:9', label: 'Add Notes again' }]);
    const done = derive({ activity, advisories: [localNotice('/home/sam/Notes', false)] }).sections.earlier;
    expect(done[0]!.actions).toEqual([]);
  });
});

describe('DE6 no Undo where no reversal exists', () => {
  it('a completed operation without a backend undo offers no Undo; a forget offers "Add back to search"', () => {
    const completed = (operationId: string, kind: string | null): AdvisoryRecord => ({ key: operationId, origin: 'stream', acknowledged: false,
      sourceRenderHint: 'PERSISTED', event: { id: operationId, classId: 'operation.completed', occurredAt: '2026-11-05T09:00:00Z',
        renderHint: 'PERSISTED', classExtras: { operationId, outcome: 'SUCCESS' }, diagnosticsLink: null, provenance: null,
        primaryAction: { target: 'core.reindex', defaultArgsJson: null }, primaryActionKind: kind, bodyI18nKey: null } } as unknown as AdvisoryRecord);
    const labels = (record: AdvisoryRecord) => derive({ advisories: [record] }).sections.earlier[0]!.actions!.map(action => action.label);
    expect(labels(completed('core.clear-failed-jobs', null))).not.toContain('Undo');
    expect(labels(completed('core.forget-source', null))).toEqual(['Add back to search']);
    expect(labels(completed('core.file-operations', 'undo'))).toEqual(['Undo']);
  });
});
