---
schema: test-intent.v1
task: eval-p2-harness-move
author-role: builder
author-session: eval-p2-test-intent-builder-20261102
---

# Test-intent entries

The owner quotations below are the verbatim words relayed in this assignment from the
original move task. The package-coverage decision from independent review and the settled
lifecycle-input scenario are explicitly recorded as delegated agent choices.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaExchangeLeaseScanTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "Eval LLM calls reach the chat model through the Engine's /v1 proxy rather than llama-server's own port; the existing exchange lease, proxy admission and accounted-caller rules also remain in force.",
      "whySurvives": "The direct-port predicate, line-by-line observation and empty-violations expectation are unchanged. The scan still includes scripts/jseval/jseval and now also includes scripts/jsharness/jsharness, keeping moved code inside the same safeguard. The added package-inventory assertion makes coverage stricter: every scripts/ child containing pyproject.toml must have its same-named Python package in EVAL_PACKAGES. No lease checks, preconditions, expected outcomes or execution conditions are weakened.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i would accept your recommendation",
          "location": "task eval-p2-harness-move user turn accepting the recommendation"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, autonomouslky",
          "delegationLocation": "task eval-p2-harness-move user turn granting autonomy",
          "delegationScope": "Carry out the accepted recommendation to move UI checks and process registers out of jseval unchanged, preserving existing UI-check invocations and their safeguards.",
          "choice": "An independent review found that moving code into scripts/jsharness would let it escape the existing Engine /v1 proxy scan. Within the owner's delegated move, the orchestrator chose to scan both Python packages and require the scan's package list to match every Python package under scripts/. This coverage choice is an agent choice; the inventory assertion is not quoted as owner wording.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/system-tests/build.gradle.kts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename"
      ],
      "whatMoved": "The lifecycleIntegrationTest input fingerprint follows the UI harness package from scripts/jseval/jseval to scripts/jsharness/jsharness with the same **/*.py and **/*.json patterns. It also fingerprints scripts/lib/python-path.cjs, which scripts/supervisor-conformance/bulk-fault-scenario.mjs imports to choose the checkout for python -m jsharness ui-shot. Existing UI source inputs, scenarios, observations, assertions, tags, filters, fault injection and test execution conditions are kept. The shell helper python-path.sh is not a declared input of this task; this Node lifecycle harness directly uses the .cjs helper. The fingerprint narrows from all of scripts/jseval/jseval (167 files, mostly measurement code the harness never ran) to the 26 files of scripts/jsharness/jsharness; every ui_* file fingerprinted before is fingerprinted at its new location, and jsharness imports nothing from jseval.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "i would accept your recommendation",
          "location": "task eval-p2-harness-move user turn accepting the recommendation"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly, autonomouslky",
          "delegationLocation": "task eval-p2-harness-move user turn granting autonomy",
          "delegationScope": "Carry out the accepted recommendation to move UI checks and process registers out of jseval unchanged, preserving existing UI-check invocations and their safeguards.",
          "choice": "Preserve the settled must-not-break scenario: Existing UI-check invocations behave the same from the new location, including the system-tests installed harness declaring the package as inputs. Follow the moved package and its CommonJS import-path helper in lifecycleIntegrationTest inputs so edited harness code or checkout selection cannot reuse stale proof. This scenario and its input adaptation are agent choices within the delegated move, not owner-words.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Execution evidence

Observed on 2026-11-02 UTC (2026-11-03 local). Every command below exited 0. This is
preservation/structural-rule evidence, not a defect reproduction; the new inventory check does
not exist on the before-state, so no fail-before is claimed for it.

### LlamaExchangeLeaseScanTest

`--rerun` forces execution so the XML is from this run, not a cached or up-to-date result.

Candidate 0281e8ee5100e01d2f01db11edbf2aa0a5348263 (this worktree; the flagged files are unchanged from HEAD):

```bash
js-gradle-slot -w 3600 ./gradlew :modules:app-inference:test --tests '*LlamaExchangeLeaseScanTest*' --rerun
# BUILD SUCCESSFUL in 12s; 39 actionable tasks: 1 executed, 38 up-to-date
```

`modules/app-inference/build/test-results/test/TEST-io.justsearch.app.inference.LlamaExchangeLeaseScanTest.xml`:

```xml
<testsuite name="io.justsearch.app.inference.LlamaExchangeLeaseScanTest" tests="6" skipped="0" failures="0" errors="0" timestamp="2026-11-02T23:17:40.334Z" hostname="elias-systemproductname" time="1.527">
```

Test cases: theExchangeRequestBuilderRequiresALease, evalScriptsReachTheChatModelThroughTheProxy,
noOtherMainClassNamesALlamaServerExchange, theEvalScanCoversEveryPythonPackageUnderScripts,
theProxyAdmitsEachExchangeBeforeItReachesLlamaServer,
everyInferenceExchangePassesItsLeaseAndOnlyProbesGoWithout.

