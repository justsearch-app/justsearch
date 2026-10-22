---
schema: test-intent.v1
task: bulk-refusal-self-contained
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Test-intent changes

Owner source: task request following verifier 4's rejection of 94159f108:

> Every place that produces the plain sentence (the Java read-time explanation and any UI-side mapping of the eight codes) produces the sentence together with the code as one text

> Where a surface already prints the code separately next to the sentence, remove the now-duplicate display so the code appears exactly once.

The existing checks below are amended relative to 94159f108: representative messages now include
the support-code suffix; visible-output assertions count the code once, and the Java projection
check now requires the support code inside the explanation. All prior cause, safety, stored-code,
non-bulk, unknown-code, promoted-with-gaps and lifecycle assertions remain. The obsolete requirement
that the explanation exclude the support code is replaced by the owner's explicit new contract.

- `modules/app-observability/src/test/java/io/justsearch/app/observability/operations/BulkReindexFailureProjectionTest.java`
- `modules/ui-web/src/shell-v0/components/AgentBulkOperationCompletion.test.ts`
- `modules/ui-web/src/shell-v0/components/BulkReindexFailure.history.test.ts`
- `modules/ui-web/src/shell-v0/components/BulkReindexFailure.ledger.test.ts`
- `modules/ui-web/src/shell-v0/components/chat/ToolCallCard.refusal.test.ts`
- `modules/ui-web/src/shell-v0/views/BulkOperationCompletion.test.ts`
- `modules/ui-web/src/shell-v0/views/BulkOperationImmediateRefusal.test.ts`
- `modules/ui-web/src/shell-v0/views/LibrarySurface.bulkOutcome.test.ts`
- `modules/ui/src/test/java/io/justsearch/ui/api/mcp/BulkReindexFailureTransportTest.java`

Every listed test source is newly added relative to the merge-base with origin/main, so the gate
reports them as isolated new sources and requires no entries or separate acceptance record under
[the test-intent rules](../../../docs/reference/testing/test-intent.md#isolated-new-test-files).
This changeset records the intent change requested by the builder brief without claiming independent
acceptance. The verifier checks the changed expectations and before/after evidence.

```json
{ "entries": [] }
```

## Acceptance records
