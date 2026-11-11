---
schema: test-intent.v1
task: t-20261111-modelset
author-role: builder
author-session: ce52d70e-890a-4092-9a94-1fcdd1ca4acb
---

# Test-intent entries

An index switch during the deferred model start no longer closes the live model set. Two existing
test files change: one call adapts to the new cleanup signature, and one new health check pins the
reporting of an unacquirable serving model set (scenario M2). The new KnowledgeServer-level checks
are in an isolated new file (exempt).

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerDeferredRetirementTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Adaptation",
      "moved": ["signature"],
      "whatMoved": "retiredSourceClosesOnlyItsOwnWrapperAfterTheSourceService invokes the private KnowledgeServer.closeRetiredSource by reflection. Its parameters changed from (ServingView old, EncoderSet successorEncoder, QueryRoleSet successorQuery) to (ServingView old): whether a set is shared is now decided at cleanup time from the views that reference it, not from values captured at promotion. The call now passes only A's view. Inputs, preconditions, observation and expected outcome are unchanged: A's view carries A's sets, B's sets exist and no view references them, and the test still verifies that A's service, surface, wrapper, query handle and query wrapper close in order and that B's never close."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerHealthServiceTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "New",
      "behaviour": "When the serving generation's model set cannot be acquired (recorded on the process-wide QueryEncoderReadiness), the health check stays serving, reports the query encoders cold and adds the issue 'Query models unavailable: <reason>'. A warm service reports neither; a service with no models reports aiReady false, not cold, and no such issue. Embedding readiness is not reached before the existing readiness bound and is reached once the bound has passed, so the bound is not extended. Clearing the reason removes the issue.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with investigating Windows start and restart failures. i dont want you to spend your tokens too much on implementation, but save that for opus models.\nM. Start with a check that forces the bad order and fails for that reason.",
          "delegationLocation": "task t-20261111-modelset request",
          "delegationScope": "investigating and repairing the Windows start and restart failures, beginning with model start M: an index switch while the deferred model start is in flight, with its settled scenarios",
          "choice": "Health reports scenario M2 as agreed in the task: \"When the model set of the view that is currently serving cannot be acquired, that stays distinguishable, in health and in one warning-level log line, from query encoders that are warm and from a legitimate selection with no models; it does not hold overall readiness back beyond the existing readiness bound.\" Agent choice of form: no new health field; the existing query_encoders_cold flag plus an issue line 'Query models unavailable: <reason>' in the health issue list; the embedding readiness input keeps its existing bound.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/modelset-switch-during-model-start.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerHealthServiceTest.java#unavailableServingModelsAreReportedApartFromWarmAndFromNoModels"
      ],
      "repaired": false
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1cad6-cc8c-7043-96e5-e0209752850b",
  "verdict": "accept",
  "digest": "sha256:c1e1052f831796243f1a426e716896d674ab5fa075a8c6928c16ad793fda8c36",
  "note": "M2 intent and execution evidence match; signature adaptation preserves the oracle."
}
```
