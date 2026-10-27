---
schema: test-intent.v1
task: query-classification
author-role: builder
author-session: codex-rescope-builder-20261026
---

# Test-intent entries

This rescope preserves baseline classifier behavior. All classifier-rule repair claims from the draft
are withdrawn. Policy and diagnostics remain a separate change; no model quality or rank improvement
is claimed. The restored production classifier is byte-identical to d036a07a9b9.

The table covers both historical test sources. It also ran against the actual baseline class in the
shared clone under `tmp/agent-tmp/rescope-builder/before`: 151 rows passed. Against c4ca2dcaf6's own
class in `tmp/agent-tmp/rescope-builder/classifier-candidate`, the same table produced 48 assertion
failures out of 151 rows. Raw comparison output is retained as `classifier-baseline.log` and
`classifier-candidate.log` under `tmp/agent-tmp/rescope-builder/`. These comparisons invoke the same
table method and JUnit assertions with each clone's classifier, not a reimplementation of the rules.

The historical `query-classifier-repaired.json` filename now holds the producer's negative
control against the rejected c4ca2dcaf6 class. It does not support a new classifier-rule repair.
The preservation evidence is the JUnit run on the final source.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/QueryClassifierTest.java"
      ],
      "class": "New",
      "behaviour": "R1 preservation: QueryClassifier.java and the original nested tests are restored byte-for-byte to d036a07a9b9 behavior. A 151-row table freezes baseline labels across the baseline and rejected candidate classifier test inputs, including their computed separator and large-input cases. It preserves existing classifications; it introduces no multi-token, quote, Unicode-separator or trailing-punctuation rule. Expected labels were evaluated with the baseline class.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a task register accordingly for query classification, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task query-classification user turn owner-delegation (quoted verbatim in the rescope builder assignment, 2026-10-26); follow-up: proceed accordingly",
          "delegationScope": "Investigate, design and implement query-classification improvements autonomously within the assigned baseline-preservation and policy/diagnostics scope. Technical scenarios, fixtures and test oracles are delegated agent choices; independent acceptance remains pending.",
          "choice": "Keep baseline classification for every SIMPLE query; retain the union of historical classifier inputs with baseline labels as a regression table. Do not retain the rejected classifier rules or their repair claims.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/query-classifier-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/QueryClassifierTest.java#baselineClassificationTable"
      ]
    }
  ]
}
```

## Acceptance records

Pending independent acceptance of the final candidate.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "qc-final-verifier-kuq66jbr-20261027",
  "verdict": "accept",
  "digest": "sha256:2997099cefefada65e59e897f3ec801f01a86dc7f8036fa5419676b0e49a7e9f",
  "note": "Delegated baseline preservation is within scope. Production classifier and original nested tests are unchanged from 5cad18c873; the frozen table executes on baseline and candidate. No rule repair or ranking-quality claim is accepted."
}
```
