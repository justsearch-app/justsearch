package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.ObjectMapper;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.index.MigrationProgressSnapshot;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for KnowledgeServer.
 *
 * <p>Tests are organized by functionality:
 * <ul>
 *   <li>Safe gauge methods (Phase 1)</li>
 *   <li>State getters (Phase 2)</li>
 *   <li>File I/O methods (Phase 4)</li>
 *   <li>Migration progress (Phase 4)</li>
 * </ul>
 *
 * <p>Uses reflection and Mockito to test private methods without calling
 * the constructor (which requires complex WorkerConfig setup).
 */
@DisplayName("KnowledgeServer")
class KnowledgeServerTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void cutoverDefersWhenPendingEmbeddingCountCannotBeRead() throws Exception {
    var server = createServerWithJobQueue(new StubJobQueue());
    var runtime = org.mockito.Mockito.mock(
        io.justsearch.adapters.lucene.runtime.RunningRuntime.class,
        org.mockito.Mockito.RETURNS_DEEP_STUBS);
    var controller = org.mockito.Mockito.mock(EmbeddingCompatibilityController.class);
    org.mockito.Mockito.when(controller.currentFingerprint()).thenReturn("current-model");
    setField(server, "ingestLifecycle", runtime);
    setField(server, "embeddingCompatController", controller);
    var counts = runtime.indexCountOps();
    org.mockito.Mockito.when(counts.countByFieldOrThrow(
        io.justsearch.indexing.SchemaFields.EMBEDDING_STATUS,
        io.justsearch.indexing.SchemaFields.EMBEDDING_STATUS_PENDING))
        .thenThrow(new IOException("reader unavailable"))
        .thenReturn(0);
    Method method = KnowledgeServer.class.getDeclaredMethod("finalizeEmbeddingRebuildBeforeCutover");
    method.setAccessible(true);

    assertEquals(false, method.invoke(server), "unreadable is not drained");
    org.mockito.Mockito.verify(controller, org.mockito.Mockito.never())
        .checkRebuildCompletion(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    assertEquals(true, method.invoke(server), "a subsequent successful zero read permits certification");
    org.mockito.Mockito.verify(controller).checkRebuildCompletion(0L, 0);
  }

  // ==================== Phase 1: Safe Gauge Methods ====================

  @Nested
  @DisplayName("safePendingJobs()")
  class SafePendingJobsTests {

    @Test
    @DisplayName("null queue returns zero")
    void nullQueue_returnsZero() throws Exception {
      KnowledgeServer server = createServerWithJobQueue(null);
      assertEquals(0L, invokeSafePendingJobs(server));
    }

    @Test
    @DisplayName("non-SqliteJobQueue falls back to safeJobQueueDepth")
    void nonSqliteQueue_fallsBackToQueueDepth() throws Exception {
      StubJobQueue queue = new StubJobQueue();
      queue.setQueueDepth(99L);
      KnowledgeServer server = createServerWithJobQueue(queue);
      // Falls back to safeJobQueueDepth() which returns queueDepth()
      assertEquals(99L, invokeSafePendingJobs(server));
    }

    @Test
    @DisplayName("SqliteJobQueue returns pending count")
    void sqliteQueue_returnsPendingCount(@TempDir Path tempDir) throws Exception {
      Path dbPath = tempDir.resolve("test.db");
      try (SqliteJobQueue queue = new SqliteJobQueue(dbPath)) {
        queue.open();
        // Enqueue some files
        queue.enqueue(List.of(tempDir.resolve("file1.txt"), tempDir.resolve("file2.txt")));

        KnowledgeServer server = createServerWithJobQueue(queue);
        long pending = invokeSafePendingJobs(server);
        assertEquals(2L, pending);
      }
    }
  }

  @Nested
  @DisplayName("safeProcessingJobs()")
  class SafeProcessingJobsTests {

