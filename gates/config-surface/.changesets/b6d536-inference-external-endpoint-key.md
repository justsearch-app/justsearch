---
classification: declared-growth
adr: 0051
task: t-20261005-b6d536
---

Declares ONE configuration key: `justsearch.inference.external_endpoint` /
`JUSTSEARCH_INFERENCE_EXTERNAL_ENDPOINT` (`EnvRegistry.INFERENCE_EXTERNAL_ENDPOINT`), default unset,
apply scope `restart-required`. Net growth is exactly this key: `env_sysprop_pairs` 239 → 240 and
`apply_scope` 280 → 281; `yaml_keys` and `config_keys` are unchanged.

**Why it is a key.** ADR-0051 decision 2 ends adoption: JustSearch no longer uses a llama-server it
did not start in this Engine, because a crashed Engine's unregistered server answering on the
configured port was indistinguishable from the user's own. The decision keeps the user's own server
possible only "when explicitly configured" (reconciliation ruling CH4), and this key is that explicit
configuration. Without it the configured port (`justsearch.server.port`) is no longer a BYO path: a
server found there is ignored with one WARN and the status note `external_server_ignored`, and the
managed server runs on a fresh loopback port.

**It is a selection, not a tunable.** The value is a loopback `http://127.0.0.1:<port>` (or
`localhost`) URL. Any other host, scheme, a missing port or a path is refused with a reason at start
(`ExternalInferenceEndpoint.parse`), never reinterpreted; a named server is reported and never
stopped.

**Restart required.** It is read when the generative backend starts; a running Engine does not
re-point an active backend at a different server, so `restart-required` is the honest scope.

**Reader.** `ResolvedConfigBuilder` resolves it onto `ResolvedConfig.Ai.inferenceExternalEndpoint`,
read by `LlamaServerOps` on every start.

## Baseline advance (same change)

`gates/config-surface/baseline.txt` moves with the key it accounts for: `env_sysprop_pairs` 239 →
**240** and `apply_scope` 280 → **281**, each +1 = exactly the key above; `yaml_keys` 111 and
`config_keys` 53 unchanged. Measured with `node scripts/docs/generate-runtime-config-matrix.mjs` on
this branch (`yaml_keys=111 env_sysprop_pairs=240 config_keys=53 apply_scope=281 rows=293`).
