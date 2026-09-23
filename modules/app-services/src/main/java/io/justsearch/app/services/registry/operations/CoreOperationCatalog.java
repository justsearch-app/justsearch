/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations;

import io.justsearch.agent.api.registry.AliasRegistry;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.CatalogMatcher;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RequiredCapability;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.core.context.EngineContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Catalog of core Operation entries shipped in slice 1.2.
 *
 * <p>Per tempdoc 429 §"Initial entries": three seeds exercise all three confirm
 * strategies (NONE / INLINE / TYPED), all three risk tiers (LOW / HIGH × 2),
 * and the multi-executor benefit (UI / AGENT / CLI).
 *
 * <ul>
 *   <li>{@code core.restart-worker} — HIGH risk, TYPED confirm ("restart"),
 *       UI executor only
 *   <li>{@code core.bulk-reindex} — HIGH risk, INLINE confirm, UI + AGENT
 *       (NOT_IMPLEMENTED handler)
 *   <li>{@code core.ping-backend} — LOW risk, NONE confirm, UI + AGENT + CLI
 * </ul>
 *
 * <p>Provenance is {@code Provenance.core("1.0")} for all entries. Bindings use
 * the convenience {@link Binding#of(OperationRef)} (handlerId == op id by default).
 */
public final class CoreOperationCatalog implements OperationCatalog {

  public static final String NAMESPACE = "core";

  private final AliasRegistry aliasRegistry;
  private final CatalogMatcher catalogMatcher;

  public CoreOperationCatalog() {
    this(AliasRegistry.empty(), CatalogMatcher.defaultMatcher());
  }

  public CoreOperationCatalog(AliasRegistry aliasRegistry, CatalogMatcher catalogMatcher) {
    this.aliasRegistry = aliasRegistry;
    this.catalogMatcher = catalogMatcher;
  }

  @Override
  public AliasRegistry aliasRegistry() {
    return aliasRegistry;
  }

  @Override
  public CatalogMatcher matcher() {
    return catalogMatcher;
  }

  public static final OperationRef RESTART_WORKER = new OperationRef("core.restart-worker");
  public static final OperationRef BULK_REINDEX = new OperationRef("core.bulk-reindex");
  /**
   * Slice 447-followup-bulk-reindex-recovery (Option A) + §X.11.5 Phase 7: parameterless
   * full-corpus rebuild wrapper. {@link #BULK_REINDEX} requires a {@code corpusIds}
   * argument that's dynamic per-recovery; this wrapper sidesteps it by rebuilding the
   * entire watched-roots corpus. Used as the recovery target for
   * {@code index.unavailable + index.not_healthy} (442 §B.9 row 548).
   */
  public static final OperationRef REBUILD_INDEX = new OperationRef("core.rebuild-index");
  public static final OperationRef PING_BACKEND = new OperationRef("core.ping-backend");
  /**
   * Slice 3a-2-c precondition: clear all permanently-failed indexing jobs from the
   * worker queue. MEDIUM risk (destructive on job records); inline confirm on the FE
   * side per ActionButton risk-driven UX.
   */
  public static final OperationRef CLEAR_FAILED_JOBS =
      new OperationRef("core.clear-failed-jobs");

  /**
   * Slice 484 §3.6 / observations.md `core.index-gc` closure: prune marked or stale
   * index generations. MEDIUM risk + Inline confirm (mirror clear-failed-jobs);
   * Audience.OPERATOR (admin maintenance). Default args {@code keepLatest=0,
   * pruneMarkedOnly=true} — {@code keepLatest=0} means "use server-side default
   * policy" per the proto contract (matches IndexingController.handleIndexGc).
   * Wires through {@link io.justsearch.app.services.registry.operations.handlers.IndexGcHandler}
   * to {@code MigrationOps.runIndexGc}.
   */
  public static final OperationRef INDEX_GC = new OperationRef("core.index-gc");

  /**
   * Tempdoc 931 §E item 10: purge deleted-but-unmerged documents from the ACTIVE index so two arms
   * of a paired evaluation compare with equal merge state. LOW risk — it removes nothing a query
   * can reach, it only collapses tombstones the searcher already hides — but it holds the writer
   * for the duration of the merge, so it is confirm-free yet operator-audience.
   * Wires through {@link io.justsearch.app.services.registry.operations.handlers.SettleIndexHandler}
   * to {@code MigrationOps.settleIndex}.
   */
  public static final OperationRef SETTLE_INDEX = new OperationRef("core.settle-index");

  /** Slice 445: cancel an in-flight indexing job by pathHash. */
  public static final OperationRef CANCEL_INDEXING_JOB =
      new OperationRef("core.cancel-indexing-job");

  /** Slice 445: retry a FAILED indexing job by pathHash. */
  public static final OperationRef RETRY_INDEXING_JOB =
      new OperationRef("core.retry-indexing-job");

  /** Slice 445: substrate-routed equivalent of POST /api/library/resolve-hash. */
  public static final OperationRef RESOLVE_PATH_HASH =
      new OperationRef("core.resolve-path-hash");

  /**
   * Slice 3a-1-2 closure: lightweight incremental reindex of all watched roots.
   * Distinct from {@link #BULK_REINDEX} (blue/green migration). LOW risk (idempotent;
   * doesn't destroy any data); no confirm. Args: {@code {"force": boolean}}.
   */
  public static final OperationRef REINDEX = new OperationRef("core.reindex");

  /**
   * Tempdoc 626 §Recency (Move C): verify/reconcile a SINGLE watched root by its {@code pathHash} — a
   * granularity-matched recovery for the {@code index.drift-unknown} condition (scoped to one folder
   * instead of a corpus-wide {@link #REINDEX}). Re-prunes orphans + re-walks the root, refreshing its
   * per-root verification state. LOW risk (idempotent; doesn't destroy data); no confirm. Args:
   * {@code {"pathHash": string}}.
   */
  public static final OperationRef RECONCILE_ROOT = new OperationRef("core.reconcile-root");

  /**
   * Slice 3a-1-2 closure: privacy-redacted diagnostics-pack export. LOW risk
   * (read-only; produces a ZIP under AI Home); no confirm. Result carries the
   * ZIP path in {@code structuredData.path}.
   */
  public static final OperationRef EXPORT_DIAGNOSTICS =
      new OperationRef("core.export-diagnostics");

  /**
   * Head-local allowlist-only support summary returned transiently for an explicit clipboard copy.
   * LOW risk, no confirmation, no Worker or Inference availability requirement.
   */
  public static final OperationRef COPY_DIAGNOSTIC_SUMMARY =
      new OperationRef("core.copy-diagnostic-summary");

  /**
   * Slice 3a-2-c LibraryView Add Folder migration. LOW risk (additive). Args:
   * {@code {"path": string, "collection"?: string}}. Maps to
   * IndexingService.addWatchedRoot.
   */
  public static final OperationRef ADD_WATCHED_ROOT =
      new OperationRef("core.add-watched-root");

  /**
   * Slice 3a-2-c LibraryView Remove Folder migration. MEDIUM risk (deletes
   * indexed documents). Args: {@code {"path": string, "collection"?: string}}.
   * Returns {@code structuredData.deletedJobs}.
   */
  public static final OperationRef REMOVE_WATCHED_ROOT =
      new OperationRef("core.remove-watched-root");

  /**
   * Slice 3a-2-c LibraryView Preview Excludes button. LOW risk (read-only;
   * dryRun walk that counts matches per-pattern without deletion). No args.
   * Returns the full ExcludesService.ExcludesResult shape in structuredData.
   */
  public static final OperationRef PREVIEW_EXCLUDES =
      new OperationRef("core.preview-excludes");

  /**
   * Slice 3a-2-c LibraryView Apply Excludes button. HIGH risk (destructive:
   * deletes already-indexed documents whose paths match the configured globs).
   * No args. Returns the full ExcludesService.ExcludesResult in
   * structuredData. Typed-confirm policy mirrors restart-worker's pattern.
   */
  public static final OperationRef APPLY_EXCLUDES =
      new OperationRef("core.apply-excludes");

  /**
   * Slice 3a-2-c BrainRuntimeSection Apply Runtime button. MEDIUM risk
   * (restarts llama-server when in ONLINE mode). No args. Returns
   * {@code structuredData.mode} (post-apply current mode).
   */
  public static final OperationRef RELOAD_INFERENCE =
      new OperationRef("core.reload-inference");

  /**
   * Slice 3a-2-c BrainRuntimeSection Switch-to-Online / Switch-to-Indexing
   * buttons (single Operation with mode arg).
   *
   * <p><b>Tempdoc 737 §12b/§12d:</b> superseded by {@link #SET_CHAT_ENABLED} — a temporary alias,
   * retirement per tempdoc 737 §12d once the FE has fully migrated off the {@code mode} vocabulary.
   * Its handler now maps {@code online} → {@code chatEnabled=true} / {@code indexing} →
   * {@code chatEnabled=false} through the SAME spec-write path as {@code core.set-chat-enabled}, so
   * it carries no preconditions and cannot re-introduce the §3b circular denial. Args:
   * {@code {"mode": "online" | "indexing"}}.
   */
  public static final OperationRef SWITCH_INFERENCE_MODE =
      new OperationRef("core.switch-inference-mode");

  /**
   * Tempdoc 737 §12b: the runtime-authority intent write that supersedes
   * {@link #SWITCH_INFERENCE_MODE}. Records the user's desired chat-engine state on the runtime
   * spec ("Start AI" / "Shut Down AI") with <b>no preconditions</b> — an intent cannot be denied
   * because the thing it asks for is off (§3b circular class made inexpressible). LOW risk, no
   * confirm: it is a reversible preference toggle and the primary escape action from every runtime
   * state (soft-off included), which must never be gated. Enterprise online-AI policy + GPU
   * availability are enforced at convergence inside the reconciler, not at intent time. Args:
   * {@code {"enabled": boolean}}. Returns {@code structuredData.chatEnabled} +
   * {@code structuredData.engineState}.
   */
  public static final OperationRef SET_CHAT_ENABLED = new OperationRef("core.set-chat-enabled");

  /**
   * Slice 3a-2-c BrainRuntimeSection Trigger Offline Processing button.
   * Dispatches the VDU + embeddings catch-up worker. LOW risk (background
   * batch; idempotent — re-running just re-attempts pending work). Returns
   * an empty structuredData; success means dispatch succeeded, not that
   * processing completed.
   */
  public static final OperationRef TRIGGER_OFFLINE_PROCESSING =
      new OperationRef("core.trigger-offline-processing");

  /**
   * Slice 3a-2-c BrainRuntimeSection Activate Runtime Variant. MEDIUM risk
   * (starts GPU pack activation lifecycle). Args: {@code {"variantId": string}}.
   * Returns the post-start activation status snapshot in structuredData
   * (mirrors the AiRuntimeStatusResponse shape).
   */
  public static final OperationRef ACTIVATE_RUNTIME_VARIANT =
      new OperationRef("core.activate-runtime-variant");

  /**
   * Slice 3a-2-c BrainRuntimeSection Deactivate Runtime Variant. MEDIUM risk
   * (starts GPU pack deactivation lifecycle). No args. Returns the
   * post-start activation status snapshot in structuredData.
   */
  public static final OperationRef DEACTIVATE_RUNTIME_VARIANT =
      new OperationRef("core.deactivate-runtime-variant");

  /**
   * Slice 3a-2-c BrainPackImportSection Preflight button. LOW risk
   * (read-only validation). Args: {@code {"path": string}}. Returns the
   * preflight result map in structuredData (mirrors AiPackPreflightResult).
   */
  public static final OperationRef PREFLIGHT_AI_PACK =
      new OperationRef("core.preflight-ai-pack");

  /**
   * Slice 3a-2-c BrainPackImportSection Import button. MEDIUM risk (starts
   * pack-import lifecycle). Args: {@code {"path": string,
   * "allowDowngrade"?: boolean}}. Returns the post-start status snapshot
   * (AiPackImportStatus shape).
   */
  public static final OperationRef IMPORT_AI_PACK =
      new OperationRef("core.import-ai-pack");

  /**
   * Slice 3a-2-c BrainInstallSection Start Install. MEDIUM risk (begins
   * fresh AI install lifecycle). Args:
   * {@code {"acceptTerms"?: boolean}}. Returns post-start install status
   * (AiInstallStatus shape).
   */
  public static final OperationRef START_AI_INSTALL =
      new OperationRef("core.start-ai-install");

  /**
   * D1 installer-generation amendment: activate the staged model candidate through the recorded
   * generation owner. Download/acquisition remains owned by {@link #START_AI_INSTALL}; this
   * operation is the separately approved durable activation boundary.
   */
  public static final OperationRef ACTIVATE_INSTALLED_MODELS =
      new OperationRef("core.activate-installed-models");

  /**
   * Slice 3a-2-c BrainInstallSection Cancel Install. MEDIUM risk (cancels
   * a running install). No args. Idempotent if no install is running.
   */
  public static final OperationRef CANCEL_AI_INSTALL =
      new OperationRef("core.cancel-ai-install");

  /**
   * Slice 3a-2-c BrainInstallSection Repair AI. MEDIUM risk (re-runs install
   * steps to recover from partial / corrupted state). Args:
   * {@code {"acceptTerms"?: boolean}}.
   */
  public static final OperationRef REPAIR_AI_INSTALL =
      new OperationRef("core.repair-ai-install");

  /**
   * Slice 3a-2-c BrainPackImportSection Create User Policy. MEDIUM risk
   * (writes a new file under AI Home). Args:
   * {@code {"manifestSha256": string}}. Returns {@code path} of the
   * created file in structuredData.
   */
  public static final OperationRef CREATE_USER_POLICY =
      new OperationRef("core.create-user-policy");

  /**
   * Slice 3a-2-c BrainPackImportSection Add Digest to Allowlist. MEDIUM
   * risk (modifies the user policy file). Args:
   * {@code {"manifestSha256": string}}. Returns {@code path}, {@code changed},
   * {@code allowlistedCount} in structuredData.
   */
  public static final OperationRef ALLOWLIST_ADD_DIGEST =
      new OperationRef("core.allowlist-add-digest");

  /**
   * Slice 3a-2-c SettingsView Reset to Defaults button. MEDIUM risk
   * (resets FE-controlled settings to canonical defaults; preserves
   * admin-set fields). No args. Returns the post-reset SettingsV2-shaped
   * map in structuredData so the FE can refresh its store from the
   * response.
   */
  public static final OperationRef RESET_SETTINGS =
      new OperationRef(io.justsearch.app.services.settings.SettingsResetPreparation.OPERATION_ID);

  /**
   * D1-4: the accepted settings transaction. The HTTP settings front supplies the complete
   * {@code SettingsV2} witness and optional UI mode intent as one typed operation envelope;
   * the operation runner owns acceptance and terminal persistence.
   */
  public static final OperationRef RECONFIGURE = new OperationRef("core.reconfigure");

  /**
   * Slice 491 §9.D Phase E (C4 / E3) — agent navigation tool. Gives the agent loop a
   * structured tool-call form of navigation that complements the URL-emission path. The
   * handler dispatches a {@link io.justsearch.agent.api.registry.ShellAddress.Navigation}
   * envelope via {@link io.justsearch.agent.api.registry.BackendIntentRouter} which forwards
   * onto {@code /api/intent/stream} for the FE {@code IntentRouter} to consume.
   *
   * <p>Args: {@code {"surfaceId": "core.<surface-id>"}}. LOW risk (navigation is
   * presentation-layer; trust lattice gates destructive side-effects elsewhere).
   *
   * <p>Tempdoc 560 WS4: only the ref constant lives here now (the handler registers under it). The
   * single canonical {@code core.navigate-to-surface} Operation declaration (superset executors
   * {@code {UI, AGENT}}, audience {@code USER}) lives in {@link
   * io.justsearch.agent.tools.AgentToolsOperationCatalog}.
   */
  public static final OperationRef NAVIGATE_TO_SURFACE =
      new OperationRef("core.navigate-to-surface");

  private final List<Operation> definitions = List.of(
      restartWorker(),
      bulkReindex(),
      rebuildIndex(),
      pingBackend(),
      clearFailedJobs(),
      reindex(),
      reconcileRoot(),
      exportDiagnostics(),
      copyDiagnosticSummary(),
      addWatchedRoot(),
      removeWatchedRoot(),
      previewExcludes(),
      applyExcludes(),
      reloadInference(),
      switchInferenceMode(),
      setChatEnabled(),
      triggerOfflineProcessing(),
      activateRuntimeVariant(),
      deactivateRuntimeVariant(),
      preflightAiPack(),
      importAiPack(),
      startAiInstall(),
      activateInstalledModels(),
      cancelAiInstall(),
      repairAiInstall(),
      createUserPolicy(),
      allowlistAddDigest(),
      resetSettings(),
      reconfigure(),
      cancelIndexingJob(),
      retryIndexingJob(),
      resolvePathHash(),
      indexGc(),
      settleIndex());

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<Operation> definitions() {
    return definitions;
  }

  private static Operation restartWorker() {
    return new Operation(
        RESTART_WORKER,
        Presentation.forId(RESTART_WORKER, Optional.of("warning"), Optional.of("destructive")),
        Interface.inputsOnly("{\"type\":\"object\",\"properties\":{}}"),
        new OperationPolicy(
            RiskTier.HIGH,
            ConfirmStrategy.typedForId(RESTART_WORKER),
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(RequiredCapability.WorkerOnline.INSTANCE),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(RESTART_WORKER),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI),
        // Slice 481 §7 step 2: admin restart action; not user-self-service.
        Audience.OPERATOR);
  }

  private static String bulkArgumentsSchema(boolean corpusLabels) {
    var sources = java.util.Arrays.stream(io.justsearch.app.api.status.MigrationSource.values())
        .filter(source -> source != io.justsearch.app.api.status.MigrationSource.UNKNOWN
            && source != io.justsearch.app.api.status.MigrationSource.INSTALLER_MODEL_ACTIVATION)
        .map(source -> "\"" + source.wire() + "\"")
        .collect(java.util.stream.Collectors.joining(","));
    return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
        + "\"source\":{\"type\":\"string\",\"enum\":[" + sources + "]}"
        + (corpusLabels ? ",\"corpusIds\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":256}}" : "")
        + "}" + (corpusLabels ? ",\"required\":[\"corpusIds\"]" : "") + "}";
  }

  private static Operation bulkReindex() {
    return new Operation(
        BULK_REINDEX,
        Presentation.forId(BULK_REINDEX),
        Interface.inputsOnly(bulkArgumentsSchema(true)),
        new OperationPolicy(
            RiskTier.HIGH,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(RequiredCapability.WorkerOnline.INSTANCE),
            false).withRecordKind(io.justsearch.agent.api.registry.OperationKind.REINDEX)
            .withDeclaredSurvival(EngineContext.Survival.DURABLE),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(BULK_REINDEX),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI, ExecutorTag.AGENT),
        // Slice 481 §7 step 2: admin migration; not user-self-service.
        Audience.OPERATOR);
  }

  /**
   * Slice 447-followup-bulk-reindex-recovery (Option A) + §X.11.5 Phase 7:
   * full-corpus rebuild using the same recorded lifecycle as {@link #bulkReindex},
   * with no required arguments — usable as the static recovery target for
   * {@code index.unavailable + index.not_healthy} via {@link OperationInvocation}.
   */
  private static Operation rebuildIndex() {
    return new Operation(
        REBUILD_INDEX,
        Presentation.forId(REBUILD_INDEX),
        Interface.inputsOnly(bulkArgumentsSchema(false)),
        new OperationPolicy(
            RiskTier.HIGH,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(RequiredCapability.WorkerOnline.INSTANCE),
            false).withRecordKind(io.justsearch.agent.api.registry.OperationKind.REINDEX)
            .withDeclaredSurvival(EngineContext.Survival.DURABLE),
        OperationAvailability.empty(),
        // Slice 447-followup-live-wiring §X.12.8 Item 2.1: a full rebuild affects the
        // three indexing-related Resources (clears the indexing-jobs queue, restarts
        // processing for all indexed-roots, re-derives the failed-jobs list). The
        // §X.3.1 partition's lineage.affects slot is the natural home for this fact.
        new OperationLineage(
            Set.of(
                new ResourceRef("core.indexing-jobs"),
                new ResourceRef("core.indexed-roots"),
                new ResourceRef("core.failed-indexing-jobs")),
            Set.of()),
        Binding.of(REBUILD_INDEX),
        Provenance.core("1.0"),
        // Tempdoc 598 reopen (B-2): UI-only executor. Recovering semantic search by rebuilding the
        // index is a USER self-service action (593's thesis: the user must rebuild to get meaning-based
        // search), so the audience is USER — which makes the Brain `<jf-operation core.rebuild-index>`
        // render for a default viewer instead of 0×0, and keeps the degradation-banner remedy reachable.
        // ExecutorTag.AGENT is DROPPED: a USER+AGENT op would be offered to the agent tool list, handing
        // the model a destructive full-corpus rebuild; UI-only keeps it out of the agent surface via the
        // executor, not the audience. Safety stays on the op (HIGH risk + Inline confirm + WorkerOnline).
        Set.of(ExecutorTag.UI),
        Audience.USER);
  }

  private static Operation pingBackend() {
    return new Operation(
        PING_BACKEND,
        Presentation.forId(PING_BACKEND),
        Interface.of("{\"type\":\"object\",\"properties\":{}}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.autoRetry(2, "core.ping-backend"),
            Set.of(),
            false,
            // Slice 490 §6.3 proof-of-consumer + Group B2 follow-up: ping-backend
            // declares its advisory class as {@code core.advisory-operation-completed}
            // via the typed advisoryClass field (replacing the v1 boolean
            // emitAdvisoryOnCompletion). LOW risk, idempotent, no destructive side
            // effects — the safe canary for end-to-end advisory emission.
            Optional.of(new ResourceRef("core.advisory-operation-completed"))),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(PING_BACKEND),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI, ExecutorTag.AGENT, ExecutorTag.CLI));
  }

  private static Operation clearFailedJobs() {
    return new Operation(
        CLEAR_FAILED_JOBS,
        Presentation.forId(CLEAR_FAILED_JOBS, Optional.empty(), Optional.of("destructive")),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{}}",
            "{\"type\":\"object\",\"properties\":{\"clearedCount\":{\"type\":\"integer\"}}}"),
        new OperationPolicy(
            RiskTier.MEDIUM,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(RequiredCapability.WorkerOnline.INSTANCE),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(CLEAR_FAILED_JOBS),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI),
        // Slice 481 §7 step 2: admin triage; clearing failed-jobs queue is operator-facing.
        Audience.OPERATOR);
  }

  private static Operation indexGc() {
    return new Operation(
        INDEX_GC,
        Presentation.forId(INDEX_GC, Optional.empty(), Optional.of("destructive")),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{"
                + "\"keepLatest\":{\"type\":\"integer\",\"minimum\":0},"
                + "\"pruneMarkedOnly\":{\"type\":\"boolean\"}}}",
            "{\"type\":\"object\",\"properties\":{"
                + "\"accepted\":{\"type\":\"boolean\"},"
                + "\"markedCount\":{\"type\":\"integer\"},"
                + "\"prunedCount\":{\"type\":\"integer\"}}}"),
        new OperationPolicy(
            RiskTier.MEDIUM,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(RequiredCapability.WorkerOnline.INSTANCE),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(INDEX_GC),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI),
        // Slice 484 §3.6: admin maintenance; bounded destructive (only prunes
        // marked-for-deletion segments when pruneMarkedOnly=true).
        Audience.OPERATOR);
  }

  private static Operation settleIndex() {
    return new Operation(
        SETTLE_INDEX,
        Presentation.forId(SETTLE_INDEX, Optional.empty(), Optional.of("maintenance")),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{"
                + "\"expungeDeletesOnly\":{\"type\":\"boolean\"},"
                + "\"maxSegments\":{\"type\":\"integer\",\"minimum\":0}}}",
            "{\"type\":\"object\",\"properties\":{"
                + "\"accepted\":{\"type\":\"boolean\"},"
                + "\"maxDocBefore\":{\"type\":\"integer\"},"
                + "\"numDocsBefore\":{\"type\":\"integer\"},"
                + "\"maxDocAfter\":{\"type\":\"integer\"},"
                + "\"numDocsAfter\":{\"type\":\"integer\"},"
                + "\"segmentsAfter\":{\"type\":\"integer\"},"
                + "\"elapsedMs\":{\"type\":\"integer\"}}}"),
        new OperationPolicy(
            // LOW: nothing a query can reach is removed — only tombstones the searcher already
            // hides are collapsed. No confirm for the same reason.
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(RequiredCapability.WorkerOnline.INSTANCE),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(SETTLE_INDEX),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI),
        // Tempdoc 931 §E item 10: evaluation/admin maintenance, not an end-user affordance.
        Audience.OPERATOR);
  }

  private static Operation cancelIndexingJob() {
    return new Operation(
        CANCEL_INDEXING_JOB,
        Presentation.forId(CANCEL_INDEXING_JOB, Optional.empty(), Optional.of("destructive")),
        Interface.of(
            "{\"type\":\"object\","
                + "\"properties\":{\"pathHash\":{\"type\":\"string\"}},"
                + "\"required\":[\"pathHash\"]}",
            "{\"type\":\"object\","
                + "\"properties\":{\"cancelled\":{\"type\":\"boolean\"},"
                + "\"previousState\":{\"type\":\"string\"}}}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(RequiredCapability.WorkerOnline.INSTANCE),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(CANCEL_INDEXING_JOB),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI, ExecutorTag.AGENT));
  }

  private static Operation retryIndexingJob() {
    return new Operation(
        RETRY_INDEXING_JOB,
        Presentation.forId(RETRY_INDEXING_JOB),
        Interface.of(
            "{\"type\":\"object\","
                + "\"properties\":{\"pathHash\":{\"type\":\"string\"}},"
                + "\"required\":[\"pathHash\"]}",
            "{\"type\":\"object\","
                + "\"properties\":{\"retried\":{\"type\":\"boolean\"},"
                + "\"previousState\":{\"type\":\"string\"}}}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(RequiredCapability.WorkerOnline.INSTANCE),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(RETRY_INDEXING_JOB),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI, ExecutorTag.AGENT));
  }

  private static Operation resolvePathHash() {
    return new Operation(
        RESOLVE_PATH_HASH,
        Presentation.forId(RESOLVE_PATH_HASH),
        Interface.of(
            "{\"type\":\"object\","
                + "\"properties\":{\"pathHash\":{\"type\":\"string\"}},"
                + "\"required\":[\"pathHash\"]}",
            "{\"type\":\"object\","
                + "\"properties\":{\"found\":{\"type\":\"boolean\"},"
                + "\"path\":{\"type\":\"string\"},"
                + "\"lastSeenAtMs\":{\"type\":\"integer\"},"
                + "\"removedAtMs\":{\"type\":\"integer\"}}}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(RequiredCapability.WorkerOnline.INSTANCE),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(RESOLVE_PATH_HASH),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI, ExecutorTag.AGENT));
  }

  private static Operation reindex() {
    return new Operation(
        REINDEX,
        Presentation.forId(REINDEX),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"force\":{\"type\":\"boolean\"}}}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(RequiredCapability.WorkerOnline.INSTANCE),
            false).withRecordKind(io.justsearch.agent.api.registry.OperationKind.REINDEX)
            .withDeclaredSurvival(EngineContext.Survival.DURABLE),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(REINDEX),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI, ExecutorTag.AGENT));
  }

  private static Operation reconcileRoot() {
    return new Operation(
        RECONCILE_ROOT,
        Presentation.forId(RECONCILE_ROOT),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"pathHash\":{\"type\":\"string\"}},\"required\":[\"pathHash\"]}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(RequiredCapability.WorkerOnline.INSTANCE),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(RECONCILE_ROOT),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI, ExecutorTag.AGENT));
  }

  private static Operation exportDiagnostics() {
    return new Operation(
        EXPORT_DIAGNOSTICS,
        Presentation.forId(EXPORT_DIAGNOSTICS),
        Interface.of(
            // feTelemetry: optional FE-supplied telemetry section (e.g. the wire-drift ring
            // summary), embedded into the export as frontend/fe-telemetry.json (tempdoc 683).
            "{\"type\":\"object\",\"properties\":{\"feTelemetry\":{\"type\":\"object\"}}}",
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(RequiredCapability.WorkerOnline.INSTANCE),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(EXPORT_DIAGNOSTICS),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI),
        // Tempdoc 689 decision: read-only, privacy-redacted, local-only export; a
        // user self-service support flow, not an admin action. The state-mutating
        // siblings (clear-failed-jobs, index-gc, restart-worker) deliberately
        // remain Audience.OPERATOR.
        Audience.USER);
  }

  private static Operation copyDiagnosticSummary() {
    return new Operation(
        COPY_DIAGNOSTIC_SUMMARY,
        Presentation.forId(COPY_DIAGNOSTIC_SUMMARY),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
            "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}},\"required\":[\"summary\"]}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(COPY_DIAGNOSTIC_SUMMARY),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI),
        Audience.USER);
  }

  private static Operation addWatchedRoot() {
    return new Operation(
        ADD_WATCHED_ROOT,
        Presentation.forId(ADD_WATCHED_ROOT),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"collection\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"collection\":{\"type\":\"string\"}}}"),
        new OperationPolicy(
                RiskTier.LOW,
                ConfirmStrategy.None.INSTANCE,
                AuditPolicy.METADATA_ONLY,
                RetryPolicy.noRetry(),
                Set.of(RequiredCapability.WorkerOnline.INSTANCE),
                false)
            // Tempdoc 560 WS3: undoing an add-watched-root re-issues remove-watched-root.
            .withInverseOperationRef(REMOVE_WATCHED_ROOT),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(ADD_WATCHED_ROOT),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI, ExecutorTag.AGENT));
  }

  private static Operation removeWatchedRoot() {
    return new Operation(
        REMOVE_WATCHED_ROOT,
        Presentation.forId(REMOVE_WATCHED_ROOT, Optional.empty(), Optional.of("destructive")),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"collection\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"collection\":{\"type\":\"string\"},\"deletedJobs\":{\"type\":\"integer\"}}}"),
        new OperationPolicy(
                RiskTier.MEDIUM,
                ConfirmStrategy.Inline.INSTANCE,
                AuditPolicy.METADATA_ONLY,
                RetryPolicy.noRetry(),
                Set.of(RequiredCapability.WorkerOnline.INSTANCE),
                false)
            // Tempdoc 560 WS3: undoing a remove-watched-root re-issues add-watched-root.
            .withInverseOperationRef(ADD_WATCHED_ROOT),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(REMOVE_WATCHED_ROOT),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI));
  }

  // ExcludesResult structuredData schema shared by preview/apply Operations.
  // Mirrors the FE ApplyExcludesResponse shape (modules/ui-web src/api/domains/indexing.ts).
  private static final String EXCLUDES_RESULT_SCHEMA =
      "{\"type\":\"object\",\"properties\":{"
          + "\"dryRun\":{\"type\":\"boolean\"},"
          + "\"patterns\":{\"type\":\"integer\"},"
          + "\"rootsProcessed\":{\"type\":\"integer\"},"
          + "\"deletedByPathJobs\":{\"type\":\"integer\"},"
          + "\"deletedById\":{\"type\":\"integer\"},"
          + "\"matchedFiles\":{\"type\":\"integer\"},"
          + "\"capped\":{\"type\":\"boolean\"},"
          + "\"perPattern\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
          + "\"pattern\":{\"type\":\"string\"},\"matches\":{\"type\":\"integer\"}}}}}}";

  private static Operation previewExcludes() {
    return new Operation(
        PREVIEW_EXCLUDES,
        Presentation.forId(PREVIEW_EXCLUDES),
        Interface.of("{\"type\":\"object\",\"properties\":{}}", EXCLUDES_RESULT_SCHEMA),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(RequiredCapability.WorkerOnline.INSTANCE),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(PREVIEW_EXCLUDES),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI));
  }

  private static Operation applyExcludes() {
    return new Operation(
        APPLY_EXCLUDES,
        Presentation.forId(APPLY_EXCLUDES, Optional.of("warning"), Optional.of("destructive")),
        Interface.of("{\"type\":\"object\",\"properties\":{}}", EXCLUDES_RESULT_SCHEMA),
        new OperationPolicy(
            RiskTier.HIGH,
            ConfirmStrategy.typedForId(APPLY_EXCLUDES),
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(RequiredCapability.WorkerOnline.INSTANCE),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(APPLY_EXCLUDES),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI));
  }

  private static Operation reloadInference() {
    return new Operation(
        RELOAD_INFERENCE,
        Presentation.forId(RELOAD_INFERENCE),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{}}",
            "{\"type\":\"object\",\"properties\":{\"mode\":{\"type\":\"string\"}}}"),
        new OperationPolicy(
            RiskTier.MEDIUM,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(RequiredCapability.InferenceOnline.INSTANCE),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(RELOAD_INFERENCE),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI));
  }

  private static Operation switchInferenceMode() {
    return new Operation(
        SWITCH_INFERENCE_MODE,
        Presentation.forId(SWITCH_INFERENCE_MODE),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"mode\":{\"type\":\"string\",\"enum\":[\"online\",\"indexing\"]}},\"required\":[\"mode\"]}",
            "{\"type\":\"object\",\"properties\":{\"mode\":{\"type\":\"string\"}}}"),
        new OperationPolicy(
            RiskTier.MEDIUM,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            // Tempdoc 737 §8a: requiring InferenceOnline here was circular — the
            // online-bound direction of this op requires the postcondition it
            // establishes. Validation of the requested mode is internal to the handler.
            Set.of(),
            false).withRecordKind(io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(SWITCH_INFERENCE_MODE),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI, ExecutorTag.AGENT));
  }

  private static Operation setChatEnabled() {
    return new Operation(
        SET_CHAT_ENABLED,
        Presentation.forId(SET_CHAT_ENABLED),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"enabled\":{\"type\":\"boolean\"}},\"required\":[\"enabled\"]}",
            "{\"type\":\"object\",\"properties\":{\"chatEnabled\":{\"type\":\"boolean\"},\"engineState\":{\"type\":\"string\"}}}"),
        new OperationPolicy(
            // Tempdoc 737 §12b: LOW + None — a reversible intent write with NO preconditions (the
            // whole point: an intent to enable/disable chat cannot be denied for being off). The
            // primary escape action from every runtime state; gating it with a confirm or a
            // capability would re-introduce §3b's dead-button class.
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(),
            false).withRecordKind(io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY),
        OperationAvailability.empty(),
        // Tempdoc 737 §12b: the superseding op declares what it supersedes (OperationLineage
        // javadoc — supersedes lives on the newer Operation, favored by discovery/retrospection).
        new OperationLineage(Set.of(), Set.of(SWITCH_INFERENCE_MODE)),
        Binding.of(SET_CHAT_ENABLED),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI, ExecutorTag.AGENT));
  }

  private static Operation triggerOfflineProcessing() {
    return new Operation(
        TRIGGER_OFFLINE_PROCESSING,
        Presentation.forId(TRIGGER_OFFLINE_PROCESSING),
        Interface.of("{\"type\":\"object\",\"properties\":{}}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            // Tempdoc 737 §8a: InferenceOnline was circular — this op's own Phase A
            // brings inference online when it isn't (OfflineCoordinator); requiring it
            // up front blocked exactly that case. WorkerOnline stays: it is not
            // established by this op.
            Set.of(RequiredCapability.WorkerOnline.INSTANCE),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(TRIGGER_OFFLINE_PROCESSING),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI, ExecutorTag.AGENT));
  }

  private static Operation activateRuntimeVariant() {
    return new Operation(
        ACTIVATE_RUNTIME_VARIANT,
        Presentation.forId(ACTIVATE_RUNTIME_VARIANT),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"variantId\":{\"type\":\"string\"}},\"required\":[\"variantId\"]}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.MEDIUM,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            // Tempdoc 737 §8a: requiring InferenceOnline here was circular — activation
            // self-tests on an isolated port and is designed to work from cold/OFFLINE;
            // success is what ESTABLISHES ONLINE. Validation is internal to the handler.
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(ACTIVATE_RUNTIME_VARIANT),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI));
  }

  private static Operation deactivateRuntimeVariant() {
    return new Operation(
        DEACTIVATE_RUNTIME_VARIANT,
        Presentation.forId(DEACTIVATE_RUNTIME_VARIANT),
        Interface.of("{\"type\":\"object\",\"properties\":{}}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.MEDIUM,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            // Tempdoc 737 §8a: requiring InferenceOnline here was circular — this is
            // the designed escape hatch for a GPU variant that failed to come online;
            // gating the escape hatch on being online defeats its purpose. Validation
            // is internal to the handler.
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(DEACTIVATE_RUNTIME_VARIANT),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI));
  }

  private static Operation preflightAiPack() {
    return new Operation(
        PREFLIGHT_AI_PACK,
        Presentation.forId(PREFLIGHT_AI_PACK),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(PREFLIGHT_AI_PACK),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI));
  }

  private static Operation importAiPack() {
    return new Operation(
        IMPORT_AI_PACK,
        Presentation.forId(IMPORT_AI_PACK),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"allowDowngrade\":{\"type\":\"boolean\"}},\"required\":[\"path\"]}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.MEDIUM,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(IMPORT_AI_PACK),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI));
  }

  // BrainInstall args schema shared by start + repair (acceptTerms boolean).
  private static final String INSTALL_ARGS_WITH_TERMS_SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"acceptTerms\":{\"type\":\"boolean\"}}}";

  private static Operation startAiInstall() {
    return new Operation(
        START_AI_INSTALL,
        Presentation.forId(START_AI_INSTALL),
        Interface.of(INSTALL_ARGS_WITH_TERMS_SCHEMA, "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.MEDIUM,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(START_AI_INSTALL),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI));
  }

  private static Operation activateInstalledModels() {
    String source = io.justsearch.app.api.status.MigrationSource.INSTALLER_MODEL_ACTIVATION.wire();
    String argumentsSchema =
        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
            + "\"source\":{\"type\":\"string\",\"enum\":[\""
            + source
            + "\"]}},\"required\":[\"source\"]}";
    return new Operation(
        ACTIVATE_INSTALLED_MODELS,
        Presentation.forId(ACTIVATE_INSTALLED_MODELS),
        Interface.inputsOnly(argumentsSchema),
        new OperationPolicy(
            RiskTier.HIGH,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(RequiredCapability.WorkerOnline.INSTANCE),
            false)
            .withRecordKind(io.justsearch.agent.api.registry.OperationKind.REINDEX)
            .withDeclaredSurvival(EngineContext.Survival.DURABLE),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(ACTIVATE_INSTALLED_MODELS),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI));
  }

  private static Operation cancelAiInstall() {
    return new Operation(
        CANCEL_AI_INSTALL,
        Presentation.forId(CANCEL_AI_INSTALL),
        Interface.of("{\"type\":\"object\",\"properties\":{}}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.MEDIUM,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(CANCEL_AI_INSTALL),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI));
  }

  private static Operation repairAiInstall() {
    return new Operation(
        REPAIR_AI_INSTALL,
        Presentation.forId(REPAIR_AI_INSTALL),
        Interface.of(INSTALL_ARGS_WITH_TERMS_SCHEMA, "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.MEDIUM,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(REPAIR_AI_INSTALL),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI));
  }

  // Manifest-sha args schema shared by create-user-policy + allowlist-add-digest.
  private static final String POLICY_MANIFEST_ARGS_SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"manifestSha256\":{\"type\":\"string\"}},\"required\":[\"manifestSha256\"]}";

  private static Operation createUserPolicy() {
    return new Operation(
        CREATE_USER_POLICY,
        Presentation.forId(CREATE_USER_POLICY),
        Interface.of(
            POLICY_MANIFEST_ARGS_SCHEMA,
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"),
        new OperationPolicy(
            RiskTier.MEDIUM,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(CREATE_USER_POLICY),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI));
  }

  private static Operation allowlistAddDigest() {
    return new Operation(
        ALLOWLIST_ADD_DIGEST,
        Presentation.forId(ALLOWLIST_ADD_DIGEST),
        Interface.of(
            POLICY_MANIFEST_ARGS_SCHEMA,
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"changed\":{\"type\":\"boolean\"},\"allowlistedCount\":{\"type\":\"integer\"}}}"),
        new OperationPolicy(
            RiskTier.MEDIUM,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(ALLOWLIST_ADD_DIGEST),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI));
  }

  private static Operation resetSettings() {
    return new Operation(
        RESET_SETTINGS,
        Presentation.forId(RESET_SETTINGS),
        Interface.of("{\"type\":\"object\",\"properties\":{}}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.MEDIUM,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(),
            false).withRecordKind(io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(RESET_SETTINGS),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI),
        // Slice 481 §7 step 2: factory-reset; admin-class destructive operation.
        Audience.OPERATOR);
  }

  private static final String RECONFIGURE_INPUT_SCHEMA =
      "{\"type\":\"object\",\"additionalProperties\":false,"
          + "\"required\":[\"settings\",\"modeIntent\"],\"properties\":{"
          + "\"settings\":{\"type\":\"object\",\"required\":[\"witness\",\"operationKey\"]},"
          + "\"modeIntent\":{\"type\":[\"string\",\"null\"]}}}";

  private static Operation reconfigure() {
    return new Operation(
        RECONFIGURE,
        Presentation.forId(RECONFIGURE),
        Interface.of(RECONFIGURE_INPUT_SCHEMA, "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.MEDIUM,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(),
            false)
            .withRecordKind(io.justsearch.agent.api.registry.OperationKind.RECONFIGURE),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(RECONFIGURE),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI),
        Audience.USER);
  }

  // Tempdoc 560 WS4 (catalog collapse): the core.navigate-to-surface DEFINITION moved to
  // AgentToolsOperationCatalog (the single canonical declaration, superset executors {UI, AGENT}),
  // so core + agent-tools install into the one ContributionRegistry without a ref collision. The
  // NAVIGATE_TO_SURFACE ref constant remains here — OperationSubstrateInit registers the
  // NavigateToSurfaceHandler under it, and the agent-tools op's Binding resolves to the same handler.
}
