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
        }
      ],
      "conflictSearch": "decision records searched for a conflict: ADR-0026 and ADR-0044/0045 describe the prior full PR / light queue and dispatch-only slow tiers. The agreed O1\u2013O5, O8 and O12 replace that execution selection; ADR-0053 records the supersession. Product assertions remain, and the listed exclusions execute on main and dispatch. Acceptance remains pending independent review."
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
      "whatMoved": "Replace matrices with literal jobs and preserve the required names. The repair splits Linux Java unit lanes into disjoint measured class partitions and gives each Gradle task one shard owner. Windows supervisor conformance runs on selected PR lanes; only the six listed cases are skipped on PRs, and every case runs on main and dispatch. Engine-discovery retains its listed PR exclusion. Split the twelve complete Windows tag-filtered Test tasks into two measured module partitions (257.408 and 255.902 summed suite seconds). Preserve all fifteen safety-class requirements in their owning part, each part's evidence, main cache writes and the required aggregate. Inventory checks reject any lost or duplicated task.",
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
          "kind": "owner-words",
          "quote": "Then split the step across two Windows jobs under the existing \"Windows-native tests\" aggregate, balanced from measured task times, so that with today's worst case (15.6 min) each part's step stays under about 9 min. Every test the step runs today must still run in exactly one part (add a check for it, like the Linux shard policy does). Keep the dev-runner and conformance steps in the job the classifier selects them for.",
          "location": "task ci-one-run request; fix round H1, 2026-10-21"
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
          "kind": "owner-words",
          "quote": "Tests that are cheap static scans of the whole repository (for example the vite-launcher scan over docs) move into the always-on Public claims lane instead of making docs select the dev-runner lane. Keep a test in its lane where it needs that lane's runtime.",
          "location": "task ci-one-run request; repair brief R1a, 2026-10-21"
        },
        {
          "kind": "owner-words",
          "quote": "Split each Linux Java unit lane over 12 min into shards so no shard should exceed about 10 min, balanced from the per-class timings in tmp/ci-attribution/ (the attribution artifacts of the candidate's own hosted run).",
          "location": "task ci-one-run request; repair brief R2, 2026-10-21"
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
      "whatMoved": "Use traced dev-runner inputs in place of the Java-lane implication without changing the static reader drift guard; require enqueue messages to equal the squash preview; verify exact per-version advisory attribution and both queued auto-merge guard races, comparing enqueue time with the synchronize payload's updated_at without anonymous requests and disabling on uncertain metadata. The guard executes only a script from the trusted base commit. The configuration contention flake joins the PR-only exclusion list and still runs on main.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Replace the coarse \"Java selects dev-runner\" rule with the exact traced inputs (keep the static drift guard and the audit at zero gaps).",
          "location": "task ci-one-run request; fix-round G1, 2026-10-21"
        },
        {
          "kind": "owner-words",
          "quote": "Send exactly the previewed subject and body; take them from the preview's own output or compute them the same way, and test that subject and body equal the preview.",
          "location": "task ci-one-run request; fix-round G2, 2026-10-21"
        },
        {
          "kind": "owner-words",
          "quote": "Make attribution exact: query different versions of the same package in separate batches, so GitHub's own matching decides which version an advisory belongs to.",
          "location": "task ci-one-run request; fix-round G3, 2026-10-21"
        },
        {
          "kind": "owner-words",
          "quote": "The guard must leave auto-merge alone when the PR's head is no longer the head this event was for (a later run handles it) or when auto-merge was enabled after this workflow run was created (GraphQL autoMergeRequest.enabledAt against the run's created_at).",
          "location": "task ci-one-run request; fix-round G4, 2026-10-21"
        },
        {
          "kind": "owner-words",
          "quote": "Compare enabledAt with the event's own pull_request.updated_at from the payload (the time of the push) instead; make no extra request. (b) Fail closed: if a timestamp is missing or invalid, or anything is uncertain, disable auto-merge (losing a re-enqueue is the lesser harm). Only \"head changed again\" and \"enabled after this push\" leave it alone. Update the tests to match.",
          "location": "task ci-one-run request; fix round H2, 2026-10-21"
        }
      ]
    }
  ]
}
```

## Acceptance records

Pending independent acceptance by the orchestrator or verifier; the builder cannot accept.
