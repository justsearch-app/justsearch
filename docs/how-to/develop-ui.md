---
title: Developing the UI
type: how-to
status: stable
description: "Demo mode (no backend), real backend mode, and frontend verification."
---

# Developing the UI

> **⚠️ Correction (2026-06, tempdoc 615): `?demo=true` no longer supplies data to the live Lit `shell-v0`.**
> The production `<jf-shell>` boot resolves its API base via `resolveApiEndpoint()` (`src/api/http.ts`), which
> has **no `?demo`→`'demo'` path** — so the demo handling in `src/api/domains/*` / `src/api/streams.ts` (and
> `src/mocks/fixtures.mjs`) is **orphaned from the retired React app** and never fires for the current shell.
> In practice: **view/chrome work needs only a served frontend (no backend); search/inspector/AI need the dev
> stack running** (AI also needs `ai_activate`). Re-wiring `?demo=true` into `resolveApiEndpoint` to restore a
> no-backend data mode is a worthwhile follow-up, but it does NOT work today. The demo-mode sections below are
> kept as the (currently non-functional) spec; for the live verification workflow + the measurement companion,
> load the **`/ui-check` skill**.

## Quick start — no backend needed (CHROME/LAYOUT ONLY — demo DATA is orphaned, see notice above)

```bash
cd modules/ui-web
npm install          # first time only
npm run dev          # starts Vite
```

Open `http://localhost:5173/`. The live Lit shell renders its chrome, rail, and surfaces with no backend
(empty/`Connecting…` data). `?demo=true` is inert for the shell-v0 boot — it does **not** load fixture data
(see the correction notice above). For real data, run the dev stack.

## When to use each mode

| Mode | Command | Backend needed? | Use for |
|------|---------|----------------|---------|
| Demo | `npm run dev` + `?demo=true` on the URL | No | Component work, styling, layout, accessibility, quick visual check, SSE simulation |
| Real backend | `npm run dev` (no `?demo`) | Yes (port 33221) | Integration testing, real search results |
| Worktree FE | `node scripts/dev/serve-worktree-fe.cjs` | Borrows a running stack (read-only) | Validating **this worktree's** FE in a browser without starting/owning a stack — picks a free port and auto-detects the running backend (tempdoc 618 §7) |

## Demo mode

`?demo=true` switches the app's API base to a virtual `demo/` origin, so every
fetch is served from in-repo fixtures instead of a backend. The app code is
unchanged — `src/api/streams.ts` and `src/api/domains/inference.ts` detect the
`demo/` base (and the `?demo_*` overrides below) and return fixture data. There is
no service worker.

**Fixtures:** `src/mocks/fixtures.mjs` (also exercised by `fixtures.test.ts`)
**Activation:** `?demo=true` on the dev URL — this is what `jseval ui-shot` uses by default
**Overrides:** `?demo_ai=true` (force demo AI streaming even against a real backend),
`?demo_error=<code>` (simulate an error), `?demo_truncated=1`, `?demo_rag=fallback`,
`?demo_stream_delay_ms=<n>` (streaming cadence)

The demo fixtures cover:
- `/api/status` — reports Ready, 500 indexed documents. Fixture includes `gpu` (GpuStatusView with VRAM, utilization, driver), `meta` (StatusMeta with RPC timestamps), `encoderProfiles` (Map of per-encoder ORT timing), and flat search config fields (matching backend `@JsonUnwrapped` `SearchConfigView`)
- `/api/knowledge/search` — returns 3 fixture results. Each hit includes `meta_source`, `meta_author`, `meta_category` in the `fields` map, and `provenance` (per-hit scoring breakdown with BM25/vector/fusion scores). Response includes `pipelineExecution` (timing and component status) and `indexCapabilities` (available features)
- `/api/preview` — mock preview content
- `/api/settings/v2` — default settings (dark theme, includes `vimMode`)
- `/api/inference/status` — offline AI
- `/api/indexing/roots` — empty roots
- `POST /api/ui/ready` — boot handshake

### Mock fixture shapes

**Status fixture** (`/api/status`):

```text
{
  gpu: { available, utilization, vramTotal, vramUsed, vramFree, driverVersion, deviceCount },
  meta: { workerRpcAtMs, workerRpcStale },
  encoderProfiles: { "<name>": { calls, phaseTotalUs: {}, ortMinUs, ortMaxUs, ortP50Us, ortP95Us, ortP99Us } },
  // flat search config fields (from @JsonUnwrapped SearchConfigView):
  hybridAlpha, bm25Boost, vectorBoost, freshnessBoost, spladeBoost, ...
}
```

**Search hit shape**:

```text
{
  fields: {
    title, file_path, file_kind, content_preview, language,
    meta_source?, meta_author?, meta_category?, meta_published_at?, ...
  },
  provenance?: {
    bm25Score, vectorScore, fusionScore, crossEncoderScore?,
    crossEncoderApplied, spladeScore?, spladeApplied, ...
  }
}
```

### Adding demo data for a new endpoint

Add the fixture to `src/mocks/fixtures.mjs` and return it from the demo branch of
the relevant `src/api/` domain module (e.g. `domains/<name>.ts`, or the demo path
in `streams.ts` for SSE). Demo mode handles streaming (SSE simulation), delays
(`?demo_stream_delay_ms`), and error simulation (`?demo_error=<code>`) directly in
`src/api/streams.ts` — no service worker involved.

## Visual verification with Chrome

Agents can use Claude Code's Chrome extension for real-time visual feedback:

```text
/chrome                          # enable in session
# then ask Claude to navigate to localhost:5173 and inspect
```

Claude can take screenshots, click elements, read the DOM, execute JS in
the page context, and verify that UI changes look correct.

## Frontend verification commands

| Command | What it checks |
|---------|---------------|
| `npm run typecheck` | TypeScript compilation (no emit) |
| `npm run test:unit:run` | Vitest unit tests (single run) |
| `npm run lint` | ESLint rules |
| `npm run test:gate` | Playwright gate smoke test (needs backend) |
| `npm run test` | Full Playwright E2E suite (needs backend) |

**Minimum for a UI PR:** `typecheck` + `test:unit:run`. Add `test:gate` if
the change affects search or navigation flows.

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
index half is busy (indexing), search API responses slow down — that's why MSW
mock mode exists for UI development.

```text
Browser → Vite proxy → Engine (Javalin) → index-half port call → Lucene
                ↑
         MSW intercepts here in mock mode
```
