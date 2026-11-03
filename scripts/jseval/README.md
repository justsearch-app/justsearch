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

MIRACL fr runs only with `quality full-suite --window-seconds SECONDS`, which
requires an explicit window and includes all four collections. Relative recipe
paths (including Enron) resolve from the current checkout first, then the main
checkout for shared materialized datasets.

`check` sends the Search window's ordinary refined request: search syntax,
50 results, excerpts and the same four facets, with mode and pipeline left to
the engine. `ablate` labels explicit text/vector/hybrid runs as sensitivity
information. Runs require a clean candidate and a distribution built for it;
model files and index population are verified. HTTP failures and reranker
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
