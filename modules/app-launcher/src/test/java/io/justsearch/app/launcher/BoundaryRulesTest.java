package io.justsearch.app.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.justsearch")
class BoundaryRulesTest {
  @ArchTest
  static final ArchRule launcherMustNotDependOnUiOrEngineImplementation =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.applauncher..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              // The CLI composes HeadAssembly through app-services; UI and Engine concrete types
              // remain forbidden. The Engine registry implementation is runtime-only via SPI.
              "io.justsearch.ui..",
              // Lane F stage A item A2: the Engine composition root joins the list for the same
              // reason. app-launcher takes :modules:app-engine as `runtimeOnly`, which is a
              // classpath edge, not a bytecode dependency, so this stays green — the entry exists
              // so a future compile-scope edge from the launcher into the root fails here.
              "io.justsearch.app.engine.."
          );
}
