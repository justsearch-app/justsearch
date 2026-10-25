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
- For a separately started backend, set its port explicitly as shown below. There is no browser port-range scan.

> On Linux, use `npm --prefix ./modules/ui-web run dev:all`. macOS is outside the supported platforms.

#### Split terminals (manual)
For more control (or when debugging backend startup), run the Frontend and Backend separately.

**Terminal 1 (Backend, Windows PowerShell):**

```powershell
.\scripts\dev\run-headless-api.ps1 -Port 33221
```

**Windows note (memory safety):** If you hit Node/Vite “paging file / commit” issues during backend builds, you can skip bundling the web UI by passing `-PskipWebBuild=true` to Gradle. The `run-headless-api.ps1` script already uses this by default.

**Terminal 1 on Linux (from repo root):**

```bash
JUSTSEARCH_API_PORT=33221 node scripts/lib/tool-invoker.cjs gradle :modules:ui:runHeadless -PskipWebBuild=true
```

**Terminal 2 (Frontend, Windows PowerShell, from repo root):**

```powershell
$env:VITE_JUSTSEARCH_API_PORT = "33221"
npm --prefix modules/ui-web run dev
```

**Terminal 2 on Linux (from repo root):**

```bash
VITE_JUSTSEARCH_API_PORT=33221 npm --prefix modules/ui-web run dev
```

Open `http://127.0.0.1:5173`. The explicit port connects Vite and the browser to the backend started above.
The frontend resolves the backend using `resolveApiEndpoint()`. Resolution order is `?api_port=`,
the legacy JavaFX bridge, Tauri `invoke("api_port")`, `VITE_JUSTSEARCH_API_PORT` / `VITE_API_PORT`,
then the browser Vite proxy. Without a port override, Vite reads runtime manifests from its discovery
folders; the backend's default `modules/ui/build/applauncher-data` is not among them. To use manifest
discovery for this split-terminal setup, set the same absolute `JUSTSEARCH_DATA_DIR` in both terminals
before starting either process.

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

> Replace `33221` with your actual API port. The Windows wrapper prints `API Port: <port>`;
> the Engine prints `JUSTSEARCH_API_PORT=<port>`. `dev:all` echoes the backend logs as it starts.
> `dev:all` picks a free port near `33221`; `jseval dev` defaults to `33221` (`--port` overrides it).
> `scripts/dev/dev-runner.cjs start` defaults to port `0`, requesting an OS-assigned port.

| Endpoint | URL | Purpose |
| :--- | :--- | :--- |
| **Dashboard** | `http://localhost:33221/api/debug/dashboard` | Visual status of Worker, Index, and Memory. |
| **State JSON** | `http://localhost:33221/api/debug/state` | Raw JSON dump of the entire internal state. |
| **Event Log** | `http://localhost:33221/api/debug/events` | Recent system events (20 by default; use `?limit=<N>`). |
| **Engine Log Tail** | `http://localhost:33221/api/debug/engine-log` | Quick peek at recent engine logs (best-effort). |
| **Health** | `http://localhost:33221/api/health` | Lightweight backend health check. |
| **Inference Status** | `http://localhost:33221/api/inference/status` | Current AI mode + queues + effective runtime model/context (best-effort); includes diagnostics for explicitly configured external servers and ignored unowned servers. |

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
2.  Use the header's reindex action to incrementally check all watched roots. Unchanged files can be skipped.
3.  An unverified folder row shows **Reindex** (tooltip **Reindex to verify**). This forces
    extraction checks across all watched roots; it is not limited to that row's folder.

A full-generation rebuild for an embedding mismatch is a separate operation; an incremental reindex
does not replace that recovery.

### Forgetting an Indexed File or Folder

In Search v3, open a result's context menu and choose **Forget this file** or **Forget its folder**.
Check the exact path before confirming. Review incomplete removals in Library's **Unfinished forgets**.
See [Manage local data](manage-local-data.md) for keep-out rules, Retry/Withdraw, the outside-root file
exception, and the original files, Memory, conversations, logs and other copies that remain.

