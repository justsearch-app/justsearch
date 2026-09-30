"""Hybrid EM->LLM judge for the agent-utility eval (tempdoc 624 §C-6 / E-5).

Substring-EM is below the 2026 bar — "the first thing a hostile reviewer attacks".
This is the credible hybrid floor: **EM is a high-precision auto-PASS; the
EM-MISSES are adjudicated by an LLM judge from a DIFFERENT model family than the
claude agent under test** (the local llama-server — the self-preference control),
**dual-order** to cancel position bias (a >10 pp confound), **abstaining to the EM
verdict on disagreement**.

It runs **post-hoc over the Inspect EvalLogs** — the agent answers + targets are
already stored, so judging is decoupled from the expensive agent run and
re-judgeable without re-running agents (and validatable for free against the
committed floor logs). Emits a `judge-overlay.json` (final verdicts + the judge
identity that enters the cohort key + the EM-vs-judge **agreement rate**, the E-5
caveat as a field).

**Human-calibration machinery (tempdoc 624 §M.4/§T.3):** `agreement_rate` above is
a *self-consistency* statistic (judge vs. the cheap EM fallback), not a validated
accuracy figure against ground truth. `sample_for_calibration` /
`rater_agreement_report` / `attach_human_calibration` build the calibration
protocol the design settled on: a stratified sample of judge verdicts (oversampling
the EM-disagreement cases, where judge error concentrates), scored by >=2
INDEPENDENT raters, reporting both judge-vs-rater-majority agreement and
rater-vs-rater agreement (Cohen's kappa for exactly 2 raters, Krippendorff's alpha
for 3+ — not yet built, see `rater_agreement_report`), each with a bootstrap CI.
Real human raters are not available in an autonomous pipeline; `run_calibration_dry_run`
proves the machinery end-to-end with two independent AGENT-SUBSTITUTE heuristic raters
instead — the emitted `rater_kind` field says so explicitly so the number is never
mistaken for a real calibration figure.
"""

from __future__ import annotations

import json
import logging
import random
import time
import warnings
from pathlib import Path
from typing import Callable

from .operation_key import create_operation_key

import httpx
import numpy as np

from jseval.agent_manifest import judge_identity
from jseval.agent_retrieval_eval import _score_answer
from jseval.manifest import _sha256_canonical

log = logging.getLogger(__name__)

_JUDGE_SYSTEM = (
    "You grade a question-answering system. Decide whether the CANDIDATE answer is "
    "factually correct given the REFERENCE answer. Reply with exactly 'YES' if the "
    "candidate conveys the same factual answer as the reference, or 'NO' otherwise. "
    "Ignore phrasing, verbosity, ordering, and formatting differences."
)
_JUDGE_PROMPT_HASH = _sha256_canonical(_JUDGE_SYSTEM)
_NULL_GT = "insufficient information"


def _probe_judge_model(judge_url: str) -> str | None:
    """Best-effort: the served model id (for the judge identity / cohort key).

    Handles both OpenAI (`{"data":[{"id"}]}`) and the JustSearch/Ollama-style
    (`{"models":[{"name"|"model"}]}`) `/v1/models` shapes."""
    try:
        body = httpx.get(f"{judge_url}/v1/models", timeout=10.0).json()
        items = body.get("data") or body.get("models") or []
        if not items:
            return None
        m = items[0]
        return m.get("id") or m.get("name") or m.get("model")
    except Exception:
        return None


def _judge_once(judge_url, model, question, reference, candidate, *, ref_first: bool) -> bool:
    """One judge call in a fixed order (dual-order is two calls with ref_first flipped)."""
    if ref_first:
        user = (f"Question: {question}\nREFERENCE answer: {reference}\n"
                f"CANDIDATE answer: {candidate}\n\nIs the candidate correct? YES or NO.")
    else:
        user = (f"Question: {question}\nCANDIDATE answer: {candidate}\n"
                f"REFERENCE answer: {reference}\n\nIs the candidate correct? YES or NO.")
    body = {
        "model": model or "local",
        "messages": [{"role": "system", "content": _JUDGE_SYSTEM},
                     {"role": "user", "content": user}],
        "max_tokens": 4, "temperature": 0.0,
        "chat_template_kwargs": {"enable_thinking": False},
    }
    r = httpx.post(f"{judge_url}/v1/chat/completions", json=body, timeout=60.0)
    txt = ((r.json().get("choices") or [{}])[0].get("message", {}).get("content") or "").strip().upper()
    return txt.startswith("YES")


def _target_text(s) -> str:
    t = getattr(s, "target", None)
    if isinstance(t, str):
        return t
    if isinstance(t, list) and t:
        return str(t[0])
    return str(t or "")


