---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b53-status-isolation-builder-20261010
---

The before-state is ff0c00f5e. The copied independent verifier assertions are retained, with additional per-owner and post-action cases. Script-produced execution evidence binds the exact check sources to candidate execution and assertion failures before. Existing b50 acceptance is not rewritten. Independent intent acceptance remains pending.

Refreshed after b82 (run b82g-status-records). Entry 1's evidence was re-run on the current checks, with the fail-before again at ff0c00f5e:

- Two of its checks changed in b82 because of b82's decision (a): a held lane is now stale by its own age, not by a refresh that has been in flight for 1 s. Entry 5 records this adaptation.
- pollingLifecycle.test.ts changed in b82 for b82's S3: a status read left unanswered for a whole poll interval is abandoned. Its status variants now hold the slow read for one millisecond less than the interval instead of 30 s. That is neither decision (a) nor (b), and its assertions are unchanged. Entry 6 records this adaptation.
- StatusIsolationHttpTest's frontend case also records the Node child's start time and refuses cleanup when it changed (ea0c311cb). Its assertions are unchanged.

The current checks call StatusLaneAging, a b82 test helper, so the fail-before overlays it. StatusLaneAging reads StatusLifecycleHandler.PROBE_ALLOWANCE_PERIODS, which ff0c00f5e lacks. The compat before-patch gates/test-intent/evidence/b53-status-isolation-compat.patch adds only that constant, and no production code in that state reads it. The overlay modules/ui-web/node_modules/.before-run-private-install is an empty marker file. It gives the before worktree a real node_modules directory, so the evidence script links none. The before worktree's frontend packages came from a private install in its parent directory. That install must also include the repository root's packages: StatusIsolationHttpTest's frontend case loads esbuild, which only the root package provides, so esbuild 0.28.2 (the root lock's version) was added to it.

Phase B (integration base 181363e86, merge 98fd416d0): entry 1's evidence was re-run on the current checks, again at ff0c00f5e. Since the last run, StatusIsolationHttpTest gained `overlappingStatusPreservesAdmissionCapacityRefusal` (cbe6650b5). That test uses `EngineAdmissionService.StatusReservation` and `reserveStatusAdmission`, which arrive with 971b55573, the repair commit directly after ff0c00f5e. A second compat before-patch, gates/test-intent/evidence/b53-status-isolation-admission-compat.patch, adds only that interface and a default `reserveStatusAdmission` that throws; no production code at ff0c00f5e calls it. The first patch is unchanged. The private install in the TMP folder's parent chain held ff0c00f5e's modules/ui-web lock plus esbuild 0.28.2. All three checks pass on the candidate and fail before by assertion. None of the recorded before failures reaches the stub: they are the frontend refresh, blocked disk probe, boot status and failed observation cases, and the post-action `?fresh=true` read.

