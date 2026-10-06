/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static io.justsearch.app.services.chat.ManagedChatSeam.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.app.inference.chat.SelfTestSpec;
import io.justsearch.app.services.runtimestate.RuntimeGpuLease;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec A step 5 at the real seam (U11, I6): the activation self-test is a client of the chat
 * controller. The controller stops the main server before the candidate launches, launches the
 * candidate itself, and the main server loads once, with the committed candidate, after the
 * session closes. Two servers are never alive together.
 */
final class SelfTestSessionSeamTest {

  private static final long START_DELAY_MS = 300;

  @TempDir Path directory;

  private ManagedChatSeam seam;
  private InferenceLifecycleManager manager;
  private RuntimeIntentTestFixture intent;
  private RuntimeReconciler reconciler;
  private AutoCloseable following;

  private void start(boolean chatEnabled) throws Exception {
    seam = new ManagedChatSeam(directory, START_DELAY_MS);
    manager = seam.manager();
    intent = new RuntimeIntentTestFixture(directory.resolve("intent"), chatEnabled);
    reconciler = new RuntimeReconciler(
        manager,
        manager::getCurrentMode,
        () -> manager.view().usingExternalLlamaServer(),
        null,
        intent.spec(),
        new RuntimeGpuLease(),
        manager::setActivation,
        manager::componentRecoveryPending);
    assertNotNull(manager.chatHolds(), "the Engine's manager is driven by the chat controller");
    following = reconciler.followHolds(manager.chatHolds());
    reconciler.start();
    reconciler.requestBootConvergence();
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (following != null) following.close();
      if (reconciler != null) reconciler.close();
      if (intent != null) intent.close();
    } finally {
      if (seam != null) seam.close();
    }
  }

  @Test
  void theMainServerStopsBeforeTheCandidateAndLoadsOnceAfterTheCommit() throws Exception {
    start(true);
    await(manager::isOnline, "boot intent to bring chat online");
    assertTrue(reconciler.awaitQuiescent(30_000));
    assertEquals(List.of("model.gguf"), seam.models());

    ChatHolds holds = manager.chatHolds();
    try (var alive = new AliveSampler(directory.resolve("fake-llama"))) {
      try (var session = holds.beginSelfTest("runtime-variant-activation")) {
        assertTrue(seam.noneAlive(), "U11: the main server is stopped before the session begins");
        assertFalse(manager.isOnline());
        assertFalse(holds.vduMayStart(), "U13: a server stopped for a self-test starts no VDU pass");
        assertThrows(ModeTransitionException.class, manager::switchToOnlineMode,
            "nothing starts the main server while the session is open");
        reconciler.specChanged();
        assertTrue(reconciler.awaitQuiescent(30_000));

        int port = freePort();
        Path candidate = seam.withModel("candidate.gguf").modelPath();
        SelfTestRun run = session.run(new SelfTestSpec(
            List.of(seam.base().serverExecutable().toString(),
                "-m", candidate.toString(), "--port", Integer.toString(port)),
            directory, List.of(), directory.resolve("selftest").resolve("selftest.log"), port,
            Duration.ofSeconds(30), new HttpProbe()));
        assertNull(run.failure());
        assertTrue(run.exercised(), "the controller launched and probed the candidate");
        assertTrue(seam.noneAlive(), "the candidate has exited when run returns");
        assertEquals(List.of("model.gguf", "candidate.gguf"), seam.models(),
            "the main server did not start during the session");

        // The activation commits the candidate inside the session: recorded, not launched.
        manager.applyConfig(seam.withModel("candidate.gguf"),
            InferenceLifecycleManager.RestartPolicy.RESTART_IF_ONLINE);
        assertEquals("candidate.gguf", manager.currentConfig().modelPath().getFileName().toString());
        assertEquals(2, seam.models().size(), "the commit launches nothing while the session is open");
      }
      // As RuntimeActivationService does after its session: the commit's spec nudge.
      reconciler.specChanged();
      await(manager::isOnline, "the intent to load chat again after the session");
      assertTrue(reconciler.awaitQuiescent(30_000));
      Thread.sleep(4 * START_DELAY_MS); // room for a second load to record itself, were one made
      assertEquals(List.of("model.gguf", "candidate.gguf", "candidate.gguf"), seam.models(),
          "U11: exactly one main load after the commit, with the candidate");
      assertTrue(alive.samples() > 0);
      assertTrue(alive.max() <= 1, "I6: never two servers alive, saw " + alive.worst());
    }
  }

  @Test
  void fromIdleTheSessionGoesStraightInAndClosingReturnsToAnOffIntent() throws Exception {
    start(false);
    assertTrue(reconciler.awaitQuiescent(30_000));
    assertEquals(List.of(), seam.models());

    long begin = System.nanoTime();
    try (var session = manager.chatHolds().beginSelfTest("runtime-variant-activation")) {
      assertTrue(System.nanoTime() - begin < Duration.ofSeconds(10).toNanos(),
          "F17: an idle server is not drained for");
      assertTrue(seam.noneAlive());
    }
    assertTrue(reconciler.awaitQuiescent(30_000));
    Thread.sleep(2 * START_DELAY_MS); // room for a load to launch, were one requested
    assertEquals(List.of(), seam.models(), "chat stays off: the session replans to the intent");
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  /** The caller's probe: the candidate answers {@code /health} and {@code /props}. */
  private static final class HttpProbe implements SelfTestSpec.Probe {
    @Override
    public boolean healthy(int port) {
      try {
        return status(port, "/health") == 200;
      } catch (IOException notYet) {
        return false;
      }
    }

    @Override
    public void exercise(int port) throws Exception {
      int status = status(port, "/props");
      if (status != 200) throw new IllegalStateException("/props answered " + status);
    }

    private static int status(int port, String path) throws IOException {
      var connection = (HttpURLConnection)
          URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
      connection.setConnectTimeout(500);
      connection.setReadTimeout(2_000);
      try {
        return connection.getResponseCode();
      } finally {
        connection.disconnect();
      }
    }
  }

  /**
   * Samples how many launched servers are alive at once (I6). Each server's handle is kept from
   * the first sample that sees it, so a pid Windows hands to another process after the server
   * exited is not counted: a kept handle checks the process start time, a fresh lookup does not.
   */
  private static final class AliveSampler implements AutoCloseable {
    private final AtomicBoolean stop = new AtomicBoolean();
    private final AtomicInteger max = new AtomicInteger();
    private final AtomicInteger samples = new AtomicInteger();
    private final java.util.Map<Long, java.util.Optional<ProcessHandle>> handles =
        new java.util.concurrent.ConcurrentHashMap<>();
    private final Thread thread;
    private volatile List<String> worst = List.of();

    AliveSampler(Path fakeHome) {
      thread = Thread.ofPlatform().daemon().start(() -> {
        while (!stop.get()) {
          try {
            List<String> alive = SlowStartFakeLlamaServer.launches(fakeHome).stream()
                .filter(launch -> handles.computeIfAbsent(launch.pid(), ProcessHandle::of)
                    .map(ProcessHandle::isAlive).orElse(false))
                .map(launch -> launch.model() + "#" + launch.pid())
                .toList();
            if (alive.size() > max.get()) worst = alive;
            max.accumulateAndGet(alive.size(), Math::max);
            samples.incrementAndGet();
          } catch (IOException | RuntimeException partialLine) {
            // a launch being recorded; the next sample reads it whole
          }
          try {
            Thread.sleep(5);
          } catch (InterruptedException interrupted) {
            return;
          }
        }
      });
    }

    int max() {
      return max.get();
    }

    int samples() {
      return samples.get();
    }

    List<String> worst() {
      return worst;
    }

    @Override
    public void close() throws InterruptedException {
      stop.set(true);
      thread.join(5_000);
    }
  }
}
