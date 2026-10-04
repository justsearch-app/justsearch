# Stage E driver reviews (2026-10-01)

Two independent refute-first reviews (Sol, read-only) of the stage E driver. Verbatim.

## Review 1 (against af4320d0e)

Reviewed `codex/lane-f-pr1` at `af4320d0e`. I found these substantive objections, ranked by severity.

1. **P1 — E3 can pass while chunk indexing remains starved.** [e-run.mjs:371](scripts/jseval/lane-f/e-run.mjs:371) skips every zero or missing split rate; another positive stage can establish a pass. The retained main run `2026-10-01T12-20-49-471Z-207f3b79` passes E3 with zero embed, chunk and NER rates.

   **Effect:** Yes, both arms can starve an excluded stage and pass. Branch completion of a stage main never reached is also excluded, although that represents additional progress. This follows the amended [E.md:248](docs/design/lane-f-engine-jvm/stages/E.md:248), but weakens the explicit chunks/s and anti-starvation requirement in [design.md:2294](docs/design/lane-f-engine-jvm/design.md:2294).  
   **Fix:** Keep the per-stage comparisons, but require a separate comparable chunk-progress measurement. Until obtained, mark that acceptance condition unmeasurable. Explicitly reconcile any intended replacement of §16.

2. **P1 — Boundary censoring can erase a terminal failure already observed.** [admission-loop.mjs:1113](scripts/jseval/lane-f/admission-loop.mjs:1113) marks a boundary-aborted stream without checking its recorded terminal errors. [e-run.mjs:406](scripts/jseval/lane-f/e-run.mjs:406) bypasses terminal validation for that request, and line 415 removes it from terminal-error reporting.

   **Effect:** Yes. A stream emitting an error and subsequently hanging until boundary cancellation can become an acceptable censored request.  
   **Fix:** Censor only incomplete streams with no observed terminal violation. Preserve error events, duplicate done events and known HTTP failures regardless of subsequent cancellation; record the cancellation cause explicitly.

3. **P1 — E5 accepts index recovery beyond its bound.** [e456-live.mjs:412](scripts/jseval/lane-f/e456-live.mjs:412) accepts any finite `indexMs`; acquisition permits up to 180 seconds at line 366. The table applies a numerical deadline only to `crash-to-api` at [e-run.mjs:528](scripts/jseval/lane-f/e-run.mjs:528).

   **Effect:** Yes. Fast API restoration followed by very slow index restoration can pass, contrary to [E.md:149](docs/design/lane-f-engine-jvm/stages/E.md:149), which puts index readiness within the same 13.6-second budget.  
   **Fix:** Check the recorded index-restoration duration against that budget during projection and table evaluation.

4. **P1 — Pair identity omits actual workload bytes and some acquisition code.** [e-run.mjs:853](scripts/jseval/lane-f/e-run.mjs:853) hashes documentation as `corpus`, even for SciFact measurements. `bulk_load.py:45–50` records document count and query-pool hash, without binding the materialized document bytes. Separately, [e-pair-identity.mjs:75](scripts/jseval/lane-f/e-pair-identity.mjs:75) inventories extracted acquisition helpers but omits `e-run.mjs`, which still implements execution deadlines and the soak acquisition loop at lines 917–930.

   **Effect:** Yes. Changed SciFact contents, or changed acquisition behavior remaining in the driver, can retain the same identity. Path normalization alone is reasonable; these omitted inputs make it insufficient.  
   **Fix:** Bind the actual corpus manifest and executed model identities. Move all acquisition behavior into hashed helpers, leaving only projection/table behavior outside acquisition identity.

5. **P2 — The table can compare differently scored captures.** [e-run.mjs:489](scripts/jseval/lane-f/e-run.mjs:489) checks acquisition identity and values hashes, but no common projection version. Reprojection records its driver/raw hashes at lines 767–770; the table never validates them.

   **Effect:** Yes. Excluding scoring code appropriately preserves reusable measurements, but old and new scoring interpretations can silently produce an easier paired verdict. This changes interpretation, not necessarily acquisition.  
   **Fix:** Give projections a separate identity and require both arms to use the same current scorer and values, preferably by reprojecting both retained populations together before table generation.

