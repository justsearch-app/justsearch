---
schema: test-intent.v1
task: t-20261018-6108b0
author-role: builder
author-session: open-guard-builder
---

# Test-intent entries

The seven earlier Windows checks **passed on hosted Windows** at revision `89dc9907d5400ac55e102e7cff94c53f29e0b8cf`: see [original refusals](../evidence/open-guard-windows-repaired.json) and [round-3 dispatch](../evidence/open-guard-windows-round3-repaired.json). These are historical results, not runs of the changed round-4 candidate. Their source-file digests are now stale, and both reruns are included in `tmp/open-guard/windows-evidence-command.txt`.

The four new round-4 Windows checks are **unverified until the next hosted run**. Their two expected D4 files are intentionally absent rather than invented: the Invoice/mapped-drive repair checks need assertion failures on `c87d00139`, and document/bracket-folder marker checks are preservation. The exact commands run all four evidence groups in one hosted job. The mapped-drive check uses a real `net use` mapping to the available loopback administrative share; it removes only its own mapping even on assertion failure. It does not substitute a local drive and claim the UNC defect was exercised. macOS is out of scope.

D4 was generated in a shared scratch clone using `run-evidence.mjs --before` and an interface-only before patch. The patches expose old decisions and raw dispatch bodies under the new helper signatures; they add no validation, resolution or path simplification. Round-4 repairs use c87d00139, round-3 repairs use d6b269468, and original repairs retain origin/main. The round-4 before patch only adapts dispatch return ownership; it preserves the original PowerShell/PATHEXT lookup and VerbatimUNC refusal. Every run sets TMPDIR to this worktree's tmp/ and keeps fixtures there. Cargo output stays in this worktree's target directory. The lower Linux layer is the actual command function with PATH-isolated, marker-writing xdg-open; plugin calls go through Tauri's IPC dispatcher and real ACLs on a mock webview. This does not claim a graphical shell run.

