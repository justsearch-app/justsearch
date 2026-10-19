/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.interaction.InteractionEvent;
import io.justsearch.agent.api.interaction.InteractionEventKind;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** WP6.2 S8.2 — a direction's outcome survives reload, and an interrupted run reports it. */
class AgentDirectionRecordTest {
  @TempDir Path tempDir;

  private static final String CONV = "conv-1";

  @Test
  void reloadedThreadShowsEachDirectionOnceWithItsFinalOutcome() {
    var records = List.of(
        ack("d1", "Focus on Q3.", "queued", null, 1),
        ack("d2", "Answer in French.", "queued", null, 2),
        ack("d1", "Focus on Q3.", "applied", null, 3),
        rec("done", Map.of("finalResponse", "answer"), 4),
        ack("d2", "Answer in French.", "not_applied", "run_finished", 5),
        ack("d3", "Still waiting.", "queued", null, 6));
    var thread = AgentInteractionMapper.fromRunEvents(records, CONV).stream()
        .filter(e -> e.kind() == InteractionEventKind.PROGRESS).toList();

    assertEquals(List.of("Focus on Q3.", "Answer in French.", "Still waiting."),
        thread.stream().map(InteractionEvent::content).toList());
    assertEquals(List.of("applied", "not_applied", "queued"),
        thread.stream().map(e -> e.attributes().get("directiveOutcome")).toList());
    assertEquals("run_finished", thread.get(1).attributes().get("directiveReason"));
    for (var event : thread) {
      assertEquals("user", event.originator());
      assertEquals(Boolean.TRUE, event.attributes().get("steer"));
    }
  }

  @Test
  void legacyAcknowledgementWithoutOutcomeIsNotShownAsApplied() {
    var thread = AgentInteractionMapper.fromRunEvents(
        List.of(rec("directive_acknowledged", Map.of("directiveText", "Old direction."), 1)), CONV);
    assertEquals(List.of(), thread, "a historical acknowledgement alone is no proof it reached the model");
  }

  @Test
  void interruptedRunReportsItsUnappliedDirectionsAsNotApplied() {
    var store = new AgentRunStore(tempDir.resolve("runs"));
    var request = new AgentRequest(List.of(Map.of("role", "user", "content", "hi")), List.of(), 5);
    store.startRun("live", request, request.messages(), 8000);
    store.updateCheckpoint("live", "WAITING_APPROVAL", request.messages(), 1, 0, 30, "");
    store.runEvents().appendEvent("live", "core.agent-run", "directive_acknowledged",
        payload("d1", "Applied one.", "queued", null));
    store.runEvents().appendEvent("live", "core.agent-run", "directive_acknowledged",
        payload("d1", "Applied one.", "applied", null));
    store.runEvents().appendEvent("live", "core.agent-run", "directive_acknowledged",
        payload("d2", "Pending one.", "queued", null));

    assertEquals(1, new AgentRunReconciler(store).reconcile(Instant.parse("2026-10-19T10:00:00Z")));
    assertEquals(0, new AgentRunReconciler(store).reconcile(), "idempotent");

    var acks = store.readEvents("live").stream()
        .filter(r -> "directive_acknowledged".equals(r.get("eventType")))
        .map(r -> (Map<?, ?>) r.get("payload")).toList();
    assertEquals(4, acks.size(), acks.toString());
    var last = acks.getLast();
    assertEquals("d2", last.get("directiveId"));
    assertEquals("Pending one.", last.get("directiveText"));
    assertEquals("not_applied", last.get("outcome"));
    assertEquals("run_interrupted", last.get("reason"));
  }

  private static Map<String, Object> ack(String id, String text, String outcome, String reason, int second) {
    return rec("directive_acknowledged", payload(id, text, outcome, reason), second);
  }

  private static Map<String, Object> payload(String id, String text, String outcome, String reason) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("directiveText", text);
    payload.put("directiveId", id);
    payload.put("outcome", outcome);
    if (reason != null) payload.put("reason", reason);
    return payload;
  }

  private static Map<String, Object> rec(String eventType, Map<String, Object> payload, int second) {
    return Map.of("timestamp", String.format("2026-01-01T00:00:%02dZ", second),
        "eventType", eventType, "payload", payload);
  }
}
