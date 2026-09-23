package io.justsearch.app.services.registry.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.TrustTier;
import io.justsearch.core.context.EngineContext;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Contract test for {@link CoreOperationCatalog} (per tempdoc 429
 * §"Acceptance criteria" + §"Initial entries").
 *
 * <p>Asserts the 3 seed Operations exercise all three confirm strategies
 * (NONE / INLINE / TYPED) and the documented risk-tier + executor membership.
 */
final class CoreOperationCatalogTest {

  private final CoreOperationCatalog catalog = new CoreOperationCatalog();

  @Test
  void namespaceIsCore() {
    assertEquals("core", catalog.namespace());
  }

  @Test
  void definitionsContainExactlyTwentySevenSeedEntries() {
    // Slice 445: TABULAR Resource cluster — added cancel-indexing-job,
    // retry-indexing-job, resolve-path-hash (item Operations + privacy resolver).
    // Slice 447-followup §X.11.5 Phase 7: added core.rebuild-index parameterless
    // wrapper for 442 §B.9 row 548. Total: 27.
    // Slice 484 §3.6 / observations.md core.index-gc closure: added core.index-gc
    // (phantom Operation reference resolved). Total: 28.
    // Slice 491 §9.D Phase E (C4 / E3): added core.navigate-to-surface; Tempdoc 560 WS4 moved its
    // DEFINITION to AgentToolsOperationCatalog (it is no longer a core *definition* — the ref
    // constant stays in CoreOperationCatalog for handler registration).
    // Tempdoc 931 §E item 10: added core.settle-index (purge deleted-but-unmerged documents so
    // paired evaluation arms compare with equal merge state).
    // Tempdoc 564 facet 4c: the catalog count is DERIVED from the canonical expected id-set below,
    // not a hand-maintained magic number — the redundant count "leg" is subsumed. The id-set is the
    // one irreducible per-op authoring (adding/removing an op updates this single place; count follows).
    Set<String> expectedIds =
        Set.of(
            "core.restart-worker",
            "core.bulk-reindex",
            "core.ping-backend",
            "core.clear-failed-jobs",
            "core.reindex",
            "core.export-diagnostics",
            "core.copy-diagnostic-summary",
            "core.add-watched-root",
            "core.remove-watched-root",
            "core.preview-excludes",
            "core.apply-excludes",
            "core.reload-inference",
            "core.switch-inference-mode",
            "core.set-chat-enabled",
            "core.trigger-offline-processing",
            "core.activate-runtime-variant",
            "core.deactivate-runtime-variant",
            "core.preflight-ai-pack",
            "core.import-ai-pack",
            "core.start-ai-install",
            "core.cancel-ai-install",
            "core.repair-ai-install",
            "core.create-user-policy",
            "core.allowlist-add-digest",
            "core.reset-settings",
            "core.reconfigure",
            "core.cancel-indexing-job",
            "core.retry-indexing-job",
            "core.resolve-path-hash",
            "core.rebuild-index",
            "core.reconcile-root",
            "core.index-gc",
            "core.settle-index");
    Set<String> ids =
        catalog.definitions().stream()
            .map(op -> op.id().value())
            .collect(java.util.stream.Collectors.toSet());
    assertEquals(expectedIds.size(), catalog.definitions().size(), "catalog has no duplicate ids");
    assertEquals(expectedIds, ids);
  }

  @Test
  void restartWorkerHasHighRiskTypedConfirmAndUiOnlyExecutor() {
    Operation op = catalog.findById(CoreOperationCatalog.RESTART_WORKER).orElseThrow();
    assertEquals(RiskTier.HIGH, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.Typed.class, op.policy().confirm());
    ConfirmStrategy.Typed typed = (ConfirmStrategy.Typed) op.policy().confirm();
    assertEquals("ops.restart-worker.confirm", typed.confirmTextKey().value());
    assertEquals(AuditPolicy.METADATA_ONLY, op.policy().audit());
    assertEquals(Set.of(ExecutorTag.UI), op.executors());
    assertFalse(op.policy().undoSupported());
    assertEquals(TrustTier.CORE, op.provenance().tier());
  }

