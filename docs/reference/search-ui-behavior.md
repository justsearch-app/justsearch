---
title: Search UI Behavioral Reference
type: reference
status: stable
updated: 2026-06-13
description: "Search UI behavioral specification (zones, filters, selection, status deck)."
---

# Search UI Behavioral Reference

Behavioral specification for the JustSearch search experience. Covers every surface, interaction, and visual element involved in searching, browsing, and inspecting results. Describes what the user sees and how the system behaves — no implementation code.

For design philosophy and architecture, see `docs/explanation/10-ui-ux-design.md`.

> **Post-rewrite status (2026-06-13):** the frontend moved to Lit web components
> (ADR-0032); the React stack named in older revisions is retired. Implementation
> anchors were re-pointed to the `shell-v0` Lit surfaces. **Live-verified (ui-shot
> demo, 2026-06-13):** the search shell renders with Zone A (command input), Zone B
> (activity rail), Zone C (stage empty-state + Modified date filters), and Zone E
> (status deck). **Live-verified against the real backend + local LLM
> (Meta-Llama-3.1-8B, 2026-06-13):** typed search returns populated results with
> highlighted snippets, facet filters (Type/Format/Language with counts), result
> count + timing, and the "Keyword search · AI-expanded query" retrieval-mode badge;
> clicking a result opens a tabless document-reading pane inside the Search conversation
> zone; grounded Q&A stays in the same thread through **Ask**, with inline citations and
> source chips. **Spot-unverified (lower-value nuances):** arrow-key cursor
> vs `Enter`-to-inspect, multi-select checkboxes, and citation hover-cards — re-check
> against `shell-v0/views/UnifiedChatView.ts` (retrieve tier) + `shell-v0/components/searchResults/ResultsCard.ts`
> if a specific claim matters (Search Thread S5b: the standalone `SearchSurface.ts` this
> pointed at is retired). (Tracked: tempdoc 579.)

---

## 1. Layout and Zones

The search interface uses a five-zone layout:

| Zone | Name | Position | Purpose |
|------|------|----------|---------|
| A | Command Bar | Top | Search input, syntax toggle, autocomplete, history |
| B | Rail | Left edge | View switcher (Search, Library, Brain, System, Settings). Search is the one interaction window: `retrieve` is its base tier, **Ask** requests a cited answer, and **Delegate** starts multi-step agent work. |
| C | Stage | Center | Result list + filters, or Launchpad home |
| D | Document pane | Right | Tabless document reading inside the Search conversation zone; Q&A remains in the thread |
| E | Status Deck | Bottom | System readiness, result count, processing time, AI mode, hints |

Focus cycles through zones via Tab / Shift+Tab. Cmd/Ctrl+K always returns focus to Zone A (search input).

---

## 2. How Search Works

### Data Flow

1. User types in Command Bar → 120 ms debounce → search API call with query + filters
2. Results arrive as `SearchHit` objects from backend
3. `resultMapper` transforms hits to `HUDResultItem` objects:
   - Extracts highlights from backend `matchSpans` or falls back to regex term matching
   - Wraps matches in `<mark class="highlight-match">` HTML
   - Resolves document type from `file_kind` → MIME → fallback
4. Items flow into `useSearchStore` → VirtualResultList renders visible rows
5. Grouping (if active) is applied client-side by `groupResults` utility
6. Facets (when enabled via `facetsEnabled` filter) are included in the same search API response — not a separate call. They provide dynamic filter options (file_kind, language, mime_base; max 10 each, scanning up to 20,000 docs). Facets are only requested on the initial search, not on load-more pagination.

### Query Correction

The backend returns `correctedQuery` and `correctionApplied` fields when automatic query correction occurs. (Search Thread S5b: the standalone `SearchSurface.ts` this section pointed at is retired — the retrieve tier now lives in `shell-v0/views/UnifiedChatView.ts` + `shell-v0/components/searchResults/ResultsCard.ts`; this correction-banner behavior predates that migration and was not found live in either — pre-existing doc/code drift, logged for a fuller pass.)

### Zero Results

When a search returns no results, `ZeroResultsView` displays suggestions: "Try broader or different search terms", "Check spelling", and (when filters are active) "Remove active filters."

### Pagination

- Initial search returns a page of results (default 50)
- Cursor-based pagination (TEXT mode): reaching the end triggers fetching more via `nextCursor`
- No-cursor mode (HYBRID/VECTOR): re-fetches with expanded limit, deduplicates by ID
- `loadingMore` state prevents duplicate fetch requests
- New pages append to existing results; new searches replace results entirely

