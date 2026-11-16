// SPDX-License-Identifier: Apache-2.0
// Slice 8 (fm-15) — the status panel's model in the first minutes: one entry per thing in the user's
// words. "Search works" only when established (H2'); one "Installing AI" row with fm-12's amounts, time
// left and Pause/Resume (H3'); one row per folder keyed by its path hash, and an Earlier "Your <folder>
// folder is searchable" only for what this window witnessed (H3'', H6); operation names, successful
// gate history, add-folder receipts and "Draft kept" out of the default view, back with developer tools
// (H4'); everything that needs the person keeps its row (S8-5).
import { describe, expect, it } from 'vitest';
import { deriveFrameStatus, type StatusFact } from './frameStatus.js';
import type { AiState } from './aiStateStore.js';
import type { AdvisoryRecord } from '../components/advisory/AdvisoryStore.js';
import type { UnifiedActionEntry } from '../operations/ActionLedgerClient.js';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';
import type { Task } from '../substrates/tasks/index.js';

type Input = Parameters<typeof deriveFrameStatus>[0];
type Model = ReturnType<typeof deriveFrameStatus>;
const now = Date.parse('2026-11-16T10:00:00Z');
const MIN = 60_000;
const GIB = 1024 ** 3;
// fm-12's picture: "1.3 of 2.6 GB · about 24 min left".
const PICTURED = { state: 'running', paused: false, phase: 'download', totalBytes: 2.6 * GIB, downloadedBytes: 1.3 * GIB, remainingSeconds: 24 * 60 };
/** The search verdict a real Engine without AI models reports (unrequestedAi.test.ts's fresh install): only AI parts missing. */
const AI_ABSENT = { kind: 'degraded', severity: 'warn',
  reasons: ['index.dense_unavailable', 'lambdamart.not_configured', 'inference.deactivated', 'encoders.health.embedding_not_ready'] } as AiState['verdict'];
/** fm-15's moment: AI installs, the search verdict is still the AI-absent one. */
const installing = (overrides: Partial<AiState> = {}, install: Record<string, unknown> = PICTURED) => ai({ verdict: AI_ABSENT, ...overrides }, install);

function ai(overrides: Partial<AiState> = {}, install: Record<string, unknown> | null = null): AiState {
  return { phase: 'connected', connection: { reachable: true, lastSuccessMs: now, lastContactMs: now, consecutiveFailures: 0 },
    verdict: { kind: 'operational', severity: 'ok', reasons: [] }, stability: { kind: 'settled' },
    aiEngine: { kind: install ? 'installing' : 'not_installed', stability: { kind: 'settled' }, installFailure: null },
    activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
    status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {} } },
    installStatus: install, installObservationLive: install !== null,
    indexingStatusLive: true, snapshotLive: true, episodeMaxPendingJobs: 0, enrichSettleSamples: [], ...overrides,
  } as unknown as AiState;
}
const firstUse = (searchable: number | null) => ({ visible: false, known: true, currentStep: 2, searchable });
const derive = (input: Partial<Input> = {}): Model => deriveFrameStatus({ ai: ai(), now, contactLostAt: null, gettingStarted: firstUse(8), ...input });
const all = (model: Model): StatusFact[] => Object.values(model.sections).flat();
const titles = (model: Model): string[] => all(model).map(row => row.title);

const READY: IndexedRootView = { pathHash: 'h-docs', collection: 'default', status: 'indexed', lastIndexedIsoTime: '2026-11-16T09:00:00Z',
  inFlightCount: 0, failedCount: 0, walkCompleted: true, fileCount: 8 } as IndexedRootView;
