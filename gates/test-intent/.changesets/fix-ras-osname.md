---
schema: test-intent.v1
task: fix-ras-osname
author-role: builder
author-session: p70ssby1
---

# Test-intent entries

Confine the Windows native fixture to the service operations that require it. Fixture setup,
mock creation, assertions and cleanup run with the host OS property. The scope includes awaiting
background activation/deactivation and restores the original property even when the action throws.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "Replaced the class-wide os.name override with a try/finally scope around the three leftover-variant status checks, three model-resolution activation attempts and CPU-baseline deactivation. Those operations retain Windows 11 and the same Windows native files. All 32 tests and every assertion remain; settings/SQLite and Mockito initialize under the real host OS rather than depending on earlier tests in the JVM. The class also restores justsearch.data.dir and the two system properties EnterprisePolicyServiceImpl publishes (policy.gpu_acceleration_enabled, justsearch.policy.disallowExternalInferenceServers) after each test, so later tests in the JVM see their own values; no assertion changes."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-ras-osname-codex",
  "verdict": "accept",
  "digest": "sha256:30d2860ca5f80d5d9536fd01abe3aa55ec4aec0580e1886c1e0a27d4772835f6",
  "note": "Cold and random class runs pass 32/32; all seven fresh-JVM methods pass; primed before/after runs preserve all 32 tests and assertions. All 71 display-related suite failures reproduce before. The gate remains blocked by inherited uncovered changes."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-ras-osname-2-codex",
  "verdict": "accept",
  "digest": "sha256:5ff7423f3638fd61fb2c0843021a889ba7805c96dd09dce9b8a19378880f6ba7",
  "note": "Same-JVM unset, seeded and forced-failure probes restore all three properties. Isolated/random runs pass 32/32; suite matches 71 baseline display failures. Inherited static-map and HTTP-client lifetime findings are recorded."
}
```
