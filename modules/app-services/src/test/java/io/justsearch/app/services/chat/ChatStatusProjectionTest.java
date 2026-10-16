/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.core.component.ComponentState;
import org.junit.jupiter.api.Test;

/*
 * Spec A §9.2 U15 (the component half): a failed chat is a FAILED component with its classified
 * code, and a serving one is READY, with the context_reduced notice only after a step-down.
 */
final class ChatStatusProjectionTest {

  @Test
  void aFailedChatCarriesItsClassifiedCode() {
    assertEquals(new ChatStatusProjection.Component(ComponentState.FAILED, "inference.out_of_memory"),
        ChatStatusProjection.failed(FailedCode.RESOURCE_EXHAUSTED));
    assertEquals(new ChatStatusProjection.Component(ComponentState.FAILED, "inference.startup_timeout"),
        ChatStatusProjection.failed(FailedCode.STARTUP_TIMEOUT));
    assertEquals(new ChatStatusProjection.Component(ComponentState.FAILED, "inference.crashed"),
        ChatStatusProjection.failed(FailedCode.CRASH_LOOP));
  }

  @Test
  void readyCarriesTheContextReducedNoticeOnlyAfterAStepDown() {
    assertEquals(new ChatStatusProjection.Component(ComponentState.READY, "inference.context_reduced"),
        ChatStatusProjection.ready("stepped-from:16384"));
    assertEquals(new ChatStatusProjection.Component(ComponentState.READY, null),
        ChatStatusProjection.ready("top-rung"));
    assertEquals(new ChatStatusProjection.Component(ComponentState.READY, null),
        ChatStatusProjection.ready(null));
  }
}
