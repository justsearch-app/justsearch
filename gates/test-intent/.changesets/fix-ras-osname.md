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
      "whatMoved": "Replaced the class-wide os.name override with a try/finally scope around the three leftover-variant status checks, three model-resolution activation attempts and CPU-baseline deactivation. Those operations retain Windows 11 and the same Windows native files. All 32 tests and every assertion remain; settings/SQLite and Mockito initialize under the real host OS rather than depending on earlier tests in the JVM."
    }
  ]
}
```

## Acceptance records
