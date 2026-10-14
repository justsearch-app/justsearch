---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b73-status-state-fields
---

The status-change counter advances on state, never on measurement; fresh canonical reads are bounded; bodies without metadata are ordered by request issue. Independent acceptance remains pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/StatusStateProducersTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Every canonical status field and owner publication is classified as state, measurement or time, and the completeness check fails for an unclassified field or producer. Over real HTTP, GPU utilisation, VRAM, history arrays, telemetry throughput and its HEALTHY/UNKNOWN window, counts and clocks never advance the counter, while a state change still does; a failed 422 action after measurement-only changes carries no counter above the displayed reflection. Indexing-job counts and watched-root timestamps are not state.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "classify every canonical status field as state, measurement or time; measurements, gauges, rates, history arrays, clocks and ages never advance the counter",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b73 builder brief, Fix 1 / S20)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b73-status-state-fields-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#everyReportedStatusFieldIsClassified",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#completenessCheckFailsForAnUnclassifiedFieldOrProducer",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#classificationHasNoEntryForAFieldThatNoLongerExists",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#measurementsAndClocksAreNotComparedButStatesAre",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#everyOwnerPublicationIsAClassifiedProducer",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#idleCounterIgnoresMeasurementChanges",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java#failedActionWithMeasurementOnlyDiffCarriesNoNewerCounter",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/StatusStateProducersTest.java#indexingJobCountsWithinTheSameStatesDoNotAdvanceTheCounter",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/StatusStateProducersTest.java#watchedRootTimestampsDoNotAdvanceTheCounter"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/api/statusStorm.test.ts",
        "modules/ui-web/src/api/statusCounterSignal.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "At most one fresh canonical read is in flight and at most one is started per observed counter value; a held lane cannot turn ordinary requests into fresh reads; refused or failed fresh reads back off, honouring Retry-After, while ordinary polling continues and the display catches up at the next successful read. A body without metadata may replace the display only when it answers a read issued after the displayed body and no newer epoch contradicts it, so the UI converges within the poll interval when the server supplies no metadata. The round-6 verifier cases (hung storm, refused storm, metadata loss, out-of-order, epoch invalidation, idle) are copied with their assertions kept. statusCounterSignal's 'a later request may retry the pending publication' expectation is replaced: the same value is not read again, a later value is.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "at most one fresh read in flight; at most one fresh read per observed counter value (a fresh read that returns without reaching that value does not start another for the same value)",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b73 builder brief, Fix 3 / S20)'; the text does not appear in the owner's turns of session 886833dd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "a body without metadata may replace the display only if it answers a request issued after the displayed body was received and no newer epoch or revision is known to contradict it",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b73 builder brief, Fix 2 / S20)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b73-status-state-fields-ui.json",
      "newChecks": [
        "modules/ui-web/src/api/statusStorm.test.ts",
        "modules/ui-web/src/api/statusCounterSignal.test.ts"
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
  "session": "verify-status7-independent",
  "verdict": "reject",
  "digest": "sha256:a66c7bc5c36424c6619eb5e3a2826a77601abefd19d87ee496e136207f2800ff",
  "note": "Independent verify-status7 on 6f177f241: FAIL S20 / OWN7-BACKOFF. On the built UI against the running candidate head, after 40 s of refused fresh reads (6 refused, back-off works) and a successful ordinary poll, a lock by another client and, separately, the mounted UI's own successful lock action are not shown at the next render: 0 fresh reads, display unlocked while canonical is locked, caught up only 8-15 s later by ordinary polling. statusInvalidation.ts keeps backoffUntil (up to 30 s, Retry-After up to 300 s) until a successful signal-triggered fresh read; successful ordinary polls do not end it and due() gates every action and receipt signal on it. Unit form fails on 6f177f241 and passes on 823c82104. Otherwise: builder Java checks fail on 823c82104 by assertion and pass; classification walk finds 0 unclassified of 48 types; measurement-only changes no longer advance the counter (own Java, live idle 595 s with 0 advances, live chat-load window where every advance carried a state change); one fresh read per counter value; lock/unlock, failed, read-only, replay, approval, chat, GPU, indexing, raw settings, second tab, restart and full-response checks pass. The changed statusCounterSignal expectation is justified by the one-read-per-value rule; a later request still reads a later value and ordinary polls catch up, so a change is delayed by at most one poll, not lost."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status8-independent",
  "verdict": "accept",
  "digest": "sha256:519944c1886a080eb1abb7d861706864e64b20deb58055da54a1545473137e5f",
  "note": "Independent verify-status8 on 2fd48e172: PASS. S20 back-off repaired: any successful status read ends the back-off; live, after 40 s of refused fresh reads and a successful poll, another client's lock and the mounted UI's own lock made 2 s later show at the next render (fail on ff5bb2ae9). Builder statusBackoff cases fail on ff5bb2ae9 by assertion (5/10) and pass (10/10). ui suite completes at the 384m default heap (1731 tests, 0 failures; ff5bb2ae9 runs out of heap after 1210); narrowed producer import finds 87/87 counter call sites of a full import. About one refused fresh read per poll interval is bounded (live 7 in 40 s, 3 in 30 s after a restart); the previous bound-of-6 case assumed one clock (3 on a single fake clock); a lagging fresh read gets at most 3 ordinary follow-ups per value (unit and a live 59 s held Worker lane); an own action during back-off shows at the next successful poll; the statusCounterSignal change is justified. Lock/unlock, failed, read-only, replay, reattach, approval, chat, agent tool, GPU, indexing, raw settings, second tab, header stripping, restart, idle 10 min and full-response comparison with f6d579a86 pass; suites, frontend, architecture, lane F, regen, API contract and governance show no new failures. E2 driver window: 239/239 status reads, max 23 ms, 33.8 fresh reads/min, at most 3 per value. Noted, not caused by this candidate: a fresh read missing its 100 ms deadline publishes expired lanes (at-rest UNKNOWN, GPU unavailable) for 100-300 ms, so the UI's fresh reads generate some counter values under load (10 flips per 12 min E2-like; a loop when lanes are slowed). Report: F:/agent-sandbox/design-b6d536/codex/verify-status8/report.md"
}
```
