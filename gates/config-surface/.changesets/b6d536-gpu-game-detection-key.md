---
classification: declared-growth
adr: 0050
task: t-20261005-b6d536
---

Declares ONE configuration key: `justsearch.gpu.game_detection` / `JUSTSEARCH_GPU_GAME_DETECTION`
(`EnvRegistry.GPU_GAME_DETECTION`), default `auto`, apply scope `hot`. Net growth is exactly this
key: `env_sysprop_pairs` 240 → 241 and `apply_scope` 281 → 282; `yaml_keys` and `config_keys` are
unchanged.

**Why it is a key.** ADR-0050 decision 3 has bulk GPU work yield to a foreground game, and Spec E
section 4.3 (task t-20261005-b6d536, step E4) names this key: game detection
pauses bulk GPU work and tells the chat server while a game runs, and a false positive costs the
user indexing time and, through Spec A, an idle chat model. The user who knows better turns it off
with `justsearch.gpu.game_detection = off`.

**It is a switch, not a tunable.** The value is `auto` or `off`, case-insensitive. Any other value
resolves to `auto` with a WARN: turning detection off must be said plainly.

**Hot.** The Engine's GPU residency sampler reads it from the current configuration on every 2 s
tick (`EngineRoot.gameDetectionEnabled`); `off` clears a detected game on the next tick and stops
reading the foreground, `auto` resumes detection on the next tick. No restart is needed.

**Reader.** `ResolvedConfigBuilder` resolves it onto `ResolvedConfig.Ai.gpuGameDetection`, read
by `EngineRoot.gameDetectionEnabled` for the sampler.

## Baseline advance (same change)

`gates/config-surface/baseline.txt` moves with the key it accounts for: `env_sysprop_pairs` 240 →
**241** and `apply_scope` 281 → **282**, each +1 = exactly the key above; `yaml_keys` 111 and
`config_keys` 53 unchanged. Measured with `node scripts/docs/generate-runtime-config-matrix.mjs` on
this branch (`yaml_keys=111 env_sysprop_pairs=241 config_keys=53 apply_scope=282 rows=294`).
