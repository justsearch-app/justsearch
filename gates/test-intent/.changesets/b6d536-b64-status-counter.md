---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b64-status-counter
---

Server-owned publication metadata replaces the completion inventory. Independent acceptance is pending.

Refreshed after b82 (run b82g-status-records). Entry 2's evidence was re-run on the current checks. That entry is not marked repaired, so it has a candidate run only and no fail-before. Both checks pass with the same cases as the first record: 11 and 5. statusOwner-extra.test.ts is unchanged. statusCounterSignal.test.ts changed once, in b73 (6f177f241), and not because of b82's decision (a) or (b). In its 'does not acknowledge a current header when the snapshot reflects an older revision' case, the expectation 'a later request may retry the pending publication' became: the same counter value is not read again, and a later value is. b73's changeset entry for statusStorm.test.ts and statusCounterSignal.test.ts records that replacement and its source. verify-status7 and verify-status8 each found the changed expectation justified. Entry 2's behaviour text said the client 'retries stale observations only on another request'. That described the replaced expectation, so the text was corrected after the refresh (b82g correction). This corrects the description of code that did not change; no check or evidence changed. statusCounterSignal.test.ts line 35 asserts the one-read-per-value part.

Records phase B (batch 3) re-ran `b64-status-counter-repaired.json` (entry 1) on b599d4d7e because its check files changed after the first run. All 13 checks pass on the change. The before ref stays 23b8679ee, the parent of the lane commit ea0c311cb that introduced the behaviour, with the TestActivationForwarders overlay. RuntimeReconcilerTest now builds RuntimeReconciler with the lease-free constructor that 5e4f4e698 left, so the fail-before also applies the compat before-patch `gates/test-intent/evidence/b64-status-counter-repaired-compat.patch`. It adds only that constructor, which delegates to 23b8679ee's own Engine constructor with a fresh RuntimeGpuLease, as ServicePhase builds it. No 23b8679ee code calls it, and it supplies no status counter behaviour. All 13 checks again fail by assertion there.

