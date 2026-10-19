/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.app.services.bootstrap.phases.BootstrapProjections;
import io.justsearch.app.services.chat.ManagedChatSeam;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Reuses the established real controller/coordinator fixture without changing its old oracles. */
@Timeout(150)
final class AutomaticVduRetryBudgetTest {
  @TempDir Path directory;
  private final AutomaticVduRecoveryTest fixture = new AutomaticVduRecoveryTest();
  private InferenceLifecycleManager manager;
  private ManagedChatSeam seam;

  @BeforeEach void setUp() throws Exception {
    var field = AutomaticVduRecoveryTest.class.getDeclaredField("directory");
    field.setAccessible(true); field.set(fixture, directory);
    fixture.setUp();
    manager = (InferenceLifecycleManager) field("manager");
    seam = (ManagedChatSeam) field("seam");
  }

  @AfterEach void tearDown() throws Exception { fixture.tearDown(); }

  @Test void automaticRestorationStopsAfterFiveFailuresAndReportsStoppedRetries() throws Exception {
    exhaustRetries();
    var status = BootstrapProjections.projectInferenceSnapshot(manager, null);
    assertFalse(manager.isOnline());
    assertEquals("config_apply_failed", status.lastFailure().code());
    assertTrue(status.lastFailure().detail().toLowerCase(java.util.Locale.ROOT).contains("retries stopped"),
        "status must distinguish exhausted automatic retries from a scheduled retry");
  }

  enum Resume { HUMAN_CHAT, RECORDED_SETTINGS, PREPARED_SETTINGS }

  @Test void humanRequestStartsAFreshFiveAttemptBudget() throws Exception {
    assertFreshBudget(Resume.HUMAN_CHAT);
  }

  @Test void recordedSettingsStartAFreshFiveAttemptBudget() throws Exception {
    assertFreshBudget(Resume.RECORDED_SETTINGS);
  }

  @Test void preparedSettingsStartAFreshFiveAttemptBudget() throws Exception {
    assertFreshBudget(Resume.PREPARED_SETTINGS);
  }

  private void assertFreshBudget(Resume resume) throws Exception {
    exhaustRetries();
    int launches = seam.models().size();
    if (resume == Resume.HUMAN_CHAT) {
      assertThrows(java.util.concurrent.ExecutionException.class,
          () -> demand().toCompletableFuture().get(10, TimeUnit.SECONDS));
    } else if (resume == Resume.RECORDED_SETTINGS) {
      manager.applyConfig(seam.withModel("new-budget.gguf"), InferenceLifecycleManager.RestartPolicy.APPLY_ONLY);
    } else {
      publishRecordOnly("new-budget.gguf");
    }
    awaitAttempt(launches + 1);
    for (int attempt = 2; attempt <= 5; attempt++) {
      advance(31);
      awaitAttempt(launches + attempt);
    }
    advance(600);
    assertFalse(await(() -> restorationAttemptRunning() || seam.models().size() > launches + 5, 2),
        "the refreshed retry budget must not schedule a sixth failed attempt");
    assertEquals(launches + 5, seam.models().size(), "the explicit action grants exactly five new failed attempts");
    Files.delete(seam.fakeHome().resolve("refuse-normal"));
    demand().toCompletableFuture().get(10, TimeUnit.SECONDS);
    assertTrue(manager.isOnline());
    assertFalse(manager.currentConfig().vduMode());
    if (resume != Resume.HUMAN_CHAT) {
      assertTrue(Files.readString(seam.fakeHome().resolve("last-args.txt")).contains("new-budget.gguf"));
    }
  }

  @Test void turningChatOffAndOnMakesTheNextAttemptAfterRetriesStop() throws Exception {
    exhaustRetries();
    int launches = seam.models().size();
    manager.setActivation(new ChatActuationRouter.Activation(false, TransitionReason.USER_SWITCH))
        .toCompletableFuture().get(10, TimeUnit.SECONDS);
    Files.delete(seam.fakeHome().resolve("refuse-normal"));
    manager.setActivation(new ChatActuationRouter.Activation(true, TransitionReason.USER_SWITCH))
        .toCompletableFuture().get(10, TimeUnit.SECONDS);
    assertTrue(manager.isOnline());
    assertEquals(launches + 1, seam.models().size());
    var failure = BootstrapProjections.projectInferenceSnapshot(manager, null).lastFailure();
    assertTrue(failure == null || !failure.detail().toLowerCase(java.util.Locale.ROOT).contains("retries stopped"));
  }

