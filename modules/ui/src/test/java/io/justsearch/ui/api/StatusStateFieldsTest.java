/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.Javalin;
import io.justsearch.app.api.gpu.GpuDevice;
import io.justsearch.app.api.gpu.GpuMemoryReader;
import io.justsearch.app.api.gpu.GpuMemorySample;
import io.justsearch.app.api.gpu.GpuMemorySource;
import io.justsearch.app.api.status.CompatibilityStatusView;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.EnrichmentProgressView;
import io.justsearch.app.api.status.FailureTrackingView;
import io.justsearch.app.api.status.GpuDiagnosticsView;
import io.justsearch.app.api.status.MigrationGenerationView;
import io.justsearch.app.api.status.QueueDbStatusView;
import io.justsearch.app.api.status.SearchConfigView;
import io.justsearch.app.api.status.StatusResponse;
import io.justsearch.app.api.status.TelemetryMetricsView;
import io.justsearch.app.api.status.VectorFormatView;
import io.justsearch.app.api.status.VisualExtractionView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.telemetry.RrdMetricStore;
import io.justsearch.telemetry.TelemetryHealthState;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * b73: the status-change counter advances on a change of state, never of measurement. Every
 * field canonical status reports, and every owner publication, carries a classification.
 * Real Javalin HTTP boundary and production handler; only hardware and Worker sources are fakes.
 */
class StatusStateFieldsTest {
  private final HttpClient client = HttpClient.newHttpClient();

  /** A producer the classification has never seen: a new record with a state and a clock. */
  public record FutureProducer(boolean available, String newStatusField, long ageMs) {}

  public record FutureStatus(StatusResponse status, FutureProducer future) {}

  // ---------------------------------------------------------------- classification completeness

  private static Class<?> classification() {
    try {
      return Class.forName("io.justsearch.ui.api.StatusFieldClassification");
    } catch (ClassNotFoundException missing) {
      fail("canonical status fields carry no state/measurement/time classification");
      return null;
    }
  }

  /** The classification table: record type to field name to STATE/MEASUREMENT/TIME. */
  private static Map<?, ?> table() throws Exception {
    var field = classification().getDeclaredField("FIELDS");
    field.setAccessible(true);
    return (Map<?, ?>) field.get(null);
  }

  /**
   * Paths of reported fields with no classification, walking every field reachable through STATE
   * fields from {@code root}. Fields Jackson never serialises are not reported and need none.
   */
  private static List<String> unclassified(java.lang.reflect.Type root) throws Exception {
    var missing = new ArrayList<String>();
    walk(table(), root, root.getTypeName(), new java.util.HashSet<>(), missing);
    return missing;
  }

