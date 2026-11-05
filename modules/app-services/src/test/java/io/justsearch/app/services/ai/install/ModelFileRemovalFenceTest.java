/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.services.worker.OnnxModelStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The one removal lock in both orders, and the runtime owners' "in use" answers (design D4). */
final class ModelFileRemovalFenceTest {

  @Test
  void aUserCannotEnterWhileARemovalHoldsTheLock() {
    var fence = new ModelFileRemovalFence();
    var removal = fence.begin("chat");

    var refused = assertThrows(ModelFileRemovalFence.Refused.class, () -> fence.enter("A download or Repair"));
    assertEquals(ModelFileRemovalFence.REMOVING, refused.getMessage());
    assertThrows(ModelFileRemovalFence.Refused.class, fence::refuseWhileRemoving);
    assertEquals(Optional.of("chat"), fence.removingPart());

    removal.close();
    fence.enter("A download or Repair").close();
    fence.refuseWhileRemoving();
  }

  @Test
  void aRemovalCannotBeginWhileAUserIsRegisteredAndNamesIt() {
    var fence = new ModelFileRemovalFence();
    var user = fence.enter("An AI pack import");

    var refused = assertThrows(ModelFileRemovalFence.Refused.class, () -> fence.begin("reranker"));
    assertEquals("An AI pack import is running - try again when it finishes", refused.getMessage());

    user.close();
    user.close(); // idempotent: a second close must not release someone else's registration
    fence.begin("reranker").close();
  }

  @Test
  void anAnswerServerLaunchInProgressRefusesTheAnswerModelRemovalOnly() {
    var fence = new ModelFileRemovalFence();
    var launches = fence.answerServerLaunches();

    try (var _ = launches.admit()) {
      var refused = assertThrows(ModelFileRemovalFence.Refused.class, () -> fence.begin("chat"));
      assertEquals("An answer server start is running - try again when it finishes", refused.getMessage());
      fence.begin("reranker").close(); // Best match first's files are not the answer server's
    }
    fence.begin("chat").close();
  }

  @Test
  void aRemovalOfTheAnswerModelRefusesALaunchAndOtherRemovalsDoNot() {
    var fence = new ModelFileRemovalFence();
    var launches = fence.answerServerLaunches();

    try (var _ = fence.begin("chat")) {
      var refused = assertThrows(IllegalStateException.class, launches::admit);
      assertEquals(ModelFileRemovalFence.REMOVING, refused.getMessage());
    }
    try (var _ = fence.begin("reranker")) {
      launches.admit().close(); // answers keep starting while Best match first's files go
    }
    launches.admit().close();
  }

  @Test
  void aLaunchAndARemovalRacingNeverBothProceed() throws Exception {
    var fence = new ModelFileRemovalFence();
    var launches = fence.answerServerLaunches();
    var start = new java.util.concurrent.CyclicBarrier(2);
    var held = new java.util.concurrent.CyclicBarrier(2);
    int both = 0;
    int neither = 0;
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      for (int round = 0; round < 300; round++) {
        var launch = pool.submit(() -> {
          start.await();
          io.justsearch.app.inference.ManagedLaunchFence.Admission admitted = null;
          try {
            admitted = launches.admit();
          } catch (IllegalStateException refused) {
            // removal won
          }
          held.await(); // both sides hold what they won until both have tried
          if (admitted != null) admitted.close();
          return admitted != null;
        });
        var removal = pool.submit(() -> {
          start.await();
          ModelFileRemovalFence.Removal begun = null;
          try {
            begun = fence.begin("chat");
          } catch (ModelFileRemovalFence.Refused refused) {
            // launch won
          }
          held.await();
          if (begun != null) begun.close();
          return begun != null;
        });
        boolean launched = launch.get();
        boolean removed = removal.get();
        if (launched && removed) both++;
        if (!launched && !removed) neither++;
      }
    }
    assertEquals(0, both, "a launch and a removal of the answer model never hold together");
    assertEquals(0, neither, "neither waits for the other: one of them always proceeds");
  }

  @Test
  void aSecondRemovalCannotStartWhileOneRuns() {
    var fence = new ModelFileRemovalFence();
    var first = fence.begin("reranker");

    assertThrows(ModelFileRemovalFence.Refused.class, () -> fence.begin("reranker"));
    assertThrows(ModelFileRemovalFence.Refused.class, () -> fence.begin("chat"));

    first.close();
    assertTrue(fence.removingPart().isEmpty());
  }

  @Test
  void bestMatchFirstIsInUseUntilTheWorkerReportsNoSession() {
    assertEquals("Search is still starting - try again in a moment",
        ModelFileUsage.reranker(List.of()).orElseThrow());
    assertEquals("Best match first is still loaded - try again in a moment",
        ModelFileUsage.reranker(List.of(new OnnxModelStatus("reranker", true, "/m", false, true)))
            .orElseThrow());
    assertTrue(ModelFileUsage.reranker(List.of(
        new OnnxModelStatus("reranker", true, "/m", false, false))).isEmpty());
  }

  @Test
  void theAnswerModelIsInUseWhileAServerRunsOrAHoldIsOpen() {
    ChatActuator server = mock(ChatActuator.class);
    ChatHolds holds = mock(ChatHolds.class);
    when(holds.openHolds()).thenReturn(List.of());
    assertTrue(ModelFileUsage.answers(server, () -> false, holds).isEmpty(), "stopped and unheld");

    when(server.serverRunning()).thenReturn(true);
    assertEquals("The answer model is still loaded - try again in a moment",
        ModelFileUsage.answers(server, () -> false, holds).orElseThrow());

    when(server.serverRunning()).thenReturn(false);
    when(holds.openHolds()).thenReturn(List.of(
        new ChatHolds.OpenHold(ChatHolds.Kind.VDU, "vdu", Instant.EPOCH)));
    assertEquals("Picture reading is still using the answer model - try again in a moment",
        ModelFileUsage.answers(server, () -> false, holds).orElseThrow());
    when(holds.openHolds()).thenReturn(List.of(
        new ChatHolds.OpenHold(ChatHolds.Kind.SELF_TEST, "activation", Instant.EPOCH)));
    assertEquals("The answer model is being tested - try again when it finishes",
        ModelFileUsage.answers(server, () -> false, holds).orElseThrow());

    assertEquals("An answer server you run yourself is configured",
        ModelFileUsage.answers(null, () -> true, null).orElseThrow());
  }

  @Test
  void anUnboundProbeFailsClosed() {
    assertTrue(ModelFileRemoval.UsageProbe.UNBOUND.inUse("chat").isPresent());
    assertTrue(ModelFileRemoval.UsageProbe.UNBOUND.inUse("reranker").isPresent());
  }
}