  @Test
  void bulkReindexHasHighRiskInlineConfirmAndUiAgentExecutors() {
    Operation op = catalog.findById(CoreOperationCatalog.BULK_REINDEX).orElseThrow();
    assertEquals(RiskTier.HIGH, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.Inline.class, op.policy().confirm());
    assertEquals(AuditPolicy.METADATA_ONLY, op.policy().audit());
    assertEquals(Set.of(ExecutorTag.UI, ExecutorTag.AGENT), op.executors());
    assertFalse(op.policy().undoSupported());
  }

  @Test
  void bulkReindexAndRecoveryRebuildDeclareDurableReindexOwnership() {
    for (var reference : Set.of(CoreOperationCatalog.BULK_REINDEX, CoreOperationCatalog.REBUILD_INDEX)) {
      Operation op = catalog.findById(reference).orElseThrow();
      assertEquals(OperationKind.REINDEX, op.policy().recordKind(), reference.value());
      assertEquals(EngineContext.Survival.DURABLE,
          op.policy().declaredSurvival().orElseThrow(), reference.value());
    }
  }

  @Test
  void pingBackendHasLowRiskNoneConfirmAndAllExecutors() {
    Operation op = catalog.findById(CoreOperationCatalog.PING_BACKEND).orElseThrow();
    assertEquals(RiskTier.LOW, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.None.class, op.policy().confirm());
    assertEquals(AuditPolicy.NONE, op.policy().audit());
    assertEquals(
        Set.of(ExecutorTag.UI, ExecutorTag.AGENT, ExecutorTag.CLI), op.executors());
    assertTrue(
        op.policy().retry().allowAutoRetry(),
        "ping-backend is idempotent; should auto-retry");
  }

  @Test
  void findByIdValueResolvesEachEntry() {
    assertTrue(catalog.findByIdValue("core.restart-worker").isPresent());
    assertTrue(catalog.findByIdValue("core.bulk-reindex").isPresent());
    assertTrue(catalog.findByIdValue("core.ping-backend").isPresent());
    assertFalse(catalog.findByIdValue("core.nonexistent").isPresent());
  }

  @Test
  void allEntriesHaveCoreProvenance() {
    catalog
        .definitions()
        .forEach(
            op -> {
              assertEquals(TrustTier.CORE, op.provenance().tier());
              assertEquals("core", op.provenance().contributorId());
              assertEquals("1.0", op.provenance().version());
            });
  }

  // -------------------- Slice 3a-2-c Phase G: per-Operation policy assertions --------------------
  // For each Operation added in rounds 3-11, pin its risk tier, confirm
  // strategy, executor tags, and audit policy. Catches substrate-shape
  // regressions (someone accidentally weakening a HIGH→MEDIUM, dropping
  // typed-confirm, etc.) at unit-test time.
  //
  // Pattern mirrors restartWorkerHasHighRiskTypedConfirmAndUiOnlyExecutor
  // (the existing template). Group by cluster.

  @Test
  void clearFailedJobsHasMediumRiskInlineConfirmUiOnly() {
    Operation op = catalog.findById(CoreOperationCatalog.CLEAR_FAILED_JOBS).orElseThrow();
    assertEquals(RiskTier.MEDIUM, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.Inline.class, op.policy().confirm());
    assertEquals(Set.of(ExecutorTag.UI), op.executors());
    assertFalse(op.policy().undoSupported());
  }

  @Test
  void indexGcHasMediumRiskInlineConfirmUiOnly() {
    // Slice 484 §3.6 / observations.md core.index-gc closure.
    Operation op = catalog.findById(CoreOperationCatalog.INDEX_GC).orElseThrow();
    assertEquals(RiskTier.MEDIUM, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.Inline.class, op.policy().confirm());
    assertEquals(Set.of(ExecutorTag.UI), op.executors());
    assertFalse(op.policy().undoSupported());
  }

  @Test
  void reindexHasLowRiskNoConfirmUiAndAgent() {
    Operation op = catalog.findById(CoreOperationCatalog.REINDEX).orElseThrow();
    assertEquals(RiskTier.LOW, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.None.class, op.policy().confirm());
    assertEquals(EngineContext.Survival.DURABLE,
        op.policy().declaredSurvival().orElseThrow());
    assertEquals(Set.of(ExecutorTag.UI, ExecutorTag.AGENT), op.executors());
  }

