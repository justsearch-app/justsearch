---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b70-prompt-cache-followups
---

# Test-intent entries

Follow-ups to the delegated host prompt-cache bound (item 5): the activation self-test candidate's
cache, and registering the development override with the other inference keys. Like the bound
itself, these are the orchestrator's decisions under the owner's delegation: the owner asked "whats
best for development ?", the orchestrator recommended 1 GiB for chat, 0 for evaluation and scripted
runs, VDU unchanged and a development override, and the owner replied "do whatever you think is
best. your working autonomously". The author leaves acceptance to the independent acceptor.
Process-fixture exclusions and the safety wrapper are recorded in the builder report.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationSelfTestPromptCacheTest.java"
      ],
      "class": "New",
      "behaviour": "The runtime-activation self-test candidate launches llama-server with --cache-ram 0 in normal and evaluation mode, so it no longer gets llama.cpp's 8192 MiB default host prompt cache. The check drives a real activation and observes the candidate argv where the controller session receives it; the session declines to launch it, so no process starts.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "the bound on llama-server's host prompt cache (task item 5, inference lane), which the owner asked about with \"whats best for development ?\" after the orchestrator recommended 1 GiB for chat, 0 for evaluation and scripted runs, VDU unchanged and a development override",
          "choice": "The activation self-test candidate runs with the host prompt cache disabled (--cache-ram 0) in every mode, because it answers one tiny request and never resumes a conversation; without the flag llama.cpp enables its 8192 MiB default.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b70-prompt-cache-followups.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationSelfTestPromptCacheTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/PromptCacheKeyRegistrationTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/PromptCacheEnvironmentVariableTest.java"
      ],
      "class": "New",
      "behaviour": "The development override is registered with the other inference keys: EnvRegistry declares justsearch.llm.prompt_cache_mib with JUSTSEARCH_LLM_PROMPT_CACHE_MIB, permanent, no registered default; the apply register classifies it restart-required; the resolved configuration tracks it from the system property. The launch honours the registered environment variable, the system property outranks it, evaluation mode still forces 0, and a malformed environment value is refused naming the variable. The environment cases run a real child JVM per case.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z",
          "delegationScope": "the bound on llama-server's host prompt cache (task item 5, inference lane), which the owner asked about with \"whats best for development ?\" after the orchestrator recommended 1 GiB for chat, 0 for evaluation and scripted runs, VDU unchanged and a development override",
          "choice": "One explicit override for development, registered where the other inference configuration keys are registered and listed, validated as a non-negative integer number of MiB; an invalid value is refused with a clear message. Registration brings the registry's environment variable, which the launch honours with the system property taking precedence.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b70-prompt-cache-followups.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/PromptCacheKeyRegistrationTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/PromptCacheEnvironmentVariableTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/resolved/ConfigApplyScopesTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "One register count. packagedRegisterIsTheRepositoryRegister counts 285 register entries instead of 284: the one added entry is justsearch.llm.prompt_cache_mib (restart-required); the byte comparison with the repository register and the index.boosts scope assertion are unchanged."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-prompt-cache2-20261012",
  "verdict": "accept",
  "digest": "sha256:529be7684fe513ad4aea98401d4be1244af2bb52457d3ffa64df20a8feb2599d",
  "note": "Independent verifier on 65f1c2e8e: new checks fail on 93bacf383 by assertion and pass on the candidate; own cases agree. Live: activation self-test runs with --cache-ram 0 (log: prompt cache is disabled) in default, eval and override runs; env 384 under CLI 256 gives 256; env abc refused naming JUSTSEARCH_LLM_PROMPT_CACHE_MIB with no chat child; key registered restart-required and documented. Sources quote the owner's delegation. Nit: delegationScope puts the owner's question after the recommendation; missing --prompt-cache-mib value still prints the generic UNHANDLED parser error (pre-existing for every option)."
}
```
