package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.indexerworker.loop.FileFreshnessSnapshot.SourceValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link FileFreshness} seam (governance/logic-seams.v1.json) — the pure freshness
 * classifier extracted from FileFreshnessSnapshot. Each branch + the priority order is asserted, so
 * a flipped comparison or reordered check (which would silently misclassify a changed file as FRESH)
 * is caught. Plain JUnit; no jqwik (supply-chain rationale, tempdoc 555 §10).
 */
class FileFreshnessClassifyTest {

  private static FileFreshnessSnapshot snap(
      String normalizedPath, Object fileKey, long size, long mtime, boolean regular) {
    return new FileFreshnessSnapshot(null, normalizedPath, "hash", fileKey, size, java.nio.file.attribute.FileTime.fromMillis(mtime), null, regular, 0L);
  }

  private static final FileFreshnessSnapshot BASE = snap("/a/b.txt", "key1", 100L, 1000L, true);

  @Test
  @DisplayName("identical snapshots → FRESH")
  void identicalIsFresh() {
    assertEquals(SourceValidationResult.FRESH, FileFreshness.classify(BASE, snap("/a/b.txt", "key1", 100L, 1000L, true)));
  }

  @Test
  @DisplayName("each single difference maps to its specific result")
  void eachDifference() {
    assertEquals(SourceValidationResult.DELETED, FileFreshness.classify(BASE, snap("/a/OTHER.txt", "key1", 100L, 1000L, true)));
    assertEquals(SourceValidationResult.SOURCE_KIND_CHANGED, FileFreshness.classify(BASE, snap("/a/b.txt", "key1", 100L, 1000L, false)));
    assertEquals(SourceValidationResult.SIZE_CHANGED, FileFreshness.classify(BASE, snap("/a/b.txt", "key1", 200L, 1000L, true)));
    assertEquals(SourceValidationResult.MODIFIED_TIME_CHANGED, FileFreshness.classify(BASE, snap("/a/b.txt", "key1", 100L, 2000L, true)));
    assertEquals(SourceValidationResult.FILE_KEY_CHANGED, FileFreshness.classify(BASE, snap("/a/b.txt", "key2", 100L, 1000L, true)));
  }

  @Test
  @DisplayName("null file-key transitions trigger FILE_KEY_CHANGED")
  void nullFileKeyChanged() {
    assertEquals(SourceValidationResult.FILE_KEY_CHANGED, FileFreshness.classify(snap("/a/b.txt", null, 100L, 1000L, true), snap("/a/b.txt", "key1", 100L, 1000L, true)));
    assertEquals(SourceValidationResult.FILE_KEY_CHANGED, FileFreshness.classify(BASE, snap("/a/b.txt", null, 100L, 1000L, true)));
  }

  @Test
  @DisplayName("priority order: path > kind > size > mtime > key (first difference wins)")
  void priorityOrder() {
    // every field differs → the highest-priority (path) result wins
    assertEquals(
        SourceValidationResult.DELETED,
        FileFreshness.classify(BASE, snap("/a/OTHER.txt", "key2", 200L, 2000L, false)));
    // path equal, kind+size+mtime+key differ → kind wins
    assertEquals(
        SourceValidationResult.SOURCE_KIND_CHANGED,
        FileFreshness.classify(BASE, snap("/a/b.txt", "key2", 200L, 2000L, false)));
    // path+kind equal, size+mtime+key differ → size wins
    assertEquals(
        SourceValidationResult.SIZE_CHANGED,
        FileFreshness.classify(BASE, snap("/a/b.txt", "key2", 200L, 2000L, true)));
    // path+kind+size equal, mtime+key differ → mtime wins
    assertEquals(
        SourceValidationResult.MODIFIED_TIME_CHANGED,
        FileFreshness.classify(BASE, snap("/a/b.txt", "key2", 100L, 2000L, true)));
  }
}
