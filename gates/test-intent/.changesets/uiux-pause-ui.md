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
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts"
      ],
      "class": "New",
      "behaviour": "CR37 repairs focus continuity across status uncertainty, keeps impossible actions focusable with explanations and retains Engine refusals. CR37a/b replaces recovery Resume with tier-aware navigation to existing View tier or Reset controls, without invoking Reset or changing tier. Existing manual behavior is preserved.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "Agreed WP3.7 pause UI, CR37 rows 1, 2 and 5, CR37a and CR37b; Reset behavior and audience unchanged.",
          "choice": "Keyboard focus on Pause or Resume (or the duration choice), then a status observation that does not make the action impossible (for example an unavailable AI runtime, queued work, an activity reading briefly unconfirmed) \u2014 focus stays on the control; Enter or Space afterwards performs the action (S37.1, S37.7)",
          "label": "agent choice; agent-adopted (delegated-choice)",
          "case": "Keyboard focus on Pause or Resume (or the duration choice), then a status observation that does not make the action impossible (for example an unavailable AI runtime, queued work, an activity reading briefly unconfirmed)",
          "quote": "focus stays on the control; Enter or Space afterwards performs the action (S37.1, S37.7)",
          "location": "task t-20261019-bd718f CR37/CR37a/CR37b agreed continuation"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "Agreed WP3.7 pause UI, CR37 rows 1, 2 and 5, CR37a and CR37b; Reset behavior and audience unchanged.",
          "choice": "A status observation that does make the action impossible \u2014 the control says why where it is; focus is not dropped to the page body",
          "label": "agent choice; agent-adopted (delegated-choice)",
          "case": "A status observation that does make the action impossible",
          "quote": "the control says why where it is; focus is not dropped to the page body",
          "location": "task t-20261019-bd718f CR37/CR37a/CR37b agreed continuation"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "Agreed WP3.7 pause UI, CR37 rows 1, 2 and 5, CR37a and CR37b; Reset behavior and audience unchanged.",
          "choice": "Paused for settings recovery (reason settings_recovery) \u2014 every paused surface says the saved settings were damaged and replaced by defaults, that indexing stays held until the reset is confirmed in Settings, and to check exclusions; instead of Resume it offers a keyboard-reachable control that opens Settings at the existing Reset to defaults action; it never promises \"held until you resume\"",
          "label": "agent choice; agent-adopted (delegated-choice)",
          "case": "Paused for settings recovery (reason settings_recovery)",
          "quote": "every paused surface says the saved settings were damaged and replaced by defaults, that indexing stays held until the reset is confirmed in Settings, and to check exclusions; instead of Resume it offers a keyboard-reachable control that opens Settings at the existing Reset to defaults action; it never promises \"held until you resume\"",
          "location": "task t-20261019-bd718f CR37/CR37a/CR37b agreed continuation"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "Agreed WP3.7 pause UI, CR37 rows 1, 2 and 5, CR37a and CR37b; Reset behavior and audience unchanged.",
          "choice": "Paused for settings recovery, View tier User \u2014 same explanation, plus the route in words: in Settings, set View tier to Operator, then Reset to defaults; the control opens Settings at the View tier row; switching tier there then shows the Reset action without a reload",
          "label": "agent choice; agent-adopted (delegated-choice)",
          "case": "Paused for settings recovery, View tier User",
          "quote": "same explanation, plus the route in words: in Settings, set View tier to Operator, then Reset to defaults; the control opens Settings at the View tier row; switching tier there then shows the Reset action without a reload",
          "location": "task t-20261019-bd718f CR37/CR37a/CR37b agreed continuation"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "Agreed WP3.7 pause UI, CR37 rows 1, 2 and 5, CR37a and CR37b; Reset behavior and audience unchanged.",
          "choice": "Paused for settings recovery, View tier Operator or Developer \u2014 as CR37a: the control opens Settings at the existing Reset to defaults action",
          "label": "agent choice; agent-adopted (delegated-choice)",
          "case": "Paused for settings recovery, View tier Operator or Developer",
          "quote": "as CR37a: the control opens Settings at the existing Reset to defaults action",
          "location": "task t-20261019-bd718f CR37/CR37a/CR37b agreed continuation"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "Agreed WP3.7 pause UI, CR37 rows 1, 2 and 5, CR37a and CR37b; Reset behavior and audience unchanged.",
          "choice": "Any refused Pause or Resume (including SETTINGS_RECOVERY_REQUIRED reached another way) \u2014 the Engine's refusal message is shown where it was pressed; the state stays as status reports; never shown as done",
          "label": "agent choice; agent-adopted (delegated-choice)",
          "case": "Any refused Pause or Resume (including SETTINGS_RECOVERY_REQUIRED reached another way)",
          "quote": "the Engine's refusal message is shown where it was pressed; the state stays as status reports; never shown as done",
          "location": "task t-20261019-bd718f CR37/CR37a/CR37b agreed continuation"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f user turn 2026-10-19",
          "delegationScope": "Agreed WP3.7 pause UI, CR37 rows 1, 2 and 5, CR37a and CR37b; Reset behavior and audience unchanged.",
          "choice": "Ordinary manual pause \u2014 unchanged: Resume as before",
          "label": "agent choice; agent-adopted (delegated-choice)",
          "case": "Ordinary manual pause",
          "quote": "unchanged: Resume as before",
          "location": "task t-20261019-bd718f CR37/CR37a/CR37b agreed continuation"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-pause-ui-cr37.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37 jf-task-list retains focused Resume across unconfirmed observations and accepts activation",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37a jf-task-list explains damaged settings and navigates to the existing reset without offering Resume",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37 jf-status-deck retains focused Resume across unconfirmed observations and accepts activation",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37a jf-status-deck explains damaged settings and navigates to the existing reset without offering Resume",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37 jf-health-surface retains focused Resume across unconfirmed observations and accepts activation",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37a jf-health-surface explains damaged settings and navigates to the existing reset without offering Resume",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37 jf-library-surface retains focused Resume across unconfirmed observations and accepts activation",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37a jf-library-surface explains damaged settings and navigates to the existing reset without offering Resume",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37 jf-settings-surface retains focused Resume across unconfirmed observations and accepts activation",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37a jf-settings-surface explains damaged settings and navigates to the existing reset without offering Resume",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37 jf-sv3-window retains focused Resume across unconfirmed observations and accepts activation",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37a jf-sv3-window explains damaged settings and navigates to the existing reset without offering Resume",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37a the indexing overlay and waiting folder rows direct settings recovery to Settings",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37b OPERATOR recovery opens at the existing Reset action without changing tier",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37b DEVELOPER recovery opens at the existing Reset action without changing tier",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37 retains Pause and duration focus during uncertain observations and pending requests",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37 explains an impossible action in place without removing its focused control",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37 retains the Engine refusal SETTINGS_RECOVERY_REQUIRED where pressed across status polls",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#CR37 retains the Engine refusal SETTINGS_READ_ONLY where pressed across status polls"
      ],
      "repaired": true
    }
  ]
}
```

CR37/CR37a/CR37b continuation: agent-adopted under the same delegation. The original entries above are retained as historical intent; the new entry supersedes recovery Resume wording with the approved tier-aware route. Browser checks under `tmp/pause-ui-cr37b/` are scaffolding; the appended product checks are durable. Schedule and battery remain deferred to WP3.8. The independent verifier must append a fresh acceptance after this continuation.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "wp37-verifier-mlzrt9kc",
  "verdict": "accept",
  "digest": "sha256:d8ee7175f8018bf1909bf03ed2a3ffa91fba434622f3b5ee224d312c95bf2d05",
  "note": "Expectations follow the delegated S37/N37 scenarios; all 17 repaired checks pass and assertion-fail before, preservation passes. Acceptance covers test intent only; the unknown-reason HTTP decoder probe fails separately and does not receive product sign-off."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "wp37-verifier-er6hlprz",
  "verdict": "accept",
  "digest": "sha256:d8ee7175f8018bf1909bf03ed2a3ffa91fba434622f3b5ee224d312c95bf2d05",
  "note": "Independent review and scratch run-evidence: 17 repaired checks pass on snapshot 71f753520 and assertion-fail on b62fc5921; preservation passes. Sources match delegated S37/N37 scope. RS37 removes the unknown-reason product requirement; selector-only defensive coverage supplies no wire-boundary claim. Test intent accepted separately from the live product verdict."
}
```
