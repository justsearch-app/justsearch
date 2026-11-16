/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.app.api.AiRuntimeStatusResponse;
import io.justsearch.app.services.ai.preflight.AiPreflightService;
import io.justsearch.app.services.ai.runtime.RuntimeActivationService;
import io.justsearch.app.services.gpu.GpuCapabilityResolver;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.NativeTargets;
import io.justsearch.configuration.model.*;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.gpu.GpuCapabilities;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Slice 7b: the install statuses the AI Brain page renders, published by the real install service,
 * transport (curl), resume records and placement against a loopback server — no internet.
 *
 * <p>Each scenario records the status JSON the Engine publishes ({@code GET /api/ai/install/status}),
 * the model presence ({@code GET /api/ai/models/status}) and the plan preview where the page reads
 * it. The page tests in {@code modules/ui-web/src/shell-v0/views/brain-install/} render exactly
 * these shapes from {@code __fixtures__/engine-install-statuses.json}; every run compares the
 * shapes it records with that file (states, phases, package and stage states, pause and error
 * codes), so a change in what the Engine publishes fails here first. With the environment variable
 * {@code JUSTSEARCH_INSTALL_FIXTURE_OUT} naming a file, the recorded shapes are written there.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(240)
final class InstallFlowStatusLoopbackTest {
  /** Server behaviour per file. */
  enum Mode { NORMAL, GATE, DROP_THEN_REFUSE_RANGE, CORRUPT }

  private static final List<String> PARTS = List.of("embedding", "splade", "reranker", "ner", "citation-scorer");
  private static final Path FIXTURE = Path.of(System.getProperty("user.dir"))
      .resolve("../ui-web/src/shell-v0/views/brain-install/__fixtures__/engine-install-statuses.json").normalize();
  private static final Map<String, ObjectNode> RECORDED = new ConcurrentHashMap<>();
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path home;
  private HttpServer server;
  private ExecutorService handlers;
  private final Map<String, byte[]> files = new LinkedHashMap<>();
  private final Map<String, Mode> modes = new ConcurrentHashMap<>();
  private final List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
  private volatile CountDownLatch gateEntered = new CountDownLatch(1);
  private volatile CountDownLatch gateRelease = new CountDownLatch(1);
  private String base;
  private ModelRegistry registry;
  private MockedConstruction<GpuCapabilityResolver> gpu;
  private MockedStatic<NativeTargets> target;
  private ConfigStore previousConfig;
  private ConfigStore config;
  private UiSettingsStore settings;

