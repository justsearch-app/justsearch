"""Deterministic route-mock fixtures for ui-shot / ui-check (tempdoc 615 §13 Move 1 / §16).

The §16 experiment proved a deterministic, zero-env-noise, byte-stable capture is
achievable by intercepting `/api/*` and serving SCHEMA-VALID fixtures — no backend,
no app-level demo-mode rebuild. This module promotes that proof into a reusable,
OPT-IN harness primitive (`install_fixtures`), enabled per-run via `--fixtures`.

Scope: the deterministic STRUCTURAL steps (a11y / layout / contrast facts of the
views). It is deliberately NOT for the AI-chain steps (streaming / summarize /
citation), which need a real model — those stay live (run WITHOUT `--fixtures`).

Two traps the experiment found, encoded here so they can't recur:
- The FE parse boundary is NON-fail-open: an empty `{}` is WORSE than a 502 — it
  fails the generated-schema parse and the shell never mounts. So boot-critical
  contracts get schema-valid bodies (the captured `__fixtures__/*-live.json` for
  status/search/settings; schema-valid catalogs for the registry endpoints). The
  operation catalog carries the two Help actions so its structural captures exercise
  the same projected controls as the live surface.
- A glob `**/api/**` over-matches the FE's own `/src/api/*.ts` Vite modules; the
  matcher MUST be a path predicate (`path == '/api' or startswith('/api/')`).
"""
from __future__ import annotations

import asyncio
import json
from copy import deepcopy
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlparse, unquote

from .agent_stream_fixture import DONE_RUN_BODY, sse_frame


# One legacy-token compatibility check uses the real public plugin construction path.
# This inert declaration ignores its registration host; it defines no elements or executable UI.
COMPAT_CHAT_SURFACE_ID = "vendor.capture-compat.legacy-main"
_COMPAT_CHAT_CONTRIBUTION = {
    "id": COMPAT_CHAT_SURFACE_ID,
    "mountTag": "jf-unified-chat-view",
    "labelKey": "capture-compat.surface.label",
    "descriptionKey": "capture-compat.surface.description",
    "audience": "USER",
    "placement": "DEEPLINK",
}
_COMPAT_CHAT_MANIFEST = {
    "id": "capture-compat", "version": "1.0.0",
    "displayName": "Capture compatibility fixture", "contractVersion": "1.1",
    "tagNamespace": "capture-compat",
    "capabilities": {"surfaces": [_COMPAT_CHAT_CONTRIBUTION]},
}
COMPAT_CHAT_PLUGIN_SOURCE = (
    "(" + json.dumps(_COMPAT_CHAT_MANIFEST)[:-1]
    + ", register: function() { return {surfaceContributions: [{contribution: "
    + json.dumps(_COMPAT_CHAT_CONTRIBUTION) + "}]}; }})"
)
COMPAT_CHAT_VERIFY_BODY = json.dumps({
    "verified": False, "identity": None,
    "reason": "Untrusted inert compatibility capture fixture; no approval granted",
})


def _find_fixtures_dir() -> Path:
    """Locate `modules/ui-web/src/api/__fixtures__` by walking up to the repo root
    (robust to the file's nesting depth — mirrors ui_measure._find_axe)."""
    here = Path(__file__).resolve()
    for parent in here.parents:
        cand = parent / "modules" / "ui-web" / "src" / "api" / "__fixtures__"
        if cand.exists():
            return cand
    raise FileNotFoundError("ui-web __fixtures__ directory not found from " + str(here))


_FIX_DIR = _find_fixtures_dir()


def _load(name: str) -> str:
    return (_FIX_DIR / name).read_text(encoding="utf-8")


# Captured, schema-valid live payloads for the boot-critical (non-fail-open) contracts.
_BODY_STATUS = _load("status-response-live.json")
_BODY_SEARCH = _load("search-response-live.json")
_BODY_SETTINGS = _load("settings-v2-live.json")


def _empty_catalog(primitive: str) -> str:
    """Minimal schema-valid EMPTY registry catalog (shape per types/registry.ts +
    types/diagnostic.ts). An empty `entries` is valid and content-free, so it cannot
    drift — only a schema-key change would, which the FE contract tests already catch."""
    return json.dumps({
        "schemaVersion": "1.0.0",
        "catalogVersion": 0,
        "namespace": "core",
        "primitive": primitive,
        "entries": [],
    })


def _help_operations_catalog_body() -> str:
    """Schema-valid operation rows needed by the Help structural capture.

    These mirror the two entries emitted by ``CoreOperationCatalog``. Keeping the
    fixture intentionally narrow makes ``help``/``help-narrow`` prove the projected
    controls without turning the deterministic harness into a second full catalog.
    """
    def entry(operation_id: str, result_properties: dict, *, inputs: dict) -> dict:
        return {
            "id": operation_id,
            "type": "operation",
            "presentation": {
                "labelKey": f"ops.{operation_id.removeprefix('core.')}.label",
                "descriptionKey": f"ops.{operation_id.removeprefix('core.')}.description",
            },
            "intf": {
                "inputs": inputs,
                "result": {"type": "object", "properties": result_properties},
                "errors": [],
                "uiHints": {},
            },
            "policy": {
                "risk": "LOW",
                "confirm": {"kind": "NONE"},
                "audit": "METADATA_ONLY",
                "undoSupported": False,
                "inverseOperationId": None,
            },
            "availability": {},
            "lineage": {"affects": [], "supersedes": []},
            "provenance": {"tier": "CORE", "contributorId": "core", "version": "1.0"},
            "executors": ["UI"],
            "audience": "USER",
            "consumers": [],
        }

    export = entry(
        "core.export-diagnostics",
        {"path": {"type": "string"}},
        inputs={"type": "object", "properties": {"feTelemetry": {"type": "object"}}},
    )
    summary = entry(
        "core.copy-diagnostic-summary",
        {"summary": {"type": "string"}},
        inputs={"type": "object", "properties": {}, "additionalProperties": False},
    )
    summary["intf"]["result"]["required"] = ["summary"]
    return json.dumps({
        "schemaVersion": "1.0.0",
        "catalogVersion": 1,
        "namespace": "core",
        "primitive": "Operation",
        "entries": [export, summary],
    })


