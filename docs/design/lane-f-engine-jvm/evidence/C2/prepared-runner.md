# C2-3b: runner preparation serialization prerequisite

2026-09-13, based on a97a03510. The runner now serializes unknown-key pure
preparation through 256 fixed reentrant lock stripes. The callback receives a stable
request (an absent key is minted once) and a validated metadata row snapshot. Existing
rows bypass application preparation. Pending read/save use the original operations
store; save is reentrant and neither callback nor handler preparation holds SQLite's
connection lock. All acceptance paths share the stripes. No second key registry,
writer, persistent authority or unbounded lock map is introduced.

The callback ends before acceptance, observation or effect admission. Acceptance
holds the stripe only for the store transaction; attaching live completion controls
and publishing listeners happens afterward. Same-stripe nested acceptance/lookup
is rejected as API misuse. The snapshot lets a dispatcher decide to return a receipt
without invoking completion listeners under the preparation lock. Scope callers stay
within one key and perform only pure preparation and pending reads/writes. A scope is
not authority to execute an accepted effect. Dispatcher/cipher and approval nonce
wiring remain next; replay-schema producers are still refused in production.

## Verification and design correction

- Negative959 removes key locking and causes the original concurrency test to observe
  two preparations. Positive960 passes335 represented cases before the callback audit.
- Root identified a completion publication race in the initial design. A concurrent
  finish can land between acceptance's OPEN snapshot and controlFor's fresh read.
  Publishing a pre-existing listener there under the stripe deadlocks a listener
  waiting for another thread to request the same key. Negative962 executes2 represented
  cases with1 intended failure: the listener's same-key request times out. The test
  models that exact durable-finish/publication gap with the existing proxy-store pattern.
  Negative961 was only a missing Mockito dependency compile failure, not behavioral proof.
- Rather than add a publication queue or another lifecycle mechanism, root moved
  control attachment outside the stripe and narrowed the callback to a pure metadata
  scope. A regression also proves another thread can read SQLite inside the callback,
  pending save works, nested observer calls refuse, and acceptance succeeds afterward.
- Run963 fails on a test assertion's generic overload ambiguity; app-api204 and
  services94 execute successfully in that run. Fixed964 succeeds with336 represented
  cases, observability38 executing and the other two test tasks reusing963. All have
  zero failures/errors/skips. Affected PMD and UI integration compilation pass.
- Negative965 repeats lock removal against the final scope API and executes2 cases,
  with1 intended failure: both settled callers prepare independently before release.
  Restoring the exact source yields successful966,336 cases across52 suites. Its
  observability38 are FROM-CACHE from964; app-api204 and services94 are UP-TO-DATE
  from963. This is transparent reuse, not a claim of a fresh full suite.

[Verification JSON](prepared-runner-verification.json) records command, counts,
source hashes and reuse origins. Raw logs/counts/XML are in lane tmp under
`c2-3-preparation-runner-negative{959,961,962,965}` and
`c2-3-preparation-runner{960,963,964,966}`, with `.txt`, `-counts.json` and
`-xml/` suffixes as applicable. FAILED task status means executed-and-failed, not
reused. Retain until lane acceptance plus30days; export before worktree release.

Read-only review confirms publication is outside the lock. It also notes that the
same-stripe guard does not prevent a callback from requesting a different or absent
key. That is explicitly outside the trusted callback contract (stay within one key),
not an alternate supported scope. Root retains the narrow contract instead of adding
thread-local scope state; dispatcher integration must adhere to it and is still owed.

No new live API/model, installed restart, hosted v3 or integrated C2-3 acceptance
is claimed. This local runner proof does not close C2-3b/c or activate a producer.
