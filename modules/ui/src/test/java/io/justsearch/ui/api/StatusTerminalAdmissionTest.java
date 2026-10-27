/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.StatusResponse;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.ui.json.Jackson3JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The real fresh-status future/serializer, not a substituted status route. */
@Timeout(30)
final class StatusTerminalAdmissionTest {
  @TempDir Path directory;

  @Test
  void exceptionalFreshStatusSerializationReleasesItsReservationWithoutRetiringTheSharedLane()
      throws Exception {
    var admission = new EngineAdmissionController(4, 8, 1);
    var failing = new AtomicBoolean();
    var mapper = spy(new Jackson3JsonMapper());
    doAnswer(invocation -> {
      if (failing.get() && invocation.getArgument(0) instanceof StatusResponse) {
        throw new IllegalStateException("controlled fresh status response serialization failure");
      }
      return invocation.callRealMethod();
    }).when(mapper).toJsonString(any(), any());
    var backend = mock(KnowledgeServerBootstrap.class);
    var knowledge = mock(KnowledgeClient.class);
    when(backend.hasClient()).thenReturn(true);
    BootstrapLeaseFixtures.bind(backend, knowledge);
    var realValue = WorkerOperationalViewBuilder.builder(WorkerOperationalView.fallback("READY"))
        .core(new CoreIndexView(true, 17, 0, "SERVING", 0, 0)).build();
    when(knowledge.getWorkerOperationalView(any())).thenReturn(realValue);
    var app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(mapper);
    });
    try (var components = new StatusComponentFixture();
        var events = Executors.newSingleThreadExecutor();
        var client = HttpClient.newHttpClient();
        var shared = admission.admitObservationRequest(TestRequestContexts.internal(), false)) {
      var graph = components.capabilities();
      var status = new StatusLifecycleHandler(mock(io.justsearch.app.api.OnlineAiService.class),
          mock(io.justsearch.agent.api.AgentService.class), () -> null, backend, null, directory,
          Instant.now(), () -> "OK", null, null, null, graph.worker(), graph.inference());
      // Keep all OS/native probes outside this deterministic HTTP serializer fixture.
      status.setAtRestTap(null, mock(io.justsearch.app.services.atrest.DiskEncryptionProbe.class));
      components.attach(status);
      status.sampleAndBuildStatusSnapshot();
      var lastReal = status.buildStatusSnapshot().worker().core();
      assertEquals(realValue.core().withVduAvailable(false), lastReal);
      new ApiSecurityFilters(false, null, new EventBuffer(), events, null, null, admission).install(app);
      io.justsearch.ui.api.routes.StatusRoutes.registerLifecycleRoutes(app, status::handleStatus, status::handleHealth);
      app.start("127.0.0.1", 0);
      int baseline = admission.activeWorkCount();
      assertEquals(1, baseline);
      failing.set(true);
      var failed = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
              + "/api/status?fresh=true")).timeout(Duration.ofSeconds(5)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(500, failed.statusCode(), "the genuine fresh-status future fails in its response serializer");
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (admission.activeWorkCount() != baseline && System.nanoTime() < until) Thread.sleep(5);
      assertEquals(baseline, admission.activeWorkCount(), "only this failed HTTP status reservation releases");
      assertEquals(lastReal, status.buildStatusSnapshot().worker().core(), "serialization failure cannot replace the lane's real value");
      assertTrue(shared.cancellationReason().isEmpty());
      try (var retained = shared.retain()) {
        assertEquals(shared.context().workId(), retained.context().workId());
      }
      failing.set(false);
      var next = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
              + "/api/status")).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, next.statusCode(), "same status route is usable without a restart or larger limits");
      assertEquals(lastReal, status.buildStatusSnapshot().worker().core());
    } finally {
      app.stop();
    }
  }
}
