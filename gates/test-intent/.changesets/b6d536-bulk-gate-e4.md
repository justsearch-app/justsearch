---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, item 5, Spec E step E4, on b6d536/bulk-gate-2 (base fbc036129, the E3
commit). Game detection: UserContextSignals (gpu-bridge, FFM over shell32, user32 and powrprof)
reads the shell's notification state, Game Mode through the effective power mode notification (the
callback only stores a volatile value) and the foreground window (owner process, whether it covers
its monitor, whether it is the desktop or taskbar). The Engine's GPU residency sampler polls it on
its 2 s tick and applies the section 4.2 rule with the same tick's PDH figures and the same set of
our processes: Game Mode alone is a game; full screen, busy or a covering foreground is a game only
when the foreground process is not ours and holds at least 1 GiB of dedicated graphics memory on a
PDH reading. An unread part never counts. Debounce: raised after 2 polls, cleared after 5. Each
edge sets BulkWorkGate.setGame; the chat controller hears GAME, which outranks EXTERNAL_MEMORY. The
sampler's boot sample runs synchronously when the Engine starts it, before chat is created (CH7).
The operating indexing block carries indexing.paused_game_mode for PAUSED [game_mode].
justsearch.gpu.game_detection = auto | off (apply scope hot) turns detection off.

Delegated choices recorded here: (1) The boot sample decides at once: the first poll with detection
on is not debounced, so a game already running at boot holds bulk work and is reported before chat
first starts; later polls debounce. (2) The setting is read on every tick from the Engine's current
configuration (hot); off clears a detected game at once and stops reading the foreground; any value
other than auto or off is auto with a WARN. (3) A foreground read that throws counts as a poll
without a game. (4) If the foreground reader cannot open, the sampler runs without game detection.

Evidence files (all written by run-evidence.mjs):

