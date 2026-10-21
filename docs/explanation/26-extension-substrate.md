---
title: The Extension Substrate
type: explanation
status: stable
description: "How JustSearch is extended: one declaration model, one composer, four shared substrates, the host-owns-truth boundary, and trust-proportional isolation — the invariants every contributor (plugin, operation, resource, prompt, MCP tool, workflow, agent tool) shares."
---

# The Extension Substrate

Everything that extends JustSearch is the **same kind of thing**. A user's
plugin, a core operation, a resource, a prompt template, an external MCP tool,
a workflow, an agent tool — each is a *contribution* that **declares** what it
offers and is **composed** into one registry the host owns. There is no second
extension mechanism: the agent is just another contributor, and a plugin is not
a privileged side-channel.

This document records the **invariants** of that substrate — the properties
that hold regardless of which contribution kind you are adding, and regardless
of how any single feature was implemented. Per-kind parameters (caps, grammars,
error discriminants, method signatures) live in
[Extension Substrate Conventions](../reference/ui/extension-substrate-conventions.md);
the step-by-step authoring path is in [Write a Plugin](../how-to/write-a-plugin.md).

## One declaration model

A contribution is a `Declaration`
(`modules/app-agent-api/.../registry/Declaration.java`): a typed record of
*what is offered*. Every declaration carries the same shared cross-cutting
axes — a stable identity, a `Provenance` (who authored it and at what trust),
an audience, and its consumer hooks — plus a per-kind payload. A FE plugin is
not a separate model: it projects onto the same declaration shape
(`PluginContributions`) as a backend operation. Because the axes are shared,
any property the host reasons about uniformly — "who authored this?", "who may
see it?", "what consumes it?" — is asked once, of the base type, not re-derived
per kind.

## One composer, one registry

All declarations are composed into one `ContributionRegistry`, never a bespoke
per-feature catalog. Composition is the single point where contributions become
live: the registry is the authority, and a `DeclarationCatalog` is a typed view
*over* it, not an independent store. The practical consequence is that there is
one place to look to answer "what can this system do right now," and one place a
new kind of contributor plugs in.

## Four shared substrates

Every contribution kind reuses the same four cross-cutting seams
(`ContributionSubstrates`) rather than re-implementing them per kind:

- **Boundary** — the capability surface a contribution may reach through, and
  nothing wider.
- **Dispatch** — how an offered contribution is delivered to whatever consumes
  it.
- **Trust** — the `TrustLattice` that gates a contribution by who authored it
  and how risky the action is.
- **Lifecycle** — install, compose, and retire, with the same shape for a
  built-in catalog and a user-loaded plugin.

A new contribution kind is expected to *join* these seams, not fork a parallel
copy of them.

## The boundary law: the host owns truth

Contributions **project and feed**; they never hold authority. A contribution
declares an intent — "I can do X", "I expose reading Y" — and the host decides
whether, when, and how that intent is honored. The truth (what is installed,
what is permitted, what actually ran) lives with the host, and a contribution
reads it back as a projection. This is enforced, not merely advised: the
`host-owns-truth` discipline gate fails the build when a contribution type is
wired to own state the host should own.

## Trust is proportional to provenance; isolation to trust

Every contribution carries a `Provenance` trust tier (`TrustTier`: CORE,
TRUSTED_PLUGIN, UNTRUSTED_PLUGIN). Capability and isolation scale with that tier through the
`TrustLattice`, which gates an action on the combination of *who* authored it
and *how risky* the action is — a most-restrictive default covers any
unenumerated combination. A CORE contribution declared in JustSearch's own code
runs with full reach; an UNTRUSTED_PLUGIN frontend module loaded from a URL runs inside a
tightened compartment and is denied the capabilities (ambient selection,
clipboard, persistent sessions) whose misuse would be an exfiltration risk. The
isolation a contributor gets is a function of its trust, decided at composition
time, not patched in at each call site. Backend `UNTRUSTED_PLUGIN` operations are refused
in V1 because the backend sandbox is not implemented.

## A contribution is inert without a consumer

A declaration that nothing consumes is a **build failure**, not a silent no-op.
This is the substrate's keystone: every declaration must name at least one
consumer hook (the type system makes "zero consumers" unrepresentable), and the
`consumer-presence` discipline gate enforces it across the executable,
observable, and prompt axes. The `runtime-witness` gate carries the same
guarantee past the type system into runtime — it proves the named consumer
actually receives the contribution on a live stack, so a contribution cannot be
"wired but never delivered." Together they close the gap where a feature is
declared, compiles, and silently does nothing.

## The capability boundary: `host.*` and `@kernel/*`

A contribution reaches the system **only** through a declared, attenuable
capability surface — there is no ambient global it can reach around. Backend
contributions are handed a `host.*` interface scoped to what their trust tier
allows. Module-authored FE plugins import their capabilities by name from the
`@kernel/*` map (`@kernel/ai`, `@kernel/data`, `@kernel/platform`,
`@kernel/selection`, `@kernel/ui`, `@kernel/registration`); the loader resolves
those import paths to attenuated implementations at module-evaluation time, so
the *boundary is the import path itself*. A capability a plugin was not granted
is not a runtime denial buried in a callback — it is simply absent from the map
the plugin can import.

## The contribution kinds (axes)

The kinds of thing a contribution can be are a small, typed taxonomy — four
axis projections over the one declaration model:

- **EXECUTABLE** — it runs (operations, agent tools, workflows).
- **OBSERVABLE** — it is read or subscribed (resources, status surfaces).
- **LANGUAGE_MEDIATED** — the agent invokes it from its one tool window: a
  merged inventory of core operations, agent tools, external MCP tools, and
  workflows, each attributed by provenance and trust.
- **PRESENTATION** — it renders (surfaces, panels) within a constrained
  presentation vocabulary, so a contribution cannot become a second
  presentation authority.

Adding a *kind* extends this taxonomy additively; adding an *instance* of an
existing kind is just another declaration into the one registry. This is the
test for whether new extension work belongs here: a new instance should need no
new mechanism; if it appears to need one, it is either a genuinely new axis or a
sign the work is fighting the substrate.

## The declaration shape is one generated authority

The wire *shape* of a declaration follows the same single-authority law as its
state: it is **declared once and projected**, never hand-mirrored. The registry
endpoints (`/api/registry/operations`, `/api/registry/resources`) are served from
typed wire records (`UIOperationView`, `UIResourceView`); the FE consumes them as a
generated projection — record → JSON Schema → {TypeScript type, runtime Zod} — so
the FE type *and* its parse-boundary validator come from the one backend authority,
not a drifting hand-written `types/registry.ts`. The consumer list is a flat
`{consumerId, audience}` shape by construction (a `ConsumerView`, not a leaked
sealed-type discriminator), and the generated schema is *precise* — required and
non-null where the wire guarantees it (the `PreciseWire` marker) — so the validator
actually bites. This closes the drift class that shipped the `AuditPolicy` enum bug:
a hand-written FE copy can no longer silently disagree with the wire (tempdoc 560 §4c).

## Where to go next

- **Per-kind conventions, caps, and grammars** —
  [Extension Substrate Conventions](../reference/ui/extension-substrate-conventions.md)
  (reference: the parameter-level detail this model deliberately omits).
- **Authoring a plugin, step by step** —
  [Write a Plugin](../how-to/write-a-plugin.md).
- **The agent's one tool window and dispatch** —
  [Agent System Architecture](22-agent-system-architecture.md).
- **UI Host wiring of contribution surfaces** —
  [UI Host Architecture](07-ui-host-architecture.md).
