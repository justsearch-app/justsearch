# R10 evidence, build and hosted reconciliation

Current code checkpoint:c56e1a838. Final independent review is in progress across
R1–R10; correction-batch closure is not claimed here.

## Source and raw-artifact identity

C2-1-source-snapshot.json and C2-11-source-snapshot.json now hash raw Git blobs from
the declared committed revision,47 and6 files respectively. Their supersedes records
preserve the original inventories' declared bases and an exact Git retrieval path;
the new hashes do not relabel historical test results. A fresh verification checks
all53 against git show. Both held retirement patches also reverse in private indexes
and reconstruct all23 original committed blobs exactly. The zero-context format
requires --unidiff-zero and avoids storing trailing-space context markers. Evidence:
`tmp/c2-review-r10-held-reconstruction864.json` and
`tmp/c2-review-r10-source-and-held871.json`.

raw-evidence-sha256.json inventories every literal raw-path citation in the C2 evidence
Markdown, expanding documented braces/globs and hashing directory contents. The R10
citation repairs name the existing artifacts explicitly instead of relying on prose
suffixes. No unavailable path is silently treated as proof. The inventory is refreshed
after the last full-run capture, with its own generation timestamp and code base.
Raw logs and XML remain accessible in the named worktree/shared-runner locations
through lane acceptance plus30 days and must be exported before worktree release.
Hosted artifact expiry and retained local copies are recorded in hosted-ci.md.

## Required checks

Release compatibility tests now run in the Public claims CI job. Local841 and hosted
CI34730328727 execute all12 cases, including the baseline compatibility controls.
Local workflow trigger validation842 and canonical regeneration855 pass. Operation
surface and register-guard-resolution gates859/860 pass; their source contracts are
unchanged by the subsequent test-only snapshot/lifecycle corrections. Canonical links
pass851 (156 documents). Raw:
`tmp/c2-review-r10-release841.txt`,
`tmp/c2-review-r10-workflow842.txt`,
`tmp/c2-review-r10-regen855.txt`,
`tmp/c2-review-r10-operation-surface859*`,
`tmp/c2-review-r10-register-guard860*`, and
`tmp/c2-review-r10-links851.txt`.

The full build found and corrected three residual issues: the generation fixture's
independent completion owners (full853/focused857), the held snapshot's remaining
test-only helper (full858/focused862), and the lifecycle marker's false-positive
search (full863/negative867/focused868). Each has a separate committed proof record.
No validation, test intent or timeout was weakened. The original Windows/Linux native
exclusion failure and the63-case fix gate848 are documented in review-r10-lock-exclusion.md.

Full869 atc56e1a838 passes in6m49s. Its38 test tasks represent10133 cases across1647
suites: zero failures/errors and35 inherited skips. The Engine task executes all231
cases; the other37 tasks reuse unchanged successful inputs. Full863 had executed the
other changed correction modules and passed them. Both complete XML captures remain
available. latest-full-run-summary.json records the exact command, revision, counts,
execution/reuse and the retrieval path for the superseded historical full744 summary.
Raw `tmp/c2-review-r10-full869.txt`, `tmp/c2-review-r10-full869-counts.json` and
`tmp/c2-review-r10-full869-xml/`. The command is:

```text
gradlew.bat build pmdAll :modules:ui:compileIntegrationTestJava --continue
  -PtestParallelism=1 --max-workers=4 --console=plain
```

The successful hosted checkpoint atd9c80a646 is documented with actual task execution,
named unskipped cases and artifact limits in hosted-ci.md. It supplies current Linux
launcher, MCP quota, Windows native locks, installed operations recovery and parser
orphan proof. It does not certify the subsequent lifecycle-test-only change; CI34731185342 atc56e1a838 also passes all13 jobs after that correction. The final
independent review remains open.
