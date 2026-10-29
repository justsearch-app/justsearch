---
title: Tempdocs
type: tempdocs
status: noncanonical
---

> **Noncanonical, and outdated by design.** These are dated working notes, not current truth.
> For how the system behaves *now*, read `docs/explanation/`, `docs/reference/`, or the code.
>
> **Frozen since 2026-10-05.** Do not edit or add tempdocs. Current design goes to `docs/explanation/`, `docs/reference/` or an ADR in `docs/decisions/`.

# Tempdocs

**Tempdocs are temporary documents** — the name is the definition. Each one is a dated working
note (an investigation log, a design sketch, a slice plan) written *during* a piece of work and
then **left as it was**. They are **not maintained** after the work lands, so a tempdoc is
**outdated by design**: it describes the state of the project on the day it was written.

They are *kept* rather than deleted because, together, they are a useful **development timeline and
design archaeology** — why a decision was made, what alternatives were tried and rejected, and how
the system evolved. `git blame` on a canonical doc often points back at the tempdoc that motivated
it.

## How to read a tempdoc

- **Never treat it as current truth.** If a tempdoc claims something about how the system behaves,
  verify against `docs/explanation/` + `docs/reference/`, or the code and contract tests. The
  canonical docs are the *current behavior* surface; a tempdoc is the *historical rationale* surface.
- **The date and number are the context.** Numbers are allocated roughly in creation order, so a
  **lower number means earlier in the project** — and therefore more likely to be superseded by
  later work. Read an old tempdoc as a snapshot of an old state.
- **Candid language and dated numbers are the nature of working notes.** Tempdocs are written
  frankly (blunt problem descriptions, exploratory benchmark figures, "this is probably wrong"
  asides). Those are notes-to-self at a moment in time — **not claims about the current product**,
  and not marketing. A measurement quoted in a 3-month-old tempdoc is dated unless the canonical
  benchmark docs repeat it.

## Conventions

### Cite by slug, not number

When referring to a tempdoc **in prose**, use its title slug (the filename minus the leading number
and `.md`), not its number:

| Use | Avoid |
|---|---|
| "the `plugin-ecosystem-substrate` tempdoc" | "tempdoc 508" |
| "per the `core-contracts-c018-audit`" | "per tempdoc 519" |

Numbers are sort-order identifiers, not stable identities. Concurrent agent sessions routinely
collide on a number, and the fix is to renumber — which silently breaks any prose reference written
as "tempdoc 508". Slugs survive renumbering. (File-path *links* naturally include the current number
and must be updated when a file is renumbered; that's expected.)

**Renumbering on a collision:**

1. Renumber the newer / less-cited one to a free number:
   `mv docs/tempdocs/<old>-<slug>.md docs/tempdocs/<new>-<slug>.md`
2. Update the `# <number> — <Title>` heading inside the file.
3. Update any file-path links: `git grep -nE '<old>-<slug>' docs/` finds them.

### Multi-part clusters

A single investigation may produce several tempdocs sharing a number prefix (e.g.
`249-vespa-findings.md`, `249-opensearch-findings.md`, `249-anserini-findings.md`). This is fine when
they are coordinated parts of one investigation. Otherwise, each tempdoc gets a unique number.

## Frontmatter

Tempdoc frontmatter carries a freeform `status` / `created` / `updated`. Treat these as **dated
hints, not a controlled vocabulary** — `status` is written however the author found useful at the
time (`done`, `shipped`, `implemented`, `active`, prose summaries, …). There is no enforced status
machine; the date fields are the reliable signal.

When a tempdoc's work lands, its substantive learnings *should* migrate to the canonical docs
(`docs/explanation/`, `docs/reference/`, a new ADR in `docs/decisions/`) so the next reader finds
current truth there rather than reconstructing it from history. The tempdoc then stays as the
rationale record. This is a norm, not an enforced gate.
