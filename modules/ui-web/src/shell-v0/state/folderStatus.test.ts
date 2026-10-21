import { describe, it, expect } from 'vitest';
import { folderStatus } from './folderStatus';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view';

// Tempdoc 599 §9.1/§9.5 — the seam's invariant + precedence. The load-bearing regression is
// "a searchable tier ⟹ inFlight === 0 && failed === 0" and "a walk timestamp alone never yields
// ready" (the §8.1 false-terminal fix).
//
// 809 finding 1 + tempdoc 813 §4 split the DRAINED outcome into four honest arms:
//   a. per-root coverage known, incomplete            → `enriching` + the shared caveat + percent
//   b. per-root coverage known, complete              → `ready` ("fully searchable") — even while the
//                                                       index-wide backfill still owes OTHER roots work
//   c. coverage unknowable, backfill pending index-wide → `enriching` + caveat, NO percent
//   d. coverage unknowable, nothing pending           → the pre-813 terminal wording
// The base `row()` below deliberately carries NO coverage fields and `ctx()` defaults
// `enrichmentPending: false`, so every pre-813 case keeps exercising arm d; the tier cases opt in.

const row = (over: Partial<IndexedRootView> = {}): IndexedRootView => ({
  pathHash: 'h',
  collection: 'default',
  fileCount: 10,
  lastIndexedIsoTime: '2026-06-17T00:00:00Z',
  status: 'indexed',
  walkError: '',
  inFlightCount: 0,
  failedCount: 0,
  walkCompleted: true,
  ...over,
});

const ctx = (
  over: Partial<{
    relativeTime: string;
    verifiedRelativeTime: string;
    provisional: boolean;
    enrichmentStages: { embedding: boolean; splade: boolean; ner: boolean } | null;
    enrichmentPending: boolean;
    enrichmentBlocked: boolean;
  }> = {},
) => ({
  relativeTime: 'just now',
  verifiedRelativeTime: '',
  provisional: false,
  enrichmentPending: false,
  enrichmentBlocked: false,
  ...over,
});

/** All three parent stages on — the ordinary deployment (813 §4 applicability). */
const ALL_STAGES = { embedding: true, splade: true, ner: true };

/** The shared caveat wording, authored once in `enrichmentCoverage.ts` (809 finding 2). */
const CAVEAT = 'keyword search ready · semantic search still catching up';

/** Its round-15 F1b twin: the semantic stage cannot run at all. */
const BLOCKED_CAVEAT = 'keyword search ready · semantic search waiting for AI install';

/**
 * The applicability the round captured: no embedding service ⟹ every stage inapplicable. Note that
 * this is ALSO what a fully-enriched index looks like after the model is deleted — the two states
 * are indistinguishable by flags, which is why the row consults the index-wide blocked evidence
 * rather than the flags themselves.
 */
const NO_STAGES = { embedding: false, splade: false, ner: false };

