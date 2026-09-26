# D1-2 readiness review — 2026-09-21

## Scope and evidence boundary

This is the independent refute-first review of the D1-2 readiness plan and the
initial conditional-publication seams. The reviewed base was
`b4d01c1cc88c237a0a13d614f82bcb0c82b67803` plus the uncommitted readiness WIP in
`lane-f-pr1-verify`, on Windows/PowerShell. No build, test, or live stack was run
by this reviewer. Run 2298 completed before the conditional-publication WIP and
therefore does not prove it. The final seam re-review inspected the retained
2299-2302 command output, source inventories, counts, and JUnit XML described
below; these are externally executed evidence rather than commands run by this
reviewer.

Primary review inputs were `stages/D1.md:220-251`,
`readiness-plan-2026-09-21.md`, the core component contracts and registry, the
registry-backed capability and reason-retaining wrapper, `StatusLifecycleHandler`,
`KnowledgeServerBootstrap`, `KnowledgeServerHealthMonitor`,
`ReadinessReconciliationTrigger`, `RuntimeManifestListenerWiring`, and
`RuntimeManifestPublisher`.

## Actual conditional-seam review

The two conditional forms have the required atomic boundary. The registry
compares either the complete target component or complete expected registry
snapshot and applies the mutation under the same monitor
(`DefaultEngineComponentRegistry.java:135-150`). A matching no-op succeeds
without a revision or callback; a mismatch returns false without mutation. The
full-snapshot form rejects an API change and an API state ABA because the
registry revision participates in equality. The reason-retaining decorator is
stateless and derives retention from the same expected observation passed to the
CAS (`ReasonRetainingComponentHandle.java:34-66`). No product defect was found
in these WIP seams by inspection.

The callback-lock regression is meaningful rather than a wrong-reason pass
(`DefaultEngineComponentRegistryTest.java:129-173`). It blocks the revision-2
callback, commits revision 3 on the test thread, releases in `finally`, captures
the callback timeout `AssertionError`, joins the publisher thread, and requires
no captured failure. It would fail if the registry monitor remained held across
the callback.

The initial concurrent-writer proof defect is corrected.
`onlyOneConcurrentConditionalWriterCanReplaceTheSameObservation` now captures
`RuntimeException` and `Error` from both raw threads, requires no captured failure,
requires both threads terminated, and still requires exactly one winner and
revision 2 (`DefaultEngineComponentRegistryTest.java:68-96`). It can no longer
pass with one successful writer and one silently crashed writer.

The 1296-state aggregate table is coherent with the documented essential versus
optional policy. Its pure table test cannot establish the sampling, publication,
or callback concurrency properties below; those require separate regressions.

### Final seam evidence and verdict

No remaining concrete defect was found in the bounded source diff or its tests.
The retained evidence ties the result to the final five-file source inventory:

- 2299 completed the full static task successfully. Its production-source hashes
  match the final seam; the concurrent-test correction followed it and is covered
  by the focused static checks in 2300.
- 2300 freshly executed 40 cases in four suites with zero failures, errors, or
  skips, including all ten `DefaultEngineComponentRegistryTest` cases and the
  three existing retention/adapter suites. Its command also ran
  `:modules:app-engine:spotlessCheck` and `:modules:app-engine:pmdTest`.
- 2301 deliberately removed only the full-registry precondition. The intended
  stale-API assertion failed at `DefaultEngineComponentRegistryTest.java:118`;
  the negative-control script restored the source bytes in `finally`.
- 2302 recorded the restored source hashes byte-equal to 2300 and completed the
  same 40-case selection with zero failures, errors, or skips. Its tasks were
  cached/up-to-date, so 2300 is the fresh positive execution; 2302 is restoration
  evidence rather than an additional fresh run. The retained JUnit XML reports
  ten passing registry cases and thirty passing lifecycle cases.

Evidence is retained under `tmp/2299-component-publication-*`,
`tmp/2300-component-publication-focused-*`,
`tmp/2301-readiness-cas-negative-*`, and
`tmp/2302-component-publication-restored-*`. This verdict covers preparation
seams only; it does not claim the D1-2 runtime migration is complete.

## Migration acceptance gaps

### Initial index readiness has a circular gate

`STARTING` projects to legacy `PENDING`
(`RegistryBackedCapability.java:128-145`), while `Capability.available()` is true
only for `READY` (`Capability.java:16-18`). The current sampler refuses its Worker
RPC while that capability is unavailable (`StatusLifecycleHandler.java:474-481`).
If the bootstrap and health monitor stop their current direct READY writes
(`KnowledgeServerBootstrap.java:341-359,712-734`), no fresh sample can establish
the first index READY.

