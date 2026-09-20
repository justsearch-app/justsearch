# Corrected live composition, 2026-09-21

Revision `87195eac9`, Windows, owned dev run
`4c66c8ba-eb33-4c5c-9952-fca99f468d42`, API port50088. Fresh data is retained in
`tmp/lane-f-resume-live1982` in the Lane F worktree through acceptance plus30 days;
export before worktree release. Startup rebuilt/installed this revision. Health
confirmed fresh artifacts and the standard Qwen3.5-9B model. This is local live
proof, not installed process-death proof.

## Actual outcomes

- The dev MCP ingestion of `examples/onramp-corpus` first returned428 with frozen
  preparation and no accepted row. Opening the normal shell and repeating the same
  key presented its typed-confirm ceremony. The one-time approval was executed in
  the browser; no always-allow grant was requested. Parent1 and child2 are both
  DURABLE/COMPLETE, MCP attribution and capsule basis retained, attempts1, five
  successful units and zero failed. Child receipt revision9 is sealed and exactly
  acknowledged. Logs/results: tmp/1984-ingest.json, tmp/1985-ingest-retry.json,
  tmp/1986-ingest-outcome.json, tmp/1987-live-operations.json, tmp/1990-walks.json.
- jseval lexical search finds the corpus. The existing jseval ingest-bench registers
  the same public folder for the reindex test and confirms readiness at10 total
  documents (five bundled help plus five corpus). It adds zero new documents because
  they were already indexed; its33.8seconds include the watcher-observation window.
  This is readiness evidence, not a throughput claim. tmp/1987-search.json and
  tmp/1989-jseval/ingest-bench.json preserve results.
- The Library Reindex action records parent10/child11 as DURABLE/COMPLETE with
  attempts1. Zero new successful/failed units is expected: the sealed receipt reports
  five current skipped units for unchanged files; revision9 is acknowledged. See
  tmp/1997-reindex-family.json, tmp/1999-reindex-family.json and
  tmp/1999-walks-before.json. This is incremental reindex; forced reindex remains
  covered locally by the existing four-case producer composition tests.
- Retained ingestion retry returns original row1 and five completed units. Changed
  collection returns409 OPERATION_KEY_REUSED. Exact complete parent/child rows and
  the full jobs/walk row sets are unchanged across the later repeated checks.
  tmp/2000-retained-retry.json, tmp/2000-conflict.json and
  tmp/2001-{jobs,walks}-after.json; runnable check tmp/2001-check-retry.py.
- The real model query asks who led the Great Cinnamon Heist. Standard
  Qwen_Qwen3.5-9B-Q4_K_M.gguf answers Captain Mortimer Flux: one query, zero errors,
  exact accuracy1.0. tmp/1995-model-query/tier2-eval.json and
  tmp/1995-model-query.txt. This is a one-query functionality probe, not a quality
  benchmark. Initial1991 used the default8080 port and failed before any query; the
  actual owned llama process was verified on8082, then1995 succeeded.

The first1997 queue snapshot used a nonexistent jobs.id column and failed before
capturing its baseline. No queue-unchanged claim uses it:1999 reads the actual path
key, then2000 repeats the retry/conflict and2001 compares exact rows. The UI opened
only after the first MCP announcement; this event-only bridge showed the ceremony
on a same-key repeat with a fresh pending id. No source edit was made during this
live run. Explicit sessionId on dev calls establishes callerIsOwner=true; omitting
it made the generic MCP client report a mismatched caller despite the correct
holder session id. Do not infer takeover needs from that omitted identity.

## Discovered alias gap and remaining proof

`POST /api/indexing/reindex` still reaches IndexingController.handleReindex's direct
indexing service and flush, returning status/force without an operation key
(tmp/1992-reindex.json). It is not the Library's recorded core.reindex path.
The dev tool also does not allowlist generic operation invoke
(tmp/1994-recorded-reindex.json); that refusal produced no effect. Audit the existing
alias's owning C2 contract and route it through the existing prepared dispatcher
where required; preserve its query-force contract and retire the bypass. Do not
credit the old direct response as durable reindex proof.

The live results do not prove a forced client disconnect while a real producer is
running, store reopen, OS death, or installed recovery. Controlled real-front tests
prove request detachment; the both-store simulated process epoch and all six
installed fault scenarios remain required. Owned shutdown result is
`tmp/2002-stop.json`; confirm completion before another build or stack.
