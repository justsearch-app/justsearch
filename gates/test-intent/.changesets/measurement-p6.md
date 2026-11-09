---
schema: test-intent.v1
task: t-20261109-easy-batch
author-role: builder
author-session: 01a1c223-d745-7c03-92d7-da5fd990e427
---

# P6 projection retirement and surviving diagnostic obligations

The jseval suite is outside the file-level product test-intent scope, but this
requested record accounts for every changed/deleted test file. The three deletions
are Obsolete with same-change production removals; the gate is a named required-set
edit with the surviving structural rule retained and its counterexample strengthened.
These classes do not require a New/Defect-pin D4 attachment. No evidence JSON or
acceptance record is authored here. Independent final evidence execution and
acceptance belong to the orchestrator; the stock D4 runner supports JVM, Vitest
and Rust IDs, not Python/jseval IDs.

Durable changes: delete test_projections_bootstrap_ci.py,
test_projections_rank_diff.py and test_projections_stratified_metrics.py;
update test_phase3_gate.py's healthy fixture (also used by report serialization)
and the missing-required-output counterexample (two surviving-output cases).
Temporary scaffolding: shared before-state clone with the candidate gate test
overlaid; no product or test helper is installed or retained by that overlay.

Observed focused execution: baseline smoke 1 pass; candidate gate 8 passes;
before-state with candidate gate tests 2 assertion failures (healthy and serialized
report still demanded retired outputs), 6 passes (including both missing survivors).
No pre-existing candidate failure or skipped case was observed in those runs.
This prose is an execution summary, not stock D4 evidence or independent acceptance.

```json
{
  "entries": [
    {
      "items": [
        "scripts/jseval/tests/test_projections_bootstrap_ci.py",
        "scripts/jseval/tests/test_projections_rank_diff.py",
        "scripts/jseval/tests/test_projections_stratified_metrics.py"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Finish the measurement programme, step P6 only, in t-20261102-evalcrit: scenarios S34, S35 and S36 in its design/scenarios.md. Freeze the agent campaigns, delete only the modules the quality core replaced, and add the required-check rule. Every removed component must have no remaining reader. Neighbouring experiments and indexing-speed work are out of scope. Keep the quality core and its current tooling. A held search branch still changes scripts/jseval/jseval/quality/run.py.",
          "delegationLocation": "task t-20261109-easy-batch request",
          "delegationScope": "P6 only: retire bootstrap_ci, rank_diff and stratified_metrics and their dedicated tests; remove their registry/output requirements while preserving contract_violations and rate_timeline gate obligations.",
          "choice": "Delete the three dedicated projection tests with their production modules. In test_phase3_gate.py remove only the retired output fixture entries and parameterize the missing-output check over contract_violations and rate_timeline, preserving manifest/data-layout/report/extra-output assertions.",
          "label": "agent choice"
        },
        {
          "kind": "removal",
          "removed": [
            "scripts/jseval/jseval/projections/bootstrap_ci.py",
            "scripts/jseval/jseval/projections/rank_diff.py",
            "scripts/jseval/jseval/projections/stratified_metrics.py"
          ]
        }
      ],
      "conflictSearch": "Decision records searched for bootstrap_ci, rank_diff, stratified_metrics, projection-presence and observability gates: no conflicting requirement to retain these three generators/tests. ADR-0026 retains the manual projection-presence gate; it remains in place for contract_violations and rate_timeline."
    },
    {
      "items": [
        "scripts/jseval/tests/test_phase3_gate.py"
      ],
      "class": "Structural rule kept",
      "rule": "The legacy diagnostic gate fails if no run exists, if its manifest is absent/unparseable, or if any surviving required output is missing; it passes a readable run with all surviving outputs and tolerates extra outputs, preserving report identity.",
      "whySurvives": "The production allowlist edit is explicitly named in the delegated P6 assignment. The default healthy/report fixtures drop only the three retired output names; the missing-output oracle now covers each of contract_violations and rate_timeline independently, with an explicit expected-name assertion. The old case (only the two survivors present must fail because stratified_metrics is missing) is retired under the same authorization. No surviving manifest, data-layout, report or extra-output oracle is weakened. This is the named allowlist edit permitted for Structural rule kept, not an input-only Adaptation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Finish the measurement programme, step P6 only, in t-20261102-evalcrit: scenarios S34, S35 and S36 in its design/scenarios.md. Freeze the agent campaigns, delete only the modules the quality core replaced, and add the required-check rule. Every removed component must have no remaining reader. Neighbouring experiments and indexing-speed work are out of scope. Keep the quality core and its current tooling. A held search branch still changes scripts/jseval/jseval/quality/run.py.",
          "delegationLocation": "task t-20261109-easy-batch request",
          "delegationScope": "P6 only: retire bootstrap_ci, rank_diff and stratified_metrics and their dedicated tests; remove their registry/output requirements while preserving contract_violations and rate_timeline gate obligations.",
          "choice": "Delete the three dedicated projection tests with their production modules. In test_phase3_gate.py remove only the retired output fixture entries and parameterize the missing-output check over contract_violations and rate_timeline, preserving manifest/data-layout/report/extra-output assertions.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records
