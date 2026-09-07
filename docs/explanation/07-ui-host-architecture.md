---
title: UI Host Architecture
type: explanation
status: stable
description: "HeadlessApp, Lit shell-v0 structure, and Auto-Discovery."
---

# UI Host Architecture

JustSearch uses a "Sidecar" UI architecture. The "Backend" (`HeadlessApp`) and the "Frontend" (UI) are decoupled, allowing them to be developed and run independently.

## The Components

### 1. HeadlessApp (The Server)
*   **Class:** `io.justsearch.ui.HeadlessApp`
*   **Technology:** Java 25 + Javalin (Lightweight Web Framework).
*   **Port:** Usually explicit (default `33221` via `justsearch.api.port` / `JUSTSEARCH_API_PORT`), otherwise ephemeral (`0`) when no port is configured.
*   **Role:** Provides the REST API for the UI. It holds the active state, manages the Worker process, and handles AI orchestration via `AppFacade`.
*   **Startup Priority:** It attempts to start the `KnowledgeServer` first. If that fails (e.g., lock contention, missing JAR), it keeps the HTTP server up so the UI can render a deterministic error state:
    * `GET /api/status` includes `knowledgeServerStartError` and reports `indexState=ERROR`.
*   **Port Disclosure:** Prints `JUSTSEARCH_API_PORT=<port>` to stdout for scripts/shells (e.g. `run-headless-api.ps1`).

### 2. The Frontend (The Client)
*   **Location:** `modules/ui-web`
*   **Technology:** Vite; the `shell-v0` surfaces are Lit web components.
*   **Role:** A pure Single Page Application (SPA). It contains **no business logic**. It simply renders state provided by the API.
*   **Declarative presentation:** the core surfaces (Settings/Library/Help/Health) render through a typed *presentation kernel* — users author a surface's appearance/layout/behaviour as a declaration the kernel interprets, never code it executes (the truth/presentation cut keeps business logic team-owned). See [Frontend kernel — declarative presentation](../reference/ui/frontend-kernel/kernel/06-declarative-presentation.md).

### 3. The Shell (The Container)
*   **Location:** `modules/shell`
*   **Technology:** Tauri (Rust).
*   **Role:**
    *   Window Management (Resize, Drag, Blur).
    *   System Tray icon.
    *   **Sidecar Security:** It is responsible for spawning the `HeadlessApp` as a child process and killing it when the window closes.
    *   **Port Injection:** It captures `JUSTSEARCH_API_PORT=XXXX` from the backend's stdout and exposes it to the WebView via the Tauri command `invoke("api_port")`. The `window.justSearch.getApiPort()` bridge is a legacy JavaFX-shell artifact and is **not installed** by the Tauri shell.

#### Sidecar bundle contract (desktop)
For desktop runs, Gradle stages a runnable backend bundle into the Tauri resources directory:
* `:modules:ui:bundleSidecar` → `modules/shell/src-tauri/resources/headless/**`
* `:modules:ui:smokeSidecarBundle` runs a non-GUI smoke test against that staged bundle (backend boots; `/api/status` + `/api/health` respond).

## Service Discovery (How the UI finds the backend)

The UI resolves the backend base URL using `resolveApiEndpoint()` (`modules/ui-web/src/api/http.ts`), in this precedence order:

1. **URL override:** `?api_port=33221` (explicit testing override)
2. **Legacy bridge:** `window.justSearch.getApiPort()` (kept for parity with the old JavaFX shell; never matches in Tauri builds since the Tauri shell does not install the bridge)
3. **Tauri:** `invoke("api_port")` — the production desktop path. The Tauri command in `lib.rs` waits up to 15 s for the backend to emit its bound port on stdout.
4. **Vite env:** `VITE_JUSTSEARCH_API_PORT` (or legacy `VITE_API_PORT`) — used when the frontend is built against a fixed port at compile time.

If none of those resolve, the source is `unresolved` and the connection-attempt loop in `useApiConnection` will retry up to 10 times before showing "Unable to connect."

> **Note (2026-04-28):** Earlier versions of this chain included two
> additional steps — a loopback port-range auto-probe (`33221..33250`)
> and a Vite proxy fallback (empty base URL → Vite dev server proxies to
> the backend). Both were deleted by tempdoc 326 (`feat(ui-web):
> frontend dead code cleanup — Knip triage`, 2026-03-19). The deletion
> was a regression — Knip flagged the exports as unused and the agent
> extended that to "delete the functions." The Tauri-invoke path was
> restored after the sandbox validation in tempdoc 374 surfaced a
> "Connection Error" on every fresh install. Auto-probe and Vite proxy
> fallback are not currently restored; running `npm run dev` against a
> real backend without `VITE_JUSTSEARCH_API_PORT` set will fail to
> discover the port. `npm run dev:mock` is unaffected (MSW intercepts
> fetches in the browser).

Once resolved, the frontend performs a fast `GET /api/status` check and then polls for health periodically.

Current contract note:

- `/api/health` is a **contract-tested lifecycle gate** (schema v1) and uses HTTP `200` for `READY|DEGRADED` vs `503` for other states.
- `/api/status` remains the **richer** “what’s running?” payload (and includes the stable lifecycle subset for automation).

