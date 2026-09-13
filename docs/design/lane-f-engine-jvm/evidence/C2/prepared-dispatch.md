# Connected prepared dispatcher — September 13

C2-3b/c backend integration at f28cc04d4 plus this item. This completes the generic
backend mechanism, not C2-3 acceptance or producer activation.

## Behavior and ownership

The dispatcher looks up public-input identity before any preparation or decryption.
A matching accepted row returns its receipt; changed input refuses OPERATION_KEY_REUSED,
even while content keys are locked. An accepted incomplete row never authorizes another
caller-driven effect. Unknown preparation freezes once under the runner key scope,
persists its classified envelope, then leaves the scope before gating or acceptance.
A supplied stale/missing nonce refuses before any replacement preparation. Approval
uses the exact prepared capsule domain. Acceptance atomically transfers that envelope.

Current provenance, plugin trust, hard stop, grant scope and capsule authorize the
request. Frozen original context, executor and occurrence time supply attribution,
not authority. Reuse current admitted work only when every frozen context axis matches;
otherwise attach the detached origin as a separately admitted child. Async execution
retains that owner after the HTTP/request owner closes. Never restore a process work id
or signed intent token from disk.

Handlers explicitly validate replay schema/classification/payload before persistence
and after decode. Validation is pure format validation: it cannot select a new target
or inspect mutable execution state. Unsupported handlers retain default refusal.
Prepared undo has its own preparation/execution methods and canonical undo input.
DurableGrantScope's default prepared check refuses a shortcut; only an implementation
that understands the frozen value can prove containment. Raw scopes remain unchanged.

HeadAssembly passes the existing DataKeyManager's StoreCipher through SubstratePhase
and OperationSubstrateInit. CONTENT has no disabled/locked plaintext fallback. Failure
to seal/decode required preparation refuses before acceptance/approval. Its typed HTTP/MCP
locked projection belongs to the immediately following ingress item; no live API claim
is made here. Passthrough handlers retain transient preparation and existing behavior.

## Verification

Final984 passes **424 cases / 54 suites**, zero failures/errors/skips, with all three
test tasks executed. Affected PMD and UI integration-test compilation pass. Exact
command, task counts and source hashes are in [verification JSON](prepared-dispatch-verification.json).

- Negative976 was only a package-private test-constructor compile failure; the fixture
  uses the public constructor instead. Negative977 then executes7 cases with1 intended
  failure proving the old gate precedes preparation.
- Initial978 executes334 cases with3 failures in existing unsupported/malformed format
  tests. Production handler format validation fixes these without changing those tests.
  Positive979 passes335 cases; positive980 passes341 (services102 execute, agent-api239
  reused from979). Positive981 passes424 with agent-api239 reuse.
- Negative982 removes only the supplied nonce guard. Seven cases execute with1 failure:
  stale approval reaches replacement preparation/confirmation instead of typed refusal.
- Negative983 removes only the runner key lock. Seven cases execute with1 failure:
  concurrent unknown dispatches prepare twice. Root restored exact captured source before984.
- Real SQLite reopen proves frozen forward/undo target reuse and one effect. This is
  store reopen, not a process/installed restart witness. Content locking uses a real
  DataKeyManager and sealed bytes, but does not yet restart that manager/application.
- PreparedDispatchAdmissionTest uses the real EngineAdmissionController and SQLite for
  both same-caller attachment and cross-client child admission, including async completion
  after request close. It is local port integration, not an HTTP-server/live-model proof.

An independent read-only current-source review found no concrete defect; it specifically
confirmed the actual-admission test resolves its earlier ownership proof gap. Root reread
both negative failures and final captured XML. The reviewer did not execute checks.

Raw lane tmp logs/counts/XML use c2-3-preparation-dispatch{978..981,984} and
c2-3-preparation-dispatch-negative{976,977,982,983}.
Retain artifacts until lane acceptance plus30days and export before worktree release.

## Remaining acceptance

Frozen approval preview; HTTP and frontend exact-reference retry delivery; agent/workflow
continuations; typed locked ingress; remaining C2-3 ingress and integrated/full/live/model
proof; fresh hosted v3 proof; and C2-11 keyed installed recovery remain required. No real
prepared producer opts in here. Merge placement remains F / PR1; do not merge this item.
