/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Boot identity import guard")
final class DocumentIdentityBootImportGuardTest {

  /** In-memory identity store: enough of the contract to observe the guard's decisions. */
  private static final class RecordingStore implements DocumentIdentityStore {
    private final Map<String, String> identities = new HashMap<>();
    private final Map<String, ImportRecord> imports = new HashMap<>();
    private final List<Integer> batchSizes = new ArrayList<>();

    @Override
    public Identity resolve(String pathHash, long nowMs) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Identity importExisting(String pathHash, String docUid, long nowMs) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int importExisting(Collection<ImportedIdentity> rows, long nowMs) {
      batchSizes.add(rows.size());
      int inserted = 0;
      for (ImportedIdentity row : rows) {
        if (identities.putIfAbsent(row.pathHash(), row.docUid()) == null) {
          inserted++;
        }
      }
      return inserted;
    }

    @Override
    public RekeyResult rekey(String oldPathHash, String newPathHash, long nowMs) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void markDeleted(String pathHash, long nowMs) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Identity> lookup(String pathHash) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long identityCount() {
      return identities.size();
    }

    @Override
    public boolean hasImportRecord(String generationId) {
      return imports.containsKey(generationId);
    }

    @Override
    public void recordImport(ImportRecord record) {
      imports.put(record.generationId(), record);
    }
  }

  /** Counts scans and replays a fixed corpus in the batch size the import asked for. */
  private static final class CountingScanner
      implements DocumentIdentityBootImport.ParentIdentityScanner {
    private final List<DocumentFieldOps.StoredDocumentIdentity> corpus;
    private final long skipped;
    private int scans;
    private int observedBatchSize;

    CountingScanner(List<DocumentFieldOps.StoredDocumentIdentity> corpus, long skipped) {
      this.corpus = corpus;
      this.skipped = skipped;
    }

    @Override
    public DocumentFieldOps.ParentIdentityScanSummary scan(
        int batchSize,
        java.util.function.Consumer<List<DocumentFieldOps.StoredDocumentIdentity>> consumer) {
      scans++;
      observedBatchSize = batchSize;
      for (int from = 0; from < corpus.size(); from += batchSize) {
        consumer.accept(corpus.subList(from, Math.min(from + batchSize, corpus.size())));
      }
      return new DocumentFieldOps.ParentIdentityScanSummary(
          corpus.size() + skipped, corpus.size(), skipped);
    }
  }

  private static List<DocumentFieldOps.StoredDocumentIdentity> parents(int count) {
    List<DocumentFieldOps.StoredDocumentIdentity> corpus = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      corpus.add(new DocumentFieldOps.StoredDocumentIdentity("parent-" + i, "uid-" + i));
    }
    return corpus;
  }

  @Test
  @DisplayName("a second boot over the same generation does not scan the index again")
  void secondBootOverTheSameGenerationSkipsTheScan() {
    RecordingStore store = new RecordingStore();
    CountingScanner scanner = new CountingScanner(parents(3), 0);

    var first = DocumentIdentityBootImport.run(scanner, store, "g-1", 100L);
    var second = DocumentIdentityBootImport.run(scanner, store, "g-1", 200L);

    assertTrue(first.scanned());
    assertEquals(3, first.parentsImported());
    assertFalse(second.scanned(), "the recorded import row must suppress the second scan");
    assertEquals(1, scanner.scans);
  }

  @Test
  @DisplayName("a generation with no import row is scanned even when the store holds identities")
  void aDifferentGenerationIsStillScanned() {
    RecordingStore store = new RecordingStore();
    CountingScanner scanner = new CountingScanner(parents(3), 0);

    DocumentIdentityBootImport.run(scanner, store, "g-1", 100L);
    var promoted = DocumentIdentityBootImport.run(scanner, store, "g-2", 200L);

    assertTrue(promoted.scanned());
    assertEquals(0, promoted.parentsImported(), "already-known identities stay authoritative");
    assertEquals(2, scanner.scans);
  }

  @Test
  @DisplayName("a wiped identity table re-imports even though the import row survives")
  void wipedIdentityTableIsReimported() {
    RecordingStore store = new RecordingStore();
    CountingScanner scanner = new CountingScanner(parents(3), 0);

    DocumentIdentityBootImport.run(scanner, store, "g-1", 100L);
    store.identities.clear();
    var reimport = DocumentIdentityBootImport.run(scanner, store, "g-1", 200L);

    assertTrue(reimport.scanned());
    assertEquals(3, reimport.parentsImported());
    assertEquals(2, scanner.scans);
  }

  @Test
  @DisplayName("parents the scan skipped are recorded on the import row")
  void skippedParentsAreRecorded() {
    RecordingStore store = new RecordingStore();
    CountingScanner scanner = new CountingScanner(parents(2), 1);

    var result = DocumentIdentityBootImport.run(scanner, store, "g-1", 100L);

    assertEquals(1, result.parentsSkipped());
    DocumentIdentityStore.ImportRecord record = store.imports.get("g-1");
    assertEquals(3, record.parentsSeen());
    assertEquals(2, record.parentsImported());
    assertEquals(1, record.parentsSkipped());
    assertEquals(100L, record.importedAtMs());
  }

  @Test
  @DisplayName("no-file projection parents do not enter the file path identity store")
  void projectionParentsDoNotBecomePathIdentities() {
    RecordingStore store = new RecordingStore();
    String projectionId = new AcceptedProjection(
        "fixture-memory", "updated", 1, AcceptedProjection.Kind.UPSERT, "{}").indexId();
    CountingScanner scanner = new CountingScanner(List.of(
        new DocumentFieldOps.StoredDocumentIdentity("parent-1", "uid-1"),
        new DocumentFieldOps.StoredDocumentIdentity(projectionId, "projection-uid"),
        new DocumentFieldOps.StoredDocumentIdentity("parent-2", "uid-2")), 0);

    var result = DocumentIdentityBootImport.run(scanner, store, "g-projection", 100L);

    assertEquals(2, result.parentsSeen());
    assertEquals(2, result.parentsImported());
    assertEquals(0, result.parentsSkipped());
    assertEquals(2, store.identities.size());
    assertEquals(2, store.imports.get("g-projection").parentsSeen());
  }

  @Test
  @DisplayName("1001 parents reach the store as two transactions of at most 1000")
  void importsAreBatchedAtOneThousand() {
    RecordingStore store = new RecordingStore();
    CountingScanner scanner = new CountingScanner(parents(1001), 0);

    var result = DocumentIdentityBootImport.run(scanner, store, "g-1", 100L);

    assertEquals(1000, scanner.observedBatchSize);
    assertEquals(List.of(1000, 1), store.batchSizes);
    assertEquals(1001, result.parentsImported());
    assertEquals(2, result.batches());
  }
}
