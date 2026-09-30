"""Helpers for operation keys accepted by the JustSearch API."""

from __future__ import annotations

import secrets
import time
import uuid


def create_operation_key() -> str:
    """Return a canonical RFC 9562 UUIDv7 using the current Unix millisecond."""
    timestamp_ms = time.time_ns() // 1_000_000
    if not 0 <= timestamp_ms <= 0xFFFFFFFFFFFF:
        raise ValueError("UUIDv7 timestamp is outside the 48-bit range")

    random_a = secrets.randbits(12)
    random_b = secrets.randbits(62)
    value = (timestamp_ms << 80) | (0x7 << 76) | (random_a << 64)
    value |= 0b10 << 62 | random_b
    return str(uuid.UUID(int=value))