  private static void walk(Map<?, ?> table, java.lang.reflect.Type type, String path,
      Set<java.lang.reflect.Type> visited, List<String> missing) {
    if (type instanceof java.lang.reflect.ParameterizedType parameterized) {
      for (var argument : parameterized.getActualTypeArguments()) walk(table, argument, path, visited, missing);
      return;
    }
    if (!(type instanceof Class<?> concrete)) {
      missing.add(path + " (unsupported type " + type + ")");
      return;
    }
    if (concrete.isArray()) {
      walk(table, concrete.getComponentType(), path, visited, missing);
      return;
    }
    if (!concrete.isRecord() || !visited.add(concrete)) return;
    var fields = (Map<?, ?>) table.get(concrete);
    for (var component : concrete.getRecordComponents()) {
      if (component.getAccessor().isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class)) continue;
      String at = concrete.getSimpleName() + "." + component.getName();
      Object kind = fields == null ? null : fields.get(component.getName());
      if (kind == null) missing.add(at);
      else if ("STATE".equals(kind.toString())) walk(table, component.getGenericType(), at, visited, missing);
    }
  }

  @Test void everyReportedStatusFieldIsClassified() throws Exception {
    assertEquals(List.of(), unclassified(StatusResponse.class),
        "status fields without a state/measurement/time classification");
  }

  @Test void completenessCheckFailsForAnUnclassifiedFieldOrProducer() throws Exception {
    var missing = unclassified(FutureStatus.class);
    assertTrue(missing.contains("FutureStatus.status") && missing.contains("FutureStatus.future"),
        "a new status field must be reported unclassified: " + missing);
    var nested = unclassified(FutureProducer.class);
    assertEquals(List.of("FutureProducer.available", "FutureProducer.newStatusField",
        "FutureProducer.ageMs"), nested, "a new producer record must be reported unclassified");
  }

  @Test void classificationHasNoEntryForAFieldThatNoLongerExists() throws Exception {
    var stale = new ArrayList<String>();
    for (var entry : table().entrySet()) {
      var record = (Class<?>) entry.getKey();
      var present = new java.util.HashSet<String>();
      for (var component : record.getRecordComponents()) present.add(component.getName());
      for (Object name : ((Map<?, ?>) entry.getValue()).keySet()) {
        if (!present.contains(name)) stale.add(record.getSimpleName() + "." + name);
      }
    }
    assertEquals(List.of(), stale, "classified fields that no record declares");
  }

  @Test void measurementsAndClocksAreNotComparedButStatesAre() throws Exception {
    var normalize = StatusPublicationBackstop.class.getDeclaredMethod("content", Object.class);
    var gpu = (Map<?, ?>) normalize.invoke(null, gpu(41, 3_000L, new double[] {1, 2}));
    assertEquals(Set.of("available", "driverVersion", "deviceCount", "cudaFunctional", "source",
        "confidence"), gpu.keySet(), "GPU utilisation, VRAM and history arrays are measurements");
    var telemetry = (Map<?, ?>) normalize.invoke(null, new TelemetryMetricsView(1, 2, 3, 0.25, "HEALTHY"));
    assertEquals(Map.of(), telemetry, "telemetry throughput and its window are measurements");
    assertTrue(((Map<?, ?>) normalize.invoke(null, WorkerOperationalView.fallback("OK")))
        .containsKey("core"), "state containers are still compared");
  }

  // ------------------------------------------------------------------- producer completeness

  @Test void everyOwnerPublicationIsAClassifiedProducer() throws Exception {
    var type = classification();
    var field = type.getDeclaredField("PRODUCERS");
    field.setAccessible(true);
    var producers = ((Map<?, ?>) field.get(null)).keySet();
    // Only class files whose constant pool names the counter can call it. Importing every product
    // class instead builds the whole program graph, which alone exceeds the test JVM heap (b76).
    var classes = new com.tngtech.archunit.core.importer.ClassFileImporter()
        .withImportOption(com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .withImportOption(StatusStateFieldsTest::namesStatusChangeCounter)
        .importPackages("io.justsearch");
    var counter = classes.get(io.justsearch.core.status.StatusChangeCounter.class);
    var callers = new java.util.TreeSet<String>();
    for (var call : counter.getMethodCallsToSelf()) {
      if (!call.getName().equals("changed")) continue;
      String owner = call.getOriginOwner().getName();
      if (owner.equals(counter.getName())) continue; // its own overloads delegate to changed()
      int nested = owner.indexOf('$');
      String topLevel = nested < 0 ? owner : owner.substring(0, nested);
      callers.add(topLevel.substring(topLevel.lastIndexOf('.') + 1));
    }
    var unclassified = new java.util.TreeSet<>(callers);
    unclassified.removeAll(producers);
    var stale = new java.util.TreeSet<Object>(producers);
    stale.removeAll(callers);
    assertEquals(Set.of(), unclassified, "owner publications without a producer classification");
    assertEquals(Set.of(), stale, "producer classifications with no publication");
  }

  private static final byte[] COUNTER_INTERNAL_NAME =
      "io/justsearch/core/status/StatusChangeCounter".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  /** A call, method reference or field of the counter type puts its internal name in the pool. */
  private static boolean namesStatusChangeCounter(com.tngtech.archunit.core.importer.Location location) {
    byte[] bytes;
    try (var in = location.asURI().toURL().openStream()) {
      bytes = in.readAllBytes();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException("unreadable class file " + location, e);
    }
    outer:
    for (int i = 0; i <= bytes.length - COUNTER_INTERNAL_NAME.length; i++) {
      for (int j = 0; j < COUNTER_INTERNAL_NAME.length; j++) {
        if (bytes[i + j] != COUNTER_INTERNAL_NAME[j]) continue outer;
      }
      return true;
    }
    return false;
  }

  // ------------------------------------------------------------------------ real HTTP fixture

  private static final long GIB = 1L << 30;

  static io.justsearch.app.api.status.GpuStatusView gpu(int util, long used, double[] history) {
    return new io.justsearch.app.api.status.GpuStatusView(true, util, util, 12 * GIB, used,
        12 * GIB - used, "580.1", 1, history, history, true, "nvml", "HIGH");
  }

  /** Measurements a quiet machine still changes: GPU, VRAM, history, telemetry, counts. */
  static final class Measurements {
    final AtomicInteger tick = new AtomicInteger();

    WorkerOperationalView worker() {
      int t = tick.get();
      return WorkerOperationalViewBuilder.builder()
          .core(new CoreIndexView(true, 10 + t, 0, "SERVING", 4096L + t, 0))
          .failure(FailureTrackingView.empty()).migration(MigrationGenerationView.empty())
          .compatibility(CompatibilityStatusView.empty()).queueDb(QueueDbStatusView.healthy())
          .enrichment(EnrichmentProgressView.empty()).gpu(GpuDiagnosticsView.empty())
          .vectorFormat(VectorFormatView.empty())
          .telemetry(new TelemetryMetricsView(100 + t, 1, 200 + t, t % 2 == 0 ? 0.0 : 0.25,
              t % 2 == 0 ? "UNKNOWN" : "HEALTHY"))
          .searchConfig(SearchConfigView.empty()).visualExtraction(VisualExtractionView.empty())
          .embeddingReady(true).build();
    }

    GpuCapabilities gpu() {
      int t = tick.get();
      long total = 12 * GIB;
      long used = 3 * GIB + t * 7_000_000L;
      var nvml = new GpuCapabilities.Nvml(true, null, null, null, "580.1", 580, 1, 1, total,
          total - used, used, t % 2, 10 + t);
      var cuda = new GpuCapabilities.Cuda(true, "cuda-driver-api", GpuCapabilities.Confidence.HIGH);
      var effective = new GpuCapabilities.Effective(true, "nvml", GpuCapabilities.Confidence.HIGH,
          "580.1", 580, 1, 1, total, total - used, used, cuda);
      return new GpuCapabilities(nvml, null, effective);
    }

    GpuMemorySample memory(Instant at) {
      int t = tick.get();
      return new GpuMemorySample(GpuMemorySource.NVML, Optional.empty(),
          Optional.of(new GpuDevice("luid_0x00000000_0x0000d7a2", OptionalInt.empty())),
          Optional.of("RTX"), OptionalLong.of(12 * GIB), OptionalLong.of(3 * GIB + t * 9_000_000L),
          Map.of(), at);
    }

    RrdMetricStore.TimeSeriesResult history(String metric) {
      int t = tick.get();
      return new RrdMetricStore.TimeSeriesResult(metric, new long[] {1, 2},
          new double[] {t, t % 2 == 0 ? 1.0 : 2.0});
    }
  }

  static final class Fixture implements AutoCloseable {
    final StatusComponentFixture components = new StatusComponentFixture();
    final Measurements measurements = new Measurements();
    final java.util.concurrent.atomic.AtomicLong clockMs =
        new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
    final TelemetryHealthState telemetry = new TelemetryHealthState();
    final AtomicReference<String> conversation = new AtomicReference<>("unlocked");
    final StatusLifecycleHandler handler;
    final Javalin app;

    Fixture() {
      var ks = mock(KnowledgeServerBootstrap.class);
      var kc = mock(KnowledgeClient.class);
      when(ks.hasClient()).thenReturn(true);
      BootstrapLeaseFixtures.bind(ks, kc);
      when(kc.getWorkerOperationalView(any())).thenAnswer(a -> measurements.worker());
      var gpu = mock(GpuCapabilitiesService.class);
      when(gpu.snapshot(any(GpuCapabilities.Cuda.class))).thenAnswer(a -> measurements.gpu());
      when(gpu.snapshot()).thenAnswer(a -> measurements.gpu());
      var rrd = mock(RrdMetricStore.class);
      when(rrd.query(any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
          .thenAnswer(a -> measurements.history(a.getArgument(0)));
      var caps = components.capabilities();
      handler = new StatusLifecycleHandler(mock(io.justsearch.app.api.OnlineAiService.class),
          mock(io.justsearch.agent.api.AgentService.class), () -> null, ks, null,
          java.nio.file.Path.of(System.getProperty("java.io.tmpdir")), Instant.now(),
          () -> "OK", null, null, () -> gpu, caps.worker(), caps.inference());
      handler.setRrdStoreSupplier(() -> rrd);
      handler.setClockForTesting(clockMs::get);
      handler.setTelemetryHealthSupplier(() -> telemetry);
      handler.setConversationProtectionSupplier(conversation::get);
      handler.setGpuMemoryReader(new GpuMemoryReader() {
        @Override public GpuMemorySample sampleNow() {
          return measurements.memory(Instant.ofEpochMilli(clockMs.get()));
        }
        @Override public void close() {}
      });
      components.attach(handler);
      handler.sampleAndBuildStatusSnapshot();
      app = Javalin.create();
      new ApiSecurityFilters(false, null, null, null, null, null,
          new io.justsearch.app.engine.EngineAdmissionController(4, 16, 1)).install(app);
      StatusChangeHeaders.install(app);
      io.justsearch.ui.api.routes.StatusRoutes.registerLifecycleRoutes(app, handler::handleStatus,
          handler::handleHealth);
      app.post("/api/own-failing-action", ctx -> ctx.status(422).json(Map.of(
          "error", "refused", "errorCode", "VALIDATION_FAILED")));
      app.start("127.0.0.1", 0);
    }

    /** Advance measurements and both cache windows (5 s NVML, 2 s memory) together. */
    void measurementsChange() {
      measurements.tick.incrementAndGet();
      telemetry.recordFlushFailure();
      telemetry.recordGaugeCallbackFailure();
      clockMs.addAndGet(5_300L);
      handler.sampleAndBuildStatusSnapshot();
    }

    @Override public void close() {
      app.stop();
      handler.closeGpuMemoryReader();
      components.close();
    }
  }

  record Read(int code, long change, long reflected, String body) {}

  Read read(Fixture f, String path) throws Exception {
    var r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + f.app.port() + path))
        .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
    return new Read(r.statusCode(),
        Long.parseLong(r.headers().firstValue("X-JustSearch-Status-Change").orElse("-1")),
        Long.parseLong(r.headers().firstValue("X-JustSearch-Status-Reflected").orElse("-1")), r.body());
  }

  Read failingAction(Fixture f) throws Exception {
    var r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + f.app.port()
            + "/api/own-failing-action")).timeout(Duration.ofSeconds(3))
        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    return new Read(r.statusCode(),
        Long.parseLong(r.headers().firstValue("X-JustSearch-Status-Change").orElse("-1")), -1L, r.body());
  }

  private static void assertMeasurements(Fixture f, Read read) {
    var body = new tools.jackson.databind.ObjectMapper().readTree(read.body());
    int tick = f.measurements.tick.get();
    assertEquals(3 * GIB + tick * 7_000_000L, body.at("/gpu/usedVramBytes").asLong(),
        "the NVML cache published the changed measurement through HTTP");
    assertEquals(9 * GIB - tick * 9_000_000L,
        body.at("/operating/gpu/adapterFreeBytes").asLong(),
        "the memory cache published the changed measurement through HTTP");
    assertEquals(10 + tick, body.at("/worker/core/indexedDocuments").asLong());
    assertEquals(100 + tick, body.at("/worker/telemetry/contentLengthAvgChars").asDouble());
  }

  /** Waits for the first GPU samples (unknown to known is a state change), then settles. */
  private Read settle(Fixture f) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!read(f, "/api/status?fresh=true").body().contains("\"source\":\"NVML\"")) {
      if (System.nanoTime() > deadline) fail("GPU memory sample never became known");
      Thread.sleep(50L);
    }
    for (int i = 0; i < 2; i++) {
      f.handler.sampleAndBuildStatusSnapshot();
      read(f, "/api/status");
      read(f, "/api/status?fresh=true");
    }
    return read(f, "/api/status?fresh=true");
  }

  /** OWN6-IDLE with measurements: GPU, VRAM, history arrays, telemetry window flapping, counts. */
  @Test @Timeout(90) void idleCounterIgnoresMeasurementChanges() throws Exception {
    try (var f = new Fixture()) {
      long settled = settle(f).change();
      var seen = new ArrayList<Long>();
      var bodies = new ArrayList<String>();
      for (int i = 0; i < 3; i++) {
        f.measurementsChange();
        var ordinary = read(f, "/api/status");
        assertMeasurements(f, ordinary);
        bodies.add(ordinary.body());
        seen.add(ordinary.change());
        var fresh = read(f, "/api/status?fresh=true");
        assertMeasurements(f, fresh);
        seen.add(fresh.change());
      }
      System.out.println("B73-IDLE-MEASUREMENTS settled=" + settled + " seen=" + seen);
      assertFalse(bodies.get(0).equals(bodies.get(1)), "the fixture must change measurements");
      assertTrue(bodies.get(0).contains("\"throughputWindowState\":\"HEALTHY\"")
          && bodies.get(1).contains("\"throughputWindowState\":\"UNKNOWN\""),
          "the telemetry window must flap HEALTHY/UNKNOWN as on an idle system");
      assertTrue(seen.stream().allMatch(v -> v == settled),
          "measurements advanced the change counter: settled=" + settled + " seen=" + seen);
      f.conversation.set("locked"); // a state change no owner publishes: the backstop must
      f.handler.sampleAndBuildStatusSnapshot();
      var locked = read(f, "/api/status?fresh=true");
      assertTrue(locked.body().contains("\"locked\""));
      assertTrue(locked.change() > settled, "a state change must still advance the counter");
    }
  }

  /** A failed action whose only difference from the displayed body is measurements and clocks. */
  @Test @Timeout(90) void failedActionWithMeasurementOnlyDiffCarriesNoNewerCounter() throws Exception {
    try (var f = new Fixture()) {
      var displayed = settle(f);
      assertTrue(displayed.reflected() >= displayed.change(), "settled display reflects its counter");
      f.measurementsChange();
      read(f, "/api/status");
      var failed = failingAction(f);
      var canonical = read(f, "/api/status?fresh=true");
      assertMeasurements(f, canonical);
      System.out.println("B73-FAILED-422 displayedReflected=" + displayed.reflected() + " failedChange="
          + failed.change() + " canonicalChange=" + canonical.change());
      assertEquals(422, failed.code());
      assertFalse(displayed.body().equals(canonical.body()), "measurements must have changed");
      assertTrue(failed.change() <= displayed.reflected(),
          "a failed action with only measurement differences carries a counter above the displayed"
              + " reflection, so the UI would make a fresh read and replace its display");
    }
  }
}