describe('folderStatus', () => {
  it('a searchable tier ⟹ inFlight === 0 && failed === 0 (the core invariant)', () => {
    // Exhaustive small grid: the searchable outcomes — 809/813 made that two states, `ready` and
    // `enriching` — are the ONLY drained, failure-free, scanned ones. Both tiers are checked, over
    // both enrichment gates (this root's own coverage AND the index-wide fallback boolean), so
    // neither can become a back door around the §8.1 drain requirement.
    for (const inFlight of [0, 1, 5]) {
      for (const failed of [0, 1]) {
        for (const coverage of [
          {},
          { parentDocsTotalEmbedding: 10, parentDocsSettledEmbedding: 4 },
        ]) {
          for (const enrichmentPending of [false, true]) {
            const fs = folderStatus(
              row({ inFlightCount: inFlight, failedCount: failed, ...coverage }),
              ctx({ enrichmentStages: ALL_STAGES, enrichmentPending }),
            );
            if (fs.state === 'ready' || fs.state === 'enriching') {
              expect(inFlight).toBe(0);
              expect(failed).toBe(0);
            }
          }
        }
      }
    }
  });

  it('a walk timestamp alone never yields ready while jobs are in flight (the §8.1 fix)', () => {
    // status='indexed' + lastIndexed set (walk done) BUT 5 jobs still draining → indexing, not ready.
    const fs = folderStatus(row({ inFlightCount: 5 }), ctx());
    expect(fs.state).toBe('indexing');
    expect(fs.glyph).toBe('pending');
    expect(fs.metaText).toContain('5 remaining');
  });

  it('ready: drained + scanned + no failures → ✓ glyph + "indexed" meta (arm d: nothing knowable)', () => {
    const fs = folderStatus(row(), ctx({ relativeTime: '2 minutes ago' }));
    expect(fs.state).toBe('ready');
    expect(fs.glyph).toBe('indexed');
    expect(fs.metaText).toContain('indexed 2 minutes ago');
    // With no faithful denominator AND no index-wide pending evidence the row asserts neither tier,
    // it just stays as it was.
    expect(fs.metaText).not.toContain(CAVEAT);
    expect(fs.metaText).not.toContain('fully searchable');
    expect(fs.metaText).not.toContain('%');
  });

  it('ready: shows the §Recency "Verified" heartbeat when lastVerified is known', () => {
    // Tempdoc 626 §Recency — a calm ✓ proves it is fresh by showing WHEN it was last verified.
    const fs = folderStatus(row(), ctx({ relativeTime: '2 minutes ago', verifiedRelativeTime: 'just now' }));
    expect(fs.state).toBe('ready');
    expect(fs.metaText).toContain('Verified just now');
  });

  it('ready: omits the "Verified" suffix when never verified (empty time)', () => {
    const fs = folderStatus(row(), ctx({ verifiedRelativeTime: '' }));
    expect(fs.state).toBe('ready');
    expect(fs.metaText).not.toContain('Verified');
  });

  it('unverified: indexed but deletions unverified → caution glyph, NEVER the green ✓ (tempdoc 626 §Axis-C)', () => {
    // An otherwise-ready folder whose reconcile cap-skipped delete-detection must NOT show "indexed".
    const fs = folderStatus(row({ deleteDetectionUnverified: true }), ctx());
    expect(fs.state).toBe('unverified');
    expect(fs.glyph).toBe('unverified');
    expect(fs.glyph).not.toBe('indexed');
    expect(fs.metaText).toContain('reindex to be sure');
  });

  it('unverified is a caveat on READY only — active states outrank it', () => {
    // While indexing (jobs in flight), the unverified flag does not override the indexing state.
    const indexing = folderStatus(row({ deleteDetectionUnverified: true, inFlightCount: 3 }), ctx());
    expect(indexing.state).toBe('indexing');
    // A path-missing folder stays 'unavailable' regardless of the unverified flag.
    const gone = folderStatus(row({ deleteDetectionUnverified: true, status: 'unavailable' }), ctx());
    expect(gone.state).toBe('unavailable');
  });

  it('scanning: walk in progress (no lastIndexed, walk NOT completed) → Scanning…', () => {
    const fs = folderStatus(
      row({ status: 'pending', lastIndexedIsoTime: '', walkCompleted: false, fileCount: -1 }),
      ctx({ relativeTime: '' }),
    );
    expect(fs.state).toBe('scanning');
    expect(fs.glyph).toBe('pending');
    expect(fs.metaText).toContain('Scanning');
  });

  it('empty: walk COMPLETED but admitted zero files (the Fix 1 distinction vs scanning)', () => {
    const fs = folderStatus(
      row({ status: 'pending', lastIndexedIsoTime: '', walkCompleted: true, fileCount: 0, inFlightCount: 0 }),
      ctx({ relativeTime: '' }),
    );
    expect(fs.state).toBe('empty');
    expect(fs.metaText).toContain('No indexable files');
  });

  it('Fix 1: same wire fields, walkCompleted flips scanning↔empty', () => {
    const base = { status: 'pending', lastIndexedIsoTime: '', inFlightCount: 0, fileCount: -1 } as const;
    expect(folderStatus(row({ ...base, walkCompleted: false }), ctx({ relativeTime: '' })).state).toBe('scanning');
    expect(folderStatus(row({ ...base, walkCompleted: true }), ctx({ relativeTime: '' })).state).toBe('empty');
  });

  it('failed: drained but terminal failures → error glyph + failed count on the structured field', () => {
    // tempdoc 599 §16/B1: the failed count rides `failed` (rendered as a clickable chip), not metaText.
    const fs = folderStatus(row({ inFlightCount: 0, failedCount: 3 }), ctx());
    expect(fs.state).toBe('failed');
    expect(fs.glyph).toBe('error');
    expect(fs.failed).toBe(3);
  });

  it('failed takes the walkError path with the error message', () => {
    const fs = folderStatus(row({ walkError: 'Access denied' }), ctx());
    expect(fs.state).toBe('failed');
    expect(fs.glyph).toBe('error');
    expect(fs.metaText).toContain('Access denied');
  });

  it('indexing surfaces a failed sub-count on the structured field while still in flight', () => {
    const fs = folderStatus(row({ inFlightCount: 4, failedCount: 2 }), ctx());
    expect(fs.state).toBe('indexing');
    expect(fs.metaText).toContain('4 remaining');
    expect(fs.failed).toBe(2); // surfaced via the clickable chip, not the prose meta
  });

  it('indexed folder with fileCount 0 (files deleted post-index) is still ready, not empty', () => {
    // status='indexed' (had files at walk time) → ready even if the live FS count is now 0.
    const fs = folderStatus(row({ fileCount: 0 }), ctx());
    expect(fs.state).toBe('ready');
  });

  it('unavailable: path-missing status → muted glyph + reconnect remedy + last-known count (A1, §17.3)', () => {
    const fs = folderStatus(
      row({
        status: 'unavailable',
        walkError: 'No such file or directory',
        lastIndexedIsoTime: '',
        fileCount: 10,
      }),
      ctx({ relativeTime: '' }),
    );
    expect(fs.state).toBe('unavailable');
    expect(fs.glyph).toBe('unavailable');
    expect(fs.metaText).toContain('Folder not found');
    // §17.3 — keep the last-known count (last-known, not "0"); the folder is disconnected, not empty.
    expect(fs.metaText).toContain('last known 10 files');
  });

  it('unavailable: count unknown (-1) → remedy only, never a "0 files" (A1, §17.3)', () => {
    const fs = folderStatus(
      row({ status: 'unavailable', walkError: 'ROOT_NOT_DIRECTORY', fileCount: -1 }),
      ctx({ relativeTime: '' }),
    );
    expect(fs.state).toBe('unavailable');
    expect(fs.metaText).toContain('Folder not found');
    expect(fs.metaText).not.toContain('last known');
    expect(fs.metaText).not.toContain('0 files');
  });

  it('provisional (global rebuild) overrides everything → unknown + last-known', () => {
    const fs = folderStatus(row({ inFlightCount: 5 }), ctx({ provisional: true }));
    expect(fs.state).toBe('unknown');
    expect(fs.glyph).toBe('pending');
    expect(fs.metaText).toContain('Rebuilding');
  });
});

