# C2 correction batch: final independent review

Date: 2026-09-13. Initial implementation revision: c56e1a838; evidence revision:
a0c7d390a. Final correction-verification revision:
`b713307eada7f56f772aaf7a9b715846802ba736`.

One independent read-only reviewer inspected the R1–R10 correction batch and then
verified two consolidated correction rounds. Root implemented the corrections.
The reviewer ran no builds, changed no files and owned no dev stack. Final verdict:
**no actionable findings remain in the assigned correction scope**. This verdict
covers code correctness and retained focused evidence; full and hosted verification
are separate acceptance obligations, recorded in review-r10-evidence.md.

| Finding | Correction and retained proof | Final disposition |
| --- | --- | --- |
| Ignored start/resume can return a successful pending result | 6a33258d9; review-r3-running-refusal.md; negative874/876, final877 (105 cases) | Resolved; unexpected refusal reports storage failure, terminal winner uses its captured receipt |
| Final-component index aliases do not share exclusion | 4f45e3af4; review-r10-index-alias.md; negative878/880, final881 (18 cases) | Resolved; canonical existing base and dangling-alias refusal |
| Queue transaction cleanup can commit partial work or omit a committed projection | 2168d1245; review-r8-transaction.md; negative889/892/898, final899 (78 cases) | Resolved; uncertain cleanup retains ownership and confirmed commits publish before native listener retirement |
| Failed metadata writes can lose the acquired native lock | e6f1e2a77; review-r10-lock-errors.md; negative884, final887 (87 cases) | Resolved; metadata is best effort only while ownership remains valid |
| Native close failure releases the JVM fence prematurely | e6f1e2a77; same record; negative884/886, final887 | Resolved; retained owner and propagated index-close failure |
| Living schema checklist omits jobs16 and operations2 | 06a8f85c2; review-r9-reconciliation.md | Resolved; C2 section8 names both migrations and the content_hash consumer still owed at C2-8 |
| Literal every-commit-body claim is false for historical commits | 21fbe728a; historical-commit-proof-map.md/json | Resolved by explicit historical mapping and narrowed prospective rule, without rewriting history |
| Correction verification: ordinary queue close still permits reopen; server swallows the close failure | b713307ea; review-r8-close-owner.md; negative903, final904 (100 cases) | Resolved in correction round2; unsuccessful close retains queue, index exclusion and incomplete shutdown |

The reviewer independently checked negative903's eight cases and both intended
failures, then all100 final904 cases across eight suites with no failures, errors or
skips. PMD and Spotless executed successfully; UI integration-test compilation was
UP-TO-DATE, not newly executed. These results do not claim a fresh full suite.
Raw: `tmp/c2-review-r8-close-owner-negative903.txt`,
`tmp/c2-review-r8-close-owner-negative903-xml/`,
`tmp/c2-review-r8-close-owner904.txt` and
`tmp/c2-review-r8-close-owner904-xml/`.

The historical mapping was independently recomputed:73 commits in
c9f5e3e93..a0c7d390a,54 empty bodies,54 unique JSON entries,54 Markdown rows and85
referenced Git blobs. All85 SHA-256 values, pinned paths, URLs, retrieval commands
and cited excerpts match immutable Git content. All20 correction commits from
5a10b9dc9 through06a8f85c2 have bodies. The mapping does not execute old tests or
turn their failed, partial or reused results into fresh passing proof.

Prior evidence limitations remain explicit: full900 failed only formatting despite
zero test failures; correction902 fixed that formatting. Whole C2 remains open,
including C2-3 keyed preparation and the six keyed C2-11 recovery scenarios.
