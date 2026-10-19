/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.RuntimeManifest.IncarnationInfo;
import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.ShutdownRequest;
import io.justsearch.app.engine.ShutdownRequestWatcher.Acceptance;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Spec F §4.3 (RV2 F14): boot looks for a quit before the request watcher starts, and honours only a
 * request that is this incarnation's. A file a predecessor left behind is deleted, never honoured.
 *
 * <p>The checkpoint is reached reflectively so this class also compiles against older revisions;
 * on b1cfcac8e, late nonce-less requests fail the refusal assertion.
 */
@DisplayName("HeadlessApp boot quit checkpoints")
final class HeadlessAppBootQuitTest {

  private static final IncarnationInfo SELF = new IncarnationInfo("run-q", 2);

  private final AtomicInteger exitCode = new AtomicInteger(-1);
  private final AtomicInteger closed = new AtomicInteger();
  private final AtomicInteger built = new AtomicInteger();

  @Test
  void aRequestLeftByAnEarlierIncarnationIsDeletedAndNotHonoured(@TempDir Path dataDir)
      throws Exception {
    Path runtime = runtime(dataDir);
    for (boolean first : new boolean[] {true, false}) {
      write(runtime, "quit", future(), "\"incarnation\":\"run-q/1\",\"requestNonce\":\"n-1\"");
      assertFalse(checkpoint(dataDir, SELF, first), "a predecessor's quit must not stop this Engine");
      assertFalse(Files.exists(ShutdownRequest.pathIn(runtime)), "the stale request is deleted");
    }
    assertEquals(0, built.get(), "nothing was closed for a stale request");
    assertEquals(-1, exitCode.get());
  }

  @Test
  void aRequestNamingThisIncarnationWithoutANonceIsStale(@TempDir Path dataDir) throws Exception {
    Path runtime = runtime(dataDir);
    write(runtime, "quit", future(), "\"incarnation\":\"run-q/2\"");
    assertFalse(checkpoint(dataDir, SELF, false));
    assertFalse(Files.exists(ShutdownRequest.pathIn(runtime)));
    assertEquals(0, built.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"after-lock", "before-index", "child-capable"})
  void thisIncarnationsQuitIsHonouredAtBootAndExitsZero(String checkpointName, @TempDir Path dataDir) throws Exception {
    Path runtime = runtime(dataDir);
    write(runtime, "quit", future(), "\"incarnation\":\"run-q/2\",\"requestNonce\":\"n-2\"");
    assertTrue(checkpoint(dataDir, SELF, checkpointName.equals("after-lock")),
        "this Engine's quit is honoured at " + checkpointName);
    assertFalse(Files.exists(ShutdownRequest.pathIn(runtime)), "the honoured request is consumed");
    assertEquals(1, closed.get(), "what boot had opened is closed in order");
    assertEquals(0, exitCode.get(), "a clean boot quit exits 0");
  }

  @ParameterizedTest
  @ValueSource(strings = {"after-lock", "before-index", "child-capable"})
  void aNonceLessRequestIsDeletedAtEveryCheckpoint(String checkpointName, @TempDir Path dataDir)
      throws Exception {
    Path runtime = runtime(dataDir);
    write(runtime, "quit", future(), null);
    assertFalse(checkpoint(dataDir, SELF, checkpointName.equals("after-lock")),
        "a nonce-less request must not stop discovery at " + checkpointName);
    assertFalse(Files.exists(ShutdownRequest.pathIn(runtime)));
    assertEquals(-1, exitCode.get());
  }

  @Test
  void anExpiredQuitIsClearedAtEveryCheckpoint(@TempDir Path dataDir) throws Exception {
    Path runtime = runtime(dataDir);
    for (boolean first : new boolean[] {true, false}) {
      write(runtime, "quit", 1L, "\"incarnation\":\"run-q/2\",\"requestNonce\":\"n-2\"");
      assertFalse(checkpoint(dataDir, SELF, first));
      assertFalse(Files.exists(ShutdownRequest.pathIn(runtime)));
    }
    assertEquals(0, built.get());
  }

