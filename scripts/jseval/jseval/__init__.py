"""JustSearch search evaluation toolkit."""
from __future__ import annotations

import importlib.util
import os
import sys
from pathlib import Path


def _bind_own_jsharness() -> None:
    """Import jsharness from this checkout's ``scripts/jsharness``, never from elsewhere.

    jseval runs jsharness code (readiness, process registers, statistics), so the
    jsharness that runs must be the one beside this jseval: a PYTHONPATH naming only
    ``scripts/jseval``, or an editable install from another checkout, would otherwise
    fail to import it or silently run the other checkout's copy (and lane-f would hash
    files that did not run). Load it by file location, bypassing ``sys.path``; refuse
    if a different jsharness is already imported in this process (same idea as the
    tempdoc-716 cross-checkout guard in ``jseval.cli``).
    """
    expected = Path(__file__).resolve().parents[2] / "jsharness" / "jsharness"
    loaded = sys.modules.get("jsharness")
    if loaded is None:
        init = expected / "__init__.py"
        if not init.is_file():
            raise ImportError(
                "jseval needs jsharness from its own checkout, which is missing:\n"
                f"  jseval code: {Path(__file__).resolve().parent}\n"
                f"  expected jsharness: {expected}\n"
                "jseval loads jsharness from the scripts/jsharness directory next to "
                "its own scripts/jseval; run jseval from a full checkout."
            )
        spec = importlib.util.spec_from_file_location(
            "jsharness", init, submodule_search_locations=[str(expected)])
        loaded = importlib.util.module_from_spec(spec)
        sys.modules["jsharness"] = loaded
        try:
            spec.loader.exec_module(loaded)
        except BaseException:
            sys.modules.pop("jsharness", None)
            raise
    actual = Path(loaded.__file__ or "").resolve().parent
    same = os.path.normcase(os.path.normpath(str(actual))) == \
        os.path.normcase(os.path.normpath(str(expected)))
    if not same:
        raise ImportError(
            "jsharness is imported from a DIFFERENT location than the checkout "
            f"jseval belongs to:\n  imported jsharness: {actual}\n  "
            f"jseval's jsharness: {expected}\n"
            "jseval would run another checkout's readiness and process-register "
            "code against this tree.\nFix:  import jseval before jsharness, or "
            "put this checkout first on the path, e.g.\n  "
            f"PYTHONPATH={expected.parents[1] / 'jseval'}{os.pathsep}"
            f"{expected.parent} python -m jseval ..."
        )


_bind_own_jsharness()
