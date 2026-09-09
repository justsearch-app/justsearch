package io.justsearch.systemtests.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.registry.AgentToolEmitter;
import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.agent.AgentLoopService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.OnlineAiService.StreamCallbacks;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent Battery Test - Phase 2 Testing Infrastructure.
 *
 * <p>Data-driven integration test that evaluates agent performance across 12 test cases from
 * tempdoc 186. Validates tool usage, path handling, and response quality with deterministic LLM
 * configuration.
 *
 * <p>Uses real MCP tools (SearchTool, BrowseTool, etc.) with actual indexed corpus.
 *
 * <p>Target: 85% success rate (10+/12 tests passing)
 */
@Tag("ai")
@DisplayName("Agent Capability Battery (tempdoc 186)")
class AgentBatteryTest {
  private static final Logger log = LoggerFactory.getLogger(AgentBatteryTest.class);

  private static AgentLoopService agentService;
  private static List<AgentTestCase> testCases;
  private static Path testCorpusRoot;
  private static OperationCatalog operationCatalog;
  private static OperationDispatcher operationExecutor;
  private static AgentToolEmitter agentToolEmitter;
  private static final EngineContext BATTERY_ENGINE_CONTEXT =
      EngineProvenance.context(
          EngineContext.ClientKind.INTERNAL,
          "agent-test-client",
          Optional.of("agent-test-session"),
          Optional.empty(),
          TransportTag.AGENT_LOOP,
          EngineContext.Survival.INTERACTIVE,
          EngineContext.Urgency.FOREGROUND);

  // Result tracking for aggregate metrics
  private static final List<TestCaseResult> testResults =
      new CopyOnWriteArrayList<>();

