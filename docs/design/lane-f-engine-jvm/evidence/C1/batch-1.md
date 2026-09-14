# C1 batch 1: context, resource policy and admission oracle

Candidate: the checkpoint containing this record, based on `395078f04`. Windows local proof.
The preceding commits integrate main's policy update and the newer authorized stage designs.
No B evidence was reverted; PR 718 still carries the final implementation merge at F.

The core context keeps survival and urgency independent and rejects unbounded attribution
strings. Its reflection regression rejects extra derived accessors. The sibling projection to
`InvocationProvenance` preserves the explicit executor and signed-token input. Source tier is
resolved by the existing intent-gate resolver, including its UNTRUSTED fallback for anticipated
transports; contradictory attribution is refused. Port/front consumers are batch 2's work.

`EngineRoot` loads the packaged authoritative retained-state register. All five retained kinds
report no live count until their D1/D2 producer connects. Aggregate permits refuse at the cap and
release once; a concurrent synthetic test admits exactly three of twelve contenders at cap three.
The cursor's per-context cap is preserved as metadata; its enforcement belongs to D2. Execution
limits are declared here and connected to admission/executors during later C1 batches.

The admission oracle checks response codes, retry headers, transport failures, workload identity
and operation mix. Both aggregate arms use a non-binding fairness cap. Each synthetic context
must offer search and chat; the many-context arm distributes offered work evenly. There is no
live capture in this batch and its self-test cannot establish real admission or pacing behavior.

## Verification

Raw output is accessible under the lane worktree's `tmp/`; retain through lane F completion and
30 days after merge. Test XML snapshots are under `tmp/c1-batch1-xml/`. Commands are run at the
worktree root unless stated otherwise; `-PskipErrorProneTests=false` enables the compiler checks.

| Check | Result | Evidence |
| --- | --- | --- |
| Repository `build -x test` | PASS | `tmp/c1-batch1-final-build.txt` (329 tasks, final batch source) |
| `:modules:core:test` | PASS, 82 tests | `tmp/c1-batch1-final-tests.txt`, XML `core/` |
| `:modules:app-api:test` | PASS, 199 tests | same, XML `app-api/` |
| `:modules:app-engine:test` | PASS, 133 tests | `tmp/c1-batch1-final-tests.txt`, XML `app-engine/`; final schema-type validation is separately green in `tmp/c1-batch1-final-policy.txt` |
| EngineProvenance and IntentGateEvaluator tests | PASS, 17 tests | `tmp/c1-batch1-review-tests-fixed.txt`, XML `app-services/` |
| EngineResourcePolicy tests | PASS, 3 tests | `tmp/c1-batch1-review-tests-fixed.txt` |
| Rust `cargo test --lib --locked` | PASS, 77 tests | `tmp/c1-shell-tests.txt`; `TAURI_CONFIG` removes bundle resources for the library test only |
| Launch flags exact-set pin | PASS | `node scripts/dev/test-dev-runner-head-java-opts.mjs` |
| Admission oracle self-test | PASS, 26 cases | `node scripts/jseval/lane-f/admission-loop.mjs --self-test` |
| Generated artifacts | PASS, all 8 sets | `tmp/c1-regen-all.txt`, unfiltered `node scripts/ci/regen-all.mjs --check` |
| Docs validation and index | PASS | `tmp/c1-batch1-docs-final.txt`, 115 indexed docs |
| Edited JavaScript ESLint; diff whitespace | PASS | `npx eslint` on oracle and both launcher scripts with `--max-warnings=0`; `git diff --check` |

The independent read-only review found a false-pass workload oracle, an insufficient accessor
test and conflicting trust labels. These were corrected. Its second pass caught the missing
UNTRUSTED fallback; the root factored the existing resolver and verified explicit anticipated-
transport regressions. The first focused run's failure is retained in
`tmp/c1-batch1-review-tests.txt`; the passing correction does not erase that history. A separate
resource review found discarded cursor metadata and weak loader validation; both are corrected.
No production retained limit or live resource envelope is claimed by this batch.
