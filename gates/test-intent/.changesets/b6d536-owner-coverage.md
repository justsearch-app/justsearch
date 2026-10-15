---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: codex-b32-owner-coverage-20261008
---

# Exact helper ownership and complete exit accounting

The source is the orchestrator's b32 builder brief (agent wording under the owner's end-to-end
delegation, cited below as delegated choices). Assertion failures on f5877635f and native sibling
evidence are retained in F:/agent-sandbox/design-b6d536/codex/b32-owner-coverage/report.md.
Evidence regenerated on the b6d536/final merge (integration 3bc5c6f57 with b102, plus phaseb-all), with the canonical before
runner: `gates/test-intent/evidence/b6d536-owner-coverage.json` runs on f5877635f, the parent of
58a51a608, which added these checks. It applies
`gates/test-intent/evidence/b6d536-owner-coverage-compat.patch`, which is the gpu-bridge test
dependency on mockito-core and its lockfile lines as 58a51a608 added them. VramDetectorOwnedBootTest
needs them to compile. They are test-classpath build lines; no production code reads them. It
also applies `gates/test-intent/evidence/b102-census-lookup-compat-r4.patch` (b102's), because
CrashedEngineHelperReceiptTest now loads scripts/dev/lib/stop-exit-census.cjs and calls its later
lookup interfaces; the patch adds them as stubs that look up and observe nothing.
VramDetectorOwnedBootTest, TikaProductionOwnerCoverageTest and
CrashedEngineHelperReceiptTest#successorRecordsCrashConsequenceAndUnknownExitTime fail there by
assertion. EngineSpawnOwnershipArchTest (entry 4, not repaired) passes there. b101 (47981cfa6)
replaced TikaProbeOwnerIntentTest#firstPassConfigurationProbeHasItsOwnWindow with
firstPassConfigurationLaunchesNothingAndWritesNoWindow. The first-pass OCR configuration now
launches nothing and writes no window, so b101-probe-churn covers it. That check is no longer in
this file or in entry 2.
The builder does not fabricate canonical fail-before evidence or accept its own test intent.

```json
{
  "entries": [
    {
      "items": ["modules/gpu-bridge/src/test/java/io/justsearch/gpu/VramDetectorOwnedBootTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "The production nvidia-smi spawn path retains its original held-process owner receipts even when it completes before the Engine attaches the recorder. The test substitutes a finite CPU-only command at ProcessBuilder.start. Fail-before: f5877635f with b6d536-owner-coverage-compat.patch, by assertion (early production nvidia-smi receipt absent). Build cleanup uses unnamed lifetime-only resources in VramDetectorOwnedBootTest, retaining original inputs, resource lifetimes and every existing assertion.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Three early nvidia-smi exits (around Engine start, before the VramDetector-owned ones) are unowned: find their spawn site and route it through OwnedHelper.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b32 builder, user turn 2026-10-08)'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b6d536-build-green-f5877635f-17c136ecc0.json",
      "newChecks": ["modules/gpu-bridge/src/test/java/io/justsearch/gpu/VramDetectorOwnedBootTest.java"]
    },
    {
      "items": ["modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaProbeOwnerIntentTest.java", "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaProductionOwnerCoverageTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "Structured extractor detector construction and first-pass OCR configuration are inside owner windows. Every real Tesseract initialization or parse probe in flat, structured and policy extractors has its actual creator PID/birth and a closed intent window. Flat extraction is a preservation control; structured and policy baseline failures identify the missed detector constructor. Superseded in part by b101-probe-churn (47981cfa6): the first-pass OCR configuration launches nothing and writes no window, so it has no owner window to cover. The Tesseract probe coverage stays. Fail-before of TikaProductionOwnerCoverageTest: f5877635f with b6d536-owner-coverage-compat.patch, by assertion. Build cleanup declares the intent close handle immediately before try(close) in TikaProbeOwnerIntentTest, retaining original inputs, resource lifetimes and every existing assertion.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Find every path Tesseract runs on (Engine, worker, extraction sandbox children, persistent sandbox, PDF OCR, initialisation and parsing) and cover each with an owner record or a window, bound as in item 1.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b32 builder, user turn 2026-10-08)'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b6d536-build-green-f5877635f-17c136ecc0.json",
      "newChecks": ["modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaProductionOwnerCoverageTest.java"]
    },
    {
      "items": ["modules/app-api/src/integrationTest/java/io/justsearch/app/api/runtime/containment/CrashedEngineHelperReceiptTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "A real disposable Engine job crashes with a live witnessed helper. Its successor records OWNER_DIED with ENGINE_CRASH as cause, an explicit reason, and null exit code/time when neither can be recovered. This remains distinct from an intended exit. Fail-before: f5877635f with b6d536-owner-coverage-compat.patch and b102-census-lookup-compat-r4.patch, by assertion. Build cleanup asserts child-control acquisition prerequisites in CrashedEngineHelperReceiptTest, retaining original inputs, resource lifetimes and every existing assertion.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "A helper ended by its owner's crash needs a recorded final outcome from the surviving owner (the dev runner or the successor Engine), with the crash named as its cause and kept apart from intended exits.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b32 builder, user turn 2026-10-08)'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b6d536-build-green-f5877635f-17c136ecc0.json",
      "newChecks": ["modules/app-api/src/integrationTest/java/io/justsearch/app/api/runtime/containment/CrashedEngineHelperReceiptTest.java#successorRecordsCrashConsequenceAndUnknownExitTime"]
    },
    {
      "items": ["modules/app-launcher/src/test/java/io/justsearch/app/launcher/EngineSpawnOwnershipArchTest.java"],
      "class": "New",
      "repaired": false,
      "behaviour": "An architecture check inspects all Engine-runtime production bytecode and forbids ProcessBuilder.start/startPipeline and Runtime.exec outside the owner implementations. A planted unowned ProcessBuilder is rejected by the same predicate without executing the canary process.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Add a check that every ProcessBuilder or Runtime.exec in Engine-side production code goes through the owner path, so no spawn site is missed again.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b32 builder, user turn 2026-10-08)'; the text does not appear in the owner's turns of session 886833dd"}],
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
      "whatMoved": "All behavior assertions, inputs, process fault injections, tags and conditions remain. Cleanup uses only the original held Process or a fixture-emitted PID/birth checked on the held native termination handle; redundant PID-only and descendant-tree cleanup is removed. Fake servers record their own birth before publishing their launch. On POSIX, their original startInstant supplies birth. System-tests source is inspected and compiled but its real app campaign is deferred to the independent verifier. Build cleanup removes redundant qualification in SlowStartFakeLlamaServer, ExtractionSandboxOrphanE2ETest, StreamingFakeLlamaServer; removes overwritten PID initializers in PersistentExtractionSandboxTest; removes the overwritten native-child initializer and its redundant null guard while keeping the child-exit assertion in WindowsParserContainmentTest, retaining original inputs, resource lifetimes and every existing assertion.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Find every test, fixture and harness in the repository that stops a process by PID alone, by name or through a parent PID (for example taskkill /PID, Stop-Process -Id, ProcessHandle.of(pid).destroy*), and make each use the identity the test itself started (a held Process or handle, or PID plus birth rechecked on a held handle).", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b32 builder, user turn 2026-10-08)'; the text does not appear in the owner's turns of session 886833dd"}]
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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4c-20261015",
  "verdict": "accept",
  "digest": "sha256:c057085d5e6726f278368953a941b2ef6a5893297aa096581a2b1b8fd4e5fdbd",
  "note": "Reviewed corrected sources, canonical before proof, the crash-receipt extension and held-identity fixture cleanup. Four selected candidate checks pass; f5877635f with the declared patches reproduces three assertion-failing checks and the architecture preservation pass."
}
```
