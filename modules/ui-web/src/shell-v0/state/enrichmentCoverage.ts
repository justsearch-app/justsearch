// SPDX-License-Identifier: Apache-2.0
/**
 * enrichmentCoverage.ts — the ONE answer to "is enrichment still catching up?" (809 finding 1).
 *
 * THE DEFECT IT EXISTS FOR: text extraction and the Lucene write finish fast; embedding, SPLADE and
 * NER run AFTERWARDS on a separate backfill scheduler. During that window keyword search works and
 * semantic/hybrid search does not — yet the Library row already showed the terminal "✓ indexed",
 * claiming a capability the system does not have yet. The completion claim has to consult coverage,
 * not just job-queue drain. Wiring, not backend: every counter below is already on `/api/status`.
 *
 * THE TRAP THIS DERIVATION EXISTS TO AVOID (809 finding 9): the DOC-level counters read fully healthy
 * during an active PASSAGE-level backfill — measured live, `/api/knowledge/status` reported
 * `embeddingCoveragePercent: 100.0`, `queueDepth: 0`, `pendingJobsCount: 0` while
 * `chunkEmbeddingPendingCount` was 1554 and `chunkVectorsReady` was false. A gate reading only the
 * doc-level counter is the wrong-gate mistake with a green light on it, so this reads BOTH levels.
 *
 * POSITIVE EVIDENCE ONLY — the same doctrine `availability.indexingInFlight` follows. A tier counts as
 * catching up when its own PENDING counter is > 0 and the tier is not disabled. Absent/undefined
 * fields are UNKNOWN, never "pending": we do not attach a caveat we cannot witness. And it keys on
 * PENDING WORK, never on a `ready`/coverage flag alone — a tier that is not ready with nothing pending
 * is a stalled/failed condition (Health's subject), not "still computing", and gating the row on it
 * would pin a permanent caveat that never clears.
 *
 * SCOPE HONESTY: these counters are INDEX-WIDE. The backfill batches documents without recording which
 * root they came from (809 finding 2), so "this folder is 40% enriched" is not derivable today. A
 * consumer may therefore say only that enrichment is in flight, never that it is in flight *for this
 * folder* — which is why the caveat below names the capability, not a per-folder percentage.
 *
 * Pure data → data; no DOM, no IO, no store import.
 */
import type { StatusSnapshot } from '../utils/statusPoll.js';

/** The enrichment tiers a completion claim depends on, in the order a user meets them. */
export type EnrichmentTier = 'document-embeddings' | 'passage-embeddings' | 'sparse-terms' | 'entities';

export interface EnrichmentProgress {
  /** True when at least one enabled tier still has pending work (positive evidence). */
  readonly pending: boolean;
  /** Which tiers are still catching up — for a surface that wants to name them. */
  readonly tiers: readonly EnrichmentTier[];
}

/**
 * The caveat a completion claim carries while enrichment is still running. Authored ONCE here so a
 * second surface projects it rather than inventing a second way of saying the same thing (809 finding
 * 2's "we already have an honest vocabulary elsewhere" constraint). Says what IS true (keyword search
 * works) before what is not yet.
 */
export const ENRICHMENT_CATCHING_UP_CAVEAT = 'keyword search ready · semantic search still catching up';

/**
 * The caveat a completion claim carries when the semantic layers CANNOT be built at all — no
 * embedding service, so the documents that lack vectors will never be enriched by any amount of
 * waiting (round-15 F1/F1b: coverage 0 + no model + empty queue rendered as "fully searchable").
 *
 * Distinct in SUBJECT from {@link ENRICHMENT_CATCHING_UP_CAVEAT}, which promises work in flight:
 * this one promises nothing, because nothing is running. Same clause order — what already works
 * first, then what does not — so the two tiers read as one vocabulary rather than two moods.
 */
export const ENRICHMENT_BLOCKED_CAVEAT =
  'keyword search ready · semantic search waiting for AI install';

/**
 * The short STATUS label for the blocked state — {@link ENRICHMENT_IN_PROGRESS_LABEL}'s twin, for a
 * card whose subject is the work (Health's queue card). Its terminal "Up to date" is a coverage
 * claim (813 §4), and coverage is exactly what is missing here.
 */
export const ENRICHMENT_BLOCKED_LABEL = 'Semantic search waiting for AI install';

/**
 * The short status label for a card whose subject is the WORK, not a folder — Health's queue card,
 * whose "Up to date" is an explicit terminal trust close (630 D1) that a running backfill contradicts.
 * Deliberately the phrase the Brain surface's own progress card already uses for this exact work, so
 * the two name the same thing the same way rather than inventing a second vocabulary.
 */
export const ENRICHMENT_IN_PROGRESS_LABEL = 'Building semantic search';

/**
 * 813 §19 (W1) — the BODY tier of a progress surface whose own phase noun ("Enriching") is already
 * carried by its headline or label prefix: the one clause naming what is still being built. Distinct
 * in subject from the two constants above — {@link ENRICHMENT_CATCHING_UP_CAVEAT} qualifies a
 * COMPLETION claim (so it leads with what already works), and {@link ENRICHMENT_IN_PROGRESS_LABEL}
 * is a card's short STATUS label — while this is the in-progress body text itself.
 *
 * Promoted here because it was authored verbatim at two sites (the Tasks panel's aggregate counts
 * line and the Health "Now" strip's enrichment row) outside the shared-vocabulary module, which is
 * the fork this module exists to prevent.
 */
export const ENRICHMENT_BODY = 'semantic search catching up';

const positive = (n: number | undefined | null): boolean => typeof n === 'number' && n > 0;

/** Disabled tiers are excluded; an absent flag means "not declared off", so the tier still counts. */
const enabled = (flag: boolean | undefined | null): boolean => flag !== false;

/**
 * Derive whether the enrichment backfill still owes work, from the `/api/status` snapshot.
 *
 * Reads `worker.enrichment` — the one block carrying all four tiers AND their enabled flags
 * (`WorkerStatusMapper.java:140-163`). The top-level `status.embedding` block mirrors the DOC-level
 * embedding numbers only; it is deliberately not consulted, so there is one reader, not two.
 */
export function enrichmentProgress(status: StatusSnapshot | null | undefined): EnrichmentProgress {
  const e = status?.worker?.enrichment;
  if (!e) return { pending: false, tiers: [] };
  const tiers: EnrichmentTier[] = [];
  if (enabled(e.embeddingEnabled) && positive(e.embeddingPendingCount)) {
    tiers.push('document-embeddings');
  }
  // The passage tier is the one the doc-level counters hide (finding 9's diagnostic trap).
  if (enabled(e.embeddingEnabled) && positive(e.chunk?.chunkEmbeddingPendingCount)) {
    tiers.push('passage-embeddings');
  }
  if (enabled(e.spladeEnabled) && positive(e.spladePendingCount)) {
    tiers.push('sparse-terms');
  }
  if (enabled(e.nerEnabled) && positive(e.pendingNerCount)) {
    tiers.push('entities');
  }
  return { pending: tiers.length > 0, tiers };
}
