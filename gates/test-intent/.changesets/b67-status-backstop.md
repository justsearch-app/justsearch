---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b67-status-backstop
---

Canonical status owns publication completeness; independent acceptance remains pending.

Refreshed after b82 (run b82g-status-records). The evidence of entries 1 and 2 was re-run on the current checks. Entry 1's fail-before ran again at 93479e944 with its original overlays:

- The verifier-derived checks changed in b82 because of b82's decisions (a) and (b). Entry 3 records this adaptation.
- StatusPublicationCompletenessTest gained b82's knownUserVisibleStatesAlwaysSignal case, which b82's changeset covers, and an import tidy. Entry 1's two named methods are unchanged.
- statusBoundaryPoll.test.ts gained b82's S4 cases (headerlessStatusConvergesAfterSseEpoch), which b82's changeset covers. Its earlier cases are unchanged. Entry 1 checks the whole file, so its evidence now includes the S4 cases.

The current checks call StatusLaneAging, a b82 test helper, so the fail-before also overlays it. StatusLaneAging reads StatusLifecycleHandler.PROBE_ALLOWANCE_PERIODS, which 93479e944 lacks. The compat before-patch gates/test-intent/evidence/b67-status-backstop-compat.patch adds only that constant, and no production code in that state reads it. The overlay modules/ui-web/node_modules/.before-run-private-install is an empty marker file. It gives the before worktree a real node_modules directory, so the evidence script links none. The before worktree's frontend packages came from a private install in its parent directory.

Records phase B (batch 3) re-ran entry 2's evidence (`b67-status-backstop-preservation.json`) on b599d4d7e because VerifierStatusIsolationTest changed after that run. The entry has no fail-before; all four classes pass (9, 23, 10 and 14 cases).

