# jseval

Evaluation and profiling toolkit for JustSearch. Install/usage: `pyproject.toml`,
`requirements.lock.txt`; CLI entry point `python -m jseval` (`jseval/cli.py`).
## Data policy

`scripts/jseval` is a *toolkit* directory, not a data store. Three rules decide
whether bytes belong here (tempdoc 930 chunk C; enforced mechanically by the
`scripts/jseval/` block in the repo `.gitignore`).

1. **Campaign run outputs are never committed.** Logs, per-cell records, judge
   overlays, calibrations and verdict JSONs from a measurement campaign go to the
   gitignored `scripts/jseval/runs/<campaign>/`, or to the `datasets/` cache.
   `scripts/jseval/runs/`, `scripts/jseval/*-run-*/` and `scripts/jseval/*-pilot-*/`
   are gitignored so this is not a matter of remembering. Quote the numbers in the
   owning tempdoc or in `docs/reference/search-quality-register.md`; do not commit
   the tree that produced them.

   Three pre-930 evidence sets are explicitly re-admitted by a `!` negation and are
   the only exceptions: `624-run-2026-07-03/`, `624-run-2026-07-18-confirmatory/`
   and `782-run-2026-07-28-hero/`. Each is named as the evidence backing a published
   claim — the accepted, sha256-chained agent-utility publication
   (`public-agent-utility/current.v1.json`) and canonical register entries — so
   deleting them would falsify a published claim rather than merely drop history.
   Adding a fourth exception means adding a published claim first.

2. **Generated corpora commit their recipe, not their content.** A procedurally
   fabricated corpus commits `meta.json` (whose `generation_provenance` is the full
   `jseval.corpus_generate.generate()` parameter set) and is rebuilt on demand.
   Fetched corpora already work this way — `jseval/corpus_fetch.py` commits a recipe
   and materializes through the shared `jseval/dataset_cache.py` (tempdoc 709).

   The recipe is only a substitute for the bytes while it *reproduces* them.
   `jseval.corpus_generate.regenerate_and_diff` is the check, and it must pass before
   the content is dropped: the pre-tempdoc-767 `624-corpora/battlefield-*` and
   `635-corpora/battlefield-en-scale-v1` corpora fail it (their provenance predates
   the required `entity_bank` parameter, and the syllable-pair minter that produced
   them was deleted), so their `docs.jsonl` stays committed as the only surviving
   copy.

3. **Test fixtures live under a named `*-corpora/` (or `tests/fixtures/`) directory
   and must be cited by a test or a frozen pre-registration.** A fixture nobody reads
   is a run output by another name. Cite it by path from the test or the campaign
   identity file, so the citation scan that admits it is the same scan that would
   catch it going stale.

   A byte-identical duplicate across two `*-corpora/` roots is not automatically
   waste: `781-corpora/` deliberately mirrors the unchanged enron bytes from
   `707-corpora/` because it is the v2 cohort's own identity, pinned by path in
   `782-hero/cells.v1.json` and enumerated as one 8-cell root by
   `experiments/paraphrase_bridge_suite.py`. Re-point a duplicate only when no
   commitment, certification or pre-registration names its path.

## Search-quality quick check

Run from the checkout whose engine is being measured, with jseval installed:

```sh
python -m jseval quality prepare scifact
python -m jseval quality baseline scifact
python -m jseval quality check scifact
python -m jseval quality ablate scifact --mode vector
```

Omit the collection arguments to baseline/check to use SciFact, MIRACL de
and Enron. Each needs its prepared index; run `quality prepare ALIAS`
for each beforehand. Stored collection directories with `quality.config.json`
also work, with `--documents-dir` for local source texts. Preparation builds
through pausable background admission and may wait for the Gradle slot. Scoring
never builds or repairs an index and refuses missing, corrupt or stale inputs
as **not ready**. Re-run preparation outside the exclusive window.

An index is owned by the collection's document identity: population (count,
manifest and content digests), layout and witness formats. Editing queries or
judgments therefore reuses the prepared index; changing a document, the layout
or the witness formats builds a new one. `prepare` also accepts a corpus-only
collection without scored queries.

The index is keyed by what can change its contents, each by content, never by
where it lives:

- the **distribution snapshot**: inside the same Gradle slot hold as
  `installDist` (the worktree installation lock), preparation copies the
  install folder, the runtime resources the engine reads through its repo root
  (`SSOT/`, the headless `application.yaml`) and the eval launch scripts into a
  read-only snapshot under `~/.cache/justsearch-quality/dists/`. Unchanged
  files share one copy. A snapshot is published only when `lib/` holds exactly
  the jars the installed start script's `CLASSPATH` names and the install
  folder, walked again, holds exactly the snapshot's files; a folder that
  cannot be listed refuses. The engine and its extraction children run from
  the snapshot, never from the build folder. Its digest covers every file;
- the preparation harness modules as loaded (materialization, ingestion,
  launch, witness and identity code; not scoring, comparison, reporting, query
  or label code);
