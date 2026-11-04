/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.ops.BatchStats;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.path.PathResolutionStore;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
final class SourceStoragePublicationTest extends LuceneExecutorTestBase {
  private static final String ORIGIN = "00000000-0000-4000-8000-000000000001";
  @TempDir Path temporary;

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"before-replaced", "before-removed", "before-unreadable", "before-uuid-failure",
      "between-replaced", "between-removed", "between-unreadable", "between-uuid-failure"})
  void topologyChangesNeverCommitStaleKnownStorage(String change) throws Exception {
    publish(temporary, 3, change);
  }

  @Test
  void unchangedTopologyKeepsDiscoveryIndependentOfSourceCount() throws Exception {
    var one = publish(temporary.resolve("one"), 1, null);
    var many = publish(temporary.resolve("many"), 10, null);
    assertEquals(one.uuidReads, many.uuidReads, "UUID inventory must not grow with source count");
    assertEquals(one.sourceReads, many.sourceReads, "device resolution must not grow with source count");
    assertTrue(one.mountReads <= 2, "at most capture plus one comparison per source");
    assertTrue(many.mountReads <= 11, "at most capture plus one comparison per source");
  }

  @ParameterizedTest(name = "{displayName} [{index}] row {arguments}")
  @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
  void selfConsistentDeviceEvidenceDeterminesCommittedStorage(int row) throws Exception {
    publish(temporary, 1, null, row);
  }

  private StorageFixture publish(Path directory, int files, String change) throws Exception {
    return publish(directory, files, change, 0);
  }

  private StorageFixture publish(Path directory, int files, String change, int deviceCase) throws Exception {
    Files.createDirectories(directory);
    boolean chunked = change != null || deviceCase != 0;
    String content = "provenance lexical needle " + (chunked ? "one two three four five six seven eight.\n".repeat(600) : "short source");
    Path targetPath = directory.resolve("target");
    Path activePath = directory.resolve("active");
    var catalog = new JustSearchConfigurationLoader().loadFieldCatalog();
    try (var target = IndexSchema.fromCatalog(catalog).atPath(targetPath).withExecutorRegistrations(testLuceneExecutors()).open();
        var active = IndexSchema.fromCatalog(catalog).atPath(activePath).withExecutorRegistrations(testLuceneExecutors()).open()) {
      target.commitOps().stopCommitTimer();
      active.commitOps().stopCommitTimer();
      JobQueue queue = mock(JobQueue.class, invocation -> {
        if (invocation.getMethod().getName().equals("sourceOriginId")) {
          return Optional.of(ORIGIN);
        }
        return RETURNS_DEFAULTS.answer(invocation);
      });
      when(queue.ownsClaimForPublication(any())).thenReturn(true);
      var parser = mock(TimeboxedContentExtractor.class);
      var policy = TikaExtractionPolicy.defaults();
      when(parser.extractionPolicy()).thenReturn(policy);
      when(parser.extractArtifact(any())).thenReturn(ExtractionArtifact.full(
          new ContentExtractor.ExtractionResult(content, "source", "text/plain"),
          policy, "envelope-publication-test", false));
      var journal = mock(IngestionOutcomeJournal.class);
      var authority = new WorkerIngestionAuthority();
      var roots = mock(SourcePresence.RootCheck.class);
      when(roots.admits(any())).thenReturn(true);
      when(roots.admitsFile(any())).thenReturn(true);
      authority.useRootCheck(roots);
      var fence = new FileMutationFence();
      var stats = new BatchStats();
      var stale = mock(StaleSnapshotResolver.class);
      var identity = mock(DocumentIdentityStore.class);
      when(identity.resolve(anyString(), anyLong()))
          .thenAnswer(invocation -> new DocumentIdentityStore.Identity("hash", invocation.getArgument(0), 1, 1));
      var extractor = new JobBatchExtractor(authority, journal, queue, parser,
          target.documentFieldOps(), target.indexCountOps(), stats, stale, mock(StaleSourceHandler.class),
          IndexingPacing.unthrottled(), new AtomicBoolean(true), new HashSet<>(),
          () -> mock(PathResolutionStore.class), () -> identity, (stage, ms, reason) -> {}, () -> false,
          ignored -> {}, fence);
      var writer = new JobBatchWriter(authority, target.indexingCoordinator(), mock(WorkerSignalBus.class),
          mock(EmbeddingProviderLifecycle.class), () -> null, journal, queue, parser,
          mock(OperationalMetrics.class), stats, stale, ignored -> {}, (stage, ms, reason) -> {},
          () -> false, () -> false, () -> active, fence);

      var paths = new ArrayList<Path>();
      var claims = new ArrayList<JobQueue.IndexJob>();
      for (int i = 0; i < files; i++) {
        Path source = directory.resolve("source-" + i + ".txt");
        Files.writeString(source, content);
        paths.add(source);
        claims.add(new JobQueue.IndexJob(source, "documents", null, null, null, null, false, null, directory));
      }
      var extracted = extractor.extractAll(claims);
      assertEquals(files, extracted.size());
      writer.beginBatch();
      var storage = new StorageFixture();
      storage.deviceCase = deviceCase;
      if (deviceCase != 0) {
        String device = deviceCase == 3 || deviceCase == 4 ? "0:45" : "8:2";
        String type = deviceCase == 3 ? "btrfs" : deviceCase == 4 ? "tmpfs" : "ext4";
        String source = deviceCase == 4 || deviceCase == 5 ? "none" : "/dev/child";
        storage.topology = List.of("1 0 8:1 / / rw - ext4 /dev/ancestor rw",
            "2 1 " + device + " / " + directory + " rw - " + type + " " + source + " rw");
      }
      storage.install(writer);
      // Forbid identity capture/leases while allowing the batch storage observer's dispatch.
      Class<?> rootIdentity = Class.forName("io.justsearch.indexerworker.services.RootIdentity");
      var captureRoot = rootIdentity.getDeclaredMethod("capture", Path.class);
      var holdRoot = rootIdentity.getDeclaredMethod("hold", Path.class);
      captureRoot.setAccessible(true);
      holdRoot.setAccessible(true);
      try (var identities = mockStatic(rootIdentity, CALLS_REAL_METHODS)) {
        for (int i = 0; i < files; i++) {
          if (change != null && i == (change.startsWith("before") ? 0 : 1)) storage.change(change);
          writer.write(extracted.get(i), null);
          // A commit after each source reproduces the verifier's real publication sequence.
          commit(target);
          commit(active);
        }
        identities.verify(() -> captureRoot.invoke(null, any(Path.class)), never());
        identities.verify(() -> holdRoot.invoke(null, any(Path.class)), never());
      }
      for (Path index : List.of(targetPath, activePath)) {
        try (var directoryReader = org.apache.lucene.store.FSDirectory.open(index);
            var reader = DirectoryReader.open(directoryReader)) {
          var searcher = new IndexSearcher(reader);
          for (int i = 0; i < files; i++) {
            String id = PathNormalizer.normalizeKey(paths.get(i));
            var parent = searcher.search(new TermQuery(new Term("doc_id", id)), 1);
            assertEquals(1, parent.totalHits.value(), "real writer publishes each parent");
            var chunks = searcher.search(new TermQuery(new Term("parent_doc_id", id)), 1000);
            assertEquals(chunked, chunks.totalHits.value() > 1);
            var rows = new ArrayList<org.apache.lucene.search.ScoreDoc>(List.of(parent.scoreDocs));
            rows.addAll(List.of(chunks.scoreDocs));
            boolean changed = change != null && (change.startsWith("before") || i > 0);
            var parentFields = searcher.storedFields().document(parent.scoreDocs[0].doc);
            String key = parentFields.get("storage_key");
            assertEquals("1", parentFields.get("envelope_version"), "every committed row requires an envelope");
            if (changed) assertFalse("known".equals(parentFields.get("storage_status"))
                && "aaaa-1111:ext4:/".equals(key),
                "P1b-6: later source must never commit the old mount's known storage");
            if (deviceCase != 0) {
              String expected = switch (deviceCase) {
                case 1, 5 -> "bbbb-2222:ext4:/";
                case 3 -> "cccc-3333:btrfs:/";
                default -> null;
              };
              assertEquals(expected, key,
                  "device case " + deviceCase + ": contradictory or uncertain evidence must never publish another device's UUID");
            } else if (!changed) assertEquals("aaaa-1111:ext4:/", key);
            else if (change.endsWith("replaced")) {
              assertTrue(key == null || "bbbb-2222:ext4:/".equals(key), "replacement evidence or unknown");
            } else assertNull(key, "uncertain storage must be unknown");
            for (var hit : rows) {
              var stored = searcher.storedFields().document(hit.doc);
              assertEquals("1", stored.get("envelope_version"), "every committed row requires an envelope");
              if (changed) {
                assertFalse("known".equals(stored.get("storage_status"))
                    && "aaaa-1111:ext4:/".equals(stored.get("storage_key")),
                    "P1b-6: later source must never commit the old mount's known storage");
              }
              assertEquals(key == null ? "unknown" : "known", stored.get("storage_status"));
              assertEquals(key, stored.get("storage_key"));
              assertEquals(key == null ? null : deviceCase == 0 ? "/" : directory.toString(),
                  stored.get("storage_mount"));
              assertEquals(ORIGIN, stored.get("origin_id"));
            }
          }
        }
      }
      return storage;
    }
  }

  private static final class StorageFixture {
    List<String> topology = List.of("1 0 8:1 / / rw - ext4 /dev/first rw");
    int deviceCase;
    boolean unreadable;
    boolean uuidFailure;
    int mountReads;
    int uuidReads;
    int sourceReads;

    void change(String change) {
      topology = change.endsWith("removed") ? List.of()
          : List.of("2 0 8:2 / / rw - ext4 /dev/replacement rw");
      unreadable = change.endsWith("unreadable");
      uuidFailure = change.endsWith("uuid-failure");
    }

    void install(JobBatchWriter writer) throws Exception {
      Class<?> type;
      try { type = Class.forName("io.justsearch.indexerworker.services.StorageObservationSnapshot"); }
      catch (ClassNotFoundException before) { return; } // Assert old behavior through committed rows.
      var evidence = Class.forName(type.getName() + "$Evidence");
      Object fixture = java.lang.reflect.Proxy.newProxyInstance(evidence.getClassLoader(), new Class<?>[] {evidence},
          (proxy, method, args) -> switch (method.getName()) {
            case "mountInfo" -> {
              mountReads++;
              if (unreadable) throw new java.io.IOException("unreadable mountinfo");
              yield topology;
            }
            case "uuidDevices" -> {
              uuidReads++;
              if (uuidFailure) throw new java.io.IOException("unreadable UUID inventory");
              if (deviceCase != 0) {
                var lease = Class.forName("io.justsearch.indexerworker.services.LinuxRootIdentityLease");
                var unique = lease.getDeclaredMethod("uniqueUuidDevices", Map.class);
                unique.setAccessible(true);
                yield unique.invoke(null, Map.of("8:1", List.of("aaaa-1111"),
                    "8:2", deviceCase == 7 ? List.of("bbbb-2222", "dddd-4444") : List.of("bbbb-2222"),
                    "8:3", List.of("cccc-3333"), "0:45", List.of("eeee-5555")));
              }
              yield Map.of("8:1", "aaaa-1111", "8:2", "bbbb-2222");
            }
            case "sourceDevice" -> {
              sourceReads++;
              var device = args[0].getClass().getDeclaredMethod("device");
              device.setAccessible(true);
              if (deviceCase == 0) yield device.invoke(args[0]);
              var lease = Class.forName("io.justsearch.indexerworker.services.LinuxRootIdentityLease");
              var resolve = lease.getDeclaredMethod("sourceDevice", args[0].getClass());
              resolve.setAccessible(true);
              try (var filesystem = mockStatic(Files.class, CALLS_REAL_METHODS)) {
                filesystem.when(() -> Files.readAttributes(Path.of("/dev/ancestor"), "unix:rdev,mode"))
                    .thenReturn(Map.of("mode", 0060000, "rdev", 2049L));
                if (deviceCase == 6) {
                  filesystem.when(() -> Files.readAttributes(Path.of("/dev/child"), "unix:rdev,mode"))
                      .thenThrow(new java.io.IOException("unreadable source"));
                } else {
                  long rdev = deviceCase == 2 ? 2049L : deviceCase == 3 ? 2051L : 2050L;
                  filesystem.when(() -> Files.readAttributes(Path.of("/dev/child"), "unix:rdev,mode"))
                      .thenReturn(Map.of("mode", 0060000, "rdev", rdev));
                }
                try { yield resolve.invoke(null, args[0]); }
                catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
              }
            }
            default -> throw new UnsupportedOperationException(method.getName());
          });
      var capture = type.getDeclaredMethod("linux", evidence);
      capture.setAccessible(true);
      var field = JobBatchWriter.class.getDeclaredField("storageSnapshot");
      field.setAccessible(true);
      field.set(writer, capture.invoke(null, fixture));
    }
  }
  private static void commit(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
