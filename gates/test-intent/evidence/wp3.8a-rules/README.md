# WP3.8a indexing-rules execution evidence

Candidate: `941ad6d493` (the product/test files remain unchanged). Before: `2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093`.
This is builder execution evidence. Independent acceptance remains outstanding.

## Runner adaptation

The [adapter patch](runner-machine-rules.patch) reproduces `tmp/wp38a-runner/run-evidence.mjs`
from `scripts/governance/gates/test-intent/run-evidence.mjs`. It follows the accepted
[HTTP lifetime adapter](../http-lifetime/README.md): the official selection, classification,
check-content digests, working-tree hashing and evidence self-digest are unchanged. The official
runner and its support modules are unchanged. Every JSON file identifies the patch and adaptation
in `producer.adapterPatch` and `producer.adaptation`.

The launcher clears inherited `JUSTSEARCH_*`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `JAVA_OPTS`,
`_JAVA_OPTIONS`, `DISPLAY` and `WAYLAND_DISPLAY`, and sets `CUDA_VISIBLE_DEVICES=-1`. It invokes
`env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node tmp/wp38a-runner/run-evidence.mjs ...`.
The whole script holds one managed slot, as this assignment's machine rules require; its child
Gradle commands use `./gradlew` without a nested slot. The slot wrapper appends its worker limit
to inherited `GRADLE_OPTS`. Exit 75 is retried after ten minutes, bounded at three hours.
No init script, client JVM options, `--no-daemon` or `--stop` are used. Both revisions already
contain the same per-task profile isolation in `JvmBaseConventionsPlugin.kt`.

The baseline is an owned shared clone at `$TMPDIR/before`, checked out detached at the exact
before revision. It has its own build folder and a real `modules/ui-web/node_modules` installed
with plain `npm ci`; neither dependency tree is shared or symlinked. Each invocation transplants
only the named candidate test file. Between invocations, the launcher restores that file from
the baseline (or removes it if absent there), so an incompatible new test cannot prevent another
file's checks from compiling. No production/build compatibility overlays or patches are applied.
The adapter verifies baseline revision and production/build sources before transplanting tests.
The clone is retained during collection and removed after diagnostic capture.

For working-tree hashing, the launcher sets `GIT_OBJECT_DIRECTORY=$TMPDIR/wp38a-objects` and
`GIT_ALTERNATE_OBJECT_DIRECTORIES` to the candidate's existing object directory. The official
hashing function uses its temporary index and writes only to that owned object directory;
source Git metadata is untouched. Git clone/checkout metadata writes occur only in the clone.

## Reading and reproducing the results

Each JSON file names the exact checks, content digests, commands, candidate results and baseline
classifications. `integrationTest` maps to `:modules:ui:integrationTest` and that source set's
JUnit XML through the official `gradleTarget`; no substitute path or task is used.

After reconstructing the adapter by applying the patch to a copy of the official runner, prepare
an owned baseline clone and independent dependencies as above. With the cleaned environment,
owned Git object directory and read-only alternate, invoke the slot-wrapped runner using:

```text
--out <evidence path> --before 2ae5d5f23dfa7cfb5eeb901ad9c9616c6236b093
--before-root <TMPDIR> --check <id> [--check <id> ...]
```

Take check IDs from that evidence file. Restore the transplanted baseline test source before
moving to the next file. Do not transplant all the candidate tests together: new production APIs
are absent at the baseline and would prevent compatible checks from compiling.

Files can contain both repair and preservation checks; the changeset selects each subset with
its own entry. For new-API controls, baseline compilation diagnostics remain recorded and the
entry explicitly makes only an after-only claim. Compile failures establish no fail-before.
The official runner exits 1 whenever any baseline check does not fail by assertion, including
preservation passes and compile limitations; these real exit codes are retained.

The `AiPackImportServiceTest.json` and `EngineForgetInProcessTest.json` files are diagnostic
reproductions of the four known failures, excluded from all `newChecks` and successful evidence
claims. The worktree report `tmp/verify/wp3.8a-evidence-report.md` lists every check's observed
outcomes, failure kinds, changeset restructuring, validation and final gate output.

Shared resources touched: managed Gradle slots/cache and npm cache. The checks own their temporary
profiles, loopback fixtures, child processes, checkout, dependency trees, build output and logs.
The owner's dev stack, data, display, clock, time zone and power settings are untouched. No manual
process stop, independent acceptance, commit, publication or GitHub issue action is performed.

## Execution summary

All 33 selected candidate checks passed without skips. Baseline classifications: 10 assertion
failures establishing repairs, 5 passing preservation checks, and 18 compile-limited controls
recorded as after-only. Both full-bootstrap integration methods passed through the actual
integrationTest task. All four specified known failures reproduced on both revisions and are
excluded from passing evidence. The official validator accepts every evidence-bearing entry;
the final test-intent gate exits 1 with only `test-intent/acceptance-missing`.
The owned baseline clone was removed after diagnostic capture.
