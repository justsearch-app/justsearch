/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

final class ExclusionSaveHttpTest {
  @TempDir Path directory;

  @Test void S326InputExpansionAndSyntaxRefusalsPreserveSavedPolicyThroughHttp() throws Exception {
    var json = JsonMapper.builder().build();
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
    // Validation precedes reservation: invalid requests must not need or call an operation runner.
    var service = new SettingsServiceImpl(settings, org.mockito.Mockito.mock(
        io.justsearch.app.api.operations.OperationAttemptRunner.class));
    var controller = new SettingsController(settings, directory, null, service);
    var server = io.javalin.Javalin.create(configure -> {
      configure.showJavalinBanner = false;
      configure.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    }).post("/api/settings/v2", controller::handleUpdateSettingsV2).start(0);
    try {
      var original = settings.inspect();
      List<List<String>> invalidLists = List.of(
          IntStream.range(0, 513).mapToObj(i -> "file" + i + ".txt").toList(),
          IntStream.range(0, 257).mapToObj(i -> "directory" + i).toList(),
          List.of("**/[broken"));
      for (var patterns : invalidLists) {
        String body = json.writeValueAsString(Map.of("ui", Map.of("excludePatterns", patterns),
            "witness", original.witness(), "operationKey", OperationKeys.generate(Clock.systemUTC())));
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + server.port() + "/api/settings/v2"))
            .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode(), response.body());
        assertEquals("INVALID_REQUEST", json.readTree(response.body()).path("errorCode").asText());
        assertEquals(original.witness(), settings.inspect().witness());
        assertEquals(original.settings().getExcludePatterns(), settings.inspect().settings().getExcludePatterns());
      }
    } finally { server.stop(); }
  }
}
