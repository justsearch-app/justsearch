# Frozen approval preview backend — September 13

C2-3 display projection atb1ff98611 plus this item. The handler projects a bounded
OperationApprovalPreview only from its frozen preparation. The dispatcher requests it
only when prepared execution needs fresh confirmation. Missing previews refuse by
default; matching receipts, AUTO, durable-grant and valid-capsule paths do not call it.
This replaces neither canonical public identity nor the persisted prepared payload.

The value is a nonblank metadata summary bounded to8192 UTF-8 bytes, with a redacted
toString. It names target/root/scope, never note body, prompts or credentials. Oversize
refuses instead of truncating an identity. This typed projection enforces one bound
across exception and pending state without adding another stored target/envelope version.

HTTP428 and pending-by-id GET use its complete summary as the existing argsSummary,
instead of raw content-bearing public JSON. MCP retains it on the same TTL-bounded
pending record. The existing SSE routing event remains unchanged and contains no
preview; no ledger/history projection receives it. Public args remain separately
available for exact capsule binding/redispatch.

Negative994 executes11 cases with3 intended failures: missing dispatcher target preview
and both HTTP invoke/undo showing raw arguments instead. Positive995 executes463 cases.
Run996 fails only a test compile assumption (Subscription is not AutoCloseable); no UI
proof is claimed from it. Root uses the existing explicit unsubscribe method in finally.
Final997 passes **463 cases /55 suites**, zero failures/errors/skips: UI113 execute,
services110 and agent-api240 reuse unchanged successful995. PMD and integration-test
compilation pass. [Verification JSON](prepared-preview-verification.json) gives commands,
source hashes and raw paths.

Coverage proves the original target after SQLite reopen without re-preparation, default
preview refusal, valid approval/receipt bypass of display generation, complete long-target
HTTP/peek delivery without raw content, UTF-8 byte bounds and redacted toString, plus
actual pending storage/SSE-channel publication on MCP without target metadata in routing.
Reopen is a store-level witness, not an installed/application restart. Read-only design
mapping confirmed this projection seam and identified the long-summary UI gap; root
reread connected code and exact negative/final output.

The 8192-byte prepared display intentionally differs from the existing200-character
raw-argument fallback. **Long-target wrapping and visual proof are the next owned item**
before C2-3 acceptance; current AuthorizationHost has no explicit wrapping rule. No live
UI/model/hosted proof or producer activation is claimed. Agent/workflow continuations
and integrated/full/fresh-v3-hosted proof remain required. Merge remains F/PR1. Retain
raw logs/counts/XML until lane acceptance plus30days and export before worktree release.