The author has not accepted these entries; independent acceptance remains for the verifier.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Pin F4 local/URL refusals, resolved dispatch, directory-only reveal and opener ACLs through validators, actual command functions with isolated xdg-open, and Tauri mock-webview IPC. The earlier Windows checks passed on hosted Windows; see hostedEvidence. The changed candidate requires the listed reruns.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "D1, revised by F4 round 3: local files allow exactly pdf, doc, docx, xls, xlsx, ppt, pptx, odt, ods, odp, rtf, epub, txt, md, csv, tsv, log, json, xml, html, htm, eml, msg, png, jpg, jpeg, gif, bmp, webp, tif, tiff, svg, mp3, wav, m4a, flac, mp4, mov, mkv, webm, markdown, mdx, yaml, yml, toml, ini, conf, rst, tex, srt, vtt, ics, vcf, mbox, odg, dotx, ppsx, vsdx, xps, mobi, djvu, cbz, heic, avif, ico, psd, raw, ogg, opus, aac, wma, avi, wmv, m4v, mpg, mpeg, zip, 7z. Code, scripts, launchers, macro-enabled Office, mht, mhtml and unknown formats stay refused. Existing directories open in the file manager whatever their names, after resolution. The extension policy is one constant with an explicit test.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "D4/S1/S2/S7: resolve the path, check the resolved target extension and dispatch the resolved target. A document symlink to an allowed type opens. An allowed-name symlink to a refused target, missing, dangling and looping paths are refused. Preserve UNC, traversal and the original executable refusals. Windows drive letters are not URL schemes; Linux colons are legal. Windows rejects network/device forms and alternate streams before canonicalization, resolves 8.3 and trailing-dot/space names, and accepts the extended prefix produced by canonicalization only internally. For Windows shell dispatch, simplify the checked canonical disk target with dunce::simplified, or convert a mapped drive's VerbatimUNC target to a plain UNC path that still names the checked target; refuse device and unrepresentable paths. Never hand a verbatim path to open or Explorer. Replacement between validation and dispatch remains a residual.",
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
      "hostedEvidence": {
        "file": "gates/test-intent/evidence/open-guard-windows-repaired.json",
        "revision": "89dc9907d5400ac55e102e7cff94c53f29e0b8cf",
        "checks": [
          "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_ntfs_short_names_trailing_dots_ads_and_junctions",
          "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_refuses_mixed_network_device_and_ads_inputs",
          "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_symlinks_resolve_document_and_refuse_disguised_launcher"
        ],
        "scope": "Passed on hosted Windows at the recorded revision; rerun required for the round-4 candidate."
      }
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Allowed web/mail links still validate; the existing path/executable preservation tests remain unchanged. The revised format policy has separate repaired evidence against d6b269468.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "D1, revised by F4 round 3: local files allow exactly pdf, doc, docx, xls, xlsx, ppt, pptx, odt, ods, odp, rtf, epub, txt, md, csv, tsv, log, json, xml, html, htm, eml, msg, png, jpg, jpeg, gif, bmp, webp, tif, tiff, svg, mp3, wav, m4a, flac, mp4, mov, mkv, webm, markdown, mdx, yaml, yml, toml, ini, conf, rst, tex, srt, vtt, ics, vcf, mbox, odg, dotx, ppsx, vsdx, xps, mobi, djvu, cbz, heic, avif, ico, psd, raw, ogg, opus, aac, wma, avi, wmv, m4v, mpg, mpeg, zip, 7z. Code, scripts, launchers, macro-enabled Office, mht, mhtml and unknown formats stay refused. Existing directories open in the file manager whatever their names, after resolution. The extension policy is one constant with an explicit test.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "D4/S1/S2/S7: resolve the path, check the resolved target extension and dispatch the resolved target. A document symlink to an allowed type opens. An allowed-name symlink to a refused target, missing, dangling and looping paths are refused. Preserve UNC, traversal and the original executable refusals. Windows drive letters are not URL schemes; Linux colons are legal. Windows rejects network/device forms and alternate streams before canonicalization, resolves 8.3 and trailing-dot/space names, and accepts the extended prefix produced by canonicalization only internally. For Windows shell dispatch, simplify the checked canonical disk target with dunce::simplified, or convert a mapped drive's VerbatimUNC target to a plain UNC path that still names the checked target; refuse device and unrepresentable paths. Never hand a verbatim path to open or Explorer. Replacement between validation and dispatch remains a residual.",
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
          "choice": "D1, revised by F4 round 3: local files allow exactly pdf, doc, docx, xls, xlsx, ppt, pptx, odt, ods, odp, rtf, epub, txt, md, csv, tsv, log, json, xml, html, htm, eml, msg, png, jpg, jpeg, gif, bmp, webp, tif, tiff, svg, mp3, wav, m4a, flac, mp4, mov, mkv, webm, markdown, mdx, yaml, yml, toml, ini, conf, rst, tex, srt, vtt, ics, vcf, mbox, odg, dotx, ppsx, vsdx, xps, mobi, djvu, cbz, heic, avif, ico, psd, raw, ogg, opus, aac, wma, avi, wmv, m4v, mpg, mpeg, zip, 7z. Code, scripts, launchers, macro-enabled Office, mht, mhtml and unknown formats stay refused. Existing directories open in the file manager whatever their names, after resolution. The extension policy is one constant with an explicit test.",
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
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Repair d6b269468: all 78 approved file extensions validate, directories with any name open, and the actual Linux command dispatches folders. Windows real open/reveal checks observe the exact path sent to the OS, refuse unsimplifiable targets, and passed on hosted Windows at 89dc9907; round-4 dispatch changes require a rerun.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F4 round 3: check the canonical Windows target, but dispatch and reveal the simplified non-verbatim form; refuse if that is impossible. Add real dispatch tests for a .txt file, open/Explorer receiving no \\\\?\\ prefix, and bracket/question-like names. Restore opening of existing directories with any name, and reveal folders via Explorer on Windows. D1, revised by F4 round 3: local files allow exactly pdf, doc, docx, xls, xlsx, ppt, pptx, odt, ods, odp, rtf, epub, txt, md, csv, tsv, log, json, xml, html, htm, eml, msg, png, jpg, jpeg, gif, bmp, webp, tif, tiff, svg, mp3, wav, m4a, flac, mp4, mov, mkv, webm, markdown, mdx, yaml, yml, toml, ini, conf, rst, tex, srt, vtt, ics, vcf, mbox, odg, dotx, ppsx, vsdx, xps, mobi, djvu, cbz, heic, avif, ico, psd, raw, ogg, opus, aac, wma, avi, wmv, m4v, mpg, mpeg, zip, 7z. Code, scripts, launchers, macro-enabled Office, mht, mhtml and unknown formats stay refused. Existing directories open in the file manager whatever their names, after resolution. The extension policy is one constant with an explicit test.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/open-guard-round3-repaired.json",
      "newChecks": [
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::documents_allow_exact_d1_formats",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::directories_open_regardless_of_name",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::linux_commands_open_folders_of_any_name"
      ],
      "repaired": true,
      "hostedEvidence": {
        "file": "gates/test-intent/evidence/open-guard-windows-round3-repaired.json",
        "revision": "89dc9907d5400ac55e102e7cff94c53f29e0b8cf",
        "checks": [
          "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_real_open_uses_plain_dispatch_path",
          "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_real_reveal_uses_plain_dispatch_path_for_file_and_folder",
          "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_real_dispatch_handles_brackets_and_question_like_names",
          "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_refuses_verbatim_only_dispatch"
        ],
        "scope": "Passed on hosted Windows at the recorded revision; rerun required for the round-4 candidate."
      }
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Round-4 Windows regressions: real Invoice folder dispatch never runs Invoice.cmd; real mapped UNC document/folder dispatch succeeds while mapped launchers are refused. Both checks are unverified until the next hosted run.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F4 round 4: never pass a Windows open target to command lookup. Open directories with explorer.exe and one exact path argument; documents use ShellExecuteExW with the open verb and the fully qualified checked path. Reveal retains Explorer /select. For Invoice next to Invoice.cmd, report.txt next to report.txt.cmd, and Q3 [draft] next to Q3 [draft].cmd, the real dispatch must not produce a marker within a few seconds. The hosted probe reproduced Invoice executing its neighbor; the other two cases are preservation checks. Mapped drives whose checked canonical target is VerbatimUNC must dispatch the same target as ordinary UNC, with document type validation and directories allowed. Keep refusing device/GLOBALROOT and other unrepresentable paths. A real net use mapping to \\\\localhost\\C$ must open win.ini and refuse a launcher; unavailable administrative share is an environment failure, not a subst-based pass.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/open-guard-windows-round4-repaired.json",
      "newChecks": [
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_folder_does_not_execute_neighbor_cmd",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_mapped_unc_drive_opens_document_and_refuses_launcher"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Round-4 Windows preservation: actual document and bracket-folder dispatch never runs a same-named .cmd neighbor. Both checks are unverified until the next hosted run; the earlier hosted probe observed no marker for these cases.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F4 round 4: never pass a Windows open target to command lookup. Open directories with explorer.exe and one exact path argument; documents use ShellExecuteExW with the open verb and the fully qualified checked path. Reveal retains Explorer /select. For Invoice next to Invoice.cmd, report.txt next to report.txt.cmd, and Q3 [draft] next to Q3 [draft].cmd, the real dispatch must not produce a marker within a few seconds. The hosted probe reproduced Invoice executing its neighbor; the other two cases are preservation checks. Mapped drives whose checked canonical target is VerbatimUNC must dispatch the same target as ordinary UNC, with document type validation and directories allowed. Keep refusing device/GLOBALROOT and other unrepresentable paths. A real net use mapping to \\\\localhost\\C$ must open win.ini and refuse a launcher; unavailable administrative share is an environment failure, not a subst-based pass.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/open-guard-windows-round4-preserved.json",
      "newChecks": [
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_document_does_not_execute_neighbor_cmd",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_bracket_folder_does_not_execute_neighbor_cmd"
      ]
    }
  ]
}
```

## Acceptance records
