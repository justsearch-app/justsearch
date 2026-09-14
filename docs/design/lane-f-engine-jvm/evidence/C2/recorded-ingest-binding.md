# C2-8d.1 recorded root and child binding

2026-09-14, based on pushed128a0c945, Windows/PowerShell. This item is a WIP
building block of [the vertical ingestion plan](C2-8d-vertical-plan.md). C2-8d and
C2-9 remain open until the real producer, queue receipts and pre-poll recovery are
connected and verified. No ingestion recovery, live-model or installed proof is claimed.

## Change and ownership

RecordedRootPlan is an immutable, filesystem-free value with strict root-plan.v1
metadata and deterministic nested-root partitioning. RecordedIngestChild keeps its
parent key and selected-plan digest in the internal descriptor; the one-root plan
is stored separately as its private preparation. No plan is decoded from identity_json.

The process root binds RecordedIngestPlanResolver to the attempt runner. It validates
only core.ingest-files/INGEST and core.reindex/REINDEX metadata, using the existing
prepared envelope codec, exact original attribution and strict frozen root schema.
Contributed kind declarations and undo do not select this producer. The resolver runs
outside SQLite ownership. It establishes preparation binding, not replay authority.

Only the same runner's issued parent capability can request a child, outside its
preparation scope. Exact root membership is checked against the resolved plan. The
store compares the inspected parent preparation nonce/payload within the insertion
transaction, requires RUNNING for a new child, and copies context and provenance from
the parent row. A derived child has history NONE and its own persisted preparation
nonce. Concurrent/repeated acceptance finds the existing child, including terminal
outcomes. Parent completion remains explicit producer composition.

Age/capacity pruning retains terminal children while their parent remains open. The
existing canonical internal identity supplies the parent relation; no second store
or authority was introduced. Once the parent is terminal the ordinary retention
policy applies. Without this protection a resumed parent could remint an already
completed root after child history eviction.

The architecture rule includes prepared and child acceptance in the shared runner's
exclusive write surface, with an executable violating caller fixture. Internal child
refusal is projected without private cause/payload through the existing error owner.

## Verification

- focused1535 executed all five selected test tasks:124 cases in31 suites, no failures,
  errors or skips. Covers root grammar, real SQLite child binding/concurrency/retention,
  real production codec resolver, architectural refusal and REST/MCP error contracts.
- negative1536 deliberately removed the preparation witness comparison and both
  child-retention prune predicates. Seven cases ran; exactly two assertions failed:
  stale preparation was accepted and an aged child disappeared under its open parent.
  No compile/error failure substitutes for those discriminating assertions.
- The store source was restored byte-identically to SHA256
  2e785c6f0ef56ff7de20694a30e40273b43bec1363bb65e3fca033a43427c537.
  The final positive run additionally verifies retention release after parent completion.
- Independent review found COMPLETE_WITH_GAPS is nonterminal. The retention predicate
  now excludes only terminal states; the clock/prune/reopen test covers RUNNING and
  COMPLETE_WITH_GAPS and verifies release after parent completion.
- Integrated1537 executed all five full affected test suites:5,013 cases in774 suites,
  four existing skips and no failures/errors. All ten PMD tasks and whole Spotless pass.
  [Task inventory and tested source hashes](recorded-ingest-binding.json) retain exact
  execution/reuse status. Existing JDK/deprecation, LightGBM fixture and unrelated
  MissingOverride/FutureReturnValueIgnored warnings remain visible; none were suppressed.
- Canonical storage docs and old held-cut records now match the current preparation and
  child-binding scope. Regeneration, links and runtime matrix pass without derived drift.
  Independent second review found no further substantive defect.
- Targeted negative1538 reverted only the parent-state retention predicate: the
  COMPLETE_WITH_GAPS parameter alone failed its retained-child assertion. Exact
  integrated source bytes were restored (SHA256152de594475260b9282971f3b08e36331bd57446d4b46cfe43e6be325b6343ff).
  Final1539 passes by reusing the identical full app-observability test result from
  integrated1537; PMD/Spotless also pass. This is cache reuse, not a new execution.
- A single production-resolver-to-runner-to-store composition test remains owed with
  the actual producer in C2-8d.3. Current tests independently prove the real codec
  resolver and real SQLite runner with an injected trusted resolver; do not conflate them.

Logs/XML/manifests live under the active lane-f-pr1-verify worktree's tmp directory:
child-binding-focused1535 and child-binding-negative1536 generation directories and
same-named .txt logs. Retain through2026-10-14 or acceptance plus30 days, whichever is
later. These are accessible local evidence, not hosted/platform proof. Hosted CI and
CLA for the preceding admission-revision commit128a0c945 both passed (CI34809851127,
CLA34809849392); they do not verify this dirty binding change.
