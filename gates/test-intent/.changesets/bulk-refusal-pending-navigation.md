---
schema: test-intent.v1
task: bulk-refusal-pending-navigation
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Test-intent changes

Owner source:

> a response that lands while the surface is hidden does not set its transient error (Health standard and recommended, Brain rebuild, model activation).

> if the user returns before the response arrives, the refusal shows normally, sentence and code.

> accepted operations keep their keys and progress across navigation; later failures after return render with sentence and code.

`BulkOperationImmediateRefusal.test.ts` adds held HTTP responses for all four controls. Its new cases
check the hidden transient state and the returning DOM, preserve refusals delivered after return,
and preserve acceptance delivered while hidden plus the later keyed failure. Existing refusal,
code-count, retry, cancellation, gap-decision and clear-on-hide assertions remain unchanged.

`BulkOperationCompletion.test.ts` additionally navigates away and back after acceptance, requiring
the key and resumed progress reads before its existing later-failure and polling-stop assertions.

Both files are isolated new test sources relative to the merge-base with origin/main. This note
records the added checks without claiming independent acceptance or weakening any expectation.

```json
{ "entries": [] }
```

## Acceptance records
