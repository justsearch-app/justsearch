---
title: 767 — Certification runbook (paid + GPU half)
status: EXECUTED 2026-07-22 — both members fully-certified (32/32 gates). See 767-camouflaged-injection-corpus-lane.md §R for results, threshold table, deviations, spend, and the harness-recovery notes.
created: 2026-07-21
companion-to: 767-camouflaged-injection-corpus-lane.md
---

# 767 — Certification runbook: the paid and GPU-backed gates

**Agent campaign freeze (2026-11-09):** Fresh and resumed live agent campaigns are frozen. Campaign commands and runbooks are retained for historical reproduction and offline analysis; published evidence and offline replay remain available. The campaign plans and launch/resume instructions below are historical and do not authorize campaign work. Working appendix to `767-camouflaged-injection-corpus-lane.md` §N.4. Covers the only remaining
certification work for `en-legal-clerc` and `en-email-enron-raw`: the paid `closed_book` gate and
the three backend-bound gates (`retrieval_calibration`, `union_recall`, `leak_floor`).

**Nothing in this document has been executed.** It was prepared offline at zero spend and zero GPU
time. Every command below was validated only to the extent noted in §8 — flags confirmed to exist
and parse, invocations never run.

All dollar and wall-clock figures in this document are **estimates**, derived and labelled as such
in §3. They are not measurements.

---

## 1. Blockers — clear these before authorizing any spend

Five findings would break the run before it produced anything. B1, B2 and B4 are hard stops.

### B1 — The scientific policy is pinned to the superseded n=20 build (HARD STOP)

`scripts/jseval/707-corpus-certification-policy.v1.json` still carries, for all 8 cells,
`"query_count": 20` and the `corpus_signature` / `query_gold_sha256` of the **pre-rebuild**
corpora. The rebuilt corpora are n=50 with entirely different signatures.

`_policy_threshold` (`scripts/jseval/jseval/corpus_certify.py:297-311`) raises on any mismatch of
those three fields. It is reached only when `--scientific-evidence` is supplied
(`corpus_certify.py:92`, `:149-156`) — which is exactly why structural-only certification is green
today and the certification step would die on its **first cell**.

Reproduced offline against the live code and the committed certification:

```
707 scientific policy identity mismatch for mixed/en-legal-clerc-1k-verbose
```

Confirmed additionally that all 8 policy cells still match the **old** n=20 datasets byte-for-byte
— so the policy is not merely stale in one field, it is pinned wholesale to the superseded build.

**Remedy:** re-pin each cell's `corpus_signature`, `query_gold_sha256` and `query_count` from
`707-corpora/<member>/structural-certification.v1.json`.

> **Scientific caution, not a mechanical edit.** The `thresholds` blocks are *pre-registration*
> commitments. Re-pinning **identity** (signature/hash/count) is bookkeeping. Re-deriving
> **thresholds** after seeing measured results is p-hacking. If the n=50 rebuild is believed to
> move the expected `ndcg_band` / `union_recall.minimum` (§N.3 notes gold is now 10.0% of a 1k cell
> and each question sits among 98 decoys rather than 38, a 2.58x increase — which plausibly *does*
> move retrieval difficulty), the thresholds must be re-registered **before** the measurement runs,
> with the reasoning recorded. Decide this deliberately; do not let it become a post-hoc edit.

### B2 — The n=50 datasets are not materialized anywhere on disk (HARD STOP)

`datasets/` does not exist in this worktree, and the only materialized copy of these 8 cells
anywhere under `F:\justsearch-public` is in `.claude/worktrees/step2-powered/datasets/mixed/` —
which is the **old n=20 build** (verified: all 8 cells `n=20`, signature mismatch against the
current certification, exact match against the stale policy).

`datasets/` is gitignored, so the rebuild's materialized output was not preserved. Every command in
§5 phases B–D reads `datasets/mixed/<name>/`.

**Remedy:** re-materialize all 8 cells before anything else — see Phase A. §N.3 records that
regeneration is under 10 minutes and free.

### B3 — `corpus-fidelity` is also a PAID command (cost-model correction)

The lane brief classes `retrieval_calibration` as GPU-only. It is **both**. `assess_fidelity`
calls `shortcut_leak_rate` (`scripts/jseval/jseval/corpus_fidelity.py:139`), which drives one
`claude -p` subprocess **per query carrying evidence** (`corpus_fidelity.py:66-84`) — 50 calls per
cell, 400 across the cohort, with a full evidence-document body in each prompt.

Practical consequence: the paid budget is roughly **double** a closed-book-only estimate, and part
of the spend happens *inside* the GPU window, where a stack takeover would waste it.

### B4 — The `--modes` set in Phase C1 silently under-measures union recall (HARD STOP)

The Phase C1 command as originally written passed `--modes lexical,vector,hybrid`. That set passes
the projection's structural check — `status: "ok"` needs only a final mode plus at least one leg
(`staged_recall_accounting.py:221-233`; the check consumed by `corpus_certify.py:575-589` asserts
`status == "ok"`, not a leg count) — so **nothing warns**. But the certification thresholds were
derived from a run whose leg set ALSO included **splade**.

The mechanism is a silent omission, not an error:

```python
LEG_MODES = ("vector", "lexical", "splade")   # staged_recall_accounting.py:76
legs = [m for m in LEG_MODES if m in present] # :240
```

A mode that was never run is simply absent from `present`, so it drops out of the leg union rather
than raising.

**The arithmetic proof** (this is what caught it):

- Original certified run for `en-legal-clerc-1k-verbose`
  (`707-pillar1-inband-utility-corpus.md:519`): hybrid nDCG@10 **0.5051**, vector **0.5952**,
  lexical **0.0193**, **union recall 0.75**, leak 0.0.
- Union recall 0.75 is arithmetically impossible from vector ∪ lexical alone: even if the two leg
  hit-sets were fully disjoint the union could not exceed 0.5952 + 0.0193 = **0.6145**. A third
  leg must have contributed.
- A pilot re-run with `--modes lexical,vector,hybrid` gave vector 0.48, lexical 0.0, union
  **0.48** — union exactly equals vector, i.e. exactly one contributing leg.

**Consequence, stated plainly:** with the three-mode set every cell would fail the `union_recall`
gate (floor 0.65 for the 1k-verbose cells, `707-corpus-certification-policy.v1.json:15`) **after**
the full paid + GPU spend was already committed — and the failure would present as a corpus
regression rather than as a command-line error, inviting a threshold re-derivation (the exact
p-hacking B1 warns against) in response to a measurement artefact.

**Remedy:** Phase C1 must run `--modes lexical,vector,splade,hybrid --embedding --splade`. The
`--embedding` requirement is independent and pre-existing: `707-pillar1-inband-utility-corpus.md:491-492`
records that calibration runs must use `--embedding` with `hybrid` as the headline mode, because a
`bm25_splade`-only read presents as a false FAIL. Phase C1 below is corrected accordingly.

### B5 — `datasets/` resolves differently per command (materialization trap)

The two commands that read materialized cells do not agree on where `datasets/` is:

| Command | Resolution | Code |
|---|---|---|
| `corpus-certify --datasets-dir datasets` | `click.Path` relative to the **current working directory** | `commands/corpus.py:35`, `:184-197` |
| `jseval run --dataset mixed/<name>` | `REPO_ROOT / "datasets"` — **cwd is ignored** | `corpora.py:306` |

So materializing into `scripts/jseval/datasets/` satisfies `corpus-certify` (run from
`scripts/jseval/`) and then fails `jseval run` with:

```
FileNotFoundError: corpus.jsonl not found at <repo-root>/datasets/mixed/<name>/corpus.jsonl
```

**Materialize into the repo-root `datasets/` directory** — i.e. `<worktree-root>/datasets/mixed/…`
— and pass `--datasets-dir` as an absolute path, or run `corpus-certify` from the worktree root so
the relative form resolves to the same place. Both `datasets/` and `datasets-*/` are gitignored
(`.gitignore:221-222`), so nothing here is preserved across a clean checkout; this is also why B2
exists.

### Non-blocking notes

- `707-corpora/**` is **actively being modified** by other agents (uncommitted changes across both
  members, including both `structural-certification.v1.json` files). The commitment manifests are
  inputs to the structural `immutable_commitment` check. Re-run structural certification and
  confirm `structurally-certified` immediately before Phase B; do not start on a moving base.
- `member.v1.json` `gold_source` is stale for both members (§N.4, already logged). It is not read
  by the certification path — cosmetic here, but do not use it to locate gold.
- The bare `jseval` entry point **fails** from this worktree (cross-checkout guard, CLAUDE.md
  pitfall). Every command below uses the `PYTHONPATH` form; this was validated.

---

## 2. Prerequisites

Run from the worktree root: `F:\justsearch-public\.claude\worktrees\767-injection-corpus`.

```bash
export PYTHONUTF8=1
export PYTHONPATH=scripts/jseval
JS="python -m jseval"
```

| # | Prerequisite | Check |
|---|---|---|
| P1 | B1 resolved — policy re-pinned, thresholds deliberately settled | §5 Phase A gate script |
| P2 | B2 resolved — all 8 cells materialized, signatures match | §5 Phase A gate script |
| P2b | B5 respected — cells live in the **repo-root** `datasets/mixed/`, not `scripts/jseval/datasets/` | `ls datasets/mixed/` from the worktree root |
| P3 | `707-corpora/**` quiesced; both members re-certify `structurally-certified` | Phase A step A3 |
| P4 | `claude` CLI on PATH and authenticated (closed-book + shortcut probe both shell out to it) | `claude --version` |
| P5 | Build green | `./gradlew.bat build -x test` |
| P6 | Dev stack free, or takeover authorized | §4 |
| P7 | Explicit founder authorization for the spend in §3 | — |

---

## 3. Cost and wall-clock estimates

> Every number in this section is an **estimate**. The per-call cost basis is derived from a repo
> precedent, not from a measured invoice for this cohort.

### 3.1 What is actually paid for

Two independent paid passes, both `haiku`, both at `--concurrency 8`:

| Pass | Command | Calls/cell | Cells | Total calls | Per-call cap | Code |
|---|---|---|---|---|---|---|
| Closed-book | `corpus-certify` | 50 | 8 | **400** | `--max-budget-usd 0.10` | `utility_calibrate.py:424-449` |
| Shortcut-leak probe | `corpus-fidelity` | 50 | 8 | **400** | `--max-budget-usd 0.05` | `corpus_fidelity.py:53-84` |

