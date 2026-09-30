# Prepared frontend consent continuation — September 13

C2-3 client item at47ebd09c5 plus this change. OperationClient's approval method now
returns the capsule, operation key and preparation nonce together; its only production
consumers are the invoke and undo consent flows. Both send the reference outside public
arguments on retry. A gate's expected key/nonce must match the approval response; missing,
changed or unpaired values fail without redispatch. Legacy non-prepared approvals still
work when the gate has no preparation reference. Undo also accepts an explicit caller key.

Invoke snapshots the exact serializable JSON request before its first network call so a
caller editing nested input while the ceremony is open cannot substitute retry arguments.
The backend remains the identity and consent authority; the snapshot is a request-local
value, not another durable store. The old token-only return and stale React-client comments
are retired; the prior success assertion now checks the full capability object.

Negative989 runs30 tests with6 failures. Its two retry-body assertions prove dropped
references; four refusal cases initially reached an unstubbed third fetch and are not
claimed as causal refusal proof. After supplying an explicit unexpected-success response,
negative991 removes the reference comparison and request snapshot from the final source:
30 tests run with4 intended failures (three improper retries and one mutated input).
Exact source restoration passes the full992 frontend suite: **6480 tests / 483 files**,
zero failed/pending/todo tests. Type checking and focused ESLint pass. The full log retains
24 happy-dom Fetch AbortError messages from onAsyncTaskManagerAbort/teardownWindow;
these are teardown cancellation output, with no unhandled/test failures reported.

The UI skill's affected-file lookup reports no visual steps for OperationClient.ts.
The ui-step-coverage gate passes (54 source paths,4 core surfaces,2 strict endpoints).
No visual screenshot or live approval/model behavior is claimed for this transport item.
[Verification JSON](prepared-client-verification.json) records commands, raw locations,
source hashes and limits. Keep logs/JSON until acceptance plus30days and export before
worktree release. Frozen target previews, agent/workflow consumers and integrated C2-3
acceptance still follow; no prepared producer is activated. Merge remains F / PR1.
