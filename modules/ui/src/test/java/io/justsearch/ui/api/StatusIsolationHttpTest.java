/* SPDX-License-Identifier: Apache-2.0 */
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

class StatusIsolationHttpTest {
  final JsonMapper json = JsonMapper.builder().build();
  final HttpClient client = HttpClient.newHttpClient();
  static WorkerOperationalView view() {
    return view(0);
  }
  static WorkerOperationalView view(long pendingJobs) {
    return WorkerOperationalViewBuilder.builder()
      .core(new CoreIndexView(true, 10, pendingJobs, pendingJobs > 0 ? "INDEXING" : "IDLE", 0, 0))
      .failure(FailureTrackingView.empty()).migration(MigrationGenerationViewBuilder.builder()
          .migrationEnumerator(MigrationGenerationView.empty().migrationEnumerator())
          .pendingJobsCount(pendingJobs).pendingReadyJobsCount(pendingJobs).build())
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
    final KnowledgeServerBootstrap ks = mock(KnowledgeServerBootstrap.class);
    final KnowledgeClient kc = mock(KnowledgeClient.class);
    final StatusLifecycleHandler handler;
    final Javalin app;
    final io.justsearch.app.engine.EngineAdmissionController admission=new io.justsearch.app.engine.EngineAdmissionController(4,16,1);
    Fixture(boolean workerBlock) {
      when(ks.hasClient()).thenReturn(true);
      BootstrapLeaseFixtures.bind(ks,kc);
      when(kc.getWorkerOperationalView(any())).thenAnswer(a -> {
        if (workerBlock) probe();
        return workerView.get();
      });
      var caps = components.capabilities();
      handler = new StatusLifecycleHandler(mock(io.justsearch.app.api.OnlineAiService.class),
        mock(io.justsearch.agent.api.AgentService.class), () -> null, ks, null,
        java.nio.file.Path.of("C:/agent-sandbox-tmp/b53/test-tmp"), Instant.now(),
        () -> { if (!workerBlock) probe(); return "OK"; }, null,null,null,caps.worker(),caps.inference());
      components.attach(handler);
      handler.sampleAndBuildStatusSnapshot();
      app=Javalin.create();
      new ApiSecurityFilters(false,null,null,null,null,null,admission).install(app);
      io.justsearch.ui.api.routes.StatusRoutes.registerLifecycleRoutes(app,handler::handleStatus,handler::handleHealth);
      app.start("127.0.0.1",0);
      // Warm route registration and JSON serialization before measuring diagnostic poll latency.
      try (var warm = HttpClient.newHttpClient()) {
        var response = warm.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
            + "/api/status")).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
      } catch (Exception failure) { throw new AssertionError(failure); }

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
  @Test @org.junit.jupiter.api.Timeout(45)
  void busyCadenceReachesHealthyComponentsBeforeTheirObservationsExpire() throws Exception {
    try (var f = new Fixture(true);
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var trigger = new io.justsearch.app.services.observability.health.ReadinessReconciliationTrigger(executors);
        var monitor = new KnowledgeServerHealthMonitor(executors, f.ks, 10000L)) {
      when(f.ks.tryCheckHealth()).thenReturn(java.util.Optional.of(true));
      monitor.componentRegistry(f.components.registry());
      var bindings = new java.util.LinkedHashMap<String, ComponentRecoveryBinding>();
      for (var row : f.components.registry().snapshot().components()) {
        bindings.put(row.spec().name(), new ComponentRecoveryBinding(f.components.handle(row.spec().name()), null));
      }
      monitor.componentRecoveryBindings(bindings, ignored -> {});
      var seeded = new CountDownLatch(1);
      var busy = new CountDownLatch(1);
      trigger.attach(() -> {
        f.handler.sampleAndBuildStatusSnapshot();
        if (f.handler.samplingPeriodMs() == 2000L) busy.countDown();
        else seeded.countDown();
      });
      assertTrue(seeded.await(3, TimeUnit.SECONDS));
      assertEquals(10000L, f.handler.samplingPeriodMs());
      monitor.onTick(trigger::request);
      monitor.tickIntervalSupplier(f.handler::samplingPeriodMs);
      f.workerView.set(view(1));
      f.block.set(true);
      monitor.start();
      assertTrue(f.entered.await(13, TimeUnit.SECONDS));
      Thread.sleep(100L);
      f.block.set(false);
      f.release.countDown();
      assertTrue(busy.await(3, TimeUnit.SECONDS), "the real sample must first adopt the busy cadence");
      // The real monitor queued its next idle tick before the asynchronous sample chose busy.
      Thread.sleep(7100L);
      var snapshot = get(f, "");
      assertFalse(snapshot.path("readiness").path("components").path("telemetry").path("stale").asBoolean(),
          "the busy cadence must refresh healthy telemetry before its observation expires");
      assertTrue(snapshot.path("operating").path("freshness").path("staleSources").isEmpty(),
          "the asynchronous cadence transition cannot expire healthy operating components");
      assertFalse(snapshot.path("meta").path("workerRpcStale").asBoolean());
    }
  }
  @Test void freshReadShowsPostActionStateWhilePeriodicSamplerOwnsHungDisk() throws Exception {
    try (var f = new Fixture(false);
        var executors = new io.justsearch.core.execution.TestEngineExecutors();
        var trigger = new io.justsearch.app.services.observability.health.ReadinessReconciliationTrigger(executors)) {
      var state = new AtomicReference<>("locked");
      f.handler.setConversationProtectionSupplier(state::get);
      f.handler.sampleAndBuildStatusSnapshot();
      assertEquals("locked", get(f, "").path("conversationProtection").path("state").asText());
      f.handler.setRefreshRequest(trigger::request);
      f.block.set(true);
      trigger.attach(f.handler::sampleAndBuildStatusSnapshot);
      assertTrue(f.entered.await(2, TimeUnit.SECONDS));
      f.app.post("/api/test/unlock", ctx -> { state.set("unlocked"); ctx.status(204); });
      var action = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + f.app.port()
          + "/api/test/unlock")).timeout(Duration.ofSeconds(2)).POST(HttpRequest.BodyPublishers.noBody()).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(204, action.statusCode());
      assertEquals("unlocked", get(f, "?fresh=true").path("conversationProtection").path("state").asText(),
          "the post-action owner must publish while the periodic sampler is occupied");
    }
  }
  @Test void postActionFreshReadIncludesAQueuedObservationAfterAnEarlierRead() throws Exception {
    try (var f = new Fixture(false)) {
      var state = new AtomicReference<>("locked");
      var hold = new AtomicBoolean();
      var captured = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      f.handler.setConversationProtectionSupplier(() -> {
        String before = state.get();
        if (hold.get()) {
          captured.countDown();
          try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return before;
      });
      f.handler.sampleAndBuildStatusSnapshot();
      assertEquals("locked", get(f, "").path("conversationProtection").path("state").asText());
      hold.set(true);
      var sampler = Thread.ofPlatform().daemon().start(f.handler::sampleAndBuildStatusSnapshot);
      try {
        assertTrue(captured.await(2, TimeUnit.SECONDS));
        sampler.join(5_000L);
        assertFalse(sampler.isAlive(), "a held conversation producer cannot hold the sampling pass");
        f.app.post("/api/test/unlock", ctx -> { state.set("unlocked"); ctx.status(204); });
        var action = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + f.app.port()
            + "/api/test/unlock")).timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(204, action.statusCode());
        var requested = new CountDownLatch(1);
        f.app.beforeMatched(ctx -> { if ("true".equals(ctx.queryParam("fresh"))) requested.countDown(); });
        var fresh = client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + f.app.port()
            + "/api/status?fresh=true")).timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertTrue(requested.await(2, TimeUnit.SECONDS));
        Thread.sleep(20L);
        hold.set(false);
        release.countDown();
        var response = fresh.get(5, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertEquals("unlocked", json.readTree(response.body()).path("conversationProtection").path("state").asText(),
            "a fresh post-action read cannot acknowledge a value captured before the action");
      } finally {
        hold.set(false);
        release.countDown();
        sampler.join(5_000L);
      }
    }
  }
  @Test void actualFrontendRefreshPublishesPostActionStateFromProductionHttpRoute() throws Exception {
    try (var f = new Fixture(false)) {
      var state = new AtomicReference<>("locked");
      f.handler.setConversationProtectionSupplier(state::get);
      f.handler.sampleAndBuildStatusSnapshot();
      f.app.post("/api/test/unlock", ctx -> { state.set("unlocked"); ctx.status(204); });
      var root = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
      while (!java.nio.file.Files.isRegularFile(root.resolve("modules/ui-web/src/shell-v0/utils/statusPoll.ts"))) {
        root = java.util.Objects.requireNonNull(root.getParent(), "frontend source root");
      }
      var script = java.nio.file.Files.createTempFile("status-poll-http-", ".mjs");
      var output = java.nio.file.Files.createTempFile("status-poll-bundle-", ".mjs");
      var log = java.nio.file.Files.createTempFile("status-poll-output-", ".log");
      java.nio.file.Files.writeString(script, """
          import assert from 'node:assert/strict';
          import {createRequire} from 'node:module';
          import {pathToFileURL} from 'node:url';
          import path from 'node:path';
          const [root, base, out] = process.argv.slice(2);
          const require = createRequire(path.join(root, 'modules/ui-web/package.json'));
          const {build} = require('esbuild');
          await build({entryPoints:[path.join(root,'modules/ui-web/src/shell-v0/utils/statusPoll.ts')],
            outfile:out,bundle:true,platform:'node',format:'esm',target:'node24',define:{'import.meta.env':'{"DEV":true}'},
            plugins:[{name:'loopback-transport',setup(b){
              b.onResolve({filter:/authorizedFetch[.]js$/},()=>({path:'loopback',namespace:'http'}));
              b.onLoad({filter:/.*/,namespace:'http'},()=>({contents:
                'export const authorizedFetch=(input,init)=>fetch(input,{...init,signal:AbortSignal.any([init.signal,AbortSignal.timeout(5000)])});'}));
            }}]});
          globalThis.window = {setInterval,clearInterval};
          const poller = await import(pathToFileURL(out));
          poller.setStatusApiBase(base);
          let latest;
          let first;
          const observed = new Promise(resolve => {first=resolve;});
          const unsubscribe = poller.subscribeStatus(value=>{latest=value;first();});
          try {
            await observed;
            assert.equal(latest?.conversationProtection?.state,'locked','initial real HTTP snapshot');
            const action=await fetch(base+'/api/test/unlock',{method:'POST',signal:AbortSignal.timeout(5000)});
            assert.equal(action.status,204);
            await poller.refreshStatusNow();
            assert.equal(latest?.conversationProtection?.state,'unlocked','actual frontend refresh must publish post-action state');
            console.log('FRONTEND_HTTP_POST_ACTION=unlocked');
          } finally {unsubscribe();poller.__resetStatusPollForTest();}
          """, java.nio.charset.StandardCharsets.UTF_8);
      Process child = null;
      Instant childBirth = null;
      try {
        child = new ProcessBuilder("node", script.toString(), root.toString(),
            "http://127.0.0.1:" + f.app.port(), output.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        childBirth = child.info().startInstant().orElseThrow();
        System.err.println("status-isolation-node-start=" + child.pid() + "/" + childBirth);
        assertTrue(child.waitFor(15, TimeUnit.SECONDS), "frontend loopback check exits normally");
        var body = java.nio.file.Files.readString(log, java.nio.charset.StandardCharsets.UTF_8);
        System.out.println(body);
        assertEquals(0, child.exitValue(), body);
        assertTrue(body.contains("FRONTEND_HTTP_POST_ACTION=unlocked"));
      } finally {
        if (child != null && child.isAlive()) {
          if (!child.info().startInstant().orElseThrow().equals(childBirth)) {
            throw new IllegalStateException("Node process identity changed; cleanup refused");
          }
          child.destroyForcibly();
        }
        java.nio.file.Files.deleteIfExists(script);
        java.nio.file.Files.deleteIfExists(output);
        java.nio.file.Files.deleteIfExists(log);
      }
    }
  }

  @Test @org.junit.jupiter.api.Timeout(20)
  void newerHealthyContactSurvivesAnOlderPendingFailurePublication() throws Exception {
    var publicationEntered = new CountDownLatch(1);
    var publicationRelease = new CountDownLatch(1);
    var healthyContact = new CountDownLatch(1);
    try (var f = new Fixture(true);
        var threads = Executors.newFixedThreadPool(2)) {
      var handle = spy(f.components.handle("index"));
      doAnswer(call -> {
        publicationEntered.countDown();
        assertTrue(publicationRelease.await(5, TimeUnit.SECONDS));
        return call.callRealMethod();
      }).when(handle).transitionIfUnchanged(any(io.justsearch.core.component.EngineComponentSnapshot.class),
          eq(io.justsearch.core.component.ComponentState.UNAVAILABLE), anyString(), anyString());
      f.handler.setIndexComponent(f.components.registry(), handle);
      var calls = new AtomicInteger();
      when(f.kc.getWorkerOperationalView(any())).thenAnswer(call -> {
        if (calls.incrementAndGet() == 1) throw new IllegalStateException("older failed contact");
        healthyContact.countDown();
        return view();
      });
      var first = threads.submit(f.handler::sampleAndBuildStatusSnapshot);
      try {
        assertTrue(publicationEntered.await(3, TimeUnit.SECONDS));
        var second = threads.submit(f.handler::sampleAndBuildStatusSnapshot);
        assertTrue(healthyContact.await(3, TimeUnit.SECONDS), "publication cannot hold the independent Worker observation");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (f.handler.buildStatusSnapshot().meta().workerRpcStale() && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(get(f, "").path("meta").path("workerRpcStale").asBoolean());
        publicationRelease.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (f.components.handle("index").snapshot().state() != io.justsearch.core.component.ComponentState.READY
            && System.nanoTime() < deadline) Thread.sleep(10);
        var response = get(f, "");
        assertEquals("READY", response.path("components").path("index").path("state").asText(),
            "an older status-owned failure publication cannot defeat the newer healthy observation");
        assertFalse(response.path("meta").path("workerRpcStale").asBoolean());
      } finally {
        publicationRelease.countDown();
      }
    } finally {
      publicationRelease.countDown();
    }
  }
  @Test void operatingProjectionSharesTheCurrentResponseWithoutWaitingForTheSampler() throws Exception {
    try (var f = new Fixture(false)) {
      var first = get(f, "");
      f.components.transition("generative", io.justsearch.core.component.ComponentState.FAILED,
          "inference.crashed", "inference crashed");
      var next = get(f, "");
      assertEquals("inference.crashed", next.path("components").path("generative").path("reason_code").asText());
      assertEquals(next.path("components").path("generative").path("reason_code"),
          next.path("operating").path("chat").path("reasonCode"), "all current component surfaces agree");
      assertEquals(next.path("observed_at"), next.path("operating").path("assembledAt"),
          "the response and operating projection share their actual assembly time");
      assertTrue(next.path("operating").path("seq").asLong() > first.path("operating").path("seq").asLong());
      assertEquals(next.path("operating").path("seq"), get(f, "").path("operating").path("seq"));
    }
  }
  @Test void cachedReadRetainsIndependentObservationProvenance() throws Exception {
    try (var f = new Fixture(false)) {
      var first = get(f, "");
      Thread.sleep(25L);
      var next = get(f, "");
      assertEquals(first.path("readiness").path("components").path("embedding").path("observedAt"),
          next.path("readiness").path("components").path("embedding").path("observedAt"),
          "cached source observation must not be restamped when the response is built");
      assertTrue(Instant.parse(next.path("readiness").path("observedAt").asText()).isAfter(
          Instant.parse(first.path("readiness").path("observedAt").asText())),
          "cheap current-state projection has its own observation time");
      assertFalse(next.path("readiness").path("components").path("embedding").path("stale").asBoolean());
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
      // The sampler has a deadline; wait for the independently owned producer publication.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (f.handler.buildStatusSnapshot().meta().workerRpcStale() && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
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
  @Test @org.junit.jupiter.api.Timeout(45) void healthyGpuMustStayFreshWithinIdleSamplingPeriod() throws Exception {
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
        clock.addAndGet(3000L);
        var resampled = waitForGpuPublication(f, clock.get());
        assertFalse(resampled.path("stale").asBoolean());
        String resampledAt = resampled.path("sampledAt").asText();
        clock.addAndGet(10000L);
        var later = get(f, "").path("operating").path("gpu");
        assertEquals(resampledAt, later.path("sampledAt").asText());
        assertEquals(10000L, later.path("ageMs").asLong());
        assertFalse(later.path("stale").asBoolean(),
          "healthy idle GPU stays fresh through two sampling periods");
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
