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
- Starts the frontend at `http://127.0.0.1:5173`.
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

Open `http://127.0.0.1:5173`. The frontend resolves the backend using `resolveApiEndpoint()` and will typically
connect automatically (via `VITE_JUSTSEARCH_API_PORT` / `VITE_API_PORT`, or by probing `33221..33250` on loopback).

Tip: If you started the backend on a non-default port, you can force the UI to use it via:

```text
http://127.0.0.1:5173/?api_port=33221
```

You can also set `VITE_JUSTSEARCH_API_PORT` when running `npm run dev`.
(`VITE_API_PORT` is accepted as a legacy alias.)

### Integrated Mode (Tauri)
To test the full desktop experience (with native window chrome, drag regions, etc.), run the Tauri shell.

**Prereq (stage the bundled backend into the Tauri resources folder):**

```powershell
node scripts/lib/tool-invoker.cjs gradle --no-daemon -PskipWebBuild=true :modules:ui:bundleSidecar
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
  (`http://127.0.0.1:5173`) cannot directly call that backend by design.
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
node scripts/lib/tool-invoker.cjs gradle --no-daemon -PskipWebBuild=true :modules:ui:smokeSidecarBundle
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
| **Engine Log Tail** | `http://localhost:33221/api/debug/engine-log` | Quick peek at recent engine logs (best-effort). |
| **Health** | `http://localhost:33221/api/health` | Lightweight backend health check. |
| **Inference Status** | `http://localhost:33221/api/inference/status` | Current AI mode + queues + effective runtime model/context (best-effort); includes external-server diagnostics when applicable. |

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
- `Ctrl+K` opens the command palette; **Help → Keyboard Shortcuts** in the app has the full list.

### Resetting the App
For a full reset, use **Settings → Data → Delete all local data**. **Help → Troubleshooting**
in the app (High disk usage) explains what it removes and the backup steps.

To rebuild only a stuck or corrupted index, quit JustSearch, then delete the
`index` folder inside its data folder (Windows `%APPDATA%\io.justsearch.shell`,
Linux default `~/.local/share/io.justsearch.shell`), and restart. If `XDG_DATA_HOME`
is set on Linux, use `$XDG_DATA_HOME/io.justsearch.shell` instead.

### Troubleshooting: GPU usage doesn't stop after a summary

It’s easy to misread GPU telemetry as “the summary is still running”.

- **Expected (normal)**:
  - `llama-server` often keeps **VRAM allocated while idle** so the next request is fast.
  - GPU **utilization** should drop close to 0% when the request finishes (other apps can still use the GPU).
- **Bad (needs intervention)**:
  - You see **multiple** `llama-server` processes, or the PID **keeps changing** every second. Check the process IDs and inference status to identify a leftover process or repeated restart before stopping anything.

Useful commands (Windows):

```powershell
# See llama-server processes (PID should be stable)
Get-Process -Name llama-server -ErrorAction SilentlyContinue | Select-Object Id,StartTime,CPU

# Find listening ports owned by llama-server processes
Get-NetTCPConnection -State Listen | Where-Object { $_.OwningProcess -in (Get-Process -Name llama-server -ErrorAction SilentlyContinue).Id } | Select-Object LocalAddress,LocalPort,OwningProcess

# Per-process GPU snapshot (who is actually using SM%)
nvidia-smi.exe pmon -c 1

# Kill an orphan llama-server if needed
Stop-Process -Id <pid> -Force
```

JustSearch uses a `llama-server` it did not start only when
`justsearch.inference.external_endpoint` is set to its loopback HTTP URL with an
explicit port (for example `http://127.0.0.1:8085`); otherwise it starts its own on
a free loopback port.

Tip: `GET /api/inference/status` is usually the fastest way to see if the app thinks it is Online/Indexing/Offline, plus any external-server diagnostics when an explicit endpoint is in use.

## 4. AI Agent Usage Notes

When using browser automation tools (Playwright, Puppeteer, or MCP browser tools) to interact with the UI, be aware of these issues and workarounds.

### Search Input Doesn't Trigger Results

**Problem:** Programmatic typing in the search box may not trigger search results because the search input's web-component handlers rely on specific DOM events (`input`, `keydown/keyup`) that browser automation may not fire correctly.

**Solution:** Use URL query parameters instead of typing:

```text
http://127.0.0.1:5173/?q=search_term

# Optional deterministic override (avoid discovery delays in automation):
http://127.0.0.1:5173/?api_port=<port>&q=search_term
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

Start the backend and frontend together:

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
2. Rebuild (Java-only, skips web bundle): `node scripts/lib/tool-invoker.cjs gradle :modules:ui:installDist -PskipWebBuild=true --no-daemon`
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

# Refresh persisted inference settings (model/context/gpu layers) from Brain settings.
# Use the Reload control, which submits an accepted settings operation with a fresh witness.

# Detach from an explicitly configured external llama-server (starts a managed server on a free loopback port; leaves the external process running)
Invoke-RestMethod -Method Post -Uri http://localhost:33221/api/inference/detach
# Note: if the runtime is not Online with an external server, this is a no-op and returns "detached": false (ports unchanged).
# Example response:
# {
#   "success": true,
#   "detached": true,
#   "previousPort": 8085,
#   "newPort": 53421,
#   "mode": "ONLINE"
# }

# Recover a failed index component with its current configuration.
# Supply the current per-boot mutation token in $mutationHeaders.
$mutationHeaders = @{ 'X-JustSearch-Session' = $sessionToken }
Invoke-RestMethod -Method Post -Uri http://localhost:33221/api/engine/components/index/recover -Headers $mutationHeaders

# Preview (paged extracted text)
Invoke-RestMethod -Uri "http://localhost:33221/api/preview?docId=d:\path\to\file.txt&offsetChars=0&maxChars=8000"
```
