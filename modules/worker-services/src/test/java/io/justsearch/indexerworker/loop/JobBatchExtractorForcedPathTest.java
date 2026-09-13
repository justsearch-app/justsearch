/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

/**
 * Tempdoc 821 §3-C3 — the behavioural half of "make force-reindex real".
 *
 * <p>The C3 class member this closes: a user's force-reindex could not repair a bad enrichment
 * population, because the force flag never left the Head. The Worker admitted the same paths and
 * this extractor took its UNCHANGED branch on every one of them, so nothing was re-extracted and
 * nothing was re-enriched — an installed base with a degenerate chunk/vector population had no
 * self-repair route at all.
 *
 * <p>These tests change NOTHING on disk between the two arms: the same file, the same mtime,
 * {@code isUnmodified} genuinely true. The ONLY difference is whether the path is in the forced
 * set that a {@code SCAN_MODE_FORCE_REINDEX} scan populates (proved end-to-end by
 * {@code WorkerScanOpsTest#forceReindexScanMarksEveryAdmittedPathForced}). The forced key here is
 * derived exactly as {@code WorkerScanOps} derives it, and the file is admitted through the REAL
 * {@link WorkerIngestionAuthority}, so the key agreement between the two sides is under test too —
 * a scan-side key that missed the envelope's would make the force a silent no-op.
 */
@DisplayName("JobBatchExtractor — a forced path bypasses the UNCHANGED branch")
final class JobBatchExtractorForcedPathTest {

  @TempDir Path tempDir;

  /** Everything the extractor needs, with the forced set as the single variable under test. */
  private record Harness(
      JobBatchExtractor extractor,
      DocumentFieldOps documentFieldOps,
      TimeboxedContentExtractor contentExtractor,
      IngestionOutcomeJournal journal,
      BatchStats batchStats,
      DocumentIdentityStore identityStore) {}

  private Harness newHarness(Set<String> forcedPaths) throws Exception {
    DocumentIdentityStore identityStore = mock(DocumentIdentityStore.class);
    when(identityStore.resolve(anyString(), anyLong()))
        .thenReturn(new DocumentIdentityStore.Identity("hash", "test-uid", 1L, 1L));
    return newHarness(forcedPaths, identityStore);
  }

  private Harness newHarness(Set<String> forcedPaths, DocumentIdentityStore identityStore)
      throws Exception {
    DocumentFieldOps documentFieldOps = mock(DocumentFieldOps.class);
    // Nothing changed on disk — the index's view of this doc is current. This is the precondition
    // that makes the UNCHANGED branch the DEFAULT outcome, so taking it is not the finding; NOT
    // taking it is.
    when(documentFieldOps.isUnmodified(anyString(), anyLong())).thenReturn(true);

    IndexCountOps indexCountOps = mock(IndexCountOps.class);
    // A non-empty index, so the `indexEmptyForBatch` short-circuit (312 item 10) is NOT what
    // decides the branch — otherwise both arms would skip the unchanged-check for the wrong reason.
    when(indexCountOps.docCount()).thenReturn(7L);

    TimeboxedContentExtractor contentExtractor = mock(TimeboxedContentExtractor.class);
    // Fail extraction deliberately: reaching extraction at all is the signal these tests read,
    // and a terminal ExtractionException is a branch the extractor already handles cleanly.
    when(contentExtractor.extractArtifact(any()))
        .thenThrow(new ContentExtractor.ExtractionException("stubbed — reached extraction"));

    IngestionOutcomeJournal journal = mock(IngestionOutcomeJournal.class);
    BatchStats batchStats = mock(BatchStats.class);
    StaleSnapshotResolver staleResolver = mock(StaleSnapshotResolver.class);

    JobBatchExtractor extractor =
        new JobBatchExtractor(
            new WorkerIngestionAuthority(), // REAL: the envelope's normalizedPath is production's
            journal,
            mock(JobQueue.class),
            contentExtractor,
            documentFieldOps,
            indexCountOps,
            batchStats,
            staleResolver,
            mock(StaleSourceHandler.class),
            IndexingPacing.unthrottled(),
            new AtomicBoolean(true),
            forcedPaths,
            () -> mock(PathResolutionStore.class),
            () -> identityStore,
            mock(IndexingDocumentOps.StageRecorder.class),
            () -> false,
            delta -> {});
    return new Harness(
        extractor, documentFieldOps, contentExtractor, journal, batchStats, identityStore);
  }