### Summarizing and Asking Questions (AI)
Click a result row to open its document preview. Results have no checkboxes. To build a selection
in the live results card, use **Ctrl-click** (or Meta-click) to toggle files, or
**Shift-click** to select a range. Frozen results from earlier turns can be opened but not multi-selected.

For selected files, use the selection menu's **Summarize all** or **Ask about all** action.
For a single result, its context menu includes **Ask about this file**. AI actions require a running
local chat model. Answers and summaries appear in the conversation, rather than a separate Inspector input.

**Ctrl+K** opens the command palette. See **Help → Keyboard Shortcuts** for the current shortcuts.

### Resetting the App

The desktop app's data folder is:

- Windows: `%APPDATA%\io.justsearch.shell`
- Linux: `~/.local/share/io.justsearch.shell` (or `$XDG_DATA_HOME/io.justsearch.shell` if set)

Prefer **Settings → Data → Delete all local data**, then type **DELETE** to confirm. This closes the
app and deletes the index, settings, logs, and chat history on next launch; AI models and the llama-server
runtime are preserved. Export an encrypted backup of chat history, memories, and agent history first
from **Security & Privacy** if you need to keep them.

Inside that folder, `index` holds the search index and `jobs.db` holds the indexing queue.
A browser dev stack uses its own data folder instead: `dev:all` defaults to `modules/ui-web/.dev-data`, Gradle `runHeadless`
defaults to `modules/ui/build/applauncher-data`, and jseval records its selected folder in its run output.
Use that stack's data folder rather than the desktop folder.

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

# Read the actual llama-server port from /api/inference/status first.
# Its initial default is 8081 (8082 when API port is 8081 or auto/0); managed launches may use a fresh port.
# Find listening ports owned by llama-server processes
Get-NetTCPConnection -State Listen | Where-Object { $_.OwningProcess -in (Get-Process -Name llama-server -ErrorAction SilentlyContinue).Id } | Select-Object LocalAddress,LocalPort,OwningProcess

# Per-process GPU snapshot (who is actually using SM%)
nvidia-smi.exe pmon -c 1

# Kill an orphan llama-server if needed
Stop-Process -Id <pid> -Force
```

JustSearch uses a server it did not start only when `justsearch.inference.external_endpoint` names it,
for example `http://127.0.0.1:8085`. This must be a loopback HTTP URL with an explicit port. Otherwise,
it starts its own server and reports a healthy unowned server as `external_server_ignored`; it does
not adopt it automatically. See ADR-0051.

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

Click a result row to open its document preview. For Q&A, use **Ask about this file** in the result
context menu, or select files with Ctrl/Shift-click and use **Ask about all**.

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

# Windows-only convenience wrapper (also starts both)
.\scripts\dev\dev-all.ps1

# Or start separately on Windows:
# Terminal 1: Backend (rebuilds on each start unless -SkipBuild)
.\scripts\dev\run-headless-api.ps1 -Port 33221

# Terminal 2: Frontend (hot-reloads automatically; from repo root)
$env:VITE_JUSTSEARCH_API_PORT = "33221"
npm --prefix modules/ui-web run dev
```

On Linux, use the split-terminal commands in Quick Start above; the `.ps1` wrappers call
Windows Gradle launchers. Java backend changes require restart. Frontend (Lit/Vite) hot-reloads automatically.

### Verifying Backend Changes

After modifying Java code:
1. Stop the dev stack:
   - If you used `npm --prefix modules/ui-web run dev:all`, press **Ctrl+C** in that terminal.
   - If you used `run-headless-api.ps1`, press **Ctrl+C** in that terminal.
2. Rebuild (Java-only, skips web bundle): `node scripts/lib/tool-invoker.cjs gradle :modules:ui:installDist -PskipWebBuild=true --no-daemon`
3. Restart on Windows: `.\scripts\dev\run-headless-api.ps1 -SkipBuild`. On Linux, rerun the
   `runHeadless` command in Quick Start; with `dev:all`, rerun that npm command.

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

# Detach from an explicitly configured external llama-server for this run.
# Starts a managed server and leaves the user-owned external process running.
Invoke-RestMethod -Method Post -Uri http://localhost:33221/api/inference/detach
# If no external server is in use, returns "detached": false (ports unchanged).
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
