"""Lane F semantic-non-regression **workflow fixture** — capture + structural diff.

Design contract: ``docs/design/lane-f-engine-jvm/design.md`` section 16 row
"semantic non-regression" and section 17.2 (PR 0). Ranking metrics say nothing about
*what a client was handed*, so this instrument captures a fixed set of search queries
and chat turns — returned evidence (doc/chunk ids, source identity, truncation points),
citations, and cancellation behaviour — to a JSON artifact, and DIFFS two captures taken
from two backend builds ("split" = today's ``main``, "single" = the lane branch).

**Mode-agnostic by construction.** Nothing here knows whether the backend is one process
or two: it speaks HTTP to a running backend and nothing else. That is why the fixture
lands in PR 0, before stage A, so the thing stage E diffs against exists on ``main`` first.

The equality relation
---------------------
Declared **before** any run, in the fixture file's ``fields`` table: every captured field
path maps to ``exact`` or to exactly one **allowed-difference class**, from a closed set
of three (design.md 17.7):

``new-reason-code``
    Reason codes that did not exist in the baseline. Compared as SETS: the baseline's
    codes must be a subset of the candidate's. A code only the candidate has is allowed;
    a code the baseline had and the candidate lost is a REGRESSION.
``equal-score-order``
    Ordering of equal-score hits. The value is a score-tagged list
    (``[{"score": s, "value": v}, ...]``); the differ groups consecutive hits whose score
    is within the fixture's ``scoreTieEpsilon`` of the group's first member, and compares
    each group as an unordered multiset. A tie permutation is allowed; a changed member is
    a REGRESSION.

    **"Equal" is instantiated as within-epsilon, and this is a mechanism detail (17.6).**
    Design 16 names the class "ordering of equal-score hits"; scores are not byte-stable in
    practice, so exact equality would make the class inert. Measured on two consecutive
    captures of ONE build against ONE index (``tmp/fixture-capture-{1,2}.json``): over 118
    identity-matched hits the score delta was non-zero on 111 of them, max **0.009442**,
    mean 0.001658 — GPU float nondeterminism in the dense / cross-encoder legs. In the same
    data **no** adjacent score gap was zero, so an exact-equality grouping yields 120
    singleton groups and permits nothing at all.

    Two consequences the differ has to respect:

    * the raw score is never COMPARED, only used to derive the partition. Group *sizes* are
      compared, not group scores — comparing scores would re-introduce the jitter the class
      exists to absorb. ``queries.hits[].score`` is consequently not a declared field and
      not in the diffed capture; the observed scores are recorded under the capture's
      non-diffed ``observed`` block for the reader.
    * grouping is over CONSECUTIVE hits, and the delivered order is **not** score-sorted:
      ``SearchResultMapper.java:133`` applies ``applyFreshnessDecay`` per hit without
      re-sorting, so the emitted score is not the sort key. Consecutive grouping is
      therefore deliberately CONSERVATIVE — two within-epsilon hits that are not adjacent
      land in different groups and swapping them is a REGRESSION. That is the safe
      direction: it under-permits, never over-permits.

    **The whole hit is one value.** ``queries.hits[]`` carries the entire per-hit
    projection as a single dict per position — id, source identity, chunk span, excerpt
    spans, matched fields, stage ids. Projecting each attribute into its OWN score-tagged
    list would let the differ permute them independently, so a candidate whose chunk spans
    swapped inside a tie group while the ids stayed put would read as an allowed
    reordering. One value per hit makes that a regression, which is what it is.

    One key inside that dict, :data:`_HIT_REASON_CODE_KEYS`, keeps ``new-reason-code``
    semantics: tie-group members are paired on their exact keys, and only then is the
    subset rule applied to the reason code. That is why a ``hits[]`` diff can report
    ``allowed:new-reason-code`` — both names come from the same closed set of three; no
    fourth class is introduced.
``generative-text``
    Generative text, nominally under a fixed seed. Any difference is allowed.

:func:`validate_fixture` **raises** on any other class name, and any field present in a
capture but absent from the declaration table is a REGRESSION — so a newly captured field
cannot slip through unclassified.

Fields that legitimately move with the clock are **not captured at all** rather than being
given a class: ``SearchTrace.stages[].ms``, ``tookMs``, the ``latencyMs`` figures, the SSE
``heartbeat`` frames, and the per-run ``sessionId``.

No fixed seed exists on the chat path
-------------------------------------
``POST /api/chat/agent`` carries **no** ``seed`` request field. The only ``seed`` that
reaches llama-server is the vision/VDU agreement-probe overload —
``OnlineModeOps.sendChatRequestDetailed(..., Long seed)``
(``modules/app-inference/src/main/java/io/justsearch/app/inference/OnlineModeOps.java:957``,
written to the request body at ``:966``); ``sendChatRequest`` always passes ``null``
(same file, ``:947-950``). Adding one is a backend change and out of scope for this
instrument. Generative text is therefore treated under ``generative-text``
**unconditionally**, not "under a fixed seed", and the fixture's chat turns are
deliberately narrow and factual so that the chat fields declared ``exact`` (citation
targets, source identity, tool names, terminal disposition) have the best chance of
holding without one. If a chat ``exact`` field diffs, read it as a finding about the
fixture's assumption first — but record it, do not reclassify: no class is added after a
diff is seen.

Paths are relative to a declared corpus root
--------------------------------------------
Every path-bearing value the backend returns is the ABSOLUTE indexed path, and the
baseline and candidate captures are taken from two different worktrees — so without
anchoring, every hit would differ on ``path`` and the diff would be all-REGRESSION for a
reason that is not semantic. :class:`CorpusRootRewriter` stores each one as a
forward-slash, case-preserved path relative to the root
(``F:\\…\\lane-F\\docs\\explanation\\02-process-coordination.md`` becomes
``docs/explanation/02-process-coordination.md``). The root comes from ``--corpus-root``,
else ``$JUSTSEARCH_FIXTURE_CORPUS_ROOT``, else the single registered watched root; zero or
several roots is a refusal, not a guess. **Both captures must be taken with the corpus at
the same relative layout under their respective roots** — this makes two worktrees
comparable, it does not make two different corpus arrangements comparable.

Two fields are dropped rather than rewritten — ``hit.id`` and its duplicate
``fields.doc_id``. See :func:`hit_projection`: for a chunk hit that string is a fresh
``UUID`` per indexing run, so there is nothing to rewrite it to.

Endpoints used
--------------
- ``POST /api/knowledge/search`` — the search half (``KnowledgeSearchResponse`` +
  ``SearchTrace``).
- ``POST /api/chat/agent`` with ``Accept: text/event-stream`` — the chat half.
- ``DELETE /api/chat/sessions/{id}`` — cancellation (``AgentRoutes.java:80`` ->
  ``AgentController.handleCancelSession``). A search request has **no** cancel endpoint
  (client abort only), so the fixture's cancellation case is an agent turn.
- ``GET /api/chat/sessions/{id}`` — the post-cancel session record (``state``,
  ``terminationReason.{disposition,errorCode,cancelTrigger}``).
- ``GET /api/indexing/roots`` — corpus-root derivation when none was declared.
- ``GET /api/status`` — informational provenance only; **not** diffed.

The mutating ``DELETE`` carries the per-boot session token from
``JUSTSEARCH_SESSION_TOKEN`` as ``X-JustSearch-Session`` when set, matching
``jseval/ingest.py:189``. ``httpx`` derives the ``Host`` header from ``base_url``
(``http://127.0.0.1:<port>``), which is what the local-API Host allowlist wants — the
same thing every other jseval HTTP caller relies on.
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
from pathlib import Path
from typing import Any, Iterable

import httpx

log = logging.getLogger(__name__)

CAPTURE_SCHEMA = "jseval.workflow-fixture-capture.v1"
DIFF_SCHEMA = "jseval.workflow-fixture-diff.v1"
FIXTURE_SCHEMA = "jseval.lane-f-workflow-fixture.v1"

DEFAULT_CAPTURE_FILENAME = "workflow-fixture-capture.v1.json"

#: The closed set of allowed-difference classes (design.md 17.7). Exactly three, never more.
ALLOWED_DIFFERENCE_CLASSES = ("new-reason-code", "equal-score-order", "generative-text")
EXACT = "exact"

#: Statuses a field can carry in a diff result.
STATUS_EQUAL = "equal"
STATUS_REGRESSION = "REGRESSION"
STATUS_MISSING = "missing"

#: SSE events dropped at capture time (liveness beat, not behaviour).
_UNCAPTURED_EVENTS = frozenset({"heartbeat"})

#: Streamed-token events collapsed to one entry in the event-name sequence: their COUNT is
#: a function of the generated text, which has no declared class.
_TOKEN_EVENTS = frozenset({"chunk", "reasoning_chunk"})

#: Keys INSIDE the ``queries.hits[]`` per-hit projection that carry ``new-reason-code``
#: semantics rather than ``exact``. Every other key of the projection must match exactly
#: once tie-group members have been paired. Declared here rather than in the fixture's
#: `fields` table because the table maps *captured field paths*, and these are sub-keys of
#: one captured path — a table entry for them would be a declaration the capture never
#: emits, which the health check now refuses.
_HIT_REASON_CODE_KEYS = frozenset({"extractionReasonCode"})

_SESSION_TOKEN_ENV = "JUSTSEARCH_SESSION_TOKEN"
_CORPUS_ROOT_ENV = "JUSTSEARCH_FIXTURE_CORPUS_ROOT"

#: Substituted for a path-bearing value that does not live under the declared corpus root.
#: The raw value is kept only in the (non-diffed) provenance block, so the diff never
#: compares one machine's absolute path against another's.
OUTSIDE_ROOT_SENTINEL = "<outside-corpus-root>"


class WorkflowFixtureError(RuntimeError):
    """The fixture, a capture, or a diff request is malformed."""


# ---------------------------------------------------------------------------
# Fixture validation
# ---------------------------------------------------------------------------

def validate_fixture(fixture: dict) -> dict[str, str]:
    """Return the declaration table, raising unless the fixture is well-formed.

    Refuses a class name outside :data:`ALLOWED_DIFFERENCE_CLASSES` — the "exactly three
    classes, nothing else" rule is enforced here rather than trusted.
    """
    if not isinstance(fixture, dict):
        raise WorkflowFixtureError("fixture must be a JSON object")
    schema = fixture.get("schema")
    if schema != FIXTURE_SCHEMA:
        raise WorkflowFixtureError(
            f"fixture schema is {schema!r}, expected {FIXTURE_SCHEMA!r}")
    fields = fixture.get("fields")
    if not isinstance(fields, dict) or not fields:
        raise WorkflowFixtureError("fixture declares no `fields` table")
    epsilon = fixture.get("scoreTieEpsilon")
    if isinstance(epsilon, bool) or not isinstance(epsilon, (int, float)) or epsilon < 0:
        raise WorkflowFixtureError(
            "fixture must declare a non-negative numeric `scoreTieEpsilon` — the width of a "
            "score tie. Scores are not byte-stable (GPU float nondeterminism), so exact "
            "equality would make the equal-score-order class inert."
        )
    for path, klass in fields.items():
        if klass == EXACT:
            continue
        if klass not in ALLOWED_DIFFERENCE_CLASSES:
            raise WorkflowFixtureError(
                f"field {path!r} declares allowed-difference class {klass!r}, which is not "
                f"one of the three: {list(ALLOWED_DIFFERENCE_CLASSES)} (or {EXACT!r}). "
                "The class set is closed and no class is added after a diff is seen."
            )
    for key in ("queries", "chatTurns"):
        items = fixture.get(key)
        if not isinstance(items, list) or not items:
            raise WorkflowFixtureError(f"fixture declares no `{key}`")
        seen: set[str] = set()
        for item in items:
            ident = item.get("id") if isinstance(item, dict) else None
            if not ident:
                raise WorkflowFixtureError(f"a `{key}` entry has no `id`")
            if ident in seen:
                raise WorkflowFixtureError(f"duplicate `{key}` id {ident!r}")
            seen.add(ident)
    return dict(fields)


def load_fixture(path: str | Path) -> dict:
    """Read and validate a fixture definition."""
    doc = json.loads(Path(path).read_text(encoding="utf-8"))
    validate_fixture(doc)
    return doc


# ---------------------------------------------------------------------------
# SSE parsing
# ---------------------------------------------------------------------------

def _parse_frame(raw: str) -> tuple[str, Any] | None:
    event: str | None = None
    data_lines: list[str] = []
    for line in raw.split("\n"):
        if not line or line.startswith(":"):
            continue
        if line.startswith("event:"):
            event = line[len("event:"):].strip()
        elif line.startswith("data:"):
            data_lines.append(line[len("data:"):].lstrip(" "))
    if event is None and not data_lines:
        return None
    data = "\n".join(data_lines)
    payload: Any = None
    if data:
        try:
            payload = json.loads(data)
        except json.JSONDecodeError:
            payload = data
    return (event or "message", payload)


class SseBuffer:
    """Incremental ``text/event-stream`` frame splitter.

    Frames are separated by a blank line; ``\\r\\n`` is normalised to ``\\n``. Used by the
    live capture (which must act on ``session_started`` mid-stream) and by
    :func:`parse_sse_frames` for a complete body.
    """

    def __init__(self) -> None:
        self._buf = ""

    def feed(self, text: str) -> list[tuple[str, Any]]:
        self._buf += text
        # A TRAILING lone '\r' is held back rather than normalised: it may be the first
        # half of a '\r\n' split across two network chunks, and turning it into '\n' here
        # would fabricate a frame boundary in the middle of a CRLF stream.
        pending = self._buf
        holdover = ""
        if pending.endswith("\r"):
            pending, holdover = pending[:-1], "\r"
        normalised = pending.replace("\r\n", "\n").replace("\r", "\n")
        frames: list[tuple[str, Any]] = []
        while "\n\n" in normalised:
            raw, normalised = normalised.split("\n\n", 1)
            frame = _parse_frame(raw)
            if frame is not None:
                frames.append(frame)
        self._buf = normalised + holdover
        return frames

    def flush(self) -> list[tuple[str, Any]]:
        """Parse whatever is left (a final frame with no trailing blank line)."""
        raw, self._buf = self._buf.replace("\r\n", "\n").replace("\r", "\n"), ""
        frame = _parse_frame(raw)
        return [frame] if frame is not None else []


def parse_sse_frames(body: str) -> list[tuple[str, Any]]:
    """Parse a complete SSE body into ``[(event, payload), ...]``."""
    buf = SseBuffer()
    return buf.feed(body) + buf.flush()


# ---------------------------------------------------------------------------
# Capture helpers
# ---------------------------------------------------------------------------

class CorpusRootRewriter:
    """Rewrites an absolute indexed path to a forward-slash path relative to the corpus root.

    WHY THIS EXISTS. Every path-bearing value the backend returns is the ABSOLUTE indexed
    path — ``IndexingDocumentOps`` writes ``DOC_ID = PATH = absolutePath``
    (``modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/
    IndexingDocumentOps.java:162,172,174``), ``ChunkDocumentWriter`` writes a chunk's
    ``PATH = parentDocId`` (the same string,
    ``QueryFilterBuilder.java:284-285``), and the agent's ``sourceMap`` emits that
    ``parentDocId``/``path`` verbatim
    (``modules/app-agent-api/src/main/java/io/justsearch/agent/api/AgentEventPayloads.java:
    336-344``). The baseline and candidate captures are taken from two different worktrees,
    so without this every hit would differ on ``path`` and the diff would be all-REGRESSION
    for a reason that is not semantic.

    CASE. ``PathNormalizer.normalizePath`` LOWERCASES on Windows
    (``modules/worker-services/src/main/java/io/justsearch/indexerworker/util/
    PathNormalizer.java:24-27``), so the indexed path's case need not match the root the
    watched-roots registry reports. Matching is therefore case-insensitive, while the
    emitted remainder preserves whatever case the backend gave — this never imposes a fold
    of its own.

    A value outside the root becomes :data:`OUTSIDE_ROOT_SENTINEL` and is recorded (raw) in
    the capture's non-diffed provenance, where :func:`capture_health` turns it into a
    problem. Silently keeping the absolute value would reintroduce exactly the diff noise
    this class removes.
    """

    def __init__(self, root: str) -> None:
        self.root = root
        self._prefix = self._key(root)
        self.raw_example: str | None = None
        self.outside: list[str] = []

    @staticmethod
    def _key(value: str) -> str:
        return value.replace("\\", "/").rstrip("/").lower()

    @staticmethod
    def is_absolute(value: str) -> bool:
        """Windows drive letter, UNC prefix, or a leading slash.

        Deliberately NOT :func:`os.path.isabs`, which answers about the machine running
        jseval: a capture taken on Windows can be diffed on Linux, where ``isabs`` calls
        ``F:\\x`` relative and would silently stop flagging real absolute paths.
        """
        slashed = value.replace("\\", "/")
        return (
            slashed.startswith("/")
            or (len(value) >= 2 and value[1] == ":" and value[0].isalpha())
        )

    def rewrite(self, value: Any) -> Any:
        """Rewrite one path-bearing scalar; pass anything that is not a non-empty string.

        A value that is not ABSOLUTE is passed through untouched. It carries no
        machine-specific prefix, so it cannot cause the cross-worktree diff noise this
        class exists to remove — and calling it "outside the corpus root" would overload
        that signal with a second meaning and fire on any already-relative value the
        backend returns.
        """
        if not isinstance(value, str) or not value:
            return value
        if not self.is_absolute(value):
            return value
        if self.raw_example is None:
            self.raw_example = value
        slashed = value.replace("\\", "/")
        key = self._key(value)
        if key == self._prefix:
            return ""
        if key.startswith(self._prefix + "/"):
            # Slice the ORIGINAL (case-preserved) string, not the folded key.
            return slashed[len(self._prefix) + 1:]
        if value not in self.outside:
            self.outside.append(value)
        return OUTSIDE_ROOT_SENTINEL

    def rewrite_ref(self, value: Any, separator: str = "#") -> Any:
        """Rewrite the path half of a ``"<path><sep><suffix>"`` composite reference."""
        if not isinstance(value, str) or separator not in value:
            return self.rewrite(value)
        head, _, tail = value.rpartition(separator)
        return f"{self.rewrite(head)}{separator}{tail}"

    def provenance(self) -> dict:
        return {
            "corpusRoot": self.root,
            "rawRootExample": self.raw_example,
            "pathsOutsideCorpusRoot": len(self.outside),
            "pathsOutsideCorpusRootExamples": self.outside[:3],
        }


class _NoRewrite(CorpusRootRewriter):
    """Identity rewriter — for callers whose values are already relative (unit tests)."""

    def __init__(self) -> None:  # noqa: D107 - see class docstring
        super().__init__("")

    def rewrite(self, value: Any) -> Any:
        return value

    def rewrite_ref(self, value: Any, separator: str = "#") -> Any:
        return value


def resolve_corpus_root(
    client: httpx.Client, corpus_root: str | None = None
) -> tuple[str, str]:
    """Resolve the corpus root, returning ``(root, source)``.

    Order: explicit ``--corpus-root``, then ``$JUSTSEARCH_FIXTURE_CORPUS_ROOT``, then the
    single registered watched root from ``GET /api/indexing/roots``
    (``{"roots": [{collection, path, fileCount, lastIndexed?}]}`` — see
    ``docs/reference/api-contract-map.md`` "Watched Roots API"). With zero or more than one
    root the caller must say which, so this REFUSES rather than guessing: picking one would
    silently decide what "relative" means for the whole artifact.
    """
    if corpus_root and corpus_root.strip():
        return corpus_root.strip(), "option"
    env = os.environ.get(_CORPUS_ROOT_ENV)
    if env and env.strip():
        return env.strip(), "env"
    try:
        resp = client.get("/api/indexing/roots", timeout=30.0)
        resp.raise_for_status()
        roots = [
            r.get("path") for r in (resp.json().get("roots") or [])
            if isinstance(r, dict) and r.get("path")
        ]
    except (httpx.HTTPError, ValueError) as exc:
        raise WorkflowFixtureError(
            f"cannot derive the corpus root: GET /api/indexing/roots failed ({exc}). "
            f"Pass --corpus-root or set ${_CORPUS_ROOT_ENV}."
        ) from exc
    if len(roots) == 1:
        return roots[0], "indexing-roots"
    # Joined, not repr'd: a Python repr of a Windows path doubles every backslash, and this
    # message is read by a human who then pastes one of these into --corpus-root.
    listed = "".join(f"\n  - {r}" for r in roots) or "\n  (none)"
    raise WorkflowFixtureError(
        f"cannot derive the corpus root: the backend has {len(roots)} watched roots"
        f"{listed}\nExactly one is needed to make paths relative unambiguously. "
        f"Pass --corpus-root or set ${_CORPUS_ROOT_ENV}."
    )


def _hit_field(hit: dict, name: str):
    return (hit.get("fields") or {}).get(name)


def _digest(text: Any) -> str | None:
    if text is None:
        return None
    return hashlib.sha256(str(text).encode("utf-8")).hexdigest()[:16]


def _span(start: Any, end: Any) -> str | None:
    if start is None and end is None:
        return None
    return f"{start}:{end}"


def score_tagged(hits: Iterable[dict], project) -> list[dict]:
    """Build the ``equal-score-order`` representation: ``[{score, value}, ...]``.

    Self-contained on purpose — the differ never has to look up a sibling field to know
    which items tie.
    """
    return [{"score": h.get("score"), "value": project(h)} for h in hits]


def hit_projection(hit: dict, paths: CorpusRootRewriter | None = None) -> dict:
    """The whole per-hit evidence record, as ONE value.

    Kept as a single dict (rather than one score-tagged list per attribute) so the differ
    cannot permute a hit's attributes independently of its identity inside a tie group.

    TWO FIELDS ARE DELIBERATELY ABSENT — ``id`` and ``docId``. They are the same string
    (``hit.id`` is the worker's ``SearchResult.getId()``, i.e. the Lucene ``DOC_ID`` —
    ``SearchResultMapper.java:136-138``), and that string has two shapes:

    * for a WHOLE-DOCUMENT hit it is the normalized absolute path, so it is redundant with
      ``path`` once both are made relative — it is not a hash, and dropping it loses nothing
      (``IndexingDocumentOps.java:162,172,174``; ``IndexingService.java:145`` calls it "a
      normalized absolute path string");
    * for a CHUNK hit it is ``"chunk:" + UUID.randomUUID()`` — freshly minted per indexing
      run and deliberately not derived from the parent or the chunk index
      (``modules/indexing-core/src/main/java/io/justsearch/indexing/chunking/
      ChunkIds.java:52-54``). Two builds of the same corpus never agree on it, so capturing
      it would make every chunk hit a REGRESSION for a reason that is not semantic — and
      unlike an absolute path there is nothing to rewrite it to.

    A chunk hit's stable, deterministic identity is ``parentDocId`` (an absolute path, made
    relative here) plus ``chunkIndex``/``chunkSpan``, all of which ARE captured.
    """
    paths = paths or _NoRewrite()
    return {
        "path": paths.rewrite(_hit_field(hit, "path")),
        "filename": _hit_field(hit, "filename"),
        "isChunk": _hit_field(hit, "is_chunk"),
        "parentDocId": paths.rewrite(_hit_field(hit, "parent_doc_id")),
        "chunkIndex": _hit_field(hit, "chunk_index"),
        "chunkSpan": _span(
            _hit_field(hit, "chunk_start_char"), _hit_field(hit, "chunk_end_char")),
        "contentTruncated": _hit_field(hit, "content_truncated"),
        "excerptSpans": [
            _span(r.get("startChar"), r.get("endChar"))
            for r in (hit.get("excerptRegions") or [])
        ],
        "matchedFields": sorted(hit.get("matchedFields") or []),
        "stageIds": [s.get("id") for s in (hit.get("trace") or [])],
        # The one `new-reason-code` sub-key (see _HIT_REASON_CODE_KEYS).
        "extractionReasonCode": _hit_field(hit, "extraction_reason_code"),
    }


def capture_query_record(
    response: dict, *, http_status: int = 200, paths: CorpusRootRewriter | None = None
) -> dict:
    """Project one ``/api/knowledge/search`` response onto the declared field set.

    Timing (``tookMs``, ``stages[].ms``, the ``latencyMs`` figures) is dropped here rather
    than declared, so it can never be compared by accident.
    """
    hits = list(response.get("results") or [])
    trace = response.get("searchTrace") or {}
    stages = list(trace.get("stages") or [])
    degradation = trace.get("degradation") or {}

    def stage_key(stage: dict, idx: int) -> str:
        return f"{idx}:{stage.get('id')}"

    return {
        "httpStatus": http_status,
        "totalHits": response.get("totalHits"),
        "matchCount": response.get("matchCount"),
        "hitCount": len(hits),
        # NOTE there is no "hits[].score" field. Scores jitter (max 0.009442 measured over
        # two captures of one build), so a declared score field would be a REGRESSION on
        # every query. The score survives only inside the score-tagged wrapper below, where
        # the differ uses it to derive tie groups and never compares it; the observed values
        # are recorded for the reader in the capture's non-diffed `observed` block.
        "hits[]": score_tagged(hits, lambda h: hit_projection(h, paths)),
        "trace.effectiveMode": trace.get("effectiveMode"),
        "trace.decisionKind": trace.get("decisionKind"),
        "trace.stageIds": [s.get("id") for s in stages],
        "trace.stageStatuses": {stage_key(s, i): s.get("status") for i, s in enumerate(stages)},
        "trace.stageCardinality": {
            stage_key(s, i): s.get("cardinality") for i, s in enumerate(stages)
        },
        "trace.stageReasons": {stage_key(s, i): s.get("reason") for i, s in enumerate(stages)},
        "trace.degradationFlags": {
            k: degradation.get(k)
            for k in ("vectorBlocked", "hybridFallback", "spladeExecuted")
        },
        "trace.degradationReasons": {
            k: degradation.get(k)
            for k in ("vectorBlockedReason", "hybridFallbackReason", "spladeSkipReason")
        },
    }


def _source_ref(src: dict, paths: CorpusRootRewriter) -> str:
    """``"<relative parent path>#<chunkIndex>"``.

    An agent source's ``parentDocId`` is the parent's Lucene ``DOC_ID``, i.e. the absolute
    indexed path (``AgentEventPayloads.sourceMap``, ``:336-344``), so it is made relative
    here exactly as a search hit's is.
    """
    return f"{paths.rewrite(src.get('parentDocId'))}#{src.get('chunkIndex')}"


def capture_chat_record(
    frames: list[tuple[str, Any]],
    *,
    http_status: int = 200,
    cancel: dict | None = None,
    paths: CorpusRootRewriter | None = None,
) -> dict:
    """Project one agent SSE run onto the declared field set.

    ``frames`` is ``[(event, payload), ...]`` as produced by :func:`parse_sse_frames`.
    ``cancel`` is the cancellation record (``None`` for an ordinary turn — every
    ``cancel.*`` key is still emitted, as ``None``, so the declaration table covers a
    fixed key set on every turn). ``paths`` makes the source/citation path carriers
    relative to the corpus root.
    """
    paths = paths or _NoRewrite()
    kept = [(e, p) for e, p in frames if e not in _UNCAPTURED_EVENTS]

    names: list[str] = []
    for event, _payload in kept:
        if event in _TOKEN_EVENTS and names and names[-1] == event:
            continue
        names.append(event)

    def payloads(event: str) -> list[dict]:
        return [p for e, p in kept if e == event and isinstance(p, dict)]

    done = payloads("done")
    error = payloads("error")
    terminal_event = None
    # A terminal frame whose payload did not parse as an object would otherwise leave every
    # field it carries at None — indistinguishable from "the backend said nothing". Name the
    # cause instead, as its own declared (exact) field.
    terminal_parse_error = False
    for event, payload in reversed(kept):
        if event in ("done", "error"):
            terminal_event = event
            terminal_parse_error = not isinstance(payload, dict)
            break

    done_payload = done[-1] if done else {}
    error_payload = error[-1] if error else {}
    sources = [s for s in (done_payload.get("sources") or []) if isinstance(s, dict)]
    citations = [c for c in (done_payload.get("citations") or []) if isinstance(c, dict)]

    # A citation names its source by INDEX into `sources`; resolve it to the source's own
    # identity so the captured citation target survives a change in source ordering.
    def citation_target(c: dict) -> str:
        idx = c.get("sourceIndex")
        if isinstance(idx, int) and 0 <= idx < len(sources):
            return _source_ref(sources[idx], paths)
        return f"<unresolved:{idx}>"

    cancel = cancel or {}
    return {
        "httpStatus": http_status,
        "eventNames": names,
        "terminalEvent": terminal_event,
        "terminalPayloadParseError": terminal_parse_error,
        "iterationsUsed": done_payload.get("iterationsUsed"),
        "toolCallsExecuted": done_payload.get("toolCallsExecuted"),
        "toolNames": [p.get("toolName") for p in payloads("tool_exec_started")],
        "toolTruncatedForModel": [
            p.get("truncatedForModel") for p in payloads("tool_exec_completed")
        ],
        "disposition": done_payload.get("disposition"),
        "citationScorer": done_payload.get("citationScorer"),
        "citationTargets": sorted({citation_target(c) for c in citations}),
        "sourceRefs": [_source_ref(s, paths) for s in sources],
        "sourcePaths": [paths.rewrite(s.get("path")) for s in sources],
        "sourceSpans": [_span(s.get("startLine"), s.get("endLine")) for s in sources],
        "sourceContextInclusion": [s.get("contextInclusion") for s in sources],
        "sourceContextIncludedChars": [s.get("contextIncludedChars") for s in sources],
        "sourceExcerptDigests": [_digest(s.get("excerpt")) for s in sources],
        "errorCode": error_payload.get("errorCode"),
        "toolErrorCodes": [
            p.get("errorCode") for p in payloads("tool_exec_completed")
        ],
        "finalResponse": done_payload.get("finalResponse"),
        "reasoningText": "".join(
            str(p.get("text") or "") for p in payloads("reasoning_chunk")
        ) or None,
        "toolArguments": [p.get("arguments") for p in payloads("tool_call_proposed")],
        "citationSentences": [c.get("sentenceText") for c in citations],
        "cancel.requested": cancel.get("requested", False),
        "cancel.httpStatus": cancel.get("httpStatus"),
        "cancel.terminalEvent": cancel.get("terminalEvent"),
        "cancel.terminalReasonCode": cancel.get("terminalReasonCode"),
        "cancel.sessionState": cancel.get("sessionState"),
        "cancel.sessionDisposition": cancel.get("sessionDisposition"),
        "cancel.sessionCancelTrigger": cancel.get("sessionCancelTrigger"),
    }


# ---------------------------------------------------------------------------
# Live capture
# ---------------------------------------------------------------------------

def _session_headers(session_token: str | None) -> dict[str, str]:
    """The per-boot mutation token header, or ``{}`` when no token is available.

    ``ApiSecurityFilters.TOKEN_REQUIRED_METHODS`` is ``{POST, PUT, DELETE}`` in prod mode,
    so this belongs on the search POST and the chat POST as well as the cancel DELETE —
    it is installed on the whole :class:`httpx.Client` in :func:`capture`, not per call.
    """
    token = session_token if session_token is not None else os.environ.get(_SESSION_TOKEN_ENV)
    return {"X-JustSearch-Session": token} if token and token.strip() else {}


def _build_search_body(spec: dict) -> dict:
    body: dict = {"query": spec["query"], "limit": spec.get("limit", 10)}
    mode = spec.get("mode")
    if mode:
        body["mode"] = mode
    body["includeExcerpts"] = True
    body["debug"] = True
    return body


def observed_query_scores(response: dict) -> list:
    """The raw hit scores, for the capture's non-diffed ``observed`` block.

    Kept OUT of the query record on purpose: ``diff`` walks ``queries``/``chatTurns`` and
    treats every field there as declared-or-regression, so a jittering score placed among
    them would fail on every query. Putting it in a section the differ does not walk is a
    non-diffed slot by construction — no second "compared/not compared" tier to reason
    about, and no weakening of the undeclared-field rule.
    """
    return [h.get("score") for h in (response.get("results") or [])]


#: Candidate paths for the index document count on ``/api/status``, in order. The status
#: response nests it under a coverage group rather than exposing a top-level field
#: (``EmbeddingStatusGroup.docCount`` / ``ChunkCoverageGroup.docCount`` /
#: ``WorkerDebugView.docCount``), and which group is populated depends on the worker's
#: state — so this reads the first that answers and records which one it used.
_DOC_COUNT_PATHS = (
    ("embedding", "docCount"),
    ("worker", "embedding", "docCount"),
    ("chunkCoverage", "docCount"),
    ("worker", "docCount"),
    ("docCount",),
)


def _dig(doc: Any, path: tuple[str, ...]) -> Any:
    for key in path:
        if not isinstance(doc, dict):
            return None
        doc = doc.get(key)
    return doc


def read_doc_count(client: httpx.Client) -> tuple[Any, str | None]:
    """``(docCount, "<path.that.answered>")`` from ``/api/status``, or ``(None, None)``."""
    try:
        resp = client.get("/api/status", timeout=30.0)
        resp.raise_for_status()
        doc = resp.json()
    except (httpx.HTTPError, ValueError):  # pragma: no cover - live-only path
        return None, None
    for path in _DOC_COUNT_PATHS:
        value = _dig(doc, path)
        if isinstance(value, int) and not isinstance(value, bool):
            return value, ".".join(path)
    return None, None


def read_ai_runtime(client: httpx.Client) -> tuple[Any, Any]:
    """``(chatProfile, activationState)`` from ``GET /api/ai/runtime/status``.

    The profile decides which model answered the chat turns, so a paired diff is only
    meaningful when both captures were taken on the SAME one — recorded here (non-diffed)
    so a mismatched pair is legible after the fact rather than showing up as unexplained
    chat regressions.

    Field names follow the dev-MCP's own reader:
    ``scripts/dev/justsearch-dev-mcp/server.mjs:2376`` takes
    ``st?.active?.chatProfile ?? st?.chatProfile`` and ``:2373`` reads
    ``st?.activation?.state``. Both halves are best-effort — an AI-offline backend answers
    nothing useful here and that must not fail a search-only capture.
    """
    try:
        resp = client.get("/api/ai/runtime/status", timeout=30.0)
        resp.raise_for_status()
        doc = resp.json()
    except (httpx.HTTPError, ValueError):  # pragma: no cover - live-only path
        return None, None
    if not isinstance(doc, dict):
        return None, None
    active = doc.get("active") if isinstance(doc.get("active"), dict) else {}
    profile = active.get("chatProfile")
    if profile is None:
        profile = doc.get("chatProfile")
    activation = doc.get("activation") if isinstance(doc.get("activation"), dict) else {}
    return profile, activation.get("state")


def capture_provenance(client: httpx.Client) -> dict:
    """Informational backend identity from ``/api/status``. Never diffed."""
    try:
        resp = client.get("/api/status", timeout=30.0)
        resp.raise_for_status()
        doc = resp.json()
    except (httpx.HTTPError, ValueError) as exc:  # pragma: no cover - live-only path
        log.warning("provenance: /api/status unavailable (%s)", exc)
        return {"available": False, "error": str(exc)}
    worker = doc.get("worker") or {}
    return {
        "available": True,
        "schema_version": doc.get("schema_version"),
        "service": doc.get("service"),
        "status": doc.get("status"),
        "indexAvailable": doc.get("indexAvailable"),
        "worker.buildStamp": worker.get("buildStamp"),
        "observed_at": doc.get("observed_at"),
    }


def run_chat_turn(
    client: httpx.Client,
    spec: dict,
    *,
    session_token: str | None = None,
    timeout: float = 300.0,
    paths: CorpusRootRewriter | None = None,
) -> dict:
    """Run one agent turn, optionally cancelling it after a named event.

    Returns the captured chat record. The cancel is issued **synchronously between two
    reads of the stream**, on a second connection — the run's own stream stays open so
    the terminal frame it ends with is what gets recorded.
    """
    cancel_after = spec.get("cancelAfterEvent")
    body = {
        "messages": [{"role": "user", "content": spec["content"]}],
        "maxIterations": spec.get("maxIterations", 3),
    }
    frames: list[tuple[str, Any]] = []
    buf = SseBuffer()
    session_id: str | None = None
    cancel: dict = {"requested": False} if cancel_after else {}
    http_status = 0

    with client.stream(
        "POST",
        "/api/chat/agent",
        json=body,
        headers={"Accept": "text/event-stream"},
        timeout=timeout,
    ) as response:
        http_status = response.status_code
        if response.status_code != 200:
            response.read()
            return capture_chat_record(
                [], http_status=http_status, cancel=cancel or None, paths=paths)
        for text in response.iter_text():
            for frame in buf.feed(text):
                frames.append(frame)
                event, payload = frame
                if event == "session_started" and isinstance(payload, dict):
                    session_id = payload.get("sessionId")
                if (
                    cancel_after
                    and not cancel["requested"]
                    and event == cancel_after
                    and session_id
                ):
                    cancel.update(_cancel_session(client, session_id, session_token))
        frames.extend(buf.flush())

    if cancel_after:
        terminal_event = None
        terminal_reason = None
        for event, payload in reversed(frames):
            if event in ("done", "error"):
                terminal_event = event
                if isinstance(payload, dict):
                    terminal_reason = payload.get("errorCode") or payload.get("disposition")
                break
        cancel["terminalEvent"] = terminal_event
        cancel["terminalReasonCode"] = terminal_reason
        cancel.update(_session_state(client, session_id))

    return capture_chat_record(
        frames, http_status=http_status, cancel=cancel or None, paths=paths)


def _cancel_session(
    client: httpx.Client, session_id: str, session_token: str | None
) -> dict:
    """``DELETE /api/chat/sessions/{id}`` — the only cancel endpoint the API exposes.

    (A search request has none: a client can only abort the connection.)
    """
    try:
        resp = client.request(
            "DELETE",
            f"/api/chat/sessions/{session_id}",
            headers=_session_headers(session_token),
            timeout=30.0,
        )
        return {"requested": True, "httpStatus": resp.status_code}
    except httpx.HTTPError as exc:  # pragma: no cover - live-only path
        log.warning("cancel: DELETE failed (%s)", exc)
        return {"requested": True, "httpStatus": None}


def _session_state(client: httpx.Client, session_id: str | None) -> dict:
    if not session_id:
        return {}
    try:
        resp = client.get(f"/api/chat/sessions/{session_id}", timeout=30.0)
        if resp.status_code != 200:
            return {"sessionState": f"<http {resp.status_code}>"}
        doc = resp.json()
    except (httpx.HTTPError, ValueError) as exc:  # pragma: no cover - live-only path
        log.warning("cancel: session read failed (%s)", exc)
        return {}
    reason = doc.get("terminationReason") or {}
    return {
        "sessionState": doc.get("state"),
        "sessionDisposition": reason.get("disposition"),
        "sessionCancelTrigger": reason.get("cancelTrigger"),
    }


def capture(
    base_url: str,
    fixture: dict,
    out_path: str | Path,
    *,
    session_token: str | None = None,
    skip_chat: bool = False,
    timeout: float = 300.0,
    corpus_root: str | None = None,
) -> dict:
    """Run the whole fixture against a live backend and write the capture artifact.

    ``corpus_root`` anchors every path-bearing value: the backend returns ABSOLUTE indexed
    paths, and the two captures are taken from two different worktrees, so paths are stored
    relative to this root (see :class:`CorpusRootRewriter`). Omitted, it is resolved from
    ``$JUSTSEARCH_FIXTURE_CORPUS_ROOT`` or from a single registered watched root; with zero
    or several roots :func:`resolve_corpus_root` refuses.

    Returns the capture document (also written to ``out_path``).
    """
    validate_fixture(fixture)
    queries: dict[str, dict] = {}
    chat_turns: dict[str, dict] = {}
    observed_queries: dict[str, dict] = {}

    with httpx.Client(
        base_url=base_url, timeout=timeout, headers=_session_headers(session_token)
    ) as client:
        root, root_source = resolve_corpus_root(client, corpus_root)
        paths = CorpusRootRewriter(root)
        log.info("corpus root %s (from %s)", root, root_source)
        provenance = capture_provenance(client)
        doc_count_start, doc_count_path = read_doc_count(client)
        # Initialised here so --skip-chat (which skips the end read below) still resolves.
        doc_count_end, doc_count_end_path = doc_count_start, doc_count_path
        chat_profile, ai_runtime_state = read_ai_runtime(client)
        log.info("chat profile %s (ai runtime %s)", chat_profile, ai_runtime_state)
        # SEARCH BEFORE CHAT, and this order is load-bearing, not incidental: the chat turns
        # index their own agent history (measured: docCount 91 -> 102 across one capture's
        # three turns), so running them first would move the index under the queries.
        for spec in fixture["queries"]:
            resp = client.post("/api/knowledge/search", json=_build_search_body(spec))
            payload = resp.json() if resp.status_code == 200 else {}
            queries[spec["id"]] = capture_query_record(
                payload, http_status=resp.status_code, paths=paths)
            observed_queries[spec["id"]] = {"scores": observed_query_scores(payload)}
            log.info("captured query %s (%d hits)",
                     spec["id"], queries[spec["id"]]["hitCount"])
        # Refuse a capture with any rejected query. An all-401 artifact is byte-identical to
        # another all-401 artifact, so without this it would diff clean against the other
        # build and read as "no semantic regression"; a partial one is unusable as a baseline
        # (capture_health fails it) and is better refused at the source than stored.
        if queries and any(r["httpStatus"] != 200 for r in queries.values()):
            statuses = sorted({r["httpStatus"] for r in queries.values()})
            raise WorkflowFixtureError(
                f"a fixture query was rejected by the backend (statuses {statuses}); "
                "refusing to write a capture that records rejections. Check the backend "
                f"is serving the corpus and that ${_SESSION_TOKEN_ENV} / --session-token "
                "carries the per-boot mutation token."
            )
        if not skip_chat:
            for spec in fixture["chatTurns"]:
                chat_turns[spec["id"]] = run_chat_turn(
                    client, spec, session_token=session_token, timeout=timeout,
                    paths=paths)
                log.info("captured chat turn %s (terminal=%s)",
                         spec["id"], chat_turns[spec["id"]]["terminalEvent"])
        doc_count_end, doc_count_end_path = read_doc_count(client)

    # The root block rides in `provenance` — NOT diffed, by design: the two captures come
    # from two worktrees, so their roots differ and their raw examples differ. What IS
    # diffed is what the root produced (relative paths); what the reader needs is a way to
    # see what was stripped, which `rawRootExample` gives.
    provenance = {
        **provenance, **paths.provenance(), "corpusRootSource": root_source,
        # ONE capture per fresh corpus: the chat turns index agent history, so a second
        # capture on the same stack sees a changed index. The pair is the evidence.
        "docCountAtStart": doc_count_start,
        "docCountAtEnd": doc_count_end,
        "docCountSource": doc_count_path or doc_count_end_path,
        # Which model answered the chat turns. Both captures of a paired diff must be on
        # the SAME profile; recorded (never diffed) so a mismatched pair is legible instead
        # of surfacing as unexplained chat regressions.
        "chatProfile": chat_profile,
        "aiRuntimeState": ai_runtime_state,
    }

    doc = {
        "schema": CAPTURE_SCHEMA,
        "fixture_id": fixture.get("id"),
        "fixture_version": fixture.get("version"),
        "base_url": base_url,
        "provenance": provenance,
        "queries": queries,
        "chatTurns": chat_turns,
        # NOT walked by `diff` — a non-diffed slot by construction (see
        # `observed_query_scores`). Anything here is for the reader, never compared.
        "observed": {"queries": observed_queries},
    }
    out = Path(out_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps(doc, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    log.info("wrote %s", out)
    return doc


# ---------------------------------------------------------------------------
# The differ
# ---------------------------------------------------------------------------

def _canonical(value: Any) -> str:
    return json.dumps(value, sort_keys=True, ensure_ascii=False, default=str)


def _reason_codes(value: Any, prefix: str = "") -> set[str]:
    """Collect the non-null scalar leaves of ``value`` as a code set.

    Dict keys are carried into the code (``"<key>=<code>"``) so a code that RELOCATES —
    the same string appearing under a different stage — is not silently absorbed as
    "the set is unchanged". List positions are deliberately not carried: a positional
    shift is what the class exists to tolerate.
    """
    codes: set[str] = set()
    if value is None:
        return codes
    if isinstance(value, dict):
        for key, item in value.items():
            codes |= _reason_codes(item, f"{prefix}{key}=")
    elif isinstance(value, (list, tuple)):
        for item in value:
            codes |= _reason_codes(item, prefix)
    else:
        codes.add(f"{prefix}{value}")
    return codes


def _within(score: float, anchor: float, epsilon: float) -> bool:
    """``|score - anchor| <= epsilon``, INCLUSIVE at the boundary.

    The slack is not cosmetic: ``abs(0.99 - 1.00)`` is ``0.010000000000000009`` in IEEE
    754, so a bare ``<= 0.01`` puts an exactly-at-epsilon hit in a different tie group
    depending on representation error — the partition would then be decided by which
    decimal literals the backend happened to emit rather than by the declared threshold.
    """
    return abs(score - anchor) <= epsilon * (1.0 + 1e-9) + 1e-12


def _tie_groups(
    value: Any, epsilon: float = 0.0
) -> tuple[bool, list[tuple[Any, list[Any]]] | None]:
    """Split a score-tagged list into consecutive within-``epsilon`` groups.

    A hit joins the current group when its score is within ``epsilon`` of that group's
    FIRST member (not of its predecessor — a chain of small steps must not accumulate into
    an arbitrarily wide group).

    Returns ``(ok, groups)``. ``ok`` is False on a shape violation — not a score-tagged
    list, or a ``None``/non-numeric score. A missing score is a violation rather than a
    tie: treating it as one would collapse the whole list into a single group whose
    members may then be reordered freely, which is the opposite of what the class means.
    """
    if not isinstance(value, list):
        return False, None
    groups: list[tuple[Any, list[Any]]] = []
    for item in value:
        if not isinstance(item, dict) or "score" not in item or "value" not in item:
            return False, None
        score = item["score"]
        if isinstance(score, bool) or not isinstance(score, (int, float)):
            return False, None
        if groups and _within(score, groups[-1][0], epsilon):
            groups[-1][1].append(item["value"])
        else:
            groups.append((score, [item["value"]]))
    return True, groups


def _split_member(member: Any) -> tuple[str, dict[str, Any]]:
    """``(canonical exact part, {reason-key: code})`` for one tie-group member."""
    if not isinstance(member, dict):
        return _canonical(member), {}
    exact = {k: v for k, v in member.items() if k not in _HIT_REASON_CODE_KEYS}
    codes = {k: v for k, v in member.items() if k in _HIT_REASON_CODE_KEYS}
    return _canonical(exact), codes


def _compare_tie_group(
    baseline: list[Any], candidate: list[Any], index: int, score: Any
) -> tuple[bool, str, list[str]]:
    """Compare one equal-score group as an unordered multiset of WHOLE members.

    Returns ``(ok, reason, gained_codes)``. Members are paired on their exact keys, so a
    changed member — a swapped chunk span, a different path — fails here even though its
    rank moved only inside a tie. Only after the pairing holds is the ``new-reason-code``
    subset rule applied to :data:`_HIT_REASON_CODE_KEYS`.
    """
    from collections import Counter

    b_keys = [_split_member(m) for m in baseline]
    c_keys = [_split_member(m) for m in candidate]
    b_counts = Counter(k for k, _ in b_keys)
    c_counts = Counter(k for k, _ in c_keys)
    if b_counts != c_counts:
        changed = sorted((b_counts - c_counts).elements())[:3]
        return False, (
            f"tie group {index} (score {score}) is not a permutation of the baseline's: "
            f"member(s) present in the baseline and not in the candidate: {changed}"
        ), []

    gained: list[str] = []
    for key in b_counts:
        b_codes: dict[str, set[str]] = {}
        c_codes: dict[str, set[str]] = {}
        for sink, pairs in ((b_codes, b_keys), (c_codes, c_keys)):
            for member_key, codes in pairs:
                if member_key != key:
                    continue
                for name, code in codes.items():
                    sink.setdefault(name, set())
                    if code is not None:
                        sink[name].add(str(code))
        for name in set(b_codes) | set(c_codes):
            before = b_codes.get(name, set())
            after = c_codes.get(name, set())
            lost = sorted(before - after)
            if lost:
                return False, (
                    f"tie group {index} (score {score}): reason code(s) {lost} present in "
                    f"the baseline's {name!r} and gone in the candidate"
                ), []
            gained.extend(f"{name}={c}" for c in sorted(after - before))
    return True, "", gained


def _compare_equal_score_order(
    baseline: Any, candidate: Any, epsilon: float = 0.0
) -> tuple[str, str]:
    ok_b, groups_b = _tie_groups(baseline, epsilon)
    ok_c, groups_c = _tie_groups(candidate, epsilon)
    if not ok_b or not ok_c:
        return STATUS_REGRESSION, (
            "declared equal-score-order but the value is not a score-tagged list of "
            "numeric-scored items ([{score, value}, ...])"
        )
    # The partition is a MEANS, not the end: what the class permits is "hits whose scores
    # tie may swap". So the partition is only consulted when something actually swapped.
    #
    # Checking it unconditionally is a false-positive generator, measured on the live pair:
    # q07's hits were identical in identity AND order, but the gap between ranks 7 and 8
    # moved from 0.010470 to 0.009788 — across the epsilon — so the partition went from ten
    # singletons to [...,2] and the query read as a REGRESSION with nothing changed. The
    # partition is jitter-sensitive exactly where two hits sit near the epsilon; the
    # delivered order is not. Guarding on "did anything reorder" removes that class of false
    # positive without permitting anything new: an unchanged order has no reordering to
    # allow or refuse.
    order_b = [_split_member(m)[0] for _, members in groups_b for m in members]
    order_c = [_split_member(m)[0] for _, members in groups_c for m in members]
    if order_b != order_c and [len(m) for _, m in groups_b] != [
        len(m) for _, m in groups_c
    ]:
        return STATUS_REGRESSION, (
            f"the tie-group partition differs (baseline "
            f"{[len(m) for _, m in groups_b]} vs candidate "
            f"{[len(m) for _, m in groups_c]} at epsilon {epsilon}) — a hit crossed a "
            "tie-group boundary AND the delivered order changed"
        )
    if order_b == order_c:
        # Nothing reordered. Still run the per-group member/reason-code comparison below so
        # a CHANGED hit at the same position is caught — but pair members positionally,
        # since the two partitions may legitimately differ in shape.
        groups_b = [(0.0, [m for _, ms in groups_b for m in ms])]
        groups_c = [(0.0, [m for _, ms in groups_c for m in ms])]

    gained: list[str] = []
    reordered = False
    for index, ((score, members_b), (_, members_c)) in enumerate(zip(groups_b, groups_c)):
        if len(members_b) != len(members_c):
            return STATUS_REGRESSION, (
                f"tie group {index} (score {score}) has {len(members_b)} members in the "
                f"baseline and {len(members_c)} in the candidate"
            )
        ok, reason, group_gained = _compare_tie_group(members_b, members_c, index, score)
        if not ok:
            return STATUS_REGRESSION, reason
        gained.extend(group_gained)
        if [_split_member(m)[0] for m in members_b] != [
            _split_member(m)[0] for m in members_c
        ]:
            reordered = True

    if gained:
        return (
            "allowed:new-reason-code",
            f"new reason code(s) on an otherwise unchanged hit set: {sorted(set(gained))}"
            + (" (equal-score hits also reordered)" if reordered else ""),
        )
    return (
        "allowed:equal-score-order",
        "equal-score hits reordered within their tie group; every hit is otherwise identical",
    )


def compare_field(
    klass: str, baseline: Any, candidate: Any, epsilon: float = 0.0
) -> tuple[str, str]:
    """Compare one field under its declared class. Returns ``(status, reason)``.

    Raises :class:`WorkflowFixtureError` on a class name outside the closed set — the
    differ refuses an unknown class rather than defaulting to permissive.
    """
    if klass != EXACT and klass not in ALLOWED_DIFFERENCE_CLASSES:
        raise WorkflowFixtureError(
            f"unknown allowed-difference class {klass!r}; the closed set is "
            f"{list(ALLOWED_DIFFERENCE_CLASSES)} (or {EXACT!r})"
        )
    if _canonical(baseline) == _canonical(candidate):
        return STATUS_EQUAL, "byte-equal"

    if klass == EXACT:
        return STATUS_REGRESSION, "declared exact but the value differs"

    if klass == "generative-text":
        return f"allowed:{klass}", "generative text differs (no fixed seed on the chat path)"

    if klass == "new-reason-code":
        before = _reason_codes(baseline)
        after = _reason_codes(candidate)
        lost = sorted(before - after)
        if lost:
            return (
                STATUS_REGRESSION,
                f"reason code(s) present in the baseline and gone in the candidate: {lost}",
            )
        gained = sorted(after - before)
        return f"allowed:{klass}", f"new reason code(s) only in the candidate: {gained}"

    return _compare_equal_score_order(baseline, candidate, epsilon)


def _records(doc: dict, section: str) -> dict[str, dict]:
    value = doc.get(section) or {}
    return value if isinstance(value, dict) else {}


def capture_health(
    baseline: dict, candidate: dict, declared_not_captured: list[str]
) -> dict:
    """Assert the two captures actually recorded something before believing their diff.

    Two identical *failures* are byte-equal, so without this an all-401 backend (or a
    half-captured fixture) diffs clean and reads as "no semantic regression". The health
    block is part of the verdict, not a warning: a diff whose health fails does not pass.
    """
    problems: list[str] = []
    for section in ("queries", "chatTurns"):
        if not _records(baseline, section) and not _records(candidate, section):
            problems.append(
                f"section {section!r} is empty in BOTH captures — nothing was compared "
                "(a --skip-chat capture is not diffable)"
            )
    if declared_not_captured:
        problems.append(
            "declared in the fixture but never captured: "
            f"{declared_not_captured} — the capture projection and the declaration table "
            "have drifted"
        )
    for label, doc in (("baseline", baseline), ("candidate", candidate)):
        prov = doc.get("provenance") or {}
        outside = prov.get("pathsOutsideCorpusRoot") or 0
        if outside:
            problems.append(
                f"{label}: path outside corpus root — {outside} captured path(s) do not live "
                f"under {prov.get('corpusRoot')!r} and were replaced with "
                f"{OUTSIDE_ROOT_SENTINEL!r} (e.g. "
                f"{(prov.get('pathsOutsideCorpusRootExamples') or ['?'])[0]}). The corpus "
                "root is wrong, or the index holds documents from outside it"
            )
        for section in ("queries", "chatTurns"):
            for record_id, record in sorted(_records(doc, section).items()):
                if not isinstance(record, dict):
                    continue
                status = record.get("httpStatus")
                if status is not None and status != 200:
                    problems.append(
                        f"{label} {section}/{record_id}: httpStatus {status} — the backend "
                        "rejected or failed this request, so nothing was measured"
                    )
                if section == "queries" and record.get("hitCount") == 0:
                    problems.append(
                        f"{label} queries/{record_id}: hitCount 0 — the backend returned no "
                        "evidence for a fixture query (wrong corpus, or the index is empty)"
                    )
                # A truncated turn's citations are partial BY CONSTRUCTION, so storing one as
                # a baseline bakes in an artefact of the iteration cap. Only the ordinary
                # turns are meant to complete; the cancelled turn is supposed to end early.
                if (
                    section == "chatTurns"
                    and not record.get("cancel.requested")
                    and record.get("disposition") == "MAX_ITERATIONS"
                ):
                    problems.append(
                        f"{label} chatTurns/{record_id}: chat turn ended MAX_ITERATIONS after "
                        f"{record.get('iterationsUsed')} iterations — the ordinary turns are "
                        "meant to COMPLETE, so its citations/sources are partial. Raise the "
                        "turn's maxIterations or simplify the question; do not store this as "
                        "a baseline"
                    )
    return {"ok": not problems, "problems": problems}


def diff(baseline: str | Path | dict, candidate: str | Path | dict, fixture: dict) -> dict:
    """Diff two captures under the fixture's declared equality relation.

    ``baseline`` / ``candidate`` are capture paths or already-loaded capture documents.
    Returns a structured result; ``pass`` is True only when nothing is ``REGRESSION`` or
    ``missing``.
    """
    declarations = validate_fixture(fixture)
    epsilon = float(fixture["scoreTieEpsilon"])
    base = baseline if isinstance(baseline, dict) else json.loads(
        Path(baseline).read_text(encoding="utf-8"))
    cand = candidate if isinstance(candidate, dict) else json.loads(
        Path(candidate).read_text(encoding="utf-8"))

    entries: list[dict] = []
    captured_paths: set[str] = set()
    for section in ("queries", "chatTurns"):
        b_recs = _records(base, section)
        c_recs = _records(cand, section)
        for record_id in sorted(set(b_recs) | set(c_recs)):
            b_rec = b_recs.get(record_id)
            c_rec = c_recs.get(record_id)
            field_names = sorted(set(b_rec or {}) | set(c_rec or {}))
            for name in field_names:
                path = f"{section}.{name}"
                captured_paths.add(path)
                entry: dict = {"record": f"{section}/{record_id}", "field": path}
                klass = declarations.get(path)
                if klass is None:
                    entry.update(
                        status=STATUS_REGRESSION,
                        reason="field is not declared in the fixture's `fields` table; "
                               "an undeclared field is a regression by construction",
                    )
                    entries.append(entry)
                    continue
                entry["class"] = klass
                if b_rec is None or name not in b_rec:
                    entry.update(status=STATUS_MISSING, reason="absent from the baseline capture")
                elif c_rec is None or name not in c_rec:
                    entry.update(status=STATUS_MISSING, reason="absent from the candidate capture")
                else:
                    status, reason = compare_field(
                        klass, b_rec[name], c_rec[name], epsilon)
                    entry.update(status=status, reason=reason)
                    if status == STATUS_REGRESSION:
                        entry["baseline"] = b_rec[name]
                        entry["candidate"] = c_rec[name]
                entries.append(entry)

    counts = {"equal": 0, "allowed": 0, STATUS_REGRESSION: 0, STATUS_MISSING: 0}
    for entry in entries:
        status = entry["status"]
        if status == STATUS_EQUAL:
            counts["equal"] += 1
        elif status.startswith("allowed:"):
            counts["allowed"] += 1
        else:
            counts[status] += 1

    by_class = {k: 0 for k in ALLOWED_DIFFERENCE_CLASSES}
    for entry in entries:
        if entry["status"].startswith("allowed:"):
            by_class[entry["status"].split(":", 1)[1]] += 1

    declared_not_captured = sorted(set(declarations) - captured_paths)
    health = capture_health(base, cand, declared_not_captured)

    return {
        "schema": DIFF_SCHEMA,
        "fixture_id": fixture.get("id"),
        "fixture_version": fixture.get("version"),
        "pass": (
            counts[STATUS_REGRESSION] == 0
            and counts[STATUS_MISSING] == 0
            and health["ok"]
        ),
        "counts": counts,
        "allowed_by_class": by_class,
        "health": health,
        "baseline_provenance": base.get("provenance"),
        "candidate_provenance": cand.get("provenance"),
        "declared_not_captured": declared_not_captured,
        "fields": entries,
    }


def failures(result: dict) -> list[dict]:
    """The entries that make a diff result fail (regressions + missing fields)."""
    return [
        e for e in result.get("fields", [])
        if e["status"] in (STATUS_REGRESSION, STATUS_MISSING)
    ]
