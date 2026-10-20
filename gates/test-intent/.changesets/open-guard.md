---
schema: test-intent.v1
task: t-20261018-6108b0
author-role: builder
author-session: open-guard-builder
---

# Test-intent entries

All eleven earlier Windows checks **passed on hosted Windows at `e22910c2de10eb90232fff11cc9c3248f454a23c`**: [original refusals](../evidence/open-guard-windows-repaired.json), [round-3 dispatch](../evidence/open-guard-windows-round3-repaired.json), [round-4 repairs](../evidence/open-guard-windows-round4-repaired.json), and [round-4 preservation](../evidence/open-guard-windows-round4-preserved.json). Those results cover Invoice/PATHEXT, mapped UNC documents and folders, and document/bracket-folder markers. All four were rerun on the final dispatch in hosted windows-latest run 39428698231 at 75bab60ce (job log kept in the task evidence); the committed evidence files are those reruns.

Round 9 supersedes the round-8 focus-or-selection interpretation. The hosted round-8 log (run 39423698535) records the correct parent with SelectionCount=0 and Focused=000-unrelated.txt for both legacy Explorer /select and the candidate. The earlier single-file probe could not distinguish incidental focus from reveal targeting. Hosted Explorer therefore provides no reliable observable selection or focus for a reveal. These are supplied Windows observations, not executions by this Linux builder.

The GUI checks now require the **exact parent folder window** for both plain and comma reveals, recording Selected, SelectionCount, Focused and pipeline values as observations only. They still poll for the exact unique fixture parent. The plain reveal control must pass before/after 143fd387b; the comma reveal must assertion-fail before (Desktop) and pass after. Every other check and cleanup remains unchanged.

Item targeting is independently checked at the native item-ID level. Production and the new Windows test use the same `windows_reveal::with_item_ids` function, including the exact parent/count/children arguments handed to SHOpenFolderAndSelectItems. For a,b2.txt, Q3 [draft].txt and plain.txt, SHGetPathFromIDListEx must resolve the parent to the exact parent and ILCombine(parent, child) to the exact file. The test requires exactly one child. The hosted command runs two production-helper mutations: passing the parent alone (count=0) and passing the parent's own last child instead of the file's. Both must produce executed assertion failures; a compile error or unavailable runner does not count. The mutation patches are `open-guard-round9-parent-only.patch` and `open-guard-round9-wrong-child.patch`; their native runtime results, like the new PIDL and amended GUI checks, were verified on hosted windows-latest run 39428698231 at 75bab60ce. Linux applied and Windows-target typechecked the candidate and both mutations, but cannot execute Windows shell APIs.

Linux mutation reasoning, after applying and typechecking both patches: parent-only passes count=0, so the shared-argument test returns Err before reading children and fails its outer assert_eq against Ok. Wrong-child appends the parent folder's own final ID to that parent, which cannot equal any of the three requested file names; resolution either errors or yields a different target, and the same assertion rejects it. The hosted runs of both mutations in hosted windows-latest run 39428698231 at 75bab60ce (job log kept in the task evidence) failed by assertion; their evidence is open-guard-windows-round9-parent-only.json and open-guard-windows-round9-wrong-child.json.

Production PIDL semantics stay as in round 7: folders use SEE_MASK_IDLIST/lpIDList with lpFile NULL; reveal uses the resolved parent plus relative child. Documents and URLs retain their dispatch. The command attempts every evidence group, including both mutation controls, then exits nonzero if any unexpected result occurred. Each GUI test's cleanup finishes before the next call. The job owns 8.3/long-path setup. macOS is out of scope.

