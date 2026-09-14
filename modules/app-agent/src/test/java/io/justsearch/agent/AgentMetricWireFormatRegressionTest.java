package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.AgentTags.AgentBudgetEdgeTags;
import io.justsearch.agent.AgentTags.AgentErrorTags;
import io.justsearch.agent.AgentTags.AgentRetryExhaustedTags;
import io.justsearch.agent.AgentTags.AgentRetryTags;
import io.justsearch.agent.AgentTags.SessionEndedTags;
import io.justsearch.agent.AgentTags.ToolCallTags;
import io.justsearch.agent.AgentTags.ToolFailureTags;
import io.justsearch.agent.GenAiTags.GenAiOperationTags;
import io.justsearch.agent.GenAiTags.GenAiTokenUsageTags;
import io.justsearch.agent.api.AgentErrorClass;
import io.justsearch.agent.api.AgentErrorCode;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.MetricCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 417 F3 wire-format regression test for {@link AgentMetricCatalog} and
 * {@link GenAiMetricCatalog}.
 */
final class AgentMetricWireFormatRegressionTest {

  @TempDir Path tmp;

  @Test
  void wireFormatStructuralEquivalence() throws Exception {
    String ndjson;
    try (LocalTelemetry telemetry =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(),
            tmp,
            500,
            "test",
            "0",
            "metrics.ndjson",
            List.of(
                MetricCatalog.of(AgentMetricCatalog.NAMESPACE, AgentMetricCatalog.DEFINITIONS),
                MetricCatalog.of(
                    GenAiMetricCatalog.NAMESPACE, GenAiMetricCatalog.DEFINITIONS)))) {
      // Tempdoc 415 F5: pass a non-trivial supplier so the active_count gauge has live values
      // to read on each flush. The test mutates the supplier between flushes to prove the
      // supplier is invoked at flush-time, not eagerly captured at construction.
      AtomicInteger sessionCount = new AtomicInteger(2);
      var agent = new AgentMetricCatalog(telemetry.registry(), sessionCount::get);
      var genAi = new GenAiMetricCatalog(telemetry.registry());

      agent.errorTotal.increment(
          new AgentErrorTags(AgentErrorCode.LLM_TRANSIENT, AgentErrorClass.TRANSIENT));
      agent.retryTotal.increment(new AgentRetryTags(AgentErrorCode.LLM_TRANSIENT, "1"));
      agent.loopBlockedTotal.increment(EmptyTags.INSTANCE);
      agent.budgetEdgeFinalizeTotal.increment(new AgentBudgetEdgeTags(true));
      agent.retryExhaustedTotal.increment(
          new AgentRetryExhaustedTags(AgentErrorCode.LLM_TRANSIENT));

      // Tempdoc 415: session-lifecycle metric emissions cover all 9 new metrics, plus the
      // three SessionEndedTags shapes (COMPLETED → no error_code/cancel_trigger; ERRORED →
      // error_code present; CANCELLED → cancel_trigger present).
      agent.sessionStartTotal.increment(EmptyTags.INSTANCE);
      agent.sessionDurationMs.record(1234L, EmptyTags.INSTANCE);
      agent.sessionTerminateTotal.increment(
          new SessionEndedTags(TerminalDisposition.COMPLETED, null, null));
      agent.sessionTerminateTotal.increment(
          new SessionEndedTags(
              TerminalDisposition.ERRORED, AgentErrorCode.BUDGET_EXHAUSTED, null));
      agent.sessionTerminateTotal.increment(
          new SessionEndedTags(TerminalDisposition.CANCELLED, null, CancelTrigger.USER));
      agent.sessionContextSizeBytesAtEnd.record(8192L, EmptyTags.INSTANCE);
      agent.sessionIterationsAtEnd.record(3L, EmptyTags.INSTANCE);
      agent.sessionToolCallsAtEnd.record(2L, EmptyTags.INSTANCE);
      agent.sessionToolCallTotal.increment(new ToolCallTags("search_index"));
      agent.sessionToolFailureTotal.increment(new ToolFailureTags("ingest_files"));

      genAi.operationDuration.record(123L, new GenAiOperationTags("rewrite"));
      genAi.tokenUsage.record(50L, new GenAiTokenUsageTags("input"));

      telemetry.flush();
      ndjson = Files.readString(tmp.resolve("telemetry").resolve("metrics.ndjson"));
    }

    assertTrue(
        anyLineWithName(ndjson, "agent.error.total").stream()
            .anyMatch(
                l ->
                    l.contains("\"error_code\":\"LLM_TRANSIENT\"")
                        && l.contains("\"error_class\":\"TRANSIENT\"")),
        "agent.error.total missing error_code/error_class; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "agent.retry.total").stream()
            .anyMatch(l -> l.contains("\"attempt\":\"1\"")),
        "agent.retry.total missing attempt tag; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "agent.budget_edge_finalize.total").stream()
            .anyMatch(l -> l.contains("\"success\":\"true\"")),
        "budget_edge_finalize missing success=true; got: " + ndjson);

    assertTrue(
        anyLineWithName(ndjson, "gen_ai.client.operation.duration").stream()
            .anyMatch(l -> l.contains("\"gen_ai.operation.name\":\"rewrite\"")),
        "gen_ai.operation.duration missing operation.name tag; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "gen_ai.client.token.usage").stream()
            .anyMatch(l -> l.contains("\"gen_ai.token.type\":\"input\"")),
        "gen_ai.token.usage missing token.type tag; got: " + ndjson);