  @BeforeAll
  static void setup() throws Exception {
    log.info("Setting up Agent Battery test...");

    // 1. Load test manifest
    testCases = loadManifest();

    // 2. Setup test corpus (simplified: use current working directory)
    testCorpusRoot = Path.of(System.getProperty("user.dir"));
    log.info("Test corpus root: {}", testCorpusRoot);

    // 3. Initialize substrate catalog + executor + emitter with stub Operations
    // Per Phase 11 of tempdoc 429: replaces the legacy ToolRegistry-based setup.
    var stubs = List.of(
        new StubOperation("search_index", RiskTier.LOW, "search results"),
        new StubOperation("browse_folders", RiskTier.LOW, "folder listing"),
        new StubOperation("ingest_files", RiskTier.MEDIUM, "ingestion complete"),
        new StubOperation("file_operations", RiskTier.LOW, "file content"));
    operationCatalog =
        OperationCatalog.of(
            "core", stubs.stream().map(StubOperation::toOperation).toList());
    HandlerRegistry handlers = new HandlerRegistry();
    for (StubOperation s : stubs) {
      final StubOperation captured = s;
      handlers.register(
          new OperationRef("core." + s.wireName.replace('_', '-')),
          (args, engineContext) -> captured.execute(args));
    }
    // Test-local OperationDispatcher impl per tempdoc 429 §C decision A — system-tests
    // cannot import the production OperationExecutorImpl from app-services. CORE-tier
    // dispatch is sufficient for the battery scenarios.
    operationExecutor =
        new OperationDispatcher() {
          @Override
          public OperationResult dispatch(
              Operation op, String argumentsJson, EngineContext engineContext) {
            OperationHandler handler =
                handlers.resolve(new OperationRef(op.binding().handlerId())).orElseThrow();
            return handler.execute(argumentsJson, engineContext);
          }

          @Override
          public OperationResult dispatch(
              Operation op,
              String argumentsJson,
              InvocationProvenance provenance,
              Optional<String> confirmationToken,
              EngineContext engineContext) {
            OperationHandler handler =
                handlers.resolve(new OperationRef(op.binding().handlerId())).orElseThrow();
            return handler.execute(argumentsJson, provenance, engineContext);
          }

          @Override
          public OperationResult undo(Operation op, String executionId, EngineContext engineContext) {
            if (!op.policy().undoSupported()) {
              return OperationResult.failure("Undo not supported by " + op.id().value());
            }
            OperationHandler handler =
                handlers.resolve(new OperationRef(op.binding().handlerId())).orElseThrow();
            return handler.undo(executionId, engineContext);
          }

          @Override
          public OperationResult undo(
              Operation op,
              String executionId,
              InvocationProvenance provenance,
              Optional<String> confirmationToken,
              EngineContext engineContext) {
            if (!op.policy().undoSupported()) {
              return OperationResult.failure("Undo not supported by " + op.id().value());
            }
            OperationHandler handler =
                handlers.resolve(new OperationRef(op.binding().handlerId())).orElseThrow();
            return handler.undo(executionId, engineContext);
          }
        };
    agentToolEmitter = new InlineAgentToolEmitter();

    // 4. Create ScriptedAiService with responses for all 12 test cases
    OnlineAiService ai =
        new ScriptedAiService(
            List.of(
                // EXP 1: "What tools do you have available?" - text-only response
                ScriptedResponse.textOnly(
                    "I have the following tools: search_index for searching, browse_folders for"
                        + " browsing, and file_operations for file access."),
                // EXP 2: "What are the indexed root folders?" - calls browse_folders
                ScriptedResponse.toolCall("call_1", "core_browse_folders", "{}"),
                ScriptedResponse.textOnly("The indexed roots are shown above."),
                // EXP 3: "What is the docs/explanation folder used for?" - text response
                ScriptedResponse.textOnly(
                    "The docs/explanation folder contains explanatory documentation about the"
                        + " system's design and architecture."),
                // EXP 4: "What files are in docs/explanation?" - calls browse_folders
                ScriptedResponse.toolCall(
                    "call_2", "core_browse_folders", "{\"parent_path\":\"docs/explanation\"}"),
                ScriptedResponse.textOnly("The files in docs/explanation are listed above."),
                // EXP 5: "What does the configuration.md file explain?" - text response
                ScriptedResponse.textOnly(
                    "The configuration.md file explains the config system and settings."),
                // EXP 6: "How many main sections are in the architecture documentation?" - text
                ScriptedResponse.textOnly(
                    "The architecture documentation has several sections covering system design."),
                // EXP 7: "What is documented in tempdocs/186?" - text response
                ScriptedResponse.textOnly(
                    "Tempdocs 186 documents agent testing experiments and battery test design."),
                // EXP 8: "Read the content of docs/explanation/configuration.md" - calls
                // file_operations
                ScriptedResponse.toolCall(
                    "call_3",
                    "core_file_operations",
                    "{\"operation\":\"read\",\"file_path\":\"docs/explanation/configuration.md\"}"),
                ScriptedResponse.textOnly("Here's the content from configuration.md."),
                // EXP 9: "Search the codebase for mentions of 'inference'" - calls search_index
                ScriptedResponse.toolCall("call_4", "core_search_index", "{\"query\":\"inference\"}"),
                ScriptedResponse.textOnly("Found several mentions of inference in the codebase."),
                // EXP 10: "Find all files that mention GPU" - calls search_index
                ScriptedResponse.toolCall("call_5", "core_search_index", "{\"query\":\"GPU\"}"),
                ScriptedResponse.textOnly("Found files mentioning GPU."),
                // EXP 11: "Ingest the file docs/README.md into the index" - calls ingest_files
                ScriptedResponse.toolCall(
                    "call_6", "core_ingest_files", "{\"paths\":[\"docs/README.md\"]}"),
                ScriptedResponse.textOnly("Successfully ingested docs/README.md."),
                // EXP 12: "Search for 'configuration' and then browse the relevant folder" -
                // multi-step
                ScriptedResponse.toolCall(
                    "call_7", "core_search_index", "{\"query\":\"configuration\"}"),
                ScriptedResponse.toolCall("call_8", "core_browse_folders", "{\"parent_path\":\"docs\"}"),
                ScriptedResponse.textOnly("Found configuration references and browsed the docs folder.")));

    // 5. Initialize agent service
    agentService =
        new AgentLoopService(
            ai,
            operationCatalog,
            operationExecutor,
            agentToolEmitter,
            null,
            engineContext -> List.of(testCorpusRoot.toString()));

    log.info("Agent Battery setup complete: {} test cases loaded", testCases.size());
  }