// Tempdoc 813 §4 — arms a + b: the PER-ROOT coverage tier. The regression these pin: a folder whose
// jobs drained but whose semantic layers are 40% done must SAY so (the shared caveat plus its own
// percent) instead of the pre-813 unqualified "indexed", and a folder with no faithful coverage
// denominator must NOT gain a fabricated percent.
describe('folderStatus — per-root enrichment tier (813 §4)', () => {
  const covered = (over: Partial<IndexedRootView> = {}): IndexedRootView =>
    row({
      fileCount: 312,
      parentDocsTotalEmbedding: 100,
      parentDocsTotalSplade: 100,
      parentDocsTotalNer: 100,
      parentDocsSettledEmbedding: 100,
      parentDocsSettledSplade: 100,
      parentDocsSettledNer: 100,
      chunkDocsTotal: 100,
      chunkDocsSettled: 100,
      ...over,
    });

  it('S33.5 zero settled stays enriching without a fabricated starting percentage', () => {
    for (const pending of [false, true]) {
      const fs = folderStatus(covered({ parentDocsSettledEmbedding: 0,
        parentDocsSettledSplade: 0, parentDocsSettledNer: 0, chunkDocsSettled: 0 }),
        ctx({ enrichmentStages: ALL_STAGES, enrichmentPending: pending }));
      expect(fs.state).toBe('enriching');
      expect(fs.metaText).toContain(CAVEAT);
      expect(fs.metaText).not.toMatch(/\d+%/);
    }
  });

  it('arm a — drained but coverage incomplete → the shared caveat + a per-root percent', () => {
    // Four applicable stages of 100 docs each, 40 settled in every one: 160/400 = 40%.
    const fs = folderStatus(
      covered({
        parentDocsSettledEmbedding: 40,
        parentDocsSettledSplade: 40,
        parentDocsSettledNer: 40,
        chunkDocsSettled: 40,
      }),
      ctx({ relativeTime: '2 minutes ago', enrichmentStages: ALL_STAGES }),
    );
    expect(fs.state).toBe('enriching');
    expect(fs.glyph).toBe('pending');
    expect(fs.metaText).toBe(`default · 312 files · ${CAVEAT} · 40%`);
  });

  it('arm b — coverage complete → "fully searchable", keeping the indexed + Verified suffixes', () => {
    const fs = folderStatus(
      covered(),
      ctx({
        relativeTime: '2 minutes ago',
        verifiedRelativeTime: 'just now',
        enrichmentStages: ALL_STAGES,
      }),
    );
    expect(fs.state).toBe('ready');
    expect(fs.glyph).toBe('indexed');
    expect(fs.metaText).toBe(
      'default · 312 files · fully searchable · indexed 2 minutes ago · Verified just now',
    );
  });

  /**
   * Round-15 F1b — the Library row's half of F1. At 0% coverage with no embedding model the row
   * rendered "scifact ✓ — default · 5184 files · indexed 4m ago · Verified just now": a green check
   * and a freshness heartbeat with NO semantic qualifier, because every stage being inapplicable
   * leaves `rootCoverage` null — the same shape as "coverage not derivable yet".
   */
  it('F1b: no embedding model ⟹ the row is qualified, not an unadorned "✓ … Verified just now"', () => {
    const fs = folderStatus(
      covered({
        parentDocsSettledEmbedding: 0,
        parentDocsSettledSplade: 0,
        parentDocsSettledNer: 0,
        chunkDocsSettled: 0,
      }),
      ctx({
        relativeTime: '4 minutes ago',
        verifiedRelativeTime: 'just now',
        enrichmentStages: NO_STAGES,
        enrichmentBlocked: true,
      }),
    );
    expect(fs.state).toBe('keyword-only');
    expect(fs.glyph).not.toBe('indexed');
    expect(fs.metaText).toContain(BLOCKED_CAVEAT);
    expect(fs.metaText).not.toContain('fully searchable');
    // Never a fabricated number: no stage is countable, so no percent may be shown.
    expect(fs.metaText).not.toContain('%');
    // The heartbeat itself is still true and still shown — the defect was its being UNQUALIFIED.
    expect(fs.metaText).toBe(`default · 312 files · ${BLOCKED_CAVEAT} · Verified just now`);
  });

  it('F1-repro BOUNDARY: same flags, vectors persisted ⟹ the row stays terminal', () => {
    // The round's non-reproducing first attempt: model deleted after enrichment finished. Nothing is
    // outstanding index-wide, so `enrichmentBlocked` is false and the row must not acquire a caveat
    // for work that does not exist.
    const fs = folderStatus(
      covered(),
      ctx({
        relativeTime: '4 minutes ago',
        verifiedRelativeTime: 'just now',
        enrichmentStages: NO_STAGES,
        enrichmentBlocked: false,
      }),
    );
    expect(fs.state).toBe('ready');
    expect(fs.glyph).toBe('indexed');
    expect(fs.metaText).not.toContain(BLOCKED_CAVEAT);
    expect(fs.metaText).toContain('Verified just now');
  });

  it('arm b outranks the index-wide boolean — a complete root is done while OTHER roots enrich', () => {
    // `enrichmentPending` is INDEX-WIDE (809 finding 2: the backfill records no root attribution), so
    // it must not withhold the terminal claim from a root whose OWN coverage is provably complete —
    // that would pin a permanent caveat on a finished folder for someone else's work.
    const fs = folderStatus(
      covered(),
      ctx({ relativeTime: '2 minutes ago', enrichmentStages: ALL_STAGES, enrichmentPending: true }),
    );
    expect(fs.state).toBe('ready');
    expect(fs.glyph).toBe('indexed');
    expect(fs.metaText).toContain('fully searchable');
    expect(fs.metaText).not.toContain(CAVEAT);
  });

  it('arm a needs no index-wide boolean — per-root coverage is evidence enough on its own', () => {
    // The mirror of the case above: per-root truth decides in BOTH directions, so a root still
    // climbing says so even when the index-wide fallback gate reads "nothing pending".
    const fs = folderStatus(
      covered({ chunkDocsSettled: 0 }),
      ctx({ enrichmentStages: ALL_STAGES, enrichmentPending: false }),
    );
    expect(fs.state).toBe('enriching');
    expect(fs.metaText).toContain(`${CAVEAT} · 75%`);
  });

  it('zero denominator: coverage fields absent/zero → no percent, pre-813 wording', () => {
    const absent = folderStatus(row(), ctx({ enrichmentStages: ALL_STAGES }));
    expect(absent.state).toBe('ready');
    expect(absent.metaText).not.toContain('%');
    // Explicit zeros are the "index runtime unavailable" shape (IndexedRootView: all-zero) — the
    // same withdrawal, never "0%".
    const zeros = folderStatus(
      row({ parentDocsTotalEmbedding: 0, chunkDocsTotal: 0, parentDocsSettledEmbedding: 0 }),
      ctx({ enrichmentStages: ALL_STAGES }),
    );
    expect(zeros.state).toBe('ready');
    expect(zeros.metaText).not.toContain(CAVEAT);
  });

  it('applicability unknown (no snapshot yet) → no percent even with coverage on the row', () => {
    const fs = folderStatus(covered({ parentDocsSettledSplade: 0 }), ctx({ enrichmentStages: null }));
    expect(fs.state).toBe('ready');
    expect(fs.metaText).not.toContain(CAVEAT);
    expect(fs.metaText).not.toContain('%');
  });

  it('a disabled stage leaves the denominator — it cannot pin a folder below 100% forever', () => {
    // SPLADE off with zero SPLADE-settled docs: with the stage counted the root would sit at 75%.
    const row0 = covered({ parentDocsSettledSplade: 0 });
    const spladeOff = folderStatus(
      row0,
      ctx({ enrichmentStages: { embedding: true, splade: false, ner: true } }),
    );
    expect(spladeOff.state).toBe('ready');
    expect(spladeOff.metaText).toContain('fully searchable');
    const spladeOn = folderStatus(row0, ctx({ enrichmentStages: ALL_STAGES }));
    expect(spladeOn.state).toBe('enriching');
    expect(spladeOn.metaText).toContain(`${CAVEAT} · 75%`);
  });

  it('coverage never overrides the active/failed/unverified states (precedence unchanged)', () => {
    const stages = ctx({ enrichmentStages: ALL_STAGES });
    expect(folderStatus(covered({ inFlightCount: 3 }), stages).state).toBe('indexing');
    expect(folderStatus(covered({ failedCount: 2 }), stages).state).toBe('failed');
    expect(folderStatus(covered({ deleteDetectionUnverified: true }), stages).state).toBe(
      'unverified',
    );
  });

  it('999 of 1000 is NOT "fully searchable" — completeness is exact, never a rounded 100%', () => {
    // The §8.1 false-terminal in a new costume: Math.round would report 100% with a document still
    // pending. Completeness gates on settled >= total, and the displayed percent caps at 99.
    const fs = folderStatus(
      row({
        fileCount: 1000,
        parentDocsTotalEmbedding: 1000,
        parentDocsTotalSplade: 1000,
        parentDocsTotalNer: 1000,
        parentDocsSettledEmbedding: 999,
        parentDocsSettledSplade: 1000,
        parentDocsSettledNer: 1000,
        chunkDocsTotal: 0,
        chunkDocsSettled: 0,
      }),
      ctx({ enrichmentStages: ALL_STAGES }),
    );
    expect(fs.state).toBe('enriching');
    expect(fs.metaText).toContain(`${CAVEAT} · 99%`);
    expect(fs.metaText).not.toContain('fully searchable');
  });

  it('a missing SETTLED key withdraws the stage, exactly like a missing total', () => {
    // The wire reported no SPLADE numerator for this root. Keeping the stage in the denominator
    // with a zero numerator freezes a fraction nothing can ever move (100 of 200 forever); the
    // absence is the same absence as a missing total, so the stage leaves the ratio.
    const fs = folderStatus(
      row({
        fileCount: 312,
        parentDocsTotalEmbedding: 100,
        parentDocsSettledEmbedding: 100,
        parentDocsTotalSplade: 100,
        // parentDocsSettledSplade deliberately absent
      }),
      ctx({ enrichmentStages: { embedding: true, splade: true, ner: false } }),
    );
    expect(fs.state).toBe('ready');
    expect(fs.metaText).toContain('fully searchable');
    expect(fs.metaText).not.toContain('· 50%');
  });

  it('the chunk tier follows the EMBEDDING stage — same encoder, same applicability', () => {
    // Embedding switched off: its chunk vectors will never be computed either, so a chunk backlog
    // must not hold the folder at "enriching" forever.
    const fs = folderStatus(
      row({
        fileCount: 312,
        parentDocsTotalEmbedding: 100,
        parentDocsSettledEmbedding: 0,
        parentDocsTotalNer: 100,
        parentDocsSettledNer: 100,
        chunkDocsTotal: 400,
        chunkDocsSettled: 0,
      }),
      ctx({ enrichmentStages: { embedding: false, splade: false, ner: true } }),
    );
    expect(fs.state).toBe('ready');
    expect(fs.metaText).toContain('fully searchable');
  });

  it('settled counts are clamped to their total — a stale count can never exceed 100%', () => {
    const fs = folderStatus(
      covered({ parentDocsSettledEmbedding: 500, chunkDocsSettled: 500 }),
      ctx({ enrichmentStages: ALL_STAGES }),
    );
    expect(fs.state).toBe('ready');
    expect(fs.metaText).toContain('fully searchable');
  });
});

