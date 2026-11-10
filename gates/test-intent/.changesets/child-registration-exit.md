---
schema: test-intent.v1
task: t-20261110-childreg
author-role: builder
author-session: codex-child-reg-20261110
---

# Deterministic managed-child registration exit

The loader fixture's assertions, library selection, inherited environment and launch cases are
unchanged. Its child now waits for stdin EOF; the parent closes stdin after managed start returns.
A separate control-level test pins the registration race using independently controlled process
information, JDK exit status and the exit callback. No running application or shared dev stack is used.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/LinuxPackChildEnvironmentTest.java"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i think agents should not ask me for any input if they know better, which in most work related tasks is the case.",
          "delegationLocation": "task t-20261110-childreg request",
          "delegationScope": "fixing a recurring CI flake and the production defect it exposes",
          "choice": "Keep every loader-environment assertion and launch case, and make the C fixture wait for stdin EOF, closing stdin only after managed start returns. Pin the identity-capture race in a separate deterministic control-level regression rather than relying on fixture exit timing.",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "Remove process-exit timing from the Linux downloaded-pack loader test while preserving its full environment and library-precedence contract.",
        "callersAndStateOwners": "LinuxPackChildEnvironmentTest starts a fresh parent JVM for each launch case. Its Parent creates a managed child with pipes and closes that child's stdin after start returns. The test owns its temporary pack, host and library directories, parent and managed processes; no shared application state or dependency tree is mutated.",
        "indirectBehaviours": "The C fixture prints the same value and LD_LIBRARY_PATH, then waits for EOF before exit zero. The parent still reads every stdout byte, awaits the exit and prints its own environment. All child-kind, marker-present/absent, inherited-environment, parent-exit, output and timeout assertions remain unchanged. GCC invocation, executable RPATH and test selection are unchanged.",
        "evidence": "Regenerated script-produced gates/test-intent/evidence/child-registration-preservation.json runs all three changed registration test classes and LinuxPackChildEnvironmentTest: 21 candidate cases pass without skips. The e753cc6e shared-clone before state has six publication assertion failures and 15 preservation passes, including the unchanged loader check. child-registration-publication.json independently records all six publication checks passing after/assertion-failing before; unchanged child-registration-interrupt.json records eight interruption cases passing after, with seven failures and one preservation pass before on c2579219. After applying CR7 to every new extraction check, all twenty consecutive CPU-loaded iterations passed all 29 cases without failures, errors or skips. Per-iteration logs/XML, all twenty results, exact commands and the owned CPU identity/termination record are retained under build/child-reg-interrupt/cr7-all-extraction. The load was terminated and reaped after verifying its birth identity. The separate first-request stderr-reset defect remains documented in build/child-reg-interrupt/README.md.",
        "unresolvedRisks": "Startup stderr retention and extraction exception classification remain outside narrowed CR7 and are filed separately; no extraction production code was changed. Previous broad sandbox failures are compared with a fresh shared-clone baseline under build/child-reg-interrupt/finish-round; broad Gradle checks were not rerun for this test-only continuation. Native Windows execution remains unavailable on Linux. Independent verification and acceptance remain required."
      }
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/api/runtime/child/RegistrationPublicationFixture.java",
        "modules/worker-services/src/test/java/io/justsearch/app/api/runtime/child/RegistrationPublicationFixture.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/ChildRegistrationExitTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/RegistrationExitCallersTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/RegistrationExitExtractionTest.java"
      ],
      "class": "Defect pin",
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no data loss",
        "reproduction": "On e753cc6e2f39602edd51cb2eb48f7924234b9507 a real child exits 37 during identity capture. Hold the watcher after it claims EXITED and before outcome publication. Start returns without the outcome: chat/extraction cannot read the original code yet, and a self-test with a healthy probe exercises and reports success despite the exited child. Six checks fail assertions before the repair."
      },
      "reproduction": "On e753cc6e2f39602edd51cb2eb48f7924234b9507 a real child exits 37 during identity capture. Hold the watcher after it claims EXITED and before outcome publication. Start returns without the outcome: chat/extraction cannot read the original code yet, and a self-test with a healthy probe exercises and reports success despite the exited child. Six checks fail assertions before the repair.",
      "sources": [
        {
          "kind": "decision-record",
          "location": "docs/decisions/0047-context-window-is-a-derived-resource.md:72-74",
          "quote": "If llama-server refuses a rung it exits immediately; `LlamaServerOps` steps down one rung and relaunches on `PROCESS_EXITED` (the same seam `relaunchWithoutReasoningBudget` already used)."
        },
        {
          "kind": "decision-record",
          "location": "docs/decisions/0048-extraction-isolation-and-indexing-pacing.md:64",
          "quote": "a crash reports the exit code; a child OOM is a permanent parse failure."
        },
        {
          "kind": "decision-record",
          "location": "docs/decisions/0051-held-process-lifetimes-and-owner-exit-records.md:58-60",
          "quote": "Each owner stops its children through one API, `stop(child, reason, mode)`. It writes an intent to an append-only exit ledger per data directory, terminates, waits on the process handle, and records the outcome."
        },
        {
          "kind": "decision-record",
          "location": "docs/decisions/0051-held-process-lifetimes-and-owner-exit-records.md:60",
          "quote": "An exit observed before that call is `UNEXPECTED`, even if an intent exists."
        }
      ],
      "evidence": "gates/test-intent/evidence/child-registration-publication.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/ChildRegistrationExitTest.java#captureFailureWaitsForTheWatchersSinglePublication",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/ChildRegistrationExitTest.java#completedRegistryWriteWaitsForTheWatchersSinglePublication",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/RegistrationExitCallersTest.java#realChatFirstLaunchAndRelaunchWaitForRegistrationExitPublication",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/RegistrationExitCallersTest.java#realSelfTestCannotPassWhileRegistrationExitPublicationIsPending",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/RegistrationExitExtractionTest.java#realRegistrationCrashWaitsForPublishedStatusAndGetsAReplacement",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/RegistrationExitExtractionTest.java#realRegistrationHeapExitWaitsForPublishedStatusAndGetsAReplacement"
      ],
      "expectations": "The original exit is published before registration returns, preserving the code and diagnostic for the immediate-exit PROCESS_EXITED path. Extraction reports the child's exit code and a later request gets a replacement. CR7 is narrowed for every new extraction check: both realRegistrationCrashWaitsForPublishedStatusAndGetsAReplacement and realRegistrationHeapExitWaitsForPublishedStatusAndGetsAReplacement require a failed request reporting code 37 and a replacement child on the later request. The mock checks registrationExitReportsItsActualCodeAndGetsAReplacement and registrationHeapExitReportsItsActualCodeAndGetsAReplacement likewise require the actual code (23 in their fixture) and replacement. None requires startup stderr or exception classification. The separate first-request stderr-reset defect is documented in build/child-reg-interrupt/README.md, Follow-up investigation: 2026-11-10 07:14–07:21 UTC (controlled reproduction: heap-investigation/diagnostic.xml). The orchestrator filed that defect separately; extraction production code remains unchanged. A known exited self-test cannot supply a successful exercise. Exactly one outcome, recent exit and child-exit record retain UNEXPECTED with no invented stop intent. These are loss-of-outcome guards for the cited existing contracts; the fixture only instruments publication and changes none of the pre-existing assertions."
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/ChildRegistrationInterruptTest.java"
      ],
      "class": "Defect pin",
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no data loss",
        "reproduction": "On c2579219582f57680d44f73474d04d7e791f009a, interrupt before, during or after the original real child confirmation wait. Its exit code 37 and output are returned, but ExitLedgerWriter loses the child-exit with ClosedByInterruptException and reload finds no exit record. Interrupted live-child rollback similarly loses its stop intent/durability. Interrupting the publication wait after the watcher captured no registry ID leaves the persisted manifest entry stale. Seven of eight cases fail assertions; the capture-failure publication case is a passing preservation check."
      },
      "reproduction": "On c2579219582f57680d44f73474d04d7e791f009a, interrupt before, during or after the original real child confirmation wait. Its exit code 37 and output are returned, but ExitLedgerWriter loses the child-exit with ClosedByInterruptException and reload finds no exit record. Interrupted live-child rollback similarly loses its stop intent/durability. Interrupting the publication wait after the watcher captured no registry ID leaves the persisted manifest entry stale. Seven of eight cases fail assertions; the capture-failure publication case is a passing preservation check.",
      "sources": [
        {
          "kind": "decision-record",
          "location": "docs/decisions/0051-held-process-lifetimes-and-owner-exit-records.md:58-60",
          "quote": "Each owner stops its children through one API, `stop(child, reason, mode)`. It writes an intent to an append-only exit ledger per data directory, terminates, waits on the process handle, and records the outcome."
        },
        {
          "kind": "decision-record",
          "location": "docs/decisions/0051-held-process-lifetimes-and-owner-exit-records.md:60",
          "quote": "An exit observed before that call is `UNEXPECTED`, even if an intent exists."
        }
      ],
      "evidence": "gates/test-intent/evidence/child-registration-interrupt.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/ChildRegistrationInterruptTest.java"
      ],
      "expectations": "No caller interrupt erases the actual exit outcome or its ledger evidence. After registration returns or refuses, the caller still observes its interrupt. Exited children retain code 37, both output streams, one future outcome, one recent exit, and one reloaded child-exit with no intent. Living children remain refused and are rolled back with the recorded reason. Publication waits remain bounded and remove any late manifest entry. Tests own real JVMs, a real ExitLedgerWriter/Reader and temporary FileChannel-backed manifest writes; the process seam controls only observation timing and the original process is the exit-code authority."
    }
  ]
}
```
