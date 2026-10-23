---
title: "ADR-0035: Plugin boundary — truth vs presentation"
type: decision
status: accepted
description: "Plugins extend presentation while core backend truth remains backend-owned. The frontend Resource overlay still permits colliding plugin IDs to replace catalog entries; the intended boundary is not fully enforced by types."
date: 2026-06-09
probes: none - the contribution-surface gate does not establish namespace safety at the frontend Resource overlay; see the 2026-10-20 amendment.
last_reviewed: 2026-10-20
---


# ADR-0035: Plugin boundary — truth vs presentation

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft (authored ~2026-05; the rewrite shipped per tempdoc 563). Internal cross-references to that
> draft's now-removed planning material (`slices/`, `20-systems/`, `30-agent-workflows/`,
> `60-migration-history/`, `archive/`) are historical and were not rewritten — the decision stands on
> its own. Sibling-ADR links point to `docs/decisions/`; kernel links to
> `docs/reference/ui/frontend-kernel/kernel/`.

## Status

Accepted. Cited by `docs/how-to/write-a-plugin.md` (as "ADR 05"). Carried forward by tempdocs 560 (extension substrate) and 569 (user-authored presentation).

Current implementation corrections are recorded in the 2026-10-20 amendment below.

## Decision

Plugins may extend and render framework capability, but they do not own core
backend truth.

## Refinement (V1.5, 2026-05-07)

The original decision conflated two boundaries that V1.5 cohort umbrella
`slices/470-v1-5-user-ui-authorship-substrate.md` has separated:

- **Truth boundary** (preserved, load-bearing): plugins, themes,
  layouts, and chromes CANNOT fork the canonical implementations of
  Operations, Resources, or Prompts. Backend-owned, singular,
  authoritative. The kernel's single-source-of-truth property
  depends on this invariant. Forking truth produces forked-shell-per-
  plugin chaos and the framework collapses.

- **Presentation boundary** (lifted with user consent + provenance UI):
  plugins, themes, layouts, and chromes CAN replace the renderer, the
  surface, the layout, the chrome, or the tokens. Subject to:
  1. explicit user opt-in (no auto-replacement on plugin install)
  2. persistent provenance badge in the chrome indicating
     "you are viewing a non-core <element>; provided by `<plugin-id>`
     at trust tier `<tier>`"
  3. one-click revert to core always available
  4. the override recorded in `userConfig` per slice 470 §4

The presentation boundary lift unblocks "users can fully create their
own UI" — the V1.5 program's stated goal — without violating the
truth boundary. The two boundaries serve different invariants and
must not be conflated.

Operations / Resources / Prompts = truth. Renderer / Surface / Layout
/ Chrome / Theme = presentation. Truth stays singular; presentation
is user-authored.

## Architectural invariant: validate at construction, not trust at consumption

Surfaced 2026-05-07 by `slices/478-v1-5-structural-design-refinements.md`
§3 after the V1.5 alpha confidence-increase pass. The seven
uncertainties flagged in 470 §B.D.11 all reduced to a single root
cause: values crossing trust boundaries as raw data, with consumers
responsible for re-validating against documented invariants.

The structurally-correct alternative: **producing sites emit
value-types whose construction is the validation.** Consumers cannot
bypass an invariant they cannot construct around. Trust state is a
type, not a comment.

This invariant operates within the presentation boundary; it does
not modify the truth boundary above. See 478 §3 for the seam-mapping
table and §4.A–I for the nine design refinements that operationalize
the invariant. Future plugin-substrate ADRs should cite this
invariant when designing new dispatch / catalog / lifecycle
surfaces.

## Rationale

The framework needs extensibility without letting untrusted or separately
versioned code fork the shell's truth model. Plugin contributions require
permissions, namespaces, diagnostics attribution, and failure containment.

## Rejects

- Plugins as hidden owners of core readiness, health, or operation truth.
- Silent plugin updates that expand permissions or alter shell behavior.
- Renderer escape hatches without visible diagnostics and failure containment.

## Future Agents Must Not

- Let plugin convenience APIs bypass operation risk or confirmation policy.
- Store plugin state outside a declared namespace.
- Treat local user-installed plugins as fully trusted shell code.

## Revisit When

- Plugin support is intentionally removed from the framework.
- A stronger signing/marketplace trust tier is added.
- A plugin contribution type requires new permission or containment vocabulary.
- **Presentation surface replacement** (Surface / Chrome / Layout /
  Theme Manifests). **TRIGGERED 2026-05-07** by V1.5 user UI authorship
  goal — addressed by the Refinement section above and slice cohort
  464/471/472/473/474 under umbrella `slices/470-v1-5-user-ui-authorship-substrate.md`.

## Affected Docs

- `20-systems/07-extensions-renderers.md`
- `20-systems/04-theme-customization.md`
- `20-systems/08-accessibility-observability-verification.md`
- `slices/470-v1-5-user-ui-authorship-substrate.md` (V1.5 cohort umbrella; truth/presentation refinement is the authoritative theory record; THEORY-RATIFIED 2026-05-07)
- `slices/464-v1-5-plugin-substrate-foundation.md` (Plugin tier sub-theory; ratified 2026-05-07)
- `slices/471-v1-5-surface-override-channel.md` (Surface override channel)
- `slices/472-v1-5-layout-manifest.md` (Layout Manifest tier — Eclipse perspective template)
- `slices/474-v1-5-theme-manifest.md` (Theme Manifest tier — CSS Cascade Layers + DTCG)
- `slices/475-v1-5-tailwind-cleanup-debt.md` (theme-tier prereq cleanup)
- `slices/476-v1-6-chrome-manifest-tier.md` (Chrome substitution deferred to V1.6 per Option A ratification)
- `slices/478-v1-5-structural-design-refinements.md` (validate-at-construction invariant; partially implemented refinements; V1.5.2 residuals)

## Source Evidence

- `archive/source-tempdocs/421-extensibility.md`
- `archive/source-tempdocs/421-stack.md`

## Amendment 2026-10-20: frontend Resource overlay enforcement gap

The truth/presentation split above remains the intended backend ownership
rule. Its description as a fully enforced type boundary is too strong for the
frontend Resource overlay: `mergePluginResourceContributions` copies the catalog
and calls `next.set(merged.id, merged)`, so a plugin entry with a colliding id
replaces the frontend entry. `PluginRegistry` forwards `resourceContributions`
without a `core.*` namespace rejection at this seam. Provenance is attached,
but it does not prevent the collision. This observation is about the frontend
catalog; it does not establish that a plugin replaces a backend Resource owner.
Evidence: `modules/ui-web/src/api/registry/ResourceCatalogClient.ts`
(`mergePluginResourceContributions`) and
`modules/ui-web/src/shell-v0/plugin-api/PluginRegistry.ts` (`resourceContributions`
handler). Enforcing the namespace invariant requires a separate code change;
this amendment does not authorize overriding core truth.
