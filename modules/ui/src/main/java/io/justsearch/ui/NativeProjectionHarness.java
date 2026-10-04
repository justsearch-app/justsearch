/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.app.api.indexing.ProjectionDurability;
import io.justsearch.app.api.indexing.ProjectionReceipt;
import io.justsearch.app.api.indexing.ProjectionSeedSource;
import io.justsearch.app.engine.EngineRoot;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.harness.HarnessBarrierProtocol;
import io.justsearch.ipc.PipelineConfigs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Private installed proof, composed only in the actual supervisor-harness Head. */
final class NativeProjectionHarness implements ProjectionSeedSource {
  static final String SCENARIO = "native-mixed-after-pointer";
  static final String SOURCE = "native-supervised-fixture";
  static final String ACTION_FAMILY = "native-projection-actions";
  private static final String RETAINED_CONTENT = "retainedbodyq7x91";
  private static final String LATE_CONTENT = "latebodyk9z83";
  private static final String CUT_POINT = "migration-after-pointer-commit";
  private static final int MAX_INPUT_BYTES = 64 * 1024;
  private static final long WAIT_SECONDS = 180;
  private static final Logger LOG = LoggerFactory.getLogger(NativeProjectionHarness.class);
  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  private final Path data;
  private final Path input;
  private final Fixture fixture;
  private final boolean successor;
  private final CountDownLatch sourceEntered = new CountDownLatch(1);
  private final CountDownLatch sourceReleased = new CountDownLatch(1);
  private final AtomicBoolean held = new AtomicBoolean();
  private final AtomicBoolean driven = new AtomicBoolean();

  private record Fixture(String phase, List<AcceptedProjection> initial,
      List<AcceptedProjection> finalRows) {}

  private NativeProjectionHarness(Path data) throws IOException {
    this.data = data.toAbsolutePath().normalize();
    input = this.data.resolve("fixtures").resolve("native-projection-source.json");
    fixture = readFixture(input);
    successor = verifyPriorCut();
    if (!fixture.phase().equals(successor ? "final" : "initial")) {
      throw new IOException("Native source phase does not match the supervised incarnation");
    }
    requireScope(fixture.finalRows().get(1), deletedPrefix().resolve("late-projection.md"),
        "deleted-files");
    requireScope(fixture.initial().getFirst(), work().resolve("retained-projection.md"),
        "keep-collection");
  }

  static NativeProjectionHarness fromEnvironment(Path data, Function<String, String> env) {
    if (!SCENARIO.equals(env.apply("JUSTSEARCH_REAL_RECOVERY_SCENARIO"))) return null;
    if (!"1".equals(env.apply("JUSTSEARCH_SUPERVISOR_HARNESS"))
        || !CUT_POINT.equals(env.apply("JUSTSEARCH_MIGRATION_BARRIER_POINT"))
        || !"1".equals(env.apply("JUSTSEARCH_MIGRATION_BARRIER_SELF_EXIT"))) {
      throw new IllegalArgumentException("Native projection proof requires its exact supervised self-exit cut");
    }
    try {
      return new NativeProjectionHarness(data);
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Native projection fixture is invalid", invalid);
    }
  }

  void registerBeforeStart(EngineRoot root) { root.registerProjectionSeedSource(this); }

  boolean automaticRootProducersEnabled() { return successor; }

  @Override public String sourceId() { return SOURCE; }

  @Override public void enumerate(Consumer<AcceptedProjection> sink) throws IOException {
    Fixture current = readFixture(input);
    requireSameRows(current);
    boolean firstSnapshot = !successor && held.compareAndSet(false, true);
    if (firstSnapshot && !current.phase().equals("initial")) {
      throw new IOException("Initial source snapshot changed before capture");
    }
    List<AcceptedProjection> snapshot = current.phase().equals("initial")
        ? current.initial() : current.finalRows();
    snapshot.forEach(sink);
    if (!successor) {
      if (firstSnapshot) sourceEntered.countDown();
      try {
        if (!sourceReleased.await(600, TimeUnit.SECONDS)) throw new IOException("Native source was not released");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("Native source enumeration was interrupted", interrupted);
      }
    }
  }