  /** The key every marker writes for an admitted path — production's own derivation. */
  private static String forcedKey(Path file) {
    return PathNormalizer.normalizeKey(file);
  }

  /**
   * The derivation {@code WorkerIngestService#submitBatch} used before tempdoc 821 §P/P3: absolutize
   * but never {@link Path#normalize()}. Kept here ONLY as the negative control below.
   */
  private static String preP3Key(Path file) {
    return PathNormalizer.normalizePath(file.toAbsolutePath().toString());
  }

  @Test
  @DisplayName("forced: an untouched file is re-extracted instead of skipped as UNCHANGED")
  void forcedPathIsReExtractedEvenThoughNothingChanged() throws Exception {
    Path file = Files.writeString(tempDir.resolve("doc.txt"), "unchanged content");
    Set<String> forcedPaths = ConcurrentHashMap.newKeySet();
    forcedPaths.add(forcedKey(file));
    Harness h = newHarness(forcedPaths);

    h.extractor().extractAll(List.of(new JobQueue.IndexJob(file, null)));

    verify(h.contentExtractor()).extractArtifact(file);
    verify(h.journal(), never()).recordOutcomeSafely(any(), eq("UNCHANGED"), any());
    // The force short-circuits `!forceReindex && ...` before the mtime lookup, so the check is
    // never even asked — a stronger assertion than "it was asked and ignored".
    verify(h.documentFieldOps(), never()).isUnmodified(anyString(), anyLong());
    assertTrue(forcedPaths.isEmpty(), "the mark is one-shot: consumed by this pass, not sticky");
  }

  @Test
  @DisplayName("not forced: the same untouched file IS skipped as UNCHANGED")
  void unforcedPathTakesTheUnchangedBranch() throws Exception {
    Path file = Files.writeString(tempDir.resolve("doc.txt"), "unchanged content");
    Harness h = newHarness(ConcurrentHashMap.newKeySet());

    List<ExtractedJob> extracted =
        h.extractor().extractAll(List.of(new JobQueue.IndexJob(file, null)));

    // The inverse arm: identical file, identical mocks, empty forced set. Without this, the test
    // above could pass because extraction always runs, proving nothing about the force.
    assertEquals(List.of(), extracted, "an unchanged doc yields no extracted job");
    verify(h.documentFieldOps()).isUnmodified(eq(forcedKey(file)), anyLong());
    verify(h.contentExtractor(), never()).extractArtifact(any());
    verify(h.journal()).recordOutcomeSafely(eq(file), eq("UNCHANGED"), any());
    verify(h.batchStats()).recordSkipped();
  }

  @Test
  @DisplayName("identity is durable before unchanged detection or extraction")
  void identityResolutionPrecedesEveryAdmissionExit() throws Exception {
    Path unchanged = Files.writeString(tempDir.resolve("unchanged.txt"), "same");
    Harness unchangedHarness = newHarness(ConcurrentHashMap.newKeySet());

    unchangedHarness.extractor().extractAll(List.of(new JobQueue.IndexJob(unchanged, null)));

    InOrder unchangedOrder =
        inOrder(unchangedHarness.identityStore(), unchangedHarness.documentFieldOps());
    unchangedOrder.verify(unchangedHarness.identityStore()).resolve(anyString(), anyLong());
    unchangedOrder
        .verify(unchangedHarness.documentFieldOps())
        .isUnmodified(eq(forcedKey(unchanged)), anyLong());

    Path forced = Files.writeString(tempDir.resolve("forced.txt"), "same");
    Set<String> forcedPaths = ConcurrentHashMap.newKeySet();
    forcedPaths.add(forcedKey(forced));
    Harness forcedHarness = newHarness(forcedPaths);

    forcedHarness.extractor().extractAll(List.of(new JobQueue.IndexJob(forced, null)));

    InOrder forcedOrder =
        inOrder(forcedHarness.identityStore(), forcedHarness.contentExtractor());
    forcedOrder.verify(forcedHarness.identityStore()).resolve(anyString(), anyLong());
    forcedOrder.verify(forcedHarness.contentExtractor()).extractArtifact(forced);
  }

