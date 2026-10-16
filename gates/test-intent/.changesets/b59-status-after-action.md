---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b59-status-after-action
---

The actual Brain handlers use the production canonical status poller. All four repaired cases fail by assertion on 1939ad02c. The operation spy retains execution, unlike a stubbed successful operation. Transport tests cover completion receipts, concurrent batching and real admission waiting. Independent acceptance is pending.

CI759 round 4 joins the curried `it.each` registration in statusInvalidation.test.ts onto one
line to satisfy frontend lint. All 32 cases pass both before and after this formatting change;
parameter rows, callbacks and assertions are unchanged. Only its directly covering evidence
`b59-status-after-action-new.json` is regenerated, with the declared before-state `1939ad02c`, no
overlay or patch, and ordinary copied vitest dependencies (`--copy-node-modules`). The Brain
check and its evidence are unchanged. This refresh records execution, not acceptance.

Review history (records repair, 2026-10-14). On the previous content (digest sha256:a969f2e8...),
verify-status5, -6 and -7 rejected and verify-status8 accepted; the gate reported that as a
disagreement needing a tie-break. The rejections concerned product behaviour on earlier candidates
(ea0c311cb, 6377d0030, 6f177f241): native lock completion, counter advances from measurement noise,
rollback when status metadata is missing, refused fresh-read bursts after a restart, and back-off
after refused fresh reads. verify-status8 reports those cases passing on 2fd48e172 and notes one
residual it did not attribute to that candidate (expired lanes published under load, taken up by
b82). This repair changed only the sources (orchestrator wording no longer cited as owner words), so
every earlier record is stale and the disagreement is not decided here. The next acceptor reviews the
current content.

Records phase B (batch 3) re-ran both evidence files on b599d4d7e because their check files changed
after the first runs. `b59-status-after-action-new.json` passes (32 cases). For
`b59-status-after-action-repaired.json`, BrainSurface.after-action.test.ts now imports
../../api/statusInvalidation.js, which 1939ad02c lacks, so the fail-before applies the compat
before-patch `gates/test-intent/evidence/b59-status-after-action-compat.patch`. It adds only
modules/ui-web/src/api/statusInvalidation.ts with a no-op `observeStatusResponse()`; no 1939ad02c
code calls it. The four cases again fail by their after-action assertions on 1939ad02c.

