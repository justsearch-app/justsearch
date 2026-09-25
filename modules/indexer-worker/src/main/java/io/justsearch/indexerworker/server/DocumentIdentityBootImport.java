/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seeds the {@code document_identity} table from the serving index generation.
 *
 * <p><b>Why this is a seeding step and not a per-boot pass.</b> After the first import, every parent
 * written through admission has already resolved its uid through the store, and a Green built by
 * Blue/Green migration re-ingests through that same store rather than copying Blue's stored fields.
 * The only states the scan can still repair are an index that predates the store (pre-V11) and a
 * wiped or restored {@code jobs.db}. Both are visible without walking the index: the identity table
 * is empty, or {@code document_identity_import} carries no row for the serving generation. Running
 * it unconditionally cost a full {@code maxDoc()} walk of every generation on the boot critical
 * path, before the switch buffer could drain.
 */
final class DocumentIdentityBootImport {
  private static final Logger log = LoggerFactory.getLogger(DocumentIdentityBootImport.class);

  /** Parents per SQLite transaction. Bounds peak heap and keeps each commit short. */
  static final int BATCH_SIZE = 1000;

  private DocumentIdentityBootImport() {}

  /** The scan the import drives, narrowed so a test can supply one without a Lucene runtime. */
  @FunctionalInterface
  interface ParentIdentityScanner {
    DocumentFieldOps.ParentIdentityScanSummary scan(
        int batchSize, Consumer<List<DocumentFieldOps.StoredDocumentIdentity>> batchConsumer);
  }

  /** What one boot did: either it skipped, or it scanned and recorded these counts. */
  record Result(
      boolean scanned, long parentsSeen, long parentsImported, long parentsSkipped, int batches) {
    static Result skipped() {
      return new Result(false, 0, 0, 0, 0);
    }
  }

  static Result run(
      ParentIdentityScanner scanner,
      DocumentIdentityStore store,
      String generationId,
      long nowMs) {
    Objects.requireNonNull(scanner, "scanner");
    Objects.requireNonNull(store, "store");
    Objects.requireNonNull(generationId, "generationId");

    if (store.identityCount() > 0 && store.hasImportRecord(generationId)) {
      log.debug(
          "Document identity import already recorded for generation {}; skipping index scan",
          generationId);
      return Result.skipped();
    }

    long[] importedBatchesAndProjections = new long[3];
    DocumentFieldOps.ParentIdentityScanSummary summary =
        scanner.scan(
            BATCH_SIZE,
            batch -> {
              List<DocumentIdentityStore.ImportedIdentity> rows = new ArrayList<>(batch.size());
              for (DocumentFieldOps.StoredDocumentIdentity identity : batch) {
                if (AcceptedProjection.isProjectionIndexId(identity.docId())) {
                  importedBatchesAndProjections[2]++;
                  continue;
                }
                String normalizedKey =
                    PathNormalizer.normalizeKey(Path.of(identity.docId()));
                rows.add(
                    new DocumentIdentityStore.ImportedIdentity(
                        DocumentIdentityStore.pathHash(normalizedKey), identity.docUid()));
              }
              if (!rows.isEmpty()) {
                importedBatchesAndProjections[0] += store.importExisting(rows, nowMs);
                importedBatchesAndProjections[1]++;
              }
            });

    long pathParentsSeen = summary.parentsSeen() - importedBatchesAndProjections[2];

    store.recordImport(
        new DocumentIdentityStore.ImportRecord(
            generationId,
            nowMs,
            pathParentsSeen,
            importedBatchesAndProjections[0],
            summary.parentsSkipped()));

    if (summary.parentsSkipped() > 0) {
      log.warn(
          "Document identity import skipped {} of {} live parent documents in generation {}:"
              + " their doc_id or doc_uid docvalues are missing or blank. Each one mints a fresh"
              + " identity at its next admission, so uid-keyed links to their old identity are lost.",
          summary.parentsSkipped(),
          pathParentsSeen,
          generationId);
    }
    log.info(
        "Document identity import scanned {} serving parent documents in generation {}"
            + " ({} new identities, {} skipped, {} batches)",
        pathParentsSeen,
        generationId,
        importedBatchesAndProjections[0],
        summary.parentsSkipped(),
        importedBatchesAndProjections[1]);

    return new Result(
        true,
        pathParentsSeen,
        importedBatchesAndProjections[0],
        summary.parentsSkipped(),
        (int) importedBatchesAndProjections[1]);
  }
}
