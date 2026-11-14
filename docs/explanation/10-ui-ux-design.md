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
contents. Use the shipped Inter variable font and familiar interaction patterns. Primary actions must
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
  teal button; text actions use the normal text colour in medium weight.
- **Reserve teal** for “on or working now”: switches that are on, progress, and the lit
  steps of AI Brain, plus the one filled main action on a screen. Tabs, the active area
  and selected rows are neutral; information is grey.
- **Use the existing typography and surface tokens.** Inter ships with the app; 20 and 28 px
  text uses its display optical size through the variable font's optical-size axis. The six
  text sizes are 11 px for small labels, 12 px for secondary text, 13 px for body and controls,
  15 px for the search field and lead sentences, 20 px for headings, and 28 px for the page
  headline. The four corner radii are 4 px for marks and bars, 8 px for controls and rows,
  14 px for fields, cards, panels and dialogs, and fully round. Hairlines are
  approximately seven-percent white in the dark theme, with theme-appropriate contrast in
  light and high-contrast modes. The token names below are the implementation vocabulary;
  do not copy raw style values into components where the style-literal ratchet forbids them.

| Purpose | Existing tokens and source |
|---|---|
| Shipped Inter UI font | `--font-native` in `styles/tokens.css`; Search uses `--font-sans` in `views/search-v3/sv3-tokens.css.ts` |
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

### First use and Getting started

Search shows **Nothing to search yet** only after a successful folder-list answer and a
fresh, confirmed zero searchable-document count. Unknown, stale and failed observations
leave the ordinary composer in place. The empty state offers **Add a folder** and suggested
folders by name and path, with no estimated file counts. Both use Library's existing add
operation: a native picker on desktop and manual path entry in a browser. Registration does
not mean files are searchable.

The Getting started card has three steps: add folders, try a search, and optional answers
and search by meaning. Folder and AI completion come from current facts. Only a search
response received while files are searchable saves the search-completed mark. While files
are being read, the live count says “n files searchable so far”, with “m waiting to be read”
when the queue is known, or “still reading” when only activity is known. There is no promised
total or time estimate. A watched folder with no searchable files points to Library.

The core card finishes after adding content and trying a search; AI is optional. **Hide**
also removes its calm-status pill. **Help → Getting started** reopens the card until hidden.
Progress is cross-profile and shared across windows, using `walkthroughState['core.getting-started']`
in `justsearch.userState.v2`: `completedStepIds` holds `try-search` and, after Help reopening,
`reopened-from-help`; `dismissed` records Hide. The old welcome record is read without being
changed. Finished or dismissed old welcomes suppress the core card when folders are watched;
partial old progress completes no new step. A completed factory reset clears the state.

Plugin walkthroughs retain their registry, completion triggers and saved progress. Pending
plugin steps appear as a section of Getting started, including a plugin-only card once the
core steps finish. The old four-step core welcome contribution is retired. The pill says
“Getting started · n of 3” only at the calm status level; every higher status keeps precedence.
The card never navigates on its own or changes restored destinations or browser history.

Ordinary demo and fixture captures suppress the card so their established composition stays
stable. The `first-use` and `first-use-reading` captures deliberately seed no walkthrough
state and cover a fresh empty engine and a folder with files partly read.

## 2. Shell structure

`chrome/Shell.ts` renders one top bar over the window area. The page has three landmarks:
the top bar (banner), its area switcher (navigation) and the window area (main).