# The Library substrate list: a thin {items, count} envelope around IndexedRootView
# (LibrarySurface.ts:62 listResponseSchema, `.loose()`). An empty list is the schema-valid
# "no folders configured" state — the SAME minimal-empty principle as the registry catalogs.
# This endpoint is non-fail-open (parseWireContract), so the unmapped `{}` it used to get tripped
# the parse and logged `[WireContract] contract drift` — a fixtures gap masquerading as an app
# error (tempdoc 615 §33). Mapping it un-pollutes the `console_real` trust signal; the
# fixture-coverage clause of check-ui-step-coverage keeps the next such endpoint from drifting
# silently (615 §37.1).
_BODY_INDEXED_ROOTS = json.dumps({"items": [], "count": 0})


def _minutes_ago_iso(minutes: int) -> str:
    """An ISO instant ``minutes`` in the past, computed AT REQUEST TIME.

    Deliberately not a hard-coded literal: the row's meta line renders this through the host's
    relative formatter (`relativeTime.formatRelative` — 'just now' / 'Nm ago' / 'Nh ago' / 'Nd ago'),
    so a frozen timestamp would render a DIFFERENT string every day the capture is re-taken ('3d
    ago' → '47d ago'). Deriving it from `now` is what keeps the rendered text byte-stable; the
    minute bucket only moves if a capture takes >1 min between fixture-serve and paint."""
    stamp = datetime.now(timezone.utc) - timedelta(minutes=minutes)
    return stamp.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _indexed_roots_body(variant: str) -> str:
    """The Library substrate list for a variant (tempdoc 813 §4 — the ENRICHING folder tier).

    The `enriching` variant serves TWO rows that differ ONLY in enrichment coverage, so one capture
    renders both drained arms of `folderStatus` (folderStatus.ts:289-319) side by side:
      - `docs` — coverage KNOWN and incomplete ⟹ state `enriching`: the shared
        `ENRICHMENT_CATCHING_UP_CAVEAT` wording plus THIS root's own percent.
      - `notes` — every applicable stage settled ⟹ state `ready` ("fully searchable"), which is the
        per-root truth OUTRANKING the still-active index-wide backfill (813 §17's four-arm merge).

    Every non-coverage field is pinned to the drained-and-clean shape the tier requires
    (`inFlightCount`/`failedCount` 0, `status` "indexed", `walkCompleted` true, both timestamps set,
    `deleteDetectionUnverified` false) — any one of them off would divert the row to an EARLIER
    branch (indexing / failed / unverified) and the capture would silently stop being about
    enrichment at all.

    The coverage denominators follow the wire's own discipline: each parent stage counts only the
    documents carrying ITS status field, and the chunk tier is counted over CHUNKED documents. The
    applicability flags come from the index-wide `/api/status` snapshot (`_status_body`'s matching
    `enriching` arm), never from the row — the wire row carries counts only.

    Every other variant keeps `_BODY_INDEXED_ROOTS` (the empty list), unchanged."""
    if variant != "enriching":
        return _BODY_INDEXED_ROOTS
    return json.dumps({
        "items": [
            {
                "pathHash": "a1b2c3d4e5f60718293a4b5c6d7e8f90",
                "collection": "docs",
                "status": "indexed",
                "fileCount": 300,
                "inFlightCount": 0,
                "failedCount": 0,
                "walkCompleted": True,
                "deleteDetectionUnverified": False,
                "lastIndexedIsoTime": _minutes_ago_iso(6),
                "lastVerifiedIsoTime": _minutes_ago_iso(4),
                # 1,080 settled of 1,800 applicable ⟹ 60%: embedding mid-flight (120/300), SPLADE and
                # NER settled (300/300 each), chunk embeddings mid-flight (360/900).
                "parentDocsTotalEmbedding": 300,
                "parentDocsSettledEmbedding": 120,
                "parentDocsTotalSplade": 300,
                "parentDocsSettledSplade": 300,
                "parentDocsTotalNer": 300,
                "parentDocsSettledNer": 300,
                "chunkDocsTotal": 900,
                "chunkDocsSettled": 360,
            },
            {
                "pathHash": "0f9e8d7c6b5a49382716253443526170",
                "collection": "notes",
                "status": "indexed",
                "fileCount": 100,
                "inFlightCount": 0,
                "failedCount": 0,
                "walkCompleted": True,
                "deleteDetectionUnverified": False,
                "lastIndexedIsoTime": _minutes_ago_iso(9),
                "lastVerifiedIsoTime": _minutes_ago_iso(4),
                # Settled on every applicable stage ⟹ `complete` is EXACT (settled >= total), the only
                # basis on which the row may claim "fully searchable".
                "parentDocsTotalEmbedding": 100,
                "parentDocsSettledEmbedding": 100,
                "parentDocsTotalSplade": 100,
                "parentDocsSettledSplade": 100,
                "parentDocsTotalNer": 100,
                "parentDocsSettledNer": 100,
                "chunkDocsTotal": 300,
                "chunkDocsSettled": 300,
            },
        ],
        "count": 2,
    })


