/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.justsearch.app.api.operations.OperationStore;
import org.junit.jupiter.api.Test;

/** C2's database has one implementation; producers depend on its app-api port. */
@AnalyzeClasses(packages = "io.justsearch", importOptions = {
    ImportOption.DoNotIncludeTests.class, ExecutorArchitectureTest.NoTestFixtures.class})
class OperationStoreArchitectureTest {
  @ArchTest
  static final ArchRule IMPLEMENTATION_OWNER = classes().that().implement(OperationStore.class)
      .should().resideInAPackage("io.justsearch.app.observability.operations");

  @ArchTest
  static final ArchRule PRODUCER_BOUNDARY = noClasses().that()
      .resideOutsideOfPackages("io.justsearch.app.observability.operations..",
          "io.justsearch.ui", "io.justsearch.applauncher")
      .should().dependOnClassesThat()
      .haveFullyQualifiedName("io.justsearch.app.observability.operations.SqliteOperationStore");

  @Test
  void ownerRuleRejectsAnAlternateProducerImplementation() {
    var imported = new ClassFileImporter().importClasses(UnauthorizedStore.class, OperationStore.class);
    assertTrue(IMPLEMENTATION_OWNER.evaluate(imported).hasViolation());
  }

  static final class UnauthorizedStore implements OperationStore {
    @Override public java.util.Optional<Recovery> recovery() { return java.util.Optional.empty(); }
    @Override public void close() {}
  }
}
