---
title: Extension Substrate Conventions
type: reference
status: stable
description: "Parameter-level conventions of the extension substrate: profile-fork-from-active, selection-clears-on-surface-change, WhenExpression grammar, virtual-operation audience validation, template slot caps + trust attenuation, host.ai error discriminants, plugin file-size caps. The model behind these is in explanation/26."
---

# Extension Substrate Conventions

> Customization (themes, skins, layouts, plugins and macros) is frozen for now.
> What exists keeps working and gets fixes, but no new extension features are being added.

This is the **parameter-level** companion to
[The Extension Substrate](../../explanation/26-extension-substrate.md). The
explanation doc states the invariants (one declaration model, one composer,
the host-owns-truth boundary, trust-proportional isolation, consumer-presence);
this page records the specific conventions, caps, and grammars a contribution
author looks up. These are lookup values, not architecture — when a specific
number or grammar is load-bearing for your change, confirm it against the
cited test, which is the source of truth.

## Profiles (UserStateDocument V2)

The user state document holds a collection of profiles with one active. Each
profile carries layout, theme, pinned searches, keybinding overrides, and the
renderer's user-config slice. Cross-profile slices (acknowledged advisories,
recent commands, plugin settings) live at the document root.

**Convention — `createProfile(id, label, basedOn?)`:** when `basedOn` is
omitted, the new profile copies its slices from the **currently-active
profile**, not from a virgin built-in default. This matches the natural "fork
what I'm looking at" UX: a user who customizes their layout + theme then
creates a new profile expects those customizations to carry over.

The built-in `default` profile cannot be deleted and is the fallback target
when the active profile id becomes inconsistent (e.g., manual storage
tampering).

## Selection model

Selection is a first-class state slice. The discriminated `SelectionItem`
union has three kinds today: `search-hit`, `browse-node`, `plugin-item`.
Capabilities (`open`, `pin`, `export`, `ask-ai-about`, `reveal-in-explorer`,
`copy-link`) project as a comma-separated string into
`ShellContext.selectionCapabilities` for `when`-predicate consumption.

**Convention — selection clears on surface change.** When the active surface
changes (rail click, palette nav, deep link), the shell clears the selection.
The alternative (per-surface selection memo restored on return) was rejected as
harder to predict from the user's perspective.

**Convention — `inspectorState.setSelected` reads the optional `kind` field on
`SelectedItem`** and dispatches to the correct `SelectionItem` variant. Callers
that omit `kind` default to `search-hit` for back-compat with the original
single-kind shape.

## WhenExpression

The shell evaluator implements the VS Code when-clause grammar subset: logical
(`!`, `&&`, `||`), equality (`==`/`===`, `!=`/`!==`), numeric (`>`, `>=`, `<`,
`<=`), membership (`in`, `not in`), regex (`=~`), bare-key truthy.

**Convention — flat-key context.** Predicates reference keys directly on
`ShellContext`. No dotted-path access (e.g., write `selectionKind ==
search-hit`, not `selection.kind == search-hit`). The grammar follows VS Code
precedent.

**Convention — silently-false + WARN-once on parse error.** A malformed
predicate filters the contribution out and logs a single WARN per expression.
The evaluator never throws into the contribution-listing path.

**Membership operator semantics: `<value> in <containerKey>`.** The LHS is a
literal needle; the RHS is the key whose value is a comma-separated haystack.
Example: `export in selectionCapabilities` evaluates to true when
`selectionCapabilities` includes `"export"`.

## Virtual operations (agent-audience projection)

Plugin-contributed shell commands appear in the agent's tool list via the
LANGUAGE_MEDIATED axis. Tools must declare `audience: ["AGENT"]` (or `["USER",
"AGENT"]`) on the wire to be accepted by the backend store. Backend validation
is independent of the FE filter — the FE's `serializeVirtualOperationsForAgent`
emits the audience array, the backend's `AgentController.hasAgentAudience`
rejects entries without it.