def _surface_entry(
    surface_id: str, mount_tag: str, placement: str, members: list[str] | None = None,
) -> dict:
    """One Surface catalog entry, SCHEMA-COMPLETE against the generated wire contract.

    Tempdoc 911 (885 UL.10): this used to be deliberately partial, on the (then-true) premise that
    `SurfaceCatalogClient.tryFetchAndPopulate` only checked `Array.isArray(body.entries)` and cast
    the rest. Tempdoc 884 replaced that cast with `parseWireContract(surfaceCatalogSchema, …)`, whose
    generated `z.strictObject` requires EVERY field below — so the partial body started failing the
    parse, `tryFetchAndPopulate` swallowed the throw and returned false, and `listSurfaces()` stayed
    permanently empty of wire data. `members` lives ONLY on the wire entry (a CORE plugin
    contribution omits it and the merge preserves the wire's), so `memberHostAliases()` produced no
    core.security-surface -> core.settings-surface redirect and the `security` / `security-light`
    a11y-gate steps timed out on `jf-settings-window dialog[open]`.

    So: still schema-SHAPED filler (not a byte-exact capture), but it must now be schema-COMPLETE,
    and `members` is always present (the schema has no `.optional()` on it). `altitude` / `riskTier`
    / `stateSchema` / `provenance.identity` / `presentation.category` / `presentation.iconHint`
    carry the values the Java `Surface` record defaults these four surfaces to (CoreSurfaceCatalog
    passes neither altitude nor riskTier, so PRODUCT + LOW; `Presentation.of` and
    `Provenance.core` leave the nullable slots null)."""
    entry: dict = {
        "id": surface_id,
        "altitude": "PRODUCT",
        "presentation": {
            "labelKey": f"registry-surface.{surface_id.split('.', 1)[1]}.label",
            "descriptionKey": f"registry-surface.{surface_id.split('.', 1)[1]}.description",
            "category": None,
            "iconHint": None,
        },
        "audience": "USER",
        "placement": placement,
        "consumes": {
            "resources": [],
            "operations": [],
            "prompts": [],
            "diagnosticChannels": [],
            "conversationShapes": [],
        },
        "members": members or [],
        "mountTag": mount_tag,
        "provenance": {
            "tier": "CORE",
            "contributorId": "core",
            "version": "1.0",
            "identity": None,
        },
        "riskTier": "LOW",
        "stateSchema": None,
    }
    return entry


def _surfaces_catalog_body() -> str:
    """The `/api/registry/surfaces` catalog (855 F2 closure fix). Fixtures mode previously fell
    through to the generic empty-object body (unmapped in `_ROUTES`), so `listSurfaces()` stayed
    empty and `memberHostAliases()` (catalogResolver.ts) — built by scanning every host's declared
    `members` — never produced the core.security-surface -> core.settings-surface redirect. The
    `security`/`security-light` ui-shot steps navigate to the (now off-rail, member-only)
    core.security-surface expecting that redirect to open the settings window, and timed out
    waiting for the modal with an empty catalog.

    Ids/placements/membership mirror CorePlugin.ts + CoreSurfaceCatalog.java exactly: settings-surface
    is MODAL and hosts [presentation-gallery, presentation-editor, security] as members;
    security-surface (and the two presentation surfaces) are DEEPLINK — the minimal composition
    shape `resolveSurface`/`memberHostAliases` need to redirect correctly."""
    entries = [
        _surface_entry(
            "core.settings-surface", "jf-settings-surface", "MODAL",
            members=[
                "core.presentation-gallery-surface",
                "core.presentation-editor-surface",
                "core.security-surface",
            ],
        ),
        _surface_entry("core.search-v3-surface", "jf-sv3-window", "RAIL"),
        _surface_entry("core.security-surface", "jf-security-surface", "DEEPLINK"),
        _surface_entry(
            "core.presentation-gallery-surface", "jf-presentation-gallery-surface", "DEEPLINK",
        ),
        _surface_entry(
            "core.presentation-editor-surface", "jf-presentation-editor-surface", "DEEPLINK",
        ),
    ]
    return json.dumps({
        "schemaVersion": "1.0.0",
        "catalogVersion": 1,
        "namespace": "core",
        "primitive": "Surface",
        "entries": entries,
    })


# Path substring -> fixture body. First match wins. `/api/status`, `/api/knowledge/search`,
# `/api/inference/status`, and `/api/settings` are NOT here — all four have a per-variant
# transform and are dispatched explicitly in `fixture_body()` before this table is consulted.
# `/api/indexing-roots/substrate` DOES have a per-variant transform (`_indexed_roots_body`) and is
# likewise dispatched first, but it STAYS in this table: `_ROUTES` is the authority the
# `check-ui-step-coverage` fixture-coverage clause reads (615 §37.1), and this row is the body every
# non-`enriching` variant still serves.
_ROUTES: tuple[tuple[str, str], ...] = (
    ("/api/preview", json.dumps({"content": "# Indexing pipeline\n\nThe worker enriches documents before search returns results.\n\n" * 12,
        "totalChars": 1200, "offsetChars": 0, "hasMore": False})),
    ("/api/diagnostics/ingestion/summary", '{"rollups": [], "count": 0}'),
    ("/api/operation-history/", '{"historySince": 0, "state": "unknown"}'),
    ("/api/indexing-roots/substrate", _BODY_INDEXED_ROOTS),
    ("/api/registry/surfaces", _surfaces_catalog_body()),
    ("/api/registry/operations", _help_operations_catalog_body()),
    ("/api/registry/resources", _empty_catalog("Resource")),
    ("/api/registry/diagnostic-channels", _empty_catalog("DiagnosticChannel")),
)

