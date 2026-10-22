---
schema: test-intent.v1
task: t-20261020-c2e24a
author-role: builder
author-session: upgrade-status-builder
---

# Upgrade retry status and serving counts

U15/U16/U20 were adopted by the user on 2026-10-20 with “i agree, proceed accordingly”.
The orchestrator's serving-count decision is an implementation choice under the quoted delegation,
not owner-words. Index counts retain the documented all-collection parent scope; default-scope
searchableDocuments remains separate.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/IndexStatusOpsWaitingTest.java"
      ],
      "class": "New",
      "behaviour": "retryOnlyBacklogWaitsUntilReadyThenProcessingAndWaitsAgain; terminalFailuresAndFatalLoopNeverPretendToProcess; completedQueueRowsAreNotDocumentsWhenNoReaderExists. Existing fixture setup now reports the producer state consistently; every other assertion remains intact.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U15",
          "quote": "When every pending job waits for a retry and none is processing, status does not say INDEXING: it shows a waiting state with the backlog and the next retry time.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U16",
          "quote": "Status follows waiting -> ready (including retry_after equal to now) -> processing -> waiting or complete, for mixed ready and waiting queues, an empty queue, terminal failures and a fatal loop failure, also after a restart; the public status, the UI lifecycle and active-work checks agree.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U20",
          "quote": "The indexed count shown matches the real number of searchable parent documents after upgrade, migration and restart (not derived from queue rows).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Implementation of agreed U15/U16/U20; preserve HTTP compatibility and existing scope rules.",
          "choice": "Represent retry-only waiting as WAITING in the existing unconstrained indexState string and reuse nextRetryAtMs; count serving parents across searchable scopes, keeping the existing default-scope searchableDocuments subset and separate building count.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-status-jvm-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/IndexStatusOpsWaitingTest.java#retryOnlyBacklogWaitsUntilReadyThenProcessingAndWaitsAgain",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/IndexStatusOpsWaitingTest.java#terminalFailuresAndFatalLoopNeverPretendToProcess",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/IndexStatusOpsWaitingTest.java#completedQueueRowsAreNotDocumentsWhenNoReaderExists"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchableDocCountReaderSelectionTest.java"
      ],
      "class": "New",
      "behaviour": "followsSearchServingReader. Existing fixture setup now reports the producer state consistently; every other assertion remains intact.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U20",
          "quote": "The indexed count shown matches the real number of searchable parent documents after upgrade, migration and restart (not derived from queue rows).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Implementation of agreed U15/U16/U20; preserve HTTP compatibility and existing scope rules.",
          "choice": "Represent retry-only waiting as WAITING in the existing unconstrained indexState string and reuse nextRetryAtMs; count serving parents across searchable scopes, keeping the existing default-scope searchableDocuments subset and separate building count.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-status-jvm-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchableDocCountReaderSelectionTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/UpgradeStatusRestartTest.java"
      ],
      "class": "New",
      "behaviour": "reopenedQueueReportsWaitingAndDueWorkThenProcessingAndCompletion. Existing fixture setup now reports the producer state consistently; every other assertion remains intact.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U15",
          "quote": "When every pending job waits for a retry and none is processing, status does not say INDEXING: it shows a waiting state with the backlog and the next retry time.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U16",
          "quote": "Status follows waiting -> ready (including retry_after equal to now) -> processing -> waiting or complete, for mixed ready and waiting queues, an empty queue, terminal failures and a fatal loop failure, also after a restart; the public status, the UI lifecycle and active-work checks agree.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Implementation of agreed U15/U16/U20; preserve HTTP compatibility and existing scope rules.",
          "choice": "Represent retry-only waiting as WAITING in the existing unconstrained indexState string and reuse nextRetryAtMs; count serving parents across searchable scopes, keeping the existing default-scope searchableDocuments subset and separate building count.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-status-jvm-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/UpgradeStatusRestartTest.java#reopenedQueueReportsWaitingAndDueWorkThenProcessingAndCompletion"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/LifecycleContractTest.java"
      ],
      "class": "New",
      "behaviour": "retryWaitingIsPublicWithoutClaimingActiveWorkOrStalledThroughput. Existing fixture setup now reports the producer state consistently; every other assertion remains intact.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U15",
          "quote": "When every pending job waits for a retry and none is processing, status does not say INDEXING: it shows a waiting state with the backlog and the next retry time.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U16",
          "quote": "Status follows waiting -> ready (including retry_after equal to now) -> processing -> waiting or complete, for mixed ready and waiting queues, an empty queue, terminal failures and a fatal loop failure, also after a restart; the public status, the UI lifecycle and active-work checks agree.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U20",
          "quote": "The indexed count shown matches the real number of searchable parent documents after upgrade, migration and restart (not derived from queue rows).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Implementation of agreed U15/U16/U20; preserve HTTP compatibility and existing scope rules.",
          "choice": "Represent retry-only waiting as WAITING in the existing unconstrained indexState string and reuse nextRetryAtMs; count serving parents across searchable scopes, keeping the existing default-scope searchableDocuments subset and separate building count.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-status-jvm-repaired.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/LifecycleContractTest.java#retryWaitingIsPublicWithoutClaimingActiveWorkOrStalledThroughput"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexWorkWaitingTest.java"
      ],
      "class": "New",
      "behaviour": "publicWorkerStateOwnsActiveWorkAcrossRetryAndFatalTransitions. Existing fixture setup now reports the producer state consistently; every other assertion remains intact.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U15",
          "quote": "When every pending job waits for a retry and none is processing, status does not say INDEXING: it shows a waiting state with the backlog and the next retry time.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U16",
          "quote": "Status follows waiting -> ready (including retry_after equal to now) -> processing -> waiting or complete, for mixed ready and waiting queues, an empty queue, terminal failures and a fatal loop failure, also after a restart; the public status, the UI lifecycle and active-work checks agree.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Implementation of agreed U15/U16/U20; preserve HTTP compatibility and existing scope rules.",
          "choice": "Represent retry-only waiting as WAITING in the existing unconstrained indexState string and reuse nextRetryAtMs; count serving parents across searchable scopes, keeping the existing default-scope searchableDocuments subset and separate building count.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-status-jvm-repaired.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexWorkWaitingTest.java#publicWorkerStateOwnsActiveWorkAcrossRetryAndFatalTransitions"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/indexingWaiting.test.ts"
      ],
      "class": "New",
      "behaviour": "shows retry waiting with the backlog and deadline then follows the worker lifecycle. Existing fixture setup now reports the producer state consistently; every other assertion remains intact. Terminal failures stay errors rather than becoming a successful completion claim; runnable enrichment is preserved.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U15",
          "quote": "When every pending job waits for a retry and none is processing, status does not say INDEXING: it shows a waiting state with the backlog and the next retry time.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U16",
          "quote": "Status follows waiting -> ready (including retry_after equal to now) -> processing -> waiting or complete, for mixed ready and waiting queues, an empty queue, terminal failures and a fatal loop failure, also after a restart; the public status, the UI lifecycle and active-work checks agree.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Implementation of agreed U15/U16/U20; preserve HTTP compatibility and existing scope rules.",
          "choice": "Represent retry-only waiting as WAITING in the existing unconstrained indexState string and reuse nextRetryAtMs; count serving parents across searchable scopes, keeping the existing default-scope searchableDocuments subset and separate building count.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-status-ui-lifecycle-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/indexingWaiting.test.ts#shows retry waiting with the backlog and deadline then follows the worker lifecycle",
        "modules/ui-web/src/shell-v0/state/indexingWaiting.test.ts#terminal job failures stay errors instead of becoming a completion claim"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/TaskList.test.ts"
      ],
      "class": "New",
      "behaviour": "keeps retry waiting visible without showing progress or a ready claim. Existing fixture setup now reports the producer state consistently; every other assertion remains intact. Terminal failures stay errors rather than becoming a successful completion claim; runnable enrichment is preserved.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U15",
          "quote": "When every pending job waits for a retry and none is processing, status does not say INDEXING: it shows a waiting state with the backlog and the next retry time.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U16",
          "quote": "Status follows waiting -> ready (including retry_after equal to now) -> processing -> waiting or complete, for mixed ready and waiting queues, an empty queue, terminal failures and a fatal loop failure, also after a restart; the public status, the UI lifecycle and active-work checks agree.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Implementation of agreed U15/U16/U20; preserve HTTP compatibility and existing scope rules.",
          "choice": "Represent retry-only waiting as WAITING in the existing unconstrained indexState string and reuse nextRetryAtMs; count serving parents across searchable scopes, keeping the existing default-scope searchableDocuments subset and separate building count.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-status-ui-lifecycle-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/TaskList.test.ts#keeps retry waiting visible without showing progress or a ready claim",
        "modules/ui-web/src/shell-v0/components/TaskList.test.ts#terminal failures withdraw the completion window and remain visible as errors"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusDeck.test.ts"
      ],
      "class": "New",
      "behaviour": "announces retry waiting with its backlog and deadline. Existing fixture setup now reports the producer state consistently; every other assertion remains intact. During migration a live serving count replaces the retained count; stale or unavailable observations retain the existing fallback.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U15",
          "quote": "When every pending job waits for a retry and none is processing, status does not say INDEXING: it shows a waiting state with the backlog and the next retry time.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U16",
          "quote": "Status follows waiting -> ready (including retry_after equal to now) -> processing -> waiting or complete, for mixed ready and waiting queues, an empty queue, terminal failures and a fatal loop failure, also after a restart; the public status, the UI lifecycle and active-work checks agree.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Implementation of agreed U15/U16/U20; preserve HTTP compatibility and existing scope rules.",
          "choice": "Represent retry-only waiting as WAITING in the existing unconstrained indexState string and reuse nextRetryAtMs; count serving parents across searchable scopes, keeping the existing default-scope searchableDocuments subset and separate building count.",
          "label": "agent choice"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U20",
          "quote": "The indexed count shown matches the real number of searchable parent documents after upgrade, migration and restart (not derived from queue rows).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-status-ui-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusDeck.test.ts#announces retry waiting with its backlog and deadline",
        "modules/ui-web/src/shell-v0/components/StatusDeck.test.ts#uses a fresh serving count during migration instead of the retained count"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts"
      ],
      "class": "New",
      "behaviour": "shows the serving document count and retry deadline while the queue waits. Existing fixture setup now reports the producer state consistently; every other assertion remains intact. During migration a live serving count replaces the retained count; stale or unavailable observations retain the existing fallback.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U15",
          "quote": "When every pending job waits for a retry and none is processing, status does not say INDEXING: it shows a waiting state with the backlog and the next retry time.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U16",
          "quote": "Status follows waiting -> ready (including retry_after equal to now) -> processing -> waiting or complete, for mixed ready and waiting queues, an empty queue, terminal failures and a fatal loop failure, also after a restart; the public status, the UI lifecycle and active-work checks agree.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U20",
          "quote": "The indexed count shown matches the real number of searchable parent documents after upgrade, migration and restart (not derived from queue rows).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Implementation of agreed U15/U16/U20; preserve HTTP compatibility and existing scope rules.",
          "choice": "Represent retry-only waiting as WAITING in the existing unconstrained indexState string and reuse nextRetryAtMs; count serving parents across searchable scopes, keeping the existing default-scope searchableDocuments subset and separate building count.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-status-ui-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts#shows the serving document count and retry deadline while the queue waits",
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts#uses the current serving count during migration instead of cached or building counts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/IndexStatusOpsLoopFailureTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingAvailabilityTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingGameModeTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingGpuBusyTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingStateAssemblerTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/WorkerStatusSamplerTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusGpuReadingAgeTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Queue-backed fixtures publish INDEXING/IDLE from their unchanged readiness/processing inputs, instead of the obsolete SERVING placeholder; loop-failure fixture now supplies ready counts matching its unchanged queue depth. Inputs, fault injection and all expected outcomes are preserved.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U16",
          "quote": "Status follows waiting -> ready (including retry_after equal to now) -> processing -> waiting or complete, for mixed ready and waiting queues, an empty queue, terminal failures and a fatal loop failure, also after a restart; the public status, the UI lifecycle and active-work checks agree.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.render.test.ts"
      ],
      "class": "New",
      "behaviour": "Library rows consume the index-wide retry-wait verdict while retaining their own backlog and failure scope; the shared explanation supplies the next retry time.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U15",
          "quote": "When every pending job waits for a retry and none is processing, status does not say INDEXING: it shows a waiting state with the backlog and the next retry time.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U16",
          "quote": "Status follows waiting -> ready (including retry_after equal to now) -> processing -> waiting or complete, for mixed ready and waiting queues, an empty queue, terminal failures and a fatal loop failure, also after a restart; the public status, the UI lifecycle and active-work checks agree.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U20",
          "quote": "The indexed count shown matches the real number of searchable parent documents after upgrade, migration and restart (not derived from queue rows).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Implementation of agreed U15/U16/U20; preserve HTTP compatibility and existing scope rules.",
          "choice": "Represent retry-only waiting as WAITING in the existing unconstrained indexState string and reuse nextRetryAtMs; count serving parents across searchable scopes, keeping the existing default-scope searchableDocuments subset and separate building count.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-status-library-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.render.test.ts#library rows describe retry waiting without claiming active indexing"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/MidMigrationCompatSurfaceTest.java"
      ],
      "class": "New",
      "behaviour": "During migration the user count follows searchable parents in serving BLUE, with GREEN building progress separate; compatibility diagnostics remain tied to serving BLUE.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U20",
          "quote": "The indexed count shown matches the real number of searchable parent documents after upgrade, migration and restart (not derived from queue rows).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Implementation of agreed U15/U16/U20; preserve HTTP compatibility and existing scope rules.",
          "choice": "Represent retry-only waiting as WAITING in the existing unconstrained indexState string and reuse nextRetryAtMs; count serving parents across searchable scopes, keeping the existing default-scope searchableDocuments subset and separate building count.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-status-jvm-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/MidMigrationCompatSurfaceTest.java#midMigrationTheSurfaceDescribesBlueAndAsksForTheReindexItIsPerforming"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/IndexingOverlay.test.ts"
      ],
      "class": "New",
      "behaviour": "A retry-only backlog does not present a blocking active-work overlay; the status deck owns its waiting explanation. Existing overlay fixtures explicitly mark the indexing observation live.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U15",
          "quote": "When every pending job waits for a retry and none is processing, status does not say INDEXING: it shows a waiting state with the backlog and the next retry time.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U16",
          "quote": "Status follows waiting -> ready (including retry_after equal to now) -> processing -> waiting or complete, for mixed ready and waiting queues, an empty queue, terminal failures and a fatal loop failure, also after a restart; the public status, the UI lifecycle and active-work checks agree.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U20",
          "quote": "The indexed count shown matches the real number of searchable parent documents after upgrade, migration and restart (not derived from queue rows).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Implementation of agreed U15/U16/U20; preserve HTTP compatibility and existing scope rules.",
          "choice": "Represent retry-only waiting as WAITING in the existing unconstrained indexState string and reuse nextRetryAtMs; count serving parents across searchable scopes, keeping the existing default-scope searchableDocuments subset and separate building count.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-status-overlay-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/IndexingOverlay.test.ts#does not present retry waiting as active work in a blocking overlay"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/indexingTaskWaiting.test.ts"
      ],
      "class": "New",
      "behaviour": "Qualifies delayed running file rows using the shared phase and withdraws them from the running-job chip; independent agent work and completed file rows retain their status.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-c2e24a",
          "scenario": "U16",
          "quote": "Status follows waiting -> ready (including retry_after equal to now) -> processing -> waiting or complete, for mixed ready and waiting queues, an empty queue, terminal failures and a fatal loop failure, also after a restart; the public status, the UI lifecycle and active-work checks agree.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-c2e24a request",
          "delegationScope": "Implementation of agreed U15/U16/U20; preserve HTTP compatibility and existing scope rules.",
          "choice": "Retained indexing file rows cannot claim current running work once the shared phase says waiting, drained or error; the running chip excludes those rows while keeping independent agent work.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/upgrade-status-ui-task-rows-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/indexingTaskWaiting.test.ts#qualifies delayed running file rows after the worker stops runnable file work",
        "modules/ui-web/src/shell-v0/components/indexingTaskWaiting.test.ts#withdraws delayed file work from the running chip while preserving independent agent work"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records