---

## 3. Search Input (Zone A — Command Bar)

### Text Entry
The input accepts three modes, distinguished by prefix:
- **Plain text**: Standard keyword search
- **`/` prefix**: Command mode (not search)
- **`??` prefix**: Chat/AI mode — expands to text area for questions about indexed files

### Query Syntax
Two syntax modes, toggled via a button next to the input:
- **Simple** (default): Natural keyword matching with prefix expansion and automatic typo correction
- **Lucene**: Full Lucene query syntax (field queries, boolean operators, wildcards). No auto-correction.

A small **"?" help button** appears next to the syntax toggle, linking to syntax documentation.

The active syntax mode is persisted to localStorage and survives restarts.

### Debounce and Execution
- Typing triggers a search after a 120 ms debounce (both local and remote)
- Each new keystroke resets the debounce timer
- In-flight searches are cancelled via AbortController when a new search triggers
- No manual "search" button — execution is automatic

### Autocomplete
- `useSuggest` hook calls `GET /api/knowledge/suggest` with 200 ms debounce
- Minimum 2 characters to trigger suggestions
- Backend performs prefix matching on titles and filenames (title matches boosted 4x prefix, 2x infix; deduplicated)
- Dropdown renders below the search bar with Search icons and suggestion text. Matching portions of each suggestion are highlighted with a teal-tinted `<mark>` element (case-insensitive substring match); suggestions that don't contain the query as a substring render without highlighting
- Keyboard navigation: Arrow Up/Down to select, Enter to accept, Escape to dismiss
- Tab accepts ghost text inline completion
- Only active in search mode (not command or chat modes)

### Clear Button
An X button appears inside the search input when the query is non-empty. Clicking it clears the query and refocuses the input.

### Search History
- When the input is focused with an empty query, a dropdown shows recent searches
- History stored in localStorage (max 30 entries) via `useSearchHistory` hook. The dropdown shows up to 10 most recent entries.
- A "Clear" button in the dropdown header removes all history entries
- Each entry displays a relative timestamp (e.g., "5m", "2h", "3d")
- Each history entry can be removed individually (X button on hover)
- The dropdown closes on Escape, blur (200 ms delay), or when the user starts typing
- Selecting a history entry populates the input and triggers search
- The history dropdown supports full keyboard navigation: Arrow Up/Down to highlight entries, Enter to select, Escape to dismiss. ARIA attributes (`role="listbox"` on the container, `role="option"` and `aria-selected` on entries) provide screen reader support.

### Loading Indicator
- An animated pulsing teal dot appears inside the command bar (Zone A) during active searches
- Disappears when results arrive or search errors out
- This is separate from the StatusDeck (Zone E) "Searching..." indicator — both fire simultaneously but are independent UI elements

### URL Sync
The query and all active filters sync bidirectionally with the browser URL:
- Parameters: `q` (query), `type`, `sort`, `lang`, `date`, `path`, `facets`, `mime_base`, `metaSource`, `metaAuthor`, `metaCategory`
- Uses `history.replaceState` (not pushState) with 150 ms throttle
- Default values are omitted to keep URLs clean
- URL is read on mount to restore state from shared/bookmarked URLs
- Hash fragment is preserved during updates

---

## 4. Filter Bar

Located between the command bar and the result list. Collapsed by default, expands on interaction.

### Available Filters

| Filter | Options | Default |
|--------|---------|---------|
| Scope | Source folders / path prefix | All |
| Type | all, pdf, markdown, images | all |
| Sort | Relevance, Newest, Oldest | Relevance |
| Group by | none, type, folder, date | none |
| Date range | any, 24h, 7d, 30d | any |
| Language | Dynamic list from facets | — |
| MIME base | Multi-select pill toggles (text, image, application, etc.) | — |
| Path prefix | Free-text path filter (Detailed mode) | — |
| Facets toggle | Enable/disable facet computation | off |

### MIME Base Facet Toggles
When facets are enabled and results contain mixed MIME types, MIME base options appear as pill toggles (up to 50 values requested from the backend). The first 8 are shown by default; when more than 8 exist, a "Show N more..." link expands the full list in-place (toggles to "Show less" when expanded). Each pill shows the MIME type and document count. Active toggles use teal accent styling. These are dynamic — they reflect the actual MIME distribution of current search results.

### Metadata Facet Filters

Three metadata facet sections — Source (`metaSource`), Author (`metaAuthor`), Category (`metaCategory`) — appear in `SearchFiltersBar` when facets are enabled and metadata values exist in results. They follow the same data-driven pill/toggle pattern as entity facets:

