---
schema: test-intent.v1
task: t-20261101-uiimpl
author-role: builder
author-session: s2-install-poll-deadline-builder-20261103
---

# Test-intent entries

T1 and T2 are quoted from the supplied, owner-adopted scenarios. T3 is the deadline rule in the
poller header. Pack status has no observation flag; its failed read retains history and releases
the batch, preserving the existing snapshot contract.

Evidence is produced by the unmodified run-evidence.mjs from a temporary shared clone with copied
dependencies; its before-worktree belongs to that clone and cannot write into the source Git
metadata. T1 checks fail by assertion on bfc6a52032 because a held read blocks the next batch.
The preservation evidence covers both existing test files and the slow successful read.
It also runs the unchanged Brain observation-freshness and desktop-recovery tests: the
statusPoll rule honours explicit admission retry delays as answered reads, and stopping releases
the new deadline timers synchronously.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.deadline.test.ts"
      ],
      "class": "New",
      "behaviour": "Each endpoint abandons a held response or body after the interval fixed at read start, retains history while marking install/runtime observations failed, recovers on the next tick and ignores late results. Repeated forever-held reads cannot stop polling. Idle reads use 5s and abandonment releases one shared queued refresh.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "T1",
          "quote": "An install-status, runtime-status or pack-status read the Engine does not answer within the deadline (the statusPoll rule: abandoned after one poll interval, aborted) counts as a failed read: that observation is marked failed (the AI Brain page says it is checking, as for any failed read) and the next tick is scheduled; when reads answer again the page recovers on the next tick. A request held forever never stops polling.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "T2",
          "quote": "Normal reads, the adaptive cadence (fast while installing, slow otherwise), refresh-now (no overlapping reads, one shared queued read), stop and resubscribe, API base change, and a slow read that answers within the deadline are unchanged; aborted reads never publish late results.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-install-poll-deadline.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.deadline.test.ts#T1 abandons a held install response and recovers on the next tick",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.deadline.test.ts#T1 abandons a held install body and recovers on the next tick",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.deadline.test.ts#T1 abandons a held runtime response and recovers on the next tick",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.deadline.test.ts#T1 abandons a held runtime body and recovers on the next tick",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.deadline.test.ts#T1 abandons a held packs response and recovers on the next tick",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.deadline.test.ts#T1 abandons a held packs body and recovers on the next tick",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.deadline.test.ts#T1 keeps polling when every response is held forever",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.deadline.test.ts#T1/T2 abandons an idle read at 5s and shares one queued refresh after abandonment"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.deadline.test.ts"
      ],
      "class": "New",
      "behaviour": "An idle read answering at 4999ms remains successful and un-aborted; the next ordinary read is still scheduled 5s after completion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "T2",
          "quote": "Normal reads, the adaptive cadence (fast while installing, slow otherwise), refresh-now (no overlapping reads, one shared queued read), stop and resubscribe, API base change, and a slow read that answers within the deadline are unchanged; aborted reads never publish late results.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-install-poll-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.deadline.test.ts#T2 uses the idle interval as the deadline and accepts a slow answer before it"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.test.ts",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.refresh.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "T1",
          "quote": "An install-status, runtime-status or pack-status read the Engine does not answer within the deadline (the statusPoll rule: abandoned after one poll interval, aborted) counts as a failed read: that observation is marked failed (the AI Brain page says it is checking, as for any failed read) and the next tick is scheduled; when reads answer again the page recovers on the next tick. A request held forever never stops polling.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "T2",
          "quote": "Normal reads, the adaptive cadence (fast while installing, slow otherwise), refresh-now (no overlapping reads, one shared queued read), stop and resubscribe, API base change, and a slow read that answers within the deadline are unchanged; aborted reads never publish late results.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "Repository decision records searched for polling deadlines and abandonment: none conflict. The supplied T1/T2 scenarios supersede holding requests beyond one interval.",
      "behaviour": "Retire only the assumption that a still-pending read can hold a batch for 20–30s. Lifecycle cancellation now resolves replacement reads at 999ms. The overlap test holds the initial batch for 999ms and the queued idle batch for 4999ms. Every existing count, retained-value, observation, queue-sharing, cancellation and cadence assertion remains.",
      "evidence": "gates/test-intent/evidence/s2-install-poll-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.test.ts",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.refresh.test.ts"
      ]
    }
  ]
}
```

## Acceptance records

