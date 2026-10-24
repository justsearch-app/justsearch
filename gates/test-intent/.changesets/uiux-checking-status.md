---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: uiux-checking-status-pz8kayz9
---

# Test-intent entries

Scenarios are agent choice, adopted under the user's delegation.

Execution evidence is complete for nine UI and seven Java repair-check selections: candidate passes
and assertion failures on fc0c268eb. Six preservation selections pass before and after, without
skips. The clean baseline reproduced the full UI task's ArchUnit heap failure at the unchanged
384 MiB heap. The authorized targeted service and UI/HTTP suites pass; CI remains the authority
for the full UI task. No acceptance record is authored.

D10 originally failed the batch comparison; those results remain below. The orchestrator-directed
request-level rerun meets the 10% bound on all three fixtures, with identical scored coverage and
matched identities. No production code changed for the rerun. These are builder observations;
independent verification remains separate.

## D10 / F5 measurement protocol (declared before measurement)

Baseline: fc0c268eb. Candidate: this uncommitted changeset, with working-tree digest in evidence.
Use one private Engine at a time, CPU only (`CUDA_VISIBLE_DEVICES=-1`), the same private index
snapshot copied to separate baseline/candidate data folders before either timed run, and the same
read-only citation scorer `/home/elias/dev/justsearch/models/onnx/citation-scorer` (hashes recorded
with the run). Fake llama-server replies are deterministic; no real chat model is used.
Fixtures: a short ownership answer, a two-sentence ownership/date answer and a numeric answer;
each has a fixed source document and fixed answer text, recorded with the corpus/index hashes.
No indexing or other task checks run during timing. Record CPU affinity, available memory, model
hashes and resource conditions for each block. Warm the scorer with two untimed requests per
fixture on each Engine start. Fence the restored index with the existing indexing/settle API
before warm-up and again before timing, and record runtime status. Measure with a monotonic
clock on the dispatch client from the last
`chunk` event to `done`, excluding startup, retrieval and model text streaming. Also report the
five-run median of the scorer-reported `tookMs` for each paired fixture/block, with the same 10%
bound, as requested in the 2026-10-23 continuation. This addition is declared before the first
timing sample.
Alternate baseline and candidate blocks B,A,B,A,B,A, with five measured runs per fixture in every
block. Report each five-run median **per fixture** and compare each adjacent B/A pair (three pairs);
each candidate median must be within 10% of its paired baseline. Compare
`sentencesScored`, `sentencesTotal`, source coverage and matched sentence identities on identical
inputs; no newly unscored sentence is allowed. Preserve the raw frames and timing samples.

Fixed corpus: `tmp/checking-status/corpus/ownership.md`; fixture questions and fake answers:
`tmp/checking-status/fixtures.json`. Content/model hashes are frozen before measurement in
`tmp/checking-status/fixture-manifest.json`. The index snapshot is made once after indexing this
corpus and settling the index, before timing, and restored at the same private data path between
blocks so all stored absolute paths remain identical. This refines the separate-folder procedure
above: snapshots isolate blocks while the canonical private data path stays fixed.
Both distributions run from the same canonical working directory so the built-in help documents
keep identical source identities. Those automatically indexed help sources are included in the
fixture manifest and the frozen index manifest. Each distribution uses its own compiled classes
and dependencies, with hot reload disabled.

## D10 paired-request protocol (declared before new measurement)

The orchestrator requires a new measurement because batch-level drift confounded the prior
comparison. Keep the prior results below. Run B=fc0c268eb and A=candidate simultaneously, each
with its own private data, HOME and short temporary folder, both CPU only with identical 768 MiB
Engine heap and processor settings. Restore the same frozen index independently; verify all
existing corpus, answer, index and scorer hashes. Run from the same canonical working directory.
After both Engines report idle indexes, warm each with five requests **per fixture and side**,
then collect exactly 30 adjacent A/B pairs per fixture. Alternate pair order AB, BA, AB, BA,
yielding request-level ABBA interleaving. Rotate fixtures ownership, ownership-date, budget
within each round; alternate pair order globally, producing strict ABBA request ordering. Only one scoring request is in flight across both Engines. Record every
request, including warmups, side/order, monotonic times, scorer timing, raw SSE frames and
resource conditions. Discard no measurements. No real model generation, indexing or unrelated
checks run during measurement.