  @BeforeEach void start() throws Exception {
    handlers = Executors.newVirtualThreadPerTaskExecutor();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(handlers);
    server.createContext("/f/", this::serve);
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort() + "/f/";
    var shipped = ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
    Random random = new Random(7);
    List<ModelPackage> packages = new ArrayList<>();
    for (String id : PARTS) {
      ModelPackage real = shipped.findPackage(id);
      // Two files and more for every part: a part is done only after its last file.
      byte[] model = new byte[160 * 1024 + PARTS.indexOf(id) * 8192];
      random.nextBytes(model);
      byte[] tokenizer = ("{\"tokenizer\":\"" + id + "\"}").repeat(400).getBytes(StandardCharsets.UTF_8);
      byte[] cfg = ("{\"part\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8);
      files.put(id + "/model.onnx", model);
      files.put(id + "/tokenizer.json", tokenizer);
      files.put(id + "/config.json", cfg);
      packages.add(new ModelPackage(real.id(), real.label(), real.description(), real.targetDir(),
          List.of(new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
              sha(model), model.length, base + id + "/model.onnx")),
          List.of(new SupportingFile("tokenizer.json", sha(tokenizer), tokenizer.length, base + id + "/tokenizer.json"),
              new SupportingFile("config.json", sha(cfg), cfg.length, base + id + "/config.json")),
          0, real.termsUrl(), real.installRoot(), real.license(), real.tier(), false, false,
          real.necessity(), real.dependsOn()));
    }
    packages.add(shipped.findPackage("chat"));
    packages.add(shipped.findPackage("cuda-runtime"));
    registry = new ModelRegistry(2, "install flow loopback", packages);
    var effective = new GpuCapabilities.Effective(false, "fixture", GpuCapabilities.Confidence.HIGH,
        null, null, null, 1, -1L, -1L, 0L, new GpuCapabilities.Cuda(false, "fixture", GpuCapabilities.Confidence.HIGH));
    gpu = mockConstruction(GpuCapabilityResolver.class,
        (mock, context) -> when(mock.snapshot()).thenReturn(new GpuCapabilities(null, null, effective)));
    NativeTargets linux = NativeTargets.forTarget("linux-x86_64");
    target = mockStatic(NativeTargets.class, CALLS_REAL_METHODS);
    target.when(NativeTargets::current).thenReturn(linux);
    settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, home.resolve("settings.json"));
    previousConfig = ConfigStore.globalOrNull();
    config = new ConfigStore(io.justsearch.app.services.config.ConfigStoreRebuilder.prepare(settings.load()));
    ConfigStore.setGlobal(config);
  }

  @AfterEach void stop() throws Exception {
    gateRelease.countDown();
    if (config != null) ConfigStore.restoreGlobal(config, previousConfig);
    if (target != null) target.close();
    if (gpu != null) gpu.close();
    if (server != null) server.stop(0);
    if (handlers != null) {
      handlers.shutdownNow();
      assertTrue(handlers.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @AfterAll static void compareOrWrite() throws Exception {
    if (RECORDED.isEmpty()) return;
    String out = System.getenv("JUSTSEARCH_INSTALL_FIXTURE_OUT");
    if (out != null && !out.isBlank()) {
      // Scenarios not run this time keep their recorded shapes.
      ObjectNode all = JSON.createObjectNode();
      if (Files.exists(Path.of(out))) {
        JsonNode earlier = JSON.readTree(Files.readString(Path.of(out)));
        earlier.properties().forEach(e -> all.set(e.getKey(), e.getValue()));
      }
      RECORDED.forEach(all::set);
      ObjectNode sorted = JSON.createObjectNode();
      all.properties().stream().map(java.util.Map.Entry::getKey).sorted().forEach(key -> sorted.set(key, all.get(key)));
      Files.writeString(Path.of(out), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(sorted) + "\n");
      return;
    }
    if (!Files.exists(FIXTURE)) return; // the module is tested without the ui-web tree
    JsonNode committed = JSON.readTree(Files.readString(FIXTURE));
    for (String key : RECORDED.keySet()) {
      assertTrue(committed.has(key), "the page fixtures lack " + key);
      assertEquals(skeleton(committed.get(key)), skeleton(RECORDED.get(key)),
          key + ": the Engine now publishes another shape than the page tests render; record them again");
    }
  }

  /** Multi-file parts, pause while a file finishes, the pause between files, resume, completion. */
  @Test void pauseHoldsBetweenFilesAndAPartIsDoneOnlyAfterItsLastFile() throws Exception {
    modes.put("splade/model.onnx", Mode.GATE);
    var service = service();
    record("firstRunPreview", null, null, service.previewInstallPlan());
    var attempt = service.startInstall(true);
    assertTrue(gateEntered.await(30, TimeUnit.SECONDS), "the second part's model file was requested");
    AiInstallStatus running = await(service, s -> "downloading".equals(pkg(s, "splade").state)
        && "installed".equals(pkg(s, "embedding").state));
    assertEquals("running", running.state);
    assertEquals("download", running.phase);
    record("running", running, presence(service), null);

    service.pauseInstall();
    AiInstallStatus finishing = service.getStatus();
    // The Engine says paused at once, while the file in flight keeps downloading.
    assertTrue(finishing.paused);
    assertEquals("running", finishing.state);
    assertEquals("downloading", pkg(finishing, "splade").state);
    record("pausedFileFinishing", finishing, presence(service), null);

    gateRelease.countDown();
    AiInstallStatus between = await(service, s -> "installed".equals(pkg(s, "splade").state));
    Thread.sleep(300); // the run now waits at the pause gate before the part's next file
    between = service.getStatus();
    assertTrue(between.paused);
    var splade = pkg(between, "splade");
    // The per-file "installed" between two files of one part: bytes and files say it is not done.
    assertEquals("installed", splade.state);
    assertTrue(splade.bytesDownloaded < splade.bytesTotal, splade.bytesDownloaded + " of " + splade.bytesTotal);
    var presenceBetween = presence(service);
    assertFalse(part(presenceBetween, "splade").complete(), "only the model file of splade is placed");
    assertTrue(part(presenceBetween, "embedding").complete());
    record("pausedBetweenFiles", between, presenceBetween, null);
    assertFalse(requests.contains("splade/tokenizer.json"), "a paused run starts no next file");

    service.resumeInstall();
    AiInstallStatus done = attempt.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
    assertEquals("completed", done.state, done.errorCode + ": " + done.lastError);
    var presenceDone = presence(service);
    for (String id : PARTS) assertTrue(part(presenceDone, id).complete(), id);
    record("completed", service.getStatus(), presenceDone, service.previewInstallPlan());
  }

  /** Cancel while the first file downloads: nothing placed; then the same disk after a restart. */
  @Test void cancelWithNothingPlacedLeavesNoPart() throws Exception {
    modes.put("embedding/model.onnx", Mode.GATE);
    var service = service();
    var attempt = service.startInstall(true);
    assertTrue(gateEntered.await(30, TimeUnit.SECONDS));
    await(service, s -> "downloading".equals(pkg(s, "embedding").state));
    assertTrue(service.cancel());
    gateRelease.countDown();
    AiInstallStatus cancelled = attempt.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
    assertEquals("cancelled", cancelled.state);
    var presenceAfter = presence(service);
    for (String id : PARTS) {
      assertFalse(part(presenceAfter, id).complete(), id);
      assertTrue(part(presenceAfter, id).presentVariantFiles().isEmpty(), id);
    }
    record("cancelledNothingPlaced", service.getStatus(), presenceAfter, service.previewInstallPlan());
    var restarted = service();
    AiInstallStatus afterRestart = restarted.getStatus();
    assertEquals("idle", afterRestart.state);
    assertFalse(afterRestart.installedFully);
    record("restartAfterCancelNothingPlaced", afterRestart, presence(restarted), restarted.previewInstallPlan());
  }

  /** Cancel after the first part is placed: the placed part stays, the run reports cancelled. */
  @Test void cancelKeepsPlacedParts() throws Exception {
    modes.put("splade/model.onnx", Mode.GATE);
    var service = service();
    var attempt = service.startInstall(true);
    assertTrue(gateEntered.await(30, TimeUnit.SECONDS));
    await(service, s -> "downloading".equals(pkg(s, "splade").state));
    assertTrue(service.cancel());
    gateRelease.countDown();
    AiInstallStatus cancelled = attempt.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
    assertEquals("cancelled", cancelled.state);
    var presenceAfter = presence(service);
    assertTrue(part(presenceAfter, "embedding").complete());
    assertFalse(part(presenceAfter, "splade").complete());
    record("cancelledPartsPlaced", service.getStatus(), presenceAfter, service.previewInstallPlan());
    var restarted = service();
    record("restartAfterCancelPartsPlaced", restarted.getStatus(), presence(restarted), restarted.previewInstallPlan());
  }

  /**
   * A dropped connection whose resume also fails: the transport retries, the part fails, later parts
   * continue, the run fails with the placed parts on disk. Then a new run covers only what is missing.
   */
  @Test void droppedConnectionAndFailedResumeFailTheRunThenANewRunCoversOnlyWhatIsMissing() throws Exception {
    modes.put("splade/model.onnx", Mode.DROP_THEN_REFUSE_RANGE);
    var service = service();
    long firstTotal = service.previewInstallPlan().totalDownloadBytes;
    var attempt = service.startInstall(true);
    AiInstallStatus retrying = await(service, s -> s.message != null && s.message.contains("(attempt 2 of"));
    record("runningRetrying", retrying, presence(service), null);
    AiInstallStatus failed = attempt.completion().toCompletableFuture().get(150, TimeUnit.SECONDS);
    assertEquals("failed", failed.state, failed.message);
    assertEquals("failed", pkg(failed, "splade").state);
    assertEquals("installed", pkg(failed, "embedding").state);
    assertEquals("installed", pkg(failed, "citation-scorer").state, "later parts continue after a failed one");
    assertTrue(requests.stream().anyMatch(r -> r.startsWith("splade/model.onnx bytes=")), requests.toString());
    var presenceAfter = presence(service);
    assertTrue(part(presenceAfter, "embedding").complete());
    assertFalse(part(presenceAfter, "splade").complete());
    var previewAfter = service.previewInstallPlan();
    record("failedPartsMissing", service.getStatus(), presenceAfter, previewAfter);
    var restarted = service();
    AiInstallStatus afterRestart = restarted.getStatus();
    assertEquals("idle", afterRestart.state);
    assertFalse(afterRestart.installedFully);
    record("restartAfterFailed", afterRestart, presence(restarted), restarted.previewInstallPlan());

    modes.clear();
    gateEntered = new CountDownLatch(1);
    gateRelease = new CountDownLatch(1);
    // The failed run placed the part's small files; only its model file is still missing.
    modes.put("splade/model.onnx", Mode.GATE);
    var retry = restarted.startInstall(true);
    assertTrue(gateEntered.await(30, TimeUnit.SECONDS));
    AiInstallStatus retryRunning = await(restarted, s -> "downloading".equals(pkg(s, "splade").state));
    // Try again: the new run's total covers only the missing part.
    assertTrue(retryRunning.totalBytes < firstTotal, retryRunning.totalBytes + " < " + firstTotal);
    assertEquals("installed", pkg(retryRunning, "embedding").state);
    record("retryRunning", retryRunning, presence(restarted), null);
    gateRelease.countDown();
    AiInstallStatus retried = retry.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
    assertEquals("completed", retried.state, retried.errorCode + ": " + retried.lastError);
  }

  /** Every file arrives and fails verification: a failed run with nothing placed. */
  @Test void failureAfterEveryFileArrivedLeavesNothingPlaced() throws Exception {
    for (String file : files.keySet()) modes.put(file, Mode.CORRUPT);
    var service = service();
    AiInstallStatus failed = service.startInstall(true).completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
    assertEquals("failed", failed.state);
    for (String id : PARTS) assertEquals("failed", pkg(failed, id).state, id);
    var presenceAfter = presence(service);
    for (String id : PARTS) assertTrue(part(presenceAfter, id).presentVariantFiles().isEmpty(), id);
    record("failedNothingPlaced", service.getStatus(), presenceAfter, service.previewInstallPlan());
    var restarted = service();
    record("restartAfterFailedNothingPlaced", restarted.getStatus(), presence(restarted), restarted.previewInstallPlan());
  }

  // ---------- fixture plumbing ----------

  private AiInstallService service() {
    var service = spy(new AiInstallService(null, settings, null, null, home));
    doReturn(registry).when(service).getManifest();
    doReturn(HardwareProfile.cpuOnly()).when(service).detectHardwareProfile();
    service.setOperationLeaseService(new io.justsearch.app.services.lease.RecordingOperationLeaseService());
    return service;
  }

  private static AiPreflightService.AiPreflightResult presence(AiInstallService service) {
    var runtime = mock(RuntimeActivationService.class);
    when(runtime.getStatus()).thenReturn(new AiRuntimeStatusResponse(null, List.of(), null, List.of()));
    return new AiPreflightService(service, runtime).getPreflight();
  }

  private static AiPreflightService.PackageStatus part(AiPreflightService.AiPreflightResult presence, String id) {
    return presence.packages().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
  }

  private static AiInstallStatus.PackageStatus pkg(AiInstallStatus status, String id) {
    return status.packages.stream().filter(p -> id.equals(p.packageId)).findFirst()
        .orElse(new AiInstallStatus.PackageStatus());
  }

  private static AiInstallStatus await(AiInstallService service, Predicate<AiInstallStatus> condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
    AiInstallStatus last = service.getStatus();
    while (!condition.test(last)) {
      assertTrue(System.nanoTime() < deadline, "status never reached the awaited shape: " + last.state
          + " " + last.phase + " " + last.message);
      Thread.sleep(20);
      last = service.getStatus();
    }
    return last;
  }

  private void record(String key, AiInstallStatus status, Object presence, Object preview) {
    ObjectNode node = JSON.createObjectNode();
    if (status != null) node.set("status", local(JSON.valueToTree(status)));
    if (presence != null) node.set("presence", JSON.valueToTree(presence));
    if (preview != null) node.set("preview", local(JSON.valueToTree(preview)));
    RECORDED.put(key, node);
  }

  /** The loopback port changes per run; the page never reads it. */
  private JsonNode local(JsonNode node) {
    String text = node.toString().replace(base, "http://127.0.0.1:0/f/")
        .replace(home.toString(), "/home/fixture");
    return JSON.readTree(text);
  }

  /** What the page decides on: states, phases, pause, error codes, per part and per stage; no timings or byte counts. */
  private static String skeleton(JsonNode recorded) {
    StringBuilder out = new StringBuilder();
    JsonNode status = recorded.path("status");
    if (!status.isMissingNode()) {
      out.append("state=").append(status.path("state").asString()).append(" phase=").append(status.path("phase").asString())
          .append(" paused=").append(status.path("paused").asBoolean()).append(" error=").append(status.path("errorCode").asString())
          .append(" installedFully=").append(status.path("installedFully").asBoolean())
          .append(" repairNeeded=").append(status.path("repairNeeded").asBoolean()).append('\n');
      for (JsonNode p : status.path("packages")) {
        out.append(" pkg ").append(p.path("packageId").asString()).append('=').append(p.path("state").asString())
            .append(" stage=").append(p.path("stage").asString()).append('\n');
      }
      for (JsonNode s : status.path("stages")) {
        out.append(" stage ").append(s.path("stage").asString()).append('=').append(s.path("state").asString()).append('\n');
      }
    }
    for (JsonNode p : recorded.path("presence").path("packages")) {
      out.append(" presence ").append(p.path("id").asString()).append(" complete=").append(p.path("complete").asBoolean())
          .append(" variants=").append(p.path("presentVariantFiles")).append('\n');
    }
    for (JsonNode c : recorded.path("preview").path("components")) {
      out.append(" component ").append(c.path("id").asString()).append('=').append(c.path("state").asString()).append('\n');
    }
    return out.toString();
  }

  private void serve(HttpExchange exchange) throws IOException {
    String file = exchange.getRequestURI().getPath().substring("/f/".length());
    String range = exchange.getRequestHeaders().getFirst("Range");
    requests.add(range == null ? file : file + " " + range);
    byte[] bytes = files.get(file);
    Mode mode = modes.getOrDefault(file, Mode.NORMAL);
    try (exchange) {
      if (bytes == null) {
        exchange.sendResponseHeaders(404, -1);
        return;
      }
      int offset = range == null ? 0 : Integer.parseInt(range.substring("bytes=".length(), range.length() - 1));
      if (offset > 0) {
        exchange.getResponseHeaders().set("Content-Range", "bytes " + offset + "-" + (bytes.length - 1) + "/" + bytes.length);
      }
      exchange.sendResponseHeaders(offset == 0 ? 200 : 206, bytes.length - offset);
      byte[] content = bytes.clone();
      if (mode == Mode.CORRUPT) content[content.length - 1] ^= 1;
      // A dropped connection: a third of the file, then the connection closes; a resume gets nothing.
      int end = mode == Mode.DROP_THEN_REFUSE_RANGE ? (offset > 0 ? offset : bytes.length / 3) : bytes.length;
      int half = offset + (end - offset) / 2;
      for (int cursor = offset; cursor < end; cursor += 8192) {
        if (mode == Mode.GATE && cursor >= half && gateEntered.getCount() > 0) {
          exchange.getResponseBody().flush();
          gateEntered.countDown();
          try {
            if (!gateRelease.await(60, TimeUnit.SECONDS)) return;
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
          }
        }
        exchange.getResponseBody().write(content, cursor, Math.min(8192, end - cursor));
        exchange.getResponseBody().flush();
      }
    } catch (IOException peerClosed) {
      // Cancellation and dropped connections close the response early.
    }
  }

  private static String sha(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
