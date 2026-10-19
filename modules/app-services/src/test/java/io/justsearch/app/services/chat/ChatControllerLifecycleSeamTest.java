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
import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildSpec;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
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

  @TempDir Path directory;

  private ManagedChatSeam seam;
  private InferenceLifecycleManager manager;

  @BeforeEach
  void setUp() throws Exception {
    seam = new ManagedChatSeam(directory, 0);
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
    seam.holdHealth();
    CompletableFuture<Throwable> x = apply("x.gguf");
    seam.awaitHealth(2);
    assertFalse(x.isDone(), "X is held in its health wait");

    // A, B and C arrive one after another while X is still starting.
    List<CompletableFuture<Throwable>> waiting = new ArrayList<>();
    for (String target : List.of("a.gguf", "b.gguf", "c.gguf")) {
      waiting.add(applyAndAwaitBlocked(target));
    }
    seam.releaseHealth();

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
    seam.holdHealth();
    CompletableFuture<Throwable> start = background(() -> manager.switchToOnlineMode());
    seam.awaitHealth(1);
    assertFalse(start.isDone(), "quit is requested during the health wait");

    InferenceLifecycleManager stopped = manager;
    ChildControl launcher = (ChildControl) field(stopped, "childControl");
    ChildSpec restart = (ChildSpec) field(field(field(stopped, "serverOps"), "child"), "spec");
    seam.quit();
    manager = null;

    // The default setup gives the manager ownership of this launcher, so quit must close it.
    try {
      assertTrue((boolean) field(launcher, "closed"), "quit permanently closes its owned launcher");
      var refused = assertThrows(IllegalStateException.class, () -> launcher.start(restart));
      assertEquals("child control is closed", refused.getMessage());
      assertTrue(launcher.list().isEmpty(), "a refused restart must not launch a child");
    } finally {
      launcher.close(); // also release a child retained by a broken refusal
    }
    assertNotNull(start.get(30, TimeUnit.SECONDS), "a start cut short by quit does not succeed");
    await(seam::noneAlive, "every launched server to end");
    assertNotNull(background(stopped::switchToOnlineMode).get(30, TimeUnit.SECONDS),
        "a terminal manager refuses a later start");
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
    seam.holdHealth();
    Object lifecycleLock = lifecycleLock(manager);

    // A start's health wait.
    CompletableFuture<Throwable> start = background(() -> manager.switchToOnlineMode());
    seam.awaitHealth(1);
    assertLockFree(lifecycleLock, "while a start waits for health");
    assertFalse(start.isDone(), "the probe ran during the health wait");
    seam.releaseHealth();
    assertNull(start.get(60, TimeUnit.SECONDS), "the start completes");

    // A relaunch's drain of an in-flight reply, then the relaunched server's health wait.
    seam.holdHealth();
    AutoCloseable reply = exchange(manager, "GENERATE");
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
    await(() -> draining(manager), "the relaunch to drain");
    assertLockFree(lifecycleLock, "while a relaunch drains an in-flight reply");
    assertEquals(1, seam.models().size(), "the probe ran during the drain");
    reply.close();

    seam.awaitHealth(2);
    assertLockFree(lifecycleLock, "while a relaunched server is waiting for health");
    assertFalse(prepared.isDone(), "the probe ran during the candidate's health wait");
    seam.releaseHealth();
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

  /**
   * Opens an exchange of the given kind the way a reply does: through the manager's
   * {@code admitExchange}. The test is also the step-2 fail-before check, so on a tree without
   * the generation tracker it takes the request gate's lease instead (the same admission there).
   */
  private static AutoCloseable exchange(InferenceLifecycleManager manager, String kind)
      throws Exception {
    Method admit = admitExchange(manager);
    if (admit == null) {
      return (AutoCloseable) invoke(field(manager, "requestGate"), "acquire");
    }
    Object kindValue = null;
    for (Object constant : admit.getParameterTypes()[0].getEnumConstants()) {
      if (((Enum<?>) constant).name().equals(kind)) {
        kindValue = constant;
      }
    }
    try {
      return (AutoCloseable) admit.invoke(manager, kindValue, null);
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof RuntimeException refused) {
        throw refused;
      }
      throw failure;
    }
  }

  private static Method admitExchange(InferenceLifecycleManager manager) {
    for (Method method : manager.getClass().getMethods()) {
      if (method.getName().equals("admitExchange") && method.getParameterCount() == 2) {
        return method;
      }
    }
    return null;
  }

  /** True once generation admission is closed: a new exchange is refused. */
  private static boolean draining(InferenceLifecycleManager manager) {
    try {
      if (admitExchange(manager) == null) {
        Object gate = field(manager, "requestGate");
        Field reloading = gate.getClass().getDeclaredField("reloading");
        reloading.setAccessible(true);
        synchronized (gate) {
          return reloading.getBoolean(gate);
        }
      }
      try {
        exchange(manager, "AUX").close();
        return false;
      } catch (IllegalStateException closed) {
        return true;
      }
    } catch (Exception failure) {
      throw new AssertionError("could not probe generation admission", failure);
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
      return result.isDone() || java.util.Arrays.stream(caller.getStackTrace())
          .anyMatch(frame -> frame.getClassName().equals(ChatServerControllerImpl.class.getName())
              && frame.getMethodName().equals("await"));
    }, "the request for " + model + " to wait");
    assertFalse(result.isDone(), "the accepted target waits behind X");
    return result;
  }

  private static ModeTransitionException assertInstanceOfMode(Throwable failure) {
    assertNotNull(failure, "the superseded request fails");
    return assertThrows(ModeTransitionException.class, () -> {
      throw failure;
    });
  }
}
