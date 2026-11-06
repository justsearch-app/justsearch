/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.UnwatchRootRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real atomic-write failures; volume lookup alone is injected, never the retry outcome. */
@ExtendWith(NoVolumeIdentityExtension.class)
final class RootVolumeInitializationRetryTest {
  @TempDir Path temp;
  private Path base;
  private RunningRuntime runtime;

  @BeforeEach
  void setUp() throws Exception {
    base = Files.createDirectory(temp.resolve("index"));
    runtime = mock(RunningRuntime.class, RETURNS_DEEP_STUBS);
    sample(List.of());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void refusalRetriesFailedDeclaredInitializationAndConfiguredEnrollment(boolean configured)
      throws Exception {
    Path root = root("declared");
    Path outside = root("configured-outside-watches");
    var service = service();
    if (configured) service.seedRootVolumesAfterUpgrade(List.of(root));
    Path blocked = block(RootVolumeLedger.PENDING_FILE_NAME);
    if (!configured) service.seedRootVolumesAfterUpgrade(List.of(root));
    service.seedConfiguredRoots(List.of(outside));
    for (int retry = 0; retry < 3; retry++) {
      service.rootVolumeRefusal(root.toString(), CallContext.none());
      assertFalse(service.rootOnRecordedVolume(outside.toString()));
      assertTrue(ledger().recorded(outside).isEmpty());
    }
    if (!configured) assertFalse(Files.exists(base.resolve(RootVolumeLedger.FILE_NAME)));
    Files.delete(blocked);

    assertEquals("", service.rootVolumeRefusal(root.toString(), CallContext.none()));
    assertTrue(service.rootOnRecordedVolume(root.toString()));
    assertTrue(service.rootOnRecordedVolume(outside.toString()),
        "configured enrollment recovers even though only the watched root is periodically asked");
    assertFalse(ledger().awaitsSeeding(outside));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void pendingPublishedBeforeLedgerFailureRecoversWithoutLosingEitherRoot(boolean restart) throws Exception {
    Path first = root("first");
    Path sibling = root("sibling");
    Path blocked = block(RootVolumeLedger.FILE_NAME);
    var service = service();
    service.seedRootVolumesAfterUpgrade(List.of(first, sibling));
    assertTrue(ledger().awaitsSeeding(first));
    assertTrue(ledger().awaitsSeeding(sibling));
    Files.delete(blocked);
    if (restart) {
      service = service();
      service.seedRootVolumesAfterUpgrade(List.of(first, sibling));
    }
    assertEquals("", service.rootVolumeRefusal(first.toString(), CallContext.none()));
    assertEquals("", service.rootVolumeRefusal(sibling.toString(), CallContext.none()));
    assertTrue(service.rootOnRecordedVolume(first.toString()));
    assertTrue(service.rootOnRecordedVolume(sibling.toString()));
  }

  @Test
  void removedParentIsNeverReenrolledAndIndependentChildSurvives() throws Exception {
    Path parent = root("parent");
    Path child = Files.createDirectory(parent.resolve("child"));
    Path sibling = root("sibling");
    Path blocked = block(RootVolumeLedger.PENDING_FILE_NAME);
    var service = service();
    service.seedRootVolumesAfterUpgrade(List.of(parent, child, sibling));
    service.seedConfiguredRoots(List.of(parent, child));
    service.unwatchRoot(UnwatchRootRequest.newBuilder().setRootPath(parent.toString()).build(),
        CallContext.none());
    Files.delete(blocked);
    service.rootVolumeRefusal(sibling.toString(), CallContext.none());
    service.rootVolumeRefusal(child.toString(), CallContext.none());
    service.rootVolumeRefusal(parent.toString(), CallContext.none());
    assertTrue(ledger().recorded(parent).isEmpty());
    assertFalse(ledger().awaitsSeeding(parent));
    assertTrue(service.rootOnRecordedVolume(child.toString()));
    assertTrue(service.rootOnRecordedVolume(sibling.toString()));
  }

  @Test
  void siblingRecoversWhileMissingRootRemainsPendingAndArbitrarySyncCannotEnroll() throws Exception {
    Path missing = temp.resolve("missing");
    Path sibling = root("sibling");
    Path unrelated = root("unrelated");
    var service = service();
    Path blocked = block(RootVolumeLedger.PENDING_FILE_NAME);
    service.seedRootVolumesAfterUpgrade(List.of(missing, sibling));
    Files.delete(blocked);
    service.rootVolumeRefusal(sibling.toString(), CallContext.none());
    service.rootVolumeRefusal(unrelated.toString(), CallContext.none());
    assertTrue(service.rootOnRecordedVolume(sibling.toString()));
    assertTrue(ledger().awaitsSeeding(missing));
    assertFalse(ledger().awaitsSeeding(unrelated));
    assertTrue(ledger().recorded(unrelated).isEmpty());
    Files.createDirectory(missing);
    service.rootVolumeRefusal(missing.toString(), CallContext.none());
    assertTrue(service.rootOnRecordedVolume(missing.toString()));
  }

  @Test
  void unlockStillRequiresReadableIndexedEvidence() throws Exception {
    Path root = root("root");
    var service = service();
    Path blocked = block(RootVolumeLedger.PENDING_FILE_NAME);
    service.seedRootVolumesAfterUpgrade(List.of(root));
    Files.delete(blocked);
    when(runtime.readPathOps().search(any(), anyInt(), anySet(), any(), isNull()))
        .thenThrow(new IllegalStateException("unreadable index"));
    assertFalse(service.rootVolumeRefusal(root.toString(), CallContext.none()).isEmpty());
    assertTrue(ledger().awaitsSeeding(root));
    assertTrue(ledger().recorded(root).isEmpty());
    sample(List.of());
    assertEquals("", service.rootVolumeRefusal(root.toString(), CallContext.none()));
  }

  @Test
  void mismatchedSampleStaysPendingUntilHalfItsSizeAndMtimeStampsMatch() throws Exception {
    Path root = root("root");
    Path first = Files.writeString(root.resolve("first.txt"), "original");
    Path second = Files.writeString(root.resolve("second.txt"), "original");
    Path third = Files.writeString(root.resolve("third.txt"), "original");
    Path fourth = Files.writeString(root.resolve("fourth.txt"), "original");
    sample(List.of(hit(first), hit(second), hit(third), hit(fourth)));
    Files.writeString(second, "changed-size");
    Files.writeString(third, "changed-size");
    Files.writeString(fourth, "changed-size");
    var service = service();
    Path blocked = block(RootVolumeLedger.PENDING_FILE_NAME);
    service.seedRootVolumesAfterUpgrade(List.of(root));
    Files.delete(blocked);
    service.rootVolumeRefusal(root.toString(), CallContext.none());
    assertTrue(ledger().recorded(root).isEmpty());
    sample(List.of(hit(first), hit(second), hit(third), hit(fourth)));
    Files.writeString(third, "different-size-again");
    Files.writeString(fourth, "different-size-again");
    assertEquals("", service.rootVolumeRefusal(root.toString(), CallContext.none()));
    assertTrue(service.rootOnRecordedVolume(root.toString()));
  }

  @Test
  void failedPendingCleanupPreservesRecordedVolumeSiblingsAndExplicitFileAuthority() throws Exception {
    Path root = root("root");
    Path sibling = root("sibling");
    Path explicit = root("explicit");
    String volume = RootIdentity.capture(root).volume();
    ledger().beginUpgradeSeeding(List.of(root));
    ledger().record(sibling, volume);
    ledger().recordFileFolder(explicit, volume);
    Path blocked = block(RootVolumeLedger.PENDING_FILE_NAME);
    assertThrows(IOException.class, () -> ledger().seeded(root, volume));
    assertEquals(volume, ledger().recorded(root).orElseThrow());
    Path authorityFile = base.resolve(RootVolumeLedger.FILE_NAME);
    var recordedTime = java.nio.file.attribute.FileTime.fromMillis(1_000L);
    Files.setLastModifiedTime(authorityFile, recordedTime);
    byte[] authority = Files.readAllBytes(authorityFile);
    Files.delete(blocked);
    var service = service();
    service.seedRootVolumesAfterUpgrade(List.of(root, sibling));
    assertFalse(ledger().awaitsSeeding(root));
    assertEquals(recordedTime, Files.getLastModifiedTime(authorityFile));
    assertArrayEquals(authority, Files.readAllBytes(base.resolve(RootVolumeLedger.FILE_NAME)),
        "pending cleanup must not rewrite completed authority or timestamps");
    assertEquals(volume, ledger().recordedFileFolder(explicit).orElseThrow());
    assertEquals(volume, ledger().recorded(sibling).orElseThrow());
    byte[] pending = Files.readAllBytes(base.resolve(RootVolumeLedger.PENDING_FILE_NAME));
    service.rootVolumeRefusal(root.toString(), CallContext.none());
    service().seedRootVolumesAfterUpgrade(List.of(root, sibling));
    assertArrayEquals(pending, Files.readAllBytes(base.resolve(RootVolumeLedger.PENDING_FILE_NAME)));
    assertArrayEquals(authority, Files.readAllBytes(base.resolve(RootVolumeLedger.FILE_NAME)));
  }

  @Test
  void retryNeverOverwritesConflictingAncestorAuthority() throws Exception {
    Path ancestor = root("ancestor");
    Path child = Files.createDirectory(ancestor.resolve("child"));
    ledger().beginUpgradeSeeding(List.of(child));
    ledger().record(ancestor, "windows-volume:bad");
    var service = service();
    service.seedRootVolumesAfterUpgrade(List.of(child));
    service.rootVolumeRefusal(child.toString(), CallContext.none());
    assertTrue(ledger().awaitsSeeding(child));
    assertTrue(ledger().recorded(child).isEmpty());
    assertEquals("windows-volume:bad", ledger().recorded(ancestor).orElseThrow());
  }

  @Test
  void recordedVolumeQueryNeverRetriesInitialization() throws Exception {
    Path root = root("root");
    var service = service();
    Path blocked = block(RootVolumeLedger.PENDING_FILE_NAME);
    service.seedRootVolumesAfterUpgrade(List.of(root));
    Files.delete(blocked);
    assertFalse(service.rootOnRecordedVolume(root.toString()));
    assertFalse(Files.exists(base.resolve(RootVolumeLedger.FILE_NAME)));
    assertFalse(Files.exists(base.resolve(RootVolumeLedger.PENDING_FILE_NAME)));
    service().seedRootVolumesAfterUpgrade(List.of(root));
    assertTrue(service.rootOnRecordedVolume(root.toString()), "a fresh owner resumes the boot input");
  }

  @Test
  void removalOverlappingEvidenceRetryWinsWithoutReenrollment() throws Exception {
    Path root = root("root");
    var service = service();
    Path blocked = block(RootVolumeLedger.PENDING_FILE_NAME);
    service.seedRootVolumesAfterUpgrade(List.of(root));
    Files.delete(blocked);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var reads = runtime.readPathOps();
    doAnswer(ignored -> {
      entered.countDown();
      assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
      return new SearchResult(List.of(), 0L, 0L);
    }).when(reads).search(any(), anyInt(), anySet(), any(), isNull());
    long mark = service.rootRemovalMark();
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var retry = executor.submit(() -> service.rootVolumeRefusal(root.toString(), CallContext.none()));
      assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
      var removal = executor.submit(() -> service.unwatchRoot(UnwatchRootRequest.newBuilder()
          .setRootPath(root.toString()).build(), CallContext.none()));
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      while (!service.rootRemovedSince(root, mark) && System.nanoTime() < deadline) {
        java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
      }
      assertTrue(service.rootRemovedSince(root, mark), "removal must overlap the held evidence check");
      release.countDown();
      retry.get(5, java.util.concurrent.TimeUnit.SECONDS);
      removal.get(5, java.util.concurrent.TimeUnit.SECONDS);
    } finally { release.countDown(); }
    service.rootVolumeRefusal(root.toString(), CallContext.none());
    assertTrue(ledger().recorded(root).isEmpty());
    assertFalse(ledger().awaitsSeeding(root));
  }

  @Test
  void unrelatedAuthorityCreatedDuringFailedInitializationDoesNotStrandStartupRoot() throws Exception {
    Path root = root("root");
    Path explicit = root("explicit");
    var service = service();
    Path blocked = block(RootVolumeLedger.PENDING_FILE_NAME);
    service.seedRootVolumesAfterUpgrade(List.of(root));
    String volume = RootIdentity.capture(explicit).volume();
    ledger().recordFileFolder(explicit, volume);
    byte[] existing = Files.readAllBytes(base.resolve(RootVolumeLedger.FILE_NAME));
    assertTrue(ledger().recorded(root).isEmpty());
    service.rootVolumeRefusal(root.toString(), CallContext.none());
    assertArrayEquals(existing, Files.readAllBytes(base.resolve(RootVolumeLedger.FILE_NAME)));
    Files.delete(blocked);
    assertEquals("", service.rootVolumeRefusal(root.toString(), CallContext.none()));
    assertTrue(service.rootOnRecordedVolume(root.toString()));
    assertEquals(volume, ledger().recordedFileFolder(explicit).orElseThrow());
  }

  @Test
  void configurationReplacementDiscardsUnfinishedEnrollmentOfRemovedRoot() throws Exception {
    Path watched = root("watched");
    Path removed = root("removed-configured");
    Path kept = root("kept-configured");
    var service = service();
    service.seedRootVolumesAfterUpgrade(List.of(watched));
    Path blocked = block(RootVolumeLedger.PENDING_FILE_NAME);
    service.seedConfiguredRoots(List.of(removed, kept));
    service.seedConfiguredRoots(List.of(kept));
    Files.delete(blocked);
    service.rootVolumeRefusal(watched.toString(), CallContext.none());
    assertTrue(service.rootOnRecordedVolume(kept.toString()));
    assertFalse(ledger().awaitsSeeding(removed));
    assertTrue(ledger().recorded(removed).isEmpty());
  }

  @Test
  void uuidlessRecoveryFinishesOnceWithoutInventingAuthority() throws Exception {
    Path root = root("root");
    Path configured = root("configured");
    try (var ignored = NoVolumeIdentityExtension.withVolumeIdentity(false)) {
      var service = service();
      Path blocked = block(RootVolumeLedger.PENDING_FILE_NAME);
      service.seedRootVolumesAfterUpgrade(List.of(root));
      service.seedConfiguredRoots(List.of(configured));
      Files.delete(blocked);
      assertEquals("", service.rootVolumeRefusal(root.toString(), CallContext.none()));
      assertFalse(ledger().awaitsSeeding(root));
      assertFalse(ledger().awaitsSeeding(configured));
      assertTrue(ledger().recorded(root).isEmpty());
      assertTrue(ledger().recorded(configured).isEmpty());
      assertFalse(service.rootOnRecordedVolume(root.toString()));
      byte[] pending = Files.readAllBytes(base.resolve(RootVolumeLedger.PENDING_FILE_NAME));
      service.rootVolumeRefusal(root.toString(), CallContext.none());
      service.rootVolumeRefusal(configured.toString(), CallContext.none());
      assertArrayEquals(pending, Files.readAllBytes(base.resolve(RootVolumeLedger.PENDING_FILE_NAME)));
      // A configured root with no persisted native identity retains its existing restart exception.
      var restarted = service();
      restarted.seedRootVolumesAfterUpgrade(List.of(root));
      restarted.seedConfiguredRoots(List.of(configured));
      assertFalse(ledger().awaitsSeeding(configured));
      assertTrue(ledger().recorded(configured).isEmpty());
    }
  }

  private RootVolumeLedger ledger() { return RootVolumeLedger.under(base); }
  private Path root(String name) throws IOException { return Files.createDirectory(temp.resolve(name)); }
  private Path block(String file) throws IOException {
    return Files.createDirectory(base.resolve(file + ".tmp"));
  }
  private WorkerIngestService service() {
    return new WorkerIngestService(mock(JobQueue.class), null, null, IndexingPacing.unthrottled(),
        base, null, runtime, runtime, null, 0L);
  }
  private void sample(List<SearchHit> hits) throws IOException {
    var reads = runtime.readPathOps();
    doReturn(new SearchResult(hits, hits.size(), 0L)).when(reads)
        .search(any(), anyInt(), anySet(), any(), isNull());
  }
  private static SearchHit hit(Path file) throws IOException {
    return new SearchHit(file.toString(), 1F, Map.of(SchemaFields.PATH, file.toString(),
        SchemaFields.SIZE_BYTES, Long.toString(Files.size(file)),
        SchemaFields.MODIFIED_AT, Long.toString(Files.getLastModifiedTime(file).toMillis())));
  }
}
