"""Readable reports (S12/S17/S19): a one-line outcome, then the evidence behind it."""

from __future__ import annotations

from typing import Mapping
import json
import math
import numpy as np

from .compare import Comparison, syntax_refused
from .types import Collection, GroupVerdict, Outcome, RunHeader


def compact(text: str, limit: int = 1000) -> str:
    """Bound terminal diagnostics; the report/JSON retains complete evidence."""
    text = " ".join(text.split())
    return text if len(text) <= limit else text[:limit] + "… (full details in report/JSON)"


def outcome_line(cmp: Comparison) -> str:
    v = cmp.verdict
    head = f"{v.outcome.value} {cmp.collection.name}"
    if v.outcome is Outcome.INVALID:
        return compact(f"{head}: {v.reasons[0]}") + (f" (+{len(v.reasons) - 1} more)" if len(v.reasons) > 1 else "")
    g = _headline_group(v.groups)
    line = (f"{head} {g.metric} {g.baseline_mean:.4f} -> {g.candidate_mean:.4f} "
            f"({g.mean_delta:+.4f}, 95% [{g.ci_low:+.4f}, {g.ci_high:+.4f}]) "
            f"n={g.n}; groups {sum(1 for x in v.groups if x.outcome in ('non-inferior', 'improved'))}"
            f"/{len(v.groups)} ok; unjudged@10 {cmp.unjudged['baseline_share']:.1%} -> "
            f"{cmp.unjudged['candidate_share']:.1%}")
    if v.reasons:
        line += f"; {v.reasons[0]}"
    if cmp.judgment_requests:
        rows = list(judgment_rows(cmp))
        line += f"; needs judgment: {len(rows)} query/document/arm rows; " + "; ".join(
            f"qid={qid} doc_id={doc} arm={arm}" for qid, doc, arm in rows[:3])
        if len(rows) > 3:
            line += f" (+{len(rows)-3} more; full list in report/JSON)"
    if cmp.candidate:
        line += "; " + timing_text(search_timings(cmp.candidate.results))
    return compact(line)


def judgment_rows(cmp: Comparison):
    """Keep arm provenance even when the same pair appears in both rankings."""
    arms = {arm: cmp.results(arm) for arm in ('baseline', 'candidate')}
    for qid, doc in cmp.judgment_requests:
        for arm, results in arms.items():
            if qid in results and doc in results[qid].ranked[:10]:
                yield qid, doc, arm


def _headline_group(groups: tuple[GroupVerdict, ...]) -> GroupVerdict:
    return next((g for g in groups if g.group == "all"), groups[0])


def score_line(c: Collection, aggregate: Mapping[str, float], unjudged_share: float, n: int,
               results=()) -> str:
    metrics = " ".join(f"{k} {v:.4f}" for k, v in aggregate.items())
    return f"VALID {c.name} n={n} {metrics} unjudged@10 {unjudged_share:.1%}; {timing_text(search_timings(results))}"


def _identity_rows(h: RunHeader) -> dict[str, str]:
    pop = h.index_population
    return {"commit": h.commit + (" (dirty)" if h.dirty_hash else ""),
            "collection version": h.collection_version[:16],
            "index key": h.index_key,
            "index population": f"{pop.get('count')} docs, manifest "
                                f"{str(pop.get('manifest_sha256'))[:12]}, content "
                                f"{str(pop.get('content_sha256'))[:12]}",
            "index build": h.index_build + (f" (cache key {h.index_cache_key})"
                                            if h.index_cache_key else ""),
            **{f"index state: {k}": str(v) for k, v in sorted(h.index_state.items())},
            "request config": h.request_config_sha256[:12],
            "expected legs / reranker": f"{'+'.join(h.expected_legs)} / {h.expected_reranker}",
            **{f"budget: {k}": str(v) for k, v in sorted(h.budget.items())},
            **{f"model: {k}": _short(v) for k, v in sorted(h.model_hashes.items())},
            "hardware": ", ".join(f"{k}={v}" for k, v in sorted(h.hardware.items())) or "-",
            "started / ended": f"{h.started} / {h.ended}"}


def _short(value, width: int = 40) -> str:
    """Long values (paths, digests) keep their informative tail."""
    text = str(value)
    return text if len(text) <= width else "…" + text[-(width - 1):]


def _fmt_doc(c: Collection, judgments: Mapping[str, int], doc_id: str) -> str:
    grade = judgments.get(doc_id)
    label = "unjudged" if grade is None else f"grade {grade}"
    return f"`{doc_id}` {c.documents.get(doc_id, '?')} ({label})"