    @Test
    @DisplayName("null queue returns zero")
    void nullQueue_returnsZero() throws Exception {
      KnowledgeServer server = createServerWithJobQueue(null);
      assertEquals(0L, invokeSafeProcessingJobs(server));
    }

    @Test
    @DisplayName("non-SqliteJobQueue returns zero")
    void nonSqliteQueue_returnsZero() throws Exception {
      StubJobQueue queue = new StubJobQueue();
      KnowledgeServer server = createServerWithJobQueue(queue);
      assertEquals(0L, invokeSafeProcessingJobs(server));
    }
  }

  @Nested
  @DisplayName("safePendingReadyJobs()")
  class SafePendingReadyJobsTests {

    @Test
    @DisplayName("null queue returns zero")
    void nullQueue_returnsZero() throws Exception {
      KnowledgeServer server = createServerWithJobQueue(null);
      assertEquals(0L, invokeSafePendingReadyJobs(server));
    }

    @Test
    @DisplayName("non-SqliteJobQueue returns zero")
    void nonSqliteQueue_returnsZero() throws Exception {
      StubJobQueue queue = new StubJobQueue();
      KnowledgeServer server = createServerWithJobQueue(queue);
      assertEquals(0L, invokeSafePendingReadyJobs(server));
    }
  }

  @Nested
  @DisplayName("safePendingBackoffJobs()")
  class SafePendingBackoffJobsTests {

    @Test
    @DisplayName("null queue returns zero")
    void nullQueue_returnsZero() throws Exception {
      KnowledgeServer server = createServerWithJobQueue(null);
      assertEquals(0L, invokeSafePendingBackoffJobs(server));
    }

    @Test
    @DisplayName("non-SqliteJobQueue returns zero")
    void nonSqliteQueue_returnsZero() throws Exception {
      StubJobQueue queue = new StubJobQueue();
      KnowledgeServer server = createServerWithJobQueue(queue);
      assertEquals(0L, invokeSafePendingBackoffJobs(server));
    }
  }

  @Nested
  @DisplayName("safeSwitchBufferDepth()")
  class SafeSwitchBufferDepthTests {

    @Test
    @DisplayName("null queue returns zero")
    void nullQueue_returnsZero() throws Exception {
      KnowledgeServer server = createServerWithJobQueue(null);
      assertEquals(0L, invokeSafeSwitchBufferDepth(server));
    }

    @Test
    @DisplayName("non-SqliteJobQueue returns zero")
    void nonSqliteQueue_returnsZero() throws Exception {
      StubJobQueue queue = new StubJobQueue();
      KnowledgeServer server = createServerWithJobQueue(queue);
      assertEquals(0L, invokeSafeSwitchBufferDepth(server));
    }

    @Test
    @DisplayName("SqliteJobQueue returns buffer depth")
    void sqliteQueue_returnsBufferDepth(@TempDir Path tempDir) throws Exception {
      Path dbPath = tempDir.resolve("test.db");
      try (SqliteJobQueue queue = new SqliteJobQueue(dbPath)) {
        queue.open();
        // Empty buffer should return 0
        KnowledgeServer server = createServerWithJobQueue(queue);
        assertEquals(0L, invokeSafeSwitchBufferDepth(server));

        // Add a buffer entry
        queue.putSwitchBuffer("test:key", "UPSERT", "/some/path");
        assertEquals(1L, invokeSafeSwitchBufferDepth(server));
      }
    }
  }

  @Nested
  @DisplayName("safePendingEmbeddings()")
  class SafePendingEmbeddingsTests {

    @Test
    @DisplayName("null ingestRuntime returns zero")
    void nullRuntime_returnsZero() throws Exception {
      KnowledgeServer server = createServerWithIngestRuntime(null);
      assertEquals(0, invokeSafePendingEmbeddings(server));
    }
  }

  @Nested
  @DisplayName("safePendingVdu()")
  class SafePendingVduTests {