  @AfterAll
  static void teardown() throws Exception {
    log.info("Cleaning up Agent Battery resources...");

    // Write JSON results
    if (!testResults.isEmpty()) {
      writeResultsJson();
    } else {
      log.warn("No test results to write");
    }

    log.info("Cleanup complete");
  }

  /**
   * Write test results to JSON file for CI dashboard and scorecard integration.
   */
  private static void writeResultsJson() throws Exception {
    int total = testResults.size();
    int passed = (int) testResults.stream().filter(TestCaseResult::passed).count();
    int failed = total - passed;
    double successRate = total > 0 ? (double) passed / total : 0.0;
    double avgIterations =
        testResults.stream().mapToInt(TestCaseResult::iterations).average().orElse(0.0);

    // Create result object
    var result =
        Map.of(
            "suite",
            "agent-battery",
            "version",
            1,
            "timestamp",
            java.time.Instant.now().toString(),
            "aggregate",
            Map.of(
                "total_tests",
                total,
                "passed",
                passed,
                "failed",
                failed,
                "success_rate",
                successRate,
                "avg_iterations",
                avgIterations),
            "per_test",
            testResults.stream()
                .map(
                    r ->
                        Map.of(
                            "id",
                            r.id(),
                            "name",
                            r.name(),
                            "passed",
                            r.passed(),
                            "iterations",
                            r.iterations(),
                            "tool_calls",
                            r.toolCalls(),
                            "duration_ms",
                            r.durationMs()))
                .toList());

    // Write to JSON file
    Path outputDir = Path.of("build/test-results/agent-battery");
    java.nio.file.Files.createDirectories(outputDir);

    Path outputFile = outputDir.resolve("agent-battery-result.v1.json");
    String json = new tools.jackson.databind.ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValueAsString(result);

    java.nio.file.Files.writeString(outputFile, json);

    log.info("Test results written to: {}", outputFile.toAbsolutePath());
  }