Records phase B (batch 3), verifier repair. The phase B verifier rejected entry 2 because its evidence had no before-state and no reason. Coordinator decision: reclassify entry 2 as repaired only if the candidate passes repeatedly and every class fails at 93479e944 by the behaviour this change repairs. Candidate: the four classes ran 10 times on this change (Gradle --rerun, same selectors); all 10 runs passed every case (9, 23, 10 and 14). Before: 93479e944 with the StatusLaneAging overlay and `gates/test-intent/evidence/b67-status-backstop-compat.patch` (only the PROBE_ALLOWANCE_PERIODS constant, which no 93479e944 code reads), four direct runs (the three the decision asked for and one earlier) plus the evidence runner's run. Every class failed by assertion in every run. The only assertion that failed in every class and every run is overlappingStatusPreservesAdmissionCapacityRefusal, "a saturated work pool must not refuse a status read". That is b58's bounded status observation pool (9bf134aac), which cbe6650b5 (b75) wrote into the check after this change. The other failures varied between runs: blockedDiskProbeMustNotStaleHealthyWorkerOrAi, the hung-supplier cases of VerifierStatus2Test (worker staleness for 1 to 4 of the 11 hung suppliers, once "recovered inference clears only its stale state"), olderPendingFailureCannotOverrideQueuedHealthyConversation and [1] at-rest. These are lane isolation and staleness (b53, b82), not this change's publication completeness. The before failures are therefore unrelated to the behaviour this change repairs, so no class is reclassified. Entry 2 stays a New, candidate-only entry, and `b67-status-backstop-preservation.json` was re-run without a before-state (all four classes pass).

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus5Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusPublicationCompletenessTest.java",
        "modules/ui-web/src/api/statusBoundary.test.ts",
        "modules/ui-web/src/api/statusBoundaryPoll.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The full canonical status structure publishes missed owner changes and stale/recovered transitions, excluding clocks and ages. Failed reads carry publication metadata; cached polls with unreflected changes refresh once; restart and missing metadata cannot restore older current state. Refused fresh reads do not create a loop. Copied verifier assertions are retained. Build cleanup documents the baseline missing-header catches and removes trailing blank lines in VerifierStatus5Test, retaining original inputs, resource lifetimes and every existing assertion.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "metadata on any response counts, successful or not; a body is never shown as current unless it is known to be at least as new as what is displayed",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b67 builder brief, task item 5 / S20)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-build-green-93479e944-43d5c70cef.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus5Test.java#realEncryptionOwnerPublishesItsCanonicalStateChange",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus5Test.java#samplerFailureAndRecoveryPublishCanonicalFreshnessChanges",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusPublicationCompletenessTest.java#everyStatusBearingFieldAndFutureProducerHasAPublicationPath",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusPublicationCompletenessTest.java#producerWithoutOwnerPublicationStillSignalsCanonicalChange",
        "modules/ui-web/src/api/statusBoundary.test.ts",
        "modules/ui-web/src/api/statusBoundaryPoll.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus3Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus4Test.java"
      ],
      "class": "New",
      "behaviour": "Retain the independent verifier real-HTTP isolation, admission, owner, projection and provenance checks as preservation evidence. Their assertions remain unchanged; the fixture temporary directory uses the test runtime directory. Candidate-only. Before-state not reproducible as evidence for this change: at 93479e944 (parent of 6377d0030, the lane commit that added these four classes) every class fails by assertion, but the one assertion that fails in every class and run is overlappingStatusPreservesAdmissionCapacityRefusal, \"a saturated work pool must not refuse a status read\": the bounded status observation pool of b58 (9bf134aac), adapted into this check after this change by cbe6650b5. It is not the publication completeness this change repairs. The other failing cases (worker and lane staleness, at-rest, queued conversation producer) vary with timing at 93479e944. See Records phase B (batch 3), verifier repair. Build cleanup removes trailing blank lines in VerifierStatus2Test, VerifierStatus3Test, VerifierStatus4Test, VerifierStatusIsolationTest, retaining original inputs, resource lifetimes and every existing assertion.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Preservation: every check added by ea0c311cb, b53's StatusIsolationHttpTest and admission checks, the verifier's 65 own preservation cases where reusable",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b67 builder brief, Checks)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-build-green-candidate-b3b4c08bac.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus3Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus4Test.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus3Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus4Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus5Test.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Before each stale or unknown assertion on a held lane, the cases now age that lane by its own clock with StatusLaneAging.ageHeldLanes: they advance the handler clock beyond the capped idle limit and run one bounded sampler pass. Two b82 decisions caused this. Decision (a): a hung lane is reported stale only after 3 of its own update intervals, so detection is later when idle, about 30 to 120 s. This is accepted as the cost of no longer flipping current values to unknown under load. Under the old rule a refresh in flight for more than 1 s made its lane stale, so these cases used to sleep 1.2 s: VerifierStatusIsolationTest's blocked-disk and hung-worker cases, VerifierStatus2Test's per-owner case, VerifierStatus4Test's additional-owner case and VerifierStatus5Test's samplerFailureAndRecoveryPublishCanonicalFreshnessChanges. Decision (b): when a fresh read misses its deadline, it shows the last published value labelled current, rather than unknown. So right after an action it can briefly show the pre-action value. These cases used to observe the held lane as stale or unknown right after a fresh read had missed its deadline, and they now age it first: VerifierStatus3Test's persistent hung worker, VerifierStatus4Test's older pending failure (conversation protection unknown) and VerifierStatus5Test's unknown unrelated owner. Each case still holds the same producer. It asserts the same stale or unknown marker on exactly that part, the same current values on every other part, the same counter and reflected-revision relations and the same recovery. Build cleanup removes trailing blank lines in VerifierStatus2Test, VerifierStatus3Test, VerifierStatus4Test, VerifierStatusIsolationTest; documents the baseline missing-header catches and removes trailing blank lines in VerifierStatus5Test, retaining original inputs, resource lifetimes and every existing assertion."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status6-independent",
  "verdict": "reject",
  "digest": "sha256:f8d5d758193c3a5fb4ee3d532dfcb98b8c4a18aa2f59315713b496bb180f7c81",
  "note": "Builder checks fail on 93479e944 by assertion and pass on the candidate, but they do not exercise measurement-only producer changes, missing metadata or refused-read storms. Independent candidate 6377d0030: FAIL. (1) StatusPublicationBackstop counts measurement noise: live idle head advanced the counter 51 times in 10 min (GPU utilisation 0<->1, VRAM, recent* arrays, telemetry window state); the idle mounted UI made 37 fresh reads and a failed 422 action triggered a fresh read and snapshot replacement in 3 of 4 runs. (2) Metadata loss: UI kept 'unlocked' for 12 polls while canonical was locked (live, header-stripping proxy; module case fails, passes on 93479e944). (3) After a real head restart a 1000-request UI burst produced 392 refused fresh reads in 3.6 s, contradicting 'refused fresh reads do not create a loop'. Passing: lock/unlock, raw settings, second tab, approval, agent tool, chat, GPU/index, replay/reattach, restart display, full suites, architecture, regen-all, API contract, lane F. Report: F:/agent-sandbox/design-b6d536/codex/verify-status6/report.md"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status7-independent",
  "verdict": "reject",
  "digest": "sha256:f8d5d758193c3a5fb4ee3d532dfcb98b8c4a18aa2f59315713b496bb180f7c81",
  "note": "Candidate 6f177f241 replaces this record's behaviour (backstop over the full structure) with a state-field classification and changes the refused-read handling, so its evidence no longer describes the running mechanism. Independent verify-status7 on 6f177f241: FAIL S20 / OWN7-BACKOFF. On the built UI against the running candidate head, after 40 s of refused fresh reads (6 refused, back-off works) and a successful ordinary poll, a lock by another client and, separately, the mounted UI's own successful lock action are not shown at the next render: 0 fresh reads, display unlocked while canonical is locked, caught up only 8-15 s later by ordinary polling. statusInvalidation.ts keeps backoffUntil (up to 30 s, Retry-After up to 300 s) until a successful signal-triggered fresh read; successful ordinary polls do not end it and due() gates every action and receipt signal on it. Unit form fails on 6f177f241 and passes on 823c82104."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status8-independent",
  "verdict": "accept",
  "digest": "sha256:f8d5d758193c3a5fb4ee3d532dfcb98b8c4a18aa2f59315713b496bb180f7c81",
  "note": "Independent verify-status8 on 2fd48e172: PASS. S20 back-off repaired: any successful status read ends the back-off; live, after 40 s of refused fresh reads and a successful poll, another client's lock and the mounted UI's own lock made 2 s later show at the next render (fail on ff5bb2ae9). Builder statusBackoff cases fail on ff5bb2ae9 by assertion (5/10) and pass (10/10). ui suite completes at the 384m default heap (1731 tests, 0 failures; ff5bb2ae9 runs out of heap after 1210); narrowed producer import finds 87/87 counter call sites of a full import. About one refused fresh read per poll interval is bounded (live 7 in 40 s, 3 in 30 s after a restart); the previous bound-of-6 case assumed one clock (3 on a single fake clock); a lagging fresh read gets at most 3 ordinary follow-ups per value (unit and a live 59 s held Worker lane); an own action during back-off shows at the next successful poll; the statusCounterSignal change is justified. Lock/unlock, failed, read-only, replay, reattach, approval, chat, agent tool, GPU, indexing, raw settings, second tab, header stripping, restart, idle 10 min and full-response comparison with f6d579a86 pass; suites, frontend, architecture, lane F, regen, API contract and governance show no new failures. E2 driver window: 239/239 status reads, max 23 ms, 33.8 fresh reads/min, at most 3 per value. Noted, not caused by this candidate: a fresh read missing its 100 ms deadline publishes expired lanes (at-rest UNKNOWN, GPU unavailable) for 100-300 ms, so the UI's fresh reads generate some counter values under load (10 flips per 12 min E2-like; a loop when lanes are slowed). Report: F:/agent-sandbox/design-b6d536/codex/verify-status8/report.md"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VC-status9-20261014",
  "verdict": "accept",
  "digest": "sha256:f66587b4156f82c31dade249b0560e929353528b4baba8e0762480fc0d137d9a",
  "note": "VC-status9 (independent Claude verifier) on b6d536/status-timeout-2 vs 98cad099f: accepted ce7685a1c (b82: settings recovery null priorSettings fixed, 8 failures gone; data-age staleness, live 20-min E2 scripted-agent load 0 at-rest/GPU flips vs 92/106 on the base, 10871 canonical and 1186 fresh reads with 0 timeouts; ordered publication under an injected barrier; hung UI status read no longer stops polling; nothing blocks under the publication lock) and the records commits 19f230c5f, 41554dac9 and 0f9e824b2 (docs state staleAfterMs = 3 x sampler period and lanes stale after 3 of their own cadences with consequences a and b; Adaptations name the right decisions; compat patches add only PROBE_ALLOWANCE_PERIODS; before-failures rerun at f6d579a86, ff0c00f5e and 93479e944 match the records; behaviour texts corrected to the current code). Decided trade-offs: a hung lane is stale only after 3 of its cadences (30-120 s idle); a fresh read missing its deadline shows the last published value as current; a server always slower than 10 s shows empty status. Unverified: none beyond the named flaky StatusIsolationHttpTest under full CPU load (6/30 on both). Digest bound with --base 98cad099f, the lane's base."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS1-20261015",
  "verdict": "accept",
  "digest": "sha256:ac942667374bdf067733e970c92b0b7248b7caa78e4f65b50377d49c94949558",
  "note": "Re-reviewed the corrected candidate-only preservation explanation and status backstop checks; candidate and declared before runs match the evidence."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS5a-20261016",
  "verdict": "accept",
  "digest": "sha256:b7b1b0393f42a25059e1d9a495bfed4874029fdb1ff0e95502488a19d260f00e",
  "note": "Re-bound to origin/main 298354254 after confirming comment and whitespace cleanup preserves every assertion. All listed candidate checks pass and 93479e944 reproduces the backstop/publication failures; the separate candidate-only preservation reason remains explicit."
}
```
