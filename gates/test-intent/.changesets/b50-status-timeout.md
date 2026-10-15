---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b50-status-timeout-builder-20261010
---

The builder brief requires status to return within the unchanged five-second client deadline
while producers cannot complete. Real HTTP checks hold each blocking dependency, cover boot,
asynchronous fresh requests, failed observations and recovery. The one-second assertion is a
deliberately tighter server budget; no request retry or client-timeout increase is introduced.
The before-state is f6d579a86. Independent intent acceptance remains pending.

Refreshed after b82 (run b82g-status-records). Entry 1's evidence was re-run on the current checks, with the fail-before again at f6d579a86, no overlays and no compatibility patch. Both fail-before details are identical to the first record. StatusNonBlockingHttpTest changed twice since then; WorkerStatusSamplerTest did not:

- In b53 (971b55573), StatusNonBlockingHttpTest became a b53 check too. b53 added the failure-stays-local cases and the power, resume and vision sources. It replaced several expectations: a failed producer no longer aborts publication, and a held producer now marks only its own part stale. b53's entry 1 records those expectations and their sources. The case count rose from 26 to 46; no case was removed.
- In b82, the blocked-dependency case changed because of b82's decision (a). Entry 3 records that adaptation.

Entry 1's behaviour text was corrected after the refresh (b82g correction). It said fresh=true schedules an observation rather than joining it. The current code waits for that observation for at most 100 ms (StatusLifecycleHandler.FRESH_READ_DEADLINE_MS). It also said the stale or unknown markers are returned while a dependency cannot answer. They now appear only once the held part has aged past its own lane's limit (entry 3), and a failed producer marks only its own part. This corrects the description of code that did not change in this refresh; no check or evidence changed.

