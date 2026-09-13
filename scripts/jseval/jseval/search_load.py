"""Background search load driven against the Head during ingestion (tempdoc 885).

Lane C's throughput comparison needs the indexing pipeline measured *while foreground search
traffic is present*. Before item 3 that was because `POST /api/knowledge/search` wrote the
Worker's MMF activity slot and the slot made `IndexingLoop` breath-hold (pause outright). Since
item 3 the search RPC itself is the signal: it increments the Worker's in-flight foreground
gauge for its duration, which drives the indexing duty cycle (`IndexingPacing`). Either way this
module's job is the same — hold real foreground traffic against the Worker while ingest runs.

Two modes:

* ``qpm`` — N queries per minute, evenly spaced (query ``i`` is due at ``start + i * 60/N``).
* ``continuous`` — back-to-back, one request in flight at a time.

The loop runs on a daemon thread for the duration of ingest + readiness/pipeline wait and is
purely additive: it never touches the eval metrics, and with no option passed no thread is
started and no ``search_load`` block is written.
"""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timezone

import httpx

from .retriever import DEFAULT_SEARCH_TIMEOUT_SEC

log = logging.getLogger(__name__)

#: Per-request timeout. A slower response is counted as an error rather than retried —
#: retrying would distort the offered load the measurement is about.
REQUEST_TIMEOUT_SEC = DEFAULT_SEARCH_TIMEOUT_SEC

#: Poll granularity while waiting for the next scheduled query, so ``stop()`` stays responsive.
_STOP_POLL_SEC = 0.1

#: The one foreground-search request shape. Shared with the cadence first-search probe
#: (:mod:`jseval.cadence`, item 19) so both populations measure the identical query.
SEARCH_PATH = "/api/knowledge/search"
DEFAULT_TOP_K = 10
DEFAULT_SEARCH_MODE = "hybrid"


def open_client(base_url: str) -> httpx.Client:
    """The HTTP client every foreground search in this harness is issued through."""
    return httpx.Client(base_url=base_url, timeout=REQUEST_TIMEOUT_SEC)


def search_body(
    query: str,
    top_k: int = DEFAULT_TOP_K,
    search_mode: str = DEFAULT_SEARCH_MODE,
) -> dict:
    """Request body for ``POST /api/knowledge/search``."""
    return {"query": query, "limit": top_k, "mode": search_mode}


def issue_search(client: httpx.Client, body: dict) -> float | None:
    """Issue one search. Returns its latency in ms, or ``None`` when the request failed."""
    t0 = time.monotonic()
    try:
        resp = client.post(SEARCH_PATH, json=body)
        resp.raise_for_status()
        return (time.monotonic() - t0) * 1000.0
    except Exception as e:
        log.warning("Search query failed (%s): %s", type(e).__name__, e)
        return None


@dataclass(frozen=True)
class SearchLoadSpec:
    """Resolved search-load configuration. ``qpm`` is ``None`` in continuous mode."""

    mode: str
    qpm: int | None = None
    top_k: int = DEFAULT_TOP_K
    search_mode: str = DEFAULT_SEARCH_MODE


def resolve_spec(qpm: int | None, continuous: bool) -> SearchLoadSpec | None:
    """Turn the two CLI options into a spec (or ``None`` when neither was passed).

    Raises ``ValueError`` when both are given or when ``qpm`` is not positive.
    """
    if qpm is not None and continuous:
        raise ValueError("--search-load-qpm and --search-load continuous are mutually exclusive")
    if continuous:
        return SearchLoadSpec(mode="continuous")
    if qpm is None:
        return None
    if qpm <= 0:
        raise ValueError(f"--search-load-qpm must be positive, got {qpm}")
    return SearchLoadSpec(mode="qpm", qpm=qpm)


def interval_sec(qpm: int) -> float:
    """Even spacing between two queries at ``qpm`` queries per minute."""
    if qpm <= 0:
        raise ValueError(f"qpm must be positive, got {qpm}")
    return 60.0 / qpm


def due_at(start: float, index: int, qpm: int) -> float:
    """Monotonic deadline for the ``index``-th query (0-based) of a ``qpm`` schedule."""
    if index < 0:
        raise ValueError(f"index must be non-negative, got {index}")
    return start + index * interval_sec(qpm)


