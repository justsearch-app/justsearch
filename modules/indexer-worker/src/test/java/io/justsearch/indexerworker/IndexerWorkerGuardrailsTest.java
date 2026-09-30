package io.justsearch.indexerworker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.loop.ops.EmbeddingRecoveryOps;
import io.justsearch.indexerworker.services.WorkerIngestService;

@AnalyzeClasses(packages = "io.justsearch.indexerworker", importOptions = ImportOption.DoNotIncludeTests.class)
class IndexerWorkerGuardrailsTest {
  // `indexerWorkerMustNotReadEnvOrSystemProperties` and its five exemptions (DevReloadManager,
  // IndexStatusOps, WorkerHealthService, TikaOcrRuntime, ExtractionSandboxCommand) were retired in
  // tempdoc 883 decision 5. The single repo-wide replacement is
  // io.justsearch.deadcode.SystemAccessFunnelTest (modules/dead-code-audit); each exemption is now
  // a line in gates/config-surface/sysaccess-allowlist.txt, a ratchet that only shrinks. The
  // reasons they were exempt (a dev-only build stamp, a test-only synthetic-delay hook,
  // TikaOcrRuntime's native-resource DISCOVERY of Tesseract/tessdata on the worker host, and
  // ExtractionSandboxCommand's `java.home` fallback for the JVM's own launcher path) are recorded
  // in that file rather than here, so one list carries both the entries and their justifications.

  @ArchTest
  static final ArchRule indexerWorkerMustNotDependOnTestSupport =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.indexerworker..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("io.justsearch.testsupport..");

  // Lane F stage A item A10 deleted MmfWorkerSignalBus, this rule's one exemption. Strengthened
  // rather than retired with it (the same move as its app-services twin): the invariant is that the
  // index half does no memory-mapped IO, and inside one JVM there is no cross-process signal left
  // to justify re-opening the exemption.
  @ArchTest
  static final ArchRule indexerWorkerMustNotDoMemoryMappedIo =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.indexerworker..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("java.nio.MappedByteBuffer");

  // ============================================================
  // Tempdoc 517 — capture-once enforcement on the search-execution package layout.
  //
  // The SearchInputCapture class in services.input.* is the only class permitted
  // to depend on the IO primitives (CommitOps, IndexCountOps, encoders, the
  // EmbeddingProvider). The planner (services.plan.*) and response-builder
  // (services.respond.*) must depend only on captured values flowing through
  // SearchInputs. This makes the capture-once invariant (cluster snapshot,
  // corpus profile, encode result) a compile-time check rather than a comment.
  //
  // SearchExecutor (services.execute.*) is permitted to depend on these types
  // because it runs the actual retrieval IO. The point of the rule is to keep
  // planner + response-builder pure relative to the captured SearchInputs.
  // ============================================================

  @ArchTest
  static final ArchRule plannerMustNotDependOnIoPrimitives =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.indexerworker.services.plan..")
          .should()
          .dependOnClassesThat()
          .haveSimpleName("CommitOps")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("IndexCountOps")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("SpladeEncoder")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("SpladeIdfQueryEncoder")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("BgeM3Encoder")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("EmbeddingProvider");

  @ArchTest
  static final ArchRule responseBuilderMustNotDependOnEncoders =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.indexerworker.services.respond..")
          .should()
          .dependOnClassesThat()
          .haveSimpleName("SpladeEncoder")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("SpladeIdfQueryEncoder")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("BgeM3Encoder")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("EmbeddingProvider")
          .orShould()
          .dependOnClassesThat()
          .haveSimpleName("CommitOps");

  // ============================================================
  // Tempdoc 726 F3 — the embedding auto-rescue must go through EmbeddingRecoveryOps.
  //
  // A BLOCKED_LEGACY index has no stored embedding fingerprint, so vectors on documents already
  // marked COMPLETED have unknowable provenance. The backfill only picks up PENDING documents and
  // certification fires on pending==0, so a rescue that transitions to REBUILDING WITHOUT first
  // re-marking those documents PENDING certifies instantly, re-embeds nothing, and stamps a
  // fingerprint attesting to vectors no one re-embedded. EmbeddingRecoveryOps.rescueBlockedLegacyIndex
  // is the one place that orders re-mark-then-transition correctly.
  //
  // This has been written wrong twice by two independent authors, which is why it is a rule and not
  // a comment: the ordering is invisible at the call site, and calling the controller directly
  // compiles fine.
  // ============================================================

