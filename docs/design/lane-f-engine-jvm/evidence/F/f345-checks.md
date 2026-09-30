# Stage F F-3/F-4/F-5 verification - 2026-09-30

Worktree: `F:/justsearch-public/.claude/worktrees/lane-f-f345`, branch
`codex/lane-f-f345`, base `1798bf91cb95e9284f34da9694505b473674adb7` plus
the uncommitted diff. Windows, Node v24.12.0, Python 3.13.
No Gradle, backend, commit or push was run. D2 remains post-merge work; the Engine runs G1.

## Implementation and boundaries

- F-3: writable hand-authored Claude dev-stack/module-arch text corrected; eight
  allow-listed current sentences now gate both trees. Self-test proves red on a
  divergence in either tree and green after restoration. `.agents` is explicitly
  read-only in this sandbox and Windows denies writes; its six skill edits are
  provided as a patch rather than falsely claimed applied. F-3 remains incomplete.
- F-4: removed jseval's orphan-process scan, lock parser, kill helpers and their
  obsolete sweep tests. Directory deletion still attempts all entries, retries
  failures and fails closed on survivors. Tree stop still retires the run record
  after stopping its owned tree. Regressions cover no process scan, transient
  deletion, persistent deletion failure, real Windows locked-file failure, and
  record ordering. Health reads schema-2 API/index/generative slots with component
  READY/UNAVAILABLE states; aggregate lifecycle states remain unchanged. Legacy
  process-slot rejection and exact offline-generative acceptance are tested.
- F-4 subagent fallback was already retired: `subagent-guide.mjs:48-58` reads
  AGENTS.md directly and reports missing instructions instead of copying a fallback.
  The new regression asserts invariant 1's exact wording, excluding Markdown bold.
- F-5: Engine-log sandbox instruction, all eight retained WORKER owner notes
  (five production stores plus three newer harness stores), module-path register
  notes, and retired execution proto surface corrected. No store identity changed.
  Readiness count wording and supervision retirement labels were already current.
  API contract map was verified, not edited. GPU holder rename remains a Java follow-up.

## Source proof

- `scripts/dev/dev-runner.cjs:1007-1038`: registered children are read from
  runtime/manifest.json; PID liveness, creation time and executable identity gate
  termination. Terminal recovery calls this at :2703; stopRun calls it at :3138.
  This is the owner of registered-child cleanup, not jseval's former broad scan.
- `modules/app-api/src/main/java/io/justsearch/app/api/lifecycle/LifecycleSnapshotV2.java:10-47`
  and `modules/core/src/main/java/io/justsearch/core/component/ComponentState.java:5-12`:
  schema-2 slots and component state vocabulary. Matches
  `docs/reference/api-contract-map.md:162-194`.
- `modules/ui/src/main/java/io/justsearch/ui/api/routes/BootRoutes.java:82-106`:
  retained head/worker/brain selectors, worker 501. Matches API map :321.
- Runtime holder follow-up: `RuntimeGpuLease.java:24-28,65`,
  `RuntimeStatus.java:168`, `RuntimeStatusTest.java:48`,
  `bootstrap/phases/BootstrapProjections.java:124`, and
  `InferenceRuntimeView.java:41,58` must change
  consistently with the status projection and FE contract when WORKER becomes INDEXING.
  No Java or frontend source was edited in this batch.

Prepared Codex edits: `tmp/f3-codex-skills.patch` (retain 14 days). Applying it
requires a session with write access to `.agents`; no ACL was changed here.
The complete current canonical bodies and manual header corrections cover six
skills. Parent verification: `git apply --check --whitespace=error-all` passes;
applying to the writable `tmp/f3-final-proof` fixture passes the new content gate
and the scoped Codex residue checker. The actual protected tree remains unchanged.

## Checks

Full logs: `tmp/f345-checks/` in this worktree; retain for 14 days or until the
next F batch records its replacement proof. Initial suite outputs are preserved.

