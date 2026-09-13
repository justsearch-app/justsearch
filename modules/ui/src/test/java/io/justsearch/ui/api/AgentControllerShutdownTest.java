/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 638 PE — regression guard for the heartbeat-scheduler leak. {@code AgentController} is
 * registered inline (not as an {@code ApiModule}), so before this fix its {@code shutdown()} was
 * wired into no teardown path and its scheduler thread leaked. {@code LocalApiServer.shutdown()} now
 * calls it; this test pins that {@code shutdown()} actually stops the scheduler.
 */
@DisplayName("AgentController.shutdown stops the heartbeat scheduler (638 PE)")
final class AgentControllerShutdownTest {

  @Test
  void shutdownStopsHeartbeatScheduler() {
    AgentController controller = new AgentController(new io.justsearch.core.execution.TestEngineExecutors(), () -> null, null, null, null);
    assertFalse(
        controller.isHeartbeatSchedulerShutdown(), "scheduler should be running before shutdown");

    controller.shutdown();
    assertTrue(
        controller.isHeartbeatSchedulerShutdown(), "shutdown() must stop the heartbeat scheduler");

    // Idempotent — a second shutdown (e.g. double teardown) is safe.
    controller.shutdown();
    assertTrue(controller.isHeartbeatSchedulerShutdown());
  }
}
