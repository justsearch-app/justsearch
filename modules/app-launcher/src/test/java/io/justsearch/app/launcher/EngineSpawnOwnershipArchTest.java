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
      "io.justsearch.app.api.runtime.child.DefaultChildControl",
      "io.justsearch.app.api.runtime.child.LinuxProcessIdentity");

  private static final String BOUND = "io.justsearch.app.api.runtime.child.BoundChildProcess";
  private static final Set<String> BOUND_CALLERS = Set.of(
      BOUND,
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

  @Test void plantedUnownedBoundProcessIsRejected() {
    assertFalse(violations(new ClassFileImporter().importClasses(PlantedBoundSpawn.class)).isEmpty(),
        "the platform seam must not let an unowned caller bypass the owner check");
  }

  private static List<String> violations(JavaClasses classes) {
    List<String> violations = new ArrayList<>();
    int[] spawns = {0};
    classes.forEach(type -> type.getMethodCallsFromSelf().forEach(call -> {
      boolean spawn = call.getTargetOwner().getFullName().equals("java.lang.ProcessBuilder")
          && Set.of("start", "startPipeline").contains(call.getName());
      boolean exec = call.getTargetOwner().getFullName().equals("java.lang.Runtime")
          && call.getName().equals("exec");
      boolean bound = call.getTargetOwner().getFullName().equals(BOUND)
          && call.getName().equals("start");
      if (spawn || bound) spawns[0]++;
      if (exec || (spawn && !OWNERS.contains(type.getName()) && !BOUND.equals(type.getName()))
          || (bound && !BOUND_CALLERS.contains(type.getName()))) violations.add(call.getDescription());
    }));
    assertFalse(spawns[0] == 0, "production classes must actually be inspected");
    return violations;
  }
  private static class PlantedBoundSpawn {
    static Process unowned() throws java.io.IOException {
      return io.justsearch.app.api.runtime.child.BoundChildProcess.start(
          new ProcessBuilder("unowned-canary")); // never executed
    }
  }
  private static class PlantedSpawn {
    static Process unowned() throws java.io.IOException {
      return new ProcessBuilder("unowned-canary").start(); // never executed
    }
  }
}
