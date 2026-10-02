package io.justsearch.app.services.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tempdoc 580 §17 — guard tests for the generic feedback NDJSON store (snapshots + dispositions). */
class NdjsonAppendStoreTest {

  @Test
  void failedBackfillHidesThePrefixAndRetriesConflictingHistoryOnTheSameHandle(@TempDir Path dir)
      throws Exception {
    Path archive = dir.resolve("feature-snapshots.ndjson");
    String first = line(snapshot("iid", "uid-a"));
    String conflict = line(snapshot("iid", "uid-b"));
    String history = first + "invalid historical JSON\n" + conflict;
    Files.writeString(archive, history);
    var store = new NdjsonAppendStore<>(archive, FeatureSnapshot.class);
    assertThrows(Exception.class, store::initializeLookup);
    assertEquals(java.util.Optional.empty(), store.resolveStableDocId("iid", "path"),
        "readable prefix evidence is insufficient until the whole archive succeeds");
    assertEquals(java.util.Optional.empty(),
        new NdjsonAppendStore<>(archive, FeatureSnapshot.class).resolveStableDocId("iid", "path"));
    store.append(snapshot("new-iid", "new-uid"));
    assertEquals(java.util.Optional.empty(), store.resolveStableDocId("new-iid", "path"),
        "an append cannot expose rows when historical initialization still fails");

    String captured = Files.readString(archive).substring(history.length());
    assertFalse(captured.isBlank(), "failed initialization must not discard the new archival snapshot");
    Files.writeString(archive, first + conflict + captured + line(snapshot("complete-iid", "complete-uid")));
    store.initializeLookup();
    assertEquals(java.util.Optional.empty(), store.resolveStableDocId("iid", "path"));
    assertEquals(java.util.Optional.of("complete-uid"), store.resolveStableDocId("complete-iid", "path"));
    assertEquals(java.util.Optional.of("new-uid"), store.resolveStableDocId("new-iid", "path"));
  }

  @Test
  void failedGenerationCannotContaminateARepairedArchive(@TempDir Path dir) throws Exception {
    Path archive = dir.resolve("feature-snapshots.ndjson");
    Files.writeString(archive, line(snapshot("iid", "discarded-prefix-uid")) + "invalid JSON\n");
    var store = new NdjsonAppendStore<>(archive, FeatureSnapshot.class);
    assertThrows(Exception.class, store::initializeLookup);
    Files.writeString(archive, line(snapshot("iid", "repaired-uid")));
    store.initializeLookup();
    assertEquals(java.util.Optional.of("repaired-uid"), store.resolveStableDocId("iid", "path"),
        "a retry must build from the complete repaired archive rather than merge a failed prefix");
  }

  @Test
  void interruptedBackfillDoesNotPublishThePrefixAndCanRetry(@TempDir Path dir) throws Exception {
    Path archive = dir.resolve("feature-snapshots.ndjson");
    Files.writeString(archive, line(snapshot("iid", "uid-a")) + line(snapshot("iid", "uid-b"))
        + line(snapshot("complete-iid", "complete-uid")));
    var cipher = org.mockito.Mockito.spy(io.justsearch.agent.api.encryption.StoreCipher.disabled());
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    org.mockito.Mockito.doAnswer(invocation -> {
      if (reads.incrementAndGet() == 2) Thread.currentThread().interrupt();
      return invocation.callRealMethod();
    }).when(cipher).open(org.mockito.ArgumentMatchers.anyString());
    var store = new NdjsonAppendStore<>(archive, FeatureSnapshot.class, cipher);
    try {
      assertThrows(IOException.class, store::initializeLookup);
    } finally {
      Thread.interrupted();
    }
    assertEquals(java.util.Optional.empty(), store.resolveStableDocId("iid", "path"));
    store.initializeLookup();
    assertEquals(java.util.Optional.empty(), store.resolveStableDocId("iid", "path"));
    assertEquals(java.util.Optional.of("complete-uid"), store.resolveStableDocId("complete-iid", "path"));
  }

