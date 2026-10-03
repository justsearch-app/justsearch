# D1 reconfigure implementation review (2026-09-30)

Refute-first static review by Sol (gpt-6.1-sol) of 5ccfa74a8 on codex/lane-f-reconf-inplace. Verbatim; fixes assigned back to the implementing session.

Reviewed `5ccfa74a8`; static review only. No edits, Gradle, or backend starts.

- **BLOCKER — CUDA preparation occurs after commitment.** [KnowledgeServer.java:4737](F:/justsearch-public/.claude/worktrees/lane-f-reconf/modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:4737) accepts assemblies without realizing CUDA. `NativeSessionHandle` initializes CUDA on first inference. B can therefore persist before GPU allocation fails; restored A can report READY without restoring its previous device. **Fix:** perform bounded inference and verify realized device before returning prepared B or publishing restored A.

- **BLOCKER — restoration `Error` bypasses recovery.** [KnowledgeServer.java:4989](F:/justsearch-public/.claude/worktrees/lane-f-reconf/modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:4989) excludes `Error`. If B throws a runtime exception and A reconstruction throws an `Error`, the outer preparation catch suppresses A’s error onto B. No owner reaches the composer, no recovery reservation is retained, and the encoder remains RELOADING—which automatic recovery does not admit. **Fix:** retain recovery ownership and both causes, or propagate the fatal error into ordered restart; add this regression.

- **SHOULD-FIX — installed CUDA assertions precede initialization.** [query-reconfigure.mjs:196](F:/justsearch-public/.claude/worktrees/lane-f-reconf/scripts/supervisor-conformance/query-reconfigure.mjs:196) checks realized CUDA B before its model query; restored A has the same ordering. Diagnostics do not initialize sessions. **Fix:** realize candidates during production preparation, then assert CUDA; independently query before runtime assertions in the harness.

- **SHOULD-FIX — outage sampling misses preparation.** [query-reconfigure.mjs:158](F:/justsearch-public/.claude/worktrees/lane-f-reconf/scripts/supervisor-conformance/query-reconfigure.mjs:158) starts sampling after releasing the barrier. An outage during retirement/composition can escape the final zero-outage assertion. **Fix:** sample from POST issuance through completion, with counts per apply and held requests across the transition.

- **SHOULD-FIX — composition failures omit operation evidence.** [SettingsCommitCoordinator.java:759](F:/justsearch-public/.claude/worktrees/lane-f-reconf/modules/app-services/src/main/java/io/justsearch/app/services/settings/SettingsCommitCoordinator.java:759) attaches evidence only after preparation returns. B composition exceptions therefore omit mode/reason/free/footprint from the failed operation result, contrary to D1:2519–2520. **Fix:** carry the measured decision in a typed refusal and assert its response/replay fields.

- **SHOULD-FIX — named failure regressions do not inject those failures.** [KnowledgeServerQuerySettingsOwnerTest.java:279](F:/justsearch-public/.claude/worktrees/lane-f-reconf/modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQuerySettingsOwnerTest.java:279) repeats manual `abort()` calls under four labels. Coordinator tests use fake owners. **Fix:** exercise the physical IN_PLACE owner through actual later-owner, validation, cancellation, and replacement failures.

- **SHOULD-FIX — API documentation lacks composition semantics.** [SettingsV2.java:23](F:/justsearch-public/.claude/worktrees/lane-f-reconf/modules/app-api/src/main/java/io/justsearch/app/api/settings/SettingsV2.java:23) adds generic `mode`/`reason` beside `settingsMode`, without documenting their scope in `api-contract-map.md`. **Fix:** prefer a `composition` object or prefixed fields; document values, response-only semantics, and refusal placement.

**Sound, from code inspection:**

- Own capture releases before draining; the five-second timeout restores untouched A. Drain checks issued views sharing the same query-set instance.
- Degraded services clear query wrappers and retain index sessions/producer ownership. Transfer occurs at publication.
- Source memory credits realized GPU query handles, excluding CPU fallback and index roles.
- Settings replacement follows composition and validation; postcommit publication failure marks uncertainty.
- Both schema copies are identical; TS and fixture include the additions. `restartScheduled` logic is unchanged.
- Installed ceiling is applied before launch; refusal checks settings bytes/witness, and final checks compare PID, restart count, and generation. Installed execution remains unproven.