def _iter_eval_records(log_dir: str):
    """Yield raw per-sample records across every EvalLog in `log_dir`.

    The log-walking core shared by `judge_logs` (scoring) and
    `collect_calibration_texts` (the calibration dry run's text lookup) — kept as
    one generator so the two never drift on which files/samples are skipped.
    Yields ``{"cond", "seed", "qid", "question", "reference", "candidate", "em"}``.
    """
    from inspect_ai.log import read_eval_log

    for lf in sorted(Path(log_dir).glob("*.json")):
        if lf.name in ("eval-set.json", "logs.json"):
            continue
        try:
            log = read_eval_log(lf.as_posix())
        except Exception:
            continue
        if not getattr(log, "eval", None):
            continue
        for s in (log.samples or []):
            if (s.metadata or {}).get("error") or getattr(s, "error", None):
                continue
            # tempdoc 675 single pool: condition is a sample field; sample.id is
            # "{cond}|q{i}" — read cond per-sample and strip to the bare qid.
            cond = (s.metadata or {}).get("condition")
            seed = int(s.epoch or 1) - 1
            qid = str(s.id).split("|", 1)[-1]
            ref = _target_text(s)
            cand = (getattr(s.output, "completion", "") if s.output else "") or ""
            question = s.input if isinstance(s.input, str) else ""
            em = _score_answer(ref, cand)
            yield {"cond": cond, "seed": seed, "qid": qid, "question": question,
                   "reference": ref, "candidate": cand, "em": em}


def judge_logs(log_dir: str, *, judge_url: str = "http://127.0.0.1:33221",
               judge_model: str | None = None) -> dict:
    """Hybrid re-score over the EvalLogs. Returns the overlay artifact (dict).

    Shape: ``{"judge_identity", "stats", "scores": {"<cond>|<seed>|<qid>":
    {"em", "judge", "final"}}}``. ``judge`` is null for EM auto-passes and for
    null/abstention queries (the judge grades factual answers, not abstention —
    those stay EM). On any judge-call failure the cell falls back to EM (graceful).

    ``judge_url`` is the JustSearch **Head API's own base URL** (its OpenAI-compat
    proxy, ``OpenAiCompatController.java``), NOT llama-server's raw ephemeral port —
    the Head forwards ``/v1/chat/completions``/``/v1/models`` to whichever port
    llama-server actually bound, specifically so callers don't have to discover it.
    The default matches jseval's own eval-backend port
    (``_DEFAULT_BASE_URL_EVAL`` in ``jseval/commands/_common.py``); override with
    the live backend's real base URL if it was started on a different port. Prior
    default (``:8080``) was the *production* Head API port, wrong for eval-backend
    runs which use ``:33221`` (tempdoc 624 "judge-scoring gap" follow-up).
    """
    model = judge_model or _probe_judge_model(judge_url)
    scores: dict = {}
    em_pass = judged = agree = disagree = flips = call_failures = 0

    for rec in _iter_eval_records(log_dir):
        cond, seed, qid = rec["cond"], rec["seed"], rec["qid"]
        ref, cand, question, em = rec["reference"], rec["candidate"], rec["question"], rec["em"]
        final, judge_verdict = em, None
        # Judge only EM-missed, NON-null queries (abstention stays EM).
        if not em and ref.lower().strip().rstrip(".") != _NULL_GT:
            try:
                v1 = _judge_once(judge_url, model, question, ref, cand, ref_first=True)
                v2 = _judge_once(judge_url, model, question, ref, cand, ref_first=False)
                judged += 1
                if v1 == v2:
                    agree += 1
                    judge_verdict = v1
                    final = v1
                    if v1:
                        flips += 1  # judge rescued an EM-miss
                else:
                    disagree += 1  # position-bias disagreement -> abstain to EM
            except Exception:
                call_failures += 1
        else:
            em_pass += int(em)
        scores[f"{cond}|{seed}|{qid}"] = {"em": em, "judge": judge_verdict, "final": final}

    stats = {
        "em_auto_pass": em_pass, "judged_misses": judged,
        "judge_flips": flips, "judge_disagreements": disagree,
        "agreement_rate": (round(agree / judged, 4) if judged else None),
        "call_failures": call_failures,
        "degraded_to_em": (judged == 0 and call_failures > 0),
    }
    return {
        "judge_identity": judge_identity(
            kind=("hybrid-em-llm" if judged > 0 else "substring-em"),
            model=model, version=model, prompt_hash=_JUDGE_PROMPT_HASH),
        "stats": stats,
        "scores": scores,
    }


# --- Human-calibration machinery (tempdoc 624 §M.4 / §T.3) -----------------------


def sample_for_calibration(scores: dict, *, n: int = 40, seed: int = 0,
                            disagreement_frac: float = 0.6) -> list[str]:
    """Draw a deterministic, stratified sample of `scores`-dict keys for calibration.

    Splits `scores` into two strata: **disagreement** (the judge fired and its
    verdict differs from the raw EM call — ``judge is not None and em != judge``,
    i.e. a judge-flip/rescue) and everything else. Disagreement is where judge
    error concentrates (§M.4) and EM-agreement cells are high-precision by
    construction, so the disagreement stratum is oversampled to
    `disagreement_frac` of the sample (default 60%) — far above its natural
    frequency, which is typically a small fraction of all items. Deterministic
    given `seed` (stdlib `random.Random`): identical inputs -> identical sample,
    so repeated calibration passes over the same overlay are reproducible.
    Returns a plain list of ``"<cond>|<seed>|<qid>"`` keys, size <= min(n, len(scores)).
    """
    keys = sorted(scores.keys())  # deterministic base ordering before any RNG use
    if not keys or n <= 0:
        return []
    n = min(n, len(keys))
    frac = max(0.0, min(1.0, disagreement_frac))
    disagreement_keys = [k for k in keys
                          if scores[k].get("judge") is not None
                          and scores[k]["em"] != scores[k]["judge"]]

    rng = random.Random(seed)
    n_dis = min(len(disagreement_keys), round(n * frac))
    sampled_dis = rng.sample(disagreement_keys, n_dis) if n_dis else []

    remaining_pool = [k for k in keys if k not in sampled_dis]
    n_rest = n - len(sampled_dis)
    sampled_rest = rng.sample(remaining_pool, min(n_rest, len(remaining_pool))) if n_rest > 0 else []

    sample = sampled_dis + sampled_rest
    rng.shuffle(sample)
    return sample


