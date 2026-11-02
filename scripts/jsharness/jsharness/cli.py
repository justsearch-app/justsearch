"""jsharness CLI entry point — the ``main`` Click group + global options.

All commands now live in :mod:`jsharness.commands` (split from the former
3.5k-line monolith — tempdoc 645). ``main`` stays importable as
``jsharness.cli.main`` to preserve the ``jsharness`` console-script and
``python -m jsharness`` entry points.
"""
from __future__ import annotations

import logging
import os

import click



def _assert_matching_checkout() -> None:
    """Refuse to run another checkout's jsharness code against this one (tempdoc 716).

    An editable install / stale PYTHONPATH pins ``import jsharness`` to ONE
    checkout; invoking from a different worktree then silently executes the
    other checkout's code (path resolution follows CWD since tempdoc 351, so
    the failure is stale-logic-with-fresh-paths — worse than either alone).
    Fail closed with the remedy inline; escape hatch mirrors the
    ``--allow-engine-mismatch`` idiom (tempdoc 644) for the deliberate case.
    """
    if os.environ.get("JUSTSEARCH_ALLOW_CROSS_CHECKOUT_JSHARNESS") == "1":
        return
    from ._paths import cwd_checkout_root, module_checkout_root

    cwd_root = cwd_checkout_root()
    if cwd_root is None:
        return  # not inside a JustSearch checkout — nothing to cross-check
    module_root = module_checkout_root()
    if os.path.normcase(os.path.normpath(str(cwd_root))) == \
            os.path.normcase(os.path.normpath(str(module_root))):
        return
    raise click.ClickException(
        "jsharness is imported from a DIFFERENT checkout than the one you are "
        f"invoking it from:\n  imported code: {module_root}\n  "
        f"invoking checkout: {cwd_root}\n"
        "An editable install pins `import jsharness` to one checkout; running "
        "from another silently executes stale code against this tree's "
        "paths.\nFix:  set PYTHONPATH before invoking, e.g.\n  "
        f"PYTHONPATH={cwd_root / 'scripts' / 'jsharness'} python -m jsharness ...\n"
        "or set JUSTSEARCH_ALLOW_CROSS_CHECKOUT_JSHARNESS=1 to run "
        "cross-checkout deliberately."
    )


@click.group()
@click.option("--verbose", "-v", is_flag=True, help="Enable debug logging.")
@click.option("--json", "json_mode", is_flag=True, help="Emit JSON to stdout.")
@click.pass_context
def main(ctx, verbose: bool, json_mode: bool) -> None:
    """JustSearch UI checks and process registers."""
    _assert_matching_checkout()
    ctx.ensure_object(dict)
    ctx.obj["json"] = json_mode
    level = logging.DEBUG if verbose else logging.INFO
    logging.basicConfig(
        level=level,
        format="%(asctime)s %(levelname)-8s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )
    # Suppress httpcore/httpx request-level logging — it floods the output
    # with ~20 lines per HTTP call, drowning progress logging (14:1 noise ratio).
    logging.getLogger("httpcore").setLevel(logging.WARNING)
    logging.getLogger("httpx").setLevel(logging.WARNING)


from .commands import COMMANDS  # noqa: E402

for command in COMMANDS:
    main.add_command(command)