## Supplemental test intent: eval and soak status consumers

The gate explicitly excludes `scripts/` and the jseval suite. These are additional New checks,
recorded here for the independent verifier; they do not replace any existing expectation.

- Items: `scripts/jseval/tests/test_ui_perf.py` and `scripts/jseval/tests/test_bulk_load.py`.
- Source kind `owner-adopted-scenario`, task `t-20261020-c2e24a`, scenario U16:
  “Status follows waiting -> ready (including retry_after equal to now) -> processing -> waiting or complete, for mixed ready and waiting queues, an empty queue, terminal failures and a fatal loop failure, also after a restart; the public status, the UI lifecycle and active-work checks agree.”
  Adoption: user on 2026-10-20 with “i agree, proceed accordingly”; label: agent-drafted, owner-adopted.
- Source kind `delegated-choice`; delegationQuote “resume with remaining work autonomously”;
  delegationLocation `task t-20261020-c2e24a request`; label: agent choice.
  Choice: performance setup reuses the canonical nested-status readiness wait and refuses to
  measure after timeout/fatal failure. Soak activity uses the Worker's runnable file verdict and
  reachable enrichment counters; a retry backlog or a serving population below target alone is
  not activity. Terminal loop failure never qualifies as an active load window. Current nested worker/indexing
  freshness flags make a stale snapshot unmeasurable instead of claiming active work.
- New checks: `test_setup_refuses_an_unsettled_queue_before_measuring` (WAITING/INDEXING/FAILED),
  `test_setup_waits_through_retry_and_processing_before_measuring`, and `RetryQueueActivityTest`
  (retry-only/idle/fatal, runnable work, reachable/unavailable enrichment and both nested stale flags).
- Before: detached d05fb4bba production with only the two test files overlaid; eleven assertion
  failures and seventeen passes in `tmp/builder/followup/evidence/harness-before.log`.
- After: candidate harness and readiness checks; 76 passes plus five passing subtests in
  `tmp/builder/followup/evidence/harness-after.log`. No existing test expectation was weakened.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a161b1-ca67-7ef2-9f4d-c64a90c6af09",
  "verdict": "accept",
  "digest": "sha256:57254e3e27792b1d56475bfcd81793d361200db6a580099990dc72154a56ed94",
  "note": "All fourteen entries preserve existing checks or add agreed U15/U16/U20 coverage, and delegated implementation choices are honestly attributed."
}
```
