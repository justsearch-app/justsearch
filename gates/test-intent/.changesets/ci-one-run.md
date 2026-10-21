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
        "scripts/ci/unit-test-shard-policy.v1.json"
      ],
      "class": "Adaptation",
      "moved": [
        "split",
        "instrumentation"
      ],
      "whatMoved": "Replace matrix job IDs with literal jobs preserving task membership and all required names. Record the existing supervisor conformance commands as main-only; engine-discovery uses the shared Node test wrapper to exclude the listed Windows case only on PRs. No assertions are edited. O12 authorizes the changed PR selection in the workflow entry.",
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
        }
      ]
    }
  ]
}
```

## Acceptance records

Pending independent acceptance by the orchestrator or verifier; the builder cannot accept.
