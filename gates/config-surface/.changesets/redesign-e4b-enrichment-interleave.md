---
classification: declared-growth
tempdoc: 710
task: redesign-e4b
reason: Canonical enrichment interleave names for the one fair enrichment scheduler, with the SPLADE names kept as aliases.
---

E4b gives the busy-path enrichment settings generic names, because one scheduler now serves every
lane (dense parent, dense chunk, sparse parent, sparse chunk, NER), not only SPLADE:
`justsearch.backfill.enrichment_interleave_interval_ms` / `JUSTSEARCH_BACKFILL_ENRICHMENT_INTERLEAVE_INTERVAL_MS`
and `justsearch.backfill.enrichment_interleave_batch_size` / `JUSTSEARCH_BACKFILL_ENRICHMENT_INTERLEAVE_BATCH_SIZE`.
Both are permanent and `component:index`, like the legacy `splade_interleave_*` names, which stay
as aliases with their values. Both names join one source-ordinal chain before parsing, and the
canonical name wins at equal priority. The batch setting stays a document count cap. Tempdoc 710
(Wave-1.5 Move 4) owns the `justsearch.backfill.*` pacing surface these extend. No saved file
format changes. Net growth is exactly these two keys: `env_sysprop_pairs` 249 → 251 and
`apply_scope` 290 → 292 (baseline repinned in this change). `yaml_keys` and `config_keys` are
unchanged.

Owner words (task redesign-e4b request): "Settings: canonical
justsearch.backfill.enrichment_interleave_interval_ms (and env name); old SPLADE names stay as
aliases with their values; the batch setting keeps its count-cap meaning and gets a canonical
enrichment name with alias; precedence by existing source ordinals, canonical wins at equal
priority."
