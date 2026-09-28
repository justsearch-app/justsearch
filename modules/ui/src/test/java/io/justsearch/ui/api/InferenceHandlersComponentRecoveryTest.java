/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.services.worker.ComponentRecoveryAuthority;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

final class InferenceHandlersComponentRecoveryTest {
  @Test
  void acceptedRequestNamesTheComponentAndSchedulesOneAttempt() {
    var authority = new StubAuthority(ComponentRecoveryAuthority.Outcome.ACCEPTED);
    var handler = handler(authority);
    var ctx = context();

    handler.handleRecoverComponent(ctx);

    verify(ctx).status(202);
    assertEquals("index", authority.requestedName);
    assertEquals("index", body(ctx).get("component"));
    assertEquals("ACCEPTED", body(ctx).get("recovery"));
  }

  @Test
  void busyRecoveryReturnsEngineLimitWithoutSchedulingAnotherAttempt() {
    var authority = new StubAuthority(ComponentRecoveryAuthority.Outcome.ALREADY_RUNNING);
    var ctx = context();
    handler(authority).handleRecoverComponent(ctx);

    verify(ctx).status(429);
    assertEquals("index", authority.requestedName);
    assertEquals("ADMISSION_ENGINE_LIMIT", body(ctx).get("errorCode"));
  }

  @Test
  void otherRefusalsUseTheirActualHttpStates() {
    assertStatus(ComponentRecoveryAuthority.Outcome.EXHAUSTED, 503);
    assertStatus(ComponentRecoveryAuthority.Outcome.NOT_APPLICABLE, 409);
    assertStatus(ComponentRecoveryAuthority.Outcome.UNKNOWN_COMPONENT, 404);
    assertStatus(ComponentRecoveryAuthority.Outcome.OWNER_UNAVAILABLE, 503);
  }

  @Test
  void unboundAuthorityReportsStartupWindow() {
    var ctx = context();
    handler(null).handleRecoverComponent(ctx);
    verify(ctx).status(503);
    assertEquals("SERVICE_UNAVAILABLE", body(ctx).get("errorCode"));
  }

  private static void assertStatus(ComponentRecoveryAuthority.Outcome verdict, int status) {
    var ctx = context();
    handler(new StubAuthority(verdict)).handleRecoverComponent(ctx);
    verify(ctx).status(status);
  }

  private static InferenceHandlers handler(ComponentRecoveryAuthority authority) {
    var handler = new InferenceHandlers(
        mock(io.justsearch.app.api.OnlineAiService.class), null,
        mock(io.justsearch.gpu.GpuCapabilitiesService.class),
        mock(io.justsearch.app.api.EnterprisePolicyService.class),
        mock(io.justsearch.app.services.settings.UiSettingsStore.class), null, null, null);
    if (authority != null) handler.setComponentRecovery(authority);
    return handler;
  }

  private static Context context() {
    var ctx = mock(Context.class);
    when(ctx.pathParam("name")).thenReturn("index");
    when(ctx.endpointHandlerPath()).thenReturn("/api/engine/components/{name}/recover");
    when(ctx.status(anyInt())).thenReturn(ctx);
    when(ctx.json(any())).thenReturn(ctx);
    return ctx;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> body(Context ctx) {
    var captured = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(captured.capture());
    return (Map<String, Object>) captured.getValue();
  }

  private static final class StubAuthority implements ComponentRecoveryAuthority {
    private final Outcome verdict;
    private String requestedName;

    private StubAuthority(Outcome verdict) {
      this.verdict = verdict;
    }

    @Override
    public Outcome requestComponentRecovery(String name) {
      requestedName = name;
      return verdict;
    }
  }
}
