/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.services.worker.ComponentRecoveryAuthority;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/** The legacy route remains for one release solely to describe its replacement. */
final class InferenceHandlersWorkerRestartTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void retiredRouteNeverDispatchesRecoveryRegardlessOfBootstrapPresence(boolean bound) {
    var authority = mock(ComponentRecoveryAuthority.class);
    var bootstrap = bound ? mock(KnowledgeServerBootstrap.class) : null;
    var handler = new InferenceHandlers(null, bootstrap, null, null, null, null, null, null,
        () -> authority);
    var ctx = mock(Context.class);
    when(ctx.status(anyInt())).thenReturn(ctx);
    when(ctx.json(any())).thenReturn(ctx);
    when(ctx.endpointHandlerPath()).thenReturn("/api/worker/restart");

    handler.handleRestartWorker(ctx);

    verify(ctx).status(410);
    var captured = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(captured.capture());
    var body = (Map<?, ?>) captured.getValue();
    assertEquals("ENDPOINT_RETIRED", body.get("errorCode"));
    assertEquals("PERMANENT", body.get("errorClass"));
    assertEquals(false, body.get("retryable"));
    assertEquals("errors.ENDPOINT_RETIRED", body.get("i18nKey"));
    assertEquals("This endpoint is retired; use POST /api/engine/components/index/recover",
        body.get("error"));
    assertFalse(body.containsKey("code"));
    assertFalse(body.containsKey("port"));
    verifyNoInteractions(authority);
    if (bootstrap != null) verifyNoInteractions(bootstrap);
  }
}
