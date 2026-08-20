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
  status/search/settings; minimal-valid EMPTY catalogs for the registry endpoints).
- A glob `**/api/**` over-matches the FE's own `/src/api/*.ts` Vite modules; the
  matcher MUST be a path predicate (`path == '/api' or startswith('/api/')`).
"""
from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlparse

from .agent_stream_fixture import DONE_RUN_BODY

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
    """One minimal Surface catalog entry (types/surface.ts `Surface`). The FE client
    (`SurfaceCatalogClient.tryFetchAndPopulate`) does NOT Zod-validate this envelope — it only
    checks `Array.isArray(body.entries)` and casts the rest — so this is schema-SHAPED filler, not
    a byte-exact wire capture; only `id`/`placement`/`members`/`mountTag` are load-bearing for the
    consumers below."""
    entry: dict = {
        "id": surface_id,
        "presentation": {
            "labelKey": f"registry-surface.{surface_id.split('.', 1)[1]}.label",
            "descriptionKey": f"registry-surface.{surface_id.split('.', 1)[1]}.description",
        },
        "audience": "USER",
        "placement": placement,
        "consumes": {"resources": [], "operations": [], "prompts": [], "diagnosticChannels": []},
        "mountTag": mount_tag,
        "provenance": {"tier": "CORE", "contributorId": "core", "version": "1.0"},
    }
    if members:
        entry["members"] = members
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
    ("/api/indexing-roots/substrate", _BODY_INDEXED_ROOTS),
    ("/api/registry/surfaces", _surfaces_catalog_body()),
    ("/api/registry/operations", _empty_catalog("Operation")),
    ("/api/registry/resources", _empty_catalog("Resource")),
    ("/api/registry/diagnostic-channels", _empty_catalog("DiagnosticChannel")),
)

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

# The two variants that turn the degraded-readiness knobs. `degraded` (tempdoc 697) also flips
# `ui.mode` to "simple" so the COLLAPSED pill renders; `degraded-detailed` (tempdoc 814 closure,
# the `chat-bands-detailed` step) is the same readiness/inference state with the captured
# "advanced" disclosure LEFT ALONE, so the banner renders EXPANDED instead — the Detailed-mode
# floor the D1 share assertion had no capture for. Neither is a fuzzer axis (see the note above).
# `degraded-thread` (tempdoc 814 review pass, the `chat-spine-multi` step) is the same
# readiness/inference/disclosure state as `degraded` PLUS a canonical thread RECORD with two
# user turns — the state `spineItems()` needs and the only fixture-reachable way to reach it
# (see `_thread_body`).
# `agent-run` (tempdoc 814 §D8, the `chat-evidence-rail` / `chat-activity-rail-open` steps) is
# `degraded-thread` PLUS (a) grounding sources + a DONE lifecycle on the thread record and
# (b) a real terminating SSE body for POST /api/chat/dispatch (agent_stream_fixture.DONE_RUN).
# It is the only variant under which a fixture-driven agent RUN completes.
_DEGRADED_VARIANTS = frozenset({"degraded", "degraded-detailed", "degraded-thread", "agent-run"})

# `sv3-sources` (tempdoc 859 §B, the `sv3-composer-occlusion` step) is the record path WITHOUT the
# degraded knobs: a thread whose last answer carries populated `attributes.citations`, which is what
# the Search v3 window projects into a turn's evidence (`views/search-v3/sv3-record.ts`'s
# `recordEvidenceOf`). `agent-run` already emits `citations: []` there, and `panelSpeaks`
# (`Sv3Main`) gates on LENGTH — so the sv3 Sources panel was never fixture-unreachable, only one
# populated array away. The answers are deliberately long so the transcript OVERFLOWS a pinned
# viewport; a capture where nothing scrolls satisfies an occlusion assertion vacuously.
#
# It is a SEPARATE variant rather than a change to `agent-run` so that variant's bytes are
# untouched and `chat-evidence-rail` / `chat-activity-rail-open` keep their baselines.

# The variants that serve a `/api/thread/{id}` RECORD (and therefore seed the per-tab
# lastViewedConversation pointer so a cold chat surface auto-restores it).
_THREAD_RECORD_VARIANTS = frozenset({"degraded-thread", "agent-run", "sv3-sources"})

# The per-tab pointer UnifiedChatView reads on connect (`readLastViewedConversation`,
# controllers/lastViewedConversation.ts KEY) — seeding it is what makes a COLD chat surface
# auto-load the fixture conversation below instead of landing on an empty thread.
_FIXTURE_CONVERSATION_ID = "fixture-multi-turn-conversation"
THREAD_POINTER_SEED = (
    "try { sessionStorage.setItem('justsearch.lastViewedConversation.v1', "
    f"'{_FIXTURE_CONVERSATION_ID}'); }} catch (e) {{}}"
)


# Tempdoc 814 §D8.1 — the grounding the `agent-run` variant hangs on its LAST assistant message.
# Shape: `api/generated/shape-handlers/shared.ts`'s `AgentSource` (every field required — the FE
# reads `path`/`title`/`headingText` for the rail rows and `parentDocId` + `startLine`/`endLine`
# for the click-to-local-line deep link). `hydrateAnswerEvidenceFromRecord` (UnifiedChatView)
# scans the record BACKWARD for the newest `ASSISTANT_MESSAGE` carrying a non-empty
# `attributes.sources`, so this is what mounts `.evidence-rail` with NO stream involvement —
# the whole point of §D8.1's record-path-first split.
#
# THREE rows, not one: `EVIDENCE_RAIL_MAX_VISIBLE` bounds the docked rail to a top-N index (§D3),
# so a single row could never show the "N of M" bounded-index behaviour the rail exists to have.
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
        "excerpt": "Retrieval results reach the head over gRPC and are projected onto the surface.",
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
        "excerpt": "Retrieval results reach the head over gRPC and are projected onto the surface.",
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
    "the index directly and reaches the worker over gRPC instead.\n\n"
    "When a query arrives the head fans it out to both retrieval arms, fuses the two ranked lists, "
    "and reranks the survivors before any of it reaches the surface. The passages that come back "
    "carry their own provenance — the parent document, the chunk offsets and the heading they were "
    "written under — which is what lets an answer point at the exact lines it was grounded in "
    "rather than at a document as a whole.\n\n"
    "Everything the reader sees below the answer is that provenance, rendered. Opening the Sources "
    "disclosure mounts the shared citations panel for this turn, which is the same component every "
    "other window in the product mounts on a landed answer."
)

# Tempdoc 814 §D8.1 — the typed loop object the rail's lifecycle row reads (`unifiedLifecycles`,
# validated by `unifiedThreadClient.ts`'s `lifecycleSchema`). `state: "DONE"` is load-bearing
# twice: it is the row's own text, and `runCompleted` (UnifiedChatView) reads it to render a
# finished run as a neutral FACT rather than an alarm. Counts agree with the SSE `done` payload
# (`agent_stream_fixture.DONE_RUN`) and the budget agrees with its `budget_update`, so the record
# and the stream cannot tell two different stories about the same run.
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


def _thread_body(variant: str = "degraded-thread") -> str:
    """The `GET /api/thread/{id}` record for the `degraded-thread` variant (tempdoc 814
    review pass): TWO user turns and their answers, in the wire shape
    `views/unifiedThreadClient.ts` validates (`conversationId` + `events[]` of
    `{id, occurredAt, kind, originator, content, attributes}` with `kind` in
    KNOWN_EVENT_KINDS).

    WHY A RECORD AND NOT TWO SUBMITS: `spineItems()` reads `mergedTimeline()`, which is built
    from the canonical RECORD (`projectUnifiedThread(this.unifiedEvents)`) plus the live agent
    overlay — NOT from `this.thread`, the plain ask-turn array. So submitting asks under
    `--fixtures` (which is all the stubbed SSE allows) can never move the spine's turn count,
    however many turns land: measured — two rendered `.message.user` bubbles, `affordance:
    'agent'`, `wideZone: true`, and still zero `.run-spine`, because the record was empty.
    Two turns is exactly `spineItems()`'s `turns < 2` floor (UnifiedChatView.ts ~3163).
    Content is inert prose; no evidence/sources, so nothing else in the view changes shape.

    Tempdoc 814 §D8.1 — the `agent-run` variant keeps that structure UNCHANGED (so
    `chat-spine-multi` is untouched) and adds exactly the two fields the record can already
    carry: `attributes.sources` on the LAST assistant message (which
    `hydrateAnswerEvidenceFromRecord` turns into `agentCtrl.answerSources`, mounting
    `.evidence-rail`) and a DONE `lifecycles[]` entry (which the activity rail's
    `.activity-lifecycle` row reads). Neither needs a stream; that is §D8.1's whole claim."""
    with_evidence = variant == "agent-run"
    # Tempdoc 859 §B — the sv3 arm. Deliberately a SEPARATE branch from `with_evidence`: touching
    # `agent-run`'s bytes would move the `chat-evidence-rail` / `chat-activity-rail-open` baselines.
    with_citations = variant == "sv3-sources"
    last_attributes = None
    if with_evidence:
        last_attributes = {"sources": list(_AGENT_RUN_SOURCES), "citations": []}
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
            _event(4, "ASSISTANT_MESSAGE", "assistant",
                   _SV3_LONG_ANSWER if with_citations
                   else "The worker enriches each document, then the head projects the result set.",
                   last_attributes),
        ],
        "lifecycles": [_AGENT_RUN_LIFECYCLE] if with_evidence else [],
    })


