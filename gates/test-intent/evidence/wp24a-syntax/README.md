# WP2.4a syntax execution evidence

Candidate: `f7118134b867dba1cba64aaf69012f93dba5a8e3`. Before:
`e2a17382308201413adb2c8641cd1e9344f7e226`. Builder evidence only; independent
acceptance remains pending. Production code, tests, expectations and official governance scripts
were unchanged during this assignment. `.agents/preflight` is absent.

All 23 checks requested by the brief executed and passed on the candidate, with no skips.
On the before revision, 16 failed by assertion for the reason under test and seven passed.
Six additional existing Worker checks passed on both revisions. No selected check needed
after-only evidence: even the new retrieval test file compiled and executed before.

| Evidence | Candidate, unique cases | Before, unique cases | Runner exit |
| --- | --- | --- | --- |
| [Java](java.json) | 16 passed | 8 assertion failures, 8 passed | 1 |
| [Vitest](ui.json) | 13 passed | 8 assertion failures, 5 passed | 1 |

The Java evidence also names the full Worker class, whose seven cases include the separately
named X5 repair. That duplicate records the six existing controls without changing official
selection. Do not double-count the X5 case. Both runners return 1 because their unchanged
overall success condition requires every before check to fail an assertion; preservation passes
therefore make the aggregate exit nonzero. All candidate child commands exit 0; the before
commands exit 1 for the recorded assertion failures.

## Java adapter

The [patch](runner-machine-rules.patch) reproduces `tmp/wp24a-runner/run-evidence.mjs` from
the official script. It follows the accepted [HTTP lifetime](../http-lifetime/README.md) and
WP2.3a filter adapters. Selection, classification and digest algorithms are unchanged. Each
Gradle call runs through `env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew`, without
an init script. Both revisions already isolate test profiles in `JvmBaseConventionsPlugin`.
The adapter strips display, JVM-option and JustSearch environment overrides, disables CUDA,
retains inherited GRADLE_OPTS, and retains the precedent's ten-minute exit-75 retry interval
and three-hour limit. No retry occurred. Java evidence identifies the adapter in
`producer.adapterPatch` and `producer.adaptation`.

The before tree is an owned TMPDIR shared clone, with its own build directories. Only the
candidate's three Java test files are transplanted; the production and build files retain
baseline bytes. No Java check uses a symlinked node_modules directory. The official tree-hash
algorithm uses an owned `GIT_OBJECT_DIRECTORY`, with the source objects as a read-only
alternate, to avoid writing objects or index metadata into the source repository.

From the source repository root:

```bash
mkdir -p tmp/wp24a-runner
patch --output=tmp/wp24a-runner/run-evidence.mjs scripts/governance/gates/test-intent/run-evidence.mjs < gates/test-intent/evidence/wp24a-syntax/runner-machine-rules.patch
node --check tmp/wp24a-runner/run-evidence.mjs
```

Use an owned, initially empty TMPDIR; create `$TMPDIR/before` with
`git clone --quiet --shared --no-checkout . "$TMPDIR/before"`, then detach it at the before
revision. Set a private Git object directory and the source objects as its read-only alternate.
Run the adapter with `--out gates/test-intent/evidence/wp24a-syntax/java.json`,
`--before e2a17382308201413adb2c8641cd1e9344f7e226 --before-root "$TMPDIR"`, and every Java
ID from [selected-checks.json](selected-checks.json) as a separate `--check` argument.
Java-only execution needs no npm installation. Remove the owned clone after inspecting it.

## Official Vitest runner

Vitest used the official runner byte for byte, in default mode without
`--copy-node-modules`. [commands.json](commands.json) records the exact invocation. Because
the official script normally registers a before worktree, its candidate root was a disposable
shared clone detached at the same candidate revision. A private Git launcher substitutes an
owned shared clone for that lifecycle. Only temporary clones' metadata was modified.
Its test and runner bytes match the source candidate.

