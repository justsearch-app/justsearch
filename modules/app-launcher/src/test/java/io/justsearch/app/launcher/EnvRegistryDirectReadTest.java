package io.justsearch.app.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.justsearch.configuration.EnvRegistry;
import java.util.Set;

/**
 * ArchUnit rule: production code outside {@code io.justsearch.configuration} must not call
 * value-reading methods on {@link EnvRegistry} ({@code get()}, {@code getString()}, etc.).
 *
 * <p>Direct reads bypass the ordinal chain (YAML, settings.json, snapshot). Use
 * {@code ConfigStore.global().get()...} instead.
 *
 * <p>Exempted classes: bootstrap (pre-ConfigStore), worker process
 * (worker-core WorkerConfig), and diagnostics (EffectiveConfigController).
 *
 * <p>Added by tempdoc 347 D5, replacing the string-based {@code CheckEnvRegistryDirectReadsTask}.
 */
@AnalyzeClasses(packages = "io.justsearch", importOptions = ImportOption.DoNotIncludeTests.class)
class EnvRegistryDirectReadTest {

  /** Classes exempt from the direct-read rule. */
  private static final Set<String> EXEMPT_SIMPLE_NAMES = Set.of(
      // Bootstrap (pre-ConfigStore):
      "Launcher",
      "LauncherBootstrap",
      "LauncherEnvironment",
      "JustSearchConfigurationLoader",
      "PlatformPaths",
      "RepoRootLocator",
      // Early init (before full startup):
      "UiSettingsStore",
      // Forwarding (reads to forward to child process):
      // Brain process (no ConfigStore):
      "WorkerConfig",          // worker-core (no ConfigStore)
      // Diagnostic display only:
      "EffectiveConfigController",
      // Static field initializer (cannot safely access ConfigStore):
      "IndexingDocumentOps",
      // ORT JNI native path (loaded before ConfigStore):
      "OrtCudaHelper",
      // Model discovery fallback:
      "EmbeddingOnnxModelDiscovery"
  );

  private static final Set<String> DIRECT_READ_METHODS = Set.of(
      "get", "getString", "getInt", "getBoolean", "getLong", "getDouble", "getPath"
  );

  private static final ArchCondition<JavaClass> callEnvRegistryDirectReadMethod =
      new ArchCondition<>("call EnvRegistry value-reading methods") {
        @Override
        public void check(JavaClass item, ConditionEvents events) {
          for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
            if (call.getTargetOwner().isEquivalentTo(EnvRegistry.class)
                && DIRECT_READ_METHODS.contains(call.getName())) {
              events.add(SimpleConditionEvent.violated(item,
                  item.getSimpleName() + " calls EnvRegistry." + call.getName()
                      + "() at " + call.getSourceCodeLocation()));
            }
          }
        }
      };

  @ArchTest
  static final ArchRule noDirectEnvRegistryReadsOutsideConfiguration =
      noClasses()
          .that()
          .resideOutsideOfPackages("io.justsearch.configuration..")
          .and(DescribedPredicate.describe(
              "are not exempt",
              (JavaClass c) -> !EXEMPT_SIMPLE_NAMES.contains(c.getSimpleName())))
          .should(callEnvRegistryDirectReadMethod)
          .as("Production code should read config from ConfigStore, not EnvRegistry directly. "
              + "Add the class to EXEMPT_SIMPLE_NAMES if this is bootstrap/forwarding code.");
}
