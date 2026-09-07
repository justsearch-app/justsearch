package io.justsearch.app.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * ArchUnit tests to detect unreferenced (potentially dead) code.
 *
 * <p>Uses bytecode analysis to find private and package-private methods that have zero incoming
 * references from other code units. This helps identify dead code that can be safely removed.
 *
 * <p>Known limitations:
 *
 * <ul>
 *   <li>Reflection-based calls are invisible to bytecode analysis
 *   <li>String constants may be inlined, causing false positives
 *   <li>Framework callbacks (Jackson, gRPC) need explicit exclusions
 *   <li>Test-only callers are invisible (ArchUnit excludes test sources)
 *   <li>Inherited method calls may resolve to the subclass rather than the declaring class
 * </ul>
 *
 * <p>Methods that ArchUnit cannot trace (test-only callers, reflection, inheritance) are listed in
 * {@link #KNOWN_UNREFERENCED} with a documented reason for each. These are also annotated with
 * {@code @SuppressWarnings("unused")} in source for IDE support.
 *
 * @see <a href="https://jdriven.com/blog/2021/01/Detect-delete-unreferenced-code-with-ArchUnit">
 *     JDriven: Detect & delete unreferenced code with ArchUnit</a>
 */
@AnalyzeClasses(packages = "io.justsearch", importOptions = ImportOption.DoNotIncludeTests.class)
class UnreferencedCodeTest {

  /**
   * Methods that ArchUnit's bytecode analysis cannot trace but are verified to have callers.
   * Keyed by "SimpleClassName.methodName", value is the reason for exemption.
   *
   * <p>To add an entry: verify the method has callers (tests, reflection, inheritance), annotate
   * the method with {@code @SuppressWarnings("unused")} in source, then add it here.
   *
   * <p>To audit: grep for the method name across the codebase. If no callers exist, remove the
   * entry and delete the method.
   */
  private static final Map<String, String> KNOWN_UNREFERENCED =
      Map.ofEntries(
          // Test-only callers (ArchUnit excludes test sources)
          Map.entry("RetryDecision.action", "AgentRetryPolicyTest"),
          Map.entry("Builder.operationLeaseService",
              "UpgradeLifecycleContractTest (LocalApiServer.Builder op-lease test seam; production "
                  + "resolves the service from HeadAssembly.serviceOut(), tempdoc 617)"),
          Map.entry("LauncherEnvironment.HeadAssembly", "LauncherEnvironmentCloseTest"),
          Map.entry("LauncherEnvironment.configManager", "LauncherEnvironmentCloseTest"),
          Map.entry("LauncherEnvironment.telemetry", "LauncherEnvironmentCloseTest"),
          Map.entry("InferenceLifecycleManager.formatContextAsNumberedPassages",
              "RAGContextTest (tempdoc 849: the head's section-aware cut asserts its assembled "
                  + "context still parses through the REAL online-path parser, so this delegate is "
                  + "the cross-module seam it round-trips through; OnlineModeOpsTest calls "
                  + "OnlineModeOps directly, not this)"),
          Map.entry("InferenceLifecycleManager.extractUsageFromChatChunk", "LlamaServerUsageParsingTest"),
          // Tempdoc 885 item 14: the pool's reuse and recycle properties have no other observable.
          // "Three requests succeeded" is also true of three spawns, so the tests assert the child
          // PID and the spawn/restart counts; without these they would pass for the wrong reason.
          Map.entry("PersistentExtractionSandbox.spawnCount", "PersistentExtractionSandboxTest"),
          Map.entry("PersistentExtractionSandbox.restartCount", "PersistentExtractionSandboxTest"),
          Map.entry("PersistentExtractionSandbox.firstChildPid", "PersistentExtractionSandboxTest"),
          Map.entry("InferenceLifecycleManager.asIntOrNull", "InferenceLifecycleManagerUtilsTest"),
          Map.entry("InferenceLifecycleManager.asPositiveInt", "InferenceLifecycleManagerUtilsTest"),
          Map.entry("InferenceLifecycleManager.startLlamaServer", "ExternalServerTest"),
          Map.entry("InferenceLifecycleManager.handlePeriodicHealthFailure", "ExternalServerTest"),
          Map.entry("InferenceLifecycleManager.updateFromPropsBestEffort", "ServerPropsOpsTest"),
          Map.entry("InferenceLifecycleManager.extractContextTokensFromProps", "LlamaServerPropsParsingTest"),
          Map.entry("InferenceLifecycleManager.isUsingExternalServer", "ExternalServerTest"),
          Map.entry("SpladeEncoder.postProcess", "SpladePostProcessTest"),
          Map.entry("KnowledgeSearchEngine.isLambdaMartEligible", "KnowledgeHttpApiAdapterHarmfulCombinationsTest (556 renamed KnowledgeHttpApiAdapter→KnowledgeSearchEngine)"),
          Map.entry("ConfigStore.clearGlobal", "TestResolvedConfigHelper"),
          Map.entry("JvmRuntimeGauges.getJvmGaugeErrorCount", "JvmRuntimeGaugesTest"),
          Map.entry("AgentLoopService.forTesting", "AgentLoopServiceTest (test-only static factory)"),
          Map.entry("RootWatcherRegistry.watchedRoots", "RootWatcherRegistryTest (tempdoc 418 Phase A)"),
          Map.entry("ScanProgressRegistry.activeBufferCount", "ScanProgressRegistryTest (tempdoc 419 T4)"),
          Map.entry("ConsentCapsuleService.liveNonceCount", "ConsentCapsuleServiceTest (test-only nonce-eviction accessor)"),
          Map.entry("ConsentCapsuleService.liveGrantCount", "ConsentCapsuleServiceTest (test-only grant-eviction accessor, tempdoc 550)"),
          Map.entry("RemoteKnowledgeClient.isWalkExecutorTerminated", "RemoteKnowledgeClientCloseJoinsWalkTest (test-only accessor: close() joins the walk thread, tempdoc 932 item 7)"),
          Map.entry("ScanProgressRegistry.pruneNow", "ScanProgressRegistryTest (tempdoc 419 T4)"),
          // Reflection / test contract (method signature verified via getDeclaredMethod)
          // Tempdoc 516 Slice 4d (W6): IndexingLoop.handle*EmbeddingFailure removed —
          // wrappers had no production callers; production uses EmbeddingBackfillOps statics.
          Map.entry("HeadAssembly.chooseFirstNonBlank", "reflection shim for HeadAssemblyTest"),
          // worker-services module not on app-launcher classpath — callers invisible
          Map.entry("RagContextOps.executeRetrieval", "called from GrpcSearchService (worker-services)"),
          Map.entry("RagContextOps.searchChunksWithMeta", "called from executeRetrieval overload (same class)"),
          Map.entry("ChunkRerankResult.wasReranked", "called from RagContextOps (worker-services)"),
          // Tempdoc 517 — static delegates called from SearchOrchestratorPipelineDispatchTest
          // (worker-services classpath, invisible here).
          Map.entry("SearchOrchestrator.deriveActualMode", "SearchOrchestratorPipelineDispatchTest (worker-services)"),
          Map.entry("SearchOrchestrator.deriveEffectiveMode", "SearchOrchestratorPipelineDispatchTest (worker-services)"),
          Map.entry("SearchOrchestrator.modeToDefaultPipeline", "SearchOrchestratorPipelineDispatchTest (worker-services)"),
          // Tempdoc 516 — package-private test accessors. Used by worker-services tests
          // (IndexingLoopTest direct; AdversarialCorpusIngestionTest via reflection on
          // getWriter/getExtractor).
          Map.entry("AgentController.isHeartbeatSchedulerShutdown", "AgentControllerShutdownTest (638 PE)"),
          // 859 D live-defect D2 — ChatController acquired the same heartbeat scheduler, so it has
          // the same test accessor asserting the same start/stop symmetry.
          Map.entry("ChatController.isHeartbeatSchedulerShutdown", "ChatControllerHeartbeatTest (859 D2)"),
          Map.entry("IndexingLoop.getJournal", "IndexingLoopTest + AdversarialCorpusIngestionTest"),
          Map.entry("IndexingLoop.getEmbeddingLifecycle", "IndexingLoopTest (516 Slice 4c)"),
          Map.entry("IndexingLoop.getWriter", "IndexingLoopTest + AdversarialCorpusIngestionTest (516 W5.1)"),
          Map.entry("IndexingLoop.getExtractor", "IndexingLoopTest + AdversarialCorpusIngestionTest (516 W5.2)"),
          Map.entry("IndexingLoop.getBackfillScheduler", "test accessor for the scheduler (516 W6)"),
          Map.entry("OperationalMetrics.deregisterEncoder", "EncoderProfileAccumulatorTest"),
          Map.entry("NativeSessionHandle.peekCpuSession", "NativeSessionHandleTest (same-package)"),
          // Convenience overloads — internal self-calls only (flagged because ArchUnit's
          // unreferenced check wants an external caller). Pre-existing; kept as part of
          // the public overload surface for future callers.
          Map.entry("WritePathOps.readModifyWriteBatch", "2-arg overload delegates to 3-arg; both kept for API symmetry"),
          // Tempdoc 406 — phase types and RuntimeSession internals.
          Map.entry("DeferredRuntime.session", "LifecycleTestAccessor (test-only) unwraps via this accessor"),
          Map.entry("ReadOnlyRuntime.session", "LifecycleTestAccessor (test-only) unwraps via this accessor"),
          Map.entry("RunningRuntime.session", "LifecycleTestAccessor + VectorSearchIntegrationTest unwrap via this accessor"),
          // Pre-existing dead methods from other worktree merges — not introduced by slice 494.
          Map.entry("SseWriter.writeSseComment", "pre-existing; added by slice 491 Phase E worktree"),
          Map.entry("EngineConversationContext.mutableMessages", "pre-existing; added by slice 491 Phase E worktree"),
          // Test-only / generics-blind callers the owning agents merged without registering (591 red-gate triage).
          Map.entry("AgentSession.budgetGateHeld", "AgentSessionBudgetTest (577 R2 budget held-gate accessor)"),
          Map.entry("AgentSession.contextGateHeld", "AgentSessionBudgetTest (577 R3 context held-gate accessor)"),
          Map.entry("RouteContractPolicy.declaredSchemaFiles", "RouteContractPolicyCoverageTest (899 SDK schema-closure test)"),
          Map.entry("RouteContractPolicy.validateSdkRoutes", "SDK snapshot projection and policy tests (899 test-source callers)"),
          Map.entry("NdjsonAppendStore.storeFile", "called from GplJobCoordinator (app-services); ArchUnit resolves the call through the generic NdjsonAppendStore<T> to the erased type"),
          // Tempdoc 638 F6 — pre-existing red-gate violations inherited from main merges (607/626
          // visual-extraction + incremental-indexing). Classified by caller analysis; non-dead ones
          // get the standard invisible-caller exemption. Suspected-dead ones flagged for owner review
          // (docs/observations.d) rather than deleted from another agent's just-merged code.
          Map.entry("SearchTraceProjector.project", "called from SearchResponseBuilder (worker-services, invisible to app-launcher classpath)"),
          Map.entry("VisualExtractionEvidence.from", "called from PolicyDrivenTikaExtractor (worker-services, invisible to app-launcher classpath)"),
          Map.entry("EmbeddingProviderLifecycle.unloadEmbeddingService", "invoked via reflection by IndexingLoopUnloadTelemetryEmitTest (worker-services)"),
          Map.entry("SearchPipelinePresets.toProtoPipelineConfig", "1-arg convenience overload; production uses the 2-arg form via KnowledgeSearchEngine; 1-arg exercised by PipelineConfigPresetExpansionTest"),
          Map.entry("ExcludeMatcher.isExcluded", "test-only (ExcludeMatcherBarePatternTest); production exclusion uses ExcludeGlobs.isExcludedDirectory/isExcludedPath — suspected dead, flagged for owner (638 F6)"),
          Map.entry("SyncOps.getScheduler", "no caller found in any source — suspected dead, flagged for SyncOps owner (638 F6); not deleted (another agent's just-merged code)"),
          Map.entry("AgentController.shutdown", "heartbeat-scheduler stop method ('Call on shutdown'); lifecycle wiring not located — flagged for owner (638 F6), not deleted"));

  /**
   * Classes that ArchUnit's bytecode analysis cannot trace but are verified to have dependents.
   * Keyed by simple class name, value is the reason for exemption. Same conventions as {@link
   * #KNOWN_UNREFERENCED}.
   */
  private static final Map<String, String> KNOWN_UNREFERENCED_CLASSES = Map.of();

  // =========================================================================
  // Dead class detection
  // =========================================================================

  /**
   * Detect package-private classes that no other class depends on. A class is "referenced" if any
   * other class in the codebase has a dependency on it (field type, method parameter/return type,
   * constructor call, method call, annotation, superclass, interface, etc.).
   *
   * <p>Excludes:
   *
   * <ul>
   *   <li>Public and protected classes (may be API surface)
   *   <li>Inner/nested classes (lifecycle tied to enclosing class)
   *   <li>Enums (often used via reflection or switch patterns)
   *   <li>Annotations (used via reflection)
   *   <li>Classes listed in {@link #KNOWN_UNREFERENCED_CLASSES}
   *   <li>Entry points (main classes, gRPC service implementations)
   *   <li>Framework-registered classes (Jackson mixins, module initializers)
   * </ul>
   */
  @ArchTest
  void no_unreferenced_package_private_classes(JavaClasses importedClasses) {
    Set<String> referencedClassNames = collectReferencedClasses(importedClasses);

    classes()
        .that(arePackagePrivateClasses())
        .and(isNotInnerClass())
        .and(isNotEnumOrAnnotation())
        .and(isNotKnownUnreferencedClass())
        .and(isNotEntryPointOrFrameworkClass())
        .should(beReferencedByOtherClasses(referencedClassNames))
        .as("Package-private classes should be referenced by at least one other class (potential dead class)")
        .check(importedClasses);
  }

  // =========================================================================
  // Dead field detection
  // =========================================================================

  /**
   * Fields that ArchUnit's bytecode analysis cannot trace but are verified to have accessors.
   * Keyed by "SimpleClassName.fieldName", value is the reason for exemption.
   */
  private static final Map<String, String> KNOWN_UNREFERENCED_FIELDS = Map.ofEntries();

  /**
   * Detect package-private fields that are never read by any code outside their declaring class.
   * A field is "dead" if it is written (assigned) but never read — write-only fields serve no
   * purpose.
   *
   * <p>Excludes:
   *
   * <ul>
   *   <li>Private fields (already covered by PMD UnusedPrivateField)
   *   <li>Public and protected fields (may be API surface)
   *   <li>Synthetic fields (compiler-generated, e.g. $VALUES for enums)
   *   <li>Fields annotated with serialization annotations (Jackson, etc.)
   *   <li>Fields in {@link #KNOWN_UNREFERENCED_FIELDS}
   *   <li>Logger fields (SLF4J pattern)
   *   <li>Constant fields (static final — often used as sentinel values or config)
   * </ul>
   */
  @ArchTest
  void no_unreferenced_package_private_fields(JavaClasses importedClasses) {
    fields()
        .that(arePackagePrivateFields())
        .and(isNotSyntheticField())
        .and(isNotLoggerField())
        .and(isNotConstantField())
        .and(isNotSerializationField())
        .and(isNotKnownUnreferencedField())
        .should(beReadByAtLeastOneCodeUnit())
        .as("Package-private fields should be read by at least one code unit (potential dead field)")
        .check(importedClasses);
  }

  // =========================================================================
  // Unreferenced method detection
  // =========================================================================

  /**
   * Detect private and package-private methods that are never called by any other code.
   *
   * <p>Excludes:
   *
   * <ul>
   *   <li>Lambda synthetic methods (lambda$*)
   *   <li>Bridge methods (generated for generics)
   *   <li>Methods annotated with framework annotations (Jackson, etc.)
   *   <li>Methods with @Override in gRPC service implementations
   *   <li>Methods listed in {@link #KNOWN_UNREFERENCED}
   * </ul>
   */
  @ArchTest
  void no_unreferenced_non_public_methods(JavaClasses importedClasses) {
    Set<String> referencedMethods = collectReferencedMethods(importedClasses);

    methods()
        .that(arePrivateOrPackagePrivate())
        .and()
        .doNotHaveModifier(JavaModifier.SYNTHETIC)
        .and()
        .doNotHaveModifier(JavaModifier.BRIDGE)
        .and(isNotLambdaMethod())
        .and(isNotFrameworkCallback())
        .and(isNotKnownUnreferenced())
        .should(beReferencedByOtherCodeUnits(referencedMethods))
        .as("Private/package-private methods should be referenced by other code (potential dead code)")
        .check(importedClasses);
  }

  // =========================================================================
  // Reference collection
  // =========================================================================

  /** Collects all method signatures that are called or referenced from any code unit. */
  private static Set<String> collectReferencedMethods(JavaClasses allClasses) {
    Set<String> referencedMethods = new HashSet<>();

    for (JavaClass javaClass : allClasses) {
      // Collect method calls from all methods
      javaClass
          .getMethodCallsFromSelf()
          .forEach(call -> referencedMethods.add(call.getTarget().getFullName()));

      // Collect method references (for lambdas and method references)
      javaClass
          .getMethodReferencesFromSelf()
          .forEach(ref -> referencedMethods.add(ref.getTarget().getFullName()));

      // Collect method calls from constructors (Fix #5)
      javaClass
          .getConstructors()
          .forEach(
              ctor ->
                  ctor.getMethodCallsFromSelf()
                      .forEach(call -> referencedMethods.add(call.getTarget().getFullName())));
    }

    return referencedMethods;
  }

  // =========================================================================
  // Custom predicates for filtering
  // =========================================================================

  /** Matches private or package-private methods (Fix #2: extends beyond just private). */
  private static DescribedPredicate<JavaMethod> arePrivateOrPackagePrivate() {
    return new DescribedPredicate<>("are private or package-private") {
      @Override
      public boolean test(JavaMethod method) {
        return method.getModifiers().contains(JavaModifier.PRIVATE)
            || (!method.getModifiers().contains(JavaModifier.PUBLIC)
                && !method.getModifiers().contains(JavaModifier.PROTECTED)
                && !method.getModifiers().contains(JavaModifier.PRIVATE));
      }
    };
  }

  /** Excludes lambda synthetic methods (named lambda$methodName$0, etc.). */
  private static DescribedPredicate<JavaMethod> isNotLambdaMethod() {
    return new DescribedPredicate<>("is not a lambda method") {
      @Override
      public boolean test(JavaMethod method) {
        return !method.getName().startsWith("lambda$");
      }
    };
  }

  /**
   * Excludes methods that are framework callbacks or test infrastructure.
   *
   * <p>Excludes:
   *
   * <ul>
   *   <li>Jackson serialization annotations
   *   <li>gRPC service method overrides
   *   <li>Test infrastructure: *ForTest*, *ForTesting, install*, reset*
   * </ul>
   */
  private static DescribedPredicate<JavaMethod> isNotFrameworkCallback() {
    return new DescribedPredicate<>("is not a framework callback or test infrastructure") {
      @Override
      public boolean test(JavaMethod method) {
        String name = method.getName();

        // Test infrastructure hooks (called via reflection/DI in tests)
        if (name.contains("ForTest")
            || name.endsWith("ForTesting")
            || name.startsWith("install")
            || name.startsWith("reset")) {
          return false;
        }

        // Jackson serialization callbacks
        if (method.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonCreator")
            || method.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonSetter")
            || method.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonGetter")
            || method.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonProperty")
            || method.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonValue")
            || method.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonAnySetter")
            || method.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonAnyGetter")) {
          return false;
        }

        // gRPC service method overrides
        if (method.isAnnotatedWith(Override.class)) {
          JavaClass owner = method.getOwner();
          if (owner.getAllRawSuperclasses().stream()
              .anyMatch(
                  c -> c.getName().contains("Grpc$") && c.getName().contains("ImplBase"))) {
            return false;
          }
        }

        return true;
      }
    };
  }

  /**
   * Excludes methods listed in {@link #KNOWN_UNREFERENCED}. These are methods with verified callers
   * that ArchUnit's bytecode analysis cannot trace (test-only, reflection, inheritance).
   */
  private static DescribedPredicate<JavaMethod> isNotKnownUnreferenced() {
    return new DescribedPredicate<>("is not in the known-unreferenced exclusion list") {
      @Override
      public boolean test(JavaMethod method) {
        String key = method.getOwner().getSimpleName() + "." + method.getName();
        return !KNOWN_UNREFERENCED.containsKey(key);
      }
    };
  }

  // =========================================================================
  // Custom ArchCondition for reference checking
  // =========================================================================

  /** Checks that a method is referenced by at least one other code unit (method or constructor). */
  private static ArchCondition<JavaMethod> beReferencedByOtherCodeUnits(
      Set<String> referencedMethods) {
    return new ArchCondition<>("be referenced by other code units") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        String signature = method.getFullName();

        if (!referencedMethods.contains(signature)) {
          String message =
              String.format(
                  "Method %s in %s is never referenced",
                  method.getName(), method.getOwner().getSimpleName());
          events.add(SimpleConditionEvent.violated(method, message));
        }
      }
    };
  }

  // =========================================================================
  // Dead class detection — predicates and conditions
  // =========================================================================

  /**
   * Collects all class names that are depended upon by at least one OTHER class. Uses ArchUnit's
   * {@link JavaClass#getDirectDependenciesFromSelf()} which covers field types, method
   * parameters/return types, constructor calls, method calls, annotations, superclasses, and
   * interfaces.
   */
  private static Set<String> collectReferencedClasses(JavaClasses allClasses) {
    Set<String> referenced = new HashSet<>();
    for (JavaClass javaClass : allClasses) {
      for (Dependency dep : javaClass.getDirectDependenciesFromSelf()) {
        JavaClass target = dep.getTargetClass();
        // Only count references from OTHER classes (not self-references)
        if (!target.getName().equals(javaClass.getName())) {
          referenced.add(target.getName());
        }
      }
    }
    return referenced;
  }

  /** Matches package-private (default visibility) top-level classes. */
  private static DescribedPredicate<JavaClass> arePackagePrivateClasses() {
    return new DescribedPredicate<>("are package-private") {
      @Override
      public boolean test(JavaClass javaClass) {
        return !javaClass.getModifiers().contains(JavaModifier.PUBLIC)
            && !javaClass.getModifiers().contains(JavaModifier.PROTECTED)
            && !javaClass.getModifiers().contains(JavaModifier.PRIVATE);
      }
    };
  }

  /** Excludes inner/nested classes — their lifecycle is tied to the enclosing class. */
  private static DescribedPredicate<JavaClass> isNotInnerClass() {
    return new DescribedPredicate<>("is not an inner/nested class") {
      @Override
      public boolean test(JavaClass javaClass) {
        return !javaClass.getName().contains("$");
      }
    };
  }

  /** Excludes enums and annotation types — often used via reflection or switch patterns. */
  private static DescribedPredicate<JavaClass> isNotEnumOrAnnotation() {
    return new DescribedPredicate<>("is not an enum or annotation") {
      @Override
      public boolean test(JavaClass javaClass) {
        return !javaClass.isEnum() && !javaClass.isAnnotation();
      }
    };
  }

  /** Excludes classes listed in {@link #KNOWN_UNREFERENCED_CLASSES}. */
  private static DescribedPredicate<JavaClass> isNotKnownUnreferencedClass() {
    return new DescribedPredicate<>("is not in the known-unreferenced-classes exclusion list") {
      @Override
      public boolean test(JavaClass javaClass) {
        return !KNOWN_UNREFERENCED_CLASSES.containsKey(javaClass.getSimpleName());
      }
    };
  }

  /**
   * Excludes entry points and framework-registered classes that have no static dependents but are
   * invoked by the runtime.
   */
  private static DescribedPredicate<JavaClass> isNotEntryPointOrFrameworkClass() {
    return new DescribedPredicate<>("is not an entry point or framework-registered class") {
      @Override
      public boolean test(JavaClass javaClass) {
        String name = javaClass.getSimpleName();

        // gRPC service implementations (registered dynamically)
        if (javaClass.getAllRawSuperclasses().stream()
            .anyMatch(c -> c.getName().contains("Grpc$") && c.getName().contains("ImplBase"))) {
          return false;
        }

        // Main entry points
        if (name.endsWith("Main") || name.endsWith("App") || name.endsWith("Application")) {
          return false;
        }

        return true;
      }
    };
  }

  /** Checks that a class is depended upon by at least one other class in the codebase. */
  private static ArchCondition<JavaClass> beReferencedByOtherClasses(
      Set<String> referencedClassNames) {
    return new ArchCondition<>("be referenced by at least one other class") {
      @Override
      public void check(JavaClass javaClass, ConditionEvents events) {
        if (!referencedClassNames.contains(javaClass.getName())) {
          String message =
              String.format(
                  "Class %s in package %s is never referenced by any other class",
                  javaClass.getSimpleName(), javaClass.getPackageName());
          events.add(SimpleConditionEvent.violated(javaClass, message));
        }
      }
    };
  }

  // =========================================================================
  // Dead field detection — predicates and conditions
  // =========================================================================

  /** Matches package-private (default visibility) fields. */
  private static DescribedPredicate<JavaField> arePackagePrivateFields() {
    return new DescribedPredicate<>("are package-private") {
      @Override
      public boolean test(JavaField field) {
        return !field.getModifiers().contains(JavaModifier.PRIVATE)
            && !field.getModifiers().contains(JavaModifier.PUBLIC)
            && !field.getModifiers().contains(JavaModifier.PROTECTED);
      }
    };
  }

  /** Excludes compiler-generated synthetic fields (e.g. enum $VALUES). */
  private static DescribedPredicate<JavaField> isNotSyntheticField() {
    return new DescribedPredicate<>("is not synthetic") {
      @Override
      public boolean test(JavaField field) {
        return !field.getModifiers().contains(JavaModifier.SYNTHETIC)
            && !field.getName().startsWith("$");
      }
    };
  }

  /** Excludes SLF4J logger fields (conventional pattern: static final Logger log/LOG/logger). */
  private static DescribedPredicate<JavaField> isNotLoggerField() {
    return new DescribedPredicate<>("is not a logger field") {
      @Override
      public boolean test(JavaField field) {
        String name = field.getName().toLowerCase();
        return !(name.equals("log") || name.equals("logger"));
      }
    };
  }

  /** Excludes static final fields — constants, sentinel values, shared config. */
  private static DescribedPredicate<JavaField> isNotConstantField() {
    return new DescribedPredicate<>("is not a constant (static final)") {
      @Override
      public boolean test(JavaField field) {
        return !(field.getModifiers().contains(JavaModifier.STATIC)
            && field.getModifiers().contains(JavaModifier.FINAL));
      }
    };
  }

  /** Excludes fields with Jackson or serialization annotations. */
  private static DescribedPredicate<JavaField> isNotSerializationField() {
    return new DescribedPredicate<>("is not annotated with serialization annotations") {
      @Override
      public boolean test(JavaField field) {
        return !field.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonProperty")
            && !field.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonAnySetter")
            && !field.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonAnyGetter");
      }
    };
  }

  /** Excludes fields listed in {@link #KNOWN_UNREFERENCED_FIELDS}. */
  private static DescribedPredicate<JavaField> isNotKnownUnreferencedField() {
    return new DescribedPredicate<>("is not in the known-unreferenced-fields exclusion list") {
      @Override
      public boolean test(JavaField field) {
        String key = field.getOwner().getSimpleName() + "." + field.getName();
        return !KNOWN_UNREFERENCED_FIELDS.containsKey(key);
      }
    };
  }

  /**
   * Checks that a field has at least one read access (GET) from any code unit. Write-only fields
   * are dead — they are assigned but their value is never consumed.
   */
  private static ArchCondition<JavaField> beReadByAtLeastOneCodeUnit() {
    return new ArchCondition<>("be read by at least one code unit") {
      @Override
      public void check(JavaField field, ConditionEvents events) {
        boolean hasRead =
            field.getAccessesToSelf().stream()
                .anyMatch(access -> access.getAccessType() == JavaFieldAccess.AccessType.GET);
        if (!hasRead) {
          String message =
              String.format(
                  "Field %s in %s is never read (write-only)",
                  field.getName(), field.getOwner().getSimpleName());
          events.add(SimpleConditionEvent.violated(field, message));
        }
      }
    };
  }
}
