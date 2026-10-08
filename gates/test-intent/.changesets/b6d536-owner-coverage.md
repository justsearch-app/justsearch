---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: codex-b32-owner-coverage-20261008
---

# Exact helper ownership and complete exit accounting

The source is the owner's b32 builder request. Assertion failures on f5877635f and native sibling
evidence are retained in F:/agent-sandbox/design-b6d536/codex/b32-owner-coverage/report.md.
The canonical evidence producer ran the candidate. Its optional before runner creates a worktree
outside the mandated directory and removes it with force after linking node_modules. The owner's
worktree rules prohibit that route; manually executed detached baseline evidence remains separate.
The builder does not fabricate canonical fail-before evidence or accept its own test intent.

```json
{
  "entries": [
    {
      "items": ["modules/gpu-bridge/src/test/java/io/justsearch/gpu/VramDetectorOwnedBootTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "The production nvidia-smi spawn path retains its original held-process owner receipts even when it completes before the Engine attaches the recorder. The test substitutes a finite CPU-only command at ProcessBuilder.start.",
      "sources": [{"kind": "owner-words", "location": "task t-20261005-b6d536 request (b32 builder, user turn 2026-10-08)", "quote": "Three early nvidia-smi exits (around Engine start, before the VramDetector-owned ones) are unowned: find their spawn site and route it through OwnedHelper."}],
      "evidence": "gates/test-intent/evidence/b6d536-owner-coverage.json",
      "newChecks": ["modules/gpu-bridge/src/test/java/io/justsearch/gpu/VramDetectorOwnedBootTest.java"]
    },
    {
      "items": ["modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaProbeOwnerIntentTest.java", "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaProductionOwnerCoverageTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "Structured extractor detector construction and first-pass OCR configuration are inside owner windows. Every real Tesseract initialization or parse probe in flat, structured and policy extractors has its actual creator PID/birth and a closed intent window. Flat extraction is a preservation control; structured and policy baseline failures identify the missed detector constructor.",
      "sources": [{"kind": "owner-words", "location": "task t-20261005-b6d536 request (b32 builder, user turn 2026-10-08)", "quote": "Find every path Tesseract runs on (Engine, worker, extraction sandbox children, persistent sandbox, PDF OCR, initialisation and parsing) and cover each with an owner record or a window, bound as in item 1."}],
      "evidence": "gates/test-intent/evidence/b6d536-owner-coverage.json",
      "newChecks": ["modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaProbeOwnerIntentTest.java#firstPassConfigurationProbeHasItsOwnWindow", "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaProductionOwnerCoverageTest.java"]
    },
    {
      "items": ["modules/app-api/src/integrationTest/java/io/justsearch/app/api/runtime/containment/CrashedEngineHelperReceiptTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "A real disposable Engine job crashes with a live witnessed helper. Its successor records OWNER_DIED with ENGINE_CRASH as cause, an explicit reason, and null exit code/time when neither can be recovered. This remains distinct from an intended exit.",
      "sources": [{"kind": "owner-words", "location": "task t-20261005-b6d536 request (b32 builder, user turn 2026-10-08)", "quote": "A helper ended by its owner's crash needs a recorded final outcome from the surviving owner (the dev runner or the successor Engine), with the crash named as its cause and kept apart from intended exits."}],
      "evidence": "gates/test-intent/evidence/b6d536-owner-coverage.json",
      "newChecks": ["modules/app-api/src/integrationTest/java/io/justsearch/app/api/runtime/containment/CrashedEngineHelperReceiptTest.java#successorRecordsCrashConsequenceAndUnknownExitTime"]
    },
    {
      "items": ["modules/app-launcher/src/test/java/io/justsearch/app/launcher/EngineSpawnOwnershipArchTest.java"],
      "class": "New",
      "repaired": false,
      "behaviour": "An architecture check inspects all Engine-runtime production bytecode and forbids ProcessBuilder.start/startPipeline and Runtime.exec outside the owner implementations. A planted unowned ProcessBuilder is rejected by the same predicate without executing the canary process.",
      "sources": [{"kind": "owner-words", "location": "task t-20261005-b6d536 request (b32 builder, user turn 2026-10-08)", "quote": "Add a check that every ProcessBuilder or Runtime.exec in Engine-side production code goes through the owner path, so no spawn site is missed again."}],
      "evidence": "gates/test-intent/evidence/b6d536-owner-coverage.json",
      "newChecks": ["modules/app-launcher/src/test/java/io/justsearch/app/launcher/EngineSpawnOwnershipArchTest.java"]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/HealthyFakeLlamaServer.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ManagedLlamaAdoptionTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/SurvivingTerminator.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationSelfTestSeamTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SlowStartFakeLlamaServer.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StreamingFakeLlamaServer.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/WindowsParserContainmentTest.java",
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/chaos/ExtractionSandboxOrphanE2ETest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "All behavior assertions, inputs, process fault injections, tags and conditions remain. Cleanup uses only the original held Process or a fixture-emitted PID/birth checked on the held native termination handle; redundant PID-only and descendant-tree cleanup is removed. Fake servers record their own birth before publishing their launch. On POSIX, their original startInstant supplies birth. System-tests source is inspected and compiled but its real app campaign is deferred to the independent verifier.",
      "sources": [{"kind": "owner-words", "location": "task t-20261005-b6d536 request (b32 builder, user turn 2026-10-08)", "quote": "Find every test, fixture and harness in the repository that stops a process by PID alone, by name or through a parent PID (for example taskkill /PID, Stop-Process -Id, ProcessHandle.of(pid).destroy*), and make each use the identity the test itself started (a held Process or handle, or PID plus birth rechecked on a held handle)."}]
    }
  ]
}
```

## Acceptance records

Pending independent verifier acceptance. No builder acceptance is recorded.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-census5-resumed-20261008",
  "verdict": "reject",
  "digest": "sha256:5fe1ac55842ee0591c30cbc8bf5412be2da97d5f35aae5d9aa2e512cb6910dde",
  "note": "Independent rerun: new owner-coverage assertions fail on f5877635f and pass on 58a51a608; O9 native sibling/orphan cases repaired. Reject acceptance: the canonical repaired-check evidence omits fail-before, and the crash-helper check validates the ledger receipt without the final census, which loses a real delayed OWNER_DIED receipt (S15). Evidence: F:/agent-sandbox/design-b6d536/codex/verify-census5/candidate-delayed-native-analysis-repeat.json. Full verification report pending."
}
```
