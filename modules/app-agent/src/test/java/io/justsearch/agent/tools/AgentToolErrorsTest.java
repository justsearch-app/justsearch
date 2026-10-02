/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.EngineContextTestFixtures;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 877 §2.6 — the error classifier's contract.
 *
 * <p>The assertions that matter are the DISTINCTIONS, not the happy path: a model's bad JSON and an
 * unreachable Worker used to produce the same untyped string with the same ERROR-level stack trace,
 * and a consumer could not tell a model mistake from a system fault. Each test below pins one of
 * those distinctions.
 */
class AgentToolErrorsTest {

  @TempDir Path tempDir;

  private static ApiErrorCode codeOf(OperationResult r) {
    return ApiErrorCode.valueOf(r.errorCode().orElseThrow(() -> new AssertionError("no errorCode")));
  }

  @Test
  @DisplayName("malformed model JSON is BAD_REQUEST and not retryable")
  void malformedJsonIsBadRequest() {
    Exception jackson = null;
    try {
      ToolArgs.parse("{\"broken\"");
    } catch (Exception e) {
      jackson = e;
    }
    assertTrue(jackson != null, "parse must throw on malformed JSON");
    OperationResult r = AgentToolErrors.classify("core_search_index", "Search error", jackson);
    assertFalse(r.success());
    assertEquals(ApiErrorCode.BAD_REQUEST, codeOf(r));
    assertEquals(Boolean.FALSE, r.retryable().orElseThrow(), "the model must not retry the same JSON");
  }

  @Test
  @DisplayName("a bad ARGUMENT value is BAD_REQUEST too — same class, different cause")
  void badArgumentIsBadRequest() {
    OperationResult r =
        AgentToolErrors.classify(
            "core_read_document", "Read error", new ToolArgs.BadArgument("\"offset_chars\" must be a number"));
    assertEquals(ApiErrorCode.BAD_REQUEST, codeOf(r));
    assertTrue(
        r.message().contains("offset_chars"),
        "the model-facing message keeps the field name: " + r.message());
  }

  @Test
  @DisplayName("a fetch timeout is TIMEOUT and RETRYABLE — the distinction that was missing")
  void timeoutIsRetryable() {
    OperationResult r =
        AgentToolErrors.classify("core_search_index", "Search error", new TimeoutException("15000 ms"));
    assertEquals(ApiErrorCode.TIMEOUT, codeOf(r));
    assertEquals(Boolean.TRUE, r.retryable().orElseThrow());
    assertTrue(ApiErrorCode.TIMEOUT.isRetryable(), "retryable is read off the shared classification");
  }

  @Test
  @DisplayName("an unreachable Worker is SERVICE_UNAVAILABLE, matched by class name across modules")
  void workerUnreachableIsServiceUnavailable() {
    // The REAL type the port throws. This test used to declare a local class NAMED
    // StatusRuntimeException to satisfy the classifier's string match — so it kept passing after
    // item A6 deleted the transport, while production classified every unreachable index half as
    // INTERNAL_ERROR. A test that constructs its own subject to match a string is not testing the
    // production path; it is testing the string.
    OperationResult r =
        AgentToolErrors.classify(
            "core_browse_folders",
            "Browse error",
            new KnowledgeClientException(
                KnowledgeClientException.Status.UNAVAILABLE, "index half not up"));
    assertEquals(ApiErrorCode.SERVICE_UNAVAILABLE, codeOf(r));
    assertEquals(Boolean.TRUE, r.retryable().orElseThrow());
  }

  @Test
  @DisplayName("a deadline from the port is also retryable-unavailable, not an internal error")
  void deadlineFromThePortIsServiceUnavailable() {
    OperationResult r =
        AgentToolErrors.classify(
            "core_browse_folders",
            "Browse error",
            new KnowledgeClientException(
                KnowledgeClientException.Status.DEADLINE_EXCEEDED, "too slow"));
    assertEquals(ApiErrorCode.SERVICE_UNAVAILABLE, codeOf(r));
  }

  @Test
  @DisplayName("a port failure that is NOT an outage stays an internal error")
  void nonOutagePortFailureIsNotServiceUnavailable() {
    // The negative half: without it, widening the arm to "any KnowledgeClientException" would pass.
    OperationResult r =
        AgentToolErrors.classify(
            "core_browse_folders",
            "Browse error",
            new KnowledgeClientException(KnowledgeClientException.Status.INTERNAL, "boom"));
    assertEquals(ApiErrorCode.INTERNAL_ERROR, codeOf(r));
  }

  @Test
  @DisplayName("an unreachable Worker gets the same actionable sentence, not a transport dump")
  void workerUnreachableMessageIsActionable() {
    OperationResult r =
        AgentToolErrors.classify(
            "core_browse_folders",
            "Browse error",
            new KnowledgeClientException(
                KnowledgeClientException.Status.UNAVAILABLE, "UNAVAILABLE: io exception"));

    assertFalse(r.message().contains("UNAVAILABLE: io exception"), r.message());
    assertTrue(r.message().contains("retry shortly"), r.message());
  }

