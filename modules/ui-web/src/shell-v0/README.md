# Shell V0 (Lit web-components chrome)

`shell-v0/` is the production frontend UI for JustSearch: a Lit
custom-elements chrome with no virtual-DOM framework. The earlier React
stack (`App.tsx`, `GlassShell`, `ActivityRail`, `Stage`, the React
rail-view components) was decommissioned and fully removed (ADR-0032,
`docs/decisions/0032-fe-lit-web-components.md`).

Mount path: `src/main.jsx` side-effect-imports `./shell-v0/index.ts`
(`src/main.jsx:70`), whose barrel (`index.ts`) registers every `<jf-*>`
custom element, including the chrome itself: `<jf-shell>`, `<jf-rail>`,
and `<jf-stage>` are defined in `chrome/Shell.ts`
(`customElements.define('jf-shell', Shell)` at `chrome/Shell.ts:2397`,
`'jf-rail'` at `:2674`, `'jf-stage'` at `:2980`). Production boot then
mounts `<jf-shell>` directly (no React root, no `ReactDOM.render`).

## Layout

```
shell-v0/
├── chrome/              — the production chrome: jf-shell / jf-rail / jf-stage
│                           (chrome/Shell.ts), OverlayHost
├── views/                — surface views mounted into the stage (ActivitySurface,
│                           BrainSurface, search-v3/SearchV3View, ApiExplorerView, ...)
├── state/                — client-persisted state stores (NavigationJournal,
│                           UserStateDocument)
├── controllers/          — behavior controllers (AgentSessionController,
│                           ReasoningController, liveRuns, draftPersistence)
├── components/            — generic reusable Lit components (Form, StatusCard,
│                           ActionButton, Table, Button, ConfirmDialog, ...)
├── renderers/             — JSON Forms renderer set (controls/, layouts/) plus
│                           the resource-view renderer registry; includes the
│                           AUTO-GENERATED component-vocabulary.generated.ts
│                           (see below)
├── themes/                — design tokens, default theme CSS, the
│                           React-bridge CSS (`app-bridge.css`, retained as the
│                           semantic-token alias layer), authorable-component /
│                           presentation-schema validation
├── display/                — display-layer helpers (authoritySpace, facts,
│                           format, landmarks)
├── plugin-api/             — third-party plugin host API contract (HostApiImpl,
│                           CorePlugin, KernelResolver, capabilities/)
├── plugins/                — first-party bundled plugins (token-editor/)
├── substrates/              — kernel substrate primitives ("plugins request,
│                           kernel renders"); has its own README — see
│                           `substrates/README.md`
├── shell/                  — Lumino-backed dock + pane registry (a distinct
│                           `Shell` class from `chrome/Shell.ts`; exported as
│                           `Shell`/`LitWidget` from `index.ts`)
├── layout/                  — declarative layout manifest (LayoutManifest)
├── commands/                — command palette + context-action / empty-state /
│                           inspector-tab registries
├── router/                  — URL projection and intent routing between surfaces
├── streaming/                — SSE envelope stream handling (EnvelopeStream,
│                           EnvelopeStreamPool, LivenessWatchdog, MultiplexedStream)
├── operations/               — operation invocation client + authorization
│                           broker (ActionLedgerClient, OperationClient)
├── handshake/                — CapabilitiesHandshake — negotiates `host.*`
│                           sub-API contract versions with the backend
├── aggregate-substrate/       — aggregate-surfacing dispatch strategies
│                           (bootstrapped once at barrel load)
├── primitives/                — base custom-element class (JfElement) +
│                           adaptive layout primitives (bar/density/spacing)
├── projections/               — bounded projection helpers
├── strategies/                 — diagnostic-channel / subscription strategies
├── hooks/                       — small reusable hooks (resolvePathLazy)
├── hover/                       — HoverPreviewHost
├── demo/                        — standalone debug-route demos gated by
│                           `main.jsx` URL params (`?shell-demo=1`, etc.)
├── utils/                       — shell-v0-local shared utilities
└── __tests__/                    — cross-cutting tests (custom-element tag prefix)
```

## Key patterns

- **Custom-element views.** Each routed surface (`views/*.ts`) is a
  standalone Lit custom element mounted into `<jf-stage>`. All route
  surfaces except the default landing surface are lazy-loaded on first
  navigation (`views/lazySurfaceRegistry.ts`, referenced from
  `index.ts:52-58`); the default landing surface (`SearchV3View`,
  `views/search-v3/SearchV3View.ts`, tag `jf-sv3-window`) is imported eagerly, transitively via
  `chrome/Shell.ts` (`index.ts:61-65`).
- **Search.** `core.search-v3-surface` is the main window. Search mode retrieves files;
  Ask, Structured and Delegate share its composer, transcript and document reader.
  The retired Search, Agent and unified-chat surface ids resolve directly to Search.
- **State stores.** Client-persisted state lives in typed documents
  under `state/` (`NavigationJournal.ts`, `UserStateDocument.ts`), not
  in component instance fields.
- **Generated component vocabulary.** `renderers/component-vocabulary.generated.ts`
  is auto-generated by `scripts/ci/gen-component-vocabulary.mjs` — the
  closed set of every `jf-*` host element a user-authored Presentation
  Declaration may reference (file header,
  `renderers/component-vocabulary.generated.ts:1-8`). Regenerate with
  `node scripts/ci/gen-component-vocabulary.mjs`; do not hand-edit.
  `npm run check:component-vocabulary` (`modules/ui-web/package.json:28`)
  fails CI if it drifts.
- **Two distinct "Shell" classes.** `chrome/Shell.ts` is the production
  chrome (`<jf-shell>`). `shell/Shell.ts` is an unrelated Lumino-backed
  dock + pane registry, exported as `Shell`/`LitWidget` from
  `index.ts` (`index.ts:100-111`). Don't conflate the two when
  searching for "Shell".
- **substrates/ has its own README** (`substrates/README.md`) covering
  the substrate contract in depth — not duplicated here.

## Pointers

- Architecture: `docs/explanation/07-ui-host-architecture.md`
- Design system: `docs/explanation/10-ui-ux-design.md`
- Why Lit over React: ADR-0032 (`docs/decisions/0032-fe-lit-web-components.md`)
- Module-level frontend README (stack, commands, source structure):
  `modules/ui-web/README.md`
