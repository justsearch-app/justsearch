/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class SourceWitnessCertificationTest extends LuceneExecutorTestBase {
  @TempDir Path temporary;

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"after-hash", "before-ack", "size-before-ack"})
  void unstableShortcutDefersWithoutExtractionAndRetryPublishesNewBytes(String timing) throws Exception {
    Path file = Files.writeString(temporary.resolve("source.txt"), "before");
    if (!timing.equals("size-before-ack")) requireCtime(file);
    try (var runtime = runtime()) {
      var h = new Harness(runtime);
      var claim = claim(file);
      AtomicBoolean mutated = new AtomicBoolean();
      if (!timing.equals("after-hash")) {
        when(h.fields.isUnmodified(anyString(), anyLong(), anyLong(), anyString(), anyString()))
            .thenAnswer(call -> {
              if (mutated.compareAndSet(false, true)) {
                if (timing.equals("size-before-ack")) Files.writeString(file, "longer content");
                else rewrite(file, "after!");
              }
              return true;
            });
      }
      try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
        hashes.when(() -> SourceContentHash.sha256WithinRoot(file, temporary)).thenAnswer(call -> {
          String hash = SourceContentHash.sha256(file);
          if (timing.equals("after-hash") && mutated.compareAndSet(false, true)) rewrite(file, "after!");
          return hash;
        });
        assertTrue(assertDoesNotThrow(() -> h.extractor.extractAll(List.of(claim))).isEmpty());
      }
      assertDoesNotThrow(() -> {
        verify(h.queue, never()).markClaimDone(any(), any(), any());
        verify(h.parser, never()).extractArtifact(any());
        verify(h.queue).deferClaim(eq(claim), argThat(outcome ->
            outcome.outcomeClass() == IngestionOutcomeClass.STALE_SOURCE
                && outcome.retryPolicy() == IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT), any());
      });
      h.retryAndPublish(claim);
      assertEquals(SourceContentHash.sha256(file), runtime.documentFieldOps().getDocumentField(
          PathNormalizer.normalizeKey(file), SchemaFields.SOURCE_SHA256));
      assertEquals(Files.readString(file), runtime.documentFieldOps().getDocumentField(
          PathNormalizer.normalizeKey(file), SchemaFields.CONTENT));
    }
  }

  @Test
  void writerRejectsRestoredMtimeRewriteAndRetryPublishesNewBytes() throws Exception {
    Path file = Files.writeString(temporary.resolve("writer.txt"), "before");
    requireCtime(file);
    try (var runtime = runtime()) {
      var h = new Harness(runtime);
      h.changedIndex();
      var claim = claim(file);
      var extracted = assertDoesNotThrow(() -> h.extractor.extractAll(List.of(claim)));
      assertEquals(1, extracted.size());
      rewrite(file, "after!");
      assertDoesNotThrow(() -> h.writer.write(extracted.getFirst(), null));
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
      assertEquals(0, runtime.indexCountOps().docCountOrThrow(), "stale bytes must never publish");
      verify(h.queue, never()).markClaimDone(any(), any(), any());
      verify(h.queue).deferClaim(eq(claim), any(), any());
      h.retryAndPublish(claim);
      assertEquals(SourceContentHash.sha256(file), runtime.documentFieldOps().getDocumentField(
          PathNormalizer.normalizeKey(file), SchemaFields.SOURCE_SHA256));
      assertEquals(Files.readString(file), runtime.documentFieldOps().getDocumentField(
          PathNormalizer.normalizeKey(file), SchemaFields.CONTENT));
    }
  }

  @Test
  void subMillisecondMtimeIsComparedIndependentlyOfCtime() throws Exception {
    Path file = Files.writeString(temporary.resolve("nanos.txt"), "source");
    FileTime first = FileTime.from(java.time.Instant.ofEpochSecond(1700000000L, 123100000));
    FileTime second = FileTime.from(java.time.Instant.ofEpochSecond(1700000000L, 123900000));
    Files.setLastModifiedTime(file, first);
    assumeTrue(Files.getLastModifiedTime(file).equals(first), "filesystem lacks nanosecond mtime");
    var before = withoutCtime(FileFreshnessSnapshot.capture(file), null, false);
    Files.setLastModifiedTime(file, second);
    assumeTrue(Files.getLastModifiedTime(file).equals(second), "filesystem lacks nanosecond mtime");
    var after = withoutCtime(FileFreshnessSnapshot.capture(file), null, false);
    assertEquals(first.toMillis(), second.toMillis());
    assertEquals(FileFreshnessSnapshot.SourceValidationResult.MODIFIED_TIME_CHANGED,
        FileFreshness.classify(before, after));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {true, false})
  void fileKeyAvailabilityTransitionsAreChanges(boolean initiallyNull) throws Exception {
    var captured = FileFreshnessSnapshot.capture(Files.writeString(temporary.resolve("keys.txt"), "source"));
    assumeTrue(captured.fileKey() != null, "filesystem lacks file keys");
    var absent = withoutCtime(captured, null, true);
    var present = withoutCtime(captured, captured.fileKey(), true);
    assertEquals(FileFreshnessSnapshot.SourceValidationResult.FILE_KEY_CHANGED,
        FileFreshness.classify(initiallyNull ? absent : present, initiallyNull ? present : absent));
  }

  @Test
  void replacementWithSameSizeAndMtimeIsChanged() throws Exception {
    Path file = Files.writeString(temporary.resolve("replaced.txt"), "source");
    var before = FileFreshnessSnapshot.capture(file);
    Path replacement = Files.writeString(temporary.resolve("replacement.txt"), "target");
    Files.setLastModifiedTime(replacement, Files.getLastModifiedTime(file));
    var key = FileFreshnessSnapshot.capture(replacement).fileKey();
    assumeTrue(key != null && before.fileKey() != null && !key.equals(before.fileKey()), "distinct keys required");
    Files.move(replacement, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    assertEquals(FileFreshnessSnapshot.SourceValidationResult.FILE_KEY_CHANGED, before.validateNow());
  }

  @Test
  void unavailableAttributesAfterHashNeverAcknowledgeUnchanged() throws Exception {
    Path file = Files.writeString(temporary.resolve("unreadable.txt"), "source");
    try (var runtime = runtime()) {
      var h = new Harness(runtime);
      var claim = claim(file);
      AtomicBoolean hashed = new AtomicBoolean();
      try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS);
          var snapshots = mockStatic(FileFreshnessSnapshot.class, CALLS_REAL_METHODS)) {
        snapshots.when(() -> FileFreshnessSnapshot.capture(file)).thenAnswer(call -> {
          if (hashed.get()) throw new java.nio.file.AccessDeniedException(file.toString());
          return call.callRealMethod();
        });
        hashes.when(() -> SourceContentHash.sha256WithinRoot(file, temporary)).thenAnswer(call -> {
          String hash = SourceContentHash.sha256(file);
          hashed.set(true);
          return hash;
        });
        assertTrue(assertDoesNotThrow(() -> h.extractor.extractAll(List.of(claim))).isEmpty());
      }
      assertDoesNotThrow(() -> {
        verify(h.queue, never()).markClaimDone(any(), any(), any());
        verify(h.parser, never()).extractArtifact(any());
        verify(h.queue).markClaimFailed(eq(claim), argThat(outcome ->
            outcome.retryPolicy() == IngestionRetryPolicy.RETRY_UNTIL_AVAILABLE), any());
      });
    }
  }

  @Test
  void unmodifiedShortcutReadsContentOnlyOnce() throws Exception {
    Path file = Files.writeString(temporary.resolve("stable.txt"), "source");
    try (var runtime = runtime()) {
      var h = new Harness(runtime);
      var captures = new java.util.concurrent.atomic.AtomicInteger();
      var basic = new java.util.concurrent.atomic.AtomicInteger();
      var ctime = new java.util.concurrent.atomic.AtomicInteger();
      var other = new java.util.concurrent.atomic.AtomicInteger();
      try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS);
          var _ = mockStatic(FileFreshnessSnapshot.class, call -> {
            if (call.getMethod().getName().equals("capture") && file.equals(call.getArgument(0))) {
              captures.incrementAndGet();
            }
            return call.callRealMethod();
          });
          var _ = mockStatic(Files.class, call -> {
            if (call.getArguments().length > 1 && file.equals(call.getArgument(0))) {
              // getAttribute internally calls readAttributes(String); count that probe once.
              if (call.getMethod().getName().equals("readAttributes") && call.getArgument(1) instanceof Class<?>) {
                basic.incrementAndGet();
              } else if (call.getMethod().getName().equals("getAttribute")) {
                if ("unix:ctime".equals(call.getArgument(1))) ctime.incrementAndGet();
                else other.incrementAndGet();
              }
            }
            return call.callRealMethod();
          })) {
        assertTrue(h.extractor.extractAll(List.of(claim(file))).isEmpty());
        hashes.verify(() -> SourceContentHash.sha256WithinRoot(file, temporary), times(1));
      }
      System.out.println("C3 unchanged source attribute probes: captures=" + captures.get() + ", basic=" + basic.get()
          + ", ctime=" + ctime.get() + ", other=" + other.get());
      verify(h.parser, never()).extractArtifact(any());
      verify(h.queue).markClaimDone(any(), any(), any());
    }
  }

  // Reflection keeps the repair checks overlay-compatible with the millisecond-only base record.
  private static FileFreshnessSnapshot withoutCtime(FileFreshnessSnapshot original,
      Object key, boolean replaceKey) throws Exception {
    var components = FileFreshnessSnapshot.class.getRecordComponents();
    Object[] values = new Object[components.length];
    for (int i = 0; i < components.length; i++) {
      values[i] = components[i].getAccessor().invoke(original);
      if (components[i].getName().equals("changedTime")) values[i] = null;
      if (replaceKey && components[i].getName().equals("fileKey")) values[i] = key;
    }
    return FileFreshnessSnapshot.class.getDeclaredConstructor(
        Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new))
        .newInstance(values);
  }

  private static void requireCtime(Path file) throws IOException {
    try {
      var ctime = Files.getAttribute(file, "unix:ctime");
      Files.setLastModifiedTime(file, Files.getLastModifiedTime(file));
      assumeTrue(!ctime.equals(Files.getAttribute(file, "unix:ctime")), "filesystem did not expose distinct ctime");
    } catch (UnsupportedOperationException | IllegalArgumentException unavailable) {
      assumeTrue(false, "filesystem lacks ctime");
    }
  }

  private static void rewrite(Path file, String bytes) throws IOException {
    FileTime mtime = Files.getLastModifiedTime(file);
    Object ctime = Files.getAttribute(file, "unix:ctime");
    Files.writeString(file, bytes);
    Files.setLastModifiedTime(file, mtime);
    assertNotEquals(ctime, Files.getAttribute(file, "unix:ctime"), "mutation must expose distinct ctime");
    assertEquals(mtime, Files.getLastModifiedTime(file));
  }

  private RunningRuntime runtime() throws IOException {
    var base = FieldCatalogDef.forChunkTesting(0);
    var fields = new java.util.ArrayList<>(base.fields());
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.SOURCE_SHA256, "keyword", true, false,
        List.of(), null, null, false));
    return IndexSchema.fromCatalog(new FieldCatalogDef(base.version(), fields)).atPath(temporary.resolve("index"))
        .withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private JobQueue.IndexJob claim(Path file) {
    return new JobQueue.IndexJob(file, null, null, null, null, null, false, null, temporary);
  }

  private final class Harness {
    final JobQueue queue = mock(JobQueue.class);
    final DocumentFieldOps fields = mock(DocumentFieldOps.class);
    final TimeboxedContentExtractor parser = mock(TimeboxedContentExtractor.class);
    final JobBatchExtractor extractor;
    final JobBatchWriter writer;
    final RunningRuntime runtime;

    Harness(RunningRuntime runtime) throws Exception {
      this.runtime = runtime;
      when(queue.ownsClaimForPublication(any())).thenReturn(true);
      var counts = mock(IndexCountOps.class);
      when(counts.docCountOrThrow()).thenReturn(1L);
      when(parser.extractionPolicy()).thenReturn(TikaExtractionPolicy.defaults());
      when(parser.extractArtifact(any())).thenAnswer(call -> {
        Path path = call.getArgument(0);
        return ExtractionArtifact.full(new ExtractionResult(Files.readString(path), path.getFileName().toString(),
            "text/plain"), parser.extractionPolicy(), "witness-test", false);
      });
      when(fields.isUnmodified(anyString(), anyLong(), anyLong(), anyString(), anyString())).thenReturn(true);
      var journal = mock(IngestionOutcomeJournal.class);
      when(journal.outcome(any(), any(), any(), any())).thenCallRealMethod();
      doCallRealMethod().when(journal).recordOutcomeSafely(any(), any(), any());
      var authority = new WorkerIngestionAuthority();
      var roots = mock(SourcePresence.RootCheck.class);
      when(roots.admits(any())).thenReturn(true);
      when(roots.vouches(any())).thenReturn(true);
      authority.useRootCheck(roots);
      var fence = new FileMutationFence();
      var stale = new StaleSnapshotResolver(authority, mock(StaleSourceHandler.class), journal, queue, parser,
          ignored -> {}, fence);
      var identity = mock(DocumentIdentityStore.class);
      when(identity.resolve(anyString(), anyLong()))
          .thenReturn(new DocumentIdentityStore.Identity("hash", "00000000-0000-4000-8000-000000000001", 1, 1));
      extractor = new JobBatchExtractor(authority, journal, queue, parser, fields, counts, new BatchStats(),
          stale, mock(StaleSourceHandler.class), IndexingPacing.unthrottled(), new AtomicBoolean(true),
          new java.util.HashSet<>(), () -> mock(PathResolutionStore.class), () -> identity,
          (stage, ms, reason) -> {}, () -> false, ignored -> {}, fence);
      writer = new JobBatchWriter(authority, runtime.indexingCoordinator(), mock(WorkerSignalBus.class),
          mock(EmbeddingProviderLifecycle.class), () -> null, journal, queue, parser,
          mock(OperationalMetrics.class), new BatchStats(), stale, ignored -> {},
          mock(IndexingDocumentOps.StageRecorder.class), () -> false, () -> false, () -> null, fence);
    }

    void changedIndex() throws IOException {
      when(fields.isUnmodified(anyString(), anyLong(), anyLong(), anyString(), anyString())).thenReturn(false);
    }

    void retryAndPublish(JobQueue.IndexJob claim) throws Exception {
      changedIndex();
      var retry = assertDoesNotThrow(() -> extractor.extractAll(List.of(claim)));
      assertEquals(1, retry.size(), "a stable next attempt must extract the new bytes");
      assertDoesNotThrow(() -> writer.write(retry.getFirst(), null));
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
    }
  }
}
