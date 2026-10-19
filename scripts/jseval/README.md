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
