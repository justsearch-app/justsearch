---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, Spec F item 6 (supervision and containment), steps V and 1: the exit-ledger
stop-detail vocabulary gains the owner-side details and an unknown-token read, and the Engine joins
a kill-on-close job of its own as the first statement of `HeadlessApp.main`, so every helper it
starts ends with it. This changeset covers only this slice's files; the other flagged files on the
branch belong to other tasks.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/StopDetailVocabularyTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerFixtureTest.java",
        "governance/fixtures/exit-ledger/engine.v1.jsonl"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Spec F step V. ownerStopDetailsRoundTrip: session_end, takeover, interrupted and unknown are StopDetail tokens that read back to the same member. unknownTokenReadsUnknownWithRaw: a ledger record whose detail is a token this build does not know reads as UNKNOWN with the raw token kept (detailRaw), never as a parse failure, and the Engine's self-intent writer refuses to write UNKNOWN. fixturesConformAndRead: every line of governance/fixtures/exit-ledger/engine.v1.jsonl validates against governance/exit-ledger.v1.schema.json and reads through LedgerRecord with the detail the fixture names, including an unknown exit time read as unknown.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S15",
          "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S16",
          "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-stop-detail-vocabulary.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/StopDetailVocabularyTest.java#ownerStopDetailsRoundTrip",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/StopDetailVocabularyTest.java#unknownTokenReadsUnknownWithRaw",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerFixtureTest.java#fixturesConformAndRead"
      ]
    },
    {
      "items": [
        "modules/app-api/src/integrationTest/java/io/justsearch/app/api/runtime/containment/EngineJobHelperContainmentTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsEngineJobTest.java",
        "modules/app-launcher/src/test/java/io/justsearch/app/launcher/EngineContainmentArchTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Spec F step 1. killedEngineLeavesNoHelperOrConsoleHost (T23): a JVM that boots Engine containment and starts a ping helper, then is killed with taskkill /F, leaves nothing it started alive 3000 ms later, its console host included, and reported CONTAINED. bootJoinsAKillOnCloseJobWithExactlyThatLimit: after EngineContainment.installAtBoot() the process is in a job whose extended limit flags are exactly KILL_ON_JOB_CLOSE (0x2000), a second call returns the same installed containment, current() is it, and before install current() reads UNAVAILABLE not_installed. engineMainJoinsItsJobBeforeAnyOtherCall: the first call HeadlessApp.main makes is EngineContainment.installAtBoot(). onlyTheTwoContainmentClassesCreateJobsAndJavaNeverTerminatesOne: CreateJobObjectW appears in Java main only in WindowsEngineJob and WindowsParserContainment, TerminateJobObject in no Java main source, and TerminateJobObject( in no shell source but containment.rs.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S13",
          "quote": "If the app's shell crashes or is killed, the Engine and every helper end within seconds; the next launch starts normally.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-engine-job.json",
      "newChecks": [
        "modules/app-api/src/integrationTest/java/io/justsearch/app/api/runtime/containment/EngineJobHelperContainmentTest.java#killedEngineLeavesNoHelperOrConsoleHost",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/containment/WindowsEngineJobTest.java#bootJoinsAKillOnCloseJobWithExactlyThatLimit",
        "modules/app-launcher/src/test/java/io/justsearch/app/launcher/EngineContainmentArchTest.java#engineMainJoinsItsJobBeforeAnyOtherCall"
      ]
    },
    {
      "items": [
        "modules/app-launcher/src/test/java/io/justsearch/app/launcher/EngineContainmentArchTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppContainmentPolicyTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestContainmentTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineContainmentNoticeTest.java"
      ],
      "class": "New",
      "behaviour": "Spec F step 1, the surfaces of the Engine job. onlyTheTwoContainmentClassesCreateJobsAndJavaNeverTerminatesOne: see the entry above (no fail-before claimed). unavailableContainmentRefusesBootOnlyWhereContainmentIsRequired and containedOrInapplicableNeverRefuses: an Engine whose job could not be created refuses to boot (before the instance lock, exit FATAL_OR_UNCAUGHT) only under the conformance harness or JUSTSEARCH_REQUIRE_CONTAINMENT; contained, absent or inapplicable containment never refuses. manifestCarriesTheBootContainmentFromTheFirstWrite: the runtime manifest on disk and every head publish carry the boot containment status and reason, and the public projection keeps it. unavailableContainmentIsAStickyHealthConditionNamingTheFailedCall and containedOrInapplicableEnginePublishesNothing: an unavailable Engine job is a sticky WARNING health condition EngineContainmentUnavailable whose detail names the failed call; a contained or inapplicable Engine publishes none.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the open design points of the supervision and containment change end to end within scenarios S13-S16.",
          "choice": "Spec F section 2.4 failure policy as the builder implements it: an Engine without its job still boots for the user and says so (sticky reason code engine.containment_unavailable, a WARNING health condition, the manifest field), and refuses to boot only where containment is a precondition of the run, the conformance harness or JUSTSEARCH_REQUIRE_CONTAINMENT.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-containment-surface.json",
      "newChecks": [
        "modules/app-launcher/src/test/java/io/justsearch/app/launcher/EngineContainmentArchTest.java#onlyTheTwoContainmentClassesCreateJobsAndJavaNeverTerminatesOne",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppContainmentPolicyTest.java#unavailableContainmentRefusesBootOnlyWhereContainmentIsRequired",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppContainmentPolicyTest.java#containedOrInapplicableNeverRefuses",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestContainmentTest.java#manifestCarriesTheBootContainmentFromTheFirstWrite",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineContainmentNoticeTest.java#unavailableContainmentIsAStickyHealthConditionNamingTheFailedCall",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineContainmentNoticeTest.java#containedOrInapplicableEnginePublishesNothing"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/ChildControlVocabularyTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "exactMembers: the literal list of StopDetail wire tokens it compares against StopDetail.values() gains, at the end and in declaration order, the four tokens Spec F step V adds by design: session_end, takeover, interrupted and unknown. The oracle is unchanged: the enum must have exactly the listed members, in order, with no drift either way, and the expected list is still a literal, not computed from production code. Every other assertion in exactMembers and the rest of the class is unchanged. Orchestrator ruling under the owner's delegation.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S15",
          "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S16",
          "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the open design points of the supervision and containment change end to end within scenarios S13-S16.",
          "choice": "Orchestrator ruling: Spec F step V extends the StopDetail vocabulary with session_end, takeover, interrupted (owner-side QUIT details) and the reader-only unknown, so the exact-members check lists them; the exact-members, no-drift oracle is kept.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "0ae522aa-cda1-4c85-9e8f-65677d5cc3c0",
  "verdict": "accept",
  "digest": "sha256:27cc8baa2feadd2cd608344fc146bae2f46f36d6f2f8614b20aa0a65c97a8c53",
  "note": "Verifier f12: all six new checks fail on f75486245 for the reason under test and pass on ef6df5aec; T23 matched live (taskkill /F of the dev Engine ended llama-server, extraction JVM and their conhosts within 1.24 s). Notes: entry 3 cites Spec F section 2.4 where the failure policy is section 1.3; entry 4's 'input re-encoding' label is loose (the expected list changed, exact-members oracle kept); entry 1 could also cite S18 for takeover/interrupted."
}
```
