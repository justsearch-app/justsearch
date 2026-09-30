---
title: Secondary Views Behavioral Reference
type: reference
status: stable
updated: 2026-08-19
description: "Secondary view behavioral specification (Library, Brain, Health, Settings, Help)."
---

# Secondary Views Behavioral Reference

[`search-ui-behavior.md`](search-ui-behavior.md) documents the search experience (Zones A, C, D, E). This document covers the secondary views accessed through Zone B (the ActivityRail), plus the ActivityRail itself. Together they form the complete UI behavioral reference.

> **Search-thread unification:** **Search** is the one rail window for the `retrieve` → Ask → Delegate progression. The retired `core.search-surface` aliases to `core.unified-chat-surface` for compatibility. Mode-specific Ask, free-chat, and extract IDs remain deep-link addresses but are not independent rail or split-pane destinations. The nav-items table below also carries pre-578 taxonomy + React-era styling drift tracked by tempdoc 579 — verify the live rail against `CoreSurfaceCatalog.java` + `ShellRail.ts`.

Source files (Lit surfaces under `modules/ui-web/src/shell-v0/`): the rail chrome
`chrome/ShellRail.ts`, and the view surfaces `views/LibrarySurface.ts`,
`views/BrainSurface.ts`, `views/HealthSurface.ts`, `views/SettingsSurface.ts`,
`views/HelpSurface.ts`.

> **Post-rewrite status (2026-06-13):** the frontend moved to Lit web components
> (ADR-0032); the React stack named in older revisions is retired. Anchors were
> re-pointed to the `shell-v0` surfaces and **live-verified (ui-shot demo,
> 2026-06-13): the ActivityRail and all five surfaces (Library, Brain, Health,
> Settings, Help) render and match these descriptions** — e.g. Library shows the
> watched-folders + exclude-patterns layout, Brain the model-config panel, Settings
> the Simple/Detailed + System/Dark/Light + High-contrast/Vim controls. Deep
> per-control behaviors were spot-checked, not exhaustively exercised; verify a
> specific claim against `modules/ui-web/src/shell-v0/` + code if precision matters.
> (Tracked: tempdoc 579.)

---

## 1. ActivityRail (Zone B)

**Source:** `shell-v0/chrome/ShellRail.ts` (the `<jf-rail>` nav)

The ActivityRail is a vertical icon strip on the left edge of the window. It provides top-level view switching.

### Navigation items

Six items, split into two groups by a thin horizontal divider:

| # | ID | Icon | Label | Tint color | Group |
|---|-----|------|-------|------------|-------|
| 1 | `unified-chat` | MessageSquare | Search | teal | Primary | _(the one interaction window — retrieve/Ask/Delegate)_ |
| 2 | `library` | Library | Library | amber | Primary |
| 3 | `brain` | Brain | AI Brain | teal | Primary |
| 4 | `health` | Activity | Health | emerald | Primary |
| 5 | `help` | HelpCircle | Help | _(no tint property)_ | Secondary |
| 6 | `settings` | Settings | Settings | purple | Secondary | _(tempdoc 855: fixed rail affordance, same pattern as Help — opens the Settings window as a `Placement.MODAL` overlay rather than navigating into Stage; see §5.)_

### Active state

- When a non-search item is active, a `motion.div` with `layoutId="rail-indicator"` renders behind the icon, providing a smooth spring animation between items (`stiffness: 400, damping: 30`).
- The active indicator uses the item's tint color for background and inset glow: e.g., `bg-teal-500/15` + `shadow-[inset_0_0_12px_rgba(20,184,166,0.15)]`.
- **Exception (historical):** the retired standalone Search rail item had no background when active (`bg-transparent`), merging with the top bar; the current Search window follows the normal active-indicator treatment.
- Inactive items are `text-[var(--text-muted)]` with hover states.

### Keyboard

Each group is a Radix `RovingFocusGroup.Root` with `orientation="vertical"`. Arrow Up/Down moves focus within a group. Items are native `<button>` elements, so Enter and Space activate via browser default behavior (no custom key handlers).

The two groups are separate roving focus containers — keyboard focus does not cross the divider automatically.

### View prefetching

On `mouseEnter` or `focus`, the target view's chunk is prefetched via dynamic `import()`. This reduces Suspense fallback duration when switching views. View switches are wrapped in `startTransition()` to prevent React synchronous-input errors.

