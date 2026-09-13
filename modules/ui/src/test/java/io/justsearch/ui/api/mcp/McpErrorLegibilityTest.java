/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ui.api.TestRequestContexts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.WorkerServices;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.ui.api.KnowledgeSearchController;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 725 (design #2, increment W3) — pins the error-result legibility grammar across {@link
 * McpToolSurface}: the 3 "knowledge server not available" sites (answer/search/status) and the 5
 * generic {@code catch (Exception e)} sites (answer/search/status/operation
 * dispatch/runtime_manifest) both state the condition descriptively and point at {@code
 * justsearch_status} as a remedy, never as an imperative instruction to act now.
 *
 * <p>The runtime_manifest catch site is verified by code reading, not a live test here: its
 * exception path requires a Jackson serialization failure on the (real, final) {@code
 * RuntimeManifest} record, which the other 4 sites' straightforward mock-throws don't need —
 * confirmed instead that {@code log.warn} was added and the message uses {@link
 * McpToolSurface}'s shared {@code toolFailureContent} helper (same as the other 4 sites).
 */
@DisplayName("McpToolSurface: error-result legibility (tempdoc 725 W3)")
final class McpErrorLegibilityTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneId.of("UTC"));

  private static final String STATUS_POINTER = "justsearch_status tool";

  private static String textOf(Map<String, Object> result) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    return (String) content.get(0).get("text");
  }

  private static McpToolSurface surfaceWithNoBackend() {
    return new McpToolSurface(
        List.of(OperationCatalog.of("core", List.of())),
        mock(OperationDispatcher.class),
        () -> null,
        () -> null,
        FIXED_CLOCK);
  }

  // ---------------------------------------------------------------------
  // "Knowledge server not available" — answer / search / status
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("answer: unavailable knowledge server states the condition + status-tool pointer")
  void answerUnavailableMessage() {
    Map<String, Object> result =
        surfaceWithNoBackend().callTool("justsearch_answer", Map.of("query", "q"), "s1", TestRequestContexts.mcp("s1"));

    assertEquals(Boolean.TRUE, result.get("isError"));
    String text = textOf(result);
    assertTrue(
        text.contains("Knowledge server is not available (worker offline or still starting)."),
        text);
    assertTrue(text.contains(STATUS_POINTER), text);
    assertNoImperativeCallInstruction(text);
  }

  @Test
  @DisplayName("search: unavailable knowledge server states the condition + status-tool pointer")
  void searchUnavailableMessage() {
    Map<String, Object> result =
        surfaceWithNoBackend().callTool("justsearch_search", Map.of("query", "q"), "s1", TestRequestContexts.mcp("s1"));

    assertEquals(Boolean.TRUE, result.get("isError"));
    String text = textOf(result);
    assertTrue(
        text.contains("Knowledge server is not available (worker offline or still starting)."),
        text);
    assertTrue(text.contains(STATUS_POINTER), text);
    assertNoImperativeCallInstruction(text);
  }

  @Test
  @DisplayName("status: unavailable knowledge server states the condition + status-tool pointer")
  void statusUnavailableMessage() {
    Map<String, Object> result =
        surfaceWithNoBackend().callTool("justsearch_status", Map.of(), "s1", TestRequestContexts.mcp("s1"));

    assertEquals(Boolean.TRUE, result.get("isError"));
    String text = textOf(result);
    assertTrue(
        text.contains("Knowledge server is not available (worker offline or still starting)."),
        text);
    assertTrue(text.contains(STATUS_POINTER), text);
    assertNoImperativeCallInstruction(text);
  }

  // ---------------------------------------------------------------------
  // Generic catch (Exception e) sites: uniform "<tool> failed: <class>: <message>. ..." grammar
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("answer: generic failure states tool name, exception class/message, status pointer")
  void answerGenericFailureMessage() {
    DocumentService documents = mock(DocumentService.class);
    when(documents.retrieveContext(any(), any(EngineContext.class))).thenThrow(new IllegalStateException("boom"));
    WorkerServices workers = new WorkerServices(null, documents, null, null, null);
    HeadAssembly facade = mock(HeadAssembly.class);
    when(facade.workers()).thenReturn(workers);
    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> null,
            () -> facade,
            FIXED_CLOCK);

    Map<String, Object> result = surface.callTool("justsearch_answer", Map.of("query", "q"), "s1", TestRequestContexts.mcp("s1"));

    assertEquals(Boolean.TRUE, result.get("isError"));
    String text = textOf(result);
    assertTrue(text.startsWith("Answer failed: IllegalStateException: boom."), text);
    assertTrue(text.contains(STATUS_POINTER), text);
    assertNoImperativeCallInstruction(text);
  }

  @Test
  @DisplayName("search: generic failure states tool name, exception class/message, status pointer")
  void searchGenericFailureMessage() {
    KnowledgeHttpApiAdapter adapter = mock(KnowledgeHttpApiAdapter.class);
    when(adapter.search(any(), any(EngineContext.class))).thenThrow(new IllegalStateException("boom"));
    KnowledgeSearchController ctrl = mock(KnowledgeSearchController.class);
    when(ctrl.getAdapter()).thenReturn(adapter);
    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> ctrl,
            () -> null,
            FIXED_CLOCK);

    Map<String, Object> result = surface.callTool("justsearch_search", Map.of("query", "q"), "s1", TestRequestContexts.mcp("s1"));

    assertEquals(Boolean.TRUE, result.get("isError"));
    String text = textOf(result);
    assertTrue(text.startsWith("Search failed: IllegalStateException: boom."), text);
    assertTrue(text.contains(STATUS_POINTER), text);
    assertNoImperativeCallInstruction(text);
  }

  @Test
  @DisplayName("status: generic failure states tool name, exception class/message, status pointer")
  void statusGenericFailureMessage() {
    KnowledgeHttpApiAdapter adapter = mock(KnowledgeHttpApiAdapter.class);
    when(adapter.status(any(EngineContext.class))).thenThrow(new IllegalStateException("boom"));
    KnowledgeSearchController ctrl = mock(KnowledgeSearchController.class);
    when(ctrl.getAdapter()).thenReturn(adapter);
    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> ctrl,
            () -> null,
            FIXED_CLOCK);

    Map<String, Object> result = surface.callTool("justsearch_status", Map.of(), "s1", TestRequestContexts.mcp("s1"));

    assertEquals(Boolean.TRUE, result.get("isError"));
    String text = textOf(result);
    assertTrue(text.startsWith("Status failed: IllegalStateException: boom."), text);
    assertTrue(text.contains(STATUS_POINTER), text);
    assertNoImperativeCallInstruction(text);
  }

  @Test
  @DisplayName(
      "operation dispatch: generic failure states tool name, exception class/message, status"
          + " pointer")
  void operationDispatchGenericFailureMessage() {
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    when(dispatcher.dispatch(any(), any(), any(), any())).thenThrow(new IllegalStateException("boom"));
    McpToolSurface surface =
        new McpToolSurface(
            List.of(new AgentToolsOperationCatalog()),
            dispatcher,
            () -> null,
            () -> null,
            FIXED_CLOCK);

    Map<String, Object> result =
        surface.callTool("justsearch_ingest", Map.of("paths", List.of("C:/tmp/notes")), "s1", TestRequestContexts.mcp("s1"));

    assertEquals(Boolean.TRUE, result.get("isError"));
    String text = textOf(result);
    assertTrue(
        text.startsWith("Operation core.ingest-files failed: IllegalStateException: boom."),
        text);
    assertTrue(text.contains(STATUS_POINTER), text);
    assertNoImperativeCallInstruction(text);
  }

  private static java.util.stream.Stream<Arguments> classifiedFailures() {
    return java.util.stream.Stream.of(
        Arguments.of(new UnsupportedOperationException("unsupported operation"), ApiErrorCode.NOT_SUPPORTED),
        Arguments.of(new IllegalArgumentException("invalid query"), ApiErrorCode.INVALID_REQUEST),
        // Lane F review B1: these two cases used to construct io.grpc.StatusRuntimeException. They
        // were the transport's vocabulary, and item A6 stopped the client from speaking it — the
        // MCP surface can no longer be handed one of these by anything, so a test that keeps
        // producing them is asserting a classification of an exception that cannot arrive. The
        // port's own failure type carries the same statuses, and it is what reaches this surface
        // now, so the two rows move onto it rather than being deleted: the property is that a
        // worker failure keeps its API policy through the MCP serialization, which is unchanged.
        Arguments.of(
            new KnowledgeClientException(
                KnowledgeClientException.Status.UNAVAILABLE, "worker restarting"),
            ApiErrorCode.SERVICE_UNAVAILABLE),
        Arguments.of(
            new KnowledgeClientException(KnowledgeClientException.Status.DEADLINE_EXCEEDED, "slow"),
            ApiErrorCode.TIMEOUT),
        Arguments.of(new RuntimeException("unclassified exception"), ApiErrorCode.INTERNAL_ERROR));
  }

  @ParameterizedTest
  @MethodSource("classifiedFailures")
  @DisplayName("search: typed failures retain the API policy in both serialized delivery tiers")
  void classifiedSearchFailure(Exception failure, ApiErrorCode expectedCode) {
    KnowledgeHttpApiAdapter adapter = mock(KnowledgeHttpApiAdapter.class);
    when(adapter.search(any(), any(EngineContext.class))).thenThrow(failure);
    KnowledgeSearchController ctrl = mock(KnowledgeSearchController.class);
    when(ctrl.getAdapter()).thenReturn(adapter);
    McpToolSurface surface = new McpToolSurface(
        List.of(OperationCatalog.of("core", List.of())), mock(OperationDispatcher.class),
        () -> ctrl, () -> null, FIXED_CLOCK);

    var result = surface.callTool("justsearch_search", Map.of("query", "q"), "s1", TestRequestContexts.mcp("s1"));
    assertFailureFacts(result, expectedCode);
    assertFalse(textOf(result).contains("may be transient"));
  }

  @ParameterizedTest
  @MethodSource("classifiedFailures")
  @DisplayName("answer: failed futures preserve the underlying API policy and explanation")
  void classifiedAnswerFutureFailure(Exception failure, ApiErrorCode expectedCode) {
    for (Exception asyncFailure : List.of(failure,
        new java.util.concurrent.CompletionException(
            new java.util.concurrent.ExecutionException(failure)))) {
      DocumentService documents = mock(DocumentService.class);
      when(documents.retrieveContext(any(), any(EngineContext.class)))
          .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(asyncFailure));
      HeadAssembly facade = mock(HeadAssembly.class);
      when(facade.workers()).thenReturn(new WorkerServices(null, documents, null, null, null));
      McpToolSurface surface = new McpToolSurface(
          List.of(OperationCatalog.of("core", List.of())), mock(OperationDispatcher.class),
          () -> null, () -> facade, FIXED_CLOCK);

      var result = surface.callTool("justsearch_answer", Map.of("query", "q"), "s1", TestRequestContexts.mcp("s1"));
      assertFailureFacts(result, expectedCode);
      assertTrue(textOf(result).startsWith("Answer failed: " + failure.getClass().getSimpleName() + ":"));
      assertFalse(textOf(result).contains("ExecutionException"));
      assertFalse(textOf(result).contains("CompletionException"));
    }
  }

  @Test
  void exceptionDetailsAreSanitizedInBothTiers() {
    KnowledgeHttpApiAdapter adapter = mock(KnowledgeHttpApiAdapter.class);
    when(adapter.status(any(EngineContext.class))).thenThrow(new IllegalStateException("Cannot read C:\\private\\notes.txt"));
    KnowledgeSearchController ctrl = mock(KnowledgeSearchController.class);
    when(ctrl.getAdapter()).thenReturn(adapter);
    McpToolSurface surface = new McpToolSurface(
        List.of(OperationCatalog.of("core", List.of())), mock(OperationDispatcher.class),
        () -> ctrl, () -> null, FIXED_CLOCK);

    var result = surface.callTool("justsearch_status", Map.of(), "s1", TestRequestContexts.mcp("s1"));
    String wire = JsonMapper.builder().build().writeValueAsString(result);
    assertFalse(wire.contains("private"), wire);
    assertTrue(wire.contains("[path]"), wire);
    assertFailureFacts(result, ApiErrorCode.INVALID_STATE);
  }

  @Test
  void boundaryValidationHasKnownNonRetryableFacts() {
    var result = surfaceWithNoBackend().callTool(
        "justsearch_search", Map.of("query", 42), "s1", TestRequestContexts.mcp("s1"));
    assertTrue(textOf(result).contains("Invalid arguments"));
    assertFailureFacts(result, ApiErrorCode.INVALID_REQUEST);
  }

  @Test
  void unavailableBackendHasExistingTransientClassification() {
    var result = surfaceWithNoBackend().callTool("justsearch_status", Map.of(), "s1", TestRequestContexts.mcp("s1"));
    assertFailureFacts(result, ApiErrorCode.SERVICE_UNAVAILABLE);
  }

  @Test
  void unclassifiedOperationResultDoesNotInventRetryability() {
    var result = operationFailure(OperationResult.failure("Ingestion could not finish"));
    var structured = structuredOf(result);
    assertEquals(Map.of("error", "Ingestion could not finish"), structured);
    assertEquals("Ingestion could not finish", textOf(result));
    assertEquals(Boolean.TRUE, result.get("isError"));
  }

  @Test
  void operationResultPreservesOptionalFactsWithoutClassifyingHandlerCode() {
    var result = operationFailure(OperationResult.failure(
        "Blocked by library policy", "HANDLER_POLICY", Map.of(), false));
    var structured = structuredOf(result);
    assertEquals("HANDLER_POLICY", structured.get("errorCode"));
    assertEquals(Boolean.FALSE, structured.get("retryable"));
    assertFalse(structured.containsKey("errorClass"));
    assertTrue(textOf(result).contains("Error code: HANDLER_POLICY"));
    assertTrue(textOf(result).contains("Retryable: false"));
  }

  @Test
  void unknownToolRemainsUnclassified() {
    var result = surfaceWithNoBackend().callTool("unrecognized", Map.of(), "s1", TestRequestContexts.mcp("s1"));
    var structured = structuredOf(result);
    assertEquals(1, structured.size());
    assertEquals(structured.get("error"), textOf(result));
    assertEquals(Boolean.TRUE, result.get("isError"));
  }

  private static Map<String, Object> operationFailure(OperationResult failure) {
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    when(dispatcher.dispatch(any(), any(), any(), any())).thenReturn(failure);
    McpToolSurface surface = new McpToolSurface(
        List.of(new AgentToolsOperationCatalog()), dispatcher, () -> null, () -> null, FIXED_CLOCK);
    return surface.callTool("justsearch_ingest", Map.of("paths", List.of("C:/tmp/notes")), "s1", TestRequestContexts.mcp("s1"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> structuredOf(Map<String, Object> result) {
    // Read what a client receives, including actual JSON booleans, rather than only the Java map.
    var mapper = JsonMapper.builder().build();
    Map<String, Object> wire = mapper.readValue(mapper.writeValueAsString(result), Map.class);
    return (Map<String, Object>) wire.get("structuredContent");
  }

  private static void assertFailureFacts(Map<String, Object> result, ApiErrorCode code) {
    var structured = structuredOf(result);
    assertEquals(Boolean.TRUE, result.get("isError"));
    assertEquals(code.name(), structured.get("errorCode"));
    assertEquals(code.errorClass().name(), structured.get("errorClass"));
    assertEquals(code.isRetryable(), structured.get("retryable"));
    String text = textOf(result);
    assertTrue(text.startsWith((String) structured.get("error")), text);
    assertTrue(text.contains("Error code: " + code.name()), text);
    assertTrue(text.contains("Error class: " + code.errorClass().name()), text);
    assertTrue(text.contains("Retryable: " + code.isRetryable()), text);
    assertNoImperativeCallInstruction(text);
  }

  /**
   * Grammar rule (tempdoc 725): error results state facts descriptively; the status-tool pointer
   * is remedy phrasing ("state is available via ..." / "is reported by ..."), never an imperative
   * ("call justsearch_status now").
   */
  private static void assertNoImperativeCallInstruction(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    assertFalse(lower.contains("now call"), "must not issue an imperative instruction: " + text);
    assertFalse(lower.contains("call the justsearch_status"), "must not issue an imperative instruction: " + text);
  }
}
