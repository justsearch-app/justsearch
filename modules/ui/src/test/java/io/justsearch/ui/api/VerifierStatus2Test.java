package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.justsearch.app.api.status.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Independent real-HTTP owner isolation matrix, written before reading candidate tests. */
class VerifierStatus2Test extends VerifierStatusIsolationTest {
  static void set(Object target,String name,Object value) throws Exception {
    var field=target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target,value);
  }
  @ParameterizedTest
  @ValueSource(strings={"inferenceSnapshotSupplier","conversationProtectionStateSupplier",
      "telemetryHealthSupplier","knowledgeServerLifecycleSupplier","gpuCapabilitiesSupplier",
      "vduCapabilitySnapshotSupplier","vduProcessingSupplier","devicePlanSupplier",
      "rrdStoreSupplier","lambdamartRerankerSupplier","gplCoordinatorSupplier"})
  void oneHungSupplierLeavesUnrelatedOwnersFreshAndRecovers(String name) throws Exception {
    try(var f=new Fixture(true)) {
      var clock = new AtomicLong(System.currentTimeMillis());
      f.handler.setClockForTesting(clock::get);
      f.handler.setRefreshRequest(() -> f.freshDispatches.add(Thread.currentThread()));
      String heldLane = switch (name) {
        case "inferenceSnapshotSupplier" -> "inference";
        case "conversationProtectionStateSupplier" -> "conversation";
        case "telemetryHealthSupplier" -> "telemetry";
        case "knowledgeServerLifecycleSupplier" -> "bulk-gate";
        case "gpuCapabilitiesSupplier", "rrdStoreSupplier" -> "gpu";
        case "vduCapabilitySnapshotSupplier" -> "vdu";
        case "vduProcessingSupplier" -> "vdu-processing";
        case "devicePlanSupplier" -> "device-plan";
        case "lambdamartRerankerSupplier", "gplCoordinatorSupplier" -> "readiness-lambdamartModel";
        default -> throw new AssertionError("unmapped mandatory supplier " + name);
      };
      var block=new AtomicBoolean(false);
      // The lifecycle supplier is read independently by power/resume/bulk-gate/indexing-hold.
      var entered=new CountDownLatch(name.equals("knowledgeServerLifecycleSupplier") ? 4 : 1);
      var release=new CountDownLatch(1);
      var calls=new AtomicInteger();
      var input = new AtomicReference<>(knownSupplierValue(name, clock.get(), false));
      Supplier<Object> supplier=()->{
        calls.incrementAndGet();
        if(block.get()){ entered.countDown(); try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);} }
        return input.get();
      };
      switch (name) {
        case "devicePlanSupplier" -> f.handler.setDevicePlanSupplier(
            () -> (io.justsearch.app.services.gpu.GpuProfileResolver.Resolution) supplier.get());
        case "knowledgeServerLifecycleSupplier" -> f.handler.setKnowledgeServerLifecycleSupplier(
            () -> (io.justsearch.app.services.worker.KnowledgeServerBootstrap) supplier.get());
        default -> set(f.handler,name,supplier);
      }
      if(name.equals("gpuCapabilitiesSupplier"))set(f.handler,"cachedGpuStatusAtMs",0L);
      if(name.equals("lambdamartRerankerSupplier")) {
        Object coordinator = knownSupplierValue("gplCoordinatorSupplier", clock.get(), true);
        set(f.handler,"gplCoordinatorSupplier", (Supplier<Object>)()->coordinator);
      }
      if(name.equals("gplCoordinatorSupplier")) {
        Object unloadedReranker = knownSupplierValue("lambdamartRerankerSupplier", clock.get(), true);
        set(f.handler,"lambdamartRerankerSupplier", (Supplier<Object>)()->unloadedReranker);
      }
      if(name.equals("rrdStoreSupplier")) {
        Object capabilities = knownSupplierValue("gpuCapabilitiesSupplier", clock.get(), false);
        set(f.handler,"gpuCapabilitiesSupplier", (Supplier<Object>)()->capabilities);
        set(f.handler,"cachedGpuStatusAtMs",0L);
      }
      if (name.equals("vduCapabilitySnapshotSupplier")) f.workerView.set(
          WorkerOperationalViewBuilder.builder(view()).visualExtraction(
              new VisualExtractionView(false, false, null, null, 0L, 1L, null, false)).build());
      Instant beforeProjection = Instant.now();
      f.handler.sampleAndBuildStatusSnapshot();
      f.drainPublications(null);
      var beforeCompanion = captureCompanion(f, name);
      Instant beforeProjected = Instant.now();
      f.lane(heldLane).completion().get(2, TimeUnit.SECONDS);
      assertTrue(calls.get() > 0, "mandatory supplier prerequisite must reach " + name);
      var before = f.published(heldLane);
      assertEquals(clock.get(), before.observedAtMs(), "known supplier publication before hold");
      assertFalse(before.failed());
      assertFalse(before.staleSource());
      assertNotNull(before.value(), "mandatory supplier must publish a typed value, not absence");
      var knownBody = get(f, "");
      assertKnownPublic(name, knownBody, before, false);
      assertCompanion(name, knownBody, beforeCompanion, false, beforeProjection, beforeProjected);
      assertPublicFreshness(name, knownBody, false, false);
      long period = before.periodMs();
      assertTrue(heldLane.equals("gpu") ? period == 5_000L || period == 10_000L
          : period == 2_000L || period == 10_000L, "supported producer period");
      assertTrue(before.cadenceMs() >= period && before.cadenceMs() <= 4L * period,
          "effective cadence allows at most three periods of probe/cache lag");
      if (name.equals("rrdStoreSupplier")) {
        assertTrue(get(f, "").path("gpu").path("available").asBoolean(),
            "the RRD prerequisite is an available NVML capabilities publication");
      }
      if(name.equals("gpuCapabilitiesSupplier"))set(f.handler,"cachedGpuStatusAtMs",0L);
      if(name.equals("rrdStoreSupplier"))set(f.handler,"cachedGpuStatusAtMs",0L);
      Instant heldProjection = Instant.now();
      block.set(true);
      var sampler=Thread.ofPlatform().daemon().start(f.handler::sampleAndBuildStatusSnapshot);
      try {
        assertTrue(entered.await(2,TimeUnit.SECONDS), "mandatory supplier must enter: " + name);
        sampler.join(6000);
        assertFalse(sampler.isAlive(), "the sampler must finish without waiting for its held supplier");
        assertSame(before, f.published(heldLane), "held supplier cannot publish a new observation");
        clock.set(before.observedAtMs() + 3L * before.cadenceMs() + 1L);
        assertTrue(clock.get() - before.observedAtMs() > 3L * before.cadenceMs());
        f.handler.sampleAndBuildStatusSnapshot();
        assertSame(before, f.published(heldLane), "sampling unrelated owners cannot renew the held source");
        assertTrue(f.lane(heldLane).stale(clock.get()), "the held supplier publication must actually be stale");
        for (String healthy : new String[] {"worker", "registry"}) {
          f.lane(healthy).completion().get(2, TimeUnit.SECONDS);
          var publication = f.published(healthy);
          assertEquals(clock.get(), publication.observedAtMs(), "fresh unrelated publication: " + healthy);
          assertFalse(publication.failed(), healthy);
          assertFalse(publication.staleSource(), healthy);
          assertNotNull(publication.value(), healthy);
        }
        f.drainPublications(heldLane);
        var heldCompanion = captureCompanion(f, name);
        Instant heldProjected = Instant.now();
        var s=get(f,"");
        assertPublicFreshness(name, s, true, false);
        assertHeldPublic(name, s);
        boolean companionHeld = heldLane.startsWith("readiness-");
        assertCompanion(name, s, heldCompanion, false, companionHeld ? beforeProjection : heldProjection,
            companionHeld ? beforeProjected : heldProjected);
        System.out.println("OWNER="+name+" calls="+calls.get());
        assertFalse(s.path("meta").path("workerRpcStale").asBoolean(),"worker independent of "+name);
        assertFalse(s.path("readiness").path("components").path("ai").path("stale").asBoolean(),"registry AI independent of "+name);
        assertEquals("READY", s.path("readiness").path("components").path("ai").path("state").asText());
        if(!name.equals("telemetryHealthSupplier")) assertFalse(s.path("readiness").path("components").path("telemetry").path("stale").asBoolean(),"telemetry independent of "+name);
        if(name.equals("conversationProtectionStateSupplier")) assertEquals("unknown",s.path("conversationProtection").path("state").asText());
        if(name.equals("inferenceSnapshotSupplier")) assertTrue(s.path("operating").path("freshness").path("staleSources").toString().contains("chat"),"hung inference stale chat");
        int heldCalls=calls.get();
        get(f,"?fresh=true");get(f,"?fresh=true");get(f,"?fresh=true");
        assertEquals(heldCalls,calls.get(),"no duplicate blocked producer");
      } finally {block.set(false);release.countDown();sampler.join(6000);assertFalse(sampler.isAlive());}
      // The released call began before aging. Drain it, then demand a post-hold source read.
      f.lane(heldLane).completion().get(2, TimeUnit.SECONDS);
      f.drainPublications(null);
      int drainedCalls = calls.get();
      input.set(knownSupplierValue(name, clock.get(), true));
      if (heldLane.equals("gpu")) set(f.handler,"cachedGpuStatusAtMs",0L);
      Instant recoveryProjection = Instant.now();
      f.handler.sampleAndBuildStatusSnapshot();
      f.drainPublications(null);
      var recoveryCompanion = captureCompanion(f, name);
      Instant recoveryProjected = Instant.now();
      f.lane(heldLane).completion().get(2, TimeUnit.SECONDS);
      assertTrue(calls.get() > drainedCalls, "recovery must read the changed mandatory supplier");
      var publication = f.published(heldLane);
      assertEquals(clock.get(), publication.observedAtMs(), "recovery must publish after the hold");
      assertNotSame(before, publication);
      assertFalse(publication.failed());
      assertFalse(publication.staleSource());
      assertNotNull(publication.value());
      var recovered=get(f,"");
      assertKnownPublic(name, recovered, publication, true);
      assertCompanion(name, recovered, recoveryCompanion, true, recoveryProjection, recoveryProjected);
      assertPublicFreshness(name, recovered, false, true);
      assertFalse(recovered.path("meta").path("workerRpcStale").asBoolean());
      assertFalse(recovered.path("readiness").path("components").path("ai").path("stale").asBoolean());
      if(name.equals("inferenceSnapshotSupplier")) assertFalse(recovered.path("operating").path("freshness").path("staleSources").toString().contains("chat"),"recovered inference clears only its stale state");
    }
  }
  private static Object knownSupplierValue(String name, long nowMs, boolean after) {
    return switch (name) {
      case "inferenceSnapshotSupplier" -> new InferenceRuntimeView("ONLINE", null, false, null,
          new LifecycleCounters(0L, 0L, 0L), true, after ? "Recovering" : "Healthy", "", "", "CHAT");
      case "conversationProtectionStateSupplier" -> after ? "unlocked" : "locked";
      case "telemetryHealthSupplier" -> {
        var state = new io.justsearch.telemetry.TelemetryHealthState();
        state.recordFlushFailure();
        if (after) { state.recordFlushFailure(); state.recordDiskSpaceLowEvent(); }
        yield state;
      }
      case "knowledgeServerLifecycleSupplier" -> {
        var server = mock(io.justsearch.app.services.worker.KnowledgeServerBootstrap.class);
        var gate = new io.justsearch.core.scheduling.BulkWorkGate();
        gate.setGame(!after);
        when(server.bulkWork()).thenReturn(gate);
        yield server;
      }
      case "gpuCapabilitiesSupplier" -> {
        var service = mock(io.justsearch.gpu.GpuCapabilitiesService.class);
        var caps = mock(io.justsearch.gpu.GpuCapabilities.class);
        var nvml = mock(io.justsearch.gpu.GpuCapabilities.Nvml.class);
        when(nvml.available()).thenReturn(true);
        when(nvml.gpuUtilizationPercent()).thenReturn(after ? 42 : 37);
        when(caps.nvml()).thenReturn(nvml);
        when(caps.effective()).thenReturn(new io.justsearch.gpu.GpuCapabilities.Effective(
            true, "nvml", io.justsearch.gpu.GpuCapabilities.Confidence.HIGH,
            "580.1", 580, 1, 1, 12L << 30, 8L << 30, 4L << 30,
            new io.justsearch.gpu.GpuCapabilities.Cuda(true, "cuda-driver-api",
                io.justsearch.gpu.GpuCapabilities.Confidence.HIGH)));
        when(service.snapshot(any(io.justsearch.gpu.GpuCapabilities.Cuda.class))).thenReturn(caps);
        yield service;
      }
      case "rrdStoreSupplier" -> {
        var store = mock(io.justsearch.telemetry.RrdMetricStore.class);
        when(store.query(anyString(), anyLong(), anyLong())).thenAnswer(i ->
            new io.justsearch.telemetry.RrdMetricStore.TimeSeriesResult(i.getArgument(0),
                new long[] {i.getArgument(2)}, new double[] {after ? 42.5 : 37.5}));
        yield store;
      }
      case "vduCapabilitySnapshotSupplier" ->
          new io.justsearch.app.services.vdu.VduCapabilityState.Snapshot(after ? "vdu.missing_mmproj" : "vdu.circuit_open", nowMs);
      case "vduProcessingSupplier" -> !after;
      case "devicePlanSupplier" -> {
        var arena = new io.justsearch.ort.GpuArenaPlan(after
            ? io.justsearch.ort.GpuArenaPlan.Profile.P16_PLUS : io.justsearch.ort.GpuArenaPlan.Profile.P12,
            false, new io.justsearch.ort.GpuArenaPlan.Chat(java.util.Optional.empty(),
                java.util.OptionalInt.empty(), false, 0.0, false),
            java.util.Optional.empty(), java.util.Map.of(), 0, java.util.Set.of());
        var plan = new io.justsearch.app.services.gpu.GpuProfileResolver.Resolution(
            java.util.Optional.of(arena), io.justsearch.app.api.status.operating.ProfileSource.MEASURED, 0);
        yield plan;
      }
      case "lambdamartRerankerSupplier" -> {
        var reranker = mock(io.justsearch.app.api.gpl.RerankerService.class);
        when(reranker.isLoaded()).thenReturn(!after);
        yield reranker;
      }
      case "gplCoordinatorSupplier" -> {
        var coordinator = mock(io.justsearch.app.api.gpl.GplStatusProvider.class);
        when(coordinator.getStatus()).thenReturn(new io.justsearch.app.api.gpl.GplJobStatus(
            after ? io.justsearch.app.api.gpl.GplJobStatus.Status.COMPLETED
                : io.justsearch.app.api.gpl.GplJobStatus.Status.FAILED, 1L, 1L, 1L, null, null));
        yield coordinator;
      }
      default -> throw new AssertionError("no typed value for " + name);
    };
  }

  private void assertKnownPublic(String name, tools.jackson.databind.JsonNode body,
      StatusObservation.Sample<?> publication, boolean after) throws Exception {
    String directField = switch (name) {
      case "inferenceSnapshotSupplier" -> "inference";
      case "conversationProtectionStateSupplier" -> "conversationProtection";
      case "gpuCapabilitiesSupplier", "rrdStoreSupplier" -> "gpu";
      default -> null;
    };
    if (directField != null) assertEquals(json.readTree(json.writeValueAsString(publication.value())), body.path(directField),
        "HTTP must expose the exact typed source publication");
    switch (name) {
      case "inferenceSnapshotSupplier" -> {
        assertEquals("ONLINE", body.path("inference").path("phase").asText());
        assertEquals(after ? "Recovering" : "Healthy", body.path("inference").path("engineState").asText());
        assertEquals("CHAT", body.path("inference").path("leaseHolder").asText());
        assertEquals(after ? "LOADING" : "READY", body.path("operating").path("chat").path("state").asText());
        if (after) assertEquals("RECOVERY", body.path("operating").path("chat").path("reason").asText());
      }
      case "conversationProtectionStateSupplier" ->
          assertEquals(after ? "unlocked" : "locked", body.path("conversationProtection").path("state").asText());
      case "telemetryHealthSupplier" -> {
        assertEquals(after ? 2L : 1L, body.path("telemetryHealth").path("flushFailureCount").asLong(-1L));
        assertEquals(0L, body.path("telemetryHealth").path("gaugeCallbackFailureCount").asLong(-1L));
      }
      case "knowledgeServerLifecycleSupplier" -> {
        assertTrue(body.path("operating").path("indexing").path("reasons").isArray());
        assertEquals(!after, body.path("operating").path("indexing").path("reasons").toString().contains("GAME_MODE"));
      }
      case "gpuCapabilitiesSupplier", "rrdStoreSupplier" -> {
        assertTrue(body.path("gpu").path("available").asBoolean());
        assertEquals(name.equals("gpuCapabilitiesSupplier") && after ? 42 : 37, body.path("gpu").path("gpuUtilizationPercent").asInt());
        assertEquals("nvml", body.path("gpu").path("source").asText());
        assertEquals("HIGH", body.path("gpu").path("confidence").asText());
        assertTrue(body.path("gpu").path("cudaFunctional").asBoolean());
        if (name.equals("rrdStoreSupplier")) {
          assertEquals(after ? 42.5 : 37.5, body.path("gpu").path("recentUtilizationPercent").path(0).asDouble());
          assertEquals(after ? 42.5 : 37.5, body.path("gpu").path("recentMemoryUtilizationPercent").path(0).asDouble());
        }
      }
      case "vduCapabilitySnapshotSupplier" -> assertEquals(after ? "vdu.missing_mmproj" : "vdu.circuit_open",
          body.path("worker").path("visualExtraction").path("vduBlockedReason").asText());
      case "vduProcessingSupplier" -> {
        assertTrue(body.path("worker").path("visualExtraction").path("vduProcessing").isBoolean());
        assertEquals(!after, body.path("worker").path("visualExtraction").path("vduProcessing").asBoolean());
      }
      case "devicePlanSupplier" -> {
        assertEquals(after ? "P16_PLUS" : "P12", body.path("operating").path("gpu").path("profile").asText());
        assertEquals("MEASURED", body.path("operating").path("gpu").path("profileSource").asText());
      }
      case "lambdamartRerankerSupplier", "gplCoordinatorSupplier" -> {
        var component = body.path("readiness").path("components").path("lambdamartModel");
        assertEquals(json.readTree(json.writeValueAsString(publication.value())), component,
            "the exact readiness value, source and observation time must reach HTTP");
        assertEquals(name.equals("gplCoordinatorSupplier") && !after ? "DEGRADED" : "READY", component.path("state").asText());
        if (name.equals("gplCoordinatorSupplier")) assertEquals(after ? "lambdamart.not_configured" : "lambdamart.failed",
            component.path("reasonCode").asText());
        else if (after) assertEquals("lambdamart.not_configured", component.path("reasonCode").asText());
        else assertTrue(component.path("reasonCode").isMissingNode() || component.path("reasonCode").isNull(),
            "loaded model differs from legitimate lambdamart.not_configured absence");
        assertFalse(component.path("stale").asBoolean());
      }
      default -> throw new AssertionError("no public oracle for " + name);
    }
  }

  private static void assertHeldPublic(String name, tools.jackson.databind.JsonNode body) {
    switch (name) {
      case "inferenceSnapshotSupplier" -> {
        assertEquals("Healthy", body.path("inference").path("engineState").asText());
        assertEquals("CHAT", body.path("inference").path("leaseHolder").asText());
        assertEquals("READY", body.path("operating").path("chat").path("state").asText());
      }
      case "telemetryHealthSupplier" -> {
        assertEquals(1L, body.path("telemetryHealth").path("flushFailureCount").asLong(-1L));
        assertEquals(0L, body.path("telemetryHealth").path("gaugeCallbackFailureCount").asLong(-1L));
      }
      case "vduCapabilitySnapshotSupplier" -> assertEquals("vdu.circuit_open",
          body.path("worker").path("visualExtraction").path("vduBlockedReason").asText());
      case "vduProcessingSupplier" -> assertTrue(body.path("worker").path("visualExtraction").path("vduProcessing").asBoolean());
      case "devicePlanSupplier" -> assertEquals("P12", body.path("operating").path("gpu").path("profile").asText());
      case "knowledgeServerLifecycleSupplier" -> assertTrue(body.path("operating").path("indexing").path("reasons").toString().contains("GAME_MODE"));
      default -> { } // Sentinels and the blocked GPL companion are checked separately.
    }
  }

  private static String companionDimension(String name) {
    return switch (name) {
      case "telemetryHealthSupplier" -> "telemetry";
      case "vduCapabilitySnapshotSupplier", "vduProcessingSupplier" -> "visualDocumentUnderstanding";
      case "lambdamartRerankerSupplier", "gplCoordinatorSupplier" -> "lambdamartModel";
      default -> null;
    };
  }

  private static ReadinessComponentView captureCompanion(Fixture f, String name) throws Exception {
    String dimension = companionDimension(name);
    return dimension == null ? null : (ReadinessComponentView) f.published("readiness-" + dimension).value();
  }

  private static void assertCompanion(String name, tools.jackson.databind.JsonNode body,
      ReadinessComponentView publication, boolean after, Instant started, Instant completed) {
    String dimension = companionDimension(name);
    if (dimension == null) return;
    assertNotNull(publication, "capture the relevant companion before HTTP");
    String state = "READY";
    String reason = null;
    String source;
    switch (name) {
      case "telemetryHealthSupplier" -> {
        source = "telemetry_health";
        if (after) { state = "DEGRADED"; reason = "telemetry.disk_space_low"; }
      }
      case "vduCapabilitySnapshotSupplier", "vduProcessingSupplier" -> {
        source = "head_vdu_status";
        if (name.equals("vduCapabilitySnapshotSupplier")) {
          state = "DEGRADED";
          reason = after ? "vdu.missing_mmproj" : "vdu.circuit_open";
        }
      }
      case "lambdamartRerankerSupplier", "gplCoordinatorSupplier" -> {
        source = "head_gpl_status";
        if (after) reason = "lambdamart.not_configured";
        else if (name.equals("gplCoordinatorSupplier")) { state = "DEGRADED"; reason = "lambdamart.failed"; }
      }
      default -> throw new AssertionError(name);
    }
    var component = body.path("readiness").path("components").path(dimension);
    assertEquals(state, publication.state());
    assertEquals(reason, publication.reasonCode());
    assertEquals(source, publication.source());
    assertEquals(state, component.path("state").asText());
    assertEquals(reason, component.path("reasonCode").asText(null));
    assertEquals(source, component.path("source").asText());
    // Companion time belongs to its real projection, independent of raw fake/event time.
    Instant observed = Instant.parse(publication.observedAt());
    assertFalse(observed.isBefore(started), "companion predates its actual projection window");
    assertFalse(observed.isAfter(completed), "companion is later than its actual projection window");
    assertEquals(publication.observedAt(), component.path("observedAt").asText(),
        "HTTP must retain the captured companion's real observation time");
  }

  private static void assertPublicFreshness(String name, tools.jackson.databind.JsonNode body,
      boolean stale, boolean after) {
    String dimension = switch (name) {
      case "telemetryHealthSupplier" -> "telemetry";
      case "vduCapabilitySnapshotSupplier", "vduProcessingSupplier" -> "visualDocumentUnderstanding";
      case "lambdamartRerankerSupplier", "gplCoordinatorSupplier" -> "lambdamartModel";
      default -> null;
    };
    if (dimension != null) {
      var component = body.path("readiness").path("components").path(dimension);
      String source = switch (dimension) {
        case "telemetry" -> "telemetry_health";
        case "visualDocumentUnderstanding" -> "head_vdu_status";
        case "lambdamartModel" -> "head_gpl_status";
        default -> throw new AssertionError(dimension);
      };
      assertEquals(source, component.path("source").asText());
      assertTrue(component.path("stale").isBoolean(), "public freshness marker: " + name);
      assertEquals(stale, component.path("stale").asBoolean(), "public owner freshness: " + name);
      return;
    }
    String block = switch (name) {
      case "inferenceSnapshotSupplier" -> "chat";
      case "knowledgeServerLifecycleSupplier" -> "indexing";
      case "devicePlanSupplier" -> "gpu";
      default -> null;
    };
    if (block != null) {
      var sources = body.path("operating").path("freshness").path("staleSources");
      assertTrue(sources.isArray(), "public operating freshness must be present");
      boolean listed = false;
      for (var source : sources) if (block.equals(source.asText())) listed = true;
      assertEquals(stale, listed, "public owner freshness: " + name);
      return;
    }
    // These wire contracts have no freshness flag or source timestamp. The explicit unknown/
    // unavailable sentinel differs from the known value; sample identity/time is checked separately.
    switch (name) {
      case "conversationProtectionStateSupplier" -> assertEquals(stale ? "unknown" : after ? "unlocked" : "locked",
          body.path("conversationProtection").path("state").asText());
      case "gpuCapabilitiesSupplier", "rrdStoreSupplier" -> {
        assertTrue(body.path("gpu").path("available").isBoolean());
        assertEquals(!stale, body.path("gpu").path("available").asBoolean());
      }
      default -> throw new AssertionError("no public freshness contract for " + name);
    }
  }
  @Test void failingConversationIsUnknownAndRecoveryReportsNewState() throws Exception {
    try(var f=new Fixture(true)) {
      var fail=new AtomicBoolean(false); var state=new AtomicReference<>("unlocked");
      f.handler.setConversationProtectionSupplier(()->{if(fail.get())throw new IllegalStateException("injected failure");return state.get();});
      f.handler.sampleAndBuildStatusSnapshot();
      assertEquals("unlocked",get(f,"").path("conversationProtection").path("state").asText());
      fail.set(true);f.handler.sampleAndBuildStatusSnapshot();
      var failed=get(f,"");assertEquals("unknown",failed.path("conversationProtection").path("state").asText());
      assertFalse(failed.path("meta").path("workerRpcStale").asBoolean());
      state.set("locked");fail.set(false);f.handler.sampleAndBuildStatusSnapshot();
      assertEquals("locked",get(f,"").path("conversationProtection").path("state").asText());
    }
  }
  @Test void freshImmediatelyReflectsConversationAction() throws Exception {
    try(var f=new Fixture(true)) {
      var state=new AtomicReference<>("locked");f.handler.setConversationProtectionSupplier(state::get);
      f.handler.sampleAndBuildStatusSnapshot();get(f,"");
      state.set("unlocked");
      assertEquals("unlocked",get(f,"?fresh=true").path("conversationProtection").path("state").asText(),"action refresh must publish post-action state in its response");
    }
  }
  @Test void heldRegistryOwnerDoesNotDelayHttp() throws Exception {
    try(var f=new Fixture(true)) {
      get(f,"");
      var field=f.components.registry().getClass().getDeclaredField("monitor");field.setAccessible(true);
      Object monitor=field.get(f.components.registry());
      var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
      var holder=Thread.ofPlatform().daemon().start(()->{synchronized(monitor){entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}});
      try {assertTrue(entered.await(2,TimeUnit.SECONDS));get(f,"");get(f,"?fresh=true");}
      finally {release.countDown();holder.join(3000);assertFalse(holder.isAlive());}
    }
  }
}
