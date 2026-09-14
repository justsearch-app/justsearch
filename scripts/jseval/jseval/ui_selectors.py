"""Shared UI selector registry for the ui-eval subcommand.

Ported from modules/ui-web/scripts/evidence/lib/selectors.mjs.
Keep this list intentionally small and stable.
"""

from __future__ import annotations

from dataclasses import dataclass

# data-testid values present on the live Lit shell-v0 (the retired-React testids — inspector-*,
# action-*, filters-toggle, context-* pills, citation-highlight, health-view — were removed in
# tempdoc 615 §6.1b; their steps now target live hooks below or were retired).
TID_SEARCH_INPUT = "search-input"
TID_SEARCH_RESULT_ROW = "search-result-row"
TID_RESULT_ROW_SNIPPET_TOGGLE = "result-row-snippet-toggle"
TID_GLOBAL_COMMAND_CHROME = "global-command-chrome"
# Tempdoc 840 Phase 5 — the per-component install list and the consent dialog it leads into.
TID_INSTALL_COMPONENT_LIST = "install-component-list"
TID_BRAIN_SIMPLE_ACTION = "brain-simple-action"
TID_INSTALL_CONSENT_DIALOG = "install-consent-dialog"

# Activity rail navigation — the rail renders each nav button with
# `data-surface-id="core.<x>-surface"` (`Shell.ts` renderRailButton) and a `@click` that selects
# the surface; there are NO per-view `activity-*` testids. Nav clicks therefore target the
# surface id (Playwright pierces the rail's open shadow root). Repointed from the stale
# `activity-*` testids that no longer exist on the rail (559/571 rail rework).
# Search Thread S5b — the standalone `core.search-surface` rail surface is retired; the retrieve
# tier folded into the one window (`core.unified-chat-surface`), which is the rail button "home"/
# "search" now click (labeled "Search" post-rename, id unchanged).
RAIL_SURFACE_SEARCH = "core.unified-chat-surface"
RAIL_SURFACE_LIBRARY = "core.library-surface"
RAIL_SURFACE_BRAIN = "core.brain-surface"
RAIL_SURFACE_HEALTH = "core.health-surface"
RAIL_SURFACE_SETTINGS = "core.settings-surface"
RAIL_SURFACE_SECURITY = "core.security-surface"
RAIL_SURFACE_HELP = "core.help-surface"
RAIL_SURFACE_BROWSE = "core.browse-surface"


def rail_css(surface_id: str) -> str:
    """CSS selector for a rail nav button by its `data-surface-id`."""
    return f'[data-surface-id="{surface_id}"]'