    @Test
    @DisplayName("null ingestRuntime returns zero")
    void nullRuntime_returnsZero() throws Exception {
      KnowledgeServer server = createServerWithIngestRuntime(null);
      assertEquals(0, invokeSafePendingVdu(server));
    }
  }

  // ==================== Phase 2: State Getters ====================

  // Lane F stage A item A13 deleted KnowledgeServer#getPort() along with its last caller (the
  // standalone IndexerWorker.main log line). It had returned a constant -1 since A9 removed the
  // gRPC server, so the nested GetPortTests block that pinned "-1, there is no socket" went with
  // it: inside one JVM there is no port to be wrong about.

  @Nested
  @DisplayName("embeddingCompatController()")
  class EmbeddingCompatControllerTests {

    @Test
    @DisplayName("returns null when not initialized")
    void notInitialized_returnsNull() throws Exception {
      KnowledgeServer server = createEmptyServer();
      assertNull(server.embeddingCompatController());
    }

    @Test
    @DisplayName("returns controller when set")
    void initialized_returnsController() throws Exception {
      KnowledgeServer server = createEmptyServer();
      // Create a minimal mock controller (using reflection since constructor is complex)
      EmbeddingCompatibilityController controller =
          createEmbeddingCompatController();
      setField(server, "embeddingCompatController", controller);
      assertSame(controller, server.embeddingCompatController());
    }
  }

  // ==================== Phase 4: File I/O Methods ====================

  // Note: handleCorruptDatabase() tests removed - they require integration-level testing
  // due to SQLite connection lifecycle management. The method manipulates open SQLite
  // connections and WAL files which are difficult to test in isolation.

  @Nested
  @DisplayName("loadWatchedRootsBestEffort()")
  class LoadWatchedRootsTests {

    @Test
    @DisplayName("returns empty list when no roots file and no config")
    void noRootsFile_noConfig_returnsEmpty(@TempDir Path tempDir) throws Exception {
      KnowledgeServer server = createServerWithDataDir(tempDir);
      List<Path> roots = invokeLoadWatchedRootsBestEffort(server, null);
      assertTrue(roots.isEmpty());
    }

    @Test
    @DisplayName("loads roots from JSON array format")
    void jsonArrayFormat_loadsRoots(@TempDir Path tempDir) throws Exception {
      // Create a watched_roots.json with array format
      Path rootsFile = tempDir.resolve("watched_roots.json");
      Path root1 = tempDir.resolve("docs");
      Path root2 = tempDir.resolve("code");
      Files.createDirectories(root1);
      Files.createDirectories(root2);

      String json = JSON.writeValueAsString(List.of(root1.toString(), root2.toString()));
      Files.writeString(rootsFile, json);

      KnowledgeServer server = createServerWithDataDir(tempDir);
      List<Path> roots = invokeLoadWatchedRootsBestEffort(server, null);

      assertEquals(2, roots.size());
      assertTrue(roots.contains(root1.toAbsolutePath().normalize()));
      assertTrue(roots.contains(root2.toAbsolutePath().normalize()));
    }

    @Test
    @DisplayName("loads roots from object format with roots array")
    void objectFormat_loadsRoots(@TempDir Path tempDir) throws Exception {
      Path rootsFile = tempDir.resolve("watched_roots.json");
      Path root1 = tempDir.resolve("documents");
      Files.createDirectories(root1);

      String json = """
          {
            "roots": [
              {"path": "%s"}
            ]
          }
          """.formatted(root1.toString().replace("\\", "\\\\"));
      Files.writeString(rootsFile, json);

      KnowledgeServer server = createServerWithDataDir(tempDir);
      List<Path> roots = invokeLoadWatchedRootsBestEffort(server, null);

      assertEquals(1, roots.size());
      assertEquals(root1.toAbsolutePath().normalize(), roots.get(0));
    }

