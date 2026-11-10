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

class FileOperationMoveSafetyTest {

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

}
