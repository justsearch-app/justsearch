/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.SourceEnvelope;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SourceEnvelopeSparsePreservationTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  @Test void sourceWideBatchRetainsLegacyChunkSparsePostings() throws Exception {
    try (var runtime = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(directory).withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      for (String id : List.of("parent", "child")) {
        var fields = new HashMap<String, Object>(Map.of("doc_id", id, "doc_uid", "uid-" + id,
            "path", "/source.txt", "content", "needle", "collection", "documents"));
        fields.putAll(new SourceEnvelope(null, SourceEnvelope.StorageStatus.UNKNOWN, null, null).fields());
        if (id.equals("child")) {
          fields.putAll(Map.of("is_chunk", "true", "parent_doc_id", "parent", "chunk_start_char", "0",
              "chunk_end_char", "6", "chunk_content", "needle",
              "chunk_parent_content_sha256", ChunkParentRevision.sha256Hex("needle"),
              "splade", Map.of("needle", 64f), "splade_status", "COMPLETED"));
        }
        var document = runtime.session().fieldMapper.toDocument(fields, null);
        document.removeFields("splade_payload");
        runtime.session().snapshot.writer().updateDocument(new Term("doc_id", id), document);
      }
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
      int before = frequency(); assertTrue(before > 0);
      assertEquals(1, runtime.indexingCoordinator().updateDocumentsBatch(List.of(Map.entry("parent",
          new HashMap<String, Object>(new SourceEnvelope(null, SourceEnvelope.StorageStatus.NOT_OBSERVED, null, null).fields())))).updatedCount());
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
      assertEquals(before, frequency(), "fan-out must retain a legacy chunk's sparse postings");
    }
    assertTrue(frequency() > 0, "postings survive reopen");
  }

  private int frequency() throws Exception {
    try (var storage = FSDirectory.open(directory); var reader = DirectoryReader.open(storage)) {
      var searcher = new IndexSearcher(reader);
      int doc = searcher.search(new TermQuery(new Term("doc_id", "child")), 1).scoreDocs[0].doc;
      for (var leaf : reader.leaves()) {
        if (doc < leaf.docBase || doc >= leaf.docBase + leaf.reader().maxDoc()) continue;
        var terms = leaf.reader().terms(SchemaFields.SPLADE);
        if (terms == null) return 0;
        var iterator = terms.iterator();
        if (!iterator.seekExact(new org.apache.lucene.util.BytesRef("needle"))) return 0;
        var postings = iterator.postings(null, PostingsEnum.FREQS);
        return postings.advance(doc - leaf.docBase) == doc - leaf.docBase ? postings.freq() : 0;
      }
      return 0;
    }
  }
}
