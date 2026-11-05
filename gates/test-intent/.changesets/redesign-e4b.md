---
schema: test-intent.v1
task: redesign-e4b
author-role: builder
author-session: redesign-e4b-builder
---

# Test-intent entries

E4b cuts the scheduler over to one fair enrichment scheduler. `BackfillScheduler` keeps a
persistent in-memory service ledger over the lanes dense parent, BGE-M3 (when configured, in the
dense-parent position, replacing dense parent and both sparse lanes), dense chunk, sparse parent,
sparse chunk and NER. A turn runs one E4a lane unit, charged `max(measured, 10 ms)` with
foreground pacing waits excluded. Ties go to the first tied lane after a round-robin pointer.
A lane that (re)enters eligibility gets `max(S_i, min S_j)` over the lanes that stayed eligible.
When none stayed eligible, the last eligible minimum survives the gap.
Busy calls admit one turn at the canonical interleave interval; pending ingestion no longer
hard-stops them. An idle cycle repeats turns until its deadline, a 500-turn bound (its budget at
the charge floor), a hard stop, pending ingestion after the admitted unit, or no lane that can
still advance. A lane that returns without advancing, or whose unit was cancelled, held or had
its source changed, is not offered again that cycle. The mode probe, combined/individual branch,
tight loops, drain-count gates, the three-second dense reservation, the work-set signature
detector, NRT suspension around the tight loop and the op's five-writing-batch commit are gone.
The scheduler counts enrichment-dirty rows for both callers. It commits at the existing
document or time threshold after a turn, and at every idle-cycle end and every busy call that
finds no lane; a failed commit keeps the count. Retained routes count as retained progress, so a
refused batch's per-document fallback continues in the same cycle. One DEBUG line and a
private observer field (set reflectively by tests; no public API) record each turn. The mode gauge reports `active`/`idle`.
`justsearch.backfill.enrichment_interleave_{interval_ms,batch_size}` are canonical; the SPLADE
names are aliases joined into one ordinal chain, and canonical values win ties.

The new and revised fixtures use real isolated Lucene writers or the existing mocks, fake encoders
that advance a scheduler-local monotonic clock, and no sleeps. They close their runtimes and
restore singleton metrics, the mode gauge and the scheduler logger level. They touch no owner
application, dev stack, dependency tree or GPU. Reflection keeps the before state compiling
(`runInterleavedSplade`, the absent observer, both record accessor spellings), so the repair
checks fail by assertion.

Superseded expectations, all by the decisions quoted below. Mode labels `combined`/`individual`
are retired. The five-writing-batch cadence is replaced by the document threshold; the probe
fixtures set it to five, which keeps their durability boundaries. The combined bundled write no
longer exists for separate providers, so storage attribution is per lane write (BGE-M3 keeps its
two-stage write). NER units publish one document each. The dense reservation became a ledger
assertion, and the work-set stall detector became eligible-service evidence. The dense-only
caller's no-classification policy goes because one dense lane runs the common kernel. A
concurrent change to another stage's field no longer rejects a lane's write, since the writer's
proof covers only the stages a write carries; the rejected-and-excluded oracle is moved to the
dense stage's own field.

Manual validation on 2026-11-05: the transplanted repair checks (new FairEnrichmentSchedulerTest,
EnrichmentInterleaveAliasTest, the revised stall test and the new projection case) ran on a shared
clone of the base in its own build folders. Every repair selector below failed there by assertion
for its named reason: busy service was sparse-only, pending ingestion hard-stopped, a fixed-order
probe was used, there were no canonical keys, the work-set detector stayed silent on a frozen
unit, the gauge read combined/individual, and retired machinery was still present. The
preservation cases (P1095 x3, D8 holds, the publishing-never-warns stall case, the blank-alias case)
passed on both. On the candidate, the six module suites ran: configuration 374, worker-core 673,
worker-services 4,250, adapters-lucene 1,018, indexer-worker 2,598 and app-engine 890 tests, all
passing after the register-size pin was moved. A worker-core tokenizer setUp timeout under load
passed when rerun.

