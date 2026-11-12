---
schema: test-intent.v1
task: t-20261112-toolstate
author-role: orchestrator
author-session: fb2b646b-9739-4a64-af38-29fbcca0bec9
---

# Capability-health recovery check reads only what was fully delivered

`CapabilityHealthBridgeRecoveryTest.registryTransitionsNeverFabricateRecoveryOccurrences` threw
`ConcurrentModificationException` at line 87 on CI four times (2026-11-03 to 2026-11-11, main run
42501602679 among them), passing on rerun. The registry delivers condition changes on its own
delivery threads, which appended to the test's plain `ArrayList` while the assertion streamed it;
the assertion could also run before every published change had been delivered.

The capture list is now a `CopyOnWriteArrayList`, and the test waits (10 seconds at most) until
every change the registry published (`currentSeq()`) has reached the observer before it checks that
none is `OCCURRENCE_APPENDED`. The assertion, inputs and transitions are unchanged. An independent
check forced a callback append during the read: the old test threw the exception, the new one
passed; a fabricated occurrence delivered late still fails the new test, and the old one missed it.
No product code changes.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeRecoveryTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The observer's capture list became thread-safe (CopyOnWriteArrayList) and the test waits until every change the registry published (currentSeq) has been delivered before asserting that none is OCCURRENCE_APPENDED, because delivery runs on the registry's own threads and appended to a plain ArrayList while the assertion streamed it (ConcurrentModificationException at line 87 on CI, four times). The transitions, the asserted condition and the inputs are unchanged."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "claude-subagent-toolstate-cme-acceptor-1",
  "verdict": "accept",
  "digest": "sha256:33343870c90a33d45c18fa0fea03fc8e15a9a6620d47a6a62a3240a584e27157"
}
```
