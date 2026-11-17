// SPDX-License-Identifier: Apache-2.0
// fm-15 (fm-rest-a) — the status panel's model while files are being read:
//  S1 one entry for reading in Running; no separate entry per folder for ordinary scanning, reading or
//     enriching, and no "Open AI Brain" on it.
//  S4 with developer tools on, the per-folder entries and the extra action show as before.
//  S5 a folder that needs the person, or whose observation is uncertain, keeps its own entry; "Open AI
//     Brain" stays on an entry whose own words say AI is needed or missing.
//  S6 a successful save of reading rules or hours (core.set-indexing-rules) adds no Earlier entry; a
//     failed one does, developer tools show the successful one, and another operation with the same
//     label still shows.
import { describe, expect, it } from 'vitest';
import { deriveFrameStatus, type StatusFact } from './frameStatus.js';
import type { AiState } from './aiStateStore.js';
import type { AdvisoryRecord } from '../components/advisory/AdvisoryStore.js';
import { projectBackend } from '../operations/ActionLedgerClient.js';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';

type Input = Parameters<typeof deriveFrameStatus>[0];
const now = Date.parse('2026-11-17T10:00:00Z');

function ai(overrides: Partial<AiState> = {}): AiState {
  return { phase: 'connected', connection: { reachable: true, lastSuccessMs: now, lastContactMs: now, consecutiveFailures: 0 },
    verdict: { kind: 'operational', severity: 'ok', reasons: [] }, stability: { kind: 'settled' },
    aiEngine: { kind: 'not_installed', notRequested: true, stability: { kind: 'settled' }, installFailure: null },
    activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
    status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {} } },
    installStatus: null, installObservationLive: false,
    indexingStatusLive: true, snapshotLive: true, episodeMaxPendingJobs: 0, enrichSettleSamples: [], ...overrides,
  } as unknown as AiState;
}
/** fm-15's moment: the Engine reads 8 files (the packaged app's "8 files remaining"). */
const reading = (): AiState => ai({ status: { worker: { core: { indexState: 'INDEXING', pendingJobs: 8 }, enrichment: {} } } as AiState['status'],
  episodeMaxPendingJobs: 8 });

const folder = (hash: string, extra: Partial<IndexedRootView> = {}): IndexedRootView => ({ pathHash: hash, collection: 'default',
  status: 'scanning', inFlightCount: 8, failedCount: 0, walkCompleted: true, fileCount: 8, ...extra } as IndexedRootView);
const PATHS = new Map([['docs', '/home/me/Documents'], ['notes', '/home/me/Notes'], ['usb', '/media/usb/Photos'], ['old', '/home/me/Old']]);

const derive = (input: Partial<Input> = {}) => deriveFrameStatus({ ai: reading(), now, contactLostAt: null, rootPaths: PATHS, rootsLive: true, ...input });
const ids = (rows: readonly StatusFact[]) => rows.map(row => row.id);
const labels = (row: StatusFact | undefined) => (row?.actions ?? []).map(action => action.label);

describe('S1 one entry for reading in Running', () => {
  it('while the Documents folder is read: Running holds the reading entry only, and it offers no "Open AI Brain"', () => {
    const model = derive({ roots: [folder('docs')] });
    expect(ids(model.sections.running)).toEqual(['indexing']);
    const entry = model.sections.running[0]!;
    expect(entry.title).toBe('Reading');
    expect(labels(entry)).not.toContain('Open AI Brain');
  });
  it('a folder still being scanned and one whose meaning work runs are no entries of their own either', () => {
    const scanning = folder('notes', { inFlightCount: 0, walkCompleted: false, status: 'scanning' });
    const model = derive({ roots: [folder('docs'), scanning] });
    expect(ids(model.sections.running)).toEqual(['indexing']);
    expect(ids(Object.values(model.sections).flat()).filter(id => id.startsWith('folder-work:'))).toEqual([]);
  });
});

describe('S4 developer tools keep the per-folder entries and the extra action', () => {
  it('with developer tools on, the folder entry and "Open AI Brain" show as before', () => {
    const model = derive({ roots: [folder('docs')], developer: true });
    expect(ids(model.sections.running).sort()).toEqual(['folder-work:docs', 'indexing']);
    expect(labels(model.sections.running.find(row => row.id === 'indexing'))).toEqual(['Open AI Brain']);
    expect(labels(model.sections.running.find(row => row.id === 'folder-work:docs'))).toEqual(['Show in Library']);
  });
});

