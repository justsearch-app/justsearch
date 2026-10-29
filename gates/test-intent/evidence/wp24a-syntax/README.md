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

## Search syntax × restrictions after rebase

[restrictions-java.json](restrictions-java.json) records seven integration preservation
checks on the working tree based on `51bba4727a9168d219c9fd3f1d6990cbc2369fa1` and
on that exact before revision. All seven executed and passed on both; no production
gap was found and no check claims a repair. The six Worker checks use actual
planning/capture/Lucene retrieval, a one-result budget, stronger crossed decoys,
individual and combined folder/date/size restrictions, counts/facets, inclusive
bounds, unknown metadata, zero size, empty intersections and plain Search/SIMPLE
preservation. The entry-handler check pins restriction validation before syntax
validation when both inputs are invalid.

This uses the same adapted Java runner reproduced from
`runner-machine-rules.patch`, without an init script. The runner exits 1 because
its generic fail-before condition requires assertion failures, whereas these are
preservation passes; all four child Gradle commands exit 0. The exact invocation,
logs and installed-Engine requests/responses are linked from
`tmp/verify/wp2.4a-integration-report.md`. The before checkout is an owned shared
clone at `$TMPDIR/before`; test files alone are transplanted. A private object
directory prevents the tree-hash computation from writing Git objects into the
source repository. Independent acceptance of the changed digest remains pending.

## Filters UI merge (WP2.4a)

The merge refresh uses the official Vitest runner unchanged, `TZ=UTC`, with
`--configLoader runner --maxWorkers=2` supplied by the private npx launcher.
`ui.json` retains its exact 13 check IDs and before SHA
`e2a17382308201413adb2c8641cd1e9344f7e226`: 13 pass after, eight assertion failures
and five preservation passes before (aggregate runner exit 1 for the preservation passes).
The shared mounted-view file also required refreshing `../wp2.3b-filters.json`
and `../wp2.3b-unchanged-fields.json` with their recorded IDs and before SHAs;
all 19 pass after and fail by assertion before.

`combined-ui.json` adds four mounted-view syntax × Filters checks. All four pass
after and fail by assertion on Filters main `8363d7b9386b796c38bb7ef777df37a2c4ad1b2f`,
where Search syntax is absent: three fail on the missing `querySyntax: search`
and one on the missing inline Engine syntax reason. Chip removal reruns the
unchanged syntax; delayed responses and error-body reads cannot override new restrictions.
Sources remain delegated-choice under the original quote and location.

The source merge remains uncommitted. The official runner executed in an owned
shared-clone snapshot of its working files, so its tree hash and the check-file
digests identify those bytes. The private Git launcher translated before-worktree
creation/removal into owned shared clones and plain `npm ci` in each real
dependency folder; candidate dependencies also used plain `npm ci`. Only temporary
clone Git metadata was written. All owned candidate/before clones were removed.
The launcher source, exact invocations, lifecycle log, suite logs and real-system
requests/screenshots are linked from `tmp/verify/wp2.4a-merge-report.md`.
Independent acceptance of both refreshed changesets remains pending.