def collect_calibration_texts(log_dir: str, keys) -> dict:
    """Re-read the EvalLogs and return the (question, reference, candidate) text
    a rater needs, for exactly the sampled ``keys`` (typically
    `sample_for_calibration`'s output). ``{key: {"question","reference","candidate"}}``.
    """
    wanted = set(keys)
    out: dict = {}
    if not wanted:
        return out
    for rec in _iter_eval_records(log_dir):
        key = f"{rec['cond']}|{rec['seed']}|{rec['qid']}"
        if key in wanted:
            out[key] = {"question": rec["question"], "reference": rec["reference"],
                        "candidate": rec["candidate"]}
    return out


_KAPPA_PE_EPS = 1e-12


def _kappa_pe(labels_a, labels_b) -> float:
    """Chance-expected agreement given each rater's marginal YES rate — the
    ``p_e`` term shared by `cohens_kappa` and `is_degenerate_pe`."""
    a = np.asarray(list(labels_a), dtype=bool)
    b = np.asarray(list(labels_b), dtype=bool)
    p_a1, p_b1 = float(np.mean(a)), float(np.mean(b))
    return p_a1 * p_b1 + (1 - p_a1) * (1 - p_b1)


def is_degenerate_pe(labels_a, labels_b) -> bool:
    """True when both raters gave every item the same single label (``p_e ~= 1``)
    — the homogeneous-sample case `cohens_kappa` special-cases to ``kappa = 1.0``.

    That ``kappa = 1.0`` is mathematically correct as the limiting value, but it
    is indistinguishable on its own from a genuine, informative "judge and rater
    independently agree on a diverse sample" result. Callers that report kappa
    (`rater_agreement_report`) should surface this flag alongside it so a reader
    can tell the two cases apart, instead of trusting a bare ``kappa: 1.0``.
    """
    a = np.asarray(list(labels_a), dtype=bool)
    b = np.asarray(list(labels_b), dtype=bool)
    if len(a) == 0:
        raise ValueError("is_degenerate_pe needs at least one labeled item")
    return _kappa_pe(a, b) >= 1.0 - _KAPPA_PE_EPS


def cohens_kappa(labels_a, labels_b) -> float:
    """Cohen's kappa for exactly two raters over parallel boolean label sequences.

    ``kappa = (p_o - p_e) / (1 - p_e)`` where ``p_o`` is observed agreement and
    ``p_e`` is chance-expected agreement given each rater's marginal YES rate.
    The degenerate case (``p_e == 1``, i.e. both raters gave every item the same
    single label) can only occur alongside perfect observed agreement, so it is
    defined as ``kappa = 1.0`` rather than dividing by zero — see `is_degenerate_pe`
    for the companion flag that tells this case apart from a genuine kappa=1.0.
    """
    a = np.asarray(list(labels_a), dtype=bool)
    b = np.asarray(list(labels_b), dtype=bool)
    if len(a) != len(b):
        raise ValueError("cohens_kappa needs two label sequences of equal length")
    if len(a) == 0:
        raise ValueError("cohens_kappa needs at least one labeled item")
    po = float(np.mean(a == b))
    pe = _kappa_pe(a, b)
    if pe >= 1.0 - _KAPPA_PE_EPS:
        return 1.0
    return (po - pe) / (1 - pe)


def bootstrap_kappa_ci(labels_a, labels_b, *, n_resamples: int = 2000,
                        alpha: float = 0.05, seed: int = 42) -> tuple[float, float]:
    """Bootstrap confidence interval (default 95%) for `cohens_kappa`, by
    resampling item pairs with replacement. Deterministic given `seed`."""
    a = np.asarray(list(labels_a), dtype=bool)
    b = np.asarray(list(labels_b), dtype=bool)
    n = len(a)
    if n == 0:
        raise ValueError("bootstrap_kappa_ci needs at least one labeled item")
    if n == 1:
        k = cohens_kappa(a, b)
        return k, k
    rng = np.random.default_rng(seed)
    boots = np.empty(n_resamples)
    for i in range(n_resamples):
        idx = rng.integers(0, n, size=n)
        boots[i] = cohens_kappa(a[idx], b[idx])
    lo = float(np.percentile(boots, 100 * alpha / 2))
    hi = float(np.percentile(boots, 100 * (1 - alpha / 2)))
    return lo, hi


def rater_majority_vote(rater_labels: list[list[bool]]) -> list:
    """Per-item majority vote across N parallel rater label lists.

    Generalizes past 2 raters by construction (N-rater vote counting), even
    though only the N=2 path is exercised today. Returns ``None`` for an item
    with no strict majority (only possible when N is even — e.g. 2 raters
    disagreeing 1-1) so callers can drop true ties rather than guess a label.
    """
    n_raters = len(rater_labels)
    if n_raters == 0:
        return []
    n_items = len(rater_labels[0])
    out = []
    for i in range(n_items):
        votes = [rater_labels[r][i] for r in range(n_raters)]
        yes = sum(1 for v in votes if v)
        no = n_raters - yes
        out.append(True if yes > no else False if no > yes else None)
    return out