  /** Schedules owned work; a slow initial bootstrap must publish readiness before any capture. */
  void startAfterBootstrap(EngineRoot root, KnowledgeServerBootstrap bootstrap) {
    if (successor) return;
    if (bootstrap == null || !driven.compareAndSet(false, true)) {
      throw new IllegalStateException("Native projection driver requires one connected bootstrap");
    }
    var limits = root.executors().limits(EngineExecutorSpec.Kind.BACKGROUND);
    var owner = root.executors().register(new EngineExecutorSpec(
        "harness.native-projection", EngineExecutorSpec.Kind.BACKGROUND,
        EngineExecutorSpec.Mode.PLATFORM, 1, limits.maxQueue(), 1));
    try {
      var executor = owner.open(Thread.ofPlatform().daemon().name("native-projection-harness-", 0).factory());
      executor.execute(() -> {
        try {
          drive(root, bootstrap);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          LOG.error("NATIVE_PROJECTION_HARNESS_FAILED: interrupted", interrupted);
        } catch (IOException | RuntimeException failure) {
          LOG.error("NATIVE_PROJECTION_HARNESS_FAILED", failure);
        } finally {
          owner.close();
        }
      });
    } catch (RuntimeException rejected) {
      owner.close();
      throw rejected;
    }
  }

  private void drive(EngineRoot root, KnowledgeServerBootstrap bootstrap)
      throws IOException, InterruptedException {
    Thread actor = Thread.currentThread();
    var provenance = EngineProvenance.internal("native-projection-harness",
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);
    try (var workHandle = root.admission().admit(provenance, false);
        var ignored = workHandle.onCancel(reason -> actor.interrupt())) {
      EngineContext context = workHandle.context();
      awaitVerifiedBootstrap(bootstrap);
      AcceptedProjection retained = fixture.initial().getFirst();
      ProjectionReceipt firstReceipt = call(bootstrap,
          client -> client.indexAndReturn(retained, ProjectionDurability.NRT, context));
      requireNrt(firstReceipt, retained, firstReceipt.generationId());
      List<Path> prefixFiles = List.of(deletedPrefix().resolve("prefix.txt"),
          deletedPrefix().resolve("dual.txt"), prefixRoot().resolve("retained.txt"));
      Path collectionFile = collectionRoot().resolve("collection.txt");
      require(call(bootstrap, client -> client.submitBatch(prefixFiles, false, "keep-collection", context))
          .getAcceptedCount() == 3, "Initial prefix files were not accepted");
      require(call(bootstrap, client -> client.submitBatch(List.of(collectionFile), false, "deleted-files", context))
          .getAcceptedCount() == 1, "Initial collection file was not accepted");
      for (int index = 0; index < prefixFiles.size(); index++) {
        Path file = prefixFiles.get(index);
        String marker = List.of("native-prefix-victim", "native-prefix-and-collection-victim",
            "native-retained-file").get(index);
        await("initial file " + file, () -> hasHit(bootstrap, context, marker, normalized(file), false));
      }
      await("initial collection file", () -> hasHit(bootstrap, context,
          "native-collection-victim", normalized(collectionFile), false));
      await("initial retained projection", () -> hasHit(bootstrap, context,
          RETAINED_CONTENT, retained.indexId(), false));
      await("initial real model query", () -> hasHit(bootstrap, context,
          "native-retained-file", normalized(prefixRoot().resolve("retained.txt")), true));
      var migration = call(bootstrap, client -> client.startMigration("installed native mixed receipt proof", context));
      require(migration.accepted() && !migration.restartRequired()
          && firstReceipt.generationId().equals(migration.activeGenerationId())
          && !migration.buildingGenerationId().isBlank(), "Native migration did not start in place");
      if (!sourceEntered.await(WAIT_SECONDS, TimeUnit.SECONDS)) throw new IOException("Native source never entered");
      await("four physical B file parents", () -> {
        var status = call(bootstrap, client -> client.getStatus(context)).getMigration();
        return migration.buildingGenerationId().equals(status.getServingIngestGenerationId())
            && "MIGRATING".equals(status.getMigrationState())
            && status.getBuildingDocCount() == 4 && status.getPendingJobsCount() == 0
            && status.getProcessingJobsCount() == 0;
      });
      require(call(bootstrap, client -> client.deleteDocsByPathPrefix(deletedPrefix(), context)) >= 2,
          "Prefix deletion did not remove its actual A files");
      require(call(bootstrap, client -> client.deleteDocsByCollection("deleted-files", context)) >= 1,
          "Collection deletion did not remove its actual A file");
      AcceptedProjection late = fixture.finalRows().get(1);
      ProjectionReceipt lateReceipt = call(bootstrap,
          client -> client.indexAndReturn(late, ProjectionDurability.NRT, context));
      requireNrt(lateReceipt, late, migration.activeGenerationId());
      await("later A projection", () -> hasHit(bootstrap, context,
          LATE_CONTENT, late.indexId(), false));
      var marker = Map.of("scenario", SCENARIO, "pid", ProcessHandle.current().pid(),
          "sourceGeneration", migration.activeGenerationId(),
          "buildingGeneration", migration.buildingGenerationId(), "lateReceipt", lateReceipt);
      HarnessBarrierProtocol.await(data, ACTION_FAMILY, JSON.writeValueAsString(marker), false, 600);
      Fixture finalInput = readFixture(input);
      requireSameRows(finalInput);
      require("final".equals(finalInput.phase()), "Runner did not persist the final complete source");
    }
    // The actor's admission is gone before normal enumeration return permits the final fence.
    sourceReleased.countDown();
  }