**Convention — agent projection is opt-in.** Commands without a
`decorateCommandForAgent` call do NOT appear in the agent's tool list. The
decoration is the audience-review gate.

**Convention — invocation roundtrip.** When the agent calls a `vop_*` tool, the
backend emits a `tool_call_virtual` SSE event, blocks on a 30s future, and
accepts the FE's result via `POST /api/chat/agent/tool-result`. Timeout →
structured failure fed back to the agent as a regular `OperationResult.failure`.

## EmptyStateRegistry contexts

Empty states are a contribution axis. Each contribution declares a `context`
string (e.g., `palette-no-results`, `search-no-results`, `library-empty`) and
an optional `when` predicate. Consumers list contributions for their context,
the registry filters by `when` against the live ShellContext, and renders them
in priority order.

**Convention — context names are stable strings.** Future contributions extend
the namespace additively. Don't reuse a context name for a different consumer;
the registry treats them as distinct.

## Template slot grammar

`TemplateCatalog` uses Raycast-precedent slot syntax:

- Named arguments: `{argument name="topic" default="recent"}`
- Ambient bindings: `{clipboard}`, `{selection}`, `{primarySelection}`,
  `{date}`, `{time}`, `{datetime}`, `{day}`, `{uuid}`, `{currentSurface}`,
  `{activeProfile}`
- Escape: `{{` / `}}` produce literal `{` / `}`

**Convention — slot caps: soft 5, hard 8.** Above 5 the multi-prompt UX is
awkward; the runtime WARNs. Above 8 the parser throws. Multi-step palette
wizards are the intended escape hatch for richer flows.

**Convention — trust attenuation on ambient bindings.** UNTRUSTED templates
cannot bind `{selection}`, `{primarySelection}`, or `{clipboard}` (data-exfil
risk). The check happens at template registration time, not invocation —
composition over runtime gate.

## host.ai sub-interface

Plugins access LLM inference via two entry points:

- `host.ai.invokeShape(shapeId, body)` / `streamShape(...)` — stateless
  one-shot. Works for EPHEMERAL shapes.
- `host.ai.openSession(shapeId, sessionId?)` — session-bound. Works for
  PERSISTENT shapes. **UNTRUSTED plugins throw synchronously at openSession** so
  the wrong-tier surface is the call site, not a deferred error chunk.

**Convention — discriminated error envelope.** Error chunks carry
`payload.kind`: `'http-error' | 'parse-error' | 'session-closed' |
'transport-error' | 'denied'` with a structured detail.

## File-based plugin distribution

`scan_plugins` (Rust IPC) reads
`~/.justsearch/plugins/<id>/manifest.json` (cap 64 KB) + `plugin.js` (cap
1 MB). Oversize entries return `tooLarge: true` with empty manifest/source so
the FE skips + logs instead of OOM'ing.

**Convention — caps are enforced at the read boundary.** Do NOT remove the caps
to support large plugins; instead, design the plugin to stay under the limits
(split into multiple files loaded on demand via `host.fetch`).

## Where each convention is verified

| Convention | Test file |
|---|---|
| createProfile basedOn defaults to active | UserStateDocument.test.ts |
| Selection clears on surface change | whenExpressionIntegration.test.ts |
| inspectorState bridge reads kind | selectionState.test.ts |
| WhenExpression grammar + policy | whenExpression.test.ts |
| Virtual operation audience validation | AgentControllerAudienceTest.java |
| Virtual tool invocation roundtrip | AgentSessionVirtualToolTest.java + VirtualToolDispatcher.test.ts |
| EmptyStateRegistry context filter + when | EmptyStateRegistry.test.ts |
| Template slot caps + trust attenuation | TemplateCatalog.test.ts |
| host.ai error discriminants + UNTRUSTED throw | HostApiAi.test.ts |
| Plugin file-size caps | Rust read_capped (manual via 2 MB drop test) |
