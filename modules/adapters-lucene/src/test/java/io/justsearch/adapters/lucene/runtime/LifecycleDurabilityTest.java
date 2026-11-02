/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

final class LifecycleDurabilityTest extends RuntimeTestBase {
  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"writable", "read-only", "deferred"})
  void failClosedPreservesCorruptDirectory(String mode) throws Exception {
    Path index = seed();
    Path segments;
    try (var files = Files.list(index)) {
      segments = files.filter(p -> p.getFileName().toString().startsWith("segments_")).findFirst().orElseThrow();
    }
    Files.write(segments, new byte[] {1, 2, 3});
    var before = directoryBytes(index);
    var builder = builder(index, "STRUCTURAL", true, "FAIL_CLOSED");
    assertThrows(IndexRuntimeIOException.class, () -> {
      try (var ignored = open(builder, mode)) { }
    });
    assertEquals(before, directoryBytes(index));
    try (var files = Files.list(index.getParent())) {
      assertFalse(files.anyMatch(p -> p.getFileName().toString().contains(".bak-")));
    }
  }

  @Test
  void dirtyWritableOpenDetectsBodyCorruption() throws Exception {
    assertDirtyBodyDetected("writable");
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"read-only", "deferred"})
  void dirtyReadOnlyOpenStillDetectsBodyCorruption(String mode) throws Exception {
    assertDirtyBodyDetected(mode);
  }

  private void assertDirtyBodyDetected(String mode) throws Exception {
    Path index = seed();
    corruptBody(index);
    try (var dir = FSDirectory.open(index)) {
      assertDoesNotThrow(() -> ComponentsFactory.checkIndexIntegrity(dir, index, false));
    }
    Files.delete(CleanShutdownMarker.pathFor(index));
    assertThrows(IndexRuntimeIOException.class, () -> {
      try (var ignored = open(builder(index, "STRUCTURAL", false, "FAIL_CLOSED"), mode)) { }
    });
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"STRUCTURAL", "OFF"})
  void cleanStructuralAndExplicitOffKeepTheirVerificationTier(String tier) throws Exception {
    Path index = seed();
    corruptBody(index);
    if (tier.equals("OFF")) Files.delete(CleanShutdownMarker.pathFor(index));
    assertDoesNotThrow(() -> {
      try (var ignored = builder(index, tier, false, "FAIL_CLOSED").openReadOnly()) { }
    });
  }

  @Test
  void markerInvalidationFailureRefusesWritableOpen() throws Exception {
    Path index = seed();
    Path marker = CleanShutdownMarker.pathFor(index);
    Files.delete(marker);
    Files.createDirectory(marker);
    Files.writeString(marker.resolve("held"), "cannot delete a nonempty marker directory");
    assertThrows(IndexRuntimeIOException.class, () -> {
      try (var ignored = builder(index, "STRUCTURAL", false, "FAIL_CLOSED").open()) { }
    });
    assertTrue(Files.exists(marker.resolve("held")));
    Files.delete(marker.resolve("held"));
    Files.delete(marker);
    assertDoesNotThrow(() -> {
      try (var ignored = builder(index, "STRUCTURAL", false, "FAIL_CLOSED").open()) { }
    });
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"manager", "writer", "directory"})
  void failedCloseRetainsOwnerAndDoesNotRepeatEarlierStages(String stage) throws Exception {
    var session = new RuntimeSession(buildSchemaWithDim(4));
    var manager = mock(SearcherManager.class);
    var writer = mock(IndexWriter.class);
    var directory = mock(Directory.class);
    Path index = Files.createDirectory(tempDir.resolve("retained"));
    Files.writeString(index.resolve("payload"), "must survive incomplete cleanup");
    when(writer.isOpen()).thenReturn(true);
    if (stage.equals("manager")) doThrow(new IOException("manager close")).doNothing().when(manager).close();
    if (stage.equals("writer")) doThrow(new IOException("writer close")).doNothing().when(writer).close();
    if (stage.equals("directory")) doThrow(new IOException("directory close")).doNothing().when(directory).close();
    session.snapshot = new LifecycleSnapshot(directory, writer, manager, index, true, null);
    assertThrows(IndexRuntimeIOException.class, session::close);
    assertNotNull(session.snapshot);
    assertTrue(Files.exists(index.resolve("payload")));
    assertFalse(CleanShutdownMarker.wasClean(index));
    assertDoesNotThrow(session::close);
    verify(manager, times(stage.equals("manager") ? 2 : 1)).close();
    verify(writer, times(stage.equals("writer") ? 2 : 1)).close();
    verify(directory, times(stage.equals("directory") ? 2 : 1)).close();
    assertNull(session.snapshot);
    assertFalse(Files.exists(index));
    assertDoesNotThrow(session::close);
  }

  @Test
  void failedAdmissionReaderCloseIsReportedAndRetried() throws Exception {
    Path index = seed();
    var runtime = builder(index, "STRUCTURAL", false, "FAIL_CLOSED").open();
    runtime.commitOps().stopCommitTimer();
    runtime.commitOps().closeAdmissionReader();
    var admission = mock(SearcherManager.class);
    doThrow(new IOException("admission close failed")).doNothing().when(admission).close();
    var field = CommitOps.class.getDeclaredField("admissionManager");
    field.setAccessible(true);
    field.set(runtime.commitOps(), admission);
    try {
      assertThrows(IndexRuntimeIOException.class, runtime::close);
      assertNotNull(runtime.session().snapshot);
      assertTrue(runtime.session().snapshot.writer().isOpen());
      assertFalse(CleanShutdownMarker.wasClean(index));
      assertDoesNotThrow(runtime::close);
      verify(admission, times(2)).close();
      assertTrue(CleanShutdownMarker.wasClean(index));
    } finally { runtime.close(); }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"BACKUP_ONLY", "BACKUP_REBUILD"})
  void permittedRecoveryKeepsBackupAndRecoveryMarker(String policy) throws Exception {
    Path index = seed();
    Path commit;
    try (var files = Files.list(index)) {
      commit = files.filter(p -> p.getFileName().toString().startsWith("segments_")).findFirst().orElseThrow();
    }
    byte[] damaged = {1, 2, 3};
    Files.write(commit, damaged);
    try (var recovered = builder(index, "STRUCTURAL", true, policy).open()) {
      assertEquals(0, recovered.indexCountOps().docCount());
      assertTrue(IndexRecoveryMarker.exists(index));
    }
    try (var files = Files.list(index.getParent())) {
      Path backup = files.filter(p -> p.getFileName().toString().contains(".bak-")).findFirst().orElseThrow();
      assertArrayEquals(damaged, Files.readAllBytes(backup.resolve(commit.getFileName())));
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"locked", "permission", "transient"})
  void nonCorruptionOpenFailuresNeverMoveTheIndex(String kind) throws Exception {
    Path index = seed();
    var before = directoryBytes(index);
    IOException injected = switch (kind) {
      case "locked" -> new org.apache.lucene.store.LockObtainFailedException("busy");
      case "permission" -> new java.nio.file.AccessDeniedException(index.toString());
      default -> new IOException("transient read failure");
    };
    var reason = ComponentsFactory.openFailureReason(injected);
    assertNotEquals(IndexRuntimeIOException.Reason.CORRUPT_INDEX, reason);
    var builder = builder(index, "STRUCTURAL", true, "BACKUP_REBUILD")
        .withIndexOpenGuard(() -> { throw new IndexRuntimeIOException(reason, "injected open failure", injected); });
    assertThrows(IndexRuntimeIOException.class, builder::open);
    assertEquals(before, directoryBytes(index));
    try (var files = Files.list(index.getParent())) {
      assertFalse(files.anyMatch(p -> p.getFileName().toString().contains(".bak-")));
    }
  }

  private Path seed() throws Exception {
    Path index = tempDir.resolve("index");
    try (var runtime = builder(index, "STRUCTURAL", false, "FAIL_CLOSED").open()) {
      runtime.session().snapshot.writer().getConfig().setUseCompoundFile(false);
      var random = new java.util.Random(17);
      var content = new StringBuilder();
      for (int i = 0; i < 4096; i++) content.append(Integer.toHexString(random.nextInt())).append(' ');
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "seed", SchemaFields.DOC_UID, "seed#0",
          SchemaFields.CONTENT, content.toString())));
      runtime.commitOps().commitAndTrack(CommitReason.RESET);
    }
    assertTrue(CleanShutdownMarker.wasClean(index));
    return index;
  }

  private LuceneRuntimeBuilder builder(Path index, String tier, boolean recovery, String policy) throws Exception {
    var config = ResolvedConfig.builder();
    config.contributeEnvRegistry();
    config.contributeYaml(new ObjectMapper(new YAMLFactory()).readTree(
        "index:\n  integrity_check: " + tier + "\n  auto_recovery: " + recovery
            + "\n  recovery:\n    policy: " + policy + "\n"));
    return buildSchemaWithDim(4).atPath(index).withFallbackIndexPath(tempDir)
        .withExecutorRegistrations(testLuceneExecutors()).withConfig(config.build());
  }

  private static LuceneRuntime open(LuceneRuntimeBuilder builder, String mode) {
    return switch (mode) {
      case "writable" -> builder.open();
      case "read-only" -> builder.openReadOnly();
      case "deferred" -> builder.openDeferred();
      default -> throw new IllegalArgumentException(mode);
    };
  }

  private static Map<String, String> directoryBytes(Path index) throws IOException {
    var bytes = new java.util.TreeMap<String, String>();
    try (var files = Files.list(index)) {
      for (Path path : files.toList()) {
        if (!path.getFileName().toString().equals("write.lock")) {
          bytes.put(path.getFileName().toString(), java.util.HexFormat.of().formatHex(Files.readAllBytes(path)));
        }
      }
    }
    return bytes;
  }

  private static void corruptBody(Path index) throws IOException {
    Path data;
    try (var files = Files.list(index)) {
      data = files.filter(p -> p.getFileName().toString().endsWith(".fdt"))
          .max(Comparator.comparingLong(p -> p.toFile().length())).orElseThrow();
    }
    byte[] bytes = Files.readAllBytes(data);
    bytes[bytes.length / 2] ^= 0x7f;
    Files.write(data, bytes);
  }
}