    // Tempdoc 415 wire-format assertions.
    assertTrue(
        !anyLineWithName(ndjson, "agent.session.start_total").isEmpty(),
        "agent.session.start_total missing; got: " + ndjson);
    assertTrue(
        !anyLineWithName(ndjson, "agent.session.duration_ms").isEmpty(),
        "agent.session.duration_ms missing; got: " + ndjson);
    assertTrue(
        !anyLineWithName(ndjson, "agent.session.context_size_bytes_at_end").isEmpty(),
        "agent.session.context_size_bytes_at_end missing; got: " + ndjson);
    assertTrue(
        !anyLineWithName(ndjson, "agent.session.iterations_at_end").isEmpty(),
        "agent.session.iterations_at_end missing; got: " + ndjson);
    assertTrue(
        !anyLineWithName(ndjson, "agent.session.tool_calls_at_end").isEmpty(),
        "agent.session.tool_calls_at_end missing; got: " + ndjson);

    // disposition=COMPLETED has no error_code or cancel_trigger keys.
    assertTrue(
        anyLineWithName(ndjson, "agent.session.terminate_total").stream()
            .anyMatch(
                l ->
                    l.contains("\"disposition\":\"COMPLETED\"")
                        && !l.contains("\"error_code\"")
                        && !l.contains("\"cancel_trigger\"")),
        "terminate_total{COMPLETED} should omit error_code and cancel_trigger; got: " + ndjson);
    // disposition=ERRORED has error_code, no cancel_trigger.
    assertTrue(
        anyLineWithName(ndjson, "agent.session.terminate_total").stream()
            .anyMatch(
                l ->
                    l.contains("\"disposition\":\"ERRORED\"")
                        && l.contains("\"error_code\":\"BUDGET_EXHAUSTED\"")
                        && !l.contains("\"cancel_trigger\"")),
        "terminate_total{ERRORED} should carry error_code without cancel_trigger; got: "
            + ndjson);
    // disposition=CANCELLED has cancel_trigger, no error_code.
    assertTrue(
        anyLineWithName(ndjson, "agent.session.terminate_total").stream()
            .anyMatch(
                l ->
                    l.contains("\"disposition\":\"CANCELLED\"")
                        && l.contains("\"cancel_trigger\":\"USER\"")
                        && !l.contains("\"error_code\"")),
        "terminate_total{CANCELLED} should carry cancel_trigger without error_code; got: "
            + ndjson);

    assertTrue(
        anyLineWithName(ndjson, "agent.session.tool_call_total").stream()
            .anyMatch(l -> l.contains("\"tool_name\":\"search_index\"")),
        "agent.session.tool_call_total missing tool_name; got: " + ndjson);
    assertTrue(
        anyLineWithName(ndjson, "agent.session.tool_failure_total").stream()
            .anyMatch(l -> l.contains("\"tool_name\":\"ingest_files\"")),
        "agent.session.tool_failure_total missing tool_name; got: " + ndjson);

    // Tempdoc 415 F5: active_count gauge supplier wiring. The supplier returned 2 at flush
    // time, so the NDJSON should carry value 2 on the gauge line. (NDJSON exporter formats
    // gauge values as numbers without quotes.)
    assertTrue(
        anyLineWithName(ndjson, "agent.session.active_count").stream()
            .anyMatch(l -> l.contains("\"value\":2") || l.contains("\"value\":2.0")),
        "agent.session.active_count gauge missing or wrong value (expected 2); got: " + ndjson);
  }

  /**
   * Tempdoc 415 F5: independent test that the gauge supplier is re-invoked on each flush
   * (not captured eagerly at construction). Mutates the supplier between two flushes and
   * asserts the second NDJSON shows the new value.
   */
  @Test
  void activeCountGaugeReadsSupplierLiveOnEachFlush() throws Exception {
    String firstNdjson;
    String secondNdjson;
    try (LocalTelemetry telemetry =
        new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(),
            tmp,
            500,
            "test",
            "0",
            "metrics-live.ndjson",
            List.of(
                MetricCatalog.of(AgentMetricCatalog.NAMESPACE, AgentMetricCatalog.DEFINITIONS),
                MetricCatalog.of(
                    GenAiMetricCatalog.NAMESPACE, GenAiMetricCatalog.DEFINITIONS)))) {
      AtomicInteger live = new AtomicInteger(7);
      // Build catalog with the live supplier.
      new AgentMetricCatalog(telemetry.registry(), live::get);
      new GenAiMetricCatalog(telemetry.registry());

      telemetry.flush();
      firstNdjson = Files.readString(tmp.resolve("telemetry").resolve("metrics-live.ndjson"));

      live.set(11);
      telemetry.flush();
      secondNdjson = Files.readString(tmp.resolve("telemetry").resolve("metrics-live.ndjson"));
    }

    assertTrue(
        anyLineWithName(firstNdjson, "agent.session.active_count").stream()
            .anyMatch(l -> l.contains("\"value\":7") || l.contains("\"value\":7.0")),
        "First flush should reflect supplier value 7; got: " + firstNdjson);
    assertTrue(
        anyLineWithName(secondNdjson, "agent.session.active_count").stream()
            .anyMatch(l -> l.contains("\"value\":11") || l.contains("\"value\":11.0")),
        "Second flush should reflect supplier value 11 (proves supplier is invoked live, "
            + "not captured at construction); got: " + secondNdjson);
  }

  private static List<String> anyLineWithName(String ndjson, String name) {
    List<String> out = new ArrayList<>();
    for (String line : ndjson.split("\n")) {
      if (line.contains("\"name\":\"" + name + "\"")) {
        out.add(line);
      }
    }
    return out;
  }
}
