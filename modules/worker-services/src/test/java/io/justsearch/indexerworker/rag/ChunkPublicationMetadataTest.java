/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.rag;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ChunkPublicationMetadataTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  private static final ChunkDocumentWriter.ParentChunkMetadata META =
      new ChunkDocumentWriter.ParentChunkMetadata("text/markdown", "text/markdown", "markdown", "en", 1000L, "docs", "uid");

  @Test void D40OversizedUtf8HeadingsCompleteReplacementAndReplayAfterReopen() throws Exception {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(0).fields());
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.CHUNK_HEADING_TEXT, "keyword", true, true, List.of(), null, null, false));
    fields.add(new FieldCatalogDef.FieldDef(SchemaFields.CHUNK_HEADING_LEVEL, "long", true, true, List.of(), null, null, false));
    var schema = IndexSchema.fromCatalog(new FieldCatalogDef("heading-publication", fields));
    for (String letter : List.of("a", "界", "😀")) {
      Path index = directory.resolve("index-" + letter.codePointAt(0));
      String content = "old valid paragraph\n".repeat(500) + "## " + letter.repeat(40000) + "\n" + "tail paragraph\n".repeat(1000);
      int total;
      try (var runtime = schema.atPath(index).withExecutorRegistrations(testLuceneExecutors()).open()) {
        runtime.commitOps().stopCommitTimer();
        for (int pass = 0; pass < 3; pass++) {
          var plan = ChunkDocumentWriter.plan(content, "text/markdown", "markdown");
          var parent = new java.util.HashMap<String, Object>(Map.of(SchemaFields.DOC_ID, "/parent",
              SchemaFields.DOC_UID, "uid", SchemaFields.PATH, "/parent", SchemaFields.CONTENT, content,
              SchemaFields.MIME_BASE, "text/markdown", SchemaFields.FILE_KIND, "markdown"));
          ChunkDocumentWriter.declare(parent, plan);
          runtime.indexingCoordinator().indexSingle(new IndexDocument(parent));
          total = pass == 1 ? ChunkDocumentWriter.regenerateChunksFromExistingParent(runtime.documentFieldOps(),
              runtime.indexingCoordinator(), "/parent", content, false)
              : ChunkDocumentWriter.replaceDeclaredChunks(runtime.indexingCoordinator(), "/parent", content, META, plan, false);
          assertEquals(plan.total(), total);
          runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
          assertEquals(total, runtime.indexCountOps().countQueryOrThrow(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, "/parent"))));
          var children = runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID, "/parent", total);
          boolean foundBounded = false;
          for (String id : children) {
            String heading = runtime.documentFieldOps().getDocumentField(id, SchemaFields.CHUNK_HEADING_TEXT);
            if (heading != null && !heading.isEmpty()) {
              assertTrue(heading.getBytes(StandardCharsets.UTF_8).length <= 32766);
              assertFalse(Character.isHighSurrogate(heading.charAt(heading.length() - 1)));
              foundBounded = true;
            }
          }
          assertTrue(foundBounded);
        }
      }
      try (var runtime = schema.atPath(index).withExecutorRegistrations(testLuceneExecutors()).open()) {
        assertTrue(runtime.indexCountOps().countByIdAndChunksStrict("/parent") > 2);
      }
    }
  }

  @Test void D42DoublingSimilarlyChunkedContentHasNearLinearPublicationAllocation() {
    var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    assertTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    String small = "## Heading\nparagraph words go here\n".repeat(6000);
    String large = small + small;
    for (int warmup = 0; warmup < 3; warmup++) {
      allocated(bean, small);
      allocated(bean, large);
    }
    long a = Long.MAX_VALUE, b = Long.MAX_VALUE;
    for (int sample = 0; sample < 3; sample++) {
      a = Math.min(a, allocated(bean, small));
      b = Math.min(b, allocated(bean, large));
    }
    assertTrue(b <= a * 2.6, "doubling content allocated " + a + " -> " + b + " bytes");
  }

  private long allocated(com.sun.management.ThreadMXBean bean, String text) {
    var fields = mock(DocumentFieldOps.class); var coordinator = mock(IndexingCoordinator.class);
    long id = Thread.currentThread().threadId(); long before = bean.getThreadAllocatedBytes(id);
    long started = System.nanoTime();
    new io.justsearch.indexerworker.loop.FileMutationFence().guard(() ->
        ChunkDocumentWriter.regenerateChunks(fields, coordinator, "/parent", text, META, false));
    long bytes = bean.getThreadAllocatedBytes(id) - before;
    org.slf4j.LoggerFactory.getLogger(getClass()).info("Metadata publication: chars={}, bytes={}, fenceNanos={}",
        text.length(), bytes, System.nanoTime() - started);
    return bytes;
  }
}