Records phase B (batch 3), verifier repair. The phase B verifier rejected entry 2 because its evidence had no before-state and no reason. All its imports (http, settingsAttempt, authorizedFetch, OperationClient, chatResidency, statusPoll and the live status fixture) exist at 1939ad02c, the parent of the lane commit 5e877caed that added statusInvalidation.test.ts, so `b59-status-after-action-new.json` was re-run with `--before 1939ad02c` and no patch or overlay (throwaway copy of b599d4d7e; the copy also carried the candidate's regenerated status-response.ts type file, which has no runtime code). All 32 cases pass on the change. On 1939ad02c the check fails by assertion; a direct vitest run of the same file there counts 31 failing cases and 1 passing, the preservation case 'failed operations and read-only operations do not request fresh status'.

```json
{
  "entries": [
    {
      "items": ["modules/ui-web/src/shell-v0/views/BrainSurface.after-action.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "Successful chat disable and GPU settings completion refresh and publish canonical status before the next scheduled poll through the real action handlers and shared production poller. No locally assumed status is published. Chat capability and Health engine labels follow the canonical observation while the legacy inference poll is stale.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Once an action that changes status-surface state succeeds, every UI surface that shows that state reflects the post-action state without waiting for the scheduled poll, read from the same canonical status that dev tools and scripts read", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b59 builder role contract, task item 5 / S20)'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b59-status-after-action-repaired.json",
      "newChecks": ["modules/ui-web/src/shell-v0/views/BrainSurface.after-action.test.ts"]
    },
    {
      "items": ["modules/ui-web/src/api/statusInvalidation.test.ts"],
      "class": "New",
      "repaired": false,
      "behaviour": "The shared invalidation mechanism observes operation and undo receipts, validated settings COMPLETE, and direct runtime/indexing/lifecycle writes through production transports and canonical polling. Concurrent completions coalesce, 429 keeps the fresh read inside admission, and failed or read-only actions do not invalidate status. Before-state 1939ad02c (parent of 5e877caed, which added this check): 31 of the 32 cases fail by assertion (canonical status still READY where the action published OFF; and the admission case fails its expected-false assertion); the preservation case 'failed operations and read-only operations do not request fresh status' passes there and here. CI759 round 4 preserves the curried it.each call while joining its continuation to remove lint ambiguity; every parameter row, callback and assertion remains unchanged.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "a fresh read must not bypass admission or produce a storm (coalesce concurrent fresh requests)", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b59 builder role contract, preservation constraint)'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b59-status-after-action-new.json",
      "newChecks": ["modules/ui-web/src/api/statusInvalidation.test.ts"]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status3-independent",
  "verdict": "reject",
  "digest": "sha256:4f4e0d07998bc94a6dd09d1572d435d316ab894cdc51b157de86df90b0396fe5",
  "note": "FAIL S20 OWN-APPROVAL-COMPLETION: real approved pending execution returns Settings committed but next render remains Online/Shut Down AI while canonical status is OFF; no fresh read. Independent new checks pass 35/35 and fail before by assertion; full suites pass, 239/239 canonical E2 polls succeed under foreign load. Report F:/agent-sandbox/design-b6d536/codex/verify-status3/report.md; named unverified cases and E3 chunk-progress failure retained."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status4-independent",
  "verdict": "reject",
  "digest": "sha256:4f4e0d07998bc94a6dd09d1572d435d316ab894cdc51b157de86df90b0396fe5",
  "note": "Independent candidate 8fb8c7c4b: b59 completion remains incomplete for raw plugin settings POST; native COMPLETE receipt leaves UI Online/Shut Down AI while canonical status is OFF, with zero fresh reads. Ordinary actions and MCP approval pass, but all-path S20 fails. Evidence: F:/agent-sandbox/design-b6d536/codex/verify-status4/report.md. Qualified second E2 window: 239/239 status polls, max full-body 38.7 ms, at most one Gradle invocation; foreign load, no quiet-machine claim."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status5-independent",
  "verdict": "reject",
  "digest": "sha256:a969f2e86692b0e8ec83c607af7d7fd20ec6d2cd2aa4ca99a3836147bb12471a",
  "note": "Current ea0c311cb replaces/completes this action-refresh mechanism with owner publication metadata. Earlier completion-classifier evidence is revision-specific and is necessarily named as superseded. Exercised chat/GPU/indexing/approval actions now render canonical state, but S20 all-owner/all-path completion fails: native conversation locking leaves the mounted UI unlocked until polling, and absent metadata permits a rendered rollback after a refused canonical read. Historical judgments retained. Report: F:/agent-sandbox/design-b6d536/codex/verify-status5/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status6-independent",
  "verdict": "reject",
  "digest": "sha256:a969f2e86692b0e8ec83c607af7d7fd20ec6d2cd2aa4ca99a3836147bb12471a",
  "note": "Evidence invalid on the candidate (BrainSurface.after-action.test.ts and statusInvalidation.test.ts changed since their evidence runs); this record is invalidated. Live: failed/read-only action no longer leaves the UI unchanged. Independent candidate 6377d0030: FAIL. (1) StatusPublicationBackstop counts measurement noise: live idle head advanced the counter 51 times in 10 min (GPU utilisation 0<->1, VRAM, recent* arrays, telemetry window state); the idle mounted UI made 37 fresh reads and a failed 422 action triggered a fresh read and snapshot replacement in 3 of 4 runs. (2) Metadata loss: UI kept 'unlocked' for 12 polls while canonical was locked (live, header-stripping proxy; module case fails, passes on 93479e944). (3) After a real head restart a 1000-request UI burst produced 392 refused fresh reads in 3.6 s, contradicting 'refused fresh reads do not create a loop'. Passing: lock/unlock, raw settings, second tab, approval, agent tool, chat, GPU/index, replay/reattach, restart display, full suites, architecture, regen-all, API contract, lane F. Report: F:/agent-sandbox/design-b6d536/codex/verify-status6/report.md"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status7-independent",
  "verdict": "reject",
  "digest": "sha256:a969f2e86692b0e8ec83c607af7d7fd20ec6d2cd2aa4ca99a3836147bb12471a",
  "note": "Evidence already invalid before this candidate (BrainSurface.after-action and statusInvalidation tests changed since their runs). Live on 6f177f241: chat disable, GPU setting and reindex completions show at the next render in normal operation, but not while back-off from earlier refused fresh reads lasts. Independent verify-status7 on 6f177f241: FAIL S20 / OWN7-BACKOFF. On the built UI against the running candidate head, after 40 s of refused fresh reads (6 refused, back-off works) and a successful ordinary poll, a lock by another client and, separately, the mounted UI's own successful lock action are not shown at the next render: 0 fresh reads, display unlocked while canonical is locked, caught up only 8-15 s later by ordinary polling. statusInvalidation.ts keeps backoffUntil (up to 30 s, Retry-After up to 300 s) until a successful signal-triggered fresh read; successful ordinary polls do not end it and due() gates every action and receipt signal on it. Unit form fails on 6f177f241 and passes on 823c82104."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status8-independent",
  "verdict": "accept",
  "digest": "sha256:a969f2e86692b0e8ec83c607af7d7fd20ec6d2cd2aa4ca99a3836147bb12471a",
  "note": "Independent verify-status8 on 2fd48e172: PASS. S20 back-off repaired: any successful status read ends the back-off; live, after 40 s of refused fresh reads and a successful poll, another client's lock and the mounted UI's own lock made 2 s later show at the next render (fail on ff5bb2ae9). Builder statusBackoff cases fail on ff5bb2ae9 by assertion (5/10) and pass (10/10). ui suite completes at the 384m default heap (1731 tests, 0 failures; ff5bb2ae9 runs out of heap after 1210); narrowed producer import finds 87/87 counter call sites of a full import. About one refused fresh read per poll interval is bounded (live 7 in 40 s, 3 in 30 s after a restart); the previous bound-of-6 case assumed one clock (3 on a single fake clock); a lagging fresh read gets at most 3 ordinary follow-ups per value (unit and a live 59 s held Worker lane); an own action during back-off shows at the next successful poll; the statusCounterSignal change is justified. Lock/unlock, failed, read-only, replay, reattach, approval, chat, agent tool, GPU, indexing, raw settings, second tab, header stripping, restart, idle 10 min and full-response comparison with f6d579a86 pass; suites, frontend, architecture, lane F, regen, API contract and governance show no new failures. E2 driver window: 239/239 status reads, max 23 ms, 33.8 fresh reads/min, at most 3 per value. Noted, not caused by this candidate: a fresh read missing its 100 ms deadline publishes expired lanes (at-rest UNKNOWN, GPU unavailable) for 100-300 ms, so the UI's fresh reads generate some counter values under load (10 flips per 12 min E2-like; a loop when lanes are slowed). Report: F:/agent-sandbox/design-b6d536/codex/verify-status8/report.md"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4c-20261015",
  "verdict": "accept",
  "digest": "sha256:580deaaab020ee20cffd7d4afc31e44470550776dd3e1c21713dc0978f0695d2",
  "note": "Reviewed the real transport and polling paths and the add-only compatibility declaration that preserves the old transport behavior. Both current frontend files pass and the declared 1939ad02c before runs reproduce the missing canonical-status publication assertions."
}
```
