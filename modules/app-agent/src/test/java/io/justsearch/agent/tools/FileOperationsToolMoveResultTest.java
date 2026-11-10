package io.justsearch.agent.tools;

import io.justsearch.core.context.EngineContext;
import io.justsearch.agent.EngineContextTestFixtures;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import io.justsearch.configuration.PlatformPaths;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileOperationsToolMoveResultTest {

  @TempDir Path tempDir;

  private Path root;
  private AtomicReference<Map<Path, Path>> capturedMappings;
  // Tempdoc 875 §C.6 — the indexed roots are a live lookup, not a constant: a user can remove a
  // watched root between an operation and its undo. Holding them in a reference lets a test
  // reproduce that without changing what any existing test sees (it starts as List.of(root)).
  private AtomicReference<List<Path>> indexedRoots;
  private FileOperationsTool tool;

  @BeforeEach
  void setUp() throws IOException {
    root = tempDir.resolve("indexed");
    Files.createDirectories(root);
    capturedMappings = new AtomicReference<>();
    indexedRoots = new AtomicReference<>(List.of(root));
    tool =
        new FileOperationsTool(
            context -> indexedRoots.get(),
            (pathMappings, context) -> {
              capturedMappings.set(pathMappings);
              return pathMappings.size();
            },
            new FileOperationLog(tempDir.resolve("data").resolve("file-operations")));
  }

  @Test
  void d6DeleteFailureNamesBothRemainingPathsInToolResult() throws IOException {
    assumeFalse(PlatformPaths.isWindows(), "POSIX permissions and Unix hard links required");
    Path sourceParent = Files.createDirectory(root.resolve("source-parent"));
    Path source = Files.writeString(sourceParent.resolve("source"), "source bytes");
    Path destination = root.resolve("destination");
    var permissions = Files.getPosixFilePermissions(sourceParent);
    Files.setPosixFilePermissions(sourceParent, PosixFilePermissions.fromString("r-x------"));
    try {
      String json =
          "{\"operations\": [{\"op\": \"MOVE\", \"source\": \"%s\", \"destination\": \"%s\"}]}\n"
              .formatted(source.toString().replace("\\", "\\\\"), destination.toString().replace("\\", "\\\\"));

      OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);

      assertFalse(result.success(), result.message());
      assertEquals("source bytes", Files.readString(source));
      assertEquals("source bytes", Files.readString(destination));
      assertTrue(Files.isSameFile(source, destination), "Deletion failed after linking");
      assertTrue(result.message().contains("Move incomplete"), result.message());
      assertTrue(result.message().contains("source " + source + " remains=true"), result.message());
      assertTrue(result.message().contains("destination " + destination + " remains=true"), result.message());
      assertNull(capturedMappings.get());
    } finally {
      Files.setPosixFilePermissions(sourceParent, permissions);
    }
  }

}
