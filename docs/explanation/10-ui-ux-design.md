---
title: UI/UX Design System
type: explanation
status: stable
description: "Current window structure, visual rules, interaction model, progress vocabulary, and accessibility requirements."
---

# UI/UX Design System

This page states the current UI rules and explains the shell and interaction model.
Implementation anchors are under `modules/ui-web/src/shell-v0/`; history lives in git
and the frozen tempdocs.

## 1. Design principles

JustSearch is a keyboard-first desktop utility for finding files and working with their
contents. Use the system UI font and familiar interaction patterns. Primary actions must
be reachable by keyboard, with visible focus and meaningful labels.

**Simple is the default; Detailed is available on request.** Routine actions and status
stay understandable without technical knowledge. Diagnostics, raw identifiers and runtime
configuration belong in Detailed mode. Use the shared app-wide disclosure level described
in [Simple and Detailed UI modes](../reference/ui/simple-vs-detailed-mode.md).

### Current visual rules

These rules were agreed by the maintainer on 2026-11-01, using the Search window as the
visual reference:

- **Use the whole window.** Do not put a window's contents in a narrow centred column or
  wrap groups in cards (boxes). Separate groups with small grey headings, space and
  hairlines.
- **Say the state in words.** For example, “Your files are searchable” tells the user what
  they can do. Keep technical detail in Detailed mode; a colour or icon alone is not a status.
- **Use text actions.** Avoid outlined pill buttons. Give a window's main action one filled
  teal button; secondary actions are quiet text controls.
- **Reserve teal** for the main action, switches that are on, progress, and links that add
  something. Do not use it as decoration or a file-type colour code.
- **Use the existing typography and surface tokens.** Headlines are regular weight (400),
  in the 24–30 px range; body text is 14–15 px and grey details are 12–13 px. Hairlines are
  approximately seven-percent white in the dark theme, with theme-appropriate contrast in
  light and high-contrast modes. The token names below are the implementation vocabulary;
  do not copy raw style values into components where the style-literal ratchet forbids them.

| Purpose | Existing tokens and source |
|---|---|
| System UI font | `--font-native` in `styles/tokens.css`; Search uses `--font-sans` in `views/search-v3/sv3-tokens.css.ts` |
| Search headline | `--font-size-sv3-display`, used at weight 400 by `Sv3Composer` |
| Body text | `--font-size-sv3-sm` in Search; `--font-size-md` in the shared scale |
| Grey details | `--font-size-sv3-xs` / `--secondary-label` in Search; `--font-size-sm` / `--text-secondary` in the shared scale |
| Group spacing | Search's `--space-*` scale; shared `--density-stack-gap*` tokens |
| Hairlines | Search's `--border`; shared `--border-subtle` → `--glass-border` (both dark defaults use six-percent white, the nearby existing rung) |
| Main action | Search's `--primary` / `--primary-foreground`; shared `--accent-tint` / `--accent-on-tint` |
| Keyboard focus | Search's `--ring`; shared `--focus-ring-color` |

These are window-composition rules. A conversation's reading measure and a document pane
are content layouts within a window, not a narrow container for the whole application.
Some floating panels use blur; backdrop blur and a glass aesthetic are not UI requirements.

## 2. Shell structure

`chrome/Shell.ts` renders the following structure. `layout/LayoutManifest.ts` describes
visibility and composition of the `rail`, `stage` and `statusBar` regions; it does not
imply five fixed functional zones. `statusBar.visible` now controls only plugin status
items in the status panel; the title-bar status pill remains available.

| Shell part | Responsibility |
|---|---|
| Title bar | App name, back/forward navigation, recent views, active surface title, status pill, Simple/Detailed control, bookmarks and Copy URL |
| Rail | Catalog-driven navigation; Simple mode withholds optional diagnostic surfaces |
| Stage | The active surface; layouts can also compose a second surface in a split stage |
| Status pill and panel (`jf-status-pill`, `jf-status-panel`) | One status in the title bar; the pill opens current conditions, work and earlier activity over the page |
| Overlay slots (`jf-overlay-host`) | Command palette, Settings, authorization dialogs, reading drawers and other transient surfaces in named slots |

### Main windows

- **Search** is one interaction window for retrieval, cited answers and delegated work.
  It starts in Search (keyword retrieval requires no model); the user can choose Ask or
  Delegate. A session sidebar keeps past conversations reachable. Legacy mode-specific
  surface IDs remain compatibility addresses rather than extra rail destinations.
- **Library** manages indexed sources and folders, their state, exclusions and recovery actions.
- **AI Brain** presents AI installation and readiness; engine configuration sits behind
  its own Advanced settings disclosure, independent of the app-wide Detailed mode. It surfaces schema incompatibility and enrichment readiness
  rather than treating a drained job queue as complete semantic coverage.
