"""Shared process readiness result."""
from __future__ import annotations
from dataclasses import dataclass, field

@dataclass
class ReadinessResult:
    """Result of a backend readiness check."""

    passed: bool
    failure_reasons: list[str] = field(default_factory=list)
    snapshot: dict = field(default_factory=dict)


