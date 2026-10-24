---
schema: test-intent.v1
task: t-20261022-f9aafb
author-role: builder
author-session: 602f2ee3-8f6d-4615-a933-4844103a7491
---

# Test-intent entries

Author: the orchestrator's Claude session, which wrote this ten-line build change itself (below the depth floor). The independent Codex verifier ran the preservation and memory checks; acceptance comes from a separate session.

```json
{
  "entries": [
    {
      "items": [
        "build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the memory issue needs a different fix in general",
          "location": "task t-20261022-f9aafb, user turn 2026-10-22 (swap filling when test-heavy Gradle builds overlap)"
        }
      ],
      "assessment": {
        "purpose": "Give every Gradle Test task JVM on Linux with glibc the native-heap trim flag -XX:TrimNativeHeapInterval=30000, which the Engine already uses (modules/ui/build.gradle.kts), so test JVMs that boot real ONNX models return freed native memory to the OS. Test selection, filters, tags, timeouts, heap size and retry settings are unchanged.",
        "callersAndStateOwners": "JvmBaseConventionsPlugin configures every Test task through tasks.withType(Test).configureEach, including tasks registered late. isGlibcHost is computed once per project from `getconf GNU_LIBC_VERSION` through providers.exec, the same probe modules/ui/build.gradle.kts uses for the Engine; it is false on Windows and on non-glibc Linux, where no argument is added. No test or production source changes; no test reads the flag.",
        "indirectBehaviours": "The flag only makes glibc return freed heap pages periodically; it does not change Java heap limits, GC choice or test ordering. Possible indirect effects: a test that measures native memory or timing could see different numbers, and the configuration cache must still be reused with the exec probe. Both were checked.",
        "evidence": "Independent Codex verifier (task t-20261022-f9aafb, results verifier-trim-2) on candidate ec4bec991c against baseline 1c399f63b: (1) live test JVM command lines show the flag absent before and present after, also on an extra late-registered Test task; (2) the same 281 tests (core, indexing, app-util, worker-services, app-services, app-launcher unit lanes plus PersistentExtractionSandboxTest.argFileEncodingRoundTripsThroughTheJdkParser, ChatLaunchCompatibilityTest.chatLaunchPreservesPipesArgumentsEnvironmentAndWorkingDirectory, LauncherInternalsTest.ensureDataDirCreatesDefaultDirectory) pass on both; (3) a second identical invocation reports 'Reusing configuration cache.' on both; (4) with real models, BrakeExhaustedWorkerServesReadOnlyTest's test JVM floor 35 s after each server close fell from 2.40/4.19 GiB to 1.23/1.89 GiB. Preservation runs add no new checks, so their logs stay with the verifier's evidence rather than run-evidence files.",
        "unresolvedRisks": "Windows and musl paths were inspected, not run. Per-cycle growth of about 0.66 GiB remains with real models; it comes from tokenizers not released on some close paths, a separate product repair in the same task."
      }
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a16bdf-9828-7ad3-b184-7830085d0677",
  "verdict": "accept",
  "digest": "sha256:b1ee97b43348c44edf8ebfaa15fc856e161becfb1a497175db8fa93f4ce58b84"
}
```