  private static String line(FeatureSnapshot snapshot) {
    return new tools.jackson.databind.ObjectMapper().writeValueAsString(snapshot) + "\n";
  }

  @Test
  void oneSnapshotResolvesAllHitsBeforePublishingAnIdentity(@TempDir Path dir) throws Exception {
    var cipher = org.mockito.Mockito.spy(io.justsearch.agent.api.encryption.StoreCipher.disabled());
    var store = new NdjsonAppendStore<>(dir.resolve("feature-snapshots.ndjson"), FeatureSnapshot.class, cipher);
    store.initializeLookup();
    var firstRead = new java.util.concurrent.atomic.AtomicBoolean(true);
    var visible = new java.util.concurrent.atomic.AtomicReference<>(java.util.Optional.<String>empty());
    org.mockito.Mockito.doAnswer(invocation -> {
      if (firstRead.compareAndSet(true, false)) visible.set(store.resolveStableDocId("iid", "path"));
      return invocation.callRealMethod();
    }).when(cipher).open(org.mockito.ArgumentMatchers.anyString());
    store.append(new FeatureSnapshot("iid", "q", 1L, List.of(
        new FeatureSnapshot.HitFeatures("uid-a", "path", 1, 1f, 0f, 0f, 1f, null),
        new FeatureSnapshot.HitFeatures("uid-b", "path", 2, 1f, 0f, 0f, 1f, null))));
    assertEquals(java.util.Optional.empty(), store.resolveStableDocId("iid", "path"));
    assertEquals(java.util.Optional.empty(), visible.get(),
        "a duplicate alias must never expose just the first hit's UID while resolving the snapshot");
  }

  @Test
  void observationsCannotBypassTheOwnersBackfillRetryCadence(@TempDir Path dir) throws Exception {
    Path archive = dir.resolve("feature-snapshots.ndjson");
    String history = line(snapshot("iid", "uid-a")) + "invalid historical JSON\n"
        + line(snapshot("iid", "uid-b"));
    Files.writeString(archive, history);
    var cipher = org.mockito.Mockito.spy(io.justsearch.agent.api.encryption.StoreCipher.disabled());
    var observed = NdjsonAppendStore.observedFeatureSnapshots(archive, cipher);
    for (int i = 0; i < 8; i++) observed.append(snapshot("new-" + i, "new-uid"));
    org.mockito.Mockito.verify(cipher, org.mockito.Mockito.never()).open(org.mockito.ArgumentMatchers.anyString());
    String captures = Files.readString(archive).substring(history.length());
    var mapper = new tools.jackson.databind.ObjectMapper();
    var fresh = captures.lines().filter(line -> !line.isBlank())
        .map(mapper::readTree).map(row -> mapper.treeToValue(row.get("record"), FeatureSnapshot.class)).toList();
    assertEquals(8, fresh.size(), "archive capture must remain available while backfill is failing");
    for (int i = 0; i < 8; i++) assertEquals("new-" + i, fresh.get(i).interactionId());
    assertEquals(java.util.Optional.empty(), observed.resolveStableDocId("iid", "path"));
    assertEquals(java.util.Optional.empty(), observed.resolveStableDocId("new-0", "path"));
    assertThrows(Exception.class, observed::initializeLookup);

    Files.writeString(archive, line(snapshot("iid", "uid-a")) + line(snapshot("iid", "uid-b")) + captures);
    new NdjsonAppendStore<>(archive, FeatureSnapshot.class, cipher).initializeLookup();
    assertEquals(java.util.Optional.of("new-uid"), observed.resolveStableDocId("new-0", "path"));
    observed.append(snapshot("new", "new-uid"));
    assertEquals(java.util.Optional.of("new-uid"), observed.resolveStableDocId("new", "path"));
    assertEquals(java.util.Optional.empty(), observed.resolveStableDocId("iid", "path"));
  }

