/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.engine.EngineKnowledgeClient;
import io.justsearch.app.engine.ForegroundLoadGate;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.StatusResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
final class EngineObserverPacingTest {
  @Test
  void observerRequestsThroughRealFiltersNeverStartForegroundWork() throws Exception {
    var services = mock(WorkerAppServices.class);
    var ingest = mock(WorkerIngestService.class);
    var search = mock(WorkerSearchService.class);
    when(services.ingestService()).thenReturn(ingest);
    when(services.searchService()).thenReturn(search);
    when(ingest.indexStatus(any(), any())).thenReturn(StatusResponse.getDefaultInstance());
    when(search.search(any(), any())).thenReturn(SearchResponse.getDefaultInstance());
    var load = new ForegroundLoad();
    var paths = List.of("/api/knowledge/status", "/api/status", "/api/health",
        "/api/health/events/stream", "/api/debug/state", "/api/diagnostics",
        "/api/diagnostics/ingestion/summary");
    var app = Javalin.create(config -> config.showJavalinBanner = false);
    try (var registry = new TestEngineExecutors();
        var events = Executors.newSingleThreadExecutor();
        var client = new EngineKnowledgeClient(registry, () -> services, new ForegroundLoadGate(load),
            5_000, 100, IpcTelemetry.noop());
        var http = HttpClient.newHttpClient()) {
      // The public component constructor creates its admission owner. The front must share that
      // exact owner, just as EngineRoot wires it, so the real client checks the stamped work id.
      var admissionField = EngineKnowledgeClient.class.getDeclaredField("admission");
      admissionField.setAccessible(true);
      var admission = (EngineAdmissionController) admissionField.get(client);
      new ApiSecurityFilters(false, null, new EventBuffer(), events, null, admission, admission).install(app);
      for (String path : paths) {
        app.get(path, ctx -> {
          client.getStatus(RequestEngineContext.get(ctx));
          ctx.result("observed");
        });
      }
      app.get("/api/knowledge/search", ctx -> {
        client.search("probe", 1, RequestEngineContext.get(ctx));
        ctx.result("searched");
      });
      app.start("127.0.0.1", 0);
      for (String caller : List.of("WEBVIEW", "MCP_CLIENT")) {
        for (String path : paths) {
          var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
              .header("X-JustSearch-Client-Kind", caller).GET().build(), HttpResponse.BodyHandlers.ofString());
          assertEquals(200, response.statusCode(), response.body());
          assertEquals(0, load.startedTotal(), caller + " observer " + path + " must not throttle indexing");
        }
      }
      var response = http.send(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + app.port() + "/api/knowledge/search")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals(1, load.startedTotal(), "real search still feeds the same gauge");
      verify(ingest, times(paths.size() * 2)).indexStatus(any(), any());
    } finally {
      app.stop();
    }
  }
}
