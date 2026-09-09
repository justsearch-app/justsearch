package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.ObjectMapper;
import io.justsearch.ipc.BatchRequest;
import io.justsearch.ipc.BatchResponse;
import io.justsearch.ipc.CountJobsByPathPrefixRequest;
import io.justsearch.ipc.CountJobsByPathPrefixResponse;
import io.justsearch.ipc.StatusRequest;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.SyncDirectoryResponse;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.core.context.EngineContext;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkerIngestServiceTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;
  private JobQueue jobQueue;
  private WorkerIngestService service;

  @BeforeEach
  void setUp() throws Exception {
    Path dbPath = tempDir.resolve("jobs.db");
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    // Use stub implementations for dependencies not needed in these tests
    IndexingLoop stubLoop = stubIndexingLoop();
    WorkerSignalBus stubBus = new StubWorkerSignalBus();
    Path stubIndexBasePath = tempDir.resolve("indexBase");
    Path stubIndexPath = stubIndexBasePath.resolve("indices").resolve("g-test");
    Files.createDirectories(stubIndexPath);
    service = new WorkerIngestService(
        jobQueue, stubLoop, stubBus, IndexingPacing.unthrottled(), stubIndexBasePath, stubIndexPath,
        null, null, null, 0L);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (jobQueue != null) {
      jobQueue.close();
    }
  }

  @Test
  void submitBatchAcceptsExistingFiles() throws Exception {
    // Create real files for the test
    Path file1 = tempDir.resolve("file1.txt");
    Path file2 = tempDir.resolve("file2.txt");
    Files.writeString(file1, "content1");
    Files.writeString(file2, "content2");

    BatchRequest request = BatchRequest.newBuilder()
        .addFilePaths(file1.toAbsolutePath().toString())
        .addFilePaths(file2.toAbsolutePath().toString())
        .build();

    BatchResponse response = service.submitBatch(request, CallContext.none());

    assertEquals(2, response.getAcceptedCount());
    assertTrue(response.getErrorMessage().isEmpty());
  }

  /**
   * Tempdoc 813 Slice B call-site pin. {@code SubmitBatch} is one of the four producers of queue
   * rows; the remaining-work byte weight is only as good as what each producer records, and a
   * producer that quietly used the unsized overload would not fail anything — the aggregate would
   * just understate the backlog. So the size is asserted at the call site.
   */
  @Test
  void submitBatchRecordsEachFilesSizeAtEnqueue() throws Exception {
    Path small = Files.writeString(tempDir.resolve("small.txt"), "x".repeat(10));
    Path big = Files.writeString(tempDir.resolve("big.txt"), "y".repeat(4_096));

    BatchResponse response =
        service.submitBatch(
            BatchRequest.newBuilder()
                .addFilePaths(small.toAbsolutePath().toString())
                .addFilePaths(big.toAbsolutePath().toString())
                .build(),
            CallContext.none());
    assertEquals(2, response.getAcceptedCount());

    JobQueue.PendingBytes bytes = jobQueue.pendingBytes();
    assertEquals(
        Files.size(small) + Files.size(big),
        bytes.knownBytes(),
        "SubmitBatch must stat each admitted file and carry the size on the enqueue entry");
    assertEquals(0L, bytes.unknownSizeJobs(), "no admitted file may land as an unsized row");
  }

  @Test
  void submitBatchRejectsNonExistentFiles() {
    BatchRequest request = BatchRequest.newBuilder()
        .addFilePaths("/nonexistent/path/file.txt")
        .build();

    // Should complete without error but accept 0 files
    BatchResponse response = service.submitBatch(request, CallContext.none());

    assertEquals(0, response.getAcceptedCount());
    assertTrue(response.getErrorMessage().contains("invalid") || response.getErrorMessage().contains("All paths"));
  }

  @Test
  void submitBatchRejectsPathTraversalAttempts() throws Exception {
    // Create a real file
    Path realFile = tempDir.resolve("real.txt");
    Files.writeString(realFile, "content");

    BatchRequest request = BatchRequest.newBuilder()
        .addFilePaths(realFile.toAbsolutePath().toString())
        .addFilePaths(tempDir.toAbsolutePath() + "/../../../etc/passwd")
        .build();

    // Should accept the real file but reject the traversal attempt
    BatchResponse response = service.submitBatch(request, CallContext.none());

    assertEquals(1, response.getAcceptedCount());
  }

  @Test
  void submitBatchRejectsRelativePaths() throws Exception {
    BatchRequest request = BatchRequest.newBuilder()
        .addFilePaths("relative/path/file.txt")
        .build();

    BatchResponse response = service.submitBatch(request, CallContext.none());

    assertEquals(0, response.getAcceptedCount());
  }

  @Test
  void submitBatchHandlesEmptyRequest() {
    BatchRequest request = BatchRequest.newBuilder().build();

    BatchResponse response = service.submitBatch(request, CallContext.none());

    assertEquals(0, response.getAcceptedCount());
  }

  @Test
  void submitBatchRejectsOversizedBatch() {
    // Create a batch larger than MAX_BATCH_SIZE (10,000)
    BatchRequest.Builder builder = BatchRequest.newBuilder();
    for (int i = 0; i < 10_001; i++) {
      builder.addFilePaths("/path/to/file" + i + ".txt");
    }
    BatchRequest request = builder.build();

    // Should reject with INVALID_ARGUMENT
    WorkerServiceException error =
        assertThrows(
            WorkerServiceException.class, () -> service.submitBatch(request, CallContext.none()));
    assertEquals(WorkerServiceException.Status.INVALID_ARGUMENT, error.status());
    assertEquals("Batch size 10001 exceeds maximum 10000", error.getMessage());
  }

  @Test
  void indexStatusReturnsQueueState() throws Exception {
    // Create a real file
    Path file = tempDir.resolve("file.txt");
    Files.writeString(file, "content");

    BatchRequest batchRequest = BatchRequest.newBuilder()
        .addFilePaths(file.toAbsolutePath().toString())
        .build();
    service.submitBatch(batchRequest, CallContext.none());

    StatusResponse response =
        service.indexStatus(StatusRequest.getDefaultInstance(), CallContext.none());

    assertEquals(1, response.getCore().getQueueDepth());
    assertEquals("INDEXING", response.getCore().getState());
    assertTrue(response.getCore().getIsHealthy());
  }

  @Test
  void indexStatusReturnsIdleWhenEmpty() {
    StatusResponse response =
        service.indexStatus(StatusRequest.getDefaultInstance(), CallContext.none());

    assertEquals(0, response.getCore().getQueueDepth());
    assertEquals("IDLE", response.getCore().getState());
    assertTrue(response.getCore().getIsHealthy());
  }

  // ========== SyncDirectory Tests ==========

  @Test
  void syncDirectoryReturnsErrorForBlankRootPath() {
    SyncDirectoryRequest request = SyncDirectoryRequest.newBuilder()
        .setRootPath("")
        .build();

    SyncDirectoryResponse response = service.syncDirectory(request, CallContext.none());

    assertEquals("root_path is required", response.getError());
  }

  @Test
  void syncDirectoryReturnsErrorWhenIndexRuntimeIsNull() {
    // Service was created with indexRuntime = null
    SyncDirectoryRequest request = SyncDirectoryRequest.newBuilder()
        .setRootPath(tempDir.toAbsolutePath().toString())
        .build();

    SyncDirectoryResponse response = service.syncDirectory(request, CallContext.none());

    assertEquals("Index runtime not available", response.getError());
  }

  @Test
  void syncDirectoryReturnsErrorForNonExistentDirectory() throws Exception {
    // Create a service with a mock indexRuntime that returns 0 for prune
    // But directory doesn't exist, so it should fail before reaching indexRuntime
    Path nonExistent = tempDir.resolve("does_not_exist");

    SyncDirectoryRequest request = SyncDirectoryRequest.newBuilder()
        .setRootPath(nonExistent.toAbsolutePath().toString())
        .build();

    // Should fail with "Index runtime not available" first since indexRuntime is null
    // But if indexRuntime was set, it would fail with "does not exist"
    SyncDirectoryResponse response = service.syncDirectory(request, CallContext.none());

    assertTrue(response.getError().contains("not available") ||
               response.getError().contains("does not exist"),
        "Expected error about runtime or directory, got: " + response.getError());
  }

  @Test
  void syncDirectoryNoLongerSkipsOnForegroundActivity() throws Exception {
    // Tempdoc 885 item 3: WorkerSignalBus.isUserActive() — and the syncDirectory skip that used
    // to fire when the user was active and force was false — were retired along with the
    // breath-hold pause; indexing pacing is now a duty cycle the caller cannot observe as a
    // skip. There is no longer a WorkerSignalBus signal to construct an "active" stub from, so
    // this pins the surviving invariant directly: syncDirectory's outcome is the same regardless
    // of `force` when the index runtime is unavailable — no activity-based skip remains for
    // either value.
    Path stubIndexBasePath = tempDir.resolve("indexBase");
    Path stubIndexPath = stubIndexBasePath.resolve("indices").resolve("g-test");
    Files.createDirectories(stubIndexPath);
    WorkerIngestService svc = new WorkerIngestService(
        jobQueue, stubIndexingLoop(), new StubWorkerSignalBus(), IndexingPacing.unthrottled(),
        stubIndexBasePath, stubIndexPath, null, null, null, 0L);

    for (boolean force : new boolean[] {false, true}) {
      SyncDirectoryRequest request = SyncDirectoryRequest.newBuilder()
          .setRootPath(tempDir.toAbsolutePath().toString())
          .setForce(force)
          .build();

      SyncDirectoryResponse response = svc.syncDirectory(request, CallContext.none());

      assertFalse(response.getSkipped(), "force=" + force + ": no activity-based skip remains");
      assertEquals("Index runtime not available", response.getError());
    }
  }

  /**
   * Verify that during SWITCHING state, if switch buffer write fails, UNAVAILABLE is returned.
   *
   * <p>This is critical for ACK-without-durability protection: the caller must retry when
   * switch buffer writes fail during the migration cutover phase.
   */
  @Test
  void submitBatchReturnsUnavailableWhenSwitchBufferWriteFailsDuringSwitching() throws Exception {
    // Close existing setup
    if (jobQueue != null) {
      jobQueue.close();
    }

    // Create a fresh index base path with SWITCHING state
    Path indexBasePath = tempDir.resolve("indexBase");
    Path indicesDir = indexBasePath.resolve("indices");
    Path statePath = indexBasePath.resolve("state.json");
    Path genDir = indicesDir.resolve("g-test");
    Files.createDirectories(genDir);

    // Write a state.json with migration_state = SWITCHING
    String stateJson = """
        {
          "format_version": 2,
          "active_generation": "g-active",
          "building_generation": "g-test",
          "previous_generation": null,
          "migration_state": "SWITCHING",
          "migration_paused": false,
          "pause_reason": null,
          "paused_at_ms": null,
          "updated_at_ms": %d
        }
        """.formatted(System.currentTimeMillis());
    Files.writeString(statePath, stateJson);

    // Create a fresh SqliteJobQueue
    Path dbPath = tempDir.resolve("switching-test.db");
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    // Drop switch_buffer table to simulate SQL failure
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {
      stmt.execute("DROP TABLE switch_buffer");
    }

    // Create service with the SWITCHING indexBasePath
    IndexingLoop stubLoop = stubIndexingLoop();
    WorkerSignalBus stubBus = new StubWorkerSignalBus();
    WorkerIngestService switchingService = new WorkerIngestService(
        jobQueue, stubLoop, stubBus, IndexingPacing.unthrottled(), indexBasePath, genDir,
        null, null, null, 0L);

    // Create a real file for the request
    Path file = tempDir.resolve("testfile.txt");
    Files.writeString(file, "test content");

    BatchRequest request = BatchRequest.newBuilder()
        .addFilePaths(file.toAbsolutePath().toString())
        .build();

    // Call submitBatch - should fail with UNAVAILABLE
    WorkerServiceException error =
        assertThrows(
            WorkerServiceException.class,
            () -> switchingService.submitBatch(request, CallContext.none()),
            "Expected UNAVAILABLE error when switch buffer write fails during SWITCHING");
    assertEquals(WorkerServiceException.Status.UNAVAILABLE, error.status(),
        "Expected UNAVAILABLE status code when switch buffer write fails");
    assertTrue(error.getMessage().contains("Switch buffer write failed"),
        "Error description should mention switch buffer write failure");
  }

  @Test
  void submitBatchReturnsUnavailableWhenSwitchingAndQueueIsNotSqlite() throws Exception {
    Path indexBasePath = tempDir.resolve("indexBase-submit-nonsqlite");
    Path indicesDir = indexBasePath.resolve("indices");
    Path statePath = indexBasePath.resolve("state.json");
    Path genDir = indicesDir.resolve("g-test");
    Files.createDirectories(genDir);

    String stateJson =
        """
        {
          "format_version": 2,
          "active_generation": "g-active",
          "building_generation": "g-test",
          "previous_generation": null,
          "migration_state": "SWITCHING",
          "migration_paused": false,
          "pause_reason": null,
          "paused_at_ms": null,
          "updated_at_ms": %d
        }
        """
            .formatted(System.currentTimeMillis());
    Files.writeString(statePath, stateJson);

    Path file = tempDir.resolve("submit-switching-test.txt");
    Files.writeString(file, "test content");

    JobQueue nonSqliteQueue = new NoopJobQueue();
    WorkerIngestService switchingService =
        new WorkerIngestService(
            nonSqliteQueue, stubIndexingLoop(), new StubWorkerSignalBus(), IndexingPacing.unthrottled(),
            indexBasePath, genDir, null, null, null, 0L);

    BatchRequest request =
        BatchRequest.newBuilder().addFilePaths(file.toAbsolutePath().toString()).build();
    WorkerServiceException error =
        assertThrows(
            WorkerServiceException.class,
            () -> switchingService.submitBatch(request, CallContext.none()),
            "Expected UNAVAILABLE error in SWITCHING when queue is not SqliteJobQueue");

    assertEquals(WorkerServiceException.Status.UNAVAILABLE, error.status());
    assertTrue(error.getMessage().contains("Migration is switching"));
  }

  @Test
  void syncDirectoryReturnsUnavailableWhenSwitchingAndQueueIsNotSqlite() throws Exception {
    Path indexBasePath = tempDir.resolve("indexBase-nonsqlite");
    Path indicesDir = indexBasePath.resolve("indices");
    Path statePath = indexBasePath.resolve("state.json");
    Path genDir = indicesDir.resolve("g-test");
    Files.createDirectories(genDir);

    String stateJson =
        """
        {
          "format_version": 2,
          "active_generation": "g-active",
          "building_generation": "g-test",
          "previous_generation": null,
          "migration_state": "SWITCHING",
          "migration_paused": false,
          "pause_reason": null,
          "paused_at_ms": null,
          "updated_at_ms": %d
        }
        """
            .formatted(System.currentTimeMillis());
    Files.writeString(statePath, stateJson);

    JobQueue nonSqliteQueue = new NoopJobQueue();
    WorkerIngestService switchingService =
        new WorkerIngestService(
            nonSqliteQueue, stubIndexingLoop(), new StubWorkerSignalBus(), IndexingPacing.unthrottled(),
            indexBasePath, genDir, null, null, null, 0L);

    SyncDirectoryRequest request =
        SyncDirectoryRequest.newBuilder().setRootPath(tempDir.toAbsolutePath().toString()).build();
    WorkerServiceException error =
        assertThrows(
            WorkerServiceException.class,
            () -> switchingService.syncDirectory(request, CallContext.none()),
            "Expected UNAVAILABLE error in SWITCHING when queue is not SqliteJobQueue");

    assertEquals(WorkerServiceException.Status.UNAVAILABLE, error.status());
    assertTrue(error.getMessage().contains("Migration is switching"));
  }

  @Test
  void syncDirectoryBuffersSwitchOpWhenSwitchingAndQueueIsSqlite() throws Exception {
    if (jobQueue != null) {
      jobQueue.close();
    }

    Path indexBasePath = tempDir.resolve("indexBase-sync-sqlite");
    Path indicesDir = indexBasePath.resolve("indices");
    Path statePath = indexBasePath.resolve("state.json");
    Path genDir = indicesDir.resolve("g-test");
    Files.createDirectories(genDir);

    String stateJson =
        """
        {
          "format_version": 2,
          "active_generation": "g-active",
          "building_generation": "g-test",
          "previous_generation": null,
          "migration_state": "SWITCHING",
          "migration_paused": false,
          "pause_reason": null,
          "paused_at_ms": null,
          "updated_at_ms": %d
        }
        """
            .formatted(System.currentTimeMillis());
    Files.writeString(statePath, stateJson);

    Path dbPath = tempDir.resolve("switching-sync-sqlite.db");
    SqliteJobQueue sqliteQueue = new SqliteJobQueue(dbPath);
    sqliteQueue.open();
    jobQueue = sqliteQueue;

    WorkerIngestService switchingService =
        new WorkerIngestService(
            sqliteQueue, stubIndexingLoop(), new StubWorkerSignalBus(), IndexingPacing.unthrottled(),
            indexBasePath, genDir, null, null, null, 0L);

    String rootPath = tempDir.toAbsolutePath().toString();
    JobQueue.EnqueueProvenance provenance =
        new JobQueue.EnqueueProvenance("agent", "AGENT_LOOP");
    CallContext caller =
        new CallContext(
            null,
            null,
            CallContext.CancelSignal.NEVER,
            new EngineContext(
                EngineContext.ClientKind.INTERNAL,
                "agent-test",
                Optional.of("session-test"),
                Optional.empty(),
                "UNTRUSTED",
                "AGENT_LOOP",
                EngineContext.Survival.DURABLE,
                EngineContext.Urgency.BACKGROUND),
            provenance);
    SyncDirectoryResponse response =
        switchingService.syncDirectory(
            SyncDirectoryRequest.newBuilder().setRootPath(rootPath).setForce(true).build(),
            caller);

    assertFalse(response.getSkipped());
    assertEquals(0, response.getFilesAdded());
    assertEquals(0, response.getFilesDeleted());
    assertTrue(response.getDeferredToSwitchBuffer());
    assertTrue(response.getError().contains("DEFERRED"));

    String normalizedRoot =
        io.justsearch.indexerworker.util.PathNormalizer.normalizePathPrefix(rootPath);
    String expectedRoot = normalizedRoot == null ? rootPath : normalizedRoot;
    SqliteJobQueue.SwitchBufferOp op =
        sqliteQueue.listSwitchBufferOps().stream()
            .filter(entry -> ("sync_root:" + expectedRoot).equals(entry.key()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing switch_buffer op for sync root"));

    assertEquals("SYNC_ROOT", op.op());
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = JSON.readValue(op.payload(), Map.class);
    assertEquals(expectedRoot, payload.get("root_path"));
    assertEquals(true, payload.get("force"));
    assertEquals(1, payload.get("version"));
    assertEquals("agent", payload.get("originator"));
    assertEquals("AGENT_LOOP", payload.get("transport"));

    switchingService.syncDirectory(
        SyncDirectoryRequest.newBuilder().setRootPath(rootPath).setForce(false).build(), CallContext.none());
    var coalesced = io.justsearch.indexerworker.queue.SwitchBufferSyncRoot.decode(
        sqliteQueue.listSwitchBufferOps().getFirst().payload());
    assertEquals(provenance, coalesced.provenance(), "maintenance must preserve the buffered agent");
    assertFalse(coalesced.force(), "coalescing preserves the incoming work parameters");

    String maintenanceRoot = Files.createDirectories(tempDir.resolve("maintenance-only")).toString();
    switchingService.syncDirectory(
        SyncDirectoryRequest.newBuilder().setRootPath(maintenanceRoot).setForce(true).build(), CallContext.none());
    var maintenancePayload = sqliteQueue.listSwitchBufferOps().stream()
        .map(entry -> JSON.readTree(entry.payload()))
        .filter(node -> node.path("root_path").asText().contains("maintenance-only"))
        .findFirst().orElseThrow();
    assertEquals(1, maintenancePayload.path("version").asInt());
    assertTrue(maintenancePayload.has("originator") && maintenancePayload.get("originator").isNull());
    assertTrue(maintenancePayload.has("transport") && maintenancePayload.get("transport").isNull());
  }

  // ========== File Walking Behavior Tests ==========

  @Test
  void isCloudPlaceholder_returnsFalseForNormalFile() throws Exception {
    Path file = tempDir.resolve("normal.txt");
    Files.writeString(file, "hello");

    // On any platform, a normal file should not be a cloud placeholder
    assertFalse(SyncDirectoryOps.isCloudPlaceholder(file));
  }

  @Test
  void walkFileTree_visitFileFailed_continuesWalk() throws Exception {
    // Create structure: dir/a.txt, dir/sub/b.txt
    Path dir = tempDir.resolve("walk-resilience");
    Path sub = dir.resolve("sub");
    Files.createDirectories(sub);
    Files.writeString(dir.resolve("a.txt"), "a");
    Files.writeString(sub.resolve("b.txt"), "b");

    // Walk with a visitFileFailed that continues (same pattern as production code)
    List<String> visited = new ArrayList<>();
    List<String> failures = new ArrayList<>();

    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            visited.add(file.getFileName().toString());
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            failures.add(file.getFileName().toString());
            return FileVisitResult.CONTINUE;
          }
        });

    // Both files should be visited despite being in different directories
    assertTrue(visited.contains("a.txt"), "Expected a.txt visited, got: " + visited);
    assertTrue(visited.contains("b.txt"), "Expected b.txt visited, got: " + visited);
    assertTrue(failures.isEmpty(), "No failures expected for accessible files");
  }

  @Test
  void walkSkipDirs_skipsSystemDirectories() throws Exception {
    // Create structure with system dirs and a normal dir
    Path root = tempDir.resolve("walk-skip");
    Files.createDirectories(root);

    // System dirs that should be skipped
    Path recycleBin = root.resolve("$Recycle.Bin");
    Path nodeModules = root.resolve("node_modules");
    Path gitDir = root.resolve(".git");
    // Normal dir that should be traversed
    Path docs = root.resolve("docs");

    for (Path d : List.of(recycleBin, nodeModules, gitDir, docs)) {
      Files.createDirectories(d);
      Files.writeString(d.resolve("file.txt"), "content");
    }
    Files.writeString(root.resolve("root.txt"), "content");

    // Walk with the same preVisitDirectory pattern as production code
    List<String> visitedFiles = new ArrayList<>();
    Set<String> skipDirs =
        Set.of(
            "$recycle.bin",
            "system volume information",
            ".git",
            ".svn",
            ".hg",
            "node_modules");

    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (dir.equals(root)) return FileVisitResult.CONTINUE;
            String name =
                dir.getFileName() != null
                    ? dir.getFileName().toString().toLowerCase(Locale.ROOT)
                    : "";
            if (skipDirs.contains(name)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            visitedFiles.add(
                root.relativize(file).toString().replace('\\', '/'));
            return FileVisitResult.CONTINUE;
          }
        });

    // root.txt and docs/file.txt should be visited
    assertTrue(
        visitedFiles.contains("root.txt"), "Expected root.txt visited, got: " + visitedFiles);
    assertTrue(
        visitedFiles.contains("docs/file.txt"),
        "Expected docs/file.txt visited, got: " + visitedFiles);

    // Files inside system dirs should NOT be visited
    assertFalse(
        visitedFiles.stream().anyMatch(f -> f.startsWith("$Recycle.Bin")),
        "Should not visit files in $Recycle.Bin");
    assertFalse(
        visitedFiles.stream().anyMatch(f -> f.startsWith("node_modules")),
        "Should not visit files in node_modules");
    assertFalse(
        visitedFiles.stream().anyMatch(f -> f.startsWith(".git")),
        "Should not visit files in .git");
  }

  @Test
  void countJobsByPathPrefixReturnsZeroForEmptyQueue() {
    CountJobsByPathPrefixRequest request =
        CountJobsByPathPrefixRequest.newBuilder()
            .setPathPrefix(tempDir.resolve("roota").toAbsolutePath().toString())
            .build();

    CountJobsByPathPrefixResponse response =
        service.countJobsByPathPrefix(request, CallContext.none());

    assertEquals(0, response.getCounts().getPendingCount());
    assertEquals(0, response.getCounts().getProcessingCount());
    assertEquals(0, response.getCounts().getFailedCount());
  }

  @Test
  void countJobsByPathPrefixFiltersByPrefix() throws Exception {
    // Two sibling roots; enqueue two PENDING jobs under roota, one under rootb.
    Path rootA = Files.createDirectories(tempDir.resolve("roota"));
    Path rootB = Files.createDirectories(tempDir.resolve("rootb"));
    Path a1 = rootA.resolve("a1.txt");
    Path a2 = rootA.resolve("a2.txt");
    Path b1 = rootB.resolve("b1.txt");
    Files.writeString(a1, "a1");
    Files.writeString(a2, "a2");
    Files.writeString(b1, "b1");
    BatchResponse enqueued =
        service.submitBatch(
            BatchRequest.newBuilder()
                .addFilePaths(a1.toAbsolutePath().toString())
                .addFilePaths(a2.toAbsolutePath().toString())
                .addFilePaths(b1.toAbsolutePath().toString())
                .build(),
            CallContext.none());
    assertEquals(3, enqueued.getAcceptedCount());

    CountJobsByPathPrefixRequest request =
        CountJobsByPathPrefixRequest.newBuilder()
            .setPathPrefix(rootA.toAbsolutePath().toString())
            .build();

    CountJobsByPathPrefixResponse response =
        service.countJobsByPathPrefix(request, CallContext.none());

    // Only roota's two jobs counted; rootb excluded by the prefix boundary.
    assertEquals(2, response.getCounts().getPendingCount());
    assertEquals(0, response.getCounts().getProcessingCount());
    assertEquals(0, response.getCounts().getFailedCount());
  }

  @Test
  void countJobsByPathPrefixDegradesCoverageWhenIndexRuntimeAbsent() throws Exception {
    // Tempdoc 813 Slice A: the queue counts come from SQLite (always available), the coverage
    // counts from Lucene. This fixture constructs the service with a null ingestLifecycle, so the
    // coverage leg must degrade to all-zero WITHOUT costing the caller its job counts — the
    // Library row still needs its truthful "N remaining".
    Path root = Files.createDirectories(tempDir.resolve("degraded"));
    Path f1 = root.resolve("f1.txt");
    Files.writeString(f1, "f1");
    assertEquals(
        1,
        service
            .submitBatch(
                BatchRequest.newBuilder().addFilePaths(f1.toAbsolutePath().toString()).build(),
                CallContext.none())
            .getAcceptedCount());

    CountJobsByPathPrefixResponse response =
        service.countJobsByPathPrefix(
            CountJobsByPathPrefixRequest.newBuilder()
                .setPathPrefix(root.toAbsolutePath().toString())
                .build(),
            CallContext.none());

    assertEquals(1, response.getCounts().getPendingCount(), "queue counts must survive");
    assertEquals(0, response.getCoverage().getParentDocsTotalEmbedding());
    assertEquals(0, response.getCoverage().getParentDocsSettledEmbedding());
    assertEquals(0, response.getCoverage().getParentDocsTotalSplade());
    assertEquals(0, response.getCoverage().getParentDocsSettledSplade());
    assertEquals(0, response.getCoverage().getParentDocsTotalNer());
    assertEquals(0, response.getCoverage().getParentDocsSettledNer());
    assertEquals(0, response.getCoverage().getChunkDocsTotal());
    assertEquals(0, response.getCoverage().getChunkDocsSettled());
  }

  @Test
  void countJobsByPathPrefixDoesNotTreatUnderscoreAsWildcard() throws Exception {
    // Tempdoc 599 Fix 2: the prefix "a_b" must NOT match the sibling "aXb" (a LIKE '_' wildcard
    // would). The range-query implementation matches only true path-prefix descendants.
    Path aUnderscoreB = Files.createDirectories(tempDir.resolve("a_b"));
    Path aXb = Files.createDirectories(tempDir.resolve("aXb"));
    Path f1 = aUnderscoreB.resolve("f1.txt");
    Path f2 = aXb.resolve("f2.txt");
    Files.writeString(f1, "f1");
    Files.writeString(f2, "f2");
    BatchResponse enqueued =
        service.submitBatch(
            BatchRequest.newBuilder()
                .addFilePaths(f1.toAbsolutePath().toString())
                .addFilePaths(f2.toAbsolutePath().toString())
                .build(),
            CallContext.none());
    assertEquals(2, enqueued.getAcceptedCount());

    CountJobsByPathPrefixRequest request =
        CountJobsByPathPrefixRequest.newBuilder()
            .setPathPrefix(aUnderscoreB.toAbsolutePath().toString())
            .build();

    CountJobsByPathPrefixResponse response =
        service.countJobsByPathPrefix(request, CallContext.none());

    // Only a_b's one job — the aXb sibling is excluded (no underscore-as-wildcard bleed).
    assertEquals(1, response.getCounts().getPendingCount());
  }

  /** Stub IndexingLoop for testing - returns sensible defaults. */
  // This service test borrows loop status only; do not construct an unused extractor owner.
  private static IndexingLoop stubIndexingLoop() {
    IndexingLoop loop = org.mockito.Mockito.mock(IndexingLoop.class);
    org.mockito.Mockito.when(loop.getLastCommitTime()).thenAnswer(ignored -> System.currentTimeMillis());
    org.mockito.Mockito.when(loop.getCurrentState()).thenReturn("IDLE");
    return loop;
  }

  /** Minimal non-SQLite queue for SWITCHING fallback-path tests. */
  private static final class NoopJobQueue implements JobQueue {
    @Override
    public void open() {}

    @Override
    public int enqueue(List<Path> paths, String collection) {
      return paths == null ? 0 : paths.size();
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
    public long queueDepth() {
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

  /** Stub WorkerSignalBus for testing - returns sensible defaults. */
  private static final class StubWorkerSignalBus implements WorkerSignalBus {
    private final long startupTime = System.currentTimeMillis();

    @Override
    public void open() {}





    @Override
    public boolean isMainGpuActive() {
      return false;  // Assume GPU is free in tests
    }

    @Override
    public long startupTime() {
      return startupTime;
    }

    @Override
    public void close() {}
  }

}
