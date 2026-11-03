---
schema: test-intent.v1
task: redesign-c1a
author-role: builder
author-session: 01a1a1a4-dfc9-73b1-b6ac-098df68a2370
---

# C1a: certify whole indexed families before suppressing sync repair

Intent is the owner-supplied C1a contract in this builder request. No evidence or acceptance
record is authored here; the named evidence files are outputs for the independent verifier.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsChangedStampTest.java"
      ],
      "class": "New",
      "behaviour": "Ordinary DEFAULT/MAINTENANCE and strict planning admit missing, duplicate-ordinal and wrong-revision families, including committed damage hidden from the search reader. Complete families stay suppressed. The existing mocked unchanged-source controls now supply a successful completeness reader; their expectations are unchanged. A 1,000-file fixture measures census queries, family point queries and bounded admission batches.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Outcome: JustSearch's folder sync and the migration reconciliation plan skip a file as already indexed only when its whole indexed family (parent and every required chunk) is complete and current.",
          "location": "task redesign-c1a request"
        },
        {
          "kind": "owner-words",
          "quote": "P6 Cost: the completeness check runs only for files the parent evidence would otherwise suppress, batched per sync pass; report index reads per 1,000 unchanged files before and after.",
          "location": "task redesign-c1a request"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c1a-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsChangedStampTest.java#ordinarySyncReadmitsDamagedIndexedFamily",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsChangedStampTest.java#candidateDiscoveryIncludesDamagedIndexedFamily"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsChangedStampTest.java"
      ],
      "class": "New",
      "behaviour": "Complete multi-chunk families stay suppressed unless force=true. Measure the bounded additional logical index-query budget for 1,000 unchanged files through DEFAULT, MAINTENANCE and strict planning; every file remains suppressed before and after.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "P6 Cost: the completeness check runs only for files the parent evidence would otherwise suppress, batched per sync pass; report index reads per 1,000 unchanged files before and after.",
          "location": "task redesign-c1a request"
        },
        {
          "kind": "owner-words",
          "quote": "P1 A complete family with matching stamps, policy, hash presence and permitted collection stays suppressed through both filters; force=true still admits it; a wrong collection, changed stamp, missing stored hash or old policy stays eligible.",
          "location": "task redesign-c1a request"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c1a-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsChangedStampTest.java#unchangedThousandFileSyncMeasuresIndexReadBudget",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsChangedStampTest.java#completeIndexedFamilyStaysSuppressedUnlessForced"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncExtractionPolicyTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/DeclaredCollectionReconciliationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsCandidateDiscoveryTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/DeclaredCollectionScanSnapshotTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Existing mocked parent-filter controls now wire a mocked successful batched DocumentFieldOps reuse check. Source stamps, policy/hash presence, collection fixtures, calls and expected eligibility are unchanged. Real Lucene repair checks live in SyncDirectoryOpsChangedStampTest."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ExtractionPolicyMaintenanceAdmissionTest.java"
      ],
      "class": "New",
      "behaviour": "A completed current-policy file with one missing declared child is readmitted by maintenance without force. The same missing-child repair preserves terminal and future-retry queue owners. Existing old-policy checks are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "P5 Maintenance still preserves protected failure and retry owners when a repair is eligible (ExtractionPolicyMaintenanceAdmissionTest ~36).",
          "location": "task redesign-c1a request"
        },
        {
          "kind": "owner-words",
          "quote": "force=false sync in DEFAULT and in MAINTENANCE admits the path.",
          "location": "task redesign-c1a request"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c1a-repair.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ExtractionPolicyMaintenanceAdmissionTest.java#completedMissingChunkIsReadmittedWithoutForce"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ExtractionPolicyMaintenanceAdmissionTest.java"
      ],
      "class": "New",
      "behaviour": "A missing-chunk maintenance repair does not reset terminal or future-retry failure ownership, including the stored wait and retry owner.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "P5 Maintenance still preserves protected failure and retry owners when a repair is eligible (ExtractionPolicyMaintenanceAdmissionTest ~36).",
          "location": "task redesign-c1a request"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c1a-preservation.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ExtractionPolicyMaintenanceAdmissionTest.java#missingChunkRepairPreservesProtectedFailureOwner"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java"
      ],
      "class": "New",
      "behaviour": "The nonregular-replacement preservation controls now start with complete, current one-child families. Retiring a source replaced by a directory or symlink still removes only that family, retains both rows of the complete sibling family, and admits only the new descendant. Every existing deletion, retained-row and admission expectation is unchanged; malformed-family admission is covered by the C1a repair checks.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "P1 A complete family with matching stamps, policy, hash presence and permitted collection stays suppressed through both filters; force=true still admits it; a wrong collection, changed stamp, missing stored hash or old policy stays eligible.",
          "location": "task redesign-c1a request"
        },
        {
          "kind": "owner-words",
          "quote": "P3 Exclusions, cloud placeholders, nested-root scopes, outside-root and symlink cases unchanged.",
          "location": "task redesign-c1a request"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-c1a-preservation.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#restartReconciliationRetiresNonregularReplacements"
      ]
    }
  ]
}
```

## Run-evidence arguments

The before compatibility patch removes only newly required reuse-reader mock wiring from existing
controls and the direct setter in the maintenance fixture. It does not change inputs, calls,
assertions or production code. The repair fixtures reach sync through WorkerIngestService's real
wiring on both versions. The changed test files are automatically overlaid by --check; the six
additional overlays keep the other adapted controls compiling on the baseline. Each tree builds
into its own build directories.

Repair:

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-c1a-repair.json \
  --before d2bc02e1107e294442e91ebf3d88e78ad2e17d9a \
  --before-patch gates/test-intent/.changesets/redesign-c1a-before.patch \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncExtractionPolicyTest.java \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/services/DeclaredCollectionReconciliationTest.java \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsCandidateDiscoveryTest.java \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/services/DeclaredCollectionScanSnapshotTest.java \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceCandidateReconciliationTest.java \
  --overlay modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsChangedStampTest.java#ordinarySyncReadmitsDamagedIndexedFamily \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsChangedStampTest.java#candidateDiscoveryIncludesDamagedIndexedFamily \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ExtractionPolicyMaintenanceAdmissionTest.java#completedMissingChunkIsReadmittedWithoutForce
```

