"""Tests for the self-healing Selector primitive (tempdoc 615 §11 HARDEN).

The failure mode is a brittle step: a testid rename breaks resolution even though the
element is unchanged by accessible role+name. These pin the resolution ORDER (role+name
wins when present; testid is the fallback) without a browser.

Also covers the cite-key selector builders in `ui_check`, whose failure mode is the
mirror image: a selector that is never wrong about WHICH element it wants but cannot
name it, because the key is a Windows path and CSS reads a backslash as an escape.
"""
from __future__ import annotations

import asyncio

from jseval.ui_check import (
    _build_steps,
    _cite_card_selected_selector,
    _cite_card_selector,
    _css_escaped,
    _set_density_in_settings,
    _set_detail_level_in_settings,
    _type_and_search,
)
from jseval import ui_selectors as S
from jseval.ui_selectors import Selector


class _FakeLocator:
    def __init__(self, count: int, tag: str, raises: bool = False):
        self._count = count
        self.tag = tag
        self._raises = raises
        self.first = self

    async def count(self) -> int:
        if self._raises:
            raise RuntimeError("count failed")
        return self._count


class _FakePage:
    def __init__(self, role_count: int, role_raises: bool = False):
        self._role_count = role_count
        self._role_raises = role_raises
        self.calls: list = []

    def get_by_role(self, role, name=None):
        self.calls.append(("role", role, name))
        return _FakeLocator(self._role_count, "role", self._role_raises)

    def get_by_test_id(self, tid):
        self.calls.append(("testid", tid))
        return _FakeLocator(1, "testid")


def _locate(sel, page):
    return asyncio.run(sel.locate(page))


def test_role_match_wins():
    page = _FakePage(role_count=1)
    loc = _locate(Selector("searchbox", "Search files", "search-input"), page)
    assert loc.tag == "role"


def test_falls_back_to_testid_when_role_absent():
    page = _FakePage(role_count=0)
    loc = _locate(Selector("searchbox", "Search files", "search-input"), page)
    assert loc.tag == "testid"
    assert ("testid", "search-input") in page.calls


def test_role_error_falls_back_to_testid():
    page = _FakePage(role_count=0, role_raises=True)
    loc = _locate(Selector("searchbox", "Search files", "search-input"), page)
    assert loc.tag == "testid"


def test_no_testid_returns_role_locator():
    # No testid fallback → return the (possibly empty) role locator, never None.
    page = _FakePage(role_count=0)
    loc = _locate(Selector("searchbox", "Search files", testid=None), page)
    assert loc.tag == "role"


# ---------------------------------------------------------------------------
# Cite-key selectors — a `data-cite-key` is a source PATH
# ---------------------------------------------------------------------------
# The regression: `sv3-citation-selected` interpolated the picked key raw into
# `[data-cite-key="…"]`. On Windows the key carries backslashes, and a backslash in a CSS
# selector introduces an escape in string context too — so `…\598-…` parses as U+0598
# followed by `-` and the selector matches nothing on a page that is rendering correctly.

# A Windows key wearing every character class the escape rules care about: a drive colon,
# separators, a dot, a fragment marker, and the `\5` sequence that reproduced the defect.
_WIN_KEY = r"F:\repo\docs\598-x.md#c3"


def _css_escape(value: str) -> str:
    """Test double for the page's `CSS.escape`.

    Covers the subset these keys exercise (ASCII punctuation gets a backslash;
    alphanumerics, `-`, `_` and non-ASCII pass through). The leading-digit and
    leading-hyphen hex-escape branches of the real algorithm are deliberately not
    modelled — no cite key reaches them, and the assertions below never depend on them.
    """
    return "".join(
        ch if (ch.isalnum() or ch in "-_" or ord(ch) > 0x7F) else "\\" + ch for ch in value
    )


def _css_unescape(value: str) -> str:
    """What a CSS parser reads out of a quoted attribute value: `\\X` is the character X."""
    out: list[str] = []
    i = 0
    while i < len(value):
        if value[i] == "\\" and i + 1 < len(value):
            out.append(value[i + 1])
            i += 2
        else:
            out.append(value[i])
            i += 1
    return "".join(out)


