---
schema: test-intent.v1
task: ci-one-run
author-role: builder
author-session: ci-one-run-builder-20261021
---

# One scoped PR run and full main suite

```json
{
  "entries": [
    {
      "items": [
        ".github/workflows/ci.yml",
        "build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "ci-one-run",
          "scenario": "O1",
          "quote": "A PR that changes only docs or agent files: only Secret scan and Public claims do work; every required check reports; with idle runners it merges within 10 min.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "ci-one-run",
          "scenario": "O2",
          "quote": "A PR with any path no lane claims, a workflow change, a changed-file list that is incomplete, or a manual/branch dispatch without PR metadata: every PR lane runs; slowest job within 16 min idle.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "ci-one-run",
          "scenario": "O3",
          "quote": "A PR is tested by every lane that reads any file it changes, in either path of a rename, or deletes; mixed changes get the union.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "ci-one-run",
          "scenario": "O4",
          "quote": "A Java-module PR runs the Java lanes in its full closure (including reverse, test and whole-program-audit dependencies), Windows contracts and Engine lifecycle; lanes it cannot affect are skipped; slowest job within 15 min idle.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "ci-one-run",
          "scenario": "O8",
          "quote": "Push to main runs the full suite unscoped, including Integration tests, Windows stop-census, Measured axe and the Windows supervisor conformance cases.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "ci-one-run",
          "scenario": "O12",
          "quote": "Known flake families no longer block PRs; deterministic failures in the PR lanes still do.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "yes",
          "delegationLocation": "task t-20261020-5d7302 agreement",
          "delegationScope": "Approved direction: slow suites and the combined-tree check on main after merging, flaky tests out of the required set",
          "choice": "Run the slow suites and the combined-tree check on main after merging; exclude the listed flaky tests from the required PR set while retaining their execution on main and dispatch.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: ADR-0026 and ADR-0044/0045 describe the prior full PR / light queue and dispatch-only slow tiers. The agreed O1\u2013O5, O8 and O12 replace that execution selection; ADR-0053 records the supersession. Product assertions remain, and the listed exclusions execute on main and dispatch."
    },
    {
      "items": [
        "scripts/ci/unit-test-shard-policy.v1.json",
        "scripts/ci/test-verify-unit-test-shard-policy.mjs",
        "scripts/ci/test-windows-contract-diagnostics.mjs",
        "scripts/ci/test-report-unit-test-attribution.mjs",
        "scripts/ci/test-lifecycle-suite-coverage.mjs",
        "scripts/ci/test-report-ci-evidence-digest.mjs"
      ],
      "class": "Adaptation",
      "moved": [
        "split",
        "instrumentation"
      ],
      "whatMoved": "Replace matrices with literal jobs and preserve the required names. The repair splits Linux Java unit lanes into disjoint measured class partitions and gives each Gradle task one shard owner. Windows supervisor conformance runs on selected PR lanes; only the six listed cases are skipped on PRs, and every case runs on main and dispatch. Engine-discovery retains its listed PR exclusion. Split the twelve complete Windows tag-filtered Test tasks into two module partitions; after hosted runs showed part 1 slower, separate app-services from worker-services and move observability into part 1 using full task timings including test compilation. Move dev-runner conformance and its diagnostics to the dev-runner-selected Windows node job. Preserve all fifteen safety-class requirements in their owning part, each part's evidence, main cache writes and the required aggregate. Inventory checks reject any lost or duplicated task.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "ci-one-run",
          "scenario": "O4",
          "quote": "A Java-module PR runs the Java lanes in its full closure (including reverse, test and whole-program-audit dependencies), Windows contracts and Engine lifecycle; lanes it cannot affect are skipped; slowest job within 15 min idle.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "ci-one-run",
          "scenario": "O8",
          "quote": "Push to main runs the full suite unscoped, including Integration tests, Windows stop-census, Measured axe and the Windows supervisor conformance cases.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "ci-one-run",
          "scenario": "O12",
          "quote": "Known flake families no longer block PRs; deterministic failures in the PR lanes still do.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "agreed to all",
          "delegationLocation": "task t-20261020-5d7302 agreement",
          "delegationScope": "Scoped CI scenarios and delivery timing",
          "choice": "Split Windows contracts into two jobs balanced by measured task times under the existing Windows-native tests aggregate; check that every existing test runs in exactly one part, and keep dev-runner and conformance steps in their selected job.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ActionLedgerControllerTest.java",
        "scripts/dev/test-dev-runner-vite-launchers.mjs",
        "scripts/ci/check-action-ledger-docs.test.mjs",
        "scripts/ci/check-model-license-docs.test.mjs",
        "scripts/ci/check-vite-launchers.test.mjs",
        "scripts/ci/unit-test-class-shards.v1.json",
        "modules/app-engine/build.gradle.kts",
        "modules/app-launcher/build.gradle.kts",
        "modules/app-services/build.gradle.kts",
        "modules/ui/build.gradle.kts",
        "modules/indexer-worker/build.gradle.kts",
        "modules/worker-services/build.gradle.kts"
      ],
      "class": "Adaptation",
      "moved": [
        "split",
        "instrumentation"
      ],
      "whatMoved": "Move the repository-wide Vite scan, the action-ledger documentation check and the registry-projected legal table check into always-on Public claims so docs-only PRs retain those assertions. Action-ledger kind and limit checks read the Java declarations directly. Preserve every other controller assertion. Split expensive Java test source sets into measured per-class Test tasks with the same JVM and module configurations; nested classes remain together and unlisted classes are assigned automatically. Local test tasks remain complete.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "keep the light qeue, i agree with the rest. proceed autonomously",
          "delegationLocation": "task t-20261020-5d7302 agreement",
          "delegationScope": "Autonomous implementation of the agreed CI changes",
          "choice": "Move cheap repository-wide static scans into always-on Public claims; keep checks that need a lane runtime in that lane.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "agreed to all",
          "delegationLocation": "task t-20261020-5d7302 agreement",
          "delegationScope": "Scenario O1: docs-only PRs keep the repository-wide static assertions in always-on Public claims",
          "choice": "Move the repository-wide Vite scan, action-ledger documentation check and registry-projected legal table check into always-on Public claims so docs-only PRs retain those assertions.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "agreed to all",
          "delegationLocation": "task t-20261020-5d7302 agreement",
          "delegationScope": "Scoped Java lanes and delivery timing",
          "choice": "Split Linux Java unit lanes over 12 minutes into shards balanced by measured per-class timings, targeting at most about 10 minutes per shard.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "scripts/ci/classify-changes.test.mjs",
        "scripts/ci/one-run-policy.test.mjs",
        "scripts/ci/test-report-github-advisories.mjs",
        "scripts/dev/run-gh.test.mjs",
        "scripts/ci/auto-merge-guard.test.mjs"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Use traced dev-runner inputs in place of the Java-lane implication and expand the indexed static reader audit to joined paths and frontend/notice inputs; require enqueue messages to equal the squash preview; verify exact per-package/version advisory attribution against each package's own vulnerable range and both queued auto-merge guard races, comparing enqueue time with the synchronize payload's updated_at without anonymous requests and disabling on uncertain metadata. Retry the guard read and disable operations three times with short backoff; a failed disable names the PR and says auto-merge is still on. The guard executes only a script from the trusted base commit. The configuration contention flake joins the PR-only exclusion list and still runs on main.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "yes",
          "delegationLocation": "task t-20261020-5d7302 agreement",
          "delegationScope": "Advisory gate blocking only PR-introduced vulnerabilities",
          "choice": "Attribute an advisory only to the package named by its own vulnerable entry and a queried version that matches.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "agreed to all",
          "delegationLocation": "task t-20261020-5d7302 agreement",
          "delegationScope": "Lane selection from the inputs each lane reads",
          "choice": "Replace the coarse Java-selects-dev-runner rule with exact traced inputs; preserve the static drift guard and zero-gap audit.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "yes",
          "delegationLocation": "task t-20261020-5d7302 agreement",
          "delegationScope": "Squash auto-merge with the previewed message",
          "choice": "Send exactly the previewed subject and body, obtained from the preview output or computed identically; test equality with the preview.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "yes",
          "delegationLocation": "task t-20261020-5d7302 agreement",
          "delegationScope": "Advisory gate blocking only PR-introduced vulnerabilities",
          "choice": "Query different versions of the same package in separate batches so GitHub matching determines exact advisory attribution.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "yes",
          "delegationLocation": "task t-20261020-5d7302 agreement",
          "delegationScope": "Auto-merge bound to the validated PR head",
          "choice": "Compare enabledAt against the synchronize payload pull_request.updated_at instead, without an extra request. Disable auto-merge on missing or invalid timestamps or other uncertainty; leave it enabled only when the head changed again or it was enabled after the push.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records

Pending independent acceptance by the orchestrator or verifier; the builder cannot accept.

```json
{
  "kind": "acceptance",
  "role": "orchestrator",
  "session": "justsearch-03",
  "verdict": "reject",
  "digest": "sha256:764a52e5c78509866b52e15ddd64063e6837da04c9c5994f619d01431df7bd19",
  "note": "Nine owner-words sources quote orchestrator fix-round briefs (H1, R1a, R2, K5, G1-G4, H2), not the owner's words. Recast them as delegated-choice quoting the owner's delegation; the scenario sources are owner-adopted and fine."
}
```

```json
{
  "kind": "acceptance",
  "role": "orchestrator",
  "session": "justsearch-03",
  "verdict": "accept",
  "digest": "sha256:69dafaa13a50f5c92ab3feae221765e8ff56cecab70b57d94a520e1b74dff92a",
  "note": "Re-reviewed after the reject: the nine fix-round choices are now delegated-choice sources quoting the owner's words, which the task record holds (light queue and autonomy 2026-10-20; 'yes' to the direction and 'agreed to all' 2026-10-21); each choice lies within its delegation. Scenario sources match the agreed r2 text. Verification: verifier r4 (full) and r5 (focused) on this candidate passed."
}
```

```json
{
  "kind": "acceptance",
  "role": "orchestrator",
  "session": "justsearch-03",
  "verdict": "accept",
  "digest": "sha256:29e3fa15fc7f5ffd5a004e28a71287e1d7711f96cb4c1ecd5910efa41d9438c8",
  "note": "Re-accepted after an independent read: the stale created_at choice is removed, the out-of-date pending sentence is gone, and entries 1 and 3 now also cite the owner's 'yes' and 'agreed to all' for their direct scopes. Each choice lies within its delegation."
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "agent-verifier-_9c1al7j",
  "verdict": "accept",
  "digest": "sha256:29e3fa15fc7f5ffd5a004e28a71287e1d7711f96cb4c1ecd5910efa41d9438c8",
  "note": "Audit: classes fit the diffs; moved action-ledger assertions kept (5 mutations fail); base task inventory all owned; PR-only skips run on main; scenario and delegation scopes hold; ADR-0044/0026 conflict named and superseded by owner's later yes. Weak: no cited evidence that the four Gradle pr-flakes are flaky; scenario task field is ci-one-run, not the task id."
}
```
