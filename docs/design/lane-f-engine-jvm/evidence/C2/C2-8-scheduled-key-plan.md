# C2-8a: carry the scheduled-run operation key from the public producer

2026-09-14; base5a4d1dde3. This is the scheduling ingress obligation placed in C2-8
by C2-3d; it does not claim ingestion resume or change the stage's remaining items.

## Decision and ownership

POST /api/presence/run accepts optional operationKey alongside prompt/conversationId.
A supplied key must be a string and is validated by the operations owner; absent mints
once and the response returns that key. BackgroundRunService gains a keyed schedule
overload; existing internal callers keep the null-key convenience overload. Acceptance
uses the existing canonical digest of AgentRequest plus normalized delay and carries the
existing context/provenance. No second record, token, history writer or retry registry.

An existing matching attempt returns before touching the timer, even if this scheduler
is now closed. Changed input under the same key refuses OPERATION_KEY_REUSED. Concurrent
matching accepts therefore install at most one timer. New accepted work retains current
shutdown/admission/error handling. Interactive background runs remain interactive: this
item adds retry identity, not durable prompt replay or inherited authority for nested tools.

The response includes operationKey, state, replayed and whether this request scheduled new
work. A recorded terminal outcome is reported without a new effect. Key conflicts/expiry,
invalid keys and storage/capacity failure retain their typed non-success HTTP responses.
Caller data cannot select provenance or survival.

The existing UI caller mints the UUIDv7 at the logical action boundary. Extract the
existing settings key generator into one shared api operation-key helper and use it from
both settings and background runs. Do not add another UUID implementation. Transport
retry must reuse that captured key/body; a new deliberate action gets a new key.

## Per-item commit and checks

One C2-8a commit covers service overload/dedup, controller wire, UI key use, tests and
canonical request documentation. Push immediately. Root owns controller/UI/docs and all
builds; a bounded worker owns BackgroundRunService and its existing tests.

Prove: supplied key retained, same-key pending/terminal retries run once, changed prompt or
delay conflicts, concurrent duplicates install one timer, closed scheduler still answers a
recorded result, missing-key internal behavior preserved. Controller tests use a real runner
and SQLite where practical, plus malformed key/refusal responses. UI tests assert key/body
capture and response failure handling; settings tests continue to exercise shared key grammar.
Run relevant Java/frontend/gates and independent review. C2-8 ingestion/root preparation,
C2-9 scoped job recovery and C2-10 reindex remain the next substantive producer work.