def _attr_value(selector: str) -> str:
    return selector.split('[data-cite-key="', 1)[1].split('"]', 1)[0]


class _FakeEvalPage:
    def __init__(self):
        self.evaluated: list[tuple[str, str]] = []

    async def evaluate(self, expr, arg=None):
        self.evaluated.append((expr, arg))
        return _css_escape(arg)


def test_css_escaped_ships_the_raw_key_into_the_page():
    # The escaping authority is the browser's own CSS.escape, not a Python re-implementation,
    # so the key must arrive at the page untouched.
    page = _FakeEvalPage()
    escaped = asyncio.run(_css_escaped(page, _WIN_KEY))
    assert page.evaluated == [("(v) => CSS.escape(v)", _WIN_KEY)]
    assert escaped == _css_escape(_WIN_KEY)


def test_card_selector_names_the_key_a_css_parser_would_read():
    escaped = _css_escape(_WIN_KEY)
    sel = _cite_card_selector(escaped)
    assert sel == (
        '[data-testid="sv3-turn-citations"] button.source'
        f'[data-cite-key="{escaped}"]'
    )
    assert sel.startswith('[data-testid="sv3-turn-citations"] button.source')
    assert _css_unescape(_attr_value(sel)) == _WIN_KEY


def test_selected_card_selector_names_the_key_a_css_parser_would_read():
    escaped = _css_escape(_WIN_KEY)
    sel = _cite_card_selected_selector(escaped)
    assert sel == (
        f'button.source[data-cite-key="{escaped}"][data-selected][aria-current="true"]'
    )
    # `data-selected` is the styling hook; `aria-current` is the announcement the step exists
    # to prove — both stay on the selector.
    assert sel.endswith('[data-selected][aria-current="true"]')
    assert _css_unescape(_attr_value(sel)) == _WIN_KEY


def test_the_raw_interpolation_would_have_named_something_else():
    # Pins the DEFECT, not just the repair: dropping the escape step yields a selector whose
    # attribute value a CSS parser reads as a different string — hence the step that could
    # never pass on Windows.
    raw = f'[data-cite-key="{_WIN_KEY}"]'
    assert _css_unescape(_attr_value(raw)) != _WIN_KEY
    assert _cite_card_selector(_WIN_KEY) != _cite_card_selector(_css_escape(_WIN_KEY))


def test_a_key_needing_no_escaping_is_left_alone():
    # A POSIX-shaped key round-trips unchanged, so the fix costs nothing off Windows.
    plain = "doc-42"
    assert _cite_card_selector(_css_escape(plain)) == (
        '[data-testid="sv3-turn-citations"] button.source[data-cite-key="doc-42"]'
    )


class _HarnessLocator:
    def __init__(self, page, selector: str):
        self.page = page
        self.selector = selector
        self.first = self

    async def wait_for(self, **kwargs):
        self.page.calls.append(("wait_for", self.selector, kwargs))

    async def fill(self, value: str):
        self.page.calls.append(("fill", self.selector, value))
        if self.selector == S.CSS_DENSITY_SLIDER:
            self.page.density = {"0": "Compact", "1": "Comfortable", "2": "Spacious"}[value]

    async def click(self, **kwargs):
        self.page.calls.append(("click", self.selector, kwargs))
        for label in ("Simple", "Detailed"):
            if f"role=button;name={label};" in self.selector:
                self.page.checked_labels = {label}

    async def scroll_into_view_if_needed(self, **kwargs):
        self.page.calls.append(("scroll", self.selector))

    async def dispatch_event(self, event):
        self.page.calls.append(("dispatch", self.selector, event))

    async def get_attribute(self, name: str):
        self.page.calls.append(("get_attribute", self.selector, name))
        if name == "aria-valuetext":
            return self.page.density
        if name == "aria-pressed":
            checked = any(
                f"name={label};" in self.selector for label in self.page.checked_labels
            )
            return "true" if checked else "false"
        return None

    def get_by_role(self, role, name=None, exact=None):
        suffix = f"role={role};name={name};exact={exact}"
        return _HarnessLocator(self.page, f"{self.selector} >> {suffix}")