  /**
   * A call to an ECC auto-rescue entry point ({@code maybeAutoStartRebuildFor*}) from anywhere but
   * the seam. The controller's own internals are exempt (today {@code
   * maybeAutoStartRebuildForBlockedLegacy} delegates to {@code onForcedReindexRequested}).
   *
   * <p>Matched by method-name prefix, not exact name, so a NEW auto-rescue variant is caught the day
   * it lands rather than needing this rule updated to know about it.
   */
  private static final DescribedPredicate<JavaCall<?>> AUTO_RESCUE_CALL_OUTSIDE_THE_SEAM =
      new DescribedPredicate<>(
          "call an EmbeddingCompatibilityController auto-rescue entry point"
              + " (maybeAutoStartRebuildFor*) from outside EmbeddingRecoveryOps") {
        @Override
        public boolean test(JavaCall<?> call) {
          if (!call.getTargetOwner()
              .getName()
              .equals(EmbeddingCompatibilityController.class.getName())) {
            return false;
          }
          if (!call.getName().startsWith("maybeAutoStartRebuildFor")) {
            return false;
          }
          String origin = call.getOriginOwner().getName();
          return !origin.equals(EmbeddingRecoveryOps.class.getName())
              && !origin.equals(EmbeddingCompatibilityController.class.getName());
        }
      };

  /**
   * A call to the raw forced-reindex trigger from outside the allowlist. Without this, the rule
   * above is trivially bypassed: a rescue can skip the {@code maybeAutoStartRebuildFor*} family and
   * flip the state machine straight through {@code onForcedReindexRequested}.
   *
   * <p>{@code WorkerIngestService} is deliberately legal — it triggers on a user-initiated forced
   * reindex, where the re-embed work comes from the surrounding ingest request that rewrites
   * documents to PENDING, not from the flag flip. That is the criterion for any future addition
   * here: real pending work must already be guaranteed by the caller's own context.
   */
  private static final DescribedPredicate<JavaCall<?>> FORCED_REINDEX_TRIGGER_OUTSIDE_ALLOWLIST =
      new DescribedPredicate<>(
          "call EmbeddingCompatibilityController.onForcedReindexRequested from outside"
              + " EmbeddingRecoveryOps / WorkerIngestService") {
        @Override
        public boolean test(JavaCall<?> call) {
          if (!call.getTargetOwner()
              .getName()
              .equals(EmbeddingCompatibilityController.class.getName())) {
            return false;
          }
          if (!call.getName().equals("onForcedReindexRequested")) {
            return false;
          }
          String origin = call.getOriginOwner().getName();
          return !origin.equals(EmbeddingRecoveryOps.class.getName())
              && !origin.equals(EmbeddingCompatibilityController.class.getName())
              && !origin.equals(WorkerIngestService.class.getName());
        }
      };

  @ArchTest
  static final ArchRule embeddingAutoRescueMustGoThroughEmbeddingRecoveryOps =
      noClasses()
          .should()
          .callMethodWhere(AUTO_RESCUE_CALL_OUTSIDE_THE_SEAM)
          .because(
              "tempdoc 726 F3 — an embedding auto-rescue must re-mark the unknown-provenance"
                  + " COMPLETED/FAILED parent docs PENDING BEFORE transitioning to REBUILDING,"
                  + " otherwise pending==0 is already true, certification fires instantly, nothing"
                  + " is re-embedded, and the current fingerprint is stamped over vectors it does"
                  + " not attest to. Call"
                  + " EmbeddingRecoveryOps.rescueBlockedLegacyIndex(ecc, ingestLifecycle,"
                  + " batchSize, log) instead of driving the controller directly — it is the one"
                  + " place that orders re-mark-then-transition correctly, and it returns the"
                  + " re-marked count so the caller can log it. If you are adding a NEW rescue"
                  + " trigger, put its condition inside that method rather than calling the"
                  + " controller from a new site");

  @ArchTest
  static final ArchRule forcedReindexTriggerMustNotBypassTheRescueSeam =
      noClasses()
          .should()
          .callMethodWhere(FORCED_REINDEX_TRIGGER_OUTSIDE_ALLOWLIST)
          .because(
              "tempdoc 726 F3 — onForcedReindexRequested only flips the state machine; it queues no"
                  + " re-embed work. It is safe from WorkerIngestService because a user-initiated"
                  + " forced reindex rides an ingest request that rewrites documents to PENDING"
                  + " itself. Anywhere else it is the same unsound shortcut as calling the"
                  + " auto-rescue entry points directly: use"
                  + " EmbeddingRecoveryOps.rescueBlockedLegacyIndex, which re-marks first. Adding a"
                  + " caller here requires showing that real PENDING work is guaranteed by the"
                  + " caller's own context");

  // NOTE — a third tempdoc-517 rule ("encoder imports confined to input-capture")
  // was considered but dropped. Peer classes outside the search-execution
  // scope (CitationMatchOps for citation embeddings; WorkerHealthService for
  // readiness probes; RagContextOps for RAG embeddings; WorkerSearchService for
  // gRPC-level wiring) legitimately depend on EmbeddingProvider/SPLADE/BGE-M3
  // for their own concerns. The narrower rules above (planner / responder no IO)
  // enforce what tempdoc 517's design actually requires — namely that the
  // captured-once invariant holds for SearchInputs consumers — without
  // over-reaching into peer-class concerns.
}
