/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.configuration.FileOps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexGenerationCleanupAuthorityTest {
  @TempDir Path temp;

  @Test void emptyRemnantAfterFinalRemovalFailureIsReclaimedSeparately() throws Exception {
    var manager = new IndexGenerationManager(temp);
    manager.initializeOrLoad();
    assertEmptyRemnantReclaimed(manager, ownedMarked());
  }

  @Test void emptyRecordedFallbackRemnantIsReclaimedAfterFinalRemovalFailure() throws Exception {
    var manager = new IndexGenerationManager(temp);
    manager.initializeOrLoad();
    String identity = "g-01994180-0000-7000-8000-000000000119";
    Path old = ownedDirectory(identity, identity);
    Files.writeString(old.resolve("DELETEME"), "rename failed");
    assertEmptyRemnantReclaimed(manager, old);
  }

  @Test void recordedEmptyRemnantSurvivesAutomaticCleanupAndBootFence() throws Exception {
    var manager = new IndexGenerationManager(temp);
    manager.initializeOrLoad();
    Path remnant = emptyRecordedRemnant();
    manager.pruneMarkedForDeletionBestEffort();
    assertTrue(Files.isDirectory(remnant));
    assertEquals(IndexGenerationManager.BootDisposition.FENCED,
        manager.initializeForBoot(new IndexGenerationManager.BootOwnership.Native(), "a".repeat(64)).disposition());
    assertTrue(Files.isDirectory(remnant));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void explicitGcKeepsRecordedEmptyRemnantDuringMigration(boolean building) throws Exception {
    var manager = new IndexGenerationManager(temp);
    manager.initializeOrLoad();
    if (building) {
      manager.startMigration("manual");
      manager.updateMigrationState(IndexGenerationManager.MigrationState.IDLE);
    } else {
      manager.updateMigrationState(IndexGenerationManager.MigrationState.MIGRATING);
    }
    Path remnant = emptyRecordedRemnant();
    var result = assertDoesNotThrow(() -> manager.gcBestEffort(0, true));
    assertTrue(Files.isDirectory(remnant));
    assertEquals(0, emptyLeftovers(result));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"active_generation", "previous_generation"})
  void explicitGcKeepsRecordedEmptyRemnantReferencedByPreviousState(String binding) throws Exception {
    var manager = new IndexGenerationManager(temp);
    manager.initializeOrLoad();
    Path remnant = emptyRecordedRemnant();
    var json = new tools.jackson.databind.ObjectMapper();
    var previous = (tools.jackson.databind.node.ObjectNode) json.readTree(temp.resolve("state.json").toFile());
    previous.put(binding, remnant.getFileName().toString());
    Files.write(temp.resolve("state.json.prev"), json.writeValueAsBytes(previous));
    var result = assertDoesNotThrow(() -> manager.gcBestEffort(0, true));
    assertTrue(Files.isDirectory(remnant));
    assertEquals(0, emptyLeftovers(result));
  }

  @Test void explicitGcReclaimsRecordedEmptyRemnantAfterBuildingPointerWasCleared() throws Exception {
    var manager = new IndexGenerationManager(temp);
    manager.initializeOrLoad();
    Path remnant = emptyRecordedRemnant();
    var json = new tools.jackson.databind.ObjectMapper();
    var previous = (tools.jackson.databind.node.ObjectNode) json.readTree(temp.resolve("state.json").toFile());
    previous.put("building_generation", remnant.getFileName().toString());
    previous.put("migration_state", "MIGRATING");
    Files.write(temp.resolve("state.json.prev"), json.writeValueAsBytes(previous));
    var result = assertDoesNotThrow(() -> manager.gcBestEffort(0, true));
    assertFalse(Files.exists(remnant));
    assertEquals(1, emptyLeftovers(result));
    assertEquals(0, result.prunedCount());
  }

  private Path emptyRecordedRemnant() throws Exception {
    return Files.createDirectory(temp.resolve("indices/g-01994180-0000-7000-8000-000000000119"));
  }

  private void assertEmptyRemnantReclaimed(IndexGenerationManager manager, Path old) throws Exception {
    var failed = new AtomicBoolean();
    try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
      files.when(() -> Files.delete(eq(old))).thenAnswer(invocation -> {
        failed.set(true);
        throw new IOException("injected final directory removal failure");
      });
      files.when(() -> Files.deleteIfExists(eq(old))).thenAnswer(invocation -> {
        failed.set(true);
        throw new IOException("injected final directory removal failure");
      });
      assertThrows(RuntimeException.class, () -> manager.gcBestEffort(0, true));
    }
    assertTrue(failed.get());
    assertTrue(Files.isDirectory(old));
    try (var entries = Files.list(old)) { assertEquals(0, entries.count()); }
    var result = assertDoesNotThrow(() -> manager.gcBestEffort(0, true));
    assertFalse(Files.exists(old));
    assertEquals(0, result.prunedCount());
    assertEquals(1, emptyLeftovers(result));
  }

  @Test void failedPayloadRemovalRetainsAllOwnershipEvidence() throws Exception {
    var manager = new IndexGenerationManager(temp);
    manager.initializeOrLoad();
    Path old = ownedMarked();
    Files.writeString(old.resolve("payload"), "locked payload");
    try (var files = mockStatic(FileOps.class)) {
      files.when(() -> FileOps.deleteRecursivelyBestEffort(any(), any()))
          .thenReturn(new FileOps.DeleteResult(0, 1));
      assertThrows(RuntimeException.class, () -> manager.gcBestEffort(0, true));
    }
    assertTrue(Files.isRegularFile(old.resolve(".justsearch-generation.sentinel")));
    assertTrue(Files.isRegularFile(old.resolve(".justsearch-index-generation.json")));
    assertEquals("locked payload", Files.readString(old.resolve("payload")));
  }

  @Test void nestedPayloadLinkIsUnlinkedWithoutTraversingItsTarget() throws Exception {
    var manager = new IndexGenerationManager(temp);
    manager.initializeOrLoad();
    Path old = ownedMarked();
    Path external = Files.createDirectory(temp.resolve("external"));
    Files.writeString(external.resolve("foreign"), "external bytes");
    Files.createSymbolicLink(old.resolve("nested"), external);
    assertDoesNotThrow(() -> manager.gcBestEffort(0, true));
    assertFalse(Files.exists(old));
    assertEquals("external bytes", Files.readString(external.resolve("foreign")));
  }

  private static int emptyLeftovers(Object result) throws Exception {
    try { return (int) result.getClass().getMethod("emptyLeftoversRemoved").invoke(result); }
    catch (NoSuchMethodException absent) { return 0; }
  }

  private Path ownedMarked() throws Exception {
    return ownedDirectory("g-old.del-20260101-000000", "g-old");
  }

  private Path ownedDirectory(String name, String identity) throws Exception {
    Path folder = Files.createDirectory(temp.resolve("indices").resolve(name));
    Files.writeString(folder.resolve(".justsearch-generation.sentinel"),
        "justsearch_generation_sentinel_v1\ngeneration_id=" + identity + "\ncreated_at_ms=1\n");
    Files.writeString(folder.resolve(".justsearch-index-generation.json"),
        "{\"format_version\":1,\"generation_id\":\"" + identity + "\",\"source\":\"new\",\"created_at_ms\":2}");
    return folder;
  }
}
