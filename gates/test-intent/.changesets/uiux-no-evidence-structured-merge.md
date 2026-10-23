---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: uiux-no-evidence-merge-builder
---

# Test-intent entries

Combined cases are agent-adopted under the existing delegated design choice. The choice below
quotes the merge continuation brief verbatim. Existing test expectations remain unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.modelStructured.test.ts"
      ],
      "class": "New",
      "behaviour": "Separate schema and model arguments, preserve distinct request and live/cold turn identities, never offer the no-evidence action on Structured, and preserve model versus Structured Retry/Edit including remembered schema and cold schema review.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "WP4.2b merge combined cases",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP4.2b design and integration with Structured mode, preserving both established contracts and all existing test expectations.",
          "choice": "Decide the combined cases explicitly and test them: a Structured turn is never offered the no-evidence model action; a model-answer turn's Retry/Edit stays a model answer while a Structured turn's stays Structured; persistence keeps both main's extraction rule and your D5 rule (no turn that either side saves is dropped, none is saved twice); runAsk carries both the schema and the model-answer choice without one shadowing the other.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-no-evidence-structured-merge-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.modelStructured.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ModelStructuredPersistenceTest.java"
      ],
      "class": "New",
      "behaviour": "The union of extraction and D5 terminal-persistence rules saves exactly one question/error pair for each attempt, including overlap, and keeps classified outcomes and unique message identities after a cold store read.",
      "sources": [
        {
          "kind": "delegated-choice",
          "task": "t-20261019-bd718f",
          "agreedScenario": "WP4.2b merge combined cases",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "WP4.2b design and integration with Structured mode, preserving both established contracts and all existing test expectations.",
          "choice": "Decide the combined cases explicitly and test them: a Structured turn is never offered the no-evidence model action; a model-answer turn's Retry/Edit stays a model answer while a Structured turn's stays Structured; persistence keeps both main's extraction rule and your D5 rule (no turn that either side saves is dropped, none is saved twice); runAsk carries both the schema and the model-answer choice without one shadowing the other.",
          "label": "agent choice; agent-adopted (delegated-choice)"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-no-evidence-structured-merge-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ModelStructuredPersistenceTest.java"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "uvce0kse-verifier-3",
  "verdict": "accept",
  "digest": "sha256:d37e0b47043ee5bd1d53619a9a3c757aff18168ed4693b5fef6e0b43aac7fc44",
  "note": "New expectations follow the agent-adopted delegated-choice scenarios reviewed before the diff. Independent evidence: 7 frontend cases and 1 Java case pass with zero skips; both fail before by assertions for the intended repair. The recorded dependency-copy adapter preserves internal links. Product verification is reported separately."
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "01a168ce-c6e2-7d71-b529-62b0b59158ae",
  "verdict": "accept",
  "digest": "sha256:d37e0b47043ee5bd1d53619a9a3c757aff18168ed4693b5fef6e0b43aac7fc44",
  "note": "Audited both entries as New within delegated integration scope: schema/model identity and Retry/Edit remain distinct; extraction and D5 persistence rules save exactly once, including overlap. Independently confirmed 7 frontend passes and assertion fail-before. Reviewed digest-valid Java evidence: 1 pass after and the intended assertion failure before. Optional local Java rerun was not executed due to the shared queue. Existing expectations are not weakened; test-intent acceptance only."
}
```
