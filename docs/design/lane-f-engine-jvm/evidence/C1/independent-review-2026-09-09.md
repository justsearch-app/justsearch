# Independent C1 review — 2026-09-09

Independent review of the C1 range 9dededdbe..d4dfbb075 is complete (five read-only lanes, every finding
  re-read at the cited lines at d4dfbb075). Act on this before any further C1 or C2 work. Order matters.

  1. Blockers, fix first

  - C1-10 reopened the tempdoc-885 starvation defect. RequestEngineContext.java:33 stamps every HTTP/MCP request
    FOREGROUND; withBudget now wraps the ingest RPC family, so getStatus/getHealthCheck count on the pacing
    gauge (EngineKnowledgeClient.java:556-573, ForegroundLoadGate.java:48-55). The packaged MCP bridge tells
    agents to poll /api/knowledge/status after ingesting (scripts/prod/justsearch-mcp/server.mjs:797-803); each
    poll throttles enrichment. You diagnosed this and fixed only the test harness (EngineTestHarness.java:131);
    EngineForegroundPacingTest:158-164 supplies BACKGROUND itself and so tests the gate, not the front. Fix:
    observer routes (/api/knowledge/status, /api/status, /api/health*, /api/debug/state, /api/diagnostics) get
    BACKGROUND urgency at the front, the way StatusLifecycleHandler.java:483-486 already derives an internal
    context; add a test through the real filter chain asserting ForegroundLoad.startedTotal does not move on a
    status request; add the observer-exclusion probe to adr-0048 in governance/adr-probes.v1.json, and restore
    the history note you deleted at :603-612.
  - BoundedHandoff.java:340: the one-line close() you added on InterruptedException makes a truncated scan
    report success. drainAndClose():257 treats closed as drained, so
    EngineKnowledgeClient.scanRootWork():927-955 returns the last partial progress frame as the terminal result
    with no error. Fix: call fail(...) (which closes and records) instead of close(), and make drainAndClose
    require delivered >= accepted.

  2. Must-fix, in this order

  - RuntimeSession.close() (:742) is now synchronized and holds the monitor across three unbounded waits (wait()
    at :750-753, commitOps.stopCommitTimer() → executor.close() at :769, the crtrt.close() join loop at
    :778-786), while retainTaskLifetime() (:728) needs the same monitor. One stalled search leg blocks every
    search and, via the write barrier held in RunningRuntime.drainAndClose():212-239, every write. Fix: test
    closed before entering the monitor in retainTaskLifetime; run closeResources() outside the monitor; bound
    both loops with a deadline and a WARN naming the outstanding owners.
  - PdfOcrEngine.terminateAndWait (:557-569) is an unbounded, interrupt-immune waitFor(), and
    shutdownAndAwaitTermination (:588) replaced the old 5 s awaitTermination with pool.close(), another
    unbounded wait. Copy PersistentExtractionSandbox.terminateAndWait:546-554 (bounded, returns a boolean) and
    restore the bounded pool await.
  - PdfOcrEngine.renderAndOcr:241 opens a pool per document against the index.pdf-ocr registration whose
    maxInstances = 1 (WorkerExecutorRegistrations.java:88); after one wedged PDF, every later OCR-eligible PDF
    is refused INSTANCE_LIMIT at :336-368 and recorded PARSER_FAILED, discarding structured text already
    extracted at PolicyDrivenTikaExtractor.java:121-122. Open the pool once and kill children per document.
  - EngineKnowledgeClient.closeTransport() (:1063-1071): five closes, no try/finally. One throw leaves names
    registered forever and EngineRoot.compose() can never build a new client (register():74-76), including on
    the terminal-writer recovery path. Aggregate exactly as SearchPerSourceExecutor.close():274-291 does.
  - EngineKnowledgeClient.java:578-592: a worker failure after the budget has already expired, including an
    Error, is dropped with no log because budget.complete() loses the CAS and there is no else. Log at ERROR and
    rethrow Error.
  - PersistentExtractionSandbox.java:316-317: readers.submit can now throw (bounded pool) after the request
    frame is written at :310, with no discard; the slot returns with an unread response and the next document
    can receive the previous document's text. Wrap it in discardAndClassify, and add a correlation id to the
    frame protocol.
  - KnowledgeServerHealthMonitor.java:221-232 and :639-644: a capacity refusal is logged at debug as "monitor
    closing" and the tick loop dies for the process life. Branch on reason() == CLOSED; re-arm on capacity
    refusal (the WorkerMethvinWatcher.java:332-348 pattern).
  - ApiSecurityFilters.java:172-188: the admission filter has no OPTIONS exemption, so CORS preflights consume
    slots and can be refused 429, which the browser reports as an opaque failure; and :436 does not expose
    Retry-After, so the packaged webview cannot read it and falls back to a default that only coincidentally
    equals the register value. Exempt OPTIONS; add Retry-After to Access-Control-Expose-Headers; test both.
  - Governance and sweep debts from C1.md itself: register the RoutingExtractionSandbox logic seam
    (governance/logic-seams.v1.json is untouched; the gate reports the stage-A count of 16); add the
    consult-register region for Executors.new*; delete stages/B.md §10 row 3 (:1018-1021), which
    HeadlessApp.java:1397-1402 now implements; raise ExecutorArchitectureTest's floors from 58/63 to the live
    64/79; separate or mark the aggregate refusal from executor refusals so the harness oracle can tell them
    apart (retrySafe === true on counted 429s in admission-loop.mjs); pin store-recoverability currentVersion to
    SqliteSchema.TARGET_VERSION; make the C1-11 double-release test hold two works and assert inFlight() == 1;
    drive shutdown step 1 through the sequence with the real admission object and assert FROZEN on a later
    admit.

  3. Record-keeping, before the next commit

  - You edited three dated decisions in place: my E runbook E0.1 (two 55-minute runs → three 40-minute runs, in
    a code commit, dd11e372d) and its Q2 ceiling; the section 0 row for the E runbook; and section 16's
    aggregate-bound row. Restore each original sentence, then add a new dated row in section 0 for each change
    with your reasoning. The 40-minute cut shortens the continuous window the heap-growth row exists to observe;
    if you keep it, say why. Never rewrite a dated row again.
  - evidence/C1/ cites 208 tmp/ paths with one hash and zero committed artefacts. Commit the SHA-256 inventory
    of every cited raw file (stage B's b17-recovery-proofs.md shape) and the summary JSON of the last full run.
  - stages/C1.md's status says "integrated stress-enabled build green"; the last recorded full run failed
    (integrated-candidate.md:152-155) and only a 13-case rerun followed. Run the full -PincludeStress=true suite
    on the current head, record it, then correct the status line.
  - Nine of your twelve WIP checkpoint commits are build-red on their own (ff98753ad references
    EngineAdmissionService before 82881bb9a adds it; c14e8ba9d calls constructors 9625d1711 adds;
    adapters-lucene's testFixtures dependency lags its test until f3b2bc852). Do not describe them as per-item
    green anywhere.
  - Get one hosted CI green on the current head and record the run id; the last recorded hosted run fails
    IndexingLedgerCoherenceTest and no run after d237c8b41 is recorded.

  4. Working rules from here

  - One implementer per worktree at a time. Reviewers read-only. Do not run two workers editing the same tree;
    if you need parallelism, cut a branch per worker and merge yourself.
  - Commit per item, build-green on its own commit, push after each. No dirty tree older than an hour.
  - New mechanisms outside the current checklist's items need a dated section 0 row before the code, and the row
    states which stage table entry owns them. Three of your batch-4 additions (scan-progress retention
    semantics with a new expiry response, the persisted CANCELLED terminal, the agent-history reconciliation
    timer) are C2/D2-shaped; note them in C2.md and D2.md §1 so those stages inherit them knowingly.
  - A defect found in production is fixed in production, never in the harness that revealed it.

  What was good, so you keep doing it: the executor registry's typed, synchronous refusal that reaches the
  submitter; deleting HybridSearchOps's swallow-and-go-sequential instead of patching it; Rule 9 keyed on arity
  and method references; the sandbox-failure workflow test; the confinement rule tighter than specified and
  mutation-proven; the C1-9 oracle refusing its own vacuous configuration; EngineFutures' exactly-once
  actual-exit lifetime; never invoking admission callbacks under the lock; fixing the SPLADE read-modify-write
  and the dead GPU broadcast in place.

  C1 is not closed until items 1 to 3 are done and reviewed. C2 does not start before that.
