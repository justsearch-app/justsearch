/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

final class VduOpsAttemptOwnershipTest {
  @Test void completedOlderRecoveryKeepsLaterClaimInTheNextCancellation() throws Exception {
    var calls=mock(IngestServiceCalls.class);var ops=ops(calls);
    var context=TestEngineContexts.internal().withWorkId(UUID.randomUUID());
    when(calls.markVduProcessing(any())).thenReturn(MarkVduProcessingResponse.newBuilder().setSuccess(true).setRetryCount(1).build());
    claim(ops,"first","attempt-A",context);
    when(calls.recoverVduProcessing(any())).thenAnswer(invocation -> {
      claim(ops,"second","attempt-B",context);
      return RecoverVduProcessingResponse.newBuilder().setRecoveredCount(1).build();
    });
    assertEquals(1,ops.recoverVduProcessingForOwner(context));
    assertEquals(Set.of("attempt-B"),attempts(recoveryRequest(ops,context)),
        "completion of an older cancellation cannot forget a later claim");
  }

  @Test void failedRecoveryKeepsItsAttemptsAvailableForRetry() throws Exception {
    var calls=mock(IngestServiceCalls.class);var ops=ops(calls);
    var context=TestEngineContexts.internal().withWorkId(UUID.randomUUID());
    when(calls.markVduProcessing(any())).thenReturn(MarkVduProcessingResponse.newBuilder().setSuccess(true).setRetryCount(1).build());
    claim(ops,"first","attempt-A",context);
    when(calls.recoverVduProcessing(any())).thenThrow(new IllegalStateException("controlled recovery failure"));
    assertThrows(IllegalStateException.class,()->ops.recoverVduProcessingForOwner(context));
    assertEquals(Set.of("attempt-A"),attempts(recoveryRequest(ops,context)),
        "a failed cancellation must retain the exact attempts for retry");
  }

  @Test void refusedReleaseRetainsAttemptForOwnedRecovery() throws Exception {
    var calls = mock(IngestServiceCalls.class); var ops = ops(calls);
    var context = TestEngineContexts.internal().withWorkId(UUID.randomUUID());
    when(calls.markVduProcessing(any())).thenReturn(MarkVduProcessingResponse.newBuilder().setSuccess(true).setRetryCount(1).build());
    claim(ops, "first", "attempt-A", context);
    when(calls.releaseVduProcessing("first", "attempt-A")).thenReturn(false);
    assertFalse(ops.releaseVduProcessing("first", "attempt-A", context));
    assertEquals(Set.of("attempt-A"), attempts(recoveryRequest(ops, context)),
        "C17: a refused release cannot forget recoverable authority");
  }

  private static VduOps ops(IngestServiceCalls calls) {
    return new VduOps(new IngestRpcExecutor() {
      @Override public <T> T execute(String operation,KnowledgeClient.RpcDeadlineCategory category,
          Function<IngestServiceCalls,T> action,EngineContext context) { return action.apply(calls); }
    });
  }
  private static void claim(VduOps ops,String id,String attempt,EngineContext context) throws Exception {
    try { assertEquals(1,ops.getClass().getDeclaredMethod("markVduProcessing",String.class,int.class,String.class,EngineContext.class)
        .invoke(ops,id,3,attempt,context)); }
    catch(NoSuchMethodException baseline) { assertEquals(1,ops.markVduProcessing(id,3,context)); }
  }
  private static RecoverVduProcessingRequest recoveryRequest(VduOps ops,EngineContext context) throws Exception {
    try { return (RecoverVduProcessingRequest)ops.getClass().getDeclaredMethod("recoveryRequest",EngineContext.class).invoke(ops,context); }
    catch(NoSuchMethodException baseline) { return RecoverVduProcessingRequest.newBuilder().setProcedureOwner(context.workId().orElseThrow().toString()).build(); }
  }
  @SuppressWarnings("unchecked")
  private static Set<String> attempts(RecoverVduProcessingRequest request) throws Exception {
    try { return new HashSet<>((List<String>)request.getClass().getMethod("getAttemptIdsList").invoke(request)); }
    catch(NoSuchMethodException baseline) { return Set.of(); }
  }
}
