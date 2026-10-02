package io.justsearch.app.services.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    Files.writeString(archive, first + "invalid historical JSON\n" + conflict);
    var store = new NdjsonAppendStore<>(archive, FeatureSnapshot.class);
    assertThrows(Exception.class, store::initializeLookup);
    assertEquals(java.util.Optional.empty(), store.resolveStableDocId("iid", "path"),
        "readable prefix evidence is insufficient until the whole archive succeeds");
    assertEquals(java.util.Optional.empty(),
        new NdjsonAppendStore<>(archive, FeatureSnapshot.class).resolveStableDocId("iid", "path"));
    store.append(snapshot("new-iid", "new-uid"));
    assertEquals(java.util.Optional.empty(), store.resolveStableDocId("new-iid", "path"),
        "an append cannot expose rows when historical initialization still fails");

    Files.writeString(archive, first + conflict + line(snapshot("complete-iid", "complete-uid")));
    store.initializeLookup();
    assertEquals(java.util.Optional.empty(), store.resolveStableDocId("iid", "path"));
    assertEquals(java.util.Optional.of("complete-uid"), store.resolveStableDocId("complete-iid", "path"));
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
    assertEquals(history, Files.readString(archive));
    assertEquals(java.util.Optional.empty(), observed.resolveStableDocId("iid", "path"));

    Files.writeString(archive, line(snapshot("iid", "uid-a")) + line(snapshot("iid", "uid-b")));
    new NdjsonAppendStore<>(archive, FeatureSnapshot.class, cipher).initializeLookup();
    observed.append(snapshot("new", "new-uid"));
    assertEquals(java.util.Optional.of("new-uid"), observed.resolveStableDocId("new", "path"));
    assertEquals(java.util.Optional.empty(), observed.resolveStableDocId("iid", "path"));
  }

  @Test
  void legacyLookupBackfillRunsOnceAndPreservesHistoricalIdentity(@TempDir Path dir)
      throws Exception {
    Path archive = dir.resolve("feature-snapshots.ndjson");
    Files.writeString(archive, new tools.jackson.databind.ObjectMapper()
        .writeValueAsString(snapshot("legacy-iid", "legacy-uid")) + "\n");
    new NdjsonAppendStore<>(archive, FeatureSnapshot.class).initializeLookup();
    Files.writeString(archive, "invalid historical JSON\n");
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
    Files.writeString(archive, "invalid historical JSON\n");
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
