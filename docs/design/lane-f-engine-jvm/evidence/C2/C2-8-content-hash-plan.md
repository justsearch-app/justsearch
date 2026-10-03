# C2-8b: commit the existing content hash with the queue outcome

2026-09-14, base 2ab49abc4. This is a per-item foundation for Q5, not a claim that
operation-scoped ingestion/restart recovery is connected.

## Existing ownership and choice

SqliteSchema V15 already adds nullable jobs.content_hash. JobBatchExtractor computes the
source SHA-256 before and after extraction, and JobBatchWriter passes that hash into the
Lucene document. The missing write is the queue's committed outcome. Reuse the existing
IngestionLedgerTransition, exact IndexJob claim and markDoneTransitions transaction.
A separate writer, table or operation checkpoint callback would create unnecessary ownership.

Add nullable committedContentHash to the transition, retaining existing constructors.
A non-null hash must be a lowercase SHA-256 and have a processing claim. Writer supplies
ExtractedJob.sourceSha256. The journal holds that transition until IndexingLoop's existing
successful commit-and-drain boundary. Its per-unit fallback must call the same transition
API with a singleton collection, preserving the hash instead of falling back to the older
hash-free markClaimDone API.

SqliteJobQueue admits only the exact live claim under its existing lock. DONE, hash and
ingestion-ledger outcome commit in one transaction. A stale/forged claim cannot publish a
hash; rollback retains the claim for retry. Hash-free completion clears an unknown digest
rather than making an old one look newly proven. Re-enqueue already replaces the row with
a null hash; retain that conservative behavior. No change to WAL durability or schema.

A hash proves which source bytes produced that successful committed write. It alone is
not a generation witness, current-source freshness check, membership record or operation
completion. UNCHANGED skips currently use mtime; they must not fabricate this evidence.
The subsequent C2-8/9 owner must address path overlap, watcher replacement, root membership,
per-file attempt/backoff preservation and generation/grant checks before using hashes to
skip replay. The prepared root plan remains held until those producer paths are connected.

## Review correction: retry committed outcomes while idle

Independent review found a structural stall: after the index commit succeeds, both SQL
outcome attempts can fail, leaving the transition pending while indexedSinceCommit is zero.
The old idle and shutdown guards then never drain it unless another document is indexed.
Use the existing loop as retry owner: commit buffered writes when the counter is positive,
then drain on every idle cycle, including zero new writes. At shutdown use the same ordering.
A thrown index commit must skip drain. No timer, durable marker, queue or second owner is
needed; the existing counter distinguishes buffered effects from already committed outcomes.
Tests exercise the production idle/shutdown functions, failed commits, both SQL attempts
failing, and later retry without another index write or redundant commit.

## Batch and acceptance

One commit, then immediate push: transition/queue transaction, writer/journal carrier,
regressions and this evidence. Root owns queue/API and all builds; bounded worker may own
writer/journal/test changes. No compiled-source edits during builds.

Prove the pending journal has no queue effect, actual writer supplies source SHA-256,
batch fallback retains the full transition, and failed singleton writes stay pending.
Real SQLite tests cover committed hash after reopen, stale/equal-valued claim refusal,
atomic ledger failure rollback with retry, and re-enqueue/unknown completion clearing.
Run affected module suites, PMD/format, a negative control and independent review.
C2-8/9 live and installed recovery acceptance remains open after this foundation.
