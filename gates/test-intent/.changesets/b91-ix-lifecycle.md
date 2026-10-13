---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: codex-b91-ix-lifecycle-20261013
---

# Lifecycle ownership and exit accounting

The new assertions cover the assigned lifecycle, state, security, concurrency and diagnostic findings.
Existing expectations are retained. The added hold actuator flag affects only the two new cases.
The helper rollback check opens a real Windows process and records its native birth before cleanup;
the bounded witness check blocks a real writer attached to an empty Windows job, without joining or
terminating any process. Resume checks use the existing birth-guarded stand-in server.

The Node tests in scripts/dev/test-dev-runner-ix-lifecycle.mjs exercise production recorder/census
functions and the actual running-record construction, with local filesystem and IO seams. They
cover unknown exits, exact parser ownership and nested containment, genuine helper crashes, and
retry-incarnation fallback receipts. Script checks are outside the canonical JVM evidence producer.
Full native parser teardown and full dev-runner retry/fallback replay remain separate verification
limits; no live stack or GPU workload is used here.

Owner delegation was checked against the owner's transcript, not agent-written review prose.
Execution details and safety exclusions are in F:/agent-sandbox/design-b6d536/codex/b91-ix-lifecycle/report.md.
Independent test-intent acceptance is pending; the builder does not accept its own work.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/OwnedHelperRollbackTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Failed helper admission rolls back the retained spawn or returns and retains its exact unconfirmed survivor.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design decisions for owner item 6, process ownership and exit accounting, and supporting lifecycle health/recovery behavior.",
          "choice": "Failed helper admission rolls back the retained spawn or returns and retains its exact unconfirmed survivor.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-app-api.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/OwnedHelperRollbackTest.java#failedHandleAcquisitionRollsBackSpawn",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/OwnedHelperRollbackTest.java#failedRollbackReturnsTheExactSurvivor"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsJobWitnessBoundTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A blocked ledger append cannot extend the witness shutdown fence beyond its allowance; failure prevents ownership release.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design decisions for owner item 6, process ownership and exit accounting, and supporting lifecycle health/recovery behavior.",
          "choice": "A blocked ledger append cannot extend the witness shutdown fence beyond its allowance; failure prevents ownership release.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-app-api.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsJobWitnessBoundTest.java#blockedStorageCannotOutliveFinishAllowance"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerWriterTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A failed ledger write emits one warning per failure episode and recovery follows durable pending gaps and a new record.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design decisions for owner item 6, process ownership and exit accounting, and supporting lifecycle health/recovery behavior.",
          "choice": "A failed ledger write emits one warning per failure episode and recovery follows durable pending gaps and a new record.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-app-api.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerWriterTest.java#persistenceFailureWarnsOnceAndRecoveryIsReported"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResumeProbeTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Resume requires a bounded successful probe; open, failed or cancelled generation leases do not prove server health.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design decisions for owner item 6, process ownership and exit accounting, and supporting lifecycle health/recovery behavior.",
          "choice": "Resume requires a bounded successful probe; open, failed or cancelled generation leases do not prove server health.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-app-inference.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResumeProbeTest.java#anOpenGenerationCannotPassAFailingResumeProbe",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResumeProbeTest.java#aClosedGenerationCannotPassAFailingResumeProbe"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineShutdownDeviceLostTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "An unwritten device-loss reservation cannot close the Engine or authorize a free requested restart.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design decisions for owner item 6, process ownership and exit accounting, and supporting lifecycle health/recovery behavior.",
          "choice": "An unwritten device-loss reservation cannot close the Engine or authorize a free requested restart.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-app-engine.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineShutdownDeviceLostTest.java#failedDeviceLossReservationDoesNotCloseOrRequestRestart"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatDeviceLossTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Device-loss reservation failure leaves chat in the existing recovery-failure state without requesting restart.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design decisions for owner item 6, process ownership and exit accounting, and supporting lifecycle health/recovery behavior.",
          "choice": "Device-loss reservation failure leaves chat in the existing recovery-failure state without requesting restart.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-app-services.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatDeviceLossTest.java#aReservationFailureLatchesChatInsteadOfRequestingRestart"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Running holds wait for serialized startup readiness and receive startup failure instead of success from supersession.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design decisions for owner item 6, process ownership and exit accounting, and supporting lifecycle health/recovery behavior.",
          "choice": "Running holds wait for serialized startup readiness and receive startup failure instead of success from supersession.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-app-services.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#equivalentRunningHoldsWaitForTheActualStart",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#equivalentRunningHoldsBothObserveStartFailure"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/diagnostics/DiagnosticsServiceImplRedactionTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Diagnostics include both redacted ledger families, retained generations and loss records, and explicitly describe collection failure.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design decisions for owner item 6, process ownership and exit accounting, and supporting lifecycle health/recovery behavior.",
          "choice": "Diagnostics include both redacted ledger families, retained generations and loss records, and explicitly describe collection failure.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-app-services.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/diagnostics/DiagnosticsServiceImplRedactionTest.java#zipIncludesRetainedExitEvidenceAndLossRecords",
        "modules/app-services/src/test/java/io/justsearch/app/services/diagnostics/DiagnosticsServiceImplRedactionTest.java#zipExplainsUnavailableExitEvidence"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppDeviceLostRestartTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The production self-intent adapter propagates device-loss persistence failure; reopened durable history counts admitted restarts.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design decisions for owner item 6, process ownership and exit accounting, and supporting lifecycle health/recovery behavior.",
          "choice": "The production self-intent adapter propagates device-loss persistence failure; reopened durable history counts admitted restarts.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-ui.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppDeviceLostRestartTest.java#failedSelfIntentPersistenceCannotAuthorizeADeviceLossRestart"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingStateAssemblerTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Production status publishes exit-ledger write degradation scoped to the data directory and clears it after durable recovery.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design decisions for owner item 6, process ownership and exit accounting, and supporting lifecycle health/recovery behavior.",
          "choice": "Production status publishes exit-ledger write degradation scoped to the data directory and clears it after durable recovery.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-ui.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingStateAssemblerTest.java#ledgerWriteFailureIsVisibleUntilDurableRecovery"
      ]
    }
  ]
}
```