    @Test
    @DisplayName("skips non-existent directories")
    void nonExistentDirs_areSkipped(@TempDir Path tempDir) throws Exception {
      Path rootsFile = tempDir.resolve("watched_roots.json");
      Path existingRoot = tempDir.resolve("exists");
      Files.createDirectories(existingRoot);

      String json = JSON.writeValueAsString(List.of(
          existingRoot.toString(),
          tempDir.resolve("does_not_exist").toString()));
      Files.writeString(rootsFile, json);

      KnowledgeServer server = createServerWithDataDir(tempDir);
      List<Path> roots = invokeLoadWatchedRootsBestEffort(server, null);

      assertEquals(1, roots.size());
      assertEquals(existingRoot.toAbsolutePath().normalize(), roots.get(0));
    }

    @Test
    @DisplayName("handles malformed JSON gracefully")
    void malformedJson_returnsEmpty(@TempDir Path tempDir) throws Exception {
      Path rootsFile = tempDir.resolve("watched_roots.json");
      Files.writeString(rootsFile, "{ invalid json }}}");

      KnowledgeServer server = createServerWithDataDir(tempDir);
      List<Path> roots = invokeLoadWatchedRootsBestEffort(server, null);

      // Should return empty (or fallback to config roots)
      assertTrue(roots.isEmpty());
    }
  }

  @Nested
  @DisplayName("migrationProgressSnapshot()")
  class MigrationProgressSnapshotTests {

    @Test
    @DisplayName("returns live snapshot when no persisted snapshot")
    void noPersistedSnapshot_returnsLive() throws Exception {
      KnowledgeServer server = createEmptyServer();

      // Set up live atomic fields
      setAtomicBoolean(server, "migrationEnumeratorRunning", true);
      setVolatileBoolean(server, "migrationEnumeratorDone", false);
      setAtomicLong(server, "migrationEnumeratorRootsTotal", 5L);
      setAtomicLong(server, "migrationEnumeratorRootsDone", 2L);
      setAtomicLong(server, "migrationEnumeratorFilesSeen", 100L);
      setAtomicLong(server, "migrationEnumeratorFilesEnqueued", 95L);
      setAtomicLong(server, "migrationEnumeratorStartedAtMs", 1000L);
      setAtomicLong(server, "migrationEnumeratorFinishedAtMs", 0L);
      setAtomicReference(server, "migrationEnumeratorLastPath", "/some/path");
      setField(server, "persistedMigrationProgressSnapshot", null);

      MigrationProgressSnapshot snapshot = invokeMigrationProgressSnapshot(server);

      assertTrue(snapshot.enumeratorRunning());
      assertFalse(snapshot.enumeratorDone());
      assertEquals(5L, snapshot.rootsTotal());
      assertEquals(2L, snapshot.rootsDone());
      assertEquals(100L, snapshot.filesSeen());
      assertEquals(95L, snapshot.filesEnqueued());
      assertEquals(1000L, snapshot.startedAtMs());
      assertEquals(0L, snapshot.finishedAtMs());
      assertEquals("/some/path", snapshot.lastPath());
    }

    @Test
    @DisplayName("merges with persisted snapshot keeping higher counters")
    void persistedSnapshot_mergesHigherCounters() throws Exception {
      KnowledgeServer server = createEmptyServer();

      // Set up live atomic fields with lower values
      setAtomicBoolean(server, "migrationEnumeratorRunning", false);
      setVolatileBoolean(server, "migrationEnumeratorDone", true);
      setAtomicLong(server, "migrationEnumeratorRootsTotal", 3L);
      setAtomicLong(server, "migrationEnumeratorRootsDone", 1L);
      setAtomicLong(server, "migrationEnumeratorFilesSeen", 50L);
      setAtomicLong(server, "migrationEnumeratorFilesEnqueued", 45L);
      setAtomicLong(server, "migrationEnumeratorStartedAtMs", 2000L);
      setAtomicLong(server, "migrationEnumeratorFinishedAtMs", 3000L);
      setAtomicReference(server, "migrationEnumeratorLastPath", "");

      // Set persisted snapshot with higher values
      MigrationProgressSnapshot persisted = new MigrationProgressSnapshot(
          true, false, 5L, 3L, 200L, 180L, 1000L, 2500L, "/old/path");
      setField(server, "persistedMigrationProgressSnapshot", persisted);

      MigrationProgressSnapshot snapshot = invokeMigrationProgressSnapshot(server);

      // Should take max of counters
      assertEquals(5L, snapshot.rootsTotal());
      assertEquals(3L, snapshot.rootsDone());
      assertEquals(200L, snapshot.filesSeen());
      assertEquals(180L, snapshot.filesEnqueued());
      // Should take min of started times
      assertEquals(1000L, snapshot.startedAtMs());
      // Should take max of finished times
      assertEquals(3000L, snapshot.finishedAtMs());
      // Should take live lastPath if not blank
      assertEquals("/old/path", snapshot.lastPath()); // live is blank, so uses persisted
    }
  }

