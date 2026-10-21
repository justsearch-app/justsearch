---
title: "Frontend kernel — platform & stack"
type: reference
status: stable
description: "FE platform/stack contracts (Lit, build, runtime)."
date: 2026-06-09
---

# Frontend kernel — platform & stack

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft's `10-kernel/` set (authored ~2026-05; the rewrite shipped per tempdoc 563). References to
> the draft's removed planning material (`slices/`, `20-systems/`, `archive/`, …) are historical.
> ADR links point to `docs/decisions/`; sibling kernel docs are in this folder.


The framework platform choice remains part of the destination packet because it
shapes plugin boundaries, theming, and long-term maintenance.

## Default Stack

The destination default is:

- Lit 3 for shell and generic framework components
- Web Components as the public component boundary
- CSS custom properties for theme and design tokens
- native browser platform APIs where sufficient

The reason is structural, not aesthetic. Web Components provide a stable
boundary for plugins, themes, and future product rewrites. Lit is the small
default authoring layer for that boundary.

## Hybrid Rule

The framework boundary is Web Components. Inside a component or plugin, an
implementation may use another framework when the plugin owns the cost.

This preserves a stable shell/plugin contract while avoiding a false claim that
all future UI code must be Lit forever.

## Rejected Alternatives

The original packet evaluated React, Solid, Svelte, Vue, and generic platform
approaches. The current rewrite does not repeat every comparison, but the
decision logic is preserved:

- React is strong for product UI but weaker as a plugin/theme boundary.
- Solid was the strongest runner-up.
- Large generic UI platforms fail when escape hatches are underfunded.
- Framework popularity is less important than stable extension boundaries.

## Library Policy

Libraries should be adopted when they support framework responsibilities:

- schema/forms rendering
- command search
- accessibility primitives
- i18n formatting
- stream parsing
- diagnostics export

They should not become hidden owners of backend truth or plugin trust.

## Backend Implications

The frontend stack depends on backend contracts:

- self-describing primitive entries
- JSON schemas for arguments/settings/resources
- i18n keys rather than duplicated English maps
- capability handshake fields
- declared runtime context
- stable operation/resource ids

The stack decision is therefore coupled to backend API discipline, not just UI
implementation style.

