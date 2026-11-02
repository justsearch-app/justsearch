/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.IndexRecoveryMarker;
import io.justsearch.adapters.lucene.runtime.SafeIndexPathOps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexOwnershipAdmissionTest {
  @TempDir Path temp;
  private static final String SENTINEL = ".justsearch-generation.sentinel";
  private static final String MANIFEST = ".justsearch-index-generation.json";

  @Test void plainForeignFolderIsNeverAdopted() throws Exception {
    Path foreign = foreign("foreign");
    assertReadOnlyRefusal(new IndexGenerationManager(temp), foreign);
  }

  @Test void foreignLuceneShapeIsNeverAdoptedOrCollected() throws Exception {
    Path foreign = foreign("g-20000101-000001");
    Files.writeString(foreign.resolve("segments_1"), "corrupt foreign segments");
    assertReadOnlyRefusal(new IndexGenerationManager(temp), foreign);
    Path base = temp.resolve("gc");
    var manager = new IndexGenerationManager(base);
    manager.initializeOrLoad();
    Path retained = Files.createDirectory(base.resolve("indices/g-20000101-000001"));
    Files.writeString(retained.resolve("segments_1"), "foreign segments");
    var before = snapshot(retained);
    assertDoesNotThrow(() -> manager.gcBestEffort(0, false));
    assertEquals(before, snapshot(retained));
  }

  @Test void malformedSentinelsNeverAuthorizeAdoption() throws Exception {
    List<String> invalid = List.of("generation_id=g-owned\n", "wrong\ngeneration_id=g-owned\ncreated_at_ms=1\n",
        sentinel("another"), sentinel("g-owned") + "unknown=yes\n",
        sentinel("g-owned") + "generation_id=g-owned\n", sentinel("g-owned").replace("=1\n", "=0\n"),
        sentinel("g-owned") + "x".repeat(1_024));
    for (int i = 0; i < invalid.size(); i++) {
      Path base = Files.createDirectory(temp.resolve("case" + i));
      Path folder = Files.createDirectories(base.resolve("indices/g-owned"));
      Files.writeString(folder.resolve(SENTINEL), invalid.get(i));
      assertReadOnlyRefusal(new IndexGenerationManager(base), folder);
    }
  }

  @Test void malformedManifestsNeverAuthorizeAdoption() throws Exception {
    List<String> invalid = List.of("{", manifest("another"), manifest("g-owned").replace("\"format_version\":1", "\"format_version\":4"),
        manifest("g-owned").replace("{", "{\"unknown\":1,"),
        manifest("g-owned").replace("{", "{\"format_version\":1,"), " ".repeat(16_385));
    for (int i = 0; i < invalid.size(); i++) {
      Path base = Files.createDirectory(temp.resolve("case" + i));
      Path folder = owned(base, "g-owned");
      Files.writeString(folder.resolve(MANIFEST), invalid.get(i));
      assertReadOnlyRefusal(new IndexGenerationManager(base), folder);
    }
  }

  @Test void linkedDirectoryAndMetadataAreRefused() throws Exception {
    Path outside = Files.createDirectory(temp.resolve("outside"));
    Files.writeString(outside.resolve("payload"), "foreign");
    Path base = Files.createDirectory(temp.resolve("base"));
    Files.createDirectory(base.resolve("indices"));
    Path link = Files.createSymbolicLink(base.resolve("indices/g-linked"), outside);
    var before = snapshot(outside);
    assertReadOnlyRefusal(new IndexGenerationManager(base), link);
    assertEquals(before, snapshot(outside));
    Files.delete(link);
    Path folder = owned(base, "g-owned");
    Path evidence = temp.resolve("external-sentinel");
    Files.writeString(evidence, sentinel("g-owned"));
    Files.delete(folder.resolve(SENTINEL));
    Files.createSymbolicLink(folder.resolve(SENTINEL), evidence);
    assertReadOnlyRefusal(new IndexGenerationManager(base), folder);
    assertEquals(sentinel("g-owned"), Files.readString(evidence));
  }

  @Test void everyNativeInitializerRefusesBeforeWriting() throws Exception {
    for (String operation : List.of("boot", "migration", "pause", "phase", "attempt", "recovery")) {
      Path base = Files.createDirectory(temp.resolve(operation));
      var manager = new IndexGenerationManager(base);
      manager.initializeOrLoad();
      Path foreign = Files.createDirectory(base.resolve("indices/foreign"));
      Files.writeString(foreign.resolve("payload"), "foreign");
      var before = snapshot(base);
      var refusal = assertThrows(IOException.class, () -> {
        switch (operation) {
          case "boot" -> manager.initializeForBoot(new IndexGenerationManager.BootOwnership.Native(), null);
          case "migration" -> manager.startFreshMigration("manual");
          case "pause" -> manager.setMigrationPaused(true, "operator");
          case "phase" -> manager.updateMigrationState(IndexGenerationManager.MigrationState.FAILED);
          case "attempt" -> manager.recordAutoRebuildAttempt("fingerprint");
          case "recovery" -> manager.reconcileActiveRecoveryOwnership();
          default -> fail(operation);
        }
      }, operation);
      assertTrue(refusal.getMessage().contains("index.ownership_unproven"), operation);
      assertEquals(before, snapshot(base), operation);
    }
  }

  @Test void validPointerCannotClaimForeignActiveReplacement() throws Exception {
    var manager = new IndexGenerationManager(temp);
    Path active = manager.initializeOrLoad().activeGenerationPath();
    Files.delete(active.resolve(SENTINEL));
    Files.delete(active.resolve(MANIFEST));
    Files.writeString(active.resolve("foreign"), "restored foreign content");
    assertReadOnlyRefusal(manager, active);
  }

  @Test void validBackupCannotClaimForeignReplacement() throws Exception {
    var manager = new IndexGenerationManager(temp);
    Path active = manager.initializeOrLoad().activeGenerationPath();
    SafeIndexPathOps.backupDirectory(active, temp.resolve("indices"));
    Files.createDirectory(active);
    Files.writeString(active.resolve("foreign"), "foreign replacement");
    var before = snapshot(temp);
    assertThrows(IOException.class, manager::reconcileActiveRecoveryOwnership);
    assertEquals(before, snapshot(temp));
  }

  @Test void flatLegacyIsNeverMoved() throws Exception {
    Files.writeString(temp.resolve("segments_1"), "foreign legacy");
    var before = snapshot(temp);
    var refusal = assertThrows(IOException.class, () -> new IndexGenerationManager(temp).initializeOrLoad());
    assertTrue(refusal.getMessage().contains("index.ownership_unproven"));
    assertEquals(before, snapshot(temp));
  }

  @Test void gcNeverTrustsSentinelPresenceAlone() throws Exception {
    var manager = new IndexGenerationManager(temp);
    manager.initializeOrLoad();
    Path marked = foreign("g-20000101-000001.del-20260101-000000");
    Files.writeString(marked.resolve(SENTINEL), "generation_id=g-20000101-000001\n");
    var before = snapshot(marked);
    assertDoesNotThrow(() -> manager.gcBestEffort(0, true));
    assertEquals(before, assertDoesNotThrow(() -> snapshot(marked)));
    assertDoesNotThrow(() -> manager.gcBestEffort(0, false));
    assertEquals(before, assertDoesNotThrow(() -> snapshot(marked)));
  }

  @Test void nonRegularRecoveryControlRefusesWithoutFollowing() throws Exception {
    var manager = new IndexGenerationManager(temp);
    Path active = manager.initializeOrLoad().activeGenerationPath();
    Path external = temp.resolve("external");
    Files.writeString(external, "external bytes");
    Files.createSymbolicLink(IndexRecoveryMarker.pathFor(active), external);
    var before = snapshot(temp);
    assertThrows(IOException.class, manager::initializeOrLoad);
    assertEquals(before, snapshot(temp));
    assertEquals("external bytes", Files.readString(external));
  }

  @Test void oversizedRecoveryMarkerKeepsObligation() throws Exception {
    Path active = new IndexGenerationManager(temp).initializeOrLoad().activeGenerationPath();
    Files.writeString(IndexRecoveryMarker.pathFor(active), "x".repeat(1_025));
    assertTrue(IndexRecoveryMarker.exists(active));
    assertEquals("malformed", IndexRecoveryMarker.readReason(active));
    assertEquals(1_025, Files.size(IndexRecoveryMarker.pathFor(active)));
  }

  @Test void absentActiveRecoversOwnedEvidenceWithRebuildObligation() throws Exception {
    var manager = new IndexGenerationManager(temp);
    Path active = manager.initializeOrLoad().activeGenerationPath();
    SafeIndexPathOps.backupDirectory(active, temp.resolve("indices"));
    boolean restored = assertDoesNotThrow(() -> manager.reconcileActiveRecoveryOwnership());
    assertTrue(restored);
    assertTrue(Files.isRegularFile(active.resolve(SENTINEL)));
    assertTrue(Files.isRegularFile(active.resolve(MANIFEST)));
    assertTrue(IndexRecoveryMarker.exists(active));
    assertDoesNotThrow(manager::initializeOrLoad);
  }

  @Test void unprovenMarkedFoldersDoNotConsumeBuildCapacity() throws Exception {
    var manager = new IndexGenerationManager(temp);
    manager.initializeOrLoad();
    Path marked = foreign("g-20000101-000001.del-20260101-000000");
    var before = snapshot(marked);
    assertDoesNotThrow(() -> manager.startFreshMigration("manual"));
    assertEquals(before, assertDoesNotThrow(() -> snapshot(marked)));
  }

  @Test void everyRebuildPathIgnoresEveryUnprovenMarkedRepresentation() throws Exception {
    for (String shape : List.of("marked", "marker-only", "predecessor")) {
      for (String operation : List.of("ordinary", "explicit", "automatic", "recorded")) {
        Path base = temp.resolve(shape + "-" + operation);
        var manager = new IndexGenerationManager(base);
        var initial = manager.initializeOrLoad();
        Path unproven;
        if (shape.equals("predecessor")) {
          manager.startFreshMigration("manual");
          manager.promoteBuildingGenerationToActive();
          unproven = SafeIndexPathOps.markForDeletion(initial.activeGenerationPath(), base.resolve("indices")).effectivePath();
          Files.delete(unproven.resolve(SENTINEL));
          Files.delete(unproven.resolve(MANIFEST));
        } else {
          unproven = Files.createDirectory(base.resolve("indices/")
              .resolve(shape.equals("marked") ? "g-foreign.del-20260101-000000" : "g-foreign"));
          if (shape.equals("marker-only")) Files.writeString(unproven.resolve("DELETEME"), "foreign");
        }
        Files.writeString(unproven.resolve("foreign"), "foreign bytes");
        var before = snapshot(unproven);
        assertDoesNotThrow(() -> manager.initializeForBoot(new IndexGenerationManager.BootOwnership.Native(), null));
        assertEquals(1, manager.retainedGenerationCount());
        assertFalse(manager.requiresMigrationJournalForBoot());
        switch (operation) {
          case "ordinary" -> assertDoesNotThrow(manager::initializeOrLoad);
          case "explicit" -> assertDoesNotThrow(() -> manager.startFreshMigration("manual"));
          case "automatic" -> {
            assertDoesNotThrow(() -> manager.recordAutoRebuildAttempt("a".repeat(64)));
            assertDoesNotThrow(() -> manager.startMigration("corrupt_index_rebuild"));
          }
          case "recorded" -> assertDoesNotThrow(() -> manager.startRecordedMigration(
              "01994180-0000-7000-8000-000000000119", "recorded-marked", "a".repeat(64)));
          default -> fail(operation);
        }
        assertEquals(before, snapshot(unproven), shape + "/" + operation);
      }
    }
  }

  @Test void malformedStagingIsLeftAndDoesNotBlockFreshBoot() throws Exception {
    Path leftover = Files.createDirectories(temp.resolve("staging/g-old.12345678-0000-4000-8000-000000000001"));
    Files.writeString(leftover.resolve(SENTINEL), sentinel("g-old"));
    Files.writeString(leftover.resolve(MANIFEST), "{interrupted manifest");
    var before = snapshot(leftover);
    var manager = new IndexGenerationManager(temp);
    assertDoesNotThrow(manager::initializeOrLoad);
    assertEquals(before, snapshot(leftover));
  }

  @Test void invalidStagingIdentityIsLeftAndReported() throws Exception {
    for (String identity : List.of("g..foreign", "g-owner.del-20260101-000000")) {
      Path base = temp.resolve(identity);
      Path leftover = Files.createDirectories(base.resolve("staging/" + identity
          + ".12345678-0000-4000-8000-000000000001"));
      Files.writeString(leftover.resolve(SENTINEL), sentinel(identity));
      Files.writeString(leftover.resolve("payload"), "foreign bytes");
      var before = snapshot(leftover);
      var manager = new IndexGenerationManager(base);
      assertDoesNotThrow(manager::initializeOrLoad);
      assertEquals(before, snapshot(leftover));
      assertTrue(manager.cleanupWarnings().stream().anyMatch(warning ->
          warning.path().equals(leftover.toAbsolutePath().normalize().toString())));
    }
  }

  @Test void genuineSentinelOnlyGenerationIsAdopted() throws Exception {
    for (String ending : List.of("\n", "\r\n", "\r")) {
      Path base = temp.resolve("ending-" + ending.length() + "-" + (int) ending.charAt(0));
      Path folder = owned(base, "g-old");
      Files.delete(folder.resolve(MANIFEST));
      Files.writeString(folder.resolve(SENTINEL), sentinel("g-old").replace("\n", ending));
      var layout = new IndexGenerationManager(base).initializeOrLoad();
      assertEquals(folder, layout.activeGenerationPath());
      assertTrue(Files.isRegularFile(folder.resolve(MANIFEST)));
    }
  }

  @Test void recordedLineEndingsRemainCompatibleAndTimestampBindingRemainsStrict() throws Exception {
    var manager = new IndexGenerationManager(temp);
    var initial = manager.initializeOrLoad();
    String key = "01994180-0000-7000-8000-000000000119";
    var state = manager.startRecordedMigration(key, "line-ending", "a".repeat(64));
    Path candidate = temp.resolve("indices").resolve(state.building_generation());
    String body = Files.readString(candidate.resolve(SENTINEL));
    Files.writeString(candidate.resolve(SENTINEL), body.replace("\n", "\r\n"));
    var owner = new IndexGenerationManager.BootOwnership.Recorded(key, initial.activeGenerationId(),
        "line-ending", "a".repeat(64), true);
    var boot = assertDoesNotThrow(() -> manager.initializeForBoot(owner, "a".repeat(64)));
    assertEquals(IndexGenerationManager.BootDisposition.BUILDING, boot.disposition());
    long created = new tools.jackson.databind.ObjectMapper().readTree(Files.readAllBytes(candidate.resolve(MANIFEST)))
        .path("created_at_ms").asLong();
    Files.writeString(candidate.resolve(SENTINEL), body.replace("created_at_ms=" + created,
        "created_at_ms=" + (created + 1)));
    var before = snapshot(temp);
    assertThrows(IOException.class, () -> manager.initializeForBoot(owner, "a".repeat(64)));
    assertEquals(before, snapshot(temp));
  }

  @Test void backupRepresentationIsNeverOpenedByAServingPointer() throws Exception {
    var manager = new IndexGenerationManager(temp);
    var layout = manager.initializeOrLoad();
    Path backup = SafeIndexPathOps.backupDirectory(layout.activeGenerationPath(), temp.resolve("indices"));
    Path state = temp.resolve("state.json");
    Files.writeString(state, Files.readString(state).replace(layout.activeGenerationId(), backup.getFileName().toString()));
    var before = snapshot(temp);
    assertThrows(IOException.class, manager::initializeOrLoad);
    assertEquals(before, snapshot(temp));
  }

  @Test void genuineDiagnosticBackupRemainsCollectableBesideProtectedActive() throws Exception {
    var manager = new IndexGenerationManager(temp);
    Path active = manager.initializeOrLoad().activeGenerationPath();
    Path backup = SafeIndexPathOps.backupDirectory(active, temp.resolve("indices"));
    assertDoesNotThrow(() -> manager.reconcileActiveRecoveryOwnership());
    assertEquals(1, manager.retainedGenerationCount());
    var result = assertDoesNotThrow(() -> manager.gcBestEffort(0, false));
    assertEquals(1, result.prunedCount());
    assertFalse(Files.exists(backup));
    assertTrue(Files.isRegularFile(active.resolve(SENTINEL)));
    assertTrue(Files.isRegularFile(active.resolve(MANIFEST)));
  }

  @Test void foreignMarkedFoldersSurviveBothGcModes() throws Exception {
    var manager = new IndexGenerationManager(temp);
    manager.initializeOrLoad();
    Path folder = foreign("g-old.del-20260101-000000");
    var before = snapshot(folder);
    assertDoesNotThrow(() -> manager.gcBestEffort(0, true));
    assertDoesNotThrow(() -> manager.gcBestEffort(0, false));
    assertEquals(before, snapshot(folder));
  }

  @Test void genuineBackupMarkedNamesRemainCollectable() throws Exception {
    var manager = new IndexGenerationManager(temp);
    manager.initializeOrLoad();
    Path backup = owned(temp, "g-old");
    Path marked = backup.resolveSibling("g-old.bak-20260101-000000.del-20260102-000000");
    Files.move(backup, marked);
    assertDoesNotThrow(() -> manager.gcBestEffort(0, true));
    assertFalse(Files.exists(marked));
  }

  // Releases up to v0.3.0 rebuilt the index in place during recovery: the pointer names <id>,
  // <id> holds only Lucene files, and the sentinel and manifest survive only in <id>.bak-<ts>.
  @Test void release030InPlaceRecoveredActiveStartsWithMetadataFromBackup() throws Exception {
    String id = "g-20250701-000000";
    Path indices = Files.createDirectories(temp.resolve("indices"));
    Path backup = Files.createDirectory(indices.resolve(id + ".bak-20250801-000000"));
    Files.writeString(backup.resolve(SENTINEL), sentinel(id));
    Files.writeString(backup.resolve(MANIFEST), v010Manifest(id));
    Path active = luceneOnly(indices.resolve(id));
    Files.writeString(temp.resolve("state.json"), v010State(id));
    var manager = new IndexGenerationManager(temp);
    assertTrue(assertDoesNotThrow(() -> manager.reconcileActiveRecoveryOwnership()));
    assertEquals(id, assertDoesNotThrow(manager::initializeOrLoad).activeGenerationId());
    assertEquals(Files.readString(backup.resolve(SENTINEL)), Files.readString(active.resolve(SENTINEL)));
    assertEquals(Files.readString(backup.resolve(MANIFEST)), Files.readString(active.resolve(MANIFEST)));
    assertTrue(Files.isRegularFile(active.resolve("segments_1")));
  }

  @Test void inPlaceRecoveredActiveWithCurrentStateStartsThroughBoot() throws Exception {
    var manager = new IndexGenerationManager(temp);
    Path active = manager.initializeOrLoad().activeGenerationPath();
    String sentinelBytes = Files.readString(active.resolve(SENTINEL));
    SafeIndexPathOps.backupDirectory(active, temp.resolve("indices"));
    luceneOnly(active);
    var restarted = new IndexGenerationManager(temp);
    assertTrue(assertDoesNotThrow(() -> restarted.reconcileActiveRecoveryOwnership()));
    var boot = assertDoesNotThrow(() -> restarted.initializeForBoot(
        new IndexGenerationManager.BootOwnership.Native(), null));
    assertEquals(active, boot.layout().activeGenerationPath());
    assertEquals(sentinelBytes, Files.readString(active.resolve(SENTINEL)));
  }

  @Test void inPlaceRecoveredLayoutWithForeignFileIsRefusedUnchanged() throws Exception {
    String id = "g-20250701-000000";
    Path indices = Files.createDirectories(temp.resolve("indices"));
    Path backup = Files.createDirectory(indices.resolve(id + ".bak-20250801-000000"));
    Files.writeString(backup.resolve(SENTINEL), sentinel(id));
    Files.writeString(backup.resolve(MANIFEST), v010Manifest(id));
    Path active = luceneOnly(indices.resolve(id));
    Files.writeString(active.resolve("foreign.txt"), "foreign bytes");
    Files.writeString(temp.resolve("state.json"), v010State(id));
    var manager = new IndexGenerationManager(temp);
    var before = snapshot(temp);
    var refusal = assertThrows(IOException.class, () -> manager.reconcileActiveRecoveryOwnership());
    assertTrue(refusal.getMessage().contains("index.ownership_unproven"), refusal.toString());
    assertThrows(IOException.class, manager::initializeOrLoad);
    assertEquals(before, snapshot(temp));
  }

  // Releases up to v0.1.0 wrote the sentinel with a plain truncating write, so a crash could leave it empty.
  @Test void emptySentinelBesideValidManifestStillStarts() throws Exception {
    Path active = emptySentinelLayout("g-20250701-000000");
    assertEquals("g-20250701-000000", assertDoesNotThrow(() -> new IndexGenerationManager(temp).initializeOrLoad())
        .activeGenerationId());
    assertTrue(Files.isRegularFile(active.resolve("segments_1")));
  }

  @Test void emptySentinelIsRewrittenFromTheManifestIdentity() throws Exception {
    Path active = emptySentinelLayout("g-20250701-000000");
    assertDoesNotThrow(() -> new IndexGenerationManager(temp).initializeOrLoad());
    assertEquals("justsearch_generation_sentinel_v1\ngeneration_id=g-20250701-000000\ncreated_at_ms=1751328000000\n",
        Files.readString(active.resolve(SENTINEL)));
  }

  private Path emptySentinelLayout(String id) throws Exception {
    Path active = Files.createDirectories(temp.resolve("indices").resolve(id));
    Files.write(active.resolve(SENTINEL), new byte[0]);
    Files.writeString(active.resolve(MANIFEST), v010Manifest(id));
    Files.writeString(active.resolve("segments_1"), "x");
    Files.writeString(temp.resolve("state.json"), v010State(id));
    return active;
  }

  @Test void emptySentinelWithoutManifestProofStaysRefused() throws Exception {
    String id = "g-20250701-000000";
    for (String shape : List.of("no-manifest", "other-identity", "no-pointer")) {
      Path base = Files.createDirectory(temp.resolve(shape));
      Path active = Files.createDirectories(base.resolve("indices").resolve(id));
      Files.write(active.resolve(SENTINEL), new byte[0]);
      if (!shape.equals("no-manifest")) {
        Files.writeString(active.resolve(MANIFEST), v010Manifest(shape.equals("other-identity") ? "g-other" : id));
      }
      Files.writeString(active.resolve("segments_1"), "x");
      if (!shape.equals("no-pointer")) Files.writeString(base.resolve("state.json"), v010State(id));
      assertReadOnlyRefusal(new IndexGenerationManager(base), active);
    }
  }

  @Test void indexBaseBelowOrAtASymlinkStartsFreshAndExisting() throws Exception {
    Path real = Files.createDirectories(temp.resolve("real"));
    Path link = Files.createSymbolicLink(temp.resolve("link"), real);
    assertDoesNotThrow(() -> new IndexGenerationManager(link.resolve("data/fresh-index")).initializeOrLoad(),
        "fresh install below a linked ancestor");
    String id = "g-20250615-120000";
    Path generation = Files.createDirectories(real.resolve("data/index/indices").resolve(id));
    Files.writeString(generation.resolve(SENTINEL), sentinel(id));
    Files.writeString(generation.resolve(MANIFEST), v010Manifest(id));
    Files.writeString(real.resolve("data/index/state.json"), v010State(id));
    assertEquals(id, assertDoesNotThrow(() -> new IndexGenerationManager(link.resolve("data/index"))
        .initializeOrLoad()).activeGenerationId(), "existing index below a linked ancestor");
    Path movedBase = Files.createSymbolicLink(temp.resolve("moved-index"), real.resolve("data/index"));
    assertEquals(id, assertDoesNotThrow(() -> new IndexGenerationManager(movedBase).initializeOrLoad())
        .activeGenerationId(), "index base that is itself a link");
  }

  private static Path luceneOnly(Path directory) throws Exception {
    Files.createDirectories(directory);
    for (String name : List.of("segments_1", "write.lock", "_0.cfs", "_0.cfe", "_0.si", "_0_1.liv")) {
      Files.writeString(directory.resolve(name), "rebuilt in place");
    }
    return directory;
  }

  /** Manifest as written by the v0.1.0 writer (Jackson default pretty printer, format 1). */
  private static String v010Manifest(String identity) {
    return "{\n  \"format_version\" : 1,\n  \"generation_id\" : \"" + identity
        + "\",\n  \"source\" : \"new\",\n  \"created_at_ms\" : 1751328000000\n}";
  }

  /** State pointer as written by the v0.1.0 writer (format 2, null fields omitted). */
  private static String v010State(String active) {
    return "{\n  \"format_version\" : 2,\n  \"active_generation\" : \"" + active
        + "\",\n  \"migration_state\" : \"IDLE\",\n  \"migration_paused\" : false,\n"
        + "  \"updated_at_ms\" : 1751328000000\n}";
  }

  private Path foreign(String name) throws Exception {
    Path folder = Files.createDirectories(temp.resolve("indices").resolve(name));
    Files.writeString(folder.resolve("payload"), "foreign bytes");
    return folder;
  }

  private void assertReadOnlyRefusal(IndexGenerationManager manager, Path rejected) throws Exception {
    var before = snapshot(rejected.getParent().getParent());
    var refusal = assertThrows(IOException.class, manager::initializeOrLoad);
    assertTrue(refusal.getMessage().contains("index.ownership_unproven"), refusal.toString());
    assertEquals(before, snapshot(rejected.getParent().getParent()));
  }

  private static Path owned(Path base, String identity) throws Exception {
    Path folder = Files.createDirectories(base.resolve("indices").resolve(identity));
    Files.writeString(folder.resolve(SENTINEL), sentinel(identity));
    Files.writeString(folder.resolve(MANIFEST), manifest(identity));
    return folder;
  }

  private static String sentinel(String identity) {
    return "justsearch_generation_sentinel_v1\ngeneration_id=" + identity + "\ncreated_at_ms=1\n";
  }

  private static String manifest(String identity) {
    return "{\"format_version\":1,\"generation_id\":\"" + identity + "\",\"source\":\"new\",\"created_at_ms\":2}";
  }

  private static Map<String, String> snapshot(Path base) throws Exception {
    Map<String, String> result = new TreeMap<>();
    try (var paths = Files.walk(base)) {
      for (Path path : paths.toList()) {
        String value = Files.isSymbolicLink(path) ? "link:" + Files.readSymbolicLink(path)
            : Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "directory"
            : java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(path));
        result.put(base.relativize(path).toString(), value);
      }
    }
    return result;
  }
}
