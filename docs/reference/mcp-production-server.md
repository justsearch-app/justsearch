---
title: Production MCP Server Reference
type: reference
status: stable
description: "MCP server for connecting AI agents to a running JustSearch instance via Streamable HTTP."
---

# Production MCP Server Reference

JustSearch exposes an MCP server at `POST /mcp` on its local API.
External AI tools (Claude Desktop, Cursor, VS Code Copilot, etc.)
connect to it and get access to the local knowledge base — search,
retrieve context, browse folders, ingest files, and check status.

**Source of truth:** `modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java`

## Which port?

The MCP endpoint lives on the same loopback HTTP API as everything else, so "which port is the
MCP server on?" is exactly "which port is the API on?":

- **Default: `8080`.** The API port resolves through `justsearch.api.port` /
  `JUSTSEARCH_API_PORT` (`modules/configuration/.../EnvRegistry.java`); when nothing sets it,
  the built-in default is `8080` (`ResolvedConfigBuilder.buildPorts()`). The packaged desktop
  app sets no port override, so an installed app binds `http://127.0.0.1:8080`.
- **Ephemeral fallback.** If the configured port is already in use, the server logs a warning
  and rebinds on a random free port instead of failing
  (`modules/ui/src/main/java/io/justsearch/ui/api/LocalApiServer.java` — bind-failure fallback).
  Setting the port to `0` requests an ephemeral port explicitly.
- **Pin it:** set the `JUSTSEARCH_API_PORT` environment variable (or the
  `-Djustsearch.api.port=` system property for source runs) before launching. Note that a pin
  is a *preference*: if that port is taken, the ephemeral fallback above still applies.
- **Discover the actual port:** the backend writes it as `head.apiPort` in the runtime manifest,
  `<data dir>\runtime\manifest.json` — for the installed desktop app that is
  `%APPDATA%\io.justsearch.shell\runtime\manifest.json`. It also prints
  `JUSTSEARCH_API_PORT=<port>` to stdout (captured in `logs\headless-backend.log`) and serves
  `GET /api/health` once up. (Gradle dev tasks like `runHeadless`/`devAll` default to `33221`,
  which is why older docs and scripts mention that number — the installed app does not use it.)

## Setup

### Claude Desktop one-click (MCPB) — available from the next release

An MCPB bundle (`justsearch-mcp.mcpb`, sources in
[`packaging/mcpb/`](../../packaging/mcpb/README.md)) will be attached to
JustSearch releases **starting with the next release** — it is not on any
published release yet, and it needs an app build that serves `POST /mcp`,
which the v0.1.0 release may predate. Once shipped: download the `.mcpb` from
the release page and open it with Claude Desktop (Settings → Extensions) —
one click, no JSON editing. The bundle is a thin local stdio bridge to the
running app's `/mcp` endpoint; it handles port discovery via the runtime
manifest automatically. Until then, use the connector flow below.

### Claude Desktop in ~2 minutes, starting from "launch the app"

1. **Launch JustSearch** (Start menu). Wait for the window to load — the API is up when
   `http://127.0.0.1:8080/api/health` answers in a browser. If it doesn't, read the actual
   port (`head.apiPort`) from `%APPDATA%\io.justsearch.shell\runtime\manifest.json` and use that below.
2. **Claude Desktop → Settings → Connectors → Add custom connector**, URL:

   ```text
   http://127.0.0.1:8080/mcp
   ```

3. **Done.** Ask Claude something about your indexed files; it will call `justsearch_answer` /
   `justsearch_search`. (First useful answers require having pointed JustSearch at a folder and
   letting it finish indexing.)

If your Claude Desktop version has no Connectors UI, bridge stdio→HTTP with `mcp-remote`
(needs Node.js) in `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "justsearch": {
      "command": "npx",
      "args": ["mcp-remote", "http://127.0.0.1:8080/mcp"]
    }
  }
}
```

### Cursor / Windsurf / VS Code