const READING: IndexedRootView = { ...READY, lastIndexedIsoTime: undefined, status: 'scanning', inFlightCount: 5 } as IndexedRootView;
const PATHS = new Map([['h-docs', '/home/me/Documents']]);
/** One window's observations in order: each derive gets the memory the previous one returned. */
function observe(steps: readonly { at: number; roots: readonly IndexedRootView[]; paths?: ReadonlyMap<string, string>; live?: boolean; ai?: AiState }[]): Model[] {
  const models: Model[] = [];
  let folders: Model['folders'];
  for (const step of steps) {
    const model = derive({ now: step.at, roots: step.roots, rootPaths: step.paths ?? PATHS, rootsLive: step.live ?? true,
      ...(step.ai ? { ai: step.ai } : {}), ...(folders ? { folders } : {}) });
    folders = model.folders;
    models.push(model);
  }
  return models;
}
const searchableRow = (model: Model) => model.sections.earlier.find(row => /folder is searchable$/.test(row.title));

describe('S8-1 / H3\' the first minutes: one "Installing AI" entry', () => {
  const stageTasks: Task[] = [
    { id: 'aiinstall:search', label: 'Downloading AI · Search', status: 'running', progress: 0.5, originSurfaceId: 'core.brain-surface' },
    { id: 'aiinstall:chat', label: 'Downloading AI · Chat', status: 'queued', originSurfaceId: 'core.brain-surface' },
  ] as Task[];
  it('"Search works", then Running "Installing AI" with "1.3 of 2.6 GB · about 24 min left" and Pause, no stage rows', () => {
    const model = derive({ ai: installing(), tasks: stageTasks });
    expect(model.sentence).toBe('Search works');
    expect(model.sections.running.map(row => row.title)).toEqual(['Installing AI']);
    const row = model.sections.running[0]!;
    expect(row.detail).toBe('1.3 of 2.6 GB · about 24 min left');
    expect(row.actions!.map(action => action.label)).toContain('Pause');
    expect(row.actions).toContainEqual({ kind: 'install-toggle', target: 'pause', label: 'Pause' });
    expect(titles(model).join('\n')).not.toMatch(/Downloading AI/);
  });
  it('paused: "Installing AI · Paused" with Resume (7b\'s words), no time left', () => {
    const row = derive({ ai: ai({}, { ...PICTURED, paused: true }) }).sections.running[0]!;
    expect([row.title, row.after]).toEqual(['Installing AI', 'Paused']);
    expect(row.actions).toContainEqual({ kind: 'install-toggle', target: 'resume', label: 'Resume' });
    expect(row.actions!.map(action => action.label)).not.toContain('Pause');
    expect(row.detail).toBe('1.3 of 2.6 GB');
  });
  it.each([
    ['unknown amounts', { totalBytes: 0, downloadedBytes: 0 }, /^(?!.* of ).*$/],
    ['no estimate', { remainingSeconds: 0 }, /^1\.3 of 2\.6 GB$/],
    ['preparing', { phase: 'plan' }, /^Preparing the download$/],
    ['verifying', { packages: [{ id: 'embedding', state: 'verifying' }] }, /^Checking the downloaded files$/],
    ['setting up', { phase: 'apply' }, /^Setting up the downloaded parts$/],
  ])('ETA only while downloading with an estimate, amounts only when known: %s', (_case, patch, expected) => {
    const row = derive({ ai: ai({}, { ...PICTURED, ...patch }) }).sections.running.find(r => r.id === 'ai-install')!;
    expect(row.detail ?? '').toMatch(expected);
    expect(row.detail ?? '').not.toMatch(/left/);
  });
  it('a blocked stage\'s reason shows on the install row instead of its own row', () => {
    const install = { ...PICTURED, stages: [{ stage: 'search', label: 'Search', state: 'blocked', blockedReason: 'Not enough disk space' }] };
    const model = derive({ ai: ai({}, install), tasks: [
      { id: 'aiinstall:search', label: 'Downloading AI · Search — Not enough disk space', status: 'failed', originSurfaceId: 'core.brain-surface' } as Task] });
    expect(model.sections.running.map(row => row.title)).toEqual(['Installing AI']);
    expect(model.sections.earlier).toEqual([]);
    expect(model.sections.running[0]!.note).toContain('Not enough disk space');
  });
  it('a failed stage with no reason the row can carry keeps its own row', () => {
    const install = { ...PICTURED, stages: [{ stage: 'search', label: 'Search', state: 'failed' }] };
    const model = derive({ ai: ai({}, install), tasks: [
      { id: 'aiinstall:search', label: 'Downloading AI · Search', status: 'failed', originSurfaceId: 'core.brain-surface' } as Task] });
    expect(titles(model)).toContain('Downloading AI · Search');
  });
  it('S8-7\' after a reload the install is still one entry', () => {
    const fresh = derive({ ai: ai({}, PICTURED), tasks: stageTasks });
    expect(all(fresh).filter(row => /Installing AI|Downloading AI/.test(row.title))).toHaveLength(1);
  });
});

