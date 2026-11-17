# Changelog

All notable changes to JustSearch are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project aims to
follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) once it reaches a stable release.

> **Note on history.** JustSearch is published with a **fresh git history** — the public repository starts
> at the open-source release, not at the project's first private commit. The project has been in active
> development since 2025; this changelog records user-facing changes from the public release forward, not
> the full internal development timeline (that design history lives in [`docs/tempdocs/`](docs/tempdocs/),
> which is dated, working-note archaeology — not current product documentation).

## [Unreleased]

## [0.3.5] - 2026-11-17

### Added
- Export a Search conversation by copying or saving Markdown or JSON, keeping settled answers, citations, sources and turn statuses (#1059)
- On first launch, Search offers Add a folder or a shortcut to Documents, Desktop or Downloads, then focuses the search field and shows how many of your files are searchable once the first one is ready (#1188, #1233, #1248, #1264, #1278)
- A startup frame shows while the app loads, with Reload and Copy report available if the interface cannot start (#1192)
- Move the desktop window by dragging its top area, double-click to maximize or restore, and use minimize, maximize and close controls even during startup or recovery; close hides to the tray, and tray Quit exits (#1194)

### Changed
- **If you use 0.3.0, install 0.3.5 over your existing installation to keep your data.** The new updater uses a different signed release format (#993, #1053)
- One top bar switches between Search, Library and AI Brain, with Settings, System and Help & shortcuts in its menu, a small green status dot after setup when everything is quiet, and text for running work, problems and requests (#1123, #1134, #1233, #1243, #1263, #1264, #1278)
- The status panel groups reading and AI downloads into one row each, lets you pause reading there, shows when a folder becomes searchable, and keeps requests and failures visible while putting routine history behind the Show developer tools setting (#1123, #1243, #1272, #1278, #1282)
- Desktop notifications appear in the background for waiting requests or long work that finishes or fails, open the item when clicked, and have Off, On and On with sound choices in Settings (#1161)
- Library shows file counts and reading status for each folder, with details for files being read, files that could not be read and the last check for changes, while Browse shows a keyboard-navigable file tree sorted by name with size, date and file actions (#1096, #1189, #1234, #1248, #1267, #1269, #1281, #1282)
- Skip rules save when you leave a field and reading hours save as you change them, with an in-place confirmation to remove already-read matches, a choice when another window changes the rules, and visible keyboard focus when editing or removing rules (#1189, #1271, #1277)
- Read everything again is at the bottom of Library beside Activity and asks for confirmation in place before it starts (#1189, #1269, #1281, #1282)
- Read waiting files now temporarily lets files held by reading hours or the battery rule be read while keeping the saved rules and leaving background AI work paused (#1273)
- AI Brain shows what works now and offers separate switches for Search by meaning, Best match first, Text in scans and images, and Answers, with a Memory tab when answers work and graphics memory shown only after AI is installed (#1100, #1233, #1239, #1268, #1278, #1283)
- Reading memory controls how much text the answer model can read at once, and the optional Pause answers while files are read switch holds new answers during a large backlog while searches and answers already being written continue, then automatically sends a question waiting in Search (#1100, #1137, #1201, #1268, #1282)
- Install AI asks before downloading and lists each part’s size, licence and terms, then shows progress by part with a retry or cancel option if the download stops while word search stays available (#1243, #1270, #1276, #1278, #1283)
- Best match first changes result ranking without restarting and keeps its downloaded files, with automatic ranking improvements off by default when search runs on the CPU and existing explicit choices preserved (#1118, #1182)
- Text in scans and images switches on or off without restarting, keeps downloaded files and previously read text, resumes waiting images when enabled, and explains when the answer model cannot read them (#1143, #1186)
- AI Brain lists downloaded parts and their sizes and offers Remove AI models when AI is installed and answers are unavailable, taking you to controls that ask before removing reranker files or answer and picture-model files when both features are off (#1160, #1187, #1276, #1283)
- Model removal keeps files still in use or that the app cannot safely identify, restores captured files after an interruption, and keeps removed files out of Repair until you confirm Download again (#1160, #1187)
- Open Settings from the top-bar menu to search seven sections and jump to controls, including controls in Library or AI Brain (#1116, #1206, #1208, #1263)
- Press Ctrl+K to find actions, settings, folders, loaded conversations and areas across the app (#1214, #1263, #1264)
- Search uses a single-line field with a Clear button, shows recent searches with counts, times and a way to clear them on the idle page, and adds a conversations sidebar when there is a conversation and a Search / Ask switch when answers are available (#1264, #1279, #1282, #1283)
- Search results stay under the field with a match count and plain-language filters such as Documents and Mail, while an empty result offers Add a folder and suggests search by meaning when AI is missing (#1265, #1279)
- Matching built-in Help pages appear as a link below Search results, including when Help is the only match (#1266)
- One resizable details pane per window opens from a search result or a citation, shows source and context tabs for answers, docks at the right edge in wide Search windows, and opens over the page in narrow windows (#1167, #1265, #1274, #1280)
- Opening a result shows the file’s path, Open file and Show in folder, and jumps to the matching passage when its location can be confirmed, with found words highlighted and internal scan markers hidden (#1265, #1275, #1282, #1284)
- Wide windows center each area’s content, and the app ships its own Inter typeface, uses teal for active controls and primary actions (#1259, #1262, #1274, #1281)
- File and download sizes use the same units and rounding in Search, Browse, AI Brain and Health (#1276)
- System shows six plain-language Health rows with technical readouts under Diagnostics and an Activity feed where you can filter records, see their files and receipts, and use the available undo, restore, save and export actions (#1219, #1220, #1227)
- Activity opens the visible feed and focuses it when you arrive from the status panel or command palette, uses the same plain language in records and details, and resolves file names from the Engine that supplied the record (#1243, #1282)
- Most risky actions confirm beside the item, offer undo where a reversal exists, show failures and keep their receipts in Activity; delayed actions return keyboard focus to their surviving control. Delete all local data keeps its typed confirmation dialog. Agent requests wait for review in the status panel, high-risk requests retain their protected dialog, and Deny cancels the request across windows and reloads (#1115, #1139, #1147, #1149, #1155, #1174)
- **Existing libraries re-read and re-index every file once after upgrading because the extraction policy changed.** Language filters use the text of each file, sampling throughout longer documents so an opening such as a site menu is less likely to decide the language, and leave code, structured data and text with too little prose unlabeled (#1098, #1198, #1209, #1223, #1229, #1231)
- Learned-sparse search enrichment (SPLADE) is off by default, reducing background work and graphics memory use; existing sparse data stays, and default search is unchanged. Explicitly enabling SPLADE and restarting backfills unfinished work; a SPLADE search while it is off uses the other search methods and explains why (#1162)
- Partial enrichment updates preserve completed sparse search artifacts without running the sparse encoder again; existing indexes acquire the stored sparse payload lazily on rewrite. Re-extracting identical text reuses matching completed model outputs, while forced re-indexing still recomputes everything (#1109, #1213)
- **Read your files again if you downgrade an index written by this version to v0.3.2.** That version cannot read the new index fields, and older versions refuse the upgraded job and operation databases without modifying them (#1109, #1128, #1150)
- Windows and Linux installers include the project licence and third-party notices beside the Engine, with maintainer attribution and corrected BSD attribution (#1080, #1130, #1178)

### Fixed
- PDF extraction keeps word boundaries, rejoins words split by a line-end hyphen, and retains distinct page-edge text, including differences in letter case, though more running headers can remain and some letter-spaced headings may no longer match keyword searches (#1070, #1142, #1198, #1209)
- Tables keep wider rows and text before a content limit, Office files with many slides, images or package parts can be indexed, and words in table cells and list items stay separate in Word, HTML and OpenDocument files (#1070, #1142, #1198, #1209, #1256)
- Reading Office files leaves out hidden picture filenames, duplicate page previews and footnote markers while keeping footnote text, and places HTML table captions before their tables (#1261)
- OCR keeps completed pages when its time budget expires, reports missing coverage, reads sideways scans and oriented phone photos, and defaults to German and English when their language data is available, at about twice the previous OCR time per page (#1070, #1087, #1229)
- AI picture reading keeps fuller existing text when a transcription is incomplete, resumes automatically after a vision-server crash or cooldown, and keeps working through other files when one picture is corrupt or too large (#1087, #1229, #1255, #1260)
- Metadata filters apply to passage results, selections of 1,024 or more files work, and invalid relevance cursors are rejected. Search without reranking ranks to a stable depth before cutting the page; reranking skips overlong queries with a reason and also considers leading candidates' document openings (#1118, #1164, #1180, #1230)
- New keeps the previous conversation's draft, selected files and still-arriving answer; reopened unfinished answers are checked against saved outcomes instead of being resent or prematurely marked failed. Retry becomes available after its wait even if its timer wakes early (#1076, #1082, #1211)
- Indexing detects same-size edits with restored timestamps on Linux while running, checks for changes during reading, and keeps forced re-indexing across restarts; sync repairs missing or damaged passages without repeatedly restarting unchanged work during upgrades. Watched files whose extraction failed get a new attempt after extraction limits or policy change (#1086, #1111, #1122, #1125, #1138, #1204)
- Interrupted queue work and folder initialization retry after recoverable failures, and unfinished imports keep the records they need to finish. An import interrupted by a crash or restart resumes by itself on its accepted plan when it had durable approval; an import approved only once is listed as interrupted in Library with Resume, which asks for approval again, and Cancel wins over Resume. A failure later in a batch no longer hides earlier accepted index updates, and errors while notifying the app cannot make a committed action appear to fail (#1072, #1150, #1171, #1176, #1181, #1232)
- If the recorded volume of a watched folder is unavailable or replaced, its indexed documents are kept; a different drive mounted inside a completed one-off import is protected, but one mounted inside a watched folder is not yet. Deletions and interrupted renames recover correctly, and removed folders and collections stay removed. Removing already-read matches to skip rules also checks files outside watched folders, and folder-relative rules cover completed one-off folder imports. Folder scans stay within their watched scope, accept filenames with consecutive dots, recover lost watchers, report unreadable files as incomplete, and clear coverage warnings after a complete successful check (#1083, #1097, #1148, #1236)
- Completed one-off folder imports stay current: deleted files, subfolders and a deleted import folder leave search, changed files are re-read when the import has durable approval, and folder-relative skip rules apply to their files; new files are not picked up. Their documents are kept while their drive is unplugged, replaced or mounted over. Changed files from imports made before this version are re-read only after importing them again; deletions and applicable skip rules still apply, edits that keep a file's size and modification time are not detected, and files removed by a skip rule come back only by importing again (#1236)
- Background enrichment advances across long documents and large backlogs, shares time between stages, lets CPU work continue during GPU holds, and stops promptly when indexing is paused. Completed work is kept, replaced models cannot publish stale results, and storage or model failures back off without charging unrelated files retries (#1073, #1095, #1117, #1127, #1129, #1136, #1144, #1151, #1166)
- Meaning search correctly encodes long documents, entity recognition covers the whole text, keeps short names and retries failures, and changes to a model's tokenizer or other files are detected. Person, place and organization name grouping covers the whole library, follows edits and deletions, and rebuilds existing groups once on upgrade, changing group IDs (#1090, #1092)
- Engine shutdown drains native inference before exiting on Linux, failed stops and resets report failure, and Windows restarts recognize an Engine that has already exited. Index switches keep models that are still starting, ready answer models are noticed sooner, and helpers that exit immediately retain their real exit details (#1061, #1081, #1099, #1193, #1205, #1218)
- Losing the Engine shows a recovery notice inside the app with Restart the engine and Copy report, keeps Health reachable even through repeated crashes, and restores keyboard focus after recovery, minimizing or closing to the tray (#1197, #1210, #1225, #1252)
- Right-clicking the desktop window shows the usual editing menu in text fields and no browser menu around the rest of the app (#1252)
- The Engine avoids an unnecessary restart when an index migration is requested just after startup (#1253)
- Built-in Help stays available after a search-index rebuild, including one that resumes after a restart (#1251)
- Health marks affected values as last known once a status read fails, counts your own files separately from bundled Help, and reports unknown file counts in the app and MCP when the reading worker has not reported them (#1234, #1248)
- Health, Logs and Library’s Browse view keep their content reachable in small windows, and the packaged app displays dark-theme dropdowns, progress bars, folder indentation, swatches and user themes correctly (#1234, #1257, #1282)
- Indexing progress and search readiness reflect stored results, failed or unreadable enrichment and unknown counts; enrichment outcomes report what actually completed. AI Brain keeps polling after a hung status read, and recovered queue operations clear their matching health failures (#1084, #1104, #1121, #1179)
- A fresh install offers Install AI and reserves warnings for AI downloads that fail or stop (#1233, #1239, #1270, #1283)
- A temporarily unreadable settings file is reported as unavailable instead of being moved aside and replaced with defaults, and startup preserves settings written by a newer version (#1188, #1221)
- Settings reopened at launch, after the app quit with it open, is a real dialog again above the window controls and closes with its X and with Escape (#1238)
- Dates and times display correctly in the desktop app, and keyboard focus rings remain visible across built-in palettes, including Sepia Focus and High Vis (#1199, #1202)
- The plugin authoring SDK supplies current, self-contained host types and loads through both its declared ESM and CommonJS entry points (#1177)

### Security
- Recognized credential file names and extensions, credential folders and the app's data folder are excluded from user indexing; **hidden folders below a watched folder are no longer indexed, and matching existing entries are removed on first start**, without changing files on disk. Hidden-folder exclusion covers dot-prefixed folders relative to the watched root; ordinary files added individually from inside them stay eligible. Directly watched hidden folders remain eligible apart from protected files, and the app's own Help and assistant-history entries stay searchable. **Every `.key` and `.asc` file is excluded, including Keynote documents, so such files previously searchable disappear from results** (#1165)
- MCP clients cannot access the built-in assistant's history; in the packaged app, HTTP access to it needs the app window's credential; headless and development HTTP access keeps its existing authority (#1165)
- The desktop app's updater drops unused ZIP extraction support and its vulnerable dependency; that code was not reachable (#1088)
- Packaged-app approvals, grants, plugin trust and settings changes require the app window's credential, preventing token-only clients from approving their own requests; the built-in assistant and folder-adding links now ask under default permissions, and the legacy REST bridge can no longer ingest. Queued high-risk and plugin permission prompts keep their protected dialog (#1107, #1115)
- The app leaves index directories alone unless it can prove ownership and explains when an unrecognized directory blocks search. Assistant file moves refuse existing destinations, including dangling links, and keep a source that changed during a copy instead of deleting it (#1108, #1185)

#### Known Issues
- Saved bookmarks stay on disk, but the app has no bookmark list or bookmark controls (#1134)
- AI answers require a supported NVIDIA graphics card, and the Linux installer currently offers CPU search because its model registry has no Linux GPU packs (#1030, #1044)

## [0.3.2] - 2026-10-30

### Added
- Linux desktop support: an x86_64 `.deb` for Ubuntu 24.04 and later, with a desktop launcher, tray and `justsearch://` links (#761, #986)
- Search lists documents, filters by folder, date and size, and understands exact phrases, exclusions, `title:` and `author:`; Structured mode extracts schema-checked JSON (#805, #911, #1011, #1012, #1014)
- Recent searches and conversations open from the search bar; conversation branches show their original and let you return to it (#912, #1009)
- Pause indexing from its status controls, keep it paused across restarts, or set indexing hours and pause on battery; Search stays available (#904, #918, #1033)
- Forget indexed files or folders from a Search result’s menu; permitted folders and files inside watched folders stay excluded until their exclusion is removed, while files outside watched folders can return after an explicit add or import; original files, saved answers, logs and backups remain; review and forget assistant memories from Search (#935, #998)
- Settings can delete local data while keeping downloaded AI models; Alt+Shift+Space summons Search on Windows and Linux X11, with `--focus-search` as the Wayland alternative (#821)
- Turn off automatic update checks with `JUSTSEARCH_AUTO_UPDATE_CHECK=false`; administrators can forbid all app-update checks and downloads with `appUpdatesEnabled` in policy (#856)

### Changed
- Search replaces the old chat window; old links, bookmarks and plugins open Search, answer and extraction links wait for you to send, and workflow-run links show an unavailable notice (#936, #1003, #1005)
- Windows and Linux updates use signed multi-platform metadata; Linux installs through a system password prompt. **0.3.0 users cannot update in-app to this release: install the new version over the old one; your data is kept** (#993, #1053)
- Install AI works on Linux for CPU search, obeys download policy even when it cannot be read, names missing required downloads as failures, resumes retries and repairs incomplete GPU-pack extraction; empty libraries can activate models (#853, #897, #992, #1013)
- Search and chat share GPU memory, background work yields while chat answers, and chat unloads with a reason when another app needs the memory; the Engine uses less memory at startup (#759, #907, #952)
- Answers show when citation checking is in progress or incomplete and when a source is gone; result highlights follow whole words and source previews hide supported front matter (#903, #938, #985, #1051)
- Update the Claude Desktop extension (MCPB) to 0.3.1: older bridges receive HTTP 401; the updated bridge also works with the previous Engine. Production API reads and streams require the per-boot token except six public Runtime Contract reads; native clients use the owner-only runtime manifest, and explicit port overrides require both a proven manifest and `JUSTSEARCH_SESSION_TOKEN` (#775, #799, #825)
- API settings writes require the latest GET witness and a fresh canonical UUIDv7 operation key (#727)

### Removed
- The pins panel and the old window’s automatic search-history events and pin-run updates; existing history and pins stay, and plugins can still read pins (#1005)
- Open file no longer opens files without an extension or with an unsupported extension; use Show in folder to access them instead (#833)
- `/api/mcp/token` and stdout token emission; `/api/inference/reload` gives way to `core.reconfigure`, and `POST /api/worker/restart` returns HTTP 410: use `POST /api/engine/components/index/recover` (#727, #825)

### Fixed
- Text extraction preserves UTF-8, UTF-16, split Unicode characters and PDF page boundaries, reports decoding loss, and respects configured extraction limits (#976, #981, #983, #989, #995, #997, #1002)
- Changed files with old timestamps are re-read, exclusions stay enforced, watcher deletions cannot remove newer content, returning drives resume, and files keep their declared folder’s collection (#843, #848, #910, #1004, #1045)
- Upgrading from 0.3.0 keeps a compatible index and queued work; interrupted index switches recover, and removing folders during rebuilds leaves nested watched folders intact (#893, #921, #951, #1010, #1016)
- Uncertain index state no longer triggers deletion, unreadable memories are not overwritten, and keystore saves survive crashes (#758, #812, #857)
- Search during reload asks you to retry, keyword fallback pages correctly, answer retrieval handles ranking and timeouts honestly, and citations with wrong or cut-off numbers stay unverified (#914, #930, #959, #974, #977, #979)
- Delegate applies every steering direction and resumes approval waits correctly; Stop stays pending until the Engine confirms, conversation deletion preserves unrelated drafts, and saved messages keep their order (#811, #899, #947, #954, #1048)
- A crashed Engine can restart from recovery without quitting the app, keeps its crash evidence across restarts, and leaves indexing startable after a kill (#822, #826, #1025)
- Open file and Show in folder work in installed builds; Windows High Contrast remains usable, and help and Report a problem lead to current guidance (#776, #792, #924, #986)
- Credentials go only to a proven Engine, runtime and encryption folders are owner-only, and tokenizer tracking requests and Google Fonts preconnects stop (#799, #827, #831, #838, #873)

#### Known Issues
- Chat requires a supported NVIDIA GPU; it has no CPU fallback. Chat on a Windows GPU is not tested in this release (#1030, #1053)
- Linux chat needs published NVIDIA GPU packs and a registry update; the shipped registry contains no Linux packs, so Install AI currently offers CPU search only (#1030, #1044)

## [0.3.0] - 2026-09-07

### Added
- Agent tools can read the documents they find, page by page, within a declared path scope, and say so honestly when a file is outside the index (#566)
- Agent tools run behind a consent boundary: a risk ceiling per grant, argument-level scope, and undo that cannot reach outside the run (#581)
- The model is only offered tools that actually exist and can run, from one authority (#584)
- Every source in an answer shows how it arrived — retrieved by search or opened directly — with a provenance header on the evidence panel (#504, #553)
- Citations are checked against the literal passage they point at, and the answer reports how much of it sources actually cover (#466, #473)
- Search tool cards show, behind one disclosure, what was searched and what came back (#570)
- A run that stops at its ceiling says so, and elided steps are marked instead of silently dropped (#576)
- Conversations get a server-side title, and renaming one sticks everywhere it appears (#461)
- Reasoning is kept with the conversation, so reopening a past chat shows what the model thought (#492)
- A chat or agent run still in progress can be found and re-attached after a reload or a dropped connection (#478, #479)
- Documents keep a stable identity across re-extraction, rename, delete-and-reindex and a full index rebuild (#645, #660)
- Indexing shows whether enrichment actually finished, and "force reindex" really reindexes (#432)
- A per-stage `enrichment.incomplete` health condition surfaces enrichment that was silently lost (#437)
- Search results say when relevance re-ranking ran out of time, instead of quietly returning unranked order (#510)
- Installing acquires models component by component, so search is usable before the whole download lands (#483)
- Help can copy a privacy-bounded diagnostic summary to paste into a bug report (#634)
- The MCP server answers `GET /mcp` with 405 per spec and relays `facetsTruncated`, so an external agent cannot read partial facet counts as exhaustive (#430, #420)

### Changed
- The model now reasons before answering by default, within a bounded 512-token budget (#464)
- Index rebuilds run blue/green and only swap in once complete, and the "reindex required" check no longer fires on changes that cannot affect the index (#620)
- Search treats every language the same: the English stop-word skip is replaced by a document-frequency rule computed from your own index (#646)
- The chat context budget is derived from the loaded model's real context window instead of fixed numbers (#596, #599, #603)
- Indexing yields to your searches instead of pausing in fixed blocks, and extraction runs in a persistent process pool (#595, #598, #602)
- Chat prose reads better: one shared markdown renderer, a typography ramp, real separators and clearer nesting (#489, #572)
- Citation marks are muted in sourced answers, anchored literally, and coloured by a tier that means something (#569)
- "Detailed mode" and "Search" are named consistently across the app (#678)
- Command-line and environment settings outrank a stored in-app value again for server path, exclude patterns and GPU layers (#601)

### Fixed
- An optional reranker no longer degrades retrieval, and in-place rebuilds are visible in readiness (#424)
- Facet results stop lying: non-facetable fields are absent rather than empty, and truncation after a failed scan is reported truthfully (#423)
- Re-ranking preserves the number of results it was given (#419)
- Ask and RAG honor the collection you scoped them to (#421)
- `query_syntax=lucene` is honored on multi-leg search (#425)
- Readiness carries real staleness, including on composite conditions (#422, #433)
- The first scan of a watched folder carries its collection label (#427)
- Truthful tooltip text, a reachable New chat control and real session timestamps (#418)
- A transient worker startup timeout no longer bricks the app, and worker boot recovery has one authority and a bounded budget (#439, #512)
- The file watcher records a file caught mid-write as unknown size rather than zero (#612)
- Embedding compatibility is resolved before ingest instead of after (#470)
- Text files whose first bytes happened to match a binary signature were indexed as empty (#459)
- A streaming answer could wedge part-way through (#476)
- The delegate tier discarded the model's tool call, so it could not run at all (#586)
- Selecting a citation no longer hides that it is weakly supported (#460)
- References the model invents but nothing supports are stripped from the prose (#578)
- A citation matcher that never ran no longer produces a verdict (#548)
- The sources an agent run read survive cancellation, error and the iteration ceiling (#551)
- An interrupted agent run is reconciled on restart instead of left dangling (#465)
- Failure details survive into the UI and MCP responses instead of being flattened away (#688)
- The failed-files drawer lays out correctly, its retry chips stay reachable, and its data contract has a parse boundary (#616, #614)
- The updater's release sequence is derived from published releases, so renaming the build workflow can no longer permanently block updates (#497)
- An agent request that selects tools the index cannot offer yet says which tools are withheld and why, instead of a bare "no tools available"
- Tool failures during an index component recovery reach the model as "the index is restarting, retry shortly" instead of a raw internal error, and the failure class is carried on the wire
- The failed-files drawer shows the real scan id of a failed job instead of a placeholder, and the drawer now displays it per row (#702)
- Settings labels no longer render as raw `settings.*` keys when the window boots while the backend is restarting, or after an upgrade with a cached catalog (#702)
- A failed-file health event reads as a sentence naming the file and the error, instead of an unfilled template and an attribute dump (#702)
- The notification shown when a folder is added during an index rebuild uses plain words and a relative time, not an internal condition id and a raw clock (#702)
- The chat transcript follows its newest turn, so an answer to a long conversation no longer lands out of sight below the previous card; an answer that arrives only in the completion event is kept, and a dispatch that produced nothing says so instead of clearing the prompt silently (#703)
- A failed file re-queued by the periodic folder sync keeps its scan id and collection, so the failed-files drawer keeps showing which scan it belongs to (#703)
- A clean Install AI reports fully installed: development-only packages no longer count as skipped, and a skipped package now says why (`skipCause`) instead of always blaming the hardware (#703)
- A reopened Documents answer keeps its "Based on your documents" frame after a restart instead of being relabelled as a model-only answer (#703)
- Undoing an agent file operation no longer garbles non-ASCII text in the response; every JSON response now declares UTF-8
- An agent transcript written while the index was restarting is indexed once the index is back, instead of being skipped forever

### Security
- The per-boot mutation token fails closed, per ADR-0046 (#597)
- The MCP endpoint validates the `Origin` header, closing the DNS-rebinding gap in Streamable HTTP (#426)
- File-acting agent tools are bounded by a consent lattice — a risk ceiling per grant, argument scope, and undo contained to the run (#581)
- Undoing an agent operation now passes the same trust gate as the operation itself, so a reversal can no longer run with less consent than the forward action

#### Known Issues

- Updating in place from 0.2.0 has not been exercised end to end; if an in-app update fails, install 0.3.0 with the installer. Tracked in [`617-in-place-app-update-mechanism.md`](docs/tempdocs/617-in-place-app-update-mechanism.md) §9.
- Model download shows one overall progress bar, not per-component progress with a rate or an ETA. Tracked in [`840-model-download-restructure.md`](docs/tempdocs/840-model-download-restructure.md).
- A quick answer with reasoning on can come back empty, and reloads as an empty bubble with only a reasoning block. Tracked in [`845-rag-budget-and-prompt-scope.md`](docs/tempdocs/845-rag-budget-and-prompt-scope.md) and [`848-reasoning-persistence.md`](docs/tempdocs/848-reasoning-persistence.md).
- Documents ingested from outside your configured folders cannot be removed by the normal cleanup paths. Tracked in [`875-agent-tool-consent-boundary.md`](docs/tempdocs/875-agent-tool-consent-boundary.md).
- An agent task stops after 10 tool iterations, so a long multi-document task can be cut short. Tracked in [`868-agent-tool-capabilities.md`](docs/tempdocs/868-agent-tool-capabilities.md).

## [0.2.0] - 2026-08-13

### Added
- Public open-source release of JustSearch (Apache-2.0): local-first hybrid search; an optional on-device LLM (cited Q&A, chat, summarize/extract, and a consent-gated file-acting agent); and a production MCP server so external AI agents (Claude Code, Cursor, Claude Desktop) can drive search and retrieval.

<!--
Going forward, group entries under a version heading using these categories
(Keep a Changelog): Added, Changed, Deprecated, Removed, Fixed, Security.

## [2.0.0-alpha.NN] - YYYY-MM-DD
### Added / Changed / Fixed / Security
-->
