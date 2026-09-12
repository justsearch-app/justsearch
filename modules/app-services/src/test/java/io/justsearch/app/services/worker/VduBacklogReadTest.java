/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineWorkCancelledException;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.QueryPendingVduRequest;
import io.justsearch.ipc.QueryPendingVduResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Pins the Head-side mapping for the VDU and embedding backlog read operations. */
final class VduBacklogReadTest {

  private IngestServiceCalls serviceCalls;
  private RecordingRpc rpc;
  private VduOps ops;
  private EngineContext context;

  @BeforeEach
  void setUp() {
    serviceCalls = mock(IngestServiceCalls.class);
    rpc = new RecordingRpc(serviceCalls);
    ops = new VduOps(rpc);
    context = TestEngineContexts.ui();
  }

  @Test
  void countPendingVduReturnsTheTotalEvenWhenTheResponsePageIsSmaller() {
    QueryPendingVduResponse response =
        QueryPendingVduResponse.newBuilder().addDocIds("one").setTotalCount(7).build();
    when(serviceCalls.queryPendingVdu(any(QueryPendingVduRequest.class))).thenReturn(response);

    assertEquals(7, ops.countPendingVdu(context));

    ArgumentCaptor<QueryPendingVduRequest> request =
        ArgumentCaptor.forClass(QueryPendingVduRequest.class);
    verify(serviceCalls).queryPendingVdu(request.capture());
    assertEquals(1, request.getValue().getLimit());
    assertInvocation("queryPendingVdu", KnowledgeClient.RpcDeadlineCategory.STANDARD, context);
  }

  @Test
  void countPendingVduPreservesAReallyEmptyQueue() {
    when(serviceCalls.queryPendingVdu(any(QueryPendingVduRequest.class)))
        .thenReturn(QueryPendingVduResponse.getDefaultInstance());

    assertEquals(0, ops.countPendingVdu(context));

    verify(serviceCalls).queryPendingVdu(any(QueryPendingVduRequest.class));
    assertInvocation("queryPendingVdu", KnowledgeClient.RpcDeadlineCategory.STANDARD, context);
  }

  @Test
  void countPendingEmbeddingsUsesTheJavaOnlyCall() {
    when(serviceCalls.countPendingEmbeddings()).thenReturn(11);

    assertEquals(11, ops.countPendingEmbeddings(context));

    verify(serviceCalls).countPendingEmbeddings();
    assertInvocation(
        "countPendingEmbeddings", KnowledgeClient.RpcDeadlineCategory.STANDARD, context);
  }

  @Test
  void queryPendingVduDocIdsUsesTheDefaultAndCallerLimits() {
    QueryPendingVduResponse defaultResponse =
        QueryPendingVduResponse.newBuilder().addDocIds("default-one").addDocIds("default-two")
            .setTotalCount(8).build();
    QueryPendingVduResponse customResponse =
        QueryPendingVduResponse.newBuilder().addDocIds("custom-one").setTotalCount(8).build();
    when(serviceCalls.queryPendingVdu(any(QueryPendingVduRequest.class)))
        .thenReturn(defaultResponse, customResponse);

    assertEquals(List.of("default-one", "default-two"), ops.queryPendingVduDocIds(context));
    assertEquals(List.of("custom-one"), ops.queryPendingVduDocIds(2, context));

    ArgumentCaptor<QueryPendingVduRequest> request =
        ArgumentCaptor.forClass(QueryPendingVduRequest.class);
    verify(serviceCalls, times(2)).queryPendingVdu(request.capture());
    assertEquals(List.of(100, 2), request.getAllValues().stream()
        .map(QueryPendingVduRequest::getLimit).toList());
    assertEquals(
        2,
        rpc.invocations.stream()
            .filter(
                invocation ->
                    invocation.operation().equals("queryPendingVdu")
                        && invocation.category() == KnowledgeClient.RpcDeadlineCategory.STANDARD
                        && invocation.context() == context)
            .count());
  }

  @Test
  void everyBacklogReadPassesTheExactContextObjectToTheExecutor() {
    when(serviceCalls.queryPendingVdu(any(QueryPendingVduRequest.class)))
        .thenReturn(QueryPendingVduResponse.getDefaultInstance());
    when(serviceCalls.countPendingEmbeddings()).thenReturn(0);

    ops.countPendingVdu(context);
    ops.countPendingEmbeddings(context);
    ops.queryPendingVduDocIds(context);

    assertEquals(3, rpc.invocations.size());
    for (Invocation invocation : rpc.invocations) {
      assertSame(context, invocation.context());
    }
  }

  @Test
  void readRuntimeFailureIsPropagatedAsTheSameObjectFromCountsAndQuery() {
    assertReadFailurePropagates(new IllegalStateException("worker read failed"));
  }

  @Test
  void readCancellationIsPropagatedAsTheSameObjectFromCountsAndQuery() {
    assertReadFailurePropagates(new EngineWorkCancelledException("user_stop"));
  }

  private void assertReadFailurePropagates(RuntimeException failure) {
    rpc.failWith(failure);

    assertSame(failure, assertThrows(RuntimeException.class, () -> ops.countPendingVdu(context)));
    assertSame(
        failure, assertThrows(RuntimeException.class, () -> ops.countPendingEmbeddings(context)));
    assertSame(
        failure, assertThrows(RuntimeException.class, () -> ops.queryPendingVduDocIds(context)));
  }

  private void assertInvocation(
      String operation, KnowledgeClient.RpcDeadlineCategory category, EngineContext expectedContext) {
    assertEquals(1, rpc.invocations.stream()
        .filter(invocation -> invocation.operation().equals(operation)
            && invocation.category() == category
            && invocation.context() == expectedContext)
        .count());
  }

  private record Invocation(
      String operation, KnowledgeClient.RpcDeadlineCategory category, EngineContext context) {}

  private static final class RecordingRpc implements IngestRpcExecutor {
    private final IngestServiceCalls serviceCalls;
    private final List<Invocation> invocations = new ArrayList<>();
    private RuntimeException failure;

    private RecordingRpc(IngestServiceCalls serviceCalls) {
      this.serviceCalls = serviceCalls;
    }

    private void failWith(RuntimeException failure) {
      this.failure = failure;
    }

    @Override
    public <T> T execute(
        String operation,
        KnowledgeClient.RpcDeadlineCategory category,
        Function<IngestServiceCalls, T> rpcFunction,
        EngineContext engineContext) {
      invocations.add(new Invocation(operation, category, engineContext));
      if (failure != null) {
        throw failure;
      }
      return rpcFunction.apply(serviceCalls);
    }
  }
}
