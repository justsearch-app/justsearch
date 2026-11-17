// SPDX-License-Identifier: Apache-2.0
/**
 * First minutes: one name per thing. The app "reads" files; its features are "search by meaning" and
 * "word search". These cases call the functions that produce the words a user reads and pin the new
 * sentences; the guard at the end keeps the old names out of the display text of the same files.
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import type { StatusResponse } from '../api/generated/index.js';
import {
  ENRICHMENT_INCOMPLETE_CAVEAT,
  KEYWORD_FALLBACK_CAVEAT,
  PASSAGE_REDUCED_CAVEAT,
  readinessNotice,
  reasonFor,
} from './state/readinessNotice.js';
import { verdictBody, type SystemHealthVerdict } from './state/verdict.js';
import { aiEngineBody, type AiEngineVerdict } from './state/aiVerdict.js';
import { selectIndexingProgress } from './state/indexingProgress.js';
import {
  ENRICHMENT_BLOCKED_CAVEAT,
  ENRICHMENT_BLOCKED_LABEL,
  ENRICHMENT_BODY,
  ENRICHMENT_CATCHING_UP_CAVEAT,
  ENRICHMENT_IN_PROGRESS_LABEL,
} from './state/enrichmentCoverage.js';
import { indexResultWords, rowOutcomeWords, sourceWords } from './operations/activityWords.js';
import { indexLabel, projectBackend, type BackendLedgerEntry } from './operations/ActionLedgerClient.js';
import type { FeedRow } from './operations/activityFeed.js';
import { DEGRADATION_REASON_WORDING, userSummaryParts } from './aggregate-substrate/strategies/searchTraceExplain.js';

const verdict = (kind: SystemHealthVerdict['kind'], severity: SystemHealthVerdict['severity'], reasons: string[]): SystemHealthVerdict =>
  ({ kind, severity, reasons });

describe('readiness notices say reading, search by meaning and word search', () => {
  it('words each cause row in the first-minutes names', () => {
    const wording = (code: string) => reasonFor(code).wording;
    expect(wording('indexing.paused_schedule')).toBe('Outside reading hours.');
    expect(wording('indexing.pausing_schedule')).toBe('Pausing — finishing current files. Outside reading hours.');
    expect(wording('indexing.pausing_settings_recovery_check_exclusions')).toBe('Pausing — finishing current files. Settings recovery — saved settings were damaged and replaced by defaults. Reading stays held until the reset is confirmed in Settings. Check exclusions.');
    expect(wording('index.throughput_stalled')).toBe('Reading has stalled');
    expect(wording('index.throughput_degraded')).toBe('Reading is slower than usual');
    expect(wording('no_documents')).toBe('No files read yet');
    expect(wording('ocr.disabled')).toBe('OCR is disabled, so scanned text cannot be read yet');
    expect(wording('inference.gpu_yielded_to_indexing')).toBe('The GPU is reading your files — chat resumes when it finishes');
    expect(wording('encoders.health.embedding_not_ready')).toBe('Search by meaning is not ready');
    expect(wording('chunk_embedding.not_ready')).toBe('Passage-level search by meaning is not ready');
    expect(wording('chunk_embedding.in_progress')).toBe('Passage-level search by meaning is still being prepared');
    expect(wording('encoders.health.semantic_stuck')).toBe('Search by meaning is stalled');
    expect(wording('encoders.reloading')).toBe('Search by meaning models are reloading');
    expect(wording('index.blocked_legacy')).toBe('The index was built before search by meaning was available — rebuild it to enable meaning-based results.');
    expect(wording('index.embedding_legacy')).toBe("Search by meaning isn't available on this index yet — rebuild it to enable meaning-based results.");
    expect(wording('index.embedding_rebuilding')).toBe('Search by meaning is being rebuilt — word results are complete, ranking by meaning resumes when it finishes.');
    expect(wording('index.embedding_mismatch')).toBe('The embedding model changed — rebuild the index to restore search by meaning.');
    expect(wording('index.dense_unavailable')).toBe('Search by meaning is unavailable right now — showing word results.');
    expect(wording('index.rebuild_brake_exhausted')).toBe('The index could not be rebuilt after repeated attempts — search still works on what is already read, but new files are not being added. Rebuild the index to try again.');
  });

  it('words the banner headlines and bodies in the first-minutes names', () => {
    const paused = readinessNotice(verdict('transitioning', 'warn', ['rebuilding', 'source:embedding_model_change', 'index.embedding_rebuilding']));
    expect(paused?.headline).toBe('Search by meaning paused; word search available.');
    expect(paused?.body).toBe('Word search remains available while the index is rebuilt; ranking by meaning resumes afterward.');

    const reindex = readinessNotice(verdict('degraded', 'warn', ['index.embedding_mismatch']));
    expect(reindex?.headline).toBe('Reindex required.');
    expect(reindex?.body).toBe('Search by meaning is degraded until the index is rebuilt — results may be word search only.');

    const schema = readinessNotice(verdict('degraded', 'info', ['index.schema_mismatch']));
    expect(schema?.body).toBe('Search is fully working — both search by meaning and word search are active. Rebuilding will pick up newer index features.');

    const passage = readinessNotice(verdict('degraded', 'warn', ['chunk_embedding.not_ready']));
    expect(passage?.headline).toBe('Search by meaning partially degraded.');
    expect(passage?.body).toBe('Passage-level precision is reduced while passage data for search by meaning is missing — results are still ranked by meaning.');

    const ai = readinessNotice(verdict('degraded', 'warn', ['inference.offline']));
    expect(ai?.body).toBe('Search is fully working — both search by meaning and word search are active. Chat and answer features are unavailable until the AI model is online.');

    const impaired = readinessNotice(verdict('degraded', 'warn', ['index.dense_unavailable']));
    expect(impaired?.headline).toBe('Search by meaning degraded.');
    expect(impaired?.body).toBe('Showing word results; relevance ranking may be reduced.');
  });

  it('words the shared caveats in the first-minutes names', () => {
    expect(KEYWORD_FALLBACK_CAVEAT).toBe('Showing keyword-ranked results — ranking by meaning is degraded');
    expect(ENRICHMENT_INCOMPLETE_CAVEAT).toBe("Results may miss some documents' semantic matches; word search covers them");
    expect(PASSAGE_REDUCED_CAVEAT).toBe('Passage-level precision is reduced — results are still ranked by meaning');
  });

  it('words the rebuild verdict body in the first-minutes names', () => {
    expect(verdictBody(verdict('transitioning', 'warn', ['rebuilding', 'index.embedding_rebuilding'])))
      .toBe('Search by meaning is paused while the index is rebuilt; word search remains available.');
  });
});

describe('progress and coverage say reading and search by meaning', () => {
  it('says the AI engine prepares search by meaning', () => {
    expect(aiEngineBody({ kind: 'indexing' } as AiEngineVerdict)).toBe('Preparing search by meaning…');
  });

  it('names a blocked embedding stage as search by meaning and the fallback as word search', () => {
    const status = { worker: {
      core: { indexState: 'IDLE', pendingJobs: 0 },
      enrichment: { backfillMode: 'idle', embeddingEnabled: true, embeddingAvailable: false, embeddingPendingCount: 1, spladePendingCount: 5 },
      migration: {},
    } } as StatusResponse;
    const progress = selectIndexingProgress(status, true, 0, []);
    expect(progress.phase).toBe('blocked');
    expect(progress.activity.summary).toBe('Search by meaning unavailable');
    expect(progress.activity.detail).toContain('Embedding cannot run right now. Word search remains available.');
  });

  it('words the coverage caveats and labels', () => {
    expect(ENRICHMENT_CATCHING_UP_CAVEAT).toBe('word search ready · search by meaning still catching up');
    expect(ENRICHMENT_BLOCKED_CAVEAT).toBe('word search ready · search by meaning waiting for AI install');
    expect(ENRICHMENT_BLOCKED_LABEL).toBe('Search by meaning waiting for AI install');
    expect(ENRICHMENT_IN_PROGRESS_LABEL).toBe('Preparing search by meaning');
    expect(ENRICHMENT_BODY).toBe('search by meaning catching up');
  });
});

describe('Activity says the app read files', () => {
  it('words a reading record, its outcome and its source', () => {
    expect(indexLabel('DONE', 'docs')).toBe('Read · docs');
    expect(indexLabel('FAILED', 'docs')).toBe("Couldn't read · docs");
    expect(indexLabel('RETRY_EXHAUSTED', 'docs')).toBe('Gave up reading · docs');
    expect(indexLabel('PAUSED', 'docs')).toBe('Reading outcome unknown (PAUSED) · docs');
    expect(indexResultWords('DONE')).toBe('Read');
    expect(rowOutcomeWords('index', 'DONE')).toBe('Read');
    const row = { history: [], ledger: { transport: 'WORKER_INDEXER' } } as unknown as FeedRow;
    expect(sourceWords(row)).toBe('Background reading');
  });

  it('words a scan as reading a number of files, singular and plural', () => {
    const scan = (over: Partial<BackendLedgerEntry>) => projectBackend({
      id: 'operation:2026-05-28T00:00:00.000Z:core.scan-root', kind: 'operation', operationId: 'core.scan-root',
      occurredAt: '2026-05-28T00:00:00.000Z', originator: 'system', outcome: 'COMPLETED', scanId: 'scan-1',
      collection: 'docs', docsDone: 2, docsFailed: 0, docsAdmitted: 2, durationMs: 45_000, ...over,
    }).label;
    expect(scan({})).toBe('Read 2 files · docs · 45s');
    expect(scan({ docsDone: 1, docsAdmitted: 1 })).toBe('Read 1 file · docs · 45s');
    expect(scan({ outcome: 'STARTED' })).toBe('Reading · docs…');
  });
});

describe('the search trace says search by meaning and word search', () => {
  it('words the retrieval mode and the meaning-ranking reasons', () => {
    expect(userSummaryParts({ version: 1, effectiveMode: 'TEXT' } as Parameters<typeof userSummaryParts>[0])).toEqual(['Word search']);
    expect(userSummaryParts({ version: 1, effectiveMode: 'VECTOR' } as Parameters<typeof userSummaryParts>[0])).toEqual(['Search by meaning']);
    expect(DEGRADATION_REASON_WORDING.INITIALIZING).toBe('search by meaning is still warming up');
    expect(DEGRADATION_REASON_WORDING.SEMANTIC_BUDGET_EXCEEDED).toBe('search by meaning did not answer in time');
    expect(DEGRADATION_REASON_WORDING.SKIPPED_NO_DISCRIMINATIVE_TERM).toBe('Ranking by meaning was skipped — every word in this query is common across your documents.');
    const blocked = userSummaryParts({ version: 1, effectiveMode: 'TEXT', degradation: { vectorBlocked: true, vectorBlockedReason: 'LEGACY_INDEX_NO_FINGERPRINT' } } as Parameters<typeof userSummaryParts>[0]);
    expect(blocked).toContain('ranking by meaning blocked — the index needs a one-time rebuild');
  });
});

/**
 * Guard: the old names stay out of the display text of the files this change reworded.
 *
 * How display text is told from the rest, kept simple: block comments, HTML comments and line
 * comments are removed first; every forbidden phrase contains a space, which no id, key, code, enum
 * value, test id, CSS class or route in these files has (they use dots, dashes and underscores), so
 * whatever still matches is text a user can read or hear. A literal that must keep an old phrase
 * (compared with Engine text, say) goes on ALLOWED with its reason.
 */