## API Structure (`LocalApiServer`)
The `LocalApiServer` exposes REST endpoints that map to controllers:
*   **Health/Status:** `GET /api/status`, `GET /api/health`
*   **Knowledge Status (worker snapshot):** `GET /api/knowledge/status` (used by bench harnesses; overlaps with `/api/status` but is focused on worker/index progress and queue counters)
*   **Search (UI path):** `POST /api/knowledge/search`
    * Supports `filters`, `facets`, `projection`, plus `sort` + `cursor` (TEXT-mode pagination).
    * Response may include `nextCursor` when more results exist.
*   **Suggest (autocomplete):** `GET /api/knowledge/suggest`
*   **Indexing:** `GET/POST/DELETE /api/indexing/roots`, `GET /api/indexing/suggested-roots`, `POST /api/indexing/reindex`, `POST /api/knowledge/ingest`, `POST /api/indexing/excludes/apply`
*   **Schema migration controls:** `POST /api/indexing/migration/start`, `/cutover`, `/rollback`, `/pause`, `/resume`
*   **Index GC (best-effort):** `POST /api/indexing/gc`
*   **Preview:** `GET /api/preview`
*   **AI (SSE):** `POST /api/summarize/batch/stream`, `POST /api/ask/stream`, `POST /api/summarize/hierarchical/stream` (plus legacy single-file `/api/summarize/stream` and non-streaming `POST /api/summarize`)
    * Streaming frames are parsed in the frontend via `modules/ui-web/src/api/sse.ts` (spec-correct SSE framing; tolerant of partial chunks and CRLF).
    * Streams may include a `rag_meta` event (retrieval mode/reason + truncation + chunk counts). See `docs/reference/contracts/search-and-rag-reason-codes.md`.
*   **Live Scan Progress (SSE, GET):** `GET /api/scans/{scanId}/progress` — backed by an in-memory `ScanProgressRegistry`; events `progress` / `complete` / `error`. Subscribe by `scanId` returned in the `KnowledgeIngestResponse.scanId` field of `POST /api/knowledge/ingest`. Closing the SSE connection propagates a gRPC cancel to the worker (T3 `CancelToken` substrate). Tempdoc 419 / T4. See `docs/reference/api-contract-map.md` for full payload shapes.
*   **Library Resolve-Hash (ADR-0028):** `POST /api/library/resolve-hash` — the *only* HTTP endpoint allowed to resolve a `pathHash` back to a filename. Diagnostic export endpoints MUST NOT call it (ArchUnit-enforced). Tempdoc 419 / T5.
*   **Diagnostics — Ingestion ledger:** `GET /api/diagnostics/ingestion/recent`, `GET /api/diagnostics/ingestion/summary` — privacy-safe ingestion outcome reads (path-hash only). Tempdoc 410 §12.
*   **Inference Control:** `GET /api/inference/status`, `POST /api/inference/mode`, `POST /api/inference/reload`, `POST /api/inference/detach`
*   **Policy:** `GET /api/policy/effective`, `GET /api/policy/validate`, `POST /api/policy/user/create`, `POST /api/policy/user/allowlist/pack-manifest/add`
*   **AI Install:** `GET /api/ai/install/manifest`, `GET /api/ai/install/status`, `POST /api/ai/install/start`, `POST /api/ai/install/cancel`, `POST /api/ai/install/repair`
*   **AI Packs:** `POST /api/ai/packs/preflight`, `POST /api/ai/packs/import`, `GET /api/ai/packs/status`, `GET /api/ai/packs/installed`
*   **AI Runtime (v3)**: `GET /api/gpu/capabilities`, `GET /api/ai/runtime/status`, `POST /api/ai/runtime/activate`, `POST /api/ai/runtime/deactivate`
*   **Settings:** `GET/POST /api/settings/v2`
*   **UI Ready:** `POST /api/ui/ready`, `GET /api/ui/ready`
*   **Diagnostics:** `POST /api/diagnostics/export`
*   **Worker Control:** `POST /api/worker/restart` (restarts the Knowledge Worker for embedding/apply scenarios)
*   **Debug:** `GET /api/debug/state`, `GET /api/debug/events`, `GET /api/debug/worker-log`, `GET /api/debug/dashboard`, `GET /api/debug/chunks`, `GET /api/debug/effective-config`

## REST contract boundaries (DTO direction)

JustSearch uses **two** API layers:

- **REST (`/api/*`)**: the stable, UI-facing contract owned by the Head process.
- **gRPC (internal)**: the Head ↔ Worker contract used for performance and strong typing.

Important direction rule (to prevent leaking internal proto churn into the UI layer):

- **UI REST controllers should not import gRPC proto DTOs** by default.
  - The Head should translate gRPC responses into **Head-owned** JSON DTOs (or plain maps) and expose those over REST.
  - This keeps the UI REST surface stable even if the proto evolves.

This is enforced by ArchUnit guardrails (see `UiApiGuardrailsTest`). A concrete example is Worker status mapping: `KnowledgeClient` exposes UI-friendly status snapshots to the Head so `LocalApiServer` doesn’t depend on proto DTO types.

