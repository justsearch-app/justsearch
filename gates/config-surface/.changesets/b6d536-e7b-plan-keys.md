---
classification: declared-growth
adr: 0050
task: t-20261005-b6d536
---

Declares TWO configuration keys for Spec E step E7b (the device-memory plan applied):

| key | env | default | apply scope |
| :--- | :--- | :--- | :--- |
| `justsearch.mmproj.offload` | `JUSTSEARCH_MMPROJ_OFFLOAD` | `true` | `component:generative` |
| `justsearch.gpu.memory_plan` | `JUSTSEARCH_GPU_MEMORY_PLAN` | blank | `component:encoders` |

**`mmproj.offload`** is where llama-server puts the vision projector. The default `true` is
today's launch line, with no flag. On a 12 GB or 8 GB device the Engine contributes `false` at the
auto-detected ordinal (owner decision: 12 GB cards run the 9B at 32k with the projector on the
CPU), and the launch line gains `--no-mmproj-offload` right after `--mmproj`. An explicit value
outranks the plan and changes the chat footprint the plan reserves.

**`gpu.memory_plan`** is derived, not a preference. It carries the token of the profile the plan
applies (`p16_plus`, `p12` or `p8`) and is blank when no plan applies. Only the Engine's own
contribution at ordinal 150 is read; an explicit value is ignored with a WARN. Encoder sessions and
the chat window ladder use it to tell a planned profile from today's constants. Because of that,
a 10 GB card without the compact model keeps today's caps and window exactly (owner decision).

Both enum entries are appended at the END of `EnvRegistry`, per the cross-lane append rule.

## Baseline advance (same change)

| metric | was | now | delta |
| :--- | ---: | ---: | :--- |
| `env_sysprop_pairs` | 241 | **243** | +2 = exactly the keys above |
| `apply_scope` | 282 | **284** | +2 = exactly the keys above |
| `yaml_keys` | 111 | 111 | unchanged |
| `config_keys` | 53 | 53 | unchanged |

Measured with `node scripts/docs/generate-runtime-config-matrix.mjs` on this branch
(`yaml_keys=111 env_sysprop_pairs=243 config_keys=53 apply_scope=284 rows=296`).
`ConfigApplyScopesTest` pins the register size at 284.
