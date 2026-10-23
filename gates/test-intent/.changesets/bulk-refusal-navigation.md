---
schema: test-intent.v1
task: bulk-refusal-navigation
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Test-intent changes

Owner source:

> Add or extend a check that a stale refusal error does not survive navigating away and back (fails before, passes after).

`modules/ui-web/src/shell-v0/views/BulkOperationImmediateRefusal.test.ts` adds two cases that remove
and reattach the same Health or Brain instance after an actual refused rebuild request. They require
the transient refusal state, visible sentence and support code to be cleared on return. All existing
sentence, support-code, retry and later-failure checks remain unchanged.

The source is new relative to the merge-base with origin/main, so it is an isolated new test source
under the test-intent rules. This note records the added navigation contract without claiming
independent acceptance.

```json
{ "entries": [] }
```

## Acceptance records