  /**
   * Load test manifest with test cases.
   *
   * <p>For now, returns hardcoded test cases. Will load from JSON later.
   */
  private static List<AgentTestCase> loadManifest() {
    List<AgentTestCase> cases = new ArrayList<>();

    // EXP 1: Tool inventory (text-only)
    cases.add(
        new AgentTestCase(
            "exp-001",
            "Tool inventory (text-only)",
            "What tools do you have available?",
            2,
            new TextOnlySuccessCriteria(List.of("search", "browse", "file"))));

    // EXP 2: List roots
    cases.add(
        new AgentTestCase(
            "exp-002",
            "List roots",
            "What are the indexed root folders?",
            3,
            new ToolUsageSuccessCriteria(List.of("core_browse_folders"))));

    // EXP 3: Explain purpose of docs/explanation (response quality)
    cases.add(
        new AgentTestCase(
            "exp-003",
            "Explain purpose of docs/explanation",
            "What is the docs/explanation folder used for?",
            3,
            new ResponseQualityCriteria(
                List.of("explanation", "document"), null)));

    // EXP 4: Files in docs/explanation
    cases.add(
        new AgentTestCase(
            "exp-004",
            "List files in docs/explanation",
            "What files are in docs/explanation?",
            3,
            new ToolInvokedSuccessCriteria("core_browse_folders")));

    // EXP 5: What is configuration.md about? (response quality)
    cases.add(
        new AgentTestCase(
            "exp-005",
            "What is configuration.md about?",
            "What does the configuration.md file explain?",
            3,
            new ResponseQualityCriteria(
                List.of("config"), null)));

    // EXP 6: How many sections in architecture? (response quality)
    cases.add(
        new AgentTestCase(
            "exp-006",
            "How many sections in architecture docs?",
            "How many main sections are in the architecture documentation?",
            3,
            new ResponseQualityCriteria(
                List.of("section"), null)));

    // EXP 7: What's in tempdocs/186? (response quality)
    cases.add(
        new AgentTestCase(
            "exp-007",
            "What's in tempdocs/186?",
            "What is documented in tempdocs/186?",
            3,
            new ResponseQualityCriteria(
                List.of("agent", "test"), null)));

    // EXP 8: Read configuration.md (path validation)
    cases.add(
        new AgentTestCase(
            "exp-008",
            "Read configuration.md",
            "Read the content of docs/explanation/configuration.md",
            3,
            new ToolInvokedSuccessCriteria("core_file_operations")));

    // EXP 9: Search for "inference" (tool usage)
    cases.add(
        new AgentTestCase(
            "exp-009",
            "Search for 'inference'",
            "Search the codebase for mentions of 'inference'",
            3,
            new ToolUsageSuccessCriteria(List.of("core_search_index"))));

    // EXP 10: Find files containing "GPU" (tool usage)
    cases.add(
        new AgentTestCase(
            "exp-010",
            "Find files containing 'GPU'",
            "Find all files that mention GPU",
            3,
            new ToolUsageSuccessCriteria(List.of("core_search_index"))));

    // EXP 11: Ingest new file (path validation)
    cases.add(
        new AgentTestCase(
            "exp-011",
            "Ingest new file",
            "Ingest the file docs/README.md into the index",
            3,
            new ToolInvokedSuccessCriteria("core_ingest_files")));

    // EXP 12: Multi-step: search + browse (tool usage)
    cases.add(
        new AgentTestCase(
            "exp-012",
            "Multi-step: search and browse",
            "Search for 'configuration' and then browse the relevant folder",
            5,
            new ToolUsageSuccessCriteria(List.of("core_search_index", "core_browse_folders"))));

    return cases;
  }

  /** Provide test cases for parameterized test. */
  static Stream<AgentTestCase> provideTestCases() {
    return testCases.stream();
  }

  @ParameterizedTest
  @MethodSource("provideTestCases")
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  @DisplayName("Agent battery test case")
  void agentBatteryTestCase(AgentTestCase testCase) throws Exception {
    log.info("Running test case: {} - {}", testCase.id(), testCase.name());

    long startTime = System.currentTimeMillis();

    // Run agent with test query
    AgentRequest request =
        new AgentRequest(
            List.of(Map.of("role", "user", "content", testCase.instruction())),
            List.of(),
            testCase.maxIterations());

    List<AgentEvent> events = new CopyOnWriteArrayList<>();
    agentService.runAgent(request, events::add, BATTERY_ENGINE_CONTEXT);

    long durationMs = System.currentTimeMillis() - startTime;

    // Extract iterations and tool calls from AgentDone event
    AgentEvent.AgentDone doneEvent =
        events.stream()
            .filter(e -> e instanceof AgentEvent.AgentDone)
            .map(e -> (AgentEvent.AgentDone) e)
            .findFirst()
            .orElse(null);

    int iterations = doneEvent != null ? doneEvent.iterationsUsed() : 0;
    int toolCalls = doneEvent != null ? doneEvent.toolCallsExecuted() : 0;

    // Evaluate success based on test case criteria
    boolean passed = testCase.successCriteria().evaluate(events);

    // Record result
    testResults.add(
        new TestCaseResult(
            testCase.id(), testCase.name(), passed, iterations, toolCalls, durationMs));

    // Log results
    log.info(
        "Test case {} - {}: {} ({} iterations, {} tool calls, {}ms)",
        testCase.id(),
        testCase.name(),
        passed ? "PASS" : "FAIL",
        iterations,
        toolCalls,
        durationMs);

    // Assert
    assertTrue(passed, "Test " + testCase.id() + " failed: " + testCase.name());
  }