  private static void requireNrt(ProjectionReceipt receipt, AcceptedProjection projection, String generation) {
    require(receipt.visibility() == ProjectionReceipt.Visibility.NRT
        && receipt.sourceId().equals(projection.sourceId()) && receipt.documentId().equals(projection.documentId())
        && receipt.sourceRevision() == projection.sourceRevision() && receipt.generationId().equals(generation),
        "Projection receipt did not witness its exact NRT effect");
  }

  private static boolean hasHit(KnowledgeServerBootstrap bootstrap, EngineContext context,
      String query, String id, boolean vector) {
    var result = call(bootstrap, client -> client.search(query, 20,
        vector ? PipelineConfigs.VECTOR : PipelineConfigs.TEXT, context));
    var trace = result.getSearchTrace();
    if (!result.hasSearchTrace() || trace.getDecisionKind().isBlank()
        || "blocked".equals(trace.getDecisionKind()) || "empty_query".equals(trace.getDecisionKind())
        || (vector && !"VECTOR".equals(trace.getEffectiveMode()))) return false;
    return result.getResultsList().stream().anyMatch(hit -> id.equals(hit.getId()));
  }

  private static <T> T call(KnowledgeServerBootstrap bootstrap, Function<KnowledgeClient, T> action) {
    try (var lease = bootstrap.captureClient()) { return lease.withClient(action); }
  }

  static void awaitVerifiedBootstrap(KnowledgeServerBootstrap bootstrap)
      throws IOException, InterruptedException {
    await("verified bootstrap", bootstrap::isReady);
  }