_GAP_BUILDING_GENERATION = "g-0199abc1-2345-7abc-8abc-0123456789ab"
_GAP_OUTCOME = json.dumps({
    "historySince": 0,
    "state": "running",
    "phase": "awaiting_acceptance",
    "result": {
        "gapListHash": "a" * 64,
        "gaps": [
            {"unitId": "notes/old-draft.txt", "reason": "CANDIDATE_PROJECTION_MISSING"},
            {"unitId": "notes/deleted-draft.txt", "reason": "DELETE_NOT_APPLIED"},
        ],
    },
})

# Seed: dismiss the first-run 'welcome' walkthrough (id per canonicalManifest.ts) so
# the overlay never clutters the deterministic capture, and pin the inspector tab.
WALKTHROUGH_SEED = (
    "try {"
    "localStorage.setItem('justsearch-inspector-tab','ai');"
    "localStorage.setItem('justsearch.userState.v2', JSON.stringify({"
    "  version: 2, activeProfileId: 'default', profiles: {},"
    "  walkthroughState: { welcome: { activeStepIndex: 0, completedStepIds: [], dismissed: true } }"
    "}));"
    "} catch (e) {}"
)


def is_api_path(url: str) -> bool:
    """True for the REST root only — NOT the FE's own `/src/api/*.ts` Vite modules."""
    path = urlparse(url).path
    return path == "/api" or path.startswith("/api/")


# Data-extreme variants for the GENERATE fuzzer (tempdoc 615 §11 GENERATE). The
# "data-extreme" axis becomes a fixture transform — not a backend state — because the
# whole point of route-mock is that data is a deterministic fixture. Minimal-viable set;
# add `huge`/`long-names`/`error` here as the set grows.
#
# NOTE (tempdoc 697 activation): `degraded` (the `_status_body` transform below) is
# DELIBERATELY NOT added here. `VARIANTS` is consumed ONLY by the GENERATE fuzzer
# (`ui_fuzz.py`), which crosses it with {viewport x theme} as a data-extreme axis for the
# search surface — adding `degraded` here would silently add a fuzzer cell. `degraded` is
# reachable only via an explicit `install_fixtures(ctx, variant="degraded")` call, made by
# the isolated `chat-proportion` ui-shot step alone (`ui_check.py`'s `Step.fixtures_variant`).
# The same reasoning holds for `indexing` (tempdoc 813 Slice D), reachable only from the
# isolated `tasks-occlusion` step, and for `enriching` (tempdoc 813 §4/§5), reachable only from the
# isolated `library-enriching` step.
VARIANTS = ("default", "empty")

# Structural Search variants are separate from the data-extreme fuzzer axis above.
# degraded: compact notice + ready human Ask; degraded-detailed: expanded notice.
# agent-run: canonical recorded activity/evidence; agent-budget: held live decision.
_DEGRADED_VARIANTS = frozenset({"degraded", "degraded-detailed", "agent-run", "agent-budget", "no-evidence"})
_THREAD_RECORD_VARIANTS = frozenset({"agent-run", "sv3-sources"})

# Search restores through the shared per-tab pointer, never another tab's recency.
_FIXTURE_CONVERSATION_ID = "fixture-multi-turn-conversation"
THREAD_POINTER_SEED = (
    "try { sessionStorage.setItem('justsearch.lastViewedConversation.v1', "
    f"'{_FIXTURE_CONVERSATION_ID}'); }} catch (e) {{}}"
)


# Wire-valid recorded agent sources, distinct from retrieval citations below.
_AGENT_RUN_SOURCES: tuple[dict, ...] = (
    {
        "parentDocId": "doc-indexing-pipeline",
        "chunkIndex": 3,
        "path": "docs/explanation/indexing-pipeline.md",
        "title": "Indexing pipeline",
        "excerpt": "The worker enriches each document before the head projects the result set.",
        "startLine": 41,
        "endLine": 48,
        "headingText": "Enrichment stages",
    },
    {
        "parentDocId": "doc-system-overview",
        "chunkIndex": 7,
        "path": "docs/explanation/01-system-overview.md",
        "title": "System overview",
        "excerpt": "Head, Body and Brain are separate processes; only the Body owns the index.",
        "startLine": 12,
        "endLine": 19,
        "headingText": "Process model",
    },
    {
        "parentDocId": "doc-retrieval-contract",
        "chunkIndex": 1,
        "path": "docs/reference/api-contract-map.md",
        "title": "API contract map",
        "excerpt": "Retrieval results cross the in-process port and are projected onto the surface.",
        "startLine": 88,
        "endLine": 95,
        "headingText": "Knowledge search",
    },
)

# Tempdoc 859 §B — the RETRIEVAL citations the `sv3-sources` variant hangs on its last assistant
# message. Shape: `components/chat/citationTypes.ts`'s `RetrievalCitation` (the wire key is
# `attributes.citations`, distinct from `attributes.sources` above, which is the AgentSource rail
# feed). `sv3-record.ts`'s `recordEvidenceOf` reads exactly this array into a v3 turn's
# `evidence.sources`, which is what mounts `jf-citations-panel` behind the turn's Sources
# disclosure. THREE rows so the panel has real height to occlude with.
_SV3_RETRIEVAL_CITATIONS: tuple[dict, ...] = (
    {
        "parentDocId": "doc-indexing-pipeline",
        "chunkIndex": 3,
        "chunkTotal": 12,
        "startChar": 1180,
        "endChar": 1460,
        "score": 0.91,
        "excerpt": "The worker enriches each document before the head projects the result set.",
        "startLine": 41,
        "endLine": 48,
        "headingText": "Enrichment stages",
        "headingLevel": 2,
    },
    {
        "parentDocId": "doc-system-overview",
        "chunkIndex": 7,
        "chunkTotal": 20,
        "startChar": 320,
        "endChar": 610,
        "score": 0.84,
        "excerpt": "Head, Body and Brain are separate processes; only the Body owns the index.",
        "startLine": 12,
        "endLine": 19,
        "headingText": "Process model",
        "headingLevel": 2,
    },
    {
        "parentDocId": "doc-retrieval-contract",
        "chunkIndex": 1,
        "chunkTotal": 9,
        "startChar": 44,
        "endChar": 300,
        "score": 0.77,
        "excerpt": "Retrieval results cross the in-process port and are projected onto the surface.",
        "startLine": 88,
        "endLine": 95,
        "headingText": "Knowledge search",
        "headingLevel": 3,
    },
)

