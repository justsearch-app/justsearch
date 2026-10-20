---
title: "ADR-0032: Frontend rendering — Lit web components"
type: decision
status: accepted
description: "The frontend is authored as Lit web components; the React stack was retired in the rewrite."
date: 2026-06-09
probes:
  - adr-0032-no-react
last_reviewed: 2026-09-02
---


# ADR-0032: Frontend rendering — Lit web components

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft (authored ~2026-05; the rewrite shipped per tempdoc 563). Internal cross-references to that
> draft's now-removed planning material (`slices/`, `20-systems/`, `30-agent-workflows/`,
> `60-migration-history/`, `archive/`) are historical and were not rewritten — the decision stands on
> its own. Sibling-ADR links point to `docs/decisions/`; kernel links to
> `docs/reference/ui/frontend-kernel/kernel/`.

## Status

Accepted. Supersedes the FE half of ADR-0012 (UI Stack and Documentation Tooling, now Superseded). The React→Lit rewrite shipped per tempdoc 563.

## Decision

The default framework stack is Lit 3 with Web Components and CSS custom
properties.

## Rationale

The public boundary matters more than product authoring preference. Web
Components create a stable plugin/theme boundary; Lit provides a small default
implementation layer.

## Rejects

- React as the public plugin/theme boundary for the framework kernel.
- A framework-specific plugin API that cannot survive product rewrites.
- Pretending the hybrid model has no cognitive cost.

## Future Agents Must Not

- Use stack popularity alone to reopen the decision.
- Bind plugin contracts to a product framework implementation detail.
- Hide the cost of custom Lit renderer sets or Shadow DOM ergonomics.

## Revisit When

- Web Components stop being a viable browser-native boundary.
- Plugin requirements are intentionally dropped from the framework.
- A different stack can preserve plugin/theme contracts with lower total
  maintenance cost and documented migration path.

## Affected Docs

- `../reference/ui/frontend-kernel/kernel/03-platform-stack.md`
- `20-systems/04-theme-customization.md`
- `20-systems/07-extensions-renderers.md`

## Source Evidence

- `archive/source-tempdocs/421-stack.md`
- `archive/source-tempdocs/421-reasoning.md`
