/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static io.justsearch.app.services.chat.ManagedChatSeam.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.app.api.runtime.child.TerminateCodes;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.app.inference.chat.SelfTestSpec;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec B steps 5 and 6 at the real seam: the Engine stops its llama-server children through
 * ChildControl, so each planned stop ends the launched process with {@code JS_CHILD_STOP}
 * ({@code 0x4A530001}), the code the exit ledger and the dev census classify as REQUESTED. A plain
 * destroy ends it with 1, which nothing can tell from a crash. Covered: the main server on
 * deactivate and on quit, and the activation self-test's candidate.
 */
final class LlamaStopCodeSeamTest {

  private static final Duration EXIT_BOUND = Duration.ofSeconds(15);

  @TempDir Path directory;

  private ManagedChatSeam seam;
  private InferenceLifecycleManager manager;

  @BeforeEach
  void setUp() throws Exception {
    assumeTrue(ExitCodeWatch.supported(), "the launched process's exit code is read on Windows");
    seam = new ManagedChatSeam(directory, 200);
    manager = seam.manager();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (seam != null) seam.close();
  }

  @Test
  void deactivatingChatEndsTheMainServerWithTheChildStopCode() throws Throwable {
    manager.switchToOnlineMode();
    try (ExitCodeWatch launched = ExitCodeWatch.open(launcherOfLatest())) {
      long begin = System.nanoTime();
      manager.switchToIndexingMode();
      OptionalInt code = launched.awaitExitCode(EXIT_BOUND);
      assertEquals(OptionalInt.of(TerminateCodes.JS_CHILD_STOP), code,
          "a planned stop ends llama-server with JS_CHILD_STOP, not a plain destroy's 1");
      assertTrue(Duration.ofNanos(System.nanoTime() - begin).compareTo(EXIT_BOUND) < 0);
    }
  }

  @Test
  void quitEndsTheMainServerWithTheChildStopCode() throws Throwable {
    manager.switchToOnlineMode();
    try (ExitCodeWatch launched = ExitCodeWatch.open(launcherOfLatest())) {
      seam.quit();
      manager = null;
      assertEquals(OptionalInt.of(TerminateCodes.JS_CHILD_STOP),
          launched.awaitExitCode(EXIT_BOUND), "quit ends llama-server with JS_CHILD_STOP");
    }
  }

  @Test
  void theSelfTestCandidateIsEndedWithTheChildStopCode() throws Throwable {
    AtomicReference<ExitCodeWatch> candidate = new AtomicReference<>();
    try (var session = manager.chatHolds().beginSelfTest("runtime-variant-activation")) {
      int port = freePort();
      Path model = seam.withModel("candidate.gguf").modelPath();
      Path workingDirectory = Files.createDirectories(directory.resolve("selftest"));
      SelfTestRun run = session.run(new SelfTestSpec(
          List.of(seam.base().serverExecutable().toString(),
              "-m", model.toString(), "--port", Integer.toString(port)),
          workingDirectory, List.of(), workingDirectory.resolve("selftest.log"), port,
          Duration.ofSeconds(30), new HttpProbe(() -> {
            try {
              candidate.set(ExitCodeWatch.open(launcherOfLatest()));
            } catch (Throwable failure) {
              throw new IllegalStateException(failure);
            }
          })));
      assertNull(run.failure());
      assertTrue(run.exercised());
      assertNotNull(candidate.get(), "the probe saw the candidate");
      try (ExitCodeWatch launched = candidate.get()) {
        // Returned only after the candidate exited: the code is already there.
        assertEquals(OptionalInt.of(TerminateCodes.JS_CHILD_STOP),
            launched.awaitExitCode(Duration.ZERO),
            "the self-test candidate is ended with JS_CHILD_STOP before run returns");
      }
    }
  }

  /** The process the Engine launched for the latest server: the fake's launcher script. */
  private long launcherOfLatest() throws Exception {
    await(() -> !seam.models().isEmpty(), "a launch to be recorded");
    List<SlowStartFakeLlamaServer.Launch> launches =
        SlowStartFakeLlamaServer.launches(seam.fakeHome());
    long server = launches.getLast().pid();
    return ProcessHandle.of(server).flatMap(ProcessHandle::parent)
        .orElseThrow(() -> new AssertionError("fake llama-server " + server + " has no launcher"))
        .pid();
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  /** Answers health over HTTP; {@code onExercise} runs while the candidate is healthy. */
  private record HttpProbe(Runnable onExercise) implements SelfTestSpec.Probe {
    @Override
    public boolean healthy(int port) {
      try {
        var connection = (HttpURLConnection)
            URI.create("http://127.0.0.1:" + port + "/health").toURL().openConnection();
        connection.setConnectTimeout(500);
        connection.setReadTimeout(2_000);
        try {
          return connection.getResponseCode() == 200;
        } finally {
          connection.disconnect();
        }
      } catch (IOException notYet) {
        return false;
      }
    }

    @Override
    public void exercise(int port) {
      onExercise.run();
    }
  }
}