describe('H2\' "Search works" only when established', () => {
  it.each(['checking', 'connecting', 'transitioning', 'unreachable'] as const)('not while the search verdict is %s', kind => {
    expect(derive({ ai: ai({ verdict: { kind, severity: 'info', reasons: [] } as AiState['verdict'] }) }).sentence).toBeUndefined();
  });
  it.each([[0], [null]])('not with %s fresh own searchable files', searchable => {
    expect(derive({ gettingStarted: firstUse(searchable) }).sentence).toBeUndefined();
  });
  it('not from a stale observation', () => {
    expect(derive({ ai: ai({ phase: 'stale' }) }).sentence).toBeUndefined();
  });
  it('operational with searchable files: "Search works"', () => {
    expect(derive().sentence).toBe('Search works');
  });
  // RV8-1 — a searchable count the indexing observation no longer backs is not fresh evidence.
  it.each([
    ['the worker RPC is stale', { meta: { workerRpcStale: true } }],
    ['the indexing source is stale', { operating: { freshness: { staleSources: ['indexing'] } } }],
  ])('not while %s, even with a positive searchable count', (_case, patch) => {
    const status = { worker: { core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {} }, ...patch } as unknown as AiState['status'];
    expect(derive({ ai: ai({ status }) }).sentence).not.toBe('Search works');
    expect(derive({ ai: installing({ status }) }).sentence).not.toBe('Search works');
  });
  it('an outage says what today\'s summary says, never "Search works"', () => {
    const lost = derive({ ai: ai({ connection: { reachable: false, lastSuccessMs: now - MIN, lastContactMs: now - MIN, consecutiveFailures: 3 } }),
      contactLostAt: now - MIN });
    expect(lost.sentence).toBe("Can't reach the engine");
    const limited = derive({ ai: ai({ verdict: { kind: 'degraded', severity: 'error', reasons: ['index.failed'] } as AiState['verdict'] }) });
    expect(limited.sections.problems.map(row => row.title)).toContain('Search limited');
    expect(limited.sentence).toBe('Search limited');
  });
});

describe('H2\'\' "Search works" while only AI parts are missing (exact-word search runs)', () => {
  it('the pictured install (AI not installed or installing, only AI reasons, searchable files): "Search works"', () => {
    expect(derive({ ai: installing() }).sentence).toBe('Search works');
    expect(derive({ ai: installing({ aiEngine: { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null } as AiState['aiEngine'] }, { ...PICTURED, state: 'idle' }) }).sentence)
      .toBe('Search works');
  });
  it.each(['index.failed', 'index.corrupt', 'component.start_deadline'])('not when the degraded verdict carries a search-unavailable cause (%s)', code => {
    const model = derive({ ai: installing({ verdict: { ...AI_ABSENT, severity: 'error', reasons: [...AI_ABSENT.reasons, code] } }) });
    expect(model.sentence).not.toBe('Search works');
    expect(model.sentence).toBe('Search limited');
  });
  it('not while the pill says search is limited (a reindex cause)', () => {
    const model = derive({ ai: installing({ verdict: { ...AI_ABSENT, reasons: [...AI_ABSENT.reasons, 'index.embedding_mismatch'] } }) });
    expect(model.pill.kind).toBe('limited');
    expect(model.sentence).toBe('Search limited');
  });
  it('not while the engine is unreachable', () => {
    const model = derive({ ai: installing({ connection: { reachable: false, lastSuccessMs: now - MIN, lastContactMs: now - MIN, consecutiveFailures: 3 } }),
      contactLostAt: now - MIN });
    expect(model.pill.kind).toBe('unreachable');
    expect(model.sentence).toBe("Can't reach the engine");
  });
  it('not from a stale observation', () => {
    expect(derive({ ai: installing({ phase: 'stale' }) }).sentence).toBeUndefined();
  });
  it.each([[0], [null]])('not with %s fresh own searchable files', searchable => {
    expect(derive({ ai: installing(), gettingStarted: firstUse(searchable) }).sentence).toBeUndefined();
  });
});

