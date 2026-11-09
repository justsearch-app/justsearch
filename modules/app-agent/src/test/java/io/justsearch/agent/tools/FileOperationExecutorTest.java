package io.justsearch.agent.tools;

import io.justsearch.core.context.EngineContext;
import io.justsearch.agent.EngineContextTestFixtures;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileOperationExecutorTest {

  @TempDir Path tempDir;

  private Path root;
  private Path dataDir;
  private FileOperationExecutor executor;
  private int indexUpdateCount;
  private Map<Path, Path> capturedPathMappings;

  @BeforeEach
  void setUp() throws IOException {
    root = tempDir.resolve("indexed");
    Files.createDirectories(root);
    dataDir = tempDir.resolve("data");
    var log = new FileOperationLog(dataDir.resolve("file-operations"));
    indexUpdateCount = 0;
    capturedPathMappings = null;
    executor =
        new FileOperationExecutor(
            context -> List.of(root),
            (pathMappings, context) -> {
              indexUpdateCount += pathMappings.size();
              capturedPathMappings = new java.util.HashMap<>(pathMappings);
              return pathMappings.size();
            },
            log);
  }

  // ===== Validation tests =====

  @Test
  void validateSourceWithinRoots() throws IOException {
    Path src = root.resolve("a.txt");
    Files.writeString(src, "hello");
    Path dest = root.resolve("b.txt");

    var report = executor.validate(List.of(new FileOperation(FileOperation.OpType.MOVE, src, dest)), EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(report.allValid(), "Move within roots should be valid");
  }

  @Test
  void validateSourceOutsideRootsIsInvalid() throws IOException {
    Path outside = tempDir.resolve("outside.txt");
    Files.writeString(outside, "data");
    Path dest = root.resolve("inside.txt");

    var report =
        executor.validate(List.of(new FileOperation(FileOperation.OpType.MOVE, outside, dest)), EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(report.allValid());
    assertTrue(report.summary().contains("SOURCE_NOT_SANDBOXED"));
  }

  @Test
  void validateDestOutsideRootsIsInvalid() throws IOException {
    Path src = root.resolve("a.txt");
    Files.writeString(src, "data");
    Path dest = tempDir.resolve("outside.txt");

    var report =
        executor.validate(List.of(new FileOperation(FileOperation.OpType.MOVE, src, dest)), EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(report.allValid());
    assertTrue(report.summary().contains("DEST_NOT_SANDBOXED"));
  }

  @Test
  void validateSourceMissingIsInvalid() {
    Path src = root.resolve("nonexistent.txt");
    Path dest = root.resolve("b.txt");

    var report =
        executor.validate(List.of(new FileOperation(FileOperation.OpType.MOVE, src, dest)), EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(report.allValid());
    assertTrue(report.summary().contains("SOURCE_MISSING"));
  }

  @Test
  void validateSourceRequiredForMoveRenameAndCopy() {
    Path dest = root.resolve("b.txt");

    for (var opType :
        List.of(FileOperation.OpType.MOVE, FileOperation.OpType.RENAME, FileOperation.OpType.COPY)) {
      var report = executor.validate(List.of(new FileOperation(opType, null, dest)), EngineContextTestFixtures.AGENT_LOOP);
      assertFalse(report.allValid(), opType + " without source should be invalid");
      assertTrue(report.summary().contains("SOURCE_REQUIRED"));
    }
  }

  @Test
  void validateMkdirDoesNotRequireSource() {
    Path dest = root.resolve("newdir");
    var report =
        executor.validate(List.of(new FileOperation(FileOperation.OpType.MKDIR, null, dest)), EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(report.allValid(), "MKDIR should not require source");
  }

  @Test
  void validateDestExistsIsConflict() throws IOException {
    Path src = root.resolve("a.txt");
    Files.writeString(src, "data");
    Path dest = root.resolve("b.txt");
    Files.writeString(dest, "existing");

    var report =
        executor.validate(List.of(new FileOperation(FileOperation.OpType.MOVE, src, dest)), EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(report.allValid());
    assertTrue(report.summary().contains("DEST_EXISTS"));
  }

  @Test
  void validateMkdirIdempotentWhenDestExists() throws IOException {
    Path dest = root.resolve("existing-dir");
    Files.createDirectories(dest);

    var report =
        executor.validate(List.of(new FileOperation(FileOperation.OpType.MKDIR, null, dest)), EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(report.allValid(), "MKDIR should be idempotent");
  }

  @Test
  void validateDestParentMissing() throws IOException {
    Path src = root.resolve("a.txt");
    Files.writeString(src, "data");
    Path dest = root.resolve("no-such-parent").resolve("b.txt");

    var report =
        executor.validate(List.of(new FileOperation(FileOperation.OpType.MOVE, src, dest)), EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(report.allValid());
    assertTrue(report.summary().contains("DEST_PARENT_MISSING"));
  }

  // ===== Execution tests =====

  @Test
  void executeMoveFile() throws IOException {
    Path src = root.resolve("source.txt");
    Files.writeString(src, "content");
    Path dest = root.resolve("moved.txt");

    var report =
        executor.execute(
            List.of(new FileOperation(FileOperation.OpType.MOVE, src, dest)), "Test move", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(report.allSucceeded());
    assertFalse(Files.exists(src), "Source should be removed after move");
    assertTrue(Files.exists(dest), "Destination should exist after move");
    assertEquals("content", Files.readString(dest));
    assertEquals(1, indexUpdateCount, "Index should be updated for MOVE");
    assertNotNull(report.batchId());
  }

  @Test
  void executeRenameFile() throws IOException {
    Path src = root.resolve("old-name.txt");
    Files.writeString(src, "renamed");
    Path dest = root.resolve("new-name.txt");

    var report =
        executor.execute(
            List.of(new FileOperation(FileOperation.OpType.RENAME, src, dest)), "Test rename", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(report.allSucceeded());
    assertFalse(Files.exists(src));
    assertEquals("renamed", Files.readString(dest));
    assertEquals(1, indexUpdateCount, "Index should be updated for RENAME");
  }

  @Test
  void executeMkdir() {
    Path dest = root.resolve("new-dir");

    var report =
        executor.execute(
            List.of(new FileOperation(FileOperation.OpType.MKDIR, null, dest)), "Test mkdir", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(report.allSucceeded());
    assertTrue(Files.isDirectory(dest));
    assertEquals(0, indexUpdateCount, "MKDIR should not trigger index update");
  }

  @Test
  void executeMkdirIdempotent() throws IOException {
    Path dest = root.resolve("existing-dir");
    Files.createDirectories(dest);

    var report =
        executor.execute(
            List.of(new FileOperation(FileOperation.OpType.MKDIR, null, dest)), "Idempotent mkdir", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(report.allSucceeded());
    assertTrue(Files.isDirectory(dest));
  }

  @Test
  void executeCopyFile() throws IOException {
    Path src = root.resolve("original.txt");
    Files.writeString(src, "copy me");
    Path dest = root.resolve("copy.txt");

    var report =
        executor.execute(
            List.of(new FileOperation(FileOperation.OpType.COPY, src, dest)), "Test copy", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(report.allSucceeded());
    assertTrue(Files.exists(src), "Source should still exist after copy");
    assertEquals("copy me", Files.readString(dest));
    assertEquals(0, indexUpdateCount, "COPY should not trigger index update");
  }

  @Test
  void executeCopyDirectory() throws IOException {
    Path srcDir = root.resolve("src-dir");
    Files.createDirectories(srcDir);
    Files.writeString(srcDir.resolve("a.txt"), "aaa");
    Files.createDirectories(srcDir.resolve("sub"));
    Files.writeString(srcDir.resolve("sub").resolve("b.txt"), "bbb");

    Path destDir = root.resolve("dest-dir");

    var report =
        executor.execute(
            List.of(new FileOperation(FileOperation.OpType.COPY, srcDir, destDir)),
            "Copy directory", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(report.allSucceeded());
    assertTrue(Files.exists(destDir.resolve("a.txt")));
    assertEquals("aaa", Files.readString(destDir.resolve("a.txt")));
    assertTrue(Files.exists(destDir.resolve("sub").resolve("b.txt")));
    assertEquals("bbb", Files.readString(destDir.resolve("sub").resolve("b.txt")));
    assertEquals(0, indexUpdateCount, "COPY should not trigger index update");
  }

  @Test
  void executeFailFastOnError() throws IOException {
    Path src1 = root.resolve("file1.txt");
    Files.writeString(src1, "ok");
    Path dest1 = root.resolve("moved1.txt");

    // Op 2 will fail: source doesn't exist
    Path src2 = root.resolve("nonexistent.txt");
    Path dest2 = root.resolve("moved2.txt");

    Path src3 = root.resolve("file3.txt");
    Files.writeString(src3, "should not run");
    Path dest3 = root.resolve("moved3.txt");

    var report =
        executor.execute(
            List.of(
                new FileOperation(FileOperation.OpType.MOVE, src1, dest1),
                new FileOperation(FileOperation.OpType.MOVE, src2, dest2),
                new FileOperation(FileOperation.OpType.MOVE, src3, dest3)),
            "Fail-fast test", EngineContextTestFixtures.AGENT_LOOP);

    assertFalse(report.allSucceeded());
    assertEquals(1, report.successCount(), "Only first op should succeed");
    assertEquals(1, report.failureCount(), "Second op should fail");
    assertEquals(2, report.results().size(), "Third op should not be attempted");
    assertTrue(Files.exists(src3), "Third file should not have been moved");
  }

  @Test
  void executeMultipleOpsSequentially() throws IOException {
    Path dir = root.resolve("newdir");
    Path src = root.resolve("file.txt");
    Files.writeString(src, "content");
    Path dest = root.resolve("newdir").resolve("file.txt");

    var report =
        executor.execute(
            List.of(
                new FileOperation(FileOperation.OpType.MKDIR, null, dir),
                new FileOperation(FileOperation.OpType.MOVE, src, dest)),
            "Multi-op", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(report.allSucceeded());
    assertEquals(2, report.successCount());
    assertTrue(Files.exists(dest));
  }

  @Test
  void executeDirectoryMoveCollectsChildPathMappings() throws IOException {
    // Create a directory with multiple files
    Path srcDir = root.resolve("src-folder");
    Files.createDirectories(srcDir.resolve("sub"));
    Files.writeString(srcDir.resolve("a.txt"), "aaa");
    Files.writeString(srcDir.resolve("b.txt"), "bbb");
    Files.writeString(srcDir.resolve("sub").resolve("c.txt"), "ccc");

    Path destDir = root.resolve("dest-folder");

    var report =
        executor.execute(
            List.of(new FileOperation(FileOperation.OpType.MOVE, srcDir, destDir)),
            "Dir move", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(report.allSucceeded(), report.results().toString());
    assertFalse(Files.exists(srcDir), "Source dir should be removed");
    assertTrue(Files.exists(destDir.resolve("a.txt")));
    assertTrue(Files.exists(destDir.resolve("sub").resolve("c.txt")));
    // 3 files in the directory should each generate an index update
    assertEquals(3, indexUpdateCount, "All child files should trigger index updates");

    // Verify actual path mappings (not just count)
    assertNotNull(capturedPathMappings, "Path mappings should be captured");
    assertEquals(3, capturedPathMappings.size());
    assertEquals(
        destDir.resolve("a.txt"),
        capturedPathMappings.get(srcDir.resolve("a.txt")),
        "a.txt mapping");
    assertEquals(
        destDir.resolve("b.txt"),
        capturedPathMappings.get(srcDir.resolve("b.txt")),
        "b.txt mapping");
    assertEquals(
        destDir.resolve("sub").resolve("c.txt"),
        capturedPathMappings.get(srcDir.resolve("sub").resolve("c.txt")),
        "sub/c.txt mapping");
  }

  // ===== Conflict strategy tests =====

  @Test
  void validateSkipStrategyPassesOnDestExists() throws IOException {
    Path src = root.resolve("a.txt");
    Files.writeString(src, "data");
    Path dest = root.resolve("b.txt");
    Files.writeString(dest, "existing");

    var report =
        executor.validate(
            List.of(new FileOperation(FileOperation.OpType.MOVE, src, dest)),
            ConflictStrategy.SKIP, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(report.allValid(), "SKIP strategy should pass validation even with DEST_EXISTS");
  }

  @Test
  void validateAutoSuffixStrategyPassesOnDestExists() throws IOException {
    Path src = root.resolve("a.txt");
    Files.writeString(src, "data");
    Path dest = root.resolve("b.txt");
    Files.writeString(dest, "existing");

    var report =
        executor.validate(
            List.of(new FileOperation(FileOperation.OpType.MOVE, src, dest)),
            ConflictStrategy.AUTO_SUFFIX, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(
        report.allValid(), "AUTO_SUFFIX strategy should pass validation even with DEST_EXISTS");
  }

  @Test
  void validateFailStrategyRejectsDestExists() throws IOException {
    Path src = root.resolve("a.txt");
    Files.writeString(src, "data");
    Path dest = root.resolve("b.txt");
    Files.writeString(dest, "existing");

    var report =
        executor.validate(
            List.of(new FileOperation(FileOperation.OpType.MOVE, src, dest)),
            ConflictStrategy.FAIL, EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(report.allValid());
    assertTrue(report.summary().contains("DEST_EXISTS"));
  }

  @Test
  void executeSkipStrategySkipsConflictingOp() throws IOException {
    Path src = root.resolve("src.txt");
    Files.writeString(src, "new data");
    Path dest = root.resolve("dest.txt");
    Files.writeString(dest, "existing data");

    var report =
        executor.execute(
            List.of(new FileOperation(FileOperation.OpType.MOVE, src, dest)),
            "Skip test",
            ConflictStrategy.SKIP, EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(report.allSucceeded(), "Skipped ops are still considered success");
    assertEquals(0, report.successCount(), "No operations should actually succeed");
    assertEquals(1, report.skippedCount(), "One operation should be skipped");
    assertTrue(Files.exists(src), "Source should remain when skipped");
    assertEquals("existing data", Files.readString(dest), "Destination should be unchanged");
    assertEquals(0, indexUpdateCount, "No index update for skipped ops");
  }

  @Test
  void executeSkipStrategySummaryMentionsSkipped() throws IOException {
    Path src = root.resolve("src.txt");
    Files.writeString(src, "data");
    Path dest = root.resolve("dest.txt");
    Files.writeString(dest, "existing");

    var report =
        executor.execute(
            List.of(new FileOperation(FileOperation.OpType.MOVE, src, dest)),
            "Summary test",
            ConflictStrategy.SKIP, EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(report.summary().contains("skipped"), "Summary should mention skipped: " + report.summary());
  }

  @Test
  void executeAutoSuffixCreatesUniqueFile() throws IOException {
    Path src = root.resolve("src.txt");
    Files.writeString(src, "new content");
    Path dest = root.resolve("dest.txt");
    Files.writeString(dest, "existing");

    var report =
        executor.execute(
            List.of(new FileOperation(FileOperation.OpType.MOVE, src, dest)),
            "Auto suffix test",
            ConflictStrategy.AUTO_SUFFIX, EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(report.allSucceeded(), report.summary());
    assertEquals(1, report.successCount());
    assertEquals(0, report.skippedCount());
    assertFalse(Files.exists(src), "Source should be moved");
    assertTrue(Files.exists(dest), "Original dest should remain");
    assertEquals("existing", Files.readString(dest));

    Path suffixed = root.resolve("dest (1).txt");
    assertTrue(Files.exists(suffixed), "Auto-suffixed file should exist");
    assertEquals("new content", Files.readString(suffixed));
  }

  @Test
  void executeAutoSuffixPreservesExtension() throws IOException {
    Path src = root.resolve("src.data.tar.gz");
    Files.writeString(src, "archive");
    Path dest = root.resolve("archive.tar.gz");
    Files.writeString(dest, "existing archive");

    var report =
        executor.execute(
            List.of(new FileOperation(FileOperation.OpType.MOVE, src, dest)),
            "Extension test",
            ConflictStrategy.AUTO_SUFFIX, EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(report.allSucceeded(), report.summary());
    // resolveUniqueName splits at last dot: "archive.tar" + ".gz"
    Path suffixed = root.resolve("archive.tar (1).gz");
    assertTrue(Files.exists(suffixed), "Suffixed file should preserve last extension: " + suffixed);
  }

  @Test
  void executeAutoSuffixNoExtension() throws IOException {
    Path src = root.resolve("src-noext");
    Files.writeString(src, "data");
    Path dest = root.resolve("dest-noext");
    Files.writeString(dest, "existing");

    var report =
        executor.execute(
            List.of(new FileOperation(FileOperation.OpType.MOVE, src, dest)),
            "No ext test",
            ConflictStrategy.AUTO_SUFFIX, EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(report.allSucceeded(), report.summary());
    Path suffixed = root.resolve("dest-noext (1)");
    assertTrue(Files.exists(suffixed), "Suffixed file without extension: " + suffixed);
    assertEquals("data", Files.readString(suffixed));
  }

  @Test
  void executeAutoSuffixIncrementsWhenMultipleConflicts() throws IOException {
    // Create dest.txt, dest (1).txt, dest (2).txt
    Path dest = root.resolve("dest.txt");
    Files.writeString(dest, "original");
    Files.writeString(root.resolve("dest (1).txt"), "conflict 1");
    Files.writeString(root.resolve("dest (2).txt"), "conflict 2");

    Path src = root.resolve("src.txt");
    Files.writeString(src, "new data");

    var report =
        executor.execute(
            List.of(new FileOperation(FileOperation.OpType.MOVE, src, dest)),
            "Multi conflict test",
            ConflictStrategy.AUTO_SUFFIX, EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(report.allSucceeded(), report.summary());
    Path suffixed = root.resolve("dest (3).txt");
    assertTrue(Files.exists(suffixed), "Should use next available suffix (3)");
    assertEquals("new data", Files.readString(suffixed));
  }

  @Test
  void executeMkdirNotAffectedByConflictStrategy() throws IOException {
    // MKDIR is idempotent — conflict strategy should not change behavior
    Path dest = root.resolve("existing-dir");
    Files.createDirectories(dest);

    var report =
        executor.execute(
            List.of(new FileOperation(FileOperation.OpType.MKDIR, null, dest)),
            "Mkdir idempotent",
            ConflictStrategy.AUTO_SUFFIX, EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(report.allSucceeded());
    assertEquals(1, report.successCount());
    assertEquals(0, report.skippedCount());
  }

  @Test
  void validatePathTraversalRejected() throws IOException {
    Path src = root.resolve("legit.txt");
    Files.writeString(src, "data");
    // Use .. to try to escape sandbox
    Path dest = root.resolve("sub").resolve("..").resolve("..").resolve("outside.txt");

    var report =
        executor.validate(List.of(new FileOperation(FileOperation.OpType.MOVE, src, dest)), EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(report.allValid(), "Path traversal should be rejected");
    assertTrue(
        report.summary().contains("DEST_NOT_SANDBOXED"),
        "Should specifically fail sandbox check: " + report.summary());
  }

  // ===== isWithinIndexedRoots: the undo-path containment check (tempdoc 875 §C.6) =====
  // Its javadoc promises it fails closed for every root unavailability. The roots supplier is the
  // live indexing service, so "the Worker is down" arrives here as a throwing or null-returning
  // supplier — the adverse preconditions the happy-path tests can never reach
  // (`green-masked-destructive`).

  private FileOperationExecutor executorWithRootsSupplier(
      java.util.function.Function<EngineContext, List<Path>> supplier) throws IOException {
    return new FileOperationExecutor(
        supplier,
        (pathMappings, context) -> pathMappings.size(),
        new FileOperationLog(dataDir.resolve("file-operations-adverse")));
  }

  @Test
  void isWithinIndexedRootsAcceptsAnInRootPath() throws IOException {
    Path file = root.resolve("a.txt");
    Files.writeString(file, "data");

    assertTrue(
        executor.isWithinIndexedRoots(file, EngineContextTestFixtures.AGENT_LOOP),
        "Precondition: an in-root path is accepted, so the adverse cases below fail for the "
            + "supplier, not for the path");
  }

  @Test
  void isWithinIndexedRootsFailsClosedWhenTheRootsSupplierThrows() throws IOException {
    Path file = root.resolve("a.txt");
    Files.writeString(file, "data");
    FileOperationExecutor adverse =
        executorWithRootsSupplier(
            context -> {
              throw new IllegalStateException("Worker unavailable");
            });

    assertFalse(
        adverse.isWithinIndexedRoots(file, EngineContextTestFixtures.AGENT_LOOP),
        "A throwing roots supplier must read as 'containment not proven', not propagate");
  }

  @Test
  void isWithinIndexedRootsFailsClosedWhenTheRootsSupplierReturnsNull() throws IOException {
    Path file = root.resolve("a.txt");
    Files.writeString(file, "data");
    FileOperationExecutor adverse = executorWithRootsSupplier(context -> null);

    assertFalse(
        adverse.isWithinIndexedRoots(file, EngineContextTestFixtures.AGENT_LOOP),
        "A null roots list must read as 'containment not proven', not NPE");
  }

  @Test
  void isWithinIndexedRootsFailsClosedWhenNoRootsAreConfigured() throws IOException {
    Path file = root.resolve("a.txt");
    Files.writeString(file, "data");
    FileOperationExecutor adverse = executorWithRootsSupplier(context -> List.of());

    assertFalse(adverse.isWithinIndexedRoots(file, EngineContextTestFixtures.AGENT_LOOP), "No roots means nothing is contained");
  }
}
