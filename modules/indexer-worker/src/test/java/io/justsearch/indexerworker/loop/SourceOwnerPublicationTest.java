/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.app.api.indexing.SourceOrigin;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.*;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.SourceEnvelope;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class SourceOwnerPublicationTest extends LuceneExecutorTestBase {
  private static final List<String> ENVELOPE = List.of("origin_id", "storage_status", "storage_mount", "storage_key", "envelope_version");
  private static final String TEXT = "provenance lexical needle one two three four five six seven eight.\n".repeat(600);
  @TempDir Path temporary;

  @Test
  void unchangedLegacyReadmissionChangesEveryOwnerAfterReopenWithoutExtraction() throws Exception {
    Path root = Files.createDirectories(temporary.resolve("root/nested"));
    Path file = Files.writeString(root.resolve("source.txt"), TEXT);
    var original = origin(SourceOrigin.Kind.IMPORT, root.getParent(), "first", 1, false);
    var replacement = origin(SourceOrigin.Kind.IMPORT, root, "second", 2, true);
    try (var f = new Fixture(file)) {
      f.queue.recordSourceOrigins(List.of(original, replacement));
      f.publish(f.claim(original.root().path(), "first", false));
      var clear = new HashMap<String, Object>(); ENVELOPE.forEach(field -> clear.put(field, null));
      for (String id : rows(f.index).keySet()) f.runtime.indexingCoordinator().updateDocument(id, clear);
      f.commit();
      assertNull(rows(f.index).get(f.id()).get("envelope_version"));
    }
    try (var f = new Fixture(file)) {
      f.publish(f.claim(root, "second", false));
      verify(f.parser, never()).extractArtifact(any());
      assertFamily(f.index, f.id(), replacement.id(), "known", "aa-bb:ext4:/");
      assertEquals("SESSION", approval(replacement.id(), "approval_class"));
      assertInstanceOf(OperationAuthorizationBasis.EphemeralCapsule.class, OperationAuthorizationBasis.decode(approval(replacement.id(), "authorization_basis")));
    }
    try (var f = new Fixture(file)) {
      assertFamily(f.index, f.id(), replacement.id(), "known", "aa-bb:ext4:/");
      assertEquals(1, lexicalHits(f.runtime, "needle"));
      assertEquals(TEXT, f.runtime.documentFieldOps().getDocumentField(f.id(), "content"));
    }
  }

  @Test
  void unchangedReadmissionKeepsTheCompleteSourceProbeBudget() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("root"));
    Path file = Files.writeString(root.resolve("source.txt"), TEXT);
    var owner = origin(SourceOrigin.Kind.IMPORT, root, "import", 1, false);
    try (var f = new Fixture(file)) {
      f.queue.useDeclaredCollectionRoots(List::of);
      f.queue.recordSourceOrigins(List.of(owner)); f.publish(f.claim(root, "import", false));
      var claim = f.claim(root, "import", false);
      var captures = new java.util.concurrent.atomic.AtomicInteger();
      var basic = new java.util.concurrent.atomic.AtomicInteger();
      var ctime = new java.util.concurrent.atomic.AtomicInteger();
      try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS);
          var _ = mockStatic(FileFreshnessSnapshot.class, call -> {
            if (call.getMethod().getName().equals("capture") && file.equals(call.getArgument(0))) captures.incrementAndGet();
            return call.callRealMethod();
          });
          var _ = mockStatic(Files.class, call -> {
            if (call.getArguments().length > 1 && file.equals(call.getArgument(0))) {
              if (call.getMethod().getName().equals("readAttributes") && call.getArgument(1) instanceof Class<?>) basic.incrementAndGet();
              else if (call.getMethod().getName().equals("getAttribute") && "unix:ctime".equals(call.getArgument(1))) ctime.incrementAndGet();
            }
            return call.callRealMethod();
          })) {
        f.publish(claim);
        hashes.verify(() -> SourceContentHash.sha256WithinRoot(file, root), times(1));
      }
      System.out.println("Complete unchanged source probes: captures=" + captures.get() + ", basic=" + basic.get() + ", ctime=" + ctime.get());
      assertTrue(captures.get() <= 3, "base performs three source snapshot captures");
      assertTrue(basic.get() <= 5, "base performs five source basic-attribute probes");
      assertTrue(ctime.get() <= 3, "base performs three source ctime probes on Linux");
      verify(f.parser, times(1)).extractArtifact(file);
    }
  }

  @Test
  void unchangedReadmissionUpdatesTheServingLexicalProjectionWithoutParsing() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("root"));
    Path file = Files.writeString(root.resolve("source.txt"), TEXT);
    var first = origin(SourceOrigin.Kind.IMPORT, root, "first", 1, false);
    var next = origin(SourceOrigin.Kind.IMPORT, root, "next", 2, true);
    try (var active = open(temporary.resolve("active")); var f = new Fixture(file)) {
      f.active = active; f.queue.recordSourceOrigins(List.of(first, next));
      f.publish(f.claim(root, "first", false));
      active.commitOps().commitAndTrack(); active.commitOps().maybeRefreshBlocking();
      int count = rows(temporary.resolve("active")).size();
      f.publish(f.claim(root, "next", false));
      active.commitOps().commitAndTrack(); active.commitOps().maybeRefreshBlocking();
      assertFamily(temporary.resolve("active"), f.id(), next.id(), "known", "aa-bb:ext4:/");
      assertFamily(f.index, f.id(), next.id(), "known", "aa-bb:ext4:/");
      assertEquals(count, rows(temporary.resolve("active")).size());
      assertEquals(TEXT, active.documentFieldOps().getDocumentField(f.id(), "content"));
      assertEquals(1, lexicalHits(active, "needle"));
      verify(f.parser, times(1)).extractArtifact(file);
    }
  }

  @Test
  void deepestWatchedOwnerOverridesImportAndLaterImportReplacesEarlierApproval() throws Exception {
    Path root = Files.createDirectories(temporary.resolve("root/nested"));
    Path file = Files.writeString(root.resolve("source.txt"), TEXT);
    var first = origin(SourceOrigin.Kind.IMPORT, root.getParent(), "first", 1, false);
    var second = origin(SourceOrigin.Kind.IMPORT, root, "second", 2, true);
    var outer = origin(SourceOrigin.Kind.WATCHED, root.getParent(), "outer", 3, false);
    var inner = origin(SourceOrigin.Kind.WATCHED, root, "inner", 4, true);
    try (var f = new Fixture(file)) {
      f.queue.recordSourceOrigins(List.of(first, second, outer, inner));
      f.publish(f.claim(first.root().path(), "first", false));
      f.publish(f.claim(second.root().path(), "second", false));
      assertFamily(f.index, f.id(), second.id(), "known", "aa-bb:ext4:/");
      assertEquals("SESSION", approval(second.id(), "approval_class"));
      f.queue.useDeclaredCollectionRoots(() -> List.of(new RootBinding(outer.root().path(), "documents"),
          new RootBinding(inner.root().path(), "documents")));
      f.publish(f.claim(first.root().path(), "first", false));
      assertFamily(f.index, f.id(), inner.id(), "known", "aa-bb:ext4:/");
      assertEquals("SESSION", approval(inner.id(), "approval_class"));
      verify(f.parser, times(1)).extractArtifact(file);
    }
  }

  @Test
  void deepestWatchedDeclarationWinsEvenForFreshImportPublication() throws Exception {
    Path root = Files.createDirectories(temporary.resolve("root/nested"));
    Path file = Files.writeString(root.resolve("source.txt"), TEXT);
    var imported = origin(SourceOrigin.Kind.IMPORT, root.getParent(), "import", 1, false);
    var outer = origin(SourceOrigin.Kind.WATCHED, root.getParent(), "outer", 2, false);
    var inner = origin(SourceOrigin.Kind.WATCHED, root, "inner", 3, true);
    try (var f = new Fixture(file)) {
      f.queue.recordSourceOrigins(List.of(imported, outer, inner));
      f.queue.useDeclaredCollectionRoots(() -> List.of(new RootBinding(root.getParent(), "documents"), new RootBinding(root, "documents")));
      f.publish(f.claim(root.getParent(), "import", false));
      assertFamily(f.index, f.id(), inner.id(), "known", "aa-bb:ext4:/");
      assertEquals("SESSION", approval(inner.id(), "approval_class"));
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {false, true})
  void supersededClaimReleasedAfterSuccessorCannotRestoreItsOwner(boolean unchanged) throws Exception {
    Path root = Files.createDirectories(temporary.resolve("root"));
    Path file = Files.writeString(root.resolve("source.txt"), TEXT);
    var first = origin(SourceOrigin.Kind.IMPORT, root, "first", 1, false);
    var second = origin(SourceOrigin.Kind.IMPORT, root, "second", 2, true);
    try (var f = new Fixture(file)) {
      f.queue.recordSourceOrigins(List.of(first, second));
      f.publish(f.claim(root, "first", false));
      var old = f.claim(root, "first", !unchanged);
      var paused = f.extractor.extractAll(List.of(old));
      // A second queue session publishes the newer revision while the original issued claim
      // and its captured result remain paused. No sleeps or shared service are involved.
      try (var successor = new SqliteJobQueue(temporary.resolve("jobs.db"))) {
        successor.open();
        successor.enqueueEntries(List.of(JobQueue.EnqueueEntry.ofUnknownSize(file).withinRoot(root).withForceReindex(true)),
            "documents", "second");
        var nextClaims = successor.pollPending(1); assertEquals(1, nextClaims.size());
        var metrics = mock(OperationalMetrics.class);
        var journal = new IngestionOutcomeJournal(successor, metrics, null, () -> false);
        var nextWriter = new JobBatchWriter(new WorkerIngestionAuthority(), f.runtime.indexingCoordinator(),
            mock(WorkerSignalBus.class), mock(EmbeddingProviderLifecycle.class), () -> null, journal,
            successor, f.parser, metrics, new BatchStats(), mock(StaleSnapshotResolver.class), ignored -> {},
            (stage, ms, reason) -> {}, () -> false, () -> false, () -> null, f.fence);
        nextWriter.beginBatch(); controlledStorage(nextWriter);
        for (var ex : f.extractor.extractAll(nextClaims)) nextWriter.write(ex, null);
        f.commit(); journal.drainPending();
        for (var ex : paused) f.writer.write(ex, null);
        f.commit();
      }
      assertFamily(f.index, f.id(), second.id(), "known", "aa-bb:ext4:/");
      assertEquals(0, f.queue.jobStateCounts().processingCount());
      assertEquals("SESSION", approval(second.id(), "approval_class"));
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"within", "outside", "watched", "nested-watched", "alias"})
  void movePreservesIdentityAndReobservesDestinationEnvelopeWithIndependentChildren(String destination) throws Exception {
    Path root = Files.createDirectories(temporary.resolve("import"));
    Path other = Files.createDirectories(temporary.resolve("other"));
    Path nested = Files.createDirectories(root.resolve("nested"));
    Path file = Files.writeString(root.resolve("source.txt"), TEXT);
    var imported = origin(SourceOrigin.Kind.IMPORT, root, "import", 1, false);
    var watched = origin(SourceOrigin.Kind.WATCHED, other, "watched", 2, true);
    var nestedWatch = origin(SourceOrigin.Kind.WATCHED, nested, "nested", 3, true);
    Path alias = root.resolve("alias");
    boolean throughAlias = destination.equals("alias") && io.justsearch.configuration.PlatformPaths.isLinux();
    if (throughAlias) Files.createSymbolicLink(alias, nested);
    Path moved = switch (destination) {
      case "within" -> root.resolve("moved.txt");
      case "alias" -> (throughAlias ? alias : root).resolve("moved.txt");
      case "outside" -> temporary.resolve("exact.txt");
      case "watched" -> other.resolve("moved.txt");
      case "nested-watched" -> nested.resolve("moved.txt");
      default -> throw new AssertionError(destination);
    };
    String expected = switch (destination) {
      case "within", "alias" -> imported.id(); case "outside" -> null;
      case "watched" -> watched.id(); default -> nestedWatch.id();
    };
    try (var f = new Fixture(file)) {
      f.queue.recordSourceOrigins(List.of(imported, watched, nestedWatch));
      f.publish(f.claim(root, "import", false));
      String uid = f.runtime.documentFieldOps().getDocumentField(f.id(), "doc_uid");
      Path independent = Files.writeString(temporary.resolve("independent.txt"), "provenance");
      var own = new HashMap<String, Object>(Map.of("doc_id", "independent-child", "doc_uid", "independent-uid", "parent_doc_id", f.id(),
          "is_chunk", "true", "path", independent.toString(), "chunk_content", "provenance", "collection", "documents"));
      own.put("chunk_start_char", "0"); own.put("chunk_end_char", "10");
      own.put("chunk_parent_content_sha256", io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex(TEXT));
      own.putAll(new SourceEnvelope(imported.id(), SourceEnvelope.StorageStatus.KNOWN, "/own", "own-device:ext4:/").fields());
      f.runtime.indexingCoordinator().indexSingle(new IndexDocument(own)); f.commit();
      var loop = mock(IndexingLoop.class);
      when(loop.withFileMutationFence(any())).thenAnswer(call -> f.fence.guard(call.getArgument(0)));
      var service = new WorkerIngestService(f.queue, loop, null, IndexingPacing.unthrottled(),
          null, null, f.runtime, f.runtime, null, 0L);
      service.setDocumentIdentityStore(f.identities);
      Object owner = new Object(); service.setMutationAdmission(new WorkerMutationAdmission(owner), owner);
      if (throughAlias) assertTrue(service.watchRoot(io.justsearch.ipc.WatchRootRequest.newBuilder()
          .setRootPath(root.toString()).setRootAdded(true).build(), CallContext.none()).getWatching());
      f.queue.useDeclaredCollectionRoots(() -> List.of(new RootBinding(other, "documents"), new RootBinding(nested, "documents")));
      Files.move(file, moved);
      var destinationStorage = controlledSnapshot("cc-dd", "/destination", throughAlias ? nested.toString() : "/");
      try (var observation = mockStatic(StorageObservationSnapshot.class, CALLS_REAL_METHODS)) {
        observation.when(StorageObservationSnapshot::capture).thenReturn(destinationStorage);
        var response = service.updateDocumentPaths(io.justsearch.ipc.UpdatePathsRequest.newBuilder()
            .addMappings(io.justsearch.ipc.PathMapping.newBuilder().setOldPath(f.id()).setNewPath(PathNormalizer.normalizeKey(moved))).build(),
            CallContext.none());
        assertEquals(1, response.getUpdatedCount());
      }
      String newId = PathNormalizer.normalizeKey(moved);
      var committed = rows(f.index);
      assertFalse(committed.containsKey(f.id())); assertTrue(committed.containsKey(newId));
      Map<String, String> parent = committed.get(newId);
      assertEquals(expected, parent.get("origin_id"));
      assertEquals(io.justsearch.configuration.PlatformPaths.isLinux() ? "known" : "not_observed", parent.get("storage_status"));
      assertEquals(io.justsearch.configuration.PlatformPaths.isLinux() ? "cc-dd:ext4:/destination" : null,
          parent.get("storage_key"), "destination storage is re-observed through its real parent");
      int inherited = 0;
      for (var row : committed.entrySet()) {
        if (row.getKey().equals("independent-child")) continue;
        for (String field : ENVELOPE) assertEquals(parent.get(field), row.getValue().get(field), field);
        if (newId.equals(row.getValue().get("parent_doc_id"))) inherited++;
      }
      assertTrue(inherited > 1); assertEquals(uid, parent.get("doc_uid"));
      assertEquals(uid, f.identities.lookup(DocumentIdentityStore.pathHash(newId)).orElseThrow().docUid());
      assertEquals(imported.id(), committed.get("independent-child").get("origin_id"));
      assertEquals("own-device:ext4:/", committed.get("independent-child").get("storage_key"));
      assertEquals(independent.toString(), committed.get("independent-child").get("path"));
      assertEquals(TEXT, parent.get("content")); assertEquals(1, lexicalHits(f.runtime, "needle"));
    }
    try (var _ = open(temporary.resolve("index"))) {
      var committed = rows(temporary.resolve("index"));
      assertEquals(expected, committed.get(PathNormalizer.normalizeKey(moved)).get("origin_id"));
      assertEquals("own-device:ext4:/", committed.get("independent-child").get("storage_key"));
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"single", "parent-first", "chunk-first"})
  void metadataPatchUpdatesTheWholeSourceAndClearsUnknownEvidence(String batch) throws Exception {
    Path root = Files.createDirectory(temporary.resolve("root"));
    Path file = Files.writeString(root.resolve("source.txt"), TEXT);
    var first = origin(SourceOrigin.Kind.IMPORT, root, "first", 1, false);
    var next = origin(SourceOrigin.Kind.IMPORT, root, "next", 2, true);
    try (var f = new Fixture(file)) {
      f.queue.recordSourceOrigins(List.of(first, next)); f.publish(f.claim(root, "first", false));
      var patch = new HashMap<String, Object>();
      patch.put("envelope_version", "1"); patch.put("origin_id", next.id());
      patch.put("storage_status", "unknown"); patch.put("storage_mount", null); patch.put("storage_key", null);
      String chunk = rows(f.index).keySet().stream().filter(id -> !id.equals(f.id())).findFirst().orElseThrow();
      if (batch.equals("single")) assertTrue(f.runtime.indexingCoordinator().updateDocument(f.id(), patch));
      else {
        var updates = new ArrayList<Map.Entry<String, Map<String, Object>>>();
        updates.add(Map.entry(f.id(), patch));
        var child = Map.entry(chunk, Map.<String, Object>of("title", "preserved chunk title"));
        if (batch.equals("parent-first")) updates.add(child); else updates.addFirst(child);
        assertEquals(2, f.runtime.indexingCoordinator().updateDocumentsBatch(updates).updatedCount());
      }
      f.commit();
      if (!batch.equals("single")) assertEquals("preserved chunk title", rows(f.index).get(chunk).get("title"));
      assertFamily(f.index, f.id(), next.id(), "unknown", null);
      for (var row : rows(f.index).values()) assertNull(row.get("storage_mount"));
      assertEquals(TEXT, f.runtime.documentFieldOps().getDocumentField(f.id(), "content"));
      assertEquals(1, lexicalHits(f.runtime, "needle"));
    }
  }

  @Test
  void failureAfterParentFailsPublicationReadsRestrictivelyAndNextAdmissionConverges() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("root"));
    Path file = Files.writeString(root.resolve("source.txt"), TEXT);
    var first = origin(SourceOrigin.Kind.IMPORT, root, "first", 1, false);
    var next = origin(SourceOrigin.Kind.IMPORT, root, "next", 2, true);
    try (var f = new Fixture(file)) {
      f.queue.recordSourceOrigins(List.of(first, next)); f.publish(f.claim(root, "first", false));
      var claim = f.claim(root, "next", false);
      try (var _ = failAtFirstChunk(f.runtime, f.id())) {
        var patch = new SourceEnvelope(next.id(), SourceEnvelope.StorageStatus.KNOWN, "/", "cc-dd:ext4:/new-storage").fields();
        assertThrows(IndexRuntimeIOException.class,
            () -> f.fence.guard(() -> f.runtime.indexingCoordinator().updateDocument(f.id(), patch)),
            "a source-wide patch must reach chunks and propagate their write failure");
        f.writer.beginBatch();
        var observer = JobBatchWriter.class.getDeclaredField("storageSnapshot"); observer.setAccessible(true);
        observer.set(f.writer, controlledSnapshot("cc-dd", "/new-storage"));
        for (var ex : f.extractor.extractAll(List.of(claim))) f.writer.write(ex, null);
        f.commit();
      }
      var committed = rows(f.index);
      assertEquals(next.id(), committed.get(f.id()).get("origin_id"), "reach a real parent write before the injected chunk failure");
      assertTrue(committed.values().stream().anyMatch(row -> first.id().equals(row.get("origin_id"))));
      try (var db = DriverManager.getConnection("jdbc:sqlite:" + temporary.resolve("jobs.db")); var query = db.createStatement();
          var row = query.executeQuery("SELECT state,last_reason_code FROM jobs")) {
        assertTrue(row.next()); assertNotEquals("DONE", row.getString(1)); assertEquals("WRITE_FAILED", row.getString(2));
      }
      var read = assertDoesNotThrow(() -> DocumentFieldOps.class.getMethod("sourceEnvelope", String.class));
      for (String id : committed.keySet()) {
        @SuppressWarnings("unchecked") var decoded = (Optional<SourceEnvelope>) read.invoke(f.runtime.documentFieldOps(), id);
        assertTrue(decoded.isPresent()); assertNull(decoded.orElseThrow().originId(), "disagreement cannot present either owner as known");
        assertEquals(io.justsearch.configuration.PlatformPaths.isLinux() ? SourceEnvelope.StorageStatus.UNKNOWN
            : SourceEnvelope.StorageStatus.NOT_OBSERVED, decoded.orElseThrow().storageStatus());
        assertNull(decoded.orElseThrow().storageKey());
      }
      // Explicit admission clears only this job's retry window, without time-based waits.
      f.publish(f.claim(root, "next", false));
      verify(f.parser, times(1)).extractArtifact(file);
      assertFamily(f.index, f.id(), next.id(), "known", "aa-bb:ext4:/");
    }
    try (var f = new Fixture(file)) { assertFamily(f.index, f.id(), next.id(), "known", "aa-bb:ext4:/"); }
  }

  private static AutoCloseable failAtFirstChunk(RunningRuntime runtime, String parent) throws Exception {
    var sessionMethod = RunningRuntime.class.getDeclaredMethod("session"); sessionMethod.setAccessible(true);
    Object session = sessionMethod.invoke(runtime);
    var snapshotField = session.getClass().getDeclaredField("snapshot"); snapshotField.setAccessible(true);
    Object snapshot = snapshotField.get(session);
    var components = snapshot.getClass().getRecordComponents();
    var values = new Object[components.length]; var types = new Class<?>[components.length];
    for (int i = 0; i < components.length; i++) {
      var accessor = components[i].getAccessor(); accessor.setAccessible(true);
      values[i] = accessor.invoke(snapshot); types[i] = components[i].getType();
      if (values[i] instanceof org.apache.lucene.index.IndexWriter original) {
        var injected = mock(org.apache.lucene.index.IndexWriter.class, org.mockito.AdditionalAnswers.delegatesTo(original));
        doAnswer(call -> {
          Term term = call.getArgument(0);
          if (!parent.equals(term.text())) throw new java.io.IOException("injected after parent before first inheriting chunk");
          return original.updateDocument(term, call.getArgument(1));
        }).when(injected).updateDocument(any(Term.class), any());
        values[i] = injected;
      }
    }
    var constructor = snapshot.getClass().getDeclaredConstructor(types); constructor.setAccessible(true);
    snapshotField.set(session, constructor.newInstance(values));
    return () -> snapshotField.set(session, snapshot);
  }

  private final class Fixture implements AutoCloseable {
    final Path file, index = temporary.resolve("index");
    final SqliteJobQueue queue = new SqliteJobQueue(temporary.resolve("jobs.db"));
    final SqliteDocumentIdentityStore identities;
    final RunningRuntime runtime;
    RunningRuntime active;
    final TimeboxedContentExtractor parser = mock(TimeboxedContentExtractor.class);
    final FileMutationFence fence = new FileMutationFence();
    final IngestionOutcomeJournal journal;
    final JobBatchExtractor extractor;
    final JobBatchWriter writer;
    Fixture(Path file) throws Exception {
      this.file = file; queue.open();
      identities = new SqliteDocumentIdentityStore(temporary.resolve("jobs.db"), 1000);
      runtime = open(index);
      var policy = TikaExtractionPolicy.defaults(); when(parser.extractionPolicy()).thenReturn(policy);
      when(parser.extractArtifact(any())).thenReturn(ExtractionArtifact.full(
          new ContentExtractor.ExtractionResult(TEXT, "source", "text/plain"), policy, "owner-test", false));
      var metrics = mock(OperationalMetrics.class);
      journal = new IngestionOutcomeJournal(queue, metrics, null, () -> false);
      var authority = new WorkerIngestionAuthority();
      var roots = mock(SourcePresence.RootCheck.class);
      when(roots.admits(any())).thenReturn(true); when(roots.admitsFile(any())).thenReturn(true);
      authority.useRootCheck(roots);
      var stale = new StaleSnapshotResolver(authority, mock(StaleSourceHandler.class), journal, queue, parser,
          ignored -> {}, fence); var stats = new BatchStats();
      extractor = new JobBatchExtractor(authority, journal, queue, parser, runtime.documentFieldOps(), runtime.indexCountOps(),
          stats, stale, mock(StaleSourceHandler.class), IndexingPacing.unthrottled(), new AtomicBoolean(true), new HashSet<>(),
          () -> mock(PathResolutionStore.class), () -> identities, (stage, ms, reason) -> {}, () -> false, ignored -> {}, fence);
      writer = new JobBatchWriter(authority, runtime.indexingCoordinator(), mock(WorkerSignalBus.class),
          mock(EmbeddingProviderLifecycle.class), () -> null, journal, queue, parser, metrics, stats, stale,
          ignored -> {}, (stage, ms, reason) -> {}, () -> false, () -> false, () -> active, fence);
    }
    String id() { return PathNormalizer.normalizeKey(file); }
    JobQueue.IndexJob claim(Path root, String operation, boolean force) {
      assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.ofUnknownSize(file).withinRoot(root).withForceReindex(force)),
          "documents", operation));
      var jobs = queue.pollPending(1); assertEquals(1, jobs.size()); return jobs.getFirst();
    }
    void publish(JobQueue.IndexJob claim) throws Exception {
      writer.beginBatch(); controlledStorage(writer);
      for (var ex : extractor.extractAll(List.of(claim))) writer.write(ex, null);
      commit();
    }
    void commit() {
      long delay = writer.prepareRetry(() -> true); writer.finishBatch(System.currentTimeMillis() + delay);
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking(); journal.drainPending();
    }
    @Override public void close() throws Exception { runtime.close(); identities.close(); queue.close(); }
  }

  private RunningRuntime open(Path path) {
    var runtime = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog()).atPath(path)
        .withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer(); return runtime;
  }
  private static SourceOrigin origin(SourceOrigin.Kind kind, Path root, String operation, long time, boolean session) {
    String basis = session ? new OperationAuthorizationBasis.EphemeralCapsule().encode()
        : new OperationAuthorizationBasis.StructuralAuto().encode();
    return SourceOrigin.accepted(kind, SourceOrigin.watchedRoot(root, "documents"),
        new SourceOrigin.Approval(basis, "system_internal", "trusted", "CLI", operation, operation,
            session ? SourceOrigin.ApprovalClass.SESSION : SourceOrigin.ApprovalClass.DURABLE, SourceOrigin.EvidenceSource.GATE), time);
  }
  private String approval(String id, String field) throws Exception {
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + temporary.resolve("jobs.db"));
        var query = db.prepareStatement("SELECT " + field + " FROM source_origin WHERE origin_id=?")) {
      query.setString(1, id); try (var row = query.executeQuery()) { assertTrue(row.next()); return row.getString(1); }
    }
  }
  private static int lexicalHits(RunningRuntime runtime, String token) throws Exception {
    return runtime.indexCountOps().countQueryOrThrow(new TermQuery(new Term("content", token)));
  }
  private static Map<String, Map<String, String>> rows(Path index) throws Exception {
    try (var directory = org.apache.lucene.store.FSDirectory.open(index); var reader = DirectoryReader.open(directory)) {
      var searcher = new IndexSearcher(reader); var result = new HashMap<String, Map<String, String>>();
      for (var hit : searcher.search(new MatchAllDocsQuery(), 1000).scoreDocs) {
        var stored = searcher.storedFields().document(hit.doc); var fields = new HashMap<String, String>();
        for (var field : stored) if (field.stringValue() != null) fields.put(field.name(), field.stringValue());
        result.put(stored.get("doc_id"), fields);
      }
      return result;
    }
  }
  private static void assertFamily(Path index, String id, String origin, String status, String key) throws Exception {
    var committed = rows(index); assertTrue(committed.size() > 2);
    if ("known".equals(status) && !io.justsearch.configuration.PlatformPaths.isLinux()) {
      status = "not_observed"; key = null;
    }
    for (var row : committed.values()) {
      assertEquals(origin, row.get("origin_id"), "parent and each inheriting chunk follow the accepted owner");
      assertEquals("1", row.get("envelope_version")); assertEquals(status, row.get("storage_status"));
      assertEquals(key, row.get("storage_key")); assertEquals("documents", row.get("collection"));
    }
    assertTrue(committed.containsKey(id));
  }
  private static void controlledStorage(JobBatchWriter writer) throws Exception {
    var field = JobBatchWriter.class.getDeclaredField("storageSnapshot"); field.setAccessible(true);
    field.set(writer, controlledSnapshot("aa-bb", "/"));
  }
  private static StorageObservationSnapshot controlledSnapshot(String uuid, String filesystemRoot) throws Exception {
    return controlledSnapshot(uuid, filesystemRoot, "/");
  }
  private static StorageObservationSnapshot controlledSnapshot(String uuid, String filesystemRoot, String mountPoint) throws Exception {
    var type = StorageObservationSnapshot.class;
    if (!io.justsearch.configuration.PlatformPaths.isLinux()) {
      var portable = type.getDeclaredMethod("notObserved"); portable.setAccessible(true);
      return (StorageObservationSnapshot) portable.invoke(null);
    }
    var evidence = Class.forName(type.getName() + "$Evidence");
    Object fixture = java.lang.reflect.Proxy.newProxyInstance(evidence.getClassLoader(), new Class<?>[] {evidence},
        (proxy, method, args) -> switch (method.getName()) {
          case "mountInfo" -> List.of("1 0 8:1 " + filesystemRoot + " " + mountPoint + " rw - ext4 /dev/fixture rw");
          case "uuidDevices" -> Map.of("8:1", uuid); case "sourceDevice" -> "8:1";
          default -> throw new UnsupportedOperationException(method.getName());
        });
    var capture = type.getDeclaredMethod("linux", evidence); capture.setAccessible(true);
    return (StorageObservationSnapshot) capture.invoke(null, fixture);
  }
}