def rater_agreement_report(judge_verdicts: list[bool], raters: list[list[bool]], *,
                            n_resamples: int = 2000, seed: int = 42) -> dict:
    """Compute both calibration agreement statistics over one sample (§M.4):

    - **judge-vs-rater-majority agreement** — the judge's own verdicts against the
      raters' majority vote (items with no strict majority are dropped and counted
      in ``n_dropped_ties``).
    - **rater-vs-rater agreement** — the natural-disagreement baseline the judge's
      own agreement should be read against, not a bare number in isolation.

    Both as Cohen's kappa (exactly 2 raters) with a bootstrap CI, plus a
    ``degenerate_pe`` flag (see `is_degenerate_pe`) — ``True`` when the two label
    sequences behind that kappa are a homogeneous sample (both raters gave every
    item the same single label), so a bare ``kappa: 1.0`` isn't mistaken for a
    genuine, informative agreement result on a diverse sample. Structured to
    generalize to Krippendorff's alpha for 3+ raters later (`rater_majority_vote`
    already N-rater-general); 3+ raters isn't implemented yet — only the 2-rater
    dry-run path is built/tested (tempdoc 624 §M.4/§T.3).
    """
    n_raters = len(raters)
    if n_raters < 2:
        raise ValueError("rater_agreement_report needs >= 2 independent raters")
    if n_raters >= 3:
        raise NotImplementedError(
            "3+ raters needs Krippendorff's alpha -- not implemented; only the 2-rater "
            "path is built/tested (tempdoc 624 §M.4/§T.3).")

    rater_a, rater_b = raters
    rvr_kappa = cohens_kappa(rater_a, rater_b)
    rvr_lo, rvr_hi = bootstrap_kappa_ci(rater_a, rater_b, n_resamples=n_resamples, seed=seed)
    rvr_degenerate = is_degenerate_pe(rater_a, rater_b)

    majority = rater_majority_vote(raters)
    pairs = [(j, m) for j, m in zip(judge_verdicts, majority) if m is not None]
    n_dropped_ties = len(majority) - len(pairs)
    if pairs:
        j_labels, m_labels = zip(*pairs)
        jvm_kappa = cohens_kappa(j_labels, m_labels)
        jvm_lo, jvm_hi = bootstrap_kappa_ci(j_labels, m_labels, n_resamples=n_resamples, seed=seed)
        jvm_degenerate = is_degenerate_pe(j_labels, m_labels)
    else:
        jvm_kappa = jvm_lo = jvm_hi = jvm_degenerate = None

    return {
        "n": len(judge_verdicts),
        "n_dropped_ties": n_dropped_ties,
        "judge_vs_rater_agreement": {"value": jvm_kappa, "ci_low": jvm_lo, "ci_high": jvm_hi,
                                      "degenerate_pe": jvm_degenerate},
        "rater_vs_rater_agreement": {"value": rvr_kappa, "ci_low": rvr_lo, "ci_high": rvr_hi,
                                      "degenerate_pe": rvr_degenerate},
    }


def _rater_substitute_token_overlap(question: str, reference: str, candidate: str) -> bool:
    """Agent-substitute rater A: token-Jaccard-overlap heuristic (>=50% of the
    reference's word set appears in the candidate). NOT an LLM call, and NOT
    `_judge_once`'s prompt/logic — an independent decision rule used ONLY for the
    mechanism-proving dry run (`run_calibration_dry_run`). Not a substitute for
    real human labeling.
    """
    ref_tokens = {t for t in reference.lower().split() if t}
    cand_tokens = {t for t in candidate.lower().split() if t}
    if not ref_tokens:
        return not cand_tokens
    overlap = len(ref_tokens & cand_tokens) / len(ref_tokens)
    return overlap >= 0.5


def _rater_substitute_containment(question: str, reference: str, candidate: str) -> bool:
    """Agent-substitute rater B: a SECOND, independently-designed heuristic —
    whether the reference's longest word appears verbatim in the candidate.
    Deliberately a different decision rule than rater A (anchor-containment vs.
    set-overlap), so the two "raters" aren't the same check under two names.
    Dry-run mechanism proof only — NOT a substitute for real human labeling.
    """
    ref = reference.lower().strip()
    cand = candidate.lower()
    if not ref:
        return not cand.strip()
    words = [w.strip(".,!?;:\"'()") for w in ref.split()]
    words = [w for w in words if len(w) > 2]
    if not words:
        return ref in cand
    anchor = max(words, key=len)
    return anchor in cand


# --- The column-level rater seam (tempdoc 674) -------------------------------
#
# A RATER is a column-producer: given the sampled calibration items (a
# ``{key: {"question","reference","candidate"}}`` dict), it returns a WHOLE
# label column ``{key: bool | None}`` in one call, owning HOW it produces that
# column. `None` means that rater abstains on that item.
#
# This is the seam that dissolves the interleave-vs-serial tension a per-item
# loop would hit: because a rater's `label_sample` call is its entire turn, a
# GPU-serial rater can load its model, label the whole sample, and unload as
# its natural unit of work -- no execution-topology flag is needed for
# correctness (tempdoc 674 §Design reach: "a source-agnostic aggregator needs
# a source seam at the granularity the scarcest shared resource dictates").
# `_HeuristicRater` and `_EndpointRater` below are thin adapters over logic
# that already existed (the dry-run heuristics, `external_grader`'s client);
# `LocalSerialRater` is the one genuinely new rater kind this seam unlocks.