class _Response:
    status = 200
    url = "http://localhost:5174/api/settings/v2"

    class request:
        method = "POST"

    async def json(self):
        return {"state": "COMPLETE"}


class _ResponseInfo:
    @property
    def value(self):
        async def get():
            return _Response()
        return get()


class _ExpectResponse:
    def __init__(self, page, predicate):
        self.page = page
        self.predicate = predicate

    async def __aenter__(self):
        if callable(self.predicate):
            assert self.predicate(_Response())
        self.page.calls.append(("expect_response", self.predicate))
        return _ResponseInfo()

    async def __aexit__(self, *_args):
        return False


class _HarnessPage:
    def __init__(self):
        self.calls = []
        self.density = "Comfortable"
        self.checked_labels = set()

    def locator(self, selector):
        self.calls.append(("locator", selector))
        return _HarnessLocator(self, selector)

    def get_by_role(self, role, name=None, exact=None):
        self.calls.append(("get_by_role", role, name, exact))
        return _HarnessLocator(self, f"page >> role={role};name={name};exact={exact}")

    def get_by_test_id(self, test_id):
        if test_id in (S.TID_INSTALL_COMPONENT_LIST, S.TID_BRAIN_SIMPLE_ACTION):
            assert self.checked_labels == {"Simple"}, "The requested Brain control is hidden in Detailed"
        self.calls.append(("get_by_test_id", test_id))
        return _HarnessLocator(self, f"testid={test_id}")

    async def evaluate(self, expression, *args):
        self.calls.append(("evaluate", expression))

    def expect_response(self, predicate, **kwargs):
        self.calls.append(("expect_response_args", predicate, kwargs))
        return _ExpectResponse(self, predicate)


def test_search_setup_drives_the_search_v3_composer_and_explicit_palette_action():
    page = _HarnessPage()
    asyncio.run(_type_and_search(page, "needle"))
    assert ("locator", S.CSS_SV3_COMPOSER_TEXTAREA) in page.calls
    assert ("fill", S.CSS_SV3_COMPOSER_TEXTAREA, "needle") in page.calls
    assert any("Open command palette" in call[1] for call in page.calls if call[0] == "click")
    assert any("Search this text" in call[1] for call in page.calls if call[0] == "click")
    assert ("locator", S.CSS_SV3_RESULT_ROW) in page.calls
    assert ("locator", S.CSS_SEARCH_INPUT) not in page.calls


def test_density_setup_uses_the_native_range_and_checks_its_user_facing_stop():
    page = _HarnessPage()
    asyncio.run(_set_density_in_settings(page, "Spacious"))
    assert ("fill", S.CSS_DENSITY_SLIDER, "2") in page.calls
    assert page.density == "Spacious"


def test_detail_level_setup_awaits_a_complete_settings_post():
    page = _HarnessPage()
    asyncio.run(_set_detail_level_in_settings(page, "Detailed"))
    assert any(
        "role=group;name=Detail level" in call[1] and "role=button;name=Detailed;exact=True" in call[1]
        for call in page.calls if call[0] == "wait_for"
    )
    assert any(
        "role=button;name=Detailed" in call[1]
        for call in page.calls if call[0] == "click"
    )
    predicates = [call[1] for call in page.calls if call[0] == "expect_response_args"]
    assert len(predicates) == 1 and callable(predicates[0])


def test_detail_level_setup_does_not_invent_a_write_when_already_selected():
    page = _HarnessPage()
    page.checked_labels.add("Detailed")
    asyncio.run(_set_detail_level_in_settings(page, "Detailed"))
    assert not any(call[0] == "expect_response_args" for call in page.calls)


def test_brain_install_drilldowns_select_the_panel_before_using_its_controls():
    steps = {step.name: step for step in _build_steps("http://localhost:5186", 0, 1000)}
    for name in ("ai-brain-components", "ai-brain-consent",
                 "ai-brain-components-light", "ai-brain-consent-light"):
        assert steps[name].fixtures_variant == "install-preview"
        page = _HarnessPage()
        page.checked_labels = {"Detailed"}
        asyncio.run(steps[name].setup(page))
        assert page.checked_labels == {"Simple"}
        assert any(call[0] == "expect_response_args" for call in page.calls)
