---
classification: declared-growth
tempdoc: 958
owner: inference-runtime
reason: One machine-wide bounded optimized-model store replaces unbounded model-directory caches.
---

Registers exactly two permanent keys: `justsearch.ort.optimized_cache_dir` /
`JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR` and `justsearch.ort.optimized_cache_max_mb` /
`JUSTSEARCH_ORT_OPTIMIZED_CACHE_MAX_MB`. The directory defaults to
`PlatformPaths.getPlatformDefault()/cache/ort-optimized`, independently of data-dir selection.
The cap defaults to 16384 MiB; zero disables disk caching. Both require restart.

The location override isolates CI/tests and lets operators choose the disk holding large
regenerable graphs. The cap prevents the recurring unbounded disk growth recorded in design 958
section 2. Neither setting can be represented by per-model metadata or the per-run data directory.

EnvRegistry -> ResolvedConfigBuilder (including `ort.*` YAML contributions) ->
ResolvedConfig.Ai.OptimizedCache -> ConfigStore -> OrtOptimizedModelStore follows the existing
ORT native-library config route. Graph serialization and optimization setters stay in
SessionOptionsApplier. OrtOptimizedModelStoreTest pins the override and cap.

The baseline accounts for exactly two new YAML contributions, two env/sysprop pairs and two
apply-scope registrations: 109 -> 111, 237 -> 239, 278 -> 280. ConfigKey count stays 53.
These are source-count deltas; regenerate the runtime-config matrix and verify the pins after
the read/edit-only measurement window ends.