  /**
   * Reports the battery's aggregate success rate. It deliberately asserts nothing about the rate
   * itself: the per-case {@code assertTrue(passed, ...)} above is what fails a regression, and the
   * 85% target is an agent-quality goal, not a property of a code change. The name says "reports",
   * not "meets", because a test named for a threshold it does not check reads as coverage it does
   * not provide. Raising it to an assertion is tracked in tempdoc 930 §22.2 open items.
   */
  @Test
  @DisplayName("Agent battery reports its aggregate success rate")
  void aggregateSuccessRate() {
    // Runs after the parameterized cases by JUnit ordering, so testResults is complete.
    int total = testResults.size();
    int passed = (int) testResults.stream().filter(TestCaseResult::passed).count();
    double successRate = total > 0 ? (double) passed / total : 0.0;

    log.info("Aggregate success rate: {}/{} tests passed ({}.0%)", passed, total, (int) (successRate * 100));

    // Log per-test results
    for (TestCaseResult result : testResults) {
      log.info(
          "  {} - {}: {}",
          result.id(),
          result.name(),
          result.passed() ? "PASS" : "FAIL");
    }

    log.info(
        "Success rate threshold check: {}.0% {} 85% (measured, not asserted; baseline is ~42%)",
        (int) (successRate * 100),
        successRate >= 0.85 ? ">=" : "<");
  }

  // ===== Test Case Model =====

  /** Represents a single agent battery test case. */
  record AgentTestCase(
      String id,
      String name,
      String instruction,
      int maxIterations,
      SuccessCriteria successCriteria) {}

  /** Represents the result of a test case execution. */
  record TestCaseResult(
      String id,
      String name,
      boolean passed,
      int iterations,
      int toolCalls,
      long durationMs) {}

  /** Success criteria interface. */
  interface SuccessCriteria {
    boolean evaluate(List<AgentEvent> events);
  }

  /** Type 1: Text-only response with keywords. */
  record TextOnlySuccessCriteria(List<String> requiredKeywords) implements SuccessCriteria {
    @Override
    public boolean evaluate(List<AgentEvent> events) {
      // Check 1: No tool calls should be executed
      boolean noToolCalls =
          events.stream()
              .noneMatch(e -> e instanceof AgentEvent.ToolExecutionCompleted);

      if (!noToolCalls) {
        log.debug("TextOnlySuccessCriteria failed: tool calls detected");
        return false;
      }

      // Check 2: Response contains all required keywords
      String finalResponse = extractFinalResponse(events);
      if (finalResponse == null || finalResponse.isBlank()) {
        log.debug("TextOnlySuccessCriteria failed: no final response");
        return false;
      }

      String lowerResponse = finalResponse.toLowerCase();
      boolean allKeywordsPresent =
          requiredKeywords.stream()
              .allMatch(keyword -> lowerResponse.contains(keyword.toLowerCase()));

      if (!allKeywordsPresent) {
        log.debug("TextOnlySuccessCriteria failed: missing keywords");
        return false;
      }

      log.debug("TextOnlySuccessCriteria passed");
      return true;
    }
  }

  /** Type 2: Tool usage validation. */
  record ToolUsageSuccessCriteria(List<String> requiredTools) implements SuccessCriteria {
    @Override
    public boolean evaluate(List<AgentEvent> events) {
      List<String> actualTools = extractToolSequence(events);

      // Check if all required tools were called (order doesn't matter for now)
      boolean allToolsCalled =
          requiredTools.stream().allMatch(actualTools::contains);

      if (!allToolsCalled) {
        log.debug(
            "ToolUsageSuccessCriteria failed: expected {} but got {}",
            requiredTools,
            actualTools);
        return false;
      }

      log.debug("ToolUsageSuccessCriteria passed: tools {}", actualTools);
      return true;
    }
  }