  @Test
  void exportDiagnosticsHasLowRiskNoConfirmUiOnly() {
    Operation op = catalog.findById(CoreOperationCatalog.EXPORT_DIAGNOSTICS).orElseThrow();
    assertEquals(RiskTier.LOW, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.None.class, op.policy().confirm());
    assertEquals(Set.of(ExecutorTag.UI), op.executors());
  }

  @Test
  void copyDiagnosticSummaryIsHeadLocalLowRiskMetadataOnlyUserOperation() {
    Operation op = catalog.findById(CoreOperationCatalog.COPY_DIAGNOSTIC_SUMMARY).orElseThrow();
    assertEquals(RiskTier.LOW, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.None.class, op.policy().confirm());
    assertEquals(AuditPolicy.METADATA_ONLY, op.policy().audit());
    assertEquals(Set.of(ExecutorTag.UI), op.executors());
    assertEquals(Audience.USER, op.audience());
    assertTrue(
        op.policy().requiredCapabilities().isEmpty(),
        "copy summary must remain available without Worker or Inference");
    assertTrue(op.intf().result().contains("\"required\":[\"summary\"]"));
  }

  @Test
  void exportDiagnosticsIsUserAudienceWhileStateMutatingSiblingsStayOperator() {
    // Tempdoc 689 decision: core.export-diagnostics is a read-only, privacy-redacted,
    // local-only export — a user self-service support flow, not an admin action. Pin
    // this deliberately asymmetric to its state-mutating siblings (clear-failed-jobs,
    // index-gc, restart-worker), which remain Audience.OPERATOR, so a future blanket
    // audience change fails loudly instead of silently widening operator-only actions
    // to end users.
    Operation exportDiagnostics =
        catalog.findById(CoreOperationCatalog.EXPORT_DIAGNOSTICS).orElseThrow();
    Operation clearFailedJobs =
        catalog.findById(CoreOperationCatalog.CLEAR_FAILED_JOBS).orElseThrow();
    Operation indexGc = catalog.findById(CoreOperationCatalog.INDEX_GC).orElseThrow();
    Operation restartWorker = catalog.findById(CoreOperationCatalog.RESTART_WORKER).orElseThrow();

    assertEquals(Audience.USER, exportDiagnostics.audience());
    assertEquals(Audience.OPERATOR, clearFailedJobs.audience());
    assertEquals(Audience.OPERATOR, indexGc.audience());
    assertEquals(Audience.OPERATOR, restartWorker.audience());
  }

  @Test
  void addWatchedRootHasLowRiskNoConfirmUiAndAgent() {
    Operation op = catalog.findById(CoreOperationCatalog.ADD_WATCHED_ROOT).orElseThrow();
    assertEquals(RiskTier.LOW, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.None.class, op.policy().confirm());
    assertEquals(Set.of(ExecutorTag.UI, ExecutorTag.AGENT), op.executors());
    // Required arg: path
    assertTrue(op.intf().inputs().contains("\"required\":[\"path\"]"));
  }

  @Test
  void removeWatchedRootHasMediumRiskInlineConfirmUiOnly() {
    Operation op = catalog.findById(CoreOperationCatalog.REMOVE_WATCHED_ROOT).orElseThrow();
    assertEquals(RiskTier.MEDIUM, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.Inline.class, op.policy().confirm());
    assertEquals(Set.of(ExecutorTag.UI), op.executors());
    assertTrue(op.intf().inputs().contains("\"required\":[\"path\"]"));
  }

  @Test
  void previewExcludesHasLowRiskNoConfirm() {
    Operation op = catalog.findById(CoreOperationCatalog.PREVIEW_EXCLUDES).orElseThrow();
    assertEquals(RiskTier.LOW, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.None.class, op.policy().confirm());
  }

  /**
   * apply-excludes is the second HIGH-risk + Typed-confirm Operation in the
   * substrate (after restart-worker). Pinning ensures the typed-confirm
   * isn't accidentally weakened.
   */
  @Test
  void applyExcludesHasHighRiskTypedConfirm() {
    Operation op = catalog.findById(CoreOperationCatalog.APPLY_EXCLUDES).orElseThrow();
    assertEquals(RiskTier.HIGH, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.Typed.class, op.policy().confirm());
    ConfirmStrategy.Typed typed = (ConfirmStrategy.Typed) op.policy().confirm();
    assertEquals("ops.apply-excludes.confirm", typed.confirmTextKey().value());
    assertEquals(Set.of(ExecutorTag.UI), op.executors());
  }

