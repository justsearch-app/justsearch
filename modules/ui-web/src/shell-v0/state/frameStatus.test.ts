// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { deriveFrameStatus, completedLongWork, FINISHED_DWELL_MS, authorizationDeadline,
  type StatusFact } from './frameStatus.js';
import type { AiState } from './aiStateStore.js';
import type { AdvisoryRecord } from '../components/advisory/AdvisoryStore.js';
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
    // The retained broadcast is history only: it can never execute the decision a second time.
    expect(m.sections.earlier.find(f => f.id === 'advisory:auth-adv')!.actions).toEqual([]);
    // Answered elsewhere / withdrawn: the request leaves, the history stays inert.
    const answered = derive({ authorizations: [], advisories: [broadcast] });
    expect(answered.sections.needs).toEqual([]);
    expect(answered.sections.earlier[0]!.actions).toEqual([]);
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
    const m = derive({ ai: ai(), roots: [root], rootsLive: true });
    const row = m.sections.problems.find(f => f.id === 'folder-walk:locked');
    expect(row?.title).toBe('Locked needs attention');
    expect(row?.detail).not.toContain('INCOMPLETE_COVERAGE');
    expect(row?.actions?.[0]?.target).toBe('core.library-surface');
    expect(m.pill.kind).toBe('problems');
    expect(m.pill.label).not.toBe('Up to date');
    const unverified = derive({ ai: ai(), roots: [{ ...root, walkError: '', deleteDetectionUnverified: true }], rootsLive: true });
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
  it('ST7 unread sorts ahead of a newer read item and remains separate from seen and active state', () => {
    const read = advisory(true); const unread = { ...advisory(), key: 'older', event: { ...read.event, occurredAt: '2025-01-01T00:00:00Z' } };
    expect(derive({ advisories: [read, unread] }).sections.earlier.map(f => f.id)).toEqual(['advisory:older', 'advisory:h1']);
  });
  it.each(['available', 'repair_required'])('ST4 update %s retains its existing Settings action', state => {
    expect(derive({ update: { state, availableVersion: '1.2.3' } as FrameStatusInput['update'] }).sections.needs[0]!.actions![0]!.target).toBe('core.settings-surface');
    expect(derive({ update: { state: 'idle' } as FrameStatusInput['update'] }).sections.needs).toEqual([]);
  });
});
