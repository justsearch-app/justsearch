---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b68-prompt-cache
---

# Test-intent entries

Item 5, the delegated host prompt-cache bound. The author leaves acceptance to the independent
acceptor. Process-fixture exclusions and the safety wrapper are recorded in the builder report.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerPromptCacheTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java"
      ],
      "class": "New",
      "behaviour": "Chat launches with an explicit 1024 MiB host prompt-cache bound. Evaluation and VDU use zero even with a valid development override. Decimal non-negative integer overrides are applied, including zero; malformed, blank, signed, fractional and overflowing values are refused with the property name and required type. The captured bound participates in managed launch identity and survives mode changes, profile changes, crash recovery, and VDU enter/exit. The lifecycle test observes the launch argv at each manager start seam, with normal, evaluation and overridden configurations; it also changes the ambient properties after capture to prove relaunches keep the head's choice.",
      "sources": [
        { "kind": "owner-words", "quote": "Normal chat launches llama-server with a bounded host prompt cache of 1024 MiB by default.", "location": "task t-20261005-b6d536 request" },
        { "kind": "owner-words", "quote": "When the head runs in evaluation mode (justsearch.eval.mode=true, the mode evaluation and scripted-agent runs use), the prompt cache is disabled (0), so measurements do not depend on what earlier requests left in the cache.", "location": "task t-20261005-b6d536 request" },
        { "kind": "owner-words", "quote": "VDU mode keeps 0, unchanged.", "location": "task t-20261005-b6d536 request" },
        { "kind": "owner-words", "quote": "One explicit override for development (for example a system property or setting read through the existing inference configuration, plus a dev-runner option that passes it), validated: a non-negative integer number of MiB; an invalid value is refused with a clear message, not silently ignored.", "location": "task t-20261005-b6d536 request" },
        { "kind": "owner-words", "quote": "Every relaunch path (mode changes, profile changes, crash restart, VDU enter and exit) must apply the rule for the mode it relaunches into.", "location": "task t-20261005-b6d536 request" }
      ],
      "evidence": "gates/test-intent/evidence/b68-prompt-cache.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerPromptCacheTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java"
      ],
      "repaired": true
    },
    {
      "items": ["modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerLaunchFlagsTest.java"],
      "class": "Obsolete",
      "sources": [
        { "kind": "owner-words", "quote": "Normal chat launches llama-server with a bounded host prompt cache of 1024 MiB by default.", "location": "task t-20261005-b6d536 request" }
      ],
      "conflictSearch": "decision records searched for a conflict: none; the exact-argv contract keeps its GPU window, slots, KV types, reasoning and projector expectations and adds the explicitly required host cache flag. The VDU zero-cache expectation is preserved."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-prompt-cache-20261012",
  "verdict": "reject",
  "digest": "sha256:f68bd3c7a576301b361e8dbec96fe2d70fb613d6e855ee8111c359e2953b1c74",
  "note": "Behaviour verified (both new checks fail before by assertion, pass after; live child logs show 1024/0/256 bounds and eviction). Rejected on sources only: every source in both entries is labelled owner-words with location 'task t-20261005-b6d536 request', but the quoted text is the orchestrator's decision under the owner's delegation, not the owner's words. Re-cite as delegated-choice (delegationQuote, delegationLocation, delegationScope, choice, label 'agent choice'). Obsolete conflict search repeated: no decision record mentions the prompt cache."
}
```
