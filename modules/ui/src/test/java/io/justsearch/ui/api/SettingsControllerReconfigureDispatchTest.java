/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.SettingsService;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.settings.LlmSettingsV2;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.api.settings.UiSettingsV2;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP settings writes must enter the composed core.reconfigure operation exactly once. */
final class SettingsControllerReconfigureDispatchTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Operation RECONFIGURE = new CoreOperationCatalog()
      .findById(CoreOperationCatalog.RECONFIGURE).orElseThrow();

  @Test
  void productionRouteDispatchesOneTypedEnvelopeAndPreservesFullWitnessWithNullModeIntent()
      throws Exception {
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    SettingsService service = mock(SettingsService.class);
    SettingsV2 input = fullSettings();
    ContextFixture fixture = contextFixture(input);
    AtomicReference<Object> responseBody = new AtomicReference<>();
    doAnswer(call -> {
      responseBody.set(call.getArgument(0));
      return fixture.context;
    }).when(fixture.context).json(any());

    when(dispatcher.dispatch(eq(RECONFIGURE), anyString(), any(InvocationProvenance.class),
        eq(Optional.empty()), eq(fixture.engineContext), eq(input.operationKey())))
        .thenReturn(OperationResult.success("Settings committed", responseData(input)));

    new SettingsController(null, Path.of("."), null, service, dispatcher, RECONFIGURE)
        .handleUpdateSettingsV2(fixture.context);

    ArgumentCaptor<String> arguments = ArgumentCaptor.forClass(String.class);
    verify(dispatcher, times(1)).dispatch(eq(RECONFIGURE), arguments.capture(),
        any(InvocationProvenance.class), eq(Optional.empty()), eq(fixture.engineContext),
        eq(input.operationKey()));
    JsonNode envelope = JSON.readTree(arguments.getValue());
    assertEquals(input, JSON.treeToValue(envelope.path("settings"), SettingsV2.class));
    assertTrue(envelope.has("modeIntent"));
    assertTrue(envelope.path("modeIntent").isNull(), "omitted UI intent is explicit null");
    assertEquals(input.operationKey(), envelope.path("settings").path("operationKey").asText());
    assertEquals(input.witness().acceptedRevision(),
        envelope.path("settings").path("witness").path("acceptedRevision").asLong());
    assertEquals(input.witness().lastCommittedOperationKey(),
        envelope.path("settings").path("witness").path("lastCommittedOperationKey").asText());
    SettingsV2 response = (SettingsV2) responseBody.get();
    assertEquals(input, response);
    verifyNoInteractions(service);
  }

  @Test
  void refreshHeaderBecomesAnAcceptedReconfigureIntentWithTheSameWitnessAndKey() throws Exception {
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    SettingsV2 input = new SettingsV2(null, null, null, null,
        new SettingsWitness(0, null), OperationKeys.generate(Clock.systemUTC()), null, null);
    ContextFixture fixture = contextFixture(input);
    when(fixture.context.header(SettingsController.REFRESH_INFERENCE_HEADER)).thenReturn("true");
    when(dispatcher.dispatch(eq(RECONFIGURE), anyString(), any(InvocationProvenance.class),
        eq(Optional.empty()), eq(fixture.engineContext), eq(input.operationKey())))
        .thenReturn(OperationResult.success("Refreshed", Map.of("operationKey", input.operationKey())));

    new SettingsController(null, Path.of("."), null, null, dispatcher, RECONFIGURE)
        .handleUpdateSettingsV2(fixture.context);

    ArgumentCaptor<String> arguments = ArgumentCaptor.forClass(String.class);
    verify(dispatcher).dispatch(eq(RECONFIGURE), arguments.capture(),
        any(InvocationProvenance.class), eq(Optional.empty()), eq(fixture.engineContext),
        eq(input.operationKey()));
    JsonNode envelope = JSON.readTree(arguments.getValue());
    assertTrue(envelope.path("refreshInference").booleanValue());
    assertEquals(input, JSON.treeToValue(envelope.path("settings"), SettingsV2.class));
  }

  @Test
  void malformedRefreshHeaderCannotFallThroughToOrdinarySettings() {
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    ContextFixture fixture = contextFixture(fullSettings());
    when(fixture.context.header(SettingsController.REFRESH_INFERENCE_HEADER)).thenReturn("false");

    new SettingsController(null, Path.of("."), null, null, dispatcher, RECONFIGURE)
        .handleUpdateSettingsV2(fixture.context);

    verify(fixture.context).status(400);
    verifyNoInteractions(dispatcher);
  }

  @Test
  void versionConflictFromReconfigureIsProjectedAsConflictAndRetainsOperationKey() {
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    SettingsService service = mock(SettingsService.class);
    SettingsV2 input = fullSettings();
    ContextFixture fixture = contextFixture(input);
    AtomicReference<Object> responseBody = new AtomicReference<>();
    doAnswer(call -> {
      responseBody.set(call.getArgument(0));
      return fixture.context;
    }).when(fixture.context).json(any());
    when(dispatcher.dispatch(eq(RECONFIGURE), anyString(), any(InvocationProvenance.class),
        eq(Optional.empty()), eq(fixture.engineContext), eq(input.operationKey())))
        .thenReturn(OperationResult.failure("Settings changed", "VERSION_CONFLICT", Map.of(), false));

    new SettingsController(null, Path.of("."), null, service, dispatcher, RECONFIGURE)
        .handleUpdateSettingsV2(fixture.context);

    verify(fixture.context).status(409);
    @SuppressWarnings("unchecked")
    Map<String, Object> response = (Map<String, Object>) responseBody.get();
    assertEquals("VERSION_CONFLICT", response.get("errorCode"));
    assertEquals(input.operationKey(), response.get("operationKey"));
    assertFalse(response.containsKey("witness"));
    verify(dispatcher, times(1)).dispatch(eq(RECONFIGURE), anyString(),
        any(InvocationProvenance.class), eq(Optional.empty()), eq(fixture.engineContext),
        eq(input.operationKey()));
    verifyNoInteractions(service);
  }

  @Test
  void componentFailureFromReconfigureCannotBecomeHttp200() {
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    SettingsService service = mock(SettingsService.class);
    SettingsV2 input = fullSettings();
    ContextFixture fixture = contextFixture(input);
    AtomicReference<Object> responseBody = new AtomicReference<>();
    AtomicInteger status = new AtomicInteger(200);
    when(fixture.context.status(anyInt())).thenAnswer(call -> {
      status.set(call.getArgument(0));
      return fixture.context;
    });
    doAnswer(call -> {
      responseBody.set(call.getArgument(0));
      return fixture.context;
    }).when(fixture.context).json(any());
    when(dispatcher.dispatch(eq(RECONFIGURE), anyString(), any(InvocationProvenance.class),
        eq(Optional.empty()), eq(fixture.engineContext), eq(input.operationKey())))
        .thenReturn(OperationResult.failure("Component replacement failed", "CONFIG_APPLY_FAILED",
            Map.of(), false));

    new SettingsController(null, Path.of("."), null, service, dispatcher, RECONFIGURE)
        .handleUpdateSettingsV2(fixture.context);

    assertEquals(500, status.get());
    @SuppressWarnings("unchecked")
    Map<String, Object> response = (Map<String, Object>) responseBody.get();
    assertEquals("CONFIG_APPLY_FAILED", response.get("errorCode"));
    assertEquals(input.operationKey(), response.get("operationKey"));
    verify(fixture.context, never()).status(200);
    verify(dispatcher, times(1)).dispatch(eq(RECONFIGURE), anyString(),
        any(InvocationProvenance.class), eq(Optional.empty()), eq(fixture.engineContext),
        eq(input.operationKey()));
    verifyNoInteractions(service);
  }

  @Test
  void generationBoundRefusalNamesKeysAndTheSeparateReindexOperation() {
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    SettingsV2 input = fullSettings();
    ContextFixture fixture = contextFixture(input);
    AtomicReference<Object> responseBody = new AtomicReference<>();
    doAnswer(call -> {
      responseBody.set(call.getArgument(0));
      return fixture.context;
    }).when(fixture.context).json(any());
    when(dispatcher.dispatch(eq(RECONFIGURE), anyString(), any(InvocationProvenance.class),
        eq(Optional.empty()), eq(fixture.engineContext), eq(input.operationKey())))
        .thenReturn(OperationResult.failure("Reindex required", "GENERATION_BOUND_REQUIRES_REINDEX",
            Map.of("keys", List.of("justsearch.embed.onnx.model_path"),
                "operation", "core.bulk-reindex"), false));

    new SettingsController(null, Path.of("."), null, null, dispatcher, RECONFIGURE)
        .handleUpdateSettingsV2(fixture.context);

    verify(fixture.context).status(409);
    @SuppressWarnings("unchecked")
    Map<String, Object> response = (Map<String, Object>) responseBody.get();
    assertEquals(List.of("justsearch.embed.onnx.model_path"), response.get("keys"));
    assertEquals("core.bulk-reindex", response.get("operation"));
    assertEquals("GENERATION_BOUND_REQUIRES_REINDEX", response.get("errorCode"));
  }

  @Test
  void completedRestartRequiredReceiptExposesRestartScheduled() {
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    SettingsV2 input = fullSettings();
    ContextFixture fixture = contextFixture(input);
    AtomicReference<Object> responseBody = new AtomicReference<>();
    doAnswer(call -> {
      responseBody.set(call.getArgument(0));
      return fixture.context;
    }).when(fixture.context).json(any());
    var data = new java.util.LinkedHashMap<>(responseData(input));
    data.put("restartScheduled", true);
    when(dispatcher.dispatch(eq(RECONFIGURE), anyString(), any(InvocationProvenance.class),
        eq(Optional.empty()), eq(fixture.engineContext), eq(input.operationKey())))
        .thenReturn(OperationResult.success("Settings committed", data));

    new SettingsController(null, Path.of("."), null, null, dispatcher, RECONFIGURE)
        .handleUpdateSettingsV2(fixture.context);

    assertEquals(Boolean.TRUE, ((SettingsV2) responseBody.get()).restartScheduled());
  }

  @Test
  void missingWitnessPreparationRefusalIsHttp400() {
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    SettingsV2 complete = fullSettings();
    SettingsV2 missingWitness = new SettingsV2(complete.ui(), complete.llm(),
        complete.indexPaths(), complete.settingsMode(), null, complete.operationKey(),
        complete.state(), complete.apiPort());
    ContextFixture fixture = contextFixture(missingWitness);
    AtomicReference<Object> responseBody = new AtomicReference<>();
    doAnswer(call -> {
      responseBody.set(call.getArgument(0));
      return fixture.context;
    }).when(fixture.context).json(any());
    when(dispatcher.dispatch(eq(RECONFIGURE), anyString(), any(InvocationProvenance.class),
        eq(Optional.empty()), eq(fixture.engineContext), eq(missingWitness.operationKey())))
        .thenReturn(OperationResult.failure("Missing settings witness", "BAD_REQUEST", Map.of(), false));

    new SettingsController(null, Path.of("."), null, null, dispatcher, RECONFIGURE)
        .handleUpdateSettingsV2(fixture.context);

    verify(fixture.context).status(400);
    @SuppressWarnings("unchecked")
    Map<String, Object> response = (Map<String, Object>) responseBody.get();
    assertEquals("BAD_REQUEST", response.get("errorCode"));
    assertEquals(missingWitness.operationKey(), response.get("operationKey"));
  }

  private static SettingsV2 fullSettings() {
    String previousKey = OperationKeys.generate(Clock.systemUTC());
    String operationKey = OperationKeys.generate(Clock.systemUTC());
    return new SettingsV2(
        new UiSettingsV2("dark", true, "compact", true, "reveal", 431, true, "advanced",
            true, List.of("*.tmp", "node_modules/**"), true),
        new LlmSettingsV2("llama-server.exe", 8192, 2048, 35, "C:/models/chat.gguf",
            "C:/models/llama.dll"),
        List.of("C:/docs", "D:/papers"), "read_write", new SettingsWitness(7, previousKey),
        operationKey, "COMPLETE", 43123);
  }

  private static Map<String, Object> responseData(SettingsV2 settings) {
    return Map.of("ui", settings.ui(), "llm", settings.llm(), "indexPaths", settings.indexPaths(),
        "settingsMode", settings.settingsMode(), "witness", settings.witness(),
        "operationKey", settings.operationKey(), "state", settings.state(), "apiPort",
        settings.apiPort());
  }

  private static ContextFixture contextFixture(SettingsV2 input) {
    Context context = mock(Context.class);
    var engineContext = TestRequestContexts.browser();
    when(context.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(engineContext);
    when(context.body()).thenReturn(JSON.writeValueAsString(input));
    when(context.path()).thenReturn("/api/settings/v2");
    when(context.method()).thenReturn(HandlerType.POST);
    when(context.header(SettingsController.UI_MODE_INTENT_HEADER)).thenReturn(null);
    when(context.status(anyInt())).thenReturn(context);
    return new ContextFixture(context, engineContext);
  }

  private record ContextFixture(Context context, io.justsearch.core.context.EngineContext engineContext) {}
}
