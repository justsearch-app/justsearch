package io.justsearch.app.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * ArchUnit tests enforcing module layering rules.
 *
 * <p>These rules prevent improper dependencies between modules, ensuring:
 * <ul>
 *   <li>Foundation modules remain leaf nodes (no deps on higher layers)</li>
 *   <li>UI is only consumed by app-launcher (the entry point)</li>
 *   <li>app-* modules don't depend on ui</li>
 *   <li>Core/API modules don't depend on implementation modules</li>
 * </ul>
 *
 * <p>See docs/tempdocs/34-module-structure-and-build-graph.md for the full dependency graph.
 */
@AnalyzeClasses(packages = "io.justsearch", importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringEnforcementTest {

  // =========================================================================
  // Rule 1: Foundation modules must remain leaf nodes
  // =========================================================================

  @ArchTest
  static final ArchRule coreModuleMustNotDependOnHigherLayers =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.core..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "io.justsearch.ui..",
              "io.justsearch.app..",
              "io.justsearch.adapters..",
              "io.justsearch.aibackend..",
              "io.justsearch.indexing..",
              "io.justsearch.reranker..",
              "io.justsearch.ipc..",
              "io.justsearch.indexerworker..",
              "io.justsearch.aiworker..")
          .as("core module must remain a foundation leaf (no deps on higher layers)");

  @ArchTest
  static final ArchRule configurationModuleMustNotDependOnHigherLayers =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.configuration..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "io.justsearch.ui..",
              "io.justsearch.app..",
              "io.justsearch.adapters..",
              "io.justsearch.aibackend..",
              "io.justsearch.indexing..",
              "io.justsearch.reranker..",
              "io.justsearch.ipc..",
              "io.justsearch.core..",
              "io.justsearch.indexerworker..",
              "io.justsearch.aiworker..")
          .as("configuration module must remain a foundation leaf (no deps on higher layers)");

  @ArchTest
  static final ArchRule telemetryModuleMustNotDependOnHigherLayers =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.telemetry..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "io.justsearch.ui..",
              "io.justsearch.app..",
              "io.justsearch.adapters..",
              "io.justsearch.aibackend..",
              "io.justsearch.indexing..",
              "io.justsearch.reranker..",
              "io.justsearch.ipc..",
              "io.justsearch.configuration..",
              "io.justsearch.indexerworker..",
              "io.justsearch.aiworker..")
          .as("telemetry may use neutral execution contracts but no higher layers");

  @ArchTest
  static final ArchRule telemetryCoreDependencyIsOnlyTheNeutralExecutionContract =
      noClasses().that().resideInAnyPackage("io.justsearch.telemetry..")
          .should().dependOnClassesThat(
              JavaClass.Predicates
                  .resideInAPackage("io.justsearch.core..")
                  .and(DescribedPredicate.not(
                      JavaClass.Predicates
                          .resideInAPackage("io.justsearch.core.execution.."))))
          .as("telemetry core dependency is restricted to the neutral execution contract");

  // =========================================================================
  // Rule 2: UI module is the top of the stack (only app-launcher consumes it)
  // =========================================================================

  @ArchTest
  static final ArchRule onlyAppLauncherMayDependOnUi =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch..")
          .and()
          .resideOutsideOfPackage("io.justsearch.app.launcher..")
          .and()
          .resideOutsideOfPackage("io.justsearch.ui..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("io.justsearch.ui..")
          .as("only app-launcher may depend on ui module (ui is the top layer)");

  // =========================================================================
  // Rule 3: app-api must remain a clean contract seam
  // =========================================================================

  @ArchTest
  static final ArchRule appApiMustNotDependOnImplementations =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.app.api..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "io.justsearch.app.services..",
              "io.justsearch.app.inference..",
              "io.justsearch.app.pipeline..",
              "io.justsearch.app.launcher..",
              "io.justsearch.ui..",
              "io.justsearch.adapters..",
              "io.justsearch.aibackend..",
              "io.justsearch.indexing..",
              "io.justsearch.ipc..",
              "io.justsearch.indexerworker..",
              "io.justsearch.aiworker..")
          .as("app-api must remain a clean contract seam (no implementation deps)");

  // =========================================================================
  // Rule 4: Worker modules should not depend on UI
  // =========================================================================

  @ArchTest
  static final ArchRule indexerWorkerMustNotDependOnUi =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.indexerworker..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("io.justsearch.ui..")
          // Lane F stage A item A2: predicate unchanged, wording re-cut. This used to read
          // "(runs in separate process)", which stops being the reason once the Head and the
          // Worker share one JVM. The direction of the edge is the reason, and it survives the
          // merge: the index half is below the API front and never reaches up into it.
          .as("indexer-worker must not depend on ui (the index half never reaches the API front)");

  // =========================================================================
  // Rule 5: Prevent circular dependencies between key modules
  // =========================================================================

  @ArchTest
  static final ArchRule appInferenceMustNotDependOnAppServices =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.app.inference..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("io.justsearch.app.services..")
          .as("app-inference must not depend on app-services (extracted module)");

  @ArchTest
  static final ArchRule ipcCommonMustNotDependOnHigherLayers =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.ipc..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "io.justsearch.ui..",
              "io.justsearch.app.services..",
              "io.justsearch.app.inference..",
              "io.justsearch.app.launcher..",
              "io.justsearch.adapters..",
              "io.justsearch.aibackend..",
              "io.justsearch.indexing..",
              "io.justsearch.indexerworker..",
              "io.justsearch.aiworker..")
          .as("ipc-common must not depend on higher layers (shared IPC contract)");

  // =========================================================================
  // Rule 6: Worker modules must not depend on app-services (the port points one way)
  // =========================================================================

  @ArchTest
  static final ArchRule indexerWorkerMustNotDependOnAppServices =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.indexerworker..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("io.justsearch.app.services..")
          .allowEmptyShould(true)
          // Lane F stage A item A2: predicate unchanged, wording re-cut. "(isolated worker)"
          // was a process claim; after the merge the two halves share a JVM and the rule's
          // reason is the port's direction, which the merge does not change.
          .as("indexer-worker must not depend on app-services (the port is called inward only)");

  // =========================================================================
  // Rule 6b: only the Engine composition root, the worker itself and the Lucene
  // adapters may reach the worker's internals
  // =========================================================================

  /**
   * Lane F design 3.3, added at stage A item A2. The merge puts the Head and the Worker in one
   * JVM, which removes the process boundary that used to make this unreachable by construction —
   * so it becomes an ArchUnit pin instead. {@code io.justsearch.app.engine..} is the composition
   * root (it binds the ports and must see both halves), {@code io.justsearch.indexerworker..} is
   * the worker's own code, and {@code io.justsearch.adapters..} is the Lucene layer the worker is
   * built on. Everything else — the API front, orchestration, the launcher — reaches the index
   * through a port.
   *
   * <p>The class-level half of hard invariant 1 stays where it is
   * ({@code IndexWriterOwnershipTest#onlyLuceneOwnersMayDependOnLuceneClasses}); this rule is the
   * module-level half that replaces the process boundary. Both are pinned by ADR-0049, stage A
   * item A15, which supersedes ADR-0001 and ADR-0002.
   *
   * <p>Scope note: {@code @AnalyzeClasses} above sets {@link
   * com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests}, so this rule reads
   * production bytecode only. Test code that constructs a worker service directly is out of
   * scope by construction, not by exemption.
   */
  @ArchTest
  static final ArchRule onlyEngineAndWorkerMayDependOnWorkerInternals =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch..")
          .and()
          .resideOutsideOfPackages(
              "io.justsearch.app.engine..",
              "io.justsearch.indexerworker..",
              "io.justsearch.adapters..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "io.justsearch.indexerworker.server..",
              "io.justsearch.indexerworker.services..",
              "io.justsearch.indexerworker.loop..")
          .as(
              "sharing a JVM does not license application code to reach past a port into Lucene"
                  + " (ADR-0049): only io.justsearch.app.engine.., io.justsearch.indexerworker.."
                  + " and io.justsearch.adapters.. may depend on"
                  + " io.justsearch.indexerworker.{server,services,loop}..");

  // =========================================================================
  // Rule 8: No direct java.util.logging usage — use SLF4J
  // =========================================================================

  @ArchTest
  static final ArchRule noDirectJulUsage =
      noClasses()
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("java.util.logging.Logger")
          .as("Use SLF4J (org.slf4j.Logger) instead of java.util.logging.Logger");

  // =========================================================================
  // Rule 7: UI must use GPL contracts from app-api, not concrete implementations
  // =========================================================================

  @ArchTest
  static final ArchRule uiMustNotDependOnGplImplementations =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.ui..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("io.justsearch.app.services.gpl..")
          .as("ui must access GPL types through app-api contracts (GplStatusProvider,"
              + " RerankerService, GplEvalData), not concrete implementations");

  // =========================================================================
  // Rule 9: asynchronous work must name its executor
  // =========================================================================

  private static final String COMPLETABLE_FUTURE = "java.util.concurrent.CompletableFuture";
  private static final String EXECUTOR = "java.util.concurrent.Executor";

  private static final ArchCondition<JavaClass> asyncCallsNameExecutor =
      new ArchCondition<>("use an executor for asynchronous CompletableFuture work") {
        @Override
        public void check(JavaClass item, ConditionEvents events) {
          for (var call : item.getCodeUnitAccessesFromSelf()) {
            String owner = call.getTargetOwner().getFullName();
            String name = call.getName();
            if ((COMPLETABLE_FUTURE.equals(owner) || "java.util.concurrent.CompletionStage".equals(owner))
                && name.endsWith("Async")
                && call.getTarget().getRawParameterTypes().stream()
                    .noneMatch(parameter -> EXECUTOR.equals(parameter.getFullName()))) {
              events.add(
                  SimpleConditionEvent.violated(
                      item,
                      item.getSimpleName()
                          + " calls CompletableFuture#"
                          + name
                          + " without an Executor at "
                          + call.getSourceCodeLocation()
                          + " — asynchronous work must use a registered executor (C1-6)"));
            }
            if ("java.util.concurrent.ForkJoinPool".equals(owner)
                && "commonPool".equals(name)) {
              events.add(
                  SimpleConditionEvent.violated(
                      item,
                      item.getSimpleName()
                          + " calls ForkJoinPool.commonPool() at "
                          + call.getSourceCodeLocation()
                          + " — use a registered executor (C1-6)"));
            }
            if ("parallelStream".equals(name)
                || ("parallel".equals(name) && owner.startsWith("java.util.stream."))) {
              events.add(
                  SimpleConditionEvent.violated(
                      item,
                      item.getSimpleName()
                          + " calls "
                          + name
                          + "() at "
                          + call.getSourceCodeLocation()
                          + " — parallel stream work bypasses executor admission (C1-6)"));
            }
          }
        }
      };

  @ArchTest
  static final ArchRule asynchronousCallsMustUseRegisteredExecutors =
      classes().should(asyncCallsNameExecutor).as(
          "CompletableFuture *Async calls must pass an Executor; commonPool and parallel streams"
              + " are prohibited (C1-6)");
}
