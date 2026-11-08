/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.RerankRequest;
import io.justsearch.ipc.RerankResponse;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** C5 P1: the actual request-building transport seam retains additive default compatibility. */
final class SearchRpcQueryBudgetPolicyTest {
  @Test
  void genericRerankAndAbsentPolicyRetainDefaultOffCompatibility() throws Exception {
    var requests = new ArrayList<RerankRequest>();
    SearchServiceCalls calls = mock(SearchServiceCalls.class);
    when(calls.rerank(any())).thenAnswer(invocation -> {
      requests.add(invocation.getArgument(0));
      return RerankResponse.getDefaultInstance();
    });
    try (var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var client = new TestKnowledgeClient(executors, calls)) {
      client.rerank("long generic GPL input", List.of("document"), 1234, TestEngineContexts.internal());
      client.rerank("long generic RAG input", List.of("document"), 1234, TestEngineContexts.internal());
    }
    assertEquals(2, requests.size());
    for (var request : requests) {
      assertFalse(policyEnabled(request));
      assertEquals(1234, request.getDeadlineMs());
      assertEquals(List.of("document"), request.getDocumentTextsList());
    }
    assertFalse(policyEnabled(RerankRequest.parseFrom(RerankRequest.getDefaultInstance().toByteArray())));
  }

  @Test
  void searchRouteSendsTrueWhileFollowingGenericCallStillSendsFalse() throws Exception {
    var requests = new ArrayList<RerankRequest>();
    SearchServiceCalls calls = mock(SearchServiceCalls.class);
    when(calls.rerank(any())).thenAnswer(invocation -> {
      requests.add(invocation.getArgument(0));
      return RerankResponse.getDefaultInstance();
    });
    try (var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var client = new TestKnowledgeClient(executors, calls)) {
      // On the exact before tree ordinary search used generic rerank. Keep that public boundary
      // executable there so missing policy fails a field assertion rather than compilation.
      Method route;
      try {
        route = KnowledgeClient.class.getMethod("rerankSearch", String.class, List.class, long.class, EngineContext.class);
      } catch (NoSuchMethodException beforePolicy) {
        route = KnowledgeClient.class.getMethod("rerank", String.class, List.class, long.class, EngineContext.class);
      }
      route.invoke(client, "actual CE query", List.of("one", "two"), 1234L, TestEngineContexts.internal());
      client.rerank("generic after search", List.of("document"), 1234, TestEngineContexts.internal());
    }
    assertEquals(2, requests.size());
    assertTrue(policyEnabled(requests.getFirst()), "interactive search alone opts into wire policy field 5");
    assertFalse(policyEnabled(requests.getLast()));
    assertEquals("actual CE query", requests.getFirst().getQuery());
    assertEquals(List.of("one", "two"), requests.getFirst().getDocumentTextsList());
  }

  private static boolean policyEnabled(RerankRequest request) throws Exception {
    return UnknownFieldSet.parseFrom(request.toByteArray()).getField(5).getVarintList().contains(1L);
  }
}