Preservation (no --before because these must pass before as well as after):

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-c1a-preservation.json \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsChangedStampTest.java#unchangedThousandFileSyncMeasuresIndexReadBudget \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncDirectoryOpsChangedStampTest.java#completeIndexedFamilyStaysSuppressedUnlessForced \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ExtractionPolicyMaintenanceAdmissionTest.java#missingChunkRepairPreservesProtectedFailureOwner \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherDeleteSafetyTest.java#restartReconciliationRetiresNonregularReplacements
```

Run preservation separately in the shared baseline clone with the eight test-file overlays and
compatibility patch; do not label passing baseline controls as assertion-fail-before evidence.
The existing complete module suites cover P1-P4, including policy/collection, cancellation,
walk exclusions, cloud placeholders, nested roots, symlinks and unavailable/capped census evidence.
The existing ChunkCompletenessAdmissionTest suite covers the unchanged single-file assessment,
including guarded legacy backfill and admission-reader currency.

## Builder observations

Round decision, 2026-11-03T14:13Z: the first focused round could not compile a test's
package-private-reader call; it now uses the public search API. The second round confirmed all
18 repair admissions but found fixture expectations: ordinary sync lacked a registered-volume
presence guard, and the Mockito census counter counted both a public overload and its delegate.
Before the third round, provide explicit presence authority for the disposable fixture and count
only the five-argument census entry. No production repair or scenario change is needed. The
third focused round passed. The subsequent full preservation run exposed two more existing mocks
without reuse-reader wiring (DeclaredCollectionScanSnapshotTest and
WorkerIngestServiceCandidateReconciliationTest). Their parent fixtures now supply successful family
certification; existing outcomes and all production code are unchanged. The next full
run checks these fixture adaptations. The full run
also reported an unrelated missing telemetry output file; no telemetry test or code is edited.
A subsequent fixture inspection found PruneOps explicitly reopens the serving reader. Held-reader
ordinary variants now delegate to the real runtime with only pruning disabled; both filters also
assert the old complete count, ordinal set and revision after the decision. This strengthens the
C1a-3 precondition while keeping all index rows and reuse assessment real. Fresh before checks
and the final full candidate run cover this stronger fixture.

`.agents/preflight` is absent. Shared resources: Gradle cache and slot scheduler.
No owner service is used or stopped.


## Measured filter-read budget (P6)

All three modes (DEFAULT, MAINTENANCE and strict CANDIDATE discovery) produced the same counts.
The census counter selects the public five-argument search entry rather than also counting its
delegating overload. The family counter intercepts actual Lucene point searches on the disposable
runtime's admission reader, including retries. Every batch contains at most 256 candidates.

| Per 1,000 unchanged files | Base d2bc02e110 | Candidate |
| --- | ---: | ---: |
| Census page queries | 4 | 4 |
| Family point queries | 0 | 1,000 |
| Admission batches | 0 | 4 |
| Filter queries total | 4 | 1,004 |

These are logical filter queries, not operating-system read syscalls. Existing prune work and
commit-time segment-metadata preparation are outside this counter and are unchanged. The added
cost is exactly one family point query per otherwise suppressed file in this unchanged fixture.
Complete families, force controls, collection/policy/stamp controls and protected failure owners
passed on the base; the 18 worker repair cases plus one SQLite maintenance repair case failed by
JUnit assertion for the required parent-only suppression reason. The full base Lucene suite had
1,008 cases, zero failures and three skips; the full base indexer suite had 2,507 cases, the one
intended repair assertion failure and 42 skips. The full base worker suite had 3,334 XML-reported
cases, 18 intended repair assertion failures, four environmental failures and 24 skips. No new
repair case was skipped.

## Known baseline environment failures

These four failures were observed on the base and the first full candidate worker run:

- LinuxRootMountInfoTest#missingDanglingAndAmbiguousUuidMappingsAreUnknown:
  NoSuchFileException for /dev/disk/by-uuid.
- WatcherUnavailabilityScopeTest#aNewerUnavailableIncarnationCannotBeClearedByAnOlderReconciliation:
  expected true, observed false.
- WatcherUnavailabilityScopeTest#endedUnavailableIncarnationIsRecoverableOnlyByItsOwnReconciliation:
  attributed root loss expected to preserve runtime-wide certainty, observed false.
- WatcherUnavailabilityScopeTest#inaccessibleRootWhoseAbsenceIsUnknownIsStillRootScoped:
  unreadable root expected to retain root-scoped uncertainty, observed false.

## Running-app limitation

The candidate distribution built successfully with :modules:ui:installDist. The first
justsearch.dev.start attempt and a 60-second acquire_when_free wait were blocked by another
active owner. At 2026-11-03T16:06Z the stack became free. The distribution was refreshed through
js-gradle-slot (five-second successful installDist), and start with skipBuild:true reached
ready_worker on the candidate's own disposable data directory.

Owned run: e8bc8497-7e78-4fab-81d9-e641cb0004ac, API port 38447, UI port 53877.
Recorded identities: runner PID 2066767, backend PID 2066817, frontend PID 2067384.
Data: tmp/c1a-real/data under this worktree; source fixture: tmp/c1a-real/corpus/chunked.txt.

The MCP ingestion call was refused before execution: "MCP tool call requires approval, but
approval policy is never". The ownership-scoped stop call for this same run was refused with
that identical policy error. No source fixture was ingested and no chunk edit or migration was
performed. The running-app restoration and live-migration repeat are unverified. The owned run
remains active because stopping it is blocked by the MCP approval gate; an operator must stop
that exact run or change the approval policy. No other owner's application was used or stopped.

Shared check resources: Gradle cache and slot scheduler, followed by the dev stack only after it
became free. All Lucene/SQLite test state and build folders belong to the candidate or its shared
baseline clone. No CI runner, merge, push, commit, run-evidence output or acceptance record was
created by this builder.


The first full candidate indexer run reported one additional preservation-fixture failure:
WatcherDeleteSafetyTest#restartReconciliationRetiresNonregularReplacements[directory] expected one
admission but observed two. Its retained sibling had a placeholder child without ordinal, total or
revision metadata, so C1a correctly classified it as damaged. The fixture now supplies complete,
current one-child families and asserts their reuse precondition before replacement. Existing
expected row counts, deletions and admissions remain unchanged; no restart-path code is edited.
The final full run and a focused baseline preservation run cover this corrected fixture.


The final worker-services suite reported 3,334 XML cases with exactly the four baseline failures
above and 24 existing skips. The strengthened held-reader checks, both additional mock controls,
all P6 measurements and the unchanged telemetry wire-format check passed. The final Lucene suite
reported 1,008 cases with zero failures and three existing skips. A fresh baseline focused run of
the strengthened fixtures had exactly the 18 intended worker assertion failures and no skips;
the telemetry check, collection snapshot controls and force control passed there. The corrected
nonregular-replacement fixture also passed all three baseline variants with zero skips.


## Final builder checks

- env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew -PskipWebBuild=true
  :modules:worker-services:test :modules:adapters-lucene:test :modules:indexer-worker:test --continue:
  final run completed in 28m 11s. Indexer: 2,507 XML cases, zero failures, 39 existing skips.
  Lucene: 1,008 cases, zero failures, three existing skips. Worker-services: 3,334 cases,
  exactly the four baseline environmental failures, 24 existing skips. All 19 C1a repair
  cases passed without skips, including the strengthened stale-reader variants.
- Fresh base worker selection: exactly the 18 intended repair assertion failures; unchanged
  force/collection controls and telemetry passed. The maintenance repair previously failed
  by the intended assertion on the base; its final candidate class had six passes, zero skips.
- Base nonregular-replacement selection: all three corrected fixture variants passed, zero skips.
- env -u DISPLAY -u WAYLAND_DISPLAY node scripts/dev/java-prepush.mjs --unreferenced:
  final run passed in 5m 54s (249 tasks), including compilation, PMD, selected integration
  suites and UnreferencedCodeTest. It retains the script's existing LinuxWatchedDriveReturnIntegrationTest
  exclusion. Initial QA caught a missing Mockito matcher import and unnecessary qualification
  caused by importing HashSet; both were repaired without behavioral changes or suppressions.
- The first full candidate worker run also had a missing telemetry NDJSON output file; that
  unchanged test passed in the fresh baseline selection and the final full candidate worker run.
- git diff --check, compatibility-patch reverse applicability, and changeset digest parsing passed.
- .agents/preflight is absent. No test expectation, existing check, tag or guard was weakened,
  disabled or deleted. Running-app and live-migration validation remain blocked as stated above.

Builder elapsed interval: 2026-11-03T13:42Z–16:16Z (about 2h 34m); assignment estimate 2–4 hours.
Most elapsed time was shared Gradle-slot waiting and complete module suites.

Final read-only check at 2026-11-03T16:16Z confirmed the owned run remains ACTIVE, HTTP-ready and worker-ready.