Both are one call per query, so both scale **linearly** with `n_chains`.

### 3.2 The 20 -> 50 scaling, stated explicitly

The rebuild moved every cell from 20 to 50 queries (§N.1). Both paid passes are per-query, so:

**every prior closed-book cost estimate scales by exactly 50/20 = 2.5x.**

### 3.3 Hard ceiling (arithmetic, not an estimate)

The per-call `--max-budget-usd` caps are enforced by the `claude` CLI, so the spend is bounded:

```
closed-book : 400 calls x $0.10/call = $40.00   (absolute ceiling)
shortcut    : 400 calls x $0.05/call = $20.00   (absolute ceiling)
                                       -------
worst case                             $60.00
```

This ceiling holds even if every single call saturates its budget. It will not be approached.

### 3.4 Realistic estimate

Anchor: tempdoc 707 (`707-pillar1-inband-utility-corpus.md:432-434`, `:450-451`) estimates
closed-book certification at **"low single-digit dollars"** / **"~$1-3"** at the then-current
**20 queries/cell** across the member matrix, i.e. ~160 calls.

```
implied per-call basis   = $1..$3 / 160 calls        ~= $0.006 .. $0.019 / call

closed-book at n=50      = 400 calls x $0.006..0.019  = $2.50 .. $7.50
```

The shortcut probe injects a **full evidence document** into each prompt, where closed-book sends
only the question. CLERC opinions are long, so input tokens dominate and per-call cost is higher —
conservatively 2-5x the closed-book basis, then clipped by its own $0.05 cap:

```
shortcut probe at n=50   = 400 calls x $0.012..0.050  = $5.00 .. $20.00
```

**Estimated total: ~$8 - $28. Hard ceiling $60.**

The wide band is honest: no measured per-call cost for the document-injecting probe exists in the
repo. If the founder wants a tighter number, run Phase B on **one** cell first (50 calls, ~$0.30-$1)
and extrapolate from the observed spend — cheap de-risking of the whole budget.

### 3.5 Wall-clock

**Paid, no stack** (Phase B, closed-book only): 400 calls / 8 concurrent = 50 batches. At ~5-15 s
per `claude -p` round trip: **~4-13 minutes**.

**GPU / stack window** (Phase C): the precedent is **~1h20m for an 8-cell cohort**, and it **is**
a real measurement — `707-pillar1-inband-utility-corpus.md:511-512` records an executed run,
"Full gate chain executed under the founder-directed 30-min wakeup supervision loop (16:55–18:15
wall, ~1 h 20 m GPU)". (An earlier draft of this runbook could not locate the source and labelled
it *recalled*; that was wrong and is corrected here.) Treat it as a measured precedent for a
**20-query** cohort — this cohort has **50 queries per cell**, so the per-query paid probes inside
Phase C scale 2.5×, while the ingest/index portion does not.

Comparability of this cohort against that precedent:

- **Comparable:** same shape — 8 cells, 4x1k + 4x10k. Document counts are unchanged, and
  ingest + enrichment dominates the window.
- **Changed by n=50:** query execution is 2.5x, but it is a small share of a run next to ingest.
- **Added by n=50:** the paid shortcut probe adds ~50 calls/cell at concurrency 8, roughly
  **1-3 min/cell**, i.e. **+8-25 min** across the cohort — serial time inside the GPU window.
- **Path-dependent:** the self-contained route (§5 Phase C, option 1) ingests each cell **twice**
  (once for `jseval run`, once for `corpus-fidelity --start-backend`), roughly doubling the ingest
  half.

**Estimated stack window: 1h30m - 3h.** Size the lease accordingly, noting the clamp in §4.

---

## 4. Dev-stack coordination

Authority: `.claude/rules/branch-safety.md` §Shared Dev Stack, and the `/dev-stack` skill.
One stack runs at a time across all worktrees.

### Before taking the stack

1. `justsearch_dev_quick_health` — always first. Read `ownership.verdict`, do not infer from raw
   lease fields.
2. Act on the verdict:
   - `TAKEOVER_ABANDONED` — `start` self-serve-proceeds, no user round-trip.
   - `IDLE_HOLD` — owner alive but idle; `takeover: "warn"` is self-authorizable.
   - `CONTENTION` — owner actively using it. **Ask the founder.** A `force` takeover requires
     explicit user direction.
   - `HANDSHAKE_REQUIRED` / `REQUIRES_CONFIRMATION` — a critical op-lease is running; see
     `criticalOps[]` and the `/dev-stack` error-code table. Do not force past an
     `UNSAFE_TO_INTERRUPT` op to save time.
3. `justsearch_dev_acquire_when_free` — preferred over a conflict/ask/retry loop when the stack is
   busy but the window is not urgent.
4. `justsearch_dev_preflight` before a long run.

### Declaring the lease

Start with an explicit campaign lease so a minutes-long `jseval` step does not lapse into
`TAKEOVER_ABANDONED` mid-run:

```
justsearch_dev_start { leaseDurationSec: 7200, ... }
```

