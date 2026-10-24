/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import java.util.Map;
import org.apache.lucene.index.DirectoryReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A successful disposable canary neither publishes real work nor closes its borrowed analyzer. */
final class IngestionProbeIsolationTest extends LuceneExecutorTestBase {
  @TempDir Path temp;

  @Test
  void healthyProbeLeavesRealIndexUntouchedAndAnalyzerUsable() throws Exception {
    try (var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0))
        .atPath(temp.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      var writer = runtime.session().snapshot.writer();
      int documents = writer.getDocStats().maxDoc;
      long sequence = writer.getMaxCompletedSequenceNumber();
      boolean pending = writer.hasUncommittedChanges();
      boolean published = DirectoryReader.indexExists(runtime.session().snapshot.directory());
      runtime.indexingCoordinator().probeIngestion();
      assertEquals(documents, writer.getDocStats().maxDoc, "canary stays out of the real index");
      assertEquals(sequence, writer.getMaxCompletedSequenceNumber(), "no real document operation");
      assertEquals(pending, writer.hasUncommittedChanges(), "no real commit");
      assertEquals(published, DirectoryReader.indexExists(runtime.session().snapshot.directory()));
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "real-document", SchemaFields.DOC_UID, "real-document#0",
          SchemaFields.CONTENT, "healthy real document after canary")));
      assertEquals(documents + 1, writer.getDocStats().maxDoc, "borrowed analyzer remains usable");
    }
  }
}
