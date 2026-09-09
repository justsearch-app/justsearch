/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class OrchestrationHandlesShutdownTest {
  @Test
  void childCleanupFailureReachesOrderedShutdownAfterRemainingOwnersClose() {
    List<String> order = new ArrayList<>();
    var handles = new OrchestrationHandles(
        () -> order.add("gpl"), () -> order.add("reranker"), null, null, null, null,
        () -> { order.add("inference"); throw new IllegalStateException("child still alive"); },
        null, null, null, null, null, null, () -> order.add("agents"));
    var failure = assertThrows(IllegalStateException.class, handles::close);
    assertEquals(List.of("gpl", "agents", "inference", "reranker"), order);
    assertEquals("child still alive", failure.getSuppressed()[0].getMessage());
  }
}