> **`leaseDurationSec` is clamped server-side to [30, 7200] — 2 hours maximum.** The §3.5 estimate
> runs to 3h, so a full cohort in one window **will** outlive the maximum lease. Either split the
> cohort across two windows (natural boundary: legal member, then email member), or keep the
> session touching the stack so the lease renews. Do not assume one `start` covers a 3h run.

Monitor remaining hold via `ownership.lease.remainingSec` on `justsearch_dev_quick_health` /
`justsearch_dev_status`.

### During and after

- `justsearch_dev_tail_log` for progress; `justsearch_dev_status` for process state.
- **`justsearch_dev_stop` when finished** — the stack is shared and holds VRAM and ports.
- This is an attended run. Per `/dev-stack`, an unattended multi-hour window needs an explicit,
  recent founder go for *that window*; a budget remark is not an arming.

### On contention mid-run

See §7 R3. The short version: measured artifacts already written to `eval-results/` survive, so
recover per-cell rather than restarting the cohort.

---

## 5. The sequence

Eight cells. `SIZE` in `{1000, 10000}`, `VARIANT` in `{verbose, short-natural}`, for each of
`en-legal-clerc` and `en-email-enron-raw`. Dataset names follow
`mixed/<member>-<1k|10k>-<variant>`.

### Phase A — Re-materialize and re-baseline (FREE, offline, ~10 min)

Clears B1 and B2. Nothing downstream is valid without it.

**A1.** Re-materialize all 8 cells from the committed recipes. The authoritative parameters live in
`707-corpora/<member>/<SIZE>-<VARIANT>/recipe.json` (verified: `seed: 707`, `style: interleave`,
`host_min_words: 60`, `n_distractors` 900 at 1k / 9900 at 10k) and the shared assembly block in
`707-corpora/<member>/member.v1.json`.

The exact `corpus-inject-real` invocations used for the n=50 rebuild are **not recorded verbatim
anywhere in the repo** (searched; only the narrative in §N exists). The agents who ran the rebuild
hold them. Rather than reconstruct them here and risk a wrong flag, **treat A2 as the acceptance
test** — it is exact, mechanical, and free.

**A2. Gate script — the acceptance test for A1.** Validated offline; this exact script produced the
mismatch table that identified B2.

```bash
python - <<'PY'
import json, sys, hashlib
from pathlib import Path
sys.path.insert(0, 'scripts/jseval')
from jseval.corpus_identity import corpus_signature
D = Path('datasets/mixed')
bad = 0
for m in ('en-legal-clerc', 'en-email-enron-raw'):
    cert = json.load(open(f'scripts/jseval/707-corpora/{m}/structural-certification.v1.json',
                          encoding='utf-8'))
    for size, vs in cert['datasets'].items():
        for v, c in vs.items():
            root = D / c['dataset'].split('/', 1)[1]
            if not root.is_dir():
                print('MISSING', root); bad += 1; continue
            sig = corpus_signature(root)
            qg = hashlib.sha256((root / 'queries.json').read_bytes()).hexdigest()
            n = len(json.load(open(root / 'queries.json', encoding='utf-8')))
            ok = (sig == c['corpus_signature'] and qg == c['query_gold_sha256']
                  and n == c['query_count'])
            print('OK  ' if ok else 'MISMATCH', root.name, 'n=', n)
            bad += (not ok)
print('\nPHASE A', 'PASS' if not bad else f'FAIL ({bad} cells)')
sys.exit(1 if bad else 0)
PY
```

**A3.** Re-run structural certification for both members and confirm `structurally-certified`
(no `--scientific-evidence` — this stays free and offline):

```bash
$JS corpus-certify-member \
  --member en-legal-clerc \
  --datasets-dir datasets \
  --output scripts/jseval/707-corpora/en-legal-clerc/structural-certification.v1.json \
  --dataset 1000:verbose:en-legal-clerc-1k-verbose \
  --dataset 1000:short-natural:en-legal-clerc-1k-short-natural \
  --dataset 10000:verbose:en-legal-clerc-10k-verbose \
  --dataset 10000:short-natural:en-legal-clerc-10k-short-natural \
  --commitment 1000:verbose:scripts/jseval/707-corpora/en-legal-clerc/1000-verbose \
  --commitment 1000:short-natural:scripts/jseval/707-corpora/en-legal-clerc/1000-short-natural \
  --commitment 10000:verbose:scripts/jseval/707-corpora/en-legal-clerc/10000-verbose \
  --commitment 10000:short-natural:scripts/jseval/707-corpora/en-legal-clerc/10000-short-natural
```

Repeat with `--member en-email-enron-raw` and the `en-email-enron-raw-*` names.

**A4.** Re-pin the policy per B1, then verify it resolves. This must print a threshold dict for all
8 cells, not the mismatch error:

