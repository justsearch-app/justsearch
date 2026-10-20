---
schema: test-intent.v1
task: t-20261018-6108b0
author-role: builder
author-session: open-guard-builder
---

# Test-intent entries

All eleven earlier Windows checks **passed on hosted Windows at `e22910c2de10eb90232fff11cc9c3248f454a23c`**: [original refusals](../evidence/open-guard-windows-repaired.json), [round-3 dispatch](../evidence/open-guard-windows-round3-repaired.json), [round-4 repairs](../evidence/open-guard-windows-round4-repaired.json), and [round-4 preservation](../evidence/open-guard-windows-round4-preserved.json). Those committed results cover Invoice/PATHEXT, mapped UNC documents and folders, and document/bracket-folder markers. Their source-file digests are stale after round 5; all four reruns are in `tmp/open-guard/windows-evidence-command.txt`.

Hosted [run 39415072540](https://github.com/justsearch-app/justsearch/actions/runs/39415072540) on `df947d57e` established a **round-5 security regression**: folder `lpFile` dispatch through ShellExecuteExW ran Invoice.cmd and Q3 [draft].cmd instead of opening their neighboring folders. The document marker check passed. The new selection trace also establishes a **product reveal failure**: HWND 917988 was at the correct parent, but native SelectionCount was 0 and both indexed and pipeline reads were empty after the unchanged 15-second poll. This supersedes round 6's uncertainty about the observer. The D: NTFS check failed because 8.3 generation was disabled; the hosted job now enables it, and the test is unchanged.

Round 7 parses only the checked plain path into a PIDL. Directory open and directory reveal use ShellExecuteExW with SEE_MASK_IDLIST, lpIDList set to that item, and lpFile NULL. Thus no folder filename is supplied for PATHEXT lookup or Explorer command-line parsing. File reveal clones that item's PIDL, removes its last ID to obtain the parent, and supplies ILFindLastID(item) as one child relative to that parent to SHOpenFolderAndSelectItems. It does not open a folder with an omitted selection. The previous full-item/zero-child call agreed with the documented shortcut but failed to select on this runner; round 7 uses the explicit parent/child form. PIDLs remain alive through the calls and are freed, and COM initialization stays balanced. Documents and URLs retain their existing validated lpFile dispatch.

The existing neighbor checks now exercise both folder open and folder reveal, require the actual expected Explorer location, and still poll for marker side effects. The selection assertion, 15-second observer polling, and pass/unwind cleanup guards remain. [SHELLEXECUTEINFOW](https://learn.microsoft.com/en-us/windows/win32/api/shellapi/ns-shellapi-shellexecuteinfow) documents item-ID dispatch; [SHOpenFolderAndSelectItems](https://learn.microsoft.com/en-us/windows/win32/api/shlobj_core/nf-shlobj_core-shopenfolderandselectitems) documents relative children. No runtime Windows pass is claimed for this candidate: all item-ID changes and reruns remain **unverified until hosted**.

`tmp/open-guard/windows-evidence-command.txt` runs the new Invoice/bracket/reveal regressions against `df947d57e`, retains the comma checks against `143fd387b`, and reruns all historical Windows evidence files. It attempts every group, copies failed artifacts and observer traces too, cleans windows between children, and exits nonzero after all groups if any failed. The hosted job owns core.longpaths and 8.3 setup; the command does not alter them. macOS is out of scope.

D4 was generated in a shared scratch clone using `run-evidence.mjs --before` and an interface-only before patch. The patches expose old decisions and raw dispatch bodies under the new helper signatures; they add no validation, resolution or path simplification. Round-7 regressions use df947d57e and round-5 repairs use 143fd387b with unchanged dispatch signatures; round-4 repairs use c87d00139, round-3 repairs use d6b269468, and original repairs retain origin/main. The round-4 before patch only adapts dispatch return ownership; it preserves the original PowerShell/PATHEXT lookup and VerbatimUNC refusal. Every run sets TMPDIR to this worktree's tmp/ and keeps fixtures there. Cargo output stays in this worktree's target directory. The lower Linux layer is the actual command function with PATH-isolated, marker-writing xdg-open; plugin calls go through Tauri's IPC dispatcher and real ACLs on a mock webview. This does not claim a graphical shell run.

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
        "revision": "e22910c2de10eb90232fff11cc9c3248f454a23c",
        "checks": [
          "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_ntfs_short_names_trailing_dots_ads_and_junctions",
          "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_refuses_mixed_network_device_and_ads_inputs",
          "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_symlinks_resolve_document_and_refuse_disguised_launcher"
        ],
        "scope": "Passed on hosted Windows at e22910c2d; source digest changed and rerun required for round 5."
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
      "behaviour": "Repair d6b269468: all 78 approved file extensions validate, directories with any name open, and the actual Linux command dispatches folders. Windows real open/reveal checks observe the exact path sent to the OS, refuse unsimplifiable targets, and passed on hosted Windows at e22910c2d; round-5 dispatch changes require a rerun.",
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
        "revision": "e22910c2de10eb90232fff11cc9c3248f454a23c",
        "checks": [
          "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_real_open_uses_plain_dispatch_path",
          "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_real_reveal_uses_plain_dispatch_path_for_file_and_folder",
          "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_real_dispatch_handles_brackets_and_question_like_names",
          "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_refuses_verbatim_only_dispatch"
        ],
        "scope": "Passed on hosted Windows at e22910c2d; source digest changed and rerun required for round 5."
      }
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Round-4 mapped UNC documents/folders dispatch while mapped launchers are refused; passed at e22910c2d. The Invoice regression introduced in round 5 now has separate round-7 fail-before evidence. All current Windows reruns remain unverified.",
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
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_mapped_unc_drive_opens_document_and_refuses_launcher"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Document dispatch never executes report.txt.cmd; passed at e22910c2d and in run 39415072540 on df947d57e. Bracket-folder dispatch regressed in round 5 and now has separate round-7 repaired evidence. Current reruns remain unverified.",
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
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_document_does_not_execute_neighbor_cmd"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)",
        "modules/shell/src-tauri/src/open_guard_windows.rs",
        "modules/shell/src-tauri/src/open_guard_windows.ps1"
      ],
      "class": "New",
      "behaviour": "The comma folders must reach the exact folder, and a,b2.txt reveal must show its parent with that file selected. Hosted df947d57e passed comma folder routing but still had native selection count zero for reveal. Round-7 PIDL dispatch keeps these original before assertions against 143fd387b; the candidate is unverified until hosted.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F4 round 5: Windows folder open and reveal pass the checked non-verbatim target as one shell API value, never through Explorer command-line parsing. Keep ShellExecuteExW for documents/URLs, mapped-drive resolution, and neighboring .cmd marker refusals. Observe the actual Explorer location for a plain control, Q4,notes, Q6,root, and a,b2.txt reveal, checking selection too. Comma cases must assertion-fail on 143fd387b; the plain control passes on both. Every Windows dispatch test closes only its own observed windows and recorded new viewer PIDs on pass and failure. Hosted round-4 evidence at e22910c2d is historical; round-5 execution remains unverified until hosted.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F4 round 6: distinguish product selection failure from observer failure using the actual local Explorer view. Keep the a,b2.txt selection assertion and the fail-before requirement on 143fd387b. Read SelectedItems through its documented Count/Item interface and record count, indexed paths, and the original pipeline result; never set selection in the observer. The hosted log omits selection diagnostics, so do not invent a confirmed cause. Attempt every Windows evidence group after failures, preserve cleanup between calls, and exit nonzero at the end if any failed.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/open-guard-windows-round5-repaired.json",
      "newChecks": [
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_comma_folder_window_notes",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_comma_folder_window_root",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_comma_reveal_window_and_selection"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)",
        "modules/shell/src-tauri/src/open_guard_windows.rs",
        "modules/shell/src-tauri/src/open_guard_windows.ps1"
      ],
      "class": "New",
      "behaviour": "Round-5 GUI control passed on 143fd387b and 394392a8d in hosted run 39412497810. It must pass again with the changed observer; an unavailable GUI is a failure.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F4 round 5: Windows folder open and reveal pass the checked non-verbatim target as one shell API value, never through Explorer command-line parsing. Keep ShellExecuteExW for documents/URLs, mapped-drive resolution, and neighboring .cmd marker refusals. Observe the actual Explorer location for a plain control, Q4,notes, Q6,root, and a,b2.txt reveal, checking selection too. Comma cases must assertion-fail on 143fd387b; the plain control passes on both. Every Windows dispatch test closes only its own observed windows and recorded new viewer PIDs on pass and failure. Hosted round-4 evidence at e22910c2d is historical; round-5 execution remains unverified until hosted.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/open-guard-windows-round5-preserved.json",
      "newChecks": [
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_folder_window_control"
      ],
      "hostedEvidence": {
        "file": "gates/test-intent/evidence/open-guard-windows-round5-control-before.json",
        "revision": "143fd387b3cef33b49dcf9f6fc684129a10606ba",
        "checks": [
          "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_folder_window_control"
        ],
        "scope": "Passed on 143fd387b in hosted run 39412497810; artifact provided under tmp/open-guard/round6/evidence/. A rerun with the changed observer is pending."
      }
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Round-7 regressions: Invoice and Q3 [draft] opening/reveal never run adjacent .cmd files and reach their actual folders; comma-file reveal selects a,b2.txt. All three checks must assertion-fail on df947d57e and pass with PIDL dispatch. Current Windows results are unverified until hosted.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F4 round 7: repair the hosted round-5 folder security regression first. Resolve the checked plain folder to an item ID; open/reveal directories with SEE_MASK_IDLIST and lpIDList, with lpFile NULL, so no folder name enters command/PATHEXT lookup. Reveal a file with its parent PIDL and one relative child derived from that item PIDL. Keep documents/URLs on their existing validated dispatch. Native SelectionCount zero after polling confirms the reveal failure is in the product. Keep all marker, comma, plain-control, selection and cleanup checks. The hosted job enables 8.3 generation and long paths; do not weaken the NTFS check or configure those in the script. Attempt all evidence files, clean between calls, and fail the job at the end if any failed.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/open-guard-windows-round7-repaired.json",
      "newChecks": [
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_folder_does_not_execute_neighbor_cmd",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_bracket_folder_does_not_execute_neighbor_cmd",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_comma_reveal_window_and_selection"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