| Shell part | Responsibility |
|---|---|
| Top bar (`chrome/TopBar.ts`, `jf-top-bar`) | Brand; the area switcher; the status pill; Settings; the More (⋯) menu |
| Area switcher | Search, Library and AI Brain, in that order (Ctrl+1, Ctrl+2, Ctrl+3; tooltips name the shortcut). The bar chooses its layout from its own room (after the desktop window controls), measured against the text size: below 1000 px of room at standard text only the active area keeps its visible label; below 720 px the bar shows the brand dot, one button naming the active area that opens the three areas as a menu, the pill as an icon, Settings and More; below 480 px (a 600 px window with enlarged text) that button keeps only its icon and the switcher leaves the centre |
| More (⋯) menu | System (Health, Activity, Logs), Help & shortcuts (F1) and About; windows contributed by the catalog or plugins under “More”; a Developer group (API explorer, Copy link to this view, Show backend address, Logs) while Settings › Advanced › Show developer tools is on |
| Window tab row | A window's own tabs at its top (Library: Folders, Browse; AI Brain: AI Brain, Memory; System: Health, Logs, Activity) and its window actions. Search has none |
| Stage | The active window; layouts can also compose a second window in a split stage, with a picker for the second pane |
| Details pane (`jf-details-pane`) | At most one per window, on its right edge: tabs that scroll rather than shrink, 360-560 px and resizable, inline from 980 px of window width, a sheet over the window below. Each window owns its details, so a split stage's two windows never show each other's selection |
| Status pill and panel (`jf-status-pill`, `jf-status-panel`) | One status in the top bar; the pill opens current conditions, work and earlier activity over the page |
| Overlay slots (`jf-overlay-host`) | Command palette, Settings, authorization dialogs and other transient surfaces in named slots. The frame has no side drawers |

`layout/LayoutManifest.ts` keeps its `rail`, `stage` and `statusBar` zones for saved
layouts and plugin layouts. `rail.visible: false` (Focus, Zen and custom layouts) hides the
area switcher; the areas stay reachable through Ctrl+1/2/3 and the command palette, and
`rail.surfaces` limits the windows the switcher and the More menu list.
`statusBar.visible: false` hides plugin status items in the status panel and the pill's
plugin count, never notices or needs-you items. A split stage keeps its second pane.
The retired rail and title-bar elements (`jf-rail`, `jf-recents-menu`,
`jf-bookmarks-popover`) stay in the presentation vocabulary as inert elements, so a
presentation that names one still loads. Saved bookmarks and the navigation journal stay
stored; the title bar's bookmark, recent-views and Copy URL controls and its Simple/Detailed
toggle are gone (Settings › Appearance › Detail level remains).

### Opening the window

`index.html` paints an inert frame before the application entry script: brand, the three area
names with none selected, a Starting pill, Settings and More symbols, and an empty work area.
Only “Starting” is announced. It has no controls, application stores, navigation selectors or
custom elements. Its inline styles project a small set of canonical tokens at build time.

`justsearch.bootFrame.v1` is a disposable localStorage paint cache: light/dark mode and six
resolved colours (background, raised surface, text, muted text, border and accent). It follows
the live appearance after successful appearance and presentation restore. Later appearance,
palette, presentation and profile changes go through `boot/bootFrame.ts`'s
`installBootFramePersistence` seam, which subscribes to completed appearance writes and saves resolved token changes. A failed or partial
restore preserves the previous cache. Invalid values and unavailable storage fall back to the
OS preference; `?theme=light` or `?theme=dark` takes precedence. A factory reset clears the cache
with the other window storage before any application store loads.

Recovery and reset retain ownership of the root. Desktop connecting gives the frame back when
an engine address is available. `mountBootApplication` keeps it above the attached shell until
Lit has completed the shell and top bar's first render and a paint opportunity. A terminal event
or reset replacing the root during that hand-off wins.

If the entry script or one of its static dependencies cannot load or evaluate, bootstrap rejects,
or a browser has no engine address, a static page says **JustSearch couldn't open its window**.
The same page replaces a frame still showing after 60 seconds. It offers Reload and Copy report,
starts keyboard focus on Reload, and works without application code. Clipboard refusal reveals
a selectable report. The report contains only the release version from `gradle.properties`, a
fixed failure kind, desktop/browser, OS family and ISO failure time. It never reads error text,
URLs, paths, hosts or keys. Optional boot failures remain optional. A late successful first
render replaces the failure page; a deadline cannot replace recovery or reset. If connecting
held the root beyond the deadline, the deadline still applies when the frame returns.