class _HeuristicRater:
    """Column-producer wrapping a deterministic, non-LLM heuristic label function
    (the dry-run's agent-substitute mechanism proof). Never abstains."""

    def __init__(self, name: str, fn):
        self.name = name
        self._fn = fn

    def label_sample(self, texts: dict) -> dict:
        return {k: self._fn(t["question"], t["reference"], t["candidate"]) for k, t in texts.items()}


class _EndpointRater:
    """Column-producer wrapping a live, concurrently-addressable HTTP grader
    (`external_grader.GraderConfig`) — today's cross-family panel path,
    unchanged in behavior, just re-expressed as a column-producer."""

    def __init__(self, config, budget=None):
        self.name = config.name
        self._config = config
        self._budget = budget

    def label_sample(self, texts: dict) -> dict:
        from . import external_grader as eg

        return {
            k: eg.call_grader_dual_order(self._config, t["question"], t["reference"], t["candidate"],
                                          budget=self._budget)
            for k, t in texts.items()
        }


# Callback type for LocalSerialRater progress notification: (event_name, detail_dict) -> None.
# Adapted from `readiness.py`'s `_SnapshotCallback` shape (an optional typed callback backed
# by a stdlib `logging` default), NOT copied verbatim -- Probe C (tempdoc 674 §Pre-implementation
# confidence probes, remaining work) found `readiness.py`'s poll-count throttle (log every 15 of a
# 2s-interval multi-minute poll loop) doesn't transfer to grading a handful of items after one
# swap; this callback fires once per event (swap/restore boundaries + each labeled item) rather
# than being throttled, since the iteration count here is orders of magnitude smaller.
_ProgressCallback = Callable[[str, dict], None]


