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
    StatusComponentFixture components;
    final AtomicBoolean block = new AtomicBoolean();
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<WorkerOperationalView> workerView = new AtomicReference<>(view());
    StatusLifecycleHandler handler;
    StatusFixtureWork work;
    final AtomicReference<WorkerOperationalView> consumedWorker = new AtomicReference<>();
    long requestStartedMs;
    long requestCompletedMs;
    Javalin app;
    final io.justsearch.app.engine.EngineAdmissionController admission=new io.justsearch.app.engine.EngineAdmissionController(4,16,1);
    Fixture(boolean workerBlock) {
      try {
        components = new StatusComponentFixture();
        var ks = mock(KnowledgeServerBootstrap.class);
        var kc = mock(KnowledgeClient.class);
        when(ks.hasClient()).thenReturn(true);
        BootstrapLeaseFixtures.bind(ks,kc);
        when(kc.getWorkerOperationalView(any())).thenAnswer(a -> {
          var input = workerView.get();
          consumedWorker.set(input);
          if (workerBlock) probe();
          return input;
        });
        var caps = components.capabilities();
        handler = new StatusLifecycleHandler(mock(io.justsearch.app.api.OnlineAiService.class),
          mock(io.justsearch.agent.api.AgentService.class), () -> null, ks, null,
          java.nio.file.Path.of(System.getProperty("java.io.tmpdir")), Instant.now(),
          () -> { if (!workerBlock) probe(); return "OK"; }, null,null,null,caps.worker(),caps.inference());
        work = new StatusFixtureWork(handler);
        handler = work.handler;
        components.attach(handler);
        handler.sampleAndBuildStatusSnapshot();
        app=Javalin.create();
        new ApiSecurityFilters(false,null,null,null,null,null,admission).install(app);
        io.justsearch.ui.api.routes.StatusRoutes.registerLifecycleRoutes(app,work::handleStatus,handler::handleHealth);
        app.start("127.0.0.1",0);
        try(var warm=HttpClient.newHttpClient()) {
          assertEquals(200,warm.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.port()+"/api/status"))
              .timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }catch(Exception e){throw new AssertionError(e);}
      } catch (RuntimeException | Error failure) {
        cleanUp(failure);
        throw failure;
      }
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
    void drainPublications(String heldLane) throws Exception {
      var held = heldLane == null ? java.util.Set.<String>of()
          : heldLane.equals("bulk-gate") ? java.util.Set.of("power", "resume", "bulk-gate", "indexing-hold")
          : java.util.Set.of(heldLane);
      work.drain(held);
    }
    void probe() {
      work.sourceEntered();
      calls.incrementAndGet();
      if (block.get()) { entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); work.errors.add(e); throw new RuntimeException(e); } }
    }
    public void close() throws Exception {
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
        if (!work.quiescent) return; // Retain resources if an owned consumer may still use them.
      }
      try { if (app != null) app.stop(); }
      catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
      try { if (handler != null) handler.closeGpuMemoryReader(); }
      catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
      try { if (components != null) components.close(); }
      catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
    }
  }
  tools.jackson.databind.JsonNode get(Fixture f, String query) throws Exception {
    long t=System.nanoTime();
    f.requestStartedMs = System.currentTimeMillis();
    var r=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+f.app.port()+"/api/status"+query)).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());
    f.requestCompletedMs = System.currentTimeMillis();
    long ms=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-t);
    System.out.println("HTTP latencyMs="+ms+" code="+r.statusCode()+" body="+r.body());
    assertEquals(200,r.statusCode()); assertTrue(ms<500,"status must be cheap; latency="+ms);
    return json.readTree(r.body());
  }
  static java.util.Map<String, Long> contactLaneAges(StatusLifecycleHandler handler, long now) throws Exception {
    var field = StatusLifecycleHandler.class.getDeclaredField("observations");
    field.setAccessible(true);
    var lanes = (java.util.Map<?, ?>) field.get(handler);
    var sampleField = StatusObservation.class.getDeclaredField("sample");
    sampleField.setAccessible(true);
    var ages = new java.util.HashMap<String, Long>();
    for (String dimension : new String[] {"indexServing", "embedding", "chunkEmbedding",
        "visualTextExtraction", "visualDocumentUnderstanding", "gpu"}) {
      String lane = dimension.equals("indexServing") ? "registry" : "readiness-" + dimension;
      var sample = (StatusObservation.Sample<?>) sampleField.get(lanes.get(lane));
      assertNotNull(sample, "contact provenance requires a real companion publication: " + lane);
      long age = Math.max(0L, now - sample.observedAtMs());
      ages.put(dimension, sample.failed() || sample.staleSource() || age > 3L * sample.cadenceMs() ? age : 0L);
    }
    return ages;
  }

  static void assertWorkerEpoch(tools.jackson.databind.JsonNode body, long sourceEpoch) {
    assertEquals(sourceEpoch, body.path("meta").path("workerRpcAtMs").asLong());
    assertEquals(Instant.ofEpochMilli(sourceEpoch).toString(),
        body.path("operating").path("indexing").path("sampledAt").asText(), "indexing keeps the Worker epoch");
  }

  static void assertWorkerTimes(tools.jackson.databind.JsonNode body, long sourceEpoch, boolean stale,
      long projectionStartedMs, long requestStartedMs, long requestCompletedMs,
      java.util.Map<String, Long> extraAges) {
    assertWorkerEpoch(body, sourceEpoch);
    var sources = java.util.Map.of("indexServing", "worker_status", "embedding", "worker_health_check",
        "chunkEmbedding", "worker_status", "visualTextExtraction", "worker_status",
        "visualDocumentUnderstanding", "head_vdu_status", "gpu", "gpu_saturation_monitor");
    for (var entry : sources.entrySet()) {
      var component = body.path("readiness").path("components").path(entry.getKey());
      assertEquals(entry.getValue(), component.path("source").asText());
      assertEquals(stale, component.path("stale").asBoolean(), "contact provenance: " + entry.getKey());
      long age = component.path("stalenessMs").asLong(-1L);
      if (stale) {
        assertEquals(Instant.ofEpochMilli(sourceEpoch).toString(), component.path("observedAt").asText(),
            "contact loss retains the last successful Worker source epoch");
        long extra = extraAges.getOrDefault(entry.getKey(), 0L);
        assertTrue(age >= Math.max(extra, Math.max(0L, requestStartedMs - sourceEpoch)));
        assertTrue(age <= Math.max(extra, Math.max(0L, requestCompletedMs - sourceEpoch)),
            "contact age uses real response time, plus applicable companion/registry age");
      } else {
        assertEquals(0L, age, "recovery clears contact and companion age");
        long observed = Instant.parse(component.path("observedAt").asText()).toEpochMilli();
        assertTrue(observed >= projectionStartedMs && observed <= requestCompletedMs,
            "a fresh companion uses real projection time: " + entry.getKey());
      }
    }
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
      Thread sampler=f.work.startSampler();
      Throwable phaseFailure = null;
      try {
        assertTrue(f.entered.await(2,TimeUnit.SECONDS)); StatusLaneAging.ageHeldLanes(f.handler);
        var s=get(f,"");
        assertTrue((s.path("diskPressure").isNull() || s.path("diskPressure").isMissingNode()),"blocked disk producer must become unknown");
        assertFalse(s.path("meta").path("workerRpcStale").asBoolean(),"healthy worker must not be marked stale by blocked disk probe");
        assertFalse(s.path("readiness").path("components").path("ai").path("stale").asBoolean(),"healthy AI must not be stale because disk probe blocked");
      } catch (Exception | Error phaseError) { phaseFailure = phaseError; throw phaseError; } finally {
        try { f.release.countDown(); sampler.join(3000); assertFalse(sampler.isAlive());
        } catch (Exception | Error cleanupError) {
          if (phaseFailure == null) throw cleanupError;
          if (cleanupError != phaseFailure) phaseFailure.addSuppressed(cleanupError);
        }
      }
    }
  }
  @Test void hungWorkerMustNotStaleHeadLocalAiAndTelemetry() throws Exception {
    try(var f=new Fixture(true)) {
      f.block.set(true);
      Thread sampler=f.work.startSampler();
      Throwable phaseFailure = null;
      try {
        assertTrue(f.entered.await(2,TimeUnit.SECONDS)); StatusLaneAging.ageHeldLanes(f.handler);
        var s=get(f,""); assertTrue(s.path("meta").path("workerRpcStale").asBoolean());
        assertFalse(s.path("readiness").path("components").path("ai").path("stale").asBoolean(),"hung worker must leave independent AI fresh");
        assertFalse(s.path("readiness").path("components").path("telemetry").path("stale").asBoolean(),"hung worker must leave telemetry fresh");
      } catch (Exception | Error phaseError) { phaseFailure = phaseError; throw phaseError; } finally {
        try { f.release.countDown(); sampler.join(3000); assertFalse(sampler.isAlive());
        } catch (Exception | Error cleanupError) {
          if (phaseFailure == null) throw cleanupError;
          if (cleanupError != phaseFailure) phaseFailure.addSuppressed(cleanupError);
        }
      }
    }
  }
  @Test void recoveryClearsStaleAndFreshEventuallyAdvances() throws Exception {
    try(var f=new Fixture(true)) {
      var clock = new AtomicLong(System.currentTimeMillis());
      f.handler.setClockForTesting(clock::get);
      long projectionStartedMs = System.currentTimeMillis();
      f.handler.sampleAndBuildStatusSnapshot();
      f.drainPublications(null);
      var healthy = get(f, "");
      assertEquals(clock.get(), healthy.path("meta").path("workerRpcAtMs").asLong());
      assertEquals(10L, healthy.path("worker").path("core").path("indexedDocuments").asLong());
      assertWorkerTimes(healthy, clock.get(), false, projectionStartedMs,
          f.requestStartedMs, f.requestCompletedMs, java.util.Map.of());
      assertFalse(healthy.path("meta").path("workerRpcStale").asBoolean());
      long healthyAt = clock.get();
      var publication = f.published("worker");
      assertTrue(publication.periodMs() == 2_000L || publication.periodMs() == 10_000L,
          "Worker uses the supported busy or idle period");
      assertTrue(publication.cadenceMs() >= publication.periodMs()
          && publication.cadenceMs() <= 4L * publication.periodMs(),
          "effective cadence includes at most three periods of probe allowance");
      // Test-owned policy: a held source is stale after THREE effective cadences.
      long staleAfter = 3L * publication.cadenceMs();
      assertTrue(staleAfter > 0L);
      f.block.set(true);
      Thread sampler=f.work.startSampler();
      Throwable phaseFailure = null;
      try {
        assertTrue(f.entered.await(2,TimeUnit.SECONDS));
        // Hold the source, then age its last publication beyond its actual effective cadence.
        clock.addAndGet(staleAfter + 1L);
        f.drainPublications("worker");
        var stale = get(f, "");
        assertEquals(healthyAt, stale.path("meta").path("workerRpcAtMs").asLong());
        assertWorkerTimes(stale, healthyAt, true, projectionStartedMs,
            f.requestStartedMs, f.requestCompletedMs, contactLaneAges(f.handler, clock.get()));
        assertTrue(stale.path("meta").path("workerRpcStale").asBoolean(),
            "recovery must start from a positively observed stale Worker");
      } catch (Exception | Error phaseError) { phaseFailure = phaseError; throw phaseError; } finally {
        try {
          f.block.set(false); f.release.countDown(); sampler.join(3000);
          assertFalse(sampler.isAlive());
        } catch (Exception | Error cleanupError) {
          if (phaseFailure == null) throw cleanupError;
          if (cleanupError != phaseFailure) phaseFailure.addSuppressed(cleanupError);
        }
      }
      // Close old calls, coalesced successors and downstream publications with the old input.
      f.drainPublications(null);
      int oldCalls = f.calls.get();
      var recoveryInput = WorkerOperationalViewBuilder.builder(view())
          .core(new CoreIndexView(true, 11, 0, view().core().indexState(), 0, 0)).build();
      f.workerView.set(recoveryInput);
      projectionStartedMs = System.currentTimeMillis();
      f.handler.sampleAndBuildStatusSnapshot();
      f.drainPublications(null);
      assertTrue(f.calls.get() > oldCalls, "recovery must invoke the source after old work ended");
      assertSame(recoveryInput, f.consumedWorker.get(), "the new invocation consumed exactly Worker11");
      var recovered=get(f, ""); assertFalse(recovered.path("meta").path("workerRpcStale").asBoolean());
      assertWorkerTimes(recovered, clock.get(), false, projectionStartedMs,
          f.requestStartedMs, f.requestCompletedMs, java.util.Map.of());
      assertEquals(clock.get(), recovered.path("meta").path("workerRpcAtMs").asLong());
      assertTrue(recovered.path("meta").path("workerRpcAtMs").asLong() > healthyAt);
      assertEquals(11L, recovered.path("worker").path("core").path("indexedDocuments").asLong(),
          "the recovery response must publish the new Worker source value");
      int recoveryCalls = f.calls.get();
      var freshInput = WorkerOperationalViewBuilder.builder(view())
          .core(new CoreIndexView(true, 12, 0, view().core().indexState(), 0, 0)).build();
      f.workerView.set(freshInput);
      long freshAt = clock.incrementAndGet();
      long freshProjectionStartedMs = System.currentTimeMillis();
      get(f, "?fresh=true");
      boolean newer=false;
      for(int i=0;i<20&&!newer;i++) {
        Thread.sleep(50);
        var next = get(f, "");
        newer = next.path("meta").path("workerRpcAtMs").asLong() == freshAt
            && next.path("worker").path("core").path("indexedDocuments").asLong() == 12L;
        if (newer) assertWorkerTimes(next, freshAt, false, freshProjectionStartedMs,
            f.requestStartedMs, f.requestCompletedMs, java.util.Map.of());
      }
      assertTrue(newer,"fresh request must eventually publish the exact new Worker value and source timestamp");
      f.drainPublications(null);
      assertTrue(f.calls.get() > recoveryCalls, "fresh12 has its own source invocation");
      assertSame(freshInput, f.consumedWorker.get());
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
      f.work.gates.add(release);
      var holder=Thread.ofPlatform().daemon().unstarted(()->{synchronized(fieldGet(field,f.admission)){
        entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();f.work.errors.add(e);}
      }});
      f.work.threads.add(holder);
      holder.setUncaughtExceptionHandler((thread, failure) -> f.work.errors.add(failure));
      holder.start();
      Throwable phaseFailure = null;
      try {assertTrue(entered.await(2,TimeUnit.SECONDS));get(f,"");get(f,"?fresh=true");}
      catch (Exception | Error phaseError) { phaseFailure = phaseError; throw phaseError; } finally{
        try {release.countDown();holder.join(3000);assertFalse(holder.isAlive());
        } catch (Exception | Error cleanupError) {
          if (phaseFailure == null) throw cleanupError;
          if (cleanupError != phaseFailure) phaseFailure.addSuppressed(cleanupError);
        }
      }
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
      when(reader.sampleNow()).thenAnswer(a -> {
        f.work.gpuSampleEntered();
        return new io.justsearch.app.api.gpu.GpuMemorySample(
        io.justsearch.app.api.gpu.GpuMemorySource.NVML,java.util.Optional.empty(),
        java.util.Optional.of(new io.justsearch.app.api.gpu.GpuDevice("luid_0x00000000_0x00000001",java.util.OptionalInt.of(0))),
        java.util.Optional.of("verifier GPU"),java.util.OptionalLong.of(12L*1024*1024*1024),
        java.util.OptionalLong.of(1024*1024*1024),java.util.Map.of(),Instant.ofEpochMilli(clock.get()));
      });
      Throwable phaseFailure = null;
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
      } catch (Exception | Error phaseError) { phaseFailure = phaseError; throw phaseError; } finally {
        try {
          f.drainPublications(null);
          f.handler.closeGpuMemoryReader();
        } catch (Exception | Error cleanupError) {
          if (phaseFailure == null) throw cleanupError;
          if (cleanupError != phaseFailure) phaseFailure.addSuppressed(cleanupError);
        }
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
      f.work.gates.add(release);
      var first=new AtomicBoolean(true);
      f.app.beforeMatched(ctx->{if(ctx.path().equals("/api/status")&&first.compareAndSet(true,false)){
        entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();f.work.errors.add(e);}
      }});
      CompletableFuture<HttpResponse<String>> pending=null;
      Throwable primaryFailure = null;
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
      } catch (Exception | Error failure) { primaryFailure = failure; throw failure; } finally {
        release.countDown();
        var cleanupFailure = new Exception("status request/admission cleanup failed");
        try { if(pending!=null)pending.get(3,TimeUnit.SECONDS); }
        catch (Throwable failure) { cleanupFailure.addSuppressed(failure); }
        for (var observer : observers) {
          try { observer.close(); } catch (Throwable failure) { cleanupFailure.addSuppressed(failure); }
        }
        for (var owner : owners) {
          try { owner.close(); } catch (Throwable failure) { cleanupFailure.addSuppressed(failure); }
        }
        if (cleanupFailure.getSuppressed().length > 0) {
          if (primaryFailure == null) throw cleanupFailure;
          primaryFailure.addSuppressed(cleanupFailure);
        }
      }
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
