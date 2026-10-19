---
title: Developing the UI
type: how-to
status: stable
description: "Layout work without a backend, deterministic fixture captures, real backend development, and frontend verification."
---

# Developing the UI

## Quick start — layout without a backend

```bash
cd modules/ui-web
npm install          # first time only
npm run dev          # starts Vite
```

Open `http://127.0.0.1:5173/`. The Lit shell renders its chrome, rail, and surfaces with no backend,
but data views remain empty or show `Connecting…`. `?demo=true` does not supply data to the current
shell. For deterministic browser captures with data, use `jseval ui-shot --fixtures`; for real
search, indexing, and AI behavior, run a backend.

## When to use each mode

| Mode | Command | Backend needed? | Use for |
|------|---------|----------------|---------|
| Layout | `npm run dev` in `modules/ui-web` | No | Chrome, styling, and layout without data |
| Fixture capture | `python -m jseval ui-shot search-results --fixtures` from `scripts/jseval` | No; the harness serves Vite and intercepts API requests | Deterministic visual and accessibility checks |
| Real backend | `npm --prefix modules/ui-web run dev:all` from repo root | Starts one; the UI receives its port | Real search and integration work |
| Worktree FE | `node scripts/dev/serve-worktree-fe.cjs` from repo root | Borrows a running stack (read-only) | Validating this worktree's frontend without starting another backend |

When running Vite separately, its proxy discovers a running backend through the runtime manifest.
`VITE_JUSTSEARCH_API_PORT` (legacy alias `VITE_API_PORT`) overrides that discovery. See
[Using the UI](use-ui.md) for split-terminal and desktop workflows, and
[Capture UI Evidence](capture-ui-evidence.md) for the fixture capture commands.

## Visual verification with Chrome

Agents can use Claude Code's Chrome extension for real-time visual feedback:

```text
/chrome                          # enable in session
# then ask Claude to navigate to 127.0.0.1:5173 and inspect
```

Claude can take screenshots, click elements, read the DOM, execute JS in
the page context, and verify that UI changes look correct.

## Frontend verification commands

| Command | What it checks |
|---------|---------------|
| `npm run typecheck` | TypeScript compilation (no emit) |
| `npm run test:unit:run` | Vitest unit tests (single run) |
| `npm run lint` | ESLint rules |

**Minimum for a UI PR:** `typecheck` + `test:unit:run`. Use `jseval ui-shot` or
`jseval ui-check` for browser checks of affected search or navigation flows
(see [Capture UI Evidence](capture-ui-evidence.md)).

## Architecture overview

The frontend is a Lit web-components SPA served by Vite (dev) or bundled into the
Tauri desktop shell (prod). The entry point is `src/main.jsx` (named `.jsx` for
historical reasons; it bootstraps Lit, not React — see the decommission note at the
top of that file).

- **API layer** (`src/api/`): centralized `request()` function in `http.ts`.
  All backend calls go through here. Typed domain modules live in
  `src/api/domains/` (`search.ts`, `status.ts`, `settings.ts`, `suggest.ts`, etc.).
- **Shell + surfaces** (`src/shell-v0/`): Lit `<jf-*>` custom elements. The
  production chrome is `<jf-shell>` / `<jf-rail>` / `<jf-stage>`; view surfaces
  live in `shell-v0/views/*Surface.ts` and the declarative render layer in
  `shell-v0/renderers/`.
- **State**: Lit reactive controllers and signals (`@lit-labs/signals`) within
  `shell-v0/`. There is no Zustand store and no `src/hooks/` directory — both were
  part of the retired React stack (ADR-0032).

## Relationship to the backend

The UI talks to the Engine via REST (`/api/*`). The API layer routes to the
index half through in-process port calls (ADR-0049 — one JVM, no IPC). When the
index half is busy (indexing), search API responses can slow down. Use fixture
captures for deterministic frontend checks and a running dev stack for integration checks.

```text
Browser → Vite proxy → Engine (Javalin) → index-half port call → Lucene
```