  @Test
  void reloadInferenceHasMediumRiskInlineConfirm() {
    Operation op = catalog.findById(CoreOperationCatalog.RELOAD_INFERENCE).orElseThrow();
    assertEquals(RiskTier.MEDIUM, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.Inline.class, op.policy().confirm());
  }

  /**
   * switch-inference-mode is the first arg-bearing Operation with a wire
   * enum. Pin both the risk and the enum membership in the input schema.
   */
  @Test
  void switchInferenceModeHasMediumRiskInlineConfirmAndEnumArg() {
    Operation op = catalog.findById(CoreOperationCatalog.SWITCH_INFERENCE_MODE).orElseThrow();
    assertEquals(RiskTier.MEDIUM, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.Inline.class, op.policy().confirm());
    assertEquals(Set.of(ExecutorTag.UI, ExecutorTag.AGENT), op.executors());
    String inputs = op.intf().inputs();
    assertTrue(inputs.contains("\"enum\":[\"online\",\"indexing\"]"));
    assertTrue(inputs.contains("\"required\":[\"mode\"]"));
  }

  /**
   * Tempdoc 737 §12b: the intent-write op that supersedes switch-inference-mode. Pin its
   * no-precondition / no-confirm shape, its enabled arg, its executors, and the supersedes edge —
   * the properties the design turns on (an intent write cannot be gated or denied).
   */
  @Test
  void setChatEnabledIsLowRiskNoConfirmNoPreconditionsAndSupersedesSwitchMode() {
    Operation op = catalog.findById(CoreOperationCatalog.SET_CHAT_ENABLED).orElseThrow();
    assertEquals(RiskTier.LOW, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.None.class, op.policy().confirm());
    assertTrue(
        op.policy().requiredCapabilities().isEmpty(),
        "an intent write must carry no required capabilities (no preconditions)");
    assertEquals(Set.of(ExecutorTag.UI, ExecutorTag.AGENT), op.executors());
    assertTrue(op.intf().inputs().contains("\"required\":[\"enabled\"]"));
    assertEquals(
        Set.of(CoreOperationCatalog.SWITCH_INFERENCE_MODE),
        op.lineage().supersedes(),
        "the newer op declares that it supersedes switch-inference-mode");
  }

  @Test
  void triggerOfflineProcessingHasLowRiskNoConfirm() {
    Operation op = catalog.findById(CoreOperationCatalog.TRIGGER_OFFLINE_PROCESSING).orElseThrow();
    assertEquals(RiskTier.LOW, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.None.class, op.policy().confirm());
    assertEquals(Set.of(ExecutorTag.UI, ExecutorTag.AGENT), op.executors());
  }

  @Test
  void activateRuntimeVariantHasMediumRiskInlineConfirmRequiringVariantId() {
    Operation op = catalog.findById(CoreOperationCatalog.ACTIVATE_RUNTIME_VARIANT).orElseThrow();
    assertEquals(RiskTier.MEDIUM, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.Inline.class, op.policy().confirm());
    assertEquals(Set.of(ExecutorTag.UI), op.executors());
    assertTrue(op.intf().inputs().contains("\"required\":[\"variantId\"]"));
  }

  @Test
  void deactivateRuntimeVariantHasMediumRiskInlineConfirmNoArgs() {
    Operation op = catalog.findById(CoreOperationCatalog.DEACTIVATE_RUNTIME_VARIANT).orElseThrow();
    assertEquals(RiskTier.MEDIUM, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.Inline.class, op.policy().confirm());
  }

  @Test
  void preflightAiPackHasLowRiskNoConfirmRequiringPath() {
    Operation op = catalog.findById(CoreOperationCatalog.PREFLIGHT_AI_PACK).orElseThrow();
    assertEquals(RiskTier.LOW, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.None.class, op.policy().confirm());
    assertTrue(op.intf().inputs().contains("\"required\":[\"path\"]"));
  }

