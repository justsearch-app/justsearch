/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.deadcode;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.time.Duration;
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
 * <p>Inherited setup preemptively bounds both the import and waiting for its lock to 120 seconds.
 * Setup overrides the repository's 30-second default, which still bounds each test body.
 */
abstract class ImportedProgram {

  private static JavaClasses cached;

  @BeforeAll
  @Timeout(120)
  static void importProgram() {
    assertTimeoutPreemptively(Duration.ofMinutes(2), ImportedProgram::classes);
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
