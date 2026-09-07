package io.justsearch.ui.api.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static io.justsearch.ui.api.ContractSchemaAssertions.assertConforms;

import io.javalin.http.Context;
import io.justsearch.app.api.runtime.RuntimeContract;
import io.justsearch.app.api.runtime.RuntimeManifest;
import io.justsearch.app.api.runtime.RuntimeManifestBuilder;
import io.justsearch.app.api.runtime.RuntimeManifestHeadInfoBuilder;
import io.justsearch.app.api.runtime.RuntimeManifestWorkerInfoBuilder;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tempdoc 501 §13.4.5 audience axis: HTTP-class transports (REST + SSE +
 * MCP + well-known) must serve {@code manifest.publicProjection()}. The
 * filesystem transport keeps the full record (FS-permission gated). This
 * test exercises {@link RuntimeManifest#publicProjection} directly so the
 * live-stack test (which runs prodMode=false and never produces a token)
 * cannot silently regress the projection.
 */
class RuntimeManifestControllerRedactionTest {

  @Test
  void serializationFailureUsesSanitizedStableEnvelope() throws Exception {
    RuntimeManifest manifest =
        RuntimeManifestBuilder.builder()
            .schemaVersion(1)
            .instanceId("inst-hostile")
            .pid(1234L)
            .startedAt("2026-09-03T00:00:00Z")
            .dataDir("C:\\Users\\victim\\private")
            .head(
                RuntimeManifestHeadInfoBuilder.builder()
                    .apiPort(54321)
                    .apiBaseUrl("http://127.0.0.1:54321")
                    .readyAt("2026-09-03T00:00:00Z")
                    .build())
            .build();
    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.current()).thenReturn(manifest);
    Context ctx = mock(Context.class);
    when(ctx.status(500)).thenReturn(ctx);
    String hostile = "token=secret at C:\\Users\\victim\\private\\manifest.json";

    new RuntimeManifestController(
            publisher,
            ignored -> {
              throw new IllegalStateException(hostile);
            })
        .handleGet(ctx);

    ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(body.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> response = (Map<String, Object>) body.getValue();
    assertEquals("INTERNAL_ERROR", response.get("errorCode"));
    assertEquals("Runtime manifest serialization failed", response.get("error"));
    assertFalse(response.toString().contains("secret"));
    assertFalse(response.toString().contains("victim"));
    assertConforms(
        "GET /api/runtime/manifest status 500",
        "api-error-response.v1.json",
        new tools.jackson.databind.ObjectMapper().writeValueAsString(response));
  }

  @Test
  void publicProjectionStripsSessionTokenWhenPresent() {
    RuntimeManifest.HeadInfo head =
        RuntimeManifestHeadInfoBuilder.builder()
            .apiPort(54321)
            .apiBaseUrl("http://127.0.0.1:54321")
            .sessionToken("super-secret-prod-token")
            .readyAt("2026-05-20T20:00:00Z")
            .build();
    RuntimeManifest manifest =
        RuntimeManifestBuilder.builder()
            .schemaVersion(1)
            .instanceId("ddd-eee-fff")
            .pid(1234L)
            .startedAt("2026-05-20T19:59:00Z")
            .dataDir("/tmp/whatever")
            .head(head)
            .build();

    RuntimeManifest publicView = manifest.publicProjection();

    assertNotNull(publicView.head(), "head sub-record must remain present");
    assertEquals(54321, publicView.head().apiPort(), "non-sensitive fields must survive");
    assertEquals("http://127.0.0.1:54321", publicView.head().apiBaseUrl());
    assertNull(publicView.head().sessionToken(), "sessionToken must be stripped");
  }

  @Test
  void publicProjectionIsIdentityWhenNoTokenPresent() {
    RuntimeManifest.HeadInfo head =
        RuntimeManifestHeadInfoBuilder.builder()
            .apiPort(54321)
            .apiBaseUrl("http://127.0.0.1:54321")
            .readyAt("2026-05-20T20:00:00Z")
            .build();
    RuntimeManifest manifest =
        RuntimeManifestBuilder.builder()
            .schemaVersion(1)
            .instanceId("ddd-eee-fff")
            .pid(1234L)
            .startedAt("2026-05-20T19:59:00Z")
            .dataDir("/tmp/whatever")
            .head(head)
            .build();

    RuntimeManifest publicView = manifest.publicProjection();

    assertEquals(manifest, publicView, "identity projection when nothing to redact");
  }

  @Test
  void publicProjectionPreservesWorkerAndAi() {
    RuntimeManifest.HeadInfo head =
        RuntimeManifestHeadInfoBuilder.builder()
            .apiPort(54321)
            .apiBaseUrl("http://127.0.0.1:54321")
            .sessionToken("token")
            .readyAt("2026-05-20T20:00:00Z")
            .build();
    // No grpcPort: lane F item A11 left it without a producer, and this test is about the public
    // projection preserving the worker sub-record, which state/indexBasePath assert without it.
    RuntimeManifest.WorkerInfo worker =
        RuntimeManifestWorkerInfoBuilder.builder()
            .state("ready")
            .indexBasePath("/data/idx")
            .readyAt("2026-05-20T20:01:00Z")
            .build();
    RuntimeManifest.AiInfo ai =
        new RuntimeManifest.AiInfo(
            "READY",
            true,
            null,
            "2026-05-20T20:02:00Z",
            "b8571",
            "b8571",
            "SUPPORTED",
            new io.justsearch.app.api.OnlineAiRuntimeIntrospection.ContextWindow(
                16384, "stepped-from:32768", 5_368_709_120L, 2, "q8_0"));
    RuntimeManifest manifest =
        RuntimeManifestBuilder.builder()
            .schemaVersion(1)
            .instanceId("ddd-eee-fff")
            .pid(1234L)
            .startedAt("2026-05-20T19:59:00Z")
            .dataDir("/tmp/whatever")
            .head(head)
            .worker(worker)
            .ai(ai)
            .build();

    RuntimeManifest publicView = manifest.publicProjection();

    assertNotNull(publicView.worker(), "worker sub-record must survive projection");
    assertEquals("ready", publicView.worker().state());
    assertEquals("/data/idx", publicView.worker().indexBasePath());
    assertNotNull(publicView.ai(), "ai sub-record must survive projection");
    assertEquals("READY", publicView.ai().phase());
    // Tempdoc 682 Item 2: the build-pin pair is not a credential — it must survive projection.
    assertEquals("b8571", publicView.ai().serverBuildExpected());
    assertEquals("b8571", publicView.ai().serverBuildActual());
    // Tempdoc 835: the thinking-capability verdict is a capability fact, not a credential.
    assertEquals("SUPPORTED", publicView.ai().thinkingSupport());
    // Tempdoc 883: the derived context window is a capability fact too — it is what this machine
    // ended up able to do, and free VRAM is already public on /api/inference/status.gpu.
    assertNotNull(publicView.ai().contextWindow(), "context window must survive projection");
    assertEquals(16384, publicView.ai().contextWindow().rung());
    assertEquals("stepped-from:32768", publicView.ai().contextWindow().reason());
    assertNull(publicView.head().sessionToken());
  }

  @Test
  void publicProjectionPreservesRuntimeContractWhileStrippingToken() {
    // Tempdoc 654: the RuntimeContract descriptor must survive the public projection that the
    // HTTP / SSE / MCP / well-known transports serve — even on the redaction (non-identity) branch
    // that strips the session token. This is the exact serve path an external agent reads.
    RuntimeManifest.HeadInfo head =
        RuntimeManifestHeadInfoBuilder.builder()
            .apiPort(54321)
            .apiBaseUrl("http://127.0.0.1:54321")
            .sessionToken("super-secret-prod-token")
            .readyAt("2026-05-20T20:00:00Z")
            .build();
    RuntimeManifest manifest =
        RuntimeManifestBuilder.builder()
            .schemaVersion(1)
            .instanceId("ddd-eee-fff")
            .pid(1234L)
            .startedAt("2026-05-20T19:59:00Z")
            .dataDir("/tmp/whatever")
            .head(head)
            .runtimeContract(RuntimeContract.current())
            .build();

    RuntimeManifest publicView = manifest.publicProjection();

    assertNull(publicView.head().sessionToken(), "sessionToken must still be stripped");
    assertNotNull(publicView.runtimeContract(), "runtimeContract must survive projection");
    assertEquals(RuntimeContract.CURRENT_VERSION, publicView.runtimeContract().version());
    assertNotNull(publicView.runtimeContract().constituents());
    assertEquals(
        RuntimeContract.current().constituents().mcpToolSurfaceVersion(),
        publicView.runtimeContract().constituents().mcpToolSurfaceVersion(),
        "constituent versions must be intact on the public view");
  }
}
