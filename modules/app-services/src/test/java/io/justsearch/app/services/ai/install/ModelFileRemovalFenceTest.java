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