---

## 2. LibraryView

**Source:** `shell-v0/views/LibrarySurface.ts`

Manages indexed folder roots. The user adds folders, monitors indexing status, and removes folders from the index.

### Header

- Title: "Library" / "Manage your indexed folders"
- **Reindex All** button — visible only in Detailed mode (`isAdvanced`). Triggers full re-index of all roots. Shows a spinning RefreshCw icon during reindex.
- **Add Folder** button (teal, `bg-teal-700`) — visible only when at least one root exists (empty state has its own CTA).

### Add folder flow

- **Tauri runtime:** Opens native OS folder picker via `@tauri-apps/plugin-dialog`. On success, calls `add(path, 'default')`.
- **Browser mode:** A persistent amber warning bar is always visible: "Running in browser mode. Native folder picker not available — enter paths manually." When the user clicks "Add Folder", an inline text input appears below the warning (`showManualInput` state). The input has amber-themed styling (`border-amber-500/30`, `bg-amber-500` submit button) with a Cancel button to hide it again.

### Folder list

Each root renders as a glass card (`bg-[var(--glass-surface)]`, hover upgrades border to `--glass-border-hover` but background stays the same) with:

1. **Folder icon** (40×40 rounded box) with a status dot overlay at bottom-right.
2. **Path** (truncated, primary text).
3. **Metadata row:** file count + relative time ("2h ago", "Just now", date).
4. **Remove button** (Trash2 icon) — visible on hover only (`opacity-0 group-hover:opacity-100`).

Cards animate in with staggered `motion.div` (`delay: index * 0.03`).

### Status icons

Each root has a `status` field mapped to an icon with a colored blur glow behind it:

| Status | Icon | Color | Glow |
|--------|------|-------|------|
| `indexed` | CheckCircle2 | `--accent-success` (green) | green 30% blur |
| `indexing` | Loader2 (spinning) | `--accent-tint` (teal) | teal 30% blur + pulse |
| `error` | AlertCircle | `--accent-danger` (red) | red 30% blur |
| `pending` | Clock | `--accent-warning` (amber) | amber 20% blur |

### Empty states

When no roots exist, a centered glass card with a Folder icon displays:

- **No files indexed yet:** "Start building your library" / "Add a folder to begin searching your files"
- **Files indexed but no roots:** "Your library is ready to grow" / "{N} files indexed. Add a folder to keep your library fresh."

Both show a teal "Add Folder" button with a glow shadow.

### Remove folder

Clicking the Trash2 button opens a `ConfirmDialog` (danger variant):
- Title: "Remove Folder"
- Message: `Remove "{path}" from the index? The files will not be deleted, but they will no longer be searchable.`
- Buttons: "Remove" (confirm) / "Keep" (cancel)

### Exclude patterns (Detailed only)

Visible only in Detailed mode and only when at least one root exists. A glass card with:

- Label: "Exclude patterns" with glob examples (`**/node_modules/**`, `**/.git/**`, `**/*.log`).
- Textarea for one glob per line. Changes are saved to settings on each keystroke.
- **Apply excludes** button — flushes settings to backend, then calls `applyExcludes()` API. Shows result summary: "Applied N patterns across M roots: deleted X files (by id) + Y jobs (by path prefix)."

### Error handling

The Folders view includes an **Indexing activity** summary of retained ingestion
outcomes, grouped by reason and outcome class. Its total counts recorded events,
not distinct files or current search readiness. Known reasons have readable labels;
unknown reasons remain visible with neutral wording. The summary distinguishes
loading, empty, fetch failure and last-loaded data, and offers an explicit refresh.
It does not resolve filename hashes or retry ingestion jobs. Leaving Folders stops
its refresh subscription and cancels an in-flight request.

Errors display as a floating toast at `absolute top-16 right-6`, animated in from the right. Red styling (`bg-red-500/15 text-red-300`), with a dismiss × button that calls `clearError`. There is no auto-dismiss timer — the toast persists until the user clicks × or `clearError` is called as a side effect of another operation (e.g., a successful add/remove).

### No-connection state

When `apiBase` is null, shows a centered "No API Connection" message with HardDrive icon: "Start the backend to manage library."

---

## 3. BrainView

**Source:** `shell-v0/views/BrainSurface.ts`

The largest and most complex view (1400+ lines). Manages AI model installation, configuration, and runtime control. Has two modes that show entirely different content.

