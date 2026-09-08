"""jseval CLI command registry (tempdoc 645).

Each group module under this package exposes a module-level ``COMMANDS``
list of :class:`click.Command` objects. :func:`register_all` imports every
module, registers each command **with its owning group** in a name-keyed
registry, and attaches it to the top-level Click group. Mirrors the
``jseval.projections`` self-registering-module seam (``register`` /
``registry`` / ``register_all``). Import errors PROPAGATE (a shipped command
must never silently vanish), and a duplicate command name raises (Click's
``add_command`` would otherwise silently overwrite — louder than projections'
last-wins, justified for a shipped CLI).

This module is the **single source of command-surface metadata** (name → group):
``--help`` sections (`JsevalGroup`), the ``jseval commands`` catalog, and the
surface-lock inventory all *project* :func:`command_groups` here — none forks
its own group map (tempdoc 645 projection-vs-fork seam).
"""
from __future__ import annotations

import dataclasses
import difflib
import importlib

import click

# Group modules under jseval.commands.* (also the default --help section order).
_GROUP_MODULES = (
    "run",
    "analysis",
    "bench",
    "gates",
    "corpus",
    "ui",
    "eval_cmds",
    "utility",
    "release",
    "ops",
    "index_cache_cmd",
    "workflow_fixture",
)
# Pre-existing command modules that live at jseval.* (registered here too).
_LEGACY_MODULES = ("qu_spike", "qu_v3_eval")

# Module stem -> stable group label. Defaults to the stem; only overrides differ.
# The 2 legacy modules share one explicit label (they have no group module).
_MODULE_GROUP_LABEL = {"eval_cmds": "eval", "qu_spike": "query-understanding",
                       "qu_v3_eval": "query-understanding",
                       "index_cache_cmd": "index-cache",
                       "workflow_fixture": "workflow-fixture"}

# Logical order groups appear in (``--help`` + catalog). Module order, with the
# legacy group last.
GROUP_ORDER = tuple(_MODULE_GROUP_LABEL.get(m, m) for m in _GROUP_MODULES) + ("query-understanding",)


@dataclasses.dataclass(frozen=True)
class CommandInfo:
    """A registered command and the group it belongs to."""

    command: click.Command
    group: str


_REGISTRY: dict[str, CommandInfo] = {}


def register(command: click.Command, group: str) -> click.Command:
    """Register ``command`` under ``group`` by name; raise on a duplicate name.

    Unlike :mod:`jseval.projections` (which lets the last registration win),
    a duplicate CLI command name is a build error — two modules both claiming
    e.g. ``run`` would otherwise have one silently shadow the other.
    """
    name = command.name
    if name in _REGISTRY:
        raise ValueError(f"duplicate jseval command name: {name!r}")
    _REGISTRY[name] = CommandInfo(command=command, group=group)
    return command


def registry() -> dict[str, CommandInfo]:
    """Return a read-only snapshot of the registry (name -> CommandInfo)."""
    return dict(_REGISTRY)


def command_groups() -> dict[str, str]:
    """Return name -> group label — the canonical command→group map to project."""
    return {name: info.group for name, info in _REGISTRY.items()}


def reset_registry_for_tests() -> None:
    """Clear the registry (tests that re-bootstrap a controlled set)."""
    _REGISTRY.clear()


def register_all(group):
    """Import every command module, register its ``COMMANDS`` with its group, attach to ``group``."""
    reset_registry_for_tests()
    todo = [(f"{__name__}.{m}", _MODULE_GROUP_LABEL.get(m, m)) for m in _GROUP_MODULES]
    todo += [(f"jseval.{m}", _MODULE_GROUP_LABEL.get(m, m)) for m in _LEGACY_MODULES]
    for modname, label in todo:
        mod = importlib.import_module(modname)
        for cmd in mod.COMMANDS:
            register(cmd, label)
            group.add_command(cmd)


def _group_title(group: str) -> str:
    """Human section heading for a group label (``query-understanding`` -> ``Query Understanding``)."""
    return group.replace("-", " ").title()


class JsevalGroup(click.Group):
    """Top-level group that PROJECTS the registry's group metadata for UX.

    Two features, both reading the canonical command→group map (never a fork):
    - ``--help`` lists commands in labelled sections by group, ordered by
      :data:`GROUP_ORDER` (tempdoc 645 A2);
    - a mistyped command yields a git-like "did you mean …?" suggestion
      (tempdoc 645 B6), zero-dependency via :mod:`difflib`.
    """

    def format_commands(self, ctx, formatter):
        groups = command_groups()
        # bucket visible commands by group, preserving registration order within a group
        buckets: dict[str, list[tuple[str, str]]] = {}
        for name in self.list_commands(ctx):
            cmd = self.get_command(ctx, name)
            if cmd is None or cmd.hidden:
                continue
            label = groups.get(name, "other")
            buckets.setdefault(label, []).append((name, cmd.get_short_help_str()))
        ordered = [g for g in GROUP_ORDER if g in buckets]
        ordered += [g for g in buckets if g not in GROUP_ORDER]  # any stragglers, stable
        for label in ordered:
            with formatter.section(_group_title(label)):
                formatter.write_dl(buckets[label])

    def resolve_command(self, ctx, args):
        try:
            return super().resolve_command(ctx, args)
        except click.UsageError as error:
            attempted = args[0] if args else ""
            matches = difflib.get_close_matches(attempted, self.list_commands(ctx), n=3, cutoff=0.5)
            if matches:
                hint = matches[0] if len(matches) == 1 else ", ".join(matches)
                error.message = f"{error.message}\n\nDid you mean: {hint}?"
            raise