6. **P2 — Branch-only wire scoring weakens the paired procedure and conflicts with E0.** [e-run.mjs:478](scripts/jseval/lane-f/e-run.mjs:478) substitutes `true` for main’s failed or missing wire clause whenever its record exists. The retained latest scripted main population contains **33 search 504s and four `LLM_ERROR` terminals**, not merely failures after the window.

   **Effect:** Yes at table level: the paired failure becomes a pass when branch passes, despite [E.md:229](docs/design/lane-f-engine-jvm/stages/E.md:229) retaining timeout/5xx failures. However, [e-run.mjs:599](scripts/jseval/lane-f/e-run.mjs:599) still requires every main E2 clause to pass before E0 freezes bounds. Thus the current acquisition flow blocks this failed baseline rather than implementing the declared exception consistently.  
   **Fix:** Preserve paired wire validity, or explicitly amend the contract to distinguish baseline outcomes from candidate acceptance and specify baseline eligibility. Apply that decision consistently in E0 and table scoring.

7. **P2 — E4’s component budget clause checks launch flags and the split sum instead of a component budget.** [e456-live.mjs:217](scripts/jseval/lane-f/e456-live.mjs:217) validates launch flags; [e-run.mjs:526](scripts/jseval/lane-f/e-run.mjs:526) repeats the machine-wide comparison for `component-commit-budget`.

   **Effect:** Yes. Missing consumer-budget evidence can be presented as a component-budget pass, although [design.md:1820](docs/design/lane-f-engine-jvm/design.md:1820) requires explicit consumer accounting, including host ORT allocations.  
   **Fix:** Evaluate the component budget separately; retain launch-flag compliance and machine-wide comparison as distinct checks.

The following parts are sound, within these limits:

- **Item 1:** The fixed hybrid-then-lexical window matches the amended contract. Early observed completion invalidates it; missing, stale and discontinuous samples cannot establish validity (`bulk_load.py:100`, `e-run.mjs:281`).
- **Item 2:** Positive split stages require positive branch rates meeting 90%; missing branch rates and no comparable stages remain unmeasurable. The exclusion weakness is finding 1.
- **Item 5:** Both arms follow the same activation-before-measurement sequence (`e-run.mjs:255`, `e-start-ready.mjs:57`). I found no driver timing asymmetry. **Equivalent GPU co-residence is unproved:** readiness requires only reranker presence, and captured main activation still reports lazy embed/SPLADE sessions. No branch captures establish the realized comparison.
- **Item 6:** Normalizing incidental checkout paths and excluding genuinely scoring-only bytes are sound principles; the missing acquisition/projection bindings need fixing.
- **Item 7:** Recording the supplemental encoder probe as a named gap is consistent with `E.md:255` and its separate D2 acceptance allocation. It does not prove request-time encoder acceptance.
- The general verdict logic preserves known failures; absent workloads, missing numeric bounds and mismatched acquisition identities cannot pass.

Verification: six JavaScript syntax checks passed. The selected Node test run reported **22 passes and one scratch-directory `EPERM` failure**; the broader run also encountered sandbox write restrictions. No builds, npm tests, stacks, edits, commits or pushes were performed.
## Review 2: confirmation (against 0e47164c0)

**I found two P1 evidence gaps. I would not clear this revision for an unattended acceptance run.** Reviewed `codex/lane-f-pr1` at `0e47164c0621fe2d68382fc53fb4e75cd136dc8c`; no files were changed.

1. **P1 — E4 can pass its wire clause with missing search-load evidence.**  
   At the window boundary, acquisition kills the current soak subprocess and accepts its interrupted completion ([e-acquire.mjs:133](scripts/jseval/lane-f/e-acquire.mjs:133)). Search-load counters reach an artifact only after ingestion finishes and evaluation writes its summary ([run.py:739](scripts/jseval/jseval/commands/run.py:739)). Thus that interrupted cycle can lose earlier, non-boundary timeouts/5xx.

   Clean admission calls then establish `no-timeout-or-5xx=true`; absent search summaries do not invalidate it ([e-run.mjs:689](scripts/jseval/lane-f/e-run.mjs:689), [e-run.mjs:738](scripts/jseval/lane-f/e-run.mjs:738)). Collector API failures—including settings restoration during cleanup—are also retained as collector errors rather than wire failures ([e456-live.mjs:195](scripts/jseval/lane-f/e456-live.mjs:195), [e456-live.mjs:202](scripts/jseval/lane-f/e456-live.mjs:202)).

   **Fix:** Persist search outcomes throughout each cycle, require coverage of every cycle, and project recorded non-boundary collector HTTP failures into the branch wire verdict. Test an interrupted final cycle containing an earlier 504; missing outcomes must be unmeasurable, and the recorded 504 must fail.

