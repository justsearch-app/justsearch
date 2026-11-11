# `shell-v0/substrates/` — kernel substrate primitives

Per Tempdoc 543 §3 and §13.2: this directory holds the kernel
substrate primitives that frame "plugins request, kernel renders." Each
substrate is a typed data shape + a small set of pure-ish helpers; no
substrate paints UI directly (chrome components in `../components/`
do that). Substrates may depend on `../primitives/` and on each other
where the dependency graph is acyclic.

Refreshed 2026-05-24 per Tempdoc 543 §28 W1 (autonomous run 3).

## Substrates shipped

### `actions/` — §3.C Action substrate

Canonical invocation primitive. Each Action carries `id`, `title`,
`appliesTo` (Addressable kinds; null = global), `when` (scope
predicate), `handler` (returns an `Effect`), `provenance` (required
per §21.A2). Per §21.B the substrate absorbed legacy
`registerShellCommand` + `projectOperationsToCommands`; legacy
`shell.*` / `op.*` ids route to `core.action.*` via
`resolveActionIdFromCommandId` in `commands/CommandRegistry.ts`.

`applyEffect(effect, invokedBy, originator?)` is the closed-union
dispatch table: 16 kinds (6 original + 8 §21.D UIEffect v2 + 2 §25.β5
DataEffect arm). §32 R-P4 routes the DOM-event kinds through a single
`dispatchDomEvent(name, detail)` helper.
Exhaustiveness is enforced by the `never`-typed default — adding a
kind to `effect.ts` without updating the dispatch + inverse derivation
fails TS compilation.

`invokeAndApply(id, args, addressable, ctx)` is the convenience
end-to-end path. The optional `ctx: KernelCtx` (§25.β3) exposes
`elicit` + `proposeEffect` so handlers can ask mid-invocation
questions or stage Pending effects for review.

### `effect.ts` — Effect closed discriminated union

Shared by `actions/` (dispatch) and `effects/` (journal). Per §M4
follow-up: the union is closed at build time; future kinds extend by
additive type widening.

Current kinds:
- `noop`, `navigate`, `open-pane`, `close-pane`, `toast`,
  `invoke-operation` (original).
- §21.D UIEffect v2: `set-selection`, `clear-selection`,
  `focus-element`, `scroll-to`, `open-modal`, `close-modal`,
  `copy-to-clipboard`, `set-form-value`.
- §25.β5 DataEffect arm: `data-result`, `data-error`.

### `effects/` — §13.2.2 Effect Journal substrate

Append-only typed log. Every `applyEffect` writes a `JournalEntry`
carrying the effect, invoker `Provenance`, ISO timestamp, derived
inverse, and (§21.E) `originator: 'user' | 'agent' | 'system'`.
Per-Pending entries carry `pendingOutcome: 'accepted' | 'rejected'`
when they originated from a `proposeEffect` lifecycle event.

