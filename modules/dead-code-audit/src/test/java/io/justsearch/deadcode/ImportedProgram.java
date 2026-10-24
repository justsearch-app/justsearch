/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.deadcode;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;

/**
 * The whole-program import, shared by every analysis in this module.
 *
 * <p>Importing every {@code io.justsearch} production class across all audited modules holds
 * ~1,300 classes plus their members in memory and is the slowest thing this module does. It is
 * also identical for every analysis here, so it is done once per JVM rather than once per test
 * class — a second importer would double both the wall time and the heap of a task that already
 * asks for 2g, and whole-classpath scanners in this repo are the tests that go red with
 * {@code TimeoutException} under concurrent-agent CPU load (`.claude/rules/agent-lessons.md`).
 *
 * <p>Analyses extend this class so the shared import runs in bounded setup, outside the 30-second
 * per-test default in {@code JvmBaseConventionsPlugin}. The import has its own 120-second budget:
 * it took about 4 seconds idle and 41 seconds with three CPU burners per available CPU. Test bodies
 * retain the default timeout; neither import nor analysis gets an unbounded wait.
 */
abstract class ImportedProgram {

  private static JavaClasses cached;

  @BeforeAll
  @Timeout(120)
  static void importProgram() {
    classes();
  }

  static synchronized JavaClasses classes() {
    if (cached == null) {
      cached =
          new ClassFileImporter()
              .withImportOption(new ImportOption.DoNotIncludeTests())
              .importPackages("io.justsearch");
    }
    return cached;
  }
}
