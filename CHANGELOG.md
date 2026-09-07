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

### Added
<!-- 852 promotion: these bullets assume the Search v3 cutover PR lands before the tag; the sv3 agent confirms or trims them -->
- A rebuilt chat window, with one chronological timeline per answer that shows the model's reasoning interleaved with the tools it used (#533)
- A floating composer that stays over the transcript instead of being pinned below it (#529)
- Chat width presets — narrow, default and wide (#573)
- Visible control over what the model is given: a context floor, a compact view, an editable summary and per-item exclusions (#503)
- Branch and version pager on any turn, with edit, retry and cascade delete (#505)
- A settings window you can open from anywhere, with the same layout wherever you open it (#527)
- Keyboard navigation through the steps of a run (#516)
- A health and activity panel inside the chat window (#514)

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
- Tool failures during a Worker restart reach the model as "the index is restarting, retry shortly" instead of a raw internal error, and the failure class is carried on the wire
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
