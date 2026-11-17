// SPDX-License-Identifier: Apache-2.0
// fm-15 (fm-rest-a) S9 — the status panel's model while reading is paused or held (the hold entry shows):
//  with developer tools off there is no separate entry per folder for a folder that merely waits, and no
//  "Recent file activity" entry; the hold entry stays. With developer tools on both show as today. A folder
//  that needs the person keeps its entry, and file failures keep their problems entry.
import { describe, expect, it } from 'vitest';
import { deriveFrameStatus, type StatusFact } from './frameStatus.js';
import type { AiState } from './aiStateStore.js';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';
import type { Task } from '../substrates/tasks/index.js';

type Input = Parameters<typeof deriveFrameStatus>[0];
const now = Date.parse('2026-11-17T10:00:00Z');

/** Paused by the person with 8 files left, as the packaged app shows after "Pause reading". */
function paused(reasons: readonly string[] = ['user_paused']): AiState {
  return { phase: 'connected', connection: { reachable: true, lastSuccessMs: now, lastContactMs: now, consecutiveFailures: 0 },
    verdict: { kind: 'operational', severity: 'ok', reasons: [] }, stability: { kind: 'settled' },
    aiEngine: { kind: 'not_installed', notRequested: true, stability: { kind: 'settled' }, installFailure: null },
    activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
    status: { worker: { core: { indexState: 'INDEXING', pendingJobs: 8 }, enrichment: {} },
      operating: { indexing: { state: 'PAUSED', reasons, untilEpochMs: null, waitingForResume: true } } },
    installStatus: null, installObservationLive: false,
    indexingStatusLive: true, snapshotLive: true, episodeMaxPendingJobs: 8, enrichSettleSamples: [],
  } as unknown as AiState;
}
const folder = (hash: string, extra: Partial<IndexedRootView> = {}): IndexedRootView => ({ pathHash: hash, collection: 'default',
  status: 'indexed', inFlightCount: 8, failedCount: 0, walkCompleted: true, fileCount: 8, ...extra } as IndexedRootView);
const PATHS = new Map([['docs', '/home/me/Documents'], ['usb', '/media/usb/Photos']]);
/** The file window the Engine streams: two files it last touched. */
const TASKS = [
  { id: 'idxjob:1', label: 'report.pdf', status: 'running' },
  { id: 'idxjob:2', label: 'notes.txt', status: 'completed' },
] as unknown as Task[];

const derive = (input: Partial<Input> = {}) => deriveFrameStatus({ ai: paused(), now, contactLostAt: null, rootPaths: PATHS,
  rootsLive: true, roots: [folder('docs')], tasks: TASKS, ...input });
const ids = (rows: readonly StatusFact[]) => rows.map(row => row.id);
const all = (model: ReturnType<typeof derive>) => ids(Object.values(model.sections).flat());

describe('S9 paused or held, the hold entry speaks for reading', () => {
  it('paused by the person: the hold entry only; no folder entry that merely waits, no "Recent file activity"', () => {
    const model = derive();
    expect(ids(model.sections.needs)).toEqual(['indexing-hold']);
    expect(model.sections.needs[0]!.indexing).toBe(true);
    expect(model.sections.running).toEqual([]);
    expect(all(model).filter(id => id.startsWith('folder-work:'))).toEqual([]);
    expect(Object.values(model.sections).flat().map(row => row.title)).not.toContain('Recent file activity');
  });
  it('held by the reading hours: the same', () => {
    const model = derive({ ai: paused(['schedule']) });
    expect(ids(model.sections.needs)).toEqual(['indexing-hold']);
    expect(model.sections.running).toEqual([]);
  });
  it('kept: with developer tools on, the folder entry and "Recent file activity" show as today', () => {
    const model = derive({ developer: true });
    expect(ids(model.sections.needs)).toEqual(['indexing-hold']);
    const running = model.sections.running;
    expect(ids(running)).toContain('folder-work:docs');
    expect(running.find(row => row.id === 'folder-work:docs')!.title).toBe('Documents: Waiting for Resume · 8 remaining');
    expect(running.map(row => row.title)).toContain('Recent file activity');
  });
  it('kept: a folder that needs the person and a folder with failed files keep their entries', () => {
    const model = derive({ roots: [folder('docs', { failedCount: 2 }), folder('usb', { status: 'unavailable', inFlightCount: 0 })] });
    expect(ids(model.sections.problems)).toEqual(expect.arrayContaining(['files:docs', 'folder:usb']));
    expect(ids(model.sections.needs)).toEqual(['indexing-hold']);
  });
});
