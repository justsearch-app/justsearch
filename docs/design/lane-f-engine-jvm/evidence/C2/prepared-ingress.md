# Prepared HTTP references and locked ingress — September 13

C2-3 follow-on at227bcc001 plus this item. HTTP invoke and undo now accept a
server-issued preparationNonce separately from public arguments, require its operation
key, and call the exact seven-argument dispatcher. Malformed/unkeyed references refuse
before dispatch. Absent nonce retains existing raw/keyed call behavior.

Operations invoke/undo, MCP operation dispatch and server-side approved execution
project the existing STORE_LOCKED contract through OperationInvocationResponse's common
metadata factory. HTTP invoke/undo answer423; MCP reports a structured error; approval
reports executed=false with executeErrorCode=STORE_LOCKED while preserving the issued
capsule/key/nonce. Unlock is a prerequisite, so retryable=false does not invite blind
retries. No key exception or stored prepared content is exposed or logged as a handler
failure by these arms. Existing preparation-unavailable remains409 CONFLICT.

Negative985 executes8 cases with6 failures: four prove lost typed locked projection.
Its two forwarding failures were unstubbed old-overload NPEs and are not claimed as
causal proof. Root corrected those fixture stubs; negative986 executes4 cases with2
intended exact-overload verification failures, proving the nonce was discarded.
Positive987 executes283 cases. Final988 passes284 cases across47 suites with zero
failures/errors/skips: UI80 execute, app-api204 reuse unchanged successful987. PMD and
UI integration-test compilation pass. Captured XML includes the invalid-reference and
preparation-unavailable cases. Exact command/counts/source hashes are in
[verification JSON](prepared-ingress-verification.json).

Root reread the affected catches, null-nonce compatibility, exact-overload assertions
and captured failures. These are controller/port tests; no live server, installed,
model or hosted evidence is claimed. Frontend retry propagation and frozen preview
remain next, with agent/workflow continuations and integrated C2-3 proof required.
Raw evidence stays in lane tmp per the JSON; retain until acceptance plus30days and
export before worktree release. No prepared producer is activated; merge stays F/PR1.