# Tempdoc 814 §D8.2 — the agent capability probe (`AgentSessionController.checkAvailability`,
# polled by `agentSessionStore`'s `startPolling`). Under every other variant this endpoint is
# unmapped, so `data.available` reads `undefined` -> `ctrl.available` never becomes `true` ->
# `UnifiedChatView.send()`'s `if (ctrl.available !== true) return` SILENTLY drops the submit and
# no agent run can start. LIVE-VERIFIED as the blocker (the run never left the composer without
# it). Variant-gated so no other step gains an agent capability it does not model. `tools: []` is
# honest: the fixture run executes no tool through this probe's catalog.
_AGENT_TOOLS_BODY = json.dumps({"available": True, "tools": []})


def _search_body(variant: str) -> str:
    """The search response for a variant. 'empty' = the zero-results edge state."""
    if variant == "empty":
        d = json.loads(_BODY_SEARCH)
        d["results"] = []
        d["totalHits"] = 0
        d["matchCount"] = 0
        return json.dumps(d)
    return _BODY_SEARCH


def _status_body(variant: str) -> str:
    """The status response for a variant. 'degraded' (tempdoc 697 activation) flips
    `readiness.composites.retrieval` to DEGRADED with a real `LifecycleReasonCode`
    (`worker.health.embedding_not_ready` — LifecycleReasonCode.java:29) so the chat
    window's collapsed degradation pill (`.degradation-banner-collapsed`,
    UnifiedChatView.renderCollapsedDegradationBanner) renders deterministically. The
    reason code carries 'warn' severity (verdict.ts severityForCodes), not 'error', so
    severity alone does not force the banner open (UnifiedChatView.ts:2123
    `forcedExpanded = isAdvancedMode() || verdict.severity === 'error'` — the other half,
    Simple-mode disclosure, is `_settings_body`'s job below). Also bumps
    `worker.core.indexedDocuments` off zero: LIVE-VERIFIED (headless
    probe) that `availability.ts:110-125`'s `no_documents` gate — not just AI-online —
    pins the composer's Ask/Delegate escalation to plain search (askPinned() reads
    `projectAvailability('documents', aiState)`, which short-circuits to `unavailable` on a
    zero document count regardless of AI capability); with docs > 0 the SAME projection
    instead returns `{kind:'degraded', caveat}` off this step's own degraded verdict
    (availability.ts:134-143), which does NOT pin Ask. NOT a fuzzer axis — see the
    `VARIANTS` note above.

    'indexing' (tempdoc 813 Slice D) puts the worker in a live INDEXING state with enrichment
    still behind, so the Tasks panel renders its aggregate card deterministically for the
    `tasks-occlusion` step. Every knob is load-bearing for `selectIndexingProgress`
    (indexingProgress.ts):
      - `core.indexState` -> "INDEXING": the projection ignores any state the WORKER does not
        report (WORKER_REPORTED_INDEX_STATES = IDLE/INDEXING/ERROR). The captured live fixture
        says "SERVING", a `WorkerOperationalView.fallback` state, so the projection is on its
        `unknown` arm by default and the panel correctly renders nothing.
      - `core.pendingJobs` > 0: the ONLY input that selects the `indexing` phase (and the panel's
        "N files remaining" count). `migration.processingJobsCount` splits it running/queued.
      - `core.recentDocsPerSec`: three equal non-zero samples — the projection's stability test
        (all trailing samples non-zero) — so the coarse "~" estimate line renders too. Equal
        samples keep the median, and therefore the rendered string, byte-stable.
      - the `enrichment` counters + `backfillMode`: enrichment genuinely behind, so the fixture
        represents the real overlap (jobs draining WHILE the backfill runs) rather than a
        jobs-only state that could never occur with a live backfill.
    Critically, the panel's visibility under this variant derives from the POLL projection, not
    from the SSE task list — `install_fixtures` serves every `/stream` as an EMPTY event stream
    (see `_handler` below), so a panel gated on SSE tasks would capture as hidden and the
    occlusion assertion would pass vacuously. NOT a fuzzer axis — see the `VARIANTS` note above.

    'enriching' (tempdoc 813 §4/§5) is the ENRICHING phase the `indexing` variant deliberately does
    NOT reach: the job queue is DRAINED (`pendingJobs` 0) while the enrichment backfill still owes
    work, which is the only input combination `selectIndexingProgress` reads as
    `phase === 'enriching'` (indexingProgress.ts:333-334 — `jobsPending > 0` would win the ternary,
    so a variant with any backlog can never render this phase). `indexState` stays "IDLE" because the
    projection only reads WORKER-reported states, and IDLE is the honest one for a drained queue.
    The counters are internally consistent (completed + pending == doc count per stage) and give the
    aggregate card its faithful denominator: 640 pending of 2,400 applicable ⟹ 73%, with SPLADE and
    NER already settled so the number comes from the two stages that are genuinely behind. This is
    the ONLY variant that also transforms the Library substrate list (`_indexed_roots_body`), because
    the per-root tier needs BOTH halves — coverage counts from the row, stage applicability from
    here.

    `degraded-detailed` needs the identical readiness state — the banner it expands is the same
    one this transform gives something to render."""
    if variant == "enriching":
        d = json.loads(_BODY_STATUS)
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
            "reasonCodes": ["worker.health.embedding_not_ready"],
        }
        d["worker"]["core"]["indexedDocuments"] = 1
        return json.dumps(d)
    return _BODY_STATUS


