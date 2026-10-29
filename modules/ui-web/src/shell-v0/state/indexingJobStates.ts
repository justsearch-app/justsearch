// SPDX-License-Identifier: Apache-2.0
/**
 * Indexing-job state vocabulary — the shell-side spelling of `IndexingJobView.STATE_*` (app-api).
 *
 * Tempdoc 885 item 21b — `RETRY_EXHAUSTED` is the terminal state meaning "we retried for the whole
 * seven-day window and never managed to read this file". Distinct from `FAILED`, which means the
 * file itself is unreadable (a parse failure, or the untyped attempts cap).
 *
 * ONE spelling, because three surfaces classify on it — the Activity ledger
 * (`operations/ActionLedgerClient.ts`), the tasks tray (`substrates/tasks/indexingJobsBridge.ts`)
 * and the failed-files drawer (`components/FailedJobsDrawer.ts`) — and the backend record that owns
 * the vocabulary says in as many words that "a projection that must classify a state cannot invent
 * its own spelling". Three literals is three chances to typo one into the silent `default` arm.
 *
 * It lives beside its consumers rather than in `api/domains/indexing.ts` (the HTTP client for the
 * indexing endpoints) deliberately: that module has no live importer at all, so hanging a live
 * symbol off it makes one reachable export the sole reason a dead module is retained.
 */
export const JOB_STATE_RETRY_EXHAUSTED = 'RETRY_EXHAUSTED';

/**
 * #758 — failed-files surface only: the file exists but cannot be read right now
 * (locked by another program, access denied). The queue keeps retrying with growing back-off and no
 * time limit; nothing is removed. Never the same as a missing file, which leaves the index.
 */
export const JOB_STATE_SOURCE_UNREADABLE = 'SOURCE_UNREADABLE';

/** As `JOB_STATE_SOURCE_UNREADABLE`, for a file whose indexed content is kept and searchable. */
export const JOB_STATE_INDEXED_SOURCE_UNREADABLE = 'INDEXED_SOURCE_UNREADABLE';
