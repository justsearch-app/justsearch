---
schema: test-intent.v1
task: t-20261107-maintenance
author-role: orchestrator
author-session: 01a1b3ac-9f18-7a62-b225-2c4d599a54ac
---

# Remove unused-export allowances for two deleted frontend files

The baseline still names `TaskList.ts` and `sv3-results.ts`, although neither path exists.
Remove those two rows only. Every surviving path, count and date remains unchanged;
the enforcer and its failure cases are unchanged.

```json
{
  "entries": [
    {
      "items": ["gates/dead-code/baseline.txt"],
      "change": "modified",
      "kind": "baseline",
      "class": "Structural rule kept",
      "rule": "Each reported file's unused-export count must not exceed its pinned allowance; malformed and unsupported whole-file reports still fail closed.",
      "whySurvives": "Only src/shell-v0/components/TaskList.ts (4) and src/shell-v0/views/search-v3/sv3-results.ts (2) are removed. Both resolve under modules/ui-web and are absent at the base and candidate. All surviving rows are byte-identical, no allowance is raised, and neither enforcer code nor any existing test or classification is weakened. A future reintroduction of either path therefore starts with allowance zero.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Proceed with the investigation, then design implementation of all items accordingly and proceed end to end",
          "delegationLocation": "task t-20261107-maintenance request",
          "delegationScope": "Implement the five analyzed maintenance items, including pruning stale dead-code allowances for deleted files, while preserving existing behavior and protections.",
          "choice": "Remove only the two absent-path allowances and retain all existing-file pins and failure predicates.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "source-verifier-70f644bc-20261107-01",
  "verdict": "accept",
  "digest": "sha256:7eb50c4a8a1d7672db85462bfa5a22bc6b3c85e5bba8c268de700af23478ae8e",
  "note": "Independently verified exact absent-path pruning, byte-identical surviving pins, and preserved growth/malformed/whole-file failure checks on before and candidate."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "/root/source_reviewer",
  "verdict": "accept",
  "digest": "sha256:7eb50c4a8a1d7672db85462bfa5a22bc6b3c85e5bba8c268de700af23478ae8e",
  "note": "Independent source audit accepts Structural rule kept: only two absent-path allowances are removed; surviving pins and enforcement predicates are unchanged. Delegation covers this choice, no conflicting decision record was found, and the digest was independently recomputed. Runtime execution remains separately verified."
}
```
