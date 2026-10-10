/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.justsearch.app.api.status.*;
import io.justsearch.core.component.EngineComponentRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Each request crosses real HTTP while an actual status dependency cannot complete. */
final class StatusNonBlockingHttpTest {
  @Test
  void failedObservationRetainsTheLastResponseAsStale() throws Exception {
    try (var fixture = new Fixture("disk-pressure")) {
      fixture.handler.sampleAndBuildStatusSnapshot();
      fixture.fail.set(true);
      assertThrows(IllegalStateException.class, fixture.handler::sampleAndBuildStatusSnapshot);
      var response = assertDoesNotThrow(() -> fixture.get(""));
      assertEquals(200, response.statusCode());
      var body = new ObjectMapper().readTree(response.body());
      assertTrue(body.path("readiness").path("components").path("ai").path("stale").asBoolean());
      assertEquals("unknown", body.path("conversationProtection").path("state").asText());
    }
  }

  @ParameterizedTest(name = "status does not wait for {0}")
  @ValueSource(strings = {"disk-pressure", "inference", "gpu-capabilities", "gpl",
      "conversation", "telemetry", "vdu", "vdu-processing", "device-plan", "bulk-gate",
      "registry", "assembly-lock", "gpu-memory", "at-rest", "worker", "rrd", "admission-lock",
      "agent", "online-ai", "gpu-saturation", "model-file-path"})
  void blockedDependencyReturnsAgedSnapshot(String source) throws Exception {
    try (var fixture = new Fixture(source)) {
      fixture.handler.sampleAndBuildStatusSnapshot();
      // A warm HTTP request establishes that the route/serialization work before contention.
      assertEquals(200, fixture.get("").statusCode());
      fixture.block.set(true);
      fixture.expireGpuReading();
      Thread sampler = Thread.ofPlatform().daemon().start(() -> {
        if (source.equals("assembly-lock") || source.equals("admission-lock")) {
          synchronized (fixture.heldLock()) { fixture.pause(); }
        } else {
          fixture.handler.sampleAndBuildStatusSnapshot();
        }
      });
      try {
        assertTrue(fixture.entered.await(10, TimeUnit.SECONDS), "the dependency is held");
        fixture.now.addAndGet(source.endsWith("lock") ? 31_001L : 1_001L);
        long started = System.nanoTime();
        String query = source.equals("worker") ? "?fresh=true" : "";
        var response = assertDoesNotThrow(() -> fixture.get(query),
            "GET /api/status must not exceed the client's unchanged five-second deadline");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        System.out.println("STATUS_HTTP " + source + " elapsedMs=" + elapsedMs);
        assertTrue(elapsedMs < 1_000L, "status must fit its budget while " + source + " is held");
        assertEquals(200, response.statusCode());
        JsonNode body = new ObjectMapper().readTree(response.body());
        assertTrue(body.path("meta").path("workerRpcStale").asBoolean());
        assertFalse(body.path("indexAvailable").asBoolean());
        assertEquals("UNKNOWN", body.path("atRestProtection").path("diskEncryption").asText());
        assertTrue(body.path("readiness").path("components").path("ai").path("stale").asBoolean());
        assertTrue(body.path("operating").path("freshness").path("staleSources").toString().contains("chat"));
        assertTrue(body.path("operating").path("freshness").path("staleSources").toString().contains("gpu"));
        // A fresh request has the same latency contract even while an observation owns a lock.
        assertEquals(200, assertDoesNotThrow(() -> fixture.get("?fresh=true")).statusCode());
      } finally {
        fixture.release.countDown();
        sampler.join(10_000L);
        assertFalse(sampler.isAlive(), "the owned sampler must exit after release");
      }
      fixture.block.set(false);
      fixture.handler.sampleAndBuildStatusSnapshot();
      var recovered = fixture.get("");
      assertEquals(200, recovered.statusCode());
      assertFalse(new ObjectMapper().readTree(recovered.body())
          .path("readiness").path("components").path("ai").path("stale").asBoolean());
    }
  }

  @ParameterizedTest(name = "boot status does not join {0} ({1})")
  @org.junit.jupiter.params.provider.CsvSource({"disk-pressure, ''", "disk-pressure, ?fresh=true",
      "worker, ''", "worker, ?fresh=true"})
  void firstRequestNeverJoinsWorkerSampling(String source, String query) throws Exception {
    try (var fixture = new Fixture(source)) {
      fixture.block.set(true);
      // No completed sample exists. The response must be honest unknown immediately.
      var response = assertDoesNotThrow(() -> fixture.get(query),
          "a boot request must return without waiting for its first observation");
      assertEquals(200, response.statusCode());
      var body = new ObjectMapper().readTree(response.body());
      assertTrue(body.path("meta").path("workerRpcStale").asBoolean());
      assertEquals("UNKNOWN", body.path("readiness").path("components").path("ai").path("state").asText());
      assertTrue(fixture.entered.await(10, TimeUnit.SECONDS));
      fixture.release.countDown();
    }
  }