2. **P1 — Missing runtime model identity can still produce a valid pair identity.**  
   Capture errors are recorded and swallowed ([e-measured-inputs.mjs:61](scripts/jseval/lane-f/e-measured-inputs.mjs:61)). Finalization accepts an empty runtime-status population, ignores the captured manifest, and hashes the configured store with empty executed selections ([e-measured-inputs.mjs:39](scripts/jseval/lane-f/e-measured-inputs.mjs:39)). Configured external model paths enter selection strings but their files enter the inventory only through runtime references ([e-measured-inputs.mjs:18](scripts/jseval/lane-f/e-measured-inputs.mjs:18)).

   Consequently, both arms can pair despite missing executed-model proof; changed external weights can escape the metadata binding in that case.

   **Fix:** Require sufficient config/runtime receipts to identify every executed model, including metadata for configured external files. Keep session/provider observations informational, but fail identity finalization when required model identity is unavailable. Add missing-status and external-model counterexamples.

3. **P2 — Complete native-child crash proof remains unavailable.**  
   The new gate correctly refuses a crash-free pass without exit accounting ([e-crash-evidence.mjs:50](scripts/jseval/lane-f/e-crash-evidence.mjs:50)). However, acquisition expects `processExits` and `exitAccountingComplete` from stop output ([e-acquire.mjs:190](scripts/jseval/lane-f/e-acquire.mjs:190)); the producer returns neither ([dev-runner.cjs:3245](scripts/dev/dev-runner.cjs:3245)). Census crash detection tracks Engine/Head/Worker, not native-child disappearance ([e-crash-evidence.mjs:29](scripts/jseval/lane-f/e-crash-evidence.mjs:29)).

   **Fix:** Collect identity-bound exits through teardown for every owned JVM/native child. This currently blocks a measurable E4 zero-crashes pass; it does **not** create a false pass.

The nine-item confirmation follows. “Falsifying” means the asserted behavior contradicts the old implementation; I did not execute an old-code mutation. Newly introduced helper imports alone would not constitute behavioral proof.