| Check | Result |
| --- | --- |
| Scoped residue checker | FAIL, 229 lexical hits; every hit classified in f345-residue.md; 17 Codex skill hits remain blocked |
| Codex agent parity | FAIL, new content assertion detects the protected Codex installer divergence; other eight checks pass |
| Parity self-test | PASS, exit-1 divergence in each tree, restored exit 0 |
| skills-sync --check | PASS, five skills / nine sources |
| agent-analytics run-all-tests | FAIL, 52/61 files at initial suite run; new invariant test subsequently corrected and all 10 focused checks pass; eight unrelated files remain red |
| governance run-all-tests | FAIL, 30 passed / one failed; no-TypeScript fixture resolves ancestor TypeScript despite withTypeScript=false |
| check-store-recoverability | PASS, identities preserved |
| regen-all --check | FAIL at notices: build/reports/licenses/third-party-licenses.json absent; requires Gradle license inputs, which user forbids producing |
| regen-all --check --except notices | PASS, all seven hermetic sets, including shape-handlers beyond notices |
| python -m pytest -q -p no:cacheprovider | FAIL, 3614 passed / 25 skipped / 63 failed, 467.17 seconds; full suite before final component-state correction |
| Final affected jseval tests | PASS, 156 tests including final schema-2 component states and legacy-slot regression |
| npm run lint:scripts | NOT RUN, root node_modules absent |
| git diff --check | PASS |

Environment failures are separate from implementation proof: 61 pytest failures
are denied writes to Inspect's trace file under LOCALAPPDATA; release's outside-repo
path test receives a writable temp directory inside this repo; ui-serve's native
PID check fails. The initial default pytest temp directory was also denied; tests
were rerun with a writable worktree basetemp. Seven analytics files require native
process/port enumeration unavailable in this sandbox; compact-save assumes a clean
worktree and fails on this authorized uncommitted diff. The new invariant regression
initially compared Markdown-bold text to plain text; its final focused result is green.

The full discipline kernel, Java/status-schema and frontend verification for the
holder rename were not performed: the rename was not applied and Gradle is forbidden.
These results do not establish stage F completion.

## Files

- .claude/skills/dev-stack/SKILL.md
- .claude/skills/module-arch/SKILL.md
- docs/design/lane-f-engine-jvm/stages/F.md
- governance/execution-surfaces.v1.json
- governance/logic-seams.v1.json
- governance/operation-surfaces.v1.json
- governance/runtime-state.v1.json
- governance/sandbox-coverage.v1.json
- governance/store-recoverability.v1.json
- scripts/agent-analytics/hooks/subagent-guide.test.mjs
- scripts/ci/check-codex-agent-parity.mjs
- scripts/dev/dev-runner.cjs
- scripts/dev/lib/process-record.cjs
- scripts/dev/test-dev-mcp-hot-reload.mjs
- scripts/jseval/916_chunk_sweep.py
- scripts/jseval/jseval/backend.py
- scripts/jseval/jseval/cadence.py
- scripts/jseval/jseval/counterfactual.py
- scripts/jseval/jseval/duplicate_prevalence_production.py
- scripts/jseval/jseval/metrics_reader.py
- scripts/jseval/jseval/provenance.py
- scripts/jseval/jseval/readiness.py
- scripts/jseval/jseval/search_load.py
- scripts/jseval/jseval/ui_check.py
- scripts/jseval/jseval/ui_fixtures.py
- scripts/jseval/lane-f/fixture-cycle.sh
- scripts/jseval/serve-eval-backend.py
- scripts/jseval/tests/test_backend.py
- scripts/jseval/tests/test_cadence.py
- scripts/jseval/tests/test_duplicate_prevalence_production.py
- scripts/jseval/tests/test_preflight.py
- scripts/jseval/tests/test_run_register.py
- docs/design/lane-f-engine-jvm/evidence/F/f345-checks.md
- docs/design/lane-f-engine-jvm/evidence/F/f345-residue.md
- scripts/ci/test-check-codex-agent-parity.mjs
