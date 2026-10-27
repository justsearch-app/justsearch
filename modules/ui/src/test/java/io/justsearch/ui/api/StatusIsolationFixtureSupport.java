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
import tools.jackson.databind.json.JsonMapper;

abstract class StatusIsolationFixtureSupport {
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
    final Instant createdAt = Instant.now();
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
      // Read the actual producer's state, rather than its delegating instrumentation mock.
      var lane = work.sources.get(name);
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
      }
      try { if (app != null) app.stop(); }
      catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
      try { if (handler != null) handler.closeGpuMemoryReader(); }
      catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
      try { if (components != null) components.close(); }
      catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
      try { if (work != null) work.release(); }
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
  static java.util.Map<String, Long> contactLaneAges(StatusFixtureWork work, long now) throws Exception {
    var lanes = work.sources;
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

  static Object fieldGet(java.lang.reflect.Field field,Object object){
    try{return field.get(object);}catch(IllegalAccessException e){throw new AssertionError(e);}
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