  // ==================== Phase 5: Enqueue Methods ====================

  @Nested
  @DisplayName("enqueueAllFilesUnderRoots()")
  class EnqueueAllFilesUnderRootsTests {

    @Test
    @DisplayName("returns 0 for empty roots list")
    void emptyRoots_returnsZero(@TempDir Path tempDir) throws Exception {
      Path dbPath = tempDir.resolve("jobs.db");
      try (SqliteJobQueue queue = new SqliteJobQueue(dbPath)) {
        queue.open();
        KnowledgeServer server = createServerWithJobQueue(queue);

        int count = invokeEnqueueAllFilesUnderRoots(server, List.of());
        assertEquals(0, count);
      }
    }

    @Test
    @DisplayName("returns 0 when jobQueue is null")
    void nullQueue_returnsZero() throws Exception {
      KnowledgeServer server = createServerWithJobQueue(null);
      int count = invokeEnqueueAllFilesUnderRoots(server, List.of(Path.of("/some/path")));
      assertEquals(0, count);
    }

    @Test
    @DisplayName("enqueues files from roots")
    void validRoots_enqueuesFiles(@TempDir Path tempDir) throws Exception {
      // Create directory structure
      Path root = tempDir.resolve("docs");
      Files.createDirectories(root);
      Files.writeString(root.resolve("file1.txt"), "content1");
      Files.writeString(root.resolve("file2.txt"), "content2");
      Path subdir = root.resolve("subdir");
      Files.createDirectories(subdir);
      Files.writeString(subdir.resolve("file3.txt"), "content3");

      Path dbPath = tempDir.resolve("jobs.db");
      try (SqliteJobQueue queue = new SqliteJobQueue(dbPath)) {
        queue.open();
        KnowledgeServer server = createServerWithJobQueueAndRunning(queue, true);

        int count = invokeEnqueueAllFilesUnderRoots(server, List.of(root));
        assertEquals(3, count);
        assertEquals(3L, queue.queueDepth());
      }
    }

    @Test
    @DisplayName("skips non-directory roots")
    void nonDirectoryRoots_areSkipped(@TempDir Path tempDir) throws Exception {
      Path dbPath = tempDir.resolve("jobs.db");
      Path file = tempDir.resolve("not_a_dir.txt");
      Files.writeString(file, "content");

      try (SqliteJobQueue queue = new SqliteJobQueue(dbPath)) {
        queue.open();
        KnowledgeServer server = createServerWithJobQueueAndRunning(queue, true);

        int count = invokeEnqueueAllFilesUnderRoots(server, List.of(file));
        assertEquals(0, count);
      }
    }
  }

  // ==================== Reflection Helpers ====================

  private static long invokeSafePendingJobs(KnowledgeServer server) throws Exception {
    Method method = KnowledgeServer.class.getDeclaredMethod("safePendingJobs");
    method.setAccessible(true);
    return (long) method.invoke(server);
  }

  private static long invokeSafeProcessingJobs(KnowledgeServer server) throws Exception {
    Method method = KnowledgeServer.class.getDeclaredMethod("safeProcessingJobs");
    method.setAccessible(true);
    return (long) method.invoke(server);
  }

