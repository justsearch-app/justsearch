# WP2.3a filter test-intent execution evidence

Candidate: `ae33f45a2e429c6a63b52ca8f971d12d408c2419`. Before: `b744db87446b847edb306eb4d12c92781ac5b4dd`. Builder execution evidence only; independent acceptance remains pending.

The [adapter patch](runner-machine-rules.patch) reproduces `tmp/wp23a-runner/run-evidence.mjs` from the official runner. It follows the accepted [HTTP lifetime adapter](../http-lifetime/README.md) and stale-source-deletion precedent: official result selection, failure classification and digest algorithms are unchanged; Gradle children use `env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew`, without init scripts. Both revisions already isolate test profiles per task. Display, JVM and JustSearch overrides are stripped, CUDA is disabled, inherited GRADLE_OPTS is retained, and Vitest uses at most two workers. Exit-75 retries use the precedent's ten-minute interval and three-hour retry limit. No exit 75 or retry occurred.

The before state is an owned TMPDIR shared clone, detached at the assigned base, with only candidate test files transplanted. Production/build sources remain baseline bytes. Its real dependency directory was installed by plain `npm ci` (exit 0, 283 packages); it shares neither node_modules nor build folders with the candidate. The clone is removed after execution. The official working-tree hash code uses an owned `GIT_OBJECT_DIRECTORY` with source Git objects as a read-only alternate, so it writes no objects or index into source repository metadata. Each D4 JSON file carries `producer.adapterPatch` and `producer.adaptation`.

| Evidence | Candidate | Before | Runner exit |
| --- | --- | --- | --- |
| [Repairs](../wp2.3a-filters.json) | 20 passed | 20 assertion failures | 0 |
| [Preservation](../wp2.3a-preservation.json) | 2 passed | 2 passed | 1 |
| [Domain validation](../wp2.3a-domain-validation.json) | 2 passed | after-only; diagnostic compile failure | 0 |
| [Entry validation](../wp2.3a-entry-validation.json) | 1 passed | after-only; diagnostic classified exception | 0 |

Every selected candidate check executed with zero skips. Preservation's exit 1 is the official runner's expected overall result when `--before` checks pass instead of failing assertions; both child test commands passed on both revisions.

`SearchRestrictionsTest` cannot compile before: the new `SearchRestrictions` type is absent (nine cannot-find-symbol diagnostics). [Raw compile output](before-domain-compilation.log) records the actual failure; this is not assertion fail-before evidence.

`RestrictionValidationEntryTest#searchAndAskRejectTheExactValidationMatrixBeforeAdmission` executed before, but the official classifier returned `exception`: Mockito `ArgumentsAreDifferent` reported the Search handler's 500 `INVALID_STATE` ("Search requires the shared publication owner") instead of the expected 400 `INVALID_REQUEST`. That run does not establish that the full validation matrix failed for its intended reason. [Raw task output](before-entry-validation.log) and [JUnit XML](before-entry-validation.xml) retain it. The after-only entry explicitly disclaims repaired/assertion-fail-before proof. No classifier, test, setup or production expectation was changed to manufacture it.

The previous Adaptation entry for `QueryFilterBuilderTest#buildChunkFilterQuery_resolvesDateRangeThroughParent` is now New/repaired: the changed expected outcome (ignored date to parent-resolved date) does not retain the whole oracle, as Adaptation requires. Its exact check fails before by assertion and passes after. New preservation entries set `repaired: false`; the two after-only entries also set it false and explain their limitations. All delegated-choice source objects, including quotes, remain unchanged.

## Reproduction

From the repository root, apply the patch to an outside-scripts copy:

```bash
mkdir -p tmp/wp23a-runner
patch --output=tmp/wp23a-runner/run-evidence.mjs scripts/governance/gates/test-intent/run-evidence.mjs < gates/test-intent/evidence/wp2.3a-filters/runner-machine-rules.patch
node --check tmp/wp23a-runner/run-evidence.mjs
```

Create an owned shared clone at `$TMPDIR/before` using `git clone --quiet --shared --no-checkout . "$TMPDIR/before"` and detach it at the before revision; run plain `npm ci` in its `modules/ui-web` folder. Keep CUDA disabled, clear inherited display/JVM/JustSearch variables and retain GRADLE_OPTS. Set an owned Git object directory and the repository object directory as its read-only alternate. Invoke the adapted runner with each file's `checks[].id` as `--check` arguments and that file as `--out`; repair and preservation also need `--before b744db87446b847edb306eb4d12c92781ac5b4dd --before-root "$TMPDIR"`. Domain and entry files are after-only. Exact executed test commands, statuses and failing output remain in the D4 JSON files. Remove the owned before clone when finished.

The adapter passes `node --check`, and patch application reproduces its bytes exactly. `.agents/preflight` is absent. Official evidence validation and the final gate outcome are recorded below after the final check. No product/test/governance-script change, commit, publication, issue action, Engine, dev stack, owner app, display/desktop access or GPU inference was involved. Shared resources touched: managed Gradle slots/cache and npm cache.

## Final checks

Official evidence validation: 11 entries, zero errors. The assigned-base gate exited 1; see [complete output](gate-final.log). Only independent acceptance is missing. The owned before clone was removed after all runners completed; no external checks remain pending.