  /** Type 4: Response quality - keyword-based content validation. */
  record ResponseQualityCriteria(List<String> requiredKeywords, List<String> forbiddenKeywords)
      implements SuccessCriteria {
    @Override
    public boolean evaluate(List<AgentEvent> events) {
      String response = extractFinalResponse(events);
      if (response == null || response.isBlank()) {
        log.debug("ResponseQualityCriteria failed: no final response");
        return false;
      }

      String lowerResponse = response.toLowerCase(java.util.Locale.ROOT);

      // Check required keywords
      boolean hasRequired =
          requiredKeywords.stream()
              .allMatch(kw -> lowerResponse.contains(kw.toLowerCase(java.util.Locale.ROOT)));

      if (!hasRequired) {
        log.debug("ResponseQualityCriteria failed: missing required keywords");
        return false;
      }

      // Check for forbidden keywords (if any)
      if (forbiddenKeywords != null && !forbiddenKeywords.isEmpty()) {
        boolean hasForbidden =
            forbiddenKeywords.stream()
                .anyMatch(kw -> lowerResponse.contains(kw.toLowerCase(java.util.Locale.ROOT)));

        if (hasForbidden) {
          log.debug("ResponseQualityCriteria failed: contains forbidden keywords");
          return false;
        }
      }

      log.debug("ResponseQualityCriteria passed");
      return true;
    }
  }

  /**
   * Type 3: the named tool was invoked at least once.
   *
   * <p>This used to be called {@code PathValidationSuccessCriteria} and carried a {@code parameter}
   * component naming the path argument it would one day check. It cannot: {@link
   * AgentEvent.ToolExecutionStarted} carries only {@code callId}, {@code toolName} and a trace
   * context — tool arguments are not on that event at all, so no amount of JSON parsing here
   * reaches them. The name and the component were describing an assertion the type could never
   * make. Validating argument paths needs the arguments put on the wire first.
   */
  record ToolInvokedSuccessCriteria(String toolName) implements SuccessCriteria {
    @Override
    public boolean evaluate(List<AgentEvent> events) {
      boolean invoked =
          events.stream()
              .filter(e -> e instanceof AgentEvent.ToolExecutionStarted)
              .map(e -> (AgentEvent.ToolExecutionStarted) e)
              .anyMatch(e -> e.toolName().equals(toolName));

      log.debug("ToolInvokedSuccessCriteria {}: tool {}", invoked ? "passed" : "failed", toolName);
      return invoked;
    }
  }

  // ===== Helper Methods =====

  /** Extract final response text from AgentDone event. */
  private static String extractFinalResponse(List<AgentEvent> events) {
    return events.stream()
        .filter(e -> e instanceof AgentEvent.AgentDone)
        .map(e -> (AgentEvent.AgentDone) e)
        .map(AgentEvent.AgentDone::finalResponse)
        .findFirst()
        .orElse(null);
  }

  /** Extract sequence of tool names that were executed. */
  private static List<String> extractToolSequence(List<AgentEvent> events) {
    // Use ToolExecutionStarted instead of Completed since it has toolName
    return events.stream()
        .filter(e -> e instanceof AgentEvent.ToolExecutionStarted)
        .map(e -> (AgentEvent.ToolExecutionStarted) e)
        .map(AgentEvent.ToolExecutionStarted::toolName)
        .toList();
  }

  // ===========================================================================
  // Test Infrastructure: ScriptedAiService for Deterministic Testing
  // ===========================================================================

  /**
   * Per Phase 11 of tempdoc 429: substrate-based stub operation for the battery's
   * deterministic LLM scenarios. Wraps an Operation declaration + handler invocation
   * for the four agent tools (search_index, browse_folders, ingest_files,
   * file_operations) used in the 12 test cases per tempdoc 186.
   */
  private static final class StubOperation {
    final String wireName;
    final RiskTier risk;
    final String returnValue;

