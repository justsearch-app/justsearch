/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.SettingsService;
import io.justsearch.app.api.operations.OperationStoreException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class SettingsControllerErrorProjectionTest {
  @ParameterizedTest
  @CsvSource({
      "RECONFIGURE_IN_PROGRESS,409,TRANSIENT,true",
      "OPERATIONS_CAPACITY,503,TRANSIENT,true",
      "SETTINGS_RECOVERY_REQUIRED,503,PERMANENT,false",
      "OPERATION_STORAGE_FAILED,500,PERMANENT,false",
      "VERSION_CONFLICT,409,VALIDATION,false",
      "SETTINGS_READ_ONLY,409,POLICY,false"
  })
  void typedRefusalHasConsistentStatusClassAndRetryability(String code, int status, String classification,
      boolean retryable) {
    var service = mock(SettingsService.class);
    RuntimeException failure = code.equals("OPERATION_STORAGE_FAILED")
        ? new OperationStoreException(OperationStoreException.Code.STORAGE_FAILED, null)
        : new OperationPreparationRefused(OperationResult.failure("Refused", code, Map.of(), retryable));
    when(service.applyPublic(any(), any(), any())).thenThrow(failure);
    var context = mock(Context.class);
    when(context.body()).thenReturn("{}");
    when(context.path()).thenReturn("/api/settings/v2");
    when(context.method()).thenReturn(io.javalin.http.HandlerType.POST);
    when(context.status(anyInt())).thenReturn(context);
    when(context.json(any())).thenReturn(context);
    new SettingsController(null, Path.of("."), null, service).handleUpdateSettingsV2(context);
    verify(context).status(status);
    var capture = ArgumentCaptor.forClass(Object.class);
    verify(context).json(capture.capture());
    var payload = assertInstanceOf(Map.class, capture.getValue());
    assertEquals(code, payload.get("errorCode"));
    assertEquals(classification, payload.get("errorClass"));
    assertEquals(retryable, payload.get("retryable"));
    assertFalse(payload.containsKey("witness"));
  }
}
