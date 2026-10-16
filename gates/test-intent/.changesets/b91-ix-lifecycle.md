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

Script tests under scripts/dev are outside the gate. The gate flags only product test scope
(modules/ and watched baselines), and run-evidence has no runner for Node scripts. This changeset
therefore has no entries for them. Two earlier Adaptation entries are dropped because they named
`scripts/dev/test-dev-runner-stop-ownership.mjs` and `scripts/dev/test-dev-runner-ix-lifecycle.mjs`,
which no diff can flag (stale-entry-item). Their adaptations are kept here:

- `test-dev-runner-stop-ownership.mjs`: the repaired unknown-observation contract records UNKNOWN
  and no code for an unobserved terminate/taskkill, and records attemptedEnding separately. Every
  census-observed case, the forced flag, the observed exit time and the one-exit-per-incarnation
  check are unchanged.
- `test-dev-runner-ix-lifecycle.mjs`: the vm context of 'running record preserves the first-ready
  retry incarnation' supplies the integration's initial exit-census names (actuator.current(),
  initialCensusState, initialCensusIdentity). Inputs, observation and expected outcome are
  unchanged.

Both were run directly with node on the merged tree b6d536/final (da09d9f15, which carries b102's
stop census). ix-lifecycle passed 10 of 10 and stop-ownership passed 16 of 16. This was
candidate-only; no fail-before applies to an adaptation, and the runner cannot record Node tests.

Owner delegation was checked against the owner's transcript, not agent-written review prose.
Execution details and safety exclusions are in F:/agent-sandbox/design-b6d536/codex/b91-ix-lifecycle/report.md.
Independent test-intent acceptance is pending; the builder does not accept its own work.

