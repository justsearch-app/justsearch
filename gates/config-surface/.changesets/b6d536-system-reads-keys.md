---
classification: declared-growth
adr: 0051
task: t-20261005-b6d536
---

Declares TWO configuration keys that the integration of the b6d536 slices had been reading
directly (`System.getenv` / `Boolean.getBoolean`), outside the configuration funnel. Net growth is
exactly these two keys: `env_sysprop_pairs` 241 → 243 and `apply_scope` 282 → 284; `yaml_keys` and
`config_keys` are unchanged.

1. **`justsearch.engine.incarnation` / `JUSTSEARCH_ENGINE_INCARNATION`**
   (`EnvRegistry.ENGINE_INCARNATION`), default unset, apply scope `restart-required`. ADR-0051:
   "The supervisor records Engine incarnations the same way." Spec F section 3.2: a supervisor (the dev runner's engine actuator, the shell) sets the variable at
   each Engine spawn as `<session>/<n>`, and the Engine copies it into its runtime manifest from the
   first write on. Before, `RuntimeManifestPublisher` read the variable with `System.getenv`. The
   variable name is unchanged, so the supervisors need no change. It describes the process itself,
   so it cannot change while the process runs: `restart-required`.
2. **`justsearch.eval.mode` / `JUSTSEARCH_EVAL_MODE`** (`EnvRegistry.EVAL_MODE`), default false,
   apply scope `restart-required`. The dev and evaluation runs pass `-Djustsearch.eval.mode=true`;
   the synthetic debug routes answer only under it (otherwise 404). The new Spec A step 9 route
   `POST /api/debug/chat-pressure` (`ChatResidencyController#handleDevPressure`) read it with
   `Boolean.getBoolean`; it now reads `EnvRegistry.EVAL_MODE.getBoolean(false)`. The older readers
   (`BootstrapHelpers`, `DebugConditionController`) are on the sysaccess allowlist and are left as
   they are. The registry also accepts the environment variable, which nothing sets today.

**Not a key: PATH.** The two llama-server launch paths (`LlamaServerOps#runtimeDllPath`,
`ChildSelfTestLauncher#pathOverride`) read the process `PATH` to put the DLL directories in front of
it. Registering `PATH` would print the machine's `PATH` in the effective-config output, so it is not
registered. Instead the direct read is removed: `ChildSpec` carries a `pathPrefix`, and
`DefaultChildControl` puts it in front of the `PATH` the child inherits from its `ProcessBuilder`
environment (reusing that variable's spelling, `Path` or `PATH`). No process-environment read
remains at either launch site.

## Baseline advance (same change)

`gates/config-surface/baseline.txt` moves with the keys it accounts for: `env_sysprop_pairs` 241 →
**243** and `apply_scope` 282 → **284**, each +2 = exactly the two keys above; `yaml_keys` 111 and
`config_keys` 53 unchanged. Measured with `node scripts/docs/generate-runtime-config-matrix.mjs` on
this branch (`yaml_keys=111 env_sysprop_pairs=243 config_keys=53 apply_scope=284 rows=296`).
