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

## [0.3.6] - 2026-11-18

#### Before you update from 0.3.0

- **0.3.0 cannot update in the app. Install 0.3.6 over your existing installation to keep your data.** (#993, #1053, #1054)
- **A change in how the app extracts text triggers one re-read of eligible files in watched folders as they're checked. Libraries with many scans may take longer, since text recognition took about twice as long per page in testing.** (#1209, #1229, #1231)
- **Re-import old one-off folders to refresh their extracted text.** (#1231, #1236)
- **Keep a copy of your app data before updating if you may need to go back to 0.3.2 or 0.3.0, and restore that copy before starting the older version.** Those versions cannot use the upgraded index and databases (#1109, #1128, #1150)
- **Update the JustSearch MCP extension in Claude Desktop if you use the 0.3.0 bridge. If you set `JUSTSEARCH_API_PORT` for it, remove that setting so the bridge finds the app by itself. If you call the API directly, update your client to use the Engine's per-start session token and replace calls to retired endpoints.** (#727, #775, #799, #825)
- **Writing settings through the packaged app's API also requires the app window's credential.** The old REST bridge can no longer add files to the index, and MCP clients can still request approval to add files (#727, #1107)
- **Saved view bookmarks are no longer accessible from the app.** Search still lets you pin conversations (#1134)
- **Automatic CPU reranking and SPLADE indexing are now off by default.** Explicit choices are kept, and SPLADE model files stay installed or available to download (#1162, #1182)
- **On first start, a cleanup begins that removes protected files from Search, including previously indexed credential files, files in hidden subfolders below a watched folder (unless you watch that hidden folder directly), and every `.key` or `.asc` file, including Keynote documents.** They can stay visible in Search until the cleanup finishes; an incomplete cleanup runs again on a later start. The files stay on disk (#1165)

### Added
- Export a Search conversation by copying or saving Markdown or JSON, keeping settled answers, citations, sources and turn statuses (#1059)
- The first-folder screen offers Add a folder and shortcuts to Documents, Desktop or Downloads. The field gets focus when Search appears, and that window shows a count from the first searchable file while the folder is being read (#1188, #1233, #1248, #1264, #1278)
- A startup frame shows while the app loads, with Reload and Copy report available if the interface cannot start (#1192)
- Move the desktop window by dragging its top area, double-click to maximize or restore, and use minimize, maximize and close controls even during startup or recovery; close hides to the tray, and tray Quit exits (#1194)

### Changed
- One top bar switches between Search, Library and AI Brain, with Settings, System and Help & shortcuts in its menu, a small green status dot after setup when everything is quiet, and text for running work, problems and requests (#1123, #1134, #1233, #1243, #1263, #1264, #1278)
- The status panel groups reading and AI downloads into one row each, lets you pause reading, and keeps requests and failures visible. A folder's searchable notice appears in the window that saw it become searchable and clears on reload (#1123, #1243, #1272, #1278, #1282)
- Desktop notifications can alert you in the background to waiting requests, work failures and completions of work that took at least a minute. Choose Off, On or On with sound in Settings (#1161)
- Clicking a notification shows the item if it is still available. Some Linux desktops may signal for attention while leaving JustSearch in the background (#1161)
- Library shows each folder's reading status, unreadable files and last check for changes, plus a count of files on disk when known, capped at 50,000+. Browse has a keyboard-navigable file tree sorted by name with sizes, dates and file actions (#1096, #1189, #1234, #1248, #1267, #1269, #1281, #1282)
- Skip rules save when you leave a field and reading hours save as you change them. Library asks before removing matching files from Search and lets you choose which rules to keep after changes in another window (#1189, #1271, #1277)
- Pending skip-rule saves survive a window reload, and Library offers Try again if it cannot tell whether a save went through (#1271)
- Read everything again appears beside Activity when you have watched folders and asks before re-reading all of them, including unchanged files (#1189, #1269, #1281, #1282)
- Read waiting files now lets files held by the current reading-hours or battery rule be read without changing your rules or releasing held AI work. The release ends when the queue empties, you pause reading, the rules change, a different hold takes effect, or the Engine restarts (#1273)
- AI Brain shows what works now and offers separate switches for Search by meaning, Best match first, Text in scans and images, and Answers, with a Memory tab when answers work and graphics memory shown only after AI is installed (#1100, #1233, #1239, #1268, #1278, #1283)
- Reading memory controls how much text the answer model can read at once (#1100, #1201, #1268, #1282)
- Pause answers while files are read holds new answers during large reading backlogs and lets searches and answers already underway continue. The still-open Search window sends its waiting question when the Engine lifts the pause, while API callers receive a refusal during the pause (#1137, #1268, #1282)
- Install AI shows each part's size, licence and terms before the initial download, with keyboard focus on Cancel. During the download, word search stays available and AI Brain shows progress by part (#1243, #1270, #1276, #1278, #1283)
- When you pause a download, the current file may finish. If the download stops, Try again starts another attempt and Cancel install dismisses the stopped view in that window (#1270, #1283)
- Best match first changes result ranking without restarting and keeps its downloaded files when switched off (#1118, #1182)
- Text in scans and images switches on or off without restarting, keeps downloaded files and previously read text, resumes waiting images when enabled, and explains when the answer model cannot read them (#1143, #1186)
- AI Brain lists downloaded parts and their sizes and offers Remove AI models when AI is installed and answers are unavailable, taking you to controls that ask before removing reranker files or answer and picture-model files when both features are off (#1160, #1187, #1276, #1283)
- Model removal keeps files still in use or that the app cannot safely identify, restores captured files after an interruption, and keeps removed files out of Repair until you confirm Download again (#1160, #1187)
- Open Settings from the top-bar menu to search seven sections and jump to controls, including controls in Library or AI Brain (#1116, #1206, #1208, #1263)
- Ctrl+K finds actions, settings, folders, loaded conversations and areas across the app. Plugin walkthroughs are in Help (#1214, #1263, #1264)
- Search uses a single-line field, and Clear returns to recent searches with their counts and times even if a search response is still on its way. The conversation sidebar appears once there's a conversation and hides its titles while history is locked (#1264, #1279, #1282)
- When Ask is offered in Search, choose it from the Search / Ask switch or the mode menu (#1283)
- Results show how the search ran, its time and match count, with filters such as Documents and Mail when there are different kinds to choose from. When nothing matches, use Clear filters if filters are active or Add a folder otherwise (#1265, #1279)
- A word search with no results suggests Search by meaning when the app confirms that AI is missing or still installing (#1265)
- When available, a separate count of word matches links to built-in Help, including when Help has the only matches. The main results, counts and filters cover your library (#1266)
- Search results and citations share a resizable details pane that docks on the right in wide windows and opens over the page in narrow ones. Press Space to preview a result and close the pane to return focus (#1167, #1265, #1274, #1280)
- The details pane has a Context tab in Ask, and installed plugins can add tabs (#1167)
- Opening a result marks it in the list and shows its path, Open file and Show in folder. The pane jumps to the first passage shown in the row if it can verify the position and text, and otherwise opens at the beginning without a passage mark (#1265, #1275, #1282, #1284)
- The result's menu offers Copy as Markdown, Copy as JSON and Why this result when available (#1265)
- Wide windows center each area's content, and the app uses its bundled Inter typeface and teal for active controls and primary actions (#1259, #1262, #1274, #1281)
- Displayed file sizes in Search and Browse, download sizes in AI Brain, and sizes in Health use the same units and rounding (#1276)
- System shows six plain-language Health rows with technical readouts under Diagnostics and an Activity feed where you can filter records, see their files and receipts, and use the available undo, restore, save and export actions (#1219, #1220, #1227)
- All activity in the status panel clears previous filters and focuses the feed. Activity uses the same wording in records and details, and looks up file names on the Engine that supplied the record (#1243, #1282)
- Most risky actions confirm beside the item, show failures and receipts in Activity, and offer undo where available. Deleting all local data still requires typed confirmation (#1115, #1139, #1147, #1149, #1155, #1174)
- Agent requests wait for review in the status panel, and high-risk requests use a separate confirmation dialog. Deny cancels the request across windows and reloads (#1115, #1139, #1147, #1149, #1155, #1174)
- Language filters use text from throughout a document, so an opening such as a site menu is less likely to decide the language. Code, structured data and text with too little prose stay unlabeled (#1098, #1223, #1231)
- Enabling SPLADE and restarting fills in unfinished work. With SPLADE off, a search that requests it uses other search methods and explains why (#1162)
- Background index updates keep completed SPLADE work. Re-reading can reuse model results when the text, passage layout and positions, and models still match, while Read everything again recomputes them (#1109, #1213)
- The owner-only runtime manifest supplies the session token for API clients, and a few public status reads work without it. Settings writes also require the latest settings witness and a fresh UUIDv7 operation key (#727, #799, #825)
- API clients can no longer fetch the session token from `/api/mcp/token` or read it from Engine output. Use `core.reconfigure` in place of `/api/inference/reload`, and `POST /api/engine/components/index/recover` in place of `POST /api/worker/restart` (#727, #825)
- Windows and Linux installers include the project licence and third-party notices beside the Engine, with maintainer attribution and corrected BSD attribution (#1080, #1130, #1178)

### Fixed
- Updating from inside the Windows app removes leftover Engine files that could prevent search from working after the update (#1290)
- If you left text in the Search field when you closed the app, pressing Enter after reopening it runs the search with your current filters (#1291)
- PDF extraction keeps word boundaries, rejoins words split by a line-end hyphen when the next line starts with a lowercase letter, and retains distinct page-edge text. More running headers can remain, and some letter-spaced headings may no longer match keyword searches (#1070, #1142, #1198, #1209)
- Tables keep wider rows and text before a content limit, and words in table cells and list items stay separate in Word, HTML and OpenDocument files. HTML table captions come before their tables (#1070, #1142, #1198, #1209, #1256, #1261)
- Office files with many slides, images or package parts can be indexed, and reading Office files leaves out hidden picture filenames and duplicate page previews. Word keeps footnote text without the markers (#1070, #1142, #1198, #1209, #1261)
- The table and Office fixes apply when files are read, including watched files re-read during the upgrade (#1231, #1256, #1261)
- Text recognition (OCR) keeps completed pages when time runs out, reports incomplete coverage, and reads sideways scans and JPEG photos with usable orientation information. It defaults to German and English when their language data is available (#1070, #1087, #1229)
- AI picture reading keeps fuller existing text when a transcription is incomplete and keeps working through other files when one picture is corrupt or too large. It resumes automatically after the picture-reading process crashes or cools down, and a crash leaves the current picture eligible to retry (#1087, #1229, #1255, #1260)
- Metadata filters apply to passage results, selections of 1,024 or more files work, and invalid relevance cursors are rejected. Search without reranking ranks to a stable depth before cutting the page; reranking skips overlong queries with a reason and also considers leading candidates' document openings (#1118, #1164, #1180, #1230)
- New keeps the previous conversation's draft, selected files and still-arriving answer; reopened unfinished answers are checked against saved outcomes instead of being resent or prematurely marked failed. Retry becomes available after its wait even if its timer wakes early (#1076, #1082, #1211)
- Indexing detects same-size edits with restored timestamps on Linux while running, checks for changes during reading, and keeps forced re-indexing across restarts; sync repairs missing or damaged passages without repeatedly restarting unchanged work during upgrades. Watched files whose extraction failed get a new attempt after extraction limits or policy change (#1086, #1111, #1122, #1125, #1138, #1204)
- Interrupted queue work and folder initialization retry after recoverable failures. A later failure no longer hides earlier accepted index updates or makes a saved action appear to fail (#1072, #1150, #1171, #1176, #1181)
- After a crash or restart, unfinished imports resume on the accepted plan when saved approval still covers the source. For one-time approvals, Resume in Library asks again, and Cancel takes priority over Resume (#1150, #1232)
- If a watched folder's drive is unavailable or replaced, its indexed files are kept. Deletions and interrupted renames recover correctly, and removed folders and collections stay removed (#1083, #1097, #1148, #1236)
- Folder checks stay within their watched scope, accept filenames with consecutive dots, recover lost watchers, and clear coverage warnings after a complete check. Removing already-read matches to skip rules also checks files outside watched folders (#1083, #1097, #1148, #1236)
- One-off imports re-read changed files only while saved approval still covers the source. Deletions and skip rules still apply, including for old imports and revoked approvals, while adding new files or bringing back skipped files requires another import (#1236)
- One-off imports keep their indexed files if the drive is unplugged, replaced or mounted over, including when a different drive is mounted inside the import (#1236)
- Background enrichment advances across long documents and large backlogs, shares time between stages, lets CPU work continue during GPU holds, and stops promptly when indexing is paused. Completed work is kept, replaced models cannot publish stale results, and storage or model failures back off without charging unrelated files retries (#1073, #1095, #1117, #1127, #1129, #1136, #1144, #1151, #1166)
- Meaning search correctly encodes long documents, entity recognition covers the whole text, keeps short names and retries failures, and changes to a model's tokenizer or other files are detected. Person, place and organization name grouping covers the whole library, follows edits and deletions, and rebuilds existing groups once on upgrade, changing group IDs (#1090, #1092)
- Engine shutdown drains native inference before exiting on Linux, failed stops and resets report failure, and Windows restarts recognize an Engine that has already exited. Index switches keep models that are still starting, ready answer models are noticed sooner, and helpers that exit immediately retain their real exit details (#1061, #1081, #1099, #1193, #1205, #1218)
- Losing the Engine shows a recovery notice with Restart the engine and Copy report, and the copied report counts Engine exits. Health stays reachable through repeated crashes, and keyboard focus returns after recovery, minimizing or closing to the tray (#1197, #1210, #1225, #1252)
- Right-clicking the desktop window shows the usual editing menu in text fields and no browser menu around the rest of the app (#1252)
- The Engine avoids an unnecessary restart when an index migration is requested just after startup (#1253)
- Built-in Help stays available through a search-index rebuild, including one that resumes after a restart. If Help cannot be read, the app keeps serving the existing index (#1251)
- Health marks values as last known after a status read fails and counts your files separately from built-in Help. When the reading worker has not reported counts or AI progress, the app shows counts as unknown and MCP omits unavailable values (#1234, #1248)
- Health, Logs and Library’s Browse view keep their content reachable in small windows, and the packaged app displays dark-theme dropdowns, progress bars, folder indentation, swatches and user themes correctly (#1234, #1257, #1282)
- Indexing progress and search readiness reflect stored results, failed or unreadable enrichment and unknown counts; enrichment outcomes report what actually completed. AI Brain keeps polling after a hung status read, and recovered queue operations clear their matching health failures (#1084, #1104, #1121, #1179)
- A fresh install offers Install AI and reserves warnings for AI downloads that fail or stop (#1233, #1239, #1270, #1283)
- A temporarily unreadable settings file is reported as unavailable instead of being moved aside and replaced with defaults, and startup preserves settings written by a newer version (#1188, #1221)
- Settings reopened at launch, after the app quit with it open, is a real dialog again above the window controls and closes with its X and with Escape (#1238)
- Dates and times display correctly in the desktop app, and keyboard focus rings remain visible across built-in palettes, including Sepia Focus and High Vis (#1199, #1202)
- The plugin authoring SDK supplies current, self-contained host types and loads through both its declared ESM and CommonJS entry points (#1177)

### Security
- Indexing skips recognized credential files and folders, the app's data folder and dot-prefixed subfolders of watched folders. You can still watch a hidden folder directly or add an ordinary file from one, and the first-start cleanup retries until the protected entries are gone from Search (#1165)
- MCP clients cannot access the built-in assistant's history; in the packaged app, HTTP access to it needs the app window's credential; headless and development HTTP access keeps its existing authority (#1165)
- The desktop app's updater drops unused ZIP extraction support and its vulnerable dependency; that code was not reachable (#1088)
- Packaged-app approvals, grants, plugin trust and settings changes require the app window's credential. The built-in assistant and links that add folders now ask first under default permissions (#1107, #1115)
- The app leaves index directories alone unless it can prove ownership and explains when an unrecognized directory blocks search. Assistant file moves refuse existing destinations, including dangling links, and keep a source that changed during a copy instead of deleting it (#1108, #1185)

#### Known Issues
- Saved view bookmarks remain on disk. The Ctrl+D bookmark instruction in bundled Help is out of date (#1134)
- AI answers require a supported NVIDIA graphics card. On Linux, Install AI offers CPU search only because the bundled model list has no Linux GPU packs (#1030, #1044)
- After an AI download stops, Try again can download parts without showing their sizes and terms again if the computer's hardware or settings changed in between (#1270, #1283)
- On a slow Engine, Activate downloaded models can briefly show a duplicate confirmation while activation proceeds (#1250)
- The Other files filter can include mail. Some `.msg` and `.mbox` files are labeled Mail in results and can be missing from the Mail filter (#1279)
- Drive protection for watched folders applies at the watched root and leaves child mounts unprotected. One-off imports miss edits that leave the file's size and modification time unchanged (#1236)
- At the end of Install AI, the Engine can restart once on its own, making search briefly unavailable until it comes back (#727)
- On Windows, adding the suggested Documents folder can show “Some subfolders couldn't be read” and “Problems right now” because JustSearch can't read Windows' My Music, My Pictures and My Videos links inside Documents. Search still works for files the app can read (#1192)

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