Named limit retained in round 3: on slots=1 (including VDU), long prompt processing or a
non-streaming reply with no token for the 10-second probe timeout may be probed and relaunched.
This is unchanged behavior, not a new health guarantee.

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
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsJobWitnessReserveTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Reserve 150 ms inside the total witness allowance for an in-flight append; the 500-append measured p99 ranges from 3.39 to 9.79 ms and the reserve also covers a 100 ms injected stall.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Owner item 6 process ownership and exit accounting; round-2 orchestrator decisions under this delegation.",
          "choice": "Reserve 150 ms inside the total witness allowance for an in-flight append; the 500-append measured p99 ranges from 3.39 to 9.79 ms and the reserve also covers a 100 ms injected stall.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-r2-app-api.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsJobWitnessReserveTest.java#anAppendInFlightAtTheDrainDeadlineCanFinishWithinTheFenceReserve"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/HelperGapSchemaTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Both ownership rollback and boot-buffer overflow gap records conform to the existing ledger schema, including the since field. CI portability repair: make the fake process fail its command observation with a fixture SecurityException, exercising the same ownership rollback and schema assertions on POSIX as on Windows; POSIX does not perform the Windows OpenProcess lookup of its nonexistent PID. The overflow scenario is retained.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Owner item 6 process ownership and exit accounting; round-2 orchestrator decisions under this delegation.",
          "choice": "Both ownership rollback and boot-buffer overflow gap records conform to the existing ledger schema, including the since field.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-r2-app-api.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/HelperGapSchemaTest.java#rollbackGapConformsToTheLedgerSchema",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/HelperGapSchemaTest.java#bootBufferGapConformsToTheLedgerSchema"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerCauseWarningTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Every distinct I/O cause within a ledger failure episode emits a warning naming its cause; repeats of that cause stay suppressed.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Owner item 6 process ownership and exit accounting; round-2 orchestrator decisions under this delegation.",
          "choice": "Every distinct I/O cause within a ledger failure episode emits a warning naming its cause; repeats of that cause stay suppressed.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-r2-app-api.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerCauseWarningTest.java#distinctWriteFailuresWarnOncePerCauseWithinAnEpisode"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppRestartReservationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "An unused device-loss reservation consumes no persistent restart budget; before writer readiness a restart remains permitted, counted in memory, with an explicit unpersisted-count status cause.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Owner item 6 process ownership and exit accounting; round-2 orchestrator decisions under this delegation.",
          "choice": "An unused device-loss reservation consumes no persistent restart budget; before writer readiness a restart remains permitted, counted in memory, with an explicit unpersisted-count status cause.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-r2-ui.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppRestartReservationTest.java#aQuitWinningAfterReservationDoesNotSpendTheRestartBudget",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppRestartReservationTest.java#aRestartBeforeWriterReadinessUsesMemoryAndReportsItsUnpersistedCount"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppWitnessFailureTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Failure to fence witness recording inside main finally remains an unclean exit with its reason and retained instance lock, and reaches the normal fatal startup exit. The disposable probe launches the current JVM executable on every OS and records its PID with native FILETIME on Windows or its JDK child start instant on POSIX, rechecking the same identity before held-handle cleanup. Bounded exit, the normal-exit shutdown-hook marker and visible failure-reason assertions are unchanged. The original four-selector evidence is regenerated against 32d00dda8 with the same witness-probe overlay and no patches.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Owner item 6 process ownership and exit accounting; round-2 orchestrator decisions under this delegation.",
          "choice": "Failure to fence witness recording inside main finally remains an unclean exit with its reason and retained instance lock, and reaches the normal fatal startup exit.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-r2-ui.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppWitnessFailureTest.java#aWitnessFailureInMainFinallyStillReachesTheNormalFatalExit"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResumeProgressTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A real single-slot GENERATE stream that produces tokens after resume is health proof, stays open beyond the grace period, and is never probed or relaunched while progressing.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Owner item 6 process ownership and exit accounting; round-2 orchestrator decisions under this delegation.",
          "choice": "A real single-slot GENERATE stream that produces tokens after resume is health proof, stays open beyond the grace period, and is never probed or relaunched while progressing.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-r2-app-inference.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResumeProgressTest.java#aSingleSlotReplyProgressingAfterResumePassesWithoutAProbeOrRelaunch"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OpenAiCompatTokenProgressTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The OpenAI proxy forwards token progress to its held generation lease, including reasoning tokens, while heartbeats and empty deltas give no proof of token work.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Owner item 6 process ownership and exit accounting; round-2 orchestrator decisions under this delegation.",
          "choice": "The OpenAI proxy forwards token progress to its held generation lease, including reasoning tokens, while heartbeats and empty deltas give no proof of token work.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-r2-ui.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OpenAiCompatTokenProgressTest.java#proxyReportsTokensButDoesNotCreditHeartbeatsOrEmptyDeltas"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/HealthyFakeLlamaServer.java",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppWitnessFailureProbe.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Add opt-in single-slot streaming instrumentation to the existing fake server, preserving all existing behaviors, and an isolated main-entry probe that injects startup and witness-fence failures before any live stack starts. Fixture cleanup retains PID plus recorded creation-time ownership.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Owner item 6 process ownership and exit accounting; round-2 orchestrator decisions under this delegation.",
          "choice": "Exercise progressing single-slot replies and fatal startup finally through the real Java components using safe disposable fixtures.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppRestartReservationTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "Preservation: a failed reservation write is refused before chat becomes terminal; this passes on 32d00dda8 and the final candidate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Owner item 6 process ownership and exit accounting, while preserving the existing durable refusal before chat enters terminal shutdown.",
          "choice": "Keep refusal of a device-loss reservation when persistence fails before terminal chat shutdown. A forced blank delimiter checks the actual ledger storage without publishing an exit intent; only the committed DEVICE_LOST self-intent spends the budget.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-r2-preservation-ui.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppRestartReservationTest.java#reservationPersistenceFailureStillRefusesBeforeTerminalShutdown"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppRestartCommitFailureTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A failed second device-loss write refuses restart without fatal halt, ordered close or published hand-off, keeps chat latched and retryable with a visible reason, spends no budget or pending exit intent, and releases the run-once flag for a later clean quit. A dispatcher refusal also cancels its unused reservation instead of blocking the next operator retry.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Owner item 6 process ownership and exit accounting; round-3 orchestrator decisions under this delegation.",
          "label": "agent choice",
          "choice": "A failed second device-loss write refuses restart without fatal halt, ordered close or published hand-off, keeps chat latched and retryable with a visible reason, spends no budget or pending exit intent, and releases the run-once flag for a later clean quit. A dispatcher refusal also cancels its unused reservation instead of blocking the next operator retry."
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-r3-ui.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppRestartCommitFailureTest.java#failedCommitRefusesRestartWithoutClosingOrHalting",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppRestartCommitFailureTest.java#aLaterQuitRecordsItsOwnIntentAndExitsCleanly",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppRestartCommitFailureTest.java#commitFailureKeepsChatLatchedAndRetryable",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppRestartCommitFailureTest.java#aFailedDispatchReleasesItsUnusedReservation"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/diagnostics/DiagnosticsExitLedgerBudgetTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Exit-ledger diagnostics retain newest record suffixes across all ledger files within a shared 8 MiB redacted-byte budget and state how many source bytes were omitted.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Owner item 6 process ownership and exit accounting; round-3 orchestrator decisions under this delegation.",
          "label": "agent choice",
          "choice": "Exit-ledger diagnostics retain newest record suffixes across all ledger files within a shared 8 MiB redacted-byte budget and state how many source bytes were omitted."
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-r3-app-services.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/diagnostics/DiagnosticsExitLedgerBudgetTest.java#exportKeepsNewestRecordsWithinOneEightMiBBudget"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppDeviceLostRestartTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppRestartReservationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Remove calls to test-only recovery overloads. The existing tests now bind through the production recorder entry point and inject real write failures through its ledger, keeping history, fail-closed, ordered-close, exit-code and unused-reservation expectations unchanged. A reflection fallback invokes the original production binding on earlier revisions for evidence; it is confined to tests.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Owner item 6 process ownership and exit accounting; round-3 orchestrator decisions under this delegation.",
          "label": "agent choice",
          "choice": "Drive device-loss history and write-refusal tests through the same recorder binding production uses, preserving their expectations and before-state assertion failures."
        }
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/chat/DeviceLostRecoveryHandshakeTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "An adapter that cannot acknowledge a durable commit refuses the two-step restart request without making chat terminal or issuing an unverified plain request.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Owner item 6 process ownership and exit accounting; round-4 orchestrator decisions under this delegation.",
          "label": "agent choice",
          "choice": "An adapter that cannot acknowledge a durable commit refuses the two-step restart request without making chat terminal or issuing an unverified plain request."
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-r4-all.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/chat/DeviceLostRecoveryHandshakeTest.java#aLegacyAdapterCannotAcceptBeforeCommit"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppEngineDeviceLossChainTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The actual EngineRoot/executors/inference-factory/chat chain forwards commit callbacks: a refused second write leaves chat latched, visibly failed and retryable with no halt, hand-off or spent budget; success commits first, ends chat second and publishes the hand-off third.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Owner item 6 process ownership and exit accounting; round-4 orchestrator decisions under this delegation.",
          "label": "agent choice",
          "choice": "The actual EngineRoot/executors/inference-factory/chat chain forwards commit callbacks: a refused second write leaves chat latched, visibly failed and retryable with no halt, hand-off or spent budget; success commits first, ends chat second and publishes the hand-off third."
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-r4-all.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppEngineDeviceLossChainTest.java#aRefusedCommitThroughEngineRootKeepsChatRetryable",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppEngineDeviceLossChainTest.java#aCommittedRestartThroughEngineRootCommitsThenEndsChatThenHandsOff"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/diagnostics/DiagnosticsExitLedgerTornLineTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Diagnostics retain complete UTF-8 ledger records around malformed or incomplete lines, state how many selected lines were skipped, and retain exact source-byte omission accounting within the existing 8 MiB budget.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Owner item 6 process ownership and exit accounting; round-4 orchestrator decisions under this delegation.",
          "label": "agent choice",
          "choice": "Diagnostics retain complete UTF-8 ledger records around malformed or incomplete lines, state how many selected lines were skipped, and retain exact source-byte omission accounting within the existing 8 MiB budget."
        }
      ],
      "evidence": "gates/test-intent/evidence/b91-ix-lifecycle-r4-all.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/diagnostics/DiagnosticsExitLedgerTornLineTest.java#exportKeepsCompleteRecordsAroundMalformedAndTornLines"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineDeviceLossWitnessTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatDeviceLossTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppDeviceLostRestartTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Recovery test doubles explicitly accept the commit handshake while keeping restart, shutdown, history and fail-closed expectations unchanged. Restore damaged UTF-8 comment characters in the existing device-loss test; its expectations stay unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Owner item 6 process ownership and exit accounting; round-4 orchestrator decisions under this delegation.",
          "label": "agent choice",
          "choice": "Recovery test doubles explicitly accept the commit handshake while keeping restart, shutdown, history and fail-closed expectations unchanged. Restore damaged UTF-8 comment characters in the existing device-loss test; its expectations stay unchanged."
        }
      ]
    }
  ]
}
```

## Merge adaptations (b6d536/ix-lifecycle 624a05539 into b6d536/integration 851507135)

The adaptations are where this lane and the integration's vdu-auto and dev-tool work meet; each
keeps both lanes' accepted behaviour. One test changed (instrumentation only, a script test outside
the gate, described near the top):

- ChatServerControllerImpl, the controller loop: this lane's device-loss restart handshake holds
  every step while a restart is pending (deviceLostRestartPending), and vdu-auto's VDU restore and
  residency recheck now wait behind it too: the restore runs only while no device-loss restart is
  pending, and a pending residency recheck counts as work only outside that hold. A refused restart
  still stops chat (deviceLostStop) before anything else; the residency recheck keeps its own branch.
- ChatServerControllerImpl.routeHeld: this lane's serialized readiness barrier (a hold's start is
  wrapped so a later activation cannot supersede it) is enqueued through vdu-auto's awaitHeld, which
  the VDU restore also uses. With the barrier, a superseded hold step is BUSY, as this lane decided,
  so awaitHeld no longer takes the supersededIsDone flag.
- GenerationTracker: vdu-auto's whenNoLeaseOpen and this lane's lastTokenNanos are both kept.
- scripts/dev/dev-runner.cjs, the running record: this lane's incarnation field and the
  integration's exit-census Engine identity captured before the record (only while that incarnation
  is current) are both kept. exitCensusEngine stays the record's first field, followed by
  schemaVersion, because the integration's process-races test reads that field from the production
  source up to schemaVersion; incarnation now follows schemaVersion.
- scripts/dev/test-dev-runner-ix-lifecycle.mjs: the vm context of 'running record preserves the
  first-ready retry incarnation' supplies the integration's census names (see the script-test note
  near the top).

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VC-lifecycle-20261014",
  "verdict": "accept",
  "digest": "sha256:1a85e4882d2b8520be5a3daa7cf64cb8e0f5fff72f3fc33941e38c53a83c17d4",
  "note": "VC-lifecycle (independent Claude verifier) over four rounds, accepting 2e7a2c8fb vs 61ff9b955 (rejected c4f2405b4 because EngineRoot's wrapper dropped the two-step restart request): on the real device-loss chain through EngineRoot and HeadlessApp, a refused restart keeps chat latched and retryable with a visible reason, no halt, no hand-off and no budget spent; a committed restart commits, then chat ends, then the hand-off, with one restart counted; nothing reaches the uncaught handler; a later quit records only QUIT and exits 0; the diagnostics ledger export keeps every complete record, skips and counts malformed lines and stays within 8 MiB with exact omitted bytes; restored text changed no literal's meaning. Each repaired check fails on c4f2405b4 by assertion. Suites show only baseline failures; UnreferencedCodeTest passes; PMD adds nothing; dev-runner lifecycle script 10/10. Named limit: on a single-slot server a reply with no token for the probe timeout may be probed and relaunched. Unverified: a live device loss (cannot be triggered on this machine). Digest bound with --base 61ff9b955, the lane's base."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4b-20261015",
  "verdict": "accept",
  "digest": "sha256:5dad993c54c9b761ba2d7d63e4943f462cf2bc2748d12cc442515badbbea07b2",
  "note": "Reviewed the scoped lifecycle assertions, refreshed evidence and opt-in fake-server instrumentation; all 15 selected current candidate cases pass. The retained before claims, source scopes and independent prior results still match; the unrelated failed status row in a shared evidence file is not cited by these entries."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS5d-20261016",
  "verdict": "accept",
  "digest": "sha256:72aeaf689f17c658bf34f6485e6415923a1560451da09fe89369a026c0122593",
  "note": "The shared Healthy fixture changes cleanup while retaining PID-plus-birth checks, serving behavior and test oracles. All listed candidate selectors pass and the fixture-dependent resume replay reproduces the recorded PASSED-versus-RELAUNCHED failure on 32d00dda8."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS6a-20261016",
  "verdict": "accept",
  "digest": "sha256:560e871a8308a40a7abc663bd1cdf38fbc84070d77b01492ac01f6479ac425eb",
  "note": "Reviewed the portable admission fault, shared fake-server cleanup, unchanged lifecycle oracles and refreshed evidence. All cited candidate selectors pass, and every declared before setup reproduces its intended assertion or preservation outcome, including the fixture-overlay resume replay."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS7-20261016",
  "verdict": "accept",
  "digest": "sha256:5b935e0ab0a3717f5bd1fa20a20702bc5ae52e1615667d8e2a2d2b4de667e6ee",
  "note": "Reviewed fixture cleanup, the explicit fake-process admission fault and portable JVM launcher; all 36 scoped evidence selectors pass. All six declared before setups match their assertion and preservation outcomes, including the four actual round-4 selectors without an unrelated shared status-test overlay."
}
```
