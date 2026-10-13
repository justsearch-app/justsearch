---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b84-gpu-health-2-round-2
---

# Preserve a person's effective pressure override and publish reload timing

Starting revision: 66fbe51d5. Whole-change reference: 44a9e8aa2.
The existing expectations and earlier regression checks remain unchanged.
The owner delegated design; the choices above implement the orchestrator's round-2 decisions.
The status fail-before compatibility patch adds only a no-op supplier setter to the old handler,
so its check compiles while the old null reload fields and old demand behavior remain intact.
The patch is recorded in the producer's evidence; it contains no repair.

```json
{
  "entries": [
    {
      "sources": [
        {
          "label": "agent choice",
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "choice": "A person overrides an effective pressure level until a confirmed clear or escalation. A forced reload during the wait remembers its unload cause. Status reports the same wait as the demand refusal.",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Autonomous GPU co-residence design and repair, including the round-2 decisions"
        }
      ],
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#loadAnywaySurvivesOverlappingPressureAtTheSameEffectiveLevel",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#profileDowngradeKeepsTheOverrideUntilEffectivePressureClears"
      ],
      "behaviour": "All five applicable overlap sequences preserve load anyway at the same or lower effective level. Effective clearing ends the override; a profile downgrade stays covered.",
      "repaired": true,
      "class": "New",
      "evidence": "gates/test-intent/evidence/b6d536-b84-round-2-controller.json",
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java"
      ]
    },
    {
      "sources": [
        {
          "label": "agent choice",
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "choice": "A person overrides an effective pressure level until a confirmed clear or escalation. A forced reload during the wait remembers its unload cause. Status reports the same wait as the demand refusal.",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Autonomous GPU co-residence design and repair, including the round-2 decisions"
        }
      ],
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java#loadAnywayDuringDwellSurvivesReturningPressureUntilHysteresisClears",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java#returningPressureOverrideStillYieldsToMoreSevereEffectivePressure"
      ],
      "behaviour": "A forced reload during a clear dwell remembers external pressure for all profiles. Returning pressure is covered, four clear samples do not revoke the choice, five clear samples do, and P8/P12 still yield to a more severe game level. Agents never force the load.",
      "repaired": true,
      "class": "New",
      "evidence": "gates/test-intent/evidence/b6d536-b84-round-2-controller.json",
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SamplerChatResidencyTest.java"
      ]
    },
    {
      "sources": [
        {
          "label": "agent choice",
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "choice": "A person overrides an effective pressure level until a confirmed clear or escalation. A forced reload during the wait remembers its unload cause. Status reports the same wait as the demand refusal.",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Autonomous GPU co-residence design and repair, including the round-2 decisions"
        }
      ],
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java#statusDuringReloadWaitMatchesTheDemandRefusal"
      ],
      "behaviour": "The real status handler publishes reloadAllowed=false and nextReloadNotBefore from the controller wait under NONE, external memory, or game pressure. Human and agent demand refusals agree with that deadline; forceAllowed remains human-only; READY clears the deadline.",
      "repaired": true,
      "class": "New",
      "evidence": "gates/test-intent/evidence/b6d536-b84-round-2-status.json",
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java"
      ]
    },
    {
      "sources": [
        {
          "label": "agent choice",
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "choice": "nextReloadNotBefore is an absolute instant that stays the same for a given wait and changes only when the wait does; the ordinary chat refusal's retry-after counts down that same wait, in whole seconds rounded up, at least one.",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Autonomous GPU co-residence design and repair, including the round-2 decisions"
        }
      ],
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java#consecutiveStatusPassesWhileChatWaitsUnderPressureKeepTheReloadInstantAndSeq"
      ],
      "behaviour": "Two consecutive operating status passes while chat waits under pressure, with nothing else changed, publish the same nextReloadNotBefore, rounded up to a whole second and about 30 s after the wait began, and the same servedOperating seq.",
      "repaired": true,
      "class": "New",
      "evidence": "gates/test-intent/evidence/b6d536-b84-reload-instant.json",
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java"
      ]
    },
    {
      "sources": [
        {
          "label": "agent choice",
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "choice": "nextReloadNotBefore is an absolute instant that stays the same for a given wait and changes only when the wait does; the ordinary chat refusal's retry-after counts down that same wait, in whole seconds rounded up, at least one.",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Autonomous GPU co-residence design and repair, including the round-2 decisions"
        }
      ],
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#underPressureTheRefusalCountsDownTheRunningWaitInsteadOfRestartingIt"
      ],
      "behaviour": "Under pressure, the first refusal says the full 30 s; later refusals within the same wait say the whole seconds left of it, rounded up and at least one; pressure that outlasts the wait starts the next one at 30 s.",
      "repaired": true,
      "class": "New",
      "evidence": "gates/test-intent/evidence/b6d536-b84-reload-instant.json",
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java"
      ]
    },
    {
      "sources": [
        {
          "label": "agent choice",
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "choice": "nextReloadNotBefore is an absolute instant that stays the same for a given wait and changes only when the wait does; the ordinary chat refusal's retry-after counts down that same wait, in whole seconds rounded up, at least one.",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "Autonomous GPU co-residence design and repair, including the round-2 decisions"
        }
      ],
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java#clearedPressureEndsThePublishedReloadWaitAndRaisesSeq"
      ],
      "behaviour": "When the pressure clears, the next status pass publishes reloadAllowed=true with no nextReloadNotBefore and a higher servedOperating seq.",
      "class": "New",
      "evidence": "gates/test-intent/evidence/b6d536-b84-reload-instant-preservation.json",
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java"
      ]
    }
  ]
}
```

## Acceptance records
