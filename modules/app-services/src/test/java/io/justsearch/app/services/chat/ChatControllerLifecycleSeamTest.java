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
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec A step 2 at the real seam: the Engine's lifecycle manager, built by its composition root,
 * with a chat server stand-in that takes a while to start. Every start, stop and relaunch goes
 * through the chat controller, so relaunch requests that pile up during a relaunch coalesce (U2),
 * quit during a start leaves nothing running (U3, S16), and no lifecycle lock is held while the
 * controller waits for health or drains (U4).
 */
final class ChatControllerLifecycleSeamTest {

  private static final long START_DELAY_MS = 1_500;

  @TempDir Path directory;

  private ManagedChatSeam seam;
  private InferenceLifecycleManager manager;

  @BeforeEach
  void setUp() throws Exception {
    seam = new ManagedChatSeam(directory, START_DELAY_MS);
    manager = seam.manager();
  }

  @AfterEach
  void tearDown() throws Exception {
    seam.close();
  }

  @Test
  void relaunchRequestsThatArriveDuringARelaunchLaunchOnlyTheFinalTarget() throws Exception {
    manager.switchToOnlineMode();
    assertEquals(List.of("model.gguf"), seam.models(), "the first start launches once");

    // X: a relaunch whose server takes a while to become healthy.
    CompletableFuture<Throwable> x = apply("x.gguf");
    await(() -> seam.models().size() == 2, "X to launch");

    // A, B and C arrive one after another while X is still starting.
    List<CompletableFuture<Throwable>> waiting = new ArrayList<>();
    for (String target : List.of("a.gguf", "b.gguf", "c.gguf")) {
      waiting.add(applyAndAwaitBlocked(target));
    }

    assertEquals(null, x.get(60, TimeUnit.SECONDS), "X completes");
    assertEquals(null, waiting.get(2).get(60, TimeUnit.SECONDS), "C completes");
    assertEquals(List.of("model.gguf", "x.gguf", "c.gguf"), seam.models(),
        "after X only the final target C launches, once; A and B never launch");
    assertEquals("c.gguf", manager.currentConfig().modelPath().getFileName().toString());
    assertTrue(manager.isOnline());
    for (int i = 0; i < 2; i++) {
      var superseded = assertInstanceOfMode(waiting.get(i).get(60, TimeUnit.SECONDS));
      assertEquals(ModeTransitionException.Reason.ALREADY_TRANSITIONING, superseded.reason(),
          "a superseded request is told so");
    }
  }

  @Test
  void quitWhileTheServerIsStartingLeavesNothingRunningAndRefusesLaterStarts() throws Exception {
    CompletableFuture<Throwable> start = background(() -> manager.switchToOnlineMode());
    await(() -> seam.models().size() == 1, "the start to launch");

    seam.quit();
    manager = null;

    assertNotNull(start.get(30, TimeUnit.SECONDS), "a start cut short by quit does not succeed");
    await(seam::noneAlive, "every launched server to end");
    Thread.sleep(START_DELAY_MS);
    assertEquals(1, seam.models().size(), "nothing relaunches after quit");
    assertTrue(seam.noneAlive());
  }

