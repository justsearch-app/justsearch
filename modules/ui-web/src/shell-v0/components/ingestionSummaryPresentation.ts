// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

// Projection of IndexingController.handleIngestionOutcomeSummary / KnowledgeClient's map-backed
// payload (no generated record schema exists). Unknown codes remain valid wire values.
export const ingestionSummarySchema = z.object({
  rollups: z.array(z.object({
    outcomeClass: z.string(),
    reasonCode: z.string(),
    retryPolicy: z.string(),
    count: z.number().int().nonnegative(),
    lastObservedAtMs: z.number().int().nonnegative(),
  })),
  count: z.number().int().nonnegative(),
});
export type IngestionRollup = z.infer<typeof ingestionSummarySchema>['rollups'][number];

/** Wording projection of Worker IngestionReasonCodes, including its multiline constants. */
export const INGESTION_REASON_LABELS: Readonly<Record<string, string>> = {
  SUCCESS: 'Content indexed',
  SUCCESS_PARTIAL: 'Content indexed up to the extraction limit',
  SUCCESS_EMPTY: 'No usable content extracted',
  EXTRACTION_DROPOUT_PENDING_FALLBACK: 'No text extracted; another extraction method is queued',
  EXTRACTION_DROPOUT_UNRECOVERED: 'No text recovered by the available extraction methods',
  SKIPPED_TEMP_OR_SYSTEM: 'Temporary or system file excluded',
  UNCHANGED: 'File unchanged since its last index',
  NON_REGULAR_SOURCE: 'Source is not a regular file',
  MISSING_AT_PROCESSING: 'File missing when processing began',
  DELETED_OR_MISSING: 'File deleted or missing',
  STALE_AFTER_EXTRACTION: 'File changed during extraction',
  DELETED_AFTER_SNAPSHOT: 'File deleted after it was checked',
  SIZE_CHANGED_AFTER_SNAPSHOT: 'File size changed after it was checked',
  MODIFIED_TIME_CHANGED_AFTER_SNAPSHOT: 'File modification time changed after it was checked',
  FILE_KEY_CHANGED_AFTER_SNAPSHOT: 'File replaced after it was checked',
  SOURCE_KIND_CHANGED_AFTER_SNAPSHOT: 'Source type changed after it was checked',
  UNREADABLE: 'File could not be read',
  IO_ERROR: 'File access failed',
  INPUT_TOO_LARGE: 'File exceeds the input size limit',
  OFFICE_INPUT_TOO_LARGE: 'Office document exceeds the input size limit',
  PARSER_FAILED: 'Content extraction failed',
  PARSER_TIMEOUT: 'Content extraction timed out',
  SANDBOX_FAILED: 'Isolated content extraction failed',
  WRITE_FAILED: 'Index write failed',
  WRITE_UNAVAILABLE_DRAINING: 'Index writes paused while the worker drains',
  CLOUD_PLACEHOLDER: 'Cloud-only file is not available locally',
};

// These are labels for the ledger's outcome axis, not current folder readiness or retry policy.
const OUTCOMES: Readonly<Record<string, { label: string; status: string }>> = {
  SUCCESS_FULL: { label: 'Indexed', status: 'success' },
  SUCCESS_PARTIAL: { label: 'Indexed partially', status: 'warning' },
  SUCCESS_EMPTY: { label: 'Indexed without content', status: 'neutral' },
  SKIPPED_POLICY: { label: 'Skipped', status: 'neutral' },
  DEFERRED_POLICY: { label: 'Deferred', status: 'neutral' },
  STALE_SOURCE: { label: 'Source changed', status: 'neutral' },
  UNSUPPORTED: { label: 'Unsupported', status: 'neutral' },
  BUDGET_EXCEEDED: { label: 'Limit exceeded', status: 'warning' },
  PARSER_FAILED: { label: 'Extraction failed', status: 'failed' },
  PARSER_TIMEOUT: { label: 'Extraction timed out', status: 'failed' },
  IO_FAILED: { label: 'File access failed', status: 'failed' },
  WRITE_FAILED: { label: 'Index write failed', status: 'failed' },
  WRITE_UNAVAILABLE_DRAINING: { label: 'Write deferred', status: 'neutral' },
  SANDBOX_FAILED: { label: 'Extraction failed', status: 'failed' },
};

export function ingestionReasonLabel(code: string): string {
  return Object.hasOwn(INGESTION_REASON_LABELS, code)
    ? INGESTION_REASON_LABELS[code]!
    : 'Reason not recognized by this version';
}

export function ingestionOutcomeLabel(code: string): { label: string; status: string } {
  return Object.hasOwn(OUTCOMES, code)
    ? OUTCOMES[code]!
    : { label: 'Outcome unknown', status: 'neutral' };
}

/** Retry-policy subgroups are combined only for display; no retry decision is made here. */
export function ingestionSummaryRows(rollups: IngestionRollup[]): IngestionRollup[] {
  const rows = new Map<string, IngestionRollup>();
  for (const row of rollups) {
    const key = JSON.stringify([row.outcomeClass, row.reasonCode]);
    const prior = rows.get(key);
    rows.set(key, prior ? {
      ...row, count: prior.count + row.count,
      lastObservedAtMs: Math.max(prior.lastObservedAtMs, row.lastObservedAtMs),
    } : { ...row });
  }
  return [...rows.values()].sort((a, b) => b.count - a.count
    || a.outcomeClass.localeCompare(b.outcomeClass) || a.reasonCode.localeCompare(b.reasonCode));
}