### No-connection state

When `apiBase` is null, renders `BrainNoConnection` component (a minimal placeholder).

### Header

`BrainHeader` component with a refresh button (spins while settings are loading/saving).

### Alerts

`BrainAlerts` renders at the top when:
- `runtimeError` is set (red error banner)
- `downloadsDisabled` by policy (yellow warning)
- `onlineAiDisabled` by policy (yellow warning)

### Simple mode (default)

Rendered when `ui.mode !== 'advanced'`. Uses the `BrainSimplePanel` component.

**State machine** (`AiState`): `'not_installed' | 'installing' | 'offline' | 'starting' | 'online'`

| State | Status label | Sublabel | CTA button | Icon badge |
|-------|-------------|----------|------------|------------|
| `not_installed` | Not Installed | Install AI models to get started | **Install AI** | gray |
| `installing` | Installing... | Progress % | **Cancel** | teal pulse |
| `offline` | AI Offline | Context-dependent (see below) | **Start AI** | gray |
| `starting` | Starting... | Loading model... N% | **Cancel** | amber pulse |
| `online` | AI Online | Chat and summaries ready | **Shut Down AI** | emerald glow |

The state is derived from `installStatus.state`, `inference.mode`, and boolean flags (`starting`, `switching`).

**Offline sublabel** distinguishes manual shutdown ("AI was shut down. Start again to enable chat and summaries.") from fresh state ("Start AI to enable chat and summaries") via a local ref tracking whether the user explicitly shut down.

**Startup progress bar**: When `aiState === 'starting'`, an amber progress bar renders below the status header. Uses `useStartupProgress` hook — a time-based ease-out curve (0–95% over ~25s) that resets when the state changes. The sublabel shows "Loading model... N%".

**Cancel during startup**: The starting state uses an active "Cancel" button (not disabled) that calls `switchToIndexing()` to abort.

**Runtime info card**: When `aiState === 'online'`, a compact info card renders showing tier, context window (tokens), and GPU description. Data comes from `/api/inference/status` fields already exposed by `useInferenceMode`.

**Capability cards** ("What you get"): Shown for both `not_installed` and `offline` states (reminds returning users of AI features).

The Brain header exposes the shared **Simple / Detailed** segmented control.

### Detailed mode

Rendered when `ui.mode === 'advanced'`. Shows the full configuration interface.

The same Brain header control returns to Simple mode; it is a projection of the app-wide setting, not a Brain-only mode.

**Section attention badges**: Colored dots appear on `<summary>` elements when a section needs attention:
- **Install AI** — amber (not completed) or red (failed)
- **GPU Acceleration** — red (NVML error)
- **Runtime** — red (inference error)
- **Models** — amber (no model path configured)

Sections in render order:

1. **Recommended next step** — A breadcrumb: "1) Install models → 2) Start inference → 3) Apply settings"