Not done: the combined op's sparse/NER phases, the batch-level `processSpladeBackfill`,
`processNerBackfill` and full-batch `processDenseBackfill`, and inert context fields (dense-share
supplier, batch counter, id caches, work-set signature) now have only test callers. Deleting them
needs the E1-E3 tests that call them (about 20 classes) moved to the lane operations. D7's
full-retention-capacity variant is not covered by a new check. The real-system acceptance is the
orchestrator's on a committed candidate.

```json
{
  "entries": [
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/resolved/ResolvedConfigBuilderTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillResourceAdmissionTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "rename"
      ],
      "whatMoved": "The busy entry point runInterleavedSplade is renamed runInterleavedEnrichment and the resolved accessors spladeInterleave* are renamed enrichmentInterleave*. Inputs, preconditions, observations and expected values are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4b",
          "scenario": "D6",
          "quote": "Settings: canonical justsearch.backfill.enrichment_interleave_interval_ms (and env name); old SPLADE names stay as aliases with their values; the batch setting keeps its count-cap meaning and gets a canonical enrichment name with alias",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerTightLoopTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The never-draining population is also returned through the parent-only (non-chunk) query, the population the separate sparse-parent lane selects. The three containments, their bounds and expected outcomes are unchanged; the class comment records that they now bind the idle cycle of lane turns.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4b",
          "scenario": "D4b",
          "quote": "Both sparse lanes receive scheduled opportunities under continuing parent arrivals, respecting chunk enablement.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerModeRecordingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentReceiptPreservationTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e4b request",
          "quote": "Gauge: the backfill mode gauge's values become \"active\" (the last scheduler call, busy or idle, admitted a lane turn) and \"idle\"; holds and backoff keep their existing reasons; documented, not silently reinterpreted."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e4b request",
          "quote": "BGE-M3: when configured it is one unified lane that owns everything its operation selects today (sparse-pending parents and chunks, writing dense and sparse), charged once, with no second selectable alias; NER stays separate."
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Only the gauge's combined/individual expectations change to active. In EnrichmentReceiptPreservationTest the concurrent change moves to the dense stage's own retry field, because separate lanes no longer carry NER fields in a dense write. The not-re-encoded-in-the-same-cycle oracle and the completion-counter input are kept."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/BackfillProbeCommitPersistenceTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerPersistenceTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e4b request",
          "quote": "Commits (interim until E6): the scheduler counts enrichment-dirty documents for both callers and, after each turn, commits when the existing document or time threshold is reached; at the end of an idle cycle, an orderly yield and drain it commits if anything is dirty (keeps the probe test's immediate-durability outcome with the timer off)."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e4b request",
          "quote": "durability outcomes of the probe/persistence tests are kept, the five-writing-batch counter goes with the probe and its tests are revised to durability outcomes."
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Every durable-state, retry, reopen and rollback assertion is kept. Only which commit reason or count fires changes: a five-document threshold now stands in for five writing batches, and the persistence reopen asserts one commit rather than its reason."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e4b request",
          "quote": "Units: an admitted E3 unit finishes (not \"a document\"); ingestion pressure lets one admitted unit finish with its result or retained progress kept, then stops further idle units; hard stops and deadlines keep their E3 meaning."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e4b request",
          "quote": "Holds: acquisition holds are keyed by provider (shared by every lane that provider serves, through both callers); storage holds stay per represented stage (E3c-b). A success clears only holds of its own kind for its own key. Provider replacement resets that provider's acquisition hold."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e4b request",
          "quote": "Combined multi-stage orchestration, bundled selection and fixed phases | Transfer dense single-pass/window logic before deleting the class. Preserve accounting, preservation, stop, identity, retention and long-document tests."
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Busy calls are renamed. Accepted-prefix checks read the turn's receipts together, because NER units publish one document each. The combined attribution case becomes per-lane attribution: the failed write holds exactly the stages it carried, peers advance the same rows, held stages wait and recover after expiry, and a BGE-M3 case holds its dense and sparse pair. The combined owner-release case becomes a dense windowed case. The accepted owner releases its slot, the complete unaccepted owner is kept and recovers without recomputing, a peer replacement leaves dense held, and a later row failure charges once. Every cancellation, hard-stop, closed-writer and row-local oracle is kept."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e4b request",
          "quote": "Absolute dense-share reservation | `BackfillSchedulerSlowBatchTest:663`; `CombinedEnrichmentLongDocumentTest:509\u2013573`. Replace reservation-specific assertions with opportunity/service-time assertions."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e4b request",
          "quote": "Ledger (precise): each lane i keeps charged service S_i. A turn goes to the eligible lane with the least S_i; ties go to the first tied lane after a round-robin pointer that advances past each chosen lane (no static priority)."
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4b",
          "scenario": "D8",
          "quote": "More than five cycles of advancing retained offsets produce no stall warning; repeated returned service without advancement produces one diagnostic episode, while holds/backoff do not count as failed service.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. The reservation case becomes a ledger case: before, at and after the old 3 s boundary the charged dense turn hands the next turn to NER. The no-individual-publication helper now checks for per-document fallback encoder calls, because one-row NER units and one-row final pages are normal. The sparse backoff hold is installed through the stage-keyed result method. The 798 budget WARN case is spent by one non-advancing unit (every row rejected stale) that overruns the budget, since non-advancing lanes no longer repeat within a cycle. Every timing, interruption and preservation oracle is kept."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentLaneDecompositionTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e4b request",
          "quote": "extract the dense kernel (single-pass classification, retained routes, resumable windows, pooling) from the combined op into a common dense unit; expose bounded lane operations"
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e4b request",
          "quote": "both existing dense paths (combined and individual) run the common kernel before E4a merges."
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. There is one dense lane now, so the dense-only configuration also classifies once under late chunking and resumes the same windows. The individual case expects one classification and the combined case's turn sequence. The pooling case never expects single-pass when late chunking is off. Reference pooling, sparse preservation, offsets and stored vectors are unchanged."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerStallDetectionTest.java"
      ],
      "class": "New",
      "behaviour": "A lane that keeps receiving eligible turns which return without publishing, charging, terminally failing or advancing windows logs one stall WARN per episode after five such turns. Publishing service never warns, even with an identical work-set selected every cycle, and the detector re-arms after the lane advances.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4b",
          "scenario": "D8",
          "quote": "More than five cycles of advancing retained offsets produce no stall warning; repeated returned service without advancement produces one diagnostic episode, while holds/backoff do not count as failed service.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e4b request",
          "quote": "Work-set-signature stall detector | `BackfillSchedulerStallDetectionTest:262\u2013334`; replace selection identity with eligible-service/advancement evidence."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e4b.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerStallDetectionTest.java#frozenEligibleUnitLogsTheStallWarn",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerStallDetectionTest.java#stallWarnIsDeduplicatedPerEpisode",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerStallDetectionTest.java#detectorReArmsAfterRecovery"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/resolved/ConfigApplyScopesTest.java"
      ],
      "class": "New",
      "behaviour": "The configuration apply-scope register contains 292 entries: the 290 existing ones plus the canonical justsearch.backfill.enrichment_interleave_batch_size and enrichment_interleave_interval_ms keys, each component:index like their legacy aliases. The packaged register stays byte-identical to the repository register; the explicit scope assertions are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4b",
          "scenario": "D6",
          "quote": "Settings: canonical justsearch.backfill.enrichment_interleave_interval_ms (and env name); old SPLADE names stay as aliases with their values; the batch setting keeps its count-cap meaning and gets a canonical enrichment name with alias",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e4b-preservation.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/resolved/ConfigApplyScopesTest.java#packagedRegisterIsTheRepositoryRegister"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/IndexConfigurationProjectionTest.java"
      ],
      "class": "New",
      "behaviour": "The canonical enrichment interleave interval and batch size change the index owner's effective version. The legacy SPLADE name with the same value projects identically, and an explicit default equals the default.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "redesign-e4b",
          "scenario": "D6",
          "quote": "Canonical and legacy settings resolve by ordinal and equal-priority canonical preference; effective projection and actual interval/count cap agree, including BGE compatibility.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e4b.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/IndexConfigurationProjectionTest.java#canonicalAndLegacyEnrichmentInterleaveNamesProjectOneEffectiveValue"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java"
      ],
      "class": "New",
      "behaviour": "The re-entry service floor survives a call with no eligible lanes. A BGE-M3 acquisition hold never blocks the separate dense-chunk provider; a dense-chunk acquisition failure under BGE-M3 stays held through busy and idle calls without row retry charge. A not-due busy call reports idle after an active call.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e4b request",
          "quote": "the re-entry floor is the minimum charged service over all lanes at the last call where any lane was eligible (kept when no lane is eligible), so a global gap cannot create a burst"
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e4b request",
          "quote": "holds are keyed by the provider that lane's operation actually calls (dense chunk -> dense provider even when BGE-M3 is configured; BGE-M3 lane -> BGE-M3 provider), and a hold is recorded against that provider so it is not re-acquired until expiry or replacement"
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e4b request",
          "quote": "every scheduler call, due or not, sets the gauge from that call (a call that admits no turn reports idle)"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e4b-verifier-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D3_globalEligibilityGapDoesNotReintroduceCatchupBurst",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D5b_bgeHoldDoesNotBlockIndependentDenseChunk",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D5b_denseChunkAcquisitionHoldUnderBge",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D11_notDueBusyCallReportsIdle"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java"
      ],
      "class": "New",
      "behaviour": "An idle cycle that reconciles disambiguation without admitting a fair-lane turn reports idle in the backfill-mode gauge, while returning true so the caller still selects active pacing (E3c-a).",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e4b request",
          "quote": "the gauge reports lane admission only (\"active\" if the call admitted at least one fair-lane turn, else \"idle\"); the activity/pacing result returned to the caller stays as it is (disambiguation still counts as useful work for pacing, per E3c-a)."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e4b-d11-disambiguation-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D11_disambiguationWithoutLaneAdmissionReportsIdle"
      ],
      "repaired": true
    }
  ]
}
```

