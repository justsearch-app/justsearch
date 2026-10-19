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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

  /**
   * The process can die between any two durable writes. Whatever was saved last, a direction that
   * never went out in a model request must reach the reloaded thread as not applied, with why, once.
   */
  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({
    // died before recovery ran at all
    "unfinished run, READY_FOR_LLM, false, run_interrupted",
    // died during recovery: the interruption was stamped, the direction's outcome not yet written
    "unfinished run stamped by an interrupted recovery, WAITING_APPROVAL, true, run_interrupted",
    // died while finishing: the terminal checkpoint was saved, the final outcome not yet written
    "run that finished, DONE, false, run_finished",
    "run that was stopped, CANCELLED, false, run_stopped",
    "run that failed, ERROR, false, run_failed",
  })
  void recoveryReportsADirectionLeftQueuedByAnyInterruption(
      String label, String state, boolean stamped, String reason) {
    Path root = tempDir.resolve("runs");
    var store = new AgentRunStore(root);
    seedRun(store, "r1", state);
    store.runEvents().appendEvent("r1", "core.agent-run", "directive_acknowledged",
        payload("d1", "Applied one.", "queued", null));
    store.runEvents().appendEvent("r1", "core.agent-run", "directive_acknowledged",
        payload("d1", "Applied one.", "applied", null));
    store.runEvents().appendEvent("r1", "core.agent-run", "directive_acknowledged",
        payload("d2", "Answer in French.", "queued", null));
    if (stamped) {
      assertTrue(store.markInterrupted("r1", Instant.parse("2026-10-19T09:00:00Z")));
    }

    // A later process: a fresh store over the same files, then the startup pass, twice.
    var restarted = new AgentRunStore(root);
    new AgentRunReconciler(restarted).reconcile(Instant.parse("2026-10-19T10:00:00Z"));
    new AgentRunReconciler(restarted).reconcile(Instant.parse("2026-10-19T11:00:00Z"));

    var directions = new AgentRunQueryService(restarted, null, null, null, null, null)
        .threadEvents(CONV).stream()
        .filter(e -> Boolean.TRUE.equals(e.attributes().get("steer")))
        .toList();
    assertEquals(List.of("Applied one.", "Answer in French."),
        directions.stream().map(InteractionEvent::content).toList());
    assertEquals("applied", directions.get(0).attributes().get("directiveOutcome"));
    assertEquals("not_applied", directions.get(1).attributes().get("directiveOutcome"),
        "a direction that never went out must not stay queued in the reloaded record");
    assertEquals(reason, directions.get(1).attributes().get("directiveReason"));
    assertEquals(List.of("queued", "applied", "queued", "not_applied"), outcomes(restarted, "r1"),
        "exactly one final outcome per direction, however often recovery runs");
  }

  @Test
  void recoveryLeavesAFinishedRunWhoseOutcomesWereAllWrittenAlone() {
    Path root = tempDir.resolve("runs");
    var store = new AgentRunStore(root);
    seedRun(store, "r1", "DONE");
    store.runEvents().appendEvent("r1", "core.agent-run", "directive_acknowledged",
        payload("d1", "Too late.", "queued", null));
    store.runEvents().appendEvent("r1", "core.agent-run", "directive_acknowledged",
        payload("d1", "Too late.", "not_applied", "run_finished"));
    // The live run's last write, after every direction's outcome.
    store.setTerminationReason("r1",
        new TerminationReason(TerminalDisposition.COMPLETED, null, null));

    var restarted = new AgentRunStore(root);
    assertEquals(0, new AgentRunReconciler(restarted).reconcile());
    assertEquals(List.of("queued", "not_applied"), outcomes(restarted, "r1"));
    assertNull(restarted.readSnapshot("r1").get("interruptedAt"), "a finished run is not interrupted");
  }

  private static void seedRun(AgentRunStore store, String sessionId, String state) {
    List<Map<String, Object>> messages = List.of(
        Map.of("role", "system", "content", "Be helpful."),
        Map.of("role", "user", "content", "Inspect the files."));
    var request = new AgentRequest(messages, List.of(), 4, List.of(), null, null, CONV, null,
        List.of(), null, false, null);
    store.startRun(sessionId, request, messages, 10_000);
    store.updateCheckpoint(sessionId, state, messages, 1, 0, 30, "");
  }

  private static List<Object> outcomes(AgentRunStore store, String sessionId) {
    return store.readEvents(sessionId).stream()
        .filter(r -> "directive_acknowledged".equals(r.get("eventType")))
        .map(r -> ((Map<?, ?>) r.get("payload")).get("outcome"))
        .map(Object.class::cast)
        .toList();
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
