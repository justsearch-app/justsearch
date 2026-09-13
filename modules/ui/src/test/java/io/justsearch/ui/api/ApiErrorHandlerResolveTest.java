package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.IndexRuntimeIOException;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.inference.LlmServerException;
import io.justsearch.ipc.CircuitBreakerOpenException;
import io.justsearch.ipc.KnowledgeServerNotConnectedException;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ApiErrorHandler.resolve() and toResponse()")
final class ApiErrorHandlerResolveTest {

  @Test
  void wrappedExecutorRefusalWritesBoundedRetryHeaderButNeverPromisesSafeReplay() {
    var refusal = new io.justsearch.core.execution.EngineExecutorRejectedException(
        io.justsearch.core.execution.EngineExecutorRejectedException.Reason.QUEUE_LIMIT, "test.http", 7);
    var ctx = org.mockito.Mockito.mock(io.javalin.http.Context.class);
    org.mockito.Mockito.when(ctx.path()).thenReturn("/test");
    var failure = new java.util.concurrent.CompletionException(new java.util.concurrent.ExecutionException(refusal));
    assertTrue(ApiErrorHandler.writeExecutorRefusal(ctx, failure, null));
    org.mockito.Mockito.verify(ctx).status(429);
    org.mockito.Mockito.verify(ctx).header("Retry-After", "7");
    var response = ApiErrorHandler.toResponse(ApiErrorHandler.resolve(failure), failure);
    assertEquals("ADMISSION_ENGINE_LIMIT", response.get("errorCode"));
    assertEquals(false, response.get("retrySafe"));
  }

  @Test
  void boundedExecutorRefusalsKeepCapacityClassificationThroughAsyncWrappers() {
    var refused = new io.justsearch.core.execution.EngineExecutorRejectedException(
        io.justsearch.core.execution.EngineExecutorRejectedException.Reason.QUEUE_LIMIT, "test", 3);
    assertEquals(ApiErrorCode.ADMISSION_ENGINE_LIMIT, ApiErrorHandler.resolve(refused));
    assertEquals(ApiErrorCode.ADMISSION_ENGINE_LIMIT,
        ApiErrorHandler.resolve(new java.util.concurrent.CompletionException(refused)));
    assertEquals(429, ApiErrorHandler.httpStatusFor(ApiErrorHandler.resolve(refused)));
    assertEquals(3, refused.retryAfterSeconds());
    var closed = new io.justsearch.core.execution.EngineExecutorRejectedException(
        io.justsearch.core.execution.EngineExecutorRejectedException.Reason.CLOSED, "test", 3);
    assertEquals(ApiErrorCode.SERVICE_UNAVAILABLE, ApiErrorHandler.resolve(closed));
    assertEquals(503, ApiErrorHandler.httpStatusFor(ApiErrorHandler.resolve(closed)));
  }

  // ── resolve() ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("resolve()")
  class Resolve {

    @Test
    @DisplayName("null → INTERNAL_ERROR")
    void nullReturnsInternalError() {
      assertEquals(ApiErrorCode.INTERNAL_ERROR, ApiErrorHandler.resolve(null));
    }

    @Test
    @DisplayName("LlmServerException(400) → CONTEXT_TOO_LARGE")
    void llm400IsContextTooLarge() {
      assertEquals(
          ApiErrorCode.CONTEXT_TOO_LARGE,
          ApiErrorHandler.resolve(new LlmServerException(400, "prompt too long")));
    }

    @Test
    @DisplayName("LlmServerException(429) → LLM_OVERLOADED")
    void llm429IsOverloaded() {
      assertEquals(
          ApiErrorCode.LLM_OVERLOADED,
          ApiErrorHandler.resolve(new LlmServerException(429, "rate limited")));
    }

    @Test
    @DisplayName("LlmServerException(503) → LLM_OVERLOADED")
    void llm503IsOverloaded() {
      assertEquals(
          ApiErrorCode.LLM_OVERLOADED,
          ApiErrorHandler.resolve(new LlmServerException(503, "service unavailable")));
    }

    @Test
    @DisplayName("LlmServerException(500) → INTERNAL_ERROR")
    void llm500IsInternalError() {
      assertEquals(
          ApiErrorCode.INTERNAL_ERROR,
          ApiErrorHandler.resolve(new LlmServerException(500, "internal error")));
    }