D4 was generated in a shared scratch clone using `run-evidence.mjs --before` and an interface-only before patch. The patches expose old decisions and raw dispatch bodies under the new helper signatures; they add no validation, resolution or path simplification. Round-7 neighbor regressions use df947d57e, while comma reveal/routing repairs and both plain controls use 143fd387b with unchanged dispatch signatures; round-4 repairs use c87d00139, round-3 repairs use d6b269468, and original repairs retain origin/main. The round-4 before patch only adapts dispatch return ownership; it preserves the original PowerShell/PATHEXT lookup and VerbatimUNC refusal. Every run sets TMPDIR to this worktree's tmp/ and keeps fixtures there. Cargo output stays in this worktree's target directory. The lower Linux layer is the actual command function with PATH-isolated, marker-writing xdg-open; plugin calls go through Tauri's IPC dispatcher and real ACLs on a mock webview. This does not claim a graphical shell run.

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
        "scope": "Passed on hosted Windows at e22910c2d and again on the final dispatch in hosted windows-latest run 39428698231 at 75bab60ce (job log kept in the task evidence)."
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
        "scope": "Passed on hosted Windows at e22910c2d and again on the final dispatch in hosted windows-latest run 39428698231 at 75bab60ce (job log kept in the task evidence)."
      }
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Round-4 mapped UNC documents/folders dispatch while mapped launchers are refused; passed at e22910c2d. The Invoice regression introduced in round 5 now has separate round-7 fail-before evidence. All current Windows reruns were verified on hosted windows-latest run 39428698231 at 75bab60ce.",
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
      "behaviour": "Document dispatch never executes report.txt.cmd; passed at e22910c2d and in run 39415072540 on df947d57e. Bracket-folder dispatch regressed in round 5 and now has separate round-7 repaired evidence. Current reruns were verified on hosted windows-latest run 39428698231 at 75bab60ce.",
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
      "behaviour": "Comma folders reach the exact folder; comma-file reveal reaches its exact parent. Focused/Selected are diagnostics only because the hosted round-8 log disproves their reliability. The amended comma checks must assertion-fail on 143fd387b and pass after; current Windows execution was verified on hosted windows-latest run 39428698231 at 75bab60ce.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F4 round 5: Windows folder open and reveal pass the checked non-verbatim target as one shell API value, never through Explorer command-line parsing. Keep ShellExecuteExW for documents/URLs, mapped-drive resolution, and neighboring .cmd marker refusals. Observe the actual Explorer location for a plain control, Q4,notes, Q6,root, and a,b2.txt reveal, checking selection too. Comma cases must assertion-fail on 143fd387b; the plain control passes on both. Every Windows dispatch test closes only its own observed windows and recorded new viewer PIDs on pass and failure. Hosted round-4 evidence at e22910c2d is historical; round-5 and later execution was verified on hosted windows-latest run 39428698231 at 75bab60ce.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F4 round 6: distinguish product selection failure from observer failure using the actual local Explorer view. Keep the a,b2.txt selection assertion and the fail-before requirement on 143fd387b. Read SelectedItems through its documented Count/Item interface and record count, indexed paths, and the original pipeline result; never set selection in the observer. The hosted log omits selection diagnostics, so do not invent a confirmed cause. Attempt every Windows evidence group after failures, preserve cleanup between calls, and exit nonzero at the end if any failed.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F4 round 8: the hosted probe establishes that Explorer focuses reveal targets without selecting them, including classic /select on plain.txt, while SelectItem proves native selection can be observed. Require the exact parent view AND the exact target focused or selected; record both values and keep polling. Add a plain-name reveal control that passes on 143fd387b and after; the comma reveal must still fail on 143fd387b (Desktop) and pass after. Keep every other check and production dispatch unchanged. Do not require the amended reveal assertion to fail on df947d57e, where focus may already satisfy the corrected contract; retain its fail-before on 143fd387b. Retain run-everything-then-fail and cleanup between Windows evidence calls. All current Windows results were verified on hosted windows-latest run 39428698231 at 75bab60ce.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F4 round 9: hosted Explorer gives no reliable reveal selection or focus even with classic /select and a decoy file. GUI checks require the exact parent only and retain Focused and Selected diagnostics. Plain reveal must pass before/after 143fd387b; comma reveal must fail before (Desktop) and pass after. Independently resolve production-built parent and relative-child PIDLs to the exact parent and target for a,b2.txt, Q3 [draft].txt and plain.txt. Mutation controls must reject parent-only and wrong-child production arguments by assertion. Keep every other check, cleanup and run-everything-then-fail command behavior. Current Windows results were verified on hosted windows-latest run 39428698231 at 75bab60ce.",
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
      "behaviour": "The plain-folder control and plain.txt reveal control require the exact folder/parent and must pass on 143fd387b and the candidate. Focused/Selected are diagnostic only. Current Windows execution was verified on hosted windows-latest run 39428698231 at 75bab60ce.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F4 round 5: Windows folder open and reveal pass the checked non-verbatim target as one shell API value, never through Explorer command-line parsing. Keep ShellExecuteExW for documents/URLs, mapped-drive resolution, and neighboring .cmd marker refusals. Observe the actual Explorer location for a plain control, Q4,notes, Q6,root, and a,b2.txt reveal, checking selection too. Comma cases must assertion-fail on 143fd387b; the plain control passes on both. Every Windows dispatch test closes only its own observed windows and recorded new viewer PIDs on pass and failure. Hosted round-4 evidence at e22910c2d is historical; round-5 and later execution was verified on hosted windows-latest run 39428698231 at 75bab60ce.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F4 round 8: the hosted probe establishes that Explorer focuses reveal targets without selecting them, including classic /select on plain.txt, while SelectItem proves native selection can be observed. Require the exact parent view AND the exact target focused or selected; record both values and keep polling. Add a plain-name reveal control that passes on 143fd387b and after; the comma reveal must still fail on 143fd387b (Desktop) and pass after. Keep every other check and production dispatch unchanged. Do not require the amended reveal assertion to fail on df947d57e, where focus may already satisfy the corrected contract; retain its fail-before on 143fd387b. Retain run-everything-then-fail and cleanup between Windows evidence calls. All current Windows results were verified on hosted windows-latest run 39428698231 at 75bab60ce.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F4 round 9: hosted Explorer gives no reliable reveal selection or focus even with classic /select and a decoy file. GUI checks require the exact parent only and retain Focused and Selected diagnostics. Plain reveal must pass before/after 143fd387b; comma reveal must fail before (Desktop) and pass after. Independently resolve production-built parent and relative-child PIDLs to the exact parent and target for a,b2.txt, Q3 [draft].txt and plain.txt. Mutation controls must reject parent-only and wrong-child production arguments by assertion. Keep every other check, cleanup and run-everything-then-fail command behavior. Current Windows results were verified on hosted windows-latest run 39428698231 at 75bab60ce.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/open-guard-windows-round5-preserved.json",
      "newChecks": [
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_folder_window_control",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_plain_reveal_window_control"
      ],
      "hostedEvidence": {
        "file": "gates/test-intent/evidence/open-guard-windows-round5-control-before.json",
        "revision": "143fd387b3cef33b49dcf9f6fc684129a10606ba",
        "checks": [
          "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_folder_window_control"
        ],
        "scope": "Passed on 143fd387b in hosted run 39412497810; The rerun with the changed observer passed in run 39428698231."
      }
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Invoice and Q3 [draft] opening/reveal never run adjacent .cmd files and reach their actual folders. Both must assertion-fail on df947d57e and pass with PIDL dispatch. Other checks are unchanged; comma reveal is covered separately against 143fd387b with the exact-parent GUI assertion. Current reruns were verified on hosted windows-latest run 39428698231 at 75bab60ce.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F4 round 8: the hosted probe establishes that Explorer focuses reveal targets without selecting them, including classic /select on plain.txt, while SelectItem proves native selection can be observed. Require the exact parent view AND the exact target focused or selected; record both values and keep polling. Add a plain-name reveal control that passes on 143fd387b and after; the comma reveal must still fail on 143fd387b (Desktop) and pass after. Keep every other check and production dispatch unchanged. Do not require the amended reveal assertion to fail on df947d57e, where focus may already satisfy the corrected contract; retain its fail-before on 143fd387b. Retain run-everything-then-fail and cleanup between Windows evidence calls. All current Windows results were verified on hosted windows-latest run 39428698231 at 75bab60ce.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F4 round 9: hosted Explorer gives no reliable reveal selection or focus even with classic /select and a decoy file. GUI checks require the exact parent only and retain Focused and Selected diagnostics. Plain reveal must pass before/after 143fd387b; comma reveal must fail before (Desktop) and pass after. Independently resolve production-built parent and relative-child PIDLs to the exact parent and target for a,b2.txt, Q3 [draft].txt and plain.txt. Mutation controls must reject parent-only and wrong-child production arguments by assertion. Keep every other check, cleanup and run-everything-then-fail command behavior. Current Windows results were verified on hosted windows-latest run 39428698231 at 75bab60ce.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/open-guard-windows-round7-repaired.json",
      "newChecks": [
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_folder_does_not_execute_neighbor_cmd",
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_bracket_folder_does_not_execute_neighbor_cmd"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Resolve the exact production reveal parent and relative child PIDLs to the intended parent/file for comma, bracket and plain names. Require one child and exact combined target; hosted parent-only/wrong-child mutations must assertion-fail. Current native Windows results were verified on hosted windows-latest run 39428698231 at 75bab60ce. Expected-failure artifacts: open-guard-windows-round9-parent-only.json and open-guard-windows-round9-wrong-child.json.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "F4 round 9: hosted Explorer gives no reliable reveal selection or focus even with classic /select and a decoy file. GUI checks require the exact parent only and retain Focused and Selected diagnostics. Plain reveal must pass before/after 143fd387b; comma reveal must fail before (Desktop) and pass after. Independently resolve production-built parent and relative-child PIDLs to the exact parent and target for a,b2.txt, Q3 [draft].txt and plain.txt. Mutation controls must reject parent-only and wrong-child production arguments by assertion. Keep every other check, cleanup and run-everything-then-fail command behavior. Current Windows results were verified on hosted windows-latest run 39428698231 at 75bab60ce.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/open-guard-windows-round9-item-ids.json",
      "newChecks": [
        "modules/shell/src-tauri/src/lib.rs#open_guard_tests::windows_reveal_item_ids_identify_exact_target"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-aa814ea0ee38dde3e",
  "verdict": "accept",
  "digest": "sha256:e4239ca35f1bd683f2610ecdd61081b6280277767d6386d91d96741bd7cec838",
  "note": "Verifier round 4 at 0c0114ac2, changeset wording at dec9f943b checked against run 39428698231 evidence: Linux S1-S7 attacks and real shell unchanged and safe; hosted Windows item-ID folder open/reveal fixes comma routing with controls, no neighbor .cmd runs, item-ID mutations are caught; real-desktop selection remains unobservable on hosted runners."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-aa814ea0ee38dde3e",
  "verdict": "accept",
  "digest": "sha256:4fec4a7c4997d19a501912d243e5719184e8c85792a232a1603110037968857c",
  "note": "Verifier at e8be8f592: the GUI observer now compares GetLongPathNameW-expanded full paths for equality and never matches on expansion failure, so a different folder or its parent still fails; the store-recoverability entry is truthful and its check passes; PR CI run 39446626686 passed all 17 Windows open_guard tests with default TEMP; round-4 behavioural verification stands."
}
```
