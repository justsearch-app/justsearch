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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

  @ParameterizedTest
  @ValueSource(strings = {"transport", "circuit", "cancelled", "refused"})
  void updatePropagatesControlFailureWithoutTurningItIntoAnApplicationOutcome(String kind) {
    RuntimeException failure = controlFailure(kind);
    rpc.failWith(failure);
    assertSame(failure, assertThrows(RuntimeException.class, this::update));
    assertInvocation("updateVduResult", KnowledgeClient.RpcDeadlineCategory.VDU_OPERATION, context);
  }

  @ParameterizedTest
  @ValueSource(strings = {"transport", "circuit", "cancelled", "refused"})
  void markPropagatesControlFailureWithoutTurningItIntoRetryExhaustion(String kind) {
    RuntimeException failure = controlFailure(kind);
    rpc.failWith(failure);
    assertSame(failure, assertThrows(RuntimeException.class,
        () -> ops.markVduProcessing("document", 3, context)));
    assertInvocation("markVduProcessing", KnowledgeClient.RpcDeadlineCategory.STANDARD, context);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void updatePreservesExplicitApplicationOutcomeAndRequest(boolean success) {
    when(serviceCalls.updateVduResult(any())).thenReturn(io.justsearch.ipc.UpdateVduResultResponse
        .newBuilder().setSuccess(success).setError(success ? "" : "document absent").build());
    assertEquals(success, update());
    var request = ArgumentCaptor.forClass(io.justsearch.ipc.UpdateVduResultRequest.class);
    verify(serviceCalls).updateVduResult(request.capture());
    assertEquals("document", request.getValue().getDocId());
    assertEquals("content", request.getValue().getExtractedContent());
    assertEquals(io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT,
        request.getValue().getOutcome());
    assertEquals("enrichment", request.getValue().getVduEnrichment());
    assertEquals(2, request.getValue().getPageCount());
    assertInvocation("updateVduResult", KnowledgeClient.RpcDeadlineCategory.VDU_OPERATION, context);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void markPreservesExplicitApplicationOutcomeAndRequest(boolean success) {
    when(serviceCalls.markVduProcessing(any())).thenReturn(io.justsearch.ipc.MarkVduProcessingResponse
        .newBuilder().setSuccess(success).setRetryCount(2)
        .setError(success ? "" : "Max retries exceeded").build());
    assertEquals(success ? 2 : -1, ops.markVduProcessing("document", 3, context));
    var request = ArgumentCaptor.forClass(io.justsearch.ipc.MarkVduProcessingRequest.class);
    verify(serviceCalls).markVduProcessing(request.capture());
    assertEquals("document", request.getValue().getDocId());
    assertEquals(3, request.getValue().getMaxRetries());
    assertInvocation("markVduProcessing", KnowledgeClient.RpcDeadlineCategory.STANDARD, context);
  }

  private boolean update() {
    return ops.updateVduResult("document", "content",
        io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT, "enrichment", 2, context);
  }

  private static RuntimeException controlFailure(String kind) {
    return switch (kind) {
      case "transport" -> new IllegalStateException("worker unavailable");
      case "circuit" -> new io.justsearch.ipc.CircuitBreakerOpenException("worker unavailable");
      case "cancelled" -> new EngineWorkCancelledException("user_stop");
      case "refused" -> new io.justsearch.core.execution.EngineExecutorRejectedException(
          io.justsearch.core.execution.EngineExecutorRejectedException.Reason.QUEUE_LIMIT,
          "index.ingest", 1);
      default -> throw new IllegalArgumentException(kind);
    };
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