Records phase B (batch 3), verifier repair. The phase B verifier rejected entry 2 because `b64-status-signal.json` has no before-state and the entry gave no reason. Entry 2 now states it: both checks exercise the server-metadata functions ea0c311cb added to statusInvalidation, which do not exist at 23b8679ee. The evidence is unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/DefaultEngineComponentRegistryTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/TransitionRunnerTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/PlacementStageTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuProfileResolverTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/power/EnergyStatePollerTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsCommitCoordinatorTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteIndexingJobsBridgeTest.java",
        "modules/core/src/test/java/io/justsearch/core/scheduling/BulkWorkPolicyTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WatchedRootsStatusCounterTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusChangeCounterHttpTest.java",
        "modules/ui-web/src/api/statusOwner-raw-settings.test.ts",
        "modules/ui-web/src/api/statusOwner-reattach.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Successful status-relevant owner publications advance a shared counter; reads, identical publications, settings replay and timestamp-only install rewrites do not. Chat loading and completion each signal. Every API response reports the counter without sampling, canonical reads report a conservative reflected revision. Raw plugin settings writes refresh status; read-only reattachment does not. Original verifier assertions are retained.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "the UI must learn that canonical state changed from the server that owns the state, not from client-side guesses about which requests or operation names change it",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b64 builder role contract, task item 5 / S20)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b64-status-counter-repaired.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/DefaultEngineComponentRegistryTest.java#statusCounterTracksPublicationAndNotSnapshotOrNoop",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/TransitionRunnerTest.java#statusCounterTracksBothChatTransitionEdges",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/PlacementStageTest.java#statusCounterTracksInstalledDistributionAndNotTimestampOnlyRewrite",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuProfileResolverTest.java#statusCounterTracksGpuProfileAndNotReadsOrIdenticalPlan",
        "modules/app-services/src/test/java/io/justsearch/app/services/power/EnergyStatePollerTest.java#energyStatusChangesAdvanceCounterButReadsAndRepeatedPollsDoNot",
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerTest.java#statusCounterTracksActivationProcedureAndNotStatusRead",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsCommitCoordinatorTest.java#statusCounterTracksSettingsCommitAndNotExactReplay",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteIndexingJobsBridgeTest.java#statusCounterTracksJobsAndNotReadOnlyReattachment",
        "modules/core/src/test/java/io/justsearch/core/scheduling/BulkWorkPolicyTest.java#statusCounterTracksVerdictChangesAndNotRepeatedInputs",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WatchedRootsStatusCounterTest.java#statusCounterTracksRootsAndNotDuplicateRemoval",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusChangeCounterHttpTest.java#statusCounterResponseReflectsACompletedSampleAndNeverSamplesForMetadata",
        "modules/ui-web/src/api/statusOwner-raw-settings.test.ts",
        "modules/ui-web/src/api/statusOwner-reattach.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/api/statusCounterSignal.test.ts",
        "modules/ui-web/src/api/statusOwner-extra.test.ts"
      ],
      "class": "New",
      "behaviour": "Additive server metadata coalesces bursts. It makes one fresh read per observed value. A value still unreflected after that read is followed up only by ordinary reads started by later signals, at most 3; a later value gets one fresh read. It isolates head epochs and preserves caller bodies. Read-only approvals and replayed completions with unchanged server counters cause no fresh read. Failed responses can convey a different client's completed publication. The copied verifier approval/replay assertions remain unchanged. Before-state not reproducible on 23b8679ee (parent of ea0c311cb, which added both checks): new API. Both files import observeStatusResponse, observeStatusEvent and statusSnapshotDisplayed from statusInvalidation, and statusCounterSignal.test.ts also observeStatusSignal and statusSnapshotCanDisplay; 23b8679ee's statusInvalidation exports only bindStatusRefresh, statusChanged, createStatusExecutionObserver and statusWriteCompleted, the completion inventory this change replaced. 23b8679ee had no server publication metadata to observe, so stand-ins for these functions would decide the assertions themselves.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "the UI must learn that canonical state changed from the server that owns the state, not from client-side guesses about which requests or operation names change it",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b64 builder role contract, task item 5 / S20)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b64-status-signal.json",
      "newChecks": [
        "modules/ui-web/src/api/statusCounterSignal.test.ts",
        "modules/ui-web/src/api/statusOwner-extra.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/api/statusCompletion.test.ts",
        "modules/ui-web/src/api/statusInvalidation.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.after-action.test.ts",
        "modules/ui/src/test/java/io/justsearch/ui/api/LocalApiCorsPolicyTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/OpenAiCompatControllerTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "Preserved b59/b61 fixtures now supply the server metadata carried by real transports; the Brain transport double invokes the same generic metadata reader as authorizedFetch. The old action/render assertions remain. CORS exact expectations include the three documented additive exposed headers. StatusIsolation child cleanup captures creation time at start and verifies it before stopping. The cross-client case is an additional assertion, with assertion failure on 23b8679ee and pass recorded in before-cross-client.log / cross-client.log. Build cleanup removes redundant qualification in StatusIsolationHttpTest, retaining original inputs, resource lifetimes and every existing assertion. CI759 round 4 joins the curried it.each call continuations in statusCompletion.test.ts and statusInvalidation.test.ts to satisfy frontend lint. The same 55 cases execute before and after; their parameter rows, callbacks and assertions remain unchanged. Their directly covering b59 and b61 entries regenerate execution evidence with their original before-states and copied vitest dependencies.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "the UI must learn that canonical state changed from the server that owns the state, not from client-side guesses about which requests or operation names change it",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b64 builder role contract, task item 5 / S20)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ]
    }
  ]
}
```

## Acceptance records

Pending independent verifier judgment; the builder does not accept its own change.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status5-independent",
  "verdict": "reject",
  "digest": "sha256:d8f61542bfcf8eac4396504390fc1c8b6006515a04de4e653303def9e1b2f7c3",
  "note": "Independent ea0c311cb: FAIL S20 / OWN-ENCRYPTION-OWNER on built UI and native head: external encryption lock completes 200, next read-only UI request and render still show unlocked, canonical fresh status is locked, zero fresh reads. OWN-RESTART-MISSING: controlled new epoch plus refused canonical read and an older response without metadata restores Online/READY while native canonical state is OFF. Real-HTTP sampler stale/recovery counter-publication assertions also fail. Evidence and preservation/timing limits: F:/agent-sandbox/design-b6d536/codex/verify-status5/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status6-independent",
  "verdict": "reject",
  "digest": "sha256:d8f61542bfcf8eac4396504390fc1c8b6006515a04de4e653303def9e1b2f7c3",
  "note": "Candidate 6377d0030 changes what advances this counter (structural backstop over the whole body) and where the client observes it (every response including refusals), so this record's evidence no longer describes the running mechanism and is invalidated. Independent candidate 6377d0030: FAIL. (1) StatusPublicationBackstop counts measurement noise: live idle head advanced the counter 51 times in 10 min (GPU utilisation 0<->1, VRAM, recent* arrays, telemetry window state); the idle mounted UI made 37 fresh reads and a failed 422 action triggered a fresh read and snapshot replacement in 3 of 4 runs. (2) Metadata loss: UI kept 'unlocked' for 12 polls while canonical was locked (live, header-stripping proxy; module case fails, passes on 93479e944). (3) After a real head restart a 1000-request UI burst produced 392 refused fresh reads in 3.6 s, contradicting 'refused fresh reads do not create a loop'. Passing: lock/unlock, raw settings, second tab, approval, agent tool, chat, GPU/index, replay/reattach, restart display, full suites, architecture, regen-all, API contract, lane F. Report: F:/agent-sandbox/design-b6d536/codex/verify-status6/report.md"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status7-independent",
  "verdict": "reject",
  "digest": "sha256:241d3b09dd551375b313d6829ae962f7acc44e4efc618db818b7ebdeb9498e03",
  "note": "Candidate 6f177f241 changes statusCounterSignal.test.ts (entry 2's new check), so entry 2's evidence is invalid (governance: evidence-invalid) and statusOwner-extra.test.ts becomes uncovered; the counter now compares classified state only, so entry 1 no longer describes what advances it. Independent verify-status7 on 6f177f241: FAIL S20 / OWN7-BACKOFF. On the built UI against the running candidate head, after 40 s of refused fresh reads (6 refused, back-off works) and a successful ordinary poll, a lock by another client and, separately, the mounted UI's own successful lock action are not shown at the next render: 0 fresh reads, display unlocked while canonical is locked, caught up only 8-15 s later by ordinary polling. statusInvalidation.ts keeps backoffUntil (up to 30 s, Retry-After up to 300 s) until a successful signal-triggered fresh read; successful ordinary polls do not end it and due() gates every action and receipt signal on it. Unit form fails on 6f177f241 and passes on 823c82104."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status8-independent",
  "verdict": "accept",
  "digest": "sha256:241d3b09dd551375b313d6829ae962f7acc44e4efc618db818b7ebdeb9498e03",
  "note": "Independent verify-status8 on 2fd48e172: PASS. S20 back-off repaired: any successful status read ends the back-off; live, after 40 s of refused fresh reads and a successful poll, another client's lock and the mounted UI's own lock made 2 s later show at the next render (fail on ff5bb2ae9). Builder statusBackoff cases fail on ff5bb2ae9 by assertion (5/10) and pass (10/10). ui suite completes at the 384m default heap (1731 tests, 0 failures; ff5bb2ae9 runs out of heap after 1210); narrowed producer import finds 87/87 counter call sites of a full import. About one refused fresh read per poll interval is bounded (live 7 in 40 s, 3 in 30 s after a restart); the previous bound-of-6 case assumed one clock (3 on a single fake clock); a lagging fresh read gets at most 3 ordinary follow-ups per value (unit and a live 59 s held Worker lane); an own action during back-off shows at the next successful poll; the statusCounterSignal change is justified. Lock/unlock, failed, read-only, replay, reattach, approval, chat, agent tool, GPU, indexing, raw settings, second tab, header stripping, restart, idle 10 min and full-response comparison with f6d579a86 pass; suites, frontend, architecture, lane F, regen, API contract and governance show no new failures. E2 driver window: 239/239 status reads, max 23 ms, 33.8 fresh reads/min, at most 3 per value. Noted, not caused by this candidate: a fresh read missing its 100 ms deadline publishes expired lanes (at-rest UNKNOWN, GPU unavailable) for 100-300 ms, so the UI's fresh reads generate some counter values under load (10 flips per 12 min E2-like; a loop when lanes are slowed). Report: F:/agent-sandbox/design-b6d536/codex/verify-status8/report.md"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VC-status9-20261014",
  "verdict": "accept",
  "digest": "sha256:147044af6f18d59e6dc7cfd83a5d2f3cf03bccf78b16a9ed539cf94161cbbc53",
  "note": "VC-status9 (independent Claude verifier) on b6d536/status-timeout-2 vs 98cad099f: accepted ce7685a1c (b82: settings recovery null priorSettings fixed, 8 failures gone; data-age staleness, live 20-min E2 scripted-agent load 0 at-rest/GPU flips vs 92/106 on the base, 10871 canonical and 1186 fresh reads with 0 timeouts; ordered publication under an injected barrier; hung UI status read no longer stops polling; nothing blocks under the publication lock) and the records commits 19f230c5f, 41554dac9 and 0f9e824b2 (docs state staleAfterMs = 3 x sampler period and lanes stale after 3 of their own cadences with consequences a and b; Adaptations name the right decisions; compat patches add only PROBE_ALLOWANCE_PERIODS; before-failures rerun at f6d579a86, ff0c00f5e and 93479e944 match the records; behaviour texts corrected to the current code). Decided trade-offs: a hung lane is stale only after 3 of its cadences (30-120 s idle); a fresh read missing its deadline shows the last published value as current; a server always slower than 10 s shows empty status. Unverified: none beyond the named flaky StatusIsolationHttpTest under full CPU load (6/30 on both). Digest bound with --base 98cad099f, the lane's base."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS3-20261015",
  "verdict": "accept",
  "digest": "sha256:b95cdf7ea305d12e0e1fccfbbd5f3114e078bb19c33e7076f008742cbaa49cc4",
  "note": "Reviewed the changed fixtures/evidence and the corrected candidate-only server-metadata API-absence reason. Current selected checks pass; today's independent reruns match all 13 repaired before outcomes and the reason-only rereview matches current content."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4c-20261015",
  "verdict": "accept",
  "digest": "sha256:ad280cadc51ac4b88795ae3143bef90b994b33e3aa88156f7f1b20181b38bc03",
  "note": "Reviewed refreshed repaired evidence and the unrelated settings method change; all 13 selected candidate checks pass and the declared 23b8679ee before rerun reproduces its recorded classifications. The unchanged metadata-only entry retains its explicit absent-before-API reason."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS5a-20261016",
  "verdict": "accept",
  "digest": "sha256:96df05581b25a02153fed16d8262b102c6414c2134059f5a8a56a4808b4a6593",
  "note": "Re-bound to origin/main 298354254 after reviewing the shared HTTP qualification cleanup and retained counter oracles. All listed candidate checks pass and the 13 declared 23b8679ee before outcomes reproduce with its additive constructor patch and fixture overlay."
}
```