def wait_sec(now: float, start: float, index: int, qpm: int) -> float:
    """Seconds to wait before issuing query ``index``; 0 when already due or behind."""
    return max(0.0, due_at(start, index, qpm) - now)


def percentile(sorted_values: list[float], pct: float) -> float:
    """Nearest-rank percentile over an already-sorted list (``pct`` in 0..100)."""
    if not sorted_values:
        raise ValueError("percentile of an empty sample")
    if not 0 < pct <= 100:
        raise ValueError(f"pct must be in (0, 100], got {pct}")
    rank = -(-len(sorted_values) * pct // 100)  # ceil without importing math
    return sorted_values[int(rank) - 1]


def summarize(
    spec: SearchLoadSpec,
    latencies_ms: list[float],
    errors: int,
    started_at: str,
    ended_at: str,
    duration_s: float,
    query_pool_size: int,
) -> dict:
    """Build the ``summary.json`` ``search_load`` block."""
    ordered = sorted(latencies_ms)
    block: dict = {
        "mode": spec.mode,
        "qpm": spec.qpm,
        "search_mode": spec.search_mode,
        "request_timeout_sec": REQUEST_TIMEOUT_SEC,
        "queries_issued": len(latencies_ms) + errors,
        "queries_ok": len(latencies_ms),
        "errors": errors,
        "query_pool_size": query_pool_size,
        "started_at": started_at,
        "ended_at": ended_at,
        "duration_s": round(duration_s, 3),
        "latency_ms": None,
    }
    if ordered:
        block["latency_ms"] = {
            "p50": round(percentile(ordered, 50), 3),
            "p95": round(percentile(ordered, 95), 3),
            "max": round(ordered[-1], 3),
        }
    return block


class SearchLoadRunner:
    """Runs the query loop on a daemon thread between :meth:`start` and :meth:`stop`."""

    def __init__(self, base_url: str, queries: list[str], spec: SearchLoadSpec) -> None:
        if not queries:
            raise ValueError("search load needs at least one query")
        self._base_url = base_url
        self._queries = queries
        self._spec = spec
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._latencies_ms: list[float] = []
        self._errors = 0
        self._started_at = ""
        self._ended_at = ""
        self._wall_start = 0.0
        self._wall_end = 0.0

    def start(self) -> None:
        self._started_at = datetime.now(timezone.utc).isoformat()
        self._wall_start = time.monotonic()
        self._thread = threading.Thread(
            target=self._run, name="jseval-search-load", daemon=True,
        )
        self._thread.start()
        log.info(
            "Search load started: mode=%s qpm=%s pool=%d",
            self._spec.mode, self._spec.qpm, len(self._queries),
        )

    def stop(self) -> dict:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=REQUEST_TIMEOUT_SEC + 5.0)
        self._wall_end = time.monotonic()
        self._ended_at = datetime.now(timezone.utc).isoformat()
        block = summarize(
            self._spec,
            self._latencies_ms,
            self._errors,
            self._started_at,
            self._ended_at,
            self._wall_end - self._wall_start,
            len(self._queries),
        )
        log.info(
            "Search load stopped: issued=%d errors=%d latency=%s",
            block["queries_issued"], block["errors"], block["latency_ms"],
        )
        return block

    # -- internals ---------------------------------------------------------

    def _run(self) -> None:
        try:
            with open_client(self._base_url) as client:
                index = 0
                while not self._stop.is_set():
                    if self._spec.qpm is not None and not self._wait_for_slot(index):
                        return
                    query = self._queries[index % len(self._queries)]
                    self._issue(
                        client,
                        search_body(query, self._spec.top_k, self._spec.search_mode),
                    )
                    index += 1
        except Exception:  # pragma: no cover - the loop must never kill the run
            log.exception("Search load thread aborted")

    def _wait_for_slot(self, index: int) -> bool:
        """Sleep until query ``index`` is due. Returns False if stopped while waiting."""
        assert self._spec.qpm is not None
        while not self._stop.is_set():
            remaining = wait_sec(time.monotonic(), self._wall_start, index, self._spec.qpm)
            if remaining <= 0:
                return True
            self._stop.wait(min(remaining, _STOP_POLL_SEC))
        return False

    def _issue(self, client: httpx.Client, body: dict) -> None:
        latency_ms = issue_search(client, body)
        if latency_ms is None:
            self._errors += 1
        else:
            self._latencies_ms.append(latency_ms)