```bash
python - <<'PY'
import json, sys
sys.path.insert(0, 'scripts/jseval')
from jseval.corpus_certify import (_active_scientific_policy_cells, _policy_threshold,
                                   SCIENTIFIC_GATES)
pol = json.load(open('scripts/jseval/707-corpus-certification-policy.v1.json', encoding='utf-8'))
bad = 0
for m in ('en-legal-clerc', 'en-email-enron-raw'):
    cells = _active_scientific_policy_cells(pol, member=m)
    cert = json.load(open(f'scripts/jseval/707-corpora/{m}/structural-certification.v1.json',
                          encoding='utf-8'))
    for size, vs in cert['datasets'].items():
        for v, c in vs.items():
            for g in SCIENTIFIC_GATES:
                try:
                    _policy_threshold(cells, dataset=c['dataset'],
                                      corpus_signature=c['corpus_signature'],
                                      query_gold_sha256=c['query_gold_sha256'],
                                      query_count=c['query_count'], gate=g)
                except ValueError as e:
                    print('FAIL', e); bad += 1
print('POLICY', 'PASS' if not bad else 'FAIL')
sys.exit(1 if bad else 0)
PY
```

> **Do not proceed past Phase A until A2, A3 and A4 all pass.** Everything after this point costs
> money or GPU time, and every downstream artifact is bound to the corpus signature A2 verifies.

---

### Phase B — Closed-book gate (PAID, no stack, ~4-13 min, est. $2.50-$7.50)

Runs 8x, once per cell. No dev stack — **do not hold the stack during this phase.**

Per-cell prompt count = 50. `--threshold 0.15` matches the policy's `maximum_accuracy`
(`707-corpus-certification-policy.v1.json`); `--model haiku` and `--concurrency 8` are the
defaults, stated explicitly so the record is unambiguous.

```bash
for DS in en-legal-clerc-1k-verbose en-legal-clerc-1k-short-natural \
          en-legal-clerc-10k-verbose en-legal-clerc-10k-short-natural \
          en-email-enron-raw-1k-verbose en-email-enron-raw-1k-short-natural \
          en-email-enron-raw-10k-verbose en-email-enron-raw-10k-short-natural; do
  $JS corpus-certify \
    --dataset "mixed/$DS" \
    --datasets-dir datasets \
    --model haiku \
    --threshold 0.15 \
    --concurrency 8
done
```

**Output:** writes `closed_book_certification` into `datasets/mixed/<DS>/metadata.json`. That
`metadata.json` **is** the `closed_book` measurement file for Phase D — `_derive_scientific_verdict`
reads `measurement_source["closed_book_certification"]` (`corpus_certify.py:455`). No stdout capture
needed.

**Safe by construction:** `corpus_signature` is `sha256(corpus.jsonl + qrels/test.tsv)`
(`corpus_identity.py:20-58`) — `metadata.json` is not an input, so mutating it here does **not**
invalidate the signature Phase A pinned. Verified.

**Cost-de-risking option:** run the first cell alone, observe actual spend, then decide whether to
proceed with the remaining seven (§3.4).

---

### Phase C — Backend gates (GPU + PAID, est. 1h30m-3h)

Acquire the stack per §4 first.

Each cell needs two artifacts:

| Artifact | Produced by | Consumed by |
|---|---|---|
| `<run_dir>/manifest.json` | `jseval run` | all 3 backend gates (`--run-manifest`) |
| `<run_dir>/projections/staged_recall_accounting.json` | `jseval run` (auto) | `union_recall`, `leak_floor` |
| fidelity result JSON | `corpus-fidelity` (stdout) | `retrieval_calibration` |

**C1 — Evaluation run (per cell).** Produces the manifest and the projection. The projection is
written automatically by the registered-projection batch to
`<run_dir>/projections/staged_recall_accounting.json` (`projections/base.py:94-113`).

```bash
$JS run \
  --dataset "mixed/$DS" \
  --modes lexical,vector,splade,hybrid \
  --embedding \
  --splade \
  --pipeline \
  --start-backend \
  --clean
```

`--modes` must include a final `hybrid` plus at least one leg mode, or the projection returns
`status: "insufficient-modes"` and both `union_recall` and `leak_floor` fail their
`status == "ok"` structural check (`corpus_certify.py:582`).

**All three leg modes are mandatory here — see B4.** The structural check is satisfied by two legs,
so an omitted `splade` produces a green-looking projection with a silently lowered
`leg_union_recall`, failing every `union_recall` gate after the spend. `--embedding` is required
per `707-pillar1-inband-utility-corpus.md:491-492`.

**Do NOT pass `--allow-degraded`.** `retrieval_calibration` requires `comparable: true` with an
empty `comparability_reasons` (`corpus_certify.py:542-544`); a degraded engine set fails the gate
after the GPU time is already spent.

**C2 — Fidelity assessment (per cell).** This is the paid half inside the GPU window.

```bash
$JS --json corpus-fidelity \
  --dataset "mixed/$DS" \
  --datasets-dir datasets \
  --modes lexical,vector,hybrid \
  --embedding \
  --model haiku \
  --concurrency 8 \
  --start-backend \
  --clean \
  > "tmp/fidelity-$DS.json"
```

Two operational cautions:

- `--start-backend` **requires** `--clean` (the command refuses otherwise,
  `commands/corpus.py:519-522`), and it re-ingests. This is the second ingest of the cell — the
  main driver of the wall-clock band in §3.5. If a single shared backend can be kept up across C1
  and C2, pass `--base-url` instead of `--start-backend --clean` and halve the ingest time. That
  optimisation was **not validated** here (§8) — verify it on one cell before relying on it.