For each fixture report median paired A-minus-B differences in milliseconds for scorer
`tookMs` and last-chunk-to-done. Define spread as the 10th–90th percentile interval of those
paired differences, with linear interpolation between sorted observations. Also report both
side medians and their A/B ratio. The existing 10% bar remains; no newly unscored sentences or
changed matched identities are allowed. If ownership or budget exceeds 10%, investigate the
matcher barrier and progress emission before returning, as the orchestrator directed.

## D10 paired-request observations

B=fc0c268eb; A=the unchanged candidate digest
`0c0a9b704b5bcee47471ebe2f10355d4f464ffaa176a26df7082de9d24fb6803`.
Both private distributions were rebuilt before launch. Both Engines stayed running concurrently
with distinct private data/HOME and identical CPU-only JVM settings. Five warmups per fixture
and side preceded exactly 30 adjacent measured pairs per fixture, globally ordered ABBA.
All 210 requests (30 warmups, 180 measured) used CROSS_ENCODER, scored 1/2/1 sentences for
ownership/ownership-date/budget and retained identical coverage and matched identities.
Input/model/index hashes and pre-timing idle state verified. No requests or timing samples
were discarded, repeated or skipped. Shared-machine one-minute load ranged 28.53–33.07.

Delta is paired A-minus-B; spread is the declared P10–P90 interval. Ratios are median(A)/median(B).

| Fixture | Metric | B/A medians ms | Paired Δ median [P10,P90] ms | A/B |
| --- | --- | --- | --- | --- |
| ownership | scoring | 94.00/89.50 | +2.50 [-27.30,+17.10] | 0.9521 |
| ownership | text-to-done | 99.07/91.60 | +2.97 [-27.55,+17.47] | 0.9246 |
| ownership-date | scoring | 527.50/505.50 | -24.50 [-111.30,+105.50] | 0.9583 |
| ownership-date | text-to-done | 530.00/507.60 | -23.69 [-113.37,+105.30] | 0.9578 |
| budget | scoring | 281.50/274.50 | +3.50 [-91.60,+65.10] | 0.9751 |
| budget | text-to-done | 284.09/277.88 | +3.86 [-91.23,+66.53] | 0.9781 |

All six median ratios meet the unchanged 10% bound. Median paired completion differences are
+2.97 ms, -23.69 ms and +3.86 ms; every difference interval crosses zero. These observations
are not a claim that the candidate makes scoring faster. Neither ownership nor budget exceeds
10%, so the conditional profiling/attribution step was not triggered. No performance code fix
or heap/check configuration change was made.

Raw requests, SSE receive timestamps, runtime manifests, conditions and harness hashes:
`tmp/checking-status/paired-performance.json`; statistics: `paired-comparison.json` and
`paired-table.md`; execution log: `paired-driver.log`; exact pre-measure protocol snapshot:
`paired-protocol-before.md`. Both Engines and their owned drivers stopped normally after the run.
The previous batch evidence remains unchanged and is historical, superseded for this comparison
by the orchestrator's explicit request-level protocol.

## D10 prior batch observations

Five-run medians per fixture and adjacent pair; milliseconds, B=fc0c268eb, A=candidate.
Completion is the declared last-chunk-to-done interval. Scoring is scorer-reported `tookMs`.

