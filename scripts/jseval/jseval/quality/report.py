"""Readable reports (S12/S17/S19): a one-line outcome, then the evidence behind it."""

from __future__ import annotations

from typing import Mapping

from .compare import Comparison
from .types import Collection, GroupVerdict, Outcome, RunHeader


def outcome_line(cmp: Comparison) -> str:
    v = cmp.verdict
    head = f"{v.outcome.value} {cmp.collection.name}"
    if v.outcome is Outcome.INVALID:
        return f"{head}: {v.reasons[0]}" + (f" (+{len(v.reasons) - 1} more)" if len(v.reasons) > 1 else "")
    g = _headline_group(v.groups)
    line = (f"{head} {g.metric} {g.baseline_mean:.4f} -> {g.candidate_mean:.4f} "
            f"({g.mean_delta:+.4f}, 95% [{g.ci_low:+.4f}, {g.ci_high:+.4f}]) "
            f"n={g.n}; groups {sum(1 for x in v.groups if x.outcome in ('non-inferior', 'improved'))}"
            f"/{len(v.groups)} ok; unjudged@10 {cmp.unjudged['baseline_share']:.1%} -> "
            f"{cmp.unjudged['candidate_share']:.1%}")
    if v.reasons:
        line += f"; {v.reasons[0]}"
    return line


def _headline_group(groups: tuple[GroupVerdict, ...]) -> GroupVerdict:
    return next((g for g in groups if g.group == "all"), groups[0])


def score_line(c: Collection, aggregate: Mapping[str, float], unjudged_share: float, n: int) -> str:
    metrics = " ".join(f"{k} {v:.4f}" for k, v in aggregate.items())
    return f"VALID {c.name} n={n} {metrics} unjudged@10 {unjudged_share:.1%}"


def _identity_rows(h: RunHeader) -> dict[str, str]:
    pop = h.index_population
    return {"commit": h.commit + (" (dirty)" if h.dirty_hash else ""),
            "collection version": h.collection_version[:16],
            "index key": h.index_key,
            "index population": f"{pop.get('count')} docs, manifest {str(pop.get('manifest_sha256'))[:12]}",
            **{f"index state: {k}": str(v) for k, v in sorted(h.index_state.items())},
            "request config": h.request_config_sha256[:12],
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
           f"{len(c.queries)} queries, {len(c.documents)} documents.", ""]
    if v.reasons:
        out += ["## Reasons", ""] + [f"- {r}" for r in v.reasons] + [""]
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
                f"{u['differing_pairs']} ({u['differing_share']:.1%}; threshold {u['threshold']:.0%}).",
                ""]
        if cmp.judgment_requests:
            out += [f"{len(cmp.judgment_requests)} pairs go to judgment with the collection's "
                    "rubric; both arms are then rescored against the new label version.", ""]
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
