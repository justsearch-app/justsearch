package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.javalin.Javalin;
import io.justsearch.app.api.status.*;
import io.justsearch.app.services.worker.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class VerifierStatusIsolationTest {
  final JsonMapper json = JsonMapper.builder().build();
  final HttpClient client = HttpClient.newHttpClient();
  static WorkerOperationalView view() {
    return WorkerOperationalViewBuilder.builder()
      .core(new CoreIndexView(true, 10, 0, "SERVING", 0, 0))
      .failure(FailureTrackingView.empty()).migration(MigrationGenerationView.empty())
      .compatibility(CompatibilityStatusView.empty()).queueDb(QueueDbStatusView.healthy())
      .enrichment(EnrichmentProgressView.empty()).gpu(GpuDiagnosticsView.empty())
      .vectorFormat(VectorFormatView.empty()).telemetry(new TelemetryMetricsView(0,0,0,0.25,"OK"))
      .searchConfig(SearchConfigView.empty()).visualExtraction(VisualExtractionView.empty())
      .embeddingReady(true).build();
  }
  static final class Fixture implements AutoCloseable {
    final StatusComponentFixture components = new StatusComponentFixture();
    final AtomicBoolean block = new AtomicBoolean();
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    final AtomicInteger calls = new AtomicInteger();
    final StatusLifecycleHandler handler;
    final Javalin app;
    final io.justsearch.app.engine.EngineAdmissionController admission=new io.justsearch.app.engine.EngineAdmissionController(4,16,1);
    Fixture(boolean workerBlock) {
      var ks = mock(KnowledgeServerBootstrap.class);
      var kc = mock(KnowledgeClient.class);
      when(ks.hasClient()).thenReturn(true);
      BootstrapLeaseFixtures.bind(ks,kc);
      when(kc.getWorkerOperationalView(any())).thenAnswer(a -> {
        if (workerBlock) probe();
        return view();
      });
      var caps = components.capabilities();
      handler = new StatusLifecycleHandler(mock(io.justsearch.app.api.OnlineAiService.class),
        mock(io.justsearch.agent.api.AgentService.class), () -> null, ks, null,
        java.nio.file.Path.of(System.getProperty("java.io.tmpdir")), Instant.now(),
        () -> { if (!workerBlock) probe(); return "OK"; }, null,null,null,caps.worker(),caps.inference());
      components.attach(handler);
      handler.sampleAndBuildStatusSnapshot();
      app=Javalin.create();
      new ApiSecurityFilters(false,null,null,null,null,null,admission).install(app);
      io.justsearch.ui.api.routes.StatusRoutes.registerLifecycleRoutes(app,handler::handleStatus,handler::handleHealth);
      app.start("127.0.0.1",0);
      try(var warm=HttpClient.newHttpClient()) {
        assertEquals(200,warm.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.port()+"/api/status"))
            .timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
      }catch(Exception e){throw new AssertionError(e);}
    }
    void probe() {
      calls.incrementAndGet();
      if (block.get()) { entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); } }
    }
    public void close() { release.countDown(); app.stop(); components.close(); }
  }
  tools.jackson.databind.JsonNode get(Fixture f, String query) throws Exception {
    long t=System.nanoTime();
    var r=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+f.app.port()+"/api/status"+query)).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());
    long ms=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-t);
    System.out.println("HTTP latencyMs="+ms+" code="+r.statusCode()+" body="+r.body());
    assertEquals(200,r.statusCode()); assertTrue(ms<500,"status must be cheap; latency="+ms);
    return json.readTree(r.body());
  }
  @Test void frozenStatusPreservesRefusalAndRelease() throws Exception {
    try(var f=new Fixture(false)) {
      assertEquals(200,client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+f.app.port()+"/api/status")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
      var freeze=f.admission.freezeAdmission("upgrade");
      try {
        for(String query: new String[]{"", "?fresh=true"}) {
          var r=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+f.app.port()+"/api/status"+query)).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());
          System.out.println("FROZEN_JSON="+r.body());
          assertEquals(503,r.statusCode(),r.body());
          assertEquals("UPGRADE_PREPARING",json.readTree(r.body()).get("errorCode").asText());
          assertTrue(json.readTree(r.body()).get("retrySafe").asBoolean());
          assertFalse(r.headers().firstValue("Retry-After").isPresent());
        }
      } finally { f.admission.releaseAdmission(freeze.preparationId()); }
      assertEquals(200,client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+f.app.port()+"/api/status")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
    }
  }
  @Test void blockedDiskProbeMustNotStaleHealthyWorkerOrAi() throws Exception {
    try(var f=new Fixture(false)) {
      f.block.set(true);
      Thread sampler=Thread.ofPlatform().daemon().start(f.handler::sampleAndBuildStatusSnapshot);
      try {
        assertTrue(f.entered.await(2,TimeUnit.SECONDS)); StatusLaneAging.ageHeldLanes(f.handler);
        var s=get(f,"");
        assertTrue((s.path("diskPressure").isNull() || s.path("diskPressure").isMissingNode()),"blocked disk producer must become unknown");
        assertFalse(s.path("meta").path("workerRpcStale").asBoolean(),"healthy worker must not be marked stale by blocked disk probe");
        assertFalse(s.path("readiness").path("components").path("ai").path("stale").asBoolean(),"healthy AI must not be stale because disk probe blocked");
      } finally { f.release.countDown(); sampler.join(3000); assertFalse(sampler.isAlive()); }
    }
  }
  @Test void hungWorkerMustNotStaleHeadLocalAiAndTelemetry() throws Exception {
    try(var f=new Fixture(true)) {
      f.block.set(true);
      Thread sampler=Thread.ofPlatform().daemon().start(f.handler::sampleAndBuildStatusSnapshot);
      try {
        assertTrue(f.entered.await(2,TimeUnit.SECONDS)); StatusLaneAging.ageHeldLanes(f.handler);
        var s=get(f,""); assertTrue(s.path("meta").path("workerRpcStale").asBoolean());
        assertFalse(s.path("readiness").path("components").path("ai").path("stale").asBoolean(),"hung worker must leave independent AI fresh");
        assertFalse(s.path("readiness").path("components").path("telemetry").path("stale").asBoolean(),"hung worker must leave telemetry fresh");
      } finally { f.release.countDown(); sampler.join(3000); assertFalse(sampler.isAlive()); }
    }
  }
  @Test void recoveryClearsStaleAndFreshEventuallyAdvances() throws Exception {
    try(var f=new Fixture(true)) {
      f.block.set(true);
      Thread sampler=Thread.ofPlatform().daemon().start(f.handler::sampleAndBuildStatusSnapshot);
      assertTrue(f.entered.await(2,TimeUnit.SECONDS)); Thread.sleep(1200);
      get(f,""); f.block.set(false); f.release.countDown(); sampler.join(3000);
      var recovered=get(f,""); assertFalse(recovered.path("meta").path("workerRpcStale").asBoolean());
      String before=recovered.path("observed_at").asText(); get(f,"?fresh=true");
      boolean newer=false;
      for(int i=0;i<20&&!newer;i++){Thread.sleep(50);newer=!before.equals(get(f,"").path("observed_at").asText());}
      assertTrue(newer,"fresh request must eventually advance observation");
    }
  }
  @Test @org.junit.jupiter.api.Timeout(90) void repeatedFreshDuringHangDoesNotPileUpSamplerThreads() throws Exception {
    try(var f=new Fixture(true)) {
      var primed=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+f.app.port()+"/api/status")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString()); assertEquals(200,primed.statusCode());
      f.block.set(true); get(f,"?fresh=true"); assertTrue(f.entered.await(2,TimeUnit.SECONDS));
      int calls=f.calls.get();
      for(int i=0;i<60;i++){get(f,"?fresh=true");Thread.sleep(1000);}
      assertEquals(calls,f.calls.get(),"fresh requests must coalesce while producer is hung");
      long count=Thread.getAllStackTraces().keySet().stream().filter(t->t.getName().equals("status-observation")).count();
      assertTrue(count<=1,"one bounded sampler, found "+count);
      f.block.set(false); f.release.countDown();
    }
  }
  @Test void healthyCompleteResponseForRevisionComparison() throws Exception {
    try(var f=new Fixture(true)) {
      var response=get(f,"");
      assertFalse(response.path("meta").path("workerRpcStale").asBoolean());
      System.out.println("PARITY_JSON="+response);
    }
  }
  @Test void heldAdmissionOwnerLockDoesNotDelayRealHttp() throws Exception {
    try(var f=new Fixture(true)) {
      var field=f.admission.getClass().getDeclaredField("lock");field.setAccessible(true);
      var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
      var holder=Thread.ofPlatform().daemon().start(()->{synchronized(fieldGet(field,f.admission)){
        entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
      }});
      try {assertTrue(entered.await(2,TimeUnit.SECONDS));get(f,"");get(f,"?fresh=true");}
      finally{release.countDown();holder.join(3000);assertFalse(holder.isAlive());}
    }
  }
  static Object fieldGet(java.lang.reflect.Field field,Object object){
    try{return field.get(object);}catch(IllegalAccessException e){throw new AssertionError(e);}
  }
  @Test void healthyGpuMustStayFreshWithinIdleSamplingPeriod() throws Exception {
    try(var f=new Fixture(true)) {
      var reader=mock(io.justsearch.app.api.gpu.GpuMemoryReader.class);
      when(reader.sampleNow()).thenAnswer(a -> new io.justsearch.app.api.gpu.GpuMemorySample(
        io.justsearch.app.api.gpu.GpuMemorySource.NVML,java.util.Optional.empty(),
        java.util.Optional.of(new io.justsearch.app.api.gpu.GpuDevice("luid_0x00000000_0x00000001",java.util.OptionalInt.of(0))),
        java.util.Optional.of("verifier GPU"),java.util.OptionalLong.of(12L*1024*1024*1024),
        java.util.OptionalLong.of(1024*1024*1024),java.util.Map.of(),Instant.now()));
      f.handler.setGpuMemoryReader(reader);
      Thread.sleep(100);
      f.handler.sampleAndBuildStatusSnapshot();
      assertFalse(get(f,"").path("operating").path("gpu").path("stale").asBoolean());
      assertEquals(10000L,f.handler.samplingPeriodMs());
      Thread.sleep(7100);
      assertFalse(get(f,"").path("operating").path("gpu").path("stale").asBoolean(),
        "healthy GPU must not turn stale before the next scheduled idle sample");
      f.handler.closeGpuMemoryReader();
    }
  }
  @Test void overlappingStatusPreservesAdmissionCapacityRefusal() throws Exception {
    try(var f=new Fixture(true)) {
      var context=io.justsearch.app.services.intent.EngineProvenance.context(
        io.justsearch.core.context.EngineContext.ClientKind.WEBVIEW,"local-webview",
        java.util.Optional.empty(),java.util.Optional.empty(),
        io.justsearch.agent.api.registry.TransportTag.BUTTON,
        io.justsearch.core.context.EngineContext.Survival.INTERACTIVE,
        io.justsearch.core.context.EngineContext.Urgency.BACKGROUND);
      var owners=new java.util.ArrayList<io.justsearch.app.api.EngineWorkHandle>();
      var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
      var first=new AtomicBoolean(true);
      f.app.beforeMatched(ctx->{if(ctx.path().equals("/api/status")&&first.compareAndSet(true,false)){
        entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
      }});
      CompletableFuture<HttpResponse<String>> pending=null;
      try {
        for(int n=0;n<3;n++)owners.add(f.admission.admit(context,false));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+f.app.port()+"/api/status")).timeout(Duration.ofSeconds(3)).GET().build();
        pending=client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
        assertTrue(entered.await(1,TimeUnit.SECONDS));
        var overlapping=client.send(request,HttpResponse.BodyHandlers.ofString());
        System.out.println("OVERLAP_JSON="+overlapping.statusCode()+" "+overlapping.body());
        assertEquals(429,overlapping.statusCode(),"an overlapping status read must preserve the previous capacity refusal");
      } finally {release.countDown();if(pending!=null)pending.get(3,TimeUnit.SECONDS);owners.forEach(io.justsearch.app.api.EngineWorkHandle::close);}
    }
  }
}






