/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.runtime;

import static io.justsearch.ui.api.ContractSchemaAssertions.assertConforms;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.Javalin;
import io.justsearch.app.api.runtime.RuntimeContract;
import io.justsearch.app.api.runtime.RuntimeManifest;
import io.justsearch.app.api.runtime.RuntimeManifestBuilder;
import io.justsearch.app.api.runtime.RuntimeManifestHeadInfoBuilder;
import io.justsearch.ui.api.routes.RuntimeApiRoutes;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuntimeClientHttpContractTest {
  private final AtomicReference<RuntimeManifest> current = new AtomicReference<>();
  private Javalin app;
  private HttpClient client;

  @BeforeEach
  void start() {
    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.manifestPath()).thenReturn(Path.of("build", "runtime-client-http", "manifest.json"));
    when(publisher.current()).thenAnswer(ignored -> current.get());
    app =
        Javalin.create(
            config -> {
              config.showJavalinBanner = false;
              config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
            });
    new RuntimeApiRoutes(new io.justsearch.core.execution.TestEngineExecutors(), publisher).register(app);
    app.start("127.0.0.1", 0);
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  @AfterEach
  void stop() {
    if (app != null) app.stop();
  }

  @Test
  void manifestAndWellKnownHandlersSerializeThePublicSchema() throws Exception {
    current.set(manifest("LIFECYCLE_STATE_READY"));
    assertGet("/api/runtime/manifest", 200, "runtime-manifest-public.v2.json");
    assertGet("/.well-known/justsearch/manifest.json", 200, "runtime-manifest-public.v2.json");

    current.set(null);
    assertGet("/api/runtime/manifest", 503, "api-error-response.v1.json");
    assertGet("/.well-known/justsearch/manifest.json", 503, "api-error-response.v1.json");
  }

  @Test
  void readinessAndLivenessHandlersPreserveLifecycleStatuses() throws Exception {
    current.set(manifest("LIFECYCLE_STATE_READY"));
    assertGet("/api/runtime/ready", 200, "runtime-ready-response.v1.json");
    assertGet("/api/runtime/live", 200, "runtime-live-response.v1.json");

    current.set(manifest("LIFECYCLE_STATE_STARTING"));
    assertGet("/api/runtime/ready", 503, "runtime-ready-response.v1.json");

    current.set(null);
    assertGet("/api/runtime/ready", 503, "runtime-ready-response.v1.json");
    assertGet("/api/runtime/live", 200, "runtime-live-response.v1.json");
  }

  @Test
  void manifestSerializationFailureIsSanitizedOverHttp() throws Exception {
    app.stop();
    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.current()).thenReturn(manifest("LIFECYCLE_STATE_READY"));
    String hostile = "token=secret at C:\\Users\\victim\\private\\manifest.json";
    RuntimeManifestController controller =
        new RuntimeManifestController(
            publisher,
            ignored -> {
              throw new IllegalStateException(hostile);
            });
    app =
        Javalin.create(
            config -> {
              config.showJavalinBanner = false;
              config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
            });
    app.get("/api/runtime/manifest", controller::handleGet);
    app.start("127.0.0.1", 0);

    HttpResponse<String> response = get("/api/runtime/manifest");
    assertEquals(500, response.statusCode(), response.body());
    assertFalse(response.body().contains("secret"));
    assertFalse(response.body().contains("victim"));
    assertConforms(
        "GET /api/runtime/manifest status 500", "api-error-response.v1.json", response.body());
  }

  private void assertGet(String path, int expectedStatus, String schema) throws Exception {
    HttpResponse<String> response = get(path);
    assertEquals(expectedStatus, response.statusCode(), response.body());
    assertConforms("GET " + path + " status " + expectedStatus, schema, response.body());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static RuntimeManifest manifest(String lifecycle) {
    return RuntimeManifestBuilder.builder()
        .schemaVersion(RuntimeManifest.CURRENT_SCHEMA_VERSION)
        .instanceId("sdk-http-instance")
        .pid(1234L)
        .startedAt("2026-09-03T00:00:00Z")
        .dataDir("C:\\private-runtime-dir")
        .lifecycle(lifecycle)
        .head(
            RuntimeManifestHeadInfoBuilder.builder()
                .apiPort(33221)
                .apiBaseUrl("http://127.0.0.1:33221")
                .sessionToken("must-not-escape")
                .readyAt("2026-09-03T00:00:01Z")
                .build())
        .runtimeContract(RuntimeContract.current())
        .build();
  }
}
