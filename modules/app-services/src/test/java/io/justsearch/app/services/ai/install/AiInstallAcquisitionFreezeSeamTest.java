/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static io.justsearch.app.services.chat.ManagedChatSeam.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.chat.ManagedChatSeam;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec A row 11 at the real seam, seen from outside: Install AI's acquisition window is one
 * convergence freeze. While the install is acquiring, the runtime status shows the
 * {@code INSTALL_ACQUISITION} procedure and a spec write that turns chat on is not acted on; when
 * the window ends the engine converges to the spec once. The install is entered through its real
 * entry point and held at the first stage's free-space check, before any byte moves.
 *
 * <p>The run's BITS orphan sweep removes every queued "JustSearch AI" transfer of this Windows
 * user that the run's own resume sidecars do not claim, a test's empty models directory claims
 * none, so the sweep would cancel a real install's downloads on this machine. The test reports a
 * non-Windows {@code os.name} from the start of the run until the free-space check, which the
 * sweep precedes, so the sweep is a no-op; nothing else on that path depends on it.
 */
final class AiInstallAcquisitionFreezeSeamTest {

  private static final long START_DELAY_MS = 300;

  @TempDir Path tmp;

  private ManagedChatSeam seam;
  private RuntimeIntentTestFixture intent;
  private RuntimeReconciler reconciler;
  private AutoCloseable following;
  private final String osName = System.getProperty("os.name");

  @AfterEach
  void tearDown() throws Exception {
    System.setProperty("os.name", osName);
    try {
      if (following != null) following.close();
      if (reconciler != null) reconciler.close();
      if (intent != null) intent.close();
    } finally {
      if (seam != null) seam.close();
    }
  }

  // Row 11: one freeze over the acquisition window; the spec is honoured once at its end.
  @Test
  void theAcquisitionWindowFreezesConvergenceAndTheEngineFollowsTheSpecOnceAfter()
      throws Exception {
    seam = new ManagedChatSeam(tmp.resolve("engine"), START_DELAY_MS);
    InferenceLifecycleManager manager = seam.manager();
    intent = new RuntimeIntentTestFixture(tmp.resolve("intent"), false); // a fresh install
    reconciler = new RuntimeReconciler(
        manager,
        manager::getCurrentMode,
        () -> manager.view().usingExternalLlamaServer(),
        null,
        intent.spec(),
        manager::setActivation,
        manager::componentRecoveryPending);
    Object holds = invokeIfPresent(manager, "chatHolds");
    if (holds != null) following = (AutoCloseable) invokeIfPresent(reconciler, "followHolds", holds);
    reconciler.start();
    reconciler.requestBootConvergence();
    assertTrue(reconciler.awaitQuiescent(30_000));
    assertEquals(List.of(), seam.models(), "chat is off before the install");

    Path home = tmp.resolve("ai-home");
    Path runtime = home.resolve("native-bin").resolve("llama-server").resolve("llama-server.exe");
    Files.createDirectories(runtime.getParent());
    Files.writeString(runtime, "a present runtime; never launched here");
    AiInstallService svc = newService(intent.settings(), home, holds);
    assertTrue(svc.modelsDir().startsWith(home), "downloads are planned into the test home only");

    AtomicBoolean inWindow = new AtomicBoolean();
    AtomicReference<String> procedure = new AtomicReference<>();
    AtomicReference<List<String>> launchesInWindow = new AtomicReference<>();
    AtomicBoolean onlineInWindow = new AtomicBoolean();
    svc.setFreeSpaceProbeForTest(dir -> {
      System.setProperty("os.name", osName);
      if (inWindow.compareAndSet(false, true)) {
        procedure.set(reconciler.current().condition(RuntimeStatus.Axis.PROCEDURE)
            .map(RuntimeStatus.Condition::status).orElse("absent"));
        intent.spec().setChatEnabled(true); // the user turns chat on mid-install
        reconciler.specChanged();
        try {
          Thread.sleep(3_500); // past the manager's 2 s pre-launch wait, were a start forwarded
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        launchesInWindow.set(seam.models());
        onlineInWindow.set(manager.isOnline());
        svc.cancel(); // end the run here, before any byte moves
      }
      return 1L; // no room: the stage is refused before its first byte
    });

    System.setProperty("os.name", "Linux"); // see the class comment: no machine-wide BITS sweep
    svc.startInstall(true);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while ("running".equals(svc.getStatus().state) && System.nanoTime() < deadline) {
      Thread.sleep(25);
    }
    System.setProperty("os.name", osName);

    assertTrue(inWindow.get(), "the run reached its first stage, state=" + svc.getStatus().state
        + " error=" + svc.getStatus().errorCode);
    assertEquals("INSTALL_ACQUISITION", procedure.get(),
        "the acquisition window shows as the install-acquisition procedure");
    assertEquals(List.of(), launchesInWindow.get(), "a spec write inside the window starts nothing");
    assertEquals(false, onlineInWindow.get());

    await(manager::isOnline, "the end of the window to honour the spec");
    assertTrue(reconciler.awaitQuiescent(30_000));
    Thread.sleep(4 * START_DELAY_MS); // room for a second load to record itself, were one made
    assertEquals(List.of("model.gguf"), seam.models(), "the engine follows the spec exactly once");
  }

  /**
   * The service as the Engine composes it, chosen by reflection so this check runs against the
   * constructor of either side of Spec A step 6: with the chat controller's holds where the
   * service takes them, with the reconciler where it does not.
   */
  private AiInstallService newService(UiSettingsStore settings, Path home, Object holds)
      throws Exception {
    for (Constructor<?> constructor : AiInstallService.class.getConstructors()) {
      Class<?>[] types = constructor.getParameterTypes();
      if (types.length != 6 || types[4] != Path.class) continue;
      Object last;
      if (types[5] == RuntimeReconciler.class) last = reconciler;
      else if (types[5].getSimpleName().equals("ChatHolds")) last = holds;
      else continue;
      return (AiInstallService) constructor.newInstance(
          OnlineAiService.unavailable(), settings, null, null, home, last);
    }
    throw new AssertionError("no composition constructor");
  }

  private static Object invokeIfPresent(Object target, String name, Object... args)
      throws Exception {
    for (Method method : target.getClass().getMethods()) {
      if (method.getName().equals(name) && method.getParameterCount() == args.length) {
        return method.invoke(target, args);
      }
    }
    return null;
  }
}
