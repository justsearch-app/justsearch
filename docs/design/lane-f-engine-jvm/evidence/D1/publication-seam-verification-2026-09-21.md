# Conditional component publication proof

Prepared on `b4d01c1cc` plus the five Java files identified by
`tmp/2300-component-publication-focused-sources.json`. This adds publication
primitives; it does not replace the runtime capability authorities, wire the
sampler, or claim D1-2 acceptance.

The component-only conditional transition compares a complete immutable
observation. The full-registry form also protects related preconditions such as
API READY. Both compare and commit under the existing registry monitor, notify
outside it, accept a matching no-op without publishing, and reject stale writes
without side effects. The stateless reason-retaining handle shares the existing
ReasonRetention rule and capability-state mapping. Ordinary publications retry a
component race; full-registry sampling publications never retry an obsolete result.

The [independent review](readiness-review-2026-09-21.md) identified one test weakness:
a competing thread's uncaught exception could be missed. The test now captures
and rejects either thread's failure. The callback-lock regression similarly
captures callback timeout errors, so escaping through a five-second timeout cannot
produce a passing test.

| Run | Result and limit |
| --- | --- |
| 2299 | `spotlessCheck pmdAll --continue` passes in3m39s. All independent static checks run; full test execution is separate. |
| 2300 | Registry, adapter, retention, corrupt-cause tests:40 cases/4 suites, zero failures/errors/skips, both module tasks fresh. Includes the review correction; affected Engine format/PMD also pass. |
| 2301 | Omit only the full-registry precondition. The stale-API publication regression fails at its expected-false assertion (line118 in the retained source), with1 test/1 failure. Production source restored byte-exactly. |
| 2302 | Restored40 cases/4 suites represented, zero failures/errors/skips: Engine FROM-CACHE and services UP-TO-DATE reuse the matching2300 results. Not a fresh execution claim. |

Focused command (2300 additionally includes Engine spotlessCheck/pmdTest):

```powershell
.\gradlew.bat :modules:app-engine:test --tests io.justsearch.app.engine.DefaultEngineComponentRegistryTest :modules:app-services:test --tests io.justsearch.app.services.lifecycle.RegistryBackedCapabilityTest --tests io.justsearch.app.services.lifecycle.ReasonRetentionTest --tests io.justsearch.app.services.lifecycle.WorkerCapabilityCorruptLatchTest -PtestParallelism=1
```

Retained logs, source inventories, counts and copied XML are under this worktree's
`tmp/2299-component-publication-*`, `tmp/2300-component-publication-focused*`,
`tmp/2301-readiness-cas-negative*`, and `tmp/2302-component-publication-restored*`.
The negative driver is `tmp/2301-readiness-cas-negative.py`. Retain through lane
acceptance plus30days and export before removing the worktree.

Still required in the runtime migration: structural-client sampler eligibility,
bootstrap/recovery initialization, no sampler feedback, clock-age demotion without
an RPC, single revision-ordered manifest writer, all1296 schema2 combinations,
schema/UI generation and both host conformance suites. The plan and review name
these separately; the seam tests are not substitutes for that connected proof.
