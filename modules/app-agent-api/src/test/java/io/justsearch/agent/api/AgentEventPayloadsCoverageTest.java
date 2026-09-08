/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 834 §6.3.1 — the RECORD-COMPONENT coverage gate for {@link AgentEventPayloads#base}.
 *
 * <p>The gap this closes: adding a component to an {@link AgentEvent} record does NOT change the
 * wire until {@code base()} is hand-edited, and unlike a missing VARIANT (which the exhaustive
 * switch rejects at compile time) a missing FIELD is silent — the run persists and streams an event
 * that is quietly missing state. 834 added three components to {@link AgentEvent.StateSnapshot}
 * exactly this way; this test is what makes the next such addition loud.
 *
 * <p>Three constraints the naive version gets wrong, all of them deliberate here:
 *
 * <ul>
 *   <li><b>{@code trace} is excluded.</b> {@code base()} deliberately omits it — {@code withTrace}
 *       appends it afterwards — so a blanket every-component assertion would fail on EVERY variant.
 *   <li><b>Per-variant, not generic over the sealed set.</b> Component-name-to-payload-key is not
 *       one-to-one across all permits: {@code ToolCallProposed} expands its single {@code call}
 *       component into three keys. So this test is scoped to the FLAT-MAPPING variants listed
 *       below, {@code StateSnapshot} first.
 *   <li><b>Keyed off a list whose permit-parity is already asserted elsewhere</b> — the sealed set
 *       is covered by {@code AgUiEventTranslatorConformanceTest.coversEveryPermit} and
 *       {@code AgentEventSchemaConformanceTest}, so this file does not fork a second permit census;
 *       it asserts instead that every variant it DOES cover is genuinely flat-mapping.
 * </ul>
 */
final class AgentEventPayloadsCoverageTest {

  /** The component {@code base()} deliberately omits (see {@code AgentEventPayloads#withTrace}). */
  private static final String TRACE_COMPONENT = "trace";

  /**
   * Variants whose record components map ONE-TO-ONE onto {@code base()} payload keys. Deliberately
   * not the whole sealed set — see the class javadoc's second constraint.
   */
  private static final List<AgentEvent> FLAT_MAPPING_VARIANTS =
      List.of(
          new AgentEvent.StateSnapshot(
              2,
              1500,
              3,
              7,
              "primary",
              List.of(new AgentEvent.PendingApproval("c1", "core_write", "{}", "high", "confirm")),
              "WATCH",
              new AgentEvent.ParkSnapshot("approval", 1_700_000_000_000L, "c1"),
              TraceContext.none()),
          new AgentEvent.TextChunk("hi"),
          new AgentEvent.ReasoningChunk("thinking"),
          new AgentEvent.ToolCallApproved("c1"),
          new AgentEvent.ToolCallRejected("c1", "no"),
          new AgentEvent.ToolExecutionStarted("c1", "tool"),
          new AgentEvent.ToolCallVirtual("c1", "wire", "args"),
          new AgentEvent.DirectiveAcknowledged("focus"),
          new AgentEvent.ContextCompacted(4),
          // Lane F PR 0b: the three applied-sampling components are POPULATED here on purpose.
          // base() omits each when null (so an un-echoed session_started stays byte-identical to
          // its pre-PR-0b payload), and this test's question is "does a component that IS set reach
          // the wire" — which a null-everywhere variant could not ask.
          new AgentEvent.SessionStarted("sid", 0.7, 0.8, 20260907L, TraceContext.none()),
          new AgentEvent.HandoffExecuted("a", "b"),
          new AgentEvent.HandoffProposed("a", "b", "why"),
          new AgentEvent.BudgetGatePending(1, 2, 3),
          new AgentEvent.ContextGatePending(7800, 8192),
          new AgentEvent.AgentProgress("phase", "msg", 1, 8),
          new AgentEvent.AgentBudgetUpdate("phase", 1, 2, 3, 4, 5));

  @Test
  @DisplayName("every record component (except trace) appears as a base() payload key")
  void everyComponentIsCarried() {
    for (AgentEvent event : FLAT_MAPPING_VARIANTS) {
      Set<String> components = componentsOf(event);
      Set<String> keys = new TreeSet<>(AgentEventPayloads.base(event).keySet());
      Set<String> missing =
          components.stream()
              .filter(c -> !keys.contains(c))
              .collect(Collectors.toCollection(TreeSet::new));
      assertTrue(
          missing.isEmpty(),
          () ->
              event.getClass().getSimpleName()
                  + " declares component(s) "
                  + missing
                  + " that base() does not emit — adding a component does NOT change the wire until"
                  + " AgentEventPayloads.base() is edited, and a missing FIELD is silent (834"
                  + " §6.3.1). Emit them, or move this variant off FLAT_MAPPING_VARIANTS with a"
                  + " reason.");
    }
  }

  @Test
  @DisplayName("FLAT_MAPPING_VARIANTS really are flat — no variant emits an undeclared key")
  void flatMeansFlat() {
    // The converse direction: if base() emitted a key with no matching component, the variant is
    // NOT flat-mapping (that is the ToolCallProposed shape) and does not belong in this list — the
    // forward assertion above would then be checking a weaker property than it claims to.
    for (AgentEvent event : FLAT_MAPPING_VARIANTS) {
      Set<String> components = componentsOf(event);
      Set<String> extra =
          AgentEventPayloads.base(event).keySet().stream()
              .filter(k -> !components.contains(k))
              .collect(Collectors.toCollection(TreeSet::new));
      assertTrue(
          extra.isEmpty(),
          () ->
              event.getClass().getSimpleName()
                  + " emits key(s) "
                  + extra
                  + " with no matching record component — it is not flat-mapping, so remove it from"
                  + " FLAT_MAPPING_VARIANTS.");
    }
  }

  @Test
  @DisplayName("base() omits trace on every variant (the exclusion is real, not vacuous)")
  void traceIsExcludedNotForgotten() {
    // Guards the exclusion itself: if base() ever started emitting `trace`, skipping it above would
    // silently stop testing anything about it, and withTrace would double-write.
    for (AgentEvent event : FLAT_MAPPING_VARIANTS) {
      assertFalse(
          AgentEventPayloads.base(event).containsKey(TRACE_COMPONENT),
          () -> event.getClass().getSimpleName() + ": base() must not emit the trace envelope");
    }
  }

  @Test
  @DisplayName("StateSnapshot: pendingApprovals is emitted-empty for none, park is ABSENT")
  void unknownIsNotNone() {
    // 834 §6.3.3 — the tri-state. A running, unparked snapshot emits `pendingApprovals: []`
    // ("none") and OMITS `park`. A legacy events.ndjson record simply lacks the key, and a consumer
    // must read that absence as UNKNOWN — which is only representable because the producer always
    // writes the key when it knows the answer.
    Map<String, Object> running =
        AgentEventPayloads.base(new AgentEvent.StateSnapshot(1, 10, 0, 2, "primary"));
    assertTrue(running.containsKey("pendingApprovals"), "the key is emitted even when empty");
    assertEquals(List.of(), running.get("pendingApprovals"), "empty list means NONE pending");
    assertFalse(running.containsKey("park"), "an unparked run omits park entirely");
    assertEquals("ASSIST", running.get("autonomyLevel"), "the dial is never null");

    Map<String, Object> parked =
        AgentEventPayloads.base(
            new AgentEvent.StateSnapshot(
                1,
                10,
                0,
                2,
                "primary",
                List.of(new AgentEvent.PendingApproval("c1", "core_write", "{}", "high", null)),
                "WATCH",
                new AgentEvent.ParkSnapshot("approval", 42L, "c1"),
                TraceContext.none()));
    List<?> approvals = assertInstanceOf(List.class, parked.get("pendingApprovals"));
    assertEquals(1, approvals.size());
    Map<?, ?> approval = assertInstanceOf(Map.class, approvals.get(0));
    assertEquals("c1", approval.get("callId"));
    assertEquals("high", approval.get("risk"));
    assertFalse(
        approval.containsKey("gateBehavior"), "a null gate verdict is ABSENT, not an empty string");
    Map<?, ?> park = assertInstanceOf(Map.class, parked.get("park"));
    assertEquals("approval", park.get("kind"));
    assertEquals(42L, park.get("sinceEpochMs"));
  }

  /**
   * Tempdoc 878 §D.4 — the wire says how much of a tool's output the MODEL received, and stays
   * SILENT when nobody measured.
   *
   * <p>The defect: {@code output} carried the tool's whole answer while the loop appended a
   * Layer-2-truncated copy to the prompt, so one field quietly answered two different questions and
   * a reader debugging a wrong answer was looking at evidence the model never had.
   *
   * <p>The absent case is asserted first because it is the one a "helpful" default would break. An
   * emitter that did not measure must produce NO key — never {@code truncatedForModel: false},
   * which would retroactively describe every record written before this field as complete.
   */
  @Test
  @DisplayName("878 §D.4: tool_exec_completed reports what the model saw, and says nothing when unmeasured")
  void toolCompletedReportsWhatReachedTheModel() {
    var full =
        io.justsearch.agent.api.registry.OperationResult.success("0123456789ABCDEFGHIJ");

    Map<String, Object> unmeasured =
        AgentEventPayloads.base(new AgentEvent.ToolExecutionCompleted("c1", full));
    assertFalse(
        unmeasured.containsKey("outputCharsToModel"),
        "an emitter that did not measure writes NO key — absent is 'unknown', not 'all of it'");
    assertFalse(unmeasured.containsKey("truncatedForModel"), "and neither half appears alone");

    Map<String, Object> truncated =
        AgentEventPayloads.base(new AgentEvent.ToolExecutionCompleted("c1", full, 8));
    assertEquals(
        "0123456789ABCDEFGHIJ",
        truncated.get("output"),
        "`output` stays the tool's WHOLE answer: the reader is not context-bound, and showing less"
            + " than the tool returned would be a new dishonesty rather than a fix for the old one");
    assertEquals(8, truncated.get("outputCharsToModel"), "and beside it, what the prompt got");
    assertEquals(
        true,
        truncated.get("truncatedForModel"),
        "derived from the count, never carried independently — two fields can contradict each other");

    Map<String, Object> whole =
        AgentEventPayloads.base(
            new AgentEvent.ToolExecutionCompleted("c1", full, full.message().length()));
    assertEquals(
        false,
        whole.get("truncatedForModel"),
        "measured-and-complete is a real, DIFFERENT answer from unmeasured — a consumer can tell"
            + " 'the model got everything' from 'nobody said', which is the whole point of the pair");
  }

  /**
   * Tempdoc 877 open items — the tool-error classification reaches the wire.
   *
   * <p>{@code AgentToolErrors} has typed every tool failure since 877 ({@code BAD_REQUEST} for the
   * model's own bad argument, {@code SERVICE_UNAVAILABLE} for a Worker outage) and the payload
   * dropped all of it: a consumer saw {@code success:false} plus prose and could not tell a model
   * mistake from a system fault — the exact distinction 877 created the codes to carry.
   *
   * <p>Absence stays load-bearing, as with the pair above: an unclassified failure writes NEITHER
   * key rather than a placeholder code.
   */
  @Test
  @DisplayName("877: tool_exec_completed carries errorCode + retryable, and stays silent when unset")
  void toolCompletedCarriesTheErrorClassification() {
    var unclassified =
        io.justsearch.agent.api.registry.OperationResult.failure("Browse error: something");
    Map<String, Object> silent =
        AgentEventPayloads.base(new AgentEvent.ToolExecutionCompleted("c1", unclassified));
    assertFalse(
        silent.containsKey("errorCode"),
        "a tool that classified nothing must not be given a code on the wire");
    assertFalse(silent.containsKey("retryable"), "and neither half appears alone");

    var classified =
        io.justsearch.agent.api.registry.OperationResult.failure(
            "Browse error: the knowledge worker is not reachable",
            "SERVICE_UNAVAILABLE",
            Map.of("tool", "core_browse_folders"),
            true);
    Map<String, Object> payload =
        AgentEventPayloads.base(new AgentEvent.ToolExecutionCompleted("c2", classified));
    assertEquals("SERVICE_UNAVAILABLE", payload.get("errorCode"));
    assertEquals(true, payload.get("retryable"));
    assertEquals(false, payload.get("success"), "the classification rides the failure, not replaces it");

    var modelInput =
        io.justsearch.agent.api.registry.OperationResult.failure(
            "Read error: \"offset_chars\" must be a number",
            "BAD_REQUEST",
            Map.of("tool", "core_read_document"),
            false);
    Map<String, Object> inputPayload =
        AgentEventPayloads.base(new AgentEvent.ToolExecutionCompleted("c3", modelInput));
    assertEquals("BAD_REQUEST", inputPayload.get("errorCode"));
    assertEquals(
        false,
        inputPayload.get("retryable"),
        "the distinction the FE needs: retrying the same arguments cannot help");

    var ok = io.justsearch.agent.api.registry.OperationResult.success("done");
    Map<String, Object> okPayload =
        AgentEventPayloads.base(new AgentEvent.ToolExecutionCompleted("c4", ok));
    assertFalse(okPayload.containsKey("errorCode"), "a success carries no error classification");
    assertFalse(okPayload.containsKey("retryable"), okPayload.toString());
  }

  /**
   * Tempdoc 878 review B1 — the count is a fraction OF THE OUTPUT, so it can never exceed it.
   *
   * <p>The first implementation measured the string {@code AgentContextCompressor.truncate}
   * RETURNS, which is the prefix plus a {@code [... truncated, N chars omitted]} marker. For an
   * output just over the cap that number was LARGER than the output itself, which flipped the
   * derived {@code truncatedForModel} to {@code false} — a measured "the model got all of it" on an
   * output it demonstrably did not get all of, which is the exact mixed signal the one-component
   * design was chosen to make impossible.
   *
   * <p>Asserted as an INVARIANT over the boundary rather than against a magic number, so it stays
   * true if the cap or the marker's wording ever changes.
   */
  @Test
  @DisplayName("878 B1: outputCharsToModel never exceeds the output it is a fraction of")
  void theModelCountIsAFractionOfTheOutput() {
    String output = "x".repeat(9000);
    var result = io.justsearch.agent.api.registry.OperationResult.success(output);

    // The value a producer measuring the TRUNCATED string would have reported: prefix + marker.
    int naive = 4000 + "\n[... truncated, 5000 chars omitted]".length();
    assertTrue(naive < output.length(), "this fixture must be big enough for the naive value to fit");

    var honest = new AgentEvent.ToolExecutionCompleted("c1", result, 4000);
    assertTrue(
        honest.outputCharsToModel() <= output.length(),
        "the count is characters OF THE OUTPUT — the truncation marker is in the prompt but it is"
            + " not the tool's answer, and this field counts the answer");
    assertTrue(honest.truncatedForModel(), "and a genuinely truncated output says so");

    // The inversion, stated directly: a count that includes the marker can exceed a small overflow.
    var overflowed = new AgentEvent.ToolExecutionCompleted("c2", result, output.length() + 1);
    assertFalse(
        overflowed.truncatedForModel(),
        "a count larger than the output silently reads as 'not truncated' — which is why the"
            + " producer must measure the output, not the envelope it was wrapped in");
  }

  private static Set<String> componentsOf(AgentEvent event) {
    RecordComponent[] components = event.getClass().getRecordComponents();
    return java.util.Arrays.stream(components)
        .map(RecordComponent::getName)
        .filter(name -> !TRACE_COMPONENT.equals(name))
        .collect(Collectors.toCollection(TreeSet::new));
  }
}
