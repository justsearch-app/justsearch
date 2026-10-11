/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;
import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
class StatusChangeCounterHttpTest {
 @Test void statusCounterResponseReflectsACompletedSampleAndNeverSamplesForMetadata() throws Exception {
  try (var fixture = new StatusIsolationHttpTest.Fixture(false);
      var http = HttpClient.newHttpClient()) {
   // The production-wide response decorator also applies to unrelated read-only responses.
   try { Class.forName("io.justsearch.ui.api.StatusChangeHeaders").getDeclaredMethod("install", io.javalin.Javalin.class).invoke(null, fixture.app); }
   catch (ClassNotFoundException baseline) { /* baseline must fail by assertion below */ }
   fixture.app.get("/api/counter-read", ctx -> ctx.json(java.util.Map.of("ok", true)));
   var base = "http://127.0.0.1:" + fixture.app.port();
   var response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/counter-read")).GET().build(), HttpResponse.BodyHandlers.ofString());
   assertTrue(response.headers().firstValue("X-JustSearch-Status-Change").isPresent(), "all API reads must carry the owner-declared counter");
   var epoch = response.headers().firstValue("X-JustSearch-Status-Epoch").orElseThrow();
   long counter = Long.parseLong(response.headers().firstValue("X-JustSearch-Status-Change").orElseThrow());
   int calls = fixture.calls.get();
   var again = http.send(HttpRequest.newBuilder(URI.create(base + "/api/counter-read")).GET().build(), HttpResponse.BodyHandlers.ofString());
   assertEquals(counter, Long.parseLong(again.headers().firstValue("X-JustSearch-Status-Change").orElseThrow()));
   assertEquals(calls, fixture.calls.get(), "reading counter metadata must never sample a producer");
   var status = http.send(HttpRequest.newBuilder(URI.create(base + "/api/status?fresh=true")).GET().build(), HttpResponse.BodyHandlers.ofString());
   assertEquals(200, status.statusCode());
   assertEquals(epoch, status.headers().firstValue("X-JustSearch-Status-Epoch").orElseThrow());
   assertTrue(Long.parseLong(status.headers().firstValue("X-JustSearch-Status-Reflected").orElseThrow()) <=
       Long.parseLong(status.headers().firstValue("X-JustSearch-Status-Change").orElseThrow()));
  }
 }
}
