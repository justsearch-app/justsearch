---
schema: test-intent.v1
task: linux-deb
author-role: builder
author-session: codex-builder-linux-deb-continuation
---

# Test-intent entries

These are preservation checks for the existing Linux argv reader and frontend handoff.
No production deep-link or shortcut code changes. Desktop dispatch through an installed
handler remains part of the independent Ubuntu VM qualification.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/search_shortcut.rs#cfg(test)",
        "modules/ui-web/src/shell-v0/router/sources/TauriDeepLinkSource.desktop.test.ts"
      ],
      "class": "New",
      "behaviour": "The real deep-link plugin retains a Linux desktop URI passed through argv; LaunchRequest keeps its destination and requests the window without a search-focus override. The frontend dispatches the cold-launch URI and the same destination from the existing running-instance event, and releases its listener on teardown. These tests exercise the existing readers and event adapter; they do not establish real desktop registration or interprocess delivery.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Deep links on Linux: a `justsearch://` URL given as the launch argument opens it in a fresh instance and is handed to the already-running instance",
          "location": "task linux-deb user turn 2 item 4 (2026-10-27)"
        },
        {
          "kind": "owner-words",
          "quote": "If the Linux path already works from argv, only add a test proving it; change code only where it does not.",
          "location": "task linux-deb user turn 2 item 4 (2026-10-27)"
        }
      ],
      "evidence": "gates/test-intent/evidence/linux-deb.json",
      "newChecks": [
        "modules/shell/src-tauri/src/search_shortcut.rs#search_shortcut::tests::linux_desktop_uri_argv_is_available_to_the_cold_launch_reader",
        "modules/ui-web/src/shell-v0/router/sources/TauriDeepLinkSource.desktop.test.ts#dispatches the fresh-instance URL, then a URL forwarded to the running instance"
      ]
    }
  ]
}
```

## Acceptance records
