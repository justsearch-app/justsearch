/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.services.excludes.ExcludesServiceImpl;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ui.api.routes.IndexingRoutes;
import io.justsearch.ui.json.Jackson3JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ExclusionPolicyHttpTest {
  @TempDir Path root;
  private static final String KEY = "justsearch.ui.exclude_patterns";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test void OV1ReadPreviewAndApplyNameTheWinningPolicyWithoutChangingPrecedence() throws Exception {
    var previous = ConfigStore.globalOrNull();
    var indexing = mock(IndexingService.class);
    when(indexing.getWatchedRoots(any())).thenReturn(List.of(new IndexingService.WatchedRoot("docs", root)));
    when(indexing.deleteDocByIdWithOutcome(anyString(), any())).thenReturn(new IndexingService.DeletionOutcome(true, ""));
    for (String name : List.of("saved-only.txt", "env-only.txt", "system-only.txt")) Files.writeString(root.resolve(name), name);
    var controller = new IndexingController(() -> indexing, new ExcludesServiceImpl(() -> indexing), null, null);
    var app = Javalin.create(c -> { c.showJavalinBanner = false; c.jsonMapper(new Jackson3JsonMapper()); });
    try (var client = HttpClient.newHttpClient()) {
      IndexingRoutes.register(app, controller); app.start("127.0.0.1", 0);
      for (String source : List.of("settings.json", "env_var", "jvm_arg")) {
        var builder = ResolvedConfig.builder().put(KEY, 300, "settings.json", null, "[\"saved-only.txt\"]");
        if (!source.equals("settings.json")) builder.put(KEY, 400, "env_var", "JUSTSEARCH_UI_EXCLUDE_PATTERNS", "[\"env-only.txt\"]");
        if (source.equals("jvm_arg")) builder.put(KEY, 500, "jvm_arg", KEY, "[\"system-only.txt\"]");
        ConfigStore.setGlobal(new ConfigStore(builder.build()));
        String pattern = source.equals("settings.json") ? "saved-only.txt" : source.equals("env_var") ? "env-only.txt" : "system-only.txt";
        var policy = get(client, app.port(), "/api/indexing/excludes");
        verifyNoInteractions(indexing);
        assertEquals(source, policy.path("source").asText());
        assertEquals(List.of(pattern), JSON.convertValue(policy.path("patterns"), List.class));
        assertEquals(source.equals("env_var") ? "JUSTSEARCH_UI_EXCLUDE_PATTERNS" : source.equals("jvm_arg") ? KEY : "", policy.path("sourceDetail").asText(""));
        for (boolean preview : List.of(true, false)) {
          var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/indexing/excludes/apply?dryRun=" + preview))
              .POST(HttpRequest.BodyPublishers.noBody()).build();
          var response = client.send(request, HttpResponse.BodyHandlers.ofString());
          assertEquals(200, response.statusCode());
          var result = JSON.readTree(response.body());
          assertEquals(policy, result.path("effectivePolicy"), "OV1: response describes the actual matcher policy");
          assertEquals(pattern, result.path("perPattern").get(0).path("pattern").asText());
          assertEquals(1, result.path("matchedFiles").asInt());
        }
        verify(indexing).deleteDocByIdWithOutcome(eq(root.resolve(pattern).toString()), any());
        clearInvocations(indexing);
      }
    } finally { app.stop(); TestResolvedConfigHelper.restoreGlobal(previous); }
  }

  @Test void OV1OperationsNameTheEffectivePolicyEvenWithoutDiskMatches() throws Exception {
    var previous = ConfigStore.globalOrNull();
    var indexing = mock(IndexingService.class);
    when(indexing.getWatchedRoots(any())).thenReturn(List.of());
    ConfigStore.setGlobal(new ConfigStore(ResolvedConfig.builder()
        .put(KEY, 300, "settings.json", null, "[\"saved-only.txt\"]")
        .put(KEY, 400, "env_var", "JUSTSEARCH_UI_EXCLUDE_PATTERNS", "[\"env-only.txt\"]").build()));
    var service = new ExcludesServiceImpl(() -> indexing);
    try {
      var preview = new io.justsearch.app.services.registry.operations.handlers.PreviewExcludesHandler(() -> service);
      var apply = new io.justsearch.app.services.registry.operations.handlers.ApplyExcludesHandler(() -> service);
      for (io.justsearch.agent.api.registry.OperationHandler handler : List.of(preview, apply)) {
        var result = handler.execute("{}", null);
        assertTrue(result.success());
        JsonNode policy = JSON.valueToTree(result.structuredData()).path("effectivePolicy");
        assertEquals("env_var", policy.path("source").asText(), "OV1: Operations name the winning source");
        assertEquals("env-only.txt", policy.path("patterns").path(0).asText());
      }
    } finally { TestResolvedConfigHelper.restoreGlobal(previous); }
  }

  private static JsonNode get(HttpClient client, int port, String path) throws Exception {
    var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), "OV1: effective policy is available without walking disk");
    return JSON.readTree(response.body());
  }
}
