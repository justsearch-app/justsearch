---
classification: declared-growth
tempdoc: 936
---

C1 adds one startup-only operator setting, ENGINE_ADMISSION_AGGREGATE_LIMIT:
justsearch.engine.admission.aggregate_limit / JUSTSEARCH_ENGINE_ADMISSION_AGGREGATE_LIMIT.
It can reduce the packaged aggregate admission cap for constrained hosts and live saturation
proof; it cannot exceed the packaged policy. The packaged policy remains the default and the
single limit authority. EngineResourcePolicy validates the override before composition.

The env_sysprop_pairs pin moves from247 to248 with this declaration; yaml_keys110 and
config_keys56 are unchanged. The earlier stage-A shrink to247 remains valid before this
C1 key. Hosted34339800148 caught that the key added by e71b512a6 lacked its own pin advance.
The separate ORT-key declaration does not explain this new surface; this changeset does.
