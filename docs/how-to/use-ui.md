---
title: Using the UI
type: how-to
status: stable
description: "Running dev modes and accessing debug dashboards."
---

# Using the UI

This guide explains how to run, use, and debug the JustSearch user interface.

Related docs:
- UI workflow readiness checklist + known issues: `docs/reference/ui-user-readiness.md`
- Troubleshooting schema mismatch / “reindex no-op”: `docs/reference/index-schema-mismatch-reindex-noop.md`
- Schema migration architecture overview: `docs/explanation/11-index-schema-migration.md`

## 1. Quick Start

### Developer Mode (Browser)
For rapid UI development, you can run the UI in a normal browser (Vite) against the real local backend.

#### One command (recommended)
This starts both the backend and the Vite dev server together:

```powershell
npm --prefix .\modules\ui-web run dev:all
```

What it does:
- Starts the backend (`:modules:ui:runHeadless`) on a free port near `33221`.
- Starts the frontend at `http://localhost:5173`.
- Sets `VITE_JUSTSEARCH_API_PORT=<port>` (and legacy `VITE_API_PORT=<port>`) so the UI connects immediately (no `?api_port=` needed).
- If you started the backend separately (and didn’t set the Vite env var), the UI can also auto-discover by probing `33221..33250` on loopback and validating the `/api/status` payload.

> Note: On macOS/Linux, use `npm --prefix ./modules/ui-web run dev:all`.

#### Split terminals (manual)
For more control (or when debugging backend startup), run the Frontend and Backend separately.

**Terminal 1 (Backend):**

```powershell
.\scripts\dev\run-headless-api.ps1
```

**Windows note (memory safety):** If you hit Node/Vite “paging file / commit” issues during backend builds, you can skip bundling the web UI by passing `-PskipWebBuild=true` to Gradle. The `run-headless-api.ps1` script already uses this by default.

**Terminal 2 (Frontend):**

```powershell
cd modules\ui-web
npm run dev
```

Open `http://localhost:5173`. The frontend resolves the backend using `resolveApiEndpoint()` and will typically
connect automatically (via `VITE_JUSTSEARCH_API_PORT` / `VITE_API_PORT`, or by probing `33221..33250` on loopback).

Tip: If you started the backend on a non-default port, you can force the UI to use it via:

```text
http://localhost:5173/?api_port=33221
```

You can also set `VITE_JUSTSEARCH_API_PORT` when running `npm run dev`.
(`VITE_API_PORT` is accepted as a legacy alias.)

### Integrated Mode (Tauri)
To test the full desktop experience (with native window chrome, drag regions, etc.), run the Tauri shell.

**Prereq (stage the bundled backend into the Tauri resources folder):**

```powershell
.\gradlew.bat --no-daemon -PskipWebBuild=true :modules:ui:bundleSidecar
```

Then run:

```powershell
npm --prefix .\modules\shell run tauri dev
```

Notes:
- In Tauri mode, the shell **spawns** the Java backend and the UI discovers it via `invoke("api_port")`
  (no `?api_port=` required).
- The shell starts the backend with `-Djustsearch.prod=true`, so the Local API CORS allowlist is in **desktop/prod**
  mode (only `tauri://<loopback-host>` and `http(s)://tauri.localhost` are allowed). A browser UI
  (`http://localhost:5173`) cannot directly call that backend by design.
- The current dev supervisor is **Windows-first** (it expects `runtime/bin/java.exe` and Windows classpath separators).

### Build the desktop installer (Windows)

```powershell
# NOTE: Most developer machines do not have Microsoft "SignTool" installed.
# For local builds, use --no-sign (release signing is handled separately).
npm --prefix .\modules\shell run tauri -- build --bundles nsis --no-sign
```

Artifacts are produced under `modules/shell/src-tauri/target/release/bundle/` (primarily `nsis/`).

### Sidecar bundle smoke test (no GUI)

```powershell
.\gradlew.bat --no-daemon -PskipWebBuild=true :modules:ui:smokeSidecarBundle
```

This launches the staged headless bundle and asserts basic readiness (`/api/status` and `/api/health` with Worker READY).

## 2. Debugging Tools

The app includes built-in introspection tools reachable via browser.

> Replace `33221` with your actual API port. `run-headless-api.ps1` prints `JUSTSEARCH_API_PORT=<port>` to stdout,
> and `npm --prefix modules/ui-web run dev:all` echoes the backend logs as it starts.