Consumers: undo cursor (`undoLastEffect`), Activity (its feed and the
selected record's details), per-originator filter
(`listJournalByOriginator`), macros (replay via `runMacro`).

Cross-session persistence via localStorage (500-entry LRU cap).

### `pending-effects/` — §14.4 / §21.E PendingEffect substrate

The "propose → review → accept/reject" pattern the field converged on
(Cursor / Continue.dev / Copilot Workspace / Anthropic Operator).
`proposeEffect(effect, invokedBy, originator='agent', opts)` registers
a Pending; `acceptPending(id, applyFn)` dispatches via the injected
applyFn and records a `pendingOutcome: 'accepted'` entry;
`rejectPending(id)` records `pendingOutcome: 'rejected'` without
dispatching. §25.δ6 adds `proposeEffectSequence` for grouped
proposals (accept/reject together).

Chrome: `<jf-pending-effect-queue>` floats lower-right.

### `evaluationContext/` — §13.2.1 EvaluationContext substrate

Layered projection: `Scope ∪ TargetFacts(addressable) ∪
EnvironmentSignals`. WhenExpression predicates evaluate against the
result. Projector registry is per-`AddressableKind`; projectors are
pure and the composer memoizes per `(scopeVersion, addressable.kind,
addressable.id)`.

Production projectors:
- `searchResultProjector.ts` — first live projector (§25.ζ#6 + §28.W2
  consumer wiring). Projects `kind: 'search-result'` Addressables.

§25.β5 data-result cache: `setLatestDataResult` /
`getLatestDataResult` lets WhenExpressions branch on the latest
operation return value. Side-write bumps scopeVersion so predicates
re-evaluate.

### `addressable.ts` — `Addressable` discriminated union

`{kind, id, payload}` where `kind: AddressableKind` is closed-extensible
via TypeScript module augmentation (same pattern 511 uses for
SurfaceContextKind). Plugins introducing a new kind extend via
augmentation + register a projector via the manifest's
`factsProjectors` entry.

### `scope/` — §3.B Scope substrate

ScopeSnapshot serialization + restoration. Per §25.γ1 the
`restoreScope` mapper is table-driven (SCOPE_FIELDS coordinator).
`activateProfile` calls `restoreScope` + a defensive
`bumpScopeVersion` per §25.α3.

### `profiles/` — §13.2.3 / §13.6 Workspace Profiles substrate

A WorkspaceProfile is `{id, label, enabledManifestIds, scope,
inheritsFrom?}`. Activation: uninstall profile-scoped manifests not
in target set; install missing target manifests via registered
factories; `restoreScope`; `bumpScopeVersion`. Inheritance is set
arithmetic + child-wins scope merge per §13.6 #4.

Persistence to localStorage. UI: SettingsSurface developer panel
(includes §25.ζ#4 inheritance picker).

`diff.ts` — §25.δ4 pure `diffProfileActivation(targetProfileId)`
returns typed diff (`manifestsToInstall`, `manifestsToUninstall`,
`manifestsUnchanged`, `scopeDelta`) without activating.

### `manifest/` — §13.2.3 ContributionManifest substrate

A typed data shape for all of a contributor's contributions.
`installContributionManifest` is table-driven (§25.γ2 COORDINATORS) —
each contribution kind has an (install, rollback) coordinator row.
Adding an 11th kind is one row.

`profileBinding: 'profile-scoped'` auto-registers a Profile factory
per §25.γ3. `effectInverses` (§25.γ5) lets plugins ship per-operation
declarative inverses; the effects substrate consults via the
`setEffectInverseLookup` hook installed at module load.

`installedAt` is stamped at install site (§25.α7), not in the
`makePluginProvenance` helper.

### `elicit/` — §14.4 / §25.β3 mid-invocation user input

`elicit(opts)` returns `Promise<unknown | null>`; dispatches
`jf-elicit-request`; `resolveElicit(id, value)` / `cancelElicit(id)`
resolve the promise. Chrome: `<jf-elicit-host>` modal with embedded
`<jf-form>`. Threaded into Action handlers via the `ctx.elicit` field
of `KernelCtx`. Aligns with MCP `sampling.elicitInput`.

### `consent/` — §14.4 / §25.β4 capability consent

`recordConsent(contributorId, capability, decision)` where
`decision: 'allow-once' | 'allow-always' | 'deny'`. `checkCapability`
/ `isAllowed` / `consumeOnce` / `revokeConsent` for runtime gates.
`requestCapability(opts)` opens a chrome prompt and returns the user's
decision as a Promise. Chrome: `<jf-consent-host>` per-request banner.

allow-always + deny persist to localStorage; allow-once is in-memory
(decays at session exit).

`SettingsSurface` (§28.W3) renders the central permissions
management list using `listAllConsents` + `revokeConsent`.

### `macros/` — §14.3 δ3 / §25.δ3 Replay-as-Macro

`defineMacro({id, label, effects})` registers a macro + auto-registers
as a palette Action under `core.action.macro.<id>`. `runMacro(id)`
dispatches each effect in sequence via `applyEffect`. Persists to
localStorage. Activity's details (§28.W5) let journal records be chosen
and saved or exported in journal order.

## Layer placement (per §19 KCS direction)

Each substrate's exports correspond to a future capability-module
shape under the three-layer KCS direction:
- `useProvenance(contributorId)` — primitives/provenance.ts
- `useScope()` — substrates/scope/
- `useActions({addressable, scope})` + `invokeAction(...)` —
  substrates/actions/
- `useEvaluationContext({addressable, scope?})` —
  substrates/evaluationContext/
- `useWorkspaceProfile()` — substrates/profiles/
- `useContributionManifest()` — substrates/manifest/

The current module-level exports are the "before" of the KCS bridge.
A future tempdoc lifts them into the kernel-capability-substrate
shape without changing call-site semantics.

## Cross-cutting principles

1. **Plugins request, kernel renders.** Substrates expose typed data
   shapes that contributors populate; the kernel owns the rendering /
   dispatching / journaling code. No substrate accepts an open
   callback that paints chrome.
2. **Closed unions over open extensibility.** `Effect`,
   `AddressableKind`, `ConsentDecision`, `EffectOriginator`,
   `PendingEvent` are all closed discriminated unions. TypeScript
   exhaustiveness in switch tables catches missing handlers at compile
   time. New kinds extend by additive type widening.
3. **Provenance is uniform.** Every contribution to every registry
   carries a `provenance: Provenance` (§21.A2 made it required). The
   `resolveProvenance` fallback resolver retired.
4. **Substrate write before dispatch.** `recordEffect` runs BEFORE
   `applyEffect`'s chrome dispatch so a failed dispatch still leaves
   an audit trail.
5. **Idempotent boot.** `restoreJournalFromStorage`,
   `restoreProfilesFromStorage`, `restoreConsentFromStorage`,
   `restoreMacrosFromStorage`, `bootSearchResultProjector` all guard
   against second-call no-ops so HMR + StrictMode + chrome
   re-attachment don't corrupt state.

## Adding a new substrate

1. Module under `substrates/<name>/index.ts`. Export types + the
   registry / pure helpers. Default-export NOTHING (consumers
   named-import).
2. Tests at `substrates/<name>/<name>.test.ts`. Cover the substrate's
   own invariants in isolation; cross-substrate integration belongs
   in the consumer's test.
3. If the substrate persists state, factor through
   `primitives/storage.ts`'s `safeLocalStorage` so headless / Safari
   Private don't crash.
4. If the substrate notifies listeners, use `primitives/notify.ts`'s
   `notifyAll` / `notifyAllWith<T>` so the swallow-one-bad-subscriber
   semantic is consistent.
5. If the substrate ships chrome, put the Lit element under
   `../components/` and mount it from `Shell.ts`. Substrates and
   chrome are physically separated.
6. Update THIS README with a one-paragraph block above.

## What this layer does NOT own

- Lit UI rendering (chrome components live in `../components/`)
- Backend API clients (live in `../../api/`)
- Plugin loading / sandboxing (lives in `../plugin-api/`)
- Domain state stores (live in `../state/`)
- Renderer dispatch / x-ui-renderer wiring (lives in `../renderers/`)
- Aggregate-strategy dispatch (lives in `../aggregate-substrate/`)
- Surface activation lifecycle (lives in `../router/`)

The substrate layer is the kernel's "what shapes exist" layer. Other
modules consume those shapes.