    @Test
    @DisplayName("KnowledgeServerNotConnectedException → SERVICE_UNAVAILABLE")
    void knowledgeServerNotConnected() {
      assertEquals(
          ApiErrorCode.SERVICE_UNAVAILABLE,
          ApiErrorHandler.resolve(new KnowledgeServerNotConnectedException()));
    }

    @Test
    @DisplayName("CircuitBreakerOpenException → SERVICE_UNAVAILABLE")
    void circuitBreakerOpen() {
      assertEquals(
          ApiErrorCode.SERVICE_UNAVAILABLE,
          ApiErrorHandler.resolve(new CircuitBreakerOpenException("tripped")));
    }

    @Test
    @DisplayName("TimeoutException → TIMEOUT")
    void timeoutException() {
      assertEquals(
          ApiErrorCode.TIMEOUT,
          ApiErrorHandler.resolve(new TimeoutException("timed out")));
    }

    @Test
    @DisplayName("IOException → IO_ERROR")
    void ioException() {
      assertEquals(
          ApiErrorCode.IO_ERROR, ApiErrorHandler.resolve(new IOException("disk error")));
    }

    @Test
    @DisplayName("IllegalArgumentException → INVALID_REQUEST")
    void illegalArgument() {
      assertEquals(
          ApiErrorCode.INVALID_REQUEST,
          ApiErrorHandler.resolve(new IllegalArgumentException("bad param")));
    }

    @Test
    @DisplayName("IllegalStateException → INVALID_STATE")
    void illegalState() {
      assertEquals(
          ApiErrorCode.INVALID_STATE,
          ApiErrorHandler.resolve(new IllegalStateException("wrong state")));
    }

    @Test
    @DisplayName("UnsupportedOperationException → NOT_SUPPORTED")
    void unsupportedOperation() {
      assertEquals(
          ApiErrorCode.NOT_SUPPORTED,
          ApiErrorHandler.resolve(new UnsupportedOperationException("nope")));
    }

    @Test
    @DisplayName("IndexRuntimeIOException(DISK_FULL) → INDEX_DISK_FULL")
    void indexDiskFull() {
      assertEquals(
          ApiErrorCode.INDEX_DISK_FULL,
          ApiErrorHandler.resolve(
              new IndexRuntimeIOException(
                  IndexRuntimeIOException.Reason.DISK_FULL, "disk full", null)));
    }

    @Test
    @DisplayName("IndexRuntimeIOException(CORRUPT_INDEX) → INDEX_CORRUPT")
    void indexCorrupt() {
      assertEquals(
          ApiErrorCode.INDEX_CORRUPT,
          ApiErrorHandler.resolve(
              new IndexRuntimeIOException(
                  IndexRuntimeIOException.Reason.CORRUPT_INDEX, "corrupt", null)));
    }

    @Test
    @DisplayName("IndexRuntimeIOException(LOCKED) → INDEX_LOCKED")
    void indexLocked() {
      assertEquals(
          ApiErrorCode.INDEX_LOCKED,
          ApiErrorHandler.resolve(
              new IndexRuntimeIOException(
                  IndexRuntimeIOException.Reason.LOCKED, "locked", null)));
    }

    @Test
    @DisplayName("IndexRuntimeIOException(BACKPRESSURE) → INDEX_BACKPRESSURE")
    void indexBackpressure() {
      assertEquals(
          ApiErrorCode.INDEX_BACKPRESSURE,
          ApiErrorHandler.resolve(
              new IndexRuntimeIOException(
                  IndexRuntimeIOException.Reason.BACKPRESSURE, "slow down", null)));
    }

    @Test
    @DisplayName("RuntimeException with 'unavailable' in message → INTERNAL_ERROR (no message-sniffing)")
    void noMessageSniffingUnavailable() {
      assertEquals(
          ApiErrorCode.INTERNAL_ERROR,
          ApiErrorHandler.resolve(new RuntimeException("server unavailable")));
    }

    @Test
    @DisplayName("RuntimeException with 'not found' in message → INTERNAL_ERROR (no message-sniffing)")
    void noMessageSniffingNotFound() {
      assertEquals(
          ApiErrorCode.INTERNAL_ERROR,
          ApiErrorHandler.resolve(new RuntimeException("resource not found")));
    }