  @Test
  void truncatedHistoryStillKeepsFreshSnapshotsOnSeparateLines(@TempDir Path dir) throws Exception {
    Path archive = dir.resolve("feature-snapshots.ndjson");
    String truncated = line(snapshot("iid", "uid-a")) + "{\"record\":";
    Files.writeString(archive, truncated);
    var store = NdjsonAppendStore.observedFeatureSnapshots(
        archive, io.justsearch.agent.api.encryption.StoreCipher.disabled());
    store.append(snapshot("iid", "uid-b"));
    store.append(snapshot("fresh", "fresh-uid"));
    var mapper = new tools.jackson.databind.ObjectMapper();
    var rows = Files.readString(archive).substring(truncated.length()).lines()
        .filter(line -> !line.isBlank()).map(mapper::readTree)
        .map(row -> mapper.treeToValue(row.get("record"), FeatureSnapshot.class)).toList();
    assertEquals(List.of("iid", "fresh"), rows.stream().map(FeatureSnapshot::interactionId).toList());
    assertThrows(Exception.class, store::initializeLookup);
    assertEquals(java.util.Optional.empty(), store.resolveStableDocId("iid", "path"));
    assertEquals(java.util.Optional.empty(), store.resolveStableDocId("fresh", "path"));
  }

  @Test
  void unwritableLookupDoesNotPreventArchivalCaptureAcrossHandles(@TempDir Path dir) throws Exception {
    Path archive = dir.resolve("feature-snapshots.ndjson");
    Files.writeString(dir.resolve("feature-snapshots.ndjson.lookup"), "directory obstructed");
    var observed = NdjsonAppendStore.observedFeatureSnapshots(
        archive, io.justsearch.agent.api.encryption.StoreCipher.disabled());
    observed.append(snapshot("iid", "uid-a"));
    var reopened = new NdjsonAppendStore<>(archive, FeatureSnapshot.class);
    reopened.append(snapshot("iid", "uid-b"));
    assertEquals(2, reopened.readAll().size());
    assertEquals(java.util.Optional.empty(), reopened.resolveStableDocId("iid", "path"));
    Files.delete(dir.resolve("feature-snapshots.ndjson.lookup"));
    reopened.initializeLookup();
    assertEquals(java.util.Optional.empty(), reopened.resolveStableDocId("iid", "path"));
  }

  @Test
  void projectionFailurePersistsTheArchiveAndInvalidatesStaleEvidenceAcrossReopen(@TempDir Path dir)
      throws Exception {
    Path archive = dir.resolve("feature-snapshots.ndjson");
    var cipher = org.mockito.Mockito.spy(io.justsearch.agent.api.encryption.StoreCipher.disabled());
    var store = new NdjsonAppendStore<>(archive, FeatureSnapshot.class, cipher);
    store.append(snapshot("iid", "uid-a"));
    assertEquals(java.util.Optional.of("uid-a"), store.resolveStableDocId("iid", "path"));
    var failProjection = new java.util.concurrent.atomic.AtomicBoolean(true);
    org.mockito.Mockito.doAnswer(invocation -> {
      String plain = invocation.getArgument(0);
      if (failProjection.get() && plain.contains("\"docUid\"")) {
        throw new IllegalStateException("lookup row cannot be written");
      }
      return invocation.callRealMethod();
    }).when(cipher).seal(org.mockito.ArgumentMatchers.anyString());
    NdjsonAppendStore.observedFeatureSnapshots(archive, cipher).append(snapshot("iid", "uid-b"));
    assertEquals(2, store.readAll().size(), "lookup write failure cannot veto an archival write");
    assertEquals(java.util.Optional.empty(), store.resolveStableDocId("iid", "path"));
    var reopened = new NdjsonAppendStore<>(archive, FeatureSnapshot.class, cipher);
    assertEquals(java.util.Optional.empty(), reopened.resolveStableDocId("iid", "path"),
        "stale readiness must remain invalid across restart even when invalidation writes cannot run");
    failProjection.set(false);
    reopened.initializeLookup();
    assertEquals(java.util.Optional.empty(), reopened.resolveStableDocId("iid", "path"));
    reopened.append(snapshot("fresh", "fresh-uid"));
    assertEquals(java.util.Optional.of("fresh-uid"), reopened.resolveStableDocId("fresh", "path"));
  }

