# JustSearch Sandbox Validation Environment

This document describes the environment inside the Windows Sandbox and how to
validate the JustSearch installer. It is durable harness knowledge; the
per-candidate specifics (what this release ships and must cover) live in the
generated `coverage-brief.md` and `validation-mode.md`.

## What is staged

The host-side launcher (`scripts/sandbox/sandbox-launch.py`) stages the mapped
folder at `C:\Users\WDAGUtilityAccount\Desktop\JustSearchTest\` with:

- The newest `*-setup.exe` (JustSearch installer)
- `CLAUDE.md` — the sandbox validation mission
- `sandbox-environment.md` — this file
- `coverage-brief.md` + `coverage-manifest.json` — the generated per-candidate
  must-touch surface set (derived from what the candidate ships)
- `validation-mode.md` — generated authority for this instance's model mode
  (`fresh-install` vs `pre-staged-models`)
- `candidate-provenance.md` — generated record of WHAT was validated: the
  installer's filename, SHA-256, size, host source path, agreement with any
  `SHA256SUMS` staged beside it, and the build commit when the build made it
  derivable. Quote it in the final summary so the archived evidence identifies
  its own build instead of needing host-side archaeology later
- `staging-gaps.md` — generated record of assets the host failed to stage this
  round (e.g. a missing SciFact corpus or Node installer); each entry must be
  recorded as a round-level coverage gap, not silently absorbed
- `collect-evidence.ps1` — the capture harness (port discovery + API ladder +
  `/mcp` Inspector check + snapshots)
- `docs/` — `explanation/`, `reference/`, `how-to/`, `decisions/`, `tempdocs/`,
  plus `llms.txt`
- `.claude/rules/` + `CLAUDE.md` + `docs/llms.txt` — sandbox-aware orientation
  (the `start` skill this used to name was retired; orientation is CLAUDE.md +
  the docs index + `scripts/agent-analytics/world-state.mjs`)
- `.claude/settings.json` — sanitized (no plugins, no dev-tools MCP,
  bypassPermissions)
- `tools/` — any installers copied here on the host (e.g. Git)
- `scifact/` — SciFact eval corpus for ingest-and-quality validation

Model mapping is instance-specific. Read `validation-mode.md` before launching:

- In `fresh-install` mode, no host models are mapped and `JUSTSEARCH_MODELS_DIR`
  must remain unset. This validates the production first-run Install AI path.
- In `pre-staged-models` mode, host models are mapped read-write at
  `C:\Users\WDAGUtilityAccount\Desktop\JustSearchModels\`. Set
  `JUSTSEARCH_MODELS_DIR` only when intentionally using this shortcut.

## What you install manually

Nothing runs automatically on startup besides Explorer opening at the mapped
folder (and the launcher's `setx` that enables request tracing — see *Endpoint
tracing* below). You install everything yourself:

- **Git for Windows** — from `tools\Git-Setup.exe /VERYSILENT /NORESTART
  /NOCANCEL /SP-` (or download it).
- **Agent harness — operator step, pick ONE** (this is the authority for the
  install; the charter only tells the agent what differs once it is running):
  - **Claude Code**:
    ```powershell
    irm https://claude.ai/install.ps1 | iex; $bin = "$env:USERPROFILE\.local\bin"; $u = [System.Environment]::GetEnvironmentVariable("Path","User"); if ($u -notlike "*$bin*") { [System.Environment]::SetEnvironmentVariable("Path","$u;$bin","User") }; $env:Path += ";$bin"
    ```
    then `claude` from the mapped folder. The staged `.claude/settings.json`
    starts it in bypass-permissions mode (fallback:
    `claude --dangerously-skip-permissions`). Reads `CLAUDE.md` + `.claude/`.
    Kick off with `/start`.
  - **Codex** (CLI, or the ChatGPT/Codex desktop app when you want Computer Use):
    ```powershell
    powershell -ExecutionPolicy ByPass -c "irm https://chatgpt.com/codex/install.ps1 | iex"
    ```
    Sign in (re-done every boot — the sandbox is wiped), open the mapped folder
    as the project and **accept the trust prompt**: the staged
    `.codex/config.toml` only loads for a trusted folder. It pins `gpt-5.6-sol`,
    sets `approval_policy = "never"` + `sandbox_mode = "danger-full-access"`
    (Windows Sandbox is the isolation boundary), and raises
    `project_doc_max_bytes` so the ~66 KB `AGENTS.md` is not silently cut at
    Codex's 32 KiB default. If you cannot trust the folder, launch with
    `codex --dangerously-bypass-approvals-and-sandbox` and put
    `project_doc_max_bytes = 262144` in `%USERPROFILE%\.codex\config.toml`.
    For GUI driving, install and enable the Computer Use plugin BEFORE
    kicking off, so the start skill's capability probe sees it. Reads
    `AGENTS.md` (same bytes as `CLAUDE.md`) + `.codex/` + `.agents/skills/`.
    Kick off with `$start` (CLI) or `@start` (desktop app).
- **JustSearch** — run the `*-setup.exe`. Per ADR-0024 the per-user NSIS installer
  lands at `%LOCALAPPDATA%\JustSearch\`, NOT `C:\JustSearch\`. User data goes to
  `%APPDATA%\io.justsearch.shell\`.

## Directory layout

| Path | Contents |
|------|----------|
| `C:\Users\WDAGUtilityAccount\Desktop\JustSearchTest\` | Mapped folder. Persists on the host at `tmp/sandbox/share/`. |
| `C:\Users\WDAGUtilityAccount\Desktop\JustSearchModels\` | Present only in `pre-staged-models` mode. |
| `%LOCALAPPDATA%\JustSearch\` | Installed app (per-user, ADR-0024). `JustSearch.exe` is the Tauri shell. |
| `%LOCALAPPDATA%\JustSearch\resources\headless\` | Bundled Java backend: JRE, JARs, llama-server, SSOT. |
| `%APPDATA%\io.justsearch.shell\` | User data: downloaded models, Lucene index, logs, runtime state, `telemetry/`. Survives uninstall by design. |
| `%APPDATA%\io.justsearch.shell\models\` | ONNX model files + chat GGUF. Created by Install AI. |
| `%APPDATA%\io.justsearch.shell\native-bin\llama-server\variants\cuda12\` | Populated by Install AI's GPU-runtime package (llama-server cuda12 variant + CUDA runtime DLLs). Empty/absent on CPU / low-VRAM profiles. |

The installer size and the exact model/GPU-runtime package set are candidate-
specific and change per release — do not assume figures from a prior candidate.
The authority for *this* candidate's asset set is its published `SHA256SUMS` /
GitHub Release (the 726 asset pipeline), per `docs/how-to/cut-a-release.md`.
(`coverage-brief.md` governs *surface* coverage, not the asset set.)

## Environment characteristics (durable)

- **Clean Windows** — no dev tools, no git repo, no pre-existing models.
- **GPU passthrough** — the host NVIDIA card is reachable for vGPU. NVML
  (`nvml.dll`) works and reports VRAM; `nvcuda.dll` loads and the driver-API probe
  (`cuInit` + `cuDeviceGetCount`) succeeds, which is what the install gate uses.
  `nvidia-smi.exe` is NOT on PATH — use `/api/ai/runtime/status` (NVML-first) for
  authoritative GPU metadata. The bundled `cuda12` llama-server ships its own
  runtime, so chat runs at GPU speed without a system CUDA toolkit.
- **Internet available** — needed for OAuth, Claude install, model downloads.
- **`prod=true`** -- the packaged candidate is the SHIPPED build, and
  `ApiSecurityFilters` enforces the session token (`X-JustSearch-Session`) on
  every mutating (`POST`/`PUT`/`DELETE`) call, with no path exemption. This is
  NOT the dev stack. See `CLAUDE.md`'s session-token section (the "packaged
  candidate boots `prod=true`" paragraph) for the authoritative token-fetch
  pattern before assuming any mutating call is unauthenticated.
- **Log rotation** — every launch rotates `logs/engine.log` (one prior
  generation kept as `.log.1`, older discarded) — the one JVM log; there is no
  separate `worker.log` (the Worker's own logback config was deleted). When
  investigating a crash, check `.log.1` (the previous boot) as well as the
  live log.

## Port discovery

The backend publishes its bound port in the runtime manifest at
`%APPDATA%\io.justsearch.shell\runtime\manifest.json` (tempdoc 501). Read
`head.apiPort`; don't probe with netstat:

```powershell
$manifest = Get-Content "$env:APPDATA\io.justsearch.shell\runtime\manifest.json" | ConvertFrom-Json
$port = $manifest.head.apiPort
$base = "http://127.0.0.1:$port"
Invoke-WebRequest "$base/api/health" -UseBasicParsing | Select-Object -ExpandProperty Content
```

The manifest also carries `instanceId`, `pid`, `lifecycle`, `worker.state`, and
`ai.phase` (see `docs/explanation/23-runtime-manifest.md`), plus the `reachability`
block whose `/mcp` transport confirms the MCP endpoint is advertised.

## Endpoint tracing (for coverage)

The launcher sets `JUSTSEARCH_HEAD_TRACING_LEVEL=detailed` (via `setx` in the
`.wsb` LogonCommand) so every API request is recorded as an OpenTelemetry span to
`%APPDATA%\io.justsearch.shell\telemetry\traces.ndjson`. This is the empirical
record of which endpoints the round exercised, consumed by the finalize coverage
check. If you launch the app in a way that doesn't inherit the env var, set it
yourself before launch (accepted values: `none` default, `sample` 1%, `detailed`
all).

## What to validate

The goal is to verify the installed app works on a clean machine **and** that a
first-time user can understand it. `coverage-brief.md` lists the surfaces this
candidate must exercise; after the cheap sanity ladder passes, prefer this journey
order:

1. **Fresh launch / first decision** — does `%LOCALAPPDATA%\JustSearch\JustSearch.exe`
   start and render, and is the next step obvious without API knowledge?
2. **Backend health sanity** — `/api/health` returns READY (before Install AI
   completes, expect DEGRADED with `inference.offline`).
3. **Install AI journey** — can the user discover the correct path, understand
   terms/size/progress, and see a clear completion or failure? Check alternate
   paths such as command-palette entries.
4. **Library / file indexing journey** — add a folder via the UI; use the staged
   SciFact corpus for the full ingest run; fall back to `POST /api/knowledge/ingest`
   with evidence if the UI cannot ingest it.
5. **Search / Chat / escalation ladder** — real searches from the UI compared with
   API responses; each ladder rung's label honest; AI-requiring rungs disabled with
   a clear reason when offline.
6. **`/mcp` external-client reachability** — see CLAUDE.md.
7. **Trust surfaces** — Security & Privacy, Memory, Appearance/Skins (see CLAUDE.md
   and `coverage-brief.md`).
8. **Cross-surface truthfulness** — UI state must match `/api/health`,
   `/api/status`, `/api/knowledge/status`, `/api/ai/install/status`, and
   `/api/ai/runtime/status`. Treat mismatches as findings even when the APIs pass.