# CSS selectors (for locator()-based queries)
CSS_SEARCH_INPUT = f'[data-testid="{TID_SEARCH_INPUT}"]'
CSS_SEARCH_INPUT_TEXTAREA = f'textarea[data-testid="{TID_SEARCH_INPUT}"]'
CSS_SEARCH_RESULT_ROW = f'[data-testid="{TID_SEARCH_RESULT_ROW}"]'
# tempdoc 615 §6.1b: live Lit hooks (no retired React testids). The inspector is the custom element
# `<jf-inspector-pane>` (chrome/Shell.ts); a citation mark is `.cite-ref`/`[data-cite-key]`
# (components/chat/MarkdownBlock.ts). Selection is a row click, so there is no per-row select checkbox.
CSS_INSPECTOR_PANE = 'jf-inspector-pane'
CSS_CITATION_HIGHLIGHT = '.cite-ref, [data-cite-key]'
# tempdoc 855: Settings is a MODAL surface — the rail affordance opens the `<jf-settings-window>`
# dialog over the stage instead of mounting the surface into it. `dialog[open]` is the readiness
# signal (the window element itself is always in the DOM, so its content stays connected while
# closed); the mounted `<jf-settings-surface>` is the content signal.
CSS_SETTINGS_WINDOW = 'jf-settings-window'
CSS_SETTINGS_WINDOW_DIALOG = 'jf-settings-window dialog[open]'
CSS_SETTINGS_WINDOW_CONTENT = 'jf-settings-window jf-settings-surface'
# Tempdoc 855 §5 item 1 — Security absorbed into Settings as a member category (off the rail). Its
# deep-link redirects onto the settings window (member→host alias), which mounts the member surface
# INSIDE the persistent `<jf-settings-surface>` — this is the readiness signal that the Security
# category (not just Settings generally) is the one active/rendered.
CSS_SETTINGS_WINDOW_SECURITY_CONTENT = 'jf-settings-window jf-settings-surface jf-security-surface'
# The unified-chat composer remains the correct hook for chat-surface steps.
CSS_COMPOSER_TEXTAREA = 'jf-composer textarea'
# Search v3 has its own composer and explicit command-palette transition into retrieval. The retired
# standalone `search-input` testid above is retained only for old, independently-owned steps; current
# search setup must enter through this textarea and choose "Search this text" from the palette.
CSS_SV3_WINDOW = 'jf-sv3-window'
CSS_SV3_COMPOSER_TEXTAREA = 'jf-sv3-window jf-sv3-composer textarea'
CSS_SV3_PALETTE = 'jf-sv3-window jf-sv3-palette'
# Search v3 projects lightweight rows from `Sv3Main`; it does not mount the older
# `<jf-results-card>` rows carrying `search-result-row`.
CSS_SV3_RESULT_ROW = '[data-testid="sv3-main-row"]'
# Settings owns Density as a native range inside the discrete-slider atom. Its selected user-facing
# stop is exposed by `aria-valuetext`; the setting is FE-local and does not issue `/api/settings/v2`.
CSS_DENSITY_SLIDER = (
    'jf-settings-window jf-settings-surface '
    'jf-discrete-slider input[type="range"][aria-label="Density"]'
)
# Tempdoc 697 activation — the two persistent-chrome, data-dependent elements the shrink-only
# proportion ratchet (governance/ui-proportion-baseline.v1.json) tracks on the chat surface.
# `.degradation-banner-collapsed` is UnifiedChatView.renderCollapsedDegradationBanner's one-line
# pill (needs a DEGRADED readiness verdict); `.message.user` is a rendered user turn in the
# thread (UnifiedChatView.ts:5189 `this.thread = [...]`).
CSS_DEGRADATION_BANNER_COLLAPSED = '.degradation-banner-collapsed'
CSS_MESSAGE_USER = '.message.user'
# Tempdoc 814 closure (audit finding A) — the EXPANDED banner and the cause list only its
# expanded branch renders. `.degradation-banner` is worn by BOTH forms (the pill's class is
# `degradation-banner degradation-banner-collapsed`), so the causes list — not the banner
# class — is what proves a capture actually reached Detailed expansion
# (UnifiedChatView.renderDegradationBanner's `notice-causes` <ul>).
CSS_DEGRADATION_BANNER = '.degradation-banner'
CSS_DEGRADATION_CAUSES = '[data-testid="chat-degradation-causes"]'
# Sandbox round 7 — the four elements whose RELATIONS the `chat-occlusion` step measures:
# the reading column starved by the pane beside it, and the toast stack growing over the
# chat header's control row. `.conversation` / `.document-pane` are UnifiedChatView zones
# (unifiedChatStyles.ts); `.toast` is AdvisoryToastHost's wrapper; `.header` is the
# New chat / Export / Activity control row (UnifiedChatView.ts renderAnswerPlane).
CSS_CONVERSATION_COLUMN = '.conversation'
CSS_DOCUMENT_PANE = '.document-pane'
CSS_TOAST = '.toast'
CSS_CHAT_HEADER = '.header'
# Tempdoc 814 (W4 activation) — the D7 gate-registration elements for the "richest
# all-bands" (`chat-bands`), small-viewport docked-composer (`chat-composer-small`), and
# single-turn no-spine (`chat-spine-single`) steps.
# `.conversation-zone` is the grid that owns the run-spine + `.conversation` scroller
# (UnifiedChatView.renderAnswerPlane); `jf-unified-chat-view` is the surface HOST custom
# element (`customElements.define('jf-unified-chat-view', ...)`), the D1 "owner of the
# sum" denominator the D7.1 share assertion divides against.
CSS_CONVERSATION_ZONE = '.conversation-zone'
CSS_SURFACE_HOST = 'jf-unified-chat-view'
# The Library surface's LOADING branch, for the `skeleton-library` step. There is no
# `data-testid` here and none is added: the surface already has an observable loading state —
# `LibrarySurface.render()` swaps in a single `<div class="empty">Loading…</div>` while the
# `/api/indexing-roots/substrate` fetch is in flight (`this.loading`, set before the fetch and
# cleared in its `finally`). Anchored on the TEXT, not on `.empty`: that same class also renders
# the "No watched folders" and "Rebuilding index…" rows, so a class-only selector would go green
# on exactly the states this step exists to distinguish itself from. "Loading" appears once in
# the whole surface.
CSS_LIBRARY_SURFACE_HOST = 'jf-library-surface'
TEXT_LIBRARY_LOADING = 'Loading'
# `.composer` is the composer BLOCK div (687 R5a's protected element — row-consolidation
# happens inside it, never replacing it); `.activity-rail` is the agent-mode `<details>`
# run-summary band (renders whenever `affordance === 'agent'`, even before a run reports
# budget/lifecycle — UnifiedChatView.ts tempdoc-561-C-2 comment); `.run-spine` is the
# agent-mode run-timeline minimap (mounts only when `spineItems()` is non-null — agent
# affordance + wide zone + a run with more than one turn or real node boundaries).
CSS_COMPOSER_BLOCK = '.composer'
CSS_ACTIVITY_RAIL = '.activity-rail'
CSS_RUN_SPINE = '.run-spine'
# The "Delegate a multi-step task to the agent" escalation rung — the plain affordance
# toggle (`this.affordance = 'agent'`) that mounts `.activity-rail` / gates `.run-spine`
# WITHOUT requiring an actual agent run (no SSE stream needed; see chat-bands' setup
# docstring for the fixture-reachability finding this selector exists to exploit).
TID_ESCALATION_DELEGATE = "escalation-delegate"
CSS_ESCALATION_DELEGATE = f'[data-testid="{TID_ESCALATION_DELEGATE}"]'
# Tempdoc 814 §D8 — the two bands the agent-run fixture makes capture-reachable.
# `.evidence-rail` is the DOCKED sources pane (mounts on `evidenceRailMounted()`: agent
# affordance + a non-empty `answerSources` + wide zone); `.sources-affordance` is the
# in-answer "Sources · N" chip, which by §D5 must NOT render while the rail owns the count.
# `.activity-budget` is the FIRST body row of the expanded activity rail (the budget readout
# + bar) and `.activity-context` its context-headroom sibling; `.activity-lifecycle` is the
# turn/iteration/tool + state line the thread record's `lifecycles[]` feeds.
CSS_EVIDENCE_RAIL = '.evidence-rail'
CSS_SOURCES_AFFORDANCE = '.sources-affordance'
CSS_ACTIVITY_RAIL_SUMMARY = '.activity-rail > summary'
CSS_ACTIVITY_BUDGET = '.activity-budget'
CSS_ACTIVITY_CONTEXT = '.activity-context'
CSS_ACTIVITY_LIFECYCLE = '.activity-lifecycle'

