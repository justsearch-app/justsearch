/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.DefaultChildControl;
import io.justsearch.app.inference.chat.SelfTestRun;
import io.justsearch.app.inference.chat.SelfTestSpec;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/*
 * Design D4, repair round 2, on real processes: whether the answer model's files may still be in
 * use is read from the processes this Engine started, never from the answer server's mode. A stop
 * that failed leaves the mode offline while the server lives, and a self-test candidate that
 * outlived its stop outlives the controller's hold; both keep the files in use until the process
 * is gone. The terminator kills nothing while it is told to survive, so each stop really fails.
 */
final class AnswerFilesInUseTest {
  private static final String SERVER_LOADED = "The answer model is still loaded - try again in a moment";
  private static final String BEING_TESTED = "The answer model is being tested - try again when it finishes";

  @TempDir Path tmp;
  private final SurvivingTerminator terminator = new SurvivingTerminator();
  private Path fakeDir;
  private InferenceLifecycleManager manager;
  private DefaultChildControl control;

  @AfterEach
  void tearDown() throws Exception {
    terminator.killAll();
    try {
      if (manager != null) manager.close();
    } finally {
      try {
        if (fakeDir != null) HealthyFakeLlamaServer.endAll(fakeDir);
      } finally {
        if (control != null) control.close();
      }
    }
  }

  @Test
  void aFailedStopKeepsTheAnswerModelInUseWhileTheModeSaysOffline() throws Exception {
    fakeDir = tmp.resolve("fake-llama");
    manager = manager(HealthyFakeLlamaServer.install(fakeDir));
    manager.switchToOnlineMode();
    assertTrue(manager.isOnline(), "precondition: the managed answer server runs");
    assertEquals(Optional.of(SERVER_LOADED), manager.answerFilesInUse());

    terminator.survive = true;
    try {
      manager.switchToIndexingMode(); // stops the managed server
    } catch (Exception stopFailed) {
      // The failed stop is the case under test; its own report is the lifecycle's business.
    }

    assertFalse(terminator.alive().isEmpty(), "precondition: the stop failed and the server lives");
    assertFalse(manager.chatActuator().serverRunning(), "precondition: the mode says not running");
    assertEquals(Optional.of(SERVER_LOADED), manager.answerFilesInUse(),
        "a live server keeps the answer model in use, whatever the mode says");

    terminator.killAll();
    HealthyFakeLlamaServer.endAll(fakeDir);
    assertEquals(Optional.empty(), eventually(manager::answerFilesInUse),
        "once the process is gone the files are released");
  }

  @Test
  void aSelfTestCandidateThatOutlivedItsStopKeepsTheAnswerModelInUse() throws Exception {
    manager = manager(tmp.resolve("unused-llama-server"));
    assertEquals(Optional.empty(), manager.answerFilesInUse(), "precondition: nothing runs");
    terminator.survive = true;

    SelfTestRun run = manager.selfTestLauncher().run(new SelfTestSpec(sleepingChild(), null,
        List.of(), tmp.resolve("self-test.log"), freePort(), Duration.ofSeconds(10),
        new SelfTestSpec.Probe() {
          @Override
          public boolean healthy(int port) {
            return true;
          }

          @Override
          public void exercise(int port) {}
        }));

    assertFalse(run.exercised(), "precondition: the candidate survived its stop: " + run.failure());
    assertFalse(terminator.alive().isEmpty(), "precondition: the candidate lives");
    assertFalse(manager.chatActuator().serverRunning(), "precondition: no answer server is running");
    assertEquals(Optional.of(BEING_TESTED), manager.answerFilesInUse(),
        "a surviving candidate keeps the answer model in use after its test returned");

    terminator.killAll();
    assertEquals(Optional.empty(), eventually(manager::answerFilesInUse),
        "once the candidate is gone the files are released");
  }

  private static Optional<String> eventually(Supplier<Optional<String>> read) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    Optional<String> last = read.get();
    while (last.isPresent() && System.nanoTime() < deadline) {
      Thread.sleep(50);
      last = read.get();
    }
    return last;
  }

  private InferenceLifecycleManager manager(Path executable) throws Exception {
    control = DefaultChildControl.of((type, fields) -> true, terminator, ManagedChildRegistry.noop());
    Path model = tmp.resolve("model.gguf");
    if (!Files.exists(model)) Files.createFile(model);
    ResolvedConfig captured = TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.context.size", "4096",
        "justsearch.data.dir", tmp.resolve("data").toString()));
    InferenceConfig configuration =
        new InferenceConfig(executable, model, null, freePort(), 4096, 0, false);
    return new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(),
        configuration, NoopInferenceTelemetryEvents.INSTANCE, control, captured, ignored -> () -> {});
  }

  /** A real, long-lived stand-in candidate: ManagedLlamaAdoptionTest's sleeping child. */
  private static List<String> sleepingChild() throws Exception {
    String java = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    String classes = Path.of(ManagedLlamaAdoptionTest.SleepingChild.class.getProtectionDomain()
        .getCodeSource().getLocation().toURI()).toString();
    return List.of(java, "-cp", classes, ManagedLlamaAdoptionTest.SleepingChild.class.getName());
  }

  private static int freePort() throws java.io.IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }
}