- **`corpus-fidelity` has no `--output` flag.** The measurement must be captured from stdout, and
  `--json` mode may interleave ingest progress. Verify `tmp/fidelity-$DS.json` parses as a single
  JSON object with a top-level `retrieval_ndcg` before proceeding; if not, extract the trailing
  JSON object. The nested `fidelity` block written into `metadata.json` is **not** a substitute —
  the verdict reads these keys at top level (`corpus_certify.py:474-487`).

Record each cell's run directory; Phase D needs it:

```bash
RUN_DIR=$(ls -td scripts/jseval/tmp/eval-results/*/ | head -1)
```

---

### Phase D — Build the 16 evidence artifacts (FREE, offline, ~1 min)

4 gates x 4 cells per member. Run all 16 per member; nothing is paid or GPU-bound here.

`--run-manifest` is **mandatory for the three backend gates and forbidden for `closed_book`** —
`build_scientific_measurement_artifact` raises on either mistake (`corpus_certify.py:341-344`).

```bash
# closed_book — measurement is metadata.json, NO --run-manifest
$JS corpus-scientific-evidence-build \
  --member en-legal-clerc \
  --dataset "mixed/$DS" \
  --dataset-dir "datasets/mixed/$DS" \
  --gate closed_book \
  --measurement "datasets/mixed/$DS/metadata.json" \
  --output "tmp/evidence/$DS.closed_book.json"

# retrieval_calibration — measurement is the captured fidelity JSON
$JS corpus-scientific-evidence-build \
  --member en-legal-clerc \
  --dataset "mixed/$DS" \
  --dataset-dir "datasets/mixed/$DS" \
  --gate retrieval_calibration \
  --measurement "tmp/fidelity-$DS.json" \
  --run-manifest "$RUN_DIR/manifest.json" \
  --output "tmp/evidence/$DS.retrieval_calibration.json"

# union_recall and leak_floor — same projection, different threshold
for G in union_recall leak_floor; do
  $JS corpus-scientific-evidence-build \
    --member en-legal-clerc \
    --dataset "mixed/$DS" \
    --dataset-dir "datasets/mixed/$DS" \
    --gate "$G" \
    --measurement "$RUN_DIR/projections/staged_recall_accounting.json" \
    --run-manifest "$RUN_DIR/manifest.json" \
    --output "tmp/evidence/$DS.$G.json"
done
```

`union_recall` and `leak_floor` deliberately share one measurement file — the verdict derives
different fields and thresholds from it (`corpus_certify.py:573-595`).

---

### Phase E — Final certification (FREE, offline, all-or-nothing)

Exactly 16 `--scientific-evidence` entries per member. The CLI enforces the complete matrix and
rejects any partial set (`commands/corpus.py:103-111`) — there is no incremental mode.

```bash
M=en-legal-clerc
$JS corpus-certify-member \
  --member "$M" \
  --datasets-dir datasets \
  --output "scripts/jseval/707-corpora/$M/structural-certification.v1.json" \
  --dataset 1000:verbose:$M-1k-verbose \
  --dataset 1000:short-natural:$M-1k-short-natural \
  --dataset 10000:verbose:$M-10k-verbose \
  --dataset 10000:short-natural:$M-10k-short-natural \
  --commitment 1000:verbose:scripts/jseval/707-corpora/$M/1000-verbose \
  --commitment 1000:short-natural:scripts/jseval/707-corpora/$M/1000-short-natural \
  --commitment 10000:verbose:scripts/jseval/707-corpora/$M/10000-verbose \
  --commitment 10000:short-natural:scripts/jseval/707-corpora/$M/10000-short-natural \
  --scientific-evidence 1000:verbose:closed_book:tmp/evidence/$M-1k-verbose.closed_book.json \
  --scientific-evidence 1000:verbose:retrieval_calibration:tmp/evidence/$M-1k-verbose.retrieval_calibration.json \
  --scientific-evidence 1000:verbose:union_recall:tmp/evidence/$M-1k-verbose.union_recall.json \
  --scientific-evidence 1000:verbose:leak_floor:tmp/evidence/$M-1k-verbose.leak_floor.json \
  --scientific-evidence 1000:short-natural:closed_book:tmp/evidence/$M-1k-short-natural.closed_book.json \
  --scientific-evidence 1000:short-natural:retrieval_calibration:tmp/evidence/$M-1k-short-natural.retrieval_calibration.json \
  --scientific-evidence 1000:short-natural:union_recall:tmp/evidence/$M-1k-short-natural.union_recall.json \
  --scientific-evidence 1000:short-natural:leak_floor:tmp/evidence/$M-1k-short-natural.leak_floor.json \
  --scientific-evidence 10000:verbose:closed_book:tmp/evidence/$M-10k-verbose.closed_book.json \
  --scientific-evidence 10000:verbose:retrieval_calibration:tmp/evidence/$M-10k-verbose.retrieval_calibration.json \
  --scientific-evidence 10000:verbose:union_recall:tmp/evidence/$M-10k-verbose.union_recall.json \
  --scientific-evidence 10000:verbose:leak_floor:tmp/evidence/$M-10k-verbose.leak_floor.json \
  --scientific-evidence 10000:short-natural:closed_book:tmp/evidence/$M-10k-short-natural.closed_book.json \
  --scientific-evidence 10000:short-natural:retrieval_calibration:tmp/evidence/$M-10k-short-natural.retrieval_calibration.json \
  --scientific-evidence 10000:short-natural:union_recall:tmp/evidence/$M-10k-short-natural.union_recall.json \
  --scientific-evidence 10000:short-natural:leak_floor:tmp/evidence/$M-10k-short-natural.leak_floor.json
```

