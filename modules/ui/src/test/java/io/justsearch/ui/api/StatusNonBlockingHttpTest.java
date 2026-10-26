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
      assertDoesNotThrow(fixture.handler::sampleAndBuildStatusSnapshot);
      var response = assertDoesNotThrow(() -> fixture.get(""));
      assertEquals(200, response.statusCode());
      var body = new ObjectMapper().readTree(response.body());
      assertTrue(body.path("diskPressure").isMissingNode() || body.path("diskPressure").isNull());
      assertFalse(body.path("readiness").path("components").path("ai").path("stale").asBoolean());
      assertEquals("locked", body.path("conversationProtection").path("state").asText());
    }
  }

  @ParameterizedTest(name = "failure of {0} stays local")
  @ValueSource(strings = {"disk-pressure", "inference", "gpl", "conversation", "telemetry",
      "vdu", "vdu-processing", "device-plan", "bulk-gate", "gpu-capabilities", "at-rest",
      "agent", "online-ai", "gpu-saturation", "power", "resume", "vision"})
  void failedProducerDoesNotInvalidateOtherOwners(String source) throws Exception {
    try (var fixture = new Fixture(source)) {
      fixture.handler.sampleAndBuildStatusSnapshot();
      fixture.fail.set(true);
      fixture.expireGpuReading();
      assertDoesNotThrow(fixture.handler::sampleAndBuildStatusSnapshot,
          "failure of one producer must not abort publication");
      var response = fixture.get("");
      assertEquals(200, response.statusCode());
      var body = new ObjectMapper().readTree(response.body());
      assertFalse(body.path("meta").path("workerRpcStale").asBoolean(), "Worker is healthy");
      assertFalse(body.path("readiness").path("components").path("ai").path("stale").asBoolean(),
          "head-local AI is independent of the failed producer");
      if (source.equals("disk-pressure")) assertTrue(body.path("diskPressure").isMissingNode());
      if (source.equals("conversation")) assertEquals("unknown", body.path("conversationProtection").path("state").asText());
      if (source.equals("power")) assertEquals("UNKNOWN", body.path("power").path("source").asText());
      if (source.equals("resume")) assertFalse(body.path("catchingUp").asBoolean());
      if (source.equals("vision")) assertTrue(body.path("readiness").path("components").path("visualDocumentUnderstanding").path("stale").asBoolean());
      if (source.equals("telemetry")) assertTrue(body.path("readiness").path("components").path("telemetry").path("stale").asBoolean());
      if (source.equals("gpl")) assertTrue(body.path("readiness").path("components").path("lambdamartModel").path("stale").asBoolean());
      if (source.equals("gpu-saturation") || source.equals("online-ai")) assertTrue(body.path("readiness").path("components").path("gpu").path("stale").asBoolean());
      assertIndependentComponentsFresh(body, source);
    }
  }

  @ParameterizedTest(name = "status does not wait for {0}")
  @ValueSource(strings = {"disk-pressure", "inference", "gpu-capabilities", "gpl",
      "conversation", "telemetry", "vdu", "vdu-processing", "device-plan", "bulk-gate",
      "registry", "assembly-lock", "gpu-memory", "at-rest", "worker", "rrd", "admission-lock",
      "agent", "online-ai", "gpu-saturation", "model-file-path", "power", "resume", "vision"})
  void blockedDependencyReturnsAgedSnapshot(String source) throws Exception {
    try (var fixture = new Fixture(source)) {
      Instant beforeProjection = Instant.now();
      fixture.projectionStartedMs = beforeProjection.toEpochMilli();
      fixture.handler.sampleAndBuildStatusSnapshot();
      fixture.drainPublications();
      var beforeCompanion = fixture.captureCompanion();
      Instant beforeProjected = Instant.now();
      var heldPublications = new java.util.LinkedHashMap<String, StatusObservation.Sample<?>>();
      for (String lane : fixture.heldLanes()) {
        var publication = fixture.published(lane);
        assertEquals(fixture.sourceObservedAt(lane), publication.observedAtMs(), "known publication before hold: " + lane);
        assertFalse(publication.failed(), lane);
        assertFalse(publication.staleSource(), lane);
        assertNotNull(publication.value(), "typed pre-hold publication: " + lane);
        fixture.assertSupportedCadence(lane, publication);
        heldPublications.put(lane, publication);
      }
      if (source.equals("rrd")) {
        assertTrue(new ObjectMapper().readTree(fixture.get("").body())
            .path("gpu").path("available").asBoolean(), "RRD needs an available GPU publication");
      }
      var knownBody = new ObjectMapper().readTree(fixture.get("").body());
      fixture.assertKnownPublic(knownBody);
      fixture.assertCompanion(knownBody, beforeCompanion, beforeProjection, beforeProjected);
      fixture.assertPublicFreshness(knownBody, false);
      for (var entry : heldPublications.entrySet())
        fixture.assertPublicPublication(knownBody, entry.getKey(), entry.getValue());
      // A warm HTTP request establishes that the route/serialization work before contention.
      assertEquals(200, fixture.get("").statusCode());
      Instant heldProjection = Instant.now();
      fixture.projectionStartedMs = heldProjection.toEpochMilli();
      fixture.block.set(true);
      fixture.expireGpuReading();
      Thread sampler = Thread.ofPlatform().daemon().unstarted(() -> {
        if (source.equals("assembly-lock") || source.equals("admission-lock")) {
          synchronized (fixture.heldLock()) { fixture.pause(); }
        } else {
          fixture.handler.sampleAndBuildStatusSnapshot();
        }
      });
      fixture.work.threads.add(sampler);
      sampler.setUncaughtExceptionHandler((thread, failure) -> fixture.work.errors.add(failure));
      sampler.start();
      Throwable phaseFailure = null;
      try {
        assertTrue(fixture.entered.await(10, TimeUnit.SECONDS), "the dependency is held");
        if (!source.endsWith("lock")) {
          sampler.join(10_000L);
          assertFalse(sampler.isAlive(), "independent producers publish without waiting for this owner");
        }
        // Use the held source's published effective cadence, including its cache/probe allowance.
        long agedAt = fixture.now.get();
        for (var entry : heldPublications.entrySet()) {
          assertSame(entry.getValue(), fixture.published(entry.getKey()),
              "the held source must retain its pre-hold publication");
          agedAt = Math.max(agedAt, entry.getValue().observedAtMs()
              + 3L * entry.getValue().cadenceMs() + 1L);
        }
        fixture.now.set(agedAt);
        for (var publication : heldPublications.values()) {
          assertTrue(fixture.now.get() - publication.observedAtMs()
              > 3L * publication.cadenceMs(),
              "the held publication must age beyond its actual effective cadence");
        }
        if (!source.endsWith("lock")) {
          // Every other lane keeps sampling and stays current; only the held one ages.
          Thread pass = fixture.work.startSampler();
          pass.join(10_000L);
          assertFalse(pass.isAlive(), "the next sampler pass is bounded while " + source + " is held");
          fixture.drainPublications();
          for (String lane : new String[] {"worker", "registry", "disk", "conversation"}) {
            if ((source.equals("worker") && lane.equals("worker"))
                || (source.equals("registry") && lane.equals("registry"))
                || (source.equals("disk-pressure") && lane.equals("disk"))
                || (source.equals("conversation") && lane.equals("conversation"))) continue;
            var publication = fixture.published(lane);
            assertEquals(fixture.now.get(), publication.observedAtMs(), "fresh unrelated source: " + lane);
            assertFalse(publication.failed(), lane);
            assertFalse(publication.staleSource(), lane);
            assertNotNull(publication.value(), lane);
            if (lane.equals("disk")) assertEquals("OK", publication.value());
            if (lane.equals("conversation")) assertEquals("locked",
                ((ConversationProtectionView) publication.value()).state());
          }
        }
        if (!source.endsWith("lock")) fixture.drainPublications();
        var heldCompanion = fixture.captureCompanion();
        Instant heldProjected = Instant.now();
        for (var entry : heldPublications.entrySet()) {
          assertSame(entry.getValue(), fixture.published(entry.getKey()),
              "unrelated sampling cannot renew the held source");
          assertTrue(fixture.lane(entry.getKey()).stale(fixture.now.get()),
              "the aged held publication must actually be classified stale: " + entry.getKey());
        }
        long started = System.nanoTime();
        String query = source.equals("worker") ? "?fresh=true" : "";
        var response = assertDoesNotThrow(() -> fixture.get(query),
            "GET /api/status must not exceed the client's unchanged five-second deadline");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        System.out.println("STATUS_HTTP " + source + " elapsedMs=" + elapsedMs);
        assertTrue(elapsedMs < 1_000L, "status must fit its budget while " + source + " is held");
        assertEquals(200, response.statusCode());
        JsonNode body = new ObjectMapper().readTree(response.body());
        fixture.assertPublicFreshness(body, true);
        fixture.assertHeldPublic(body);
        if (!source.endsWith("lock")) fixture.assertCompanion(body, heldCompanion,
            fixture.companionIsHeld() ? beforeProjection : heldProjection,
            fixture.companionIsHeld() ? beforeProjected : heldProjected);
        boolean workerLost = source.equals("worker") || source.endsWith("lock");
        assertEquals(workerLost, body.path("meta").path("workerRpcStale").asBoolean());
        boolean aiLost = source.equals("registry") || source.endsWith("lock");
        assertEquals(aiLost, body.path("readiness").path("components").path("ai").path("stale").asBoolean());
        if (!aiLost) assertEquals("READY",
            body.path("readiness").path("components").path("ai").path("state").asText());
        if (source.equals("at-rest") || source.endsWith("lock")) {
          assertEquals("UNKNOWN", body.path("atRestProtection").path("diskEncryption").asText());
        }
        if (source.equals("disk-pressure")) {
          assertTrue(body.path("diskPressure").isMissingNode() || body.path("diskPressure").isNull());
          assertFalse(body.path("readiness").path("components").path("telemetry").path("stale").asBoolean());
        }
        if (source.equals("inference")) {
          assertTrue(body.path("operating").path("freshness").path("staleSources").toString().contains("chat"));
          assertFalse(body.path("meta").path("workerRpcStale").asBoolean());
        }
        var dims = body.path("readiness").path("components");
        switch (source) {
          case "gpl" -> assertTrue(dims.path("lambdamartModel").path("stale").asBoolean());
          case "telemetry" -> assertTrue(dims.path("telemetry").path("stale").asBoolean());
          case "vdu", "vdu-processing" -> assertTrue(dims.path("visualDocumentUnderstanding").path("stale").asBoolean());
          case "online-ai", "gpu-saturation" -> assertTrue(dims.path("gpu").path("stale").asBoolean());
          case "gpu-capabilities", "rrd" -> assertFalse(body.path("gpu").path("available").asBoolean());
          case "conversation" -> assertEquals("unknown", body.path("conversationProtection").path("state").asText());
          case "agent" -> assertTrue(body.path("agentSessions").isMissingNode() || body.path("agentSessions").isNull());
          case "gpu-memory", "device-plan" -> assertTrue(body.path("operating").path("freshness").path("staleSources").toString().contains("gpu"));
          case "power" -> assertEquals("UNKNOWN", body.path("power").path("source").asText());
          case "resume" -> assertFalse(body.path("catchingUp").asBoolean());
          case "vision" -> assertTrue(dims.path("visualDocumentUnderstanding").path("stale").asBoolean());
          case "bulk-gate" -> assertTrue(body.path("operating").path("freshness").path("staleSources").toString().contains("indexing"));
          case "model-file-path" -> assertTrue(body.path("modelDistribution").path("installProfile").isMissingNode());
          default -> { }
        }
        if (!source.equals("telemetry") && !source.endsWith("lock")) {
          assertFalse(dims.path("telemetry").path("stale").asBoolean(),
              "a different blocked owner cannot age telemetry");
        }
        assertIndependentComponentsFresh(body, source);
        // The subsequent fresh request retains HTTP 200 and the unchanged five-second client deadline.
        assertEquals(200, assertDoesNotThrow(() -> fixture.get("?fresh=true")).statusCode());
      } catch (Exception | Error phaseError) { phaseFailure = phaseError; throw phaseError; } finally {
        try {
          fixture.release.countDown();
          sampler.join(10_000L);
          assertFalse(sampler.isAlive(), "the owned sampler must exit after release");
        } catch (Exception | Error cleanupError) {
          if (phaseFailure == null) throw cleanupError;
          if (cleanupError != phaseFailure) phaseFailure.addSuppressed(cleanupError);
        }
      }
      fixture.block.set(false);
      // A released read began at the old clock. Drain it before requiring a new source read.
      fixture.drainPublications();
      fixture.advanceRecoveryInput();
      fixture.expireGpuReading();
      Instant recoveryProjection = Instant.now();
      fixture.projectionStartedMs = recoveryProjection.toEpochMilli();
      fixture.handler.sampleAndBuildStatusSnapshot();
      fixture.drainPublications();
      var recoveryCompanion = fixture.captureCompanion();
      Instant recoveryProjected = Instant.now();
      for (var entry : heldPublications.entrySet()) {
        var publication = fixture.published(entry.getKey());
        assertNotSame(entry.getValue(), publication, "release must permit a new source publication");
        assertEquals(fixture.sourceObservedAt(entry.getKey()), publication.observedAtMs(), "recovery publication after aging");
        assertFalse(publication.failed());
        assertFalse(publication.staleSource());
        assertNotNull(publication.value(), "typed recovered publication: " + entry.getKey());
      }
      var recovered = fixture.get("");
      assertEquals(200, recovered.statusCode());
      var recoveredBody = new ObjectMapper().readTree(recovered.body());
      fixture.assertNewInputConsumed();
      fixture.assertKnownPublic(recoveredBody);
      fixture.assertCompanion(recoveredBody, recoveryCompanion, recoveryProjection, recoveryProjected);
      fixture.assertPublicFreshness(recoveredBody, false);
      for (String lane : fixture.heldLanes())
        fixture.assertPublicPublication(recoveredBody, lane, fixture.published(lane));
      assertFalse(new ObjectMapper().readTree(recovered.body())
          .path("readiness").path("components").path("ai").path("stale").asBoolean());
    }
  }

  private static void assertIndependentComponentsFresh(JsonNode body, String source) {
    if (source.endsWith("lock")) return; // These preservation cases advance beyond every cadence.
    var dependent = switch (source) {
      case "registry" -> java.util.Set.of("workerControlPlane", "indexServing", "ai");
      case "worker" -> java.util.Set.of("indexServing", "embedding", "chunkEmbedding",
          "visualTextExtraction", "visualDocumentUnderstanding", "gpu");
      case "gpl" -> java.util.Set.of("lambdamartModel");
      case "telemetry" -> java.util.Set.of("telemetry");
      case "vdu", "vdu-processing", "vision" -> java.util.Set.of("visualDocumentUnderstanding");
      case "online-ai", "gpu-saturation" -> java.util.Set.of("gpu");
      default -> java.util.Set.<String>of();
    };
    var dimensions = body.path("readiness").path("components");
    for (var dimension : io.justsearch.app.api.lifecycle.ReadinessDimension.values()) {
      if (!dependent.contains(dimension.key())) {
        assertTrue(dimensions.has(dimension.key()), "the independent component is present");
        assertFalse(dimensions.path(dimension.key()).path("stale").asBoolean(),
            source + " must not age independent " + dimension.key());
      }
    }
    if (!source.equals("conversation")) {
      assertEquals("locked", body.path("conversationProtection").path("state").asText());
    }
    if (!source.equals("disk-pressure")) assertEquals("OK", body.path("diskPressure").asText());
    var affectedBlocks = switch (source) {
      case "registry" -> java.util.Set.of("chat", "indexing");
      case "inference" -> java.util.Set.of("chat");
      case "worker", "bulk-gate", "power" -> java.util.Set.of("indexing");
      case "gpu-memory", "device-plan" -> java.util.Set.of("gpu");
      default -> java.util.Set.<String>of();
    };
    for (var block : body.path("operating").path("freshness").path("staleSources")) {
      assertTrue(affectedBlocks.contains(block.asText()),
          source + " must not age independent operating " + block.asText());
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
      if (query.isEmpty() || source.equals("worker")) {
        assertTrue(body.path("meta").path("workerRpcStale").asBoolean());
      } else {
        assertFalse(body.path("meta").path("workerRpcStale").asBoolean());
      }
      if (query.isEmpty()) {
        assertEquals("UNKNOWN", body.path("readiness").path("components").path("ai").path("state").asText());
      } else {
        assertEquals("READY", body.path("readiness").path("components").path("ai").path("state").asText());
        assertFalse(body.path("readiness").path("components").path("ai").path("stale").asBoolean());
      }
      assertTrue(fixture.entered.await(10, TimeUnit.SECONDS));
      fixture.release.countDown();
    }
  }

  private static final class Fixture implements AutoCloseable {
    StatusComponentFixture components;
    final AtomicBoolean block = new AtomicBoolean();
    final AtomicBoolean fail = new AtomicBoolean();
    StatusFixtureWork work;
    record SourceRead(String dependency, boolean after) {}
    final java.util.concurrent.ConcurrentLinkedQueue<SourceRead> sourceReads = new java.util.concurrent.ConcurrentLinkedQueue<>();
    int oldSourceReads;
    int oldRerankerReads;
    int oldCoordinatorReads;
    int oldRegistryEvents;
    final java.util.concurrent.ConcurrentLinkedQueue<io.justsearch.core.component.EngineComponentSnapshot> registryEvents = new java.util.concurrent.ConcurrentLinkedQueue<>();
    final java.util.concurrent.ConcurrentLinkedQueue<Boolean> rerankerReads = new java.util.concurrent.ConcurrentLinkedQueue<>();
    final java.util.concurrent.ConcurrentLinkedQueue<io.justsearch.app.api.gpl.GplJobStatus> coordinatorReads = new java.util.concurrent.ConcurrentLinkedQueue<>();
    final AtomicLong consumedAgentCount = new AtomicLong();
    final AtomicLong consumedWorkerDocuments = new AtomicLong();
    final AtomicLong consumedGpuTime = new AtomicLong();
    final AtomicLong consumedEncryptionTime = new AtomicLong();
    Instant registryStateSince;
    long companionObservedAtMs;
    long projectionStartedMs;
    long requestStartedMs;
    long requestCompletedMs;
    final AtomicLong now = new AtomicLong(System.currentTimeMillis());
    final AtomicLong indexedDocuments = new AtomicLong(10L);
    final long initialAtMs = now.get();
    final AtomicBoolean recovered = new AtomicBoolean();
    final AtomicLong readingAtMs = new AtomicLong(now.get() - 137L);
    java.nio.file.Path modelHome;
    final CountDownLatch entered;
    final CountDownLatch release = new CountDownLatch(1);
    StatusLifecycleHandler handler;
    Javalin app;
    final String source;
    final io.justsearch.configuration.resolved.ConfigStore priorConfig;
    io.justsearch.configuration.resolved.ConfigStore installedConfig;
    HttpClient http;
    final io.justsearch.app.engine.EngineAdmissionController admission =
        new io.justsearch.app.engine.EngineAdmissionController(1, 1, 1);

    Fixture(String source) throws Exception {
      this.source = source;
      entered = new CountDownLatch(source.equals("bulk-gate") ? 4 : 1);
      priorConfig = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
      try {
        components = new StatusComponentFixture();
        registryStateSince = components.handle("generative").snapshot().stateSince();
        http = HttpClient.newHttpClient();
        if (source.equals("model-file-path")) {
          modelHome = java.nio.file.Files.createTempDirectory("status-model-contract-");
          io.justsearch.configuration.model.InstallContractIO.write(
              new io.justsearch.configuration.model.InstallContract(2, now.get(),
                  new io.justsearch.configuration.model.HardwareProfile(false, false, -1L),
                  io.justsearch.configuration.model.DownloadProfile.CPU, java.util.Map.of(), modelHome), modelHome);
          var config = mock(io.justsearch.configuration.resolved.ConfigStore.class, RETURNS_DEEP_STUBS);
          var home = mock(java.nio.file.Path.class);
          when(config.get().paths().home()).thenReturn(home);
          when(home.resolve(io.justsearch.configuration.model.InstallContract.CONTRACT_FILENAME))
              .thenAnswer(i -> {
                gate("model-file-path");
                return modelHome.resolve(io.justsearch.configuration.model.InstallContract.CONTRACT_FILENAME);
              });
          io.justsearch.configuration.resolved.ConfigStore.setGlobal(config);
          installedConfig = config;
        }
        var graph = components.capabilities();
        var onlineAi = mock(io.justsearch.app.api.OnlineAiService.class, withSettings()
            .extraInterfaces(io.justsearch.app.api.OnlineAiRuntimeIntrospection.class));
        when(((io.justsearch.app.api.OnlineAiRuntimeIntrospection) onlineAi).hasVisionCapability())
            .thenAnswer(i -> { boolean after = gate("vision"); return !source.equals("vision") || !after; });
        when(onlineAi.isAvailable()).thenAnswer(i -> { boolean after = gate("online-ai"); return source.equals("online-ai") && !after; });
        var agent = mock(io.justsearch.agent.api.AgentService.class);
        when(agent.isAvailable()).thenAnswer(i -> { gate("agent"); return true; });
        when(agent.activeSessionCount()).thenAnswer(i -> {
          int count = source.equals("agent") ? (recovered.get() ? 8 : 7) : 0;
          consumedAgentCount.set(count);
          return count;
        });
        var reranker = mock(io.justsearch.app.api.gpl.RerankerService.class);
        when(reranker.isLoaded()).thenAnswer(i -> {
          boolean loaded = !recovered.get();
          rerankerReads.add(loaded);
          return loaded;
        });
        var coordinator = mock(io.justsearch.app.api.gpl.GplStatusProvider.class);
        var completedStatus = new io.justsearch.app.api.gpl.GplJobStatus(
            io.justsearch.app.api.gpl.GplJobStatus.Status.COMPLETED, 1L, 1L, 1L, null, null);
        when(coordinator.getStatus()).thenAnswer(i -> { coordinatorReads.add(completedStatus); return completedStatus; });
        handler = new StatusLifecycleHandler(onlineAi, agent,
            () -> { boolean after = gate("inference"); return new InferenceRuntimeView("ONLINE", null, false, null,
                new LifecycleCounters(0L, 0L, 0L), true, source.equals("inference") && after ? "Recovering" : "Healthy", "", "", "CHAT"); },
            null, null, null, Instant.now(), () -> { boolean after = gate("disk-pressure"); return source.equals("disk-pressure") && after ? "WARNING" : "OK"; },
            () -> { gate("gpl"); return source.equals("gpl") ? reranker : null; },
            () -> source.equals("gpl") ? coordinator : null,
            () -> { boolean after = gate("gpu-capabilities"); return gpuService(after); }, graph.worker(), graph.inference());
        work = new StatusFixtureWork(handler);
        handler = work.handler;
        components.attach(handler);
        if (source.equals("online-ai") || source.equals("gpu-saturation")) {
          var monitor = mock(io.justsearch.ui.observability.GpuSaturationMonitor.class);
          when(monitor.compute(anyLong())).thenAnswer(i -> {
            boolean after = gate("gpu-saturation");
            // Online-AI's true value must affect the public verdict; monitor absence would be READY.
            return new io.justsearch.ui.observability.GpuSaturationMonitor.Result(95.0,
                (source.equals("online-ai") && (long) i.getArgument(0) > 0)
                    || (source.equals("gpu-saturation") && after) ? "HEALTHY" : "SATURATED");
          });
          handler.setGpuSaturationMonitor(monitor);
        }
        if (source.equals("registry")) {
          var registry = mock(EngineComponentRegistry.class);
          when(registry.snapshot()).thenAnswer(i -> { gate("registry"); return components.registry().snapshot(); });
          when(registry.subscribe(any())).thenAnswer(i -> {
            java.util.function.Consumer<io.justsearch.core.component.EngineComponentSnapshot> listener = i.getArgument(0);
            return components.registry().subscribe(snapshot -> {
              listener.accept(snapshot);
              registryEvents.add(snapshot); // Record successful consumption by the real handler callback.
            });
          });
          handler.setIndexComponent(registry, components.index());
        }
        handler.setClockForTesting(now::get);
        handler.setConversationProtectionSupplier(() -> { boolean after = gate("conversation"); return source.equals("conversation") && after ? "unlocked" : "locked"; });
        var telemetry = new io.justsearch.telemetry.TelemetryHealthState();
        telemetry.recordFlushFailure();
        var recoveredTelemetry = new io.justsearch.telemetry.TelemetryHealthState();
        recoveredTelemetry.recordFlushFailure();
        recoveredTelemetry.recordFlushFailure();
        recoveredTelemetry.recordDiskSpaceLowEvent();
        handler.setTelemetryHealthSupplier(() -> { boolean after = gate("telemetry"); return source.equals("telemetry") ? (after ? recoveredTelemetry : telemetry) : null; });
        handler.setVduCapabilitySnapshotSupplier(() -> { boolean after = gate("vdu");
          return source.equals("vdu")
              ? new io.justsearch.app.services.vdu.VduCapabilityState.Snapshot(
                  after ? "vdu.missing_mmproj" : "vdu.circuit_open", now.get())
              : null; });
        handler.setVduProcessingSupplier(() -> { boolean after = gate("vdu-processing"); return source.equals("vdu-processing") && !after; });
        handler.setDevicePlanSupplier(() -> { boolean after = gate("device-plan");
          if (!source.equals("device-plan")) return null;
          var arena = new io.justsearch.ort.GpuArenaPlan(after
              ? io.justsearch.ort.GpuArenaPlan.Profile.P16_PLUS : io.justsearch.ort.GpuArenaPlan.Profile.P12,
              false, new io.justsearch.ort.GpuArenaPlan.Chat(java.util.Optional.empty(),
                  java.util.OptionalInt.empty(), false, 0.0, false),
              java.util.Optional.empty(), java.util.Map.of(), 0, java.util.Set.of());
          return new io.justsearch.app.services.gpu.GpuProfileResolver.Resolution(
              java.util.Optional.of(arena), io.justsearch.app.api.status.operating.ProfileSource.MEASURED, 0);
        });
        var probe = mock(io.justsearch.app.services.atrest.DiskEncryptionProbe.class);
        when(probe.current()).thenReturn(io.justsearch.app.services.atrest.AtRestProtection.unknown());
        when(probe.reading()).thenAnswer(i -> {
          long readingTime = readingAtMs.get();
          boolean after = gate("at-rest");
          consumedEncryptionTime.set(readingTime);
          if (!source.equals("at-rest") && !source.endsWith("lock")) return null;
          return new io.justsearch.app.services.atrest.DiskEncryptionProbe.Reading(
              source.equals("at-rest") && after
                  ? new io.justsearch.app.services.atrest.AtRestProtection(
                      io.justsearch.app.services.atrest.AtRestProtection.State.NOT_ENCRYPTED, "fixture-volume",
                      io.justsearch.app.services.atrest.AtRestProtection.Confidence.HIGH) : encrypted(),
              readingTime, Duration.ZERO, false);
        });
        handler.setAtRestTap(null, probe);
        {
          // Every held-producer case has a healthy independent Worker to protect.
          var ks = mock(io.justsearch.app.services.worker.KnowledgeServerBootstrap.class);
          var client = mock(io.justsearch.app.services.worker.KnowledgeClient.class);
          when(ks.hasClient()).thenReturn(true);
          when(client.getWorkerOperationalView(any())).thenAnswer(i -> {
            long documents = indexedDocuments.get();
            gate("worker");
            consumedWorkerDocuments.set(documents);
            return WorkerOperationalViewBuilder.builder(WorkerOperationalView.fallback("READY"))
                .core(new CoreIndexView(true, documents, 0, "SERVING", 0, 0))
                .visualExtraction(new VisualExtractionView(false, false, null, null, 0L,
                    source.equals("vdu") ? 1L : 0L, null, false)).build();
          });
          BootstrapLeaseFixtures.bind(ks, client);
          handler.setKnowledgeServer(ks, null);
        }
        if (source.equals("rrd")) {
          var rrd = mock(io.justsearch.telemetry.RrdMetricStore.class);
          when(rrd.query(anyString(), anyLong(), anyLong())).thenAnswer(i -> {
            boolean after = gate("rrd"); return new io.justsearch.telemetry.RrdMetricStore.TimeSeriesResult(
                i.getArgument(0), new long[] {i.getArgument(2)}, new double[] {after ? 42.5 : 37.5});
          });
          handler.setRrdStoreSupplier(() -> rrd);
        }
        if (source.equals("bulk-gate")) {
          var ks = mock(io.justsearch.app.services.worker.KnowledgeServerBootstrap.class);
          var bulk = new io.justsearch.core.scheduling.BulkWorkGate();
          bulk.setGame(true);
          when(ks.bulkWork()).thenReturn(bulk);
          handler.setKnowledgeServerLifecycleSupplier(() -> { boolean after = gate("bulk-gate"); bulk.setGame(!after); return ks; });
        }
        if (source.equals("power") || source.equals("resume")) {
          var ks = mock(io.justsearch.app.services.worker.KnowledgeServerBootstrap.class);
          when(ks.bulkWork()).thenReturn(new io.justsearch.core.scheduling.BulkWorkGate());
          when(ks.energyState()).thenAnswer(i -> {
            boolean after = gate("power");
            return source.equals("power") && after
                ? new io.justsearch.app.util.EnergyState(io.justsearch.app.util.EnergyState.Intent.REDUCED,
                    io.justsearch.app.util.EnergyState.Source.BATTERY)
                : new io.justsearch.app.util.EnergyState(io.justsearch.app.util.EnergyState.Intent.FULL,
                    io.justsearch.app.util.EnergyState.Source.AC);
          });
          when(ks.recentlyResumed(anyLong())).thenAnswer(i -> { boolean after = gate("resume"); return !source.equals("resume") || !after; });
          handler.setKnowledgeServerLifecycleSupplier(() -> ks);
        }
        if (source.equals("gpu-memory")) {
          var reader = mock(io.justsearch.app.api.gpu.GpuMemoryReader.class);
          when(reader.sampleNow()).thenAnswer(i -> {
            work.gpuSampleEntered();
            long readingTime = readingAtMs.get();
            boolean after = gate("gpu-memory");
            consumedGpuTime.set(readingTime);
            return new io.justsearch.app.api.gpu.GpuMemorySample(
                io.justsearch.app.api.gpu.GpuMemorySource.NVML, java.util.Optional.empty(),
                java.util.Optional.of(new io.justsearch.app.api.gpu.GpuDevice("luid_0x00000000_0x00000001", java.util.OptionalInt.of(0))),
                java.util.Optional.of(after ? "recovered GPU" : "fixture GPU"),
                java.util.OptionalLong.of((after ? 16L : 12L) * 1024 * 1024 * 1024),
                java.util.OptionalLong.of((after ? 2L : 1L) * 1024 * 1024 * 1024),
                java.util.Map.of(), Instant.ofEpochMilli(readingTime));
          });
          handler.setGpuMemoryReader(reader);
          // The first native sample must have completed before we block a refresh.
          var field = StatusLifecycleHandler.class.getDeclaredField("gpuMemory");
          field.setAccessible(true);
          Object hold = field.get(handler);
          var method = hold.getClass().getDeclaredMethod("projection");
          method.setAccessible(true);
          var projection = (OperatingGpuProjection) method.invoke(hold);
          work.awaitGpuFirstSample(); // Join the actual background owner, rather than race it with a second caller.
          var at = OperatingGpuProjection.class.getDeclaredField("cachedAt");
          at.setAccessible(true);
          at.set(projection, Instant.ofEpochMilli(now.get()).minusSeconds(10));
        }
        app = Javalin.create(c -> c.showJavalinBanner = false);
        new ApiSecurityFilters(false, null, null, null, null, null, admission).install(app);
        io.justsearch.ui.api.routes.StatusRoutes.registerLifecycleRoutes(
            app, work::handleStatus, handler::handleHealth);
        app.start("127.0.0.1", 0);
      } catch (Exception | Error failure) {
        cleanUp(failure);
        throw failure;
      }
    }

    private static io.justsearch.app.services.atrest.AtRestProtection encrypted() {
      return new io.justsearch.app.services.atrest.AtRestProtection(
          io.justsearch.app.services.atrest.AtRestProtection.State.ENCRYPTED, "fixture-volume",
          io.justsearch.app.services.atrest.AtRestProtection.Confidence.HIGH);
    }

    void assertSupportedCadence(String lane, StatusObservation.Sample<?> publication) {
      long period = publication.periodMs();
      boolean supported = switch (lane) {
        case "models" -> period == 30_000L;
        case "gpu", "at-rest" -> period == 5_000L || period == 10_000L;
        default -> period == 2_000L || period == 10_000L;
      };
      assertTrue(supported, "supported busy/idle/cache period: " + lane);
      assertTrue(publication.cadenceMs() >= period && publication.cadenceMs() <= 4L * period,
          "effective cadence includes at most three periods of probe allowance: " + lane);
    }

    void assertPublicPublication(JsonNode body, String lane, StatusObservation.Sample<?> publication) throws Exception {
      var mapper = new ObjectMapper();
      String directField = switch (lane) {
        case "disk" -> "diskPressure";
        case "inference", "gpu", "power" -> lane;
        case "conversation" -> "conversationProtection";
        case "at-rest" -> "atRestProtection";
        case "models" -> "modelDistribution";
        case "agent" -> "agentSessions";
        case "resume" -> "catchingUp";
        default -> null;
      };
      if (directField != null) assertEquals(mapper.readTree(mapper.writeValueAsString(publication.value())),
          body.path(directField), "HTTP must expose the exact typed publication: " + lane);
      if (lane.startsWith("readiness-")) assertEquals(mapper.readTree(mapper.writeValueAsString(publication.value())),
          body.path("readiness").path("components").path(lane.substring("readiness-".length())),
          "HTTP must expose the readiness source and its exact observation time");
    }

    void assertKnownPublic(JsonNode body) {
      assertRegistryTimes(body);
      VerifierStatusIsolationTest.assertWorkerEpoch(body, now.get());
      if (source.equals("worker") || source.endsWith("lock"))
        VerifierStatusIsolationTest.assertWorkerTimes(body, now.get(), false, projectionStartedMs,
            requestStartedMs, requestCompletedMs, java.util.Map.of());
      boolean after = recovered.get();
      var dimensions = body.path("readiness").path("components");
      switch (source) {
        case "disk-pressure" -> assertEquals(after ? "WARNING" : "OK", body.path("diskPressure").asText());
        case "inference" -> {
          assertEquals("ONLINE", body.path("inference").path("phase").asText());
          assertEquals(after ? "Recovering" : "Healthy", body.path("inference").path("engineState").asText());
          assertEquals("CHAT", body.path("inference").path("leaseHolder").asText());
          assertEquals(after ? "LOADING" : "READY", body.path("operating").path("chat").path("state").asText());
          if (after) assertEquals("RECOVERY", body.path("operating").path("chat").path("reason").asText());
        }
        case "gpu-capabilities", "rrd" -> {
          assertTrue(body.path("gpu").path("available").asBoolean());
          assertEquals(source.equals("gpu-capabilities") && after ? 42 : 37,
              body.path("gpu").path("gpuUtilizationPercent").asInt());
          assertEquals("nvml", body.path("gpu").path("source").asText());
          assertEquals("HIGH", body.path("gpu").path("confidence").asText());
          assertTrue(body.path("gpu").path("cudaFunctional").asBoolean());
          if (source.equals("rrd")) {
            assertEquals(after ? 42.5 : 37.5, body.path("gpu").path("recentUtilizationPercent").path(0).asDouble());
            assertEquals(after ? 42.5 : 37.5, body.path("gpu").path("recentMemoryUtilizationPercent").path(0).asDouble());
          }
        }
        case "gpl" -> {
          var component = dimensions.path("lambdamartModel");
          assertEquals("READY", component.path("state").asText());
          if (after) assertEquals("lambdamart.not_configured", component.path("reasonCode").asText());
          else assertTrue(component.path("reasonCode").isMissingNode() || component.path("reasonCode").isNull(),
              "a loaded model is distinct from legitimate not-configured absence");
          assertFalse(component.path("stale").asBoolean());
        }
        case "conversation" -> assertEquals(after ? "unlocked" : "locked", body.path("conversationProtection").path("state").asText());
        case "telemetry" -> {
          assertEquals(after ? 2L : 1L, body.path("telemetryHealth").path("flushFailureCount").asLong(-1L));
          assertEquals(0L, body.path("telemetryHealth").path("gaugeCallbackFailureCount").asLong(-1L));
        }
        case "vdu" -> assertEquals(after ? "vdu.missing_mmproj" : "vdu.circuit_open",
            body.path("worker").path("visualExtraction").path("vduBlockedReason").asText());
        case "vdu-processing" -> {
          assertTrue(body.path("worker").path("visualExtraction").path("vduProcessing").isBoolean());
          assertEquals(!after, body.path("worker").path("visualExtraction").path("vduProcessing").asBoolean());
        }
        case "device-plan" -> {
          assertEquals(after ? "P16_PLUS" : "P12", body.path("operating").path("gpu").path("profile").asText());
          assertEquals("MEASURED", body.path("operating").path("gpu").path("profileSource").asText());
        }
        case "bulk-gate" -> {
          assertTrue(body.path("operating").path("indexing").path("reasons").isArray());
          assertEquals(!after, body.path("operating").path("indexing").path("reasons").toString().contains("GAME_MODE"));
        }
        case "worker" -> {
          assertEquals(after ? 11L : 10L, body.path("worker").path("core").path("indexedDocuments").asLong());
          assertEquals("SERVING", body.path("worker").path("core").path("indexState").asText());
          assertEquals(now.get(), body.path("meta").path("workerRpcAtMs").asLong());
          assertFalse(body.path("meta").path("workerRpcStale").asBoolean());
        }
        case "registry" -> {
          assertEquals(after ? "DEGRADED" : "READY", dimensions.path("ai").path("state").asText());
          assertFalse(dimensions.path("ai").path("stale").asBoolean());
          assertEquals(after ? "FAILED" : "READY", body.path("components").path("generative").path("state").asText());
          if (after) {
            assertEquals("inference.activation_failed", body.path("components").path("generative").path("reason_code").asText());
            assertEquals("inference.activation_failed", dimensions.path("ai").path("reasonCode").asText());
          }
        }
        case "assembly-lock", "admission-lock" -> {
          assertEquals(after ? 11L : 10L, body.path("worker").path("core").path("indexedDocuments").asLong());
          assertEquals("SERVING", body.path("worker").path("core").path("indexState").asText());
          assertEquals(now.get(), body.path("meta").path("workerRpcAtMs").asLong());
          assertFalse(body.path("meta").path("workerRpcStale").asBoolean());
          assertEquals("READY", dimensions.path("ai").path("state").asText());
          assertFalse(dimensions.path("ai").path("stale").asBoolean());
          assertEquals("ENCRYPTED", body.path("atRestProtection").path("diskEncryption").asText());
          assertEquals("fixture-volume", body.path("atRestProtection").path("source").asText());
          assertEquals("HIGH", body.path("atRestProtection").path("confidence").asText());
          assertEquals(readingAtMs.get(), body.path("atRestProtection").path("probedAtMs").asLong());
        }
        case "at-rest" -> {
          assertEquals(after ? "NOT_ENCRYPTED" : "ENCRYPTED", body.path("atRestProtection").path("diskEncryption").asText());
          assertEquals("fixture-volume", body.path("atRestProtection").path("source").asText());
          assertEquals("HIGH", body.path("atRestProtection").path("confidence").asText());
          assertNotEquals(now.get(), readingAtMs.get(), "the reading time is independent of the wrapper probe time");
          assertEquals(readingAtMs.get(), body.path("atRestProtection").path("probedAtMs").asLong());
        }
        case "gpu-memory" -> {
          var gpu = body.path("operating").path("gpu");
          assertEquals("NVML", gpu.path("source").asText());
          assertEquals(after ? "recovered GPU" : "fixture GPU", gpu.path("adapter").asText());
          assertEquals((after ? 16L : 12L) * 1024 * 1024 * 1024, gpu.path("adapterTotalBytes").asLong());
          assertEquals((after ? 14L : 11L) * 1024 * 1024 * 1024, gpu.path("adapterFreeBytes").asLong());
          assertNotEquals(now.get(), readingAtMs.get(), "supplied GPU time must not be restamped as wrapper time");
          assertEquals(Instant.ofEpochMilli(readingAtMs.get()).toString(), gpu.path("sampledAt").asText());
          assertEquals(now.get() - readingAtMs.get(), gpu.path("ageMs").asLong(-1L));
          assertFalse(gpu.path("stale").asBoolean());
        }
        case "agent" -> assertEquals(after ? 8 : 7, body.path("agentSessions").path("activeCount").asInt(-1));
        case "online-ai", "gpu-saturation" -> {
          boolean saturated = source.equals("online-ai") ? after : !after;
          assertEquals(saturated ? "DEGRADED" : "READY", dimensions.path("gpu").path("state").asText());
          if (saturated) assertEquals("gpu.saturated", dimensions.path("gpu").path("reasonCode").asText());
          else assertTrue(dimensions.path("gpu").path("reasonCode").isMissingNode() || dimensions.path("gpu").path("reasonCode").isNull());
          assertFalse(dimensions.path("gpu").path("stale").asBoolean());
        }
        case "model-file-path" -> {
          var models = body.path("modelDistribution");
          assertEquals("CPU", models.path("installProfile").asText());
          if (after) {
            assertTrue(models.path("modelVariants").path("reranker").path("skipped").isBoolean());
            assertTrue(models.path("modelVariants").path("reranker").path("skipped").asBoolean());
            assertEquals("User declined reranker", models.path("modelVariants").path("reranker").path("skipReason").asText());
          } else assertTrue(models.path("modelVariants").path("reranker").isMissingNode());
        }
        case "power" -> {
          assertEquals(after ? "BATTERY" : "AC", body.path("power").path("source").asText());
          assertTrue(body.path("power").path("energyReduced").isBoolean());
          assertEquals(after, body.path("power").path("energyReduced").asBoolean());
          assertTrue(body.path("operating").path("indexing").path("reasons").isArray());
          assertEquals(after, body.path("operating").path("indexing").path("reasons").toString().contains("ENERGY_SAVER"));
        }
        case "resume" -> {
          assertTrue(body.path("catchingUp").isBoolean());
          assertEquals(!after, body.path("catchingUp").asBoolean());
        }
        case "vision" -> {
          assertTrue(body.path("worker").path("core").path("vduAvailable").isBoolean());
          assertEquals(!after, body.path("worker").path("core").path("vduAvailable").asBoolean());
          assertFalse(dimensions.path("visualDocumentUnderstanding").path("stale").asBoolean());
        }
        default -> throw new AssertionError("no public value for " + source);
      }
    }

    void assertRegistryTimes(JsonNode body) {
      assertEquals(registryStateSince.toString(),
          body.path("components").path("generative").path("state_since").asText());
      assertEquals(registryStateSince.toString(),
          body.path("readiness").path("engineComponents").path("generative").path("stateSince").asText());
      long observed = Instant.parse(body.path("readiness").path("components").path("ai").path("observedAt").asText()).toEpochMilli();
      assertTrue(observed >= requestStartedMs && observed <= requestCompletedMs,
          "cheap registry readiness uses the actual response projection time");
      long age = body.path("readiness").path("components").path("ai").path("stalenessMs").asLong(-1L);
      assertEquals(block.get() && (source.equals("registry") || source.endsWith("lock"))
          ? now.get() - initialAtMs : 0L, age);
    }

    void assertHeldPublic(JsonNode body) {
      assertRegistryTimes(body);
      VerifierStatusIsolationTest.assertWorkerEpoch(body,
          source.equals("worker") || source.endsWith("lock") ? initialAtMs : now.get());
      if (source.equals("worker") || source.endsWith("lock"))
        VerifierStatusIsolationTest.assertWorkerTimes(body, initialAtMs, true, initialAtMs,
            requestStartedMs, requestCompletedMs, source.endsWith("lock")
                ? java.util.Map.of("indexServing", now.get() - initialAtMs, "embedding", now.get() - initialAtMs,
                    "chunkEmbedding", now.get() - initialAtMs, "visualTextExtraction", now.get() - initialAtMs,
                    "visualDocumentUnderstanding", now.get() - initialAtMs, "gpu", now.get() - initialAtMs)
                : java.util.Map.of());
      if (source.equals("worker")) {
        // The fresh request may reproject healthy head companions. Bound time, do not freeze it.
        for (String dimension : new String[] {"telemetry", "lambdamartModel"}) {
          var component = body.path("readiness").path("components").path(dimension);
          long observed = Instant.parse(component.path("observedAt").asText()).toEpochMilli();
          assertTrue(observed >= projectionStartedMs && observed <= requestCompletedMs);
          assertEquals(0L, component.path("stalenessMs").asLong(-1L));
        }
      }
      // Cached value assertions supplement the held raw Sample identity and freshness checks.
      switch (source) {
        case "inference" -> {
          assertEquals("Healthy", body.path("inference").path("engineState").asText());
          assertEquals("READY", body.path("operating").path("chat").path("state").asText());
        }
        case "telemetry" -> {
          assertEquals(1L, body.path("telemetryHealth").path("flushFailureCount").asLong(-1L));
          assertEquals(0L, body.path("telemetryHealth").path("gaugeCallbackFailureCount").asLong(-1L));
        }
        case "vdu" -> assertEquals("vdu.circuit_open", body.path("worker").path("visualExtraction").path("vduBlockedReason").asText());
        case "vdu-processing" -> assertTrue(body.path("worker").path("visualExtraction").path("vduProcessing").asBoolean());
        case "vision" -> assertTrue(body.path("worker").path("core").path("vduAvailable").asBoolean());
        case "device-plan" -> assertEquals("P12", body.path("operating").path("gpu").path("profile").asText());
        case "bulk-gate" -> assertTrue(body.path("operating").path("indexing").path("reasons").toString().contains("GAME_MODE"));
        case "registry" -> {
          assertEquals("READY", body.path("components").path("generative").path("state").asText());
          assertEquals("READY", body.path("readiness").path("components").path("ai").path("state").asText());
        }
        case "worker", "assembly-lock", "admission-lock" -> {
          assertEquals(10L, body.path("worker").path("core").path("indexedDocuments").asLong());
          assertEquals(initialAtMs, body.path("meta").path("workerRpcAtMs").asLong());
        }
        case "gpu-memory" -> {
          var gpu = body.path("operating").path("gpu");
          assertEquals("NVML", gpu.path("source").asText());
          assertEquals("fixture GPU", gpu.path("adapter").asText());
          assertEquals(12L * 1024 * 1024 * 1024, gpu.path("adapterTotalBytes").asLong());
          assertEquals(Instant.ofEpochMilli(readingAtMs.get()).toString(), gpu.path("sampledAt").asText());
          assertEquals(now.get() - readingAtMs.get(), gpu.path("ageMs").asLong(-1L));
          assertTrue(gpu.path("stale").asBoolean());
        }
        default -> { } // Sentinel-only surfaces and held readiness lanes are checked separately.
      }
    }

    long sourceObservedAt(String lane) {
      return lane.equals("gpu-memory") ? Math.min(now.get(), readingAtMs.get()) : now.get();
    }

    void advanceRecoveryInput() throws Exception {
      // No changed input can leak through the released old call or its queued companions.
      oldSourceReads = (int) sourceReads.stream().filter(read -> read.dependency().equals(witnessDependency())).count();
      oldRerankerReads = rerankerReads.size();
      oldCoordinatorReads = coordinatorReads.size();
      oldRegistryEvents = registryEvents.size();
      recovered.set(true);
      readingAtMs.set(now.get() - 251L);
      if (source.equals("worker") || source.endsWith("lock")) indexedDocuments.set(11L);
      if (source.equals("registry")) {
        components.transition("generative", io.justsearch.core.component.ComponentState.FAILED,
            "inference.activation_failed", "fixture recovery input");
        registryStateSince = components.handle("generative").snapshot().stateSince();
      }
      if (source.equals("model-file-path")) {
        var model = io.justsearch.configuration.model.InstallContract.InstalledModel.skipped("reranker",
            io.justsearch.configuration.model.SkipCause.USER_DECLINED, "User declined reranker");
        io.justsearch.configuration.model.InstallContractIO.write(
            new io.justsearch.configuration.model.InstallContract(2, now.get(),
                new io.justsearch.configuration.model.HardwareProfile(false, false, -1L),
                io.justsearch.configuration.model.DownloadProfile.CPU, java.util.Map.of("reranker", model), modelHome), modelHome);
      }
    }

    void drainPublications() throws Exception {
      var held = !block.get() ? java.util.Set.<String>of()
          : source.equals("bulk-gate") ? java.util.Set.of("power", "resume", "bulk-gate", "indexing-hold")
          : java.util.Set.of(heldLanes());
      work.drain(held);
    }

    String witnessDependency() {
      return source.endsWith("lock") ? "worker" : source;
    }

    void assertNewInputConsumed() {
      var calls = sourceReads.stream().filter(read -> read.dependency().equals(witnessDependency())).toList();
      assertTrue(calls.size() > oldSourceReads, "recovery must invoke the actual changed source: " + source);
      assertTrue(calls.stream().skip(oldSourceReads).allMatch(SourceRead::after),
          "new source invocations must consume the changed fixture epoch: " + source);
      if (source.equals("worker") || source.endsWith("lock")) assertEquals(11L, consumedWorkerDocuments.get());
      if (source.equals("registry")) assertTrue(registryEvents.stream().skip(oldRegistryEvents)
          .anyMatch(snapshot -> snapshot.components().stream().anyMatch(component ->
              component.spec().name().equals("generative")
                  && component.state() == io.justsearch.core.component.ComponentState.FAILED
                  && component.stateSince().equals(registryStateSince))),
          "the real subscription consumed the changed owner transition, in addition to the recovery poll");
      if (source.equals("agent")) assertEquals(8L, consumedAgentCount.get());
      if (source.equals("gpl")) {
        assertTrue(rerankerReads.stream().limit(oldRerankerReads).anyMatch(Boolean::booleanValue),
            "configured loaded-model evidence precedes the hold");
        assertTrue(rerankerReads.size() > oldRerankerReads);
        assertTrue(rerankerReads.stream().skip(oldRerankerReads).noneMatch(Boolean::booleanValue),
            "recovery consumed the supplied unloaded model");
        assertTrue(coordinatorReads.size() > oldCoordinatorReads, "intentional absence consumes a known completed coordinator");
        assertTrue(coordinatorReads.stream().skip(oldCoordinatorReads).allMatch(status ->
            status.status() == io.justsearch.app.api.gpl.GplJobStatus.Status.COMPLETED));
      }
      if (source.equals("gpu-memory")) assertEquals(readingAtMs.get(), consumedGpuTime.get());
      if (source.equals("at-rest") || source.endsWith("lock")) assertEquals(readingAtMs.get(), consumedEncryptionTime.get());
    }

    String companionLane() {
      return switch (source) {
        case "gpl" -> "readiness-lambdamartModel";
        case "telemetry" -> "readiness-telemetry";
        case "vdu", "vdu-processing", "vision" -> "readiness-visualDocumentUnderstanding";
        case "online-ai", "gpu-saturation" -> "readiness-gpu";
        default -> null;
      };
    }

    boolean companionIsHeld() {
      return source.equals("gpl") || source.equals("online-ai") || source.equals("gpu-saturation");
    }

    ReadinessComponentView captureCompanion() throws Exception {
      String lane = companionLane();
      if (lane == null) return null;
      var sample = published(lane);
      companionObservedAtMs = sample.observedAtMs();
      return (ReadinessComponentView) sample.value();
    }

    void assertCompanion(JsonNode body, ReadinessComponentView publication, Instant started, Instant completed) {
      String lane = companionLane();
      if (lane == null) return;
      assertNotNull(publication, "capture the relevant companion before HTTP");
      boolean after = recovered.get();
      String state = "READY";
      String reason = null;
      String provenance;
      switch (source) {
        case "gpl" -> { provenance = "head_gpl_status"; if (after) reason = "lambdamart.not_configured"; }
        case "telemetry" -> {
          provenance = "telemetry_health";
          if (after) { state = "DEGRADED"; reason = "telemetry.disk_space_low"; }
        }
        case "vdu", "vdu-processing", "vision" -> {
          provenance = "head_vdu_status";
          if (source.equals("vdu")) { state = "DEGRADED"; reason = after ? "vdu.missing_mmproj" : "vdu.circuit_open"; }
        }
        case "online-ai", "gpu-saturation" -> {
          provenance = "gpu_saturation_monitor";
          if (source.equals("online-ai") ? after : !after) { state = "DEGRADED"; reason = "gpu.saturated"; }
        }
        default -> throw new AssertionError(source);
      }
      var component = body.path("readiness").path("components").path(lane.substring("readiness-".length()));
      assertEquals(state, publication.state());
      assertEquals(reason, publication.reasonCode());
      assertEquals(provenance, publication.source());
      assertEquals(state, component.path("state").asText());
      assertEquals(reason, component.path("reasonCode").asText(null));
      assertEquals(provenance, component.path("source").asText());
      assertEquals(Math.max(0L, now.get() - companionObservedAtMs), component.path("stalenessMs").asLong(-1L),
          "companion age uses its own injected-clock lane; a stale raw input can have a zero-age companion");
      // DTO time is real projection time, independently bounded; never raw injected/event time.
      Instant observed = Instant.parse(publication.observedAt());
      assertFalse(observed.isBefore(started), "companion predates its actual projection window");
      assertFalse(observed.isAfter(completed), "companion is later than its actual projection window");
      assertEquals(publication.observedAt(), component.path("observedAt").asText(),
          "HTTP must retain the captured companion's real observation time");
    }

    void assertPublicFreshness(JsonNode body, boolean stale) {
      String dimension = switch (source) {
        case "gpl" -> "lambdamartModel";
        case "telemetry" -> "telemetry";
        case "vdu", "vdu-processing", "vision" -> "visualDocumentUnderstanding";
        case "online-ai", "gpu-saturation" -> "gpu";
        case "registry", "assembly-lock", "admission-lock" -> "ai";
        default -> null;
      };
      if (dimension != null) {
        var component = body.path("readiness").path("components").path(dimension);
        String expectedSource = switch (dimension) {
          case "lambdamartModel" -> "head_gpl_status";
          case "telemetry" -> "telemetry_health";
          case "visualDocumentUnderstanding" -> "head_vdu_status";
          case "gpu" -> "gpu_saturation_monitor";
          case "ai" -> "lifecycle_inference";
          default -> throw new AssertionError(dimension);
        };
        assertEquals(expectedSource, component.path("source").asText());
        assertTrue(component.path("stale").isBoolean(), "public freshness marker: " + source);
        assertEquals(stale, component.path("stale").asBoolean(), "public owner freshness: " + source);
      }
      String block = switch (source) {
        case "inference" -> "chat";
        case "bulk-gate", "power" -> "indexing";
        case "gpu-memory", "device-plan" -> "gpu";
        default -> null;
      };
      if (block != null) {
        var sources = body.path("operating").path("freshness").path("staleSources");
        assertTrue(sources.isArray(), "public operating freshness must be present");
        boolean listed = false;
        for (var entry : sources) if (block.equals(entry.asText())) listed = true;
        assertEquals(stale, listed, "public owner freshness: " + source);
      }
      switch (source) {
        case "worker", "assembly-lock", "admission-lock" -> {
          assertTrue(body.path("meta").path("workerRpcStale").isBoolean());
          assertEquals(stale, body.path("meta").path("workerRpcStale").asBoolean());
          if (source.endsWith("lock")) assertEquals(stale ? "UNKNOWN" : "ENCRYPTED",
              body.path("atRestProtection").path("diskEncryption").asText());
        }
        case "gpu-memory" -> {
          assertTrue(body.path("operating").path("gpu").path("stale").isBoolean());
          assertEquals(stale, body.path("operating").path("gpu").path("stale").asBoolean());
        }
        // The following fields expose absence/unknown sentinels, without a separate freshness
        // flag or wire observation time (except encryption's probedAtMs). Check known identity
        // versus that sentinel; lane publication identity/time is checked in all phases too.
        case "disk-pressure" -> {
          if (stale) assertTrue(body.path("diskPressure").isMissingNode() || body.path("diskPressure").isNull());
          else assertEquals(recovered.get() ? "WARNING" : "OK", body.path("diskPressure").asText());
        }
        case "gpu-capabilities", "rrd" -> {
          assertTrue(body.path("gpu").path("available").isBoolean());
          assertEquals(!stale, body.path("gpu").path("available").asBoolean());
        }
        case "conversation" -> assertEquals(stale ? "unknown" : recovered.get() ? "unlocked" : "locked",
            body.path("conversationProtection").path("state").asText());
        case "at-rest" -> assertEquals(stale ? "UNKNOWN" : recovered.get() ? "NOT_ENCRYPTED" : "ENCRYPTED",
            body.path("atRestProtection").path("diskEncryption").asText());
        case "agent" -> {
          if (stale) assertTrue(body.path("agentSessions").isMissingNode() || body.path("agentSessions").isNull());
          else assertEquals(recovered.get() ? 8 : 7, body.path("agentSessions").path("activeCount").asInt(-1));
        }
        case "model-file-path" -> {
          if (stale) assertTrue(body.path("modelDistribution").path("installProfile").isMissingNode());
          else assertEquals("CPU", body.path("modelDistribution").path("installProfile").asText());
        }
        case "power" -> assertEquals(stale ? "UNKNOWN" : recovered.get() ? "BATTERY" : "AC", body.path("power").path("source").asText());
        case "resume" -> {
          assertTrue(body.path("catchingUp").isBoolean());
          assertEquals(!stale && !recovered.get(), body.path("catchingUp").asBoolean());
        }
        default -> {
          if (dimension == null && block == null) throw new AssertionError("no freshness contract for " + source);
        }
      }
    }

    String[] heldLanes() {
      return switch (source) {
        case "assembly-lock", "admission-lock" -> new String[] {"worker", "registry", "at-rest"};
        case "disk-pressure" -> new String[] {"disk"};
        case "gpu-capabilities", "rrd" -> new String[] {"gpu"};
        case "gpl" -> new String[] {"readiness-lambdamartModel"};
        case "online-ai", "gpu-saturation" -> new String[] {"readiness-gpu"};
        case "model-file-path" -> new String[] {"models"};
        default -> new String[] {source};
      };
    }

    StatusObservation<?> lane(String name) throws Exception {
      var field = StatusLifecycleHandler.class.getDeclaredField("observations");
      field.setAccessible(true);
      var lanes = (java.util.Map<?, ?>) field.get(handler);
      var lane = (StatusObservation<?>) lanes.get(name);
      assertNotNull(lane, "published lane " + name);
      return lane;
    }

    StatusObservation.Sample<?> published(String name) throws Exception {
      var field = StatusObservation.class.getDeclaredField("sample");
      field.setAccessible(true);
      var sample = (StatusObservation.Sample<?>) field.get(lane(name));
      assertNotNull(sample, "publication from " + name);
      return sample;
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
      at.set(projection, Instant.ofEpochMilli(now.get()).minusSeconds(10));
    }

    io.justsearch.gpu.GpuCapabilitiesService gpuService(boolean after) {
      if (!source.equals("rrd") && !source.equals("gpu-capabilities")) return null;
      var service = mock(io.justsearch.gpu.GpuCapabilitiesService.class);
      var caps = mock(io.justsearch.gpu.GpuCapabilities.class);
      var nvml = mock(io.justsearch.gpu.GpuCapabilities.Nvml.class);
      when(nvml.available()).thenReturn(true);
      when(nvml.gpuUtilizationPercent()).thenReturn(source.equals("gpu-capabilities") && after ? 42 : 37);
      when(caps.nvml()).thenReturn(nvml);
      when(caps.effective()).thenReturn(new io.justsearch.gpu.GpuCapabilities.Effective(
          true, "nvml", io.justsearch.gpu.GpuCapabilities.Confidence.HIGH,
          "580.1", 580, 1, 1, 12L << 30, 8L << 30, 4L << 30,
          new io.justsearch.gpu.GpuCapabilities.Cuda(true, "cuda-driver-api",
              io.justsearch.gpu.GpuCapabilities.Confidence.HIGH)));
      when(service.snapshot(any(io.justsearch.gpu.GpuCapabilities.Cuda.class))).thenReturn(caps);
      return service;
    }

    boolean gate(String dependency) {
      work.sourceEntered();
      boolean after = recovered.get();
      sourceReads.add(new SourceRead(dependency, after));
      if (!source.equals(dependency)) return after;
      if (fail.get()) throw new IllegalStateException("status producer failed");
      if (block.get()) pause();
      return after;
    }
    void pause() {
      entered.countDown();
      try {
        if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("unreleased test gate");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        var failure = new AssertionError(e);
        work.errors.add(failure);
        throw failure;
      } catch (Error failure) { work.errors.add(failure); throw failure; }
    }

    HttpResponse<String> get(String query) throws Exception {
      requestStartedMs = System.currentTimeMillis();
      var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
          + "/api/status" + query)).timeout(Duration.ofSeconds(5)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      requestCompletedMs = System.currentTimeMillis();
      return response;
    }

    @Override public void close() throws Exception {
      var failure = new Exception("status fixture cleanup failed");
      cleanUp(failure);
      if (failure.getSuppressed().length > 0) throw failure;
    }

    private void cleanUp(Throwable failure) {
      if (work != null) work.stopIngress();
      block.set(false);
      release.countDown();
      if (work != null) {
        work.gates.forEach(CountDownLatch::countDown);
        work.errors.removeIf(error -> error == failure);
        try { work.drain(java.util.Set.of()); }
        catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
        if (!work.quiescent) return; // Retain config/files/resources while a consumer may still run.
      }
      try {
        if (app != null) app.stop();
      } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
      try {
        if (http != null) http.close();
      } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
      try {
        if (handler != null) handler.closeGpuMemoryReader();
      } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
      try {
        if (components != null) components.close();
      } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
      if (source.equals("model-file-path") && modelHome != null) {
        try {
          if (installedConfig != null)
            io.justsearch.configuration.resolved.ConfigStore.restoreGlobal(installedConfig, priorConfig);
        } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
        try {
          java.nio.file.Files.deleteIfExists(modelHome.resolve(io.justsearch.configuration.model.InstallContract.CONTRACT_FILENAME));
        } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
        try {
          java.nio.file.Files.deleteIfExists(modelHome);
        } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
      }
    }
  }
}
