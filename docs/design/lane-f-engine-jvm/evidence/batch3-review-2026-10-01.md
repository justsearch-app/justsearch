# Batch 3 independent review (2026-10-01)

Refute-first static review by Sol (gpt-6.1-sol, no sub-agents) at 5b4560d8c. Verbatim.

One **SHOULD-FIX**; no BLOCKER found. Reviewed HEAD `5b4560d8c`, including `f7b7ae6aa`, `5ccfa74a8`, `d621c56ec`, and `4a0cdd15b`. Static review only; no tests executed.

- **SHOULD-FIX — [KnowledgeServer.java:5075](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:5075):** `closeCandidate()` releases `source` after cleanup steps that catch only `RuntimeException`. In BESIDE preparation, a native-close `LinkageError` skips `source.close()`. `abort()` retains recovery ownership and returns successfully, leaving its captured A holder outstanding. Shutdown then times out draining that holder at `:6431`, before reaching candidate cleanup at `:6474`; graceful shutdown requires a successful recovery retry first. **Fix:** release the captured source in `finally`, preserve cleanup failures and candidate ownership, and add a one-shot cleanup-`Error` regression followed directly by shutdown.

**Sound notes**

- **A:** Capture, fork, and wiring acquisition increment holders; atomic release prevents double decrement. Blue retirement checks both search and ingest runtime identities across current/retired views (`KnowledgeServer.java:7988`); release retries dependent cleanup (`:310`). No additional accounting leak or lock inversion found.
- **A:** Holder drains retain five-second deadlines (`:6267`, `:6316`). A held view produces bounded shutdown failure and retained ownership; ordered shutdown continues and selects hard-stop for unquiesced native ownership. Retained predecessors block another generation allocation (`IndexGenerationManager.java:1854`), preventing unbounded generation churn.
- **B:** Both former BLOCKERs are addressed: B and restored GPU A undergo inference warmup/device verification before publication (`KnowledgeServer.java:4782`, `:5111`); restoration `Error` retains recovery ownership and both causes (`:5063`, `:5187`). Nested, response-only `composition` is documented in `docs/reference/api-contract-map.md:572`.
- **C:** `git show --name-only 4a0cdd15b` contains only tests and an evidence document. Exact-count, exclusion/reacquisition, abrupt-exit, and migration assertions retain their intent; SSOT isolation, argfiles, and Green-publication waits repair fixture conditions.