  @Test
  void importAiPackHasMediumRiskInlineConfirmRequiringPath() {
    Operation op = catalog.findById(CoreOperationCatalog.IMPORT_AI_PACK).orElseThrow();
    assertEquals(RiskTier.MEDIUM, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.Inline.class, op.policy().confirm());
    assertTrue(op.intf().inputs().contains("\"required\":[\"path\"]"));
  }

  @Test
  void startAiInstallHasMediumRiskInlineConfirm() {
    Operation op = catalog.findById(CoreOperationCatalog.START_AI_INSTALL).orElseThrow();
    assertEquals(RiskTier.MEDIUM, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.Inline.class, op.policy().confirm());
  }

  @Test
  void cancelAiInstallHasMediumRiskInlineConfirm() {
    Operation op = catalog.findById(CoreOperationCatalog.CANCEL_AI_INSTALL).orElseThrow();
    assertEquals(RiskTier.MEDIUM, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.Inline.class, op.policy().confirm());
  }

  @Test
  void repairAiInstallHasMediumRiskInlineConfirm() {
    Operation op = catalog.findById(CoreOperationCatalog.REPAIR_AI_INSTALL).orElseThrow();
    assertEquals(RiskTier.MEDIUM, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.Inline.class, op.policy().confirm());
  }

  @Test
  void createUserPolicyHasMediumRiskInlineConfirmRequiringManifestSha() {
    Operation op = catalog.findById(CoreOperationCatalog.CREATE_USER_POLICY).orElseThrow();
    assertEquals(RiskTier.MEDIUM, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.Inline.class, op.policy().confirm());
    assertTrue(op.intf().inputs().contains("\"required\":[\"manifestSha256\"]"));
  }

  @Test
  void allowlistAddDigestHasMediumRiskInlineConfirmRequiringManifestSha() {
    Operation op = catalog.findById(CoreOperationCatalog.ALLOWLIST_ADD_DIGEST).orElseThrow();
    assertEquals(RiskTier.MEDIUM, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.Inline.class, op.policy().confirm());
    assertTrue(op.intf().inputs().contains("\"required\":[\"manifestSha256\"]"));
  }

  @Test
  void resetSettingsHasMediumRiskInlineConfirmNoArgs() {
    Operation op = catalog.findById(CoreOperationCatalog.RESET_SETTINGS).orElseThrow();
    assertEquals(RiskTier.MEDIUM, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.Inline.class, op.policy().confirm());
    assertEquals(Set.of(ExecutorTag.UI), op.executors());
  }

  @Test
  void reconfigureIsAUiOnlyNoConfirmReconfigureRecordWithWitnessEnvelope() {
    Operation op = catalog.findById(CoreOperationCatalog.RECONFIGURE).orElseThrow();
    assertEquals(RiskTier.MEDIUM, op.policy().risk());
    assertInstanceOf(ConfirmStrategy.None.class, op.policy().confirm());
    assertEquals(OperationKind.RECONFIGURE, op.policy().recordKind());
    assertEquals(AuditPolicy.METADATA_ONLY, op.policy().audit());
    assertEquals(Set.of(ExecutorTag.UI), op.executors());
    assertTrue(op.intf().inputs().contains("\"settings\""));
    assertTrue(op.intf().inputs().contains("\"witness\""));
    assertTrue(op.intf().inputs().contains("\"operationKey\""));
    assertTrue(op.intf().inputs().contains("\"modeIntent\""));
  }

  /**
   * Cross-cutting invariant: every operation's auditPolicy is at least
   * METADATA_ONLY (or NONE for read-only ping-backend-likes). Catches
   * accidental drops to no-audit on mutating operations.
   */
  @Test
  void allMutatingOperationsAuditAtLeastMetadata() {
    catalog
        .definitions()
        .forEach(
            op -> {
              if (op.policy().risk() == RiskTier.LOW) {
                // LOW-risk read-onlys may have audit=NONE (e.g. ping-backend).
                return;
              }
              assertEquals(
                  AuditPolicy.METADATA_ONLY,
                  op.policy().audit(),
                  "Operation " + op.id().value() + " (risk=" + op.policy().risk()
                      + ") must audit at least METADATA_ONLY");
            });
  }
}
