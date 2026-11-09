package io.justsearch.agent.tools;

import io.justsearch.core.context.EngineContext;
import io.justsearch.agent.EngineContextTestFixtures;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import io.justsearch.configuration.PlatformPaths;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
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
  private FileOperationLog log;
  private int indexUpdateCount;
  private Map<Path, Path> capturedPathMappings;

  @BeforeEach
  void setUp() throws IOException {
    root = tempDir.resolve("indexed");
    Files.createDirectories(root);
    dataDir = tempDir.resolve("data");
    log = new FileOperationLog(dataDir.resolve("file-operations"));
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

  @Test
  void d1FailRefusesDestinationCreatedAfterValidation() throws IOException {
    for (var type : List.of(FileOperation.OpType.MOVE, FileOperation.OpType.RENAME)) {
      Path source = Files.writeString(root.resolve(type + "-source"), "source bytes");
      Path destination = root.resolve(type + "-destination");
      var operations = List.of(new FileOperation(type, source, destination));
      assertTrue(executor.validate(operations, EngineContextTestFixtures.AGENT_LOOP).allValid());
      Files.writeString(destination, "late occupant");

      var report = executor.execute(operations, "Late conflict", EngineContextTestFixtures.AGENT_LOOP);

      assertFalse(report.allSucceeded(), "An occupied destination must refuse the move");
      assertEquals("late occupant", Files.readString(destination));
      assertEquals("source bytes", Files.readString(source));
      assertEquals(0, indexUpdateCount);
    }
  }

  @Test
  void d2FailValidationRefusesDanglingDestination() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "Symbolic links require privileges on Windows");
    Path source = Files.writeString(root.resolve("source"), "source bytes");
    Path destination = root.resolve("destination");
    Path missing = root.resolve("missing");
    Files.createSymbolicLink(destination, missing);

    var report = executor.validate(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)),
        EngineContextTestFixtures.AGENT_LOOP);

    assertFalse(report.allValid(), "A dangling link occupies its name");
    assertEquals("DEST_EXISTS", report.results().getFirst().errorCode());
    assertEquals(missing, Files.readSymbolicLink(destination));
  }

  @Test
  void d3SkipPreservesDanglingDestination() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "Symbolic links require privileges on Windows");
    Path source = Files.writeString(root.resolve("source"), "source bytes");
    Path destination = root.resolve("destination");
    Path missing = root.resolve("missing");
    Files.createSymbolicLink(destination, missing);

    var report = executor.execute(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "Skip link",
        ConflictStrategy.SKIP, EngineContextTestFixtures.AGENT_LOOP);

    assertEquals(1, report.skippedCount(), "A dangling link must be skipped");
    assertEquals(missing, Files.readSymbolicLink(destination));
    assertEquals("source bytes", Files.readString(source));
    assertEquals(0, indexUpdateCount);
  }

  @Test
  void d3AutoSuffixSkipsDanglingNames() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "Symbolic links require privileges on Windows");
    Path source = Files.writeString(root.resolve("source"), "source bytes");
    Path destination = root.resolve("destination.txt");
    Path firstSuffix = root.resolve("destination (1).txt");
    Path missing = root.resolve("missing");
    Files.createSymbolicLink(destination, missing);
    Files.createSymbolicLink(firstSuffix, missing);

    var report = executor.execute(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "Suffix link",
        ConflictStrategy.AUTO_SUFFIX, EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(report.allSucceeded(), report.summary());
    assertEquals(missing, Files.readSymbolicLink(destination));
    assertEquals(missing, Files.readSymbolicLink(firstSuffix));
    assertEquals("source bytes", Files.readString(root.resolve("destination (2).txt")));
    assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void d9SymbolicSourceNeverReplacesAnOccupant() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "Symbolic links require privileges on Windows");
    Path target = Files.writeString(root.resolve("target"), "target bytes");
    Path source = Files.createSymbolicLink(root.resolve("source"), target);
    Path destination = Files.writeString(root.resolve("destination"), "occupant bytes");

    var report = executor.execute(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "Link conflict",
        EngineContextTestFixtures.AGENT_LOOP);

    assertFalse(report.allSucceeded(), "Moving a symbolic link must refuse an occupant");
    assertEquals("occupant bytes", Files.readString(destination));
    assertEquals(target, Files.readSymbolicLink(source));
    assertEquals("target bytes", Files.readString(target));
    assertEquals(0, indexUpdateCount);
  }

  @Test
  void d9DanglingSymbolicSourceMovesAsALink() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "Symbolic links require privileges on Windows");
    Path missing = root.resolve("missing");
    Path source = Files.createSymbolicLink(root.resolve("source"), missing);
    Path destination = root.resolve("destination");
    var operations = List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination));
    assertTrue(executor.validate(operations, EngineContextTestFixtures.AGENT_LOOP).allValid());

    var report = executor.execute(operations, "Dangling source", EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(report.allSucceeded(), report.summary());
    assertEquals(missing, Files.readSymbolicLink(destination));
    assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
    assertFalse(Files.exists(missing));
    assertEquals(Map.of(source, destination), capturedPathMappings);
  }

  @Test
  void d9DirectorySymbolicSourceMovesOnlyTheLink() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "Symbolic links require privileges on Windows");
    Path target = Files.createDirectory(root.resolve("target"));
    Path child = Files.writeString(target.resolve("child"), "target bytes");
    Path source = Files.createSymbolicLink(root.resolve("source"), target);
    Path destination = root.resolve("destination");

    var report = executor.execute(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "Directory link",
        EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(report.allSucceeded(), report.summary());
    assertEquals(target, Files.readSymbolicLink(destination));
    assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
    assertEquals("target bytes", Files.readString(child));
    assertEquals(Map.of(source, destination), capturedPathMappings);
  }

  @Test
  void p1MoveAndRenamePreserveMetadata() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "POSIX permissions checked on Unix");
    for (var type : List.of(FileOperation.OpType.MOVE, FileOperation.OpType.RENAME)) {
      Path source = Files.writeString(root.resolve(type + "-source"), "preserved bytes");
      Path destination = root.resolve(type + "-destination");
      var permissions = PosixFilePermissions.fromString("rw-r-----");
      Files.setPosixFilePermissions(source, permissions);
      Files.setLastModifiedTime(source, FileTime.fromMillis(1_600_000_000_000L));
      FileTime modified = Files.getLastModifiedTime(source);

      var report = executor.execute(
          List.of(new FileOperation(type, source, destination)), "Metadata",
          EngineContextTestFixtures.AGENT_LOOP);

      assertTrue(report.allSucceeded(), report.summary());
      assertEquals("preserved bytes", Files.readString(destination));
      assertEquals(modified, Files.getLastModifiedTime(destination));
      assertEquals(permissions, Files.getPosixFilePermissions(destination));
      assertFalse(Files.exists(source));
    }
  }

  @Test
  void p2MoveOntoNonEmptyDirectoryLeavesBothTrees() throws IOException {
    Path source = Files.createDirectory(root.resolve("source"));
    Path destination = Files.createDirectory(root.resolve("destination"));
    Files.writeString(source.resolve("source-child"), "source bytes");
    Files.writeString(destination.resolve("destination-child"), "occupant bytes");

    var report = executor.execute(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "Directory conflict",
        EngineContextTestFixtures.AGENT_LOOP);

    assertFalse(report.allSucceeded());
    assertEquals("source bytes", Files.readString(source.resolve("source-child")));
    assertEquals("occupant bytes", Files.readString(destination.resolve("destination-child")));
    assertEquals(0, indexUpdateCount);
  }

  private void useMoveFiles(FileOperationExecutor.MoveFileSystem moveFiles) {
    executor = new FileOperationExecutor(
        context -> List.of(root),
        (mappings, context) -> {
          indexUpdateCount += mappings.size();
          capturedPathMappings = new java.util.HashMap<>(mappings);
          return mappings.size();
        }, log, moveFiles);
  }

  @Test
  void d4AutoSuffixRefusesLateOccupantWithoutRetry() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "Hard-link route is Unix-only");
    Path source = Files.writeString(root.resolve("source"), "source bytes");
    Path destination = Files.writeString(root.resolve("destination.txt"), "original occupant");
    Path chosen = root.resolve("destination (1).txt");
    useMoveFiles(new FileOperationExecutor.MoveFileSystem() {
      @Override
      void createLink(Path target, Path from) throws IOException {
        assertEquals(chosen, target, "Only the first chosen name is attempted");
        Files.writeString(target, "late occupant");
        super.createLink(target, from);
      }
    });

    var report = executor.execute(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "Suffix race",
        ConflictStrategy.AUTO_SUFFIX, EngineContextTestFixtures.AGENT_LOOP);

    assertFalse(report.allSucceeded());
    assertEquals("late occupant", Files.readString(chosen));
    assertEquals("original occupant", Files.readString(destination));
    assertEquals("source bytes", Files.readString(source));
    assertFalse(Files.exists(root.resolve("destination (2).txt")));
    assertEquals(0, indexUpdateCount);
  }

  @Test
  void d5UnavailableHardLinkUsesCopyAndDelete() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "Hard-link route is Unix-only");
    Path source = Files.writeString(root.resolve("source"), "source bytes");
    Path destination = root.resolve("destination");
    Files.setLastModifiedTime(source, FileTime.fromMillis(1_600_000_000_000L));
    FileTime modified = Files.getLastModifiedTime(source);
    var permissions = Files.getPosixFilePermissions(source);
    useMoveFiles(new FileOperationExecutor.MoveFileSystem() {
      @Override
      void createLink(Path target, Path from) throws IOException {
        throw new AccessDeniedException(target.toString());
      }
    });

    var report = executor.execute(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "No link",
        EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(report.allSucceeded(), report.summary());
    assertEquals("source bytes", Files.readString(destination));
    assertEquals(modified, Files.getLastModifiedTime(destination));
    assertEquals(permissions, Files.getPosixFilePermissions(destination));
    assertFalse(Files.exists(source));
    assertEquals(Map.of(source, destination), capturedPathMappings);
  }

  @Test
  void d5UnavailableHardLinkCopyRefusesAnOccupiedTarget() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "Hard-link route is Unix-only");
    Path source = Files.writeString(root.resolve("source"), "source bytes");
    Path destination = root.resolve("destination");
    useMoveFiles(new FileOperationExecutor.MoveFileSystem() {
      @Override
      void createLink(Path target, Path from) throws IOException {
        Files.writeString(target, "late occupant");
        throw new UnsupportedOperationException("Hard links unavailable");
      }
    });

    var report = executor.execute(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "No link conflict",
        EngineContextTestFixtures.AGENT_LOOP);

    assertFalse(report.allSucceeded());
    assertEquals("late occupant", Files.readString(destination));
    assertEquals("source bytes", Files.readString(source));
    assertEquals(0, indexUpdateCount);
  }

  @Test
  void d6DeleteFailureLeavesBothNames() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "Hard-link route is Unix-only");
    Path source = Files.writeString(root.resolve("source"), "source bytes");
    Path destination = root.resolve("destination");
    useMoveFiles(new FileOperationExecutor.MoveFileSystem() {
      @Override
      void delete(Path path) throws IOException {
        throw new AccessDeniedException(path.toString());
      }
    });

    var report = executor.execute(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "Delete failure",
        EngineContextTestFixtures.AGENT_LOOP);

    assertFalse(report.allSucceeded());
    assertEquals("source bytes", Files.readString(source));
    assertEquals("source bytes", Files.readString(destination));
    assertTrue(Files.isSameFile(source, destination));
    assertTrue(report.results().getFirst().errorMessage().contains("source " + source + " remains=true"));
    assertTrue(report.results().getFirst().errorMessage().contains("destination " + destination + " remains=true"));
    assertEquals(0, indexUpdateCount);
    assertNull(capturedPathMappings);
  }

  @Test
  void d6DeleteFailurePreservesAReplacedDestination() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "Hard-link route is Unix-only");
    Path source = Files.writeString(root.resolve("source"), "source bytes");
    Path destination = root.resolve("destination");
    useMoveFiles(new FileOperationExecutor.MoveFileSystem() {
      @Override
      void delete(Path path) throws IOException {
        Files.delete(destination);
        Files.writeString(destination, "replacement bytes");
        throw new AccessDeniedException(path.toString());
      }
    });

    var report = executor.execute(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "Delete failure race",
        EngineContextTestFixtures.AGENT_LOOP);

    assertFalse(report.allSucceeded());
    assertEquals("source bytes", Files.readString(source));
    assertEquals("replacement bytes", Files.readString(destination));
    assertTrue(report.results().getFirst().errorMessage().contains("source " + source + " remains=true"));
    assertTrue(report.results().getFirst().errorMessage().contains("destination " + destination + " remains=true"));
    assertEquals(0, indexUpdateCount);
    assertNull(capturedPathMappings);
  }

  @Test
  void d7ReplacedSourceIsNeverDeleted() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "Hard-link route is Unix-only");
    Path source = Files.writeString(root.resolve("source"), "source bytes");
    Path destination = root.resolve("destination");
    Path retained = root.resolve("retained-original");
    useMoveFiles(new FileOperationExecutor.MoveFileSystem() {
      @Override
      void createLink(Path target, Path from) throws IOException {
        super.createLink(target, from);
        Files.move(from, retained);
        Files.writeString(from, "replacement source");
      }
    });

    var report = executor.execute(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "Source race",
        EngineContextTestFixtures.AGENT_LOOP);

    assertFalse(report.allSucceeded());
    assertEquals("replacement source", Files.readString(source));
    assertEquals("source bytes", Files.readString(retained));
    assertEquals("source bytes", Files.readString(destination));
    assertTrue(report.results().getFirst().errorMessage().contains("source " + source + " remains=true"));
    assertTrue(report.results().getFirst().errorMessage().contains("destination " + destination + " remains=true"));
    assertEquals(0, indexUpdateCount);
    assertNull(capturedPathMappings);
  }

  @Test
  void d8UndoRefusesTheWholeGroupWhenOneOriginalIsOccupied() throws IOException {
    assertUndoRefusesOccupiedOriginal(false);
  }

  @Test
  void d8UndoRefusesDanglingOriginalAndPreservesTheWholeGroup() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "Symbolic links require privileges on Windows");
    assertUndoRefusesOccupiedOriginal(true);
  }

  private void assertUndoRefusesOccupiedOriginal(boolean dangling) throws IOException {
    Path first = Files.writeString(root.resolve("first"), "first bytes");
    Path second = Files.writeString(root.resolve("second"), "second bytes");
    Path firstMoved = root.resolve("first-moved");
    Path secondMoved = root.resolve("second-moved");
    var forward = executor.execute(List.of(
        new FileOperation(FileOperation.OpType.MOVE, first, firstMoved),
        new FileOperation(FileOperation.OpType.MOVE, second, secondMoved)), "Undo group",
        EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(forward.allSucceeded(), forward.summary());
    Path missing = root.resolve("missing");
    if (dangling) {
      Files.createSymbolicLink(first, missing);
    } else {
      Files.writeString(first, "occupant bytes");
    }
    indexUpdateCount = 0;
    var tool = new FileOperationsTool(context -> List.of(root),
        (mappings, context) -> { indexUpdateCount += mappings.size(); return mappings.size(); }, log);

    var result = tool.undo(forward.batchId(), EngineContextTestFixtures.AGENT_LOOP);

    assertFalse(result.success());
    assertTrue(result.message().contains("Undo validation failed"), result.message());
    assertTrue(result.message().contains("DEST_EXISTS"), result.message());
    if (dangling) {
      assertEquals(missing, Files.readSymbolicLink(first));
    } else {
      assertEquals("occupant bytes", Files.readString(first));
    }
    assertEquals("first bytes", Files.readString(firstMoved));
    assertEquals("second bytes", Files.readString(secondMoved));
    assertFalse(Files.exists(second));
    assertEquals(0, indexUpdateCount);
  }

  @Test
  void d9CopyFallbackMovesTheSymbolicLinkItself() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "Hard-link route is Unix-only");
    Path target = Files.writeString(root.resolve("target"), "target bytes");
    Path source = Files.createSymbolicLink(root.resolve("source"), target);
    Path destination = root.resolve("destination");
    useMoveFiles(new FileOperationExecutor.MoveFileSystem() {
      @Override
      void createLink(Path to, Path from) {
        throw new UnsupportedOperationException("Hard links unavailable");
      }
    });

    var report = executor.execute(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "Copy link",
        EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(report.allSucceeded(), report.summary());
    assertEquals(target, Files.readSymbolicLink(destination));
    assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
    assertEquals("target bytes", Files.readString(target));
    assertEquals(Map.of(source, destination), capturedPathMappings);
  }

  @Test
  void d9CopyFallbackRefusesSpecialFileWithoutOpeningIt() throws IOException, InterruptedException {
    assumeFalse(PlatformPaths.isWindows(), "A named pipe requires Unix");
    Path source = root.resolve("pipe");
    Path destination = root.resolve("destination");
    useMoveFiles(new FileOperationExecutor.MoveFileSystem() {
      @Override
      void createLink(Path to, Path from) {
        throw new UnsupportedOperationException("Hard links unavailable");
      }

      @Override
      void copy(Path from, Path to) {
        fail("A special file must be refused before calling copy");
      }
    });
    Process fifo = new ProcessBuilder("mkfifo", source.toString()).start();
    assertEquals(0, fifo.waitFor(), "Create a real special file without opening it");

    var report = executor.execute(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "Special file",
        EngineContextTestFixtures.AGENT_LOOP);

    assertFalse(report.allSucceeded());
    assertTrue(report.results().getFirst().errorMessage().contains("non-regular file"));
    assertTrue(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
    assertFalse(Files.exists(destination));
    assertEquals(0, indexUpdateCount);
  }

  @Test
  void d10DirectoryCopyDeletesOnlyCopiedEntries() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "Atomic-directory fallback checked on Unix");
    Path source = Files.createDirectory(root.resolve("source"));
    Path nested = Files.createDirectory(source.resolve("nested"));
    Files.writeString(nested.resolve("copied"), "copied bytes");
    Path destination = root.resolve("destination");
    Path lateChild = nested.resolve("late-child");
    useMoveFiles(new FileOperationExecutor.MoveFileSystem() {
      private boolean firstDelete = true;

      @Override
      void moveDirectory(Path from, Path to) throws IOException {
        throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "Cross-volume");
      }

      @Override
      void delete(Path path) throws IOException {
        if (firstDelete) {
          firstDelete = false;
          Files.writeString(lateChild, "late bytes");
        }
        super.delete(path);
      }
    });

    var report = executor.execute(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "Directory race",
        EngineContextTestFixtures.AGENT_LOOP);

    assertFalse(report.allSucceeded());
    assertTrue(report.results().getFirst().errorMessage().contains("Directory move incomplete"));
    assertEquals("late bytes", Files.readString(lateChild));
    assertFalse(Files.exists(nested.resolve("copied")));
    assertEquals("copied bytes", Files.readString(destination.resolve("nested/copied")));
    assertFalse(Files.exists(destination.resolve("nested/late-child")));
    assertEquals(0, indexUpdateCount);
  }

  @Test
  void p2DirectoryCopyFallbackPreservesChildMappings() throws IOException {
    Path source = Files.createDirectory(root.resolve("source"));
    Path nested = Files.createDirectory(source.resolve("nested"));
    Path first = Files.writeString(source.resolve("first"), "first bytes");
    Path second = Files.writeString(nested.resolve("second"), "second bytes");
    Path destination = root.resolve("destination");
    useMoveFiles(new FileOperationExecutor.MoveFileSystem() {
      @Override
      void moveDirectory(Path from, Path to) throws IOException {
        throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "Cross-volume");
      }
    });

    var report = executor.execute(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "Directory copy",
        EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(report.allSucceeded(), report.summary());
    assertFalse(Files.exists(source));
    assertEquals("first bytes", Files.readString(destination.resolve("first")));
    assertEquals("second bytes", Files.readString(destination.resolve("nested/second")));
    assertEquals(Map.of(first, destination.resolve("first"), second, destination.resolve("nested/second")),
        capturedPathMappings);
  }

  @Test
  void d11DirectoryCopyPreservesChildReplacedWhileSiblingCopies() throws IOException {
    assertDirectoryCopyPreservesChangedChild(true);
  }

  @Test
  void d11DirectoryCopyPreservesSameSizeAndMtimeEditWhileSiblingCopies() throws IOException {
    assertDirectoryCopyPreservesChangedChild(false);
  }

  private void assertDirectoryCopyPreservesChangedChild(boolean replace) throws IOException {
    Path source = Files.createDirectory(root.resolve("source"));
    Files.writeString(source.resolve("first"), "original bytes");
    Files.writeString(source.resolve("second"), "original bytes");
    Path destination = root.resolve("destination");
    Path retained = root.resolve("retained-original");
    String siblingBytes = "longer sibling bytes".repeat(4096);
    String changedBytes = replace ? "replaced bytes" : "edited source!";
    var moveFiles = new FileOperationExecutor.MoveFileSystem() {
      private Path copiedChild;

      @Override
      void moveDirectory(Path from, Path to) throws IOException {
        throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "Cross-volume");
      }

      @Override
      void copy(Path from, Path to) throws IOException {
        if (copiedChild == null) {
          super.copy(from, to);
          copiedChild = from;
          // Prepare the not-yet-copied sibling without depending on directory iteration order.
          Path sibling = source.resolve(from.getFileName().toString().equals("first") ? "second" : "first");
          Files.writeString(sibling, siblingBytes);
          return;
        }
        try (var input = Files.newInputStream(from); var output = Files.newOutputStream(to)) {
          output.write(input.readNBytes(1024));
          assertEquals("original bytes", Files.readString(destination.resolve(copiedChild.getFileName())));
          var before = Files.readAttributes(copiedChild, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
          if (replace) {
            Files.move(copiedChild, retained);
          }
          Files.writeString(copiedChild, changedBytes);
          Files.setLastModifiedTime(copiedChild, before.lastModifiedTime());
          var after = Files.readAttributes(copiedChild, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
          assertEquals(before.lastModifiedTime(), after.lastModifiedTime());
          if (replace) {
            assertEquals(before.size(), after.size(), "Replacement differs only by file identity");
            assertNotNull(before.fileKey(), "This filesystem supplies file identity");
            assertNotEquals(before.fileKey(), after.fileKey());
          } else {
            assertEquals(before.fileKey(), after.fileKey(), "Editing preserves the inode");
            assertEquals(before.size(), after.size(), "Editing preserves the size too");
          }
          input.transferTo(output);
        }
      }
    };
    useMoveFiles(moveFiles);

    var report = executor.execute(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "Changed child",
        EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(Files.exists(moveFiles.copiedChild), "The changed source child must not be deleted");
    assertEquals(changedBytes, Files.readString(moveFiles.copiedChild));
    assertFalse(report.allSucceeded(), "A changed source makes the move incomplete");
    String error = report.results().getFirst().errorMessage();
    assertTrue(error.contains("Directory move incomplete"), error);
    assertTrue(error.contains(source.toString()), error);
    assertTrue(error.contains(destination.toString()), error);
    assertTrue(error.contains(moveFiles.copiedChild.toString()), error);
    assertEquals("original bytes", Files.readString(destination.resolve(moveFiles.copiedChild.getFileName())));
    Path sibling = destination.resolve(moveFiles.copiedChild.getFileName().toString().equals("first") ? "second" : "first");
    assertEquals(siblingBytes, Files.readString(sibling));
    assertEquals(0, indexUpdateCount);
    assertNull(capturedPathMappings);
  }

  @Test
  void d11SingleFileCopyPreservesSameSizeAndMtimeEditDuringCopy() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "Hard-link route is Unix-only");
    Path source = Files.writeString(root.resolve("source"), "original bytes");
    Path destination = root.resolve("destination");
    useMoveFiles(new FileOperationExecutor.MoveFileSystem() {
      @Override
      void createLink(Path to, Path from) {
        throw new UnsupportedOperationException("Hard links unavailable");
      }

      @Override
      void copy(Path from, Path to) throws IOException {
        super.copy(from, to);
        var before = Files.readAttributes(from, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Files.writeString(from, "edited source!");
        Files.setLastModifiedTime(from, before.lastModifiedTime());
        var after = Files.readAttributes(from, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assertEquals(before.fileKey(), after.fileKey());
        assertEquals(before.size(), after.size(), "Editing preserves the size too");
        assertEquals(before.lastModifiedTime(), after.lastModifiedTime());
      }
    });

    var report = executor.execute(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "Changed file",
        EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(Files.exists(source), "The changed source file must not be deleted");
    assertEquals("edited source!", Files.readString(source));
    assertEquals("original bytes", Files.readString(destination));
    assertFalse(report.allSucceeded(), "A changed source makes the move incomplete");
    String error = report.results().getFirst().errorMessage();
    assertTrue(error.contains("Move incomplete"), error);
    assertTrue(error.contains("source " + source + " remains=true"), error);
    assertTrue(error.contains("destination " + destination + " remains=true"), error);
    assertEquals(0, indexUpdateCount);
    assertNull(capturedPathMappings);
  }

  @Test
  void d11CopyPreservesSourceUnreadableAtComparison() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "POSIX permissions and Unix copy fallback required");
    Path source = Files.writeString(root.resolve("source"), "original bytes");
    Path destination = root.resolve("destination");
    var permissions = Files.getPosixFilePermissions(source);
    useMoveFiles(new FileOperationExecutor.MoveFileSystem() {
      @Override
      void createLink(Path to, Path from) {
        throw new UnsupportedOperationException("Hard links unavailable");
      }

      @Override
      void copy(Path from, Path to) throws IOException {
        super.copy(from, to);
        Files.setPosixFilePermissions(from, PosixFilePermissions.fromString("---------"));
        assertThrows(AccessDeniedException.class, () -> Files.readString(from),
            "The source must really be unreadable before comparison");
      }
    });

    try {
      var report = executor.execute(
          List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "Unreadable source",
          EngineContextTestFixtures.AGENT_LOOP);

      assertTrue(Files.exists(source), "An unreadable source must not be deleted");
      assertFalse(report.allSucceeded());
      String error = report.results().getFirst().errorMessage();
      assertTrue(error.contains("source " + source + " remains=true"), error);
      assertTrue(error.contains("destination " + destination + " remains=true"), error);
      assertEquals("original bytes", Files.readString(destination));
      assertEquals(0, indexUpdateCount);
      assertNull(capturedPathMappings);
    } finally {
      if (Files.exists(source)) {
        Files.setPosixFilePermissions(source, permissions);
      }
    }
    assertEquals("original bytes", Files.readString(source));
  }

  @Test
  void d11CopyDeletesSourceWithUnchangedBytesDespiteChangedMtime() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "Hard-link route is Unix-only");
    Path source = Files.writeString(root.resolve("source"), "original bytes");
    Path destination = root.resolve("destination");
    useMoveFiles(new FileOperationExecutor.MoveFileSystem() {
      @Override
      void createLink(Path to, Path from) {
        throw new UnsupportedOperationException("Hard links unavailable");
      }

      @Override
      void copy(Path from, Path to) throws IOException {
        super.copy(from, to);
        Files.setLastModifiedTime(from, FileTime.fromMillis(Files.getLastModifiedTime(from).toMillis() + 2000));
      }
    });

    var report = executor.execute(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "Unchanged bytes",
        EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(report.allSucceeded(), report.summary());
    assertFalse(Files.exists(source));
    assertEquals("original bytes", Files.readString(destination));
    assertEquals(Map.of(source, destination), capturedPathMappings);
  }

  @Test
  void d11CopyPreservesChangedSymbolicLinkTarget() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "Symbolic links and Unix copy fallback required");
    Path originalTarget = Path.of("original-target");
    Path changedTarget = Path.of("changed-target!");
    Path source = Files.createSymbolicLink(root.resolve("source"), originalTarget);
    Path destination = root.resolve("destination");
    useMoveFiles(new FileOperationExecutor.MoveFileSystem() {
      @Override
      void createLink(Path to, Path from) {
        throw new UnsupportedOperationException("Hard links unavailable");
      }

      @Override
      void copy(Path from, Path to) throws IOException {
        super.copy(from, to);
        Files.delete(from);
        Files.createSymbolicLink(from, changedTarget);
      }
    });

    var report = executor.execute(
        List.of(new FileOperation(FileOperation.OpType.MOVE, source, destination)), "Changed link",
        EngineContextTestFixtures.AGENT_LOOP);

    assertEquals(changedTarget, Files.readSymbolicLink(source));
    assertEquals(originalTarget, Files.readSymbolicLink(destination));
    assertFalse(report.allSucceeded());
    String error = report.results().getFirst().errorMessage();
    assertTrue(error.contains("source " + source + " remains=true"), error);
    assertTrue(error.contains("destination " + destination + " remains=true"), error);
    assertEquals(0, indexUpdateCount);
    assertNull(capturedPathMappings);
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