Before-state 81f1b00072473126985200ba9a91c534fbb18012 (shared clone in $TMPDIR/before):

```bash
js-gradle-slot -w 3600 ./gradlew :modules:app-inference:test --tests '*LlamaExchangeLeaseScanTest*' --rerun
# BUILD SUCCESSFUL in 7s; 39 actionable tasks: 1 executed, 38 up-to-date
```

```xml
<testsuite name="io.justsearch.app.inference.LlamaExchangeLeaseScanTest" tests="5" skipped="0" failures="0" errors="0" timestamp="2026-11-02T23:19:12.399Z" hostname="elias-systemproductname" time="1.245">
```

The same five cases, without theEvalScanCoversEveryPythonPackageUnderScripts.

### lifecycleIntegrationTest declared inputs

A Gradle dry run cannot print inputs, and resolving `inputs.files` at configuration time fails
(`Resolution of the configuration ':modules:system-tests:jacocoAgent' was attempted without an
exclusive lock`). So an init script outside the repository (applied to the root build only, not
the included build-logic build) registers a task that reads
`lifecycleIntegrationTest.inputs.files` at execution time and prints the entries under
scripts/jseval, scripts/jsharness and scripts/lib/python-path*. lifecycleIntegrationTest itself
does not run.

The init script (`$TMPDIR/scripts/lifecycle-inputs.init.gradle.kts`):

```kotlin
// Adds :modules:system-tests:printLifecycleHarnessInputs, which lists lifecycleIntegrationTest's
// declared input files under the UI harness paths without running that test.
if (gradle.parent == null) {
  gradle.rootProject {
    project(":modules:system-tests") {
      tasks.register("printLifecycleHarnessInputs") {
        doLast {
          val root = rootProject.projectDir.toPath()
          val files = tasks.getByName("lifecycleIntegrationTest").inputs.files.files
            .map { root.relativize(it.toPath()).toString() }
            .filter { it.startsWith("scripts/jsharness") || it.startsWith("scripts/jseval") || it.startsWith("scripts/lib/python-path") }
            .sorted()
          println("lifecycleIntegrationTest harness inputs (${files.size}):")
          files.forEach { println("  $it") }
        }
      }
    }
  }
}
```

```bash
js-gradle-slot -w 3600 ./gradlew -q --no-configuration-cache \
  -I "$TMPDIR/scripts/lifecycle-inputs.init.gradle.kts" \
  :modules:system-tests:printLifecycleHarnessInputs
```

Candidate output (complete):

```text
lifecycleIntegrationTest harness inputs (26):
  scripts/jsharness/jsharness/__init__.py
  scripts/jsharness/jsharness/__main__.py
  scripts/jsharness/jsharness/_paths.py
  scripts/jsharness/jsharness/agent_identity.py
  scripts/jsharness/jsharness/agent_spawn_register.py
  scripts/jsharness/jsharness/agent_stream_fixture.py
  scripts/jsharness/jsharness/cli.py
  scripts/jsharness/jsharness/commands.py
  scripts/jsharness/jsharness/readiness.py
  scripts/jsharness/jsharness/run_register.py
  scripts/jsharness/jsharness/suite_stats.py
  scripts/jsharness/jsharness/types.py
  scripts/jsharness/jsharness/ui_a11y_gate.py
  scripts/jsharness/jsharness/ui_agent_flows.py
  scripts/jsharness/jsharness/ui_check.py
  scripts/jsharness/jsharness/ui_critic.py
  scripts/jsharness/jsharness/ui_diff.py
  scripts/jsharness/jsharness/ui_fixtures.py
  scripts/jsharness/jsharness/ui_fuzz.py
  scripts/jsharness/jsharness/ui_measure.py
  scripts/jsharness/jsharness/ui_perf.py
  scripts/jsharness/jsharness/ui_proportion_gate.py
  scripts/jsharness/jsharness/ui_selectors.py
  scripts/jsharness/jsharness/ui_shot.py
  scripts/jsharness/jsharness/ui_step_index.json
  scripts/lib/python-path.cjs
```

Before-state output: `lifecycleIntegrationTest harness inputs (167):`, all under
scripts/jseval/jseval/, none under scripts/jsharness or scripts/lib/python-path*. The ui_*
file names in the two lists are identical (compared with `diff`).

### Shared resources

The js-gradle-slot queue/admission locks and the shared Gradle user home (caches, build cache,
daemons). Each checkout used its own .gradle and build directories. No installed application,
dev stack, model server or GPU was used. `.agents/preflight` does not exist in this checkout.

## Acceptance records
