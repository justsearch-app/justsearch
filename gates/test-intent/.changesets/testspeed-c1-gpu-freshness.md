---
schema: test-intent.v1
task: t-20261018-6108b0
author-role: builder
author-session: 01a177d6-ac1a-7c41-8b26-647cb7a3d13a
---

# GPU freshness clock adaptation

The owner asked, "no, i want you to takeover the test speed batch". C1–C6 and
the clock/publication choices are agent choices in the delegated test-speed work,
not owner-authored or owner-adopted scenario quotations. No acceptance is authored here.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "Only healthyGpuMustStayFreshWithinIdleSamplingPeriod changes. Elapsed aging is driven through the existing StatusLifecycleHandler.setClockForTesting seam and one AtomicLong also stamps the mocked NVML samples. A bounded wait requires the NVML source and the expected sampledAt to reach the real loopback HTTP response before aging; it never waits for stale=false. The original healthy-GPU assertions and 10000 ms idle sampling period remain. The first aged HTTP response must retain the published timestamp and report ageMs=7100. Because every mocked reading uses the same handler clock, the unchanged timestamp and exact aged value prove the reading under test was not refreshed after aging. HTTP 200, latency below 500 ms, and the two-second HTTP request timeout remain in the unchanged get helper. Reader closure now runs in finally. The base test still executes in all five unchanged inheritor classes."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "Only healthyGpuMustStayFreshWithinIdleSamplingPeriod changes. Elapsed aging is driven through the existing StatusLifecycleHandler.setClockForTesting seam and one AtomicLong also stamps the mocked NVML samples. A bounded wait requires the NVML source and the expected sampledAt to reach the real loopback HTTP response before aging; it never waits for stale=false. The original healthy-GPU assertions and 10000 ms idle sampling period remain. The first aged HTTP response must retain the published timestamp and report ageMs=7100. Because every mocked reading uses the same handler clock, the unchanged timestamp and exact aged value prove the reading under test was not refreshed after aging. HTTP 200, latency below 500 ms, and the two-second HTTP request timeout remain in the unchanged get helper. Reader closure now runs in finally. The HTTP test still advances another 3000 ms, resamples, and observes that newly published reading after a further 10000 ms with its timestamp unchanged; its 45-second timeout is unchanged."
    }
  ]
}
```

## Preservation evidence

The existing public comment in
`modules/ui/src/main/java/io/justsearch/ui/api/StatusLifecycleHandler.java` says
"The reading carries its own timestamp". `OperatingGpuProjection.java` documents
that the first sample runs in the background and reports UNKNOWN until it exists.
The original test's assertion message is "healthy GPU must not turn stale before
the next scheduled idle sample".
The same inputs, reader ownership,
HTTP observation and freshness expectations remain; added source, timestamp,
age assertions prevent an UNKNOWN view or a refreshed reading from satisfying
the aging case: every mocked reading uses the same handler clock, so a refresh
after advancing it would change sampledAt and reset the reading age.

Raw commands, UTC times, original XML, source hashes, daemon/profile observations,
comparison mutations and summaries are retained under `tmp/testspeed-c1/evidence/`.
The initial adaptation run passed the seven matched healthy-GPU executions:
summed JUnit durations 69.041 s before and 5.623 s after. This is a case-duration comparison;
configuration, cache and compile time are not a suite or CI speed claim.
The broader selected classes passed 112 tests with zero skips/failures; UI compile,
Spotless, pmdMain, pmdTest and pmdIntegrationTest completed successfully. In separate
owned comparison clones, changing only `gpuAge > gpuStaleAfterMs` to
`gpuAge > 6_000L` in StatusSnapshotCache.ageOperating made both the base and HTTP
healthy-GPU tests fail at the intended aged-freshness assertion, for both original
and rewritten test sources (2/2 per arm). Mutation diffs/hashes, raw XML and HTTP
NVML timestamp/age witnesses are retained. The delivered production source is
unchanged. Final review removed only three newly added Mockito invocation-count
assertions: multiple identical same-clock setup readings are valid. Known source,
exact published timestamp and aged age assertions remain. The reviewed final
selection passed all seven original identities with summed JUnit duration 5.817 s,
compared with the original 69.041 s baseline. The broader 112 and paired mutations
above describe the initial adaptation; they were not repeated for removal of the
extra call-count checks. Reviewed final raw evidence is under
`tmp/testspeed-c1/evidence/reviewed-final.*`. Independent verification and
test-intent acceptance remain outstanding.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a177e6-8c39-7423-93b5-dca337a309c8",
  "verdict": "accept",
  "digest": "sha256:858457bf71ce03a95841ae672c5b23447715a194f27d54fa30347de16149d878",
  "note": "Independent verification of c1cf44bc57111d0719efc76525826be0f5150918: both Adaptations preserve the oracle and truthfully label delegated agent choices. Matched runs passed all 112 identities with zero skips; target seven summed 64.440s before and 0.804s after. The actual 6000ms mutation failed both original aged HTTP assertions in each arm. Delayed publication/older timestamp/expiry counterexample passed both arms. Production unchanged. Historical author measurements were not used as execution proof."
}
```
