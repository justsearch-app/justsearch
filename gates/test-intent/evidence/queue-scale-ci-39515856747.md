# Queue-scale PR 841 CI repair

Task t-20261020-540095; builder session 01a1562b-b2e9-7510-832f-04ea6ad6da28.
Candidate starts at merge commit 8151d8f81; actual merge-base with origin/main is
2ba7d810e4c5a5173d5182c3c5a7294aab49562a. No commits made by this repair.

## Static analysis

CI job 118947709464 reports BoundedHandoffTest.java:76 UnnecessaryFullyQualifiedName.
Changed only `new java.util.concurrent.atomic.AtomicInteger()` to `new AtomicInteger()`;
the existing import resolves the same type. No input, injection, assertion, selection or timeout
changed. PMD passed in one `js-gradle-slot ./gradlew ... --continue -PskipWebBuild=true
--console=plain` invocation for every Java source set this branch changed:

- `:modules:app-engine:pmdMain`
- `:modules:app-engine:pmdTest`
- `:modules:app-observability:pmdMain`
- `:modules:app-services:pmdMain`
- `:modules:app-services:pmdTest`
- `:modules:indexer-worker:pmdMain`
- `:modules:indexer-worker:pmdTest`
- `:modules:ui:pmdTest`
- `:modules:worker-core:pmdMain`
- `:modules:worker-services:pmdMain`
- `:modules:worker-services:pmdTest`

Result: BUILD SUCCESSFUL in 38s; 115 actionable tasks, 18 executed, 1 from cache,
96 up-to-date. Existing Java deprecation/unchecked warnings are not failures.

The unchanged evidence runner, its entire lifecycle under js-gradle-slot, refreshed only
BoundedHandoffTest (full class plus the named check) against 11a8ff327:
queue-scale-pmd-39515856747.json. Candidate: 15 tests passed, no skips; before:
15 ran, only the expected delivery-boundary assertion failed. Entry 28 retains its original
receipt for unaffected classes; entry 35 carries the extracted handoff check and fresh receipt.
All existing acceptance records remain byte-for-byte unchanged.

## Dead code

Reproduced CI's relevant web input steps: `npm ci --prefix modules/ui-web --audit=false`,
`npm --prefix modules/ui-web run knip:report`, then
`node scripts/governance/run.mjs --gate dead-code --mode gate`.
CI's runtime-config/module-dependency/advisory producers supply other gates, not dead-code;
no Vite build precedes this gate in Public claims.

Before fix: exit 1, dead-code fail, 6 findings. The sole failing growth finding is
subscriptionStrategy.ts, 2 -> 3 unused exports. The branch stopped importing tabularStrategy
from outside its module when indexing jobs moved to their own window reducer. Remove that
unused named export; the function remains used internally by generic resources and the
indexing reducer. This deletes the unused API without suppressions or baseline changes.

On the merge-base clone, with identical Node dependencies, knip lists only the existing
`_strategies` export and `StateData` type in this module. The baseline gate passes with five
rebalance-available findings. Candidate after fix: exit 0, dead-code pass, the same five
nonblocking shrink findings. Leave these and the pre-existing unused exports unchanged.
Related Vitest files subscriptionStrategy.test.ts and indexingJobsWindow.test.ts: 23 passed;
npm run lint and npm run typecheck: both exit 0.

## Quit investigation

CI job 118947709510 fails lifecycle.mjs:114, `quit finishes within the contract bound`.
Ran the exact narrowed dev-runner case with --repeat 5 on Linux on both revisions:

Merge base:

```text
PASS  adapter:dev-runner :: quit-deadline-is-the-contract-value
PASS  adapter:dev-runner :: quit-deadline-is-the-contract-value
PASS  adapter:dev-runner :: quit-deadline-is-the-contract-value
PASS  adapter:dev-runner :: quit-deadline-is-the-contract-value
PASS  adapter:dev-runner :: quit-deadline-is-the-contract-value
supervisor-conformance: 5/5 checks passed
```

Candidate:

```text
PASS  adapter:dev-runner :: quit-deadline-is-the-contract-value
PASS  adapter:dev-runner :: quit-deadline-is-the-contract-value
PASS  adapter:dev-runner :: quit-deadline-is-the-contract-value
PASS  adapter:dev-runner :: quit-deadline-is-the-contract-value
PASS  adapter:dev-runner :: quit-deadline-is-the-contract-value
supervisor-conformance: 5/5 checks passed
```

Both sets execute the unchanged assertion that elapsed quit time is <= 8,000 + 2,000 ms,
and the force deadline is 7,700 ms within 250 ms. Initial baseline attempts did not reach the
trigger phase: the fresh clone lacked CI's native bound-exec fixture. Adding dependencies did
not resolve that missing binary. Built the baseline's unchanged C source with CI's exact
`cc -std=c11 -O2 -Wall -Wextra -Werror` flags, then ran the clean five-case baseline above.
Those earlier startup failures are setup failures, not quit measurements.

There is no branch diff in scripts/dev, scripts/supervisor-conformance, native/bound-exec or
governance/supervision-contract.v1.json. The adapter sets
JUSTSEARCH_DEV_RUNNER_ENGINE_COMMAND to process.execPath + fake-engine.mjs and substitutes
an idle Node frontend. The fake engine explicitly has no index or ordered shutdown. This
Windows failure therefore does not execute the modified Java queue, bridge, SSE sender or
handoff drainer. No evidence attributes it to this branch; Windows itself was not reproduced
on this Linux machine. PR 842 was inspected: its lifecycle changes extend initial fixture
setup budgets, not this quit assertion. Do not claim it fixes this exact Windows failure.

The real Java quit path was also read: HeadAssembly closes the bridge and registry channels;
RemoteIndexingJobsBridge.stop fences the active subscription, shuts down reconnect executors
with shutdownNow and closes streams; EngineKnowledgeClient.closeTransport closes registered
flows; BoundedHandoff.close clears the queue and cancels the producer instead of draining;
SseStreamChannel.close retires listeners and calls shutdownNow, explicitly without waiting for
arbitrary observer callbacks. No new drain/termination wait is introduced in these paths.
EngineRoot's existing closeIndex completion check is unchanged. No shutdown product change
was warranted by this investigation.

## Cleanup and final checks

Every conformance child belongs to its harness run; teardown confirms no fake Engine survives.
JUSTSEARCH_HOME and JUSTSEARCH_DATA_DIR were task-local; no Java Engine, database or models
were used. The shared baseline clone and producer-owned before worktree were removed.
Machine ~/.config/justsearch is absent. git diff --check passes. Test-intent CLI exits 1 with
only acceptance-stale remaining; the existing nonblocking reconsider-rate warning is 4/4
reviewer disagreements among audited PRs.
