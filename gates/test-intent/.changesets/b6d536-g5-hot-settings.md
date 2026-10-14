---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b6d536-g5-hot-settings-20261007
---

# Test-intent entries

The config-surface key and hot apply-scope entry already exist from E4. This repair
adds a settings.json source without introducing another resolver key (282 entries).
The settings UI has no GPU or performance section; the API exposes the switch.
E4 and E6 corrections preserve the independent verifier's rejected records as history.
Acceptance on the new content belongs to the independent verifier, not this builder.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/app/engine/GameDetectionSettingsApiTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The running Engine resumes bulk on the next tick after a settings API off update, then pauses again on auto with the same detected game. The override survives a partial patch, is persisted, and appears in settings reads and effective-config with settings.json provenance. Invalid API values are rejected without committing a revision.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Make it a user setting applied hot through the existing settings path: a settings.json field mapped by ConfigStoreRebuilder, the settings API, config-surface registration and effective-config.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-07 builder brief'; the text does not appear in the owner's turns of session 886833dd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "The user can turn detection off: justsearch.gpu.game_detection = auto | off.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-07 (owner-quoted spec E section 4.3)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-g5-api.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/app/engine/GameDetectionSettingsApiTest.java#settingsApiTurnsDetectionOffAndBackOnWithoutRestart",
        "modules/ui/src/test/java/io/justsearch/app/engine/GameDetectionSettingsApiTest.java#settingsApiRejectsInvalidDetectionWithoutCommit"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/api/settingsAttempt.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The client retains off and auto in validated settings API patches. Existing settings-attempt checks keep their full oracle.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Make it a user setting applied hot through the existing settings path: a settings.json field mapped by ConfigStoreRebuilder, the settings API, config-surface registration and effective-config.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-07 builder brief'; the text does not appear in the owner's turns of session 886833dd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "The user can turn detection off: justsearch.gpu.game_detection = auto | off.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-07 (owner-quoted spec E section 4.3)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-g5-client.json",
      "newChecks": [
        "modules/ui-web/src/api/settingsAttempt.test.ts#carries game detection off and auto in settings attempts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/api/__fixtures__/settings-v2-live.json"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "WireRecordSchemaGenTest regenerates the captured settings response with llm.gameDetection:null for the absent override. All other fixture fields and consumer expectations are unchanged. Null and omission both preserve the prior override in a partial settings patch; the existing generated-schema faithfulness checks accept both encodings.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Make it a user setting applied hot through the existing settings path: a settings.json field mapped by ConfigStoreRebuilder, the settings API, config-surface registration and effective-config.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-07 builder brief'; the text does not appear in the owner's turns of session 886833dd"
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
  "session": "verify-batch1-20261008-independent",
  "verdict": "accept",
  "digest": "sha256:43c0547f9f087cbf5972dca05ee0dde27afe32c7623e106052fe2aa721d8400e"
}
```
