---
schema: test-intent.v1
task: t-20261102-redesign
author-role: builder
author-session: observer_error_builder
---

# Test-intent entries

All test changes are durable regression or preservation checks; no compatibility scaffolding is introduced.
The pre-existing listener contract is `modules/app-api/src/main/java/io/justsearch/app/api/operations/OperationStore.java`:
"Listener failure cannot undo completion or prevent other observers."
The assertions below are bounded agent choices under the quoted delegation, not user-authored assertions.
The source diff also removes one existing redundant qualifier after importing `OperationRecord`.
The separate preservation receipt executes the unchanged admission scenario with its external oracle and the storage-Error controls.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationCompletionSubscriptionTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationAttemptRunnerTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/ForgetOperationStoreTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "An observer AssertionError cannot change a committed direct finish, winning rejection, deferred Forget completion or synchronous/asynchronous runner completion, nor prevent healthy observers registered on either side. Actual terminal state, receipt and counters are witnessed through another SQLite connection and after reopen. Repeated/refused terminalization cannot rewrite the winner or duplicate notifications; Forget emits nothing before explicit publish and repeated publish is idempotent. Runner bodies enter and return execution, public completion agrees with the committed row, and persistenceFailure stays unresolved. Existing SQL-commit/store-unlock and outer-Forget-fence assertions retain their predicates and report any captured callback failure outside publication. A deliberately wrong terminal-state oracle is rejected both before and after. Body/asynchronous-effect Error controls remain fatal, leave RUNNING for recovery, and emit no terminal notification. Repaired D4 class-level checks include the unchanged preservation cases: exactly five observer regressions fail by assertion before, while preservation and the intentional oracle rejection pass in both trees.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree with your recommendations. now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Remaining ingestion design and implementation selected during the authorized takeover. This bounded correction enforces the existing OperationStore.subscribeCompletions listener-failure guarantee; it does not change terminal persistence, public timing or execution ownership.",
          "choice": "Isolate observer-origin RuntimeException and Error at SqliteOperationStore.publishCompletion, while preserving actual body and storage Error propagation. Verify real SQLite committed completion through direct, rejection, deferred Forget and runner APIs, and keep callback verification failures observable outside the isolated callback.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingestion-observer-repaired.json",
      "newChecks": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationCompletionSubscriptionTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationAttemptRunnerTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/ForgetOperationStoreTest.java"
      ]
    },
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationAdmissionRunnerTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The existing raw-acceptance no-completion oracle now records notification count inside the listener and asserts zero outside the observer isolation boundary. Inputs, single-use/thread-bound scope checks, absence-of-completion expectation and all other assertions remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree with your recommendations. now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Remaining ingestion design and implementation selected during the authorized takeover. This bounded correction enforces the existing OperationStore.subscribeCompletions listener-failure guarantee; it does not change terminal persistence, public timing or execution ownership.",
          "choice": "Isolate observer-origin RuntimeException and Error at SqliteOperationStore.publishCompletion, while preserving actual body and storage Error propagation. Verify real SQLite committed completion through direct, rejection, deferred Forget and runner APIs, and keep callback verification failures observable outside the isolated callback.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/SqliteOperationStoreTransactionFailureTest.java"
      ],
      "class": "New",
      "behaviour": "An Error raised by the real terminal-write path remains the same escaping Error, cannot publish success, and leaves the real SQLite row RUNNING through reopen. A delegating JDBC fault injects the Error before the terminal UPDATE; the original connection is restored for cleanup. Existing commit/rollback/close failure controls are retained.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree with your recommendations. now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Remaining ingestion design and implementation selected during the authorized takeover. This bounded correction enforces the existing OperationStore.subscribeCompletions listener-failure guarantee; it does not change terminal persistence, public timing or execution ownership.",
          "choice": "Isolate observer-origin RuntimeException and Error at SqliteOperationStore.publishCompletion, while preserving actual body and storage Error propagation. Verify real SQLite committed completion through direct, rejection, deferred Forget and runner APIs, and keep callback verification failures observable outside the isolated callback.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingestion-observer-preservation.json",
      "newChecks": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/SqliteOperationStoreTransactionFailureTest.java"
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
  "session": "01a1be2a-2660-7430-b421-4a919546faad",
  "verdict": "accept",
  "digest": "sha256:f9c94c766a663fc193dedab24e86f90fdbc92813c78ce2721e6059cde3799ffa",
  "note": "Independent test-intent acceptance only: all three complete entries accepted; five observer regressions fail by assertion on exact baseline and pass candidate, preservation and standalone real public store/runner SQLite probes pass. Overall verification is separate: original full test gate reported the root-image sibling UNCHANGED/null assertion and baseline comparison is pending. See immutable verifier packet report/evidence."
}
```