- **Search**: Each section has a search input to filter the facet value list
- **Expand/collapse**: First 8 values shown by default; "Show N more..." link expands the full list (toggles to "Show less")
- **Toggle pills**: Each value renders as a pill toggle with the value label and document count. Active pills use teal accent styling. Toggling a pill adds/removes that value from the corresponding metadata filter array

Active metadata filters appear as removable chips alongside other filter chips (see Active Filter Display below).

### Density Toggle
A segmented control on the right side of the filter bar with three options: Compact (rows icon), Comfort (balanced rows icon), Rich (expanded rows icon). Updates both the layout store and system settings (persisted).

### Active Filter Display
- Each active (non-default) filter appears as a removable chip below the bar
- Chips show the filter name and value; clicking the x removes that filter
- Active metadata filter chips display for Source, Author, and Category selections
- A "Clear" button appears when any filter is active, resetting all filters to defaults and grouping to "none"
- `hasActiveFilters` checks entity array lengths AND metadata array lengths (`metaSource`, `metaAuthor`, `metaCategory`) in addition to scalar filter values

### Bulk Action Bar
When one or more results are multi-selected, the filter bar's content swaps to a bulk action bar within the same row: "{N} selected" label, Deselect, Select all, Copy paths, Open all, and Actions buttons. Filter dropdowns and chips are hidden during bulk selection. DensityToggle remains visible in both modes. No layout shift — the bar height stays constant. The Actions button opens the Action Panel (Section 10).

### Grouping
When group-by is set to type, folder, or date:
- Results are organized under collapsible section headers
- Each header shows a chevron (collapsed/expanded), a label, and a count
- Clicking a header toggles collapse state
- Collapsed groups hide their results but remain in the list
- Keyboard navigation skips headers and collapsed items
- Auto-enabled when results contain 2+ distinct types (P16)

Grouping is a display preference stored in `useLayoutStore` with localStorage persistence. It does not affect the API query — all results are fetched, then grouped client-side.

### Sort and Grouping Interaction
When both sort and group-by are active, grouping takes precedence over sort for top-level ordering. Items arrive sorted from the API (by relevance, date, etc.), then `groupResults` re-organizes them into groups. Within each group, items retain their original API sort order. Groups themselves are ordered by category (type: pdf → note → code → image; date: Today → Yesterday → This Week → This Month → Older).

---

## 5. Result List (Zone C — Stage)

### Virtualization
The result list uses the `virtua` library (VList component, v0.48.3) for virtualized rendering. Only visible rows exist in the DOM at any time. This handles 10,000+ results without performance degradation. Item heights are automatically measured via ResizeObserver and cached by key.

### Density Modes
Three density modes control how much information each result row shows. Switched via the inline DensityToggle in the filter bar. Density is a global layout property — the `data-density` attribute on `<html>` drives CSS spacing tokens used across all views (search results, Library, Brain, Health, Settings).

#### Compact
- Smallest rows: `py-1.5 px-3`, 24px icon container
- Shows: category icon, title (12px), 1-line snippet (12px, tertiary color), inline size
- Hides: match pills, path/breadcrumb, "More" expand button
- Expansion state is ignored — switching to compact while a snippet is expanded in another mode shows the 1-line clamp
- GroupHeader uses reduced padding and text size (`py-1`, `text-xs`)
- Best for: scanning large result sets quickly

#### Comfort (Default)
- Medium rows: `py-2 px-3`, 32px icon container
- Shows: category icon, title (14px), snippet (2 lines, 13px, expandable to 8), match pills with counts, breadcrumb (3 parent segments), inline size+date
- Best for: balanced scanning with enough context

#### Rich
- Largest rows: `py-4 px-4`, 36px icon container, card background (`rounded-lg bg-[var(--glass-surface)]/50`)
- Shows: category icon, title (14px), metadata line (file kind · size · date · language badge · relevance score), breadcrumb (3 parent segments), match pills with counts (above snippet), snippet (4 lines, 13px, expandable to 8)
- Metadata line: file kind derived from type (PDF, Markdown, Code, Image, Document), language from backend `language` field, score prefixed with `rel`
- Language badge has border for light theme visibility
- Best for: detailed examination of individual results

### Result Row Anatomy

Each result row contains (visibility varies by density):

1. **Category Icon** — Color-coded icon in a rounded container indicating document type:
   - File (generic): neutral gray
   - PDF: coral/red
   - Note/Markdown: amber
   - Image: magenta
   - Code: green
   - Icon container uses the category color at low opacity for background, stronger opacity for border