def render(cmp: Comparison) -> str:
    c, v = cmp.collection, cmp.verdict
    out = [f"# Search quality: {c.name}", "", f"**{outcome_line(cmp)}**", "",
           f"Exit code {v.exit_code}. Collection version `{c.version[:16]}`, "
           f"{len(c.queries)} queries, {len(c.documents)} documents; numpy {v.numpy_version}.",
           ""]
    if cmp.candidate and cmp.candidate.header.run_kind == 'frozen-index':
        h = cmp.candidate.header
        out += [f"Frozen-index comparison: current source `{h.commit}`, runtime snapshot "
                f"`{h.runtime_identity['distribution']}`; original index-build snapshot "
                f"`{h.identity['distribution']}`, index key `{h.index_key}`, historical baseline "
                f"run SHA-256 `{h.frozen_index['baseline_run_sha256']}`.", "",
                "Payload hashes bind the present cached index and this copy; no complete historical "
                "payload inventory was recorded at baseline time.", ""]
    if v.reasons:
        out += ["## Reasons", ""] + [f"- {r}" for r in v.reasons] + [""]
    for arm, record in (("baseline", cmp.baseline), ("candidate", cmp.candidate)):
        if record:
            ids = [r.qid for r in record.results if syntax_refused(r)]
            out += [f"{arm} search time: {timing_text(search_timings(record.results))}", ""]
            out += [f"{arm} syntax refusals ({len(ids)}): " + (", ".join(ids) or "none"), ""]
    if cmp.baseline and cmp.candidate:
        b, n = _identity_rows(cmp.baseline.header), _identity_rows(cmp.candidate.header)
        out += ["## Effective inputs and index state", "", "| | baseline | candidate | |",
                "|---|---|---|---|"]
        for k in list(dict.fromkeys([*b, *n])):
            mark = "" if b.get(k) == n.get(k) else "differs"
            out.append(f"| {k} | {b.get(k, '-')} | {n.get(k, '-')} | {mark} |")
        out.append("")
        if cmp.confounds:
            out += ["Uncontrolled differences (comparison is INCONCLUSIVE):", ""]
            out += [f"- {x}" for x in cmp.confounds] + [""]
        for k, ids in cmp.affected.items():
            out += [f"Affected queries ({k}, {len(ids)}): " + ", ".join(ids), ""]
        if cmp.notes:
            out += [f"- note: {x}" for x in cmp.notes] + [""]
    if v.groups:
        out += ["## Groups", "",
                "| group | n | baseline | candidate | delta | 95% interval | d_z | p | p (Holm) | margin | outcome |",
                "|---|---|---|---|---|---|---|---|---|---|---|"]
        for g in v.groups:
            dz = "-" if g.effect_size_dz is None else f"{g.effect_size_dz:+.2f}"
            out.append(f"| {g.group} | {g.n} | {g.baseline_mean:.4f} | {g.candidate_mean:.4f} | "
                       f"{g.mean_delta:+.4f} | [{g.ci_low:+.4f}, {g.ci_high:+.4f}] | {dz} | "
                       f"{g.p_value:.4f} | {g.p_adjusted:.4f} | {g.margin} | {g.outcome} |")
        out.append("")
    if cmp.unjudged:
        u = cmp.unjudged
        out += ["## Unjudged results", "",
                f"Share of top-10 results without a judgment: baseline {u['baseline_share']:.1%}, "
                f"candidate {u['candidate_share']:.1%}. Unjudged pairs in only one arm's top 10: "
                f"{u['differing_pairs']} ({u['differing_share']:.1%}; "
                + (f"threshold {u['threshold']:.0%})." if u["threshold"] is not None else
                   "official judgments: unjudged counts as non-relevant, information only)."),
                ""]
        if cmp.judgment_requests:
            out += [f"{len(cmp.judgment_requests)} pairs go to judgment with the collection's "
                    "rubric; both arms are then rescored against the new label version.", ""]
            out += ["| query id | document id | arm |", "|---|---|---|"]
            out += [f"| {qid} | {doc} | {arm} |" for qid, doc, arm in judgment_rows(cmp)]
            out.append("")
    if cmp.per_query:
        out += _most_changed(cmp)
    return "\n".join(out).rstrip() + "\n"


def _most_changed(cmp: Comparison) -> list[str]:
    c, cfg = cmp.collection, cmp.config
    base, cand = cmp.results("baseline"), cmp.results("candidate")
    ranked = sorted(cmp.per_query.items(), key=lambda kv: (-abs(kv[1]["delta"]), kv[0]))
    ranked = [kv for kv in ranked if kv[1]["delta"] != 0][: cfg.most_changed]
    out = [f"## Most changed queries ({cfg.metric})", ""]
    if not ranked:
        return out + ["No query changed.", ""]
    for qid, row in ranked:
        j = c.judgments.get(qid, {})
        out += [f"### {qid} ({row['group']}): {row['baseline'][cfg.metric]:.4f} -> "
                f"{row['candidate'][cfg.metric]:.4f} ({row['delta']:+.4f})", "",
                f"> {c.queries[qid].text}", ""]
        for arm, res in (("baseline", base[qid]), ("candidate", cand[qid])):
            out.append(f"{arm} top {cfg.top}:")
            out += [f"{i}. {_fmt_doc(c, j, d)}" for i, d in enumerate(res.ranked[: cfg.top], 1)]
            if not res.ranked:
                out.append("(no results)")
            out.append("")
    return out


def search_timings(results):
    values = [r.timings.get('elapsed_seconds') for r in results]
    if not values or not all(isinstance(v, (int, float)) and not isinstance(v, bool)
                             and math.isfinite(v) and v >= 0 for v in values):
        return {'p50_seconds': None, 'p95_seconds': None}
    p50, p95 = np.percentile(values, [50, 95])
    return {'p50_seconds': float(p50), 'p95_seconds': float(p95)}


def timing_text(row):
    def value(key):
        number = row.get(key)
        return 'unavailable' if number is None else f'{number:.3f}s'
    return f"p50 {value('p50_seconds')}, p95 {value('p95_seconds')}"


def run_measurements(c, record, groups=()):
    from . import scoring, sensitivity
    return {**search_timings(record.results), 'queries': len(record.results),
            'nDCG@10': scoring.aggregate(scoring.score_results(c.judgments, record.results))['nDCG@10'],
            'counts': sensitivity.counts(c, record.results, groups)}


def measurement_text(name, row, is_sensitivity=False):
    from .sensitivity import DEADLINE_NOTE
    text = (f"{name}: n={row['queries']}, nDCG@10 {row['nDCG@10']:.4f}; {timing_text(row)}")
    if is_sensitivity:
        text += '\n\nReranker counts by collection and fixed query group (each with its query denominator):\n\n```json\n'
        text += json.dumps(row['counts'], indent=2) + '\n```\n\n' + DEADLINE_NOTE
    return text
