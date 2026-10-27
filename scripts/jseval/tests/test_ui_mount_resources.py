"""Browser proof for mount diagnostics; every request is intercepted, no dev stack."""
import asyncio

import pytest

from jseval import ui_check, ui_shot


def test_failed_module_response_is_reported_without_query_values(tmp_path, monkeypatch):
    playwright = pytest.importorskip("playwright.async_api")
    monkeypatch.setattr(ui_shot, "_SERVER_INFO_PATH", tmp_path / "absent.json")

    async def check():
        async with playwright.async_playwright() as runtime:
            try:
                browser = await runtime.chromium.launch()
            except playwright.Error as error:
                if "Executable doesn't exist" in str(error):
                    pytest.skip("ui-shot Chromium is not installed")
                raise
            try:
                page = await browser.new_page()

                async def respond(route):
                    if route.request.url == "http://mount-proof.invalid/":
                        await route.fulfill(status=200, content_type="text/html", body=
                            '<script type="module" src="/node_modules/.vite/deps/lit.js?v=private-value"></script>')
                    else:
                        await route.fulfill(status=504, content_type="text/javascript",
                                            body="Outdated Optimize Dep")

                await page.route("**/*", respond)
                await page.goto("http://mount-proof.invalid/", wait_until="load")
                with pytest.raises(ui_check.AppNotMountedError) as failure:
                    await ui_check._await_app_ready(page, timeout_ms=10)
                assert "504 /node_modules/.vite/deps/lit.js" in str(failure.value)
                assert "private-value" not in str(failure.value)
            finally:
                await browser.close()

    asyncio.run(check())


@pytest.mark.parametrize(("surface_id", "visible", "ready"), [
    ("core.search-v3-surface", True, True),
    ("core.unified-chat-surface", True, True),
    ("core.search-v3-surface", False, False),
    ("core.library-surface", True, False),
])
def test_mount_requires_a_visible_current_or_legacy_search_rail_button(
        tmp_path, monkeypatch, surface_id, visible, ready):
    """A mounted shell/other rail entry cannot substitute for Search readiness."""
    playwright = pytest.importorskip("playwright.async_api")
    monkeypatch.setattr(ui_shot, "_SERVER_INFO_PATH", tmp_path / "absent.json")

    async def check():
        async with playwright.async_playwright() as runtime:
            browser = await runtime.chromium.launch()
            try:
                page = await browser.new_page()
                style = "" if visible else ' style="display:none"'
                await page.set_content(
                    f'<jf-shell><button data-surface-id="{surface_id}"{style}>Search</button></jf-shell>')
                if ready:
                    await ui_check._await_app_ready(page, timeout_ms=50)
                else:
                    with pytest.raises(ui_check.AppNotMountedError, match="app shell never mounted"):
                        await ui_check._await_app_ready(page, timeout_ms=50)
            finally:
                await browser.close()

    asyncio.run(check())