  private static long invokeSafePendingReadyJobs(KnowledgeServer server) throws Exception {
    Method method = KnowledgeServer.class.getDeclaredMethod("safePendingReadyJobs");
    method.setAccessible(true);
    return (long) method.invoke(server);
  }

  private static long invokeSafePendingBackoffJobs(KnowledgeServer server) throws Exception {
    Method method = KnowledgeServer.class.getDeclaredMethod("safePendingBackoffJobs");
    method.setAccessible(true);
    return (long) method.invoke(server);
  }

  private static long invokeSafeSwitchBufferDepth(KnowledgeServer server) throws Exception {
    Method method = KnowledgeServer.class.getDeclaredMethod("safeSwitchBufferDepth");
    method.setAccessible(true);
    return (long) method.invoke(server);
  }

  private static int invokeSafePendingEmbeddings(KnowledgeServer server) throws Exception {
    Method method = KnowledgeServer.class.getDeclaredMethod("safePendingEmbeddings");
    method.setAccessible(true);
    return (int) method.invoke(server);
  }

  private static int invokeSafePendingVdu(KnowledgeServer server) throws Exception {
    Method method = KnowledgeServer.class.getDeclaredMethod("safePendingVdu");
    method.setAccessible(true);
    return (int) method.invoke(server);
  }

  @SuppressWarnings("unchecked")
  private static List<Path> invokeLoadWatchedRootsBestEffort(
      KnowledgeServer server, Object ignored) throws Exception {
    Method method = KnowledgeServer.class.getDeclaredMethod(
        "loadWatchedRootsBestEffort",
        io.justsearch.configuration.resolved.ResolvedConfig.class);
    method.setAccessible(true);
    // Build a minimal ResolvedConfig with empty collections
    io.justsearch.configuration.resolved.ResolvedConfig rc =
        io.justsearch.configuration.resolved.ResolvedConfig.builder().build();
    return (List<Path>) method.invoke(server, rc);
  }

  private static MigrationProgressSnapshot invokeMigrationProgressSnapshot(KnowledgeServer server)
      throws Exception {
    Method method = KnowledgeServer.class.getDeclaredMethod("migrationProgressSnapshot");
    method.setAccessible(true);
    return (MigrationProgressSnapshot) method.invoke(server);
  }

  private static int invokeEnqueueAllFilesUnderRoots(KnowledgeServer server, List<Path> roots)
      throws Exception {
    Method method =
        KnowledgeServer.class.getDeclaredMethod("enqueueAllFilesUnderRoots", List.class);
    method.setAccessible(true);
    return (int) method.invoke(server, roots);
  }

  // ==================== Server Factory Helpers ====================

  /** Creates an empty KnowledgeServer without calling constructor. */
  private static KnowledgeServer createEmptyServer() throws Exception {
    KnowledgeServer server =
        org.mockito.Mockito.mock(KnowledgeServer.class, org.mockito.Mockito.CALLS_REAL_METHODS);

    // Initialize atomic fields that are final and need values
    initializeAtomicFields(server);

    return server;
  }

  /** Creates a KnowledgeServer with only the jobQueue field set. */
  @SuppressWarnings("removal")
  private static KnowledgeServer createServerWithJobQueue(JobQueue jobQueue) throws Exception {
    KnowledgeServer server = createEmptyServer();
    setField(server, "jobQueue", jobQueue);
    return server;
  }

  /** Creates a KnowledgeServer with jobQueue and running flag set. */
  private static KnowledgeServer createServerWithJobQueueAndRunning(
      JobQueue jobQueue, boolean running) throws Exception {
    KnowledgeServer server = createServerWithJobQueue(jobQueue);
    setField(server, "running", running);
    return server;
  }

  /** Creates a KnowledgeServer with only the ingestLifecycle field set. */
  private static KnowledgeServer createServerWithIngestRuntime(Object runtime) throws Exception {
    KnowledgeServer server = createEmptyServer();
    setField(server, "ingestLifecycle", runtime);
    return server;
  }

