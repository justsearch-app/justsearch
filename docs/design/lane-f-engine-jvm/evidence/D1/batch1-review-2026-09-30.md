# Batch 1 independent review (2026-09-30)

Refute-first static review by Sol (gpt-6.1-sol) of 1ac1407ef (help source), the settings-caller fix and bb399e495 (G1). Verbatim. The three SHOULD-FIX findings are open on branch codex/lane-f-d1-help (Codex session 01a0f2bb-6611-7850-abea-6ced24298a09, Gradle gated on tmp/grants/gradle-help2).

- **SHOULD-FIX — [KnowledgeServerMigrationOps.java:2005](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/ops/KnowledgeServerMigrationOps.java:2005): collection precedence changes ordinary ingestion semantics.** A default watched root persists a null label (`RootLifecycleOps.java:305`). If the same path is configured as `books`, Green now assigns `books`. Ordinary ingestion uses the watched binding; old Green preserved the existing job collection through SQL `COALESCE` (`SqliteJobQueue.java:1078`), with no later configuration derivation. Fix: watched-root presence must win even with a null label; add a same-path collision regression.

- **SHOULD-FIX — [KnowledgeServerMigrationOps.java:2012](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/ops/KnowledgeServerMigrationOps.java:2012): Green admits help in eval mode.** Bootstrap explicitly skips help to protect eval counts and precision (`KnowledgeServerBootstrap.java:1087`); Green unconditionally includes physically present help. An eval migration therefore introduces non-eval documents. Fix: apply the same eval exclusion to migration coverage and test it.

- **SHOULD-FIX — [ResolvedConfig.java:88](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfig.java:88): discovered SSOT loses help coverage.** Without an explicit SSOT override, `ResolvedConfigBuilder.java:892` leaves `ssotPath` null even though `JustSearchConfigurationLoader.java:61` discovers the repository’s SSOT. Previously Bootstrap found help through its discovered working directory; now both Bootstrap and Green receive no help source. Fix: populate the resolved SSOT path from the loader’s discovery during normal configuration assembly.

Collection trace: ordinary ingestion uses the requested/watched label; pre-change Green retained an existing job label, otherwise default; post-change Green supplies its selected root label, overriding existing labels when non-null. Labels therefore were useful when old jobs were absent, but were not universally missing before this commit.

**No findings:**

- **Help identity/replay/marker:** resolved help uses the same path, `justsearch-help` label, and file-admission pipeline. Audit transport differs, but document identity remains path-based (`IndexingDocumentOps.java:172`). Successful complete migration preserves help coverage behind the existing marker.
- **Relative/missing physical paths:** normalization and missing-directory skipping are sound. Top-level Markdown selection matches Bootstrap.
- **Queue signature:** implementers/callers are updated; the default throws rather than silently dropping collection (`SwitchBufferCapableQueue.java:97`).
- **Module boundary:** configuration was already an `app-api` dependency; canonical dependency map and inspected ArchUnit rules permit it.
- **`c15c9eba5`:** no remaining operational settings POST without witness/key found in the requested paths.
- **`bb399e495`:** both spawn sites use G1. AOT/default launch arguments add no competing collector; the ZGC experiment explicitly disables G1. Remaining SerialGC uses belong to separate child/test JVMs.

Static review only; no builds, backends, tests, or `--check-canonical` execution. Findings remain applicable at `fba5eca0f`.