2. **Compatibility warnings** (3 cards):
   - `EmbeddingCompatibilityCard` — warns when the embedding model fingerprint has changed since indexing. Shows "BLOCKED_LEGACY" or "BLOCKED_MISMATCH" states with a force-reindex button.
   - `SchemaCompatibilityCard` — warns when the index schema format is incompatible. Similar blocking states with force-reindex.
   - `ChunkVectorStatusCard` — shows semantic search readiness: chunk doc count, vector coverage %, pending/failed embedding counts.

   **Search Quality Features** (`SearchQualityFeaturesSection`): An extracted component that shows the active/inactive status of search quality subsystems (e.g., reranking, citation scoring). The component corrects ONNX feature status using system store data before rendering — it reads `rerankerOrtCuda` and `rerankerModelPath` from `/api/status` (ground truth reflecting the Worker's actual ORT session state) rather than relying solely on ONNX model discovery (which reflects static file presence and can report "Inactive" when the model is actually executing via env-var configuration). Displays status like "1/2 active" with per-feature detail (e.g., "Search reranking: Active" + correct model path).

3. **Policy & Packs** _(Tauri only, collapsible `<details>`)_ — `PolicyHelperPanel` shows:
   - Effective policy (machine vs. user)
   - Pack allowlist configuration status
   - Connection info (endpoint, port, source)
   - Copy buttons for machine/user policy JSON templates

4. **GPU Acceleration** _(Tauri only)_ — Runtime variant management:
   - GPU capabilities display (source, CUDA available, driver, VRAM total/used formatted as human-readable bytes e.g. "8.0 GB")
   - Installed runtime variants with Activate/Deactivate buttons
   - Policy-gated: warns with user-friendly text when GPU acceleration or external inference servers are restricted by administrator policy. Activate button shows "Blocked by administrator policy" tooltip when policy prevents activation.
   - Activation status with phase, result, VRAM delta formatted with tooltip explaining "VRAM usage change after activating this runtime variant"

5. **Install AI** — Online model download:
   - Shows install manifest (asset list with filenames, terms URLs, download progress)
   - Buttons: Install AI, Cancel, Repair AI
   - Repair shares the install progress UI: phase stepper dots (download/verify/apply/smoke_test), progress bar, and bytes downloaded/total
   - Policy-gated: disabled when `downloadsEnabled=false`

6. **Import AI Pack** _(Tauri only)_ — Offline/air-gapped import:
   - Preflight: compute manifest SHA-256 without installing
   - Import .zip or folder
   - Policy-gated: requires allowlist to be configured
   - Auto-creates/updates user policy for pack allowlisting

7. **AI Home** _(Tauri only)_ — File system paths:
   - Home directory path with "Open folder" button
   - Reveal buttons: logs dir, llama-server.log, engine.log
   - Export diagnostics button

8. **Runtime** — Inference mode control:
   - Compact mode badge (colored by state: emerald=online, amber=transitioning, gray=indexing)
   - Primary action: "Start AI" (when offline) or "Shut Down AI" (when online)
   - Secondary text links: "Apply settings" (reloads inference config) and "Process queue" (triggers offline VDU + embeddings)

9. **Models**:
   - **llama-server executable** — optional BYO path with Browse button (Tauri only)
   - **Generative Engine** (`ModelSlotCard`) — "Main Process (GPU Accelerated). Handles chat, summarization, and intent translation." Capabilities: Chat, Summarization, Intent, Streaming.
   - **Embedding Engine** (`ModelSlotCard`) — "Worker Process (CPU Optimized). Handles semantic search and file indexing." Capabilities: Vector Search, Indexing, Similarity.
   - Browse buttons for each model (Tauri only)
   - "Restart worker to apply embedding model" button

10. **Inference settings** _(inside Models section, Detailed UI only)_:
    - Context Window slider (512–32768 tokens, step 512)
    - Max tokens slider (64–16384 tokens, step 64)
    - GPU Layers: "Auto (CPU-only)" checkbox (default on, hides slider). When unchecked, slider appears (1–100 layers). Explanatory text above: v1 ships CPU-only runtime, manual offloading requires GPU-capable runtime.
    - llama library path (text input, optional)
    - "Pause indexing while summarizing" checkbox

---

## 4. HealthView

**Source:** `shell-v0/views/HealthSurface.ts`

System health monitoring dashboard. Shows vitals, connection status, AI engine state, and derived health events.

### Header

- Title: "System Health" / "Monitor index and API status"
- **Health badge** — pill showing overall status:
  - Green: "All systems operational" (when connected + index healthy)
  - Amber: context-specific message ("Backend disconnected", "Index unhealthy", or first error event message)
  - Badge title attribute shows the first event message on hover
- **Auto-refresh** checkbox (default: on)
- **Manual refresh** button (RefreshCw, spins during load)

### Stats grid

Four metric cards in a 2×2 (or 4-column on large screens) grid:

| Metric | Icon | Icon color | Value | Status dot |
|--------|------|-----------|-------|------------|
| Files | FileText | teal | `indexedDocuments.toLocaleString()` | green if >0, neutral otherwise |
| Size | Database | blue | formatted bytes | _(none)_ |
| Memory | MemoryStick | purple | `{MB} MB` with "of {max}" subtitle | _(none)_ |
| Queue | Zap | amber if >0, muted otherwise | pending job count | processing (blue pulse) if >0, neutral otherwise |

### Connection card

- Endpoint URL (monospace)
- Status: connected (green) / disconnected (ghost) / error (red)
- Index State: 6 possible values mapped to display text:
  - IDLE → "Ready" (green), INDEXING → "Indexing..." (amber), ERROR → "Error" (red), UNAVAILABLE → "Unavailable" (red), NOT_STARTED → "Not started" (ghost), UNKNOWN → "Not connected" (ghost)
- Uptime counter at bottom

### Queue DB card

- Status: Healthy (green) / Unhealthy (red)
- Last Backup: relative time, amber if >24h stale
- Integrity Check: relative time + "(FAILED)" suffix in red if `lastQuickCheckOk === false`, amber if >24h stale

### AI Engine card

- Section header with mode badge (Online=green, Starting=blue, Offline=gray)
- Status line with colored dot: Online / Starting / Offline
- Metrics grid (4 columns): Embed queue, VDU queue, Context (actual), Context (configured)
- Error display if `inference.error` exists

### GPU card

Renders when `gpu.available != null` in the status response (only shown when GPU data is available from the backend's NVML integration). When no GPU is detected, a "No GPU detected" fallback is shown instead.

Contents:
- **VRAM bar** — horizontal progress bar showing used/total VRAM, color-coded (green when usage is low, amber when moderate, red when high). Example: "1.7 GB / 12 GB VRAM"
- **Utilization %** — GPU utilization percentage from NVML
- **Driver version** — NVIDIA driver version string (e.g., "595.79")
- **Device count** — number of GPU devices detected

Verified with real NVML data during the tempdoc 364 verification pass.

### Health events

The live Health surface consumes typed conditions and occurrences from
`/api/health/events/stream`. Agent completion details explain completed, step-limit,
budget-limit, errored and cancelled outcomes in plain language, preserving the
event's existing title and severity. A step-limit explanation does not claim that
an answer exists; an unknown disposition produces an honest generic explanation.
Explicit lifecycle messages take precedence over these fallback details.

Recent events shows the latest 30 received events in reverse order through the
shared `jf-health-event` activity-row renderer. Each row preserves its producer's
severity and uses the existing status-tone mapping. An empty list says "No events
yet."; it does not imply that every subsystem is healthy.

### Quick actions

- **Open Library** — switches to library view via `setActiveView('library')`
- **Reindex now** (force) — triggers full reindex, teal styling
- **Restart worker** — requires `ConfirmDialog` (warning variant): "This will stop the knowledge server process and restart it. Any in-progress indexing will be interrupted."
- All actions disabled in demo mode, with italic note: "Actions unavailable in demo mode."
- Action errors display in a red banner below the buttons.

### Polling

Auto-refresh polls `/api/status` every 5 seconds (`pollInterval: autoRefresh ? 5000 : 0`). A separate 1-second interval updates `nowMs` for relative time display. Staleness threshold is 24 hours for backup and integrity check timestamps.

---

## 5. SettingsView

> **Superseded placement (tempdoc 855, 2026-08-19):** Settings is no longer a
> Stage-mounted secondary view — `core.settings-surface` flipped from `Placement.RAIL`
> to `Placement.MODAL`. Navigating to it opens `<jf-settings-window>` (a chrome-level
> overlay in `OverlayHost`'s `center` slot, mounted from `shell-v0/chrome/SettingsWindow.ts`)
> over the unchanged Stage, instead of replacing the Stage's active surface. The section
> body below (glass sections, `useSettings` hook, staggered entry) predates that change
> and the Lit rewrite; it documents the pre-855 Stage-page layout for history, not the
> current window. Current composition: one declared category register
> (`shell-v0/views/settingsRegister.ts`) projects the window's grouped nav (accordion +
> scroll-spy), category pages, deep-link targets, and in-window search — see
> `docs/explanation/10-ui-ux-design.md` ("Settings window") for the current behavioral
> description, and `docs/reference/ui/frontend-kernel/kernel/00-primitives.md` for the
> `Placement.MODAL` routing contract.

**Source:** `shell-v0/views/SettingsSurface.ts`

User preferences panel with 6 glass sections that animate in with staggered entry.

### Header

- Title: "Settings" / "Customize your experience"
- "Saving..." indicator (teal text) when settings are being persisted
- **Reset to Defaults** button — requires `ConfirmDialog` (warning variant): "This will reset all settings (theme, density, keyboard, LLM configuration) back to their default values."

### Sections

#### 1. Interface (Layers icon)

UI Mode toggle:
- **Simple** (default) — "Standard view"
- **Detailed** — "Reindex & excludes"

Helper text: "Detailed mode shows Reindex All button and exclude patterns in Library."

This toggle gates Detailed-only features across all views (Library exclude patterns, Brain runtime sections, Health is unaffected).

#### 2. Appearance (Palette icon)

Theme selection:
- **System** (default) — "Follow OS" — removes `data-theme` attribute, CSS media queries handle it
- **Dark** — "Default theme" — sets `data-theme="dark"`
- **Light** — "Bright theme" — sets `data-theme="light"`

High Contrast toggle — syncs to both settings store and the `onHighContrastChange` prop (which applies the `.high-contrast` class to the shell).

> **Superseded (tempdoc 855 §17 R1, 2026-08-19):** the React-era `onHighContrastChange` prop is
> gone. High contrast is now the ONE control, in the Accessibility section (`jf-switch`,
> `data-testid="settings-high-contrast"`), writing through `SettingsSurface.patch()` to the
> canonical `UISettings.highContrast` field — the same backend-persisted path every other setting
> uses. It reaches the `.high-contrast` root class via the APPEARANCE_FLOW statechart's
> `set-appearance` effect, applied by `Shell.ts`'s `jf-set-appearance` listener (`themeState.applyAppearance`).
> The Appearance section (this one) now renders a cross-link row pointing at Accessibility instead
> of its own duplicate toggle.

#### 3. Keyboard (Keyboard icon)

- **Vim Mode** toggle — "j/k navigation, / for search". `vimMode` is a persisted backend field (added to `UISettings` type, `UiSettingsV2Schema`, and the backend `UiSettingsV2` DTO as `vimMode: Boolean`). The setting round-trips correctly through `POST /api/settings/v2` and survives backend restarts. Previously this was localStorage-only.
- **Enter Action** dropdown (`GlassSelect`): Open (default) / Reveal / Preview

#### 5. Shortcuts (Command icon)

Read-only reference grid (2 columns on md+):

| Key | Action |
|-----|--------|
| Cmd+K | Focus search |
| Cmd+Shift+P | Action panel |
| Cmd+\\ | Toggle Inspector Panel |
| Tab | Cycle zones |
| ↑↓ | Navigate |
| Enter | Open file |
| Esc | Clear / close |

> **Correction (tempdoc 855 fix round, 2026-08-19):** this grid previously listed `Cmd+Shift+H` →
> "High contrast" — no such shortcut binding exists in code (`git grep` for a Shift+H handler
> across `modules/ui-web/src` turns up nothing). High contrast is toggled via the Accessibility
> section's switch only (see the §2 Appearance supersession note above); the row is removed rather
> than corrected because no shortcut replaces it.

#### 6. Data (Database icon)

"Delete all local data" button — Tauri only, disabled in browser mode (`opacity-50 cursor-not-allowed`).

Requires `ConfirmDialog` (danger variant): "This will close JustSearch. On the next launch, your local index, settings, and logs will be deleted. Your BYO AI assets (models and llama-server.exe) in AI Home are preserved."

On confirm, invokes `request_delete_data` Tauri command.

### Settings persistence

Three-tier save mechanism:
1. **In-memory reactive state** — immediate update
2. **API POST /api/settings/v2** — debounced backend persistence
3. **localStorage** — backup/fallback

The `useSettings` hook manages the debounce and API sync. `saving` state shows the "Saving..." indicator.

---

## 6. HelpView

**Source:** `shell-v0/views/HelpSurface.ts`

Two-column layout on large screens (single column on small).

### Header

- Title: "Help & Support" with HelpCircle icon
- Subtitle: "Copy a redacted summary or export the full local diagnostics bundle."
- **Copy diagnostic summary** operation — available while Worker/Inference are offline. Copies the bounded allowlist-only summary directly from the operation response; the summary is never rendered or persisted. A fixed local receipt appears only after the clipboard write succeeds, with fixed failure text otherwise.
- **Export diagnostics** operation — exports the full redacted ZIP and shows the saved path on success or an error on failure.

### Left column

**FAQ accordion** — 5 items, single-expand (clicking one closes the other):

1. "How do I add files to search?" — Points to Library + Add Folder.
2. "What file types are supported?" — PDFs, Markdown, text, code, images (with OCR).
3. "How does AI summarization work?" — Select file → Summarize → local AI, no external data.
4. "Why is indexing slow?" — Initial indexing is slow, incremental is fast. Check Health.
5. "How do I use the AI chat feature?" — `??` prefix in search bar.

Expanded answers show below the question with a chevron rotation animation.

**Quick troubleshooting** — Glass card with 3 bullet points:
- Stale results → Reindex in Library (Detailed) or Health
- Stuck indexing → Restart worker in Health
- Bug report → Copy diagnostic summary, review it, then paste it into the optional issue field

### Right column

**Keyboard shortcuts** — only bindings that actually fire are listed:

| Key | Description |
|-----|-------------|
| `Ctrl / ⌘ + K` | Open the command palette |
| `Enter` | Run the search when the search box is focused |
| `Esc` | Clear the search box or close an open panel/drawer |
| `Ctrl / ⌘ + Z` | Undo |
| `Ctrl / ⌘ + Shift + Z` | Redo |
| `J / K` | Step through a visible conversation transcript |

**Local-first info** — Glass card with Shield icon: "Your files stay on this machine. Search and indexing run locally, and the UI only talks to the local backend over loopback."

**Network activity** — Glass card with Wifi icon, 3 bullet points:
- Local app traffic uses loopback (not a network service)
- AI model downloads only on explicit user action
- Future "Online" features will be clearly labeled and opt-in

---

## 7. Cross-cutting patterns

### Simple/Detailed mode

The `ui.mode` setting (`'simple'` default / `'advanced'`) gates features across multiple views:

| View | Simple mode | Detailed mode adds |
|------|-------------|-------------------|
| Library | Add/remove folders, status display | Reindex All button, exclude patterns section |
| Brain | BrainSimplePanel (state machine) | Full configuration: compatibility cards, policy, GPU booster, model slots, inference settings |
| Settings | All 6 sections visible | _(no difference)_ |
| Health | Full dashboard | _(no difference)_ |

The toggle lives in the top bar, Settings → Interface, and the Brain surface header; all three
controls project the same live `uiModeState` value.

### ConfirmDialog pattern

Reused across views for destructive or impactful actions. Three variants:

| Variant | Styling | Used by |
|---------|---------|---------|
| `danger` | Red accent | Library remove folder, Settings delete data |
| `warning` | Amber accent | Health restart worker, Settings reset to defaults |
| `info` | Neutral/teal accent | Brain install confirm ("Download AI models?") |

All dialogs have title, message, confirm label, and cancel label. They block interaction via a modal overlay.

### Auto-refresh and polling

| View | Interval | Mechanism |
|------|----------|-----------|
| Health | 5 seconds | `useStatus({ pollInterval: 5000 })` + manual refresh button |
| Library | On-demand | `useSources({ pollInterval: 0, autoFetch: true })` — fetches once on mount, then on user actions |
| Brain | 10 seconds | `useStatus({ pollInterval: 10000 })` for embedding compat status |

Both pack import and runtime activation polling include failure protection: after 30 consecutive failed poll attempts (~30 seconds), the status transitions to `'failed'` with a "Lost connection to server" message, preventing stale progress indicators.

### Tauri vs browser capabilities

| Feature | Tauri | Browser |
|---------|-------|---------|
| Folder picker | Native OS dialog | Manual text input (amber warning) |
| File picker (model, pack) | Native OS dialog | Not available |
| Reveal in explorer | `@tauri-apps/api` invoke | Not available |
| Delete all local data | `request_delete_data` command | Disabled (grayed out) |
| AI Pack import | Available (with policy) | Not available |
| AI Home paths | Resolved from Tauri | Not available |
| GPU Booster Pack | Available | Not available |

### Staggered animations

All views use `framer-motion` for entry animations:
- HealthView: `containerVariants` with `staggerChildren: 0.05`, individual `itemVariants` with `y: 8` slide-up
- LibraryView: folder cards stagger with `delay: index * 0.03`
- SettingsView: sections stagger with `staggerChildren: 0.06, delayChildren: 0.1`

### Glass card styling

All views use a consistent card pattern: `rounded-2xl bg-[var(--glass-surface)] border border-[var(--glass-border)] backdrop-blur-sm`. HealthView formalizes this as a local `GlassCard` component.

---

## Related

- [`search-ui-behavior.md`](search-ui-behavior.md) — Search UI behavioral reference (Zones A, C, D, E)
- Accessibility findings are routed to the owning tempdoc at discovery (tempdoc 872; the `issues/search-accessibility.md` register was retired, tempdoc 821 §7 D5; the observations store that briefly superseded it is retired too — its history is in git)