  /** Creates a KnowledgeServer with the dataDir field set. */
  private static KnowledgeServer createServerWithDataDir(Path dataDir) throws Exception {
    KnowledgeServer server = createEmptyServer();
    setField(server, "dataDir", dataDir);
    return server;
  }

  /** Creates a minimal EmbeddingCompatibilityController for testing. */
  private static EmbeddingCompatibilityController createEmbeddingCompatController() {
    return new EmbeddingCompatibilityController(java.util.Map::of, () -> 0L);
  }

  /** Initializes final fields that require non-null values. */
  private static void initializeAtomicFields(KnowledgeServer server) throws Exception {
    setField(server, "migrationEnumeratorRunning", new AtomicBoolean(false));
    setField(server, "migrationEnumeratorRootsTotal", new AtomicLong(0L));
    setField(server, "migrationEnumeratorRootsDone", new AtomicLong(0L));
    setField(server, "migrationEnumeratorFilesSeen", new AtomicLong(0L));
    setField(server, "migrationEnumeratorFilesEnqueued", new AtomicLong(0L));
    setField(server, "migrationEnumeratorStartedAtMs", new AtomicLong(0L));
    setField(server, "migrationEnumeratorFinishedAtMs", new AtomicLong(0L));
    setField(server, "migrationEnumeratorLastPath", new AtomicReference<>(""));
    setField(
        server,
        "embeddingFingerprintSupplier",
        new AtomicReference<java.util.function.Supplier<java.util.Optional<String>>>(
            java.util.Optional::empty));
  }

  private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
    for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
      try {
        return c.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        // Not declared at this level of the hierarchy; fall through and try the superclass.
      }
    }
    throw new NoSuchFieldException(name);
  }

  private static void setField(Object target, String fieldName, Object value) throws Exception {
    Field field = findField(target.getClass(), fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static void setAtomicBoolean(Object target, String fieldName, boolean value)
      throws Exception {
    Field field = findField(target.getClass(), fieldName);
    field.setAccessible(true);
    ((AtomicBoolean) field.get(target)).set(value);
  }

  private static void setAtomicLong(Object target, String fieldName, long value) throws Exception {
    Field field = findField(target.getClass(), fieldName);
    field.setAccessible(true);
    ((AtomicLong) field.get(target)).set(value);
  }

  @SuppressWarnings("unchecked")
  private static void setAtomicReference(Object target, String fieldName, Object value)
      throws Exception {
    Field field = findField(target.getClass(), fieldName);
    field.setAccessible(true);
    ((AtomicReference<Object>) field.get(target)).set(value);
  }

  private static void setVolatileBoolean(Object target, String fieldName, boolean value)
      throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.setBoolean(target, value);
  }

  // ==================== Stub Implementations ====================

  /** Stub JobQueue for testing safe* gauge methods. */
  private static final class StubJobQueue implements JobQueue {
    private long queueDepth = 0L;
    private boolean shouldThrow = false;

    void setQueueDepth(long depth) {
      this.queueDepth = depth;
    }

    @Override
    public long queueDepth() {
      if (shouldThrow) {
        throw new RuntimeException("Simulated failure");
      }
      return queueDepth;
    }

    @Override
    public void open() throws SQLException, IOException {}

    @Override
    public int enqueue(List<Path> paths, String collection) {
      return 0;
    }

    @Override
    public List<IndexJob> pollPending(int limit) {
      return List.of();
    }

    @Override
    public void markDone(Path path) {}

    @Override
    public void markFailed(Path path, String errorMessage) {}

    @Override
    public int recoverStuckJobs() {
      return 0;
    }

    @Override
    public long completedCount() {
      return 0;
    }

    @Override
    public int cleanupOldJobs(int retentionDays) {
      return 0;
    }

    @Override
    public void close() {}
  }
}