| Item | Confirmation and evidence | Falsifying test / remaining risk |
|---|---|---|
| **1. Chunk starvation** | **Fixed.** Positive pending-chunk progress is independent of relative stage progress; branch gains cannot establish the relative pass. [e-run.mjs:393](scripts/jseval/lane-f/e-run.mjs:393), [e-run.mjs:555](scripts/jseval/lane-f/e-run.mjs:555). | [Test:29](scripts/jseval/lane-f/e-review.test.mjs:29) passed. The matching-primary/zero-chunks counterexample exposes the old omission. Missing chunk evidence remains unmeasurable. |
| **2. Boundary censoring** | **Fixed.** Observed terminal errors, duplicate done events and HTTP failures survive cancellation; cancellation cause is explicit. [admission-loop.mjs:1127](scripts/jseval/lane-f/admission-loop.mjs:1127), [e-agent-metrics.cjs:5](scripts/jseval/lane-f/e-agent-metrics.cjs:5). | [Test:45](scripts/jseval/lane-f/e-review.test.mjs:45) passed, including manually retained old boundary flags. Its terminal-error case would expose old scoring. No new censoring bypass found. |
| **3. E5 index deadline** | **Fixed.** Acquisition, reprojection and table evaluation apply the 13,600 ms bound. [e456-live.mjs:416](scripts/jseval/lane-f/e456-live.mjs:416), [e-run.mjs:783](scripts/jseval/lane-f/e-run.mjs:783), [e-run.mjs:561](scripts/jseval/lane-f/e-run.mjs:561). | [Test:72](scripts/jseval/lane-f/e-review.test.mjs:72) is substantive: 13,601 ms must fail even with a stored passing clause. Execution was scratch-write blocked. Missing/nonfinite duration cannot pass. |
| **4. Measurement identity** | **Partially fixed.** Actual corpus filenames/bytes, model metadata/selections and the acquisition-module closure are bound. [bulk_load.py:65](scripts/jseval/jseval/bulk_load.py:65), [e-measured-inputs.mjs:15](scripts/jseval/lane-f/e-measured-inputs.mjs:15), [e-pair-identity.mjs:75](scripts/jseval/lane-f/e-pair-identity.mjs:75). | [Test:85](scripts/jseval/lane-f/e-review.test.mjs:85) genuinely varies corpus bytes/names, model metadata and acquisition bytes, but was scratch-write blocked. It omits missing-runtime identity and driver gating behavior. Finding 2 remains. |
| **5. Projection identity** | **Fixed for the official table path.** Both arms must match each other and the current scorer; table prepares both populations before writing either. [e-run.mjs:517](scripts/jseval/lane-f/e-run.mjs:517), [e-run.mjs:893](scripts/jseval/lane-f/e-run.mjs:893). | [Test:112](scripts/jseval/lane-f/e-review.test.mjs:112) passed for mixed identities **and two equally stale identities**. [Integration test:152](scripts/jseval/lane-f/e-review.test.mjs:152) is substantive but scratch-write blocked. No identity-check bypass found. |
| **6. Candidate wire policy / E0** | **Fixed for E2/E3 and E0; incomplete for E4.** MAIN outcomes are baseline facts; BRANCH wire failures still fail. E0 requires both valid windows and finite admitted p95s. [e-run.mjs:502](scripts/jseval/lane-f/e-run.mjs:502), [e-run.mjs:635](scripts/jseval/lane-f/e-run.mjs:635). | [Test:182](scripts/jseval/lane-f/e-review.test.mjs:182) passed and would expose the old E0 contradiction. Finding 1 supplies the uncovered E4 case. |
| **7. Component budget** | **Fixed as a fail-closed evidence check.** Host ORT and every component require consumer accounting; flags and machine sums are separate. [e-memory-budget.mjs:6](scripts/jseval/lane-f/e-memory-budget.mjs:6), [e456-live.mjs:220](scripts/jseval/lane-f/e456-live.mjs:220). | [Test:203](scripts/jseval/lane-f/e-review.test.mjs:203) passed. Missing host ORT/accounting cannot pass. No accounting producer was added, so overnight capture alone cannot close this clause. |
| **8. Sampler start race** | **Fixed.** The driver waits boundedly for scope before launching the sampler. [e-acquire.mjs:14](scripts/jseval/lane-f/e-acquire.mjs:14), [e-acquire.mjs:118](scripts/jseval/lane-f/e-acquire.mjs:118). | [Test:211](scripts/jseval/lane-f/e-review.test.mjs:211) passed for delayed and absent scope. It tests the wait helper, not a real sampler launch; the call-site wiring is confirmed by inspection. Missing scope fails explicitly. |
| **9. Candidate crashes / teardown** | **Policy and JVM crash scanning fixed; complete acquisition unfinished.** Reports are scanned after stop, timing/source retained, and MAIN crashes are baseline facts. [e-acquire.mjs:185](scripts/jseval/lane-f/e-acquire.mjs:185), [e-run.mjs:526](scripts/jseval/lane-f/e-run.mjs:526). | [Test:223](scripts/jseval/lane-f/e-review.test.mjs:223) has real teardown/native-exit counterexamples but was scratch-write blocked. Its synthetic complete receipts have no current producer. Finding 3 remains. |

On the additional checks:

- **Acquisition hashing:** Deadlines, soak execution, subprocess/background handling and cleanup moved into hashed helpers. Acquisition-related decisions still in `e-run.mjs` are fixture eligibility ([45](scripts/jseval/lane-f/e-run.mjs:45)), plan construction ([118](scripts/jseval/lane-f/e-run.mjs:118)), dirty-source eligibility ([574](scripts/jseval/lane-f/e-run.mjs:574)), run prerequisites ([960](scripts/jseval/lane-f/e-run.mjs:960)), machine/corpus receipts ([966](scripts/jseval/lane-f/e-run.mjs:966)), and the expected shared-model location ([989](scripts/jseval/lane-f/e-run.mjs:989)). Plan outputs are hashed, but “all acquisition behavior is outside the driver” is too strong—particularly the injected dirty-source predicate. Move eligibility predicates into the hashed owner.
- **Projection check:** Neither matching stale identities nor mixed identities bypass it.
- **Branch failure policy:** Known workload violations and scanned crashes fail. E4 search/collector coverage and native-exit acquisition prevent confirming “every non-boundary failure, including teardown.”
- **E0 invalid MAIN window:** A false or missing window-validity clause prevents freezing. The passed E0 regression covers this; no relaxation was found.

Verification: **13 changed JavaScript syntax checks passed. The lane-F Node suite reported 87 passes and 25 sandbox-blocked failures**, including `EPERM` on scratch-directory creation. I did not work around those restrictions. I also inspected the retained MAIN Worker crash report and `hs_err` sources outside the design document. No Gradle, npm tests, stacks, commits or pushes were run.