class LocalSerialRater:
    """Column-producer that serially swaps the locally-served chat model
    (tempdoc 674 §Long-term design) to grade a whole calibration column, then
    restores the original model — the local, $0, single-GPU-tenant grader kind
    the column seam exists to unlock.

    Drives the SAME two loopback Head-API routes an operator already uses to
    change the served model by hand — no new Java, no parallel swap authority
    (an eval-owned throwaway server was explicitly rejected as the primary path
    for exactly this reason, tempdoc 674 §Long-term design "Explicitly
    rejected"): ``POST /api/settings/v2`` to point ``llm.modelPath`` at
    `model_path`, then ``POST /api/ai/runtime/activate`` (reusing whichever
    variant is already active) to reload llama-server with it.

    One `label_sample` call is this rater's WHOLE turn: save the currently
    configured model path, swap in `model_path`, assert `/v1/models` now
    reports the intended model (a failed or no-op swap would otherwise
    silently grade two columns with the SAME model under a different name —
    the exact trap tempdoc 674 names in §Theorization-D), label every sampled
    item, then restore the original model path in a `finally` — so a crash
    mid-run leaves the dev-stack pointed at the ORIGINAL model, not a grader.

    Progress is reported via stdlib `logging` (INFO on swap/restore boundaries,
    DEBUG per labeled item) plus an optional `on_progress` callback for a caller
    that wants structured per-event data — this module imports no `click` and
    never will (the established `commands/*.py`-thin-wrapper / logic-module
    boundary, tempdoc 645); any CLI-visible text is `commands/utility.py`'s job,
    wiring `on_progress` to `click.echo`.

    `keep_loaded_between_raters`, if `True`, skips the restore-to-original step
    in `label_sample`'s `finally` — leaving this rater's model loaded for
    whatever runs next, rather than paying a second swap to return to the
    original model. Default `False` (today's safe behavior: every rater leaves
    the dev-stack on a known-good model even if the process crashes immediately
    after). This is a caller-opt-in speed/safety tradeoff (tempdoc 674's
    critical-analysis pass found the default costs ~2x the swaps the original
    design's own cost narrative assumed) — the LAST rater in a panel that opts
    into this is still responsible for restoring the original model itself; this
    class does not know whether it is last.
    """

    _POLL_INTERVAL_SEC = 2.0
    _ACTIVATE_TIMEOUT_SEC = 180.0

    def __init__(self, name: str, model_path: str, *,
                 backend_base_url: str = "http://127.0.0.1:33221", timeout_sec: float = 60.0,
                 keep_loaded_between_raters: bool = False,
                 on_progress: _ProgressCallback | None = None):
        self.name = name
        self._model_path = model_path
        self._base = backend_base_url.rstrip("/")
        self._timeout = timeout_sec
        self._keep_loaded = keep_loaded_between_raters
        self._on_progress = on_progress

    def _emit(self, event: str, **detail) -> None:
        log.info("LocalSerialRater[%s]: %s %s", self.name, event, detail)
        if self._on_progress is not None:
            self._on_progress(event, detail)

    def _get_settings(self) -> dict:
        r = httpx.get(f"{self._base}/api/settings/v2", timeout=10.0)
        r.raise_for_status()
        return r.json()

    def _set_model_path(self, path) -> None:
        settings_response = httpx.get(f"{self._base}/api/settings/v2", timeout=10.0)
        settings_response.raise_for_status()
        settings = settings_response.json()
        witness = settings["witness"]
        r = httpx.post(f"{self._base}/api/settings/v2", json={
            "llm": {"modelPath": path},
            "witness": witness,
            "operationKey": create_operation_key(),
        }, timeout=10.0)
        r.raise_for_status()

    def _current_variant_id(self) -> str:
        status = httpx.get(f"{self._base}/api/ai/runtime/status", timeout=10.0).json()
        variant_id = (status.get("active") or {}).get("activeVariantId")
        if not variant_id:
            raise RuntimeError(
                "LocalSerialRater: no active runtime variant to reactivate with -- activate a "
                "variant (e.g. via ai_activate) before swapping models.")
        return variant_id

    def _activate_and_wait(self, variant_id: str) -> None:
        r = httpx.post(f"{self._base}/api/ai/runtime/activate", json={"variantId": variant_id},
                        timeout=10.0)
        r.raise_for_status()
        deadline = time.monotonic() + self._ACTIVATE_TIMEOUT_SEC
        while time.monotonic() < deadline:
            status = httpx.get(f"{self._base}/api/ai/runtime/status", timeout=10.0).json()
            state = (status.get("activation") or {}).get("state")
            if state == "completed":
                return
            if state == "failed":
                message = (status.get("activation") or {}).get("message")
                raise RuntimeError(f"LocalSerialRater: activation of {variant_id!r} failed: {message}")
            time.sleep(self._POLL_INTERVAL_SEC)
        raise TimeoutError(
            f"LocalSerialRater: activation of {variant_id!r} did not complete within "
            f"{self._ACTIVATE_TIMEOUT_SEC}s")

    def _assert_serving(self, expected_path: str) -> None:
        # Defensive dual-shape parse -- mirrors `_probe_judge_model` (neither this
        # codebase nor llama-server's own `/v1/models` shape is pinned anywhere).
        served = _probe_judge_model(self._base)
        expected_name = Path(expected_path).name
        if served is None or expected_name not in served:
            raise RuntimeError(
                f"LocalSerialRater: served-model assertion failed after swap -- expected a model "
                f"matching {expected_name!r}, /v1/models reports {served!r}. Refusing to grade with "
                f"an unconfirmed model.")

    def label_sample(self, texts: dict) -> dict:
        from . import external_grader as eg

        variant_id = self._current_variant_id()
        original_path = (self._get_settings().get("llm") or {}).get("modelPath")
        try:
            self._emit("swap_start", model_path=self._model_path)
            swap_started_at = time.monotonic()
            self._set_model_path(self._model_path)
            self._activate_and_wait(variant_id)
            self._assert_serving(self._model_path)
            self._emit("swap_complete", model_path=self._model_path,
                       elapsed_sec=round(time.monotonic() - swap_started_at, 1))

            config = eg.GraderConfig(name=self.name, endpoint_url=f"{self._base}/v1/chat/completions",
                                      model=self.name, timeout_sec=self._timeout)
            column: dict = {}
            n_items = len(texts)
            for i, (k, t) in enumerate(texts.items(), start=1):
                column[k] = eg.call_grader_dual_order(config, t["question"], t["reference"],
                                                        t["candidate"])
                log.debug("LocalSerialRater[%s]: labeled item %d/%d", self.name, i, n_items)
                if self._on_progress is not None:
                    self._on_progress("item_labeled", {"index": i, "total": n_items, "key": k})
            return column
        finally:
            if self._keep_loaded:
                self._emit("restore_skipped", reason="keep_loaded_between_raters=True")
            else:
                self._emit("restore_start", model_path=original_path)
                try:
                    self._set_model_path(original_path)
                    self._activate_and_wait(variant_id)
                    self._emit("restore_complete", model_path=original_path)
                except Exception as restore_exc:
                    warnings.warn(
                        f"LocalSerialRater: failed to restore original model {original_path!r} after "
                        f"swap: {restore_exc}. The dev-stack may still be pointed at "
                        f"{self._model_path!r} -- restore manually via POST /api/settings/v2.",
                        RuntimeWarning, stacklevel=2)


# Empirically measured full stop->reload->self-test->restore cycle for one swap on the
# reference dev machine (RTX 4070, Qwen3.5-9B-Q4_K_M) -- tempdoc 674 §Pre-implementation
# confidence probes (remaining work), Probe E: two consecutive real swaps, ~21s each.
# A rough per-hardware/per-model estimate, never a guarantee -- printed as a decision aid,
# same discipline as `external_grader.estimate_cross_family_cost`'s own dollar figure.
_MEASURED_SWAP_SECONDS = 21.0

# Rule-of-thumb VRAM overhead for KV cache + CUDA runtime on top of a quantized GGUF's
# on-disk size, from published local-inference guidance (tempdoc 674 §Post-implementation
# polish, "External research pass"). A conservative estimate, not a hard limit.
_VRAM_OVERHEAD_FRACTION = 0.20