Both candidate and before dependency folders were independently populated by plain `npm ci`.
A private PATH launcher named `git` forwards ordinary commands to the original absolute Git
path. For the official `worktree add --detach <path> <revision>` request, it instead runs
`git clone --quiet --shared --no-checkout . <path>`, detaches that clone at the requested
revision, and runs plain `npm ci` in its `modules/ui-web` folder. Every child exit status
propagates. It accepts only direct children of the owned `--before-root` whose names begin
`test-intent-before-`. The official `prepareNodeModules` step then finds a real directory
and creates no link. On `worktree remove`, the launcher removes only that owned shared clone.
The [clone lifecycle log](ui-clone-lifecycle.log) records these real commands and results;
the official evidence retains its generic `<before-worktree>` label.

A private PATH launcher named `npx` executed the original absolute npx path
with `"$@" --configLoader runner --maxWorkers=2`. This enforces the required loader and two-worker
limit while leaving the official runner unchanged. Both trees run the default Vitest selection.
Clear inherited display/JVM/JustSearch overrides, disable CUDA and retain GRADLE_OPTS when
launching the runner. Pass the UI IDs from selected-checks.json, the same `--before` revision,
and an owned `--before-root`. The evidence retains the official command string before the
PATH launcher appends these flags. No Java check used this harness.

The candidate and before npm installations each exited 0 (283 packages). An initial diagnostic
used a clone-local checkout hook and private worktree; it was replaced with the final shared-clone
lifecycle to follow the brief's checkout rule literally. No initial worktree remains. The final
before shared clone was removed through the official runner's cleanup request. All remaining
owned clones were removed at delivery. The launchers used for the final run are retained in
`tmp/wp24a-runner/` for inspection and reproduction alongside the command records.

## Classification and adaptations

The changeset separates additions from modifications to retained oracles. Its two Adaptation
entries describe the existing request contract's wire re-encoding and the Worker's extra fixture
metadata. A separate New/repaired entry covers the added `querySyntax: search` expectations in
the two existing request tests. Added Head, Worker, draft and other-mode cases remain New.
Preservation entries explicitly set `repaired: false` and cite evidence with `--before`.
All delegated-choice source objects and quotes retain their original contents.

The Worker controls use `@DisplayName`. An initial diagnostic selected their Java method names:
Gradle ran all seven cases, but the unchanged official selector reported six as `not-found`.
Its generic classifier then called those absent selections `compile` because the successful
build output mentioned `compileTestJava`. No compilation failed. The final run uses the real
test class ID and records all seven cases. [Before case XML](worker-before.xml) and
[candidate case XML](worker-candidate.xml) retain the JUnit results with system-out/system-err
logs omitted; all six existing controls pass on both. The initial diagnostic remains in
`tmp/wp24a-runner/java-initial-diagnostic.*`, outside the final evidence.

The assertions before cover broader SIMPLE matches instead of field-local phrases/exclusions,
missing Head syntax/policy dispatch, absent invalid-query rejection, Worker result count 3
instead of 1, missing inline Engine reason, unsuppressed automatic errors, absent pending-count
state and absent Search request discrimination. None is labelled fail-before because of a
compile, setup, environment or missing-wiring exception.

## Checks and resources

Exact commands, statuses and UTC times are in commands.json. Selected test commands and failing
before output also remain in the D4 files and [Java](java-runner.log) / [Vitest](ui-runner.log)
runner logs. The official evidence validator, adapter syntax/patch reproduction, and final
assigned-base gate results are recorded in [validation.log](validation.log) and
[gate-final.log](gate-final.log). The final gate has only missing independent acceptance.

Shared resources touched: managed Gradle slots/cache, npm registry/cache and CPU/RAM. All
checkouts, build folders, dependencies, fixtures and logs belonged to these checks. No Engine,
dev stack, owner service/data, desktop/display, GPU inference, commit, push, publication or
GitHub issue action was used. No process was manually stopped.