  /**
   * U4 (Spec A §3.5 item 5): while the controller waits for a launched server's health, and while
   * it drains an in-flight reply before a relaunch, another thread can take the lifecycle manager's
   * transition lock at once.
   */
  @Test
  void noLifecycleLockIsHeldWhileTheControllerWaitsForHealthOrDrains() throws Exception {
    seam.startDelay(4_000); // a wide health-wait window
    Object lifecycleLock = lifecycleLock(manager);

    // A start's health wait.
    CompletableFuture<Throwable> start = background(() -> manager.switchToOnlineMode());
    await(() -> seam.models().size() == 1, "the start to launch");
    assertLockFree(lifecycleLock, "while a start waits for health");
    assertFalse(start.isDone(), "the probe ran during the health wait");
    assertNull(start.get(60, TimeUnit.SECONDS), "the start completes");

    // A relaunch's drain of an in-flight reply, then the relaunched server's health wait.
    Object gate = field(manager, "requestGate");
    AutoCloseable reply = (AutoCloseable) invoke(gate, "acquire");
    InferenceConfig candidate = seam.withModel("b.gguf");
    var resolved = seam.resolvedWithModel("b.gguf");
    var prepared = new CompletableFuture<InferenceLifecycleManager.PreparedConfigApply>();
    Thread.ofPlatform().daemon().start(() -> {
      try {
        prepared.complete(manager.prepareResolvedConfig(candidate, resolved, true));
      } catch (Throwable failure) {
        prepared.completeExceptionally(failure);
      }
    });
    await(() -> draining(gate), "the relaunch to drain");
    assertLockFree(lifecycleLock, "while a relaunch drains an in-flight reply");
    assertEquals(1, seam.models().size(), "the probe ran during the drain");
    reply.close();

    await(() -> seam.models().size() == 2, "the candidate to launch");
    assertLockFree(lifecycleLock, "while a relaunched server is waiting for health");
    assertFalse(prepared.isDone(), "the probe ran during the candidate's health wait");
    prepared.get(60, TimeUnit.SECONDS).abort();
  }

  // ==================== helpers ====================

  private interface Call {
    void run() throws Exception;
  }

  private static CompletableFuture<Throwable> background(Call call) {
    var result = new CompletableFuture<Throwable>();
    Thread.ofPlatform().daemon().start(() -> {
      try {
        call.run();
        result.complete(null);
      } catch (Throwable failure) {
        result.complete(failure);
      }
    });
    return result;
  }

  private static void assertLockFree(Object lock, String during) throws Exception {
    var acquired = new CountDownLatch(1);
    Thread.ofPlatform().daemon().start(() -> {
      synchronized (lock) {
        acquired.countDown();
      }
    });
    assertTrue(acquired.await(1, TimeUnit.SECONDS),
        "another thread takes the lifecycle lock within 1 s " + during);
  }

  private static Object lifecycleLock(InferenceLifecycleManager manager) throws Exception {
    return invoke(field(manager, "runner"), "lock");
  }

  private static boolean draining(Object gate) {
    try {
      Field reloading = gate.getClass().getDeclaredField("reloading");
      reloading.setAccessible(true);
      synchronized (gate) {
        return reloading.getBoolean(gate);
      }
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static Object field(Object owner, String name) throws ReflectiveOperationException {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static Object invoke(Object owner, String name) throws ReflectiveOperationException {
    Method method = owner.getClass().getDeclaredMethod(name);
    method.setAccessible(true);
    return method.invoke(owner);
  }

  private CompletableFuture<Throwable> apply(String model) throws Exception {
    InferenceConfig target = seam.withModel(model);
    return background(() -> manager.applyConfig(
        target, InferenceLifecycleManager.RestartPolicy.RESTART_IF_ONLINE));
  }

  private CompletableFuture<Throwable> applyAndAwaitBlocked(String model) throws Exception {
    InferenceConfig target = seam.withModel(model);
    var result = new CompletableFuture<Throwable>();
    Thread caller = Thread.ofPlatform().daemon().start(() -> {
      try {
        manager.applyConfig(target, InferenceLifecycleManager.RestartPolicy.RESTART_IF_ONLINE);
        result.complete(null);
      } catch (Throwable failure) {
        result.complete(failure);
      }
    });
    await(() -> {
      Thread.State state = caller.getState();
      return state == Thread.State.WAITING || state == Thread.State.BLOCKED
          || state == Thread.State.TIMED_WAITING || result.isDone();
    }, "the request for " + model + " to wait");
    return result;
  }

  private static ModeTransitionException assertInstanceOfMode(Throwable failure) {
    assertNotNull(failure, "the superseded request fails");
    return assertThrows(ModeTransitionException.class, () -> {
      throw failure;
    });
  }
}