def estimate_local_serial_preflight(local_graders: list[tuple[str, str]], *,
                                     swaps_per_rater: int = 2,
                                     seconds_per_swap: float = _MEASURED_SWAP_SECONDS) -> dict:
    """Pure, standalone pre-flight report for a panel's `LocalSerialRater` graders
    (tempdoc 674 remaining-work slice) -- the local-serial-specific sibling of
    `external_grader.estimate_cross_family_cost`, which stays dollar-cost-only and
    applies uniformly to every grader kind. This one covers the axes dollars don't:
    file size, best-effort architecture/capability signal, VRAM fit, and swap-time.

    `local_graders` is `[(name, model_path), ...]` -- the same raw config a caller
    already has before constructing `LocalSerialRater` instances, so this function
    needs no rater objects. Meant to be printed and confirmed BEFORE any real swap
    is made, mirroring `estimate_cross_family_cost`'s own "print before you commit"
    discipline -- both should appear in the same dry-run block.

    Never raises on a missing/unparseable GGUF field or an unavailable GPU probe --
    every signal here is advisory (`general.size_label` is spec-optional; VRAM
    detection can fail on a non-NVIDIA machine) and must fail open, not block a
    caller whose model this function simply can't fully characterize.

    `swaps_per_rater` defaults to 2 (swap-in + restore, `LocalSerialRater`'s
    default `keep_loaded_between_raters=False` behavior); pass 1 if the caller
    knows it will keep models loaded between raters instead.
    """
    from . import gguf_probe
    from .env_fingerprint import probe_gpu_vram

    gpu = probe_gpu_vram()
    vram_total_bytes = (gpu.get("mem_total_mb") or 0) * 1024 * 1024 if gpu.get("available") else None

    per_grader = []
    for name, model_path in local_graders:
        entry = {"name": name, "model_path": model_path, "size_bytes": None,
                  "architecture": None, "size_label": None, "vram_fit": "unknown"}
        try:
            info = gguf_probe.probe_gguf(model_path)
        except Exception as e:
            entry["error"] = str(e)
        else:
            entry["size_bytes"] = info.size_bytes
            entry["architecture"] = info.architecture
            entry["size_label"] = info.size_label
            if vram_total_bytes is not None:
                needed = info.size_bytes * (1 + _VRAM_OVERHEAD_FRACTION)
                entry["vram_fit"] = "likely_fits" if needed <= vram_total_bytes else "likely_too_large"
                entry["estimated_vram_bytes"] = round(needed)
        per_grader.append(entry)

    total_swaps = len(local_graders) * swaps_per_rater
    return {
        "n_local_graders": len(local_graders),
        "gpu_available": bool(gpu.get("available")),
        "vram_total_bytes": vram_total_bytes,
        "per_grader": per_grader,
        "estimated_swap_count": total_swaps,
        "estimated_time_sec": round(total_swaps * seconds_per_swap),
        "note": "advisory only -- size_label may be absent, VRAM/time figures are rough estimates, "
                "not guarantees (tempdoc 674).",
    }


def run_calibration(log_dir: str, overlay: dict, raters: list, *, n: int = 40, seed: int = 0,
                     rater_kind: str) -> dict:
    """Unified calibration orchestrator (tempdoc 674) — the column-level rater
    seam's collection loop. `run_calibration_dry_run` and
    `run_cross_family_calibration` are thin wrappers over this function,
    preserved for their existing public signatures/tests; this is where new
    rater kinds (see `LocalSerialRater`) actually plug in.

    Draws a stratified sample from `overlay["scores"]` (`sample_for_calibration`,
    oversampling the EM-disagreement stratum) and fetches its text
    (`collect_calibration_texts`), then collects each rater's WHOLE label
    column in turn (sequential, not interleaved-by-item — see the column-seam
    note above `_HeuristicRater`) before computing agreement via the untouched
    `rater_agreement_report`.

    `raters` must be >= 2 column-producers (`rater_agreement_report`'s 2-rater
    floor — 3+ raises `NotImplementedError` there). If any rater abstains
    (`None`) on an item, the WHOLE item is dropped for every rater (not just
    that rater's vote) — counted in the returned `n_abstained`, never silently
    imputed. `rater_kind` is stamped unconditionally, never gated on any caller
    input, so the emitted number can never be mistaken for a different kind of
    calibration than it is.
    """
    if len(raters) < 2:
        raise ValueError("run_calibration needs >= 2 independent raters "
                          "(rater_agreement_report's 2-rater floor)")

    scores = overlay.get("scores", {})
    sample_keys = sample_for_calibration(scores, n=n, seed=seed)
    texts = collect_calibration_texts(log_dir, sample_keys) if sample_keys else {}
    available_texts = {k: texts[k] for k in sample_keys if k in texts}

    columns = [r.label_sample(available_texts) for r in raters]  # one rater's whole turn at a time

    rater_labels: list[list[bool]] = [[] for _ in raters]
    judge_labels, used_keys = [], []
    n_abstained = 0
    for k in sample_keys:
        if k not in available_texts:
            continue  # sample key not found in the logs (shouldn't happen) -- skip defensively
        verdicts = [col.get(k) for col in columns]
        if any(v is None for v in verdicts):
            n_abstained += 1  # >=1 rater abstained on this item -- drop it, don't guess
            continue
        for i, v in enumerate(verdicts):
            rater_labels[i].append(v)
        judge_labels.append(bool(scores[k]["final"]))
        used_keys.append(k)

    base = {
        "rater_kind": rater_kind,
        "n": len(used_keys),
        "sample_qids": used_keys,
        "n_abstained": n_abstained,
        "raters": [r.name for r in raters],
    }
    if len(used_keys) < 2:
        return {
            **base,
            "judge_vs_rater_agreement": {"value": None, "ci_low": None, "ci_high": None,
                                          "degenerate_pe": None},
            "rater_vs_rater_agreement": {"value": None, "ci_low": None, "ci_high": None,
                                          "degenerate_pe": None},
            "note": "sample has fewer than 2 usable items -- no agreement statistic computed",
        }

    report = rater_agreement_report(judge_labels, rater_labels, seed=seed)
    return {
        **base,
        "n_dropped_ties": report["n_dropped_ties"],
        "judge_vs_rater_agreement": report["judge_vs_rater_agreement"],
        "rater_vs_rater_agreement": report["rater_vs_rater_agreement"],
    }


