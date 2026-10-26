package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.justsearch.app.api.status.*;
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
      var block=new AtomicBoolean(false); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
      var calls=new AtomicInteger();
      Object known = knownSupplierValue(name, clock.get());
      Supplier<Object> supplier=()->{
        calls.incrementAndGet();
        if(block.get()){ entered.countDown(); try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);} }
        return known;
      };
      set(f.handler,name,supplier);
      if(name.equals("gpuCapabilitiesSupplier"))set(f.handler,"cachedGpuStatusAtMs",0L);
      if(name.equals("lambdamartRerankerSupplier"))set(f.handler,"gplCoordinatorSupplier",
          (Supplier<Object>)()->knownSupplierValue("gplCoordinatorSupplier", clock.get()));
      if(name.equals("gplCoordinatorSupplier"))set(f.handler,"lambdamartRerankerSupplier",
          (Supplier<Object>)()->knownSupplierValue("lambdamartRerankerSupplier", clock.get()));
      if(name.equals("rrdStoreSupplier")) {
        set(f.handler,"gpuCapabilitiesSupplier",
            (Supplier<Object>)()->knownSupplierValue("gpuCapabilitiesSupplier", clock.get()));
        set(f.handler,"cachedGpuStatusAtMs",0L);
      }
      f.handler.sampleAndBuildStatusSnapshot();
      f.lane(heldLane).completion().get(2, TimeUnit.SECONDS);
      assertTrue(calls.get() > 0, "mandatory supplier prerequisite must reach " + name);
      var before = f.published(heldLane);
      assertEquals(clock.get(), before.observedAtMs(), "known supplier publication before hold");
      assertFalse(before.failed());
      assertFalse(before.staleSource());
      assertNotNull(before.value(), "mandatory supplier must publish a typed value, not absence");
      var knownBody = get(f, "");
      assertKnownPublic(name, knownBody, before);
      assertPublicFreshness(name, knownBody, false);
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
      block.set(true);
      var sampler=Thread.ofPlatform().daemon().start(f.handler::sampleAndBuildStatusSnapshot);
      try {
        assertTrue(entered.await(2,TimeUnit.SECONDS), "mandatory supplier must enter: " + name);
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
        var s=get(f,"");
        assertPublicFreshness(name, s, true);
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
      if (heldLane.equals("gpu")) set(f.handler,"cachedGpuStatusAtMs",0L);
      f.handler.sampleAndBuildStatusSnapshot();
      f.lane(heldLane).completion().get(2, TimeUnit.SECONDS);
      var publication = f.published(heldLane);
      assertEquals(clock.get(), publication.observedAtMs(), "recovery must publish after the hold");
      assertNotSame(before, publication);
      assertFalse(publication.failed());
      assertFalse(publication.staleSource());
      assertNotNull(publication.value());
      var recovered=get(f,"");
      assertKnownPublic(name, recovered, publication);
      assertPublicFreshness(name, recovered, false);
      assertFalse(recovered.path("meta").path("workerRpcStale").asBoolean());
      assertFalse(recovered.path("readiness").path("components").path("ai").path("stale").asBoolean());
      if(name.equals("inferenceSnapshotSupplier")) assertFalse(recovered.path("operating").path("freshness").path("staleSources").toString().contains("chat"),"recovered inference clears only its stale state");
    }
  }
  private static Object knownSupplierValue(String name, long nowMs) {
    return switch (name) {
      case "inferenceSnapshotSupplier" -> new InferenceRuntimeView("ONLINE", null, false, null,
          new LifecycleCounters(0L, 0L, 0L), true, "Healthy", "", "", "CHAT");
      case "conversationProtectionStateSupplier" -> "locked";
      case "telemetryHealthSupplier" -> {
        var state = new io.justsearch.telemetry.TelemetryHealthState();
        state.recordFlushFailure();
        yield state;
      }
      case "knowledgeServerLifecycleSupplier" -> {
        var server = mock(io.justsearch.app.services.worker.KnowledgeServerBootstrap.class);
        var gate = new io.justsearch.core.scheduling.BulkWorkGate();
        gate.setGame(true);
        when(server.bulkWork()).thenReturn(gate);
        yield server;
      }
      case "gpuCapabilitiesSupplier" -> {
        var service = mock(io.justsearch.gpu.GpuCapabilitiesService.class);
        var caps = mock(io.justsearch.gpu.GpuCapabilities.class);
        var nvml = mock(io.justsearch.gpu.GpuCapabilities.Nvml.class);
        when(nvml.available()).thenReturn(true);
        when(nvml.gpuUtilizationPercent()).thenReturn(37);
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
                new long[] {i.getArgument(2)}, new double[] {37.5}));
        yield store;
      }
      case "vduCapabilitySnapshotSupplier" ->
          new io.justsearch.app.services.vdu.VduCapabilityState.Snapshot("vdu.circuit_open", nowMs);
      case "vduProcessingSupplier" -> true;
      case "devicePlanSupplier" -> {
        var arena = new io.justsearch.ort.GpuArenaPlan(io.justsearch.ort.GpuArenaPlan.Profile.P12,
            false, new io.justsearch.ort.GpuArenaPlan.Chat(java.util.Optional.empty(),
                java.util.OptionalInt.empty(), false, 0.0, false),
            java.util.Optional.empty(), java.util.Map.of(), 0, java.util.Set.of());
        var plan = new io.justsearch.app.services.gpu.GpuProfileResolver.Resolution(
            java.util.Optional.of(arena), io.justsearch.app.api.status.operating.ProfileSource.MEASURED, 0);
        yield plan;
      }
      case "lambdamartRerankerSupplier" -> {
        var reranker = mock(io.justsearch.app.api.gpl.RerankerService.class);
        when(reranker.isLoaded()).thenReturn(true);
        yield reranker;
      }
      case "gplCoordinatorSupplier" -> {
        var coordinator = mock(io.justsearch.app.api.gpl.GplStatusProvider.class);
        when(coordinator.getStatus()).thenReturn(new io.justsearch.app.api.gpl.GplJobStatus(
            io.justsearch.app.api.gpl.GplJobStatus.Status.COMPLETED, 1L, 1L, 1L, null, null));
        yield coordinator;
      }
      default -> throw new AssertionError("no typed value for " + name);
    };
  }

  private void assertKnownPublic(String name, tools.jackson.databind.JsonNode body,
      StatusObservation.Sample<?> publication) throws Exception {
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
        assertEquals("Healthy", body.path("inference").path("engineState").asText());
        assertEquals("CHAT", body.path("inference").path("leaseHolder").asText());
      }
      case "conversationProtectionStateSupplier" ->
          assertEquals("locked", body.path("conversationProtection").path("state").asText());
      case "telemetryHealthSupplier" ->
          assertEquals(1L, body.path("telemetryHealth").path("flushFailures").asLong());
      case "knowledgeServerLifecycleSupplier" ->
          assertEquals("GAME_MODE", body.path("operating").path("indexing").path("reasons").path(0).asText());
      case "gpuCapabilitiesSupplier", "rrdStoreSupplier" -> {
        assertTrue(body.path("gpu").path("available").asBoolean());
        assertEquals(37, body.path("gpu").path("gpuUtilizationPercent").asInt());
        assertEquals("nvml", body.path("gpu").path("source").asText());
        assertEquals("HIGH", body.path("gpu").path("confidence").asText());
        assertTrue(body.path("gpu").path("cudaFunctional").asBoolean());
        if (name.equals("rrdStoreSupplier")) {
          assertEquals(37.5, body.path("gpu").path("recentUtilizationPercent").path(0).asDouble());
          assertEquals(37.5, body.path("gpu").path("recentMemoryUtilizationPercent").path(0).asDouble());
        }
      }
      case "vduCapabilitySnapshotSupplier" -> assertEquals("vdu.circuit_open",
          body.path("worker").path("visualExtraction").path("vduBlockedReason").asText());
      case "vduProcessingSupplier" ->
          assertTrue(body.path("worker").path("visualExtraction").path("vduProcessing").asBoolean());
      case "devicePlanSupplier" -> {
        assertEquals("P12", body.path("operating").path("gpu").path("profile").asText());
        assertEquals("MEASURED", body.path("operating").path("gpu").path("profileSource").asText());
      }
      case "lambdamartRerankerSupplier", "gplCoordinatorSupplier" -> {
        var component = body.path("readiness").path("components").path("lambdamartModel");
        assertEquals(json.readTree(json.writeValueAsString(publication.value())), component,
            "the exact readiness value, source and observation time must reach HTTP");
        assertEquals("READY", component.path("state").asText());
        assertTrue(component.path("reasonCode").isMissingNode() || component.path("reasonCode").isNull(),
            "loaded model differs from legitimate lambdamart.not_configured absence");
        assertFalse(component.path("stale").asBoolean());
      }
      default -> throw new AssertionError("no public oracle for " + name);
    }
  }

  private static void assertPublicFreshness(String name, tools.jackson.databind.JsonNode body,
      boolean stale) {
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
      case "conversationProtectionStateSupplier" -> assertEquals(stale ? "unknown" : "locked",
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
