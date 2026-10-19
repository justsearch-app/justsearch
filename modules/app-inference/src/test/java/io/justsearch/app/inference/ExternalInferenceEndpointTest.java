/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Ruling CH4 (ADR-0051 decision 2): {@code justsearch.inference.external_endpoint} is the only way
 * to use a llama-server JustSearch did not start. An unusable value is refused with a reason, and a
 * server found on the configured port without it is reported as {@code external_server_ignored}.
 */
final class ExternalInferenceEndpointTest {
  @Test
  void blankMeansJustSearchRunsItsOwnServer() {
    assertEquals(Optional.empty(), ExternalInferenceEndpoint.parse(null));
    assertEquals(Optional.empty(), ExternalInferenceEndpoint.parse("  "));
  }

  @Test
  void loopbackHttpUrlWithPortIsAccepted() {
    assertEquals(
        Optional.of(new ExternalInferenceEndpoint("http://127.0.0.1:8080", 8080)),
        ExternalInferenceEndpoint.parse("http://127.0.0.1:8080"));
    assertEquals(8081, ExternalInferenceEndpoint.parse(" http://localhost:8081/ ").orElseThrow().port());
  }

  @Test
  void unusableValuesAreRefusedWithAReason() {
    for (String value :
        new String[] {
          "http://192.168.1.5:8080", "https://127.0.0.1:8080", "http://127.0.0.1",
          "http://127.0.0.1:0", "http://127.0.0.1:8080/v1", "http://127.0.0.1:8080?x=1", "8080",
        }) {
      IllegalArgumentException refused =
          assertThrows(IllegalArgumentException.class, () -> ExternalInferenceEndpoint.parse(value), value);
      assertTrue(refused.getMessage().contains("is refused"), refused.getMessage());
      assertTrue(refused.getMessage().contains(ExternalInferenceEndpoint.CONFIG_KEY), refused.getMessage());
    }
  }

  @Test
  void refusedEndpointFailsTheStartAsInvalidConfiguration(@TempDir Path tmp) {
    LlamaServerOps ops = ops();
    try {
      ModeTransitionException failure =
          assertThrows(
              ModeTransitionException.class,
              () -> ops.startLlamaServer(request(tmp, 1, "http://example.com:8080")));
      assertEquals(ModeTransitionException.Reason.INVALID_CONFIG, failure.reason());
    } finally {
      ops.shutdown();
    }
  }

  @Test
  void serverOnConfiguredPortWithoutEndpointIsReportedAsIgnored(@TempDir Path tmp) throws Exception {
    HttpServer other = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    other.createContext("/health", exchange -> {
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    other.start();
    LlamaServerOps ops = ops();
    try {
      assertNull(ops.externalServerNote());
      // No executable exists, so the managed launch fails after the configured port was checked.
      ModeTransitionException failure =
          assertThrows(
              ModeTransitionException.class,
              () -> ops.startLlamaServer(request(tmp, other.getAddress().getPort(), null)));
      assertEquals(ModeTransitionException.Reason.EXECUTABLE_NOT_FOUND, failure.reason());
      assertEquals(LlamaServerOps.EXTERNAL_SERVER_IGNORED_NOTE, ops.externalServerNote());
      assertEquals("external_server_ignored", ops.externalServerNote());

      other.stop(0);
      assertThrows(
          ModeTransitionException.class,
          () -> ops.startLlamaServer(request(tmp, other.getAddress().getPort(), null)));
      assertNull(ops.externalServerNote(), "the note clears once nothing answers there");
    } finally {
      other.stop(0);
      ops.shutdown();
    }
  }

  private static LlamaServerOps ops() {
    return new LlamaServerOps(
        new InferenceExecutorRegistrations(new io.justsearch.core.execution.TestEngineExecutors()),
        HttpClient.newHttpClient(), new ObjectMapper(), null, () -> Mode.OFFLINE,
        new NoopPropsObserver(), ignored -> {}, (ignored, guard) -> {},
        NoopInferenceTelemetryEvents.INSTANCE, ManagedChildRegistry.noop());
  }

  private static LlamaServerOps.StartRequest request(Path tmp, int configuredPort, String endpoint) {
    Map<String, String> entries = new HashMap<>();
    entries.put("justsearch.data.dir", tmp.resolve("data").toString());
    if (endpoint != null) entries.put(ExternalInferenceEndpoint.CONFIG_KEY, endpoint);
    InferenceConfig config =
        new InferenceConfig(
            tmp.resolve("missing-llama-server.exe"), tmp.resolve("model.gguf"), null,
            configuredPort, 4096, 0, false);
    return new LlamaServerOps.StartRequest(
        new LlamaServerConfigContext(config, TestResolvedConfigHelper.fromEntries(entries)),
        LlamaServerOps.AdoptionPolicy.LEGACY_ALLOW_EXTERNAL);
  }

  private static final class NoopPropsObserver implements PropsObserver {
    @Override
    public void onModelIdObserved(String modelId, LlamaServerConfigContext context) {}
    @Override public void onContextTokensObserved(int contextTokens) {}
    @Override public String observedModelId() { return null; }
    @Override public Integer observedContextTokens() { return null; }
  }
}
