/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Every Engine-side production spawn reaches a held owner path, including future sites. */
class EngineSpawnOwnershipArchTest {
  private static final Set<String> OWNERS = Set.of(
      "io.justsearch.app.api.runtime.child.OwnedHelper",
      "io.justsearch.app.api.runtime.child.DefaultChildControl");

  @Test void everyProductionSpawnUsesAnOwner() {
    var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("io.justsearch");
    assertEquals(List.of(), violations(classes), "every ProcessBuilder/Runtime.exec must use its owner");
  }

  @Test void plantedUnownedProcessBuilderIsRejected() {
    assertFalse(violations(new ClassFileImporter().importClasses(PlantedSpawn.class)).isEmpty(),
        "an unowned ProcessBuilder planted in a production class must fail the check");
  }

  private static List<String> violations(JavaClasses classes) {
    List<String> violations = new ArrayList<>();
    int[] spawns = {0};
    classes.forEach(type -> type.getMethodCallsFromSelf().forEach(call -> {
      boolean spawn = call.getTargetOwner().getFullName().equals("java.lang.ProcessBuilder")
          && Set.of("start", "startPipeline").contains(call.getName());
      boolean exec = call.getTargetOwner().getFullName().equals("java.lang.Runtime")
          && call.getName().equals("exec");
      if (spawn) spawns[0]++;
      if (exec || (spawn && !OWNERS.contains(type.getName()))) violations.add(call.getDescription());
    }));
    assertFalse(spawns[0] == 0, "production classes must actually be inspected");
    return violations;
  }
  private static class PlantedSpawn {
    static Process unowned() throws java.io.IOException {
      return new ProcessBuilder("unowned-canary").start(); // never executed
    }
  }
}
