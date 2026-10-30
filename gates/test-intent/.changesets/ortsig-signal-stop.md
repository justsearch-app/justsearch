---
schema: test-intent.v1
task: ortsig
author-role: builder
author-session: ortsig-builder-5
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
`tmp/verify/ortsig-round5/evidence-runner.patch`: js-gradle-slot, shared clones, no init scripts,
slot-75 retries, and a read-only SHA-256 working-tree hash in place of git add. No assertions or test selection
semantics are changed. Child launch resets inherited SIG_IGN for INT before exec on both trees.
Python3 and Node are test-launch/classification dependencies, not product dependencies.
Detailed before/after observations and limits: `tmp/verify/ortsig-builder-5-report.md`.
The S3/S5 deadline repair covers a held close already owned by quit, restart or upgrade
for TERM and INT: status 1 with no hooks. Both signals also retain a previously selected
restart status 4. All six held-close cases fail by status assertion on candidate 59714ea571;
the selected-status controls pass on that candidate. The evidence below reruns against
7959b712fd; the repair report retains the separate 59714ea571 comparison.
The amended S6 rule is implemented in round 5: TERM/INT received before status selection
supplies status 143/130 once native owners quiesce, else 1, regardless of close owner.
The first close and its original self-intent remain; a signal-won upgrade writes no receipt.
Signals after selection retain the selected status. The authority tests cover both signals,
arrival orders and quiesced/unquiesced drains for quit/restart/upgrade/fatal owners; the HTTP
upgrade test covers the interval between acknowledgement and dispatch. The supplied independent
18-case CompetitionProbe is rerun against the baseline JSON oracle (status, intent, receipt).
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
      "behaviour": "S1\u2013S6: real TERM/INT child-JVM ordering for active, queued and truly detached native leases; bounded boot/close and fatal deadline status even when quit/restart/upgrade already owns a held close, retaining already-selected status; one close with first reason and self-intent, signal status before selection and no upgrade receipt when signalled; registration refusal retains defaults; clean or halted Engine-only termination ends Linux-bound extraction/MCP stand-in helpers, with fatal status at deadline. The probe uses mocked OrtSession.run with real NativeSessionHandle/DetachableRun and admission ownership; it requires no model or GPU.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Decide what you can judge better than the user, which is most work: scope, scenarios, fixing now or deferring, design options, retries, restarting or resuming work the host stopped.",
          "delegationLocation": "AGENTS.md",
          "delegationScope": "The orchestrator chose the agent-adopted scenarios in the supplied ortsig/scenarios.md v2 under this standing delegation; the builder implements those settled scenarios without changing them.",
          "label": "agent choice",
          "choice": "S1\u2013S6: route non-Windows TERM/INT before hooks through the existing one-close authority; preserve baseline clean/refused/deadline status, including signal precedence over competing fatal requests before selection, bound boot/drain, and arbitrate upgrades including after HTTP acknowledgement. S7/S9 preserve existing early signal exit and Linux child containment."
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
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessSignalStopTest.java#deadlineIsFatalWhenCooperativeOwnerHasNotSelectedStatus",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessSignalStopTest.java#deadlineKeepsStatusAlreadySelectedByRestart",
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
          "choice": "S1\u2013S6: route non-Windows TERM/INT before hooks through the existing one-close authority; preserve baseline clean/refused/deadline status, including signal precedence over competing fatal requests before selection, bound boot/drain, and arbitrate upgrades including after HTTP acknowledgement. S7/S9 preserve existing early signal exit and Linux child containment."
        }
      ],
      "evidence": "gates/test-intent/evidence/ortsig-signal-stop.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessSignalStopTest.java#signalPreservesSelfIntentAndQuitHandoff",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessSignalStopTest.java#beforeNativeBootSignalExitsPromptly"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSignalAuthorityTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation controls for TERM/INT after final status selection: quit 0, restart 4, upgrade 0 with its receipt, fatal 1. All eight pass on fa6a807110 as well as the candidate; this is not repaired behavior. The original main baseline lacks the signal claim entry point, so this reflective check fails structurally there; no behavioral fail-before is claimed for these controls. Eight additional real-signal child-JVM controls on the candidate preserve the same selected statuses and receipt.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Decide what you can judge better than the user, which is most work: scope, scenarios, fixing now or deferring, design options, retries, restarting or resuming work the host stopped.",
          "delegationLocation": "AGENTS.md",
          "delegationScope": "The orchestrator chose the agent-adopted scenarios in the supplied ortsig/scenarios.md v2 under this standing delegation; the builder implements those settled scenarios without changing them.",
          "label": "agent choice",
          "choice": "S1\u2013S6: route non-Windows TERM/INT before hooks through the existing one-close authority; preserve baseline clean/refused/deadline status, including signal precedence over competing fatal requests before selection, bound boot/drain, and arbitrate upgrades including after HTTP acknowledgement. S7/S9 preserve existing early signal exit and Linux child containment. Signals received after final status selection leave the selected status and upgrade receipt unchanged."
        }
      ],
      "evidence": "gates/test-intent/evidence/ortsig-signal-stop.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSignalAuthorityTest.java#signalAfterSelectionKeepsOwnerStatus"
      ],
      "repaired": false
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a18d28-c68c-71b2-917b-2b2044c53c30",
  "verdict": "accept",
  "digest": "sha256:6ae53490410fea9d4e900761220a2d2e0722221371b1b8d6a17bfa6ec8a886f3",
  "note": "Codex verifier-3 (final round) on 9b4bf16f26: PASS S1-S7, S9, S11 (5 real crash-free cycles; earlier rounds 30+10, base 5/5 SIGSEGV); S6 18/18 match base; classifications and disclosures honest. S8/S10 unverified (named limits)."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a18db9-c2e8-7ed1-9911-bd98d0318ef4",
  "verdict": "accept",
  "digest": "sha256:8705adc4aca135fff1b1d7eedf56d9faf42de606aa2a308dc370cbaec0fd9df1",
  "note": "Codex verifier-4 on 1ba58972e1: lint-only diff (compiled instructions identical), 86 selected tests and fresh PMD pass, classifications and evidence honest, 16 check-source digests match; gate fails only for stale acceptance against the merge base. Behaviour verified by verifier-3 (session 01a18d28) on the pre-lint commit."
}
```
