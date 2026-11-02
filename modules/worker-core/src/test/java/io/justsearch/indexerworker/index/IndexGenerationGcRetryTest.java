/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class IndexGenerationGcRetryTest {
  @TempDir Path temp;

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {false, true})
  void failedDirectoryRemovalRetainsOwnershipForRetry(boolean renamed) throws Exception {
    var manager = new IndexGenerationManager(temp);
    Path active = manager.initializeOrLoad().activeGenerationPath();
    Path indices = temp.resolve("indices");
    assumeTrue(Files.getFileAttributeView(indices, PosixFileAttributeView.class) != null,
        "requires POSIX permissions; unsupported on Windows");
    Path old = generation("g-20000101-000001");
    if (renamed) {
      old = Files.move(old, old.resolveSibling(old.getFileName() + ".del-20260101-000000"));
    } else {
      Files.writeString(old.resolve("DELETEME"), "marked for deletion");
    }
    var permissions = Files.getPosixFilePermissions(indices);
    UncheckedIOException failure = null;
    try {
      Files.setPosixFilePermissions(indices, PosixFilePermissions.fromString("r-x------"));
      assumeFalse(Files.isWritable(indices), "permissions must deny parent directory removal");
      try {
        manager.gcBestEffort(0, true);
      } catch (UncheckedIOException incomplete) {
        failure = incomplete;
      }
      assertTrue(Files.isDirectory(old), "removing the directory must fail");
      assertFalse(Files.exists(old.resolve("payload")), "payload removal must have succeeded");
    } finally {
      Files.setPosixFilePermissions(indices, permissions);
    }

    var result = new IndexGenerationManager(temp).gcBestEffort(0, true);

    assertFalse(Files.exists(old), "retry must remove the owned remnant");
    assertEquals(new IndexGenerationManager.GcResult(0, 1), result);
    assertNotNull(failure, "the first attempt must report incomplete cleanup");
    assertEquals("Index GC incomplete", failure.getMessage());
    assertEquals(List.of(active.getFileName().toString()), directoryNames());
  }

  @Test
  void retryPreservesProtectedForeignAndRetainedDirectories() throws Exception {
    var manager = new IndexGenerationManager(temp);
    String active = manager.initializeOrLoad().activeGenerationId();
    Path previous = generation("g-previous");
    Path building = generation("g-building");
    List<String> preservedNames = new ArrayList<>();
    for (String id : List.of(active, "g-previous", "g-building")) {
      Files.writeString(temp.resolve("indices").resolve(id).resolve("DELETEME"), "protected");
      String renamed = id + ".del-20260101-000000";
      Files.createDirectory(temp.resolve("indices").resolve(renamed));
      preservedNames.add(renamed);
    }
    Files.writeString(temp.resolve("state.json"),
        "{\"format_version\":2,\"active_generation\":\"" + active
            + "\",\"previous_generation\":\"g-previous\",\"building_generation\":\"g-building\","
            + "\"migration_state\":\"MIGRATING\",\"updated_at_ms\":0}");
    Path foreign = Files.createDirectory(temp.resolve("indices/foreign"));
    Files.writeString(foreign.resolve("payload"), "foreign data");
    Path markerOnly = Files.createDirectory(temp.resolve("indices/marker-only"));
    Files.writeString(markerOnly.resolve("DELETEME"), "no ownership sentinel");
    Files.createDirectory(temp.resolve("indices/foreign.del-unrecognized"));
    Path first = generation("g-20000101-000001");
    Path second = generation("g-20000101-000002");
    Path marked = generation("g-obsolete");
    Files.writeString(marked.resolve("DELETEME"), "marked for deletion");

    assertEquals(new IndexGenerationManager.GcResult(0, 1), manager.gcBestEffort(0, true));
    assertFalse(Files.exists(marked));
    assertTrue(Files.isDirectory(first));
    assertTrue(Files.isDirectory(second));
    assertEquals(new IndexGenerationManager.GcResult(1, 1), manager.gcBestEffort(1, false));
    assertFalse(Files.exists(first));
    assertEquals(new IndexGenerationManager.GcResult(0, 0),
        new IndexGenerationManager(temp).gcBestEffort(1, false));
    preservedNames.addAll(List.of("foreign", "foreign.del-unrecognized", "g-20000101-000002",
        "g-building", "g-previous", "marker-only", active));
    assertEquals(preservedNames.stream().sorted().toList(), directoryNames());
    assertEquals("foreign data", Files.readString(foreign.resolve("payload")));
    for (Path protectedPath : List.of(temp.resolve("indices").resolve(active), previous, building)) {
      assertTrue(Files.isRegularFile(protectedPath.resolve(".justsearch-generation.sentinel")));
      assertEquals("protected", Files.readString(protectedPath.resolve("DELETEME")));
    }
    assertTrue(Files.isRegularFile(second.resolve("payload")));
  }

  private Path generation(String name) throws Exception {
    Path path = Files.createDirectory(temp.resolve("indices").resolve(name));
    Files.writeString(path.resolve(".justsearch-generation.sentinel"), "generation_id=" + name + "\n");
    Files.writeString(path.resolve("payload"), "retained on failure");
    return path;
  }

  private List<String> directoryNames() throws Exception {
    try (var entries = Files.list(temp.resolve("indices"))) {
      return entries.filter(Files::isDirectory).map(path -> path.getFileName().toString()).sorted().toList();
    }
  }
}
