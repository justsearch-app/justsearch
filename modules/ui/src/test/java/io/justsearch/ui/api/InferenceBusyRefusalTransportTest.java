/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.api.EngineAdmissionException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * #759: a refusal because the Engine's foreground inference
 * threads and their queue are full reaches HTTP as {@code ADMISSION_INFERENCE_BUSY}, a 429 with
 * Retry-After, never as {@code ADMISSION_ENGINE_LIMIT}; raised mid-request it is never replayable.
 *
 * <p>The reason is looked up by name so the check runs, and fails by assertion, where it is absent.
 */
final class InferenceBusyRefusalTransportTest {

  @Test
  @SuppressWarnings("unchecked")
  void inferenceBusyIsItsOwnCapacityCodeWithRetryAfterAndNoSafeReplay() {
    var refusal = new EngineAdmissionException(inferenceBusy(), 3);

    assertEquals("ADMISSION_INFERENCE_BUSY", RequestEngineWork.errorCode(refusal));
    var direct = mock(Context.class);
    RequestEngineWork.status(direct, refusal);
    verify(direct).status(429);
    verify(direct).header("Retry-After", "3");

    var failure = new CompletionException(new ExecutionException(refusal));
    assertEquals("ADMISSION_INFERENCE_BUSY", ApiErrorHandler.resolve(failure).name());
    assertEquals(429, ApiErrorHandler.httpStatusFor(ApiErrorHandler.resolve(failure)));
    var response = ApiErrorHandler.toResponse(ApiErrorHandler.resolve(failure), failure);
    assertEquals("ADMISSION_INFERENCE_BUSY", response.get("errorCode"));
    assertEquals(false, response.get("retrySafe"));

    var ctx = mock(Context.class);
    when(ctx.path()).thenReturn("/api/knowledge/search");
    assertTrue(ApiErrorHandler.writeExecutorRefusal(ctx, failure, null));
    verify(ctx).status(429);
    verify(ctx).header("Retry-After", "3");
    var body = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(body.capture());
    assertEquals("ADMISSION_INFERENCE_BUSY", ((Map<String, Object>) body.getValue()).get("errorCode"));
    assertEquals(false, ((Map<String, Object>) body.getValue()).get("retrySafe"));
  }

  static EngineAdmissionException.Reason inferenceBusy() {
    var reason = Arrays.stream(EngineAdmissionException.Reason.values())
        .filter(r -> r.name().equals("INFERENCE_BUSY")).findFirst();
    assertTrue(reason.isPresent(), "no INFERENCE_BUSY admission reason");
    return reason.orElseThrow();
  }
}
