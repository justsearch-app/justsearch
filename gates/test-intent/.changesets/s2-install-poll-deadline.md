---
schema: test-intent.v1
task: t-20261101-uiimpl
author-role: builder
author-session: s2-install-poll-deadline-builder-20261103
---

# Test-intent entries

The behaviour is an agent choice within the owner's delegation to fix the install-status hang as suggested
("proceed with the rest as you suggested"): a per-read deadline of at least 5 s (the slow poll interval, also
during the fast cadence); T3 states that rule in the header.
Pack reads now carry the same failure observation as install/runtime through the store to the page.

Evidence is produced by the unmodified run-evidence.mjs from a temporary shared clone with copied
dependencies; its before-worktree belongs to that clone and cannot write into the source Git
metadata. T1 checks fail by assertion on bfc6a52032 because a held read blocks the next batch.
Repair checks fail by assertion on cab9030e66 for the late-refusal timer, premature fast deadline,
and missing pack failure wording. The preservation evidence covers existing poller tests, the
slow idle read, Brain observation freshness, and desktop recovery. No acceptance record is written.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.deadline.test.ts"
      ],
      "class": "New",
      "behaviour": "Each endpoint abandons a held response or body after a deadline of at least the slow 5s interval, retains history while marking its observation failed, recovers on the next tick and ignores late results. Repeated forever-held reads cannot stop polling. Abandonment releases one shared queued refresh.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with the rest as you suggested",
          "delegationLocation": "task t-20261103-oldleft, user turn 2026-11-03T12:02Z (the follow-up was then handed over to task t-20261101-uiimpl)",
          "delegationScope": "Fixing the AI install-status hang (one held install-status request stops all later polling) as suggested: a per-request deadline so a held request counts as a failed tick, and correcting the aiInstallPoll.ts header claim.",
          "label": "agent choice",
          "choice": "A held install, runtime or pack status read is abandoned after a deadline of at least 5 s (the slow poll interval, also while the fast 1 s cadence runs) and counts as a failed read: its observation is marked failed (the AI Brain page says it is checking or shows last observed values), the next tick is scheduled, and the page recovers on the next answered read; late results never publish."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with the rest as you suggested",
          "delegationLocation": "task t-20261103-oldleft, user turn 2026-11-03T12:02Z (the follow-up was then handed over to task t-20261101-uiimpl)",
          "delegationScope": "Fixing the AI install-status hang (one held install-status request stops all later polling) as suggested: a per-request deadline so a held request counts as a failed tick, and correcting the aiInstallPoll.ts header claim.",
          "label": "agent choice",
          "choice": "Normal reads, the adaptive cadence, refresh-now (no overlap, one shared queued read), stop and resubscribe, API base change, admission retry delays and slow reads that answer within the deadline behave as before."
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
          "kind": "delegated-choice",
          "delegationQuote": "proceed with the rest as you suggested",
          "delegationLocation": "task t-20261103-oldleft, user turn 2026-11-03T12:02Z (the follow-up was then handed over to task t-20261101-uiimpl)",
          "delegationScope": "Fixing the AI install-status hang (one held install-status request stops all later polling) as suggested: a per-request deadline so a held request counts as a failed tick, and correcting the aiInstallPoll.ts header claim.",
          "label": "agent choice",
          "choice": "Normal reads, the adaptive cadence, refresh-now (no overlap, one shared queued read), stop and resubscribe, API base change, admission retry delays and slow reads that answer within the deadline behave as before."
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
          "kind": "delegated-choice",
          "delegationQuote": "proceed with the rest as you suggested",
          "delegationLocation": "task t-20261103-oldleft, user turn 2026-11-03T12:02Z (the follow-up was then handed over to task t-20261101-uiimpl)",
          "delegationScope": "Fixing the AI install-status hang (one held install-status request stops all later polling) as suggested: a per-request deadline so a held request counts as a failed tick, and correcting the aiInstallPoll.ts header claim.",
          "label": "agent choice",
          "choice": "A held install, runtime or pack status read is abandoned after a deadline of at least 5 s (the slow poll interval, also while the fast 1 s cadence runs) and counts as a failed read: its observation is marked failed (the AI Brain page says it is checking or shows last observed values), the next tick is scheduled, and the page recovers on the next answered read; late results never publish."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with the rest as you suggested",
          "delegationLocation": "task t-20261103-oldleft, user turn 2026-11-03T12:02Z (the follow-up was then handed over to task t-20261101-uiimpl)",
          "delegationScope": "Fixing the AI install-status hang (one held install-status request stops all later polling) as suggested: a per-request deadline so a held request counts as a failed tick, and correcting the aiInstallPoll.ts header claim.",
          "label": "agent choice",
          "choice": "Normal reads, the adaptive cadence, refresh-now (no overlap, one shared queued read), stop and resubscribe, API base change, admission retry delays and slow reads that answer within the deadline behave as before."
        }
      ],
      "conflictSearch": "Repository decision records searched for polling deadlines and abandonment: none conflict. The supplied T1/T2 scenarios supersede holding requests beyond one interval.",
      "behaviour": "Retire only the assumption that a still-pending read can hold a batch for 20–30s. Lifecycle cancellation resolves replacement reads within their deadline and now asserts the successful pack observation as well. The overlap test retains every existing count, retained-value, observation, queue-sharing, cancellation and cadence assertion.",
      "evidence": "gates/test-intent/evidence/s2-install-poll-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.test.ts",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.refresh.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.repairs.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.packObservation.test.ts"
      ],
      "class": "New",
      "behaviour": "A refusal arriving after stop cannot re-arm a timer. Fast-cadence reads answering at 4999ms stay successful and the next poll follows 1s after completion. Pack timeout and HTTP failure retain running history but label it last observed, show Checking pack status and gate Import with a checking reason rather than a present-tense running claim; the next successful tick restores idle status and Import, and a late response cannot replace recovery.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with the rest as you suggested",
          "delegationLocation": "task t-20261103-oldleft, user turn 2026-11-03T12:02Z (the follow-up was then handed over to task t-20261101-uiimpl)",
          "delegationScope": "Fixing the AI install-status hang (one held install-status request stops all later polling) as suggested: a per-request deadline so a held request counts as a failed tick, and correcting the aiInstallPoll.ts header claim.",
          "label": "agent choice",
          "choice": "A held install, runtime or pack status read is abandoned after a deadline of at least 5 s (the slow poll interval, also while the fast 1 s cadence runs) and counts as a failed read: its observation is marked failed (the AI Brain page says it is checking or shows last observed values), the next tick is scheduled, and the page recovers on the next answered read; late results never publish."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with the rest as you suggested",
          "delegationLocation": "task t-20261103-oldleft, user turn 2026-11-03T12:02Z (the follow-up was then handed over to task t-20261101-uiimpl)",
          "delegationScope": "Fixing the AI install-status hang (one held install-status request stops all later polling) as suggested: a per-request deadline so a held request counts as a failed tick, and correcting the aiInstallPoll.ts header claim.",
          "label": "agent choice",
          "choice": "Normal reads, the adaptive cadence, refresh-now (no overlap, one shared queued read), stop and resubscribe, API base change, admission retry delays and slow reads that answer within the deadline behave as before."
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-install-poll-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.repairs.test.ts#T2 a late admission refusal after stop cannot re-arm a deadline timer",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.repairs.test.ts#T2 fast polling accepts a healthy loaded Engine read just before 5s",
        "modules/ui-web/src/shell-v0/views/BrainSurface.packObservation.test.ts#T1 a pack timeout shows retained history as checking and recovers the Import control",
        "modules/ui-web/src/shell-v0/views/BrainSurface.packObservation.test.ts#T1 a pack HTTP failure shows retained history as checking and recovers the Import control"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusDeck.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The typed AiState fixture supplies the new required packObservationLive field (false with its null packStatus), as it already does for install/runtime. Inputs, observations and every assertion are unchanged."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "reviewer-43",
  "verdict": "reject",
  "digest": "sha256:ec1c39b326f35bf956ea60bd18050384e69e92c2e7924c7af70534b17f3b4e56",
  "note": "Audit by independent reviewer session reviewer-43 (Codex): reject: sources labelled owner-adopted for agent-drafted scenarios never shown to the owner. Transcribed by the orchestrator."
}
```
