---
classification: declared-growth
adr: 0050
task: t-20261005-b6d536
---

Reconcile the integrated branch's pins with its existing configuration declarations.
This repair adds no configuration key or apply entry. The prior slice records used
overlapping baseline counts; the merged source measures 248 environment/system
property pairs and 289 apply entries, while the pins still said 244 and 285.

Relative to main 298354254 (239 pairs, 280 apply entries), the nine declarations are:

| system property | purpose | apply scope |
| :--- | :--- | :--- |
| `justsearch.inference.external_endpoint` | Explicit external chat server; never adopted or stopped | restart-required |
| `justsearch.gpu.game_detection` | Pause bulk GPU work while a game runs | hot |
| `justsearch.engine.incarnation` | Supervisor-supplied process incarnation | restart-required |
| `justsearch.eval.mode` | Explicit evaluation/debug route gate | restart-required |
| `justsearch.mmproj.offload` | Vision projector placement under the device-memory plan | component:generative |
| `justsearch.gpu.memory_plan` | Engine-derived planned profile | component:encoders |
| `justsearch.gpu.memory_plan_unplanned_roles` | Engine-derived encoder roles outside that plan | component:encoders |
| `justsearch.gpu.device_available` | Engine-derived captured device availability | restart-required |
| `justsearch.llm.prompt_cache_mib` | Development override of the managed chat prompt-cache bound | restart-required |

The external-endpoint, game-detection, system-reads, E7b-plan and prompt-cache
changesets retain their detailed reasons. The two additional E7b-derived fields
are already declared in EnvRegistry and read by the memory-plan/execution policy;
they report the Engine's captured plan rather than adding a user preference.

`generate-runtime-config-matrix.mjs` measures yaml_keys=111,
env_sysprop_pairs=248, config_keys=53 and apply_scope=289. Only the two stale
pins advance, to these measured values. The config-surface gate fails before this
reconciliation with declared-growth-without-repin for both metrics and passes
after it. The existing declared-growth mechanism licenses the advance; its
next-change growth ratchet remains in force.