  @Test
  void resolutionCannotReturnARowReadBeforeAConflictingArchiveAppend(@TempDir Path dir) throws Exception {
    Path archive = dir.resolve("feature-snapshots.ndjson");
    var cipher = org.mockito.Mockito.spy(io.justsearch.agent.api.encryption.StoreCipher.disabled());
    var store = new NdjsonAppendStore<>(archive, FeatureSnapshot.class, cipher);
    store.append(snapshot("iid", "uid-a"));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var first = new java.util.concurrent.atomic.AtomicBoolean(true);
    org.mockito.Mockito.doAnswer(invocation -> {
      String row = invocation.getArgument(0);
      if (row.contains("\"docUid\"") && first.compareAndSet(true, false)) {
        entered.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS));
      }
      return invocation.callRealMethod();
    }).when(cipher).open(org.mockito.ArgumentMatchers.anyString());
    var resolution = new FutureTask<>(() -> store.resolveStableDocId("iid", "path"));
    Thread.ofPlatform().daemon().name("feedback-resolution-test").start(resolution);
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      NdjsonAppendStore.observedFeatureSnapshots(archive, cipher).append(snapshot("iid", "uid-b"));
    } finally {
      release.countDown();
    }
    assertEquals(java.util.Optional.empty(), resolution.get(2, TimeUnit.SECONDS),
        "a row opened before a concurrent conflicting append cannot survive the coverage change");
    assertEquals(2, store.readAll().size());
  }

  @Test
  void backfillAndArchivalAppendShareOneWriteBoundaryAcrossHandles(@TempDir Path dir) throws Exception {
    Path archive = dir.resolve("feature-snapshots.ndjson");
    String history = line(snapshot("historical", "historical-uid"));
    Files.writeString(archive, history);
    var cipher = org.mockito.Mockito.spy(io.justsearch.agent.api.encryption.StoreCipher.disabled());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var sealed = new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(invocation -> {
      entered.countDown();
      assertTrue(release.await(5, TimeUnit.SECONDS));
      return invocation.callRealMethod();
    }).when(cipher).open(org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.doAnswer(invocation -> {
      String plain = invocation.getArgument(0);
      if (plain.contains("\"record\"") && plain.contains("fresh-uid")) sealed.countDown();
      return invocation.callRealMethod();
    }).when(cipher).seal(org.mockito.ArgumentMatchers.anyString());
    var backfill = new NdjsonAppendStore<>(archive, FeatureSnapshot.class, cipher);
    var capture = NdjsonAppendStore.observedFeatureSnapshots(archive, cipher);
    var initialization = new FutureTask<Void>(() -> { backfill.initializeLookup(); return null; });
    var append = new FutureTask<Void>(() -> { capture.append(snapshot("fresh", "fresh-uid")); return null; });
    Thread.ofPlatform().daemon().name("feedback-backfill-test").start(initialization);
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      Thread.ofPlatform().daemon().name("feedback-append-test").start(append);
      assertTrue(sealed.await(2, TimeUnit.SECONDS));
      assertThrows(TimeoutException.class, () -> append.get(100, TimeUnit.MILLISECONDS));
      assertEquals(history, Files.readString(archive), "append cannot bypass an in-flight backfill");
    } finally {
      release.countDown();
    }
    initialization.get(2, TimeUnit.SECONDS);
    append.get(2, TimeUnit.SECONDS);
    assertEquals(2, backfill.readAll().size());
    assertEquals(java.util.Optional.of("fresh-uid"), backfill.resolveStableDocId("fresh", "path"));
  }

  @Test
  void legacyLookupBackfillRunsOnceAndPreservesHistoricalIdentity(@TempDir Path dir)
      throws Exception {
    Path archive = dir.resolve("feature-snapshots.ndjson");
    Files.writeString(archive, new tools.jackson.databind.ObjectMapper()
        .writeValueAsString(snapshot("legacy-iid", "legacy-uid")) + "\n");
    new NdjsonAppendStore<>(archive, FeatureSnapshot.class).initializeLookup();
    corruptArchiveWithoutChangingLength(archive);
    assertThrows(Exception.class, new NdjsonAppendStore<>(archive, FeatureSnapshot.class)::readAll);
    var reopened = new NdjsonAppendStore<>(archive, FeatureSnapshot.class);
    // A completed persistent backfill must not open the archive again, even after restart.
    reopened.initializeLookup();
    assertEquals(java.util.Optional.of("legacy-uid"), reopened.resolveStableDocId("legacy-iid", "path"));
  }

  @Test
  void keyedLookupSurvivesReopenWithoutOpeningTheArchiveOrWaitingOnItsMonitor(@TempDir Path dir)
      throws Exception {
    Path archive = dir.resolve("feature-snapshots.ndjson");
    var cipher = org.mockito.Mockito.spy(io.justsearch.agent.api.encryption.StoreCipher.disabled());
    var store = new NdjsonAppendStore<>(archive, FeatureSnapshot.class, cipher);
    for (int i = 0; i < 50; i++) store.append(snapshot("iid-" + i, "uid-" + i));
    // Reading the archive cannot produce a row now. The keyed projection must stand alone.
    corruptArchiveWithoutChangingLength(archive);
    assertThrows(Exception.class, store::readAll);
    var reopened = new NdjsonAppendStore<>(archive, FeatureSnapshot.class, cipher);
    org.mockito.Mockito.clearInvocations(cipher);
    synchronized (reopened) {
      org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () ->
          assertEquals(java.util.Optional.of("uid-49"), reopened.resolveStableDocId("iid-49", "path")));
    }
    org.mockito.Mockito.verify(cipher, org.mockito.Mockito.times(1)).open(org.mockito.ArgumentMatchers.anyString());
    assertEquals(java.util.Optional.empty(), reopened.resolveStableDocId("missing", "path"));
  }

  @Test
  void keyedLookupKeepsConflictingEvidenceUnresolvedAcrossHandles(@TempDir Path dir) throws Exception {
    Path archive = dir.resolve("feature-snapshots.ndjson");
    var first = new NdjsonAppendStore<>(archive, FeatureSnapshot.class);
    var second = new NdjsonAppendStore<>(archive, FeatureSnapshot.class);
    first.append(snapshot("iid", "uid-1"));
    second.append(snapshot("iid", "uid-2"));
    first.append(snapshot("iid", "uid-1"));
    assertEquals(java.util.Optional.empty(), second.resolveStableDocId("iid", "path"));
  }

  @Test
  void keyedLookupUsesTheArchiveCipherAndFailsClosedWhileLocked(@TempDir Path dir) throws Exception {
    var locked = new java.util.concurrent.atomic.AtomicBoolean();
    var cipher = new io.justsearch.agent.api.encryption.StoreCipher(
        new io.justsearch.agent.api.encryption.DataKeyState() {
          public boolean enabled() { return true; }
          public boolean locked() { return locked.get(); }
          public byte[] dek() { return new byte[32]; }
        });
    var store = new NdjsonAppendStore<>(dir.resolve("feature-snapshots.ndjson"), FeatureSnapshot.class, cipher);
    store.append(snapshot("iid", "uid"));
    try (var files = Files.walk(dir.resolve("feature-snapshots.ndjson.lookup"))) {
      var rows = files.filter(file -> file.getFileName().toString().endsWith(".json")).toList();
      assertEquals(1, rows.size());
      for (Path file : rows) {
        org.junit.jupiter.api.Assertions.assertTrue(cipher.isSealed(Files.readString(file)));
      }
    }
    assertEquals(java.util.Optional.of("uid"), store.resolveStableDocId("iid", "path"));
    locked.set(true);
    assertEquals(java.util.Optional.empty(), store.resolveStableDocId("iid", "path"));
  }

  private static FeatureSnapshot snapshot(String interaction, String uid) {
    return new FeatureSnapshot(interaction, "q", 1L,
        List.of(new FeatureSnapshot.HitFeatures(uid, "path", 1, 1f, 0f, 0f, 1f, null)));
  }

  static void corruptArchiveWithoutChangingLength(Path archive) throws IOException {
    // Keep the coverage watermark valid while proving identity resolution never parses the log.
    Files.writeString(archive, "x".repeat(Math.toIntExact(Files.size(archive)) - 1) + "\n");
  }

  @Test
  void featureSnapshot_roundtripsWithNullableTokenCount(@TempDir Path dir) throws IOException {
    var store =
        new NdjsonAppendStore<>(dir.resolve("feature-snapshots.ndjson"), FeatureSnapshot.class);
    store.append(
        new FeatureSnapshot(
            "iid-1",
            "the query",
            123L,
            List.of(
                new FeatureSnapshot.HitFeatures(
                    "uid-1", "C:/docs/d1", 1, 0.9f, 0.8f, 0.7f, 0.85f, 1024L),
                new FeatureSnapshot.HitFeatures("d2", 2, 0.5f, 0.4f, 0.3f, 0.45f, null))));

    List<FeatureSnapshot> all = store.readAll();
    assertEquals(1, all.size());
    FeatureSnapshot got = all.get(0);
    assertEquals("iid-1", got.interactionId());
    assertEquals(2, got.hits().size());
    assertEquals("uid-1", got.hits().get(0).docId());
    assertEquals("C:/docs/d1", got.hits().get(0).sourceDocId());
    assertEquals(1024L, got.hits().get(0).parentTokenCount());
    assertNull(got.hits().get(1).sourceDocId(), "legacy path rows have no source alias");
    assertNull(got.hits().get(1).parentTokenCount(), "absent token count must round-trip as null");
  }

  @Test
  void featureSnapshot_roundtripsTheContentRevisionAndReadsALegacyRowAsUnknown(@TempDir Path dir)
      throws IOException {
    // Tempdoc 931 §C.6 — a row written before contentRevision existed must deserialize with null,
    // not fail and not default to something LabelProjection would read as a mismatch. The legacy
    // line is written VERBATIM rather than round-tripped, so this is the real on-disk shape.
    Path file = dir.resolve("feature-snapshots.ndjson");
    Files.writeString(
        file,
        "{\"schemaVersion\":1,\"record\":{\"interactionId\":\"iid-legacy\",\"query\":\"q\","
            + "\"occurredAtMs\":1,\"hits\":[{\"docId\":\"uid-legacy\",\"sourceDocId\":\"C:/a.md\","
            + "\"rank\":1,\"sparse\":0.1,\"dense\":0.2,\"splade\":0.3,\"fused\":0.4,"
            + "\"parentTokenCount\":null}]}}\n");

    var store = new NdjsonAppendStore<>(file, FeatureSnapshot.class);
    store.append(
        new FeatureSnapshot(
            "iid-current",
            "q",
            2L,
            List.of(
                new FeatureSnapshot.HitFeatures(
                    "uid-current", "C:/b.md", 1, 0.9f, 0.8f, 0.7f, 0.85f, 10L, "rev-b"))));

    List<FeatureSnapshot> all = store.readAll();
    assertEquals(2, all.size());
    assertNull(
        all.get(0).hits().get(0).contentRevision(),
        "a pre-931 row is UNKNOWN, never a revision mismatch");
    assertEquals("uid-legacy", all.get(0).hits().get(0).docId());
    assertEquals("rev-b", all.get(1).hits().get(0).contentRevision());
  }

  @Test
  void resultDisposition_roundtrips(@TempDir Path dir) throws IOException {
    var store =
        new NdjsonAppendStore<>(dir.resolve("result-dispositions.ndjson"), ResultDisposition.class);
    store.append(
        new ResultDisposition(
            "iid-1",
            "d1",
            ResultDisposition.Kind.CITED,
            ResultDisposition.Contributor.AGENT_CITATION,
            42L));
    store.append(
        new ResultDisposition(
            "iid-1",
            "d2",
            ResultDisposition.Kind.REFINED_WITHOUT_OPENING,
            ResultDisposition.Contributor.SEARCH_INTERACTION,
            43L));

    List<ResultDisposition> all = store.readAll();
    assertEquals(2, all.size());
    assertEquals(ResultDisposition.Kind.CITED, all.get(0).kind());
    assertEquals(ResultDisposition.Contributor.AGENT_CITATION, all.get(0).contributor());
    assertEquals(ResultDisposition.Kind.REFINED_WITHOUT_OPENING, all.get(1).kind());
  }

  @Test
  void readAll_emptyWhenNothingWritten(@TempDir Path dir) throws IOException {
    assertEquals(
        0,
        new NdjsonAppendStore<>(dir.resolve("x.ndjson"), FeatureSnapshot.class).readAll().size());
  }

  @Test
  void legacyUnversionedRecordRemainsReadable(@TempDir Path dir) throws IOException {
    Path path = dir.resolve("feature-snapshots.ndjson");
    Files.writeString(
        path,
        "{\"interactionId\":\"iid-legacy\",\"query\":\"q\",\"occurredAtMs\":1,"
            + "\"hits\":[{\"docId\":\"C:/legacy.md\",\"rank\":1,\"sparse\":0.1,"
            + "\"dense\":0.2,\"splade\":0.3,\"fused\":0.4,\"parentTokenCount\":null}]}\n");

    var store = new NdjsonAppendStore<>(path, FeatureSnapshot.class);
    FeatureSnapshot legacy = store.readAll().getFirst();
    assertEquals("iid-legacy", legacy.interactionId());
    assertEquals("C:/legacy.md", legacy.hits().getFirst().docId());
    assertNull(legacy.hits().getFirst().sourceDocId());
  }

  @Test
  void versionedPrePhase2HitWithoutSourceAliasRemainsReadable(@TempDir Path dir)
      throws IOException {
    Path path = dir.resolve("feature-snapshots.ndjson");
    Files.writeString(
        path,
        "{\"schemaVersion\":1,\"record\":{\"interactionId\":\"iid-old\",\"query\":\"q\","
            + "\"occurredAtMs\":1,\"hits\":[{\"docId\":\"C:/old.md\",\"rank\":1,"
            + "\"sparse\":0.1,\"dense\":0.2,\"splade\":0.3,\"fused\":0.4,"
            + "\"parentTokenCount\":null}]}}\n");

    FeatureSnapshot legacy =
        new NdjsonAppendStore<>(path, FeatureSnapshot.class).readAll().getFirst();

    assertEquals("C:/old.md", legacy.hits().getFirst().docId());
    assertNull(legacy.hits().getFirst().sourceDocId());
  }

  @Test
  void futureRecordVersionIsRefused(@TempDir Path dir) throws IOException {
    Path path = dir.resolve("feature-snapshots.ndjson");
    Files.writeString(path, "{\"schemaVersion\":2,\"record\":{}}\n");
    var store = new NdjsonAppendStore<>(path, FeatureSnapshot.class);

    assertThrows(
        io.justsearch.configuration.persistence.UnsupportedStoreVersionException.class,
        store::readAll);
  }
}