## Evidence commands

Run sequentially in the background under `js-gradle-slot`. The evidence runner writes these
files; the orchestrator runs these commands after the builder hands back. The two original isolated new files,
`FairEnrichmentSchedulerTest` and `EnrichmentInterleaveAliasTest`, have their original fail-before listed here for the verifier. The four verifier-repair checks
in `FairEnrichmentSchedulerTest` have their own entry and pre-repair evidence command below. Run in a disposable shared clone of the final candidate commit inside `$TMPDIR`
with any current test and changeset edits copied in; copy the generated evidence files back
to the source worktree. The runner creates its before checkout in `$TMPDIR` against that
clone, so Git metadata and build folders stay isolated from the source repository.

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-e4b.json \
  --before 7a5ee0004c1dd2b3e7ed7cd71e3fc61eb02f7329 \
  --before-root "$TMPDIR" \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D1_continuingIngestionServesEveryContinuouslyEligibleLane \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D2_pendingIngestionLetsOneAdmittedUnitFinishThenStopsFurtherIdleUnits \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D2_ingestionArrivingDuringAMultiCallDocumentKeepsTheUnitsRetainedProgress \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D3_anOverrunningEarlyLaneCannotReclaimTheFirstSlotAcrossCyclesAndCallers \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D3_zeroTimeOutcomesAndTiesRotateWithoutMonopolising \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D3_unequalCostsShareChargedServiceNotTurnCounts \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D3_reentryKeepsDebtAndNeverBurstsAfterAbsenceOrProviderReplacement \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D4b_bothSparseLanesGetOpportunitiesUnderContinuingParentArrivals \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D5b_oneProviderHoldCoversEveryLaneItServesThroughBothCallers \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D7_retainedClassificationAndWindowsResumeThroughEitherCaller \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D7_replacedContentInvalidatesRetainedWorkForBothCallers \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D8_advancingRetainedWindowsOverManyCyclesAreNotAStall \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D9_smallTurnsUseTheThresholdsRetryAFailedCommitAndDrainAtIdle \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D10_bgeIsOneUnifiedOwnerChargedOnceBesideSeparateNer \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D11_activityGaugeAndPerTurnRecordsDistinguishWindowAdvancementFromCompletion \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#architecturalDeletionLeavesNoModeRoutingProbeOrDrainGates \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerStallDetectionTest.java#frozenEligibleUnitLogsTheStallWarn \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerStallDetectionTest.java#stallWarnIsDeduplicatedPerEpisode \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerStallDetectionTest.java#detectorReArmsAfterRecovery \
  --check modules/configuration/src/test/java/io/justsearch/configuration/resolved/EnrichmentInterleaveAliasTest.java#canonicalOnlyLegacyOnlyNeitherAndBoth \
  --check modules/configuration/src/test/java/io/justsearch/configuration/resolved/EnrichmentInterleaveAliasTest.java#propertyOutranksEnvironmentAcrossBothNames \
  --check modules/configuration/src/test/java/io/justsearch/configuration/resolved/EnrichmentInterleaveAliasTest.java#invalidWinningValueDefaultsWithoutFallingBackToALowerPriorityAlias \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/IndexConfigurationProjectionTest.java#canonicalAndLegacyEnrichmentInterleaveNamesProjectOneEffectiveValue