# Tempdoc 859 §B — long enough that the transcript overflows the step's pinned viewport, which is
# what makes `minScrollableRegions: 1` and the max-scroll occlusion assertion non-vacuous.
_SV3_LONG_ANSWER = (
    "The worker enriches each document, then the head projects the result set. "
    "Enrichment runs in stages: the extractor normalises the document body, the chunker splits it "
    "on structural boundaries, and the encoder produces the dense and sparse representations the "
    "index stores side by side. None of that work happens in the head process, which never touches "
    "the index directly and reaches the index half through an in-process port.\n\n"
    "When a query arrives the head fans it out to both retrieval arms, fuses the two ranked lists, "
    "and reranks the survivors before any of it reaches the surface. The passages that come back "
    "carry their own provenance — the parent document, the chunk offsets and the heading they were "
    "written under — which is what lets an answer point at the exact lines it was grounded in "
    "rather than at a document as a whole.\n\n"
    "Everything the reader sees below the answer is that provenance, rendered. Opening the Sources "
    "disclosure mounts the shared citations panel for this turn, which is the same component every "
    "other window in the product mounts on a landed answer."
)

# Canonical run lifecycle; its counts and budget agree with DONE_RUN.
_AGENT_RUN_LIFECYCLE: dict = {
    "sessionId": "fixture-agent-run-0001",
    "state": "DONE",
    "actor": "primary",
    "turns": 2,
    "iterations": 2,
    "toolCalls": 1,
    "actors": ["primary"],
    "budget": {"initial": 8192, "consumed": 1840, "remaining": 6352, "overBudget": False},
}


def _thread_body(variant: str = "sv3-sources") -> str:
    """Canonical thread records: two user turns, with retrieval evidence or agent activity.

    Search projects these through the real thread parser and record adapter. Long retrieval
    answers make transcript overflow a witnessed state rather than a max-count assumption.
    """
    with_evidence = variant == "agent-run"
    with_citations = variant == "sv3-sources"
    last_attributes = None
    if with_evidence:
        last_attributes = {"sources": list(_AGENT_RUN_SOURCES), "citations": [], "shapeId": "core.agent-run"}
    elif with_citations:
        last_attributes = {"citations": list(_SV3_RETRIEVAL_CITATIONS)}

    def _event(idx: int, kind: str, originator: str, content: str,
               attributes: dict | None = None) -> dict:
        return {
            "id": f"evt-{idx}",
            # Fixed timestamps keep the capture byte-stable (the projection sorts on them).
            "occurredAt": f"2026-08-06T10:0{idx}:00Z",
            "kind": kind,
            "originator": originator,
            "content": content,
            "attributes": attributes or {},
        }

    return json.dumps({
        "conversationId": _FIXTURE_CONVERSATION_ID,
        "events": [
            _event(1, "USER_MESSAGE", "user", "What is this file about?"),
            _event(2, "ASSISTANT_MESSAGE", "assistant",
                   _SV3_LONG_ANSWER if with_citations
                   else "It describes how the indexing pipeline hands results to the head process."),
            _event(3, "USER_MESSAGE", "user", "And how does indexing reach it?"),
            *([_event(4, "TOOL_ACTIVITY", "agent", "Searched indexing", {
                "callId": "fixture-tool-1", "toolName": "search", "arguments": '{"query":"indexing"}',
                "status": "completed", "success": True,
                "output": "Indexing hands searchable documents to the query pipeline. " * 8,
                "shapeId": "core.agent-run",
            })] if with_evidence else []),
            _event(5 if with_evidence else 4, "ASSISTANT_MESSAGE", "assistant",
                   _SV3_LONG_ANSWER if with_citations
                   else "The worker enriches each document, then the head projects the result set.",
                   last_attributes),
        ],
        "lifecycles": [_AGENT_RUN_LIFECYCLE] if with_evidence else [],
    })


# Real Delegate readiness probe, only for fixtures that model an agent.
_AGENT_TOOLS_BODY = json.dumps({"available": True, "tools": []})


# `sv3-results` (WP1.1, the `sv3-search-results` step) is Search mode's LONG list: the default
# body's hits repeated under distinct ids and paths until the result list overflows a pinned narrow
# viewport, so "the last row stays reachable above the floating composer" is a real assertion
# rather than one a two-row list satisfies vacuously. Not a fuzzer axis — see the `VARIANTS` note.
_SV3_RESULTS_COUNT = 24


def _search_body(variant: str) -> str:
    """The search response for a variant. 'empty' = the zero-results edge state."""
    if variant == "empty":
        d = json.loads(_BODY_SEARCH)
        d["results"] = []
        d["totalHits"] = 0
        d["matchCount"] = 0
        return json.dumps(d)
    if variant == "sv3-results":
        d = json.loads(_BODY_SEARCH)
        base = d["results"]
        hits = []
        for i in range(_SV3_RESULTS_COUNT):
            hit = json.loads(json.dumps(base[i % len(base)]))
            name = f"note-{i + 1:02d}.md"
            hit["id"] = f"doc-sv3-{i + 1}"
            hit["fields"]["path"] = f"C:/docs/notes/{name}"
            hit["fields"]["filename"] = name
            hit["fields"]["title"] = f"Note {i + 1}"
            hits.append(hit)
        d["results"] = hits
        d["totalHits"] = _SV3_RESULTS_COUNT
        d["matchCount"] = _SV3_RESULTS_COUNT
        return json.dumps(d)
    return _BODY_SEARCH


