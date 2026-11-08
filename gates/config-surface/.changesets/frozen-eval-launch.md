---
classification: declared-growth
tempdoc: docs/reference/jseval-pipeline-reference.md#frozen-comparison-engine-lifetime
task: t-20261108-frozen-startup
---

One internal process launch key, `justsearch.eval.frozen` / `JUSTSEARCH_FROZEN_EVAL`, selects the
owned frozen-index comparison lifetime. The frozen handle alone supplies it to the eval launcher;
it requires eval mode before startup effects and never persists in user settings. It is separate
from prepared-only and ordinary eval, which still create or upgrade their writer. An actual frozen
lifetime captures the mode before index/store initialization and retains the restriction through
late health/recovery and mutation entrances.

The declared design requires a distinct nonpersisted frozen-eval launch intent because normal
migration and deferred phases also use read-side runtimes. Inferring frozen mode from the runtime
type or changing prepared-only would change normal product behavior. No extra user option is added.
Net growth: `env_sysprop_pairs` 251 → 252; `apply_scope` 292 → 293. `yaml_keys` and `config_keys`
remain unchanged. Both pins advance for this single declaration and its restart-required apply row.
