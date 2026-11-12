/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.app.api.operations.ForgetService;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.ui.api.routes.IndexingRoutes;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Actual HTTP registrar/controller; the coordinator's durable inventory is covered in app-engine. */
final class InterruptedImportsHttpTest {
  @Test void retainedImportWithoutDocumentsIsListedWithItsFrozenScopeAndResumeRevision() throws Exception {
    var owner = mock(RecordedIngestionService.class);
    when(owner.hasOwner()).thenReturn(true);
    when(owner.interruptedImports()).thenReturn(List.of(new RecordedIngestionService.InterruptedImport(
        "01993ba0-0000-7000-8000-000000000001", 3, "RECOVERY_AUTHORIZATION_REFUSED",
        List.of(new RecordedRootPlan.Root(Path.of("/original/private"), "frozen", true, false,
            List.of("*.secret"), List.of())), true)));
    var app = server(owner);
    try {
      var response = get(app, null);
      assertEquals(200, response.statusCode());
      var item = JsonMapper.builder().build().readTree(response.body()).path("imports").get(0);
      assertEquals(3, item.path("interruptionRevision").asInt());
      assertEquals("RECOVERY_AUTHORIZATION_REFUSED", item.path("reason").asString());
      assertTrue(item.path("resumeAvailable").asBoolean());
      assertEquals("frozen", item.path("folders").get(0).path("collection").asString());
      assertTrue(item.path("folders").get(0).path("force").asBoolean());
      verify(owner).hasOwner();
      verify(owner).interruptedImports();
      verifyNoMoreInteractions(owner);
    } finally { app.stop(); }
  }

  @Test void unavailableInventoryIsAnExplicitFailureAndOtherCallersCannotReadTargets() throws Exception {
    var owner = mock(RecordedIngestionService.class);
    when(owner.hasOwner()).thenReturn(false);
    var app = server(owner);
    try {
      assertEquals(503, get(app, null).statusCode());
      for (String caller : List.of("MCP_CLIENT", "INTERNAL", "CLI", "PAIRED_DEVICE")) {
        assertEquals(403, get(app, caller).statusCode());
      }
      verify(owner, never()).interruptedImports();
    } finally { app.stop(); }
  }

  private static Javalin server(RecordedIngestionService owner) {
    var controller = new IndexingController(() -> null, null, null, null,
        ForgetService::unavailable, () -> owner);
    var app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    IndexingRoutes.register(app, controller);
    return app.start(0);
  }

  private static HttpResponse<String> get(Javalin app, String caller) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
        + "/api/indexing/imports/interrupted")).timeout(Duration.ofSeconds(5)).GET();
    if (caller != null) request.header("X-JustSearch-Client-Kind", caller);
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
