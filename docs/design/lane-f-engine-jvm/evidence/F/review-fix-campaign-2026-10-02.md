# Review-and-fix campaign, 2026-10-02

Report-back snapshot at `3c4ff74e5`. Sources: the orchestrator's
`tmp/campaign-ledger.md` (through its 05:49 entry), the dated
[handoff campaign](../../handoff.md#review-and-fix-campaign-2026-10-02-evening-read-first-supersedes-next-steps-below-where-they-differ),
`git log --format='%h %s' origin/main..HEAD`, and the F7/F8 assignment's
hosted-run IDs and current-head status. This is a summary of recorded observations,
not a new build, measurement, managed review record or merge authorization.

## Publication and integration

PR #727 is the current draft PR 1 on `codex/lane-f-pr1`, head `3c4ff74e5`.
PR #718 was the earlier draft opened at B, with hosted proof at `84b8c0b6f`.
The successor candidate retained the lane scope while preserving #718's immutable
checkpoints: see [publication lineage](../C2/publication-lineage.md) and
[B hosted proof](../B/hosted-ci.md). The handoff identifies #727 as the current
publication and keeps the merge at F, subject to the owner's explicit go-ahead.

Four review waves covered 46 areas and about 90 findings (handoff counts).
The ledger's final accepted set contains 24 packages: 23 integrated fix branches
and S7, which is based on main and is reserved for a separate PR. The build-fix
package was already integrated as `409b1926b`, separately from those 23 branches.

The first integration-to-lane merge is `0818dffed`; post-CI corrections joined
the lane at `6b7ddec54`. The campaign handoff at `7aaabb6a7` predates the overnight
compile/test reconciliation and hosted runs; its "not compiled or tested" statement
is historical, superseded by the later ledger entries below.

## Accepted packages and verdicts

The tip column is the accepted branch's second parent at its integration merge.
Verdicts summarize the ledger's final disposition; an acceptance under the stopping
rule does not mean that the last challenger issued an unconditional approval.
Challenger labels below are ledger review labels, **not managed review record IDs**.

| Package | Final recorded verdict | Accepted tip | Integration merge |
|---|---|---|---|
| P1 | Accepted under stopping rule; narrow hosted death-observability fix; residuals parked (fix challenger-23). | `e6e7d142d` | `de539aa77` |
| P2 | Independently approved. | `7bf1a7b64` | `27106d98a` |
| P3 | Independently approved, including X10-F1. | `adfc16323` | `0aed545d4` |
| P5 | Approved after expired-owner visibility fix (fix challenger-22). | `6d1c20944` | `00e9b1c90` |
| P6 | Approved on third review (challenger-17). | `65069ed01` | `aefd1e69c` |
| P7 | Approved (fix challenger-21). | `8e9430623` | `a9b7989f2` |
| P8 | Approved (fix challenger-19). | `ca4207036` | `bca52883a` |
| P9 | Accepted; root verified final minor round: docs tests 13/13, generator check and docs-validate. | `43e2f3d25` | `8ddae4002` |
| P10 | Independently approved in final accepted set. | `1b3802393` | `2ca2d87c1` |
| Q1 | Independently approved. | `55ac1bf2f` | `8bb4bb5b2` |
| Q3 / Q3n | Approved after owner replacement (fix2 challenger-24). | `43fb2e712` | `ae1576125` |
| Q4 / Q4n | Accepted under stopping rule; root-removal/refusal residuals routed (fix2 challenger-28). | `0536d5728` | `f09566a32` |
| Q5 | Independently approved. | `098d88dc1` | `12d51b3fb` |
| Q7 | Accepted, approve-with-fixes; minor CI coverage residual routed (fix2 challenger-33). | `2d9e18862` | `a03e342b1` |
| Q10 | Approved after cleanup-owner correction (fix2 challenger-34). | `2c743c0fb` | `1c63db01c` |
| Q11 | Approved; change-stream cache and observer retention (fix2 challenger-31). | `ed705c580` | `6a0451ac2` |
| Q13 | Accepted; last issue routed to Q4n, refuted with evidence and not re-flagged in combined review. | `ce4fb8ff1` | `e9c57ca08` |
| Q14 | Independently approved. | `7d97ed088` | `e3729855f` |
| S2 | Accepted, approve-with-fixes; data-loss regression fixed, minor pathless-chunk residual routed (fix3 challenger-23). | `3f483e10a` | `f9240d2b5` |
| S3 | Independently approved. | `d786d01b7` | `bf0c9ba5a` |
| S4 | Approved after archival-capture correction (fix3 challenger-16). | `65b09628e` | `b09a94364` |
| S5 / S5n | Accepted under stopping rule; PDF allocation residuals routed (fix3 challenger-20). | `e6ec2d153` | `b74f2aa83` |
| S6 | Approved (fix3 challenger-22). | `fafab8b65` | `ee0f0806b` |

S7 was approved on its third review (fix3 challenger-13), on
`codex/lane-f-fix3-s7`; it is excluded from the integration count. Its accepted
tip is not recorded in the supplied lane ledger and is not inferred here.

## Stopping rule and integration corrections

The root's ~20:30 rule accepts a package once its latest review finds neither a
fix-introduced regression nor an unfixed original finding. Further pre-existing
sibling paths receive one final round, then become recorded follow-ups. This rule
was applied to P1, Q4n, S5n and the archive preflight integration correction.
It does not authorize integrating the parked designs.

Production-conflict merges received independent review. Archive preflight regression
I2 was fixed by `b946e3af8` and accepted under the stopping rule (integ challenger-5);
remaining sibling paths went to untrusted-input hardening. The initial RINTs5fix
checkout error was superseded by RINTs5fix2 at that commit. S3 citation-cancellation
tests were reconciled with S6 inference admission at `5ea0a529f`.

The ledger records `build -x test` green at `57e249712`, followed by G/H suite
corrections (including `06a0aeff7`, `65cbaf50a`, `8461fd631`, `f880be966`).
It does not claim a clean local full-suite result on the memory-exhausted machine:
the root classified the UI status timeouts as environmental at the unchanged
lane head, then used hosted CI as the overnight verification source.

Post-integration CI fixes:

- `1fd54134d`: classify the new persistence writes in store-recoverability.
- `8c5bbd314`: retain page size in field-sorted knowledge search so nextCursor survives;
  accepted by integ challenger-7 (approve-with-fixes), with reranked TEXT residual routed.
- `c435ea392`: repin config-surface YAML keys at 109 and declare the braces advisory.
- `3c4ff74e5`: regenerate module-deps and llms.txt for the integrated fixes.

## Hosted CI and limits of the receipts

Run IDs are supplied by the F7/F8 brief. The ledger records the heads and outcomes
chronologically but does not attach those IDs to its entries. The first two
associations below follow that chronology; they still need exact hosted receipts.
Read-only receipt lookup could not authenticate through the sandboxed GitHub CLI,
and unauthenticated API access was unavailable. No PR was edited.

| Hosted run | Recorded outcome / receipt status |
|---|---|
| [37087124745](https://github.com/justsearch-app/justsearch/actions/runs/37087124745) | Head `0818dffed`, conclusion failure (attempt 1). Failed: Public claims (unclassified store-recoverability write sites) and Integration tests (HttpPagingCursorE2ETest: no `nextCursor`). All other jobs passed, including Windows-native, app-ui, search-worker and model-free lifecycle. |
| [37089447837](https://github.com/justsearch-app/justsearch/actions/runs/37089447837) | Head `6b7ddec54`, conclusion failure (attempt 1). Failed: Public claims only (npm-audit advisory, config-surface pin). Integration tests passed with the paging fix. |
| [37090994095](https://github.com/justsearch-app/justsearch/actions/runs/37090994095) | Head `c435ea392`, conclusion cancelled (attempt 1). Public claims failed on the stale module-deps canonical doc; superseded by the push of `3c4ff74e5` before Integration tests completed. |
| [37092195712](https://github.com/justsearch-app/justsearch/actions/runs/37092195712) | Head `3c4ff74e5`, conclusion success (attempt 2). Attempt 1 failed only Windows-native on a supervisor-conformance readiness flake; the rerun passed. |

The final flake was `fileless-clean-local-restart-is-not-counted`, reporting
"supervisor never reached running/incarnation 1". The diff from the previously
passing Windows-native head was docs-only. The root routed readiness-wait hardening
to the dev-runner process-ownership follow-up rather than claiming the flake fixed.

## Parked designs and follow-ups

| Item | Recorded disposition | Owner / destination |
|---|---|---|
| S1 + Q9 | Generation GC/crash safety: four rejects found further unprunable classes; proof-free leftovers need ownership proof. `codex/lane-f-fix3-s1` retained, not integrated. | Generation-GC design owner / post-merge ownership-model follow-up. |
| Q12 | Enrichment/backfill consistency: four rejects across two owners; require revision witnesses at every publication and completion latch. `codex/lane-f-fix2-q12` retained, not integrated. | Enrichment owner / post-merge consistency design. |
| Q15 + S8 + SUP | Seven rejects across three owners; dev-runner owner model and lifecycle state machine needed. `codex/lane-f-fix3-s8` retained, not integrated; includes P1 residuals. | Dev-runner/supervision owner / post-merge process-ownership design. |
| Untrusted-input hardening | PDF duplicate-filter decode params, image-filter output and inline aliases; archive local-header metadata, shared resource accounting and filename-marker exemption. | Security/extraction owner / post-merge hardening lane. |
| Root-removal residuals | Workers after caller timeout, replacement fences on frozen reindex plans, buffered deletion completion, silent citation-matching refusals. | Root lifecycle/refusal owner / post-merge hardening lane. |
| X07-F1..F5 | Fix jseval instrument bugs after E; preserve frozen instrument pairing. | jseval owner / follow-up PR after E. |
| P4 | Lucene-commit/SQL-outcome crash window remains a known C2 gap; owner deferred. | Durable-ingestion owner / post-merge crash-witness follow-up. |
| X05-F1 | Watched-root escape needs a design; root deferred. | Root authorization/security owner / post-merge hardening design. |
| W06-F1 | Pre-existing AUTO search disables dense when opt-in BGE-M3 is selected. | Search-quality owner / search-quality register follow-up. |
| S7 | Approved main-based agent-tooling package; separate from lane integration. | Agent-tooling owner / separate PR with owner's go-ahead. |
| Reranked TEXT paging | Relevance-sorted TEXT with reranker ready still overfetches and drops the cursor; changes would move E1 quality mid-lane. | Search-quality owner / reranked-window pagination design after E. |
| Supervisor readiness flake | Final Windows-native rerun passed; readiness-wait hardening remains open. | Dev-runner/supervision owner / process-ownership follow-up. |
| Q7 CI coverage residual | Unreachable commands in multiline steps can receive coverage credit. | Governance owner / CI coverage hardening. |
| S2 pathless chunks | Differing owner identity/backing path can leave orphan chunks; pre-existing minor residual. | Index cleanup owner / post-merge hardening lane. |
| W07-F3 / W09-F1 | Conversation metadata RMW races and dev-only reuse of closed NER were routed follow-ups; no closure recorded. | Conversation-store / dev-reload owners, post-merge follow-ups. |
| X09 dev MCP ownership | Findings queued behind Q15; no separate accepted closure recorded. | Dev-runner/MCP owner / process-ownership follow-up. |
| World-state perf smoke | Historical C1 proof: 10,108 ms vs unchanged 10,000 ms bound under concurrent Gradle; all 16 pass standalone. | Agent-analytics owner / repeat on quiet machine; [C1 receipt](../C1/integrated-candidate.md). |

## E and inherited report-back obligations

The overnight paired branch queue has **not started**. The memory gate remained
closed at 03:42, 04:30 and 05:30; the last entry records about 2.8 GB available and
81% commit. E stays in progress, with no new E measurement, paired table or verdict.
Existing [E values](../E/values.json) retain `floorMachine.claimed=false`.

The owner deferred E7 signed upgrade proof to the next signed release. Linux
recovery proof remains unrecorded, conditional on the supported-platform claim.
D2 remains post-merge work on main under the dated
[re-plan audit](../replan-audit-2026-09-30.md) and handoff re-plan; no D2 acceptance
or evidence directory is claimed. These are inherited dispositions, not campaign fixes.

Section 18 still routes README disclosure and threat-model OTLP wording to the
product-doc owner, the inference host priority/aging contract to the inference lane,
and the field register to lane D. No new completion of those requests is recorded.
The handoff also records PR #735 (Codex role pins) as open, for the owner to merge;
its current hosted state was not rechecked in this drafting assignment.

Two previously outstanding cleanup items have commits: the 36 stale io.grpc
verification rows were pruned at `6ddc14994`; G1 and compact-header AOT training
flags were corrected at `59927a956`. Installed cache-load verification remains
an outstanding handoff obligation; E uses no dev AOT cache in either arm.

F-7's report-back is updated and [F-8's public body](pr1-body-draft.md) is drafted.
Final E results, exact campaign hosted receipts, managed review IDs/current record,
the remaining F-8 proof and owner merge go-ahead are not supplied by this draft.