The same migration can strand auxiliary initialization. The monitor currently
runs it only after observing a capability transition into READY
(`KnowledgeServerHealthMonitor.java:297-306`). The correction is to gate sampling
on structural client presence/physical owner availability, not aggregate READY.
A successful boot or health observation completes the owned initialization and
requests reconciliation without publishing index READY; only the fresh
four-input sampler may publish READY.

Required proof: a bound, healthy client with index `STARTING` performs one real
sample and reaches READY without a pre-existing READY capability; recovery runs
auxiliary initialization exactly once.

### Readiness publication must invalidate an API race

The index READY definition includes API/head READY in addition to Worker contact,
index health, and sample freshness (`stages/D1.md:239-244`; readiness plan
`:45-50`). The API owner can transition READY to ABSENT during shutdown
(`LocalApiServer.java:358-365,970-975`). A target-component-only CAS would still
match the index and publish a stale READY after that API transition.

The new full-registry conditional form closes this race atomically. The sampler
must pass the exact registry snapshot used for the conjunction and discard a
mismatch without retry. `readinessPublicationRejectsAnApiChangeBetweenObservationAndCommit`
(`DefaultEngineComponentRegistryTest.java:100-125`) exercises the API change, API
ABA, no-side-effect rejection, and successful fresh observation. The runtime
wiring remains required before this is shipped behavior.

### Sampler publication currently feeds back into sampling

`ReadinessReconciliationTrigger.wireTo` requests reconciliation on every worker
and inference transition (`ReadinessReconciliationTrigger.java:119-125`). Its
pending flag is cleared before the thunk runs (`:148-155`), so a sampler-owned
READY or UNAVAILABLE publication can enqueue an immediate second Worker RPC.
Burst coalescing does not prevent this self-feedback. Preserve the monitor's
per-tick request path (`KnowledgeServerHealthMonitor.java:320-341` and
`HeadlessApp.java:655-674`), while routing physical/API/other-component changes
to reconciliation and suppressing sampler-owned index outcomes.

Required proof counts actual sampler/RPC invocations: one sampler publication
must schedule zero follow-up passes, while a physical index transition and an API
transition each schedule the intended pass.

### The runtime manifest needs one ordered aggregate writer

The current manifest wiring listens only to inference and worker capabilities
(`RuntimeManifestListenerWiring.java:73-150`). API-only and encoder-only changes
therefore cannot refresh the four-component aggregate. Those callbacks also pass
their independently derived lifecycle into `publishWorkerReady`,
`publishWorkerFailed`, and `publishAi`, each of which can overwrite the manifest
lifecycle (`RuntimeManifestPublisher.java:482-532,557-598`). Leaving those writes
in place would retain multiple aggregate authorities.

Use one registry subscription for aggregate lifecycle publication. Subscribe
before reading the bootstrap snapshot, derive from that exact immutable snapshot,
and retain the returned subscription for teardown. Worker/AI/mode/chat callbacks
may continue to own their axis-specific fields, but must preserve rather than
write lifecycle.

The registry deliberately releases its monitor before invoking listeners
(`DefaultEngineComponentRegistry.java:135-150`), so concurrent or reentrant
publication can deliver revision N+1 before a delayed N. The manifest publisher's
`synchronized` methods serialize arrival order but do not reject stale arrival.
Add a registry-revision high-water mark guarded by the existing publisher monitor.
Reject revisions below the high-water mark and advance it before I/O and also on
an aggregate no-op. An equal-revision replay remains eligible: because commit
writes disk before updating `current` (`RuntimeManifestPublisher.java:833-839`),
this lets the same current registry snapshot retry a failed manifest write while
still excluding every older observation. After a successful write, the existing
lifecycle equality check makes the equal-revision replay a no-op. This requires
no new timer, store, or independent retry policy.

Required proof covers API-only and encoder-only aggregate updates, subscription
bootstrap racing a callback, revision 3 followed by revision 2, revision 3 as an
aggregate no-op followed by a different revision-2 aggregate, a failed write
followed by a successful replay of the same revision while an older revision is
still suppressed, and no delivery after subscription close. The subscription
must close before the component registry and manifest publisher; the current
final cleanup closes those at `HeadlessApp.java:1370-1382`.

## Completion boundary

D1-2 is not proven by the reviewed WIP. The atomic conditional foundation is
sound by inspection, subject to the thread-failure test correction. Completion
still requires the runtime owner migration and the focused regressions above,
the exhaustive aggregate/schema checks, affected module tests, schema/UI checks,
both host conformance suites, and the specified real boot showing non-vacuous
component registrations and fresh index readiness.
