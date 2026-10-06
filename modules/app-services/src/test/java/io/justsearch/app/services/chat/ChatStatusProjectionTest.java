/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.chat.ChatState;
import io.justsearch.app.api.chat.ChatStatus;
import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.api.chat.LoadingCause;
import io.justsearch.app.api.chat.RefusedCode;
import io.justsearch.app.api.chat.UnloadReason;
import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.core.component.ComponentState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/*
 * Spec A §9.2 U15: every ChatStatus projects to one component observation and one legacy Mode
 * (Spec A §3.1); a chat that does not run never projects to INDEXING (reconciliation F4).
 */
final class ChatStatusProjectionTest {

  private static final Instant SINCE = Instant.parse("2026-10-06T08:00:00Z");

  @Test
  void everyStatusProjectsToOneComponentAndOneMode() {
    for (ChatStatus status : everyStatus()) {
      for (ComponentState current : ComponentState.values()) {
        var component = ChatStatusProjection.component(status, current);
        String where = status + " from " + current;
        switch (status.state()) {
          case LOADING -> assertEquals(
              current == ComponentState.READY || current == ComponentState.RELOADING
                  ? ComponentState.RELOADING : ComponentState.STARTING, component.state(), where);
          case READY -> assertEquals(ComponentState.READY, component.state(), where);
          case FAILED -> assertEquals(ComponentState.FAILED, component.state(), where);
          case OFF, UNLOADED, REFUSED ->
              assertEquals(ComponentState.UNAVAILABLE, component.state(), where);
        }
        if (status.state() == ChatState.READY) {
          assertNull(component.reasonCode(), where);
        } else {
          assertNotNull(component.reasonCode(), "every non-serving state names a cause: " + where);
        }
      }
      Mode mode = ChatStatusProjection.mode(status);
      assertNotEquals(Mode.INDEXING, mode, "F4: " + status);
      assertEquals(switch (status.state()) {
        case READY -> Mode.ONLINE;
        case LOADING -> Mode.TRANSITIONING;
        case OFF, UNLOADED, REFUSED, FAILED -> Mode.OFFLINE;
      }, mode, status.toString());
    }
  }

  @Test
  void aFailedChatCarriesItsClassifiedCode() {
    assertEquals(new ChatStatusProjection.Component(ComponentState.FAILED, "inference.out_of_memory"),
        ChatStatusProjection.failed(FailedCode.RESOURCE_EXHAUSTED));
    assertEquals(new ChatStatusProjection.Component(ComponentState.FAILED, "inference.crashed"),
        ChatStatusProjection.failed(FailedCode.CRASH_LOOP));
    assertEquals("inference.startup_timeout", ChatStatusProjection.component(
        status(ChatState.FAILED, FailedCode.STARTUP_TIMEOUT), ComponentState.READY).reasonCode());
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

  private static List<ChatStatus> everyStatus() {
    List<ChatStatus> all = new ArrayList<>();
    all.add(status(ChatState.OFF, null));
    all.add(status(ChatState.READY, null));
    for (LoadingCause cause : LoadingCause.values()) {
      all.add(new ChatStatus(ChatState.LOADING, Optional.of(cause), Optional.empty(),
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), SINCE, true,
          false, 0, 0, Optional.empty(), 0));
    }
    for (UnloadReason reason : UnloadReason.values()) {
      Optional<StopDetail> detail = reason == UnloadReason.RESIDENCY_PRESSURE
          ? Optional.of(StopDetail.GAME_MODE) : Optional.empty();
      all.add(new ChatStatus(ChatState.UNLOADED, Optional.empty(), Optional.of(reason),
          Optional.empty(), Optional.empty(), detail, Optional.empty(), SINCE, true, false, 0, 0,
          Optional.empty(), 0));
    }
    for (RefusedCode code : RefusedCode.values()) {
      all.add(new ChatStatus(ChatState.REFUSED, Optional.empty(), Optional.empty(),
          Optional.of(code), Optional.empty(), Optional.empty(), Optional.empty(), SINCE, true,
          false, 0, 0, Optional.empty(), 0));
    }
    for (FailedCode code : FailedCode.values()) all.add(status(ChatState.FAILED, code));
    return all;
  }

  private static ChatStatus status(ChatState state, FailedCode failed) {
    return new ChatStatus(state, Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.ofNullable(failed), Optional.empty(), Optional.empty(), SINCE, true, false, 0, 0,
        Optional.empty(), 0);
  }
}
