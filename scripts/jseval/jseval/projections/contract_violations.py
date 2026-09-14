"""Contract-violation projection (tempdoc 400 LR6-c).

Consumes ``contract.violation`` span events from a run's
``traces.ndjson`` and aggregates by ``(contract.tempdoc, contract.tier)``
so drift in violation rate becomes visible in Layer 4 outputs.

Until the deferred contract tiers land (``@SampleContract`` +
``@BootContract``), no ``contract.violation`` events are emitted in
production and the projection returns an empty aggregate. That is the
intended shape: when the runtime tiers ship, the projection starts
returning non-empty data with zero downstream changes.

Event shape (agreed contract with LR6-a runtime tiers when they land):

.. code-block:: json

    {
      "trace_id": "...",
      "span_id": "...",
      "name": "<parent span name>",
      "events": [
        {
          "name": "contract.violation",
          "attrs": {
            "contract.tempdoc": "397 §14.25",
            "contract.tier": "@SampleContract",
            "contract.description": "pure-encoder denylist"
          }
        }
      ]
    }
"""

from __future__ import annotations

import json
import logging
from collections import Counter
from pathlib import Path
from typing import Iterable

log = logging.getLogger(__name__)

CONTRACT_VIOLATION_EVENT_NAME = "contract.violation"


def _iter_spans(traces_path: Path) -> Iterable[dict]:
    """Yield each span record from the NDJSON file. Skips unparseable lines."""
    if not traces_path.is_file():
        return
    try:
        with traces_path.open("r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    yield json.loads(line)
                except json.JSONDecodeError:
                    log.debug("skipping unparseable line in %s", traces_path.name)
    except OSError as e:
        log.debug("failed to read %s: %s", traces_path, e)


def aggregate(traces_path: Path) -> dict:
    """Aggregate contract.violation events into counts by (tempdoc, tier).

    Returns a dict with:

    - ``total_violations``: int — total count across all events.
    - ``by_tempdoc``: dict[str, int] — count per ``contract.tempdoc``.
    - ``by_tier``: dict[str, int] — count per ``contract.tier``.
    - ``by_tempdoc_and_tier``: dict[str, int] — count per
      ``"tempdoc || tier"`` composite key, for 2-dim drift detection.
    - ``samples``: list of up to 10 full violation records (first-seen
      order) — useful for spot-checking without loading the full trace.

    Sources merged:

    - Primary: the caller-provided ``traces_path`` (typically
      ``<run_dir>/traces.ndjson``). Missing file is not an error.
    - **Secondary (Phase 6 / 6.1):** a sibling
      ``projections/_errors.ndjson`` emitted by
      :func:`jseval.projections.base.run_all` on projection failure.
      This is the self-feed path — projection-dispatcher failures
      surface through the same aggregate the nightly gate already
      checks, so an always-failing projection no longer hides in the
      engine log.
    """
    total = 0
    by_tempdoc: Counter[str] = Counter()
    by_tier: Counter[str] = Counter()
    by_both: Counter[str] = Counter()
    samples: list[dict] = []

    paths_to_scan: list[Path] = [traces_path]
    if traces_path.parent.name != "projections":
        errors_path = traces_path.parent / "projections" / "_errors.ndjson"
        if errors_path.is_file():
            paths_to_scan.append(errors_path)

    for path in paths_to_scan:
        for span in _iter_spans(path):
            events = span.get("events") or []
            for ev in events:
                if ev.get("name") != CONTRACT_VIOLATION_EVENT_NAME:
                    continue
                attrs = ev.get("attrs") or {}
                tempdoc = attrs.get("contract.tempdoc") or "<unknown>"
                tier = attrs.get("contract.tier") or "<unknown>"
                total += 1
                by_tempdoc[tempdoc] += 1
                by_tier[tier] += 1
                by_both[f"{tempdoc} || {tier}"] += 1
                if len(samples) < 10:
                    samples.append({
                        "trace_id": span.get("trace_id"),
                        "span_id": span.get("span_id"),
                        "span_name": span.get("name"),
                        "attrs": attrs,
                    })

    return {
        "total_violations": total,
        "by_tempdoc": dict(by_tempdoc),
        "by_tier": dict(by_tier),
        "by_tempdoc_and_tier": dict(by_both),
        "samples": samples,
    }


def write_report(aggregate_result: dict, run_dir: Path) -> Path:
    """Write the aggregate as ``contract-violations.json`` in the run dir."""
    path = run_dir / "contract-violations.json"
    path.write_text(
        json.dumps(aggregate_result, indent=2, sort_keys=True, ensure_ascii=False),
        encoding="utf-8",
    )
    return path


# LR4-a registration — wraps :func:`aggregate` as a Projection so the
# Phase-3 dispatcher invokes it alongside the LR4-b..g projections.
# ``write_report`` remains available as the standalone Phase-1 entry
# point for callers that want the report outside ``run_dir/projections/``.
from .base import Projection  # noqa: E402 - intentional late import

PROJECTION = Projection(
    name="contract_violations",
    schema_version=1,
    description="Aggregate contract.violation span events (LR6-c).",
    produce=lambda run_dir: aggregate(run_dir / "traces.ndjson"),
)