@dataclass(frozen=True)
class Selector:
    """Self-healing element selector (tempdoc 615 §11 HARDEN).

    Resolves by accessible ROLE + NAME first, falling back to the `data-testid`.
    Playwright's role/label engines pierce OPEN shadow roots (verified on the Lit
    shell), so role+name is stable across the testid churn that made this session's
    React->Lit migration painful — a testid rename can't break a step that has a
    role+name, and a missing/renamed role can't break one that still has its testid.
    The a11y tree the measurement substrate already records IS this resolution key
    (§11 standout idea #1: "the harness hardens itself").
    """

    role: str
    name: str | None = None
    testid: str | None = None

    async def locate(self, page):
        """Return a Playwright locator: the role+name match if present, else the testid."""
        if self.role:
            loc = page.get_by_role(self.role, name=self.name) if self.name else page.get_by_role(self.role)
            try:
                if await loc.count() > 0:
                    return loc.first
            except Exception:
                pass
        if self.testid:
            return page.get_by_test_id(self.testid)
        # No testid fallback — return the (empty) role locator so the caller's
        # wait_for surfaces a clear "not found by role" failure rather than None.
        return (page.get_by_role(self.role, name=self.name) if self.name
                else page.get_by_role(self.role))


# The search input: role=searchbox / accessible-name "Search files" (verified live via
# the measurement a11y tree), testid as the fallback. Used by the search steps.
SEARCH_INPUT = Selector(role="searchbox", name="Search files", testid=TID_SEARCH_INPUT)


# Navigation views — maps view name to the rail button's surface id
VIEWS: dict[str, str] = {
    "home": RAIL_SURFACE_SEARCH,
    "search": RAIL_SURFACE_SEARCH,
    "library": RAIL_SURFACE_LIBRARY,
    "ai-brain": RAIL_SURFACE_BRAIN,
    "health": RAIL_SURFACE_HEALTH,
    "settings": RAIL_SURFACE_SETTINGS,
    "security": RAIL_SURFACE_SECURITY,
    "help": RAIL_SURFACE_HELP,
}
