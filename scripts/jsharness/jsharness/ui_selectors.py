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
RAIL_SURFACE_SEARCH = "core.search-v3-surface"
# Synthetic readiness control for historical bundles; never used by current cameras.
RAIL_SURFACE_OLDER_MAIN = "core.unified-chat-surface"
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
# Search v3 has its own composer, which opens in Search mode: Enter in this textarea searches. The
# retired standalone `search-input` testid above is retained only for old, independently-owned steps.
CSS_SV3_WINDOW = 'jf-sv3-window'
CSS_SV3_DEGRADATION_COMPACT = 'jf-sv3-window [data-testid="sv3-degradation"][data-open="false"]'
CSS_SV3_COMPOSER_TEXTAREA = 'jf-sv3-window textarea[data-testid="sv3-composer-input"]'
CSS_SV3_PALETTE = 'jf-sv3-window jf-sv3-palette'
# Search v3's Search mode renders its results on the shared `<jf-results-card>`, inside its own
# results region, so the row is the card's `search-result-row` scoped to that region.
CSS_SV3_RESULT_ROW = 'jf-sv3-window jf-sv3-results [data-testid="search-result-row"]'
# Settings owns Density as a native range inside the discrete-slider atom. Its selected user-facing
# stop is exposed by `aria-valuetext`; the setting is FE-local and does not issue `/api/settings/v2`.
CSS_DENSITY_SLIDER = (
    'jf-settings-window jf-settings-surface '
    'jf-discrete-slider input[type="range"][aria-label="Density"]'
)
CSS_TOAST = '.toast'
# The Library surface's LOADING branch, for the `skeleton-library` step. There is no
# `data-testid` here and none is added: the surface already has an observable loading state —
# `LibrarySurface.render()` swaps in a single `<div class="empty">Loading…</div>` while the
# `/api/indexing-roots/substrate` fetch is in flight (`this.loading`, set before the fetch and
# cleared in its `finally`). Anchored on the TEXT, not on `.empty`: that same class also renders
# the "No watched folders" and "Rebuilding index…" rows, so a class-only selector would go green
# on exactly the states this step exists to distinguish itself from. "Loading" appears once in
# the whole surface.
CSS_LIBRARY_SURFACE_HOST = 'jf-library-surface'
CSS_LIBRARY_INDEXING_HOURS = 'jf-library-surface jf-indexing-rules-editor #indexing-hours'
CSS_LIBRARY_FOLDER_TOGGLE = '.folder-toggle'
TEXT_LIBRARY_LOADING = 'Loading'
# Content witnesses run immediately before each Search screenshot AND axe measurement.
# Presence of Shell or the rail alone cannot establish any of these states.
SEARCH_STATE_WITNESSES = {
    "home": ['[data-testid="sv3-composer-headline"]', 'jf-sv3-composer textarea'],
    "chat-mode": ['jf-sv3-composer[tier="ask"] textarea'],
    "qa-response": ['[data-testid="sv3-turn-answer"]', 'jf-document-pane :is(.blocks, .source)'],
    "chat-proportion": ['[data-testid="sv3-turn-question"]', '[data-testid="sv3-turn-answer"]', '[data-testid="sv3-degradation"][data-open="false"]'],
    "chat-occlusion": ['[data-testid="search-result-row"]', 'jf-document-pane :is(.blocks, .source)'],
    "chat-bands": ['[data-testid="sv3-record-activity"]', '[data-testid="sv3-run-receipt"]'],
    "chat-bands-detailed": ['[data-testid="sv3-turn-answer"]', '[data-testid="sv3-degradation-detail"]'],
    "chat-composer-small": ['jf-sv3-composer textarea'],
    "chat-wide-docked": ['[data-testid="sv3-turn-answer"]', '[data-testid="sv3-degradation"]'],
    "chat-evidence-rail": ['[data-testid="sv3-turn-answer"]', '[data-testid="sv3-turn-citations"] button.source'],
    "chat-activity-rail-open": ['[data-testid="sv3-record-activity"]', 'jf-tool-call-card[expanded] .tool-card-body'],
    "chat-model-only": ['[data-testid="sv3-turn-markdown"]'],
    "chat-structured": ['[data-testid="sv3-turn-json"]'],
    "chat-budget": ['[data-testid="sv3-run-budget-facts"]', '[data-testid="sv3-run-budget-finalize"]', '[data-testid="sv3-run-budget-stop"]'],
    "chat-chip-yield": ['[data-testid="sv3-degradation"][data-open="false"]'],
    "sv3-composer-occlusion": ['[data-testid="sv3-turn-answer"]', '[data-testid="sv3-turn-citations"] button.source'],
    "sv3-search-results": ['[data-testid="search-result-row"]'],
    "sv3-hero-forced-colors": ['[data-testid="sv3-composer-headline"]', 'jf-sv3-composer textarea'],
    "sv3-docked-forced-colors": ['[data-testid="sv3-turn-answer"]'],
    "sv3-palette-forced-colors": ['jf-sv3-palette [role="option"]'],
    "sv3-citation-selected": ['.cite-ref.cite-selected', 'jf-document-pane :is(.blocks, .source)'],
    "sv3-citation-dropped": ['[data-testid="sv3-turn-citations"] .inclusion', 'jf-document-pane :is(.blocks, .source)'],
    "legacy-token-plugin": ['jf-sv3-composer textarea'],
}

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