2. **Title** — The document title or filename. Search term matches are highlighted with bold warm-gradient backgrounds (dark mode) or cool-gradient backgrounds (light mode). Title highlights are visually prominent: weight 600, padded, with a subtle inset underline shadow.

3. **Breadcrumb** (comfort and rich modes) — `ParentBreadcrumb` shows up to 3 parent directory segments with `›` separators (e.g., `justsearch › docs › explanation ›`). Full file path available on hover via `title` attribute. Replaces raw file paths in both comfort and rich modes.

4. **Metadata Line** (rich mode only) — Structured line below the title showing file kind label (PDF, Markdown, Code, Image, Document), size, modified date, language badge, relevance score (prefixed `rel`), and metadata badges for `metaSource`, `metaAuthor`, and `metaCategory` when present. Metadata badges render as small pills in the metadata line, providing at-a-glance document provenance. Consolidates metadata that was previously inline with the title.

5. **Snippet** (all modes) — Content excerpt rendered by `SnippetBlock` component. In compact mode: 1-line static snippet from `content_preview`. In comfort and rich modes: when the backend returns excerpt regions (query-focused windows from the full document content), `ExcerptRegionsBlock` replaces the static snippet. Each region shows a `~line N` label and highlighted text from the actual match location, separated by `···` dividers. Default visible regions: 1 (comfort), 2 (rich); expand to see all via "More"/"Less". When no excerpt regions are available (e.g., match only in first 4K, or vector-only mode), falls back to `content_preview` with line-clamp: 2 lines (comfort), 4 lines (rich), expandable to 8. The "More" button renders when content overflows the line-clamp (ref-based overflow detection) or when additional excerpt regions are hidden. Snippet highlights are intentionally subdued compared to title highlights: lower opacity background, weight 500, minimal padding, no shadow.

6. **Match Pills** (comfort and rich modes) — Small pills indicating which fields matched the query. Field labels: "title", "path", "semantic". Content matches are hidden (redundant with snippet). Each pill uses semantic coloring: amber for title, teal for semantic, neutral for others. Shows match count (e.g., "title x3") when count exceeds 1. In rich mode, match pills render above the snippet for correct reading order (why it matched → content preview).

7. **Checkbox** (all modes) — Multi-selection checkbox with glass styling, visible on hover or when the row is multi-selected. Teal when checked, with a pop animation on toggle (150ms transition).

8. **Hover Action Buttons** (all modes) — Small action buttons (copy path, open, reveal in folder, summarize if AI available, more actions) that fade in on row hover (`opacity-0 → opacity-100`, 150ms transition) and fade out on mouse leave. Positioned at the right edge of the row. On the keyboard cursor row, action buttons become Tab-focusable (`tabIndex=0`) and are visually revealed without hover. On non-cursor rows, buttons remain `tabIndex=-1`. A wheel-scroll listener blurs any focused action button to prevent stale focus when Virtua recycles DOM nodes.

9. **Help Badge** — Results with `collection === 'justsearch-help'` display a teal "Help" pill, distinguishing built-in help files from user documents.

### Expandable Snippets
In Comfort and Rich modes, `SnippetBlock` supports two expansion modes depending on content:

1. **Excerpt regions** (when backend returns `excerptRegions`): Shows 1 region by default in comfort, 2 in rich. "More" expands to show all regions; "Less" collapses back. Each region is a ~400-char window around actual match positions in the full document, with `~line N` positional labels.

2. **Static snippet fallback** (when no excerpt regions): Renders `content_preview` with line-clamp. "More"/"Less" toggles between clamped (2/4 lines for comfort/rich) and expanded (8 lines). The "More" button only renders when content overflows the line-clamp (detected via `scrollHeight > clientHeight`).

In compact mode, expansion is suppressed — `effectiveExpanded` gates on `density !== 'compact'`, so switching to compact while expanded shows the 1-line clamp. Excerpt regions are not requested in compact mode (`includeExcerpts` is conditional on density). This replaced the former Browse Panel (Section 8).

### Animations and Transitions
- **Row hover/state**: 100ms `transition-all` on background, border, shadow changes
- **Selection scale**: Selected rows scale to `1.01` (subtle lift effect)
- **Checkbox pop**: 150ms transition on check/uncheck toggle
- **Hover actions**: 150ms opacity fade on row action buttons
- **Icon badge**: 150ms transition on category icon hover

