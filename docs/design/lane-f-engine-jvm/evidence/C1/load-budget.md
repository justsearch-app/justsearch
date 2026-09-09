# Foreground-load measurement client budget

C1 final pacing proof, base7ff787cf378f0b26c124a56f8b8e17536cd57526 plus this item.
The dated design section0 decision precedes implementation. Primary423 remains a failed
zero-error acceptance attempt:80 offered,76 successful and4 unclassified failures. Its saved
server responses include HTTP200 after35.373,45.941 and43.427 seconds. Those receipts prove
that the load client's30s allowance can expire during a supported response; they do not prove
the exact exception type of all four failures, which the old default logging omitted.

Ordinary jseval retrieval already uses90s. KnowledgeClient's RERANK multiplier12.0 permits60s
at the default5s base, including the documented CPU reranker path. Load and first-search
clients now share ordinary retrieval's existing90s constant. Both summaries record
request_timeout_sec. Each load/probe sample still makes one attempt, counts failures and
records successful latency; failures now log their exception type at warning level. Engine
budgets, cancellation, admission limits and E performance thresholds are unchanged. This is
an instrument correction; fresh live evidence must establish the result independently.

## Verification

Windows11 / Python3.13 / Temurin25.0.2. Negative446 fails both new regressions against the old
code: a modeled observed45.941s response is abandoned at30s, and timeout type is not visible.
Restored447 passes61 load/cadence cases. MockTransport inspects HTTPX's actual configured
read timeout; it does not sleep45s or claim live timing proof. A true ReadTimeout still fails
with exactly one attempt. Both summary populations assert the reported allowance.
Build449 passes in5s (3 executed,329 up-to-date). Canonical docs and generated skills are
synchronized; llms, skills, canonical links, module-deps and runtime-config checks pass.

Raw: `tmp/c1-load-budget-negative-446.txt`, `tmp/c1-load-budget-restored-447.txt`,
`tmp/c1-load-budget-build-449.txt`.

Full Python448 completes3639 passed,16 skipped and one failure in408.80s. The existing
runner-count fixture called stop immediately after start, racing its own two-request fake
client sequence; visible warning logging exposed the race (one request reached the fake
before stop). Both finite-sequence runner fixtures now join their self-terminating thread
with a5s bound and assert it exited before collecting the summary. The exact request/error
counts remain unchanged. Focused453 passes all96 load, cadence and retriever cases in0.76s.
This repairs fixture synchronization, not production stopping semantics. A clean committed
full Python rerun remains required and448 is retained as a failed result.
Raw: `tmp/c1-python-suite-448.txt`, `tmp/c1-load-budget-restored-453.txt`.
