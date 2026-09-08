/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.engine.EngineExit.ExitClass;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stage B item B1 — every deliberate exit is classified, and an unknown one fails open to retry.
 *
 * <p>The classification is what a supervisor budgets on, so the two directions that matter are:
 * a code the Engine really produces must not be missing from the table, and an unrecognised code
 * must not stop the product permanently.
 */
@DisplayName("EngineExit — exit-code classification (stage B item B1)")
final class EngineExitTest {

  @Test
  @DisplayName("each named code classifies as design 7.1 says")
  void namedCodesClassify() {
    assertEquals(ExitClass.REQUESTED, EngineExit.classify(EngineExit.OK));
    assertEquals(
        ExitClass.NON_TRANSIENT,
        EngineExit.classify(EngineExit.DATA_DIR_LOCKED),
        "another live instance holds the lock, so the next attempt reads the same lock — retrying"
            + " is three cooldowns spent reaching the same answer");
    assertEquals(
        ExitClass.TRANSIENT,
        EngineExit.classify(EngineExit.OUT_OF_MEMORY),
        "design 7.1 names the out-of-memory exit transient");
    assertEquals(
        ExitClass.TRANSIENT,
        EngineExit.classify(EngineExit.FATAL_OR_UNCAUGHT),
        "exit 1 is produced BOTH by the uncaught-exception handler (a crash, at any time) and by"
            + " the catch around the run (usually a boot failure). Nothing in the integer separates"
            + " them, so it classifies as the one whose misjudgement is cheaper.");
  }

  @Test
  @DisplayName("an unrecognised code is TRANSIENT — fail open to retry, never to exhausted")
  void unknownCodesFailOpenToRetry() {
    // A Windows access violation, as a supervisor observes it.
    assertEquals(ExitClass.TRANSIENT, EngineExit.classify(-1073741819));
    assertEquals(ExitClass.TRANSIENT, EngineExit.classify(137), "SIGKILL as 128+9");
    assertEquals(ExitClass.TRANSIENT, EngineExit.classify(42));
    assertEquals(ExitClass.TRANSIENT, EngineExit.classify(Integer.MIN_VALUE));
    assertEquals(ExitClass.TRANSIENT, EngineExit.classify(Integer.MAX_VALUE));
  }

  @Test
  @DisplayName("an unknown code describes itself as unknown, carrying the number")
  void unknownCodeDescribesItself() {
    assertEquals("unknown(-1073741819)", EngineExit.describe(-1073741819));
    assertEquals("out_of_memory", EngineExit.describe(EngineExit.OUT_OF_MEMORY));
    assertEquals("data_dir_locked", EngineExit.describe(EngineExit.DATA_DIR_LOCKED));
  }

  /** {@code HeadlessApp.java}, read as text from this module's project directory. */
  private static final Path HEADLESS_APP =
      Path.of("..", "ui", "src", "main", "java", "io", "justsearch", "ui", "HeadlessApp.java");

  /** A {@code System.exit(...)} argument, whatever it is. */
  private static final Pattern EXIT_ARG =
      Pattern.compile("System\\.exit\\(([^)]*)\\)", Pattern.MULTILINE);

  @Test
  @DisplayName("every System.exit on the boot path goes through this table, not a bare integer")
  void everyExitSiteUsesTheTable() throws Exception {
    // A table nothing calls is parallel documentation: it drifts from the code the moment someone
    // adds `System.exit(4)`, and the supervisor then budgets an exit the table never heard of as
    // TRANSIENT forever. So the pin is on the CALL SITES, not on the table's own contents.
    assertTrue(
        Files.isRegularFile(HEADLESS_APP),
        "HeadlessApp.java not found at " + HEADLESS_APP.toAbsolutePath()
            + " — if it moved, this pin must follow it rather than silently checking nothing");
    String src = Files.readString(HEADLESS_APP);

    Set<String> args = new LinkedHashSet<>();
    Matcher m = EXIT_ARG.matcher(src);
    while (m.find()) {
      args.add(m.group(1).trim());
    }

    assertTrue(args.size() >= 2, "the scan found " + args + " — it is matching nothing useful");
    for (String arg : args) {
      assertTrue(
          arg.contains("EngineExit."),
          "System.exit(" + arg + ") in HeadlessApp does not name an EngineExit constant. Add the"
              + " code to EngineExit with its class before using it: a supervisor reads the integer"
              + " and has no other way to learn what it meant.");
    }
  }
}
