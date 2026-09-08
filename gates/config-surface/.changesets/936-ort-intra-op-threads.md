---
classification: declared-growth
tempdoc: 936
---

Declares ONE configuration key: `justsearch.onnxruntime.intra_op_threads` /
`JUSTSEARCH_ORT_INTRA_OP_THREADS`, no default (unset). It contributes one env/sysprop pair;
`yaml_keys` and `config_keys` are unchanged.

**Why it is a key and not a constant.** `SessionOptionsApplier.applyBase` sets
`interOpNumThreads` on every ONNX Runtime session but has never set `intraOpNumThreads` — only the
three probe paths in `OrtSessionAssembler` (`:143`, `:178`, `:208`) do. Production sessions
therefore inherit ORT's own choice, which it derives from hardware concurrency. On the **CPU
execution provider** that count decides how a GEMM partitions its reduction, so it decides the
order the same floats are summed in, so it decides the low bits of an embedding. Within one
machine the count is stable and nothing moves; across two machines, or one whose available
parallelism differs between runs, it is a silent source of vector drift.

That matters now because lane F's deterministic capture moves the encoders to CPU (pair run 5's
residual was index-time embedding jitter on CUDA, which is upstream of every candidate budget and
so removable by no budget pin). Bit-stability on CPU is conditional on a fixed thread count, so the
capture has to be able to fix it. Every other pin the capture needs already existed as a key; this
one did not.

**Unset is today's behaviour, exactly.** `RuntimePolicy.Session.intraOpThreads` is a nullable
`Integer` and `applyBase` only calls `setIntraOpNumThreads` when it is non-null, so a deployment
that does not set the key produces the identical session options it produced before. A default of
any number would have silently re-partitioned every GEMM on every existing install — a behaviour
change disguised as a default, which is why there is none.
`RuntimePolicyResolverTest.intraOpThreadsUnsetByDefault` pins the null; a non-positive value
resolves back to null rather than reaching ORT (which rejects it), pinned by
`intraOpThreadsNonPositiveIsIgnored`.

**It rides the existing ORT session-knob channel.** The value resolves onto
`ResolvedConfig.Ai.Profiling` — the sub-record that already carries `ortProfilingDir` and
`verboseLogging` — and reaches `SessionOptionsApplier` through `RuntimePolicyResolver`, not a
`System.getenv` read in the apply path. That is the closure property tempdoc 397 §14.24 FB
established for exactly these knobs, and this key does not weaken it.

**The golden fixture moved with it.** `PolicySnapshot`'s committed JSON
(`modules/ort-common/src/test/resources/policy-snapshot.json`) gains `"intraOpThreads": null` in
its `session` block. The snapshot test caught the shape change and was regenerated deliberately
rather than relaxed.

**The enum entry is appended at the END of `EnvRegistry`**, per the cross-lane append rule.

## Baseline advance (same commit, tempdoc 883 rule)

`gates/config-surface/baseline.txt` moves in this commit, alongside the key it accounts for:

| metric | was | now | delta |
| :--- | ---: | ---: | :--- |
| `env_sysprop_pairs` | 252 | **253** | +1 = exactly the key above |
| `yaml_keys` | 112 | 112 | unchanged — the key has no YAML contribution |
| `config_keys` | 56 | 56 | unchanged |

Measured with `node scripts/docs/generate-runtime-config-matrix.mjs` on this branch
(`yaml_keys=112 env_sysprop_pairs=253 config_keys=56 rows=309`). The ratchet still only ratchets
DOWN from here.