A settings document from a newer build remains byte-identical through startup and repeated
reloads: presentation fallback applies its runtime bodies without saving defaults or history.
Explicit edits during that older-version session can still overwrite the newer document.
After the first render, navigation and Search loading belong to the shell. Host page-serving
failure, onboarding and engine loss after mount are outside this startup frame's scope; engine
loss after mount is described in [Losing the engine](#losing-the-engine).

The desktop window has no system title bar. One window layer beside the root, created by the
page's own script before any application code, holds Minimize, Maximize/Restore and Close at the
right end of the top area in every window state: the start-up frame, the failure page,
connecting, engine and reset recovery, and the running app, including the hand-off while the app
beneath the frame is still blocked. Close hides the window to the tray, as the platform close
does. The maximize control reads the window's state and follows every change. The empty top area
of each surface moves the window and a double-click maximizes or restores it; the top bar's
areas, status pill, Settings, More and their menus never start a drag. The top bar keeps room for
the controls. They are named icon buttons of one size at any text size, never below a 24 by 24 px
target, so enlarged text changes the bar's layout rather than their room. They take their colours
from the surface beneath them: the saved start-up colours while the frame or the failure page
shows, the app's theme afterwards. Their focus ring uses that surface's text colour, because some
palettes' accents are too faint on their own surfaces, and is drawn just outside the control, so a
hover or pressed fill never hides it. In forced colours the ring is the surface's text colour and
the hover fill is the selected-item pair, which stays distinct from it. The browser has neither
the controls nor drag regions. While a modal dialog such as Settings is open, the controls wait
until it closes.

### Addresses, Back and Forward

Every view has a `justsearch://surface/<id>` address in the URL hash. A window's open tab is
part of it (`?tab=<tab id>`, for example `core.library-surface?tab=core.browse-surface`), and
so is the Settings section (`core.settings-surface?section=<section>&anchor=<entry>`). Switching a window
tab adds a history entry, so Back returns to the tab before; changing the Settings section
replaces the address, so closing Settings stays one step. The section in the address always
wins over the one Settings remembers from its last opening. Member and retired surface IDs
remain deep-linkable and open their host window on the right tab.

Back and Forward (Alt+Left/Right and the mouse's back and forward buttons) run on the
browser's own history, so the address and the window shown never disagree; the navigation
journal follows each entry by an identifier stored with it and resumes the last view on a
bare launch. Back does nothing on the first view the app owns. Alt+Left/Right navigate from a
text field too (they edit no text), and nothing under them also acts on them. Every
navigation is one entry, including one made from the command palette. Settings opens over
the area it was reached from; its entry remembers that area, so a reload keeps it underneath,
and closing Settings (Escape, the close button, the backdrop or Back) returns to it once:
Back never reopens Settings from there.

### Main windows

- **Search** is one interaction window for retrieval, cited answers and delegated work.
  It starts in Search (keyword retrieval requires no model); the user can choose Ask or
  Delegate. A session sidebar keeps past conversations reachable, and at its end the agent
  runs that can be resumed, replayed or exported. Legacy mode-specific
  surface IDs remain compatibility addresses rather than extra destinations.
- **Library** manages indexed sources and folders, their state, exclusions and recovery actions.
  A folder's failed files list under the folder and open and close with it, scrolling past a set
  height; its `N failed` chip and its problem both open the folder and go there.
- **AI Brain** presents AI installation and readiness; engine configuration sits behind
  its own Advanced settings disclosure, independent of the app-wide Detailed mode. It surfaces schema incompatibility and enrichment readiness
  rather than treating a drained job queue as complete semantic coverage.
- **System** hosts diagnostic surfaces including Health, Logs and Activity, reached from
  More › System. Health shows observed system and indexing facts and the recent inference
  transition history. Activity also holds background runs and their launcher, what the agent
  did in the open run, and the effect journal with its undo.
- **Settings** is a modal window opened from the top bar's Settings button, the command
  palette or a deep link.

### Settings window

`core.settings-surface` uses `Placement.MODAL`. Navigating to it — from the top bar, the command palette, or a deep-link URL —
opens `<jf-settings-window>` (`shell-v0/chrome/SettingsWindow.ts`), a chrome-level
element in `OverlayHost`'s `center` slot (the same dock as the command palette),
instead of mounting into the Stage; the Stage's active surface is untouched
underneath. Its dialog behavior follows the `ConfirmDialog` idiom: a native
`<dialog>` driven by `ModalController` supplies focus-trap, `inert`, and Top Layer;
ESC, backdrop-click, and an X button close it. Closing is history-aware — it either
navigates back (if opening the window pushed the current address) or forward-navigates
to the underlying stage surface (covering the case where Settings was the boot/deep-link
entry) — so Back is always meaningful and never doubles up with a real browser Back.

Inside the window, one register in `shell-v0/views/settingsRegister.ts` drives navigation,
rendering, search and address resolution. Its seven sections, in order, are General,
Appearance & skins, Security & privacy, Data & reset, Plugins, Advanced and About.
General is the first page on a fresh opening; the running app remembers the last section
without saving a new preference. Explicit addresses win over that memory.

Existing category, member and entry addresses resolve to their new section and anchor.
An explicit entry wins over its former category's default. Changing section clears the
old anchor; section and anchor changes replace the address, keeping close a single step.
Ctrl+comma (Command+comma on macOS) opens Settings from text fields as well as the page.
It keeps an open Settings window in place and yields to another modal, including the palette.
User and plugin bindings retain precedence. Help and the Settings button name the shortcut.

Appearance mounts Gallery and Editor as separate, stable catalog members alongside the
existing appearance controls. Security & privacy mounts `core.security-surface` and keeps
grants, plugin permissions and local ranking feedback separate. Their catalog member IDs
remain deep-linkable. Token Editor stays a launch link to its plugin surface, per
[ADR-0035](../decisions/0035-fe-plugin-boundary.md). View tier and the existing audience-gated
Workspace profiles are under Advanced, behind the always reachable Show developer tools switch.
This local preference belongs to each user-state profile. Until explicitly toggled it follows the
view tier (on for Developer, off for User, Operator and saved Agent); toggling never changes the
view tier. Workspace profiles still require the Developer view tier, and Plugin permissions keep
their own audience rule. Hidden links explain whether tools or the view tier hides the row and
focus the switch without enabling it. Settings search, navigation and More update live when
the preference or view tier changes. More › Developer › Logs opens System › Logs; Health keeps
its API endpoint readout. Backend Reset to defaults leaves the preference alone; full local-data
deletion removes it. The switch remains usable in backend Session-only mode. Reset to defaults is under Data & reset with its
existing confirmation and recovery exception. About names the web bundle version source
and the desktop updater's observed information when available; update actions stay in General.

### Status pill and panel

One status pill in the top bar projects shared state (`components/StatusPill.ts`,
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
  state and Mark all read. Reversal actions appear only where a real reversal exists and
  only while it can still run (see [Confirming and undoing](#confirming-and-undoing)).

Plugin status items render in a separate plugin row. The first item always shows,
wrapping inside the row; further items that do not fit collect under “… N more”. The footer links to Activity (“All activity”) and Health. An empty panel says
whether search is up to date or its status is still being checked.

Feedback from the user's own actions appears inside the pill for a few seconds. While
the pill shows engine unreachable, search limited, needs you or problems, that feedback
appears beside it instead. In both cases it remains available in Earlier. The top bar
makes room for the pill's words rather than truncating them; below 720 px the pill is its
icon, the notice's words move under the top bar (wrapping rather than cut) and the notice
keeps its action. A request waiting for the reader appears only under
Needs you; it joins Earlier once answered, expired or withdrawn. Status
transitions and action feedback are announced to screen readers. The panel updates
while open, preserving expanded details; switching windows closes it. The Help surface
supplies keyboard guidance.

#### Notices outside the window

The app interrupts only when the person is not looking. With the window focused nothing
leaves the app, wherever in it they are: the pill, its announcement and the panel are the
notice. In the background (the window hidden, minimised or not focused) one desktop
notification is shown for each live event: an agent request arriving, or long work
finishing (the events the pill shows as just finished) or failing. Startup snapshots,
reconnects and anything found late never notify. A notification says who and what in the
words of the panel's first line, with a request's deadline; it never carries argument
values, file contents or failure messages. Clicking it brings the app forward and opens the
panel on that item, or the review for an agent request. An open dialog stays on top: the click
is remembered and opens when the dialog closes. The exception is an agent request's own dialog,
which gives way to the request clicked, and that first request keeps waiting. If the item has
gone by then, no review opens and the panel says so. Work whose end was not observed live is
not announced either; Earlier still lists it.

The taskbar or dock badge counts Needs you, updates live and clears at zero. Windows has no
badge count, so the taskbar shows the count as an overlay on the icon; a platform with
neither shows nothing. Settings › General › Notifications chooses Off, On (the default) or On
with sound. Off stops notifications and sound; the badge stays because it is passive. In a
plain browser there are no desktop notifications and no permission prompt.

`state/noticePolicy.ts` is the one routing decision. The shell delivers the notification
(`modules/shell/src-tauri/src/notices.rs`) because the notification plugin cannot report a
click on desktop; it also shows each event id once, however many windows report it. Each event
has its own id, built from the engine's time for it so that every window gives the same one.

#### Confirming and undoing

Reversible beats confirmed. The person's own risky actions follow three tiers:

- **Undo, where a real reversal exists.** A saved skip-rule change can be saved back as it
  was, and a removed folder can be added again (its files are read again). The pill shows
  the notice with its action (“Skip rule added · Undo”, “Removed Notes from your library ·
  Add Notes again”) for a few seconds, held while the pill or the action is hovered or
  focused; the notice stays in Earlier, and in System › Activity, with its action while the
  reversal can still run. Ctrl+Z runs the notice's reversal while it shows and afterwards
  runs the latest reversal still possible, unless the Effect Journal holds a newer
  reversible effect (`state/ownReversals.ts`). A reversal runs at most once at a time and
  never after it succeeded. If the world changed since (the settings were saved again
  elsewhere, the folder is already back), the reversal closes and says why instead of
  overwriting that change. Undo is never offered where nothing would be reversed. Ctrl+Z
  keeps its text-editing meaning inside text fields and does nothing during input-method
  composition, on key repeat or while a dialog owns the keyboard.
- **In-place confirmation, where there is no reversal.** Removing a folder, removing
  documents added by other apps, cancelling a rebuild, deleting a custom theme, revoking a
  plugin, clearing failed jobs, rebuilding and resetting settings ask in a band under the
  item (`components/ConfirmBand.ts`): one consequence sentence (what changes, what stays,
  undo or not), a danger button named with the verb and object (“Remove Notes”) and
  “Keep it”. Focus moves to “Keep it”; Escape or “Keep it” cancels and returns focus to the
  control that asked. The verb answers once: a double-click or an auto-repeating key cannot
  run the action twice. Catalog operations with an inline confirmation use the same band,
  with a consequence sentence per operation.
- **Typed confirmation in a dialog, for wide, irreversible acts.** Delete all local data,
  applying exclusions to the index, forgetting an exact item and typed operation approvals
  show the exact phrase to type. Delete all local data is a centred dialog that says who
  asked, what is deleted, what stays and that it cannot be undone, with Cancel on the left
  and the verb on the right; its statechart (`CONFIRM_CEREMONY`) remains the presentation
  seam.

Pausing an AI download no longer asks: everything downloaded is kept and installing again
resumes, and a notice says so. Repairing an AI installation keeps its dialog because it
discloses download size and licences, but it is not drawn as a danger dialog. An undo
preview (Undo all AI actions, Restore to here, Undo this turn) executes exactly the
previewed entries, not ones recorded after the preview was drawn, and says how many actions
in its scope cannot be undone.

After a folder is removed, every window offers “Add <folder> again” in Earlier, including
after a reload: the engine's removal record keeps the removed folder's path (and its
collection), and the action ledger carries it. Removal records written before this field
existed still read, without the offer. The offer is not shown while the folder is watched
again, and the window that removed the folder shows its own notice instead of a second row.

#### Requests from agents and other apps

A request from an agent — an outside app over MCP (named by the name it gives, such as
Claude Desktop), the agent in Search, or an agent's suggestion — opens no dialog when it
arrives. It appears under Needs you, the pill shows the count, and screen readers hear it
once, assertively; a request found again after a reload or reconnect is listed without an
announcement.

A row offers Allow, Deny and Review. Review opens the request inside the panel (back link
“What's going on”): who asked and when, the question, its consequence lines (the exact
target the engine will act on, the risk, whether it can be undone), the requester's own
words marked “Written by …, not checked by JustSearch” where it gave any, the deadline and
what happens then (“Denied automatically in 4 min”, or that it waits), and Allow this time
and Deny. Keys 1 and 2 answer from inside the review; they never act in a text field,
during IME composition, on key repeat, with a modifier or while another modal is open.
Escape goes back to the list and the request keeps waiting. Opening a review reads the
request's live details again: a request that no longer waits leaves the review, and one
whose details cannot be read is denied. “Always allow” is offered only where the engine
keeps it — never for high-risk operations and never for the agent in Search. A newly shown
row or review ignores answers for 400 ms, so the click that showed it cannot also answer it.

Deny cancels the request in the engine; if that fails, the panel says the request could not
be cancelled and it stays answerable. The panel, the dialog and an MCP announcement all
answer the same request through one decision: it executes at most once, and an expired,
withdrawn or already answered request cannot execute.

Requests that need typing (high-risk operations, and agent suggestions that need typed
confirmation) are listed the same way, but answering them opens the protected security
dialog (Review, or Allow suggestion for a suggestion). Plugin permission requests that the
reader's own action started (installing or enabling a plugin) open it at once. That dialog is a native modal `<dialog>` in the
browser's top layer, headed “JustSearch security check”, with a teal border. This border is
the one deliberate exception to the teal rule: it marks the genuine dialog. Skins and plugins
cannot restyle or cover it. For plugin permissions, “Allow once” is the primary answer. A confirmation the reader's own command needs opens at once too: the security
dialog when it must be typed, otherwise a small dialog that leaves the page usable.

When copies of one agent request share an operation key, answering one answers all of
them: the answered request is recorded with its decision and the others as withdrawn, so
Earlier shows one row per decision. An agent reading the outcome of that key
(`justsearch_operation_outcome`) and `GET /api/operation-history/{operationKey}` give the
same answer: waiting, denied by the user, or denied automatically once the deadline passed
unanswered.

### Losing the engine

Once the app is on screen, losing the engine never replaces the window. The status pill's own
decision drives one treatment (`components/EngineLoss.ts`, `state/engineLoss.ts`): when no
channel has had contact for the 40 s contact window and the pill's 10 s grace has passed, so
that it says “Can't reach the engine”, the window area below the top bar dims behind
**JustSearch can't reach its engine**. A shorter gap shows nothing, and failing polls while live
streams still arrive are not an outage. On the desktop the supervisor's state also counts: an
exhausted or stopped engine, or one that runs but whose index is not ready (then **JustSearch's
engine isn't ready**), shows the same treatment at once, with the supervisor's reason in plain
words. Before the app has loaded, including while the start-up frame still covers it, the
recovery page keeps the root as before; the Linux update hold still replaces the loaded app and
Windows keeps its installer hand-off.

The treatment says that searching and indexing are on hold and that files and the index are
safe, and offers **Restart the engine** (desktop) or **Try again** (browser), **Open Health** and
**Copy report**, with when the engine was last connected and last tried. The top bar, the pill
and its panel, Settings, More and the window controls stay usable; the window area beneath is
inert and keeps its state, so drafts, the selected conversation and an interrupted request
survive and nothing is sent again. Restart the engine asks the host's existing restart, shows
“Restarting the engine…” until the app reaches the engine again or it stops again (the
supervisor saying it runs is not yet an answer), ignores repeated clicks, and
says in plain words why the host refused (quitting or an update, already starting or running,
still shutting down). The browser cannot restart the engine: it says to restart it where it
runs, and Try again reads the status once. No wording speaks of reloading the window.

Open Health shows Health as a band-topped page instead of the dimmed one: “JustSearch can't
reach its engine” and the last facts seen (engine, engine process and its own words on the
desktop, index state, searchable files, files waiting), each with how long ago it was itself
seen: the index facts date from the last status answer, not from a newer answer of another kind;
live controls and cards are not shown. Copy report copies a fixed set of fields, never text the
engine, host or an error wrote: product version, desktop/browser, OS family, time, seconds since
the last contact, supervisor state, a reason code from a closed list and the recent exit codes.
When the clipboard refuses, the report is shown selected. When the engine answers again the
treatment leaves without a reload and focus returns where it was; a new engine identity still
rebinds as before (the desktop reloads on its backend-restart event), and a late answer from the
old engine neither shows nor ends the outage. Approvals and completions that happened unseen
are not announced on return.

Focus moves into the treatment once per outage, unless a modal such as the protected security
dialog holds it; that dialog stays above the treatment with its own rules, and the treatment
never answers or restores an approval. The `engine-loss` and `engine-loss-600x400-forced`
captures cover it in the measured-axe register.

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

Space previews the focused result in a floating card; Space again, Escape or leaving the row
closes it, Enter opens the file and the right arrow opens the result's details. The preview shows what
the result already carries. It never fires inside a text field, during composition, on key
repeat or under a dialog.

Answers and their streaming activity appear in the conversation. Citations open the
source in Search's details pane, with a citation witness when one is available. The pane
has a tab per cited source, numbered as the answer numbers it, then Context (what the
assistant sees), then any tabs plugins contribute; each source tab offers Open file, Show in
folder, and hiding that source from the assistant. The pane loads extracted text through
`/api/preview` and shows extraction provenance and available OCR/VDU evidence. Trust and
context detail stays with the conversation and its context disclosures.

Escape or the close control returns the keyboard to the citation or result that opened the
pane. Each conversation remembers its tab and selection, across a switch and a reload; a pane
the reader closed stays closed, and a remembered source the record no longer holds opens
nothing. The width is remembered across windows.

A selection made elsewhere (`host.ui.showInspector`, a citation outside Search) opens in the
details pane of the window the reader is in, beside the work; nothing navigates to Search.
Escape with the keyboard on the page closes the pane of the window the pointer or focus was last in.

### Keyboard and implicit actions

Use registered commands and keybindings for primary actions. In the composer, Enter
submits at the chosen tier, Shift+Enter adds a newline and Ctrl/Cmd+Enter delegates;
composition by an input method editor takes precedence. Tab follows reachable controls.

The frame's keys: Ctrl+1/2/3 switch areas, F6 and Shift+F6 move between the top bar, the
window tab row, the content and the details pane (skipping regions a window does not have),
F1 opens Help & shortcuts, and Alt+Left/Right go back and forward. None of them fires during
input-method composition, on key repeat or while a dialog owns the keyboard; Ctrl+1/2/3,
F6 and Alt+Left/Right also work from a text field because they do not edit text (AltGr
combinations, which do type on many layouts, are left alone).

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
  also removes blur. Do not rely on transparency to convey state; forced colours repaint
  transparent borders too, so a selected window tab marks itself with an underline in
  `Highlight` that the other tabs do not have.
- **Semantics:** use the roles the actual controls implement. Current result containers
  use `list` / `listitem`, and shared menus use `menu` / `menuitem`.
- **Destructive actions:** offer Undo where a real reversal exists, confirm in place where
  it does not, and type a phrase only for wide, irreversible acts (see
  [Confirming and undoing](#confirming-and-undoing)). Confirmations say the consequence and
  name the verb on the button; they never ask “Are you sure?” or answer “OK” or “Confirm”.
