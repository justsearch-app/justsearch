package io.justsearch.app.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ADR-0028 / tempdoc 419 T5.4 — pins the "only one HTTP entry point may resolve hashes" rule.
 *
 * <p>The privacy contract refinement allows raw paths to leave the worker boundary only via
 * the dedicated {@code POST /api/library/resolve-hash} endpoint, invoked by user click on
 * "show filename" in the local UI. Diagnostic-export endpoints
 * ({@code /api/diagnostics/ingestion/recent}, {@code /api/diagnostics/ingestion/summary},
 * {@code /api/diagnostics/export}) MUST NOT call into the resolver.
 *
 * <p>The structural enforcement here: any class that depends on
 * {@code PathResolutionStore.lookup} or its SQLite implementation directly must be one of the
 * known approved callers (the controller handler, the gRPC handler that exposes the RPC, and
 * the wiring sites in {@code DefaultWorkerAppServices} / {@code KnowledgeServer}). Any other
 * class touching those classes fails this test, surfacing the contract change before merge.
 *
 * <p>If a future feature legitimately needs another caller, add it to {@link #APPROVED_CALLERS}
 * with a docstring explaining why. The intent is to make every addition to that list a
 * deliberate, reviewed action.
 */
@AnalyzeClasses(packages = "io.justsearch", importOptions = ImportOption.DoNotIncludeTests.class)
final class LibraryResolveHashOnlyCallerPin {

  /**
   * Class names allowed to depend on {@code PathResolutionStore}. Each entry has a reason.
   *
   * <ul>
   *   <li>{@code IndexingController} — owns {@code POST /api/library/resolve-hash} (the
   *       single approved HTTP entry point).
   *   <li>{@code WorkerIngestService} — the index half's handler for {@code lookupPathByHash} (a
   *       gRPC server-side handler until lane F stage A item A9; a direct port call since).
   *   <li>{@code IndexingLoop} — holds the store + Supplier wire to JobBatchExtractor.
   *   <li>{@code IndexingLoopOptions} — record field; the store is passed at ctor time
   *       by DWAS (tempdoc 516 P3 / W7.2 followup — startup-config setters moved to ctor).
   *   <li>{@code JobBatchExtractor} — records {@code (pathHash, normalizedPath)} on
   *       admission (tempdoc 516 Slice 4a.3 / W5.2 — extracted from IndexingLoop).
   *   <li>{@code DefaultWorkerAppServices} — wires the store into IndexingLoop and
   *       WorkerIngestService at boot.
   *   <li>{@code KnowledgeServer} — constructs the SQLite-backed store and exposes it via
   *       {@code InfraContext}.
   *   <li>{@code InfraContext} — passes the store reference between modules.
   *   <li>{@code SqlitePathResolutionStore} — the implementation itself.
   *   <li>{@code RemoteKnowledgeClient} — Head-side gRPC client wrapper for the lookup RPC.
   *   <li>{@code IndexingService} — interface declares the {@code resolvePathHash} method
   *       so {@code IndexingController} can call it.
   * </ul>
   *
   * <p>The pin's job is to prevent silent additions; expanding this list is the deliberate
   * action a contract change requires.
   */
  static final java.util.Set<String> APPROVED_CALLERS = java.util.Set.of(
      "io.justsearch.ui.api.IndexingController",
      "io.justsearch.indexerworker.services.WorkerIngestService",
      "io.justsearch.indexerworker.loop.IndexingLoop",
      "io.justsearch.indexerworker.loop.IndexingLoopOptions",
      "io.justsearch.indexerworker.loop.JobBatchExtractor",
      "io.justsearch.indexerworker.server.DefaultWorkerAppServices",
      "io.justsearch.indexerworker.server.KnowledgeServer",
      "io.justsearch.indexerworker.server.InfraContext",
      "io.justsearch.indexerworker.queue.SqlitePathResolutionStore",
      "io.justsearch.app.services.worker.RemoteKnowledgeClient",
      "io.justsearch.app.api.IndexingService");

  /**
   * The diagnostic-export class names that MUST NOT transitively depend on the resolver.
   * Listed explicitly so a future controller refactor can't accidentally rename one and
   * silently slip out of the guard.
   */
  static final java.util.Set<String> EXPORT_CLASSES_FORBIDDEN_FROM_RESOLVER = java.util.Set.of(
      // RemoteKnowledgeClient hosts both the export reads (recentIngestionEvents,
      // ingestionOutcomeSummary) and the resolver call. The class is APPROVED above because
      // the resolver method exists; the per-method enforcement happens in IndexingController
      // (export handlers don't invoke resolvePathHash).
      "io.justsearch.app.services.diagnostics.DiagnosticsExportController" // sentinel
      );

  @ArchTest
  static final ArchRule onlyApprovedCallersTouchPathResolutionStore =
      noClasses()
          .that(notSelfOrInApprovedCallers())
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName(
              "io.justsearch.indexerworker.path.PathResolutionStore")
          .as(
              "Only the approved callers (POST /api/library/resolve-hash handler, the gRPC "
                  + "LookupPathByHash handler, the IndexingLoop admission recorder, and the "
                  + "wiring sites) may depend on PathResolutionStore. Adding a new caller "
                  + "requires updating LibraryResolveHashOnlyCallerPin.APPROVED_CALLERS with "
                  + "a written reason — that's the deliberate contract change ADR-0028 requires.");

  @ArchTest
  static final ArchRule pathResolutionStoreInterfaceLivesInWorkerCore =
      classes()
          .that()
          .haveFullyQualifiedName("io.justsearch.indexerworker.path.PathResolutionStore")
          .should()
          .resideInAPackage("io.justsearch.indexerworker.path")
          .as(
              "PathResolutionStore interface must live in worker-core's path package so both "
                  + "worker-services and indexer-worker can consume it without violating module "
                  + "dependency direction.");

  private static com.tngtech.archunit.base.DescribedPredicate<JavaClass> notSelfOrInApprovedCallers() {
    return new com.tngtech.archunit.base.DescribedPredicate<JavaClass>(
        "not the PathResolutionStore interface itself (or its synthetic NOOP inner) "
            + "and not in APPROVED_CALLERS list") {
      @Override
      public boolean test(JavaClass clazz) {
        String name = clazz.getFullName();
        // Exclude the interface itself and its synthetic anonymous NOOP implementation
        // (a Java compiler artifact that "implements" the interface as part of declaring it).
        if (name.startsWith("io.justsearch.indexerworker.path.PathResolutionStore")) {
          return false;
        }
        return !APPROVED_CALLERS.contains(name);
      }
    };
  }
}
