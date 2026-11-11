/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.javalin.Javalin;
import io.justsearch.app.api.AiPackImportStatus;
import io.justsearch.app.api.OnlineAiRuntimeIntrospection;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.engine.EngineRoot;
import io.justsearch.app.services.ai.pack.AiPackImportService;
import io.justsearch.app.services.encryption.DataKeyManager;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.ui.api.runtime.RuntimeProbeController;
import io.justsearch.ui.json.Jackson3JsonMapper;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A routinely polled request whose real handler is held in its JSON response serialization, through
 * the real admission filters and the Engine's own admission, while the Engine's index recovery
 * close runs against a mocked physical index owner.
 */
@Timeout(30)
final class PolledObservationRecoveryCloseTest {
  @TempDir Path directory;

  @ParameterizedTest(name = "{arguments}")
  @ValueSource(strings = {"/api/runtime/live", "/api/diagnostics/traces",
      "/api/inference/transitions?limit=8", "/api/ai/packs/status", "/api/conversations/encryption"})
  void heldPolledReadDoesNotRefuseTheRecoveryClose(String route) throws Exception {
    var outcome = holdAcrossRecoveryClose(route);
    assertDoesNotThrow(outcome::rethrowCloseFailure,
        "a held " + route + " response must not make the recovery close refuse");
    verify(outcome.server()).closeForRecovery();
    assertEquals(200, outcome.status());
  }

  @ParameterizedTest(name = "{arguments}")
  @ValueSource(strings = {"/api/test-index-work"})
  void heldOrdinaryRequestStillRefusesTheRecoveryClose(String route) throws Exception {
    var outcome = holdAcrossRecoveryClose(route);
    var refused = assertThrows(IllegalStateException.class, outcome::rethrowCloseFailure);
    assertEquals("Live Engine work retains the index owner", refused.getMessage());
    verify(outcome.server(), never()).closeForRecovery();
    assertEquals(200, outcome.status());
  }

  private record Outcome(KnowledgeServer server, Throwable closeFailure, int status) {
    void rethrowCloseFailure() throws Throwable {
      if (closeFailure != null) throw closeFailure;
    }
  }

  private Outcome holdAcrossRecoveryClose(String route) throws Exception {
    String previousDataDir = System.getProperty("justsearch.data.dir");
    System.setProperty("justsearch.data.dir", directory.resolve("data").toString());
    var hold = new AtomicBoolean(true);
    var serializing = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var mapper = spy(new Jackson3JsonMapper());
    doAnswer(invocation -> {
      if (hold.get()) {
        serializing.countDown();
        assertTrue(release.await(10, TimeUnit.SECONDS), "held response was not released");
      }
      return invocation.callRealMethod();
    }).when(mapper).toJsonString(any(), any());
    var app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(mapper);
    });
    var root = new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class),
        1_000, 100);
    var server = mock(KnowledgeServer.class);
    when(server.awaitClosed(anyLong())).thenReturn(true);
    var closeFailure = new AtomicReference<Throwable>();
    try (var _ = root.processResources(); root;
        var events = Executors.newSingleThreadExecutor();
        var client = HttpClient.newHttpClient()) {
      Field field = EngineRoot.class.getDeclaredField("server");
      field.setAccessible(true);
      field.set(root, server);
      new ApiSecurityFilters(false, null, new EventBuffer(), events, null, null, root.admission())
          .install(app);
      registerRealHandlers(app);
      app.start("127.0.0.1", 0);
      var response = client.sendAsync(HttpRequest.newBuilder(URI.create(
              "http://127.0.0.1:" + app.port() + route)).timeout(Duration.ofSeconds(15)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertTrue(serializing.await(10, TimeUnit.SECONDS), route + " never reached serialization");
      assertEquals(1, root.admission().activeWorkCount(), route + " holds its admission");
      try {
        root.closeForRecovery();
      } catch (Throwable failure) {
        closeFailure.set(failure);
      } finally {
        hold.set(false);
        release.countDown();
      }
      int status = response.get(15, TimeUnit.SECONDS).statusCode();
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (root.admission().activeWorkCount() != 0 && System.nanoTime() < until) Thread.sleep(5);
      assertEquals(0, root.admission().activeWorkCount(), "the held request released its admission");
      return new Outcome(server, closeFailure.get(), status);
    } finally {
      release.countDown();
      app.stop();
      if (previousDataDir == null) System.clearProperty("justsearch.data.dir");
      else System.setProperty("justsearch.data.dir", previousDataDir);
    }
  }

  /** The production handlers of the polled routes, registered as their route modules do. */
  private void registerRealHandlers(Javalin app) {
    var probe = new RuntimeProbeController(new RuntimeManifestPublisher(directory.resolve("runtime")));
    app.get("/api/runtime/live", probe::handleLive, ActivityRole.OBSERVATION);
    app.get("/api/diagnostics/traces", new DiagnosticsController(null, null)::handleRecentTraces,
        ActivityRole.OBSERVATION);
    var inference = new InferenceHandlers(mock(OnlineAiService.class,
        withSettings().extraInterfaces(OnlineAiRuntimeIntrospection.class)),
        null, null, null, null, null, null, null, () -> null);
    app.get("/api/inference/transitions", inference::handleInferenceTransitions,
        ActivityRole.OBSERVATION);
    var packs = mock(AiPackImportService.class);
    when(packs.getStatus()).thenReturn(new AiPackImportStatus());
    app.get("/api/ai/packs/status", new AiPackController(packs, null)::handleGetStatus,
        ActivityRole.OBSERVATION);
    var keys = mock(DataKeyManager.class);
    when(keys.state()).thenReturn(DataKeyManager.State.LOCKED);
    app.get("/api/conversations/encryption",
        new ConversationEncryptionController(keys)::handleStatus, ActivityRole.OBSERVATION);
    // An ordinary GET observation that the filters still admit as Engine work.
    app.get("/api/test-index-work", ctx -> ctx.json(Map.of("ok", true)), ActivityRole.OBSERVATION);
  }
}
