/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.*;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.app.services.intent.PendingAuthorizationStore;
import io.justsearch.ui.api.TestRequestContexts;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class McpOperationKeyTest {
  private static final String KEY = "019940e2-3400-7000-8000-000000000001";
  private final OperationDispatcher dispatcher = mock(OperationDispatcher.class);
  private final PendingAuthorizationStore pending = mock(PendingAuthorizationStore.class);
  private final McpToolSurface surface = new McpToolSurface(
      List.of(new AgentToolsOperationCatalog()), dispatcher, () -> null, () -> null,
      Clock.systemUTC(), () -> null, pending, null);

  @ParameterizedTest
  @ValueSource(strings = {"justsearch_ingest", "justsearch_browse"})
  void keyIsDeliveredSeparatelyFromPublicArguments(String tool) {
    when(dispatcher.dispatch(any(), any(), any(), any(), any(), eq(KEY)))
        .thenReturn(OperationResult.success("recorded", Map.of("operationKey", KEY)));
    Map<String, Object> args = new LinkedHashMap<>();
    if (tool.equals("justsearch_ingest")) args.put("paths", List.of("C:/notes"));
    args.put("operationKey", KEY);
    var response = surface.callTool(tool, args, "session", TestRequestContexts.mcp("session"));
    assertEquals(false, response.get("isError"));
    var publicJson = ArgumentCaptor.forClass(String.class);
    verify(dispatcher).dispatch(any(), publicJson.capture(), any(), eq(Optional.empty()), any(), eq(KEY));
    assertFalse(JsonMapper.builder().build().readTree(publicJson.getValue()).has("operationKey"));
    assertEquals(KEY, args.get("operationKey"), "caller map is not mutated");
    assertTrue(response.toString().contains(KEY));
  }

  @Test
  void approvalRetainsKeyAndPublicArguments() {
    when(dispatcher.dispatch(any(), any(), any(), any(), any(), eq(KEY)))
        .thenThrow(new ConfirmationRequiredException(AgentToolsOperationCatalog.INGEST_FILES,
            GateBehavior.TYPED_CONFIRM,
            ConfirmStrategy.typedForId(AgentToolsOperationCatalog.INGEST_FILES), SourceTier.UNTRUSTED));
    when(pending.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
        .thenReturn("pending-key");
    var response = surface.callTool("justsearch_ingest",
        Map.of("paths", List.of("C:/notes"), "operationKey", KEY), "session", TestRequestContexts.mcp("session"));
    assertEquals(true, response.get("isError"));
    var publicJson = ArgumentCaptor.forClass(String.class);
    verify(pending).create(eq("core.ingest-files"), publicJson.capture(), any(), any(), any(), any(),
        isNull(), eq(TransportTag.MCP), any(), any(), eq(KEY), eq(false));
    assertFalse(JsonMapper.builder().build().readTree(publicJson.getValue()).has("operationKey"));
    assertTrue(response.toString().contains("JustSearch app"));
  }

  @ParameterizedTest
  @EnumSource(OperationStoreException.Code.class)
  void storeFailuresPreservePublicCodesAndHideNativeCause(OperationStoreException.Code code) {
    when(dispatcher.dispatch(any(), any(), any(), any(), any(), eq(KEY)))
        .thenThrow(new OperationStoreException(code, new IllegalStateException("private-path-and-payload")));
    var response = surface.callTool("justsearch_ingest",
        Map.of("paths", List.of("C:/notes"), "operationKey", KEY), "session", TestRequestContexts.mcp("session"));
    assertEquals(true, response.get("isError"));
    Map<?, ?> facts = (Map<?, ?>) response.get("structuredContent");
    String expected = switch (code) {
      case INVALID_OPERATION_KEY -> "OPERATION_KEY_INVALID";
      case OPERATION_EXPIRED -> "OPERATION_KEY_EXPIRED";
      case STORAGE_FAILED -> "OPERATION_STORAGE_FAILED";
      default -> code.name();
    };
    assertEquals(expected, facts.get("errorCode"));
    assertEquals(code == OperationStoreException.Code.OPERATIONS_CAPACITY, facts.get("retryable"));
    assertFalse(response.toString().contains("private-path-and-payload"));
  }

  @Test
  void failedUnkeyedAttemptStillReturnsItsReceiptInBothDeliveryTiers() {
    when(dispatcher.dispatch(any(), any(), any(), any()))
        .thenReturn(new OperationResult(false, "failed", Optional.empty(),
            Map.of("operationKey", KEY, "operationRecordId", 17L, "privatePayload", "secret"),
            Optional.of("EFFECT_FAILED"), Map.of(), Optional.of(false)));
    var response = surface.callTool("justsearch_ingest", Map.of("paths", List.of("C:/notes")),
        "session", TestRequestContexts.mcp("session"));
    Map<?, ?> facts = (Map<?, ?>) response.get("structuredContent");
    assertEquals(KEY, facts.get("operationKey"));
    assertEquals(17L, facts.get("operationRecordId"));
    assertTrue(response.get("content").toString().contains(KEY));
    assertTrue(response.get("content").toString().contains("17"));
    assertFalse(response.toString().contains("secret"));
  }

  @Test
  void nonStringKeyRefusesBeforeDispatch() {
    var response = surface.callTool("justsearch_ingest",
        Map.of("paths", List.of("C:/notes"), "operationKey", 7), "session", TestRequestContexts.mcp("session"));
    assertEquals(true, response.get("isError"));
    assertEquals("OPERATION_KEY_INVALID", ((Map<?, ?>) response.get("structuredContent")).get("errorCode"));
    verifyNoInteractions(dispatcher, pending);
  }

  @Test
  void toolsListAdvertisesOptionalTransportKeyOnlyForOperationTools() {
    List<?> tools = (List<?>) surface.listTools().get("tools");
    for (Object row : tools) {
      Map<?, ?> tool = (Map<?, ?>) row;
      Map<?, ?> schema = (Map<?, ?>) tool.get("inputSchema");
      Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
      boolean operation = List.of("justsearch_ingest", "justsearch_browse").contains(tool.get("name"));
      assertEquals(operation, properties.containsKey("operationKey"));
      if (operation) {
        assertEquals("string", ((Map<?, ?>) properties.get("operationKey")).get("type"));
        assertFalse(schema.get("required") != null
            && ((List<?>) schema.get("required")).contains("operationKey"));
      }
    }
  }
}