Add to `.cursor/mcp.json` or equivalent:

```json
{
  "mcpServers": {
    "justsearch": {
      "url": "http://127.0.0.1:8080/mcp"
    }
  }
}
```

### Claude Code

```bash
claude mcp add justsearch --transport http http://127.0.0.1:8080/mcp
```

In all three: replace `8080` with the `head.apiPort` value from
`%APPDATA%\io.justsearch.shell\runtime\manifest.json` if `8080` was taken on your machine
(see [Which port?](#which-port)).

Every flow above needs a build that serves `POST /mcp`; the v0.1.0 installer release may predate it.
If a client reports no tools, probe the endpoint before debugging the client — it should answer with
the six tools, and a 404 means the running build has no MCP endpoint (use a newer release or a
from-source build):

```bash
curl -sS -X POST http://127.0.0.1:8080/mcp -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

### Claude Code: headless / non-interactive approval

Project-scope MCP servers in Claude Code (added with `claude mcp add` at the default project
scope, or picked up from a shared `.mcp.json`) require a one-time **interactive** approval per
project directory before Claude Code will actually connect to them. In a non-interactive context
— `claude -p`, or an Agent SDK session — launched from a directory where the server has never
been approved, the server is silently dropped: there is no error, it just does not connect.
`claude mcp list` shows it as **"Pending approval"** rather than connected.

Remedies:

- Run `claude` interactively once in that project directory and approve the server when
  prompted; subsequent non-interactive runs from the same directory pick it up.
- Or skip the project-scope approval flow entirely: pass the server programmatically via SDK
  options (Agent SDK), or via `--mcp-config <file> --strict-mcp-config` on the CLI.

## Transport

Streamable HTTP on the existing Javalin server (loopback-only,
`127.0.0.1`). No separate process — the MCP endpoint runs in the
same JVM as the Head process. No Node.js required.

**Transport security.** The spec's Streamable-HTTP security clause ("Servers **MUST** validate the
`Origin` header on all incoming connections to prevent DNS rebinding attacks... If the `Origin`
header is present and invalid, servers **MUST** respond with HTTP 403 Forbidden") is enforced on
every method served at `/mcp` — **and on `GET /api/mcp/token`**, the session-token bootstrap — by
`ApiSecurityFilters.setupMcpOriginValidation`. The rules:

| `Origin` on the request | Outcome |
|---|---|
| absent | served — native MCP hosts (Claude Code, Claude Desktop, Cursor) are HTTP clients, not browsers, and send none |
| loopback (`http(s)://127.0.0.1`, `localhost`, `[::1]`, any port) | served |
| desktop shell (`tauri://localhost`, `http(s)://tauri.localhost`) | served |
| `null` (sandboxed/`file://` context) | **403** — no host to verify, and no MCP client produces it |
| anything else | **403**, JSON-RPC error body with no `id` (plain `{error, errorCode}` on the token route, which is not a JSON-RPC endpoint) |

The value is parsed as a URI and its **host component** compared for equality, so a lookalike such
as `http://127.0.0.1.evil.com` is rejected. This composes with — and does not replace — the
API-wide `Host`-header allowlist and the loopback bind; see
[`security/threat-model.md`](security/threat-model.md).

**Methods on `/mcp`.** The spec requires the single endpoint to support both `POST` and `GET`,
answering a `GET` with either a `text/event-stream` SSE stream or **405 Method Not Allowed** when
the server offers none:

| Method | Response |
|---|---|
| `POST` | JSON-RPC request/response — the whole protocol surface |
| `DELETE` | `204`, ends the session named by `Mcp-Session-Id` |
| `GET` | **405** with `Allow: POST, DELETE, OPTIONS` and a JSON-RPC error body (`MCP_METHOD_NOT_ALLOWED`) |
| `OPTIONS` | CORS preflight, served by the API-wide catch-all |

JustSearch serves **no SSE stream** at `/mcp`: every response is the direct reply to a `POST`, and
nothing pushes unsolicited JSON-RPC to a connected client. Adding one would need a per-session push
channel plus the spec's resumability machinery (`Last-Event-ID`, event ids) — a **product**
decision, not a conformance one. 405 is the spec's own answer for a server without such a stream,
so **the GET/SSE clause is now conformant**. (Before this, `GET /mcp` returned 404 — live-verified
2026-08-12 — which told a probing client nothing about the endpoint.)

That is one clause, not the whole transport. **Known remaining gap:** the spec's *Protocol Version
Header* section requires the server to validate the `MCP-Protocol-Version` **request** header on
every non-`initialize` request and answer `400 Bad Request` on an unsupported or invalid value.
`McpProtocolHandler` only ever *emits* a version — at `initialize`
(`McpProtocolHandler.java:37,217`) — and never reads that request header, so an unsupported version
is silently accepted rather than rejected. Tracked; not addressed by this change.

Protocol version: `2025-11-25`. Capabilities: tools, resources,
prompts. Curated tool-surface version (single-sourced from
`McpContractVersions.TOOL_SURFACE_VERSION`, reported as
`serverInfo._meta["io.justsearch/toolSurfaceVersion"]` and as the runtime
manifest's `mcpToolSurfaceVersion`): `0.7.0`. MCP `serverInfo.version` is the
**build** version (bound to `EnvRegistry.APP_VERSION`) — a host that logs or
gates on server version must see this build's number, not the tool surface's.

## Available Tools (6, position-bias ordered)

| # | Tool | Backend | Purpose |
|---|------|---------|---------|
| 1 | `justsearch_answer` | `DocumentService.retrieveContext()` | RAG retrieval — assembled passages with source attribution. Primary QA tool. Position-biased first. |
| 2 | `justsearch_search` | `KnowledgeHttpApiAdapter.search()` | Exploratory search with facets, filters. For discovery and browsing. |
| 3 | `justsearch_browse` | `core.browse-folders` Operation | Folder structure exploration. |
| 4 | `justsearch_ingest` | `core.ingest-files` Operation | File indexing. The only mutating tool — see Trust Model below. |
| 5 | `justsearch_status` | `KnowledgeHttpApiAdapter.status()` | Index health + enrichment coverage. |
| 6 | `justsearch_runtime_manifest` | `RuntimeManifestPublisher` | Redacted runtime manifest (identity, lifecycle, AI runtime state) for identity-aware caching. |

All 6 tools validate their arguments against a declared JSON Schema at the MCP boundary before
dispatch (tempdoc 655) — a malformed call gets a clean tool error rather than an internal cast
failure.

### Tool lifecycle extension

MCP has no standard tool-deprecation fields, so JustSearch advertises the versioned experimental
capability `capabilities.experimental["io.justsearch/tool-lifecycle"] = {"version":"1.0"}`.
Lifecycle data comes from a closed, validated catalog beside the six tool declarations: every
catalog row must resolve to exactly one live tool, and duplicate or orphaned rows fail fast.

When a tool is deprecated, `tools/list` adds only namespaced top-level `_meta` keys:
`io.justsearch/deprecated`, `io.justsearch/deprecatedSince`, optional
`io.justsearch/sunsetAt`, and `io.justsearch/replacement`. The standard `annotations` object is left
unchanged. A short deprecation sentence is also prepended to the description for clients that ignore
extensions. The production catalog is currently empty, so no shipped tool emits this metadata and
the curated tool-surface version is `0.7.0` after the additive failure metadata below.

## Response shape (tempdoc 725)

The human-readable `content` text on `justsearch_search` and `justsearch_answer` carries several
descriptive lines beyond the raw results, so an agent can judge a response without a second call:

- **`justsearch_answer` evidence-pack header** — the first line states the passage count, the
  distinct-document count, the retrieval mode, and that the pack is retrieved evidence, not a
  synthesized answer (plus a truncation note when the context was cut to fit token limits).
- **`justsearch_search` match lines** — each hit carries a `Matched:` line naming the distinctive
  terms that drove the match (or a `Match basis: semantic similarity` line when no term overlap
  was distinctive), so an agent can tell *why* a hit matched without opening the file.
- **Degradation and coverage lines** — a once-per-response note when semantic ranking degraded or
  fell back, and a "showing N of M" line when a response was capped below the total hit count.

**`response_format`** (optional on both tools; default `"detailed"`, which includes preview
snippets and full evidence passages). `"concise"` trims the **human-readable text block only** —
`justsearch_search` text omits the preview line, and `justsearch_answer` text caps at the 3
highest-ranked passages; the coverage, match, and header lines are kept in both modes. It does not
change `structuredContent`, so a client that consumes the structured tier (the common case) sees no
size difference (tempdoc 770: measured zero reduction across 336 opt-ins).

### What these tools do and do not do (multi-step lookups)

`justsearch_answer` performs retrieval, not synthesis: it returns relevant passages with source
attribution, and the calling agent composes the answer. Questions whose answer spans a chain of
documents (for example, an entity named in one document whose details live in another) require
the calling agent to issue a follow-up retrieval for each step of the chain — the tools do not
traverse entity chains on the agent's behalf. In measurement, completion of such multi-step
lookups varies substantially with the calling agent's model tier; the response furniture above
(match lines, headers, coverage notes) makes each step's result legible but does not remove the
need for the follow-up step itself.

### Delivery tiers (tempdoc 735)

Every `justsearch_search`/`justsearch_answer` response is authored in two tiers — the
human-readable `content` text and the machine-readable `structuredContent` — and the product's
contract is that they carry **equivalent information**. Which tier a connected client actually
forwards to its model is a **client-specific behavior that is not documented upstream**:
observed 2026-07-14 with Claude Code CLI 2.1.209, the client forwards the serialized
`structuredContent` JSON when present and the text tier otherwise (this client behavior has
changed without notice before — see anthropics/claude-code issue #9962). Agents connected
through different clients may therefore receive different representations of the same response;
nothing here should be read as a guarantee of which tier any client delivers.

## Structured retrieval evidence (tempdoc 658)

`justsearch_search` and `justsearch_answer` return a machine-readable `structuredContent` object
alongside the human-readable `content` text, so an agent can inspect *why* it got these results —
not just read them. This is a projection of the same canonical records the desktop UI and REST API
already render (`SearchTrace`, `ContextCitation`); it introduces no new authority
(`governance/execution-surfaces.v1.json`).

- **`justsearch_search` → `structuredContent`**: the query-level `searchTrace` (effective mode,
  decision kind, degradation reason codes, and the per-stage list with status/reason/timing) plus a
  `results` list carrying, per hit, its `path`, `title`, `score`, `matchedTerms`/`matchedFields`, and
  `excerpts` — plus `id`, emitted only when it differs from `path` (for filesystem sources the worker
  doc-id *is* the path, so shipping both would be a verbatim duplicate; `path` is the one kept
  because it is the name the agent can act on, and `id` still ships for source classes whose doc-id
  is not a path).
  The query-level `searchTrace` is always present. The **per-hit ranking-provenance tier** —
  `trace` (which legs placed the hit, at what rank/score), `legScores` (sparse/dense/splade/fused),
  and the numeric detail sub-map inside `trace` — is included only when the call sets `detail: true`
  (tempdoc 770: measured at 19.9% of the delivered payload while carrying no document content).
  `excerpts` are never gated — they are the only document text the tool returns.
- **`justsearch_answer` → `structuredContent`**: the `citations` list (per-chunk provenance —
  `parentDocId`, char/line span, heading, score, excerpt) plus `quality` (chunks found/used, retrieval
  mode + reason code, and the CRAG-style confidence signals: coverage, best-chunk score, score gap,
  chunks considered/included, truncation). Citations are empty on the full-document fallback path.

**Tier equivalence (tempdoc 735 W6).** Both tools' `structuredContent` also carry `hints` (the
same progressive-disclosure hint strings the text tier's `Hints:` block / `Hint:` lines render),
`coverage`
(`justsearch_search`: `totalHits`/`shown`/`tookMs`; `justsearch_answer`: `passages`/`documents`),
and `truncated` (`justsearch_search`: the response was capped below `totalHits`;
`justsearch_answer`: `contextTruncated`). `justsearch_search` also carries `facets` (the same
facet-value facts its text tier renders); `justsearch_answer` does **not** — tempdoc 770 §F.5
removed the second full hybrid search it fired per call purely to build that sidecar, and the
answer path has no other facet source. These fields close the gap a structured-preferring
client would otherwise hit: before this increment, hints/facets/coverage/truncation facts existed
in the `content` text only, so a client that delivers `structuredContent` instead of text (see
Delivery tiers, above) never received them. Full evidence-passage parity (the text tier's ~10KB
answer-pack passages vs. the ~2.7KB `citations` excerpts) is explicitly out of scope for this
increment — metadata parity only.

**Data exposure.** MCP tool responses are **not redacted** — path redaction applies to the diagnostics
*export* bundle (a shareable artifact), not to live tool output. `citations[].parentDocId` is the
document's identity (its absolute file path) and `excerpt` is the passage text; both are the user's own
local data returned to the user's own local agent. This is the same identity `justsearch_search`
already returns (its `path` field) and the desktop UI already shows. The `/mcp` endpoint is
loopback-only (binds `127.0.0.1`; Hard Invariant #2), so the *server* sends nothing off the machine.
That bounds the endpoint, not the client: an MCP client running a cloud-hosted model forwards these
tool results (paths + passages) to its model provider like any other tool output. The privacy claim is
"JustSearch does not egress your documents"; what a connected client does with a response is the
client's contract (see `threat-model.md` § What this model deliberately does not claim).

## Tool Selection

Use `justsearch_answer` first when the user asks a question about
indexed content. It retrieves relevant passages assembled with source
attribution — more efficient than searching and reading individually.

Use `justsearch_search` when the agent needs to discover what exists,
browse by source/category, or find specific files. When the matching
documents carry facetable fields, it returns top facet values for filter
discovery (a zero-hit query, or a corpus without those fields, returns
none). Pass `query_syntax: "lucene"` for exact-phrase or boolean queries.

Use `justsearch_browse` to explore the folder structure before
searching — especially useful when the agent doesn't know what's
indexed.

Use `justsearch_ingest` when the user wants new content indexed.

Use `justsearch_status` to check index health, enrichment coverage,
and document count before diagnosing empty results.

This same comparative guidance — when to prefer the index over reading
files directly — is delivered to the agent at connect time through the
MCP `initialize` response's optional `instructions` field, which
compatible clients (e.g. Claude Code) inject into the model's context.
It is advisory guidance, not a contract, and states honestly that
ordinary file tools are equally good for a small set of files or an
exact string/filename lookup.

## Progressive Disclosure

The MCP surface uses response-level hints instead of schema complexity.
Tools return contextual guidance at decision time:

- **Zero results** → "try broader terms or check justsearch_status"
- **Many results** → "use facet values as filters to narrow down"
- **Low enrichment** → "enrichment in progress — semantic search may be limited"
- **Facet values** → `justsearch_search` returns top sources and entities
  whenever the matching documents carry them, as filter values for the next
  call (a zero-hit query returns none)
- **Comparative hint** → after an `answer` that drew on more than one
  document, the response states factually how many distinct documents it
  assembled evidence from in a single call — surfacing the index's
  multi-document advantage at the moment the agent sees it worked, not only
  in the tool description (which agents read once and forget)

Some advanced parameters (doc_ids, entity filters) work when passed but
are NOT in the visible schema. This is intentional — eval data shows
making them visible degrades small-model accuracy (92% → 71%) without
increasing usage. Capable agents can use them by reading the description
carefully. `query_syntax` is the exception (tempdoc 770): the description
advertised it while the schema rejected nothing and the validator silently
dropped it, so agents believed they had enabled exact-phrase search and
got fuzzy hybrid instead. It is now a declared schema parameter threaded
through to the request, so the advertised behavior is the real behavior.

## MCP Prompts (3)

| Prompt | Arguments | Purpose |
|--------|-----------|---------|
| `search_files` | `topic` (string) | Search the knowledge base |
| `answer_question` | `question` (string) | Get an answer from indexed documents |
| `index_folder` | `path` (string) | Add a folder to the index |

Prompts expand with live system context (document count, enrichment
percentages) so the model has orientation before the user's query.

## MCP Resources

Four proposed URIs for agent orientation:

| URI | Content |
|-----|---------|
| `justsearch://index/summary` | Document count, enrichment coverage, readiness |
| `justsearch://index/roots` | Indexed folder paths |
| `justsearch://index/top-sources` | Top `meta_source` facet values |
| `justsearch://index/top-entities` | Top person/organization entity values |

Plus 9 catalog-driven resources (health events, indexing jobs, etc.)
for subscription support.

## Tool execution failures

Execution failures retain `isError: true` and a readable `content[].text` message.
The same known facts are available in `structuredContent`: `error` contains the
sanitized explanation, with optional `errorCode`, `errorClass` and `retryable`.
Exception paths use the existing API classification after removing asynchronous
completion wrappers; the underlying cause supplies both wording and failure facts.
An unclassified failure
omits classification fields; an operation result preserves its supplied code and
retry hint without inferring an absent class. Missing retryability is unknown,
not permission to retry. The text channel states any supplied classification and
retry hint, so clients consuming either delivery channel see consistent facts.

The `0.7.0` tool-surface version identifies this additive metadata and the removal
of blanket transience claims. It does not change tool names, arguments, the MCP
protocol version, or authorization. A confirmation-pending response continues to
use its existing approval guidance; it must not trigger automatic replay.

## Trust Model

MCP clients are registered as `SourceTier.UNTRUSTED` in the intent
substrate. The trust lattice gates `justsearch_ingest` (the one
mutating tool) behind `TYPED_CONFIRM`.

**Confirmation is resolved in the JustSearch app, not by the calling agent
(tempdoc 655).** When a gate fires, the tool call returns immediately —
it never blocks — with a message explaining that approval is now showing
in the JustSearch app; the agent does not need to retry the call. The
approval is the SAME `PendingAuthorizationStore` / capsule mechanism the
browser UI's own gated actions use (tempdoc 550), reached via a live SSE
announcement so the app can react even though it never made the
originating request. Once a human approves in the app, the server
completes the dispatch itself using the pending record's own stored
arguments — the browser never needs (and never receives) the full
argument payload for an MCP-originated request.

If the user grants "allow always" for `core.ingest-files` at the
`UNTRUSTED` source tier (via the approval dialog, or directly through
`POST /api/authorizations/grants`), future `justsearch_ingest` calls
succeed immediately with no prompt — the pre-existing durable-grant
mechanism already covers MCP callers, since `SourceTier` is
transport-agnostic.

## Legacy: Old TypeScript MCP Server

The previous TypeScript MCP server (`scripts/prod/justsearch-mcp/server.mjs`)
is **deprecated**. It ran as a separate Node.js process via stdio
transport with 4 tools. The Java MCP handler supersedes it with
better transport (Streamable HTTP, no separate process), more tools
(6 vs 4), and direct service-layer dispatch.

The old server remains in the codebase for reference — its tool
descriptions and data from a tool-interface-design eval (tempdoc 366)
informed the new handler's design. Remove after the new handler is
eval-validated (tempdoc 500 gate).