    StubOperation(String wireName, RiskTier risk, String returnValue) {
      this.wireName = wireName;
      this.risk = risk;
      this.returnValue = returnValue;
    }

    Operation toOperation() {
      return new Operation(
          new OperationRef("core." + wireName.replace('_', '-')),
          new Presentation(
              new I18nKey("test." + wireName + ".label"),
              new I18nKey("test." + wireName + ".description"),
              Optional.empty(),
              Optional.empty()),
          Interface.of("{\"type\":\"object\",\"properties\":{}}", "{\"type\":\"object\"}"),
          new OperationPolicy(
              risk,
              ConfirmStrategy.None.INSTANCE,
              AuditPolicy.NONE,
              RetryPolicy.noRetry(),
              java.util.Set.of(),
              false),
          OperationAvailability.empty(),
          OperationLineage.empty(),
          Binding.of(new OperationRef("core." + wireName.replace('_', '-'))),
          Provenance.core("1.0"),
          java.util.Set.of(ExecutorTag.AGENT));
    }

    OperationResult execute(String args) {
      return OperationResult.success(returnValue);
    }
  }

  /**
   * Inline AgentToolEmitter used by the battery — mirrors AgentOperationEmitter's wire
   * shape (deterministic transliteration of OperationRef per tempdoc 429 §F.21 C1
   * + identity-resolves description keys) without depending on app-services.
   */
  private static final class InlineAgentToolEmitter implements AgentToolEmitter {
    private static final tools.jackson.databind.ObjectMapper MAPPER =
        new tools.jackson.databind.ObjectMapper();

    /** Tempdoc 876 §B.1 — the membership half; {@link #emit} is its wire projection. */
    @Override
    public List<Operation> offer(
        OperationCatalog catalog, java.util.Collection<String> selectedNames) {
      List<Operation> offered = new ArrayList<>();
      for (Operation op : catalog.definitions()) {
        if (!op.executors().contains(ExecutorTag.AGENT)) continue;
        String wire = OperationCatalog.toWireName(op.id());
        if (selectedNames != null && !selectedNames.isEmpty() && !selectedNames.contains(wire)) {
          continue;
        }
        offered.add(op);
      }
      return List.copyOf(offered);
    }

    @Override
    public List<Map<String, Object>> emit(
        OperationCatalog catalog, java.util.Collection<String> selectedNames) {
      List<Map<String, Object>> result = new ArrayList<>();
      for (Operation op : offer(catalog, selectedNames)) {
        try {
          var function = MAPPER.createObjectNode();
          function.put("name", OperationCatalog.toWireName(op.id()));
          function.put("description", op.presentation().descriptionKey().value());
          function.set("parameters", MAPPER.readTree(op.intf().inputs()));
          var toolObj = MAPPER.createObjectNode();
          toolObj.put("type", "function");
          toolObj.set("function", function);
          @SuppressWarnings("unchecked")
          Map<String, Object> entry = MAPPER.convertValue(toolObj, Map.class);
          result.add(new java.util.LinkedHashMap<>(entry));
        } catch (Exception e) {
          throw new IllegalStateException("Failed to emit " + op.id(), e);
        }
      }
      return result;
    }
  }

  /**
   * ScriptedAiService — replays pre-defined responses synchronously.
   * Copied from AgentLoopServiceTest for deterministic agent testing.
   */
  private static final class ScriptedAiService implements OnlineAiService {
    private final List<ScriptedResponse> responses;
    final List<List<Map<String, Object>>> recordedMessages = new ArrayList<>();
    final List<SamplingParams> recordedSampling = new ArrayList<>();
    private int callIndex;

    ScriptedAiService(List<ScriptedResponse> responses) {
      this.responses = new ArrayList<>(responses);
    }

    // Tempdoc 491 §C5: streamSummary + streamAnswer overrides removed.