```

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-e4b-preservation.json \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#P1095_chunksAdvanceWithinThreeCyclesUnderTheOriginalLongDocumentCosts \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D8_holdsAndBackoffAreNotUnsuccessfulService \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerStallDetectionTest.java#publishingWorkSetDoesNotWarn \
  --check modules/configuration/src/test/java/io/justsearch/configuration/resolved/EnrichmentInterleaveAliasTest.java#blankWinnerDefersToTheNextSourceIncludingTheAliasAtEqualPriority \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentCycleBudgetTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentLongDocumentTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/BackfillProbeCommitPersistenceTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerPersistenceTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerTightLoopTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerModeRecordingTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerProviderGateTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillResourceAdmissionTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentLaneDecompositionTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentReceiptPreservationTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentInternalStopTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentStorageDispositionTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentMapperDispositionTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BgeM3BackfillOpsTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationBackfillOpsTest.java \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SpladePendingSelectionTest.java \
  --check modules/configuration/src/test/java/io/justsearch/configuration/resolved/ResolvedConfigBuilderTest.java \
  --check modules/configuration/src/test/java/io/justsearch/configuration/resolved/ConfigApplyScopesTest.java#packagedRegisterIsTheRepositoryRegister \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentPublicationReceiptTest.java
```

The four verifier-repair checks were transplanted into shared clones with separate build folders.
All four fail by assertion on pre-repair HEAD `481b18c047f30168229559b3e4bf0bbe142dedc3`:
the global gap gives sparse six consecutive turns, the BGE hold leaves the dense chunk pending,
the dense provider is acquired three times, and the not-due busy call leaves the gauge active.
On base `7a5ee0004c1dd2b3e7ed7cd71e3fc61eb02f7329`, the BGE-hold independence check passes;
the dense acquisition hold, global-gap and not-due telemetry checks fail by assertion. The last
two base failures reflect the absence of turn observation and the former gauge semantics,
respectively. No evidence JSON is written by the builder.

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-e4b-verifier-repair.json \
  --before 481b18c047f30168229559b3e4bf0bbe142dedc3 \
  --before-root "$TMPDIR" \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D3_globalEligibilityGapDoesNotReintroduceCatchupBurst \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D5b_bgeHoldDoesNotBlockIndependentDenseChunk \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D5b_denseChunkAcquisitionHoldUnderBge \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D11_notDueBusyCallReportsIdle