- **System** hosts diagnostic surfaces including Health, Logs and Activity. Health shows
  observed system and indexing facts and the recent inference transition history.
- **Settings** is a modal window opened from the rail, command palette or a deep link.

### Settings window

`core.settings-surface` uses `Placement.MODAL`. Navigating to it — from the rail, the command palette, or a deep-link URL —
opens `<jf-settings-window>` (`shell-v0/chrome/SettingsWindow.ts`), a chrome-level
element in `OverlayHost`'s `center` slot (the same dock as the command palette),
instead of mounting into the Stage; the Stage's active surface is untouched
underneath. Its dialog behavior follows the `ConfirmDialog` idiom: a native
`<dialog>` driven by `ModalController` supplies focus-trap, `inert`, and Top Layer;
ESC, backdrop-click, and an X button close it. Closing is history-aware — it either
navigates back (if opening the window pushed the current address) or forward-navigates
to the underlying stage surface (covering the case where Settings was the boot/deep-link
entry) — so Back is always meaningful and never doubles up with a real browser Back.

Inside the window, one category tree — declared once in
`shell-v0/views/settingsRegister.ts` as `{group → category → entry}`, where an entry
is either a native section or a member surface id — drives the vertical grouped nav
(accordion + scroll-spy), the category pages, deep-link targets, and in-window search;
these are projections of the same register.
Member categories mount `core.security-surface` (Privacy & Trust),
`core.presentation-gallery-surface` and `core.presentation-editor-surface` through the
catalog composition machinery. The Token Editor (`placement: 'DEEPLINK'`) is reached
from Appearance through a launch link, per the plugin-presentation boundary
([ADR-0035](../decisions/0035-fe-plugin-boundary.md)). A member surface's own ID stays
deep-linkable: for example, `core.security-surface` opens the Settings window at its
member category through the member-to-host redirect.

### Status pill and panel

One status pill in the title bar projects shared state (`components/StatusPill.ts`,
`components/StatusPanel.ts` and `state/frameStatus.ts`). It chooses one state in this
order: engine unreachable → search limited → needs you → problems → working → just
finished → calm, “Up to date”. Calm requires a fresh observation that search is ready;
it does not claim that every AI service is healthy. Missing or stale observations say
that status is being checked or show last-seen information. Needs you shows a count;
working shows progress when known. Below 1000 px the pill uses an icon, count or ring,
while keeping its accessible name.

Clicking the pill or pressing Ctrl+Shift+S opens “What's going on” over the page,
anchored below the pill. Escape, an outside click, the close button or the pill closes
the panel and returns focus to the pill. Its sections appear in this order, omitting
empty sections:

- **Needs you:** approvals, proposed actions, plugin permission requests, indexing holds,
  update or repair decisions, and rebuild decisions, with their available actions.
  Requests that require typed confirmation or permission consent use the protected dialog.
- **Problems right now:** active problems such as unavailable folders, unreadable files,
  limited search, plugin load failures and failed AI installation.
- **Running:** indexing and enrichment, rebuilds, AI downloads, operations and background
  tasks, with progress and controls where available.
- **Earlier:** action feedback, advisories and completed work, with details, read/unread
  state and Mark all read. Reversal actions appear only where supported.

Plugin status items render in a separate plugin row, with overflow available through
“more”. The footer links to Activity (“All activity”) and Health. An empty panel says
whether search is up to date or its status is still being checked.

Feedback from the user's own actions appears inside the pill for a few seconds. While
the pill shows engine unreachable, search limited, needs you or problems, that feedback
appears beside it instead. In both cases it remains available in Earlier. Status
transitions and action feedback are announced to screen readers. The panel updates
while open, preserving expanded details; switching windows closes it. The Help surface
supplies keyboard guidance.

### Progress vocabulary (status panel, Health, folder rows)

Every surface that reports indexing progress uses one set of words, and derives its
numbers from one place — the `selectIndexingProgress` projection over the `/api/status`
snapshot (`shell-v0/state/indexingProgress.ts`). Numbers may differ between surfaces by
**scope** (index-wide vs one folder), never by derivation.

*   **Phases:** **Scanning** (walking a folder) → **Indexing**
    (files becoming keyword-searchable; the job queue drains) → **Enriching** (the
    semantic layers — embeddings, SPLADE, entities, passage vectors — catching up on
    already-indexed documents) → **Ready**. Blocked or unconfirmed work must say so rather than imply readiness.
*   **Two tiers of "done":** job drain makes a folder keyword-searchable, which is not the
    same fact as fully searchable. A Library folder row therefore shows
    `Meaning processing · 40%` while semantic coverage climbs (`views/LibrarySurface.ts`, from
    `shell-v0/state/folderStatus.ts` and the per-root coverage counts on `IndexedRootView`). "Up to date" in Health is likewise reserved for
    coverage-complete, not job-drain.
