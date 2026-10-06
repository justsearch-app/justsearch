/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static io.justsearch.app.services.chat.ManagedChatSeam.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.Mode;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.runtimestate.RuntimeGpuLease;
import io.justsearch.app.services.runtimestate.RuntimeIntentTestFixture;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reconciliation F6 at the real seam, wired as the Engine wires it: the runtime reconciler only
 * forwards boot and spec intent to the chat controller as {@code setActivation}, so an unload made
 * by anyone else stays unloaded while {@code chatEnabled} is true.
 */
final class ChatActivationSeamTest {

  private static final long START_DELAY_MS = 500;

  @TempDir Path directory;

  private ManagedChatSeam seam;
  private InferenceLifecycleManager manager;
  private RuntimeIntentTestFixture intent;
  private RuntimeReconciler reconciler;

  @BeforeEach
  void setUp() throws Exception {
    seam = new ManagedChatSeam(directory, START_DELAY_MS);
    manager = seam.manager();
    intent = new RuntimeIntentTestFixture(directory.resolve("intent"), true);
    reconciler = new RuntimeReconciler(
        manager,
        manager::getCurrentMode,
        () -> manager.view().usingExternalLlamaServer(),
        null,
        intent.spec(),
        new RuntimeGpuLease(),
        manager::setActivation,
        manager::componentRecoveryPending);
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      reconciler.close();
      intent.close();
    } finally {
      seam.close();
    }
  }

  @Test
  void bootForwardsOneActivationAndAForeignUnloadIsNeverReloaded() throws Exception {
    reconciler.start();
    reconciler.requestBootConvergence();
    await(manager::isOnline, "boot intent to bring chat online");
    assertTrue(reconciler.awaitQuiescent(30_000));
    assertEquals(List.of("model.gguf"), seam.models(), "boot forwards one activation");

    // Unloads the reconciler did not ask for, repeated past the deleted anti-flap cap of 3.
    for (int i = 1; i <= 4; i++) {
      manager.switchToIndexingMode();
      assertTrue(reconciler.awaitQuiescent(30_000));
      Thread.sleep(2 * START_DELAY_MS); // room for a reload to launch, were one requested
      assertEquals(Mode.INDEXING, manager.getCurrentMode(), "a foreign unload stays unloaded");
      assertEquals(i, seam.models().size(), "no reload after foreign unload " + i);
      if (i < 4) {
        manager.switchToOnlineMode(); // the next cycle's load, by the same foreign actor
        assertEquals(i + 1, seam.models().size());
      }
    }

    // An explicit spec write is forwarded again.
    reconciler.specChanged();
    await(manager::isOnline, "the spec write to bring chat back");
    assertTrue(reconciler.awaitQuiescent(30_000));
    assertEquals(5, seam.models().size(), "the spec write forwards one activation");
  }
}