```

The D11 disambiguation-only case is `New` with `repaired: true` under
`docs/reference/testing/test-intent.md`: it adds an expectation for a repair to the in-flight
E4b candidate, rather than changing an existing oracle. Its returned-activity assertion preserves
E3c-a pacing. Run the evidence command below on the final candidate in a disposable shared clone;
the builder does not write evidence JSON. Use pre-repair HEAD for fail-before, since the defect
is in E4b. Manual runs in separate shared clones confirmed that the selector fails by assertion
on `121d48e580721678af99f74a90cffcafb9edeb1d` (expected idle, actual active) and passes on
main `51bfa7756ce7f6313350eaaaf70d6a5cec4dc8b5`. Both runs passed the preceding assertions
that reconciliation ran, no fair lane was admitted, and the same cycle returned true.

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-e4b-d11-disambiguation-repair.json \
  --before 121d48e580721678af99f74a90cffcafb9edeb1d \
  --before-root "$TMPDIR" \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FairEnrichmentSchedulerTest.java#D11_disambiguationWithoutLaneAdmissionReportsIdle
```

Parameterized checks include their method signature in each invocation name, and the stall
checks use their method names, so every `path#method` selector resolves in JUnit XML. All
assertions and parameter values are unchanged. The repair run transplants these tests onto
7a5ee0004c1dd2b3e7ed7cd71e3fc61eb02f7329; no compatibility patch or production overlay is needed.

## Acceptance records
