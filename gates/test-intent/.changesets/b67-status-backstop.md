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
      "behaviour": "The full canonical status structure publishes missed owner changes and stale/recovered transitions, excluding clocks and ages. Failed reads carry publication metadata; cached polls with unreflected changes refresh once; restart and missing metadata cannot restore older current state. Refused fresh reads do not create a loop. Copied verifier assertions are retained.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "metadata on any response counts, successful or not; a body is never shown as current unless it is known to be at least as new as what is displayed",
          "location": "task t-20261005-b6d536 request (b67 builder brief, task item 5 / S20)"
        }
      ],
      "evidence": "gates/test-intent/evidence/b67-status-backstop-repaired.json",
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
      "behaviour": "Retain the independent verifier real-HTTP isolation, admission, owner, projection and provenance checks as preservation evidence. Their assertions remain unchanged; the fixture temporary directory uses the test runtime directory.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Preservation: every check added by ea0c311cb, b53's StatusIsolationHttpTest and admission checks, the verifier's 65 own preservation cases where reusable",
          "location": "task t-20261005-b6d536 request (b67 builder brief, Checks)"
        }
      ],
      "evidence": "gates/test-intent/evidence/b67-status-backstop-preservation.json",
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
      "whatMoved": "Before each stale or unknown assertion on a held lane, the cases now age that lane by its own clock with StatusLaneAging.ageHeldLanes: they advance the handler clock beyond the capped idle limit and run one bounded sampler pass. Two b82 decisions caused this. Decision (a): a hung lane is reported stale only after 3 of its own update intervals, so detection is later when idle, about 30 to 120 s. This is accepted as the cost of no longer flipping current values to unknown under load. Under the old rule a refresh in flight for more than 1 s made its lane stale, so these cases used to sleep 1.2 s: VerifierStatusIsolationTest's blocked-disk and hung-worker cases, VerifierStatus2Test's per-owner case, VerifierStatus4Test's additional-owner case and VerifierStatus5Test's samplerFailureAndRecoveryPublishCanonicalFreshnessChanges. Decision (b): when a fresh read misses its deadline, it shows the last published value labelled current, rather than unknown. So right after an action it can briefly show the pre-action value. These cases used to observe the held lane as stale or unknown right after a fresh read had missed its deadline, and they now age it first: VerifierStatus3Test's persistent hung worker, VerifierStatus4Test's older pending failure (conversation protection unknown) and VerifierStatus5Test's unknown unrelated owner. Each case still holds the same producer. It asserts the same stale or unknown marker on exactly that part, the same current values on every other part, the same counter and reflected-revision relations and the same recovery."
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