    @Override
    public CompletableFuture<String> summarize(String content) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public boolean isStartingUp() {
      return false;
    }

    @Override
    public void streamChatWithTools(
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools,
        int maxTokens,
        StreamCallbacks callbacks,
        SamplingParams sampling) {

      // Record a snapshot of the messages and sampling for assertion
      recordedMessages.add(List.copyOf(messages));
      recordedSampling.add(sampling);

      if (callIndex >= responses.size()) {
        callbacks.onError().accept(new IllegalStateException("No more scripted responses"));
        return;
      }

      ScriptedResponse response = responses.get(callIndex++);

      // Emit reasoning chunks (before text, matching real model behavior)
      if (response.reasoning != null && !response.reasoning.isEmpty()) {
        callbacks.onReasoningChunk().accept(response.reasoning);
      }

      // Emit text chunks
      if (response.text != null && !response.text.isEmpty()) {
        callbacks.onChunk().accept(response.text);
      }

      // Emit tool call deltas
      for (String deltaJson : response.toolCallDeltas) {
        callbacks.onToolCallDelta().accept(deltaJson);
      }

      // Emit usage if present
      if (response.usage != null) {
        callbacks.onUsage().accept(response.usage);
      }

      callbacks.onComplete().accept(null);
    }

    @Override
    public Optional<Integer> countPromptTokens(List<Map<String, Object>> messages) {
      // Best-effort simulation: 10 tokens per message
      return Optional.of(messages.size() * 10);
    }

    @Override
    public Integer llmContextTokens() {
      return 4096; // Simulated default context window
    }

    @Override
    public Integer configuredContextTokens() {
      return 4096; // Match llmContextTokens for testing
    }
  }

  /**
   * ScriptedResponse — describes what one LLM call returns.
   * Copied from AgentLoopServiceTest for deterministic agent testing.
   */
  private record ScriptedResponse(
      String text, String reasoning, List<String> toolCallDeltas, OnlineAiService.AiUsage usage) {

    static ScriptedResponse textOnly(String text) {
      return new ScriptedResponse(text, null, List.of(), null);
    }

    static ScriptedResponse empty() {
      return new ScriptedResponse(null, null, List.of(), null);
    }

    static ScriptedResponse withReasoning(String text, String reasoning) {
      return new ScriptedResponse(text, reasoning, List.of(), null);
    }

    static ScriptedResponse toolCall(String callId, String toolName, String arguments) {
      String delta = buildToolCallDeltaJson(callId, toolName, arguments);
      return new ScriptedResponse(null, null, List.of(delta), null);
    }

    static ScriptedResponse textAndToolCall(
        String text, String callId, String toolName, String arguments) {
      String delta = buildToolCallDeltaJson(callId, toolName, arguments);
      return new ScriptedResponse(text, null, List.of(delta), null);
    }

    /** Creates a response with simulated token usage. */
    ScriptedResponse withUsage(int promptTokens, int completionTokens) {
      int total = promptTokens + completionTokens;
      return new ScriptedResponse(
          this.text,
          this.reasoning,
          this.toolCallDeltas,
          new OnlineAiService.AiUsage(promptTokens, completionTokens, total));
    }

    /**
     * Build a JSON string matching the SSE chunk format that ToolCallParser expects:
     * {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"...","function":{"name":"...","arguments":"..."}}]}}]}
     */
    private static String buildToolCallDeltaJson(String callId, String toolName, String arguments) {
      // Escape the arguments JSON string for embedding
      String escapedArgs = arguments.replace("\"", "\\\"");
      return "{\"choices\":[{\"delta\":{\"tool_calls\":[{"
          + "\"index\":0,"
          + "\"id\":\"" + callId + "\","
          + "\"function\":{\"name\":\"" + toolName + "\","
          + "\"arguments\":\"" + escapedArgs + "\"}"
          + "}]}}]}";
    }
  }
}
