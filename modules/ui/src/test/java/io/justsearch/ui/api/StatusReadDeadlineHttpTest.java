/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.services.atrest.AtRestProtection;
import io.justsearch.app.services.atrest.DiskEncryptionProbe;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The fresh-read deadline ends one response's wait; it is not evidence about a status lane. Slow
 * lanes (disk encryption, GPU) keep their last real value while their refresh is held past the
 * 100 ms deadline and past one second, age only by their own sampling period, and the status
 * counter advances once per real change, on real HTTP.
 */
final class StatusReadDeadlineHttpTest {
  private static final long READ_BUDGET_MS = 1_000L;
  /** A lane's own coalesced refresh may keep one waiter; reads and sampler passes add none. */
  private static final int MAX_LANE_WAITERS = 1;
  /** One publication of the released lane plus at most one coalesced follow-up pass. */
  private static final int MAX_RELEASE_PROJECTIONS = 4;
  /** Idle sampling period (10 s) times SAMPLE_STALE_PERIODS: the least limit of an idle lane. */
  private static final long IDLE_STALE_AFTER_MS =
      StatusLifecycleHandler.SAMPLER_IDLE_PERIOD_MS * StatusLifecycleHandler.SAMPLE_STALE_PERIODS;

  /** The most periods of data age a lane's effective cadence allows (L1): three. */
  private static final long ALLOWANCE_PERIODS = 3L;

  @TempDir Path temp;