### Reduced Motion Support
The UI respects `prefers-reduced-motion`. When the OS setting is enabled:
- Decorative CSS keyframes (`shimmer`, `checkbox-pop`, `brand-mark-pulse`) are suppressed via `@media (prefers-reduced-motion: no-preference)` wrapper
- Framer-motion spring and stagger animations fall back to a quick crossfade (`duration: 0.1s`) via a shared `useMotionConfig()` hook
- Functional animations are preserved: loading spinner (`spin`), status pulse (`pulse-glow`), toggle slider spring, ActivityRail active indicator

### Context Menu
Right-clicking a result row opens a context menu with:
- **Open** — Open file in default application
- **Reveal in Folder** — Show in file explorer (Tauri desktop only; disabled with tooltip in browser)
- **Copy Path** — Copy full file path to clipboard
- **Summarize with AI** — Generate AI summary (requires AI availability)
- **Find Similar** — Search for similar documents
- **Reindex** — Trigger re-indexing of the file

Actions that require unavailable capabilities are disabled with explanatory tooltips.

The context menu supports full keyboard navigation: Arrow Down/Up to cycle through items (wrapping), Home/End to jump to first/last item, Enter or Space to activate the focused item, and Escape to close. Focus automatically moves to the first enabled item when the menu opens. Disabled items are skipped during keyboard navigation. Menu items use roving `tabIndex` and `role="menuitem"` attributes.

---

## 6. Selection and Navigation

### Selection Model

Three selection concepts coexist, but cursor and selection are synchronized:

| Concept | Trigger | Visual | Purpose |
|---------|---------|--------|---------|
| **Cursor** | Arrow keys, j/k | Tracks selection | Keyboard navigation position |
| **Selection** | Arrow keys, j/k, click | Teal background tint, enhanced border/shadow, 1% scale | Opens Inspector panel for the selected document |
| **Multi-selection** | Checkbox click, Space, Ctrl/Cmd+click | Amber background tint, checked checkbox with glow | Batch operations (AI summarize, ask question) |
| **Range selection** | Shift+Click | Selects all items between anchor and target | Contiguous multi-select (desktop convention) |

- Arrow keys move cursor AND selection simultaneously — they are kept in sync within the search results presentation (Search Thread S5b: `shell-v0/views/SearchSurface.ts` is retired; the retrieve tier lives in `shell-v0/views/UnifiedChatView.ts` + `shell-v0/components/searchResults/ResultsCard.ts`). Separate `cursorFileId` and `selectedFileId` state fields are maintained, but in practice they stay in sync.
- Clicking a row sets selection (opens Inspector) AND moves cursor
- Clicking the same row again deselects it and closes the Inspector (toggle behavior)
- Space toggles multi-selection on the current item
- Ctrl/Cmd+clicking toggles multi-selection without changing single selection
- Shift+clicking selects all results between the last-clicked item (anchor) and the Shift-clicked item, inclusive. Selection is additive — existing selections outside the range are preserved. The anchor is tracked by document ID (stable across pagination)
- Multi-selection is automatically pruned to visible results on every search — IDs that no longer appear in results are removed, but IDs that survive into a new result set stay selected
- Double-clicking a row opens the file directly (same as Ctrl/Cmd+Enter)
- After a new search completes, the first result is auto-selected and the Inspector is populated

### Keyboard Navigation

| Key | Action |
|-----|--------|
| Arrow Down / j | Move cursor and selection to next result |
| Arrow Up / k | Move cursor and selection to previous result |
| Page Down | Move cursor 10 results forward |
| Page Up | Move cursor 10 results back |
| Home | Move cursor to first result |
| End | Move cursor to last result |
| Enter | Open the selected file directly |
| Ctrl/Cmd+Enter | Open the selected file directly (alternative) |
| Double-click | Open the file directly |
| Space | Toggle multi-select on current item |
| Escape | Priority chain (see below) |
| Tab | Move focus to next zone |
| Shift+Tab | Move focus to previous zone |
| Cmd/Ctrl+K | Focus search input |
| Cmd/Ctrl+\ | Toggle Inspector panel |
| Cmd/Ctrl+Shift+P | Open Action Panel |

### Escape Priority Chain

Escape executes one level per keypress, in order:

1. **Dismiss autocomplete dropdown** — if suggest dropdown is open
2. **Dismiss history dropdown** — if search history dropdown is open
3. **Close Action Panel** — if the action panel modal is open (scoped to modal)
4. **Clear active filters** — if any non-default filters are active, reset to defaults
5. **Clear query and blur** — clear the search input text and blur focus

