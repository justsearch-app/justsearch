/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.services.ProjectionDocumentMapper;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** Real candidate admission proofs for a file UPSERT followed by an accepted broad delete. */
final class AcceptedFileBroadReplayTest extends LuceneExecutorTestBase {
  private static final String GENERATION = "green";
  private static final String SOURCE = "memory";
  private static final String COLLECTION = "notes";

  @TempDir Path tempDir;

  @Test
  void acceptedFileThenPrefixDeleteUsesLaterScopeForAbsenceCertification() throws Exception {
    try (var queue = openQueue("prefix-forward.db"); var runtime = runtime()) {
      var fixture = admitAndIndex(queue, runtime, "prefix-forward", COLLECTION);
      String prefix = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder
          .normalizePathPrefix(fixture.path().getParent().toString());
      putMarker(queue);
      runtime.indexingCoordinator().deleteByPathPrefix(prefix);
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(1, queue.deleteByPathPrefix(prefix));
      assertTrue(put(queue, op("prefix:" + prefix, "DELETE_PREFIX", prefix)));

      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertKinds(expected, "UPSERT", "PROJECTION_SOURCE", "DELETE_PREFIX");
      assertFalse(hasDocument(runtime, fixture.path().toString()));
      assertEquals(0, runtime.indexCountOps().countPathPrefixExcludingAcceptedSurvivorsStrict(
          prefix, List.of(), List.of()));
      var replay = prepare(queue, runtime).orElseThrow();
      assertEquals(expected, replay.versions());
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(queue, replay));
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration(GENERATION).isEmpty());
    }
  }

  @Test
  void acceptedFileThenCollectionDeleteUsesLaterScopeForAbsenceCertification() throws Exception {
    try (var queue = openQueue("collection-forward.db"); var runtime = runtime()) {
      var fixture = admitAndIndex(queue, runtime, "collection-forward", COLLECTION);
      putMarker(queue);
      runtime.indexingCoordinator().deleteByCollection(COLLECTION);
      runtime.commitOps().maybeRefreshBlocking();
      assertTrue(put(queue, op("collection:" + COLLECTION, "DELETE_COLLECTION", COLLECTION)));

      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertKinds(expected, "UPSERT", "PROJECTION_SOURCE", "DELETE_COLLECTION");
      assertFalse(hasDocument(runtime, fixture.path().toString()));
      assertEquals(0, runtime.indexCountOps().countCollectionExcludingAcceptedSurvivorsStrict(
          COLLECTION, List.of(), List.of()));
      var replay = prepare(queue, runtime).orElseThrow();
      assertEquals(expected, replay.versions());
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(queue, replay));
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration(GENERATION).isEmpty());
    }
  }

  @Test
  void absentAcceptedFileWithNonmatchingPrefixCannotAuthorizeCleanup() throws Exception {
    try (var queue = openQueue("prefix-nonmatch.db"); var runtime = runtime()) {
      var fixture = admitAndIndex(queue, runtime, "prefix-nonmatch", COLLECTION);
      runtime.indexingCoordinator().deleteByIdAndChunks(fixture.path().toString());
      runtime.commitOps().maybeRefreshBlocking();
      putMarker(queue);
      String other = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder
          .normalizePathPrefix(tempDir.resolve("other-scope").toString());
      assertTrue(put(queue, op("prefix:" + other, "DELETE_PREFIX", other)));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertTrue(prepare(queue, runtime).isEmpty());
      assertEquals(expected, queue.listSwitchBufferOpsStrictForGeneration(GENERATION));
    }
  }

  @Test
  void approvedLaterDeleteCannotExplainAbsentAcceptedFile() throws Exception {
    try (var queue = openQueue("prefix-approved-gap.db"); var runtime = runtime()) {
      var fixture = admitAndIndex(queue, runtime, "prefix-approved-gap", COLLECTION);
      String prefix = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder
          .normalizePathPrefix(fixture.path().getParent().toString());
      runtime.indexingCoordinator().deleteByIdAndChunks(fixture.path().toString());
      runtime.commitOps().maybeRefreshBlocking();
      putMarker(queue);
      assertTrue(put(queue, op("prefix:" + prefix, "DELETE_PREFIX", prefix)));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertTrue(prepare(queue, runtime, Set.of(expected.get(2))).isEmpty());
      assertEquals(expected, queue.listSwitchBufferOpsStrictForGeneration(GENERATION));
    }
  }

  @Test
  void malformedBroadReceiptRetainsAcceptedRows() throws Exception {
    try (var queue = openQueue("malformed-broad.db"); var runtime = runtime()) {
      var fixture = admitAndIndex(queue, runtime, "malformed-broad", COLLECTION);
      String prefix = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder
          .normalizePathPrefix(fixture.path().getParent().toString());
      runtime.indexingCoordinator().deleteByIdAndChunks(fixture.path().toString());
      runtime.commitOps().maybeRefreshBlocking();
      putMarker(queue);
      assertTrue(put(queue, op("prefix:not-the-prefix", "DELETE_PREFIX", prefix)));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertTrue(prepare(queue, runtime).isEmpty());
      assertEquals(expected, queue.listSwitchBufferOpsStrictForGeneration(GENERATION));
    }
  }

  @Test
  void protectedCollectionReceiptRetainsAcceptedRows() throws Exception {
    try (var queue = openQueue("protected-collection.db"); var runtime = runtime()) {
      var fixture = admitAndIndex(queue, runtime, "protected-collection", COLLECTION);
      runtime.indexingCoordinator().deleteByIdAndChunks(fixture.path().toString());
      runtime.commitOps().maybeRefreshBlocking();
      putMarker(queue);
      assertTrue(put(queue, op("collection:default", "DELETE_COLLECTION", "default")));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertTrue(prepare(queue, runtime).isEmpty());
      assertEquals(expected, queue.listSwitchBufferOpsStrictForGeneration(GENERATION));
    }
  }

  @Test
  void noOpBroadWriterLeavingUnexpectedDocumentRefusesCertification() throws Exception {
    try (var queue = openQueue("no-op-broad.db"); var original = runtime()) {
      var fixture = admitAndIndex(queue, original, "no-op-broad", COLLECTION);
      String prefix = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder
          .normalizePathPrefix(fixture.path().getParent().toString());
      original.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "unexpected-broad-survivor",
          SchemaFields.DOC_UID, "unexpected-broad-survivor",
          SchemaFields.PATH, prefix + "other.txt",
          SchemaFields.COLLECTION, COLLECTION,
          SchemaFields.CONTENT, "unexpected")));
      original.commitOps().maybeRefreshBlocking();
      original.indexingCoordinator().deleteByIdAndChunks(fixture.path().toString());
      original.commitOps().maybeRefreshBlocking();
      putMarker(queue);
      assertTrue(put(queue, op("prefix:" + prefix, "DELETE_PREFIX", prefix)));

      IndexingCoordinator indexing = spy(original.indexingCoordinator());
      doNothing().when(indexing).deleteByPathPrefix(prefix);
      var runtime = spy(original);
      doReturn(indexing).when(runtime).indexingCoordinator();
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertTrue(prepare(queue, runtime).isEmpty());
      assertEquals(expected, queue.listSwitchBufferOpsStrictForGeneration(GENERATION));
    }
  }

  @Test
  void strictBroadCountFailureRetainsAllAcceptedRows() throws Exception {
    try (var queue = openQueue("strict-count-failure.db"); var original = runtime()) {
      var fixture = admitAndIndex(queue, original, "strict-count-failure", COLLECTION);
      String prefix = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder
          .normalizePathPrefix(fixture.path().getParent().toString());
      original.indexingCoordinator().deleteByIdAndChunks(fixture.path().toString());
      original.commitOps().maybeRefreshBlocking();
      putMarker(queue);
      assertTrue(put(queue, op("prefix:" + prefix, "DELETE_PREFIX", prefix)));
      var counts = spy(original.indexCountOps());
      doThrow(new IOException("strict broad count unavailable"))
          .when(counts).countPathPrefixExcludingAcceptedSurvivorsStrict(
              anyString(), anyList(), anyList());
      var runtime = spy(original);
      doReturn(counts).when(runtime).indexCountOps();
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertTrue(prepare(queue, runtime).isEmpty());
      assertEquals(expected, queue.listSwitchBufferOpsStrictForGeneration(GENERATION));
    }
  }

  @Test
  void prefixDeleteDominatesAbsentOlderFileButPreservesLaterCertifiedFile() throws Exception {
    try (var queue = openQueue("prefix-dominates-file.db"); var runtime = runtime()) {
      Path folder = tempDir.resolve("prefix-dominates-file");
      var older = admitAndIndex(queue, runtime, folder.resolve("older.txt"), COLLECTION);
      String prefix = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder
          .normalizePathPrefix(folder.toString());
      runtime.indexingCoordinator().deleteByIdAndChunks(older.path().toString());
      runtime.commitOps().maybeRefreshBlocking();
      putMarker(queue);
      runtime.indexingCoordinator().deleteByPathPrefix(prefix);
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(1, queue.deleteByPathPrefix(prefix));
      assertTrue(put(queue, op("prefix:" + prefix, "DELETE_PREFIX", prefix)));

      var later = admitAndIndex(queue, runtime, folder.resolve("later.txt"), COLLECTION);
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertKinds(expected, "UPSERT", "PROJECTION_SOURCE", "DELETE_PREFIX", "UPSERT");
      assertSourceMarker(expected.get(1));
      assertEquals(later.path().toString(), runtime.documentFieldOps().getDocumentFieldOrThrow(
          later.path().toString(), SchemaFields.DOC_ID));
      assertEquals(COLLECTION, runtime.documentFieldOps().getDocumentFieldOrThrow(
          later.path().toString(), SchemaFields.COLLECTION));
      assertEquals(later.claim().plannedSourceSha256(), runtime.documentFieldOps()
          .getDocumentFieldOrThrow(later.path().toString(), SchemaFields.SOURCE_SHA256));

      var replay = prepare(queue, runtime).orElseThrow();
      assertEquals(expected, replay.versions());
      assertEquals(later.path().toString(), runtime.documentFieldOps().getDocumentFieldOrThrow(
          later.path().toString(), SchemaFields.DOC_ID));
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(queue, replay));
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration(GENERATION).isEmpty());
    }
  }

  @Test
  void prefixDeleteDominatesAbsentOlderFileButPreservesLaterOwnedProjection() throws Exception {
    try (var queue = openQueue("prefix-dominates-projection.db"); var runtime = runtime()) {
      Path folder = tempDir.resolve("prefix-dominates-projection");
      var older = admitAndIndex(queue, runtime, folder.resolve("older.txt"), COLLECTION);
      String prefix = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder
          .normalizePathPrefix(folder.toString());
      runtime.indexingCoordinator().deleteByIdAndChunks(older.path().toString());
      runtime.commitOps().maybeRefreshBlocking();
      putMarker(queue);
      runtime.indexingCoordinator().deleteByPathPrefix(prefix);
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(1, queue.deleteByPathPrefix(prefix));
      assertTrue(put(queue, op("prefix:" + prefix, "DELETE_PREFIX", prefix)));

      String projectionPath = prefix + "later-projection.md";
      var projection = projection("later-projection", 17, COLLECTION, projectionPath,
          "later projection");
      indexProjection(runtime, projection, projection.fieldsDigest());
      assertTrue(put(queue, op(projection.journalKey(), "PROJECTION", projection.encode())));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertKinds(expected, "UPSERT", "PROJECTION_SOURCE", "DELETE_PREFIX", "PROJECTION");
      assertSourceMarker(expected.get(1));
      assertProjectionWitness(runtime, projection, projectionPath, COLLECTION);

      var replay = prepare(queue, runtime).orElseThrow();
      assertEquals(expected, replay.versions());
      assertProjectionWitness(runtime, projection, projectionPath, COLLECTION);
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(queue, replay));
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration(GENERATION).isEmpty());
    }
  }

  @Test
  void unverifiedLaterProjectionRefusesWholeOlderFileCleanup() throws Exception {
    try (var queue = openQueue("prefix-dominates-unverified.db"); var runtime = runtime()) {
      Path folder = tempDir.resolve("prefix-dominates-unverified");
      var older = admitAndIndex(queue, runtime, folder.resolve("older.txt"), COLLECTION);
      String prefix = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder
          .normalizePathPrefix(folder.toString());
      runtime.indexingCoordinator().deleteByIdAndChunks(older.path().toString());
      runtime.commitOps().maybeRefreshBlocking();
      putMarker(queue);
      runtime.indexingCoordinator().deleteByPathPrefix(prefix);
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(1, queue.deleteByPathPrefix(prefix));
      assertTrue(put(queue, op("prefix:" + prefix, "DELETE_PREFIX", prefix)));

      String projectionPath = prefix + "unverified-projection.md";
      var projection = projection("unverified-projection", 18, COLLECTION, projectionPath,
          "unverified projection");
      indexProjection(runtime, projection, "f".repeat(64));
      assertTrue(put(queue, op(projection.journalKey(), "PROJECTION", projection.encode())));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertKinds(expected, "UPSERT", "PROJECTION_SOURCE", "DELETE_PREFIX", "PROJECTION");
      assertSourceMarker(expected.get(1));
      assertFalse(projection.fieldsDigest().equals(runtime.documentFieldOps()
          .getDocumentFieldOrThrow(projection.indexId(), SchemaFields.PROJECTION_DIGEST)));

      assertTrue(prepare(queue, runtime).isEmpty());
      assertEquals(expected, queue.listSwitchBufferOpsStrictForGeneration(GENERATION));
    }
  }

  private record FileFixture(Path path, JobQueue.IndexJob claim) {}

  @Test
  void deletedSealedCapturedH1H2RowDoesNotRequireAcknowledgementBeforePromotion() throws Exception {
    assertCapturedDeletionRequiresExactAcceptedGeneration(true);
  }

  @Test
  void unrelatedSealedCapturedRowCannotCertifyPrefixCleanup() throws Exception {
    assertCapturedDeletionRequiresExactAcceptedGeneration(false);
  }

  private void assertCapturedDeletionRequiresExactAcceptedGeneration(boolean acceptedHere) throws Exception {
    try (var queue = new SqliteJobQueue(tempDir.resolve("captured-prefix.db"),
        ignored -> JobQueue.RecordedClaimDecision.ALLOW); var runtime = runtime()) {
      queue.open();
      Path folder = tempDir.resolve("captured-prefix");
      Files.createDirectories(folder);
      Path source = Files.writeString(folder.resolve("captured.txt"), "captured H1");
      String h1 = SourceContentHash.sha256(source);
      String key = "01994180-0000-7000-8000-000000000091";
      var walk = queue.beginCapturedWalk(key, "a".repeat(64), true);
      assertEquals(1, queue.enqueueRecordedEntriesAndBufferForGeneration(acceptedHere ? GENERATION : "other", key,
          walk.enumerationEpoch(), List.of(new JobQueue.EnqueueEntry(source, Files.size(source), null, h1)), COLLECTION));
      queue.closeRecordedWalkEnumeration(key, walk.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
      var claim = queue.pollPending(1).getFirst();
      Files.writeString(source, "captured H2");
      String h2 = SourceContentHash.sha256(source);
      runtime.commitOps().stopCommitTimer();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, claim.path().toString(), SchemaFields.DOC_UID, claim.path().toString(),
          SchemaFields.PATH, claim.path().toString(), SchemaFields.COLLECTION, COLLECTION,
          SchemaFields.CONTENT, "captured H2", SchemaFields.SOURCE_SHA256, h2)));
      runtime.commitOps().maybeRefreshBlocking();
      queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claim, null, h2)),
          IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE));
      var sealed = queue.trySealRecordedWalk(key);
      assertTrue(sealed.sealedAt() != null);
      assertTrue(sealed.acknowledgedRevision() < sealed.revision());
      assertTrue(queue.matchesAcceptedFileProjection(claim.path().toString(), claim.unitRevision(), h1, h2));
      if (!acceptedHere) admitAndIndex(queue, runtime, folder.resolve("accepted.txt"), COLLECTION);
      putMarker(queue);
      String prefix = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder.normalizePathPrefix(folder.toString());
      assertTrue(put(queue, op("prefix:" + prefix, "DELETE_PREFIX", prefix)));
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);

      if (!acceptedHere) {
        assertTrue(prepare(queue, runtime).isEmpty());
        assertEquals(expected, queue.listSwitchBufferOpsStrictForGeneration(GENERATION));
        assertEquals(1, queue.listSwitchBufferOpsStrictForGeneration("other").size());
        assertEquals(sealed, queue.recordedWalk(key).orElseThrow());
        assertEquals(1, queue.jobStateCountsStrict().doneCount());
        return;
      }
      var replay = prepare(queue, runtime).orElseThrow();
      assertEquals(expected, replay.versions());
      assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(claim.path().toString()));
      assertEquals(sealed, queue.recordedWalk(key).orElseThrow(),
          "pre-pointer replay must preserve the sealed receipt and its post-pointer ACK owner");
      assertEquals(1, queue.jobStateCountsStrict().doneCount());
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(queue, replay));
      assertTrue(queue.acknowledgeRecordedWalk(key, sealed.revision()));
    }
  }

  @Test
  void selectivePrefixReplayConvergesBeforeApplicationAndPreservesLaterFileChunks() throws Exception {
    assertSelectivePrefixReplayConverges(false);
  }

  @Test
  void selectivePrefixReplayConvergesAfterLuceneBeforeQueueCleanup() throws Exception {
    assertSelectivePrefixReplayConverges(true);
  }

  private void assertSelectivePrefixReplayConverges(boolean luceneAlreadyDeleted) throws Exception {
    try (var queue = openQueue("prefix-partial.db"); var runtime = runtime()) {
      Path folder = tempDir.resolve("prefix-partial");
      var older = admitAndIndex(queue, runtime, folder.resolve("older.txt"), COLLECTION);
      String prefix = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder.normalizePathPrefix(folder.toString());
      putMarker(queue);
      assertTrue(put(queue, op("prefix:" + prefix, "DELETE_PREFIX", prefix)));
      if (luceneAlreadyDeleted) {
        runtime.indexingCoordinator().deleteByPathPrefix(prefix);
        runtime.commitOps().maybeRefreshBlocking();
        assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(older.path().toString()));
        assertEquals(1, queue.jobStateCountsStrict().doneCount());
      } else {
        assertTrue(hasDocument(runtime, older.path().toString()));
      }
      var later = admitAndIndex(queue, runtime, folder.resolve("later.txt"), COLLECTION);
      indexChunk(runtime, later.path().toString());
      Path unexpectedPath = Files.writeString(folder.resolve("unexpected.txt"), "unexpected");
      assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(unexpectedPath)), COLLECTION));
      var unexpected = queue.pollPending(1).getFirst();
      queue.markDone(unexpected.path());
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, unexpected.path().toString(), SchemaFields.DOC_UID, unexpected.path().toString(),
          SchemaFields.PATH, unexpected.path().toString(),
          SchemaFields.COLLECTION, COLLECTION, SchemaFields.CONTENT, "unexpected")));
      runtime.commitOps().maybeRefreshBlocking();
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);

      long epoch = runtime.indexingCoordinator().bulkDeleteEpoch();

      var replay = prepare(queue, runtime).orElseThrow();
      assertEquals(expected, replay.versions());
      assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(older.path().toString()));
      assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(unexpected.path().toString()));
      assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(later.path().toString()));
      assertFalse(hasDocument(runtime, "projection:spoofed"));
      assertTrue(runtime.indexingCoordinator().bulkDeleteEpoch() > epoch);
      assertEquals(1, queue.jobStateCountsStrict().doneCount());
      assertTrue(queue.matchesAcceptedFileProjection(later.path().toString(), later.claim().unitRevision(),
          later.claim().plannedSourceSha256(), runtime.documentFieldOps()
              .getDocumentFieldOrThrow(later.path().toString(), SchemaFields.SOURCE_SHA256)));
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(queue, replay));
    }
  }

  @Test
  void selectiveCollectionReplayPreservesLaterFileChunksAndTerminalQueueContract() throws Exception {
    try (var queue = openQueue("collection-partial.db"); var runtime = runtime()) {
      Path folder = tempDir.resolve("collection-partial");
      var older = admitAndIndex(queue, runtime, folder.resolve("older.txt"), COLLECTION);
      putMarker(queue);
      assertTrue(put(queue, op("collection:" + COLLECTION, "DELETE_COLLECTION", COLLECTION)));
      var later = admitAndIndex(queue, runtime, folder.resolve("later.txt"), COLLECTION);
      indexChunk(runtime, later.path().toString());
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      long epoch = runtime.indexingCoordinator().bulkDeleteEpoch();

      var replay = prepare(queue, runtime).orElseThrow();
      assertEquals(expected, replay.versions());
      assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(older.path().toString()));
      assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(later.path().toString()));
      assertFalse(hasDocument(runtime, "projection:spoofed"));
      assertTrue(runtime.indexingCoordinator().bulkDeleteEpoch() > epoch);
      assertEquals(2, queue.jobStateCountsStrict().doneCount());
      assertTrue(queue.matchesAcceptedFileProjection(later.path().toString(), later.claim().unitRevision(),
          later.claim().plannedSourceSha256(), runtime.documentFieldOps()
              .getDocumentFieldOrThrow(later.path().toString(), SchemaFields.SOURCE_SHA256)));
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(queue, replay));
    }
  }

  @Test
  void broadReceiptBeforeOnlyFileAdmissionPreservesItsAcceptedFileAndChunks() throws Exception {
    try (var queue = openQueue("prefix-first.db"); var runtime = runtime()) {
      Path folder = tempDir.resolve("prefix-first");
      Files.createDirectories(folder);
      String prefix = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder.normalizePathPrefix(folder.toString());
      putMarker(queue);
      assertTrue(put(queue, op("prefix:" + prefix, "DELETE_PREFIX", prefix)));
      Path unexpectedPath = Files.writeString(folder.resolve("unexpected.txt"), "unexpected");
      assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(unexpectedPath)), COLLECTION));
      var unexpected = queue.pollPending(1).getFirst();
      queue.markDone(unexpected.path());
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, unexpected.path().toString(), SchemaFields.DOC_UID, unexpected.path().toString(),
          SchemaFields.PATH, unexpected.path().toString(), SchemaFields.COLLECTION, COLLECTION,
          SchemaFields.CONTENT, "unexpected")));
      var later = admitAndIndex(queue, runtime, folder.resolve("later.txt"), COLLECTION);
      indexChunk(runtime, later.path().toString());
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertKinds(expected, "PROJECTION_SOURCE", "DELETE_PREFIX", "UPSERT");
      var replay = prepare(queue, runtime).orElseThrow();
      assertEquals(expected, replay.versions());
      assertEquals(0, runtime.indexCountOps().countByIdAndChunksStrict(unexpected.path().toString()));
      assertEquals(2, runtime.indexCountOps().countByIdAndChunksStrict(later.path().toString()));
      assertFalse(hasDocument(runtime, "projection:spoofed"));
      assertEquals(1, queue.jobStateCountsStrict().doneCount());
      assertTrue(queue.matchesAcceptedFileProjection(later.path().toString(), later.claim().unitRevision(),
          later.claim().plannedSourceSha256(), runtime.documentFieldOps()
              .getDocumentFieldOrThrow(later.path().toString(), SchemaFields.SOURCE_SHA256)));
      assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(queue, replay));
    }
  }

  @Test
  void matchingNewerProjectionRefusesBeforeAnyBroadMutation() throws Exception {
    assertMatchingNewerProjectionRefuses(false);
  }

  @Test
  void newerProjectionAssociatedWithOlderEarlierReceiptAlsoRefusesBroadMutation() throws Exception {
    assertMatchingNewerProjectionRefuses(true);
  }

  private void assertMatchingNewerProjectionRefuses(boolean retainedRowBeforeDelete) throws Exception {
    try (var queue = openQueue("prefix-newer-projection.db"); var runtime = runtime()) {
      Path folder = tempDir.resolve("prefix-newer-projection");
      var older = admitAndIndex(queue, runtime, folder.resolve("older.txt"), COLLECTION);
      String prefix = io.justsearch.adapters.lucene.runtime.QueryFilterBuilder.normalizePathPrefix(folder.toString());
      putMarker(queue);
      var retained = projection("newer", 10, COLLECTION, prefix + "newer.md", "retained");
      var newer = projection("newer", 11, COLLECTION, prefix + "newer.md", "newer");
      indexProjection(runtime, newer, newer.fieldsDigest());
      if (!retainedRowBeforeDelete) {
        assertTrue(put(queue, op("prefix:" + prefix, "DELETE_PREFIX", prefix)));
      }
      assertTrue(put(queue, op(retained.journalKey(), "PROJECTION", retained.encode())));
      if (retainedRowBeforeDelete) {
        assertTrue(put(queue, op("prefix:" + prefix, "DELETE_PREFIX", prefix)));
      }
      var expected = queue.listSwitchBufferOpsStrictForGeneration(GENERATION);
      assertTrue(prepare(queue, runtime).isEmpty());
      assertEquals(expected, queue.listSwitchBufferOpsStrictForGeneration(GENERATION));
      assertTrue(hasDocument(runtime, older.path().toString()));
      assertProjectionWitness(runtime, newer, prefix + "newer.md", COLLECTION);
    }
  }

  private static void indexChunk(RunningRuntime runtime, String parent) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, "chunk:later", SchemaFields.DOC_UID, "chunk:later", SchemaFields.PARENT_DOC_ID, parent,
        SchemaFields.IS_CHUNK, "true", SchemaFields.PATH, parent,
        SchemaFields.COLLECTION, COLLECTION, SchemaFields.CONTENT, "later chunk")));
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, "projection:spoofed", SchemaFields.DOC_UID, "projection:spoofed",
        SchemaFields.PARENT_DOC_ID, parent, SchemaFields.IS_CHUNK, "true", SchemaFields.PATH, parent,
        SchemaFields.COLLECTION, COLLECTION, SchemaFields.CONTENT, "spoofed chunk")));
    runtime.commitOps().maybeRefreshBlocking();
  }

  private FileFixture admitAndIndex(SqliteJobQueue queue, RunningRuntime runtime,
      String name, String collection) throws Exception {
    return admitAndIndex(queue, runtime, tempDir.resolve(name).resolve("accepted.txt"), collection);
  }

  private FileFixture admitAndIndex(SqliteJobQueue queue, RunningRuntime runtime,
      Path source, String collection) throws Exception {
    Files.createDirectories(source.getParent());
    Files.writeString(source, "accepted source bytes");
    assertTrue(queue.enqueueAndBufferFileForGeneration(
        GENERATION, JobQueue.EnqueueEntry.stat(source), collection, null));
    JobQueue.IndexJob claim = queue.pollPending(1).get(0);
    String path = claim.path().toString();
    runtime.commitOps().stopCommitTimer();
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, path,
        SchemaFields.DOC_UID, path,
        SchemaFields.PATH, path,
        SchemaFields.FILENAME, source.getFileName().toString(),
        SchemaFields.COLLECTION, collection,
        SchemaFields.CONTENT, "accepted source bytes",
        SchemaFields.SOURCE_SHA256, claim.plannedSourceSha256(),
        SchemaFields.IS_CHUNK, "false")));
    runtime.commitOps().maybeRefreshBlocking();
    assertEquals(claim.plannedSourceSha256(), runtime.documentFieldOps()
        .getDocumentFieldOrThrow(path, SchemaFields.SOURCE_SHA256));
    queue.markDone(claim.path());
    return new FileFixture(claim.path(), claim);
  }

  private void putMarker(SqliteJobQueue queue) {
    assertTrue(put(queue, op("projection-source:" + SOURCE.length() + ":" + SOURCE,
        "PROJECTION_SOURCE", SOURCE)));
  }

  private SqliteJobQueue openQueue(String name) throws Exception {
    var queue = new SqliteJobQueue(tempDir.resolve(name));
    queue.open();
    return queue;
  }

  private RunningRuntime runtime() {
    return withTestExecutors(IndexSchema.fromCatalog(fileCatalog()).ephemeral()).open();
  }

  private static FieldCatalogDef fileCatalog() {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
    for (String field : List.of(SchemaFields.PROJECTION_SOURCE_ID,
        SchemaFields.PROJECTION_SOURCE_REVISION, SchemaFields.PROJECTION_DIGEST)) {
      fields.add(FieldCatalogDef.forTesting(4).field(field));
    }
    fields.add(new FieldCatalogDef.FieldDef(
        SchemaFields.SOURCE_SHA256, "keyword", true, false, List.of(), null, null, false));
    return new FieldCatalogDef("file-broad-replay-test", fields);
  }

  private static boolean put(SqliteJobQueue queue, SwitchBufferCapableQueue.SwitchBufferOp op) {
    return queue.putSwitchBufferForGeneration(GENERATION, op.key(), op.op(), op.payload());
  }

  private Optional<KnowledgeServerMigrationOps.StrictReplay> prepare(
      SqliteJobQueue queue, RunningRuntime runtime) {
    return prepare(queue, runtime, Set.of());
  }

  private Optional<KnowledgeServerMigrationOps.StrictReplay> prepare(
      SqliteJobQueue queue, RunningRuntime runtime,
      Set<SwitchBufferCapableQueue.SwitchBufferOp> approvedGapVersions) {
    var context = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
        queue, runtime, null, IndexingPacing.unthrottled(), Path.of("."), Path.of("."),
        new ObjectMapper(), () -> false, () -> true,
        LoggerFactory.getLogger(AcceptedFileBroadReplayTest.class), Long.MAX_VALUE,
        GENERATION, ignored -> true, approvedGapVersions);
    return KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(context);
  }

  private static void assertKinds(
      List<SwitchBufferCapableQueue.SwitchBufferOp> rows, String... expected) {
    assertEquals(List.of(expected), rows.stream()
        .map(SwitchBufferCapableQueue.SwitchBufferOp::op).toList());
  }

  private static void assertSourceMarker(SwitchBufferCapableQueue.SwitchBufferOp marker) {
    assertEquals("PROJECTION_SOURCE", marker.op());
    assertEquals("projection-source:" + SOURCE.length() + ":" + SOURCE, marker.key());
    assertEquals(SOURCE, marker.payload());
  }

  private static void indexProjection(RunningRuntime runtime, AcceptedProjection projection,
      String digest) {
    Map<String, Object> fields = new HashMap<>(
        ProjectionDocumentMapper.toIndexDocument(projection).fields());
    fields.put(SchemaFields.PROJECTION_DIGEST, digest);
    runtime.commitOps().stopCommitTimer();
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
    runtime.commitOps().maybeRefreshBlocking();
  }

  private static void assertProjectionWitness(RunningRuntime runtime,
      AcceptedProjection projection, String expectedPath, String expectedCollection)
      throws IOException {
    assertEquals(projection.indexId(), runtime.documentFieldOps().getDocumentFieldOrThrow(
        projection.indexId(), SchemaFields.DOC_ID));
    assertEquals(SOURCE, runtime.documentFieldOps().getDocumentFieldOrThrow(
        projection.indexId(), SchemaFields.PROJECTION_SOURCE_ID));
    assertEquals(Long.toString(projection.sourceRevision()), runtime.documentFieldOps()
        .getDocumentFieldOrThrow(projection.indexId(), SchemaFields.PROJECTION_SOURCE_REVISION));
    assertEquals(projection.fieldsDigest(), runtime.documentFieldOps().getDocumentFieldOrThrow(
        projection.indexId(), SchemaFields.PROJECTION_DIGEST));
    assertEquals(expectedPath, runtime.documentFieldOps().getDocumentFieldOrThrow(
        projection.indexId(), SchemaFields.PATH));
    assertEquals(expectedCollection, runtime.documentFieldOps().getDocumentFieldOrThrow(
        projection.indexId(), SchemaFields.COLLECTION));
  }

  private static AcceptedProjection projection(String id, long revision, String collection,
      String path, String content) {
    return new AcceptedProjection(SOURCE, id, revision, AcceptedProjection.Kind.UPSERT,
        "{\"collection\":\"" + json(collection) + "\",\"content\":\""
            + json(content) + "\",\"path\":\"" + json(path) + "\"}");
  }

  private static String json(String text) {
    return text.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static boolean hasDocument(RunningRuntime runtime, String id) throws IOException {
    return id.equals(runtime.documentFieldOps().getDocumentFieldOrThrow(id, SchemaFields.DOC_ID));
  }

  private static SwitchBufferCapableQueue.SwitchBufferOp op(
      String key, String kind, String payload) {
    return new SwitchBufferCapableQueue.SwitchBufferOp(
        GENERATION, key, kind, payload, System.currentTimeMillis(),
        Long.toString(System.nanoTime()));
  }
}