  @Test
  @Timeout(120)
  void heldSlowLanesKeepTheirLastRealValueAcrossReadDeadlinesAndAgeOnlyByTheirOwnPeriod()
      throws Exception {
    try (var f = new Fixture(temp)) {
      f.settle();
      var reads = new ArrayList<Read>();
      var settled = f.read("?fresh=true");
      reads.add(settled);
      assertEquals("NOT_ENCRYPTED", settled.atRest());
      assertTrue(settled.gpuAvailable());
      assertEquals("nvml", settled.body().path("gpu").path("source").asText());
      long base = settled.change();
      long atRestProbedAt = f.lastProbeAt("at-rest");

      // Both slow lanes need a new probe, and both probes are held.
      f.expireProbeCaches();
      var atRest = f.hold("at-rest");
      var gpu = f.hold("gpu");
      reads.add(f.read("?fresh=true"));
      assertTrue(atRest.entered.await(10, TimeUnit.SECONDS), "the at-rest refresh is held");
      assertTrue(gpu.entered.await(10, TimeUnit.SECONDS), "the GPU refresh is held");
      long heldSince = f.clock();

      // Fresh reads keep arriving beyond the 100 ms deadline and beyond one second.
      long started = System.nanoTime();
      int fresh = 0;
      while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_600L) {
        var read = f.read(fresh % 3 == 2 ? "" : "?fresh=true");
        reads.add(read);
        fresh++;
        assertTrue(read.latencyMs() < READ_BUDGET_MS, "status read must stay bounded: " + read.latencyMs());
        assertEquals("NOT_ENCRYPTED", read.atRest(),
            "a read deadline must not replace the last real disk-encryption value");
        assertTrue(read.gpuAvailable(), "a read deadline must not replace the last real GPU value");
        assertEquals(base, read.change(), "a read deadline is not a state change");
      }
      assertTrue(f.clock() - heldSince >= 1_500L, "the refreshes were held beyond one second");
      assertTrue(f.age("at-rest") >= f.clock() - atRestProbedAt - 5L,
          "the held lane's observation time stays its last real probe time");

      // Age the held lanes by their own cadence while every other lane keeps sampling. Each held
      // lane's limit is three of its effective cadences (L1): at least three periods, and at most
      // the cap, three times the period plus the allowance of three periods.
      long atRestLimit = f.staleAfter("at-rest");
      long gpuLimit = f.staleAfter("gpu");
      for (long limit : new long[] {atRestLimit, gpuLimit}) {
        assertTrue(limit >= IDLE_STALE_AFTER_MS && limit <= IDLE_STALE_AFTER_MS * (1L + ALLOWANCE_PERIODS),
            "a bounded limit: " + limit);
      }
      for (int step = 0; step < 2; step++) {
        f.offset.addAndGet(12_000L);
        f.sampleWhileHeld();
        var read = f.read("?fresh=true");
        reads.add(read);
        assertTrue(f.age("at-rest") <= atRestLimit, "still within three cadences");
        assertEquals("NOT_ENCRYPTED", read.atRest(), "within three cadences the last real value stands");
        assertTrue(read.gpuAvailable());
        assertEquals(base, read.change(), "time within the lane's cadence is not a state change");
      }
      f.offset.addAndGet(Math.max(atRestLimit - f.age("at-rest"), gpuLimit - f.age("gpu")) + 2_000L);
      f.sampleWhileHeld();
      assertTrue(f.age("at-rest") > atRestLimit);
      assertTrue(f.age("gpu") > gpuLimit);
      var stale = f.read("?fresh=true");
      reads.add(stale);
      assertTrue(stale.latencyMs() < READ_BUDGET_MS);
      assertEquals("UNKNOWN", stale.atRest(), "a lane older than three cadences is reported unknown");
      assertEquals("UNKNOWN", stale.body().path("atRestProtection").path("confidence").asText());
      assertFalse(stale.gpuAvailable(), "a GPU lane older than three cadences is reported unavailable");
      assertEquals(base + 1, stale.change(), "the held lanes becoming stale by their own age is one change");
      assertExactlyThoseLanesAffected(stale.body());
      for (int i = 0; i < 4; i++) {
        var read = f.read(i % 2 == 0 ? "?fresh=true" : "");
        reads.add(read);
        assertEquals("UNKNOWN", read.atRest());
        assertEquals(base + 1, read.change(), "deadlines while stale advance nothing");
      }

      // Recovery: each lane's own completion is one real change.
      gpu.release();
      var gpuBack = f.awaitRead(read -> read.gpuAvailable());
      reads.add(gpuBack);
      assertEquals("UNKNOWN", gpuBack.atRest(), "only the recovered lane changes");
      assertEquals(base + 2, gpuBack.change());
      atRest.release();
      var atRestBack = f.awaitRead(read -> "NOT_ENCRYPTED".equals(read.atRest()));
      reads.add(atRestBack);
      assertEquals(base + 3, atRestBack.change());
      assertTrue(f.age("at-rest") < IDLE_STALE_AFTER_MS, "recovered lane is current again");
      for (int i = 0; i < 3; i++) {
        var read = f.read("?fresh=true");
        reads.add(read);
        assertEquals(base + 3, read.change(), "recovered and unchanged: no further advance");
      }

      // A real change is one advance, and the deadline after it adds none.
      f.atRestState.set(AtRestProtection.State.ENCRYPTED);
      f.expireProbeCaches();
      f.handler.sampleAndBuildStatusSnapshot();
      var changed = f.read("?fresh=true");
      reads.add(changed);
      assertEquals("ENCRYPTED", changed.atRest());
      assertEquals(base + 4, changed.change());
      for (int i = 0; i < 3; i++) {
        var read = f.read("?fresh=true");
        reads.add(read);
        assertEquals(base + 4, read.change());
      }

      // No value alternates: the reported sequence is exactly the real transitions.
      var transitions = new ArrayList<String>();
      for (var read : reads) {
        String shown = read.atRest() + "/" + read.gpuAvailable();
        if (transitions.isEmpty() || !transitions.getLast().equals(shown)) transitions.add(shown);
      }
      assertEquals(List.of("NOT_ENCRYPTED/true", "UNKNOWN/false", "UNKNOWN/true",
          "NOT_ENCRYPTED/true", "ENCRYPTED/true"), transitions);
    }
  }

  @Test
  @Timeout(60)
  void cachedProbeAnswersKeepTheirRealProbeTime() throws Exception {
    try (var f = new Fixture(temp)) {
      f.settle();
      f.read("?fresh=true");
      long atRestProbedAt = f.lastProbeAt("at-rest");
      long gpuProbedAt = f.lastProbeAt("gpu");
      int atRestProbes = f.probes("at-rest");
      f.offset.addAndGet(3_000L);
      f.handler.sampleAndBuildStatusSnapshot();
      assertEquals(atRestProbes, f.probes("at-rest"), "the at-rest answer came from its cache");
      assertTrue(f.age("at-rest") >= f.clock() - atRestProbedAt - 5L,
          "a cached answer keeps its probe time, not the refresh time: age " + f.age("at-rest"));
      assertTrue(f.age("gpu") >= f.clock() - gpuProbedAt - 5L,
          "a cached GPU answer keeps its probe time: age " + f.age("gpu"));
      f.offset.addAndGet(2_500L);
      f.handler.sampleAndBuildStatusSnapshot();
      assertTrue(f.probes("at-rest") > atRestProbes, "an expired cache probes again");
      assertTrue(f.age("at-rest") < 1_000L, "a new probe is a new observation");
    }
  }

  /**
   * b82 S6: a fresh read answered at its deadline leaves nothing behind on a held lane. Neither
   * repeated fresh reads nor periodic sampler passes collect waiters on the held lane's
   * observation, and its release runs only the lane's own publication, not the obsolete work of
   * every read that waited on it.
   */
  @Test
  @Timeout(120)
  void freshDeadlineDetachesOutstandingObservationWaiters() throws Exception {
    try (var f = new Fixture(temp)) {
      f.settle();
      f.expireProbeCaches();
      var atRest = f.hold("at-rest");
      f.read("?fresh=true");
      assertTrue(atRest.entered.await(10, TimeUnit.SECONDS), "the at-rest refresh is held");
      var held = f.running("at-rest");
      assertFalse(held.isDone());

      int reads = 0;
      for (int batch = 0; batch < 5; batch++) {
        for (int i = 0; i < 20; i++, reads++) {
          assertTrue(f.read("?fresh=true").latencyMs() < READ_BUDGET_MS);
        }
        int waiters = held.getNumberOfDependents();
        System.out.println("STATUS_WAITERS freshReads=" + reads + " dependents=" + waiters);
        assertTrue(waiters <= MAX_LANE_WAITERS,
            "fresh reads answered at their deadline must not stay attached to the held lane: "
                + waiters + " waiters after " + reads + " reads");
      }

      var projections = f.countOperatingProjections();
      atRest.release();
      held.get(10, TimeUnit.SECONDS);
      int work = f.settledCount(projections);
      System.out.println("STATUS_RELEASE operatingProjections=" + work + " afterReads=" + reads);
      assertTrue(work <= MAX_RELEASE_PROJECTIONS,
          "the release must run the lane's own publication, not one projection per earlier read: " + work);
      assertEquals("NOT_ENCRYPTED", f.read("").atRest());
    }
  }

  /** b82 S6, periodic sampling alone: sampler passes leave no waiter on a held lane either. */
  @Test
  @Timeout(120)
  void samplerPassesLeaveNoWaitersOnAHeldLane() throws Exception {
    try (var f = new Fixture(temp)) {
      f.settle();
      f.expireProbeCaches();
      var atRest = f.hold("at-rest");
      f.sampleWhileHeld();
      assertTrue(atRest.entered.await(10, TimeUnit.SECONDS), "the at-rest refresh is held");
      var held = f.running("at-rest");
      for (int pass = 2; pass <= 6; pass++) {
        f.sampleWhileHeld();
        int waiters = held.getNumberOfDependents();
        System.out.println("STATUS_WAITERS samplerPasses=" + pass + " dependents=" + waiters);
        assertTrue(waiters <= MAX_LANE_WAITERS,
            "sampler passes must not stay attached to the held lane: " + waiters + " waiters after "
                + pass + " passes");
      }
      var projections = f.countOperatingProjections();
      atRest.release();
      held.get(10, TimeUnit.SECONDS);
      int work = f.settledCount(projections);
      System.out.println("STATUS_RELEASE operatingProjections=" + work + " afterSamplerPasses=6");
      assertTrue(work <= MAX_RELEASE_PROJECTIONS, "bounded release work: " + work);
      assertEquals("NOT_ENCRYPTED", f.read("").atRest());
    }
  }

  private static void assertExactlyThoseLanesAffected(JsonNode body) {
    assertEquals("unlocked", body.path("conversationProtection").path("state").asText(),
        "an unrelated lane stays current");
    assertFalse(body.path("meta").path("workerRpcStale").asBoolean(), "the Worker lane stays current");
    assertEquals("OK", body.path("diskPressure").asText());
    for (var dimension : io.justsearch.app.api.lifecycle.ReadinessDimension.values()) {
      assertFalse(body.path("readiness").path("components").path(dimension.key()).path("stale").asBoolean(),
          "the slow lanes must not age readiness " + dimension.key());
    }
  }

  record Read(int code, long change, long latencyMs, JsonNode body) {
    String atRest() { return body.path("atRestProtection").path("diskEncryption").asText(); }
    boolean gpuAvailable() { return body.path("gpu").path("available").asBoolean(); }
  }

  static final class Gate {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch released = new CountDownLatch(1);
    private final Runnable clear;
    Gate(Runnable clear) { this.clear = clear; }
    void release() { clear.run(); released.countDown(); }
  }

  static final class Fixture implements AutoCloseable {
    final AtomicLong offset = new AtomicLong();
    final AtomicReference<AtRestProtection.State> atRestState =
        new AtomicReference<>(AtRestProtection.State.NOT_ENCRYPTED);
    final Map<String, Gate> gates = new ConcurrentHashMap<>();
    final Map<String, Long> probedAt = new ConcurrentHashMap<>();
    final Map<String, Integer> probeCounts = new ConcurrentHashMap<>();
    final StatusComponentFixture components = new StatusComponentFixture();
    final StatusLifecycleHandler handler;
    final Javalin app;
    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    Fixture(Path temp) {
      var ks = mock(KnowledgeServerBootstrap.class);
      var client = mock(KnowledgeClient.class);
      when(ks.hasClient()).thenReturn(true);
      BootstrapLeaseFixtures.bind(ks, client);
      WorkerOperationalView view = WorkerOperationalViewBuilder.builder(WorkerOperationalView.fallback("READY"))
          .core(new CoreIndexView(true, 10, 0, "SERVING", 0, 0)).build();
      when(client.getWorkerOperationalView(any())).thenReturn(view);
      var gpuService = mock(GpuCapabilitiesService.class);
      var caps = mock(GpuCapabilities.class);
      when(caps.nvml()).thenReturn(new GpuCapabilities.Nvml(true, null, "nvml.dll", null, "580.1",
          580, 1, 1, 12L << 30, 8L << 30, 4L << 30, 10, 20));
      when(caps.effective()).thenReturn(new GpuCapabilities.Effective(true, "nvml",
          GpuCapabilities.Confidence.HIGH, "580.1", 580, 1, 1, 12L << 30, 8L << 30, 4L << 30, null));
      when(gpuService.snapshot(any(GpuCapabilities.Cuda.class))).thenAnswer(i -> {
        probe("gpu");
        return caps;
      });
      var diskProbe = mock(DiskEncryptionProbe.class);
      when(diskProbe.current()).thenAnswer(i -> {
        probe("at-rest");
        return new AtRestProtection(atRestState.get(), "shell-property", AtRestProtection.Confidence.HIGH);
      });
      var graph = components.capabilities();
      handler = new StatusLifecycleHandler(mock(io.justsearch.app.api.OnlineAiService.class),
          mock(io.justsearch.agent.api.AgentService.class), () -> null, null, null, temp,
          Instant.now(), () -> "OK", null, null, () -> gpuService, graph.worker(), graph.inference());
      components.attach(handler);
      handler.setClockForTesting(this::clock);
      handler.setKnowledgeServer(ks, null);
      handler.setConversationProtectionSupplier(() -> "unlocked");
      handler.setAtRestTap(null, diskProbe);
      app = Javalin.create(c -> c.showJavalinBanner = false);
      io.justsearch.ui.api.routes.StatusRoutes.registerLifecycleRoutes(
          app, handler::handleStatus, handler::handleHealth);
      app.start("127.0.0.1", 0);
    }

    long clock() { return System.currentTimeMillis() + offset.get(); }

    void probe(String lane) {
      probedAt.put(lane, clock());
      probeCounts.merge(lane, 1, Integer::sum);
      var gate = gates.get(lane);
      if (gate == null) return;
      gate.entered.countDown();
      try {
        if (!gate.released.await(60, TimeUnit.SECONDS)) throw new AssertionError("unreleased " + lane);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }

    Gate hold(String lane) {
      var gate = new Gate(() -> gates.remove(lane));
      gates.put(lane, gate);
      return gate;
    }

    long lastProbeAt(String lane) { return probedAt.get(lane); }
    int probes(String lane) { return probeCounts.getOrDefault(lane, 0); }

    void settle() throws Exception {
      handler.sampleAndBuildStatusSnapshot();
      handler.sampleAndBuildStatusSnapshot();
      read("?fresh=true");
    }

    /** One sampler pass while lanes are held: every other lane publishes a new observation. */
    void sampleWhileHeld() throws Exception {
      var pass = Thread.ofPlatform().daemon().start(handler::sampleAndBuildStatusSnapshot);
      pass.join(15_000L);
      assertFalse(pass.isAlive(), "the sampler pass is bounded while lanes are held");
    }

    void expireProbeCaches() throws Exception {
      for (String name : new String[] {"cachedGpuStatusAtMs", "cachedAtRestAtMs"}) {
        var field = StatusLifecycleHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(handler, 0L);
      }
    }

    long age(String lane) throws Exception {
      var field = StatusLifecycleHandler.class.getDeclaredField("observations");
      field.setAccessible(true);
      var lanes = (Map<?, ?>) field.get(handler);
      return ((StatusObservation<?>) lanes.get(lane)).age(clock());
    }

    /** The lane's own staleness limit for its last real observation. */
    long staleAfter(String lane) throws Exception {
      var field = StatusLifecycleHandler.class.getDeclaredField("observations");
      field.setAccessible(true);
      var lanes = (Map<?, ?>) field.get(handler);
      return ((StatusObservation<?>) lanes.get(lane)).staleAfterMs();
    }

    /** The held lane's outstanding observation. */
    java.util.concurrent.CompletableFuture<?> running(String lane) throws Exception {
      var field = StatusLifecycleHandler.class.getDeclaredField("observations");
      field.setAccessible(true);
      var observation = ((Map<?, ?>) field.get(handler)).get(lane);
      var running = StatusObservation.class.getDeclaredField("running");
      running.setAccessible(true);
      var future = ((AtomicReference<?>) running.get(observation)).get();
      assertNotNull(future, "lane " + lane + " has an outstanding observation");
      return (java.util.concurrent.CompletableFuture<?>) future;
    }

    /** Counts operating projections: each assembly reads the device plan exactly once. */
    java.util.concurrent.atomic.AtomicInteger countOperatingProjections() throws Exception {
      var count = new java.util.concurrent.atomic.AtomicInteger();
      var field = StatusLifecycleHandler.class.getDeclaredField("operatingStateAssembler");
      field.setAccessible(true);
      var assembler = (OperatingStateAssembler) field.get(handler);
      assembler.setDevicePlan(() -> {
        count.incrementAndGet();
        return null;
      });
      return count;
    }

    /** Waits until the counter has not moved for half a second and returns it. */
    int settledCount(java.util.concurrent.atomic.AtomicInteger count) throws Exception {
      int last = -1;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (count.get() != last && System.nanoTime() < deadline) {
        last = count.get();
        Thread.sleep(500L);
      }
      return count.get();
    }

    Read read(String query) throws Exception {
      long started = System.nanoTime();
      var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
          + "/api/status" + query)).timeout(Duration.ofSeconds(5)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertEquals(200, response.statusCode());
      long change = Long.parseLong(response.headers().firstValue(StatusChangeHeaders.CHANGE).orElseThrow());
      var read = new Read(response.statusCode(), change, ms, json.readTree(response.body()));
      System.out.println("STATUS_DEADLINE query=" + query + " ms=" + ms + " change=" + change
          + " atRest=" + read.atRest() + " gpu=" + read.gpuAvailable());
      return read;
    }

    Read awaitRead(java.util.function.Predicate<Read> condition) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      Read read;
      do {
        read = read("");
        if (condition.test(read)) return read;
        Thread.sleep(20L);
      } while (System.nanoTime() < deadline);
      fail("the released lane did not publish: " + read.body());
      return read;
    }

    @Override public void close() {
      gates.values().forEach(Gate::release);
      app.stop();
      http.close();
      components.close();
    }
  }
}