  @Test void explicitPendingPassEntersAfterTwoHealthyStartsWithoutAnotherEvent() throws Exception {
    invoke("pendingRestore");
    Files.delete(seam.fakeHome().resolve("refuse-normal"));
    io.justsearch.app.services.chat.SlowStartFakeLlamaServer.install(seam.fakeHome(), 2_000);
    Files.writeString(seam.fakeHome().resolve("pause-completion"), "pause");
    int launches = seam.models().size();
    var coordinator = (OfflineCoordinator) field("coordinator");
    long start = System.nanoTime();
    coordinator.startOfflineProcessing(io.justsearch.app.services.TestEngineContexts.ui(), outcome -> {});
    assertTrue(await(() -> Files.exists(seam.fakeHome().resolve("requests.txt")), 12),
        "the accepted explicit pass must enter without a timer tick, manual activation or new activity");
    assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(12), "healthy fake entry bound is twelve seconds");
    assertEquals(launches + 2, seam.models().size(), "offline entry starts normal chat, then the vision configuration");
    assertTrue(manager.currentConfig().vduMode());
    Files.delete(seam.fakeHome().resolve("pause-completion"));
    invoke("awaitPass");
    assertTrue(manager.isOnline());
    assertFalse(manager.currentConfig().vduMode());
  }

  @Test void recordOnlyPreparedSettingsRetainTheNewestRestorationTarget() throws Exception {
    invoke("pendingRestore");
    var next = seam.withModel("prepared-budget.gguf");
    publishRecordOnly("prepared-budget.gguf");
    assertEquals(next, manager.currentConfig());
    Files.delete(seam.fakeHome().resolve("refuse-normal"));
    demand().toCompletableFuture().get(10, TimeUnit.SECONDS);
    assertTrue(manager.isOnline(), "a person's demand must start the newly published normal configuration");
    assertEquals(next, manager.currentConfig());
    assertTrue(Files.readString(seam.fakeHome().resolve("last-args.txt")).contains("prepared-budget.gguf"));
  }

  private void publishRecordOnly(String model) throws Exception {
    var next = seam.withModel(model);
    var resolvedMethod = ManagedChatSeam.class.getDeclaredMethod("resolvedWithModel", String.class);
    resolvedMethod.setAccessible(true);
    var resolved = (io.justsearch.configuration.resolved.ResolvedConfig)
        resolvedMethod.invoke(seam, model);
    var prepared = manager.prepareResolvedConfig(next, resolved, true, true);
    assertFalse(prepared.targetsOnline(), "an offline refresh prepares settings without a private model start");
    prepared.withLifecycleLock(prepared::installAfterSettingsCommit);
    prepared.notifyAfterSettingsCommit();
    prepared.retireAfterSettingsCommit();
  }

  private void exhaustRetries() throws Exception {
    invoke("pendingRestore");
    int launches = seam.models().size();
    // The first failed return is attempt one; four subsequent automatic launches exhaust five.
    for (int attempt = 1; attempt <= 4; attempt++) {
      advance(31);
      awaitAttempt(launches + attempt);
    }
    advance(600);
    assertFalse(await(() -> restorationAttemptRunning() || seam.models().size() > launches + 4, 2),
        "automatic retries stop after five consecutive failures");
    assertEquals(launches + 4, seam.models().size(), "automatic retries stop after five consecutive failures");
  }

  private void awaitAttempt(int launches) throws Exception {
    assertTrue(await(() -> seam.models().size() >= launches, 10), "the permitted restoration attempt must start");
    assertTrue(await(() -> !restorationAttemptRunning(), 10),
        "the attempt must finish before controller time advances");
  }

  private boolean restorationAttemptRunning() {
    try {
      Object controller = manager.chatResidency();
      var monitor = controller.getClass().getDeclaredField("monitor");
      var attempt = controller.getClass().getDeclaredField("vduRestoreAttempt");
      monitor.setAccessible(true); attempt.setAccessible(true);
      synchronized (monitor.get(controller)) { return attempt.get(controller) != null; }
    } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
  }

  private java.util.concurrent.CompletionStage<Void> demand() throws Exception {
    return manager.chatResidency().demand(new io.justsearch.app.api.chat.Demand(
        io.justsearch.app.api.chat.DemandKind.HUMAN_CHAT,
        io.justsearch.app.services.TestEngineContexts.ui(), Duration.ofSeconds(30)));
  }

  private void advance(long seconds) throws Exception {
    var method = AutomaticVduRecoveryTest.class.getDeclaredMethod("advanceControllerSeconds", long.class);
    method.setAccessible(true); method.invoke(fixture, seconds);
  }

  private Object field(String name) throws Exception {
    var field = AutomaticVduRecoveryTest.class.getDeclaredField(name);
    field.setAccessible(true); return field.get(fixture);
  }

  private void invoke(String name) throws Exception {
    var method = AutomaticVduRecoveryTest.class.getDeclaredMethod(name);
    method.setAccessible(true);
    try { method.invoke(fixture); }
    catch (java.lang.reflect.InvocationTargetException failure) {
      if (failure.getCause() instanceof Exception cause) throw cause;
      throw failure;
    }
  }

  private static boolean await(java.util.function.BooleanSupplier condition, int seconds) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(20);
    return condition.getAsBoolean();
  }
}