### Accessibility
- Result list container: `role="grid"`, `aria-label="Search results"`
- Each result row: `role="row"`, `aria-selected` (reflects selection state), `aria-label` with title and type
- Multi-select checkboxes: `role="checkbox"`, `aria-checked`
- Focus management via `tabIndex={0}` on the list container

---

## 7. Search States

### Zero-Query State (Launchpad)
When the search box is empty, the Stage does not show the result list. Instead, a `LaunchpadGrid` component renders with adaptive content based on a 4-state machine:

| State | Condition | Greeting | Content |
|-------|-----------|----------|---------|
| **fresh** | No roots, no documents | "Welcome to JustSearch" | Setup CTA to add a folder via Library + privacy statement |
| **seeded** | No roots, has documents (help files only) | "Welcome to JustSearch" | Setup CTA + help topic cards + privacy statement |
| **indexing** | Has roots, pending jobs | "Indexing your files…" | Progress indicator + help topic cards |
| **ready** | Has roots, indexed documents | Time-based greeting (Good morning/afternoon/evening) | File count + help topic chips |

**Help topic cards / search example chips:** Content varies by state:
- **fresh/seeded/indexing states**: 5 clickable cards with descriptions, derived from the built-in P23 help files: Getting Started, Keyboard Shortcuts, Search Syntax, AI Features, Troubleshooting. Clicking any card executes a search for that topic.
- **ready state**: A "TRY A SEARCH" header with 5 compact clickable search-example chips: readme, justsearch, config, api, todo. Clicking a chip populates the search bar and executes the query.

**Setup CTA:** On fresh and seeded states, a prominent prompt encourages the user to add their first folder via the Library view.

**Privacy statement:** On fresh and seeded states, a line below the subtitle reads: "Everything runs on your machine — your files never leave this device." This disappears automatically when the user adds a folder (advancing to indexing/ready states).

**Compact navigation row:** Four buttons at the bottom: Add folder, Configure AI, Health, Actions — providing quick access to key workflows without using the Rail.

### Empty State (Zero Results)
When a query returns no results, `ZeroResultsView` renders:
- SearchX icon (large, muted)
- "No results for [query]" heading
- Corrected query info (if backend provided one): "Also tried [correctedQuery] — no matches found."
- Suggestions checklist: try broader terms, check spelling, remove active filters (clickable if filters active)
- Recent searches section (up to 5 clickable chips, excluding current query)

### Error State
Search errors render an AlertCircle icon with "Search failed" heading and the error message. The global connection-loss overlay handles backend death separately.

### Command/Chat Empty States
- `/command` with no match: Terminal icon, "No matching command", hint to try `/help`
- `??chat` before response: MessageCircle icon, "Ask a question", hint about indexed files

### Pagination Footer
When more results are available beyond the current page (`hasMore && onLoadMore`), a pagination footer appears with "Showing {N} of {total}" and a "Load more" button. The footer only renders when there are additional results to fetch — it is not a permanent element. The count label is secondary to the pagination control. This is separate from the Status Deck result count.

### Scroll Behavior
When a new search executes, `searchGeneration` counter increments and triggers `VirtualResultList` to `scrollToIndex(0)`.

---

## 8. Browse Panel (Removed)

The browse panel was removed by P15. Its only unique value (snippet preview) is now provided by inline expandable snippets on result rows (2→8 lines via chevron toggle). The `isCompactMode` flag that controlled its visibility is no longer referenced.

---

## 9. Document pane (Zone D)

Selecting a result opens a tabless reading pane inside the Search conversation zone. The pane owns
document preview and navigation only; it does not create a second Answer or Ask workspace. Grounded
questions are sent with **Ask** in the same Search thread, and citation navigation focuses the relevant
document passage. `inspectorState.ts` retains the historical name as an internal compatibility detail.
- Context indicators: safety, token count, RAG status
- Expandable context details: chunks used/found, context size, document list
- Clickable citation list with relevance scores and excerpts
- AI triggers (Summarize, Ask) auto-switch to AI tab

### AI Unavailable
- When AI is unavailable: AI tab is hidden, only Preview tab shown
- Zero AI overhead — no tab bar clutter when AI features are off

### Multi-File Mode
When multiple files are checked:
- Tab bar header shows file count
- Batch summarize is available
- Questions are answered across all selected files

---

## 10. Action Panel

A global command palette opened via Cmd/Ctrl+Shift+P (or the "Actions" button in the filter bar when items are multi-selected).

### Appearance
- Modal overlay with backdrop fade animation
- Centered panel with spring animation (stiffness 400, damping 30)
- Filter input at the top for searching available actions
- Arrow key navigation through the action list

