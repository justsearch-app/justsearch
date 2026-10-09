/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.chat.GenerationLease;
import io.justsearch.app.api.runtime.child.StopReason;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Spec E step E2, ASSIST leases (reconciliation v2: "Chat generating (HUMAN_CHAT, AGENT_CHAT,
 * BACKGROUND GENERATE leases; not ASSIST)"; F13: "AUX and ASSIST never wait: they fail fast in
 * DRAINING, STARTING and STOPPING, as today. `inFlight` counts GENERATE leases only.").
 */
final class AssistLeaseTest {

  private static final Duration BOUND = Duration.ofSeconds(5);

  @Test
  void assistLeasesHoldDrainsAndFailFastButNeverGenerateOrPace() throws Exception {
    var entered = new AtomicInteger();
    var edges = new ArrayList<String>();
    var tracker = new GenerationTracker(work -> {
      entered.incrementAndGet();
      return () -> {};
    }, Runnable::run);
    tracker.onEdge((generating, epoch) -> edges.add(generating + "@" + epoch));

    GenerationTracker.Ticket ticket =
        tracker.queue(GenerationTracker.Kind.ASSIST, GenerationTrackerTest.work());
    GenerationLease assist = ticket.admit();
    assertFalse(tracker.generating(), "an ASSIST lease is not generating");
    assertEquals(0, tracker.inFlight(), "inFlight counts GENERATE leases only");
    assertEquals(List.of(), edges, "an ASSIST lease opens no edge");
    assertEquals(0, entered.get(), "an ASSIST exchange does not pace indexing");

    assertNull(tracker.closeIfIdle(), "an idle unload defers while an ASSIST lease is open");
    assertThrows(IllegalStateException.class, () -> tracker.drain(
        ChatDrainPolicy.forReason(StopReason.RECONFIGURE, Duration.ZERO)),
        "a drain waits for an open ASSIST lease");
    assertTrue(tracker.admissionOpen(), "a timed-out drain reopens admission");
    assist.close();
    ticket.close();

    var hold = tracker.drain(ChatDrainPolicy.forReason(StopReason.RECONFIGURE, BOUND));
    try {
      assertThrows(IllegalStateException.class,
          () -> tracker.admit(GenerationTracker.Kind.ASSIST, null),
          "ASSIST never waits: it fails fast while admission is closed");
    } finally {
      hold.close();
    }
    assertEquals(List.of(), edges);
  }

  // Spec E §1.2 / F19: a VDU lease holds a drain and keeps its work handle, but never generates
  @Test
  void vduLeasesHoldDrainsButAreNeverCountedAsGenerating() throws Exception {
    var entered = new AtomicInteger();
    var released = new AtomicInteger();
    var edges = new ArrayList<String>();
    var tracker = new GenerationTracker(work -> {
      entered.incrementAndGet();
      return released::incrementAndGet;
    }, Runnable::run);
    tracker.onEdge((generating, epoch) -> edges.add(generating + "@" + epoch));

    GenerationTracker.Ticket ticket =
        tracker.queue(GenerationTracker.Kind.VDU, GenerationTrackerTest.work());
    assertEquals(1, entered.get(), "a VDU exchange keeps its work handle like GENERATE");
    GenerationLease vdu = ticket.admit();
    assertFalse(tracker.generating(), "a VDU lease is not chat generating");
    assertEquals(0, tracker.inFlight(), "inFlight counts GENERATE leases only");
    assertEquals(List.of(), edges, "a VDU lease opens no edge");

    assertNull(tracker.closeIfIdle(), "an idle unload defers while a VDU lease is open");
    assertThrows(IllegalStateException.class, () -> tracker.drain(
        ChatDrainPolicy.forReason(StopReason.RECONFIGURE, Duration.ZERO)),
        "a drain waits for an open VDU lease");
    vdu.close();
    ticket.close();
    assertEquals(1, released.get());

    GenerationLease chat = tracker.admit(GenerationTracker.Kind.GENERATE, null);
    try (chat) {
      assertTrue(tracker.generating(), "a chat reply still counts");
    }
    assertEquals(List.of("true@1", "false@1"), edges);
  }
}
