# HTTP request-lifetime execution evidence, round 2

Candidate: `ee92a253a795b7a49a3bcf374042edc12fadbf9b`. Before: main `784f94fd049b9b2c39708437fa262c32a58c32b1`.

This is builder evidence, not independent acceptance. Production code and `scripts/governance/` are unchanged; changes remain uncommitted. `.agents/preflight` is absent.

The [adapter patch](runner-machine-rules.patch) reproduces `tmp/http-lifetime-2/run-evidence.mjs` from the official runner. It follows main's accepted stale-source-deletion adapter: unchanged official selection, assertion classification and digest algorithms, Gradle through the mandated slot wrapper, no init script, stripped display/JVM/JustSearch variables, CUDA disabled, and a retained TMPDIR shared clone with independent plain `npm ci`. Both revisions already have identical per-task test isolation conventions. `producer.adapterPatch` and `producer.adaptation` identify this adaptation in every evidence file.

The initial proxy attempt ran its checks but failed while hashing the tree because the source Git object directory is read-only. The successful runs keep the official hashing code and set `GIT_OBJECT_DIRECTORY` to an owned temporary directory, with the source objects as a read-only alternate. No source Git objects were written.

## Selected evidence

| Evidence file | Candidate cases | Before cases |
| --- | ---: | --- |
| [uiux-cutover-stream-proxy-repair.json](../uiux-cutover-stream-proxy-repair.json) | 8 passed | 8 assertion failures |
| [uiux-cutover-stream-proxy-preservation.json](../uiux-cutover-stream-proxy-preservation.json) | 2 passed | 2 passed |
| [uiux-cutover-stream-http-repair.json](../uiux-cutover-stream-http-repair.json) | 15 passed | 15 assertion failures |
| [uiux-cutover-stream-http-preservation.json](../uiux-cutover-stream-http-preservation.json) | 3 passed | 3 passed |
| [uiux-cutover-stream-owner-controls.json](../uiux-cutover-stream-owner-controls.json) | 9 passed | current-only controls |

All 37 selected candidate cases passed without skips; all 23 repair cases fail before with assertions, and all 5 preservation cases pass before. The Java repair entry has 10 named methods expanding to 15 cases. No before claim is made for the 9 current-owner controls. Main lacks the new owner helper type; the existing changeset records that compatibility limitation.

For preservation evidence, the official runner returns **exit 1** because `--before` requires assertion failures for its overall success condition. Both candidate and before preservation tests passed; these are deliberately preserved real exit codes, not failed preservation assertions.

## Classification checks

- `candidate-nvidia`: 1 test; 1 passed, 0 failed, 0 skipped. [Raw log](candidate-nvidia.log).
- `before-ui`: 2204 tests; 2201 passed, 2 failed, 1 skipped. [Raw log](before-ui.log).
- `candidate-status`: 1 test; 0 passed, 1 failed, 0 skipped. [Raw log](candidate-status.log).

The installed-NVIDIA availability test passed focused on the candidate and failed in the full before suite. It is pre-existing or load-dependent under both conditions in the round-2 decision. The before suite's only other failure is StatusIsolation frontend refresh. Its candidate-focused failure and before failure both report `Cannot find module 'esbuild'`; the test, frontend source and dependency manifests have identical baseline/current bytes. Neither test was modified. Failure types/messages, source digests and exact command arguments are in [observations.json](observations.json).

## Commands and checks

The [execution observations](observations.json) list every round-2 acceptance command, real exit code, timing, build summary, selected evidence counts and available JUnit counts. Evidence files also retain each exact child command and its exit status. Both independent `npm ci` commands exited 0 and each installed 283 packages; npm reported one moderate dependency advisory on both revisions. There are no dependency changes in this round.

The runner passed `node --check`; applying the retained patch to the official runner reproduced the executed adapter byte for byte. Every evidence file passes the official evidence validator. The final command `node scripts/governance/gates/test-intent/cli.mjs check --base origin/main` exits 1 with **two problems: missing independent acceptance and an unrelated uncovered IngestPreparationBoundaryTest difference**. During execution, origin/main advanced from the assigned `784f94fd04` to `a23a9186ea01` (`test(app-agent): bind the socket fixture under a short path`, #966). The package and governance sources remain unchanged. The supplemental `--base 784f94fd04` gate exits 1 with **only missing independent acceptance**. Complete outputs: [required origin/main gate](gate-final.log), [assigned-main gate](gate-assigned-main.log). No merge, ref adjustment or unrelated test change was made.

The accepted round-1 module, compile/PMD and frontend suite results are carried forward, not rerun here: app-services 4,293 tests (11 skipped), app-engine 774 (9 skipped), repository test/integration compilation and PMD passed, frontend typecheck/lint passed, and 7,897 frontend unit cases passed. Their original command records remain in `build/reports/http-lifetime-checks/check-summary.json`. This round adds no product changes.

## Resources and remaining work

Shared resources touched: managed Gradle slots/cache and npm cache; the existing availability test invokes host nvidia-smi with CUDA disabled. All checkouts, dependency trees, test profiles, loopback fixtures, builds and logs are owned by these checks. No owner's service, dev stack, browser or desktop was used; no process was manually stopped.

Independent acceptance and handling of the concurrent main-base advancement are left to the orchestrator/verifier. The already-declared static middleware and Thread.start injection gaps remain unverified. No commits, publication, GitHub issue action or independent acceptance were performed.