describe('S5 what needs the person keeps its own entry while reading runs', () => {
  it('a missing folder and a folder that could not be read completely keep their entry and Show in Library', () => {
    const model = derive({ roots: [folder('docs'), folder('usb', { status: 'unavailable', inFlightCount: 0 }),
      folder('old', { walkError: 'INCOMPLETE_COVERAGE', inFlightCount: 0 })] });
    expect(ids(model.sections.problems)).toEqual(expect.arrayContaining(['folder:usb', 'folder-walk:old']));
    for (const id of ['folder:usb', 'folder-walk:old']) expect(labels(model.sections.problems.find(row => row.id === id))).toEqual(['Show in Library']);
    expect(ids(model.sections.running)).toEqual(['indexing']);
  });
  it('a folder with failed files keeps its entry', () => {
    const model = derive({ roots: [folder('docs', { failedCount: 2 })] });
    expect(ids(model.sections.problems)).toContain('files:docs');
  });
  it('a folder removal that did not finish keeps its entry', () => {
    const model = derive({ roots: [folder('docs'), folder('old', { walkError: 'Removal incomplete. Try again.', inFlightCount: 0 })] });
    expect(ids(model.sections.problems)).toContain('folder-walk:old');
  });
  it('an uncertain folder observation keeps the folder\'s own entry', () => {
    const model = derive({ roots: [folder('docs')], rootsLive: false });
    expect(ids(model.sections.running).sort()).toEqual(['folder-work:docs', 'indexing']);
    expect(model.sections.running.find(row => row.id === 'folder-work:docs')!.stale).toBe(true);
  });
  it('a folder held by a retry wait keeps its own entry', () => {
    const waiting = ai({ status: { worker: { core: { indexState: 'WAITING', pendingJobs: 3 },
      failure: { retryPendingJobs: 3, nextRetryAtMs: now + 60_000 }, enrichment: {} } } as unknown as AiState['status'], episodeMaxPendingJobs: 3 });
    const model = derive({ ai: waiting, roots: [folder('docs', { inFlightCount: 3 })] });
    expect(model.progress.phase).toBe('waiting');
    expect(ids(Object.values(model.sections).flat())).toContain('folder-work:docs');
  });
  it('"Open AI Brain" stays on the entry that says AI is needed', () => {
    const needsAi = ai({ aiEngine: { kind: 'offline', stability: { kind: 'settled' }, installFailure: null } as AiState['aiEngine'],
      status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0, indexedDocuments: 7, searchableDocuments: 7 }, enrichment: {
        embeddingEnabled: false, spladeEnabled: false, nerEnabled: false, embeddingDocCount: 7, embeddingPendingCount: 7,
        chunk: { chunkDocCount: 3, chunkEmbeddingPendingCount: 3, chunkVectorsReady: false } } } } as unknown as AiState['status'] });
    const entry = derive({ ai: needsAi }).sections.needs.find(row => row.id === 'indexing')!;
    expect(entry.title).toMatch(/\bAI\b/);
    expect(labels(entry)).toEqual(['Open AI Brain']);
  });
});

describe('S6 a saved reading rule is no Earlier entry', () => {
  const save = (outcome: 'SUCCESS' | 'FAILURE', operationId = 'core.set-indexing-rules', id = 'save-1') => projectBackend({
    id, kind: 'operation', operationId, outcome, originator: 'user', occurredAt: '2026-11-17T09:59:00Z' });
  const earlier = (input: Partial<Input>) => ids(derive({ ai: ai(), ...input }).sections.earlier);
  it('two successful saves add nothing under Earlier', () => {
    expect(earlier({ activity: [save('SUCCESS'), save('SUCCESS', 'core.set-indexing-rules', 'save-2')] })).toEqual([]);
  });
  it('a failed save shows', () => {
    expect(earlier({ activity: [save('FAILURE')] })).toEqual(['activity:save-1']);
  });
  it('with developer tools on the successful save shows', () => {
    expect(earlier({ activity: [save('SUCCESS')], developer: true })).toEqual(['activity:save-1']);
  });
  it('another operation that merely carries the same label still shows', () => {
    const other = { ...save('SUCCESS', 'acme.set-reading-rules', 'other-1'), label: 'Set reading rules' };
    const model = derive({ ai: ai(), activity: [other] });
    expect(model.sections.earlier.map(row => [row.id, row.title])).toEqual([['activity:other-1', 'Set reading rules']]);
  });
  it('the save\'s completion notice is left out too, and kept when it failed', () => {
    const notice = (outcome: string): AdvisoryRecord => ({ key: `n-${outcome}`, origin: 'stream', acknowledged: false, sourceRenderHint: 'PERSISTED', event: {
      id: `n-${outcome}`, classId: 'operation.completed', occurredAt: '2026-11-17T09:59:00Z', renderHint: 'PERSISTED',
      classExtras: { operationId: 'core.set-indexing-rules', outcome }, diagnosticsLink: null, provenance: null,
      primaryAction: null, bodyI18nKey: null } } as unknown as AdvisoryRecord);
    expect(earlier({ advisories: [notice('SUCCESS')] })).toEqual([]);
    expect(earlier({ advisories: [notice('FAILURE')] })).toEqual(['advisory:n-FAILURE']);
  });
});