describe('H3\'\' one row per folder; Earlier only for what this window witnessed', () => {
  it('reading, then searchable: one Earlier row with its age from the witnessed moment, no file count, no operation row', () => {
    const [reading, ready, later, hours] = observe([{ at: now, roots: [READING] }, { at: now + MIN, roots: [READY] },
      { at: now + 13 * MIN, roots: [READY] }, { at: now + MIN + 3 * 60 * MIN, roots: [READY] }]);
    expect(reading!.sections.running.map(row => row.id)).toContain('folder-work:h-docs');
    expect(searchableRow(reading!)).toBeUndefined();
    const row = searchableRow(ready!)!;
    expect(row).toMatchObject({ title: 'Your Documents folder is searchable', section: 'earlier', age: 'just now' });
    expect(row.detail).toBeUndefined();
    expect(JSON.stringify(row)).not.toMatch(/8 files/);
    expect(ready!.sections.running.map(row => row.id)).not.toContain('folder-work:h-docs');
    expect(searchableRow(later!)).toMatchObject({ age: '12 min' });
    expect(searchableRow(hours!)).toMatchObject({ age: '3 h' });
    expect(all(later!).filter(row => row.id.endsWith('h-docs'))).toHaveLength(1);
  });
  it('a folder already searchable at the first snapshot gets no Earlier row (H6)', () => {
    const models = observe([{ at: now, roots: [READY] }, { at: now + MIN, roots: [READY] }]);
    for (const model of models) expect(searchableRow(model)).toBeUndefined();
  });
  it('path resolution and refresh do not reset the age', () => {
    const [, unresolved, resolved] = observe([{ at: now, roots: [READING], paths: new Map() },
      { at: now + MIN, roots: [READY], paths: new Map() }, { at: now + 6 * MIN, roots: [READY] }]);
    expect(searchableRow(unresolved!)).toMatchObject({ title: 'A watched folder is searchable', age: 'just now' });
    expect(searchableRow(resolved!)).toMatchObject({ title: 'Your Documents folder is searchable', age: '5 min' });
  });
  it('leaving searchable removes the Earlier row and shows the latest state; a later searchable is new', () => {
    const [, , again, back] = observe([{ at: now, roots: [READING] }, { at: now + MIN, roots: [READY] },
      { at: now + 2 * MIN, roots: [READING] }, { at: now + 10 * MIN, roots: [READY] }]);
    expect(searchableRow(again!)).toBeUndefined();
    expect(again!.sections.running.map(row => row.id)).toContain('folder-work:h-docs');
    expect(searchableRow(back!)).toMatchObject({ age: 'just now' });
  });
  it('removed, then added back: the earlier row goes; the re-added folder is new', () => {
    const [, , removed, readded, readyAgain] = observe([{ at: now, roots: [READING] }, { at: now + MIN, roots: [READY] },
      { at: now + 2 * MIN, roots: [] }, { at: now + 3 * MIN, roots: [READING] }, { at: now + 4 * MIN, roots: [READY] }]);
    expect(searchableRow(removed!)).toBeUndefined();
    expect(searchableRow(readded!)).toBeUndefined();
    expect(searchableRow(readyAgain!)).toMatchObject({ age: 'just now' });
  });
  it('a folder without a stable id gets no Earlier row', () => {
    const models = observe([{ at: now, roots: [{ ...READING, pathHash: undefined } as IndexedRootView] },
      { at: now + MIN, roots: [{ ...READY, pathHash: undefined } as IndexedRootView] }]);
    expect(searchableRow(models[1]!)).toBeUndefined();
  });
  it('an unconfirmed folder observation witnesses nothing', () => {
    const [, stale] = observe([{ at: now, roots: [READING] }, { at: now + MIN, roots: [READY], live: false }]);
    expect(searchableRow(stale!)).toBeUndefined();
  });
  it('a rebuild that leaves a folder searchable is not "became searchable"; its rows say nothing of reading while it runs', () => {
    const rebuilding = ai({ stability: { kind: 'provisional', cause: 'rebuilding' } as AiState['stability'] });
    const [, during, after] = observe([{ at: now, roots: [READY] }, { at: now + MIN, roots: [READING], ai: rebuilding }, { at: now + 2 * MIN, roots: [READY] }]);
    expect(during!.sections.running.map(row => row.id)).not.toContain('folder-work:h-docs');
    expect(searchableRow(after!)).toBeUndefined();
  });
  // RV8-2 — the row shows only while the folder is searchable now; the witnessed moment is remembered.
  it('witnessed searchable, then a rebuild or an unconfirmed count: no row; searchable again: the row with its first age', () => {
    const rebuilding = ai({ stability: { kind: 'provisional', cause: 'rebuilding' } as AiState['stability'] });
    const countFailed = { ...READY, inFlightCount: null } as unknown as IndexedRootView;
    const [, ready, during, unknown, back] = observe([{ at: now, roots: [READING] }, { at: now + MIN, roots: [READY] },
      { at: now + 3 * MIN, roots: [READY], ai: rebuilding }, { at: now + 5 * MIN, roots: [countFailed] }, { at: now + 11 * MIN, roots: [READY] }]);
    expect(searchableRow(ready!)).toMatchObject({ age: 'just now' });
    expect(searchableRow(during!)).toBeUndefined();
    expect(searchableRow(unknown!)).toBeUndefined();
    expect(searchableRow(back!)).toMatchObject({ title: 'Your Documents folder is searchable', age: '10 min' });
  });
  // RV8-5 — a failed roots refresh keeps the last list (rootsLive false): the row needs live folder evidence.
  it('witnessed searchable, then a failed folder refresh: no row; live again: the row with its first age', () => {
    const [, ready, failed, live] = observe([{ at: now, roots: [READING] }, { at: now + MIN, roots: [READY] },
      { at: now + 3 * MIN, roots: [READY], live: false }, { at: now + 7 * MIN, roots: [READY] }]);
    expect(searchableRow(ready!)).toMatchObject({ age: 'just now' });
    expect(searchableRow(failed!)).toBeUndefined();
    expect(searchableRow(live!)).toMatchObject({ title: 'Your Documents folder is searchable', age: '6 min' });
  });
  // RV8-6 — a confirmed removal (forgetIndexedRoots drops the folder, the list stays unconfirmed) forgets it.
  it('witnessed searchable, failed refresh, then removed: adding it back already searchable shows no old row', () => {
    const [, , , removed, readded] = observe([{ at: now, roots: [READING] }, { at: now + MIN, roots: [READY] },
      { at: now + 2 * MIN, roots: [READY], live: false }, { at: now + 3 * MIN, roots: [], live: false },
      { at: now + 4 * MIN, roots: [READY] }]);
    expect(searchableRow(removed!)).toBeUndefined();
    expect(searchableRow(readded!)).toBeUndefined();
  });
  it('the retained failure count still reaches the folder\'s problem row while it is read again', () => {
    const model = derive({ roots: [READING], rootPaths: PATHS, rootsLive: true, lastKnownFailed: { 'h-docs': 3 } });
    expect(model.sections.problems.map(row => row.title)).toContain('3 files need review · Documents · last known');
  });
  it('a successful add-folder operation leaves no Earlier row; its failure stays', () => {
    const add = (id: string, outcome: string): UnifiedActionEntry => ({ id, source: 'backend', kind: 'operation', occurredAt: '2026-11-16T09:59:00Z',
      originator: 'user', label: 'Add Watched Folder', operationId: 'core.add-watched-root', outcome });
    const ok = derive({ activity: [add('a1', 'SUCCESS')] });
    expect(titles(ok)).not.toContain('Add Watched Folder');
    const advisory = derive({ advisories: [operationAdvisory('core.add-watched-root', 'SUCCESS')] });
    expect(advisory.sections.earlier).toEqual([]);
    const failed = derive({ activity: [add('a2', 'FAILURE')] });
    expect(failed.sections.earlier.map(row => [row.title, row.detail])).toEqual([['Add Watched Folder', 'Failed']]);
    expect(titles(derive({ activity: [add('a1', 'SUCCESS')], developer: true }))).toContain('Add Watched Folder');
  });
});

