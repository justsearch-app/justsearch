---
schema: test-intent.v1
task: t-20261018-6108b0
author-role: builder
author-session: open-guard-builder
---

# Test-intent entries

Windows NTFS, 8.3, trailing-dot/space, ADS, symlink and junction checks are `#[cfg(windows)]` and **unverified here**. Run the exact hosted command in `tmp/open-guard/windows-evidence-command.txt`; real Windows launcher side effects remain unverified. macOS is out of scope.

D4 was generated in a shared scratch clone using `run-evidence.mjs --before` and an interface-only before patch. The patch exposes the old decisions under the new helper signatures; it adds no validation or resolution. Cargo output stays in this worktree's target directory. The lower Linux layer is the actual command function with PATH-isolated, marker-writing xdg-open; plugin calls go through Tauri's IPC dispatcher and real ACLs on a mock webview. This does not claim a graphical shell run.

The author has not accepted these entries; independent acceptance remains for the verifier.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Pin F4 local/URL refusals, resolved dispatch, directory-only reveal and opener ACLs through validators, actual command functions with isolated xdg-open, and Tauri mock-webview IPC. Windows-specific checks below remain unverified on this host.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "D1: local opening allows exactly pdf, doc, docx, xls, xlsx, ppt, pptx, odt, ods, odp, rtf, epub, txt, md, csv, tsv, log, json, xml, html, htm, eml, msg, png, jpg, jpeg, gif, bmp, webp, tif, tiff, svg, mp3, wav, m4a, flac, mp4, mov, mkv, webm. Unknown types, scripts, sources and launchers, including macro-enabled Office, mht and mhtml, are refused with the type named and Show in folder offered.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "D4/S1/S2/S7: resolve the path, check the resolved target extension and dispatch the resolved target. A document symlink to an allowed type opens. An allowed-name symlink to a refused target, missing, dangling and looping paths are refused. Preserve UNC, traversal and the original executable refusals. Windows drive letters are not URL schemes; Linux colons are legal. Windows rejects network/device forms and alternate streams before canonicalization, resolves 8.3 and trailing-dot/space names, and accepts only the extended prefix produced by canonicalization. Replacement between validation and dispatch remains a residual.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "D2/S3: external links have their own command, accepting http, https and mailto only, including queries and fragments. Parse with a URL parser; refuse empty input, control characters, NUL and malformed URLs. Every other scheme, including file, javascript, vbscript, data, ms-settings, ms-msdt, search-ms, shell and tel, is refused in any case. Local opening refuses schemes except Windows drive paths.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "D5/S6: reveal dispatches only the existing resolved directory itself, or the existing resolved parent of a file, never a file; Windows keeps Explorer /select. Crafted paths beneath launcher files and missing items are refused.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "D3/S5: preserve the injected target=_blank link handler using opener:allow-open-url scoped to http, https and mailto only. Remove opener:default, opener:allow-open-path and opener:allow-reveal-item-in-dir; direct plugin path/reveal and tel calls must be refused. Direct custom commands must enforce the same rules when frontend helpers are bypassed.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/open-guard-rust-repaired.json",
      "newChecks": [
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::refuses_unlisted_types_and_names_each_type",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::refuses_extensionless_files",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::refuses_missing_paths",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::refuses_empty_and_control_local_paths",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::refuses_other_url_schemes_in_every_case",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::refuses_malformed_and_control_urls",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::refuses_urls_on_local_path",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::symlinks_use_resolved_type_and_target",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::refuses_allowed_name_symlink_to_launcher",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::refuses_dangling_and_looping_links",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::linux_colons_are_kept_and_trailing_suffixes_refused",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::reveal_returns_existing_directory_for_files_and_folders",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::refuses_reveal_under_file_or_missing_item",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::linux_commands_refuse_launchers_without_dispatch",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::linux_commands_dispatch_resolved_pdf_and_reveal_directory",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::direct_plugin_ipc_refuses_paths_reveal_and_tel"
      ],
      "repaired": true,
      "unverifiedChecks": [
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_ntfs_short_names_trailing_dots_ads_and_junctions",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_refuses_mixed_network_device_and_ads_inputs",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_symlinks_resolve_document_and_refuse_disguised_launcher"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "All D1 formats (both cases) and allowed web/mail links still validate; the D1 allow-list constant equals the stated list. The existing path/executable preservation tests remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "D1: local opening allows exactly pdf, doc, docx, xls, xlsx, ppt, pptx, odt, ods, odp, rtf, epub, txt, md, csv, tsv, log, json, xml, html, htm, eml, msg, png, jpg, jpeg, gif, bmp, webp, tif, tiff, svg, mp3, wav, m4a, flac, mp4, mov, mkv, webm. Unknown types, scripts, sources and launchers, including macro-enabled Office, mht and mhtml, are refused with the type named and Show in folder offered.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "D4/S1/S2/S7: resolve the path, check the resolved target extension and dispatch the resolved target. A document symlink to an allowed type opens. An allowed-name symlink to a refused target, missing, dangling and looping paths are refused. Preserve UNC, traversal and the original executable refusals. Windows drive letters are not URL schemes; Linux colons are legal. Windows rejects network/device forms and alternate streams before canonicalization, resolves 8.3 and trailing-dot/space names, and accepts only the extended prefix produced by canonicalization. Replacement between validation and dispatch remains a residual.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "D2/S3: external links have their own command, accepting http, https and mailto only, including queries and fragments. Parse with a URL parser; refuse empty input, control characters, NUL and malformed URLs. Every other scheme, including file, javascript, vbscript, data, ms-settings, ms-msdt, search-ms, shell and tel, is refused in any case. Local opening refuses schemes except Windows drive paths.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/open-guard-preserved.json",
      "newChecks": [
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::documents_allow_exact_d1_formats",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::urls_accept_only_web_and_mail"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/plugin-api/capabilities/platform-open-guard.test.ts"
      ],
      "class": "New",
      "behaviour": "Native local/external failures reject once, render the actual advisory toast, and never use a browser fallback. Exercise all three local consumer activations and the Show in folder action. Successful calls use the distinct native routes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "S4: a refusal reaches the caller and shows visible feedback. There is no window.open fallback in Tauri and no second attempt. This applies to BrowseSurface, search-result actions, citation opening and openExternalUrl. Local refusals offer Show in folder. BrowseSurface uses the local command rather than the external URL command.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "D2/S3: external links have their own command, accepting http, https and mailto only, including queries and fragments. Parse with a URL parser; refuse empty input, control characters, NUL and malformed URLs. Every other scheme, including file, javascript, vbscript, data, ms-settings, ms-msdt, search-ms, shell and tel, is refused in any case. Local opening refuses schemes except Windows drive paths.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "D1: local opening allows exactly pdf, doc, docx, xls, xlsx, ppt, pptx, odt, ods, odp, rtf, epub, txt, md, csv, tsv, log, json, xml, html, htm, eml, msg, png, jpg, jpeg, gif, bmp, webp, tif, tiff, svg, mp3, wav, m4a, flac, mp4, mov, mkv, webm. Unknown types, scripts, sources and launchers, including macro-enabled Office, mht and mhtml, are refused with the type named and Show in folder offered.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/open-guard-ui-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/plugin-api/capabilities/platform-open-guard.test.ts#local refusal rejects once, renders feedback, and offers Show in folder",
        "modules/ui-web/src/shell-v0/plugin-api/capabilities/platform-open-guard.test.ts#external refusal rejects once and renders feedback without a browser fallback",
        "modules/ui-web/src/shell-v0/plugin-api/capabilities/platform-open-guard.test.ts#routes BrowseSurface file activation through the local command with visible refusal",
        "modules/ui-web/src/shell-v0/plugin-api/capabilities/platform-open-guard.test.ts#routes search-result activation through the local command with visible refusal",
        "modules/ui-web/src/shell-v0/plugin-api/capabilities/platform-open-guard.test.ts#routes citation activation through the local command with visible refusal",
        "modules/ui-web/src/shell-v0/plugin-api/capabilities/platform-open-guard.test.ts#keeps successful local and external calls on their respective native routes"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/plugin-api/capabilities/platform-open-guard.test.ts"
      ],
      "class": "New",
      "behaviour": "Browser-mode local and external opening retain the existing window.open behavior outside the desktop shell.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F4 targets the desktop shell on Windows and Linux; preserve existing browser-mode opening outside Tauri. macOS is out of scope.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/open-guard-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/plugin-api/capabilities/platform-open-guard.test.ts#keeps browser opening outside Tauri"
      ]
    }
  ]
}
```

## Acceptance records