def _status_body(variant: str) -> str:
    """Wire-valid readiness states. Search keeps indexing controls quiet.

    Degraded variants have a real warning code, indexed content, and authoritative READY
    chat operation; only those variants claim that a human Ask or Delegate is available.
    """
    if variant == "semantic-paused":
        # D1-14: an in-place model-change build serves keyword results while
        # the dense leg is rebuilding. The reason code, not the migration
        # source, licenses the notice in the Lit search window.
        d = json.loads(_BODY_STATUS)
        d["worker"]["migration"].update({
            "migrationState": "SWITCHING",
            "migrationSource": "embedding_model_change",
            "activeGenerationId": "g1",
            "buildingGenerationId": "g2",
            "servingSearchGenerationId": "g2",
            "servingIngestGenerationId": "g2",
        })
        d["worker"]["core"]["indexedDocuments"] = 1
        d["worker"]["compatibility"]["embeddingCompatState"] = "INITIALIZING"
        d["components"]["encoders"]["state"] = "RELOADING"
        d["readiness"]["engineComponents"]["encoders"]["state"] = "RELOADING"
        d["readiness"]["components"]["indexServing"]["state"] = "DEGRADED"
        d["readiness"]["composites"]["retrieval"] = {
            "state": "DEGRADED", "reasonCodes": ["index.embedding_rebuilding"],
            "stale": False, "maxStalenessMs": 0,
        }
        return json.dumps(d)
    if variant == "gap-decision":
        d = json.loads(_BODY_STATUS)
        d["worker"]["migration"]["migrationState"] = "AWAITING_ACCEPTANCE"
        d["worker"]["migration"]["buildingGenerationId"] = _GAP_BUILDING_GENERATION
        return json.dumps(d)
    if variant == "enriching":
        d = json.loads(_BODY_STATUS)
        # This capture measures confirmed work. Stamp each response when served, rather than
        # presenting the canonical January 2024 observation as current (S33.4 / N33.3).
        d["meta"]["workerRpcAtMs"] = int(datetime.now(timezone.utc).timestamp() * 1000)
        core = d["worker"]["core"]
        core["indexState"] = "IDLE"
        core["indexHealthy"] = True
        core["indexedDocuments"] = 400
        core["pendingJobs"] = 0
        enrichment = d["worker"]["enrichment"]
        enrichment["backfillMode"] = "combined"
        enrichment["embeddingEnabled"] = True
        enrichment["spladeEnabled"] = True
        enrichment["nerEnabled"] = True
        enrichment["embeddingDocCount"] = 400
        enrichment["embeddingPendingCount"] = 160
        enrichment["embeddingCompletedCount"] = 240
        enrichment["spladeDocCount"] = 400
        enrichment["spladePendingCount"] = 0
        enrichment["spladeCompletedCount"] = 400
        enrichment["pendingNerCount"] = 0
        enrichment["completedNerCount"] = 400
        enrichment["chunk"]["chunkDocCount"] = 1200
        enrichment["chunk"]["chunkEmbeddingPendingCount"] = 480
        enrichment["chunk"]["chunkEmbeddingCompletedCount"] = 720
        return json.dumps(d)
    if variant == "indexing":
        d = json.loads(_BODY_STATUS)
        # Keep the active task aggregate confirmed throughout the capture's status polls.
        d["meta"]["workerRpcAtMs"] = int(datetime.now(timezone.utc).timestamp() * 1000)
        core = d["worker"]["core"]
        core["indexState"] = "INDEXING"
        core["indexHealthy"] = True
        core["indexedDocuments"] = 1218
        core["pendingJobs"] = 412
        core["recentDocsPerSec"] = [4.0, 4.0, 4.0]
        d["worker"]["migration"]["processingJobsCount"] = 4
        enrichment = d["worker"]["enrichment"]
        enrichment["backfillMode"] = "combined"
        enrichment["embeddingDocCount"] = 1218
        enrichment["embeddingPendingCount"] = 430
        enrichment["spladeDocCount"] = 1218
        enrichment["spladePendingCount"] = 430
        return json.dumps(d)
    if variant in _DEGRADED_VARIANTS:
        d = json.loads(_BODY_STATUS)
        d["readiness"]["composites"]["retrieval"] = {
            "state": "DEGRADED",
            "reasonCodes": ["encoders.health.embedding_not_ready"],
        }
        d["worker"]["core"]["indexedDocuments"] = 1
        # These variants submit chat turns. The operating view owns chat readiness,
        # so it must agree with their online inference fixture below.
        d["operating"]["chat"].update({
            "state": "READY", "reason": None, "detail": None, "reasonCode": None,
            "load": None, "queued": 0,
        })
        return json.dumps(d)
    return _BODY_STATUS


def _inference_body(variant: str) -> str:
    """Online inference only for fixtures that promise an available AI action."""
    if variant in _DEGRADED_VARIANTS:
        return json.dumps({
            "mode": "online",
            "available": True,
            "starting": False,
            "embeddingQueueSize": 0,
            "vduQueueSize": 0,
            "llmContextTokens": 4096,
            "configuredContextTokens": 4096,
            "tier": "default",
            "activeModelId": "fixture-model",
            "generation": 1,
            "lastStartupDurationMs": 1000,
            "gpu": {"cudaAvailable": True, "totalVramBytes": 8_000_000_000, "vramDescription": "8 GB"},
        })
    return "{}"


