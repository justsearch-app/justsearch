---
schema: test-intent.v1
task: slow-tests-b
author-role: builder
author-session: codex-slow-tests-b
---

# Slow-test batch B: replace waiting with observed completion

The ten suites and independent-interrupt fixture keep their original executions and behavioral expectations. Timing and mutation evidence is under `tmp/slow-tests-b/` in the builder checkout. Independent acceptance is pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "B1: The handler clock now advances both existing cache windows. HTTP witnesses require each GPU, memory, worker and telemetry sample to change; the original ordinary/fresh counter, real-state and failed-action assertions remain.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RecordedRootPreparationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "B2: Removal now runs concurrently with the same real held producer. ThreadMXBean witnesses blocking on the exact producer fence before release. The original collection/membership checks remain and persisted state is re-read. The existing ten-second escape now fails instead of silently succeeding.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/OnlineModeOpsTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "B3: A latch holds the same real HTTP chat response until the existing vision-refusal assertions finish, then releases it in finally. The real 200-ms vision deadline, lock ownership witness and successful chat completion remain.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientInferenceQueueTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "B4: All seven variants positively queue behind the original holder. The captured start must predate release and deadline-start must equal the original budget exactly, including the inference deadline. This replaces the 600-ms observation window and 100-ms slack. Separate real expiry/lease checks remain.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatControllerLifecycleSeamTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ManagedChatSeam.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SlowStartFakeLlamaServer.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "B5: The real child fake records entry to health and holds its response on an explicit file signal. X/A/B/C ordering uses accepted-controller-wait stack witnesses, then releases health. Quit awaits startup completion and child exit and probes refusal of a later start. Health and drain lock probes run while the relevant response/lease is held. All original outcomes, identities, counts and cleanup remain; other fake users retain their configured startup delay.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceQueuedUnavailableTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "B6: Only the two timely cases use 1250 ms instead of 20 s, preserving a positive allowance after lexical reserve and margin. The loading latch still really expires. All aged queue inputs, unavailable/permanent/missing provider cases, keyword assertions, encoder non-execution checks and loading/contended distinctions remain.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientExecutorTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "B7/F1: Existing deadline fixtures use 250 ms (500 ms for seven concurrent entrants) instead of one second. Existing positive worker/queue witnesses precede every timeout assertion. All parameter executions, real timers, late completions, admission/serving ownership, text availability and failure reporting remain. Independent interrupt observation is acknowledged before release; the original lease-close interrupt assertion remains.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatActivationSeamTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "B8: All four foreign unload/load cycles and final explicit spec write remain on the real manager/child. Synchronous unload delivery is counted; the existing reconciler quiescence barrier settles forwarding and recorded activation futures prove controller delivery. Fake startup delays and one-second absence windows are removed, preserving exact launch counts and modes.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ProxyStreamAcrossReconfigureTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "B9: The manager generation count witnesses ownership of the real proxied stream lease before reconfiguration and throughout the observed drain. AUX admission refusal witnesses entry to the real drain. Pending reconfiguration and unchanged launches are checked before stream release. Reply bytes, DONE, exact target launches, process cleanup and recorded completion-before-relaunch ordering remain.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RichDocumentInProcessTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "B10: The real PDF parser/index/search path and marker assertions remain. Indexing is observed by queue drain plus document count instead of two mandatory one-second polls. Teardown requires the captured real server closure latch, successful reacquisition of its index-root lock and bounded file/directory rename probes, replacing the two-second grace. Release timeout fails. The extraction counterexample refuses the PDF job at JobBatchExtractor extraction entry, bypassing every parser route: disabling only StructuredContentExtractor parsing still yielded a searchable PDF and was therefore not a complete extraction bypass. Omitting KnowledgeServer owner closure leaves its actual index-root lock held, so reacquisition fails.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "For each suite: remove the waiting by the mechanism named (completion signal, latch, witness, existing clock), keep every execution and every assertion's meaning, and keep the real thing real (real HTTP, real lock deadline, real expiry where the test is about expiry).",
          "location": "task slow-tests-b user turn 2 (2026-10-19 builder brief, Investigator rows and Deliver / preserve scenarios)"
        }
      ]
    }
  ]
}
```

## Acceptance records

Pending independent verification; no builder acceptance.
