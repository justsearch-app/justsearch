"""jseval projections — Layer-4 derived analyses (tempdoc 400 LR4).

A projection is a pure function over a run directory's artifacts
(``traces.ndjson``, ``metrics.ndjson``, ``{mode}_per_query.json``,
``summary.json``, ``manifest.json``) that produces a derived JSON
document. Each projection registers itself at module import time;
``jseval run`` invokes every registered projection at end-of-run via
:func:`jseval.projections.run_all`.

Sub-modules:

- :mod:`jseval.projections.base` — registry + Projection dataclass +
  ``run_all`` dispatcher (LR4-a).
- :mod:`jseval.projections.contract_violations` — LR6-c aggregator
  (Phase 1 shipped; unchanged).
- :mod:`jseval.projections.rate_timeline` — LR4-d rate + stall
  tagging.
- :mod:`jseval.projections.cpu_fallback_counts` — LR4-f fallback
  aggregator.
"""

from __future__ import annotations

from .base import (
    Projection,
    register,
    registry,
    reset_registry_for_tests,
    run_all,
)

__all__ = [
    "Projection",
    "register",
    "registry",
    "reset_registry_for_tests",
    "run_all",
]


_PROJECTION_MODULE_NAMES = (
    # Phase 1 (shipped).
    "contract_violations",
    # Phase 3 (LR4-*); modules land incrementally. Listing them here
    # ensures each new module self-registers on the next bootstrap
    # without further __init__.py edits.
    "cpu_fallback_counts",
    "rate_timeline",
    # Tempdoc 406: Lucene runtime substrate observability.
    "lucene_runtime_telemetry",
    # Tempdoc 636 / D-005: recall-funnel decomposition.
    "staged_recall_accounting",
    # Tempdoc 916: SPLADE truncation evidence (opt-in sidecar; empty when unset).
    "splade_truncation",
)


def _import_registered_projections() -> None:
    """Import every projection sub-module + register its ``PROJECTION``.

    Each projection module exports a module-level ``PROJECTION``
    attribute of type :class:`Projection`. This function imports the
    module and registers that attribute — decoupling registration
    from import side effects so registry-reset-then-re-bootstrap
    (i.e. tests) works correctly.

    Missing modules are tolerated: Phase 3 ships LR4-* incrementally,
    and a module that does not exist yet must not break the bootstrap
    for the modules that do. Import failures for known-missing
    siblings are debug-logged; any other error propagates (a broken
    module is still a failure signal).
    """
    import importlib

    for mod_name in _PROJECTION_MODULE_NAMES:
        qualified = f"{__name__}.{mod_name}"
        try:
            mod = importlib.import_module(qualified)
        except ModuleNotFoundError as exc:
            # Only tolerate when the missing module is the projection
            # sub-module itself; a missing transitive dep (e.g. ranx)
            # surfaces as a different missing name and should propagate.
            if exc.name == qualified:
                import logging as _logging
                _logging.getLogger(__name__).debug(
                    "projection %r not present yet; skipping during "
                    "incremental Phase 3 landing", mod_name,
                )
                continue
            raise
        projection = getattr(mod, "PROJECTION", None)
        if projection is not None:
            register(projection)


def _bootstrap() -> None:
    """Re-populate the registry from every available projection module.

    Idempotent by virtue of :func:`register` dropping duplicate names.
    Safe to call from :func:`run_all_discovered` on every invocation —
    consumers that reset the registry (tests) get a fresh population
    on the next call without bookkeeping their own flags.
    """
    _import_registered_projections()


def run_all_discovered(run_dir, *, skip=frozenset()):
    """Bootstrap the registry, then invoke :func:`run_all`.

    The canonical entrypoint for ``jseval run`` at end-of-run. Tests
    that want a controlled registry should call :func:`reset_registry_
    for_tests` and register their own Projections instead of using
    this helper.

    Pass ``skip={"projection_name", ...}`` to bypass specific
    projections (Phase 6 / 6.1 — useful when iterating on a flaky
    projection without losing the nightly's other signals).
    """
    _bootstrap()
    return run_all(run_dir, skip=skip)