## Network posture (local-only)
The Local API is intentionally **not** a network service.

* The HTTP server binds to **loopback only** (`127.0.0.1`) in both dev and prod.
* CORS is **loopback-only**:
  * In desktop/prod mode (`-Djustsearch.prod=true` / `JUSTSEARCH_PROD=true`), only the **desktop WebView origins** are allowed:
    * `tauri://<loopback-host>` (legacy/custom protocol, e.g. `tauri://localhost`)
    * `http(s)://tauri.localhost` (Tauri v2 / WebView2 asset origin)
  * In browser/dev mode, loopback browser origins are allowed:
    * `http(s)://localhost:<any>` / `http(s)://127.0.0.1:<any>` / `http(s)://[::1]:<any>`
    * plus the desktop origins above

## Session token security (desktop/prod mode)
In desktop/prod mode, JustSearch protects **state-changing endpoints** with a per-run session token:

- The backend generates a cryptographically secure token at startup and prints it to stdout as `JUSTSEARCH_SESSION_TOKEN=<token>` (`modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java`).
- The Tauri shell captures it (without logging it) and exposes it to the WebView via `invoke("session_token")` (`modules/shell/src-tauri/src/lib.rs`).
- `LocalApiServer` requires `X-JustSearch-Session: <token>` on **all non-GET** requests in prod mode (including `POST /api/ui/ready` and SSE streaming endpoints) (`modules/ui/src/main/java/io/justsearch/ui/api/LocalApiServer.java`).
- The UI’s `request()` helper resolves the token once and automatically attaches the header for non-GET requests (`modules/ui-web/src/api/http.ts`). Streaming helpers also resolve + attach it (`modules/ui-web/src/api/streams.ts`).

Rationale: loopback bind + CORS reduces exposure, but **CORS is not authentication**. The session token prevents arbitrary local webpages from issuing mutating requests against a running desktop instance.

## Error Handling
Since the backend is a separate process, the UI must handle "Disconnects" gracefully.
*   **Reconnecting State:** If `fetch()` fails (Network Error), the UI shows a "Reconnecting..." overlay.
*   **Retry Loop:** On initial connect it retries with a short backoff (currently ~5s, capped attempts) before showing a final “Unable to connect” screen.
*   **Health Monitor:** Once connected, it periodically re-checks `/api/health` (gate) and `/api/status` (rich state) and triggers reconnect if the backend disappears.
*   **Hot Reload:** This architecture allows developers to restart the Java backend (recompile) without refreshing the browser window.

Additionally:

- **Worker startup failures are observable**: `/api/status` includes a `knowledgeServerStartError` and uses `indexState=ERROR` when the Head is up but the Worker failed to start.
- **Typed HTTP errors for index operations**: `/api/knowledge/search` and indexing endpoints map gRPC error codes to meaningful HTTP statuses (e.g., 503/409/429) so the UI can distinguish “backend up, worker unavailable” from “request rejected”.

## Tauri Shell-Direct Operations

Most operator-facing destructive actions in the UI route through the
**OperationCatalog substrate** — surfaces mount `<jf-operation>`
(tempdoc 511), which calls the wire's `OperationClient.invoke()`,
which crosses HTTP to the Head. The catalog is the single source of
truth for label, risk, audience, and confirm ceremony.

A small number of operations bypass the catalog and call the Tauri
shell directly. This is **sanctioned** for actions that need
shell-process scope (Rust-level filesystem control, native process
lifecycle, OS integration) — capabilities the Head process cannot
provide because it is itself a child of the Tauri shell.

### Current shell-direct operations

- **Factory reset** (`prepare_delete_data` + `confirm_delete_data`,
  invoked from `SettingsSurface.deleteAllData`). Two-phase
  token-based protocol implemented in
  `modules/shell/src-tauri/src/lib.rs`. The reset must release
  Lucene index file locks, clear MMF segments, and trigger a planned
  app exit — all of which require the Tauri shell to drive, not the
  Head.

### When to use shell-direct vs the OperationCatalog

A new operation should route through the **OperationCatalog**
unless it satisfies one of these conditions:

1. **Requires shell-process scope.** Examples: factory reset (above),
   autostart toggle (filesystem ACLs), system-tray operations,
   native dialog invocation.
2. **Requires planned app exit.** The Head cannot reliably kill its
   own parent; the shell can.
3. **Bypasses Head availability.** Diagnostic recovery actions that
   must work when the Head is in a degraded state.

If none of the above apply, prefer the OperationCatalog: the
ceremony, audience gate, audit log, and substrate observability
come for free.

### Discipline

When adding a shell-direct call:

- Define a `#[tauri::command]` in `modules/shell/src-tauri/src/lib.rs`.
- Call it via `import('@tauri-apps/api/core').invoke(...)` from the
  surface, gated on `isTauriRuntime()`.
- Add a comment at the call-site citing this section and the
  shell-scope rationale.
- Do not invent a "shell-direct executor tag" for the
  OperationCatalog. The catalog covers Head-side operations only.
  Bridging the two would defeat both substrates' guarantees.
