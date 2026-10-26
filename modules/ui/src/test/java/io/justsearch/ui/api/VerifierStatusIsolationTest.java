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
    final AtomicReference<WorkerOperationalView> workerView = new AtomicReference<>(view());
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
        return workerView.get();
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
      var clock = new AtomicLong(System.currentTimeMillis());
      f.handler.setClockForTesting(clock::get);
      f.handler.sampleAndBuildStatusSnapshot();
      f.lane("worker").completion().get(2, TimeUnit.SECONDS);
      var healthy = get(f, "");
      assertEquals(clock.get(), healthy.path("meta").path("workerRpcAtMs").asLong());
      assertEquals(10L, healthy.path("worker").path("core").path("indexedDocuments").asLong());
      assertFalse(healthy.path("meta").path("workerRpcStale").asBoolean());
      long healthyAt = clock.get();
      long staleAfter = f.lane("worker").staleAfterMs();
      assertTrue(staleAfter > 0L);
      f.block.set(true);
      Thread sampler=Thread.ofPlatform().daemon().start(f.handler::sampleAndBuildStatusSnapshot);
      try {
        assertTrue(f.entered.await(2,TimeUnit.SECONDS));
        // Hold the source, then age its last publication beyond its actual effective cadence.
        clock.addAndGet(staleAfter + 1L);
        var stale = get(f, "");
        assertEquals(healthyAt, stale.path("meta").path("workerRpcAtMs").asLong());
        assertTrue(stale.path("meta").path("workerRpcStale").asBoolean(),
            "recovery must start from a positively observed stale Worker");
        f.workerView.set(WorkerOperationalViewBuilder.builder(view())
            .core(new CoreIndexView(true, 11, 0, view().core().indexState(), 0, 0)).build());
      } finally {
        f.block.set(false); f.release.countDown(); sampler.join(3000);
        assertFalse(sampler.isAlive());
      }
      // The held call was stamped before the age interval. Require a new source observation.
      f.lane("worker").completion().get(2, TimeUnit.SECONDS);
      f.handler.sampleAndBuildStatusSnapshot();
      f.lane("worker").completion().get(2, TimeUnit.SECONDS);
      var recovered=get(f, ""); assertFalse(recovered.path("meta").path("workerRpcStale").asBoolean());
      assertEquals(clock.get(), recovered.path("meta").path("workerRpcAtMs").asLong());
      assertTrue(recovered.path("meta").path("workerRpcAtMs").asLong() > healthyAt);
      assertEquals(11L, recovered.path("worker").path("core").path("indexedDocuments").asLong(),
          "the recovery response must publish the new Worker source value");
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
      for(int i=0;i<30;i++){get(f,"?fresh=true");}
      // Exercise coalescing both before and after the held worker observation becomes stale.
      StatusLaneAging.ageHeldLanes(f.handler);
      assertTrue(get(f,"").path("meta").path("workerRpcStale").asBoolean(),
          "the held worker lane must be stale before the second burst of fresh reads");
      for(int i=0;i<30;i++){get(f,"?fresh=true");}
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
      var clock = new AtomicLong(System.currentTimeMillis());
      f.handler.setClockForTesting(clock::get);
      var reader=mock(io.justsearch.app.api.gpu.GpuMemoryReader.class);
      when(reader.sampleNow()).thenAnswer(a -> new io.justsearch.app.api.gpu.GpuMemorySample(
        io.justsearch.app.api.gpu.GpuMemorySource.NVML,java.util.Optional.empty(),
        java.util.Optional.of(new io.justsearch.app.api.gpu.GpuDevice("luid_0x00000000_0x00000001",java.util.OptionalInt.of(0))),
        java.util.Optional.of("verifier GPU"),java.util.OptionalLong.of(12L*1024*1024*1024),
        java.util.OptionalLong.of(1024*1024*1024),java.util.Map.of(),Instant.ofEpochMilli(clock.get())));
      try {
        f.handler.setGpuMemoryReader(reader);
        var published = waitForGpuPublication(f, clock.get());
        assertFalse(published.path("stale").asBoolean());
        assertEquals(10000L,f.handler.samplingPeriodMs());
        String sampledAt = published.path("sampledAt").asText();
        // Age the published reading without asking any producer to refresh it.
        clock.addAndGet(7100L);
        var aged = get(f, "").path("operating").path("gpu");
        assertEquals(sampledAt, aged.path("sampledAt").asText());
        assertEquals(7100L, aged.path("ageMs").asLong());
        assertFalse(aged.path("stale").asBoolean(),
          "healthy GPU must not turn stale before the next scheduled idle sample");
      } finally {
        f.handler.closeGpuMemoryReader();
      }
    }
  }

  private tools.jackson.databind.JsonNode waitForGpuPublication(Fixture f, long sampledAtMs)
      throws Exception {
    String expected = Instant.ofEpochMilli(sampledAtMs).toString();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    tools.jackson.databind.JsonNode gpu;
    do {
      f.handler.sampleAndBuildStatusSnapshot();
      gpu = get(f, "").path("operating").path("gpu");
      if ("NVML".equals(gpu.path("source").asText())
          && expected.equals(gpu.path("sampledAt").asText())) break;
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    assertEquals("NVML", gpu.path("source").asText(), "known GPU reading must reach HTTP");
    assertEquals(expected, gpu.path("sampledAt").asText(), "expected GPU sample must be published");
    return gpu;
  }
  /**
   * Status reads have their own bounded observation pool (b75 integration decision): a saturated
   * work pool cannot refuse them, but overlapping reads beyond the observation pool's per-client
   * and aggregate bounds are refused with the typed capacity reason and Retry-After. An in-flight
   * read keeps its observation slot until its response is sent.
   */
  @Test void overlappingStatusPreservesAdmissionCapacityRefusal() throws Exception {
    try(var f=new Fixture(true)) {
      var context=statusContext("local-webview");
      var owners=new java.util.ArrayList<io.justsearch.app.api.EngineWorkHandle>();
      var observers=new java.util.ArrayList<io.justsearch.app.api.EngineAdmissionService.StatusReservation>();
      var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
      var first=new AtomicBoolean(true);
      f.app.beforeMatched(ctx->{if(ctx.path().equals("/api/status")&&first.compareAndSet(true,false)){
        entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
      }});
      CompletableFuture<HttpResponse<String>> pending=null;
      try {
        // Work fills this client's work bound; it must not refuse a status read.
        for(int n=0;n<4;n++)owners.add(f.admission.admit(context,false));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+f.app.port()+"/api/status")).timeout(Duration.ofSeconds(3)).GET().build();
        pending=client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
        assertTrue(entered.await(1,TimeUnit.SECONDS),"a saturated work pool must not refuse a status read");
        // Per-client bound: the held read still occupies its slot, so three more reach the bound of 4.
        for(int n=0;n<3;n++)observers.add(f.admission.reserveStatusAdmission(context));
        var overlapping=client.send(request,HttpResponse.BodyHandlers.ofString());
        System.out.println("OVERLAP_JSON="+overlapping.statusCode()+" "+overlapping.body());
        assertEquals(429,overlapping.statusCode(),"an overlapping status read must preserve the previous capacity refusal");
        assertCapacityRefusal(overlapping,"ADMISSION_CONTEXT_LIMIT");
        // Aggregate bound: other clients fill the pool of 16 while this client has room (1 of 4).
        observers.forEach(io.justsearch.app.api.EngineAdmissionService.StatusReservation::close);
        observers.clear();
        for(int c=0;c<5;c++)for(int n=0;n<3;n++)observers.add(f.admission.reserveStatusAdmission(statusContext("observer-"+c)));
        var aggregate=client.send(request,HttpResponse.BodyHandlers.ofString());
        System.out.println("AGGREGATE_JSON="+aggregate.statusCode()+" "+aggregate.body());
        assertCapacityRefusal(aggregate,"ADMISSION_ENGINE_LIMIT");
        observers.forEach(io.justsearch.app.api.EngineAdmissionService.StatusReservation::close);
        observers.clear();
        release.countDown();
        assertEquals(200,pending.get(3,TimeUnit.SECONDS).statusCode());
        assertEquals(owners.size(),f.admission.activeWorkCount(),
            "the held status slot is released once its response has been sent");
        assertEquals(200,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
      } finally {release.countDown();if(pending!=null)pending.get(3,TimeUnit.SECONDS);
        observers.forEach(io.justsearch.app.api.EngineAdmissionService.StatusReservation::close);
        owners.forEach(io.justsearch.app.api.EngineWorkHandle::close);}
    }
  }

  static io.justsearch.core.context.EngineContext statusContext(String clientId) {
    return io.justsearch.app.services.intent.EngineProvenance.context(
        io.justsearch.core.context.EngineContext.ClientKind.WEBVIEW,clientId,
        java.util.Optional.empty(),java.util.Optional.empty(),
        io.justsearch.agent.api.registry.TransportTag.BUTTON,
        io.justsearch.core.context.EngineContext.Survival.INTERACTIVE,
        io.justsearch.core.context.EngineContext.Urgency.BACKGROUND);
  }

  void assertCapacityRefusal(HttpResponse<String> response, String code) throws Exception {
    assertEquals(429,response.statusCode(),response.body());
    assertEquals("1",response.headers().firstValue("Retry-After").orElseThrow());
    var body=json.readTree(response.body());
    assertEquals(code,body.get("errorCode").asText(),response.body());
    assertEquals("TRANSIENT",body.get("errorClass").asText(),response.body());
  }
}
