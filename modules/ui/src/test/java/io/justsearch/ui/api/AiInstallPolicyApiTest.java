/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.javalin.Javalin;
import io.justsearch.app.services.ai.install.AiInstallService;
import io.justsearch.telemetry.Telemetry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

final class AiInstallPolicyApiTest {
  @TempDir Path home;

  @Test
  void snapshotExceptionRefusesStartAndRepairOnTheWire() throws Exception {
    var service = new AiInstallService(null, null, null,
        () -> { throw new IllegalStateException("policy unavailable"); }, home);
    var controller = new AiInstallController(service, mock(Telemetry.class));
    var app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    }).post("/api/ai/install/start", controller::handleStart)
        .post("/api/ai/install/repair", controller::handleRepair).start(0);
    try {
      var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
      for (String endpoint : new String[] {"start", "repair"}) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
            + "/api/ai/install/" + endpoint)).timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"acceptTerms\":true}")).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode());
        var body = JsonMapper.builder().build().readTree(response.body());
        assertEquals("DOWNLOADS_DISABLED", body.path("errorCode").asText());
        assertEquals("Downloads disabled by administrator policy.", body.path("error").asText());
      }
    } finally { app.stop(); }
    assertFalse(Files.exists(home.resolve("install-contract.v2.json")));
  }
}