### Contents
- Searchable list of file actions: Open, Reveal in folder, Copy path, Summarize with AI, Ask a question, Find similar, Reindex
- Actions grouped by availability (available first, unavailable grayed out with explanations)
- Selection count indicator showing how many files are targeted
- Keyboard shortcut hints shown in the footer bar (not per-action)
- Only "Summarize" supports batch operations on multi-selected files; all other actions are single-selection only

### Behavior
- Escape closes the panel (level 3 in the Escape priority chain)
- Selecting an action executes it against the current selection
- Unavailable actions are shown but disabled with tooltip explanations

---

## 11. Visual Design System

### Glass Aesthetic
The entire UI uses a "glass morphism" design language:
- Surfaces use translucent overlays (white tint in dark mode, cool navy tint in light mode)
- Three surface levels: base (3% opacity), hover (6%), active (10%) in dark; (5.5%, 10%, 14%) in light
- Borders use the same tint color at varying opacities
- Elevated panels get backdrop blur (20px base, 30px elevated) and saturation boost (1.1-1.15x)

### Color System
Colors use the OKLCH color space for perceptual uniformity:
- **Text hierarchy**: primary (95% opacity) > secondary (75%/82%) > tertiary (55%/68%) > muted (35%/68%) > ghost (20%/28%)
- **Category colors**: Each document type has a dedicated hue — blue (doc), green (code), magenta (image), amber (note), coral (pdf)
- **Accent colors**: Teal for selection/interactive elements (`oklch(75% 0.15 180)` dark / `oklch(45% 0.18 180)` light), amber for scores/warnings, purple for command mode
- **Semantic colors**: Success (green), error/danger (red, `oklch(65% 0.20 25)`), warning (amber, `oklch(75% 0.18 70)`)

### Themes
Two themes: dark (default) and light. Controlled via `data-theme` attribute on the root element.

Key differences between themes:
- Dark mode: white-tinted glass surfaces, lighter text, warm highlight gradients (amber/gold)
- Light mode: navy-tinted glass surfaces, darker text, cool highlight gradients (blue/teal)
- Category colors shift: dark uses higher lightness (70%), light uses lower lightness (45%) with higher chroma
- Accent badges: dark uses lighter amber, light uses darker amber

Theme is persisted to localStorage and toggled in Settings.

### Highlight Styles
Two tiers of highlight treatment ensure visual hierarchy:

**Title highlights** (primary):
- Gradient background (warm in dark, cool in light)
- Font weight 600
- Generous padding (0.1em 0.25em)
- 3px border radius
- Inset underline shadow

**Snippet highlights** (secondary):
- Flat low-opacity background
- Font weight 500
- Minimal padding (0.05em 0.15em)
- No shadow

This ensures the eye is drawn to title matches first during scanning.

---

## 12. Settings That Affect Search

| Setting | Stored In | Effect |
|---------|-----------|--------|
| Density | localStorage via useLayoutStore | Changes result row height and visible information |
| Group by | localStorage via useLayoutStore | Adds section headers and collapse behavior |
| Query syntax | localStorage via useSearchStore | Switches between simple and Lucene syntax |
| Theme | localStorage | Switches all colors between dark and light palettes |
| High contrast | Backend `/api/settings/v2` (`ui.highContrast`) | Increases contrast for accessibility |

**Correction (tempdoc 855, 2026-08-19):** High contrast is not a `localStorage`-only preference like
the rows above — it is the canonical, backend-persisted `UISettings.highContrast` field, round-tripped
through `POST /api/settings/v2` like theme (see `secondary-views-behavior.md` §5 Settings persistence
and its §2 Appearance supersession note).

These are display preferences only — they do not affect the search API query or ranking.

Density can be changed from the inline DensityToggle in the filter bar during search.

---

## 13. Status Deck (Zone E)

The 32px bottom bar shows system telemetry and ambient feedback.

### Always Visible

- **Connection pill**: "Connected" with Wifi icon (green success styling) when connected; "Disconnected" with WifiOff icon (red danger styling with glow) when disconnected; "Demo mode" with Sparkles icon (purple dashed border) in demo mode
  - Tooltip: "Local service ready" / "Cannot reach local JustSearch service" / "Simulated results and AI responses"

- **Result count** (when > 0): Database icon + "{N} results" in glass pill
  - Tooltip: "Search returned N results"

- **Index count** (when > 0): HardDrive icon + "{N} indexed" in warm accent pill
  - Tooltip: "N files in the search index"

