/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.component.TestEngineComponents;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A user-started rebuild (core.rebuild-index, core.bulk-reindex) is a recorded candidate without an
 * installer configuration. The bundled help documents must be in the generation it promotes, live
 * and after a restart that resumes the BUILDING generation, without duplicates or a marker edit.
 */
@Timeout(180)
final class KnowledgeServerRebuildHelpTest {
  private static final String OPERATION = "01994180-0000-7000-8000-000000000231";
  private static final String SECOND_OPERATION = "01994180-0000-7000-8000-000000000232";
  private static final String REBUILD = "user_requested_rebuild";

  @ParameterizedTest
  @ValueSource(strings = {REBUILD, "user_requested_bulk_reindex"})
  void userRebuildPromotesBundledHelpWithoutRestart(String source, @TempDir Path tempDir)
      throws Exception {
    Help help = Help.write(tempDir);
    try (Fixture fixture = new Fixture(tempDir)) {
      fixture.startRebuild(source);
      fixture.awaitMonitor();
      assertTrue(fixture.lifecycle.published, "the rebuild with zero watched roots promotes");
      assertEquals(fixture.building, fixture.manager.readStateBestEffort().active_generation());
      help.assertServedExactly(fixture.server);
    }
    help.assertUntouched();
    assertEquals("v6", Files.readString(tempDir.resolve("data/.help-ingested-version")),
        "the startup marker is not edited");
  }

  @Test
  void rebuiltHelpSurvivesRestartsWithoutDuplicates(@TempDir Path tempDir) throws Exception {
    Help help = Help.write(tempDir);
    try (Fixture fixture = new Fixture(tempDir)) {
      fixture.startRebuild(REBUILD);
      fixture.awaitMonitor();
      assertTrue(fixture.lifecycle.published);
    }
    for (int start = 0; start < 2; start++) {
      try (Fixture restarted = new Fixture(tempDir)) {
        help.assertServedExactly(restarted.server);
      }
    }
    help.assertUntouched();
  }

  @Test
  void excludedHelpStaysOutOfTheRebuild(@TempDir Path tempDir) throws Exception {
    Help help = Help.write(tempDir);
    try (Fixture fixture = new Fixture(tempDir, Map.of(
        "justsearch.ui.exclude_patterns", "[\"" + help.excluded.getFileName() + "\"]"))) {
      Files.writeString(help.excluded, "# Excluded\nexcludedhelpmarker");
      fixture.startRebuild(REBUILD);
      fixture.awaitMonitor();
      assertTrue(fixture.lifecycle.published);
      help.assertServedExactly(fixture.server);
    }
    try (Fixture restarted = new Fixture(tempDir, Map.of(
        "justsearch.ui.exclude_patterns", "[\"" + help.excluded.getFileName() + "\"]"))) {
      help.assertServedExactly(restarted.server);
    }
  }