Refreshed in records phase B (batch 3) on b599d4d7e, because ee3c86b19 changed WorkerStatusSamplerTest to build HealthEventChangeRegistry with its delivery executor. That constructor does not exist at f6d579a86, so the fail-before now applies the compat before-patch gates/test-intent/evidence/b50-status-timeout-compat.patch. It adds only the declaration HealthEventChangeRegistry(ExecutorService), which ignores its argument and calls the existing no-argument constructor; no production code at f6d579a86 calls it. Both checks again fail by assertion there, with the same details as before, and pass on the candidate (46 and 9 cases).

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/WorkerStatusSamplerTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Real GET /api/status stays below one second, with an unchanged five-second client deadline, while disk, inference, GPU capabilities/native memory, GPL, conversation protection, telemetry, VDU, device plan, bulk gate, registry, assembly, Worker, RRD, agent availability, online AI, GPU saturation, model-file path resolution or admission cannot answer. A held part is reported with the existing stale/unknown markers once it has aged past its own lane's limit (entry 3); the other parts stay current. A boot request without fresh=true returns unknown at once without joining its first observation. A fresh=true request starts an observation and waits for it for at most 100 ms (StatusLifecycleHandler.FRESH_READ_DEADLINE_MS). A part that has not answered by then keeps its last published value, or unknown before its first, and the response stays within the budget. A failed producer does not abort publication: only its own part becomes stale or unknown, and the other parts keep their current values. Release and successful sampling restore freshness. The existing sampler checks retain RPC counts, sampling cadence and tap ownership; their boot and fresh observations now await the off-request producer.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "status is computed from current state, cheaply, and is never blocked behind inference, I/O or another component's lock; a component that cannot answer in time is reported as such (existing unknown/stale states), not waited for.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Do not raise the client timeout or add retries.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b50-status-timeout.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/WorkerStatusSamplerTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/LifecycleContractTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusOperatingStateTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusReadinessStalenessTest.java",
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/PlannedContextSettingsIntegrationTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "Fixtures explicitly invoke the existing sampler entry point before reading the completed observation, including after a settings mutation, GPU reader closure and GPU refresh. Before-state status reads implicitly performed those producer calls; the repaired read path only projects their publication. Original producer inputs, component transitions, staleness clock boundaries, settings persistence, lifecycle/readiness/GPU/degradation expectations and real HTTP assertions are retained. Health contract fixtures are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Preservation: status contents and the status-surface checks (specC), lane F status consumers, frontend status tests.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"
        }
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "b82 decision (a): a hung lane is reported stale only after 3 of its own update intervals, so detection is later when idle, about 30 to 120 s. This is accepted as the cost of no longer flipping current values to unknown under load. Under the old rule, a refresh in flight for more than 1 s made its lane stale, so the blocked-dependency case advanced its clock 1001 ms for non-lock sources. It now advances 31001 ms for every source, as the lock sources already did, and then runs one more sampler pass for non-lock sources, with an added assertion that this pass finishes within 10 s while the dependency is held. Each case still holds the same dependency. It asserts the same budget of under one second against the unchanged five-second client deadline, the same stale or unknown marker on the held part, the same fresh=true response and the same recovery after release."
    }
  ]
}
```

## Acceptance records

Pending independent verifier review. The builder cannot self-accept or delegate.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status-codex-20261010",
  "verdict": "reject",
  "digest": "sha256:435e167ab827c0a85ca85faee017cdc182971488eee67baca359879a29003b8f",
  "note": "Independent real-HTTP checks fail component isolation: a held disk probe marks a healthy Worker stale; a hung Worker marks head-local AI stale. Healthy GPU turns stale before the next idle sample, and overlapping status changes the previous capacity refusal from 429 to 200. Candidate latency checks pass but assert global staleness, which does not cover the owner-required selective stale behavior. Evidence: F:/agent-sandbox/design-b6d536/codex/verify-status/report.md and candidate-focused-first-extended-xml; baseline GPU and overlapping-capacity preservation checks pass."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status2-codex-20261010",
  "verdict": "reject",
  "digest": "sha256:faeb591a181fad7bdb83bff7822f173e1cbab386fbb28e15c1069aafee79784d",
  "note": "FAIL remaining status-consumer contract: live UI stays Online after disable while fresh canonical state is OFF, then updates only at next poll. Independent isolation checks pass; 239 status polls in 1200 seconds have no timeout, max 49.751 ms under recorded foreign load. No quiet-machine acceptance; unavailable before-test APIs and proof-gate failures remain gaps. Report: F:/agent-sandbox/design-b6d536/codex/verify-status2/report.md"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VC-status9-20261014",
  "verdict": "accept",
  "digest": "sha256:1d79995a6c3745d47237c11cdbae7b6dcf1632111c71f65e44cb9626f6bcf30d",
  "note": "VC-status9 (independent Claude verifier) on b6d536/status-timeout-2 vs 98cad099f: accepted ce7685a1c (b82: settings recovery null priorSettings fixed, 8 failures gone; data-age staleness, live 20-min E2 scripted-agent load 0 at-rest/GPU flips vs 92/106 on the base, 10871 canonical and 1186 fresh reads with 0 timeouts; ordered publication under an injected barrier; hung UI status read no longer stops polling; nothing blocks under the publication lock) and the records commits 19f230c5f, 41554dac9 and 0f9e824b2 (docs state staleAfterMs = 3 x sampler period and lanes stale after 3 of their own cadences with consequences a and b; Adaptations name the right decisions; compat patches add only PROBE_ALLOWANCE_PERIODS; before-failures rerun at f6d579a86, ff0c00f5e and 93479e944 match the records; behaviour texts corrected to the current code). Decided trade-offs: a hung lane is stale only after 3 of its cadences (30-120 s idle); a fresh read missing its deadline shows the last published value as current; a server always slower than 10 s shows empty status. Unverified: none beyond the named flaky StatusIsolationHttpTest under full CPU load (6/30 on both). Digest bound with --base 98cad099f, the lane's base."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4c-20261015",
  "verdict": "accept",
  "digest": "sha256:6170edc5da6a2a46f8a39728a2b1d417427b6a15b81a6476b2a23adf6fa1e446",
  "note": "Reviewed corrected sources and executor-constructor compatibility; both candidate check files pass and f6d579a86 reproduces the declared assertion failures with its add-only patch. The sampler and settings adaptations retain their existing assertions."
}
```