Repeat verbatim with `M=en-email-enron-raw`.

**Success:** `status: "fully-certified"`, `fully_certified: true`, and all four
`scientific_gates` reading `passed`. The command raises `scientific certification failed` on any
gate miss (`commands/corpus.py:130-131`).

---

### Phase F — Publication pointer (only after Phase E is green)

**Sequencing constraint, from §N.4 and confirmed in code:** re-certify **before** selecting any
publication pointer.

The claim policy validates the certification copy **embedded inside** a published claim:
`certification_snapshot` base64-embeds the whole certification document and its sha256
(`corpus_certify.py:654-664`), and validation re-decodes and re-checks that embedded copy
(`corpus_certify.py:738-748`, with `_complete_certification_document` requiring
`status == "fully-certified"` and every gate `passed` at `:791-796`). `utility_claim_policy.py`
~:342 consumes that snapshot.

A pointer selected against the current `structurally-certified` document would embed a copy that
can never validate. **Do not select or publish a pointer until Phase E prints `fully-certified`.**

---

## 6. Idempotency

| Phase | Idempotent? | Notes |
|---|---|---|
| A (materialize) | Yes | Deterministic by seed; A2 verifies byte-identity |
| A3/A4 (structural, policy) | Yes | Pure reads plus one output file |
| **B (closed-book)** | **Yes, but re-charges** | Overwrites `metadata.json`; a re-run costs the full ~$2.50-$7.50 again |
| **C1 (`jseval run`)** | Yes, new run dir | Each run writes a **new** timestamped dir; old ones survive |
| **C2 (`corpus-fidelity`)** | **Yes, but re-charges** | Re-runs 50 paid probes for that cell |
| D (evidence build) | Yes | Pure function of inputs; free, re-run freely |
| E (certify) | Yes | Pure function of inputs; free, re-run freely |

**The key asymmetry: D and E are free and idempotent; B and C cost money every time.** When a
certification fails, re-run D and E first — a large share of failures are envelope or path
mistakes, not bad measurements.

---

## 7. Failure modes and recovery

### R1 — The paid run dies halfway (Phase B)

**Partial artifacts are reusable.** `corpus-certify` writes `metadata.json` per cell on completion,
so every cell that finished keeps its `closed_book_certification`. Restart the loop from the first
cell whose `metadata.json` lacks the block:

```bash
for DS in ...; do
  python -c "import json,sys; m=json.load(open(f'datasets/mixed/$DS/metadata.json',encoding='utf-8')); \
sys.stdout.write('$DS ' + ('HAVE' if 'closed_book_certification' in m else 'MISSING') + '\n')"
done
```

Only the **certification matrix** is all-or-nothing, not the **measurement production**. Do not
re-run all 8 cells because one failed.

Caveat: `closed_book_filter` swallows per-call exceptions and treats a failed call as
retrieval-relevant (`utility_calibrate.py:443-444`). A cell whose calls silently failed en masse
still writes a plausible-looking `closed_book_accuracy` near 0. **Sanity-check `n_queries == 50`
and that accuracy is not suspiciously uniform across cells** before trusting a batch that hit
errors. This is the one place a partial failure can pass as success.

### R2 — A gate's evidence is rejected at Phase E

Re-derive what the rejection depends on — nothing more:

| Rejection | Re-run |
|---|---|
| Envelope/identity mismatch (`status: "failed"`, no `observed`) | D then E only — **free** |
| `closed_book` accuracy over `maximum_accuracy` | Real result: the corpus is contaminated. Do **not** re-run to get a better number — investigate |
| `retrieval_calibration` `comparable: false` | C1+C2 for that cell with a full engine set (no `--allow-degraded`) |
| `retrieval_calibration` nDCG out of band | Threshold vs. reality question — see B1's pre-registration caution. **Not** a re-run |
| `union_recall`/`leak_floor` `status != "ok"` | C1 for that cell with all leg modes present |
| Anything else | D then E first; re-measure only if D+E still reject |

Because Phase E is all-or-nothing, **one** bad cell blocks the member. But recovery is per-cell:
re-measure the one cell, rebuild its 4 artifacts, re-run E with all 16.

### R3 — The stack is taken mid-run (Phase C)

Completed cells' run dirs under `scripts/jseval/tmp/eval-results/` survive a takeover — they are
written per run, not at cohort end. Re-acquire per §4 and resume at the first cell without a
complete run dir. Losses are limited to the in-flight cell, including its paid probes (up to 50
calls, est. under $2.50).

To reduce exposure: run Phase C **one member at a time** (4 cells, comfortably inside the 2h lease
clamp) rather than all 8 in one window.

### R4 — `707 scientific policy identity mismatch`

B1, or a corpus re-materialized after the policy was pinned. Re-run A2 and A4. If A2 passes and A4
fails, the policy was pinned to different bytes — re-pin from the current certification.

