"""NDJSON metric stream reader (tempdoc 400 LR3-b).

Consumes the two metric files produced by the telemetry stack at
``<dataDir>/telemetry/metrics.ndjson`` (application telemetry) and
``<dataDir>/telemetry/metrics-worker.ndjson`` (index telemetry in the same Engine). Provides
a single merged iterator ordered by timestamp so Layer 4 projections
(rate-based timeline, stall tagging, stratified metrics, etc.) can
consume both streams as one logical time-series.

Each line is a self-contained JSON object with a ``t`` ISO-8601 timestamp
and a ``source`` field that this module adds (``"head"`` / ``"worker"``)
so downstream consumers can split or group by origin without re-parsing
the filename.
"""

from __future__ import annotations

import json
import logging
from datetime import datetime
from pathlib import Path
from typing import Iterator

log = logging.getLogger(__name__)


def _parse_ts(raw: str | None) -> datetime | None:
    """Parse the ISO-8601 ``t`` field; returns ``None`` if missing / unparseable."""
    if not raw:
        return None
    try:
        # NDJSON uses trailing Z for UTC; Python 3.11+ handles this, earlier
        # versions may need the Z→+00:00 shim.
        if raw.endswith("Z"):
            raw = raw[:-1] + "+00:00"
        return datetime.fromisoformat(raw)
    except ValueError:
        return None


def _iter_file(path: Path, source: str) -> Iterator[dict]:
    """Yield parsed JSON records from a metrics NDJSON file, tagged with source.

    Skips missing files (returns empty), unparseable lines (logs + skips), and
    records without a parseable ``t`` (keeps them but with ``_ts`` = None).
    """
    if not path.is_file():
        log.debug("Metrics file not present: %s", path)
        return
    try:
        with path.open("r", encoding="utf-8") as f:
            for line_no, line in enumerate(f, start=1):
                line = line.strip()
                if not line:
                    continue
                try:
                    record = json.loads(line)
                except json.JSONDecodeError:
                    log.debug("Skipping unparseable line %s:%d", path.name, line_no)
                    continue
                record["source"] = source
                record["_ts"] = _parse_ts(record.get("t"))
                yield record
    except OSError as e:
        log.debug("Failed to read metrics file %s: %s", path, e)


def read_merged(
    data_dir: Path,
    *,
    since: datetime | None = None,
    until: datetime | None = None,
) -> list[dict]:
    """Read both NDJSON files from ``<data_dir>/telemetry/`` merged by timestamp.

    Returns a list sorted by ``_ts`` (records with unparseable timestamps
    sort first). Applies optional [since, until) window filtering (both
    bounds inclusive on the lower side, exclusive on the upper side).
    Missing files are not errors.
    """
    telemetry_dir = data_dir / "telemetry"
    head_file = telemetry_dir / "metrics.ndjson"
    worker_file = telemetry_dir / "metrics-worker.ndjson"

    records: list[dict] = []
    records.extend(_iter_file(head_file, "head"))
    records.extend(_iter_file(worker_file, "worker"))

    if since is not None or until is not None:
        filtered = []
        for r in records:
            ts = r.get("_ts")
            if ts is None:
                # Unparseable timestamps: include unconditionally so downstream
                # consumers can see them. Projections can filter further.
                filtered.append(r)
                continue
            if since is not None and ts < since:
                continue
            if until is not None and ts >= until:
                continue
            filtered.append(r)
        records = filtered

    # Stable sort: unparseable ts records sort first (datetime.min sentinel).
    records.sort(key=lambda r: r.get("_ts") or datetime.min)
    return records


def iter_merged(
    data_dir: Path,
    *,
    since: datetime | None = None,
    until: datetime | None = None,
) -> Iterator[dict]:
    """Streaming variant of :func:`read_merged`. Yields records in timestamp order.

    Internally delegates to :func:`read_merged` (needs the full set to sort
    by timestamp); useful when callers prefer iteration over an in-memory
    list.
    """
    yield from read_merged(data_dir, since=since, until=until)
