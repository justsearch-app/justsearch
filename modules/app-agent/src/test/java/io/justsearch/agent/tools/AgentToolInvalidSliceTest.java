/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.EngineContextTestFixtures;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

final class AgentToolInvalidSliceTest {
  @Test
  void typedInvalidArgumentIsNonRetryableWithOnlyFutureWrappersRemoved() {
    var failure = new KnowledgeClientException(
        KnowledgeClientException.Status.INVALID_ARGUMENT, "invalid offset");
    for (Throwable error : new Throwable[] {failure,
        new CompletionException(new ExecutionException(failure))}) {
      var result = AgentToolErrors.classify("core_read_document", "Read error", error);
      assertFalse(result.success());
      assertEquals("INVALID_REQUEST", result.errorCode().orElseThrow());
      assertFalse(result.retryable().orElseThrow());
      assertTrue(result.message().contains("invalid offset"));
    }
    var wrapped = AgentToolErrors.classify("core_read_document", "Read error",
        new IllegalStateException("wrapper", failure));
    assertEquals("INTERNAL_ERROR", wrapped.errorCode().orElseThrow());
  }

  @Test
  void readDocumentReportsTypedInvalidOffsetWithoutRetrying() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var tool = new ReadDocumentTool((docId, offset, max, context) -> {
      calls.incrementAndGet();
      assertEquals(2, offset);
      return CompletableFuture.failedFuture(new KnowledgeClientException(
          KnowledgeClientException.Status.INVALID_ARGUMENT, "invalid offset"));
    });
    var result = tool.execute("{\"path\":\"doc\",\"offset_chars\":2}",
        EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertEquals("INVALID_REQUEST", result.errorCode().orElseThrow());
    assertFalse(result.retryable().orElseThrow());
    assertEquals(1, calls.get());
  }

  @Test
  void surrogateMessageDoesNotClassifyAnInternalFailureAsInvalidRequest() {
    var result = AgentToolErrors.classify("core_read_document", "Read error",
        new IllegalArgumentException("offset splits a surrogate pair"));
    assertEquals("INTERNAL_ERROR", result.errorCode().orElseThrow());
  }
}