Phase B verifier repair: entry 4's evidence (b53-status-provenance.json) now has a before-state run at ff0c00f5e. The current check calls `buildReadinessEnvelope(..., false)`, the direct, non-cached mode. That overload does not exist at ff0c00f5e, where the direct mode is the only one and takes three arguments. The compat before-patch gates/test-intent/evidence/b53-status-provenance-compat.patch adds only that four-argument overload. It calls the three-argument method unchanged for `false` and throws for `true`. No production code at ff0c00f5e calls it, so the outcome is the before-state's own envelope building. The check passes on the candidate and also passes at ff0c00f5e: the bounded observation-timestamp oracle and its stale, freshness and lost-contact assertions already held there. It is preservation evidence, not a repair claim.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui-web/src/shell-v0/utils/pollingLifecycle.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Each status producer publishes independently through real loopback HTTP with production StatusRoutes and security/admission filters. A held or failed producer only invalidates its own status fields; Worker contact and head-local AI stay current. Idle GPU stays fresh through two ten-second periods. A newer healthy Worker observation survives an older pending status-owned failure publication, while external component transitions remain protected by the full-registry conditional update. The real health monitor shortens an already scheduled idle tick when asynchronous reconciliation adopts the busy cadence, before healthy observations expire. The operating response uses the same current component reason and actual assembly time as the top-level response, while preserving measurement timestamps and monotonic content sequences without the sampler lock. Overlapping status occupies and releases its fairness slot and is refused at capacity. Post-action refresh requests fresh=true and observes the changed owner while periodic reconciliation is occupied. Deadlines, coalescing, freeze refusals, recovery and status contents are preserved.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Staleness for each component follows from that component's own sampling period, not a shared constant.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b53 builder brief, task item 5)'; the text does not appear in the owner's turns of session 886833dd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Each status component is produced and published independently, so a slow or failed producer marks only its own component stale or unknown and every other component stays current.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b53 builder brief, task item 5)'; the text does not appear in the owner's turns of session 886833dd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "After a user action, the UI's refresh shows the post-action state. Do not raise the client timeout, add retries or reintroduce blocking.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b53 builder brief, task item 5)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b53-status-isolation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java",
        "modules/ui-web/src/shell-v0/utils/pollingLifecycle.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexReadinessPublicationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The coalescing check now observes the actual independent Worker lane and its publication instead of requiring a second sampler thread to contend on the removed sampler-wide RPC lock. It waits for both contact and lifecycle publication before checking recovery. Original contact counts, component states, refusal, conditional-publication ownership, recovery and cached-read assertions remain.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Preservation: b50's own non-blocking checks, specC status-surface checks, lane F status consumers, frontend status tests.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b53 builder brief, task item 5)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingGpuProjectionTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleSemanticStuckTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "GPU projection checks pass the same existing six-second window explicitly after the producer now receives its own cadence. Readiness checks explicitly select the same direct-observation mode they previously selected implicitly; production selects cached inputs through the same envelope assembler. Redundant test-only wrapper methods are removed. These three files retain every prior assertion, fixture value, contact provenance and expected reason. Original suites execute on ff0c00f5e; adapted suites execute on the candidate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Staleness for each component follows from that component's own sampling period, not a shared constant.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b53 builder brief, task item 5)'; the text does not appear in the owner's turns of session 886833dd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Preservation: b50's own non-blocking checks, specC status-surface checks, lane F status consumers, frontend status tests.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b53 builder brief, task item 5)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ]
    },
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/StatusReadinessStalenessTest.java"],
      "class": "New",
      "repaired": false,
      "behaviour": "Independent component publications retain their own real observation timestamps. Two real-sampler checks replace shared response-timestamp equality with present observation timestamps bounded by sampling start and response return. Stale flags, zero fresh staleness, and lost-contact provenance assertions remain. Direct envelope calls explicitly retain their former mode. The original common-timestamp oracle passes on ff0c00f5e but is deliberately replaced because independent publications occur at different times; candidate observations are never restamped to imitate a shared pass.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Each status component is produced and published independently, so a slow or failed producer marks only its own component stale or unknown and every other component stays current.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b53 builder brief, task item 5)'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b53-status-provenance.json",
      "newChecks": ["modules/ui/src/test/java/io/justsearch/ui/api/StatusReadinessStalenessTest.java"]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "b82 decision (a): a hung lane is reported stale only after 3 of its own update intervals, so detection is later when idle, about 30 to 120 s. This is accepted as the cost of no longer flipping current values to unknown under load. Under the old rule, a refresh in flight for more than 1 s made its lane stale. So StatusIsolationHttpTest's blocked-disk and hung-worker cases used to sleep 1.2 s before asserting. They now advance the handler clock beyond the capped idle limit and run one bounded sampler pass (StatusLaneAging.ageHeldLanes). StatusNonBlockingHttpTest's blocked-dependency case used to advance its clock 1001 ms for non-lock sources. It now advances 31001 ms for every source, then runs one bounded sampler pass for non-lock sources. Each case still holds the same producer. It asserts the same stale or unknown marker on exactly that part, the same current values on every other part, the same bounded response time and the same recovery."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/utils/pollingLifecycle.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "b82 decision S3: a status read that never answers no longer stops polling; it is abandoned after one poll interval and polling continues. The status variants used to hold a slow read pending for 30 s, three times the 10 s status poll interval, which S3 now abandons. They now hold it for one millisecond less than the interval (STATUS_SLOW_MS = 9999), at each of the four places that waited 30 s: the single-flight case, the obsolete JSON completion case (after resubscribe and after rebind) and both waits in the queued post-action read case. The inference variants keep the 30 s hold. Every assertion is unchanged: one read in flight while slow, polling resumes after completion, obsolete completions are ignored, and the queued post-action read is sent as /api/status?fresh=true and resolves refreshes only after it publishes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "how the UI keeps its status display current for task item 5 (S20). The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 5's completion condition: \"Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.\" (task request).",
          "choice": "Orchestrator decision in the b82 brief, S3: \"Repair so a read that does not settle cannot stop the polling schedule or leave the display stuck, without raising any timeout as a fix for server slowness, without more than one read in flight per kind beyond what the existing rules allow, and without read storms (an abandoned read is aborted, not left running in parallel indefinitely). Decide the bound from the existing poll interval and justify.\"",
          "label": "agent choice",
          "provenance": "delegationQuote was 'Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.' at 'task t-20261005-b6d536 request (task item 5)', which is item wording, not a delegation"
        }
      ]
    }
  ]
}
```

## Acceptance records

Pending independent verifier review; the builder cannot self-accept or delegate.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status2-codex-20261010",
  "verdict": "reject",
  "digest": "sha256:13ce217c9c6d22134dd2909b6a1583892586dddecdfeb10a0e3d6b1c2555e2a6",
  "note": "FAIL OWN-ACTION-CONSUMERS / S20: corrected real frontend check retains Online after committed disable plus its inference refresh; canonical status is OFF, intent false, no fresh canonical read. It converges at next poll after 4994 ms. Status isolation checks pass and loaded 20-minute E2 has 239/239 HTTP 200, zero status timeouts. Quiet-machine result unverified. Report: F:/agent-sandbox/design-b6d536/codex/verify-status2/report.md"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VC-status9-20261014",
  "verdict": "accept",
  "digest": "sha256:618933ab8711e90d8ba91a2e187fa63596854797795f32ab8fba8c707897941a",
  "note": "VC-status9 (independent Claude verifier) on b6d536/status-timeout-2 vs 98cad099f: accepted ce7685a1c (b82: settings recovery null priorSettings fixed, 8 failures gone; data-age staleness, live 20-min E2 scripted-agent load 0 at-rest/GPU flips vs 92/106 on the base, 10871 canonical and 1186 fresh reads with 0 timeouts; ordered publication under an injected barrier; hung UI status read no longer stops polling; nothing blocks under the publication lock) and the records commits 19f230c5f, 41554dac9 and 0f9e824b2 (docs state staleAfterMs = 3 x sampler period and lanes stale after 3 of their own cadences with consequences a and b; Adaptations name the right decisions; compat patches add only PROBE_ALLOWANCE_PERIODS; before-failures rerun at f6d579a86, ff0c00f5e and 93479e944 match the records; behaviour texts corrected to the current code). Decided trade-offs: a hung lane is stale only after 3 of its cadences (30-120 s idle); a fresh read missing its deadline shows the last published value as current; a server always slower than 10 s shows empty status. Unverified: none beyond the named flaky StatusIsolationHttpTest under full CPU load (6/30 on both). Digest bound with --base 98cad099f, the lane's base."
}
```