  private static final class Fixture implements AutoCloseable {
    final StatusComponentFixture components = new StatusComponentFixture();
    final AtomicBoolean block = new AtomicBoolean();
    final AtomicBoolean fail = new AtomicBoolean();
    final AtomicLong now = new AtomicLong(System.currentTimeMillis());
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final StatusLifecycleHandler handler;
    final Javalin app;
    final String source;
    final io.justsearch.configuration.resolved.ConfigStore priorConfig;
    final HttpClient http = HttpClient.newHttpClient();
    final io.justsearch.app.engine.EngineAdmissionController admission =
        new io.justsearch.app.engine.EngineAdmissionController(1, 1, 1);

    Fixture(String source) throws Exception {
      this.source = source;
      priorConfig = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
      if (source.equals("model-file-path")) {
        var config = mock(io.justsearch.configuration.resolved.ConfigStore.class, RETURNS_DEEP_STUBS);
        var home = mock(java.nio.file.Path.class);
        when(config.get().paths().home()).thenReturn(home);
        when(home.resolve(io.justsearch.configuration.model.InstallContract.CONTRACT_FILENAME))
            .thenAnswer(i -> {
              gate("model-file-path");
              return java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))
                  .resolve("absent-status-contract-" + java.util.UUID.randomUUID());
            });
        io.justsearch.configuration.resolved.ConfigStore.setGlobal(config);
      }
      var graph = components.capabilities();
      var onlineAi = mock(io.justsearch.app.api.OnlineAiService.class);
      when(onlineAi.isAvailable()).thenAnswer(i -> { gate("online-ai"); return false; });
      var agent = mock(io.justsearch.agent.api.AgentService.class);
      when(agent.isAvailable()).thenAnswer(i -> { gate("agent"); return true; });
      handler = new StatusLifecycleHandler(onlineAi, agent,
          () -> { gate("inference"); return new InferenceRuntimeView("ONLINE", null, false, null,
              new LifecycleCounters(0L, 0L, 0L), true, "Healthy", "", "", ""); },
          null, null, null, Instant.now(), () -> { gate("disk-pressure"); return "OK"; },
          () -> { gate("gpl"); return null; }, () -> null,
          () -> { gate("gpu-capabilities"); return gpuService(); }, graph.worker(), graph.inference());
      components.attach(handler);
      if (source.equals("online-ai") || source.equals("gpu-saturation")) {
        var monitor = mock(io.justsearch.ui.observability.GpuSaturationMonitor.class);
        when(monitor.compute(anyLong())).thenAnswer(i -> {
          gate("gpu-saturation");
          return mock(io.justsearch.ui.observability.GpuSaturationMonitor.Result.class);
        });
        handler.setGpuSaturationMonitor(monitor);
      }
      if (source.equals("registry")) {
        var registry = mock(EngineComponentRegistry.class);
        when(registry.snapshot()).thenAnswer(i -> { gate("registry"); return components.registry().snapshot(); });
        handler.setIndexComponent(registry, components.index());
      }
      handler.setClockForTesting(now::get);
      handler.setConversationProtectionSupplier(() -> { gate("conversation"); return "locked"; });
      handler.setTelemetryHealthSupplier(() -> { gate("telemetry"); return null; });
      handler.setVduCapabilitySnapshotSupplier(() -> { gate("vdu"); return null; });
      handler.setVduProcessingSupplier(() -> { gate("vdu-processing"); return false; });
      handler.setDevicePlanSupplier(() -> { gate("device-plan"); return null; });
      var probe = mock(io.justsearch.app.services.atrest.DiskEncryptionProbe.class);
      when(probe.current()).thenAnswer(i -> {
        gate("at-rest"); return io.justsearch.app.services.atrest.AtRestProtection.unknown();
      });
      handler.setAtRestTap(null, probe);
      if (source.equals("worker")) {
        var ks = mock(io.justsearch.app.services.worker.KnowledgeServerBootstrap.class);
        var client = mock(io.justsearch.app.services.worker.KnowledgeClient.class);
        when(ks.hasClient()).thenReturn(true);
        when(client.getWorkerOperationalView(any())).thenAnswer(i -> {
          gate("worker");
          return WorkerOperationalViewBuilder.builder(WorkerOperationalView.fallback("READY"))
              .core(new CoreIndexView(true, 10, 0, "SERVING", 0, 0)).build();
        });
        BootstrapLeaseFixtures.bind(ks, client);
        handler.setKnowledgeServer(ks, null);
      }
      if (source.equals("rrd")) {
        var rrd = mock(io.justsearch.telemetry.RrdMetricStore.class);
        when(rrd.query(anyString(), anyLong(), anyLong())).thenAnswer(i -> {
          gate("rrd"); return null;
        });
        handler.setRrdStoreSupplier(() -> rrd);
      }
      if (source.equals("bulk-gate")) {
        var ks = mock(io.justsearch.app.services.worker.KnowledgeServerBootstrap.class);
        handler.setKnowledgeServerLifecycleSupplier(() -> { gate("bulk-gate"); return ks; });
      }
      if (source.equals("gpu-memory")) {
        var reader = mock(io.justsearch.app.api.gpu.GpuMemoryReader.class);
        when(reader.sampleNow()).thenAnswer(i -> {
          gate("gpu-memory");
          return io.justsearch.app.api.gpu.GpuMemorySample.unknown(
              io.justsearch.app.api.gpu.GpuMemorySample.UnknownReason.NO_NVIDIA_ADAPTER, Instant.now());
        });
        handler.setGpuMemoryReader(reader);
        // The first native sample must have completed before we block a refresh.
        var field = StatusLifecycleHandler.class.getDeclaredField("gpuMemory");
        field.setAccessible(true);
        Object hold = field.get(handler);
        var method = hold.getClass().getDeclaredMethod("projection");
        method.setAccessible(true);
        var projection = (OperatingGpuProjection) method.invoke(hold);
        projection.sampleFirst();
        var at = OperatingGpuProjection.class.getDeclaredField("cachedAt");
        at.setAccessible(true);
        at.set(projection, Instant.now().minusSeconds(10));
      }
      app = Javalin.create(c -> c.showJavalinBanner = false);
      new ApiSecurityFilters(false, null, null, null, null, null, admission).install(app);
      app.get("/api/status", handler::handleStatus);
      app.start("127.0.0.1", 0);
    }

    Object heldLock() {
      try {
        if (source.equals("admission-lock")) {
          var f = io.justsearch.app.engine.EngineAdmissionController.class.getDeclaredField("lock");
          f.setAccessible(true);
          return f.get(admission);
        }
        var f = StatusLifecycleHandler.class.getDeclaredField("operatingStateAssembler");
        f.setAccessible(true);
        return f.get(handler);
      } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    void expireGpuReading() throws Exception {
      for (String name : new String[] {"cachedGpuStatusAtMs", "cachedAtRestAtMs", "cachedModelDistAtMs"}) {
        var field = StatusLifecycleHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(handler, 0L);
      }
      if (!source.equals("gpu-memory")) return;
      var field = StatusLifecycleHandler.class.getDeclaredField("gpuMemory");
      field.setAccessible(true);
      Object hold = field.get(handler);
      var method = hold.getClass().getDeclaredMethod("projection");
      method.setAccessible(true);
      var projection = (OperatingGpuProjection) method.invoke(hold);
      var at = OperatingGpuProjection.class.getDeclaredField("cachedAt");
      at.setAccessible(true);
      at.set(projection, Instant.now().minusSeconds(10));
    }

    io.justsearch.gpu.GpuCapabilitiesService gpuService() {
      if (!source.equals("rrd")) return null;
      var service = mock(io.justsearch.gpu.GpuCapabilitiesService.class);
      var caps = mock(io.justsearch.gpu.GpuCapabilities.class);
      var nvml = mock(io.justsearch.gpu.GpuCapabilities.Nvml.class);
      when(nvml.available()).thenReturn(true);
      when(caps.nvml()).thenReturn(nvml);
      when(service.snapshot(any(io.justsearch.gpu.GpuCapabilities.Cuda.class))).thenReturn(caps);
      return service;
    }

    void gate(String dependency) {
      if (!source.equals(dependency)) return;
      if (fail.get()) throw new IllegalStateException("status producer failed");
      if (block.get()) pause();
    }
    void pause() {
      entered.countDown();
      try {
        if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("unreleased test gate");
      } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    HttpResponse<String> get(String query) throws Exception {
      return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
          + "/api/status" + query)).timeout(Duration.ofSeconds(5)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }

    @Override public void close() throws Exception {
      release.countDown();
      app.stop();
      http.close();
      handler.closeGpuMemoryReader();
      components.close();
      if (source.equals("model-file-path")) {
        io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(priorConfig);
      }
    }
  }
}
