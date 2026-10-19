---
title: "Conflict Ledger"
type: reference
status: stable
description: "The going-forward register for design conflicts, intentional reframes, and unresolved authority questions — plus the implementer≠resolver closure protocol that governs how they are resolved."
date: 2026-06-09
---

# Conflict Ledger

This ledger records **conflicts, intentional reframes, and unresolved authority
questions** that surface during slice / substrate work, together with their current
handling. It is the artifact the slice-execution closure protocol writes to.

## The closure protocol (implementer ≠ resolver)

When a slice or substrate change surfaces a conflict, record a row here. The
load-bearing discipline:

> **The agent or reviewer resolving a conflict should not be the same agent who
> originally flagged it.** Single-agent self-validation rests on the same blind
> spots as the implementation.

The retired slice-execution methodology required a conflict row here alongside the
commit message naming the verdict and a committed follow-up slice id. Both
`independent-reviewer-required` and `ux-audit-closure` are honor-system rules,
not build-enforced (the gates that enforced them were retired, tempdoc 563).

## How to add a row

1. Give the conflict a stable `C-NNN` id (next free number).
2. State the conflict and its current handling.
3. On resolution, a *different* agent/reviewer records the resolution direction
   and links the follow-up slice/ADR.

## Open conflicts

| ID | Conflict | Current handling |
| --- | --- | --- |
| — | *(none currently open)* | — |

## History

The historical `C-001 … C-018` conflict family was authored during the
frontend-rewrite kernel work (the retired `421` draft, ~2026-05) and was retired
with that draft on 2026-06-09. Those rows are preserved in git history.