const GUARDED_FILES = [
  'state/readinessNotice.ts', 'state/verdict.ts', 'state/aiVerdict.ts', 'state/indexingProgress.ts',
  'state/enrichmentCoverage.ts', 'operations/activityWords.ts', 'operations/ActionLedgerClient.ts',
  'components/ActionLedgerView.ts', 'components/ActivityDetails.ts', 'views/ActivitySurface.ts',
  'controllers/FolderAddFlow.ts', 'components/DragOverlay.ts', 'components/EngineLoss.ts',
  'views/SettingsSurface.ts', 'views/SystemSelfView.ts', 'views/HelpSurface.ts', 'views/BrainSurface.ts',
  'aggregate-substrate/strategies/searchTraceExplain.ts',
];
const FORBIDDEN = /semantic search|keyword search|keyword results|indexing hours|documents indexed/i;
const ALLOWED: ReadonlyArray<{ file: string; text: string; why: string }> = [];

function displayLines(source: string): string[] {
  const code = source
    .replace(/\/\*[\s\S]*?\*\//g, (block) => block.replace(/[^\n]/g, ' '))
    .replace(/<!--[\s\S]*?-->/g, (block) => block.replace(/[^\n]/g, ' '));
  return code.split('\n').map((line) => line.replace(/(^|\s)\/\/.*$/, ''));
}

describe('guard: no old names in display text', () => {
  const here = dirname(fileURLToPath(import.meta.url));
  it.each(GUARDED_FILES)('%s', (file) => {
    const hits = displayLines(readFileSync(resolve(here, file), 'utf8'))
      .map((line, index) => ({ line: index + 1, text: line.trim() }))
      .filter(({ text }) => FORBIDDEN.test(text))
      .filter(({ text }) => !ALLOWED.some((allowed) => allowed.file === file && text.includes(allowed.text)));
    expect(hits).toEqual([]);
  });
});