def run_calibration_dry_run(log_dir: str, overlay: dict, *, n: int = 40, seed: int = 0) -> dict:
    """Mechanism-proving dry run of the human-calibration pipeline (§M.4/§T.3).

    Scores the sampled items with TWO INDEPENDENT AGENT-SUBSTITUTE heuristic
    raters (deterministic, non-LLM, mutually distinct — see
    `_rater_substitute_token_overlap` / `_rater_substitute_containment`), via
    `run_calibration`. Heuristic raters never abstain, so `n_abstained`/`raters`
    are stripped to preserve this function's original return shape.

    **This proves the sampling + agreement machinery is wired correctly
    end-to-end. It does NOT produce a usable calibration number** — genuine
    human raters are not available in this fully-autonomous pipeline. The
    returned ``rater_kind`` field says so explicitly; treat any kappa here as a
    mechanism check, never as a validated judge-accuracy figure.
    """
    raters = [
        _HeuristicRater("agent-substitute-token-overlap", _rater_substitute_token_overlap),
        _HeuristicRater("agent-substitute-containment", _rater_substitute_containment),
    ]
    result = run_calibration(log_dir, overlay, raters, n=n, seed=seed,
                              rater_kind="agent-substitute, NOT human")
    result.pop("n_abstained", None)
    result.pop("raters", None)
    return result


def attach_human_calibration(overlay: dict, log_dir: str, *, n: int = 40, seed: int = 0) -> dict:
    """Mutate + return `overlay` with a `human_calibration` block attached
    (additive — `judge_identity`/`stats`/`scores` are untouched). See
    `run_calibration_dry_run` for what is actually computed today (a mechanism
    dry run with agent-substitute raters, not real human calibration).
    """
    overlay["human_calibration"] = run_calibration_dry_run(log_dir, overlay, n=n, seed=seed)
    return overlay


# --- Cross-family LLM grader panel calibration (tempdoc 624 §M.9 "U-Founder-4
# revised") -------------------------------------------------------------------


def run_cross_family_calibration(log_dir: str, overlay: dict, *, graders, n: int = 40,
                                  seed: int = 0, max_calls: int | None = None) -> dict:
    """Cross-family LLM grader panel calibration — the founder-decided REPLACEMENT
    for bulk human labeling (tempdoc 624 §M.9 "U-Founder-4 revised").

    `graders` must be a list of >= 2 column-producers (`_EndpointRater` /
    `LocalSerialRater` instances, or raw `external_grader.GraderConfig` values
    for backward compatibility — see below) from DIFFERENT provider families
    than BOTH the agent-under-test and the local judge — that cross-family
    property is a caller responsibility (this function has no way to
    mechanically verify provider identity), matching the founder decision's own
    accepted honesty limit: cross-family reduces but does NOT eliminate
    grader-correlation.

    `max_calls`, if given, is a hard cap on total external HTTP calls any
    `_EndpointRater` in `graders` may make (see `external_grader.GraderCallBudget`)
    — raises `external_grader.GraderCallBudgetExceeded` before the call that
    would exceed it is ever made. Pass the value already confirmed via
    `external_grader.estimate_cross_family_cost`. (Ignored by `LocalSerialRater`
    graders, which make $0 local calls.)

    Thin wrapper over `run_calibration` (tempdoc 674): raw `GraderConfig` values
    are auto-wrapped in `_EndpointRater` for backward compatibility with
    existing callers; `LocalSerialRater` instances pass through unchanged. The
    `"graders"` key name (rather than `run_calibration`'s generic `"raters"`) is
    preserved for this function's existing callers/tests.
    """
    from . import external_grader as eg

    if len(graders) < 2:
        raise ValueError(
            "run_cross_family_calibration needs >= 2 graders "
            "(rater_agreement_report's 2-rater floor)")

    budget = eg.GraderCallBudget(max_calls) if max_calls is not None else None
    raters = [
        g if hasattr(g, "label_sample") else _EndpointRater(g, budget)
        for g in graders
    ]
    result = run_calibration(log_dir, overlay, raters, n=n, seed=seed,
                              rater_kind="cross-family-llm, NOT human")
    result["graders"] = result.pop("raters")
    return result


def write_overlay(log_dir: str, overlay: dict) -> str:
    """Persist the overlay dict verbatim as ``judge-overlay.json``.

    Generic over shape — any additive key (e.g. `human_calibration`, see
    `attach_human_calibration`) is written the same way as `judge_identity` /
    `stats` / `scores`; nothing here special-cases individual fields.
    """
    path = Path(log_dir) / "judge-overlay.json"
    path.write_text(json.dumps(overlay, indent=2), encoding="utf-8")
    return str(path)
