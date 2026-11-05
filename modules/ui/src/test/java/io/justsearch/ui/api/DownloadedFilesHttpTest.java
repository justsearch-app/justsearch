/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.Javalin;
import io.justsearch.app.api.AiInstallService;
import io.justsearch.app.api.DownloadedFiles;
import io.justsearch.app.api.InstallPlanPreview;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.ui.api.routes.AiRoutes;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Design D4 over real HTTP and the production route table: Advanced settings reads its Downloaded
 * files rows from {@code GET /api/ai/install/downloaded-files}, and "Download again" previews the
 * plan with {@code ?include=} without any saved choice being changed on the way.
 */
final class DownloadedFilesHttpTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private Javalin app;
  private final List<String> calls = new ArrayList<>();

  @AfterEach
  void stop() {
    if (app != null) app.stop();
  }

  /** The install owner answers by method, so every call that reaches it is visible. */
  private void start() {
    AiInstallService service = mock(AiInstallService.class, invocation -> {
      String name = invocation.getMethod().getName();
      calls.add(name + (invocation.getArguments().length == 0 ? ""
          : " " + java.util.Arrays.toString(invocation.getArguments())));
      return switch (name) {
        case "downloadedFiles" -> {
          var view = new DownloadedFiles();
          var part = new DownloadedFiles.Part();
          part.id = "reranker";
          part.reason = "Switch Best match first off first";
          view.parts.add(part);
          yield view;
        }
        case "previewInstallPlan" -> {
          var preview = new InstallPlanPreview();
          var component = new InstallPlanPreview.ComponentEstimate();
          component.id = "reranker";
          component.state = invocation.getArguments().length == 0 ? "declined" : "to-download";
          preview.components.add(component);
          yield preview;
        }
        default -> null;
      };
    });
    ChatController chat = mock(ChatController.class);
    when(chat.handler(any(), any())).thenReturn(ctx -> {});
    when(chat.dynamicHandler(any())).thenReturn(ctx -> {});
    app = Javalin.create(cfg -> {
      cfg.showJavalinBanner = false;
      cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    AiRoutes.register(app, mock(PreviewController.class),
        new AiInstallController(service, mock(Telemetry.class)), mock(AiPackController.class),
        mock(AiRuntimeController.class), mock(AiModelsController.class), chat);
    app.start("127.0.0.1", 0);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
            .timeout(Duration.ofSeconds(5)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void theDownloadedFilesRowsAreServedFromTheInstallOwner() throws Exception {
    start();

    HttpResponse<String> response = get("/api/ai/install/downloaded-files");

    assertEquals(200, response.statusCode(), response.body());
    JsonNode part = MAPPER.readTree(response.body()).path("parts").path(0);
    assertEquals("reranker", part.path("id").asString());
    assertEquals("Switch Best match first off first", part.path("reason").asString());
    assertEquals(List.of("downloadedFiles"), calls);
  }

  @Test
  void downloadAgainPreviewsTheIncludedPartWithoutSavingAnyChoice() throws Exception {
    start();

    HttpResponse<String> response = get("/api/ai/install/plan-preview?include=reranker");

    assertEquals(200, response.statusCode(), response.body());
    assertEquals("to-download", MAPPER.readTree(response.body())
        .path("components").path(0).path("state").asString());
    assertEquals(List.of("previewInstallPlan [[reranker]]"), calls,
        "only the preview ran: no decline was withdrawn on the way");
    assertTrue(calls.stream().noneMatch(call -> call.startsWith("setPackageDeclined")));
  }
}