- `b6d536-bulk-gate-e4-repaired.json`: repaired behaviour, failing by assertion on fbc036129 (the
  operating indexing block's reasonCode was null for PAUSED [game_mode]).
- `b6d536-bulk-gate-e4-preservation.json`: E3's pressure rows (sampler, status, Engine witness)
  pass on fbc036129 and on the change; exits 1 by design because it shows no repair. These test
  files are unchanged, so no entry below names them.
- `b6d536-bulk-gate-e4-new.json`: checks of classes and signatures added in this change (no seam
  for them exists on fbc036129).
- `b6d536-e4-merged-scope.json`: regenerated on b599d4d7e (phase B). Later merges added keys, so the
  packaged-register check now expects 289. It passes on the candidate and fails by assertion on
  fbc036129 (289 expected, 281 actual).

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingGameModeTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "S7 status half for a game: with a P12 gate whose game input is set, the operating indexing block is PAUSED with the single reason GAME_MODE, affects INDEXING, and carries the reasonCode indexing.paused_game_mode; once the game ends it is RUNNING with no reasons. With a game and external pressure together the reasons are GAME_MODE then GPU_BUSY_EXTERNAL and the code is indexing.paused_game_mode; when the game ends the code is indexing.paused_gpu_busy. On fbc036129 the reasonCode was null, failing by assertion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S20",
          "quote": "The GPU, chat, indexing and lifecycle states the UI shows are the ones dev tools and evaluation scripts read.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "The PAUSED code is indexing.paused_game_mode, TRANSIENT, registered in the readiness reason-code registry like indexing.paused_gpu_busy; it is chosen whenever GAME_MODE is the first reason.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e4-repaired.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingGameModeTest.java#aGamePausesWithTheGameModeCode",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingGameModeTest.java#aGameOutranksExternalMemory"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingAvailabilityTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "holdPausesAndReasonsAccumulate: indexing PAUSED with reasons GAME_MODE, ENERGY_SAVER, CHAT_GENERATING now carries the reasonCode indexing.paused_game_mode. That one assertion replaces 'assertNull(paused.reasonCode(), \"game mode is primary; its code lands in E4\")', which this task wrote in step E1 as the placeholder for this step; every other assertion of the method (PAUSED and the three reasons in precedence order) is unchanged, and the unused assertNull import is removed. Before the change the reasonCode was null.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "When a game is the first of several reasons, the block carries the game's code.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e4-repaired.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingAvailabilityTest.java#holdPausesAndReasonsAccumulate"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GameDetectorTest.java"
      ],
      "class": "New",
      "behaviour": "E-U4, the game rule and debounce. Game Mode alone is a game, with no foreground and no memory reading. Exclusive full screen, busy, and a foreground window covering its monitor are each a game when the foreground process holds 1 GiB of dedicated memory on a PDH reading, and none is at 1 GiB minus one byte. An ordinary window (none of the three) is no game at 8 GiB. Our own process in the foreground is no game. A shell window, presentation mode and 'no user present' are no game. No foreground reading, no memory reading, unknown memory, NVML or PDH without per-process figures, no figure for the foreground process, no foreground process, nothing read, and Game Mode read as off are each no game. The debounce raises on the second consecutive game poll and clears on the fifth consecutive poll without one (a 15-poll sequence). The boot seed decides at once; after a quiet seed the debounce applies; reset clears at once.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "Presentation mode excludes every non-Game-Mode branch, like QUNS_NOT_PRESENT; a covering window counts only while the shell is not in front; the boot sample is not debounced.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e4-new.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GameDetectorTest.java#gameModeAloneIsAGame",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GameDetectorTest.java#eachCandidateBranchWithOneGibIsAGame",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GameDetectorTest.java#eachCandidateBranchUnderOneGibIsNoGame",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GameDetectorTest.java#anOrdinaryWindowIsNoGame",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GameDetectorTest.java#ourOwnForegroundIsNoGame",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GameDetectorTest.java#shellPresentationAndNotPresentAreNoGame",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GameDetectorTest.java#unreadPartsAreNoGame",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GameDetectorTest.java#debounceRaisesAfterTwoAndClearsAfterFive",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GameDetectorTest.java#seedDecidesAtOnce"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuResidencyGameTest.java"
      ],
      "class": "New",
      "behaviour": "E-U4, the sampler rows. A game raises after two polls: the P12 gate holds for GAME_MODE alone and the chat receiver hears GAME; after four polls without it the gate still holds, after the fifth it does not and the receiver hears NONE. With external pressure raised first the receiver hears EXTERNAL_MEMORY, then GAME when the game raises (reasons GAME_MODE then GPU_BUSY_EXTERNAL), then EXTERNAL_MEMORY again when the game clears while the pressure stays. The boot sample, before any schedule, holds the gate for GAME_MODE and a receiver bound afterwards hears GAME. With detection turned off a detected game clears on the next tick (GAME then NONE), no game is detected over ten more ticks, and the foreground is not read. A foreground read that throws is no game: five such polls clear a detected game and five more do not raise one. Closing the sampler clears the gate and closes both readers.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "The boot sample decides at once; turning detection off clears at once and stops reading the foreground; a failed foreground read counts as a poll without a game; closing the sampler clears a game it published.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e4-new.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuResidencyGameTest.java#gameRaisesHoldsAndReportsThenClears",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuResidencyGameTest.java#gameOutranksExternalMemory",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuResidencyGameTest.java#bootSampleDetectsAGameAtOnce",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuResidencyGameTest.java#detectionOffClearsAtOnceAndDetectsNothing",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuResidencyGameTest.java#aFailedForegroundReadIsNoGame",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuResidencyGameTest.java#closeClearsTheGameAndClosesBothReaders"
      ]
    },
    {
      "items": [
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/context/UserContextSignalsTest.java"
      ],
      "class": "New",
      "behaviour": "E-S7d: UserContextSignals on the real Windows APIs of this machine (Windows only). SHQueryUserNotificationState answers with a known state; the effective power mode notification reports a mode within 5 s of registration; a foreground window's owner, when there is one, is a live process, and without one nothing covers a monitor. After close every sample is the unknown sample, and a second close is harmless. The covers rule needs every edge: an exact match and an overhang cover; a window short of the bottom edge, or on another monitor, does not; a monitor at a negative origin is covered by a window matching it.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e4-new.json",
      "newChecks": [
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/context/UserContextSignalsTest.java#readsTheRealUserContext",
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/context/UserContextSignalsTest.java#aClosedReaderReadsNothing",
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/context/UserContextSignalsTest.java#coversNeedsEveryEdge"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineGameDetectionWitnessTest.java"
      ],
      "class": "New",
      "behaviour": "Step E4 as the Engine composes it (sections 3.4 and 4.4, CH7). aGameAtBootHoldsBeforeTheFirstTickAndChatHearsIt: an EngineRoot with the production server factory and its gate on P12 starts its residency sampler over a PDH reader where a foreign process holds 2 GiB and a foreground reader showing that process in exclusive full screen; before any scheduled tick the sampler's latest observation is a game and the Engine's gate verdict reasons are GAME_MODE alone; the chat controller that BootstrapInferenceFactory then installs over the Engine's executors reports GAME; closing the process resources closes the foreground reader. detectionOffInTheEngineConfigurationDetectsNoGame: with justsearch.gpu.game_detection = off in the published configuration, the same readers over the boot sample and three ticks give no game and no hold.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "Until GpuProfileState exists (step E7), the boot sample runs where the Engine starts its residency sampler, which HeadlessApp does before buildApi creates chat; the setting is read from the Engine's current configuration on every tick.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e4-new.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineGameDetectionWitnessTest.java#aGameAtBootHoldsBeforeTheFirstTickAndChatHearsIt",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineGameDetectionWitnessTest.java#detectionOffInTheEngineConfigurationDetectsNoGame"
      ]
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/resolved/GameDetectionSettingTest.java"
      ],
      "class": "New",
      "behaviour": "Spec E section 4.3: justsearch.gpu.game_detection resolves to auto when unset (detection on), to off for '  OFF ' (detection off), and to auto for false, 0, disabled, the empty string and on.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "Only off, in any case and with spaces, turns detection off; any other value is auto with a WARN.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e4-new.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/resolved/GameDetectionSettingTest.java#defaultsToAuto",
        "modules/configuration/src/test/java/io/justsearch/configuration/resolved/GameDetectionSettingTest.java#offTurnsDetectionOff",
        "modules/configuration/src/test/java/io/justsearch/configuration/resolved/GameDetectionSettingTest.java#anyOtherValueIsAuto"
      ]
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/resolved/ConfigApplyScopesTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "E4 adds the hot justsearch.gpu.game_detection setting: the key has hot apply scope. The merged register has 284 keys: the 282-key E4 branch union plus justsearch.engine.incarnation and justsearch.eval.mode from integration. G5 provides the runtime settings source. The merged test retains the hot-scope assertion and complete packaged/repository equality; refreshed evidence is attached for this exact source.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "The user can turn detection off: justsearch.gpu.game_detection = auto | off.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-07 (owner-quoted spec E section 4.3)'; the text does not appear in the owner's turns of session 886833dd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "the integrate changeset's packaged-count oracle moved 282 to 284 under an Adaptation/signature class: reclassify it correctly with the reason (the merge union adds two integration keys).",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request: batch-1 repair builder brief, item 5, 2026-10-09'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-e4-merged-scope.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/resolved/ConfigApplyScopesTest.java#packagedRegisterIsTheRepositoryRegister"
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
  "session": "a3a46d49-5d40-43d9-a1ad-d9e9e536d01b",
  "verdict": "reject",
  "digest": "sha256:8b76ef4e0ca3ad92fc44947f981aaa82909de3423d3fbdbf120d52b2bf87107a",
  "note": "ConfigApplyScopesTest is entered as Adaptation (moved: signature), but its expected outcome changed: packagedRegisterIsTheRepositoryRegister now asserts 282 register entries instead of 281. test-intent.md keeps the whole oracle, expected outcome included, for an Adaptation, and no signature moved. Re-enter it as New with the source for the added justsearch.gpu.game_detection key and evidence (the count assertion fails on fbc036129). Also note: the entry calls the key hot, but the candidate gives it no runtime-changeable source (env, JVM arg or default only; no settings route), so the hot claim is unsupported. Other E4 entries checked and would be acceptable."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch1-20261008-independent",
  "verdict": "reject",
  "digest": "sha256:73cfe0910834aba5dd5ea7febd70397ff9e2b088f177389d1c378cfb1a6b3787"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch2-20261010-independent",
  "verdict": "accept",
  "digest": "sha256:3df0b5f42a35c0757b6d0cf4be85e046f1f42cb7a7862c34c7123b6df90a68d2",
  "note": "Corrected changed entry is New with owner authority for the merged 284-key oracle. Exact original parent register/resource fails by assertion (281), candidate passes count, scope and byte identity. Fresh native-context and candidate game checks pass; live settings source is hot settings.json. This intent judgment is separate from unfinished full live contract checks."
}
```