def _inference_body(variant: str) -> str:
    """The `/api/inference/status` response for a variant (tempdoc 697 activation).
    LIVE-VERIFIED (headless probe): this endpoint is UNMAPPED for every other variant
    (falls through to `fixture_body`'s generic `{}`), which reads as `available: undefined`
    -> `aiStateStore.computeCapabilities().chat = false` -> the composer's Ask/Delegate
    escalation is pinned to plain search (`UnifiedChatView.askPinned()` via
    `availability.ts:104-106`) for EVERY existing structural step under `--fixtures` — the
    correct behavior for those (AI genuinely offline with no dev stack). 'degraded' reports
    the model ONLINE so the `chat-proportion` step can actually submit a turn (escalateAsk()
    -> send()). Kept variant-gated (NOT added to `_ROUTES`) so no other step's rendering
    changes. `degraded-detailed` needs the same ONLINE report for the same two reasons (a
    submitted turn, and the Delegate rung's `capabilities.chat` availability gate)."""
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
    """The `/api/settings` response for a variant (tempdoc 697 activation). The captured
    `_BODY_SETTINGS` fixture carries `ui.mode: "advanced"` (whatever the live capture's
    disclosure was set to at capture time). LIVE-VERIFIED (headless probe): Advanced mode
    force-expands the chat window's degradation banner regardless of severity
    (UnifiedChatView.ts:2123 `forcedExpanded = isAdvancedMode() || verdict.severity ===
    'error'`), so `.degradation-banner-collapsed` never renders under the captured default —
    only the wider expanded `.degradation-banner` form does. 'degraded' flips `ui.mode` to
    "simple" so the collapsed pill (the element this ratchet tracks) renders. Every other
    variant keeps the captured `advanced` default unchanged.

    DELIBERATELY not extended to `degraded-detailed` (tempdoc 814 closure): that variant exists
    precisely to KEEP the captured "advanced" disclosure, which is how the `chat-bands-detailed`
    step gets the EXPANDED banner (`forcedExpanded = isAdvancedMode() && !this.shortZone`) — the
    Detailed-mode height floor that had no registered ceiling. This one function is the ONLY knob
    separating the two degraded variants."""
    if variant in ("degraded", "degraded-thread", "agent-run"):
        d = json.loads(_BODY_SETTINGS)
        d["ui"]["mode"] = "simple"
        return json.dumps(d)
    return _BODY_SETTINGS