- the JDK (resolved once and passed as `JAVA_HOME`), JVM options, the ORT/CUDA
  native folders and `LD_LIBRARY_PATH`, the tesseract executable, its libraries
  and language data (resolved once and passed explicitly);
- the whole models root (`--models-dir`): every package model discovery can
  select, BGE-M3 and chat models included. Only bookkeeping whose readers cannot
  change index contents is left out: ORT's legacy optimized siblings (only ever
  deleted), chat identity receipts (read only to identify the chat model, which
  quality runs disable) and writer temporaries. A `*.sha256` digest sidecar the
  engine would trust (matching modification time and size) must equal its
  model's digest, because index fingerprints come from it;
- every other `JUSTSEARCH_*` setting by its literal value, except the listed
  resource locators (`JUSTSEARCH_FIELD_CATALOG`, `JUSTSEARCH_MCP_HOST_CONFIG`,
  `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH`, `JUSTSEARCH_NATIVE_PATH`,
  `JUSTSEARCH_SERVER_EXE`, `JUSTSEARCH_LLM_MODEL_PATH`), which key by the
  content they name.

Quality engines never use ORT's optimized-graph cache
(`JUSTSEARCH_ORT_OPTIMIZED_CACHE_MAX_MB=0`, its folder inside the entry's data
folder): a cached graph is loaded without verifying its bytes. The engine
launch and the JDK resolver drop inherited Node variables that load code or
code-shaping data (`NODE_OPTIONS`, `NODE_PATH`, `NODE_PRESERVE_SYMLINKS`,
`NODE_PRESERVE_SYMLINKS_MAIN`, `NODE_COMPILE_CACHE`, `NODE_REPL_EXTERNAL_MODULE`,
`NODE_ICU_DATA`).

So a docs, web-UI, CI or jseval scoring/report/query/label edit, a rebase over
such changes or a second worktree at the same tree adopts the existing index.
Preparation then runs one incremental `installDist`; when the snapshot digest
is unchanged, the index is reused. A Java edit, a dependency, `SSOT/` or
`application.yaml` change, a model file, the JDK, OCR runtime, native folders,
a runtime setting or the preparation harness builds a new index. An input that
cannot be resolved, hashed or validated is refused with its reason: automatic
GPU discovery (set `JUSTSEARCH_GPU_ENABLED=false`, or `true` with
`JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH`), JVM argument files, VM option files,
agents, `LD_PRELOAD`, an extraction-command override or any option value not on
the allow-list, a per-package model location (`JUSTSEARCH_*_MODEL_PATH`; point
`--models-dir` at a models root instead), a `models/` folder in the data folder,
a missing or unreadable model, snapshot or OCR file. Such a
refusal never quarantines a cached index. Snapshots that are not newest for a
checkout, not used by a cache entry and not running are pruned. Indexes keyed
before this identity (`jseval.quality.index.v1`) are never adopted; an
explicitly chosen data folder from then is refused by name.
`baseline`, `check` and `score` refuse such a collection. A config's
`pending_kinds` names query kinds stored without judgments until a later
labelling pass. Those queries are counted as pending and excluded from scoring.

MIRACL fr runs only with `quality full-suite --window-seconds SECONDS`, which
requires an explicit window and includes all four collections. Relative recipe
paths (including Enron) resolve from the current checkout first, then the main
checkout for shared materialized datasets.

`check` sends the Search window's ordinary refined request: search syntax,
50 results, excerpts and the same four facets, with mode and pipeline left to
the engine. `ablate` labels explicit text/vector/hybrid runs as sensitivity
information. Runs require a clean candidate and the snapshot installed from
exactly its sources: a checkout edited since its last `prepare` (committed or
not) is refused as a stale distribution before any engine starts, and so is a
snapshot whose bytes no longer match its digest. Model files and index
population are verified. Run headers record one digest per identity component
(`identity`) and the runtime settings without machine paths. HTTP failures and reranker
loading, contention, deadline or inference drops invalidate the run. Identical
by-design skips in both arms remain comparable, with their reasons recorded.

Scoring estimates its cost before reserving the machine through
`scripts/dev/admission.py exclusive`. The default window is 600 seconds,
including backend startup, scoring and cleanup. `--window-seconds` may shorten
it; there is no override for work that does not fit. Gate waiting and draining
stop after 1200 and 600 seconds respectively. The admission guardian owns and
reaps all children, including hung inference. The next collection starts warming
while the current one is queried.

Each command prints one outcome line. Exit codes: PASS 0, REGRESSION 1,
INCONCLUSIVE/INVALID 2. Reports name the measured surface, request, candidate,
models and per-collection elapsed times. JSONL runs, summaries and comparison
reports default to the gitignored `scripts/jseval/runs/quality/`; baselines live
in its `baselines/` directory. Use `--runs-dir`, `--baseline-dir` and
`--cache-root` to choose separate resources. Set `JS_ADMISSION_DIR` when running
isolated tests; ordinary checks use the shared machine reservation.