// 809 finding 1 — arm c: the INDEX-WIDE fallback gate. When this root's own coverage is not
// derivable, positive evidence that the backfill still owes work (`enrichmentProgress(status).pending`)
// still withholds the terminal ✓ — it just cannot name a percent. A drained folder has finished text
// extraction and the Lucene write; embedding/SPLADE/NER run afterwards on the backfill scheduler, and
// during that window keyword search works while semantic and hybrid search do not. The terminal ✓
// claimed that capability. These assertions supersede the tempdoc 599 §10 scoping note ("the
// vector/embedding tier is intentionally NOT reflected here").
describe('folderStatus — index-wide enrichment fallback (809 finding 1)', () => {
  it('drained + indexed BUT enrichment pending ⇒ NOT ready, and never the ✓ glyph', () => {
    const fs = folderStatus(row(), ctx({ enrichmentPending: true }));
    expect(fs.state).toBe('enriching');
    expect(fs.state).not.toBe('ready');
    expect(fs.glyph).not.toBe('indexed');
  });

  it('the claim says what IS true: indexed + keyword ready, semantic still catching up', () => {
    const fs = folderStatus(row(), ctx({ relativeTime: '2 minutes ago', enrichmentPending: true }));
    // It still reports the true half (the folder IS indexed and keyword-searchable) …
    expect(fs.metaText).toContain('indexed 2 minutes ago');
    expect(fs.metaText).toContain('keyword search ready');
    // … and does not claim the half that is not true yet.
    expect(fs.metaText).toContain('semantic search still catching up');
    expect(fs.metaText).toBe(`default · 10 files · indexed 2 minutes ago · ${CAVEAT}`);
  });

  it('the fallback arm names NO percent — index-wide evidence cannot measure one root', () => {
    // 809 finding 2: the backfill batches documents without recording which root they came from, so
    // "this folder is N% enriched" is not derivable from the index-wide boolean. A number here would
    // be fabricated (the §8.1 false-terminal's cousin).
    const fs = folderStatus(row(), ctx({ enrichmentPending: true }));
    expect(fs.metaText).not.toContain('%');
  });

  it('drain alone no longer yields ready — the gate is coverage (the exact pre-fix behaviour)', () => {
    // Identical row, identical drain; ONLY the coverage fact differs. This is the assertion that
    // distinguishes a coverage gate from a queue-drain gate.
    expect(folderStatus(row(), ctx({ enrichmentPending: false })).state).toBe('ready');
    expect(folderStatus(row(), ctx({ enrichmentPending: true })).state).toBe('enriching');
  });

  it('a folder-specific problem still outranks the index-wide backfill note', () => {
    // Failures and unverified deletions are facts about THIS folder; enrichment is index-wide.
    expect(folderStatus(row({ failedCount: 2 }), ctx({ enrichmentPending: true })).state).toBe('failed');
    expect(
      folderStatus(row({ deleteDetectionUnverified: true }), ctx({ enrichmentPending: true })).state,
    ).toBe('unverified');
    expect(folderStatus(row({ inFlightCount: 3 }), ctx({ enrichmentPending: true })).state).toBe('indexing');
  });

  it('enrichment pending does not manufacture a claim for a folder that indexed nothing', () => {
    const fs = folderStatus(
      row({ status: 'scanned', lastIndexedIsoTime: '', fileCount: 0 }),
      ctx({ relativeTime: '', enrichmentPending: true }),
    );
    expect(fs.state).toBe('empty');
  });
});