    @Test
    @DisplayName("unknown exception → INTERNAL_ERROR")
    void unknownException() {
      assertEquals(
          ApiErrorCode.INTERNAL_ERROR,
          ApiErrorHandler.resolve(new RuntimeException("something weird")));
    }
  }

  // ── toResponse() null-safety ───────────────────────────────────────────

  @Nested
  @DisplayName("toResponse() null-safety")
  class ToResponseNullSafety {

    @Test
    @DisplayName("toResponse(null, telemetry, route) does not throw NPE")
    void nullExceptionDoesNotThrow() {
      Map<String, Object> response = ApiErrorHandler.toResponse((Exception) null, null, "/test");
      assertEquals("Internal error", response.get("error"));
      assertEquals("INTERNAL_ERROR", response.get("errorCode"));
    }

    @Test
    @DisplayName("toResponse(null) does not throw NPE")
    void nullExceptionLegacyDoesNotThrow() {
      Map<String, Object> response = ApiErrorHandler.toResponse((Exception) null);
      assertEquals("An error occurred", response.get("error"));
      assertEquals("INTERNAL_ERROR", response.get("errorCode"));
    }
  }

  // ── sanitizeMessage() ─────────────────────────────────────────────────

  @Nested
  @DisplayName("sanitizeMessage()")
  class SanitizeMessage {

    @Test
    @DisplayName("strips Windows paths")
    void stripsWindowsPaths() {
      String sanitized = ApiErrorHandler.sanitizeMessage("Failed to read C:\\Users\\admin\\data.db");
      assertFalse(sanitized.contains("C:\\Users"), "Windows path should be replaced");
      assertTrue(sanitized.contains("[path]"));
    }

    @Test
    @DisplayName("strips internal class names")
    void stripsInternalClassNames() {
      String sanitized =
          ApiErrorHandler.sanitizeMessage("io.justsearch.app.services.FooException occurred");
      assertFalse(sanitized.contains("io.justsearch"), "Internal class name should be replaced");
      assertTrue(sanitized.contains("[internal]"));
    }

    @Test
    @DisplayName("null message returns generic error")
    void nullMessage() {
      assertEquals("An error occurred", ApiErrorHandler.sanitizeMessage(null));
    }

    @Test
    @DisplayName("blank message returns generic error")
    void blankMessage() {
      assertEquals("An error occurred", ApiErrorHandler.sanitizeMessage("   "));
    }
  }

  // ── resolveByName() ───────────────────────────────────────────────────

  @Nested
  @DisplayName("resolveByName()")
  class ResolveByName {

    @Test
    @DisplayName("valid name resolves correctly")
    void validName() {
      assertEquals(ApiErrorCode.TIMEOUT, ApiErrorHandler.resolveByName("TIMEOUT"));
    }

    @Test
    @DisplayName("null name falls back to INTERNAL_ERROR")
    void nullName() {
      assertEquals(ApiErrorCode.INTERNAL_ERROR, ApiErrorHandler.resolveByName(null));
    }

    @Test
    @DisplayName("unknown name falls back to INTERNAL_ERROR")
    void unknownName() {
      assertEquals(ApiErrorCode.INTERNAL_ERROR, ApiErrorHandler.resolveByName("FAKE_CODE"));
    }
  }

  // ── Response structure ────────────────────────────────────────────────

  @Nested
  @DisplayName("response structure")
  class ResponseStructure {

    @Test
    @DisplayName("typed response contains all required fields")
    void typedResponseHasRequiredFields() {
      Map<String, Object> response =
          ApiErrorHandler.toResponse(ApiErrorCode.TIMEOUT, "timed out");
      assertEquals("timed out", response.get("error"));
      assertEquals("TIMEOUT", response.get("errorCode"));
      assertEquals("TRANSIENT", response.get("errorClass"));
      assertEquals(true, response.get("retryable"));
    }

    @Test
    @DisplayName("retryable is false for permanent errors")
    void permanentErrorNotRetryable() {
      Map<String, Object> response =
          ApiErrorHandler.toResponse(ApiErrorCode.INTERNAL_ERROR, "fail");
      assertEquals(false, response.get("retryable"));
    }
  }
}
