package io.justsearch.app.services.worker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.justsearch.app.services", importOptions = ImportOption.DoNotIncludeTests.class)
class AppServicesWorkerGuardrailsTest {
  // The former `appServicesMustNotReadEnvOrSystemProperties` rule and its 21-entry inline
  // allowlist were retired in tempdoc 883 decision 5. It was the largest of six per-module copies
  // of the same rule, and the copies together still left telemetry, app-inference, gpu-bridge,
  // ai-backend, app-launcher, ort-common, worker-services, benchmarks and ssot-tools uncovered.
  // The single repo-wide replacement is io.justsearch.deadcode.SystemAccessFunnelTest
  // (modules/dead-code-audit); the exemptions this rule named are now entries in
  // gates/config-surface/sysaccess-allowlist.txt, a ratchet that only shrinks.

  // Lane F stage A item A10 deleted MainSignalBus, the memory-mapped bus that was this rule's one
  // exemption. The rule is kept and STRENGTHENED rather than deleted with it: its subject is not
  // that class but the invariant that app-services does no memory-mapped IO, and with the Head and
  // the index half in one JVM there is no longer any reason for an exemption to come back. An
  // exemption list that outlives its entry is a re-entry licence; removing the entry is what closes
  // it.
  @ArchTest
  static final ArchRule appServicesMustNotDoMemoryMappedIo =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.app.services..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("java.nio.MappedByteBuffer");

  // Tempdoc 541 fix-pass B.3: the §31 Rule 2 service-construction-site rule (formerly
  // serviceImplsConstructedOnlyInBootstrapPhases) was lifted to
  // io.justsearch.app.services.bootstrap.CompositionRootGuardrailsTest where it consolidates
  // with the other §4.3 composition-root rules. Predicate moved verbatim; allowlist preserved.
}