*   **No fabricated numbers:** a percent is rendered only when the denominator is
    faithful (at least one applicable stage with documents to enrich). With no
    denominator — or a stale snapshot — the surface says less rather than showing a
    placeholder, and time estimates are withdrawn when their observed basis is missing or stale.
    Indexing uses recent ingest throughput; Enriching uses its own measured settle rate,
    not the ingest rate. Neither is a countdown.
*   **"Offline" is not a progress word.** It describes the AI engine or a service being
    down, and nothing else; enrichment copy uses the phase nouns above.

## 3. Interaction model

### Search and document reading

The composer belongs to the Search window. Search, Ask and Delegate are explicit
choices. Commands are available through the separate command palette.

Search uses a debounced quick pass and a refined pass on settle or Enter, with guards
against superseded responses. Recent searches are reachable from the empty Search
composer. The current filter disclosure accepts folder, modified-date and size bounds;
active restrictions can be removed, and result facets use the shared filter state.

Results use the shared Lit `ResultsCard` renderer with snippets, result explanations and
facet controls. In the Search window, opening a result and choosing it for an Ask are
separate acts: a plain click opens the document; explicit selection controls choose files.
The results can prepare an Ask about those chosen files.

Answers and their streaming activity appear in the conversation. Citations open the
source in the document reading pane, with a citation witness when one is available.
The pane loads extracted text through `/api/preview` and shows extraction provenance and
available OCR/VDU evidence. Trust and context detail stays with the conversation
and its context disclosures.

### Keyboard and implicit actions

Use registered commands and keybindings for primary actions. In the composer, Enter
submits at the chosen tier, Shift+Enter adds a newline and Ctrl/Cmd+Enter delegates;
composition by an input method editor takes precedence. Tab follows reachable controls.

Dropping folders onto the shell dispatches the add-to-index action. Result context menus
expose the actions appropriate to the result through the shared menu; do not promise a
fixed list of commands on every item.

### AI activity and enrichment

Online and indexing activity are runtime states, not exclusive GPU ownership: a loaded
LLM and background encoders can share available GPU memory. AI Brain exposes the
configuration and activity controls.

“Process pending work” dispatches `core.trigger-offline-processing` through the
catalog-driven `jf-operation`. Each operation runs one captured pass of up to 100 pending
vision documents and starts the AI engine when needed; embedding backfill needs no handoff
and runs beside chat. Its durable operation record reports acknowledged processed/failed
units and blocked remainder; it completes only after procedure cleanup. An incomplete
pass reports `ENRICHMENT_INCOMPLETE`. Completion describes that pass; future backlog and
embedding progress remain visible through `/api/inference/status`. The label is defined in `registry-operation.en.properties`.

## 4. Themes and accessibility

### Theme system

The app follows the OS colour setting unless the user picks a theme. Settings → Appearance
supports System, Dark and Light. System resolves `prefers-color-scheme` and follows live
OS changes; Dark is an explicit choice, not the app's unconditional default.
`index.html` samples the OS preference (or an explicit theme URL parameter) before the
first paint; `state/themeState.ts` applies restored appearance and live changes.
Appearance settings are restored through the settings API; palette selection is projected
from the consolidated `UserStateDocument`.

Shared colours use CSS custom properties in `styles/tokens.css`; Search also has the
scoped vocabulary in `views/search-v3/sv3-tokens.css.ts`. The shared cascade has
`core-theme`, `user-theme` and `user-override` layers, with accessibility overrides taking
precedence. Primitive colours feed semantic surface, text and accent tokens; Tailwind's
`@theme inline` maps shared tokens to utilities. Use the existing tokens rather than
assuming a fixed token count, a single colour exception or a three-value recipe for
another theme.

### Accessibility

- **Visible focus:** preserve keyboard focus rings and meaningful focus restoration when
  closing dialogs. Focus is part of the interaction, not decorative teal.
- **Contrast:** check WCAG AA contrast (4.5:1 for normal text, 3:1 for large text) in dark
  and light themes. The contrast-matrix gate checks token pairings; `jsharness ui-a11y-gate`
  checks measured axe results from rendered surfaces. A configured scan is not evidence
  that every view passes.
- **High contrast:** keep the app's high-contrast setting and OS forced-colours support
  working. High-contrast overrides strengthen text/border contrast; OS forced-colours
  also removes blur. Do not rely on transparency to convey state.
- **Semantics:** use the roles the actual controls implement. Current result containers
  use `list` / `listitem`, and shared menus use `menu` / `menuitem`.
- **Destructive actions:** require a confirmation before destructive work. Settings'
  Delete all data flow includes a typed DELETE confirmation; operation controls follow
  their declared confirmation policy.
