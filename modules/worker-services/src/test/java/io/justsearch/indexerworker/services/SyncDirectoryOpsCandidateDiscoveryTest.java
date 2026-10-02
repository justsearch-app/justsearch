/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.ReadPathOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class SyncDirectoryOpsCandidateDiscoveryTest {
  @TempDir Path tempDir;

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void strictDiscoverySharesCharacterBudgetAcrossIndexAndDisk(boolean force) throws Exception {
    Path file = Files.writeString(tempDir.resolve("long-" + "a".repeat(160) + ".txt"), "text");
    String normalized = PathNormalizer.normalizeKey(file);
    // Disk alone fits (one normalized key plus conservative Path storage); index + disk does not.
    var ops = new SyncDirectoryOps(paths(file), null, null, null,
        IndexingPacing.unthrottled(), null, 200_000, 3 * normalized.length());

    IOException failure = assertThrows(IOException.class,
        () -> ops.discoverCandidateDifference(tempDir.toString(), force));

    assertTrue(failure.getMessage().contains("path characters"), failure.getMessage());
  }

  @Test
  void strictIndexCursorHistoryAlsoConsumesTheRetainedCharacterBudget() {
    var reads = paths();
    when(reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenReturn(new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0, "cursor".repeat(100)));
    var ops = new SyncDirectoryOps(reads, null, null, null,
        IndexingPacing.unthrottled(), null, 200_000, 128);

    IOException failure = assertThrows(IOException.class,
        () -> ops.discoverCandidateDifference(tempDir.toString(), true));

    assertTrue(failure.getMessage().contains("path characters"), failure.getMessage());
  }

  @Test
  void cooperativeExpiryDuringStrictIndexScanNeverReturnsAPartialSnapshot() {
    var expired = new AtomicBoolean();
    ReadPathOps reads = paths();
    when(reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenAnswer(ignored -> {
          expired.set(true);
          return new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0);
        });
    var ops = new SyncDirectoryOps(reads, null, null, null, IndexingPacing.unthrottled(), null);

    IOException failure = assertThrows(IOException.class,
        () -> ops.discoverCandidateDifference(tempDir.toString(), true, expired::get));

    assertTrue(failure.getMessage().contains("cancelled"));
    assertTrue(!Thread.currentThread().isInterrupted(), "Expiry is cooperative, not an interrupt");
  }

  @Test
  void discoversAddsAndConfirmedMissingCandidatesWithoutMutation() throws Exception {
    Path retained = Files.writeString(tempDir.resolve("retained.txt"), "retained");
    Path added = Files.writeString(tempDir.resolve("added.txt"), "added");
    Path deleted = tempDir.resolve("deleted.txt").toAbsolutePath();
    ReadPathOps reads = paths(retained, deleted);
    var ops = new SyncDirectoryOps(
        reads, null, null, null, IndexingPacing.unthrottled(), null);

    try (SyncDirectoryOps.RootDifference difference =
        ops.discoverCandidateDifference(tempDir.toString(), false)) {
      assertEquals(List.of(added.toAbsolutePath()), difference.additions());
      assertEquals(List.of(PathNormalizer.normalizeKey(deleted)), difference.deletions());
      difference.requireCurrentRootIdentity();
    }
  }

  @Test
  void missingServingReadPathAndPathCapFailClosed() throws Exception {
    var missing = new SyncDirectoryOps(
        null, null, null, null, IndexingPacing.unthrottled(), null);
    assertThrows(IOException.class,
        () -> missing.discoverCandidateDifference(tempDir.toString(), false));

    Path first = tempDir.resolve("first.txt").toAbsolutePath();
    Path second = tempDir.resolve("second.txt").toAbsolutePath();
    var capped = new SyncDirectoryOps(
        paths(first, second), null, null, null, IndexingPacing.unthrottled(), null, 2);
    assertThrows(IOException.class,
        () -> capped.discoverCandidateDifference(tempDir.toString(), false));
  }

  @Test
  void interruptedWalkNeverReturnsAPartialDifference() {
    var ops = new SyncDirectoryOps(
        paths(), null, null, null, IndexingPacing.unthrottled(), null);
    Thread.currentThread().interrupt();
    try {
      assertThrows(IOException.class,
          () -> ops.discoverCandidateDifference(tempDir.toString(), false));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void rootRemovedAfterValidationNeverReturnsAnEmptyHealthyDifference() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("removed-during-scan"));
    ReadPathOps reads = paths();
    when(reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenAnswer(ignored -> {
          Files.delete(root);
          return new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0L);
        });
    var ops = new SyncDirectoryOps(
        reads, null, null, null, IndexingPacing.unthrottled(), null);

    assertThrows(IOException.class, () -> ops.discoverCandidateDifference(root.toString(), false));
  }

  @Test
  void rootReplacementAfterValidationNeverReturnsAHealthyDifference() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("replaced-during-scan"));
    AtomicBoolean replacementRan = new AtomicBoolean();
    ReadPathOps reads = paths();
    when(reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenAnswer(ignored -> {
          Files.delete(root);
          Files.createDirectory(root);
          replacementRan.set(true);
          return new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0L);
        });
    var ops = new SyncDirectoryOps(
        reads, null, null, null, IndexingPacing.unthrottled(), null);

    assertThrows(IOException.class, () -> ops.discoverCandidateDifference(root.toString(), false));
    assertTrue(replacementRan.get());
  }

  @Test
  void legalNoFileProjectionIsExcludedFromFileReconciliation() throws Exception {
    ReadPathOps reads = searchHits(new LuceneRuntimeTypes.SearchHit(
        "memory:record-7", 1.0f,
        Map.of(
            SchemaFields.PATH, tempDir.resolve("projection.txt").toString(),
            SchemaFields.PROJECTION_SOURCE_ID, "source-7")));
    var ops = new SyncDirectoryOps(
        reads, null, null, null, IndexingPacing.unthrottled(), null);

    try (SyncDirectoryOps.RootDifference difference =
        ops.discoverCandidateDifference(tempDir.toString(), false)) {
      assertEquals(List.of(), difference.deletions());
    }
  }

  @Test
  void sameCreationTimeCannotMakeDifferentStableIdsEqual() throws Exception {
    Path realPath = tempDir.toRealPath();
    FileTime sharedCreationTime = Files.readAttributes(
        tempDir, BasicFileAttributes.class).creationTime();
    RootIdentity first = new RootIdentity(
        realPath, new RootIdentity.ProviderFileIdentity("stable-file-id-a"));
    RootIdentity replacement = new RootIdentity(
        realPath, new RootIdentity.ProviderFileIdentity("stable-file-id-b"));
    TimestampedIdentity firstObservation = new TimestampedIdentity(first, sharedCreationTime);
    TimestampedIdentity replacementObservation =
        new TimestampedIdentity(replacement, sharedCreationTime);

    assertEquals(firstObservation.creationTime(), replacementObservation.creationTime());
    assertThrows(
        IOException.class,
        () -> firstObservation.identity().requireSame(replacementObservation.identity(), realPath));
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void windowsNativeFileIdSmokeAlsoCoversRefsWhenTempDirectoryIsOnRefs() throws Exception {
    RootIdentity identity = RootIdentity.capture(tempDir);

    assertTrue(identity.stableFileIdentity() instanceof RootIdentity.WindowsFileIdentity);
    identity.requireCurrent(tempDir);
    if (Files.getFileStore(tempDir).type().equalsIgnoreCase("ReFS")) {
      RootIdentity.WindowsFileIdentity nativeId =
          (RootIdentity.WindowsFileIdentity) identity.stableFileIdentity();
      assertEquals(32, nativeId.fileId128().length());
    }
  }

  @Test
  void mismatchedOrOutOfRootFileMetadataFailsClosed() {
    String canonical = PathNormalizer.normalizeKey(tempDir.resolve("file.txt"));
    ReadPathOps mismatch = searchHits(new LuceneRuntimeTypes.SearchHit(
        canonical, 1.0f,
        Map.of(SchemaFields.PATH, PathNormalizer.normalizeKey(tempDir.resolve("other.txt")))));
    var mismatchedOps = new SyncDirectoryOps(
        mismatch, null, null, null, IndexingPacing.unthrottled(), null);
    assertThrows(IOException.class,
        () -> mismatchedOps.discoverCandidateDifference(tempDir.toString(), false));

    String outside = PathNormalizer.normalizeKey(tempDir.getParent().resolve("outside.txt"));
    ReadPathOps outsideRoot = searchHits(new LuceneRuntimeTypes.SearchHit(
        outside, 1.0f, Map.of(SchemaFields.PATH, outside)));
    var outsideOps = new SyncDirectoryOps(
        outsideRoot, null, null, null, IndexingPacing.unthrottled(), null);
    assertThrows(IOException.class,
        () -> outsideOps.discoverCandidateDifference(tempDir.toString(), false));

    ReadPathOps relative = searchHits(new LuceneRuntimeTypes.SearchHit(
        "relative.txt", 1.0f, Map.of(SchemaFields.PATH, "relative.txt")));
    var relativeOps = new SyncDirectoryOps(
        relative, null, null, null, IndexingPacing.unthrottled(), null);
    assertThrows(IOException.class,
        () -> relativeOps.discoverCandidateDifference(tempDir.toString(), false));
  }

  private static ReadPathOps paths(Path... paths) {
    List<LuceneRuntimeTypes.SearchHit> hits = java.util.Arrays.stream(paths)
        .map(path -> {
          String normalized = PathNormalizer.normalizeKey(path);
          return new LuceneRuntimeTypes.SearchHit(
              normalized, 1.0f, Map.of(SchemaFields.PATH, normalized));
        })
        .toList();
    return searchHits(hits.toArray(LuceneRuntimeTypes.SearchHit[]::new));
  }

  private record TimestampedIdentity(RootIdentity identity, FileTime creationTime) {}

  private static ReadPathOps searchHits(LuceneRuntimeTypes.SearchHit... hitsArray) {
    ReadPathOps reads = mock(ReadPathOps.class);
    List<LuceneRuntimeTypes.SearchHit> hits = List.of(hitsArray);
    when(reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenReturn(new LuceneRuntimeTypes.SearchResult(hits, hits.size(), 0L));
    return reads;
  }
}