def _settings_body(variant: str) -> str:
    """Compact disclosure unless this fixture explicitly promises Detailed."""
    if variant in ("degraded", "agent-run", "agent-budget", "no-evidence"):
        d = json.loads(_BODY_SETTINGS)
        d["ui"]["mode"] = "simple"
        return json.dumps(d)
    return _BODY_SETTINGS


def _install_preview_bodies() -> dict[str, str]:
    """A deterministic two-component install scenario, using registry labels/terms/sizes.

    This is fixture plan selection, not the hardware planner: select CPU embedding and
    reranking so the required and improves-results groups both have a concrete row.
    """
    registry_path = _FIX_DIR.parents[3] / "configuration/src/main/resources/ai/model-registry.v2.json"
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    packages = [p for p in registry["packages"] if p["id"] in ("embedding", "reranker")]
    components = []
    for package in packages:
        variant = next(v for v in package["variants"] if v.get("targetEP") == "CPU")
        size = variant["sizeBytes"] + sum(f["sizeBytes"] for f in package.get("supportingFiles", []))
        components.append({
            "id": package["id"], "label": package["label"], "description": package["description"],
            "tier": package["tier"], "necessity": package["necessity"],
            "declinable": package["necessity"] != "required", "declined": False,
            "totalBytes": size, "downloadBytes": size, "state": "to-download", "unavailableReason": "",
        })
    tier_labels = {"retrieval-core": "Core retrieval", "retrieval-enrichment": "Retrieval enrichment"}
    preview = {"intent": "full-desktop", "downloadProfile": "CPU_ONLY", "resumableBytes": 0,
               "totalDownloadBytes": sum(c["downloadBytes"] for c in components), "components": components,
               "tiers": [{"tier": c["tier"], "label": tier_labels[c["tier"]], "includedByIntent": True,
                          "totalBytes": c["totalBytes"], "downloadBytes": c["downloadBytes"]}
                         for c in components]}
    return {
        "/api/ai/install/manifest": json.dumps({**registry, "packages": packages}),
        "/api/ai/install/plan-preview": json.dumps(preview),
        "/api/ai/install/status": json.dumps({"state": "idle", "installedFully": False,
            "resumableBytes": 0, "downloadedBytes": 0, "totalBytes": 0, "packages": [],
            "stages": [], "readyCapabilities": [], "bytesPerSecond": -1, "remainingSeconds": -1}),
    }


def fixture_body(url: str, variant: str = "default") -> str:
    """The deterministic body for a given /api URL under a data variant. Unmapped
    endpoints get an empty object (the structural steps don't depend on their contents)."""
    if variant == "install-preview":
        path = urlparse(url).path
        if path in ("/api/ai/install/manifest", "/api/ai/install/plan-preview", "/api/ai/install/status"):
            return _install_preview_bodies()[path]
        if path == "/api/status":
            status = json.loads(_BODY_STATUS)
            status["inference"].update({"phase": "OFFLINE", "identity": None,
                "engineState": "Down", "engineReason": "not-installed", "chatEnabledSpec": False})
            status["aiReady"] = False
            return json.dumps(status)
    if "/api/messages/registry-surface/en" in url:
        # Keep the Search copy in its production catalog, including cold async boot.
        catalog = Path(__file__).resolve().parents[3] / "modules/app-api/src/main/resources/messages/registry-surface.en.properties"
        messages = dict(line.split("=", 1) for line in catalog.read_text().splitlines()
                        if line and not line.startswith("#") and "=" in line)
        return json.dumps({"locale": "en", "messages": messages})
    if "/api/inference/status" in url:
        return _inference_body(variant)
    if "/api/status" in url:
        return _status_body(variant)
    if "/api/settings" in url:
        return _settings_body(variant)
    if "/api/knowledge/search" in url:
        return _search_body(variant)
    if "/api/indexing-roots/substrate" in url:
        return _indexed_roots_body(variant)
    if "/api/thread/" in url:
        if variant in _THREAD_RECORD_VARIANTS:
            return _thread_body(variant)
        conversation_id = unquote(urlparse(url).path.split("/api/thread/", 1)[1])
        return json.dumps({"conversationId": conversation_id, "events": [], "lifecycles": []})
    if urlparse(url).path.startswith("/api/chat/conversations/") and urlparse(url).path.endswith("/history"):
        return json.dumps({"messages": []})
    if "/api/chat/agent/tools" in url and variant in ("agent-run", "agent-budget"):
        return _AGENT_TOOLS_BODY
    if "/api/operation-history/" in url and variant == "gap-decision":
        return _GAP_OUTCOME
    for needle, body in _ROUTES:
        if needle in url:
            return body
    return "{}"