  @Test
  @DisplayName("an unavailable identity store fails closed before any indexable artifact exists")
  void unavailableIdentityStoreFailsClosedBeforeExtraction() throws Exception {
    Path file = Files.writeString(tempDir.resolve("identity-unavailable.txt"), "body");
    DocumentIdentityStore identityStore = mock(DocumentIdentityStore.class);
    when(identityStore.resolve(anyString(), anyLong()))
        .thenThrow(new IllegalStateException("identity store unavailable"));
    Harness h = newHarness(ConcurrentHashMap.newKeySet(), identityStore);

    List<ExtractedJob> extracted =
        h.extractor().extractAll(List.of(new JobQueue.IndexJob(file, null)));

    assertEquals(List.of(), extracted, "no artifact may escape without a persisted identity");
    verify(h.contentExtractor(), never()).extractArtifact(any());
    verify(h.documentFieldOps(), never()).isUnmodified(anyString(), anyLong());
    verify(h.journal()).recordOutcomeSafely(eq(file), eq("WRITE_FAILED(document_identity)"), any());
    verify(h.batchStats()).recordFailed();
  }

  /**
   * A path shape that the two derivations disagree about: the same file reached through one
   * redundant {@code ..} hop. {@code PathNormalizer.normalizePath} never resolves that segment, so
   * the pre-P3 key is one segment off the envelope's — marked, never looked up.
   */
  private Path viaRedundantParentHop(Path dir, String fileName) {
    return dir.resolve("..").resolve(dir.getFileName()).resolve(fileName);
  }

  @Test
  @DisplayName("forced: the mark still lands when the marker's path is not lexically normalized")
  void forcedKeySurvivesANonNormalizedPathShape() throws Exception {
    Path dir = Files.createDirectories(tempDir.resolve("sub"));
    Files.writeString(dir.resolve("doc.txt"), "unchanged content");
    Path submitted = viaRedundantParentHop(dir, "doc.txt");

    // Precision: the shape is genuinely discriminating, so the arms below cannot both pass because
    // the two derivations happen to agree on this input.
    assertNotEquals(
        preP3Key(submitted),
        forcedKey(submitted),
        "this shape must separate the two derivations, or it proves nothing");
    assertEquals(
        FileFreshnessSnapshot.capture(submitted).normalizedPath(),
        forcedKey(submitted),
        "the shared derivation must reproduce the envelope's key byte-for-byte");

    Set<String> forcedPaths = ConcurrentHashMap.newKeySet();
    forcedPaths.add(forcedKey(submitted));
    Harness h = newHarness(forcedPaths);

    h.extractor().extractAll(List.of(new JobQueue.IndexJob(submitted, null)));

    verify(h.contentExtractor()).extractArtifact(submitted);
    verify(h.journal(), never()).recordOutcomeSafely(any(), eq("UNCHANGED"), any());
    verify(h.documentFieldOps(), never()).isUnmodified(anyString(), anyLong());
    assertTrue(forcedPaths.isEmpty(), "the envelope's lookup consumed the mark");
  }

  @Test
  @DisplayName("the pre-P3 key for that same shape is inert: marked, never looked up")
  void preP3KeyForTheSameShapeSilentlyFailsToForce() throws Exception {
    Path dir = Files.createDirectories(tempDir.resolve("sub"));
    Files.writeString(dir.resolve("doc.txt"), "unchanged content");
    Path submitted = viaRedundantParentHop(dir, "doc.txt");

    Set<String> forcedPaths = ConcurrentHashMap.newKeySet();
    forcedPaths.add(preP3Key(submitted));
    Harness h = newHarness(forcedPaths);

    h.extractor().extractAll(List.of(new JobQueue.IndexJob(submitted, null)));

    // This is the failure mode P3 removes, pinned as a standing negative control: reverting any
    // marker to the pre-P3 derivation makes the test above fail with exactly this behaviour.
    verify(h.contentExtractor(), never()).extractArtifact(any());
    verify(h.journal()).recordOutcomeSafely(any(), eq("UNCHANGED"), any());
    assertEquals(
        Set.of(preP3Key(submitted)),
        forcedPaths,
        "nothing consumed the mark — the force was silent");
  }
}