  @Test
  void aRestartForThisIncarnationIsLeftForTheWatcher(@TempDir Path dataDir) throws Exception {
    Path runtime = runtime(dataDir);
    write(runtime, "restart", future(), "\"incarnation\":\"run-q/2\",\"requestNonce\":\"n-2\"");
    assertFalse(checkpoint(dataDir, SELF, false));
    assertTrue(Files.exists(ShutdownRequest.pathIn(runtime)), "the watcher owns restart requests");
    assertEquals(0, built.get());
  }

  @Test
  void theWatcherRefusesARequestAddressedToAnotherIncarnation(@TempDir Path dataDir)
      throws Exception {
    Path runtime = runtime(dataDir);
    write(runtime, "quit", future(), "\"incarnation\":\"run-q/1\",\"requestNonce\":\"n-1\"");
    ShutdownRequest stale = ShutdownRequest.read(runtime).orElseThrow();
    write(runtime, "quit", future(), null);
    ShutdownRequest legacy = ShutdownRequest.read(runtime).orElseThrow();

    // The production acceptance: this test JVM runs with no JUSTSEARCH_ENGINE_INCARNATION, so
    // no addressed request is its own; an unaddressed writer is also refused.
    assertEquals(Acceptance.REFUSE, HeadlessApp.shutdownRequestAcceptance().apply(stale),
        "a request for another incarnation must not stop this one");
    assertEquals(Acceptance.REFUSE, HeadlessApp.shutdownRequestAcceptance().apply(legacy));

    Method forIncarnation = assertDoesNotThrow(() -> HeadlessApp.class.getDeclaredMethod(
        "shutdownRequestAcceptance", IncarnationInfo.class), "no incarnation-aware acceptance");
    forIncarnation.setAccessible(true);
    @SuppressWarnings("unchecked")
    var asSelf = (Function<ShutdownRequest, Acceptance>) forIncarnation.invoke(null, SELF);
    @SuppressWarnings("unchecked")
    var asPredecessor = (Function<ShutdownRequest, Acceptance>) forIncarnation.invoke(null,
        new IncarnationInfo("run-q", 1));
    assertEquals(Acceptance.REFUSE, asSelf.apply(stale));
    assertEquals(Acceptance.ACCEPT, asPredecessor.apply(stale));
    assertEquals(Acceptance.REFUSE, asSelf.apply(legacy));
  }

  private boolean checkpoint(Path dataDir, IncarnationInfo current, boolean first)
      throws Exception {
    Method checkpoint = assertDoesNotThrow(() -> HeadlessApp.class.getDeclaredMethod(
            "bootShutdownCheckpoint", Path.class, IncarnationInfo.class, boolean.class,
            HeadlessApp.RequestFileDeleter.class, Supplier.class),
        "boot has no shutdown checkpoint: a quit before the watcher starts is never seen");
    checkpoint.setAccessible(true);
    HeadlessApp.RequestFileDeleter deleter = Files::deleteIfExists;
    Supplier<EngineShutdownSequence> sequence = () -> {
      built.incrementAndGet();
      return new EngineShutdownSequence(dataDir, List.of(new EngineShutdownSequence.Step(
          "boot-resources", reason -> {
            closed.incrementAndGet();
            return null;
          })), exitCode::set);
    };
    try {
      return (boolean) checkpoint.invoke(null, runtime(dataDir), current, first, deleter, sequence);
    } catch (InvocationTargetException failed) {
      if (failed.getCause() instanceof Exception cause) throw cause;
      throw failed;
    }
  }

  private static Path runtime(Path dataDir) throws java.io.IOException {
    return Files.createDirectories(dataDir.resolve("runtime"));
  }

  private static long future() {
    return System.currentTimeMillis() + 60_000L;
  }

  private static void write(Path runtime, String reason, long deadline, String extra)
      throws java.io.IOException {
    Files.writeString(ShutdownRequest.pathIn(runtime), "{\"schemaVersion\":1,\"reason\":\"" + reason
        + "\",\"deadlineEpochMs\":" + deadline + ",\"issuedBy\":\"dev-runner\""
        + (extra == null ? "" : "," + extra) + "}");
  }
}
