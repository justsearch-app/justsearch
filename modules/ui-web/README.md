# ui-web — JustSearch Frontend

TypeScript + [Lit](https://lit.dev/) web components frontend for JustSearch,
built with Vite. The production UI is the `shell-v0` custom-element chrome
(`<jf-shell>` + `<jf-rail>` + `<jf-stage>`) mounted from `src/main.jsx` — see
ADR-0032 (`docs/decisions/0032-fe-lit-web-components.md`) for why the earlier
React stack was retired.

Architecture: `docs/explanation/01-system-overview.md`,
`docs/explanation/07-ui-host-architecture.md`
Design system: `docs/explanation/10-ui-ux-design.md`

## Stack

- **TypeScript + Lit** — custom elements (`shell-v0/`), no virtual DOM framework.
- **Vite** — dev server, production build, and the local dev-stack API proxy
  (`vite.config.js`) that forwards `/api/*` to the backend.
- **Vitest** — unit tests (`happy-dom`).
- **Generated API client + wire types** — `src/api/generated/` is produced by
  `gen:api-client` / `gen:wire-schema-types` / `gen:shape-handlers` /
  `gen:liveness-constants` from the backend's wire contracts; the matching
  `check:*-regen` scripts fail CI if the generated output drifts from source.
- **Loopback-only backend** — the frontend only ever talks to the Head API on
  `127.0.0.1` (Hard Invariant: loopback-only network); there is no other
  network dependency for local dev.

## Source structure

```
src/
  api/           — HTTP client, SSE streams, generated API client + wire types
  shell-v0/      — the Lit chrome: chrome/, views/, state/, controllers/,
                   components/, themes/, plugin-api/, substrates/
  boot/          — API base resolution at app boot
  i18n/          — backend-served message catalogs (error/resource/surface/...)
  mocks/         — fixture data shared by tests and evidence capture
  persistence/   — client-side persistence helpers
  utils/         — shared utilities
  main.jsx       — app entry point
```

## Development

```bash
npm run dev
# Backend must be running (default port 33221, or set VITE_JUSTSEARCH_API_PORT)
```

The Vite dev-server proxy forwards `/api/*` and `/.well-known/justsearch/*`
requests to the locally-discovered backend.

## Commands

| Command | What it does |
|---------|-------------|
| `npm run dev` | Vite dev server (needs backend running) |
| `npm run build` | Production build |
| `npm run typecheck` | TypeScript type checking |
| `npm run test:unit:run` | Vitest unit tests (single run) |
| `npm run test:unit` | Vitest unit tests (watch mode) |
| `npm run lint` | ESLint |
| `npm run knip` | Dead-code / unused-dependency check |
| `npm run build:analyze` | Production build with bundle visualizer |

For visual/measurement verification of `shell-v0` changes, run
`jseval ui-a11y-gate` and `jseval ui-proportion-gate` (both capture fixture state).