| Pair | Fixture | Completion B/A ms | Change | Scoring B/A ms | Change | Coverage |
| --- | --- | --- | ---: | --- | ---: | --- |
| 1 | ownership | 47.4/61.6 | +30.0% | 46.0/58.0 | +26.1% | identical |
| 1 | ownership-date | 322.0/300.8 | -6.6% | 318.0/298.0 | -6.3% | identical |
| 1 | budget | 177.1/201.6 | +13.8% | 175.0/196.0 | +12.0% | identical |
| 2 | ownership | 50.2/57.5 | +14.6% | 47.0/54.0 | +14.9% | identical |
| 2 | ownership-date | 328.2/356.4 | +8.6% | 325.0/352.0 | +8.3% | identical |
| 2 | budget | 187.4/196.5 | +4.8% | 185.0/193.0 | +4.3% | identical |
| 3 | ownership | 123.2/52.6 | -57.3% | 119.0/49.0 | -58.8% | identical |
| 3 | ownership-date | 860.5/328.7 | -61.8% | 845.0/326.0 | -61.4% | identical |
| 3 | budget | 492.4/181.2 | -63.2% | 487.0/179.0 | -63.2% | identical |

The fixed protocol failed three completion comparisons and the corresponding scoring
comparisons. Input hashes verified in every block; all pre-measure worker states were idle;
coverage was identical across all six blocks, with 1/2/1 sentences scored for the three fixtures.
Shared-machine one-minute load ranged from 6.3 to 26.9. The third baseline block slowed sharply;
that demonstrates variation but does not establish the cause of every failing comparison.
No timing sample was discarded or repeated. Raw frames, samples and conditions are in
`tmp/checking-status/performance-{B1,A1,B2,A2,B3,A3}.json`; the comparison assertion failed.
Two setup failures (manifest-relative path and worker startup readiness) occurred before any
measurement and were corrected before the single complete six-block run.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-ask.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-record.evidenceProjection.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The real SSE parser drives the visible status and an outside-busy live region, leaves streamed reference literals unchanged, clears every terminal and result, deduplicates repeated start signals, and binds fast follow-ups to their own turn. Live and cold readers retain only an explicit unfinished fact, without fabricated counts; zero-match and historical unknown records remain distinct. Existing expectations are retained. Announcement receipts deduplicate an attempt across navigation and remount; failed turns retain an explicit incomplete-check disclosure.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "The citation-trust designs and the agent-adopted WP4.3 scenarios and amendments quoted in the builder assignment.",
          "choice": "S12.1: When the citation check actually starts on an Ask answer, the turn shows a status that the answer is being checked against the user's sources, driven by an Engine signal, not a timer. It ends when the check's result arrives or the turn settles, whichever comes first (decision D7).\n\nS12.2: The status clears on every outcome: result, check timeout, check failure, a later finishing step failing, connection drop, turn error, and Stop (the turn is then shown as stopped, also after cold reload, as WP1.2 delivers). Checked by holding the citation check, and separately holding a later finishing step: the status shows only during the first.\n\nS12.4: While the status shows, the answer text stays as it streamed: no reference literal is stripped and restored, no marks flash; at settlement marks appear once.\n\nS12.6: The status is announced while the check is running, once per attempt, with no stale announcement after settlement; the announcing region is not inside the transcript's aria-busy subtree (Sv3Main.ts:1815) or is otherwise shown to announce during the check. Legible in both themes, forced colors, about 360 px; motion respects reduced-motion.\n\nS12.7: Two conversations, or a fast follow-up sent right after settlement, never show the status on the wrong turn.\n\nS10.7: When the check did not finish (time limit or failure), the turn says the check did not complete, using the existing vocabulary for that state (evidenceProjection.ts:663-673, :755), and invents no counts; the panel does not call unjudged sources not cited and the frame does not call the answer not grounded. This stays distinguishable from a completed check with zero matches and from a historical record with no facts, live, after cold reload and after an Engine restart (decision D2).\n\nD2: Unfinished check (S10.7). Options: Head emits zero counts (draft); frontend infers from missing facts; Head stores an explicit incomplete-check fact. Recommend the explicit fact, mapped to the existing grounding-incomplete state (the agent plane already uses the NONE scorer stamp for this, agentEvidence.ts:86-97). Why: zero counts claim an examination that never happened; inference cannot tell old records apart.",
          "label": "agent choice, adopted under the user's delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-checking-status-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts#announces a checking attempt once across navigation, rerender and remount, then announces a new attempt",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts#keeps an unfinished-check disclosure when a later finishing step fails",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts#checks only from the Engine signal through the citation result, preserving the streamed text",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts#clears checking and its announcement on error even without a result",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts#clears checking and its announcement on CANCELLED even without a result",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts#clears checking and its announcement on done even without a result",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts#clears checking and its announcement on drop even without a result",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-ask.test.ts#keeps the explicit unfinished-check fact without inventing coverage counts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-record.evidenceProjection.test.ts#distinguishes an unfinished Ask check from zero matches and historical missing facts on cold load"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/CitationCheckingStatusTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/StreamingCitationMatcherTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatStopTurnHttpTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Observe the Engine dispatch with a held scorer and a separately held later finisher. Start is Ask-only and absent for skipped checks; text precedes the check result and terminal. Timeout, exceptional, null and NONE outcomes emit and store the same explicit incomplete envelope without counts. The saved question exists during checking. A later finishing failure still follows the check result. Stop preserves a check fact already delivered live; the existing nonfatal-service-failure expectation now requires that fact instead of silence. Later consumer/controller exceptions and STOP_ERROR keep an emitted incomplete-check fact and the failure code through cold history and Engine reconstruction.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "The citation-trust designs and the agent-adopted WP4.3 scenarios and amendments quoted in the builder assignment.",
          "choice": "S12.1: When the citation check actually starts on an Ask answer, the turn shows a status that the answer is being checked against the user's sources, driven by an Engine signal, not a timer. It ends when the check's result arrives or the turn settles, whichever comes first (decision D7).\n\nS12.2: The status clears on every outcome: result, check timeout, check failure, a later finishing step failing, connection drop, turn error, and Stop (the turn is then shown as stopped, also after cold reload, as WP1.2 delivers). Checked by holding the citation check, and separately holding a later finishing step: the status shows only during the first.\n\nS12.3: The status never shows when no check runs: an answer without sources, a blank answer, a check skipped for lack of verification sources (StreamingCitationMatcher.java:156-162), a model answer (WP4.2b) and a Delegate run.\n\nS10.7: When the check did not finish (time limit or failure), the turn says the check did not complete, using the existing vocabulary for that state (evidenceProjection.ts:663-673, :755), and invents no counts; the panel does not call unjudged sources not cited and the frame does not call the answer not grounded. This stays distinguishable from a completed check with zero matches and from a historical record with no facts, live, after cold reload and after an Engine restart (decision D2).\n\nD2: Unfinished check (S10.7). Options: Head emits zero counts (draft); frontend infers from missing facts; Head stores an explicit incomplete-check fact. Recommend the explicit fact, mapped to the existing grounding-incomplete state (the agent plane already uses the NONE scorer stamp for this, agentEvidence.ts:86-97). Why: zero counts claim an examination that never happened; inference cannot tell old records apart.\n\nF2: After Engine loss during checking, and after model-answer failure or context overflow, cold reload retains the saved question and accurately represents its failed or unfinished attempt. It never shows a completed empty answer, silently retries, or duplicates the question. The original no-evidence outcome remains intact.\n\nN12.2: Streaming, Stop and late Stop as WP1.2 delivered them; a Stop during the check is saved as stopped.",
          "label": "agent choice, adopted under the user's delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-checking-status-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/CitationCheckingStatusTest.java#unfinishedCheckFactSurvivesALaterFinishingFailure",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatStopTurnHttpTest.java#incompleteCheckSurvivesALaterFailureAcrossHttpAndEngineRestart",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/CitationCheckingStatusTest.java#checkingEndsWithItsResultBeforeALaterFinishingStep",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/CitationCheckingStatusTest.java#unfinishedCheckIsExplicitLiveAndOnTheStoredAnswer",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/CitationCheckingStatusTest.java#skippedChecksNeverStartAndSummarizeKeepsItsVocabulary",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/CitationCheckingStatusTest.java#resultPrecedesALaterFinishingFailure",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatStopTurnHttpTest.java#stoppedAnswerKeepsTheCheckFactAlreadyShownLive"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/CitationCheckingStatusTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "Preservation oracle: a completed zero-match CROSS_ENCODER result keeps its real scored count and carries no unfinished fact. This passes on both versions. The evidence also runs the existing citation-order/declaration and durable Stop/late Stop/disconnection guards; baseline results are recorded separately in uiux-checking-status-preservation-before.json.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "The citation-trust designs and the agent-adopted WP4.3 scenarios and amendments quoted in the builder assignment.",
          "choice": "S10.7: When the check did not finish (time limit or failure), the turn says the check did not complete, using the existing vocabulary for that state (evidenceProjection.ts:663-673, :755), and invents no counts; the panel does not call unjudged sources not cited and the frame does not call the answer not grounded. This stays distinguishable from a completed check with zero matches and from a historical record with no facts, live, after cold reload and after an Engine restart (decision D2).\n\nN12.1: Existing event order (text, then rag.citation_matches, then done). The old window, Summarize, MCP, API and Java stream readers ignore the new signal without errors.\n\nN12.2: Streaming, Stop and late Stop as WP1.2 delivered them; a Stop during the check is saved as stopped.",
          "label": "agent choice, adopted under the user's delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-checking-status-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/CitationCheckingStatusTest.java#completedZeroMatchesIsDifferentFromAnUnfinishedCheck"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StreamingFakeLlamaServer.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Add opt-in response-event.txt and keep-alive file controls for owned private browser/performance probes. No existing test creates these files: FINAL_EVENT, the release handshake, default lifetime, parent-exit behavior, authentication and existing assertions remain unchanged."
    }
  ]
}
```

## Checking-status rejection repair (builder observations)

The 6db83d87b rejection is addressed by per-attempt announcement receipts in per-tab storage,
retaining explicit check facts in later-error persistence, and rendering an incomplete disclosure
for failed turns. All three delegation locations use `task t-20261019-bd718f request`; kind and
agent-adopted wording are unchanged. The acceptance/rejection section is unchanged; this content
requires fresh independent acceptance.

Two new UI checks pass on the candidate and fail by assertion on 6db83d87b. A real browser probe
against a private 6db83d87b Engine repeats the announcement after switching between existing
conversation controls while the actual CPU scorer is held; the candidate keeps the visible
checking row without repeating the announcement. Reload does not restore checking or resend;
a new question and an actual Retry each announce as new attempts. Remount/rerender and cold
failed-record disclosure also have unit coverage.

The new Java check and real HTTP matrix pass on the candidate and fail by assertion on
6db83d87b: the old error record has no `claimMatches` although the incomplete fact was emitted.
The three HTTP outcomes are a later consumer exception, controller exception and STOP_ERROR.
Each keeps the fact and error code through cold history and a reconstructed Engine/history API.
Machine evidence: `gates/test-intent/evidence/uiux-checking-status-repair-ui-6db.json` and
`gates/test-intent/evidence/uiux-checking-status-repair-java-6db.json`. The original UI/Java
repair evidence is refreshed against fc0c268eb; six preservation selections (eight cases)
pass before and after, without skips. No compatibility patch or copied dependency tree was used:
run-evidence ran in default link mode from disposable shared clones. Its Vitest subprocesses
used the requested runner loader and disabled cache through the temporary Node preload, with
VITEST_MAX_WORKERS=2, to keep writes outside node_modules.

Final checks: lint/typecheck pass; full Vitest with `--cache=false --configLoader runner`
passes 608 files / 7,646 tests; targeted service/UI suites pass 156/125 tests, zero skips;
UnreferencedCodeTest passes three cases; affected PMD and installDist pass. UI gates pass 28/28
and all 14 requested governance gates pass. Live completion, timeout, Stop, connection loss,
Engine loss/restart and scorer failure pass with cold reload/restart and no automatic resend.
The unchanged 6db83d87b Engine reads the candidate's timeout fact through history and thread APIs.
Raw browser/HTTP reports and exact commands are under `tmp/checking-repair/`.

The scorer, progress emission, matcher barrier and normal completion path are unchanged.
Only error persistence changes on the Engine, so the conditional D10 rerun is not required.
The test-intent check against fc0c268eb has no delegation/evidence errors; its sole error is the
missing fresh independent acceptance. No acceptance record is authored by this builder.

## Acceptance records
