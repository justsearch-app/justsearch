---
title: "Frontend kernel — runtime contracts"
type: reference
status: stable
description: "Versioning axes + the capability-handshake contracts the FE runtime honors."
date: 2026-06-09
---

# Frontend kernel — runtime contracts

> **Graduated to canonical docs on 2026-06-09** from the retired `421` frontend-rewrite kernel
> draft's `10-kernel/` set (authored ~2026-05; the rewrite shipped per tempdoc 563). References to
> the draft's removed planning material (`slices/`, `20-systems/`, `archive/`, …) are historical.
> ADR links point to `docs/decisions/`; sibling kernel docs are in this folder.


The kernel begins each session by negotiating with the backend. Runtime
contracts are additive and honest: the frontend may degrade, but it must know
why.

## Capability Handshake

The handshake exchanges:

- shell protocol version
- supported primitive versions
- renderer capabilities
- locale and theme capabilities
- plugin framework version
- enabled feature flags
- server capabilities and constraints
- deprecation and incompatibility diagnostics

Agreement is soft-fail by default. Unsupported optional features degrade with a
visible reason. Required incompatibilities produce a hard shell failure with an
actionable diagnostic.

## Capability Changes

The backend may publish mid-session capability changes. The shell consumes those
changes as runtime truth, not as hints.

Capability changes must identify:

- affected primitive or subsystem
- previous state
- new state
- reason code
- observed time
- whether user action is required

## Streaming Contract (SSE liveness; retired process signalling is separate)

Streams are classified before implementation:

- state streams: replace or patch current observable state
- progress streams: describe one operation/job lifecycle
- history streams: append durable events
- language streams: deliver prompt output

Each class declares whether it supports replay, resume, ordering guarantees,
deduplication, and last-known-good display.

Do not copy a no-replay rule from one stream class into another without an
explicit decision.

State / progress / history streams (the `registry` / `surface` / `system`
StreamId kinds) adopt the universal SSE envelope: single JSON object as
`data:`, constant SSE event name `"frame"`, fields `streamId` /
`frameKind` / `seq` / `ts` / `payload` / `resumeToken`, plus the
lifecycle subkind discipline (`connected` / `snapshot` / `heartbeat` /
`reset` / `closing`). Resume is supported via `?since=<resumeToken>`.
Canonical reference: `05-streaming-envelope.md`. Decision record:
`../../../../decisions/0037-universal-sse-envelope.md`. Language streams are
explicitly out of scope and keep their cancellation / ordering
contracts independent.

## Dispatch Contract

The shell maps backend-described entries (a `(uischema, schema)` pair) to
custom-element renderers via tester-rank dispatch. The dispatch surface is
a runtime contract because plugins extend it and user-level overrides
(per-pane settings, plugin substitution UI) flow through it.

The dispatch surface declares:

- **Tester contract**: each renderer pairs with a tester function returning
  a numeric rank (-1 for no match; 0+ for match). Rank constants are
  defined by the substrate (basic / layout / structural = 1; specialized = 2).
- **Resolution rule**: highest positive rank wins; ties broken by
  registration order (earliest wins). Rank 100+ is reserved for
  user-selected forced overrides.
- **User override channel**: dispatch accepts an optional `userConfig`
  parameter. When `userConfig.rendererOverride` declares an explicit tag
  for the dispatch point, the dispatcher returns that tag directly without
  consulting tester ranks.
- **Failure containment**: when a renderer's tester returns a positive
  rank but the renderer's class throws on construction or `render()`, the
  dispatcher logs the error to the host's diagnostic surface (per
  `20-systems/07-extensions-renderers.md` §"Failure containment") and
  falls back to the next-highest-ranked renderer.

Substrate-level specification of the dispatch and renderer surfaces lives at
`slices/3a-1-5-renderer-contract-spec.md` (renderer side) and
`slices/3a-1-7-dispatch-user-override.md` (dispatch + userConfig side).

## Versioning

The shell has multiple version axes:

- backend API/schema
- primitive schema
- shell framework
- plugin API
- theme/token schema
- layout/settings schema

Axes are independent unless a feature explicitly couples them. Coupled features
must declare their compatibility matrix.

## Runtime Context

Every response whose behavior varies by runtime mode must declare that mode.
This includes persistence, inference runtime, install mode, policy mode,
feature availability, demo/mock state, and degraded worker state.

Frontend code must not infer mode from absence, timing, endpoint failure, or
file discovery when the backend can declare it.

The shipped substrate (slice 440) ships
`RuntimeContext(SystemMode systemMode, boolean automationEnabled)` as a
`STATE` × `SSE_STREAM` Resource at `core.runtime-context`. v1 carries
two mode-shaped dimensions with single authoritative sources
(`systemMode` from the `justsearch.eval.mode` system property;
`automationEnabled` from `ResolvedConfig.ui().automationEnabled()`).
Future dimensions (aiMode / installMode / policyMode) are deferred
until each has a clear single source — adding a slot is non-breaking
per the LSP soft-fail discipline. Settings mutations propagate live
through a `RuntimeContextConfigBridge` that subscribes to `ConfigStore`
change events and broadcasts a replace envelope when
`automationEnabled` toggles. Per-endpoint `runtimeContext` field
adoption is opt-in convenience now, not substrate work.
