# D1 acceptance assertions (2026-09-30)

Working base: `e981ac33b1e4092b2b4e8340b86ab9eb725a200a`, branch
`codex/lane-f-d1-close`. Test and fixture edits are uncommitted. No persistent
production change, commit, push, dev stack, or installed Engine run is authorized
here. Temporary production mutations are authorized only for red/green proof
and must restore the original bytes.

Governing inventory: [acceptance reconciliation](acceptance-reconciliation-2026-09-30.md).
This receipt records additions and verification limits; it does not close D1.

## Added regressions

| Clause | Test / proof owner | Witness |
|---|---|---|
| D1-9, D1.md 1871-1873 | `NativeGenerationPromotionTest` | Exact second `startFreshMigration` refusal for candidate and retained predecessor; retained-state reason, reopened state/pointer, exact unchanged directory names. |
| D1-8, 1651-1652 | `IndexGenerationRetirementTest` | Fresh transition disk trace `[1, 2, 2, 2, 1]`; each count at most two; exact surviving Green and absent Blue. |
| D1-8, 1647-1649 | `EngineMigrationLifecycleTest` | Issued Blue view exceeds the actual five-second retired-view deadline; timeout reports retained ownership, old runtime remains queryable, release completes retirement. |
| D1-8, 1646-1647 | `EngineMigrationLifecycleTest` | Search loop spans the real before-SWITCHING barrier and swap; every response equals the known complete, disjoint A or B document-ID signature. Async errors propagate. |
| D1-8, 1643-1646 | Installed `migration` fixture / `EngineSupervisedRecoveryE2ETest` | Actual pre-activation manifest PID/instance and supervisor incarnation equal promoted and settled snapshots. Compile-only in this assignment. |
| D1-8, 1652-1653 | `EngineNativePointerBootMutationTest` and `RecordedBulkIngestionCoordinatorTest` | Real recorded post-pointer cut, actual body exit and reopen; preterminal store oracle observes exact writable B, sealed queue revision, actual replay certificate and empty journal, then permits COMPLETE. Companion rejects wrong writer and unsettled replay. |
| D1-8, 1620-1622 | `KnowledgeServerCloseCompletionTest` | Actual issued-before-start query, nine real WP1 pauses, blocked acquisitions in publication write sections, and exact Blue runtime usability after Green publication. |
| D1-13, 2326 / 2345-2349 | `EngineLifecycleE2ETest` / installed gap fixture | Real restored-A native CPU lease retained through controlled lifecycle shutdown; unquiesced diagnostic and absent JVM-hook log distinguish hard stop; code 1 is counted TRANSIENT recovery to a new PID/instance. Compile-only in this assignment. |

After two substantive review rounds, the proof was narrowed to production-owned
barriers and physical witnesses: no reflected synthetic publication, invented
manifest identity, postterminal-only settlement claim, or count-only search
identity is accepted.

The preterminal oracle rejects publication writers and takes the existing
publication read lock without waiting. It reads the actual published runtime
and committed operation/queue rows through read-only SQLite connections with
zero busy timeout. It avoids contention with ordinary model initialization and
queue polling while preventing a premature completion from waiting into B.

## Independently confirmed source finding

The new issued-before-start WP1 regression exposes a probable Blue runtime
lifetime failure on the unchanged production path. Runtime execution is pending.

`KnowledgeServer.captureServingView` increments the exact view's holders
(`KnowledgeServer.java:5460-5478`). Live Green start retires the original A/A
view and publishes an A/B view; original cleanup intentionally leaves A's
runtime open (`:6998-7041`). Final promotion captures and retires the A/B view
(`:7325-7329`, `:7444-7470`). Its cleanup can close A through
`closeRetiredSource` (`:7569-7577`) after the temporary source lease exits
(`:7552`), while the original A/A view still holds an issued query. Cleanup
checks only its own view's holders (`:5587-5603`).

Independent refutation confirmed this ownership chain. The existing migration
held-view test acquires after Green is open, so it retains the later A/B view
and misses the earlier issued-query case. The new matrix queries the original
Blue runtime after publication and must not be weakened if it fails.

Proposed bounded fix, not implemented: arbitrate retirement of the exact physical
runtime across every serving/retired view referencing it under the existing
owner monitor. Compare that with an explicit shared runtime lease before adding
another lifetime representation. Preserve the existing retry and retained
capacity behavior; do not close Blue until every issued query using it exits.

## Verification status

- `git diff --check`: passed on the current dirty tree.
- Strict UTF-8 decoding of all nine modified Java files passed; none has a BOM.
- `node --check scripts/supervisor-conformance/bulk-fault-scenario.mjs`: passed.
- `node --check scripts/supervisor-conformance/migration-restart-scenario.mjs`: passed.
- `node --test scripts/supervisor-conformance/bulk-fault-semantic.test.mjs scripts/supervisor-conformance/bulk-fault-citation.test.mjs`: 13 tests, 0 failures, 0 errors, 0 skips. Node emitted its existing experimental SQLite warning.
- `LC_ALL=C.UTF-8 git diff | grep -P '^\+.*[^\x00-\x7F]'`: no added non-ASCII text at the last inspection.
- Gradle grant was absent throughout 60-second polling from `2026-09-30T17:27:54Z` to the 170-minute deadline. The poll exited with `GRADLE_GRANT_TIMEOUT`, code 2, at approximately `20:17:56Z`; a final check at `20:18:08Z` was also absent. No Gradle command ran.
- Java compile, Spotless, focused class runs, XML counts, and temporary production-mutation red/green demonstrations are unverified. No current-run JUnit XML or red/green demonstration exists.
- Installed scenarios must not run; this assignment authorizes their compilation only.
- `git diff --stat`: 11 tracked files, 1,252 insertions and 36 deletions; this new evidence receipt is additionally untracked.
- Closeout world-state output is retained at `tmp/d1-close-proof/world-state-closeout.txt`. The worktree remains dirty and uncommitted by instruction. Its read-only spawn inventory reported the ownerless OTLP sink and two identity-refused UI-shot records; none was changed. The mutation-capable cleanup sweep and worktree lifecycle writes were omitted because they write the shared main registry outside the assigned worktree.

## Remaining expression limits

WP1 has no hook inside the atomic query-capture monitor between resolving the
view and incrementing its holders (`KnowledgeServer.java:5460-5478`). The matrix
covers capture before migration, acquisition blocked during real swap sections,
and all nine live/swap points; literal pauses inside each acquisition substep
cannot be added without a new production seam. Same-configuration Flow A
intentionally reuses one compatible encoder owner. This matrix does not replace
the differing-model recorded A/B witness assigned to D1-12.

This assignment stopped at the specified grant timeout. A subsequent verification
pass needs the grant before every allowed Gradle command. First format/compile
the touched modules and execute the new WP1 regression against unchanged
production. A real assertion failure must stop that pass and be reported with
XML/log evidence and the proposed fix. No production mutation has been applied.
