/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Task b6d536 b101: the disk-encryption state is read at most once per ten minutes, so
 * {@code /api/status} says when the value it shows was read: {@code atRestProtection.probedAtMs},
 * epoch milliseconds, next to the value. Real status route over HTTP and the real disk-encryption
 * probe of the data volume; runs unchanged on b599d4d7e, whose view had no read time.
 */
final class StatusAtRestReadTimeHttpTest {

  @Test void statusSaysWhenTheDiskEncryptionValueWasRead() throws Exception {
    long jvmStarted = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
    try (var fixture = new StatusIsolationFixtureSupport.Fixture(false);
        HttpClient client = HttpClient.newHttpClient()) {
      var response = client.send(HttpRequest.newBuilder(
              URI.create("http://127.0.0.1:" + fixture.app.port() + "/api/status"))
          .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
      long answeredBy = System.currentTimeMillis();
      assertEquals(200, response.statusCode(), response.body());
      JsonNode atRest = JsonMapper.builder().build().readTree(response.body()).path("atRestProtection");
      assertTrue(atRest.path("diskEncryption").isString(), "the value is there: " + atRest);
      JsonNode readAt = atRest.path("probedAtMs");
      assertTrue(readAt.isIntegralNumber(), "the value carries when it was read: " + atRest);
      assertTrue(readAt.asLong() >= jvmStarted && readAt.asLong() <= answeredBy,
          "read during this process, before the answer: " + readAt + " not in [" + jvmStarted + ", "
              + answeredBy + "]");
    }
  }
}
