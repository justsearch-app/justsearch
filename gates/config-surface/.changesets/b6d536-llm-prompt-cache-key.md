---
classification: declared-growth
adr: 0050
task: t-20261005-b6d536
---

Declares ONE configuration key: `justsearch.llm.prompt_cache_mib` /
`JUSTSEARCH_LLM_PROMPT_CACHE_MIB` (`EnvRegistry.LLM_PROMPT_CACHE_MIB`), no registered default,
apply scope `restart-required`. Net growth is exactly this key: `env_sysprop_pairs` 243 → 244 and
`apply_scope` 284 → 285; `yaml_keys` and `config_keys` are unchanged.

**Why it is a key.** The managed llama-server's host prompt cache is bounded (task
t-20261005-b6d536 item 5): chat 1024 MiB, evaluation mode and VDU 0. The one development override
of the chat bound already existed as a bare system property read in `InferenceConfig`. This change
registers it with the other inference launch keys, so it is declared, resolved, listed and
apply-classified like them rather than a one-off read.

**It is a bound, not a mode switch.** The value is a decimal integer number of MiB, 0 to
2147483647. A blank, signed, fractional, malformed or overflowing value is refused with the source
named, never replaced by the default. Evaluation mode and VDU still use 0 when it is set.

**No registered default.** The default depends on the mode (1024 for chat, 0 in evaluation mode),
so `InferenceConfig` owns it; a registered `1024` would report a value the evaluation launch does
not use.

**Restart required.** The bound is captured when the inference configuration is created; mode
changes, profile changes, crash recovery and VDU exit keep it. Only process sources (JVM argument,
environment variable) carry it.

**Reader.** `InferenceConfig` reads it presence-sensitively through `EnvRegistry.LLM_PROMPT_CACHE_MIB`
(system property, then environment variable), the same precedence `ResolvedConfigBuilder` reports.
The dev runner's `--prompt-cache-mib` writes the system property.

## Baseline advance (same change)

`gates/config-surface/baseline.txt` moves with the key it accounts for: `env_sysprop_pairs` 243 →
**244** and `apply_scope` 284 → **285**, each +1 = exactly the key above; `yaml_keys` 111 and
`config_keys` 53 unchanged. Measured with `node scripts/docs/generate-runtime-config-matrix.mjs` on
this branch (`yaml_keys=111 env_sysprop_pairs=244 config_keys=53 apply_scope=285 rows=297`).
