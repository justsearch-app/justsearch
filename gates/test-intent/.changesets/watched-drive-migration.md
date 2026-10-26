---
schema: test-intent.v1
task: drive-migration
author-role: builder
author-session: codex-drive-migration-builder-20261024
---

# Watched-drive migration regression

These expectations implement the orchestrator's settled D1-D9/A1/A3 brief under delegated autonomy:
successful promotion, current content, exact acknowledgement, readable fenced status and automatic
retry. Replace the disabled reproduction's refusal expectation with that outcome; keep its real
loop mounts, identity checks and owned cleanup.
The separate watcher-admission case preserves the existing final-fence handoff contract.
A3 moves current-byte preparation ahead of generic drains without moving accepted receipts.
Fixture cleanup owns boot-thread cancellation and physical drainage; no new product-wide quit API remains.
Default ownership uses the index policy's null representation and the live watcher's `default`
name interchangeably; named collections must still match. Replay must preserve a later admission's
exact revision, and its projection-verification refusal must appear in status.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxWatchedDriveReturnIntegrationTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Defect pin",
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no data loss",
        "reproduction": "A real loop-backed watched drive leaves at a held migration barrier; its retained reconciliation debt and vanished-file UPSERT prevent a writable promoted runtime."
      },
      "reproduction": "returnDuringBootMigrationPromotes holds migration-before-switching or migration-green-drained, unmounts the owned loop filesystem, deletes a source elsewhere, returns the same root, and attempts promotion with and without restart.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive repair under reviewed A3: conditional current-byte preparation/ordered reconciliation and fixture-only physical shutdown, followed by required checks. No product lifecycle expansion.",
          "choice": "canonicalise default ownership consistently in reconciliation's field comparison, the queue comparison and admission; keep exact-revision verification; and make a verification refusal set the visible refusal/fence status instead of only logging (D7).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive repair under reviewed A3: conditional current-byte preparation/ordered reconciliation and fixture-only physical shutdown, followed by required checks. No product lifecycle expansion.",
          "choice": "D1: The drive leaves during a held cutover; a file under it is deleted while away; it returns with the same root identity. After cutover (with and without an Engine restart in between), the migration promotes; one writable runtime serves search and ingest; the deleted file's document is gone; every other document is present with its label.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive repair under reviewed A3: conditional current-byte preparation/ordered reconciliation and fixture-only physical shutdown, followed by required checks. No product lifecycle expansion.",
          "choice": "D2: While away, without any companion DELETE: a file is recreated at the same path with new content; a file is modified (including a change that keeps size and mtime). After promotion, the index holds the current content; nothing current is removed.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive repair under reviewed A3: conditional current-byte preparation/ordered reconciliation and fixture-only physical shutdown, followed by required checks. No product lifecycle expansion.",
          "choice": "D5: Concurrency: (a) admission blocked by the final fence during replay is applied after promotion; (b) an exact-revision receipt replaced during unfenced settlement wins over the older debt.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive repair under reviewed A3: conditional current-byte preparation/ordered reconciliation and fixture-only physical shutdown, followed by required checks. No product lifecycle expansion.",
          "choice": "D7: When refusing, the Engine keeps a readable index, reports a visible fenced status naming the reason, and retries automatically when the drive and evidence return; existing operator recovery is unchanged. Indefinite refusal with evidence available is a failure.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive repair under reviewed A3: conditional current-byte preparation/ordered reconciliation and fixture-only physical shutdown, followed by required checks. No product lifecycle expansion.",
          "choice": "D8: The integration cases skip (never pass) when loop/udisks prerequisites are missing, and leave no loop device or mount behind.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive repair under reviewed A3: conditional current-byte preparation/ordered reconciliation and fixture-only physical shutdown, followed by required checks. No product lifecycle expansion.",
          "choice": "A1: when an incarnation ends because its root became unavailable (its volume left), record that as uncertainty scoped to that root, which only the fresh reconciliation preflight of that root (under the final fence, with the evidence rules above) can clear.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive repair under reviewed A3: conditional current-byte preparation/ordered reconciliation and fixture-only physical shutdown, followed by required checks. No product lifecycle expansion.",
          "label": "agent choice",
          "choice": "A3 routine implementation: preserve persisted journal order and contents; prepare eligible exact/owned pending admissions with fresh current-byte proof under the actual writer fence and reserved queue transaction; defer insertion/DONE repair to ordered apply; repeat preparation while draining. Preserve original failures and drain only the fixture-owned boot threads/client executors/admissions within one fixture deadline; retain owners and backing files on incomplete cleanup. Production lifecycle semantics return to pre-A2."
        }
      ],
      "evidence": "gates/test-intent/evidence/watched-drive-migration.json",
      "newChecks": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxWatchedDriveReturnIntegrationTest.java#returnDuringBootMigrationPromotes"
      ]
    },
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Adaptation",
      "reproduction": "A failed D1 has a durable periodic admission waiting behind cutover. Fixture cleanup must preserve the primary error, independently cancel owned groups, report incomplete physical drain and support retry without closing the live index owner.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive repair under reviewed A3: conditional current-byte preparation/ordered reconciliation and fixture-only physical shutdown, followed by required checks. No product lifecycle expansion.",
          "label": "agent choice",
          "choice": "A3 routine implementation: preserve persisted journal order and contents; prepare eligible exact/owned pending admissions with fresh current-byte proof under the actual writer fence and reserved queue transaction; defer insertion/DONE repair to ordered apply; repeat preparation while draining. Preserve original failures and drain only the fixture-owned boot threads/client executors/admissions within one fixture deadline; retain owners and backing files on incomplete cleanup. Production lifecycle semantics return to pre-A2."
        }
      ],
      "moved": "instrumentation",
      "whatMoved": "The shared fixture captures its boot threads under their server start monitor, signals both migration and recorded groups independently, tracks one client-close future, and waits for real physical thread/executor/admission drain within a shared fixture budget. It retains stateful owners and backing files when incomplete. Existing HTTP/reload/search checks, data recovery and index ownership fence remain; production quiescence semantics are restored. HTTP stop and transport close now start independently once as tracked physical actions; their success and exit share the same monotonic budget, and failed cleanup retains private configuration and owners for retry. PMD-only normalization uses already imported List/Duration/mockingDetails names, without changing shutdown ordering, guards or waits."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WatcherMigrationReplayTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Adaptation",
      "moved": "signature",
      "whatMoved": "The retry-preservation control now binds the complete real journal and exact root receipt through A3 preparation. It still asserts only the intended retry_after changes, an advanced cursor observes it, invalid proof wakes nothing and all other row fields/receipts remain unchanged. The old signature is invoked only in an unchanged pre-A3 comparison. The baseline adapter exercises old refusal/backoff when neither preparatory nor wake API exists, retaining all real row/receipt/claim assertions and invalid-proof controls.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive repair under reviewed A3: conditional current-byte preparation/ordered reconciliation and fixture-only physical shutdown, followed by required checks. No product lifecycle expansion.",
          "label": "agent choice",
          "choice": "A3 routine implementation: preserve persisted journal order and contents; prepare eligible exact/owned pending admissions with fresh current-byte proof under the actual writer fence and reserved queue transaction; defer insertion/DONE repair to ordered apply; repeat preparation while draining. Preserve original failures and drain only the fixture-owned boot threads/client executors/admissions within one fixture deadline; retain owners and backing files on incomplete cleanup. Production lifecycle semantics return to pre-A2."
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReturnedRootCurrentByteReplayTest.java"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "repaired": true,
      "behaviour": "A3 H1-to-H2 preparation must pass through the production claim/extractor/writer/commit path, including identical metadata and pristine claims that first defer. Owned DONE H2-to-H3/debt handoff after restart, absent wake, independent/protected rows and broad-delete ordering retain their separate controls. The conditional write rejects stale frozen journal/full-row expectations before mutation and rolls SQL back when post-mutation fresh proof fails. The production writer re-probes uncommitted sources during issued extraction and pending commit. Parameterized display names include their existing method identity so stock evidence associates the same arguments and assertions with the requested check.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive repair under reviewed A3: conditional current-byte preparation/ordered reconciliation and fixture-only physical shutdown, followed by required checks. No product lifecycle expansion.",
          "label": "agent choice",
          "choice": "A3 routine implementation: preserve persisted journal order and contents; prepare eligible exact/owned pending admissions with fresh current-byte proof under the actual writer fence and reserved queue transaction; defer insertion/DONE repair to ordered apply; repeat preparation while draining. Preserve original failures and drain only the fixture-owned boot threads/client executors/admissions within one fixture deadline; retain owners and backing files on incomplete cleanup. Production lifecycle semantics return to pre-A2."
        }
      ],
      "evidence": "gates/test-intent/evidence/watched-drive-current-byte.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReturnedRootCurrentByteReplayTest.java#unavailablePredecessorConvergesThroughTheProductionExtractor",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReturnedRootCurrentByteReplayTest.java#staleFrozenExpectationsRefuseBeforeAnyConditionalMutation",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReturnedRootCurrentByteReplayTest.java#failedPostMutationRootProofRollsBackSqlAndRetainsDebtForRetry",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReturnedRootCurrentByteReplayTest.java#freshFileSuccessorCannotHandOffAcrossAnInterveningPathReceipt"
      ]
    },
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxWatchedDriveReturnIntegrationTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "New",
      "behaviour": "Fixture-owned stalled migration and durable periodic sync must physically exit/drain while retaining original assertions. A zero, submillisecond or2500ms budget must report incomplete physical cleanup, retain stateful owners/files/a single close future and allow retry; cleanup errors remain suppressed onto the body error. This does not claim a product-wide quit fix.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive repair under reviewed A3: conditional current-byte preparation/ordered reconciliation and fixture-only physical shutdown, followed by required checks. No product lifecycle expansion.",
          "label": "agent choice",
          "choice": "A3 routine implementation: preserve persisted journal order and contents; prepare eligible exact/owned pending admissions with fresh current-byte proof under the actual writer fence and reserved queue transaction; defer insertion/DONE repair to ordered apply; repeat preparation while draining. Preserve original failures and drain only the fixture-owned boot threads/client executors/admissions within one fixture deadline; retain owners and backing files on incomplete cleanup. Production lifecycle semantics return to pre-A2."
        }
      ],
      "evidence": "gates/test-intent/evidence/watched-drive-fixture.json",
      "newChecks": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxWatchedDriveReturnIntegrationTest.java#stalledCutoverCleanupRetainsBodyFailureAndPhysicallyDrainsPeriodicAdmission",
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxWatchedDriveReturnIntegrationTest.java#fixtureBudgetReportsIncompleteExitAndSupportsRetry"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReturnedRootCurrentByteReplayTest.java"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "behaviour": "Ordinary non-recorded scan batch attribution survives H1-to-H2-to-H3 preparation and unacknowledged commit/restart with exact debt handoff; another ordinary attribution confers no ownership. Real admission binding composes reconciliation with the actual loop file writer fence; uncooperative source changes remain detectable and fresh retries share the original promotion deadline. The stock repaired-behavior proof selects the pending succession assertion; the separate restart control and its intended ownership-refusal before are retained in tmp/round9/successor-final-before.xml and the final focused/full suite XML. Parameterized display names include their existing method identity so stock evidence associates the same arguments and assertions with the requested check.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive repair under reviewed A3: conditional current-byte preparation/ordered reconciliation and fixture-only physical shutdown, followed by required checks. No product lifecycle expansion.",
          "label": "agent choice",
          "choice": "Ordinary non-recorded scan batch attribution survives H1-to-H2-to-H3 preparation and unacknowledged commit/restart with exact debt handoff; another ordinary attribution confers no ownership. Real admission binding composes reconciliation with the actual loop file writer fence; uncooperative source changes remain detectable and fresh retries share the original promotion deadline."
        }
      ],
      "evidence": "gates/test-intent/evidence/watched-drive-ordinary-scan.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReturnedRootCurrentByteReplayTest.java#ordinaryScanPendingH2RepairsH3AndPreservesScanAttribution"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReturnedRootCurrentByteReplayTest.java"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "behaviour": "The production extractor fixture binds the same mutation admission owner as DefaultWorkerAppServices; conditional reconciliation excludes a competing actual loop writer until the real transaction completes. This preserves the critical-section ordering contract. Parameterized display names include their existing method identity so stock evidence associates the same arguments and assertions with the requested check.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive repair under reviewed A3: conditional current-byte preparation/ordered reconciliation and fixture-only physical shutdown, followed by required checks. No product lifecycle expansion.",
          "label": "agent choice",
          "choice": "The production extractor fixture binds the same mutation admission owner as DefaultWorkerAppServices; conditional reconciliation excludes a competing actual loop writer until the real transaction completes. This preserves the critical-section ordering contract."
        }
      ],
      "evidence": "gates/test-intent/evidence/watched-drive-writer-fence.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReturnedRootCurrentByteReplayTest.java#reconciliationExcludesTheActualLoopWriterUntilConditionalAdmissionFinishes"
      ]
    },
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxWatchedDriveReturnIntegrationTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "New",
      "behaviour": "With a real outstanding HTTP handler, zero, submillisecond and2500ms cleanup must start HTTP/transport cancellation independently, return within the shared allowance, retain physical owners/configuration/backing directories on incomplete exit, and permit successful retry after release. Existing ownership and body-error protections remain. The control stays in the shared-fixture consumer so the stock before runner transplants the new assertion without transplanting the repaired Fixture.close into the before tree.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive repair under reviewed A3: conditional current-byte preparation/ordered reconciliation and fixture-only physical shutdown, followed by required checks. No product lifecycle expansion.",
          "label": "agent choice",
          "choice": "With a real outstanding HTTP handler, zero, submillisecond and2500ms cleanup must start HTTP/transport cancellation independently, return within the shared allowance, retain physical owners/configuration/backing directories on incomplete exit, and permit successful retry after release. Existing ownership and body-error protections remain."
        }
      ],
      "evidence": "gates/test-intent/evidence/watched-drive-http-budget.json",
      "newChecks": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/LinuxWatchedDriveReturnIntegrationTest.java#outstandingHttpCleanupSharesTheBudgetAndRetainsOwnersUntilRetry"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReturnedRootCurrentByteReplayTest.java"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "behaviour": "A3 preparation may wake only eligible PENDING admissions. Proven absence never changes DONE, FAILED or issued row waits; their exact rows and receipts survive unchanged. The source absence does not widen preparation authority.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive repair under reviewed A3: conditional current-byte preparation/ordered reconciliation and fixture-only physical shutdown, followed by required checks. No product lifecycle expansion.",
          "label": "agent choice",
          "choice": "A3 preparation may wake only eligible PENDING admissions. Proven absence never changes DONE, FAILED or issued row waits; their exact rows and receipts survive unchanged. The source absence does not widen preparation authority."
        }
      ],
      "evidence": "gates/test-intent/evidence/watched-drive-absent-state.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/ReturnedRootCurrentByteReplayTest.java#absentPreparationPreservesDoneFailedAndIssuedWaits"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/OutstandingHttpFailureCleanupIntegrationTest.java"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "repaired": true,
      "behaviour": "A real closed-client exchange refusal before handler entry must retain its original Exception/Error identity, suppress a later close-future failure, and physically retire the fixture and restore private globals. The before adapter executes snapshot d97 original finally when the test cleanup helper is absent; its impossible exit wait must cause the masking assertion failure, not a missing-method error.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration user turn 2026-10-25 takeover: autonomous completion of this existing repair",
          "delegationScope": "Complete only the existing D1-D9/A1 watched-drive repair under reviewed A3: conditional current-byte preparation/ordered reconciliation and fixture-only physical shutdown, followed by required checks. No product lifecycle expansion.",
          "label": "agent choice",
          "choice": "A real closed-client exchange refusal before handler entry must retain its original Exception/Error identity, suppress a later close-future failure, and physically retire the fixture and restore private globals. The before adapter executes snapshot d97 original finally when the test cleanup helper is absent; its impossible exit wait must cause the masking assertion failure, not a missing-method error."
        }
      ],
      "evidence": "gates/test-intent/evidence/watched-drive-pre-entry-cleanup.json",
      "newChecks": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/OutstandingHttpFailureCleanupIntegrationTest.java#preEntryExchangeFailureKeepsItsIdentityAndClosesTheFixture"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/NativeProjectionHarnessTest.java"
      ],
      "class": "New",
      "behaviour": "A4/R1-R6: exercise existing private hasHit/await with actual Bootstrap capture/ClientLease/withClient/close and a mocked physical search: exact typed VECTOR loading is pending, then only the expected hit with effective VECTOR succeeds; all nonmatching error identities and TEXT loading propagate unchanged; invalid responses remain pending; the original 180 second polling deadline and interruption release ownership and publish no action marker. Existing successor and exact cut/receipt controls are retained. The journal-loss skip branch is unchanged and inspected, not claimed runtime verified by these controls.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration request",
          "delegationScope": "Existing drive repair only; approved A4 installed native fixture correction and R1-R8 controls; no production model/query policy or budget change.",
          "choice": "Use reflection to exercise unchanged private hasHit/await without creating production API; actual client leases retain and release a controlled physical serving view. Preserve all existing tests and installed native assertions. The exact loading-only search catch adapts fixture readiness; it does not establish a product defect or a baseline native pass.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/watched-drive-native-readiness.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/NativeProjectionHarnessTest.java#vectorLoadingIsPendingThenRequiresAnActualVectorHitAndReleasesEachLease"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/ExtractionInfrastructureTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "A5 fixture plumbing only: create a unique absolute scratch root beneath the existing JUnit TempDir, pass it as one java.io.tmpdir argument to this persistent child, and discover/damage only its contained snapshot directory. The original parent-temp discovery failed before outage injection on pristine c8: expected one child snapshot folder, found zero, with effective parent private Gradle tmp and child /tmp. Preserve every original input, assertion, grace, retry, attempt, canary, recovery, timeout and selection. The method has two source rows and no separate unrelated-row assertion. Add partial-constructor cleanup that preserves its primary error and independently closes initialized loop/timebox/child/provider/executor/queue resources; baseline lacked this guard. Ordinary fault cleanup still restores permissions/directory before closure. Current fixture is also transplanted to pristine c8 production for preservation, not product fail-before.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; ask me only what its section 10 lists.",
          "delegationLocation": "task drive-migration request",
          "delegationScope": "Conditional approved A5/T1-T6 extraction fixture correction after pristine-main constructor failures and effective JVM temp mismatch are established; no production or global profile policy changes.",
          "choice": "Use the fixture-owned child scratch root and matching contained discovery; retain persistent-child outage/recovery semantics and all original oracles. Guard constructor initialization and independently close initialized owners with cleanup failures suppressed onto the original error. Record old c8 failure as invalid fixture evidence and corrected c8 execution as preservation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/watched-drive-extraction-fixture.json"
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1759f-94df-7be2-8fea-d2d57d2d0c9c",
  "verdict": "accept",
  "digest": "sha256:3e3efc4bd3901e677077ad33cf692460e3c4816cfc6b778181c4d79582a63d69",
  "note": "Verified immutable C14 c14d550f4965013ec85ef9b8cd1cbbbc01358e86. Consolidated evidence: tmp/drive-verifier-evidence/report-private-home.txt; actual private child JVM properties and native receipts/crash/restart: tmp/drive-verifier-evidence/native-cpu-private-home/actual-installed-properties.json and native-proof.json. Prior independent before/physical/module evidence retained by validated identity; exact accepted baseline descriptor failure and optional skips remain labeled."
}
```
