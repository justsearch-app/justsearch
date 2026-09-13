package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class McpToolLifecycleTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final Instant DEPRECATED_SINCE = Instant.parse("2026-09-01T00:00:00Z");
  private static final Instant SUNSET_AT = Instant.parse("2026-12-01T00:00:00Z");

  @Test
  void fakeLifecycleProjectsNamespacedMetadataAndDescriptionFallback() {
    var surface =
        new McpToolSurface(
            List.of(fakeTool("fake_old"), fakeTool("fake_replacement")),
            List.of(
                new McpToolSurface.ToolLifecycle(
                    "fake_old", DEPRECATED_SINCE, SUNSET_AT, "fake_replacement")));

    Map<String, Object> oldTool = listedTools(surface).get(0);
    assertTrue(
        ((String) oldTool.get("description"))
            .startsWith(
                "Deprecated since 2026-09-01T00:00:00Z; use fake_replacement instead. "));

    @SuppressWarnings("unchecked")
    Map<String, Object> metadata = (Map<String, Object>) oldTool.get("_meta");
    assertEquals(Boolean.TRUE, metadata.get("io.justsearch/deprecated"));
    assertEquals("2026-09-01T00:00:00Z", metadata.get("io.justsearch/deprecatedSince"));
    assertEquals("2026-12-01T00:00:00Z", metadata.get("io.justsearch/sunsetAt"));
    assertEquals("fake_replacement", metadata.get("io.justsearch/replacement"));
    assertFalse(oldTool.containsKey("io.justsearch/deprecated"));
    assertFalse(
        listedTools(surface).get(1).containsKey("_meta"),
        "only catalogued tools receive lifecycle metadata");
  }

  @Test
  void lifecycleProjectionLeavesStandardAnnotationsSemanticallyUnchanged() {
    var annotations = new LinkedHashMap<String, Object>();
    annotations.put("readOnlyHint", false);
    annotations.put("idempotentHint", true);
    var definition = fakeTool("fake_old", annotations);
    var surface =
        new McpToolSurface(
            List.of(definition),
            List.of(
                new McpToolSurface.ToolLifecycle(
                    "fake_old", DEPRECATED_SINCE, null, "fake_replacement")));

    Map<String, Object> projected = listedTools(surface).get(0);
    @SuppressWarnings("unchecked")
    Map<String, Object> projectedAnnotations =
        (Map<String, Object>) projected.get("annotations");
    assertEquals(annotations, projectedAnnotations);
    assertEquals(List.copyOf(annotations.keySet()), List.copyOf(projectedAnnotations.keySet()));
    assertFalse(projectedAnnotations.keySet().stream().anyMatch(key -> key.startsWith("io.justsearch/")));

    @SuppressWarnings("unchecked")
    Map<String, Object> metadata = (Map<String, Object>) projected.get("_meta");
    assertFalse(metadata.containsKey("io.justsearch/sunsetAt"), "sunsetAt remains optional");
  }

  @Test
  void productionLifecycleCatalogIsEmpty() {
    var surface =
        new McpToolSurface(List.of(), null, () -> null, () -> null, java.time.Clock.systemUTC());

    assertEquals(7, listedTools(surface).size());
    assertTrue(
        listedTools(surface).stream().noneMatch(tool -> tool.containsKey("_meta")),
        "no production tool is deprecated");
  }

  @Test
  void initializeAdvertisesVersionedLifecycleCapabilityFromSurface() throws Exception {
    var surface = new McpToolSurface(List.of(fakeTool("fake_tool")), List.of());
    var handler = new McpProtocolHandler(surface, List.of());
    Context context = mock(Context.class);
    when(context.path()).thenReturn("/mcp");
    when(context.body())
        .thenReturn("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(context.result(resultCaptor.capture())).thenReturn(context);
    when(context.contentType(anyString())).thenReturn(context);

    handler.handlePost(context);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(resultCaptor.getValue(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    @SuppressWarnings("unchecked")
    Map<String, Object> capabilities = (Map<String, Object>) result.get("capabilities");
    @SuppressWarnings("unchecked")
    Map<String, Object> experimental = (Map<String, Object>) capabilities.get("experimental");
    assertEquals(
        Map.of("version", "1.0"),
        experimental.get("io.justsearch/tool-lifecycle"));
  }

  @Test
  void duplicateLifecycleCatalogKeysAreRejected() {
    var lifecycle =
        new McpToolSurface.ToolLifecycle(
            "fake_old", DEPRECATED_SINCE, null, "fake_replacement");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> new McpToolSurface(List.of(fakeTool("fake_old")), List.of(lifecycle, lifecycle)));
    assertTrue(error.getMessage().contains("Duplicate MCP lifecycle catalog key: fake_old"));
  }

  @Test
  void orphanedOrMisspelledLifecycleToolIsRejected() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new McpToolSurface(
                    List.of(fakeTool("fake_live")),
                    List.of(
                        new McpToolSurface.ToolLifecycle(
                            "fake_lvie", DEPRECATED_SINCE, null, "fake_replacement"))));
    assertTrue(error.getMessage().contains("fake_lvie resolved 0 times"));
  }

  @Test
  void duplicateLiveToolResolutionIsRejected() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new McpToolSurface(
                    List.of(fakeTool("fake_old"), fakeTool("fake_old")),
                    List.of(
                        new McpToolSurface.ToolLifecycle(
                            "fake_old", DEPRECATED_SINCE, null, "fake_replacement"))));
    assertTrue(error.getMessage().contains("fake_old resolved 2 times"));
  }

  @Test
  void lifecycleChronologyIsValidated() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new McpToolSurface.ToolLifecycle(
                    "fake_old", DEPRECATED_SINCE, DEPRECATED_SINCE, "fake_replacement"));
    assertTrue(error.getMessage().contains("sunsetAt must be after deprecatedSince"));
  }

  private static McpToolSurface.ToolDefinition fakeTool(String name) {
    return fakeTool(name, Map.of("readOnlyHint", true));
  }

  private static McpToolSurface.ToolDefinition fakeTool(
      String name, Map<String, Object> annotations) {
    return new McpToolSurface.ToolDefinition(
        name,
        "Original description for " + name + ".",
        Map.of("type", "object", "properties", Map.of()),
        annotations);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> listedTools(McpToolSurface surface) {
    return (List<Map<String, Object>>) surface.listTools().get("tools");
  }
}