| Endpoint | URL | Purpose |
| :--- | :--- | :--- |
| **Dashboard** | `http://localhost:33221/api/debug/dashboard` | Visual status of Worker, Index, and Memory. |
| **State JSON** | `http://localhost:33221/api/debug/state` | Raw JSON dump of the entire internal state. |
| **Event Log** | `http://localhost:33221/api/debug/events` | Ring buffer of the last 50 system events. |
| **Worker Log Tail** | `http://localhost:33221/api/debug/worker-log` | Quick peek at recent worker logs (best-effort). |
| **Health** | `http://localhost:33221/api/health` | Lightweight backend health check. |
| **Inference Status** | `http://localhost:33221/api/inference/status` | Current AI mode + queues + effective runtime model/context (best-effort); includes external server adoption diagnostics when applicable. |

Note: `GET /api/debug/effective-config` exists for "effective config" debugging. See `docs/explanation/06-configuration-ssot.md` for configuration source-attribution rules.

## 3. Common Tasks

### Adding a Folder to Index
1.  Navigate to the **Library** view (Folder icon).
2.  **Browser Mode:** Click "Add Folder" and paste the path (native picker is unavailable).
3.  **Tauri Mode:** Click "Add Folder" to open the OS dialog.
4.  Monitor the **Health** view to see the document count rise.

### Forcing a Re-Index
If the index seems out of sync:
1.  Go to **Library** view.
2.  Click **Reindex All** (top-right).
3.  *Note:* This does not delete the index; it marks all files as "dirty" to force re-evaluation.

You can also reindex a single folder from the Library list (refresh icon per row).

### Summarizing and Asking Questions (AI)
The right panel (**Inspector Panel**) contains the preview + AI tools.

**Single-file**
1. Click a result row (or the checkbox) to open the Inspector for that file.
2. Use **Summarize** to stream a summary, or type a question in the Inspector input and press **Enter** to stream an answer.

**Multi-file**
1. Use the **checkboxes** in the result list to build a multi-selection.
2. Use **Summarize** / **Ask** to run across the selected files.

Notes:
- Multi-file summarize runs **full coverage** by default (slower). For best performance, keep it **≤ 3 files**.
- You can cancel in-flight streaming from the Inspector when the **Cancel** button is shown.
- Keyboard tips:
  - `Enter` on the results list commits selection and opens the Inspector.
  - `Ctrl/Cmd+Enter` opens the file (Desktop) or a best-effort backend preview (Browser).
  - `Ctrl/Cmd+Shift+P` opens the **Action Panel**.

### Resetting the App
If `IndexingLoop` is stuck or the index is corrupted:
1.  **Stop** all processes.
2.  **Delete** `%LOCALAPPDATA%\JustSearch\index`.
3.  **Delete** `%LOCALAPPDATA%\JustSearch\jobs.db`.
4.  **Restart**.

### Troubleshooting: GPU usage doesn't stop after a summary

It’s easy to misread GPU telemetry as “the summary is still running”.

- **Expected (normal)**:
  - `llama-server` often keeps **VRAM allocated while idle** so the next request is fast.
  - GPU **utilization** should drop close to 0% when the request finishes (other apps can still use the GPU).
- **Bad (needs intervention)**:
  - You see **multiple** `llama-server` processes, or the PID **keeps changing** every second → this usually means a stale/orphan `llama-server` is still bound to the port and the Head is repeatedly trying (and failing) to start another one.

Useful commands (Windows):

```powershell
# See llama-server processes (PID should be stable)
Get-Process -Name llama-server -ErrorAction SilentlyContinue | Select-Object Id,StartTime,CPU

# See who is listening on the llama-server port (default 8080)
Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -eq 8080 } | Select-Object LocalAddress,LocalPort,OwningProcess

# Per-process GPU snapshot (who is actually using SM%)
nvidia-smi.exe pmon -c 1

# Kill an orphan llama-server if needed
Stop-Process -Id <pid> -Force
```

Note: JustSearch can adopt an already-running `llama-server` on the configured port (instead of spawning another and entering a restart loop). For safety, adoption verifies the server via `GET /props` (not just `GET /health`). If `GET /props` is missing/unparseable, adoption is refused by default; for dev-only workflows you can set `-Djustsearch.inference.external.allow_health_only_adoption=true` to fall back to health-only adoption.

Tip: `GET /api/inference/status` is usually the fastest way to see if the app thinks it is Online/Indexing/Offline, plus any external-server diagnostics when adoption is in use.

