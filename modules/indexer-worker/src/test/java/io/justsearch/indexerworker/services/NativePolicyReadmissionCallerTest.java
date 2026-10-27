/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.loop.FileMutationFence;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue.SwitchBufferOp;
import io.justsearch.indexerworker.queue.SwitchBufferUpsert;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** Real SQLite/Lucene/file-fence caller checks; this component fixture does not run an extractor. */
@EnabledOnOs(OS.LINUX)
@ExtendWith(NoVolumeIdentityExtension.class)
@Timeout(30)
final class NativePolicyReadmissionCallerTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  private static final String CANDIDATE = "g-native-policy";
  private static final JobQueue.EnqueueProvenance ORIGIN = new JobQueue.EnqueueProvenance("system", "MIGRATION_ENUMERATOR");
  private static final IngestionOutcome SKIP = IngestionOutcome.of(IngestionOutcomeClass.SKIPPED_POLICY,
      "SKIPPED_TEMP_OR_SYSTEM", IngestionRetryPolicy.NONE);

  @Test
  void liveNoRootDebtCertifiesOnlyItsExactCurrentUiSkipAndFinalPublicationSnapshot() throws Exception {
    try (var f = new Fixture()) {
      Path file = f.source("private.txt"); var op = f.skipped(file);
      var store = f.configuration("[\"private.txt\"]");
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      var subscription = f.registry.subscription(f.root);
      assertNotNull(subscription);
      assertTrue(f.registry.isCurrentAndActive(subscription));
      assertEquals(RootIdentity.capture(f.root), subscription.rootIdentity());
      var policy = f.policy(store, false);
      var replay = f.prepare(policy);
      assertTrue(replay.isPresent(), "the no-root-debt path needs complete explicit declarations");
      assertEquals(List.of(op), replay.orElseThrow().versions());
      try (var fence = f.mutation.beginFinalFence(f.runtime, 1_000)) {
        assertNotNull(fence);
        assertEquals(List.of(file.toString()), policy.omittedPaths());
      }
      assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
      assertTrue(f.finalDecision(policy, () -> {
        store.publicationLock().writeLock().lock();
        try { return policy.reproveBeforePointer(); }
        finally { store.publicationLock().writeLock().unlock(); }
      }));
      store.update(f.configuration("[]").currentSnapshot());
      store.update(f.configuration("[\"private.txt\"]").currentSnapshot());
      store.publicationLock().writeLock().lock();
      try { assertFalse(policy.reproveBeforePointer(), "identical policy bytes are a different publication"); }
      finally { store.publicationLock().writeLock().unlock(); }
      assertEquals(List.of(op), f.queue.listSwitchBufferOpsStrict());
    }
  }

  @Test
  void genuineAlreadyAdmittedUiSkipNeedsNoReadOfNowUnreadableSourceContent() throws Exception {
    try (var f = new Fixture()) {
      Path file = f.source("private.txt"); var old = f.skipped(file);
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      var before = f.queue.watcherReconciliationRow(file.toString());
      var permissions = Files.getPosixFilePermissions(file);
      try {
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        assertFalse(Files.isReadable(file), "SETUP: actual source content became unreadable only after valid admission");
        var replay = f.prepare(f.policy(f.configuration("[\"private.txt\"]"), false));
        assertTrue(replay.isPresent());
        assertEquals(List.of(old), replay.orElseThrow().versions());
        assertEquals(before, f.queue.watcherReconciliationRow(file.toString()));
        assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
      } finally { Files.setPosixFilePermissions(file, permissions); }
    }
  }

  @Test
  void committedBootReadmitsPresentSourceBeforeSubscriptionWithoutInventingAWatchOrReceipt() throws Exception {
    try (var f = new Fixture()) {
      Path file = f.source("private.txt"); var old = f.skipped(file);
      assertNull(f.registry.subscription(f.root));
      Files.writeString(file, "new current bytes after the old skip");
      var policy = f.policy(f.configuration("[]"), true);
      assertTrue(f.readmit(policy));
      var actual = f.queue.listSwitchBufferOpsStrict().getFirst();
      var payload = SwitchBufferUpsert.decode(actual.payload());
      var row = f.queue.watcherReconciliationRow(file.toString());
      assertEquals("PENDING", row.get("state"));
      assertEquals(SourceContentHash.sha256(file), payload.sourceSha256());
      assertEquals(payload.unitRevision(), row.get("unit_revision"));
      assertEquals(f.root, payload.ingestionRoot()); assertNull(payload.collection());
      assertNotEquals(old.revision(), actual.revision()); assertTrue(actual.acceptedOrder() > old.acceptedOrder());
      assertNull(f.registry.subscription(f.root), "later boot reconciliation still owns real watch registration");
      assertEquals(1, f.queue.listSwitchBufferOpsStrict().size(), "no fabricated root debt or extra authority");
    }
  }

  @Test
  void supportedOperatorAllowingPolicyCanReadmitButOperatorExclusionCannotCertifyUiOmission() throws Exception {
    String previous = System.getProperty("justsearch.ui.exclude_patterns");
    try (var f = new Fixture()) {
      Path file = f.source("private.txt"); var old = f.skipped(file);
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      System.setProperty("justsearch.ui.exclude_patterns", "[\"private.txt\"]");
      var excluded = f.configuration("[]");
      assertTrue(excluded.currentSnapshot().resolution("justsearch.ui.exclude_patterns").sourceOrdinal() > 300,
          "SETUP: use the actual supported high-priority property source");
      var cannotOmit = f.policy(excluded, false);
      assertTrue(cannotOmit.freeze(f.queue.listSwitchBufferOpsStrict()));
      assertFalse(cannotOmit.certify(old));
      System.setProperty("justsearch.ui.exclude_patterns", "[]");
      var allowed = f.configuration("[\"private.txt\"]");
      assertTrue(allowed.currentSnapshot().resolution("justsearch.ui.exclude_patterns").sourceOrdinal() > 300);
      assertTrue(f.readmit(f.policy(allowed, false)));
      assertEquals("PENDING", f.queue.watcherReconciliationRow(file.toString()).get("state"));
      assertNotEquals(old.revision(), f.queue.listSwitchBufferOpsStrict().getFirst().revision());
    } finally {
      if (previous == null) System.clearProperty("justsearch.ui.exclude_patterns");
      else System.setProperty("justsearch.ui.exclude_patterns", previous);
    }
  }

  @Test
  void ordinaryLabelsCannotBorrowProtectedPhysicalOrProjectionOwnership() throws Exception {
    try (var f = new Fixture()) {
      Path file = Files.writeString(Files.createDirectories(f.data.resolve("agent-history")).resolve("private.txt"), "private");
      var old = f.skipped(file);
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      var protectedPolicy = f.policy(f.configuration("[\"**/*.txt\"]"), false);
      assertTrue(protectedPolicy.freeze(f.queue.listSwitchBufferOpsStrict()));
      assertFalse(protectedPolicy.excludes(file, f.root, null));
      assertFalse(protectedPolicy.certify(old));
      assertFalse(f.readmit(f.policy(f.configuration("[]"), false)));
      assertEquals(List.of(old), f.queue.listSwitchBufferOpsStrict());
      assertEquals("DONE", f.queue.watcherReconciliationRow(file.toString()).get("state"));
      Path projectionPath = f.source("projection.txt"); var projectionOp = f.skipped(projectionPath);
      f.runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "projection-owned-id", SchemaFields.DOC_UID, "projection-owned-id",
          SchemaFields.PATH, projectionPath.toString(), SchemaFields.PROJECTION_SOURCE_ID, "owned-projection",
          SchemaFields.COLLECTION, "documents", SchemaFields.CONTENT, "projection-owned text")));
      f.runtime.commitOps().commitAndTrack();
      f.runtime.commitOps().maybeRefreshBlocking();
      assertEquals(projectionPath.toString(), f.runtime.documentFieldOps()
          .getDocumentFieldOrThrow("projection-owned-id", SchemaFields.PATH));
      assertEquals("owned-projection", f.runtime.documentFieldOps()
          .getDocumentFieldOrThrow("projection-owned-id", SchemaFields.PROJECTION_SOURCE_ID));
      assertEquals(1, f.runtime.indexCountOps().countQueryOrThrow(new org.apache.lucene.search.BooleanQuery.Builder()
          .add(new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term(SchemaFields.PATH,
              projectionPath.toString())), org.apache.lucene.search.BooleanClause.Occur.FILTER)
          .add(new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term(SchemaFields.DOC_ID,
              "projection-owned-id")), org.apache.lucene.search.BooleanClause.Occur.FILTER).build()),
          "SETUP: actual committed canonical projection membership precedes the protection check");
      var snapshot = f.queue.listSwitchBufferOpsStrict();
      var policy = f.policy(f.configuration("[]"), false);
      assertFalse(f.readmit(policy));
      assertEquals(snapshot, f.queue.listSwitchBufferOpsStrict());
      assertEquals("DONE", f.queue.watcherReconciliationRow(projectionPath.toString()).get("state"));
      var omission = f.policy(f.configuration("[\"**/*.txt\"]"), false);
      assertTrue(omission.freeze(snapshot));
      assertFalse(omission.certify(projectionOp), "a non-file projection id sharing PATH is protected");
    }
  }

  @Test
  void acceptedProjectionPathIsProtectedBeforeItsRowsAreReplayedOnTarget() throws Exception {
    try (var f = new Fixture()) {
      Path file = f.source("projected.txt"); var old = f.skipped(file);
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      var projection = new io.justsearch.app.api.indexing.AcceptedProjection("owned-source", "document", 1,
          io.justsearch.app.api.indexing.AcceptedProjection.Kind.UPSERT,
          JsonMapper.builder().build().writeValueAsString(Map.of(
              SchemaFields.PATH, file.toString(), SchemaFields.COLLECTION, "documents", SchemaFields.CONTENT, "projection")));
      KnowledgeServerMigrationOps.seedProjectionSource(f.queue, f.generation,
          new io.justsearch.app.api.indexing.ProjectionSeedSource() {
            @Override public String sourceId() { return projection.sourceId(); }
            @Override public void enumerate(java.util.function.Consumer<io.justsearch.app.api.indexing.AcceptedProjection> sink) {
              sink.accept(projection);
            }
          });
      assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
      assertNull(f.runtime.documentFieldOps().getDocumentFieldOrThrow(projection.indexId(), SchemaFields.DOC_ID));
      var journal = f.queue.listSwitchBufferOpsStrict();
      assertTrue(journal.contains(old));
      assertTrue(f.queue.tryMatchesNativePolicySkippedAdmission(f.generation, journal, old,
          f.queue.watcherReconciliationRow(file.toString())), "exact file ownership alone is insufficient");
      assertFalse(f.readmit(f.policy(f.configuration("[]"), false)));
      assertEquals(journal, f.queue.listSwitchBufferOpsStrict());
      assertEquals("DONE", f.queue.watcherReconciliationRow(file.toString()).get("state"));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "replayedCanonicalStoredProjectionAndLegalAliasesBlockNativePolicyPermission({0},{1})")
  @org.junit.jupiter.params.provider.CsvSource({"canonical,target", "canonical,serving", "canonical,both",
      "dot,target", "dot,serving", "dotdot,target"})
  void replayedCanonicalStoredProjectionAndLegalAliasesBlockNativePolicyPermission(String spelling, String placement) throws Exception {
    try (var f = new Fixture(false, true, !placement.equals("target"))) {
      var projectionRuntime = placement.equals("serving") ? f.serving : f.runtime;
      Path file = f.source("projected.txt");
      Files.createDirectory(f.root.resolve("branch"));
      String raw = switch (spelling) {
        case "dot" -> f.root + "/./projected.txt";
        case "dotdot" -> f.root + "/branch/../projected.txt";
        default -> file.toString();
      };
      assertEquals(file, Path.of(raw).toAbsolutePath().normalize());
      assertTrue(Files.isSameFile(file, Path.of(raw)), "SETUP: aliases refer to the owned ordinary source");
      var projection = new io.justsearch.app.api.indexing.AcceptedProjection("canonical-source", "document", 1,
          io.justsearch.app.api.indexing.AcceptedProjection.Kind.UPSERT,
          JsonMapper.builder().build().writeValueAsString(Map.of(SchemaFields.PATH, raw,
              SchemaFields.COLLECTION, "documents", SchemaFields.CONTENT, "source-owned projection")));
      KnowledgeServerMigrationOps.seedProjectionSource(f.queue, f.generation,
          new io.justsearch.app.api.indexing.ProjectionSeedSource() {
            @Override public String sourceId() { return projection.sourceId(); }
            @Override public void enumerate(java.util.function.Consumer<io.justsearch.app.api.indexing.AcceptedProjection> sink) {
              sink.accept(projection);
            }
          });
      var context = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(f.queue, projectionRuntime, null,
          IndexingPacing.unthrottled(), f.base, projectionRuntime.openedIndexPath(), JsonMapper.builder().build(),
          () -> false, () -> true, LoggerFactory.getLogger(NativePolicyReadmissionCallerTest.class),
          System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5), f.generation, ignored -> true)
          .withPublisher(f.publisher).withMigrationRoots(List.of(new ResolvedConfig.FileSource(f.root, null)));
      try (var fence = f.mutation.beginFinalFence(f.runtime, 1_000)) {
        assertNotNull(fence);
        assertTrue(KnowledgeServerMigrationOps.drainSwitchBufferStrict(context), "SETUP: real projection seed/replay commits and acknowledges");
      }
      assertTrue(f.queue.listSwitchBufferOpsStrict().isEmpty(), "SETUP: protection must come from indexed rows, not buffered guard");
      assertEquals(raw, projectionRuntime.documentFieldOps().getDocumentFieldOrThrow(projection.indexId(), SchemaFields.PATH));
      assertEquals(projection.sourceId(), projectionRuntime.documentFieldOps()
          .getDocumentFieldOrThrow(projection.indexId(), SchemaFields.PROJECTION_SOURCE_ID));
      assertEquals(1, projectionRuntime.indexCountOps().countQueryOrThrow(new org.apache.lucene.search.TermQuery(
          new org.apache.lucene.index.Term(SchemaFields.DOC_ID, projection.indexId()))));
      assertEquals(1, projectionRuntime.indexCountOps().countQueryOrThrow(new org.apache.lucene.search.TermQuery(
          new org.apache.lucene.index.Term(SchemaFields.PATH, raw))));
      if (placement.equals("both")) {
        f.serving.indexingCoordinator().indexSingle(ProjectionDocumentMapper.toIndexDocument(projection));
        f.serving.commitOps().maybeRefreshBlocking();
        assertEquals(projection.sourceId(), f.serving.documentFieldOps()
            .getDocumentFieldOrThrow(projection.indexId(), SchemaFields.PROJECTION_SOURCE_ID));
      }
      var old = f.skipped(file);
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      var journal = f.queue.listSwitchBufferOpsStrict();
      var row = f.queue.watcherReconciliationRow(file.toString());
      var omission = f.policy(f.configuration("[\"projected.txt\"]"), false);
      assertTrue(omission.freeze(journal));
      boolean certified = omission.certify(old);
      boolean readmitted = f.readmit(f.policy(f.configuration("[]"), false));
      assertAll(
          () -> assertFalse(certified, "indexed canonical projection/alias cannot certify ordinary omission"),
          () -> assertFalse(readmitted, "indexed canonical projection/alias cannot acquire file readmission"),
          () -> assertEquals(journal, f.queue.listSwitchBufferOpsStrict()),
          () -> assertEquals(row, f.queue.watcherReconciliationRow(file.toString())),
          () -> assertEquals("source-owned projection", projectionRuntime.documentFieldOps()
              .getDocumentFieldOrThrow(projection.indexId(), SchemaFields.CONTENT)));
    }
  }

  @Test
  void subscribedAncestorGlobMakesTwoNativeAttemptsWithoutContentHashOrFreshOrder() throws Exception {
    try (var f = new Fixture()) {
      Path file = f.source("private.txt"); f.skipped(file);
      assertTrue(f.registry.watch(directory.toString(), null).watching());
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      var store = f.configuration("[\"root/**\"]");
      var previous = ConfigStore.globalOrNull(); ConfigStore.setGlobal(store);
      try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
        assertFalse(io.justsearch.configuration.excludes.ExclusionPatterns.fromRawJsonStrict(
            store.currentSnapshot().ui().excludePatterns(), false).isExcludedPath(f.root, file),
            "SETUP: anchored glob does not match relative to the file's own ingestion root");
        assertTrue(f.publisher.isUserExcluded(file, f.root), "SETUP: real subscribed ancestor excludes while own-root spelling does not");
        var journal = f.queue.listSwitchBufferOpsStrict();
        var row = f.queue.watcherReconciliationRow(file.toString());
        boolean first = f.readmit(f.policy(store, false));
        boolean second = f.readmit(f.policy(store, false));
        assertAll(
            () -> assertFalse(first, "ancestor exclusion cannot mint a pending revision"),
            () -> assertFalse(second),
            () -> hashes.verify(() -> SourceContentHash.sha256(file), never()),
            () -> assertEquals(journal, f.queue.listSwitchBufferOpsStrict()),
            () -> assertEquals(row, f.queue.watcherReconciliationRow(file.toString())));
      } finally { ConfigStore.restoreGlobal(store, previous); }
    }
  }

  @Test
  void publicSaveAllowAfterSubscribedAncestorGlobReadmitsCurrentBytes() throws Exception {
    try (var f = new Fixture()) {
      Path file = f.source("private.txt"); var old = f.skipped(file);
      assertTrue(f.registry.watch(directory.toString(), null).watching());
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      var store = f.configuration("[\"root/**\"]");
      var previous = ConfigStore.globalOrNull(); ConfigStore.setGlobal(store);
      try {
        assertTrue(f.publisher.isUserExcluded(file, f.root));
        f.publicSave(store, List.of());
        assertFalse(f.publisher.isUserExcluded(file, f.root), "public Save updates the real worker predicate too");
        Files.writeString(file, "current bytes after public allow");
        assertTrue(f.readmit(f.policy(store, false)));
        var current = f.queue.listSwitchBufferOpsStrict().getFirst();
        assertNotEquals(old.revision(), current.revision());
        assertTrue(current.acceptedOrder() > old.acceptedOrder());
        assertEquals(SourceContentHash.sha256(file), SwitchBufferUpsert.decode(current.payload()).sourceSha256());
        assertEquals("PENDING", f.queue.watcherReconciliationRow(file.toString()).get("state"));
      } finally { ConfigStore.restoreGlobal(store, previous); }
    }
  }

  @Test
  void ancestorReplacedByALinkIntoProtectedFixtureStorageRefusesBeforeAnyQueueEffect() throws Exception {
    try (var f = new Fixture()) {
      Path parent = Files.createDirectory(f.root.resolve("ordinary"));
      Path file = Files.writeString(parent.resolve("private.txt"), "ordinary admitted bytes");
      var old = f.skipped(file);
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      Path protectedRoot = Files.createDirectories(f.data.resolve("agent-history"));
      Path protectedFile = Files.writeString(protectedRoot.resolve("private.txt"), "protected fixture bytes");
      Files.move(parent, f.root.resolve("old-ordinary"));
      Files.createSymbolicLink(parent, protectedRoot);
      assertEquals("protected fixture bytes", Files.readString(file), "SETUP: link reaches only the owned fixture");
      var row = f.queue.watcherReconciliationRow(file.toString());
      var journal = f.queue.listSwitchBufferOpsStrict();
      assertThrows(java.io.IOException.class, () -> f.readmit(f.policy(f.configuration("[]"), false)));
      assertEquals(row, f.queue.watcherReconciliationRow(file.toString()));
      assertEquals(journal, f.queue.listSwitchBufferOpsStrict()); assertTrue(journal.contains(old));
      assertEquals("protected fixture bytes", Files.readString(protectedFile));
    }
  }

  @Test
  void ancestorChangeAfterRealCommitIsUnconfirmedAndRetainsTheNewAuthoritativeReceipt() throws Exception {
    try (var f = new Fixture()) {
      Path parent = Files.createDirectory(f.root.resolve("ordinary"));
      Path file = Files.writeString(parent.resolve("private.txt"), "ordinary admitted bytes");
      var old = f.skipped(file);
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      Path protectedRoot = Files.createDirectories(f.data.resolve("agent-history"));
      Files.writeString(protectedRoot.resolve("private.txt"), "protected fixture bytes");
      var field = SqliteJobQueue.class.getDeclaredField("connection"); field.setAccessible(true);
      var original = (java.sql.Connection) field.get(f.queue);
      var intercepted = mock(java.sql.Connection.class, org.mockito.AdditionalAnswers.delegatesTo(original));
      var changed = new java.util.concurrent.atomic.AtomicBoolean();
      doAnswer(call -> {
        original.commit(); // Actual SQLite commit, not a fabricated successful result.
        try {
          Files.move(parent, f.root.resolve("old-ordinary")); Files.createSymbolicLink(parent, protectedRoot);
          changed.set(true);
        } catch (java.io.IOException setup) { throw new java.sql.SQLException("owned postcommit ancestry fixture failed", setup); }
        return null;
      }).when(intercepted).commit();
      field.set(f.queue, intercepted);
      try {
        var failure = assertThrows(java.io.IOException.class,
            () -> f.readmit(f.policy(f.configuration("[]"), false)));
        assertTrue(changed.get(), "SETUP: the filesystem change followed an actual confirmed SQLite commit");
        assertTrue(failure.getMessage().contains("committed"));
      } finally { field.set(f.queue, original); }
      var current = f.queue.listSwitchBufferOpsStrict().getFirst();
      assertNotEquals(old.revision(), current.revision()); assertTrue(current.acceptedOrder() > old.acceptedOrder());
      assertEquals("PENDING", f.queue.watcherReconciliationRow(file.toString()).get("state"));
      assertEquals(SwitchBufferUpsert.decode(current.payload()).unitRevision(),
          f.queue.watcherReconciliationRow(file.toString()).get("unit_revision"));
      assertEquals(0, f.queue.removeReplayedSwitchBufferOps(List.of(old)), "old cleanup cannot erase the committed replacement");
      assertEquals("protected fixture bytes", Files.readString(protectedRoot.resolve("private.txt")));
    }
  }

  @Test
  void unknownRecordedVolumeNeverCreatesReplacementOrAdoptsARecord() throws Exception {
    try (var f = new Fixture(); var unavailable = NoVolumeIdentityExtension.withVolumeIdentity(false)) {
      Path file = f.source("private.txt"); var old = f.skipped(file);
      var ledger = RootVolumeLedger.under(f.base);
      var recorded = ledger.recordedFor(f.root);
      assertTrue(recorded.isPresent(), "SETUP: the fixture recorded its actual previously known volume");
      assertNull(RootIdentity.capture(f.root).volume(), "SETUP: the current volume lookup is actually unknown");
      var records = Files.readAllBytes(f.base.resolve(RootVolumeLedger.FILE_NAME));
      assertThrows(java.io.IOException.class, () -> f.readmit(f.policy(f.configuration("[]"), true)));
      assertEquals(List.of(old), f.queue.listSwitchBufferOpsStrict());
      assertEquals("DONE", f.queue.watcherReconciliationRow(file.toString()).get("state"));
      assertEquals(recorded, ledger.recordedFor(f.root), "unknown current identity cannot adopt or replace a record");
      assertArrayEquals(records, Files.readAllBytes(f.base.resolve(RootVolumeLedger.FILE_NAME)));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"simple", "mixed", "root"})
  void stillExcludedCommittedReceiptsRequireFinalOwnerAndAcknowledgeWithoutOldSubscription(String route) throws Exception {
    try (var f = new Fixture(true)) {
      Path file = f.source("private.txt"); var old = f.skipped(file);
      f.addMixedOrRoot(route);
      f.commitPointer();
      var journal = f.queue.listSwitchBufferOpsStrict();
      var store = f.configuration("[\"private.txt\"]");
      assertNull(f.registry.subscription(f.root));
      assertFalse(f.settle(route, f.policy(store, true)), "a missing final owner cannot ACK a new omission");
      assertEquals(journal, f.queue.listSwitchBufferOpsStrict());
      var policy = f.policy(store, true);
      var decisions = new java.util.concurrent.atomic.AtomicInteger();
      assertTrue(f.settleWithDecision(route, store, policy, () -> {}, () -> {}, decisions));
      assertEquals(1, decisions.get());
      assertTrue(f.queue.listSwitchBufferOpsStrict().isEmpty());
      assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
      assertEquals("old skipped bytes", Files.readString(file));
      assertEquals("DONE", f.queue.watcherReconciliationRow(file.toString()).get("state"));
      if (!route.equals("root")) assertNull(f.registry.subscription(f.root), "plain proof invents no historical watch");
      assertTrue(old.acceptedOrder() > 0);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"simple,false", "simple,true", "mixed,false", "mixed,true", "root,false", "root,true"})
  void publicSaveBeforeFinalDecisionIncludingIdenticalPublicationRetainsEveryOldReceipt(String route, boolean identical) throws Exception {
    try (var f = new Fixture(true)) {
      Path file = f.source("private.txt"); f.skipped(file); f.addMixedOrRoot(route); f.commitPointer();
      var store = f.configuration("[\"private.txt\"]");
      var journal = f.queue.listSwitchBufferOpsStrict();
      var previous = store.currentSnapshot();
      var policy = f.policy(store, true);
      assertFalse(f.settleWithDecision(route, store, policy,
          () -> f.publicSave(store, identical ? List.of("private.txt") : List.of()), () -> {},
          new java.util.concurrent.atomic.AtomicInteger()));
      assertNotSame(previous, store.currentSnapshot());
      assertEquals(identical ? "[\"private.txt\"]" : "[]", store.currentSnapshot().ui().excludePatterns());
      assertEquals(journal, f.queue.listSwitchBufferOpsStrict());
      assertEquals("DONE", f.queue.watcherReconciliationRow(file.toString()).get("state"));
      assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(file.toString()));
      if (identical) {
        assertTrue(f.settleWithDecision(route, store, f.policy(store, true), () -> {}, () -> {},
            new java.util.concurrent.atomic.AtomicInteger()), "a fresh current owner may certify the same policy bytes");
      } else if (!route.equals("root")) {
        assertTrue(f.readmit(f.policy(store, true)), "a fresh attempt readmits current allowed bytes");
        assertEquals("PENDING", f.queue.watcherReconciliationRow(file.toString()).get("state"));
      }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"simple", "mixed", "root"})
  void publicSaveAfterDecisionRunsOutsidePublicationAndLaterOrdinaryBootAdmissionUsesCurrentPolicy(String route) throws Exception {
    try (var f = new Fixture(true)) {
      Path file = f.source("private.txt"); var old = f.skipped(file); f.addMixedOrRoot(route); f.commitPointer();
      var oldUnit = SwitchBufferUpsert.decode(old.payload()).unitRevision();
      var store = f.configuration("[\"private.txt\"]");
      assertTrue(f.settleWithDecision(route, store, f.policy(store, true), () -> {},
          () -> f.publicSave(store, List.of()), new java.util.concurrent.atomic.AtomicInteger()));
      assertEquals("[]", store.currentSnapshot().ui().excludePatterns());
      assertTrue(f.queue.listSwitchBufferOpsStrict().isEmpty(), "only the earlier exact decision was acknowledged");
      // Real ordinary boot discovery/admission runs after the final decision/ACK owners exit.
      // This component's loop is a double: full extractor/writer boot convergence is a separate gate.
      var previous = ConfigStore.globalOrNull(); ConfigStore.setGlobal(store);
      try {
        assertTrue(f.publisher.reconcileCommittedBootRoot(
            new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(f.root, null), () -> false));
      } finally { ConfigStore.restoreGlobal(store, previous); }
      var row = f.queue.watcherReconciliationRow(file.toString());
      assertEquals("PENDING", row.get("state")); assertNotEquals(oldUnit, row.get("unit_revision"));
      assertEquals(SourceContentHash.sha256(file), row.get("planned_source_sha256"));
      assertNotNull(f.registry.subscription(f.root));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"simple", "mixed", "root"})
  void exactReplacementAfterDecisionAndProofErrorNeverEraseNewOrUnknownWork(String route) throws Exception {
    try (var f = new Fixture(true)) {
      Path file = f.source("private.txt"); var old = f.skipped(file); f.addMixedOrRoot(route); f.commitPointer();
      var journal = f.queue.listSwitchBufferOpsStrict(); var store = f.configuration("[\"private.txt\"]");
      assertFalse(f.settleWithDecision(route, store, f.policy(store, true), () -> {
        try { f.queue.close(); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
      }, () -> {}, new java.util.concurrent.atomic.AtomicInteger()));
      f.queue.open(); assertEquals(journal, f.queue.listSwitchBufferOpsStrict(), "unavailable proof cannot ACK");
      var replacement = new java.util.concurrent.atomic.AtomicReference<SwitchBufferOp>();
      assertFalse(f.settleWithDecision(route, store, f.policy(store, true), () -> {}, () -> {
        // Existing queue-owner API, deliberately not a purported public INGEST bypass.
        assertTrue(f.queue.enqueueAndBufferFileForGeneration(f.generation,
            JobQueue.EnqueueEntry.stat(file, ORIGIN).withinRoot(f.root), null));
        replacement.set(f.queue.listSwitchBufferOpsStrict().stream().filter(op -> old.key().equals(op.key())).findFirst().orElseThrow());
      }, new java.util.concurrent.atomic.AtomicInteger()));
      assertNotNull(replacement.get()); assertNotEquals(old.revision(), replacement.get().revision());
      assertTrue(f.queue.listSwitchBufferOpsStrict().contains(replacement.get()));
      assertEquals("PENDING", f.queue.watcherReconciliationRow(file.toString()).get("state"));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "completeAncestorScopeOmitsWithoutHashingAndRetainsExactReceiptUntilFinalDecision({0})")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"declared", "subscribed", "awaiting"})
  void completeAncestorScopeOmitsWithoutHashingAndRetainsExactReceiptUntilFinalDecision(String scope) throws Exception {
    try (var f = new Fixture(false, false, false, scope.equals("declared"))) {
      Path file = f.source("private.txt"); var op = f.skipped(file);
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      if (scope.equals("subscribed")) assertTrue(f.registry.watch(directory.toString(), "ancestor").watching());
      if (scope.equals("awaiting")) {
        f.registry.watchWhenVolumeReturns(directory, "ancestor");
        assertTrue(f.registry.awaitsVolume(directory));
      }
      var store = f.configuration("[\"root/**\"]");
      var previous = ConfigStore.globalOrNull(); ConfigStore.setGlobal(store);
      try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
        assertTrue(f.publisher.isUserExcluded(file, f.root), "SETUP: the actual worker owner excludes across root labels");
        var journal = f.queue.listSwitchBufferOpsStrict();
        var row = f.queue.watcherReconciliationRow(file.toString());
        var policy = f.policy(store, false);
        assertFalse(f.readmit(policy));
        assertFalse(f.readmit(f.policy(store, false)));
        hashes.verify(() -> SourceContentHash.sha256(file), never());
        assertEquals(journal, f.queue.listSwitchBufferOpsStrict());
        assertEquals(row, f.queue.watcherReconciliationRow(file.toString()));
        assertTrue(f.prepare(policy).isPresent(), "current exact ancestry can certify this omission");
        assertTrue(f.finalDecision(policy, () -> {
          store.publicationLock().writeLock().lock();
          try { return policy.reproveBeforePointer(); }
          finally { store.publicationLock().writeLock().unlock(); }
        }));
        assertEquals(List.of(op), f.queue.listSwitchBufferOpsStrict(), "proof does not notify or ACK itself");
      } finally { ConfigStore.restoreGlobal(store, previous); }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "storedParentAndLateOwnershipNeverBecomeOrdinaryFilePermission({0})")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"parent", "missing-parent", "blank-marker", "reserved-id", "late-alias"})
  void storedParentAndLateOwnershipNeverBecomeOrdinaryFilePermission(String kind) throws Exception {
    try (var f = new Fixture()) {
      Path file = f.source("private.txt"); var op = f.skipped(file);
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      var documents = new ArrayList<Map<String, Object>>();
      if (kind.equals("late-alias")) {
        Files.createDirectory(f.root.resolve("zz"));
        documents.add(Map.of(SchemaFields.DOC_ID, file.toString(), SchemaFields.PATH, file.toString()));
        for (int n = 0; n < 300; n++) documents.add(Map.of(SchemaFields.DOC_ID, file + "#chunk-" + n,
            SchemaFields.PARENT_DOC_ID, file.toString(), SchemaFields.PATH, file.toString()));
        documents.add(Map.of(SchemaFields.DOC_ID, "late-owner", SchemaFields.PATH, f.root + "/zz/../private.txt",
            SchemaFields.PROJECTION_SOURCE_ID, "late-source"));
      } else if (kind.equals("parent") || kind.equals("missing-parent")) {
        if (kind.equals("parent")) documents.add(Map.of(SchemaFields.DOC_ID, "source-parent",
            SchemaFields.PROJECTION_SOURCE_ID, "real-parent-source"));
        documents.add(Map.of(SchemaFields.DOC_ID, "child", SchemaFields.PARENT_DOC_ID, "source-parent",
            SchemaFields.PATH, file.toString()));
      } else if (kind.equals("blank-marker")) {
        documents.add(Map.of(SchemaFields.DOC_ID, file.toString(), SchemaFields.PATH, file.toString(),
            SchemaFields.PROJECTION_SOURCE_ID, " "));
      } else documents.add(Map.of(SchemaFields.DOC_ID, "projection:unmarked", SchemaFields.PATH, file.toString()));
      for (var metadata : documents) f.indexMetadata(metadata);
      if (kind.equals("late-alias")) {
        assertTrue(Files.isSameFile(file, Path.of(f.root + "/zz/../private.txt")));
        var firstPage = f.runtime.readPathOps().search(new org.apache.lucene.search.MatchAllDocsQuery(), 256,
            java.util.Set.of(SchemaFields.PATH, SchemaFields.PROJECTION_SOURCE_ID),
            io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort.PATH_ASC, null);
        assertEquals(256, firstPage.hits().size(), "SETUP: fill the existing strict reader's complete first page");
        assertTrue(firstPage.hits().stream().noneMatch(hit -> "late-owner".equals(hit.docId())));
        assertEquals("late-source", f.runtime.documentFieldOps().getDocumentFieldOrThrow("late-owner", SchemaFields.PROJECTION_SOURCE_ID));
      }
      if (kind.equals("blank-marker")) assertEquals(" ", f.runtime.documentFieldOps()
          .getDocumentFieldOrThrow(file.toString(), SchemaFields.PROJECTION_SOURCE_ID), "SETUP: blank stored marker exists");
      var journal = f.queue.listSwitchBufferOpsStrict();
      var row = f.queue.watcherReconciliationRow(file.toString());
      try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
        if (kind.equals("missing-parent")) assertThrows(java.io.IOException.class,
            () -> f.readmit(f.policy(f.configuration("[]"), false)));
        else assertFalse(f.readmit(f.policy(f.configuration("[]"), false)));
        hashes.verify(() -> SourceContentHash.sha256(file), never());
      }
      var omitted = f.policy(f.configuration("[\"private.txt\"]"), false);
      assertTrue(omitted.freeze(journal));
      try (var fence = f.mutation.beginFinalFence(f.runtime, 1_000)) {
        assertNotNull(fence);
        assertFalse(omitted.certify(op));
      }
      assertEquals(journal, f.queue.listSwitchBufferOpsStrict());
      assertEquals(row, f.queue.watcherReconciliationRow(file.toString()));
    }
  }

  @Test
  void ancestorExclusionStillCannotCertifyAProjectionOwner() throws Exception {
    try (var f = new Fixture()) {
      Path file = f.source("private.txt"); var op = f.skipped(file);
      assertTrue(f.registry.watch(directory.toString(), "ancestor").watching());
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      f.indexMetadata(Map.of(SchemaFields.DOC_ID, "projection-owner", SchemaFields.PATH, f.root + "/./private.txt",
          SchemaFields.PROJECTION_SOURCE_ID, "source"));
      var policy = f.policy(f.configuration("[\"root/**\"]"), false);
      var journal = f.queue.listSwitchBufferOpsStrict(); var row = f.queue.watcherReconciliationRow(file.toString());
      assertTrue(policy.freeze(journal));
      try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
        assertFalse(f.readmit(policy));
        hashes.verify(() -> SourceContentHash.sha256(file), never());
      }
      try (var fence = f.mutation.beginFinalFence(f.runtime, 1_000)) {
        assertNotNull(fence);
        assertFalse(policy.certify(op));
      }
      assertEquals(journal, f.queue.listSwitchBufferOpsStrict());
      assertEquals(row, f.queue.watcherReconciliationRow(file.toString()));
    }
  }

  @Test
  void ancestorMembershipAbaInvalidatesAttemptAndUnregisteredAncestorNeverSuppliesPolicy() throws Exception {
    try (var f = new Fixture()) {
      Path file = f.source("private.txt"); var op = f.skipped(file);
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      var store = f.configuration("[\"root/**\"]");
      var before = f.policy(store, false);
      assertTrue(before.allows(file, f.root, null), "an unregistered filesystem ancestor has no policy authority");
      assertTrue(f.registry.watch(directory.toString(), "ancestor").watching());
      var policy = f.policy(store, false);
      assertTrue(f.registry.unwatch(directory.toString()));
      assertTrue(f.registry.watch(directory.toString(), "ancestor").watching());
      assertFalse(policy.allows(file, f.root, null));
      assertFalse(policy.excludes(file, f.root, null), "rewatch cannot revive the earlier witness");
      assertFalse(before.allows(file, f.root, null), "ancestor addition invalidates a previously allowed attempt");
      assertTrue(f.policy(store, false).excludes(file, f.root, null));
      assertEquals(List.of(op), f.queue.listSwitchBufferOpsStrict());
    }
  }

  @Test
  void ownerThreadUncommittedReplayInvalidatesPreparedOwnershipBeforePublication() throws Exception {
    try (var f = new Fixture(false, true)) {
      Path file = f.source("private.txt"); var op = f.skipped(file);
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      var store = f.configuration("[\"private.txt\"]"); var policy = f.policy(store, false);
      assertTrue(f.prepare(policy).isPresent());
      var journal = f.queue.listSwitchBufferOpsStrict(); var row = f.queue.watcherReconciliationRow(file.toString());
      long epoch = admissionEpoch(f.runtime);
      assertFalse(f.finalDecision(policy, () -> {
        var projection = new io.justsearch.app.api.indexing.AcceptedProjection("late-source", "document", 1,
            io.justsearch.app.api.indexing.AcceptedProjection.Kind.UPSERT,
            JsonMapper.builder().build().writeValueAsString(Map.of(SchemaFields.PATH, f.root + "/./private.txt",
                SchemaFields.COLLECTION, "documents", SchemaFields.CONTENT, "late uncommitted owner")));
        assertDoesNotThrow(() -> f.runtime.indexingCoordinator().indexSingle(ProjectionDocumentMapper.toIndexDocument(projection)));
        assertEquals(epoch, admissionEpoch(f.runtime), "SETUP: durable commit epoch did not change");
        store.publicationLock().writeLock().lock();
        try { return policy.reproveBeforePointer(); }
        finally { store.publicationLock().writeLock().unlock(); }
      }), "owner-thread replay cannot reuse a prepared reader or durable-epoch witness");
      assertEquals(List.of(op), journal); assertEquals(journal, f.queue.listSwitchBufferOpsStrict());
      assertEquals(row, f.queue.watcherReconciliationRow(file.toString()));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "allCommittedAckConsumersRetainAReplayedProjectionOwner({0})")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"simple", "mixed", "root"})
  void allCommittedAckConsumersRetainAReplayedProjectionOwner(String route) throws Exception {
    try (var f = new Fixture(true, true)) {
      Path file = f.source("private.txt");
      var projection = new io.justsearch.app.api.indexing.AcceptedProjection("ack-source", "document", 1,
          io.justsearch.app.api.indexing.AcceptedProjection.Kind.UPSERT,
          JsonMapper.builder().build().writeValueAsString(Map.of(SchemaFields.PATH, f.root + "/./private.txt",
              SchemaFields.COLLECTION, "documents", SchemaFields.CONTENT, "replayed owner")));
      f.replayProjection(projection);
      assertTrue(f.queue.listSwitchBufferOpsStrict().isEmpty());
      assertEquals(projection.sourceId(), f.runtime.documentFieldOps().getDocumentFieldOrThrow(
          projection.indexId(), SchemaFields.PROJECTION_SOURCE_ID));
      var op = f.skipped(file); f.addMixedOrRoot(route); f.commitPointer();
      var journal = f.queue.listSwitchBufferOpsStrict(); var row = f.queue.watcherReconciliationRow(file.toString());
      var store = f.configuration("[\"private.txt\"]");
      assertFalse(f.settleWithDecision(route, store, f.policy(store, true), () -> {}, () -> {},
          new java.util.concurrent.atomic.AtomicInteger()), "actual ACK consumer cannot erase an unproved omission");
      assertTrue(journal.contains(op)); assertEquals(journal, f.queue.listSwitchBufferOpsStrict());
      assertEquals(row, f.queue.watcherReconciliationRow(file.toString()));
      assertEquals("replayed owner", f.runtime.documentFieldOps().getDocumentFieldOrThrow(projection.indexId(), SchemaFields.CONTENT));
    }
  }

  private static long admissionEpoch(RunningRuntime runtime) {
    try {
      var field = runtime.commitOps().getClass().getDeclaredField("admissionCommitEpoch");
      field.setAccessible(true); return field.getLong(runtime.commitOps());
    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
  }

  @Test
  void realProjectionProducerNeedsTheFinalFenceAsWellAsTheFileFence() throws Exception {
    try (var f = new Fixture(true, true); var producers = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      f.commitPointer();
      Path file = f.source("private.txt"); var op = f.skipped(file);
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      var projection = new io.justsearch.app.api.indexing.AcceptedProjection("concurrent-source", "document", 1,
          io.justsearch.app.api.indexing.AcceptedProjection.Kind.UPSERT,
          JsonMapper.builder().build().writeValueAsString(Map.of(SchemaFields.PATH, f.root + "/./private.txt",
              SchemaFields.COLLECTION, "documents", SchemaFields.CONTENT, "concurrent owner")));
      var control = new io.justsearch.app.api.indexing.AcceptedProjection("control-source", "document", 1,
          io.justsearch.app.api.indexing.AcceptedProjection.Kind.UPSERT,
          JsonMapper.builder().build().writeValueAsString(Map.of(SchemaFields.COLLECTION, "documents", SchemaFields.CONTENT, "unrelated")));
      f.publisher.underFileMutationFence(() -> {
        var result = producers.submit(() -> f.publisher.applyProjection(control,
            io.justsearch.app.api.indexing.ProjectionDurability.NRT, CallContext.none()));
        assertDoesNotThrow(() -> result.get(2, java.util.concurrent.TimeUnit.SECONDS),
            "SETUP: file fence alone does not exclude real projection writes");
        return true;
      });
      var store = f.configuration("[\"private.txt\"]"); var policy = f.policy(store, false);
      assertTrue(f.prepare(policy).isPresent());
      var producerThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
      var submitted = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
      assertTrue(f.finalDecision(policy, () -> {
        submitted.set(producers.submit(() -> {
          producerThread.set(Thread.currentThread());
          return f.publisher.applyProjection(projection, io.justsearch.app.api.indexing.ProjectionDurability.NRT, CallContext.none());
        }));
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
          while (true) {
            Thread writer = producerThread.get();
            if (writer != null && writer.getState() == Thread.State.WAITING
                && java.util.Arrays.stream(writer.getStackTrace()).anyMatch(frame ->
                    frame.getClassName().equals(WorkerMutationAdmission.class.getName()) && frame.getMethodName().equals("enter"))) break;
            Thread.sleep(5);
          }
        }, "real producer must wait on the precise final admission owner");
        assertFalse(submitted.get().isDone());
        store.publicationLock().writeLock().lock();
        try { return policy.reproveBeforePointer(); }
        finally { store.publicationLock().writeLock().unlock(); }
      }));
      assertDoesNotThrow(() -> submitted.get().get(2, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(projection.sourceId(), f.runtime.documentFieldOps().getDocumentFieldOrThrow(
          projection.indexId(), SchemaFields.PROJECTION_SOURCE_ID));
      assertFalse(f.finalDecision(policy, () -> {
        store.publicationLock().writeLock().lock();
        try { return policy.reproveBeforePointer(); }
        finally { store.publicationLock().writeLock().unlock(); }
      }), "a resumed producer invalidates the earlier permission");
      assertEquals(List.of(op), f.queue.listSwitchBufferOpsStrict());
    }
  }

  @Test
  void ordinaryParentChunksAndLinuxBackslashSiblingRemainEligible() throws Exception {
    try (var f = new Fixture()) {
      Path file = f.source("private.txt"); var old = f.skipped(file);
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      f.indexMetadata(Map.of(SchemaFields.DOC_ID, file.toString(), SchemaFields.PATH, file.toString()));
      f.indexMetadata(Map.of(SchemaFields.DOC_ID, file + "#chunk-1", SchemaFields.PARENT_DOC_ID, file.toString(),
          SchemaFields.PATH, file.toString()));
      var noFile = new io.justsearch.app.api.indexing.AcceptedProjection("no-file", "document", 1,
          io.justsearch.app.api.indexing.AcceptedProjection.Kind.UPSERT,
          JsonMapper.builder().build().writeValueAsString(Map.of(SchemaFields.COLLECTION, "documents", SchemaFields.CONTENT, "unrelated")));
      f.runtime.indexingCoordinator().indexSingle(ProjectionDocumentMapper.toIndexDocument(noFile));
      f.indexMetadata(Map.of(SchemaFields.DOC_ID, "backslash-owner", SchemaFields.PATH, f.root + "\\private.txt",
          SchemaFields.PROJECTION_SOURCE_ID, "backslash-source"));
      assertNotEquals(file, Path.of(f.root + "\\private.txt").normalize(), "SETUP: Linux backslash is a filename character");
      assertTrue(f.readmit(f.policy(f.configuration("[]"), false)), "ordinary serving metadata and unrelated projections are not blanket vetoes");
      var fresh = f.queue.listSwitchBufferOpsStrict().getFirst();
      assertNotEquals(old.revision(), fresh.revision()); assertTrue(fresh.acceptedOrder() > old.acceptedOrder());
    }
  }

  @Test
  void reservedCollectionOwnerStillBlocksReadmissionAndOmission() throws Exception {
    try (var f = new Fixture()) {
      Path file = f.source("private.txt"); var old = f.skipped(file);
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      Path sibling = Files.createDirectory(directory.resolve("sibling"));
      assertTrue(f.registry.watch(sibling.toString(), "sibling").watching());
      var unrelated = f.policy(f.configuration("[\"root/**\"]"), false);
      assertTrue(unrelated.allows(file, f.root, null), "a registered sibling supplies no ancestor exclusion authority");
      assertFalse(unrelated.excludes(file, f.root, null));
      f.runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "reserved-owner", SchemaFields.DOC_UID, "reserved-owner",
          SchemaFields.PATH, file.toString(),
          SchemaFields.COLLECTION, io.justsearch.configuration.InternalCollections.AGENT_HISTORY,
          SchemaFields.CONTENT, "reserved collection fixture")));
      f.runtime.commitOps().maybeRefreshBlocking();
      assertEquals(io.justsearch.configuration.InternalCollections.AGENT_HISTORY,
          f.runtime.documentFieldOps().getDocumentFieldOrThrow("reserved-owner", SchemaFields.COLLECTION));
      assertNull(f.runtime.documentFieldOps().getDocumentFieldOrThrow("reserved-owner", SchemaFields.PROJECTION_SOURCE_ID),
          "SETUP: reserved collection ownership is independent of the repaired marker guard");
      assertFalse(f.readmit(f.policy(f.configuration("[]"), false)));
      var omitted = f.policy(f.configuration("[\"private.txt\"]"), false);
      assertTrue(omitted.freeze(f.queue.listSwitchBufferOpsStrict()));
      assertFalse(omitted.certify(old));
      assertEquals(List.of(old), f.queue.listSwitchBufferOpsStrict());
      assertEquals("DONE", f.queue.watcherReconciliationRow(file.toString()).get("state"));
    }
  }

  @Test
  void immutableReadOnlyServingGenerationPreservesOrdinaryReadmission() throws Exception {
    try (var f = new Fixture()) {
      Path file = f.source("private.txt"); var old = f.skipped(file);
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      Path bluePath = f.base.resolve("g-read-only-serving");
      try (var writer = withTestExecutors(f.runtime.schema().atPath(bluePath)).open()) {
        writer.indexingCoordinator().indexSingle(new IndexDocument(Map.of(SchemaFields.DOC_ID, file.toString(),
            SchemaFields.DOC_UID, file.toString(), SchemaFields.PATH, file.toString(),
            SchemaFields.COLLECTION, "documents", SchemaFields.CONTENT, "ordinary A")));
        writer.commitOps().commitAndTrack();
      }
      try (var blue = withTestExecutors(f.runtime.schema().atPath(bluePath)).openReadOnly()) {
        var publisher = new WorkerIngestService(f.queue, f.loop, mock(WorkerSignalBus.class), IndexingPacing.unthrottled(),
            f.base, f.runtime.openedIndexPath(), f.runtime, blue, null, 0L);
        publisher.setRootWatcherRegistry(f.registry); publisher.setMutationAdmission(f.mutation, f.runtime);
        var store = f.configuration("[]");
        var policy = new NativePolicyReplay(store, store.publicationLock(), f.queue, f.runtime, publisher,
            f.generation, List.of(new ResolvedConfig.FileSource(f.root, null)));
        try (var fence = f.mutation.beginFinalFence(f.runtime, 1_000)) {
          assertNotNull(fence);
          var journal = f.queue.listSwitchBufferOpsStrict();
          assertTrue(policy.readmit(journal, List.of(old)));
        }
        assertEquals("PENDING", f.queue.watcherReconciliationRow(file.toString()).get("state"));
        assertNotEquals(old.revision(), f.queue.listSwitchBufferOpsStrict().getFirst().revision());
      }
    }
  }

  @Test
  void ancestorOperatorExclusionNeverInventsUiOmissionAuthority() throws Exception {
    String previous = System.getProperty("justsearch.ui.exclude_patterns");
    try (var f = new Fixture()) {
      Path file = f.source("private.txt"); var old = f.skipped(file);
      assertTrue(f.registry.watch(directory.toString(), "ancestor").watching());
      assertTrue(f.registry.watch(f.root.toString(), null).watching());
      System.setProperty("justsearch.ui.exclude_patterns", "[\"root/**\"]");
      var store = f.configuration("[]");
      assertTrue(store.currentSnapshot().resolution("justsearch.ui.exclude_patterns").sourceOrdinal() > 300);
      var journal = f.queue.listSwitchBufferOpsStrict(); var row = f.queue.watcherReconciliationRow(file.toString());
      var policy = f.policy(store, false);
      assertTrue(policy.freeze(journal));
      try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
        assertFalse(f.readmit(policy));
        hashes.verify(() -> SourceContentHash.sha256(file), never());
      }
      assertFalse(policy.certify(old));
      assertEquals(journal, f.queue.listSwitchBufferOpsStrict()); assertEquals(row, f.queue.watcherReconciliationRow(file.toString()));
    } finally {
      if (previous == null) System.clearProperty("justsearch.ui.exclude_patterns");
      else System.setProperty("justsearch.ui.exclude_patterns", previous);
    }
  }

  private final class Fixture implements AutoCloseable {
    final Path root = Files.createDirectory(directory.resolve("root"));
    final Path data;
    final String generation;
    final io.justsearch.indexerworker.index.IndexGenerationManager manager;
    final Path base = Files.createDirectories(directory.resolve("index"));
    final SqliteJobQueue queue = new SqliteJobQueue(directory.resolve("queue.db"));
    final RunningRuntime runtime;
    final RunningRuntime serving;
    final RootWatcherRegistry registry = new RootWatcherRegistry();
    final WorkerIngestService publisher;
    final IndexingLoop loop;
    final WorkerMutationAdmission mutation;
    final ReentrantReadWriteLock publication = new ReentrantReadWriteLock();
    final io.justsearch.app.services.worker.WatchedRootsState rootsOwner;
    // Each immutable attempt keeps its own complete declarations; no production getter is added.
    final Map<NativePolicyReplay, List<ResolvedConfig.FileSource>> attemptRoots = new java.util.IdentityHashMap<>();
    Fixture() throws Exception { this(false); }
    Fixture(boolean nativeTransition) throws Exception { this(nativeTransition, false); }
    Fixture(boolean nativeTransition, boolean projectionReplay) throws Exception { this(nativeTransition, projectionReplay, false); }
    Fixture(boolean nativeTransition, boolean projectionReplay, boolean separateServing) throws Exception {
      this(nativeTransition, projectionReplay, separateServing, false);
    }
    Fixture(boolean nativeTransition, boolean projectionReplay, boolean separateServing, boolean declareAncestor) throws Exception {
      data = Files.createDirectories(nativeTransition ? directory.resolve("data") : root.resolve("data"));
      var binding = new java.util.LinkedHashMap<String, Object>();
      binding.put("path", root.toString()); binding.put("collection", null);
      var bindings = new ArrayList<Map<String, Object>>();
      bindings.add(binding);
      if (declareAncestor) bindings.add(Map.of("path", directory.toString(), "collection", "ancestor"));
      Files.writeString(data.resolve("watched_roots.json"), JsonMapper.builder().build().writeValueAsString(
          Map.of("schemaVersion", 1, "roots", bindings)));
      rootsOwner = io.justsearch.app.services.worker.WatchedRootsState.load(data);
      assertTrue(rootsOwner.snapshotBindings().contains(
          new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root, null)),
          "SETUP: actual official watched-root owner is loaded before runtime");
      assertEquals(declareAncestor ? 2 : 1, rootsOwner.snapshotBindings().size());
      manager = nativeTransition ? new io.justsearch.indexerworker.index.IndexGenerationManager(base) : null;
      if (manager != null) {
        manager.initializeOrLoad();
        generation = manager.startMigration("native-policy-component").building_generation();
        manager.updateMigrationState(io.justsearch.indexerworker.index.IndexGenerationManager.MigrationState.SWITCHING);
      } else generation = CANDIDATE;
      queue.open();
      var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
      fields.add(new FieldCatalogDef.FieldDef(SchemaFields.SOURCE_SHA256, "keyword", true, false, List.of(), null, null, false));
      fields.add(new FieldCatalogDef.FieldDef(SchemaFields.PROJECTION_SOURCE_ID, "keyword", true, false, List.of(), null, null, false));
      if (projectionReplay) {
        fields.add(new FieldCatalogDef.FieldDef(SchemaFields.PROJECTION_SOURCE_REVISION, "keyword", true, false, List.of(), null, null, false));
        fields.add(new FieldCatalogDef.FieldDef(SchemaFields.PROJECTION_DIGEST, "keyword", true, false, List.of(), null, null, false));
      }
      runtime = withTestExecutors(IndexSchema.fromCatalog(new FieldCatalogDef("native-policy", fields), () -> Map.of(), ignored -> {})
          .atPath(manager == null ? base.resolve(generation) : manager.resolveGenerationPathStrict(generation))).open();
      runtime.commitOps().stopCommitTimer();
      serving = separateServing ? withTestExecutors(IndexSchema.fromCatalog(new FieldCatalogDef("native-policy", fields),
          () -> Map.of(), ignored -> {}).atPath(base.resolve("g-serving"))).open() : runtime;
      if (serving != runtime) serving.commitOps().stopCommitTimer();
      loop = mock(IndexingLoop.class); var fence = new FileMutationFence();
      when(loop.fileMutationFence()).thenReturn(fence);
      when(loop.withFileMutationFence(any())).thenAnswer(call -> {
        java.util.function.Supplier<?> action = call.getArgument(0);
        return fence.guard(action);
      });
      publisher = new WorkerIngestService(queue, loop, mock(WorkerSignalBus.class), IndexingPacing.unthrottled(),
          base, runtime.openedIndexPath(), runtime, serving, null, 0L);
      publisher.setRootWatcherRegistry(registry);
      publisher.useExclusionRoots(rootsOwner::watchedPaths);
      mutation = new WorkerMutationAdmission(runtime); publisher.setMutationAdmission(mutation, runtime);
      assertNotNull(RootIdentity.capture(root).volume(), "SETUP: existing test volume convention supplies known identity");
      // This owned positive fixture begins with a real known record. Configured-root seeding
      // alone does not create a missing ledger; no live subscription is invented for boot.
      String volume = RootIdentity.capture(root).volume();
      var ledger = RootVolumeLedger.under(base);
      ledger.record(root, volume);
      assertEquals(volume, ledger.recordedFor(root).orElseThrow(), "SETUP: actual ledger owns the held root volume");
      publisher.seedConfiguredRoots(List.of(root));
      assertNull(registry.subscription(root), "SETUP: boot starts before ordinary watch registration");
    }
    void replayProjection(io.justsearch.app.api.indexing.AcceptedProjection projection) throws Exception {
      KnowledgeServerMigrationOps.seedProjectionSource(queue, generation,
          new io.justsearch.app.api.indexing.ProjectionSeedSource() {
            @Override public String sourceId() { return projection.sourceId(); }
            @Override public void enumerate(java.util.function.Consumer<io.justsearch.app.api.indexing.AcceptedProjection> sink) {
              sink.accept(projection);
            }
          });
      var replay = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, runtime, null,
          IndexingPacing.unthrottled(), base, runtime.openedIndexPath(), JsonMapper.builder().build(),
          () -> false, () -> true, LoggerFactory.getLogger(NativePolicyReadmissionCallerTest.class),
          System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5), generation, ignored -> true)
          .withPublisher(publisher).withMigrationRoots(List.of(new ResolvedConfig.FileSource(root, null)));
      try (var fence = mutation.beginFinalFence(runtime, 1_000)) {
        assertNotNull(fence);
        assertTrue(KnowledgeServerMigrationOps.drainSwitchBufferStrict(replay), "SETUP: real accepted projection producer/replay");
      }
    }
    void indexMetadata(Map<String, Object> metadata) throws Exception {
      var fields = new java.util.LinkedHashMap<String, Object>(metadata);
      fields.put(SchemaFields.DOC_UID, metadata.get(SchemaFields.DOC_ID));
      fields.put(SchemaFields.COLLECTION, "documents");
      fields.put(SchemaFields.CONTENT, "owned metadata fixture");
      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
      runtime.commitOps().maybeRefreshBlocking();
    }
    Path source(String name) throws Exception { return Files.writeString(root.resolve(name), "old skipped bytes"); }
    SwitchBufferOp skipped(Path file) throws Exception {
      assertTrue(queue.enqueueAndBufferFileForGeneration(generation, JobQueue.EnqueueEntry.stat(file, ORIGIN).withinRoot(root), null));
      var claim = queue.pollPending(1).getFirst(); assertEquals(file, claim.path());
      assertTrue(queue.markClaimDone(claim, SKIP, null));
      return queue.listSwitchBufferOpsStrict().stream().filter(op -> ("path:" + file).equals(op.key())).findFirst().orElseThrow();
    }
    ConfigStore configuration(String policy) {
      String oldData = System.getProperty("justsearch.data.dir");
      String oldIndex = System.getProperty("justsearch.index.base_path");
      System.setProperty("justsearch.data.dir", data.toString());
      System.setProperty("justsearch.index.base_path", base.toString());
      try {
        // Match public Save's normal resolver sources, including the scoped path properties.
        // UI settings remain ordinal300; a real supported operator property still wins at500.
        var result = new ConfigStore(ResolvedConfig.builder().contributeBaseSources()
            .putSettings("justsearch.ui.exclude_patterns", policy).build(), publication);
        assertEquals(data, result.currentSnapshot().paths().dataDir(), "SETUP: bound protected physical directory");
        assertEquals(base, result.currentSnapshot().paths().indexBasePath(), "SETUP: bound actual index base");
        assertSame(publication, result.publicationLock(), "SETUP: policy and public Save share the fixture publication owner");
        return result;
      } finally {
        if (oldData == null) System.clearProperty("justsearch.data.dir"); else System.setProperty("justsearch.data.dir", oldData);
        if (oldIndex == null) System.clearProperty("justsearch.index.base_path"); else System.setProperty("justsearch.index.base_path", oldIndex);
      }
    }
    NativePolicyReplay policy(ConfigStore owner, boolean boot) {
      return rootsOwner.withForgetBindings(official -> {
        owner.publicationLock().readLock().lock();
        try {
          var snapshot = owner.currentSnapshot();
          var roots = List.copyOf(KnowledgeServerMigrationOps.loadMigrationRootDeclarations(data,
              snapshot.collections(), JsonMapper.builder().build(), official));
          assertTrue(roots.contains(new ResolvedConfig.FileSource(root, null)));
          var policy = boot ? NativePolicyReplay.forCommittedBoot(owner, owner.publicationLock(), queue, runtime, publisher,
              generation, roots, snapshot) : new NativePolicyReplay(owner, owner.publicationLock(), queue,
                  runtime, publisher, generation, roots, snapshot);
          attemptRoots.put(policy, roots);
          assertTrue(policy.matchesAuthority(owner, publication, roots));
          var changed = new ArrayList<>(roots);
          changed.remove(new ResolvedConfig.FileSource(root, null));
          assertFalse(policy.matchesAuthority(owner, publication, changed),
              "a changed declaration set cannot borrow this attempt's exact authority");
          System.out.println("Caller frozen policy roots=" + roots + "; official=" + official
              + "; writable=" + runtime.isAcceptingWrites() + "; snapshotIdentity=" + System.identityHashCode(snapshot));
          return policy;
        } catch (java.io.IOException unknown) { throw new java.io.UncheckedIOException(unknown); }
        finally { owner.publicationLock().readLock().unlock(); }
      });
    }
    void addMixedOrRoot(String route) throws Exception {
      if (route.equals("mixed")) {
        // A real complete empty projection source selects the existing mixed consumer.
        KnowledgeServerMigrationOps.seedProjectionSource(queue, generation,
            new io.justsearch.app.api.indexing.ProjectionSeedSource() {
              @Override public String sourceId() { return "empty-owned-source"; }
              @Override public void enumerate(java.util.function.Consumer<io.justsearch.app.api.indexing.AcceptedProjection> sink) {}
            });
      } else if (route.equals("root")) {
        var debt = new io.justsearch.indexerworker.queue.WatcherRootReconciliation(root.toString(),
            RootIdentity.capture(root).toString(), null);
        assertTrue(queue.putSwitchBuffer(debt.key(), io.justsearch.indexerworker.queue.WatcherRootReconciliation.OPERATION, debt.encode()));
      }
    }
    void commitPointer() throws Exception {
      assertNotNull(manager);
      runtime.commitOps().commitWithBuildState(io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BuildState.COMPLETE,
          io.justsearch.adapters.lucene.runtime.CommitReason.MIGRATION_CUTOVER);
      var state = manager.readStateBestEffort();
      try (var promotion = manager.beginNativePromotion(state.active_generation(), generation)) { promotion.promote(); }
      assertEquals(generation, manager.readStateBestEffort().active_generation());
      var promoted = manager.inspectCurrentLayoutForBoot().orElseThrow();
      assertEquals(generation, promoted.activeGenerationId());
      assertEquals("IDLE", promoted.state().migration_state());
      assertNull(promoted.state().building_generation());
      assertEquals(manager.resolveGenerationPathStrict(generation), runtime.openedIndexPath());
      assertEquals(promoted.activeGenerationPath(), runtime.openedIndexPath());
      assertTrue(runtime.isAcceptingWrites());
      System.out.println("Caller promoted pointer=" + promoted.state() + "; runtime=" + runtime.openedIndexPath());
    }
    io.justsearch.app.services.worker.WatchedRootsState officialRoots() {
      return rootsOwner;
    }
    boolean settle(String route, NativePolicyReplay policy) throws Exception {
      try (var fence = mutation.beginFinalFence(runtime, 1_000)) {
        assertNotNull(fence);
        return route.equals("root") ? KnowledgeServerMigrationOps.drainSwitchBufferStrict(context(policy))
            : KnowledgeServerMigrationOps.settleCommittedNativeFileWitnesses(queue, runtime, generation,
                List.of("empty-owned-source"), LoggerFactory.getLogger(NativePolicyReadmissionCallerTest.class), policy);
      }
    }
    boolean settleWithDecision(String route, ConfigStore store, NativePolicyReplay policy, Runnable before,
        Runnable after, java.util.concurrent.atomic.AtomicInteger decisions) throws Exception {
      var rootsOwner = officialRoots();
      return rootsOwner.withForgetBindings(official -> {
        policy.bindCommittedAcknowledgement(selected -> {
          before.run();
          boolean accepted;
          assertTrue(runtime.isAcceptingWrites());
          assertTrue(publisher.ownsForgetQueue(queue));
          assertSame(publication, store.publicationLock());
          accepted = withOwnership(policy, () -> {
          try (var state = manager.beginForgetNoCandidate(runtime.openedIndexPath())) {
            System.out.println("Caller no-candidate guard acquired for=" + runtime.openedIndexPath());
            store.publicationLock().writeLock().lock();
            try {
              var currentRoots = KnowledgeServerMigrationOps.loadMigrationRootDeclarations(data,
                  store.currentSnapshot().collections(), JsonMapper.builder().build(), official);
              boolean authority = policy.matchesAuthority(store, store.publicationLock(), currentRoots);
              boolean proof = authority && policy.reproveCommittedAcknowledgement(selected);
              System.out.println("Caller final decision official=" + official + "; frozen=" + attemptRoots.get(policy)
                  + "; current=" + currentRoots + "; authority=" + authority + "; proof=" + proof
                  + "; selected=" + selected + "; snapshotIdentity=" + System.identityHashCode(store.currentSnapshot()));
              return authority && proof;
            } finally { store.publicationLock().writeLock().unlock(); }
          } catch (java.io.IOException unknown) { return false; }
          });
          assertFalse(store.publicationLock().isWriteLockedByCurrentThread());
          if (accepted) { decisions.incrementAndGet(); after.run(); }
          return accepted;
        });
        try { return settle(route, policy); }
        catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
      });
    }
    void publicSave(ConfigStore store, List<String> patterns) {
      assertFalse(store.publicationLock().isWriteLockedByCurrentThread(), "public Save must run outside final publication proof");
      try (var saves = java.util.concurrent.Executors.newSingleThreadExecutor()) {
        saves.submit(() -> {
          String oldData = System.getProperty("justsearch.data.dir");
          String oldIndex = System.getProperty("justsearch.index.base_path");
          System.setProperty("justsearch.data.dir", data.toString());
          System.setProperty("justsearch.index.base_path", base.toString());
          try (var operations = new io.justsearch.app.observability.operations.SqliteOperationStore(data.resolve("settings-operations.db"))) {
            var settings = new io.justsearch.app.services.settings.UiSettingsStore(
                io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.READ_WRITE, data.resolve("settings.json"));
            var coordinator = new io.justsearch.app.services.settings.SettingsCommitCoordinator(settings, store, () -> {
              throw new AssertionError("Unexpected component settings restart");
            }, candidate -> io.justsearch.agent.api.registry.OperationResult.success("prepared"));
            var runner = new io.justsearch.app.observability.operations.OperationAttemptRunnerImpl(operations,
                java.time.Clock.systemUTC(), java.util.Set.of(io.justsearch.agent.api.registry.OperationKind.SETTINGS_APPLY,
                    io.justsearch.agent.api.registry.OperationKind.RECONFIGURE), coordinator);
            var json = JsonMapper.builder().build();
            var patch = json.readValue(json.writeValueAsString(Map.of("ui", Map.of("excludePatterns", patterns),
                "witness", settings.inspect().witness(), "operationKey", io.justsearch.app.api.operations.OperationKeys.generate(java.time.Clock.systemUTC()))),
                io.justsearch.app.api.settings.SettingsV2.class);
            var context = io.justsearch.app.services.intent.EngineProvenance.context(
                io.justsearch.core.context.EngineContext.ClientKind.WEBVIEW, "native-policy-settings",
                java.util.Optional.empty(), java.util.Optional.empty(), io.justsearch.agent.api.registry.TransportTag.BUTTON,
                io.justsearch.core.context.EngineContext.Survival.DURABLE,
                io.justsearch.core.context.EngineContext.Urgency.BACKGROUND);
            var result = new io.justsearch.app.services.settings.SettingsServiceImpl(settings, runner).applyPublic(patch, null, context);
            assertTrue(result.response().success());
            assertEquals(io.justsearch.app.api.operations.OperationState.COMPLETE, result.record().state());
            assertEquals(result.record().key(), settings.inspect().witness().lastCommittedOperationKey());
            System.out.println("Caller actual public Save=" + json.writeValueAsString(
                Map.of("response", result.response(), "record", result.record(), "witness", settings.inspect().witness())));
          } finally {
            if (oldData == null) System.clearProperty("justsearch.data.dir"); else System.setProperty("justsearch.data.dir", oldData);
            if (oldIndex == null) System.clearProperty("justsearch.index.base_path"); else System.setProperty("justsearch.index.base_path", oldIndex);
          }
          return null;
        }).get(5, java.util.concurrent.TimeUnit.SECONDS);
      } catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
    }
    // Instrumentation compatibility: the original baseline has no preparation scope. The same
    // assertions still run there, under its existing owner, without adding any repair to it.
    boolean withOwnership(NativePolicyReplay policy, java.util.function.BooleanSupplier decision) {
      try {
        var prepare = NativePolicyReplay.class.getMethod("withFinalOwnership", java.util.function.BooleanSupplier.class);
        return (boolean) prepare.invoke(policy, decision);
      } catch (NoSuchMethodException baseline) { return decision.getAsBoolean(); }
      catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    boolean finalDecision(NativePolicyReplay policy, java.util.function.BooleanSupplier decision) throws Exception {
      try (var fence = mutation.beginFinalFence(runtime, 1_000)) {
        assertNotNull(fence);
        return withOwnership(policy, decision);
      }
    }
    boolean readmit(NativePolicyReplay policy) throws Exception {
      try (var fence = mutation.beginFinalFence(runtime, 1_000)) {
        assertNotNull(fence);
        return KnowledgeServerMigrationOps.readmitNativePolicySkippedAdmissions(context(policy));
      }
    }
    java.util.Optional<KnowledgeServerMigrationOps.StrictReplay> prepare(NativePolicyReplay policy) throws Exception {
      try (var fence = mutation.beginFinalFence(runtime, 1_000)) {
        assertNotNull(fence);
        return KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(context(policy));
      }
    }
    KnowledgeServerMigrationOps.DrainSwitchBufferContext context(NativePolicyReplay policy) {
      var roots = attemptRoots.get(policy);
      assertNotNull(roots, "SETUP: context uses this exact attempt's complete frozen declarations");
      return new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, runtime, null, IndexingPacing.unthrottled(),
          base, runtime.openedIndexPath(), JsonMapper.builder().build(), () -> false, () -> true,
          LoggerFactory.getLogger(NativePolicyReadmissionCallerTest.class), System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5),
          generation, ignored -> true).withPublisher(publisher).withMigrationRoots(roots)
          .withNativePolicy(policy);
    }
    @Override public void close() throws Exception {
      try { if (serving != runtime) serving.close(); }
      finally { try { runtime.close(); } finally { queue.close(); } }
    }
  }
}
