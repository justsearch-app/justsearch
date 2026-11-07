---
schema: test-intent.v1
task: t-20261102-redesign
author-role: builder
author-session: c8_health_builder
---

# Test-intent entries

D108 is the adopted C8 integrity-recovery obligation, expanded as supplied scenario C8D4.

The original integrity fixture retains its SQL, corrupt-result and empty-result failures, unrelated-read isolation and historical timestamp assertions. The only changed expectation is the permanently latched health after a real passing quick-check. Independent acceptance is required on the final candidate.

```json
{"entries":[{"items":["modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/QueueDefectRegressionTest.java"],"class":"Obsolete","sources":[{"kind":"owner-adopted-scenario","task":"t-20261102-redesign","scenario":"D108","quote":"unresolved failure kinds cleared only by a later success of the same kind; quick-check clears only integrity failure.","label":"agent-drafted, owner-adopted"}],"conflictSearch":"The supplied complete adopted C8D1-D8/P1-P6 scenarios and accepted candidate-2 design require integrity-only recovery through a full real passing quick-check; existing source documentation described the superseded permanent latch. No conflicting adopted decision was supplied. Independent acceptor repeats the intent search."}]}
```

## Acceptance records
