/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HistoricalOwnershipAdmissionTest {
  @TempDir Path temp;
  private static final String SENTINEL = ".justsearch-generation.sentinel";
  private static final String MANIFEST = ".justsearch-index-generation.json";
  private static final String ID = "g-20250701-000000";
  private static final List<String> POINTERS = List.of("v2", "v1", "prev-absent", "prev-empty", "prev-torn", "prev-v1");
  private static final List<String> OPERATIONS = List.of("boot", "migration", "pause", "phase", "attempt", "recovery", "recorded");

  @Test void recoveredActiveAcceptsV1Pointer() throws Exception { recovered("v1"); }

  @Test void recoveredActiveAcceptsEveryPreviousPointerFallback() {
    assertAll(POINTERS.stream().filter(s -> s.startsWith("prev")).<org.junit.jupiter.api.function.Executable>map(s -> () -> recovered(s)));
  }

  @Test void emptySentinelAcceptsV1Pointer() throws Exception { emptySentinelStarts("v1"); }

  @Test void emptySentinelAcceptsEveryPreviousPointerFallback() {
    assertAll(POINTERS.stream().filter(s -> s.startsWith("prev")).<org.junit.jupiter.api.function.Executable>map(s -> () -> emptySentinelStarts(s)));
  }

  @Test void historicalLayoutsKeepCurrentPointerCompatibility() throws Exception {
    recovered("v2");
    emptySentinelStarts("v2");
  }

  private void recovered(String pointer) throws Exception {
    Path base = temp.resolve("recovered-" + pointer);
    Path backup = owned(base, ID + ".bak-20250801-000000", ID);
    Path active = Files.createDirectory(backup.resolveSibling(ID));
    try (var directory = FSDirectory.open(active);
        var writer = new IndexWriter(directory, new IndexWriterConfig())) {
      var document = new Document();
      document.add(new StringField("proof", "retained", Field.Store.YES));
      writer.addDocument(document);
      writer.commit();
    }
    pointer(base, pointer, ID, null, null);
    var manager = new IndexGenerationManager(base);
    assertDoesNotThrow(() -> { manager.reconcileActiveRecoveryOwnership(); }, pointer);
    assertEquals(ID, assertDoesNotThrow(() -> manager.initializeForBoot(
        new IndexGenerationManager.BootOwnership.Native(), null)).layout().activeGenerationId());
    try (var directory = FSDirectory.open(active); var reader = DirectoryReader.open(directory)) {
      assertEquals(1, new IndexSearcher(reader).count(new TermQuery(new Term("proof", "retained"))));
    }
  }

  private void emptySentinelStarts(String pointer) throws Exception {
    Path base = temp.resolve("empty-" + pointer);
    Path active = owned(base, ID, ID);
    Files.write(active.resolve(SENTINEL), new byte[0]);
    pointer(base, pointer, ID, null, null);
    assertEquals(ID, assertDoesNotThrow(() -> new IndexGenerationManager(base).initializeForBoot(
        new IndexGenerationManager.BootOwnership.Native(), null)).layout().activeGenerationId());
  }

  @Test void everyPointedUnprovenMarkedDirectoryRefusesEveryInitializerBeforeWrites() {
    var checks = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String pointer : POINTERS) {
      for (String binding : List.of("active", "building", "previous")) {
        for (String shape : List.of("DELETEME", "del-name", "empty")) {
          for (String operation : OPERATIONS) {
            checks.add(() -> {
            String label = pointer + "/" + binding + "/" + shape + "/" + operation;
            Path base = temp.resolve(label);
            owned(base, ID, ID);
            String unprovenId = shape.equals("del-name") ? "g-foreign.del-20250801-000000" : "g-foreign";
            Path unproven = Files.createDirectory(base.resolve("indices").resolve(unprovenId));
            if (!shape.equals("empty")) Files.writeString(unproven.resolve("foreign.txt"), "foreign bytes");
            if (shape.equals("DELETEME")) Files.writeString(unproven.resolve("DELETEME"), "foreign marker");
            pointer(base, pointer, binding.equals("active") ? unprovenId : ID,
                binding.equals("building") ? unprovenId : null, binding.equals("previous") ? unprovenId : null);
            var manager = new IndexGenerationManager(base);
            var before = snapshot(base);
            var refusal = assertThrows(IOException.class, () -> invoke(manager, operation), label);
            assertTrue(refusal.getMessage().contains("index.ownership_unproven"), label + ": " + refusal);
            assertEquals(before, snapshot(base), label);
            });
          }
        }
      }
    }
    assertAll(checks);
  }

  @Test void backupAuthorityRejectsHardLinkedLuceneFilesUnchanged() throws Exception { rejectedRecovery("hardlink"); }

  @Test void backupAuthorityRejectsUnknownLuceneExtensionUnchanged() throws Exception { rejectedRecovery("extension"); }

  private void rejectedRecovery(String shape) throws Exception {
    Path base = temp.resolve(shape);
    owned(base, ID + ".bak-20250801-000000", ID);
    Path active = Files.createDirectory(base.resolve("indices").resolve(ID));
    Path external = Files.writeString(base.resolve("external"), "outside bytes");
    if (shape.equals("hardlink")) Files.createLink(active.resolve("segments_1"), external);
    else Files.writeString(active.resolve("_0.foreign"), "arbitrary bytes");
    pointer(base, "v2", ID, null, null);
    var before = snapshot(base);
    var refusal = assertThrows(IOException.class, () -> new IndexGenerationManager(base).reconcileActiveRecoveryOwnership());
    assertTrue(refusal.getMessage().contains("index.ownership_unproven"), refusal.toString());
    assertEquals(before, snapshot(base));
    assertEquals("outside bytes", Files.readString(external));
  }

  @Test void everyInitializerRepairsEmptySentinelBeforeOtherWritesWithoutChangingHardLinkAlias() throws Exception {
    for (String operation : OPERATIONS) {
      for (boolean hardlink : List.of(false, true)) {
        Path base = temp.resolve(operation + "-" + hardlink);
        Path active = owned(base, ID, ID);
        Path sentinel = active.resolve(SENTINEL);
        Files.write(sentinel, new byte[0]);
        Path alias = base.resolve("external-sentinel");
        if (hardlink) Files.createLink(alias, sentinel);
        pointer(base, "v2", ID, null, null);
        var manager = new IndexGenerationManager(base);
        var first = new AtomicReference<Path>();
        try (var writes = mockStatic(AtomicFileWrites.class, CALLS_REAL_METHODS)) {
          writes.when(() -> AtomicFileWrites.replaceStrict(any(Path.class), any(byte[].class))).thenAnswer(call -> {
            Path target = call.getArgument(0);
            if (first.compareAndSet(null, target)) assertEquals(sentinel, target, operation);
            return call.callRealMethod();
          });
          assertDoesNotThrow(() -> invoke(manager, operation));
        }
        assertEquals(sentinel, first.get(), operation);
        assertEquals(sentinel(ID), Files.readString(sentinel));
        if (hardlink) {
          assertEquals(0, Files.size(alias));
          assertFalse(Files.isSameFile(sentinel, alias));
        }
      }
    }
  }

  @Test void readOnlyGuardsLeaveEmptySentinelAndPointersUntouched() throws Exception {
    for (String guard : List.of("journal", "layout")) {
      Path base = temp.resolve(guard);
      Path active = owned(base, ID, ID);
      Files.write(active.resolve(SENTINEL), new byte[0]);
      pointer(base, "v2", ID, null, null);
      var before = snapshot(base);
      var manager = new IndexGenerationManager(base);
      try {
        if (guard.equals("journal")) manager.requiresMigrationJournalForBoot();
        else manager.inspectCurrentLayoutForBoot();
      } catch (IOException unproven) {
        // The strict read-only view may reject the incomplete metadata, but cannot repair it.
      }
      assertEquals(before, snapshot(base), guard);
    }
  }

  private static void invoke(IndexGenerationManager manager, String operation) throws Exception {
    switch (operation) {
      case "boot" -> manager.initializeForBoot(new IndexGenerationManager.BootOwnership.Native(), null);
      case "migration" -> manager.startFreshMigration("manual");
      case "pause" -> manager.setMigrationPaused(true, "operator");
      case "phase" -> manager.updateMigrationState(IndexGenerationManager.MigrationState.FAILED);
      case "attempt" -> manager.recordAutoRebuildAttempt("a".repeat(64));
      case "recovery" -> manager.reconcileActiveRecoveryOwnership();
      case "recorded" -> manager.startRecordedMigration("01994180-0000-7000-8000-000000000119", "repair", "a".repeat(64));
      default -> fail(operation);
    }
  }

  private static Path owned(Path base, String name, String id) throws Exception {
    Path directory = Files.createDirectories(base.resolve("indices").resolve(name));
    Files.writeString(directory.resolve(SENTINEL), sentinel(id));
    Files.writeString(directory.resolve(MANIFEST), "{\"format_version\":1,\"generation_id\":\"" + id
        + "\",\"source\":\"new\",\"created_at_ms\":1751328000000}");
    return directory;
  }

  private static String sentinel(String id) {
    return "justsearch_generation_sentinel_v1\ngeneration_id=" + id + "\ncreated_at_ms=1751328000000\n";
  }

  private static void pointer(Path base, String shape, String active, String building, String previous) throws Exception {
    String json = "{\"format_version\":" + (shape.equals("v1") || shape.equals("prev-v1") ? 1 : 2)
        + ",\"active_generation\":\"" + active + "\",\"building_generation\":" + nullable(building)
        + ",\"previous_generation\":" + nullable(previous)
        + ",\"migration_state\":\"IDLE\",\"migration_paused\":false,\"updated_at_ms\":1751328000000}";
    Files.writeString(base.resolve(shape.startsWith("prev") ? "state.json.prev" : "state.json"), json);
    if (shape.equals("prev-empty")) Files.writeString(base.resolve("state.json"), "");
    if (shape.equals("prev-torn")) Files.writeString(base.resolve("state.json"), "{torn");
  }

  private static String nullable(String value) { return value == null ? "null" : "\"" + value + "\""; }

  private static Map<String, String> snapshot(Path base) throws Exception {
    Map<String, String> result = new TreeMap<>();
    try (var paths = Files.walk(base)) {
      for (Path path : paths.toList()) {
        result.put(base.relativize(path).toString(), Files.isSymbolicLink(path) ? "link:" + Files.readSymbolicLink(path)
            : Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "directory"
            : Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
      }
    }
    return result;
  }
}
