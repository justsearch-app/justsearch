---
classification: declared-growth
tempdoc: 936
---

Declares ONE configuration key: `index.vector.exhaustive_search` /
`JUSTSEARCH_INDEX_VECTOR_EXHAUSTIVE_SEARCH`, boolean, default `false`. It contributes one YAML key
and one env/sysprop pair; `config_keys` is unchanged.

**Why it is a key and not a constant.** Lane F's design section 0 records that two fresh index
builds of the same 91 documents returned different evidence at the top-10 margin. Part A of this PR
fixes the larger source (the chunk legs sorted only on Lucene's internal docId). The remainder is
the dense leg: HNSW is approximate, and Lucene 10.4's `AbstractKnnVectorQuery.getLeafResults` has
**no** exact path for an unfiltered query at any `k` — so `index.vector.ef_search`, which is only an
oversample floor on `k`, cannot pin it. The one exact branch Lucene exposes is `exactSearch`, taken
when a filter is present and its cost is within the per-leaf top-k. This key is the switch that
puts the query into that shape: `ReadPathOps#buildKnnQuery` raises `k` to at least
`reader.maxDoc()` and substitutes a `MatchAllDocsQuery` when the caller supplied no filter. Without
a key, the paired stage-E captures cannot hold the dense leg still, and 16's byte-equal-fields list
is measuring noise.

**The default reproduces today's behaviour exactly.** With the switch off, `buildKnnQuery` returns
`new KnnFloatVectorQuery(field, vector, resolveVectorQueryK(limit), filter)` — the identical
construction the three inline call sites it replaces performed, including for a null filter (Lucene's
3-arg constructor delegates to the 4-arg one with `null`). No searcher is acquired to read `maxDoc`
in that mode. `VectorSearchIntegrationTest.exhaustiveSearchMakesTheDenseLegExactAndReportsEveryVectorBearingDoc`
pins both arms on one corpus: switch on, `totalHits` is every vector-bearing document and the
returned ranking equals a brute-force EUCLIDEAN scan the test computes itself; switch off, the same
call's `totalHits` is bounded by the internal query `k`.

**It changes the approximation, not which branches the pipeline takes.** In exhaustive mode the
dense leg's `totalHits` is the whole vector-bearing corpus by construction, so counting it as
candidate-budget saturation would fire the chunk-branch retry on essentially every query.
`SearchExecutor#isCandidateBudgetSaturated` therefore drops the `totalHits` term for the dense leg
(and only the dense leg, and only in this mode); the returned-hit-count term is untouched.
`SearchExecutorChunkBranchLeversTest.CandidateBudgetSaturation` pins all five cases.

**It resolves onto `ResolvedConfig.Index` and reaches the Worker through the ordinal-450 config
snapshot**, not a raw `EnvRegistry` read inside the Worker JVM — the same channel
`index.commit.timer_interval_ms` uses, and for the same reason (885 [R1]).
`ConfigWiringTest.vectorExhaustiveSearchReachesTheRuntimeSessionWhenSet` asserts it at the set-site
(`RuntimeSession.vectorExhaustiveSearch`), which is what the kNN factory reads, rather than only at
the config record.

**The enum entry is appended at the END of `EnvRegistry`**, per the cross-lane append rule.

## Baseline advance (same commit, tempdoc 883 rule)

`gates/config-surface/baseline.txt` moves in this commit, alongside the key it accounts for:

| metric | was | now | delta |
| :--- | ---: | ---: | :--- |
| `yaml_keys` | 111 | **112** | +1 = exactly the key above |
| `env_sysprop_pairs` | 251 | **252** | +1 = exactly the key above |
| `config_keys` | 56 | 56 | unchanged |

Measured with `node scripts/docs/generate-runtime-config-matrix.mjs` on this branch
(`yaml_keys=112 env_sysprop_pairs=252 config_keys=56 rows=308`). The pre-change pin of 111/251/56
is what this branch's base measures, so the delta is fully attributable and the ratchet keeps its
meaning — it still only ratchets DOWN from here.