### R5 — `--scientific-evidence matrix must be exactly complete for every member cell`

A missing or misnamed path among the 16. Free to diagnose: confirm 16 files exist and each
`SIZE:VARIANT:GATE` triple appears exactly once. `SIZE` is `1000`/`10000` (not `1k`/`10k`) — the
dataset *names* use `1k`/`10k` but the matrix keys do not. Easy to get wrong.

---

## 8. Validation status

Everything below was checked **offline, at zero cost**, against the code in this worktree.

**Validated:**

- All four commands exist and their `--help` parses: `corpus-certify`, `corpus-fidelity`,
  `corpus-certify-member`, `corpus-scientific-evidence-build`.
- Every flag used above exists with the spelling shown (read from the `@click.option` decorators,
  not from memory).
- The bare `jseval` entry point fails from this worktree; the `PYTHONPATH=scripts/jseval
  python -m jseval` form works.
- **B1 reproduced directly** — `_policy_threshold` raises
  `707 scientific policy identity mismatch for mixed/en-legal-clerc-1k-verbose` against the current
  certification.
- **B2 reproduced directly** — `datasets/` absent here; the only on-disk copy (step2-powered) is
  n=20 and mismatches all 8 signatures, while matching the stale policy exactly.
- **B3 confirmed by code path** — `corpus_fidelity.py:53-84` shells out to `claude` per query.
- Per-call budget caps ($0.10 closed-book, $0.05 shortcut) read directly from the subprocess
  argument lists.
- Measurement shapes for all four gates traced through `_derive_scientific_verdict`
  (`corpus_certify.py:409-596`), which is what determines the correct `--measurement` file per gate.
- `corpus_signature` excludes `metadata.json`, so Phase B does not invalidate Phase A.
- The projection output path `<run_dir>/projections/staged_recall_accounting.json`
  (`projections/base.py:94-113`).
- The Phase A2 gate script — it is the script that found B2.

**NOT validated (could not be checked without spending or taking the stack):**

- No command in Phases B, C, D or E was executed. Flags parse; end-to-end behaviour is unverified.
- The `corpus-inject-real` invocations for Phase A1 — not recorded anywhere in the repo. Phase A2
  is the compensating control.
- Whether `corpus-fidelity --json` emits a cleanly parseable single JSON object on stdout when
  ingest progress is interleaved (§5 C2). **Most likely first real-run failure.**
- The shared-backend optimisation in C2 (`--base-url` against C1's stack instead of re-ingesting).
- ~~Whether `--modes lexical,vector,hybrid` yields `status: "ok"` from the projection for these
  specific corpora~~ — RESOLVED, and it is the wrong question: it *does* yield `status: "ok"`, which
  is precisely the trap. See **B4** — the three-mode set under-measures `leg_union_recall` by
  omitting `splade`, observed on a pilot re-run (union 0.48 vs. the certified 0.75). Phase C1 now
  passes all three leg modes.
- All cost and wall-clock figures in §3. Estimates, per §3's header.
- ~~The "~1h20m for 8 cells" precedent~~ — RESOLVED: it is a real measurement at `707-pillar1-inband-utility-corpus.md:511-512` (executed run, 16:55–18:15 wall). Corrected in the Phase C estimate above.

**Recommended first real action:** Phase A (free), then **Phase B on a single cell** to convert the
§3.4 cost band into a measured per-call number before committing to the remaining seven.

---

## 9. Original measured baselines (comparison table)

These are the **measured** numbers from the n=20 certified run
(`707-pillar1-inband-utility-corpus.md:517-524`, executed 2026-07-15, 16:55–18:15 wall, all 8 steps
rc=0; leg set included `splade`, headline mode `hybrid` with `--embedding`). Recorded here so a
re-run has a direct before/after instead of deriving expectations from the thresholds — which is
how B4 nearly went undetected.

| cell | hybrid nDCG@10 | vector | lexical | verdict (default band) | union recall | leak |
|---|---|---|---|---|---|---|
| en-legal-clerc-1k-verbose | **0.5051** | 0.5952 | 0.0193 | PASS (moderate) | **0.75** | 0.0 |
| en-legal-clerc-1k-short-natural | **0.4685** | — | — | PASS (hard) | — | — |
| en-legal-clerc-10k-verbose | **0.3238** | 0.4165 | 0.0000 | PASS (hard) | — | — |
| de-miracl-1k-verbose | 0.1849 | 0.1890 | 0.0000 | **FAIL** (below band) | 0.35 | 0.0 |
| de-miracl-1k-short-natural | 0.2271 | — | — | **FAIL** | — | — |
| de-miracl-10k-verbose | 0.0324 | — | — | **FAIL** (collapsed) | — | — |

Reading notes:

- The bands shown are the **default** (0.3–0.85), not the ratified policy thresholds — do not treat
  the verdict column as the gate outcome.
- `—` means the cell was not measured for that column in that run, **not** zero.
- These are **n=20**; the run this runbook prepares is **n=50** (§N.1). Expect movement from the
  sample-size change alone — that is a genuine difference in the thing measured, unlike B4's
  configuration artefact. Distinguish the two before touching a threshold (B1's caution).
- Two cells in the 8-cell cohort are not in this table (the run predates them); for those there is
  no prior measurement to compare against.