  /** H5: a help folder that cannot be enumerated fails the rebuild; the serving generation stays. */
  @Test
  void unreadableHelpKeepsTheServingGeneration(@TempDir Path tempDir) throws Exception {
    Help help = Help.write(tempDir);
    assumeTrue(Files.getFileStore(help.root).supportsFileAttributeView("posix"));
    var permissions = Files.getPosixFilePermissions(help.root);
    try (Fixture fixture = new Fixture(tempDir)) {
      String serving = fixture.manager.readStateBestEffort().active_generation();
      Files.setPosixFilePermissions(help.root, java.util.Set.of());
      try {
        fixture.startRebuild(REBUILD);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!fixture.lifecycle.published && System.nanoTime() < deadline
            && !"FAILED".equals(fixture.manager.readStateBestEffort().migration_state())) {
          Thread.sleep(25);
        }
        assertFalse(fixture.lifecycle.published, "an incomplete help enumeration never promotes");
        assertEquals("FAILED", fixture.manager.readStateBestEffort().migration_state());
        fixture.lifecycle.cancelled = true; // the Engine records the refusal of the FAILED pointer
        fixture.awaitMonitor();
      } finally {
        Files.setPosixFilePermissions(help.root, permissions);
      }
      assertFalse(fixture.lifecycle.published);
      assertEquals(serving, fixture.manager.readStateBestEffort().active_generation());
    }
    help.assertUntouched();
  }

  /**
   * H2 overlap: a captured watched root that contains or is the help folder owns those files, so
   * the rebuild does not relabel them as help; the captured walk keeps their watched binding.
   */
  @ParameterizedTest
  @ValueSource(strings = {"ancestor", "coincide"})
  void capturedWatchedRootKeepsItsHelpBinding(String shape, @TempDir Path tempDir)
      throws Exception {
    Help help = Help.write(tempDir);
    Path watched = "ancestor".equals(shape) ? help.root.getParent() : help.root;
    try (Fixture fixture = new Fixture(tempDir)) {
      fixture.lifecycle.capturedRoot = watched;
      fixture.startRebuild(REBUILD);
      fixture.awaitMonitor();
      assertTrue(fixture.lifecycle.published);
      try (var view = fixture.server.captureServingView()) {
        for (Path file : help.expected) {
          assertNull(view.searchRuntime().documentFieldOps().getDocumentField(
              PathNormalizer.normalizeKey(file), SchemaFields.COLLECTION),
              "the watched root's walk, not the help source, owns " + file);
        }
      }
    }
    help.assertUntouched();
  }

  @ParameterizedTest
  @ValueSource(strings = {"absent", "empty"})
  void rebuildWithoutHelpSourceStillCompletes(String shape, @TempDir Path tempDir) throws Exception {
    if ("empty".equals(shape)) Files.createDirectories(tempDir.resolve("data/SSOT/docs/help"));
    try (Fixture fixture = new Fixture(tempDir)) {
      fixture.startRebuild(REBUILD);
      fixture.awaitMonitor();
      assertTrue(fixture.lifecycle.published, "no help is required when there is none");
      assertEquals(fixture.building, fixture.manager.readStateBestEffort().active_generation());
      try (var view = fixture.server.captureServingView()) {
        assertEquals(0L, view.searchRuntime().indexCountOps().docCountOrThrow());
      }
    }
  }

  /** H4(a): the process stopped after BUILDING was recorded, before help was admitted. */
  @Test
  void resumedBuildingGenerationAdmitsHelpBeforePromotion(@TempDir Path tempDir) throws Exception {
    Help help = Help.write(tempDir);
    Recorded recorded;
    try (Fixture first = new Fixture(tempDir)) {
      recorded = first.recordBuildingOnly(REBUILD);
    }
    resumeAndAssertHelp(tempDir, recorded, help, true);
  }

  /** H4(b): help rows were admitted to the candidate but not processed when the process stopped. */
  @Test
  void resumedBuildingGenerationWithAdmittedHelpHasNoDuplicates(@TempDir Path tempDir)
      throws Exception {
    Help help = Help.write(tempDir);
    Recorded recorded;
    try (Fixture first = new Fixture(tempDir)) {
      recorded = first.recordBuildingOnly(REBUILD);
    }
    try (var queue = new SqliteJobQueue(tempDir.resolve("data/jobs.db"))) {
      queue.open();
      var entries = help.expected.stream()
          .map(file -> new JobQueue.EnqueueEntry(file, sizeOf(file)).withinRoot(help.root)).toList();
      assertEquals(entries.size(), queue.enqueueEnumeratedFilesForGeneration(
          recorded.building(), entries, "justsearch-help"));
    }
    resumeAndAssertHelp(tempDir, recorded, help, true);
  }

  /** H4(c): the process stopped at the pointer commitment, before or after it. */
  @ParameterizedTest
  @ValueSource(strings = {"migration-before-pointer-commit", "migration-after-pointer-commit"})
  void crashAroundPointerCommitmentKeepsHelp(String point, @TempDir Path tempDir) throws Exception {
    Help help = Help.write(tempDir);
    Path snapshot = tempDir.resolve("snapshot");
    CountDownLatch release = new CountDownLatch(1);
    Recorded recorded;
    AtomicInteger reached = new AtomicInteger();
    try (Fixture fixture = new Fixture(tempDir, Map.of(), transition -> {
        if (point.equals(transition.point())) {
          // The durable state a process kill at this point leaves behind.
          copyTree(tempDir.resolve("data"), snapshot.resolve("data"));
          copyTree(tempDir.resolve("index"), snapshot.resolve("index"));
          reached.incrementAndGet();
          release.await(60, TimeUnit.SECONDS);
        }
      })) {
      recorded = fixture.startRebuild(REBUILD);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (reached.get() == 0 && System.nanoTime() < deadline) Thread.sleep(25);
      assertEquals(1, reached.get(), "the rebuild reaches " + point);
      release.countDown();
      fixture.awaitMonitor();
    }
    deleteTree(tempDir.resolve("data"));
    deleteTree(tempDir.resolve("index"));
    Files.move(snapshot.resolve("data"), tempDir.resolve("data"));
    Files.move(snapshot.resolve("index"), tempDir.resolve("index"));
    if ("migration-after-pointer-commit".equals(point)) {
      try (Fixture restarted = new Fixture(tempDir)) {
        assertEquals(recorded.building(),
            restarted.manager.readStateBestEffort().active_generation());
        help.assertServedExactly(restarted.server);
      }
    } else {
      // Recovery may resume the candidate or roll the recorded promotion forward.
      resumeAndAssertHelp(tempDir, recorded, help, false);
    }
    help.assertUntouched();
  }

  /** Cancellation before the pointer moves keeps the serving generation and its help. */
  @Test
  void refusedRebuildKeepsServingHelp(@TempDir Path tempDir) throws Exception {
    Help help = Help.write(tempDir);
    try (Fixture fixture = new Fixture(tempDir)) {
      help.indexInto(fixture.server); // the serving generation already holds its help
    }
    AtomicReference<RebuildLifecycle> owner = new AtomicReference<>();
    try (Fixture fixture = new Fixture(tempDir, Map.of(), transition -> {
        if ("migration-before-pointer-commit".equals(transition.point())) {
          // The user cancels: the owner records the refusal and this attempt stops before the
          // pointer moves.
          owner.get().cancelled = true;
          throw new IOException("fixture cancellation before the pointer");
        }
      })) {
      owner.set(fixture.lifecycle);
      String serving = fixture.manager.readStateBestEffort().active_generation();
      fixture.startRebuild(REBUILD, SECOND_OPERATION);
      fixture.awaitMonitor();
      assertTrue(fixture.lifecycle.cancelled, "the rebuild reached the pointer commitment");
      assertFalse(fixture.lifecycle.published);
      assertEquals(serving, fixture.manager.readStateBestEffort().active_generation());
      help.assertServedExactly(fixture.server);
    }
  }

  private static void resumeAndAssertHelp(Path tempDir, Recorded recorded, Help help,
      boolean requireBuilding) throws Exception {
    try (Fixture resumed = new Fixture(tempDir, Map.of(), MigrationTransitionBarrier.NO_HOOK,
        recorded)) {
      if (requireBuilding || field(resumed.server, "generationBootDisposition")
          == IndexGenerationManager.BootDisposition.BUILDING) {
        resumed.assertResumedBuilding(recorded);
        resumed.awaitMonitor();
        assertTrue(resumed.lifecycle.published, "the resumed rebuild promotes");
      }
      assertEquals(recorded.building(), resumed.manager.readStateBestEffort().active_generation());
      help.assertServedExactly(resumed.server);
    }
    try (Fixture restarted = new Fixture(tempDir)) {
      help.assertServedExactly(restarted.server);
    }
  }

  private record Recorded(String operation, String source, String sourceGeneration,
      String fingerprint, String building) {}

  /** Two eligible top-level Markdown files, one ignored text file and one nested Markdown file. */
  private record Help(Path root, List<Path> expected, Path excluded, Map<Path, String> bytes) {
    static Help write(Path tempDir) throws IOException {
      Path root = Files.createDirectories(tempDir.resolve("data/SSOT/docs/help"));
      Path welcome = Files.writeString(root.resolve("welcome.md"), "# Welcome\nhelpwelcomemarker");
      Path shortcuts = Files.writeString(root.resolve("keyboard-shortcuts.md"),
          "# Keyboard shortcuts\nhelpshortcutsmarker");
      Files.writeString(root.resolve("ignored.txt"), "not help");
      Files.writeString(Files.createDirectories(root.resolve("nested")).resolve("ignored.md"),
          "nested help");
      Files.writeString(tempDir.resolve("data/.help-ingested-version"), "v6");
      var bytes = new HashMap<Path, String>();
      try (Stream<Path> files = Files.walk(root)) {
        for (Path file : files.filter(Files::isRegularFile).toList()) {
          bytes.put(file, Files.readString(file));
        }
      }
      return new Help(root, List.of(welcome, shortcuts), root.resolve("excluded.md"), bytes);
    }

    void assertServedExactly(KnowledgeServer server) throws Exception {
      try (var view = server.captureServingView()) {
        var search = view.searchRuntime();
        for (Path file : expected) {
          assertEquals("justsearch-help", search.documentFieldOps().getDocumentField(
              PathNormalizer.normalizeKey(file), SchemaFields.COLLECTION),
              "help document is served with its own collection: " + file);
        }
        for (Path absent : List.of(root.resolve("ignored.txt"),
            root.resolve("nested/ignored.md"), excluded)) {
          assertNull(search.documentFieldOps().getDocumentField(
              PathNormalizer.normalizeKey(absent), SchemaFields.COLLECTION), absent.toString());
        }
        assertEquals(expected.size(), search.indexCountOps().docCountOrThrow(),
            "each help identity exactly once and nothing else");
      }
    }

    /** Writes the help documents as startup ingestion leaves them in the serving generation. */
    void indexInto(KnowledgeServer server) throws Exception {
      try (var view = server.captureServingView()) {
        var active = (io.justsearch.adapters.lucene.runtime.RunningRuntime) view.searchRuntime();
        for (Path file : expected) {
          String key = PathNormalizer.normalizeKey(file);
          active.indexingCoordinator().indexSingle(new io.justsearch.indexing.api.IndexDocument(
              Map.of(SchemaFields.DOC_ID, key, SchemaFields.DOC_UID, key + "#0",
                  SchemaFields.PATH, key, SchemaFields.COLLECTION, "justsearch-help",
                  SchemaFields.CONTENT, bytes.get(file))));
        }
        active.commitOps().commitAndTrack(
            io.justsearch.adapters.lucene.runtime.CommitReason.DRAIN);
      }
      assertServedExactly(server);
    }

    void assertUntouched() throws IOException {
      for (var entry : bytes.entrySet()) {
        assertEquals(entry.getValue(), Files.readString(entry.getKey()), "help is never written");
      }
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final TestEngineExecutors executors = new TestEngineExecutors();
    private final TestEngineComponents components = TestEngineComponents.fourComponents();
    private final RebuildLifecycle lifecycle = new RebuildLifecycle();
    private final ConfigStore previousConfiguration = ConfigStore.globalOrNull();
    private final KnowledgeServer server;
    private final IndexGenerationManager manager;
    private String building;

    private Fixture(Path tempDir) throws Exception {
      this(tempDir, Map.of());
    }

    private Fixture(Path tempDir, Map<String, String> extra) throws Exception {
      this(tempDir, extra, MigrationTransitionBarrier.NO_HOOK);
    }

    private Fixture(Path tempDir, Map<String, String> extra, MigrationTransitionBarrier.Hook barrier)
        throws Exception {
      this(tempDir, extra, barrier, null);
    }

    private Fixture(Path tempDir, Map<String, String> extra, MigrationTransitionBarrier.Hook barrier,
        Recorded resume) throws Exception {
      var values = new HashMap<String, String>();
      values.put(EnvRegistry.DATA_DIR.configKey(), tempDir.resolve("data").toString());
      values.put(EnvRegistry.INDEX_BASE_PATH.configKey(), tempDir.resolve("index").toString());
      values.put("justsearch.ssot.path", tempDir.resolve("data/SSOT").toString());
      values.put(EnvRegistry.EXTRACTION_SANDBOX_MODE.configKey(), "in_process");
      values.put("index.schema_mismatch.policy", "BLUE_GREEN_MIGRATE");
      values.put(EnvRegistry.SPARSE_MODEL.configKey(), "splade");
      for (var role : new EnvRegistry[] {EnvRegistry.AI_EMBED_ENABLED, EnvRegistry.SPLADE_ENABLED,
          EnvRegistry.NER_ENABLED, EnvRegistry.BGE_M3_ENABLED, EnvRegistry.RERANK_ENABLED,
          EnvRegistry.CITATION_SCORER_ENABLED}) values.put(role.configKey(), "false");
      values.putAll(extra);
      var configuration = TestResolvedConfigHelper.fromEntries(values);
      ConfigStore.setGlobal(new ConfigStore(configuration));
      if (resume != null) lifecycle.resume(resume);
      server = new KnowledgeServer(executors, WorkerConfig.load(configuration), null,
          ManagedChildRegistry.noop(), lifecycle, components.handle("index"),
          components.handle("encoders"), configuration, () -> ConfigStore.global().get(),
          new ReentrantReadWriteLock(true));
      server.installMigrationBarrierForTests(barrier);
      server.bindBootRootBindings(List.of());
      server.start();
      if (server.deferredModelInit != null) server.deferredModelInit.get(30, TimeUnit.SECONDS);
      assertTrue(server.awaitIndexRecoveryModels(30_000));
      manager = (IndexGenerationManager) field(server, "indexGenerationManager");
    }

    /** Records the BUILDING generation durably, as the Engine does, then starts it live. */
    private Recorded startRebuild(String source) throws Exception {
      return startRebuild(source, OPERATION);
    }

    private Recorded startRebuild(String source, String operation) throws Exception {
      Recorded recorded = recordBuildingOnly(source, operation);
      lifecycle.resume(recorded);
      assertTrue(server.beginRecordedBuildingLiveAsync(operation, () -> {})
          .get(30, TimeUnit.SECONDS));
      return recorded;
    }

    /** The durable BUILDING record without any live start: a process stop right after it. */
    private Recorded recordBuildingOnly(String source) throws Exception {
      return recordBuildingOnly(source, OPERATION);
    }

    private Recorded recordBuildingOnly(String source, String operation) throws Exception {
      var method = KnowledgeServer.class.getDeclaredMethod("expectedIndexFingerprintOrNull");
      method.setAccessible(true);
      String fingerprint = (String) method.invoke(server);
      assertNotNull(fingerprint);
      String active = manager.readStateBestEffort().active_generation();
      building = manager.startRecordedMigration(operation, source, fingerprint, active, List.of())
          .building_generation();
      return new Recorded(operation, source, active, fingerprint, building);
    }

    /** The boot found the recorded candidate and resumed it as the BUILDING generation. */
    private void assertResumedBuilding(Recorded recorded) throws Exception {
      building = recorded.building();
      assertEquals(IndexGenerationManager.BootDisposition.BUILDING,
          field(server, "generationBootDisposition"));
    }

    private void awaitMonitor() throws Exception {
      Thread monitor = (Thread) field(server, "migrationCutoverThread");
      assertNotNull(monitor);
      monitor.join(60_000);
      assertFalse(monitor.isAlive(), "the physical cutover monitor must finish");
    }

    @Override public void close() throws Exception {
      try { server.close(); }
      finally {
        try { components.close(); executors.close(); }
        finally { TestResolvedConfigHelper.restoreGlobal(previousConfiguration); }
      }
    }
  }

  /** The recorded bulk owner: no installer candidate, an empty settings projection. */
  private static final class RebuildLifecycle implements RecordedIngestionLifecycle {
    private volatile Recorded recorded;
    private volatile boolean published;
    private volatile boolean cancelled;
    private volatile Path capturedRoot;

    void resume(Recorded value) { recorded = value; }

    @Override public Optional<RecordedRootPlan> recordedRootPlan(String key) {
      Recorded current = recorded;
      return capturedRoot == null || current == null ? Optional.empty()
          : Optional.of(new RecordedRootPlan(current.building(), List.of(new RecordedRootPlan.Root(
              capturedRoot, null, false, false, List.of(), List.of()))));
    }

    @Override public IndexGenerationManager.BootOwnership bootOwnership(JobQueue queue) {
      Recorded current = recorded;
      return current == null || published ? new IndexGenerationManager.BootOwnership.Native()
          : new IndexGenerationManager.BootOwnership.Recorded(current.operation(),
              current.sourceGeneration(),
              current.source(), current.fingerprint(), true, true, List.of());
    }
    @Override public Optional<RecordedCandidate> recordedCandidate(String key) { return Optional.empty(); }
    @Override public boolean recordedCutoverReady(String key) { return recorded != null && !cancelled; }
    @Override public boolean recordedPrecommitRefused(String key) { return cancelled; }
    @Override public boolean committedBulkTerminal(String key) { return published; }
    @Override public JobQueue.RecordedClaimDecision recordedClaimDecision(String key) {
      return JobQueue.RecordedClaimDecision.ALLOW;
    }
    @Override public Attachment attach(JobQueue queue, CheckedServingGeneration generation,
        java.util.function.BooleanSupplier online) { return () -> {}; }
    @Override public IndexGenerationManager.State promoteRecordedGeneration(
        String key, JobQueue queue, CheckedPromotion promotion) throws IOException {
      var state = promotion.promote();
      published = true;
      return state;
    }
  }

  private static long sizeOf(Path file) {
    try { return Files.size(file); }
    catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
  }

  private static void copyTree(Path from, Path to) throws IOException {
    try (Stream<Path> paths = Files.walk(from)) {
      for (Path path : paths.toList()) {
        Path target = to.resolve(from.relativize(path).toString());
        if (Files.isDirectory(path)) Files.createDirectories(target);
        else if (Files.isRegularFile(path)) Files.copy(path, target);
      }
    }
  }

  private static void deleteTree(Path root) throws IOException {
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }

  private static Object field(KnowledgeServer server, String name) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(server);
  }
}
