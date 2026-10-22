---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: uiux-pause-ui-builder-p4e4l0ga
---

# Test-intent entries

All scenarios below are agent-adopted under delegated choice, never owner-adopted.
The quotes preserve the original agreed scenario text. S37.3's schedule and battery cases
are deferred to WP3.8 by orchestrator decision in the continuation request:

> Yes. Implement the pause UI now with the reasons the Engine reports today: manual (USER_PAUSED, with its end time when set) and settings recovery (SETTINGS_RECOVERY), keeping the automatic holds' existing reasons (N37.1). Schedule and battery reasons arrive with WP3.8, which is out of this package's scope; do not add Engine reasons for them. Make the reason display data-driven from the status reason so WP3.8 only adds wording, and make an unknown reason still show "paused" with a neutral reason rather than nothing.

The operation IDs follow design §2.A and the registered Engine catalog:
`core.indexing-pause` and `core.indexing-resume`. The context paragraph in the original
assignment reversed these names; the real Engine rejected that spelling, and it was repaired.
Browser checks and their screenshots are verification scaffolding under `tmp/pause-ui/`;
these two product test files are durable. No acceptance record is authored by the builder.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/indexingProgress.pause.test.ts",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts"
      ],
      "class": "New",
      "behaviour": "Status owns pausing/paused and resume, deadlines and reason wording; unknown non-automatic reasons retain a neutral pause. Empty paused queues stay visible. Shared shell surfaces and Search v3 expose the same invocation and approval path without optimistic state. Folder waiting retains failures and verification recovery; the single indexing announcer is quiet on identical polls. Search v3 retains context in Search and Ask modes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "Area 3 UI designs and agreed WP3.7 pause/resume scenarios; schedule and battery controls remain WP3.8.",
          "choice": "S37.1 The user can pause from Settings and from the main indexing progress view, with an optional end time, and resume wherever Paused is shown; by mouse and keyboard, with accessible names and state.",
          "quote": "The user can pause from Settings and from the main indexing progress view, with an optional end time, and resume wherever Paused is shown; by mouse and keyboard, with accessible names and state.",
          "agreedScenario": "S37.1",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "Area 3 UI designs and agreed WP3.7 pause/resume scenarios; schedule and battery controls remain WP3.8.",
          "choice": "S37.2 Between Pause and quiet the UI says pausing, then paused, following status (S36.1); never paused early.",
          "quote": "Between Pause and quiet the UI says pausing, then paused, following status (S36.1); never paused early.",
          "agreedScenario": "S37.2",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "Area 3 UI designs and agreed WP3.7 pause/resume scenarios; schedule and battery controls remain WP3.8.",
          "choice": "S37.3 Every paused state names its reason (manual, schedule, battery) and, for manual pause, the end time if set.",
          "quote": "Every paused state names its reason (manual, schedule, battery) and, for manual pause, the end time if set.",
          "agreedScenario": "S37.3",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "Area 3 UI designs and agreed WP3.7 pause/resume scenarios; schedule and battery controls remain WP3.8.",
          "choice": "S37.4 Folder rows while paused keep their real searchability state; failed, unavailable and unverified folders keep their caveats and recovery actions; folders with waiting work say it waits for Resume.",
          "quote": "Folder rows while paused keep their real searchability state; failed, unavailable and unverified folders keep their caveats and recovery actions; folders with waiting work say it waits for Resume.",
          "agreedScenario": "S37.4",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "Area 3 UI designs and agreed WP3.7 pause/resume scenarios; schedule and battery controls remain WP3.8.",
          "choice": "S37.5 Pause/Resume from one window or surface is reflected on the others after the next status observation; the UI never shows paused while status says running or the reverse.",
          "quote": "Pause/Resume from one window or surface is reflected on the others after the next status observation; the UI never shows paused while status says running or the reverse.",
          "agreedScenario": "S37.5",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "Area 3 UI designs and agreed WP3.7 pause/resume scenarios; schedule and battery controls remain WP3.8.",
          "choice": "S37.6 A paused state with nothing queued stays visible (not Ready).",
          "quote": "A paused state with nothing queued stays visible (not Ready).",
          "agreedScenario": "S37.6",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "Area 3 UI designs and agreed WP3.7 pause/resume scenarios; schedule and battery controls remain WP3.8.",
          "choice": "S37.7 Readable and operable in both themes, High Contrast, narrow width; announced once (paused, resumed), not on every poll.",
          "quote": "Readable and operable in both themes, High Contrast, narrow width; announced once (paused, resumed), not on every poll.",
          "agreedScenario": "S37.7",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "Area 3 UI designs and agreed WP3.7 pause/resume scenarios; schedule and battery controls remain WP3.8.",
          "choice": "S37.8 In Search v3, a search or Ask while paused with waiting files says that indexing is paused and offers Resume; it does not just show no results.",
          "quote": "In Search v3, a search or Ask while paused with waiting files says that indexing is paused and offers Resume; it does not just show no results.",
          "agreedScenario": "S37.8",
          "label": "agent choice; agent-adopted (delegated-choice)"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-pause-ui-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/indexingProgress.pause.test.ts#S37.2 follows PAUSING then PAUSED, withholding progress estimates throughout",
        "modules/ui-web/src/shell-v0/state/indexingProgress.pause.test.ts#S37.3 names the manual deadline and recovery reason, with neutral wording for unknown reasons",
        "modules/ui-web/src/shell-v0/state/indexingProgress.pause.test.ts#S37.6 keeps an empty paused queue visible instead of Ready",
        "modules/ui-web/src/shell-v0/state/indexingProgress.pause.test.ts#S37.5 takes resume from status, never from a reason or stable counts alone",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#S37.1 invokes Pause with the chosen deadline through the existing approval path without optimistic paused state",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#S37.1 the progress view pauses indefinitely and a sibling surface resumes from the same observation",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#S37.1 reports uncertain invocation failures without claiming paused",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#S37.4 waiting folder work names Resume and retains failure and searchability evidence",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#S37.4 paused unverified folders retain their verification caveat and recovery state",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#S37.7 the status announcer changes once per pause/resume edge, staying silent on repeated polls",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#S37.8 Search v3 offers Resume alongside paused waiting-file context for Search and Ask",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#S37.1 the indexing overlay exposes the same Pause and Resume control",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#S37.1 S37.5 S37.6 jf-task-list exposes Resume even with nothing queued",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#S37.1 S37.5 S37.6 jf-status-deck exposes Resume even with nothing queued",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#S37.1 S37.5 S37.6 jf-health-surface exposes Resume even with nothing queued",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#S37.1 S37.5 S37.6 jf-library-surface exposes Resume even with nothing queued",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#S37.1 S37.5 S37.6 jf-settings-surface exposes Resume even with nothing queued"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/indexingProgress.pause.test.ts"
      ],
      "class": "New",
      "behaviour": "Preservation: automatic game/GPU/energy holds retain their work-specific reasons and active text indexing. A PAUSED state without any policy reason cannot suppress progress or invent a user pause. Existing full Vitest and UI gate suites pass on baseline and candidate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "Area 3 UI designs and agreed WP3.7 pause/resume scenarios; schedule and battery controls remain WP3.8.",
          "choice": "N37.1 WP3.3's reasons for automatic holds; 813 progress rules; Settings surface sections and saves; UnifiedChatView unchanged apart from the shared surfaces it hosts.",
          "quote": "WP3.3's reasons for automatic holds; 813 progress rules; Settings surface sections and saves; UnifiedChatView unchanged apart from the shared surfaces it hosts.",
          "agreedScenario": "N37.1",
          "label": "agent choice; agent-adopted (delegated-choice)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "Area 3 UI designs and agreed WP3.7 pause/resume scenarios; schedule and battery controls remain WP3.8.",
          "choice": "N37.2 axe, proportion and UI gates; task list clear of the rail.",
          "quote": "axe, proportion and UI gates; task list clear of the rail.",
          "agreedScenario": "N37.2",
          "label": "agent choice; agent-adopted (delegated-choice)"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-pause-ui-preserve.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/indexingProgress.pause.test.ts#N37.1 automatic holds keep their work-specific explanations and active text indexing"
      ]
    }
  ]
}
```

## Acceptance records