class _SettingsFixtureState:
    """One browser context's witnessed settings document and keyed receipts.

    ``color_scheme`` projects the browser context's initial appearance into the
    settings GET without changing the captured canonical fixture. Subsequent
    POSTs continue to mutate and witness this context-local document.
    """

    def __init__(self, variant: str, color_scheme: str = "dark"):
        self._settings = json.loads(_settings_body(variant))
        self._settings["ui"]["theme"] = color_scheme
        self._receipts: dict[str, tuple[str, dict]] = {}

    def get_body(self) -> str:
        observation = deepcopy(self._settings)
        observation["operationKey"] = None
        observation["state"] = None
        return json.dumps(observation)

    def post(self, raw_body: str) -> tuple[int, str]:
        request = json.loads(raw_body)
        key = request.get("operationKey")
        canonical = json.dumps(request, sort_keys=True, separators=(",", ":"))
        if not isinstance(key, str):
            return 400, json.dumps({"error": "Missing operation key", "errorCode": "INVALID_REQUEST"})
        prior = self._receipts.get(key)
        if prior is not None:
            if prior[0] != canonical:
                return 409, json.dumps({"error": "Operation key reused", "errorCode": "OPERATION_KEY_REUSED"})
            return 200, json.dumps(prior[1])

        witness = request.get("witness")
        if witness != self._settings.get("witness"):
            return 409, json.dumps({
                "error": "Settings changed elsewhere", "errorCode": "VERSION_CONFLICT", "retryable": False,
            })
        for section in ("ui", "llm"):
            patch = request.get(section)
            if isinstance(patch, dict):
                current = self._settings.get(section)
                self._settings[section] = {**(current if isinstance(current, dict) else {}), **patch}
        if "indexPaths" in request:
            self._settings["indexPaths"] = deepcopy(request["indexPaths"])

        accepted_revision = int(witness["acceptedRevision"]) + 1
        self._settings["witness"] = {
            "acceptedRevision": accepted_revision,
            "lastCommittedOperationKey": key,
        }
        receipt = deepcopy(self._settings)
        receipt["operationKey"] = key
        receipt["state"] = "COMPLETE"
        self._receipts[key] = (canonical, deepcopy(receipt))
        return 200, json.dumps(receipt)


async def install_fixtures(ctx, variant: str = "default", color_scheme: str = "dark") -> None:
    """Make a browser context deterministic: seed the dismissed walkthrough and
    serve fixtures for every `/api/*` call (so the no-backend 502 storm can't occur).
    ``variant`` selects a per-route transform: `_search_body` (GENERATE data-extreme,
    'empty') and `_status_body` (readiness state, 'degraded' — tempdoc 697) both key off
    the same ``variant`` string. ``color_scheme`` selects the initial theme returned by
    `/api/settings/v2` for this context and defaults to dark. Call once on a fresh
    context, before `new_page`."""
    await ctx.add_init_script(WALKTHROUGH_SEED)
    # Variant-gated: only the record-bearing variants want a cold chat surface to auto-restore
    # the fixture conversation (`_thread_body`), so no other step's boot changes.
    if variant in _THREAD_RECORD_VARIANTS:
        await ctx.add_init_script(THREAD_POINTER_SEED)
    settings = _SettingsFixtureState(variant, color_scheme=color_scheme)

    # A held budget decision needs an OPEN SSE response. route.fulfill closes a body and
    # correctly causes STREAM_INCOMPLETE; it cannot model this state. Own a loopback-only
    # transport for this one fixture, and close every connection with its browser context.
    budget_url = None
    if variant == "agent-budget":
        writers = set()
        async def held_stream(reader, writer):
            writers.add(writer)
            try:
                await reader.readuntil(b"\r\n\r\n")
                body = sse_frame("session_started", {"sessionId": "fixture-held-budget"})
                body += sse_frame("budget_update", {"phase": "llm_response", "tokensConsumed": 8192,
                    "tokensRemaining": 0, "totalTokensConsumed": 8192,
                    "promptTokens": 3072, "contextWindow": 4096})
                body += sse_frame("budget_gate", {"tokensNeeded": 2048, "tokensRemaining": 0,
                    "totalTokensConsumed": 8192})
                writer.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                    "Access-Control-Allow-Origin: *\r\nCache-Control: no-cache\r\n\r\n" + body).encode())
                await writer.drain()
                await reader.read()
            finally:
                writer.close()
                await writer.wait_closed()
                writers.discard(writer)
        server = await asyncio.start_server(held_stream, "127.0.0.1", 0)
        budget_url = f"http://127.0.0.1:{server.sockets[0].getsockname()[1]}/api/chat/dispatch"
        async def close_budget_transport():
            server.close()
            await server.wait_closed()
            for writer in list(writers):
                writer.close()
                await writer.wait_closed()
        ctx.on("close", close_budget_transport)

    async def _handler(route):
        req = route.request
        if "/api/chat/dispatch" in req.url and budget_url is not None:
            await route.continue_(url=budget_url)
            return
        if "/api/chat/dispatch" in req.url and variant in _DEGRADED_VARIANTS:
            shape = json.loads(req.post_data or "{}").get("shapeId")
            if shape == "core.rag-ask" and variant == "no-evidence":
                await route.fulfill(status=200, content_type="text/event-stream",
                    body='event: error\ndata: {"error":"No matching documents","errorCode":"NO_CONTENT"}\n\n')
                return
            if shape == "core.agent-run":
                body = DONE_RUN_BODY
            else:
                # The real human Ask transport; no component state or private method injection.
                frames = [
                    ("rag.citations", {"citations": list(_SV3_RETRIEVAL_CITATIONS)}),
                    ("chunk", {"text": "The worker enriches each document, then the head projects the result set."}),
                    ("done", {"promptTokens": 3072, "contextWindow": 4096}),
                ]
                if shape == "core.free-chat":
                    frames = frames[1:]
                elif shape == "core.extract":
                    frames = [("done", {"finalResponse": '{"summary":"Indexing enriches documents."}', "promptTokens": 256})]
                body = "".join(f"event: {event}\ndata: {json.dumps(payload)}\n\n" for event, payload in frames)
            await route.fulfill(status=200, content_type="text/event-stream", body=body)
            return
        if "/api/settings/v2" in req.url:
            if req.method == "POST":
                status, body = settings.post(req.post_data or "{}")
            else:
                status, body = 200, settings.get_body()
            await route.fulfill(status=status, content_type="application/json", body=body)
            return
        accept = req.headers.get("accept") or ""
        if "/stream" in req.url or "text/event-stream" in accept:
            await route.fulfill(status=200, content_type="text/event-stream", body="")
            return
        await route.fulfill(status=200, content_type="application/json",
                            body=fixture_body(req.url, variant))

    await ctx.route(lambda url: is_api_path(url), _handler)