- **Pending jobs** (when > 0): Loader2 spinning icon + "{N} jobs" in teal accent pill
  - Tooltip: "N files waiting to be indexed"

- **Searching indicator**: A separate pulsing teal dot in StatusDeck (Zone E) + "Searching..." label during active searches. This is independent from the command bar dot (Zone A) — both render simultaneously.

- **AI Mode** (ModeIndicator component):
  - Online: Brain icon + "AI Online" in teal (tooltip: "AI engine is active — chat, Q&A, and summarization available")
  - Offline: Brain icon + "AI Offline" in tertiary gray (tooltip: "AI engine is idle — enable in AI Brain view")
  - Indexing: Cog spinning icon + "Indexing" in blue (tooltip: "AI engine is processing embeddings for semantic search")
  - Transitioning: Loader2 spinning + "Switching..." in amber

- **VDU queue** (when > 0): FileImage icon + "{N} VDU" in purple
  - Tooltip: "Analyzing visual content in N documents"

- **Embedding queue** (when > 0): Zap icon + "{N} embed" in blue
  - Tooltip: "Generating search embeddings for N files"

- **Next action hint**: Contextual suggestions based on system state (e.g., "Add a folder to get started", "Try a search query"). Note: only visible in certain system states, not always present.

### Alt-Reveal

Holding Alt shows additional information:

- **Processing time** (when > 0): Clock icon + "{N}ms" in muted text
  - Tooltip: "Search completed in Nms"
- **Hardware tier**: Gauge icon + tier label at 11px font size
  - gpu_12gb_plus: teal accent
  - gpu_8gb: amber warning
  - gpu_lt_8gb: amber warning
  - cpu_only: neutral tertiary gray
  - gpu_unknown: neutral tertiary gray
- **Memory usage**: Cpu icon + formatted bytes
- **Hotkey legend** (right side):
  - Enter → Open
  - Cmd+K → Search
  - Cmd+Shift+P → Actions
  - Tab → Zones
  - Cmd+\ → Inspector

### Permanently Visible (Right Side)

- **Alt hint**: "Hold Alt for shortcuts" (hidden when Alt is pressed)
- **Version number**: "v{version}" in ghost text — only rendered when provided by the backend; may not be visible in all environments.

---

## 14. Secondary Features

### Help View
Accessible from the ActivityRail (Zone B). A full-page view with two columns:

**Left column:**
- FAQ accordion with 5 expandable items (adding files, file types, AI summarization, indexing speed, AI chat)
- Quick Troubleshooting section with bullet-point guidance

**Right column:**
- Keyboard shortcuts table (9 shortcuts)
- Local-first info card (explaining privacy model)
- Network activity info card

**Header:** "Help & Support" with subtitle "Diagnostics export and local-first transparency". Includes an Export Diagnostics button (disabled in demo mode).

### Demo Mode
Activated via `?demo=true` URL parameter. Provides a fully offline experience:
- Uses 8 hardcoded mock files (`DEMO_RESULTS`)
- Search uses local case-insensitive substring matching on title/path/snippet
- No API calls, no facets, no pagination, no AI features
- Stores are exposed on `window.__JUSTSEARCH_STORES__` for E2E test manipulation
- Status Deck shows "Demo mode" pill instead of connection status

### Built-in Searchable Help Content
Five markdown help files ship in `SSOT/docs/help/` and are auto-ingested on startup with collection tag `justsearch-help`:
- `getting-started.md` (81 lines — comprehensive first-session guide)
- `keyboard-shortcuts.md` (full binding table including `??` Q&A prefix and autocomplete navigation)
- `search-syntax.md` (query modes, filters, zero-results page, autocomplete)
- `ai-features.md` (setup, Q&A via `??` and Inspector, RAG explanation)
- `troubleshooting.md` (symptom-based guidance with cross-references)

**Auto-ingestion:** `KnowledgeServerBootstrap.tryIngestHelpFiles()` runs after `reindexPersistedRoots()`. Uses a version-stamped marker file (`.help-ingested-version`) in the data directory for idempotency — only re-ingests when `HELP_FILES_VERSION` changes. Current version: `v2`.

**Collection threading:** `target_collection` field flows through: proto `BatchRequest` → `WorkerIngestService` → `SqliteJobQueue` (V4 schema migration adds `collection` column) → `IndexingLoop.buildDocument()` → `SchemaFields.COLLECTION` → Lucene field catalog (`fields.v1.json`).

**Frontend badge:** Results with `collection === 'justsearch-help'` display a teal "Help" pill in the result-row renderer (`shell-v0/renderers/`).