## 4. AI Agent Usage Notes

When using browser automation tools (Playwright, Puppeteer, or MCP browser tools) to interact with the UI, be aware of these issues and workarounds.

### Search Input Doesn't Trigger Results

**Problem:** Programmatic typing in the search box may not trigger search results because the search input's web-component handlers rely on specific DOM events (`input`, `keydown/keyup`) that browser automation may not fire correctly.

**Solution:** Use URL query parameters instead of typing:

```text
http://localhost:5173/?q=search_term

# Optional deterministic override (avoid discovery delays in automation):
http://localhost:5173/?api_port=<port>&q=search_term
```

The app reads `?q=` from the URL and sets the query state directly, bypassing the input event chain.

### Q&A Panel Doesn't Open After File Selection

This issue is **fixed**: clicking a checkbox now also activates the row so the Inspector opens immediately (no “checkbox then click row” dance).

### Accessibility Snapshot Limitations

**Problem:** Browser accessibility snapshots (used by automation tools) don't capture:
- Dynamically rendered markdown content (LLM responses)
- Streaming text updates
- Some dynamically-rendered web-component (Lit) content

**Workarounds:**
1. Use **network request inspection** to verify API calls succeeded
2. Check **console messages** for errors
3. Use **backend logs** to verify request processing
4. For visual verification, ask the user to confirm what they see

### Full-Stack Development Workflow

For AI agents working on this project:

```powershell
# Start backend + frontend together (recommended; cross-platform)
npm --prefix .\modules\ui-web run dev:all

# Windows convenience wrapper (also starts both)
.\scripts\dev\dev-all.ps1

# Or start separately for more control:
# Terminal 1: Backend (rebuilds on each start unless -SkipBuild)
.\scripts\dev\run-headless-api.ps1

# Terminal 2: Frontend (hot-reloads automatically)
cd modules\ui-web
npm run dev
```

**Important:** Java backend changes require restart. Frontend (Lit/Vite) hot-reloads automatically.

### Verifying Backend Changes

After modifying Java code:
1. Stop the dev stack:
   - If you used `npm --prefix modules/ui-web run dev:all`, press **Ctrl+C** in that terminal.
   - If you used `run-headless-api.ps1`, press **Ctrl+C** in that terminal.
2. Rebuild (Java-only, skips web bundle): `.\gradlew.bat :modules:ui:installDist -PskipWebBuild=true --no-daemon`
3. Restart: `.\scripts\dev\run-headless-api.ps1 -SkipBuild`

**One distribution:** lane F stage A item A13 deleted the Worker distribution. `:modules:ui:installDist` builds the only tree the backend launches from, and the index half is in its `lib/` — there is no second artifact that can drift out of sync.

### API Testing Without UI

Test backend endpoints directly:

```powershell
# Check status
Invoke-RestMethod -Uri http://localhost:33221/api/status

# Search
$body = '{"query": "alice", "limit": 10}'
Invoke-RestMethod -Uri http://localhost:33221/api/knowledge/search -Method POST -Body $body -ContentType "application/json"

# Debug chunk info (for RAG investigation)
Invoke-RestMethod -Uri "http://localhost:33221/api/debug/chunks?docId=d:\path\to\file.txt"

# Inference status
Invoke-RestMethod -Uri http://localhost:33221/api/inference/status

# Hot-apply persisted inference settings (model/context/gpu layers).
# Note: restarts llama-server only if already Online; if Online AI adopted an external llama-server instance, restart is rejected (use /api/inference/detach).
Invoke-RestMethod -Method Post -Uri http://localhost:33221/api/inference/reload

# Detach from an adopted external llama-server (starts a managed server on a new port; leaves the external process running)
Invoke-RestMethod -Method Post -Uri http://localhost:33221/api/inference/detach
# Note: if the runtime is not using an adopted external server, this is a no-op and returns "detached": false (ports unchanged).
# Example response:
# {
#   "success": true,
#   "detached": true,
#   "previousPort": 8080,
#   "newPort": 53421,
#   "mode": "ONLINE"
# }

# Restart the Knowledge Worker (useful after changing embedding model path)
Invoke-RestMethod -Method Post -Uri http://localhost:33221/api/worker/restart

# Preview (paged extracted text)
Invoke-RestMethod -Uri "http://localhost:33221/api/preview?docId=d:\path\to\file.txt&offsetChars=0&maxChars=8000"
```