def fixture_body(url: str, variant: str = "default") -> str:
    """The deterministic body for a given /api URL under a data variant. Unmapped
    endpoints get an empty object (the structural steps don't depend on their contents)."""
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
    if "/api/thread/" in url and variant in _THREAD_RECORD_VARIANTS:
        return _thread_body(variant)
    if "/api/chat/agent/tools" in url and variant == "agent-run":
        return _AGENT_TOOLS_BODY
    for needle, body in _ROUTES:
        if needle in url:
            return body
    return "{}"


async def install_fixtures(ctx, variant: str = "default") -> None:
    """Make a browser context deterministic: seed the dismissed walkthrough and
    serve fixtures for every `/api/*` call (so the no-backend 502 storm can't occur).
    ``variant`` selects a per-route transform: `_search_body` (GENERATE data-extreme,
    'empty') and `_status_body` (readiness state, 'degraded' — tempdoc 697) both key off
    the same ``variant`` string. Call once on a fresh context, before `new_page`."""
    await ctx.add_init_script(WALKTHROUGH_SEED)
    # Variant-gated: only the record-bearing variants want a cold chat surface to auto-restore
    # the fixture conversation (`_thread_body`), so no other step's boot changes.
    if variant in _THREAD_RECORD_VARIANTS:
        await ctx.add_init_script(THREAD_POINTER_SEED)

    async def _handler(route):
        req = route.request
        # Tempdoc 814 §D8.2 — the agent run's OWN transport. `host.ai.streamShape` POSTs the
        # shape to /api/chat/dispatch and reads the RESPONSE as SSE; the generic JSON branch
        # below used to answer it with `{}`, so both buffer-based parsers saw no terminal frame
        # and `pumpHostAiStream` threw STREAM_INCOMPLETE — the "Connection lost — the response
        # was interrupted." row in every agent-mode capture. Serving a complete, schema-validated
        # multi-frame body instead is what makes a DONE run capture-reachable. Checked BEFORE the
        # `/stream`-ish branch (this path contains no "/stream") and before the JSON branch.
        if "/api/chat/dispatch" in req.url and variant == "agent-run":
            await route.fulfill(status=200, content_type="text/event-stream", body=DONE_RUN_BODY)
            return
        accept = req.headers.get("accept") or ""
        if "/stream" in req.url or "text/event-stream" in accept:
            await route.fulfill(status=200, content_type="text/event-stream", body="")
            return
        await route.fulfill(status=200, content_type="application/json",
                            body=fixture_body(req.url, variant))

    await ctx.route(lambda url: is_api_path(url), _handler)
