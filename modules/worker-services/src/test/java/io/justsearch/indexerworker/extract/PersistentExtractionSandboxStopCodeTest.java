/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static io.justsearch.indexerworker.extract.PersistentExtractionSandboxTest.javaCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.ProcessTerminator;
import io.justsearch.app.api.runtime.child.TerminateCodes;
import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import io.justsearch.indexerworker.extract.PersistentExtractionSandboxTest.IgnoringShutdownChild;
import io.justsearch.indexerworker.extract.PersistentExtractionSandboxTest.ScriptedChild;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Every child the extraction pool ends is ended by the child control's terminate, so its real OS
 * exit code is the control's own code: {@code JS_CHILD_STOP} (0x4A530001) on Windows, which no
 * child exits with by itself. The pool's first child is observed by handing it a process started
 * here.
 */
final class PersistentExtractionSandboxStopCodeTest {

  @TempDir Path tempDir;

  private Path file(String name) throws Exception {
    Path path = tempDir.resolve(name);
    Files.writeString(path, "sandbox child content");
    return path;
  }

  private static PersistentExtractionSandbox pool(Class<?> child, Duration timeout, int maxRequests) {
    return new PersistentExtractionSandbox(TestWorkerExecutorRegistrations.readers(),
        javaCommand(child), TikaExtractionPolicy.defaults(), OcrRoutingConfig.disabled(), timeout,
        1, maxRequests, null, ManagedChildRegistry.noop());
  }

  private static int expectedCode() {
    return File.separatorChar == '\\'
        ? TerminateCodes.JS_CHILD_STOP
        : ProcessTerminator.forCurrentOs().ourCode();
  }

  /** Runs {@code action} with the pool's next spawn being {@code observed}. */
  private static void spawning(Process observed, ThrowingRunnable action) throws Exception {
    try (var _ = Mockito.mockConstruction(ProcessBuilder.class,
        (builder, context) -> Mockito.when(builder.start()).thenReturn(observed))) {
      action.run();
    }
  }

  private static void assertEndedWithOurCode(Process observed) throws Exception {
    assertTrue(observed.waitFor(15, TimeUnit.SECONDS), "the pool must end the child");
    assertEquals(expectedCode(), observed.exitValue(), "the child ends with the control's code");
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  @Test
  @Timeout(60)
  void timeoutKillEndsTheChildWithTheChildStopCode() throws Exception {
    Process observed = new ProcessBuilder(javaCommand(ScriptedChild.class)).start();
    try (var sandbox = pool(ScriptedChild.class, Duration.ofSeconds(3), 500)) {
      spawning(observed, () -> assertThrows(
          TimeboxedContentExtractor.ExtractionTimeoutException.class,
          () -> sandbox.extract(file("hang.txt"))));
      assertEndedWithOurCode(observed);
    } finally {
      observed.destroyForcibly();
    }
  }

  @Test
  @Timeout(60)
  void protocolRecycleEndsTheChildWithTheChildStopCode() throws Exception {
    Process observed = new ProcessBuilder(javaCommand(ScriptedChild.class)).start();
    try (var sandbox = pool(ScriptedChild.class, Duration.ofSeconds(20), 500)) {
      spawning(observed, () -> assertThrows(SandboxExtractionException.class,
          () -> sandbox.extract(file("malformed.txt"))));
      assertEndedWithOurCode(observed);
    } finally {
      observed.destroyForcibly();
    }
  }

  @Test
  @Timeout(60)
  void requestBudgetRecycleEndsTheChildWithTheChildStopCode() throws Exception {
    Process observed = new ProcessBuilder(javaCommand(ScriptedChild.class)).start();
    try (var sandbox = pool(ScriptedChild.class, Duration.ofSeconds(20), 1)) {
      spawning(observed, () -> sandbox.extract(file("ok-1.txt")));
      assertEquals("ok-2.txt", sandbox.extract(file("ok-2.txt")).result().content());
      assertEndedWithOurCode(observed);
    } finally {
      observed.destroyForcibly();
    }
  }

  @Test
  @Timeout(60)
  void closeForcingAChildThatIgnoresEofEndsItWithTheChildStopCode() throws Exception {
    Process observed = new ProcessBuilder(javaCommand(IgnoringShutdownChild.class)).start();
    try (var sandbox = pool(IgnoringShutdownChild.class, Duration.ofSeconds(20), 500)) {
      spawning(observed, () -> sandbox.extract(file("ignores-close.txt")));
      sandbox.close();
      assertEndedWithOurCode(observed);
    } finally {
      observed.destroyForcibly();
    }
  }
}
