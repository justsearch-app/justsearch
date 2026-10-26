package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
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
      Supplier<Object> supplier=()->{
        calls.incrementAndGet();
        if(block.get()){ entered.countDown(); try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);} }
        return null;
      };
      set(f.handler,name,supplier);
      if(name.equals("gpuCapabilitiesSupplier"))set(f.handler,"cachedGpuStatusAtMs",0L);
      if(name.equals("lambdamartRerankerSupplier"))set(f.handler,"gplCoordinatorSupplier",(Supplier<Object>)()->null);
      if(name.equals("gplCoordinatorSupplier"))set(f.handler,"lambdamartRerankerSupplier",(Supplier<Object>)()->null);
      if(name.equals("rrdStoreSupplier")) {
        var svc=org.mockito.Mockito.mock(io.justsearch.gpu.GpuCapabilitiesService.class);
        var caps=org.mockito.Mockito.mock(io.justsearch.gpu.GpuCapabilities.class);
        var nvml=org.mockito.Mockito.mock(io.justsearch.gpu.GpuCapabilities.Nvml.class);
        org.mockito.Mockito.when(nvml.available()).thenReturn(true);
        org.mockito.Mockito.when(caps.nvml()).thenReturn(nvml);
        org.mockito.Mockito.when(svc.snapshot(org.mockito.ArgumentMatchers.any(io.justsearch.gpu.GpuCapabilities.Cuda.class))).thenReturn(caps);
        set(f.handler,"gpuCapabilitiesSupplier",(Supplier<Object>)()->svc);
        set(f.handler,"cachedGpuStatusAtMs",0L);
      }
      f.handler.sampleAndBuildStatusSnapshot();
      f.lane(heldLane).completion().get(2, TimeUnit.SECONDS);
      assertTrue(calls.get() > 0, "mandatory supplier prerequisite must reach " + name);
      var before = f.published(heldLane);
      assertEquals(clock.get(), before.observedAtMs(), "known supplier publication before hold");
      assertFalse(before.failed());
      assertFalse(before.staleSource());
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
        clock.set(before.observedAtMs() + f.lane(heldLane).staleAfterMs() + 1L);
        assertTrue(clock.get() - before.observedAtMs()
            > StatusLifecycleHandler.SAMPLE_STALE_PERIODS * before.cadenceMs());
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
      f.handler.sampleAndBuildStatusSnapshot();
      f.lane(heldLane).completion().get(2, TimeUnit.SECONDS);
      var publication = f.published(heldLane);
      assertEquals(clock.get(), publication.observedAtMs(), "recovery must publish after the hold");
      assertNotSame(before, publication);
      assertFalse(publication.failed());
      assertFalse(publication.staleSource());
      var recovered=get(f,"");
      assertFalse(recovered.path("meta").path("workerRpcStale").asBoolean());
      assertFalse(recovered.path("readiness").path("components").path("ai").path("stale").asBoolean());
      if(name.equals("inferenceSnapshotSupplier")) assertFalse(recovered.path("operating").path("freshness").path("staleSources").toString().contains("chat"),"recovered inference clears only its stale state");
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
