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
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package and its desktop integration",
          "choice": "A justsearch:// URL given as the launch argument on Linux opens it in a fresh instance and is handed to the already-running instance; where the existing argv reader already does this, prove it with tests and change no production code.",
          "label": "agent choice"
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
