---
schema: test-intent.v1
task: t-20261106-frozen-index
author-role: builder
author-session: 01a1b880-a623-7580-b591-38506070d7aa
---

# Test-intent entries

```json
{
  "entries": [
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/resolved/ConfigApplyScopesTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "The packaged apply-scope register must be byte-for-byte the repository register, contain exactly the authorized declaration set, and retain the declared apply scope for each explicitly checked key.",
      "whySurvives": "The existing byte-equality check and every existing key/scope assertion remain. The one authorized internal frozen-eval launch declaration adds one governed row, so the exact cardinality changes from 292 to 293. The added direct assertion requires justsearch.eval.frozen to be restart-required; it strengthens the same registration predicate rather than allowing a missing row, changing an existing key scope, calculating the expected size from production output, or weakening the exact-set guard.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Approve narrow extension (Recommended)",
          "delegationLocation": "task t-20261106-frozen-index request",
          "delegationScope": "Implementation choices within the approved narrow frozen-index comparison prerequisite, preserving the frozen floor, labels and tolerances. This citation supplies no additional scoring allowance; the separately bounded scoring invocation is consumed. It is not owner adoption of an arbitrary register count or fixture output.",
          "choice": "Represent the owned frozen-only Engine lifetime by one nonpersisted internal launch flag, separate from ordinary eval/prepared-only behavior. Keep exact register cardinality aligned with that single explicitly declared key and require its restart-required scope directly.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records

Independent source/intent acceptance is recorded below; execution and gate results are separate.

```json
{
  "kind": "acceptance",
  "role": "reviewer",
  "session": "01a1b879-70a2-73f0-9a5e-3231c1e53dae",
  "verdict": "accept",
  "digest": "sha256:2b23f457c6ebbbecb95f6fc43473e5ef25023aa612ae9d21782bea0527eef942",
  "note": "Exact digest explicitly accepted by independent reviewer 01a1b879-70a2-73f0-9a5e-3231c1e53dae on 6ab16f1; covered test and declaration unchanged. CLI transcription by orchestrator 01a1b0c3-481a-7d22-87d4-0ea0ad710b2c preserves the read-only reviewer role. Source and intent only; no runtime or product PASS."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "01a1b879-70a2-73f0-9a5e-3231c1e53dae",
  "verdict": "accept",
  "digest": "sha256:2b23f457c6ebbbecb95f6fc43473e5ef25023aa612ae9d21782bea0527eef942",
  "note": "Independent source/intent audit: one authorized frozen launch declaration supports 292 to 293; packaged byte equality and prior scope assertions remain, and the new restart-required assertion strengthens the rule. No execution, runtime or product PASS inferred."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1b8ad-2c61-73f0-b08d-5b72b82a7545",
  "verdict": "accept",
  "digest": "sha256:2b23f457c6ebbbecb95f6fc43473e5ef25023aa612ae9d21782bea0527eef942",
  "note": "Independent Structural rule kept acceptance: before-state exact292 declaration guard becomes293 for the single authorized internal frozen launch key; packaged equality and prior scope assertions remain, with explicit restart-required assertion added. I independently executed all6 ConfigApplyScopes cases at exact90f after restoring fixture-owned environment, with no skips. This accepts test intent only; no new installed-runtime, model-quality or scoring PASS."
}
```