  private static void await(String label, BooleanSupplier condition) throws IOException, InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) throw new IOException("Native proof timed out: " + label);
      Thread.sleep(100);
    }
  }

  private boolean verifyPriorCut() throws IOException {
    Path cut = HarnessBarrierProtocol.reached(data, "migration-barrier");
    if (!Files.exists(cut)) return false;
    JsonNode marker = readBounded(cut);
    JsonNode actions = readBounded(HarnessBarrierProtocol.reached(data, ACTION_FAMILY));
    JsonNode receipt = actions.path("lateReceipt");
    AcceptedProjection late = fixture.finalRows().get(1);
    if (!CUT_POINT.equals(marker.path("point").asText())
        || !SCENARIO.equals(actions.path("scenario").asText())
        || !marker.path("pid").isIntegralNumber() || marker.path("pid").asLong() <= 0
        || marker.path("pid").asLong() == ProcessHandle.current().pid()
        || !actions.path("pid").isIntegralNumber()
        || marker.path("pid").asLong() != actions.path("pid").asLong()
        || !actions.path("sourceGeneration").isString() || actions.path("sourceGeneration").asText().isBlank()
        || !actions.path("buildingGeneration").isString() || actions.path("buildingGeneration").asText().isBlank()
        || actions.path("sourceGeneration").equals(actions.path("buildingGeneration"))
        // The existing transition barrier observes persisted state AFTER promotion.
        || !marker.path("sourceGeneration").equals(actions.path("buildingGeneration"))
        || !marker.path("buildingGeneration").isString() || !marker.path("buildingGeneration").asText().isEmpty()
        || !SOURCE.equals(receipt.path("sourceId").asText())
        || !late.documentId().equals(receipt.path("documentId").asText())
        || !receipt.path("sourceRevision").isIntegralNumber()
        || receipt.path("sourceRevision").asLong() != late.sourceRevision()
        || !receipt.path("generationId").equals(actions.path("sourceGeneration"))
        || !"NRT".equals(receipt.path("visibility").asText())) {
      throw new IOException("Native prior cut does not match its action owner");
    }
    return true;
  }

  private static Fixture readFixture(Path input) throws IOException {
    JsonNode root = readBounded(input);
    if (!keys(root).equals(Set.of("version", "source_id", "phase", "initial", "final"))
        || !root.path("version").isIntegralNumber() || root.path("version").asInt() != 1
        || !SOURCE.equals(root.path("source_id").asText())
        || !Set.of("initial", "final").contains(root.path("phase").asText())) {
      throw new IOException("Native source fixture schema is invalid");
    }
    List<AcceptedProjection> initial = projections(root.path("initial"));
    List<AcceptedProjection> finalRows = projections(root.path("final"));
    if (initial.size() != 1 || finalRows.size() != 2
        || !"retained".equals(initial.getFirst().documentId())
        || !initial.getFirst().sameEffect(finalRows.getFirst())
        || !"late".equals(finalRows.get(1).documentId())
        || finalRows.get(1).sourceRevision() <= initial.getFirst().sourceRevision()) {
      throw new IOException("Native source fixture revisions are inconsistent");
    }
    return new Fixture(root.path("phase").asText(), initial, finalRows);
  }

  private static List<AcceptedProjection> projections(JsonNode array) throws IOException {
    if (!array.isArray() || array.size() > 2) throw new IOException("Native source is not a bounded complete set");
    List<AcceptedProjection> rows = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (JsonNode node : array) {
      if (!keys(node).equals(Set.of("version", "source_id", "document_id", "source_revision", "kind", "fields"))) {
        throw new IOException("Native projection schema is invalid");
      }
      try {
        var row = AcceptedProjection.decode(JSON.writeValueAsString(node));
        if (!SOURCE.equals(row.sourceId()) || row.kind() != AcceptedProjection.Kind.UPSERT
            || row.sourceRevision() <= 0 || !ids.add(row.documentId())) {
          throw new IOException("Native source identity or revision is invalid");
        }
        rows.add(row);
      } catch (IllegalArgumentException invalid) {
        throw new IOException("Native source projection is invalid", invalid);
      }
    }
    return List.copyOf(rows);
  }

  private static JsonNode readBounded(Path file) throws IOException {
    byte[] bytes;
    try (var stream = Files.newInputStream(file)) { bytes = stream.readNBytes(MAX_INPUT_BYTES + 1); }
    if (bytes.length > MAX_INPUT_BYTES) throw new IOException("Native fixture exceeds its bounded input");
    try { return JSON.readTree(bytes); }
    catch (RuntimeException invalid) { throw new IOException("Native fixture JSON is invalid", invalid); }
  }

  private void requireSameRows(Fixture current) throws IOException {
    if (!fixture.initial().equals(current.initial()) || !fixture.finalRows().equals(current.finalRows())) {
      throw new IOException("Native source changed its accepted projection revisions");
    }
  }

  private static Set<String> keys(JsonNode node) {
    if (node == null || !node.isObject()) return Set.of();
    Set<String> keys = new HashSet<>();
    node.properties().forEach(entry -> keys.add(entry.getKey()));
    return keys;
  }

  private static void requireScope(AcceptedProjection row, Path path, String collection) throws IOException {
    JsonNode fields = JSON.readTree(row.fieldsJson());
    if (!keys(fields).equals(Set.of("title", "content", "path", "collection"))
        || fields.properties().stream().anyMatch(entry -> !entry.getValue().isString())
        || !normalized(path).equals(fields.path("path").asText())
        || !collection.equals(fields.path("collection").asText())
        || fields.path("title").asText().isBlank()
        || !("retained".equals(row.documentId()) ? RETAINED_CONTENT : LATE_CONTENT)
            .equals(fields.path("content").asText())) {
      throw new IOException("Native source fields do not match the fixture scope");
    }
  }

  private static String normalized(Path path) {
    String key = path.toAbsolutePath().normalize().toString();
    return PlatformPaths.isWindows() ? key.toLowerCase(Locale.ROOT) : key;
  }

  private Path work() { return data.getParent(); }
  private Path prefixRoot() { return work().resolve("native-prefix-root"); }
  private Path deletedPrefix() { return prefixRoot().resolve("deleted"); }
  private Path collectionRoot() { return work().resolve("native-collection-root"); }
  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
