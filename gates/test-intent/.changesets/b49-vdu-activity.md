---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b49-vdu
---

The verifier's mixed PDF, search, 45-second cooldown and explicit enrichment pass reproduce zero completed units on origin/main and f6d579a86. The production composition applies the automatic idle threshold at the accepted pass's first interruption checkpoint. An accepted pass needs a fresh activity boundary; automatic acceptance retains its existing idle gate. This entry awaits independent acceptance.

```json
{
  "entries": [
    {
      "items": ["modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/VduActivityCheckpointTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "The production-composed interruption signal lets an accepted VDU pass run despite the earlier 45-second-old search, still interrupts for current energy reduction or a bulk hold, clears with those holds, and interrupts for activity during that pass.",
      "sources": [
        {"kind":"owner-words","quote":"Find the cause from the Engine and llama-server logs of the failing run (why each unit does not complete: projector not loaded or on a different device than the plan says, a request rejected, a deadline, a gate that never opens, a status that is never written) and fix it at the cause.","location":"task t-20261005-b6d536 request"},
        {"kind":"owner-adopted-scenario","task":"t-20261005-b6d536","scenario":"S7","quote":"while a game runs or adapter memory is low, no new bulk GPU batch starts","label":"agent-drafted, owner-adopted"}
      ],
      "evidence": "gates/test-intent/evidence/b49-vdu-activity.json",
      "newChecks": ["modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/VduActivityCheckpointTest.java"]
    },
    {
      "items": ["modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaProjectorPolicyTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "An administrator GPU-off policy keeps the vision projector and llama-server operations on CPU despite the default projector-offload preference, and the managed configuration identity reflects that changed placement.",
      "sources": [
        {"kind":"owner-words","quote":"live: the verifier's VDU operation completes its units on the candidate with GPU on, and with GPU off either completes on the CPU or reports the existing unavailable/unmet reason (no silent zero-unit completion).","location":"task t-20261005-b6d536 request"}
      ],
      "evidence": "gates/test-intent/evidence/b49-vdu-activity.json",
      "newChecks": ["modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaProjectorPolicyTest.java"]
    }
  ]
}
```

## Acceptance records