function operationAdvisory(operationId: string, outcome: string): AdvisoryRecord {
  return { key: `op-${outcome}`, origin: 'stream', acknowledged: false, sourceRenderHint: 'PERSISTED', event: {
    id: `op-${outcome}`, classId: 'operation.completed', occurredAt: '2026-11-16T09:59:00Z', renderHint: 'PERSISTED',
    classExtras: { operationId, outcome }, diagnosticsLink: null, provenance: null, primaryAction: null, bodyI18nKey: null,
  } } as AdvisoryRecord;
}

describe('H4\' words: what the default panel leaves out, and what developer tools bring back', () => {
  const gate = (id: string, pendingId: string, disposition: string, operation: string): UnifiedActionEntry => ({ id, source: 'backend', kind: 'gate',
    occurredAt: '2026-11-16T09:58:00Z', originator: 'agent', label: `${disposition} ${operation}`, gate: { disposition, operation, pendingId } });
  const run = (id: string, allowedBy: string, outcome: string, label: string): UnifiedActionEntry => ({ id, source: 'backend', kind: 'operation',
    occurredAt: '2026-11-16T09:59:00Z', originator: 'agent', label, outcome, allowedBy });
  const grant = (id: string, allowedBy: string): UnifiedActionEntry => ({ id, source: 'backend', kind: 'grant', occurredAt: '2026-11-16T09:58:30Z',
    originator: 'agent', label: 'grant ISSUED', allowedBy, grant: { action: 'ISSUED', subject: 'Reindex everything' } });
  it('"Allowed: …" history is out of the default view and back with developer tools', () => {
    const activity = [gate('g1', 'p1', 'APPROVED', 'Add Watched Folder'), run('o1', 'p1', 'SUCCESS', 'Add Watched Folder'), grant('k1', 'p1')];
    expect(titles(derive({ activity })).join('\n')).not.toMatch(/Allowed|Permission/);
    const dev = derive({ activity, developer: true });
    expect(dev.sections.earlier.map(row => [row.title, row.detail])).toContainEqual(['Allowed: Add Watched Folder', 'Allowed · completed']);
  });
  it('an allowed operation that failed becomes its own row in the default view, in today\'s failure words', () => {
    const activity = [gate('g2', 'p2', 'APPROVED', 'Reindex everything'), run('o2', 'p2', 'FAILURE', 'Reindex everything')];
    const model = derive({ activity });
    expect(model.sections.earlier.map(row => [row.title, row.detail, row.tone])).toEqual([['Reindex everything', 'Failed', 'failure']]);
    const partial = derive({ activity: [gate('g3', 'p3', 'APPROVED', 'Reindex everything'), run('o3', 'p3', 'PARTIAL', 'Reindex everything')] });
    expect(partial.sections.earlier.map(row => [row.title, row.detail])).toEqual([['Reindex everything', 'Finished incomplete']]);
    const dev = derive({ activity, developer: true });
    expect(dev.sections.earlier.map(row => [row.title, row.detail])).toEqual([['Allowed: Reindex everything', 'Allowed · failed']]);
  });
  // RV8-3 — only successful permission history (issued, used) leaves the default view.
  it('withdrawn and expired permissions keep their rows; an issued or used one is out of the default view', () => {
    const permission = (action: string): UnifiedActionEntry => ({ id: `k-${action}`, source: 'backend', kind: 'grant', occurredAt: '2026-11-16T09:58:30Z',
      originator: 'agent', label: `grant ${action}`, grant: { action, subject: 'Search Index' } });
    const model = derive({ activity: ['ISSUED', 'CONSUMED', 'REVOKED', 'EXPIRED'].map(permission) });
    expect(model.sections.earlier.map(row => [row.title, row.detail])).toEqual([
      ['Permission for Search Index', 'Withdrawn'], ['Permission for Search Index', 'Expired']]);
  });
  it('a denial keeps its row', () => {
    expect(titles(derive({ activity: [gate('g4', 'p4', 'DENIED', 'Reindex everything')] }))).toContain('Denied by you: Reindex everything');
  });
  it('a retained "Allowed" request notice and "Draft kept" are out of the default view', () => {
    const allowed = { key: 'auth', origin: 'stream', acknowledged: false, sourceRenderHint: 'PERSISTED', event: { id: 'auth', classId: 'authorization.pending',
      occurredAt: '2026-11-16T09:58:00Z', renderHint: 'PERSISTED', classExtras: { pendingId: 'p9', disposition: 'APPROVED' }, diagnosticsLink: null,
      provenance: null, primaryAction: null, bodyI18nKey: null } } as AdvisoryRecord;
    const draft = { key: 'draft', origin: 'local', acknowledged: false, sourceRenderHint: 'EPHEMERAL', toast: { message: 'Draft kept' }, event: { id: 'draft',
      classId: 'core.draft-kept', occurredAt: '2026-11-16T09:58:00Z', renderHint: 'EPHEMERAL', classExtras: {}, diagnosticsLink: null,
      provenance: null, primaryAction: null, bodyI18nKey: null } } as AdvisoryRecord;
    expect(derive({ advisories: [allowed, draft] }).sections.earlier).toEqual([]);
    expect(titles(derive({ advisories: [allowed, draft], developer: true }))).toEqual(expect.arrayContaining(['Allowed', 'Draft kept']));
  });
});

describe('S8-5 what needs the person keeps its row', () => {
  it('an approval keeps its target, risk and irreversibility; failures and reduced search keep theirs', () => {
    const model = derive({ authorizations: [{ pendingId: 'p', operationId: 'core.reindex', argsSummary: '/home/me/Documents',
      riskTier: 'HIGH', undoSupported: false, arrival: 'needs-you' }] });
    const review = model.sections.needs[0]!.review!;
    expect(review.lines.map(line => line.text)).toEqual(['/home/me/Documents', 'High risk', 'No undo: it cannot be reversed']);
    const failed = derive({ roots: [{ ...READY, walkError: 'ACCESS_DENIED' } as IndexedRootView], rootPaths: PATHS, rootsLive: true });
    expect(failed.sections.problems.map(row => row.title)).toContain('Documents needs attention');
    const reduced = derive({ ai: ai({ verdict: { kind: 'degraded', severity: 'error', reasons: ['index.failed'] } as AiState['verdict'] }) });
    expect(reduced.sections.problems[0]!.actions!.map(action => action.label)).toEqual(['Health']);
  });
});
