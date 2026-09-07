/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.ApiErrorCode;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 877 §2.6 — the error classifier's contract.
 *
 * <p>The assertions that matter are the DISTINCTIONS, not the happy path: a model's bad JSON and an
 * unreachable Worker used to produce the same untyped string with the same ERROR-level stack trace,
 * and a consumer could not tell a model mistake from a system fault. Each test below pins one of
 * those distinctions.
 */
class AgentToolErrorsTest {

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
    // app-agent does not depend on the gRPC runtime, so the classifier matches the class NAME.
    // This stand-in proves the name-matching arm without dragging that dependency into the module.
    class StatusRuntimeException extends RuntimeException {
      private static final long serialVersionUID = 1L;

      StatusRuntimeException(String m) {
        super(m);
      }
    }
    OperationResult r =
        AgentToolErrors.classify(
            "core_browse_folders", "Browse error", new StatusRuntimeException("UNAVAILABLE: io"));
    assertEquals(ApiErrorCode.SERVICE_UNAVAILABLE, codeOf(r));
    assertEquals(Boolean.TRUE, r.retryable().orElseThrow());
  }

  @Test
  @DisplayName("an unreachable Worker gets the same actionable sentence, not a transport dump")
  void workerUnreachableMessageIsActionable() {
    class StatusRuntimeException extends RuntimeException {
      private static final long serialVersionUID = 1L;

      StatusRuntimeException(String m) {
        super(m);
      }
    }
    OperationResult r =
        AgentToolErrors.classify(
            "core_browse_folders",
            "Browse error",
            new StatusRuntimeException("UNAVAILABLE: io exception"));

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
