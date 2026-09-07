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

    **The tie group straddling the rank-K cutoff is compared as a SET** (K = the query
    spec's declared ``limit``). Equal-score hits beyond K are UNOBSERVED at K, so when
    index-time GPU embedding jitter moves near-tied chunks across the rank-K boundary, a
    swap between the hit at rank K and a tied hit at rank K+1 reads as a *membership* change
    rather than as the tie permutation it is. Measured live on q06 (tie-group score 0.121)
    and q10 (0.236): one member of the BOTTOM tie group differed between two builds, with the
    rest of the group invisible past rank 10. This is still "ordering of equal-score hits",
    now across the K boundary, so it stays inside THIS class — no fourth class is introduced.
    Two halves:

    * CAPTURE asks the backend for ``2 * K`` and stores the top K PLUS the remainder of the
      tie group holding rank K, dropping everything after that group (it was never part of
      the top-K contract). ``hitCount`` keeps its meaning — the TOP-K count,
      ``min(returned, K)``. Making it the compared-slice length would turn a legitimate
      11-vs-12 cutoff-group size into a REGRESSION on an exact field, i.e. re-create the
      failure this change removes. How many hits the backend returned (``hitsCaptured``) and
      how many were kept (``hitsCompared``) go in the capture's NON-diffed ``observed``
      block: both legitimately differ between two builds.
    * DIFF compares every group ABOVE the cutoff group under the unchanged rules (the
      order-guard and the whole-member multiset), and the cutoff group itself as an unordered
      SET over its full observed membership — any permutation is allowed; a member absent
      from the other side's group is a REGRESSION, and the ``new-reason-code`` subset rule
      still applies to members present on both sides. It FAILS CLOSED when the cutoff group
      ran to the end of a FULL 2K response, or when an older capture records no
      ``hitsCaptured``: the group may continue past what was observed, so its membership is
      unproven and a set difference must not be waved through.

    **Consequence a reader must know: the capture now asks the backend for 2K, and ``limit``
    feeds the backend's candidate budget / collapse limit / rerank pool — so the top-K set at
    limit 2K is NOT guaranteed identical to the top-K set at limit K.** Both sides of a pair
    use the same limit, so the diff itself stays sound; but captures taken at the old limit
    are **not** comparable to new ones.

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

The chat path's sampling is pinned by the fixture
-------------------------------------------------
An agent turn is **shape-driven** and never reaches
``ConversationEngine.parseSamplingParams``
(``modules/app-services/src/main/java/io/justsearch/app/services/conversation/
ConversationEngine.java:1151`` — the chat-completions path, not ``POST /api/chat/agent``).
Its sampling is ``SamplingParams.AGENT``, temperature **0.7** / top_p **0.8**
(``modules/app-api/src/main/java/io/justsearch/app/api/SamplingParams.java:175``), handed
back by ``AgentLlmCaller.resolveAgentSampling``
(``modules/app-agent/src/main/java/io/justsearch/agent/AgentLlmCaller.java:282-295``,
applying the run's optional override through ``agentBaseSampling`` at ``:310-322``),
which adds ``tool_choice``/grammar on a forced-tool turn and otherwise returns that
constant unchanged.

PR 0b adds an optional top-level ``sampling`` object on the chat request —
``{"temperature": <double|null>, "top_p": <double|null>, "seed": <long|null>}``, every key
optional, absent/null meaning "no override" — which the backend applies **over** that
constant. The fixture **must** declare a ``sampling`` block
(:func:`validate_fixture` refuses one that is missing or malformed);
:func:`run_chat_turn` merges it into the body of every chat turn and :func:`capture`
records it in the non-diffed ``provenance`` beside ``chatProfile``, so a pair captured
under different sampling is legible after the fact.

**The capture records the REQUESTED pin and the APPLIED one, and the difference is the
point.** ``provenance.sampling`` is only what this capture *asked* for — it writes its own
request back, so run the same fixture against a build predating the override and the artifact
still reads as pinned while the backend silently ignored it. ``provenance.samplingApplied``
is what the run said it would *actually* use: the ``session_started`` frame now echoes
``samplingTemperature`` / ``samplingTopP`` / ``samplingSeed``, each key OMITTED when the
backend resolved no value and **all three absent on a build predating PR 0b**
(``AgentEventPayloads.sessionStartedPayload``). :func:`applied_sampling` reads them per turn,
never defaulting an absent key, and :func:`capture_health` fails a diff when the echo is
missing or when the two sides applied different sampling. A pin that can only be asserted,
never contradicted, is not evidence; only these two can disagree.

The same argument covers the four **boot-time** settings the pair must share
(:data:`PINNED_CONFIG_KEYS`). The capture cannot set them — the orchestrator does, at stack
launch — so :func:`read_config_pins` OBSERVES them from ``GET /api/debug/effective-config``
into ``provenance.pins``, and a missing or disagreeing pin is a capture-HEALTH failure rather
than a diffed row: they are not outputs either build produced, they are the conditions both
were measured under, and a pair captured under different conditions is not a comparison of
two builds at all. (The PR 0b pair run fetched ``/api/config/effective``, which does not
exist — ``tmp/pr0b-pair/effective-config-1.json`` holds the ``NOT_FOUND`` body — so that run
recorded no pins and could not have caught a mismatch.)

Generative text is **still** classified ``generative-text`` rather than "deterministic
under a fixed seed": a seed pins the sampler, not the tool-call trajectory the turn takes.
The fixture's chat turns stay deliberately narrow and factual so that the chat fields
declared ``exact`` (citation targets, source identity, tool names, terminal disposition)
have the best chance of holding. If a chat ``exact`` field diffs, read it as a finding
about the fixture's assumption first — but record it, do not reclassify: no class is added
after a diff is seen.

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
from typing import Any, Iterable, NamedTuple

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

#: The keys the chat request's top-level ``sampling`` override carries (PR 0b). Each is
#: individually optional and ``null`` means "no override" — the backend then keeps
#: ``SamplingParams.AGENT`` (0.7 / 0.8) for that dimension. Declared as a CLOSED set so a
#: typo (``topP``, ``top-p``, ``temp``) is refused at validation rather than silently sent
#: and silently ignored by the backend, which would leave the capture unpinned while the
#: fixture claims it is pinned.
SAMPLING_KEYS = frozenset({"temperature", "top_p", "seed"})

#: The keys a ``chatTurns`` spec may carry — the CLOSED set the capture actually honours.
#: ``id`` (the record key), ``content`` (the user message), ``maxIterations`` and
#: ``cancelAfterEvent`` are read by :func:`run_chat_turn` and :func:`capture`; nothing else is
#: read anywhere. Closed for the same reason :data:`SAMPLING_KEYS` is: a typo (``maxIterms``)
#: would otherwise be silently ignored and the turn would run at the default cap of 3 while
#: the fixture claims 8 — an unpinned run that reads as a pinned one.
CHAT_TURN_KEYS = frozenset({"id", "content", "maxIterations", "cancelAfterEvent"})

#: The ``session_started`` payload keys carrying the sampling the run will ACTUALLY use, mapped
#: to the names ``provenance.samplingApplied`` records them under (the fixture's own
#: :data:`SAMPLING_KEYS` vocabulary, so requested and applied are directly comparable).
#: Each key is OMITTED by the backend when it resolved no value, and ALL THREE are absent on a
#: build predating PR 0b — ``AgentEventPayloads.sessionStartedPayload``
#: (``modules/app-agent-api/src/main/java/io/justsearch/agent/api/AgentEventPayloads.java:
#: 346-358``) writes no key rather than an explicit null, exactly so that "this build does not
#: report applied sampling" is distinguishable from "this run applied none". That absence is
#: the signal; it is never defaulted.
APPLIED_SAMPLING_KEYS = {
    "samplingTemperature": "temperature",
    "samplingTopP": "top_p",
    "samplingSeed": "seed",
}

#: The BOOT-TIME settings both sides of a paired diff must have run under, recorded from
#: ``GET /api/debug/effective-config`` into ``provenance.pins``. They are not request
#: parameters — the orchestrator sets them at stack launch — so the capture cannot pin them,
#: only OBSERVE them, which is why a mismatch is a capture-health failure rather than a field
#: diff. See the ``THE CAPTURE RUN IS PINNED`` fixture note for what each one does.
#:
#: The last five were added after the second pair round, where four queries differed on
#: candidate-pool counts and three on which chunk held the rank-10 slot. Every leg hands fusion
#: a BOUNDED candidate list, and a chunk missing from a leg scores 0.0 for it with the leg's
#: weight still in the denominator (``index.hybrid.chunk_cc_zero_exclude`` inherits
#: ``cc_zero_exclude``, default false), so falling out of one leg costs a chunk that leg's whole
#: weighted share at once. Two of the gates are also STEP FUNCTIONS on a one-document change:
#: leg arbitration flips alpha 0.5 -> 0.7 when the two legs' top-10 doc ids share at most ONE
#: document (Jaccard i/(20-i) < 0.1 reduces to i < 1.82), and the recall-complete splice protects
#: each leg's rank<=10 and EVICTS a fused hit to make room. Both are ON by default.
PINNED_CONFIG_KEYS = (
    "index.vector.exhaustive_search",
    "justsearch.llm.slots",
    "justsearch.rerank.deadline_ms",
    "justsearch.rerank.chunks.deadline_ms",
    # The rerank window and the arena that has to hold it (PR 0b, fourth pair round). These two
    # move TOGETHER or the reranker dies: the window IS the batch, the batch is padded up to a
    # bucket from {4,8,16,24,32,48,64} (`CrossEncoderReranker.BATCH_SIZE_BUCKETS`), and 100 —
    # tried in round 3 — is outside that ladder entirely and exhausted the 2048 MB arena on
    # every query. 40 pads to 48 and 4096 MB restores the SAME arena-per-padded-row the working
    # 20-doc/24-bucket configuration had (85.3 MB).
    "justsearch.rerank.top_k",
    "justsearch.rerank.gpu_mem_mb",
    # Candidate budgets and the two step functions (PR 0b, second pair round).
    "index.hybrid.candidate_limit_max",
    "index.hybrid.chunk_collapse_limit_multiplier",
    "index.hybrid.leg_arbitration_enabled",
    "index.hybrid.leg_recall_complete_enabled",
)

#: Recorded into ``provenance.pins`` WHEN SET, but never required. The CPU-execution-provider
#: keys make the encoders bit-deterministic (CUDA kernels reduce in a nondeterministic order), and
#: they work — but MEASURED on this corpus they are too slow to capture with: after the 15-minute
#: enrichment wait, document embeddings were at 38% and 0 of 1,283 chunks were embedded at four
#: intra-op threads, so a cycle exceeds an hour. They stay documented instruments for anyone who
#: wants a bit-stable capture and can spend the time; a GPU capture is not failed for lacking them.
#: GPU encoder jitter is handled instead by the NOISE PAIR, which measures each field's noise
#: rather than assuming it away.
OPTIONAL_CONFIG_PINS = (
    "justsearch.embed.gpu.enabled",
    "justsearch.splade.gpu_enabled",
    "justsearch.ner.gpu_enabled",
    "justsearch.rerank.gpu.enabled",
    "justsearch.bgem3.gpu_enabled",
    "justsearch.onnxruntime.intra_op_threads",
)

#: Cutoffs that are HARD-CODED and therefore cannot be pinned by any env setting. Recorded here
#: so a reader does not conclude the capture is fully non-truncating: it is not, and cannot be
#: without a product change.
#:
#: * ``SearchExecutor.CHUNK_INITIAL_CANDIDATE_MULTIPLIER = 10`` and ``CHUNK_RETRY_MULTIPLIER = 2``
#:   (SearchExecutor.java:63-64) — at the pinned wire limit of 100 the chunk legs see 1000 chunks,
#:   which is AT this corpus's chunk count rather than above it.
#: * ``SearchPlanner.MAX_LIMIT = 100`` (SearchPlanner.java:37) — caps the wire limit, so branch
#:   fusion emits at most 100 documents, BELOW a 102-document corpus. This is a hard ceiling.
#: * ``HybridSearchOps.ARBITRATION_TOP_K`` / ``ARBITRATION_OVERLAP_MAX`` /
#:   ``ARBITRATION_DENSE_CONFIDENT_MIN`` (HybridSearchOps.java:58, 74, 72) — the arbitration's
#:   shape can only be turned OFF (which the pins above do), never widened.
UNPINNABLE_CUTOFFS = (
    "SearchExecutor.CHUNK_INITIAL_CANDIDATE_MULTIPLIER",
    "SearchExecutor.CHUNK_RETRY_MULTIPLIER",
    "SearchPlanner.MAX_LIMIT",
)

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

def validate_sampling(sampling: Any) -> dict:
    """Return the fixture's ``sampling`` override, raising unless it is well-formed.

    REQUIRED, not optional. Without it the chat turns run under ``SamplingParams.AGENT``
    (temperature 0.7, top_p 0.8 —
    ``AgentLlmCaller.resolveAgentSampling``/``SamplingParams.java:175``), and a capture
    taken that way cannot support the chat fields the fixture declares ``exact``: the two
    sides of a pair differ because the sampler differed, not because the build did. An
    EMPTY block is refused for the same reason — it declares a pin and pins nothing.

    A block whose values are ALL null is refused for the same reason an empty one is: every
    key means "no override", so it declares a pin and pins nothing.

    Unknown keys are refused rather than forwarded: the backend ignores what it does not
    recognise, so a typo would leave the run unpinned while the artifact claims otherwise.
    """
    if not isinstance(sampling, dict) or not sampling:
        raise WorkflowFixtureError(
            "fixture must declare a non-empty `sampling` block pinning the agent's "
            'sampling for the capture (e.g. {"temperature": 0.0, "seed": 20260907}). '
            "Without it every chat turn samples under SamplingParams.AGENT "
            "(temperature 0.7, top_p 0.8) and the chat fields declared `exact` diff for a "
            "reason that is not a build difference."
        )
    unknown = sorted(set(sampling) - SAMPLING_KEYS)
    if unknown:
        raise WorkflowFixtureError(
            f"fixture `sampling` declares unknown key(s) {unknown}; the chat request's "
            f"sampling override carries exactly {sorted(SAMPLING_KEYS)} and the backend "
            "silently ignores anything else, which would leave the capture unpinned"
        )
    if all(sampling.get(key) is None for key in sampling):
        raise WorkflowFixtureError(
            "fixture `sampling` block declares a pin and pins NOTHING — every value is null, "
            "and null means 'no override' on each key individually, so the chat turns still "
            "run under SamplingParams.AGENT (temperature 0.7, top_p 0.8). Give at least one "
            'key a value (e.g. {"temperature": 0.0, "seed": 20260907}), or drop the block '
            "and let validation refuse it, rather than claiming a pin the capture does not "
            "have."
        )
    for key in ("temperature", "top_p"):
        value = sampling.get(key)
        if value is None:
            continue
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise WorkflowFixtureError(
                f"fixture `sampling.{key}` must be a number or null, got {value!r}")
    seed = sampling.get("seed")
    if seed is not None and (isinstance(seed, bool) or not isinstance(seed, int)):
        raise WorkflowFixtureError(
            f"fixture `sampling.seed` must be an integer or null, got {seed!r}")
    return dict(sampling)


def validate_fixture(fixture: dict) -> dict[str, str]:
    """Return the declaration table, raising unless the fixture is well-formed.

    Refuses a class name outside :data:`ALLOWED_DIFFERENCE_CLASSES` — the "exactly three
    classes, nothing else" rule is enforced here rather than trusted — and refuses a
    fixture with no ``sampling`` block: an unpinned capture samples the chat turns under
    ``SamplingParams.AGENT`` (0.7 / 0.8) and the chat fields declared ``exact`` then diff
    for a reason that is not a build difference.
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
    validate_sampling(fixture.get("sampling"))
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
            # A chat-turn spec is validated against a CLOSED key set: the capture reads
            # exactly CHAT_TURN_KEYS and silently ignores anything else, so `maxIterms: 8`
            # would leave the turn running at the default cap of 3 while the fixture reads as
            # if it declared 8. Refused here rather than discovered as a MAX_ITERATIONS
            # capture-health failure with no visible cause.
            if key == "chatTurns":
                unknown = sorted(set(item) - CHAT_TURN_KEYS)
                if unknown:
                    raise WorkflowFixtureError(
                        f"chat turn {ident!r} declares unknown key(s) {unknown}; a chat-turn "
                        f"spec carries exactly {sorted(CHAT_TURN_KEYS)} and the capture reads "
                        "nothing else, so anything here is silently ignored"
                    )
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


def _is_number(value: Any) -> bool:
    return not isinstance(value, bool) and isinstance(value, (int, float))


def _within(score: float, anchor: float, epsilon: float) -> bool:
    """``|score - anchor| <= epsilon``, INCLUSIVE at the boundary.

    The slack is not cosmetic: ``abs(0.99 - 1.00)`` is ``0.010000000000000009`` in IEEE
    754, so a bare ``<= 0.01`` puts an exactly-at-epsilon hit in a different tie group
    depending on representation error — the partition would then be decided by which
    decimal literals the backend happened to emit rather than by the declared threshold.
    """
    return abs(score - anchor) <= epsilon * (1.0 + 1e-9) + 1e-12


def _tie_index_groups(scores: list[Any], epsilon: float) -> list[list[int]]:
    """Partition consecutive POSITIONS into within-``epsilon`` tie groups.

    THE one implementation of the grouping rule, shared by the capture (which slices the
    cutoff group out of a raw ``results`` list) and the differ (:func:`_tie_groups`, which
    groups a stored score-tagged list). A second copy would let the two sides disagree about
    where the cutoff group ends, which is exactly the disagreement the cutoff rule removes.

    A hit joins the current group when its score is within ``epsilon`` of that group's FIRST
    member — not of its predecessor, so a chain of small steps cannot accumulate into an
    arbitrarily wide group. A non-numeric score starts a singleton and lets nothing join it;
    judging it a shape violation is the differ's business, not the partition's.
    """
    groups: list[list[int]] = []
    anchor: Any = None
    for index, score in enumerate(scores):
        if groups and anchor is not None and _is_number(score) and _within(
            score, anchor, epsilon
        ):
            groups[-1].append(index)
        else:
            groups.append([index])
            anchor = score if _is_number(score) else None
    return groups


#: The trace stage whose per-hit score DECIDES the delivered order. `KnowledgeSearchEngine`
#: applies `applyRerankOrder(results, orderToApply, topK)` (:1077), so the list arrives in the
#: cross-encoder's order, while the score ON the hit is the pre-rerank fusion score, freshness-
#: decayed afterwards without re-sorting (`SearchResultMapper.java:129-134`). The CE's own score
#: is appended as this stage by `SearchTraceMapper.mapHitStages` (:44-51) and is NOT gated by
#: `include_detail` (that gates `detail`, not `score`).
CROSS_ENCODER_STAGE = "cross-encoder"

#: The fusion stage's per-hit score, kept in `observed` for diagnosis only.
FUSION_STAGE = "fusion"

#: What `equal-score-order` grouped on. `cross-encoder` is the delivered sort key; `delivered`
#: is the fallback for a query the CE did not score (a lexical/TEXT fallback, or a hit outside
#: the rerank window).
SCORE_BASIS_CROSS_ENCODER = "cross-encoder"
SCORE_BASIS_DELIVERED = "delivered"


def stage_score(hit: dict, stage_id: str) -> Any:
    """The per-hit score of one trace stage, or None when that stage is absent for this hit."""
    for stage in hit.get("trace") or []:
        if isinstance(stage, dict) and stage.get("id") == stage_id:
            return stage.get("score")
    return None


def resolve_score_basis(hits: list[dict]) -> tuple[str, list[Any]]:
    """``(basis, scores)`` — the scores `equal-score-order` must group on.

    THE DELIVERED ORDER IS THE CROSS-ENCODER'S, so the cross-encoder's per-hit score is the
    only number whose ties mean "these two could legitimately swap". Grouping on the score
    carried on the hit — the pre-rerank fusion score, freshness-decayed after reranking without
    re-sorting — grouped a CE-ordered list by an unrelated key: measured non-monotone in all 12
    queries of both 2026-09-07 captures, 7-11 inversions per 20 hits, so roughly half of every
    "consecutive tie group" was an artefact.

    ALL-OR-NOTHING per query, deliberately. If any hit lacks a CE score the whole query falls
    back to the delivered score. A list mixing two score bases would have epsilon comparing
    numbers from different scales, which is precisely the defect this replaces; a uniform
    fallback is merely conservative (it under-permits, as consecutive grouping already does).
    A query the CE never ran for — a lexical fallback, `trace.effectiveMode` TEXT — is the
    ordinary case for this branch, and `observed.scoreBasis` records it per query.
    """
    if not hits:
        return SCORE_BASIS_CROSS_ENCODER, []
    ce = [stage_score(h, CROSS_ENCODER_STAGE) for h in hits]
    if all(_is_number(v) for v in ce):
        return SCORE_BASIS_CROSS_ENCODER, ce
    return SCORE_BASIS_DELIVERED, [h.get("score") for h in hits]


def compared_slice(
    hits: list[dict], limit: int, epsilon: float, scores: list[Any] | None = None
) -> list[dict]:
    """The top ``limit`` hits PLUS the remainder of the tie group holding rank ``limit``.

    The capture asks the backend for ``2 * limit`` so that this group can be observed WHOLE;
    everything after it is dropped, because it was never part of the top-K contract. Without
    this the group is only PARTLY observed at K and a swap with a member sitting at K+1 reads
    as a membership change rather than the tie permutation it is (measured live on q06 / q10,
    where index-time GPU embedding jitter moved near-tied chunks across the rank-10 cutoff).

    The returned slice always ENDS on a tie-group boundary, which is what lets the differ
    assert that the cutoff group is the last group of the partition.
    """
    if limit <= 0 or len(hits) <= limit:
        return list(hits)
    basis_scores = scores if scores is not None else resolve_score_basis(hits)[1]
    for group in _tie_index_groups(basis_scores, epsilon):
        if group[0] <= limit - 1 <= group[-1]:
            return list(hits[: group[-1] + 1])
    return list(hits[:limit])  # pragma: no cover - every index lands in some group


def score_tagged(
    hits: Iterable[dict], project, scores: list[Any] | None = None
) -> list[dict]:
    """Build the ``equal-score-order`` representation: ``[{score, value}, ...]``.

    Self-contained on purpose — the differ never has to look up a sibling field to know
    which items tie.

    ``scores`` is the basis :func:`resolve_score_basis` chose (the cross-encoder score, the
    delivered sort key). It is passed in rather than re-derived so the capture's slice and the
    stored tags cannot disagree about which number they mean. Omitted, it falls back to the
    score carried on the hit — the pre-cutoff behaviour, kept for the synthetic responses in
    the tests, which have no trace.
    """
    hit_list = list(hits)
    basis = scores if scores is not None else [h.get("score") for h in hit_list]
    return [
        {"score": basis[i], "value": project(h)} for i, h in enumerate(hit_list)
    ]


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
    response: dict,
    *,
    http_status: int = 200,
    paths: CorpusRootRewriter | None = None,
    limit: int = 10,
    epsilon: float = 0.0,
) -> dict:
    """Project one ``/api/knowledge/search`` response onto the declared field set.

    Timing (``tookMs``, ``stages[].ms``, the ``latencyMs`` figures) is dropped here rather
    than declared, so it can never be compared by accident.

    ``limit`` is K — the query spec's declared limit, and the number of hits that are under
    contract. The request asked for ``2 * K``, so ``hits[]`` carries the COMPARED SLICE (top
    K plus the remainder of the tie group holding rank K; see :func:`compared_slice`) while
    ``hitCount`` stays the TOP-K count. The defaults reproduce the pre-cutoff behaviour for
    existing callers: at ``epsilon=0.0`` only byte-equal scores group, so with fewer than K
    hits — every synthetic response in the tests — the slice is the whole list, exactly as
    before.
    """
    all_hits = list(response.get("results") or [])
    # Resolve the grouping basis ONCE, over the full returned list, and thread it into both the
    # slice and the stored tags — deriving it twice would let them disagree about which number
    # "equal score" means, the exact disagreement the cutoff rule exists to remove.
    basis, basis_scores = resolve_score_basis(all_hits)
    hits = compared_slice(all_hits, limit, epsilon, basis_scores)
    trace = response.get("searchTrace") or {}
    stages = list(trace.get("stages") or [])
    degradation = trace.get("degradation") or {}

    def stage_key(stage: dict, idx: int) -> str:
        return f"{idx}:{stage.get('id')}"

    return {
        "httpStatus": http_status,
        # NO "totalHits" and NO "trace.stageCardinality" — both are CANDIDATE-POOL SIZES, not
        # evidence, and both moved between two fresh ingests of one corpus on one build (q02,
        # q03, q07, q09 in the 2026-09-07 pair). Design 16 names evidence selection, truncation
        # points, citation targets and cancellation as the byte-equal fields; it never names
        # either of these. A count of how many candidates a stage happened to consider is a
        # property of the candidate budget and of which chunks sat at a per-leg cutoff, so
        # diffing it reports pool churn as a semantic regression. Both are recorded in the
        # capture's non-diffed `observed` block, where a reader can still see them.
        #
        # `matchCount` STAYS exact: it is an IndexSearcher.count over the query, i.e. how many
        # documents match at all, which is a property of the corpus and the query rather than
        # of any budget.
        "matchCount": response.get("matchCount"),
        # The TOP-K count, NOT the compared-slice length. `hitCount` is a declared, exactly
        # diffed field: making it the slice length would turn a legitimate cutoff-group size
        # difference (11 members vs 12) into a REGRESSION on the very query the cutoff rule
        # exists to stop failing. How many were captured / compared live in the capture's
        # non-diffed `observed` block instead.
        "hitCount": min(len(all_hits), limit) if limit > 0 else len(all_hits),
        # NOTE there is no "hits[].score" field. Scores jitter (max 0.009442 measured over
        # two captures of one build), so a declared score field would be a REGRESSION on
        # every query. The score survives only inside the score-tagged wrapper below, where
        # the differ uses it to derive tie groups and never compares it; the observed values
        # are recorded for the reader in the capture's non-diffed `observed` block.
        # Score-tagged on the CROSS-ENCODER score when the CE scored every hit — the number
        # that decides the delivered order — falling back to the hit's delivered score
        # otherwise. `observed.scoreBasis` records which, per query.
        "hits[]": score_tagged(
            hits, lambda h: hit_projection(h, paths), basis_scores[: len(hits)]
        ),
        "trace.effectiveMode": trace.get("effectiveMode"),
        "trace.decisionKind": trace.get("decisionKind"),
        "trace.stageIds": [s.get("id") for s in stages],
        "trace.stageStatuses": {stage_key(s, i): s.get("status") for i, s in enumerate(stages)},
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


#: Default multiplier for how many hits the capture REQUESTS relative to the compared K.
#: Declared in the fixture as `captureLimitMultiplier`; this is the fallback for a fixture
#: written before the key existed.
DEFAULT_CAPTURE_LIMIT_MULTIPLIER = 4


def capture_limit(spec: dict, fixture: dict | None = None) -> int:
    """How many hits to REQUEST for one query spec: ``K * captureLimitMultiplier``.

    Separate from the compared K on purpose. The request breadth has two jobs the compared
    breadth does not: it must be wide enough for the cutoff tie group to be observed whole, and
    wide enough that the CROSS-ENCODER WINDOW clears the compared K by a margin. The window is
    the request limit (``searchLimit = max(requestedLimit, rerankConfig.topK())``,
    ``KnowledgeSearchEngine.java:625-629``; window = ``min(topK, results.size())``, ``:969-970``),
    so at 2K the window ended exactly at the captured set and a chunk whose FUSION rank jittered
    across rank 20 was reranked on one build and not the other — the three unmatched hits at
    ranks 7-8 of pair run 4. At 4K the window is four times the compared K, so a hit near
    compared rank 10 has to move ~30 fusion ranks to fall out of it.
    """
    k = spec.get("limit", 10)
    multiplier = (fixture or {}).get(
        "captureLimitMultiplier", DEFAULT_CAPTURE_LIMIT_MULTIPLIER)
    return max(k, int(k) * int(multiplier))


def _build_search_body(spec: dict, fixture: dict | None = None) -> dict:
    """The search request for one query spec — asking for ``captureLimitMultiplier * K``, not K.

    K (the spec's declared ``limit``) is still what the capture COMPARES; the extra K is what
    makes the tie group straddling the rank-K cutoff observable whole (see
    :func:`compared_slice`). CAVEAT, recorded because it is not free: ``limit`` also feeds the
    backend's candidate budget / collapse limit / rerank pool, so the top-K set at limit 2K is
    not guaranteed identical to the top-K set at limit K. Both sides of a pair use the same
    limit so the diff stays sound — but a capture taken at the old limit is NOT comparable to
    one taken now.
    """
    body: dict = {"query": spec["query"], "limit": capture_limit(spec, fixture)}
    mode = spec.get("mode")
    if mode:
        body["mode"] = mode
    body["includeExcerpts"] = True
    body["debug"] = True
    return body


def observed_query_scores(response: dict) -> list:
    """The DELIVERED per-hit scores — the freshness-decayed fusion score, not the sort key.

    Named ``scores`` for continuity; it is the number carried on the hit. The number that
    actually orders the list is the cross-encoder's, recorded beside this as
    ``crossEncoderScores`` (see :func:`resolve_score_basis`), and the pre-decay fusion stage
    score as ``fusionScores``. All three are observation only.

    Kept OUT of the query record on purpose: ``diff`` walks ``queries``/``chatTurns`` and
    treats every field there as declared-or-regression, so a jittering score placed among
    them would fail on every query. Putting it in a section the differ does not walk is a
    non-diffed slot by construction — no second "compared/not compared" tier to reason
    about, and no weakening of the undeclared-field rule.
    """
    return [h.get("score") for h in (response.get("results") or [])]


def observed_query_counts(response: dict, record: dict) -> dict:
    """The per-query numbers the differ never compares, for the same ``observed`` block.

    ``hitsCaptured`` is how many hits the backend returned for the ``2 * K`` request;
    ``hitsCompared`` is how many landed in the compared slice (top K plus the rest of the
    cutoff tie group). NEITHER may go in the query record: ``diff`` treats every field in
    ``queries``/``chatTurns`` as declared-or-regression, and both of these legitimately
    differ between two builds — a cutoff group of 11 members on one side and 12 on the other
    is precisely the situation the cutoff rule exists to tolerate. Recording them among the
    diffed fields would manufacture the false positive this change removes.

    The differ still READS ``hitsCaptured`` (never compares it): a cutoff group that ran to
    the end of a full 2K response may continue past what was observed, and that is the case
    the cutoff rule fails closed on.
    """
    trace = response.get("searchTrace") or {}
    stages = list(trace.get("stages") or [])
    all_hits = list(response.get("results") or [])
    basis, _ = resolve_score_basis(all_hits)
    return {
        "hitsCaptured": len(all_hits),
        "hitsCompared": len(record.get("hits[]") or []),
        # WHICH number `equal-score-order` grouped on for this query, and the two it did not.
        # `cross-encoder` is the delivered sort key; `delivered` means the CE did not score
        # every hit (a lexical/TEXT fallback, or hits outside the rerank window) and the whole
        # query fell back — recorded per query because the answer is per query.
        "scoreBasis": basis,
        "crossEncoderScores": [
            stage_score(h, CROSS_ENCODER_STAGE) for h in all_hits
        ],
        "fusionScores": [stage_score(h, FUSION_STAGE) for h in all_hits],
        # Candidate-pool sizes, recorded but NEVER compared (see `capture_query_record`): a
        # reader diagnosing a hit-set difference wants them, and diffing them reports pool
        # churn as a semantic regression.
        "totalHits": response.get("totalHits"),
        "stageCardinality": {
            f"{i}:{s.get('id')}": s.get("cardinality") for i, s in enumerate(stages)
        },
    }


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


#: The enrichment fields recorded verbatim into ``provenance.enrichment``. Chosen because they
#: are what a reader needs to judge a capture after the fact: the index state, each enabled
#: stage's coverage, and the pending counts that say whether work is still outstanding.
ENRICHMENT_STATUS_FIELDS = (
    "indexState",
    "embeddingEnabled",
    "spladeEnabled",
    "nerEnabled",
    "chunkSpladeEnabled",
    "embeddingCoveragePercent",
    "spladeCoveragePercent",
    "chunkVectorCoveragePercent",
    "chunkSpladeCoveragePercent",
    "chunkEmbeddingPendingCount",
    "pendingNerCount",
    "completedNerCount",
    "chunkDocCount",
    "docCount",
)


def read_enrichment(client) -> dict:
    """``provenance.enrichment`` — the index's enrichment state at CAPTURE START.

    A capture taken on a partially enriched index is not a slower capture, it is a capture of a
    DIFFERENT index. The four-capture acceptance proved how badly that hides: side A cycle 1's
    enrichment wait timed out, the capture was taken anyway, and that side then reported 11 of 12
    queries' hits as noisy (26 unmatched hits, max cross-encoder delta 0.0982 against 0.0000 on
    the healthy side) — so the noise mask, which exists to excuse real jitter, silently excused an
    unfinished index instead and the gate passed with 0 regressions.

    ``incompleteReasons`` is the verdict of :func:`readiness._check_pipeline_complete_conditions`,
    IMPORTED rather than restated: the capture must refuse on exactly the predicate the ingest
    wait uses, or the two drift and a capture can satisfy one while failing the other.
    ``expected_doc_count_min=0`` because the doc count is the fixture corpus's business (the
    capture already records docCountAtStart/End), not this check's.
    """
    from .readiness import _check_pipeline_complete_conditions, flatten_status

    try:
        resp = client.get("/api/status", timeout=30.0)
        raw = resp.json() if resp.status_code == 200 else {}
    except Exception as exc:  # noqa: BLE001 - a status failure must not kill the capture
        log.warning("enrichment: GET /api/status failed (%s)", exc)
        return {"incompleteReasons": ["status_unavailable"]}
    snapshot = flatten_status(raw) if isinstance(raw, dict) else {}
    out = {k: snapshot.get(k) for k in ENRICHMENT_STATUS_FIELDS}
    out["incompleteReasons"] = sorted(_check_pipeline_complete_conditions(snapshot, 0))
    return out


def read_config_pins(client: httpx.Client) -> tuple[dict, dict]:
    """``({key: value}, {key: winning source})`` for :data:`PINNED_CONFIG_KEYS`.

    Reads ``GET /api/debug/effective-config`` — the REAL route. The PR 0b pair run fetched
    ``/api/config/effective``, which does not exist: ``tmp/pr0b-pair/effective-config-1.json``
    is the backend's ``NOT_FOUND`` body, so that run recorded no pins at all and its diff
    could not have caught a pin mismatch.

    Shape (``EffectiveConfigController.buildResolvedConfigEntries`` ->
    ``EffectiveConfigEntry``): a top-level ``resolvedConfig`` array of
    ``{key, value, source, ordinal, detail, candidates[]}``. The record is annotated
    ``@JsonInclude(NON_NULL)``, so ``value`` is OMITTED — not null — for a key no source
    supplied. ``value`` is always a STRING (``"true"``, ``"200"``), never a typed scalar.

    A key with no value is recorded as MISSING rather than as a value, because that is what an
    unpinned run looks like: ``index.vector.exhaustive_search`` has no registered default
    (``EnvRegistry.java:1381`` — the two-argument constructor), so a stack launched without
    ``JUSTSEARCH_INDEX_VECTOR_EXHAUSTIVE_SEARCH`` resolves it to nothing and the pin's absence
    becomes a capture-health failure instead of a silent "both sides agree on nothing".

    Best-effort in one direction only: an unreachable or absent endpoint yields EMPTY pins
    rather than an exception, so a capture still gets written — and :func:`capture_health`
    then refuses to pass a diff built on it.
    """
    try:
        resp = client.get("/api/debug/effective-config", timeout=30.0)
        resp.raise_for_status()
        doc = resp.json()
    except (httpx.HTTPError, ValueError) as exc:  # pragma: no cover - live-only path
        log.warning("pins: GET /api/debug/effective-config failed (%s)", exc)
        return {}, {}
    entries = doc.get("resolvedConfig") if isinstance(doc, dict) else None
    if not isinstance(entries, list):
        log.warning("pins: /api/debug/effective-config carries no `resolvedConfig` array")
        return {}, {}
    by_key = {e.get("key"): e for e in entries if isinstance(e, dict)}
    pins: dict = {}
    sources: dict = {}
    # Required pins plus the optional instruments: an optional key that IS set is recorded, so a
    # reader can tell a CPU-encoder capture from a GPU one, but its absence fails nothing.
    for key in tuple(PINNED_CONFIG_KEYS) + tuple(OPTIONAL_CONFIG_PINS):
        entry = by_key.get(key)
        if not isinstance(entry, dict) or entry.get("value") is None:
            continue
        pins[key] = entry.get("value")
        sources[key] = entry.get("source")
    return pins, sources


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


def applied_sampling(frames: list[tuple[str, Any]]) -> dict:
    """The sampling the run ACTUALLY used, read off the ``session_started`` frame.

    Returns ``{"temperature": …, "top_p": …, "seed": …}`` with ``None`` for each key the
    frame omitted. All three ``None`` is the load-bearing case: it means the backend told the
    capture nothing, which is what a build predating PR 0b does — see
    :data:`APPLIED_SAMPLING_KEYS`. :func:`capture_health` fails a diff on it rather than
    reading it as "the run applied no override".
    """
    for event, payload in frames:
        if event == "session_started" and isinstance(payload, dict):
            return {name: payload.get(key) for key, name in APPLIED_SAMPLING_KEYS.items()}
    return {name: None for name in APPLIED_SAMPLING_KEYS.values()}


class ChatTurnCapture(NamedTuple):
    """One agent turn's DIFFED record plus the NON-diffed sampling the run applied.

    Two values rather than one dict on purpose. ``diff`` walks every key of a ``chatTurns``
    record and treats an undeclared one as a REGRESSION by construction, so putting the
    applied sampling on the record would either fail every turn or force a ``fields`` entry
    for something that legitimately differs between two builds — which is precisely the
    signal it exists to carry. It rides to ``provenance.samplingApplied`` instead, where
    :func:`capture_health` compares it as a run precondition.
    """

    record: dict
    sampling_applied: dict


def run_chat_turn(
    client: httpx.Client,
    spec: dict,
    *,
    sampling: dict | None = None,
    session_token: str | None = None,
    timeout: float = 300.0,
    paths: CorpusRootRewriter | None = None,
) -> ChatTurnCapture:
    """Run one agent turn, optionally cancelling it after a named event.

    Returns :class:`ChatTurnCapture` — the captured chat record plus the sampling the backend
    said it would actually use. The cancel is issued **synchronously between two reads of the
    stream**, on a second connection — the run's own stream stays open so the terminal frame
    it ends with is what gets recorded.

    ``sampling`` is the fixture's pin (PR 0b), sent as the request's top-level
    ``sampling`` object and applied by the backend over ``SamplingParams.AGENT``. What comes
    BACK on ``session_started`` is what the backend resolved; the two are recorded separately
    (``provenance.sampling`` vs ``provenance.samplingApplied``) because a build that ignores
    the override is only detectable when they can disagree.
    """
    cancel_after = spec.get("cancelAfterEvent")
    body = {
        "messages": [{"role": "user", "content": spec["content"]}],
        "maxIterations": spec.get("maxIterations", 3),
    }
    if sampling:
        # Passed through verbatim — `validate_sampling` has already refused an unknown key,
        # so nothing here can be a silently-ignored typo. Copied rather than aliased: the
        # same dict is reused for every turn of a capture and must not be mutated by one.
        body["sampling"] = dict(sampling)
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
            return ChatTurnCapture(
                capture_chat_record(
                    [], http_status=http_status, cancel=cancel or None, paths=paths),
                applied_sampling([]),
            )
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

    return ChatTurnCapture(
        capture_chat_record(
            frames, http_status=http_status, cancel=cancel or None, paths=paths),
        applied_sampling(frames),
    )


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
    sampling = validate_sampling(fixture.get("sampling"))
    # The same epsilon the differ uses, so the cutoff group the capture slices out is the
    # cutoff group the differ later finds (see `compared_slice` / `_tie_index_groups`).
    epsilon = float(fixture["scoreTieEpsilon"])
    queries: dict[str, dict] = {}
    chat_turns: dict[str, dict] = {}
    observed_queries: dict[str, dict] = {}
    sampling_applied: dict[str, dict] = {}

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
        enrichment = read_enrichment(client)
        if enrichment.get("incompleteReasons"):
            log.warning(
                "enrichment INCOMPLETE at capture start: %s — this capture will be refused by "
                "capture_health", enrichment["incompleteReasons"])
        chat_profile, ai_runtime_state = read_ai_runtime(client)
        log.info("chat profile %s (ai runtime %s)", chat_profile, ai_runtime_state)
        # SEARCH BEFORE CHAT, and this order is load-bearing, not incidental: the chat turns
        # index their own agent history (measured: docCount 91 -> 102 across one capture's
        # three turns), so running them first would move the index under the queries.
        for spec in fixture["queries"]:
            resp = client.post("/api/knowledge/search", json=_build_search_body(spec, fixture))
            payload = resp.json() if resp.status_code == 200 else {}
            record = capture_query_record(
                payload, http_status=resp.status_code, paths=paths,
                limit=spec.get("limit", 10), epsilon=epsilon)
            queries[spec["id"]] = record
            observed_queries[spec["id"]] = {
                "scores": observed_query_scores(payload),
                **observed_query_counts(payload, record),
            }
            log.info("captured query %s (%d hits, %d compared)",
                     spec["id"], record["hitCount"],
                     observed_queries[spec["id"]]["hitsCompared"])
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
                turn = run_chat_turn(
                    client, spec, sampling=sampling, session_token=session_token,
                    timeout=timeout, paths=paths)
                chat_turns[spec["id"]] = turn.record
                # The APPLIED sampling, beside the REQUESTED one in provenance. Not a diffed
                # field: it legitimately differs between two builds (that is the whole point
                # — a build that ignores the override reports different values, or none at
                # all), so it is compared by `capture_health` as a run precondition.
                sampling_applied[spec["id"]] = turn.sampling_applied
                log.info("captured chat turn %s (terminal=%s, applied sampling %s)",
                         spec["id"], turn.record["terminalEvent"], turn.sampling_applied)
        pins, pin_sources = read_config_pins(client)
        log.info("boot-time pins %s (sources %s)", pins, pin_sources)
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
        # Recorded at capture START, before the chat turns move the index (they index their own
        # agent history). A capture whose enabled stages are not complete is refused by
        # `capture_health` — see `read_enrichment`.
        "enrichment": enrichment,
        "chatProfile": chat_profile,
        "aiRuntimeState": ai_runtime_state,
        # The sampling override sent on every chat turn (PR 0b), recorded for the same
        # reason chatProfile is: it decides what the model produced, so a pair captured
        # under two different pins compares two samplers, not two builds. Never diffed.
        "sampling": sampling,
        # …and what the backend said it would ACTUALLY use, per turn, read off
        # `session_started`. `sampling` above is only what this capture ASKED for: run the
        # same fixture against a build predating the override and the artifact still shows it
        # set, because the capture writes its own request back. Only `samplingApplied` can
        # contradict it, so only `samplingApplied` can prove the pin took.
        "samplingApplied": sampling_applied,
        # The four BOOT-TIME settings, observed from /api/debug/effective-config. The capture
        # cannot set these — the orchestrator does, at stack launch — so it records them and
        # `capture_health` refuses a pair that ran under different ones.
        "pins": pins,
        "pinSources": pin_sources,
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


def _tie_groups(
    value: Any, epsilon: float = 0.0
) -> tuple[bool, list[tuple[Any, list[Any]]] | None]:
    """Split a score-tagged list into consecutive within-``epsilon`` groups.

    The grouping rule itself lives in :func:`_tie_index_groups` (shared with the capture's
    :func:`compared_slice`); this adds the shape check the differ needs.

    Returns ``(ok, groups)``. ``ok`` is False on a shape violation — not a score-tagged
    list, or a ``None``/non-numeric score. A missing score is a violation rather than a
    tie: treating it as one would collapse the whole list into a single group whose
    members may then be reordered freely, which is the opposite of what the class means.
    """
    if not isinstance(value, list):
        return False, None
    for item in value:
        if not isinstance(item, dict) or "score" not in item or "value" not in item:
            return False, None
        if not _is_number(item["score"]):
            return False, None
    partition = _tie_index_groups([item["score"] for item in value], epsilon)
    return True, [
        (value[group[0]]["score"], [value[i]["value"] for i in group])
        for group in partition
    ]


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

    return _compare_member_reason_codes(
        b_keys, c_keys, f"tie group {index} (score {score})")


def _compare_member_reason_codes(
    b_keys: list[tuple[str, dict[str, Any]]],
    c_keys: list[tuple[str, dict[str, Any]]],
    label: str,
) -> tuple[bool, str, list[str]]:
    """Apply the ``new-reason-code`` subset rule to members already paired on their exact keys.

    Shared by the ordinary tie-group comparison (:func:`_compare_tie_group`) and the cutoff
    group's set comparison (:func:`_compare_cutoff_group`) — the rule is the same in both
    places, only the membership test that precedes it differs.

    Returns ``(ok, reason, gained_codes)``.
    """
    keys: list[str] = []
    for key, _ in b_keys:
        if key not in keys:
            keys.append(key)
    gained: list[str] = []
    for key in keys:
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
        for name in sorted(set(b_codes) | set(c_codes)):
            before = b_codes.get(name, set())
            after = c_codes.get(name, set())
            lost = sorted(before - after)
            if lost:
                return False, (
                    f"{label}: reason code(s) {lost} present in "
                    f"the baseline's {name!r} and gone in the candidate"
                ), []
            gained.extend(f"{name}={c}" for c in sorted(after - before))
    return True, "", gained


class CutoffContext(NamedTuple):
    """What the differ needs to apply the cutoff-group rule to one query's ``hits[]``.

    ``limit`` is K, read from the query spec (``diff`` already has the fixture and the record
    id, exactly as it already does for ``epsilon``). The two ``hits_captured`` figures are
    read from each capture's NON-diffed ``observed`` block — read, never compared — and are
    what tells the differ whether the cutoff group could still extend past what was observed.
    ``None`` on either side means an older capture that did not record it, which fails closed.
    """

    limit: int
    baseline_hits_captured: int | None = None
    candidate_hits_captured: int | None = None


def _cutoff_group_index(
    groups: list[tuple[Any, list[Any]]], limit: int | None
) -> int | None:
    """Index of the tie group holding flattened rank ``limit`` (0-based ``limit - 1``).

    ``None`` when there is no cutoff to speak of: no limit, or a compared slice with fewer
    than ``limit`` members (the backend simply returned fewer hits than the contract asks
    for, so nothing sits at the boundary).
    """
    if limit is None or limit <= 0:
        return None
    if sum(len(members) for _, members in groups) < limit:
        return None
    seen = 0
    for index, (_, members) in enumerate(groups):
        seen += len(members)
        if seen >= limit:
            return index
    return None  # pragma: no cover - the total was already checked


def _cutoff_membership_unproven(
    hits_captured: int | None, compared: int, limit: int
) -> bool:
    """True when the cutoff group may extend past what the backend actually returned.

    The capture asks for ``2 * limit``. If the compared slice runs to the LAST hit returned
    **and** the backend returned the full ``2 * limit`` it was asked for, the tie group can
    continue past rank 2K and its membership is unproven — a member "absent from the other
    side" may simply be one this capture never saw. An older capture with no ``hitsCaptured``
    is unproven for the same reason: there is no evidence either way. Both fail CLOSED.
    """
    if not isinstance(hits_captured, int) or isinstance(hits_captured, bool):
        return True
    return compared >= hits_captured and hits_captured >= 2 * limit


def _compare_cutoff_group(
    baseline: list[Any], candidate: list[Any], cutoff: CutoffContext,
    compared_b: int, compared_c: int,
) -> tuple[bool, str, list[str]]:
    """Compare the cutoff tie group as an unordered SET over its full observed membership.

    Any permutation is allowed — that is the whole point, since a hit at rank K and a tied
    hit at rank K+1 swapping places is "ordering of equal-score hits" across the K boundary,
    not a membership change. A member ABSENT from the other side's group is still a
    REGRESSION, and the ``new-reason-code`` subset rule still applies to the members present
    on both sides.

    Returns ``(ok, reason, gained_codes)``.
    """
    b_keys = [_split_member(m) for m in baseline]
    c_keys = [_split_member(m) for m in candidate]
    b_set = {key for key, _ in b_keys}
    c_set = {key for key, _ in c_keys}
    if b_set != c_set:
        unproven = (
            _cutoff_membership_unproven(
                cutoff.baseline_hits_captured, compared_b, cutoff.limit)
            or _cutoff_membership_unproven(
                cutoff.candidate_hits_captured, compared_c, cutoff.limit)
        )
        reason = (
            f"the cutoff tie group (the group holding rank {cutoff.limit}) is not the same "
            f"SET on both sides: member(s) only in the baseline "
            f"{sorted(b_set - c_set)[:3]}; only in the candidate {sorted(c_set - b_set)[:3]}"
        )
        if unproven:
            # FAIL CLOSED. The group ran to the end of a full 2K response (or the capture
            # does not record how many hits it saw), so it may continue past what was
            # observed and the set difference cannot be told apart from an unseen member.
            if not isinstance(cutoff.baseline_hits_captured, int) or not isinstance(
                cutoff.candidate_hits_captured, int
            ):
                cause = (
                    "a capture does not record `hitsCaptured` in its `observed` block (an "
                    "older capture), so nothing says where the group really ends"
                )
            else:
                cause = (
                    f"the group runs to the end of what was captured (baseline "
                    f"{cutoff.baseline_hits_captured}, candidate "
                    f"{cutoff.candidate_hits_captured} hits for a request of "
                    f"{2 * cutoff.limit})"
                )
            reason += (
                f" — and {cause}, so its membership past the capture is UNPROVEN and this "
                "fails closed rather than being read as a permitted permutation"
            )
        return False, reason, []
    return _compare_member_reason_codes(
        b_keys, c_keys, f"cutoff tie group (rank {cutoff.limit})")


def _compare_ordered_groups(
    groups_b: list[tuple[Any, list[Any]]],
    groups_c: list[tuple[Any, list[Any]]],
    epsilon: float,
) -> tuple[str | None, str, list[str], bool]:
    """The unchanged tie-group rules, applied to a run of groups.

    Returns ``(regression_status_or_None, reason, gained_codes, reordered)``. Called with the
    WHOLE partition when there is no cutoff group, and with the groups strictly ABOVE the
    cutoff group when there is one — those keep today's semantics untouched.
    """
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
        ), [], False
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
            ), [], False
        ok, reason, group_gained = _compare_tie_group(members_b, members_c, index, score)
        if not ok:
            return STATUS_REGRESSION, reason, [], False
        gained.extend(group_gained)
        if [_split_member(m)[0] for m in members_b] != [
            _split_member(m)[0] for m in members_c
        ]:
            reordered = True
    return None, "", gained, reordered


def _compare_equal_score_order(
    baseline: Any, candidate: Any, epsilon: float = 0.0,
    cutoff: CutoffContext | None = None,
) -> tuple[str, str]:
    """The ``equal-score-order`` comparison, cutoff group included.

    ``cutoff`` is present only for ``queries.hits[]`` (``chatTurns`` has no rank-K contract).
    With it, the groups strictly ABOVE the cutoff group keep today's rules and the cutoff
    group is compared as a set; without it, the whole partition takes today's rules.
    """
    ok_b, groups_b = _tie_groups(baseline, epsilon)
    ok_c, groups_c = _tie_groups(candidate, epsilon)
    if not ok_b or not ok_c:
        return STATUS_REGRESSION, (
            "declared equal-score-order but the value is not a score-tagged list of "
            "numeric-scored items ([{score, value}, ...])"
        )

    limit = cutoff.limit if cutoff else None
    cut_b = _cutoff_group_index(groups_b, limit)
    cut_c = _cutoff_group_index(groups_c, limit)
    # The capture slices at the cutoff group's LAST member, so that group is the last of the
    # partition by construction. Assert it rather than assume it: a capture taken before the
    # slice existed (or at some other limit) would otherwise be compared under a rule its
    # data cannot support, silently.
    for label, cut, groups in (
        ("baseline", cut_b, groups_b), ("candidate", cut_c, groups_c)
    ):
        if cut is not None and cut != len(groups) - 1:
            return STATUS_REGRESSION, (
                f"the {label} capture's cutoff tie group (the group holding rank {limit}) is "
                f"group {cut} of {len(groups)}, not the last — the capture was not sliced at "
                "the cutoff group, so the membership of that group is only partly observed "
                "and the cutoff rule cannot be applied to it"
            )

    # The cutoff group is split off only when the DELIVERED ORDER actually changed — the same
    # guard that keeps the q07 false positive out (see `_compare_ordered_groups`), applied
    # one level up. Splitting unconditionally would re-create that false positive at the
    # boundary: a hit that merely JOINED the cutoff group without moving leaves the whole
    # order identical but shortens the head partition by one group, which the head's own
    # partition check would then call a regression with nothing reordered.
    #
    # And only when BOTH sides have a cutoff group. One side short of K hits is a real
    # difference that `hitCount` (exact) already reports; here it falls back to the stricter
    # whole-partition rules, which under-permits rather than over-permits.
    tail_b: list[Any] | None = None
    tail_c: list[Any] | None = None
    head_b, head_c = groups_b, groups_c
    reordered_at_all = (
        [_split_member(m)[0] for _, members in groups_b for m in members]
        != [_split_member(m)[0] for _, members in groups_c for m in members]
    )
    if reordered_at_all and cut_b is not None and cut_c is not None:
        head_b, tail_b = groups_b[:cut_b], groups_b[cut_b][1]
        head_c, tail_c = groups_c[:cut_c], groups_c[cut_c][1]

    status, reason, gained, reordered = _compare_ordered_groups(head_b, head_c, epsilon)
    if status is not None:
        return status, reason

    if tail_b is not None and tail_c is not None and cutoff is not None:
        ok, cut_reason, cut_gained = _compare_cutoff_group(
            tail_b, tail_c, cutoff,
            sum(len(m) for _, m in groups_b), sum(len(m) for _, m in groups_c),
        )
        if not ok:
            return STATUS_REGRESSION, cut_reason
        gained.extend(cut_gained)
        if [_split_member(m)[0] for m in tail_b] != [_split_member(m)[0] for m in tail_c]:
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
    klass: str, baseline: Any, candidate: Any, epsilon: float = 0.0,
    cutoff: CutoffContext | None = None,
) -> tuple[str, str]:
    """Compare one field under its declared class. Returns ``(status, reason)``.

    Raises :class:`WorkflowFixtureError` on a class name outside the closed set — the
    differ refuses an unknown class rather than defaulting to permissive.

    ``cutoff`` is threaded the same way ``epsilon`` already is: :func:`diff` holds the fixture
    and the record id, so it can hand down the query spec's ``limit``. It is ``None`` for
    ``chatTurns``, which has no rank-K cutoff.
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

    return _compare_equal_score_order(baseline, candidate, epsilon, cutoff)


def _records(doc: dict, section: str) -> dict[str, dict]:
    value = doc.get(section) or {}
    return value if isinstance(value, dict) else {}


def _observed_hits_captured(doc: dict, record_id: str) -> int | None:
    """``observed.queries.<id>.hitsCaptured``, or ``None`` on an older capture.

    READ, never compared — the ``observed`` block is not walked by :func:`diff`. It is the
    only evidence for whether a cutoff tie group could extend past what the backend returned;
    ``None`` makes :func:`_cutoff_membership_unproven` fail closed.
    """
    node: Any = doc
    for key in ("observed", "queries", record_id, "hitsCaptured"):
        if not isinstance(node, dict):
            return None
        node = node.get(key)
    return node if _is_number(node) and not isinstance(node, float) else None


def _provenance(doc: dict) -> dict:
    prov = doc.get("provenance")
    return prov if isinstance(prov, dict) else {}


def _pin_problems(baseline: dict, candidate: dict) -> list[str]:
    """The four BOOT-TIME pins must be present on both sides and must AGREE.

    A health problem rather than a diffed field, and the distinction is the point: these are
    not things either build produced, they are the conditions the two runs were performed
    under. A pair whose sides ran under different pins is comparing two CONFIGURATIONS, not
    two builds, and every byte-equal field it reports is meaningless — so the verdict has to
    fail wholesale rather than record a difference on one row.
    """
    problems: list[str] = []
    pins: dict[str, dict] = {}
    for label, doc in (("baseline", baseline), ("candidate", candidate)):
        value = _provenance(doc).get("pins")
        pins[label] = value if isinstance(value, dict) else {}
        missing = [k for k in PINNED_CONFIG_KEYS if pins[label].get(k) is None]
        if missing:
            problems.append(
                f"{label}: provenance.pins is missing {missing} — the capture could not prove "
                "the run's boot-time settings. Either the backend does not serve GET "
                "/api/debug/effective-config, or the setting was never set: a stack launched "
                "without JUSTSEARCH_INDEX_VECTOR_EXHAUSTIVE_SEARCH=true resolves "
                "index.vector.exhaustive_search to no value at all (it has no default). "
                "Re-capture with the four pins set at stack launch."
            )
    for key in PINNED_CONFIG_KEYS:
        before = pins["baseline"].get(key)
        after = pins["candidate"].get(key)
        if before is not None and after is not None and before != after:
            problems.append(
                f"the two captures ran under DIFFERENT {key}: baseline {before!r} vs "
                f"candidate {after!r}. A paired diff whose sides ran under different "
                "boot-time settings compares two configurations, not two builds — every "
                "field it reports as equal is meaningless. Re-capture both sides identically."
            )
    return problems


def _applied_sampling_problems(baseline: dict, candidate: dict) -> list[str]:
    """The APPLIED sampling must be reported for every turn, on both sides, and must AGREE.

    ``provenance.sampling`` is only what the capture ASKED for — it writes its own request
    back, so it reads as "pinned" even against a build that never implemented the override.
    ``provenance.samplingApplied`` is what ``session_started`` said the run would really use,
    and its ABSENCE is the pre-PR-0b signal, checked here explicitly: a turn whose applied
    sampling is entirely null means the backend reported nothing, which must FAIL rather than
    be read as "the run applied no override".
    """
    problems: list[str] = []
    applied: dict[str, dict] = {}
    for label, doc in (("baseline", baseline), ("candidate", candidate)):
        value = _provenance(doc).get("samplingApplied")
        if not isinstance(value, dict):
            problems.append(
                f"{label}: provenance.samplingApplied is absent — this capture cannot show "
                "what sampling the run ACTUALLY used, only what it requested. Either the "
                "capture predates the applied-sampling echo, or the backend does. Re-capture "
                "against a build whose session_started frame carries samplingTemperature / "
                "samplingTopP / samplingSeed."
            )
            applied[label] = {}
            continue
        applied[label] = value
        for turn_id in sorted(_records(doc, "chatTurns")):
            turn = value.get(turn_id)
            if not isinstance(turn, dict):
                problems.append(
                    f"{label} chatTurns/{turn_id}: no entry in provenance.samplingApplied, "
                    "so the sampling this turn ran under is unknown"
                )
            elif all(v is None for v in turn.values()):
                problems.append(
                    f"{label} chatTurns/{turn_id}: the session_started frame carried NO "
                    "applied sampling (samplingTemperature / samplingTopP / samplingSeed all "
                    "absent), which is what a build predating PR 0b does — the requested "
                    f"pin {_provenance(doc).get('sampling')!r} was never proved to have been "
                    "applied. Re-capture against a build that echoes it."
                )
    for turn_id in sorted(set(applied["baseline"]) | set(applied["candidate"])):
        before = applied["baseline"].get(turn_id)
        after = applied["candidate"].get(turn_id)
        if isinstance(before, dict) and isinstance(after, dict) and before != after:
            problems.append(
                f"chatTurns/{turn_id}: the two captures APPLIED different sampling — "
                f"baseline {before!r} vs candidate {after!r}. The two sides sampled "
                "differently, so their generative and tool-trajectory fields differ for a "
                "reason that is not a build difference. Re-capture both sides under the same "
                "pin."
            )
    return problems


#: Cross-encoder skip reasons that mean the relevance model was EXPECTED to run and did not.
#: `CrossEncoderSkipReason.isDrop()` (app-api) — a by-design skip is not in this set.
CROSS_ENCODER_DROP_REASONS = frozenset({
    "DEADLINE_EXCEEDED", "RPC_FAILED", "MODEL_NOT_LOADED", "INFERENCE_FAILED", "UNKNOWN",
})


def _cross_encoder_problems(baseline: dict, candidate: dict) -> list[str]:
    """Refuse a capture whose cross-encoder was DROPPED.

    The cross-encoder decides the delivered order. When it is dropped the results keep their
    fusion order, so the capture records a DIFFERENT PIPELINE's output — and because both sides
    of a pair are usually taken on the same stack, both are degraded identically and the diff
    gets QUIETER, not noisier. That is the dangerous direction: pair run 3 read as "2
    regressions, nearly clean" while every query had `cross-encoder: skipped /
    INFERENCE_FAILED`, caused by a JUSTSEARCH_RERANK_TOP_K=100 pin that pushed the CE batch from
    20 to 100 documents into an ONNX Runtime arena failure. A green earned by disabling the
    reranker is worth nothing, so it is a health problem rather than a warning.
    """
    problems: list[str] = []
    for label, capture in (("baseline", baseline), ("candidate", candidate)):
        for record_id, record in sorted(_records(capture, "queries").items()):
            statuses = record.get("trace.stageStatuses") or {}
            reasons = record.get("trace.stageReasons") or {}
            for key, status in sorted(statuses.items()):
                if not key.endswith(":cross-encoder") or status != "skipped":
                    continue
                reason = str(reasons.get(key) or "").upper()
                if reason in CROSS_ENCODER_DROP_REASONS:
                    problems.append(
                        f"{label} queries/{record_id}: the cross-encoder was DROPPED "
                        f"({reason}), so this query's results keep their FUSION order and the "
                        "capture records a degraded pipeline. Both sides of a pair degrade "
                        "together, so the diff gets quieter rather than noisier — a green here "
                        "would be earned by disabling the reranker. If the Worker log shows an "
                        "ONNX Runtime arena allocation failure, raise "
                        "JUSTSEARCH_RERANK_GPU_MEM_MB or lower the rerank window; then "
                        "re-capture."
                    )
    return problems


#: Verdict status for a field the SAME-BUILD noise pair showed is not stable. Suffixed with the
#: side whose noise pair moved (``baseline`` / ``candidate`` / ``both``).
STATUS_NOISY_PREFIX = "noisy-"

#: Default ceiling on the fraction of compared fields a side's noise pair may show as noisy
#: before the gate refuses the whole run. Overridable per fixture as `maxNoisyFraction`.
DEFAULT_MAX_NOISY_FRACTION = 0.10


def _noise_side_status(
    captures: list[dict],
    section: str,
    record_id: str,
    name: str,
    klass: str,
    epsilon: float,
    limit: int | None,
) -> bool:
    """True when ANY TWO of a side's same-build captures disagree on this field.

    Every pair is compared, not just each later capture against the primary. Two captures
    UNDER-SAMPLE: the third gate run had q05 and q12 change their tie-group partition across
    sides while being stable within BOTH sides' two-capture pairs, on one build -- the pair had
    simply not drawn the unstable value yet. Comparing all pairs of N means a field counts as
    stable only when every capture of that side agreed with every other.

    "Disagree" is judged under the fixture's own declared relation, not by byte equality: a tie
    permutation the `equal-score-order` class already allows is not noise, it is the relation
    working. Only a verdict the relation would call a REGRESSION counts.

    A field a capture does not carry is skipped rather than called noisy. That is the
    conservative direction: absent evidence of instability leaves the field in the verdict, so a
    truncated capture can only make the gate stricter, never laxer.
    """
    present = []
    for capture in captures:
        rec = _records(capture, section).get(record_id)
        if isinstance(rec, dict) and name in rec:
            present.append((capture, rec[name]))
    for a in range(len(present)):
        for b in range(a + 1, len(present)):
            cap_a, val_a = present[a]
            cap_b, val_b = present[b]
            cutoff = None
            if section == "queries" and limit is not None:
                cutoff = CutoffContext(
                    int(limit),
                    _observed_hits_captured(cap_a, record_id),
                    _observed_hits_captured(cap_b, record_id),
                )
            status, _ = compare_field(klass, val_a, val_b, epsilon, cutoff)
            if status == STATUS_REGRESSION:
                return True
    return False


def _noise_problems(
    entries: list[dict],
    baseline: dict,
    candidate: dict,
    baseline_noise: list,
    candidate_noise: list,
    max_noisy_fraction: float,
) -> list[str]:
    """Refuse a gate whose noise pair is too loud, or whose noise capture is not comparable.

    Two independent refusals.

    A NOISE FRACTION above the declared ceiling means the instrument is measuring its own
    instability rather than the build's. Every noisy field is excluded from the verdict, so a
    pipeline degraded on both sides — a dropped reranker, a half-finished enrichment — would
    otherwise present as a very quiet diff with most fields silently withdrawn. The ceiling is
    what stops "nothing was compared" from reading like "nothing regressed".

    A noise capture whose PINS, APPLIED SAMPLING or CHAT PROFILE differ from its own side's
    primary is not a same-build noise measurement at all; it is a second configuration, and the
    noise it reports belongs to the configuration change rather than to the build.
    """
    problems: list[str] = []
    compared = [e for e in entries if e.get("class") is not None]
    total = len(compared) or 1
    for side, noise in (("baseline", baseline_noise), ("candidate", candidate_noise)):
        # Gating is engaged for the run as a whole, so a side that brought no second capture
        # cannot be gated: nothing of it was measured for stability, and every one of its fields
        # would count as stable by default. That is the direction that silently passes.
        if not noise:
            problems.append(
                f"no noise captures on {side}: the gate was asked to exclude noise but this "
                "side has only one capture, so nothing of it was measured for stability and "
                "every one of its fields would count as stable by default. Capture this side "
                "at least twice on the same build (fixture-pair.sh <outdir> <profile> <port> 3)."
            )
            continue
        # A field noisy on BOTH sides is noisy on EACH side, so `noisy-both` counts towards both
        # fractions. Counting only a side exclusive noise (the first version of this) understated
        # every side that shares an unstable field with the other: the four-capture acceptance
        # measured baseline 9 exclusive + 6 both and scored the baseline at 9/222 = 4%, under the
        # ceiling, when the honest figure is 15/222 = 6.8% and should have failed.
        noisy = [
            e for e in compared
            if str(e.get("status", "")) in (STATUS_NOISY_PREFIX + side, STATUS_NOISY_PREFIX + "both")
        ]
        fraction = len(noisy) / total
        if fraction > max_noisy_fraction:
            problems.append(
                f"{side}: the same-build noise pair moved {len(noisy)} of {total} compared "
                f"fields ({fraction:.1%}), above the declared maxNoisyFraction of "
                f"{max_noisy_fraction:.1%}. Every noisy field is EXCLUDED from the verdict, so "
                "at this level the gate is withdrawing most of what it was meant to compare and "
                "a quiet result would mean 'almost nothing was checked', not 'nothing "
                "regressed'. Find what is unstable on that side — a dropped reranker, an "
                "unfinished enrichment, a moving index — before trusting any verdict."
            )
    for side, primary, noise in (
        ("baseline", baseline, baseline_noise), ("candidate", candidate, candidate_noise)
    ):
        p_prov = _provenance(primary)
        for index, capture in enumerate(noise, start=2):
            n_prov = _provenance(capture)
            for field in ("pins", "samplingApplied", "chatProfile"):
                if p_prov.get(field) != n_prov.get(field):
                    problems.append(
                        f"{side} capture {index}: provenance.{field} differs from its own "
                        f"primary capture's ({p_prov.get(field)!r} vs {n_prov.get(field)!r}). "
                        "Every capture of a side has to be the SAME build under the SAME "
                        "conditions — otherwise the 'noise' it measures is the condition "
                        "change, and every field it excuses is excused for the wrong reason."
                    )
    return problems


def _enrichment_problems(baseline: dict, candidate: dict) -> list[str]:
    """Refuse a capture taken before its enabled enrichment stages finished.

    This is the guard that was missing when the four-capture acceptance passed with 0
    regressions on a side whose first cycle captured a half-enriched index. The failure mode is
    specifically nasty in a noise-pair gate: an unfinished index makes a side disagree with
    ITSELF, every disagreeing field is then classified noise and withdrawn from the verdict, and
    the run reports success having compared almost nothing. So it is a health refusal, not a
    warning, and it fires on the same predicate the ingest wait uses.
    """
    problems: list[str] = []
    for label, doc in (("baseline", baseline), ("candidate", candidate)):
        enrichment = _provenance(doc).get("enrichment")
        if not isinstance(enrichment, dict):
            problems.append(
                f"{label}: provenance.enrichment is absent — this capture cannot show whether "
                "the index had finished enriching when it was taken. A capture of a partially "
                "enriched index disagrees with itself, and in a noise-pair gate those "
                "disagreements are withdrawn as noise, so the run can report success having "
                "compared almost nothing. Re-capture with a build that records it."
            )
            continue
        reasons = enrichment.get("incompleteReasons")
        if reasons:
            problems.append(
                f"{label}: the index was NOT fully enriched when this capture was taken "
                f"({reasons}). Every enabled stage must be complete with nothing pending — "
                "coverage "
                f"embed={enrichment.get('embeddingCoveragePercent')} "
                f"splade={enrichment.get('spladeCoveragePercent')} "
                f"chunkVector={enrichment.get('chunkVectorCoveragePercent')}, pending "
                f"chunkEmbedding={enrichment.get('chunkEmbeddingPendingCount')} "
                f"ner={enrichment.get('pendingNerCount')}. Re-run the cycle; "
                "fixture-cycle.sh now aborts rather than capturing this state."
            )
    return problems


def capture_health(
    baseline: dict, candidate: dict, declared_not_captured: list[str]
) -> dict:
    """Assert the two captures actually recorded something before believing their diff.

    Two identical *failures* are byte-equal, so without this an all-401 backend (or a
    half-captured fixture) diffs clean and reads as "no semantic regression". The health
    block is part of the verdict, not a warning: a diff whose health fails does not pass.

    It also asserts the two runs' PRECONDITIONS — the four boot-time pins and the sampling
    each turn actually applied (:func:`_pin_problems`, :func:`_applied_sampling_problems`).
    Those are health problems rather than declared fields because they are not outputs either
    build produced; they are the conditions under which both were measured. A pair captured
    under different conditions is not a comparison of two builds at all, so the right verdict
    is "this diff is not evidence", not "field X differs".
    """
    problems: list[str] = []
    problems.extend(_pin_problems(baseline, candidate))
    problems.extend(_applied_sampling_problems(baseline, candidate))
    problems.extend(_cross_encoder_problems(baseline, candidate))
    problems.extend(_enrichment_problems(baseline, candidate))
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


def _load_capture(value):
    if value is None or isinstance(value, dict):
        return value
    return json.loads(Path(value).read_text(encoding="utf-8"))


def _load_captures(value) -> list:
    """Normalise a side's extra captures to a list.

    Accepts a single capture (path or dict) or a sequence of them, so the earlier two-capture
    call shape keeps working while N captures are the default. ``None`` is an empty list: the
    side brought nothing, which `_noise_problems` refuses when gating is engaged.
    """
    if value is None:
        return []
    if isinstance(value, (str, Path, dict)):
        return [_load_capture(value)]
    return [_load_capture(v) for v in value]


def diff(
    baseline: str | Path | dict,
    candidate: str | Path | dict,
    fixture: dict,
    baseline_noise=None,
    candidate_noise=None,
) -> dict:
    """Diff two captures under the fixture-declared equality relation.

    ``baseline`` / ``candidate`` are capture paths or already-loaded capture documents.
    Returns a structured result; ``pass`` is True only when nothing is ``REGRESSION`` or
    ``missing``.

    GATING WITH N SAME-BUILD CAPTURES PER SIDE (design 16 relation refinement). Pass
    ``baseline_noise`` / ``candidate_noise`` -- each the OTHER fresh-corpus captures of that same
    side on the same build, as a list (a single capture is still accepted) -- and the differ
    first asks, per field, whether that field is stable WITHIN a side before asking whether it
    differs BETWEEN sides. A field is noisy on a side when ANY TWO of that side's captures
    disagree; two captures under-sample, which is why the default is three. A field that a side own noise pair already moves is
    reported ``noisy-baseline`` / ``noisy-candidate`` / ``noisy-both``, counted under
    ``counts.noisy``, and excluded from the regression verdict: a difference it shows across
    sides cannot be attributed to the build, because the same build produces that difference
    against itself.

    This replaces ASSUMING determinism with MEASURING it. Five pair rounds established that some
    fields are simply not stable on this stack (index-time GPU embedding jitter moves fusion
    candidates, and no candidate-budget pin removes it), and both alternatives were worse:
    declaring those fields non-exact would blind the gate to real regressions in them forever,
    and pinning the pipeline hard enough to silence them (CPU encoders) costs over an hour per
    cycle and stops measuring the shipping configuration.

    Without the two noise arguments the behaviour is exactly as before.
    """
    declarations = validate_fixture(fixture)
    epsilon = float(fixture["scoreTieEpsilon"])
    max_noisy_fraction = float(fixture.get("maxNoisyFraction", DEFAULT_MAX_NOISY_FRACTION))
    base = _load_capture(baseline)
    cand = _load_capture(candidate)
    base_noise = _load_captures(baseline_noise)
    cand_noise = _load_captures(candidate_noise)
    # Gating is on for the run when EITHER side brought extra captures. A side that brought none
    # is then a health problem rather than a silent pass -- see `_noise_problems`.
    noise_gating = baseline_noise is not None or candidate_noise is not None

    # K per query, so the differ can find the tie group straddling the rank-K cutoff. Threaded
    # exactly as `epsilon` is — `diff` already holds both the fixture and the record id.
    query_limits = {
        spec["id"]: spec.get("limit", 10)
        for spec in fixture.get("queries") or []
        if isinstance(spec, dict) and spec.get("id")
    }

    entries: list[dict] = []
    captured_paths: set[str] = set()
    for section in ("queries", "chatTurns"):
        b_recs = _records(base, section)
        c_recs = _records(cand, section)
        for record_id in sorted(set(b_recs) | set(c_recs)):
            cutoff: CutoffContext | None = None
            if section == "queries" and record_id in query_limits:
                cutoff = CutoffContext(
                    int(query_limits[record_id]),
                    _observed_hits_captured(base, record_id),
                    _observed_hits_captured(cand, record_id),
                )
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
                        klass, b_rec[name], c_rec[name], epsilon, cutoff)
                    limit = query_limits.get(record_id) if section == "queries" else None
                    noisy_sides = [
                        side
                        for side, primary, noise in (
                            ("baseline", base, base_noise),
                            ("candidate", cand, cand_noise),
                        )
                        if noise
                        and _noise_side_status(
                            [primary] + noise, section, record_id, name, klass, epsilon, limit)
                    ]
                    if noisy_sides:
                        # The cross-side verdict is kept for the reader -- it is what the gate
                        # WOULD have said -- but it decides nothing: a field that a build moves
                        # against itself cannot evidence a difference between two builds.
                        marker = "both" if len(noisy_sides) == 2 else noisy_sides[0]
                        entry.update(
                            status=STATUS_NOISY_PREFIX + marker,
                            reason=(
                                "excluded from the verdict: the " + marker + " same-build noise "
                                "pair already moves this field, so a cross-side difference here "
                                "is not attributable to the build (cross-side would have been: "
                                + status + ")"
                            ),
                            crossSideStatus=status,
                            crossSideReason=reason,
                        )
                    else:
                        entry.update(status=status, reason=reason)
                        if status == STATUS_REGRESSION:
                            entry["baseline"] = b_rec[name]
                            entry["candidate"] = c_rec[name]
                entries.append(entry)

    counts = {"equal": 0, "allowed": 0, "noisy": 0, STATUS_REGRESSION: 0, STATUS_MISSING: 0}
    for entry in entries:
        status = entry["status"]
        if status == STATUS_EQUAL:
            counts["equal"] += 1
        elif status.startswith("allowed:"):
            counts["allowed"] += 1
        elif status.startswith(STATUS_NOISY_PREFIX):
            counts["noisy"] += 1
        else:
            counts[status] += 1

    noisy_by_side = {"baseline": 0, "candidate": 0, "both": 0}
    for entry in entries:
        status = str(entry["status"])
        if status.startswith(STATUS_NOISY_PREFIX):
            noisy_by_side[status[len(STATUS_NOISY_PREFIX):]] += 1

    by_class = {k: 0 for k in ALLOWED_DIFFERENCE_CLASSES}
    for entry in entries:
        if entry["status"].startswith("allowed:"):
            by_class[entry["status"].split(":", 1)[1]] += 1

    declared_not_captured = sorted(set(declarations) - captured_paths)
    health = capture_health(base, cand, declared_not_captured)
    if noise_gating:
        noise_problems = _noise_problems(
            entries, base, cand, base_noise, cand_noise, max_noisy_fraction)
        if noise_problems:
            health = dict(health)
            health["problems"] = list(health.get("problems") or []) + noise_problems
            health["ok"] = False

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
        "noisy_by_side": noisy_by_side,
        # How many same-build captures each side actually brought (primary included). Two
        # under-sample: printing it stops a reader inferring a stability claim the run never made.
        "captures_per_side": {
            "baseline": 1 + len(base_noise),
            "candidate": 1 + len(cand_noise),
        },
        "maxNoisyFraction": max_noisy_fraction,
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