  @Test
  // Lane F item A10 deleted the signal-bus reconnect arm together with its only thrower
  // (RemoteKnowledgeClient.reconnect). This test used to pin that the arm was narrow; it now pins
  // the stronger fact that no IllegalStateException is special-cased at all.
  @DisplayName("an IllegalStateException is INTERNAL_ERROR — no message-matching arm survives")
  void unrelatedIllegalStateStaysInternal() {
    OperationResult r =
        AgentToolErrors.classify(
            "core_ingest_files", "Ingest error", new IllegalStateException("port already bound"));
    assertEquals(ApiErrorCode.INTERNAL_ERROR, codeOf(r));
    assertEquals("Ingest error: port already bound", r.message());
  }

  @Test
  @DisplayName("direct and future-wrapped admission refusals retain their identity for the transport")
  void admissionRefusalsEscapeClassification() {
    for (var reason : EngineAdmissionException.Reason.values()) {
      var refusal = new EngineAdmissionException(reason, 7);
      for (Throwable failure : new Throwable[] {
          refusal,
          new CompletionException(refusal),
          new ExecutionException(refusal),
          new CompletionException(new ExecutionException(refusal)),
          new ExecutionException(new CompletionException(refusal))
      }) {
        assertSame(refusal, assertThrows(EngineAdmissionException.class,
            () -> AgentToolErrors.classify("core_browse_folders", "Browse error", failure)));
      }
    }
  }

  @Test
  void siblingHandlersPreserveAdmissionRefusals() {
    var refusal = new EngineAdmissionException(EngineAdmissionException.Reason.ENGINE_LIMIT, 7);
    String path = Path.of(".").toAbsolutePath().normalize().resolve("q13-admission")
        .toString().replace("\\", "\\\\");
    for (RuntimeException failure : new RuntimeException[] {
        refusal, new CompletionException(new ExecutionException(refusal))
    }) {
      var search = new SearchTool((request, context) -> { throw failure; });
      assertSame(refusal, assertThrows(EngineAdmissionException.class,
          () -> search.execute("{\"query\":\"test\"}", EngineContextTestFixtures.AGENT_LOOP)));

      var read = new ReadDocumentTool((docId, offset, maxChars, context) -> { throw failure; });
      String readArguments = "{\"path\":\"" + path + "\"}";
      assertSame(refusal, assertThrows(EngineAdmissionException.class,
          () -> read.execute(readArguments, EngineContextTestFixtures.AGENT_LOOP)));

      var asyncRead = new ReadDocumentTool((docId, offset, maxChars, context) ->
          CompletableFuture.failedFuture(failure));
      assertSame(refusal, assertThrows(EngineAdmissionException.class,
          () -> asyncRead.execute(readArguments, EngineContextTestFixtures.AGENT_LOOP)));

      var files = new FileOperationsTool(context -> { throw failure; },
          (mappings, context) -> { throw new AssertionError("Refusal must prevent file effects"); },
          new FileOperationLog(tempDir.resolve("file-operations")));
      String fileArguments = "{\"operations\":[{\"op\":\"MKDIR\",\"destination\":\"" + path + "\"}]}";
      assertSame(refusal, assertThrows(EngineAdmissionException.class,
          () -> files.execute(fileArguments, EngineContextTestFixtures.AGENT_LOOP)));
    }
  }

  @Test
  @DisplayName("future wrappers are unwrapped before classifying, or every async failure is INTERNAL")
  void futureWrappersAreUnwrapped() {
    OperationResult completion =
        AgentToolErrors.classify(
            "core_read_document", "Read error", new CompletionException(new TimeoutException("t")));
    assertEquals(ApiErrorCode.TIMEOUT, codeOf(completion));

    OperationResult execution =
        AgentToolErrors.classify(
            "core_read_document", "Read error", new ExecutionException(new TimeoutException("t")));
    assertEquals(ApiErrorCode.TIMEOUT, codeOf(execution));
  }

  @Test
  @DisplayName("anything unrecognised stays INTERNAL_ERROR — the conservative end keeps its trace")
  void unrecognisedIsInternal() {
    OperationResult r =
        AgentToolErrors.classify("core_ingest_files", "Ingest error", new IllegalStateException("boom"));
    assertEquals(ApiErrorCode.INTERNAL_ERROR, codeOf(r));
    assertEquals(Boolean.FALSE, r.retryable().orElseThrow());
  }

  @Test
  @DisplayName("the model-facing message keeps its existing shape: \"<prefix>: <detail>\"")
  void messageShapeUnchanged() {
    OperationResult r =
        AgentToolErrors.classify("core_search_index", "Search error", new IllegalStateException("boom"));
    assertEquals("Search error: boom", r.message());
  }

  @Test
  @DisplayName("badRequest() carries the tool name in errorDetails so a consumer can attribute it")
  void badRequestCarriesTool() {
    OperationResult r = AgentToolErrors.badRequest("core_file_operations", "missing 'destination'");
    assertEquals(ApiErrorCode.BAD_REQUEST, codeOf(r));
    assertEquals("core_file_operations", r.errorDetails().get("tool"));
    assertEquals("missing 'destination'", r.message());
  }
}
