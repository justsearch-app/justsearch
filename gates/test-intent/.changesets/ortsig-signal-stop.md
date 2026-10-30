---
schema: test-intent.v1
task: ortsig
author-role: builder
author-session: ortsig-builder-3
---

# Test-intent entries

S3 matches the baseline JVM hook on clean, refused and deadline drains: QUIT self-intent
(expected code 0), pending quit handoff, and 143/130 only after native quiescence; refused
or deadline drains exit 1. Deadline termination still halts without starting JVM hooks.
The production ledger classifier calls these statuses UNEXPECTED because they differ from
expected code 0; the manifest-aware supervisor classifies them REQUESTED, uncounted, stop.
The preservation check runs both signals and all three drain modes through the real writers.
See the 12 process observations in `tmp/verify/ortsig-builder-3-report.md`.

The supplied contract is agent-adopted under delegated choice, not owner-adopted.
The existing competing-JVM-hook limitation probe is retained unchanged.
Baseline 7959b712fd is built as a private shared clone; no source metadata is changed.
Evidence uses a temporary copy of the repository runner, with the reviewable adapter at
`tmp/verify/ortsig-round3/evidence-runner.patch`: js-gradle-slot, shared clones, no init scripts,
slot-75 retries, and a read-only SHA-256 working-tree hash in place of git add. No assertions or test selection
semantics are changed. Child launch resets inherited SIG_IGN for INT before exec on both trees.
Python3 and Node are test-launch/classification dependencies, not product dependencies.
Detailed before/after observations and limits: `tmp/verify/ortsig-builder-3-report.md`.
Independent acceptance is left to the verifier; this builder does not author an acceptance record.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSignalAuthorityTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessSignalStopTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppUpgradeShutdownWiringTest.java",
        "modules/ui/src/test/java/io/justsearch/ort/HeadlessSignalStopProbe.java",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessSignalHandoffProbe.java"
      ],
      "class": "New",
      "behaviour": "S1\u2013S6: real TERM/INT child-JVM ordering for active, queued and truly detached native leases; bounded boot/close and fatal deadline status; one exit with first reason and nonce-bound upgrade receipt; registration refusal retains defaults; clean or halted Engine-only termination ends Linux-bound extraction/MCP stand-in helpers, with fatal status at deadline. The probe uses mocked OrtSession.run with real NativeSessionHandle/DetachableRun and admission ownership; it requires no model or GPU.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Decide what you can judge better than the user, which is most work: scope, scenarios, fixing now or deferring, design options, retries, restarting or resuming work the host stopped.",
          "delegationLocation": "AGENTS.md",
          "delegationScope": "The orchestrator chose the agent-adopted scenarios in the supplied ortsig/scenarios.md v2 under this standing delegation; the builder implements those settled scenarios without changing them.",
          "label": "agent choice",
          "choice": "S1\u2013S6: route non-Windows TERM/INT before hooks through the existing one-close authority; preserve baseline clean/refused/deadline status and fatal precedence, bound boot/drain, and arbitrate upgrades including after HTTP acknowledgement. S7/S9 preserve existing early signal exit and Linux child containment."
        }
      ],
      "evidence": "gates/test-intent/evidence/ortsig-signal-stop.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSignalAuthorityTest.java#signalExitMatchesNativeQuiescence",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSignalAuthorityTest.java#competingTriggersCloseOnceAndKeepFirstReason",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSignalAuthorityTest.java#upgradeAcknowledgementBeforeDispatchDoesNotOverrideSignalWinner",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSignalAuthorityTest.java#fatalPrecedenceReachesDeadlineWithoutWaitingForClose",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessSignalStopTest.java#hooksWaitForAdmittedQueuedDetachedAndBootWork",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessSignalStopTest.java#deadlineHaltsWithoutHooksAndSecondSignalCannotExtendIt",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessSignalStopTest.java#signalDuringBootFailureWaitsForFatalCleanup",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessSignalStopTest.java#fatalShutdownKeepsItsRealProcessStatusAtDeadline",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessSignalStopTest.java#handlerRegistrationFailureKeepsDefaultHandlingAndAttemptsBothSignals",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessSignalStopTest.java#containedExtractionAndMcpHelpersEndWithSignalStoppedEngine",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppUpgradeShutdownWiringTest.java#signalAndAcknowledgedUpgradeShareOneExitAuthority"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessSignalStopTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation checks: clean, refused and deadline TERM/INT preserve QUIT self-intent, expected code 0, quit handoff and baseline process status through real writers; before native boot both signals exit promptly with existing status. These pass on the base and are not claimed as repaired behavior.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Decide what you can judge better than the user, which is most work: scope, scenarios, fixing now or deferring, design options, retries, restarting or resuming work the host stopped.",
          "delegationLocation": "AGENTS.md",
          "delegationScope": "The orchestrator chose the agent-adopted scenarios in the supplied ortsig/scenarios.md v2 under this standing delegation; the builder implements those settled scenarios without changing them.",
          "label": "agent choice",
          "choice": "S1\u2013S6: route non-Windows TERM/INT before hooks through the existing one-close authority; preserve baseline clean/refused/deadline status and fatal precedence, bound boot/drain, and arbitrate upgrades including after HTTP acknowledgement. S7/S9 preserve existing early signal exit and Linux child containment."
        }
      ],
      "evidence": "gates/test-intent/evidence/ortsig-signal-stop.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessSignalStopTest.java#signalPreservesSelfIntentAndQuitHandoff",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessSignalStopTest.java#beforeNativeBootSignalExitsPromptly"
      ],
      "repaired": false
    }
  ]
}
```
